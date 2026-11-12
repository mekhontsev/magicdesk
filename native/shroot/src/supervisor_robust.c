#define _GNU_SOURCE
#include "host_policy.h"
#include "event_signal.h"
#include "guest_execution.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "namespace_broker.h"
#include "pid_namespace.h"
#include "pid_calls.h"
#include "queue_signal.h"
#include "resource_order.h"
#include "robust_futex.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_memory.h"
#include "supervisor_robust.h"
#include "supervisor_task.h"
#include "task_birth.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_child_reap.h"
#include "task_helper.h"
#include "task_quiesce.h"
#include "task_retained.h"
#include "task_robust.h"
#include "task_status.h"
#include "task_wait.h"
#include <stdlib.h>
struct retained_memory *retained_memories,*retained_creation,*retained_work;
static struct retained_memory *retained_actors[RETAINED_ACTOR_BUCKETS];
static unsigned robust_replays;
struct retained_retirement *retained_retirement;
struct md_task_quiesce *retained_barrier;

static void retained_schedule(struct retained_memory *m);
static void retained_unschedule(struct retained_memory *m);
static int robust_managed(const struct thread *t);
static void robust_register_complete(struct thread *t,long error);
static void retained_creation_finish(struct retained_memory *m,long error);
static void retained_track_actor(struct retained_memory *m);
static void robust_cleanup_complete(struct retained_memory *m,int state);
static void retained_memory_release(struct retained_memory *m);
static void retained_retirement_finish(int completed);
static int retained_retirement_member(const struct thread *t,pid_t parent);
static int retained_retirement_group_ready(pid_t parent);
static void retained_retirement_progress(void);

int retained_task_held(const struct thread *t) {
    /* Registration and terminal cleanup own the exact stopped continuation,
     * including the interval before their deferred event is replayed. */
    return t->robust.registering || t->robust.terminal;
}

struct retained_memory *retained_actor(pid_t pid) {
    for (struct retained_memory *m=retained_actors[(unsigned)pid%RETAINED_ACTOR_BUCKETS];m;m=m->indexed)
        if (m->retained.actor==pid) return m;
    return NULL;
}

int retained_child_identity(const struct retained_memory *m,pid_t *parent,pid_t *group,int *signal) {
    /* A ready private actor completed setsid and was cloned with exit_signal
     * zero. Its traced parent cannot reparent it before the terminal boundary.
     * Detach clears this borrowed pointer before the thread slot can be reused. */
    if (!m || !m->ready || !m->parent || !m->parent->pid
            || m->parent->terminal_stop || m->parent->resume_lost) return 0;
    *parent=m->parent->tgid; *group=m->retained.actor; *signal=0;
    return 1;
}

static void retained_schedule(struct retained_memory *m) {
    if (m->queued) return;
    m->queued=1; m->work_next=retained_work; retained_work=m;
}

static void retained_unschedule(struct retained_memory *m) {
    if (!m->queued) return;
    struct retained_memory **link=&retained_work;
    while (*link!=m) link=&(*link)->work_next;
    *link=m->work_next; m->work_next=NULL; m->queued=0;
}

static int robust_managed(const struct thread *t) {
    return md_app_uid_active || md_pid_namespace_depth(md_pid_identity_namespace(t->resources.pid));
}

struct retained_memory *retained_memory_get(struct thread *t) {
    if (t->memory_actor) return t->memory_actor;
    struct retained_memory *m=calloc(1,sizeof(*m));
    if (!m) return NULL;
    m->memory=t->memory; m->next=retained_memories; retained_memories=m;
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->born && p->memory==t->memory) {
        CHECK(!p->memory_actor); p->memory_actor=m; ++m->members;
    }
    CHECK(t->memory_actor==m);
    retained_schedule(m);
    return m;
}

void retained_detach(struct thread *t) {
    for (struct retained_memory *m=retained_memories;m;m=m->next)
        if (m->parent==t) m->parent=NULL;
    struct retained_memory *m=t->memory_actor;
    t->memory_actor=NULL; t->robust.head=0; t->robust.tid=0; t->robust.registering=0;
    t->robust.registering_head=0;
    if (!m) return;
    CHECK(m->members && m->terminal!=t && m->creator!=t);
    if (!--m->members) { m->memory=NULL; m->retiring=1; retained_schedule(m); }
}

