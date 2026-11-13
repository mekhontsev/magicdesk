#define _GNU_SOURCE
#include <sys/ioctl.h>
#include "control_file.h"
#include "cooperative_native.h"
#include "fd_filter.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "guest_debugger.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "interception_route.h"
#include "linux_abi.h"
#include "namespace_broker.h"
#include "proc_file.h"
#include "process_owner.h"
#include "resource_order.h"
#include "seccomp_capture.h"
#include "seccomp_install.h"
#include "seccomp_listener.h"
#include "seccomp_notify.h"
#include "seccomp_policy.h"
#include "seccomp_program.h"
#include "signal_actions.h"
#include "signal_delivery.h"
#include "signal_pending.h"
#include "supervisor_actors.h"
#include "supervisor_domain.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_identity.h"
#include "supervisor_internal.h"
#include "supervisor_memory.h"
#include "supervisor_robust.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_frame.h"
#include "svc_payload.h"
#include "svc_registry.h"
#include "svc_runtime.h"
#include "syscall_catalog.h"
#include "syscall_result.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_copy.h"
#include "task_helper.h"
#include "task_pending.h"
#include "task_quiesce.h"
#include "task_stop.h"

static int guest_policy_boundary(const struct thread *t,const struct seccomp_data *call);
static int filter_listener_prepare(struct thread *t);
static void policy_admission_retain(struct thread *t,int pending);
static const struct md_interception_routes *interception_routes(void);
static int guest_notification_publish(struct thread *t,const struct seccomp_notif *q);
static int host_policy_deliver(struct thread *t,struct user_pt_regs *regs,const siginfo_t *info,int deferred);
static int host_policy_dispatch(struct thread *t,struct user_pt_regs *regs,const struct seccomp_data *call);
static void filter_install_retired(struct thread *t,long result);
static int filter_listener_admit(struct thread *t);
static int policy_pending_visit(void *context,const siginfo_t *info);
static int policy_sync_begin(struct thread *t);
static void policy_sync_release(struct thread *t);

struct md_task_quiesce *policy_barrier;

struct thread *policy_installer;

static int policy_barrier_ready;

void filter_service_gates(struct md_seccomp_gate gates[FILTER_SERVICE_GATES]) {
    gates[0]=(struct md_seccomp_gate){abi.adapter_native_return,abi.adapter_native_return,-1,
        SECCOMP_RET_TRACE|MD_INTERCEPT_TASK};
    gates[1]=(struct md_seccomp_gate){abi.buffer_admit,abi.buffer_admit,SYS_gettid,
        SECCOMP_RET_TRACE|MD_INTERCEPT_TASK};
    gates[2]=(struct md_seccomp_gate){abi.task_return,abi.task_return,-1,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK};
    gates[3]=(struct md_seccomp_gate){abi.code_begin,abi.code_end,-1,SECCOMP_RET_ALLOW};
}

struct md_seccomp_policy *admission_policy(const struct thread *t) {
    return t->seccomp.admission_policy_valid ? t->seccomp.admission_policy : t->seccomp.policy;
}

static int guest_policy_boundary(const struct thread *t,const struct seccomp_data *call) {
    /* Helpers execute an already admitted original call. Their scratch
     * arguments and service PC are not another application policy request. */
    uintptr_t pc=call->instruction_pointer;
    return t->entered && t->phase==IDLE && pc!=RAW_GATE && pc!=abi.task_return
        && !(pc>=abi.code_begin && pc<abi.code_end);
}

