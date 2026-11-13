#define _GNU_SOURCE
#include "adapter_buffer.h"
#include "control_epoll.h"
#include "cooperative_channel.h"
#include "cooperative_native.h"
#include "fd_capture.h"
#include "fd_channel.h"
#include "fd_client.h"
#include "fd_install.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_return.h"
#include "file_open.h"
#include "file_snapshot.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "guest_domain.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "interception_program.h"
#include "linux_abi.h"
#include "namespace_broker.h"
#include "notification_result.h"
#include "private_memory.h"
#include "proc_file.h"
#include "proc_paths.h"
#include "process_image_view.h"
#include "resource_order.h"
#include "signal_delivery.h"
#include "signal_protocol.h"
#include "signal_view.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_proc.h"
#include "supervisor_readiness.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_registry.h"
#include "syscall_result.h"
#include "task_checkpoint.h"
#include "supervisor_transfer.h"
#include "task_copy.h"
#include "task_descriptor.h"
#include "task_helper.h"
#include "task_memory.h"
#include "task_stop.h"
#include <stdlib.h>
#include <sys/ioctl.h>
static struct seccomp_data notification_identity(struct thread *t,const struct seccomp_data *call);
static int notification_same_call(struct thread *t,const struct seccomp_data *a,const struct seccomp_data *b);
static void describe_notification_call(struct thread *t,const char *owner,uint64_t id,
        const struct seccomp_data *call);
static _Noreturn void notification_conflict(struct thread *t,const char *reason,
        const struct seccomp_notif *request,const struct md_broker_completion *incoming,int line);
static void metadata_restart_enter(struct thread *t);
static int metadata_instruction(struct thread *t,uintptr_t pc,uint32_t word);

void completed_event_discard(struct thread *t) {
    struct thread **event=&broker_events;
    while (*event && *event!=t) event=&(*event)->broker_next;
    if (*event) *event=t->broker_next;
    t->broker_next=NULL; t->broker_event.held=0;
    free(t->metadata_restart); t->metadata_restart=NULL;
    t->metadata_signal_restored=0;
    t->metadata_signal_requeued=0;
}

void buffer_admission_yield(struct thread *t) {
    if (!t || !t->fs.admission_pending) return;
    /* A shielded USER_NOTIF cannot take INTERRUPT until answered. Retirement
     * races delivery atomically; an already published operation is not replayed. */
    int sent=md_notification_retire(listener,t->fs.admission_id,MD_BUFFER_ADMIT_RETRY);
    if (sent<0) errno=-sent;
    CHECK(sent>=0);
    if (sent) ++t->fs.admission_yields;
    if (t->memory_op.notification && t->memory_op.notification->id==t->fs.admission_id) {
        free(t->memory_op.notification); t->memory_op.notification=NULL;
        if (!t->memory_op.resume) md_pending_cancel(&t->memory_op.continuation);
    }
    t->fs.admission_pending=0;
}

void notification_release(struct thread *t) {
    /* No operation was admitted. A signal or lazy-entry retry must discard
     * both metadata export and typed descriptor discovery before re-entry. */
    release_operation(t);
    md_proc_file_release(t->proc.file); t->proc.file=NULL;
}

int notification_unexecuted(const struct thread *t) {
    return t->phase==DELEGATE_CANCEL || (t->phase==EXPORT_CANCEL && t->metadata.previous==IDLE);
}

int notification_entry_context(struct thread *t,const struct user_pt_regs *regs,
        const struct seccomp_data *call) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!md_svc_registry_context(registry,regs->pc,regs->sp)) return 0;
    uintptr_t origin=md_svc_registry_call_origin(registry,call->instruction_pointer);
    return md_svc_registry_call_origin(registry,regs->pc)!=origin
        && md_svc_registry_call_origin(registry,regs->pc+4)!=origin;
}

void notification_interrupted(struct thread *t) {
    if (!notification_unexecuted(t)) return;
    /* A delivery stop can precede the requested INTERRUPT. Nothing has run:
     * retire the request before the handler can replace its register context.
     * The kernel retains EINTR/SA_RESTART and the pending interrupt is harmless
     * in IDLE. Never execute the cancelled call in a signal handler's frame. */
    CHECK(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&t->metadata.request.id)==-1 && errno==ENOENT);
    notification_release(t);
    t->phase=IDLE;
    if (t->metadata_restart) t->metadata_restart->stage=METADATA_ENTRY;
    SUPERVISOR_TRACE("PROBE notification interrupted pid=%d\n",t->pid);
}

static struct seccomp_data notification_identity(struct thread *t,const struct seccomp_data *call) {
    struct seccomp_data identity=*call;
    identity.instruction_pointer=md_svc_registry_call_origin(md_memory_svc(t->memory),call->instruction_pointer);
    return identity;
}

static int notification_same_call(struct thread *t,const struct seccomp_data *a,const struct seccomp_data *b) {
    if (md_notification_same_call(a,b)) return 1;
    struct seccomp_data left=notification_identity(t,a),right=notification_identity(t,b);
    return md_notification_same_call(&left,&right);
}

