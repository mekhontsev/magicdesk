#define _GNU_SOURCE
#include "host_policy.h"
#include "cooperative_native.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_identity.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "linux_abi.h"
#include "process_access.h"
#include "pid_calls.h"
#include "seccomp_policy.h"
#include "signal_origin.h"
#include "signal_view.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_machine.h"
#include "svc_registry.h"
#include "syscall_result.h"
#include "task_pending.h"
#include "task_status.h"
#include <linux/audit.h>
static int debug_host_policy_visit(void *context,const siginfo_t *info);

void debugger_wait_release(struct thread *t) {
    retry_clear(t,RETRY_DEBUG_WAIT);
    if (t->debug.wait_output) {
        CHECK(!md_buffer_release(&t->debug.wait_output->buffer));
        free(t->debug.wait_output); t->debug.wait_output=NULL;
    }
}

int debugger_parent(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (t) {
        long parent=md_task_parent(pid);
        if (parent>=0) t->parent=(pid_t)parent;
    }
    return t ? t->parent : 0;
}

void debugger_reaped(pid_t observer,pid_t pid,int native_wait) {
    if (native_wait) process_reap(find_thread(observer),pid);
    else pid_collection_pending=1;
}

int debugger_group(pid_t pid) {
    struct thread *t=find_thread(pid);
    return t ? t->tgid : pid;
}

uid_t debugger_uid(pid_t pid) {
    struct thread *t=find_thread(pid);
    CHECK(t);
    return t->identity.uid.real;
}

int debugger_signal_mask(pid_t pid,int writing,uint64_t *mask) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    uint64_t native=writing ? *mask : 0;
    if (writing && t->signals.host) native&=~MD_SIGNAL_SYS_BIT;
    if (native_trace(writing ? PTRACE_SETSIGMASK : PTRACE_GETSIGMASK,pid,
            (void *)sizeof(native),&native)<0) return -errno;
    if (writing) {
        t->signals.handler_mask_known=0;
        if (t->signals.host) t->signals.host_blocked=!!(*mask&MD_SIGNAL_SYS_BIT);
    } else *mask=t->signals.host ? md_signal_view_mask(native,t->signals.host_blocked) : native;
    return 0;
}

uid_t debugger_visible_uid(pid_t observer,pid_t sender,uid_t fallback) {
    struct thread *t=find_thread(observer);
    return t ? process_signal_uid(t,sender,fallback) : fallback;
}

void debugger_signal_origin(pid_t recipient,const siginfo_t *info,struct md_signal_origin *origin) {
    struct thread *t=find_thread(recipient);
    if (t) {
        signal_origin_capture(t,info,origin);
        if (origin->valid) signal_origin_consumed(t,info);
    }
}

/* Keep stop information native while a guest debugger owns it. Translate only
 * on actual delivery, in the recipient's namespaces rather than the tracer's. */
void debugger_signal_translate(pid_t observer,const struct md_signal_origin *origin,siginfo_t *info) {
    struct thread *t=find_thread(observer);
    if (t) signal_origin_translate(t,origin,info);
}

pid_t debugger_visible_pid(pid_t observer,pid_t native) {
    struct thread *t=find_thread(observer);
    return t ? visible_pid(t,native) : 0;
}

pid_t debugger_native_pid(pid_t observer,pid_t visible) {
    struct thread *t=find_thread(observer);
    return t ? native_pid(t,visible) : 0;
}

int inspect_allowed(pid_t caller, pid_t pid, int filesystem) {
    struct thread *from=find_thread(caller), *to=find_thread(pid);
    if (!from || !to || md_domain_restricted(from->resources.fs)) return 0;
    int allowed=from->tgid==to->tgid || md_process_may_inspect(&from->identity,&to->identity,filesystem,
        to->resources.access);
    if (!allowed) SUPERVISOR_TRACE("PROBE inspect-denied caller=%d target=%d filesystem=%d uid=%u/%u gid=%u/%u target-uid=%u/%u/%u target-gid=%u/%u/%u caps=%#llx/%#llx target-caps=%#llx dumpable=%d same-userns=%d\n",
        caller,pid,filesystem,from->identity.uid.real,from->identity.uid.fs,
        from->identity.gid.real,from->identity.gid.fs,
        to->identity.uid.real,to->identity.uid.effective,to->identity.uid.saved,
        to->identity.gid.real,to->identity.gid.effective,to->identity.gid.saved,
        (unsigned long long)from->identity.caps.permitted,(unsigned long long)from->identity.caps.effective,
        (unsigned long long)to->identity.caps.permitted,md_process_dumpable(to->resources.access),
        from->identity.user_namespace==to->identity.user_namespace);
    return allowed;
}