int guest_trace_rejected(struct thread *t,const struct seccomp_data *call) {
    if (!guest_policy_boundary(t,call) || !md_seccomp_policy_may_trace(admission_policy(t))) return 0;
    uint32_t decision;
    CHECK(!md_seccomp_policy_eval(admission_policy(t),call,&decision));
    /* Guest TRACE cookies are not service selectors. USER_NOTIF also outranks
     * TRACE, so both interception channels need this original-call decision.
     * Guest TRACESECCOMP observation is not enabled: Linux returns ENOSYS. */
    return (decision&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_TRACE;
}

void guest_notification_discard(struct thread *t) {
    struct guest_notification *n=t->seccomp.notification;
    if (!n) return;
    md_seccomp_notification_release(n->message);
    md_seccomp_notify_release(n->queue);
    if (n->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    free(n); t->seccomp.notification=NULL;
}

static int guest_notification_publish(struct thread *t,const struct seccomp_notif *q) {
    struct guest_notification *n=t->seccomp.notification;
    struct md_seccomp_notification_kernel kernel={.listener=listener,.id=q->id,.borrowed=1,
        .files=t->resources.files,.identity=&t->identity};
    int error=md_seccomp_notify_begin_kernel(n->queue,t->pid,t->resources.pid,&n->original,&kernel,&n->message);
    SUPERVISOR_TRACE("PROBE guest-notification pid=%d nr=%d result=%d\n",t->pid,n->original.nr,error);
    md_seccomp_notify_release(n->queue); n->queue=NULL;
    if (error) {
        struct seccomp_notif_resp reply={.id=q->id,.error=error};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
    return error;
}

int guest_notification_begin(struct thread *t,const struct seccomp_data *data,
        const struct user_pt_regs *regs,const struct seccomp_notif *kernel,const siginfo_t *host_signal) {
    if (!guest_policy_boundary(t,data) || !md_seccomp_policy_may_notify(admission_policy(t))) return 0;
    uint32_t decision; const struct md_seccomp_policy *winner=NULL;
    CHECK(!md_seccomp_policy_decide(admission_policy(t),data,&decision,&winner));
    if ((decision&SECCOMP_RET_ACTION_FULL)!=SECCOMP_RET_USER_NOTIF) return 0;
    CHECK(!t->seccomp.notification);
    struct md_fd_object *object=md_seccomp_policy_listener(winner);
    struct md_seccomp_notify *queue=md_seccomp_notify_retain(md_seccomp_listener_notifications(object));
    md_fd_object_release(object);
    struct guest_notification *n=queue ? calloc(1,sizeof(*n)) : NULL;
    int error=queue ? n ? 0 : -ENOMEM : -ENOSYS;
    if (!error) {
        t->seccomp.notification=n; n->queue=queue; n->original=*data;
        SUPERVISOR_TRACE("PROBE guest-notification-start pid=%d nr=%d pc=%llx host=%d\n",
            t->pid,data->nr,(unsigned long long)data->instruction_pointer,!!host_signal);
        if (host_signal) n->host_signal=*host_signal;
        if (kernel) {
            /* Cancel only the private transport before publishing any
             * public ID. The same owned carrier then serves both TRACE
             * and USER_NOTIF entries, including concurrent TSYNC. */
            n->promoting=1;
            delegate_begin(t,kernel);
            return 1;
        }
        t->phase=GUEST_NOTIFICATION; execution_retain(t,data,regs);
        error=md_task_checkpoint_suspend(&checkpoints,t->pid);
        if (error>=0) { CHECK(!error); n->checkpoint_suspended=1; }
        if (!error) {
            struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_SECCOMP_LISTENER,1},
                .flags=MD_TASK_CALL_INTERRUPTIBLE};
            error=md_task_call_begin(&n->call,native_trace,t->pid,
                host_signal ? MD_TASK_CALL_USER : MD_TASK_CALL_SECCOMP,regs,
                abi.task_call,abi.task_return,&args);
            if (error && n->call.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return 1; }
            if (!error) { t->kernel_stopped=0; return 1; }
        }
        guest_notification_discard(t); t->phase=IDLE;
    } else md_seccomp_notify_release(queue);
    if (kernel) {
        struct seccomp_notif_resp reply={.id=kernel->id,.error=error};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    } else { struct user_pt_regs returned=*regs; return_value(t,&returned,error); }
    return 1;
}

int guest_notification_kernel(struct thread *t,const struct seccomp_notif *q) {
    struct guest_notification *n=t->seccomp.notification;
    if (!n || !md_task_call_notification(&n->call,q)) return 0;
    if (n->continuing_native) {
        struct seccomp_notif_resp reply={.id=q->id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        return 1;
    }
    CHECK(!n->message);
    guest_notification_publish(t,q); return 1;
}

int guest_notification_event(struct thread *t,int status) {
    struct guest_notification *n=t->seccomp.notification;
    if (!n || n->promoting) return 0;
    unsigned event=(unsigned)status>>16;
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) return 0;
    if (task_call_signal(t,&n->call,status)) return 1;
    if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)!=SIGTRAP) return 0;
    SUPERVISOR_TRACE("PROBE guest-notification-event pid=%d phase=%d status=%x stage=%u\n",
        t->pid,t->phase,status,n->call.stage);
    struct seccomp_notif_resp response;
    int replied=n->message ? md_seccomp_notification_result(n->message,&response) : 0;
    int continuing=replied>0 && (response.flags&SECCOMP_USER_NOTIF_FLAG_CONTINUE);
    if (replied>0 && (!continuing || n->continuing_native)) CHECK(!md_task_call_retire_entry(&n->call));
    else if (continuing && !n->host_signal.si_signo)
        CHECK(!md_task_call_preserve_admission(&n->call));
    int ready=md_task_call_event(&n->call,status);
    if (ready<0) {
        if (ready!=-ESRCH) {
            struct user_pt_regs observed={0}; registers(t->pid,&observed);
            fprintf(stderr,"Shroot: notification call failed pid=%d error=%d status=%x stage=%u boundary=%d pc=%llx nr=%llu value=%lld\n",
                t->pid,ready,status,n->call.stage,n->call.boundary,observed.pc,observed.regs[8],(long long)observed.regs[0]);
        }
        CHECK(ready==-ESRCH); t->resume_lost=1; return 1;
    }
    if (!ready) { t->kernel_stopped=0; return 1; }
    if (continuing && !n->continuing_native && !n->host_signal.si_signo
            && guest_notification_route(t,&n->original)==MD_INTERCEPT_NATIVE) {
        struct md_task_call_arguments args={.number=n->original.nr,.flags=MD_TASK_CALL_INTERRUPTIBLE};
        memcpy(args.value,n->original.args,sizeof(args.value));
        n->continuing_native=1;
        int error=md_task_call_again(&n->call,&args);
        if (!error) error=md_task_call_retire_entry(&n->call);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
        else t->kernel_stopped=0;
        return 1;
    }
    long result=n->continuing_native ? n->call.result
        : replied>0 ? response.error ? response.error : response.val : n->call.result;
    int continued_native=n->continuing_native;
    if (continued_native) continuing=0;
    siginfo_t host_signal=n->host_signal;
    struct seccomp_data call=n->original;
    uint64_t mask=n->call.mask;
    enum md_task_call_boundary boundary=n->call.boundary;
    guest_notification_discard(t); t->phase=IDLE;
    struct user_pt_regs original=t->execution.registers;
    if ((replied<=0 || continued_native) && md_syscall_restarting(result)) {
        restart_boundary(t,&original,mask,RESTART_SIGNAL,boundary);
        return 1;
    }
    if (continuing) {
        if (host_signal.si_signo) {
            if (!host_policy_dispatch(t,&original,&call)) host_policy_deliver(t,&original,&host_signal,1);
            return 1;
        }
        t->seccomp.notification_continued=1; return 0;
    }
    return_task_value(t,&original,result,boundary); return 1;
}