static void describe_notification_call(struct thread *t,const char *owner,uint64_t id,
        const struct seccomp_data *call) {
    struct seccomp_data identity=notification_identity(t,call);
    int valid=ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&id);
    int validation_error=valid<0 ? errno : 0;
    fprintf(stderr,"guest-runtime: notification %s id=%llu valid=%d validationErrno=%d nr=%d arch=%#x pc=%#llx origin=%#llx"
        " args=[%#llx,%#llx,%#llx,%#llx,%#llx,%#llx]\n",owner,(unsigned long long)id,
        valid==0,validation_error,call->nr,call->arch,(unsigned long long)call->instruction_pointer,
        (unsigned long long)identity.instruction_pointer,(unsigned long long)call->args[0],
        (unsigned long long)call->args[1],(unsigned long long)call->args[2],
        (unsigned long long)call->args[3],(unsigned long long)call->args[4],(unsigned long long)call->args[5]);
}

static _Noreturn void notification_conflict(struct thread *t,const char *reason,
        const struct seccomp_notif *request,const struct md_broker_completion *incoming,int line) {
    int error=errno;
    /* Cold, bounded evidence only. Never read guest memory or wait for the
     * worker lock while reporting a broken result-custody invariant. */
    fprintf(stderr,"guest-runtime: notification conflict reason=%s pid=%d tgid=%d phase=%d"
        " stopped=%d interrupt=%d event=%#x held=%d restart=%d errno=%d\n",reason,t->pid,t->tgid,
        t->phase,t->kernel_stopped,t->interrupt_pending,t->broker_event.status,t->broker_event.held,
        t->metadata_restart ? (int)t->metadata_restart->stage : -1,error);
    if (request) describe_notification_call(t,"incoming",request->id,&request->data);
    if (t->phase==DELEGATE_CANCEL || t->phase==EXPORT_CANCEL || t->phase==PROC_CANCEL)
        describe_notification_call(t,"admission",t->metadata.request.id,&t->metadata.request.data);
    const struct md_broker_completion *results[]={t->broker_completion,incoming};
    for (unsigned i=0;i<2;++i) if (results[i]) {
        const struct md_broker_completion *r=results[i];
        describe_notification_call(t,i ? "completion-incoming" : "completion",r->notification_id,&r->call);
        fprintf(stderr,"guest-runtime: notification result operation=%u flags=%#x live=%d error=%d fd=%d kind=%u\n",
            r->operation,r->flags,r->notification_live,r->result.error,r->opened.fd,r->opened.completion.kind);
    }
    md_broker_describe_task(broker,t->pid,STDERR_FILENO);
    errno=error; fail(reason,__FILE__,line);
}

