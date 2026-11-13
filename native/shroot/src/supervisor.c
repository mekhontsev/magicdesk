#define _GNU_SOURCE
#ifdef MD_KERNEL_TESTING
#include "kernel_profile.h"
#endif
#include "cgroup.h"
#include "command_access.h"
#include "descriptor_inode.h"
#include "elf_admission.h"
#include "event_signal.h"
#include "event_wait.h"
#include "entry_wakeup.h"
#include "fd_exchange.h"
#include "fd_native_pin.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "fs_anchor.h"
#include "fs_buffer_service.h"
#include "fs_engine.h"
#include "fs_mounts.h"
#include "fs_storage.h"
#include "fs_worker.h"
#include "guest_accounts.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "guest_user.h"
#include "host_identity.h"
#include "inode_internal.h"
#include "inode_store.h"
#include "input_session.h"
#include "interception.h"
#include "interception_memory.h"
#include "ipc_credentials.h"
#include "ipc_namespace.h"
#include "ipc_origins.h"
#include "launch_identity.h"
#include "launch_registry.h"
#include "linux_abi.h"
#include "mount_copy_service.h"
#include "mount_graph.h"
#include "mount_namespace_internal.h"
#include "namespace_broker.h"
#include "network_launch.h"
#include "network_namespace.h"
#include "network_profile.h"
#include "pending.h"
#include "proc_file.h"
#include "process_image_view.h"
#include "process_limits.h"
#include "process_owner.h"
#include "profile.h"
#include "queue_signal.h"
#include "raw.h"
#include "resource_order.h"
#include "seccomp_policy.h"
#include "signal_actions.h"
#include "signal_delivery.h"
#include "signal_pending.h"
#include "signal_protocol.h"
#include "signal_view.h"
#include "storage_database.h"
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
#include "supervisor_robust.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_machine.h"
#include "sysv_ipc.h"
#include "sysv_shm.h"
#include "task_checkpoint.h"
#include "supervisor_transfer.h"
#include "task_helper.h"
#include "task_quiesce.h"
#include "task_registry.h"
#include "task_resources.h"
#include "task_stop.h"
#include "task_wait.h"
#include "uts_namespace.h"
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/prctl.h>
#include <sys/timerfd.h>
#include <sys/wait.h>

static void drain(void);
/* One supervisor owns one launched tree. It never attaches to Android tasks. */
struct md_interception_abi abi;
int diagnostics;
int signal_diagnostics;
struct interception_statistics *statistics;
struct md_pending_queue retry_queues[2];
struct md_task_wait task_wait;
struct md_debugger *debugger;
pid_t leader;
unsigned live, total, images, signals, copy_faults, fstat_failures;
unsigned calls;
struct denial denials[64];
int result, signal_fd;
static int entry_wake_fd=-1;
int listener = -1;
int fd_ready = -1;
struct md_resource_wakeup *fd_wakeup;
struct md_fd_exchanges *fd_exchanges;
int cancelling;
struct md_launch_registration registration = {.directory=-1, .record=-1};
static int cancellation_timer = -1;
unsigned external_stats, external_errors;
unsigned protected_stats;
unsigned retained_calls, retained_errors;
unsigned root_changes, restricted_denials;
unsigned group_stops, group_wakes, raced_wakes;
const char *admitted_path;
struct md_fs_worker *filesystem;
struct md_fs_buffer_service *buffer_service;
struct md_mount_copy_service *mount_copy_service;
struct md_ipc_origins *origin_service;
struct md_task_helpers *helpers;
int ipc_events=-1;
int socket_events=-1;
struct md_namespace_broker *broker;
struct md_task_registry *tasks;
struct md_identity launch_identity;
int virtual_identity;
struct md_admission admission = {.source = -1};
unsigned admitted_images;
unsigned identity_changes;
static struct md_network_launch launch_network;

static void cleanup_resume_tracees(void);
static void retry_progress(struct md_pending_node *node,void *context);
static void retry_dispatch(unsigned queue);
static void fd_progress(void);
static void cancellation_arm(unsigned seconds);
static void describe_pending_tasks(void);
static void cancellation_expired(void);
static void progress_events(const struct pollfd *events);
static void event_sources(struct pollfd events[EVENT_SOURCES]);
static pid_t deferred_event(int *status,struct rusage *usage);
static pid_t kernel_event(int *status,struct rusage *usage);
static int checkpoint_event(pid_t pid,int *status);
static int tasks_pending(void);
static pid_t wait_event(int *status, struct rusage *usage);
static void supervisor_sigsys(int signal,siginfo_t *info,void *context);
static void report_statistics(void);

void retry_clear(struct thread *t, enum retry_kind kind) {
    struct retry_owner *owner=&t->retries[kind>=RETRY_FD && kind!=RETRY_TRANSFER];
    owner->mask&=~kind;
    if (!owner->mask) md_pending_cancel(&owner->node);
}

void retry_wait(struct thread *t, enum retry_kind kind) {
    unsigned queue=kind>=RETRY_FD && kind!=RETRY_TRANSFER;
    struct retry_owner *owner=&t->retries[queue];
    owner->thread=t; owner->mask|=kind;
    md_pending_add(&retry_queues[queue],&owner->node);
}

static void cleanup_resume_tracees(void) {
    /* A barrier may already own TRACEEXIT. SIGKILL cannot produce a second
     * wait event until we release that stop. Include private helper actors,
     * and let ptrace verify custody before touching any retained numeric PID. */
    struct md_task_wait_member *first=task_wait.cursor,*member=first;
    if (member) do {
        ptrace(PTRACE_CONT,member->pid,NULL,(void *)(uintptr_t)SIGKILL);
        member=member->next;
    } while (member!=first);
}

