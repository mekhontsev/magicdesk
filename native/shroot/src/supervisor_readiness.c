#define _GNU_SOURCE
#include "adapter_buffer.h"
#include "control_epoll.h"
#include "control_file.h"
#include "cooperative_channel.h"
#include "cooperative_native.h"
#include "epoll_calls.h"
#include "epoll_wait.h"
#include "event_signal.h"
#include "event_wait.h"
#include "fd_admission.h"
#include "fd_capture.h"
#include "fd_install.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "fs_buffer.h"
#include "guest_execution.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "ipc_io_job.h"
#include "linux_abi.h"
#include "poll_calls.h"
#include "poll_wait.h"
#include "queue_signal.h"
#include "received_fds.h"
#include "resource_order.h"
#include "signal_pending.h"
#include "signal_protocol.h"
#include "signal_view.h"
#include "supervisor_actors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_readiness.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "syscall_result.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include <fcntl.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/timerfd.h>

static int readiness_frame(struct thread *t,struct md_buffer_span *span);
static int readiness_restore(struct thread *t,int continuation,long result);
static void readiness_poll_elapsed(struct thread *t,struct md_poll_frame *frame);
static void readiness_capture(struct thread *t,unsigned command,long value);
static void readiness_begin(struct thread *t,const struct user_pt_regs *r);
static void readiness_poll_load(struct thread *t);
static void readiness_park(struct thread *t,uintptr_t address);
static void readiness_task_progress(struct thread *t,int completed,long value);
static void readiness_admission_release(struct readiness_owner *o,int commit);
static void readiness_delivery_release(struct readiness_owner *o);
static void readiness_retired(struct thread *t,long result);
static void readiness_request_loan(struct thread *t);
static void readiness_selected(struct thread *t,long result);
static int readiness_inventory(struct readiness_owner *o);
static void readiness_start_stopped(struct thread *t);
static void readiness_shared_done(struct thread *t,long result);
static int readiness_shared(struct thread *t);
static void readiness_activated(struct md_fd_object *queue);
static void readiness_native_call(struct thread *t);
static void readiness_delivery_start(struct thread *t);
static void readiness_duplicate_done(struct thread *t,long result);

static void readiness_loan_publish(struct readiness_loan *loan,long result) {
    md_fd_origins_finish(&loan->publication,(int)result);
    if (result>=0) loan->retirement->number=loan->number=(int)result;
}

int readiness_waiting(const struct thread *t) {
    return t->readiness && (t->phase==EPOLL_WAIT || (t->entry.execution && t->entry.waiting));
}

struct readiness_owner *readiness_activation;

unsigned readiness_admission_purpose(const struct thread *t,const struct seccomp_data *call) {
    const struct readiness_owner *o=t->readiness;
    if (!o || o->done || call->nr!=(long)t->execution.request.nr) return 0;
    for (unsigned i=0;i<6;++i) if (call->args[i]!=t->execution.request.args[i]) return 0;
    if (o->loans[o->lending].retirement && o->loans[o->lending].number<0) return MD_FS_BUFFER_EPOLL_LOAN;
    if (!o->received && md_fd_order_state(&o->files)==MD_RESOURCE_ORDER_RUNNING)
        return MD_FS_BUFFER_EPOLL_ADMIT;
    return 0;
}

void readiness_unwatch(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (o && o->io_waiting) {
        CHECK(!epoll_ctl(socket_events,EPOLL_CTL_DEL,md_ipc_io_job_event(&o->wait.job),NULL));
        o->io_waiting=0;
    }
}

static void readiness_admission_release(struct readiness_owner *o,int commit) {
    if (o->reserved) {
        CHECK(!md_control_epoll_admit_finish(md_control_epoll_from_object(o->queue),commit));
        o->reserved=0;
    }
    md_fd_admission_release(o->admission); o->admission=NULL;
    if (readiness_activation==o) {
        readiness_activation=NULL;
        CHECK(!md_event_signal(fd_ready));
    }
}

static void readiness_delivery_release(struct readiness_owner *o) {
    if (o->publication_memory.queue && md_resource_order_cancel(&o->publication_memory)==1)
        CHECK(!md_resource_order_complete(&o->publication_memory));
    if (o->delivery_claimed) {
        md_control_epoll_task_delivery_finish(md_control_epoll_from_object(o->queue),0);
        o->delivery_claimed=0;
        CHECK(!md_event_signal(fd_ready));
    }
    o->delivery_waiting=0;
}

void readiness_evict_park(struct thread *t,int finished,long result) {
    struct readiness_owner *o=t->readiness;
    readiness_delivery_release(o);
    md_epoll_wait_cancel(&o->wait);
    retry_clear(t,RETRY_READINESS);
    o->parked=1; t->phase=DISPATCHING;
    if (finished) { o->finishing=1; o->finish_result=result; }
    else if (o->wait.task_count) { o->finishing=1; o->finish_result=o->wait.task_count; }
    else if (o->loans[READINESS_TASK_QUEUE].number>=0) md_epoll_wait_resample(&o->wait);
    CHECK(!md_event_signal(fd_ready));
}

void readiness_release(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (!o) return;
    readiness_delivery_release(o);
    for (unsigned n=0;n<READINESS_QUEUES;++n) {
        struct readiness_loan *loan=&o->loans[n];
        if (loan->publication.table) {
            CHECK(t->entry.effect==readiness_duplicate_done);
            unsigned state=entry_effect_reconcile(t);
            if (state==MD_NATIVE_DONE) readiness_loan_publish(loan,t->entry.control->native.result);
            else {
                readiness_loan_publish(loan,-ECANCELED);
                /* An unknown allocating effect cannot be retired by guessing
                 * its slot. Kernel table teardown is the remaining owner. */
                if (state!=MD_NATIVE_CANCELLED && !cancelling) {
                    fprintf(stderr,"Shroot: unobserved temporary FD allocation in task %d\n",t->pid);
                    cancel_tree(125);
                }
            }
            t->entry.effect=NULL; t->entry.waiting=0;
        }
        CHECK(!md_fd_install_cancel(&loan->delivery));
        if (!loan->retirement) continue;
        if (loan->number>=0) loan_retire(loan->retirement,NULL,NULL,NULL);
        else {
            md_fd_loan_end(loan->retirement->loan);
            md_fd_loan_release(loan->retirement->loan);
            md_fd_origins_unpin(loan->retirement->loan_table);
            free(loan->retirement);
        }
        loan->retirement=NULL;
    }
    if (o->task_started) md_control_epoll_task_abandon(o->task_control);
    else md_control_epoll_task_finish(o->task_control,-ECANCELED);
    if (o->task_control) CHECK(!md_event_signal(fd_ready));
    readiness_unwatch(t);
    md_epoll_wait_cancel(&o->wait);
    md_poll_wait_free(o->poll);
    md_fd_capture_cancel(&o->capture);
    md_received_fds_release(o->received);
    readiness_admission_release(o,0);
    if (o->files.table && md_fd_order_cancel(&o->files)==1) CHECK(!md_fd_order_complete(&o->files));
    md_fd_object_release(o->target); md_fd_object_release(o->queue);
    free(o->detached); free(o); t->readiness=NULL;
}

