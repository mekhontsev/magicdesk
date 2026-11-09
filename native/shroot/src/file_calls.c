#define _GNU_SOURCE
#include "file_calls.h"
#include "namespace.h"
#include "proc_paths.h"
#include "proc_control.h"
#include "descriptor_calls.h"
#include "fd_client.h"
#include "adapter_buffer.h"
#include "native_identity.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <sys/inotify.h>
#include "namespace_internal.h"
#include "open_request.h"

static long open_extended(const struct md_fs *fs, const char *exe, const unsigned long *a) {
    struct open_how how;
    long r = md_read_struct(&how, sizeof(how), (void *)a[2], a[3]);
    if (r < 0) return r;
    r=md_open_how_validate(&how);
    if (r) return r;
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
    /* Host mount paths keep the kernel's walk. Never apply IN_ROOT against
     * Android's root when the supplied descriptor belongs to the guest. */
    char mounted[PATH_MAX];
    md_copy(mounted, sizeof(mounted), path);
    r = md_namespace_relative_mount(fs, (int)a[0], mounted);
    if (r < 0) return r;
    if (md_fs_host_path(fs, mounted) && !(how.resolve & RESOLVE_IN_ROOT))
        return md_namespace_host_open_resolved(fs,(int)a[0],path,mounted,&how,0);
    return !fs->endpoint[0] ? -ENOTSUP : md_namespace_open_resolved(fs, (int)a[0], path, &how);
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

static long xattr_extended(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    unsigned flags=(unsigned)a[2];
    struct md_xattr_args value={0};
    if (nr==SYS_setxattrat || nr==SYS_getxattrat) {
        long error=md_read_struct(&value,sizeof(value),(void *)a[4],a[5]);
        if (error<0) return error;
        if (nr==SYS_getxattrat && value.flags) return -EINVAL;
    }
    if (flags&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW)) return -EINVAL;
    char path[PATH_MAX]="";
    if (a[1] || !(flags&AT_EMPTY_PATH)) {
        long error=md_read_string(path,sizeof(path),(const char *)a[1]);
        if (error<0) return error;
    }
    int descriptor=!*path;
    if (descriptor && !(flags&AT_EMPTY_PATH)) return -ENOENT;
    int nofollow=flags&AT_SYMLINK_NOFOLLOW;
    long legacy;
    switch (nr) {
    case SYS_setxattrat: legacy=descriptor ? SYS_fsetxattr : nofollow ? SYS_lsetxattr : SYS_setxattr; break;
    case SYS_getxattrat: legacy=descriptor ? SYS_fgetxattr : nofollow ? SYS_lgetxattr : SYS_getxattr; break;
    case SYS_listxattrat: legacy=descriptor ? SYS_flistxattr : nofollow ? SYS_llistxattr : SYS_listxattr; break;
    default: legacy=descriptor ? SYS_fremovexattr : nofollow ? SYS_lremovexattr : SYS_removexattr; break;
    }
    unsigned long args[6]={descriptor ? a[0] : (uintptr_t)path, a[3], value.value, value.size, value.flags};
    if (nr==SYS_listxattrat) args[2]=a[4];
    if (descriptor) return md_file_call(fs,exe,legacy,args);
    /* Preserve the caller's dirfd; the existing provider owns permission,
     * symlink, mount and metadata semantics for both syscall generations. */
    if (fs->endpoint[0]) {
        long error=md_namespace_relative_mount(fs,(int)a[0],path);
        if (error<0) return error;
        if (md_fs_host_path(fs,path)) return md_namespace_host_call(fs,exe,legacy,args,0,path,0);
        return md_namespace_xattr(fs,legacy,md_namespace_descriptor((int)a[0]),path,args);
    }
    char resolved[PATH_MAX];
    long error=path_at(fs,exe,(int)a[0],(uintptr_t)path,!nofollow,0,resolved);
    return error<0 ? error : md_raw(legacy,(uintptr_t)resolved,args[1],args[2],args[3],args[4],0);
}

static long native_metadata_fallback(const struct md_fs *fs,long nr,const unsigned long *a,long error) {
    if ((error!=-EMFILE && error!=-EACCES && error!=-EPERM)
            || (nr!=SYS_fstat && nr!=SYS_newfstatat && nr!=SYS_statx)) return error;
    /* A protected caller can still inspect its native proc/dev FD when an
     * export channel cannot be admitted. Hold its table across classification
     * and the native operation; managed files retain the namespace result. */
    long locked=md_fd_control(MD_FD_USE,0,0,0);
    if (locked<0) return locked;
    long native=md_namespace_native_descriptor((int)a[0]);
    struct statx st;
    if (native>0) error=nr==SYS_statx
        ? RAW5(statx,a[0],a[1],a[2],(a[3]&~(unsigned long)STATX_MNT_ID_UNIQUE)|STATX_MNT_ID,&st)
        : md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    long released=md_fd_control(MD_FD_RELEASE,0,0,0);
    if (released<0) return released;
    return !error && nr==SYS_statx ? md_namespace_native_statx_result(fs,a,&st) : error;
}

