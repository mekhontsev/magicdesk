#define _GNU_SOURCE
#include "raw.h"
#include "adapter_buffer.h"
#include "adapter_frame.h"
#include "cooperative_native.h"
#include "event_signal.h"
#include "fd_capture.h"
#include "fd_channel.h"
#include "fd_install.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_return.h"
#include "file_metadata.h"
#include "file_open.h"
#include "fs.h"
#include "fs_anchor.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "fs_continuation.h"
#include "fs_operation.h"
#include "fs_path_call.h"
#include "fs_syscall.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "supervisor_domain.h"
#include "guest_identity.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "landlock_policy.h"
#include "linux_abi.h"
#include "mount_namespace.h"
#include "namespace_broker.h"
#include "pending.h"
#include "proc_file.h"
#include "proc_paths.h"
#include "process_image_view.h"
#include "process_limits.h"
#include "queue_signal.h"
#include "received_fds.h"
#include "resource_order.h"
#include "seccomp_capture.h"
#include "signal_delivery.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_identity.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_proc.h"
#include "supervisor_readiness.h"
#include "supervisor_seccomp.h"
#include "supervisor_task.h"
#include "supervisor_transfer.h"
#include "syscall_result.h"
#include "sysv_ipc.h"
#include "task_descriptor.h"
#include "task_helper.h"
#include "task_memory.h"
#include "task_registry.h"
#include <linux/audit.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/timerfd.h>

struct buffer_completion { long value, released; };
static void load_path(struct thread *t);
static void export_reply(struct thread *t, long endpoint, uint64_t token);
static void export_stat_complete(struct thread *t,const struct md_fs_result *result,int error);
static void export_stat_begin(struct thread *t);
static void export_finish(struct thread *t);
static int capture_foreign_start(struct thread *t,struct capture_owner *owner);
static void capture_start(struct thread *t, struct md_fs_buffer_request *request);
static void buffer_result_begin(struct thread *t,int direct);
static void buffer_result_replay(struct thread *t);
static void buffer_complete_stopped(struct thread *t);
static struct buffer_completion buffer_finish(struct thread *t,long copied);
static void direct_status_complete(struct thread *t,const struct md_fs_result *result,int error);
static void direct_complete(struct thread *t,int error);

int buffer_release(struct thread *t) {
    int error=md_buffer_release(&t->fs.buffer);
    /* Prepared input belongs to this lease, not to the next adapter call. */
    if (!error) t->execution.prepared=0;
    return error;
}

void adapter_discard(struct thread *t) {
    transfer_discard(t);
    entry_wait_end(t);
    ipc_unwatch(t);
    t->process_op.clone_prepared=0;
    md_fs_anchor_release(t->process_op.clone_cgroup); t->process_op.clone_cgroup=NULL;
    md_memory_exposure_end(&t->memory_op.exposure,1);
    for (unsigned i=0;i<2;i++) {
        md_pending_cancel(&t->retries[i].node);
        t->retries[i].mask=0;
    }
    t->descriptors.selector_exit=0;
    close_detach(t);
    task_action_discard(t);
    readiness_release(t);
    if (t->control.pending) { t->control.pending->dispose(t->control.pending); t->control.pending=NULL; }
    control_publication_finish(t,-ECANCELED);
    t->control.done=0;
    t->execution.wait_resume=IDLE;
    if (t->proc.lookup.table) CHECK(!md_fd_order_cancel(&t->proc.lookup));
    control_input_discard(t);
    if (t->fs.descriptor_order.table) CHECK(!md_fd_order_cancel(&t->fs.descriptor_order));
    t->fs.descriptor_operation=0;
    memory_capture_discard(t);
    CHECK(!md_proc_file_io_cancel(&t->proc.io));
    md_proc_file_release(t->proc.file); t->proc.file=NULL; t->proc.waiting=t->proc.direct=0;
    outgoing_discard(t);
    memory_buffers_discard(t);
    md_fd_capture_cancel(&t->descriptors.pidfd_capture);
    memory_operation_discard(t);
    CHECK(!md_fd_return_cancel(&t->fs.returned));
    receive_discard(t);
    t->fs.return_waiting=0;
    CHECK(md_broker_result_finish(broker,t->pid,NULL)>=0);
    CHECK(!md_fd_install_cancel(&t->proc.delivery));
    md_fs_buffer_cancel(&t->proc.snapshot);
    if (t->proc.image_fd>=0) { close(t->proc.image_fd); t->proc.image_fd=-1; }
    if (t->control.mapping_fd>=0) { close(t->control.mapping_fd); t->control.mapping_fd=-1; }
    md_fs_buffer_cancel(&t->fs.call);
    if (t->capture) {
        t->capture->caller=0; t->capture=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
    md_fs_buffer_open_release(&t->fs.open);
    md_ipc_transport_close(&t->io.transport);
    CHECK(!buffer_release(t));
    t->fs.waiting=0;
    t->fs.rights_delivered=0;
    t->fs.direct=DIRECT_NONE;
}

int duplicate_fd(pid_t pid, int descriptor) {
    /* Never substitute a group's leader for another thread's descriptor table.
     * Older kernels can duplicate a leader's descriptors; other threads use
     * their task-affine SCM_RIGHTS continuation instead. */
    int process=-1;
    int fd=md_task_descriptor_copy(pid,descriptor,&process);
    if (process>=0) close(process);
    return fd;
}

int read_string(pid_t pid, uintptr_t address, char *text, size_t capacity) {
    ssize_t size=md_task_read_string(pid,address,text,capacity,md_page_size);
    return size<0 ? (int)size : 0;
}

void export_begin(struct thread *t, const struct seccomp_notif *q) {
    CHECK(t->phase == IDLE || t->phase == DISPATCHING);
    t->metadata = (struct metadata_operation){.previous = t->phase, .request = *q,
        .channel = -1, .descriptor = -1,
        .output_size = q->data.nr == SYS_openat ? 0 : sizeof(struct stat),
        .output = q->data.args[q->data.nr == SYS_newfstatat ? 2 : 1]};
    t->phase = EXPORT_CANCEL;
    tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-notification");
}

void export_start(struct thread *t,const struct user_pt_regs *observed) {
    struct metadata_operation *op = &t->metadata;
    op->mask_shielded=t->signals.shielded;
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->request.id) == -1 && errno == ENOENT);
    if (t->metadata_restart && op->previous==IDLE) op->saved=t->metadata_restart->saved;
    else if (observed) op->saved=*observed;
    else if (!registers(t->pid, &op->saved)) return;
    op->saved.pc = op->request.data.instruction_pointer;
    op->saved.regs[8] = op->request.data.nr;
    for (unsigned i = 0; i < 6; i++) op->saved.regs[i] = op->request.data.args[i];
    if (!save_signal_mask(t,&op->mask,"get-mask")
            || !shield(t)) return;
    struct user_pt_regs regs = op->saved;
    regs.pc = EXPORT; regs.regs[30] = abi.exported_entry;
    regs.regs[0] = op->request.data.args[0];
    if (!set_registers(t->pid, &regs)) return;
    t->phase = EXPORTING; resume(t->pid, 0);
}

