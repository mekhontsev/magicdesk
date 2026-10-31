#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_control.h"
#include "inode_internal.h"
#include "network_namespace.h"
#include "proc_paths.h"
#include "proc_sysctl.h"
#include "proc_static.h"
#include "user_sysctl.h"
#include "procfs_view.h"
#include "linux_abi.h"
#include "file_permissions.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <string.h>
#include <unistd.h>

int md_fs_proc_control_stat(struct md_filesystem *fs,struct md_control_file control,struct stat *st) {
    struct md_proc_file *file=md_proc_file_from_control(control);
    if (!file) return control.ops->stat(control.context,st);
    struct md_task_credentials target;
    int error=md_task_registry_credentials(fs->tasks,md_proc_file_get_node(file).process,&target);
    if (error && error!=-ESRCH) return error;
    int result=md_proc_file_retained_stat(file,error ? NULL : &target.identity,st);
    if (!error) md_identity_release(&target.identity);
    return result;
}
int md_fs_proc_task(struct md_filesystem *fs,struct md_fs_anchor *origin,const char *path,
        const struct md_fs_request *q,struct md_fs_result *out,const struct md_fs_output *output) {
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    enum md_proc_kind selected=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    enum md_proc_file_kind kind=(enum md_proc_file_kind)(selected-MD_PROC_UID_MAP);
    if (!md_proc_file_task(kind) || !fs->actor) return 0;
    /* Proc text is virtual, but watches belong to the native inode. Keep the
     * admitted path delegation so caller-affine capture enforces its access. */
    if (q->operation==MD_FS_WATCH_PATH && md_proc_native_task(selected)) return 0;
    struct md_task_snapshot target;
    int error=md_task_registry_proc(fs->tasks,ref.process,&target);
    /* External Android processes retain native inspection and admission. */
    if (error==-ESRCH) return 0;
    struct stat st;
    struct md_proc_file_node node;
    if (!error) error=md_proc_file_task_stat(ref.process,&target.identity,kind,&node,&st);
    *out=(struct md_fs_result){.fd=-1,.error=error};
    if (!error) switch (q->operation) {
    case MD_FS_STAT: case MD_FS_FSTAT:
        md_fs_stat_info(&st,&out->info); md_fs_anchor_mount_info(origin,&out->info);
        break;
    case MD_FS_ACCESS:
        error=(q->mode&W_OK) && md_view_readonly(origin->mount) ? -EROFS
            : md_proc_file_task_access(fs->store->identity,&st,q->mode,1);
        break;
    case MD_FS_OPEN: case MD_FS_REOPEN: {
        unsigned mode=(q->flags&O_PATH) ? 0 : (q->flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK
            : (q->flags&O_ACCMODE)==O_WRONLY ? W_OK : R_OK;
        if (!(q->flags&O_PATH) && (q->flags&O_TRUNC)) mode|=W_OK;
        error=(mode&W_OK) && md_view_readonly(origin->mount) ? -EROFS
            : md_proc_file_task_access(fs->store->identity,&st,mode,1);
        if (!error && (q->flags&O_NOATIME) && !(q->flags&O_PATH))
            error=md_file_noatime(&st,fs->store->identity);
        struct md_proc_file *file=NULL;
        if (!error) {
            file=md_proc_file_open(target.identity.user_namespace,kind,fs->store->identity,(int)q->flags);
            if (!file) error=-errno;
        }
        if (file) {
            md_proc_file_node(file,node);
            md_proc_file_pid_view(file,origin->mount->pid_namespace);
            md_proc_file_cgroup(file,target.cgroup);
            md_proc_file_place(file,origin,&st);
            error=md_fs_control_publish(md_proc_file_descriptor(file),md_proc_file_control(file),origin,
                fs->store->identity,(int)q->flags,out,output);
            md_proc_file_release(file);
        }
        break;
    }
    case MD_FS_STATFS:
        md_fs_statfs_result(origin->fd,md_fs_anchor_policy(origin),NULL,out,output);
        if (!out->error) ((struct md_fs_statfs *)output->data)->type=PROC_SUPER_MAGIC;
        break;
    case MD_FS_READLINK: error=-EINVAL; break;
    case MD_FS_GETCAP: case MD_FS_GETACL: error=-ENODATA; break;
    default: error=-EOPNOTSUPP; break;
    }
    if (error) out->error=error;
    md_task_snapshot_release(&target);
    return 1;
}

int md_fs_proc_prepare(struct md_filesystem *fs) {
    int parent=md_fs_private_mounts_parent(fs->store);
    if (parent<0) return parent;
    struct md_provider_tree *tree=md_procfs_new(parent,NULL,fs->store->identity);
    int error=tree ? 0 : -errno;
    close(parent);
    struct md_view_location target={.fd=-1,.parent=-1};
    if (!error) error=md_view_walk(fs,AT_FDCWD,NULL,"/proc",1,0,MD_VIEW_MOUNT_TARGET,&target);
    /* The initial mount retains native PID numbering. Its network directories
     * and controls use the same task-selected provider as a guest proc mount. */
    struct md_mount_source source={.fd=tree ? md_provider_tree_root(tree) : -1,
        .backing=MD_MOUNT_PROC,.provider=tree,
        .policy=MD_MOUNT_NOSUID|MD_MOUNT_NODEV|MD_MOUNT_NOEXEC};
    if (!error) error=md_view_attach_source(fs,&source,&target,NULL);
    md_view_location_close(&target);
    md_provider_tree_release(tree);
    return error;
}
int md_fs_proc_operation(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (!mount || mount->backing!=MD_MOUNT_PROC || fd<0 || !fs->actor) return 0;
    char path[PATH_MAX];
    int r=md_procfs_path(mount,fd,path,sizeof(path));
    if (r) { out->error=r; return 1; }
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    enum md_proc_file_kind kind=(enum md_proc_file_kind)(ref.kind-MD_PROC_UID_MAP);
    int sysctl=md_proc_file_sysctl(kind);
    int user_control=ref.kind==MD_PROC_MAX_USER_NAMESPACES;
    int catalogue=md_proc_static(ref.kind);
    if (q->operation==MD_FS_STATFS) {
        md_fs_statfs_result(fd,md_view_policy_flags(mount),NULL,out,output);
        if (!out->error) ((struct md_fs_statfs *)output->data)->type=PROC_SUPER_MAGIC;
        return 1;
    }
    if (md_procfs_masked(path)) {
        switch (q->operation) {
        case MD_FS_PATH: case MD_FS_OBJECT_ID: return 0;
        case MD_FS_OPEN: case MD_FS_REOPEN:
            if (q->flags&O_PATH) return 0;
            r=-EACCES; break;
        case MD_FS_STAT: case MD_FS_FSTAT: {
            struct stat st;
            r=fstat(fd,&st) ? -errno : 0;
            if (!r) {
                st.st_mode&=S_IFMT;
                st.st_uid=st.st_gid=0;
                md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id;
            }
            break;
        }
        case MD_FS_ACCESS:
            r=(q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) || (q->mode&~7) ? -EINVAL
                : q->mode ? -EACCES : 0;
            break;
        case MD_FS_READLINK: r=-EINVAL; break;
        case MD_FS_GETCAP: case MD_FS_GETACL: r=-ENODATA; break;
        default: r=-EACCES; break;
        }
        if (r) out->error=r;
        return 1;
    }
    if (q->operation!=MD_FS_PATH && q->operation!=MD_FS_OBJECT_ID) {
        unsigned minimum=q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT
            || ((q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN) && (q->flags&O_PATH)) ? 2 : 1;
        r=md_procfs_check(fs,mount,path,minimum);
        if (r) { out->error=r; return 1; }
    }
    int network=md_network_namespace_managed(fs->actor->network);
    int network_control=network && md_proc_network_sysctl(ref.kind);
    enum md_proc_kind selected=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    if (selected==MD_PROC_PROCESS_DIRECTORY && (q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN)) {
        struct md_fs_anchor *origin=NULL;
        r=md_view_anchor_duplicate(fs,view,mount,fd,&origin);
        int handled=r ? 1 : md_fs_proc_task(fs,origin,path,q,out,output);
        md_fs_anchor_release(origin);
        if (r) out->error=r;
        if (handled) return 1;
    }
    if (selected==MD_PROC_PROCESS_DIRECTORY && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)) {
        struct stat st;
        r=fstat(fd,&st) ? -errno : md_procfs_ownership(fs,path,&st);
        if (!r) { md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id; }
        else out->error=r;
        return 1;
    }
    if (!sysctl && !network_control && !user_control && !catalogue && (q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN)
            && (q->flags&O_NOATIME) && !(q->flags&O_PATH)) {
        r=md_procfs_noatime(fs,fd,path);
        if (r) { out->error=r; return 1; }
    }
    if (!sysctl && !network_control && !user_control && !catalogue && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)
            && mount->provider) {
        int private=md_provider_tree_private(mount->provider,fd);
        if (private<0) { out->error=private; return 1; }
        if (private) {
            struct stat st;
            int netdir=network && !strncmp(path,"/proc/sys/net",13)
                && (!path[13] || path[13]=='/');
            r=(netdir ? fstat(fd,&st) : stat(path,&st)) ? -errno : 0;
            if (!r && netdir) {
                st.st_uid=md_user_namespace_into(md_network_namespace_owner(fs->actor->network),0,0);
                st.st_gid=md_user_namespace_into(md_network_namespace_owner(fs->actor->network),1,0);
                st.st_mode=S_IFDIR|0555;
            }
            if (!r) { md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id; }
            else out->error=r;
            return 1;
        }
    }
    if (!sysctl && !user_control && !catalogue && (!md_proc_network_sysctl(ref.kind)
            || !md_network_namespace_managed(fs->actor->network))) return 0;
    if (q->operation==MD_FS_PATH || q->operation==MD_FS_OBJECT_ID) return 0;
    /* Metadata/access do not need an open description or its carrier memfd. */
    if (sysctl && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)) {
        struct stat st;
        r=md_proc_sysctl_stat(kind,&st);
        if (!r) { md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id; }
        else out->error=r;
        return 1;
    }
    if (sysctl && q->operation==MD_FS_ACCESS) {
        if (q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) r=-EINVAL;
        else if ((q->mode&W_OK) && md_view_readonly(mount)) r=-EROFS;
        else r=md_proc_sysctl_access(kind,fs->store->identity,q->mode,!!(q->flags&MD_AT_EACCESS));
        if (r) out->error=r;
        return 1;
    }
    struct md_fs_anchor *origin=NULL;
    if (q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN)
        r=md_view_anchor_duplicate(fs,view,mount,fd,&origin);
    struct md_control_file file={0};
    if (!r) {
        file=sysctl ? md_proc_sysctl_new(kind,origin,fs->actor->uts)
            : catalogue ? md_proc_static_new(ref.kind,fs->store->identity->user_namespace,origin)
            : user_control ? md_user_sysctl_new(fs->store->identity->user_namespace,origin)
            : md_network_sysctl_new(fs->actor->network,
            (enum md_network_parameter)(ref.kind-MD_PROC_NET_UNPRIVILEGED_PORT),ref.interface,origin);
        if (!file.ops) r=-errno;
    }
    const struct md_identity *id=fs->store->identity;
    if (!r) switch (q->operation) {
    case MD_FS_STAT: case MD_FS_FSTAT: {
        struct stat st;
        r=file.ops->stat(file.context,&st);
        if (!r) { md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id; }
        break;
    }
    case MD_FS_ACCESS: {
        if (q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) { r=-EINVAL; break; }
        if ((q->mode&W_OK) && md_view_readonly(mount)) { r=-EROFS; break; }
        struct md_identity actor=md_identity_access(id,!!(q->flags&MD_AT_EACCESS));
        r=file.ops->access(file.context,&actor,q->mode); break;
    }
    case MD_FS_STATFS:
        md_fs_statfs_result(fd,md_view_policy_flags(mount),NULL,out,output);
        break;
    case MD_FS_OPEN: case MD_FS_REOPEN: {
        if (q->flags&O_DIRECTORY) { r=-ENOTDIR; break; }
        if (q->flags&~(O_ACCMODE|O_CLOEXEC|O_APPEND|O_TRUNC|O_NONBLOCK|O_NOCTTY|O_NOFOLLOW|O_PATH|O_CREAT|O_EXCL|O_LARGEFILE|O_NOATIME)) {
            r=-EOPNOTSUPP; break;
        }
        if (q->operation==MD_FS_REOPEN && (q->mode&MD_FS_REOPEN_MUTABLE)) { r=-EOPNOTSUPP; break; }
        if (!output || !output->control) { out->requires_control=1; r=-EOPNOTSUPP; break; }
        /* The descriptor host owns all data semantics; never reopen the
         * Android sysctl for read/write merely to supply a carrier. */
        int carrier;
        if (file.ops->descriptor) carrier=file.ops->descriptor(file.context,(int)q->flags);
        else { carrier=open("/dev/null",O_CLOEXEC|(q->flags&~(O_CREAT|O_EXCL|O_TRUNC|O_NOATIME))); if (carrier<0) carrier=-errno; }
        r=md_fs_control_publish(carrier,file,origin,id,(int)q->flags,out,output);
        break;
    }
    case MD_FS_READLINK: r=-EINVAL; break;
    case MD_FS_GETDENTS: r=-ENOTDIR; break;
    case MD_FS_GETCAP: case MD_FS_GETACL: r=-ENODATA; break;
    default: r=-EOPNOTSUPP; break;
    }
    md_control_file_release(file); md_fs_anchor_release(origin);
    if (r) out->error=r;
    return 1;
}
