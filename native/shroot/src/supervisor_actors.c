#define _GNU_SOURCE
#include "supervisor_io.h"
#include "clone_call.h"
#include "cooperative_channel.h"
#include "event_signal.h"
#include "fd_client.h"
#include "fd_filter_program.h"
#include "fd_mutation.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "fs_buffer_service.h"
#include "fs_continuation.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "linux_abi.h"
#include "namespace_broker.h"
#include "pending.h"
#include "pid_filter_program.h"
#include "proc_paths.h"
#include "process_limits.h"
#include "queue_signal.h"
#include "resource_order.h"
#include "signal_delivery.h"
#include "signal_protocol.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_readiness.h"
#include "supervisor_robust.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "task_birth.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_filter.h"
#include "task_helper.h"
#include "task_quiesce.h"
#include "task_registry.h"
#include "task_signal_control.h"
#include <linux/close_range.h>
#include <stdlib.h>
#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif
struct thread *broker_events;
static unsigned task_action_calls;
static struct md_pending_queue task_action_queue;
static struct md_pending_queue task_continuations;
static int task_continuations_ready;
struct task_action *task_action_execution;
struct task_action *loan_retirements;
struct md_task_quiesce *task_action_barrier;
static int task_action_releasing;

static void task_action_release(struct task_action *owner);
static void task_action_finish(struct task_action *owner,long result);
static void loan_failed(struct task_action *owner,long result);
static long loan_close_finish(struct task_action *owner,long result);
static void task_action_call_complete(long result,int retry);
static void capture_borrowed_complete(void *context,struct md_task_helper_result result);
static void task_action_call_start(struct task_action *owner);
static int selector_peer_ready(struct thread *peer,unsigned kind,int number);
static int task_files_queued(const struct thread *p);
static int task_action_native_borrow(struct task_action *owner,struct thread *actor,int *stopped);
static void task_action_progress(struct md_pending_node *node,void *context);
static int loan_borrow(struct task_action *owner,struct thread *p,int *alive,int *stopped);
static void loan_start(struct task_action *owner);
static void fd_loan_closed(struct thread *t,long result);
static int fd_loan_evict(struct thread *mutator,struct md_fd_loan *loan,int number);

struct md_task_call *task_action_call(struct task_action *action) {
    return action->loan || action->capture ? &action->native : &action->filter.task;
}

/* Cold transactions share stop ownership. Only retirement of a mapping's own
 * temporary FD may nest inside its barrier; unrelated guest work stays runnable. */
struct md_task_quiesce **quiescence_barrier(const struct mapping_owner *parent) {
    if (retained_creation || retained_retirement) return &retained_barrier;
    if (policy_installer) return &policy_barrier;
    if (memory_execution) return &memory_barrier;
    if (mapping_execution && mapping_execution!=parent) return &mapping_barrier;
    if (task_action_execution || task_action_releasing) return &task_action_barrier;
    return NULL;
}

int quiescence_busy(const struct mapping_owner *parent) {
    return quiescence_barrier(parent)!=NULL;
}

int buffer_admission_quiescing(pid_t pid) {
    return md_task_quiesce_interrupting(retained_barrier,pid)
        || md_task_quiesce_interrupting(policy_barrier,pid)
        || md_task_quiesce_interrupting(memory_barrier,pid)
        || md_task_quiesce_interrupting(mapping_barrier,pid)
        || md_task_quiesce_interrupting(task_action_barrier,pid);
}

long quiesce_trace(int request,pid_t pid,void *address,void *data) {
    if (trace_continues(request)) {
        struct thread *t=find_thread(pid);
        if (t) t->interrupt_pending=0;
        /* A barrier and guest debugger can share one INTERRUPT stop. Release
         * through normal admission so the debugger receives its pending stop
         * before guest execution, after the barrier has relinquished ownership. */
        if (t && md_debugger_traced(debugger,pid)) {
            CHECK(!data);
            t->memory_op.resume=1; t->kernel_stopped=1;
            task_continuation_defer(t);
            return 0;
        }
    }
    long result=trace_continues(request)
        ? md_task_checkpoint_continue(&checkpoints,request,pid)
        : native_trace(request,pid,address,data);
    if (result>=0 && request==PTRACE_INTERRUPT) {
        if (statistics) ++statistics->quiesce_interrupts;
        struct thread *t=find_thread(pid);
        buffer_admission_yield(t);
        SUPERVISOR_TRACE("PROBE quiesce-interrupt pid=%d phase=%d\n",pid,t ? (int)t->phase : -1);
        /* Quiescence can race an internal poll's entry stop and interrupt the
         * poll after the barrier releases. Retain the service wake through
         * both entry and wait; it must not become application EINTR.
         * Ordinary guest syscalls keep kernel restart semantics. */
        if (t && (t->phase==IPC_WAIT_ENTRY || t->phase==IPC_WAITING)) t->io.waking=1;
        if (t && (t->phase==SOCKET_WAIT_ENTRY || t->phase==SOCKET_WAITING
                || t->phase==EPOLL_CALL || readiness_waiting(t))) t->io.socket_waking=1;
        if (t && (t->phase==DEBUG_PARK_ENTRY || t->phase==DEBUG_PARK_EXIT)) t->debug.wait_waking=1;
        if (t && t->signals.control) md_task_signal_control_quiesced(&t->signals.control->control);
    }
    /* Only barrier release restores this unchanged guest continuation.
     * Injected helper execution must retain the broker's stopped state. */
    if (result>=0 && trace_continues(request))
        md_broker_resumed(broker,pid);
    return result;
}

int quiesce_event(struct md_task_quiesce *barrier,pid_t pid,int status,const struct rusage *usage) {
    int child=md_task_quiesce_event(barrier,pid,status,usage);
    if (child<=0) return child;
    struct thread *t=find_thread((pid_t)child);
    /* The dispatcher can own the child's initial stop before this barrier
     * exists. Until the parent event is replayed, its memory/files membership
     * is unknown, so participant selection cannot discover it by those owners.
     * Retain that existing stop, without replaying it or resuming the child. */
    if (!t || t->born || !t->initial_stop || !t->kernel_stopped) return 0;
    SUPERVISOR_TRACE("PROBE quiesce-newborn parent=%d child=%d observed=dispatcher\n",pid,t->pid);
    return md_task_quiesce_add(barrier,t->pid,1,0);
}

int quiesce_task_add(struct md_task_quiesce *barrier,struct thread *t,int stopped) {
    /* A vfork parent is kernel-suspended until its child releases the mm;
     * TRACEVFORKDONE then stops it before any userspace continuation.
     * Waiting for another INTERRUPT can deadlock the child's own continuation.
     * Existing interrupt/birth owners supply their event without another request. */
    return md_task_quiesce_add(barrier,t->pid,
        stopped || t->kernel_stopped || t->listening || t->process_op.vfork_child,
        t->interrupt_pending || birth_stop_pending(t) ? 0 : t->kernel_resume);
}