void replay(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    /* Reenter kernel admission without recursively invoking a published payload.
     * The alias retains the original policy identity and continuation. */
    struct user_pt_regs regs = op->saved;
    regs.pc=md_svc_registry_replay(md_memory_svc(t->memory),regs.pc);
    if (!set_registers(t->pid, &regs)) return;
    t->phase = EXPORT_ENTRY; resume(t->pid, 0);
}

static void export_path_complete(struct thread *t,int error) {
    t->metadata.error=error;
    replay(t);
}

static void load_path(struct thread *t) {
    struct metadata_operation *op=&t->metadata;
    if (op->error) { replay(t); return; }
    transfer_begin(t,&op->saved,op->request.data.args[1],op->path,sizeof(op->path),
        MD_TRANSFER_STRING,export_path_complete);
}

static void export_reply(struct thread *t, long endpoint, uint64_t token) {
    struct user_pt_regs regs=t->metadata.control;
    regs.regs[0]=endpoint; regs.regs[1]=token;
    t->metadata.waiting=0;
    if (set_registers(t->pid,&regs)) resume(t->pid,0);
}

void export_progress(struct thread *t) {
    retry_clear(t,RETRY_EXPORT);
    struct metadata_operation *op=&t->metadata;
    if (!op->waiting) return;
    if (md_fd_order_state(&op->files)==MD_RESOURCE_ORDER_READY)
        CHECK(!md_fd_order_start(&op->files));
    if (md_fd_order_state(&op->files)!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_EXPORT); return; }
    int error=0;
    if (!op->transport) {
        error=md_fd_origins_channel(op->files.table,&op->channel,&op->transport);
        if (error==-ESTALE) {
            error=md_fd_origins_channel_candidate(op->files.table,&op->channel,&op->transport);
            if (!error) op->checking=1;
        }
        if (!error && !op->transport) error=md_fd_channel_new(&op->transport);
    }
    if (error) { export_reply(t,error,0); return; }
    if (op->checking) { export_reply(t,op->channel,0); return; }
    if (!op->capture.channel)
        error=md_fd_channel_capture_begin(op->transport,t->tgid,1,&op->capture);
    export_reply(t,error ? error : op->channel<0 ? MD_EXPORT_ADMIT : op->channel,
        error || op->channel<0 ? 0 : op->capture.request);
}

void export_control(struct thread *t, const struct user_pt_regs *regs) {
    struct metadata_operation *op=&t->metadata;
    op->control=*regs;
    int error=-EINVAL;
    if ((t->phase==EXPORTING || t->phase==PROC_OPENING) && !op->waiting) {
        if ((regs->regs[0]==MD_EXPORT_BEGIN || regs->regs[0]==MD_EXPORT_READY)
                && !regs->regs[1] && !regs->regs[2] && !regs->regs[3] && !regs->regs[4]
                && !op->checking) {
            error=0;
            if (!op->files.table) error=md_fd_order_begin(t->resources.files,&op->files,fd_wakeup);
        } else if (regs->regs[0]==MD_EXPORT_OBSERVED && op->checking
                && md_fd_order_state(&op->files)==MD_RESOURCE_ORDER_RUNNING) {
            struct md_fd_channel_observation observation={.cookie=regs->regs[1],
                .flags=regs->regs[2],.length=regs->regs[3],.error=regs->regs[4]};
            error=md_fd_channel_resolve(op->transport,op->files.table,op->channel,&observation);
            if (!error) {
                md_fd_channel_release(op->transport); op->transport=NULL;
                op->channel=-1; op->checking=0;
            }
        }
    }
    if (error<0) { export_reply(t,error,0); return; }
    op->waiting=1;
    /* EVENT_WAIT: exact file-table turn. No mapped slot is held or needed;
     * terminal task cleanup cancels the lease and drains only its rights. */
    export_progress(t);
}

static void export_stat_complete(struct thread *t,const struct md_fs_result *result,int error) {
    struct metadata_operation *op=&t->metadata;
    if (!error && result) error=result->error;
    if (error==-EXDEV) error=0;
    else if (!error && result) md_fs_info_stat(&result->info,&op->value);
    op->error=t->phase==PROC_OPENING && op->proc_stat && error==-EBADF ? -ENOENT : error;
    op->stat_call=NULL;
    replay(t);
}

static void export_stat_begin(struct thread *t) {
    struct metadata_operation *op=&t->metadata;
    int error=fstat(op->descriptor,&op->value) ? -errno : 0;
    /* Loader admission uses native metadata. Guest metadata uses the same
     * worker receipts as other captured requests: never synchronously wait for
     * the worker while the supervisor may own its quiescence barrier. */
    if (!error && t->endpoint[0] && (t->phase==PROC_OPENING
            || op->request.data.instruction_pointer!=RAW_GATE)) {
        error=md_fs_buffer_stat_begin(buffer_service,t->pid,t->resources.fs,op->descriptor,&op->stat_call);
        if (!error) { op->descriptor=-1; return; }
    }
    close(op->descriptor); op->descriptor=-1;
    export_stat_complete(t,NULL,error);
}

static void export_finish(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    struct user_pt_regs regs;
    if (!registers(t->pid, &regs)) return;
    long sent = (long)regs.regs[0];
    if (op->task_open) {
        /* Same-site admission preceded this fixed-path call. Its descriptor
         * is the application's result, never a temporary adapter handle. */
        op->returned=op->saved; op->returned.regs[0]=sent;
        if (sent>=0) CHECK(!md_fd_origins_close_range(op->files.table,sent,sent,0));
        else op->error=(int)sent;
        store_next(t); return;
    }
    op->error = sent < 0 ? (int)sent : sent == sizeof(uint64_t) ? 0 : -EIO;
    struct md_fd_channel_input input={0};
    int error=md_fd_channel_capture_finish(&op->capture,&input);
    if (!op->error) op->error=error;
    if (!op->error) {
        int fd=input.fd[0]; input.count=0;
        if (t->phase==PROC_OPENING && op->descriptor>=0) close(op->descriptor);
        op->descriptor=fd;
    }
    if (t->phase == PROC_OPENING && op->proc_stat && op->error == -EBADF) op->error = -ENOENT;
    md_fd_channel_input_close(&input);
    if (!op->error && (op->request.data.nr==SYS_fstat || t->phase==PROC_OPENING)) {
        export_stat_begin(t); return;
    }
    if (op->request.data.nr == SYS_fstat || t->phase == PROC_OPENING) replay(t); else load_path(t);
}

static void export_store_complete(struct thread *t,int error) {
    t->metadata.error=error;
    t->metadata.copied=t->metadata.output_size;
    store_next(t);
}