unsigned long guest_notification_route(struct thread *t,const struct seccomp_data *call) {
    uint32_t decision=interception_decision(t,call);
    switch (decision&SECCOMP_RET_ACTION_FULL) {
    case SECCOMP_RET_ALLOW: return MD_INTERCEPT_NATIVE;
    case SECCOMP_RET_TRACE: return decision&SECCOMP_RET_DATA;
    case SECCOMP_RET_USER_NOTIF: return MD_INTERCEPT_DISPATCH;
    default: CHECK(0); return MD_INTERCEPT_NATIVE;
    }
}

static int host_policy_deliver(struct thread *t,struct user_pt_regs *regs,const siginfo_t *info,int deferred) {
    struct md_kernel_action current;
    CHECK(!md_signal_actions_read(t->signals.actions,SIGSYS,&current));
    if (t->signals.host_blocked || current.handler==(uintptr_t)SIG_IGN) {
        struct signal_control *owner=calloc(1,sizeof(*owner)); CHECK(owner);
        owner->forced=*info; owner->action=current;
        t->signals.control=owner; execution_capture(t,regs); t->phase=SIGNAL_CONTROL;
        CHECK(md_memory_order_begin(t->memory,&owner->memory,fd_wakeup)>=0);
        signal_control_progress(t); return 1;
    }
    if (deferred) {
        CHECK(!md_signal_delivery_seccomp(&t->signals.delivery,info));
        if (set_registers(t->pid,regs)) resume(t->pid,0);
        return 1;
    }
    return 0;
}

uint32_t host_policy_interception(const struct seccomp_data *call) {
    if (call->arch!=AUDIT_ARCH_AARCH64) return SECCOMP_RET_ALLOW;
    if (call->nr==SYS_set_robust_list || call->nr==SYS_get_robust_list)
        return SECCOMP_RET_TRACE|MD_INTERCEPT_OBSERVE;
    /* These optional native facilities have no userspace implementation.
     * Report absence only after a host denial, never fake successful
     * registrations, NUMA placement or clock discipline. Guest policy
     * takes precedence; permitted native calls do not enter this path. */
    switch (call->nr) {
#define OPTIONAL(n) case SYS_##n:
        MD_HOST_OPTIONAL_CALLS(OPTIONAL)
#undef OPTIONAL
            return SECCOMP_RET_ERRNO|ENOSYS;
    }
    /* Inherited TRAP outranks our ERRNO/TRACE/USER_NOTIF. Reuse the original
     * interception policy, not a second list of adapter-supported syscalls.
     * Native calls remain denied; guest policy was evaluated before entry. */
    const struct md_interception_routes *routes=interception_routes();
    if (md_seccomp_cache_allows(&routes->base_allow,call)) return SECCOMP_RET_ALLOW;
    struct sock_fprog program={routes->base.count,(struct sock_filter *)routes->base.code}; uint32_t decision;
    CHECK(!md_seccomp_program_eval(&program,call,&decision));
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    unsigned cookie=decision&SECCOMP_RET_DATA;
    if (action==SECCOMP_RET_ERRNO || action==SECCOMP_RET_USER_NOTIF
            || (action==SECCOMP_RET_TRACE && (cookie==MD_INTERCEPT_IDENTITY
                || cookie==MD_INTERCEPT_OBSERVE || cookie==MD_INTERCEPT_DISPATCH))) return decision;
    return SECCOMP_RET_ALLOW;
}

static int host_policy_dispatch(struct thread *t,struct user_pt_regs *regs,const struct seccomp_data *call) {
    uint32_t decision=host_policy_interception(call);
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_ALLOW) return 0;
    /* The kernel syscall exit preceded this SIGSYS. Publish the logical exit
     * only after its result or asynchronous adapter has completed. */
    if (t->debug.call_active) t->debug.adapter=1;
    if (action==SECCOMP_RET_ERRNO) {
        regs->regs[0]=md_seccomp_policy_error(decision);
        if (set_registers(t->pid,regs)) resume(t->pid,0);
        return 1;
    }
    unsigned long cookie=action==SECCOMP_RET_USER_NOTIF ? MD_INTERCEPT_DISPATCH : decision&SECCOMP_RET_DATA;
    regs->pc=call->instruction_pointer; regs->regs[8]=call->nr;
    execution_retain(t,call,regs); ++calls; ++t->calls;
    if (robust_call(t,call,regs,MD_TASK_CALL_USER)) return 1;
    if (identity_call(t,call,regs,cookie) || domain_call(t,call,regs,cookie)) return 1;
    if (cookie!=MD_INTERCEPT_DISPATCH) return 0;
    if (call->nr==SYS_shmdt) { memory_begin(t,call,regs,MEMORY_DETACH); return 1; }
    if (save_signal_mask(t,&t->execution.mask,"host-policy-dispatch-mask") && shield(t)) dispatch_with_stack(t);
    return 1;
}

