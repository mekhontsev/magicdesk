#define _GNU_SOURCE
#include "launch_registry.h"
#include <linux/netlink.h>
#include "adapter_buffer.h"
#include "control_file.h"
#include "cooperative_native.h"
#include "device_events.h"
#include "epoll_calls.h"
#include "epoll_wait.h"
#include "event_wait.h"
#include "fd_capture.h"
#include "fd_channel.h"
#include "fd_messages.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_return.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "fs_engine.h"
#include "fs_worker.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "identity_call.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "interception_program.h"
#include "ipc_credentials.h"
#include "ipc_io.h"
#include "ipc_io_host.h"
#include "ipc_io_job.h"
#include "ipc_namespace.h"
#include "ipc_origins.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "network_endpoint.h"
#include "network_io.h"
#include "network_namespace.h"
#include "network_packet.h"
#include "pending.h"
#include "pid_namespace.h"
#include "proc_file.h"
#include "received_fds.h"
#include "resource_order.h"
#include "socket_payload.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_notifications.h"
#include "supervisor_readiness.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "syscall_result.h"
#include "sysv_ipc.h"
#include "sysv_ipc_internal.h"
#include "sysv_shm.h"
#include "task_helper.h"
#include "task_outgoing.h"
#include "task_receive.h"
#include "task_registry.h"
#include "task_send.h"
#include <stdlib.h>
#include <sys/ioctl.h>

/* Cold, authenticated-origin delivery only. The receive owner retains this
 * storage until the shared kernel receipt completes origin publication. */
struct receive_publication {
    struct md_fd_object *origins[253];
    struct md_fd_origin_update updates[253];
    int numbers[253];
};
static uint64_t ipc_sequence;

static long shm_control(struct thread *t,unsigned op,const unsigned long a[4]);
static long ipc_control(struct thread *t,unsigned operation,unsigned long argument);
static int ipc_watch(struct thread *t);
static void ipc_wait_shared(struct thread *t,long value);
static struct user_pt_regs wait_poll_registers(const struct thread *t,enum phase phase);
static void control_deliver(struct thread *t,long result);
static void outgoing_complete(void *context, struct md_task_helper_result result);
static void outgoing_prepared(struct md_ipc_origin_request *request, int error, void *context);
static void outgoing_exported(void *context, struct md_task_helper_result result);
static void outgoing_captured(void *context);
static int reusable_io_channel(struct thread *t);
static void outgoing_begin(struct thread *t, const struct user_pt_regs *regs);
static void outgoing_send(struct thread *t, const struct user_pt_regs *regs);
static void receive_captured(void *context);
static void receive_complete(void *context, struct md_task_helper_result result);
static void ipc_capture_step(struct thread *t, unsigned command, long value);
static struct md_ipc_io *socket_operation(struct thread *t);
static void socket_complete(struct thread *t, long result);
static void operation_park(struct thread *t,struct user_pt_regs regs,int64_t deadline);
static void socket_io_progress(struct thread *t);
static void receive_begin(struct thread *t, const struct user_pt_regs *regs, unsigned command);
static int receive_return_begin(struct thread *t, unsigned count, int annotated);
static int receive_return(struct thread *t, const struct md_received_fds *source, const int *indices, unsigned count);
static enum call_restart control_wait_restart(const struct thread *t);
static void control_interrupted(struct thread *t,long value);
static void socket_wait_result(struct thread *t,long value,int restart,int waking);

static void io_return_context(struct thread *t,int brokered,const struct user_pt_regs *continuation) {
    if (!t->entry.control) {
        if (!brokered) t->kernel_stopped=1;
        buffer_capture(t,continuation);
    }
}

static void control_wait_shared(struct thread *t,long value) {
    if (value==-ETIMEDOUT && t->control.pending) control_progress(t);
    else control_interrupted(t,value);
}

void receive_release(struct receive_owner *owner) {
    if (!owner) return;
    if (owner->io_waiting && owner->received)
        epoll_ctl(socket_events,EPOLL_CTL_DEL,md_ipc_io_event(&owner->io,owner->received->fds[0]),NULL);
    if (owner->network.number) md_network_io_release(&owner->network,&owner->io);
    md_ipc_io_release(&owner->io);
    md_fd_capture_cancel(&owner->capture);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    free(owner->publication);
    md_received_fds_release(owner->expanded);
    md_received_fds_release(owner->received); free(owner);
}

void receive_discard(struct thread *t) {
    if (!t->io.receive) return;
    struct receive_owner *owner=t->io.receive; t->io.receive=NULL; owner->caller=0;
    if (!owner->brokered && owner->started && !owner->ready)
        CHECK(!md_task_helper_detach(helpers,t->pid));
    else receive_release(owner);
}

void outgoing_discard(struct thread *t) {
    if (!t->io.outgoing) return;
    struct outgoing_owner *owner=t->io.outgoing; t->io.outgoing=NULL; owner->caller=0;
    if (!owner->brokered && (owner->stage==OUTGOING_CAPTURE || owner->stage==OUTGOING_SEND))
        CHECK(!md_task_helper_detach(helpers,t->pid));
    else outgoing_release(owner);
}

void outgoing_release(struct outgoing_owner *owner) {
    if (!owner) return;
    if (owner->io_waiting && owner->prepared)
        epoll_ctl(socket_events,EPOLL_CTL_DEL,owner->prepared->fds[0],NULL);
    md_ipc_io_release(&owner->io);
    /* A terminal send without its result can already have published rights.
     * Known completion ends this reservation before reaching cleanup. */
    if (owner->stage==OUTGOING_SEND) md_fd_message_export_end(owner->message,1);
    md_fd_capture_cancel(&owner->capture);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    md_ipc_origins_cancel(&owner->preparation);
    if (owner->fs_order.fs && md_fs_order_cancel(&owner->fs_order)==1)
        CHECK(!md_fs_order_complete(&owner->fs_order));
    md_fs_context_end(&owner->context);
    if (owner->destination.anchor>=0) close(owner->destination.anchor);
    md_fd_message_release(owner->message);
    md_received_fds_release(owner->prepared); free(owner);
}

void control_publication_finish(struct thread *t,long result) {
    struct md_control_pending *pending=t->control.publication;
    if (!pending) return;
    t->control.publication=NULL;
    pending->copied(pending,result); pending->dispose(pending);
}

void control_pending_complete(struct thread *t,long result) {
    struct md_control_pending *pending=t->control.pending;
    t->control.pending=NULL;
    if (!pending) return;
    if (result>=0 && pending->copied) {
        CHECK(!t->control.publication);
        t->control.publication=pending;
    } else pending->dispose(pending);
}

static long shm_control(struct thread *t,unsigned op,const unsigned long a[4]) {
    struct md_shm *shm=NULL;
    int r=md_ipc_namespace_memory(t->resources.ipc,&shm);
    if (!r && t->phase!=DISPATCHING) r=-EPERM;
    if (op==MD_SHM_STAT || op==MD_SHM_OPEN || op==MD_SHM_ATTACHED || op==MD_SHM_ABORT) r=-EINVAL;
    struct shmid64_ds info;
    return r ? r : md_shm_call(shm,t->shm_space,NULL,&t->identity,t->tgid,t->resources.pid_group,op,a,&info);
}

