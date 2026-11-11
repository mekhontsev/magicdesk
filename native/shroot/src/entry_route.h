#ifndef MD_ENTRY_ROUTE_H
#define MD_ENTRY_ROUTE_H
#include "syscall_catalog.h"
#include "identity_call.h"
#include <sys/syscall.h>

enum md_entry_route { MD_ENTRY_CALLER, MD_ENTRY_WORKER, MD_ENTRY_TASK, MD_ENTRY_FD, MD_ENTRY_VALUE, MD_ENTRY_MEMORY,
    MD_ENTRY_READINESS };
/* CALLER retains the original instruction, including clone/signal-return stack
 * semantics and native IO. Dynamic FD selectors still apply at that syscall.
 * TASK uses the common C executor where its operation needs no application
 * stack or kernel signal-mask transition; other cases retain that context. */
static inline enum md_entry_route md_entry_route(int number) {
    if (number==SYS_epoll_pwait || number==SYS_epoll_pwait2) return MD_ENTRY_READINESS;
    if (number==SYS_mmap || number==SYS_munmap || number==SYS_mprotect || number==SYS_mremap)
        return MD_ENTRY_MEMORY;
    if (md_identity_scalar_call(number)) return MD_ENTRY_VALUE;
    if (number==SYS_openat || number==SYS_openat2 || number==SYS_fsopen || number==SYS_close || number==SYS_fcntl
            || number==SYS_dup || number==SYS_dup3 || number==SYS_lseek) return MD_ENTRY_FD;
#define CASE(name) case SYS_##name:
    switch (number) {
        MD_ENTRY_WORKER_CALLS(CASE) return MD_ENTRY_WORKER;
    }
    switch (number) {
        MD_FILE_CALLS(CASE)
        MD_SOCKET_CALLS(CASE)
        case SYS_socket: case SYS_setsockopt: case SYS_recvmmsg:
        case SYS_chroot: case SYS_unshare: case SYS_setns:
        MD_IDENTITY_CALLS(CASE)
        case SYS_uname: case SYS_sethostname: case SYS_setdomainname:
        case SYS_shmget: case SYS_shmat: case SYS_shmdt: case SYS_shmctl:
        case SYS_semget: case SYS_semctl: case SYS_semop: case SYS_semtimedop:
        case SYS_msgget: case SYS_msgctl: case SYS_msgsnd: case SYS_msgrcv:
        case SYS_inotify_init1: case SYS_inotify_rm_watch:
        case SYS_epoll_ctl:
            return MD_ENTRY_TASK;
        default: return MD_ENTRY_CALLER;
    }
#undef CASE
}
static inline int md_entry_supported(int number) {
    return md_entry_route(number)!=MD_ENTRY_CALLER;
}
/* These operations finish through the ordinary C execution owner, without
 * replacing the application's stack or requiring a native syscall exit. */
static inline int md_entry_task_execution(int number,const uint64_t args[6]) {
    return number==SYS_uname || number==SYS_sethostname || number==SYS_setdomainname
        || md_identity_scalar_update(number)
        || number==SYS_shmget || number==SYS_shmctl
        || number==SYS_semget || number==SYS_semctl || number==SYS_msgget || number==SYS_msgctl
        || number==SYS_getcwd || number==SYS_mknodat || number==SYS_truncate
        || number==SYS_mount || number==SYS_umount2 || number==SYS_pivot_root
        || number==SYS_setxattrat || number==SYS_getxattrat || number==SYS_listxattrat || number==SYS_removexattrat
        || number==SYS_epoll_ctl
        || (number==SYS_epoll_pwait && !(int)args[3] && !args[4]);
}
#endif