void store_next(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    if (op->error || op->copied == op->output_size) {
        struct user_pt_regs regs = op->returned;
        if (op->error) regs.regs[0] = (uint64_t)(long)op->error;
        if (t->metadata_restart && op->previous==IDLE) {
            release_operation(t); ++protected_stats;
            metadata_restart_return(t,(long)regs.regs[0]); return;
        }
        if (!set_registers(t->pid, &regs)
                || !signal_mask(t,op->mask,"restore-mask")) return;
        t->signals.shielded=op->mask_shielded;
        release_operation(t);
        t->phase = op->previous; protected_stats++; resume(t->pid, 0); return;
    }
    transfer_begin(t,&op->returned,op->output,&op->value,op->output_size,
        MD_TRANSFER_WRITE,export_store_complete);
}

void retained_result(struct thread *t, const struct seccomp_notif *q) {
    struct metadata_operation *op = &t->metadata;
    int fd = -1;
    if (!op->error && !(op->proc_attempted && op->proc_stat)) {
        int flags = q->data.nr == SYS_openat ? (int)q->data.args[2] : O_PATH;
        if (q->data.nr == SYS_newfstatat) {
            if (q->data.args[3] & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT)) op->error = -ENOTSUP;
            if (q->data.args[3] & AT_SYMLINK_NOFOLLOW) flags |= O_NOFOLLOW;
        }
        if (!op->error && !op->proc_attempted && q->data.nr==SYS_newfstatat && !q->data.args[3]) {
            /* A verified retained caller-FD directory selects task-affine
             * metadata directly. No openat2 magic-link failure is required. */
            int target=md_domain_proc_fd_number(op->descriptor,t->pid,op->path);
            if (target>=0) { proc_query_begin(t,q,target); return; }
        }
        if (!op->error && !op->proc_attempted && q->data.nr == SYS_openat
                && md_domain_proc_fd_query(op->descriptor, op->path, flags)) {
            /* Self access belongs to the caller, including O_PATH results
             * which cannot be delivered through notification ADDFD. */
            proc_query_begin(t,q,-1); return;
        }
        if (!op->error) {
            fd = md_domain_open_retained(op->descriptor, t->pid, op->path, flags);
        }
        if (fd < 0 && !op->error) op->error = fd;
        if (!op->error && q->data.nr == SYS_newfstatat && fstat(fd, &op->value)) op->error = -errno;
    }
    retained_calls++;
    if (op->error) retained_errors++;
    SUPERVISOR_TRACE("PROBE retained pid=%d nr=%d fd=%llu path=%s result=%d flags=%#llx\n",
        t->pid, q->data.nr, q->data.args[0], op->path, op->error,
        q->data.args[q->data.nr == SYS_openat ? 2 : 3]);
    if (!op->error && q->data.nr == SYS_openat) {
        struct seccomp_notif_addfd add = {.id = q->id, .srcfd = fd,
            .newfd_flags = (unsigned)q->data.args[2] & O_CLOEXEC,
            .flags = SECCOMP_ADDFD_FLAG_SEND};
        int result = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
        if (result < 0) op->error = -errno;
        else { close(fd); t->phase = EXPORT_RETURN; return; }
    }
    if (fd >= 0) close(fd);
    struct seccomp_notif_resp reply = {.id = q->id, .error = op->error};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
    t->phase = EXPORT_RETURN;
}

void delegate_begin(struct thread *t, const struct seccomp_notif *q) {
    CHECK(t->phase == IDLE);
    t->metadata = (struct metadata_operation){.request = *q, .descriptor = -1, .channel = -1};
    t->phase = DELEGATE_CANCEL;
    tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-delegation");
}

void buffer_setup(struct thread *t, const struct seccomp_notif *q) {
    size_t page=(size_t)getpagesize();
    int readonly=q->data.args[2]==1;
    size_t size=readonly ? (sizeof(struct md_seccomp_image)+page-1)/page*page*MD_SECCOMP_IMAGE_SLOTS
        : (sizeof(union md_adapter_frame)+page-1)/page*page
            *(MD_ADAPTER_BUFFER_SLOTS+MD_ADAPTER_CONTROL_SLOTS+MD_ADAPTER_CHANNEL_SLOTS);
    struct seccomp_notif_resp reply={.id=q->id};
    int preparing=readonly && t->seccomp.preflight && t->phase==DISPATCHING && t->seccomp.install
        && md_resource_order_state(&t->seccomp.install->memory)==MD_RESOURCE_ORDER_RUNNING;
    if (!t->ready || (!preparing && (t->entered || t->phase!=IDLE))
            || q->data.instruction_pointer!=RAW_GATE || q->data.args[1]!=size
            || q->data.args[2]>1 || q->data.args[3] || q->data.args[4] || q->data.args[5]) reply.error=-EPERM;
    else if (readonly) reply.error=md_memory_readonly_prepare(t->memory,MD_SECCOMP_IMAGE_SLOTS,sizeof(struct md_seccomp_image));
    else reply.error=md_memory_buffers_prepare(t->memory,MD_ADAPTER_BUFFER_SLOTS+MD_ADAPTER_CHANNEL_SLOTS,sizeof(union md_adapter_frame),
        offsetof(struct md_fs_buffer_frame,value),MD_ADAPTER_CONTROL_SLOTS);
    if (!reply.error && !readonly) reply.error=md_buffers_partition_channels(md_memory_buffers(t->memory),MD_ADAPTER_CHANNEL_SLOTS);
    if (!reply.error) {
        struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
            .srcfd=md_buffers_fd(readonly ? md_memory_readonly_buffers(t->memory) : md_memory_buffers(t->memory)),.newfd_flags=O_CLOEXEC};
        if (ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add)>=0) return;
        reply.error=-errno;
    }
    CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
}

void external_stat(void) {
    struct seccomp_notif q = {0};
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &q)) {
        CHECK(errno == ENOENT || errno == EINTR); return;
    }
    if (statistics) {
        if ((unsigned)q.data.nr < 512) statistics->calls[q.data.nr].notification++;
        else statistics->unknown++;
    }
    handle_notification(&q);
}

void buffer_reply_signal(struct thread *t, long result, const siginfo_t *signal) {
    uintptr_t extra=t->fs.request.args[0];
    if (t->fs.request.command==MD_BUFFER_PROC_START || t->fs.request.command==MD_BUFFER_EPOLL_WAIT
            || md_adapter_buffer_query(t->fs.request.command))
        extra=md_memory_buffer_address(t->memory,&t->fs.buffer);
    if (t->entry.control) {
        CHECK(!signal);
        CHECK(entry_reply(t,result,extra)); return;
    }
    struct user_pt_regs r=t->fs.registers;
    r.regs[0]=result; r.regs[1]=extra;
    if (signal) CHECK(!md_signal_delivery_hold(&t->signals.delivery,signal));
    if (set_registers(t->pid,&r)) resume(t->pid,0);
}

void buffer_reply(struct thread *t, long result) {
    buffer_reply_signal(t,result,NULL);
}