long md_file_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    if (nr==SYS_readlinkat && (int)a[3]<=0) return -EINVAL;
    if (nr==SYS_pipe2) return md_descriptor_pair(nr,a);
    if (nr==SYS_fsync || nr==SYS_fdatasync || nr==SYS_syncfs)
        return fs->endpoint[0] ? md_namespace_call(fs,exe,nr,a) : md_raw(nr,a[0],0,0,0,0,0);
    if (nr == SYS_openat2) return open_extended(fs, exe, a);
    if (nr==SYS_setxattrat || nr==SYS_getxattrat || nr==SYS_listxattrat || nr==SYS_removexattrat)
        return xattr_extended(fs,exe,nr,a);
    int descriptor=nr==SYS_fstat || nr==SYS_fstatfs || nr==SYS_fchown || nr==SYS_fchmod
        || nr==SYS_getdents64 || nr==SYS_fchdir || (nr==SYS_utimensat && !a[1]);
    if (nr==SYS_readlinkat || nr==SYS_newfstatat || nr==SYS_statx || nr==SYS_faccessat2 || nr==SYS_fchownat || nr==SYS_fchmodat2) {
        unsigned index=nr==SYS_statx ? 2 : nr==SYS_fchownat ? 4 : 3;
        if (nr==SYS_readlinkat || (a[index]&AT_EMPTY_PATH)) {
            char first;
            long error=md_read_memory(&first,(void *)a[1],1);
            if (error<0) return error;
            descriptor=!first;
        }
    }
    if (descriptor) {
        long known=md_descriptor_kind((int)a[0]);
        if (known<0) return known;
        if (known==MD_DESCRIPTOR_ANONYMOUS)
            return nr==SYS_readlinkat ? -ENOENT : md_descriptor_metadata(fs,(int)a[0],nr,a);
        if (known && (known!=MD_DESCRIPTOR_FILESYSTEM || nr==SYS_getdents64)) {
            long result=md_proc_control_metadata(&(struct md_proc_path){.process=md_native_tid(),.descriptor=(int)a[0]},nr,a);
            if (result!=-EXDEV) return result;
        }
    }
    if (fs->endpoint[0]) {
        long result=md_namespace_call(fs,exe,nr,a);
        return descriptor ? native_metadata_fallback(fs,nr,a,result) : result;
    }
    char first[PATH_MAX], second[PATH_MAX];
    long r;
    int flags;
    switch (nr) {
    case SYS_fstat: case SYS_fstatfs: case SYS_getdents64: case SYS_lseek: case SYS_fsetxattr:
    case SYS_fchmod: case SYS_fchown: case SYS_fremovexattr: case SYS_fchdir:
    case SYS_fgetxattr: case SYS_flistxattr:
        return md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
    case SYS_openat:
        flags = (int)a[2];
        r = path_at(fs, exe, (int)a[0], a[1], (flags & O_CREAT) && (flags & O_EXCL)
                ? MD_PATH_ENTRY : !(flags & O_NOFOLLOW), 0, first);
        if (r < 0) return r;
        struct md_proc_path image = md_proc_path(first);
        enum md_proc_kind control=image.kind==MD_PROC_FOREIGN ? image.foreign_kind : image.kind;
        if (control==MD_PROC_MEMORY) {
            unsigned long args[6]={(unsigned long)AT_FDCWD,(uintptr_t)first,(unsigned)flags,a[3]};
            return md_namespace_memory_open(image.process,SYS_openat,args);
        }
        if (md_proc_control_kind(control)) {
            long result=*image.tail ? -ENOTDIR : md_proc_control_open(&image,flags,0);
            if (result!=-EXDEV || !md_proc_network_control(control)) return result;
        }
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
        if (r>=0) {
            struct md_proc_path ref=md_proc_path(first);
            enum md_proc_kind kind=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
            if (md_proc_control_kind(kind)) {
                long result=*ref.tail ? -ENOTDIR : md_proc_control_metadata(&ref,nr,a);
                if (result!=-EXDEV || !md_proc_network_control(kind)) return result;
            }
        }
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