int debugger_allowed(pid_t caller, pid_t pid) {
    return inspect_allowed(caller,pid,0);
}

int debugger_traceme(pid_t parent,pid_t child) {
    struct thread *from=find_thread(parent), *to=find_thread(child);
    return from && to && !md_domain_restricted(from->resources.fs)
        && md_identity_traceme(&from->identity,&to->identity);
}

int proc_allowed(pid_t caller, pid_t pid) {
    return inspect_allowed(caller,pid,1);
}

int debugger_call(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs) {
    if (call->nr==SYS_ptrace)
        SUPERVISOR_TRACE("PROBE guest debugger caller=%d request=%llu target=%llu address=%#llx data=%#llx traced=%d\n",
            t->pid,(unsigned long long)call->args[0],(unsigned long long)call->args[1],
            (unsigned long long)call->args[2],(unsigned long long)call->args[3],
            md_debugger_traced(debugger,native_pid(t,(pid_t)call->args[1])));
    execution_retain(t,call,regs);
    return md_debugger_call(debugger,t->pid,*call);
}

void debugger_complete(pid_t pid,long value) {
    struct thread *t=find_thread(pid);
    SUPERVISOR_TRACE("PROBE debugger result pid=%d value=%ld\n",pid,value);
    if (t) return_value(t,entry_pending(t) ? NULL : &t->execution.registers,value);
}

int debugger_attachable(pid_t caller, pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t || debugger_group(caller)==debugger_group(pid) || !debugger_allowed(caller,pid)
            || !t->entered || !t->image.process_image) {
        SUPERVISOR_TRACE("PROBE debugger admission caller=%d target=%d state-or-identity-denied\n",caller,pid);
        return 0;
    }
    char byte;
    struct iovec local={&byte,1}, remote={(void *)t->image.process_image,1};
    ssize_t result=process_vm_readv(pid,&local,1,&remote,1,0);
    if (result!=1) SUPERVISOR_TRACE("PROBE debugger admission caller=%d target=%d image=%#lx read=%zd errno=%d\n",
        caller,pid,(unsigned long)t->image.process_image,result,errno);
    return result==1;
}

int debugger_interrupt(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    /* An owned stop already queued by clone is itself the interruption boundary. */
    if (t->kernel_stopped || birth_stop_pending(t)) return 0;
    if (native_trace(PTRACE_INTERRUPT,pid,NULL,NULL)) return -errno;
    t->interrupt_pending=1; return 0;
}

int debugger_observe(pid_t owner) {
    struct thread *parent=find_thread(owner);
    if (!parent) return -ESRCH;
    /* EVENT_WAIT: a new trace relationship can select a wait already blocked
     * in the kernel, in any owner thread. INTERRUPT restarts it through the
     * debugger-aware path; cancellation/exit still owns the wait's lifetime. */
    for (struct thread *t=threads; t; t=t->next) if (t->pid && t->tgid==parent->tgid) {
        /* A retained shared request identifies its call without inspecting
         * registers. Only a native child wait needs debugger re-admission. */
        if (entry_pending(t) && t->execution.request.nr!=SYS_wait4
                && t->execution.request.nr!=SYS_waitid) continue;
        if (entry_pending(t) && t->entry.wait.complete) { entry_wait_complete(t,0); continue; }
        if (entry_pending(t) && debugger_wait_pending(t)) continue;
        int error=debugger_interrupt(t->pid);
        if (error && error!=-ESRCH) return error;
    }
    return 0;
}

