#define _GNU_SOURCE
#include "adapter_buffer.h"
#include "control_file.h"
#include "control_ioctl.h"
#include "descriptor_calls.h"
#include "fd_install.h"
#include "fd_native_pin.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "file_metadata.h"
#include "file_permissions.h"
#include "filesystem_ioctl.h"
#include "fs_anchor.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "fs_mount_internal.h"
#include "fs_operation.h"
#include "fs_rpc.h"
#include "fs_worker.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "identity_call.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "network_namespace.h"
#include "network_proc.h"
#include "pid_namespace.h"
#include "private_memory.h"
#include "proc_control.h"
#include "proc_file.h"
#include "proc_namespace.h"
#include "proc_paths.h"
#include "proc_static.h"
#include "proc_sysctl.h"
#include "process_access.h"
#include "process_image_view.h"
#include "process_limits.h"
#include "process_oom.h"
#include "process_owner.h"
#include "program_files.h"
#include "resource_order.h"
#include "signal_actions.h"
#include "signal_pending.h"
#include "signal_view.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_proc.h"
#include "supervisor_task.h"
#include "task_registry.h"
#include "user_namespace.h"
#include "user_sysctl.h"
#include "watch_calls.h"
#include <linux/nsfs.h>
#include <sys/ioctl.h>
static int proc_node_stat(struct proc_owner target, enum md_proc_file_kind kind,
        struct md_proc_file_node *node, struct stat *value);
static void proc_metadata_value(struct md_proc_metadata_result *out,const struct stat *st,
        const struct md_proc_file *file);
static struct md_control_file proc_control_view(struct thread *t,enum md_proc_kind kind,
        pid_t process,const char *interface);
static int proc_node_metadata(struct thread *t, const struct md_proc_metadata_request *q, struct md_proc_metadata_result *out);
static int proc_file_publish(struct thread *t,struct md_proc_file *file);
static int control_mount_path(const struct md_proc_file *file);
static int process_image_memory(const struct thread *caller,const struct thread *target);
static void proc_file_request(struct thread *t, const struct md_proc_open_request *q);
static int proc_write_capture(struct thread *t,size_t *total);

void proc_query_begin(struct thread *t, const struct seccomp_notif *q, int target) {
    struct metadata_operation *op = &t->metadata;
    op->proc_stat=target>=0; op->task_open=target<0;
    op->proc_directory=target>=0 ? target : (int)q->data.args[0];
    op->request = *q; op->proc_attempted = 1; op->error = 0;
    t->phase = PROC_CANCEL; tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-proc-query");
}

void proc_query_start(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->request.id) == -1 && errno == ENOENT);
    struct user_pt_regs regs = op->saved;
    regs.pc = op->task_open ? abi.proc_open : EXPORT; regs.regs[30] = abi.exported_entry;
    regs.regs[0] = op->proc_directory;
    if (op->task_open) regs.regs[1] = op->request.data.args[2];
    if (!set_registers(t->pid, &regs)) return;
    t->phase = PROC_OPENING; resume(t->pid, 0);
}