struct task_action *task_action_new(struct thread *t) {
    CHECK(!t->action);
    struct task_action *owner=calloc(1,sizeof(*owner));
    if (!owner) return NULL;
    owner->requester=t; t->action=owner; ++task_action_calls;
    md_pending_add(&task_action_queue,&owner->pending);
    return owner;
}

static void task_action_release(struct task_action *owner) {
    CHECK(owner!=task_action_execution);
    md_pending_cancel(&owner->pending);
    CHECK(!md_buffer_release(&owner->buffer));
    md_fd_selector_release(owner->group);
    md_fd_selector_release(owner->selected_group);
    md_process_member_release(owner->capture_capacity);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    if (owner->loan) {
        struct task_action **link=&loan_retirements;
        while (*link && *link!=owner) link=&(*link)->next;
        CHECK(*link==owner); *link=owner->next;
        md_fd_loan_release(owner->loan);
        md_fd_origins_unpin(owner->loan_table);
    }
    if (owner->retired) owner->retired(owner->retired_context,-ECANCELED);
    if (owner->capture) {
        owner->capture->result.value=-ECANCELED;
        capture_complete(owner->capture,owner->capture->result);
    }
    CHECK(task_action_calls); --task_action_calls; free(owner);
}

void task_action_cleanup(void) {
    /* Tracees have been reaped; only metadata pins and borrowed spans remain. */
    struct task_action *active=task_action_execution;
    if (active) {
        if (active->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,active->actor->pid);
        if (active->requester) active->requester->action=NULL;
        if (active->borrowed) CHECK(!md_task_quiesce_return(
            active->outer_barrier ? active->outer_barrier : task_action_barrier,active->actor->pid));
        if (active->broker_borrowed) {
            md_broker_native_return(broker,active->actor->pid); active->broker_borrowed=0;
        }
        if (task_action_barrier) CHECK(!md_task_quiesce_release(task_action_barrier));
        task_action_execution=NULL;
        task_action_release(active);
    }
    while (loan_retirements) {
        if (loan_retirements->requester) loan_retirements->requester->action=NULL;
        task_action_release(loan_retirements);
    }
}

static void task_action_finish(struct task_action *owner,long result) {
    struct thread *t=owner->requester;
    void (*complete)(struct thread *,long)=owner->complete;
    struct user_pt_regs r=owner->continuation;
    pid_t waiter=owner->loan_waiter;
    struct md_broker_retirement retirement=owner->retirement;
    void (*retired)(void *,long)=owner->retired;
    void *retired_context=owner->retired_context;
    struct capture_owner *capture=owner->capture; owner->capture=NULL;
    owner->retired=NULL;
    if (t) { retry_clear(t,RETRY_TASK_ACTION); t->action=NULL; }
    task_action_release(owner);
    if (waiter) readiness_loan_closed(waiter,result);
    if (retirement.complete) retirement.complete(retirement.context,(int)result);
    if (retired) retired(retired_context,result);
    if (capture) {
        if (result<0) capture->result.value=result;
        capture_complete(capture,capture->result);
    }
    if (t && !retired && !capture) {
        if (complete) complete(t,result);
        else {
            r.regs[0]=result;
            if (set_registers(t->pid,&r)) resume(t->pid,0);
        }
    }
    adapter_progress();
}

void task_action_discard(struct thread *t) {
    struct task_action *owner=t->action;
    if (!owner) return;
    if (owner->resident) {
        struct md_entry_native *native=&t->entry.control->native;
        unsigned state=entry_effect_reconcile(t);
        struct md_fd_mutation close={.nr=native->number,
            .args={native->args[0],native->args[1],native->args[2]}};
        if (state==MD_NATIVE_DONE) (void)loan_close_finish(owner,native->result);
        else if (state!=MD_NATIVE_CANCELLED) CHECK(!md_fd_mutation_abandon(&close,owner->loan_table));
        t->entry.effect=NULL; t->entry.waiting=0; owner->resident=0;
    }
    t->action=NULL; owner->requester=NULL;
    if (owner->loan) { if (!owner->retired) owner->loan_turn=NULL; return; }
    /* A foreign actor can still be using this payload. Its exact completion
     * retires the detached request, independently of requester death. */
    if (owner!=task_action_execution) task_action_release(owner);
}

void task_continuation_defer(struct thread *t) {
    md_pending_add(&task_continuations,&t->memory_op.continuation);
}

void task_continuation_discard(struct thread *t) {
    md_pending_cancel(&t->memory_op.continuation);
    free(t->memory_op.notification); t->memory_op.notification=NULL;
    t->memory_op.resume=t->memory_op.signal=0;
}

static void task_continuation_run(struct md_pending_node *node,void *context) {
    (void)context;
    struct thread *t=(void *)((char *)node - offsetof(struct thread,memory_op.continuation));
    CHECK(t->pid);
    if (t->memory_op.notification) {
        struct seccomp_notif *n=t->memory_op.notification; t->memory_op.notification=NULL;
        handle_notification(n); free(n);
    }
    if (t->memory_op.resume) {
        int signal=t->memory_op.signal; t->memory_op.resume=t->memory_op.signal=0;
        resume(t->pid,signal);
    }
}

void task_continuations_progress(void) {
    if (!task_continuations_ready || task_continuations.running) return;
    task_continuations_ready=0;
    /* Each retained continuation runs once. A still-owned barrier requeues it;
     * a recursive barrier release is serviced by the next event-loop turn. */
    CHECK(!md_pending_dispatch(&task_continuations,task_continuation_run,NULL));
}

void task_action_unpark(void) {
    task_continuations_ready=1;
    task_continuations_progress();
    /* A resource can become ready while a barrier prevents its continuation.
     * Its eventfd was already acknowledged then; releasing the barrier is the
     * new readiness event, even when no resource owner has anything left to signal. */
    if (filesystem) memory_completions_pending=1;
    CHECK(!md_event_signal(fd_ready));
}

static void loan_failed(struct task_action *owner,long result) {
    /* Retain custody until kernel teardown when retirement cannot be verified. */
    if (cancelling) return;
    fprintf(stderr,"Shroot: cannot retire temporary FD %d: %s\n",owner->number,strerror((int)-result));
    cancel_tree(125);
}

static int loan_close_denied(long number,long result) {
    return number==SYS_close && (result==-EPERM || result==-EACCES || result==-ENOSYS);
}

static long loan_close_finish(struct task_action *owner,long result) {
    struct md_fd_mutation close={.nr=owner->native.arguments.number,
        .args={owner->native.arguments.value[0],owner->native.arguments.value[1],owner->native.arguments.value[2]}};
    CHECK(!md_fd_mutation_finish(&close,owner->loan_table,result));
    struct md_fd_order *turn=owner->loan_turn ? owner->loan_turn : &owner->files;
    int remaining=md_fd_origins_loan_matches(turn,owner->number,owner->loan);
    if (remaining<0) return remaining;
    if (!remaining) return 0;
    if (result==-EBADF) return md_fd_origins_close_range(owner->loan_table,owner->number,owner->number,0);
    return result;
}

