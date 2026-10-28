#define _GNU_SOURCE
#include "readonly_file.h"
#include "full_device.h"
#include "fs_control.h"
#include "fs_engine.h"
#include "fs_continuation.h"
#include "fuse_view.h"
#include "shm_bridge.h"
#include "fs_native.h"
#include "fd_filesystem.h"
#include "proc_fdinfo.h"
#include "procfs_view.h"
#include "fs_context.h"
#include "image_catalogue.h"
#include "inode_watch.h"
#include "fs_mount_internal.h"
#include "mount_graph.h"
#include "fs_storage.h"
#include "task_registry.h"
#include "ipc_credentials.h"
#include "inode_internal.h"
#include "inode_lifetime.h"
#include "linux_abi.h"
#include "task_status.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <linux/mount.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <sys/statvfs.h>
#include <sys/sysmacros.h>
#include <unistd.h>

void md_fs_statistics_report(int fd,const struct md_fs_statistics *stats) {
    for (unsigned i=MD_FS_CREATE;i<=MD_FS_LAST;++i) if (stats->operation[i].calls)
        dprintf(fd,"MD_FS operation=%u calls=%llu ns=%llu name=%s\n",i,
            (unsigned long long)stats->operation[i].calls,
            (unsigned long long)stats->operation[i].nanoseconds,md_fs_operation_name(i));
    const char *names[]={"reconcile","attach"};
    const struct md_cost *costs[]={&stats->mount_reconcile,&stats->mount_attach};
    for (unsigned i=0;i<sizeof(costs)/sizeof(*costs);++i) if (costs[i]->calls)
        dprintf(fd,"MD_MOUNT phase=%s calls=%llu ns=%llu\n",names[i],
            (unsigned long long)costs[i]->calls,(unsigned long long)costs[i]->nanoseconds);
}