void proc_deliver(struct thread *t) {
    retry_clear(t,RETRY_PROC_DELIVERY);
    if (!t->proc.delivery.files.table) return;
    int installed=md_fd_install_progress(&t->proc.delivery);
    if (installed==MD_FD_INSTALL_WAIT) { retry_wait(t,RETRY_PROC_DELIVERY); return; }
    CHECK(!t->proc.delivery.retire_error);
    close(t->proc.image_fd); t->proc.image_fd=-1;
    if (installed==MD_FD_INSTALL_CANCELLED) return;
    if (installed<0) {
        struct seccomp_notif_resp reply={.id=t->proc.delivery.notification,.error=installed};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
}

void proc_snapshot_complete(struct thread *t,struct md_fs_buffer_request *request,int error) {
    struct seccomp_notif *q=&t->proc.snapshot_notification;
    struct md_fs_buffer_open opened={.fd=-1};
    t->proc.snapshot=NULL;
    if (!error && md_fs_buffer_open_take(request,&opened)!=1) error=-EIO;
    if (!error) {
        struct md_fd_object *pin=md_fd_native_pin_new(opened.fd,NULL);
        error=pin ? md_fd_install_begin(&t->proc.delivery,t->resources.files,listener,q->id,
            opened.fd,pin,(unsigned)q->data.args[3]&O_CLOEXEC,fd_wakeup) : -errno;
        md_fd_object_release(pin);
        if (!error) {
            t->proc.image_fd=opened.fd; opened.fd=-1;
            proc_deliver(t);
        }
    }
    md_fs_buffer_open_release(&opened);
    if (error) {
        struct seccomp_notif_resp reply={.id=q->id,.error=error};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
}

struct md_proc_file *proc_file_capture(struct thread *t, int fd) {
    struct md_fd_object *object=md_fd_origins_object(t->resources.files,fd,NULL);
    struct md_proc_file *file=md_proc_file_from_object(object);
    if (!file) md_fd_object_release(object);
    return file;
}

struct proc_owner proc_owner(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (t && t->born) return (struct proc_owner){pid,&t->identity,&t->resources,
        md_pid_identity_namespace(t->resources.pid),t->resources.cgroup,t->resources.process};
    struct md_task_retired_view retired=md_task_registry_retired(tasks,pid);
    return (struct proc_owner){pid,retired.identity,NULL,md_pid_identity_namespace(retired.pid),retired.cgroup,retired.process};
}

static int proc_node_stat(struct proc_owner target, enum md_proc_file_kind kind,
        struct md_proc_file_node *node, struct stat *value) {
    if (md_proc_file_namespace(kind)) {
        if (!target.resources || (kind==MD_PROC_FILE_NS_PID_CHILDREN
                && !md_pid_namespace_initialized(target.resources->pid_children)))
            return -ENOENT;
        struct md_namespace_reference ref=md_namespace_reference_capture(
            (enum md_namespace_kind)(kind-MD_PROC_FILE_NS_USER),target.resources,target.identity->user_namespace);
        md_namespace_reference_stat(&ref,value); md_namespace_reference_release(&ref);
        *node=(struct md_proc_file_node){value->st_dev,value->st_ino,target.pid}; return 0;
    }
    if (md_proc_file_sysctl(kind)) {
        /* These virtual sysctls exist independently of Android's sysctl DAC
         * and SELinux labels. The accessible proc parent supplies the device. */
        int error=md_proc_sysctl_stat(kind,value);
        if (error) return error;
        *node=(struct md_proc_file_node){value->st_dev,value->st_ino,0};
        return 0;
    }
    return md_proc_file_task_stat(target.pid,target.identity,
        target.resources ? target.resources->access : NULL,kind,node,value);
}

static void proc_metadata_value(struct md_proc_metadata_result *out,const struct stat *st,
        const struct md_proc_file *file) {
    md_fs_stat_info(st,&out->value);
    md_fs_anchor_mount_info(md_proc_file_origin(file),&out->value);
}

static struct md_control_file proc_control_view(struct thread *t,enum md_proc_kind kind,
        pid_t process,const char *interface) {
    if (md_proc_network_table(kind)) {
        struct thread *target=find_thread(process);
        if (!target) { errno=EXDEV; return (struct md_control_file){0}; }
        if (!target->born) { errno=ENOENT; return (struct md_control_file){0}; }
        return md_network_proc_new(target->resources.network,(enum md_network_table)(kind-MD_PROC_NET_TCP));
    }
    if (kind==MD_PROC_MAX_USER_NAMESPACES) return md_user_sysctl_new(t->identity.user_namespace,NULL);
    if (md_proc_static(kind)) return md_proc_static_new(kind,t->identity.user_namespace,NULL,t->resources.network);
    return md_network_sysctl_new(t->resources.network,
        (enum md_network_parameter)(kind-MD_PROC_NET_UNPRIVILEGED_PORT),interface,NULL);
}

static int proc_node_metadata(struct thread *t, const struct md_proc_metadata_request *q, struct md_proc_metadata_result *out) {
    *out=(struct md_proc_metadata_result){0};
    struct stat st={0}, *value=&st;
    if (md_proc_network_control(q->kind) || q->kind==MD_PROC_MAX_USER_NAMESPACES || md_proc_static(q->kind)) {
        if (!memchr(q->interface,0,sizeof(q->interface))) return -EINVAL;
        struct md_control_file view=proc_control_view(t,q->kind,q->process,q->interface);
        if (!view.ops) return -errno;
        int error=view.ops->stat(view.context,value);
        struct md_identity actor=md_identity_access(&t->identity,q->effective);
        if (!error) error=view.ops->access(view.context,&actor,q->access);
        if (!error) {
            value->st_uid=md_identity_visible_id(&t->identity,0,value->st_uid);
            value->st_gid=md_identity_visible_id(&t->identity,1,value->st_gid);
            out->kind=MD_PROC_FILE_CONTROL; out->filesystem=view.ops->filesystem;
            proc_metadata_value(out,value,NULL);
        }
        md_control_file_release(view); return error;
    }
    struct proc_owner target=proc_owner(q->process);
    struct md_proc_file *file=NULL;
    struct md_proc_file_node node={0};
    enum md_proc_file_kind kind;
    if (q->kind==MD_PROC_FD || q->kind==MD_PROC_CWD || q->kind==MD_PROC_EXE) {
        struct thread *owner=find_thread(q->process);
        /* Unmanaged processes still have real proc entries. The caller's
         * native capture enforces Android access, just like status/stat. */
        if (!owner) return -EXDEV;
        if (!owner->born) return -ENOENT;
        if (owner!=t && !proc_allowed(t->pid,owner->pid)) return -EACCES;
        /* Authorization precedes native capture. Typed descriptors retain their
         * provider metadata; their carrier must never become the stat result. */
        if (q->kind!=MD_PROC_FD || q->nofollow) return -EXDEV;
        file=proc_file_capture(owner,q->descriptor);
        if (!file) return -EXDEV;
        node=md_proc_file_get_node(file);
        kind=md_proc_file_kind(file);
        target=proc_owner(node.process);
    } else if (q->kind) {
        if (!md_proc_control_kind(q->kind)) return -EINVAL;
        if (q->kind==MD_PROC_NS_UNSUPPORTED) return -ENOENT;
        if (!target.identity) return md_proc_native_task(q->kind) ? -EXDEV : -ENOENT;
        kind=(enum md_proc_file_kind)(q->kind-MD_PROC_UID_MAP);
    } else {
        if (q->process!=t->pid) return -EPERM;
        file=proc_file_capture(t,q->descriptor);
        if (!file) return -EBADF;
        node=md_proc_file_get_node(file);
        kind=md_proc_file_kind(file);
        target=proc_owner(node.process);
    }
    int error=0;
    out->filesystem=md_proc_file_filesystem(file);
    if (kind==MD_PROC_FILE_CONTROL && file) {
        if (md_proc_file_filesystem_paths(file)) { md_proc_file_release(file); return -EXDEV; }
        out->kind=(unsigned)kind;
        error=md_proc_file_control_stat(file,value);
        if (!error) {
            struct md_identity actor=md_identity_access(&t->identity,q->effective);
            error=md_proc_file_control_access(file,&actor,q->access);
        }
        if (!error) {
            value->st_uid=md_identity_visible_id(&t->identity,0,value->st_uid);
            value->st_gid=md_identity_visible_id(&t->identity,1,value->st_gid);
        }
        if (!error) proc_metadata_value(out,value,file);
        md_proc_file_release(file); return error;
    }
    struct md_proc_file_node current={0};
    int sysctl=md_proc_file_sysctl(kind);
    int namespace=md_proc_file_namespace(kind);
    out->kind=(unsigned)kind;
    if (namespace && file) md_namespace_reference_stat(md_proc_file_namespace_reference(file),value);
    else if (namespace && !q->nofollow && target.pid!=t->pid && !proc_allowed(t->pid,target.pid)) error=-EACCES;
    else if (!namespace && !sysctl && file) error=md_proc_file_retained_stat(file,tasks,0,value);
    else if (sysctl || target.identity) error=proc_node_stat(target,kind,&current,value);
    if (!error && namespace) {
        if (q->nofollow || (file && (md_proc_file_flags(file)&(O_NOFOLLOW|O_PATH))==(O_NOFOLLOW|O_PATH))) {
            value->st_mode=S_IFLNK|0777;
            if (target.identity) md_process_file_owner(target.identity,target.resources ? target.resources->access : NULL,
                value->st_mode,getuid(),&value->st_uid,&value->st_gid);
        }
        if (q->access&(W_OK|X_OK)) error=-EACCES;
    } else if (!error) error=(md_proc_file_process(kind) || md_proc_file_cgroup_kind(kind)) && (q->access&(W_OK|X_OK)) ? -EACCES
        : sysctl ? md_proc_sysctl_access(kind,&t->identity,q->access,q->effective)
        : md_proc_file_task_access(&t->identity,value,q->access,q->effective);
    if (!error) {
        value->st_uid=md_identity_visible_id(&t->identity,0,value->st_uid);
        value->st_gid=md_identity_visible_id(&t->identity,1,value->st_gid);
        proc_metadata_value(out,value,file);
    }
    md_proc_file_release(file); return error;
}

/* Proc providers share ordinary file-result publication and cancellation.
 * The input descriptor remains independent of the newly opened result. */
static int proc_file_publish(struct thread *t,struct md_proc_file *file) {
    if (t->proc.image_fd>=0 || t->fs.open.fd>=0) return -EBUSY;
    int fd=md_proc_file_descriptor(file);
    if (fd<0) { SUPERVISOR_TRACE("PROBE proc-carrier pid=%d error=%d\n",t->pid,fd); return fd; }
    struct md_fs_buffer_open opened={.fd=fd,.flags=(unsigned)md_proc_file_flags(file),
        .object=md_fd_object_retain(md_proc_file_object(file))};
    if (!buffer_service) {
        /* Direct-root launches have no filesystem worker. Use the same
         * retained result and rights transfer as their network providers. */
        struct md_buffer_span span;
        int error=md_buffer_span(&t->fs.buffer,&span);
        if (!error && span.size<sizeof(struct md_fs_buffer_frame)) error=-EINVAL;
        if (error) { md_fs_buffer_open_release(&opened); return error; }
        struct md_fs_buffer_frame *frame=span.data;
        frame->reply=(struct md_fs_response){.delivery=MD_FS_REPLIED,.result={.fd=-1,
            .selectors=md_fd_object_selectors(opened.object)}};
        frame->transfer=MD_FS_BUFFER_RIGHTS;
        t->fs.open=opened;
        buffer_reply(t,0); return 0;
    }
    struct md_fs_result result={.fd=-1};
    int error=md_fs_buffer_adopt(buffer_service,t->pid,t->resources.fs,MD_FS_OPEN,opened.flags,
        &t->fs.buffer,&result,&opened,&t->fs.call);
    md_fs_buffer_open_release(&opened);
    /* EVENT_WAIT: the common file result owner prepares an exact transfer;
     * task cancellation releases it without replaying the open. */
    if (!error) adapter_progress();
    return error;
}

static int control_mount_path(const struct md_proc_file *file) {
    struct md_fs_anchor *origin=md_proc_file_origin(file);
    /* Mount operations select the retained dentry of an O_PATH device, never
     * its data carrier. Namespace handles retain their typed bind semantics. */
    return md_proc_file_filesystem_paths(file) || ((md_proc_file_flags(file)&O_PATH)
        && !md_proc_file_namespace(md_proc_file_kind(file)) && origin && !md_fs_anchor_virtual(origin));
}

int control_file_query(struct md_fd_object *object,unsigned operation,struct md_control_file *out) {
    struct md_proc_file *file=md_proc_file_from_object(object);
    if (!file) return 0;
    if (operation==MD_FS_FSTAT && md_proc_file_filesystem_paths(file)) {
        /* IO may be synthetic while the retained inode owns live metadata. */
        *out=(struct md_control_file){0}; return 0;
    }
    *out=md_proc_file_control(file);
    if (operation==MD_FS_MOUNT || operation==MD_FS_OPEN_TREE) {
        if (control_mount_path(file)) { *out=(struct md_control_file){0}; return 0; }
        if (!out->ops || !out->ops->descriptor) return -EOPNOTSUPP;
    } else if (operation!=MD_FS_FDINFO && operation!=MD_FS_FSTAT && (md_proc_file_flags(file)&O_PATH)) return -EBADF;
    return out->ops ? 1 : -EINVAL;
}

int control_file_capture(pid_t actor,const char *path,struct md_fd_object **out) {
    struct thread *t=find_thread(actor);
    if (!t) return -ESRCH;
    struct md_proc_path selected=md_proc_path_for(path,t->pid,t->tgid);
    enum md_proc_kind kind=selected.kind==MD_PROC_FOREIGN ? selected.foreign_kind : selected.kind;
    if (kind!=MD_PROC_FD && !md_proc_control_kind(kind)) return 0;
    if (*selected.tail && kind!=MD_PROC_FD) return -ENOTDIR;
    struct thread *target=find_thread(selected.process);
    if (!target || !target->born) return kind==MD_PROC_FD ? 0 : -ENOENT;
    struct md_proc_file *file=NULL;
    if (kind==MD_PROC_FD) {
        file=proc_file_capture(target,selected.descriptor);
        if (!file) return 0;
        if (target!=t && !proc_allowed(t->pid,target->pid)) { md_proc_file_release(file); return -EACCES; }
        if (control_mount_path(file)) { md_proc_file_release(file); return 0; }
        if (*selected.tail) { md_proc_file_release(file); return -ENOTDIR; }
    } else {
        if (target!=t && !proc_allowed(t->pid,target->pid)) return -EACCES;
        enum md_proc_file_kind type=(enum md_proc_file_kind)(kind-MD_PROC_UID_MAP);
        if (kind==MD_PROC_NS_UNSUPPORTED) return -ENOENT;
        if (!md_proc_file_namespace(type) && !md_proc_file_sysctl(type)) return -EOPNOTSUPP;
        struct md_proc_file_node node={0}; struct stat st;
        int error=proc_node_stat(proc_owner(target->pid),type,&node,&st);
        if (error) return error;
        if (md_proc_file_sysctl(type)) {
            struct md_control_file control=md_proc_sysctl_new(type,NULL,t->resources.uts);
            if (!control.ops) return -errno;
            file=md_proc_file_open_control(control.ops,control.context,
                &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},O_PATH);
            md_control_file_release(control);
        } else {
        struct md_namespace_reference ref=md_namespace_reference_capture(
            (enum md_namespace_kind)(type-MD_PROC_FILE_NS_USER),&target->resources,target->identity.user_namespace);
        file=md_proc_file_open_namespace(&ref,&t->identity,O_PATH);
        md_namespace_reference_release(&ref);
        }
        if (!file) return -errno;
        md_proc_file_node(file,node);
    }
    *out=md_proc_file_object(file); return 1;
}

static int process_image_memory(const struct thread *caller,const struct thread *target) {
    if (caller->tgid!=target->tgid) return -1;
    const struct md_private_memory_access *access=md_memory_private_access(target->memory);
    return access ? access->memory : -1;
}

int executable_capture(pid_t actor,struct md_program *image) {
    struct thread *t=find_thread(actor);
    if (!t) return -ESRCH;
    struct md_proc_path selected=md_proc_path_for(image->identity.path,t->pid,t->tgid);
    SUPERVISOR_TRACE("PROBE exec-proc capture actor=%d path=%s target=%d\n",actor,image->identity.path,selected.process);
    enum md_proc_kind kind=selected.kind==MD_PROC_FOREIGN ? selected.foreign_kind : selected.kind;
    if (kind!=MD_PROC_EXE || *selected.tail || image->fd>=0) return -EINVAL;
    struct thread *target=find_thread(selected.process);
    if (!target || !target->born) return -ENOENT;
    if (target!=t && !proc_allowed(t->pid,target->pid)) return -EACCES;
    int error=md_process_image_identity(target->pid,target->image.process_image,process_image_memory(t,target),&image->identity);
    if (error) return error;
    if (target->image.executable_origin) {
        /* A proc executable retains the selected mount, including a detached
         * readonly bind. The loader's carrier does not own that authority. */
        int fd=fcntl(md_fs_anchor_fd(target->image.executable_origin),F_DUPFD_CLOEXEC,0);
        if (fd<0) return -errno;
        image->fd=fd;
        image->origin=md_fs_anchor_retain(target->image.executable_origin);
    } else if (target->image.executable) {
        struct md_control_file view=md_process_executable_view(target->image.executable);
        int fd=view.ops->descriptor(view.context,O_RDONLY|O_CLOEXEC);
        if (fd<0) return fd;
        image->fd=fd;
    } else if (!image->identity.object[0]) return -ENOENT;
    return 0;
}

int proc_path_capture(pid_t actor,pid_t process,unsigned kind,struct md_task_snapshot *snapshot) {
    if (kind!=MD_PROC_ROOT && kind!=MD_PROC_CWD) return -EINVAL;
    struct thread *t=find_thread(actor), *target=find_thread(process);
    if (!t) return -ENOENT;
    if (!target) return kind==MD_PROC_CWD ? -EXDEV : -ENOENT;
    if (!target->born) return -ENOENT;
    if (target!=t && !proc_allowed(actor,process)) return -EACCES;
    char path[64],link[PATH_MAX];
    snprintf(path,sizeof(path),"/proc/%d/%s",process,kind==MD_PROC_ROOT ? "root" : "cwd");
    /* Same-process proc access remains valid with dumpable=0. The supervisor
     * is an external observer and must not impose its denial on that caller. */
    if (t->tgid!=target->tgid && readlink(path,link,sizeof(link))<0) return -errno;
    if (md_domain_restricted(target->resources.fs)) return -EACCES;
    return md_task_registry_read(tasks,process,0,snapshot);
}

static void proc_file_request(struct thread *t, const struct md_proc_open_request *q) {
    struct { long val; int error; } reply={0};
    unsigned operation=(unsigned)q->operation;
    if (t->phase!=DISPATCHING
            || (q->view && operation!=MD_PROC_FILE_OPEN)) reply.error=-EPERM;
    else if (operation==MD_PROC_FILE_PIDFD_NAMESPACE) {
        if (!t->proc.file || t->execution.request.nr!=SYS_ioctl
                || (int)t->execution.request.args[0]!=q->process || t->execution.request.args[1]!=q->selector
                || !md_pidfd_namespace_command((unsigned)q->selector) || q->flags!=O_CLOEXEC) reply.error=-EPERM;
        else reply.error=proc_file_publish(t,t->proc.file);
        if (!reply.error) return;
    } else if (operation==MD_PROC_FILE_NAMESPACE_QUERY) {
        const struct md_namespace_reference *ref=md_proc_file_namespace_reference(t->proc.file);
        unsigned command=(unsigned)q->selector;
        if (!ref || t->execution.request.nr!=SYS_ioctl || (int)t->execution.request.args[0]!=q->process
                || t->execution.request.args[1]!=command
                || q->flags!=((command==NS_GET_PARENT || command==NS_GET_USERNS) ? O_CLOEXEC : 0U)) reply.error=-EPERM;
        else if (command==NS_GET_NSTYPE) reply.val=md_namespace_reference_type(ref);
        else if (command==NS_GET_OWNER_UID) {
            if (ref->kind!=MD_NAMESPACE_USER) reply.error=-EINVAL;
            else reply.val=md_identity_visible_id(&t->identity,0,md_user_namespace_creator(ref->value.user));
        } else if (command==NS_GET_PARENT || command==NS_GET_USERNS) {
            struct md_namespace_reference related={0};
            reply.error=md_namespace_reference_related(ref,t->identity.user_namespace,
                md_pid_identity_namespace(t->resources.pid),command==NS_GET_PARENT,&related);
            struct md_proc_file *file=NULL;
            if (!reply.error) {
                file=md_proc_file_open_namespace(&related,&t->identity,O_RDONLY|O_CLOEXEC);
                if (!file) reply.error=-errno;
                md_namespace_reference_release(&related);
            }
            if (!reply.error) reply.error=proc_file_publish(t,file);
            md_proc_file_release(file);
            if (!reply.error) return;
        }
        else reply.error=-ENOTTY;
    } else if (operation==MD_PROC_FILE_OPEN && (md_proc_network_control(q->selector)
            || q->selector==MD_PROC_MAX_USER_NAMESPACES || md_proc_static(q->selector))) {
        int flags=(int)q->flags;
        char interface[16]={0};
        if (md_proc_network_interface(q->selector)) {
            memcpy(interface,q->interface,sizeof(interface));
            if (!memchr(interface,0,sizeof(interface))) reply.error=-EINVAL;
        }
        struct md_control_file view={0};
        if (!reply.error) view=proc_control_view(t,(enum md_proc_kind)q->selector,
            (pid_t)q->process,interface);
        struct md_proc_file *file=NULL;
        if (!view.ops) { if (!reply.error) reply.error=-errno; }
        else {
            file=md_proc_file_open_control(view.ops,view.context,
                &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},flags);
            if (!file) reply.error=-errno;
            else reply.error=proc_file_publish(t,file);
        }
        md_control_file_release(view); md_proc_file_release(file);
        if (!reply.error) return;
    } else if (operation==MD_PROC_FILE_OPEN && q->selector==MD_PROC_EXE) {
        struct thread *target=find_thread((pid_t)q->process);
        int flags=(int)q->flags;
        struct md_proc_file *file=NULL;
        if (!target || (target!=t && !proc_allowed(t->pid,target->pid))) reply.error=-EACCES;
        else if (t->proc.image_fd>=0 || q->view) reply.error=-EINVAL;
        else if (target->image.executable_origin || !target->image.executable) reply.error=-EXDEV;
        else {
            struct md_control_file view=md_process_executable_view(target->image.executable);
            file=md_proc_file_open_control(view.ops,view.context,
                &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},flags);
            if (!file) reply.error=-errno;
            else {
                reply.error=proc_file_publish(t,file);
                if (!reply.error) { md_proc_file_release(file); return; }
            }
        }
        md_proc_file_release(file);
    } else if (operation==MD_PROC_FILE_OPEN || operation==MD_PROC_FILE_REOPEN) {
        struct thread *target=find_thread((pid_t)q->process);
        struct proc_owner selected=proc_owner((pid_t)q->process);
        enum md_proc_kind kind=(enum md_proc_kind)q->selector;
        int flags=(int)q->flags;
        struct md_proc_file *source=operation==MD_PROC_FILE_REOPEN && target
            ? proc_file_capture(target,(int)q->selector) : NULL;
        if (source) kind=(enum md_proc_kind)(MD_PROC_UID_MAP+md_proc_file_kind(source));
        if (!selected.identity) reply.error=operation==MD_PROC_FILE_OPEN && md_proc_native_task(kind) ? -EXDEV : -ENOENT;
        else if (operation==MD_PROC_FILE_REOPEN && !source) reply.error=-EBADF;
        else if (!source && kind==MD_PROC_NS_UNSUPPORTED) reply.error=-ENOENT;
        else if ((!source && !md_proc_control_kind(kind)) || t->proc.image_fd>=0) reply.error=-EINVAL;
        else if (source && target!=t && !proc_allowed(t->pid,target->pid)) reply.error=-EACCES;
        else if (md_proc_file_filesystem_paths(source)) reply.error=-EXDEV;
        else {
            struct md_proc_file_node node={0}; struct stat st;
            struct md_proc_file_node previous=source ? md_proc_file_get_node(source) : node;
            enum md_proc_file_kind file_kind=(enum md_proc_file_kind)(kind-MD_PROC_UID_MAP);
            struct md_pid_namespace *view=q->view ? md_pid_namespace_by_inode(q->view) : NULL;
            int sysctl=md_proc_file_sysctl(file_kind);
            int namespace=md_proc_file_namespace(file_kind);
            int control=source && file_kind==MD_PROC_FILE_CONTROL;
            struct proc_owner owner=sysctl ? proc_owner(t->pid) : source ? proc_owner(previous.process) : selected;
            int retained=source && !control && !namespace && !sysctl;
            int error=control ? md_proc_file_control_stat(source,&st) : namespace && source ? 0
                : retained ? md_proc_file_retained_stat(source,tasks,1,&st) : !owner.identity ? -ESRCH
                : proc_node_stat(owner,file_kind,&node,&st);
            if (!error && retained) node=previous;
            if (!error && q->view && !view && q->view!=md_pid_namespace_inode(NULL)) error=-ENOENT;
            if (namespace && source) {
                node=previous; md_namespace_reference_stat(md_proc_file_namespace_reference(source),&st);
            }
            if (!error && namespace && !source && target!=t && !proc_allowed(t->pid,target->pid)) error=-EACCES;
            if (!error && source && !control && (node.device!=previous.device || node.inode!=previous.inode)) error=-ESRCH;
            unsigned mode=(flags&O_ACCMODE)!=O_RDONLY || (flags&O_TRUNC) ? W_OK : R_OK;
            if (!error && namespace && (flags&O_NOFOLLOW) && !(flags&O_PATH)) error=-ELOOP;
            if (!error && !(flags&O_PATH)) error=control ? md_proc_file_control_access(source,&t->identity,mode)
                : namespace || md_proc_file_cgroup_kind(file_kind) ? mode==W_OK ? -EACCES : 0
                : sysctl ? md_proc_sysctl_access(file_kind,&t->identity,mode,1)
                : md_proc_file_task_access(&t->identity,&st,mode,1);
            if (!error && (flags&O_NOATIME) && !(flags&O_PATH)) error=md_file_noatime(&st,&t->identity);
            struct md_proc_file *file=NULL;
            if (!error && source) file=md_proc_file_reopen(source,
                &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},flags);
            else if (!error && namespace) {
                struct md_namespace_reference ref=md_namespace_reference_capture(
                    (enum md_namespace_kind)(file_kind-MD_PROC_FILE_NS_USER),selected.resources,selected.identity->user_namespace);
                file=md_proc_file_open_namespace(&ref,&t->identity,flags); md_namespace_reference_release(&ref);
            } else if (!error && sysctl) {
                struct md_control_file control=md_proc_sysctl_new(file_kind,NULL,t->resources.uts);
                if (control.ops) {
                    file=md_proc_file_open_control(control.ops,control.context,
                        &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},flags);
                    md_control_file_release(control);
                }
            } else if (!error) file=md_proc_file_open(selected.identity->user_namespace,file_kind,&t->identity,flags);
            if (file && !source) {
                md_proc_file_node(file,node); md_proc_file_pid_view(file,view);
                if (md_proc_file_cgroup_kind(file_kind)) md_proc_file_cgroup(file,selected.cgroup);
            }
            md_pid_namespace_release(view);
            int fd=error ? error : !file ? -errno : 0;
            SUPERVISOR_TRACE("PROBE proc-control open pid=%d target=%d kind=%u flags=%x provider=%d\n",
                t->pid,selected.pid,(unsigned)kind,flags,fd);
            if (fd<0) reply.error=fd;
            else {
                reply.error=proc_file_publish(t,file);
                if (!reply.error) {
                    md_proc_file_release(file);
                    md_proc_file_release(source);
                    return;
                }
            }
            md_proc_file_release(file);
        }
        md_proc_file_release(source);
    } else reply.error=-EINVAL;
    SUPERVISOR_TRACE("PROBE proc-control request pid=%d operation=%u result=%lld error=%d\n",
        t->pid,operation,(long long)reply.val,reply.error);
    buffer_reply(t,reply.error ? reply.error : reply.val);
}