void cleanup(void) {
    report_statistics();
    if (cancellation_timer>=0) { close(cancellation_timer); cancellation_timer=-1; }
    retained_cancel();
    for (struct thread *t = threads; t; t = t->next) if (t->pid) {
        kill(t->pid, SIGKILL); release_operation(t); adapter_discard(t);
    }
    cleanup_resume_tracees();
    /* EVENT_WAIT: reap owned tracees after cancellation; outer runner bounds
     * cleanup if the kernel cannot complete an exit. */
    for (;;) {
        int status;
        pid_t pid=md_task_wait_take(&task_wait,&status,NULL);
        if (!pid) pid=waitpid(-1,&status,__WALL);
        if (pid<0) { if (errno==EINTR) continue; break; }
        if (md_fs_buffer_child(buffer_service,pid,status)) continue;
        /* SIGKILL may still publish TRACEEXIT. Reaping requires continuing
         * this owned terminal stop, not merely waiting for another event. */
        if (WIFSTOPPED(status)) {
            kill(pid,SIGKILL);
            ptrace(PTRACE_CONT,pid,NULL,NULL);
        }
    }
    md_task_helpers_destroy(helpers); helpers=NULL;
    retained_destroy();
    md_task_checkpoints_clear(&checkpoints);
    memory_discard();
    task_action_cleanup();
    policy_installer=NULL;
    md_task_quiesce_destroy(policy_barrier); policy_barrier=NULL;
    while (threads) {
        struct thread *next = threads->next;
        md_task_wait_untrack(&task_wait,&threads->wait_member);
        exec_images_release(threads);
        md_process_executable_release(threads->image.executable);
        md_fs_anchor_release(threads->image.executable_origin);
        fd_discard(threads);
        adapter_discard(threads);
        md_broker_forget(broker,threads->pid);
        cooperative_buffer_release(threads);
        signal_control_discard(threads);
        filter_install_discard(threads);
        guest_notification_discard(threads);
        md_signal_pending_release(threads->signals.pending);
        md_signal_actions_release(threads->signals.actions);
        md_seccomp_policy_release(threads->seccomp.admission_policy);
        md_seccomp_policy_replace(&threads->seccomp.policy,NULL);
        md_memory_release(threads->memory);
        md_memory_release(threads->memory_op.remote);
        task_continuation_discard(threads);
        free(threads->metadata_restart);
        if (threads->broker_completion) {
            md_fs_buffer_open_release(&threads->broker_completion->opened);
            free(threads->broker_completion);
        }
        md_fd_selector_release(threads->descriptors.selector);
        md_fd_selector_release(threads->descriptors.deferred_selector);
        md_shm_space_release(threads->shm_space);
        ipc_unwatch(threads);
        md_ipc_cancel(md_ipc_namespace_active(threads->resources.ipc),&threads->io.pending);
        md_ipc_transport_close(&threads->io.transport);
        md_resources_unshare_finish(&threads->resources,&threads->identity,&threads->resource_change, -ECANCELED);
        md_resources_release(&threads->resources);
        md_identity_release(&threads->identity);
        identity_discard(threads);
        md_signal_delivery_clear(&threads->signals.delivery);
        free(threads); threads = next;
    }
    task_action_cleanup();
    md_admission_close(&admission);
    md_network_launch_close(&launch_network);
    md_debugger_destroy(debugger); debugger = NULL;
    if (ipc_events>=0) close(ipc_events);
    ipc_events=-1;
    if (socket_events>=0) close(socket_events);
    socket_events=-1;
    if (md_fs_worker_stop(filesystem)) _Exit(125);
    md_mount_copy_service_destroy(mount_copy_service); mount_copy_service=NULL;
    md_fs_buffer_service_destroy(buffer_service); buffer_service=NULL;
    md_ipc_origins_destroy(origin_service); origin_service=NULL;
    filesystem = NULL;
    md_launch_unregister(&registration);
    md_broker_destroy(broker); broker = NULL;
    md_task_quiesce_destroy(memory_barrier); memory_barrier=NULL;
    md_task_quiesce_destroy(task_action_barrier); task_action_barrier=NULL;
    md_task_quiesce_destroy(mapping_barrier); mapping_barrier=NULL;
    md_memory_release(entry_fence_memory); entry_fence_memory=NULL; entry_fence_stage=0;
    md_task_registry_destroy(tasks); tasks = NULL;
    md_cgroup_member_release(freezer); freezer=NULL;
    md_fd_exchanges_destroy(fd_exchanges); fd_exchanges=NULL;
    md_identity_release(&launch_identity);
    if (listener >= 0) close(listener);
    if (fd_ready >= 0) close(fd_ready);
    md_resource_wakeup_release(fd_wakeup); fd_wakeup=NULL;
    free(statistics);
}

_Noreturn void fail(const char *what, const char *file, int line) {
    const char *name=strrchr(file,'/');
    fprintf(stderr, "guest-runtime: supervisor failure file=%s line=%d %s errno=%d\n",
        name ? name+1 : file, line, what, errno);
    exit(125);
}

static void retry_progress(struct md_pending_node *node,void *context) {
    (void)context;
    struct retry_owner *owner=(struct retry_owner *)node;
    struct thread *t=owner->thread;
    if (retained_task_held(t)) {
        md_pending_add(&retry_queues[owner==&t->retries[1]],node);
        return;
    }
    if (owner->mask&RETRY_CLOSE) close_progress(t);
    if (!(owner->mask&~RETRY_CLOSE)) return;
    if (!memory_available(t->pid)) {
        if (owner->mask) md_pending_add(&retry_queues[owner==&t->retries[1]],node);
        return;
    }
    unsigned mask=owner->mask;
    CHECK(t->pid);
    /* A continuation can cancel another wait. New waits join the next round;
     * no runnable task or in-flight worker operation enters this registry. */
#define RETRY(kind, call) if (mask & owner->mask & (kind)) call(t)
    RETRY(RETRY_TASK_ACTION,selector_progress);
    RETRY(RETRY_READINESS,readiness_start);
    RETRY(RETRY_MAPPING,mapping_start);
    RETRY(RETRY_MEMORY,memory_start);
    RETRY(RETRY_BUFFER,buffer_take);
    RETRY(RETRY_DESCRIPTOR,buffer_descriptor_start);
    RETRY(RETRY_RECEIVE,receive_start);
    RETRY(RETRY_OUTGOING,outgoing_start);
    RETRY(RETRY_PROC_DELIVERY,proc_deliver);
    RETRY(RETRY_RETURN,buffer_return_progress);
    RETRY(RETRY_FD,fd_start);
    RETRY(RETRY_EXPORT,export_progress);
    RETRY(RETRY_CONTROL,control_progress);
    RETRY(RETRY_PROC_IO,proc_io_progress);
    RETRY(RETRY_PROC_LOOKUP,proc_lookup_start);
    RETRY(RETRY_FILTER,filter_install_progress);
    RETRY(RETRY_SIGNAL,signal_control_progress);
    RETRY(RETRY_TRANSFER,transfer_progress);
    RETRY(RETRY_DEBUG_WAIT,debugger_wait_retry);
#undef RETRY
    CHECK(!!owner->mask==!!owner->node.list);
}

static void retry_dispatch(unsigned queue) {
    if (md_pending_dispatch(&retry_queues[queue],retry_progress,NULL)==-EBUSY) {
        /* Preserve a nested completion wakeup without recursive dispatch or
         * an unbounded drain that could starve ptrace/worker events. */
        CHECK(!md_event_signal(fd_ready));
    }
}

void adapter_progress(void) {
    CHECK(!md_fs_buffer_progress(buffer_service));
    retry_dispatch(0);
}

static void fd_progress(void) {
    md_event_acknowledge(fd_ready);
    freezer_progress();
    retry_dispatch(1);
    adapter_progress();
}

static void cancellation_arm(unsigned seconds) {
    if (cancellation_timer<0) {
        cancellation_timer=timerfd_create(CLOCK_MONOTONIC,TFD_CLOEXEC|TFD_NONBLOCK);
        CHECK(cancellation_timer>=0);
    }
    const struct itimerspec timer={.it_value.tv_sec=seconds};
    CHECK(!timerfd_settime(cancellation_timer,0,&timer,NULL));
}

void cancel_children(int signal) {
    for (struct thread *t=threads;t;t=t->next)
        if (t->pid && t->born && t->pid==t->tgid)
            CHECK(!md_process_signal_child(t->pid,signal));
    CHECK(!md_process_signal_children(signal));
}

void cancel_tree(int status) {
    result=status; cancelling=1;
    cancellation_arm(2);
    cancel_children(SIGTERM);
}

static void describe_pending_tasks(void) {
    unsigned shown=0,total=0;
    for (unsigned group=0;group<2;++group) for (struct thread *t=threads;t;t=t->next) if (t->pid) {
        if (!group) ++total;
        if (shown==16 || (t->pid==t->tgid)!=!group) continue;
        ++shown;
        fprintf(stderr,"Shroot: pending pid=%d tgid=%d phase=%d stopped=%d resume=%d listening=%d interrupt=%d syscall=%d buffer=%d broker=%d ipc=%d outgoing=%d action=%d readiness=%d\n",
            t->pid,t->tgid,t->phase,t->kernel_stopped,t->kernel_resume,t->listening,t->interrupt_pending,t->execution.request.nr,
            t->fs.call!=NULL,t->broker_completion!=NULL,t->io.pending!=NULL,t->io.outgoing!=NULL,
            t->action!=NULL,t->readiness!=NULL);
    }
    if (total>shown) fprintf(stderr,"Shroot: pending tasks shown=%u total=%u\n",shown,total);
    if (task_action_execution) {
        struct task_action *owner=task_action_execution;
        fprintf(stderr,"Shroot: task action requester=%d actor=%d stage=%d brokerBorrowed=%d\n",
            owner->requester ? owner->requester->pid : 0,owner->actor ? owner->actor->pid : 0,
            task_action_call(owner)->stage,owner->broker_borrowed);
        if (task_action_barrier) md_task_quiesce_describe(task_action_barrier,STDERR_FILENO);
    }
    md_broker_describe(broker,STDERR_FILENO);
}

