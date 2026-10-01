#define _GNU_SOURCE
#include "namespace_internal.h"
#include "proc_paths.h"
#include "fd_metadata.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>

long md_namespace_relative_mount(int base, char *path) {
    if (!*path || *path == '/') return 0;
    char host[PATH_MAX];
    long n;
    if (base == AT_FDCWD) {
        n = RAW2(getcwd, host, sizeof(host));
        if (n < 0) return n;
    } else {
        if (base < 0) return -EBADF;
        char link[64] = "/proc/thread-self/fd/";
        md_decimal(link + md_length(link), (unsigned)base);
        n = RAW4(readlinkat, AT_FDCWD, link, host, sizeof(host) - 1);
        if (n < 0) return n;
        if (n == sizeof(host) - 1) return -ENAMETOOLONG;
        host[n] = 0;
    }
    if (!md_host_path(host)) return 0;
    struct stat info;
    n = base == AT_FDCWD ? RAW4(newfstatat, base, ".", &info, 0) : RAW2(fstat, base, &info);
    if (n < 0) return n;
    if (!S_ISDIR(info.st_mode)) return -ENOTDIR;
    n = md_append(host, sizeof(host), "/");
    if (!n) n = md_append(host, sizeof(host), path);
    return n ? n : md_copy(path, PATH_MAX, host);
}

