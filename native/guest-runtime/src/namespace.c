#define _GNU_SOURCE
#include "namespace_internal.h"
#include "proc_paths.h"
#include "fs_rpc.h"
#include "fd_metadata.h"
#include "linux_abi.h"
#include "raw.h"
#include "posix_acl.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <linux/memfd.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <sys/sysmacros.h>
#include <unistd.h>

long md_namespace_request(const struct md_fs *fs, struct md_fs_request *q, struct md_fs_response *out) {
    int cwd = -1;
    unsigned bases = q->operation == MD_FS_LINK || q->operation == MD_FS_RENAME ? 2 : 1;
    for (unsigned i = 0; i < bases; ++i) {
        if (q->path[i] && q->path[i][0] == '/' && !(q->resolve & RESOLVE_IN_ROOT))
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
    return r < 0 ? r : out->result.host_path ? -EREMOTE : out->result.error;
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
static long complete_open(struct md_fs_result *result, int flags) {
    int fd = result->fd;
    if (result->open_completion.kind != MD_OPEN_READY)
        return md_complete_open(fd, flags, result->open_completion);
    if (!(flags & O_CLOEXEC)) {
        long r = RAW3(fcntl, fd, F_SETFD, 0);
        if (r < 0) { RAW1(close, fd); return r; }
    }
    return fd;
}
long md_namespace_reopen(const struct md_fs *fs, int fd, int flags, int mutable) {
    long native = md_namespace_native_descriptor(fd);
    if (native < 0) return native;
    if (native) return -EXDEV;
    struct md_fs_request q = {.operation=MD_FS_REOPEN, .directory={fd,-1},
        .flags=(uint32_t)flags, .mode=(uint32_t)mutable};
    struct md_fs_response out;
    long r = md_namespace_request(fs, &q, &out);
    if (r < 0) return r;
    return complete_open(&out.result, flags);
}
long md_namespace_mutable(const struct md_fs *fs, int fd) {
    long r = md_namespace_reopen(fs, fd, O_PATH | O_NOFOLLOW | O_CLOEXEC, 1);
    return r == -EXDEV ? RAW3(fcntl, fd, F_DUPFD_CLOEXEC, 3) : r;
}
long md_namespace_open(const struct md_fs *fs, int base, const char *path, int flags, unsigned mode) {
    if (md_host_path(path))
        return RAW4(openat, base, path, flags, mode);
    /* Legacy open ignores file-type bits; openat2 validates its mode separately. */
    struct open_how how = {.flags = (unsigned)flags,
        .mode = (flags & O_CREAT) && !(flags & O_PATH) ? mode & 07777U : 0};
    return md_namespace_open_resolved(fs, base, path, &how);
}
long md_namespace_open_resolved(const struct md_fs *fs, int base, const char *path,
        const struct open_how *how) {
    int flags = (int)how->flags;
    unsigned mode = (unsigned)how->mode;
    unsigned mask=0;
    if ((flags & O_CREAT) && !(flags & O_PATH)) {
        long masked = md_namespace_creation_mode(0777);
        if (masked < 0)
            return masked;
        mask = 0777U ^ (unsigned)masked;
    }
    struct md_fs_request q = {.operation = MD_FS_OPEN,
                              .directory = {base, -1},
                              .path = {path, NULL},
                              .flags = (uint32_t)flags,
                              .mode = mode, .resolve = how->resolve, .attributes.creation_mask=mask};
    struct md_fs_response out;
    long r = md_namespace_request(fs, &q, &out);
    if (r==-EREMOTE) {
        unsigned long args[6]={(unsigned long)AT_FDCWD,(unsigned long)out.data,(unsigned)flags,mode,0,0};
        return md_namespace_host_call(fs,fs->image ? fs->image->executable_path : "",SYS_openat,args,1,out.data);
    }
    if (r < 0)
        return r;
    return complete_open(&out.result, flags);
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
long md_namespace_inspect(const struct md_fs *fs, int fd, const char *path, int flags, unsigned mode, struct md_fs_response *out) {
    if (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT))
        return -EINVAL;
    struct md_fs_request q = {.operation = path && *path ? MD_FS_STAT : MD_FS_FSTAT,
                              .directory = {fd, -1}, .mode=mode,
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
    long native = md_namespace_native_descriptor((int)a[0]);
    if (native < 0) return native;
    if (native) return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    struct md_fs_response out;
    struct stat st;
    if (nr == SYS_getdents64) {
        /* The namespace validates the descriptor and advances its cursor in
         * one operation; a preliminary metadata RPC adds no authority. */
        struct md_fs_request q = {.operation = MD_FS_GETDENTS, .directory = {(int)a[0], -1},
            .capacity = a[2] > PATH_MAX ? PATH_MAX : (uint32_t)a[2]};
        long r = md_namespace_request(fs, &q, &out);
        if (r == -EXDEV) return md_raw(nr, a[0], a[1], a[2], 0, 0, 0);
        if (r < 0) return r;
        r = md_write_memory((void *)a[1], out.data, out.result.size);
        return r < 0 ? r : out.result.size;
    }
    long r = md_namespace_inspect(fs, (int)a[0], NULL, 0, 0, &out);
    if (r == -EXDEV)
        return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    if (r < 0)
        return r;
    if (nr == SYS_fstat) {
        stat_info(&out.result.info, &st);
        return md_write_memory((void *)a[1], &st, sizeof(st));
    }
    if (!S_ISDIR(out.result.info.mode))
        return md_raw(nr, a[0], a[1], a[2], 0, 0, 0);
    struct md_fs_request q = {.operation = MD_FS_SEEKDIR, .directory = {(int)a[0], -1},
        .offset = (int64_t)a[1], .flags = (uint32_t)a[2]};
    r = md_namespace_request(fs, &q, &out);
    if (r < 0)
        return r;
    return out.result.position;
}
static long namespace_acl(const struct md_fs *fs,int fd,unsigned type,int set,int remove,const unsigned long *a) {
    struct md_fs_request q={.operation=set ? MD_FS_SETACL : remove ? MD_FS_REMOVEACL : MD_FS_GETACL,
        .directory={fd,-1},.mode=type};
    long r=0, payload=-1;
    if(set) {
        if((a[3] && a[3]<4) || a[3]>MD_ACL_MAX || (a[4]&~3UL)) return -EINVAL;
        payload=RAW2(memfd_create,"guest-acl",MFD_CLOEXEC|MFD_ALLOW_SEALING);
        if(payload<0) return payload;
        unsigned char chunk[4096];
        for(size_t copied=0;!r && copied<a[3];) {
            size_t n=a[3]-copied; if(n>sizeof(chunk)) n=sizeof(chunk);
            r=md_read_memory(chunk,(const char *)a[2]+copied,n);
            if(!r) { long written=RAW4(pwrite64,payload,chunk,n,copied); if(written!=(long)n) r=written<0 ? written : -EIO; }
            copied+=n;
        }
        if(!r) r=RAW3(fcntl,payload,F_ADD_SEALS,F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL);
        q.directory[1]=(int)payload; q.capacity=(unsigned)a[3]; q.flags=(unsigned)a[4];
    }
    struct md_fs_response out;
    if(!r) r=md_namespace_request(fs,&q,&out);
    if(payload>=0) RAW1(close,payload);
    if(!r && !set && !remove) {
        if(out.result.position<4 || out.result.position>MD_ACL_MAX) r=-EPROTO;
        else if(!a[3]) r=out.result.position;
        else if(a[3]<(unsigned long)out.result.position) r=-ERANGE;
        else r=RAW4(pread64,out.result.fd,a[2],out.result.position,0);
        RAW1(close,out.result.fd);
    }
    return r;
}
static long namespace_xattr_backing(const struct md_fs *fs, int fd, const char *name, unsigned access) {
    struct md_fs_request q = {.operation=MD_FS_XATTR_OPEN, .directory={fd,-1},
        .path={name,NULL}, .mode=access};
    struct md_fs_response out;
    long r = md_namespace_request(fs,&q,&out);
    if (!r) return out.result.fd;
    if (r != -EXDEV) return r;
    return access == W_OK ? md_namespace_mutable(fs,fd) : RAW3(fcntl,fd,F_DUPFD_CLOEXEC,3);
}
long md_namespace_xattr(const struct md_fs *fs, long nr, int base, const char *path, const unsigned long *a) {
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    char name[256];
    int list = nr == SYS_listxattr || nr == SYS_llistxattr || nr == SYS_flistxattr;
    int set = nr == SYS_setxattr || nr == SYS_lsetxattr || nr == SYS_fsetxattr;
    int remove = nr == SYS_removexattr || nr == SYS_lremovexattr || nr == SYS_fremovexattr;
    int descriptor = nr == SYS_fgetxattr || nr == SYS_flistxattr || nr == SYS_fsetxattr || nr == SYS_fremovexattr;
    if (!list) {
        long r = md_read_string(name, sizeof(name), (const char *)a[1]);
        if (r < 0)
            return r == -ENAMETOOLONG ? -ERANGE : r;
        args[1] = (unsigned long)name;
    }
    unsigned acl=!list && md_equal(name,MD_ACL_ACCESS_NAME) ? MD_ACL_ACCESS
        : !list && md_equal(name,MD_ACL_DEFAULT_NAME) ? MD_ACL_DEFAULT : 0;
    if (descriptor) {
        long flags = RAW3(fcntl, base, F_GETFL, 0);
        if (flags < 0) return flags;
        if (flags & O_PATH) return -EBADF;
    }
    int nofollow =
        nr == SYS_lsetxattr || nr == SYS_lgetxattr || nr == SYS_llistxattr || nr == SYS_lremovexattr;
    long fd = descriptor ? RAW3(fcntl,base,F_DUPFD_CLOEXEC,3)
        : md_namespace_open(fs, base, path, O_PATH | O_CLOEXEC | (nofollow ? O_NOFOLLOW : 0), 0);
    if (fd < 0)
        return fd;
    long native = md_namespace_native_descriptor((int)fd);
    if (native) {
        long result = native < 0 ? native : md_fd_xattr((int)fd, nr, args);
        RAW1(close, fd); return result;
    }
    if (list) {
        long next = namespace_xattr_backing(fs,(int)fd,"",F_OK);
        RAW1(close,fd); fd=next;
        if (fd < 0) return fd;
    }
    long r = -EXDEV;
    if(acl) {
        r=namespace_acl(fs,(int)fd,acl,set,remove,a);
        if(r!=-EXDEV) { RAW1(close,fd); return r; }
    }
    if (list || md_equal(name, MD_FILE_CAPABILITY_NAME)) {
        struct md_fs_request q = {.operation=list ? MD_FS_LISTATTR : set ? MD_FS_SETCAP : remove ? MD_FS_REMOVECAP : MD_FS_GETCAP,
            .directory={(int)fd,-1}};
        struct md_fs_response out;
        if (set) {
            if (a[3] > MD_FILE_CAPABILITY_MAX) { RAW1(close,fd); return -EINVAL; }
            q.flags=(unsigned)a[4]; q.capacity=(unsigned)a[3];
            r=md_read_memory(q.attributes.capability,(void *)a[2],a[3]);
        } else r=0;
        if (!r) r=md_namespace_request(fs,&q,&out);
        if (list && (r == -ENODATA || r == -EXDEV)) r=-EXDEV;
        else if (!r && list) {
            /* Count native attributes without passing a reduced/zero guest
             * capacity to the kernel; an undersized list must fail, not become
             * a size query. A concurrent list change may report ERANGE. */
            unsigned long probe[6]; memcpy(probe,args,sizeof(probe)); probe[1]=probe[2]=0;
            long bytes=md_fd_xattr((int)fd,nr,probe);
            size_t extra=out.result.size;
            if (bytes<0) r=bytes;
            else if (!a[2]) r=bytes+(long)extra;
            else if ((size_t)bytes > a[2] || extra > a[2]-(size_t)bytes) r=-ERANGE;
            else {
                r=bytes ? md_fd_xattr((int)fd,nr,args) : 0;
                if (r>=0 && (size_t)r<=a[2] && extra<=a[2]-(size_t)r) {
                    long copied=md_write_memory((char *)a[1]+r,out.data,extra);
                    r=copied<0 ? copied : r+(long)extra;
                } else if (r>=0) r=-ERANGE;
            }
        } else if (!r && !set && !remove) {
            if (!a[3]) r=(long)out.result.size;
            else if (a[3]<out.result.size) r=-ERANGE;
            else { r=md_write_memory((void *)a[2],out.data,out.result.size); if (!r) r=(long)out.result.size; }
        }
        if (r != -EXDEV) { RAW1(close,fd); return r; }
    }
    if (!list) {
        long next = namespace_xattr_backing(fs,(int)fd,name,set || remove ? W_OK : R_OK);
        RAW1(close,fd); fd=next;
        if (fd < 0) return fd;
    }
    r = md_fd_xattr((int)fd, nr, args);
    RAW1(close, fd);
    return r;
}
static long metadata_request(const struct md_fs *fs, long nr, int fd,
        unsigned long mode, unsigned long extra, int flags) {
    long native = md_namespace_native_descriptor(fd);
    if (native < 0) return native;
    if (native) return -EXDEV;
    struct md_fs_request q = {.directory = {fd, -1}, .flags = (unsigned)flags};
    switch (nr) {
    case SYS_fchmod: case SYS_fchmodat: case SYS_fchmodat2:
        q.operation = MD_FS_CHMOD; q.mode = (unsigned)mode; break;
    case SYS_fchown: case SYS_fchownat:
        q.operation = MD_FS_CHOWN; q.attributes.uid = (uint32_t)mode; q.attributes.gid = (uint32_t)extra; break;
    case SYS_faccessat: case SYS_faccessat2:
        q.operation = MD_FS_ACCESS; q.mode = (unsigned)mode; break;
    case SYS_utimensat: {
        q.operation = MD_FS_UTIMENS;
        struct timespec times[2] = {{0, UTIME_NOW}, {0, UTIME_NOW}};
        if (mode) { long r = md_read_memory(times, (void *)mode, sizeof(times)); if (r < 0) return r; }
        for (unsigned i = 0; i < 2; i++) {
            q.attributes.seconds[i] = times[i].tv_sec; q.attributes.nanos[i] = times[i].tv_nsec;
        }
        break;
    }
    default: return -EXDEV;
    }
    struct md_fs_response out;
    return md_namespace_request(fs, &q, &out);
}
long md_namespace_call(const struct md_fs *fs, const char *exe, long nr, const unsigned long *a) {
    if (nr == SYS_fchdir) {
        long r = metadata_request(fs, SYS_faccessat2, (int)a[0], X_OK, 0, MD_AT_EACCESS);
        return !r || r == -EXDEV ? RAW1(fchdir, a[0]) : r;
    }
    if (nr == SYS_fstat || nr == SYS_getdents64 || nr == SYS_lseek)
        return descriptor_call(fs, nr, a);
    if (nr == SYS_fsetxattr || nr == SYS_fremovexattr || nr == SYS_fgetxattr || nr == SYS_flistxattr)
        return md_namespace_xattr(fs, nr, (int)a[0], NULL, a);
    if (nr == SYS_fchmod || nr == SYS_fchown || (nr == SYS_utimensat && !a[1])) {
        long flags = RAW3(fcntl, a[0], F_GETFL, 0);
        if (flags < 0) return flags;
        if (flags & O_PATH) return -EBADF;
        long result = metadata_request(fs, nr, (int)a[0], nr == SYS_utimensat ? a[2] : a[1], a[2], 0);
        if (result != -EXDEV) return result;
        long fd = md_namespace_mutable(fs, (int)a[0]);
        if (fd < 0) return fd;
        long r;
        if (nr == SYS_fchmod) r = md_fd_chmod((int)fd, (unsigned)a[1]);
        else if (nr == SYS_fchown) r = RAW5(fchownat, fd, "", a[1], a[2], AT_EMPTY_PATH);
        else r = RAW4(utimensat, fd, "", a[2], a[3] | AT_EMPTY_PATH);
        RAW1(close, fd); return r;
    }
    if (nr == SYS_getcwd) {
        struct md_fs_request q = {.operation = MD_FS_PATH, .directory = {AT_FDCWD, -1}};
        struct md_fs_response out;
        long r = md_namespace_request(fs, &q, &out);
        if (r < 0)
            return r;
        if (out.result.size > a[1])
            return -ERANGE;
        r = md_write_memory((void *)a[0], out.data, out.result.size);
        return r < 0 ? r : out.result.size;
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
    /* Direct and symlink-resolved host boundaries use the same task adapter. */
    if (md_host_path(first) && nr != SYS_inotify_add_watch)
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
        if ((mode & S_IFMT) == S_IFIFO) {
            long masked = md_namespace_creation_mode(0777);
            if (masked < 0) return masked;
            struct md_fs_request q = {.operation=MD_FS_MKFIFO, .directory={base,-1},
                .path={first,NULL}, .mode=mode & 07777,
                .attributes.creation_mask=0777U ^ (unsigned)masked};
            struct md_fs_response out;
            long r = md_namespace_request(fs, &q, &out);
            if (r == -EREMOTE)
                return md_namespace_host_call(fs, fs->image ? fs->image->executable_path : "",
                    nr, a, 1, out.data);
            return r;
        }
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
        long r = metadata_request(fs, SYS_faccessat2, (int)fd, X_OK, 0, MD_AT_EACCESS);
        if (!r || r == -EXDEV) r = RAW1(fchdir, fd);
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
    struct md_fs_response out;
    switch (nr) {
    case SYS_inotify_add_watch: {
        long fd = md_namespace_open(fs, base, first, O_PATH | O_CLOEXEC |
            ((a[2] & IN_DONT_FOLLOW) ? O_NOFOLLOW : 0), 0);
        if (fd < 0) return fd;
        q = (struct md_fs_request){.operation = MD_FS_WATCH_ADD,
            .directory = {(int)a[0], (int)fd}, .flags = (unsigned)a[2]};
        r = md_fs_call(fs->endpoint, 5000, &q, &out);
        if (!r) r = out.result.error ? out.result.error : out.result.position;
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
        if (!*first && (flags & AT_EMPTY_PATH)) {
            /* Native boundary FDs need no namespace ownership lookup. Some LSM
             * policies allow local stat but reject SCM_RIGHTS for these FDs. */
            r=md_namespace_native_descriptor(base);
            if (r<0) return r;
            if (r) return nr==SYS_statx ? md_namespace_native_statx(fs,a)
                : md_raw(nr,a[0],a[1],a[2],a[3],a[4],a[5]);
        }
        r = md_namespace_inspect(fs, base, first, flags, nr==SYS_statx ? MD_FS_STAT_MOUNT : 0, &out);
        if (r==-EREMOTE) return md_namespace_host_call(fs,fs->image ? fs->image->executable_path : "",nr,a,1,out.data);
        if (r == -EXDEV && !*first && (flags & AT_EMPTY_PATH))
            return nr==SYS_statx ? md_namespace_native_statx(fs,a)
                : md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
        if (r < 0)
            return r;
        if (nr == SYS_newfstatat) {
            struct stat st;
            stat_info(&out.result.info, &st);
            return md_write_memory((void *)a[2], &st, sizeof(st));
        }
        struct statx st = {0};
        st.stx_mask = STATX_BASIC_STATS;
        if (out.result.info.mount_id) {
            st.stx_mask |= STATX_MNT_ID;
            st.stx_mnt_id=out.result.info.mount_id;
        }
        st.stx_blksize = out.result.info.block_size;
        st.stx_nlink = (uint32_t)out.result.info.links;
        st.stx_uid = out.result.info.uid;
        st.stx_gid = out.result.info.gid;
        st.stx_mode = (uint16_t)out.result.info.mode;
        st.stx_ino = out.result.info.inode;
        st.stx_size = out.result.info.size;
        st.stx_blocks = out.result.info.blocks;
        st.stx_atime.tv_sec = out.result.info.access_seconds;
        st.stx_atime.tv_nsec = out.result.info.access_nanos;
        st.stx_mtime.tv_sec = out.result.info.modify_seconds;
        st.stx_mtime.tv_nsec = out.result.info.modify_nanos;
        st.stx_ctime.tv_sec = out.result.info.change_seconds;
        st.stx_ctime.tv_nsec = out.result.info.change_nanos;
        st.stx_dev_major = major(out.result.info.device);
        st.stx_dev_minor = minor(out.result.info.device);
        st.stx_rdev_major = major(out.result.info.rdev);
        st.stx_rdev_minor = minor(out.result.info.rdev);
        return md_write_memory((void *)a[4], &st, sizeof(st));
    }
    case SYS_mkdirat:
        r = md_namespace_creation_mode(0777);
        if (r < 0)
            return r;
        q.operation = MD_FS_MKDIR;
        q.mode = (uint32_t)a[2] & 07777U;
        q.attributes.creation_mask=0777U^(unsigned)r;
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
        if (r==-EREMOTE) return md_namespace_host_call(fs,fs->image ? fs->image->executable_path : "",nr,a,1,out.data);
        if (r==-EXDEV && !*first) return RAW4(readlinkat,base,first,a[2],a[3]);
        if (r < 0)
            return r;
        if (out.result.size > a[3])
            out.result.size = (uint32_t)a[3];
        r = md_write_memory((void *)a[2], out.data, out.result.size);
        return r < 0 ? r : out.result.size;
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
        if (*first) {
            q.operation = MD_FS_ACCESS; q.mode = (unsigned)a[2]; q.flags = flags & ~AT_EMPTY_PATH;
            break;
        }
        if (!(flags & AT_EMPTY_PATH)) return -ENOENT;
        r = metadata_request(fs, nr, base, a[2], 0, flags & ~AT_EMPTY_PATH);
        if (r == -EXDEV) r = RAW4(faccessat2, base, "", a[2], flags);
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
        if (nr != SYS_statfs && nr != SYS_truncate) {
            r = metadata_request(fs, nr, (int)fd, a[2], a[3], flags & ~AT_EMPTY_PATH);
            if (r != -EXDEV) { if (!borrowed) RAW1(close, fd); return r; }
            long next = md_namespace_mutable(fs, (int)fd);
            if (!borrowed) RAW1(close, fd);
            fd = next; borrowed = 0;
            if (fd < 0) return fd;
        }
        switch (nr) {
        case SYS_fchmodat: {
            r = md_fd_chmod((int)fd, (unsigned)a[2]);
            break;
        }
        case SYS_fchmodat2:
            r = flags & AT_SYMLINK_NOFOLLOW
                ? RAW4(fchmodat2, fd, "", a[2], flags | AT_EMPTY_PATH)
                : md_fd_chmod((int)fd, (unsigned)a[2]);
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
    r=md_namespace_request(fs, &q, &out);
    if (r==-EREMOTE) return md_namespace_host_call(fs,fs->image ? fs->image->executable_path : "",
        nr,a,nr==SYS_symlinkat ? 2 : 1,out.data);
    return r;
}
