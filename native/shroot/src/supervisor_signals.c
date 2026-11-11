#define _GNU_SOURCE
#include "adapter_buffer.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "guest_debugger.h"
#include "guest_identity.h"
#include "identity_call.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "namespace_broker.h"
#include "pid_namespace.h"
#include "process_image_view.h"
#include "process_owner.h"
#include "resource_order.h"
#include "seccomp_policy.h"
#include "signal_actions.h"
#include "signal_delivery.h"
#include "signal_entry.h"
#include "signal_fd.h"
#include "signal_origin.h"
#include "signal_pending.h"
#include "signal_protocol.h"
#include "signal_view.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_domain.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_identity.h"
#include "supervisor_internal.h"
#include "supervisor_notifications.h"
#include "supervisor_proc.h"
#include "supervisor_readiness.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_machine.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_copy.h"
#include "task_pending.h"
#include "task_registry.h"
#include "task_signal_control.h"
#include "task_status.h"
#include "task_stop.h"

static int signal_pending_wake(struct thread *t);
static void signal_descriptor_selected(struct thread *t,long error);
static int signal_frame_native(const struct thread *t);
static int signal_control_delivery(struct thread *t,int signal,struct md_task_signal_stop *stop);
static int pending_child(void *context,const siginfo_t *info);
static int child_signals_capture(struct thread *t);
static int namespace_init(const struct thread *t);
struct signal_selection;
static int signal_candidate(struct signal_selection *s,pid_t pid,const struct md_identity *identity);
static void signal_retired_candidate(void *context,pid_t pid,struct md_task_retired_view view);
static void signal_entry_complete(struct thread *t,int failed);
static void signal_wait_info(void *context,siginfo_t *info);
static void signal_descriptor_retired(struct thread *t,long error);
static void signal_control_finish(struct thread *t,int ready);
static int signal_return_snapshot(struct thread *t);
static int signal_native_mask_commit(struct thread *t,uint64_t mask);

struct signal_selection {
    struct thread *from;
    pid_t selector,group,after,next;
    int signal,found,suppressed;
};

struct signal_control *signal_entries;

uid_t process_signal_uid(struct thread *observer,pid_t sender,uid_t fallback) {
    const struct md_identity *identity=proc_owner(sender).identity;
    return md_identity_visible_id(&observer->identity,0,identity ? identity->uid.real : fallback);
}

void signal_origin_capture(void *context,const siginfo_t *info,struct md_signal_origin *out) {
    struct thread *t=context;
    *out=(struct md_signal_origin){0};
    if (!md_signal_child_notice(info)) return;
    const struct md_signal_origin *saved=&t->signal_origin;
    if (!md_signal_origin_matches(saved,info)) saved=t->signal_delivery.child_origin;
    if (!md_signal_origin_matches(saved,info)) saved=md_signal_pending_origin(t->signal_pending,info);
    if (saved) { *out=*saved; return; }
    struct proc_owner owner=proc_owner(info->si_pid);
    if (!owner.identity) return;
    struct md_pid_identity *pid=owner.resources ? owner.resources->pid
        : md_task_registry_retired(tasks,info->si_pid).pid;
    *out=(struct md_signal_origin){.info=*info,.uid=owner.identity->uid.real,.valid=1,
        .reboot_signal=md_pid_identity_reboot_signal(pid)};
    out->count=md_pid_identity_export(pid,out->numbers);
}

void signal_origin_translate(struct thread *t,const struct md_signal_origin *origin,siginfo_t *info) {
    info->si_pid=md_pid_numbers_visible(origin->numbers,origin->count*sizeof(*origin->numbers),
        md_pid_identity_namespace(t->resources.pid),info->si_pid);
    info->si_uid=md_identity_visible_id(&t->identity,0,origin->uid);
    if (origin->reboot_signal && (info->si_code==CLD_EXITED || info->si_code==CLD_KILLED || info->si_code==CLD_DUMPED)) {
        info->si_code=CLD_KILLED; info->si_status=origin->reboot_signal;
    }
}

void signal_origin_consumed(struct thread *t,const siginfo_t *info) {
    md_signal_pending_consumed(t->signal_pending,info);
    if (md_signal_origin_matches(&t->signal_origin,info)) t->signal_origin.valid=0;
    if (md_signal_origin_matches(t->signal_delivery.child_origin,info)) {
        free(t->signal_delivery.child_origin); t->signal_delivery.child_origin=NULL;
    }
}

static int pending_child(void *context,const siginfo_t *info) {
    if (info->si_signo!=SIGCHLD) return 0;
    *(siginfo_t *)context=*info; return 1;
}

static int child_signals_capture(struct thread *t) {
    /* wait consumes a zombie, not its pending SIGCHLD. Preserve only the
     * kernel's actual coalesced slots before releasing registry ownership. */
    if (t) for (int scope=MD_SIGNAL_PRIVATE;scope<=MD_SIGNAL_SHARED;++scope) {
        siginfo_t info={0};
        int present=md_task_pending_scan_scope(native_trace,t->pid,
            scope==MD_SIGNAL_SHARED ? PTRACE_PEEKSIGINFO_SHARED : 0,pending_child,&info);
        if (present==-ESRCH) return 0;
        CHECK(present>=0);
        struct md_signal_origin origin={0};
        if (present) signal_origin_capture(t,&info,&origin);
        *md_signal_pending_child(t->signal_pending,scope)=origin;
    }
    return 1;
}

void process_reap(struct thread *t,pid_t child) {
    if (child_signals_capture(t)) md_task_registry_reap(tasks,child);
    else md_signal_pending_child_changed(t->signal_pending);
}

int signal_observe(struct thread *t,struct md_task_signal_stop *stop,int with_registers) {
    if (t->resume_lost) return 0;
    int error=md_task_signal_info(t->pid,stop);
    if (!error && with_registers) error=md_task_signal_registers(t->pid,stop);
    if (!error && with_registers && abi.signal_entry_committed && stop->regs.pc==abi.signal_entry_committed) {
        stop->regs.pc=stop->regs.regs[17];
        if (!set_registers(t->pid,&stop->regs)) return 0;
    }
    if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
    return !error;
}

int signal_replace_info(struct thread *t,struct md_task_signal_stop *stop,const siginfo_t *info) {
    CHECK(stop->has_info);
    if (!memcmp(&stop->info,info,sizeof(*info))) return 1;
    if (!tracee_request(t,PTRACE_SETSIGINFO,NULL,(void *)info,"signal-info-update")) return 0;
    stop->info=*info;
    return 1;
}

int signal_control_parked(const struct thread *t) {
    const struct signal_control *o=t->signal_control;
    return t->phase==SIGNAL_CONTROL && o && !o->scratch.pool && !o->memory.queue
        && !o->files.table && md_task_signal_control_parked(&o->control);
}

int signal_identity_allowed(const struct thread *from,const struct md_identity *identity,pid_t group,int signal) {
    return from->tgid==group || from->identity.uid.real==identity->uid.real
        || from->identity.uid.real==identity->uid.saved
        || from->identity.uid.effective==identity->uid.real
        || from->identity.uid.effective==identity->uid.saved
        || md_identity_capable_in(&from->identity,identity->user_namespace,CAP_KILL)
        || (signal==SIGCONT && getsid(from->tgid)==getsid(group));
}

