#define _GNU_SOURCE
#include "file_calls.h"
#include "namespace.h"
#include "proc_paths.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/inotify.h>
#include "namespace_internal.h"

static long open_extended(const struct md_fs *fs, const char *exe, const unsigned long *a) {
    struct open_how how;
    if (a[3] < sizeof(how)) return -EINVAL;
    if (a[3] > md_page_size) return -E2BIG;
    long r = md_read_memory(&how, (void *)a[2], sizeof(how));
    if (r < 0) return r;
    for (size_t i = sizeof(how); i < a[3];) {
        unsigned char bytes[64];
        size_t n = a[3] - i;
        if (n > sizeof(bytes)) n = sizeof(bytes);
        r = md_read_memory(bytes, (void *)(a[2] + i), n);
        if (r < 0) return r;
        for (size_t j = 0; j < n; ++j) if (bytes[j]) return -E2BIG;
        i += n;
    }
    const uint64_t allowed = O_ACCMODE | O_CREAT | O_EXCL | O_NOCTTY | O_TRUNC | O_APPEND |
        O_NONBLOCK | O_DSYNC | O_ASYNC | O_DIRECT | O_LARGEFILE | O_DIRECTORY | O_NOFOLLOW |
        O_NOATIME | O_CLOEXEC | O_SYNC | O_PATH | O_TMPFILE;
    if ((how.flags & ~allowed) || (how.mode & ~07777UL)) return -EINVAL;
    if (how.mode && !(how.flags & O_CREAT) && (how.flags & O_TMPFILE) != O_TMPFILE) return -EINVAL;
    if ((how.flags & O_PATH) && (how.flags & ~(O_PATH | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW)))
        return -EINVAL;
    if ((how.flags & (O_CREAT | O_DIRECTORY)) == (O_CREAT | O_DIRECTORY)) return -EINVAL;
    if ((how.flags & O_TMPFILE) == O_TMPFILE && !(how.flags & (O_WRONLY | O_RDWR))) return -EINVAL;
    if (how.resolve & ~(RESOLVE_BENEATH | RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS |
            RESOLVE_NO_MAGICLINKS | RESOLVE_NO_XDEV | RESOLVE_CACHED)) return -EINVAL;
    if ((how.resolve & (RESOLVE_IN_ROOT | RESOLVE_BENEATH)) == (RESOLVE_IN_ROOT | RESOLVE_BENEATH))
        return -EINVAL;
    if (!how.resolve) {
        unsigned long args[6] = {a[0], a[1], how.flags, how.mode};
        return md_file_call(fs, exe, SYS_openat, args);
    }
    char path[PATH_MAX];
    r = md_read_string(path, sizeof(path), (const char *)a[1]);
    if (r < 0) return r;
    if (!*path) return -ENOENT;
    if (*path == '/' && (how.resolve & RESOLVE_BENEATH)) return -EXDEV;
    if (how.resolve & RESOLVE_CACHED) return -EAGAIN;
    if (!fs->endpoint[0]) return -ENOTSUP;
    /* Host mount paths keep the kernel's walk. Never apply IN_ROOT against
     * Android's root when the supplied descriptor belongs to the guest. */
    char mounted[PATH_MAX];
    md_copy(mounted, sizeof(mounted), path);
    r = md_namespace_relative_mount((int)a[0], mounted);
    if (r < 0) return r;
    if (md_host_path(mounted) && !(how.resolve & RESOLVE_IN_ROOT)) {
        struct md_proc_path ref = md_proc_path(mounted);
        if (ref.kind == MD_PROC_FOREIGN) return -ENOTSUP;
        if (ref.kind == MD_PROC_CMDLINE || ref.kind == MD_PROC_AUXV) {
            /* Let the real proc walk validate relative scoping/symlinks before
             * supplying guest image bytes. These entries are not magic links. */
            long checked = RAW4(openat2, a[0], path, &how, sizeof(how));
            if (checked < 0) return checked;
            RAW1(close, checked);
            return *ref.tail ? -ENOTDIR : md_proc_image_open(fs, ref.kind, (int)how.flags);
        }
        if (ref.kind && (how.resolve & (RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS))) {
            if ((how.flags & (O_PATH | O_NOFOLLOW)) == (O_PATH | O_NOFOLLOW) && !*ref.tail)
                return RAW4(openat2, a[0], path, &how, sizeof(how));
            return -ELOOP;
        }
        if (ref.kind == MD_PROC_EXE || ref.kind == MD_PROC_ROOT) return -ENOTSUP;
        return RAW4(openat2, a[0], path, &how, sizeof(how));
    }
    return md_namespace_open_resolved(fs, (int)a[0], path, &how);
}