static int capture_foreign_start(struct thread *t,struct capture_owner *owner) {
    if (!owner->subject.task) {
        struct md_proc_path path=md_proc_path_for(owner->target.path,t->pid,t->tgid);
        if (path.kind!=MD_PROC_FOREIGN) return 0;
        struct md_task_credentials target;
        int error=md_task_registry_credentials(tasks,path.process,&target);
        if (error) return 0;
        int allowed=!target.retired && md_identity_capable_in(&t->identity,target.identity.user_namespace,CAP_SYS_PTRACE);
        if (allowed) owner->subject=(struct md_fs_task_subject){path.process,target.generation};
        md_task_credentials_release(&target);
        if (!allowed) return 0;
    }
    if (owner->subject.task==t->pid || owner->subject.task==t->tgid) return 0;
    struct task_action *action=task_action_new(t);
    if (!action) return -ENOMEM;
    action->capture=owner; owner->foreign=1;
    /* Selection starts later: the callback must not recursively complete and
     * free its capture before this admission returns. */
    CHECK(!md_event_signal(fd_ready));
    return 1;
}

void capture_complete(void *context, struct md_task_helper_result result) {
    struct capture_owner *owner=context;
    SUPERVISOR_TRACE("PROBE capture-complete caller=%d subject=%d path=%s flags=%#x metadata=%d directory=%zu value=%ld fd=%d\n",
        owner->caller,owner->subject.task,owner->target.path,owner->target.flags,owner->metadata,
        owner->directory_limit,result.value,result.descriptor);
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    if (t && t->capture==owner && !owner->foreign
            && (result.value==-ENOENT || result.value==-EACCES || result.value==-EPERM)) {
        t->kernel_stopped=1;
        int retry=capture_foreign_start(t,owner);
        if (retry>0) return;
        if (retry<0) result.value=retry;
    }
    if (t && t->capture==owner) { t->capture=NULL; t->kernel_stopped=1; }
    if (owner->complete) {
        if (t) owner->complete(t,result);
        else if (result.descriptor>=0) close(result.descriptor);
    } else CHECK(!md_fs_buffer_capture_finish(owner->request,result.descriptor,(int)result.value,&result.metadata,
        result.directory,result.directory_size));
    free(owner);
}

static void capture_start(struct thread *t, struct md_fs_buffer_request *request) {
    CHECK(!t->capture && !t->memory_op.mapping);
    SUPERVISOR_TRACE("PROBE buffer-capture pid=%d\n",t->pid);
    struct capture_owner *owner=calloc(1,sizeof(*owner));
    int error=owner ? md_fs_buffer_capture_start(request) : -ENOMEM;
    if (!error) {
        *owner=(struct capture_owner){.caller=t->pid,.request=request,.result={.descriptor=-1}};
        error=md_fs_buffer_capture_target(request,&owner->target,&owner->metadata,&owner->directory_limit,&owner->subject);
    }
    int capacity=!error && md_process_native_nofile(t->resources.process)<2;
    if (capacity && !owner->subject.task) {
        struct md_task_credentials credentials;
        error=md_task_registry_credentials(tasks,t->pid,&credentials);
        if (!error) {
            owner->subject=(struct md_fs_task_subject){t->pid,credentials.generation};
            md_task_credentials_release(&credentials);
        }
    }
    if (!error && owner->subject.task && (capacity
            || (owner->subject.task!=t->pid && owner->subject.task!=t->tgid))) {
        struct task_action *action=task_action_new(t);
        if (!action) error=-ENOMEM;
        else {
            t->capture=owner; action->capture=owner; owner->foreign=1;
            task_action_start(t); return;
        }
    }
    if (!error) {
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug.options);
    }
    if (!error) {
        struct md_task_helper_request capture={.kind=MD_TASK_HELPER_CAPTURE,
            .path=owner->target.path,.flags=owner->target.flags,.capture_metadata=owner->metadata,
            .directory_limit=owner->directory_limit};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,&t->fs.registers,&abi,
            t->memory,&t->fs.buffer,&capture,capture_complete,owner);
    }
    if (error) {
        free(owner);
        CHECK(!md_fs_buffer_capture_finish(request,-1,error,NULL,NULL,0));
    } else { t->capture=owner; t->kernel_stopped=0; }
}

int direct_addfd(const struct thread *t, const struct md_fs_buffer_frame *frame) {
    return !t->broker_event.held && (t->fs.direct==DIRECT_OPEN || t->fs.direct==DIRECT_PREPARE_RESULT) && frame->transfer==MD_FS_BUFFER_ADDFD
        && !frame->reply.result.selectors && t->fs.open.completion.kind==MD_OPEN_READY;
}

static void buffer_result_begin(struct thread *t,int direct) {
    struct seccomp_data call={.nr=direct ? SYS_fstat : SYS_gettid,.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=direct ? t->execution.request.instruction_pointer : abi.buffer_admit};
    /* Captured policies retain the owned publication gate across TSYNC. Only
     * an uncaptured inherited policy needs original-site result admission. */
    int replay=!direct && !t->seccomp.policy && t->seccomp.application_filter
        && md_broker_filesystem_call(t->execution.request.nr);
    t->fs.result_replay=replay;
    if (replay) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->fs.buffer,&span));
        ((struct md_fs_buffer_frame *)span.data)->transfer=MD_FS_BUFFER_REPLAY_ADDFD;
        call.nr=t->execution.request.nr; call.instruction_pointer=t->execution.request.instruction_pointer;
    }
    if (direct || replay) for (unsigned i=0;i<6;++i) call.args[i]=t->execution.request.args[i];
    else call.args[0]=MD_FS_BUFFER_RESULT_ADMIT;
    unsigned flags=direct ? t->execution.request.args[2] : t->fs.open.flags;
    CHECK(!md_broker_result_begin(broker,t->pid,&call,t->resources.files,
        t->fs.open.fd,md_fs_buffer_open_object(&t->fs.open),flags&O_CLOEXEC));
}

static void buffer_result_replay(struct thread *t) {
    t->fs.result_replay=0;
    struct user_pt_regs regs=t->execution.registers;
    regs.pc=RAW_GATE-4;
    if (set_registers(t->pid,&regs)) {
        t->phase=RESULT_REPLAY_ENTRY; resume(t->pid,0);
    }
}

static void buffer_complete_stopped(struct thread *t) {
    /* The helper can finish while the caller is entering this checkpoint.
     * Its readiness callback then has no parked wait to interrupt. Consume
     * that retained result here, instead of waiting for a second completion. */
    if (md_fs_buffer_open_ready(t->fs.call)) {
        CHECK(!md_fs_buffer_open_resume(t->fs.call)); return;
    }
    buffer_complete(t->fs.call,t->pid,t->entry.error);
}

