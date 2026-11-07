#ifndef MD_SECCOMP_INSTALL_H
#define MD_SECCOMP_INSTALL_H
#include <linux/seccomp.h>
#include <errno.h>
#include <stdint.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

static inline int md_seccomp_filter_call(long nr, uint64_t operation, uint64_t mode) {
    return (nr == SYS_seccomp && (unsigned)operation == SECCOMP_SET_MODE_FILTER)
        || (nr == SYS_prctl && (int)operation == PR_SET_SECCOMP && mode == SECCOMP_MODE_FILTER);
}
static inline unsigned md_seccomp_filter_flags(long nr, uint64_t flags) {
    return nr == SYS_seccomp ? (unsigned)flags : 0;
}
static inline int md_seccomp_listener_flags(unsigned flags) {
    const unsigned known=SECCOMP_FILTER_FLAG_TSYNC|SECCOMP_FILTER_FLAG_LOG
        |SECCOMP_FILTER_FLAG_SPEC_ALLOW|SECCOMP_FILTER_FLAG_NEW_LISTENER
        |SECCOMP_FILTER_FLAG_TSYNC_ESRCH|SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV;
    if ((flags&~known) || ((flags&SECCOMP_FILTER_FLAG_TSYNC)
            && !(flags&SECCOMP_FILTER_FLAG_TSYNC_ESRCH))) return -EINVAL;
    if (flags&SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV) return -EOPNOTSUPP;
    return 0;
}
static inline int md_seccomp_filter_committed(unsigned flags, long result) {
    /* Classify an admitted installation's kernel result, not an emulated
     * return from an earlier filter. A positive TSYNC failure is a TID, not a
     * listener. NEW_LISTENER+TSYNC requires TSYNC_ESRCH, so that combination
     * cannot return a failed TID. */
    return flags & SECCOMP_FILTER_FLAG_NEW_LISTENER ? result >= 0 : result == 0;
}
#endif
