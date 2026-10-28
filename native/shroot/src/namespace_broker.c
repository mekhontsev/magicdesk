#define _GNU_SOURCE
#include "task_memory.h"
#include "namespace_broker.h"
#include "fs_engine.h"
#include "watch_broker.h"
#include "task_descriptor.h"
#include "ofd_order.h"
#include "fd_install.h"
#include "fd_mutation.h"
#include "fd_selector.h"
#include "notification_result.h"
#include "fs_buffer.h"
#include "fs_path_call.h"
#include "fs_syscall.h"
#include "open_request.h"
#include "cooperative_channel.h"
#include "execution_window.h"
#include "spin_policy.h"
#include "profile.h"
#include "mount_policy.h"
#include <errno.h>
#include <fcntl.h>
#include "linux_abi.h"
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/ptrace.h>
#include <unistd.h>
#include <linux/futex.h>
#include <time.h>
#include "fs_continuation.h"

enum { EXECUTION_BURST_CALLS=32, EXECUTION_BURST_NS=100000 };
enum { DISCOVERY_RECENT=16 };

struct operation {
    struct md_fs_work work;
    struct md_namespace_broker *owner;
    struct operation *all, *free;
    struct operation *waiting;
    struct task *task;
    struct seccomp_notif notification;
    struct md_entry_channel *channel;
    uint64_t sequence;
    uintptr_t captured_path[2];
    struct md_fs_context context;
    struct md_fs_continuation *continuation;
    struct md_fd_order order;
    struct md_fs_order fs_order;
    struct md_ofd_order cursor;
    struct md_fd_origin_update update;
    struct md_fd_mutation mutation;
    struct md_inode_birth *birth;
    struct md_fd_loan *custody;
    struct md_fs_work retirement_work;
    atomic_int retirement_done;
    int retirement_error;
    long native_result;
    int provisional_fd;
    int native_plan;
    int64_t native_started;
    struct md_fs_anchor *input_origin, *output_origin;
    struct md_fs_request request;
    struct md_fs_syscall syscall;
    struct md_fs_result result;
    struct md_notification_result completion;
    struct md_fd_object *output_object;
    struct md_fd_object *control_input;
    int control_descriptor;
    int64_t position;
    int listener, directory, undelivered, owned_open, executed, recovering;
    char path[PATH_MAX];
    char second_path[PATH_MAX];
    void *value;
    int second_directory;
    struct md_fs_anchor *second_origin;
};
enum { TASK_BUCKETS = 257 };
struct md_namespace_broker {
    struct md_fs_worker *worker;
    void (*delegate)(const struct seccomp_notif *);
    void (*recover)(pid_t,struct md_broker_completion *);
    md_control_factory control;
    md_control_query control_query;
    int (*native_observe)(pid_t,struct md_entry_native *);
    int (*retire)(const struct md_broker_retirement *);
    struct md_resource_wakeup *wakeup;
    struct operation *all, *free;
    struct operation *waiting;
    struct task *tasks;
    struct task *channels, *channel_cursor;
    struct task *task_index[TASK_BUCKETS];
    struct task *result_waiting;
    atomic_uint results_pending;
    struct task *event_ready;
    pthread_mutex_t lock;
    int listener;
    unsigned process;
    struct counters { uint64_t received, handled, delegated; } *counts;
    int synchronous_wake;
    atomic_uint cooperative_count;
    _Atomic uint64_t cooperative_completed, cooperative_declined, cooperative_wakes;
    _Atomic uint64_t cooperative_descriptors;
    uint64_t carrier_paths;
    _Atomic uint64_t cooperative_native_yields;
    uint64_t native_open, native_close, native_flags, native_deferred, native_duplicate, native_seek;
    uint64_t bursts, burst_requests, burst_count_limit, burst_time_limit, burst_idle_limit;
    uint64_t notification_batches, notification_batch_calls;
    struct md_spin_policy spin;
    struct md_cost preparation, capture, execution, reply;
    struct md_cost native_prepare, native_roundtrip, native_retire, retirement;
    uint64_t result_deliveries, result_waits, result_cancelled;
    struct md_watch_broker *watches;
    /* One synchronous worker owns this buffer; delegated operations retain no
     * bytes in it. Allocation is per broker, never per syscall or directory. */
    char output[64 * 1024];
};
struct task {
    struct task *next;
    struct task *channel_next;
    struct task *indexed;
    int pid, eligible, process, borrowed;
    uintptr_t gate, watch_gate;
    uintptr_t cooperative_gate;
    struct { uintptr_t recent[DISCOVERY_RECENT]; unsigned next; } discovery;
    struct md_broker_code_ranges machine;
    struct md_entry_channel *channel;
    uint64_t sequence;
    struct operation *operation;
    struct md_namespace_broker *owner;
    struct md_fs_work event_work;
    struct task *event_next;
    int stopped, held, status, recovering, native_borrowed;
    struct rusage usage;
    struct {
        struct md_notification_result completion;
        int queued;
        struct task *next;
    } result;
};
static void describe_task(const struct task *t,int fd) {
    dprintf(fd,"Shroot: broker pid=%d eligible=%d stopped=%d held=%d status=%#x borrowed=%d operation=%d recovering=%d\n",
        t->pid,t->eligible,t->stopped,t->held,t->status,t->borrowed,t->operation!=NULL,t->recovering);
    if (t->operation) dprintf(fd,"Shroot: broker operation pid=%d nativePlan=%d\n",
        t->pid,t->operation->native_plan);
    struct md_entry_channel *c=t->channel;
    if (c) dprintf(fd,"Shroot: channel pid=%d state=%u enabled=%u caller=%u awake=%u sleeping=%u native=%u\n",
        t->pid,atomic_load(&c->state),atomic_load(&c->enabled),atomic_load(&c->caller),
        atomic_load(&c->awake),atomic_load(&c->sleeping),atomic_load(&c->native.state));
}
void md_broker_describe_task(struct md_namespace_broker *b,pid_t pid,int fd) {
    if (!b) return;
    int error=pthread_mutex_trylock(&b->lock);
    if (error) { dprintf(fd,"Shroot: broker snapshot unavailable error=%d\n",error); return; }
    for (struct task *t=b->task_index[(unsigned)pid%TASK_BUCKETS];t;t=t->indexed)
        if (t->pid==pid) { describe_task(t,fd); break; }
    pthread_mutex_unlock(&b->lock);
}
void md_broker_describe(struct md_namespace_broker *b,int fd) {
    if (!b) return;
    md_fs_worker_describe(b->worker,fd);
    int error=pthread_mutex_trylock(&b->lock);
    if (error) { dprintf(fd,"Shroot: broker snapshot unavailable error=%d\n",error); return; }
    unsigned shown=0,total=0;
    for (struct task *t=b->tasks;t;t=t->next) if (t->pid) {
        ++total;
        if (shown++>=16) continue;
        describe_task(t,fd);
    }
    pthread_mutex_unlock(&b->lock);
    if (total>16) dprintf(fd,"Shroot: broker tasks shown=16 total=%u\n",total);
}
/* Caller holds the broker lock. A retired identity can still be borrowed by
 * a worker operation or by an exact kernel event awaiting main-loop delivery. */
static void task_release(struct task *t) {
    if (--t->borrowed<0) abort();
    if (!t->pid && !t->borrowed && t->process>=0) { close(t->process); t->process=-1; }
}
static int event_complete(struct md_fs_work *work) {
    struct task *t=(void *)((char *)work - offsetof(struct task,event_work));
    struct md_namespace_broker *b=t->owner;
    pthread_mutex_lock(&b->lock);
    if (t->pid) { t->event_next=b->event_ready; b->event_ready=t; }
    else { t->held=0; task_release(t); }
    pthread_mutex_unlock(&b->lock); return 0;
}
static void channel_reply(struct md_namespace_broker *,struct md_entry_channel *,
    uint64_t,enum md_entry_outcome,long);