int signal_allowed(const struct thread *from,const struct thread *to,int signal) {
    return signal_identity_allowed(from,&to->identity,to->tgid,signal);
}

static int namespace_init(const struct thread *t) {
    return md_pid_identity_number(t->resources.pid_group,md_pid_identity_namespace(t->resources.pid))==1;
}

void deliver_signal(pid_t pid,int sig,int restored,struct md_task_signal_stop *stop) {
    struct thread *t=thread(pid);
    struct md_task_signal_stop local;
    if (!stop) { md_task_signal_stop_init(&local,native_trace); stop=&local; }
    if (sig && sig!=SIGSTOP && !t->signal_delivery.active
            && ((!restored && md_signal_delivery_pending(&t->signal_delivery))
                || t->signal_shielded || t->metadata_restart)) {
        /* A lower-level owner can finish at an architectural boundary while
         * a higher-level restart still owns that boundary. Retain its signal
         * until every internal owner has restored the application context. */
        if (!signal_observe(t,stop,0)) return;
        siginfo_t info=stop->info;
        if (!md_signal_synchronous(&info) && info.si_signo<32) {
            int retained=md_signal_delivery_retain(&t->signal_delivery,pid,&info); CHECK(retained>=0);
            resume(pid,retained); return;
        }
    }
    if (sig && (t->resources.pid || sig==SIGCHLD)) {
        if (!signal_observe(t,stop,0)) return;
        siginfo_t info=stop->info;
        int fault=md_signal_synchronous(&info);
        if (namespace_init(t) && sig!=SIGKILL && sig!=SIGSTOP && !fault) {
            uint64_t caught=0;
            long error=0;
            int handled=md_signal_actions_caught(t->signal_actions,sig);
            if (handled>=0) caught=handled ? UINT64_C(1)<<(sig-1) : 0;
            else error=md_task_signal_handlers(t->pid,&caught);
            if (error) {
                fprintf(stderr,"Shroot: cannot observe PID init signal disposition: %ld\n",error);
                syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
                resume(pid,0); return;
            }
            if (!(caught&(UINT64_C(1)<<(sig-1)))) { resume(pid,0); return; }
        }
        if (md_signal_native_sender(&info)) {
            signal_wait_info(t,&info);
            if (!signal_replace_info(t,stop,&info)) return;
        }
    }
    if (sig && t->signal_delivery.first && !t->signal_delivery.active) {
        if (!signal_observe(t,stop,0)) return;
        int error=md_signal_delivery_before_handler(&t->signal_delivery,pid,&stop->info);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
    if (signal_control_delivery(t,sig,stop)) return;
    resume(pid,sig);
}

void resume_signal(pid_t pid,int sig,const struct md_signal_origin *origin) {
    /* Debugger release has already passed ordinary signal admission. */
    struct thread *t=thread(pid);
    if (origin && origin->valid) t->signal_origin=*origin;
    deliver_signal(pid,sig,1,NULL);
    t->signal_origin.valid=0;
}

int init_signal_suppressed(const struct thread *from,const struct thread *to,int signal) {
    if ((signal!=SIGKILL && signal!=SIGSTOP) || !namespace_init(to)) return 0;
    /* Kernel-only signals may force a child init only from an ancestor PID
     * namespace. Other signals retain blocking/handler semantics at delivery. */
    return md_pid_identity_namespace(from->resources.pid)==md_pid_identity_namespace(to->resources.pid);
}

static int signal_candidate(struct signal_selection *s,pid_t pid,const struct md_identity *identity) {
    if (pid<=s->after || (s->next && pid>=s->next)) return 0;
    pid_t visible=visible_pid(s->from,pid);
    if (!visible || (s->selector==-1 && (visible==1 || pid==s->from->tgid))
            || (s->selector!=-1 && getpgid(pid)!=s->group)) return 0;
    s->found=1;
    if (!signal_identity_allowed(s->from,identity,pid,s->signal)) return 0;
    s->next=pid; return 1;
}

static void signal_retired_candidate(void *context,pid_t pid,struct md_task_retired_view view) {
    struct signal_selection *s=context;
    if (signal_candidate(s,pid,view.identity)) s->suppressed=0;
}

static int signal_pending_wake(struct thread *t) {
    struct signal_control *owner=t->signal_control;
    if (owner && !owner->returning && !owner->entry_packet)
        return md_task_signal_control_wake(&owner->control);
    /* Published entry/return packets may be followed only by native IO. They
     * hold a mapping turn, not an interruptible borrowed syscall: require an
     * acknowledgement for newly deliverable signals as for mapping contention. */
    if (!t->host_signal_blocked && !t->kernel_stopped && !t->interrupt_pending) {
        if (native_trace(PTRACE_INTERRUPT,t->pid,NULL,NULL)<0)
            return errno==ESRCH ? 0 : -errno;
        t->interrupt_pending=1;
    }
    return 0;
}

int signal_logical_enqueue(struct thread *to,enum md_signal_scope scope,const siginfo_t *info) {
    struct md_kernel_action action;
    int error=md_signal_actions_read(to->signal_actions,SIGSYS,&action);
    if (error) return error;
    if (action.handler==(uintptr_t)SIG_IGN && !to->host_signal_blocked) return 0;
    error=md_signal_pending_put(to->signal_pending,scope,info);
    if (error<0) return error;
    for (struct thread *peer=threads;peer;peer=peer->next) {
        if (!peer->pid || !peer->born || (scope==MD_SIGNAL_PRIVATE ? peer!=to : peer->tgid!=to->tgid)) continue;
        task_readiness_changed(peer);
        error=signal_pending_wake(peer);
        if (error && error!=-ESRCH) return error;
    }
    return 0;
}

long signal_logical_send(struct thread *from,const struct user_pt_regs *r,const siginfo_t *supplied) {
    long nr=r->regs[8];
    int directed=nr==SYS_tgkill || nr==SYS_tkill || nr==SYS_rt_tgsigqueueinfo;
    int index=nr==SYS_tgkill || nr==SYS_rt_tgsigqueueinfo;
    pid_t pid=native_pid(from,(pid_t)r->regs[index]);
    struct thread *to=find_thread(pid);
    if (!to || !to->born || !to->host_signals) return -ESRCH;
    if (index && to->tgid!=native_pid(from,(pid_t)r->regs[0])) return -ESRCH;
    if (!signal_allowed(from,to,SIGSYS)) return -EPERM;
    if (supplied && (supplied->si_code>=0 || supplied->si_code==SI_TKILL) && pid!=from->pid) return -EPERM;
    if (init_signal_suppressed(from,to,SIGSYS)) return 0;
    siginfo_t info=supplied ? *supplied : (siginfo_t){.si_signo=SIGSYS,.si_code=directed ? SI_TKILL : SI_USER};
    if (!supplied) { info.si_pid=from->tgid; info.si_uid=getuid(); }
    info.si_signo=SIGSYS;
    return signal_logical_enqueue(to,directed ? MD_SIGNAL_PRIVATE : MD_SIGNAL_SHARED,&info);
}

long signal_process_next(struct thread *from,pid_t selector,int signal,pid_t after) {
    if (signal<0 || signal>=NSIG) return -EINVAL;
    pid_t group=selector==0 ? getpgid(from->tgid)
        : selector < -1 && selector!=INT32_MIN ? native_pid(from,-selector) : 0;
    if (selector!=-1 && group<=0) return -ESRCH;
    struct signal_selection selection={.from=from,.selector=selector,.group=group,.after=after,.signal=signal};
    for (struct thread *to=threads;to;to=to->next) {
        if (!to->pid || to->pid!=to->tgid || !to->born) continue;
        if (signal_candidate(&selection,to->pid,&to->identity))
            selection.suppressed=init_signal_suppressed(from,to,signal);
    }
    md_task_registry_retired_processes(tasks,signal_retired_candidate,&selection);
    struct thread *to=selection.next ? find_thread(selection.next) : NULL;
    if (signal==SIGSYS && to && to->host_signals && !selection.suppressed) {
        siginfo_t info={.si_signo=SIGSYS,.si_code=SI_USER};
        info.si_pid=from->tgid; info.si_uid=getuid();
        int error=signal_logical_enqueue(to,MD_SIGNAL_SHARED,&info);
        if (error) return error;
        selection.suppressed=1;
    }
    return selection.next ? ((long)selection.next<<1)|selection.suppressed
        : selection.found ? -EPERM : after ? 0 : -ESRCH;
}

void signal_control_discard(struct thread *t) {
    retry_clear(t,RETRY_SIGNAL);
    struct signal_control *owner=t->signal_control;
    if (!owner) return;
    if (owner->entry_previous) {
        *owner->entry_previous=owner->entry_next;
        if (owner->entry_next) owner->entry_next->entry_previous=owner->entry_previous;
    }
    if (owner->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    md_buffer_release(&owner->scratch);
    if (owner->memory.queue && md_resource_order_cancel(&owner->memory)==1)
        CHECK(!md_resource_order_complete(&owner->memory));
    if (owner->loan) {
        struct task_action *loan=owner->loan;
        if (loan->number>=0 && !cancelling) {
            md_fd_origins_finish(&owner->publication,loan->number);
            loan_retire(loan,NULL,NULL,NULL);
        } else {
            if (loan->loan) md_fd_loan_end(loan->loan);
            md_fd_loan_release(loan->loan); md_fd_origins_unpin(loan->loan_table); free(loan);
        }
    }
    md_fd_origins_finish(&owner->publication,-1);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    md_fd_object_release(owner->descriptor);
    md_task_signal_control_dispose(&owner->control);
    md_memory_page_release(owner->wait_page);
    free(owner); t->signal_control=NULL;
}

static void signal_entry_complete(struct thread *t,int failed) {
    struct signal_control *owner=t->signal_control;
    struct md_kernel_action entered;
    CHECK(!md_signal_actions_enter(t->signal_actions,owner->signal,&entered));
    if (failed) {
        /* Frame construction failed before the kernel applied the action's
         * mask. A failed SIGSEGV frame also forces its disposition to DFL. */
        t->host_signal_blocked=owner->entry_blocked;
        t->signal_wait_view=owner->entry_wait_view;
        t->signal_handler_mask_known=0;
        if (owner->signal==SIGSEGV) {
            entered.handler=(uintptr_t)SIG_DFL;
            CHECK(!md_signal_actions_commit(t->signal_actions,SIGSEGV,&entered));
        }
    }
    signal_control_discard(t); t->phase=IDLE;
}

int signal_entry_committed(struct thread *t) {
    struct signal_control *owner=t->signal_control;
    if (!owner || owner->returning || !owner->entry_packet
            || atomic_load_explicit(&owner->entry_packet->pending,memory_order_acquire)) return 0;
    signal_entry_complete(t,0);
    return 1;
}

void signal_entries_progress(void) {
    for (struct signal_control *owner=signal_entries,*next;owner;owner=next) {
        next=owner->entry_next;
        struct thread *t=owner->entry_thread;
        int committed=signal_entry_committed(t);
        if (md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
                || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED))
            CHECK(!signal_pending_wake(t));
        if (committed) continue;
        if (!t->kernel_stopped && !t->interrupt_pending) {
            struct md_resource_queue *queue=owner->memory.queue;
            pthread_mutex_lock(&queue->lock);
            int contended=owner->memory.next!=NULL;
            pthread_mutex_unlock(&queue->lock);
            if (contended) {
                /* EVENT_WAIT: only a competing resource mutation needs an
                 * acknowledgement before the handler's next ordinary stop. */
                if (tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"signal-entry-contended"))
                    t->interrupt_pending=1;
            }
        }
    }
}