int debugger_options(pid_t pid, unsigned long options) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    int changed=((t->debug.options^options)&PTRACE_O_TRACEEXIT) && !t->terminal_observation;
    t->debug.options=options;
    if (!changed && !t->trace_options_dirty) return 0;
    t->trace_options_dirty=1;
    if (!t->kernel_stopped) return debugger_interrupt(pid);
    unsigned long required=t->terminal_observation ? PTRACE_O_TRACEEXIT : 0;
    if (native_trace(PTRACE_SETOPTIONS,pid,NULL,(void *)(trace_options|required|(options&PTRACE_O_TRACEEXIT)))) return -errno;
    t->trace_options_dirty=0;
    return 0;
}

void debugger_usage(pid_t pid, struct rusage *usage) {
    struct thread *t=find_thread(pid);
    *usage=t ? t->usage : (struct rusage){0};
}

int debugger_listen(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    if (native_trace(PTRACE_LISTEN,pid,NULL,NULL)) return -errno;
    t->listening=1; t->kernel_stopped=0;
    return 0;
}

void debugger_wait_wake(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t || t->debug.wait_waking) return;
    if (entry_pending(t)) {
        CHECK(t->entry.wait.complete);
        entry_wait_complete(t,0); return;
    }
    CHECK(t->phase==DEBUG_PARK_ENTRY || t->phase==DEBUG_PARK_EXIT);
    t->debug.wait_waking=1;
    /* EVENT_WAIT: the exact native poll stop must precede register changes.
     * An event before poll entry is consumed there without entering the wait. */
    if (t->phase==DEBUG_PARK_EXIT && !t->listening) {
        int error=debugger_interrupt(pid); CHECK(!error || error==-ESRCH);
    }
}

int debugger_wait_admit(pid_t pid) {
    struct thread *t=find_thread(pid);
    return t && (!entry_pending(t) || !checkpoint_restricted(pid));
}

static void debugger_wait_result(struct thread *t,long result) {
    union md_process_wait value,*captured=NULL;
    if (t->debug.wait_output) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->debug.wait_output->buffer,&span));
        memcpy(&value,span.data,sizeof(value)); captured=&value;
        debugger_wait_release(t);
    }
    t->phase=IDLE;
    md_debugger_wait_complete(debugger,t->pid,result,captured);
    if (captured) adapter_progress();
}

static void debugger_entry_probed(struct thread *t,long result) {
    if (t->entry.effect_cancelled || md_syscall_restarting(result)) {
        debugger_wait_release(t);
        md_debugger_wait_resumed(debugger,t->pid,-512);
    } else debugger_wait_result(t,result);
}

static void debugger_entry_woken(struct thread *t,long result) {
    md_debugger_wait_resumed(debugger,t->pid,result==-EINTR ? -512 : result);
}

int debugger_wait_pending(const struct thread *t) {
    return t->entry.effect==debugger_entry_probed || t->phase==DEBUG_WAIT_ENTRY || t->phase==DEBUG_WAIT_EXIT;
}