static void cancellation_expired(void) {
    uint64_t count;
    ssize_t n;
    do { n=read(cancellation_timer,&count,sizeof(count)); } while (n<0 && errno==EINTR);
    /* A signal may have rearmed an already-ready deadline in this dispatch. */
    if (n<0 && errno==EAGAIN) return;
    CHECK(n==sizeof(count));
    if (!cancelling) {
        fprintf(stderr,"guest-runtime: launch deadline expired\n");
        describe_pending_tasks();
        cancel_tree(125);
    } else if (cancelling==1) {
        /* EVENT_WAIT: graceful cancellation expired; allow ten seconds to
         * reap the killed tree, then report incomplete cleanup explicitly. */
        cancelling=2; cancellation_arm(10);
        cancel_children(SIGKILL);
    } else {
        fprintf(stderr,"guest-runtime: cancellation cleanup deadline expired\n");
        describe_pending_tasks();
        errno=ETIMEDOUT; CHECK(0);
    }
}

static void drain(void) {
    struct signalfd_siginfo signals[8];
    ssize_t bytes;
    do { bytes=read(signal_fd,signals,sizeof(signals)); } while (bytes<0 && errno==EINTR);
    if (bytes<0 && errno==EAGAIN) return;
    CHECK(bytes>0 && bytes%(ssize_t)sizeof(*signals)==0);
    /* Bounded level-triggered dispatch: remaining signals keep the FD ready.
     * Do not append an empty read to every successful notification batch. */
    for (size_t i=0;i<(size_t)bytes/sizeof(*signals);i++) {
        struct signalfd_siginfo info=signals[i];
        if (info.ssi_signo == SIGCHLD) md_task_wait_hint(&task_wait, (pid_t)info.ssi_pid);
        else if (!cancelling) {
            SUPERVISOR_TRACE("PROBE cancelled signal=%u\n", info.ssi_signo);
            cancel_tree(128 + info.ssi_signo);
        }
    }
}

static void progress_events(const struct pollfd *events) {
    if (events[0].revents) drain();
    if (events[7].revents) {
        int callers[32];
        int count=md_entry_wakeup_read(entry_wake_fd,callers,32); CHECK(count>=0);
        for (int i=0;i<count;++i) md_broker_entry_wake(broker,callers[i]);
    }
    if (events[6].revents) cancellation_expired();
    if (events[2].revents) { CHECK(!md_fs_worker_dispatch(filesystem)); adapter_progress(); }
    if (events[3].revents) fd_progress();
    if (events[4].revents) ipc_events_ready();
    if (events[5].revents) socket_events_ready();
    if (events[1].revents & POLLIN) external_stat();
    if (events[1].revents & POLLHUP) { close(listener); listener = -1; }
}

static void event_sources(struct pollfd events[EVENT_SOURCES]) {
    const struct pollfd current[EVENT_SOURCES] = {{signal_fd,POLLIN,0}, {broker ? -1 : listener,POLLIN,0},
        {md_fs_worker_fd(filesystem),POLLIN,0}, {fd_ready,POLLIN,0},
        {ipc_events,POLLIN,0}, {socket_events,POLLIN,0}, {cancellation_timer,POLLIN,0},
        {entry_wake_fd,POLLIN,0}};
    memcpy(events,current,sizeof(current));
}

static pid_t deferred_event(int *status,struct rusage *usage) {
    pid_t robust=retained_take(status,usage);
    if (robust) return robust;
    if (policy_barrier && !policy_installer) {
        pid_t event=md_task_quiesce_take(policy_barrier,status,usage);
        if (event) return event;
        md_task_quiesce_destroy(policy_barrier); policy_barrier=NULL;
        CHECK(!md_event_signal(fd_ready));
    }
    pid_t filtered=entry_fence_take(status,usage);
    if (filtered) return filtered;
    if (broker_events) {
        struct thread *t=broker_events; broker_events=t->broker_next; t->broker_next=NULL;
        *status=t->broker_event.status; if (usage) *usage=t->broker_event.usage;
        t->broker_event.held=0;
        return t->pid;
    }
    pid_t broker_event=md_broker_take_event(broker,status,usage);
    if (broker_event) return broker_event;
    pid_t pid=memory_execution ? 0 : md_task_quiesce_take(memory_barrier,status,usage);
    if (pid) return pid;
    pid=task_action_execution ? 0 : md_task_quiesce_take(task_action_barrier,status,usage);
    return pid ? pid : mapping_execution ? 0 : md_task_quiesce_take(mapping_barrier,status,usage);
}

static pid_t kernel_event(int *status,struct rusage *usage) {
    int64_t start=md_cost_begin(statistics ? &statistics->event_collect : NULL);
    int accounting=md_debugger_active(debugger);
    /* Refresh the lossy TID hint before a kernel query, not for an owned batch.
     * The periodic source budget still services all other event owners. */
    if (!task_wait.pending_count && !task_wait.count && !task_wait.drained) drain();
    pid_t pid=md_task_wait_ready(&task_wait,status,accounting ? usage : NULL);
    /* Deferred events may outlive a debugger attachment. Absent accounting
     * must be explicit, not a previous event's or uninitialized stack data. */
    if (pid>0 && usage && !accounting) *usage=(struct rusage){0};
    md_cost_end(statistics ? &statistics->event_collect : NULL,start);
    return pid;
}

enum { CHECKPOINT_EVENT, CHECKPOINT_PROGRESS, CHECKPOINT_PARKED };
static int checkpoint_event(pid_t pid,int *status) {
    /* A shielded admission may stop at syscall exit rather than EVENT_STOP.
     * The checkpoint driver must not resume that stop behind an active barrier.
     * Keep raw events queued and normalize them through this same path on replay.
     * EVENT_STOP still normalizes a checkpoint-owned synthetic interrupt first. */
    if ((unsigned)*status>>16!=PTRACE_EVENT_STOP
            && (((retained_creation || retained_retirement) && md_task_quiesce_waiting(retained_barrier,pid))
                || (policy_installer && md_task_quiesce_waiting(policy_barrier,pid))
                || (memory_execution && md_task_quiesce_waiting(memory_barrier,pid))
                || (mapping_execution && md_task_quiesce_waiting(mapping_barrier,pid))
                || (task_action_execution && md_task_quiesce_waiting(task_action_barrier,pid))))
        return CHECKPOINT_PARKED;
    int progress=md_task_checkpoint_event(&checkpoints,pid,status);
    if (progress>0) {
        /* A deferred barrier event was already recorded as stopped. Decoder
         * progress resumes that continuation without passing through resume().
         * Borrowed task calls suspend decoding and retain their own exclusion. */
        md_broker_resumed(broker,pid);
    }
    return progress;
}

static int tasks_pending(void) {
    return live || retained_memories || md_fs_buffer_children(buffer_service) || md_task_helpers_count(helpers);
}