static void signal_wait_info(void *context,siginfo_t *info) {
    struct thread *t=context;
    if (md_signal_child_notice(info)) {
        struct md_signal_origin origin;
        signal_origin_capture(t,info,&origin);
        if (origin.valid) {
            signal_origin_consumed(t,info);
            signal_origin_translate(t,&origin,info); return;
        }
    }
    if (md_signal_native_sender(info)) {
        if (info->si_signo==SIGCHLD)
            process_reboot_info(info->si_pid,&info->si_code,&info->si_status);
        info->si_uid=process_signal_uid(t,info->si_pid,info->si_uid);
        info->si_pid=visible_pid(t,info->si_pid);
    }
}

void signal_query_publish(struct thread *t) {
    if (!t->host_signals) return;
    int native=!t->host_signal_blocked && !t->signal_shielded && !t->signal_delivery.active
        && (t->phase==IDLE || (t->phase==SIGNAL_ENTRY && t->signal_control && t->signal_control->entry_packet))
        && !md_seccomp_policy_may_trace(t->policy) && !md_seccomp_policy_may_notify(t->policy)
        && !t->debug_options && !t->debug_call_active;
    if (native!=t->native_signal_query) {
        md_broker_signal_query(broker,t->pid,native);
        t->native_signal_query=native;
    }
}

static void signal_descriptor_retired(struct thread *t,long error) {
    CHECK(!error);
    signal_control_finish(t,t->signal_control->retire_error);
}

static void signal_descriptor_selected(struct thread *t,long error) {
    struct signal_control *owner=t->signal_control;
    if (error) {
        if (owner->loan && owner->loan->number>=0) {
            struct task_action *loan=owner->loan; owner->loan=NULL;
            owner->retiring=1; owner->retire_error=error<0 ? (int)error : -EAGAIN;
            md_fd_origins_finish(&owner->publication,loan->number);
            loan_retire(loan,t,&owner->files,signal_descriptor_retired);
        } else signal_control_finish(t,error<0 ? (int)error : -EAGAIN);
        return;
    }
    unsigned required=md_fd_object_selectors(owner->descriptor);
    while (owner->selector<MD_FD_SELECTOR_KINDS) {
        unsigned kind=owner->selector++;
        if (!(required&(1U<<kind))) continue;
        int selected=selector_has(t,kind,(int)owner->descriptor_result);
        if (selected<0) { signal_descriptor_selected(t,selected); return; }
        if (selected) continue;
        if (owner->checkpoint_suspended) {
            md_task_checkpoint_restore(&checkpoints,t->pid); owner->checkpoint_suspended=0;
        }
        struct task_action *action=task_action_new(t);
        if (!action) { signal_descriptor_selected(t,-ENOMEM); return; }
        action->continuation=t->original; action->boundary=owner->control.boundary;
        action->kind=(enum md_fd_selector_kind)kind; action->number=(int)owner->descriptor_result;
        action->complete=signal_descriptor_selected;
        task_action_start(t); return;
    }
    md_fd_origins_release_custody(&owner->publication);
    md_fd_origins_finish(&owner->publication,(int)owner->descriptor_result);
    if ((int)t->original.regs[0]>=0)
        CHECK(!md_fd_origins_set_flags(t->resources.files,(int)owner->descriptor_result,owner->control.descriptor_flags));
    if (owner->loan) {
        md_fd_loan_end(owner->loan->loan); md_fd_loan_release(owner->loan->loan);
        md_fd_origins_unpin(owner->loan->loan_table); free(owner->loan); owner->loan=NULL;
    }
    CHECK(!md_fd_order_complete(&owner->files));
    signal_control_finish(t,MD_SIGNAL_WAIT_ACQUIRE);
}