void md_fs_view_execute(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_fs_request logical;
    for (unsigned i=0;i<2;++i) if (q->origin[i] && q->origin[i]->endpoint.inode) {
        int error=md_fs_anchor_check_fd(q->origin[i],q->directory[i]);
        if (error) { *out=(struct md_fs_result){.fd=-1,.error=error}; return; }
        if (q!=&logical) { logical=*q; q=&logical; }
        if (!(md_fs_operation_data_inputs(q->operation)&(1U<<i)))
            logical.directory[i]=md_fs_anchor_fd(q->origin[i]);
    }
    int native=q->origin[0] && !q->origin[0]->view
        && (!q->path[0] || q->path[0][0]!='/' || (q->resolve&RESOLVE_IN_ROOT));
    if (native) *out=(struct md_fs_result){.fd=-1,
        .error=md_view_anchor_check(fs,q->directory[0],q->origin[0])};
    if (native && !out->error) out->error=-EXDEV;
    else if (!native && (md_mount_namespace_attached(fs->mounts) || q->origin[0] || q->origin[1]
            || md_view_policy_walk(fs,q)))
        md_fs_mounts_execute(fs,q,out,output);
    else if (!native) md_fs_inode_execute(fs,q,out,output);
    /* An unannotated proc/dev dirfd can predate a root change. A rejected inode
     * lookup has performed no operation on that native object; hand its path
     * to the caller's proc provider, never resolve self on this worker. */
    if (out->error==-EXDEV && (!q->origin[0] || native) && q->directory[0]>=0
            && q->path[0] && *q->path[0] && q->path[0][0]!='/') {
        /* The entire original dirfd/path pair is still available to the task.
         * It applies openat2 constraints in the kernel, without a new root. */
        int error=md_view_task_path(fs,q->directory[0],q->path[0],"",0,1);
        if (error) out->error=error;
    }
}
static void root_opened(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *opened,struct md_fs_anchor **origin,struct md_fs_result *out,
        const struct md_fs_output *output) {
    (void)q; (void)output;
    *out=(struct md_fs_result){.fd=-1,.error=opened->error};
    if (out->error) return;
    struct md_fs_location root;
    int error=md_fs_root_capture(fs,opened->fd,*origin,&root);
    if (!error) error=fs->actor ? md_domain_set_root(fs->actor->fs,&root) : -ENOTSUP;
    out->error=error==-EXDEV ? -ENOTSUP : error;
}
static void change_root(struct md_filesystem *fs, struct md_guest_domain *context,
        const struct md_fs_request *q, struct md_fs_result *out,const struct md_fs_output *output) {
    *out = (struct md_fs_result){.fd = -1};
    if (!context) { out->error = -ENOTSUP; return; }
    if (q->origin[0] && q->origin[0]->view && q->origin[0]->view!=fs->mounts && q->directory[0]!=MD_INODE_ROOT
            && (!q->path[0] || q->path[0][0]!='/')) { out->error=-ENOTSUP; return; }
    if (!md_identity_may_chroot(fs->store->identity)) { out->error = -EPERM; return; }
    if (q->directory[0]>=0 && q->path[0] && !*q->path[0]) {
        struct md_fs_location root;
        out->error=md_fs_root_capture(fs,q->directory[0],q->origin[0],&root);
        if (!out->error) out->error=md_domain_set_root(context,&root);
        return;
    }
    struct md_fs_request open = *q;
    open.operation = MD_FS_OPEN; open.flags = O_PATH | O_DIRECTORY | O_CLOEXEC;
    md_fs_view_then(fs,q,&open,out,output,root_opened);
}
static int watch_add(struct md_filesystem *fs, int instance, int object,
        const struct md_fs_anchor *origin, unsigned flags) {
    struct md_view_mount *mount=origin ? origin->mount : NULL;
    if (origin) {
        int r=md_view_anchor_check(fs,object,origin);
        if (r) return r;
    } else {
        struct md_view_object *entry=NULL;
        int r=md_view_identity(fs,object,&entry);
        if (r && r!=-EXDEV) return r;
        if (!r) mount=entry->mount;
    }
    if (mount && md_fuse_view_matches(mount->provider))
        return md_fuse_view_watch(mount->provider,fs->store,instance,object,flags);
    if (mount && mount->cgroup) {
        int r=md_cgroup_watch(mount->cgroup,fs->store,instance,object,flags);
        if (r!=-EOPNOTSUPP) return r;
    }
    if (!md_view_inode(mount)) return md_inode_watch_add(fs->store,instance,object,flags);
    struct md_fs_storage_scope scope;
    int r=md_fs_storage_enter(fs,mount,&scope);
    if (!r) {
        r=md_inode_watch_add_from(fs->store,scope.store,instance,object,flags);
        md_fs_storage_leave(&scope);
    }
    return r;
}
static void watch_opened(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *opened,struct md_fs_anchor **origin,struct md_fs_result *out,
        const struct md_fs_output *output) {
    (void)output;
    *out=(struct md_fs_result){.fd=-1,.error=opened->error};
    if (out->error) return;
    int wd=watch_add(fs,q->directory[1],opened->fd,*origin,q->flags);
    if (wd<0) out->error=wd; else out->position=wd;
}
static void watch_path(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_fs_request open=*q;
    open.operation=MD_FS_OPEN; open.flags=O_PATH|O_CLOEXEC|(q->flags&IN_DONT_FOLLOW ? O_NOFOLLOW : 0);
    open.directory[1]=-1; open.origin[1]=NULL;
    md_fs_view_then(fs,q,&open,out,output,watch_opened);
}
static int reject_native_control(void *context,const struct md_control_file_ops *ops,
        void *data,const struct md_identity *identity,int flags,struct md_fs_anchor *origin) {
    (void)context; (void)ops; (void)data; (void)identity; (void)flags; (void)origin;
    /* A typed provider's carrier is not authority for a native path mutation. */
    return -EOPNOTSUPP;
}
static void task_accessed(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *access,struct md_fs_anchor **unused,struct md_fs_result *out,
        const struct md_fs_output *output) {
    (void)unused;
    int directory=q->mode==MD_FS_TASK_CHDIR || q->mode==MD_FS_TASK_FCHDIR;
    *out=(struct md_fs_result){.fd=-1,.error=access->error};
    int fd=q->directory[0];
    if (out->error==-EXDEV) out->error=syscall(SYS_faccessat2,fd,"",directory ? X_OK : W_OK,
        AT_EMPTY_PATH|MD_AT_EACCESS) ? -errno : 0;
    if (out->error) return;
    if (directory) {
        out->fd=fcntl(fd,F_DUPFD_CLOEXEC,3);
        if (out->fd<0) out->error=-errno;
        else if (output && output->origin) *output->origin=md_fs_anchor_retain((struct md_fs_anchor *)q->origin[0]);
    } else {
        struct stat st;
        if (fstat(fd,&st)) out->error=-errno;
        else if (!S_ISREG(st.st_mode)) out->error=S_ISDIR(st.st_mode) ? -EISDIR : -EINVAL;
        if (!out->error) {
            struct md_fs_request reopen={.operation=MD_FS_REOPEN,.directory={fd,-1},
                .origin={q->origin[0],NULL},.mode=MD_FS_REOPEN_NATIVE,.flags=O_WRONLY|O_CLOEXEC};
            struct md_fs_output capture=output ? *output : (struct md_fs_output){0};
            capture.control=reject_native_control; capture.control_context=NULL;
            md_fs_view_execute(fs,&reopen,out,&capture);
        }
    }
}
static void task_opened(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *opened,struct md_fs_anchor **origin,struct md_fs_result *out,
        const struct md_fs_output *output) {
    *out=(struct md_fs_result){.fd=-1,.error=opened->error};
    if (out->error) return;
    int directory=q->mode==MD_FS_TASK_CHDIR || q->mode==MD_FS_TASK_FCHDIR;
    struct md_fs_request selected={.operation=MD_FS_ACCESS,.directory={opened->fd,-1},
        .origin={*origin,NULL},.mode=directory ? X_OK : W_OK,.flags=MD_AT_EACCESS};
    struct md_fs_request next=*q;
    next.directory[0]=opened->fd; next.origin[0]=*origin; next.path[0]=NULL;
    md_fs_view_then(fs,&next,&selected,out,output,task_accessed);
}
static void task_path(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    int directory=q->mode==MD_FS_TASK_CHDIR || q->mode==MD_FS_TASK_FCHDIR;
    struct md_fs_request open=*q;
    open.operation=MD_FS_OPEN; open.mode=0; open.offset=0;
    open.flags=O_PATH|O_CLOEXEC|(directory ? O_DIRECTORY : 0);
    if (!q->path[0] || !*q->path[0]) {
        open.operation=MD_FS_REOPEN; open.mode=MD_FS_REOPEN_NATIVE;
    }
    md_fs_view_then(fs,q,&open,out,output,task_opened);
}