void handle_notification(const struct seccomp_notif *request) {
    signal_entries_progress();
    if (ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&request->id)) {
        CHECK(errno==ENOENT); return;
    }
    int mapped=md_task_helper_notification(helpers,listener,request);
    if (mapped<0) errno=-mapped;
    CHECK(mapped>=0);
    if (mapped) return;
    struct seccomp_notif q = *request;
    struct thread *t = find_thread((pid_t)q.pid);
    CHECK(t && t->born);
    /* Notification validation precedes the per-task entry acknowledgement.
     * The handler may commit after the event loop's earlier global scan. */
    signal_entry_committed(t);
    if (t->signals.control && t->signals.control->returning) signal_control_discard(t);
    int replay=t->ready && q.data.instruction_pointer==abi.buffer_admit
        && (q.data.nr==SYS_epoll_pwait || q.data.nr==SYS_epoll_pwait2 || q.data.nr==SYS_epoll_ctl);
    CHECK(md_broker_filesystem_call(q.data.nr) || q.data.nr==SYS_prctl || q.data.nr==SYS_gettid
        || q.data.nr==SYS_rt_sigprocmask || replay);
    unsigned purpose=replay ? readiness_admission_purpose(t,&q.data) : q.data.args[0];
    int buffer_admission=replay || (q.data.nr==SYS_gettid && t->ready
        && q.data.instruction_pointer==abi.buffer_admit
        && !q.data.args[1] && !q.data.args[2] && !q.data.args[3]
        && !q.data.args[4] && !q.data.args[5]);
    if (buffer_admission) {
        t->fs.admission_id=q.id; t->fs.admission_pending=1;
        if (buffer_admission_quiescing(t->pid)) { buffer_admission_yield(t); return; }
    }
    if (mapping_notification(t,&q)) return;
    if (filter_listener_notification(t,&q)) return;
    if (guest_notification_kernel(t,&q)) return;
    if (buffer_admission && purpose==MD_ENTRY_BIND && (t->broker_completion
            || t->phase==DELEGATE_CANCEL || t->phase==EXPORT_CANCEL || t->phase==PROC_CANCEL)) {
        /* A cancelled SVC can restart through a newly published lazy entry.
         * Its channel registration is not the original call's replay. Decline
         * only registration so the entry unwinds before result delivery. */
        struct seccomp_notif_resp reply={.id=q.id,.error=-EAGAIN};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        return;
    }
    if (t->proc.snapshot) {
        if (md_notification_same_call(&t->proc.snapshot_notification.data,&q.data)) {
            CHECK(!md_notification_rebind(listener,&t->proc.snapshot_notification,&q));
            return;
        }
        md_fs_buffer_cancel(&t->proc.snapshot);
    }
    if (t->phase==DELEGATE_CANCEL || t->phase==EXPORT_CANCEL || t->phase==PROC_CANCEL) {
        /* A process-directed signal can cancel USER_NOTIF yet be consumed by
         * a peer. The kernel restarts this SVC without a delivery stop here.
         * The pending INTERRUPT still owns admission; replace only its token,
         * never start another export or replay a filesystem operation. */
        struct seccomp_notif previous=t->metadata.request,next=q;
        previous.data=notification_identity(t,&previous.data);
        next.data=notification_identity(t,&next.data);
        int error=md_notification_rebind(listener,&previous,&next);
        if (error) {
            errno=-error;
            notification_conflict(t,"notification-rebind",&q,NULL,__LINE__);
        }
        t->metadata.request=q;
        if (statistics) ++statistics->notification_rebounds;
        SUPERVISOR_TRACE("PROBE notification rebound pid=%d phase=%d nr=%d\n",t->pid,t->phase,q.data.nr);
        return;
    }
    if (t->broker_completion) {
        const struct seccomp_data *call=&t->broker_completion->call;
        if (t->phase!=IDLE || !notification_same_call(t,&q.data,call))
            notification_conflict(t,"completed-notification-reentry",&q,NULL,__LINE__);
        /* Notification waits can restart without a delivery stop in this
         * thread (another peer may consume a process-directed signal). The
         * retained result, not this replay, still owns the completed open. */
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"completed-restarted-notification");
        return;
    }
    if (!task_continuation_available(t) || (entry_fence_adapt(t) && md_broker_filesystem_call(q.data.nr)
            && !(q.data.instruction_pointer>=checkpoints.begin && q.data.instruction_pointer<checkpoints.end))) {
        if (!t->memory_op.notification) { t->memory_op.notification=malloc(sizeof(*request)); CHECK(t->memory_op.notification); }
        else CHECK(t->memory_op.notification->id==request->id
            || (ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&t->memory_op.notification->id)<0 && errno==ENOENT));
        *t->memory_op.notification=*request; task_continuation_defer(t); return;
    }
    if (!buffer_admission && guest_trace_rejected(t,&q.data)) {
        struct seccomp_notif_resp reply={.id=q.id,.error=-ENOSYS};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        return;
    }
    if (!buffer_admission && guest_notification_begin(t,&q.data,NULL,&q,NULL)) return;
    if (q.data.nr==SYS_rt_sigprocmask) {
        CHECK(!q.data.args[1]);
        if (!t->signals.host || (!t->metadata_restart && !t->signals.host_blocked && !t->signals.delivery.active) || !q.data.args[2]
                || q.data.args[3]!=sizeof(uint64_t)) {
            struct seccomp_notif_resp reply={.id=q.id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        } else {
            /* A replay is admitted under a temporary shield. Queries must
             * expose the interrupted application's mask, not that shield. */
            uint64_t mask=t->metadata_restart
                ? md_signal_view_mask(t->metadata_restart->mask,t->signals.host_blocked) : t->signals.delivery.active
                ? md_signal_view_mask(t->signals.delivery.mask,t->signals.host_blocked) : t->signals.handler_mask;
            ssize_t copied=t->metadata_restart || t->signals.delivery.active || t->signals.handler_mask_known
                ? md_task_copy_direct(t->pid,q.data.args[2],&mask,sizeof(mask),1) : -EACCES;
            if (copied>=0 || copied==-EFAULT) {
                struct seccomp_notif_resp reply={.id=q.id,
                    .error=copied==sizeof(t->signals.handler_mask) ? 0 : -EFAULT};
                CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
            } else delegate_begin(t,&q);
        }
        return;
    }
    /* A restarted notification can precede the cancelled carrier's queue
     * wakeup. No guest FD was published; release that exact old admission. */
    if (t->phase==IDLE && md_svc_registry_direct_at(md_memory_svc(t->memory),
            t->pid,q.data.instruction_pointer,0)) {
        delegate_begin(t,&q); return;
    }
    if (t->proc.delivery.files.table && t->proc.delivery.notification!=q.id) proc_deliver(t);
    if (q.data.nr==SYS_gettid || replay) {
        int value=-EPERM;
        if (buffer_admission) {
            if (purpose==MD_ENTRY_BIND) {
                long address=cooperative_bind(t);
                SUPERVISOR_TRACE("PROBE entry-bind pid=%d result=%ld phase=%d blocked=%d filter=%d\n",
                    t->pid,address,t->phase,t->entry.blocked,t->seccomp.application_filter);
                struct seccomp_notif_resp reply={.id=q.id,
                    .val=address<0 ? 0 : address,.error=address<0 ? (int)address : 0};
                CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
                return;
            } else if (purpose==MD_FS_BUFFER_IMPORT)
                value=md_fd_return_install(&t->fs.returned,listener,q.id);
            else if (purpose==MD_FS_BUFFER_EXPORT)
                value=md_fs_buffer_export_install(t->fs.call,listener,q.id);
            else if (purpose==MD_FS_BUFFER_IPC_ADMIT) {
                struct md_fd_capture *capture=NULL;
                struct md_fd_order *order=NULL;
                if (t->io.outgoing && t->io.outgoing->brokered && t->io.outgoing->stage==OUTGOING_CAPTURE) {
                    capture=&t->io.outgoing->capture; order=&t->io.outgoing->files;
                } else if (t->io.receive && t->io.receive->brokered && !t->io.receive->ready) {
                    capture=&t->io.receive->capture; order=&t->io.receive->files;
                }
                if (order && md_fd_order_state(order)==MD_RESOURCE_ORDER_RUNNING)
                    value=md_fd_capture_admit(capture,order->table,listener,q.id);
            } else if (purpose==MD_BUFFER_PIDFD_ADMIT && t->descriptors.pidfd_capture.count
                    && t->descriptors.job==MD_FD_USE && md_fd_order_state(&t->descriptors.order)==MD_RESOURCE_ORDER_RUNNING)
                value=md_fd_capture_admit(&t->descriptors.pidfd_capture,t->resources.files,listener,q.id);
            else if (purpose==MD_BUFFER_CONTROL_CAPTURE_ADMIT && t->control.capture.count
                    && t->fs.descriptor_operation==MD_BUFFER_CONTROL_CAPTURE
                    && md_fd_order_state(&t->fs.descriptor_order)==MD_RESOURCE_ORDER_RUNNING)
                value=md_fd_capture_admit(&t->control.capture,t->resources.files,listener,q.id);
            else if (purpose==MD_FS_BUFFER_EPOLL_ADMIT && t->readiness
                    && md_fd_order_state(&t->readiness->files)==MD_RESOURCE_ORDER_RUNNING) {
                value=md_fd_capture_admit(&t->readiness->capture,t->readiness->files.table,listener,q.id);
            } else if (purpose==MD_FS_BUFFER_EPOLL_LOAN && t->readiness
                    && t->readiness->loans[t->readiness->lending].retirement
                    && t->readiness->loans[t->readiness->lending].number<0) {
                struct readiness_owner *o=t->readiness;
                struct readiness_loan *loan=&o->loans[o->lending];
                int source=o->lending==READINESS_NATIVE_QUEUE ? o->received->fds[0]
                    : md_control_epoll_task_descriptor(md_control_epoll_from_object(o->queue));
                struct md_fd_object *object=o->lending==READINESS_NATIVE_QUEUE ? o->queue : NULL;
                value=o->files.table ? md_fd_install_borrow(&loan->delivery,&o->files,listener,q.id,source,object,O_CLOEXEC)
                    : md_fd_install_begin(&loan->delivery,t->resources.files,listener,q.id,source,object,O_CLOEXEC,fd_wakeup);
                if (!value) value=md_fd_install_loan(&loan->delivery,loan->retirement->loan);
                if (!value) { readiness_deliver(t); return; }
            }
        }
        if (value<0) {
            struct seccomp_notif_resp reply={.id=q.id,.error=value};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        }
        return;
    }
    if (q.data.nr == SYS_prctl) {
        if (q.data.args[0] == MD_GUEST_PROC_IMAGE) {
            struct seccomp_notif_resp reply={.id=q.id};
            struct thread *target=find_thread((pid_t)q.data.args[1]);
            struct proc_owner owner=proc_owner((pid_t)q.data.args[1]);
            const struct md_private_memory_access *access=target ? md_memory_private_access(target->memory) : NULL;
            /* Cmdline is public, unlike auxv/exe: differing guest IDs or
             * permitted capabilities do not require ptrace authority. Only
             * the admitted guest process can supply this retained image. */
            int allowed=!md_domain_restricted(t->resources.fs)
                && (q.data.args[2]==MD_PROC_CMDLINE || (target && proc_allowed(t->pid,target->pid)));
            int error=!owner.identity ? -ENOENT : !allowed ? -EACCES
                : q.data.instruction_pointer!=RAW_GATE || t->phase!=DISPATCHING
                    || t->proc.image_fd>=0 || q.data.args[4] || q.data.args[5] ? -EPERM
                : md_file_snapshot_flags((unsigned)q.data.args[3]);
            void *bytes=NULL;
            ssize_t size=error ? error : md_process_image_snapshot((pid_t)q.data.args[1],target ? target->image.process_image : 0,
                access ? access->memory : -1,(unsigned)q.data.args[2],&bytes);
            if (size<0) error=(int)size;
            else error=md_fs_buffer_snapshot_copy(buffer_service,t->pid,t->resources.fs,
                (unsigned)q.data.args[3],bytes,(size_t)size,&t->proc.snapshot);
            free(bytes);
            if (!error) {
                t->proc.snapshot_notification=q;
                /* EVENT_WAIT: owned snapshot completion, then destination FD
                 * publication. Task cancellation retires the exact request. */
                return;
            }
            reply.error=error;
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        } else if (q.data.args[0] == MD_GUEST_FD_CHANNEL) {
            struct metadata_operation *op=&t->metadata;
            int value=-EPERM;
            if (q.data.instruction_pointer==RAW_GATE
                    && (t->phase==EXPORTING || t->phase==PROC_OPENING)
                    && op->transport && op->capture.channel && op->channel<0 && !op->checking
                    && md_fd_order_state(&op->files)==MD_RESOURCE_ORDER_RUNNING
                    && !q.data.args[1] && !q.data.args[2] && !q.data.args[3]
                    && !q.data.args[4] && !q.data.args[5]) {
                value=md_fd_channel_admit(op->transport,op->files.table,listener,q.id);
                if (value>=0) { op->channel=value; return; }
            }
            struct seccomp_notif_resp reply={.id=q.id,.error=value};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        } else if (q.data.args[0] == MD_GUEST_BUFFER) {
            buffer_setup(t,&q);
        }
        else if (q.data.args[0]==MD_GUEST_SHM || q.data.args[0]==MD_GUEST_IPC
                || q.data.args[0]==MD_GUEST_PID_SIGNAL_GROUP) resource_request(t,&q);
        else image_request(t, &q);
        return;
    }
    if (q.data.nr==SYS_getdents64 && t->phase==IDLE && q.data.instruction_pointer!=RAW_GATE) {
        struct md_proc_file *file=proc_file_capture(t,(int)q.data.args[0]);
        if (file) { CHECK(!t->proc.file); t->proc.file=file; delegate_begin(t,&q); return; }
    }
    if (q.data.nr == SYS_getdents64 && md_domain_restricted(t->resources.fs)) {
        CHECK(t->phase == IDLE);
        struct seccomp_notif_resp reply = {.id = q.id, .flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE};
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT); return;
    }
    if (t->phase == EXPORT_NOTIFY) {
        struct metadata_operation *op = &t->metadata;
        CHECK(q.id != op->request.id && !memcmp(&q.data, &op->request.data, sizeof(q.data)));
        if (q.data.nr != SYS_fstat) { retained_result(t, &q); return; }
        struct seccomp_notif_resp reply = {.id = q.id, .error = op->error};
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply));
        t->phase = EXPORT_RETURN; return;
    }
    if (q.data.nr != SYS_fstat) {
        if (t->phase != IDLE) fprintf(stderr,
            "Shroot: unexpected notification pid=%d phase=%d nr=%d pc=%#llx fd=%llu resume=%d signal=%d packet=%d pending=%d\n",
            t->pid,t->phase,q.data.nr,q.data.instruction_pointer,q.data.args[0],
            t->kernel_resume,t->signals.control ? t->signals.control->signal : 0,
            t->signals.control && t->signals.control->entry_packet,
            t->signals.control && t->signals.control->entry_packet
                ? (int)atomic_load_explicit(&t->signals.control->entry_packet->pending,memory_order_acquire) : -1);
        CHECK(t->phase == IDLE);
        /* Retained native-root export below only understands openat/statat.
         * Other reads keep the same task-affine adapter as their TRACE route. */
        if ((q.data.nr!=SYS_openat && q.data.nr!=SYS_newfstatat && q.data.nr!=SYS_readlinkat)
                || !md_domain_restricted(t->resources.fs)) {
            delegate_begin(t, &q); return;
        }
        if (q.data.nr==SYS_readlinkat) {
            struct seccomp_notif_resp reply={.id=q.id,.error=md_domain_path_error(t->resources.fs)};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT); return;
        }
        if ((int)q.data.args[0] < 0) {
            struct seccomp_notif_resp reply = {.id = q.id,
                .error = (int)q.data.args[0] == AT_FDCWD ? md_domain_path_error(t->resources.fs) : -EBADF};
            CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT); return;
        }
        export_begin(t, &q); return;
    }
    if (t->phase==IDLE && q.data.instruction_pointer!=RAW_GATE) {
        if (t->endpoint[0]) { delegate_begin(t,&q); return; }
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)q.data.args[0],NULL);
        /* Native FD identity alone cannot recover a logical path or provider.
         * Discovery only chooses the adapter; it captures authority under the
         * table turn, including descriptors received after fork. */
        int tracked=object!=NULL; md_fd_object_release(object);
        if (tracked) { delegate_begin(t,&q); return; }
        struct md_proc_file *file=proc_file_capture(t,(int)q.data.args[0]);
        if (file) { CHECK(!t->proc.file); t->proc.file=file; delegate_begin(t,&q); return; }
    }
    int fd = duplicate_fd(q.pid, (int)q.data.args[0]);
    if (md_task_descriptor_needs_export(fd) || fd == -ESRCH) {
        if (t->phase == IDLE && !md_domain_restricted(t->resources.fs)) {
            delegate_begin(t, &q); return;
        }
        export_begin(t, &q); return;
    }
    int error = fd < 0 ? fd : 0;
    struct stat st;
    if (!error) {
        error = fstat(fd, &st) ? -errno : 0;
        close(fd);
    }
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id)) { CHECK(errno == ENOENT); return; }
    if (!error) {
        struct iovec local = {&st, sizeof(st)}, remote = {(void *)q.data.args[1], sizeof(st)};
        ssize_t n = process_vm_writev(q.pid, &local, 1, &remote, 1, 0);
        if (n != sizeof(st)) error = n < 0 ? -errno : -EFAULT;
    }
    external_stats++;
    if (error && ++external_errors <= 8)
        SUPERVISOR_TRACE("PROBE external fstat pid=%u fd=%llu result=%d\n", q.pid, q.data.args[0], error);
    struct seccomp_notif_resp reply = {.id = q.id, .error = error};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}