static void signal_control_finish(struct thread *t,int ready) {
    struct signal_control *owner=t->signal_control;
    if (ready==MD_SIGNAL_FD_ACQUIRE) {
        CHECK(!md_resource_order_complete(&owner->memory));
        owner->descriptor_admission=1;
        int error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error<0) signal_control_finish(t,error); else signal_control_progress(t);
        return;
    }
    if (ready==MD_SIGNAL_FD_RELEASE) {
        long value=owner->control.descriptor_result;
        if (owner->control.descriptor==1) {
            if (value>=0) {
                struct md_signal_fd *file=md_signal_fd_from_object(owner->descriptor);
                md_signal_fd_update(file,owner->control.wait_set);
                for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid && peer!=t) {
                    task_readiness_changed(peer);
                    if (peer->signal_control) {
                        int error=md_task_signal_descriptor_changed(&peer->signal_control->control,file);
                        CHECK(!error || error==-ESRCH);
                    }
                }
            }
        }
        if (owner->control.descriptor==1 && value>=0) {
            owner->descriptor_result=value;
            if (owner->loan) owner->loan->number=(int)value;
            signal_descriptor_selected(t,0); return;
        }
        md_fd_origins_finish(&owner->publication,-1);
        CHECK(!md_fd_order_complete(&owner->files));
        ready=MD_SIGNAL_WAIT_ACQUIRE;
    }
    if (ready==MD_SIGNAL_WAIT_RELEASE || ready==MD_SIGNAL_WAIT_STORAGE) {
        if (ready==MD_SIGNAL_WAIT_STORAGE) {
            md_memory_page_publish(owner->wait_page,(uintptr_t)owner->control.native.result);
        } else if (!owner->wait_page) {
            owner->wait_page=md_memory_page_acquire(t->memory);
            if (!owner->wait_page) { signal_control_finish(t,-ENOMEM); return; }
        }
        signal_control_finish(t,md_task_signal_wait_park(&owner->control,md_memory_page_address(owner->wait_page)));
        return;
    }
    if (ready==MD_SIGNAL_WAIT_PARKED) {
        CHECK(!md_buffer_release(&owner->scratch));
        owner->control.scratch=NULL; owner->control.peer=0; owner->control.capacity=0;
        CHECK(!md_resource_order_complete(&owner->memory));
        signal_control_finish(t,md_task_signal_control_continue(&owner->control));
        return;
    }
    if (ready==MD_SIGNAL_WAIT_ACQUIRE) {
        owner->reacquiring=1;
        signal_control_progress(t);
        return;
    }
    if (ready==-ESRCH) { t->resume_lost=1; return; }
    if (!ready) { t->kernel_stopped=0; return; }
    long result=ready<0 ? ready : owner->control.result;
    SUPERVISOR_TRACE("PROBE signal-control-complete pid=%d phase=%d signal=%d result=%ld handler=%#lx mask=%#llx\n",
        t->pid,t->phase,owner->signal,result,(unsigned long)owner->action.handler,
        (unsigned long long)owner->control.next_mask);
    if (owner->forced.si_signo) {
        if (result) {
            fprintf(stderr,"Shroot: forced signal disposition failed: %ld\n",result);
            syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
        } else CHECK(!md_signal_delivery_seccomp(&t->signal_delivery,&owner->forced));
        signal_control_discard(t); t->phase=IDLE; resume(t->pid,0); return;
    }
    if (owner->signal) {
        if (result) {
            fprintf(stderr,"Shroot: signal frame translation failed: %ld\n",result);
            syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
            signal_control_discard(t); t->phase=IDLE; resume(t->pid,0); return;
        }
        signal_control_discard(t); t->phase=IDLE;
        /* The kernel applied the action's native mask while building this
         * frame. Frame translation changes only the logical SIGSYS view;
         * borrowed copies restore that same native mask before completion. */
        resume(t->pid,0);
        return;
    }
    struct user_pt_regs original=t->original;
    if (!result && owner->control.sending)
        result=signal_logical_send(t,&original,&owner->control.wait_info);
    int passthrough=owner->control.passthrough;
    int restarting=owner->control.descriptor==2 && result==-EINTR;
    uint64_t restart_mask=owner->control.native.mask;
    enum md_task_call_boundary restart_at=owner->control.native.boundary;
    enum md_task_call_boundary boundary=owner->control.boundary;
    if (!result && original.regs[8]==SYS_rt_sigaction && original.regs[0]==SIGSYS
            && original.regs[1] && owner->control.requested.handler==(uintptr_t)SIG_IGN)
        for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer->tgid==t->tgid) md_signal_pending_clear(peer->signal_pending,1);
    signal_control_discard(t); t->phase=IDLE;
    if (restarting) { restart_boundary(t,&original,restart_mask,RESTART_SIGNAL,restart_at); return; }
    if (passthrough) {
        unsigned long cookie=MD_INTERCEPT_PROCESS;
        if (!process_call(t,&original,&cookie) && set_registers(t->pid,&original)) resume(t->pid,0);
        return;
    }
    if (original.regs[8]==SYS_rt_sigprocmask && t->metadata_restart) {
        metadata_restart_return(t,result); return;
    }
    return_task_value(t,&original,result,boundary);
}

