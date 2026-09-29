#define _GNU_SOURCE
#include "namespace_internal.h"
#include "proc_paths.h"
#include "fs_rpc.h"
#include "fd_metadata.h"
#include "linux_abi.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <sys/sysmacros.h>

long md_namespace_request(const struct md_fs *fs, struct md_fs_request *q, struct md_fs_result *out) {
    int cwd = -1;
    unsigned bases = q->operation == MD_FS_LINK || q->operation == MD_FS_RENAME ? 2 : 1;
    for (unsigned i = 0; i < bases; ++i) {
        if (q->path[i] && q->path[i][0] == '/')
            q->directory[i] = -1;
        else if (q->directory[i] < 0 && q->directory[i] != AT_FDCWD)
            return -EBADF;
    }
    for (unsigned i = 0; i < bases; ++i) {
        if (q->directory[i] != AT_FDCWD)
            continue;
        if (cwd < 0) {
            long fd = RAW4(openat, AT_FDCWD, ".", O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
            if (fd < 0)
                return fd;
            cwd = (int)fd;
        }
        q->directory[i] = cwd;
    }
    long r = md_fs_call(fs->endpoint, 5000, q, out);
    if (cwd >= 0)
        RAW1(close, cwd);
    /* Never replay an unconfirmed request, including a read that advanced a cursor. */
    return r < 0 ? r : out->error;
}
long md_namespace_creation_mode(unsigned mode) {
    /* Read the kernel's current fs_struct mask without temporarily changing shared process state. */
    long fd = RAW4(openat, AT_FDCWD, "/proc/thread-self/status", O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0)
        return fd;
    char text[512];
    long n = RAW3(read, fd, text, sizeof(text) - 1);
    RAW1(close, fd);
    if (n < 0)
        return n;
    text[n] = 0;
    for (char *p = text; *p; ++p)
        if ((p == text || p[-1] == '\n') && md_prefix(p, "Umask:")) {
            p += 6;
            while (*p == ' ' || *p == '\t')
                ++p;
            unsigned mask = 0, digits = 0;
            while (*p >= '0' && *p <= '7') {
                if (++digits > 4)
                    return -EIO;
                mask = 8 * mask + (unsigned)(*p++ - '0');
            }
            if (!digits || *p != '\n' || mask > 0777)
                return -EIO;
            return (mode & 07777) & ~mask;
        }
    return -ENOTSUP;
}
long md_namespace_open(const struct md_fs *fs, int base, const char *path, int flags, unsigned mode) {
    if (md_host_path(path))
        return RAW4(openat, base, path, flags, mode);
    if ((flags & O_CREAT) && !(flags & O_PATH)) {
        long masked = md_namespace_creation_mode(mode);
        if (masked < 0)
            return masked;
        mode = (unsigned)masked;
    }
    struct md_fs_request q = {.operation = MD_FS_OPEN,
                              .directory = {base, -1},
                              .path = {path, NULL},
                              .flags = (uint32_t)flags,
                              .mode = mode};
    struct md_fs_result out;
    long r = md_namespace_request(fs, &q, &out);
    if (r < 0)
        return r;
    if (!(flags & O_CLOEXEC)) {
        r = RAW3(fcntl, out.fd, F_SETFD, 0);
        if (r < 0) {
            RAW1(close, out.fd);
            return r;
        }
    }
    return out.fd;
}
long md_namespace_identity(const struct md_fs *fs, const char *path, char *out) {
    if (path[0] == '/')
        return md_copy(out, PATH_MAX, path);
    struct md_fs_request q = {.operation = MD_FS_PATH, .directory = {AT_FDCWD, -1}};
    struct md_fs_result result;
    long r = md_namespace_request(fs, &q, &result);
    if (r < 0)
        return r;
    r = md_copy(out, PATH_MAX, result.data);
    if (!r)
        r = md_append(out, PATH_MAX, "/");
    return r ? r : md_append(out, PATH_MAX, path);
}
static void stat_info(const struct md_fs_info *i, struct stat *s) {
    memset(s, 0, sizeof(*s));
    s->st_dev = i->device;
    s->st_ino = i->inode;
    s->st_nlink = i->links;
    s->st_rdev = i->rdev;
    s->st_size = i->size;
    s->st_blocks = i->blocks;
    s->st_mode = i->mode;
    s->st_uid = i->uid;
    s->st_gid = i->gid;
    s->st_blksize = i->block_size;
    s->st_atim.tv_sec = i->access_seconds;
    s->st_atim.tv_nsec = i->access_nanos;
    s->st_mtim.tv_sec = i->modify_seconds;
    s->st_mtim.tv_nsec = i->modify_nanos;
    s->st_ctim.tv_sec = i->change_seconds;
    s->st_ctim.tv_nsec = i->change_nanos;
}
long md_namespace_inspect(const struct md_fs *fs, int fd, const char *path, int flags, struct md_fs_result *out) {
    if (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT))
        return -EINVAL;
    struct md_fs_request q = {.operation = path && *path ? MD_FS_STAT : MD_FS_FSTAT,
                              .directory = {fd, -1},
                              .path = {path, NULL},
                              .flags = (uint32_t)(flags & AT_SYMLINK_NOFOLLOW)};
    if (!path || !*path)
        q.flags = 0;
    if (path && !*path && !(flags & AT_EMPTY_PATH))
        return -ENOENT;
    return md_namespace_request(fs, &q, out);
}
static long descriptor_call(const struct md_fs *fs, long nr, const unsigned long *a) {
    if ((int)a[0] < 0)
        return -EBADF;
    struct md_fs_result out;
    struct stat st;
    long r = md_namespace_inspect(fs, (int)a[0], NULL, 0, &out);
    if (r == -EXDEV)
        return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    if (r < 0)
        return r;
    if (nr == SYS_fstat) {
        stat_info(&out.info, &st);
        return md_write_memory((void *)a[1], &st, sizeof(st));
    }
    if (!S_ISDIR(out.info.mode))
        return md_raw(nr, a[0], a[1], a[2], 0, 0, 0);
    struct md_fs_request q = {.operation = nr == SYS_lseek ? MD_FS_SEEKDIR : MD_FS_GETDENTS,
                              .directory = {(int)a[0], -1}};
    if (nr == SYS_lseek) {
        q.offset = (int64_t)a[1];
        q.flags = (uint32_t)a[2];
    } else
        q.capacity = a[2] > PATH_MAX ? PATH_MAX : (uint32_t)a[2];
    r = md_namespace_request(fs, &q, &out);
    if (r < 0)
        return r;
    if (nr == SYS_lseek)
        return out.position;
    r = md_write_memory((void *)a[1], out.data, out.size);
    return r < 0 ? r : out.size;
}
long md_namespace_xattr(const struct md_fs *fs, long nr, int base, const char *path, const unsigned long *a) {
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    char name[256];
    int default_acl = 0;
    if (nr == SYS_setxattr || nr == SYS_lsetxattr || nr == SYS_fsetxattr) {
        long r = md_read_string(name, sizeof(name), (const char *)a[1]);
        if (r < 0)
            return r == -ENAMETOOLONG ? -ERANGE : r;
        args[1] = (unsigned long)name;
        default_acl = md_equal(name, "system.posix_acl_default");
    }
    if (nr == SYS_fsetxattr) {
        if (default_acl) {
            if (base < 0)
                return -EBADF;
            struct md_fs_result out;
            long r = md_namespace_inspect(fs, base, NULL, 0, &out);
            if (!r)
                return -ENOTSUP;
            if (r != -EXDEV)
                return r;
        }
        return md_raw(nr, args[0], args[1], args[2], args[3], args[4], 0);
    }
    int nofollow =
        nr == SYS_lsetxattr || nr == SYS_lgetxattr || nr == SYS_llistxattr || nr == SYS_lremovexattr;
    long fd = md_namespace_open(fs, base, path, O_PATH | O_CLOEXEC | (nofollow ? O_NOFOLLOW : 0), 0);
    if (fd < 0)
        return fd;
    /* Object creation must implement virtual-parent ACL inheritance before a
     * default ACL may be installed, by either a path or descriptor operation. */
    long r = default_acl ? -ENOTSUP : md_fd_xattr((int)fd, nr, args);
    RAW1(close, fd);
    return r;
}
long md_namespace_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    if (nr == SYS_fstat || nr == SYS_getdents64 || nr == SYS_lseek)
        return descriptor_call(fs, nr, a);
    if (nr == SYS_fsetxattr)
        return md_namespace_xattr(fs, nr, (int)a[0], NULL, a);
    if (nr == SYS_utimensat && !a[1])
        return RAW4(utimensat, a[0], 0, a[2], a[3]);
    if (nr == SYS_getcwd) {
        struct md_fs_request q = {.operation = MD_FS_PATH, .directory = {AT_FDCWD, -1}};
        struct md_fs_result out;
        long r = md_namespace_request(fs, &q, &out);
        if (r < 0)
            return r;
        if (out.size > a[1])
            return -ERANGE;
        r = md_write_memory((void *)a[0], out.data, out.size);
        return r < 0 ? r : out.size;
    }
    char first[PATH_MAX];
    int base = (int)a[0];
    unsigned path_index = 1;
    uintptr_t pointer = a[1];
    switch (nr) {
    case SYS_inotify_add_watch:
        base = AT_FDCWD;
        break;
    case SYS_chdir:
    case SYS_truncate:
    case SYS_statfs:
    case SYS_setxattr:
    case SYS_lsetxattr:
    case SYS_getxattr:
    case SYS_lgetxattr:
    case SYS_listxattr:
    case SYS_llistxattr:
    case SYS_removexattr:
    case SYS_lremovexattr:
        pointer = a[0];
        path_index = 0;
        base = AT_FDCWD;
        break;
    case SYS_symlinkat:
        pointer = a[2];
        path_index = 2;
        base = (int)a[1];
        break;
    }
    long r = md_read_string(first, sizeof(first), (const char *)pointer);
    if (r < 0)
        return r;
    r = md_namespace_relative_mount(base, first);
    if (r < 0)
        return r;
    /* Explicit host mappings only. Symlinks crossing these mounts are not implemented. */
    if (md_host_path(first))
        return md_namespace_host_call(fs, exe, nr, a, path_index, first);
    return md_namespace_path_call(fs, nr, a, base, first);
}
static long path_metadata(const struct md_fs *, long, const unsigned long *, int, const char *);
long md_namespace_path_call(const struct md_fs *fs, long nr, const unsigned long *a,
                            int base, const char *first) {
    switch (nr) {
    case SYS_openat:
        return md_namespace_open(fs, base, first, (int)a[2], (unsigned)a[3]);
    case SYS_mknodat: {
        unsigned mode = (unsigned)a[2];
        if ((mode & S_IFMT) && (mode & S_IFMT) != S_IFREG)
            return -ENOTSUP;
        long fd = md_namespace_open(fs, base, first, O_RDONLY | O_CREAT | O_EXCL | O_CLOEXEC,
                                    mode & ~S_IFMT);
        if (fd < 0)
            return fd;
        RAW1(close, fd);
        return 0;
    }
    case SYS_chdir: {
        long fd = md_namespace_open(fs, base, first, O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
        if (fd < 0)
            return fd;
        long r = RAW1(fchdir, fd);
        RAW1(close, fd);
        return r;
    }
    default: return path_metadata(fs, nr, a, base, first);
    }
}
/* Keep metadata payloads off posix_spawn's small open/chdir file-action stack. */
__attribute__((noinline)) static long path_metadata(const struct md_fs *fs, long nr,
                                                    const unsigned long *a, int base, const char *first) {
    char second[PATH_MAX];
    long r;
    struct md_fs_request q = {.directory = {base, -1}, .path = {first, NULL}};
    struct md_fs_result out;
    switch (nr) {
    case SYS_inotify_add_watch: {
        long fd = md_namespace_open(fs, base, first, O_PATH | O_CLOEXEC |
            ((a[2] & IN_DONT_FOLLOW) ? O_NOFOLLOW : 0), 0);
        if (fd < 0) return fd;
        struct md_fs_result identity;
        r = md_namespace_inspect(fs, (int)fd, NULL, 0, &identity);
        // Directory entries live in the namespace, not in its backing directory.
        // Do not advertise a watch which would silently miss logical changes.
        if (!r && S_ISDIR(identity.info.mode)) r = -ENOTSUP;
        if (!r) {
            char path[64] = "/proc/thread-self/fd/";
            md_decimal(path + md_length(path), (unsigned)fd);
            r = RAW3(inotify_add_watch, a[0], path, a[2] & ~IN_DONT_FOLLOW);
        }
        RAW1(close, fd);
        return r;
    }
    case SYS_newfstatat:
    case SYS_statx: {
        int flags = nr == SYS_statx ? (int)a[2] : (int)a[3];
        if (nr == SYS_statx &&
            (((flags & AT_STATX_SYNC_TYPE) == AT_STATX_SYNC_TYPE) || (a[3] & STATX__RESERVED)))
            return -EINVAL;
        if (nr == SYS_statx)
            flags &= ~(AT_STATX_FORCE_SYNC | AT_STATX_DONT_SYNC);
        r = md_namespace_inspect(fs, base, first, flags, &out);
        if (r == -EXDEV && !*first && (flags & AT_EMPTY_PATH))
            return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
        if (r < 0)
            return r;
        if (nr == SYS_newfstatat) {
            struct stat st;
            stat_info(&out.info, &st);
            return md_write_memory((void *)a[2], &st, sizeof(st));
        }
        struct statx st = {0};
        st.stx_mask = STATX_BASIC_STATS;
        st.stx_blksize = out.info.block_size;
        st.stx_nlink = (uint32_t)out.info.links;
        st.stx_uid = out.info.uid;
        st.stx_gid = out.info.gid;
        st.stx_mode = (uint16_t)out.info.mode;
        st.stx_ino = out.info.inode;
        st.stx_size = out.info.size;
        st.stx_blocks = out.info.blocks;
        st.stx_atime.tv_sec = out.info.access_seconds;
        st.stx_atime.tv_nsec = out.info.access_nanos;
        st.stx_mtime.tv_sec = out.info.modify_seconds;
        st.stx_mtime.tv_nsec = out.info.modify_nanos;
        st.stx_ctime.tv_sec = out.info.change_seconds;
        st.stx_ctime.tv_nsec = out.info.change_nanos;
        st.stx_dev_major = major(out.info.device);
        st.stx_dev_minor = minor(out.info.device);
        st.stx_rdev_major = major(out.info.rdev);
        st.stx_rdev_minor = minor(out.info.rdev);
        return md_write_memory((void *)a[4], &st, sizeof(st));
    }
    case SYS_mkdirat:
        r = md_namespace_creation_mode((unsigned)a[2]);
        if (r < 0)
            return r;
        q.operation = MD_FS_MKDIR;
        q.mode = (uint32_t)r;
        break;
    case SYS_unlinkat:
        q.operation = MD_FS_UNLINK;
        q.flags = (uint32_t)a[2];
        break;
    case SYS_readlinkat:
        if (!a[3])
            return -EINVAL;
        q.operation = MD_FS_READLINK;
        r = md_namespace_request(fs, &q, &out);
        if (r < 0)
            return r;
        if (out.size > a[3])
            out.size = (uint32_t)a[3];
        r = md_write_memory((void *)a[2], out.data, out.size);
        return r < 0 ? r : out.size;
    case SYS_symlinkat:
        r = md_read_string(second, sizeof(second), (const char *)a[0]);
        if (r < 0)
            return r;
        q.operation = MD_FS_SYMLINK;
        q.path[1] = second;
        break;
    case SYS_renameat:
    case SYS_renameat2:
    case SYS_linkat:
        r = md_read_string(second, sizeof(second), (const char *)a[3]);
        if (r < 0)
            return r;
        r = md_namespace_relative_mount((int)a[2], second);
        if (r < 0)
            return r;
        if (md_host_path(second))
            return -EXDEV;
        q.operation = nr == SYS_linkat ? MD_FS_LINK : MD_FS_RENAME;
        q.directory[1] = (int)a[2];
        q.path[1] = second;
        q.flags = nr == SYS_renameat ? 0 : (uint32_t)a[4];
        break;
    case SYS_faccessat:
    case SYS_faccessat2: {
        int flags = nr == SYS_faccessat2 ? (int)a[3] : 0;
        if (flags & ~(MD_AT_EACCESS | AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH))
            return -EINVAL;
        if (!*first && (flags & AT_EMPTY_PATH))
            return RAW4(faccessat2, base, "", a[2], flags);
        long fd = md_namespace_open(fs, base, first,
                                    O_PATH | O_CLOEXEC | ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0), 0);
        if (fd < 0)
            return fd;
        r = RAW4(faccessat2, fd, "", a[2], flags | AT_EMPTY_PATH);
        RAW1(close, fd);
        return r;
    }
    case SYS_setxattr:
    case SYS_lsetxattr:
    case SYS_getxattr:
    case SYS_lgetxattr:
    case SYS_listxattr:
    case SYS_llistxattr:
    case SYS_removexattr:
    case SYS_lremovexattr:
        return md_namespace_xattr(fs, nr, base, first, a);
    case SYS_fchmodat:
    case SYS_fchmodat2:
    case SYS_fchownat:
    case SYS_utimensat:
    case SYS_truncate:
    case SYS_statfs: {
        int flags = nr == SYS_fchownat ? (int)a[4] : nr == SYS_utimensat || nr == SYS_fchmodat2 ? (int)a[3] : 0;
        if (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH))
            return -EINVAL;
        int borrowed = !*first && (flags & AT_EMPTY_PATH);
        long fd = borrowed ? base
                           : md_namespace_open(fs, base, first,
                                               (nr == SYS_truncate ? O_WRONLY : O_PATH) | O_CLOEXEC |
                                                   ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0),
                                               0);
        if (borrowed && base == AT_FDCWD) {
            fd = RAW4(openat, AT_FDCWD, ".", O_PATH | O_CLOEXEC, 0);
            borrowed = 0;
        } else if (borrowed && base < 0)
            return -EBADF;
        if (fd < 0)
            return fd;
        switch (nr) {
        case SYS_fchmodat: {
            r = md_fd_chmod((int)fd, (unsigned)a[2]);
            break;
        }
        case SYS_fchmodat2:
            r = RAW4(fchmodat2, fd, "", a[2], flags | AT_EMPTY_PATH);
            break;
        case SYS_fchownat:
            r = RAW5(fchownat, fd, "", a[2], a[3], flags | AT_EMPTY_PATH);
            break;
        case SYS_utimensat:
            r = RAW4(utimensat, fd, "", a[2], flags | AT_EMPTY_PATH);
            break;
        case SYS_truncate:
            r = RAW2(ftruncate, fd, a[1]);
            break;
        default:
            r = RAW2(fstatfs, fd, a[1]);
            break;
        }
        if (!borrowed)
            RAW1(close, fd);
        return r;
    }
    default:
        return -ENOTSUP;
    }
    return md_namespace_request(fs, &q, &out);
}