static int readiness_frame(struct thread *t,struct md_buffer_span *span) {
    if (!t->readiness->detached) return md_buffer_span(&t->fs.buffer,span);
    *span=(struct md_buffer_span){.data=t->readiness->detached,.size=sizeof(*t->readiness->detached)};
    return 0;
}

static int readiness_restore(struct thread *t,int continuation,long result) {
    struct readiness_owner *o=t->readiness;
    if (!o->detached) return 1;
    int error=md_buffers_take_sized(md_memory_buffers(t->memory),sizeof(*o->detached),&t->fs.buffer);
    if (error==-EAGAIN) {
        o->restoring=continuation; o->restore_result=result;
        retry_wait(t,RETRY_READINESS); return 0;
    }
    if (error) { readiness_reply(t,error); return 0; }
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->fs.buffer,&span));
    memcpy(span.data,o->detached,sizeof(*o->detached));
    free(o->detached); o->detached=NULL; o->restoring=READINESS_ATTACHED;
    return 1;
}

static void readiness_poll_elapsed(struct thread *t,struct md_poll_frame *frame) {
    int64_t now=md_event_now(),started=t->readiness->poll_started;
    frame->elapsed=started>=0 && now>=started ? now-started : 0;
}

static void readiness_retired(struct thread *t,long result) {
    struct readiness_owner *o=t->readiness;
    readiness_reply(t,result ? result : o->finish_result);
}

void readiness_reply(struct thread *t,long result) {
    struct readiness_owner *o=t->readiness;
    readiness_delivery_release(o);
    for (unsigned n=0;n<READINESS_QUEUES;++n) {
        struct readiness_loan *loan=&o->loans[n];
        if (!loan->retirement || loan->number<0) continue;
        o->finishing=1; o->finish_result=result;
        struct task_action *retirement=loan->retirement;
        loan->retirement=NULL; loan->number=-1;
        loan_retire(retirement,t,o->files.table ? &o->files : NULL,readiness_retired); return;
    }
    retry_clear(t,RETRY_READINESS);
    readiness_admission_release(o,0);
    if (o->files.table && md_fd_order_cancel(&o->files)==1) CHECK(!md_fd_order_complete(&o->files));
    o->done=1; t->phase=DISPATCHING;
    if (!t->entry.control) buffer_capture(t,&o->continuation);
    if (o->poll && result==MD_EPOLL_NATIVE && !t->entry.execution) {
        /* Classification consumed input only. There is nothing to copy back
         * before the caller's native wait, so finish without an adapter ACK. */
        dispatch_complete(t,buffer_result_finish(t,0,MD_CALL_NATIVE));
        adapter_progress(); return;
    }
    if (o->poll && result!=MD_EPOLL_NATIVE) {
        struct md_buffer_span span;
        if (!md_buffer_span(&t->fs.buffer,&span)) readiness_poll_elapsed(t,span.data);
    }
    if (o->detached) {
        if (!t->entry.execution && (result<=0 || o->detached->native_output)) {
            dispatch_complete(t,buffer_result_finish(t,0,result));
            adapter_progress(); return;
        }
        if (!readiness_restore(t,READINESS_RESTORE_REPLY,result)) return;
    }
    buffer_reply(t,result);
}

void readiness_deliver(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    struct readiness_loan *loan=&o->loans[o->lending];
    retry_clear(t,RETRY_READINESS);
    int installed=md_fd_install_progress(&loan->delivery);
    if (installed==MD_FD_INSTALL_WAIT) { retry_wait(t,RETRY_READINESS); return; }
    CHECK(!loan->delivery.retire_error);
    if (installed==MD_FD_INSTALL_CANCELLED) return;
    if (installed<0) {
        struct seccomp_notif_resp reply={.id=loan->delivery.notification,.error=installed};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    } else loan->retirement->number=loan->number=installed;
}

static int readiness_prepare_loan(struct thread *t,unsigned index) {
    struct readiness_owner *o=t->readiness;
    struct readiness_loan *loan=&o->loans[index];
    if (!loan->retirement) {
        struct task_action *s=calloc(1,sizeof(*s));
        if (!s) return -ENOMEM;
        s->loan=md_fd_loan_new(t->resources.files);
        if (!s->loan) { free(s); return -ENOMEM; }
        s->loan_table=md_fd_origins_pin(t->resources.files);
        s->number=-1; loan->retirement=s;
    }
    return 0;
}

static void readiness_request_loan(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (!readiness_restore(t,READINESS_RESTORE_LOAN,0)) return;
    int error=readiness_prepare_loan(t,o->lending);
    if (error) { readiness_reply(t,error); return; }
    t->phase=DISPATCHING;
    if (!t->entry.control) buffer_capture(t,&o->continuation);
    buffer_reply(t,MD_EPOLL_LOAN);
}

static void readiness_duplicate_done(struct thread *t,long result) {
    struct readiness_owner *o=t->readiness;
    struct readiness_loan *loan=&o->loans[READINESS_NATIVE_QUEUE];
    if (t->entry.effect_cancelled) result=RESTART_SIGNAL;
    readiness_loan_publish(loan,result);
    if (result<0) { readiness_reply(t,result); return; }
    CHECK(!md_fd_order_complete(&o->files));
    o->native_caller=1;
    buffer_reply(t,MD_EPOLL_CAPTURED);
}

static void readiness_duplicate(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    struct readiness_loan *loan=&o->loans[READINESS_NATIVE_QUEUE];
    int error=readiness_prepare_loan(t,READINESS_NATIVE_QUEUE);
    if (!error) error=md_fd_origins_prepare_object(o->files.table,o->queue,FD_CLOEXEC,&loan->publication);
    if (!error) error=md_fd_origins_prepare_loan(&loan->publication,loan->retirement->loan);
    if (error) {
        md_fd_origins_finish(&loan->publication,-1);
        readiness_reply(t,error); return;
    }
    /* Keep the exact OFD in its own caller. The table turn covers duplication
     * and publication; the ordinary loan owner protects concurrent close,
     * fork, signal cancellation and eventual retirement. No SCM/ADDFD trip. */
    uint64_t args[6]={t->execution.request.args[0],F_DUPFD_CLOEXEC,0};
    entry_effect(t,SYS_fcntl,args,readiness_duplicate_done);
}

void readiness_loan_closed(pid_t pid,long result) {
    struct thread *waiter=find_thread(pid);
    if (waiter && waiter->readiness && waiter->readiness->parked) {
        struct readiness_owner *o=waiter->readiness;
        o->evicting=o->parked=0;
        if (result || o->finishing) readiness_reply(waiter,result ? result : o->finish_result);
        else readiness_progress(waiter);
    }
}

