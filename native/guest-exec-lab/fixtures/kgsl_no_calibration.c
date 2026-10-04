#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/ioctl.h>

int ioctl(int fd, unsigned long request, ...) {
    va_list args;
    va_start(args, request);
    void *arg = va_arg(args, void *);
    va_end(args);
    // KGSL _IOWR(0x09, 0x60, 32-byte calibrated timestamp request), test-only denial.
    if (request == 0xc0200960UL) {
        errno = ENOTTY;
        return -1;
    }
    int (*real)(int, unsigned long, ...) = dlsym(RTLD_NEXT, "ioctl");
    if (!real) {
        errno = ENOSYS;
        return -1;
    }
    return real(fd, request, arg);
}