static long path_at(const struct md_fs *fs, const char *exe, int fd,
        uintptr_t pointer, int follow, int empty, char *out) {
    char path[PATH_MAX];
    long r = md_read_string(path, sizeof(path), (const char *)pointer);
    if (r < 0) return r;
    if (!path[0] && empty) { out[0] = 0; return 0; }
    struct md_proc_path ref = md_proc_path(path);
    if (ref.kind == MD_PROC_EXE && follow == MD_PATH_FOLLOW) {
        if (*ref.tail) return -ENOTDIR;
        md_copy(path, sizeof(path), exe);
    }
    return md_fs_resolve(fs, fd, path, follow, out);
}

long md_file_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    if (nr == SYS_openat2) return open_extended(fs, exe, a);
    if (fs->endpoint[0]) return md_namespace_call(fs,exe,nr,a);
    char first[PATH_MAX], second[PATH_MAX];
    long r;
    int flags;
    switch (nr) {
    case SYS_fstat: case SYS_getdents64: case SYS_lseek: case SYS_fsetxattr:
    case SYS_fchmod: case SYS_fchown: case SYS_fremovexattr:
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    case SYS_openat:
        flags = (int)a[2];
        r = path_at(fs, exe, (int)a[0], a[1], (flags & O_CREAT) && (flags & O_EXCL)
                ? MD_PATH_ENTRY : !(flags & O_NOFOLLOW), 0, first);
        if (r < 0) return r;
        struct md_proc_path image = md_proc_path(first);
        if (image.kind == MD_PROC_CMDLINE || image.kind == MD_PROC_AUXV)
            return *image.tail ? -ENOTDIR : md_proc_image_open(fs, image.kind, flags);
        return RAW4(openat, AT_FDCWD, first, flags, a[3]);
    case SYS_newfstatat:
    case SYS_statx:
    case SYS_faccessat:
    case SYS_faccessat2:
    case SYS_fchmodat2:
        flags = nr == SYS_statx ? (int)a[2] : nr == SYS_faccessat ? 0 : (int)a[3];
        r = path_at(fs, exe, (int)a[0], a[1], !(flags & AT_SYMLINK_NOFOLLOW), flags & AT_EMPTY_PATH, first);
        return r < 0 ? r : md_raw(nr, first[0] ? AT_FDCWD : a[0], (long)first, a[2], a[3], a[4], 0);
    case SYS_mkdirat:
    case SYS_unlinkat:
    case SYS_mknodat:
        r = path_at(fs, exe, (int)a[0], a[1], MD_PATH_ENTRY, 0, first);
        return r < 0 ? r : md_raw(nr, AT_FDCWD, (long)first, a[2], a[3], 0, 0);
    case SYS_fchmodat:
        r = path_at(fs, exe, (int)a[0], a[1], 1, 0, first);
        return r < 0 ? r : RAW3(fchmodat, AT_FDCWD, first, a[2]);
    case SYS_fchownat:
        flags = (int)a[4];
        r = path_at(fs, exe, (int)a[0], a[1], !(flags & AT_SYMLINK_NOFOLLOW), flags & AT_EMPTY_PATH, first);
        return r < 0 ? r : RAW5(fchownat, first[0] ? AT_FDCWD : a[0], first, a[2], a[3], flags);
    case SYS_utimensat:
        // Linux uses a null pathname for futimens on the existing descriptor.
        if (!a[1]) return RAW4(utimensat, a[0], 0, a[2], a[3]);
        flags = (int)a[3];
        r = path_at(fs, exe, (int)a[0], a[1], !(flags & AT_SYMLINK_NOFOLLOW), flags & AT_EMPTY_PATH, first);
        return r < 0 ? r : RAW4(utimensat, first[0] ? AT_FDCWD : a[0], first, a[2], flags);
    case SYS_symlinkat:
        r = path_at(fs, exe, (int)a[1], a[2], MD_PATH_ENTRY, 0, first);
        return r < 0 ? r : RAW3(symlinkat, a[0], AT_FDCWD, first);
    case SYS_renameat:
    case SYS_renameat2:
    case SYS_linkat:
        r = path_at(fs, exe, (int)a[0], a[1], nr == SYS_linkat && (a[4] & AT_SYMLINK_FOLLOW)
                ? MD_PATH_FOLLOW : MD_PATH_ENTRY,
                nr == SYS_linkat && (a[4] & AT_EMPTY_PATH), first);
        if (r < 0) return r;
        r = path_at(fs, exe, (int)a[2], a[3], MD_PATH_ENTRY, 0, second);
        return r < 0 ? r : md_raw(nr, first[0] ? AT_FDCWD : a[0], (long)first, AT_FDCWD, (long)second, a[4], 0);
    case SYS_chdir:
    case SYS_truncate:
    case SYS_statfs:
    case SYS_setxattr: case SYS_lsetxattr:
    case SYS_getxattr: case SYS_lgetxattr:
    case SYS_listxattr: case SYS_llistxattr:
    case SYS_removexattr: case SYS_lremovexattr:
        r = path_at(fs, exe, AT_FDCWD, a[0], nr != SYS_lsetxattr && nr != SYS_lgetxattr
                && nr != SYS_llistxattr && nr != SYS_lremovexattr, 0, first);
        return r < 0 ? r : md_raw(nr, (long)first, a[1], a[2], a[3], a[4], 0);
    case SYS_inotify_add_watch:
        r = path_at(fs, exe, AT_FDCWD, a[1], !(a[2] & IN_DONT_FOLLOW), 0, first);
        return r < 0 ? r : RAW3(inotify_add_watch, a[0], first, a[2]);
    case SYS_getcwd: {
        r = RAW2(getcwd, first, sizeof(first));
        if (r < 0) return r;
        r = md_fs_guest(fs, first, second);
        if (r < 0) return r;
        size_t n = md_length(second) + 1;
        if (n > a[1]) return -ERANGE;
        r = md_write_memory((void *)a[0], second, n);
        return r < 0 ? r : (long)n;
    }
    case SYS_readlinkat:
        r = md_read_string(first, sizeof(first), (const char *)a[1]);
        if (r < 0) return r;
        struct md_proc_path ref = md_proc_path(first);
        if ((ref.kind == MD_PROC_EXE || ref.kind == MD_PROC_ROOT) && !*ref.tail) {
            if (!a[3]) return -EINVAL;
            const char *text = ref.kind == MD_PROC_ROOT ? "/" : exe;
            size_t n = md_length(text);
            if (n > a[3]) n = a[3];
            r = md_write_memory((void *)a[2], text, n);
            return r < 0 ? r : (long)n;
        }
        if ((ref.kind == MD_PROC_FD || ref.kind == MD_PROC_CWD) && !*ref.tail && !ref.ordinary_link) {
            if (!a[3]) return -EINVAL;
            r = RAW4(readlinkat, AT_FDCWD, first, second, sizeof(second) - 1);
            if (r < 0) return r;
            second[r] = 0;
            r = md_fs_guest(fs, second, first);
            if (r < 0 && r != -EXDEV) return r;
            const char *text = r == -EXDEV ? second : first;
            size_t n = md_length(text);
            if (n > a[3]) n = a[3];
            r = md_write_memory((void *)a[2], text, n);
            return r < 0 ? r : (long)n;
        }
        r = path_at(fs, exe, (int)a[0], a[1], 0, 1, first);
        return r < 0 ? r : RAW4(readlinkat, first[0] ? AT_FDCWD : a[0], first, a[2], a[3]);
    default: return -ENOSYS;
    }
}
