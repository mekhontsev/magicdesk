#define _GNU_SOURCE
#include "file_calls.h"
#include "namespace.h"
#include "proc_paths.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/inotify.h>

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
    if (fs->endpoint[0]) return md_namespace_call(fs,exe,nr,a);
    char first[PATH_MAX], second[PATH_MAX];
    long r;
    int flags;
    switch (nr) {
    case SYS_fstat: case SYS_getdents64: case SYS_lseek: case SYS_fsetxattr:
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    case SYS_openat:
        flags = (int)a[2];
        r = path_at(fs, exe, (int)a[0], a[1], (flags & O_CREAT) && (flags & O_EXCL)
                ? MD_PATH_ENTRY : !(flags & O_NOFOLLOW), 0, first);
        return r < 0 ? r : RAW4(openat, AT_FDCWD, first, flags, a[3]);
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
