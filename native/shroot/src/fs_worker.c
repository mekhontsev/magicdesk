#define _GNU_SOURCE
#include "fs_worker.h"
#include "fs_service.h"
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "image_catalogue.h"
#include "event_wait.h"
#include "event_signal.h"
#include "queue_signal.h"
#include "ipc_credentials.h"
#include "ofd_order.h"
#include "input_session.h"
#include "device_events.h"
#include "device_tree.h"
#include "network_resolver.h"
#include "fs_continuation.h"
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <signal.h>
#include <stdatomic.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

struct md_fs_worker {
    pthread_t thread;
    pthread_mutex_t lock;
    struct md_queue_signal wake, done;
    struct md_resource_wakeup *wakeup;
    int stop, ready, error, statistics;
    const char *store, *endpoint, *admit;
    struct md_task_registry *tasks;
    struct md_fd_exchanges *exchanges;
    struct md_mount_namespace *mounts;
    struct md_ofd_orders *cursors;
    const struct md_fs_attachment *attachments;
    unsigned attachment_count;
    const struct md_ipc_inherited *inherited;
    const struct md_input_launch *input;
    const struct md_network_profile *network;
    struct md_device_events *device_events;
    struct md_fs_work *completed, **completed_tail;
    struct md_fs_work *submitted, **submitted_tail;
    struct md_fs_work *waiting, **waiting_tail; /* worker-only continuations */
    int stopping;
    int notification_fd, exited;
    void *notification_context;
    int (*notification)(void *, struct md_filesystem *, short);
    int (*prepare_wait)(void *);
    struct md_fs_worker_pause *pause;
    atomic_int pause_requested;
    atomic_int tid;
    struct md_cost queued, execution, delivery, completion;
};
static struct md_fs_work *take_submitted(struct md_fs_worker *w, int *stopping) {
    pthread_mutex_lock(&w->lock);
    struct md_fs_work *work=(!w->pause || w->stopping) ? w->submitted : NULL;
    if (work) {
        w->submitted=work->next;
        if (!w->submitted) w->submitted_tail=&w->submitted;
        work->next=NULL;
    }
    *stopping=w->stopping;
    pthread_mutex_unlock(&w->lock);
    return work;
}
static int pause_requested(struct md_fs_worker *w) {
    return atomic_load_explicit(&w->pause_requested,memory_order_relaxed);
}
static void execute_work(struct md_fs_worker *w,struct md_filesystem *fs,
        struct md_fs_work *work,int error) {
    int64_t start=md_cost_begin(w->statistics ? &w->execution : NULL);
    work->await=NULL;
    work->run(work,error ? NULL : fs,error);
    if (w->statistics) md_cost_end(&w->execution,start);
    if (work->await && !error) {
        work->next=NULL;
        *w->waiting_tail=work; w->waiting_tail=&work->next;
    } else {
        work->await=NULL;
        md_fs_worker_publish(w,work);
    }
}
static void resume_ready(struct md_fs_worker *w,struct md_filesystem *fs) {
    /* Detach this pass so a multi-stage continuation is not resumed twice in
     * the same pass. No wait exists on ordinary synchronous workloads. */
    struct md_fs_work *list=w->waiting;
    w->waiting=NULL; w->waiting_tail=&w->waiting;
    unsigned resumed=0;
    while (list) {
        struct md_fs_work *work=list; list=work->next; work->next=NULL;
        int state=0;
        if (resumed<32 && !pause_requested(w)) state=work->await(work);
        if (state) { ++resumed; execute_work(w,fs,work,state<0 ? state : 0); }
        else { *w->waiting_tail=work; w->waiting_tail=&work->next; }
    }
    if (resumed==32) md_queue_signal_send(&w->wake);
}
static void run_ready(void *context, struct md_filesystem *fs) {
    struct md_fs_worker *w = context;
    md_queue_signal_acknowledge(&w->wake);
    if (w->waiting) resume_ready(w,fs);
    /* Bound a producer burst without starving socket requests, notifications
     * or stop readiness. No queue lock covers execution or publication. */
    for (unsigned i=0;i<32;++i) {
        int stopping;
        struct md_fs_work *work=take_submitted(w,&stopping);
        if (!work) return;
        if (w->statistics) md_cost_end(&w->queued,work->queued_at);
        execute_work(w,fs,work,stopping ? -ESHUTDOWN : 0);
    }
    pthread_mutex_lock(&w->lock); int more=w->submitted && !w->pause; pthread_mutex_unlock(&w->lock);
    if (more) md_queue_signal_send(&w->wake);
}
static int paused(void *context) {
    struct md_fs_worker *w=context;
    /* Only the service-loop boundary can acknowledge quiescence. Queue
     * admission checks must not publish it before remaining callbacks finish. */
    if (!pause_requested(w)) return 0;
    pthread_mutex_lock(&w->lock);
    int active=w->pause && !w->stopping;
    int acknowledge=active && !w->pause->acknowledged;
    if (acknowledge) {
        w->pause->acknowledged_at=md_event_now();
        w->pause->acknowledged=1;
    }
    pthread_mutex_unlock(&w->lock);
    if (acknowledge) md_queue_signal_send(&w->done);
    return active;
}
static int notification_fd(void *context) {
    struct md_fs_worker *w = context;
    pthread_mutex_lock(&w->lock); int fd = w->notification_fd; pthread_mutex_unlock(&w->lock);
    return fd;
}
static int notification(void *context, struct md_filesystem *fs, short events) {
    struct md_fs_worker *w = context;
    pthread_mutex_lock(&w->lock);
    void *owner = w->notification_context;
    int (*ready)(void *, struct md_filesystem *, short) = w->notification;
    pthread_mutex_unlock(&w->lock);
    return ready(owner, fs, events);
}
static int prepare_wait(void *context) {
    struct md_fs_worker *w=context;
    pthread_mutex_lock(&w->lock);
    void *owner=w->notification_context;
    int (*prepare)(void *)=w->prepare_wait;
    pthread_mutex_unlock(&w->lock);
    return prepare ? prepare(owner) : 0;
}
/* Resource lifetime is independent of the thread entry and queue transport.
 * Only the worker thread enters this runtime; close also accepts partial open. */