static void robust_register(struct thread *t,uintptr_t head) {
    t->robust.head=head;
    t->robust.tid=(uint32_t)visible_pid(t,t->pid);
}

static void robust_register_complete(struct thread *t,long error) {
    struct user_pt_regs regs=t->robust.registers;
    t->robust.registering=0;
    if (!error) robust_register(t,t->robust.registering_head);
    t->robust.registering_head=0;
    return_task_value(t,&regs,error,t->robust.boundary);
}

long robust_query(struct thread *t,pid_t target,struct md_process_query *query) {
    if (!robust_managed(t)) return -EINVAL;
    struct thread *subject=target ? find_thread(native_pid(t,target)) : t;
    if (!subject || !subject->born || subject->terminal_stop) return -ESRCH;
    if (subject!=t && !inspect_allowed(t->pid,subject->pid,0)) return -EPERM;
    *query=(struct md_process_query){{subject->robust.head,sizeof(struct md_robust_head)}};
    return 0;
}

int robust_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *regs,enum md_task_call_boundary boundary) {
    long nr=call->nr;
    if ((nr!=SYS_set_robust_list && nr!=SYS_get_robust_list) || !robust_managed(t)) return 0;
    if (nr==SYS_get_robust_list) {
        execution_begin(t,call,regs,"robust-query-mask"); return 1;
    }
    if (call->args[1]!=sizeof(struct md_robust_head)) {
        return_task_value(t,regs,-EINVAL,boundary); return 1;
    }
    if (!call->args[0] || (t->terminal_observation && t->memory_actor && t->memory_actor->ready)) {
        robust_register(t,call->args[0]);
        return_task_value(t,regs,0,boundary); return 1;
    }
    /* Only creating the retained executor and arming terminal observation
     * needs a real stop; later registrations merely replace an owned value. */
    if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
    t->robust.registers=*regs; t->robust.boundary=boundary;
    t->robust.registering_head=call->args[0];
    t->terminal_observation=1; t->trace_options_dirty=1;
    int error=debugger_options(t->pid,t->debug.options);
    if (error) { robust_register_complete(t,error); return 1; }
    struct retained_memory *m=retained_memory_get(t);
    if (!m) { robust_register_complete(t,-ENOMEM); return 1; }
    if (m->ready) { robust_register_complete(t,0); return 1; }
    t->robust.registering=1;
    retained_schedule(m);
    /* EVENT_WAIT: one mm's first registration creates its private executor;
     * other registrations wait for the same birth, never another actor. */
    CHECK(!md_event_signal(fd_ready));
    return 1;
}

static void retained_creation_finish(struct retained_memory *m,long error) {
    struct thread *t=m->creator;
    if (m->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    m->checkpoint_suspended=0; m->creator=NULL; retained_creation=NULL;
    if (m->order.queue && md_resource_order_cancel(&m->order)==1)
        CHECK(!md_resource_order_complete(&m->order));
    if (retained_barrier) CHECK(!md_task_quiesce_release(retained_barrier));
    m->error=error; m->ready=!error;
    if (t && !m->retained.parent_terminal) {
        t->kernel_stopped=1;
        t->robust.boundary=m->retained.parent.boundary;
    }
    if (error) {
        /* A cancelled birth can still own a native child. Keep its exact PID
         * until the retirement owner has removed both wait and signal state. */
        m->retiring=1;
        for (struct thread *p=threads;p;p=p->next) if (p->memory_actor==m) {
            p->memory_actor=NULL; CHECK(m->members); --m->members;
        }
        m->memory=NULL;
    }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->robust.registering
            && (p->memory_actor==m || (error && p->memory==t->memory))) {
        if (p==t && m->retained.parent_terminal) p->robust.registering=0;
        else robust_register_complete(p,error);
    }
    task_action_unpark();
}

void retained_creation_begin(struct retained_memory *m,struct thread *t,
        const struct user_pt_regs *regs,enum md_task_call_boundary call) {
    CHECK(!retained_creation && !m->ready && !m->creator && !m->retiring);
    m->creator=t; m->parent=t; retained_creation=m;
    t->terminal_observation=1; t->trace_options_dirty=1;
    int error=debugger_options(t->pid,t->debug.options);
    if (error) { retained_creation_finish(m,error); return; }
    int boundary=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (boundary<0) { retained_creation_finish(m,boundary); return; }
    m->checkpoint_suspended=1;
    if (boundary && call==MD_TASK_CALL_ENTRY) call=MD_TASK_CALL_EMULATED_ENTRY;
    error=md_task_retained_begin(&m->retained,native_trace,t->pid,call,regs,&abi);
    if (error && !m->retained.parent.stage) retained_creation_finish(m,error);
    else if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
    else t->kernel_stopped=0;
}