void buffer_complete(struct md_fs_buffer_request *request, pid_t pid, int error) {
    struct thread *t=find_thread(pid);
    if (t && t->proc.snapshot==request) {
        proc_snapshot_complete(t,request,error); return;
    }
    if (t && t->metadata.stat_call==request) {
        const struct md_fs_result *result=md_fs_buffer_status_result(request);
        CHECK(result);
        export_stat_complete(t,result,error); return;
    }
    CHECK(t && t->fs.call==request);
    if (md_fs_buffer_open_waiting(request) || md_fs_buffer_capture_pending(request)) {
        t->entry.error=error;
        if (entry_require_stop(t,md_fs_buffer_open_waiting(request) ? MD_CONTEXT_WAIT : MD_CONTEXT_DESCRIPTOR,
                0,buffer_complete_stopped)) return;
    }
    if (md_fs_buffer_open_waiting(request)) {
        if (md_fs_buffer_open_ready(request)) {
            if (t->phase==FILE_INTERRUPTING) file_wait_complete(t);
            else if (t->phase==FILE_WAITING && !t->listening)
                tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"open-ready");
            return;
        }
        CHECK(t->kernel_stopped);
        if (t->fs.direct==DIRECT_OPEN && !save_signal_mask(t,&t->execution.mask,"open-wait-mask")) return;
        if (!shield(t)) return;
        /* EVENT_WAIT: child completion interrupts this empty native poll;
         * guest signals request interruption without losing completed opens. */
        wait_poll_start(t,FILE_WAIT_ENTRY);
        return;
    }
    if (t->fs.direct==DIRECT_STATUS) {
        const struct md_fs_result *result=md_fs_buffer_status_result(request);
        CHECK(result);
        t->fs.call=NULL;
        direct_status_complete(t,result,error); return;
    }
    if (md_fs_buffer_capture_pending(request)) { capture_start(t,request); return; }
    if (!md_fs_buffer_ack_pending(request) && !md_fs_buffer_export_pending(request)
            && !md_fs_buffer_snapshot_pending(request) && !md_fs_buffer_task_pending(request)) t->fs.call=NULL;
    if (!error) {
        struct md_received_fds *expanded=NULL;
        struct md_landlock_policy *landlock=md_fs_buffer_landlock_take(request);
        if (landlock) {
            md_landlock_release(t->resources.landlock); t->resources.landlock=landlock;
            CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&t->identity,&t->resources));
        }
        int inspected=md_fs_buffer_inspect_take(request,&expanded);
        CHECK(inspected>=0);
        if (inspected) {
            CHECK(t->io.receive && t->io.receive->ready);
            md_received_fds_release(t->io.receive->expanded);
            t->io.receive->expanded=expanded;
        }
        int anonymous=0;
        CHECK(md_fs_buffer_exec_take(request,t->image.images,&t->image.mount_flags,&anonymous,
            &t->image.prepared_executable_origin,&t->execfd_origin,&t->image.setid)>=0);
        if (anonymous) {
            CHECK(!t->image.prepared_executable);
            t->image.prepared_executable=md_process_executable_new(t->image.images[0]);
            if (!t->image.prepared_executable) { error=-errno; exec_images_release(t); }
        }
        if (!error && !t->entered && t->image.images[0]>=0) exec_images_activate(t);
        if (!md_fs_buffer_task_pending(request)) CHECK(md_fs_buffer_open_take(request,&t->fs.open)>=0);
    }
    if (!error && t->fs.open.fd>=0 && !t->fs.open.task_path) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->fs.buffer,&span));
        const struct md_fs_buffer_frame *frame=span.data;
        if (frame->transfer==MD_FS_BUFFER_ADDFD)
            buffer_result_begin(t,direct_addfd(t,frame));
    }
    SUPERVISOR_TRACE("PROBE buffer-call pid=%d operation=%llu error=%d descriptor=%d\n",pid,t->fs.request.args[0],error,t->fs.open.fd>=0);
    if (t->fs.direct==DIRECT_OPEN) { direct_complete(t,error); return; }
    buffer_reply(t,error);
}

int buffer_cleanup(struct thread *t) {
    identity_discard(t);
    if (t->execution.request.nr==SYS_ioctl) control_input_discard(t);
    control_publication_finish(t,-ECANCELED);
    md_fs_anchor_release(t->process_op.clone_cgroup); t->process_op.clone_cgroup=NULL;
    readiness_release(t);
    if (t->io.outgoing) {
        if (t->io.outgoing->stage!=OUTGOING_READY
                && !(t->io.outgoing->brokered && t->io.outgoing->stage==OUTGOING_CAPTURE)) return -EBUSY;
        outgoing_release(t->io.outgoing); t->io.outgoing=NULL;
    }
    md_fs_buffer_cancel(&t->fs.call);
    CHECK(md_broker_result_finish(broker,t->pid,NULL)>=0);
    md_fs_buffer_open_release(&t->fs.open);
    t->fs.result_replay=0;
    md_ipc_transport_close(&t->io.transport);
    if (t->proc.image_fd>=0) { close(t->proc.image_fd); t->proc.image_fd=-1; }
    if (t->control.mapping_fd>=0) { close(t->control.mapping_fd); t->control.mapping_fd=-1; }
    t->fs.rights_delivered=0;
    return buffer_release(t);
}

static struct buffer_completion buffer_finish(struct thread *t,long copied) {
    long value=t->proc.io.file ? md_proc_file_io_finish(&t->proc.io,copied) : copied ? -EINVAL : 0;
    long released=buffer_cleanup(t);
    return (struct buffer_completion){value,released};
}

long buffer_result_finish(struct thread *t,long copied,long result) {
    control_publication_finish(t,result);
    int delivered=t->proc.io.file && (t->proc.io.operation==MD_PROC_FILE_READ || t->proc.io.operation==MD_PROC_FILE_GETDENTS)
        && (t->proc.io.transferred || copied>0);
    struct buffer_completion completed=buffer_finish(t,copied);
    if (completed.value && !delivered) result=completed.value;
    if (result==MD_CALL_NATIVE && completed.released<0) return completed.released;
    return result<0 ? result : completed.released<0 ? completed.released : result;
}

void buffer_take(struct thread *t) {
    retry_clear(t,RETRY_BUFFER);
    int start=t->fs.request.command==MD_BUFFER_PROC_START;
    int query=md_adapter_buffer_query(t->fs.request.command);
    int error=md_buffers_take_sized(md_memory_buffers(t->memory),start || query ? 0 : t->fs.request.args[0],&t->fs.buffer);
    if (error==-EAGAIN) { retry_wait(t,RETRY_BUFFER); return; }
    t->fs.waiting=0;
    uintptr_t address=error ? 0 : md_memory_buffer_address(t->memory,&t->fs.buffer);
    if (!error && !address) { CHECK(!buffer_release(t)); error=-EINVAL; }
    if (!error && start) { proc_io_begin(t); return; }
    if (!error && query) {
        if (t->fs.request.command==MD_BUFFER_UTS) domain_buffer_control(t,&t->fs.request);
        else if (t->fs.request.command==MD_BUFFER_PROCESS_QUERY) task_buffer_control(t,&t->fs.request);
        else if (t->fs.request.command==MD_BUFFER_CONTROL_IOCTL_LAYOUT) descriptors_buffer_control(t,&t->fs.request);
        else io_buffer_control(t,&t->fs.request,NULL);
        return;
    }
    buffer_reply(t,error ? error : (long)address);
}