void signal_control_progress(struct thread *t) {
    retry_clear(t,RETRY_SIGNAL);
    struct signal_control *owner=t->signal_control;
    CHECK(owner && (!owner->started || owner->reacquiring || owner->descriptor_admission));
    if (owner->descriptor_admission) {
        if (md_fd_order_state(&owner->files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_SIGNAL); return; }
        CHECK(!md_fd_order_start(&owner->files));
        owner->descriptor_admission=0;
        int descriptor=(int)t->original.regs[0];
        owner->descriptor=descriptor<0 ? NULL : md_fd_origins_object(t->resources.files,descriptor,NULL);
        int error=0;
        if (t->original.regs[8]==SYS_signalfd4 && !md_signal_fd_from_object(owner->descriptor)) {
            md_fd_object_release(owner->descriptor);
            owner->descriptor=md_signal_fd_object(md_signal_fd_new());
            error=owner->descriptor ? md_fd_origins_prepare_object(t->resources.files,owner->descriptor,
                t->original.regs[3]&SFD_CLOEXEC ? FD_CLOEXEC : 0,&owner->publication) : -ENOMEM;
            if (!error && descriptor<0) {
                owner->loan=calloc(1,sizeof(*owner->loan));
                if (!owner->loan) error=-ENOMEM;
                else {
                    owner->loan->number=-1;
                    owner->loan->loan_table=md_fd_origins_pin(t->resources.files);
                    owner->loan->loan=md_fd_loan_new(t->resources.files);
                    error=owner->loan->loan ? md_fd_origins_prepare_custody(&owner->publication,
                        owner->loan->loan,&owner->files) : -ENOMEM;
                }
            }
        }
        signal_control_finish(t,error ? error
            : md_task_signal_descriptor_admit(&owner->control,md_signal_fd_from_object(owner->descriptor)));
        return;
    }
    /* Buffer admission precedes the memory turn: a returning reader can still
     * own its buffer while another reader is acquiring its FD. Holding the
     * memory queue while awaiting a pool slot would deadlock their completion. */
    int wrapped=owner->signal && md_signal_actions_entry(t->signal_actions,owner->signal);
    int native_frame=owner->signal && !t->signal_wait_view.active && !wrapped;
    int error=owner->scratch.pool || native_frame ? 0 : md_buffers_take(md_memory_buffers(t->memory),&owner->scratch);
    if (error==-EAGAIN) { retry_wait(t,RETRY_SIGNAL); return; }
    if (error) { signal_control_finish(t,error); return; }
    if (!owner->memory.queue) {
        error=md_memory_order_begin(t->memory,&owner->memory,fd_wakeup);
        if (error<0) { signal_control_finish(t,error); return; }
    }
    enum md_resource_order_state state=md_resource_order_state(&owner->memory);
    if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&owner->memory));
    else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_SIGNAL); return; }
    if (owner->reacquiring) {
        struct md_buffer_span span; CHECK(!md_buffer_span(&owner->scratch,&span));
        owner->control.scratch=span.data;
        owner->control.peer=md_memory_buffer_address(t->memory,&owner->scratch);
        owner->control.capacity=span.size;
        owner->reacquiring=0;
        signal_control_finish(t,md_task_signal_control_continue(&owner->control));
        return;
    }
    owner->started=1;
    if (owner->signal) {
        struct md_kernel_action action;
        error=md_signal_actions_read(t->signal_actions,owner->signal,&action);
        if (error || action.handler!=owner->action.handler || action.flags!=owner->action.flags
                || action.mask!=owner->action.mask) {
            /* A preceding disposition exchange won the admission turn. The
             * kernel must select that new action at the retained delivery stop. */
            int signal=owner->signal;
            signal_control_discard(t); t->phase=IDLE;
            deliver_signal(t->pid,signal,1,NULL); return;
        }
        t->phase=SIGNAL_ENTRY;
        if (wrapped) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&owner->scratch,&span));
            struct md_signal_entry *packet=span.data;
            uint64_t saved=owner->entry_mask;
            uint64_t mask=md_signal_view_enter(owner->signal,&owner->action,t->host_signal_blocked,
                &t->signal_wait_view,&saved);
            if (!owner->entry_info.si_signo
                    && !tracee_request(t,PTRACE_GETSIGINFO,NULL,&owner->entry_info,"signal-entry-info")) return;
            *packet=(struct md_signal_entry){.handler=owner->action.handler,
                .mask=saved,.pending=1,.info=owner->entry_info};
            siginfo_t transport=owner->entry_info;
            transport.si_value.sival_ptr=(void *)md_memory_buffer_address(t->memory,&owner->scratch);
            if (!tracee_request(t,PTRACE_SETSIGINFO,NULL,&transport,"signal-entry-packet")) return;
            owner->entry_blocked=t->host_signal_blocked;
            owner->entry_wait_view=t->signal_wait_view;
            t->host_signal_blocked=!!(mask&MD_SIGNAL_SYS_BIT);
            t->signal_handler_mask=mask;
            t->signal_handler_mask_known=1;
            t->signal_wait_view.active=0;
            owner->entry_packet=packet; owner->entry_thread=t;
            owner->entry_next=signal_entries; owner->entry_previous=&signal_entries;
            if (signal_entries) signal_entries->entry_previous=&owner->entry_next;
            signal_entries=owner;
            if (broker) CHECK(!md_broker_task(broker,t->pid,0,RAW_GATE,abi.watch_return,NULL,0));
            signal_query_publish(t);
            t->kernel_resume=PTRACE_CONT;
            if (native_trace(PTRACE_CONT,t->pid,NULL,(void *)(uintptr_t)owner->signal)<0) {
                CHECK(errno==ESRCH); t->resume_lost=1;
            } else t->kernel_stopped=0;
            return;
        }
        /* Outside atomic waits the native frame can carry the logical mask
         * directly. The commit stop removes SIGSYS before the handler runs;
         * no protected-memory copy is needed to amend the saved frame. Never
         * replace a temporary wait mask here: SETSIGMASK clears its restore. */
        if (!t->signal_wait_view.active && t->host_signal_blocked
                && !signal_mask(t,owner->entry_mask|MD_SIGNAL_SYS_BIT,"handler-frame-logical-mask")) return;
        /* EVENT_WAIT: kernel signal-frame construction precedes this interrupt
         * stop; no veneer or application instruction executes before admission.
         * Exit/exec cancels the owner through the ordinary task lifecycle. */
        if (!tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"handler-entry-commit")) return;
        t->kernel_resume=PTRACE_CONT;
        if (native_trace(t->kernel_resume,t->pid,NULL,(void *)(uintptr_t)owner->signal)<0) {
            CHECK(errno==ESRCH); t->resume_lost=1;
        } else t->kernel_stopped=0;
        return;
    }
    int boundary=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (boundary<0) { signal_control_finish(t,boundary); return; }
    CHECK(!boundary); owner->checkpoint_suspended=1;
    struct md_buffer_span span; CHECK(!md_buffer_span(&owner->scratch,&span));
    int ready=owner->forced.si_signo
        ? md_task_signal_default_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_actions,&t->host_signal_blocked,&owner->action,
            span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_rt_sigprocmask
        ? md_task_signal_query_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,owner->entry_mask,
            span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_signalfd4 || t->original.regs[8]==SYS_read || t->original.regs[8]==SYS_readv
        ? md_task_signal_descriptor_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_pending,&t->host_signal_blocked,
            signal_wait_info,t,span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_rt_sigtimedwait
        ? md_task_signal_wait_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_pending,&t->host_signal_blocked,
            signal_wait_info,t,span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_rt_sigqueueinfo || t->original.regs[8]==SYS_rt_tgsigqueueinfo
        ? md_task_signal_send_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : md_task_signal_control_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_actions,&t->host_signal_blocked,
            (md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
                || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED)) ? MD_SIGNAL_SYS_BIT : 0,
            span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size);
    signal_control_finish(t,ready);
}

