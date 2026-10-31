#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_native.h"
#include "inode_internal.h"
#include "image_catalogue.h"
#include "fs_storage.h"
#include "linux_abi.h"
#include "procfs_view.h"
#include "filesystem_ioctl.h"
#include "fs_continuation.h"
#include "fuse_view.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <linux/magic.h>
#include <sys/sysmacros.h>
#include <sys/ioctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static void inode(struct md_filesystem *, struct md_mount_namespace *, struct md_view_mount *,
        const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
static int writable(unsigned flags) {
    return !(flags & O_PATH) && (flags & (O_ACCMODE | O_TRUNC | O_CREAT));
}
static int special(mode_t mode) {
    return S_ISCHR(mode) || S_ISBLK(mode) || S_ISFIFO(mode) || S_ISSOCK(mode);
}
static int readonly_data(const struct md_view_mount *mount,int fd) {
    if (!md_view_readonly(mount)) return 0;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    return special(st.st_mode) ? 0 : -EROFS;
}
static int device_open(const struct md_view_mount *mount, int flags, mode_t mode) {
    return !(flags&O_PATH) && (S_ISCHR(mode) || S_ISBLK(mode))
        && (md_mount_policy_flags(&mount->policy)&MD_MOUNT_NODEV) ? -EACCES : 0;
}
static int execute_access(const struct md_view_mount *mount, int fd, unsigned mode) {
    if (!(mode&X_OK) || !mount || !(md_mount_policy_flags(&mount->policy)&MD_MOUNT_NOEXEC)) return 0;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    return S_ISREG(st.st_mode) ? -EACCES : 0;
}
static int reopen_admitted(int fd, struct md_view_mount *mount, int flags,
        struct md_open_completion *completion) {
    int opened=md_reopen_file(fd,flags,completion);
    if ((opened==-EACCES || opened==-EPERM) && mount->backing==MD_MOUNT_PROC) {
        /* Proc access depends on the caller's task and dumpability. Retain
         * the admitted object and mount; complete the kernel open in that
         * caller, through the same ownership path as controlling terminals. */
        opened=fcntl(fd,F_DUPFD_CLOEXEC,3);
        if (opened<0) return -errno;
        completion->kind=MD_OPEN_CALLER;
    }
    return opened;
}
static int reopen(struct md_view_object *p, int flags, int mutable,
        struct md_open_completion *completion) {
    if (mutable && md_view_readonly(p->mount)) return -EROFS;
    struct stat st;
    if (fstat(p->fd,&st)) return -errno;
    if (md_view_readonly(p->mount) && writable(flags) && !special(st.st_mode)) return -EROFS;
    int error=device_open(p->mount,flags,st.st_mode);
    return error ? error : reopen_admitted(p->fd,p->mount,flags,completion);
}
static void result_stat(struct md_fs_result *out, int fd, const struct md_view_mount *mount) {
    struct stat st;
    if (fstat(fd, &st)) out->error = -errno;
    else {
        md_fs_stat_info(&st, &out->info);
        out->info.mount_id=mount->id;
    }
}
static int record(struct md_filesystem *fs, int fd, struct md_view_mount *mount,
        struct md_fs_result *out, int named) {
    if (fd < 0) return -errno;
    struct md_view_object *object;
    int r = named ? md_view_record(fs, fd, mount, &object) : 0;
    if (r) close(fd); else out->fd = fd;
    return r;
}
static int devpts_operation(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (!mount || !mount->devpts) return 0;
    const struct md_identity *id=fs->store->identity;
    int r=0;
    switch (q->operation) {
    case MD_FS_IOCTL: {
        if (!md_filesystem_ioctl_opens(q->mode)) return 0;
        /* The kernel selects the actual peer without a path lookup, even when
         * the master arrived from another mount namespace through SCM_RIGHTS. */
        r=ioctl(fd,TIOCGPTPEER,q->flags|O_NOCTTY|O_CLOEXEC);
        if (r<0) { r=-errno; break; }
        struct stat st;
        int error=md_devpts_stat(mount->devpts,r,&st);
        if (error) { close(r); r=error; break; }
        out->fd=r; r=0;
        if (!(q->flags&(O_PATH|O_NOCTTY))) out->open_completion.kind=MD_OPEN_CALLER;
        md_view_capture(fs,view,mount,out,output);
        break;
    }
    case MD_FS_STATFS:
        md_fs_statfs_result(fd,md_mount_policy_flags(&mount->policy),NULL,out,output);
        if (!out->error) ((struct md_fs_statfs *)output->data)->type=DEVPTS_SUPER_MAGIC;
        break;
    case MD_FS_STAT: case MD_FS_FSTAT: {
        struct stat st; r=md_devpts_stat(mount->devpts,fd,&st);
        if (!r) { md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id; }
        break;
    }
    case MD_FS_ACCESS:
        if (q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) r=-EINVAL;
        else if ((q->mode&W_OK) && (r=readonly_data(mount,fd))) break;
        else {
            struct md_identity actor=md_identity_access(id,!!(q->flags&MD_AT_EACCESS));
            r=md_devpts_access(mount->devpts,fd,q->mode,&actor);
        }
        break;
    case MD_FS_CHMOD:
        r=md_view_readonly(mount) ? -EROFS : md_devpts_chmod(mount->devpts,fd,q->mode,id); break;
    case MD_FS_CHOWN:
        r=md_view_readonly(mount) ? -EROFS : md_devpts_chown(mount->devpts,fd,q->attributes.uid,q->attributes.gid,id); break;
    case MD_FS_OPEN: case MD_FS_REOPEN: {
        if (writable(q->flags) && (r=readonly_data(mount,fd))) break;
        if (!(q->flags&O_PATH) && (q->flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) { r=-EEXIST; break; }
        struct stat st;
        if (fstat(fd,&st)) { r=-errno; break; }
        r=device_open(mount,q->flags,st.st_mode); if (r) break;
        if (!(q->flags&(O_PATH|O_NOCTTY)) && S_ISCHR(st.st_mode)
                && !(major(st.st_rdev)==5 && minor(st.st_rdev)==2)) {
            /* Controlling-terminal acquisition belongs to the caller's session,
             * not to the namespace worker opening its retained path. */
            r=md_devpts_access(mount->devpts,fd,(q->flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK
                : (q->flags&O_ACCMODE)==O_WRONLY ? W_OK : R_OK,id);
            if (!r) {
                r=fcntl(fd,F_DUPFD_CLOEXEC,3);
                if (r<0) r=-errno;
                else out->open_completion.kind=MD_OPEN_CALLER;
            }
        } else r=md_devpts_open(mount->devpts,fd,q->flags,id);
        if (r>=0) { out->fd=r; r=0; md_view_capture(fs,view,mount,out,output); }
        break;
    }
    case MD_FS_MKDIR: case MD_FS_CREATE: case MD_FS_MKNOD: case MD_FS_SYMLINK:
    case MD_FS_UNLINK: case MD_FS_RENAME: case MD_FS_LINK: case MD_FS_UTIMENS:
        r=-EPERM; break;
    default: return 0;
    }
    if (r) out->error=r;
    return 1;
}
struct directory_context { struct md_filesystem *fs; struct md_view_mount *mount; };
static ssize_t directory_entries(void *context, int fd, void *data, size_t count) {
    struct directory_context *c=context;
    struct md_view_mount *mount=c->mount;
    if (mount->backing==MD_MOUNT_PROC) return md_procfs_getdents(c->fs,mount,fd,data,count);
    if (mount->devpts) return md_devpts_getdents(mount->devpts,fd,data,count);
    if (mount->sysfs) {
        int error=md_sysfs_refresh(mount->sysfs);
        if (error) return error;
    }
    if (mount->provider) return md_provider_tree_getdents(mount->provider,fd,data,count);
    ssize_t n=syscall(SYS_getdents64,fd,data,count);
    return n<0 ? -errno : n;
}
static int virtual_operation(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,int parent,const char *name,
        const struct md_fs_request *q,struct md_fs_result *out,const struct md_fs_output *output) {
    struct md_view_location location={.fd=fd,.parent=parent,.view=view,.mount=mount};
    if (name) snprintf(location.name,sizeof(location.name),"%s",name);
    return md_fs_fuse_operation(fs,q,&location,NULL,out,output)
        || md_fs_mount_file_operation(fs,view,mount,fd,q,out,output)
        || md_fs_proc_operation(fs,view,mount,fd,q,out,output)
        || devpts_operation(fs,view,mount,fd,q,out,output)
        || md_fs_cgroup_operation(fs,view,mount,fd,parent,name,q,out,output)
        || md_fs_sysfs_operation(fs,view,mount,fd,q,out,output)
        || md_fs_provider_operation(fs,view,mount,fd,q,out,output)
        || md_fs_mqueue_operation(fs,view,mount,fd,q,out,output);
}
static void mount_metadata(const struct md_view_mount *mount,int fd,
        const struct md_fs_request *q,struct md_fs_result *out) {
    if (!mount || out->error || !(q->mode&MD_FS_STAT_MOUNT)
            || (q->operation!=MD_FS_STAT && q->operation!=MD_FS_FSTAT)
            || (out->info.attributes_mask&STATX_ATTR_MOUNT_ROOT)) return;
    struct stat st;
    if (fstat(fd,&st)) out->error=-errno;
    else md_fs_info_mount(&out->info,mount->id,md_view_same(&st,&mount->native));
}
static int anchor_path(struct md_filesystem *fs,const struct md_fs_anchor *origin,void *data,size_t capacity) {
    if (!data || !capacity) return -ERANGE;
    int error=md_view_dentry_path(fs,origin->fd,origin->mount,origin->dentry,origin->native_leaf,data,capacity);
    if (!error && origin->child) {
        char *path=data;
        size_t length=strlen(path);
        int n=snprintf(path+length,capacity-length,"%s%s",
            length && path[length-1]=='/' ? "" : "/",origin->child);
        if (n<0 || (size_t)n>=capacity-length) error=-ERANGE;
    }
    return error;
}
static int descriptor(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    const struct md_fs_anchor *origin=q->origin[0];
    if (origin && origin->child) {
        if (q->operation==MD_FS_FSTAT && q->control.ops) {
            struct stat st;
            out->error=md_fs_proc_control_stat(fs,q->control,&st);
            if (!out->error) {
                md_fs_stat_info(&st,&out->info); md_fs_anchor_mount_info(origin,&out->info);
            }
            return 1;
        }
        if (q->operation!=MD_FS_PATH) { out->error=-EOPNOTSUPP; return 1; }
        if (!output || !output->data || !output->capacity) { out->error=-ERANGE; return 1; }
        out->error=anchor_path(fs,origin,output->data,output->capacity);
        if (!out->error) out->size=strlen(output->data)+1;
        return 1;
    }
    if (origin && q->operation==MD_FS_ACCESS) {
        int error=execute_access(origin->mount,q->directory[0],q->mode);
        if (error) { out->error=error; return 1; }
    }
    if (origin && md_view_inode(origin->mount)
            && !(!md_view_base(origin->view,origin->mount) && q->operation==MD_FS_OBJECT_ID)) return 0;
    struct md_filesystem owner=*fs;
    if (origin) owner.mounts=origin->view;
    struct md_view_object *p = NULL;
    struct md_view_object retained={0};
    int r=0;
    if (origin) {
        retained.fd=origin->fd; retained.mount=origin->mount; p=&retained;
        if (q->operation==MD_FS_OBJECT_ID) r=md_view_record(&owner,q->directory[0],origin->mount,&p);
    } else r=md_view_identity(fs,q->directory[0],&p);
    if (r == -EXDEV) return 0;
    if (r) { out->error = r; return 1; }
    if (!md_view_inode(p->mount) && (r=md_fs_landlock_native(fs,q,p->mount,q->directory[0]))) {
        out->error=r; return 1;
    }
    if (q->operation==MD_FS_IOCTL && p->mount->backing!=MD_MOUNT_NATIVE
            && !(p->mount->devpts && md_filesystem_ioctl_opens(q->mode))) {
        out->error=-ENOTTY; return 1;
    }
    if (virtual_operation(fs,owner.mounts,p->mount,q->directory[0],-1,NULL,q,out,output)) {
        mount_metadata(p->mount,q->directory[0],q,out); return 1;
    }
    if (q->operation==MD_FS_ACCESS && (r=execute_access(p->mount,q->directory[0],q->mode))) {
        out->error=r; return 1;
    }
    if (md_view_inode(p->mount) && q->operation!=MD_FS_OBJECT_ID) {
        if (q->operation==MD_FS_PATH) {
            out->error=!output || !output->data ? -EINVAL
                : md_view_path(fs,q->directory[0],p->mount,output->data,output->capacity);
            if (!out->error) out->size=strlen(output->data)+1;
        } else inode(fs,owner.mounts,p->mount,q,out,output);
        return 1;
    }
    size_t capacity = output ? output->capacity : 0;
    void *data = output ? output->data : NULL;
    switch (q->operation) {
    case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_UTIMENS:
    case MD_FS_SETCAP: case MD_FS_REMOVECAP: case MD_FS_SETACL: case MD_FS_REMOVEACL:
        /* Real directory attachments retain host ownership and kernel checks.
         * The adapter handles EXDEV through the same readonly-aware reopen. */
        r = md_view_readonly(p->mount) ? -EROFS : -EXDEV; break;
    case MD_FS_GETCAP: case MD_FS_GETACL: r = -EXDEV; break;
    case MD_FS_SYNC: r=-EXDEV; break;
    case MD_FS_IOCTL:
        r=md_view_readonly(p->mount) && md_filesystem_ioctl_writes(q->mode) ? -EROFS : -EXDEV; break;
    case MD_FS_ACCESS:
        if (q->flags&~MD_AT_EACCESS || q->mode&~(R_OK|W_OK|X_OK)) r=-EINVAL;
        else if ((q->mode&W_OK) && (r=readonly_data(p->mount,p->fd))) break;
        else if (syscall(SYS_faccessat2,p->fd,"",q->mode,AT_EMPTY_PATH|q->flags)) r=-errno;
        break;
    case MD_FS_XATTR_OBJECT:
        r = md_view_readonly(p->mount) && (q->mode==MD_FS_XATTR_SET || q->mode==MD_FS_XATTR_REMOVE)
            ? -EROFS : -EXDEV; break;
    case MD_FS_FSTAT:
        result_stat(out,q->directory[0],p->mount);
        mount_metadata(p->mount,q->directory[0],q,out); break;
    case MD_FS_STATFS: md_fs_statfs_result(q->directory[0],md_mount_policy_flags(&p->mount->policy),NULL,out,output); break;
    case MD_FS_READLINK: {
        if (!data || !capacity) { r=-EINVAL; break; }
        ssize_t n=p->mount->backing==MD_MOUNT_PROC
            ? md_procfs_readlink(fs,p->mount,q->directory[0],data,capacity)
            : readlinkat(q->directory[0],"",data,capacity);
        if (n<0) r=p->mount->backing==MD_MOUNT_PROC ? (int)n : -errno; else out->size=(size_t)n;
        break;
    }
    case MD_FS_REOPEN:
        r = reopen(p, (int)q->flags, !!(q->mode&MD_FS_REOPEN_MUTABLE), &out->open_completion);
        if (r >= 0) { out->fd = r; r = 0; md_view_capture(fs,owner.mounts,p->mount,out,output); }
        break;
    case MD_FS_OBJECT_ID:
        if (!data || capacity < sizeof(p->id)) r = -ERANGE;
        else { memcpy(data, p->id, sizeof(p->id)); out->size = sizeof(p->id); }
        break;
    case MD_FS_PATH:
        if (!data || !capacity) r = -ERANGE;
        else if (owner.mounts!=fs->mounts) r=-ENOENT;
        else {
            r = md_view_path(fs, q->directory[0], p->mount, data, capacity);
            if (!r) out->size = strlen(data)+1;
        }
        break;
    case MD_FS_SEEKDIR: {
        off_t pos = lseek(q->directory[0], q->offset, (int)q->flags);
        if (pos < 0) r = -errno; else out->position = pos;
        break;
    }
    case MD_FS_GETDENTS: {
        struct directory_context context={&owner,p->mount};
        md_fs_directory_read(q,out,output,directory_entries,&context);
        break;
    }
    default: return 0;
    }
    if (r) out->error = r;
    return 1;
}
static int has_path(unsigned operation) {
    switch (operation) {
    case MD_FS_CREATE: case MD_FS_OPEN: case MD_FS_MKDIR: case MD_FS_MKNOD: case MD_FS_SYMLINK:
    case MD_FS_READLINK: case MD_FS_LINK: case MD_FS_UNLINK: case MD_FS_RENAME:
    case MD_FS_STAT: case MD_FS_STATFS: case MD_FS_REALPATH: case MD_FS_OPEN_IMAGE:
    case MD_FS_SOCKET_BIND: case MD_FS_SOCKET_ADDRESS:
    case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_UTIMENS:
        return 1;
    default: return 0;
    }
}
static int follow(const struct md_fs_request *q) {
    switch (q->operation) {
    case MD_FS_OPEN: case MD_FS_OPEN_IMAGE:
        return (q->flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL) ? -1 : !(q->flags & O_NOFOLLOW);
    case MD_FS_STAT: case MD_FS_ACCESS:
    case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_UTIMENS:
        return !(q->flags & AT_SYMLINK_NOFOLLOW);
    case MD_FS_LINK: return !!(q->flags & AT_SYMLINK_FOLLOW);
    case MD_FS_CREATE: case MD_FS_MKDIR: case MD_FS_MKNOD: case MD_FS_SYMLINK:
    case MD_FS_UNLINK: case MD_FS_RENAME: case MD_FS_SOCKET_BIND: return -1;
    case MD_FS_READLINK: return 0;
    default: return 1;
    }
}
static int missing(const struct md_fs_request *q) {
    if (q->operation == MD_FS_MKDIR) return 2;
    return q->operation == MD_FS_CREATE || q->operation == MD_FS_MKNOD || q->operation == MD_FS_SYMLINK
        || q->operation == MD_FS_SOCKET_BIND || (q->operation == MD_FS_OPEN && (q->flags & O_CREAT));
}
static int mutates(const struct md_fs_request *q, const struct md_view_location *location) {
    switch (q->operation) {
    case MD_FS_CREATE: case MD_FS_MKDIR: case MD_FS_MKNOD: case MD_FS_SYMLINK: case MD_FS_LINK:
    case MD_FS_UNLINK: case MD_FS_RENAME: case MD_FS_SOCKET_BIND:
    case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_UTIMENS:
        return 1;
    case MD_FS_OPEN: return writable(q->flags&~(location->fd>=0 ? O_CREAT : 0));
    default: return 0;
    }
}
static int mounted_root(const struct md_view_location *p) {
    if (p->fd < 0 || !p->mount) return 0;
    struct stat st;
    return !fstat(p->fd, &st) && md_view_same(&st, &p->mount->native);
}
static int selected_operation(unsigned operation) {
    switch (operation) {
    case MD_FS_CREATE: case MD_FS_OPEN: case MD_FS_OPEN_IMAGE: case MD_FS_STAT: case MD_FS_STATFS:
    case MD_FS_ACCESS: case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_UTIMENS: case MD_FS_READLINK:
    case MD_FS_SOCKET_ADDRESS:
        return 1;
    default: return 0;
    }
}
static void inode(struct md_filesystem *fs, struct md_mount_namespace *view, struct md_view_mount *mount,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    int foreign=view!=fs->mounts;
    if (foreign && (q->operation==MD_FS_PATH || q->operation==MD_FS_REALPATH || q->operation==MD_FS_OPEN_IMAGE)) {
        *out=(struct md_fs_result){.fd=-1,.error=-ENOENT}; return;
    }
    struct md_filesystem owner=*fs; owner.mounts=view; owner.inode_mount=mount;
    /* A root in another tree cannot clamp relative inode traversal. The worker
     * owns this borrowed context and restores it before handling another call. */
    struct md_fs_storage_scope scope;
    int r=md_fs_storage_enter(fs,mount,&scope);
    if (r) { *out=(struct md_fs_result){.fd=-1,.error=r}; return; }
    owner.selected_store=scope.store;
    if (foreign) scope.store->root_id=NULL;
    md_fs_inode_execute(&owner,q,out,output);
    md_fs_storage_leave(&scope);
}
static void inode_selected(struct md_filesystem *fs, const struct md_fs_request *q,
        const struct md_view_location *a, struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_fs_request local=*q;
    local.directory[0]=a->fd; local.path[0]=NULL; local.origin[0]=NULL;
    local.directory[1]=-1; local.path[1]=NULL; local.origin[1]=NULL; local.resolve=0;
    if (q->operation==MD_FS_STAT) local.operation=MD_FS_FSTAT;
    if (q->operation==MD_FS_OPEN || q->operation==MD_FS_CREATE || q->operation==MD_FS_OPEN_IMAGE) {
        unsigned flags=q->operation==MD_FS_CREATE ? O_RDWR|O_CREAT|O_EXCL
            : q->operation==MD_FS_OPEN_IMAGE ? O_RDONLY|O_CLOEXEC : q->flags;
        /* O_TMPFILE creates inside the selected directory; it does not reopen
         * that directory. Keep creation flags/mode and the provider's errno. */
        if (!(flags&O_PATH) && (flags&__O_TMPFILE)) {
            local.operation=MD_FS_OPEN; local.path[0]=".";
            inode(fs,a->view,a->mount,&local,out,output);
            return;
        }
        if (!(flags&O_PATH) && (flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) {
            out->error=-EEXIST; return;
        }
        struct stat st;
        if (fstat(a->fd,&st)) { out->error=-errno; return; }
        if (!(flags&O_PATH) && (flags&O_CREAT) && S_ISDIR(st.st_mode)) { out->error=-EISDIR; return; }
        if (q->operation==MD_FS_OPEN_IMAGE) {
            if (!output || !output->data || output->capacity<sizeof(struct md_image_identity)) {
                out->error=-ERANGE; return;
            }
            if (!S_ISREG(st.st_mode)) { out->error=-EACCES; return; }
            local.operation=MD_FS_ACCESS; local.flags=MD_AT_EACCESS; local.mode=X_OK;
            inode(fs,a->view,a->mount,&local,out,NULL);
            if (out->error) return;
        }
        local.operation=MD_FS_REOPEN; local.flags=flags&~(O_CREAT|O_EXCL); local.mode=0;
    }
    inode(fs,a->view,a->mount,&local,out,output);
    /* The selected FD preserves the object, not the caller's path syntax.
     * readlink(non-link) is EINVAL; readlinkat(fd, "") is ENOENT. */
    if (q->operation==MD_FS_READLINK && out->error==-ENOENT) {
        struct stat st;
        if (!fstat(a->fd,&st) && !S_ISLNK(st.st_mode)) out->error=-EINVAL;
    }
    if (out->error || q->operation!=MD_FS_OPEN_IMAGE) return;
    struct md_image_identity *identity=output->data;
    struct md_filesystem owner=*fs; owner.mounts=a->view;
    struct md_view_object *object=NULL;
    int r=a->view!=fs->mounts ? -ENOENT : md_view_record(&owner,a->fd,a->mount,&object);
    if (!r) r=md_view_location_path(fs,a,identity->path,sizeof(identity->path));
    if (!r) {
        memcpy(identity->object,object->id,sizeof(identity->object));
        out->size=offsetof(struct md_image_identity,path)+strlen(identity->path)+1;
    } else {
        close(out->fd); out->fd=-1; out->error=r;
        if (output->origin) { md_fs_anchor_release(*output->origin); *output->origin=NULL; }
    }
}
static void native(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_view_location *a, struct md_view_location *b,
        struct md_fs_result *out, const struct md_fs_output *output) {
    int policy=md_fs_landlock_native(fs,q,a->mount,a->fd);
    if (policy) { out->error=policy; return; }
    if (virtual_operation(fs,a->view,a->mount,a->fd,a->parent,a->name,q,out,output)) return;
    int r = 0;
    struct md_filesystem owner=*fs; owner.mounts=a->view;
    void *data = output ? output->data : NULL;
    size_t capacity = output ? output->capacity : 0;
    switch (q->operation) {
    case MD_FS_SOCKET_ADDRESS: {
        if (!(q->flags&MD_FS_SOCKET_PIN)) { r=-EOPNOTSUPP; break; }
        int fd=fcntl(a->fd,F_DUPFD_CLOEXEC,0);
        if (fd<0) r=-errno;
        else out->fd=fd;
        break;
    }
    case MD_FS_CHMOD: {
        struct stat st;
        if (fstat(a->fd,&st)) r=-errno;
        else if (S_ISLNK(st.st_mode)) r=-EOPNOTSUPP;
        else {
            char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",a->fd);
            if (fchmodat(AT_FDCWD,path,q->mode,0)) r=-errno;
        }
        break;
    }
    case MD_FS_CHOWN:
        if (fchownat(a->fd,"",q->attributes.uid,q->attributes.gid,AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW)) r=-errno;
        break;
    case MD_FS_UTIMENS: {
        const struct timespec times[2]={{q->attributes.seconds[0],q->attributes.nanos[0]},
            {q->attributes.seconds[1],q->attributes.nanos[1]}};
        if (utimensat(a->fd,"",times,AT_EMPTY_PATH|AT_SYMLINK_NOFOLLOW)) r=-errno;
        break;
    }
    case MD_FS_ACCESS:
        if (q->flags & ~(AT_SYMLINK_NOFOLLOW | MD_AT_EACCESS) || q->mode & ~(R_OK | W_OK | X_OK)) r = -EINVAL;
        else if ((q->mode&W_OK) && (r=readonly_data(a->mount,a->fd))) break;
        else if (syscall(SYS_faccessat2, a->fd, "", q->mode, AT_EMPTY_PATH | q->flags)) r = -errno;
        break;
    case MD_FS_CREATE: case MD_FS_OPEN: case MD_FS_OPEN_IMAGE: {
        int flags = q->operation == MD_FS_CREATE ? O_RDWR | O_CREAT | O_EXCL
            : q->operation == MD_FS_OPEN_IMAGE ? O_RDONLY : (int)q->flags;
        if (flags & ~(O_ACCMODE | O_CLOEXEC | O_APPEND | O_TRUNC | O_NOFOLLOW | O_DIRECTORY | O_PATH
                | O_CREAT | O_EXCL | O_NONBLOCK | O_NOCTTY | O_LARGEFILE | O_SYNC | O_DSYNC | O_DIRECT | O_NOATIME)) { r = -ENOTSUP; break; }
        if ((flags & O_ACCMODE) == O_ACCMODE) { r = -EINVAL; break; }
        if (q->operation == MD_FS_OPEN_IMAGE && (!data || capacity < sizeof(struct md_image_identity))) { r = -ERANGE; break; }
        if (flags & O_PATH) flags &= O_PATH | O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC;
        if (((flags & O_TRUNC) && !(flags & O_ACCMODE))
                || ((flags & O_CREAT) && (flags & O_DIRECTORY))) { r = -EINVAL; break; }
        if ((flags & O_CREAT) && (q->mode & ~01777)) { r = -ENOTSUP; break; }
        struct stat existing;
        if (a->fd >= 0 && fstat(a->fd, &existing)) { r = -errno; break; }
        if (a->fd>=0 && (r=device_open(a->mount,flags,existing.st_mode))) break;
        if (output && output->native_open_plan) {
            /* The walker pins the exact native object and its mount policy.
             * Data-open effects belong to the caller, just as for inode plans.
             * No creation, typed endpoint or provider operation is speculative. */
            if (a->fd<0 || !md_open_native_kind(flags,existing.st_mode)) { r=-EXDEV; break; }
            if ((flags&O_CREAT) && S_ISDIR(existing.st_mode)) { r=-EISDIR; break; }
            if ((r=md_open_plan_access(a->fd,flags))) break;
            out->fd=fcntl(a->fd,F_DUPFD_CLOEXEC,3);
            if (out->fd<0) { r=-errno; break; }
            out->open_completion=(struct md_open_completion){.kind=MD_OPEN_PLAN,
                .backing_device=existing.st_dev,.backing_inode=existing.st_ino,.mode=existing.st_mode&S_IFMT};
            md_view_capture(fs,a->view,a->mount,out,output);
            break;
        }
        int fifo = a->fd >= 0 && S_ISFIFO(existing.st_mode);
        if (fifo && q->operation == MD_FS_OPEN_IMAGE) { r = -EACCES; break; }
        if (fifo && (flags & O_CREAT) && (flags & O_EXCL)) { r = -EEXIST; break; }
        if (fifo && (flags & O_DIRECTORY)) { r = -ENOTDIR; break; }
        int deferred = fifo && !(flags & O_PATH);
        /* Proc entries can use namespace-visible PIDs. The walker has already
         * selected their native inode; replaying parent/name selects another
         * process when the logical and native PID numbers differ. */
        int selected=a->fd>=0 && (!strcmp(a->name,".") || a->mount->backing==MD_MOUNT_PROC || a->mount->provider);
        if (selected && !(flags&O_PATH) && (flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) { r=-EEXIST; break; }
        if (selected && !(flags&O_PATH) && (flags&O_CREAT) && S_ISDIR(existing.st_mode)) { r=-EISDIR; break; }
        int fd=selected ? reopen_admitted(a->fd,a->mount,(flags&~(O_CREAT|O_EXCL))|O_CLOEXEC,&out->open_completion)
            : deferred ? fcntl(a->fd, F_DUPFD_CLOEXEC, 3)
            : openat(a->parent, a->name, flags | O_NOFOLLOW | O_CLOEXEC, q->mode&~q->attributes.creation_mask);
        /* Captured opens retain mount identity in their lifetime-bound anchor.
         * Only anonymous transports and explicit object/image IDs need a
         * view-lifetime registry pin. Image admission records its ID below. */
        int named=q->operation!=MD_FS_OPEN_IMAGE && (!output || !output->origin);
        r=selected && fd<0 ? fd : record(&owner,fd,a->mount,out,named);
        if (!r && deferred) out->open_completion.kind = MD_OPEN_FIFO;
        if (!r && q->operation == MD_FS_OPEN_IMAGE) {
            struct stat st;
            if (fstat(fd, &st)) r = -errno;
            else if (!S_ISREG(st.st_mode)) r = -EACCES;
            struct md_view_object *object;
            if (!r) r = md_view_record(&owner, fd, a->mount, &object);
            struct md_image_identity *identity = data;
            if (!r && a->view!=fs->mounts) r=-ENOENT;
            if (!r) r = md_view_path(fs, fd, a->mount, identity->path, sizeof(identity->path));
            if (!r) { memcpy(identity->object, object->id, sizeof(identity->object)); out->size = offsetof(struct md_image_identity, path)+strlen(identity->path)+1; }
            if (r) { close(out->fd); out->fd = -1; }
        }
        if (!r) md_view_capture(fs,a->view,a->mount,out,output);
        break;
    }
    case MD_FS_STAT:
        if (q->flags & ~AT_SYMLINK_NOFOLLOW) r = -EINVAL;
        else result_stat(out, a->fd, a->mount);
        break;
    case MD_FS_STATFS:
        md_fs_statfs_result(a->fd,md_mount_policy_flags(&a->mount->policy),NULL,out,output);
        break;
    case MD_FS_REALPATH:
        if (!data || !capacity) r = -ERANGE;
        else if (a->view!=fs->mounts) r=-ENOENT;
        else { r = md_view_path(fs, a->fd, a->mount, data, capacity); if (!r) out->size = strlen(data)+1; }
        break;
    case MD_FS_READLINK: {
        if (!data || !capacity) { r=-EINVAL; break; }
        ssize_t n = a->mount->backing==MD_MOUNT_PROC ? md_procfs_readlink(fs,a->mount,a->fd,data,capacity)
            : readlinkat(a->fd, "", data, capacity);
        if (n < 0) r=a->mount->backing==MD_MOUNT_PROC ? (int)n : -errno; else out->size = (size_t)n;
        /* This branch resolved a nonempty pathname; descriptor-only requests
         * retain the kernel's empty-path errno in descriptor(). */
        if (r==-ENOENT) {
            struct stat st;
            if (!fstat(a->fd,&st) && !S_ISLNK(st.st_mode)) r=-EINVAL;
        }
        break;
    }
    case MD_FS_MKDIR: if (mkdirat(a->parent, a->name, q->mode&~q->attributes.creation_mask)) r = -errno; break;
    case MD_FS_MKNOD:
        if ((q->mode&S_IFMT)==S_IFCHR || (q->mode&S_IFMT)==S_IFBLK) r=-EPERM;
        else if (mknodat(a->parent,a->name,q->mode&~q->attributes.creation_mask,0)) r=-errno;
        break;
    case MD_FS_SYMLINK: if (symlinkat(q->path[1], a->parent, a->name)) r = -errno; break;
    case MD_FS_UNLINK: if (unlinkat(a->parent, a->name, (int)q->flags)) r = -errno; break;
    case MD_FS_RENAME: if (syscall(SYS_renameat2, a->parent, a->name, b->parent, b->name, q->flags)) r = -errno; break;
    case MD_FS_LINK:
        if (q->flags & ~AT_SYMLINK_FOLLOW) r = -EINVAL;
        else if (linkat(a->parent, a->name, b->parent, b->name, 0)) r = -errno;
        break;
    default: r = -EOPNOTSUPP; break;
    }
    if (r) out->error = r;
}
static int local_inode_view(struct md_filesystem *fs, const struct md_fs_request *q) {
    /* View object IDs are not inode-store IDs, even without a path crossing. */
    if (q->operation==MD_FS_OPEN_OBJECT && md_mount_namespace_attached(fs->mounts)) return 0;
    if (fs->native_root || q->resolve || (fs->mounts
            && (fs->mounts->root!=&fs->mounts->base || fs->mounts->base.source>=0))) return 0;
    if (md_view_policy_walk(fs,q)) return 0;
    for (unsigned i=0;i<2;++i) {
        const struct md_fs_anchor *p=q->origin[i];
        if (p && (p->view!=fs->mounts || !md_view_base(p->view,p->mount))) return 0;
    }
    for (unsigned i=0;i<2;++i) {
        const struct md_fs_anchor *p=q->origin[i];
        if (!p || (q->path[i] && *q->path[i]
                && (q->directory[i]==MD_INODE_ROOT || q->path[i][0]=='/'))) continue;
        int error=md_view_anchor_check(fs,q->directory[i],p);
        if (error) return error;
    }
    return 1;
}
static int base_mount_contains(void *context,uint64_t device,uint64_t inode) {
    struct md_mount_namespace *view=context;
    struct stat identity={.st_dev=device,.st_ino=inode};
    return md_view_mount_lookup(view,&view->base,&identity)!=NULL;
}
struct path_operation {
    struct md_fs_continuation base;
    struct md_view_walk_pending *walk;
    struct md_view_location a,b;
    struct md_fs_anchor *pins[2];
    unsigned operand;
};
struct observation {
    struct md_filesystem *fs;
    const struct md_fs_request *request;
    struct md_fs_result *result;
    const struct md_fs_output *output;
};
/* Read-only completion borrows the walk's admitted inode. It must not start
 * another lookup, retain that inode, or mutate the store under its read scope. */
static int observe_inode(void *context,struct md_inode_store *store,struct md_view_mount *mount,
        const struct mdi_node *node,const struct stat *physical) {
    struct observation *o=context;
    const struct md_fs_request *q=o->request;
    struct md_fs_result *out=o->result;
    switch (q->operation) {
    case MD_FS_READLINK: {
        ssize_t n=mdi_readlink(store,node,o->output ? o->output->data : NULL,
            o->output ? o->output->capacity : 0,0);
        if (n<0) return (int)n;
        out->size=(size_t)n; return 0;
    }
    case MD_FS_STAT: {
        if ((q->flags&~AT_SYMLINK_NOFOLLOW) || (q->mode&~(MD_FS_STAT_MOUNT|MD_FS_STAT_CANONICAL))) return -EINVAL;
        struct stat st=*physical;
        mdi_stat_project(node,&st);
        if (store==o->fs->store) md_catalogue_stat_project(o->fs->images,&st);
        md_fs_stat_info(&st,&out->info);
        if (q->mode&MD_FS_STAT_MOUNT)
            md_fs_info_mount(&out->info,mount->id,mount->source>=0
                ? md_view_same(physical,&mount->native) : !strcmp(node->id,store->lifetime->scope));
        return 0;
    }
    case MD_FS_ACCESS:
        if (q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) return -EINVAL;
        if ((q->mode&X_OK) && node->kind==S_IFREG
                && (md_mount_policy_flags(&mount->policy)&MD_MOUNT_NOEXEC)) return -EACCES;
        return mdi_permission(store,node,q->mode,!(q->flags&MD_AT_EACCESS));
    default: return -EINVAL;
    }
}
static void mounts_execute(struct md_filesystem *,const struct md_fs_request *,
    struct md_fs_result *,const struct md_fs_output *,struct path_operation *);
static int path_ready(struct md_fs_continuation *base) {
    return md_view_walk_ready(((struct path_operation *)base)->walk);
}
static void path_destroy(struct md_fs_continuation *base) {
    struct path_operation *p=(void *)base;
    md_view_walk_destroy(p->walk);
    md_view_location_close(&p->a); md_view_location_close(&p->b);
    for (unsigned i=0;i<2;++i) md_fs_anchor_release(p->pins[i]);
    md_fs_continuation_clear(base); free(p);
}
static void path_resume(struct md_fs_continuation *base,struct md_filesystem *fs,
        struct md_fs_result *out,const struct md_fs_output *output) {
    mounts_execute(fs,&base->request,out,output,(void *)base);
}
static int path_suspend(struct md_filesystem *fs,const struct md_fs_request *q,
        const struct md_fs_output *output,struct path_operation *p,struct md_view_walk_pending *walk,
        struct md_view_location *a,struct md_view_location *b,unsigned operand) {
    int error=0;
    struct md_fs_anchor *pins[2]={0};
    struct md_view_location *locations[]={a,b};
    for (unsigned i=0;i<2 && !error;++i) if (locations[i]->fd>=0)
        error=md_view_anchor_duplicate(fs,locations[i]->view,locations[i]->mount,locations[i]->fd,&pins[i]);
    if (!error && !p) {
        p=calloc(1,sizeof(*p));
        if (!p) error=-ENOMEM;
        else {
            p->a=p->b=(struct md_view_location){.fd=-1,.parent=-1};
            error=md_fs_continuation_capture(&p->base,q);
            if (error) { free(p); p=NULL; }
            else {
                p->base.ready=path_ready; p->base.resume=path_resume; p->base.destroy=path_destroy;
            }
        }
    }
    if (error) {
        for (unsigned i=0;i<2;++i) md_fs_anchor_release(pins[i]);
        md_view_walk_destroy(walk); return error;
    }
    for (unsigned i=0;i<2;++i) { md_fs_anchor_release(p->pins[i]); p->pins[i]=pins[i]; }
    p->a=*a; p->b=*b; p->walk=walk; p->operand=operand;
    *a=*b=(struct md_view_location){.fd=-1,.parent=-1};
    *output->continuation=&p->base;
    return -EINPROGRESS;
}
static void mounts_execute(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output,struct path_operation *resume) {
    *out = (struct md_fs_result){.fd=-1};
    if (resume) goto paths;
    if (q->operation==MD_FS_PATH && q->mode==MD_FS_PATH_DISPLAY && q->origin[0]
            && q->origin[0]->view) {
        out->error=md_view_anchor_check(fs,q->directory[0],q->origin[0]);
        if (!out->error && (!output || !output->data)) out->error=-EINVAL;
        if (!out->error) {
            if (q->origin[0]->view==fs->mounts) {
                out->error=anchor_path(fs,q->origin[0],output->data,output->capacity);
                if (out->error!=-ENOENT) {
                    if (!out->error) out->size=strlen(output->data)+1;
                    return;
                }
            }
            /* proc magic-link text is descriptive, not a path lookup grant.
             * Retained objects outside the new root still have a guest name. */
            struct md_filesystem owner=*fs;
            owner.mounts=q->origin[0]->view; owner.native_root=NULL;
            struct md_view_object detached={0};
            for (struct md_view_mount *m=q->origin[0]->mount;m;m=m->parent) {
                if (m->detached) {
                    detached=(struct md_view_object){.fd=m->source,.mount=m};
                    owner.native_root=&detached;
                    break;
                }
            }
            const char *root=fs->store->root_id;
            fs->store->root_id=NULL;
            out->error=anchor_path(&owner,q->origin[0],output->data,output->capacity);
            fs->store->root_id=root;
            if (!out->error) out->size=strlen(output->data)+1;
        }
        return;
    }
    /* A same-view inode origin does not introduce a mount crossing. Let the
     * inode engine perform its one authoritative walk after origin validation. */
    int local=local_inode_view(fs,q);
    if (local) {
        if (local<0) { out->error=local; return; }
        struct mdi_mount_boundary guard={.context=fs->mounts,.contains=base_mount_contains,
            .filter=md_view_mount_children(fs->mounts,fs->mounts ? &fs->mounts->base : NULL)};
        struct mdi_mount_boundary *previous=fs->store->mount_boundary;
        fs->store->mount_boundary=guard.filter ? &guard : NULL;
        md_fs_inode_execute(fs,q,out,output);
        fs->store->mount_boundary=previous;
        /* The inode transaction rejected the crossing before any mutation.
         * Readonly admission can precede its walk; a child mount may still be
         * writable. Resolve that rejection before applying the base policy. */
        if (!guard.crossed && (out->error!=-EXDEV
                && !(out->error==-EREMOTE && md_mount_namespace_managed_paths(fs->mounts))
                && !(out->error==-EROFS && guard.filter))) return;
        *out=(struct md_fs_result){.fd=-1};
    }
    int pause_error=md_inode_operation_window_pause(fs->store);
    if (pause_error) { out->error=pause_error; return; }
    if (q->operation == MD_FS_OPEN_OBJECT) {
        struct md_view_object *p=md_view_object(fs,q->path[0]);
        if (p) {
            if (md_fuse_view_matches(p->mount->provider)) { out->error=-EOPNOTSUPP; return; }
            if (writable(q->flags)) { out->error = -EACCES; return; }
            if (md_view_inode(p->mount)) {
                struct md_fs_request local={.operation=MD_FS_REOPEN,.directory={p->fd,-1},.flags=q->flags};
                inode(fs,fs->mounts,p->mount,&local,out,output); return;
            }
            struct stat st;
            if (fstat(p->fd, &st)) { out->error = -errno; return; }
            if (!S_ISREG(st.st_mode)) { out->error = -EINVAL; return; }
            int policy=md_fs_landlock_native(fs,q,p->mount,p->fd);
            if (policy) { out->error=policy; return; }
            int fd = reopen(p, (int)q->flags, 0, &out->open_completion);
            if (fd < 0) out->error = fd; else out->fd = fd;
            md_view_capture(fs,fs->mounts,p->mount,out,output);
            return;
        }
    }
    if ((!has_path(q->operation) || !q->path[0] || !*q->path[0])
            && !(q->operation == MD_FS_ACCESS && q->path[0] && *q->path[0])) {
        if (q->origin[0]) {
            int error=md_view_anchor_check(fs,q->directory[0],q->origin[0]);
            if (error) { out->error=error; return; }
            if (q->operation==MD_FS_PATH && q->origin[0]->view!=fs->mounts) {
                out->error=-ENOENT; return;
            }
        }
        switch (q->operation) {
        case MD_FS_FSTAT: case MD_FS_STATFS: case MD_FS_SYNC: case MD_FS_IOCTL: case MD_FS_PATH: case MD_FS_REOPEN: case MD_FS_GETDENTS: case MD_FS_READLINK:
        case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_ACCESS: case MD_FS_UTIMENS:
        case MD_FS_GETCAP: case MD_FS_SETCAP: case MD_FS_REMOVECAP:
        case MD_FS_GETACL: case MD_FS_SETACL: case MD_FS_REMOVEACL: case MD_FS_XATTR_OBJECT:
        case MD_FS_SEEKDIR: case MD_FS_OBJECT_ID:
            if (descriptor(fs, q, out, output)) return;
        }
        if (q->operation==MD_FS_PATH && fs->native_root && !q->origin[0]) {
            char inode[33];
            int known=md_inode_object_id(fs->store,q->directory[0],inode);
            /* An unannotated inode cannot choose a bind alias. A native
             * provider FD remains task-owned across pivot_root, however. */
            out->error=known==-EXDEV ? -EXDEV : known ? known : -ENOENT;
            return;
        }
        const struct md_fs_anchor *origin=q->origin[0];
        if (origin && origin->mount && q->operation==MD_FS_PATH) {
            *out=(struct md_fs_result){.fd=-1};
            out->error=!output || !output->data ? -EINVAL
                : md_view_path(fs,q->directory[0],origin->mount,output->data,output->capacity);
            if (!out->error) out->size=strlen(output->data)+1;
            return;
        }
        inode(fs,origin ? origin->view : fs->mounts,origin ? origin->mount : NULL,q,out,output);
        if (out->error==-EXDEV && q->operation==MD_FS_FSTAT) {
            /* Anonymous native objects have no mount/inode-store placement.
             * Inspect the retained FD, not the proc name which may be reused. */
            md_fs_native_descriptor(fs,q,out,output);
        }
        return;
    }
paths:;
    struct md_view_location a = {.parent=-1,.fd=-1}, b = {.parent=-1,.fd=-1};
    struct observation observation={fs,q,out,output};
    struct md_view_observer observer={.context=&observation,.complete=observe_inode};
    struct md_view_observer *observer_ptr=q->operation==MD_FS_STAT || q->operation==MD_FS_READLINK
        || q->operation==MD_FS_ACCESS ? &observer : NULL;
    struct md_view_walk_pending *pending=NULL;
    unsigned operand=0;
    if (resume) {
        a=resume->a; b=resume->b; pending=resume->walk; operand=resume->operand;
        resume->a=resume->b=(struct md_view_location){.fd=-1,.parent=-1}; resume->walk=NULL;
    }
    int pair = q->operation == MD_FS_LINK || q->operation == MD_FS_RENAME;
    /* A proc alias is task-owned for metadata and mutation as well as opens.
     * Never resolve self/fd against the filesystem worker's descriptor table. */
    uint64_t resolve=q->resolve|MD_VIEW_TASK_PATH;
    int r=0;
    if (!operand) {
        r=pending ? md_view_walk_resume(fs,pending,&a,observer_ptr)
            : md_view_walk_async(fs,q->directory[0],q->origin[0],q->path[0],
                follow(q),missing(q),resolve,&a,observer_ptr,output && output->continuation ? &pending : NULL);
        if (r==-EINPROGRESS) goto suspended;
        md_view_walk_destroy(pending); pending=NULL;
        if (observer.completed) {
            if (r) out->error=r;
            md_view_location_close(&a); md_view_location_close(&b);
            return;
        }
    }
    /* Plain native mounts can pin an existing regular file/directory as well.
     * Typed providers must not open an endpoint and then decline its result. */
    if (!r && output && output->native_open_plan && !md_view_inode(a.mount)
            && a.mount->backing!=MD_MOUNT_NATIVE) r=-EXDEV;
    if (!r && pair) {
        operand=1;
        r=pending ? md_view_walk_resume(fs,pending,&b,NULL)
            : output && output->continuation ? md_view_walk_async(fs,q->directory[1],q->origin[1],q->path[1],
                -1,1,MD_VIEW_TASK_PATH,&b,NULL,&pending)
            : md_view_walk(fs,q->directory[1],q->origin[1],q->path[1],-1,1,MD_VIEW_TASK_PATH,&b);
        if (r==-EINPROGRESS) goto suspended;
        md_view_walk_destroy(pending); pending=NULL;
        if (r==-EREMOTE && fs->store->boundary) fs->store->boundary->operand=1;
    }
    if (!r && pair && (a.view != b.view || a.mount != b.mount)) r = -EXDEV;
    if (!r && a.fd>=0 && (q->operation==MD_FS_MKDIR || q->operation==MD_FS_MKNOD
            || q->operation==MD_FS_SYMLINK || q->operation==MD_FS_CREATE
            || (q->operation==MD_FS_OPEN && !(q->flags&O_PATH)
                && (q->flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)))) r=-EEXIST;
    if (!r && mutates(q,&a) && a.mount && md_view_readonly(a.mount)) {
        r=-EROFS;
        if (q->operation==MD_FS_OPEN && a.fd>=0) {
            struct md_fs_request stat={.operation=MD_FS_FSTAT,.directory={a.fd,-1}};
            struct md_fs_result result={.fd=-1};
            if (md_view_inode(a.mount)) inode(fs,a.view,a.mount,&stat,&result,NULL);
            else result_stat(&result,a.fd,a.mount);
            if (result.error) r=result.error;
            else if (special(result.info.mode)) r=0;
        }
    }
    if (!r && (q->operation == MD_FS_UNLINK || q->operation == MD_FS_RENAME)
            && (mounted_root(&a) || (pair && mounted_root(&b)))) r = -EBUSY;
    /* Access probes inspect permissions, not the provider's ability to create
     * arbitrary nodes. Data opens and typed sysfs mutations belong to their owner.
     * Generic private-tree mutations must be rejected before native dispatch. */
    if (!r && mutates(q,&a) && q->operation!=MD_FS_ACCESS && q->operation!=MD_FS_OPEN && q->operation!=MD_FS_REOPEN
            && a.mount && a.mount->provider && !a.mount->sysfs) {
        int readonly=md_provider_tree_readonly(a.mount->provider,a.fd>=0 ? a.fd : a.parent);
        if (readonly) r=readonly<0 ? readonly : -EROFS;
        if (!r && pair && b.mount && b.mount->provider) {
            readonly=md_provider_tree_readonly(b.mount->provider,b.fd>=0 ? b.fd : b.parent);
            if (readonly) r=readonly<0 ? readonly : -EROFS;
        }
    }
    if (!r && q->operation==MD_FS_ACCESS) r=execute_access(a.mount,a.fd,q->mode);
    if (!r && q->operation==MD_FS_REALPATH) {
        out->error=!output || !output->data ? -EINVAL
            : a.view!=fs->mounts ? -ENOENT : md_view_path(fs,a.fd,a.mount,output->data,output->capacity);
        if (!out->error) out->size=strlen(output->data)+1;
    } else if (!r && md_fs_fuse_operation(fs,q,&a,pair ? &b : NULL,out,output)) {
        /* The provider retains the exact resolved objects before suspension. */
    } else if (!r && !md_view_inode(a.mount)) native(fs, q, &a, &b, out, output);
    else if (!r && ((q->operation==MD_FS_OPEN_IMAGE && !md_view_base(a.view,a.mount))
            || (a.fd>=0 && !strcmp(a.name,".") && selected_operation(q->operation)))) {
        inode_selected(fs,q,&a,out,output);
    } else if (!r) {
        struct md_fs_request local = *q;
        local.directory[0] = a.parent; local.path[0] = a.name; local.resolve = 0;
        if (pair) { local.directory[1] = b.parent; local.path[1] = b.name; }
        inode(fs,a.view,a.mount,&local,out,output);
    } else out->error = r;
    mount_metadata(a.mount,a.fd,q,out);
    md_view_location_close(&a); md_view_location_close(&b);
    return;
suspended:
    out->error=path_suspend(fs,q,output,resume,pending,&a,&b,operand);
    md_view_location_close(&a); md_view_location_close(&b);
}
void md_fs_mounts_execute(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    mounts_execute(fs,q,out,output,NULL);
}