void buffer_return_progress(struct thread *t) {
    retry_clear(t,RETRY_RETURN);
    if (!t->fs.return_waiting) return;
    int result=md_fd_return_progress(&t->fs.returned,fd_wakeup);
    if (result==MD_FD_RETURN_WAIT) { retry_wait(t,RETRY_RETURN); return; }
    if (result>=0) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->fs.buffer,&span));
        struct md_fs_buffer_frame *frame=span.data;
        if (result==MD_FD_RETURN_CHECK) {
            frame->channel.number=t->fs.returned.endpoint;
            frame->channel.observation=(struct md_fd_channel_observation){0};
            result=MD_FS_BUFFER_CHANNEL_CHECK;
        } else {
            frame->receive.number=t->fs.returned.endpoint;
            frame->receive.mapped=t->fs.returned.delivery.receipt!=NULL;
            result=0;
        }
    }
    t->fs.return_waiting=0;
    buffer_reply(t,result);
}

static void buffer_finish_control(struct thread *t) {
    if (!t->entry.execution && entry_require_stop(t,MD_CONTEXT_PROCESS,0,buffer_finish_control)) return;
    long result=buffer_result_finish(t,(long)t->fs.request.args[0],(long)t->fs.request.args[1]);
    /* A resident adapter still owns its C frame. Return to it; END retires the
     * syscall. Only a Base adapter can bypass its frame at a terminal checkpoint. */
    if (t->entry.execution) buffer_reply(t,result);
    else dispatch_complete(t,result);
    adapter_progress();
}

void fs_buffer_control(struct thread *t,const struct supervisor_control_request *control) {
    long result=-EINVAL;
    int snapshot=md_fs_buffer_snapshot_pending(t->fs.call);
    switch (control->command) {
    case MD_FS_BUFFER_RESULT_REPLAY: {
        if (!t->fs.result_replay || t->phase!=DISPATCHING || t->fs.open.fd<0
                || control->args[0] || control->args[1]) { result=-EPERM; break; }
        buffer_result_replay(t);
        return;
    }
    case MD_BUFFER_TAKE:
        if (t->fs.buffer.pool || control->args[1]) break;
        if (t->phase==DISPATCHING && t->execution.request.nr==SYS_ppoll) {
            /* The process owner commits every NOFILE update after native
             * success and shares it across threads. No foreign prlimit/proc
             * read or guest-filtered helper syscall belongs in each poll. */
            if (t->execution.request.args[1]>md_process_native_nofile(t->resources.process)) {
                result=-EINVAL; break;
            }
        }
        t->fs.waiting=1; buffer_take(t);
        /* EVENT_WAIT: a task or worker releases a mapped slot. Owned process
         * cancellation ends the wait; no polling or guest retry is required. */
        return;
    case MD_FS_BUFFER_CALL:
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || control->args[1]) break;
        result=md_fs_buffer_begin(buffer_service,t->pid,t->resources.fs,(unsigned)control->args[0],&t->fs.buffer,&t->fs.call);
        if (!result) {
            /* EVENT_WAIT: filesystem context admission and exact worker
             * completion; launch cancellation detaches, never replays a call. */
            adapter_progress(); return;
        }
        break;
    case MD_FS_BUFFER_EXEC_PREPARE:
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || control->args[0]!=MD_FS_OPEN_IMAGE || control->args[1]
                || t->image.images[0]>=0 || t->image.images[1]>=0 || t->image.images[2]>=0
                || !((t->phase==IDLE && !t->entered && !t->image.mapped)
                    || (t->phase==DISPATCHING
                        && (t->execution.request.nr==SYS_execve || t->execution.request.nr==SYS_execveat)))) break;
        result=md_fs_buffer_exec_begin(buffer_service,t->pid,t->resources.fs,&t->fs.buffer,&t->fs.call,
            md_debugger_traced(debugger,t->pid));
        if (!result) { adapter_progress(); return; }
        break;
    case MD_FS_BUFFER_EXEC_CANCEL:
        if (control->args[0] || control->args[1] || (t->phase!=DISPATCHING && (t->phase!=IDLE || t->entered))) break;
        exec_images_release(t); result=0; break;
    case MD_FS_BUFFER_SNAPSHOT_BEGIN:
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || control->args[0]>UINT_MAX || control->args[1]) break;
        result=md_fs_buffer_snapshot_begin(buffer_service,t->pid,t->resources.fs,
            &t->fs.buffer,(unsigned)control->args[0],&t->fs.call);
        if (!result) { adapter_progress(); return; }
        break;
    case MD_FS_BUFFER_SNAPSHOT_APPEND:
    case MD_FS_BUFFER_SNAPSHOT_FINISH:
        if (!snapshot || control->args[0] || control->args[1]) break;
        result=md_fs_buffer_snapshot_advance(t->fs.call,control->command==MD_FS_BUFFER_SNAPSHOT_FINISH);
        /* EVENT_WAIT: worker appends captured bytes or publishes the result;
         * cancellation retains ownership until the worker completes. */
        if (!result) return;
        md_fs_buffer_cancel(&t->fs.call);
        break;
    case MD_BUFFER_RELEASE:
        if (control->args[0] || control->args[1]) break;
        result=buffer_cleanup(t); break;
    case MD_FS_BUFFER_TASK_DONE:
        if (control->args[1] || t->descriptors.running) break;
        result=md_fs_buffer_task_finish(&t->fs.call,(long)control->args[0]);
        break;
    case MD_BUFFER_FINISH: {
        long value=(long)control->args[1];
        if (t->phase!=DISPATCHING || (long)control->args[0]<-4095
                || (value<-4095 && value!=MD_CALL_NATIVE
                    && value!=RESTART_SIGNAL && value!=RESTART_PREPARATION)) break;
        buffer_finish_control(t); return;
    }
    case MD_FS_BUFFER_ACK:
        if (control->args[1] || (long)control->args[0]>0 || (long)control->args[0]<-4095) break;
        result=md_fs_buffer_ack(t->fs.call,(int)control->args[0]);
        /* EVENT_WAIT: worker commits or rejects reserved records/cursor;
         * process cancellation retires an unconfirmed delivery. */
        if (!result) return;
        md_fs_buffer_cancel(&t->fs.call);
        break;
    case MD_FS_BUFFER_SENT:
        if (control->args[1] || (long)control->args[0]>0 || (long)control->args[0]<-4095) break;
        result=md_fs_buffer_export_sent(t->fs.call,(int)control->args[0]);
        if (!result) { adapter_progress(); return; }
        break;
    case MD_FS_BUFFER_CHANNEL_OBSERVED:
        if (!t->fs.buffer.pool || control->args[0] || control->args[1]) break;
        if (t->fs.call) {
            result=md_fs_buffer_channel_observed(t->fs.call);
            if (!result) { adapter_progress(); return; }
        } else if (t->fs.returned.checking) {
            struct md_buffer_span span;
            result=md_buffer_span(&t->fs.buffer,&span);
            if (result) break;
            const struct md_fs_buffer_frame *frame=span.data;
            result=md_fd_return_observed(&t->fs.returned,&frame->channel.observation);
            if (!result) { t->fs.return_waiting=1; buffer_return_progress(t); return; }
        }
        break;
    case MD_FS_BUFFER_RIGHTS_BEGIN: {
        if (!t->fs.buffer.pool || control->args[0] || control->args[1] || t->fs.rights_delivered) break;
        if (t->fs.open.fd<0) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_fs_buffer_frame *frame=span.data;
        if (frame->transfer!=MD_FS_BUFFER_RIGHTS) { result=-EINVAL; break; }
        int flags=t->fs.open.flags&O_CLOEXEC ? FD_CLOEXEC : 0;
        struct md_fd_object *object=md_fs_buffer_open_object(&t->fs.open);
        frame->reply.result.selectors=md_fd_object_selectors(object);
        result=md_fd_return_begin(&t->fs.returned,t->resources.files,t->fs.open.fd,
            object,flags,(void *)frame->receipt,fd_wakeup);
        if (result) break;
        t->fs.return_waiting=1;
        /* EVENT_WAIT: destination table and shared channel become available;
         * terminal cleanup publishes the exact receipt and drains queued rights. */
        buffer_return_progress(t); return;
    }
    case MD_FS_BUFFER_RIGHTS_READY:
        if (!t->fs.returned.files.table || t->fs.returned.endpoint<0 || t->fs.returned.offered
                || control->args[0] || control->args[1]) break;
        t->fs.return_waiting=1; buffer_return_progress(t); return;
    case MD_FS_BUFFER_RIGHTS_DONE: {
        if (!t->fs.buffer.pool || control->args[1] || (long)control->args[0]<-4095) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        const struct md_fs_buffer_frame *frame=span.data;
        result=!t->fs.returned.files.table && (long)control->args[0]<0 ? (long)control->args[0]
            : md_fd_return_finish(&t->fs.returned,(long)control->args[0],frame->receive.token);
        t->fs.rights_delivered=1;
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}