static long ipc_control(struct thread *t,unsigned operation,unsigned long argument) {
    struct md_ipc_request call={.nr=t->execution.request.nr};
    for (unsigned i=0;i<6;i++) call.args[i]=t->execution.request.args[i];
    int r=t->phase!=DISPATCHING ? -EPERM : 0;
    if (!r && (call.nr!=SYS_semget && call.nr!=SYS_semctl
            && call.nr!=SYS_semop && call.nr!=SYS_semtimedop && call.nr!=SYS_msgget
            && call.nr!=SYS_msgctl && call.nr!=SYS_msgsnd && call.nr!=SYS_msgrcv)) r=-EINVAL;
    struct md_ipc *ipc=NULL;
    if (!r) r=md_ipc_namespace_semaphores(t->resources.ipc,&ipc);
    long value=r;
    struct md_ipc_transport *channel=&t->io.transport;
    if (!r && operation==MD_IPC_CLOSE) {
        md_ipc_transport_close(channel);
        if (argument) {
            value=md_ipc_cancel(ipc,&t->io.pending); t->io.cancel=0;
        }
    } else if (!r && operation==MD_IPC_EXECUTE && channel->packet) {
        if (t->io.cancel) {
            value=t->io.cancel; t->io.cancel=0;
            int error=md_ipc_cancel(ipc,&t->io.pending); if (error) value=error;
        } else value=md_ipc_call(ipc,&t->io.pending,t->resources.sem_undo,&t->identity,t->pid,t->tgid,t->resources.pid_group,channel);
    } else if (!r) value=-EINVAL;
    return value;
}

long resource_control(struct thread *t,unsigned service,unsigned operation,const unsigned long args[4]) {
    if (service==MD_GUEST_SHM) return shm_control(t,operation,args);
    if (service==MD_GUEST_IPC) return ipc_control(t,operation,args[0]);
    if (service==MD_GUEST_PID_SIGNAL_GROUP)
        return signal_process_next(t,(pid_t)operation,(int)args[0],(pid_t)args[1]);
    return -EINVAL;
}

void ipc_entry_wait(struct thread *t) {
    int error=ipc_watch(t);
    if (error) {
        t->io.cancel=error;
        entry_execution_retry(t);
    } else {
        CHECK(entry_wait_begin(t,md_ipc_deadline(t->io.pending),ipc_wait_shared));
    }
}

static void ipc_wait_shared(struct thread *t,long value) {
    ipc_unwatch(t);
    /* The IPC owner retains its captured input and deadline. Readiness retries
     * that operation; signals cancel it without replaying guest-side copying. */
    if (value<0 && value!=-ETIMEDOUT) t->io.cancel=value;
    entry_execution_retry(t);
}

void resource_request(struct thread *t,const struct seccomp_notif *q) {
    unsigned long args[4]={q->data.args[2],q->data.args[3],q->data.args[4],q->data.args[5]};
    long value=q->data.instruction_pointer!=RAW_GATE ? -EPERM
        : resource_control(t,q->data.args[0],q->data.args[1],args);
    struct seccomp_notif_resp reply={.id=q->id};
    if (value==MD_IPC_WAIT) reply.val=value;
    else if (value<0) reply.error=value;
    else reply.val=value;
    CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
}

void ipc_unwatch(struct thread *t) {
    if (t->io.event) {
        CHECK(!epoll_ctl(ipc_events,EPOLL_CTL_DEL,md_ipc_wait_fd(t->io.pending),NULL)
            || errno==ENOENT);
        t->io.event=0;
    }
}

static int ipc_watch(struct thread *t) {
    if (t->io.event || !t->io.pending) return -EINVAL;
    if (ipc_sequence==UINT64_MAX) return -EOVERFLOW;
    if (ipc_events<0) {
        ipc_events=epoll_create1(EPOLL_CLOEXEC);
        if (ipc_events<0) return -errno;
    }
    struct epoll_event event={.events=EPOLLIN|EPOLLONESHOT,.data.u64=++ipc_sequence};
    if (epoll_ctl(ipc_events,EPOLL_CTL_ADD,md_ipc_wait_fd(t->io.pending),&event)) return -errno;
    t->io.event=event.data.u64; return 0;
}

void ipc_events_ready(void) {
    struct epoll_event events[16];
    int count;
    do count=epoll_wait(ipc_events,events,16,0); while (count<0 && errno==EINTR);
    CHECK(count>=0);
    for (int i=0;i<count;++i) {
        struct thread *t=threads;
        while (t && (!t->pid || t->io.event!=events[i].data.u64)) t=t->next;
        if (!t) continue;
        if (t->entry.wait.complete==ipc_wait_shared) {
            entry_wait_complete(t,0); continue;
        }
        CHECK(t->phase==IPC_WAITING);
        ipc_unwatch(t); t->io.waking=1;
        /* EVENT_WAIT: the task's interruptible empty poll is cancelled by an
         * exact watcher event. No guest FD or synthetic application signal. */
        if (!t->listening) tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"ipc-ready");
    }
}

static struct user_pt_regs wait_poll_registers(const struct thread *t,enum phase phase) {
    CHECK(phase==FILE_WAIT_ENTRY || phase==CONTROL_WAIT_ENTRY || phase==CLOSE_WAIT_ENTRY
        || phase==DEBUG_PARK_ENTRY);
    struct user_pt_regs regs=phase==CONTROL_WAIT_ENTRY ? t->fs.registers
        : phase==CLOSE_WAIT_ENTRY ? t->close.registers : t->execution.registers;
    regs.pc=abi.task_call; regs.regs[8]=SYS_ppoll;
    for (unsigned i=0;i<5;++i) regs.regs[i]=0;
    return regs;
}

void wait_poll_start(struct thread *t,enum phase phase) {
    struct user_pt_regs regs=wait_poll_registers(t,phase);
    if (set_registers(t->pid,&regs)) { t->phase=phase; resume(t->pid,0); }
}

int wait_poll_entry(struct thread *t) {
    struct ptrace_syscall_info info={0};
    if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"provider-wait-entry")) return -1;
    if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
        CHECK(info.entry.nr==SYS_ppoll && info.instruction_pointer==abi.task_return);
        return 1;
    }
    /* Starting at seccomp first drains the skipped application's exit. That
     * exit can overwrite x0; rearm the poll before unmasking or cancellation. */
    CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT);
    struct user_pt_regs regs=wait_poll_registers(t,t->phase);
    return set_registers(t->pid,&regs) ? 0 : -1;
}

void control_park(struct thread *t) {
    if (t->phase==CONTROL_WAIT_ENTRY || t->phase==CONTROL_WAITING) return;
    if (entry_wait_begin(t,t->control.pending ? t->control.pending->deadline : INT64_MAX,control_wait_shared)) return;
    if (t->entry.stop.resume==control_park) return;
    if (entry_require_stop(t,MD_CONTEXT_WAIT,0,control_park)) return;
    if (t->control.done) {
        t->control.done=0; control_deliver(t,t->control.result); return;
    }
    CHECK(t->phase==DISPATCHING && t->kernel_stopped && !t->control.done);
    /* EVENT_WAIT: provider completion interrupts the caller's native poll.
     * Guest signals remain deliverable; the owner retains only this request. */
    wait_poll_start(t,CONTROL_WAIT_ENTRY);
}

static void control_deliver(struct thread *t,long result) {
    if (!t->proc.direct) { buffer_reply(t,result); return; }
    t->proc.direct=0;
    if (result>=0) result=buffer_result_finish(t,0,result);
    else (void)buffer_cleanup(t);
    dispatch_complete(t,result); adapter_progress();
}

void control_reply(struct thread *t,long result) {
    entry_wait_end(t);
    if (t->fs.request.command==MD_BUFFER_CONTROL_MAP && result>=0) {
        CHECK(t->control.mapping_fd<0 && result<=INT_MAX);
        t->control.mapping_fd=(int)result; result=0;
    }
    /* A provider may finish before the requested checkpoint arrives. Retain
     * its exact result; only that checkpoint owns the caller continuation. */
    if (t->entry.stop.resume==control_park) {
        CHECK(!t->control.done);
        t->control.done=1; t->control.result=result; return;
    }
    if (t->phase!=CONTROL_WAIT_ENTRY && t->phase!=CONTROL_WAITING) { control_deliver(t,result); return; }
    CHECK(!t->control.done);
    t->control.done=1; t->control.result=result;
    if (t->phase==CONTROL_WAITING && !t->listening)
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"control-ready");
}