static void cancel_pending(struct md_namespace_broker *b,struct task *t) {
    if (!t->channel) return;
    unsigned state=atomic_load_explicit(&t->channel->state,memory_order_acquire);
    if (state!=MD_ENTRY_REQUEST && state!=MD_ENTRY_FD_REQUEST) return;
    uint64_t sequence=t->channel->sequence;
    enum md_entry_outcome outcome=t->sequence!=UINT64_MAX && sequence==t->sequence+1
        ? MD_ENTRY_DECLINED : MD_ENTRY_UNKNOWN;
    t->sequence=sequence;
    channel_reply(b,t->channel,sequence,outcome,0);
}
static int operation_retired(struct md_namespace_broker *b,struct operation *op) {
    /* Caller holds the broker lock. Publish only after resource turns and pins
     * have retired; the exact kernel event then resumes normal dispatch. */
    struct task *t=op->task;
    if (!t || t->operation!=op) return 0;
    t->operation=NULL;
    if (t->held==1) {
        cancel_pending(b,t);
        t->held=2; md_fs_worker_publish(b->worker,&t->event_work);
    }
    /* A delegated callback can return to its caller before this retirement.
     * The worker may already have parked while the old operation excluded a
     * published next request. Removing that exclusion must publish readiness. */
    return !t->stopped && t->channel
        && !atomic_load_explicit(&t->channel->awake,memory_order_seq_cst)
        && atomic_load_explicit(&t->channel->state,memory_order_seq_cst)==MD_ENTRY_REQUEST;
}
static void channel_reply(struct md_namespace_broker *b,struct md_entry_channel *c,
        uint64_t sequence,enum md_entry_outcome outcome,long result) {
    c->receipt=sequence; c->outcome=outcome; c->result=result;
    atomic_store_explicit(&c->state,MD_ENTRY_RESPONSE,memory_order_seq_cst);
    if (atomic_load_explicit(&c->sleeping,memory_order_seq_cst))
        syscall(SYS_futex,&c->state,FUTEX_WAKE,1,NULL,NULL,0);
    if (b->counts) {
        if (outcome==MD_ENTRY_COMPLETED) ++b->cooperative_completed;
        else ++b->cooperative_declined;
    }
}
int md_broker_hold_event(struct md_namespace_broker *b,pid_t pid,int status,const struct rusage *usage) {
    if (!b) return 0;
    pthread_mutex_lock(&b->lock);
    struct task *t=NULL;
    for (t=b->task_index[(unsigned)pid%TASK_BUCKETS];t && t->pid!=pid;t=t->indexed) { }
    int held=0;
    if (t && t->channel) atomic_store_explicit(&t->channel->native_regular,0,memory_order_release);
    if (t && t->native_borrowed) { pthread_mutex_unlock(&b->lock); return 0; }
    if (t) {
        t->stopped=1;
        if (t->operation) {
            if (t->operation->native_plan==2) {
                int error=b->native_observe ? b->native_observe(pid,&t->channel->native) : -ENOTSUP;
                if (error==-ESRCH) {
                    atomic_store_explicit(&t->channel->native.state,MD_NATIVE_UNKNOWN,memory_order_release);
                    error=0;
                }
                if (error) { pthread_mutex_unlock(&b->lock); return error; }
            }
            if (t->held) { pthread_mutex_unlock(&b->lock); return -EBUSY; }
            t->held=1; ++t->borrowed;
            t->status=status; t->usage=usage ? *usage : (struct rusage){0}; held=1;
        } else {
            /* The signal handler may longjmp instead of returning to entry.
             * Cancel unadmitted memory ownership before delivering that signal.
             * An admitted operation instead retains the stop above. */
            cancel_pending(b,t);
        }
    }
    pthread_mutex_unlock(&b->lock);
    if (held) md_fs_worker_wake(b->worker);
    return held;
}
pid_t md_broker_take_event(struct md_namespace_broker *b,int *status,struct rusage *usage) {
    if (!b) return 0;
    pthread_mutex_lock(&b->lock);
    pid_t pid=0;
    struct task **at=&b->event_ready;
    while (*at && !pid) {
        struct task *t=*at;
        /* Injection temporarily owns this exact stopped continuation. Keep its
         * original event until native_return restores the guest boundary, but
         * let unrelated tasks make progress. */
        if (t->native_borrowed) { at=&t->event_next; continue; }
        *at=t->event_next; t->event_next=NULL; t->held=0;
        task_release(t);
        pid=t->pid; *status=t->status; if (usage) *usage=t->usage;
    }
    pthread_mutex_unlock(&b->lock); return pid;
}
void md_broker_completion_handler(struct md_namespace_broker *b,void (*recover)(pid_t,struct md_broker_completion *)) {
    b->recover=recover;
}
void md_broker_control_factory(struct md_namespace_broker *b,md_control_factory factory) { b->control=factory; }
void md_broker_control_query(struct md_namespace_broker *b,md_control_query query) { b->control_query=query; }
void md_broker_native_observer(struct md_namespace_broker *b,int (*observe)(pid_t,struct md_entry_native *)) {
    b->native_observe=observe;
}
void md_broker_retirement_handler(struct md_namespace_broker *b,int (*retire)(const struct md_broker_retirement *)) {
    b->retire=retire;
}
int md_broker_native_borrow(struct md_namespace_broker *b,pid_t pid,int *stopped) {
    if (!b) return 0;
    pthread_mutex_lock(&b->lock);
    struct task *t=b->task_index[(unsigned)pid%TASK_BUCKETS];
    while (t && t->pid!=pid) t=t->indexed;
    int result=0;
    if (t) {
        struct operation *op=t->operation;
        if (t->native_borrowed) result=-EBUSY;
        else if (t->held && (!WIFSTOPPED(t->status) || (unsigned)t->status>>16==PTRACE_EVENT_EXIT
                || (unsigned)t->status>>16==PTRACE_EVENT_EXEC)) result=-ESRCH;
        else if (op && op->native_plan!=3 && md_fd_order_state(&op->order)!=MD_RESOURCE_ORDER_QUEUED)
            result=-EBUSY;
        else {
            if (t->channel) atomic_store_explicit(&t->channel->native_regular,0,memory_order_release);
            t->native_borrowed=1; *stopped|=t->held!=0; result=1;
        }
    }
    pthread_mutex_unlock(&b->lock); return result;
}
void md_broker_native_return(struct md_namespace_broker *b,pid_t pid) {
    if (!b) return;
    pthread_mutex_lock(&b->lock);
    struct task *t=b->task_index[(unsigned)pid%TASK_BUCKETS];
    while (t && t->pid!=pid) t=t->indexed;
    if (!t || !t->native_borrowed) abort();
    t->native_borrowed=0;
    pthread_mutex_unlock(&b->lock);
}
static void result_observed(struct md_namespace_broker *b,int result) {
    if (result==MD_NOTIFICATION_WAIT) ++b->result_waits;
    else if (result==MD_NOTIFICATION_CANCELLED) ++b->result_cancelled;
    else if (result==MD_NOTIFICATION_DELIVERED) ++b->result_deliveries;
}
/* Broker lock protects admission against supervisor cancellation. No guest
 * memory, filesystem mutation or supervisor round trip occurs here. */