static int signal_return_snapshot(struct thread *t) {
    if (statistics) ++statistics->signal_frames[0];
    uintptr_t base=md_memory_readonly_base(t->memory);
    if (!base || t->signal_control || t->signal_return_pending || t->metadata_restart || t->signal_delivery.active
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    /* A queued carrier must borrow the restored application's mask, not the
     * handler's mask at this pre-execution stop. Pending logical SIGSYS can
     * also become a carrier when the restored mask unblocks it in resume(). */
    if (md_signal_delivery_pending(&t->signal_delivery)
            || md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
            || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED)) return 0;
    if (statistics) ++statistics->signal_frames[1];
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    if (regs.sp%16) return 0;
    struct signal_control *owner=calloc(1,sizeof(*owner));
    if (!owner) return 0;
    struct md_buffer_span span;
    int error=md_buffers_take(md_memory_readonly_buffers(t->memory),&owner->scratch);
    if (!error) error=md_buffer_span(&owner->scratch,&span);
    if (!error && span.size<sizeof(struct md_signal_frame)) error=-ENOSPC;
    if (!error) error=md_memory_order_begin(t->memory,&owner->memory,fd_wakeup);
    if (error>=0 && md_resource_order_state(&owner->memory)==MD_RESOURCE_ORDER_READY) {
        CHECK(!md_resource_order_start(&owner->memory));
        if (statistics) ++statistics->signal_frames[2];
        struct md_signal_frame *frame=span.data;
        ssize_t copied=md_task_copy_direct(t->pid,regs.sp,frame,sizeof(*frame),0);
        if (statistics && copied==sizeof(*frame)) ++statistics->signal_frames[3];
        if (copied==sizeof(*frame) && md_signal_frame_relocatable(frame)) {
            if (statistics) ++statistics->signal_frames[4];
            uint64_t mask;
            memcpy(&mask,&frame->context.uc_sigmask,sizeof(mask));
            t->host_signal_blocked=!!(mask&MD_SIGNAL_SYS_BIT);
            t->signal_handler_mask=mask&md_signal_blockable_mask();
            t->signal_handler_mask_known=1;
            mask&=~MD_SIGNAL_SYS_BIT;
            memcpy(&frame->context.uc_sigmask,&mask,sizeof(mask));
            regs.sp=base+span.offset;
            owner->returning=1; owner->entry_thread=t;
            owner->entry_next=signal_entries; owner->entry_previous=&signal_entries;
            if (signal_entries) signal_entries->entry_previous=&owner->entry_next;
            signal_entries=owner; t->signal_control=owner;
            /* The original syscall has passed kernel seccomp. Its immutable
             * input owns the mapping turn until the next native stop. A
             * competing mapping operation requests an acknowledgement only
             * when needed; ordinary returns need no dedicated exit stop. */
            if (set_registers(t->pid,&regs)) resume(t->pid,0);
            return 1;
        }
    }
    if (owner->memory.queue && md_resource_order_cancel(&owner->memory)==1)
        CHECK(!md_resource_order_complete(&owner->memory));
    md_buffer_release(&owner->scratch); free(owner);
    return 0;
}

int signal_native_call(struct thread *t,uint64_t number,uint64_t input,uint64_t output,uint64_t size) {
    if (number==SYS_rt_sigreturn) {
        t->signal_handler_mask_known=0;
        if (signal_return_snapshot(t)) return 1;
        if (t->signal_delivery.active && !signal_mask(t,t->signal_delivery.mask,"signal-return-carrier-mask")) return 1;
        t->phase=SIGNAL_NATIVE_EXIT; resume(t->pid,0); return 1;
    }
    if (number==SYS_rt_sigprocmask) {
        if (input || (output && (t->host_signal_blocked || t->signal_delivery.active))) {
            if (input) t->signal_handler_mask_known=0;
            /* This original syscall has passed seccomp admission. Let the
             * kernel query or update its logical mask, with native pointer
             * validation and error/commit ordering.
             * EVENT_WAIT: syscall exit removes the borrowed SIGSYS bit before
             * any application instruction or handler can execute. */
            if (t->host_signal_blocked || t->signal_delivery.active) {
                uint64_t mask;
                if (t->signal_delivery.active) mask=t->signal_delivery.mask;
                else if (!save_signal_mask(t,&mask,"signal-call-mask")) return 1;
                uint64_t logical=md_signal_view_mask(mask,t->host_signal_blocked);
                if (!input && output && size==sizeof(mask)) {
                    ssize_t copied=md_task_copy_direct(t->pid,output,&logical,sizeof(logical),1);
                    /* The query has already passed original-site seccomp.
                     * A kernel-checked copy can complete it at this same stop,
                     * without lending the mask or running a second guest turn.
                     * Denied inspection retains the native protected path. */
                    if (copied>=0 || copied==-EFAULT) {
                        return_scalar(t,copied==sizeof(logical) ? 0 : -EFAULT);
                        return 1;
                    }
                }
                if (!signal_mask(t,logical,"signal-call-logical-mask")) return 1;
                if (!input) {
                    t->signal_query_mask=mask;
                    t->signal_query_active=1;
                }
            }
            t->phase=SIGNAL_NATIVE_EXIT;
        }
        resume(t->pid,0); return 1;
    }
    return 0;
}

static int signal_native_mask_commit(struct thread *t,uint64_t mask) {
    if (!t->signal_delivery.active) return signal_mask(t,mask,"signal-native-restore-mask");
    int error=md_signal_delivery_update_mask(&t->signal_delivery,t->pid,mask);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error); t->signal_handler_mask_known=0;
    return 1;
}

void signal_native_complete(struct thread *t) {
    if (t->signal_query_active) {
        /* A pure query cannot change the mask, even on EFAULT or EINVAL.
         * No guest instruction runs in this loan; restore its exact snapshot
         * instead of reading the same mask again at the owned syscall exit. */
        if (!signal_native_mask_commit(t,t->signal_query_mask)) return;
        t->signal_query_active=0; t->phase=IDLE; resume(t->pid,0);
        return;
    }
    uint64_t mask;
    if (!save_signal_mask(t,&mask,"signal-native-exit-mask")) return;
    t->host_signal_blocked=!!(mask&MD_SIGNAL_SYS_BIT);
    t->phase=IDLE;
    if ((!t->signal_delivery.active && !(mask&MD_SIGNAL_SYS_BIT))
            || signal_native_mask_commit(t,mask&~MD_SIGNAL_SYS_BIT)) resume(t->pid,0);
}

int signal_control_begin(struct thread *t,const struct user_pt_regs *r,int signal,
        const struct md_kernel_action *action,uint64_t entry_mask,const siginfo_t *info) {
    CHECK(!t->signal_control);
    struct signal_control *owner=calloc(1,sizeof(*owner));
    if (!owner) return -ENOMEM;
    owner->signal=signal;
    owner->entry_mask=entry_mask;
    if (info) owner->entry_info=*info;
    if (action) owner->action=*action;
    t->signal_control=owner;
    if (r) t->original=*r;
    t->phase=SIGNAL_CONTROL;
    signal_control_progress(t); return 0;
}

static int signal_frame_native(const struct thread *t) {
    return !t->host_signal_blocked && !t->signal_wait_view.active;
}

