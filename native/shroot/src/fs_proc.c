#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_control.h"
#include "fs_continuation.h"
#include "inode_internal.h"
#include "network_namespace.h"
#include "proc_paths.h"
#include "proc_sysctl.h"
#include "proc_static.h"
#include "user_sysctl.h"
#include "kernel_policy.h"
#include "procfs_view.h"
#include "linux_abi.h"
#include "file_permissions.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/magic.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>

int md_fs_proc_control_stat(struct md_filesystem *fs,struct md_control_file control,struct stat *st) {
    struct md_proc_file *file=md_proc_file_from_control(control);
    if (!file) return control.ops->stat(control.context,st);
    return md_proc_file_retained_stat(file,fs->tasks,0,st);
}
static int proc_task_apply(struct md_filesystem *fs,struct md_fs_anchor *origin,
        const struct md_task_snapshot *target,enum md_proc_file_kind kind,int backing,const struct stat *metadata,
        const void *directory,size_t directory_size,
        const struct md_fs_request *q,struct md_fs_result *out,const struct md_fs_output *output) {
    struct stat st;
    struct md_proc_file_node node;
    int error=0,observation=0;
    if (metadata) { st=*metadata; md_proc_file_task_metadata(target->tid,&target->identity,target->access,kind,&node,&st); }
    else if (md_proc_file_directory(kind) && origin && !md_fs_anchor_virtual(origin))
        error=md_proc_file_task_fd_stat(origin->fd,target->tid,&target->identity,target->access,kind,&node,&st);
    else error=md_proc_file_task_stat(target->tid,&target->identity,target->access,kind,&node,&st);
    *out=(struct md_fs_result){.fd=-1,.error=error};
    if (error) return error;
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
            file=md_proc_file_open(target->identity.user_namespace,kind,fs->store->identity,(int)q->flags);
            if (!file) error=-errno;
        }
        if (file) {
            md_proc_file_node(file,node);
            md_proc_file_pid_view(file,origin->mount->pid_namespace);
            md_proc_file_cgroup(file,target->cgroup);
            md_proc_file_place(file,origin,&st);
            if (backing>=0) error=md_proc_file_backing(file,backing,metadata,target->generation);
            if (!error && directory) error=md_proc_file_directory_snapshot(file,directory,directory_size);
            if (!error) {
                int descriptor=md_proc_file_descriptor(file);
                if (descriptor<0 && md_proc_file_directory(kind)) observation=descriptor;
                error=md_fs_control_publish(descriptor,md_proc_file_control(file),origin,
                    fs->store->identity,(int)q->flags,out,output);
            }
            md_proc_file_release(file);
        }
        break;
    }
    case MD_FS_STATFS:
        md_fs_statfs_result(origin->fd,md_fs_anchor_policy(origin),NULL,out,output);
        if (!out->error) ((struct md_fs_statfs *)output->data)->type=PROC_SUPER_MAGIC;
        break;
    case MD_FS_READLINK: error=q->path[0] && *q->path[0] ? -EINVAL : -ENOENT; break;
    case MD_FS_GETCAP: case MD_FS_GETACL: error=-ENODATA; break;
    default: error=-EOPNOTSUPP; break;
    }
    if (error) out->error=error;
    return observation;
}
struct proc_capture {
    struct md_fs_continuation base;
    struct md_fs_task_input input;
    struct md_fs_anchor *origin;
    struct md_task_snapshot target;
    enum md_proc_file_kind kind;
    int native_directory;
    char path[PATH_MAX];
};
static void proc_native_metadata(struct md_filesystem *fs,struct md_fs_anchor *origin,
        const char *path,const struct stat *metadata,const struct md_fs_request *q,struct md_fs_result *out) {
    struct stat st=*metadata;
    int error=md_procfs_ownership(fs,path,&st);
    if (!error && q->operation==MD_FS_ACCESS) {
        error=(q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) ? -EINVAL
            : (q->mode&W_OK) && md_view_readonly(origin->mount) ? -EROFS
            : md_procfs_task_access(fs,path,&st,q->mode,!!(q->flags&MD_AT_EACCESS));
    }
    *out=(struct md_fs_result){.fd=-1,.error=error};
    if (!error && q->operation!=MD_FS_ACCESS) {
        md_fs_stat_info(&st,&out->info); md_fs_anchor_mount_info(origin,&out->info);
    }
}
static int proc_capture_ready(struct md_fs_continuation *base) {
    return ((struct proc_capture *)base)->input.completed;
}
static void proc_capture_destroy(struct md_fs_continuation *base) {
    struct proc_capture *capture=(void *)base;
    if (capture->input.descriptor>=0) close(capture->input.descriptor);
    free(capture->input.directory);
    md_fs_anchor_release(capture->origin); md_task_snapshot_release(&capture->target);
    md_fs_continuation_clear(base); free(capture);
}
static void proc_capture_resume(struct md_fs_continuation *base,struct md_filesystem *fs,
        struct md_fs_result *out,const struct md_fs_output *output) {
    struct proc_capture *capture=(void *)base;
    int error=!capture->input.completed ? -EPROTO : capture->input.error;
    if (error) { *out=(struct md_fs_result){.fd=-1,.error=error}; return; }
    struct md_task_snapshot current={0};
    error=md_task_registry_proc(fs->tasks,capture->target.tid,&current);
    if (!error && current.generation!=capture->target.generation) error=-ESRCH;
    md_task_snapshot_release(&current);
    if (error) { *out=(struct md_fs_result){.fd=-1,.error=error}; return; }
    if (capture->native_directory) {
        const struct stat *st=&capture->input.metadata;
        if (st->st_dev!=capture->origin->device || st->st_ino!=capture->origin->inode) {
            *out=(struct md_fs_result){.fd=-1,.error=-ESTALE}; return;
        }
        if (base->request.operation==MD_FS_ACCESS || base->request.operation==MD_FS_STAT
                || base->request.operation==MD_FS_FSTAT) {
            proc_native_metadata(fs,capture->origin,capture->path,st,&base->request,out);
            return;
        }
        *out=(struct md_fs_result){.fd=capture->input.descriptor};
        capture->input.descriptor=-1;
        if (output && output->origin) *output->origin=md_fs_anchor_retain(capture->origin);
        return;
    }
    proc_task_apply(fs,capture->origin,&capture->target,capture->kind,capture->input.descriptor,&capture->input.metadata,
        capture->input.directory,capture->input.directory_size,
        &base->request,out,output);
}
static void proc_capture_begin(struct md_fs_anchor *origin,struct md_task_snapshot *target,
        enum md_proc_file_kind kind,const char *path,unsigned flags,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (!output || !output->continuation || !output->task_capture) {
        out->requires_task_capture=1; return;
    }
    struct proc_capture *capture=calloc(1,sizeof(*capture));
    int error=capture ? md_fs_continuation_capture(&capture->base,q) : -ENOMEM;
    if (error) { free(capture); out->error=error; return; }
    capture->origin=md_fs_anchor_retain(origin); capture->target=*target; *target=(struct md_task_snapshot){0};
    capture->kind=kind;
    capture->native_directory=path!=NULL;
    if (path) snprintf(capture->path,sizeof(capture->path),"%s",path);
    else snprintf(capture->path,sizeof(capture->path),"/proc/%d/%s",capture->target.tid,md_proc_file_process_name(kind));
    capture->input=(struct md_fs_task_input){.request={capture->path,flags},
        .subject={capture->target.tid,capture->target.generation},.descriptor=-1,
        .directory_limit=!path && kind==MD_PROC_FILE_PROCESS_DIRECTORY && !(flags&O_PATH) ? 65536 : 0};
    capture->base.task_input=&capture->input;
    capture->base.ready=proc_capture_ready; capture->base.resume=proc_capture_resume;
    capture->base.destroy=proc_capture_destroy;
    *output->continuation=&capture->base;
    *out=(struct md_fs_result){.fd=-1,.error=-EINPROGRESS};
}
int md_fs_proc_capture_open(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,const struct stat *metadata,unsigned flags,int denied,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (!fs->actor || (flags&O_ACCMODE)!=O_RDONLY || (flags&(O_CREAT|O_EXCL|O_TRUNC))) return 0;
    struct stat st;
    if (metadata) st=*metadata;
    else if (fstat(fd,&st)) return 0;
    if (!S_ISDIR(st.st_mode)) return 0;
    char path[PATH_MAX];
    if (md_procfs_path(mount,fd,path,sizeof(path))) return 0;
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    struct md_task_snapshot target={0};
    if (!ref.process || md_task_registry_proc(fs->tasks,ref.process,&target)) return 0;
    int allowed=target.tgid==fs->actor->tgid || (fs->store->identity
        && md_identity_capable_in(fs->store->identity,target.identity.user_namespace,CAP_SYS_PTRACE));
    struct md_fs_anchor *origin=NULL;
    if (allowed) {
        struct stat logical=st;
        int error=md_procfs_ownership(fs,path,&logical);
        if (!error && !(flags&O_PATH)) error=md_procfs_task_access(fs,path,&logical,R_OK,1);
        if (!error) error=md_view_anchor_pin(fs,view,mount,fd,&st,&origin);
        *out=(struct md_fs_result){.fd=-1,.error=error ? error : denied};
        if (!error) proc_capture_begin(origin,&target,MD_PROC_FILE_PROCESS_DIRECTORY,path,flags|O_CLOEXEC,q,out,output);
    }
    md_fs_anchor_release(origin); md_task_snapshot_release(&target);
    return allowed;
}
int md_fs_proc_task(struct md_filesystem *fs,struct md_fs_anchor *origin,const char *path,
        const struct md_fs_request *q,struct md_fs_result *out,const struct md_fs_output *output) {
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    enum md_proc_kind selected=ref.kind==MD_PROC_FOREIGN ? ref.foreign_kind : ref.kind;
    enum md_proc_file_kind kind=(enum md_proc_file_kind)(selected-MD_PROC_UID_MAP);
    if (!md_proc_file_task(kind) || !fs->actor) return 0;
    if (q->operation==MD_FS_WATCH_PATH && md_proc_native_task(selected)) return 0;
    struct md_task_snapshot target={0};
    int error=md_task_registry_proc(fs->tasks,ref.process,&target);
    if (error==-ESRCH) return 0;
    if (error) { *out=(struct md_fs_result){.fd=-1,.error=error}; return 1; }
    int observation=proc_task_apply(fs,origin,&target,kind,-1,NULL,NULL,0,q,out,output);
    /* Only denied external observation needs task-affine capture. The admitted
     * mount and credentials remain pinned; no pathname walk or mutation repeats. */
    int inspect=target.tgid==fs->actor->tgid || (fs->store->identity
        && md_identity_capable_in(fs->store->identity,target.identity.user_namespace,CAP_SYS_PTRACE));
    if ((observation==-EACCES || observation==-EPERM || observation==-ENOENT) && inspect) {
        unsigned flags=O_CLOEXEC;
        if (md_proc_file_directory(kind)) {
            int metadata_only=q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT
                || q->operation==MD_FS_ACCESS || (q->flags&O_PATH);
            flags|=O_DIRECTORY|(metadata_only ? O_PATH : O_RDONLY);
        }
        proc_capture_begin(origin,&target,kind,NULL,flags,q,out,output);
    }
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
        struct md_view_mount *mount,int fd,const struct stat *metadata,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    if (!mount || mount->backing!=MD_MOUNT_PROC || fd<0 || !fs->actor) return 0;
    char path[PATH_MAX];
    int r=md_procfs_path(mount,fd,path,sizeof(path));
    if (r) { out->error=r; return 1; }
    struct md_proc_path ref=md_proc_path_for(path,0,0);
    enum md_proc_file_kind kind=(enum md_proc_file_kind)(ref.kind-MD_PROC_UID_MAP);
    int task_path=ref.process && !strncmp(path,"/proc/",6) && path[6]>='0' && path[6]<='9';
    int sysctl=md_proc_file_sysctl(kind);
    int user_control=ref.kind==MD_PROC_MAX_USER_NAMESPACES;
    int catalogue=md_proc_static(ref.kind);
    if (mount->provider && (!strcmp(path,"/proc/uptime") || !strcmp(path,"/proc/stat"))) {
        int fallback=md_provider_tree_private(mount->provider,fd);
        if (fallback<0) { out->error=fallback; return 1; }
        if (fallback) { catalogue=1; ref.kind=!strcmp(path,"/proc/uptime") ? MD_PROC_UPTIME : MD_PROC_SYSTEM_STAT; }
    }
    int kernel_policy=ref.kind==MD_PROC_KERNEL_POLICY;
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
    if (!strcmp(path,"/proc/version") && mount->provider) {
        int snapshot=md_provider_tree_private(mount->provider,fd);
        if (snapshot<0) { out->error=snapshot; return 1; }
        if (snapshot) {
            switch (q->operation) {
            case MD_FS_PATH: case MD_FS_OBJECT_ID: return 0;
            case MD_FS_STAT: case MD_FS_FSTAT: {
                struct stat st;
                r=fstat(fd,&st) ? -errno : 0;
                if (!r) {
                    st.st_uid=st.st_gid=0; st.st_mode=S_IFREG|0444;
                    st.st_size=st.st_blocks=0;
                    md_fs_stat_info(&st,&out->info); out->info.mount_id=mount->id;
                }
                break;
            }
            case MD_FS_OPEN: case MD_FS_REOPEN:
                if (q->flags&O_PATH) return 0;
                if (!(q->flags&(O_ACCMODE|O_TRUNC))) {
                    if (!(q->flags&O_NOATIME)) return 0;
                    struct stat st={.st_uid=0,.st_gid=0,.st_mode=S_IFREG|0444};
                    r=md_file_noatime(&st,fs->store->identity);
                    if (!r) return 0;
                    break;
                }
                r=md_view_readonly(mount) ? -EROFS : -EACCES; break;
            case MD_FS_ACCESS:
                r=(q->flags&~(AT_SYMLINK_NOFOLLOW|MD_AT_EACCESS)) || (q->mode&~7U) ? -EINVAL
                    : (q->mode&W_OK) && md_view_readonly(mount) ? -EROFS
                    : q->mode&(W_OK|X_OK) ? -EACCES : 0;
                break;
            case MD_FS_READLINK: r=-EINVAL; break;
            case MD_FS_GETCAP: case MD_FS_GETACL: r=-ENODATA; break;
            default: return 0;
            }
            if (r) out->error=r;
            return 1;
        }
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
    if (selected==MD_PROC_PROCESS_DIRECTORY && (q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN
            || q->operation==MD_FS_ACCESS)) {
        struct md_fs_anchor *origin=NULL;
        if (q->origin[0] && q->origin[0]->view==view && q->origin[0]->mount==mount
                && q->origin[0]->fd==fd)
            origin=md_fs_anchor_retain((struct md_fs_anchor *)q->origin[0]);
        else r=metadata ? md_view_anchor_pin(fs,view,mount,fd,metadata,&origin)
            : md_view_anchor_duplicate(fs,view,mount,fd,&origin);
        int handled=r ? 1 : md_fs_proc_task(fs,origin,path,q,out,output);
        md_fs_anchor_release(origin);
        if (r) out->error=r;
        if (handled) return 1;
    }
    if (selected==MD_PROC_PROCESS_DIRECTORY && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)) {
        struct stat st;
        if (metadata) st=*metadata;
        else if (fstat(fd,&st)) r=-errno;
        if (!r) r=md_procfs_ownership(fs,path,&st);
        if (!r) {
            md_fs_stat_info(&st,&out->info);
            md_fs_info_mount(&out->info,mount->id,md_view_same(&st,&mount->native));
        }
        else out->error=r;
        return 1;
    }
    if (task_path && (q->operation==MD_FS_ACCESS || q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)) {
        struct md_fs_anchor *origin=NULL;
        if (q->origin[0] && q->origin[0]->view==view && q->origin[0]->mount==mount && q->origin[0]->fd==fd)
            origin=md_fs_anchor_retain((struct md_fs_anchor *)q->origin[0]);
        else r=metadata ? md_view_anchor_pin(fs,view,mount,fd,metadata,&origin)
            : md_view_anchor_duplicate(fs,view,mount,fd,&origin);
        struct stat st;
        if (!r) {
            if (metadata) st=*metadata;
            else if (fstat(fd,&st)) r=-errno;
        }
        *out=(struct md_fs_result){.fd=-1,.error=r};
        if (!r) proc_native_metadata(fs,origin,path,&st,q,out);
        else if (origin && md_fs_anchor_kind(origin)==S_IFDIR && (r==-EACCES || r==-EPERM || r==-ENOENT)) {
            struct md_task_snapshot target={0};
            if (!md_task_registry_proc(fs->tasks,ref.process,&target)) {
                int inspect=target.tgid==fs->actor->tgid || (fs->store->identity
                    && md_identity_capable_in(fs->store->identity,target.identity.user_namespace,CAP_SYS_PTRACE));
                if (inspect) proc_capture_begin(origin,&target,MD_PROC_FILE_PROCESS_DIRECTORY,path,
                    O_PATH|O_DIRECTORY|O_CLOEXEC,q,out,output);
                md_task_snapshot_release(&target);
            }
        }
        md_fs_anchor_release(origin); return 1;
    }
    if (task_path && (q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN) && !(q->flags&O_PATH)) {
        struct stat st;
        if (metadata) st=*metadata;
        else if (fstat(fd,&st)) r=-errno;
        if (!r) r=md_procfs_ownership(fs,path,&st);
        if (!r) r=(q->flags&(O_ACCMODE|O_TRUNC)) && md_view_readonly(mount) ? -EROFS
            : md_procfs_open_access(fs,path,&st,q->flags);
        if (r) { out->error=r; return 1; }
    }
    if (!sysctl && !network_control && !user_control && !catalogue && !kernel_policy && (q->operation==MD_FS_OPEN || q->operation==MD_FS_REOPEN)
            && (q->flags&O_NOATIME) && !(q->flags&O_PATH)) {
        r=md_procfs_noatime(fs,fd,path);
        if (r) { out->error=r; return 1; }
    }
    if (!sysctl && !network_control && !user_control && !catalogue && !kernel_policy && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)
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
    if (!sysctl && !user_control && !catalogue && !kernel_policy && (!md_proc_network_sysctl(ref.kind)
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
    if (!r && kernel_policy) {
        struct md_kernel_policy *policy=md_task_registry_kernel_policy(fs->tasks);
        if (!policy) r=-errno;
        else file=md_kernel_policy_file(policy,ref.parameter,origin);
        md_kernel_policy_release(policy);
        if (!r && !file.ops) r=-errno;
    } else if (!r) {
        file=sysctl ? md_proc_sysctl_new(kind,origin,fs->actor->uts)
            : md_proc_host_sample(ref.kind) ? md_proc_host_sample_new(ref.kind,fs->store->identity->user_namespace,origin,fd,
                fs->actor ? fs->actor->cgroup : NULL)
            : catalogue ? md_proc_static_new(ref.kind,fs->store->identity->user_namespace,origin,fs->actor->network)
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
    case MD_FS_READLINK: r=q->path[0] && *q->path[0] ? -EINVAL : -ENOENT; break;
    case MD_FS_GETDENTS: r=-ENOTDIR; break;
    case MD_FS_GETCAP: case MD_FS_GETACL: r=-ENODATA; break;
    default: r=-EOPNOTSUPP; break;
    }
    md_control_file_release(file); md_fs_anchor_release(origin);
    if (r) out->error=r;
    return 1;
}