static void readiness_selected(struct thread *t,long result) {
    struct readiness_owner *o=t->readiness;
    SUPERVISOR_TRACE("PROBE readiness-selected pid=%d result=%ld\n",t->pid,result);
    if (result==-ESRCH) {
        int alive=0;
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->born && p->descriptors.selector==o->request.group) alive=1;
        if (!alive) result=md_fd_admission_retire(o->admission,o->request.group);
        else { retry_wait(t,RETRY_READINESS); CHECK(!md_event_signal(fd_ready)); return; }
    } else {
        result=md_fd_admission_observed(o->admission,&o->request,result);
        if (!result) for (struct thread *p=threads;p;p=p->next)
            if (p->pid && p->descriptors.selector==o->request.group) cooperative_routes_publish(p);
    }
    if (result) readiness_reply(t,result);
    else { retry_wait(t,RETRY_READINESS); CHECK(!md_event_signal(fd_ready)); }
}

static int readiness_inventory(struct readiness_owner *o) {
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->born && p->resources.files && p->descriptors.selector) {
        int error=md_fd_admission_add(o->admission,p->resources.files,p->descriptors.selector);
        if (error) return error;
    }
    return 0;
}

static void readiness_start_stopped(struct thread *t) {
    t->readiness->continuation=t->fs.registers;
    readiness_start(t);
}

static void readiness_shared_done(struct thread *t,long result) {
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->fs.buffer,&span));
    ((struct md_epoll_frame *)span.data)->result=result;
    readiness_reply(t,MD_EPOLL_DONE);
}

static int readiness_shared(struct thread *t) {
    if (!t->entry.control) return 0;
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->fs.buffer,&span));
    struct md_epoll_frame *frame=span.data;
    struct md_control_file provider=md_fd_object_readiness(o->target);
    if (!o->operation && t->entry.execution && !frame->masked) return 0;
    /* An admitted virtual subscription is supervisor-owned state. Reuse its
     * normal ctl path without manufacturing a stopped caller. Cold selector
     * admission, task-affine targets and exclusive native capture retain
     * their explicit context owner. */
    if (o->operation && !o->task_target && provider.ops && provider.ops->poll
            && md_control_epoll_descriptor(md_control_epoll_from_object(o->queue))>=0
            && !(o->operation==EPOLL_CTL_ADD && (o->event.events&EPOLLEXCLUSIVE)
                && provider.ops->poll_subscribe)) return 0;
    int native=o->operation ? !o->task_target && (!provider.ops || !provider.ops->poll)
        : !frame->masked && frame->timed && !frame->timeout.tv_sec && !frame->timeout.tv_nsec
            && md_control_epoll_native_wait(o->queue);
    if (native) {
        uint64_t args[6]; memcpy(args,t->execution.request.args,sizeof(args));
        long number=t->execution.request.nr;
        if (number==SYS_epoll_pwait2) { number=SYS_epoll_pwait; args[3]=0; }
        if (!o->operation) { frame->native_output=1; args[2]=frame->capacity; }
        entry_effect(t,number,args,readiness_shared_done);
    } else {
        /* Classification has no effect. Keep the table turn, then continue
         * with the actual C context for native waits, captures or admission. */
        md_fd_object_release(o->queue); o->queue=NULL;
        md_fd_object_release(o->target); o->target=NULL;
        o->classified=0; o->task_target=0;
        CHECK(entry_require_stop(t,o->operation ? MD_CONTEXT_DESCRIPTOR : MD_CONTEXT_SIGNAL,
            0,readiness_start_stopped));
    }
    return 1;
}

static void readiness_activated(struct md_fd_object *queue) {
    /* Cold admission only: wake the exact waits that entered before this
     * queue gained its virtual transport. Their owner retains the deadline,
     * native result and temporary signal mask. No other task is stopped. */
    for (struct thread *p=threads;p;p=p->next) {
        struct readiness_owner *o=p->readiness;
        if (p->pid && o && o->queue==queue && readiness_waiting(p)
                && !p->io.socket_waking) {
            p->io.socket_waking=1;
            tracee_request(p,PTRACE_INTERRUPT,NULL,NULL,"epoll-cold-admission");
        }
    }
}