static int signal_control_delivery(struct thread *t,int signal,struct md_task_signal_stop *stop) {
    if (!signal || !t->host_signals || !t->entered || t->phase!=IDLE) return 0;
    struct md_kernel_action action;
    if (md_signal_actions_read(t->signal_actions,signal,&action)
            || action.handler==(uintptr_t)SIG_DFL || action.handler==(uintptr_t)SIG_IGN) return 0;
    /* A retained carrier restores the application's mask before admission.
     * A blocked signal goes back to the kernel pending queue, not through a
     * handler veneer: no handler frame will be built at this delivery stop. */
    uint64_t mask;
    /* GETSIGMASK exposes saved_sigmask while an interrupted atomic wait
     * retains TIF_RESTORE_SIGMASK. Delivery still uses its temporary mask. */
    if (t->signal_return_pending && t->signal_wait_view.active)
        mask=t->signal_wait_view.temporary;
    else if (!tracee_request(t,PTRACE_GETSIGMASK,(void *)sizeof(mask),&mask,"handler-delivery-mask")) return 1;
    if (mask&(UINT64_C(1)<<(signal-1))) return 0;
    if (signal_frame_native(t) && md_signal_actions_exclusive(t->signal_actions)
            && !md_signal_actions_entry(t->signal_actions,signal)) {
        /* No peer can replace this disposition between selection and native
         * frame construction. The saved frame is already logical; the native
         * action keeps SIGSYS clear while its logical handler mask is owned
         * here. No frame edit or commit stop is necessary. */
        struct md_kernel_action entered;
        CHECK(!md_signal_actions_enter(t->signal_actions,signal,&entered));
        uint64_t saved=mask;
        t->host_signal_blocked=!!(md_signal_view_enter(signal,&entered,0,NULL,&saved)&MD_SIGNAL_SYS_BIT);
        resume(t->pid,signal); return 1;
    }
    int error=signal_control_begin(t,NULL,signal,&action,mask,stop && stop->has_info ? &stop->info : NULL);
    if (error) {
        fprintf(stderr,"Shroot: signal admission failed: %d\n",error);
        syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL); resume(t->pid,0);
    }
    return 1;
}

int signal_control_event(struct thread *t,int status) {
    struct signal_control *owner=t->signal_control;
    if (!owner || owner->retiring || !owner->started || !WIFSTOPPED(status)
            || (unsigned)status>>16==PTRACE_EVENT_EXIT || (unsigned)status>>16==PTRACE_EVENT_EXEC) return 0;
    if (owner->entry_packet) {
        struct user_pt_regs regs;
        if (atomic_load_explicit(&owner->entry_packet->pending,memory_order_acquire)) {
            if (!registers(t->pid,&regs)) return 1;
            if (regs.pc>=abi.signal_entry && regs.pc<abi.signal_entry_committed) {
                struct md_signal_entry *packet=owner->entry_packet;
                CHECK(md_task_copy_direct(t->pid,regs.regs[1],
                    &packet->info,sizeof(packet->info),1)==sizeof(packet->info));
                CHECK(md_task_copy_direct(t->pid,regs.regs[2]+MD_SIGNAL_CONTEXT_MASK,
                    &packet->mask,sizeof(packet->mask),1)==sizeof(packet->mask));
                regs.pc=packet->handler;
                if (!set_registers(t->pid,&regs)) return 1;
            } else {
                /* A delivery stop before frame construction retains its owner. */
                if ((unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
                    t->kernel_resume=PTRACE_CONT;
                    if (!tracee_request(t,PTRACE_CONT,NULL,NULL,"signal-entry-pending")) return 1;
                    t->kernel_stopped=0; return 1;
                }
                if (!((unsigned)status>>16) && WSTOPSIG(status)==SIGSEGV) {
                    siginfo_t info;
                    if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"signal-frame-failure")) return 1;
                    if (info.si_code==SI_KERNEL) {
                        signal_entry_complete(t,1); return 0;
                    }
                }
                CHECK(0);
            }
        }
        signal_entry_complete(t,0);
        if ((unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
            resume(t->pid,0); return 1;
        }
        return 0;
    }
    struct md_task_call *call=md_task_signal_control_call(&owner->control);
    if (call) {
        if (task_call_signal(t,call,status)) return 1;
        signal_control_finish(t,md_task_signal_control_event(&owner->control,status)); return 1;
    }
    if (t->phase!=SIGNAL_ENTRY || (unsigned)status>>16!=PTRACE_EVENT_STOP
            || WSTOPSIG(status)!=SIGTRAP) return 0;
    struct md_kernel_action entered;
    CHECK(!md_signal_actions_enter(t->signal_actions,owner->signal,&entered));
    if (!t->signal_wait_view.active) {
        uint64_t saved=owner->entry_mask;
        uint64_t next=md_signal_view_enter(owner->signal,&owner->action,t->host_signal_blocked,NULL,&saved);
        if (t->host_signal_blocked
                && !signal_mask(t,next&~MD_SIGNAL_SYS_BIT,"handler-frame-native-mask")) return 1;
        t->host_signal_blocked=!!(next&MD_SIGNAL_SYS_BIT);
        signal_control_discard(t); t->phase=IDLE; resume(t->pid,0); return 1;
    }
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    SUPERVISOR_TRACE("PROBE signal-entry pid=%d phase=%d pc=%#llx signal=%llu\n",t->pid,t->phase,regs.pc,regs.regs[0]);
    CHECK(regs.pc==owner->action.handler && regs.regs[0]==(unsigned)owner->signal);
    struct md_buffer_span span={0};
    if (owner->scratch.pool) CHECK(!md_buffer_span(&owner->scratch,&span));
    int ready=md_task_signal_wait_entry_begin(&owner->control,native_trace,t->pid,&regs,
        abi.task_call,abi.task_return,t->signal_actions,&t->host_signal_blocked,&owner->action,
        &t->signal_wait_view,span.data,
        owner->scratch.pool ? md_memory_buffer_address(t->memory,&owner->scratch) : 0,span.size);
    t->signal_wait_view.active=0;
    signal_control_finish(t,ready); return 1;
}

void child_signals_observe(struct thread *t,int status) {
    if (!t || !WIFSTOPPED(status) || !md_signal_pending_child_dirty(t->signal_pending)) return;
    if (!((unsigned)status>>16) && WSTOPSIG(status)==SIGCHLD) {
        siginfo_t info;
        if (native_trace(PTRACE_GETSIGINFO,t->pid,NULL,&info)<0) { CHECK(errno==ESRCH); return; }
        struct md_signal_origin origin;
        signal_origin_capture(t,&info,&origin); t->signal_origin=origin;
    }
    if (!child_signals_capture(t)) return;
    md_signal_pending_child_observed(t->signal_pending);
    pid_collection_pending=1;
}