void proc_io_progress(struct thread *t) {
    retry_clear(t,RETRY_PROC_IO);
    if (t->proc.waiting==2) {
        struct md_control_file file=md_proc_file_control(t->proc.file);
        if (!file.ops->readable(file.context)) { retry_wait(t,RETRY_PROC_IO); control_park(t); return; }
        int result=md_proc_file_io_begin(&t->proc.io,t->proc.file,MD_PROC_FILE_READ,
            0,0,0,t->fs.request.args[0],fd_wakeup);
        if (result<0) { t->proc.waiting=0; control_reply(t,result); return; }
        t->proc.waiting=1;
    }
    if (!t->proc.waiting) return;
    int state=md_resource_order_state(&t->proc.io.turn);
    if (!t->proc.io.pending && state!=MD_RESOURCE_ORDER_READY && state!=MD_RESOURCE_ORDER_RUNNING) {
        retry_wait(t,RETRY_PROC_IO); control_park(t); return;
    }
    struct md_buffer_span span;
    long result=md_buffer_span(&t->fs.buffer,&span);
    if (!result) {
        struct md_proc_file_node node=md_proc_file_get_node(t->proc.io.file);
        struct proc_owner owner=proc_owner(node.process);
        t->proc.io.tasks=tasks;
        t->proc.io.oom=md_proc_file_oom(md_proc_file_kind(t->proc.io.file))
            ? md_process_member_oom(owner.process) : NULL;
        struct md_process_limits limits=owner.process ? md_process_limits_value(owner.process) : (struct md_process_limits){0};
        if (owner.identity) md_proc_file_process_state(t->proc.io.file,owner.identity,owner.pid_namespace,
            md_debugger_owner(debugger,owner.pid),!md_proc_file_inspection(md_proc_file_kind(t->proc.io.file))
                || proc_allowed(t->pid,owner.pid),owner.process ? &limits : NULL);
        if (md_proc_file_kind(t->proc.io.file)==MD_PROC_FILE_STATUS) {
            struct thread *target=find_thread(node.process);
            struct md_signal_status view={0};
            if (target && target->signals.shielded) {
                view.blocked_known=UINT64_MAX; view.blocked=target->execution.mask;
            }
            if (target && target->signals.host) {
                view.known=MD_SIGNAL_SYS_BIT;
                view.blocked=md_signal_view_mask(view.blocked,target->signals.host_blocked);
                view.pending=md_signal_pending_has(target->signals.pending,MD_SIGNAL_PRIVATE) ? MD_SIGNAL_SYS_BIT : 0;
                view.shared_pending=md_signal_pending_has(target->signals.pending,MD_SIGNAL_SHARED) ? MD_SIGNAL_SYS_BIT : 0;
                struct md_kernel_action action;
                if (!md_signal_actions_read(target->signals.actions,SIGSYS,&action)) {
                    view.ignored=action.handler==(uintptr_t)SIG_IGN ? MD_SIGNAL_SYS_BIT : 0;
                    view.caught=action.handler!=(uintptr_t)SIG_IGN && action.handler!=(uintptr_t)SIG_DFL
                        ? MD_SIGNAL_SYS_BIT : 0;
                } else view.known=0;
            }
            md_proc_file_signal_state(t->proc.io.file,&view);
        }
        t->proc.io.actor=(struct md_control_actor){.pid=t->pid,.identity=&t->identity,.resources=&t->resources,
            .filesystem=filesystem,.submit=md_fs_worker_control};
        result=md_proc_file_io_execute(&t->proc.io,&t->identity,t->resources.uts,span.data,span.size,(size_t)getpagesize());
        if (result>0 && t->proc.io.oom && t->proc.io.operation==MD_PROC_FILE_WRITE) {
            struct thread *target=find_thread(node.process);
            int minimum=md_proc_file_kind(t->proc.io.file)==MD_PROC_FILE_OOM_SCORE_ADJ
                && md_identity_capable_in(&t->identity,NULL,CAP_SYS_RESOURCE);
            /* This uncommon policy mutation follows shared-mm process groups.
             * vfork borrows memory, not its parent's OOM policy. No syscall
             * polling or extra per-fork policy allocation is needed. */
            if (target && !target->process_op.vfork_borrowed) for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer->born && peer->memory==target->memory && !peer->process_op.vfork_borrowed) {
                    struct md_process_oom *state=md_process_member_oom(peer->resources.process);
                    if (!state || state==t->proc.io.oom) continue;
                    state->value=t->proc.io.oom->value;
                    if (minimum) state->minimum=t->proc.io.oom->minimum;
                    state->virtualized|=t->proc.io.oom->virtualized;
                }
        }
    }
    struct md_control_file file=md_proc_file_control(t->proc.file);
    if (result==-EINPROGRESS && t->proc.io.pending) {
        /* EVENT_WAIT: the admitted IO's provider reply or caller cancellation.
         * This OFD's cursor stays serialized; other files and tables progress. */
        retry_wait(t,RETRY_PROC_IO); control_park(t); return;
    }
    if (result==-EAGAIN && t->proc.io.operation==MD_PROC_FILE_READ && !t->proc.io.positional
            && !(t->execution.request.nr==SYS_splice && (t->execution.request.args[5]&SPLICE_F_NONBLOCK))
            && !(md_proc_file_flags(t->proc.file)&O_NONBLOCK) && file.ops && file.ops->readable) {
        CHECK(!md_proc_file_io_cancel(&t->proc.io));
        /* EVENT_WAIT: provider publishes readable/terminal state and signals
         * fd_ready. Exit or signal cancels this retained OFD, not a reused FD.
         * No file-table or IO turn is held while waiting for input. */
        t->proc.waiting=2; retry_wait(t,RETRY_PROC_IO); control_park(t); return;
    }
    if (result>=0) CHECK(!md_proc_file_io_offer(&t->proc.io));
    else CHECK(!md_proc_file_io_cancel(&t->proc.io));
    if (result>=0 && t->execution.request.nr!=SYS_splice && md_proc_file_io_more(&t->proc.io))
        result|=MD_BUFFER_PROC_MORE;
    t->proc.waiting=0; control_reply(t,result);
}

