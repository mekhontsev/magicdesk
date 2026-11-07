#ifndef MD_FS_SYSCALL_H
#define MD_FS_SYSCALL_H
#include "fs_path_call.h"
#include "fs_xattr_call.h"
#include "fs_syscall_strings.h"
#include "linux_abi.h"
#include <linux/stat.h>
#include <time.h>

/* Scalar syscall decoding has no transport or task-memory ownership. Both
 * notification and cooperative entry capture the operands described here. */
enum md_fs_copy_kind { MD_FS_COPY_NONE, MD_FS_COPY_STAT, MD_FS_COPY_STATX,
    MD_FS_COPY_BYTES, MD_FS_COPY_LINK, MD_FS_COPY_FD, MD_FS_COPY_STATFS, MD_FS_COPY_XATTR };
struct md_fs_syscall {
    uintptr_t path[2], output, times;
    enum md_fs_copy_kind copy;
    unsigned operands;
};
static inline int md_fs_syscall_decode(struct md_fs_syscall *s,struct md_fs_request *q,
        long nr,const uint64_t *a,size_t capacity) {
    *s=(struct md_fs_syscall){.operands=1};
    *q=(struct md_fs_request){.directory={(int)a[0],-1}};
    unsigned strings=md_fs_syscall_strings(nr);
    for (unsigned i=0;i<2;++i,strings>>=3)
        if (strings&7) s->path[i]=a[(strings&7)-1];
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=a[i];
    unsigned paths=md_fs_path_call(q,nr,args);
    if (paths) {
        s->operands=paths;
        if (nr==SYS_openat) s->copy=MD_FS_COPY_FD;
        return 1;
    }
    int xattr=md_fs_xattr_call(q,nr,args);
    if (xattr) {
        if (xattr<0) return xattr;
        int list=q->mode==MD_FS_XATTR_LIST;
        s->copy=MD_FS_COPY_XATTR; s->output=list ? a[1] : a[2];
        return 1;
    }
    switch (nr) {
    case SYS_fsopen: {
        int error=md_fs_fsopen_parameters(q,(unsigned)a[1]);
        q->directory[0]=-1;
        s->operands=0; s->copy=MD_FS_COPY_FD;
        return error ? error : 1;
    }
    case SYS_newfstatat: case SYS_statx: {
        unsigned flags=(unsigned)a[nr==SYS_statx ? 2 : 3];
        if (nr==SYS_statx) {
            if ((flags&AT_STATX_SYNC_TYPE)==AT_STATX_SYNC_TYPE || (a[3]&STATX__RESERVED)) return -EINVAL;
            flags&=~AT_STATX_SYNC_TYPE;
        }
        if (flags&~(AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT)) return -EINVAL;
        q->operation=MD_FS_STAT; q->flags=flags;
        q->mode=nr==SYS_statx ? MD_FS_STAT_MOUNT : 0;
        s->copy=nr==SYS_statx ? MD_FS_COPY_STATX : MD_FS_COPY_STAT;
        s->output=a[nr==SYS_statx ? 4 : 2]; return 1;
    }
    case SYS_fstat:
        q->operation=MD_FS_FSTAT; s->copy=MD_FS_COPY_STAT; s->output=a[1]; return 1;
    case SYS_statfs: case SYS_fstatfs:
        q->operation=MD_FS_STATFS;
        if (nr==SYS_statfs) q->directory[0]=AT_FDCWD;
        s->copy=MD_FS_COPY_STATFS; s->output=a[1]; return 1;
    case SYS_fchmod: case SYS_fchown:
        q->operation=nr==SYS_fchmod ? MD_FS_CHMOD : MD_FS_CHOWN;
        q->mode=(unsigned)a[1];
        q->attributes.uid=(unsigned)a[1]; q->attributes.gid=(unsigned)a[2]; return 1;
    case SYS_utimensat:
        if (a[3]&~(AT_SYMLINK_NOFOLLOW|AT_EMPTY_PATH)) return -EINVAL;
        if (!a[1] && (a[3]&AT_SYMLINK_NOFOLLOW)) return -EINVAL;
        q->operation=MD_FS_UTIMENS; q->flags=(unsigned)a[3]; s->times=a[2];
        q->attributes.nanos[0]=q->attributes.nanos[1]=UTIME_NOW; return 1;
    case SYS_getdents64:
        q->operation=MD_FS_GETDENTS; q->capacity=a[2]>capacity ? capacity : a[2];
        s->copy=MD_FS_COPY_BYTES; s->output=a[1]; return 1;
    case SYS_readlinkat:
        if ((int)a[3]<=0) return -EINVAL;
        q->operation=MD_FS_READLINK; q->capacity=a[3]>capacity ? capacity : a[3];
        s->copy=MD_FS_COPY_LINK; s->output=a[2]; return 1;
    case SYS_symlinkat:
        q->operation=MD_FS_SYMLINK; q->directory[0]=(int)a[1];
        return 1;
    case SYS_faccessat: case SYS_faccessat2:
        q->operation=MD_FS_ACCESS; q->mode=(unsigned)a[2]; q->flags=nr==SYS_faccessat2 ? a[3] : 0;
        if (q->flags&~(MD_AT_EACCESS|AT_SYMLINK_NOFOLLOW|AT_EMPTY_PATH)) return -EINVAL;
        return 1;
    case SYS_fchmodat: case SYS_fchmodat2: case SYS_fchownat:
        q->operation=nr==SYS_fchownat ? MD_FS_CHOWN : MD_FS_CHMOD;
        q->flags=nr==SYS_fchmodat ? 0 : a[nr==SYS_fchownat ? 4 : 3];
        if (q->flags&~(AT_SYMLINK_NOFOLLOW|AT_EMPTY_PATH)) return -EINVAL;
        q->mode=(unsigned)a[2]; q->attributes.uid=(unsigned)a[2]; q->attributes.gid=(unsigned)a[3];
        return 1;
    default: return 0;
    }
}
#endif