static void loan_entry_complete(struct thread *t,long result) {
    struct task_action *owner=t->action;
    CHECK(owner && owner->resident && owner->loan);
    owner->resident=0;
    if (t->entry.effect_cancelled) { loan_start(owner); return; }
    if (loan_close_denied(owner->native.arguments.number,result)) {
        owner->native.arguments=(struct md_task_call_arguments){.number=SYS_close_range,
            .value={(unsigned long)owner->number,(unsigned long)owner->number,0}};
        uint64_t args[6]={owner->number,owner->number,0};
        owner->resident=1;
        entry_effect(t,SYS_close_range,args,loan_entry_complete); return;
    }
    result=loan_close_finish(owner,result);
    if (result<0) { loan_failed(owner,result); return; }
    task_action_finish(owner,result);
}

static void task_action_call_complete(long result,int retry) {
    struct task_action *owner=task_action_execution;
    struct thread *actor=owner->actor;
    if (owner->capture_capacity) {
        int error=md_process_nofile_release(owner->capture_capacity,actor->pid);
        if (error==-ESRCH) {
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer!=actor && peer->pid && peer->tgid==actor->tgid && !peer->terminal_stop) {
                    error=md_process_nofile_release(owner->capture_capacity,peer->pid);
                    if (error!=-ESRCH) break;
                }
        }
        CHECK(!error || error==-ESRCH);
        md_process_member_release(owner->capture_capacity); owner->capture_capacity=NULL;
    }
    if (owner->checkpoint_suspended) {
        md_task_checkpoint_restore(&checkpoints,actor->pid); owner->checkpoint_suspended=0;
    }
    if (owner->loan) {
        if (!retry && result!=-ESRCH) result=loan_close_finish(owner,result);
    } else if (!owner->capture && !result && !retry) {
        if (owner->pid_filter) {
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer->tgid==actor->tgid) peer->process_op.filter=1;
        } else result=md_fd_selector_installed(owner->group,owner->kind,
            owner->selector_number,owner->selector_instructions);
        if (!result) for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer->tgid==actor->tgid) cooperative_routes_publish(peer);
    }
    if (owner->borrowed) CHECK(!md_task_quiesce_return(
        owner->outer_barrier ? owner->outer_barrier : task_action_barrier,actor->pid));
    if (owner->broker_borrowed) {
        md_broker_native_return(broker,actor->pid); owner->broker_borrowed=0;
    }
    if (task_action_barrier) CHECK(!md_task_quiesce_release(task_action_barrier));
    if (owner->synthetic && !owner->outer_barrier) actor->kernel_stopped=0;
    CHECK(!md_buffer_release(&owner->buffer));
    md_fd_selector_release(owner->group); owner->group=NULL;
    owner->actor=NULL; owner->borrowed=owner->synthetic=0; owner->filter=(struct md_task_filter){0};
    task_action_releasing=1;
    task_action_execution=NULL;
    task_action_unpark();
    task_action_releasing=0;
    if (owner->loan) {
        if (retry || result==-ESRCH) return;
        if (result<0) {
            loan_failed(owner,result);
            return;
        }
        task_action_finish(owner,result); return;
    }
    if (owner->capture && !retry) { task_action_finish(owner,result); return; }
    if (!owner->requester || (result<0 && !retry)) task_action_finish(owner,result);
    else if (result>0) {
        if (!owner->remote && !owner->selected_group && !owner->pid_filter) task_action_finish(owner,result);
        else {
            owner->rejected_group=actor->tgid; owner->rejected_task=(pid_t)result;
            retry_wait(owner->requester,RETRY_TASK_ACTION);
        }
    } else if ((owner->selected_group || owner->pid_filter) && !retry) task_action_finish(owner,0);
    else retry_wait(owner->requester,RETRY_TASK_ACTION);
}

static void capture_borrowed_complete(void *context,struct md_task_helper_result result) {
    struct task_action *owner=context;
    CHECK(owner==task_action_execution && owner->capture);
    owner->actor->kernel_stopped=1;
    owner->capture->result=result;
    task_action_call_complete(result.value,0);
}

int task_checkpoint_borrow(struct thread *actor,int *owned) {
    /* A parked signal wait already owns checkpoint decoding, but its native
     * call can be restoring a SYSEMU entry when interrupted. The decoder's
     * suspended snapshot predates that call; the live resume owns skip state. */
    if (signal_control_parked(actor) && actor->signals.control->checkpoint_suspended)
        return actor->kernel_resume==PTRACE_SYSEMU;
    int boundary=md_task_checkpoint_suspend(&checkpoints,actor->pid);
    if (boundary>=0) *owned=1;
    return boundary;
}