void debugger_wait(pid_t pid,const struct seccomp_data *call,enum md_debugger_wait_action action) {
    struct thread *t=find_thread(pid);
    if (!t) return;
    int shared=entry_pending(t);
    if (shared) {
        CHECK(t->phase==IDLE || t->phase==DEBUG_WAIT_BUFFER);
        t->phase=IDLE;
        if (action==MD_DEBUG_WAIT_RESTART) { entry_restart(t,RESTART_SIGNAL); return; }
        if (action==MD_DEBUG_WAIT_PARK) {
            CHECK(entry_wait_begin(t,INT64_MAX,debugger_entry_woken)); return;
        }
        if (action==MD_DEBUG_WAIT_NATIVE) {
            unsigned long cookie=MD_INTERCEPT_PROCESS;
            CHECK(process_call(t,call,NULL,&cookie)); return;
        }
    }
    const struct user_pt_regs *original=&t->execution.registers;
    uint64_t args[6]; memcpy(args,call->args,sizeof(args));
    unsigned index=call->nr==SYS_waitid ? 1 : 0;
    if ((action==MD_DEBUG_WAIT_PROBE || action==MD_DEBUG_WAIT_REPROBE)
            && (call->nr==SYS_wait4 || call->args[0]==P_PID || call->args[0]==P_PGID) && (pid_t)call->args[index]>0) {
        pid_t mapped=native_pid(t,(pid_t)call->args[index]);
        if (!mapped) {
            debugger_wait_release(t); t->phase=IDLE;
            md_debugger_wait_complete(debugger,pid,-ECHILD,NULL); return;
        }
        args[index]=mapped;
    }
    unsigned output=call->nr==SYS_waitid ? 2 : 1;
    if ((action==MD_DEBUG_WAIT_PROBE || action==MD_DEBUG_WAIT_REPROBE)
            && !call->args[output]) {
        retry_clear(t,RETRY_DEBUG_WAIT);
        if (!t->debug.wait_output) t->debug.wait_output=calloc(1,sizeof(*t->debug.wait_output));
        struct debug_wait_output *out=t->debug.wait_output;
        int error=out ? md_buffers_take(md_memory_buffers(t->memory),&out->buffer) : -ENOMEM;
        if (error==-EAGAIN) {
            out->call=*call; out->action=action;
            t->phase=DEBUG_WAIT_BUFFER; retry_wait(t,RETRY_DEBUG_WAIT); return;
        }
        if (error) {
            debugger_wait_release(t); t->phase=IDLE;
            md_debugger_wait_complete(debugger,pid,error,NULL); return;
        }
        struct md_buffer_span span; CHECK(!md_buffer_span(&out->buffer,&span));
        memset(span.data,0,sizeof(union md_process_wait));
        args[output]=md_memory_buffer_address(t->memory,&out->buffer); CHECK(args[output]);
    }
    if (shared) {
        args[call->nr==SYS_waitid ? 3 : 2]|=WNOHANG;
        entry_effect(t,call->nr,args,debugger_entry_probed); return;
    }
    struct user_pt_regs regs=*original;
    regs.regs[8]=call->nr; memcpy(regs.regs,args,sizeof(args));
    if (action==MD_DEBUG_WAIT_RESTART) { restart_call(t,original,t->debug.wait_mask,RESTART_SIGNAL); return; }
    if (action==MD_DEBUG_WAIT_PARK) {
        /* EVENT_WAIT: native interruptible poll delivers arbitrary guest
         * signals. Inferior events cancel it with INTERRUPT; no periodic wake
         * or descriptor is needed, and ordinary execution never enters here. */
        t->debug.wait_waking=0;
        if (!shield(t)) return;
        wait_poll_start(t,DEBUG_PARK_ENTRY);
        return;
    }
    SUPERVISOR_TRACE("PROBE debugger wait pid=%d nr=%llu target=%llu action=%d phase=%d\n",pid,regs.regs[8],regs.regs[0],action,t->phase);
    if (action!=MD_DEBUG_WAIT_PROBE) regs.pc-=4;
    if (action==MD_DEBUG_WAIT_NATIVE) t->phase=IDLE;
    else {
        /* Only the nonblocking probe is shielded. A handler must not replace
         * the replayed syscall; native blocking waits retain ordinary signals. */
        if (!save_signal_mask(t,&t->debug.wait_mask,"debug-wait-mask")
                || !shield(t)) return;
        regs.regs[call->nr==SYS_waitid ? 3 : 2]|=WNOHANG;
        t->phase=action==MD_DEBUG_WAIT_PROBE ? DEBUG_WAIT_EXIT : DEBUG_WAIT_ENTRY;
    }
    if (set_registers(pid,&regs)) resume(pid,0);
}

void debugger_wait_retry(struct thread *t) {
    struct debug_wait_output *out=t->debug.wait_output;
    CHECK(out && t->phase==DEBUG_WAIT_BUFFER);
    struct seccomp_data call=out->call;
    debugger_wait(t->pid,&call,out->action);
}

int debug_syscall_exit(struct thread *t, const struct user_pt_regs *regs) {
    int active=t->debug.call_active;
    t->debug.call_active=t->debug.adapter=0;
    struct user_pt_regs captured;
    if (active && !regs) {
        if (!registers(t->pid,&captured)) return 1;
        regs=&captured;
    }
    return active && md_debugger_syscall(debugger,t->pid,regs,0);
}

