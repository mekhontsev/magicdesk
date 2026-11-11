#ifndef MD_ENTRY_ROUTE_H
#define MD_ENTRY_ROUTE_H
#include "syscall_catalog.h"
#include "identity_call.h"
#include <sys/syscall.h>

enum md_entry_route { MD_ENTRY_WORKER, MD_ENTRY_TASK, MD_ENTRY_FD, MD_ENTRY_VALUE, MD_ENTRY_MEMORY,
    MD_ENTRY_READINESS };
/* Routes select optimizations, never admission. Every intercepted instruction
 * enters the common protocol; the operation owner requests kernel context if
 * its execution needs the original stack, signal mask or a native exit. */
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
#undef CASE
    return MD_ENTRY_TASK;
}
static inline int md_entry_supported(int number) {
    return (unsigned)number<512;
}
#endif