static void task_action_call_start(struct task_action *owner) {
    struct thread *actor=owner->actor;
    struct user_pt_regs saved=owner->continuation;
    enum md_task_call_boundary boundary=owner->boundary;
    if (owner->remote) {
        struct md_task_quiesce *barrier=owner->outer_barrier ? owner->outer_barrier : task_action_barrier;
        if (!md_task_quiesce_ready(barrier)) return;
        struct md_task_quiesce_stop stop;
        int error=md_task_quiesce_borrow(barrier,actor->pid,&stop);
        if (error) { task_action_call_complete(error,error==-ESRCH); return; }
        owner->borrowed=1;
        owner->synthetic=stop.kind==MD_TASK_QUIESCE_SYNTHETIC;
        if (stop.kind==MD_TASK_QUIESCE_EVENT) {
            unsigned event=(unsigned)stop.status>>16;
            /* Lifecycle and signal delivery belong to the normal dispatcher.
             * Replay them before selecting another exact task boundary. */
            if (event!=PTRACE_EVENT_SECCOMP && (event || WSTOPSIG(stop.status)!=(SIGTRAP|0x80))) {
                task_action_call_complete(0,1); return;
            }
        }
        struct ptrace_syscall_info info={0};
        if (!registers(actor->pid,&saved)) { task_action_call_complete(-ESRCH,1); return; }
        if (native_trace(PTRACE_GET_SYSCALL_INFO,actor->pid,(void *)sizeof(info),&info)<0) {
            task_action_call_complete(-errno,0); return;
        }
        boundary=(enum md_task_call_boundary)info.op;
    }
    int checkpoint=task_checkpoint_borrow(actor,&owner->checkpoint_suspended);
    if (checkpoint<0) { task_action_call_complete(checkpoint,0); return; }
    if (checkpoint && boundary==MD_TASK_CALL_ENTRY) boundary=MD_TASK_CALL_EMULATED_ENTRY;
    SUPERVISOR_TRACE("PROBE selector-start requester=%d actor=%d remote=%d synthetic=%d kind=%u fd=%d boundary=%d pc=%llx nr=%llu value=%lld\n",
        owner->requester ? owner->requester->pid : 0,actor->pid,owner->remote,owner->synthetic,
        (unsigned)owner->kind,owner->number,boundary,
        (unsigned long long)saved.pc,(unsigned long long)saved.regs[8],(long long)saved.regs[0]);
    if (owner->loan) {
        struct md_task_call_arguments args={.number=SYS_close,.value={(unsigned long)owner->number}};
        int error=md_task_call_begin(&owner->native,native_trace,actor->pid,boundary,
            &saved,abi.task_call,abi.task_return,&args);
        if (error && owner->native.stage) { CHECK(error==-ESRCH); actor->resume_lost=1; return; }
        if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
        actor->kernel_stopped=0; return;
    }
    if (owner->capture) {
        struct capture_owner *capture=owner->capture;
        int error=md_buffers_take_reserved(md_memory_buffers(actor->memory),&owner->buffer);
        if (!error && owner->capture_capacity_needed) {
            error=md_process_nofile_reserve(actor->resources.process,actor->pid,2);
            if (!error) owner->capture_capacity=md_process_member_retain(actor->resources.process);
        }
        if (!error) {
            actor->terminal_observation=1; actor->trace_options_dirty=1;
            error=debugger_options(actor->pid,actor->debug.options);
        }
        if (!error) {
            struct md_task_helper_request request={.kind=MD_TASK_HELPER_CAPTURE,
                .path=capture->target.path,.flags=capture->target.flags,
                .capture_metadata=capture->metadata,.directory_limit=capture->directory_limit};
            error=md_task_helper_borrow(helpers,actor->pid,actor->tgid,boundary,&saved,&abi,
                actor->memory,&owner->buffer,&request,capture_borrowed_complete,owner);
        }
        if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
        capture->started=1; actor->kernel_stopped=0; return;
    }
    int error=md_buffers_take_reserved(md_memory_buffers(actor->memory),&owner->buffer);
    struct md_buffer_span span;
    if (!error) error=md_buffer_span(&owner->buffer,&span);
    uintptr_t address=error ? 0 : md_memory_buffer_address(actor->memory,&owner->buffer);
    if (!error && (!address || span.size<sizeof(struct md_task_filter_image))) error=-EINVAL;
    if (!error) {
        /* seccomp reports the PC after SVC, not the address used to enter it. */
        struct md_fd_filter_gates gates=md_fd_filter_runtime_gates(&abi);
        struct md_task_filter_image *image=span.data;
        if (owner->pid_filter) {
            struct md_pid_filter_program program;
            md_pid_filter_build(&abi,&program);
            _Static_assert(MD_PID_FILTER_INSTRUCTIONS<=MD_FD_FILTER_INSTRUCTIONS,"task filter capacity");
            image->code.count=program.count;
            memcpy(image->code.code,program.code,program.count*sizeof(*program.code));
            error=md_task_filter_begin_program(&owner->filter,native_trace,actor->pid,boundary,
                &saved,abi.task_call,abi.task_return,image,address);
        } else error=md_fd_filter_build(owner->kind,owner->number,&gates,&image->code);
        if (!error && !owner->pid_filter) {
            owner->selector_instructions=image->code.count;
            owner->selector_number=md_fd_selector_target(owner->group,owner->number,image->code.count);
            if (owner->selector_number< -1) error=owner->selector_number;
        }
        if (!error && !owner->pid_filter) error=md_task_filter_begin(&owner->filter,native_trace,actor->pid,boundary,
            &saved,abi.task_call,abi.task_return,span.data,address,owner->kind,owner->selector_number,&gates);
    }
    if (error && owner->filter.task.stage) {
        CHECK(error==-ESRCH); actor->resume_lost=1; return;
    }
    if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
    actor->kernel_stopped=0;
}

static int selector_peer_ready(struct thread *peer,unsigned kind,int number) {
    int ready=md_fd_selector_has(peer->descriptors.selector,kind,number);
    if (ready || !peer->process_op.vfork_child) return ready;
    /* The kernel will not run this parent until its vfork child releases the
     * mm. Retain only bounded selector requirements, never hold the child's
     * publication hostage to a task-affine call in that suspended parent. */
    if (!peer->descriptors.deferred_selector) peer->descriptors.deferred_selector=md_fd_selector_new();
    if (!peer->descriptors.deferred_selector) return -ENOMEM;
    ready=md_fd_selector_has(peer->descriptors.deferred_selector,kind,number);
    if (ready<0) return ready;
    if (!ready) {
        int error=md_fd_selector_mark(peer->descriptors.deferred_selector,kind,number);
        if (error) return error;
    }
    return 1;
}

int selector_has(struct thread *t,unsigned kind,int number) {
    int ready=md_fd_selector_has(t->descriptors.selector,kind,number);
    if (ready<=0 || number<0) return ready;
    for (struct thread *peer=threads;peer;peer=peer->next)
        if (peer->pid && peer->born && peer->resources.files==t->resources.files
                && peer->descriptors.selector!=t->descriptors.selector) {
            ready=selector_peer_ready(peer,kind,number);
            if (ready<=0) return ready;
        }
    return 1;
}

static int task_files_queued(const struct thread *p) {
    /* The table turn has not begun, so none of these owners may execute
     * task-affine work yet. Keep eligibility tied to resource state, not to
     * a syscall family or a scheduler's retry bit. */
    const struct md_fd_order *turns[]={&p->descriptors.order,&p->proc.lookup,&p->memory_op.files,
        &p->metadata.files,&p->fs.descriptor_order,&p->fs.returned.files,
        &p->proc.delivery.files,p->readiness ? &p->readiness->files : NULL,
        p->readiness ? &p->readiness->loans[p->readiness->lending].delivery.files : NULL,
        p->io.outgoing ? &p->io.outgoing->files : NULL,p->io.receive ? &p->io.receive->files : NULL,
        p->memory_op.mapping ? &p->memory_op.mapping->files : NULL,p->signals.control ? &p->signals.control->files : NULL};
    for (unsigned i=0;i<sizeof(turns)/sizeof(*turns);++i)
        if (md_fd_order_state(turns[i])==MD_RESOURCE_ORDER_QUEUED) return 1;
    return md_fs_buffer_waiting_files(p->fs.call)
        || md_fs_buffer_waiting_files(p->metadata.stat_call);
}

int task_parked(const struct thread *t) {
    return t->phase==WATCH_READ || t->phase==IPC_WAITING || t->phase==SOCKET_WAITING || t->phase==FILE_WAITING
        || t->phase==CONTROL_WAITING || t->phase==CLOSE_WAITING || t->phase==EPOLL_WAIT;
}

int task_continuation_available(const struct thread *t) {
    return !retained_task_held(t) && memory_available(t->pid);
}

int task_completion_available(pid_t pid) {
    struct thread *t=find_thread(pid);
    return t && task_continuation_available(t);
}