static int debug_host_policy_visit(void *context,const siginfo_t *info) {
    const struct seccomp_data *call=context;
    return info->si_signo==SIGSYS && info->si_code==SYS_SECCOMP
        && info->si_arch==call->arch && info->si_syscall==call->nr
        && (uintptr_t)info->si_call_addr==call->instruction_pointer;
}

int debug_host_policy_pending(struct thread *t,const struct user_pt_regs *regs) {
    if (!t->signals.host || !t->debug.call_active || !t->entered) return 0;
    struct seccomp_data call=syscall_description(regs);
    int pending=md_task_pending_scan(native_trace,t->pid,debug_host_policy_visit,&call);
    if (pending<=0) return pending;
    uint32_t decision;
    CHECK(!md_seccomp_policy_eval(admission_policy(t),&call,&decision));
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_ALLOW || action==SECCOMP_RET_LOG)
        action=host_policy_interception(&call)&SECCOMP_RET_ACTION_FULL;
    /* Guest TRAP and unsupported host calls retain native exit-then-signal
     * ordering. Only an owned adapter defers the debugger's result. */
    return action==SECCOMP_RET_ERRNO || action==SECCOMP_RET_TRACE || action==SECCOMP_RET_USER_NOTIF;
}

int debugger_at_boundary(const struct thread *t) {
    return t->phase==IDLE && !t->metadata_restart && !t->descriptors.running && !t->descriptors.order.table && !t->descriptors.fs_order.fs
        && !t->descriptors.policy_order.queue && !t->memory_op.order.queue && !t->memory_op.files.table
        && !t->signals.delivery.active;
}

int debugger_boundary(struct thread *t) {
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    if (machine && md_debugger_traced(debugger,t->pid)) {
        /* An interrupt can land inside a resident entry, outside the syscall
         * adapter. Retire that frame before exposing registers to the debugger;
         * a running payload finishes through its existing return checkpoint. */
        if (!svc_entry_observe(t)) return 1;
        int deferred=md_svc_machine_flush(native_trace,machine,&t->machine,t->pid);
        if (deferred==-ESRCH) { t->resume_lost=1; return 1; }
        CHECK(deferred>=0);
        if (deferred) { t->machine.draining=1; return 0; }
    }
    return md_debugger_boundary(debugger,t->pid);
}

void debugger_park_complete(struct thread *t,int cancelled) {
    long value=0;
    if (!cancelled) {
        struct user_pt_regs regs;
        if (!registers(t->pid,&regs)) return;
        value=(long)regs.regs[0];
    }
    int waking=t->debug.wait_waking; t->debug.wait_waking=0;
    CHECK(!cancelled || waking);
    int interrupted=md_syscall_restarting(value);
    SUPERVISOR_TRACE("PROBE debugger park pid=%d value=%ld waking=%d\n",t->pid,value,waking);
    t->phase=IDLE;
    if (!signal_mask(t,t->debug.wait_mask,"debug-park-unmask")) return;
    md_debugger_wait_resumed(debugger,t->pid,waking && (cancelled || interrupted || value==-ENOSYS || !value) ? 0
        : interrupted ? -512 : value<0 ? value : -EIO);
}

int debug_wait_exit(struct thread *t,pid_t pid) {
    if (t->phase==DEBUG_WAIT_ENTRY) {
        t->phase=DEBUG_WAIT_EXIT; resume(pid,0); return 1;
    }
    if (t->phase==DEBUG_WAIT_EXIT) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return 1;
        if (!signal_mask(t,t->debug.wait_mask,"debug-wait-unmask")) return 1;
        debugger_wait_result(t,(long)regs.regs[0]);
        return 1;
    }
    if (t->phase==DEBUG_PARK_ENTRY) {
        int entered=wait_poll_entry(t);
        if (entered<=0) { if (!entered) resume(pid,0); return 1; }
        if (t->debug.wait_waking) {
            wait_cancel_entry(t,DEBUG_PARK_EXIT);
            return 1;
        } else if (!signal_mask(t,t->debug.wait_mask,"debug-park-unmask")) return 1;
        t->phase=DEBUG_PARK_EXIT; resume(pid,0); return 1;
    }
    if (t->phase==DEBUG_PARK_EXIT) { debugger_park_complete(t,0); return 1; }
    return 0;
}
