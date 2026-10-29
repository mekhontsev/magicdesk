#define _GNU_SOURCE
#include "fs_operation.h"
#include "ipc_credentials.h"
#include "posix_acl.h"
#include "bpf_request.h"
#include "proc_paths.h"
#include "filesystem_ioctl.h"
#include "inode_store.h"
#include "fuse_options.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/limits.h>
#include <linux/mount.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

const char *md_fs_operation_name(unsigned operation) {
    static const char *const names[MD_FS_LAST+1]={
#define NAME(n) [MD_FS_##n]=#n
        NAME(CREATE), NAME(OPEN), NAME(MKDIR), NAME(SYMLINK), NAME(READLINK),
        NAME(LINK), NAME(UNLINK), NAME(RENAME), NAME(STAT), NAME(FSTAT), NAME(PATH),
        NAME(GETDENTS), NAME(SEEKDIR), NAME(SOCKET_BIND), NAME(SOCKET_ADDRESS), NAME(SOCKET_NAME),
        NAME(REALPATH), NAME(TEMPORARY), NAME(OBJECT_ID), NAME(OPEN_OBJECT), NAME(OPEN_IMAGE),
        NAME(WATCH_CREATE), NAME(WATCH_ADD), NAME(WATCH_REMOVE), NAME(WATCH_BYTES),
        NAME(WATCH_CONTAINS), NAME(WATCH_READ), NAME(REOPEN), NAME(CHMOD), NAME(CHOWN),
        NAME(ACCESS), NAME(UTIMENS), NAME(IPC), NAME(GETCAP), NAME(SETCAP), NAME(REMOVECAP),
        NAME(GETACL), NAME(SETACL), NAME(REMOVEACL), NAME(XATTR_OBJECT), NAME(MOUNT_TABLE),
        NAME(NATIVE_MOUNT), NAME(MKNOD), NAME(CHROOT), NAME(UMASK), NAME(STATFS), NAME(WATCH_PATH),
        NAME(TASK_PATH), NAME(XATTR), NAME(FXATTR), NAME(IPC_PEER), NAME(MOUNT), NAME(FSOPEN),
        NAME(FSCONFIG), NAME(FSMOUNT), NAME(BPF), NAME(FDINFO), NAME(LANDLOCK), NAME(OPEN_TREE),
        NAME(IOCTL), NAME(SYNC), NAME(SHM_CHANNEL), NAME(FSPICK)
#undef NAME
    };
    return md_fs_operation_valid(operation) ? names[operation] : NULL;
}

