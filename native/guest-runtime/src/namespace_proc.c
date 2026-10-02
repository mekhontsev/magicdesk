#define _GNU_SOURCE
#include "namespace_internal.h"
#include "proc_paths.h"
#include "fd_metadata.h"
#include "interception.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <linux/stat.h>
#ifndef STATX_MNT_ID_UNIQUE
#define STATX_MNT_ID_UNIQUE 0x4000U
#endif

long md_namespace_native_statx(const struct md_fs *fs, const unsigned long *args) {
    struct statx st;
    /* Publish ordinary mount IDs, never claim the kernel-global UNIQUE space. */
    long r=RAW5(statx,args[0],args[1],args[2],(args[3]&~(unsigned long)STATX_MNT_ID_UNIQUE)|STATX_MNT_ID,&st);
    if (r<0) return r;
    if (st.stx_mask & STATX_MNT_ID) {
        struct md_fs_request q={.operation=MD_FS_NATIVE_MOUNT,.directory={-1,-1},.offset=(int64_t)st.stx_mnt_id};
        struct md_fs_response out;
        r=md_fs_call(fs->endpoint,5000,&q,&out);
        if (!r) r=out.result.error;
        if (r<0) return r;
        st.stx_mnt_id=out.result.info.mount_id;
    }
    return md_write_memory((void *)args[4],&st,sizeof(st));
}