int host_policy_signal(struct thread *t,struct md_task_signal_stop *stop) {
    if (!t->signals.host || !t->entered) return 0;
    if (!signal_observe(t,stop,0)) return 1;
    siginfo_t info=stop->info;
    if (info.si_code!=SYS_SECCOMP) {
        if (t->signals.host_blocked) {
            /* Kernel delivery has already selected this task. Like ptrace's
             * blocked-signal requeue, retain that target; SI_QUEUE does not
             * distinguish rt_sigqueueinfo from rt_tgsigqueueinfo. Guest sends
             * retain their original scope at generation instead. */
            CHECK(md_signal_pending_put(t->signals.pending,MD_SIGNAL_PRIVATE,&info)>=0);
            resume(t->pid,0); return 1;
        }
        struct md_kernel_action action;
        if (!md_signal_actions_read(t->signals.actions,SIGSYS,&action)
                && action.handler==(uintptr_t)SIG_IGN) { resume(t->pid,0); return 1; }
        return 0;
    }
    if (t->phase!=IDLE) return 0;
    if (!signal_observe(t,stop,1)) return 1;
    struct user_pt_regs regs=stop->regs;
    struct seccomp_data call={.nr=info.si_syscall,.arch=info.si_arch,
        .instruction_pointer=(uintptr_t)info.si_call_addr};
    memcpy(call.args,regs.regs,sizeof(call.args));
    uint32_t decision;
    CHECK(!md_seccomp_policy_eval(admission_policy(t),&call,&decision));
    t->seccomp.admission_policy_pending=0;
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_TRAP) {
        info.si_errno=decision&SECCOMP_RET_DATA;
        if (!signal_replace_info(t,stop,&info)) return 1;
        return host_policy_deliver(t,&regs,&info,0);
    }
    if (action==SECCOMP_RET_ERRNO || action==SECCOMP_RET_TRACE) {
        if (t->debug.call_active) t->debug.adapter=1;
        regs.regs[0]=action==SECCOMP_RET_TRACE ? -ENOSYS : md_seccomp_policy_error(decision);
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return 1;
    }
    if (action==SECCOMP_RET_USER_NOTIF) {
        if (t->debug.call_active) t->debug.adapter=1;
        return guest_notification_begin(t,&call,&regs,NULL,&info);
    }
    if (action!=SECCOMP_RET_ALLOW && action!=SECCOMP_RET_LOG) return 0;
    return host_policy_dispatch(t,&regs,&call);
}

int filter_revoke_entry(struct thread *t,const struct seccomp_data *call) {
    int group=md_seccomp_filter_flags(call->nr,call->args[1])&SECCOMP_FILTER_FLAG_TSYNC;
    int error=md_svc_registry_pause(md_memory_svc(t->memory),t->pid,MD_SVC_PAUSE_FILTER);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error);
    for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid
            && (peer==t || (group && peer->tgid==t->tgid))) {
        peer->entry.blocked=1;
        md_broker_cooperative_revoke(broker,peer->pid);
    }
    return 1;
}

int filter_entry_eligible(const struct thread *t) {
    if (!t->seccomp.application_filter) return 1;
    const struct md_svc_registry *registry=md_memory_svc(t->memory);
    return registry && md_seccomp_policy_entry_compatible(t->seccomp.policy,registry->image)
        && md_memory_snapshot_address(t->memory,md_seccomp_policy_identity(t->seccomp.policy));
}

static int filter_entry_shared_policy(struct thread *t,int *filtered) {
    *filtered=0;
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
        /* A queued install may still revoke the process-wide registration in
         * its preflight. Only the last installer can publish a stable view. */
        if (p!=t && (p->seccomp.install || p->entry.fence_waiting)) return 0;
        if (!filter_entry_eligible(p) || p->debug.options || md_debugger_traced(debugger,p->pid)) return 0;
        *filtered|=p->seccomp.application_filter;
    }
    return 1;
}

void filter_entry_rearm(struct thread *t) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!registry || !(registry->pause_reasons&MD_SVC_PAUSE_FILTER) || !registry->entry_fence_drained
            || md_memory_exposed(t->memory) || !md_memory_snapshots_valid(t->memory)) return;
    int filtered;
    if (!filter_entry_shared_policy(t,&filtered)) return;
    if (filtered && statistics && !registry->filter_statistics) {
        struct md_svc_payload_state state;
        if (!md_svc_registry_read(t->pid,registry->frames+MD_SVC_POOL_SIZE,&state,sizeof(state))) {
            registry->filter_completed_start=state.completed;
            registry->filter_statistics=1;
        }
    }
    uintptr_t mode=filtered ? 2 : 1;
    int error=md_svc_registry_write(t->pid,abi.cooperative_registration,&mode,sizeof(mode));
    if (!error) error=md_svc_registry_rearm(registry,t->pid,MD_SVC_PAUSE_FILTER);
    if (error) { SUPERVISOR_TRACE("PROBE filter-entry-rearm pid=%d error=%d\n",t->pid,error); return; }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
        p->entry.blocked=0; p->entry.registration_revoked=0;
        cooperative_memory_publish(p,0);
        /* Paused sites contain their original SVC until observed again. */
        md_broker_resample(broker,p->pid,0);
    }
    SUPERVISOR_TRACE("PROBE filter-entry-ready pid=%d\n",t->pid);
}

void filter_entry_complete(struct thread *t,const struct user_pt_regs *regs) {
    filter_entry_rearm(t);
    filter_install_discard(t); t->phase=IDLE;
    struct user_pt_regs continuation=*regs;
    if (set_registers(t->pid,&continuation)) resume(t->pid,0);
}

int filter_entry_map(struct thread *t,const struct user_pt_regs *regs) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!registry || !(registry->pause_reasons&MD_SVC_PAUSE_FILTER) || !t->seccomp.policy
            || !md_seccomp_policy_entry_compatible(t->seccomp.policy,registry->image)
            || !md_memory_snapshots_valid(t->memory)) return 0;
    struct buffer_mapping *owner=calloc(1,sizeof(*owner));
    if (!owner) return 0;
    for (const struct md_seccomp_policy *p=md_seccomp_policy_parent(t->seccomp.policy);p;p=md_seccomp_policy_parent(p)) {
        owner->parent_view=md_memory_snapshot_address(t->memory,md_seccomp_policy_identity(p));
        if (owner->parent_view) { owner->ancestor=md_seccomp_policy_identity(p); break; }
    }
    size_t size=0;
    int error=md_seccomp_policy_view_size(t->seccomp.policy,owner->ancestor,&size);
    if (!error) error=md_buffers_create_readonly(1,size,&owner->buffers);
    if (error) { free(owner); return 0; }
    owner->policy=md_seccomp_policy_retain(t->seccomp.policy);
    return !buffer_mapping_start(t,regs,owner);
}