void signals_buffer_control(struct thread *t,const struct user_pt_regs *r) {
    long result=-EINVAL;
    switch (r->regs[0]) {
    case MD_BUFFER_SIGNAL_ENABLE: {
        struct { struct md_kernel_action action; uint64_t mask; } initial;
        if (t->entered || t->phase!=IDLE || !md_memory_buffers(t->memory)
                || r->regs[2]!=sizeof(initial)) break;
        ssize_t copied=md_task_copy_direct(t->pid,r->regs[1],&initial,sizeof(initial),0);
        if (copied!=sizeof(initial)) { result=copied<0 ? copied : -EFAULT; break; }
        if (!t->signal_actions) {
            /* This first bootstrap handshake follows the native exec event.
             * Later images and clone children already retain their owner. */
            t->signal_actions=md_signal_actions_exec(NULL);
            if (!t->signal_actions) { result=-ENOMEM; break; }
            result=md_signal_actions_commit(t->signal_actions,SIGSYS,&initial.action);
            if (result) break;
            t->host_signal_blocked=!!(initial.mask&MD_SIGNAL_SYS_BIT);
        }
        if (!t->signal_pending) t->signal_pending=md_signal_pending_new();
        if (!t->signal_pending) { result=-ENOMEM; break; }
        md_signal_actions_entry_configure(t->signal_actions,abi.signal_entry);
        t->host_signals=1; result=0;
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}

void signals_event(struct thread *t,pid_t pid,int status,int sig,struct md_task_signal_stop *signal_stop,int restored_signal) {
    SUPERVISOR_TRACE("PROBE signal stop pid=%d signal=%d phase=%d\n",pid,sig,t->phase);
    if (sig==SIGSYS && !restored_signal) {
        int error=md_svc_machine_seccomp(md_memory_svc(t->memory),pid,signal_stop);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
    if (sig==SIGSYS && !restored_signal && host_policy_signal(t,signal_stop)) return;
    if (sig==SIGSTOP && md_debugger_traced(debugger,pid) && !debugger_at_boundary(t)
            && signal_observe(t,signal_stop,0)
            && md_debugger_defer_stop(debugger,pid,&signal_stop->info)) {
        /* EVENT_WAIT: finish the internal operation before publishing
         * the debugger stop; task exit/cancellation releases its owner.
         * Delivering SIGSTOP now would group-stop shared resource owners. */
        resume(pid,0); return;
    }
    if (md_debugger_traced(debugger,pid) && debugger_at_boundary(t)
            && signal_observe(t,signal_stop,0)
            && md_debugger_stop(debugger,pid,status,&signal_stop->info,0)) return;
    struct user_pt_regs regs={0};
    if (sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL
                || ((diagnostics || signal_diagnostics) && sig == SIGTRAP)) {
        if (!signal_observe(t,signal_stop,1)) return;
        regs=signal_stop->regs;
    }
    if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == DOMAIN_PATH
            && regs.pc == LOAD_BYTE) {
        domain_path_finish(t, -EFAULT); return;
    }
    if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == IDENTITY_COPY
            && ((regs.pc >= abi.load_groups && regs.pc < abi.loaded_groups)
                || (regs.pc >= abi.store_groups && regs.pc < abi.stored_groups))) {
        identity_copy_finish(t, -EFAULT); return;
    }
    if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == STORING_IDS) {
        if (regs.pc >= STORE_IDS && regs.pc < STORED_IDS) {
            regs = t->original; regs.regs[0] = (uint64_t)-EFAULT;
            if (!set_registers(pid, &regs) || !restore_mask(t)) return;
            t->phase = IDLE; resume(pid, 0); return;
        }
    }
    if ((sig==SIGSEGV || sig==SIGBUS) && t->phase==PROCESS_TID_STORE
            && regs.pc>=abi.store_groups && regs.pc<abi.stored_groups) {
        /* Linux ignores a failed CLONE_*SETTID store. The child still
         * exists; never report a second failed clone to its parent. */
        if (!set_registers(pid,&t->original) || !restore_mask(t)) return;
        t->phase=IDLE; resume(pid,0); return;
    }
    if ((sig==SIGSEGV || sig==SIGBUS) && ((t->phase==PROCESS_INFO_LOAD
            && regs.pc>=abi.load_groups && regs.pc<abi.loaded_groups)
            || ((t->phase==PROCESS_INFO_STORE || t->phase==PROCESS_VALUE_STORE)
                && regs.pc>=abi.store_groups && regs.pc<abi.stored_groups))) {
        t->process_return.regs[0]=(uint64_t)-EFAULT;
        if (!set_registers(pid,&t->process_return) || !restore_mask(t)) return;
        t->phase=IDLE; resume(pid,0); return;
    }
    if (sig == SIGSEGV && t->phase == LOADING_PATH && regs.pc == LOAD_BYTE) {
        t->metadata.error = -EFAULT; replay(t); return;
    }
    if (sig == SIGSEGV && t->phase == STORING && regs.pc == STORE) {
        t->metadata.error = -EFAULT; store_next(t); return;
    }
    if ((sig==SIGSEGV || sig==SIGBUS) && regs.pc>=COPY_BEGIN && regs.pc<COPY_END
            && (t->phase==DISPATCHING || cooperative_copying(t))) {
        if (!signal_observe(t,signal_stop,0)) return;
        siginfo_t info=signal_stop->info;
        if (info.si_code>0) {
            copy_faults++;
            regs.regs[0] = (uint64_t)-EFAULT; regs.pc = regs.regs[30];
            if (set_registers(pid, &regs)) resume(pid, 0);
            return;
        }
    }
    if (sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL
            || ((diagnostics || signal_diagnostics) && sig == SIGTRAP)) {
        if (!signal_observe(t,signal_stop,0)) return;
        siginfo_t info=signal_stop->info;
        signals++;
        if (sig == SIGSYS && info.si_code==SYS_SECCOMP) {
            unsigned i;
            for (i = 0; i < 64; i++) {
                struct denial *d = &denials[i];
                if (!d->count) *d = (struct denial){.nr = info.si_syscall,
                    .adapted = t->phase == IDLE ? 0 : t->original.regs[8], .phase = t->phase};
                if (d->nr == (unsigned)info.si_syscall && d->phase == t->phase
                        && d->adapted == (t->phase == IDLE ? 0 : t->original.regs[8])) {
                    d->count++; break;
                }
            }
            if (i == 64 || denials[i].count > 4) {
                deliver_signal(pid,sig,restored_signal,signal_stop); return;
            }
        }
        SUPERVISOR_SIGNAL_TRACE("PROBE signal pid=%d signal=%d code=%d pc=%#llx nr=%llu phase=%d adapted=%llu address=%p\n",
            pid, sig, info.si_code, regs.pc, regs.regs[8], t->phase, t->original.regs[8], info.si_addr);
        SUPERVISOR_SIGNAL_TRACE("PROBE signal-context pid=%d group=%d sp=%#llx lr=%#llx x0=%#llx shielded=%d logicalSigsys=%d\n",
            pid,t->tgid,regs.sp,regs.regs[30],regs.regs[0],t->signal_shielded,t->host_signal_blocked);
        if ((diagnostics || signal_diagnostics) && t->process_image) {
            char image[PATH_MAX];
            ssize_t length=md_process_image_link(pid,t->process_image,-1,image,sizeof(image));
            if (length>0) SUPERVISOR_SIGNAL_TRACE("PROBE signal-image pid=%d path=%.*s\n",pid,(int)length,image);
        }
        if (sig == SIGSYS)
            SUPERVISOR_SIGNAL_TRACE("PROBE application SIGSYS syscall=%d cookie=%d call=%p\n", info.si_syscall, info.si_errno, info.si_call_addr);
    }
    deliver_signal(pid,sig,restored_signal,signal_stop);
}