static long descriptor_path(int base, char *host) {
    long n;
    if (base == AT_FDCWD) {
        n = RAW2(getcwd, host, PATH_MAX);
        if (n < 0) return n;
    } else {
        if (base < 0) return -EBADF;
        char link[64] = "/proc/thread-self/fd/";
        md_decimal(link + md_length(link), (unsigned)base);
        n = RAW4(readlinkat, AT_FDCWD, link, host, PATH_MAX - 1);
        if (n < 0) return n;
        if (n == PATH_MAX - 1) return -ENAMETOOLONG;
        host[n] = 0;
    }
    return 0;
}
long md_namespace_native_descriptor(int base) {
    char host[PATH_MAX];
    long r=descriptor_path(base,host);
    return r<0 ? r : md_host_path(host);
}
long md_namespace_relative_mount(int base, char *path) {
    if (!*path || *path == '/') return 0;
    char host[PATH_MAX];
    long n=descriptor_path(base,host);
    if (n<0) return n;
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
        struct md_fs_request q = {.operation = MD_FS_PATH, .directory = {(int)fd, -1}};
        struct md_fs_response out;
        long r = md_namespace_request(fs, &q, &out);
        if (!r) r = md_copy(text, sizeof(text), out.data);
        RAW1(close, fd);
        if (r < 0 && r != -EXDEV) return r;
    }
    return link_result(text, a);
}
long md_namespace_host_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a,
                            unsigned path_index, const char *path) {
    if (nr == SYS_renameat || nr == SYS_renameat2 || nr == SYS_linkat) return -ENOTSUP;
    struct md_proc_path ref = md_proc_path(path);
    if (ref.kind==MD_PROC_MOUNTS || ref.kind==MD_PROC_MOUNTINFO) {
        if (*ref.tail) return -ENOTDIR;
        if (nr==SYS_openat) {
            int flags=(int)a[2];
            if (flags&O_DIRECTORY) return -ENOTDIR;
            if (!(flags&O_PATH) && ((flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC))) return -EACCES;
            if ((flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) return -EEXIST;
            struct md_fs_request q={.operation=MD_FS_MOUNT_TABLE,.directory={-1,-1},.flags=ref.kind==MD_PROC_MOUNTINFO};
            struct md_fs_response response;
            long error=md_fs_call(fs->endpoint,5000,&q,&response);
            if (!error) error=response.result.error;
            if (error) return error;
            int fd=response.result.fd;
            if (flags&O_PATH) {
                char descriptor[64]="/proc/thread-self/fd/";
                md_decimal(descriptor+md_length(descriptor),(unsigned)fd);
                long opened=RAW4(openat,AT_FDCWD,descriptor,O_PATH|O_CLOEXEC,0);
                RAW1(close,fd);
                if (opened<0) return opened;
                fd=(int)opened;
            }
            if (!(flags&O_CLOEXEC) && (error=RAW3(fcntl,fd,F_SETFD,0))<0) { RAW1(close,fd); return error; }
            return fd;
        }
        ref.kind=MD_PROC_NONE;
    }
    if (ref.kind == MD_PROC_FOREIGN) {
        if (*ref.tail) return -ENOTSUP;
        if (ref.foreign_kind!=MD_PROC_EXE && ref.foreign_kind!=MD_PROC_CMDLINE
                && ref.foreign_kind!=MD_PROC_AUXV) return -ENOTSUP;
        if (nr!=SYS_openat && nr!=SYS_readlinkat) return -ENOTSUP;
        if (nr==SYS_readlinkat && ref.foreign_kind!=MD_PROC_EXE) return -ENOTSUP;
        if (nr==SYS_readlinkat && !a[3]) return -EINVAL;
        long fd=RAW5(prctl,MD_GUEST_PROC_IMAGE,ref.process,ref.foreign_kind,
            nr==SYS_openat ? a[2] : O_RDONLY|O_CLOEXEC|MD_PROC_IMAGE_LINK,0);
        if (fd<0 || nr==SYS_openat) return fd;
        long size=RAW3(read,fd,a[2],a[3]); RAW1(close,fd); return size;
    }
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
    if (native)
        return nr==SYS_statx ? md_namespace_native_statx(fs,args)
            : md_raw(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
    if (!tail && nr == SYS_openat) {
        if (ref.kind == MD_PROC_EXE) return md_proc_executable_open(fs, exe, (int)a[2]);
        if (ref.kind == MD_PROC_ROOT)
            return md_namespace_open(fs, AT_FDCWD, "/",
                                     (int)a[2], (unsigned)a[3]);
        long original = RAW4(openat, AT_FDCWD, path, O_PATH | O_CLOEXEC, 0);
        if (original < 0) return original;
        /* The followed magic link already selected an existing object. Creation
         * and exclusive-link semantics were handled above, not by its backing. */
        int flags = (int)a[2];
        long reopened = (flags & O_CREAT) && (flags & O_DIRECTORY) ? -EINVAL
            : md_namespace_reopen(fs, (int)original, flags & ~(O_CREAT | O_EXCL), 0);
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
    long r;
    if (tail) {
        const char *relative = ref.tail;
        while (*relative == '/') ++relative;
        if (!*relative) relative = ".";
        struct md_fs_response out;
        r = md_namespace_inspect(fs, (int)fd, NULL, 0, 0, &out);
        if (!r) r = md_namespace_path_call(fs, nr, args, (int)fd, relative);
        else if (r == -EXDEV) {
            md_copy(anchor, sizeof(anchor), "/proc/thread-self/fd/");
            md_decimal(anchor + md_length(anchor), (unsigned)fd);
            r = md_append(anchor, sizeof(anchor), "/");
            if (!r) r = md_append(anchor, sizeof(anchor), relative);
            if (!r) {
                args[path_index] = (unsigned long)anchor;
                r = nr==SYS_statx ? md_namespace_native_statx(fs,args)
                    : md_raw(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
            }
        }
    } else switch (nr) {
    case SYS_newfstatat: case SYS_statx:
        args[0] = (unsigned long)fd; args[1] = (unsigned long)"";
        args[nr == SYS_statx ? 2 : 3] |= AT_EMPTY_PATH;
        r = md_namespace_path_call(fs, nr, args, (int)fd, "");
        break;
    case SYS_chdir:
        args[0] = (unsigned long)fd;
        r = md_namespace_call(fs, exe, SYS_fchdir, args);
        break;
    case SYS_faccessat: case SYS_faccessat2:
    case SYS_fchmodat: case SYS_fchmodat2:
    case SYS_fchownat: case SYS_utimensat: {
        /* A magic link selects an inode, not a second metadata authority.
         * The ordinary empty-path operation owns permissions, ACLs and copy-up. */
        long operation = nr == SYS_faccessat ? SYS_faccessat2 : nr == SYS_fchmodat ? SYS_fchmodat2 : nr;
        unsigned flags_index = nr == SYS_fchownat ? 4 : 3;
        args[0] = (unsigned long)fd; args[1] = (unsigned long)"";
        args[flags_index] = (nr == SYS_faccessat || nr == SYS_fchmodat ? 0 : args[flags_index]) | AT_EMPTY_PATH;
        r = md_namespace_path_call(fs, operation, args, (int)fd, "");
        break;
    }
    case SYS_statfs: r = RAW2(fstatfs, fd, a[1]); break;
    case SYS_setxattr: case SYS_getxattr: case SYS_listxattr: case SYS_removexattr: {
        struct md_fs_response out;
        r = md_namespace_inspect(fs, (int)fd, NULL, 0, 0, &out);
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