int md_fs_request_validate(const struct md_fs_request *q) {
    if (!q) return -EINVAL;
    if (q->operation==MD_FS_UNLINK && (q->flags&~AT_REMOVEDIR)) return -EINVAL;
    unsigned descriptors=0;
    for (unsigned i=0;i<2;++i) {
        if (q->capture[i].path) {
            struct md_proc_path proc=md_proc_path_for(q->capture[i].path,0,0);
            int fdinfo=q->operation==MD_FS_FDINFO && !i
                && proc.kind==MD_PROC_FOREIGN && proc.foreign_kind==MD_PROC_FDINFO
                && !*proc.tail && q->capture[i].flags==O_CLOEXEC;
            int mounts=q->operation==MD_FS_MOUNT_TABLE && !i
                && proc.kind==MD_PROC_FOREIGN
                && proc.foreign_kind==(q->mode ? MD_PROC_MOUNTINFO : MD_PROC_MOUNTS)
                && !*proc.tail && q->capture[i].flags==O_CLOEXEC;
            if (q->directory[i]!=-1 || q->object[i] || q->origin[i]
                    || (md_fs_operation_data_inputs(q->operation)&(1U<<i))
                    || !*q->capture[i].path || strnlen(q->capture[i].path,PATH_MAX)==PATH_MAX
                    || (!(q->capture[i].flags&O_PATH) && !fdinfo && !mounts)
                    || q->capture[i].flags&~(O_PATH|O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW)) return -EINVAL;
            descriptors|=1U<<i;
        } else if (q->capture[i].flags) return -EINVAL;
        if (q->object[i]) {
            if (q->directory[i]!=-1 || q->origin[i] || !md_fs_object_id_valid(q->object[i])
                    || (md_fs_operation_data_inputs(q->operation)&(1U<<i))) return -EINVAL;
            descriptors|=1U<<i;
        }
        if (q->directory[i]<0 && q->directory[i]!=-1 && q->directory[i]!=AT_FDCWD) return -EBADF;
        if (q->directory[i]==AT_FDCWD && (md_fs_operation_data_inputs(q->operation)&(1U<<i))) return -EBADF;
        if (q->directory[i]!=-1) descriptors|=1U<<i;
    }
    const char *a=q->path[0] ? q->path[0] : "", *b=q->path[1] ? q->path[1] : "";
    if (!md_fs_operation_valid(q->operation)) return -ENOTSUP;
    if ((q->attributes.creation_mask & ~0777U)
            && !(q->attributes.creation_mask==MD_FS_CAPTURE_UMASK
                && (q->operation==MD_FS_MKDIR || (q->operation==MD_FS_OPEN
                    && (q->flags&O_CREAT) && !(q->flags&O_PATH))))) return -EINVAL;
    if (q->resolve && q->operation != MD_FS_OPEN) return -EINVAL;
    if (q->value_size>MD_FS_VALUE_MAX || (q->value_size && !q->value)
            || (q->value_size && q->operation!=MD_FS_XATTR && q->operation!=MD_FS_FXATTR && q->operation!=MD_FS_XATTR_OBJECT
                && q->operation!=MD_FS_SETACL && q->operation!=MD_FS_MOUNT
                && q->operation!=MD_FS_FSCONFIG && q->operation!=MD_FS_BPF && q->operation!=MD_FS_LANDLOCK)) return -EINVAL;
    if (q->operation==MD_FS_LANDLOCK) {
        unsigned expected=q->mode==MD_LL_CREATE ? 0 : q->mode==MD_LL_ADD ? 3 : 1;
        return q->mode<MD_LL_CREATE || q->mode>MD_LL_RESTRICT || descriptors!=expected
            || *a || *b || q->offset || q->capacity || q->value_size!=sizeof(uint64_t)
            || q->capture[0].path || q->capture[1].path || q->object[0] || q->object[1]
            || (expected && q->directory[0]<0) || (expected==3 && q->directory[1]<0)
            || q->flags!=(q->mode==MD_LL_CREATE ? O_RDWR|O_CLOEXEC : 0) ? -EINVAL : 0;
    }
    if (q->operation==MD_FS_BPF) {
        if (*a || *b || q->offset || q->value_size<sizeof(struct md_bpf_request)
                || q->capacity>MD_FS_VALUE_MAX || q->capture[0].path || q->capture[1].path
                || q->object[0] || q->object[1]) return -EINVAL;
        struct md_bpf_request in; memcpy(&in,q->value,sizeof(in));
        unsigned expected=md_bpf_opens(q->mode) ? 0
            : q->mode==BPF_PROG_QUERY || q->mode==BPF_OBJ_GET_INFO_BY_FD || md_bpf_element(q->mode) ? 1 : 3;
        if (descriptors!=expected || (expected && q->directory[0]<0)
                || (expected==3 && q->directory[1]<0)
                || q->flags!=(md_bpf_opens(q->mode) ? O_RDWR|O_CLOEXEC : 0)) return -EINVAL;
        if (q->mode==BPF_PROG_LOAD)
            return !in.count || in.count>MD_BPF_INSTRUCTIONS_MAX || !md_bpf_program_type(in.type)
                || in.flags || in.id || in.key_size || in.value_size || q->capacity
                || q->value_size!=sizeof(in)+in.count*sizeof(struct bpf_insn)
                || strnlen(in.name,sizeof(in.name))==sizeof(in.name) ? -EINVAL : 0;
        if (q->mode==BPF_MAP_CREATE)
            return in.id || q->capacity || q->value_size!=sizeof(in) ? -EINVAL : 0;
        if (md_bpf_element(q->mode)) {
            unsigned bytes=q->mode==BPF_MAP_LOOKUP_ELEM ? in.value_size
                : q->mode==BPF_MAP_GET_NEXT_KEY ? in.key_size : 0;
            return in.id || in.type || in.name[0] || in.count>1
                || (!in.count && q->mode!=BPF_MAP_GET_NEXT_KEY)
                || !in.key_size || in.key_size>MD_BPF_MAP_KEY_MAX
                || !in.value_size || in.value_size>MD_BPF_MAP_VALUE_MAX
                || q->capacity!=bytes || q->value_size!=sizeof(in)+in.count*in.key_size
                    +(q->mode==BPF_MAP_UPDATE_ELEM ? in.value_size : 0) ? -EINVAL : 0;
        }
        if (q->value_size!=sizeof(in) || in.count || in.type || in.key_size || in.value_size || in.name[0]) return -EINVAL;
        if (q->mode==BPF_PROG_GET_FD_BY_ID || q->mode==BPF_MAP_GET_FD_BY_ID) return in.flags || q->capacity ? -EINVAL : 0;
        if (in.id) return -EINVAL;
        if (q->mode==BPF_OBJ_GET_INFO_BY_FD)
            return in.flags || q->capacity<sizeof(struct md_bpf_info)
                || q->capacity>sizeof(struct md_bpf_info)+MD_BPF_INSTRUCTIONS_MAX*sizeof(struct bpf_insn) ? -EINVAL : 0;
        if (q->mode==BPF_PROG_QUERY)
            return (in.flags&~BPF_F_QUERY_EFFECTIVE) || q->capacity<sizeof(struct md_bpf_query)
                || q->capacity>sizeof(struct md_bpf_query)+MD_BPF_QUERY_MAX*sizeof(uint32_t)
                || (q->capacity-sizeof(struct md_bpf_query))%sizeof(uint32_t) ? -EINVAL : 0;
        if (q->mode==BPF_PROG_ATTACH || q->mode==BPF_PROG_DETACH)
            return q->capacity || (in.flags&~BPF_F_ALLOW_MULTI)
                || (q->mode==BPF_PROG_DETACH && in.flags) ? -EINVAL : 0;
        return -EOPNOTSUPP;
    }
    if (q->operation==MD_FS_FSOPEN)
        return descriptors || !*a || *b || (q->flags&~(O_RDWR|O_CLOEXEC))
            || q->mode || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_FSPICK)
        return (!*a && !(descriptors&1)) || *b || (descriptors&2)
            || q->flags&~(O_RDWR|O_CLOEXEC) || (q->flags&O_ACCMODE)!=O_RDWR
            || q->mode&~AT_SYMLINK_NOFOLLOW || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_IOCTL)
        return descriptors!=1 || q->directory[0]<0 || *a || *b
            || (q->flags && !md_filesystem_ioctl_opens(q->mode))
            || q->capacity || q->offset || !md_filesystem_ioctl_command(q->mode) ? -EINVAL : 0;
    if (q->operation==MD_FS_SYNC)
        return descriptors!=1 || q->directory[0]<0 || *a || *b || q->flags
            || q->capacity || q->offset || q->mode>MD_INODE_SYNC_FILESYSTEM ? -EINVAL : 0;
    if (q->operation==MD_FS_OPEN_TREE)
        return (!*a && !(descriptors&1)) || *b || (descriptors&2)
            || q->flags&~(O_PATH|O_CLOEXEC) || !(q->flags&O_PATH)
            || q->mode&~(AT_RECURSIVE|AT_SYMLINK_NOFOLLOW) || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_FDINFO)
        return descriptors!=3 || (q->directory[0]<0 && !q->capture[0].path) || q->directory[1]<0 || *a || *b || q->mode || q->capacity
            || q->offset<0 || (q->flags&~(O_PATH|O_CLOEXEC)) ? -EINVAL : 0;
    if (q->operation==MD_FS_FSMOUNT)
        return descriptors!=1 || q->directory[0]<0 || *a || *b || (q->flags&~(O_PATH|O_CLOEXEC))
            || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_FSCONFIG) {
        if (descriptors!=(q->mode==FSCONFIG_SET_FD ? 3U : 1U) || q->directory[0]<0
                || (q->mode==FSCONFIG_SET_FD && q->directory[1]<0)
                || *b || q->flags || q->capacity || q->offset
                || q->value_size>PATH_MAX) return -EINVAL;
        if (q->mode==FSCONFIG_SET_FLAG || q->mode==FSCONFIG_SET_FD)
            return !*a || q->value_size ? -EINVAL : 0;
        if (q->mode==FSCONFIG_SET_STRING) return !*a || !q->value_size
            || strnlen(q->value,q->value_size)!=q->value_size-1 ? -EINVAL : 0;
        if (q->mode==FSCONFIG_CMD_CREATE || q->mode==FSCONFIG_CMD_RECONFIGURE)
            return *a || q->value_size ? -EINVAL : 0;
        return -EOPNOTSUPP;
    }
    if (q->operation==MD_FS_MOUNT) {
        int pair=q->mode==MD_FS_MOUNT_BIND || q->mode==MD_FS_MOUNT_PIVOT || q->mode==MD_FS_MOUNT_MOVE
            || q->mode==MD_FS_MOUNT_MOVE_FD;
        int fuse=q->mode==MD_FS_MOUNT_FUSE;
        if ((!*a && !(descriptors&1)) || q->mode<MD_FS_MOUNT_BIND || q->mode>MD_FS_MOUNT_BINFMT
                || q->capacity || q->offset || q->value_size>(fuse ? MD_FUSE_MOUNT_MAX : PATH_MAX)
                || (pair ? (!*b && !(descriptors&2)) : fuse ? *b || !(descriptors&2) : *b || (descriptors&2))
                || (q->mode!=MD_FS_MOUNT_TMPFS && q->mode!=MD_FS_MOUNT_RAMFS && q->mode!=MD_FS_MOUNT_OVERLAY && q->mode!=MD_FS_MOUNT_PROC
                    && q->mode!=MD_FS_MOUNT_DEVPTS && q->mode!=MD_FS_MOUNT_CGROUP
                    && q->mode!=MD_FS_MOUNT_CGROUP2 && q->mode!=MD_FS_MOUNT_SYSFS && q->mode!=MD_FS_MOUNT_MQUEUE
                    && q->mode!=MD_FS_MOUNT_BINFMT
                    && q->mode!=MD_FS_MOUNT_REMOUNT && q->mode!=MD_FS_MOUNT_SETATTR && !fuse && q->value_size)) return -EINVAL;
        if (q->mode==MD_FS_MOUNT_SETATTR)
            return q->value_size!=sizeof(struct mount_attr)
                || (q->flags&~(AT_EMPTY_PATH|AT_RECURSIVE|AT_SYMLINK_NOFOLLOW|AT_NO_AUTOMOUNT)) ? -EINVAL : 0;
        if (fuse) {
            struct md_fuse_mount mount;
            return md_fuse_mount_parse(q->value,q->value_size,&mount);
        }
        return q->value_size && strnlen(q->value,q->value_size)!=q->value_size-1 ? -EINVAL : 0;
    }
    if (q->operation==MD_FS_XATTR || q->operation==MD_FS_FXATTR || q->operation==MD_FS_XATTR_OBJECT) {
        int descriptor=q->operation!=MD_FS_XATTR, set=q->mode==MD_FS_XATTR_SET;
        return (descriptors&2) || (descriptor ? descriptors!=1 || *a : !*a && descriptors!=1)
            || q->mode<MD_FS_XATTR_GET || q->mode>MD_FS_XATTR_REMOVE
            || (q->mode==MD_FS_XATTR_LIST ? *b!=0 : !*b || strlen(b)>255)
            || (q->flags & ~((descriptor ? 0 : AT_SYMLINK_NOFOLLOW) | (set ? 3U : 0)))
            || q->offset || q->capacity>MD_FS_VALUE_MAX || (!set && q->value_size)
            || (set && q->value_size!=q->capacity)
            || (q->mode==MD_FS_XATTR_REMOVE && q->capacity) ? -EINVAL : 0;
    }
    if (q->operation==MD_FS_UMASK)
        return descriptors || *a || *b || q->flags || q->mode || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_SHM_CHANNEL)
        return descriptors || *a || *b || q->flags || q->mode || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_STATFS)
        return (descriptors&2) || (!*a && descriptors!=1) || *b || q->flags || q->mode || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_WATCH_PATH)
        return q->directory[1]<0 || !*a || *b || q->mode || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation==MD_FS_TASK_PATH)
        return (descriptors&2) || (!*a && descriptors!=1) || *b || q->flags || q->capacity
            || (q->mode!=MD_FS_TASK_CHDIR && q->mode!=MD_FS_TASK_TRUNCATE && q->mode!=MD_FS_TASK_FCHDIR)
            || (q->mode!=MD_FS_TASK_TRUNCATE && q->offset)
            || (q->mode==MD_FS_TASK_FCHDIR && (q->directory[0]<0 || *a || q->object[0])) ? -EINVAL : 0;
    if (q->operation==MD_FS_MKNOD)
        return (descriptors&2) || !*a || *b || q->flags || q->capacity || q->offset<0 || q->offset>UINT32_MAX
            || (q->mode&~(S_IFMT|07777U))
            || ((q->mode&S_IFMT)!=S_IFREG && (q->mode&S_IFMT)!=S_IFIFO
                && (q->mode&S_IFMT)!=S_IFCHR && (q->mode&S_IFMT)!=S_IFBLK) ? -EINVAL : 0;
    if (q->operation==MD_FS_MOUNT_TABLE)
        return (descriptors&2) || q->directory[0]==AT_FDCWD || *a || *b || (q->flags&~(O_PATH|O_CLOEXEC)) || q->mode>1
            || q->capacity || q->offset || q->resolve ? -EINVAL : 0;
    if (q->operation==MD_FS_NATIVE_MOUNT)
        return descriptors || *a || *b || q->flags || q->mode || q->capacity || q->offset<=0 || q->resolve ? -EINVAL : 0;
    if (q->operation>=MD_FS_GETACL && q->operation<=MD_FS_REMOVEACL) {
        int set=q->operation==MD_FS_SETACL;
        return descriptors!=1 || *a || *b || q->offset
            || (q->mode!=MD_ACL_ACCESS && q->mode!=MD_ACL_DEFAULT)
            || q->capacity>MD_ACL_MAX
            || (set ? (q->capacity && q->capacity<4) || q->value_size!=q->capacity || (q->flags&~3U)
                : q->flags || (q->operation==MD_FS_REMOVEACL && q->capacity)) ? -EINVAL : 0;
    }
    if (q->operation==MD_FS_IPC_PEER)
        return descriptors!=1 || *a || *b || q->flags || q->mode>1 || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation == MD_FS_IPC)
        return (descriptors != 1 && !((q->flags == MD_IPC_PAIR || q->flags == MD_IPC_MESSAGE_READ) && descriptors == 3))
            || *b || q->mode || q->capacity || q->flags < MD_IPC_LISTEN_BEGIN
            || (q->flags > MD_IPC_MESSAGE_READ && q->flags!=MD_IPC_BIND) ? -EINVAL : 0;
    if (q->operation == MD_FS_GETCAP || q->operation == MD_FS_SETCAP || q->operation == MD_FS_REMOVECAP)
        return descriptors != 1 || *a || *b || q->mode || q->offset
            || (q->operation != MD_FS_SETCAP && (q->capacity || q->flags))
            || q->capacity > MD_FILE_CAPABILITY_MAX ? -EINVAL : 0;
    if ((q->capacity && q->operation != MD_FS_GETDENTS && q->operation != MD_FS_WATCH_READ) || q->capacity > PATH_MAX
            || (q->offset && q->operation != MD_FS_SEEKDIR)) return -EINVAL;
    if (q->operation == MD_FS_REOPEN)
        return descriptors != 1 || *a || *b || (q->mode&~(MD_FS_REOPEN_MUTABLE|MD_FS_REOPEN_NATIVE|MD_FS_REOPEN_EXECUTE)) ? -EINVAL : 0;
    if (q->operation == MD_FS_ACCESS)
        return (descriptors & 2) || (!*a && descriptors != 1) || *b ? -EINVAL : 0;
    if (q->operation >= MD_FS_CHMOD && q->operation <= MD_FS_UTIMENS)
        return (descriptors & 2) || (!*a && descriptors != 1) || *b
            || (q->flags & ~AT_SYMLINK_NOFOLLOW) ? -EINVAL : 0;
    if (q->operation >= MD_FS_WATCH_CREATE && q->operation <= MD_FS_WATCH_READ) {
        unsigned expected = q->operation == MD_FS_WATCH_CREATE ? 0 : q->operation == MD_FS_WATCH_ADD ? 3 : 1;
        if (descriptors != expected || *a || *b || q->mode) return -EINVAL;
        if (q->flags && q->operation != MD_FS_WATCH_CREATE && q->operation != MD_FS_WATCH_ADD
                && q->operation != MD_FS_WATCH_REMOVE) return -EINVAL;
        return 0;
    }
    if (q->operation == MD_FS_TEMPORARY)
        return descriptors || *a || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OBJECT_ID)
        return descriptors != 1 || *a || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OPEN_OBJECT)
        return descriptors || !*a || *b || (q->mode&~MD_FS_OBJECT_EXECUTE) ? -EINVAL : 0;
    if (q->operation == MD_FS_OPEN_IMAGE)
        return (descriptors & 2) || !*a || *b || (q->mode&~MD_FS_IMAGE_INTERPRETER) || (q->flags & ~O_NOFOLLOW) ? -EINVAL : 0;
    if (q->operation == MD_FS_GETDENTS || q->operation == MD_FS_SEEKDIR) {
        if (descriptors != 1 || *a || *b || q->mode) return -EINVAL;
        return q->operation == MD_FS_GETDENTS ? (q->flags ? -EINVAL : 0)
            : (q->flags == SEEK_SET || q->flags == SEEK_CUR ? 0 : -EINVAL);
    }
    if (q->operation == MD_FS_SOCKET_BIND)
        return !(descriptors & 2) || !*a || q->flags || (q->mode & ~0777) ? -EINVAL : 0;
    if (q->operation == MD_FS_SOCKET_ADDRESS)
        return (descriptors & 2) || (!*a && !(descriptors & 1)) || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_SOCKET_NAME)
        return (descriptors & 2) || !*a || *b || q->flags || q->mode
            || (q->operation == MD_FS_SOCKET_NAME && descriptors) ? -EINVAL : 0;
    int binary = q->operation == MD_FS_LINK || q->operation == MD_FS_RENAME;
    int proc_leaf=q->operation==MD_FS_OPEN && (q->flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW);
    if ((!binary && !proc_leaf && (descriptors & 2))
            || (!binary && q->operation != MD_FS_SYMLINK && *b)
            || (q->mode && q->operation != MD_FS_CREATE && q->operation != MD_FS_MKDIR && q->operation != MD_FS_OPEN
                && !((q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)
                    && !(q->mode&~(MD_FS_STAT_MOUNT|MD_FS_STAT_CANONICAL)))
                && !(q->operation==MD_FS_PATH && q->mode==MD_FS_PATH_DISPLAY))
            || (q->flags && q->operation != MD_FS_OPEN && q->operation != MD_FS_LINK
                && q->operation != MD_FS_RENAME && q->operation != MD_FS_UNLINK && q->operation != MD_FS_STAT)
            || ((q->operation == MD_FS_FSTAT || q->operation == MD_FS_PATH) && *a)
            || (q->operation == MD_FS_FSTAT && !(descriptors & 1))) return -EINVAL;
    return 0;
}