void readiness_start(struct thread *t) {
    retry_clear(t,RETRY_READINESS);
    struct readiness_owner *o=t->readiness;
    if (o->delivery_waiting) { readiness_delivery_start(t); return; }
    if (o->restoring) {
        int continuation=o->restoring; long value=o->restore_result;
        if (!readiness_restore(t,continuation,value)) return;
        if (continuation==READINESS_RESTORE_LOAN) readiness_request_loan(t);
        else if (continuation==READINESS_RESTORE_REPLY) readiness_reply(t,value);
        else {
            int completed=o->restore_completed; o->restore_completed=0;
            readiness_task_progress(t,completed,value);
        }
        return;
    }
    if (o->loans[o->lending].delivery.files.table || o->loans[o->lending].delivery.borrowed) {
        readiness_deliver(t); return;
    }
    SUPERVISOR_TRACE("PROBE readiness-start pid=%d classified=%d admission=%d sealed=%d files=%d activation=%d\n",
        t->pid,o->classified,o->admission!=NULL,o->sealed,md_fd_order_state(&o->files),
        readiness_activation && readiness_activation!=o);
    int error=0;
    struct md_control_epoll *queue;
    if (!o->classified) {
        if (!o->files.table) {
            error=md_fd_order_begin(t->resources.files,&o->files,fd_wakeup);
            if (error>0) error=0;
        }
        if (error) { readiness_reply(t,error); return; }
        enum md_resource_order_state state=md_fd_order_state(&o->files);
        if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(&o->files));
        else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_READINESS); return; }
        if (o->poll) {
            struct md_buffer_span span;
            error=md_buffer_span(&t->fs.buffer,&span);
            if (error) { readiness_reply(t,error); return; }
            struct md_poll_frame *f=span.data;
            unsigned count=o->poll_count>MD_POLL_CHUNK ? MD_POLL_CHUNK : o->poll_count;
            if (f->offset || f->count!=count) { readiness_reply(t,-EINVAL); return; }
            o->classified=1;
            readiness_poll_load(t); return;
        }
        o->queue=md_fd_origins_object(t->resources.files,(int)t->execution.request.args[0],NULL);
        queue=md_control_epoll_from_object(o->queue);
        o->classified=1;
        if (o->operation) {
            o->target=md_fd_origins_object(t->resources.files,o->number,NULL);
            struct md_control_file provider=md_fd_object_readiness(o->target);
            if (md_control_epoll_from_object(o->target) && o->operation!=EPOLL_CTL_DEL
                    && (o->event.events&EPOLLEXCLUSIVE)) { readiness_reply(t,-EINVAL); return; }
            o->task_target=o->target && o->target->type->task_poll && o->target->type->task_poll_epoch;
            if (md_control_epoll_from_object(o->target)
                    && md_fd_object_selectors(o->target)) { readiness_reply(t,-EOPNOTSUPP); return; }
            if (readiness_shared(t)) return;
            if (!o->task_target && (!provider.ops || !provider.ops->poll)) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
            if (!queue) { readiness_reply(t,-EINVAL); return; }
            if (!o->task_target && o->operation==EPOLL_CTL_ADD
                    && (o->event.events&EPOLLEXCLUSIVE) && provider.ops->poll_subscribe) {
                /* Capture under the existing table turn; only cold layered
                 * exclusive ADD needs a native source. Ordinary waits do not. */
                int captured=md_fd_capture_native(t->pid,&o->number,1,&o->received);
                if (captured<0) { readiness_reply(t,captured); return; }
                if (!captured) { readiness_capture(t,MD_BUFFER_EPOLL_CAPTURE_READY,0); return; }
            }
            CHECK(!md_fd_order_complete(&o->files));
            if (!o->task_target && o->operation!=EPOLL_CTL_ADD) {
                readiness_reply(t,md_control_epoll_ctl(queue,o->operation,o->number,o->target,provider,&o->event,-1)); return;
            }
        } else {
            if (readiness_shared(t)) return;
            if (!queue && !t->signals.host) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
            struct md_epoll_frame *frame;
            struct md_buffer_span span;
            error=md_buffer_span(&t->fs.buffer,&span);
            if (error) { readiness_reply(t,error); return; }
            frame=span.data;
            int masked=t->signals.host && frame->masked;
            if (!queue && !masked) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
            if (md_control_epoll_native_wait(o->queue)
                    && !masked && frame->timed && !frame->timeout.tv_sec && !frame->timeout.tv_nsec) {
                readiness_reply(t,MD_EPOLL_NATIVE); return;
            }
            if (md_fd_origins_exclusive(&o->files)) {
                o->native_caller=1;
                CHECK(!md_fd_order_complete(&o->files));
                buffer_reply(t,MD_EPOLL_CAPTURED); return;
            }
            if (t->entry.control && !checkpoint_restricted(t->pid)) {
                readiness_duplicate(t); return;
            }
            readiness_capture(t,MD_BUFFER_EPOLL_CAPTURE_READY,0); return;
        }
    }
    queue=md_control_epoll_from_object(o->queue);
    if (!o->admission) {
        /* Serialize cold admissions only after releasing the caller's table
         * turn. Earlier readiness operations must drain those same turns;
         * parking them behind an admission would create a wait cycle. */
        if (readiness_activation && readiness_activation!=o) { retry_wait(t,RETRY_READINESS); return; }
        error=md_control_epoll_admit_begin(queue);
        if (error<0) { readiness_reply(t,error); return; }
        o->reserved=error;
        if (error) {
            readiness_activation=o;
            o->admission=md_fd_admission_new(o->queue,(1U<<MD_FD_SELECTOR_EPOLL)|(1U<<MD_FD_SELECTOR_EPOLL_TARGET));
            error=o->admission ? readiness_inventory(o) : -ENOMEM;
            if (!error) error=md_fd_admission_begin(o->admission,fd_wakeup);
            if (error) { readiness_reply(t,error); return; }
        }
    }
    if (o->admission && !o->sealed) {
        error=md_fd_admission_prepare(o->admission);
        SUPERVISOR_TRACE("PROBE readiness-admission pid=%d prepared=%d\n",t->pid,error);
        if (error==1) {
            error=readiness_inventory(o);
            if (!error) error=md_fd_admission_prepare(o->admission);
        }
        if (!error) { retry_wait(t,RETRY_READINESS); return; }
        if (error==1) error=md_fd_admission_seal(o->admission);
        if (error) { readiness_reply(t,error); return; }
        o->sealed=1;
    }
    if (o->admission) {
        error=md_fd_admission_next(o->admission,&o->request);
        SUPERVISOR_TRACE("PROBE readiness-selector pid=%d result=%d fd=%d kind=%d\n",t->pid,error,o->request.number,o->request.kind);
        if (error==1) {
            struct task_action *s=task_action_new(t);
            if (!s) { readiness_reply(t,-ENOMEM); return; }
            s->continuation=o->continuation;
            s->kind=o->request.kind; s->number=o->request.number;
            s->selected_group=md_fd_selector_fork(o->request.group,1); s->complete=readiness_selected;
            task_action_start(t); return;
        }
        if (error!=2) { readiness_reply(t,error); return; }
        error=md_control_epoll_admit_finish(queue,1); o->reserved=0;
        if (error) { readiness_reply(t,error); return; }
        readiness_activated(o->queue);
        readiness_admission_release(o,0);
    }
    struct md_control_file provider=md_fd_object_readiness(o->target);
    if (o->task_target) {
        /* Reacquire after cold selector admission. Native ctl must use the
         * exact target slot observed above, not a descriptor reused meanwhile. */
        if (!o->files.table) {
            error=md_fd_order_begin(t->resources.files,&o->files,fd_wakeup);
            if (error<0) { readiness_reply(t,error); return; }
        }
        if (md_fd_order_state(&o->files)==MD_RESOURCE_ORDER_QUEUED) { retry_wait(t,RETRY_READINESS); return; }
        if (md_fd_order_state(&o->files)==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(&o->files));
        struct md_fd_object *target=md_fd_origins_object(t->resources.files,o->number,NULL);
        struct md_fd_object *current=md_fd_origins_object(t->resources.files,(int)t->execution.request.args[0],NULL);
        int same=target==o->target && current==o->queue;
        md_fd_object_release(target); md_fd_object_release(current);
        if (!same) { readiness_reply(t,-EBADF); return; }
        struct md_buffer_span span;
        error=md_buffer_span(&t->fs.buffer,&span);
        struct md_control_actor actor={.pid=t->pid,.signals=t->signals.pending};
        if (!error) error=md_control_epoll_task_begin(queue,o->operation,o->number,o->target,&actor,
            &o->event,&((struct md_epoll_frame *)span.data)->event,&o->task_control);
        if (error==-EAGAIN) {
            CHECK(!md_fd_order_complete(&o->files)); retry_wait(t,RETRY_READINESS); return;
        }
        if (error) { readiness_reply(t,error); return; }
        readiness_activated(o->queue);
        o->native_caller=1; buffer_reply(t,MD_EPOLL_CAPTURED); return;
    }
    readiness_reply(t,md_control_epoll_ctl(queue,o->operation,o->number,o->target,provider,&o->event,
        o->received ? o->received->fds[0] : -1));
}

static void readiness_capture(struct thread *t,unsigned command,long value) {
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct md_epoll_frame *f=error ? NULL : span.data;
    if (!error && command==MD_BUFFER_EPOLL_CAPTURE_SENT) error=md_fd_capture_sent(&o->capture,value);
    if (!error && command==MD_BUFFER_EPOLL_CAPTURE_OBSERVED)
        error=md_fd_capture_observed(&o->capture,o->files.table,&f->capture);
    if (!error) error=md_fd_capture_progress(&o->capture,o->files.table,t->tgid,1,&f->capture);
    if (error>0) { buffer_reply(t,error); return; }
    if (!error) {
        o->received=md_fd_capture_take(&o->capture);
        if (!o->received) error=-EPROTO;
    }
    if (error) { readiness_reply(t,error); return; }
    CHECK(!md_fd_order_complete(&o->files));
    if (o->operation) { readiness_start(t); return; }
    o->native_caller=1;
    buffer_reply(t,MD_EPOLL_CAPTURED);
}