void delegated_notification(const struct seccomp_notif *request) {
    handle_notification(request);
}

void completed_notification(pid_t pid,struct md_broker_completion *result) {
    struct thread *t=find_thread(pid);
    CHECK(t);
    if (t->broker_completion) notification_conflict(t,"duplicate-completion",NULL,result,__LINE__);
    t->broker_completion=malloc(sizeof(*result)); CHECK(t->broker_completion);
    *t->broker_completion=*result;
    t->broker_completion->call=notification_identity(t,&result->call);
    SUPERVISOR_TRACE("PROBE completed-notification pid=%d nr=%d result=%d fd=%d live=%d\n",pid,result->call.nr,
        result->result.error,result->opened.fd,result->notification_live);
    result->opened=(struct md_fs_buffer_open){.fd=-1};
    if (t->broker_completion->notification_live) {
        /* A provider may require selectors, caller opening or FIFO completion.
         * Interrupt only the still-live notification. Cancellation returns via
         * a kernel event or a restarted notification retaining this result. */
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"completed-notification");
    }
}

int completed_event_resume(struct thread *t) {
    if (!t->broker_event.held) return 0;
    if (t->metadata_restart) {
        if (!signal_mask(t,t->metadata_restart->mask,"metadata-restart-mask")) return 1;
        t->metadata_signal_requeued=t->metadata_restart->requeued_signal;
        free(t->metadata_restart); t->metadata_restart=NULL;
    }
    if (t->broker_event.has_info) {
        /* A completed adapter owns an interrupt checkpoint, not the original
         * signal-delivery stop. Publish through a real delivery stop instead
         * of injecting a signal at a ptrace event (which the kernel ignores). */
        t->broker_event.held=0; t->phase=IDLE;
        resume(t->pid,0); return 1;
    }
    t->metadata_signal_restored=1;
    t->phase=IDLE; t->broker_next=broker_events; broker_events=t;
    return 1;
}