int direct_begin(struct thread *t) {
    long nr=t->execution.request.nr;
    int status=nr!=SYS_openat;
    if (!t->endpoint[0] || t->fs.direct || t->fs.buffer.pool || t->seccomp.application_filter
            || t->debug.options || t->debug.call_active || t->debug.step_call
            || (status ? nr!=SYS_unlinkat && nr!=SYS_renameat && nr!=SYS_renameat2
                && nr!=SYS_linkat && nr!=SYS_mkdirat : nr!=SYS_openat)) return 0;
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=t->execution.request.args[i];
    struct md_fs_request q={.directory={-1,-1}};
    unsigned paths=md_fs_path_call(&q,nr,args);
    CHECK(paths);
    char captured[2][PATH_MAX];
    for (unsigned i=0;i<paths;++i) {
        char *path=captured[i];
        if (read_string(t->pid,args[i ? 3 : 1],path,PATH_MAX)) return 0;
        q.path[i]=path; q.directory[i]=-1;
        if (*path!='/') {
            int base=(int)args[i ? 2 : 0];
            if (base<0 && base!=AT_FDCWD) return 0;
            q.directory[i]=base;
        }
    }
    int managed=md_domain_scoped(t->resources.fs)
        || md_mount_namespace_managed_paths(t->resources.mounts) || t->resources.landlock;
    if (paths==1 && !managed && md_host_path(q.path[0])) return 0;
    int error;
    if (status) {
        error=md_fs_buffer_invoke_status(buffer_service,t->pid,t->resources.fs,&q,&t->fs.call);
    } else {
        if (md_buffers_take(md_memory_buffers(t->memory),&t->fs.buffer)) return 0;
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->fs.buffer,&span));
        struct md_fs_buffer_frame *frame=span.data;
        memset(&frame->input,0,offsetof(struct md_fs_buffer_input,path));
        memset(frame->input.capture_length,0,sizeof(frame->input.capture_length));
        memset(frame->input.capture_flags,0,sizeof(frame->input.capture_flags));
        frame->input.base[0]=q.directory[0]; frame->input.base[1]=-1;
        frame->input.length[0]=strlen(q.path[0])+1;
        memcpy(frame->input.path[0],q.path[0],frame->input.length[0]);
        frame->input.flags=q.flags; frame->input.mode=q.mode; frame->input.attributes=q.attributes;
        error=md_fs_buffer_invoke(buffer_service,t->pid,t->resources.fs,q.operation,&t->fs.buffer,&t->fs.call);
    }
    if (error) { CHECK(!buffer_release(t)); return 0; }
    if (status && !skip(t->pid)) { md_fs_buffer_cancel(&t->fs.call); return 1; }
    t->fs.direct=status ? DIRECT_STATUS : DIRECT_OPEN; t->phase=DISPATCHING;
    t->fs.direct_flags=q.flags; t->fs.direct_mode=q.mode;
    buffer_capture(t,&t->execution.registers); t->fs.request.args[0]=q.operation;
    if (statistics) {
        if (status) ++statistics->direct_status;
        else ++statistics->direct_open;
    }
    /* EVENT_WAIT: worker completion or task death owns the captured request.
     * No guest instruction runs before completion; mutations are never replayed. */
    adapter_progress(); return 1;
}

static void direct_status_complete(struct thread *t,const struct md_fs_result *result,int error) {
    CHECK(!t->fs.call && !t->fs.buffer.pool && t->kernel_stopped);
    int delegate=error==MD_FS_BUFFER_NEEDS_TASK || (!error && result->host_path);
    long value=error ? error : result->error;
    t->fs.direct=DIRECT_NONE;
    if (delegate) {
        if (statistics) ++statistics->direct_fallback;
        if (save_signal_mask(t,&t->execution.mask,"direct-fallback-mask")
                && shield(t)) dispatch_with_stack(t);
        return;
    }
    struct user_pt_regs regs=t->execution.registers; regs.regs[0]=value;
    if (!set_registers(t->pid,&regs)) return;
    t->phase=IDLE;
    if (!completed_event_resume(t)) resume(t->pid,0);
}