void proc_io_begin(struct thread *t) {
    size_t length=t->fs.request.args[0];
    if (t->proc.io.file) {
        if (!t->proc.io.transferred || t->proc.io.executed || t->proc.io.offered
                || length!=t->proc.io.length) { control_reply(t,-EINVAL); return; }
        t->proc.waiting=1; proc_io_progress(t); return;
    }
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=t->execution.request.args[i];
    struct md_proc_io_spec spec;
    int result=md_proc_io_decode(t->execution.request.nr,args,&spec);
    if (!result && spec.splice) spec.writing=t->proc.splice_write;
    if (!result && ((!spec.seek && !spec.command && !spec.vector && !spec.splice && length!=args[2])
            || (spec.command && length) || (spec.splice && length>args[4]) || length>PTRDIFF_MAX
            || (t->fs.request.command==MD_BUFFER_PROC_START && (spec.writing || spec.splice))))
        result=-EINVAL;
    if (!result) result=md_proc_file_io_begin(&t->proc.io,t->proc.file,
        spec.command ? MD_PROC_FILE_COMMAND : spec.seek ? MD_PROC_FILE_SEEK : spec.directory ? MD_PROC_FILE_GETDENTS
            : spec.writing ? MD_PROC_FILE_WRITE : MD_PROC_FILE_READ,
        spec.positional,spec.offset,spec.whence,length,fd_wakeup);
    if (result<0) { control_reply(t,result); return; }
    t->proc.waiting=1;
    /* EVENT_WAIT: preceding IO on the captured OFD finishes or is cancelled.
     * Buffer admission precedes this turn; neither waits on a file-table turn. */
    proc_io_progress(t);
}