void metadata_restart_return(struct thread *t,long value) {
    struct metadata_restart *r=t->metadata_restart;
    CHECK(r && r->stage==METADATA_WAIT);
    /* arm64 exposes a temporary entry/exit marker in x7 at syscall stops.
     * Only x0 is the result; retain the original registers, NZCV and args. */
    r->saved.pc+=4; r->saved.regs[0]=(unsigned long)value;
    /* Both native exit and the metadata copy's committed checkpoint already
     * own a complete result. Restore here, before any queued policy signal;
     * another service instruction would needlessly borrow the mask again. */
    if (!set_registers(t->pid,&r->saved)) return;
    t->phase=IDLE;
    CHECK(completed_event_resume(t));
}

static void metadata_restart_enter(struct thread *t) {
    struct metadata_restart *r=t->metadata_restart;
    struct user_pt_regs regs=r->saved;
    /* Use the stable gate even if the original page now contains a lazy-entry
     * branch. The syscall-entry stop restores its PC before kernel seccomp. */
    regs.pc=RAW_GATE-4; r->stage=METADATA_ENTRY;
    if (set_registers(t->pid,&regs)) resume(t->pid,0);
}

static int metadata_instruction(struct thread *t,uintptr_t pc,uint32_t word) {
    word=md_svc_registry_instruction(md_memory_svc(t->memory),pc,word);
    return (word&0xffe0001f)==0xd4000001;
}