struct thread *control_deadline_owner(void) {
    struct md_pending_queue *queue=&retry_queues[RETRY_CONTROL>=RETRY_FD];
    struct thread *first=NULL;
    /* The continuation registry already owns every active control wait.
     * Idle and retired thread slots have no deadline to inspect. */
    for (unsigned i=0;i<2;++i) for (struct md_pending_node *node=queue->lists[i].first;node;node=node->next) {
        struct retry_owner *owner=(struct retry_owner *)node;
        if (!(owner->mask&RETRY_CONTROL)) continue;
        struct thread *t=owner->thread;
        CHECK(t->pid && t->control.pending);
        if (!first || t->control.pending->deadline<first->control.pending->deadline) first=t;
    }
    return first;
}

void control_progress(struct thread *t) {
    retry_clear(t,RETRY_CONTROL);
    struct md_control_pending *pending=t->control.pending;
    if (!pending) return;
    struct md_buffer_span span;
    long result=md_buffer_span(&t->fs.buffer,&span);
    if (!result && t->control.size>span.size) result=-EOVERFLOW;
    if (!result) result=pending->resume(pending,span.data,t->control.size);
    if (result==-EINPROGRESS && md_event_now()<pending->deadline) {
        retry_wait(t,RETRY_CONTROL); control_park(t); return;
    }
    if (result==-EINPROGRESS) result=-ETIMEDOUT;
    control_pending_complete(t,result);
    control_reply(t,result);
}

static void outgoing_complete(void *context, struct md_task_helper_result result) {
    struct outgoing_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0 && !result.received);
    md_fd_message_export_end(owner->message,owner->stage==OUTGOING_SEND
        && (result.value>=0 || result.value==-ECANCELED));
    if (t && t->io.outgoing==owner) {
        t->io.outgoing=NULL; io_return_context(t,owner->brokered,&owner->continuation);
        buffer_reply_signal(t,result.value,result.signal.si_signo ? &result.signal : NULL);
    }
    outgoing_release(owner);
}

static void outgoing_prepared(struct md_ipc_origin_request *request, int error, void *context) {
    struct outgoing_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(t && t->io.outgoing==owner && owner->preparation==request && owner->stage==OUTGOING_PREPARE);
    enum md_ipc_ancillary_transport transport;
    int taken=md_ipc_origins_take_send(&owner->preparation,&owner->prepared,&transport,&owner->destination);
    CHECK(taken==error);
    struct md_buffer_span span;
    if (!error) error=md_buffer_span(&t->fs.buffer,&span);
    if (error) { outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1}); return; }
    struct md_task_outgoing_frame *frame=span.data;
    CHECK(owner->prepared->count>=1 && owner->prepared->count<=254);
    frame->rights=owner->prepared->count-1; frame->transport=transport;
    owner->brokered=owner->destination.brokered;
    frame->brokered=owner->brokered;
    frame->destination=owner->destination;
    frame->destination.anchor=-1; /* Host descriptor numbers never enter guest state. */
    owner->stage=OUTGOING_READY;
    if (!t->entry.control) buffer_capture(t,&owner->continuation);
    buffer_reply(t,0);
}

static void outgoing_exported(void *context, struct md_task_helper_result result) {
    struct outgoing_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0);
    if (!t || t->io.outgoing!=owner) {
        md_received_fds_release(result.received); outgoing_release(owner); return;
    }
    if (!owner->brokered) t->kernel_stopped=1;
    int error=(int)result.value;
    if (!error && (!result.received || result.received->count!=owner->input.count)) error=-EPROTO;
    if (!error && !origin_service) error=md_ipc_origins_new(filesystem,registration.id,&origin_service);
    if (!error) error=md_ipc_origins_send(origin_service,owner->message,result.received,
        &owner->context,&owner->options,outgoing_prepared,owner,&owner->preparation);
    md_received_fds_release(result.received);
    if (error) outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else owner->stage=OUTGOING_PREPARE;
}

static void outgoing_captured(void *context) {
    struct outgoing_owner *owner=context;
    CHECK(!md_fd_order_complete(&owner->files));
}

static int reusable_io_channel(struct thread *t) {
    /* Called under the file-table turn. Reuse an admitted channel without
     * adding an infrastructure FD to ordinary IO or exhausting a full table. */
    struct md_fd_channel *channel=NULL; int number=-1;
    int error=md_fd_origins_channel(t->resources.files,&number,&channel);
    md_fd_channel_release(channel);
    return !error && number>=0;
}

static void outgoing_capture_task(struct thread *t) {
    struct outgoing_owner *owner=t->io.outgoing;
    if (entry_require_stop(t,MD_CONTEXT_DESCRIPTOR,0,outgoing_capture_task)) return;
    owner->continuation=t->fs.registers;
    t->terminal_observation=1; t->trace_options_dirty=1;
    int error=debugger_options(t->pid,t->debug.options);
    if (!error) {
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_EXPORT,
            .descriptors=owner->input.keep,.descriptor_count=owner->input.count,.captured=outgoing_captured};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,&owner->continuation,&abi,t->memory,&t->fs.buffer,
            &request,outgoing_exported,owner);
    }
    if (error) outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
}

void outgoing_start(struct thread *t) {
    retry_clear(t,RETRY_OUTGOING);
    struct outgoing_owner *owner=t->io.outgoing;
    if (!owner || owner->stage!=OUTGOING_QUEUED) return;
    int error=0;
    if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_READY) {
        error=md_fd_order_start(&owner->files);
        if (!error) { error=md_fs_order_begin(t->resources.fs,&owner->fs_order,fd_wakeup); if (error>0) error=0; }
    }
    if (!error && md_fs_order_state(&owner->fs_order)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_OUTGOING); return; }
    if (!error) error=md_fs_order_start(&owner->fs_order);
    if (!error) error=md_task_registry_read(tasks,t->pid,0,&owner->context.task);
    if (!error) {
        owner->context.actor=t->pid; owner->context.peer=t->tgid;
        owner->context.authenticated=owner->context.has_identity=1;
        owner->brokered|=reusable_io_channel(t);
        owner->options.brokered=owner->brokered || t->entry.execution!=NULL;
        error=md_fs_order_complete(&owner->fs_order);
    }
    if (!error && owner->input.count>1)
        error=md_fd_message_capture(t->resources.files,owner->input.keep+1,owner->input.count-1,&owner->message);
    if (!error) {
        struct md_received_fds *received=NULL;
        error=md_fd_capture_native(t->pid,owner->input.keep,owner->input.count,&received);
        if (error>0) {
            CHECK(!md_fd_order_complete(&owner->files));
            owner->stage=OUTGOING_CAPTURE;
            owner->brokered=1;
            outgoing_exported(owner,(struct md_task_helper_result){.value=0,.descriptor=-1,.received=received});
            return;
        }
    }
    owner->stage=OUTGOING_CAPTURE;
    if (!error && owner->brokered) { ipc_capture_step(t,MD_FS_BUFFER_IPC_CAPTURE_READY,0); return; }
    if (error) outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else outgoing_capture_task(t);
}

static void outgoing_begin(struct thread *t, const struct user_pt_regs *regs) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct md_task_send input={0};
    if (!error) {
        if (span.size<sizeof(struct md_task_outgoing_frame)) error=-EINVAL;
        else input=((struct md_task_outgoing_frame *)span.data)->input;
    }
    if (!error && (!input.message || input.count<1 || input.count>MD_TASK_SEND_INPUTS
            || input.socket!=input.keep[0])) error=-EINVAL;
    struct outgoing_owner *owner=NULL;
    if (!error) { owner=calloc(1,sizeof(*owner)); if (!owner) error=-ENOMEM; }
    if (!error) {
        *owner=(struct outgoing_owner){.caller=t->pid,.input=input,
            .brokered=t->seccomp.application_filter,
            .options=((struct md_task_outgoing_frame *)span.data)->options,.destination={.anchor=-1}};
        if (regs) owner->continuation=*regs;
        owner->options.brokered=owner->brokered || t->entry.execution!=NULL;
        error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error>0) error=0;
    }
    if (error) { outgoing_release(owner); buffer_reply(t,error); }
    else { t->io.outgoing=owner; outgoing_start(t); }
}