static pid_t wait_event(int *status, struct rusage *usage) {
    enum { PROGRESS_BATCH=8 };
    static unsigned progress_budget;
    for (;;) {
        task_continuations_progress();
        retained_progress();
        signal_entries_progress();
        policy_sync_progress();
        entry_fence_progress();
        memory_progress();
        mapping_progress();
        task_action_schedule();
        if (memory_completions_pending && !memory_execution) {
            memory_completions_pending=0;
            CHECK(!md_fs_worker_dispatch(filesystem)); fd_progress();
        }
        struct pollfd events[EVENT_SOURCES];
        /* Bound task/memory progress so runnable work cannot starve other sources.
         * No readiness is inferred: an empty task queue immediately enters the
         * shared event wait, which also replenishes this event-count budget. */
        if (!progress_budget) {
            struct thread *waiter=entry_wait_deadline_owner();
            if (waiter && waiter->entry.wait.deadline<=md_event_now()) entry_wait_complete(waiter,-ETIMEDOUT);
            event_sources(events);
            if (statistics) ++statistics->event_polls;
            long ready = md_event_poll(events, EVENT_SOURCES);
            CHECK(ready >= 0);
            if (ready) progress_events(events);
            progress_budget=PROGRESS_BATCH;
        }
        --progress_budget;
        int entry_progress=md_broker_entry_progress(broker);
        /* Progress callbacks can finish the last private task without leaving
         * another kernel event. The outer loop uses this same lifetime owner. */
        if (!tasks_pending()) return 0;
        /* Progress can return a borrowed stop without generating SIGCHLD.
         * Drain that local event before trusting the kernel's empty queue. */
        pid_t pid=deferred_event(status,usage);
        if (!pid) pid=kernel_event(status,usage);
        if (pid > 0) {
            struct thread *returning=find_thread(pid);
            child_signals_observe(returning,*status);
            if (returning) md_signal_delivery_checkpoint(&returning->signals.delivery,*status);
            if (returning && returning->signals.control && returning->signals.control->returning)
                signal_control_discard(returning);
            int checkpoint=checkpoint_event(pid,status);
            if (checkpoint==-ESRCH) continue;
            if (checkpoint<0) fprintf(stderr,"Shroot: checkpoint pid=%d status=%x error=%d\n",pid,*status,checkpoint);
            CHECK(checkpoint>=0);
            if (checkpoint==CHECKPOINT_PROGRESS) continue;
            if (checkpoint==CHECKPOINT_PARKED) {
                /* A raw syscall entry/skip exit is not a completed native
                 * effect. Its barrier owns the unchanged stop until replay;
                 * only the normalized continuation may retire broker work. */
                md_broker_park(broker,pid);
                return pid;
            }
            int held=md_broker_hold_event(broker,pid,*status,usage); CHECK(held>=0);
            if (held) continue;
            return pid;
        }
        if (pid<0 && errno==ECHILD && md_broker_pending_events(broker)) pid=0;
        if (pid<0 && errno==ECHILD) {
            fprintf(stderr,"Shroot: empty task queue live=%u buffers=%u helpers=%u\n",
                live,md_fs_buffer_children(buffer_service),md_task_helpers_count(helpers));
            unsigned shown=0;
            for (struct thread *t=threads;t && shown<16;t=t->next) if (t->pid) {
                fprintf(stderr,"Shroot: pending pid=%d group=%d phase=%d born=%d terminal=%d lost=%d broker=%d\n",
                    t->pid,t->tgid,t->phase,t->born,t->terminal_stop,t->resume_lost,t->broker_event.held);
                ++shown;
            }
            md_broker_describe(broker,STDERR_FILENO);
            errno=ECHILD;
        }
        CHECK(pid == 0 || errno == EINTR);
        if (memory_runnable() || retained_runnable() || entry_progress) continue;
        /* EVENT_WAIT: task/resource progress, signal or cancellation timer.
         * The timer is serviced even while ptrace continuously has work. */
        int64_t bound = INT64_MAX;
        struct thread *entry_owner=entry_wait_deadline_owner();
        int64_t entry_bound=entry_owner ? entry_owner->entry.wait.deadline : INT64_MAX;
        if (entry_bound<bound) bound=entry_bound;
        struct thread *control_owner=control_deadline_owner();
        int64_t control_bound=control_owner ? control_owner->control.pending->deadline : INT64_MAX;
        if (control_bound<bound) bound=control_bound;
        if (memory_deadline()<bound) bound=memory_deadline();
        if (mapping_execution && mapping_execution->deadline<bound) bound=mapping_execution->deadline;
        if (entry_fence_memory && entry_fence_deadline<bound) bound=entry_fence_deadline;
        if (entry_fence_retry_at && entry_fence_retry_at<bound) bound=entry_fence_retry_at;
        /* Dispatch may retire a source, notably bootstrap's listener on HUP.
         * A readiness snapshot cannot outlive its callbacks' descriptor owners. */
        event_sources(events);
        if (statistics) ++statistics->event_waits;
        int64_t sleep_start=md_cost_begin(statistics ? &statistics->event_sleep : NULL);
        long outcome = md_event_wait(events, EVENT_SOURCES, bound);
        md_cost_end(statistics ? &statistics->event_sleep : NULL,sleep_start);
        if (outcome==-ETIMEDOUT && bound==entry_bound) {
            CHECK(entry_owner); entry_wait_complete(entry_owner,-ETIMEDOUT); continue;
        }
        /* Report expiry through the transaction that owns the memory deadline. */
        if (outcome==-ETIMEDOUT && bound==memory_deadline()) memory_progress();
        if (outcome==-ETIMEDOUT && mapping_execution && bound==mapping_execution->deadline) mapping_progress();
        if (outcome==-ETIMEDOUT && entry_fence_memory && bound==entry_fence_deadline) {
            entry_fence_progress();
            continue;
        }
        if (outcome==-ETIMEDOUT && entry_fence_retry_at && bound==entry_fence_retry_at) continue;
        if (outcome==-ETIMEDOUT && bound==control_bound) {
            CHECK(control_owner);
            control_progress(control_owner);
            continue;
        }
        if (outcome < 0) {
            if (diagnostics) {
                for (struct thread *t=threads;t;t=t->next) if (t->pid)
                    SUPERVISOR_TRACE("PROBE pending pid=%d phase=%d fdJob=%u fdRunning=%d files=%d fs=%d bufferWaiting=%d\n",
                        t->pid,t->phase,t->descriptors.job,t->descriptors.running,md_fd_order_state(&t->descriptors.order),
                        md_fs_order_state(&t->descriptors.fs_order),t->fs.waiting);
                md_fs_buffer_describe(buffer_service,STDERR_FILENO);
            }
            errno = (int)-outcome;
        }
        CHECK(outcome >= 0);
        progress_events(events);
        progress_budget=PROGRESS_BATCH;
    }
}

static void supervisor_sigsys(int signal,siginfo_t *info,void *context) {
    (void)context;
    if (info->si_code==SYS_SECCOMP) {
        char message[128]="Shroot: host policy denied supervisor syscall=",number[32];
        md_decimal(number,(unsigned)info->si_syscall);
        md_append(message,sizeof(message),number);
        md_append(message,sizeof(message),"\n");
        RAW3(write,2,message,md_length(message));
    }
    /* SA_RESETHAND preserves fatal delivery after the async-safe report. */
    if (RAW3(tgkill,RAW0(getpid),RAW0(gettid),signal)<0) RAW1(exit_group,125);
}