struct worker_runtime {
    struct md_fs_storage_cache storage;
    struct md_filesystem fs;
    struct md_input_session *input;
    struct md_provider_tree *sysfs, *devices;
    int listener;
};
static int runtime_open(struct md_fs_worker *w,struct worker_runtime *runtime) {
    *runtime=(struct worker_runtime){.listener=-1};
    struct md_filesystem *fs=&runtime->fs;
    int error=md_inode_store_open(w->store,0,&fs->store);
    if (!error) error=md_inode_runtime_prepare(fs->store);
    if (!error && w->admit) error=md_image_catalogue_open(fs->store,w->admit,&fs->images);
    fs->storage=&runtime->storage; fs->tasks=w->tasks; fs->exchanges=w->exchanges;
    fs->cursors=w->cursors; fs->ipc_store=w->store;
    if (!error) error = md_fs_mounts_open(fs, w->attachments, w->attachment_count);
    if (!error) {
        error=md_network_resolver_prepare(fs,w->network);
        if (error) fprintf(stderr,"Shroot resolver: %s; supply --dns IP or prepare /etc/resolv.conf\n",strerror(-error));
    }
    w->network=NULL;
    if (!error) md_fs_render_prepare(fs);
    if (!error) error=md_fs_sysfs_prepare(fs,&runtime->sysfs);
    if (!error) error=md_fs_devices_prepare(fs,w->input && w->input->endpoint,&runtime->devices);
    fs->devices=runtime->devices;
    if (!error) error=md_fs_proc_prepare(fs);
    if (!error) {
        w->device_events=md_device_events_new();
        if (!w->device_events) error=-errno;
    }
    fs->device_events=w->device_events;
    if (!error) error=md_input_session_start(fs,w->input,w->device_events,runtime->sysfs,&runtime->input);
    w->input=NULL;
    if (!error && w->inherited && w->inherited->count) {
        struct md_ipc_credentials *authority=NULL;
        error=md_fs_ipc_authority(fs,&authority);
        if (!error) error=md_ipc_credentials_inherit(authority,w->inherited);
    }
    w->inherited=NULL;
    if (!error) {
        runtime->listener=md_fs_listen(w->endpoint);
        if (runtime->listener<0) error=runtime->listener;
    }
    return error;
}
static void retire_work(struct md_fs_worker *w,int error) {
    pthread_mutex_lock(&w->lock); w->stopping=1; pthread_mutex_unlock(&w->lock);
    while (w->waiting) {
        struct md_fs_work *work=w->waiting; w->waiting=work->next; work->next=NULL;
        execute_work(w,NULL,work,error ? error : -ESHUTDOWN);
    }
    w->waiting_tail=&w->waiting;
    for (;;) {
        int stopping;
        struct md_fs_work *work=take_submitted(w,&stopping);
        if (!work) break;
        execute_work(w,NULL,work,error ? error : -ESHUTDOWN);
    }
}
static void runtime_close(struct worker_runtime *runtime) {
    struct md_filesystem *fs=&runtime->fs;
    if (runtime->listener>=0) close(runtime->listener);
    md_input_session_stop(runtime->input);
    md_provider_tree_release(runtime->sysfs);
    md_provider_tree_release(runtime->devices);
    md_control_file_release(fs->render_device);
    md_device_events_stop(fs->device_events);
    md_fs_mounts_close(fs);
    md_fs_storage_close(&runtime->storage);
    md_ipc_credentials_close(fs->ipc);
    md_image_catalogue_close(fs->images); md_inode_store_close(fs->store);
}
static void *run(void *context) {
    struct md_fs_worker *w=context;
    atomic_store(&w->tid,(int)syscall(SYS_gettid));
    sigset_t mask;
    sigemptyset(&mask); sigaddset(&mask,SIGPIPE);
    pthread_sigmask(SIG_BLOCK,&mask,NULL);
    struct worker_runtime runtime;
    int error=runtime_open(w,&runtime);
    pthread_mutex_lock(&w->lock);
    w->mounts=md_mount_namespace_retain(runtime.fs.mounts); w->error=error;
    pthread_mutex_unlock(&w->lock);
    md_event_signal(w->ready);
    struct md_inode_statistics stats={0};
    struct md_fs_statistics rpc={0};
    if (!error) {
        if (w->statistics) md_inode_measure(runtime.fs.store,&stats);
        struct md_fs_work_source source={.fd=w->wake.reader,.wakeup=w->wakeup,.context=w,.ready=run_ready,
            .notification_fd=notification_fd,.notification=notification,.prepare_wait=prepare_wait,.paused=paused};
        error=md_fs_serve(&runtime.fs,runtime.listener,w->stop,5000,w->statistics ? &rpc : NULL,&source);
    }
    retire_work(w,error);
    runtime_close(&runtime);
    if (w->statistics) {
        const struct md_cost *costs[] = {&stats.prepare, &stats.step, &stats.transaction, &stats.lock,
            &stats.version,&stats.descriptor,&stats.path,&stats.sync_object,&stats.sync_directory,&stats.commit,&stats.checkpoint};
        const char *names[] = {"prepare", "step", "transaction", "lock", "version", "descriptor", "path",
            "syncObject", "syncDirectory", "commit", "checkpoint"};
        for (unsigned i = 0; i < sizeof(costs)/sizeof(costs[0]); ++i) fprintf(stderr, "MD_STORE phase=%s calls=%llu ns=%llu\n", names[i],
            (unsigned long long)costs[i]->calls, (unsigned long long)costs[i]->nanoseconds);
        fprintf(stderr, "MD_STORE queryReuses=%llu\n", (unsigned long long)stats.query_reuses);
        fprintf(stderr,"MD_STORE walkComponents=%llu walkMaterializations=%llu writeAdmissions=%llu\n",
            (unsigned long long)stats.walk_components,(unsigned long long)stats.walk_materializations,
            (unsigned long long)stats.write_admissions);
        fprintf(stderr,"MD_STORE metadataCacheHits=%llu metadataCacheMisses=%llu\n",
            (unsigned long long)stats.metadata_cache_hits,(unsigned long long)stats.metadata_cache_misses);
        fprintf(stderr,"MD_STORE pathPrefixHits=%llu directoryCheckHits=%llu directoryCheckMisses=%llu\n",
            (unsigned long long)stats.path_prefix_hits,(unsigned long long)stats.directory_check_hits,
            (unsigned long long)stats.directory_check_misses);
        fprintf(stderr,"MD_STORE directoryPinHits=%llu\n",(unsigned long long)stats.directory_pin_hits);
        fprintf(stderr, "MD_STORE linkCountQueries=%llu membershipQueries=%llu\n",
            (unsigned long long)stats.link_count_queries, (unsigned long long)stats.membership_queries);
        md_fs_statistics_report(STDERR_FILENO,&rpc);
        struct rusage usage;
        if (!getrusage(RUSAGE_THREAD, &usage)) fprintf(stderr, "MD_CPU namespaceWorker userUs=%llu systemUs=%llu\n",
            (unsigned long long)usage.ru_utime.tv_sec * 1000000 + usage.ru_utime.tv_usec,
            (unsigned long long)usage.ru_stime.tv_sec * 1000000 + usage.ru_stime.tv_usec);
    }
    pthread_mutex_lock(&w->lock);
    w->error = error ? error : -ESHUTDOWN;
    w->exited = 1;
    pthread_mutex_unlock(&w->lock);
    md_queue_signal_send(&w->done);
    return NULL;
}
int md_fs_worker_start(const char *store, const char *endpoint, const char *admit, struct md_task_registry *tasks, struct md_fd_exchanges *exchanges,
        const struct md_fs_worker_launch *launch, struct md_fs_worker **out) {
    struct md_fs_worker *w = calloc(1, sizeof(*w));
    if (!w) return -ENOMEM;
    w->wake=w->done=(struct md_queue_signal){-1,-1};
    w->stop = w->ready = -1;
    w->notification_fd = -1;
    atomic_init(&w->pause_requested,0);
    atomic_init(&w->tid,0);
    w->store = store; w->endpoint = endpoint; w->admit = admit;
    w->tasks = tasks;
    w->exchanges=exchanges;
    if (launch) {
        w->statistics=launch->statistics;
        w->attachments=launch->attachments; w->attachment_count=launch->attachment_count;
        w->inherited=launch->inherited; w->input=launch->input; w->network=launch->network;
    }
    w->completed_tail = &w->completed;
    w->submitted_tail = &w->submitted;
    w->waiting_tail = &w->waiting;
    int *fds[] = {&w->stop, &w->ready};
    int error = pthread_mutex_init(&w->lock, NULL);
    if (error) { free(w); return -error; }
    error=md_ofd_orders_new(&w->cursors);
    if (error) goto failed;
    for (unsigned i = 0; i < 2; ++i) {
        *fds[i] = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        if (*fds[i] < 0) { error = -errno; goto failed; }
    }
    error=md_queue_signal_open(&w->wake);
    if (!error) error=md_queue_signal_open(&w->done);
    if (!error) error=md_resource_wakeup_create_pipe(w->wake.reader,w->wake.writer,&w->wakeup);
    if (error) goto failed;
    error = pthread_create(&w->thread, NULL, run, w);
    if (error) { error = -error; goto failed; }
    /* EVENT_WAIT: worker publishes namespace/listener readiness; deadline fails
     * startup. The guardian bounds teardown if kernel filesystem IO is stuck. */
    error = (int)md_event_wait_fd(w->ready, POLLIN, md_event_now() + 30000000000LL);
    if (error >= 0) { pthread_mutex_lock(&w->lock); error = w->error; pthread_mutex_unlock(&w->lock); }
    if (error < 0) {
        if (md_fs_worker_stop(w) == -ETIMEDOUT) _exit(125);
        return error;
    }
    *out = w; return 0;
failed:
    md_resource_wakeup_release(w->wakeup);
    md_queue_signal_close(&w->wake); md_queue_signal_close(&w->done);
    for (unsigned i = 0; i < 2; ++i) if (*fds[i] >= 0) close(*fds[i]);
    md_ofd_orders_release(w->cursors);
    pthread_mutex_destroy(&w->lock); free(w); return error;
}
int md_fs_worker_fd(struct md_fs_worker *w) { return w ? w->done.reader : -1; }
struct md_resource_wakeup *md_fs_worker_wakeup(struct md_fs_worker *w) { return w ? w->wakeup : NULL; }
struct md_mount_namespace *md_fs_worker_mounts(struct md_fs_worker *w) { return w ? w->mounts : NULL; }
struct md_device_events *md_fs_worker_device_events(struct md_fs_worker *w) { return w ? w->device_events : NULL; }
struct md_ofd_orders *md_fs_worker_cursors(struct md_fs_worker *w) { return w ? w->cursors : NULL; }
void md_fs_worker_wake(struct md_fs_worker *w) { if (w) md_queue_signal_send(&w->wake); }
void md_fs_worker_describe(struct md_fs_worker *w,int output) {
    if (!w) return;
    int tid=atomic_load(&w->tid);
    char path[80],state[256];
    snprintf(path,sizeof(path),"/proc/self/task/%d/syscall",tid);
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    ssize_t size=fd<0 ? -1 : read(fd,state,sizeof(state)-1);
    int error=size<0 ? errno : 0;
    if (fd>=0) close(fd);
    if (size>0 && state[size-1]=='\n') --size;
    if (size>=0) state[size]=0;
    dprintf(output,"Shroot: worker tid=%d paused=%d syscall=%s error=%d\n",
        tid,atomic_load(&w->pause_requested),size>=0 ? state : "unavailable",error);
    pthread_mutex_lock(&w->lock);
    unsigned pending=0,completed=0;
    for (struct md_fs_work *p=w->submitted;p;p=p->next) ++pending;
    for (struct md_fs_work *p=w->completed;p;p=p->next) ++completed;
    dprintf(output,"Shroot: worker pending=%u completed=%u listener=%d\n",pending,completed,w->notification_fd);
    pthread_mutex_unlock(&w->lock);
}
int md_fs_worker_pause_begin(struct md_fs_worker *w, struct md_fs_worker_pause *p) {
    if (!w || !p || p->owner) return -EINVAL;
    pthread_mutex_lock(&w->lock);
    int error=w->stopping ? -ESHUTDOWN : w->error ? w->error : w->pause ? -EBUSY : 0;
    if (!error) {
        *p=(struct md_fs_worker_pause){.owner=w}; w->pause=p;
        atomic_store_explicit(&w->pause_requested,1,memory_order_relaxed);
    }
    pthread_mutex_unlock(&w->lock);
    if (!error) md_queue_signal_send(&w->wake);
    return error;
}
int md_fs_worker_pause_state(const struct md_fs_worker_pause *p) {
    if (!p || !p->owner) return -EINVAL;
    struct md_fs_worker *w=p->owner;
    pthread_mutex_lock(&w->lock);
    int state=w->pause!=p ? -EINVAL : w->stopping ? -ESHUTDOWN : w->error ? w->error : p->acknowledged;
    pthread_mutex_unlock(&w->lock);
    return state;
}
int md_fs_worker_pause_end(struct md_fs_worker_pause *p) {
    if (!p || !p->owner) return -EINVAL;
    struct md_fs_worker *w=p->owner;
    pthread_mutex_lock(&w->lock);
    int error=w->pause!=p ? -EINVAL : 0;
    if (!error) {
        w->pause=NULL; *p=(struct md_fs_worker_pause){0};
        atomic_store_explicit(&w->pause_requested,0,memory_order_relaxed);
    }
    pthread_mutex_unlock(&w->lock);
    if (!error) md_queue_signal_send(&w->wake);
    return error;
}
int md_fs_worker_error(struct md_fs_worker *w) {
    pthread_mutex_lock(&w->lock); int error = w->error; pthread_mutex_unlock(&w->lock);
    return error;
}
void md_fs_worker_notifications(struct md_fs_worker *w, int fd, void *context,
        int (*ready)(void *, struct md_filesystem *, short),int (*prepare)(void *)) {
    pthread_mutex_lock(&w->lock);
    w->notification_fd = fd; w->notification_context = context; w->notification = ready;
    w->prepare_wait=prepare;
    pthread_mutex_unlock(&w->lock); md_queue_signal_send(&w->wake);
}
void md_fs_worker_publish(struct md_fs_worker *w, struct md_fs_work *work) {
    work->next = NULL;
    work->published_at=md_cost_begin(w->statistics ? &w->delivery : NULL);
    pthread_mutex_lock(&w->lock);
    *w->completed_tail = work; w->completed_tail = &work->next;
    pthread_mutex_unlock(&w->lock); md_queue_signal_send(&w->done);
}
int md_fs_worker_submit(struct md_fs_worker *w, struct md_fs_work *work) {
    if (!w || !work || !work->run || !work->complete) return -EINVAL;
    work->queued_at=md_cost_begin(w->statistics ? &w->queued : NULL);
    pthread_mutex_lock(&w->lock);
    int error=w->stopping ? -ESHUTDOWN : w->error;
    if (!error) {
        work->next=NULL;
        *w->submitted_tail=work; w->submitted_tail=&work->next;
    }
    pthread_mutex_unlock(&w->lock);
    if (!error) md_queue_signal_send(&w->wake);
    return error;
}
int md_fs_worker_dispatch(struct md_fs_worker *w) {
    md_queue_signal_acknowledge(&w->done);
    pthread_mutex_lock(&w->lock);
    struct md_fs_work *completed = w->completed;
    w->completed = NULL; w->completed_tail = &w->completed;
    pthread_mutex_unlock(&w->lock);
    int error=0;
    while (completed) {
        struct md_fs_work *work=completed; completed=work->next;
        work->next=NULL;
        if (work->ready && !work->ready(work)) {
            pthread_mutex_lock(&w->lock);
            *w->completed_tail=work; w->completed_tail=&work->next;
            pthread_mutex_unlock(&w->lock);
            continue;
        }
        if (w->statistics) md_cost_end(&w->delivery,work->published_at);
        int64_t start=md_cost_begin(w->statistics ? &w->completion : NULL);
        int result=work->complete(work);
        if (w->statistics) md_cost_end(&w->completion,start);
        if (!error) error=result;
    }
    return error ? error : md_fs_worker_error(w);
}
int md_fs_worker_stop(struct md_fs_worker *w) {
    if (!w) return 0;
    pthread_mutex_lock(&w->lock); w->stopping=1; pthread_mutex_unlock(&w->lock);
    md_event_signal(w->stop);
    /* EVENT_WAIT: worker exit after stop readiness; expiry leaves its memory
     * owned by the process, whose caller must terminate instead of freeing it. */
    int64_t deadline = md_event_now() + 10000000000LL;
    for (;;) {
        pthread_mutex_lock(&w->lock); int exited = w->exited; pthread_mutex_unlock(&w->lock);
        if (exited) break;
        int r = (int)md_event_wait_fd(w->done.reader, POLLIN, deadline);
        if (r < 0) return r;
        md_queue_signal_acknowledge(&w->done);
    }
    int error = pthread_join(w->thread, NULL);
    if (!error && w->statistics) {
        const struct md_cost *costs[]={&w->queued,&w->execution,&w->delivery,&w->completion};
        const char *names[]={"queue","execute","delivery","complete"};
        for (unsigned i=0;i<4;++i) fprintf(stderr,"MD_WORKER phase=%s calls=%llu ns=%llu\n",names[i],
            (unsigned long long)costs[i]->calls,(unsigned long long)costs[i]->nanoseconds);
    }
    if (!error && w->error != -ESHUTDOWN) error = -w->error;
    if (w->pause) *w->pause=(struct md_fs_worker_pause){0};
    while (w->completed) {
        struct md_fs_work *work=w->completed;
        w->completed=work->next;
        if (work->discard) work->discard(work);
    }
    md_resource_wakeup_release(w->wakeup);
    md_queue_signal_close(&w->wake); md_queue_signal_close(&w->done);
    close(w->stop); close(w->ready);
    md_mount_namespace_release(w->mounts);
    md_device_events_release(w->device_events);
    md_ofd_orders_release(w->cursors);
    pthread_mutex_destroy(&w->lock); free(w);
    return -error;
}