static void metadata_instruction_complete(struct thread *t,int error) {
    struct metadata_restart *r=t->metadata_restart;
    if (error || !metadata_instruction(t,r->saved.pc,r->instruction)) {
        CHECK(completed_event_resume(t)); return;
    }
    metadata_restart_enter(t);
}

int interrupted_metadata_event(struct thread *t,int status,const struct rusage *usage,
        struct md_task_signal_stop *stop) {
    if (t->metadata_signal_restored) { t->metadata_signal_restored=0; return 0; }
    if (t->metadata_signal_requeued && (unsigned)status>>16!=PTRACE_EVENT_STOP) {
        /* Blocked job-control signals return through the kernel, not our
         * carrier queue. Their first delivery precedes guest execution and
         * must not repeat a completed instruction check. An intervening real
         * event retires this handoff; STOP wake acknowledgements do not. */
        int restored=WIFSTOPPED(status) && !((unsigned)status>>16)
            && WSTOPSIG(status)==t->metadata_signal_requeued;
        t->metadata_signal_requeued=0;
        if (restored) return 0;
    }
    if (WIFSTOPPED(status) && !((unsigned)status>>16) && WSTOPSIG(status)!=(SIGTRAP|0x80))
        notification_interrupted(t);
    if (t->phase!=IDLE || t->broker_event.held || !WIFSTOPPED(status)
            || !t->ready || !t->entered || (unsigned)status>>16 || WSTOPSIG(status)==(SIGTRAP|0x80)
            || WSTOPSIG(status)==SIGSTOP) return 0;
    if (!signal_observe(t,stop,0)) return 1;
    siginfo_t info=stop->info;
    if (md_signal_synchronous(&info)) return 0;
    if (!signal_observe(t,stop,1)) return 1;
    struct user_pt_regs regs=stop->regs;
    /* Only pure mask queries use metadata notification/replay. A mask update
     * owns its native exit and must not inherit a second completion owner. */
    if (!md_broker_metadata_call((int)regs.regs[8])
            && !(t->signals.host && regs.regs[8]==SYS_rt_sigprocmask && !regs.regs[1])) return 0;
    struct md_svc_site *site=md_svc_registry_direct_at(md_memory_svc(t->memory),t->pid,regs.pc,regs.sp);
    if (site && regs.pc!=site->kernel_alias) return 0;
    /* A notified read can also originate at a resident native alias. Its
     * cancellation must retain the same metadata replay as the original SVC;
     * it is not an application's interruptible native wait. */
    if (site) regs.pc=site->direct.svc;
    uint32_t instruction=UINT32_C(0xd4000001);
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    ssize_t copied=site ? sizeof(instruction) : md_task_read_memory(t->pid,regs.pc,
        &instruction,sizeof(instruction),access ? access->memory : -1);
    if (copied==sizeof(instruction) && !metadata_instruction(t,regs.pc,instruction)) return 0;
    /* arm64 rewinds the SVC before the signal-delivery stop. Reception is not
     * proof of admission: cancellation can precede NOTIF_RECV altogether.
     * Verify the instruction (or our exact replacement), then replay with its
     * original identity. Newer application filters remain authoritative. */
    struct metadata_restart *restart=calloc(1,sizeof(*restart)); CHECK(restart);
    restart->saved=regs; restart->stage=METADATA_ENTRY;
    t->metadata_restart=restart;
    t->kernel_stopped=1; t->interrupt_pending=0;
    t->broker_event.held=1; t->broker_event.status=status;
    t->broker_event.usage=usage ? *usage : (struct rusage){0};
    t->broker_event.has_info=1; t->broker_event.info=info;
    if (!save_signal_mask(t,&restart->mask,"metadata-signal-mask")
            || !shield(t)) return 1;
    t->signals.retained=md_signal_delivery_retain(&t->signals.delivery,t->pid,&info);
    CHECK(t->signals.retained>=0);
    restart->requeued_signal=t->signals.retained;
    if (copied!=sizeof(instruction))
        transfer_begin(t,&regs,regs.pc,&restart->instruction,sizeof(restart->instruction),
            MD_TRANSFER_READ,metadata_instruction_complete);
    else metadata_restart_enter(t);
    return 1;
}