static int result_progress(struct md_namespace_broker *b, struct task *t) {
    int result=md_notification_result_progress(&t->result.completion);
    if (result==MD_NOTIFICATION_STATUS) result=md_notification_result_reply(&t->result.completion);
    result_observed(b,result);
    if (result==MD_NOTIFICATION_WAIT) return 1;
    return result<0 ? result : 0;
}
static void result_unqueue(struct md_namespace_broker *b, struct task *t) {
    if (!t->result.queued) return;
    struct task **p=&b->result_waiting;
    while (*p && *p!=t) p=&(*p)->result.next;
    if (*p) *p=t->result.next;
    t->result.queued=0; t->result.next=NULL;
    atomic_store_explicit(&b->results_pending,!!b->result_waiting,memory_order_release);
}
static int result_clear(struct md_namespace_broker *b, struct task *t) {
    int error=md_notification_result_clear(&t->result.completion);
    if (error) return error;
    result_unqueue(b,t);
    memset(&t->result,0,sizeof(t->result));
    return 0;
}
static int result_receive(struct md_namespace_broker *b, struct task *t, const struct seccomp_notif *q) {
    if (!t || !t->result.completion.active || q->data.nr!=t->result.completion.call.nr
            || q->data.instruction_pointer!=t->result.completion.call.instruction_pointer) return 0;
    if (t->result.completion.delivered || !md_notification_result_matches(&t->result.completion,&q->data)) {
        struct seccomp_notif_resp reply={.id=q->id,.error=-EPERM};
        return ioctl(b->listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) && errno!=ENOENT ? -errno : 1;
    }
    int result=md_notification_result_offer(&t->result.completion,b->listener,q->id,b->wakeup);
    if (result==MD_NOTIFICATION_STATUS) result=md_notification_result_reply(&t->result.completion);
    if (result<0) return result;
    result_observed(b,result);
    if (result==MD_NOTIFICATION_WAIT && !t->result.queued) {
        t->result.next=b->result_waiting; b->result_waiting=t; t->result.queued=1;
        atomic_store_explicit(&b->results_pending,1,memory_order_release);
    } else if (result!=MD_NOTIFICATION_WAIT) result_unqueue(b,t);
    return 1;
}
static int valid(struct operation *op) {
    if (op->channel) return atomic_load_explicit(&op->channel->state,memory_order_acquire)==MD_ENTRY_RUNNING;
    return !ioctl(op->listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->notification.id);
}
static void cooperative_reply(struct operation *op,enum md_entry_outcome outcome,long result) {
    channel_reply(op->owner,op->channel,op->sequence,outcome,result);
}
static int duplicate(struct task *task, pid_t pid, int number) {
    /* Worker borrows this identity until preparation ends. Retirement cannot
     * close or reuse it meanwhile; file numbers themselves are never cached. */
    return md_task_descriptor_copy(pid,number,&task->process);
}
static int pathname(pid_t pid, uintptr_t address, char *out, size_t capacity) {
    ssize_t size=md_task_read_string(pid,address,out,capacity,(size_t)sysconf(_SC_PAGESIZE));
    return size<0 ? (int)size : 0;
}
static int deliver_bytes(struct operation *op, uintptr_t pointer, const void *data, size_t size) {
    if (!valid(op)) return -ECANCELED;
    if (!size) return 0;
    int detached=op->notification.data.nr==SYS_readlinkat || op->syscall.copy==MD_FS_COPY_STAT
        || op->syscall.copy==MD_FS_COPY_STATX || op->syscall.copy==MD_FS_COPY_STATFS
        || op->syscall.copy==MD_FS_COPY_XATTR;
    if (op->channel && detached
            && size<=sizeof(op->channel->output.bytes)) {
        struct md_entry_channel *c=op->channel;
        if (c->output.sequence==op->sequence) return -EPROTO;
        c->output.sequence=op->sequence; c->output.destination=pointer; c->output.size=size;
        memcpy(c->output.bytes,data,size);
        return 0;
    }
    struct iovec local = {(void *)data, size}, remote = {(void *)pointer, size};
    ssize_t n = process_vm_writev(op->notification.pid, &local, 1, &remote, 1, 0);
    /* Only a denied, untouched output may fall back to the task-affine path.
     * Partial copies and notification cancellation are terminal for this call. */
    if (n < 0 && (errno == EPERM || errno == EACCES)) op->undelivered = 1;
    return n == (ssize_t)size ? 0 : -EFAULT;
}
static int control_open(void *context,const struct md_control_file_ops *ops,void *data,
        const struct md_identity *identity,int flags,struct md_fs_anchor *origin) {
    struct operation *op=context;
    if (!op->owner->control) return -EOPNOTSUPP;
    if (op->output_object) return -EBUSY;
    op->output_object=op->owner->control(ops,data,identity,flags,origin,&op->control_descriptor);
    return op->output_object ? 0 : -errno;
}
static void execute(struct operation *op,
        void *data, size_t capacity) {
    op->result = (struct md_fs_result){.fd = -1, .error = -ECANCELED};
    struct md_fs_output output = {.data = data, .capacity = capacity,
        .directory_position=op->request.operation==MD_FS_GETDENTS ? &op->position : NULL,
        .origin=op->request.operation==MD_FS_OPEN ? &op->output_origin : NULL,
        .native_open_plan=op->native_plan,
        .birth=op->native_plan && op->owner->retire ? &op->birth : NULL,
        .control=op->owned_open && op->owner->control ? control_open : NULL,.control_context=op,
        .continuation=&op->continuation};
    if (valid(op)) {
        op->executed=1;
        struct md_fs_continuation *previous=op->continuation;
        if (previous) md_fs_context_resume(&op->context,previous,&op->result,&output);
        else md_fs_context_execute(&op->context, &op->request, &op->result, &output);
        md_fs_continuation_pending(previous,&op->continuation,&op->result);
        /* Read-scope admission has not performed the open. A signal before
         * its unscoped attempt must retain EINTR/restart, not a saved errno. */
        if (op->result.error==-EAGAIN && op->result.open_completion.kind==MD_OPEN_NEEDS_WRITE)
            op->executed=0;
        if (!op->result.error && op->result.fd>=0 && op->control_descriptor>=0) {
            close(op->result.fd); op->result.fd=op->control_descriptor; op->control_descriptor=-1;
        }
    }
}
struct md_namespace_broker *md_broker_create(struct md_fs_worker *worker, int statistics,
        void (*delegate)(const struct seccomp_notif *)) {
    struct md_namespace_broker *b = calloc(1, sizeof(*b));
    if (b) {
        atomic_init(&b->results_pending,0);
        atomic_init(&b->cooperative_count,0);
        atomic_init(&b->cooperative_completed,0);
        atomic_init(&b->cooperative_declined,0);
        atomic_init(&b->cooperative_wakes,0);
        atomic_init(&b->cooperative_descriptors,0);
        atomic_init(&b->cooperative_native_yields,0);
        b->worker = worker; b->listener = -1; b->delegate=delegate;
        b->process=(unsigned)getpid();
        if (pthread_mutex_init(&b->lock, NULL)) { free(b); return NULL; }
        b->wakeup=md_resource_wakeup_retain(md_fs_worker_wakeup(worker));
        if (!b->wakeup) {
            pthread_mutex_destroy(&b->lock); free(b); return NULL;
        }
        if (statistics && !(b->counts = calloc(512, sizeof(*b->counts)))) {
            md_resource_wakeup_release(b->wakeup);
            pthread_mutex_destroy(&b->lock); free(b); return NULL;
        }
    }
    return b;
}
static int recycle(struct md_namespace_broker *b, struct operation *op) {
    int64_t start=md_cost_begin(b->counts ? &b->retirement : NULL);
    int cleared=md_notification_result_clear(&op->completion);
    if (cleared) return cleared;
    cleared=md_inode_birth_discard(&op->birth);
    if (cleared) return cleared;
    if (op->continuation) { op->continuation->destroy(op->continuation); op->continuation=NULL; }
    md_fd_loan_end(op->custody); md_fd_loan_release(op->custody); op->custody=NULL;
    if (op->directory >= 0) close(op->directory);
    if (op->second_directory>=0) close(op->second_directory);
    op->second_directory=-1;
    if (op->result.fd >= 0) close(op->result.fd);
    if (op->control_descriptor>=0) close(op->control_descriptor);
    op->control_descriptor=-1;
    md_fd_object_release(op->output_object); op->output_object=NULL;
    md_fd_object_release(op->control_input); op->control_input=NULL;
    if (op->result.open_completion.kind == MD_OPEN_PIPE) close(op->result.open_completion.control);
    op->directory = op->result.fd = -1;
    op->result.open_completion = (struct md_open_completion){0};
    md_fd_origins_finish(&op->update,-1);
    md_fd_mutation_discard(&op->mutation);
    op->native_plan=0;
    md_fs_anchor_release(op->input_origin); op->input_origin=NULL;
    md_fs_anchor_release(op->second_origin); op->second_origin=NULL;
    md_fs_anchor_release(op->output_origin); op->output_origin=NULL;
    int error=0;
    if (op->cursor.owner) {
        error=md_ofd_order_cancel(&op->cursor);
        if (error==1) error=md_ofd_order_complete(&op->cursor);
    }
    if (op->fs_order.fs) {
        error=md_fs_order_cancel(&op->fs_order);
        if (error==1) error=md_fs_order_complete(&op->fs_order);
    }
    if (op->order.table) {
        int retired=md_fd_order_cancel(&op->order);
        if (retired==1) retired=md_fd_order_complete(&op->order);
        if (!error) error=retired;
    }
    md_fs_context_end(&op->context);
    free(op->value); op->value=NULL;
    pthread_mutex_lock(&b->lock);
    int pending=operation_retired(b,op);
    if (op->task) {
        task_release(op->task);
        op->task=NULL;
    }
    op->free = b->free; b->free = op;
    pthread_mutex_unlock(&b->lock);
    if (pending) md_fs_worker_wake(b->worker);
    md_cost_end(b->counts ? &b->retirement : NULL,start);
    return error;
}
static void capture_input_paths(struct operation *op,const struct md_entry_channel *channel) {
    if (channel->input.sequence!=op->sequence) return;
    for (unsigned i=0;i<2;++i) if (channel->input.address[i]) {
        size_t size=strnlen(channel->input.paths[i],sizeof(channel->input.paths[i]));
        if (size>=PATH_MAX || size==sizeof(channel->input.paths[i])) continue;
        memcpy(i ? op->second_path : op->path,channel->input.paths[i],size+1);
        op->captured_path[i]=channel->input.address[i];
    }
}
static int prepare_call(struct md_filesystem *fs, struct operation *op, size_t capacity) {
    const struct seccomp_notif *q = &op->notification;
    if (op->native_plan && !md_entry_open(q->data.nr)) {
        int error=md_task_registry_files(fs->tasks,(int)q->pid,0,&op->context.task.files);
        return error ? error : 1;
    }
    if (!md_broker_filesystem_call(q->data.nr) && !op->channel) return 0;
    /* ADDFD uses fget(), which rejects O_PATH; SCM_RIGHTS preserves it. */
    if (q->data.nr==SYS_openat) {
        if (q->data.args[2]&__O_TMPFILE) return 0;
        if ((q->data.args[2]&O_PATH) && !op->native_plan) return 0;
        op->owned_open=op->owner->recover && (op->task->eligible&MD_BROKER_OPEN);
        if (op->native_plan) op->owned_open=0;
        if (!op->owned_open && !op->native_plan && (q->data.args[2]&(O_CREAT|O_TRUNC))) return 0;
    }
    if (q->data.nr == SYS_newfstatat && (q->data.args[3] & ~(AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT))) return 0;
    if (q->data.nr==SYS_readlinkat && !q->data.args[3]) return -EINVAL;
    uint64_t arguments[6];
    for (unsigned i=0;i<6;++i) arguments[i]=q->data.args[i];
    struct open_how how={0};
    int nr=q->data.nr;
    if (nr==SYS_openat2) {
        if (!op->native_plan || arguments[3]!=sizeof(how)) return 0;
        struct iovec local={&how,sizeof(how)}, remote={(void *)(uintptr_t)arguments[2],sizeof(how)};
        if (process_vm_readv(q->pid,&local,1,&remote,1,0)!=sizeof(how)) return 0;
        int error=md_open_how_validate(&how);
        if (error) return error;
        if (!md_open_native_plan(how.flags,1)) return 0;
        nr=SYS_openat; arguments[2]=how.flags; arguments[3]=how.mode;
    }
    int decoded=md_fs_syscall_decode(&op->syscall,&op->request,nr,arguments,capacity);
    if (decoded<=0) return decoded;
    op->request.actor=(int)q->pid;
    op->request.resolve=how.resolve;
    if (!op->captured_path[0]) op->path[0]=0;
    if (!op->captured_path[1]) op->second_path[0]=0;
    for (unsigned i=0;i<2;++i) if (op->syscall.path[i]) {
        char *path=i ? op->second_path : op->path;
        if (op->captured_path[i]!=op->syscall.path[i]
                && pathname(q->pid,op->syscall.path[i],path,PATH_MAX)) return 0;
        op->request.path[i]=path;
    }
    /* NULL path operands are not descriptor operations. Preserve task-affine
     * fault handling if direct capture cannot establish the complete input. */
    if (q->data.nr!=SYS_fstat && q->data.nr!=SYS_getdents64 && q->data.nr!=SYS_fstatfs
            && q->data.nr!=SYS_fchmod && q->data.nr!=SYS_fchown && q->data.nr!=SYS_utimensat
            && op->request.operation!=MD_FS_FXATTR && !op->request.path[0]) return 0;
    if ((op->syscall.operands==2 || q->data.nr==SYS_symlinkat) && !op->request.path[1]) return 0;
    if (op->syscall.copy==MD_FS_COPY_XATTR) {
        if (op->request.mode!=MD_FS_XATTR_LIST && !op->request.path[1]) return 0;
        if (strlen(op->second_path)>255) return -ERANGE;
        if (op->request.value_size) {
            op->value=malloc(op->request.value_size);
            if (!op->value) return -ENOMEM;
            struct iovec local={op->value,op->request.value_size}, remote={(void *)op->request.value,op->request.value_size};
            if (process_vm_readv(q->pid,&local,1,&remote,1,0)!=(ssize_t)op->request.value_size) return 0;
            op->request.value=op->value;
        }
    }
    if (op->syscall.times) {
        struct timespec times[2];
        struct iovec local={times,sizeof(times)}, remote={(void *)op->syscall.times,sizeof(times)};
        if (process_vm_readv(q->pid,&local,1,&remote,1,0)!=sizeof(times)) return 0;
        for (unsigned i=0;i<2;++i) {
            op->request.attributes.seconds[i]=times[i].tv_sec;
            op->request.attributes.nanos[i]=times[i].tv_nsec;
        }
    }
    if (op->request.operation==MD_FS_STAT) {
        if (!*op->path) {
            if (!(op->request.flags&AT_EMPTY_PATH)) return -ENOENT;
            op->request.operation=MD_FS_FSTAT; op->request.flags=0;
        } else op->request.flags&=AT_SYMLINK_NOFOLLOW;
    } else if (op->request.operation==MD_FS_ACCESS || op->request.operation==MD_FS_CHMOD
            || op->request.operation==MD_FS_CHOWN || op->request.operation==MD_FS_UTIMENS) {
        if (op->request.path[0] && !*op->path && !(op->request.flags&AT_EMPTY_PATH)) return -ENOENT;
        op->request.flags&=~AT_EMPTY_PATH;
    }
    if (q->data.nr==SYS_fsopen) {
        if (!op->owner->recover || !(op->task->eligible&MD_BROKER_OPEN)) return 0;
        op->owned_open=1;
    }
    int error=md_fs_context_begin(fs,(int)q->pid,0,&op->context);
    if (error) return error;
    return 1;
}
static int prepare(struct md_filesystem *fs, struct operation *op, size_t capacity) {
    struct md_cost *cost=op->owner->counts ? &op->owner->preparation : NULL;
    int64_t start=md_cost_begin(cost);
    int result=prepare_call(fs,op,capacity);
    md_cost_end(cost,start);
    return result;
}
static int capture(struct operation *op) {
    const struct seccomp_notif *q=&op->notification;
    for (unsigned i=0;i<op->syscall.operands;++i) {
        const char *path=op->request.path[i];
        if (path && *path=='/' && !(op->request.resolve&RESOLVE_IN_ROOT)) { op->request.directory[i]=-1; continue; }
        int base=op->request.directory[i];
        int *fd=i ? &op->second_directory : &op->directory;
        struct md_fs_anchor **origin=i ? &op->second_origin : &op->input_origin;
        int borrowed=-1;
        if (base==AT_FDCWD && path) {
            struct md_fs_location root;
            md_domain_paths(op->fs_order.fs,&root,origin);
            if (*origin)
                borrowed=md_fs_anchor_fd(*origin);
            else {
                char cwd[64]; snprintf(cwd, sizeof(cwd), "/proc/%u/cwd", q->pid);
                *fd=open(cwd,O_PATH|O_DIRECTORY|O_CLOEXEC);
            }
        } else {
            struct md_fd_object *object=md_fd_origins_object(op->context.task.files,base,NULL);
            *origin=md_fs_anchor_retain(md_fs_anchor_from_object(object));
            /* A retained path identifies metadata, not an ordinary directory
             * stream. Typed IO must keep the descriptor owner's cursor. */
            int unsupported=object && (!*origin || (op->request.operation==MD_FS_GETDENTS
                && (md_fd_object_selectors(object)&(1U<<MD_FD_SELECTOR_PROC))));
            if (!unsupported && !i && md_fs_anchor_virtual(*origin)) {
                if (op->request.operation==MD_FS_FSTAT && op->owner->control_query) {
                    int found=op->owner->control_query(object,op->request.operation,&op->request.control);
                    if (found<0) { md_fd_object_release(object); return found; }
                    unsupported=!found;
                    if (found) { op->control_input=object; object=NULL; }
                } else unsupported=1;
            }
            md_fd_object_release(object);
            if (unsupported) return 0;
            /* The table turn authenticates the number; the retained anchor
             * pins path identity after that turn ends. Data/offset operations
             * still capture the real OFD, never the anchor's O_PATH handle. */
            if (*origin && ((path && *path) || op->request.operation==MD_FS_FSTAT
                    || op->request.operation==MD_FS_STATFS)) borrowed=md_fs_anchor_fd(*origin);
            else *fd=duplicate(op->task,q->pid,base);
        }
        if (*fd<0 && borrowed<0) return 0;
        if (!path && (q->data.nr==SYS_fchmod || q->data.nr==SYS_fchown || q->data.nr==SYS_utimensat)) {
            int flags=fcntl(*fd,F_GETFL);
            if (flags<0) return -errno;
            if (flags&O_PATH) return -EBADF;
        }
        op->request.directory[i]=borrowed>=0 ? borrowed : *fd;
        op->request.origin[i]=*origin;
    }
    return 1;
}
static void recover(struct operation *op) {
    op->recovering=1;
    pthread_mutex_lock(&op->owner->lock);
    if (op->task && op->task->pid==(int)op->notification.pid) op->task->recovering=1;
    pthread_mutex_unlock(&op->owner->lock);
}
static int native_offer(struct operation *op) {
    int64_t start=md_cost_begin(op->owner->counts ? &op->owner->native_prepare : NULL);
    struct md_entry_channel *c=op->channel;
    struct md_entry_native *n=&c->native;
    c->native_commit=!!op->birth;
    *n=(struct md_entry_native){.number=md_entry_open(op->notification.data.nr) ? SYS_openat : op->notification.data.nr};
    if (n->number==SYS_openat) {
        int error=md_fd_origins_prepare(op->context.task.files,op->output_origin,
            op->request.flags&O_CLOEXEC ? FD_CLOEXEC : 0,&op->update);
        if (error) return error;
        if (op->birth) {
            op->custody=md_fd_loan_new(op->context.task.files);
            error=op->custody ? md_fd_origins_prepare_custody(&op->update,op->custody,&op->order) : -ENOMEM;
            if (error) return error;
        }
        n->args[0]=(uint64_t)(int64_t)AT_FDCWD;
        n->args[2]=md_open_plan_path(c->native_path,op->owner->process,
            (unsigned)md_fs_anchor_fd(op->output_origin),op->request.flags);
    } else memcpy(n->args,op->notification.data.args,sizeof(n->args));
    if (op->owner->counts) {
        if (n->number==SYS_openat) ++op->owner->native_open;
        else if (n->number==SYS_close) ++op->owner->native_close;
        else if (n->number==SYS_lseek) ++op->owner->native_seek;
        else if (n->number==SYS_dup || n->number==SYS_dup3 || (n->number==SYS_fcntl
                && ((unsigned)n->args[1]==F_DUPFD || (unsigned)n->args[1]==F_DUPFD_CLOEXEC))) ++op->owner->native_duplicate;
        else ++op->owner->native_flags;
    }
    unsigned lease=0, flags=0;
    if ((n->number==SYS_lseek || (n->number==SYS_openat && !op->birth))
            && md_fd_origins_exclusive(&op->order)) {
        struct md_fd_native native;
        if (n->number==SYS_openat && !op->birth && md_fs_anchor_kind(op->output_origin)==S_IFREG
                && !md_fd_origins_has_loans(op->context.task.files)) {
            lease=MD_ENTRY_RESULT_FD;
            flags=op->request.flags&O_CLOEXEC ? FD_CLOEXEC : 0;
        } else if (n->number==SYS_lseek && md_fd_origins_native(&op->order,(int)n->args[0],&native)) {
            lease=(unsigned)n->args[0]+1; flags=native.flags;
        }
    }
    pthread_mutex_lock(&op->owner->lock);
    op->native_plan=2;
    if (lease && !op->task->stopped) {
        c->native_flags=flags;
        atomic_store_explicit(&c->native_regular,lease,memory_order_release);
    }
    op->native_started=md_cost_begin(op->owner->counts ? &op->owner->native_roundtrip : NULL);
    n->result=-EINTR;
    atomic_store_explicit(&n->state,op->task->stopped ? MD_NATIVE_CANCELLED : MD_NATIVE_READY,memory_order_release);
    atomic_store_explicit(&c->state,MD_ENTRY_NATIVE,memory_order_seq_cst);
    if (atomic_load_explicit(&c->sleeping,memory_order_seq_cst))
        syscall(SYS_futex,&c->state,FUTEX_WAKE,1,NULL,NULL,0);
    pthread_mutex_unlock(&op->owner->lock);
    md_cost_end(op->owner->counts ? &op->owner->native_prepare : NULL,start);
    return 2;
}
static int native_descriptor(struct operation *op) {
    int number=(int)op->notification.data.args[0];
    struct md_fd_native native={0};
    int allowed=!md_fd_origins_has_loans(op->context.task.files)
        && md_fd_origins_native(&op->order,number,&native);
    if (allowed && op->notification.data.nr==SYS_lseek) {
        /* Directory offsets belong to the acknowledged directory cursor.
         * Only ordinary data OFDs keep their native kernel offset here. */
        allowed=native.kind==S_IFREG;
    }
    if (allowed && op->notification.data.nr==SYS_dup3) {
        struct md_fd_observation target;
        allowed=!md_fd_origins_observe(op->context.task.files,(int)op->notification.data.args[1],&target)
            && !target.selectors;
    }
    if (!allowed) return 0;
    unsigned command=(unsigned)op->notification.data.args[1];
    if (op->notification.data.nr==SYS_fcntl && (command==F_GETFD
            || (command==F_SETFD && (op->notification.data.args[2]&FD_CLOEXEC)==native.flags))) {
        /* The table turn owns a live kernel slot with authoritative flags.
         * Reading them or setting the same value needs no native effect. */
        cooperative_reply(op,MD_ENTRY_COMPLETED,command==F_GETFD ? native.flags : 0);
        if (op->owner->counts) ++op->owner->native_flags;
        return 1;
    }
    unsigned long args[6]; memcpy(args,op->notification.data.args,sizeof(args));
    int error=md_fd_mutation_prepare(op->context.task.files,op->notification.data.nr,args,&op->mutation);
    return error ? error : native_offer(op);
}
/* Zero requests task-affine handling, one means delivered/cancelled. */
static int reply(struct operation *op, const void *data) {
    if (op->result.error == -EXDEV || op->result.error == -EREMOTE || op->result.host_path || op->result.requires_control
            || op->undelivered) return 0;
    if (op->native_plan && !op->result.error) {
        if (op->result.open_completion.kind!=MD_OPEN_PLAN || !op->output_origin) return 0;
        return native_offer(op);
    }
    struct seccomp_notif_resp response = {.id = op->notification.id, .error = op->result.error};
    if (op->owned_open && op->executed) {
        if (!op->result.error && (op->result.fd<0 || op->result.open_completion.kind!=MD_OPEN_READY
                || md_fd_object_selectors(op->output_object))) {
            recover(op); return 0;
        }
        /* An executed failure is a retained result too. Deliver it without
         * entering the task; cancellation still transfers the exact outcome. */
        int error=op->result.error ? md_notification_result_status(&op->completion,
                &op->notification.data,op->result.error)
            : md_notification_result_fd(&op->completion,&op->notification.data,op->context.task.files,
                op->result.fd,op->output_object ? op->output_object : md_fs_anchor_object(op->output_origin),
                op->request.flags&O_CLOEXEC);
        if (error) return error;
        int result=md_notification_result_offer(&op->completion,op->listener,op->notification.id,op->owner->wakeup);
        if (result==MD_NOTIFICATION_STATUS) result=md_notification_result_reply(&op->completion);
        result_observed(op->owner,result);
        if (result==MD_NOTIFICATION_CANCELLED) { recover(op); return 0; }
        if (result==MD_NOTIFICATION_WAIT) return 2;
        if (result==MD_NOTIFICATION_DELIVERED) op->result.error=op->completion.value<0 ? (int)op->completion.value : 0;
        return result<0 ? result : 1;
    }
    if (op->result.open_completion.kind!=MD_OPEN_READY) return 0;
    if (!response.error && op->notification.data.nr == SYS_openat) {
        int error=md_fd_origins_prepare(op->context.task.files,op->output_origin,
            op->notification.data.args[2]&O_CLOEXEC ? FD_CLOEXEC : 0,&op->update);
        if (error) {
            response.error=error;
            return !ioctl(op->listener,SECCOMP_IOCTL_NOTIF_SEND,&response) || errno==ENOENT ? 1 : -errno;
        }
        /* Atomic kernel publication remains inside the same table turn as its
         * origin publication. A resumed CLONE_FILES peer must acquire that turn. */
        struct seccomp_notif_addfd add = {.id = response.id, .srcfd = op->result.fd,
            .flags = SECCOMP_ADDFD_FLAG_SEND,
            .newfd_flags = (uint32_t)op->notification.data.args[2] & O_CLOEXEC};
        int fd=ioctl(op->listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
        if (fd>=0) { md_fd_origins_finish(&op->update,fd); return 1; }
        response.error = -errno;
    } else if (!response.error && (op->notification.data.nr == SYS_getdents64
            || op->notification.data.nr==SYS_readlinkat)) {
        size_t size=op->result.size;
        int link=op->notification.data.nr==SYS_readlinkat;
        if (link && size>op->request.capacity) size=op->request.capacity;
        response.error=link && !op->request.capacity ? -EINVAL
            : deliver_bytes(op,op->notification.data.args[link ? 2 : 1],data,size);
        if (op->undelivered) return 0;
        if (!response.error) response.val=size;
    } else if (!response.error && op->syscall.copy==MD_FS_COPY_XATTR) {
        if (op->result.size) response.error=deliver_bytes(op,op->syscall.output,data,op->result.size);
        if (op->undelivered) return 0;
        if (!response.error) response.val=op->result.position;
    } else if (!response.error && op->syscall.copy==MD_FS_COPY_STATFS) {
        response.error=op->result.size!=sizeof(struct md_fs_statfs) ? -EIO
            : deliver_bytes(op,op->syscall.output,data,op->result.size);
        if (op->undelivered) return 0;
    } else if (!response.error && op->syscall.copy==MD_FS_COPY_STATX) {
        struct statx st;
        md_fs_info_statx(&op->result.info,&st);
        response.error=deliver_bytes(op,op->syscall.output,&st,sizeof(st));
        if (op->undelivered) return 0;
    } else if (!response.error && op->syscall.copy==MD_FS_COPY_STAT) {
        struct stat st;
        if (op->result.size == sizeof(st)) memcpy(&st, data, sizeof(st));
        else md_fs_info_stat(&op->result.info, &st);
        uintptr_t pointer = op->notification.data.args[op->notification.data.nr == SYS_newfstatat ? 2 : 1];
        response.error=deliver_bytes(op,pointer,&st,sizeof(st));
        if (op->undelivered) return 0;
    }
    if (op->channel) {
        /* Commit the directory cursor before waking a caller that can issue
         * another read immediately. Failed output never advances it. */
        if (!response.error && op->request.operation==MD_FS_GETDENTS) {
            off_t position=lseek(op->directory,op->position,SEEK_SET);
            if (position!=op->position) response.error=position<0 ? -errno : -EIO;
        }
        cooperative_reply(op,MD_ENTRY_COMPLETED,response.error ? response.error : response.val);
        return 1;
    }
    if (ioctl(op->listener, SECCOMP_IOCTL_NOTIF_SEND, &response))
        return errno==ENOENT ? 1 : -errno;
    /* A signal may cancel the notification after copying bytes. Commit only
     * after kernel acceptance, retaining the table/OFD turn through this seek. */
    if (!response.error && op->notification.data.nr==SYS_getdents64) {
        off_t position=lseek(op->directory,op->position,SEEK_SET);
        if (position!=op->position) return position<0 ? -errno : -EIO;
    }
    return 1;
}
/* Caller holds the broker lock; borrowed retired records stay outside the index. */
static struct task *find_task(struct md_namespace_broker *b, int pid) {
    if (pid <= 0) return NULL;
    for (struct task *t = b->task_index[(unsigned)pid % TASK_BUCKETS]; t; t = t->indexed)
        if (t->pid == pid) return t;
    return NULL;
}
void md_broker_completion_taken(struct md_namespace_broker *b,pid_t pid) {
    if (!b) return;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    if (t) t->recovering=0;
    pthread_mutex_unlock(&b->lock);
}
int md_broker_result_begin(struct md_namespace_broker *b, int pid, const struct seccomp_data *call,
        struct md_fd_origins *files, int source, struct md_fd_object *origin, unsigned flags) {
    if (!b || !call || !call->instruction_pointer
            || (!md_broker_filesystem_call(call->nr) && call->nr!=SYS_gettid)
            || !files || source<0 || flags&~O_CLOEXEC) return -EINVAL;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    int error=!t ? -ESRCH : t->result.completion.active ? -EBUSY
        : md_notification_result_fd(&t->result.completion,call,files,source,origin,flags);
    pthread_mutex_unlock(&b->lock); return error;
}
int md_broker_result_finish(struct md_namespace_broker *b, int pid, long *value) {
    if (!b) return 0;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    int delivered=t && t->result.completion.delivered;
    if (delivered && value) *value=t->result.completion.value;
    int error=t ? result_clear(b,t) : 0;
    pthread_mutex_unlock(&b->lock); return error ? error : delivered;
}
static int task_resumed(struct task *t) {
    if (t->channel) atomic_store_explicit(&t->channel->native_regular,0,memory_order_release);
    t->stopped=0;
    return t->channel && atomic_load_explicit(&t->channel->state,memory_order_acquire)==MD_ENTRY_REQUEST;
}
void md_broker_resumed(struct md_namespace_broker *b,pid_t pid) {
    if (!b) return;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    int pending=t && task_resumed(t);
    pthread_mutex_unlock(&b->lock);
    if (pending) md_fs_worker_wake(b->worker);
}
void md_broker_resample(struct md_namespace_broker *b,pid_t pid,uintptr_t pc) {
    if (!b) return;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    if (t) for (unsigned i=0;i<DISCOVERY_RECENT;++i)
        if (t->discovery.recent[i]==pc) { t->discovery.recent[i]=0; break; }
    pthread_mutex_unlock(&b->lock);
}
int md_broker_task(struct md_namespace_broker *b, int pid, int eligible, uintptr_t raw_gate, uintptr_t watch_gate,
        const struct md_broker_code_ranges *machine) {
    if (pid <= 0) return -EINVAL;
    pthread_mutex_lock(&b->lock);
    struct task *t = find_task(b, pid);
    if (!t) {
        for (t = b->tasks; t && (t->pid || t->borrowed); t = t->next) { }
        if (!t) { t = calloc(1, sizeof(*t)); if (t) { t->process = -1; t->next = b->tasks; b->tasks = t; } }
        if (t) {
            memset(&t->discovery,0,sizeof(t->discovery));
            unsigned bucket = (unsigned)pid % TASK_BUCKETS;
            t->indexed = b->task_index[bucket]; b->task_index[bucket] = t;
        }
    }
    int pending=0;
    if (t) {
        t->pid = pid; t->eligible = eligible; t->gate = raw_gate; t->watch_gate = watch_gate;
        t->machine=machine ? *machine : (struct md_broker_code_ranges){0};
        t->owner=b; t->event_work.complete=event_complete;
        if (t->channel) atomic_store_explicit(&t->channel->enabled,
            !!(eligible&MD_BROKER_COOPERATIVE),memory_order_release);
        pending=task_resumed(t);
    }
    pthread_mutex_unlock(&b->lock);
    if (pending) md_fs_worker_wake(b->worker);
    return t ? 0 : -ENOMEM;
}
int md_broker_cooperative_bind(struct md_namespace_broker *b,pid_t pid,struct md_entry_channel *channel,uintptr_t gate) {
    if (!b || !channel || !gate) return -EINVAL;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    int error=!t ? -ESRCH : t->channel ? -EALREADY : 0;
    if (!error) {
        memset(channel,0,sizeof(*channel));
        atomic_init(&channel->state,MD_ENTRY_IDLE);
        /* Registration is a notification, not a ptrace stop/resume cycle.
         * Publish current eligibility with the channel instead of waiting for
         * an unrelated future stop to enable its first request. */
        atomic_init(&channel->enabled,!!(t->eligible&MD_BROKER_COOPERATIVE));
        atomic_init(&channel->awake,0); atomic_init(&channel->sleeping,0);
        atomic_init(&channel->copying,0);
        atomic_init(&channel->native_regular,0);
        channel->owner=pid;
        t->channel=channel; t->cooperative_gate=gate; t->sequence=0; ++b->cooperative_count;
        t->channel_next=b->channels; b->channels=t;
    }
    pthread_mutex_unlock(&b->lock); return error;
}
void md_broker_cooperative_revoke(struct md_namespace_broker *b,pid_t pid) {
    if (!b) return;
    pthread_mutex_lock(&b->lock);
    struct task *t=find_task(b,pid);
    if (t) {
        t->eligible&=~MD_BROKER_COOPERATIVE;
        if (t->channel) atomic_store_explicit(&t->channel->enabled,0,memory_order_release);
    }
    pthread_mutex_unlock(&b->lock);
}
void md_broker_forget(struct md_namespace_broker *b, int pid) {
    if (!b || pid <= 0) return;
    pthread_mutex_lock(&b->lock);
    struct task **slot = &b->task_index[(unsigned)pid % TASK_BUCKETS];
    while (*slot && (*slot)->pid != pid) slot = &(*slot)->indexed;
    struct task *t = *slot;
    if (t) {
        if (t->channel) {
            /* A held kernel event cannot retire the mapping until its exact
             * admitted operation has completed. */
            if (t->operation) abort();
            atomic_store_explicit(&t->channel->enabled,0,memory_order_release);
            atomic_store_explicit(&t->channel->state,MD_ENTRY_CLOSED,memory_order_release);
            syscall(SYS_futex,&t->channel->state,FUTEX_WAKE,1,NULL,NULL,0);
            t->channel=NULL; --b->cooperative_count;
            struct task **at=&b->channels;
            while (*at && *at!=t) at=&(*at)->channel_next;
            if (*at) *at=t->channel_next;
            t->channel_next=NULL;
            if (b->channel_cursor==t) b->channel_cursor=NULL;
        }
        if (result_clear(b,t)) abort();
        *slot = t->indexed; t->indexed = NULL;
        t->pid = 0;
        t->recovering=0;
        if (!t->borrowed && t->process >= 0) { close(t->process); t->process = -1; }
    }
    pthread_mutex_unlock(&b->lock);
    md_fs_worker_wake(b->worker);
}
static uint64_t cooperative_clock(void);
static int perform(struct md_namespace_broker *b, struct operation *op, int prepared,
        struct md_execution_window *window) {
    struct counters *count=!op->channel && b->counts && (unsigned)op->notification.data.nr<512
        ? &b->counts[op->notification.data.nr] : NULL;
    if (prepared>0 && !op->cursor.owner && !op->continuation) {
        if (op->order.table && md_fd_order_state(&op->order)==MD_RESOURCE_ORDER_READY) {
            int error=md_fd_order_start(&op->order);
            if (error) return error;
        }
        if (op->native_plan && !md_entry_open(op->notification.data.nr)) {
            int result=native_descriptor(op);
            if (result==2) { op->waiting=b->waiting; b->waiting=op; return 0; }
            if (result==1) return recycle(b,op);
            if (result<0) { cooperative_reply(op,MD_ENTRY_COMPLETED,result); return recycle(b,op); }
            cooperative_reply(op,MD_ENTRY_DECLINED,0); return recycle(b,op);
        }
        int cwd=0;
        for (unsigned i=0;i<op->syscall.operands;++i)
            cwd|=op->request.path[i] && (*op->request.path[i]!='/' || (op->request.resolve&RESOLVE_IN_ROOT))
                && op->request.directory[i]==AT_FDCWD;
        if (cwd) {
            if (!op->fs_order.fs) {
                int ready=md_fs_order_begin(op->context.task.fs,&op->fs_order,b->wakeup);
                if (ready<0) return ready;
                if (!ready) {
                    /* EVENT_WAIT: the shared CLONE_FS owner completes its cwd
                     * mutation; notification cancellation retires this request. */
                    op->waiting=b->waiting; b->waiting=op;
                    return window ? md_execution_window_end(window) : 0;
                }
            }
            int error=md_fs_order_start(&op->fs_order);
            if (error) return error;
        }
        struct md_cost *cost=b->counts ? &b->capture : NULL;
        int64_t start=md_cost_begin(cost);
        prepared=capture(op);
        md_cost_end(cost,start);
        if (op->fs_order.fs) {
            int error=md_fs_order_complete(&op->fs_order);
            if (error) return error;
        }
        if (prepared>0 && op->request.operation==MD_FS_GETDENTS) {
            int ready=md_ofd_order_begin(md_fs_worker_cursors(b->worker),op->directory,0,&op->cursor,b->wakeup);
            if (ready<0) prepared=ready;
            else if (!ready) {
                /* EVENT_WAIT: copied bytes are acknowledged by the current
                 * OFD owner; notification cancellation retires this reader. */
                op->waiting=b->waiting; b->waiting=op;
                return window ? md_execution_window_end(window) : 0;
            }
        }
    }
    if (prepared>0 && op->cursor.owner && !op->continuation) {
        int error=md_ofd_order_start(&op->cursor);
        if (error) return error;
    }
    if (op->owned_open && op->order.table) {
        int error=md_fd_order_complete(&op->order);
        if (error) return error;
    }
    if (window) {
        struct md_inode_store *scope=prepared>0 ? md_fs_context_operation_window(&op->context,&op->request,op->native_plan) : NULL;
        int error=md_execution_window_admit(window,scope,md_inode_operation_window_begin,md_inode_operation_window_end,
            cooperative_clock(),EXECUTION_BURST_CALLS,EXECUTION_BURST_NS);
        if (error) return error;
    }
    if (prepared) {
        if (prepared>0) {
            struct md_cost *cost=b->counts ? &b->execution : NULL;
            int64_t start=md_cost_begin(cost);
            execute(op,b->output,sizeof(b->output));
            if (window && op->result.error==-EAGAIN && op->result.open_completion.kind==MD_OPEN_NEEDS_WRITE) {
                int error=md_execution_window_end(window);
                if (error) return error;
                execute(op,b->output,sizeof(b->output));
            }
            md_cost_end(cost,start);
            if (op->continuation) {
                if (op->order.table) {
                    int error=md_fd_order_complete(&op->order); if (error) return error;
                }
                /* EVENT_WAIT: provider reply or exact caller cancellation.
                 * Captured operands survive, but shared table/store turns do
                 * not block the daemon that must answer this request. */
                op->waiting=b->waiting; b->waiting=op;
                return window ? md_execution_window_end(window) : 0;
            }
        }
        else op->result.error=prepared;
        struct md_cost *cost=b->counts ? &b->reply : NULL;
        int64_t start=md_cost_begin(cost);
        int result=reply(op,b->output);
        md_cost_end(cost,start);
        if (result==2) {
            op->waiting=b->waiting; b->waiting=op;
            return window && op->native_plan!=2 ? md_execution_window_end(window) : 0;
        }
        if (result) {
            if (count) count->handled++;
            int error=recycle(b,op); return result<0 ? result : error;
        }
    }
    if (op->order.table) {
        int error=md_fd_order_complete(&op->order);
        if (error) return error;
    }
    if (op->cursor.owner) {
        int error=md_ofd_order_complete(&op->cursor);
        if (error) return error;
    }
    /* Delegation has not committed guest-visible IO; authenticate the later
     * task-affine request independently, without holding this table's turn. */
    md_fs_context_end(&op->context);
    int closed=window ? md_execution_window_end(window) : 0;
    if (closed) return closed;
    if (op->channel) {
        cooperative_reply(op,MD_ENTRY_DECLINED,0);
        return recycle(b,op);
    }
    md_fs_worker_publish(b->worker,&op->work);
    if (count) count->delegated++;
    return 0;
}
static void retirement_complete(void *context,int error) {
    struct operation *op=context;
    op->retirement_error=error;
    atomic_store_explicit(&op->retirement_done,1,memory_order_release);
    md_fs_worker_wake(op->owner->worker);
}
static int retirement_start(struct md_fs_work *work) {
    struct operation *op=(void *)((char *)work - offsetof(struct operation,retirement_work));
    struct md_broker_retirement request={.task=(pid_t)op->notification.pid,.number=op->provisional_fd,
        .turn=&op->order,.loan=op->custody,.complete=retirement_complete,.context=op};
    return op->owner->retire ? op->owner->retire(&request) : -ENOTSUP;
}
static int native_progress(struct md_namespace_broker *b,struct md_execution_window *window,unsigned *pending) {
    if (pending) *pending=0;
    struct operation **at=&b->waiting;
    while (*at) {
        struct operation *op=*at;
        if (op->native_plan==3) {
            if (!atomic_load_explicit(&op->retirement_done,memory_order_acquire)) { at=&op->waiting; continue; }
            if (op->retirement_error) return op->retirement_error;
            *at=op->waiting; op->waiting=NULL;
            int discarded=md_inode_birth_discard(&op->birth);
            if (discarded) return discarded;
            /* A nonexclusive loser has no published effect and no FD left.
             * Resolve the winning name through the normal open owner. */
            int retry=op->native_result==-EEXIST && !(op->request.flags&O_EXCL);
            cooperative_reply(op,retry ? MD_ENTRY_DECLINED : MD_ENTRY_COMPLETED,op->native_result);
            int error=recycle(b,op); if (error) return error;
            continue;
        }
        if (op->native_plan==2) {
            unsigned state=atomic_load_explicit(&op->channel->native.state,memory_order_acquire);
            if (state!=MD_NATIVE_DONE && state!=MD_NATIVE_UNKNOWN && state!=MD_NATIVE_CANCELLED) {
                if (pending) ++*pending;
                at=&op->waiting; continue;
            }
            long value=op->channel->native.result;
            md_cost_end(b->counts ? &b->native_roundtrip : NULL,op->native_started);
            int64_t start=md_cost_begin(b->counts ? &b->native_retire : NULL);
            int error=0;
            if (state==MD_NATIVE_UNKNOWN) {
                md_fd_origins_abandon(&op->update);
                error=md_fd_mutation_abandon(&op->mutation,op->context.task.files);
            } else if (state==MD_NATIVE_CANCELLED) {
                md_fd_origins_finish(&op->update,-1);
                md_fd_mutation_discard(&op->mutation);
            } else {
                if (op->birth && value>=0) {
                    /* The birth owns its store. Reuse only that exact gate,
                     * within the same budget as ordinary operation admission. */
                    if (window && window->owner) {
                        error=md_execution_window_admit(window,md_inode_birth_store(op->birth),
                            md_inode_operation_window_begin,md_inode_operation_window_end,
                            cooperative_clock(),EXECUTION_BURST_CALLS,EXECUTION_BURST_NS);
                        if (error) return error;
                    }
                    error=md_fs_anchor_policy(op->output_origin)&MD_MOUNT_READONLY ? -EROFS
                        : md_inode_birth_commit(&op->birth);
                    /* An accepted rename with a retirement error is terminal,
                     * not permission to close and retry the original open. */
                    if (error && !op->birth) return error;
                    if (error) {
                        md_fd_origins_finish(&op->update,(int)value);
                        op->native_result=error; op->provisional_fd=(int)value;
                        pthread_mutex_lock(&b->lock);
                        op->native_plan=3;
                        atomic_store_explicit(&op->channel->native.state,MD_NATIVE_EMPTY,memory_order_release);
                        pthread_mutex_unlock(&b->lock);
                        atomic_store_explicit(&op->retirement_done,0,memory_order_relaxed);
                        op->retirement_work.complete=retirement_start;
                        md_fs_worker_publish(b->worker,&op->retirement_work);
                        at=&op->waiting; continue;
                    }
                    md_fd_origins_release_custody(&op->update);
                }
                md_fd_origins_finish(&op->update,value<0 ? -1 : (int)value);
                error=md_fd_mutation_finish(&op->mutation,op->context.task.files,value);
            }
            if (error) return error;
            *at=op->waiting; op->waiting=NULL;
            struct md_entry_channel *c=op->channel;
            /* A signal before the native effect is a withdrawn plan, not a
             * syscall returning EINTR. Once its owners retire, the original
             * caller may enter the kernel normally. Never replay DONE (even
             * close(-EINTR), which already released its Linux descriptor). */
            enum md_entry_outcome outcome=state==MD_NATIVE_UNKNOWN ? MD_ENTRY_UNKNOWN
                : state==MD_NATIVE_CANCELLED ? MD_ENTRY_DECLINED : MD_ENTRY_COMPLETED;
            int notified=md_entry_native_retire(c,op->sequence,outcome,value);
            if (notified<0) return notified;
            if (notified) {
                if (atomic_load_explicit(&c->sleeping,memory_order_seq_cst))
                    syscall(SYS_futex,&c->state,FUTEX_WAKE,1,NULL,NULL,0);
            } else if (b->counts) ++b->native_deferred;
            if (b->counts) {
                if (outcome==MD_ENTRY_DECLINED) ++b->cooperative_declined;
                else ++b->cooperative_completed;
            }
            md_cost_end(b->counts ? &b->native_retire : NULL,start);
            error=recycle(b,op); if (error) return error;
            continue;
        }
        at=&op->waiting;
    }
    return 0;
}
static int progress(struct md_namespace_broker *b,struct md_execution_window *window) {
    int result=0;
    /* Only the worker enqueues receipts. Supervisor cancellation may remove
     * them; a nonempty hint still requires the lock and exact live queue. */
    if (atomic_load_explicit(&b->results_pending,memory_order_acquire)) {
        pthread_mutex_lock(&b->lock);
        struct task **pending=&b->result_waiting;
        while (*pending) {
            struct task *t=*pending;
            result=result_progress(b,t);
            if (result<0) break;
            if (result) { pending=&t->result.next; continue; }
            *pending=t->result.next; t->result.next=NULL; t->result.queued=0;
        }
        atomic_store_explicit(&b->results_pending,!!b->result_waiting,memory_order_release);
        pthread_mutex_unlock(&b->lock);
    }
    if (result<0) return result;
    result=native_progress(b,window,NULL);
    if (result) return result;
    struct operation **at=&b->waiting;
    while (*at) {
        struct operation *op=*at;
        if (op->native_plan==2 || op->native_plan==3) { at=&op->waiting; continue; }
        if (op->completion.active) {
            int done=md_notification_result_progress(&op->completion);
            if (done==MD_NOTIFICATION_STATUS) done=md_notification_result_reply(&op->completion);
            result_observed(b,done);
            if (done<0) return done;
            if (done==MD_NOTIFICATION_WAIT) { at=&op->waiting; continue; }
            *at=op->waiting; op->waiting=NULL;
            if (done==MD_NOTIFICATION_CANCELLED) {
                recover(op); md_fs_worker_publish(b->worker,&op->work);
            } else {
                int error=recycle(b,op); if (error) return error;
            }
            continue;
        }
        /* A stopped caller cannot finish a queued request while its table/OFD
         * owner is held by the same barrier. No effect has started here: retire
         * the reservation before publishing the stop, then let entry fall back.
         * Native effects and pending result delivery have separate owners above. */
        int interrupted=0;
        if (op->channel && !op->executed) {
            pthread_mutex_lock(&b->lock);
            interrupted=op->task->stopped;
            pthread_mutex_unlock(&b->lock);
        }
        if (interrupted) {
            *at=op->waiting; op->waiting=NULL;
            cooperative_reply(op,MD_ENTRY_DECLINED,0);
            int error=recycle(b,op); if (error) return error;
            continue;
        }
        int alive=valid(op);
        if (op->continuation) {
            if (alive && !op->continuation->ready(op->continuation)) { at=&op->waiting; continue; }
        } else if (alive && (op->cursor.owner
                ? md_ofd_order_state(&op->cursor)!=MD_RESOURCE_ORDER_READY
                : md_fd_order_state(&op->order)!=MD_RESOURCE_ORDER_READY
                    && md_fs_order_state(&op->fs_order)!=MD_RESOURCE_ORDER_READY)) { at=&op->waiting; continue; }
        *at=op->waiting; op->waiting=NULL;
        int error=alive ? perform(b,op,1,window) : recycle(b,op);
        if (error) return error;
    }
    return 0;
}
static int complete(struct md_fs_work *work) {
    struct operation *op=(struct operation *)work;
    struct md_namespace_broker *b=op->owner;
    if (op->recovering && op->task && op->task->pid==(int)op->notification.pid) {
        struct md_broker_completion result={.call=op->notification.data,.result=op->result,
            .notification_live=valid(op),
            .operation=op->request.operation,.flags=op->request.flags,
            .opened={.fd=op->result.fd,.origin=op->output_origin,.object=op->output_object,
                .completion=op->result.open_completion}};
        if (op->completion.active && op->completion.source<0) result.result.error=(int)op->completion.value;
        op->result.fd=-1; op->output_origin=NULL; op->output_object=NULL;
        op->result.open_completion=(struct md_open_completion){0};
        if (result.result.error) md_fs_buffer_open_release(&result.opened);
        b->recover((pid_t)op->notification.pid,&result);
    } else if (valid(op)) b->delegate(&op->notification);
    return recycle(b,op);
}
static uint64_t cooperative_clock(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC,&ts)) return 0;
    return (uint64_t)ts.tv_sec*1000000000ULL+ts.tv_nsec;
}
static int cooperative_wake_reply(struct md_namespace_broker *b,const struct seccomp_notif *wake) {
    if (!wake) return 0;
    struct seccomp_notif_resp response={.id=wake->id,.val=wake->pid};
    return ioctl(b->listener,SECCOMP_IOCTL_NOTIF_SEND,&response) && errno!=ENOENT ? -errno : 0;
}
/* Caller holds the broker lock. Service includes declining a request: a
 * restarted SVC may enter shared memory while its kernel result is retained. */