static void readiness_begin(struct thread *t,const struct user_pt_regs *r) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct readiness_owner *o=error ? NULL : calloc(1,sizeof(*o));
    if (!error && !o) error=-ENOMEM;
    if (error) { buffer_reply(t,error); return; }
    if (r) o->continuation=*r;
    for (unsigned n=0;n<READINESS_QUEUES;++n) o->loans[n].number=-1;
    if (!t->seccomp.policy && (t->execution.request.nr==SYS_epoll_pwait || t->execution.request.nr==SYS_epoll_pwait2
            || t->execution.request.nr==SYS_epoll_ctl)) {
        /* Reuse the admitted call for ADDFD, not an unrelated service syscall
         * which an application filter may deny. The owner validates replay. */
        struct md_epoll_frame *frame=span.data;
        frame->admission.number=t->execution.request.nr;
        for (unsigned i=0;i<6;++i) frame->admission.value[i]=t->execution.request.args[i];
    }
    if (t->execution.epoll_started) {
        md_epoll_wait_initialize(&o->wait,span.data);
        struct md_epoll_frame *frame=span.data;
        if (frame->timed)
            o->wait.deadline=t->execution.epoll_started+frame->timeout.tv_sec*1000000000LL+frame->timeout.tv_nsec;
        t->execution.epoll_started=0;
    }
    if (t->execution.request.nr==SYS_ppoll || t->execution.request.nr==SYS_pselect6 || t->execution.request.nr==SYS_rt_sigsuspend) {
        struct md_poll_frame *f=span.data;
        o->poll_count=f->total;
        o->poll_started=md_event_now();
        if (md_poll_bytes(f->total)>span.size) error=-EINVAL;
        else {
            o->poll=md_poll_wait_new(f->total,f->select);
            if (!o->poll) error=-ENOMEM;
        }
    }
    if (t->execution.request.nr==SYS_epoll_ctl) {
        o->operation=(int)t->execution.request.args[1]; o->number=(int)t->execution.request.args[2];
        o->event=((struct md_epoll_frame *)span.data)->event;
        if (o->operation!=EPOLL_CTL_ADD && o->operation!=EPOLL_CTL_MOD && o->operation!=EPOLL_CTL_DEL) error=-EINVAL;
    }
    t->readiness=o;
    if (error) readiness_reply(t,error); else readiness_start(t);
}

static void readiness_poll_load(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct md_poll_frame *f=error ? NULL : span.data;
    if (!error) error=md_poll_wait_load(o->poll,o->files.table,f);
    if (error) { readiness_reply(t,error); return; }
    o->poll_loaded+=f->count;
    if (o->poll_loaded<o->poll_count) {
        f->offset=o->poll_loaded; f->count=o->poll_count-o->poll_loaded;
        if (f->count>MD_POLL_CHUNK) f->count=MD_POLL_CHUNK;
        buffer_reply(t,MD_POLL_LOAD); return;
    }
    if (!t->entry.execution && !md_poll_wait_virtual(o->poll) && !(t->signals.host && f->wait.masked)) {
        readiness_reply(t,MD_EPOLL_NATIVE); return;
    }
    o->native_caller=1;
    CHECK(!md_fd_order_complete(&o->files));
    buffer_reply(t,MD_EPOLL_CAPTURED);
}

static void readiness_effect_done(struct thread *t,long result) {
    socket_unwatch(t);
    md_epoll_wait_cancel(&t->readiness->wait);
    int waking=t->io.socket_waking;
    t->io.socket_waking=0;
    if (waking && result==-EINTR) result=0;
    readiness_task_progress(t,1,result);
}

static void readiness_native_call(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (t->entry.control) {
        int error=md_epoll_wait_observe(&o->wait,md_control_epoll_from_object(o->queue));
        if (!error && o->wait.job.started) error=socket_watch(t);
        if (error) { readiness_reply(t,error); return; }
        uint64_t args[6]; memcpy(args,o->native_call.value,sizeof(args));
        entry_effect(t,o->native_call.number,args,readiness_effect_done);
        return;
    }
    struct user_pt_regs regs=o->continuation;
    regs.pc=abi.task_call; regs.regs[8]=o->native_call.number;
    memcpy(regs.regs,o->native_call.value,sizeof(o->native_call.value));
    if (set_registers(t->pid,&regs)) { t->phase=EPOLL_CALL; resume(t->pid,0); }
}

static void readiness_delivery_start(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    retry_clear(t,RETRY_READINESS);
    o->delivery_waiting=1;
    if (!o->delivery_claimed) {
        int ready=md_control_epoll_task_delivery_begin(md_control_epoll_from_object(o->queue));
        if (ready<0) { readiness_reply(t,ready); return; }
        if (!ready) { retry_wait(t,RETRY_READINESS); return; }
        o->delivery_claimed=1;
    }
    if (!o->publication_memory.queue) {
        int ready=md_memory_order_begin(t->memory,&o->publication_memory,fd_wakeup);
        if (ready<0) { readiness_reply(t,ready); return; }
    }
    if (md_resource_order_state(&o->publication_memory)==MD_RESOURCE_ORDER_QUEUED) {
        retry_wait(t,RETRY_READINESS); return;
    }
    CHECK(!md_resource_order_start(&o->publication_memory));
    o->delivery_waiting=0;
    /* Nonblocking native output validation and token translation share one
     * mapping turn. No memory turn survives a blocking wait or an FD loan. */
    readiness_native_call(t);
}

static void readiness_park(struct thread *t,uintptr_t address) {
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    if (!error) {
        o->detached=malloc(sizeof(*o->detached));
        if (!o->detached) error=-ENOMEM;
    }
    o->park_pending=0;
    if (error) { readiness_reply(t,error); return; }
    memcpy(o->detached,span.data,sizeof(*o->detached));
    CHECK(!buffer_release(t));
    if (o->native_call.number==SYS_ppoll) {
        o->native_call.value[0]=address+offsetof(struct md_epoll_park,queues);
        if (o->native_call.value[2]) o->native_call.value[2]=address+offsetof(struct md_epoll_park,timeout);
        if (o->native_call.value[3]) o->native_call.value[3]=address+offsetof(struct md_epoll_park,mask);
    } else {
        CHECK(o->native_call.number==SYS_epoll_pwait || o->native_call.number==SYS_epoll_pwait2);
        if (o->native_call.number==SYS_epoll_pwait2 && o->native_call.value[3])
            o->native_call.value[3]=address+offsetof(struct md_epoll_park,timeout);
        if (o->native_call.value[4]) o->native_call.value[4]=address+offsetof(struct md_epoll_park,mask);
    }
    readiness_native_call(t);
}