int metadata_restart_event(struct thread *t,int status) {
    struct metadata_restart *r=t->metadata_restart;
    if (!r || !WIFSTOPPED(status)
            || ((unsigned)status>>16 && (unsigned)status>>16!=MD_TASK_CHECKPOINT_EVENT)) return 0;
    int sig=WSTOPSIG(status);
    if (!((unsigned)status>>16) && sig!=(SIGTRAP|0x80) && sig!=SIGSTOP) {
        siginfo_t info;
        if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"metadata-deferred-signal")) return 1;
        if (!md_signal_synchronous(&info)) {
            int retained=md_signal_delivery_retain(&t->signals.delivery,t->pid,&info); CHECK(retained>=0);
            notification_interrupted(t);
            resume(t->pid,retained); return 1;
        }
    }
    if (t->phase!=IDLE) return 0;
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    t->kernel_stopped=1; t->interrupt_pending=0;
    if (sig==(SIGTRAP|0x80) && (r->stage==METADATA_ENTRY || r->stage==METADATA_WAIT)) {
        struct ptrace_syscall_info info;
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"metadata-restart-boundary")) return 1;
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
            if (r->stage!=METADATA_ENTRY || info.entry.nr!=r->saved.regs[8]
                    || info.instruction_pointer!=RAW_GATE
                    || memcmp(info.entry.args,r->saved.regs,sizeof(info.entry.args)))
                fprintf(stderr,"metadata entry mismatch pid=%d stage=%d nr=%llu/%llu pc=%#llx/%#llx\n",
                    t->pid,r->stage,(unsigned long long)info.entry.nr,r->saved.regs[8],
                    (unsigned long long)info.instruction_pointer,(unsigned long long)RAW_GATE);
            CHECK(r->stage==METADATA_ENTRY && info.entry.nr==r->saved.regs[8]
                && info.instruction_pointer==RAW_GATE
                && !memcmp(info.entry.args,r->saved.regs,sizeof(info.entry.args)));
            regs=r->saved; regs.pc+=4;
            /* This is original-site kernel admission, not a service call.
             * A shield must not turn a caught seccomp TRAP into forced death.
             * Preserve its native SIGSYS bit until the exact syscall exit. */
            uint64_t mask=(md_signal_blockable_mask()&~MD_SIGNAL_SYS_BIT)|(r->mask&MD_SIGNAL_SYS_BIT);
            if (!signal_mask(t,mask,"metadata-admission-mask")) return 1;
            t->signals.shielded=1;
            r->stage=METADATA_WAIT;
            if (set_registers(t->pid,&regs)) resume(t->pid,0);
            return 1;
        }
        CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT && r->stage==METADATA_WAIT);
        if (md_syscall_restarting(info.exit.rval)) {
            /* SIGSTOP and internal ptrace interruptions can still restart the
             * shielded read. Never expose a kernel restart code to userspace. */
            metadata_restart_enter(t);
            return 1;
        }
        metadata_restart_return(t,(long)regs.regs[0]);
        return 1;
    }
    return 0;
}