static void outgoing_send_stopped(struct thread *t) {
    outgoing_send(t,&t->fs.registers);
}

static void outgoing_send(struct thread *t, const struct user_pt_regs *regs) {
    struct outgoing_owner *owner=t->io.outgoing;
    uintptr_t address=md_memory_buffer_address(t->memory,&t->fs.buffer);
    if (regs) owner->continuation=*regs;
    owner->stage=OUTGOING_SEND;
    if (owner->brokered) { socket_io_progress(t); return; }
    /* An unmodified native peer observes kernel sender credentials. Only this
     * task-affine send needs a real helper continuation, not capture/preparation. */
    if (entry_require_stop(t,MD_CONTEXT_DESCRIPTOR,0,outgoing_send_stopped)) return;
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_SEND_CAPTURED,
        .send_inputs=owner->prepared,.send_import=address+offsetof(struct md_task_outgoing_frame,scratch.import),
        .send_address=owner->destination.anchor>=0 ? address+offsetof(struct md_task_outgoing_frame,destination.address) : 0,
        .send_anchor=owner->destination.anchor,
        .send={.message=owner->input.message,.socket=owner->input.socket,.flags=owner->input.flags,
            .sendto=owner->input.sendto,.payload=owner->input.payload,
            .token_slot=owner->input.token_slot}};
    int error=md_task_helper_begin(helpers,t->pid,t->tgid,regs,&abi,t->memory,&t->fs.buffer,
        &request,outgoing_complete,owner);
    if (error) outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
}

static void receive_captured(void *context) {
    struct receive_owner *owner=context;
    CHECK(!md_fd_order_complete(&owner->files));
}

static void receive_complete(void *context, struct md_task_helper_result result) {
    struct receive_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    owner->ready=1; owner->received=result.received;
    if (t && t->io.receive==owner) {
        io_return_context(t,owner->brokered,&owner->continuation);
        if (owner->brokered && owner->request.inline_receive) {
            struct md_buffer_span span;
            CHECK(!md_buffer_span(&t->fs.buffer,&span));
            struct md_task_receive_frame *frame=span.data;
            if (result.value<0 && owner->request.finish_error && !t->entry.execution
                    && (!owner->request.batch || !owner->io.initialized || !frame->io.batch_count)
                    && (t->execution.request.nr==SYS_recvmsg || t->execution.request.nr==SYS_recvmmsg)) {
                /* No committed message or caller-side allocation remains.
                 * A late batch error must still publish its successful prefix. */
                dispatch_complete(t,buffer_result_finish(t,0,result.value));
                adapter_progress(); return;
            }
            frame->io_completed=1; frame->result=result.value;
            if (owner->prepared) {
                frame->prepared=1;
                if (set_registers(t->pid,&owner->continuation)) resume(t->pid,0);
                return;
            }
            result.value=MD_IPC_TASK_IO;
        }
        buffer_reply(t,result.value);
    } else receive_release(owner);
}

static void ipc_capture_step(struct thread *t, unsigned command, long value) {
    struct outgoing_owner *out=t->io.outgoing;
    struct receive_owner *in=t->io.receive;
    struct md_fd_capture *capture=out ? &out->capture : &in->capture;
    struct md_fd_order *order=out ? &out->files : &in->files;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct md_fd_capture_frame *frame=error ? NULL : out
        ? &((struct md_task_outgoing_frame *)span.data)->scratch.capture.capture
        : &((struct md_task_receive_frame *)span.data)->capture;
    if (!error && command==MD_FS_BUFFER_IPC_CAPTURE_SENT) error=md_fd_capture_sent(capture,value);
    if (!error && command==MD_FS_BUFFER_IPC_CAPTURE_OBSERVED)
        error=md_fd_capture_observed(capture,order->table,frame);
    if (!error) error=md_fd_capture_progress(capture,order->table,t->tgid,out ? out->input.count : 1,frame);
    if (error>0) { buffer_reply(t,error); return; }
    struct md_received_fds *received=error ? NULL : md_fd_capture_take(capture);
    if (!error && !received) error=-EPROTO;
    if (!error) CHECK(!md_fd_order_complete(order));
    if (out) outgoing_exported(out,(struct md_task_helper_result){.value=error,.descriptor=-1,.received=received});
    else if (error) receive_complete(in,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else {
        in->received=received;
        if (in->request.inline_receive) socket_io_progress(t);
        else buffer_reply(t,MD_IPC_TASK_IO);
    }
}

static struct md_ipc_io *socket_operation(struct thread *t) {
    return t->io.outgoing ? &t->io.outgoing->io : &t->io.receive->io;
}

void socket_unwatch(struct thread *t) {
    if (t->readiness) { readiness_unwatch(t); return; }
    int *watch=t->io.outgoing ? &t->io.outgoing->io_waiting : &t->io.receive->io_waiting;
    int fd=t->io.outgoing ? t->io.outgoing->prepared->fds[0] : t->io.receive->received->fds[0];
    if (*watch) { CHECK(!epoll_ctl(socket_events,EPOLL_CTL_DEL,md_ipc_io_event(socket_operation(t),fd),NULL)); *watch=0; }
}

static void socket_complete(struct thread *t, long result) {
    entry_wait_end(t);
    CHECK(!socket_operation(t)->job.started);
    socket_unwatch(t);
    t->phase=DISPATCHING;
    if (t->io.outgoing) {
        if (result==-EPIPE && !(t->io.outgoing->input.flags&MSG_NOSIGNAL)) t->io.socket_sigpipe=1;
        outgoing_complete(t->io.outgoing,(struct md_task_helper_result){.value=result,.descriptor=-1});
    } else {
        struct receive_owner *owner=t->io.receive;
        if (result==-EPIPE && md_network_io_sending(&owner->network)
                && !(md_network_io_flags(&owner->network)&MSG_NOSIGNAL)) t->io.socket_sigpipe=1;
        if (owner->network.number && (owner->network.number==SYS_accept || owner->network.number==SYS_accept4)
                && result>=0) {
            int fd=(int)result;
            owner->io.native_ready=0;
            result=md_network_endpoint_accept(owner->network.endpoint,fd,&t->fs.open.object);
            if (!result) {
                struct md_buffer_span span;
                result=md_buffer_span(&t->fs.buffer,&span);
                if (!result) {
                    struct md_ipc_io_message *io=&((struct md_task_receive_frame *)span.data)->io;
                    result=md_network_endpoint_name(t->fs.open.object,fd,1,&io->address,&io->address_size);
                }
            }
            if (result) { close(fd); md_fd_object_release(t->fs.open.object); t->fs.open.object=NULL; }
            else {
                t->fs.open.fd=fd;
                t->fs.open.flags=owner->network.number==SYS_accept4 ? owner->network.args[3]&SOCK_CLOEXEC : 0;
            }
        }
        receive_complete(owner,(struct md_task_helper_result){.value=result,.descriptor=-1,.received=owner->received});
    }
}

int socket_watch(struct thread *t) {
    int *watch=t->readiness ? &t->readiness->io_waiting
        : t->io.outgoing ? &t->io.outgoing->io_waiting : &t->io.receive->io_waiting;
    if (*watch) return 0;
    if (socket_events<0) socket_events=epoll_create1(EPOLL_CLOEXEC);
    if (socket_events<0) return -errno;
    int fd=t->readiness ? md_ipc_io_job_event(&t->readiness->wait.job)
        : md_ipc_io_event(socket_operation(t),t->io.outgoing ? t->io.outgoing->prepared->fds[0] : t->io.receive->received->fds[0]);
    int sending=!t->readiness && (t->io.outgoing || (t->io.receive && !t->io.receive->io.job.started
        && md_network_io_sending(&t->io.receive->network)));
    struct epoll_event event={.events=(sending ? EPOLLOUT : EPOLLIN)|EPOLLONESHOT,.data.u32=(unsigned)t->pid};
    if (epoll_ctl(socket_events,EPOLL_CTL_ADD,fd,&event)) return -errno;
    *watch=1;
    return 0;
}

static void operation_park(struct thread *t,struct user_pt_regs regs,int64_t deadline) {
    int64_t remaining=deadline==INT64_MAX ? 0 : deadline-md_event_now();
    if (remaining<0) remaining=0;
    /* EVENT_WAIT: the owned completion descriptor, a guest signal or deadline
     * cancels ppoll. No elapsed interval is treated as readiness. */
    regs.pc=abi.ipc_wait;
    regs.regs[1]=remaining/1000000000LL; regs.regs[2]=remaining%1000000000LL;
    regs.regs[3]=deadline!=INT64_MAX;
    if (set_registers(t->pid,&regs)) { t->phase=SOCKET_WAIT_ENTRY; resume(t->pid,0); }
}

static void socket_wait_shared(struct thread *t,long value) {
    socket_unwatch(t);
    if (!value) { socket_io_progress(t); return; }
    socket_wait_result(t,value==-ETIMEDOUT ? 0 : value,value==-EINTR,0);
}

static void socket_io_progress(struct thread *t) {
    if (t->readiness) { readiness_progress(t); return; }
    struct outgoing_owner *out=t->io.outgoing;
    struct receive_owner *in=t->io.receive;
    socket_unwatch(t);
    struct md_buffer_span span;
    long result=md_buffer_span(&t->fs.buffer,&span);
    int waiting=0;
    if (!result) {
        struct md_ipc_io_message *io=out ? &((struct md_task_outgoing_frame *)span.data)->scratch.capture.io
            : &((struct md_task_receive_frame *)span.data)->io;
        if (in && in->request.batch_prepared && !in->io.initialized) {
            result=in->network.number
                ? md_network_io_prepare_batch(&in->io,io,in->received->fds[0],in->request.flags,in->request.batch_prepared)
                : md_ipc_io_prepare_batch(&in->io,io,in->received->fds[0],in->request.flags,in->request.batch_prepared);
            if (result) io->batch_count=0;
        }
        if (!result) waiting=in && in->network.number ? md_network_io_progress(&in->network,&in->io,&in->received,io,&result)
            : out ? md_ipc_io_send(&out->io,out->prepared,io,out->input.flags,&result)
            : md_ipc_io_receive(&in->io,&in->received,io,in->request.flags,&result);
    }
    if (waiting) {
        int64_t deadline=socket_operation(t)->job.started ? INT64_MAX : socket_operation(t)->deadline;
        if (t->entry.execution && t->entry.control) {
            int error=socket_watch(t);
            if (error) { socket_wait_result(t,error,0,0); return; }
            CHECK(entry_wait_begin(t,deadline,socket_wait_shared));
            return;
        }
        struct user_pt_regs regs=out ? out->continuation : in->continuation;
        /* The adapter is suspended mid-call. Its live stack is not the reusable
         * stack top used by completed SysV dispatches. */
        operation_park(t,regs,deadline);
        return;
    }
    SUPERVISOR_TRACE("PROBE socket-io pid=%d operation=%s result=%ld rights=%u\n",t->pid,out ? "send" : "receive",result,
        out ? out->prepared->count-1 : in->received->count-1);
    socket_complete(t,result);
}

void socket_events_ready(void) {
    struct epoll_event events[32];
    int count=epoll_wait(socket_events,events,32,0);
    CHECK(count>=0 || errno==EINTR);
    for (int i=0;i<count;++i) {
        struct thread *t=find_thread((pid_t)events[i].data.u32);
        if (t && ((t->io.outgoing && t->io.outgoing->io_waiting) || (t->io.receive && t->io.receive->io_waiting)
                || (t->readiness && t->readiness->io_waiting))) {
            if (t->entry.wait.complete==socket_wait_shared) {
                entry_wait_complete(t,0); continue;
            }
            CHECK(t->phase==SOCKET_WAITING || t->phase==SOCKET_WAIT_ENTRY || readiness_waiting(t));
            socket_unwatch(t);
            int waking=t->io.socket_waking; t->io.socket_waking=1;
            /* EVENT_WAIT: readiness cancels the caller's interruptible ppoll.
             * Socket timeout or signal completes the operation without replay. */
            if (!waking && (t->phase==SOCKET_WAITING || readiness_waiting(t)) && !t->listening)
                tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"socket-ready");
        }
    }
}

