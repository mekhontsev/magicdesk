#define _GNU_SOURCE
#include "launch_identity.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

extern int md_app_uid_active;
extern long __wrap_md_raw(long, long, long, long, long, long, long);
extern long __wrap_syscall(long, long, long, long, long, long, long);
static long observed[7], returned;
static unsigned capabilities;
static char path[128];
static int native;
long __real_md_raw(long nr, long a, long b, long c, long d, long e, long f) {
    if (native) {
        long result = syscall(nr, a, b, c, d, e, f);
        return result < 0 ? -errno : result;
    }
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
static long access_raw(int fd, const char *name, int mode, int flags) {
    return __wrap_md_raw(SYS_faccessat2, fd, (long)name, mode, flags, 0, 0);
}
static void native_access(const char *root) {
    char directory[4096];
    assert(snprintf(directory, sizeof(directory), "%s/host-access-XXXXXX", root) < (int)sizeof(directory));
    assert(mkdtemp(directory));
    int dir = open(directory, O_PATH | O_DIRECTORY | O_CLOEXEC); assert(dir >= 0);
    int file = openat(dir, "file", O_CREAT | O_RDWR | O_CLOEXEC, 0600); assert(file >= 0);
    assert(!symlinkat("missing", dir, "dangling"));
    assert(!symlinkat("loop", dir, "loop"));
    assert(!symlinkat("file", dir, "link"));
    native = 1;
    const char *names[] = {"file", "dangling", "loop", "link", ".", "missing", "link/child"};
    for (unsigned i = 0; i < sizeof(names) / sizeof(*names); ++i) {
        for (int mode = 0; mode <= 7; ++mode) {
            long result = access_raw(dir, names[i], mode, AT_SYMLINK_NOFOLLOW);
            if (!mode) assert(result == (i == 5 ? -ENOENT : i == 6 ? -ENOTDIR : 0));
            assert(result == access_raw(dir, names[i], mode, AT_SYMLINK_NOFOLLOW | AT_EACCESS));
            if (getuid() == 2000 || getuid() == 0) {
                long expected = __real_md_raw(SYS_faccessat2, dir, (long)names[i], mode, AT_SYMLINK_NOFOLLOW, 0, 0);
                assert(result == expected);
            }
        }
    }
    assert(access_raw(dir, "dangling", F_OK, 0) == -ENOENT);
    assert(access_raw(dir, "loop", F_OK, 0) == -ELOOP);
    int pin = openat(dir, "dangling", O_PATH | O_NOFOLLOW | O_CLOEXEC); assert(pin >= 0);
    assert(!access_raw(pin, "", F_OK, AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW));
    assert(!access_raw(AT_FDCWD, "", F_OK, AT_EMPTY_PATH));
    for (unsigned i = 0; i < 20; ++i) assert(!access_raw(dir, "dangling", F_OK, AT_SYMLINK_NOFOLLOW));
    int next = dup(pin); assert(next == pin + 1); close(next);
    assert(!fchmod(file, 0));
    if (getuid()) assert(access_raw(dir, "file", R_OK, AT_SYMLINK_NOFOLLOW) == -EACCES);
    errno = 0;
    assert(__wrap_syscall(SYS_faccessat2, dir, (long)"missing", F_OK, AT_SYMLINK_NOFOLLOW, 0, 0) == -1);
    assert(errno == ENOENT);
    native = 0;
    close(pin); close(file);
    assert(!unlinkat(dir, "file", 0)); assert(!unlinkat(dir, "dangling", 0));
    assert(!unlinkat(dir, "loop", 0)); assert(!unlinkat(dir, "link", 0));
    close(dir); assert(!rmdir(directory));
}
int main(int argc, char **argv) {
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
    assert(__wrap_md_raw(SYS_faccessat2, 0, (long)"test", 1, 0x8000, 0, 0) == -EINVAL);
    assert(__wrap_md_raw(SYS_faccessat2, 0, (long)"test", 8, 256, 0, 0) == -EINVAL);
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
    if (argc > 1) native_access(argv[1]);
    md_app_uid_active=0; returned=123;
    assert(__wrap_syscall(SYS_openat2,1,2,3,4,5,6)==123 && observed[0]==SYS_openat2);
    assert(__wrap_md_raw(SYS_openat2,1,2,3,4,5,6)==123 && observed[0]==SYS_openat2);
    puts("PASS app-UID admission and scoped host access");
}