uint64_t md_fs_statfs_policy(unsigned policy) {
    return ((policy&MD_MOUNT_READONLY) ? MS_RDONLY : 0)
        | ((policy&MD_MOUNT_NOSUID) ? MS_NOSUID : 0) | ((policy&MD_MOUNT_NODEV) ? MS_NODEV : 0)
        | ((policy&MD_MOUNT_NOEXEC) ? MS_NOEXEC : 0)
        | ((policy&MD_MOUNT_NOSYMFOLLOW) ? ST_NOSYMFOLLOW : 0);
}
void md_fs_statfs_result(int fd, unsigned policy, const struct md_inode_lifetime *storage,
        struct md_fs_result *out, const struct md_fs_output *output) {
    if (!output || !output->data || output->capacity<sizeof(struct md_fs_statfs)) {
        out->error=-ERANGE; return;
    }
    struct statfs native;
    int error=md_fd_filesystem(fd,&native);
    if (error) { out->error=error; return; }
    struct md_fs_statfs value={.type=native.f_type,.block_size=native.f_bsize,
        .blocks=native.f_blocks,.free_blocks=native.f_bfree,.available_blocks=native.f_bavail,
        .files=native.f_files,.free_files=native.f_ffree,.name_length=native.f_namelen,
        .fragment_size=native.f_frsize,.flags=native.f_flags};
    _Static_assert(sizeof(value.fsid)==sizeof(native.f_fsid),"statfs filesystem identity");
    memcpy(value.fsid,&native.f_fsid,sizeof(value.fsid));
    if (storage) value.flags&=~(uint64_t)(MS_RDONLY|MS_NOSUID|MS_NODEV);
    value.flags|=md_fs_statfs_policy(policy);
    if (storage && storage->filesystem_kind) {
        value.type=md_filesystem_magic(storage->filesystem_kind);
        uint64_t id=(uint64_t)storage->inode;
        memcpy(value.fsid,&id,sizeof(value.fsid));
    }
    memcpy(output->data,&value,sizeof(value)); out->size=sizeof(value);
}
int md_fs_context_begin(struct md_filesystem *fs, int actor, int peer, struct md_fs_context *context) {
    *context=(struct md_fs_context){0};
    if (!fs || !fs->store) return -EINVAL;
    if (fs->tasks) {
        int error=md_task_registry_read(fs->tasks,actor,peer,&context->task);
        if (error) return error;
        context->authenticated=context->has_identity=1;
    } else if (fs->store->identity) {
        context->task.identity=md_identity_copy(fs->store->identity);
        context->has_identity=1;
    }
    context->filesystem=fs; context->actor=actor; context->peer=peer;
    return 0;
}
void md_fs_context_end(struct md_fs_context *context) {
    md_task_snapshot_release(&context->task);
    *context=(struct md_fs_context){0};
}
struct md_inode_store *md_fs_context_operation_window(const struct md_fs_context *c,const struct md_fs_request *q,int native_plan) {
    if (!c->filesystem || q->control.ops) return NULL;
    unsigned operands=1;
    switch (q->operation) {
    case MD_FS_OPEN:
        if (!md_open_readonly_plan(q->flags) && !(native_plan && md_open_native_plan(q->flags,1))) return NULL;
        break;
    case MD_FS_LINK: case MD_FS_RENAME: operands=2; break;
    case MD_FS_CREATE: case MD_FS_MKDIR: case MD_FS_MKNOD: case MD_FS_SYMLINK:
    case MD_FS_UNLINK: case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_UTIMENS:
    case MD_FS_STAT: case MD_FS_FSTAT: case MD_FS_STATFS: case MD_FS_ACCESS: case MD_FS_GETDENTS:
    case MD_FS_READLINK: case MD_FS_XATTR: case MD_FS_FXATTR: break;
    default: return NULL;
    }
    const struct md_mount_namespace *mounts=c->authenticated ? c->task.mounts : c->filesystem->mounts;
    for (unsigned i=0;i<operands;++i) {
        if (q->object[i]) return NULL;
        if (q->path[i] && q->path[i][0]=='/' && !(q->resolve&RESOLVE_IN_ROOT)) {
            if (q->origin[i]) return NULL;
            continue;
        }
        const struct md_fs_anchor *origin=q->origin[i];
        /* Capture under the cwd/file-table owner precedes this admission.
         * A foreign or detached anchor may enter another provider/store. */
        if (!origin || !mounts || origin->view!=mounts || origin->mount!=&mounts->base) return NULL;
        if (q->directory[i]!=md_fs_anchor_fd(origin)) {
            /* Directory bytes use the captured OFD, never its O_PATH pin.
             * Cursor ownership still precedes this bounded store window. */
            if (q->operation!=MD_FS_GETDENTS || origin->kind!=S_IFDIR
                    || md_fs_anchor_check_fd(origin,q->directory[i])) return NULL;
        }
    }
    /* The local inode walker revokes retention before a mount/provider
     * crossing. Foreign topology imports and restricted roots retain their
     * independent admission contracts. */
    if (md_mount_graph_foreign(mounts)
            || (c->authenticated && md_domain_restricted(c->task.fs))) return NULL;
    return c->filesystem->store;
}
void md_fs_context_capture_result(struct md_fs_context *context, struct md_fs_result *result,
        struct md_fs_anchor **origin) {
    struct md_fs_output output={.origin=origin};
    md_view_capture(context->filesystem,context->authenticated ? context->task.mounts
        : context->filesystem->mounts,NULL,result,&output);
}
void md_fs_execute(struct md_filesystem *fs,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_fs_context context;
    int error=md_fs_context_begin(fs,q->actor,q->peer,&context);
    if (error) {
        if (output && output->origin) *output->origin=NULL;
        *out=(struct md_fs_result){.fd=-1,.error=error}; return;
    }
    md_fs_context_execute(&context,q,out,output);
    md_fs_context_end(&context);
}
int md_fs_ipc_authority(struct md_filesystem *fs, struct md_ipc_credentials **out) {
    if (!fs || !out) return -EINVAL;
    if (!fs->ipc) {
        if (!fs->ipc_store) return -ENOTSUP;
        int error=md_ipc_credentials_open(fs->ipc_store,MD_IPC_TRANSPORT_ORIGINS_V1,&fs->ipc);
        if (error) return error;
    }
    *out=fs->ipc; return 0;
}
static void context_execute(struct md_fs_context *context,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output,
        struct md_fs_continuation *continuation) {
    struct md_filesystem *fs=context->filesystem;
    if (output && output->origin) *output->origin=NULL;
    if (output && output->continuation) *output->continuation=NULL;
    if (!fs || context->actor!=q->actor || context->peer!=q->peer) {
        *out=(struct md_fs_result){.fd=-1,.error=fs ? -EPERM : -EINVAL}; return;
    }
    struct md_fs_request captured;
    if (q->attributes.creation_mask==MD_FS_CAPTURE_UMASK) {
        /* Only creation consumes umask. This applies equally to a caller-side
         * pin and an ordinary worker open; neither may read /proc for it. */
        int existing=q->operation==MD_FS_OPEN && !(q->flags&O_CREAT)
            && (q->flags&O_TMPFILE)!=O_TMPFILE;
        long mask=context->authenticated ? (existing ? 0 : md_task_snapshot_umask(&context->task)) : -EPERM;
        if (mask<0) { *out=(struct md_fs_result){.fd=-1,.error=(int)mask}; return; }
        captured=*q; captured.attributes.creation_mask=(unsigned)mask; q=&captured;
    }
    if (output && output->origin) {
        if (!md_fs_operation_has_origin(q->operation)) {
            *out=(struct md_fs_result){.fd=-1,.error=-EINVAL}; return;
        }
    }
    if (q->operation==MD_FS_UMASK) {
        long mask=context->authenticated ? md_task_snapshot_umask(&context->task) : md_task_umask(context->actor);
        *out=(struct md_fs_result){.fd=-1,.error=mask<0 ? (int)mask : 0,
            .position=mask<0 ? 0 : mask};
        return;
    }
    const struct md_task_snapshot *task=&context->task;
    if (q->operation==MD_FS_SHM_CHANNEL) {
        int fd=context->authenticated ? md_shm_bridge_connect(&fs->shm_bridge,task) : -EACCES;
        *out=(struct md_fs_result){.fd=fd<0 ? -1 : fd,.error=fd<0 ? fd : 0};
        return;
    }
    struct md_identity identity=task->identity;
    const struct md_identity *previous = fs->store->identity;
    const struct md_landlock_policy *previous_landlock=fs->store->landlock;
    int previous_execute=fs->store->image_open;
    if (q->operation == MD_FS_IPC || q->operation==MD_FS_IPC_PEER) {
        struct md_ipc_credentials *ipc=NULL;
        int error=md_fs_ipc_authority(fs,&ipc);
        if (error) *out=(struct md_fs_result){.fd=-1,.error=error};
        else md_ipc_credentials_execute(ipc, &identity, task->pid_group, q, out, output);
        return;
    }
    if (context->authenticated && !task->mounts) {
        *out=(struct md_fs_result){.fd=-1,.error=-ENOTSUP};
        return;
    }
    if (context->has_identity && q->operation == MD_FS_ACCESS)
        identity=md_identity_access(&identity,!!(q->flags&MD_AT_EACCESS));
    fs->store->identity = context->has_identity ? &identity : NULL;
    unsigned previous_mask=fs->store->creation_mask;
    const char *previous_root=fs->store->root_id;
    struct md_view_object *previous_native_root=fs->native_root;
    struct md_mount_namespace *previous_mounts=fs->mounts;
    const struct md_task_snapshot *previous_actor=fs->actor;
    fs->actor=context->authenticated ? task : NULL;
    fs->store->landlock=context->authenticated ? task->landlock : NULL;
    fs->store->image_open=q->operation==MD_FS_OPEN_IMAGE
        || (q->operation==MD_FS_OPEN_OBJECT && (q->mode&MD_FS_OBJECT_EXECUTE))
        || (q->operation==MD_FS_REOPEN && (q->mode&MD_FS_REOPEN_EXECUTE));
    if (context->authenticated) fs->mounts=task->mounts;
    int root_error=md_mount_graph_sync(fs->mounts);
    for (unsigned i=0;!root_error && i<2;++i)
        if (q->origin[i] && q->origin[i]->view!=fs->mounts)
            root_error=md_mount_graph_sync(q->origin[i]->view);
    if (!root_error && context->authenticated) root_error=md_fs_root_select(fs,&task->root);
    struct md_inode_boundary boundary={.path=output ? output->data : NULL,.capacity=output ? output->capacity : 0};
    if (q->operation==MD_FS_OPEN && (q->flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW)
            && q->directory[1]>=0) boundary.proc_link_descriptor=(unsigned)q->directory[1]+1;
    struct md_inode_boundary *previous_boundary=fs->store->boundary;
    fs->store->boundary=&boundary;
    fs->store->creation_mask=q->attributes.creation_mask;
    if (root_error) *out=(struct md_fs_result){.fd=-1,.error=root_error};
    else if (task->landlock && (q->operation==MD_FS_MOUNT || q->operation==MD_FS_FSMOUNT
            || q->operation==MD_FS_OPEN_TREE))
        *out=(struct md_fs_result){.fd=-1,.error=-EPERM};
    else if (continuation) continuation->resume(continuation,fs,out,output);
    else if (q->operation == MD_FS_CHROOT) change_root(fs, task->fs, q, out,output);
    else if (q->operation==MD_FS_MOUNT)
        md_fs_mount_execute(fs,q,out,output);
    else if (q->operation==MD_FS_FSOPEN || q->operation==MD_FS_FSCONFIG || q->operation==MD_FS_FSMOUNT
            || q->operation==MD_FS_FSPICK)
        md_fs_context_mount(fs,q,out,output);
    else if (q->operation==MD_FS_OPEN_TREE) md_fs_open_tree(fs,q,out,output);
    else if (q->operation==MD_FS_BPF) md_fs_bpf(fs,q,out,output);
    else if (q->operation==MD_FS_LANDLOCK) md_fs_landlock(fs,q,out,output);
    else if (q->operation==MD_FS_FDINFO) md_fs_fdinfo(fs,q,out,output);
    else if (q->operation == MD_FS_WATCH_PATH) watch_path(fs,q,out,output);
    else if (q->operation == MD_FS_WATCH_ADD) {
        int wd=watch_add(fs,q->directory[0],q->directory[1],q->origin[1],q->flags);
        *out=(struct md_fs_result){.fd=-1,.error=wd<0 ? wd : 0,.position=wd<0 ? 0 : wd};
    }
    else if (q->operation == MD_FS_TASK_PATH) task_path(fs,q,out,output);
    else if (q->operation==MD_FS_XATTR || q->operation==MD_FS_FXATTR) md_fs_xattr(fs,q,out,output);
    else md_fs_view_execute(fs,q,out,output);
    /* A no-follow proc inode can be inspected by its own protected task even
     * when the worker cannot traverse its fd directory. The walker first
     * admits the complete path and proc policy; an O_PATH retry captures the
     * exact leaf in the caller without delegating the mount traversal. */
    int proc_link_delegated=(out->error==-EACCES || out->error==-EPERM) && boundary.proc_link_mount
        && (q->operation==MD_FS_READLINK || (q->operation==MD_FS_STAT && (q->flags&AT_SYMLINK_NOFOLLOW))
            || (q->operation==MD_FS_OPEN && (q->flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW)));
    if (proc_link_delegated) out->error=-EREMOTE;
    if (out->error==-EREMOTE && boundary.path) {
        /* Implicit native proc bindings retain namespace capability checks
         * before either the worker or the calling task performs native IO. */
        int error=md_procfs_check(fs,NULL,boundary.path,1);
        if (error) out->error=error;
    }
    if (out->error==-EREMOTE && fs->store->landlock) {
        int error=md_fs_landlock_delegate(fs,q,boundary.path ? boundary.path : "");
        if (error) out->error=error;
    }
    if (out->error==-EREMOTE && boundary.proc_entry)
        md_fs_proc_task(fs,boundary.proc_entry,boundary.path,q,out,output);
    if (out->error==-EXDEV && fs->store->landlock
            && q->operation!=MD_FS_LINK && q->operation!=MD_FS_RENAME) {
        int error=md_fs_landlock_native(fs,q,NULL,q->directory[0]);
        if (error) out->error=error;
    }
    if (out->error==-EXDEV) md_fs_native_descriptor(fs,q,out,output);
    fs->store->boundary=previous_boundary;
    if (out->error==-EREMOTE && !fs->store->landlock)
        md_fs_native_boundary(fs,&boundary,q,out,output);
    if (out->error==-EREMOTE && (boundary.mount_root || boundary.native_directory) && q->resolve) {
        /* The logical walker admitted this native root. Resolve the remaining
         * suffix under that root in one kernel operation, never discard the
         * application's openat2 constraints in a plain-open fallback. Crossing
         * back out of the native provider requires a logical walk, so fails. */
        int root=open(boundary.mount_root ? boundary.path : "/dev",O_PATH|O_DIRECTORY|O_CLOEXEC);
        if (root<0) out->error=-errno;
        else {
            const char *relative=boundary.mount_root ? "." : boundary.path+4;
            while (*relative=='/') ++relative;
            struct open_how how={.flags=q->flags|O_CLOEXEC,.mode=q->mode,
                .resolve=(q->resolve&~RESOLVE_IN_ROOT)|RESOLVE_BENEATH};
            int fd=q->operation!=MD_FS_OPEN ? -1
                : (int)syscall(SYS_openat2,root,*relative ? relative : ".",&how,sizeof(how));
            out->error=q->operation!=MD_FS_OPEN ? -ENOTSUP : fd<0 ? -errno : 0;
            out->fd=fd; close(root);
            if (!out->error) md_view_capture(fs,NULL,NULL,out,output);
        }
    }
    if (out->error==-EREMOTE) {
        if (boundary.path && boundary.capacity) {
            out->error=0; out->host_path=boundary.operand+1; out->size=strlen(boundary.path)+1;
            out->proc_namespace=boundary.proc_namespace;
            if (proc_link_delegated || q->operation==MD_FS_STAT)
                out->info.mount_id=boundary.proc_link_mount;
        }
    }
    if (!out->error && (q->operation==MD_FS_MOUNT || (q->operation==MD_FS_FSCONFIG
            && q->mode==FSCONFIG_CMD_RECONFIGURE))) md_fs_mount_table_changed(fs);
    md_fs_anchor_release(boundary.proc_entry);
    fs->store->creation_mask=previous_mask;
    fs->store->root_id=previous_root;
    fs->native_root=previous_native_root;
    if (context->authenticated) fs->mounts=previous_mounts;
    fs->store->identity = previous;
    fs->store->landlock=previous_landlock;
    fs->store->image_open=previous_execute;
    fs->actor=previous_actor;
}
static void object_execute(struct md_fs_context *context, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_fs_request captured=*q;
    struct md_fs_anchor *origins[2]={NULL,NULL};
    int descriptors[2]={-1,-1};
    int error=context->filesystem ? md_fs_request_validate(q) : -EINVAL;
    for (unsigned i=0;i<2 && !error;++i) if (q->object[i]) {
        struct md_fs_request open={.actor=q->actor,.peer=q->peer,.operation=MD_FS_OPEN_OBJECT,
            .directory={-1,-1},.path={q->object[i],NULL},.flags=O_PATH|O_CLOEXEC};
        struct md_fs_output source={.origin=(context->authenticated ? context->task.mounts
            : context->filesystem->mounts) ? &origins[i] : NULL};
        struct md_fs_result result;
        context_execute(context,&open,&result,&source,NULL);
        descriptors[i]=result.fd;
        error=result.error;
        if (!error && (result.fd<0 || result.open_completion.kind || result.host_path)) error=-EPROTO;
        captured.directory[i]=result.fd; captured.origin[i]=origins[i]; captured.object[i]=NULL;
    }
    if (!error) context_execute(context,&captured,out,output,NULL);
    else {
        if (output && output->origin) *output->origin=NULL;
        *out=(struct md_fs_result){.fd=-1,.error=error};
    }
    for (unsigned i=0;i<2;++i) {
        md_fs_anchor_release(origins[i]);
        if (descriptors[i]>=0) close(descriptors[i]);
    }
}
void md_fs_context_execute(struct md_fs_context *context,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    if (q->capture[0].path || q->capture[1].path) {
        *out=(struct md_fs_result){.fd=-1,.error=-EINVAL}; return;
    }
    struct md_filesystem *fs=context->filesystem;
    struct md_cost *cost=fs && fs->statistics && q->operation<=MD_FS_LAST
        ? &fs->statistics->operation[q->operation] : NULL;
    int64_t begin=md_cost_begin(cost);
    struct md_fs_request mapped;
    const struct md_identity *id=context->has_identity ? &context->task.identity : NULL;
    if (id && id->user_namespace && q->operation==MD_FS_CHOWN) {
        mapped=*q;
        if (q->attributes.uid!=UINT32_MAX)
            mapped.attributes.uid=md_user_namespace_into(id->user_namespace,0,q->attributes.uid);
        if (q->attributes.gid!=UINT32_MAX)
            mapped.attributes.gid=md_user_namespace_into(id->user_namespace,1,q->attributes.gid);
        if ((q->attributes.uid!=UINT32_MAX && mapped.attributes.uid==UINT32_MAX)
                || (q->attributes.gid!=UINT32_MAX && mapped.attributes.gid==UINT32_MAX)) {
            *out=(struct md_fs_result){.fd=-1,.error=-EINVAL};
            md_cost_end(cost,begin); return;
        }
        q=&mapped;
    }
    if (q->object[0] || q->object[1]) object_execute(context,q,out,output);
    else context_execute(context,q,out,output,NULL);
    /* Internal opens and executable admission keep canonical owners. Only
     * the caller-facing metadata result belongs to the current user namespace. */
    if (!out->error && !out->host_path && id && id->user_namespace && !(q->mode&MD_FS_STAT_CANONICAL)
            && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)) {
        out->info.uid=md_identity_visible_id(id,0,out->info.uid);
        out->info.gid=md_identity_visible_id(id,1,out->info.gid);
    }
    md_cost_end(cost,begin);
}
void md_fs_context_resume(struct md_fs_context *context,struct md_fs_continuation *continuation,
        struct md_fs_result *out,const struct md_fs_output *output) {
    const struct md_fs_request *q=&continuation->request;
    context_execute(context,q,out,output,continuation);
    const struct md_identity *id=context->has_identity ? &context->task.identity : NULL;
    if (!out->error && !out->host_path && id && id->user_namespace && !(q->mode&MD_FS_STAT_CANONICAL)
            && (q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT)) {
        out->info.uid=md_identity_visible_id(id,0,out->info.uid);
        out->info.gid=md_identity_visible_id(id,1,out->info.gid);
    }
}
static void inode_execute(struct md_filesystem *fs,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_inode_store *s = fs->selected_store ? fs->selected_store : fs->store;
    struct md_image_catalogue *images = s==fs->store ? fs->images : NULL;
    *out = (struct md_fs_result){.fd = -1};
    void *data = output ? output->data : NULL;
    size_t capacity = output ? output->capacity : 0;
    const char *a = q->path[0] ? q->path[0] : "", *b = q->path[1] ? q->path[1] : "";
    int first = q->directory[0], second = q->directory[1];
    int r = -ENOTSUP, local_plan=0; struct stat st;
    switch (q->operation) {
    case MD_FS_GETACL: case MD_FS_SETACL: case MD_FS_REMOVEACL:
        md_inode_acl(s,q,out,output); return;
    case MD_FS_GETCAP: case MD_FS_SETCAP: case MD_FS_REMOVECAP:
        r = md_inode_capability(s, q, data, capacity);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_ACCESS: case MD_FS_UTIMENS:
        r = md_inode_metadata(s, q); break;
    case MD_FS_XATTR_OBJECT:
        md_inode_xattr_origin(s,q,out,output,md_fs_anchor_native_leaf(q->origin[0],s,first)); return;
    case MD_FS_REOPEN:
        r=md_catalogue_reopen(images,s,first,(int)q->flags,!!(q->mode&MD_FS_REOPEN_MUTABLE),
            &out->open_completion,output && output->origin && output->native_open_plan);
        if (r==-EXDEV && (q->mode&MD_FS_REOPEN_NATIVE) && !(output && output->native_open_plan)) {
            r=md_reopen_file(first,(int)q->flags,&out->open_completion);
            if ((r==-EACCES || r==-EPERM) && !(q->flags&(O_ACCMODE|O_PATH|O_TRUNC|O_DIRECTORY
                    |O_DIRECT|O_ASYNC|O_NOFOLLOW|O_CREAT|O_EXCL))) {
                struct md_control_file file=md_readonly_file_new(first);
                if (file.ops) {
                    int carrier=file.ops->descriptor(file.context,(int)q->flags);
                    out->error=md_fs_control_publish(carrier,file,NULL,s->identity,(int)q->flags,out,output);
                    md_control_file_release(file); return;
                }
            }
        }
        goto opened;
    case MD_FS_WATCH_CREATE: r = md_inode_watch_create(s, (int)q->flags); goto opened;
    case MD_FS_WATCH_ADD:
        r = md_inode_watch_add(s, first, second, q->flags);
        if (r >= 0) { out->position = r; r = 0; }
        break;
    case MD_FS_WATCH_REMOVE: r = md_inode_watch_remove(s, first, (int)q->flags); break;
    case MD_FS_WATCH_CONTAINS:
        out->position = md_inode_watch_contains(s, first); r = 0; break;
    case MD_FS_WATCH_BYTES:
        r = (int)md_inode_watch_bytes(s, first);
        if (r >= 0) { out->position = r; r = 0; }
        break;
    case MD_FS_WATCH_READ:
        r = output && output->reserve_watch
            ? (int)md_inode_watch_reserve(s, first, data, q->capacity < capacity ? q->capacity : capacity)
            : (int)md_inode_watch_read(s, first, data, q->capacity < capacity ? q->capacity : capacity,
                output ? output->deliver : NULL, output ? output->context : NULL);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_OPEN_IMAGE: {
        if (!data || capacity < sizeof(struct md_image_identity)) { r = -ERANGE; break; }
        struct md_image_identity *identity = data;
        r = md_catalogue_open_image(images, s, first, a, (int)q->flags, identity);
        if (r >= 0) out->size = offsetof(struct md_image_identity, path) + strlen(identity->path) + 1;
        goto opened;
    }
    case MD_FS_OBJECT_ID:
        if (!data || capacity < 33) { r = -ERANGE; break; }
        r = md_catalogue_object_id(images, s, first, data);
        if (!r) out->size = 33;
        break;
    case MD_FS_OPEN_OBJECT: r = md_catalogue_open_object(images, s, a, (int)q->flags); goto opened;
    case MD_FS_SOCKET_BIND: {
        struct md_ipc_credentials *ipc=NULL;
        r=fs->ipc_store ? md_fs_ipc_authority(fs,&ipc) : 0;
        if (!r) r=md_inode_socket_bind(s,first,a,*b ? b : a,q->mode,second);
        if (!r && ipc) r=md_ipc_socket_path_publish(ipc,second,*b ? b : a);
        break;
    }
    case MD_FS_SOCKET_ADDRESS: case MD_FS_SOCKET_NAME:
        if (q->operation==MD_FS_SOCKET_ADDRESS) r=md_inode_socket_address(s,first,q->path[0],data,capacity);
        else {
            struct md_ipc_credentials *ipc=NULL;
            r=fs->ipc_store ? md_fs_ipc_authority(fs,&ipc) : 0;
            if (!r) r=ipc ? md_ipc_socket_path(ipc,a,data,capacity) : -ENOENT;
            if (r==-ENOENT) r=md_inode_socket_name(s,a,data,capacity);
        }
        if (!r) out->size = strlen(data) + 1;
        break;
    case MD_FS_CREATE: r = md_inode_create(s, first, a, q->mode); goto opened;
    case MD_FS_OPEN:
        /* Reuse the provider's verified pin for both entry and ordinary opens.
         * Do not speculate on creation: its publication belongs to the actual
         * opener. Special endpoints decline before effects and retain their
         * deferred FIFO/device completion path. */
        local_plan=output && output->origin && !output->native_open_plan
            && !(q->flags&O_CREAT) && md_open_native_plan(q->flags,0);
        r=md_catalogue_open(images,s,first,a,(int)q->flags,q->mode,q->resolve,
            &out->open_completion,local_plan || (output && output->origin && output->native_open_plan),
            output && output->origin && output->native_open_plan ? output->birth : NULL);
        if (local_plan && r==-EXDEV) {
            local_plan=0;
            r=md_catalogue_open(images,s,first,a,(int)q->flags,q->mode,q->resolve,
                &out->open_completion,0,NULL);
        }
        goto opened;
    case MD_FS_MKDIR: r = md_inode_mkdir(s, first, a, q->mode); break;
    case MD_FS_MKNOD:
        r=md_inode_mknod(s,first,a,q->mode,(dev_t)q->offset);
        break;
    case MD_FS_SYMLINK: r = md_inode_symlink(s, b, first, a); break;
    case MD_FS_LINK: r = md_inode_link(s, first, a, second, b, (int)q->flags); break;
    case MD_FS_UNLINK: r = md_inode_unlink(s, first, a, (int)q->flags); break;
    case MD_FS_RENAME: r = md_inode_rename(s, first, a, second, b, q->flags); break;
    case MD_FS_STAT: case MD_FS_FSTAT: {
        if (q->mode & ~(MD_FS_STAT_MOUNT|MD_FS_STAT_CANONICAL)) { r=-EINVAL; break; }
        char path[PATH_MAX];
        struct mdi_stat_location location={0}, *previous=s->stat_location;
        if (q->mode & MD_FS_STAT_MOUNT) {
            /* A selected view owns mount identity. Reverse-walk names only for
             * the base-provider fallback which distinguishes /dev/shm. */
            if (!fs->inode_mount) { path[0]=0; location.path=path; }
            s->stat_location=&location;
        }
        r = q->operation == MD_FS_STAT ? md_catalogue_stat(images, s, first, a, (int)q->flags, &st)
            : md_catalogue_fstat(images, s, first, &st,md_fs_anchor_native_leaf(q->origin[0],s,first));
        s->stat_location=previous;
        if (!r) md_fs_stat_info(&st, &out->info);
        if (!r && (q->mode & MD_FS_STAT_MOUNT)) {
            const struct md_view_mount *mount=fs->inode_mount;
            uint64_t id=0;
            int root=location.root;
            if (mount) {
                id=mount->id;
                if (mount->source>=0)
                    root=location.device==mount->native.st_dev && location.inode==mount->native.st_ino;
            } else if (location.path && *location.path) {
                int shared=!strncmp(location.path,"/dev/shm",8)
                    && (!location.path[8] || location.path[8]=='/');
                r=md_fs_base_mount(fs,shared,&id);
                if (shared) root=!strcmp(location.path,"/dev/shm");
            }
            if (!r) md_fs_info_mount(&out->info,id,root);
        }
        break;
    }
    case MD_FS_SYNC: r=md_inode_sync(s,first,(enum md_inode_sync_mode)q->mode); break;
    case MD_FS_IOCTL: {
        struct stat st;
        r=md_inode_fstat(s,first,&st);
        if (!r) r=-ENOTTY;
        break;
    }
    case MD_FS_STATFS: {
        int fd=*a ? md_inode_open(s,first,a,O_PATH|O_CLOEXEC,0) : first;
        if (fd<0) out->error=fd;
        else {
            const struct md_view_mount *mount=fs->inode_mount ? fs->inode_mount
                : fs->mounts ? &fs->mounts->base : NULL;
            unsigned policy=mount ? md_mount_policy_flags(&mount->policy) : 0;
            int readonly=md_inode_store_readonly(s);
            if (readonly<0) out->error=readonly;
            else {
                if (readonly) policy|=MD_MOUNT_READONLY;
                md_fs_statfs_result(fd,policy,s->lifetime,out,output);
            }
            if (*a) close(fd);
        }
        return;
    }
    case MD_FS_PATH:
        r = md_catalogue_path(images, s, first, data, capacity);
        if (!r) out->size = strlen(data) + 1;
        break;
    case MD_FS_REALPATH:
        r = md_inode_realpath(s, first, a, data, capacity);
        if (!r) out->size = strlen(data) + 1;
        break;
    case MD_FS_TEMPORARY: r = md_inode_temporary(s); goto opened;
    case MD_FS_MOUNT_TABLE: md_fs_mount_table_open(fs,q,out,output); return;
    case MD_FS_NATIVE_MOUNT:
        r=md_fs_native_mount(fs,(uint64_t)q->offset,&out->info.mount_id); break;
    case MD_FS_READLINK:
        r = (int)md_inode_readlink(s, first, a, data, capacity);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_GETDENTS:
        r = output && output->directory_position
            ? (int)md_inode_getdents_prepare(s,first,data,q->capacity < capacity ? q->capacity : capacity,output->directory_position)
            : (int)md_inode_getdents_deliver(s, first, data, q->capacity < capacity ? q->capacity : capacity,
            output ? output->deliver : NULL, output ? output->context : NULL);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_SEEKDIR: {
        int64_t position = md_inode_seekdir(s, first, q->offset, (int)q->flags);
        if (position < 0) r = (int)position;
        else { out->position = position; r = 0; }
        break;
    }
    }
    out->error = r;
    return;
opened:
    if (r < 0) out->error = r;
    else {
        out->fd = r;
        md_view_capture(fs,fs->mounts,fs->inode_mount,out,output);
        if (!out->error && q->operation==MD_FS_REOPEN && q->origin[0] && output && output->origin
                && *output->origin) {
            (*output->origin)->dentry=q->origin[0]->dentry;
            (*output->origin)->native_leaf=q->origin[0]->native_leaf;
        }
        if (local_plan && !out->error) {
            int fd=md_open_pinned(md_fs_anchor_fd(*output->origin),(int)q->flags);
            out->open_completion=(struct md_open_completion){0};
            out->fd=fd<0 ? -1 : fd;
            if (fd<0) {
                out->error=fd;
                md_fs_anchor_release(*output->origin); *output->origin=NULL;
            }
        }
        if (!out->error && out->open_completion.kind==MD_OPEN_DEVICE) {
            struct md_open_completion device=out->open_completion;
            struct md_fs_anchor *origin=output && output->origin ? *output->origin : NULL;
            struct md_control_file file={0};
            int fd;
            if (S_ISCHR(device.mode) && device.device==makedev(1,7)) {
                struct stat st;
                fd=md_inode_fstat(s,out->fd,&st);
                if (!fd) {
                    file=md_full_device_new(&st,origin);
                    fd=file.ops ? file.ops->descriptor(file.context,(int)q->flags) : -errno;
                }
                out->open_completion=(struct md_open_completion){0};
            } else fd=md_open_device(device.mode,device.device,(int)q->flags,&out->open_completion);
            if (fd>=0 && origin) {
                struct stat st;
                if (fstat(fd,&st)) { int error=-errno; close(fd); fd=error; }
                else origin->endpoint=md_fd_identity(&st);
            }
            close(out->fd); out->fd=fd<0 ? -1 : fd;
            if (fd>=0 && file.ops) {
                /* Placement is already captured; publish only IO authority. */
                struct md_fs_output controlled=output ? *output : (struct md_fs_output){0};
                controlled.origin=NULL;
                int error=md_fs_control_publish(fd,file,origin,s->identity,(int)q->flags,out,&controlled);
                if (error) { fd=error; out->fd=-1; }
            }
            md_control_file_release(file);
            if (fd<0) {
                out->error=fd; out->open_completion=(struct md_open_completion){0};
                if (output && output->origin) {
                    md_fs_anchor_release(*output->origin); *output->origin=NULL;
                }
            }
        }
    }
}
void md_fs_inode_execute(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_inode_store *store=fs->selected_store ? fs->selected_store : fs->store;
    struct mdi_directory_pins pins={0};
    const struct mdi_directory_pins *previous_pins=store->directory_pins;
    for (unsigned i=0;i<2;++i) {
        const struct md_fs_anchor *origin=q->origin[i];
        if (!origin || origin->kind!=S_IFDIR || origin->child || q->directory[i]!=origin->fd
                || !md_inode_lifetime_same(origin->lifetime,md_inode_store_lifetime(store))) continue;
        unsigned slot=pins.count++;
        pins.entries[slot].fd=origin->fd;
        pins.entries[slot].device=origin->device;
        pins.entries[slot].inode=origin->inode;
    }
    store->directory_pins=pins.count ? &pins : NULL;
    int readonly=store->mount_readonly;
    int nodev=store->nodev;
    if (fs->mounts) store->nodev=!!(md_mount_policy_flags(fs->inode_mount
        ? &fs->inode_mount->policy : &fs->mounts->base.policy)&MD_MOUNT_NODEV);
    if (fs->mounts && (fs->inode_mount ? md_view_readonly(fs->inode_mount)
            : (md_mount_policy_flags(&fs->mounts->base.policy)&MD_MOUNT_READONLY))) store->mount_readonly=1;
    inode_execute(fs,q,out,output);
    store->directory_pins=previous_pins;
    store->mount_readonly=readonly;
    store->nodev=nodev;
}