int task_action_borrowable(struct thread *p,struct thread *requester) {
    if (retained_task_held(p)) return 0;
    /* A queued table user is parked before its operation, through either
     * transport. Other candidates retain their exact kernel continuation.
     * Never borrow an active worker,
     * mutation or newborn awaiting its first stop. */
    int continuation=p->kernel_stopped || (p->entry.control && !p->entry.effect && !p->entry.stop.resume);
    int waiter=continuation && (task_files_queued(p)
        || (p->action && !task_action_call(p->action)->stage));
    int mapping_active=p->memory_op.mapping
        && md_fd_order_state(&p->memory_op.mapping->files)!=MD_RESOURCE_ORDER_QUEUED;
    int traced=md_debugger_traced(debugger,p->pid);
    /* A debugger can share files with its stopped inferior (crash collectors
     * commonly do). Its own parked request is the only control owner, so the
     * exact held continuation can install selectors without exposing adapter
     * registers or resuming guest code. Unrelated debuggers remain excluded. */
    int debug_held=traced && requester && p->kernel_stopped && debugger_at_boundary(p)
        && md_debugger_stopped_by(debugger,p->pid,requester->pid);
    /* Tracing does not own a running task. Borrow its exact kernel boundary
     * while preserving debugger state, just as for an untraced task. Excluding
     * it could strand a temporary FD behind the table turn needed by every
     * peer. Published debugger stops and pending interrupts remain excluded. */
    int debug_running=traced && md_debugger_running(debugger,p->pid);
    return p->ready && !birth_stop_pending(p) && !p->resume_lost && !p->terminal_stop
        && !p->listening && !p->process_op.vfork_child && !p->interrupt_pending && !p->signals.return_pending
        && !mapping_active && !p->memory_op.capture && !p->memory_op.svc_pool
        && !p->descriptors.running && (!traced || debug_held || debug_running)
        && !md_task_helper_owns(helpers,p->pid,p->pid)
        && (waiter || debug_held || (p->freezer_resume && debugger_at_boundary(p))
            /* A provider owns the pending operation, not this native wait.
             * Borrow and restore its exact boundary without cancelling it. */
            || (!p->kernel_stopped && (p->phase==IDLE || p->phase==DISPATCHING
            || task_parked(p) || signal_control_parked(p) || p->phase==PROCESS_OBSERVING)));
}

static int task_action_native_borrow(struct task_action *owner,struct thread *actor,int *stopped) {
    if (retained_task_held(actor)) return -EBUSY;
    *stopped=actor->kernel_stopped;
    int borrowed=md_broker_native_borrow(broker,actor->pid,stopped);
    if (borrowed<0) return borrowed;
    owner->broker_borrowed=borrowed;
    return 0;
}

void task_action_start(struct thread *t) {
    retry_clear(t,RETRY_TASK_ACTION);
    struct task_action *owner=t->action;
    CHECK(owner && !task_action_call(owner)->stage);
    if (owner->loan) { loan_start(owner); return; }
    if (quiescence_busy(NULL) || retained_task_held(t)) { retry_wait(t,RETRY_TASK_ACTION); return; }
    if (owner->pid_filter) {
        if (t->process_op.filter) { task_action_finish(owner,0); return; }
        owner->actor=t; task_action_execution=owner;
        task_action_call_start(owner); return;
    }
    if (owner->capture) {
        struct md_fs_task_subject *target=&owner->capture->subject;
        struct thread *actor=find_thread(target->task);
        struct md_task_credentials credentials;
        int error=md_task_registry_credentials(tasks,target->task,&credentials);
        if (!error) {
            if (credentials.retired || credentials.generation!=target->generation) error=-ENOENT;
            md_task_credentials_release(&credentials);
        }
        if (!error && (!actor || !actor->born || actor->terminal_stop)) error=-ENOENT;
        if (!error) {
            struct md_proc_path path=md_proc_path_for(owner->capture->target.path,0,0);
            int public_dentry=(owner->capture->target.flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW)
                && md_proc_public_task_dentry(path)
                && path.process==actor->pid && !md_domain_restricted(t->resources.fs);
            error=owner->capture->authorize ? owner->capture->authorize(t,actor)
                : public_dentry || proc_allowed(t->pid,actor->pid) ? 0 : -EACCES;
        }
        if (error) { task_action_finish(owner,error); return; }
        if (!task_action_borrowable(actor,t)) { retry_wait(t,RETRY_TASK_ACTION); return; }
        owner->capture_capacity_needed=md_process_native_nofile(actor->resources.process)<2;
        if (owner->capture_capacity_needed) {
            /* Only a zero/one-slot group needs extra native capacity. Keep
             * every guest participant stopped until the private table is gone;
             * normal limits never acquire this group-wide barrier. */
            if (md_task_helpers_group_busy(helpers,actor->tgid)) { retry_wait(t,RETRY_TASK_ACTION); return; }
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer!=actor && peer->tgid==actor->tgid
                        && !task_action_borrowable(peer,t)) { retry_wait(t,RETRY_TASK_ACTION); return; }
        }
        int stopped=0;
        error=task_action_native_borrow(owner,actor,&stopped);
        if (error==-EBUSY) { retry_wait(t,RETRY_TASK_ACTION); return; }
        if (error) { task_action_finish(owner,error); return; }
        owner->actor=actor; owner->remote=1; task_action_execution=owner;
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!quiesce_task_add(task_action_barrier,actor,stopped));
        if (owner->capture_capacity_needed) for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer!=actor && peer->tgid==actor->tgid)
                CHECK(!quiesce_task_add(task_action_barrier,peer,0));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
        task_action_call_start(owner); return;
    }
    if (owner->rejected_task) {
        if (md_task_helper_retiring(helpers,owner->rejected_group,owner->rejected_task)) {
            retry_wait(t,RETRY_TASK_ACTION); return;
        }
        if (!syscall(SYS_tgkill,owner->rejected_group,owner->rejected_task,0) || errno!=ESRCH) {
            task_action_finish(owner,-ENOTSUP); return;
        }
        owner->rejected_task=0;
    }
    /* The request may already own this turn for dup or received publication.
     * Otherwise acquire it before freezing the set of CLONE_FILES holders.
     * A copied table inherits only filters captured before its kernel clone. */
    if (!owner->selected_group && owner->number>=0 && md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING
            && md_fd_order_state(&t->fs.returned.files)!=MD_RESOURCE_ORDER_RUNNING
            && !(t->signals.control && md_fd_order_state(&t->signals.control->files)==MD_RESOURCE_ORDER_RUNNING)
            && !(t->readiness && md_fd_order_state(&t->readiness->files)==MD_RESOURCE_ORDER_RUNNING)) {
        if (!owner->files.table) {
            int ready=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
            if (ready<0) { task_action_finish(owner,ready); return; }
        }
        if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_QUEUED) {
            retry_wait(t,RETRY_TASK_ACTION); return;
        }
        if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_READY)
            CHECK(!md_fd_order_start(&owner->files));
    }
    struct thread *candidate=NULL;
    int ready=md_fd_selector_has(owner->selected_group ? owner->selected_group : t->descriptors.selector,owner->kind,owner->number);
    if (ready<0) { task_action_finish(owner,ready); return; }
    if (ready && owner->selected_group) { task_action_finish(owner,0); return; }
    if (!ready && (!owner->selected_group || owner->selected_group==t->descriptors.selector)) candidate=t;
    int pending=0;
    for (struct thread *peer=threads;!candidate && owner->number>=0 && peer;peer=peer->next)
        if (peer->pid && peer->born && (owner->selected_group
                ? peer->descriptors.selector==owner->selected_group : peer->resources.files==t->resources.files)) {
            ready=selector_peer_ready(peer,owner->kind,owner->number);
            if (ready<0) { task_action_finish(owner,ready); return; }
            if (ready) continue;
            pending=1;
            if (task_action_borrowable(peer,t)) candidate=peer;
        }
    if (!candidate) {
        if (pending) retry_wait(t,RETRY_TASK_ACTION);
        else task_action_finish(owner,owner->selected_group ? -ESRCH : 0);
        return;
    }
    int stopped=candidate->kernel_stopped;
    if (candidate!=t) {
        int error=task_action_native_borrow(owner,candidate,&stopped);
        if (error==-EBUSY || error==-ESRCH) { retry_wait(t,RETRY_TASK_ACTION); return; }
        if (error) { task_action_finish(owner,error); return; }
    }
    owner->actor=candidate; owner->remote=candidate!=t;
    owner->group=md_fd_selector_fork(candidate->descriptors.selector,1);
    task_action_execution=owner;
    if (owner->remote) {
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!quiesce_task_add(task_action_barrier,candidate,stopped));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
    }
    task_action_call_start(owner);
}