static void receive_capture_task(struct thread *t) {
    struct receive_owner *owner=t->io.receive;
    owner->started=1;
    if (entry_require_stop(t,MD_CONTEXT_DESCRIPTOR,0,receive_capture_task)) return;
    owner->continuation=t->fs.registers;
    t->terminal_observation=1; t->trace_options_dirty=1;
    int error=debugger_options(t->pid,t->debug.options);
    if (!error) {
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_RECEIVE,
            .receive=owner->request,.captured=receive_captured};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,&owner->continuation,&abi,t->memory,&t->fs.buffer,
            &request,receive_complete,owner);
    }
    if (error) receive_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
}

void receive_start(struct thread *t) {
    retry_clear(t,RETRY_RECEIVE);
    struct receive_owner *owner=t->io.receive;
    if (!owner || owner->started) return;
    if (md_fd_order_state(&owner->files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_RECEIVE); return; }
    int error=md_fd_order_start(&owner->files);
    if (!error && (owner->network.number || owner->request.batch)) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,owner->request.socket,NULL);
        if (md_network_endpoint_is(object)) {
            owner->network.endpoint=object;
            if (!owner->network.number) {
                owner->brokered=1; owner->network.number=t->execution.request.nr;
                owner->network.identity=&t->identity;
                memcpy(owner->network.args,t->execution.request.args,sizeof(owner->network.args));
            }
        } else {
            md_fd_object_release(object);
            if (owner->network.number) error=-EBADF;
        }
    }
    if (!error) {
        error=md_fd_capture_native(t->pid,&owner->request.socket,1,&owner->received);
        if (error>0) {
            owner->started=owner->brokered=1;
            CHECK(!md_fd_order_complete(&owner->files));
            if (owner->request.inline_receive) socket_io_progress(t);
            else buffer_reply(t,MD_IPC_TASK_IO);
            return;
        }
    }
    if (!error) owner->brokered|=reusable_io_channel(t);
    if (!error && owner->brokered) {
        owner->started=1; ipc_capture_step(t,MD_FS_BUFFER_IPC_CAPTURE_READY,0); return;
    }
    if (error) receive_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else receive_capture_task(t);
}

static void receive_begin(struct thread *t, const struct user_pt_regs *regs,unsigned command) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct receive_owner *owner=NULL;
    if (!error) { owner=calloc(1,sizeof(*owner)); if (!owner) error=-ENOMEM; }
    if (!error) {
        *owner=(struct receive_owner){.caller=t->pid,
            .brokered=t->seccomp.application_filter,
            .request=((struct md_task_receive_frame *)span.data)->input};
        if (regs) owner->continuation=*regs;
        if (command==MD_BUFFER_NETWORK_DATA) {
            owner->brokered=1; owner->network.number=t->execution.request.nr;
            owner->network.identity=&t->identity;
            for (unsigned i=0;i<6;++i) owner->network.args[i]=t->execution.request.args[i];
            if (owner->network.number==SYS_sendmmsg) {
                owner->network.number=SYS_sendmsg;
                owner->network.args[2]=t->execution.request.args[3];
            }
            owner->request.socket=(int)t->execution.request.args[0];
        }
        error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error>0) error=0;
    }
    if (error) { free(owner); buffer_reply(t,error); }
    else { t->io.receive=owner; receive_start(t); }
}