static int channel_available(const struct task *t) {
    return !t->operation && !t->stopped;
}
/* The existing worker owns execution. A bounded burst cannot starve ordinary
 * notifications, resource turns, RPC peers, watches or shutdown. */
static int cooperative_progress(struct md_namespace_broker *b,struct md_filesystem *fs,
        const struct seccomp_notif *wake) {
    if (!atomic_load_explicit(&b->cooperative_count,memory_order_acquire)) return cooperative_wake_reply(b,wake);
    pthread_mutex_lock(&b->lock);
    if (!b->cooperative_count) { pthread_mutex_unlock(&b->lock); return cooperative_wake_reply(b,wake); }
    for (struct task *t=b->channels;t;t=t->channel_next)
        atomic_store_explicit(&t->channel->awake,1,memory_order_seq_cst);
    pthread_mutex_unlock(&b->lock);
    uint64_t start=cooperative_clock(), idle=start;
    const uint64_t burst_budget=EXECUTION_BURST_NS;
    struct md_execution_window window={0};
    unsigned completed=0, idle_checks=0;
    unsigned spin=md_spin_budget(&b->spin,25000);
    int spinning=0;
    int error=0;
    do {
        error=progress(b,&window); if (error) break;
        pthread_mutex_lock(&b->lock);
        struct task *t=wake ? find_task(b,(int)wake->pid)
            : b->channel_cursor ? b->channel_cursor->channel_next : b->channels;
        if (t && !t->channel) t=NULL;
        if (!t) t=b->channels;
        unsigned left=b->cooperative_count;
        int native_pending=0, effect_pending=0, runnable=0;
        while (t && left && (!channel_available(t)
                || atomic_load_explicit(&t->channel->state,memory_order_acquire)!=MD_ENTRY_REQUEST)) {
            native_pending|=atomic_load_explicit(&t->channel->state,memory_order_acquire)==MD_ENTRY_FD_REQUEST;
            effect_pending|=t->operation && t->operation->native_plan==2;
            runnable|=channel_available(t);
            t=t->channel_next ? t->channel_next : b->channels; --left;
        }
        if (!left) t=NULL;
        if (!t) {
            pthread_mutex_unlock(&b->lock);
            /* Stopped callers and resource waiters cannot publish a next
             * request. Release the window for their event owners instead of
             * spending (and penalizing) the idle-spin budget. */
            if ((!runnable && !effect_pending) || (!completed && !effect_pending) || !spin) break;
            /* An FD request needs the listener, not another shared-memory
             * turn. Do not spend the idle spin delaying its kernel carrier. */
            if (native_pending) {
                if (b->counts) ++b->cooperative_native_yields;
                break;
            }
            spinning=1;
            if (!(idle_checks++%8)) {
                uint64_t now=cooperative_clock();
                if (!now || now<idle || now-idle>=spin) {
                    md_spin_result(&b->spin,0);
                    if (b->counts) ++b->burst_idle_limit;
                    break;
                }
            }
            __asm__ volatile("yield" ::: "memory"); continue;
        }
        b->channel_cursor=t;
        if (spinning) {
            md_spin_observed(&b->spin,1,cooperative_clock(),idle+spin); spinning=0;
        }
        struct operation *op=b->free;
        if (op) b->free=op->free;
        else {
            op=calloc(1,sizeof(*op));
            if (!op) { pthread_mutex_unlock(&b->lock); error=-ENOMEM; break; }
            op->all=b->all; b->all=op;
        }
        op->owner=b; op->task=t; ++t->borrowed; t->operation=op;
        op->channel=t->channel; op->sequence=t->channel->sequence;
        op->captured_path[0]=op->captured_path[1]=0;
        capture_input_paths(op,op->channel);
        struct md_entry_request call=t->channel->call;
        int native=md_entry_native_fd(call.number,call.args)
            || md_entry_native_open(call.number,call.args);
        enum md_entry_route route=md_entry_route(call.number);
        /* A retained result owns the interrupted syscall. Decline the
         * unexecuted entry so its original kernel route can take that result. */
        int admitted=!t->recovering && (t->eligible&MD_BROKER_COOPERATIVE)
            && (route==MD_ENTRY_WORKER || route==MD_ENTRY_VALUE || native)
            && !call.reserved && t->sequence!=UINT64_MAX && op->sequence==t->sequence+1;
        t->sequence=op->sequence;
        atomic_store_explicit(&t->channel->state,MD_ENTRY_RUNNING,memory_order_release);
        op->notification=(struct seccomp_notif){.pid=t->pid,.data={.nr=call.number}};
        memcpy(op->notification.data.args,call.args,sizeof(call.args));
        op->directory=op->second_directory=op->control_descriptor=-1; op->undelivered=0;
        op->owned_open=op->executed=op->recovering=0;
        op->native_plan=admitted && native;
        op->syscall=(struct md_fs_syscall){0}; op->request=(struct md_fs_request){0};
        op->result=(struct md_fs_result){.fd=-1};
        pthread_mutex_unlock(&b->lock);
        if (admitted && route==MD_ENTRY_VALUE) {
            error=md_execution_window_end(&window);
            if (!error) {
                struct md_task_credentials credentials;
                long result=md_task_registry_credentials(fs->tasks,(int)op->notification.pid,&credentials);
                if (!result) {
                    result=credentials.retired ? -ESRCH
                        : md_identity_scalar_result(&credentials.identity,call.number);
                    md_identity_release(&credentials.identity);
                }
                cooperative_reply(op,MD_ENTRY_COMPLETED,result);
            }
            int retired=recycle(b,op);
            if (!error) error=retired;
            goto settled;
        }
        int prepared=admitted ? prepare(fs,op,sizeof(b->output)) : 0;
        int descriptors=op->native_plan;
        for (unsigned i=0;prepared>0 && i<op->syscall.operands;++i)
            descriptors|=!op->request.path[i] || *op->request.path[i]!='/';
        if (prepared>0 && descriptors) {
            int ready=md_fd_order_begin(op->context.task.files,&op->order,b->wakeup);
            if (ready<0) prepared=ready;
            else if (!ready) {
                error=md_execution_window_end(&window);
                op->waiting=b->waiting; b->waiting=op; goto settled;
            }
        }
        error=perform(b,op,prepared,&window);
settled:
        /* Return the waking task after its immediate work, not before. This
         * avoids a second client park/context switch on a sleeping worker.
         * A resource-waiting operation still uses its channel completion. */
        {
            int replied=cooperative_wake_reply(b,wake); wake=NULL;
            if (!error) error=replied;
        }
        ++completed; idle=cooperative_clock(); idle_checks=0;
    } while (!error && completed<EXECUTION_BURST_CALLS && idle-start<burst_budget);
    if (b->counts) {
        ++b->bursts; b->burst_requests+=completed;
        b->burst_count_limit+=completed==EXECUTION_BURST_CALLS;
        b->burst_time_limit+=idle-start>=burst_budget;
    }
    int closed=md_execution_window_end(&window);
    if (!error) error=closed;
    int replied=cooperative_wake_reply(b,wake);
    if (!error) error=replied;
    if (!error && (completed==EXECUTION_BURST_CALLS || idle-start>=burst_budget)) {
        /* Finish only effects already admitted in this burst. No new request
         * or guessed continuation is consumed, and no store window is held. */
        unsigned pending=0;
        error=native_progress(b,NULL,&pending);
        uint64_t now=!error && pending ? cooperative_clock() : 0,deadline=now+25000;
        for (unsigned checks=0;!error && now;++checks) {
            error=native_progress(b,NULL,&pending);
            if (error || !pending) break;
            if (!(checks%8)) {
                now=cooperative_clock();
                if (!now || now>=deadline) break;
            }
            __asm__ volatile("yield" ::: "memory");
        }
    }
    return error;
}
static int prepare_wait(void *context) {
    struct md_namespace_broker *b=context;
    if (!atomic_load_explicit(&b->cooperative_count,memory_order_acquire)) return 0;
    pthread_mutex_lock(&b->lock);
    int pending=0;
    for (struct task *t=b->channels;t;t=t->channel_next) {
        unsigned ready=md_entry_prepare_wait(t->channel);
        pending|=channel_available(t)
            && (ready&MD_ENTRY_PENDING_CALL);
        pending|=t->operation && t->operation->native_plan==2
            && (ready&MD_ENTRY_PENDING_NATIVE);
    }
    pthread_mutex_unlock(&b->lock);
    return pending;
}
static int discovery_sample(struct task *task,uintptr_t pc) {
    for (unsigned i=0;i<DISCOVERY_RECENT;++i)
        if (task->discovery.recent[i]==pc) return 0;
    /* Bound repeat suppression, not discovery: a miss always gets an attempt.
     * Code admission and pool capacity belong to the supervisor's registry. */
    task->discovery.recent[task->discovery.next]=pc;
    task->discovery.next=(task->discovery.next+1)%DISCOVERY_RECENT;
    return 1;
}
static int receive_one(struct md_namespace_broker *b,struct md_filesystem *fs,
        struct md_execution_window *window) {
    struct seccomp_notif q = {0};
    if (ioctl(b->listener, SECCOMP_IOCTL_NOTIF_RECV, &q)) {
        return errno == EINTR || errno == ENOENT ? 0 : -errno;
    }
    struct counters *count = b->counts && (unsigned)q.data.nr < 512 ? &b->counts[q.data.nr] : NULL;
    if (count) count->received++;
    pthread_mutex_lock(&b->lock);
    int eligible = 0, watch = 0;
    struct task *task = find_task(b, (int)q.pid);
    if (task && task->channel && task->operation && task->operation->native_plan==2) {
        /* A caller can already be in its next kernel syscall. Retire the
         * completed native result before selecting that syscall's route. */
        pthread_mutex_unlock(&b->lock);
        int error=md_execution_window_end(window);
        if (!error) error=progress(b,NULL);
        if (error) return error;
        pthread_mutex_lock(&b->lock);
        task=find_task(b,(int)q.pid);
    }
    if (task && task->channel && q.data.nr==SYS_gettid
            && q.data.instruction_pointer==task->cooperative_gate && q.data.args[0]==MD_ENTRY_WAKE) {
        pthread_mutex_unlock(&b->lock);
        if (b->counts) ++b->cooperative_wakes;
        int result=md_execution_window_end(window);
        if (!result) result=cooperative_progress(b,fs,&q);
        if (!result && count) ++count->handled;
        return result;
    }
    if (task && task->channel) atomic_store_explicit(&task->channel->native_regular,0,memory_order_release);
    int delivered=result_receive(b,task,&q);
    if (delivered) {
        pthread_mutex_unlock(&b->lock);
        if (count && delivered>0) count->handled++;
        int closed=md_execution_window_end(window);
        return delivered<0 ? delivered : closed;
    }
    if (task) {
        eligible = task->eligible && !task->stopped && !task->recovering && q.data.instruction_pointer != task->gate;
        for (unsigned i=0;eligible && i<task->machine.count;++i)
            if (q.data.instruction_pointer-task->machine.starts[i]<task->machine.size) eligible=0;
        if (eligible && (task->eligible&MD_BROKER_DISCOVERY) && md_entry_supported(q.data.nr)) {
            /* Delegate an unexecuted request through the normal stop owner.
             * The worker never installs code or reads registers. */
            if (discovery_sample(task,q.data.instruction_pointer)) eligible=0;
        }
        watch = q.data.instruction_pointer == task->watch_gate;
        task->borrowed++;
    }
    if (watch) {
        pthread_mutex_unlock(&b->lock);
        int closed=md_execution_window_end(window);
        int fd = duplicate(task, q.pid, (int)q.data.args[q.data.nr == SYS_sendfile ? 1 : 0]);
        pthread_mutex_lock(&b->lock);
        task_release(task);
        pthread_mutex_unlock(&b->lock);
        if (closed) { if (fd>=0) close(fd); return closed; }
        if (!b->watches) b->watches = md_watch_broker_create();
        if (!b->watches) { if (fd >= 0) close(fd); return -ENOMEM; }
        return md_watch_broker_submit(b->watches, fs->store, b->listener, &q, fd);
    }
    struct operation *op = b->free;
    if (op) b->free = op->free;
    pthread_mutex_unlock(&b->lock);
    if (!op) {
        op = calloc(1, sizeof(*op));
        if (!op) {
            pthread_mutex_lock(&b->lock);
            if (task) task_release(task);
            pthread_mutex_unlock(&b->lock);
            return -ENOMEM;
        }
        op->all = b->all; b->all = op;
    }
    op->notification = q; op->listener = b->listener;
    op->channel=NULL;
    op->captured_path[0]=op->captured_path[1]=0;
    op->directory = op->second_directory = op->control_descriptor = -1; op->undelivered = 0;
    op->owned_open=op->executed=op->recovering=0;
    op->native_plan=0;
    op->owner=b; op->work.complete=complete;
    op->task=task;
    pthread_mutex_lock(&b->lock);
    if (task && eligible) {
        eligible=!task->stopped && !task->recovering && !task->operation;
        if (eligible) {
            task->operation=op;
            struct md_entry_channel *c=task->channel;
            if (c && (task->eligible&MD_BROKER_COOPERATIVE)
                    && atomic_load_explicit(&c->state,memory_order_acquire)==MD_ENTRY_FD_REQUEST
                    && md_entry_route(q.data.nr)==MD_ENTRY_FD
                    && c->call.number==q.data.nr && !c->call.reserved
                    && !memcmp(c->call.args,q.data.args,sizeof(c->call.args))
                    && task->sequence!=UINT64_MAX && c->sequence==task->sequence+1) {
                op->sequence=c->sequence; task->sequence=c->sequence;
                memcpy(op->notification.data.args,c->call.args,sizeof(c->call.args));
                /* Copy before releasing the channel record. A signal or next
                 * request cannot overwrite these worker-owned path bytes. */
                capture_input_paths(op,c);
                if (b->counts) b->carrier_paths+=!!op->captured_path[0]+!!op->captured_path[1];
                /* The exact kernel notification now owns the request and all
                 * remaining guest-memory access. Retire only the copied channel
                 * record, not the syscall or descriptor. The caller must still
                 * complete its original SVC; no second retirement wait is needed
                 * after ADDFD_SEND has already returned the authoritative FD. */
                channel_reply(b,c,op->sequence,MD_ENTRY_COMPLETED,0);
                if (b->counts) ++b->cooperative_descriptors;
            }
        }
    }
    pthread_mutex_unlock(&b->lock);
    op->result = (struct md_fs_result){.fd = -1};
    int prepared = eligible ? prepare(fs, op, sizeof(b->output)) : 0;
    /* Absolute metadata/link lookups neither borrow nor publish a caller FD.
     * Task-relative proc objects delegate before execution and acquire their
     * own table turn. Do not serialize ordinary pathname reads with close/dup. */
    if (prepared>0 && op->request.operation!=MD_FS_FSOPEN
            && (*op->path!='/' || op->request.operation==MD_FS_OPEN)) {
        int ready=md_fd_order_begin(op->context.task.files,&op->order,b->wakeup);
        if (ready<0) prepared=ready;
        else if (!ready) {
            op->waiting=b->waiting; b->waiting=op;
            return md_execution_window_end(window);
        }
    }
    return perform(b,op,prepared,window);
}
static int receive(void *context,struct md_filesystem *fs,short events) {
    struct md_namespace_broker *b=context;
    if (!events) {
        int error=progress(b,NULL);
        if (!error) error=cooperative_progress(b,fs,NULL);
        return error ? error : b->watches ? md_watch_broker_progress(b->watches,fs->store) : 0;
    }
    if (events&(POLLERR|POLLNVAL)) return -EIO;
    if (!(events&POLLIN)) {
        if (events&POLLHUP) md_fs_worker_notifications(b->worker,-1,b,receive,NULL);
        return 0;
    }
    struct md_execution_window window={0};
    uint64_t start=cooperative_clock();
    unsigned calls=0;
    int error;
    do {
        error=receive_one(b,fs,&window);
        ++calls;
        if (error || !window.owner || calls==EXECUTION_BURST_CALLS || cooperative_clock()-start>=EXECUTION_BURST_NS) break;
        /* Only queued kernel-admitted calls share this read scope. Never wait
         * for a future call or carry the scope into another event-loop owner. */
        struct pollfd ready={.fd=b->listener,.events=POLLIN};
        int n=poll(&ready,1,0);
        if (n<0) { error=errno==EINTR ? 0 : -errno; break; }
        if (!n || !(ready.revents&POLLIN)) break;
    } while (1);
    if (b->counts) { ++b->notification_batches; b->notification_batch_calls+=calls; }
    int closed=md_execution_window_end(&window);
    return error ? error : closed;
}
void md_broker_listen(struct md_namespace_broker *b, int listener) {
    b->listener = listener;
#ifdef SECCOMP_IOCTL_NOTIF_SET_FLAGS
    /* Optional scheduling hint, not a kernel prerequisite or an affinity policy. */
    b->synchronous_wake = !ioctl(listener, SECCOMP_IOCTL_NOTIF_SET_FLAGS, SECCOMP_USER_NOTIF_FD_SYNC_WAKE_UP);
#endif
    md_fs_worker_notifications(b->worker, listener, b, receive, prepare_wait);
}
void md_broker_destroy(struct md_namespace_broker *b) {
    if (!b) return;
    md_watch_broker_destroy(b->watches);
    /* The worker must have joined before operation storage is released. */
    if (b->counts) {
        fprintf(stderr,"MD_BROKER resultDeliveries=%llu resultWaits=%llu resultCancelled=%llu\n",
            (unsigned long long)b->result_deliveries,(unsigned long long)b->result_waits,
            (unsigned long long)b->result_cancelled);
        fprintf(stderr, "MD_BROKER synchronousWake=%d\n", b->synchronous_wake);
        fprintf(stderr,"MD_BROKER cooperativeCompleted=%llu cooperativeDeclined=%llu cooperativeWakes=%llu\n",
            (unsigned long long)b->cooperative_completed,(unsigned long long)b->cooperative_declined,
            (unsigned long long)b->cooperative_wakes);
        fprintf(stderr,"MD_BROKER cooperativeDescriptors=%llu\n",(unsigned long long)b->cooperative_descriptors);
        fprintf(stderr,"MD_BROKER carrierPaths=%llu\n",(unsigned long long)b->carrier_paths);
        fprintf(stderr,"MD_BROKER cooperativeNativeYields=%llu\n",(unsigned long long)b->cooperative_native_yields);
        fprintf(stderr,"MD_BROKER nativeOpen=%llu nativeClose=%llu nativeFlags=%llu\n",
            (unsigned long long)b->native_open,(unsigned long long)b->native_close,(unsigned long long)b->native_flags);
        fprintf(stderr,"MD_BROKER nativeDeferred=%llu\n",(unsigned long long)b->native_deferred);
        fprintf(stderr,"MD_BROKER nativeDuplicate=%llu nativeSeek=%llu\n",
            (unsigned long long)b->native_duplicate,(unsigned long long)b->native_seek);
        fprintf(stderr,"MD_BROKER notificationBatches=%llu notificationBatchCalls=%llu\n",
            (unsigned long long)b->notification_batches,(unsigned long long)b->notification_batch_calls);
        fprintf(stderr,"MD_BROKER bursts=%llu requests=%llu countLimit=%llu timeLimit=%llu idleLimit=%llu\n",
            (unsigned long long)b->bursts,(unsigned long long)b->burst_requests,
            (unsigned long long)b->burst_count_limit,(unsigned long long)b->burst_time_limit,
            (unsigned long long)b->burst_idle_limit);
        const struct md_cost *costs[]={&b->preparation,&b->capture,&b->execution,&b->reply,
            &b->native_prepare,&b->native_roundtrip,&b->native_retire,&b->retirement};
        const char *names[]={"prepare","capture","execute","reply","nativePrepare","nativeRoundtrip","nativeRetire","retire"};
        for (unsigned i=0;i<sizeof(costs)/sizeof(*costs);++i)
            fprintf(stderr,"MD_BROKER phase=%s calls=%llu ns=%llu\n",names[i],
                (unsigned long long)costs[i]->calls,(unsigned long long)costs[i]->nanoseconds);
        for (unsigned i = 0; i < 512; ++i) if (b->counts[i].received)
            fprintf(stderr, "MD_BROKER nr=%u notifications=%llu handled=%llu delegated=%llu\n", i,
                (unsigned long long)b->counts[i].received, (unsigned long long)b->counts[i].handled,
                (unsigned long long)b->counts[i].delegated);
        free(b->counts);
    }
    while (b->all) {
        struct operation *op = b->all; b->all = op->all;
        if (md_notification_result_clear(&op->completion)) abort();
        if (op->control_descriptor>=0) close(op->control_descriptor);
        md_fd_object_release(op->output_object);
        md_fd_object_release(op->control_input);
        if (op->directory >= 0) close(op->directory);
        if (op->result.fd >= 0) close(op->result.fd);
        if (op->result.open_completion.kind == MD_OPEN_PIPE) close(op->result.open_completion.control);
        md_fd_origins_finish(&op->update,-1);
        md_fd_loan_end(op->custody); md_fd_loan_release(op->custody);
        md_inode_birth_forget(&op->birth);
        md_fs_anchor_release(op->input_origin); md_fs_anchor_release(op->output_origin);
        if (op->cursor.owner) {
            if (md_ofd_order_cancel(&op->cursor)==1) md_ofd_order_complete(&op->cursor);
        }
        if (op->fs_order.fs) {
            if (md_fs_order_cancel(&op->fs_order)==1) md_fs_order_complete(&op->fs_order);
        }
        if (op->order.table) {
            if (md_fd_order_cancel(&op->order)==1) md_fd_order_complete(&op->order);
        }
        md_fs_context_end(&op->context);
        free(op);
    }
    while (b->tasks) {
        struct task *t = b->tasks; b->tasks = t->next;
        if (result_clear(b,t)) abort();
        if (t->process >= 0) close(t->process);
        free(t);
    }
    md_resource_wakeup_release(b->wakeup);
    pthread_mutex_destroy(&b->lock); free(b);
}