static long link_result(const char *text, const unsigned long *a) {
    if (!a[3]) return -EINVAL;
    size_t n = md_length(text);
    if (n > a[3]) n = a[3];
    long r = md_write_memory((void *)a[2], text, n);
    return r < 0 ? r : (long)n;
}
static int link_operation(long nr, const unsigned long *a) {
    switch (nr) {
    case SYS_openat: return (a[2] & O_NOFOLLOW) || ((a[2] & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL));
    case SYS_newfstatat: case SYS_faccessat2: return a[3] & AT_SYMLINK_NOFOLLOW;
    case SYS_statx: return a[2] & AT_SYMLINK_NOFOLLOW;
    case SYS_fchownat: return a[4] & AT_SYMLINK_NOFOLLOW;
    case SYS_utimensat: return a[3] & AT_SYMLINK_NOFOLLOW;
    case SYS_lsetxattr: case SYS_lgetxattr: case SYS_llistxattr: case SYS_lremovexattr:
    case SYS_mkdirat: case SYS_unlinkat: case SYS_mknodat: case SYS_symlinkat:
        return 1;
    default: return 0;
    }
}
static long host_readlink(const struct md_fs *fs, const char *path, const unsigned long *a) {
    if (!a[3]) return -EINVAL;
    char text[PATH_MAX];
    long n = RAW4(readlinkat, AT_FDCWD, path, text, sizeof(text) - 1);
    if (n < 0) return n;
    text[n] = 0;
    long fd = RAW4(openat, AT_FDCWD, path, O_PATH | O_CLOEXEC, 0);
    if (fd >= 0) {
        struct md_fs_response out;
        long r = md_namespace_inspect(fs, (int)fd, NULL, 0, &out);
        if (!r) {
            /* A directory has one virtual parent. A file FD has inode identity,
             * but no retained virtual dentry: do not invent a hard-link name. */
            if (!S_ISDIR(out.result.info.mode)) r = -ENOTSUP;
            else {
                struct md_fs_request q = {.operation = MD_FS_PATH, .directory = {(int)fd, -1}};
                r = md_namespace_request(fs, &q, &out);
                if (!r) r = md_copy(text, sizeof(text), out.data);
            }
        }
        RAW1(close, fd);
        if (r < 0 && r != -EXDEV) return r;
    }
    return link_result(text, a);
}
long md_namespace_host_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a,
                            unsigned path_index, const char *path) {
    if (nr == SYS_renameat || nr == SYS_renameat2 || nr == SYS_linkat) return -ENOTSUP;
    struct md_proc_path ref = md_proc_path(path);
    if (ref.kind == MD_PROC_FOREIGN) return -ENOTSUP;
    if (ref.kind == MD_PROC_CMDLINE || ref.kind == MD_PROC_AUXV) {
        if (*ref.tail) return -ENOTDIR;
        if (nr == SYS_openat) return md_proc_image_open(fs, ref.kind, (int)a[2]);
        ref.kind = MD_PROC_NONE;
    }
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    args[path_index] = (unsigned long)path;
    int tail = ref.kind && *ref.tail;
    int native = !ref.kind || (!tail && link_operation(nr, a));
    if (nr == SYS_readlinkat && !tail) {
        if (ref.kind == MD_PROC_EXE) return link_result(exe, a);
        if (ref.kind == MD_PROC_ROOT) return link_result("/", a);
        if (ref.ordinary_link) return RAW4(readlinkat, AT_FDCWD, path, a[2], a[3]);
        return host_readlink(fs, path, a);
    }
    if (native && nr != SYS_newfstatat && nr != SYS_statx)
        return md_raw(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
    if (!tail && nr == SYS_openat) {
        if (ref.kind == MD_PROC_EXE) return md_proc_executable_open(fs, exe, (int)a[2]);
        if (ref.kind == MD_PROC_ROOT)
            return md_namespace_open(fs, AT_FDCWD, "/",
                                     (int)a[2], (unsigned)a[3]);
        long original = RAW4(openat, AT_FDCWD, path, O_PATH | O_CLOEXEC, 0);
        if (original < 0) return original;
        long reopened = md_namespace_reopen(fs, (int)original, (int)a[2], 0);
        RAW1(close, original);
        return reopened == -EXDEV ? RAW4(openat, AT_FDCWD, path, a[2], a[3]) : reopened;
    }
    char anchor[PATH_MAX];
    size_t length = tail ? ref.anchor_length : md_length(path);
    memcpy(anchor, path, length); anchor[length] = 0;
    long fd;
    if (!native && ref.kind == MD_PROC_EXE)
        fd = md_proc_executable_open(fs, exe, O_PATH | O_CLOEXEC | (tail ? O_DIRECTORY : 0));
    else if (!native && ref.kind == MD_PROC_ROOT)
        fd = md_namespace_open(fs, AT_FDCWD, "/",
                               O_PATH | O_CLOEXEC | (tail ? O_DIRECTORY : 0), 0);
    else
        fd = RAW4(openat, AT_FDCWD, anchor, O_PATH | O_CLOEXEC | (tail ? O_DIRECTORY : 0) |
                  (native && link_operation(nr, a) ? O_NOFOLLOW : 0), 0);
    if (fd < 0) return fd;
    if (!tail && (nr == SYS_fchmodat || nr == SYS_fchmodat2 || nr == SYS_fchownat || nr == SYS_utimensat)) {
        long next = md_namespace_mutable(fs, (int)fd);
        RAW1(close, fd); fd = next;
        if (fd < 0) return fd;
    }
    long r;
    if (tail) {
        const char *relative = ref.tail;
        while (*relative == '/') ++relative;
        if (!*relative) relative = ".";
        struct md_fs_response out;
        r = md_namespace_inspect(fs, (int)fd, NULL, 0, &out);
        if (!r) r = md_namespace_path_call(fs, nr, args, (int)fd, relative);
        else if (r == -EXDEV) {
            md_copy(anchor, sizeof(anchor), "/proc/thread-self/fd/");
            md_decimal(anchor + md_length(anchor), (unsigned)fd);
            r = md_append(anchor, sizeof(anchor), "/");
            if (!r) r = md_append(anchor, sizeof(anchor), relative);
            if (!r) {
                args[path_index] = (unsigned long)anchor;
                r = md_raw(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
            }
        }
    } else switch (nr) {
    case SYS_newfstatat: case SYS_statx:
        args[0] = (unsigned long)fd; args[1] = (unsigned long)"";
        args[nr == SYS_statx ? 2 : 3] |= AT_EMPTY_PATH;
        r = md_namespace_path_call(fs, nr, args, (int)fd, "");
        break;
    case SYS_chdir: r = RAW1(fchdir, fd); break;
    case SYS_faccessat: case SYS_faccessat2:
        r = RAW4(faccessat2, fd, "", a[2], (nr == SYS_faccessat2 ? a[3] : 0) | AT_EMPTY_PATH);
        break;
    case SYS_fchmodat: r = md_fd_chmod((int)fd, (unsigned)a[2]); break;
    case SYS_fchmodat2: r = RAW4(fchmodat2, fd, "", a[2], a[3] | AT_EMPTY_PATH); break;
    case SYS_fchownat: r = RAW5(fchownat, fd, "", a[2], a[3], a[4] | AT_EMPTY_PATH); break;
    case SYS_utimensat: r = RAW4(utimensat, fd, "", a[2], a[3] | AT_EMPTY_PATH); break;
    case SYS_statfs: r = RAW2(fstatfs, fd, a[1]); break;
    case SYS_setxattr: case SYS_getxattr: case SYS_listxattr: case SYS_removexattr: {
        struct md_fs_response out;
        r = md_namespace_inspect(fs, (int)fd, NULL, 0, &out);
        if (r == -EXDEV) r = md_raw(nr, args[0], args[1], args[2], args[3], args[4], 0);
        else if (!r) {
            md_copy(anchor, sizeof(anchor), "/proc/thread-self/fd/");
            md_decimal(anchor + md_length(anchor), (unsigned)fd);
            r = md_namespace_xattr(fs, nr, AT_FDCWD, anchor, args);
        }
        break;
    }
    case SYS_truncate: {
        md_copy(anchor, sizeof(anchor), "/proc/thread-self/fd/");
        md_decimal(anchor + md_length(anchor), (unsigned)fd);
        long writefd = md_namespace_reopen(fs, (int)fd, O_WRONLY | O_CLOEXEC, 1);
        if (writefd == -EXDEV) writefd = RAW4(openat, AT_FDCWD, anchor, O_WRONLY | O_CLOEXEC, 0);
        r = writefd < 0 ? writefd : RAW2(ftruncate, writefd, a[1]);
        if (writefd >= 0) RAW1(close, writefd);
        break;
    }
    default: r = -ENOTSUP;
    }
    RAW1(close, fd);
    return r;
}