static int receive_return_begin(struct thread *t, unsigned count, int annotated) {
    struct receive_owner *owner=t->io.receive;
    struct receive_publication *p=annotated ? owner->publication : NULL;
    struct md_fd_return_publication publication={0};
    if (p) {
        struct md_buffer_span span;
        int error=md_buffer_span(&t->fs.buffer,&span);
        if (error) return error;
        struct md_fs_buffer_frame *frame=span.data;
        publication=(struct md_fd_return_publication){.origins=p->origins,.updates=p->updates,.numbers=p->numbers,
            .memory=(void *)frame->receipt,
            .flags=owner->request.flags&MSG_CMSG_CLOEXEC ? FD_CLOEXEC : 0};
    }
    int result=md_fd_return_vector_begin(&t->fs.returned,t->resources.files,owner->outgoing,count,
        p ? &publication : NULL,fd_wakeup);
    if (!result) { t->fs.return_waiting=1; buffer_return_progress(t); }
    return result;
}

static int receive_return(struct thread *t, const struct md_received_fds *source, const int *indices, unsigned count) {
    struct receive_owner *owner=t->io.receive;
    if (!source->origins) {
        int error=md_received_fds_select(source,indices,count,owner->outgoing,NULL);
        return error ? error : receive_return_begin(t,count,0);
    }
    struct md_fd_object *origins[253];
    int error=md_received_fds_select(source,indices,count,owner->outgoing,origins);
    if (error) return error;
    unsigned known=0;
    for (unsigned i=0;i<count;++i) known+=origins[i]!=NULL;
    if (!known) return receive_return_begin(t,count,0);
    if (!owner->publication) {
        owner->publication=calloc(1,sizeof(*owner->publication));
        if (!owner->publication) return -ENOMEM;
    }
    struct receive_publication *p=owner->publication;
    memcpy(p->origins,origins,count*sizeof(*origins));
    return receive_return_begin(t,count,1);
}

void io_buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_FS_BUFFER_IPC_IO_PREPARE:
    case MD_FS_BUFFER_IPC_IO_COPY:
    case MD_FS_BUFFER_IPC_IO_SELECT: {
        if (t->phase!=DISPATCHING || control->args[1]
                || (control->args[0] && (control->command!=MD_FS_BUFFER_IPC_IO_PREPARE
                    || control->args[0]>MD_IPC_IO_BATCH || !t->io.receive || !t->io.receive->request.batch))
                || !((t->io.outgoing && t->io.outgoing->brokered && t->io.outgoing->stage==OUTGOING_READY)
                    || (t->io.receive && t->io.receive->brokered && t->io.receive->received))) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_ipc_io_message *io=t->io.outgoing ? &((struct md_task_outgoing_frame *)span.data)->scratch.capture.io
            : &((struct md_task_receive_frame *)span.data)->io;
        struct md_ipc_io *op=socket_operation(t);
        if (control->command==MD_FS_BUFFER_IPC_IO_PREPARE) {
            int fd=t->io.outgoing ? t->io.outgoing->prepared->fds[0] : t->io.receive->received->fds[0];
            int flags=t->io.outgoing ? t->io.outgoing->input.flags : t->io.receive->request.flags;
            int sending=t->io.outgoing!=NULL;
            if (t->io.receive && t->io.receive->network.number) {
                flags=md_network_io_flags(&t->io.receive->network);
                sending=md_network_io_sending(&t->io.receive->network);
            }
            result=control->args[0] ? md_ipc_io_prepare_batch(op,io,fd,flags,(unsigned)control->args[0])
                : md_ipc_io_prepare(op,io,fd,flags,sending);
        } else if (control->command==MD_FS_BUFFER_IPC_IO_SELECT)
            result=t->io.receive && t->io.receive->network.number
                ? md_network_io_select(&t->io.receive->network,op,io,io->batch_index)
                : md_ipc_io_select(op,io,io->batch_index);
        else if (io->transfer&~(MD_IPC_IO_CONTROL_BYTES|MD_IPC_IO_READ_BYTES)) result=-EINVAL;
        else result=md_ipc_io_copy(op,io,io->offset,io->length,
            !!(io->transfer&MD_IPC_IO_CONTROL_BYTES),!!(io->transfer&MD_IPC_IO_READ_BYTES));
        break;
    }
    case MD_FS_BUFFER_IPC_CAPTURE_READY:
    case MD_FS_BUFFER_IPC_CAPTURE_SENT:
    case MD_FS_BUFFER_IPC_CAPTURE_OBSERVED:
        if (control->args[1] || t->phase!=DISPATCHING
                || !((t->io.outgoing && t->io.outgoing->brokered && t->io.outgoing->stage==OUTGOING_CAPTURE)
                    || (t->io.receive && t->io.receive->brokered && !t->io.receive->ready && t->io.receive->capture.channel))) break;
        if (r) {
            if (t->io.outgoing) t->io.outgoing->continuation=*r;
            else t->io.receive->continuation=*r;
        }
        ipc_capture_step(t,(unsigned)control->command,(long)control->args[0]); return;
    case MD_FS_BUFFER_IPC_RECEIVE:
        if (control->args[0] || control->args[1] || !t->io.receive || !t->io.receive->brokered
                || t->io.receive->ready || !t->io.receive->received) break;
        if (r) t->io.receive->continuation=*r;
        socket_io_progress(t); return;
    case MD_FS_BUFFER_SEND_PREPARE:
        if (t->phase!=DISPATCHING || t->io.outgoing || !t->fs.buffer.pool || control->args[0] || control->args[1]
                || (t->execution.request.nr!=SYS_sendmsg && t->execution.request.nr!=SYS_sendmmsg
                    && t->execution.request.nr!=SYS_sendto)) break;
        /* EVENT_WAIT: source-table turn, exact capture/reaping and worker
         * preparation; cancellation retires the owner without replay. */
        outgoing_begin(t,r); return;
    case MD_FS_BUFFER_SEND_COMMIT:
        if (!t->io.outgoing || t->io.outgoing->stage!=OUTGOING_READY || !t->fs.buffer.pool || control->args[0] || control->args[1]) break;
        /* EVENT_WAIT: task-local send and exact helper reaping. No table turn
         * spans blocking IO; a committed result must never be replayed. */
        outgoing_send(t,r); return;
    case MD_FS_BUFFER_TASK_RECEIVE:
        if (t->phase!=DISPATCHING || t->io.receive || !t->fs.buffer.pool || control->args[0] || control->args[1]
                || (t->execution.request.nr!=SYS_recvmsg && t->execution.request.nr!=SYS_recvmmsg)) break;
        /* EVENT_WAIT: private native receive, export and exact helper reaping.
         * Source capture alone holds the table turn, never the blocking IO. */
        receive_begin(t,r,(unsigned)control->command); return;
    case MD_BUFFER_NETWORK_DATA:
        if (t->phase!=DISPATCHING || t->io.receive || !t->fs.buffer.pool || control->args[0] || control->args[1]) break;
        /* EVENT_WAIT: exact socket capture followed by the common cancellable
         * native IO owner. No file-table turn spans connect or accept. */
        receive_begin(t,r,(unsigned)control->command); return;
    case MD_FS_BUFFER_RECEIVED_DROP:
        if (!t->io.receive || (!t->io.receive->ready && !t->io.receive->brokered)
                || t->io.receive->io_waiting || control->args[0] || control->args[1]) break;
        receive_release(t->io.receive); t->io.receive=NULL;
        result=0; break;
    case MD_FS_BUFFER_RECEIVED_INSPECT: {
        if (!t->io.receive || !t->io.receive->ready || !t->fs.buffer.pool || control->args[0] || control->args[1]) break;
        /* EVENT_WAIT: worker classifies the retained vector. Task death cancels
         * publication; its independent custody pin survives until completion. */
        result=md_fs_buffer_inspect_begin(buffer_service,t->pid,t->resources.fs,
            &t->fs.buffer,t->io.receive->received,&t->fs.call);
        if (!result) return;
        break;
    }
    case MD_FS_BUFFER_RECEIVED_RIGHTS: {
        if (!t->io.receive || !t->io.receive->ready || !t->io.receive->received
                || !t->fs.buffer.pool || control->args[0] || control->args[1]) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        const struct md_fs_buffer_frame *frame=span.data;
        unsigned count=frame->rights.count;
        if (!count || count>253) { result=-EINVAL; break; }
        int indices[253]; memcpy(indices,frame->rights.index,count*sizeof(*indices));
        if (frame->rights.source!=MD_RECEIVED_KERNEL && frame->rights.source!=MD_RECEIVED_APPLICATION) {
            result=-EINVAL; break;
        }
        int expanded=frame->rights.source==MD_RECEIVED_APPLICATION && t->io.receive->expanded;
        const struct md_received_fds *source=expanded
            ? t->io.receive->expanded : t->io.receive->received;
        for (unsigned i=0;i<count;++i) {
            int index=indices[i];
            if (index<(expanded ? 0 : 1) || (unsigned)index>=source->count) { result=-EBADF; break; }
        }
        if (result) break;
        result=receive_return(t,source,indices,count);
        if (result) break;
        return;
    }
    case MD_BUFFER_IPC_PREPARE: {
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || t->phase!=DISPATCHING
                || control->args[0] || control->args[1]) break;
        struct md_ipc_request call={.nr=t->execution.request.nr};
        for (unsigned i=0;i<6;++i) call.args[i]=t->execution.request.args[i];
        struct md_buffer_span span;
        struct md_ipc *ipc=NULL;
        result=md_ipc_namespace_semaphores(t->resources.ipc,&ipc);
        if (!result) result=md_buffer_span(&t->fs.buffer,&span);
        if (!result) result=md_ipc_transport_prepare(ipc,&t->io.transport,&call,
            t->io.pending!=NULL,span.data,span.size);
        break;
    }
    case MD_BUFFER_NETWORK_OPEN: {
        if (t->phase!=DISPATCHING || t->execution.request.nr!=SYS_socket || !t->fs.buffer.pool
                || t->fs.open.fd>=0 || control->args[0] || control->args[1]) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_device_events *events=md_fs_worker_device_events(filesystem);
        result=events && t->execution.request.args[0]==AF_NETLINK && t->execution.request.args[2]==NETLINK_KOBJECT_UEVENT
            ? md_device_events_socket(events,(int)t->execution.request.args[1],&t->fs.open.object,&t->fs.open.fd)
            : md_network_socket_open(t->resources.network,&t->identity,(int)t->execution.request.args[0],
                (int)t->execution.request.args[1],(int)t->execution.request.args[2],&t->fs.open.object,&t->fs.open.fd);
        if (!result) {
            t->fs.open.flags=t->execution.request.args[1]&SOCK_CLOEXEC;
            ((struct md_fs_buffer_frame *)span.data)->transfer=MD_FS_BUFFER_RIGHTS;
        }
        break;
    }
    case MD_BUFFER_NETWORK_CALL: {
        if (t->phase!=DISPATCHING || !t->fs.buffer.pool || control->args[0]>MD_NETWORK_CONTROL_MAX || control->args[1]) break;
        buffer_descriptor_begin(t,control); return;
    }
    case MD_BUFFER_NETWORK_CREDENTIALS: {
        if (t->phase!=DISPATCHING || !t->fs.buffer.pool || control->args[0] || control->args[1]
                || t->execution.request.nr!=SYS_recvmsg) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (result) break;
        struct md_network_packet *packet=span.data;
        if (span.size<sizeof(*packet) || packet->pid_count>MD_PID_LEVELS) { result=-EINVAL; break; }
        packet->pid=md_pid_numbers_visible(packet->pids,packet->pid_count*sizeof(*packet->pids),
            md_pid_identity_namespace(t->resources.pid),packet->pid);
        if (packet->pid<0) { result=packet->pid; break; }
        packet->uid=md_identity_visible_id(&t->identity,0,packet->uid);
        packet->gid=md_identity_visible_id(&t->identity,1,packet->gid);
        break;
    }
    case MD_BUFFER_SHM_STAT: {
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || t->phase!=DISPATCHING || control->args[1]
                || t->execution.request.nr!=SYS_shmctl
                || ((unsigned)t->execution.request.args[1]&~IPC_64)!=IPC_STAT
                || control->args[0]!=t->execution.request.args[0]) break;
        struct md_buffer_span span;
        struct md_shm *shm=NULL;
        result=md_ipc_namespace_memory(t->resources.ipc,&shm);
        if (!result) result=md_buffer_span(&t->fs.buffer,&span);
        if (!result) {
            unsigned long args[4]={control->args[0],0,0,0};
            result=md_shm_call(shm,t->shm_space,NULL,&t->identity,t->tgid,t->resources.pid_group,
                MD_SHM_STAT,args,span.data);
        }
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}