int main(int argc, char **argv) {
#ifdef MD_KERNEL_TESTING
    int kernel_profile=md_test_kernel_profile_prepare(argc,argv);
#endif
    CHECK(argc >= 2 && md_launch_identity(getuid(), geteuid(), getgid(), getegid()));
    struct sigaction host_failure={.sa_sigaction=supervisor_sigsys,.sa_flags=SA_SIGINFO|SA_RESETHAND};
    CHECK(!sigaction(SIGSYS,&host_failure,NULL));
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    CHECK(md_page_size >= 4096 && md_page_size <= 65536 && !(md_page_size & (md_page_size - 1)));
    int argument = 1;
    int command_access = 0, command_owner = -1;
    char command_directory[PATH_MAX];
    long seconds = 0;
    while (argument < argc) {
        if (!strcmp(argv[argument], "--diagnostics")) diagnostics = 1;
        else if (!strcmp(argv[argument], "--diagnostics=signals")) signal_diagnostics = 1;
        else if (!strcmp(argv[argument], "--magicdesk") && !command_access) command_access = 1;
        else if (!strcmp(argv[argument], "--statistics")) {
            CHECK(!statistics);
            statistics = calloc(1, sizeof(*statistics));
            CHECK(statistics);
        } else break;
        argument++;
    }
    if (argument < argc && !strcmp(argv[argument], "--deadline-seconds")) {
        CHECK(argument + 2 < argc);
        char *end;
        seconds = strtol(argv[argument + 1], &end, 10);
        CHECK(!*end && seconds >= 1 && seconds <= 3600);
        argument += 2;
    }
    if (argument<argc && !strcmp(argv[argument],"--runtime-tmp")) {
        CHECK(argument+2<argc);
        CHECK(!md_database_temporary_directory(argv[argument+1]));
        argument+=2;
    }
    setvbuf(stderr, NULL, _IONBF, 0);
    struct md_debugger_host debug_host={.trace=native_trace,.parent=debugger_parent,.group=debugger_group,.uid=debugger_uid,.allowed=debugger_allowed,.traceme=debugger_traceme,
        .wait_children=private_wait_children,
        .visible_pid=debugger_visible_pid,.native_pid=debugger_native_pid,.visible_uid=debugger_visible_uid,.reaped=debugger_reaped,
        .signal_origin=debugger_signal_origin,.signal_translate=debugger_signal_translate,
        .attachable=debugger_attachable,.observe=debugger_observe,.interrupt=debugger_interrupt,.options=debugger_options,
        .listen=debugger_listen,.usage=debugger_usage,.wait=debugger_wait,.wait_wake=debugger_wait_wake,
        .complete=debugger_complete,.resume=resume_signal,.signal_mask=debugger_signal_mask,
        .wait_admit=debugger_wait_admit};
    debugger=md_debugger_create(&debug_host); CHECK(debugger);
    if (argument < argc && !strcmp(argv[argument], "--admit-elf")) {
        CHECK(argument + 2 < argc);
        admitted_path = argv[argument + 1]; argument += 2;
        CHECK(admitted_path[0] == '/');
    }
    const char *selected_user = NULL, *selected_groups = NULL, *selected_hostname = NULL;
    launch_identity = md_identity_new(getuid(), getgid());
    tasks = md_task_registry_create(); CHECK(tasks);
    struct md_identity owner = md_identity_new(getuid(), getgid());
    CHECK(!md_task_registry_publish(tasks, getpid(), getpid(), &owner, NULL));
    if (argument < argc && !strcmp(argv[argument], "--user")) {
        CHECK(argument + 2 < argc);
        selected_user = argv[argument + 1]; virtual_identity = 1; argument += 2;
    } else {
        int count = getgroups(0, NULL); CHECK(count >= 0);
        gid_t *groups = count ? malloc((size_t)count * sizeof(*groups)) : NULL;
        CHECK(!count || groups);
        CHECK(getgroups(count, groups) == count && !md_identity_groups(&launch_identity, groups, (unsigned)count));
        free(groups);
    }
    if (argument < argc && !strcmp(argv[argument], "--groups")) {
        CHECK(virtual_identity && argument + 2 < argc);
        selected_groups = argv[argument + 1]; argument += 2;
    }
    struct md_fs_attachment attachments[MD_FS_MOUNTS_MAX];
    if (argument < argc && !strcmp(argv[argument], "--hostname")) {
        CHECK(argument+2 < argc && md_hostname_valid(argv[argument+1]));
        selected_hostname=argv[argument+1]; argument+=2;
    }
    struct md_network_profile network_profile={0};
    while (argument<argc && md_network_profile_option(argv[argument])) {
        CHECK(argument+2<argc);
        int error=md_network_profile_add(&network_profile,argv[argument],argv[argument+1]);
        if (error) { errno=-error; perror("Shroot network option"); return 125; }
        argument+=2;
    }
    int network_error=md_network_profile_validate(&network_profile);
    if (network_error) { errno=-network_error; perror("Shroot network profile"); return 125; }
    unsigned attachment_count = 0;
    const char *gamepad_endpoint=NULL;
    if (argument<argc && !strcmp(argv[argument],"--gamepad-endpoint")) {
        CHECK(argument+2<argc && *argv[argument+1]); gamepad_endpoint=argv[argument+1]; argument+=2;
    }
    while (argument < argc && (!strcmp(argv[argument], "--bind") || !strcmp(argv[argument], "--bind-ro")
            || !strcmp(argv[argument], "--bind-create") || !strcmp(argv[argument], "--bind-store-ro"))) {
        CHECK(argument+2 < argc && attachment_count < MD_FS_MOUNTS_MAX);
        attachments[attachment_count++] = (struct md_fs_attachment){
            .source=argv[argument+1], .target=argv[argument+2],
            .flags=!strcmp(argv[argument], "--bind-ro") ? MD_FS_ATTACHMENT_READONLY
                : !strcmp(argv[argument], "--bind-store-ro") ? MD_FS_ATTACHMENT_STORE|MD_FS_ATTACHMENT_READONLY
                : !strcmp(argv[argument], "--bind-create") ? MD_FS_ATTACHMENT_CREATE : 0};
        argument += 3;
    }
    const char *store = NULL, *endpoint = NULL;
    if (argument < argc && !strcmp(argv[argument], "--store")) {
        CHECK(argument + 4 < argc && !strcmp(argv[argument + 2], "--endpoint"));
        store = argv[argument + 1]; endpoint = argv[argument + 3]; argument += 4;
    }
    if (selected_user) {
        uint32_t uid, gid;
        if (!md_guest_user_parse(selected_user, &uid, &gid)) launch_identity = md_identity_new(uid, gid);
        else {
            struct md_inode_store *accounts = NULL;
            CHECK(store && !md_inode_store_open(store, 0, &accounts));
            struct md_fs_storage_cache account_storage={0};
            struct md_filesystem view = {.store=accounts,.storage=&account_storage};
            CHECK(!md_fs_mounts_open(&view, attachments, attachment_count));
            int resolved = md_guest_user_resolve(&view, selected_user, &launch_identity);
            md_fs_mounts_close(&view); md_fs_storage_close(&account_storage); md_inode_store_close(accounts);
            if (resolved) { errno = -resolved; CHECK(!resolved); }
        }
    }
    if (selected_groups) {
        const char *p = selected_groups;
        uint32_t *groups = malloc(MD_IDENTITY_GROUPS_MAX * sizeof(*groups)); CHECK(groups);
        unsigned count = 0;
        while (*p) {
            CHECK(count < MD_IDENTITY_GROUPS_MAX && *p >= '0' && *p <= '9');
            uint64_t value = 0;
            do { value = value * 10 + (unsigned)(*p++ - '0'); CHECK(value < UINT32_MAX); }
            while (*p >= '0' && *p <= '9');
            groups[count++] = (uint32_t)value;
            if (*p) { CHECK(*p++ == ',' && *p); }
        }
        CHECK(!md_identity_groups(&launch_identity, groups, count));
        free(groups);
    }
    int inherited_no_new_privs = prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
    CHECK(inherited_no_new_privs >= 0);
    launch_identity.no_new_privs = (unsigned)inherited_no_new_privs;
    CHECK(argument < argc);
    if (command_access) {
        CHECK(attachment_count < MD_FS_MOUNTS_MAX);
        command_owner = md_guest_command_access(store, &attachments[attachment_count], command_directory);
        if (command_owner < 0) { errno = -command_owner; perror("MagicDesk command access"); return 125; }
        ++attachment_count;
    } else {
        unsetenv("MAGICDESK_COMMAND_ENDPOINT"); unsetenv("MAGICDESK_COMMAND_BUILD");
    }
    struct md_process_signals inherited;
    CHECK(md_process_signals_open(&inherited) >= 0);
    signal_fd = inherited.fd;
    CHECK(!prctl(PR_SET_CHILD_SUBREAPER, 1) && !atexit(cleanup));
    int channel[2]; CHECK(!socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel));
    int tty = open("/dev/tty", O_RDWR | O_NOCTTY | O_CLOEXEC);
    int headless = tty < 0;
    if (tty >= 0) close(tty);
    pid_t parent = getpid();
    struct md_ipc_inherited inherited_ipc={0};
    if (store) CHECK(!md_ipc_inherited_capture(&inherited_ipc));
    leader = fork(); CHECK(leader >= 0);
    if (!leader) {
        if (command_owner >= 0) close(command_owner);
        close(channel[0]); close(signal_fd);
        CHECK(!md_process_signals_restore(&inherited));
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == parent);
        /* Headless group signals belong to the guest tree, not its guardians.
         * A controlling PTY retains its caller's foreground job-control group. */
        if (headless) CHECK(!setpgid(0, 0));
        char byte = 'r'; CHECK(write(channel[1], &byte, 1) == 1);
        /* EVENT_WAIT: supervisor's attach acknowledgement; the outer timeout cancels. */
        CHECK(read(channel[1], &byte, 1) == 1 && byte == 'g'); close(channel[1]);
        execv(argv[argument], argv + argument); _exit(127);
    }
