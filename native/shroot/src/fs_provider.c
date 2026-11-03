#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_control.h"
#include "host_fs_view.h"
#include "sysfs_uevent.h"
#include "inode_internal.h"
#include "linux_abi.h"
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

int md_fs_provider_mount(struct md_filesystem *fs,struct md_provider_tree *tree,const char *path) {
    struct md_view_location location={.fd=-1,.parent=-1};
    int error=md_view_walk(fs,AT_FDCWD,NULL,path,1,0,MD_VIEW_MOUNT_TARGET,&location);
    struct md_mount_source source={.fd=md_provider_tree_root(tree),.backing=MD_MOUNT_PROVIDER,.provider=tree,
        .policy=MD_MOUNT_NOSUID|MD_MOUNT_NOEXEC};
    if (!error) error=md_view_attach_source(fs,&source,&location,NULL);
    md_view_location_close(&location); return error;
}
int md_fs_provider_operation(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (!mount || !mount->provider) return 0;
    switch (q->operation) {
    case MD_FS_CREATE: case MD_FS_MKDIR: case MD_FS_MKNOD: case MD_FS_SYMLINK:
    case MD_FS_UNLINK: case MD_FS_LINK: case MD_FS_RENAME: case MD_FS_CHMOD:
    case MD_FS_CHOWN: case MD_FS_UTIMENS: case MD_FS_SETCAP: case MD_FS_REMOVECAP:
    case MD_FS_SETACL: case MD_FS_REMOVEACL: case MD_FS_SOCKET_BIND:
        out->error=-EROFS; return 1;
    default: break;
    }
    if (q->operation==MD_FS_STATFS) {
        md_fs_statfs_result(fd,md_view_policy_flags(mount),NULL,out,output);
        unsigned long type=md_provider_tree_filesystem(mount->provider);
        if (!out->error && type) ((struct md_fs_statfs *)output->data)->type=type;
        return 1;
    }
    if (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT) {
        struct stat st;
        int projected=md_host_fs_stat(mount->provider,fd,&st);
        if (projected<0) { out->error=projected; return 1; }
        if (projected) {
            md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id;
            return 1;
        }
    }
    struct md_control_file file={0};
    int managed=md_provider_tree_control(mount->provider,fd,&file);
    if (!managed) managed=md_sysfs_uevent(mount->provider,fs->device_events,fd,&file);
    if (managed<0) { out->error=managed; return 1; }
    if (!managed) {
        int write_open=(q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN)
            && !(q->flags&O_PATH) && (q->flags&(O_ACCMODE|O_TRUNC));
        int write_xattr=q->operation==MD_FS_XATTR_OBJECT
            && (q->mode==MD_FS_XATTR_SET || q->mode==MD_FS_XATTR_REMOVE);
        if (write_open || write_xattr) {
            int readonly=md_provider_tree_readonly(mount->provider,fd);
            if (readonly) { out->error=readonly<0 ? readonly : -EROFS; return 1; }
        }
        return 0;
    }
    int result=0,handled=1;
    const struct md_identity *id=fs->store->identity;
    switch (q->operation) {
    case MD_FS_STAT: case MD_FS_FSTAT: {
        struct stat st; result=file.ops->stat(file.context,&st);
        if (!result) { md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id; }
        break;
    }
    case MD_FS_ACCESS: {
        if (q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS) || q->mode&~7U) result=-EINVAL;
        else if ((q->mode&W_OK) && md_view_readonly(mount)) result=-EROFS;
        else {
            struct md_identity actor=md_identity_access(id,!!(q->flags&MD_AT_EACCESS));
            result=file.ops->access(file.context,&actor,q->mode);
        }
        break;
    }
    case MD_FS_OPEN: case MD_FS_REOPEN: {
        unsigned flags=q->flags;
        struct stat st; result=file.ops->stat(file.context,&st);
        if (result) break;
        if (!(flags&O_PATH) && (S_ISCHR(st.st_mode) || S_ISBLK(st.st_mode))
                && (md_mount_policy_flags(&mount->policy)&MD_MOUNT_NODEV)) { result=-EACCES; break; }
        if (!(flags&O_PATH) && (flags&(O_CREAT|O_EXCL))==(O_CREAT|O_EXCL)) { result=-EEXIST; break; }
        if (flags&O_DIRECTORY || ((flags&O_TRUNC) && !S_ISREG(st.st_mode))
                || (flags&O_ACCMODE)==O_ACCMODE) { result=-EINVAL; break; }
        if (!(flags&O_PATH) && (flags&(O_ACCMODE|O_TRUNC)) && md_view_readonly(mount)
                && !S_ISCHR(st.st_mode) && !S_ISBLK(st.st_mode)) { result=-EROFS; break; }
        if (!output || !output->control) { out->requires_control=1; result=-EOPNOTSUPP; break; }
        struct md_fs_anchor *origin=NULL;
        result=md_view_anchor_duplicate(fs,view,mount,fd,&origin);
        if (result) break;
        int carrier=fcntl(fd,F_DUPFD_CLOEXEC,3);
        result=md_fs_control_publish(carrier<0 ? -errno : carrier,file,origin,id,(int)flags,out,output);
        md_fs_anchor_release(origin); break;
    }
    case MD_FS_GETCAP: case MD_FS_GETACL: result=-ENODATA; break;
    case MD_FS_XATTR_OBJECT:
        result=q->mode==MD_FS_XATTR_LIST ? 0 : q->mode==MD_FS_XATTR_GET ? -ENODATA : -EROFS;
        break;
    case MD_FS_PATH: case MD_FS_OBJECT_ID: handled=0; break;
    default: result=-EOPNOTSUPP; break;
    }
    md_control_file_release(file);
    if (result) out->error=result;
    return handled;
}