int receive_prepared(struct thread *t,const struct user_pt_regs *continuation,unsigned policy) {
    if (t->execution.request.nr!=SYS_recvmsg || !t->execution.prepared || !t->endpoint[0]
            || (policy&(MD_DISPATCH_NETWORK_DATA|MD_DISPATCH_NETWORK_CONTROL|MD_DISPATCH_DEVICE_EVENTS|MD_DISPATCH_OFFLINE_SOCKET))
            || t->io.receive || t->descriptors.order.table || t->descriptors.running || t->descriptors.fs_order.fs
            || t->debug.options || t->debug.call_active || t->debug.step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->fs.buffer,&span));
    struct md_task_receive_frame *frame=span.data;
    struct msghdr *original=&frame->original;
    struct iovec local={original,sizeof(*original)},remote={(void *)t->execution.request.args[1],local.iov_len};
    if (process_vm_readv(t->pid,&local,1,&remote,1,0)!=(ssize_t)local.iov_len
            || (int)original->msg_namelen<0 || original->msg_iovlen>8) return 0;
    size_t capacity=md_task_receive_capacity(original->msg_control ? original->msg_controllen : 0);
    if (!capacity || capacity>2048) return 0;
    local=(struct iovec){frame->vectors,original->msg_iovlen*sizeof(*frame->vectors)};
    remote=(struct iovec){original->msg_iov,local.iov_len};
    if (local.iov_len && process_vm_readv(t->pid,&local,1,&remote,1,0)!=(ssize_t)local.iov_len) return 0;
    struct msghdr view=*original;
    view.msg_iov=frame->vectors; view.msg_controllen=capacity;
    if (view.msg_name) view.msg_namelen=sizeof(struct sockaddr_storage);
    frame->io.batch_count=0;
    if (md_socket_payload_layout(&frame->io,&view) || !md_ipc_io_inline(&frame->io)) return 0;
    struct receive_owner *owner=calloc(1,sizeof(*owner));
    if (!owner) return 0;
    owner->request=(struct md_task_receive){.socket=(int)t->execution.request.args[0],
        .flags=(int)t->execution.request.args[2],.count=1,.inline_receive=1,.finish_error=1};
    int ready=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
    if (ready>0) {
        CHECK(!md_fd_order_start(&owner->files));
        ready=md_fd_capture_native(t->pid,&owner->request.socket,1,&owner->received);
    }
    if (ready>0) ready=!md_ipc_io_prepare(&owner->io,&frame->io,owner->received->fds[0],owner->request.flags,0)
        && owner->io.nonblocking;
    if (ready<=0) { receive_release(owner); return 0; }
    CHECK(!md_fd_order_complete(&owner->files));
    /* Capture only: no peek, guessed EAGAIN, or second receive. The existing
     * IPC owner performs actual IO and retains ancillary custody. Empty/error
     * results finish directly; successful data uses the same adapter publisher.
     * Blocking/protected/large inputs keep their task-affine preparation. */
    owner->caller=t->pid;
    owner->started=owner->brokered=owner->prepared=1;
    if (statistics) ++statistics->captured_receives;
    t->io.receive=owner;
    if (!continuation) {
        if (statistics) ++statistics->entry_receives;
        long result;
        CHECK(!md_ipc_io_receive(&owner->io,&owner->received,&frame->io,owner->request.flags,&result));
        owner->ready=1;
        if (result<0) {
            result=buffer_result_finish(t,0,result);
            receive_release(owner); t->io.receive=NULL;
            t->phase=IDLE;
            return_value(t,&t->execution.registers,result);
        } else {
            frame->io_completed=frame->prepared=1; frame->result=result;
            if (save_signal_mask(t,&t->execution.mask,"receive-publication-mask") && shield(t) && skip(t->pid))
                dispatch_with_stack(t);
        }
        return 1;
    }
    owner->continuation=*continuation; t->phase=DISPATCHING;
    socket_io_progress(t);
    return 1;
}