static int proc_write_capture(struct thread *t,size_t *total) {
    if (!t->proc.file || md_proc_file_kind(t->proc.file)!=MD_PROC_FILE_CONTROL
            || t->fs.buffer.pool || t->proc.io.file || t->proc.direct
            || t->debug.options || t->debug.call_active || t->debug.step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=t->execution.request.args[i];
    struct md_proc_io_spec spec;
    if (md_proc_io_decode(t->execution.request.nr,args,&spec) || !spec.writing || spec.splice) return 0;
    struct iovec vectors[MD_PROC_IO_VECTORS];
    unsigned count=1;
    if (!spec.vector) vectors[0]=(struct iovec){(void *)args[1],args[2]};
    else {
        if (args[2]>MD_PROC_IO_VECTORS) return 0;
        count=(unsigned)args[2];
        struct iovec local={vectors,count*sizeof(*vectors)},remote={(void *)args[1],local.iov_len};
        if (process_vm_readv(t->pid,&local,1,&remote,1,0)!=(ssize_t)local.iov_len) return 0;
    }
    if (md_proc_io_vector_size(vectors,count,total) || !*total) return 0;
    if (md_buffers_take(md_memory_buffers(t->memory),&t->fs.buffer)) return 0;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->fs.buffer,&span) && span.size>=MD_CONTROL_IO_MAX);
    size_t size=*total<MD_CONTROL_IO_MAX ? *total : MD_CONTROL_IO_MAX,remaining=size;
    unsigned captured=0;
    for (unsigned i=0;i<count && remaining;++i) {
        struct iovec v=vectors[i];
        if (v.iov_len>remaining) v.iov_len=remaining;
        if (v.iov_len) { vectors[captured++]=v; remaining-=v.iov_len; }
    }
    struct iovec local={span.data,size};
    /* Capture precedes OFD admission and all provider effects. A protected or
     * unreadable input falls back to the caller's existing copy path, without
     * ptrace force-reading pages or replaying a partially committed write. */
    if (process_vm_readv(t->pid,&local,1,vectors,captured,0)!=(ssize_t)size) {
        CHECK(!buffer_release(t)); return 0;
    }
    return 1;
}