static void readiness_task_progress(struct thread *t,int completed,long value) {
    struct readiness_owner *o=t->readiness;
    if (o->detached && o->loans[READINESS_TASK_QUEUE].number>=0) {
        o->restore_completed=completed;
        if (!readiness_restore(t,READINESS_RESTORE_WAIT,value)) return;
        o->restore_completed=0;
    }
    struct md_buffer_span span;
    long result=readiness_frame(t,&span);
    uintptr_t address=md_memory_buffer_address(t->memory,&t->fs.buffer);
    struct md_control_actor actor={.pid=t->pid,.signals=t->signals.pending};
    if (o->task_control) {
        if (completed) {
            md_control_epoll_task_finish(o->task_control,value); o->task_control=NULL;
            CHECK(!md_event_signal(fd_ready));
            CHECK(!md_fd_order_complete(&o->files));
            if (!value) readiness_activated(o->queue);
            readiness_reply(t,value); return;
        }
        if (result) { readiness_reply(t,result); return; }
        o->native_call=(struct md_task_call_arguments){.number=SYS_epoll_ctl,.value={
            o->loans[READINESS_TASK_QUEUE].number,o->operation,o->number,
            address+offsetof(struct md_epoll_frame,event)}};
        o->task_started=1;
        readiness_native_call(t); return;
    }
    int task_descriptor=o->loans[READINESS_TASK_QUEUE].number;
    int native_descriptor=o->loans[READINESS_NATIVE_QUEUE].number;
    if (native_descriptor<0) native_descriptor=(int)t->execution.request.args[0];
    int waiting=result ? 0 : (o->poll ? md_poll_wait_task(o->poll,&o->wait,span.data,address,
            completed,value,&actor,&o->native_call,&result)
        : task_descriptor>=0 ? md_epoll_wait_task_affine(&o->wait,md_control_epoll_from_object(o->queue),
            &actor,span.data,address,native_descriptor,
            task_descriptor,completed,value,&o->native_call,&result)
        : md_epoll_wait_task(&o->wait,md_control_epoll_from_object(o->queue),span.data,address,
            t->execution.request.nr,native_descriptor,
            completed,value,&o->native_call,&result));
    if (o->wait.task_copied) readiness_delivery_release(o);
    if (o->evicting && waiting!=2) {
        readiness_evict_park(t,!waiting,result); return;
    }
    if (!waiting) { readiness_reply(t,result); return; }
    if (waiting==2) {
        o->copy_pending=1; t->phase=DISPATCHING;
        if (!t->entry.control) buffer_capture(t,&o->continuation);
        buffer_reply(t,MD_EPOLL_COPY); return;
    }
    if (!o->poll && task_descriptor>=0 && o->wait.blocking && o->native_call.number==SYS_ppoll) {
        /* The adapter's private stack owns these few wait arguments. Release
         * the shared transport slot before sleeping, including with a mask. */
        o->park_pending=1; t->phase=DISPATCHING;
        if (!t->entry.control) buffer_capture(t,&o->continuation);
        buffer_reply(t,MD_EPOLL_PARK); return;
    }
    if (!o->poll && task_descriptor<0 && md_control_epoll_task_descriptor(md_control_epoll_from_object(o->queue))>=0) {
        o->lending=READINESS_TASK_QUEUE; readiness_request_loan(t); return;
    }
    if (!o->poll) {
        struct md_epoll_frame *frame=span.data;
        int independent=task_descriptor<0 && frame->native_output && !frame->masked
            && (o->native_call.number==SYS_epoll_pwait || !frame->timed);
        if (o->detached && !independent) {
            if (readiness_restore(t,READINESS_RESTORE_WAIT,0)) readiness_task_progress(t,0,0);
            return;
        }
        if (!o->detached && task_descriptor<0 && frame->native_output && !independent
                && (frame->timeout.tv_sec || frame->timeout.tv_nsec)) {
            frame->park=(struct md_epoll_park){.timeout=frame->timeout,.mask=frame->mask};
            o->park_pending=1; t->phase=DISPATCHING;
            if (!t->entry.control) buffer_capture(t,&o->continuation);
            buffer_reply(t,MD_EPOLL_PARK); return;
        }
        if (!o->detached && independent) {
            /* A native wait writes directly to the application's output and
             * uses no transport pointers. Sleeping with a shared pool slot
             * would exhaust it across forked clients and block their wakeups. */
            o->detached=malloc(sizeof(*frame));
            if (!o->detached) { readiness_reply(t,-ENOMEM); return; }
            memcpy(o->detached,frame,sizeof(*frame));
            CHECK(!buffer_release(t));
        }
    }
    if (!o->poll && task_descriptor>=0 && md_epoll_wait_collecting(&o->wait)) readiness_delivery_start(t);
    else readiness_native_call(t);
}

void readiness_progress(struct thread *t) {
    socket_unwatch(t);
    struct readiness_owner *o=t->readiness;
    if (o->received && o->loans[READINESS_NATIVE_QUEUE].number<0) {
        o->lending=READINESS_NATIVE_QUEUE; readiness_request_loan(t); return;
    }
    if (md_control_epoll_task_descriptor(md_control_epoll_from_object(o->queue))>=0
            && o->loans[READINESS_TASK_QUEUE].number<0) {
        o->lending=READINESS_TASK_QUEUE; readiness_request_loan(t); return;
    }
    CHECK(o->native_caller);
    readiness_task_progress(t,0,0);
}

void task_readiness_changed(struct thread *t) {
    struct md_control_actor actor={.pid=t->pid,.signals=t->signals.pending};
    if (readiness_waiting(t) && t->readiness->wait.blocking && !t->io.socket_waking
            && (md_poll_wait_task_ready(t->readiness->poll,&actor)>0
                || md_control_epoll_task_ready(md_control_epoll_from_object(t->readiness->queue),&actor)>0)) {
        t->io.socket_waking=1;
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"task-readiness");
    }
}

static int readiness_interrupt_prepare(struct thread *t) {
    /* The kernel owns an atomic wait's temporary mask until signal return.
     * FD cleanup and queued carriers must not shield this continuation before
     * the next syscall entry proves the signal frame has been committed. */
    int logical=t->signals.host && !t->signals.host_blocked
        && (md_signal_pending_has(t->signals.pending,MD_SIGNAL_PRIVATE)
            || md_signal_pending_has(t->signals.pending,MD_SIGNAL_SHARED));
    if (logical && t->signals.wait_view.active
            && !signal_mask(t,t->signals.wait_view.temporary&~MD_SIGNAL_SYS_BIT,"wait-delivery-mask")) return 0;
    t->signals.return_pending=!logical;
    readiness_release(t); CHECK(!buffer_release(t));
    struct user_pt_regs regs=t->execution.registers; regs.regs[0]=(uint64_t)-EINTR;
    if (!set_registers(t->pid,&regs)) return 0;
    md_memory_stack_return(t->memory,t->execution.stack); t->execution.stack=0;
    t->phase=IDLE; return 1;
}

void readiness_interrupted(struct thread *t) {
    if (readiness_interrupt_prepare(t)) resume(t->pid,0);
}

int readiness_signal_event(struct thread *t,int signal,struct md_task_signal_stop *stop,int restored) {
    if (!readiness_waiting(t)) return 0;
    if (signal_ignored(t,signal,stop)) {
        /* ptrace makes ignored signals interrupt epoll too. Keep the deadline
         * and let the common wait continuation resample after kernel return.
         * A resident effect has no syscall-exit stop: retire its interrupted
         * phase here, or kernel restart can strand the pending wake marker. */
        t->io.socket_waking=1;
        int error=entry_effect_observe(t);
        if (error==-ESRCH) { t->resume_lost=1; return 1; }
        CHECK(!error);
        resume(t->pid,0); return 1;
    }
    if (!t->readiness->signal_pending) return 0;
    if (readiness_interrupt_prepare(t)) {
        stop->has_regs=0;
        deliver_signal(t->pid,signal,restored,stop);
    }
    return 1;
}