struct control_work {
    struct md_fs_work work;
    struct md_control_pending pending;
    struct md_fs_context context;
    struct md_control_fs_operation operation;
    struct md_fs_continuation *continuation;
    struct md_resource_wakeup *wakeup;
    struct md_resource_wakeup *worker_wakeup;
    atomic_uint refs;
    atomic_int done, cancelled;
    long result;
};
static void control_release(struct control_work *w) {
    if (atomic_fetch_sub_explicit(&w->refs,1,memory_order_acq_rel)!=1) return;
    if (w->continuation) w->continuation->destroy(w->continuation);
    w->operation.destroy(w->operation.context);
    md_fs_context_end(&w->context);
    md_resource_wakeup_release(w->wakeup);
    md_resource_wakeup_release(w->worker_wakeup);
    free(w);
}
static void control_dispose(struct md_control_pending *pending) {
    struct control_work *w=(void *)((char *)pending - offsetof(struct control_work,pending));
    if (!atomic_load_explicit(&w->done,memory_order_acquire)) {
        atomic_store_explicit(&w->cancelled,1,memory_order_release);
        md_resource_wakeup_signal(w->worker_wakeup);
    }
    control_release(w);
}
static long control_resume(struct md_control_pending *pending,void *data,size_t size) {
    (void)data; (void)size;
    struct control_work *w=(void *)((char *)pending - offsetof(struct control_work,pending));
    return atomic_load_explicit(&w->done,memory_order_acquire) ? w->result : -EINPROGRESS;
}
static int control_ready(struct md_fs_work *work) {
    struct control_work *w=(void *)work;
    return atomic_load_explicit(&w->cancelled,memory_order_acquire) ? -ECANCELED
        : w->continuation->ready(w->continuation);
}
static void control_run(struct md_fs_work *work,struct md_filesystem *fs,int error) {
    struct control_work *w=(void *)work;
    if (!error && atomic_load_explicit(&w->cancelled,memory_order_acquire)) error=-ECANCELED;
    w->context.filesystem=fs;
    w->result=error ? error : w->operation.run(w->operation.context,&w->context,&w->continuation);
    w->context.filesystem=NULL;
    if (w->result==-EINPROGRESS && w->continuation) work->await=control_ready;
    else {
        if (w->result==-EINPROGRESS) w->result=-EPROTO;
        atomic_store_explicit(&w->done,1,memory_order_release);
        md_resource_wakeup_signal(w->wakeup);
    }
}
static int control_complete(struct md_fs_work *work) { control_release((void *)work); return 0; }
static void control_discard(struct md_fs_work *work) { control_release((void *)work); }
long md_fs_worker_control(void *worker,const struct md_control_actor *actor,
        struct md_control_fs_operation operation,struct md_control_pending **pending) {
    struct md_fs_worker *fs=worker;
    if (!fs || !actor || !operation.run || !operation.destroy) return -EINVAL;
    struct control_work *w=calloc(1,sizeof(*w));
    if (!w) return -ENOMEM;
    int error=md_task_registry_read(fs->tasks,actor->pid,0,&w->context.task);
    if (error) { free(w); return error; }
    w->context.actor=actor->pid; w->context.authenticated=w->context.has_identity=1;
    w->operation=operation;
    w->work=(struct md_fs_work){.run=control_run,.complete=control_complete,.discard=control_discard};
    w->pending=(struct md_control_pending){.resume=control_resume,.dispose=control_dispose};
    w->wakeup=md_resource_wakeup_retain(actor->wakeup);
    w->worker_wakeup=md_resource_wakeup_retain(fs->wakeup);
    atomic_init(&w->refs,2); atomic_init(&w->done,0); atomic_init(&w->cancelled,0);
    error=md_fs_worker_submit(fs,&w->work);
    if (error) {
        md_fs_context_end(&w->context); md_resource_wakeup_release(w->wakeup);
        md_resource_wakeup_release(w->worker_wakeup); free(w);
        return error;
    }
    *pending=&w->pending; return -EINPROGRESS;
}
