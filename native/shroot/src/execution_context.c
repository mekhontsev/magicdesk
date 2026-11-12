#include "guest_execution.h"
#include "descriptor_calls.h"
#include "socket_calls.h"
#include <linux/ipc.h>

enum md_execution_context md_execution_dispatch_context(const struct md_guest_execution *call) {
    long nr=call->number;
    const unsigned long *args=call->args;
    /* These C adapters still require their original-stack continuation. This
     * is not a property of all calls that can block: retained owners handle
     * ordinary IO, provider waits and SysV IPC without a stopped task. */
    if (nr==SYS_execve || nr==SYS_execveat) return MD_CONTEXT_EXEC;
    if (nr==SYS_rt_sigsuspend || ((nr==SYS_epoll_pwait || nr==SYS_epoll_pwait2) && args[4])
            || (nr==SYS_ppoll && args[3]) || (nr==SYS_pselect6 && args[5])) return MD_CONTEXT_SIGNAL;
    return MD_CONTEXT_COUNT;
}

enum md_execution_context md_execution_native_context(long nr,const uint64_t args[6]) {
    if (nr==SYS_clone || nr==SYS_clone3) return MD_CONTEXT_BIRTH;
    if (nr==SYS_ptrace) return MD_CONTEXT_DEBUG;
    /* An admitted driver command can block, just like native payload IO.
     * The journal owns interruption/restart; descriptor classification alone
     * does not require a stopped caller or a second adapter invocation. */
    if (nr==SYS_ioctl && !md_descriptor_query_call(nr,(unsigned)args[1])) return MD_CONTEXT_WAIT;
    if (nr==SYS_wait4 || nr==SYS_waitid) return MD_CONTEXT_WAIT;
    if (nr==SYS_execve || nr==SYS_execveat) return MD_CONTEXT_EXEC;
    if (nr==SYS_shmat || nr==SYS_shmdt) return MD_CONTEXT_MEMORY;
    if (nr==SYS_rt_sigreturn || nr==SYS_rt_sigaction || (nr==SYS_rt_sigprocmask && args[1])
            || nr==SYS_sigaltstack) return MD_CONTEXT_SIGNAL;
    if (nr==SYS_set_robust_list) return MD_CONTEXT_PROCESS;
    /* Sleeping effects need the signal-aware native journal, not a raw call
     * from the C stack. Lack of service-gate admission alone requires Base. */
    if (nr==SYS_epoll_pwait2 || nr==SYS_ppoll || nr==SYS_pselect6 || nr==SYS_rt_sigsuspend
            || (nr==SYS_epoll_pwait && ((int)args[3] || args[4]))
            || nr==SYS_semop || nr==SYS_semtimedop
            || (nr==SYS_msgsnd && !(args[3]&IPC_NOWAIT))
            || (nr==SYS_msgrcv && !(args[4]&IPC_NOWAIT))
            || nr==SYS_read || nr==SYS_readv || nr==SYS_write || nr==SYS_writev
            || nr==SYS_futex || nr==SYS_clock_nanosleep || nr==SYS_nanosleep || nr==SYS_io_uring_enter
            || nr==SYS_splice || nr==SYS_tee || nr==SYS_sendfile
            || md_socket_wait_call(nr,args[2],args[3])) return MD_CONTEXT_WAIT;
    return MD_CONTEXT_COUNT;
}