void pid_filter_begin(struct thread *t,const struct user_pt_regs *continuation,
        void (*complete)(struct thread *,long)) {
    struct task_action *owner=task_action_new(t);
    if (!owner) {
        if (complete) complete(t,-ENOMEM);
        else { struct user_pt_regs returned=*continuation; return_value(t,&returned,-ENOMEM); }
        return;
    }
    owner->pid_filter=1; owner->number=-1;
    owner->continuation=*continuation; owner->complete=complete;
    task_action_start(t);
}

void selector_deferred_complete(struct thread *t,long result) {
    if (result<0) {
        fprintf(stderr,"Shroot: cannot admit resumed shared file table: %s\n",strerror((int)-result));
        cancel_tree(125); return;
    }
    enum md_fd_selector_kind kind; int number;
    int missing=md_fd_selector_missing(t->descriptors.deferred_selector,t->descriptors.selector,&kind,&number);
    if (missing<0) { selector_deferred_complete(t,missing); return; }
    if (!missing) {
        md_fd_selector_release(t->descriptors.deferred_selector); t->descriptors.deferred_selector=NULL;
        t->phase=IDLE; resume(t->pid,0); return;
    }
    struct user_pt_regs saved;
    if (!registers(t->pid,&saved)) return;
    struct task_action *owner=task_action_new(t);
    if (!owner) { selector_deferred_complete(t,-ENOMEM); return; }
    owner->continuation=saved; owner->boundary=MD_TASK_CALL_EXIT;
    owner->kind=kind; owner->number=number;
    owner->selected_group=md_fd_selector_fork(t->descriptors.selector,1);
    owner->complete=selector_deferred_complete;
    task_action_start(t);
}

static void task_action_progress(struct md_pending_node *node,void *context) {
    (void)context;
    struct task_action *owner=(struct task_action *)node;
    /* Retain blocked work before dispatch: completion may free this owner or
     * cancel another queued action. New actions wait for the next pass. */
    md_pending_add(&task_action_queue,node);
    if (!task_action_execution && owner->requester && owner->requester->pid)
        task_action_start(owner->requester);
}

void task_action_schedule(void) {
    if (!task_action_calls || memory_execution) return;
    if (task_action_execution) {
        if (!task_action_call(task_action_execution)->stage
                && !(task_action_execution->capture && task_action_execution->capture->started))
            task_action_call_start(task_action_execution);
        return;
    }
    for (struct task_action *s=loan_retirements,*next;s;s=next) {
        next=s->next; loan_start(s);
        if (task_action_execution) return;
    }
    CHECK(!md_pending_dispatch(&task_action_queue,task_action_progress,NULL));
}

static int loan_borrow(struct task_action *owner,struct thread *p,int *alive,int *stopped) {
    if (!p || !p->pid || p->resources.files!=owner->loan_table || p->terminal_stop || p->resume_lost) return 0;
    if (retained_task_held(p) || p->signals.return_pending || (p!=owner->requester && p->pid!=owner->retirement.task
            && !task_action_borrowable(p,owner->requester))) {
        *alive=1; return 0;
    }
    int borrowed=task_action_native_borrow(owner,p,stopped);
    if (borrowed==-ESRCH) return 0;
    *alive=1;
    if (borrowed<0) return 0;
    return 1;
}

static void loan_start(struct task_action *owner) {
    if (owner->resident || cancelling || quiescence_busy(owner->retired_context)) return;
    struct md_fd_order *turn=owner->loan_turn ? owner->loan_turn : &owner->files;
    if (!turn->table) {
        int error=md_fd_order_begin(owner->loan_table,turn,fd_wakeup);
        if (error<0) { loan_failed(owner,error); return; }
    }
    if (md_fd_order_state(turn)==MD_RESOURCE_ORDER_QUEUED) return;
    if (md_fd_order_state(turn)==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(turn));
    int exists=md_fd_origins_loan_matches(turn,owner->number,owner->loan);
    if (exists<0) { loan_failed(owner,exists); return; }
    if (!exists) { task_action_finish(owner,0); return; }
    struct thread *t=owner->requester;
    if (t && t->pid && !t->kernel_stopped && !t->listening && !t->terminal_stop && !t->resume_lost
            && t->resources.files==owner->loan_table
            && t->entry.control && !t->entry.effect && !t->entry.stop.resume && !retained_task_held(t)) {
        owner->native.arguments=(struct md_task_call_arguments){.number=SYS_close,
            .value={(unsigned long)owner->number}};
        uint64_t args[6]={owner->number};
        owner->resident=1;
        entry_effect(t,SYS_close,args,loan_entry_complete); return;
    }
    struct thread *candidate=NULL;
    int alive=0,stopped=0;
    /* The requester already owns an exact stopped continuation. A peer can
     * instead have a deferred signal/lifecycle event which must not be replayed
     * while an outer mapping barrier still protects the borrowed descriptor. */
    if (loan_borrow(owner,owner->requester,&alive,&stopped)) candidate=owner->requester;
    for (struct thread *p=threads;!candidate && p;p=p->next) {
        if (p!=owner->requester && loan_borrow(owner,p,&alive,&stopped)) candidate=p;
    }
    if (!candidate) {
        /* No task can retain the kernel table. A metadata pin is not an owner. */
        if (!alive) task_action_finish(owner,0);
        return;
    }
    owner->actor=candidate; owner->remote=1;
    task_action_execution=owner;
    owner->outer_barrier=mapping_execution ? mapping_barrier : NULL;
    if (!owner->outer_barrier) {
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!quiesce_task_add(task_action_barrier,candidate,stopped));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
    }
    task_action_call_start(owner);
}