static void retained_track_actor(struct retained_memory *m) {
    if (!m->retained.actor || m->wait.pid) return;
    unsigned bucket=(unsigned)m->retained.actor%RETAINED_ACTOR_BUCKETS;
    m->indexed=retained_actors[bucket]; retained_actors[bucket]=m;
    md_task_wait_track(&task_wait,&m->wait,m->retained.actor);
    int initial=helper_newborn(m->retained.actor);
    if (initial==MD_TASK_BIRTH_READY) {
        int state=md_task_retained_event(&m->retained,m->retained.actor,
            (PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f);
        CHECK(state>=0);
    } else if (initial==MD_TASK_BIRTH_EXIT) {
        m->error=-ESRCH;
    } else CHECK(initial==MD_TASK_BIRTH_PENDING);
}

static void robust_cleanup_complete(struct retained_memory *m,int state) {
    if (state<0) {
        fprintf(stderr,"Shroot: robust cleanup actor=%d error=%d\n",m->retained.actor,state);
        cancel_tree(125); return;
    }
    if (state!=1) { m->cleaning=state==2 ? 2 : 1; return; }
    struct thread *t=m->terminal;
    t->robust.subject->robust.head=0;
    t->robust.subject=NULL; t->robust.terminal=2; ++robust_replays;
    m->terminal=NULL; m->cleaning=0;
    m->cleanup=(struct md_task_robust){0};
}

void retained_progress(void) {
    if (!retained_work) return;
    retained_retirement_progress();
    for (struct retained_memory *m=retained_work,*next;m;m=next) {
        next=m->work_next;
        if (m->ready && !m->cleaning && !m->pending && !m->retiring) {
            retained_unschedule(m); continue;
        }
        if (m->cleaning==2) robust_cleanup_complete(m,md_task_robust_progress(&m->cleanup));
        if (m->pending && m->ready && !m->cleaning && !m->retiring && !quiescence_busy(NULL)) {
            for (struct thread *t=threads;t;t=t->next) if (t->pid && t->memory_actor==m && t->robust.terminal==1) {
                --m->pending;
                m->terminal=t;
                robust_cleanup_complete(m,md_task_robust_begin(&m->cleanup,native_trace,m->retained.actor,
                    &abi,t->robust.subject->robust.head,t->robust.subject->robust.tid));
                break;
            }
        }
        if (m->ready || m->retiring || m->creator || retained_barrier || quiescence_busy(NULL)) continue;
        struct thread *t;
        for (t=threads;t;t=t->next) if (t->pid && t->memory_actor==m && t->robust.registering) break;
        if (!t) continue;
        if (!m->order.queue) CHECK(md_memory_order_begin(t->memory,&m->order,fd_wakeup)>=0);
        if (md_resource_order_state(&m->order)!=MD_RESOURCE_ORDER_READY) continue;
        CHECK(!md_resource_order_start(&m->order));
        retained_creation_begin(m,t,&t->robust.registers,t->robust.boundary);
    }
}

static void retained_memory_release(struct retained_memory *m) {
    CHECK(!m->members && !m->creator && !m->cleaning);
    struct retained_memory **link=&retained_memories;
    while (*link && *link!=m) link=&(*link)->next;
    CHECK(*link==m); *link=m->next;
    retained_unschedule(m);
    if (m->wait.pid) {
        link=&retained_actors[(unsigned)m->retained.actor%RETAINED_ACTOR_BUCKETS];
        while (*link!=m) link=&(*link)->indexed;
        *link=m->indexed;
    }
    md_task_wait_untrack(&task_wait,&m->wait);
    free(m);
}

static void retained_retirement_finish(int completed) {
    struct retained_retirement *r=retained_retirement;
    struct thread *t=r->actor;
    if (r->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    if (r->borrowed) CHECK(!md_task_quiesce_return(retained_barrier,t->pid));
    if (r->broker_borrowed) md_broker_native_return(broker,t->pid);
    CHECK(!md_buffer_release(&r->buffer));
    if (r->order.queue && md_resource_order_cancel(&r->order)==1)
        CHECK(!md_resource_order_complete(&r->order));
    CHECK(!md_task_quiesce_release(retained_barrier));
    if (r->synthetic) t->kernel_stopped=0;
    if (completed) retained_memory_release(r->memory);
    free(r); retained_retirement=NULL;
    task_action_unpark();
}

static int retained_retirement_member(const struct thread *t,pid_t parent) {
    return t->pid && t->born && (t->tgid==parent || md_task_parent(t->pid)==parent);
}

static int retained_retirement_group_ready(pid_t parent) {
    for (struct thread *t=threads;t;t=t->next) if (t->pid && t->born
            && (t->terminal_stop || retained_task_held(t) || md_task_helpers_group_busy(helpers,t->tgid))
            && retained_retirement_member(t,parent)) return 0;
    return 1;
}

static void retained_retirement_progress(void) {
    struct retained_retirement *r=retained_retirement;
    if (r) {
        if (!r->borrowed && !md_task_quiesce_ready(retained_barrier)) return;
        struct retained_memory *m=r->memory;
        struct thread *t=r->actor;
        if (!r->borrowed) {
            struct md_task_quiesce_stop stop;
            CHECK(!md_task_quiesce_borrow(retained_barrier,t->pid,&stop));
            r->borrowed=1; r->synthetic=stop.kind==MD_TASK_QUIESCE_SYNTHETIC;
            if (stop.kind==MD_TASK_QUIESCE_EVENT) {
                unsigned event=(unsigned)stop.status>>16;
                if (event!=PTRACE_EVENT_SECCOMP && (event || WSTOPSIG(stop.status)!=(SIGTRAP|0x80))) {
                    /* No child has been killed yet. Replay a real signal or
                     * lifecycle event before borrowing the next boundary. */
                    retained_retirement_finish(0); return;
                }
            }
        }
        if (!r->stage) {
            CHECK(md_resource_order_state(&r->order)==MD_RESOURCE_ORDER_RUNNING);
            CHECK(!md_buffers_take_reserved(md_memory_buffers(t->memory),&r->buffer));
            r->peer=md_memory_buffer_address(t->memory,&r->buffer);
            m->killed=1;
            CHECK(!kill(m->retained.actor,SIGKILL) || errno==ESRCH);
            if (m->exit_held) CHECK(!native_trace(PTRACE_CONT,m->retained.actor,NULL,NULL) || errno==ESRCH);
            r->stage=1;
        }
        if (!m->dead || r->stage!=1) return;
        struct user_pt_regs saved;
        struct ptrace_syscall_info info={0};
        CHECK(registers(t->pid,&saved));
        CHECK(native_trace(PTRACE_GET_SYSCALL_INFO,t->pid,(void *)sizeof(info),&info)>=0);
        int checkpoint=task_checkpoint_borrow(t,&r->checkpoint_suspended);
        CHECK(checkpoint>=0);
        enum md_task_call_boundary boundary=(enum md_task_call_boundary)info.op;
        if (checkpoint && boundary==MD_TASK_CALL_ENTRY) boundary=MD_TASK_CALL_EMULATED_ENTRY;
        /* Direct children are held with the parent group: a private notice
         * cannot coalesce a new real terminal notice during exact reaping. */
        CHECK(!md_task_child_reap_begin(&r->reap,native_trace,t->pid,t->tgid,m->retained.actor,
            boundary,&saved,abi.task_call,abi.task_return,&r->scratch,r->peer,NULL));
        r->stage=2; t->kernel_stopped=0;
        return;
    }
    if (quiescence_busy(NULL) || retained_barrier) return;
    for (struct retained_memory *m=retained_work,*next;m;m=next) {
        next=m->work_next;
        if (!m->retiring || m->members || m->cleaning || m->creator) continue;
        if (!m->retained.actor) { retained_memory_release(m); continue; }
        long parent=md_task_parent(m->retained.actor);
        if (parent==getpid() || (m->dead && parent==-ENOENT)) {
            if (m->dead) { retained_memory_release(m); continue; }
            if (!m->killed) {
                m->killed=1;
                CHECK(!kill(m->retained.actor,SIGKILL) || errno==ESRCH);
                if (m->exit_held) CHECK(!native_trace(PTRACE_CONT,m->retained.actor,NULL,NULL) || errno==ESRCH);
            }
            continue;
        }
        struct thread *actor=NULL;
        for (struct thread *t=threads;t;t=t->next)
            if (t->pid && t->tgid==parent && t->entered && task_action_borrowable(t,NULL)) { actor=t; break; }
        /* Existing helper/exit owners finish before the parent/children
         * barrier. A resumed exit cannot acknowledge INTERRUPT; in particular
         * a zombie leader is not waitable until its remaining threads exit.
         * Holding those threads here would prevent the event we await. */
        if (!actor || !retained_retirement_group_ready((pid_t)parent)) continue;
        int stopped=actor->kernel_stopped;
        int borrowed=md_broker_native_borrow(broker,actor->pid,&stopped);
        if (borrowed<0) continue;
        r=calloc(1,sizeof(*r)); CHECK(r);
        r->memory=m; r->actor=actor; r->broker_borrowed=borrowed;
        /* Never stop writers while waiting behind their memory transaction.
         * Retirement can retry at the next owner completion; no private child
         * has been killed or exposed to the parent's wait/signal state yet. */
        CHECK(md_memory_order_begin(actor->memory,&r->order,fd_wakeup)>=0);
        if (md_resource_order_state(&r->order)!=MD_RESOURCE_ORDER_READY) {
            CHECK(!md_resource_order_cancel(&r->order));
            if (borrowed) md_broker_native_return(broker,actor->pid);
            free(r); continue;
        }
        CHECK(!md_resource_order_start(&r->order));
        retained_retirement=r;
        retained_barrier=md_task_quiesce_new(quiesce_trace); CHECK(retained_barrier);
        CHECK(!quiesce_task_add(retained_barrier,actor,stopped));
        for (struct thread *t=threads;t;t=t->next) if (t!=actor && retained_retirement_member(t,(pid_t)parent))
            CHECK(!quiesce_task_add(retained_barrier,t,0));
        CHECK(!md_task_quiesce_stop(retained_barrier));
        return;
    }
}

int retained_barrier_event(pid_t pid,int status,const struct rusage *usage) {
    struct retained_retirement *r=retained_retirement;
    if ((!retained_creation && !r) || !md_task_quiesce_contains(retained_barrier,pid)) return 0;
    unsigned event=(unsigned)status>>16;
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) {
        CHECK(!md_task_quiesce_terminal(retained_barrier,pid,status,usage));
        if (r && (!r->stage || pid==r->actor->pid)) {
            if (pid==r->actor->pid) { r->borrowed=0; r->synthetic=0; }
            /* Before killing the private child, yield every terminal event.
             * exec may be waiting in de_thread for a sibling held here; its
             * INTERRUPT cannot complete until that sibling's exit is replayed.
             * A dying actor's child can be reparented: retain its exact PID for
             * the next real parent, without restoring dead registers. */
            retained_retirement_finish(0);
        }
        return 1;
    }
    if (r && pid==r->actor->pid && r->stage==2) {
        if (task_call_signal(r->actor,&r->reap.call,status)) return 1;
        int state=md_task_child_reap_event(&r->reap,status);
        CHECK(state>=0);
        if (state==1) { r->actor->kernel_stopped=1; retained_retirement_finish(1); }
    } else CHECK(!quiesce_event(retained_barrier,pid,status,usage));
    return 1;
}

int retained_runnable(void) {
    for (struct retained_memory *m=retained_work;m;m=m->work_next) if (m->cleaning==2) return 1;
    return 0;
}

pid_t retained_take(int *status,struct rusage *usage) {
    if (retained_barrier && !retained_creation && !retained_retirement) {
        pid_t pid=md_task_quiesce_take(retained_barrier,status,usage);
        if (pid) return pid;
        md_task_quiesce_destroy(retained_barrier); retained_barrier=NULL;
        CHECK(!md_event_signal(fd_ready));
    }
    if (robust_replays) for (struct thread *t=threads;t;t=t->next) if (t->pid && t->robust.terminal==2) {
        --robust_replays;
        *status=t->robust.status; if (usage) *usage=t->robust.usage;
        if (filesystem) memory_completions_pending=1;
        t->robust.terminal=0; t->robust.replay=1; return t->pid;
    }
    return 0;
}

int retained_event(pid_t pid,int status,const struct rusage *usage) {
    unsigned event=(unsigned)status>>16;
    struct retained_memory *m=retained_actor(pid);
    struct retained_memory *birth=retained_creation;
    if (birth && (pid==birth->retained.parent.actor || m==birth)) {
        struct thread *t=birth->creator;
        if (!m && task_call_signal(t,&birth->retained.parent,status)) return 1;
        int terminal=!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC;
        int state=md_task_retained_event(&birth->retained,pid,status);
        retained_track_actor(birth);
        if (birth->retained.parent_terminal || (terminal && m==birth)) {
            if (m==birth) birth->error=-ESRCH;
            retained_creation_finish(birth,-ESRCH);
            if (!m) return 0;
        } else if (state==-EAGAIN) {
            if (!m && task_call_signal(t,&birth->retained.parent,status)) return 1;
            if (!m) return 0;
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL)); return 1;
        } else if (state<0) {
            if (state==-ESRCH) { if (!m) t->resume_lost=1; return 1; }
            retained_creation_finish(birth,state); return 1;
        } else {
            if (state==1) retained_creation_finish(birth,birth->error);
            return 1;
        }
    }
    if (m) {
        if (!WIFSTOPPED(status)) { m->dead=1; return 1; }
        if (event==PTRACE_EVENT_EXIT) {
            /* The real parent's wait/signalfd must be held before ptrace
             * releases a private child to its final exit and SIGCHLD. */
            m->exit_held=1;
            if (!m->retiring) {
                fprintf(stderr,"Shroot: lost retained memory executor %d\n",pid);
                exit(125);
            }
            if (m->killed) CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL) || errno==ESRCH);
            return 1;
        }
        if (m->cleaning) {
            int state=md_task_robust_event(&m->cleanup,status);
            if (state!=-EAGAIN) { robust_cleanup_complete(m,state); return 1; }
        }
        /* No guest signal handler runs in the stack/TLS-free private actor. */
        if (m->retiring && m->killed) { kill(pid,SIGKILL); native_trace(PTRACE_CONT,pid,NULL,NULL); }
        else if (m->cleaning) CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL));
        return 1;
    }
    struct thread *t=find_thread(pid);
    if (!t) return 0;
    if (t->robust.replay) { t->robust.replay=0; return 0; }
    if (!WIFSTOPPED(status) || (event!=PTRACE_EVENT_EXIT && event!=PTRACE_EVENT_EXEC)) return 0;
    struct thread *subject=t;
    if (event==PTRACE_EVENT_EXEC) {
        unsigned long old;
        if (native_trace(PTRACE_GETEVENTMSG,pid,NULL,&old)<0) { CHECK(errno==ESRCH); return 1; }
        if (old && old!=(unsigned)pid) subject=find_thread((pid_t)old);
    }
    if (!subject || !subject->robust.head) return 0;
    CHECK(subject->memory_actor && subject->memory_actor->ready && t->memory_actor==subject->memory_actor);
    CHECK(!t->robust.terminal);
    ++subject->memory_actor->pending; retained_schedule(subject->memory_actor);
    t->kernel_stopped=1; t->robust.subject=subject;
    t->robust.terminal=1; t->robust.status=status;
    t->robust.usage=usage ? *usage : (struct rusage){0};
    return 1;
}

void retained_cancel(void) {
    for (struct retained_memory *m=retained_memories;m;m=m->next)
        if (m->retained.actor && !m->dead) kill(m->retained.actor,SIGKILL);
}

void retained_destroy(void) {
    if (retained_retirement) {
        struct retained_retirement *r=retained_retirement;
        if (r->borrowed) md_task_quiesce_return(retained_barrier,r->actor->pid);
        md_buffer_release(&r->buffer);
        if (r->order.queue && md_resource_order_cancel(&r->order)==1) md_resource_order_complete(&r->order);
        free(r); retained_retirement=NULL;
    }
    md_task_quiesce_destroy(retained_barrier); retained_barrier=NULL;
    while (retained_memories) {
        struct retained_memory *m=retained_memories; retained_memories=m->next;
        md_task_wait_untrack(&task_wait,&m->wait);
        if (m->order.queue && md_resource_order_cancel(&m->order)==1) md_resource_order_complete(&m->order);
        free(m);
    }
    retained_creation=NULL;
    retained_work=NULL; robust_replays=0;
    memset(retained_actors,0,sizeof(retained_actors));
}