int readiness_native_event(struct thread *t,int status) {
    unsigned event=(unsigned)status>>16;
    int signal=WSTOPSIG(status);
    if (t->entry.execution && t->entry.waiting && t->readiness
            && event==PTRACE_EVENT_STOP && signal==SIGTRAP && t->io.socket_waking) {
        int error=entry_effect_observe(t);
        if (error==-ESRCH) { t->resume_lost=1; return 1; }
        CHECK(!error);
        resume(t->pid,0); return 1;
    }
    if (t->phase==WAIT_CANCEL_EXIT && t->execution.wait_resume==EPOLL_CALL) {
        if (event || signal!=(SIGTRAP|0x80)) return 0;
        struct ptrace_syscall_info info={0};
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"epoll-cancel-exit")) return 1;
        struct readiness_owner *o=t->readiness;
        CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT && o && (o->evicting || o->finishing || o->signal_ready || o->resample));
        /* Only a drained exit can lend its continuation to FD retirement.
         * Rewriting an entry to the adapter's PC would re-enter seccomp with
         * the reply in x0 and mistake that value for a service command. */
        t->phase=DISPATCHING; t->execution.wait_resume=IDLE;
        if (o->signal_ready) {
            if (signal_mask(t,t->signals.wait_view.temporary&~MD_SIGNAL_SYS_BIT,"wait-signal-mask"))
                readiness_interrupted(t);
        } else if (o->evicting) readiness_evict_park(t,0,0);
        else if (o->resample) {
            o->resample=0; md_epoll_wait_resample(&o->wait); readiness_task_progress(t,1,0);
        }
        else readiness_reply(t,o->finish_result);
        return 1;
    }
    if (t->phase!=EPOLL_CALL && t->phase!=EPOLL_WAIT) return 0;
    struct user_pt_regs regs;
    if (event==PTRACE_EVENT_SECCOMP) {
        struct ptrace_syscall_info info={0};
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"readiness-admission")) return 1;
        const struct md_task_call_arguments *call=&t->readiness->native_call;
        if (t->phase!=EPOLL_WAIT || info.op!=PTRACE_SYSCALL_INFO_SECCOMP
                || info.instruction_pointer!=abi.task_return
                || info.seccomp.nr!=(unsigned long)call->number
                || memcmp(info.seccomp.args,call->value,sizeof(call->value))) return 0;
        resume(t->pid,0); return 1;
    }
    if (!event && signal==(SIGTRAP|0x80) && t->phase==EPOLL_CALL) {
        struct ptrace_syscall_info info={0};
        if (!registers(t->pid,&regs)
                || !tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"epoll-call-entry")) return 1;
        struct readiness_owner *o=t->readiness;
        int entry=info.op==PTRACE_SYSCALL_INFO_ENTRY && info.instruction_pointer==abi.task_return
            && info.entry.nr==(unsigned long)o->native_call.number
            && !memcmp(info.entry.args,o->native_call.value,sizeof(o->native_call.value));
        if (!entry) {
            fprintf(stderr,"Shroot: readiness entry pid=%d op=%u pc=%#llx expectedPc=%#lx nr=%llu expectedNr=%ld regsPc=%#llx regsNr=%llu waking=%d filter=%d evicting=%d\n",
                t->pid,info.op,(unsigned long long)info.instruction_pointer,(unsigned long)abi.task_return,
                (unsigned long long)info.entry.nr,o->native_call.number,regs.pc,regs.regs[8],
                t->io.socket_waking,t->seccomp.application_filter,o->evicting);
            for (unsigned i=0;i<6;++i)
                fprintf(stderr,"Shroot: readiness argument %u observed=%#llx expected=%#lx register=%#llx\n",
                    i,(unsigned long long)info.entry.args[i],o->native_call.value[i],regs.regs[i]);
        }
        CHECK(entry);
        if (o->evicting) {
            md_epoll_wait_cancel(&o->wait);
            wait_cancel_entry(t,EPOLL_CALL);
            return 1;
        }
        struct md_control_actor actor={.pid=t->pid,.signals=t->signals.pending};
        if (md_poll_wait_task_ready(o->poll,&actor)>0) {
            o->finishing=1; o->finish_result=md_poll_wait_interrupted(o->poll,&actor);
            md_epoll_wait_cancel(&o->wait);
            wait_cancel_entry(t,EPOLL_CALL); return 1;
        }
        if (o->wait.blocking && md_control_epoll_task_ready(md_control_epoll_from_object(o->queue),&actor)>0) {
            o->resample=1;
            md_epoll_wait_cancel(&o->wait); wait_cancel_entry(t,EPOLL_CALL); return 1;
        }
        int error=o->poll ? 0 : md_epoll_wait_observe(&o->wait,md_control_epoll_from_object(o->queue));
        if (!error && o->wait.job.started) error=socket_watch(t);
        if (error) {
            o->finishing=1; o->finish_result=error;
            md_epoll_wait_cancel(&o->wait);
            wait_cancel_entry(t,EPOLL_CALL);
            return 1;
        }
        uint64_t mask=t->execution.mask;
        if (t->signals.host) {
            struct md_buffer_span span;
            int error=readiness_frame(t,&span);
            CHECK(!error);
            struct md_epoll_frame *frame=span.data;
            if (frame->masked) {
                mask=md_signal_view_mask(t->execution.mask,t->signals.host_blocked);
                t->signals.wait_view=(struct md_signal_wait_view){.active=1,
                    .saved=mask,.temporary=frame->mask&md_signal_blockable_mask()};
                t->signals.host_blocked=!!(frame->mask&MD_SIGNAL_SYS_BIT);
                if (!t->signals.host_blocked && (md_signal_pending_has(t->signals.pending,MD_SIGNAL_PRIVATE)
                        || md_signal_pending_has(t->signals.pending,MD_SIGNAL_SHARED))) {
                    o->signal_ready=1; wait_cancel_entry(t,EPOLL_CALL); return 1;
                }
            }
        }
        if (signal_mask(t,mask,"readiness-original-mask")) { t->phase=EPOLL_WAIT; resume(t->pid,0); }
        return 1;
    }
    /* The debugger interrupts the same owned kernel wait as a readiness wake,
     * but asks for original context, not a readiness resample. */
    if (!(event==MD_TASK_CHECKPOINT_EVENT || (!event && signal==(SIGTRAP|0x80))
            || (event==PTRACE_EVENT_STOP && signal==SIGTRAP && (t->io.socket_waking
                || (t->phase==EPOLL_WAIT && md_debugger_stop_pending(debugger,t->pid)))))) return 0;
    if (!registers(t->pid,&regs)) return 1;
    if (event==PTRACE_EVENT_STOP && t->phase==EPOLL_CALL && regs.pc==abi.task_call) {
        struct readiness_owner *o=t->readiness;
        CHECK(regs.regs[8]==(unsigned long)o->native_call.number
            && !memcmp(regs.regs,o->native_call.value,sizeof(o->native_call.value)));
        /* A quiescence interrupt can precede SVC. No result exists yet;
         * consume this service wake and retain the shield until real entry. */
        t->io.socket_waking=0;
        resume(t->pid,0); return 1;
    }
    if (regs.pc!=(event==MD_TASK_CHECKPOINT_EVENT ? abi.task_completed+4 : abi.task_return))
        fprintf(stderr,"Shroot: readiness stop pid=%d phase=%d status=%#x pc=%#lx return=%#lx completed=%#lx nr=%lu value=%ld waking=%d\n",
            t->pid,t->phase,status,(unsigned long)regs.pc,(unsigned long)abi.task_return,
            (unsigned long)abi.task_completed,(unsigned long)regs.regs[8],(long)regs.regs[0],t->io.socket_waking);
    CHECK(regs.pc==(event==MD_TASK_CHECKPOINT_EVENT ? abi.task_completed+4 : abi.task_return));
    long value=(long)regs.regs[0];
    int restart=md_syscall_restarting(value);
    int waking=t->io.socket_waking; t->io.socket_waking=0;
    SUPERVISOR_TRACE("PROBE readiness-return pid=%d value=%ld waking=%d virtual=%d blocking=%d\n",
        t->pid,value,waking,t->readiness->wait.virtual_count,t->readiness->wait.blocking);
    socket_unwatch(t);
    md_epoll_wait_cancel(&t->readiness->wait);
    if (!event && signal==(SIGTRAP|0x80) && (restart || value==-EINTR) && !waking) {
        /* EVENT_WAIT: the delivery stop precedes the adapter checkpoint. Do
         * not retire the owner before learning whether the signal is ignored.
         * With no delivery, the checkpoint completes this same interruption. */
        t->readiness->signal_pending=1;
        if (restart) {
            regs.regs[0]=(uint64_t)-EINTR;
            if (!set_registers(t->pid,&regs)) return 1;
        }
        resume(t->pid,0); return 1;
    }
    t->readiness->signal_pending=0;
    if ((restart || value==-EINTR) && !waking) {
        struct readiness_owner *o=t->readiness;
        long ready=o->wait.virtual_count ? o->wait.virtual_count : o->wait.task_count;
        if (!ready) {
            struct md_buffer_span span;
            ready=readiness_frame(t,&span);
            if (!ready) {
                struct md_epoll_frame *frame=span.data;
                frame->native_output=0;
                struct md_control_actor actor={.pid=t->pid,.signals=t->signals.pending};
                if (o->poll) ready=md_poll_wait_interrupted(o->poll,&actor);
                else {
                    struct md_control_epoll *q=md_control_epoll_from_object(o->queue);
                    if (md_control_epoll_task_ready(q,&actor)>0 && o->loans[READINESS_TASK_QUEUE].number>=0) {
                        readiness_delivery_release(o);
                        signal_wait_restore(t);
                        md_epoll_wait_resample(&o->wait);
                        if (shield(t)) readiness_task_progress(t,1,0);
                        return 1;
                    }
                    ready=md_control_epoll_collect(q,frame->events,frame->capacity);
                }
            }
        }
        /* Virtual readiness may arrive alongside a guest signal. Consume it
         * once, without inspecting task-affine native descriptors here. */
        if (ready>0) {
            signal_wait_restore(t);
            if (shield(t)) readiness_reply(t,ready);
            return 1;
        }
        readiness_interrupted(t); return 1;
    }
    signal_wait_restore(t);
    if (!shield(t)) return 1;
    if (waking && (restart || value==-EINTR)) value=0;
    readiness_task_progress(t,1,value); return 1;
}

