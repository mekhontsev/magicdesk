#include "namespace_internal.h"
#include "fs_path_call.h"
#include "raw.h"
#include "linux_abi.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/mount.h>
#include <sys/syscall.h>

long md_namespace_mount_fd(const struct md_fs *fs,long nr,const unsigned long *a) {
    struct md_fs_request q={.directory={-1,-1}};
    char key[PATH_MAX],value[PATH_MAX];
    long r=0;
    if (nr==SYS_open_tree || nr==SYS_open_tree_attr) {
        struct mount_attr attr;
        if (nr==SYS_open_tree_attr) {
            if (!a[3] && a[4]) return -EINVAL;
            if (a[3]) {
                r=md_read_struct(&attr,sizeof(attr),(const void *)a[3],a[4]);
                if (r<0) return r;
                q.value=&attr; q.value_size=sizeof(attr);
            }
        }
        unsigned flags=(unsigned)a[2];
        if (flags&~(OPEN_TREE_CLONE|OPEN_TREE_CLOEXEC|AT_EMPTY_PATH|AT_NO_AUTOMOUNT
                |AT_SYMLINK_NOFOLLOW|AT_RECURSIVE)) return -EINVAL;
        if ((flags&AT_RECURSIVE) && !(flags&OPEN_TREE_CLONE)) return -EINVAL;
        r=md_read_string(key,sizeof(key),(const char *)a[1]);
        if (r<0) return r;
        if (!*key && !(flags&AT_EMPTY_PATH)) return -ENOENT;
        unsigned opened=O_PATH|((flags&OPEN_TREE_CLOEXEC) ? O_CLOEXEC : 0);
        if (!(flags&OPEN_TREE_CLONE) && !q.value_size) {
            if (flags&AT_SYMLINK_NOFOLLOW) opened|=O_NOFOLLOW;
            if (!*key && (int)a[0]!=AT_FDCWD) return md_namespace_proc_reopen(fs,(int)a[0],opened);
            return md_namespace_open(fs,(int)a[0],*key ? key : ".",opened,0);
        }
        q.operation=MD_FS_OPEN_TREE; q.directory[0]=(int)a[0]; q.path[0]=key;
        q.flags=opened; q.mode=flags&(OPEN_TREE_CLONE|AT_RECURSIVE|AT_SYMLINK_NOFOLLOW);
        return md_namespace_paths_request(fs,&q);
    }
    if (nr==SYS_mount_setattr) {
        unsigned flags=(unsigned)a[2];
        if (flags&~(AT_EMPTY_PATH|AT_RECURSIVE|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT)) return -EINVAL;
        struct mount_attr attr;
        r=md_read_struct(&attr,sizeof(attr),(const void *)a[3],a[4]);
        if (r<0) return r;
        q.operation=MD_FS_MOUNT; q.mode=MD_FS_MOUNT_SETATTR; q.flags=flags;
        q.value=&attr; q.value_size=sizeof(attr);
        /* A no-op still checks namespace authority but does not look up a path. */
        if (!attr.attr_set && !attr.attr_clr && !attr.propagation) q.path[0]="/";
        else {
            r=md_read_string(key,sizeof(key),(const char *)a[1]);
            if (r<0) return r;
            if (!*key && !(flags&AT_EMPTY_PATH)) return -ENOENT;
            q.directory[0]=(int)a[0]; q.path[0]=key;
        }
        return md_namespace_paths_request(fs,&q);
    }
    if (nr==SYS_move_mount) {
        unsigned flags=(unsigned)a[4];
        if (flags&~MOVE_MOUNT__MASK) return -EINVAL;
        q.operation=MD_FS_MOUNT; q.mode=MD_FS_MOUNT_MOVE_FD; q.flags=flags;
        q.directory[0]=(int)a[2]; q.directory[1]=(int)a[0];
        r=md_read_string(key,sizeof(key),(const char *)a[3]);
        if (r>=0) r=md_read_string(value,sizeof(value),(const char *)a[1]);
        if (r<0) return r;
        if ((!*key && !(flags&MOVE_MOUNT_T_EMPTY_PATH)) || (!*value && !(flags&MOVE_MOUNT_F_EMPTY_PATH))) return -ENOENT;
        q.path[0]=key; q.path[1]=value;
        return md_namespace_paths_request(fs,&q);
    }
    if (nr==SYS_fspick) {
        unsigned flags=(unsigned)a[2];
        if (flags&~(FSPICK_CLOEXEC|FSPICK_SYMLINK_NOFOLLOW|FSPICK_NO_AUTOMOUNT|FSPICK_EMPTY_PATH)) return -EINVAL;
        r=md_read_string(key,sizeof(key),(const char *)a[1]);
        if (r<0) return r;
        if (!*key && !(flags&FSPICK_EMPTY_PATH)) return -ENOENT;
        q.operation=MD_FS_FSPICK; q.directory[0]=(int)a[0]; q.path[0]=key;
        q.flags=O_RDWR|((flags&FSPICK_CLOEXEC) ? O_CLOEXEC : 0);
        q.mode=(flags&FSPICK_SYMLINK_NOFOLLOW) ? AT_SYMLINK_NOFOLLOW : 0;
        return md_namespace_paths_request(fs,&q);
    } else if (nr==SYS_fsopen) {
        r=md_fs_fsopen_parameters(&q,(unsigned)a[1]);
        if (r<0) return r;
        r=md_read_string(key,sizeof(key),(const char *)a[0]);
        q.path[0]=key;
    } else if (nr==SYS_fsmount) {
        if ((unsigned)a[1]&~FSMOUNT_CLOEXEC) return -EINVAL;
        q.operation=MD_FS_FSMOUNT; q.directory[0]=(int)a[0]; q.mode=(unsigned)a[2];
        q.flags=O_PATH|((a[1]&FSMOUNT_CLOEXEC) ? O_CLOEXEC : 0);
    } else {
        q.operation=MD_FS_FSCONFIG; q.directory[0]=(int)a[0]; q.mode=(unsigned)a[1];
        if (q.directory[0]<0) return -EINVAL;
        switch (q.mode) {
        case FSCONFIG_SET_FLAG:
            if (!a[2] || a[3] || (int)a[4]) return -EINVAL;
            break;
        case FSCONFIG_SET_STRING:
            if (!a[2] || !a[3] || (int)a[4]) return -EINVAL;
            r=md_read_string(value,sizeof(value),(const char *)a[3]);
            if (r>=0) { q.value=value; q.value_size=(unsigned)md_length(value)+1; }
            break;
        case FSCONFIG_SET_FD:
            if (!a[2] || a[3]) return -EINVAL;
            if ((int)a[4]<0) return -EBADF;
            q.directory[1]=(int)a[4];
            break;
        case FSCONFIG_CMD_CREATE: case FSCONFIG_CMD_RECONFIGURE:
            if (a[2] || a[3] || (int)a[4]) return -EINVAL;
            break;
        default: return -EOPNOTSUPP;
        }
        if (r>=0 && a[2]) {
            r=md_read_string(key,sizeof(key),(const char *)a[2]); q.path[0]=key;
        }
    }
    if (r<0) return r;
    struct md_fs_response out;
    r=md_fs_call(fs->endpoint,5000,&q,&out);
    if (r>=0) r=out.result.error;
    if (r<0 || nr==SYS_fsconfig) return r;
    return md_namespace_open_result(&out.result,(int)q.flags);
}