void filter_install_discard(struct thread *t) {
    retry_clear(t,RETRY_FILTER);
    struct filter_install *install=t->seccomp.install;
    if (!install) return;
    policy_sync_release(t);
    if (install->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    md_seccomp_capture_discard(&install->capture);
    if (install->virtual_listener && install->source>=0) close(install->source);
    if (install->loan) {
        struct task_action *loan=install->loan;
        if (loan->number>=0 && !cancelling) {
            md_fd_origins_finish(&install->listener,loan->number);
            loan_retire(loan,NULL,NULL,NULL);
        } else {
            if (loan->loan && md_fd_loan_active_in(loan->loan,loan->loan_table)) md_fd_loan_end(loan->loan);
            md_fd_loan_release(loan->loan); md_fd_origins_unpin(loan->loan_table); free(loan);
        }
    }
    md_fd_origins_abandon(&install->listener);
    if (install->files.table && md_fd_order_cancel(&install->files)==1)
        CHECK(!md_fd_order_complete(&install->files));
    md_buffer_release(&install->scratch);
    if (install->memory.queue && md_resource_order_cancel(&install->memory)==1)
        CHECK(!md_resource_order_complete(&install->memory));
    free(install); t->seccomp.install=NULL;
}

static void filter_install_retired(struct thread *t,long result) {
    CHECK(!result);
    long error=t->seccomp.install->error;
    int completed=t->seccomp.install->native_completed;
    filter_entry_rearm(t);
    filter_install_discard(t); t->phase=IDLE;
    struct user_pt_regs original=t->execution.registers;
    if (completed) {
        original.regs[0]=error;
        if (set_registers(t->pid,&original)) resume(t->pid,0);
    } else return_value(t,&original,error);
}

void filter_install_fail(struct thread *t,int error) {
    struct filter_install *install=t->seccomp.install;
    SUPERVISOR_TRACE("PROBE filter-install-failed pid=%d stage=%u admission=%u error=%d\n",
        t->pid,install->stage,install->admission.stage,error);
    install->error=error;
    if (install->checkpoint_suspended) {
        md_task_checkpoint_restore(&checkpoints,t->pid); install->checkpoint_suspended=0;
    }
    if (install->loan && install->loan->number>=0) {
        struct task_action *loan=install->loan; install->loan=NULL;
        md_fd_origins_finish(&install->listener,loan->number);
        install->retiring=1;
        loan_retire(loan,t,&install->files,filter_install_retired);
    } else filter_install_retired(t,0);
}

static int filter_listener_prepare(struct thread *t) {
    struct filter_install *install=t->seccomp.install;
    if (!install->virtual_listener || install->listener.table) return 0;
    if (!install->files.table) {
        int error=md_fd_order_begin(t->resources.files,&install->files,fd_wakeup);
        if (error<0) return error;
    }
    if (md_fd_order_state(&install->files)==MD_RESOURCE_ORDER_QUEUED) return -EAGAIN;
    if (md_fd_order_state(&install->files)==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(&install->files));
    struct md_fd_object *object=md_seccomp_listener_virtual(install->capture.policy,
        &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},fd_wakeup);
    if (!object) return -errno;
    int error=md_fd_origins_prepare_object(t->resources.files,object,FD_CLOEXEC,&install->listener);
    if (!error) {
        install->source=md_proc_file_descriptor(md_proc_file_from_object(object));
        if (install->source<0) error=install->source;
    }
    md_fd_object_release(object);
    if (!error) {
        struct task_action *loan=calloc(1,sizeof(*loan));
        if (!loan) error=-ENOMEM;
        else {
            install->loan=loan; loan->number=-1;
            loan->loan_table=md_fd_origins_pin(t->resources.files);
            loan->loan=md_fd_loan_new(t->resources.files);
            error=loan->loan ? md_fd_origins_prepare_custody(&install->listener,loan->loan,&install->files) : -ENOMEM;
        }
    }
    return error;
}

static int filter_listener_admit(struct thread *t) {
    struct filter_install *install=t->seccomp.install;
    if (!install->virtual_listener || install->admitted) return 0;
    int error=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (error>=0) { CHECK(!error); install->checkpoint_suspended=1; }
    if (!error) {
        struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_SECCOMP_LISTENER}};
        error=md_task_call_begin(&install->admission,native_trace,t->pid,MD_TASK_CALL_SECCOMP,
            &t->execution.registers,abi.task_call,abi.task_return,&args);
        if (error && install->admission.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return 1; }
        if (!error) { t->kernel_stopped=0; return 1; }
    }
    return error;
}