int receive_entry(struct thread *t) {
    if (t->execution.request.nr!=SYS_recvmsg || t->fs.buffer.pool
            || t->broker_event.held || t->metadata_restart) return 0;
    unsigned policy=dispatch_policy(t);
    if (!dispatch_input(t,policy)) return 0;
    /* A completed nonblocking error needs no guest continuation, stack or
     * signal shield. Successful IO retains custody until normal publication. */
    if (receive_prepared(t,NULL,policy)) return 1;
    CHECK(!buffer_cleanup(t));
    return 0;
}

void ipc_wait_complete(struct thread *t) {
    long value;
    if (!stopped_result(t,&value) || !shield(t)) return;
    ipc_unwatch(t);
    SUPERVISOR_TRACE("PROBE ipc-wait pid=%d value=%ld waking=%d\n",t->pid,value,t->io.waking);
    int interrupted=md_syscall_restarting(value);
    if (value<0 && !(t->io.waking && interrupted)) t->io.cancel=interrupted ? -EINTR : value;
    t->io.waking=0;
    dispatch_with_stack(t);
}

void socket_wait_complete(struct thread *t) {
    long value;
    if (!stopped_result(t,&value)) return;
    socket_unwatch(t);
    SUPERVISOR_TRACE("PROBE socket-wait pid=%d value=%ld waking=%d\n",t->pid,value,t->io.socket_waking);
    int restart=md_syscall_restarting(value);
    int waking=t->io.socket_waking; t->io.socket_waking=0;
    if (t->readiness && restart && !waking) {
        readiness_interrupted(t); return;
    }
    if (!shield(t)) return;
    socket_wait_result(t,value,restart,waking);
}

static void socket_wait_result(struct thread *t,long value,int restart,int waking) {
    if (waking && (restart || value==0)) { socket_io_progress(t); return; }
    if (t->readiness) {
        md_epoll_wait_cancel(&t->readiness->wait);
        if (!value) readiness_progress(t);
        else readiness_reply(t,restart ? -EINTR : value);
        return;
    }
    struct md_ipc_io *op=socket_operation(t);
    if (op->job.started) {
        md_ipc_io_interrupt(op);
        if (op->native_result==-EINTR) {
            if (restart && op->deadline==INT64_MAX) op->native_result=RESTART_SIGNAL;
            else if (!restart && value<0) op->native_result=value;
        }
        socket_io_progress(t); return;
    }
    if (op->done) value=(long)op->done;
    else if (restart) value=op->deadline==INT64_MAX ? RESTART_SIGNAL : -EINTR;
    else if (!value) value=-EAGAIN;
    socket_complete(t,value);
}

void socket_wait_cancelled(struct thread *t) {
    int error=t->execution.wait_error; t->execution.wait_error=0; t->io.socket_waking=0;
    if (!error) { socket_io_progress(t); return; }
    socket_unwatch(t);
    if (t->readiness) {
        md_epoll_wait_cancel(&t->readiness->wait); readiness_reply(t,error); return;
    }
    struct md_ipc_io *op=socket_operation(t);
    if (op->job.started) {
        md_ipc_io_interrupt(op);
        if (op->native_result==-EINTR) op->native_result=error;
        socket_io_progress(t);
    } else socket_complete(t,error);
}

static enum call_restart control_wait_restart(const struct thread *t) {
    if (t->proc.io.transferred || t->execution.request.nr==SYS_splice) return 0;
    const struct md_control_pending *pending=t->control.pending
        ? t->control.pending : t->proc.io.pending;
    if (pending) return pending->retry_preparation ? RESTART_PREPARATION : 0;
    return t->proc.io.executed ? 0 : RESTART_SIGNAL;
}

void control_wait_complete(struct thread *t) {
    long value;
    if (!stopped_result(t,&value) || !shield(t)) return;
    t->phase=DISPATCHING;
    if (t->control.done) {
        t->control.done=0; control_deliver(t,t->control.result); return;
    }
    control_interrupted(t,value);
}

static void control_interrupted(struct thread *t,long value) {
    int interrupted=value==-EINTR || md_syscall_restarting(value);
    /* Providers explicitly admit repeatable preparation, never committed
     * writes/ioctls. Ordinary IO retains its unadmitted/no-prefix rule. */
    enum call_restart retry=interrupted ? control_wait_restart(t) : 0;
    retry_clear(t,RETRY_CONTROL); retry_clear(t,RETRY_PROC_IO);
    if (t->control.pending) { t->control.pending->dispose(t->control.pending); t->control.pending=NULL; }
    CHECK(!md_proc_file_io_cancel(&t->proc.io));
    t->proc.waiting=0;
    control_deliver(t,retry ? retry : interrupted ? -EINTR : value<0 ? value : -EIO);
}

int control_wait_exit(struct thread *t,pid_t pid) {
    if (t->phase==CONTROL_WAIT_ENTRY) {
        if (t->control.done) {
            wait_cancel_entry(t,CONTROL_WAITING);
            return 1;
        }
        if (!restore_mask(t)) return 1;
        t->phase=CONTROL_WAITING; resume(pid,0); return 1;
    }
    if (t->phase==CONTROL_WAITING) { control_wait_complete(t); return 1; }
    return 0;
}

int socket_wait_exit(struct thread *t,pid_t pid) {
    if (t->phase==SOCKET_WAIT_ENTRY) {
        if (t->io.socket_waking) {
            t->execution.wait_error=0;
            wait_cancel_entry(t,SOCKET_WAITING);
            return 1;
        }
        int error=socket_watch(t);
        if (error) {
            t->execution.wait_error=error;
            wait_cancel_entry(t,SOCKET_WAITING);
            return 1;
        }
        if (t->readiness) {
            struct md_buffer_span span;
            CHECK(!md_buffer_span(&t->fs.buffer,&span));
            struct md_epoll_frame *f=span.data;
            if (f->masked) {
                struct user_pt_regs regs;
                if (!registers(pid,&regs)) return 1;
                regs.regs[3]=md_memory_buffer_address(t->memory,&t->fs.buffer)+offsetof(struct md_epoll_frame,mask);
                if (!set_registers(pid,&regs)) return 1;
            }
        }
        if (!restore_mask(t)) return 1;
        t->phase=SOCKET_WAITING; resume(pid,0); return 1;
    }
    if (t->phase==SOCKET_WAITING) { socket_wait_complete(t); return 1; }
    return 0;
}

int ipc_wait_exit(struct thread *t,pid_t pid) {
    if (t->phase==IPC_WAIT_ENTRY) {
        int error=ipc_watch(t);
        if (error) {
            t->io.cancel=error;
            wait_cancel_entry(t,IPC_WAITING);
            return 1;
        }
        if (!restore_mask(t)) return 1;
        t->phase=IPC_WAITING; resume(pid,0); return 1;
    }
    if (t->phase==IPC_WAITING) {
        ipc_wait_complete(t); return 1;
    }
    return 0;
}
