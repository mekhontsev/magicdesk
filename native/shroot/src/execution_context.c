#include "guest_execution.h"
#include "interception.h"
#include "identity_call.h"
#include "seccomp_install.h"
#include "terminal_calls.h"

enum md_execution_context md_execution_context_needed(long nr,const uint64_t args[6],unsigned cookie) {
    if (cookie&MD_INTERCEPT_CLOSE) cookie=MD_INTERCEPT_FD;
    switch (cookie) {
    case MD_INTERCEPT_MEMORY: return MD_CONTEXT_MEMORY;
    case MD_INTERCEPT_FD:
        return nr==SYS_clone || nr==SYS_clone3 ? MD_CONTEXT_BIRTH : MD_CONTEXT_DESCRIPTOR;
    case MD_INTERCEPT_PROC: case MD_INTERCEPT_WATCH: case MD_INTERCEPT_CWD: case MD_INTERCEPT_CAPTURE:
        return MD_CONTEXT_DESCRIPTOR;
    case MD_INTERCEPT_SIGNAL: return MD_CONTEXT_SIGNAL;
    case MD_INTERCEPT_OBSERVE:
        if (md_seccomp_filter_call(nr,args[0],args[1])) return MD_CONTEXT_POLICY;
        if (nr==SYS_chroot || nr==SYS_unshare || nr==SYS_setns) return MD_CONTEXT_NAMESPACE;
        return MD_CONTEXT_PROCESS;
    case MD_INTERCEPT_PROCESS:
        if (nr!=SYS_getpid && nr!=SYS_gettid) return MD_CONTEXT_PROCESS;
        break;
    case MD_INTERCEPT_IDENTITY:
        if (md_identity_scalar_call(nr) || md_identity_scalar_update(nr) || nr==SYS_prctl) break;
        return MD_CONTEXT_COPY;
    }
    if (nr==SYS_ptrace) return MD_CONTEXT_DEBUG;
    /* A descriptor owner may decline an ioctl to a native driver, including
     * blocking terminal operations. Preserve that continuation's caller frame. */
    if (nr==SYS_ioctl && (unsigned)args[1]!=FIONREAD && (unsigned)args[1]!=TIOCGWINSZ
            && (unsigned)args[1]!=TCGETS && (unsigned)args[1]!=TCGETS2) return MD_CONTEXT_DESCRIPTOR;
    if (nr==SYS_wait4 || nr==SYS_waitid) return MD_CONTEXT_WAIT;
    if (nr==SYS_execve || nr==SYS_execveat) return MD_CONTEXT_EXEC;
    if (nr==SYS_shmat || nr==SYS_shmdt) return MD_CONTEXT_MEMORY;
    if (nr==SYS_rt_sigreturn || nr==SYS_rt_sigaction || nr==SYS_rt_sigprocmask
            || nr==SYS_sigaltstack) return MD_CONTEXT_SIGNAL;
    if (nr==SYS_set_robust_list || nr==SYS_set_tid_address) return MD_CONTEXT_PROCESS;
    /* These adapters can sleep or return a kernel restart. Their owners need
     * the original mask and restart boundary, not a resident C stack. */
    if (nr==SYS_epoll_pwait2 || nr==SYS_ppoll || nr==SYS_pselect6 || nr==SYS_rt_sigsuspend
            || (nr==SYS_epoll_pwait && ((int)args[3] || args[4]))
            || nr==SYS_semop || nr==SYS_semtimedop || nr==SYS_msgsnd || nr==SYS_msgrcv
            || nr==SYS_read || nr==SYS_readv || nr==SYS_write || nr==SYS_writev
            || nr==SYS_futex || nr==SYS_clock_nanosleep || nr==SYS_nanosleep || nr==SYS_io_uring_enter
            || nr==SYS_splice || nr==SYS_tee || nr==SYS_sendfile
            || nr==SYS_accept || nr==SYS_accept4 || nr==SYS_recvfrom || nr==SYS_recvmsg || nr==SYS_recvmmsg
            || nr==SYS_connect || nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_sendmmsg) return MD_CONTEXT_WAIT;
    return MD_CONTEXT_COUNT;
}