int filter_listener_notification(struct thread *t,const struct seccomp_notif *q) {
    struct filter_install *install=t->seccomp.install;
    if (install) SUPERVISOR_TRACE("PROBE filter-listener-admission pid=%d stage=%u nr=%d pc=%llx expected=%lx args=%llx,%llx,%llx,%llx,%llx,%llx\n",
        t->pid,install->admission.stage,q->data.nr,(unsigned long long)q->data.instruction_pointer,
        (unsigned long)install->admission.returned,(unsigned long long)q->data.args[0],
        (unsigned long long)q->data.args[1],(unsigned long long)q->data.args[2],
        (unsigned long long)q->data.args[3],(unsigned long long)q->data.args[4],(unsigned long long)q->data.args[5]);
    if (!install || !install->virtual_listener || !md_task_call_notification(&install->admission,q)) return 0;
    CHECK(install->loan && install->loan->number<0 && md_fd_order_state(&install->files)==MD_RESOURCE_ORDER_RUNNING);
    struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
        .srcfd=install->source,.newfd_flags=O_CLOEXEC};
    int number=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
    SUPERVISOR_TRACE("PROBE filter-listener-injected pid=%d fd=%d error=%d\n",t->pid,number,number<0 ? errno : 0);
    if (number>=0) install->loan->number=number;
    else if (errno!=ENOENT) {
        struct seccomp_notif_resp reply={.id=q->id,.error=-errno};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
    return 1;
}

static int policy_pending_visit(void *context,const siginfo_t *info) {
    (void)context;
    return info->si_signo==SIGSYS && info->si_code==SYS_SECCOMP;
}

static void policy_admission_retain(struct thread *t,int pending) {
    if (!t->seccomp.admission_policy_valid) {
        t->seccomp.admission_policy=md_seccomp_policy_retain(t->seccomp.policy);
        t->seccomp.admission_application_filter=t->seccomp.application_filter;
        t->seccomp.admission_policy_valid=1;
    }
    t->seccomp.admission_policy_pending|=pending;
}

static int policy_sync_begin(struct thread *t) {
    if (!(md_seccomp_filter_flags(t->execution.request.nr,t->execution.request.args[1])
            &SECCOMP_FILTER_FLAG_TSYNC)) return 0;
    if (policy_installer==t) return policy_barrier_ready ? 0 : -EAGAIN;
    if (policy_barrier || quiescence_busy(NULL) || md_task_helpers_group_busy(helpers,t->tgid)) return -EAGAIN;
    policy_barrier=md_task_quiesce_new(quiesce_trace);
    if (!policy_barrier) return -ENOMEM;
    policy_installer=t; policy_barrier_ready=0;
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p!=t && p->tgid==t->tgid) {
        /* These admitted waits cannot return to application code without an
         * owned syscall/checkpoint stop. Interrupting them would invalidate a
         * notification ID or replay a socket effect against the new policy.
         * Hold their next event instead of cancelling the native request. */
        if ((p->seccomp.notification && !p->seccomp.notification->promoting)
                || signal_control_parked(p) || p->phase==ADAPTER_NATIVE || p->phase==NATIVE_WAIT
                || ((entry_native_journal(p) || p->entry.wait.complete || p->phase==SOCKET_WAITING)
                    && entry_fence_drained(p))) continue;
        CHECK(!quiesce_task_add(policy_barrier,p,0));
    }
    CHECK(!md_task_quiesce_stop(policy_barrier));
    return -EAGAIN;
}

void policy_sync_progress(void) {
    if (!policy_installer || policy_barrier_ready || !md_task_quiesce_ready(policy_barrier)) return;
    /* Kernel admission precedes signal/ptrace observation. Retain the old
     * immutable head for a collected entry or queued forced signal, including
     * a fork whose child has not yet been published by the coordinator. */
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p!=policy_installer
            && md_task_quiesce_contains(policy_barrier,p->pid)) {
        struct md_task_quiesce_stop stop;
        int error=md_task_quiesce_borrow(policy_barrier,p->pid,&stop);
        if (error==-ESRCH) continue;
        CHECK(!error);
        unsigned event=(unsigned)stop.status>>16;
        int admitted=stop.kind==MD_TASK_QUIESCE_EVENT
            && (event==PTRACE_EVENT_SECCOMP || event==PTRACE_EVENT_CLONE
                || event==PTRACE_EVENT_FORK || event==PTRACE_EVENT_VFORK);
        if (stop.kind==MD_TASK_QUIESCE_EVENT && !event && WSTOPSIG(stop.status)==SIGSYS) {
            siginfo_t info;
            if (native_trace(PTRACE_GETSIGINFO,p->pid,NULL,&info)>=0) admitted=info.si_code==SYS_SECCOMP;
            else CHECK(errno==ESRCH);
        }
        int pending=p->signals.host ? md_task_pending_scan(native_trace,p->pid,policy_pending_visit,NULL) : 0;
        if (pending<0) CHECK(pending==-ESRCH);
        if (admitted || pending>0) policy_admission_retain(p,pending>0);
        CHECK(!md_task_quiesce_return(policy_barrier,p->pid));
    }
    policy_barrier_ready=1;
    filter_install_progress(policy_installer);
}

static void policy_sync_release(struct thread *t) {
    if (policy_installer!=t) return;
    policy_installer=NULL; policy_barrier_ready=0;
    CHECK(!md_task_quiesce_release(policy_barrier));
    task_action_unpark();
}