void readiness_buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_BUFFER_POLL_BEGIN:
        if (t->phase!=DISPATCHING || t->readiness || control->args[0] || control->args[1]
                || (t->execution.request.nr!=SYS_ppoll && t->execution.request.nr!=SYS_pselect6
                    && t->execution.request.nr!=SYS_rt_sigsuspend)) break;
        readiness_begin(t,r); return;
    case MD_BUFFER_POLL_LOAD:
        if (t->phase!=DISPATCHING || !t->readiness || !t->readiness->poll || t->readiness->done
                || control->args[0] || control->args[1]
                || md_fd_order_state(&t->readiness->files)!=MD_RESOURCE_ORDER_RUNNING) break;
        if (r) t->readiness->continuation=*r;
        readiness_poll_load(t); return;
    case MD_BUFFER_POLL_COPY: {
        if (!t->readiness || !t->readiness->poll || !t->readiness->done || control->args[1]
                || control->args[0]>t->readiness->poll_count) break;
        struct md_buffer_span span;
        int error=md_buffer_span(&t->fs.buffer,&span);
        if (!error) {
            error=md_poll_wait_copy(t->readiness->poll,span.data,(unsigned)control->args[0]);
            readiness_poll_elapsed(t,span.data);
        }
        buffer_reply(t,error); return;
    }
    case MD_BUFFER_EPOLL_BEGIN:
        if (t->phase!=DISPATCHING || t->readiness || control->args[0] || control->args[1]
                || (t->execution.request.nr!=SYS_epoll_ctl && t->execution.request.nr!=SYS_epoll_pwait
                    && t->execution.request.nr!=SYS_epoll_pwait2)) break;
        readiness_begin(t,r); return;
    case MD_BUFFER_EPOLL_CAPTURE_READY:
    case MD_BUFFER_EPOLL_CAPTURE_SENT:
    case MD_BUFFER_EPOLL_CAPTURE_OBSERVED:
        if (t->phase!=DISPATCHING || !t->readiness || t->readiness->done || control->args[1]
                || md_fd_order_state(&t->readiness->files)!=MD_RESOURCE_ORDER_RUNNING) break;
        if (r) t->readiness->continuation=*r;
        readiness_capture(t,(unsigned)control->command,(long)control->args[0]); return;
    case MD_BUFFER_EPOLL_WAIT:
        if (t->phase!=DISPATCHING || !t->readiness || t->readiness->done
                || (!t->readiness->received && !t->readiness->native_caller)) break;
        if (t->readiness->copy_pending) {
            if (control->args[1]!=MD_EPOLL_COPY || (long)control->args[0]>0) break;
            t->readiness->copy_pending=0;
            if (r) t->readiness->continuation=*r;
            readiness_task_progress(t,1,(long)control->args[0]); return;
        }
        if (t->readiness->park_pending) {
            if (!control->args[0] || control->args[1]!=MD_EPOLL_PARK) break;
            if (r) t->readiness->continuation=*r;
            readiness_park(t,control->args[0]); return;
        }
        if (control->args[0] || control->args[1]) break;
        if (r) t->readiness->continuation=*r;
        readiness_progress(t); return;
    }
    buffer_reply(t,result); adapter_progress();
}

int readiness_probe_exit(struct thread *t,pid_t pid) {
    if (t->phase==EPOLL_PROBE) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return 1;
        long value=(long)regs.regs[0];
        regs.regs[3]=t->execution.request.args[3];
        t->phase=IDLE;
        if (value) {
            t->execution.epoll_started=0;
            if (set_registers(pid,&regs)) resume(pid,0);
        } else if (save_signal_mask(t,&t->execution.mask,"epoll-probe-mask") && shield(t))
            dispatch_with_stack(t);
        return 1;
    }
    return 0;
}