void loan_retire(struct task_action *owner,struct thread *requester,
        struct md_fd_order *turn,void (*complete)(struct thread *,long)) {
    if (md_fd_loan_active_in(owner->loan,owner->loan_table)) md_fd_loan_end(owner->loan);
    owner->requester=requester; owner->loan_turn=turn; owner->complete=complete;
    owner->next=loan_retirements; loan_retirements=owner; ++task_action_calls;
    if (requester) { CHECK(!requester->action); requester->action=owner; }
    if (!turn) CHECK(md_fd_order_begin(owner->loan_table,&owner->files,fd_wakeup)>=0);
    CHECK(!md_event_signal(fd_ready));
}

int broker_retire(const struct md_broker_retirement *request) {
    struct task_action *owner=calloc(1,sizeof(*owner));
    if (!owner) return -ENOMEM;
    int number=-1;
    int error=md_fd_origins_loan_range(request->turn,request->number,request->number,&number,&owner->loan);
    if (error!=1 || number!=request->number || owner->loan!=request->loan) {
        md_fd_loan_release(owner->loan); free(owner); return error<0 ? error : -ESTALE;
    }
    owner->loan_table=md_fd_origins_pin(request->turn->table);
    owner->number=number; owner->retirement=*request;
    loan_retire(owner,NULL,request->turn,NULL);
    return 0;
}

void task_call_resume(struct thread *t,struct md_task_call *call,int signal) {
    int error=md_task_call_resume(call,signal);
    if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
    else t->kernel_stopped=0;
}

struct md_task_call *task_call_continuation(struct thread *t) {
    if (t->memory_op.svc_pool) return &t->memory_op.svc_pool->call;
    if (t->signals.control && t->signals.control->started && !t->signals.control->retiring)
        return md_task_signal_control_call(&t->signals.control->control);
    if (t->seccomp.notification && !t->seccomp.notification->promoting) return &t->seccomp.notification->call;
    struct filter_install *install=t->seccomp.install;
    if (install && !install->retiring) {
        if (install->admission.stage && !install->admitted) return &install->admission;
        if (install->copy.scratch) return &install->copy.call;
    }
    return NULL;
}

void task_group_stop(struct thread *t) {
    /* EVENT_WAIT: preserve the exact operation until kernel SIGCONT or exit.
     * A group stop is not a completion/error of a borrowed syscall. */
    t->listening=1; group_stops++;
    if (native_trace(PTRACE_LISTEN,t->pid,NULL,NULL)) {
        CHECK(errno==ESRCH); t->resume_lost=1;
    } else {
        t->kernel_stopped=0;
        /* A freeze request may have been observed before the group-stop
         * acknowledgement. LISTEN is now quiescent without another cgroup
         * generation change; publish it through the common member owner. */
        if (freezer_enabled && md_cgroup_member_freezing(t->resources.cgroup))
            md_cgroup_member_frozen(t->resources.cgroup,1);
        md_debugger_child_changed(debugger,t->pid);
    }
}

int task_call_signal(struct thread *t,struct md_task_call *call,int status) {
    if (WIFSTOPPED(status) && !((unsigned)status>>16) && WSTOPSIG(status)==SIGSTOP) {
        task_call_resume(t,call,SIGSTOP); return 1;
    }
    if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)!=SIGTRAP) {
        task_group_stop(t); return 1;
    }
    if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
        t->interrupt_pending=0;
        if (t->listening) {
            t->listening=0; group_wakes++;
            md_debugger_child_changed(debugger,t->pid);
        }
        /* A coordinator wake may arrive after another owner borrowed this
         * task. Preserve that owner's exact continuation; only its own wake
         * completes a call boundary. */
        if (!md_task_call_expects_interrupt(call)) {
            if (statistics) ++statistics->stops;
            task_call_resume(t,call,0); return 1;
        }
    }
    if (!WIFSTOPPED(status) || (unsigned)status>>16 || WSTOPSIG(status)==(SIGTRAP|0x80)) return 0;
    siginfo_t info;
    if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"task-call-signal")) return 1;
    if (md_signal_synchronous(&info)) return 0;
    int retained=md_signal_delivery_retain(&t->signals.delivery,t->pid,&info); CHECK(retained>=0);
    task_call_resume(t,call,retained);
    return 1;
}

int task_action_event(pid_t pid,int status,const struct rusage *usage) {
    struct task_action *owner=task_action_execution;
    if (!owner) return 0;
    struct thread *t=find_thread(pid);
    unsigned event=(unsigned)status>>16;
    if (t!=owner->actor) {
        if (!md_task_quiesce_contains(task_action_barrier,pid)) return 0;
        CHECK(!quiesce_event(task_action_barrier,pid,status,usage)); return 1;
    }
    int helper=owner->capture && owner->capture->started;
    struct md_task_call *call=helper ? md_task_helper_call(helpers,pid) : task_action_call(owner);
    if (!call) return 0;
    if (!call->stage) {
        SUPERVISOR_TRACE("PROBE selector-stop pid=%d status=%x\n",pid,status);
        CHECK(owner->remote && !quiesce_event(task_action_barrier,pid,status,usage)); return 1;
    }
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) {
        if (helper) return 0;
        if (!owner->remote) { t->action=NULL; owner->requester=NULL; }
        task_action_call_complete(-ESRCH,0); return 0;
    }
    if (task_call_signal(t,call,status)) return 1;
    int owned_interrupt=md_task_call_expects_interrupt(call);
    if (helper) return 0;
    if (event!=PTRACE_EVENT_SECCOMP && !(event==PTRACE_EVENT_STOP && owned_interrupt)
            && (event || WSTOPSIG(status)!=(SIGTRAP|0x80))) return 0;
    if (statistics) ++statistics->stops;
    int result=md_task_call_event(call,status);
    SUPERVISOR_TRACE("PROBE task-action pid=%d status=%x stage=%u boundary=%d loan=%d ready=%d result=%ld\n",
        pid,status,call->stage,call->boundary,owner->loan!=NULL,result,call->result);
    if (result==-EPROTO) {
        struct ptrace_syscall_info info={0};
        struct user_pt_regs actual={0};
        registers(pid,&actual);
        native_trace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info);
        fprintf(stderr,"guest-runtime: task-action mismatch pid=%d status=%x phase=%d stage=%u boundary=%d loan=%d active=%ld op=%u pc=%llx/%llx nr=%llu/%llu x0=%llu/%llu x7=%llu/%llu\n",
            pid,status,t->phase,call->stage,call->boundary,owner->loan!=NULL,call->active_number,
            info.op,(unsigned long long)actual.pc,(unsigned long long)call->entry.pc,
            (unsigned long long)actual.regs[8],(unsigned long long)call->entry.regs[8],
            (unsigned long long)actual.regs[0],(unsigned long long)call->entry.regs[0],
            (unsigned long long)actual.regs[7],(unsigned long long)call->entry.regs[7]);
    }
    if (result<0) { CHECK(result==-ESRCH); t->resume_lost=1; return 1; }
    if (result) {
        t->kernel_stopped=1;
        if (owner->loan && loan_close_denied(call->arguments.number,call->result)) {
            struct md_task_call_arguments args={.number=SYS_close_range,
                .value={(unsigned long)owner->number,(unsigned long)owner->number,0}};
            result=md_task_call_again(call,&args);
            if (result) { CHECK(result==-ESRCH); t->resume_lost=1; }
            else t->kernel_stopped=0;
            return 1;
        }
        int single=!md_task_helpers_group_busy(helpers,t->tgid);
        for (struct thread *peer=threads;single && peer;peer=peer->next)
            if (peer!=t && peer->pid && peer->tgid==t->tgid) single=0;
        if (!owner->loan && call->result==-EPERM && !owner->filter.thread_only && single) {
            SUPERVISOR_TRACE("PROBE selector-thread-retry pid=%d boundary=%d\n",pid,call->boundary);
            result=md_task_filter_retry_thread(&owner->filter);
            if (result) { CHECK(result==-ESRCH); t->resume_lost=1; }
            else t->kernel_stopped=0;
            return 1;
        }
        task_action_call_complete(call->result,0);
    }
    return 1;
}