static void direct_complete(struct thread *t,int error) {
    CHECK(!t->fs.call && t->kernel_stopped);
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->fs.buffer,&span));
    const struct md_fs_buffer_frame *frame=span.data;
    int delegate=error==MD_FS_BUFFER_NEEDS_TASK || (!error && frame->reply.result.host_path);
    long value=error ? error : frame->reply.result.error;
    if (!error && !delegate && !value) {
        if (direct_addfd(t,frame)) {
            /* Only the kernel's syscall selector changes. The existing fstat
             * listener admits the exact pending result before normal decoding;
             * x8, arguments, PC, stack and flags retain the application context.
             * Exit observation still owns cancellation and retained FD cleanup. */
            int nr=SYS_fstat; struct iovec number={&nr,sizeof(nr)};
            if (!tracee_request(t,PTRACE_SETREGSET,(void *)NT_ARM_SYSTEM_CALL,&number,"direct-admit-syscall")) return;
            t->fs.direct=DIRECT_ADMIT;
            if (statistics) ++statistics->direct_admit;
            resume(t->pid,0); return;
        }
        t->fs.direct=DIRECT_PREPARE_RESULT;
        if (save_signal_mask(t,&t->execution.mask,"direct-result-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    CHECK(!buffer_cleanup(t)); t->fs.direct=DIRECT_NONE;
    if (delegate) {
        if (statistics) ++statistics->direct_fallback;
        if (save_signal_mask(t,&t->execution.mask,"direct-fallback-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    struct user_pt_regs regs=t->execution.registers; regs.regs[0]=value;
    if (!skip(t->pid) || !set_registers(t->pid,&regs)) return;
    t->phase=IDLE;
    if (!completed_event_resume(t)) resume(t->pid,0);
}

void file_wait_complete(struct thread *t) {
    struct user_pt_regs returned;
    if (!registers(t->pid,&returned)) return;
    if (!shield(t)) return;
    if (!md_fs_buffer_open_ready(t->fs.call)) {
        /* EVENT_WAIT: the exact helper acknowledges interruption. Keep this
         * task stopped, not the supervisor or other guest threads. A completed
         * endpoint wins; only an unpaired open retains kernel SA_RESTART. */
        t->phase=FILE_INTERRUPTING;
        CHECK(!md_fs_buffer_open_interrupt(t->fs.call));
        return;
    }
    t->phase=DISPATCHING;
    if (!md_fs_buffer_open_interrupted(t->fs.call)) {
        /* Direct publication captures its own shield. Do not let it mistake
         * this wait's temporary mask for the application's saved mask. */
        if (t->fs.direct==DIRECT_OPEN && !restore_mask(t)) return;
        CHECK(!md_fs_buffer_open_resume(t->fs.call));
        return;
    }
    /* The helper confirmed that no endpoint was admitted. The kernel owns
     * the application's restart decision; the adapter never replays a path. */
    CHECK(!buffer_cleanup(t)); t->fs.direct=DIRECT_NONE;
    t->broker_event.held=0;
    long value=(long)returned.regs[0];
    if (value==-EINTR || md_syscall_restarting(value)) restart_call(t,&t->execution.registers,t->execution.mask,RESTART_SIGNAL);
    else {
        struct user_pt_regs regs=t->execution.registers; regs.regs[0]=value<0 ? value : -EIO;
        if (!set_registers(t->pid,&regs) || !restore_mask(t)) return;
        md_memory_stack_return(t->memory,t->execution.stack); t->execution.stack=0;
        t->phase=IDLE; resume(t->pid,0);
    }
}

int fs_result_exit(struct thread *t,pid_t pid) {
    if (t->phase==RESULT_REPLAY_ENTRY) {
        /* The gate supplies SVC, never an instruction from a patched
         * application page. Restore the original context before kernel
         * seccomp so IP- and argument-dependent rules see that call. */
        if (!set_registers(pid,&t->execution.registers)) return 1;
        t->phase=RESULT_REPLAY_EXIT; resume(pid,0); return 1;
    }
    if (t->phase==RESULT_REPLAY_EXIT) {
        long value;
        int delivered=md_broker_result_finish(broker,pid,&value);
        CHECK(delivered>=0);
        if (!delivered) {
            struct user_pt_regs regs;
            if (!registers(pid,&regs)) return 1;
            value=(long)regs.regs[0];
        }
        if (!delivered && md_syscall_restarting(value)) {
            /* Group stops can cancel admission despite the signal
             * shield. Keep the completed open, replace only its expired
             * notification, and reapply the original kernel policy. */
            buffer_result_begin(t,0);
            buffer_result_replay(t); return 1;
        }
        t->phase=DISPATCHING; buffer_reply(t,value); return 1;
    }
    return 0;
}

int fs_direct_exit(struct thread *t,pid_t pid) {
    if (t->fs.direct==DIRECT_ADMIT) {
        long value;
        int delivered=md_broker_result_finish(broker,pid,&value);
        CHECK(delivered>=0);
        if (!delivered) {
            struct user_pt_regs regs;
            if (!registers(pid,&regs)) return 1;
            value=(long)regs.regs[0];
        }
        if (value==-EINTR || md_syscall_restarting(value)) {
            if (statistics) ++statistics->direct_retry;
            /* The mutation is complete. Retry only its cancelled FD
             * admission under the existing task-affine signal shield. */
            buffer_result_begin(t,0);
            t->fs.direct=DIRECT_PREPARE_RESULT;
            if (save_signal_mask(t,&t->execution.mask,"direct-retry-mask")
                    && shield(t) && skip(pid)) dispatch_with_stack(t);
            return 1;
        }
        CHECK(!buffer_cleanup(t)); t->fs.direct=DIRECT_NONE;
        t->phase=IDLE; resume(pid,0); return 1;
    }
    return 0;
}

int fs_wait_exit(struct thread *t,pid_t pid) {
    if (t->phase==FILE_WAIT_ENTRY) {
        if (md_fs_buffer_open_ready(t->fs.call)) {
            wait_cancel_entry(t,FILE_WAITING);
            return 1;
        }
        if (!restore_mask(t)) return 1;
        t->phase=FILE_WAITING; resume(pid,0); return 1;
    }
    if (t->phase==FILE_WAITING) { file_wait_complete(t); return 1; }
    return 0;
}

int fs_export_exit(struct thread *t,pid_t pid) {
    if (t->phase == EXPORT_ENTRY) {
        t->phase = EXPORT_NOTIFY;
        resume(pid, 0); return 1;
    }
    if (t->phase == EXPORT_RETURN || t->phase == EXPORT_NOTIFY) {
        struct metadata_operation *op = &t->metadata;
        if (!registers(pid, &op->returned)) return 1;
        if (t->phase == EXPORT_NOTIFY || (long)op->returned.regs[0] < 0)
            op->error = (int)op->returned.regs[0];
        /* A newer policy can complete the replay without notifying us,
         * even with ERRNO|0. Preserve that result and leave output alone. */
        if (t->phase == EXPORT_NOTIFY) op->copied = op->output_size;
        store_next(t); return 1;
    }
    return 0;
}

int fs_checkpoint(struct thread *t,uintptr_t checkpoint) {
    if (checkpoint == EXPORTED && (t->phase == EXPORTING || t->phase == PROC_OPENING)) {
        export_finish(t); return 1;
    }
    return 0;
}