int completed_notification_event(struct thread *t,int status,const struct rusage *usage) {
    struct md_broker_completion *result=t->broker_completion;
    if (!result) return 0;
    SUPERVISOR_TRACE("PROBE completed-event pid=%d nr=%d status=%#x\n",t->pid,result->call.nr,status);
    unsigned event=(unsigned)status>>16;
    if (WIFSTOPPED(status) && event!=PTRACE_EVENT_EXIT && event!=PTRACE_EVENT_EXEC) {
        int signal=WSTOPSIG(status);
        if (!event && signal==SIGSTOP) return 0;
        /* A group stop retains the result until SIGCONT; its LISTEN boundary
         * cannot be replaced by an internal adapter trap. */
        if (event==PTRACE_EVENT_STOP && signal!=SIGTRAP) return 0;
        struct user_pt_regs regs;
        if (!registers(t->pid,&regs)) return 1;
        if (notification_entry_context(t,&regs,&result->call)) {
            /* Internal entry work must finish on its own stack. Its ordinary
             * stop owner drains the entry; the retained result waits for the
             * original syscall/alias, never overwriting a private continuation. */
            return 0;
        }
        if (notification_unexecuted(t)) {
            /* A restarted notification may request admission before the
             * original worker publishes its retained result. Its pending stop
             * has executed nothing; retire only that exact duplicate request
             * and use this stop for the completed operation, never replay it. */
            if (!notification_same_call(t,&t->metadata.request.data,&result->call))
                notification_conflict(t,"completed-notification-stop",&t->metadata.request,NULL,__LINE__);
            notification_interrupted(t);
            if (statistics) ++statistics->notification_readmissions_retired;
        }
        if (!event && signal!=(SIGTRAP|0x80)
                && (result->opened.completion.kind==MD_OPEN_PIPE || result->opened.completion.kind==MD_OPEN_FIFO)) {
            /* No open endpoint was completed. Preserve the kernel's original
             * interrupted syscall frame and restart decision for this signal. */
            md_fs_buffer_open_release(&result->opened);
            md_broker_completion_taken(broker,t->pid);
            free(result); t->broker_completion=NULL;
            return 0;
        }
        regs.pc=result->call.instruction_pointer; regs.regs[8]=result->call.nr;
        for (unsigned i=0;i<6;++i) regs.regs[i]=result->call.args[i];
        if (result->opened.fd>=0) {
            if (t->phase!=IDLE || t->fs.buffer.pool || t->broker_event.held)
                notification_conflict(t,"completed-notification-adoption",NULL,NULL,__LINE__);
            execution_capture(t,&regs); t->kernel_stopped=1; t->interrupt_pending=0;
            t->broker_event.held=1; t->broker_event.status=status;
            t->broker_event.usage=usage ? *usage : (struct rusage){0};
            t->broker_event.has_info=!event && signal!=(SIGTRAP|0x80);
            if (t->broker_event.has_info
                    && !tracee_request(t,PTRACE_GETSIGINFO,NULL,&t->broker_event.info,"completed-signal")) return 1;
            int error=md_buffers_take(md_memory_buffers(t->memory),&t->fs.buffer);
            if (!error) error=md_fs_buffer_adopt(buffer_service,t->pid,t->resources.fs,result->operation,result->flags,
                &t->fs.buffer,&result->result,&result->opened,&t->fs.call);
            if (!error) {
                /* Transfer signal custody only after result admission. On
                 * failure the original delivery stop still owns the signal. */
                if (t->broker_event.has_info) {
                    t->signals.retained=md_signal_delivery_retain(&t->signals.delivery,t->pid,&t->broker_event.info);
                    CHECK(t->signals.retained>=0);
                }
                t->fs.direct=DIRECT_OPEN; t->phase=DISPATCHING;
                t->fs.direct_flags=result->flags; t->fs.direct_mode=0;
                buffer_capture(t,&t->execution.registers); t->fs.request.args[0]=result->operation;
                md_broker_completion_taken(broker,t->pid);
                free(result); t->broker_completion=NULL;
                return 1;
            }
            CHECK(!buffer_release(t));
            t->broker_event.held=0; regs.regs[0]=error;
        } else regs.regs[0]=result->result.error;
        if (!set_registers(t->pid,&regs)) return 1;
    }
    md_fs_buffer_open_release(&result->opened);
    md_broker_completion_taken(broker,t->pid);
    free(result); t->broker_completion=NULL;
    return 0;
}
