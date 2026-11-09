#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <sys/syscall.h>
#include "linux_abi.h"
#include "host_policy.h"

extern long __real_md_raw(long, long, long, long, long, long, long);
extern long __real_syscall(long, ...);
extern int md_app_uid_active;

#ifndef MD_WRAP_LIBC
int md_app_uid_active;
int md_app_uid_admit(long uid, long euid, long gid, long egid) {
    if (uid < 10000 || uid % 100000 < 10000 || uid % 100000 > 19999 || uid != euid || gid != egid) return 0;
    struct __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
    struct __user_cap_data_struct caps[2] = {{0}};
    if (__real_md_raw(SYS_capget, (long)&header, (long)caps, 0, 0, 0, 0)) return 0;
    for (unsigned i = 0; i < 2; ++i)
        if (caps[i].effective || caps[i].permitted || caps[i].inheritable) return 0;
    md_app_uid_active = 1;
    return 1;
}
#endif

/* Only admitted equal-ID, capability-free hosts use this fallback.
 * Guest permission decisions still belong to the inode engine. */
static long access_compat(long dir, long path, long mode, long flags) {
    if (mode & ~7 || flags & ~(MD_AT_EACCESS | AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW)) return -EINVAL;
    if (!path) return -EFAULT;
    long pin = -1;
    if ((flags & AT_SYMLINK_NOFOLLOW) && *(const char *)path) {
        pin = __real_md_raw(SYS_openat, dir, path, O_PATH | O_NOFOLLOW | O_CLOEXEC, 0, 0, 0);
        if (pin < 0) return pin;
        /* The proc FD magic link retains the symlink itself, including a
         * dangling target. Keep kernel DAC/ACL/LSM checks on that exact object. */
        dir = pin; path = (long)""; flags |= AT_EMPTY_PATH;
    }
    char buffer[64];
    if ((flags & AT_EMPTY_PATH) && !*(const char *)path) {
        if (dir == AT_FDCWD) path = (long)".";
        else {
            if (dir < 0) return -EBADF;
            const char prefix[] = "/proc/self/fd/";
            unsigned n = 0;
            for (; prefix[n]; ++n) buffer[n] = prefix[n];
            char digits[24]; unsigned count = 0;
            unsigned long value = (unsigned long)dir;
            do { digits[count++] = '0' + value % 10; value /= 10; } while (value);
            while (count) buffer[n++] = digits[--count];
            buffer[n] = 0; path = (long)buffer; dir = AT_FDCWD;
        }
    }
    long result = __real_md_raw(SYS_faccessat, dir, path, mode, 0, 0, 0);
    if (pin >= 0) __real_md_raw(SYS_close, pin, 0, 0, 0, 0, 0);
    return result;
}

#ifdef MD_WRAP_LIBC
long __wrap_syscall(long nr, long a, long b, long c, long d, long e, long f) {
    if (md_app_uid_active && (nr == SYS_openat2 || nr == SYS_fchmodat2)) {
        errno = ENOSYS;
        return -1;
    }
    if (nr != SYS_faccessat2 || !md_app_uid_active)
        return __real_syscall(nr, a, b, c, d, e, f);
    long result = access_compat(a, b, c, d);
    if (result < 0) { errno = (int)-result; return -1; }
    return result;
}
#else
long __wrap_md_raw(long nr, long a, long b, long c, long d, long e, long f) {
    /* Report unavailable host primitives to their existing owners. Never
     * replace constrained openat2 with a weaker unconstrained openat. */
    if (md_app_uid_active && (nr == SYS_openat2 || nr == SYS_fchmodat2)) return -ENOSYS;
    return nr == SYS_faccessat2 && md_app_uid_active ? access_compat(a, b, c, d)
        : __real_md_raw(nr, a, b, c, d, e, f);
}
#endif
