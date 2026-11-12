#include "interception.h"
#include "pid_filter_program.h"
#include <stddef.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>

void md_pid_filter_build(const struct md_interception_abi *abi,struct md_pid_filter_program *out) {
    uintptr_t gate=abi->raw_gate,task=abi->task_return,native=abi->native_return;
    uintptr_t adapter=abi->adapter_native_return;
#define PROCESS(name) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_##name,0,1), \
    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_PROCESS),
    const struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        /* PI words contain TIDs interpreted by the host kernel. Ordinary
         * wait/wake/requeue operations never leave their native fast path. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_futex,0,10),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,FUTEX_CMD_MASK),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FUTEX_LOCK_PI,6,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FUTEX_UNLOCK_PI,5,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FUTEX_TRYLOCK_PI,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FUTEX_WAIT_REQUEUE_PI,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FUTEX_CMP_REQUEUE_PI,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FUTEX_LOCK_PI2,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        PROCESS(getpid) PROCESS(gettid) PROCESS(getppid) PROCESS(set_tid_address)
        PROCESS(kill) PROCESS(tkill) PROCESS(tgkill) PROCESS(wait4) PROCESS(waitid)
        PROCESS(getpgid) PROCESS(getsid) PROCESS(setpgid) PROCESS(setsid)
        PROCESS(pidfd_open) PROCESS(pidfd_send_signal) PROCESS(rt_sigqueueinfo) PROCESS(rt_tgsigqueueinfo) PROCESS(rt_sigtimedwait)
        PROCESS(sched_getaffinity) PROCESS(sched_setaffinity) PROCESS(sched_getparam) PROCESS(sched_setparam)
        PROCESS(sched_getscheduler) PROCESS(sched_setscheduler) PROCESS(sched_getattr) PROCESS(sched_setattr)
        PROCESS(sched_rr_get_interval) PROCESS(get_robust_list) PROCESS(set_robust_list) PROCESS(prlimit64)
        PROCESS(ioprio_get) PROCESS(ioprio_set)
        PROCESS(process_vm_readv) PROCESS(process_vm_writev) PROCESS(kcmp)
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ioctl,0,6),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,TIOCGPGRP,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,TIOCSPGRP,1,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,TIOCGSID,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prctl,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,PR_SET_CHILD_SUBREAPER,1,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,PR_GET_CHILD_SUBREAPER,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_PROCESS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        /* Only PID-sensitive decisions inspect IP. Unrelated calls remain
         * eligible for the kernel's syscall-number-only seccomp allow cache. */
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(gate>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)gate,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        /* The task-call owner already selected native IDs. Leave its exact
         * site to the base filter, without replacing the completion cookie. */
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(task>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)task,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        /* A retained effect has already translated arguments and owns its
         * completion. Reapplying PID translation would corrupt native IDs. */
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(native>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)native,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(adapter>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)adapter,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_PROCESS)
    };
#undef PROCESS
    enum { count=sizeof(code)/sizeof(*code), tail=count-21 };
    _Static_assert(count<=MD_PID_FILTER_INSTRUCTIONS,"PID filter capacity");
    out->count=count;
    for (unsigned i=0;i<count;++i) {
        out->code[i]=code[i];
        if (i<tail && code[i].code==(BPF_RET|BPF_K)
                && code[i].k==(SECCOMP_RET_TRACE|MD_INTERCEPT_PROCESS))
            out->code[i]=(struct sock_filter)BPF_STMT(BPF_JMP|BPF_JA,tail-i-1);
    }
}
