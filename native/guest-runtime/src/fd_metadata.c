#define _GNU_SOURCE
#include "fd_metadata.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>

static void fd_path(int fd, char *path) {
    md_copy(path, 64, "/proc/self/fd/");
    md_decimal(path + 14, (unsigned)fd);
}
long md_fd_chmod(int fd, unsigned mode) {
    char path[64];
    fd_path(fd, path);
    return RAW3(fchmodat, AT_FDCWD, path, mode);
}
long md_fd_xattr(int fd, long operation, const unsigned long *arguments) {
    /* Follow the proc magic link, not a guest symlink's text. An O_PATH |
     * O_NOFOLLOW descriptor selects the symlink inode itself, even if dangling. */
    char path[64];
    fd_path(fd, path);
    switch (operation) {
    case SYS_lsetxattr: case SYS_fsetxattr:
        operation = SYS_setxattr;
        break;
    case SYS_lgetxattr: case SYS_fgetxattr:
        operation = SYS_getxattr;
        break;
    case SYS_llistxattr: case SYS_flistxattr:
        operation = SYS_listxattr;
        break;
    case SYS_lremovexattr: case SYS_fremovexattr:
        operation = SYS_removexattr;
        break;
    case SYS_setxattr:
    case SYS_getxattr:
    case SYS_listxattr:
    case SYS_removexattr:
        break;
    default:
        return -ENOSYS;
    }
    return md_raw(operation, (long)path, arguments[1], arguments[2], arguments[3], arguments[4], 0);
}
