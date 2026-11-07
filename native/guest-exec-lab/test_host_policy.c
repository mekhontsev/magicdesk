#define _GNU_SOURCE
#include "launch_identity.h"
#include <assert.h>
#include <errno.h>
#include <linux/capability.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <sys/syscall.h>

extern int md_app_uid_active;
extern long __wrap_md_raw(long, long, long, long, long, long, long);
extern long __wrap_syscall(long, long, long, long, long, long, long);
static long observed[7], returned;
static unsigned capabilities;
static char path[128];
long __real_md_raw(long nr, long a, long b, long c, long d, long e, long f) {
    if (nr == SYS_capget) {
        struct __user_cap_data_struct *caps = (void *)b;
        caps[0].effective = capabilities;
        return 0;
    }
    long args[] = {nr, a, b, c, d, e, f};
    memcpy(observed, args, sizeof(args));
    if (nr == SYS_faccessat) snprintf(path, sizeof(path), "%s", (char *)b);
    return returned;
}
long __real_syscall(long nr, ...) {
    va_list args; va_start(args,nr);
    long a[6]; for (unsigned i=0;i<6;++i) a[i]=va_arg(args,long);
    va_end(args);
    long result=__real_md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    if (result<0) { errno=(int)-result; return -1; }
    return result;
}
int main(void) {
    assert(md_launch_identity(0, 0, 0, 0));
    assert(md_launch_identity(2000, 2000, 2000, 2000));
    assert(!md_app_uid_active);
    assert(!md_launch_identity(1000, 1000, 1000, 1000));
    assert(!md_launch_identity(10123, 0, 10123, 10123));
    assert(!md_launch_identity(10123, 10123, 10123, 0));
    capabilities = 1;
    assert(!md_launch_identity(10123, 10123, 10123, 10123));
    capabilities = 0;
    returned = -EACCES;
    assert(__wrap_md_raw(SYS_faccessat2, -100, (long)"test", 1, 512, 0, 0) == -EACCES);
    assert(observed[0] == SYS_faccessat2);
    assert(md_launch_identity(10123, 10123, 10123, 10123));
    assert(md_app_uid_active);
    observed[0] = -1;
    assert(__wrap_md_raw(SYS_openat2, -100, (long)"test", 0, 0, 0, 0) == -ENOSYS);
    assert(__wrap_md_raw(SYS_fchmodat2, -100, (long)"test", 0600, 0, 0, 0) == -ENOSYS);
    assert(observed[0] == -1);
    returned = 0;
    assert(!__wrap_md_raw(SYS_faccessat2, -100, (long)"test", 1, 512, 0, 0));
    assert(observed[0] == SYS_faccessat && observed[1] == -100 && !strcmp(path, "test"));
    assert(!__wrap_md_raw(SYS_faccessat2, 123, (long)"", 1, 4096 | 512, 0, 0));
    assert(observed[1] == -100 && !strcmp(path, "/proc/self/fd/123"));
    assert(__wrap_md_raw(SYS_faccessat2, -1, (long)"", 1, 4096, 0, 0) == -EBADF);
    assert(__wrap_md_raw(SYS_faccessat2, 0, 0, 1, 0, 0, 0) == -EFAULT);
    assert(__wrap_md_raw(SYS_faccessat2, 0, (long)"test", 1, 256, 0, 0) == -EOPNOTSUPP);
    returned = 123;
    assert(__wrap_md_raw(SYS_getpid, 1, 2, 3, 4, 5, 6) == 123);
    assert(observed[0] == SYS_getpid && observed[6] == 6);
    observed[0]=-1;
    assert(__wrap_syscall(SYS_openat2,-100,(long)"test",0,0,0,0)==-1 && errno==ENOSYS);
    assert(__wrap_syscall(SYS_fchmodat2,-100,(long)"test",0,0,0,0)==-1 && errno==ENOSYS);
    assert(observed[0]==-1);
    returned=-EACCES;
    assert(__wrap_syscall(SYS_faccessat2,-100,(long)"test",1,512,0,0)==-1 && errno==EACCES);
    assert(observed[0]==SYS_faccessat);
    md_app_uid_active=0; returned=123;
    assert(__wrap_syscall(SYS_openat2,1,2,3,4,5,6)==123 && observed[0]==SYS_openat2);
    assert(__wrap_md_raw(SYS_openat2,1,2,3,4,5,6)==123 && observed[0]==SYS_openat2);
    puts("PASS app-UID admission and scoped host access");
}
