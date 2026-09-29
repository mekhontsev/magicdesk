#define _GNU_SOURCE
#include "namespace.h"
#include "raw.h"
#include <fcntl.h>
#include <unistd.h>

long md_program_open(const struct md_fs *fs, const char *path, int executable) {
    long fd;
    if (fs->endpoint[0])
        fd = md_namespace_open(fs, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0);
    else {
        char host[PATH_MAX];
        long r = md_fs_resolve(fs, AT_FDCWD, path, MD_PATH_FOLLOW, host);
        if (r < 0)
            return r;
        fd = RAW4(openat, AT_FDCWD, host, O_RDONLY | O_CLOEXEC, 0);
    }
    if (fd < 0)
        return fd;
    if (executable) {
        long r = RAW4(faccessat2, fd, "", X_OK, AT_EMPTY_PATH | AT_EACCESS);
        if (r < 0) {
            RAW1(close, fd);
            return r;
        }
    }
    return fd;
}
long md_program_identity(const struct md_fs *fs, const char *path, char *out) {
    if (fs->endpoint[0])
        return md_namespace_identity(fs, path, out);
    char host[PATH_MAX];
    long r = md_fs_resolve(fs, AT_FDCWD, path, MD_PATH_FOLLOW, host);
    return r < 0 ? r : md_fs_guest(fs, host, out);
}