int proc_write_direct(struct thread *t) {
    size_t total;
    if (!proc_write_capture(t,&total)) return 0;
    t->proc.direct=1; t->phase=DISPATCHING;
    buffer_capture(t,&t->execution.registers);
    t->fs.request.command=MD_BUFFER_PROC_IO;
    t->fs.request.args[0]=total;
    t->fs.request.args[1]=0;
    if (statistics) ++statistics->captured_writes;
    proc_io_begin(t); return 1;
}

void proc_buffer_control(struct thread *t,const struct supervisor_control_request *control) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_FS_BUFFER_PROC_LINK: {
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || t->phase!=DISPATCHING
                || !control->args[0] || control->args[0]>INT_MAX || control->args[1]) break;
        struct thread *target=find_thread((pid_t)control->args[0]);
        if (!target || !proc_allowed(t->pid,target->pid)) { result=-EACCES; break; }
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_fs_buffer_frame *frame=span.data;
        result=md_process_image_link(target->pid,target->image.process_image,process_image_memory(t,target),frame->reply.data,PATH_MAX);
        if (result<0) SUPERVISOR_TRACE("PROBE proc-image-link caller=%d target=%d error=%ld\n",t->pid,target->pid,result);
        break;
    }
    case MD_BUFFER_PROC_OPEN: {
        if (t->phase!=DISPATCHING || control->args[0] || control->args[1] || !t->fs.buffer.pool) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_proc_open_request query;
        if (span.size<sizeof(query)) { result=-EOVERFLOW; break; }
        memcpy(&query,span.data,sizeof(query));
        proc_file_request(t,&query); return;
    }
    case MD_BUFFER_PROC_META: {
        if (t->phase!=DISPATCHING || control->args[0] || control->args[1] || !t->fs.buffer.pool) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_proc_metadata_request query; struct md_proc_metadata_result metadata;
        if (span.size<sizeof(query) || span.size<sizeof(metadata)) { result=-EOVERFLOW; break; }
        memcpy(&query,span.data,sizeof(query));
        result=proc_node_metadata(t,&query,&metadata);
        SUPERVISOR_TRACE("PROBE proc metadata pid=%d target=%d kind=%u access=%u result=%ld\n",
            t->pid,query.process,query.kind,query.access,result);
        if (!result) memcpy(span.data,&metadata,sizeof(metadata));
        break;
    }
    case MD_BUFFER_PROC_START:
        if (t->phase!=DISPATCHING || !t->proc.file || t->fs.buffer.pool || t->proc.io.file || control->args[1]) break;
        t->fs.waiting=1; buffer_take(t); return;
    case MD_BUFFER_PROC_IO: {
        if (t->phase!=DISPATCHING || !t->proc.file || !t->fs.buffer.pool
                || control->args[1]) break;
        proc_io_begin(t); return;
    }
    case MD_BUFFER_PROC_ACK:
        if (t->phase!=DISPATCHING || control->args[1]>1 || (long)control->args[0]<-4095) break;
        result=control->args[1] ? md_proc_file_io_advance(&t->proc.io,(long)control->args[0])
            : md_proc_file_io_finish(&t->proc.io,(long)control->args[0]); break;
    }
    buffer_reply(t,result); adapter_progress();
}