#ifdef MD_KERNEL_TESTING
    /* Restrict only native owners. The guest must retain Shroot interception
     * and must not inherit a test filter or its no_new_privs state. */
    CHECK(!md_test_kernel_profile_install(kernel_profile));
#endif
    thread(leader)->born = 1;
    thread(leader)->signals.pending=md_signal_pending_new(); CHECK(thread(leader)->signals.pending);
    /* A caller can install filters before starting Shroot. Treat inherited
     * no_new_privs conservatively: its ERRNO rules can outrank service TRACE
     * markers, so use the same pre-filter protocol as guest-installed filters. */
    thread(leader)->seccomp.application_filter=!!inherited_no_new_privs;
    close(channel[1]);
    int keep[] = {signal_fd, channel[0], command_owner};
    CHECK(!md_process_close_fds(keep, command_owner >= 0 ? 3 : 2));
    if (store) entry_wake_fd=md_entry_wakeup_open();
    fd_exchanges=md_fd_exchanges_create(); CHECK(fd_exchanges);
    const struct md_task_helper_callbacks helper_callbacks={.trace=native_trace,.newborn=helper_newborn,.wait=&task_wait};
    helpers=md_task_helpers_new(&helper_callbacks); CHECK(helpers);
    fd_ready=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); CHECK(fd_ready>=0);
    CHECK(!md_resource_wakeup_create(fd_ready,&fd_wakeup));
    /* EVENT_WAIT: child readiness precedes attaching; timeout cancels this tree. */
    CHECK(md_event_wait_fd(channel[0], POLLIN, md_event_now() + 5000000000LL) >= 0);
    char byte; CHECK(read(channel[0], &byte, 1) == 1 && byte == 'r');
    CHECK(!native_trace(PTRACE_SEIZE, leader, NULL, (void *)trace_options));
    if (store) {
        /* The guest was forked with the caller's mask. Only the native owner
         * uses zero; creation requests already contain the guest's masked mode. */
        umask(0);
        CHECK(!md_task_registry_publish(tasks, leader, leader, &thread(leader)->identity, NULL));
        struct md_input_launch input={.endpoint=gamepad_endpoint,.wake_fd=fd_ready};
        struct md_fs_worker_launch fs_launch={.attachments=attachments,.attachment_count=attachment_count,
            .statistics=statistics!=NULL,.inherited=&inherited_ipc,.input=&input,.network=&network_profile};
        int startup = md_fs_worker_start(store, endpoint, admitted_path, tasks, fd_exchanges, &fs_launch, &filesystem);
        md_ipc_inherited_release(&inherited_ipc);
        if (startup) errno = -startup;
        CHECK(!startup);
        struct md_mount_namespace *mounts=md_fs_worker_mounts(filesystem);
        struct md_task_resources owner_resources={.mounts=mounts};
        CHECK(!md_task_registry_publish(tasks,getpid(),getpid(),&owner,&owner_resources));
        CHECK(!md_resources_init(&thread(leader)->resources,mounts));
        CHECK(!md_ipc_namespace_bind(thread(leader)->resources.ipc,store));
        CHECK(!md_task_registry_publish(tasks,leader,leader,&thread(leader)->identity,&thread(leader)->resources));
        broker = md_broker_create(filesystem, statistics != NULL, delegated_notification); CHECK(broker);
        md_broker_completion_handler(broker,completed_notification);
        md_broker_control_factory(broker,md_proc_file_control_object);
        md_broker_control_query(broker,control_file_query);
        md_broker_native_observer(broker,cooperative_native_observe);
        md_broker_entry_handler(broker,entry_control);
        if (entry_wake_fd>=0) md_broker_entry_target(broker,getpid());
        md_broker_retirement_handler(broker,broker_retire);
        buffer_service=md_fs_buffer_service_new(filesystem,tasks,fd_wakeup,&task_wait,buffer_complete); CHECK(buffer_service);
        md_fs_buffer_admission(buffer_service,task_completion_available);
        md_fs_buffer_restricted(buffer_service,checkpoint_restricted);
        md_fs_buffer_control_factory(buffer_service,md_proc_file_control_object);
        md_fs_buffer_control_query(buffer_service,control_file_query);
        md_fs_buffer_control_capture(buffer_service,control_file_capture);
        md_fs_buffer_exec_capture(buffer_service,executable_capture);
        md_fs_buffer_path_capture(buffer_service,proc_path_capture);
        CHECK(!md_mount_copy_service_create(filesystem,mount_copy_complete,&mount_copy_service));
        const char *program = "", *cwd = "/";
        for (int i = argument+1; i+1 < argc; ++i) {
            if (!strcmp(argv[i], "--cwd")) cwd = argv[++i];
            else if (!strcmp(argv[i], "--namespace") && i+2 < argc) { program = argv[i+2]; break; }
        }
        CHECK(!md_launch_register(&registration, store, program, cwd, launch_identity.uid.effective));
    } else CHECK(!md_resources_init(&thread(leader)->resources,NULL));
    /* The launcher's standard streams are already shared with the stopped
     * child. Pin only their identity, so later dumpability/filter changes do
     * not require exporting them merely to inspect metadata. */
    for (int fd=0;fd<3;++fd) {
        int flags=fcntl(fd,F_GETFD);
        if (flags<0) { CHECK(errno==EBADF); continue; }
        struct md_fd_object *pin=md_fd_native_pin_new(fd,&thread(leader)->identity);
        if (!pin) {
            SUPERVISOR_TRACE("PROBE inherited-fd pin=%d error=%d\n",fd,errno);
            continue;
        }
        if (md_descriptor_inode_is(pin)) {
            struct stat current;
            CHECK(!fstat(md_fd_native_pin_descriptor(pin),&current));
            for (int previous=0;previous<fd;++previous) {
                struct md_fd_object *other=md_fd_origins_object(thread(leader)->resources.files,previous,NULL);
                struct stat prior;
                int descriptor=md_fd_native_pin_descriptor(other);
                if (descriptor>=0 && md_descriptor_inode_is(other) && !fstat(descriptor,&prior)
                        && current.st_dev==prior.st_dev && current.st_ino==prior.st_ino) {
                    md_fd_object_release(pin); pin=other; break;
                }
                md_fd_object_release(other);
            }
        }
        struct md_fd_origin_update update={0};
        CHECK(!md_fd_origins_prepare_object(thread(leader)->resources.files,pin,(unsigned)flags,&update));
        md_fd_origins_finish(&update,fd);
        md_fd_object_release(pin);
    }
    struct md_process_limits limits;
    CHECK(!md_process_limits_read(&limits));
    thread(leader)->resources.process=md_process_member_new(&thread(leader)->identity,&limits);
    CHECK(thread(leader)->resources.process);
    network_error=md_network_launch_open(&network_profile,launch_identity.user_namespace,&launch_network);
    if (network_error) { errno=-network_error; perror("Shroot network preparation"); return 125; }
    if (launch_network.space) {
        md_network_namespace_release(thread(leader)->resources.network);
        thread(leader)->resources.network=md_network_namespace_retain(launch_network.space);
    }
    CHECK(!md_task_registry_publish(tasks,leader,leader,&thread(leader)->identity,&thread(leader)->resources));
    freezer=md_cgroup_member_retain(thread(leader)->resources.cgroup);
    if (selected_hostname) CHECK(!md_uts_namespace_set(thread(leader)->resources.uts,0,
        selected_hostname,strlen(selected_hostname)));
    byte = 'g'; CHECK(write(channel[0], &byte, 1) == 1); close(channel[0]);
    if (seconds) cancellation_arm((unsigned)seconds);
    while (tasks_pending()) {
        signal_entries_progress();
        pid_collect();
        int status; struct rusage usage; pid_t pid = wait_event(&status, &usage);
        if (!pid) break;
        struct thread *signalled=WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP
            ? find_thread(pid) : NULL;
        if (signalled && signalled->machine.signal_alias) {
            int error=md_svc_machine_signal_committed(native_trace,md_memory_svc(signalled->memory),
                &signalled->machine,pid,status);
            if (error==-ESRCH) { signalled->resume_lost=1; continue; }
            CHECK(!error);
        }
        struct thread *policy_peer=policy_installer ? find_thread(pid) : NULL;
        if (policy_installer && (md_task_quiesce_contains(policy_barrier,pid)
                || (policy_peer && policy_peer!=policy_installer && policy_peer->tgid==policy_installer->tgid))) {
            CHECK(!quiesce_event(policy_barrier,pid,status,&usage));
            continue;
        }
        if (quiescence_repair_event(pid,status,&usage)) continue;
        if (retained_barrier_event(pid,status,&usage)) continue;
        if (memory_event(pid,status,&usage)) continue;
        if (svc_pool_event(pid,status)) continue;
        if (task_action_event(pid,status,&usage)) continue;
        if (mapping_event(pid,status)) continue;
        if (mapping_guard_event(pid,status,&usage)) continue;
        int opened=md_fs_buffer_child(buffer_service,pid,status);
        CHECK(opened>=0);
        if (opened) continue;
        int mapped=md_task_helper_event(helpers,pid,status);
        if (mapped<0) errno=-mapped;
        CHECK(mapped>=0);
        if (mapped) { adapter_progress(); continue; }
        if (retained_event(pid,status,&usage)) continue;
        struct thread *t = thread(pid);
        /* Retire a completed signal veneer before syscall-restart recovery.
         * A nested delivery must observe the handler's actual IDLE state. */
        if (t->signals.control && t->signals.control->entry_packet) {
            t->kernel_stopped=1;
            t->interrupt_pending=0;
            if (signal_control_event(t,status)) continue;
        }
        struct md_task_signal_stop signal_stop;
        md_task_signal_stop_init(&signal_stop,native_trace);
        int restored_signal=0;
        if (WIFSTOPPED(status) && !((unsigned)status>>16)) {
            int deliver=0;
            int owned=md_signal_delivery_event(&t->signals.delivery,pid,WSTOPSIG(status),&deliver);
            CHECK(owned>=0);
            if (owned==MD_SIGNAL_CARRIER) { resume(pid,deliver); continue; }
            restored_signal=owned==MD_SIGNAL_RESTORED;
        }
        if (completed_notification_event(t,status,&usage)) continue;
        if (metadata_restart_event(t,status)) continue;
        if (t->signals.shielded && WIFSTOPPED(status) && !((unsigned)status>>16)
                && WSTOPSIG(status)<32 && WSTOPSIG(status)!=SIGSTOP) {
            siginfo_t info;
            if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"shielded-signal")) continue;
            if (!md_signal_synchronous(&info)) {
                int retained=md_signal_delivery_retain(&t->signals.delivery,pid,&info); CHECK(retained>=0);
                notification_interrupted(t);
                resume(pid,retained); continue;
            }
        }
        /* This delivery already passed syscall recovery. Rechecking it on a
         * nondumpable task would defer the same signal for a code-read helper
         * indefinitely, even when no syscall needs replay. */
        if (!restored_signal && interrupted_metadata_event(t,status,&usage,&signal_stop)) continue;
        t->kernel_stopped=1;
        t->interrupt_pending=0;
        if (md_debugger_active(debugger)) t->usage=usage;
        if (filter_install_event(t,status)) continue;
        if (guest_notification_event(t,status)) continue;
        if (signal_control_event(t,status)) continue;
        if (WIFEXITED(status) || WIFSIGNALED(status)) { task_terminal_event(t,pid,status); continue; }
        CHECK(WIFSTOPPED(status));
        if (statistics) statistics->stops++;
        unsigned event = (unsigned)status >> 16;
        if (lost_stop_event(t,event)) continue;
        int sig = WSTOPSIG(status);
        if (t->signals.return_pending && !event && sig==(SIGTRAP|0x80)) {
            struct ptrace_syscall_info info={0};
            if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"signal-return-entry")) continue;
            if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                t->signals.return_pending=0;
                if (t->signals.wait_view.active) {
                    uint64_t mask=t->signals.wait_view.saved&~MD_SIGNAL_SYS_BIT;
                    signal_wait_restore(t);
                    if (!signal_mask(t,mask,"wait-return-mask")) continue;
                }
                CHECK(!md_event_signal(fd_ready));
            }
        }
        if (readiness_native_event(t,status)) continue;
        if (!event && sig!=(SIGTRAP|0x80)
                && readiness_signal_event(t,sig,&signal_stop,restored_signal)) continue;
        if (!event && sig!=(SIGTRAP|0x80) && (t->phase==IDLE || t->entry.execution || entry_pending(t))
                && md_memory_svc(t->memory)) {
            /* Ignored deliveries must not cancel a native effect or mark its
             * resident frame pending. Kernel restart retains the live wait. */
            if (signal_ignored(t,sig,&signal_stop)) { resume(pid,0); continue; }
            int observed=entry_effect_observe(t);
            if (observed==-ESRCH) { t->resume_lost=1; continue; }
            CHECK(!observed);
            if (entry_native_journal(t)) signal_stop.has_regs=0;
            int deliver=0;
            int owned=md_svc_machine_signal(md_memory_svc(t->memory),&t->machine,pid,
                &t->signals.delivery,&signal_stop,&deliver);
            if (owned==-ESRCH) { t->resume_lost=1; continue; }
            if (owned<0) errno=-owned;
            CHECK(owned>=0);
            if (owned) {
                if (md_signal_delivery_pending(&t->signals.delivery)) entry_wait_complete(t,-EINTR);
                deliver_signal(pid,deliver,restored_signal,&signal_stop); continue;
            }
        }
        if (!event && sig!=(SIGTRAP|0x80)) notification_interrupted(t);
        if (event==PTRACE_EVENT_EXIT) { task_exit_event(t,pid,event); } else if (event==PTRACE_EVENT_VFORK_DONE) { task_vfork_done_event(t,pid,event); } else if (event == PTRACE_EVENT_EXEC) { task_exec_event(t,pid,event); } else if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) { task_birth_event(t,pid,event); } else if (event == PTRACE_EVENT_STOP) { task_stop_event(t,pid,status,sig); }
        else if (event == PTRACE_EVENT_SECCOMP) { execution_seccomp_event(t,pid,status,&usage); } else if (!event && sig == (SIGTRAP | 0x80)) { execution_syscall_event(t,pid,status,&usage); } else if (event==MD_TASK_CHECKPOINT_EVENT) { execution_checkpoint_event(t,pid); } else { signals_event(t,pid,status,sig,&signal_stop,restored_signal); }
    }
    for (unsigned i = 0; i < 64 && denials[i].count; i++)
        SUPERVISOR_TRACE("PROBE SIGSYS summary nr=%u phase=%d adapted=%u count=%u\n",
            denials[i].nr, denials[i].phase, denials[i].adapted, denials[i].count);
    SUPERVISOR_TRACE("PROBE complete status=%d threads=%u images=%u adapted=%u signals=%u copyFaults=%u fstatFailures=%u sandboxSupportEstablished=false\n",
        result, total, images, calls, signals, copy_faults, fstat_failures);
    SUPERVISOR_TRACE("PROBE external metadata calls=%u errors=%u\n", external_stats, external_errors);
    SUPERVISOR_TRACE("PROBE protected metadata calls=%u\n", protected_stats);
    SUPERVISOR_TRACE("PROBE retained requests=%u errors=%u\n", retained_calls, retained_errors);
    SUPERVISOR_TRACE("PROBE proc-root domain=1 changes=%u denials=%u\n",
        root_changes, restricted_denials);
    SUPERVISOR_TRACE("PROBE job-control stops=%u wakes=%u racedWakes=%u\n",
        group_stops, group_wakes, raced_wakes);
    SUPERVISOR_TRACE("PROBE identity admittedImages=%u changes=%u hostUid=%u\n",
        admitted_images, identity_changes, getuid());
    close(signal_fd); return result;
}

