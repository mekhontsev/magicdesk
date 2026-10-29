#define _GNU_SOURCE
#include "task_call.h"
#include "task_stop.h"
#include "syscall_result.h"
#include "interception.h"
#include "signal_protocol.h"
#include <elf.h>
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <string.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/syscall.h>

#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif
enum { CALL_ENTRY=1, CALLING, USER_RESTORING, REENTERING, RESTORING,
    EXIT_REENTERING, EXIT_SKIPPING, COMPLETE, RECOVERING, TERMINATING, EMULATED_EXIT };
_Static_assert((int)MD_TASK_CALL_USER==PTRACE_SYSCALL_INFO_NONE
    && (int)MD_TASK_CALL_ENTRY==PTRACE_SYSCALL_INFO_ENTRY
    && (int)MD_TASK_CALL_EXIT==PTRACE_SYSCALL_INFO_EXIT
    && (int)MD_TASK_CALL_SECCOMP==PTRACE_SYSCALL_INFO_SECCOMP,"ptrace boundary values");
static int trace(struct md_task_call *f,int request,void *address,void *data) {
    return f->trace(request,f->actor,address,data)<0 ? -errno : 0;
}
static int regs(struct md_task_call *f,int request,struct user_pt_regs *r) {
    struct iovec span={r,sizeof(*r)};
    return trace(f,request,(void *)NT_PRSTATUS,&span);
}
static int info(struct md_task_call *f,struct ptrace_syscall_info *out) {
    memset(out,0,sizeof(*out));
    return trace(f,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(*out),out);
}
static int number(struct md_task_call *f,int value) {
    struct iovec span={&value,sizeof(value)};
    return trace(f,PTRACE_SETREGSET,(void *)NT_ARM_SYSTEM_CALL,&span);
}
static int invoke(struct md_task_call *f) {
    f->replaced_entry=0;
    struct user_pt_regs call=f->saved;
    call.pc=f->call;
    call.regs[8]=f->arguments.number;
    f->active_number=f->arguments.number;
    memcpy(call.regs,f->arguments.value,sizeof(f->arguments.value));
    int error=regs(f,PTRACE_SETREGSET,&call);
    if (!error) { f->stage=CALL_ENTRY; error=trace(f,PTRACE_SYSCALL,NULL,NULL); }
    return error;
}
static int replace_entry(struct md_task_call *f) {
    f->replaced_entry=1;
    struct user_pt_regs call=f->saved;
    call.pc=f->returned;
    call.regs[8]=f->arguments.number;
    f->active_number=f->arguments.number;
    memcpy(call.regs,f->arguments.value,sizeof(f->arguments.value));
    int error=regs(f,PTRACE_SETREGSET,&call);
    if (!error) error=number(f,(int)f->arguments.number);
    if (!error) { f->stage=CALLING; error=trace(f,PTRACE_SYSCALL,NULL,NULL); }
    return error;
}
int md_task_call_begin(struct md_task_call *f,long (*operation)(int,pid_t,void *,void *),
        pid_t actor,enum md_task_call_boundary boundary,const struct user_pt_regs *resume,
        uintptr_t call,uintptr_t returned,const struct md_task_call_arguments *arguments) {
    if (!f || !operation || actor<=0 || !resume || !arguments || !call
            || returned!=call+4 || boundary<MD_TASK_CALL_USER || boundary>MD_TASK_CALL_EMULATED_ENTRY) return -EINVAL;
    if (f->stage) return -EBUSY;
    *f=(struct md_task_call){.trace=operation,.actor=actor,.boundary=boundary,
        .saved=*resume,.entry=*resume,.call=call,.returned=returned,.arguments=*arguments};
    struct ptrace_syscall_info observed;
    int error=info(f,&observed);
    unsigned expected=boundary==MD_TASK_CALL_EMULATED_ENTRY ? PTRACE_SYSCALL_INFO_ENTRY : (unsigned)boundary;
    if (!error && expected!=observed.op) error=-EINVAL;
    if (!error && (boundary==MD_TASK_CALL_ENTRY || boundary==MD_TASK_CALL_SECCOMP || boundary==MD_TASK_CALL_EMULATED_ENTRY)
            && (resume->pc<4 || observed.instruction_pointer!=resume->pc)) error=-EINVAL;
    int restarting=0;
    if (!error && boundary==MD_TASK_CALL_EXIT) {
        long value=(long)resume->regs[0];
        restarting=md_syscall_restarting(value);
        if (resume->pc<4) error=-EINVAL;
    }
    if (!error) error=trace(f,PTRACE_GETSIGMASK,(void *)sizeof(f->mask),&f->mask);
    if (error) { *f=(struct md_task_call){0}; return error; }
    uint64_t blocked=md_signal_blockable_mask();
    error=trace(f,PTRACE_SETSIGMASK,(void *)sizeof(blocked),&blocked);
    if (error) { *f=(struct md_task_call){0}; return error; }
    f->stage=CALL_ENTRY;
    if (restarting) {
        /* EVENT_WAIT: stop at the kernel's restarted entry before it executes.
         * orig_x0 is not exposed by GETREGSET; the entry is authoritative.
         * Redirect the restart to a stable SVC without fetching source code. */
        struct user_pt_regs restart=f->saved; restart.pc=f->returned;
        error=regs(f,PTRACE_SETREGSET,&restart); if (error) return error;
        f->restarting=1; f->stage=RECOVERING;
        return trace(f,PTRACE_SYSCALL,NULL,NULL);
    }
    if (boundary==MD_TASK_CALL_ENTRY || boundary==MD_TASK_CALL_SECCOMP) {
        return replace_entry(f);
    }
    if (boundary==MD_TASK_CALL_EMULATED_ENTRY) {
        f->stage=EMULATED_EXIT;
        return trace(f,PTRACE_SYSCALL,NULL,NULL);
    }
    return invoke(f);
}
static int finish(struct md_task_call *f,int restore) {
    int error=restore ? regs(f,PTRACE_SETREGSET,&f->saved) : 0;
    if (!error) error=trace(f,PTRACE_SETSIGMASK,(void *)sizeof(f->mask),&f->mask);
    if (!error && f->restarting) {
        /* The first restart consumed the interrupt's pending kernel work.
         * Rearm it so the restored ERESTART result runs through do_signal even
         * without a real pending signal, instead of escaping to libc. The
         * ordinary dispatcher owns the resulting synthetic stop. */
        error=trace(f,PTRACE_INTERRUPT,NULL,NULL);
        if (!error) f->restart_pending=1;
    }
    if (!error) f->stage=COMPLETE;
    return error ? error : 1;
}
int md_task_call_again(struct md_task_call *f,const struct md_task_call_arguments *arguments) {
    if (!f || f->stage!=COMPLETE || !arguments) return -EINVAL;
    uint64_t blocked=md_signal_blockable_mask();
    int error=trace(f,PTRACE_SETSIGMASK,(void *)sizeof(blocked),&blocked);
    if (error) return error;
    f->arguments=*arguments; f->stage=CALL_ENTRY;
    if (f->boundary==MD_TASK_CALL_ENTRY || f->boundary==MD_TASK_CALL_SECCOMP) {
        return replace_entry(f);
    }
    if (f->boundary==MD_TASK_CALL_EMULATED_ENTRY) {
        f->stage=EMULATED_EXIT;
        return trace(f,PTRACE_SYSCALL,NULL,NULL);
    }
    return invoke(f);
}
int md_task_call_resume(struct md_task_call *f,int signal) {
    if (!f || !f->stage || f->stage==COMPLETE) return -EINVAL;
    return trace(f,f->stage==USER_RESTORING || f->stage==RESTORING || f->stage==TERMINATING ? PTRACE_CONT
        : f->stage==EXIT_REENTERING || (f->stage==REENTERING && f->boundary==MD_TASK_CALL_EMULATED_ENTRY)
            ? PTRACE_SYSEMU : PTRACE_SYSCALL,
        NULL,(void *)(intptr_t)signal);
}
int md_task_call_expects_interrupt(const struct md_task_call *f) {
    return f && f->stage && f->stage!=COMPLETE && (f->restart_pending || f->stage==USER_RESTORING);
}
static int task_call_event(struct md_task_call *f,int status) {
    if (!f || !f->stage || f->stage==COMPLETE) return -EINVAL;
    if (WIFEXITED(status) || WIFSIGNALED(status)) return -ESRCH;
    if (!WIFSTOPPED(status)) return -EPROTO;
    unsigned event=(unsigned)status>>16;
    int signal=WSTOPSIG(status);
    if (f->stage==TERMINATING) return trace(f,PTRACE_CONT,NULL,NULL);
    if (f->restart_pending && event==PTRACE_EVENT_STOP && signal==SIGTRAP) {
        f->restart_pending=0;
        return md_task_call_resume(f,0);
    }
    struct user_pt_regs current;
    int error=regs(f,PTRACE_GETREGSET,&current);
    if (error) return error;
    if (f->stage==EMULATED_EXIT) {
        struct ptrace_syscall_info observed;
        if (event || signal!=(SIGTRAP|0x80)) return -EPROTO;
        error=info(f,&observed); if (error) return error;
        if (observed.op!=PTRACE_SYSCALL_INFO_EXIT) return -EPROTO;
        return invoke(f);
    }
    if (f->stage==USER_RESTORING) {
        if (event!=PTRACE_EVENT_STOP || signal!=SIGTRAP || current.pc!=f->returned) return -EPROTO;
        return finish(f,1);
    }
    if (f->stage==CALL_ENTRY) {
        struct ptrace_syscall_info observed;
        if (event || signal!=(SIGTRAP|0x80)) return -EPROTO;
        error=info(f,&observed); if (error) return error;
        if (observed.op!=PTRACE_SYSCALL_INFO_ENTRY || current.pc!=f->returned
                || observed.entry.nr!=(unsigned long)f->active_number
                || (f->active_number!=SYS_restart_syscall
                    && memcmp(observed.entry.args+1,f->arguments.value+1,5*sizeof(unsigned long)))) return -EPROTO;
        if (f->active_number!=SYS_restart_syscall && observed.entry.args[0]!=f->arguments.value[0]) {
            if (!f->replaced_entry) return -EPROTO;
            /* Replacing an already stopped entry cannot replace kernel-private
             * orig_x0. After a native restart restore only this borrowed value;
             * restart_block and all other argument validation remain native. */
            current.regs[0]=f->arguments.value[0];
            error=regs(f,PTRACE_SETREGSET,&current); if (error) return error;
        }
        f->stage=CALLING;
        return trace(f,PTRACE_SYSCALL,NULL,NULL);
    }
    if (f->stage==RECOVERING || f->stage==REENTERING || f->stage==EXIT_REENTERING || f->stage==EXIT_SKIPPING) {
        if (event || signal!=(SIGTRAP|0x80)) return -EPROTO;
        struct ptrace_syscall_info observed;
        error=info(f,&observed); if (error) return error;
        if (f->stage==RECOVERING) {
            unsigned long nr=(long)f->saved.regs[0]==-516 ? SYS_restart_syscall : f->saved.regs[8];
            if (observed.op!=PTRACE_SYSCALL_INFO_ENTRY || observed.entry.nr!=nr
                    || current.pc!=f->returned) return -EPROTO;
            f->entry=current; f->entry.pc=f->saved.pc;
            return replace_entry(f);
        }
        if (f->stage==EXIT_SKIPPING) {
            if (observed.op!=PTRACE_SYSCALL_INFO_EXIT) return -EPROTO;
            error=number(f,(int)f->saved.regs[8]);
            return error ? error : finish(f,1);
        }
        if (observed.op!=PTRACE_SYSCALL_INFO_ENTRY || observed.entry.nr!=f->entry.regs[8]
                || current.pc!=f->returned
                || memcmp(observed.entry.args,f->entry.regs,sizeof(observed.entry.args))) return -EPROTO;
        /* Replay through the stable gate, then restore the original PC before
         * seccomp evaluates the entry. The source instruction may have been
         * patched or unmapped while its syscall was stopped. */
        error=regs(f,PTRACE_SETREGSET,&f->entry); if (error) return error;
        if (f->stage==EXIT_REENTERING) {
            /* SYSEMU captured this entry before seccomp. Its exit restores
             * orig_x0 without re-executing a completed application call or
             * requiring the filter to admit an artificial syscall number. */
            f->stage=EXIT_SKIPPING;
            return trace(f,PTRACE_SYSCALL,NULL,NULL);
        }
        if (f->boundary==MD_TASK_CALL_ENTRY || f->boundary==MD_TASK_CALL_EMULATED_ENTRY) return finish(f,0);
        f->stage=RESTORING;
        return trace(f,PTRACE_CONT,NULL,NULL);
    }
    if (event==PTRACE_EVENT_SECCOMP) {
        unsigned long cookie;
        error=trace(f,PTRACE_GETEVENTMSG,NULL,&cookie); if (error) return error;
        if (f->stage==RESTORING) {
            if (current.pc!=f->saved.pc || current.regs[8]!=f->saved.regs[8]
                    || memcmp(current.regs,f->saved.regs,6*sizeof(current.regs[0]))) return -EPROTO;
            f->cookie=cookie; return finish(f,0);
        }
        if (f->stage!=CALLING || cookie!=MD_INTERCEPT_TASK || current.pc!=f->returned
                || current.regs[8]!=(unsigned long)f->active_number
                || (f->active_number!=SYS_restart_syscall
                    && memcmp(current.regs,f->arguments.value,sizeof(f->arguments.value)))) return -EPROTO;
        return trace(f,PTRACE_SYSCALL,NULL,NULL);
    }
    if (f->stage!=CALLING || event || signal!=(SIGTRAP|0x80) || current.pc!=f->returned) return -EPROTO;
    struct ptrace_syscall_info observed;
    error=info(f,&observed); if (error) return error;
    if (observed.op!=PTRACE_SYSCALL_INFO_EXIT) return -EPROTO;
    uint64_t mask;
    error=trace(f,PTRACE_GETSIGMASK,(void *)sizeof(mask),&mask); if (error) return error;
    if (!(mask&(UINT64_C(1)<<(SIGSYS-1)))) {
        /* KILL_THREAD can report EXIT before its forced SIGSYS. Restoring the
         * saved blocked mask here would suppress death and revive the actor.
         * Keep native termination in charge; no global TRACEEXIT is required. */
        f->stage=TERMINATING;
        return trace(f,PTRACE_CONT,NULL,NULL);
    }
    f->result=(long)current.regs[0];
    if (md_syscall_restarting(f->result)) {
        /* EVENT_WAIT: let the kernel restore orig_x0/restart_block and stop at
         * the restarted entry. A group stop must not complete the injection
         * with an internal ERESTART result or repeat a completed side effect. */
        if (f->result==-516) f->active_number=SYS_restart_syscall;
        f->stage=CALL_ENTRY;
        return trace(f,PTRACE_SYSCALL,NULL,NULL);
    }
    if (f->boundary==MD_TASK_CALL_USER) {
        /* EVENT_WAIT: leave the syscall boundary before restoring user registers.
         * ARM64 restores its hidden x7 after a syscall stop; the synthetic
         * interrupt returns control before any instruction after SVC executes. */
        error=trace(f,PTRACE_INTERRUPT,NULL,NULL); if (error) return error;
        f->stage=USER_RESTORING;
        return trace(f,PTRACE_CONT,NULL,NULL);
    }
    struct user_pt_regs replay=f->entry; replay.pc=f->call;
    error=regs(f,PTRACE_SETREGSET,&replay); if (error) return error;
    f->stage=f->boundary==MD_TASK_CALL_EXIT ? EXIT_REENTERING : REENTERING;
    return trace(f,f->boundary==MD_TASK_CALL_EXIT || f->boundary==MD_TASK_CALL_EMULATED_ENTRY
        ? PTRACE_SYSEMU : PTRACE_SYSCALL,NULL,NULL);
}
int md_task_call_event(struct md_task_call *f,int status) {
    int result=task_call_event(f,status);
    if (result==-EPROTO) {
        int displaced=md_task_stop_displaced(f->trace,f->actor);
        if (displaced) return displaced>0 ? -ESRCH : displaced;
    }
    return result;
}