void proc_lookup_start(struct thread *t) {
    retry_clear(t,RETRY_PROC_LOOKUP);
    if (md_fd_order_state(&t->proc.lookup)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_PROC_LOOKUP); return; }
    CHECK(t->phase==PROC_LOOKUP && !md_fd_order_start(&t->proc.lookup));
    const struct seccomp_data *call=&t->execution.request;
    struct user_pt_regs *regs=entry_pending(t) ? NULL : &t->execution.registers;
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=call->args[i];
    long nr=call->nr;
    struct md_proc_file *file=proc_file_capture(t,(int)args[nr==SYS_mmap ? 4 : 0]);
    t->proc.splice_write=!file;
    if (!file && (nr==SYS_sendfile || nr==SYS_tee || nr==SYS_splice || nr==SYS_copy_file_range))
        file=proc_file_capture(t,(int)args[nr==SYS_sendfile || nr==SYS_tee ? 1 : 2]);
    CHECK(!md_fd_order_complete(&t->proc.lookup));
    t->phase=IDLE;
    /* A selector collision changes the filter cookie, not syscall ownership.
     * Ordinary directory cursors and filesystem metadata still need their
     * filesystem adapter, including after the pre-sandbox all-FD selector. */
    struct md_control_file operations=md_proc_file_control(file);
    if (md_proc_file_filesystem_paths(file) && (nr==SYS_fstatfs || nr==SYS_syncfs
            || ((nr==SYS_fsync || nr==SYS_fdatasync) && (!operations.ops || !operations.ops->command))
            || (nr==SYS_ioctl && md_filesystem_ioctl_command((unsigned)args[1])))) {
        md_proc_file_release(file); file=NULL;
    }
    int filesystem=!file && (nr==SYS_lseek || nr==SYS_getdents64 || nr==SYS_fstatfs
        || nr==SYS_fsync || nr==SYS_fdatasync || nr==SYS_syncfs
        || (nr==SYS_ioctl && md_filesystem_ioctl_command((unsigned)args[1])));
    if (nr==SYS_mmap) {
        md_proc_file_release(file);
        memory_begin(t,call,regs,MEMORY_KERNEL); return;
    }
    if (file && nr==SYS_fcntl) {
        long result=0;
        if (args[1]==F_SETFL && (args[2]&O_NOATIME)
                && !(md_proc_file_flags(file)&(O_NOATIME|O_PATH))) {
            struct stat st;
            result=md_proc_file_control(file).ops && !md_proc_file_task(md_proc_file_kind(file)) ? md_proc_file_control_stat(file,&st)
                : md_proc_file_retained_stat(file,tasks,0,&st);
            if (!result) result=md_file_noatime(&st,&t->identity);
        }
        int handled=result ? 1 : md_proc_file_fcntl(file,(int)args[1],args[2],&result);
        md_proc_file_release(file);
        if (handled) return_value(t,regs,result); else native_continue(t);
        return;
    }
    if (file) {
        struct md_proc_io_spec spec;
        struct md_control_file control=md_proc_file_control(file);
        int namespace_ioctl=nr==SYS_ioctl && (md_proc_file_namespace(md_proc_file_kind(file))
            || md_control_ioctl_available(control));
        int error=nr==SYS_fstatfs || namespace_ioctl ? 0 : md_proc_io_decode(nr,args,&spec);
        if (!error && nr==SYS_splice) {
            if (!control.ops || !control.ops->splice) error=-EINVAL;
            spec.writing=t->proc.splice_write;
        }
        if (error && nr==SYS_ioctl) error=-ENOTTY;
        if (!error && namespace_ioctl && (md_proc_file_flags(file)&O_PATH)) error=-EBADF;
        if (!error && nr!=SYS_fstatfs && !namespace_ioctl) error=md_proc_file_access(file,
            spec.command ? MD_PROC_FILE_COMMAND : spec.seek ? MD_PROC_FILE_SEEK : spec.directory ? MD_PROC_FILE_GETDENTS
                : spec.writing ? MD_PROC_FILE_WRITE : MD_PROC_FILE_READ);
        if (error) { md_proc_file_release(file); return_value(t,regs,error); return; }
        t->proc.file=file;
    } else if (!filesystem && nr!=SYS_read && nr!=SYS_readv
            && (nr!=SYS_ioctl || md_watch_ioctl_native(nr,args[1]))
            && nr!=SYS_splice && nr!=SYS_tee && nr!=SYS_sendfile) {
        /* Without a typed control, only FIONREAD can require watch adaptation.
         * Other ioctls already passed their domain owners; execute at the
         * original PC/mask, without a cancel/replay trip through WATCH_ENTRY. */
        native_continue(t); return;
    }
    if (entry_pending(t)) {
        /* Lookup only needs the typed request and an ordered FD reference.
         * Request a stopped context for the selected operation, never merely
         * to identify the descriptor after a broad sandbox selector. */
        execution_begin(t,call,NULL,"proc-lookup-mask");
        return;
    }
    t->watch_wait=0;
    if (!save_signal_mask(t,&t->execution.mask,"proc-lookup-mask")
            || !shield(t) || !skip(t->pid)) return;
    if (file || filesystem) {
        calls++; t->calls++; t->debug.step_call=md_debugger_step(debugger,t->pid);
        dispatch_with_stack(t);
    } else { t->phase=WATCH_CANCEL; resume(t->pid,0); }
}

void proc_lookup_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs) {
    if (!t->ready || t->phase!=IDLE || t->proc.file || t->proc.lookup.table)
        fprintf(stderr,"Shroot: proc lookup conflict pid=%d ready=%d phase=%d file=%d lookup=%d pc=%#llx nr=%llu original=%d originalPc=%#llx\n",
            t->pid,t->ready,t->phase,!!t->proc.file,!!t->proc.lookup.table,
            call->instruction_pointer,(unsigned long long)call->nr,t->execution.request.nr,t->execution.request.instruction_pointer);
    CHECK(t->ready && t->phase==IDLE && !t->proc.file && !t->proc.lookup.table);
    execution_retain(t,call,entry_pending(t) ? NULL : regs);
    t->phase=PROC_LOOKUP;
    CHECK(md_fd_order_begin(t->resources.files,&t->proc.lookup,fd_wakeup)>=0);
    /* EVENT_WAIT: preceding kernel close/dup and its metadata publication must
     * precede classification of a possibly reused number. Exit cancels lookup. */
    proc_lookup_start(t);
}