static void fd_loan_closed(struct thread *t,long result) {
    if (!result && t->descriptors.job==MD_FD_DUPLICATE_BEGIN) {
        /* fd_start validated discovery before retirement. This close owns the
         * same uninterrupted table turn: do not retry our own generation bump
         * and let the waiter lend the just-retired slot again indefinitely. */
        CHECK(md_fd_order_state(&t->descriptors.order)==MD_RESOURCE_ORDER_RUNNING);
        struct md_fd_observation view;
        result=md_fd_origins_observe(t->resources.files,(int)t->execution.request.args[0],&view);
        if (!result) {
            t->descriptors.request.args[0]=view.generation;
        }
    }
    if (result) { fd_discard(t); fd_return(t,&t->descriptors.registers,result); }
    else fd_start(t);
}

static int fd_loan_evict(struct thread *mutator,struct md_fd_loan *loan,int number) {
    /* An already finished wait queued its own retirement. Yield this not-yet
     * executed mutation behind that close instead of holding up its owner.
     * A fork copy belongs to a different kernel table and closes independently. */
    for (struct task_action *s=loan_retirements;s;s=s->next)
        if (s->loan==loan && s->loan_table==mutator->resources.files && s->number==number) {
            CHECK(!md_fd_order_complete(&mutator->descriptors.order));
            CHECK(md_fd_order_begin(mutator->resources.files,&mutator->descriptors.order,fd_wakeup)>=0);
            retry_wait(mutator,RETRY_FD); return 0;
        }
    if (!md_fd_loan_active_in(loan,mutator->resources.files)) {
        struct task_action *retirement=calloc(1,sizeof(*retirement));
        if (!retirement) {
            fd_discard(mutator); return_value(mutator,&mutator->descriptors.registers,-ENOMEM); return 0;
        }
        int selected=-1;
        CHECK(md_fd_origins_loan_range(&mutator->descriptors.order,number,number,&selected,&retirement->loan)==1);
        CHECK(selected==number && retirement->loan==loan);
        retirement->number=number;
        retirement->loan_table=md_fd_origins_pin(mutator->resources.files);
        loan_retire(retirement,mutator,&mutator->descriptors.order,fd_loan_closed);
        return 0;
    }
    for (struct thread *p=threads;p;p=p->next) {
        struct readiness_owner *o=p->readiness;
        if (!p->pid || !o) continue;
        struct readiness_loan *owned=NULL;
        for (unsigned n=0;n<READINESS_QUEUES;++n)
            if (o->loans[n].retirement && o->loans[n].retirement->loan==loan) owned=&o->loans[n];
        if (!owned) continue;
        o->evicting=1;
        if (o->delivery_waiting) readiness_evict_park(p,0,0);
        if (!o->parked) {
            /* EVENT_WAIT: the calling thread leaves its exact native wait
             * before a destructive table mutation. No table lock spans IO. */
            if (readiness_waiting(p) && !p->io.socket_waking) {
                p->io.socket_waking=1;
                tracee_request(p,PTRACE_INTERRUPT,NULL,NULL,"epoll-loan-revoke");
            }
            retry_wait(mutator,RETRY_FD); return 0;
        }
        struct task_action *retirement=owned->retirement;
        owned->retirement=NULL; owned->number=-1;
        retirement->loan_waiter=p->pid;
        loan_retire(retirement,mutator,&mutator->descriptors.order,fd_loan_closed);
        return 0;
    }
    CHECK(0); return 0;
}

int fd_loans_ready(struct thread *t) {
    if (!md_fd_origins_has_loans(t->resources.files)) return 1;
    const struct seccomp_data *call=t->descriptors.job==MD_FD_DUPLICATE_BEGIN ? &t->execution.request : &t->descriptors.call;
    unsigned long a[6]; for (unsigned i=0;i<6;++i) a[i]=call->args[i];
    long nr=call->nr;
    int copying=(md_clone_call(nr) && !(t->process_op.cloning&CLONE_FILES))
        || (nr==SYS_unshare && (a[0]&CLONE_FILES))
        || (nr==SYS_close_range && (a[2]&CLOSE_RANGE_UNSHARE));
    struct md_fd_loan *loan=NULL;
    int number=-1;
    int result=copying ? md_fd_origins_loan_range(&t->descriptors.order,0,UINT_MAX,&number,&loan)
        : md_fd_mutation_loan(&t->descriptors.order,nr,a,&number,&loan);
    if (result<0) { fd_discard(t); return_value(t,&t->descriptors.registers,result); return 0; }
    if (!result) return 1;
    int ready=fd_loan_evict(t,loan,number);
    md_fd_loan_release(loan);
    return ready;
}

void selector_progress(struct thread *t) {
    retry_clear(t,RETRY_TASK_ACTION);
    if (t->action) { task_action_start(t); return; }
    if (md_task_helper_retiring(helpers,t->tgid,t->descriptors.selector_exit)) { retry_wait(t,RETRY_TASK_ACTION); return; }
    struct user_pt_regs r;
    t->descriptors.selector_exit=0;
    if (registers(t->pid,&r)) {
        r.regs[0]=0;
        if (set_registers(t->pid,&r)) resume(t->pid,0);
    }
}

int helper_newborn(pid_t pid) {
    int held=md_task_quiesce_newborn(memory_barrier,pid);
    if (held) return held;
    struct thread *t=find_thread(pid);
    if (!t) return 0;
    CHECK(!t->born && (t->initial_stop || t->listening));
    /* The first stop can already be a group stop, consumed with LISTEN before
     * the parent's birth event. Transfer ownership without resuming that child;
     * the helper starts at its subsequent CONT acknowledgement. */
    int ready=t->terminal_stop ? MD_TASK_BIRTH_EXIT
        : t->initial_stop && !t->listening ? MD_TASK_BIRTH_READY : MD_TASK_BIRTH_PENDING;
    release_thread(t);
    return ready;
}