void filter_install_progress(struct thread *t) {
    retry_clear(t,RETRY_FILTER);
    struct filter_install *install=t->seccomp.install;
    CHECK(install && t->phase==FILTER_CAPTURE && !install->copy.scratch);
    enum md_resource_order_state state=md_resource_order_state(&install->memory);
    if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&install->memory));
    else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_FILTER); return; }
    if (!md_memory_readonly_base(t->memory) || !t->entry.registration_revoked
            || md_fd_selector_has_mask(t->descriptors.selector,MD_FD_SANDBOX_SELECTORS,-1)!=1) {
        t->seccomp.preflight=1;
        if (save_signal_mask(t,&t->execution.mask,"filter-admission-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    if (!md_memory_readonly_base(t->memory)) { filter_install_fail(t,-EFAULT); return; }
    while (install->stage<2) {
        void *data=install->stage ? (void *)install->code : (void *)&install->program;
        uintptr_t address=install->stage ? (uintptr_t)install->program.filter : t->execution.request.args[2];
        size_t size=install->stage ? install->program.len*sizeof(*install->code) : sizeof(install->program);
        if (install->stage && (!install->program.len || install->program.len>BPF_MAXINSNS)) {
            filter_install_fail(t,-EINVAL); return;
        }
        ssize_t copied=md_task_copy_direct(t->pid,address,data,size,0);
        if (copied==-EPERM || copied==-EACCES) {
            int error=install->scratch.pool ? 0 : md_buffers_take(md_memory_buffers(t->memory),&install->scratch);
            if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
            struct md_buffer_span span;
            if (!error) error=md_buffer_span(&install->scratch,&span);
            if (!error) {
                int boundary=md_task_checkpoint_suspend(&checkpoints,t->pid);
                if (boundary<0) error=boundary;
                else { CHECK(!boundary); install->checkpoint_suspended=1; }
            }
            if (!error) error=md_task_copy_begin(&install->copy,native_trace,t->pid,MD_TASK_CALL_SECCOMP,
                &t->execution.registers,abi.task_call,abi.task_return,address,data,size,0,span.data,
                md_memory_buffer_address(t->memory,&install->scratch),span.size);
            if (error && install->copy.call.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return; }
            if (error) filter_install_fail(t,error);
            else t->kernel_stopped=0;
            return;
        }
        if (copied!=(ssize_t)size) { filter_install_fail(t,copied<0 ? (int)copied : -EFAULT); return; }
        ++install->stage;
    }
    if (install->stage==2) {
        struct sock_fprog program={install->program.len,install->code};
        const uint32_t delegated[]={SYS_gettid,SYS_seccomp,SYS_prctl,SYS_rt_sigaction,SYS_rt_sigprocmask,
            SYS_rt_sigreturn,SYS_rt_sigpending,SYS_rt_sigtimedwait,SYS_process_vm_readv,SYS_process_vm_writev,
            SYS_ppoll,SYS_pselect6,SYS_epoll_pwait,SYS_epoll_pwait2,SYS_signalfd4,SYS_fcntl,SYS_mmap};
        struct md_svc_registry *registry=md_memory_svc(t->memory);
        size_t capacity=FILTER_SERVICE_GATES+(registry ? registry->pool_count : 0);
        struct md_seccomp_gate *gates=calloc(capacity,sizeof(*gates));
        if (!gates) { filter_install_fail(t,-ENOMEM); return; }
        filter_service_gates(gates);
        size_t gate_count=3;
        if (registry) {
            gate_count=FILTER_SERVICE_GATES;
            for (unsigned i=0;i<registry->pool_count;++i)
                gates[gate_count++]=(struct md_seccomp_gate){registry->pools[i].address,
                    registry->pools[i].address+MD_SVC_CODE_BYTES,-1,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK};
        }
        const struct md_seccomp_route route={.delegation_pc=abi.task_return,.notify_cookie=MD_INTERCEPT_NOTIFY,
            .gates=gates,.gate_count=gate_count,.entry_first=1,.entry_image=registry ? registry->image : 0,
            .internal_gate_count=registry ? FILTER_SERVICE_GATES : 0};
        int error=md_seccomp_capture_prepare(&install->capture,md_memory_readonly_buffers(t->memory),
            md_memory_readonly_base(t->memory),t->seccomp.policy,&program,
            delegated,t->signals.host ? sizeof(delegated)/sizeof(*delegated) : 3,
            MD_INTERCEPT_TASK,&route);
        if (error==-E2BIG && registry) {
            struct md_seccomp_route fallback=route;
            fallback.gate_count=3; fallback.entry_first=0; fallback.entry_image=0; fallback.internal_gate_count=0;
            error=md_seccomp_capture_prepare(&install->capture,md_memory_readonly_buffers(t->memory),
                md_memory_readonly_base(t->memory),t->seccomp.policy,&program,
                delegated,t->signals.host ? sizeof(delegated)/sizeof(*delegated) : 3,
                MD_INTERCEPT_TASK,&fallback);
        }
        free(gates);
        if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
        if (error) { filter_install_fail(t,error); return; }
        ++install->stage;
    }
    int error=filter_listener_prepare(t);
    if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
    if (error) { filter_install_fail(t,error); return; }
    error=filter_listener_admit(t);
    if (error>0) return;
    if (error) { filter_install_fail(t,error); return; }
    error=policy_sync_begin(t);
    if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
    if (error) { filter_install_fail(t,error); return; }
    struct user_pt_regs call=t->execution.registers;
    call.regs[2]=install->capture.program;
    if (install->virtual_listener)
        call.regs[1]&=~(SECCOMP_FILTER_FLAG_NEW_LISTENER|SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV);
    t->phase=OBSERVING;
    if (set_registers(t->pid,&call)) resume(t->pid,0);
}

int filter_install_event(struct thread *t,int status) {
    struct filter_install *install=t->seccomp.install;
    if (!install || install->retiring || !WIFSTOPPED(status)
            || (unsigned)status>>16==PTRACE_EVENT_EXIT || (unsigned)status>>16==PTRACE_EVENT_EXEC) return 0;
    if (install->admission.stage && !install->admitted) {
        if (task_call_signal(t,&install->admission,status)) return 1;
        int ready=md_task_call_event(&install->admission,status);
        if (ready<0) { CHECK(ready==-ESRCH); t->resume_lost=1; return 1; }
        if (!ready) { t->kernel_stopped=0; return 1; }
        md_task_checkpoint_restore(&checkpoints,t->pid); install->checkpoint_suspended=0;
        if (install->admission.result<0) filter_install_fail(t,(int)install->admission.result);
        else {
            CHECK(install->loan->number==install->admission.result);
            install->admitted=1; filter_install_progress(t);
        }
        return 1;
    }
    if (!install->copy.scratch) return 0;
    if (task_call_signal(t,&install->copy.call,status)) return 1;
    int ready=md_task_copy_event(&install->copy,status);
    if (ready<0) { CHECK(ready==-ESRCH); t->resume_lost=1; return 1; }
    if (!ready) { t->kernel_stopped=0; return 1; }
    long copied=install->copy.result;
    size_t size=install->copy.size;
    install->copy=(struct md_task_copy){0};
    md_task_checkpoint_restore(&checkpoints,t->pid); install->checkpoint_suspended=0;
    if (copied!=(long)size) { filter_install_fail(t,copied<0 ? (int)copied : -EFAULT); return 1; }
    ++install->stage; filter_install_progress(t); return 1;
}

void filter_install_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r) {
    CHECK(!t->seccomp.install);
    unsigned flags=md_seccomp_filter_flags(call->nr,call->args[1]);
    if (flags&SECCOMP_FILTER_FLAG_NEW_LISTENER) {
        int error=md_seccomp_listener_flags(flags);
        if (error) { filter_entry_rearm(t); struct user_pt_regs call=*r; return_value(t,&call,error); return; }
    }
    struct filter_install *install=calloc(1,sizeof(*install));
    if (!install) { filter_entry_rearm(t); struct user_pt_regs call=*r; return_value(t,&call,-ENOMEM); return; }
    install->source=-1; install->virtual_listener=!!(flags&SECCOMP_FILTER_FLAG_NEW_LISTENER);
    t->seccomp.install=install; execution_retain(t,call,r); t->phase=FILTER_CAPTURE;
    int error=md_memory_order_begin(t->memory,&install->memory,fd_wakeup);
    if (error<0) filter_install_fail(t,error);
    else filter_install_progress(t);
}

int filter_observed(struct thread *t,const struct user_pt_regs *regs) {
    long result=(long)regs->regs[0];
    const struct seccomp_data *call=&t->execution.request;
    if (!t->entered || !md_seccomp_filter_call(call->nr,call->args[0],call->args[1])) return 0;
    unsigned flags=md_seccomp_filter_flags(call->nr,call->args[1]);
    unsigned length=t->seccomp.install ? t->seccomp.install->program.len : (unsigned)t->seccomp.length;
    struct md_seccomp_policy *published=NULL;
    if (t->seccomp.install) {
        struct filter_install *install=t->seccomp.install;
        if (install->virtual_listener && install->loan) {
            CHECK(install->admitted && result==install->loan->number);
            md_fd_origins_release_custody(&install->listener);
            md_fd_loan_end(install->loan->loan);
            md_fd_loan_release(install->loan->loan);
            md_fd_origins_unpin(install->loan->loan_table); free(install->loan); install->loan=NULL;
        }
        int complete=md_seccomp_capture_complete(&t->seccomp.install->capture,flags,result,&published);
        CHECK(complete>=0);
        md_fd_origins_finish(&t->seccomp.install->listener,complete ? (int)result : -1);
    }
    if (!md_seccomp_filter_committed(flags,result)) {
        filter_entry_rearm(t); filter_install_discard(t); return 0;
    }
    if (published) {
        md_seccomp_policy_replace(&t->seccomp.policy,published);
        if (flags&SECCOMP_FILTER_FLAG_TSYNC)
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer!=t && peer->tgid==t->tgid) {
                    md_seccomp_policy_replace(&peer->seccomp.policy,published);
                }
        md_seccomp_policy_release(published);
    }
    t->seccomp.application_filter=1;
    if (flags&SECCOMP_FILTER_FLAG_TSYNC)
        for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer->tgid==t->tgid) peer->seccomp.application_filter=1;
    SUPERVISOR_TRACE("PROBE installed-filter pid=%d instructions=%u\n",t->pid,length);
    if (filter_entry_map(t,regs)) return 1;
    filter_install_discard(t);
    return 0;
}