static void report_statistics(void) {
    if (statistics) {
        struct rusage usage;
        if (!getrusage(RUSAGE_SELF, &usage)) fprintf(stderr, "MD_CPU supervisor userUs=%llu systemUs=%llu\n",
            (unsigned long long)usage.ru_utime.tv_sec * 1000000 + usage.ru_utime.tv_usec,
            (unsigned long long)usage.ru_stime.tv_sec * 1000000 + usage.ru_stime.tv_usec);
        fprintf(stderr, "MD_SUPERVISOR stops=%llu\n", (unsigned long long)statistics->stops);
        fprintf(stderr,"MD_EXECUTION taskEntries=%llu completed=%llu promotions=%llu nativeEffects=%llu\n",
            (unsigned long long)statistics->entry_executions,
            (unsigned long long)statistics->entry_completions,
            (unsigned long long)statistics->entry_promotions,
            (unsigned long long)statistics->entry_effects);
        static const char *context_names[MD_CONTEXT_COUNT]={"process","birth","exec","signal","wait",
            "descriptor","memory","policy","namespace","debug"};
        fprintf(stderr,"MD_EXECUTION_NATIVE calls=%llu\n",(unsigned long long)statistics->entry_native);
        fprintf(stderr,"MD_EXECUTION_LOCAL_NATIVE calls=%llu\n",(unsigned long long)statistics->entry_local_native);
        for (unsigned i=0;i<MD_CONTEXT_COUNT;++i) if (statistics->entry_contexts[i])
            fprintf(stderr,"MD_EXECUTION_CONTEXT reason=%s calls=%llu\n",context_names[i],
                (unsigned long long)statistics->entry_contexts[i]);
        for (unsigned i=0;i<MD_CONTEXT_COUNT;++i) if (statistics->entry_stop_reasons[i])
            fprintf(stderr,"MD_EXECUTION_STOP reason=%s calls=%llu\n",context_names[i],
                (unsigned long long)statistics->entry_stop_reasons[i]);
        fprintf(stderr,"MD_SIGNAL_RETURN attempted=%llu eligible=%llu admitted=%llu copied=%llu relocated=%llu\n",
            (unsigned long long)statistics->signal_frames[0],(unsigned long long)statistics->signal_frames[1],
            (unsigned long long)statistics->signal_frames[2],(unsigned long long)statistics->signal_frames[3],
            (unsigned long long)statistics->signal_frames[4]);
        fprintf(stderr, "MD_INTERCEPTION ptraceRequests=%llu unknown=%llu nativeSeeks=%llu\n",
            (unsigned long long)statistics->ptrace_requests, (unsigned long long)statistics->unknown,
            (unsigned long long)statistics->native_seeks);
        for (unsigned group=0;group<2;++group) for (unsigned i=0;i<64;++i)
            if (statistics->ptrace_operations[group][i])
                fprintf(stderr,"MD_PTRACE request=%u calls=%llu\n",group ? 0x4200+i : i,
                    (unsigned long long)statistics->ptrace_operations[group][i]);
#define REPORT_CHECKPOINT(field,symbol) if (statistics->checkpoints[CHECKPOINT_COUNT_##field]) \
        fprintf(stderr,"MD_CHECKPOINT site=" #field " calls=%llu\n", \
            (unsigned long long)statistics->checkpoints[CHECKPOINT_COUNT_##field]);
        MD_CHECKPOINT_SITES(REPORT_CHECKPOINT)
#undef REPORT_CHECKPOINT
        for (unsigned i=0;i<128;++i) if (statistics->buffer_commands[i])
            fprintf(stderr,"MD_BUFFER command=%u calls=%llu\n",i,
                (unsigned long long)statistics->buffer_commands[i]);
        fprintf(stderr, "MD_EVENT_LOOP polls=%llu waits=%llu\n",
            (unsigned long long)statistics->event_polls, (unsigned long long)statistics->event_waits);
        fprintf(stderr,"MD_EVENT_TIME collectCalls=%llu collectNs=%llu sleepCalls=%llu sleepNs=%llu\n",
            (unsigned long long)statistics->event_collect.calls,(unsigned long long)statistics->event_collect.nanoseconds,
            (unsigned long long)statistics->event_sleep.calls,(unsigned long long)statistics->event_sleep.nanoseconds);
        fprintf(stderr,"MD_QUIESCENCE interrupts=%llu memoryRepairs=%llu\n",
            (unsigned long long)statistics->quiesce_interrupts,(unsigned long long)statistics->memory_repairs);
        fprintf(stderr,"MD_FD_NATIVE untrackedClose=%llu unchangedFlags=%llu\n",
            (unsigned long long)statistics->untracked_close,(unsigned long long)statistics->unchanged_fd_flags);
        fprintf(stderr,"MD_ENTRY_FENCE transitions=%llu drains=%llu quiescences=%llu\n",
            (unsigned long long)statistics->entry_fence_transitions,(unsigned long long)statistics->entry_fence_drains,
            (unsigned long long)statistics->entry_fence_quiescences);
        fprintf(stderr,"MD_NOTIFICATION rebounds=%llu readmissionsRetired=%llu\n",
            (unsigned long long)statistics->notification_rebounds,
            (unsigned long long)statistics->notification_readmissions_retired);
        fprintf(stderr,"MD_DIRECT status=%llu open=%llu fallback=%llu admit=%llu retry=%llu compactClose=%llu nativeIO=%llu capturedWrites=%llu capturedReceives=%llu entryReceives=%llu\n",
            (unsigned long long)statistics->direct_status,(unsigned long long)statistics->direct_open,
            (unsigned long long)statistics->direct_fallback,(unsigned long long)statistics->direct_admit,
            (unsigned long long)statistics->direct_retry,(unsigned long long)statistics->compact_close,
            (unsigned long long)statistics->native_io,(unsigned long long)statistics->captured_writes,
            (unsigned long long)statistics->captured_receives,
            (unsigned long long)statistics->entry_receives);
        for (unsigned nr = 0; nr < 512; nr++)
            if (statistics->calls[nr].trace || statistics->calls[nr].notification)
                fprintf(stderr, "MD_SYSCALL nr=%u trace=%llu notification=%llu\n", nr,
                    (unsigned long long)statistics->calls[nr].trace,
                    (unsigned long long)statistics->calls[nr].notification);
    }
}
