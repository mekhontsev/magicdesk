#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <string.h>
#include <sys/types.h>

static int open_heap_control(const char *symbol, const char *path, int flags, mode_t mode) {
    if (path && strcmp(path, "/dev/dma_heap/system") == 0) {
        errno = EACCES;
        return -1;
    }
    int (*next)(const char *, int, ...) = dlsym(RTLD_NEXT, symbol);
    if (!next) {
        errno = ENOSYS;
        return -1;
    }
    return next(path, flags, mode);
}

#define OPEN_WRAPPER(name)                                                               \
    int name(const char *path, int flags, ...) {                                          \
        mode_t mode = 0;                                                                \
        if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) {                       \
            va_list arguments;                                                         \
            va_start(arguments, flags);                                                \
            mode = va_arg(arguments, mode_t);                                          \
            va_end(arguments);                                                         \
        }                                                                              \
        return open_heap_control(#name, path, flags, mode);                             \
    }

OPEN_WRAPPER(open)
OPEN_WRAPPER(open64)