int describe_filter(pid_t pid,const struct seccomp_data *call) {
    if (!diagnostics) return -1;
    if (!md_seccomp_filter_call(call->nr,call->args[0],call->args[1])) return -1;
    struct sock_fprog program;
    struct iovec local = {&program, sizeof(program)}, remote = {(void *)call->args[2], sizeof(program)};
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    SUPERVISOR_TRACE("PROBE filter pid=%d instructions=%d readError=%d\n", pid,
        n == sizeof(program) ? program.len : -1, n < 0 ? errno : 0);
    return n == sizeof(program) ? program.len : -1;
}

static const struct md_interception_routes *interception_routes(void) {
    /* Immutable programs belong to this supervisor's bootstrap ABI. Reuse
     * them for channel ingress and USER_NOTIF CONTINUE alike. */
    static struct md_interception_routes routes;
    if (!routes.base.count) CHECK(!md_interception_routes_init(&routes,&abi));
    return &routes;
}

uint32_t interception_decision(struct thread *t,const struct seccomp_data *call) {
    struct md_fd_selector_view view;
    md_fd_selector_export(t->descriptors.selector,&view);
    unsigned flags=(t->process_op.filter ? MD_ROUTE_PID : 0)|(t->signals.host ? MD_ROUTE_SIGNALS : 0);
    uint32_t decision;
    CHECK(!md_interception_routes_eval(interception_routes(),&view,flags,call,&decision));
    return decision;
}
