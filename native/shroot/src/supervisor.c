#define _GNU_SOURCE
#include "fd_native_pin.h"
#include "file_permissions.h"
#include "host_identity.h"
#include "network_launch.h"
#include "event_wait.h"
#include "fs_rpc.h"
#include "fs_worker.h"
#include "input_session.h"
#include "device_events.h"
#include "mount_copy_service.h"
#include "mount_policy.h"
#include "fs_engine.h"
#include "command_access.h"
#include "fs_mounts.h"
#include "namespace_broker.h"
#include "notification_result.h"
#include "signal_delivery.h"
#include "task_checkpoint.h"
#include "task_stop.h"
#include "task_registers.h"
#include "task_birth.h"
#include "signal_protocol.h"
#include "cooperative_channel.h"
#include "entry_route.h"
#include "svc_machine.h"
#include "guest_domain.h"
#include "task_resources.h"
#include "cgroup.h"
#include "network_route.h"
#include "network_io.h"
#include "fd_mutation.h"
#include "control_epoll.h"
#include "poll_wait.h"
#include "fd_admission.h"
#include "epoll_wait.h"
#include "descriptor_inode.h"
#include "descriptor_calls.h"
#include "fd_exchange.h"
#include "fd_return.h"
#include "fd_install.h"
#include "fd_client.h"
#include "elf_admission.h"
#include "interception.h"
#include "interception_memory.h"
#include "task_helper.h"
#include "task_mapping.h"
#include "task_quiesce.h"
#include "task_filter.h"
#include "private_memory.h"
#include "memory_replace.h"
#include "task_outgoing.h"
#include "ipc_origins.h"
#include "ipc_io_host.h"
#include "socket_payload.h"
#include "received_fds.h"
#include "fs_buffer.h"
#include "adapter_frame.h"
#include "fs_buffer_service.h"
#include "fd_filter.h"
#include "proc_file.h"
#include "proc_namespace.h"
#include <linux/futex.h>
#include <linux/nsfs.h>
#include "proc_sysctl.h"
#include "user_sysctl.h"
#include "proc_static.h"
#include "network_proc.h"
#include "socket_calls.h"
#include "proc_control.h"
#include "control_ioctl.h"
#include "launch_identity.h"
#include "sysv_shm.h"
#include "sysv_ipc.h"
#include <linux/memfd.h>
#include <sys/mman.h>
#include "task_registry.h"
#include "guest_user.h"
#include "guest_accounts.h"
#include "process_owner.h"
#include "raw.h"
#include "profile.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/ioprio.h>
#include <linux/close_range.h>
#include "linux_abi.h"
#include "task_descriptor.h"
#include <linux/seccomp.h>
#include <signal.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include "filesystem_ioctl.h"
#include "terminal_calls.h"
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include "guest_debugger.h"
#include "task_status.h"
#include "pid_descriptor.h"
#include "pid_calls.h"
#include "process_image_view.h"
#include "file_snapshot.h"
#include <sys/signalfd.h>
#include <sys/eventfd.h>
#include <sys/timerfd.h>
#include "event_signal.h"
#include <sys/epoll.h>
#include "launch_registry.h"
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <unistd.h>
#include "task_wait.h"
#include "pending.h"
#include "fs_path_call.h"
#include "task_memory.h"
#include "clone_call.h"
#include "syscall_result.h"
#include "proc_paths.h"

/* One supervisor owns one launched tree. It never attaches to Android tasks. */
static struct md_interception_abi abi;
static struct md_task_quiesce *entry_fence_barrier;
static struct md_interception_memory *entry_fence_memory;
static struct thread *entry_fence_owner;
static int entry_fence_stage, entry_fence_error;
static int entry_fence_protected;
static uint64_t entry_fence_slots;
static int64_t entry_fence_retry_at;
static unsigned entry_fence_waiters;
static int64_t entry_fence_deadline;
#define ENTRY_QUIESCING (entry_fence_stage==1)
#define READY abi.ready
#define DONE abi.done
#define ALLOCATE abi.allocate
#define ALLOCATED abi.allocated
#define DISPATCH abi.dispatch
#define EXPORT abi.export_fd
#define EXPORTED abi.exported
#define STORE abi.store
#define STORED abi.stored
#define LOAD_BYTE abi.load_byte
#define LOADED_BYTE abi.loaded_byte
#define STORE_IDS abi.store_ids
#define STORED_IDS abi.stored_ids
#define RAW_GATE abi.raw_gate
#define COPY_BEGIN abi.copy_begin
#define COPY_END abi.copy_end
static int diagnostics;
enum {
#define CHECKPOINT_COUNTER(field,symbol) CHECKPOINT_COUNT_##field,
    MD_CHECKPOINT_SITES(CHECKPOINT_COUNTER)
#undef CHECKPOINT_COUNTER
    CHECKPOINT_COUNT
};
struct interception_statistics {
    uint64_t ptrace_requests, unknown, native_seeks, stops;
    uint64_t ptrace_operations[2][64];
    uint64_t checkpoints[CHECKPOINT_COUNT];
    uint64_t buffer_commands[128];
    uint64_t event_polls, event_waits;
    uint64_t quiesce_interrupts, memory_repairs, entry_fence_transitions, entry_fence_drains, entry_fence_quiescences;
    uint64_t notification_rebounds, notification_readmissions_retired;
    uint64_t direct_status, direct_open, direct_fallback, direct_admit, direct_retry, compact_close, native_io;
    uint64_t captured_writes, captured_receives, entry_receives;
    uint64_t untracked_close, unchanged_fd_flags;
    struct md_cost event_collect, event_sleep;
    struct { uint64_t trace, notification; } calls[512];
};
static struct interception_statistics *statistics;
#define TRACE(...) do { if (diagnostics) fprintf(stderr, __VA_ARGS__); } while (0)
enum phase { IDLE, ALLOCATING, DISPATCHING, EXECUTING, OBSERVING,
    EXPORT_CANCEL, EXPORTING, EXPORT_ENTRY, EXPORT_NOTIFY, EXPORT_RETURN, STORING,
    DELEGATE_CANCEL, LOADING_PATH, PROC_CANCEL, PROC_OPENING, STORING_IDS, DOMAIN_PATH,
    WATCH_CANCEL, WATCH_ENTRY, WATCH_READ, IPC_WAIT_ENTRY, IPC_WAITING,
    SOCKET_WAIT_ENTRY, SOCKET_WAITING, CALL_RESTART_ENTRY, CALL_RESTART_EXIT,
    FILE_WAIT_ENTRY, FILE_WAITING, FILE_INTERRUPTING, CONTROL_WAIT_ENTRY, CONTROL_WAITING, CLOSE_WAIT_ENTRY, CLOSE_WAITING,
    WAIT_CANCEL_EXIT, EPOLL_PROBE, EPOLL_CALL, EPOLL_WAIT,
    IDENTITY_COPY, DEBUG_WAIT_ENTRY, DEBUG_WAIT_EXIT, DEBUG_PARK_ENTRY, DEBUG_PARK_EXIT,
    MEMORY_WAIT, RESULT_REPLAY_ENTRY, RESULT_REPLAY_EXIT, PROCESS_OBSERVING, PROCESS_TID_STORE,
    PROCESS_INFO_LOAD, PROCESS_INFO_STORE, PROCESS_VALUE_STORE, PROCESS_INIT_ENTRY, PROCESS_INIT_EXIT, PROC_LOOKUP,
    CLONE_ENTRY, CLONE_ADMISSION };
enum memory_action { MEMORY_NONE, MEMORY_KERNEL, MEMORY_DETACH, MEMORY_FORK };
enum call_restart { RESTART_SIGNAL=-4105, RESTART_PREPARATION=-4106 };
struct metadata_operation {
    enum phase previous;
    struct seccomp_notif request;
    struct user_pt_regs saved, returned;
    uint64_t mask;
    int mask_shielded;
    int channel, error, descriptor;
    struct md_fd_channel *transport;
    struct md_fd_channel_capture capture;
    struct md_fd_order files;
    struct md_fs_buffer_request *stat_call;
    struct user_pt_regs control;
    int waiting, checking;
    int proc_attempted, proc_directory, proc_stat, task_open;
    size_t copied, output_size, path_size;
    uintptr_t output;
    char path[4096];
    struct stat value;
};
struct mapping_owner {
    pid_t caller, group;
    struct md_pid_identity *process;
    struct user_pt_regs continuation;
    struct md_shm_space *space;
    struct md_shm_mapping *pending;
    struct md_interception_memory *memory;
    struct md_resource_order order;
    struct md_task_mapping_request request;
    struct md_mapping_change change;
    struct md_task_mapping direct;
    struct md_fd_order files;
    struct md_fd_origin_update delivery;
    struct task_action *loan;
    int started, file, noexec, readonly, guarded, borrowed, retiring;
    int64_t deadline;
};
struct capture_owner {
    pid_t caller;
    struct md_fs_buffer_request *request;
};
struct memory_capture {
    pid_t caller;
    uint64_t mask;
    struct user_pt_regs returned;
    struct md_interception_buffer buffer;
};
struct probe_owner { pid_t caller; struct user_pt_regs continuation; };
enum metadata_restart_stage { METADATA_CHECK, METADATA_ENTRY, METADATA_WAIT, METADATA_RETURN };
struct metadata_restart {
    enum metadata_restart_stage stage;
    struct user_pt_regs saved;
    uint64_t mask;
};
enum outgoing_stage { OUTGOING_QUEUED, OUTGOING_CAPTURE, OUTGOING_PREPARE, OUTGOING_READY, OUTGOING_SEND };
struct outgoing_owner {
    pid_t caller;
    enum outgoing_stage stage;
    struct user_pt_regs continuation;
    struct md_task_send input;
    struct md_fd_order files;
    struct md_fs_order fs_order;
    struct md_fs_context context;
    struct md_ipc_send_options options;
    struct md_ipc_send_address destination;
    struct md_fd_message *message;
    struct md_ipc_origin_request *preparation;
    struct md_received_fds *prepared;
    struct md_fd_capture capture;
    int brokered, io_waiting;
    struct md_ipc_io io;
};
/* Cold, authenticated-origin delivery only. The receive owner retains this
 * storage until the shared kernel receipt completes origin publication. */
struct receive_publication {
    struct md_fd_object *origins[253];
    struct md_fd_origin_update updates[253];
    int numbers[253];
};
struct receive_owner {
    pid_t caller;
    int started, ready, prepared;
    struct user_pt_regs continuation;
    struct md_task_receive request;
    struct md_fd_order files;
    struct md_received_fds *received;
    struct md_received_fds *expanded;
    struct receive_publication *publication;
    int outgoing[253];
    struct md_fd_capture capture;
    int brokered, io_waiting;
    struct md_ipc_io io;
    struct md_network_io network;
};
struct task_action {
    union { struct md_task_filter filter; struct md_task_call native; };
    struct md_interception_buffer buffer;
    struct user_pt_regs continuation;
    enum md_fd_selector_kind kind;
    int selector_number;
    unsigned selector_instructions;
    int number;
    struct thread *requester, *actor;
    struct md_fd_selector *group;
    struct md_fd_selector *selected_group;
    void (*complete)(struct thread *,long);
    struct md_fd_order files;
    pid_t rejected_group, rejected_task;
    int borrowed, remote, synthetic, checkpoint_suspended;
    /* A short native close uses the same actor/quiescence owner as filter
     * installation. The table pin survives the originating task's death. */
    struct md_fd_loan *loan;
    struct md_fd_origins *loan_table;
    struct md_fd_order *loan_turn;
    struct task_action *next;
    pid_t loan_waiter;
    struct md_broker_retirement retirement;
    int broker_borrowed;
    struct md_task_quiesce *outer_barrier;
    void (*retired)(void *,long);
    void *retired_context;
};
static struct md_task_call *task_action_call(struct task_action *action) {
    return action->loan ? &action->native : &action->filter.task;
}
struct readiness_owner {
    struct md_fd_order files;
    struct md_fd_capture capture;
    struct md_received_fds *received;
    struct md_fd_object *queue,*target;
    struct md_fd_admission *admission;
    struct md_fd_admission_request request;
    struct md_epoll_wait wait;
    struct md_poll_wait *poll;
    unsigned poll_count,poll_loaded;
    int64_t poll_started;
    struct user_pt_regs continuation;
    struct epoll_event event;
    struct md_task_call_arguments native_call;
    int native_caller;
    struct md_fd_install delivery;
    struct task_action *retirement;
    int loan_number, evicting, parked, finishing;
    long finish_result;
    int operation,number,reserved,sealed,classified,done,io_waiting;
    struct md_epoll_frame *detached;
    enum { READINESS_ATTACHED, READINESS_RESTORE_WAIT, READINESS_RESTORE_LOAN,
        READINESS_RESTORE_REPLY } restoring;
    long restore_result;
};
enum retry_kind {
    RETRY_TASK_ACTION=1u<<0, RETRY_MAPPING=1u<<1, RETRY_MEMORY=1u<<2,
    RETRY_BUFFER=1u<<3, RETRY_DESCRIPTOR=1u<<4, RETRY_RECEIVE=1u<<5,
    RETRY_OUTGOING=1u<<6, RETRY_PROC_DELIVERY=1u<<7, RETRY_RETURN=1u<<8,
    RETRY_FD=1u<<9, RETRY_EXPORT=1u<<10, RETRY_CONTROL=1u<<11,
    RETRY_PROC_IO=1u<<12, RETRY_PROC_LOOKUP=1u<<13, RETRY_READINESS=1u<<14, RETRY_CLOSE=1u<<15
};
struct retry_owner {
    struct md_pending_node node;
    struct thread *thread;
    unsigned mask;
};
struct thread {
    struct thread *next;
    struct thread *indexed;
    struct retry_owner retries[2];
    struct md_interception_memory *memory;
    struct md_interception_memory *remote_memory;
    struct md_interception_memory *memory_exposure;
    int memory_resume, memory_signal;
    struct md_svc_machine_thread machine;
    int entry_fence_waiting, entry_fence_held, entry_fence_replay, entry_fence_dumpability;
    int entry_fence_status, entry_fence_result;
    struct rusage entry_fence_usage;
    struct seccomp_notif *memory_notification;
    uint64_t buffer_admission_id;
    int buffer_admission_pending;
    unsigned buffer_admission_yields;
    struct md_broker_completion *broker_completion;
    struct { int held, status, has_info; struct rusage usage; siginfo_t info; } broker_event;
    int retained_signal;
    struct metadata_restart *metadata_restart;
    int metadata_signal_restored;
    struct md_signal_delivery signal_delivery;
    int signal_shielded;
    int signal_return_pending;
    struct thread *broker_next;
    struct md_interception_buffer buffer;
    struct md_interception_buffer cooperative_buffer;
    int cooperative_blocked, registration_revoked;
    struct md_fs_buffer_request *buffer_call;
    enum { DIRECT_NONE, DIRECT_STATUS, DIRECT_OPEN, DIRECT_PREPARE_RESULT, DIRECT_ADMIT, DIRECT_RESULT } direct;
    unsigned direct_flags, direct_mode;
    struct capture_owner *capture;
    struct probe_owner *probe;
    struct md_fd_capture pidfd_capture;
    struct outgoing_owner *outgoing;
    struct receive_owner *receive;
    struct md_fs_buffer_open buffer_open;
    int result_replay;
    struct user_pt_regs buffer_registers;
    struct md_fd_install proc_delivery;
    struct md_fs_buffer_request *proc_snapshot;
    struct seccomp_notif proc_snapshot_notification;
    int proc_image_fd;
    struct md_proc_file *proc_file;
    int proc_splice_write;
    struct md_proc_file_io proc_io;
    struct md_fd_order proc_lookup;
    struct md_fd_order buffer_descriptor_order;
    unsigned buffer_descriptor_operation;
    unsigned long buffer_descriptor_argument;
    int proc_waiting, proc_direct;
    struct md_control_pending *control_pending;
    struct md_fd_closed closed;
    struct md_control_pending *close_pending;
    struct user_pt_regs close_registers;
    enum phase close_phase;
    uint64_t close_mask;
    int close_mask_shielded;
    long close_result;
    int close_done,close_errors,terminal_close_pending;
    unsigned close_event;
    unsigned long close_message;
    size_t control_size;
    int control_done;
    long control_result;
    int control_mapping_fd;
    enum phase wait_resume;
    struct md_fd_return buffer_return;
    int buffer_return_waiting;
    int buffer_rights_delivered;
    int buffer_waiting;
    int dispatch_prepared;
    struct md_fd_selector *fd_selector;
    struct md_fd_selector *fork_selector;
    struct task_action *action;
    struct readiness_owner *readiness;
    int64_t epoll_started;
    pid_t selector_exit;
    pid_t pid, tgid, parent;
    int ready, born, initial_stop, resume_lost, listening, terminal_stop;
    enum phase phase;
    uint64_t stack, mask;
    struct user_pt_regs original;
    unsigned calls;
    char endpoint[108];
    int filter_length;
    int application_filter;
    int socket_waking;
    int socket_sigpipe;
    struct metadata_operation metadata;
    struct md_task_resources resources;
    struct md_resource_change resource_change;
    struct md_mount_copy_request *mount_copy;
    struct md_fd_order fd_order;
    struct md_fs_order fs_order;
    struct md_fs_anchor *fd_origin;
    struct md_fd_mutation fd_mutation;
    struct md_fd_origin_update pair_updates[2];
    struct user_pt_regs fd_registers;
    int fd_running, fd_cwd, fd_capture, fd_task;
    unsigned fd_job;
    int duplicate_attempted, duplicate_result;
    struct md_fd_exchange *fd_exchange;
    unsigned long cloning;
    int clone_prepared;
    struct md_fs_anchor *clone_cgroup;
    struct md_identity identity;
    struct md_exec_identity pending_identity;
    int exec_images[3];
    int exec_backings[2];
    struct md_fs_anchor *execfd_origin;
    struct md_process_executable *executable, *prepared_executable;
    struct md_fs_anchor *executable_origin, *prepared_executable_origin;
    int exec_images_ready;
    unsigned exec_mount_flags;
    struct md_exec_image *exec_setid;
    int mapped_image, entered, image_backings_ready;
    int debug_step_call, debug_birth;
    pid_t debug_exec_old;
    int kernel_stopped, kernel_resume, interrupt_pending, trace_options_dirty, debug_call_active, debug_adapter;
    int freezer_resume, freezer_signal;
    pid_t vfork_child;
    int terminal_observation;
    unsigned long debug_options;
    uint64_t debug_wait_mask;
    int debug_wait_waking;
    enum call_restart call_restart;
    struct rusage usage;
    uintptr_t process_image;
    int watch_wait;
    uint32_t *identity_buffer;
    unsigned identity_count, identity_copied;
    uintptr_t identity_address;
    int identity_loading, identity_stage, identity_result;
    struct md_shm_space *shm_space;
    struct mapping_owner *mapping;
    struct memory_capture *memory_capture;
    int memory_backing_pending;
    struct md_resource_order memory_order;
    enum memory_action memory_action;
    int memory_arguments_only;
    struct md_fd_order memory_files;
    struct md_mapping_change memory_change;
    int pid_filter, pid_preflight, filter_preflight, debug_replay_entry;
    struct md_pid_identity *pid_reservation;
    struct md_process_member *process_reservation;
    long pid_result;
    struct { uintptr_t address; pid_t value; } pid_stores[2];
    unsigned pid_store_count;
    uintptr_t pid_parent_address;
    struct thread *pid_birth_parent, *pid_birth_child;
    int pid_birth_waiting, pid_birth_signal;
    int pid_init_pending;
    int memory_access_pending;
    struct user_pt_regs process_return;
    int process_info_code;
    pid_t process_info_native;
    struct md_ipc_pending *ipc_pending;
    struct md_ipc_transport ipc_transport;
    int ipc_cancel, ipc_waking;
    uint64_t ipc_event;
    struct md_task_wait_member wait_member;
};
static struct thread *threads;
static struct thread *broker_events;
static unsigned task_action_calls;
static struct task_action *task_action_execution;
static struct task_action *loan_retirements;
static struct md_task_quiesce *task_action_barrier;
static struct md_task_quiesce *mapping_barrier;
static struct mapping_owner *mapping_execution;
static int task_action_releasing;
static struct readiness_owner *readiness_activation;
static void readiness_start(struct thread *);
static void readiness_release(struct thread *);
static void readiness_capture(struct thread *,unsigned,long);
static void readiness_progress(struct thread *);
static int readiness_native_event(struct thread *,int);
static void readiness_deliver(struct thread *);
static void readiness_task_progress(struct thread *,int,long);
static void readiness_loan_closed(pid_t,long);
static int readiness_evict(struct thread *,struct md_fd_loan *);
static int fd_loans_ready(struct thread *);
static void loan_start(struct task_action *);
static void task_action_schedule(void);
static void task_action_discard(struct thread *);
static void task_action_cleanup(void);
static int debugger_at_boundary(const struct thread *);
static void cancel_tree(int);
static struct md_pending_queue retry_queues[2];
static void retry_clear(struct thread *t, enum retry_kind kind) {
    struct retry_owner *owner=&t->retries[kind>=RETRY_FD];
    owner->mask&=~kind;
    if (!owner->mask) md_pending_cancel(&owner->node);
}
static void retry_wait(struct thread *t, enum retry_kind kind) {
    struct retry_owner *owner=&t->retries[kind>=RETRY_FD];
    owner->thread=t; owner->mask|=kind;
    md_pending_add(&retry_queues[kind>=RETRY_FD],&owner->node);
}
enum { THREAD_BUCKETS = 257 };
static struct thread *thread_index[THREAD_BUCKETS];
static struct thread *find_thread(pid_t);
static struct md_task_wait task_wait;
static int pid_collection_pending;
static struct thread *pid_collection_blocker;
static int pid_gone(pid_t);
static void pid_collect(void);
static struct md_debugger *debugger;
static pid_t leader;
static unsigned live, total, calls, images, signals, copy_faults, fstat_failures;
struct denial { unsigned nr, adapted, count; enum phase phase; };
static struct denial denials[64];
static int result, signal_fd, listener = -1;
static int fd_ready = -1;
static struct md_resource_wakeup *fd_wakeup;
static struct md_fd_exchanges *fd_exchanges;
static int cancelling;
static struct md_launch_registration registration = {.directory=-1, .record=-1};
static int cancellation_timer = -1;
static unsigned external_stats, external_errors;
static unsigned protected_stats;
static unsigned retained_calls, retained_errors;
static unsigned root_changes, restricted_denials;
static unsigned group_stops, group_wakes, raced_wakes;
static const char *admitted_path;
static struct md_fs_worker *filesystem;
static struct md_fs_buffer_service *buffer_service;
static struct md_mount_copy_service *mount_copy_service;
static struct md_ipc_origins *origin_service;
static struct md_task_helpers *helpers;
static struct md_task_quiesce *memory_barrier;
static int memory_completions_pending;
struct memory_execution;
static struct memory_execution *memory_execution;
static void memory_progress(void);
static int memory_event(pid_t, int, const struct rusage *);
static int64_t memory_deadline(void);
static int memory_runnable(void);
static int memory_available(pid_t);
static int entry_fence_park(const struct thread *);
static int entry_fence_adapt(const struct thread *);
static void memory_discard(void);
static int ipc_events=-1;
static int socket_events=-1;
static uint64_t ipc_sequence;
static struct md_namespace_broker *broker;
static struct md_task_registry *tasks;
static struct md_cgroup_member *freezer;
static uint64_t freezer_generation;
static int freezer_enabled;
static void freezer_progress(void);
static struct md_identity launch_identity;
static int virtual_identity;
static struct md_admission admission = {.source = -1};
static unsigned admitted_images, identity_changes;
static int registers(pid_t pid, struct user_pt_regs *out);
static int set_registers(pid_t pid, struct user_pt_regs *regs);
static void resume(pid_t pid, int sig);
static void cooperative_buffer_release(struct thread *t);
static int shield(struct thread *t);
static int save_signal_mask(struct thread *t,uint64_t *mask,const char *reason);
static int signal_mask(struct thread *t,uint64_t mask,const char *reason);
static int restore_mask(struct thread *t);
static void fd_discard(struct thread *t);
static void close_detach(struct thread *t);
static int close_begin(struct thread *,const struct user_pt_regs *,int);
static int debug_syscall_exit(struct thread *,const struct user_pt_regs *);
static void adapter_progress(void);
static void mapping_complete(void *, struct md_task_helper_result);
static void mapping_discard(struct mapping_owner *);
static void mapping_progress(void);
static int mapping_notification(struct thread *,const struct seccomp_notif *);
static void mapping_start(struct thread *);
static void memory_start(struct thread *);
static void proc_lookup_start(struct thread *);
static void buffer_descriptor_start(struct thread *);
static void dispatch(struct thread *t);
static void direct_complete(struct thread *,int);
static void direct_status_complete(struct thread *,const struct md_fs_result *,int);
static int buffer_cleanup(struct thread *);
static void dispatch_complete(struct thread *,long);
static void file_wait_complete(struct thread *);
static long buffer_result_finish(struct thread *,long,long);
static void report_statistics(void);
static int debugger_allowed(pid_t, pid_t);
static int proc_allowed(pid_t, pid_t);
static pid_t visible_pid(struct thread *,pid_t);
static pid_t native_pid(struct thread *,pid_t);
static void ipc_unwatch(struct thread *);
static void ipc_capture_step(struct thread *, unsigned, long);
static void socket_io_progress(struct thread *);
static int restore_mask(struct thread *);
static const unsigned long trace_options = PTRACE_O_TRACESECCOMP | PTRACE_O_TRACEEXEC
    | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEVFORKDONE | PTRACE_O_TRACECLONE
    | PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD;
static void release_operation(struct thread *t);
static void metadata_restart_return(struct thread *t,long value);
static void exec_backings_release(struct thread *t) {
    for (unsigned i=0;i<2;++i) {
        if (t->exec_backings[i]>=0) close(t->exec_backings[i]);
        t->exec_backings[i]=-1;
    }
}
static void exec_images_release(struct thread *t) {
    exec_backings_release(t);
    md_fs_anchor_release(t->execfd_origin); t->execfd_origin=NULL;
    md_exec_image_release(t->exec_setid); t->exec_setid=NULL;
    md_process_executable_release(t->prepared_executable); t->prepared_executable=NULL;
    md_fs_anchor_release(t->prepared_executable_origin); t->prepared_executable_origin=NULL;
    for (unsigned i=0;i<3;++i) {
        if (t->exec_images[i]>=0) close(t->exec_images[i]);
        t->exec_images[i]=-1;
    }
    t->exec_images_ready=0;
    t->exec_mount_flags=0;
}
static void exec_images_activate(struct thread *t) {
    t->exec_images_ready=t->exec_images[0]>=0;
    md_process_executable_release(t->executable);
    t->executable=t->prepared_executable; t->prepared_executable=NULL;
    md_fs_anchor_release(t->executable_origin);
    t->executable_origin=t->prepared_executable_origin; t->prepared_executable_origin=NULL;
}
static void adapter_discard(struct thread *);
static int describe_filter(pid_t pid, const struct user_pt_regs *regs) {
    if (!diagnostics) return -1;
    if (!((regs->regs[8] == SYS_seccomp && regs->regs[0] == SECCOMP_SET_MODE_FILTER)
            || (regs->regs[8] == SYS_prctl && regs->regs[0] == PR_SET_SECCOMP))) return -1;
    struct sock_fprog program;
    struct iovec local = {&program, sizeof(program)}, remote = {(void *)regs->regs[2], sizeof(program)};
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    TRACE("PROBE filter pid=%d instructions=%d readError=%d\n", pid,
        n == sizeof(program) ? program.len : -1, n < 0 ? errno : 0);
    return n == sizeof(program) ? program.len : -1;
}
static void describe_process(pid_t pid, uint64_t pc) {
    if (!diagnostics) return;
    const char *files[] = {"maps", "status"};
    for (unsigned i = 0; i < sizeof(files) / sizeof(*files); i++) {
        char path[64], line[1024];
        snprintf(path, sizeof(path), "/proc/%d/%s", pid, files[i]);
        FILE *file = fopen(path, "re");
        if (!file) { TRACE("PROBE %s error=%d\n", path, errno); continue; }
        unsigned count = 0;
        while (fgets(line, sizeof(line), file) && count++ < 4096) {
            unsigned long long begin = 0, end = 0;
            if (!i) sscanf(line, "%llx-%llx", &begin, &end);
            if (!i ? !pc || (pc >= begin && pc < end) :
                    !strncmp(line, "Seccomp", 7) || !strncmp(line, "NoNewPrivs", 10)
                    || !strncmp(line, "Uid:", 4) || !strncmp(line, "TracerPid:", 10))
                TRACE("PROBE %s %s", path, line);
        }
        fclose(file);
    }
}
static struct md_network_launch launch_network;
static struct md_task_checkpoints checkpoints;
static void cleanup(void) {
    report_statistics();
    if (cancellation_timer>=0) { close(cancellation_timer); cancellation_timer=-1; }
    for (struct thread *t = threads; t; t = t->next) if (t->pid) {
        kill(t->pid, SIGKILL); release_operation(t); adapter_discard(t);
    }
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
    md_task_checkpoints_clear(&checkpoints);
    memory_discard();
    task_action_cleanup();
    while (threads) {
        struct thread *next = threads->next;
        md_task_wait_untrack(&task_wait,&threads->wait_member);
        exec_images_release(threads);
        md_process_executable_release(threads->executable);
        md_fs_anchor_release(threads->executable_origin);
        fd_discard(threads);
        adapter_discard(threads);
        md_broker_forget(broker,threads->pid);
        cooperative_buffer_release(threads);
        md_memory_release(threads->memory);
        md_memory_release(threads->remote_memory);
        free(threads->memory_notification);
        free(threads->metadata_restart);
        if (threads->broker_completion) {
            md_fs_buffer_open_release(&threads->broker_completion->opened);
            free(threads->broker_completion);
        }
        md_fd_selector_release(threads->fd_selector);
        md_shm_space_release(threads->shm_space);
        ipc_unwatch(threads);
        md_ipc_cancel(md_ipc_namespace_active(threads->resources.ipc),&threads->ipc_pending);
        md_ipc_transport_close(&threads->ipc_transport);
        md_resources_unshare_finish(&threads->resources,&threads->identity,&threads->resource_change, -ECANCELED);
        md_resources_release(&threads->resources);
        md_identity_release(&threads->identity);
        free(threads->identity_buffer);
        md_signal_delivery_clear(&threads->signal_delivery);
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
    md_task_quiesce_destroy(entry_fence_barrier); entry_fence_barrier=NULL;
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
_Noreturn static void fail(const char *what, int line) {
    fprintf(stderr, "guest-runtime: supervisor failure line=%d %s errno=%d\n", line, what, errno);
    exit(125);
}
#define CHECK(x) do { if (!(x)) fail(#x, __LINE__); } while (0)
/* Cold transactions share stop ownership. Only retirement of a mapping's own
 * temporary FD may nest inside its barrier; unrelated guest work stays runnable. */
static int quiescence_busy(const struct mapping_owner *parent) {
    return ENTRY_QUIESCING || memory_execution || task_action_execution || task_action_releasing
        || (mapping_execution && mapping_execution!=parent);
}
static void release_operation(struct thread *t) {
    retry_clear(t,RETRY_EXPORT);
    struct metadata_operation *op=&t->metadata;
    md_fs_buffer_cancel(&op->stat_call);
    md_fd_channel_capture_cancel(&op->capture);
    md_fd_channel_release(op->transport); op->transport=NULL;
    if (op->descriptor>=0) { close(op->descriptor); op->descriptor=-1; }
    if (op->files.table && md_fd_order_cancel(&op->files)==1)
        CHECK(!md_fd_order_complete(&op->files));
    op->waiting=op->checking=0;
}
static void completed_event_discard(struct thread *t) {
    struct thread **event=&broker_events;
    while (*event && *event!=t) event=&(*event)->broker_next;
    if (*event) *event=t->broker_next;
    t->broker_next=NULL; t->broker_event.held=0;
    free(t->metadata_restart); t->metadata_restart=NULL;
    t->metadata_signal_restored=0;
}
static int buffer_release(struct thread *t) {
    int error=md_buffer_release(&t->buffer);
    /* Prepared input belongs to this lease, not to the next adapter call. */
    if (!error) t->dispatch_prepared=0;
    return error;
}
static void receive_release(struct receive_owner *owner) {
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
static void outgoing_release(struct outgoing_owner *owner) {
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
static void adapter_discard(struct thread *t) {
    t->clone_prepared=0;
    md_fs_anchor_release(t->clone_cgroup); t->clone_cgroup=NULL;
    md_memory_exposure_end(&t->memory_exposure,1);
    for (unsigned i=0;i<2;i++) {
        md_pending_cancel(&t->retries[i].node);
        t->retries[i].mask=0;
    }
    t->selector_exit=0;
    close_detach(t);
    task_action_discard(t);
    readiness_release(t);
    if (t->control_pending) { t->control_pending->dispose(t->control_pending); t->control_pending=NULL; }
    t->control_done=0;
    t->wait_resume=IDLE;
    if (t->proc_lookup.table) CHECK(!md_fd_order_cancel(&t->proc_lookup));
    if (t->buffer_descriptor_order.table) CHECK(!md_fd_order_cancel(&t->buffer_descriptor_order));
    t->buffer_descriptor_operation=0;
    if (t->memory_capture) {
        t->memory_capture->caller=0; t->memory_capture=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
    CHECK(!md_proc_file_io_cancel(&t->proc_io));
    md_proc_file_release(t->proc_file); t->proc_file=NULL; t->proc_waiting=t->proc_direct=0;
    if (t->outgoing) {
        struct outgoing_owner *owner=t->outgoing; t->outgoing=NULL; owner->caller=0;
        if (!owner->brokered && (owner->stage==OUTGOING_CAPTURE || owner->stage==OUTGOING_SEND))
            CHECK(!md_task_helper_detach(helpers,t->pid));
        else outgoing_release(owner);
    }
    if (t->probe) {
        t->probe->caller=0; t->probe=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
    md_fd_capture_cancel(&t->pidfd_capture);
    if (t->mapping) {
        struct mapping_owner *owner=t->mapping;
        owner->caller=0; t->mapping=NULL;
        if (owner->started) CHECK(!md_task_helper_detach(helpers,t->pid));
        else mapping_discard(owner);
    }
    if (t->memory_order.queue && md_resource_order_cancel(&t->memory_order)==1)
        CHECK(!md_resource_order_complete(&t->memory_order));
    if (t->memory_files.table && md_fd_order_cancel(&t->memory_files)==1)
        CHECK(!md_fd_order_complete(&t->memory_files));
    md_memory_mapping_abandon(t->memory,&t->memory_change);
    t->memory_action=0; t->memory_arguments_only=0;
    CHECK(!md_fd_return_cancel(&t->buffer_return));
    if (t->receive) {
        struct receive_owner *owner=t->receive; t->receive=NULL; owner->caller=0;
        if (!owner->brokered && owner->started && !owner->ready)
            CHECK(!md_task_helper_detach(helpers,t->pid));
        else receive_release(owner);
    }
    t->buffer_return_waiting=0;
    CHECK(md_broker_result_finish(broker,t->pid,NULL)>=0);
    CHECK(!md_fd_install_cancel(&t->proc_delivery));
    md_fs_buffer_cancel(&t->proc_snapshot);
    if (t->proc_image_fd>=0) { close(t->proc_image_fd); t->proc_image_fd=-1; }
    if (t->control_mapping_fd>=0) { close(t->control_mapping_fd); t->control_mapping_fd=-1; }
    md_fs_buffer_cancel(&t->buffer_call);
    if (t->capture) {
        t->capture->caller=0; t->capture=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
    md_fs_buffer_open_release(&t->buffer_open);
    md_ipc_transport_close(&t->ipc_transport);
    CHECK(!buffer_release(t));
    t->buffer_waiting=0;
    t->buffer_rights_delivered=0;
    t->direct=DIRECT_NONE;
}
static long checkpoint_native_trace(int request, pid_t pid, void *address, void *data) {
    if (statistics) {
        statistics->ptrace_requests++;
        if ((unsigned)request<64) ++statistics->ptrace_operations[0][request];
        else if ((unsigned)request-0x4200<64) ++statistics->ptrace_operations[1][request-0x4200];
    }
    return ptrace(request, pid, address, data);
}
static int checkpoint_restricted(pid_t pid) {
    struct thread *t=find_thread(pid);
    return !t || t->application_filter;
}
static int checkpoint_marker(uintptr_t pc) {
#define MATCH_MARKER(field,symbol) if (abi.field && pc==abi.field+4) return 1;
    MD_CHECKPOINT_SITES(MATCH_MARKER)
#undef MATCH_MARKER
    return 0;
}
static int checkpoint_entry_marker(uintptr_t pc,unsigned long command) {
    /* Full-register completion and task-context injection retain their
     * post-syscall stop; scalar C returns need no interrupt round trip. */
    if (abi.ready && pc==abi.ready+4) return 1;
    return abi.buffer_control && pc==abi.buffer_control+4
        && (md_adapter_buffer_scalar(command) || md_fs_buffer_scalar(command));
}
static struct md_task_checkpoints checkpoints={.trace=checkpoint_native_trace,
    .restricted=checkpoint_restricted,.marker=checkpoint_marker,.entry_marker=checkpoint_entry_marker};
static long native_trace(int request,pid_t pid,void *address,void *data) {
    return md_task_checkpoint_trace(&checkpoints,request,pid,address,data);
}
static void buffer_admission_yield(struct thread *t) {
    if (!t || !t->buffer_admission_pending) return;
    /* A shielded USER_NOTIF cannot take INTERRUPT until answered. No operation
     * or FD is replayed: ADDFD_SEND has already invalidated a published token. */
    struct seccomp_notif_resp reply={.id=t->buffer_admission_id,.val=MD_BUFFER_ADMIT_RETRY};
    int sent=ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply);
    CHECK(!sent || errno==ENOENT);
    if (!sent) ++t->buffer_admission_yields;
    if (t->memory_notification && t->memory_notification->id==reply.id) {
        free(t->memory_notification); t->memory_notification=NULL;
    }
    t->buffer_admission_pending=0;
}
static int buffer_admission_quiescing(pid_t pid) {
    return md_task_quiesce_interrupting(entry_fence_barrier,pid)
        || md_task_quiesce_interrupting(memory_barrier,pid)
        || md_task_quiesce_interrupting(mapping_barrier,pid)
        || md_task_quiesce_interrupting(task_action_barrier,pid);
}
static long quiesce_trace(int request,pid_t pid,void *address,void *data) {
    if (request==PTRACE_CONT || request==PTRACE_SYSCALL || request==PTRACE_SINGLESTEP) {
        struct thread *t=find_thread(pid);
        if (t) t->interrupt_pending=0;
        /* A barrier and guest debugger can share one INTERRUPT stop. Release
         * through normal admission so the debugger receives its pending stop
         * before guest execution, after the barrier has relinquished ownership. */
        if (entry_fence_park(t) || (t && md_debugger_traced(debugger,pid))) {
            CHECK(!data);
            t->memory_resume=1; t->kernel_stopped=1;
            return 0;
        }
    }
    long result=native_trace(request,pid,address,data);
    if (result>=0 && request==PTRACE_INTERRUPT) {
        if (statistics) ++statistics->quiesce_interrupts;
        struct thread *t=find_thread(pid);
        buffer_admission_yield(t);
        TRACE("PROBE quiesce-interrupt pid=%d phase=%d\n",pid,t ? (int)t->phase : -1);
        /* Quiescence can race an internal poll's entry stop and interrupt the
         * poll after the barrier releases. Retain the service wake through
         * both entry and wait; it must not become application EINTR.
         * Ordinary guest syscalls keep kernel restart semantics. */
        if (t && (t->phase==IPC_WAIT_ENTRY || t->phase==IPC_WAITING)) t->ipc_waking=1;
        if (t && (t->phase==SOCKET_WAIT_ENTRY || t->phase==SOCKET_WAITING
                || t->phase==EPOLL_CALL || t->phase==EPOLL_WAIT)) t->socket_waking=1;
        if (t && (t->phase==DEBUG_PARK_ENTRY || t->phase==DEBUG_PARK_EXIT)) t->debug_wait_waking=1;
    }
    /* Only barrier release restores this unchanged guest continuation.
     * Injected helper execution must retain the broker's stopped state. */
    if (result>=0 && (request==PTRACE_CONT || request==PTRACE_SYSCALL || request==PTRACE_SINGLESTEP))
        md_broker_resumed(broker,pid);
    return result;
}
static int tracee_request(struct thread *t, int request, void *address, void *data, const char *operation) {
    if (t->resume_lost) return 0;
    if (native_trace(request, t->pid, address, data)>=0) {
        if (request==PTRACE_GET_SYSCALL_INFO
                && ((struct ptrace_syscall_info *)data)->op==PTRACE_SYSCALL_INFO_NONE) {
            int displaced=md_task_stop_displaced(checkpoints.trace,t->pid);
            if (displaced<0) errno=-displaced;
            CHECK(displaced>=0);
            if (displaced) { t->resume_lost=1; return 0; }
        }
        if (request==PTRACE_INTERRUPT) t->interrupt_pending=1;
        return 1;
    }
    if (errno!=ESRCH) {
        int error=errno;
        fprintf(stderr,"guest-runtime: ptrace failure pid=%d phase=%d operation=%s request=%d errno=%d\n",
            t->pid,t->phase,operation,request,error);
        errno=error;
        CHECK(errno == ESRCH);
    }
    /* EVENT_WAIT: concurrent group death/exec may invalidate a reported stop.
     * Keep ownership until its exact exit/exec event; the tree deadline bounds
     * missing events. Never substitute registers, mask or a successful resume. */
    TRACE("PROBE lost-stop pid=%d phase=%d operation=%s\n", t->pid, t->phase, operation);
    t->resume_lost = 1; return 0;
}
static int lost_stop_event(struct thread *t,unsigned event) {
    if (!t->resume_lost || event==PTRACE_EVENT_EXEC || event==PTRACE_EVENT_EXIT) return 0;
    /* A broker observation can lose the stop before returning that same event
     * to the dispatcher. Discard only a confirmed stale observation; retain
     * the task and all cleanup ownership for its exact terminal event. */
    int displaced=md_task_stop_displaced(checkpoints.trace,t->pid);
    if (displaced<0) errno=-displaced;
    CHECK(displaced==1);
    return 1;
}
static struct thread *find_thread(pid_t pid) {
    if (pid <= 0) return NULL;
    for (struct thread *t = thread_index[(unsigned)pid % THREAD_BUCKETS]; t; t = t->indexed)
        if (t->pid == pid) return t;
    return NULL;
}
static int quiesce_event(struct md_task_quiesce *barrier,pid_t pid,int status,const struct rusage *usage) {
    int error=md_task_quiesce_event(barrier,pid,status,usage);
    if (error || !WIFSTOPPED(status)) return error;
    unsigned event=(unsigned)status>>16;
    if (event!=PTRACE_EVENT_CLONE && event!=PTRACE_EVENT_FORK && event!=PTRACE_EVENT_VFORK) return 0;
    unsigned long child;
    if (native_trace(PTRACE_GETEVENTMSG,pid,NULL,&child)<0) return -errno;
    struct thread *t=find_thread((pid_t)child);
    /* The dispatcher can own the child's initial stop before this barrier
     * exists. Until the parent event is replayed, its memory/files membership
     * is unknown, so participant selection cannot discover it by those owners.
     * Retain that existing stop, without replaying it or resuming the child. */
    if (!t || t->born || !t->initial_stop || !t->kernel_stopped) return 0;
    TRACE("PROBE quiesce-newborn parent=%d child=%d observed=dispatcher\n",pid,t->pid);
    return md_task_quiesce_add(barrier,t->pid,1,0);
}
static struct thread *thread(pid_t pid) {
    CHECK(pid>0);
    struct thread *t = find_thread(pid);
    if (t) return t;
    for (t = threads; t && t->pid; t = t->next) { }
    if (!t) { t = calloc(1, sizeof(*t)); CHECK(t); t->next = threads; threads = t; }
    CHECK(!t->retries[0].node.list && !t->retries[1].node.list);
    *t = (struct thread){.pid = pid, .tgid = pid, .next = t->next,
        .metadata = {.channel = -1, .descriptor = -1},
        .identity = md_identity_copy(&launch_identity), .memory = md_memory_new(),
        .shm_space = md_shm_space_new(),
        .buffer_open = {.fd=-1}, .proc_image_fd=-1, .control_mapping_fd=-1,
        .exec_images = {-1,-1,-1}, .exec_backings={-1,-1},
        .fd_selector = md_fd_selector_new()};
    CHECK(t->memory && t->fd_selector && t->shm_space);
    unsigned bucket = (unsigned)pid % THREAD_BUCKETS;
    t->indexed = thread_index[bucket]; thread_index[bucket] = t;
    md_task_wait_track(&task_wait,&t->wait_member,pid);
    live++; total++; return t;
}
static void pid_birth_release(struct thread *child) {
    struct thread *parent=child->pid_birth_parent;
    if (!parent) return;
    CHECK(parent->pid_birth_child==child);
    child->pid_birth_parent=NULL; parent->pid_birth_child=NULL;
    if (parent->pid_birth_waiting) {
        int sig=parent->pid_birth_signal;
        parent->pid_birth_waiting=parent->pid_birth_signal=0;
        resume(parent->pid,sig);
    }
}
static void pid_store_add(struct thread *t, uintptr_t address, pid_t value) {
    if (!address) return;
    CHECK(t->pid_store_count<2);
    t->pid_stores[t->pid_store_count].address=address;
    t->pid_stores[t->pid_store_count++].value=value;
}
static void entry_fence_cancel(struct thread *t) {
    if (t->entry_fence_waiting) {
        CHECK(entry_fence_waiters); --entry_fence_waiters;
        t->entry_fence_waiting=0;
    }
}
static void release_thread(struct thread *t) {
    entry_fence_cancel(t);
    if (entry_fence_owner==t) entry_fence_owner=NULL;
    t->entry_fence_held=0;
    md_svc_machine_forget(&t->machine);
    md_signal_delivery_clear(&t->signal_delivery);
    pid_birth_release(t);
    if (t->pid_birth_child) {
        t->pid_birth_child->pid_birth_parent=NULL;
        t->pid_birth_child=NULL;
    }
    int buffered=t->buffer.pool || t->buffer_call || t->buffer_waiting || t->action;
    fd_discard(t);
    adapter_discard(t);
    exec_images_release(t);
    md_process_executable_release(t->executable); t->executable=NULL;
    md_fs_anchor_release(t->executable_origin); t->executable_origin=NULL;
    md_task_registry_forget(tasks, t->pid);
    md_resources_exit_files(&t->resources,&t->closed);
    close_detach(t);
    md_identity_release(&t->identity);
    CHECK(!md_shm_space_release(t->shm_space)); t->shm_space = NULL;
    ipc_unwatch(t);
    CHECK(!md_ipc_cancel(md_ipc_namespace_active(t->resources.ipc),&t->ipc_pending));
    md_ipc_transport_close(&t->ipc_transport);
    CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change, -ECANCELED));
    CHECK(!md_resources_release(&t->resources));
    free(t->identity_buffer); t->identity_buffer = NULL;
    md_broker_forget(broker, t->pid);
    cooperative_buffer_release(t);
    release_operation(t);
    md_memory_stack_return(t->memory, t->stack);
    md_memory_release(t->memory); t->memory = NULL;
    md_memory_release(t->remote_memory); t->remote_memory=NULL;
    free(t->memory_notification); t->memory_notification=NULL;
    if (t->broker_completion) {
        md_fs_buffer_open_release(&t->broker_completion->opened);
        free(t->broker_completion); t->broker_completion=NULL;
    }
    completed_event_discard(t);
    md_fd_selector_release(t->fd_selector); t->fd_selector = NULL;
    struct thread **slot = &thread_index[(unsigned)t->pid % THREAD_BUCKETS];
    while (*slot && *slot != t) slot = &(*slot)->indexed;
    CHECK(*slot == t);
    *slot = t->indexed; t->indexed = NULL;
    md_task_wait_untrack(&task_wait,&t->wait_member);
    t->pid = 0; live--;
    if (buffered) adapter_progress();
}
static int duplicate_fd(pid_t pid, int descriptor) {
    /* Never substitute a group's leader for another thread's descriptor table.
     * Older kernels can duplicate a leader's descriptors; other threads use
     * their task-affine SCM_RIGHTS continuation instead. */
    int process=-1;
    int fd=md_task_descriptor_copy(pid,descriptor,&process);
    if (process>=0) close(process);
    return fd;
}
static int read_string(pid_t pid, uintptr_t address, char *text, size_t capacity) {
    ssize_t size=md_task_read_string(pid,address,text,capacity,md_page_size);
    return size<0 ? (int)size : 0;
}
static void export_begin(struct thread *t, const struct seccomp_notif *q) {
    CHECK(t->phase == IDLE || t->phase == DISPATCHING);
    t->metadata = (struct metadata_operation){.previous = t->phase, .request = *q,
        .channel = -1, .descriptor = -1,
        .output_size = q->data.nr == SYS_openat ? 0 : sizeof(struct stat),
        .output = q->data.args[q->data.nr == SYS_newfstatat ? 2 : 1]};
    t->phase = EXPORT_CANCEL;
    tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-notification");
}
static void export_start(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    op->mask_shielded=t->signal_shielded;
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->request.id) == -1 && errno == ENOENT);
    if (t->metadata_restart && op->previous==IDLE) op->saved=t->metadata_restart->saved;
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
static void replay(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    /* Reenter the original SVC: kernel admission, including any newer application
     * filter, precedes resource access, descriptor injection and output copying. */
    struct user_pt_regs regs = op->saved; regs.pc -= 4;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = EXPORT_ENTRY; resume(t->pid, 0);
}
static void load_path(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    if (op->path_size == sizeof(op->path)) op->error = -ENAMETOOLONG;
    if (op->request.data.args[1] > UINTPTR_MAX - op->path_size) op->error = -EFAULT;
    if (op->error) { replay(t); return; }
    struct user_pt_regs regs = op->saved;
    regs.pc = LOAD_BYTE; regs.regs[0] = op->request.data.args[1] + op->path_size;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = LOADING_PATH; resume(t->pid, 0);
}
static void export_reply(struct thread *t, long endpoint, uint64_t token) {
    struct user_pt_regs regs=t->metadata.control;
    regs.regs[0]=endpoint; regs.regs[1]=token;
    t->metadata.waiting=0;
    if (set_registers(t->pid,&regs)) resume(t->pid,0);
}
static void export_progress(struct thread *t) {
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
static void export_control(struct thread *t, const struct user_pt_regs *regs) {
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
static void store_next(struct thread *t);
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
static void store_next(struct thread *t) {
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
        t->signal_shielded=op->mask_shielded;
        release_operation(t);
        t->phase = op->previous; protected_stats++; resume(t->pid, 0); return;
    }
    _Static_assert(sizeof(struct stat) % 16 == 0, "register output blocks");
    struct user_pt_regs regs = op->returned;
    regs.pc = STORE; regs.regs[0] = op->output + op->copied;
    memcpy(&regs.regs[2], (const char *)&op->value + op->copied, 16);
    if (!set_registers(t->pid, &regs)) return;
    t->phase = STORING; resume(t->pid, 0);
}
static void proc_query_begin(struct thread *t, const struct seccomp_notif *q, int target) {
    struct metadata_operation *op = &t->metadata;
    op->proc_stat=target>=0; op->task_open=target<0;
    op->proc_directory=target>=0 ? target : (int)q->data.args[0];
    op->request = *q; op->proc_attempted = 1; op->error = 0;
    t->phase = PROC_CANCEL; tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-proc-query");
}
static void proc_query_start(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->request.id) == -1 && errno == ENOENT);
    struct user_pt_regs regs = op->saved;
    regs.pc = op->task_open ? abi.proc_open : EXPORT; regs.regs[30] = abi.exported_entry;
    regs.regs[0] = op->proc_directory;
    if (op->task_open) regs.regs[1] = op->request.data.args[2];
    if (!set_registers(t->pid, &regs)) return;
    t->phase = PROC_OPENING; resume(t->pid, 0);
}
static void retained_result(struct thread *t, const struct seccomp_notif *q) {
    struct metadata_operation *op = &t->metadata;
    int fd = -1;
    if (!op->error && !(op->proc_attempted && op->proc_stat)) {
        int flags = q->data.nr == SYS_openat ? (int)q->data.args[2] : O_PATH;
        if (q->data.nr == SYS_newfstatat) {
            if (q->data.args[3] & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT)) op->error = -ENOTSUP;
            if (q->data.args[3] & AT_SYMLINK_NOFOLLOW) flags |= O_NOFOLLOW;
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
        if ((fd == -EACCES || fd == -ELOOP) && !op->proc_attempted
                && q->data.nr == SYS_newfstatat && !q->data.args[3]) {
            int target = md_domain_proc_fd_number(op->descriptor, t->pid, op->path);
            if (target >= 0) { proc_query_begin(t, q, target); return; }
        }
        if (fd < 0 && !op->error) op->error = fd;
        if (!op->error && q->data.nr == SYS_newfstatat && fstat(fd, &op->value)) op->error = -errno;
    }
    retained_calls++;
    if (op->error) retained_errors++;
    TRACE("PROBE retained pid=%d nr=%d fd=%llu path=%s result=%d flags=%#llx\n",
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
static int image_identity_prepare(struct thread *t,int fd,struct md_exec_identity *next,int *replacement) {
    int matched=admitted_path ? md_admission_match(&admission,t->endpoint,fd) : 0;
    if (matched<0) return matched;
    *next=(struct md_exec_identity){.value=t->identity};
    next->value.uid.saved=next->value.uid.fs=next->value.uid.effective;
    next->value.gid.saved=next->value.gid.fs=next->value.gid.effective;
    next->secure=next->value.uid.real!=next->value.uid.effective
        || next->value.gid.real!=next->value.gid.effective;
    const struct md_exec_image *setid=matched ? admission.image : t->exec_setid;
    int error=0;
    if (setid) error=md_identity_prepare_exec(&t->identity,setid,
        md_debugger_traced(debugger,t->pid) || (t->exec_mount_flags&MD_MOUNT_NOSUID),next);
    else md_identity_exec(&t->identity,&next->value);
    if (!error && matched) {
        *replacement=md_exec_image_dup(admission.image);
        if (*replacement<0) error=*replacement;
    }
    return error ? error : matched;
}
static int image_enter(struct thread *t,uintptr_t image,uintptr_t address,size_t count,unsigned long machine) {
    if (!t->ready || t->entered || !t->mapped_image || count>128 || machine>1) return -EPERM;
    if (!image || image>UINTPTR_MAX-sizeof(struct md_process_image)) return -EFAULT;
    Elf64_auxv_t aux[128];
    struct iovec local={aux,count*sizeof(*aux)},remote={(void *)address,local.iov_len};
    if (process_vm_readv(t->pid,&local,1,&remote,1,0)!=(ssize_t)local.iov_len) return -EFAULT;
    unsigned found=0;
    const struct md_exec_identity *next=&t->pending_identity;
    for (size_t i=0;i<count;++i) {
        switch (aux[i].a_type) {
        case AT_UID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,0,next->value.uid.real); found|=1; break;
        case AT_EUID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,0,next->value.uid.effective); found|=2; break;
        case AT_GID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,1,next->value.gid.real); found|=4; break;
        case AT_EGID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,1,next->value.gid.effective); found|=8; break;
        case AT_SECURE: aux[i].a_un.a_val=next->secure; found|=16; break;
        }
    }
    if (found!=31) return -EPROTO;
    /* /proc/auxv retains a snapshot, separately from the loader's live auxv. */
    struct iovec values[2]={local,local}, destinations[2]={remote,
        {(void *)(image+offsetof(struct md_process_image,auxv)),local.iov_len}};
    if (process_vm_writev(t->pid,values,2,destinations,2,0)!=(ssize_t)(2*local.iov_len)) return -EFAULT;
    CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&next->value,&t->resources));
    /* The loader has closed its copies, but these exact published images
     * still authenticate the actual mappings. Capture before secure entry
     * lowers dumpability; failure only declines optional SVC rewriting. */
    t->image_backings_ready=machine && t->exec_backings[0]>=0
        && !md_memory_image_backings(t->memory,t->pid,t->exec_backings,t->exec_backings[1]>=0 ? 2 : 1);
    exec_backings_release(t);
    t->identity=next->value; t->mapped_image=0; t->entered=1;
    md_exec_image_release(t->exec_setid); t->exec_setid=NULL;
    TRACE("PROBE entered-image pid=%d uid=%u euid=%u secure=%d\n",t->pid,
        t->identity.uid.real,t->identity.uid.effective,next->secure);
    if (diagnostics) {
        struct md_fs_location root;
        struct md_fs_anchor *cwd=NULL;
        md_domain_paths(t->resources.fs,&root,&cwd);
        TRACE("PROBE image-cwd pid=%d known=%d\n",t->pid,cwd!=NULL);
        md_fs_anchor_release(cwd);
    }
    return next->secure;
}
static void image_request(struct thread *t, const struct seccomp_notif *q) {
    struct seccomp_notif_resp reply = {.id = q->id};
    if (q->data.instruction_pointer != RAW_GATE || t->phase != IDLE) reply.error = -EPERM;
    else if (q->data.args[0] == MD_GUEST_MAP_IMAGE) {
        if (q->data.args[2]>=MD_IMAGE_TAKE_PROGRAM && q->data.args[2]<=MD_IMAGE_TAKE_EXECFD) {
            unsigned index=q->data.args[2]-MD_IMAGE_TAKE_PROGRAM;
            if (t->entered || !t->exec_images_ready || q->data.args[1]
                    || q->data.args[3] || q->data.args[4] || q->data.args[5]
                    || t->exec_images[index]<0) reply.error=-EPERM;
            else {
                struct md_exec_identity next={0};
                int replacement=-1,matched=0;
                if (!index) {
                    matched=image_identity_prepare(t,t->exec_images[index],&next,&replacement);
                    if (matched<0) { reply.error=matched; goto image_reply; }
                }
                struct md_fd_origin_update update={0};
                int error=index==2 ? md_fd_origins_prepare(t->resources.files,t->execfd_origin,0,&update) : 0;
                if (error) { reply.error=error; goto image_reply; }
                struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
                    .srcfd=replacement>=0 ? replacement : t->exec_images[index],.newfd_flags=index==2 ? 0 : O_CLOEXEC};
                int fd=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
                int saved=errno;
                if (index==2) md_fd_origins_finish(&update,fd<0 ? -saved : fd);
                if (fd>=0) {
                    if (index<2) {
                        /* Retain the exact published file until its mappings
                         * are registered; do not reacquire it from /proc/fd. */
                        CHECK(t->exec_backings[index]<0);
                        t->exec_backings[index]=(int)add.srcfd;
                        if (replacement>=0) close(t->exec_images[index]);
                    } else close(t->exec_images[index]);
                    if (!index) {
                        t->pending_identity=next; t->mapped_image=1;
                        if (matched) {
                            ++admitted_images;
                            TRACE("PROBE admitted-image pid=%d fd=%d euid=%u secure=%d\n",
                                t->pid,fd,next.value.uid.effective,next.secure);
                        } else if (t->exec_setid)
                            TRACE("PROBE stored-setid-image pid=%d euid=%u egid=%u secure=%d\n",
                                t->pid,next.value.uid.effective,next.value.gid.effective,next.secure);
                    }
                    t->exec_images[index]=-1; return;
                }
                if (replacement>=0) close(replacement);
                reply.error=-saved;
            }
        } else if (t->entered || q->data.args[2]) reply.error = -EPERM;
        else {
            int fd = admitted_path ? duplicate_fd(t->pid, (int)q->data.args[1]) : -1;
            struct md_exec_identity next;
            int replacement=-1;
            int matched=admitted_path && fd<0 ? fd : image_identity_prepare(t,fd,&next,&replacement);
            if (fd >= 0) close(fd);
            if (matched < 0) reply.error = matched;
            else {
                if (matched) {
                    struct seccomp_notif_addfd add = {.id = q->id, .srcfd = replacement,
                        .newfd = q->data.args[1], .newfd_flags = O_CLOEXEC,
                        .flags = SECCOMP_ADDFD_FLAG_SETFD | SECCOMP_ADDFD_FLAG_SEND};
                    int sent = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
                    int error = errno; close(replacement);
                    if (sent >= 0) {
                        t->pending_identity = next; t->mapped_image = 1; admitted_images++;
                        TRACE("PROBE admitted-image pid=%d fd=%d euid=%u secure=%d\n",
                            t->pid, sent, next.value.uid.effective, next.secure);
                        return;
                    }
                    if (error == ENOENT) return;
                    reply.error = -error;
                }
                if (!reply.error) {
                    t->pending_identity = next; t->mapped_image = 1;
                    if (t->exec_setid) TRACE("PROBE stored-setid-image pid=%d euid=%u egid=%u secure=%d\n",
                        t->pid,next.value.uid.effective,next.value.gid.effective,next.secure);
                }
            }
        }
    } else reply.error = -EPROTO;
image_reply:
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}
static void shm_request(struct thread *t, const struct seccomp_notif *q) {
    struct seccomp_notif_resp reply = {.id=q->id};
    struct md_shm *shm=NULL;
    int r=md_ipc_namespace_memory(t->resources.ipc,&shm);
    if (!r && (t->phase != DISPATCHING || q->data.instruction_pointer != RAW_GATE)) r = -EPERM;
    unsigned op = q->data.args[1];
    if (op==MD_SHM_STAT || op==MD_SHM_OPEN || op==MD_SHM_ATTACHED || op==MD_SHM_ABORT) r=-EINVAL;
    unsigned long a[4] = {q->data.args[2],q->data.args[3],q->data.args[4],q->data.args[5]};
    struct shmid64_ds info;
    long value = r ? r : md_shm_call(shm,t->shm_space,NULL,&t->identity,t->tgid,t->resources.pid_group,op,a,&info);
    if (value < 0) reply.error = (int)value;
    else reply.val = value;
    CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno == ENOENT);
}
static void ipc_request(struct thread *t, const struct seccomp_notif *q) {
    struct seccomp_notif_resp reply={.id=q->id};
    struct md_ipc_request call={.nr=t->original.regs[8]};
    for (unsigned i=0;i<6;i++) call.args[i]=t->original.regs[i];
    int r=t->phase!=DISPATCHING || q->data.instruction_pointer!=RAW_GATE ? -EPERM : 0;
    if (!r && (call.nr!=SYS_semget && call.nr!=SYS_semctl
            && call.nr!=SYS_semop && call.nr!=SYS_semtimedop && call.nr!=SYS_msgget
            && call.nr!=SYS_msgctl && call.nr!=SYS_msgsnd && call.nr!=SYS_msgrcv)) r=-EINVAL;
    struct md_ipc *ipc=NULL;
    if (!r) r=md_ipc_namespace_semaphores(t->resources.ipc,&ipc);
    long value=r;
    struct md_ipc_transport *channel=&t->ipc_transport;
    if (!r && q->data.args[1]==MD_IPC_CLOSE) {
        md_ipc_transport_close(channel);
        if (q->data.args[2]) {
            value=md_ipc_cancel(ipc,&t->ipc_pending); t->ipc_cancel=0;
        }
    } else if (!r && q->data.args[1]==MD_IPC_EXECUTE && channel->packet) {
        if (t->ipc_cancel) {
            value=t->ipc_cancel; t->ipc_cancel=0;
            int error=md_ipc_cancel(ipc,&t->ipc_pending); if (error) value=error;
        } else value=md_ipc_call(ipc,&t->ipc_pending,t->resources.sem_undo,&t->identity,t->pid,t->tgid,t->resources.pid_group,channel);
    } else if (!r) value=-EINVAL;
    if (value==MD_IPC_WAIT) reply.val=value;
    else if (value<0) reply.error=value;
    else reply.val=value;
    CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
}
static void ipc_unwatch(struct thread *t) {
    if (t->ipc_event) {
        CHECK(!epoll_ctl(ipc_events,EPOLL_CTL_DEL,md_ipc_wait_fd(t->ipc_pending),NULL)
            || errno==ENOENT);
        t->ipc_event=0;
    }
}
static int ipc_watch(struct thread *t) {
    if (t->ipc_event || !t->ipc_pending) return -EINVAL;
    if (ipc_sequence==UINT64_MAX) return -EOVERFLOW;
    if (ipc_events<0) {
        ipc_events=epoll_create1(EPOLL_CLOEXEC);
        if (ipc_events<0) return -errno;
    }
    struct epoll_event event={.events=EPOLLIN|EPOLLONESHOT,.data.u64=++ipc_sequence};
    if (epoll_ctl(ipc_events,EPOLL_CTL_ADD,md_ipc_wait_fd(t->ipc_pending),&event)) return -errno;
    t->ipc_event=event.data.u64; return 0;
}
static void ipc_events_ready(void) {
    struct epoll_event events[16];
    int count;
    do count=epoll_wait(ipc_events,events,16,0); while (count<0 && errno==EINTR);
    CHECK(count>=0);
    for (int i=0;i<count;++i) {
        struct thread *t=threads;
        while (t && (!t->pid || t->ipc_event!=events[i].data.u64)) t=t->next;
        if (!t) continue;
        CHECK(t->phase==IPC_WAITING);
        ipc_unwatch(t); t->ipc_waking=1;
        /* EVENT_WAIT: the task's interruptible empty poll is cancelled by an
         * exact watcher event. No guest FD or synthetic application signal. */
        if (!t->listening) tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"ipc-ready");
    }
}
static void delegate_begin(struct thread *t, const struct seccomp_notif *q) {
    CHECK(t->phase == IDLE);
    t->metadata = (struct metadata_operation){.request = *q, .descriptor = -1, .channel = -1};
    t->phase = DELEGATE_CANCEL;
    tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-delegation");
}
static int machine_observe(struct thread *t,const struct user_pt_regs *regs) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!registry || registry->disabled || !t->entered || t->application_filter || t->cooperative_blocked
            || t->debug_options || md_memory_exposed(t->memory) || !md_entry_supported((int)regs->regs[8])
            || regs->pc<4 || regs->pc==RAW_GATE) return 0;
    enum md_entry_route route=md_entry_route((int)regs->regs[8]);
    if (route==MD_ENTRY_TASK) return 0;
    /* This is only a profitability rejection. The guest-side snapshot still
     * proves every execution; a file-backed/shared range cannot use it. */
    if (route==MD_ENTRY_MEMORY && (!md_memory_native_arguments(regs->regs[8],regs->regs[2],regs->regs[3])
            || md_memory_mapping_observe(t->memory,regs->regs[0],regs->regs[1])
            || md_shm_space_used(t->shm_space))) return 0;
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    int published=md_svc_registry_direct(registry,t->pid,access ? access->memory : -1,regs->pc-4,regs->regs[8]);
    if (published) TRACE("PROBE machine-entry pid=%d svc=%#llx nr=%llu publication=%d\n",
        t->pid,regs->pc-4,regs->regs[8],published);
    return published==1 && (route==MD_ENTRY_WORKER || route==MD_ENTRY_FD || route==MD_ENTRY_VALUE
        || route==MD_ENTRY_READINESS);
}
static void notification_release(struct thread *t) {
    /* No operation was admitted. A signal or lazy-entry retry must discard
     * both metadata export and typed descriptor discovery before re-entry. */
    release_operation(t);
    md_proc_file_release(t->proc_file); t->proc_file=NULL;
}
static void notification_interrupted(struct thread *t) {
    if (t->phase!=DELEGATE_CANCEL
            && !(t->phase==EXPORT_CANCEL && t->metadata.previous==IDLE)) return;
    /* A delivery stop can precede the requested INTERRUPT. Nothing has run:
     * retire the request before the handler can replace its register context.
     * The kernel retains EINTR/SA_RESTART and the pending interrupt is harmless
     * in IDLE. Never execute the cancelled call in a signal handler's frame. */
    CHECK(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&t->metadata.request.id)==-1 && errno==ENOENT);
    notification_release(t);
    t->phase=IDLE;
    if (t->metadata_restart) t->metadata_restart->stage=METADATA_ENTRY;
    TRACE("PROBE notification interrupted pid=%d\n",t->pid);
}
static int cooperative_copying(struct thread *t) {
    struct md_buffer_span span;
    if (t->phase!=IDLE || !t->cooperative_buffer.pool
            || md_buffer_span(&t->cooperative_buffer,&span) || span.size<sizeof(struct md_entry_channel)) return 0;
    const struct md_entry_channel *c=span.data;
    return c->owner==t->pid && atomic_load_explicit(&c->copying,memory_order_acquire);
}
static void cooperative_buffer_release(struct thread *t) {
    if (t->cooperative_buffer.pool) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->cooperative_buffer,&span) && span.size>=sizeof(struct md_entry_channel));
        struct md_entry_channel *c=span.data;
        md_memory_admission_detach(t->memory,&c->memory);
        CHECK(!md_buffer_release(&t->cooperative_buffer));
    }
}
static void cooperative_memory_publish(struct thread *t,int eligible) {
    if (!t->cooperative_buffer.pool) return;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->cooperative_buffer,&span) && span.size>=sizeof(struct md_entry_channel));
    struct md_entry_channel *c=span.data;
    md_memory_admission_publish(t->memory,&c->memory,eligible && !md_shm_space_used(t->shm_space));
}
static void buffer_setup(struct thread *t, const struct seccomp_notif *q) {
    size_t page=(size_t)getpagesize();
    size_t size=(sizeof(union md_adapter_frame)+page-1)/page*page
        *(MD_ADAPTER_BUFFER_SLOTS+MD_ADAPTER_CONTROL_SLOTS+MD_ADAPTER_CHANNEL_SLOTS);
    struct seccomp_notif_resp reply={.id=q->id};
    if (!t->ready || t->entered || t->phase!=IDLE
            || q->data.instruction_pointer!=RAW_GATE || q->data.args[1]!=size
            || q->data.args[2] || q->data.args[3] || q->data.args[4] || q->data.args[5]) reply.error=-EPERM;
    else reply.error=md_memory_buffers_prepare(t->memory,MD_ADAPTER_BUFFER_SLOTS+MD_ADAPTER_CHANNEL_SLOTS,sizeof(union md_adapter_frame),
        offsetof(struct md_fs_buffer_frame,value),MD_ADAPTER_CONTROL_SLOTS);
    if (!reply.error) reply.error=md_buffers_partition_channels(md_memory_buffers(t->memory),MD_ADAPTER_CHANNEL_SLOTS);
    if (!reply.error) {
        struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
            .srcfd=md_buffers_fd(md_memory_buffers(t->memory)),.newfd_flags=O_CLOEXEC};
        if (ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add)>=0) return;
        reply.error=-errno;
    }
    CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
}
static void proc_deliver(struct thread *t) {
    retry_clear(t,RETRY_PROC_DELIVERY);
    if (!t->proc_delivery.files.table) return;
    int installed=md_fd_install_progress(&t->proc_delivery);
    if (installed==MD_FD_INSTALL_WAIT) { retry_wait(t,RETRY_PROC_DELIVERY); return; }
    CHECK(!t->proc_delivery.retire_error);
    close(t->proc_image_fd); t->proc_image_fd=-1;
    if (installed==MD_FD_INSTALL_CANCELLED) return;
    if (installed<0) {
        struct seccomp_notif_resp reply={.id=t->proc_delivery.notification,.error=installed};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
}
static void proc_snapshot_complete(struct thread *t,struct md_fs_buffer_request *request,int error) {
    struct seccomp_notif *q=&t->proc_snapshot_notification;
    struct md_fs_buffer_open opened={.fd=-1};
    t->proc_snapshot=NULL;
    if (!error && md_fs_buffer_open_take(request,&opened)!=1) error=-EIO;
    if (!error) {
        struct md_fd_object *pin=md_fd_native_pin_new(opened.fd);
        error=pin ? md_fd_install_begin(&t->proc_delivery,t->resources.files,listener,q->id,
            opened.fd,pin,(unsigned)q->data.args[3]&O_CLOEXEC,fd_wakeup) : -errno;
        md_fd_object_release(pin);
        if (!error) {
            t->proc_image_fd=opened.fd; opened.fd=-1;
            proc_deliver(t);
        }
    }
    md_fs_buffer_open_release(&opened);
    if (error) {
        struct seccomp_notif_resp reply={.id=q->id,.error=error};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
}
static struct md_proc_file *proc_file_capture(struct thread *t, int fd) {
    struct md_fd_object *object=md_fd_origins_object(t->resources.files,fd,NULL);
    struct md_proc_file *file=md_proc_file_from_object(object);
    if (!file) md_fd_object_release(object);
    return file;
}
struct proc_owner {
    pid_t pid;
    const struct md_identity *identity;
    const struct md_task_resources *resources;
    struct md_pid_namespace *pid_namespace;
    struct md_cgroup_member *cgroup;
    struct md_process_member *process;
};
static struct proc_owner proc_owner(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (t && t->born) return (struct proc_owner){pid,&t->identity,&t->resources,
        md_pid_identity_namespace(t->resources.pid),t->resources.cgroup,t->resources.process};
    struct md_task_retired_view retired=md_task_registry_retired(tasks,pid);
    return (struct proc_owner){pid,retired.identity,NULL,md_pid_identity_namespace(retired.pid),retired.cgroup,retired.process};
}
static uid_t process_signal_uid(struct thread *observer,pid_t sender,uid_t fallback) {
    const struct md_identity *identity=proc_owner(sender).identity;
    return md_identity_visible_id(&observer->identity,0,identity ? identity->uid.real : fallback);
}
static int proc_node_stat(struct proc_owner target, enum md_proc_file_kind kind,
        struct md_proc_file_node *node, struct stat *value) {
    char path[64];
    if (md_proc_file_directory(kind)) {
        snprintf(path,sizeof(path),"/proc/%d%s",target.pid,kind==MD_PROC_FILE_NS_DIRECTORY ? "/ns" : "");
        if (stat(path,value)) return -errno;
        *node=(struct md_proc_file_node){value->st_dev,value->st_ino,target.pid};
        value->st_uid=target.identity->uid.effective; value->st_gid=target.identity->gid.effective;
        return 0;
    }
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
    return md_proc_file_task_stat(target.pid,target.identity,kind,node,value);
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
    if (md_proc_static(kind)) return md_proc_static_new(kind,t->identity.user_namespace,NULL);
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
        if (!target.identity) return q->kind==MD_PROC_PROCESS_DIRECTORY
            || q->kind==MD_PROC_STATUS || q->kind==MD_PROC_STAT || q->kind==MD_PROC_LIMITS ? -EXDEV : -ENOENT;
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
    else if (namespace && target.pid!=t->pid && !proc_allowed(t->pid,target.pid)) error=-EACCES;
    else if (sysctl || target.identity) error=proc_node_stat(target,kind,&current,value);
    if (!namespace && !sysctl && file && (!target.identity || error==-ENOENT || current.inode!=node.inode || current.device!=node.device)) {
        *value=(struct stat){.st_dev=node.device,
            .st_ino=md_proc_file_directory(kind) || md_proc_file_process(kind) ? node.inode
                : (node.inode<<2)|((unsigned)kind+1)|UINT64_C(0x8000000000000000),
            .st_mode=md_proc_file_directory(kind) ? S_IFDIR|0500 : S_IFREG|(md_proc_file_process(kind) ? 0444 : 0644),
            .st_nlink=1,.st_blksize=getpagesize()};
        error=0;
    }
    if (!error && namespace) {
        if (q->nofollow || (file && (md_proc_file_flags(file)&(O_NOFOLLOW|O_PATH))==(O_NOFOLLOW|O_PATH)))
            value->st_mode=S_IFLNK|0777;
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
/* Returns zero after accepting asynchronous publication. The caller retains
 * its proc input independently; delivery owns the new result's annotation. */
static int proc_file_publish(struct thread *t, const struct seccomp_notif *q,
        struct md_proc_file *file) {
    if (t->proc_image_fd>=0) return -EBUSY;
    int fd=md_proc_file_descriptor(file);
    if (fd<0) { TRACE("PROBE proc-carrier pid=%d error=%d\n",t->pid,fd); return fd; }
    int native_flags=fcntl(fd,F_GETFL);
    if (native_flags<0) { int error=-errno; close(fd); return error; }
    if (native_flags&O_PATH) {
        if (t->proc_file) { close(fd); return -EBUSY; }
        t->proc_file=md_proc_file_retain(file);
        t->proc_image_fd=fd;
        struct seccomp_notif_resp reply={.id=q->id,.val=MD_PROC_OPEN_RIGHTS};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        return 0;
    }
    int error=md_fd_install_begin(&t->proc_delivery,t->resources.files,listener,q->id,
        fd,md_proc_file_object(file),(unsigned)md_proc_file_flags(file)&O_CLOEXEC,fd_wakeup);
    if (error) {
        TRACE("PROBE proc-publication pid=%d source=%d listener=%d flags=%x error=%d\n",
            t->pid,fd,listener,md_proc_file_flags(file),error);
        close(fd);
    }
    else {
        t->proc_image_fd=fd;
        /* EVENT_WAIT: exact destination table publication or task cancellation
         * releases the retained carrier; no guest scratch descriptor exists. */
        proc_deliver(t);
    }
    return error;
}
static int control_file_query(struct md_fd_object *object,unsigned operation,struct md_control_file *out) {
    struct md_proc_file *file=md_proc_file_from_object(object);
    if (!file) return 0;
    *out=md_proc_file_control(file);
    if (operation==MD_FS_MOUNT || operation==MD_FS_OPEN_TREE) {
        if (md_proc_file_filesystem_paths(file)) { *out=(struct md_control_file){0}; return 0; }
        if (!out->ops || !out->ops->descriptor) return -EOPNOTSUPP;
    } else if (operation!=MD_FS_FDINFO && operation!=MD_FS_FSTAT && (md_proc_file_flags(file)&O_PATH)) return -EBADF;
    return out->ops ? 1 : -EINVAL;
}
static int control_file_capture(pid_t actor,const char *path,struct md_fd_object **out) {
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
        if (md_proc_file_filesystem_paths(file)) { md_proc_file_release(file); return 0; }
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
static int executable_capture(pid_t actor,struct md_program *image) {
    struct thread *t=find_thread(actor);
    if (!t) return -ESRCH;
    struct md_proc_path selected=md_proc_path_for(image->identity.path,t->pid,t->tgid);
    TRACE("PROBE exec-proc capture actor=%d path=%s target=%d\n",actor,image->identity.path,selected.process);
    enum md_proc_kind kind=selected.kind==MD_PROC_FOREIGN ? selected.foreign_kind : selected.kind;
    if (kind!=MD_PROC_EXE || *selected.tail || image->fd>=0) return -EINVAL;
    struct thread *target=find_thread(selected.process);
    if (!target || !target->born) return -ENOENT;
    if (target!=t && !proc_allowed(t->pid,target->pid)) return -EACCES;
    int error=md_process_image_identity(target->pid,target->process_image,process_image_memory(t,target),&image->identity);
    if (error) return error;
    if (target->executable_origin) {
        /* A proc executable retains the selected mount, including a detached
         * readonly bind. The loader's carrier does not own that authority. */
        int fd=fcntl(md_fs_anchor_fd(target->executable_origin),F_DUPFD_CLOEXEC,0);
        if (fd<0) return -errno;
        image->fd=fd;
        image->origin=md_fs_anchor_retain(target->executable_origin);
    } else if (target->executable) {
        struct md_control_file view=md_process_executable_view(target->executable);
        int fd=view.ops->descriptor(view.context,O_RDONLY|O_CLOEXEC);
        if (fd<0) return fd;
        image->fd=fd;
    } else if (!image->identity.object[0]) return -ENOENT;
    return 0;
}
static int proc_path_capture(pid_t actor,pid_t process,unsigned kind,struct md_task_snapshot *snapshot) {
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
static void proc_file_request(struct thread *t, const struct seccomp_notif *q) {
    struct seccomp_notif_resp reply={.id=q->id};
    unsigned operation=(unsigned)q->data.args[1];
    if (q->data.instruction_pointer!=RAW_GATE || t->phase!=DISPATCHING
            || (q->data.args[5] && operation!=MD_PROC_FILE_OPEN)) reply.error=-EPERM;
    else if (operation==MD_PROC_FILE_PIDFD_NAMESPACE) {
        if (!t->proc_file || t->original.regs[8]!=SYS_ioctl
                || t->original.regs[0]!=q->data.args[2] || t->original.regs[1]!=q->data.args[3]
                || !md_pidfd_namespace_command((unsigned)q->data.args[3]) || q->data.args[4]) reply.error=-EPERM;
        else reply.error=proc_file_publish(t,q,t->proc_file);
        if (!reply.error) return;
    } else if (operation==MD_PROC_FILE_NAMESPACE_QUERY) {
        const struct md_namespace_reference *ref=md_proc_file_namespace_reference(t->proc_file);
        unsigned command=(unsigned)q->data.args[3];
        if (!ref || t->original.regs[8]!=SYS_ioctl || t->original.regs[0]!=q->data.args[2]
                || t->original.regs[1]!=command || q->data.args[4]) reply.error=-EPERM;
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
            if (!reply.error) reply.error=proc_file_publish(t,q,file);
            md_proc_file_release(file);
            if (!reply.error) return;
        }
        else reply.error=-ENOTTY;
    } else if (operation==MD_PROC_FILE_OPEN && (md_proc_network_control(q->data.args[3])
            || q->data.args[3]==MD_PROC_MAX_USER_NAMESPACES || md_proc_static(q->data.args[3]))) {
        int flags=(int)q->data.args[4];
        char interface[16]={0};
        if (md_proc_network_interface(q->data.args[3])) {
            struct md_buffer_span span;
            reply.error=md_buffer_span(&t->buffer,&span);
            if (!reply.error) {
                memcpy(interface,span.data,sizeof(interface));
                if (!memchr(interface,0,sizeof(interface))) reply.error=-EINVAL;
            }
        }
        struct md_control_file view={0};
        if (!reply.error) view=proc_control_view(t,(enum md_proc_kind)q->data.args[3],
            (pid_t)q->data.args[2],interface);
        struct md_proc_file *file=NULL;
        if (!view.ops) { if (!reply.error) reply.error=-errno; }
        else {
            file=md_proc_file_open_control(view.ops,view.context,
                &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},flags);
            if (!file) reply.error=-errno;
            else reply.error=proc_file_publish(t,q,file);
        }
        md_control_file_release(view); md_proc_file_release(file);
        if (!reply.error) return;
    } else if (operation==MD_PROC_FILE_OPEN && q->data.args[3]==MD_PROC_EXE) {
        struct thread *target=find_thread((pid_t)q->data.args[2]);
        int flags=(int)q->data.args[4];
        struct md_proc_file *file=NULL;
        if (!target || (target!=t && !proc_allowed(t->pid,target->pid))) reply.error=-EACCES;
        else if (t->proc_image_fd>=0 || q->data.args[5]) reply.error=-EINVAL;
        else if (target->executable_origin || !target->executable) reply.error=-EXDEV;
        else {
            struct md_control_file view=md_process_executable_view(target->executable);
            file=md_proc_file_open_control(view.ops,view.context,
                &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},flags);
            if (!file) reply.error=-errno;
            else {
                reply.error=proc_file_publish(t,q,file);
                if (!reply.error) { md_proc_file_release(file); return; }
            }
        }
        md_proc_file_release(file);
    } else if (operation==MD_PROC_FILE_OPEN || operation==MD_PROC_FILE_REOPEN) {
        struct thread *target=find_thread((pid_t)q->data.args[2]);
        struct proc_owner selected=proc_owner((pid_t)q->data.args[2]);
        enum md_proc_kind kind=(enum md_proc_kind)q->data.args[3];
        int flags=(int)q->data.args[4];
        struct md_proc_file *source=operation==MD_PROC_FILE_REOPEN && target
            ? proc_file_capture(target,(int)q->data.args[3]) : NULL;
        if (source) kind=(enum md_proc_kind)(MD_PROC_UID_MAP+md_proc_file_kind(source));
        if (!selected.identity) reply.error=operation==MD_PROC_FILE_OPEN && (kind==MD_PROC_PROCESS_DIRECTORY
            || kind==MD_PROC_STATUS || kind==MD_PROC_STAT || kind==MD_PROC_LIMITS) ? -EXDEV : -ENOENT;
        else if (operation==MD_PROC_FILE_REOPEN && !source) reply.error=-EBADF;
        else if (!source && kind==MD_PROC_NS_UNSUPPORTED) reply.error=-ENOENT;
        else if ((!source && !md_proc_control_kind(kind)) || t->proc_image_fd>=0) reply.error=-EINVAL;
        else if (source && target!=t && !proc_allowed(t->pid,target->pid)) reply.error=-EACCES;
        else if (md_proc_file_filesystem_paths(source)) reply.error=-EXDEV;
        else {
            struct md_proc_file_node node={0}; struct stat st;
            struct md_proc_file_node previous=source ? md_proc_file_get_node(source) : node;
            enum md_proc_file_kind file_kind=(enum md_proc_file_kind)(kind-MD_PROC_UID_MAP);
            struct md_pid_namespace *view=q->data.args[5] ? md_pid_namespace_by_inode(q->data.args[5]) : NULL;
            int sysctl=md_proc_file_sysctl(file_kind);
            int namespace=md_proc_file_namespace(file_kind);
            int control=source && file_kind==MD_PROC_FILE_CONTROL;
            struct proc_owner owner=sysctl ? proc_owner(t->pid) : source ? proc_owner(previous.process) : selected;
            int error=control ? md_proc_file_control_stat(source,&st) : namespace && source ? 0 : !owner.identity ? -ESRCH
                : proc_node_stat(owner,file_kind,&node,&st);
            if (!error && q->data.args[5] && !view && q->data.args[5]!=md_pid_namespace_inode(NULL)) error=-ENOENT;
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
            TRACE("PROBE proc-control open pid=%d target=%d kind=%u flags=%x provider=%d\n",
                t->pid,selected.pid,(unsigned)kind,flags,fd);
            if (fd<0) reply.error=fd;
            else {
                reply.error=proc_file_publish(t,q,file);
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
    TRACE("PROBE proc-control request pid=%d operation=%u result=%lld error=%d\n",
        t->pid,operation,(long long)reply.val,reply.error);
    CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
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
static long cooperative_bind(struct thread *t) {
    if (!buffer_service || !broker || !t->entered || t->phase!=IDLE
            || t->application_filter || t->cooperative_blocked) return -EOPNOTSUPP;
    if (t->cooperative_buffer.pool)
        return (long)md_memory_buffer_address(t->memory,&t->cooperative_buffer);
    int error=md_buffers_take_channel(md_memory_buffers(t->memory),&t->cooperative_buffer);
    if (error) return error;
    struct md_buffer_span span;
    error=md_buffer_span(&t->cooperative_buffer,&span);
    if (!error && span.size<sizeof(struct md_entry_channel)) error=-ENOBUFS;
    if (!error) error=md_broker_cooperative_bind(broker,t->pid,span.data,abi.buffer_admit);
    if (error) {
        cooperative_buffer_release(t);
        return error;
    }
    return (long)md_memory_buffer_address(t->memory,&t->cooperative_buffer);
}
static unsigned readiness_admission_purpose(const struct thread *t,const struct seccomp_data *call) {
    const struct readiness_owner *o=t->readiness;
    if (!o || o->done || call->nr!=(long)t->original.regs[8]) return 0;
    for (unsigned i=0;i<6;++i) if (call->args[i]!=t->original.regs[i]) return 0;
    if (o->retirement && o->loan_number<0) return MD_FS_BUFFER_EPOLL_LOAN;
    if (!o->received && md_fd_order_state(&o->files)==MD_RESOURCE_ORDER_RUNNING)
        return MD_FS_BUFFER_EPOLL_ADMIT;
    return 0;
}
static void handle_notification(const struct seccomp_notif *request) {
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
    int replay=t->ready && q.data.instruction_pointer==abi.buffer_admit
        && (q.data.nr==SYS_epoll_pwait || q.data.nr==SYS_epoll_pwait2);
    CHECK(md_broker_filesystem_call(q.data.nr) || q.data.nr==SYS_prctl || q.data.nr==SYS_gettid || replay);
    unsigned purpose=replay ? readiness_admission_purpose(t,&q.data) : q.data.args[0];
    int buffer_admission=replay || (q.data.nr==SYS_gettid && t->ready
        && q.data.instruction_pointer==abi.buffer_admit
        && !q.data.args[1] && !q.data.args[2] && !q.data.args[3]
        && !q.data.args[4] && !q.data.args[5]);
    if (buffer_admission) {
        t->buffer_admission_id=q.id; t->buffer_admission_pending=1;
        if (buffer_admission_quiescing(t->pid)) { buffer_admission_yield(t); return; }
    }
    if (mapping_notification(t,&q)) return;
    if (t->proc_snapshot) {
        if (md_notification_same_call(&t->proc_snapshot_notification.data,&q.data)) {
            CHECK(!md_notification_rebind(listener,&t->proc_snapshot_notification,&q));
            return;
        }
        md_fs_buffer_cancel(&t->proc_snapshot);
    }
    if (t->phase==DELEGATE_CANCEL || t->phase==EXPORT_CANCEL || t->phase==PROC_CANCEL) {
        /* A process-directed signal can cancel USER_NOTIF yet be consumed by
         * a peer. The kernel restarts this SVC without a delivery stop here.
         * The pending INTERRUPT still owns admission; replace only its token,
         * never start another export or replay a filesystem operation. */
        struct seccomp_notif previous=t->metadata.request,next=q;
        previous.data=notification_identity(t,&previous.data);
        next.data=notification_identity(t,&next.data);
        CHECK(!md_notification_rebind(listener,&previous,&next));
        t->metadata.request=q;
        if (statistics) ++statistics->notification_rebounds;
        TRACE("PROBE notification rebound pid=%d phase=%d nr=%d\n",t->pid,t->phase,q.data.nr);
        return;
    }
    if (t->broker_completion) {
        const struct seccomp_data *call=&t->broker_completion->call;
        CHECK(t->phase==IDLE && notification_same_call(t,&q.data,call));
        /* Notification waits can restart without a delivery stop in this
         * thread (another peer may consume a process-directed signal). The
         * retained result, not this replay, still owns the completed open. */
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"completed-restarted-notification");
        return;
    }
    if (!memory_available(t->pid) || (entry_fence_adapt(t) && md_broker_filesystem_call(q.data.nr)
            && !(q.data.instruction_pointer>=checkpoints.begin && q.data.instruction_pointer<checkpoints.end))) {
        if (!t->memory_notification) { t->memory_notification=malloc(sizeof(*request)); CHECK(t->memory_notification); }
        else CHECK(t->memory_notification->id==request->id
            || (ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&t->memory_notification->id)<0 && errno==ENOENT));
        *t->memory_notification=*request; return;
    }
    /* A restarted notification can precede the cancelled carrier's queue
     * wakeup. No guest FD was published; release that exact old admission. */
    if (t->phase==IDLE && md_svc_registry_direct_at(md_memory_svc(t->memory),
            t->pid,q.data.instruction_pointer,0)) {
        delegate_begin(t,&q); return;
    }
    if (t->proc_delivery.files.table && t->proc_delivery.notification!=q.id) proc_deliver(t);
    if (q.data.nr==SYS_gettid || replay) {
        int value=-EPERM;
        if (buffer_admission) {
            if (purpose==MD_ENTRY_BIND) {
                long address=cooperative_bind(t);
                struct seccomp_notif_resp reply={.id=q.id,
                    .val=address<0 ? 0 : address,.error=address<0 ? (int)address : 0};
                CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
                return;
            } else if (purpose==MD_FS_BUFFER_IMPORT)
                value=md_fd_return_install(&t->buffer_return,listener,q.id);
            else if (purpose==MD_FS_BUFFER_EXPORT)
                value=md_fs_buffer_export_install(t->buffer_call,listener,q.id);
            else if (purpose==MD_FS_BUFFER_IPC_ADMIT) {
                struct md_fd_capture *capture=NULL;
                struct md_fd_order *order=NULL;
                if (t->outgoing && t->outgoing->brokered && t->outgoing->stage==OUTGOING_CAPTURE) {
                    capture=&t->outgoing->capture; order=&t->outgoing->files;
                } else if (t->receive && t->receive->brokered && !t->receive->ready) {
                    capture=&t->receive->capture; order=&t->receive->files;
                }
                if (order && md_fd_order_state(order)==MD_RESOURCE_ORDER_RUNNING)
                    value=md_fd_capture_admit(capture,order->table,listener,q.id);
            } else if (purpose==MD_BUFFER_PIDFD_ADMIT && t->pidfd_capture.count
                    && t->fd_job==MD_FD_USE && md_fd_order_state(&t->fd_order)==MD_RESOURCE_ORDER_RUNNING)
                value=md_fd_capture_admit(&t->pidfd_capture,t->resources.files,listener,q.id);
            else if (purpose==MD_FS_BUFFER_EPOLL_ADMIT && t->readiness
                    && md_fd_order_state(&t->readiness->files)==MD_RESOURCE_ORDER_RUNNING) {
                value=md_fd_capture_admit(&t->readiness->capture,t->readiness->files.table,listener,q.id);
            } else if (purpose==MD_FS_BUFFER_EPOLL_LOAN && t->readiness
                    && t->readiness->retirement && t->readiness->loan_number<0) {
                struct readiness_owner *o=t->readiness;
                value=md_fd_install_begin(&o->delivery,t->resources.files,listener,q.id,
                    o->received->fds[0],o->queue,O_CLOEXEC,fd_wakeup);
                if (!value) value=md_fd_install_loan(&o->delivery,o->retirement->loan);
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
        if (q.data.args[0] == MD_GUEST_PROC_FILE) proc_file_request(t,&q);
        else if (q.data.args[0] == MD_GUEST_PROC_IMAGE) {
            struct seccomp_notif_resp reply={.id=q.id};
            struct thread *target=find_thread((pid_t)q.data.args[1]);
            const struct md_private_memory_access *access=target ? md_memory_private_access(target->memory) : NULL;
            /* Cmdline is public, unlike auxv/exe: differing guest IDs or
             * permitted capabilities do not require ptrace authority. The
             * namespace lookup and caller's native proc open precede this. */
            int allowed=!md_domain_restricted(t->resources.fs)
                && (q.data.args[2]==MD_PROC_CMDLINE || (target && proc_allowed(t->pid,target->pid)));
            int error=!allowed ? -EACCES
                : q.data.instruction_pointer!=RAW_GATE || t->phase!=DISPATCHING
                    || t->proc_image_fd>=0 || q.data.args[4] || q.data.args[5] ? -EPERM
                : md_file_snapshot_flags((unsigned)q.data.args[3]);
            void *bytes=NULL;
            ssize_t size=error ? error : md_process_image_snapshot((pid_t)q.data.args[1],target ? target->process_image : 0,
                access ? access->memory : -1,(unsigned)q.data.args[2],&bytes);
            if (size<0) error=(int)size;
            else error=md_fs_buffer_snapshot_copy(buffer_service,t->pid,t->resources.fs,
                (unsigned)q.data.args[3],bytes,(size_t)size,&t->proc_snapshot);
            free(bytes);
            if (!error) {
                t->proc_snapshot_notification=q;
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
        else if (q.data.args[0] == MD_GUEST_SHM) shm_request(t,&q);
        else if (q.data.args[0] == MD_GUEST_IPC) ipc_request(t,&q);
        else image_request(t, &q);
        return;
    }
    if (q.data.nr==SYS_getdents64 && t->phase==IDLE && q.data.instruction_pointer!=RAW_GATE) {
        struct md_proc_file *file=proc_file_capture(t,(int)q.data.args[0]);
        if (file) { CHECK(!t->proc_file); t->proc_file=file; delegate_begin(t,&q); return; }
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
        if (t->phase != IDLE) TRACE("PROBE unexpected notification pid=%d phase=%d nr=%d pc=%#llx fd=%llu\n",
            t->pid, t->phase, q.data.nr, q.data.instruction_pointer, q.data.args[0]);
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
        if (file) { CHECK(!t->proc_file); t->proc_file=file; delegate_begin(t,&q); return; }
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
        TRACE("PROBE external fstat pid=%u fd=%llu result=%d\n", q.pid, q.data.args[0], error);
    struct seccomp_notif_resp reply = {.id = q.id, .error = error};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}
static void delegated_notification(const struct seccomp_notif *request) {
    handle_notification(request);
}
static void completed_notification(pid_t pid,struct md_broker_completion *result) {
    struct thread *t=find_thread(pid);
    CHECK(t && !t->broker_completion);
    t->broker_completion=malloc(sizeof(*result)); CHECK(t->broker_completion);
    *t->broker_completion=*result;
    t->broker_completion->call=notification_identity(t,&result->call);
    TRACE("PROBE completed-notification pid=%d nr=%d result=%d fd=%d live=%d\n",pid,result->call.nr,
        result->result.error,result->opened.fd,result->notification_live);
    result->opened=(struct md_fs_buffer_open){.fd=-1};
    if (t->broker_completion->notification_live) {
        /* A provider may require selectors, caller opening or FIFO completion.
         * Interrupt only the still-live notification. Cancellation returns via
         * a kernel event or a restarted notification retaining this result. */
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"completed-notification");
    }
}
static int completed_event_resume(struct thread *t) {
    if (!t->broker_event.held) return 0;
    if (t->metadata_restart) {
        if (!signal_mask(t,t->metadata_restart->mask,"metadata-restart-mask")) return 1;
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
static void metadata_restart_return(struct thread *t,long value) {
    struct metadata_restart *r=t->metadata_restart;
    CHECK(r && r->stage==METADATA_WAIT);
    /* arm64 exposes a temporary entry/exit marker in x7 at syscall stops.
     * Only x0 is the result; retain the original registers, NZCV and args. */
    r->saved.pc+=4; r->saved.regs[0]=(unsigned long)value;
    r->stage=METADATA_RETURN;
    struct user_pt_regs regs=r->saved; regs.pc=abi.done_entry;
    /* A newer filter can queue SIGSYS. Keep it pending until the original
     * register context is restored at the owned return trap. */
    uint64_t mask=md_signal_protocol_mask();
    if (!signal_mask(t,mask,"metadata-return-mask")
            || !set_registers(t->pid,&regs)) return;
    t->phase=IDLE; resume(t->pid,0);
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
static int interrupted_metadata_event(struct thread *t,int status,const struct rusage *usage) {
    if (t->metadata_signal_restored) { t->metadata_signal_restored=0; return 0; }
    if (WIFSTOPPED(status) && !((unsigned)status>>16) && WSTOPSIG(status)!=(SIGTRAP|0x80))
        notification_interrupted(t);
    if (t->phase!=IDLE || t->broker_event.held || !WIFSTOPPED(status)
            || !t->ready || !t->entered || (unsigned)status>>16 || WSTOPSIG(status)==(SIGTRAP|0x80)
            || WSTOPSIG(status)==SIGSTOP) return 0;
    siginfo_t info;
    if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"metadata-signal")) return 1;
    if (info.si_code>0 && (info.si_signo==SIGTRAP || info.si_signo==SIGSYS
            || info.si_signo==SIGSEGV || info.si_signo==SIGBUS || info.si_signo==SIGILL || info.si_signo==SIGFPE)) return 0;
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    if (!md_broker_metadata_call((int)regs.regs[8])) return 0;
    struct md_svc_site *site=md_svc_registry_direct_at(md_memory_svc(t->memory),t->pid,regs.pc,regs.sp);
    if (site && regs.pc!=site->kernel_alias) return 0;
    /* A notified read can also originate at a resident native alias. Its
     * cancellation must retain the same metadata replay as the original SVC;
     * it is not an application's interruptible native wait. */
    if (site) regs.pc=site->direct.svc;
    uint32_t instruction=UINT32_C(0xd4000001);
    struct iovec local={&instruction,sizeof(instruction)},remote={(void *)regs.pc,sizeof(instruction)};
    ssize_t copied=site ? sizeof(instruction) : process_vm_readv(t->pid,&local,1,&remote,1,0);
    if (copied==sizeof(instruction) && !metadata_instruction(t,regs.pc,instruction)) return 0;
    /* arm64 rewinds the SVC before the signal-delivery stop. Reception is not
     * proof of admission: cancellation can precede NOTIF_RECV altogether.
     * Verify the instruction (or our exact replacement), then replay with its
     * original identity. Newer application filters remain authoritative. */
    struct metadata_restart *restart=calloc(1,sizeof(*restart)); CHECK(restart);
    restart->saved=regs; restart->stage=copied==sizeof(instruction) ? METADATA_ENTRY : METADATA_CHECK;
    t->metadata_restart=restart;
    t->kernel_stopped=1; t->interrupt_pending=0;
    t->broker_event.held=1; t->broker_event.status=status;
    t->broker_event.usage=usage ? *usage : (struct rusage){0};
    t->broker_event.has_info=1; t->broker_event.info=info;
    if (!save_signal_mask(t,&restart->mask,"metadata-signal-mask")
            || !shield(t)) return 1;
    t->retained_signal=md_signal_delivery_retain(&t->signal_delivery,t->pid,&info);
    CHECK(t->retained_signal>=0);
    if (restart->stage==METADATA_CHECK) {
        regs.regs[0]=regs.pc; regs.regs[1]=1; regs.pc=abi.load_groups;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
    } else metadata_restart_enter(t);
    return 1;
}
static int metadata_restart_event(struct thread *t,int status) {
    struct metadata_restart *r=t->metadata_restart;
    if (!r || !WIFSTOPPED(status)
            || ((unsigned)status>>16 && (unsigned)status>>16!=MD_TASK_CHECKPOINT_EVENT)) return 0;
    int sig=WSTOPSIG(status);
    if (!((unsigned)status>>16) && sig!=(SIGTRAP|0x80) && sig!=SIGSTOP) {
        siginfo_t info;
        if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"metadata-deferred-signal")) return 1;
        if (info.si_code<=0) {
            int retained=md_signal_delivery_retain(&t->signal_delivery,t->pid,&info); CHECK(retained>=0);
            notification_interrupted(t);
            resume(t->pid,retained); return 1;
        }
    }
    if (t->phase!=IDLE) return 0;
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    t->kernel_stopped=1; t->interrupt_pending=0;
    if (r->stage==METADATA_CHECK) {
        int loaded=(unsigned)status>>16==MD_TASK_CHECKPOINT_EVENT && regs.pc==abi.loaded_groups+4;
        int fault=(sig==SIGSEGV || sig==SIGBUS) && regs.pc>=abi.load_groups && regs.pc<abi.loaded_groups;
        if (!loaded && !fault) return 0;
        if (!set_registers(t->pid,&r->saved)) return 1;
        if (fault || !metadata_instruction(t,r->saved.pc,(unsigned)regs.regs[3])) return completed_event_resume(t);
        TRACE("PROBE interrupted-metadata pid=%d nr=%llu\n",t->pid,r->saved.regs[8]);
        metadata_restart_enter(t); return 1;
    }
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
    if (r->stage==METADATA_RETURN && (unsigned)status>>16==MD_TASK_CHECKPOINT_EVENT && regs.pc==DONE+4) {
        if (!set_registers(t->pid,&r->saved)) return 1;
        return completed_event_resume(t);
    }
    return 0;
}
static int completed_notification_event(struct thread *t,int status,const struct rusage *usage) {
    struct md_broker_completion *result=t->broker_completion;
    if (!result) return 0;
    TRACE("PROBE completed-event pid=%d nr=%d status=%#x\n",t->pid,result->call.nr,status);
    unsigned event=(unsigned)status>>16;
    if (WIFSTOPPED(status) && event!=PTRACE_EVENT_EXIT && event!=PTRACE_EVENT_EXEC) {
        int signal=WSTOPSIG(status);
        if (!event && signal==SIGSTOP) return 0;
        /* A group stop retains the result until SIGCONT; its LISTEN boundary
         * cannot be replaced by an internal adapter trap. */
        if (event==PTRACE_EVENT_STOP && signal!=SIGTRAP) return 0;
        struct user_pt_regs regs;
        if (!registers(t->pid,&regs)) return 1;
        if (t->phase==DELEGATE_CANCEL || (t->phase==EXPORT_CANCEL && t->metadata.previous==IDLE)) {
            /* A restarted notification may request admission before the
             * original worker publishes its retained result. Its pending stop
             * has executed nothing; retire only that exact duplicate request
             * and use this stop for the completed operation, never replay it. */
            CHECK(notification_same_call(t,&t->metadata.request.data,&result->call));
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
            CHECK(t->phase==IDLE && !t->buffer.pool && !t->broker_event.held);
            t->original=regs; t->kernel_stopped=1; t->interrupt_pending=0;
            t->broker_event.held=1; t->broker_event.status=status;
            t->broker_event.usage=usage ? *usage : (struct rusage){0};
            t->broker_event.has_info=!event && signal!=(SIGTRAP|0x80);
            if (t->broker_event.has_info
                    && !tracee_request(t,PTRACE_GETSIGINFO,NULL,&t->broker_event.info,"completed-signal")) return 1;
            int error=md_buffers_take(md_memory_buffers(t->memory),&t->buffer);
            if (!error) error=md_fs_buffer_adopt(buffer_service,t->pid,t->resources.fs,result->operation,result->flags,
                &t->buffer,&result->result,&result->opened,&t->buffer_call);
            if (!error) {
                /* Transfer signal custody only after result admission. On
                 * failure the original delivery stop still owns the signal. */
                if (t->broker_event.has_info) {
                    t->retained_signal=md_signal_delivery_retain(&t->signal_delivery,t->pid,&t->broker_event.info);
                    CHECK(t->retained_signal>=0);
                }
                t->direct=DIRECT_OPEN; t->phase=DISPATCHING;
                t->direct_flags=result->flags; t->direct_mode=0;
                t->buffer_registers=t->original; t->buffer_registers.regs[1]=result->operation;
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
static void external_stat(void) {
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
static int registers(pid_t pid, struct user_pt_regs *out) {
    struct iovec iov = {out, sizeof(*out)};
    if (!tracee_request(thread(pid), PTRACE_GETREGSET, (void *)NT_PRSTATUS, &iov, "get-registers")) return 0;
    CHECK(iov.iov_len == sizeof(*out)); return 1;
}
static int set_registers(pid_t pid, struct user_pt_regs *regs) {
    struct iovec iov = {regs, sizeof(*regs)};
    return tracee_request(thread(pid), PTRACE_SETREGSET, (void *)NT_PRSTATUS, &iov, "set-registers");
}
static int skip(pid_t pid) {
    int nr = -1; struct iovec iov = {&nr, sizeof(nr)};
    return tracee_request(thread(pid), PTRACE_SETREGSET, (void *)NT_ARM_SYSTEM_CALL, &iov, "skip-syscall");
}
static int cooperative_native_observe(pid_t pid,struct md_entry_native *native) {
    unsigned state=atomic_load_explicit(&native->state,memory_order_acquire);
    if (state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED) return 0;
    struct user_pt_regs regs={0};
    if (state==MD_NATIVE_RUNNING) {
        if (!registers(pid,&regs)) return -ESRCH;
    }
    long value=(long)regs.regs[0],result=native->result;
    /* A terminal stop may retain openat's zero-extended input register. */
    if (native->number==SYS_openat && (unsigned long)value==(unsigned)AT_FDCWD) value=-512;
    int completed=md_native_stopped(state,regs.pc,value,abi.native_begin,abi.native_call,abi.native_end,&result);
    if (completed<0) return completed;
    if (state==MD_NATIVE_RUNNING) {
        regs.regs[0]=result; regs.pc=abi.native_end;
        if (!set_registers(pid,&regs)) return -ESRCH;
    }
    native->result=result;
    atomic_store_explicit(&native->state,completed,memory_order_release);
    return 0;
}
static void return_value(struct thread *t, struct user_pt_regs *regs, long value) {
    if (t->clone_prepared && regs->regs[8]==SYS_clone3) {
        t->original=*regs; t->phase=OBSERVING;
    }
    if (!skip(t->pid)) return;
    regs->regs[0] = (uint64_t)value;
    if (set_registers(t->pid, regs)) resume(t->pid, 0);
}
static int debugger_parent(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (t) {
        long parent=md_task_parent(pid);
        if (parent>=0) t->parent=(pid_t)parent;
    }
    return t ? t->parent : 0;
}
static void debugger_reaped(pid_t pid,int native_wait) {
    if (native_wait) md_task_registry_reap(tasks,pid);
    else pid_collection_pending=1;
}
static int debugger_group(pid_t pid) {
    struct thread *t=find_thread(pid);
    return t ? t->tgid : pid;
}
static uid_t debugger_uid(pid_t pid) {
    struct thread *t=find_thread(pid);
    CHECK(t);
    return t->identity.uid.real;
}
static uid_t debugger_visible_uid(pid_t observer,pid_t sender,uid_t fallback) {
    struct thread *t=find_thread(observer);
    return t ? process_signal_uid(t,sender,fallback) : fallback;
}
static pid_t debugger_visible_pid(pid_t observer,pid_t native) {
    struct thread *t=find_thread(observer);
    return t ? visible_pid(t,native) : 0;
}
static pid_t debugger_native_pid(pid_t observer,pid_t visible) {
    struct thread *t=find_thread(observer);
    return t ? native_pid(t,visible) : 0;
}
static int inspect_allowed(pid_t caller, pid_t pid, int filesystem) {
    struct thread *from=find_thread(caller), *to=find_thread(pid);
    if (!from || !to || md_domain_restricted(from->resources.fs)) return 0;
    return from->tgid==to->tgid || md_identity_may_inspect(&from->identity,&to->identity,filesystem);
}
static int debugger_allowed(pid_t caller, pid_t pid) {
    return inspect_allowed(caller,pid,0);
}
static int proc_allowed(pid_t caller, pid_t pid) {
    return inspect_allowed(caller,pid,1);
}
static void debugger_complete(pid_t pid, struct user_pt_regs *regs, long value) {
    struct thread *t=find_thread(pid);
    TRACE("PROBE debugger result pid=%d nr=%llu target=%llu value=%ld\n",pid,regs->regs[8],regs->regs[0],value);
    if (t) return_value(t,regs,value);
}
static int debugger_attachable(pid_t caller, pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t || debugger_group(caller)==debugger_group(pid) || !debugger_allowed(caller,pid)
            || !t->entered || !t->process_image) {
        TRACE("PROBE debugger admission caller=%d target=%d state-or-identity-denied\n",caller,pid);
        return 0;
    }
    char byte;
    struct iovec local={&byte,1}, remote={(void *)t->process_image,1};
    ssize_t result=process_vm_readv(pid,&local,1,&remote,1,0);
    if (result!=1) TRACE("PROBE debugger admission caller=%d target=%d image=%#lx read=%zd errno=%d\n",
        caller,pid,(unsigned long)t->process_image,result,errno);
    return result==1;
}
static int birth_stop_pending(const struct thread *t) {
    /* The initial leader is seized while running, not auto-attached by clone. */
    return t->pid!=leader && t->born && !t->initial_stop;
}
static int debugger_interrupt(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    /* An owned stop already queued by clone is itself the interruption boundary. */
    if (t->kernel_stopped || birth_stop_pending(t)) return 0;
    if (native_trace(PTRACE_INTERRUPT,pid,NULL,NULL)) return -errno;
    t->interrupt_pending=1; return 0;
}
static int debugger_observe(pid_t owner) {
    struct thread *parent=find_thread(owner);
    if (!parent) return -ESRCH;
    /* EVENT_WAIT: a new trace relationship can select a wait already blocked
     * in the kernel, in any owner thread. INTERRUPT restarts it through the
     * debugger-aware path; cancellation/exit still owns the wait's lifetime. */
    for (struct thread *t=threads; t; t=t->next) if (t->pid && t->tgid==parent->tgid) {
        int error=debugger_interrupt(t->pid);
        if (error && error!=-ESRCH) return error;
    }
    return 0;
}
static int debugger_options(pid_t pid, unsigned long options) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    int changed=((t->debug_options^options)&PTRACE_O_TRACEEXIT) && !t->terminal_observation;
    t->debug_options=options;
    if (!changed && !t->trace_options_dirty) return 0;
    t->trace_options_dirty=1;
    if (!t->kernel_stopped) return debugger_interrupt(pid);
    unsigned long required=t->terminal_observation ? PTRACE_O_TRACEEXIT : 0;
    if (native_trace(PTRACE_SETOPTIONS,pid,NULL,(void *)(trace_options|required|(options&PTRACE_O_TRACEEXIT)))) return -errno;
    t->trace_options_dirty=0;
    return 0;
}
static void debugger_usage(pid_t pid, struct rusage *usage) {
    struct thread *t=find_thread(pid);
    *usage=t ? t->usage : (struct rusage){0};
}
static int debugger_listen(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    if (native_trace(PTRACE_LISTEN,pid,NULL,NULL)) return -errno;
    t->listening=1; t->kernel_stopped=0;
    return 0;
}
static void restart_call(struct thread *t,const struct user_pt_regs *original,uint64_t mask,
        enum call_restart policy) {
    t->original=*original; t->mask=mask; t->call_restart=policy;
    if (!shield(t)) return;
    struct user_pt_regs regs=*original;
    regs.pc=RAW_GATE-4; regs.regs[8]=SYS_gettid;
    if (!set_registers(t->pid,&regs)) return;
    t->phase=CALL_RESTART_ENTRY; resume(t->pid,0);
}
static void debugger_wait_wake(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t || t->debug_wait_waking) return;
    CHECK(t->phase==DEBUG_PARK_ENTRY || t->phase==DEBUG_PARK_EXIT);
    t->debug_wait_waking=1;
    /* EVENT_WAIT: the exact native poll stop must precede register changes.
     * An event before poll entry is consumed there without entering the wait. */
    if (t->phase==DEBUG_PARK_EXIT && !t->listening) {
        int error=debugger_interrupt(pid); CHECK(!error || error==-ESRCH);
    }
}
static void debugger_wait(pid_t pid, const struct user_pt_regs *original, enum md_debugger_wait_action action) {
    struct thread *t=find_thread(pid);
    if (!t) return;
    struct user_pt_regs regs=*original;
    if (action==MD_DEBUG_WAIT_RESTART) { restart_call(t,original,t->debug_wait_mask,RESTART_SIGNAL); return; }
    if (action==MD_DEBUG_WAIT_PARK) {
        /* EVENT_WAIT: native interruptible poll delivers arbitrary guest
         * signals. Inferior events cancel it with INTERRUPT; no periodic wake
         * or descriptor is needed, and ordinary execution never enters here. */
        t->original=*original; t->debug_wait_waking=0;
        if (!shield(t)) return;
        regs.pc=RAW_GATE-4; regs.regs[8]=SYS_ppoll;
        for (unsigned i=0;i<6;i++) regs.regs[i]=0;
        if (set_registers(pid,&regs)) { t->phase=DEBUG_PARK_ENTRY; resume(pid,0); }
        return;
    }
    unsigned index=regs.regs[8]==SYS_waitid ? 1 : 0;
    if (action!=MD_DEBUG_WAIT_NATIVE
            && (regs.regs[8]==SYS_wait4 || regs.regs[0]==P_PID || regs.regs[0]==P_PGID) && (pid_t)regs.regs[index]>0) {
        pid_t mapped=native_pid(t,(pid_t)regs.regs[index]);
        if (!mapped) { return_value(t,&regs,-ECHILD); return; }
        regs.regs[index]=mapped;
    }
    TRACE("PROBE debugger wait pid=%d nr=%llu target=%llu action=%d phase=%d\n",pid,regs.regs[8],regs.regs[0],action,t->phase);
    if (action!=MD_DEBUG_WAIT_PROBE) regs.pc-=4;
    if (action==MD_DEBUG_WAIT_NATIVE) t->phase=IDLE;
    else {
        /* Only the nonblocking probe is shielded. A handler must not replace
         * the replayed syscall; native blocking waits retain ordinary signals. */
        if (!save_signal_mask(t,&t->debug_wait_mask,"debug-wait-mask")
                || !shield(t)) return;
        regs.regs[regs.regs[8]==SYS_waitid ? 3 : 2]|=WNOHANG;
        t->phase=action==MD_DEBUG_WAIT_PROBE ? DEBUG_WAIT_EXIT : DEBUG_WAIT_ENTRY;
    }
    if (set_registers(pid,&regs)) resume(pid,0);
}
enum { COPY_GROUPS, COPY_CAP_HEADER, COPY_CAP_DATA, COPY_CAP_VERSION, COPY_LIMIT_INPUT, COPY_LIMIT_OUTPUT };
static void identity_copy_next(struct thread *t);
static int process_limit_apply(struct thread *t,const struct rlimit *next,struct rlimit *old) {
    pid_t pid=t->original.regs[8]==SYS_prlimit64 ? (pid_t)t->original.regs[0] : 0;
    if (pid) pid=native_pid(t,pid);
    else pid=t->pid;
    struct thread *target=find_thread(pid);
    struct md_task_retired_view retired=md_task_registry_retired(tasks,pid);
    const struct md_identity *id=target ? &target->identity : retired.identity;
    if (!id) return -ESRCH;
    unsigned resource=t->original.regs[t->original.regs[8]==SYS_prlimit64 ? 1 : 0];
    return md_process_limit(target ? target->resources.process : retired.process,
        &t->identity,id,target && target->tgid==t->tgid,resource,next,old,pid);
}
static void identity_copy_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->original;
    if (!error && t->identity_stage==COPY_LIMIT_INPUT) {
        struct rlimit next,old;
        memcpy(&next,t->identity_buffer,sizeof(next));
        error=process_limit_apply(t,t->identity_count ? &next : NULL,&old);
        uintptr_t output=r.regs[8]==SYS_prlimit64 ? r.regs[3] : r.regs[8]==SYS_getrlimit ? r.regs[1] : 0;
        if (!error && (output || r.regs[8]==SYS_getrlimit)) {
            memcpy(t->identity_buffer,&old,sizeof(old));
            t->identity_stage=COPY_LIMIT_OUTPUT; t->identity_loading=0;
            t->identity_address=output; t->identity_count=sizeof(old)/sizeof(uint32_t); t->identity_copied=0;
            identity_copy_next(t); return;
        }
    } else if (!error && t->identity_stage == COPY_CAP_HEADER) {
        uint32_t version = t->identity_buffer[0];
        int pid = (int)t->identity_buffer[1];
        if (pid>0 && t->resources.pid) {
            pid=native_pid(t,pid);
            if (!pid) error=-ESRCH;
        }
        unsigned words = version == _LINUX_CAPABILITY_VERSION_1 ? 1
            : version == _LINUX_CAPABILITY_VERSION_2 || version == _LINUX_CAPABILITY_VERSION_3 ? 2 : 0;
        if (!words) {
            t->identity_buffer[0] = _LINUX_CAPABILITY_VERSION_3;
            t->identity_stage = COPY_CAP_VERSION; t->identity_loading = 0;
            t->identity_count = 1; t->identity_copied = 0;
            t->identity_result = r.regs[8] == SYS_capget && !r.regs[1] ? 0 : -EINVAL;
            identity_copy_next(t); return;
        }
        struct md_identity ids = md_identity_copy(&t->identity);
        if (!error && pid && pid != t->pid) {
            md_identity_release(&ids);
            struct md_task_snapshot snapshot={0};
            error = r.regs[8] == SYS_capset ? -EPERM : md_task_registry_read(tasks, pid, 0, &snapshot);
            ids=md_identity_copy(&snapshot.identity);
            md_task_snapshot_release(&snapshot);
        }
        if (!error && r.regs[8] == SYS_capget && !r.regs[1]) {
            md_identity_release(&ids); t->identity_stage = COPY_CAP_DATA;
        } else if (!error) {
            if (r.regs[8] == SYS_capget) for (unsigned i = 0; i < words; i++) {
                t->identity_buffer[i*3] = ids.caps.effective >> (32*i);
                t->identity_buffer[i*3+1] = ids.caps.permitted >> (32*i);
                t->identity_buffer[i*3+2] = ids.caps.inheritable >> (32*i);
            }
            md_identity_release(&ids);
            t->identity_stage = COPY_CAP_DATA; t->identity_loading = r.regs[8] == SYS_capset;
            t->identity_address = r.regs[1]; t->identity_count = words*3; t->identity_copied = 0;
            identity_copy_next(t); return;
        } else md_identity_release(&ids);
    } else if (!error && t->identity_stage == COPY_CAP_DATA && r.regs[8] == SYS_capset) {
        uint32_t *v = t->identity_buffer;
        error = md_identity_capset(&t->identity, v[1] | ((uint64_t)v[4] << 32),
            v[0] | ((uint64_t)v[3] << 32), v[2] | ((uint64_t)v[5] << 32));
    }
    if (!error && t->identity_stage == COPY_CAP_VERSION) error = t->identity_result;
    if (!error && r.regs[8] == SYS_setgroups)
        error = md_identity_setgroups(&t->identity, t->identity_buffer, t->identity_count);
    r.regs[0] = (uint64_t)(error ? (long)error : r.regs[8] == SYS_getgroups ? (long)t->identity_count : 0);
    free(t->identity_buffer); t->identity_buffer = NULL;
    if (!set_registers(t->pid, &r) || !restore_mask(t)) return;
    t->phase = IDLE; resume(t->pid, 0);
}
static void identity_copy_next(struct thread *t) {
    if (t->identity_copied == t->identity_count) { identity_copy_finish(t, 0); return; }
    struct user_pt_regs r = t->original;
    unsigned count = t->identity_count - t->identity_copied;
    if (count > 16) count = 16;
    uintptr_t address = t->identity_address, offset = (uintptr_t)t->identity_copied * sizeof(uint32_t);
    if (address > UINTPTR_MAX - offset - count * sizeof(uint32_t)) { identity_copy_finish(t, -EFAULT); return; }
    int loading = t->identity_loading;
    r.pc = loading ? abi.load_groups : abi.store_groups;
    r.regs[0] = address + offset; r.regs[1] = count;
    if (!loading) for (unsigned i = 0; i < count; i++) r.regs[i+3] = t->identity_buffer[t->identity_copied+i];
    if (!set_registers(t->pid, &r)) return;
    t->phase = IDENTITY_COPY; resume(t->pid, 0);
}
static void identity_groups_call(struct thread *t, struct user_pt_regs *r) {
    int setting = r->regs[8] == SYS_setgroups;
    unsigned count = setting ? (unsigned)r->regs[0] : md_identity_group_count(&t->identity);
    int error = 0;
    if (setting && (!md_identity_capable(&t->identity, CAP_SETGID)
            || !md_user_namespace_may_setgroups(t->identity.user_namespace))) error = -EPERM;
    else if ((int)r->regs[0] < 0 || (setting && (unsigned)r->regs[0] > MD_IDENTITY_GROUPS_MAX)
            || (!setting && r->regs[0] && (unsigned)r->regs[0] < count)) error = -EINVAL;
    if (error || (!setting && !r->regs[0])) { return_value(t, r, error ? error : (long)count); return; }
    if (!count) { if (setting) md_identity_setgroups(&t->identity, NULL, 0); return_value(t, r, 0); return; }
    uint32_t *buffer = malloc((size_t)count * sizeof(*buffer));
    if (!buffer) { return_value(t, r, -ENOMEM); return; }
    if (!setting) for (unsigned i=0;i<count;++i)
        buffer[i]=md_identity_visible_id(&t->identity,1,md_identity_group_data(&t->identity)[i]);
    struct iovec local = {buffer, (size_t)count * sizeof(*buffer)}, remote = {(void *)r->regs[1], local.iov_len};
    ssize_t copied = setting ? process_vm_readv(t->pid, &local, 1, &remote, 1, 0)
        : process_vm_writev(t->pid, &local, 1, &remote, 1, 0);
    if (copied == (ssize_t)local.iov_len) {
        error = setting ? md_identity_setgroups(&t->identity, buffer, count) : 0;
        free(buffer); return_value(t, r, error ? error : setting ? 0 : (long)count); return;
    }
    /* Task-affine copies preserve protected-memory and EFAULT semantics without
     * relaxing dumpability. Identity publishes only after the complete read. */
    t->identity_buffer = buffer; t->identity_count = count; t->identity_copied = 0; t->original = *r;
    t->identity_address = r->regs[1]; t->identity_loading = setting; t->identity_stage = COPY_GROUPS;
    if (!save_signal_mask(t,&t->mask,"get-mask")
            || !shield(t) || !skip(t->pid)) return;
    identity_copy_next(t);
}
static int process_limit_call(struct thread *t,struct user_pt_regs *r) {
    if (r->pc == RAW_GATE) return 0;
    long nr=r->regs[8];
    unsigned resource=r->regs[nr==SYS_prlimit64 ? 1 : 0];
    if (t->phase==IDLE && t->ready
            && (nr==SYS_prlimit64 || nr==SYS_getrlimit || nr==SYS_setrlimit)
            && (resource==RLIMIT_NPROC || resource==RLIMIT_NOFILE
                || (nr==SYS_prlimit64 && r->regs[0] && !r->regs[2]))) {
        t->identity_buffer=calloc(1,sizeof(struct rlimit));
        if (!t->identity_buffer) { return_value(t,r,-ENOMEM); return 1; }
        t->original=*r; t->identity_stage=COPY_LIMIT_INPUT;
        t->identity_address=nr==SYS_prlimit64 ? r->regs[2] : nr==SYS_setrlimit ? r->regs[1] : 0;
        t->identity_count=t->identity_address || nr==SYS_setrlimit ? sizeof(struct rlimit)/sizeof(uint32_t) : 0;
        t->identity_loading=1; t->identity_copied=0;
        if (save_signal_mask(t,&t->mask,"process-limit-copy")
                && shield(t) && skip(t->pid)) identity_copy_next(t);
        return 1;
    }
    return 0;
}
#include "identity_call.h"
static int identity_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    if (r->pc == RAW_GATE) return 0;
    long nr = r->regs[8], value;
    if (nr == SYS_prctl) {
        unsigned long args[4] = {r->regs[1], r->regs[2], r->regs[3], r->regs[4]};
        value = md_identity_cap_prctl(&t->identity, r->regs[0], args);
        if (value != -ENOTSUP) { return_value(t, r, value); return 1; }
    }
    if (nr == SYS_prctl && (r->regs[0] == PR_GET_NO_NEW_PRIVS || r->regs[0] == PR_SET_NO_NEW_PRIVS)) {
        CHECK(t->phase == IDLE && t->ready);
        if (r->regs[2] || r->regs[3] || r->regs[4]) value = -EINVAL;
        else if (r->regs[0] == PR_GET_NO_NEW_PRIVS) value = r->regs[1] ? -EINVAL : t->identity.no_new_privs;
        else value = md_identity_no_new_privs(&t->identity, r->regs[1]);
        return_value(t, r, value); return 1;
    }
    if (cookie != MD_INTERCEPT_IDENTITY) return 0;
    CHECK(t->ready && t->phase == IDLE);
    switch (nr) {
    case SYS_capget: case SYS_capset:
        t->identity_buffer = calloc(6, sizeof(uint32_t));
        if (!t->identity_buffer) { return_value(t, r, -ENOMEM); return 1; }
        t->identity_stage = COPY_CAP_HEADER; t->identity_address = r->regs[0];
        t->identity_loading = 1; t->identity_count = 2; t->identity_copied = 0; t->original = *r;
        if (!save_signal_mask(t,&t->mask,"get-mask")
                || !shield(t) || !skip(t->pid)) return 1;
        identity_copy_next(t); return 1;
    case SYS_getgroups: case SYS_setgroups: identity_groups_call(t, r); return 1;
    case SYS_getuid: case SYS_geteuid: case SYS_getgid: case SYS_getegid:
        value=md_identity_scalar_result(&t->identity,nr); break;
    case SYS_getresuid: case SYS_getresgid: {
        const struct md_identity_ids *ids = nr == SYS_getresuid ? &t->identity.uid : &t->identity.gid;
        t->original = *r;
        if (!save_signal_mask(t,&t->mask,"get-mask")
                || !shield(t) || !skip(t->pid)) return 1;
        int group=nr==SYS_getresgid;
        r->pc = STORE_IDS; r->regs[3] = md_identity_visible_id(&t->identity,group,ids->real);
        r->regs[4] = md_identity_visible_id(&t->identity,group,ids->effective);
        r->regs[5] = md_identity_visible_id(&t->identity,group,ids->saved);
        if (!set_registers(t->pid, r)) return 1;
        t->phase = STORING_IDS; resume(t->pid, 0); return 1;
    }
    case SYS_setresuid:
        value = md_identity_setresuid(&t->identity, r->regs[0], r->regs[1], r->regs[2]); break;
    case SYS_setresgid:
        value = md_identity_setresgid(&t->identity, r->regs[0], r->regs[1], r->regs[2]); break;
    case SYS_setuid: value = md_identity_setuid(&t->identity, r->regs[0]); break;
    case SYS_setgid: value = md_identity_setgid(&t->identity, r->regs[0]); break;
    case SYS_setreuid: value = md_identity_setreuid(&t->identity, r->regs[0], r->regs[1]); break;
    case SYS_setregid: value = md_identity_setregid(&t->identity, r->regs[0], r->regs[1]); break;
    case SYS_setfsuid: value = md_identity_setfsuid(&t->identity, r->regs[0]); break;
    case SYS_setfsgid: value = md_identity_setfsgid(&t->identity, r->regs[0]); break;
    default: value = -ENOTSUP; break;
    }
    if ((nr == SYS_setresuid || nr == SYS_setresgid) && !value) {
        identity_changes++;
        TRACE("PROBE identity-change pid=%d uid=%u euid=%u suid=%u gid=%u egid=%u\n",
            t->pid, t->identity.uid.real, t->identity.uid.effective, t->identity.uid.saved,
            t->identity.gid.real, t->identity.gid.effective);
    }
    return_value(t, r, value); return 1;
}
static void dispatch_with_stack(struct thread *);
static int guest_root_path(struct thread *t, long nr) {
    return nr == SYS_chroot && t->endpoint[0] && !md_domain_restricted(t->resources.fs);
}
static int domain_path_apply(struct thread *t, long nr, const char *path) {
    if (nr == SYS_chdir)
        return !strcmp(path, "/") ? md_domain_chdir_root(t->resources.fs) : -ENOTSUP;
    int error = md_domain_restrict(t->resources.fs, t->pid, path);
    if (!error) {
        root_changes++;
        /* CLONE_FS members may run without another ptrace stop. Revoke their
         * published eligibility before acknowledging the shared restriction. */
        if (broker) for (struct thread *member = threads; member; member = member->next)
            if (member->pid && member->resources.fs == t->resources.fs)
                CHECK(!md_broker_task(broker, member->pid, 0, RAW_GATE, abi.watch_return,NULL));
    }
    TRACE("PROBE root-change pid=%d result=%d\n", t->pid, error);
    return error;
}
static void domain_path_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->original;
    if (!error && guest_root_path(t, r.regs[8])) {
        dispatch_with_stack(t); return;
    }
    if (!error) error = domain_path_apply(t, r.regs[8], t->metadata.path);
    r.regs[0] = (uint64_t)error;
    if (!set_registers(t->pid, &r) || !restore_mask(t)) return;
    t->phase = IDLE; resume(t->pid, 0);
}
static void domain_path_load(struct thread *t) {
    size_t offset = t->metadata.path_size;
    if (offset == sizeof(t->metadata.path)) { domain_path_finish(t, -ENAMETOOLONG); return; }
    if (t->original.regs[0] > UINTPTR_MAX - offset) { domain_path_finish(t, -EFAULT); return; }
    struct user_pt_regs r = t->original;
    r.pc = LOAD_BYTE; r.regs[0] += offset;
    if (!set_registers(t->pid, &r)) return;
    t->phase = DOMAIN_PATH; resume(t->pid, 0);
}
static void domain_path_call(struct thread *t, struct user_pt_regs *r) {
    char path[4096];
    int error = read_string(t->pid, r->regs[0], path, sizeof(path));
    if (error != -EPERM && error != -EACCES) {
        if (!error && guest_root_path(t, r->regs[8])) {
            CHECK(t->ready && t->phase == IDLE);
            t->original = *r;
            if (!save_signal_mask(t,&t->mask,"get-mask")
                    || !shield(t) || !skip(t->pid)) return;
            dispatch_with_stack(t); return;
        }
        if (!error) error = domain_path_apply(t, r->regs[8], path);
        return_value(t, r, error); return;
    }
    CHECK(t->ready && t->phase == IDLE);
    t->original = *r; t->metadata.path_size = 0;
    if (!save_signal_mask(t,&t->mask,"get-mask")
            || !shield(t) || !skip(t->pid)) return;
    domain_path_load(t);
}
static void fd_discard(struct thread *t) {
    /* Fork owns the address-space turn even before kernel admission. Early
     * quota/allocation failure must retire it just like a completed clone. */
    if (t->memory_action==MEMORY_FORK) {
        if (md_resource_order_cancel(&t->memory_order)==1)
            CHECK(!md_resource_order_complete(&t->memory_order));
        t->memory_action=0;
    }
    md_process_member_release(t->process_reservation); t->process_reservation=NULL;
    retry_clear(t,RETRY_FD);
    md_fd_selector_release(t->fork_selector); t->fork_selector=NULL;
    for (unsigned i=0;i<2;++i) md_fd_origins_finish(&t->pair_updates[i],-1);
    md_pid_identity_release(t->pid_reservation); t->pid_reservation=NULL;
    md_mount_copy_cancel(&t->mount_copy);
    if (t->fd_task) {
        md_fs_buffer_cancel(&t->buffer_call);
        t->fd_task=t->fd_running=0;
        return;
    }
    if (t->fd_running && t->fd_cwd && t->fd_registers.regs[8]!=SYS_openat)
        CHECK(!md_domain_set_cwd(t->fs_order.fs,NULL));
    md_fs_anchor_release(t->fd_origin); t->fd_origin=NULL;
    if (t->fs_order.fs && md_fs_order_cancel(&t->fs_order)==1)
        CHECK(!md_fs_order_complete(&t->fs_order));
    if (t->fd_running) CHECK(!md_fd_mutation_abandon(&t->fd_mutation,t->fd_order.table));
    else md_fd_mutation_discard(&t->fd_mutation);
    if (t->fd_order.table) {
        int running=md_fd_order_cancel(&t->fd_order);
        /* Called only after a terminal task event, never merely on timeout. */
        if (running==1) {
            if (t->fd_job==MD_FD_RECEIVE) CHECK(!md_fd_exchange_received(t->fd_exchange));
            CHECK(!md_fd_order_complete(&t->fd_order));
        }
    }
    t->fd_running=0; t->fd_job=0; t->fd_cwd=0; t->fd_capture=0;
    t->duplicate_attempted=0; t->duplicate_result=-1;
    md_fd_exchange_close(fd_exchanges,t->fd_exchange); t->fd_exchange=NULL;
}
static void fd_finish(struct thread *t, long result) {
    if (t->fd_task) {
        /* The adapter must acknowledge execution. Fatal filter exits can
         * expose unchanged arguments as if they were a syscall result. */
        t->fd_task=t->fd_running=0;
        return;
    }
    long nr=t->fd_registers.regs[8];
    if (md_clone_call(nr) && t->memory_action==MEMORY_FORK) {
        CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
    }
    int exec=nr==SYS_execve || nr==SYS_execveat;
    if (exec || nr==SYS_unshare || (nr==SYS_close_range && (t->fd_registers.regs[2]&CLOSE_RANGE_UNSHARE)))
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,result));
    if (md_clone_call(nr))
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ECANCELED));
    if (md_clone_call(nr)) {
        md_process_member_release(t->process_reservation); t->process_reservation=NULL;
        md_pid_identity_release(t->pid_reservation); t->pid_reservation=NULL;
        md_fd_selector_release(t->fork_selector); t->fork_selector=NULL;
    }
    if (exec && !result) md_fd_origins_exec_collect(t->resources.files,&t->closed);
    if (t->fd_cwd && nr!=SYS_openat && !result)
        CHECK(!md_domain_set_cwd(t->fs_order.fs,md_domain_restricted(t->fs_order.fs) ? NULL : t->fd_origin));
    md_fs_anchor_release(t->fd_origin); t->fd_origin=NULL;
    CHECK(!md_fd_mutation_finish_closed(&t->fd_mutation,t->resources.files,result,&t->closed));
    if (t->fs_order.fs) CHECK(!md_fs_order_complete(&t->fs_order));
    if (t->fd_job==MD_FD_DUPLICATE_BEGIN)
        t->duplicate_result=nr==SYS_close || result<0 ? -1 : (int)result;
    else CHECK(!md_fd_order_complete(&t->fd_order));
    t->fd_running=0; t->fd_cwd=0; t->fd_capture=0;
}
static void fd_native_start(struct thread *t);
static void mount_copy_complete(struct md_mount_copy_request *request, int actor, int error) {
    struct thread *t=find_thread(actor);
    if (!t || t->mount_copy!=request) return;
    t->mount_copy=NULL;
    if (!error) error=md_mount_copy_take(request,&t->resource_change.next.mounts,&t->resource_change.next.fs);
    if (error) {
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error));
        fd_discard(t); return_value(t,&t->fd_registers,error); return;
    }
    fd_native_start(t);
}
static int resources_unshare_prepare(struct thread *t,unsigned long flags) {
    int error=md_resources_unshare_prepare(&t->resources,flags,&t->identity,&t->resource_change);
    if (error==-ENOSPC && (flags&CLONE_NEWUSER) && md_task_registry_has_retired(tasks)) {
        /* A native wait may reap after the exit-time collection. Retry only
         * on quota pressure, preserving in-flight PID translation owners. */
        pid_collection_pending=1; pid_collect();
        error=md_resources_unshare_prepare(&t->resources,flags,&t->identity,&t->resource_change);
    }
    return error;
}
static void fd_start(struct thread *t) {
    retry_clear(t,RETRY_FD);
    if (md_fd_order_state(&t->fd_order)==MD_RESOURCE_ORDER_QUEUED
            || md_fs_order_state(&t->fs_order)==MD_RESOURCE_ORDER_QUEUED) {
        retry_wait(t,RETRY_FD); return;
    }
    if (md_fd_order_state(&t->fd_order)==MD_RESOURCE_ORDER_READY)
        CHECK(!md_fd_order_start(&t->fd_order));
    struct user_pt_regs *r=&t->fd_registers;
    if (t->fd_job==MD_FD_DUPLICATE_BEGIN) {
        int error=md_fd_origins_validate(&t->fd_order,r->regs[1]);
        if (error) {
            CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0;
            r->regs[0]=error;
            if (set_registers(t->pid,r)) resume(t->pid,0);
            return;
        }
    }
    if ((!t->fd_job || t->fd_job==MD_FD_DUPLICATE_BEGIN) && !fd_loans_ready(t)) return;
    int needs_fs=t->fd_job==MD_FD_INPUT ? (int)r->regs[2]==AT_FDCWD || (int)r->regs[3]==AT_FDCWD
        : !t->fd_job && (t->fd_cwd || md_clone_call(r->regs[8]) || r->regs[8]==SYS_setns
            || (r->regs[8]==SYS_unshare && (r->regs[0]&(CLONE_FS|CLONE_NEWNS|CLONE_NEWUSER))));
    if (needs_fs) {
        if (!t->fs_order.fs) {
            int ready=md_fs_order_begin(t->resources.fs,&t->fs_order,fd_wakeup);
            CHECK(ready>=0);
            /* EVENT_WAIT: the CLONE_FS owner completes its exact mutation;
             * terminal task cleanup cancels both ordered resource records. */
            if (!ready) { retry_wait(t,RETRY_FD); return; }
        }
        CHECK(!md_fs_order_start(&t->fs_order));
    }
    if (t->fd_job) {
        long result=0;
        if (t->fd_job==MD_FD_DUPLICATE_BEGIN) {
            struct md_fd_observation view;
            result=md_fd_origins_observe(t->resources.files,(int)t->original.regs[0],&view);
            if (!result) result=view.selectors;
        }
        if (t->fd_job==MD_FD_PAIR_BEGIN) {
            struct md_fd_object *inode=t->original.regs[8]==SYS_pipe2
                ? md_descriptor_inode_new(&t->identity,S_IFIFO|0600) : md_network_socket_new(t->resources.network);
            struct md_fd_object *objects[2]={inode,inode};
            result=inode ? md_fd_origins_prepare_batch(t->resources.files,objects,
                t->original.regs[1]&O_CLOEXEC ? FD_CLOEXEC : 0,2,t->pair_updates) : -ENOMEM;
            md_fd_object_release(inode);
        }
        if (t->fd_job==MD_FD_INPUT) {
            int bases[2]={(int)r->regs[2],(int)r->regs[3]};
            struct md_fs_anchor *cwd=NULL;
            if (needs_fs) {
                struct md_fs_location root;
                md_domain_paths(t->fs_order.fs,&root,&cwd);
            }
            result=md_fd_exchange_begin(fd_exchanges,t->pid,t->resources.files,bases,cwd,&t->fd_exchange);
            md_fs_anchor_release(cwd);
            if (t->fs_order.fs) CHECK(!md_fs_order_complete(&t->fs_order));
            if (!result) result=(long)md_fd_exchange_id(t->fd_exchange);
        }
        if (result<0) {
            CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0;
        }
        r->regs[0]=result;
        if (set_registers(t->pid,r)) resume(t->pid,0);
        return;
    }
    int error=0;
    if (r->regs[8]==SYS_setns) {
        struct md_proc_file *file=proc_file_capture(t,(int)r->regs[0]);
        const struct md_namespace_reference *ref=md_proc_file_namespace_reference(file);
        if (!ref || (file && (md_proc_file_flags(file)&O_PATH))) error=(int)r->regs[0]<0 ? -EBADF : -EINVAL;
        if (!error && ref->kind==MD_NAMESPACE_USER)
            for (struct thread *other=threads;other;other=other->next)
                if (other!=t && other->pid && other->tgid==t->tgid) { error=-EINVAL; break; }
        if (!error) error=md_resources_setns_prepare(&t->resources,&t->identity,ref,
            (unsigned)r->regs[1],&t->resource_change);
        md_proc_file_release(file);
        if (!error && t->resource_change.join_mounts) {
            error=md_mount_join_submit(mount_copy_service,t->pid,t->resource_change.join_mounts,&t->mount_copy);
            /* EVENT_WAIT: selected namespace root/cwd resolution; cancellation
             * discards the unpublished context without changing task state. */
            if (!error) return;
        }
    }
    if (r->regs[8]==SYS_execve || r->regs[8]==SYS_execveat || r->regs[8]==SYS_unshare
            || (r->regs[8]==SYS_close_range && (r->regs[2]&CLOSE_RANGE_UNSHARE)))
        error=resources_unshare_prepare(t,r->regs[8]==SYS_unshare ? r->regs[0] : CLONE_FILES);
    if (md_clone_call(r->regs[8]) && md_resources_namespace_flags(t->cloning))
        error=resources_unshare_prepare(t,md_resources_namespace_flags(t->cloning));
    if (!error && (t->resource_change.copy_mounts || t->resource_change.check_root)) {
        error=md_mount_copy_submit(mount_copy_service,t->pid,t->resources.mounts,t->resources.fs,
            t->resource_change.identity_pending ? t->resource_change.identity.user_namespace : t->identity.user_namespace,
            t->resource_change.copy_mounts,t->resource_change.check_root,&t->mount_copy);
        /* EVENT_WAIT: worker publishes an independent mount/FS candidate;
         * terminal cancellation retires it without changing the task's view. */
        if (!error) return;
    }
    if (error) {
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error));
        fd_discard(t); return_value(t,r,error); return;
    }
    fd_native_start(t);
}
static void fd_native_start(struct thread *t) {
    struct user_pt_regs *r=&t->fd_registers;
    if (!t->fd_job && !t->fd_cwd && !t->fd_capture && t->phase==IDLE
            && r->regs[8]==SYS_fcntl && (unsigned)r->regs[1]==F_SETFD
            && !t->debug_options && !t->debug_call_active && !t->debug_step_call
            && !md_debugger_traced(debugger,t->pid) && md_fd_origins_exclusive(&t->fd_order)) {
        struct md_fd_native native;
        if (md_fd_origins_native(&t->fd_order,(int)r->regs[0],&native)
                && (r->regs[2]&FD_CLOEXEC)==native.flags) {
            /* Kernel denial or success leaves the same metadata. No peer can
             * change this private table before the admitted call executes. */
            CHECK(!md_fd_order_complete(&t->fd_order));
            if (statistics) ++statistics->unchanged_fd_flags;
            resume(t->pid,0); return;
        }
    }
    int admission=0;
    if (md_clone_call(r->regs[8]) && !t->process_reservation) {
        admission=md_process_member_fork(t->resources.process,&t->identity,t->cloning,&t->process_reservation);
        if (admission==-EAGAIN && md_task_registry_has_retired(tasks)) {
            md_task_registry_collect_limits(tasks,pid_gone);
            admission=md_process_member_fork(t->resources.process,&t->identity,t->cloning,&t->process_reservation);
        }
    }
    else if (r->regs[8]==SYS_execve || r->regs[8]==SYS_execveat) {
        admission=md_process_member_exec(t->resources.process);
        if (admission==-EAGAIN && md_task_registry_has_retired(tasks)) {
            md_task_registry_collect_limits(tasks,pid_gone);
            admission=md_process_member_exec(t->resources.process);
        }
    }
    if (admission) {
        md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,admission);
        fd_discard(t); return_value(t,r,admission); return;
    }
    if (md_clone_call(r->regs[8]) && !t->pid_reservation) {
        struct md_pid_namespace *space=t->resource_change.next.pid_children
            ? t->resource_change.next.pid_children : t->resources.pid_children;
        struct md_clone_request *request=NULL;
        if (t->clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span)); request=span.data;
        }
        unsigned count=request ? request->args.set_tid_size : 0;
        unsigned depth=md_pid_namespace_depth(space), selected=count<depth ? count : depth;
        const struct md_identity *id=t->resource_change.identity_pending ? &t->resource_change.identity : &t->identity;
        int error=(t->cloning&CLONE_THREAD) && space!=md_pid_identity_namespace(t->resources.pid)
            ? -EINVAL : count>depth+1 ? -EINVAL
            : md_pid_identity_prepare_selected(space,id,request ? request->tids : NULL,selected,&t->pid_reservation);
        if (!error && request && (t->cloning&CLONE_INTO_CGROUP))
            error=md_resources_clone_cgroup_prepare(&t->resources,t->cloning,t->clone_cgroup,
                &t->identity,&t->resource_change);
        if (error) {
            md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error);
            fd_discard(t); return_value(t,r,error); return;
        }
    }
    if (r->regs[8]==SYS_setns) {
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,0));
        fd_discard(t); return_value(t,r,0); return;
    }
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=r->regs[i];
    int error=0;
    if (!error) error=md_fd_mutation_prepare(t->resources.files,r->regs[8],args,&t->fd_mutation);
    if (!error && r->regs[8]==SYS_epoll_create1) {
        struct md_control_epoll *queue=md_control_epoll_new();
        error=queue ? md_fd_origins_prepare_object(t->resources.files,md_control_epoll_object(queue),
            r->regs[0]&EPOLL_CLOEXEC ? FD_CLOEXEC : 0,&t->fd_mutation.update) : -ENOMEM;
        md_control_epoll_release(queue);
    }
    if (!error && r->regs[8]==SYS_pidfd_open) {
        struct thread *target=find_thread((pid_t)r->regs[0]);
        if (target && target->resources.pid) {
            struct md_fd_object *object=md_pid_descriptor_new(target->resources.pid_group);
            error=object ? md_fd_origins_prepare_object(t->resources.files,object,FD_CLOEXEC,&t->fd_mutation.update) : -ENOMEM;
            md_fd_object_release(object);
        }
    }
    if (!error && t->fd_cwd && r->regs[8]==SYS_fchdir)
        t->fd_origin=md_fd_origins_get(t->resources.files,(int)r->regs[0],NULL);
    if (!error && t->fd_cwd && r->regs[8]==SYS_chdir && t->buffer.pool
            && t->buffer_open.task_path==MD_FS_TASK_CHDIR
            && r->regs[0]==md_memory_buffer_address(t->memory,&t->buffer)+offsetof(struct md_fs_buffer_frame,source))
        t->fd_origin=md_fs_anchor_retain(t->buffer_open.origin);
    if (!error && t->fd_capture) {
        if ((int)r->regs[4]==MD_FS_BUFFER_SOURCE) t->fd_origin=md_fs_anchor_retain(t->buffer_open.origin);
        else if ((int)r->regs[4]==AT_FDCWD) {
            struct md_fs_location root;
            md_domain_paths(t->fs_order.fs,&root,&t->fd_origin);
        } else t->fd_origin=md_fd_origins_get(t->resources.files,(int)r->regs[4],NULL);
        struct md_fd_object *object=(int)r->regs[4]==MD_FS_BUFFER_SOURCE
            ? md_fs_buffer_open_object(&t->buffer_open) : md_fs_anchor_object(t->fd_origin);
        error=md_fd_origins_prepare_object(t->resources.files,object,
            r->regs[2]&O_CLOEXEC ? FD_CLOEXEC : 0,&t->fd_mutation.update);
        TRACE("PROBE path-capture pid=%d base=%d known=%d\n",t->pid,(int)r->regs[4],t->fd_origin!=NULL);
    }
    if (error) {
        md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ECANCELED);
        fd_discard(t); return_value(t,r,error); return;
    }
    if (md_clone_call(r->regs[8])) {
        /* Snapshot before kernel clone. Concurrent TSYNC may reach the parent
         * after the child copied its filters; a later metadata snapshot would
         * incorrectly claim that the child inherited that new selector. */
        t->fork_selector=md_fd_selector_fork(t->fd_selector,!!(t->cloning&CLONE_THREAD));
        if (!t->fork_selector) {
            md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ENOMEM);
            fd_discard(t); return_value(t,r,-ENOMEM); return;
        }
        t->original=*r; t->phase=OBSERVING;
        uintptr_t parent=r->regs[2];
        if (t->clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
            parent=((struct clone_args *)span.data)->parent_tid;
        }
        t->pid_parent_address=t->pid_reservation && (t->cloning&CLONE_PARENT_SETTID) ? parent : 0;
    }
    if (t->clone_prepared || t->resource_change.namespaces || (r->regs[8]==SYS_clone && (t->cloning&CLONE_UNTRACED))) {
        struct user_pt_regs native=*r;
        if (t->clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
            struct clone_args *args=span.data;
            unsigned depth=md_pid_namespace_depth(t->resource_change.next.pid_children
                ? t->resource_change.next.pid_children : t->resources.pid_children);
            unsigned selected=args->set_tid_size<depth ? args->set_tid_size : depth;
            args->flags&=~((uint64_t)t->resource_change.namespaces|CLONE_UNTRACED|CLONE_INTO_CGROUP);
            native.regs[0]=md_memory_buffer_address(t->memory,&t->buffer);
            args->set_tid_size-=selected;
            args->set_tid=args->set_tid_size ? native.regs[0]+offsetof(struct md_clone_request,tids)
                +selected*sizeof(pid_t) : 0;
            native.regs[1]=sizeof(*args);
            CHECK(native.regs[0]);
        } else native.regs[0]&=~(uint64_t)t->resource_change.namespaces;
        /* Guest debugger policy cannot detach a child from runtime ownership. */
        if (r->regs[8]==SYS_clone) native.regs[0]&=~(uint64_t)CLONE_UNTRACED;
        if (r->regs[8]==SYS_unshare && t->resource_change.copy_mounts) native.regs[0]|=CLONE_FS;
        if (r->regs[8]==SYS_unshare && t->resource_change.identity_pending) native.regs[0]|=CLONE_FS|CLONE_THREAD;
        if (r->regs[8]==SYS_unshare && (t->resource_change.namespaces&CLONE_NEWPID)) native.regs[0]|=CLONE_THREAD;
        if (r->regs[8]==SYS_unshare && (t->resource_change.namespaces&CLONE_NEWIPC)) native.regs[0]|=CLONE_SYSVSEM;
        if (!set_registers(t->pid,&native)) return;
    }
    /* Argument replay is internal. Restore the application's mask only after
     * admission, immediately before kernel clone copies it into the child. */
    if (t->clone_prepared && !restore_mask(t)) return;
    t->fd_running=1; resume(t->pid,0);
}
static void fd_begin(struct thread *t, const struct user_pt_regs *r, unsigned operation) {
    if (t->fd_job==MD_FD_DUPLICATE_BEGIN) {
        /* The adapter already owns discovery/admission and the table turn.
         * Observe its exact duplicate or rollback close under that same turn;
         * unrelated native mutations cannot borrow it. */
        int duplicate=!t->duplicate_attempted && r->regs[8]==t->original.regs[8];
        unsigned arguments=r->regs[8]==SYS_dup ? 1 : 3;
        if (duplicate) for (unsigned i=0;i<arguments;++i)
            if (r->regs[i]!=t->original.regs[i]) duplicate=0;
        int rollback=t->duplicate_attempted && t->duplicate_result>=0
            && r->regs[8]==SYS_close && (int)r->regs[0]==t->duplicate_result;
        if (operation!=MD_INTERCEPT_FD || t->phase!=DISPATCHING || t->fd_running
                || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING || (!duplicate && !rollback)) {
            struct user_pt_regs rejected=*r;
            return_value(t,&rejected,-EPERM); return;
        }
        t->duplicate_attempted=1; t->fd_registers=*r;
        fd_native_start(t); return;
    }
    if (operation==MD_INTERCEPT_CWD && r->regs[8]==SYS_chdir
            && md_fs_buffer_task_pending(t->buffer_call)) {
        CHECK(!t->fd_order.table && !t->fd_running && t->phase==DISPATCHING);
        int error=r->regs[0]!=md_memory_buffer_address(t->memory,&t->buffer)
            +offsetof(struct md_fs_buffer_frame,source) ? -EPERM : md_fs_buffer_task_start(t->buffer_call);
        t->fd_registers=*r;
        if (error) { return_value(t,&t->fd_registers,error); return; }
        t->fd_task=t->fd_running=1;
        resume(t->pid,0); return;
    }
    if (!t->resources.files) { resume(t->pid,0); return; }
    CHECK(!t->fd_order.table && !t->fd_running);
    t->fd_job=0;
    t->fd_capture=operation==MD_INTERCEPT_CAPTURE;
    if (t->fd_capture && !t->terminal_observation) {
        /* Retain terminal registers independently of a guest debugger. Enable
         * once per task lifetime, not around each native descriptor operation. */
        t->terminal_observation=1; t->trace_options_dirty=1;
    }
    t->fd_cwd=operation==MD_INTERCEPT_CWD || (t->fd_capture && (int)r->regs[4]==AT_FDCWD);
    t->fd_registers=*r;
    int ready=md_fd_order_begin(t->resources.files,&t->fd_order,fd_wakeup);
    CHECK(ready>=0);
    if (ready) fd_start(t); else retry_wait(t,RETRY_FD);
    /* EVENT_WAIT: another operation owns this file table. fd_ready signals its
     * exact completion; the launch deadline cancels the owned process tree. */
}
static int fd_close_compact(struct thread *t,unsigned long cookie,const struct ptrace_syscall_info *info) {
    if ((!(cookie&MD_INTERCEPT_CLOSE) && cookie!=MD_INTERCEPT_FD)
            || t->phase!=IDLE || t->fd_job || t->fd_running || t->fd_order.table
            || t->fs_order.fs || !t->resources.files
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid) || md_fd_origins_has_loans(t->resources.files)) return 0;
    int number;
    if (info) {
        if (info->op!=PTRACE_SYSCALL_INFO_SECCOMP || info->arch!=AUDIT_ARCH_AARCH64
                || info->seccomp.nr!=SYS_close) return 0;
        number=(int)info->seccomp.args[0];
    } else {
        /* Only our own filter can encode the descriptor. An application TRACE
         * cookie is not authority, even if it happens to match this encoding. */
        if (t->application_filter || !(cookie&MD_INTERCEPT_CLOSE)) return 0;
        number=(int)(cookie&MD_INTERCEPT_CLOSE_MAX);
    }
    int ready=md_fd_order_begin(t->resources.files,&t->fd_order,fd_wakeup);
    CHECK(ready>=0);
    if (!ready) { CHECK(!md_fd_order_cancel(&t->fd_order)); return 0; }
    CHECK(!md_fd_order_start(&t->fd_order));
    if (md_fd_origins_close_untracked(&t->fd_order,number)) {
        CHECK(!md_fd_order_complete(&t->fd_order));
        if (statistics) { ++statistics->untracked_close; ++statistics->calls[SYS_close].trace; }
        resume(t->pid,0); return 1;
    }
    /* No register rewrite or helper is needed. Keep the same table turn until
     * the actual close result, including EBADF and late writeback errors. */
    t->fd_registers=(struct user_pt_regs){0};
    t->fd_registers.regs[8]=SYS_close;
    t->fd_registers.regs[0]=(uint64_t)(int64_t)number;
    unsigned long args[6]={t->fd_registers.regs[0]};
    CHECK(!md_fd_mutation_prepare(t->resources.files,SYS_close,args,&t->fd_mutation));
    t->fd_cwd=t->fd_capture=0; t->fd_running=1;
    if (statistics) { ++statistics->compact_close; ++statistics->calls[SYS_close].trace; }
    resume(t->pid,0); return 1;
}
static int fd_native_io(struct thread *t,const struct ptrace_syscall_info *info) {
    if (info->arch!=AUDIT_ARCH_AARCH64
            || t->fd_job || t->fd_running || t->fd_order.table || t->fs_order.fs
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    unsigned long nr=info->seccomp.nr;
    int poll=nr==SYS_ppoll || nr==SYS_pselect6;
    int epoll=nr==SYS_epoll_pwait || nr==SYS_epoll_pwait2;
    if (epoll ? info->seccomp.ret_data!=MD_INTERCEPT_READINESS
                && info->seccomp.ret_data!=MD_INTERCEPT_DISPATCH
            : poll ? info->seccomp.ret_data!=MD_INTERCEPT_DISPATCH
            : info->seccomp.ret_data!=MD_INTERCEPT_PROC && info->seccomp.ret_data!=MD_INTERCEPT_WATCH) return 0;
    switch (nr) {
    case SYS_read: case SYS_write: case SYS_readv: case SYS_writev:
    case SYS_pread64: case SYS_pwrite64: case SYS_preadv: case SYS_pwritev:
    case SYS_preadv2: case SYS_pwritev2: case SYS_ppoll: case SYS_pselect6:
    case SYS_epoll_pwait: case SYS_epoll_pwait2: break;
    default: return 0;
    }
    struct md_fd_order turn={0};
    int ready=md_fd_order_begin(t->resources.files,&turn,fd_wakeup);
    CHECK(ready>=0);
    if (!ready) { CHECK(!md_fd_order_cancel(&turn)); return 0; }
    CHECK(!md_fd_order_start(&turn));
    /* The sole stopped table owner cannot replace the slot before CONT. No
     * turn is held across blocking IO, and an absent annotation is no proof.
     * Read actual syscall arguments, never trust an application's TRACE data. */
    /* Poll already classifies under this turn before native fallback. If no
     * object can provide readiness, the same decision needs no guest vector.
     * The kernel retains pointer, limit, timeout and signal-mask validation. */
    int native=0,probe=0;
    if (epoll) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)info->seccomp.args[0],NULL);
        int pure=md_control_epoll_native_wait(object);
        native=pure
            && (!md_control_epoll_from_object(object) || (nr==SYS_epoll_pwait && !(int)info->seccomp.args[3]));
        probe=pure && !native && nr==SYS_epoll_pwait && t->phase==IDLE
            && !info->seccomp.args[4] && !t->application_filter
            && !t->machine.syscall_exit && !t->machine.retry_svc;
        md_fd_object_release(object);
    } else native=poll ? md_fd_origins_native_poll(&turn) : md_fd_origins_exclusive(&turn)
        && md_fd_origins_native_regular(&turn,(int)info->seccomp.args[0]);
    if (!native && nr==SYS_ppoll) {
        /* Common short vectors need no adapter round trip merely because an
         * unrelated FD has a provider. As in the full poll path, classification
         * ends before the kernel wait; the kernel validates the original input.
         * Failed/partial remote reads and larger vectors retain that full path. */
        struct pollfd items[32];
        uint64_t count=info->seccomp.args[1];
        if (count<=sizeof(items)/sizeof(*items)) {
            struct iovec local={items,count*sizeof(*items)},
                remote={(void *)(uintptr_t)info->seccomp.args[0],local.iov_len};
            if (!count || process_vm_readv(t->pid,&local,1,&remote,1,0)==(ssize_t)local.iov_len)
                native=md_fd_origins_native_poll_vector(&turn,items,count);
        }
    }
    CHECK(!md_fd_order_complete(&turn));
    if (probe) {
        /* A ready native queue needs neither an adapter stack nor a captured
         * descriptor. Probe in the caller so signalfd and the application's
         * output pointer retain kernel semantics. An empty result has no
         * effect and falls through to owned blocking admission. */
        struct user_pt_regs regs;
        if (!registers(t->pid,&regs)) return 1;
        t->original=regs; t->epoll_started=md_event_now();
        regs.regs[3]=0;
        if (set_registers(t->pid,&regs)) { t->phase=EPOLL_PROBE; resume(t->pid,0); }
        return 1;
    }
    if (!native) return 0;
    if (statistics) { ++statistics->native_io; ++statistics->calls[nr].trace; }
    resume(t->pid,0); return 1;
}
static void task_action_release(struct task_action *owner) {
    CHECK(owner!=task_action_execution);
    CHECK(!md_buffer_release(&owner->buffer));
    md_fd_selector_release(owner->group);
    md_fd_selector_release(owner->selected_group);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    if (owner->loan) {
        struct task_action **link=&loan_retirements;
        while (*link && *link!=owner) link=&(*link)->next;
        CHECK(*link==owner); *link=owner->next;
        md_fd_loan_release(owner->loan);
        md_fd_origins_unpin(owner->loan_table);
    }
    if (owner->retired) owner->retired(owner->retired_context,-ECANCELED);
    CHECK(task_action_calls); --task_action_calls; free(owner);
}
static void task_action_cleanup(void) {
    /* Tracees have been reaped; only metadata pins and borrowed spans remain. */
    struct task_action *active=task_action_execution;
    if (active) {
        if (active->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,active->actor->pid);
        if (active->requester) active->requester->action=NULL;
        if (active->borrowed) CHECK(!md_task_quiesce_return(
            active->outer_barrier ? active->outer_barrier : task_action_barrier,active->actor->pid));
        if (active->broker_borrowed) {
            md_broker_native_return(broker,active->actor->pid); active->broker_borrowed=0;
        }
        if (task_action_barrier) CHECK(!md_task_quiesce_release(task_action_barrier));
        task_action_execution=NULL;
        task_action_release(active);
    }
    while (loan_retirements) {
        if (loan_retirements->requester) loan_retirements->requester->action=NULL;
        task_action_release(loan_retirements);
    }
}
static void task_action_finish(struct task_action *owner,long result) {
    struct thread *t=owner->requester;
    void (*complete)(struct thread *,long)=owner->complete;
    struct user_pt_regs r=owner->continuation;
    pid_t waiter=owner->loan_waiter;
    struct md_broker_retirement retirement=owner->retirement;
    void (*retired)(void *,long)=owner->retired;
    void *retired_context=owner->retired_context;
    owner->retired=NULL;
    if (t) { retry_clear(t,RETRY_TASK_ACTION); t->action=NULL; }
    task_action_release(owner);
    if (waiter) readiness_loan_closed(waiter,result);
    if (retirement.complete) retirement.complete(retirement.context,(int)result);
    if (retired) retired(retired_context,result);
    if (t && !retired) {
        if (complete) complete(t,result);
        else {
            r.regs[0]=result;
            if (set_registers(t->pid,&r)) resume(t->pid,0);
        }
    }
    adapter_progress();
}
static void task_action_discard(struct thread *t) {
    struct task_action *owner=t->action;
    if (!owner) return;
    t->action=NULL; owner->requester=NULL;
    if (owner->loan) { if (!owner->retired) owner->loan_turn=NULL; return; }
    /* A foreign actor can still be using this payload. Its exact completion
     * retires the detached request, independently of requester death. */
    if (owner!=task_action_execution) task_action_release(owner);
}
static void task_action_unpark(void) {
    for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid) {
        if (peer->memory_notification) {
            struct seccomp_notif *n=peer->memory_notification; peer->memory_notification=NULL;
            handle_notification(n); free(n);
        }
        if (peer->memory_resume) {
            int signal=peer->memory_signal; peer->memory_resume=peer->memory_signal=0;
            resume(peer->pid,signal);
        }
    }
    /* A resource can become ready while a barrier prevents its continuation.
     * Its eventfd was already acknowledged then; releasing the barrier is the
     * new readiness event, even when no resource owner has anything left to signal. */
    if (filesystem) memory_completions_pending=1;
    CHECK(!md_event_signal(fd_ready));
}
static void loan_failed(struct task_action *owner,long result) {
    /* Retain custody until kernel teardown when retirement cannot be verified. */
    if (cancelling) return;
    fprintf(stderr,"Shroot: cannot retire temporary FD %d: %s\n",owner->number,strerror((int)-result));
    cancel_tree(125);
}
static void task_action_call_complete(long result,int retry) {
    struct task_action *owner=task_action_execution;
    struct thread *actor=owner->actor;
    if (owner->checkpoint_suspended) {
        md_task_checkpoint_restore(&checkpoints,actor->pid); owner->checkpoint_suspended=0;
    }
    if (owner->loan) {
        if (!retry && result!=-ESRCH) {
            struct md_fd_mutation close={.nr=owner->native.arguments.number,
                .args={owner->native.arguments.value[0],owner->native.arguments.value[1],owner->native.arguments.value[2]}};
            CHECK(!md_fd_mutation_finish(&close,owner->loan_table,result));
            struct md_fd_order *turn=owner->loan_turn ? owner->loan_turn : &owner->files;
            int remaining=md_fd_origins_loan_matches(turn,owner->number,owner->loan);
            if (remaining<0) result=remaining;
            else if (!remaining) result=0;
            else if (result==-EBADF)
                result=md_fd_origins_close_range(owner->loan_table,owner->number,owner->number,0);
        }
    } else if (!result && !retry) result=md_fd_selector_installed(owner->group,owner->kind,
        owner->selector_number,owner->selector_instructions);
    if (owner->borrowed) CHECK(!md_task_quiesce_return(
        owner->outer_barrier ? owner->outer_barrier : task_action_barrier,actor->pid));
    if (owner->broker_borrowed) {
        md_broker_native_return(broker,actor->pid); owner->broker_borrowed=0;
    }
    if (task_action_barrier) CHECK(!md_task_quiesce_release(task_action_barrier));
    if (owner->synthetic && !owner->outer_barrier) actor->kernel_stopped=0;
    CHECK(!md_buffer_release(&owner->buffer));
    md_fd_selector_release(owner->group); owner->group=NULL;
    owner->actor=NULL; owner->borrowed=owner->synthetic=0; owner->filter=(struct md_task_filter){0};
    task_action_releasing=1;
    task_action_execution=NULL;
    task_action_unpark();
    task_action_releasing=0;
    if (owner->loan) {
        if (retry || result==-ESRCH) return;
        if (result<0) {
            loan_failed(owner,result);
            return;
        }
        task_action_finish(owner,result); return;
    }
    if (!owner->requester || (result<0 && !retry)) task_action_finish(owner,result);
    else if (result>0) {
        if (!owner->remote && !owner->selected_group) task_action_finish(owner,result);
        else {
            owner->rejected_group=actor->tgid; owner->rejected_task=(pid_t)result;
            retry_wait(owner->requester,RETRY_TASK_ACTION);
        }
    } else if (owner->selected_group && !retry) task_action_finish(owner,0);
    else retry_wait(owner->requester,RETRY_TASK_ACTION);
}
static void task_action_call_start(struct task_action *owner) {
    struct thread *actor=owner->actor;
    struct user_pt_regs saved=owner->continuation;
    enum md_task_call_boundary boundary=MD_TASK_CALL_USER;
    if (owner->remote) {
        struct md_task_quiesce *barrier=owner->outer_barrier ? owner->outer_barrier : task_action_barrier;
        if (!md_task_quiesce_ready(barrier)) return;
        struct md_task_quiesce_stop stop;
        int error=md_task_quiesce_borrow(barrier,actor->pid,&stop);
        if (error) { task_action_call_complete(error,error==-ESRCH); return; }
        owner->borrowed=1;
        owner->synthetic=stop.kind==MD_TASK_QUIESCE_SYNTHETIC;
        if (stop.kind==MD_TASK_QUIESCE_EVENT) {
            unsigned event=(unsigned)stop.status>>16;
            /* Lifecycle and signal delivery belong to the normal dispatcher.
             * Replay them before selecting another exact task boundary. */
            if (event!=PTRACE_EVENT_SECCOMP && (event || WSTOPSIG(stop.status)!=(SIGTRAP|0x80))) {
                task_action_call_complete(0,1); return;
            }
        }
        struct ptrace_syscall_info info={0};
        if (!registers(actor->pid,&saved)) { task_action_call_complete(-ESRCH,1); return; }
        if (native_trace(PTRACE_GET_SYSCALL_INFO,actor->pid,(void *)sizeof(info),&info)<0) {
            task_action_call_complete(-errno,0); return;
        }
        boundary=(enum md_task_call_boundary)info.op;
    }
    int checkpoint=md_task_checkpoint_suspend(&checkpoints,actor->pid);
    if (checkpoint<0) { task_action_call_complete(checkpoint,0); return; }
    owner->checkpoint_suspended=1;
    if (checkpoint && boundary==MD_TASK_CALL_ENTRY) boundary=MD_TASK_CALL_EMULATED_ENTRY;
    TRACE("PROBE selector-start requester=%d actor=%d remote=%d synthetic=%d kind=%u fd=%d boundary=%d pc=%llx nr=%llu value=%lld\n",
        owner->requester ? owner->requester->pid : 0,actor->pid,owner->remote,owner->synthetic,
        (unsigned)owner->kind,owner->number,boundary,
        (unsigned long long)saved.pc,(unsigned long long)saved.regs[8],(long long)saved.regs[0]);
    if (owner->loan) {
        struct md_task_call_arguments args={.number=SYS_close,.value={(unsigned long)owner->number}};
        int error=md_task_call_begin(&owner->native,native_trace,actor->pid,boundary,
            &saved,abi.task_call,abi.task_return,&args);
        if (error && owner->native.stage) { CHECK(error==-ESRCH); actor->resume_lost=1; return; }
        if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
        actor->kernel_stopped=0; return;
    }
    int error=md_buffers_take_reserved(md_memory_buffers(actor->memory),&owner->buffer);
    struct md_buffer_span span;
    if (!error) error=md_buffer_span(&owner->buffer,&span);
    uintptr_t address=error ? 0 : md_memory_buffer_address(actor->memory,&owner->buffer);
    if (!error && (!address || span.size<sizeof(struct md_task_filter_image))) error=-EINVAL;
    if (!error) {
        /* seccomp reports the PC after SVC, not the address used to enter it. */
        struct md_fd_filter_gates gates={RAW_GATE,abi.watch_return,abi.task_return,abi.native_return};
        struct md_task_filter_image *image=span.data;
        error=md_fd_filter_build(owner->kind,owner->number,&gates,&image->code);
        if (!error) {
            owner->selector_instructions=image->code.count;
            owner->selector_number=md_fd_selector_target(owner->group,owner->number,image->code.count);
            if (owner->selector_number< -1) error=owner->selector_number;
        }
        if (!error) error=md_task_filter_begin(&owner->filter,native_trace,actor->pid,boundary,
            &saved,abi.task_call,abi.task_return,span.data,address,owner->kind,owner->selector_number,&gates);
    }
    if (error && owner->filter.task.stage) {
        CHECK(error==-ESRCH); actor->resume_lost=1; return;
    }
    if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
    actor->kernel_stopped=0;
}
static int selector_has(struct thread *t,unsigned kind,int number) {
    int ready=md_fd_selector_has(t->fd_selector,kind,number);
    if (ready<=0 || number<0) return ready;
    for (struct thread *peer=threads;peer;peer=peer->next)
        if (peer->pid && peer->born && peer->resources.files==t->resources.files
                && peer->fd_selector!=t->fd_selector) {
            ready=md_fd_selector_has(peer->fd_selector,kind,number);
            if (ready<=0) return ready;
        }
    return 1;
}
static int task_action_borrowable(struct thread *p,struct thread *requester) {
    /* A queued table user is stopped before its operation. Other candidates
     * retain their exact kernel continuation. Never borrow an active worker,
     * mutation or newborn awaiting its first stop. */
    int waiter=p->kernel_stopped && (p->retries[1].mask==RETRY_FD
        || (p->phase==PROC_LOOKUP && md_fd_order_state(&p->proc_lookup)==MD_RESOURCE_ORDER_QUEUED)
        || (p->readiness && md_fd_order_state(&p->readiness->files)==MD_RESOURCE_ORDER_QUEUED)
        || (p->action && !task_action_call(p->action)->stage));
    int traced=md_debugger_traced(debugger,p->pid);
    /* A debugger can share files with its stopped inferior (crash collectors
     * commonly do). Its own parked request is the only control owner, so the
     * exact held continuation can install selectors without exposing adapter
     * registers or resuming guest code. Unrelated debuggers remain excluded. */
    int debug_held=traced && requester && p->kernel_stopped && debugger_at_boundary(p)
        && md_debugger_stopped_by(debugger,p->pid,requester->pid);
    /* Tracing does not own a running task. Borrow its exact kernel boundary
     * while preserving debugger state, just as for an untraced task. Excluding
     * it could strand a temporary FD behind the table turn needed by every
     * peer. Published debugger stops and pending interrupts remain excluded. */
    int debug_running=traced && md_debugger_running(debugger,p->pid);
    return p->ready && !birth_stop_pending(p) && !p->resume_lost && !p->terminal_stop
        && !p->listening && !p->vfork_child && !p->interrupt_pending && !p->signal_return_pending
        && !p->mapping && !p->memory_capture && !p->fd_running && (!traced || debug_held || debug_running)
        && (waiter || debug_held || (p->freezer_resume && debugger_at_boundary(p))
            || (!p->kernel_stopped && (p->phase==IDLE || p->phase==DISPATCHING
            || p->phase==WATCH_READ || p->phase==EPOLL_WAIT || p->phase==PROCESS_OBSERVING)));
}
static void task_action_start(struct thread *t) {
    retry_clear(t,RETRY_TASK_ACTION);
    struct task_action *owner=t->action;
    CHECK(owner && !task_action_call(owner)->stage);
    if (owner->loan) { loan_start(owner); return; }
    if (quiescence_busy(NULL)) { retry_wait(t,RETRY_TASK_ACTION); return; }
    if (owner->rejected_task) {
        if (md_task_helper_retiring(helpers,owner->rejected_group,owner->rejected_task)) {
            retry_wait(t,RETRY_TASK_ACTION); return;
        }
        if (!syscall(SYS_tgkill,owner->rejected_group,owner->rejected_task,0) || errno!=ESRCH) {
            task_action_finish(owner,-ENOTSUP); return;
        }
        owner->rejected_task=0;
    }
    /* The request may already own this turn for dup or received publication.
     * Otherwise acquire it before freezing the set of CLONE_FILES holders.
     * A copied table inherits only filters captured before its kernel clone. */
    if (!owner->selected_group && owner->number>=0 && md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING
            && md_fd_order_state(&t->buffer_return.files)!=MD_RESOURCE_ORDER_RUNNING) {
        if (!owner->files.table) {
            int ready=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
            if (ready<0) { task_action_finish(owner,ready); return; }
        }
        if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_QUEUED) {
            retry_wait(t,RETRY_TASK_ACTION); return;
        }
        if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_READY)
            CHECK(!md_fd_order_start(&owner->files));
    }
    struct thread *candidate=NULL;
    int ready=md_fd_selector_has(owner->selected_group ? owner->selected_group : t->fd_selector,owner->kind,owner->number);
    if (ready<0) { task_action_finish(owner,ready); return; }
    if (ready && owner->selected_group) { task_action_finish(owner,0); return; }
    if (!ready && (!owner->selected_group || owner->selected_group==t->fd_selector)) candidate=t;
    int pending=0;
    for (struct thread *peer=threads;!candidate && owner->number>=0 && peer;peer=peer->next)
        if (peer->pid && peer->born && (owner->selected_group
                ? peer->fd_selector==owner->selected_group : peer->resources.files==t->resources.files)) {
            ready=md_fd_selector_has(peer->fd_selector,owner->kind,owner->number);
            if (ready<0) { task_action_finish(owner,ready); return; }
            if (ready) continue;
            pending=1;
            if (task_action_borrowable(peer,t)) candidate=peer;
        }
    if (!candidate) {
        if (pending) retry_wait(t,RETRY_TASK_ACTION);
        else task_action_finish(owner,owner->selected_group ? -ESRCH : 0);
        return;
    }
    owner->actor=candidate; owner->remote=candidate!=t;
    owner->group=md_fd_selector_fork(candidate->fd_selector,1);
    task_action_execution=owner;
    if (owner->remote) {
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!md_task_quiesce_add(task_action_barrier,candidate->pid,candidate->kernel_stopped,candidate->kernel_resume));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
    }
    task_action_call_start(owner);
}
static void task_action_schedule(void) {
    if (!task_action_calls || memory_execution) return;
    if (task_action_execution) {
        if (!task_action_call(task_action_execution)->stage) task_action_call_start(task_action_execution);
        return;
    }
    for (struct task_action *s=loan_retirements,*next;s;s=next) {
        next=s->next; loan_start(s);
        if (task_action_execution) return;
    }
    for (struct thread *t=threads;t;t=t->next) if (t->pid && t->action) {
        task_action_start(t);
        if (task_action_execution) return;
    }
}
static int loan_borrow(struct task_action *owner,struct thread *p,int *alive,int *stopped) {
    if (!p || !p->pid || p->resources.files!=owner->loan_table || p->terminal_stop || p->resume_lost) return 0;
    if (p->signal_return_pending || (p!=owner->requester && p->pid!=owner->retirement.task
            && !task_action_borrowable(p,owner->requester))) {
        *alive=1; return 0;
    }
    *stopped=p->kernel_stopped;
    int borrowed=md_broker_native_borrow(broker,p->pid,stopped);
    if (borrowed==-ESRCH) return 0;
    *alive=1;
    if (borrowed<0) return 0;
    owner->broker_borrowed=borrowed;
    return 1;
}
static void loan_start(struct task_action *owner) {
    if (cancelling || quiescence_busy(owner->retired_context)) return;
    struct md_fd_order *turn=owner->loan_turn ? owner->loan_turn : &owner->files;
    if (!turn->table) {
        int error=md_fd_order_begin(owner->loan_table,turn,fd_wakeup);
        if (error<0) { loan_failed(owner,error); return; }
    }
    if (md_fd_order_state(turn)==MD_RESOURCE_ORDER_QUEUED) return;
    if (md_fd_order_state(turn)==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(turn));
    int exists=md_fd_origins_loan_matches(turn,owner->number,owner->loan);
    if (exists<0) { loan_failed(owner,exists); return; }
    if (!exists) { task_action_finish(owner,0); return; }
    struct thread *candidate=NULL;
    int alive=0,stopped=0;
    /* The requester already owns an exact stopped continuation. A peer can
     * instead have a deferred signal/lifecycle event which must not be replayed
     * while an outer mapping barrier still protects the borrowed descriptor. */
    if (loan_borrow(owner,owner->requester,&alive,&stopped)) candidate=owner->requester;
    for (struct thread *p=threads;!candidate && p;p=p->next) {
        if (p!=owner->requester && loan_borrow(owner,p,&alive,&stopped)) candidate=p;
    }
    if (!candidate) {
        /* No task can retain the kernel table. A metadata pin is not an owner. */
        if (!alive) task_action_finish(owner,0);
        return;
    }
    owner->actor=candidate; owner->remote=1;
    task_action_execution=owner;
    owner->outer_barrier=mapping_execution ? mapping_barrier : NULL;
    if (!owner->outer_barrier) {
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!md_task_quiesce_add(task_action_barrier,candidate->pid,stopped,candidate->kernel_resume));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
    }
    task_action_call_start(owner);
}
static void loan_retire(struct task_action *owner,struct thread *requester,
        struct md_fd_order *turn,void (*complete)(struct thread *,long)) {
    md_fd_loan_end(owner->loan);
    owner->requester=requester; owner->loan_turn=turn; owner->complete=complete;
    owner->next=loan_retirements; loan_retirements=owner; ++task_action_calls;
    if (requester) { CHECK(!requester->action); requester->action=owner; }
    if (!turn) CHECK(md_fd_order_begin(owner->loan_table,&owner->files,fd_wakeup)>=0);
    CHECK(!md_event_signal(fd_ready));
}
static int broker_retire(const struct md_broker_retirement *request) {
    struct task_action *owner=calloc(1,sizeof(*owner));
    if (!owner) return -ENOMEM;
    int number=-1;
    int error=md_fd_origins_loan_range(request->turn,request->number,request->number,&number,&owner->loan);
    if (error!=1 || number!=request->number || owner->loan!=request->loan) {
        md_fd_loan_release(owner->loan); free(owner); return error<0 ? error : -ESTALE;
    }
    owner->loan_table=md_fd_origins_pin(request->turn->table);
    owner->number=number; owner->retirement=*request;
    loan_retire(owner,NULL,request->turn,NULL);
    return 0;
}
static void task_call_resume(struct thread *t,struct md_task_call *call,int signal) {
    int error=md_task_call_resume(call,signal);
    if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
    else t->kernel_stopped=0;
}
static int task_call_signal(struct thread *t,struct md_task_call *call,int status) {
    if (WIFSTOPPED(status) && !((unsigned)status>>16) && WSTOPSIG(status)==SIGSTOP) {
        task_call_resume(t,call,SIGSTOP); return 1;
    }
    if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
        t->interrupt_pending=0;
        if (t->listening) { t->listening=0; group_wakes++; }
    }
    if (!WIFSTOPPED(status) || (unsigned)status>>16 || WSTOPSIG(status)!=SIGTRAP) return 0;
    siginfo_t info;
    if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"task-call-signal")) return 1;
    if (info.si_code>0) return 0;
    int retained=md_signal_delivery_retain(&t->signal_delivery,t->pid,&info); CHECK(retained>=0);
    task_call_resume(t,call,retained);
    return 1;
}
static int task_action_event(pid_t pid,int status,const struct rusage *usage) {
    struct task_action *owner=task_action_execution;
    if (!owner) return 0;
    struct thread *t=find_thread(pid);
    unsigned event=(unsigned)status>>16;
    if (t!=owner->actor) return 0;
    struct md_task_call *call=task_action_call(owner);
    if (!call->stage) {
        TRACE("PROBE selector-stop pid=%d status=%x\n",pid,status);
        CHECK(owner->remote && !quiesce_event(task_action_barrier,pid,status,usage)); return 1;
    }
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) {
        if (!owner->remote) { t->action=NULL; owner->requester=NULL; }
        task_action_call_complete(-ESRCH,0); return 0;
    }
    if (task_call_signal(t,call,status)) return 1;
    int owned_interrupt=md_task_call_expects_interrupt(call);
    if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP && !owned_interrupt) {
        if (statistics) ++statistics->stops;
        task_call_resume(t,call,0);
        return 1;
    }
    if (event!=PTRACE_EVENT_SECCOMP && !(event==PTRACE_EVENT_STOP && owned_interrupt)
            && (event || WSTOPSIG(status)!=(SIGTRAP|0x80))) return 0;
    if (statistics) ++statistics->stops;
    int result=md_task_call_event(call,status);
    TRACE("PROBE task-action pid=%d status=%x stage=%u boundary=%d loan=%d ready=%d result=%ld\n",
        pid,status,call->stage,call->boundary,owner->loan!=NULL,result,call->result);
    if (result==-EPROTO) {
        struct ptrace_syscall_info info={0};
        struct user_pt_regs actual={0};
        registers(pid,&actual);
        native_trace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info);
        fprintf(stderr,"guest-runtime: task-action mismatch pid=%d status=%x phase=%d stage=%u boundary=%d loan=%d active=%ld op=%u pc=%llx/%llx nr=%llu/%llu x0=%llu/%llu x7=%llu/%llu\n",
            pid,status,t->phase,call->stage,call->boundary,owner->loan!=NULL,call->active_number,
            info.op,(unsigned long long)actual.pc,(unsigned long long)call->entry.pc,
            (unsigned long long)actual.regs[8],(unsigned long long)call->entry.regs[8],
            (unsigned long long)actual.regs[0],(unsigned long long)call->entry.regs[0],
            (unsigned long long)actual.regs[7],(unsigned long long)call->entry.regs[7]);
    }
    if (result<0) { CHECK(result==-ESRCH); t->resume_lost=1; return 1; }
    if (result) {
        t->kernel_stopped=1;
        if (owner->loan && call->arguments.number==SYS_close
                && (call->result==-EPERM || call->result==-EACCES || call->result==-ENOSYS)) {
            struct md_task_call_arguments args={SYS_close_range,
                {(unsigned long)owner->number,(unsigned long)owner->number,0}};
            result=md_task_call_again(call,&args);
            if (result) { CHECK(result==-ESRCH); t->resume_lost=1; }
            else t->kernel_stopped=0;
            return 1;
        }
        int single=!md_task_helpers_group_busy(helpers,t->tgid);
        for (struct thread *peer=threads;single && peer;peer=peer->next)
            if (peer!=t && peer->pid && peer->tgid==t->tgid) single=0;
        if (!owner->loan && call->result==-EPERM && !owner->filter.thread_only && single) {
            TRACE("PROBE selector-thread-retry pid=%d boundary=%d\n",pid,call->boundary);
            result=md_task_filter_retry_thread(&owner->filter);
            if (result) { CHECK(result==-ESRCH); t->resume_lost=1; }
            else t->kernel_stopped=0;
            return 1;
        }
        task_action_call_complete(call->result,0);
    }
    return 1;
}
static void fd_control(struct thread *t, struct user_pt_regs *r) {
    unsigned operation=(unsigned)r->regs[0];
    uint64_t id=r->regs[1];
    long result=0;
    if (operation==MD_FD_KIND || operation==MD_FD_SELECTORS) {
        if (t->phase!=DISPATCHING || id || (long)r->regs[2]<INT_MIN
                || (long)r->regs[2]>INT_MAX || r->regs[3]) result=-EINVAL;
        else {
            if (operation==MD_FD_SELECTORS) {
                struct md_fd_observation view;
                result=md_fd_origins_observe(t->resources.files,(int)r->regs[2],&view);
                r->regs[1]=result ? 0 : view.generation;
                if (!result) result=view.selectors;
            } else {
                struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)r->regs[2],NULL);
                struct md_proc_file *file=md_proc_file_from_object(object);
                result=md_proc_file_filesystem_paths(file) ? MD_DESCRIPTOR_FILESYSTEM
                    : file ? md_proc_file_kind(file)+1 : md_descriptor_inode_is(object) ? MD_DESCRIPTOR_ANONYMOUS : 0;
                md_fd_object_release(object);
            }
        }
    } else if (operation==MD_FD_DUPLICATE_BEGIN) {
        long nr=t->original.regs[8];
        unsigned command=(unsigned)t->original.regs[1];
        int duplicate=nr==SYS_dup || nr==SYS_dup3
            || (nr==SYS_fcntl && (command==F_DUPFD || command==F_DUPFD_CLOEXEC));
        if (t->phase!=DISPATCHING || !duplicate || !id || r->regs[2] || r->regs[3]
                || t->fd_order.table || t->fd_running || t->fd_job) result=-EINVAL;
        else {
            t->fd_registers=*r; t->fd_job=operation;
            t->duplicate_attempted=0; t->duplicate_result=-1;
            int ready=md_fd_order_begin(t->resources.files,&t->fd_order,fd_wakeup);
            if (ready<0) { t->fd_job=0; result=ready; }
            else { if (ready) fd_start(t); else retry_wait(t,RETRY_FD); return; }
        }
    } else if (operation==MD_FD_DUPLICATE_END) {
        if (id || r->regs[2] || r->regs[3] || t->fd_job!=MD_FD_DUPLICATE_BEGIN || t->fd_running
                || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0;
            t->duplicate_attempted=0; t->duplicate_result=-1;
        }
    } else if (operation==MD_FD_SELECTOR_EXIT) {
        if (t->phase!=DISPATCHING || !id || id>INT_MAX || r->regs[2] || r->regs[3]) result=-EINVAL;
        else if (md_task_helper_retiring(helpers,t->tgid,(pid_t)id)) {
            /* EVENT_WAIT: the exact helper's terminal wait event releases
             * this caller. The launch deadline bounds failure/cancellation. */
            t->selector_exit=(pid_t)id;
            retry_wait(t,RETRY_TASK_ACTION);
            TRACE("PROBE selector exit wait pid=%d helper=%d\n",t->pid,t->selector_exit);
            return;
        } else if (syscall(SYS_tgkill,t->tgid,(pid_t)id,0)==-1 && errno==ESRCH) {
            /* The exit can already have been collected before this trap. */
            result=0;
        } else result=-ENOTSUP;
    } else if (operation==MD_FD_SELECTOR_QUERY || operation==MD_FD_SELECTOR_INSTALL) {
        if (t->phase!=DISPATCHING || id>=MD_FD_SELECTOR_KINDS || (long)r->regs[2]< -1
                || (long)r->regs[2]>INT_MAX || r->regs[3]) result=-EINVAL;
        else {
            result=selector_has(t,(unsigned)id,(int)r->regs[2]);
            if (operation==MD_FD_SELECTOR_INSTALL && result>=0) {
                if (result) result=0;
                else if (t->action) result=-EBUSY;
                else if (!(t->action=calloc(1,sizeof(*t->action)))) result=-ENOMEM;
                else {
                    ++task_action_calls;
                    t->action->requester=t;
                    t->action->continuation=*r;
                    t->action->kind=(enum md_fd_selector_kind)id;
                    t->action->number=(int)r->regs[2];
                    task_action_start(t); return;
                }
            }
        }
    } else if (operation==MD_FD_INPUT || operation==MD_FD_RECEIVE || operation==MD_FD_USE || operation==MD_FD_PAIR_BEGIN) {
        if ((operation==MD_FD_PAIR_BEGIN && (t->phase!=DISPATCHING
                    || (t->original.regs[8]!=SYS_pipe2 && (t->original.regs[8]!=SYS_socketpair
                        || !md_network_namespace_managed(t->resources.network)))))
                || t->fd_order.table || (operation==MD_FD_USE || operation==MD_FD_PAIR_BEGIN ? id || r->regs[2] || r->regs[3]
                : operation==MD_FD_INPUT ? t->fd_exchange!=NULL
                : !id || id!=md_fd_exchange_id(t->fd_exchange))) result=-EINVAL;
        else if (t->resources.files) {
            t->fd_registers=*r; t->fd_job=operation;
            int ready=md_fd_order_begin(t->resources.files,&t->fd_order,fd_wakeup);
            if (ready<0) { t->fd_job=0; result=ready; }
            else { if (ready) fd_start(t); else retry_wait(t,RETRY_FD); return; }
        }
    } else if (operation==MD_FD_PAIR_END) {
        if (id || t->fd_job!=MD_FD_PAIR_BEGIN || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            int ends[2]={(int)r->regs[2],(int)r->regs[3]};
            if (ends[0]==-1 && ends[1]==-1) {
                for (unsigned i=0;i<2;++i) md_fd_origins_finish(&t->pair_updates[i],-1);
            } else result=md_fd_origins_finish_batch(t->pair_updates,2,ends,2);
            for (unsigned i=0;i<2;++i) md_fd_origins_finish(&t->pair_updates[i],-1);
            CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0;
        }
    } else if (operation==MD_FD_RELEASE) {
        if (id || r->regs[2] || r->regs[3] || t->fd_job!=MD_FD_USE
                || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else { CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0; }
    } else if (!id || id!=md_fd_exchange_id(t->fd_exchange)) result=-ESTALE;
    else if (operation==MD_FD_SENT || operation==MD_FD_RECEIVED) {
        if (t->fd_job!=(operation==MD_FD_SENT ? MD_FD_INPUT : MD_FD_RECEIVE)
                || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            if (operation==MD_FD_RECEIVED) result=md_fd_exchange_received(t->fd_exchange);
            CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0;
        }
    } else if (operation==MD_FD_END) {
        if (t->fd_order.table) result=-EBUSY;
        else { md_fd_exchange_close(fd_exchanges,t->fd_exchange); t->fd_exchange=NULL; }
    } else result=-EINVAL;
    if (result<0) TRACE("PROBE fd-control pid=%d op=%u id=%llu result=%ld\n",t->pid,operation,
        (unsigned long long)id,result);
    r->regs[0]=result;
    if (operation==MD_FD_DUPLICATE_END && !result && close_begin(t,r,0)) return;
    if (set_registers(t->pid,r)) resume(t->pid,0);
}
static void buffer_reply_signal(struct thread *t, long result, const siginfo_t *signal) {
    struct user_pt_regs r=t->buffer_registers;
    if (r.regs[0]==MD_BUFFER_PROC_START || r.regs[0]==MD_BUFFER_EPOLL_WAIT)
        r.regs[1]=md_memory_buffer_address(t->memory,&t->buffer);
    r.regs[0]=result;
    if (signal) CHECK(!md_signal_delivery_hold(&t->signal_delivery,signal));
    if (set_registers(t->pid,&r)) resume(t->pid,0);
}
static void buffer_reply(struct thread *t, long result) {
    buffer_reply_signal(t,result,NULL);
}
static void readiness_unwatch(struct thread *t) {
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
static void readiness_release(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (!o) return;
    CHECK(!md_fd_install_cancel(&o->delivery));
    if (o->retirement) {
        if (o->loan_number>=0) loan_retire(o->retirement,NULL,NULL,NULL);
        else {
            md_fd_loan_end(o->retirement->loan);
            md_fd_loan_release(o->retirement->loan);
            md_fd_origins_unpin(o->retirement->loan_table);
            free(o->retirement);
        }
        o->retirement=NULL;
    }
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
static void readiness_reply(struct thread *t,long result);
static int readiness_frame(struct thread *t,struct md_buffer_span *span) {
    if (!t->readiness->detached) return md_buffer_span(&t->buffer,span);
    *span=(struct md_buffer_span){.data=t->readiness->detached,.size=sizeof(*t->readiness->detached)};
    return 0;
}
static int readiness_restore(struct thread *t,int continuation,long result) {
    struct readiness_owner *o=t->readiness;
    if (!o->detached) return 1;
    int error=md_buffers_take_sized(md_memory_buffers(t->memory),sizeof(*o->detached),&t->buffer);
    if (error==-EAGAIN) {
        o->restoring=continuation; o->restore_result=result;
        retry_wait(t,RETRY_READINESS); return 0;
    }
    if (error) { readiness_reply(t,error); return 0; }
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->buffer,&span));
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
static void readiness_reply(struct thread *t,long result) {
    struct readiness_owner *o=t->readiness;
    if (o->retirement && o->loan_number>=0) {
        o->finishing=1; o->finish_result=result;
        struct task_action *retirement=o->retirement;
        o->retirement=NULL; o->loan_number=-1;
        loan_retire(retirement,t,NULL,readiness_retired); return;
    }
    retry_clear(t,RETRY_READINESS);
    readiness_admission_release(o,0);
    if (o->files.table && md_fd_order_cancel(&o->files)==1) CHECK(!md_fd_order_complete(&o->files));
    o->done=1; t->phase=DISPATCHING; t->buffer_registers=o->continuation;
    if (o->poll && result==MD_EPOLL_NATIVE) {
        /* Classification consumed input only. There is nothing to copy back
         * before the caller's native wait, so finish without an adapter ACK. */
        dispatch_complete(t,buffer_result_finish(t,0,MD_CALL_NATIVE));
        adapter_progress(); return;
    }
    if (o->poll && result!=MD_EPOLL_NATIVE) {
        struct md_buffer_span span;
        if (!md_buffer_span(&t->buffer,&span)) readiness_poll_elapsed(t,span.data);
    }
    if (o->detached) {
        if (result<=0 || o->detached->native_output) {
            dispatch_complete(t,buffer_result_finish(t,0,result));
            adapter_progress(); return;
        }
        if (!readiness_restore(t,READINESS_RESTORE_REPLY,result)) return;
    }
    buffer_reply(t,result);
}
static void readiness_deliver(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    retry_clear(t,RETRY_READINESS);
    int installed=md_fd_install_progress(&o->delivery);
    if (installed==MD_FD_INSTALL_WAIT) { retry_wait(t,RETRY_READINESS); return; }
    CHECK(!o->delivery.retire_error);
    if (installed==MD_FD_INSTALL_CANCELLED) return;
    if (installed<0) {
        struct seccomp_notif_resp reply={.id=o->delivery.notification,.error=installed};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    } else o->retirement->number=o->loan_number=installed;
}
static void readiness_request_loan(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (!readiness_restore(t,READINESS_RESTORE_LOAN,0)) return;
    if (!o->retirement) {
        struct task_action *s=calloc(1,sizeof(*s));
        if (!s) { readiness_reply(t,-ENOMEM); return; }
        s->loan=md_fd_loan_new(t->resources.files);
        if (!s->loan) { free(s); readiness_reply(t,-ENOMEM); return; }
        s->loan_table=md_fd_origins_pin(t->resources.files);
        s->number=-1; o->retirement=s;
    }
    t->phase=DISPATCHING; t->buffer_registers=o->continuation;
    buffer_reply(t,MD_EPOLL_LOAN);
}
static void readiness_loan_closed(pid_t pid,long result) {
    struct thread *waiter=find_thread(pid);
    if (waiter && waiter->readiness && waiter->readiness->parked) {
        struct readiness_owner *o=waiter->readiness;
        o->evicting=o->parked=0;
        if (result || o->finishing) readiness_reply(waiter,result ? result : o->finish_result);
        else readiness_request_loan(waiter);
    }
}
static void fd_loan_closed(struct thread *t,long result) {
    if (!result && t->fd_job==MD_FD_DUPLICATE_BEGIN) {
        /* fd_start validated discovery before retirement. This close owns the
         * same uninterrupted table turn: do not retry our own generation bump
         * and let the waiter lend the just-retired slot again indefinitely. */
        CHECK(md_fd_order_state(&t->fd_order)==MD_RESOURCE_ORDER_RUNNING);
        struct md_fd_observation view;
        result=md_fd_origins_observe(t->resources.files,(int)t->original.regs[0],&view);
        if (!result) t->fd_registers.regs[1]=view.generation;
    }
    if (result) { fd_discard(t); return_value(t,&t->fd_registers,result); }
    else fd_start(t);
}
static int readiness_evict(struct thread *mutator,struct md_fd_loan *loan) {
    for (struct thread *p=threads;p;p=p->next) {
        struct readiness_owner *o=p->readiness;
        if (!p->pid || !o || !o->retirement || o->retirement->loan!=loan) continue;
        o->evicting=1;
        if (!o->parked) {
            /* EVENT_WAIT: the calling thread leaves its exact native wait
             * before a destructive table mutation. No table lock spans IO. */
            if (p->phase==EPOLL_WAIT && !p->socket_waking) {
                p->socket_waking=1;
                tracee_request(p,PTRACE_INTERRUPT,NULL,NULL,"epoll-loan-revoke");
            }
            retry_wait(mutator,RETRY_FD); return 0;
        }
        struct task_action *retirement=o->retirement;
        o->retirement=NULL; o->loan_number=-1;
        retirement->loan_waiter=p->pid;
        loan_retire(retirement,mutator,&mutator->fd_order,fd_loan_closed);
        return 0;
    }
    /* An already finished wait queued its own retirement. Yield this not-yet
     * executed mutation behind that close instead of holding up its owner. */
    for (struct task_action *s=loan_retirements;s;s=s->next) if (s->loan==loan) {
        CHECK(!md_fd_order_complete(&mutator->fd_order));
        CHECK(md_fd_order_begin(mutator->resources.files,&mutator->fd_order,fd_wakeup)>=0);
        retry_wait(mutator,RETRY_FD); return 0;
    }
    CHECK(0); return 0;
}
static int fd_loans_ready(struct thread *t) {
    if (!md_fd_origins_has_loans(t->resources.files)) return 1;
    const struct user_pt_regs *r=t->fd_job==MD_FD_DUPLICATE_BEGIN ? &t->original : &t->fd_registers;
    unsigned long a[6]; for (unsigned i=0;i<6;++i) a[i]=r->regs[i];
    long nr=r->regs[8];
    int copying=(md_clone_call(nr) && !(t->cloning&CLONE_FILES))
        || (nr==SYS_unshare && (a[0]&CLONE_FILES))
        || (nr==SYS_close_range && (a[2]&CLOSE_RANGE_UNSHARE));
    struct md_fd_loan *loan=NULL;
    int number=-1;
    int result=copying ? md_fd_origins_loan_range(&t->fd_order,0,UINT_MAX,&number,&loan)
        : md_fd_mutation_loan(&t->fd_order,nr,a,&number,&loan);
    if (result<0) { fd_discard(t); return_value(t,&t->fd_registers,result); return 0; }
    if (!result) return 1;
    int ready=readiness_evict(t,loan);
    md_fd_loan_release(loan);
    return ready;
}
static void readiness_selected(struct thread *t,long result) {
    struct readiness_owner *o=t->readiness;
    TRACE("PROBE readiness-selected pid=%d result=%ld\n",t->pid,result);
    if (result==-ESRCH) {
        int alive=0;
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->born && p->fd_selector==o->request.group) alive=1;
        if (!alive) result=md_fd_admission_retire(o->admission,o->request.group);
        else { retry_wait(t,RETRY_READINESS); CHECK(!md_event_signal(fd_ready)); return; }
    } else result=md_fd_admission_observed(o->admission,&o->request,result);
    if (result) readiness_reply(t,result);
    else { retry_wait(t,RETRY_READINESS); CHECK(!md_event_signal(fd_ready)); }
}
static int readiness_inventory(struct readiness_owner *o) {
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->born && p->resources.files && p->fd_selector) {
        int error=md_fd_admission_add(o->admission,p->resources.files,p->fd_selector);
        if (error) return error;
    }
    return 0;
}
static void readiness_poll_load(struct thread *t);
static void readiness_activated(struct md_fd_object *queue) {
    /* Cold admission only: wake the exact waits that entered before this
     * queue gained its virtual transport. Their owner retains the deadline,
     * native result and temporary signal mask. No other task is stopped. */
    for (struct thread *p=threads;p;p=p->next) {
        struct readiness_owner *o=p->readiness;
        if (p->pid && o && o->queue==queue && p->phase==EPOLL_WAIT
                && !o->wait.job.started && !p->socket_waking) {
            p->socket_waking=1;
            tracee_request(p,PTRACE_INTERRUPT,NULL,NULL,"epoll-cold-admission");
        }
    }
}
static void readiness_start(struct thread *t) {
    retry_clear(t,RETRY_READINESS);
    struct readiness_owner *o=t->readiness;
    if (o->restoring) {
        int continuation=o->restoring; long value=o->restore_result;
        if (!readiness_restore(t,continuation,value)) return;
        if (continuation==READINESS_RESTORE_LOAN) readiness_request_loan(t);
        else if (continuation==READINESS_RESTORE_REPLY) readiness_reply(t,value);
        else readiness_task_progress(t,0,0);
        return;
    }
    if (o->delivery.files.table) { readiness_deliver(t); return; }
    TRACE("PROBE readiness-start pid=%d classified=%d admission=%d sealed=%d files=%d activation=%d\n",
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
        if (md_fd_order_state(&o->files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_READINESS); return; }
        CHECK(!md_fd_order_start(&o->files));
        if (o->poll) {
            struct md_buffer_span span;
            error=md_buffer_span(&t->buffer,&span);
            if (error) { readiness_reply(t,error); return; }
            struct md_poll_frame *f=span.data;
            unsigned count=o->poll_count>MD_POLL_CHUNK ? MD_POLL_CHUNK : o->poll_count;
            if (f->offset || f->count!=count) { readiness_reply(t,-EINVAL); return; }
            o->classified=1;
            readiness_poll_load(t); return;
        }
        o->queue=md_fd_origins_object(t->resources.files,(int)t->original.regs[0],NULL);
        queue=md_control_epoll_from_object(o->queue);
        o->classified=1;
        if (o->operation) {
            o->target=md_fd_origins_object(t->resources.files,o->number,NULL);
            struct md_control_file provider=md_fd_object_readiness(o->target);
            if (md_control_epoll_from_object(o->target)
                    && md_fd_object_selectors(o->target)) { readiness_reply(t,-EOPNOTSUPP); return; }
            if (!provider.ops || !provider.ops->poll) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
            if (!queue) { readiness_reply(t,-EINVAL); return; }
            CHECK(!md_fd_order_complete(&o->files));
            if (o->operation!=EPOLL_CTL_ADD) {
                readiness_reply(t,md_control_epoll_ctl(queue,o->operation,o->number,o->target,provider,&o->event)); return;
            }
        } else {
            if (!queue) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
            struct md_epoll_frame *frame;
            struct md_buffer_span span;
            error=md_buffer_span(&t->buffer,&span);
            if (error) { readiness_reply(t,error); return; }
            frame=span.data;
            if (md_control_epoll_native_wait(o->queue)
                    && frame->timed && !frame->timeout.tv_sec && !frame->timeout.tv_nsec) {
                readiness_reply(t,MD_EPOLL_NATIVE); return;
            }
            if (md_fd_origins_exclusive(&o->files)) {
                o->native_caller=1;
                CHECK(!md_fd_order_complete(&o->files));
                buffer_reply(t,MD_EPOLL_CAPTURED); return;
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
        TRACE("PROBE readiness-admission pid=%d prepared=%d\n",t->pid,error);
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
        TRACE("PROBE readiness-selector pid=%d result=%d fd=%d kind=%d\n",t->pid,error,o->request.number,o->request.kind);
        if (error==1) {
            CHECK(!t->action);
            struct task_action *s=calloc(1,sizeof(*s));
            if (!s) { readiness_reply(t,-ENOMEM); return; }
            ++task_action_calls; t->action=s;
            s->requester=t; s->continuation=o->continuation;
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
    readiness_reply(t,md_control_epoll_ctl(queue,o->operation,o->number,o->target,provider,&o->event));
}
static void readiness_capture(struct thread *t,unsigned command,long value) {
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
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
    o->native_caller=1;
    buffer_reply(t,MD_EPOLL_CAPTURED);
}
static void readiness_begin(struct thread *t,const struct user_pt_regs *r) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
    struct readiness_owner *o=error ? NULL : calloc(1,sizeof(*o));
    if (!error && !o) error=-ENOMEM;
    if (error) { buffer_reply(t,error); return; }
    o->continuation=*r;
    o->loan_number=-1;
    if (t->original.regs[8]==SYS_epoll_pwait || t->original.regs[8]==SYS_epoll_pwait2) {
        /* Reuse the admitted call for ADDFD, not an unrelated service syscall
         * which an application filter may deny. The owner validates replay. */
        struct md_epoll_frame *frame=span.data;
        frame->admission.number=t->original.regs[8];
        for (unsigned i=0;i<6;++i) frame->admission.value[i]=t->original.regs[i];
    }
    if (t->epoll_started) {
        md_epoll_wait_initialize(&o->wait,span.data);
        struct md_epoll_frame *frame=span.data;
        if (frame->timed)
            o->wait.deadline=t->epoll_started+frame->timeout.tv_sec*1000000000LL+frame->timeout.tv_nsec;
        t->epoll_started=0;
    }
    if (t->original.regs[8]==SYS_ppoll || t->original.regs[8]==SYS_pselect6) {
        struct md_poll_frame *f=span.data;
        o->poll_count=f->total;
        o->poll_started=md_event_now();
        if (md_poll_bytes(f->total)>span.size) error=-EINVAL;
        else {
            o->poll=md_poll_wait_new(f->total,f->select);
            if (!o->poll) error=-ENOMEM;
        }
    }
    if (t->original.regs[8]==SYS_epoll_ctl) {
        o->operation=(int)t->original.regs[1]; o->number=(int)t->original.regs[2];
        o->event=((struct md_epoll_frame *)span.data)->event;
        if (o->operation!=EPOLL_CTL_ADD && o->operation!=EPOLL_CTL_MOD && o->operation!=EPOLL_CTL_DEL) error=-EINVAL;
    }
    t->readiness=o;
    if (error) readiness_reply(t,error); else readiness_start(t);
}
static void readiness_poll_load(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
    struct md_poll_frame *f=error ? NULL : span.data;
    if (!error) error=md_poll_wait_load(o->poll,o->files.table,f);
    if (error) { readiness_reply(t,error); return; }
    o->poll_loaded+=f->count;
    if (o->poll_loaded<o->poll_count) {
        f->offset=o->poll_loaded; f->count=o->poll_count-o->poll_loaded;
        if (f->count>MD_POLL_CHUNK) f->count=MD_POLL_CHUNK;
        buffer_reply(t,MD_POLL_LOAD); return;
    }
    if (!md_poll_wait_virtual(o->poll)) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
    o->native_caller=1;
    CHECK(!md_fd_order_complete(&o->files));
    buffer_reply(t,MD_EPOLL_CAPTURED);
}
static void close_detach(struct thread *t) {
    retry_clear(t,RETRY_CLOSE);
    if (t->close_pending) { t->close_pending->dispose(t->close_pending); t->close_pending=NULL; }
    struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources,.wakeup=fd_wakeup};
    while (t->closed.first) {
        struct md_control_pending *pending=NULL;
        md_fd_closed_next(&t->closed,&actor,&pending);
        if (pending) pending->dispose(pending);
    }
    t->close_done=0; t->close_event=0;
}
static void close_event_complete(struct thread *t) {
    t->close_event=0; t->close_done=0;
    resume(t->pid,0);
}
static void close_return(struct thread *t) {
    struct user_pt_regs regs=t->close_registers;
    regs.regs[0]=(uint64_t)t->close_result;
    if (!set_registers(t->pid,&regs) || !signal_mask(t,t->close_mask,"close-restore-mask")) return;
    t->signal_shielded=t->close_mask_shielded;
    t->phase=t->close_phase; t->close_done=0;
    if (t->phase==IDLE && t->entered && t->process_image) {
        if (debug_syscall_exit(t,&regs)) return;
        t->debug_step_call=md_debugger_step(debugger,t->pid);
    }
    resume(t->pid,0);
}
static void close_progress(struct thread *t) {
    retry_clear(t,RETRY_CLOSE);
    struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources,.wakeup=fd_wakeup};
    while (t->close_pending || t->closed.first) {
        long result=t->close_pending ? t->close_pending->resume(t->close_pending,NULL,0)
            : md_fd_closed_next(&t->closed,&actor,&t->close_pending);
        if (result==-EINPROGRESS) { retry_wait(t,RETRY_CLOSE); return; }
        if (t->close_pending) { t->close_pending->dispose(t->close_pending); t->close_pending=NULL; }
        if (result<0 && t->close_errors && !t->close_result) t->close_result=result;
    }
    t->close_done=1;
    if (t->close_event) { close_event_complete(t); return; }
    if (t->phase==CLOSE_WAITING && !t->listening)
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"close-provider-ready");
}
static void close_event_begin(struct thread *t,unsigned event,unsigned long message) {
    t->close_event=event; t->close_message=message; t->close_errors=0;
    /* EVENT_WAIT: terminal provider replies before exec continuation/exit.
     * The ptrace event already owns the stop; no guest memory or FD turn is
     * retained. Fatal termination abandons the wait, not accepted requests. */
    if (event==PTRACE_EVENT_EXIT && WIFSIGNALED((int)message)) {
        close_detach(t); t->close_event=event;
        close_event_complete(t); return;
    }
    close_progress(t);
}
static struct user_pt_regs wait_poll_registers(const struct thread *t,enum phase phase) {
    CHECK(phase==FILE_WAIT_ENTRY || phase==CONTROL_WAIT_ENTRY || phase==CLOSE_WAIT_ENTRY);
    struct user_pt_regs regs=phase==CONTROL_WAIT_ENTRY ? t->buffer_registers
        : phase==CLOSE_WAIT_ENTRY ? t->close_registers : t->original;
    regs.pc=RAW_GATE-4; regs.regs[8]=SYS_ppoll;
    for (unsigned i=0;i<5;++i) regs.regs[i]=0;
    return regs;
}
static void wait_poll_start(struct thread *t,enum phase phase) {
    struct user_pt_regs regs=wait_poll_registers(t,phase);
    if (set_registers(t->pid,&regs)) { t->phase=phase; resume(t->pid,0); }
}
static int wait_poll_entry(struct thread *t) {
    struct ptrace_syscall_info info={0};
    if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"provider-wait-entry")) return -1;
    if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
        CHECK(info.entry.nr==SYS_ppoll && info.instruction_pointer==RAW_GATE);
        return 1;
    }
    /* Starting at seccomp first drains the skipped application's exit. That
     * exit can overwrite x0; rearm the poll before unmasking or cancellation. */
    CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT);
    struct user_pt_regs regs=wait_poll_registers(t,t->phase);
    return set_registers(t->pid,&regs) ? 0 : -1;
}
static int close_begin(struct thread *t,const struct user_pt_regs *returned,int errors) {
    if (!t->closed.first) return 0;
    t->close_registers=*returned; t->close_result=(long)returned->regs[0]; t->close_phase=t->phase; t->close_errors=errors;
    close_progress(t);
    if (t->close_done && t->close_result==(long)returned->regs[0]) { t->close_done=0; return 0; }
    t->close_mask_shielded=t->signal_shielded;
    if (!save_signal_mask(t,&t->close_mask,"close-get-mask")
            || !shield(t)) return 1;
    if (t->close_done) { close_return(t); return 1; }
    /* EVENT_WAIT: a confirmed close waits for its provider, without retaining
     * the FD-table turn. Signals interrupt this wait but never replay close. */
    wait_poll_start(t,CLOSE_WAIT_ENTRY);
    return 1;
}
static void close_wait_complete(struct thread *t) {
    if (!shield(t)) return;
    if (!t->close_done) {
        /* The slot is already closed. Retain terminal provider work, but let
         * the interrupted caller return; another thread may reuse that slot. */
        close_detach(t);
        if (t->close_errors && !t->close_result) t->close_result=-EINTR;
    }
    close_return(t);
}
static void wait_cancel_entry(struct thread *t,enum phase next) {
    CHECK(next==FILE_WAITING || next==CONTROL_WAITING || next==CLOSE_WAITING || next==EPOLL_CALL);
    if (!skip(t->pid)) return;
    /* EVENT_WAIT: drain the skipped syscall's exit before restoring adapter
     * registers. At entry, seccomp/kernel return can still overwrite x0. */
    t->wait_resume=next; t->phase=WAIT_CANCEL_EXIT; resume(t->pid,0);
}
static void control_park(struct thread *t) {
    if (t->phase==CONTROL_WAIT_ENTRY || t->phase==CONTROL_WAITING) return;
    CHECK(t->phase==DISPATCHING && t->kernel_stopped && !t->control_done);
    /* EVENT_WAIT: provider completion interrupts the caller's native poll.
     * Guest signals remain deliverable; the owner retains only this request. */
    wait_poll_start(t,CONTROL_WAIT_ENTRY);
}
static void control_deliver(struct thread *t,long result) {
    if (!t->proc_direct) { buffer_reply(t,result); return; }
    t->proc_direct=0;
    if (result>=0) result=buffer_result_finish(t,0,result);
    else (void)buffer_cleanup(t);
    dispatch_complete(t,result); adapter_progress();
}
static void control_reply(struct thread *t,long result) {
    if (t->buffer_registers.regs[0]==MD_BUFFER_CONTROL_MAP && result>=0) {
        CHECK(t->control_mapping_fd<0 && result<=INT_MAX);
        t->control_mapping_fd=(int)result; result=0;
    }
    if (t->phase!=CONTROL_WAIT_ENTRY && t->phase!=CONTROL_WAITING) { control_deliver(t,result); return; }
    CHECK(!t->control_done);
    t->control_done=1; t->control_result=result;
    if (t->phase==CONTROL_WAITING && !t->listening)
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"control-ready");
}
static struct thread *control_deadline_owner(void) {
    struct md_pending_queue *queue=&retry_queues[RETRY_CONTROL>=RETRY_FD];
    struct thread *first=NULL;
    /* The continuation registry already owns every active control wait.
     * Idle and retired thread slots have no deadline to inspect. */
    for (unsigned i=0;i<2;++i) for (struct md_pending_node *node=queue->lists[i].first;node;node=node->next) {
        struct retry_owner *owner=(struct retry_owner *)node;
        if (!(owner->mask&RETRY_CONTROL)) continue;
        struct thread *t=owner->thread;
        CHECK(t->pid && t->control_pending);
        if (!first || t->control_pending->deadline<first->control_pending->deadline) first=t;
    }
    return first;
}
static void control_progress(struct thread *t) {
    retry_clear(t,RETRY_CONTROL);
    struct md_control_pending *pending=t->control_pending;
    if (!pending) return;
    struct md_buffer_span span;
    long result=md_buffer_span(&t->buffer,&span);
    if (!result && t->control_size>span.size) result=-EOVERFLOW;
    if (!result) result=pending->resume(pending,span.data,t->control_size);
    if (result==-EINPROGRESS && md_event_now()<pending->deadline) {
        retry_wait(t,RETRY_CONTROL); control_park(t); return;
    }
    if (result==-EINPROGRESS) result=-ETIMEDOUT;
    t->control_pending=NULL; pending->dispose(pending);
    control_reply(t,result);
}
static void proc_io_progress(struct thread *t) {
    retry_clear(t,RETRY_PROC_IO);
    if (t->proc_waiting==2) {
        struct md_control_file file=md_proc_file_control(t->proc_file);
        if (!file.ops->readable(file.context)) { retry_wait(t,RETRY_PROC_IO); control_park(t); return; }
        int result=md_proc_file_io_begin(&t->proc_io,t->proc_file,MD_PROC_FILE_READ,
            0,0,0,t->buffer_registers.regs[1],fd_wakeup);
        if (result<0) { t->proc_waiting=0; control_reply(t,result); return; }
        t->proc_waiting=1;
    }
    if (!t->proc_waiting) return;
    int state=md_resource_order_state(&t->proc_io.turn);
    if (!t->proc_io.pending && state!=MD_RESOURCE_ORDER_READY && state!=MD_RESOURCE_ORDER_RUNNING) {
        retry_wait(t,RETRY_PROC_IO); control_park(t); return;
    }
    struct md_buffer_span span;
    long result=md_buffer_span(&t->buffer,&span);
    if (!result) {
        struct md_proc_file_node node=md_proc_file_get_node(t->proc_io.file);
        struct proc_owner owner=proc_owner(node.process);
        struct md_process_limits limits=owner.process ? md_process_limits_value(owner.process) : (struct md_process_limits){0};
        if (owner.identity) md_proc_file_process_state(t->proc_io.file,owner.identity,owner.pid_namespace,
            md_debugger_owner(debugger,owner.pid),owner.process ? &limits : NULL);
        t->proc_io.actor=(struct md_control_actor){.pid=t->pid,.identity=&t->identity,.resources=&t->resources,
            .filesystem=filesystem,.submit=md_fs_worker_control};
        result=md_proc_file_io_execute(&t->proc_io,&t->identity,t->resources.uts,span.data,span.size,(size_t)getpagesize());
    }
    struct md_control_file file=md_proc_file_control(t->proc_file);
    if (result==-EINPROGRESS && t->proc_io.pending) {
        /* EVENT_WAIT: the admitted IO's provider reply or caller cancellation.
         * This OFD's cursor stays serialized; other files and tables progress. */
        retry_wait(t,RETRY_PROC_IO); control_park(t); return;
    }
    if (result==-EAGAIN && t->proc_io.operation==MD_PROC_FILE_READ && !t->proc_io.positional
            && !(t->original.regs[8]==SYS_splice && (t->original.regs[5]&SPLICE_F_NONBLOCK))
            && !(md_proc_file_flags(t->proc_file)&O_NONBLOCK) && file.ops && file.ops->readable) {
        CHECK(!md_proc_file_io_cancel(&t->proc_io));
        /* EVENT_WAIT: provider publishes readable/terminal state and signals
         * fd_ready. Exit or signal cancels this retained OFD, not a reused FD.
         * No file-table or IO turn is held while waiting for input. */
        t->proc_waiting=2; retry_wait(t,RETRY_PROC_IO); control_park(t); return;
    }
    if (result>=0) CHECK(!md_proc_file_io_offer(&t->proc_io));
    else CHECK(!md_proc_file_io_cancel(&t->proc_io));
    if (result>=0 && t->original.regs[8]!=SYS_splice && md_proc_file_io_more(&t->proc_io))
        result|=MD_BUFFER_PROC_MORE;
    t->proc_waiting=0; control_reply(t,result);
}
static void probe_complete(void *context, struct md_task_helper_result result) {
    struct probe_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    struct stat st;
    long error=result.value;
    if (!error && result.descriptor<0) error=-EPROTO;
    if (!error && fstat(result.descriptor,&st)) error=-errno;
    if (!error && !S_ISDIR(st.st_mode)) error=-ENOTDIR;
    if (result.descriptor>=0) close(result.descriptor);
    if (t && t->probe==owner) {
        t->probe=NULL; t->kernel_stopped=1;
        t->buffer_registers=owner->continuation;
        buffer_reply(t,error);
    }
    free(owner);
}
static void probe_begin(struct thread *t, const struct user_pt_regs *regs) {
    struct probe_owner *owner=calloc(1,sizeof(*owner));
    int error=owner ? 0 : -ENOMEM;
    if (!error) {
        *owner=(struct probe_owner){t->pid,*regs};
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug_options);
    }
    if (!error) {
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_CAPTURE,
            .path="/proc/thread-self",.flags=O_PATH|O_DIRECTORY};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,regs,&abi,t->memory,&t->buffer,
            &request,probe_complete,owner);
    }
    if (error) { free(owner); buffer_reply(t,error); }
    else { t->probe=owner; t->kernel_stopped=0; }
}
static void outgoing_complete(void *context, struct md_task_helper_result result) {
    struct outgoing_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0 && !result.received);
    md_fd_message_export_end(owner->message,owner->stage==OUTGOING_SEND
        && (result.value>=0 || result.value==-ECANCELED));
    if (t && t->outgoing==owner) {
        t->outgoing=NULL; t->kernel_stopped=1; t->buffer_registers=owner->continuation;
        buffer_reply_signal(t,result.value,result.signal.si_signo ? &result.signal : NULL);
    }
    outgoing_release(owner);
}
static void outgoing_prepared(struct md_ipc_origin_request *request, int error, void *context) {
    struct outgoing_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(t && t->outgoing==owner && owner->preparation==request && owner->stage==OUTGOING_PREPARE);
    enum md_ipc_ancillary_transport transport;
    int taken=md_ipc_origins_take_send(&owner->preparation,&owner->prepared,&transport,&owner->destination);
    CHECK(taken==error);
    struct md_buffer_span span;
    if (!error) error=md_buffer_span(&t->buffer,&span);
    if (error) { outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1}); return; }
    struct md_task_outgoing_frame *frame=span.data;
    CHECK(owner->prepared->count>=1 && owner->prepared->count<=254);
    frame->rights=owner->prepared->count-1; frame->transport=transport;
    owner->brokered=owner->destination.brokered;
    frame->brokered=owner->brokered;
    frame->destination=owner->destination;
    frame->destination.anchor=-1; /* Host descriptor numbers never enter guest state. */
    owner->stage=OUTGOING_READY; t->buffer_registers=owner->continuation;
    buffer_reply(t,0);
}
static void outgoing_exported(void *context, struct md_task_helper_result result) {
    struct outgoing_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0);
    if (!t || t->outgoing!=owner) {
        md_received_fds_release(result.received); outgoing_release(owner); return;
    }
    t->kernel_stopped=1;
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
static void outgoing_start(struct thread *t) {
    retry_clear(t,RETRY_OUTGOING);
    struct outgoing_owner *owner=t->outgoing;
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
        owner->options.brokered=owner->brokered;
        error=md_fs_order_complete(&owner->fs_order);
    }
    if (!error && owner->input.count>1)
        error=md_fd_message_capture(t->resources.files,owner->input.keep+1,owner->input.count-1,&owner->message);
    if (!error) {
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug_options);
    }
    owner->stage=OUTGOING_CAPTURE;
    if (!error && owner->brokered) { ipc_capture_step(t,MD_FS_BUFFER_IPC_CAPTURE_READY,0); return; }
    if (!error) {
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_EXPORT,
            .descriptors=owner->input.keep,.descriptor_count=owner->input.count,.captured=outgoing_captured};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,&owner->continuation,&abi,t->memory,&t->buffer,
            &request,outgoing_exported,owner);
    }
    if (error) outgoing_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
}
static void outgoing_begin(struct thread *t, const struct user_pt_regs *regs) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
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
        *owner=(struct outgoing_owner){.caller=t->pid,.continuation=*regs,.input=input,
            .brokered=t->application_filter,
            .options=((struct md_task_outgoing_frame *)span.data)->options,.destination={.anchor=-1}};
        owner->options.brokered=owner->brokered;
        error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error>0) error=0;
    }
    if (error) { outgoing_release(owner); buffer_reply(t,error); }
    else { t->outgoing=owner; outgoing_start(t); }
}
static void outgoing_send(struct thread *t, const struct user_pt_regs *regs) {
    struct outgoing_owner *owner=t->outgoing;
    uintptr_t address=md_memory_buffer_address(t->memory,&t->buffer);
    owner->continuation=*regs; owner->stage=OUTGOING_SEND;
    if (owner->brokered) { socket_io_progress(t); return; }
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_SEND_CAPTURED,
        .send_inputs=owner->prepared,.send_import=address+offsetof(struct md_task_outgoing_frame,scratch.import),
        .send_address=owner->destination.anchor>=0 ? address+offsetof(struct md_task_outgoing_frame,destination.address) : 0,
        .send_anchor=owner->destination.anchor,
        .send={.message=owner->input.message,.socket=owner->input.socket,.flags=owner->input.flags,
            .sendto=owner->input.sendto,.payload=owner->input.payload,
            .token_slot=owner->input.token_slot}};
    int error=md_task_helper_begin(helpers,t->pid,t->tgid,regs,&abi,t->memory,&t->buffer,
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
    if (t && t->receive==owner) {
        t->kernel_stopped=1; t->buffer_registers=owner->continuation;
        if (owner->brokered && owner->request.inline_receive) {
            struct md_buffer_span span;
            CHECK(!md_buffer_span(&t->buffer,&span));
            struct md_task_receive_frame *frame=span.data;
            if (result.value<0 && owner->request.finish_error
                    && (!owner->request.batch || !owner->io.initialized || !frame->io.batch_count)
                    && (t->original.regs[8]==SYS_recvmsg || t->original.regs[8]==SYS_recvmmsg)) {
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
    struct outgoing_owner *out=t->outgoing;
    struct receive_owner *in=t->receive;
    struct md_fd_capture *capture=out ? &out->capture : &in->capture;
    struct md_fd_order *order=out ? &out->files : &in->files;
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
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
    return t->outgoing ? &t->outgoing->io : &t->receive->io;
}
static void socket_unwatch(struct thread *t) {
    if (t->readiness) { readiness_unwatch(t); return; }
    int *watch=t->outgoing ? &t->outgoing->io_waiting : &t->receive->io_waiting;
    int fd=t->outgoing ? t->outgoing->prepared->fds[0] : t->receive->received->fds[0];
    if (*watch) { CHECK(!epoll_ctl(socket_events,EPOLL_CTL_DEL,md_ipc_io_event(socket_operation(t),fd),NULL)); *watch=0; }
}
static void socket_complete(struct thread *t, long result) {
    CHECK(!socket_operation(t)->job.started);
    socket_unwatch(t);
    t->phase=DISPATCHING;
    if (t->outgoing) {
        if (result==-EPIPE && !(t->outgoing->input.flags&MSG_NOSIGNAL)) t->socket_sigpipe=1;
        outgoing_complete(t->outgoing,(struct md_task_helper_result){.value=result,.descriptor=-1});
    } else {
        struct receive_owner *owner=t->receive;
        if (result==-EPIPE && md_network_io_sending(&owner->network)
                && !(md_network_io_flags(&owner->network)&MSG_NOSIGNAL)) t->socket_sigpipe=1;
        if (owner->network.number && (owner->network.number==SYS_accept || owner->network.number==SYS_accept4)
                && result>=0) {
            int fd=(int)result;
            owner->io.native_ready=0;
            result=md_network_endpoint_accept(owner->network.endpoint,fd,&t->buffer_open.object);
            if (!result) {
                struct md_buffer_span span;
                result=md_buffer_span(&t->buffer,&span);
                if (!result) {
                    struct md_ipc_io_message *io=&((struct md_task_receive_frame *)span.data)->io;
                    result=md_network_endpoint_name(t->buffer_open.object,fd,1,&io->address,&io->address_size);
                }
            }
            if (result) { close(fd); md_fd_object_release(t->buffer_open.object); t->buffer_open.object=NULL; }
            else {
                t->buffer_open.fd=fd;
                t->buffer_open.flags=owner->network.number==SYS_accept4 ? owner->network.args[3]&SOCK_CLOEXEC : 0;
            }
        }
        receive_complete(owner,(struct md_task_helper_result){.value=result,.descriptor=-1,.received=owner->received});
    }
}
static int socket_watch(struct thread *t) {
    int *watch=t->readiness ? &t->readiness->io_waiting
        : t->outgoing ? &t->outgoing->io_waiting : &t->receive->io_waiting;
    if (*watch) return 0;
    if (socket_events<0) socket_events=epoll_create1(EPOLL_CLOEXEC);
    if (socket_events<0) return -errno;
    int fd=t->readiness ? md_ipc_io_job_event(&t->readiness->wait.job)
        : md_ipc_io_event(socket_operation(t),t->outgoing ? t->outgoing->prepared->fds[0] : t->receive->received->fds[0]);
    int sending=!t->readiness && (t->outgoing || (t->receive && !t->receive->io.job.started
        && md_network_io_sending(&t->receive->network)));
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
static void readiness_task_progress(struct thread *t,int completed,long value) {
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    long result=readiness_frame(t,&span);
    uintptr_t address=md_memory_buffer_address(t->memory,&t->buffer);
    int waiting=!result && (o->poll ? md_poll_wait_task(o->poll,&o->wait,span.data,address,
            completed,value,&o->native_call,&result)
        : md_epoll_wait_task(&o->wait,md_control_epoll_from_object(o->queue),span.data,address,
            t->original.regs[8],o->received ? o->loan_number : (int)t->original.regs[0],
            completed,value,&o->native_call,&result));
    if (o->evicting) {
        md_epoll_wait_cancel(&o->wait);
        o->parked=1; t->phase=DISPATCHING;
        if (!waiting) { o->finishing=1; o->finish_result=result; }
        CHECK(!md_event_signal(fd_ready)); return;
    }
    if (!waiting) { readiness_reply(t,result); return; }
    if (!o->poll) {
        struct md_epoll_frame *frame=span.data;
        int independent=frame->native_output && !frame->masked
            && (o->native_call.number==SYS_epoll_pwait || !frame->timed);
        if (o->detached && !independent) {
            if (readiness_restore(t,READINESS_RESTORE_WAIT,0)) readiness_task_progress(t,0,0);
            return;
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
    struct user_pt_regs regs=o->continuation;
    regs.pc=abi.task_call; regs.regs[8]=o->native_call.number;
    memcpy(regs.regs,o->native_call.value,sizeof(o->native_call.value));
    if (set_registers(t->pid,&regs)) { t->phase=EPOLL_CALL; resume(t->pid,0); }
}
static void readiness_progress(struct thread *t) {
    socket_unwatch(t);
    struct readiness_owner *o=t->readiness;
    if (o->received && o->loan_number<0) { readiness_request_loan(t); return; }
    CHECK(o->native_caller);
    readiness_task_progress(t,0,0);
}
static void socket_io_progress(struct thread *t) {
    if (t->readiness) { readiness_progress(t); return; }
    struct outgoing_owner *out=t->outgoing;
    struct receive_owner *in=t->receive;
    socket_unwatch(t);
    struct md_buffer_span span;
    long result=md_buffer_span(&t->buffer,&span);
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
        struct user_pt_regs regs=out ? out->continuation : in->continuation;
        /* The adapter is suspended mid-call. Its live stack is not the reusable
         * stack top used by completed SysV dispatches. */
        operation_park(t,regs,deadline);
        return;
    }
    TRACE("PROBE socket-io pid=%d operation=%s result=%ld rights=%u\n",t->pid,out ? "send" : "receive",result,
        out ? out->prepared->count-1 : in->received->count-1);
    socket_complete(t,result);
}
static void socket_events_ready(void) {
    struct epoll_event events[32];
    int count=epoll_wait(socket_events,events,32,0);
    CHECK(count>=0 || errno==EINTR);
    for (int i=0;i<count;++i) {
        struct thread *t=find_thread((pid_t)events[i].data.u32);
        if (t && ((t->outgoing && t->outgoing->io_waiting) || (t->receive && t->receive->io_waiting)
                || (t->readiness && t->readiness->io_waiting))) {
            CHECK(t->phase==SOCKET_WAITING || t->phase==SOCKET_WAIT_ENTRY || t->phase==EPOLL_WAIT);
            socket_unwatch(t);
            int waking=t->socket_waking; t->socket_waking=1;
            /* EVENT_WAIT: readiness cancels the caller's interruptible ppoll.
             * Socket timeout or signal completes the operation without replay. */
            if (!waking && (t->phase==SOCKET_WAITING || t->phase==EPOLL_WAIT) && !t->listening)
                tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"socket-ready");
        }
    }
}
static void receive_start(struct thread *t) {
    retry_clear(t,RETRY_RECEIVE);
    struct receive_owner *owner=t->receive;
    if (!owner || owner->started) return;
    if (md_fd_order_state(&owner->files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_RECEIVE); return; }
    int error=md_fd_order_start(&owner->files);
    if (!error && (owner->network.number || owner->request.batch)) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,owner->request.socket,NULL);
        if (md_network_endpoint_is(object)) {
            owner->network.endpoint=object;
            if (!owner->network.number) {
                owner->brokered=1; owner->network.number=t->original.regs[8];
                owner->network.identity=&t->identity;
                memcpy(owner->network.args,t->original.regs,sizeof(owner->network.args));
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
    if (!error) {
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug_options);
    }
    if (!error) {
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_RECEIVE,
            .receive=owner->request,.captured=receive_captured};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,&owner->continuation,&abi,t->memory,&t->buffer,
            &request,receive_complete,owner);
    }
    owner->started=1;
    if (error) receive_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
}
static void receive_begin(struct thread *t, const struct user_pt_regs *regs) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
    struct receive_owner *owner=NULL;
    if (!error) { owner=calloc(1,sizeof(*owner)); if (!owner) error=-ENOMEM; }
    if (!error) {
        *owner=(struct receive_owner){.caller=t->pid,.continuation=*regs,
            .brokered=t->application_filter,
            .request=((struct md_task_receive_frame *)span.data)->input};
        if (regs->regs[0]==MD_BUFFER_NETWORK_DATA) {
            owner->brokered=1; owner->network.number=t->original.regs[8];
            owner->network.identity=&t->identity;
            for (unsigned i=0;i<6;++i) owner->network.args[i]=t->original.regs[i];
            if (owner->network.number==SYS_sendmmsg) {
                owner->network.number=SYS_sendmsg;
                owner->network.args[2]=t->original.regs[3];
            }
            owner->request.socket=(int)t->original.regs[0];
        }
        error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error>0) error=0;
    }
    if (error) { free(owner); buffer_reply(t,error); }
    else { t->receive=owner; receive_start(t); }
}
static void capture_complete(void *context, struct md_task_helper_result result) {
    struct capture_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    if (t && t->capture==owner) { t->capture=NULL; t->kernel_stopped=1; }
    CHECK(!md_fs_buffer_capture_finish(owner->request,result.descriptor,(int)result.value));
    free(owner);
}
static void capture_start(struct thread *t, struct md_fs_buffer_request *request) {
    CHECK(!t->capture && !t->mapping);
    TRACE("PROBE buffer-capture pid=%d\n",t->pid);
    struct capture_owner *owner=calloc(1,sizeof(*owner));
    int error=owner ? md_fs_buffer_capture_start(request) : -ENOMEM;
    if (!error) {
        *owner=(struct capture_owner){.caller=t->pid,.request=request};
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug_options);
    }
    if (!error) {
        struct md_fs_task_capture target;
        error=md_fs_buffer_capture_target(request,&target);
        if (!error) {
            struct md_task_helper_request capture={.kind=MD_TASK_HELPER_CAPTURE,
                .path=target.path,.flags=target.flags};
            error=md_task_helper_begin(helpers,t->pid,t->tgid,&t->buffer_registers,&abi,
                t->memory,&t->buffer,&capture,capture_complete,owner);
        }
    }
    if (error) {
        free(owner);
        CHECK(!md_fs_buffer_capture_finish(request,-1,error));
    } else { t->capture=owner; t->kernel_stopped=0; }
}
static int direct_addfd(const struct thread *t, const struct md_fs_buffer_frame *frame) {
    return !t->broker_event.held && (t->direct==DIRECT_OPEN || t->direct==DIRECT_PREPARE_RESULT) && frame->transfer==MD_FS_BUFFER_ADDFD
        && !frame->reply.result.selectors && t->buffer_open.completion.kind==MD_OPEN_READY;
}
static void buffer_result_begin(struct thread *t,int direct) {
    struct seccomp_data call={.nr=direct ? SYS_fstat : SYS_gettid,.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=direct ? t->original.pc : abi.buffer_admit};
    /* A restrictive filter need not allow our gettid carrier. Re-admit the
     * original operation instead; the retained result prevents replaying IO. */
    int replay=!direct && t->application_filter && md_broker_filesystem_call(t->original.regs[8]);
    t->result_replay=replay;
    if (replay) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->buffer,&span));
        ((struct md_fs_buffer_frame *)span.data)->transfer=MD_FS_BUFFER_REPLAY_ADDFD;
        call.nr=t->original.regs[8]; call.instruction_pointer=t->original.pc;
    }
    if (direct || replay) for (unsigned i=0;i<6;++i) call.args[i]=t->original.regs[i];
    else call.args[0]=MD_FS_BUFFER_RESULT_ADMIT;
    unsigned flags=direct ? t->original.regs[2] : t->buffer_open.flags;
    CHECK(!md_broker_result_begin(broker,t->pid,&call,t->resources.files,
        t->buffer_open.fd,md_fs_buffer_open_object(&t->buffer_open),flags&O_CLOEXEC));
}
static void buffer_result_replay(struct thread *t) {
    t->result_replay=0;
    struct user_pt_regs regs=t->original;
    regs.pc=RAW_GATE-4;
    if (set_registers(t->pid,&regs)) {
        t->phase=RESULT_REPLAY_ENTRY; resume(t->pid,0);
    }
}
static void buffer_complete(struct md_fs_buffer_request *request, pid_t pid, int error) {
    struct thread *t=find_thread(pid);
    if (t && t->proc_snapshot==request) {
        proc_snapshot_complete(t,request,error); return;
    }
    if (t && t->metadata.stat_call==request) {
        const struct md_fs_result *result=md_fs_buffer_status_result(request);
        CHECK(result);
        export_stat_complete(t,result,error); return;
    }
    CHECK(t && t->buffer_call==request);
    if (md_fs_buffer_open_waiting(request)) {
        if (md_fs_buffer_open_ready(request)) {
            if (t->phase==FILE_INTERRUPTING) file_wait_complete(t);
            else if (t->phase==FILE_WAITING && !t->listening)
                tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"open-ready");
            return;
        }
        CHECK(t->kernel_stopped);
        if (t->direct==DIRECT_OPEN && !save_signal_mask(t,&t->mask,"open-wait-mask")) return;
        if (!shield(t)) return;
        /* EVENT_WAIT: child completion interrupts this empty native poll;
         * guest signals request interruption without losing completed opens. */
        wait_poll_start(t,FILE_WAIT_ENTRY);
        return;
    }
    if (t->direct==DIRECT_STATUS) {
        const struct md_fs_result *result=md_fs_buffer_status_result(request);
        CHECK(result);
        t->buffer_call=NULL;
        direct_status_complete(t,result,error); return;
    }
    if (md_fs_buffer_capture_pending(request)) { capture_start(t,request); return; }
    if (!md_fs_buffer_ack_pending(request) && !md_fs_buffer_export_pending(request)
            && !md_fs_buffer_snapshot_pending(request) && !md_fs_buffer_task_pending(request)) t->buffer_call=NULL;
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
            CHECK(t->receive && t->receive->ready);
            md_received_fds_release(t->receive->expanded);
            t->receive->expanded=expanded;
        }
        int anonymous=0;
        CHECK(md_fs_buffer_exec_take(request,t->exec_images,&t->exec_mount_flags,&anonymous,
            &t->prepared_executable_origin,&t->execfd_origin,&t->exec_setid)>=0);
        if (anonymous) {
            CHECK(!t->prepared_executable);
            t->prepared_executable=md_process_executable_new(t->exec_images[0]);
            if (!t->prepared_executable) { error=-errno; exec_images_release(t); }
        }
        if (!error && !t->entered && t->exec_images[0]>=0) exec_images_activate(t);
        if (!md_fs_buffer_task_pending(request)) CHECK(md_fs_buffer_open_take(request,&t->buffer_open)>=0);
    }
    if (!error && t->buffer_open.fd>=0 && !t->buffer_open.task_path) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->buffer,&span));
        const struct md_fs_buffer_frame *frame=span.data;
        if (frame->transfer==MD_FS_BUFFER_ADDFD)
            buffer_result_begin(t,direct_addfd(t,frame));
    }
    TRACE("PROBE buffer-call pid=%d operation=%llu error=%d descriptor=%d\n",pid,t->buffer_registers.regs[1],error,t->buffer_open.fd>=0);
    if (t->direct==DIRECT_OPEN) { direct_complete(t,error); return; }
    buffer_reply(t,error);
}
static int buffer_cleanup(struct thread *t) {
    md_fs_anchor_release(t->clone_cgroup); t->clone_cgroup=NULL;
    readiness_release(t);
    if (t->outgoing) {
        if (t->outgoing->stage!=OUTGOING_READY
                && !(t->outgoing->brokered && t->outgoing->stage==OUTGOING_CAPTURE)) return -EBUSY;
        outgoing_release(t->outgoing); t->outgoing=NULL;
    }
    md_fs_buffer_cancel(&t->buffer_call);
    CHECK(md_broker_result_finish(broker,t->pid,NULL)>=0);
    md_fs_buffer_open_release(&t->buffer_open);
    t->result_replay=0;
    md_ipc_transport_close(&t->ipc_transport);
    if (t->proc_image_fd>=0) { close(t->proc_image_fd); t->proc_image_fd=-1; }
    if (t->control_mapping_fd>=0) { close(t->control_mapping_fd); t->control_mapping_fd=-1; }
    t->buffer_rights_delivered=0;
    return buffer_release(t);
}
static void dispatch_complete(struct thread *t,long value) {
    pid_t pid=t->pid;
    struct user_pt_regs regs;
    CHECK(t->phase==DISPATCHING && !t->control_pending);
    t->epoll_started=0;
    if (t->dispatch_prepared) {
        /* Early argument failures may never claim the supplied input buffer. */
        CHECK(!buffer_cleanup(t));
    }
    if (value==MD_CALL_CLONE && t->original.regs[8]==SYS_clone3) {
        CHECK(!t->clone_prepared && t->buffer.pool);
        t->clone_prepared=1;
        regs=t->original; regs.pc-=4;
        if (!set_registers(pid,&regs)) return;
        md_memory_stack_return(t->memory,t->stack); t->stack=0;
        t->phase=CLONE_ENTRY; resume(pid,0); return;
    }
    if (t->direct==DIRECT_RESULT) {
        CHECK(!buffer_cleanup(t)); t->direct=DIRECT_NONE;
    }
    /* Terminal acknowledgement owns the remaining socket custody. */
    if (t->receive) {
        CHECK((t->receive->ready || t->receive->brokered) && !t->receive->io_waiting);
        receive_release(t->receive); t->receive=NULL;
    }
    retry_clear(t,RETRY_PROC_IO);
    CHECK(!md_proc_file_io_cancel(&t->proc_io));
    md_proc_file_release(t->proc_file); t->proc_file=NULL; t->proc_waiting=t->proc_direct=0;
    if (t->pid_preflight || t->filter_preflight) {
        if (t->filter_preflight && !value && entry_fence_owner==t) t->entry_fence_replay=1;
        if (t->filter_preflight) t->registration_revoked=1;
        t->pid_preflight=t->filter_preflight=0;
        regs=t->original;
        if (!value) {
            regs.pc-=4;
            t->debug_replay_entry=t->debug_call_active;
            t->debug_adapter=0;
        }
        else regs.regs[0]=value;
        if (!set_registers(pid,&regs) || !restore_mask(t)) return;
        md_memory_stack_return(t->memory,t->stack); t->stack=0;
        t->phase=IDLE; resume(pid,0); return;
    }
    if (value==RESTART_SIGNAL || value==RESTART_PREPARATION) {
        restart_call(t,&t->original,t->mask,(enum call_restart)value); return;
    }
    if (t->memory_action==MEMORY_DETACH) {
        CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
    }
    if (value==MD_IPC_WAIT) {
        int64_t deadline=md_ipc_deadline(t->ipc_pending);
        int64_t remaining=0;
        if (deadline!=INT64_MAX) {
            int64_t now=md_event_now(); CHECK(now>=0);
            remaining=deadline>now ? deadline-now : 0;
        }
        regs=t->original; regs.pc=abi.ipc_wait; regs.sp=t->stack;
        regs.regs[30]=abi.done_entry;
        regs.regs[1]=remaining/1000000000LL; regs.regs[2]=remaining%1000000000LL;
        regs.regs[3]=deadline!=INT64_MAX;
        if (!set_registers(pid,&regs)) return;
        /* EVENT_WAIT: IPC mutation or owner death; semtimedop's deadline
         * returns EAGAIN. Signals interrupt without restarting the operation. */
        t->phase=IPC_WAIT_ENTRY; resume(pid,0); return;
    }
    if (value==MD_WATCH_WAIT || value==MD_CALL_NATIVE) {
        regs=t->original; regs.pc=RAW_GATE-4;
        t->watch_wait=value==MD_WATCH_WAIT ? 1 : 2;
        if (t->watch_wait==1) {
            /* EVENT_WAIT: native MSG_PEEK waits without consuming queue data.
             * Original signals/restart policy apply; the worker stays runnable. */
            regs.regs[8]=SYS_recvfrom;
            regs.regs[1]=t->stack-16; regs.regs[2]=1;
            regs.regs[3]=MSG_PEEK; regs.regs[4]=regs.regs[5]=0;
        }
        if (!set_registers(pid,&regs)) return;
        t->phase=WATCH_ENTRY; resume(pid,0); return;
    }
    if (value<0 && t->original.regs[8]==SYS_fstat) {
        if (++fstat_failures<=8)
            TRACE("PROBE fstat failed pid=%d fd=%llu result=%ld\n",pid,t->original.regs[0],value);
    }
    regs=t->original; regs.regs[0]=value;
    if (!set_registers(pid,&regs) || !restore_mask(t)) return;
    md_memory_stack_return(t->memory,t->stack); t->stack=0;
    if (completed_event_resume(t)) return;
    if (t->socket_sigpipe) {
        /* A checkpoint is not a signal-delivery stop. Publish application
         * SIGPIPE through the common delivery owner. */
        siginfo_t info={.si_signo=SIGPIPE,.si_code=SI_USER,.si_pid=t->tgid,.si_uid=getuid()};
        CHECK(!md_signal_delivery_hold(&t->signal_delivery,&info));
        t->socket_sigpipe=0;
    }
    t->phase=IDLE; resume(pid,0);
}
struct buffer_completion { long value, released; };
static struct buffer_completion buffer_finish(struct thread *t,long copied) {
    long value=t->proc_io.file ? md_proc_file_io_finish(&t->proc_io,copied) : copied ? -EINVAL : 0;
    long released=buffer_cleanup(t);
    return (struct buffer_completion){value,released};
}
static long buffer_result_finish(struct thread *t,long copied,long result) {
    int delivered=t->proc_io.file && (t->proc_io.operation==MD_PROC_FILE_READ || t->proc_io.operation==MD_PROC_FILE_GETDENTS)
        && (t->proc_io.transferred || copied>0);
    struct buffer_completion completed=buffer_finish(t,copied);
    if (completed.value && !delivered) result=completed.value;
    if (result==MD_CALL_NATIVE && completed.released<0) return completed.released;
    return result<0 ? result : completed.released<0 ? completed.released : result;
}
static void proc_io_begin(struct thread *t) {
    size_t length=t->buffer_registers.regs[1];
    if (t->proc_io.file) {
        if (!t->proc_io.transferred || t->proc_io.executed || t->proc_io.offered
                || length!=t->proc_io.length) { control_reply(t,-EINVAL); return; }
        t->proc_waiting=1; proc_io_progress(t); return;
    }
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=t->original.regs[i];
    struct md_proc_io_spec spec;
    int result=md_proc_io_decode(t->original.regs[8],args,&spec);
    if (!result && spec.splice) spec.writing=t->proc_splice_write;
    if (!result && ((!spec.seek && !spec.command && !spec.vector && !spec.splice && length!=args[2])
            || (spec.command && length) || (spec.splice && length>args[4]) || length>PTRDIFF_MAX
            || (t->buffer_registers.regs[0]==MD_BUFFER_PROC_START && (spec.writing || spec.splice))))
        result=-EINVAL;
    if (!result) result=md_proc_file_io_begin(&t->proc_io,t->proc_file,
        spec.command ? MD_PROC_FILE_COMMAND : spec.seek ? MD_PROC_FILE_SEEK : spec.directory ? MD_PROC_FILE_GETDENTS
            : spec.writing ? MD_PROC_FILE_WRITE : MD_PROC_FILE_READ,
        spec.positional,spec.offset,spec.whence,length,fd_wakeup);
    if (result<0) { control_reply(t,result); return; }
    t->proc_waiting=1;
    /* EVENT_WAIT: preceding IO on the captured OFD finishes or is cancelled.
     * Buffer admission precedes this turn; neither waits on a file-table turn. */
    proc_io_progress(t);
}
static int proc_write_capture(struct thread *t,size_t *total) {
    if (!t->proc_file || md_proc_file_kind(t->proc_file)!=MD_PROC_FILE_CONTROL
            || t->buffer.pool || t->proc_io.file || t->proc_direct
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=t->original.regs[i];
    struct md_proc_io_spec spec;
    if (md_proc_io_decode(t->original.regs[8],args,&spec) || !spec.writing || spec.splice) return 0;
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
    if (md_buffers_take(md_memory_buffers(t->memory),&t->buffer)) return 0;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->buffer,&span) && span.size>=MD_CONTROL_IO_MAX);
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
static int proc_write_direct(struct thread *t) {
    size_t total;
    if (!proc_write_capture(t,&total)) return 0;
    t->proc_direct=1; t->phase=DISPATCHING;
    t->buffer_registers=t->original;
    t->buffer_registers.regs[0]=MD_BUFFER_PROC_IO;
    t->buffer_registers.regs[1]=total;
    t->buffer_registers.regs[2]=0;
    if (statistics) ++statistics->captured_writes;
    proc_io_begin(t); return 1;
}
static void buffer_take(struct thread *t) {
    retry_clear(t,RETRY_BUFFER);
    int start=t->buffer_registers.regs[0]==MD_BUFFER_PROC_START;
    int error=md_buffers_take_sized(md_memory_buffers(t->memory),start ? 0 : t->buffer_registers.regs[1],&t->buffer);
    if (error==-EAGAIN) { retry_wait(t,RETRY_BUFFER); return; }
    t->buffer_waiting=0;
    uintptr_t address=error ? 0 : md_memory_buffer_address(t->memory,&t->buffer);
    if (!error && !address) { CHECK(!buffer_release(t)); error=-EINVAL; }
    if (!error && start) { proc_io_begin(t); return; }
    buffer_reply(t,error ? error : (long)address);
}
static void buffer_return_progress(struct thread *t) {
    retry_clear(t,RETRY_RETURN);
    if (!t->buffer_return_waiting) return;
    int result=md_fd_return_progress(&t->buffer_return,fd_wakeup);
    if (result==MD_FD_RETURN_WAIT) { retry_wait(t,RETRY_RETURN); return; }
    if (result>=0) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->buffer,&span));
        struct md_fs_buffer_frame *frame=span.data;
        if (result==MD_FD_RETURN_CHECK) {
            frame->channel.number=t->buffer_return.endpoint;
            frame->channel.observation=(struct md_fd_channel_observation){0};
            result=MD_FS_BUFFER_CHANNEL_CHECK;
        } else {
            frame->receive.number=t->buffer_return.endpoint;
            frame->receive.mapped=t->buffer_return.delivery.receipt!=NULL;
            result=0;
        }
    }
    t->buffer_return_waiting=0;
    buffer_reply(t,result);
}
static int receive_return_begin(struct thread *t, unsigned count, int annotated) {
    struct receive_owner *owner=t->receive;
    struct receive_publication *p=annotated ? owner->publication : NULL;
    struct md_fd_return_publication publication={0};
    if (p) {
        struct md_buffer_span span;
        int error=md_buffer_span(&t->buffer,&span);
        if (error) return error;
        struct md_fs_buffer_frame *frame=span.data;
        publication=(struct md_fd_return_publication){.origins=p->origins,.updates=p->updates,.numbers=p->numbers,
            .memory=(void *)frame->receipt,
            .flags=owner->request.flags&MSG_CMSG_CLOEXEC ? FD_CLOEXEC : 0};
    }
    int result=md_fd_return_vector_begin(&t->buffer_return,t->resources.files,owner->outgoing,count,
        p ? &publication : NULL,fd_wakeup);
    if (!result) { t->buffer_return_waiting=1; buffer_return_progress(t); }
    return result;
}
static int receive_return(struct thread *t, const struct md_received_fds *source, const int *indices, unsigned count) {
    struct receive_owner *owner=t->receive;
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
static void selector_progress(struct thread *t) {
    retry_clear(t,RETRY_TASK_ACTION);
    if (t->action) { task_action_start(t); return; }
    if (md_task_helper_retiring(helpers,t->tgid,t->selector_exit)) { retry_wait(t,RETRY_TASK_ACTION); return; }
    struct user_pt_regs r;
    t->selector_exit=0;
    if (registers(t->pid,&r)) {
        r.regs[0]=0;
        if (set_registers(t->pid,&r)) resume(t->pid,0);
    }
}
static void retry_progress(struct md_pending_node *node,void *context) {
    (void)context;
    struct retry_owner *owner=(struct retry_owner *)node;
    struct thread *t=owner->thread;
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
static void adapter_progress(void) {
    CHECK(!md_fs_buffer_progress(buffer_service));
    retry_dispatch(0);
}
static int network_namespace_capture(void *context,struct md_network_target_reference reference,
        struct md_network_namespace **out) {
    struct thread *t=context;
    if (reference.kind==MD_NETWORK_TARGET_PID) {
        struct thread *target=find_thread(native_pid(t,reference.value));
        if (!target || !target->born || !target->resources.network) return -ESRCH;
        *out=md_network_namespace_retain(target->resources.network); return 0;
    }
    if (reference.kind!=MD_NETWORK_TARGET_FD) return -EINVAL;
    struct md_proc_file *file=proc_file_capture(t,reference.value);
    const struct md_namespace_reference *ref=md_proc_file_namespace_reference(file);
    int error=!ref || ref->kind!=MD_NAMESPACE_NETWORK || (md_proc_file_flags(file)&O_PATH) ? -EINVAL : 0;
    if (!error) *out=md_network_namespace_retain(ref->value.network);
    md_proc_file_release(file); return error;
}
static void buffer_descriptor_start(struct thread *t) {
    retry_clear(t,RETRY_DESCRIPTOR);
    if (md_fd_order_state(&t->buffer_descriptor_order)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_DESCRIPTOR); return; }
    CHECK(t->phase==DISPATCHING && !md_fd_order_start(&t->buffer_descriptor_order));
    int network=t->buffer_descriptor_operation==MD_BUFFER_NETWORK_CALL;
    int fd=network ? (int)t->original.regs[0] : (int)t->buffer_descriptor_argument;
    struct md_fd_object *object=md_fd_origins_object(t->resources.files,fd,NULL);
    struct md_buffer_span span;
    long result=md_buffer_span(&t->buffer,&span),nr=t->original.regs[8];
    if (!result && network) {
        unsigned long args[6]; for (unsigned i=0;i<6;++i) args[i]=t->original.regs[i];
        struct md_network_target resolver={.context=t,.resolve=network_namespace_capture};
        struct md_network_request *request=span.data;
        int sending=nr==SYS_sendto || nr==SYS_sendmsg;
        const struct md_network_send *destination=sending ? &request->destination : NULL;
        if (span.size<sizeof(*request)) result=-EOVERFLOW;
        else if (md_device_events_is(object)) {
            uint32_t groups=0;
            result=md_network_netlink_destination(destination,&groups);
            if (!result) result=groups ? -EOPNOTSUPP
                : md_device_events_call(object,nr,args,request->data,sizeof(request->data),t->buffer_descriptor_argument,visible_pid(t,t->tgid));
        } else result=md_network_socket_call(object,&t->identity,nr,args,request->data,sizeof(request->data),
            t->buffer_descriptor_argument,visible_pid(t,t->tgid),destination,t->resources.pid_group,&resolver);
    } else if (!result) {
        if (!md_descriptor_inode_is(object)) result=-EBADF;
        else if (nr==SYS_fchown || nr==SYS_fchownat) {
            unsigned offset=nr==SYS_fchown ? 1 : 2;
            result=md_descriptor_inode_chown(object,&t->identity,
                (uint32_t)t->original.regs[offset],(uint32_t)t->original.regs[offset+1]);
        } else if (nr==SYS_fchmod || nr==SYS_fchmodat || nr==SYS_fchmodat2)
            result=md_descriptor_inode_chmod(object,&t->identity,(mode_t)t->original.regs[nr==SYS_fchmod ? 1 : 2]);
        else if (nr==SYS_fstat || nr==SYS_newfstatat || nr==SYS_statx)
            result=md_descriptor_inode_stat(object,&t->identity,span.data);
        else result=-EOPNOTSUPP;
    }
    md_fd_object_release(object);
    CHECK(!md_fd_order_complete(&t->buffer_descriptor_order));
    t->buffer_descriptor_operation=0;
    buffer_reply(t,result);
}
static void buffer_descriptor_begin(struct thread *t,const struct user_pt_regs *r) {
    CHECK(!t->buffer_descriptor_order.table);
    t->buffer_descriptor_operation=(unsigned)r->regs[0];
    t->buffer_descriptor_argument=r->regs[1];
    int error=md_fd_order_begin(t->resources.files,&t->buffer_descriptor_order,fd_wakeup);
    if (error<0) { t->buffer_descriptor_operation=0; buffer_reply(t,error); return; }
    /* EVENT_WAIT: preceding close/dup publication, cancelled on task death.
     * Only nonblocking typed operations and retained namespace-FD lookup run
     * under this turn; it ends before the adapter copies the result to userspace. */
    buffer_descriptor_start(t);
}
static void pidfd_begin(struct thread *t,const struct user_pt_regs *r);
static void pidfd_capture_step(struct thread *t,unsigned command,long value);
static void buffer_control(struct thread *t, const struct user_pt_regs *r) {
    long result=-EINVAL;
    if (statistics && r->regs[0]<128) ++statistics->buffer_commands[r->regs[0]];
    if (t->buffer_waiting || t->buffer_return_waiting || (t->buffer_call && !md_fs_buffer_ack_pending(t->buffer_call)
            && !md_fs_buffer_export_pending(t->buffer_call)
            && !md_fs_buffer_snapshot_pending(t->buffer_call)
            && !md_fs_buffer_task_pending(t->buffer_call))) { CHECK(0); }
    t->buffer_registers=*r;
    if (!buffer_service && r->regs[0]!=MD_BUFFER_MAPPED && r->regs[0]!=MD_BUFFER_TAKE
            && r->regs[0]!=MD_BUFFER_RELEASE && r->regs[0]!=MD_BUFFER_PROBE && r->regs[0]!=MD_BUFFER_UTS
            && r->regs[0]!=MD_BUFFER_PROC_IO && r->regs[0]!=MD_BUFFER_PROC_ACK
            && r->regs[0]!=MD_BUFFER_FINISH && r->regs[0]!=MD_BUFFER_PROC_START
            && r->regs[0]!=MD_BUFFER_PROC_RIGHTS && r->regs[0]!=MD_BUFFER_PROC_META && r->regs[0]!=MD_FS_BUFFER_RIGHTS_READY
            && r->regs[0]!=MD_FS_BUFFER_RIGHTS_DONE && r->regs[0]!=MD_FS_BUFFER_CHANNEL_OBSERVED
            && r->regs[0]!=MD_BUFFER_PID_NUMBER && r->regs[0]!=MD_BUFFER_DESCRIPTOR_META && r->regs[0]!=MD_BUFFER_PIDFD_NAMESPACE
            && r->regs[0]!=MD_BUFFER_PIDFD_SIGNAL
            && r->regs[0]!=MD_BUFFER_PIDFD_READY && r->regs[0]!=MD_BUFFER_PIDFD_SENT && r->regs[0]!=MD_BUFFER_PIDFD_OBSERVED
            && r->regs[0]!=MD_BUFFER_CONTROL_IOCTL_SPEC && r->regs[0]!=MD_BUFFER_CONTROL_IOCTL
            && r->regs[0]!=MD_BUFFER_CONTROL_MAP && r->regs[0]!=MD_BUFFER_CONTROL_IOCTL_LAYOUT
            && r->regs[0]!=MD_BUFFER_NETWORK_OPEN && r->regs[0]!=MD_BUFFER_NETWORK_CALL && r->regs[0]!=MD_BUFFER_NETWORK_DATA
            && r->regs[0]!=MD_BUFFER_NETWORK_CREDENTIALS
            && r->regs[0]!=MD_BUFFER_MEMORY_OPEN && r->regs[0]!=MD_BUFFER_MEMORY_OPEN_RESULT
            && !(r->regs[0]>=MD_BUFFER_EPOLL_BEGIN && r->regs[0]<=MD_BUFFER_POLL_COPY)
            && r->regs[0]!=MD_FS_BUFFER_RIGHTS_BEGIN) {
        buffer_reply(t,-ENOTSUP); return;
    }
    if (t->ipc_transport.packet && r->regs[0]!=MD_BUFFER_RELEASE) {
        buffer_reply(t,-EBUSY); return;
    }
    if (t->outgoing && r->regs[0]!=MD_FS_BUFFER_SEND_COMMIT && r->regs[0]!=MD_BUFFER_RELEASE
            && r->regs[0]!=MD_FS_BUFFER_IPC_IO_PREPARE && r->regs[0]!=MD_FS_BUFFER_IPC_IO_COPY
            && r->regs[0]!=MD_FS_BUFFER_IPC_CAPTURE_READY && r->regs[0]!=MD_FS_BUFFER_IPC_CAPTURE_SENT
            && r->regs[0]!=MD_FS_BUFFER_IPC_CAPTURE_OBSERVED) {
        buffer_reply(t,-EBUSY); return;
    }
    int snapshot=md_fs_buffer_snapshot_pending(t->buffer_call);
    if (t->buffer_call && r->regs[0]!=MD_FS_BUFFER_ACK && r->regs[0]!=MD_FS_BUFFER_SENT
            && r->regs[0]!=MD_FS_BUFFER_CHANNEL_OBSERVED
            && !(md_fs_buffer_task_pending(t->buffer_call)
                && (r->regs[0]==MD_BUFFER_RELEASE || r->regs[0]==MD_FS_BUFFER_TASK_DONE))
            && !(snapshot && (r->regs[0]==MD_FS_BUFFER_SNAPSHOT_APPEND
                || r->regs[0]==MD_FS_BUFFER_SNAPSHOT_FINISH || r->regs[0]==MD_BUFFER_RELEASE))) {
        buffer_reply(t,-EBUSY); return;
    }
    if (t->buffer_return.files.table && r->regs[0]!=MD_FS_BUFFER_RIGHTS_READY
            && r->regs[0]!=MD_FS_BUFFER_RIGHTS_DONE && r->regs[0]!=MD_FS_BUFFER_CHANNEL_OBSERVED) {
        buffer_reply(t,-EBUSY); return;
    }
    switch (r->regs[0]) {
    case MD_FS_BUFFER_RESULT_REPLAY: {
        if (!t->result_replay || t->phase!=DISPATCHING || t->buffer_open.fd<0
                || r->regs[1] || r->regs[2]) { result=-EPERM; break; }
        buffer_result_replay(t);
        return;
    }
    case MD_BUFFER_POLL_BEGIN:
        if (t->phase!=DISPATCHING || t->readiness || r->regs[1] || r->regs[2]
                || (t->original.regs[8]!=SYS_ppoll && t->original.regs[8]!=SYS_pselect6)) break;
        readiness_begin(t,r); return;
    case MD_BUFFER_POLL_LOAD:
        if (t->phase!=DISPATCHING || !t->readiness || !t->readiness->poll || t->readiness->done
                || r->regs[1] || r->regs[2]
                || md_fd_order_state(&t->readiness->files)!=MD_RESOURCE_ORDER_RUNNING) break;
        t->readiness->continuation=*r; readiness_poll_load(t); return;
    case MD_BUFFER_POLL_COPY: {
        if (!t->readiness || !t->readiness->poll || !t->readiness->done || r->regs[2]
                || r->regs[1]>t->readiness->poll_count) break;
        struct md_buffer_span span;
        int error=md_buffer_span(&t->buffer,&span);
        if (!error) {
            error=md_poll_wait_copy(t->readiness->poll,span.data,(unsigned)r->regs[1]);
            readiness_poll_elapsed(t,span.data);
        }
        buffer_reply(t,error); return;
    }
    case MD_BUFFER_EPOLL_BEGIN:
        if (t->phase!=DISPATCHING || t->readiness || r->regs[1] || r->regs[2]
                || (t->original.regs[8]!=SYS_epoll_ctl && t->original.regs[8]!=SYS_epoll_pwait
                    && t->original.regs[8]!=SYS_epoll_pwait2)) break;
        readiness_begin(t,r); return;
    case MD_BUFFER_EPOLL_CAPTURE_READY:
    case MD_BUFFER_EPOLL_CAPTURE_SENT:
    case MD_BUFFER_EPOLL_CAPTURE_OBSERVED:
        if (t->phase!=DISPATCHING || !t->readiness || t->readiness->done || r->regs[2]
                || md_fd_order_state(&t->readiness->files)!=MD_RESOURCE_ORDER_RUNNING) break;
        t->readiness->continuation=*r;
        readiness_capture(t,(unsigned)r->regs[0],(long)r->regs[1]); return;
    case MD_BUFFER_EPOLL_WAIT:
        if (t->phase!=DISPATCHING || !t->readiness || t->readiness->done
                || (!t->readiness->received && !t->readiness->native_caller)
                || r->regs[1] || r->regs[2]) break;
        t->readiness->continuation=*r;
        readiness_progress(t); return;
    case MD_FS_BUFFER_IPC_IO_PREPARE:
    case MD_FS_BUFFER_IPC_IO_COPY:
    case MD_FS_BUFFER_IPC_IO_SELECT: {
        if (t->phase!=DISPATCHING || r->regs[2]
                || (r->regs[1] && (r->regs[0]!=MD_FS_BUFFER_IPC_IO_PREPARE
                    || r->regs[1]>MD_IPC_IO_BATCH || !t->receive || !t->receive->request.batch))
                || !((t->outgoing && t->outgoing->brokered && t->outgoing->stage==OUTGOING_READY)
                    || (t->receive && t->receive->brokered && t->receive->received))) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_ipc_io_message *io=t->outgoing ? &((struct md_task_outgoing_frame *)span.data)->scratch.capture.io
            : &((struct md_task_receive_frame *)span.data)->io;
        struct md_ipc_io *op=socket_operation(t);
        if (r->regs[0]==MD_FS_BUFFER_IPC_IO_PREPARE) {
            int fd=t->outgoing ? t->outgoing->prepared->fds[0] : t->receive->received->fds[0];
            int flags=t->outgoing ? t->outgoing->input.flags : t->receive->request.flags;
            int sending=t->outgoing!=NULL;
            if (t->receive && t->receive->network.number) {
                flags=md_network_io_flags(&t->receive->network);
                sending=md_network_io_sending(&t->receive->network);
            }
            result=r->regs[1] ? md_ipc_io_prepare_batch(op,io,fd,flags,(unsigned)r->regs[1])
                : md_ipc_io_prepare(op,io,fd,flags,sending);
        } else if (r->regs[0]==MD_FS_BUFFER_IPC_IO_SELECT)
            result=t->receive && t->receive->network.number
                ? md_network_io_select(&t->receive->network,op,io,io->batch_index)
                : md_ipc_io_select(op,io,io->batch_index);
        else if (io->transfer&~(MD_IPC_IO_CONTROL_BYTES|MD_IPC_IO_READ_BYTES)) result=-EINVAL;
        else result=md_ipc_io_copy(op,io,io->offset,io->length,
            !!(io->transfer&MD_IPC_IO_CONTROL_BYTES),!!(io->transfer&MD_IPC_IO_READ_BYTES));
        break;
    }
    case MD_FS_BUFFER_IPC_CAPTURE_READY:
    case MD_FS_BUFFER_IPC_CAPTURE_SENT:
    case MD_FS_BUFFER_IPC_CAPTURE_OBSERVED:
        if (r->regs[2] || t->phase!=DISPATCHING
                || !((t->outgoing && t->outgoing->brokered && t->outgoing->stage==OUTGOING_CAPTURE)
                    || (t->receive && t->receive->brokered && !t->receive->ready && t->receive->capture.channel))) break;
        if (t->outgoing) t->outgoing->continuation=*r;
        else t->receive->continuation=*r;
        ipc_capture_step(t,(unsigned)r->regs[0],(long)r->regs[1]); return;
    case MD_FS_BUFFER_IPC_RECEIVE:
        if (r->regs[1] || r->regs[2] || !t->receive || !t->receive->brokered
                || t->receive->ready || !t->receive->received) break;
        t->receive->continuation=*r; socket_io_progress(t); return;
    case MD_FS_BUFFER_SEND_PREPARE:
        if (t->phase!=DISPATCHING || t->outgoing || !t->buffer.pool || r->regs[1] || r->regs[2]
                || (t->original.regs[8]!=SYS_sendmsg && t->original.regs[8]!=SYS_sendmmsg
                    && t->original.regs[8]!=SYS_sendto)) break;
        /* EVENT_WAIT: source-table turn, exact capture/reaping and worker
         * preparation; cancellation retires the owner without replay. */
        outgoing_begin(t,r); return;
    case MD_FS_BUFFER_SEND_COMMIT:
        if (!t->outgoing || t->outgoing->stage!=OUTGOING_READY || !t->buffer.pool || r->regs[1] || r->regs[2]) break;
        /* EVENT_WAIT: task-local send and exact helper reaping. No table turn
         * spans blocking IO; a committed result must never be replayed. */
        outgoing_send(t,r); return;
    case MD_FS_BUFFER_TASK_RECEIVE:
        if (t->phase!=DISPATCHING || t->receive || !t->buffer.pool || r->regs[1] || r->regs[2]
                || (t->original.regs[8]!=SYS_recvmsg && t->original.regs[8]!=SYS_recvmmsg)) break;
        /* EVENT_WAIT: private native receive, export and exact helper reaping.
         * Source capture alone holds the table turn, never the blocking IO. */
        receive_begin(t,r); return;
    case MD_BUFFER_NETWORK_DATA:
        if (t->phase!=DISPATCHING || t->receive || !t->buffer.pool || r->regs[1] || r->regs[2]) break;
        /* EVENT_WAIT: exact socket capture followed by the common cancellable
         * native IO owner. No file-table turn spans connect or accept. */
        receive_begin(t,r); return;
    case MD_FS_BUFFER_RECEIVED_DROP:
        if (!t->receive || (!t->receive->ready && !t->receive->brokered)
                || t->receive->io_waiting || r->regs[1] || r->regs[2]) break;
        receive_release(t->receive); t->receive=NULL;
        result=0; break;
    case MD_FS_BUFFER_RECEIVED_INSPECT: {
        if (!t->receive || !t->receive->ready || !t->buffer.pool || r->regs[1] || r->regs[2]) break;
        /* EVENT_WAIT: worker classifies the retained vector. Task death cancels
         * publication; its independent custody pin survives until completion. */
        result=md_fs_buffer_inspect_begin(buffer_service,t->pid,t->resources.fs,
            &t->buffer,t->receive->received,&t->buffer_call);
        if (!result) return;
        break;
    }
    case MD_FS_BUFFER_RECEIVED_RIGHTS: {
        if (!t->receive || !t->receive->ready || !t->receive->received
                || !t->buffer.pool || r->regs[1] || r->regs[2]) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        const struct md_fs_buffer_frame *frame=span.data;
        unsigned count=frame->rights.count;
        if (!count || count>253) { result=-EINVAL; break; }
        int indices[253]; memcpy(indices,frame->rights.index,count*sizeof(*indices));
        if (frame->rights.source!=MD_RECEIVED_KERNEL && frame->rights.source!=MD_RECEIVED_APPLICATION) {
            result=-EINVAL; break;
        }
        int expanded=frame->rights.source==MD_RECEIVED_APPLICATION && t->receive->expanded;
        const struct md_received_fds *source=expanded
            ? t->receive->expanded : t->receive->received;
        for (unsigned i=0;i<count;++i) {
            int index=indices[i];
            if (index<(expanded ? 0 : 1) || (unsigned)index>=source->count) { result=-EBADF; break; }
        }
        if (result) break;
        result=receive_return(t,source,indices,count);
        if (result) break;
        return;
    }
    case MD_BUFFER_PROBE:
        if (t->entered || t->phase!=IDLE || !t->buffer.pool || t->probe
                || t->capture || t->mapping || r->regs[1] || r->regs[2]) break;
        /* EVENT_WAIT: the production helper's private table is fully reaped
         * before readiness; the launch deadline bounds missing completion. */
        probe_begin(t,r); return;
    case MD_BUFFER_MAPPED:
        if (t->entered || t->phase!=IDLE) break;
        result=md_memory_buffers_mapped(t->memory,r->regs[1],r->regs[2]);
        TRACE("PROBE buffer-mapping pid=%d bytes=%llu result=%ld\n",t->pid,r->regs[2],result);
        break;
    case MD_BUFFER_TAKE:
        if (t->buffer.pool || r->regs[2]) break;
        if (t->phase==DISPATCHING && t->original.regs[8]==SYS_ppoll) {
            /* The process owner commits every NOFILE update after native
             * success and shares it across threads. No foreign prlimit/proc
             * read or guest-filtered helper syscall belongs in each poll. */
            if (t->original.regs[1]>md_process_native_nofile(t->resources.process)) {
                result=-EINVAL; break;
            }
        }
        t->buffer_waiting=1; buffer_take(t);
        /* EVENT_WAIT: a task or worker releases a mapped slot. Owned process
         * cancellation ends the wait; no polling or guest retry is required. */
        return;
    case MD_FS_BUFFER_CALL:
        if (!t->buffer.pool || t->buffer_open.fd>=0 || r->regs[2]) break;
        result=md_fs_buffer_begin(buffer_service,t->pid,t->resources.fs,(unsigned)r->regs[1],&t->buffer,&t->buffer_call);
        if (!result) {
            /* EVENT_WAIT: filesystem context admission and exact worker
             * completion; launch cancellation detaches, never replays a call. */
            adapter_progress(); return;
        }
        break;
    case MD_FS_BUFFER_PROC_LINK: {
        if (!t->buffer.pool || t->buffer_open.fd>=0 || t->phase!=DISPATCHING
                || !r->regs[1] || r->regs[1]>INT_MAX || r->regs[2]) break;
        struct thread *target=find_thread((pid_t)r->regs[1]);
        if (!target || !proc_allowed(t->pid,target->pid)) { result=-EACCES; break; }
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_fs_buffer_frame *frame=span.data;
        result=md_process_image_link(target->pid,target->process_image,process_image_memory(t,target),frame->reply.data,PATH_MAX);
        break;
    }
    case MD_FS_BUFFER_EXEC_PREPARE:
        if (!t->buffer.pool || t->buffer_open.fd>=0 || r->regs[1]!=MD_FS_OPEN_IMAGE || r->regs[2]
                || t->exec_images[0]>=0 || t->exec_images[1]>=0 || t->exec_images[2]>=0
                || !((t->phase==IDLE && !t->entered && !t->mapped_image)
                    || (t->phase==DISPATCHING
                        && (t->original.regs[8]==SYS_execve || t->original.regs[8]==SYS_execveat)))) break;
        result=md_fs_buffer_exec_begin(buffer_service,t->pid,t->resources.fs,&t->buffer,&t->buffer_call,
            md_debugger_traced(debugger,t->pid));
        if (!result) { adapter_progress(); return; }
        break;
    case MD_FS_BUFFER_EXEC_CANCEL:
        if (r->regs[1] || r->regs[2] || (t->phase!=DISPATCHING && (t->phase!=IDLE || t->entered))) break;
        exec_images_release(t); result=0; break;
    case MD_FS_BUFFER_SNAPSHOT_BEGIN:
        if (!t->buffer.pool || t->buffer_open.fd>=0 || r->regs[1]>UINT_MAX || r->regs[2]) break;
        result=md_fs_buffer_snapshot_begin(buffer_service,t->pid,t->resources.fs,
            &t->buffer,(unsigned)r->regs[1],&t->buffer_call);
        if (!result) { adapter_progress(); return; }
        break;
    case MD_FS_BUFFER_SNAPSHOT_APPEND:
    case MD_FS_BUFFER_SNAPSHOT_FINISH:
        if (!snapshot || r->regs[1] || r->regs[2]) break;
        result=md_fs_buffer_snapshot_advance(t->buffer_call,r->regs[0]==MD_FS_BUFFER_SNAPSHOT_FINISH);
        /* EVENT_WAIT: worker appends captured bytes or publishes the result;
         * cancellation retains ownership until the worker completes. */
        if (!result) return;
        md_fs_buffer_cancel(&t->buffer_call);
        break;
    case MD_BUFFER_RELEASE:
        if (r->regs[1] || r->regs[2]) break;
        result=buffer_cleanup(t); break;
    case MD_FS_BUFFER_TASK_DONE:
        if (r->regs[2] || t->fd_running) break;
        result=md_fs_buffer_task_finish(&t->buffer_call,(long)r->regs[1]);
        break;
    case MD_BUFFER_IPC_PREPARE: {
        if (!t->buffer.pool || t->buffer_open.fd>=0 || t->phase!=DISPATCHING
                || r->regs[1] || r->regs[2]) break;
        struct md_ipc_request call={.nr=t->original.regs[8]};
        for (unsigned i=0;i<6;++i) call.args[i]=t->original.regs[i];
        struct md_buffer_span span;
        struct md_ipc *ipc=NULL;
        result=md_ipc_namespace_semaphores(t->resources.ipc,&ipc);
        if (!result) result=md_buffer_span(&t->buffer,&span);
        if (!result) result=md_ipc_transport_prepare(ipc,&t->ipc_transport,&call,
            t->ipc_pending!=NULL,span.data,span.size);
        break;
    }
    case MD_BUFFER_NETWORK_OPEN: {
        if (t->phase!=DISPATCHING || t->original.regs[8]!=SYS_socket || !t->buffer.pool
                || t->buffer_open.fd>=0 || r->regs[1] || r->regs[2]) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_device_events *events=md_fs_worker_device_events(filesystem);
        result=events && t->original.regs[0]==AF_NETLINK && t->original.regs[2]==NETLINK_KOBJECT_UEVENT
            ? md_device_events_socket(events,(int)t->original.regs[1],&t->buffer_open.object,&t->buffer_open.fd)
            : md_network_socket_open(t->resources.network,&t->identity,(int)t->original.regs[0],
                (int)t->original.regs[1],(int)t->original.regs[2],&t->buffer_open.object,&t->buffer_open.fd);
        if (!result) {
            t->buffer_open.flags=t->original.regs[1]&SOCK_CLOEXEC;
            ((struct md_fs_buffer_frame *)span.data)->transfer=MD_FS_BUFFER_RIGHTS;
        }
        break;
    }
    case MD_BUFFER_NETWORK_CALL: {
        if (t->phase!=DISPATCHING || !t->buffer.pool || r->regs[1]>MD_NETWORK_BUFFER_SIZE || r->regs[2]) break;
        buffer_descriptor_begin(t,r); return;
    }
    case MD_BUFFER_NETWORK_CREDENTIALS: {
        if (t->phase!=DISPATCHING || !t->buffer.pool || r->regs[1] || r->regs[2]
                || t->original.regs[8]!=SYS_recvmsg) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
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
    case MD_BUFFER_DESCRIPTOR_META: {
        if (t->phase!=DISPATCHING || !t->buffer.pool || r->regs[2]!=t->original.regs[8]) break;
        buffer_descriptor_begin(t,r); return;
    }
    case MD_BUFFER_PIDFD_NAMESPACE:
        if (t->phase!=DISPATCHING || t->proc_file || t->original.regs[8]!=SYS_ioctl
                || r->regs[1]!=t->original.regs[0] || r->regs[2]!=t->original.regs[1]
                || !md_pidfd_namespace_command((unsigned)r->regs[2])) break;
        pidfd_begin(t,r); return;
    case MD_BUFFER_CLONE_CGROUP: {
        if (t->phase!=DISPATCHING || t->original.regs[8]!=SYS_clone3
                || !t->buffer.pool || t->fd_job!=MD_FD_USE || r->regs[2]
                || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING) break;
        struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
        const struct md_clone_request *request=span.data;
        if (!(request->args.flags&CLONE_INTO_CGROUP) || r->regs[1]!=request->args.cgroup) break;
        if (t->clone_cgroup) break;
        t->clone_cgroup=md_fd_origins_get(t->resources.files,(int)r->regs[1],NULL);
        buffer_reply(t,t->clone_cgroup ? 0 : 1); return;
    }
    case MD_BUFFER_PIDFD_SIGNAL:
        if (t->phase!=DISPATCHING || t->original.regs[8]!=SYS_pidfd_send_signal || r->regs[1] || r->regs[2]) break;
        pidfd_begin(t,r); return;
    case MD_BUFFER_PIDFD_READY: case MD_BUFFER_PIDFD_SENT: case MD_BUFFER_PIDFD_OBSERVED:
        if (t->phase!=DISPATCHING || !t->pidfd_capture.count || r->regs[2]) break;
        pidfd_capture_step(t,(unsigned)r->regs[0],(long)r->regs[1]); return;
    case MD_BUFFER_PROC_META: {
        if (t->phase!=DISPATCHING || r->regs[1] || r->regs[2] || !t->buffer.pool) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_proc_metadata_request query; struct md_proc_metadata_result metadata;
        if (span.size<sizeof(query) || span.size<sizeof(metadata)) { result=-EOVERFLOW; break; }
        memcpy(&query,span.data,sizeof(query));
        result=proc_node_metadata(t,&query,&metadata);
        TRACE("PROBE proc metadata pid=%d target=%d kind=%u access=%u result=%ld\n",
            t->pid,query.process,query.kind,query.access,result);
        if (!result) memcpy(span.data,&metadata,sizeof(metadata));
        break;
    }
    case MD_BUFFER_PID_NUMBER:
        if (t->phase!=DISPATCHING || r->regs[2]>1) break;
        result=r->regs[2] ? native_pid(t,(pid_t)r->regs[1]) : visible_pid(t,(pid_t)r->regs[1]); break;
    case MD_BUFFER_MEMORY_OPEN: {
        if (t->phase!=DISPATCHING || r->regs[2]) break;
        struct thread *target=find_thread((pid_t)r->regs[1]);
        result=md_memory_exposure_begin(target ? target->memory : NULL,&t->memory_exposure);
        break;
    }
    case MD_BUFFER_MEMORY_OPEN_RESULT:
        if (t->phase!=DISPATCHING || r->regs[2]) break;
        TRACE("PROBE memory-open-result pid=%d result=%ld tracked=%d\n",
            t->pid,(long)r->regs[1],t->memory_exposure!=NULL);
        md_memory_exposure_end(&t->memory_exposure,(long)r->regs[1]>=0);
        result=0; break;
    case MD_BUFFER_CONTROL_MAP: {
        if (t->phase!=DISPATCHING || !t->proc_file || t->original.regs[8]!=SYS_mmap
                || !t->buffer.pool || t->control_pending || t->control_mapping_fd>=0
                || r->regs[1] || r->regs[2]) break;
        struct md_control_file file=md_proc_file_control(t->proc_file);
        if (!file.ops || !file.ops->mapping) { result=-ENODEV; break; }
        struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,
            .resources=&t->resources,.wakeup=fd_wakeup};
        result=file.ops->mapping(file.context,&actor,(unsigned)t->original.regs[2],
            (unsigned)t->original.regs[3],&t->control_pending);
        if (t->control_pending) {
            if (result==-EINPROGRESS) {
                t->control_size=0; retry_wait(t,RETRY_CONTROL); control_park(t); return;
            }
            t->control_pending->dispose(t->control_pending); t->control_pending=NULL;
        } else if (result==-EINPROGRESS) result=-EPROTO;
        control_reply(t,result); return;
    }
    case MD_BUFFER_CONTROL_IOCTL_SPEC: case MD_BUFFER_CONTROL_IOCTL_LAYOUT: case MD_BUFFER_CONTROL_IOCTL: {
        if (t->phase!=DISPATCHING || !t->proc_file || t->original.regs[8]!=SYS_ioctl
                || r->regs[1] || r->regs[2]) break;
        struct md_control_file file=md_proc_file_control(t->proc_file);
        if (!file.ops || !file.ops->ioctl_spec || (!file.ops->ioctl && !file.ops->ioctl_async)) { result=-ENOTTY; break; }
        struct md_control_ioctl spec={0};
        unsigned command=(unsigned)t->original.regs[1];
        result=file.ops->ioctl_spec(file.context,command,&spec);
        if (result) break;
        long size=md_control_ioctl_size(&spec);
        if (size<0) { result=size; break; }
        if (r->regs[0]==MD_BUFFER_CONTROL_IOCTL_SPEC) {
            result=(long)((uint64_t)spec.size|((uint64_t)spec.direction<<32)); break;
        }
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (!result && (size_t)size>span.size) result=-EOVERFLOW;
        if (r->regs[0]==MD_BUFFER_CONTROL_IOCTL_LAYOUT) {
            if (!result && (!(spec.direction&MD_CONTROL_INDIRECT) || span.size<sizeof(spec))) result=-EINVAL;
            if (!result) memcpy(span.data,&spec,sizeof(spec));
            break;
        }
        struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources};
        if (!result) result=file.ops->ioctl_async
            ? file.ops->ioctl_async(file.context,&actor,command,t->original.regs[2],span.data,(size_t)size,&t->control_pending)
            : file.ops->ioctl(file.context,&actor,command,t->original.regs[2],span.data,(size_t)size);
        if (t->control_pending) {
            if (result==-EINPROGRESS) {
                /* EVENT_WAIT: device-provider completion or request deadline;
                 * no file-table/OFD turn is held across a userspace reply. */
                t->control_size=(size_t)size; retry_wait(t,RETRY_CONTROL); control_park(t); return;
            }
            t->control_pending->dispose(t->control_pending); t->control_pending=NULL;
        }
        break;
    }
    case MD_BUFFER_PROC_START:
        if (t->phase!=DISPATCHING || !t->proc_file || t->buffer.pool || t->proc_io.file || r->regs[2]) break;
        t->buffer_waiting=1; buffer_take(t); return;
    case MD_BUFFER_PROC_IO: {
        if (t->phase!=DISPATCHING || !t->proc_file || !t->buffer.pool
                || r->regs[2]) break;
        proc_io_begin(t); return;
    }
    case MD_BUFFER_PROC_ACK:
        if (t->phase!=DISPATCHING || r->regs[2]>1 || (long)r->regs[1]<-4095) break;
        result=r->regs[2] ? md_proc_file_io_advance(&t->proc_io,(long)r->regs[1])
            : md_proc_file_io_finish(&t->proc_io,(long)r->regs[1]); break;
    case MD_BUFFER_FINISH: {
        long value=(long)r->regs[2];
        if (t->phase!=DISPATCHING || (long)r->regs[1]<-4095
                || (value<-4095 && value!=MD_CALL_NATIVE
                    && value!=RESTART_SIGNAL && value!=RESTART_PREPARATION)) break;
        result=buffer_result_finish(t,(long)r->regs[1],value);
        dispatch_complete(t,result); adapter_progress(); return;
    }
    case MD_BUFFER_UTS: {
        if (!t->buffer.pool || t->buffer_open.fd>=0 || t->phase!=DISPATCHING || r->regs[1] || r->regs[2]) break;
        long nr=t->original.regs[8];
        if (nr!=SYS_uname && nr!=SYS_sethostname && nr!=SYS_setdomainname) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (!result && !t->resources.uts) result=-ENOTSUP;
        if (!result && nr==SYS_uname) md_uts_namespace_read(t->resources.uts,span.data);
        else if (!result) result=!md_identity_capable_in(&t->identity,md_uts_namespace_owner(t->resources.uts),CAP_SYS_ADMIN) ? -EPERM
            : md_uts_namespace_set(t->resources.uts,nr==SYS_setdomainname,span.data,t->original.regs[1]);
        break;
    }
    case MD_BUFFER_SHM_STAT: {
        if (!t->buffer.pool || t->buffer_open.fd>=0 || t->phase!=DISPATCHING || r->regs[2]
                || t->original.regs[8]!=SYS_shmctl
                || ((unsigned)t->original.regs[1]&~IPC_64)!=IPC_STAT
                || r->regs[1]!=t->original.regs[0]) break;
        struct md_buffer_span span;
        struct md_shm *shm=NULL;
        result=md_ipc_namespace_memory(t->resources.ipc,&shm);
        if (!result) result=md_buffer_span(&t->buffer,&span);
        if (!result) {
            unsigned long args[4]={r->regs[1],0,0,0};
            result=md_shm_call(shm,t->shm_space,NULL,&t->identity,t->tgid,t->resources.pid_group,
                MD_SHM_STAT,args,span.data);
        }
        break;
    }
    case MD_FS_BUFFER_ACK:
        if (r->regs[2] || (long)r->regs[1]>0 || (long)r->regs[1]<-4095) break;
        result=md_fs_buffer_ack(t->buffer_call,(int)r->regs[1]);
        /* EVENT_WAIT: worker commits or rejects reserved records/cursor;
         * process cancellation retires an unconfirmed delivery. */
        if (!result) return;
        md_fs_buffer_cancel(&t->buffer_call);
        break;
    case MD_FS_BUFFER_SENT:
        if (r->regs[2] || (long)r->regs[1]>0 || (long)r->regs[1]<-4095) break;
        result=md_fs_buffer_export_sent(t->buffer_call,(int)r->regs[1]);
        if (!result) { adapter_progress(); return; }
        break;
    case MD_FS_BUFFER_CHANNEL_OBSERVED:
        if (!t->buffer.pool || r->regs[1] || r->regs[2]) break;
        if (t->buffer_call) {
            result=md_fs_buffer_channel_observed(t->buffer_call);
            if (!result) { adapter_progress(); return; }
        } else if (t->buffer_return.checking) {
            struct md_buffer_span span;
            result=md_buffer_span(&t->buffer,&span);
            if (result) break;
            const struct md_fs_buffer_frame *frame=span.data;
            result=md_fd_return_observed(&t->buffer_return,&frame->channel.observation);
            if (!result) { t->buffer_return_waiting=1; buffer_return_progress(t); return; }
        }
        break;
    case MD_BUFFER_PROC_RIGHTS:
    case MD_FS_BUFFER_RIGHTS_BEGIN: {
        int proc=r->regs[0]==MD_BUFFER_PROC_RIGHTS;
        if (!t->buffer.pool || r->regs[1] || r->regs[2] || t->buffer_rights_delivered) break;
        if (proc ? !t->proc_file || t->proc_image_fd<0
                : t->buffer_open.fd<0) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_fs_buffer_frame *frame=span.data;
        if (!proc && frame->transfer!=MD_FS_BUFFER_RIGHTS) { result=-EINVAL; break; }
        int flags=(proc ? md_proc_file_flags(t->proc_file) : t->buffer_open.flags)&O_CLOEXEC ? FD_CLOEXEC : 0;
        struct md_fd_object *object=proc ? md_proc_file_object(t->proc_file) : md_fs_buffer_open_object(&t->buffer_open);
        frame->reply.result.selectors=md_fd_object_selectors(object);
        result=md_fd_return_begin(&t->buffer_return,t->resources.files,proc ? t->proc_image_fd : t->buffer_open.fd,
            object,flags,(void *)frame->receipt,fd_wakeup);
        if (result) break;
        t->buffer_return_waiting=1;
        /* EVENT_WAIT: destination table and shared channel become available;
         * terminal cleanup publishes the exact receipt and drains queued rights. */
        buffer_return_progress(t); return;
    }
    case MD_FS_BUFFER_RIGHTS_READY:
        if (!t->buffer_return.files.table || t->buffer_return.endpoint<0 || t->buffer_return.offered
                || r->regs[1] || r->regs[2]) break;
        t->buffer_return_waiting=1; buffer_return_progress(t); return;
    case MD_FS_BUFFER_RIGHTS_DONE: {
        if (!t->buffer.pool || r->regs[2] || (long)r->regs[1]<-4095) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        const struct md_fs_buffer_frame *frame=span.data;
        result=!t->buffer_return.files.table && (long)r->regs[1]<0 ? (long)r->regs[1]
            : md_fd_return_finish(&t->buffer_return,(long)r->regs[1],frame->receive.token);
        t->buffer_rights_delivered=1;
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}
static void fd_progress(void) {
    md_event_acknowledge(fd_ready);
    freezer_progress();
    retry_dispatch(1);
    adapter_progress();
}
static pid_t visible_pid(struct thread *t, pid_t native) {
    struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
    if (!space || native<=0) return native;
    struct md_pid_identity *id=md_pid_namespace_find(space,native);
    pid_t number=md_pid_identity_number(id,space);
    md_pid_identity_release(id); return number;
}
static pid_t native_pid(struct thread *t,pid_t visible) {
    struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
    if (!space || visible<=0) return visible;
    struct md_pid_identity *id=md_pid_namespace_lookup(space,visible);
    pid_t number=md_pid_identity_native(id);
    md_pid_identity_release(id); return number;
}
static int signal_identity_allowed(const struct thread *from,const struct md_identity *identity,pid_t group,int signal) {
    return from->tgid==group || from->identity.uid.real==identity->uid.real
        || from->identity.uid.real==identity->uid.saved
        || from->identity.uid.effective==identity->uid.real
        || from->identity.uid.effective==identity->uid.saved
        || md_identity_capable_in(&from->identity,identity->user_namespace,CAP_KILL)
        || (signal==SIGCONT && getsid(from->tgid)==getsid(group));
}
static int signal_allowed(const struct thread *from,const struct thread *to,int signal) {
    return signal_identity_allowed(from,&to->identity,to->tgid,signal);
}
static int namespace_init(const struct thread *t) {
    return md_pid_identity_number(t->resources.pid_group,md_pid_identity_namespace(t->resources.pid))==1;
}
/* Keep stop information native while a guest debugger owns it. Translate only
 * on actual delivery, in the recipient's namespaces rather than the tracer's. */
static void deliver_signal(pid_t pid,int sig,int restored) {
    struct thread *t=thread(pid);
    if (sig && sig!=SIGSTOP && !t->signal_delivery.active
            && ((!restored && md_signal_delivery_pending(&t->signal_delivery))
                || t->signal_shielded || t->metadata_restart)) {
        /* A lower-level owner can finish at an architectural boundary while
         * a higher-level restart still owns that boundary. Retain its signal
         * until every internal owner has restored the application context. */
        siginfo_t info;
        if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"retained-signal-info")) return;
        if (info.si_code<=0 && info.si_signo<32) {
            int retained=md_signal_delivery_retain(&t->signal_delivery,pid,&info); CHECK(retained>=0);
            resume(pid,retained); return;
        }
    }
    if (sig && t->resources.pid) {
        siginfo_t info;
        if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"pid-signal-info")) return;
        int fault=info.si_code>0 && (sig==SIGSEGV || sig==SIGBUS || sig==SIGILL
            || sig==SIGFPE || sig==SIGSYS || sig==SIGTRAP);
        if (namespace_init(t) && sig!=SIGKILL && sig!=SIGSTOP && !fault) {
            uint64_t caught=0;
            long error=md_task_signal_handlers(t->pid,&caught);
            if (error) {
                fprintf(stderr,"Shroot: cannot observe PID init signal disposition: %ld\n",error);
                syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
                resume(pid,0); return;
            }
            if (!(caught&(UINT64_C(1)<<(sig-1)))) { resume(pid,0); return; }
        }
        if (info.si_signo==SIGCHLD || info.si_code==SI_USER || info.si_code==SI_TKILL) {
            info.si_uid=process_signal_uid(t,info.si_pid,info.si_uid);
            info.si_pid=visible_pid(t,info.si_pid);
            if (!tracee_request(t,PTRACE_SETSIGINFO,NULL,&info,"pid-signal-sender")) return;
        }
    }
    resume(pid,sig);
}
static void resume_signal(pid_t pid,int sig) {
    /* Debugger release has already passed ordinary signal admission. */
    deliver_signal(pid,sig,1);
}
static int init_signal_suppressed(const struct thread *from,const struct thread *to,int signal) {
    if ((signal!=SIGKILL && signal!=SIGSTOP) || !namespace_init(to)) return 0;
    /* Kernel-only signals may force a child init only from an ancestor PID
     * namespace. Other signals retain blocking/handler semantics at delivery. */
    return md_pid_identity_namespace(from->resources.pid)==md_pid_identity_namespace(to->resources.pid);
}
static void pidfd_result(struct thread *t,struct md_pid_identity *identity,pid_t native,long error) {
    struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
    struct md_pidfd_frame *frame=span.data;
    int namespace=t->original.regs[8]==SYS_ioctl;
    struct thread *target=native>0 ? find_thread(native) : NULL;
    if (!error && (native<0 || (identity && (!target || target->resources.pid_group!=identity)))) error=-ESRCH;
    if (!error && target && !target->born) error=-ESRCH;
    if (!error && !target) error=namespace ? MD_CALL_NATIVE : -ESRCH;
    else if (!error && namespace) {
        unsigned command=(unsigned)t->original.regs[1];
        if (t->original.regs[2]) error=-EINVAL;
        else if (target!=t && !proc_allowed(t->pid,target->pid)) error=-EACCES;
        else if (command==0xff07 || command==0xff08) error=-EOPNOTSUPP;
        else {
            static const enum md_namespace_kind kinds[]={MD_NAMESPACE_CGROUP,MD_NAMESPACE_IPC,
                MD_NAMESPACE_MOUNT,MD_NAMESPACE_NETWORK,MD_NAMESPACE_PID,MD_NAMESPACE_PID_CHILDREN,
                MD_NAMESPACE_USER,MD_NAMESPACE_USER,MD_NAMESPACE_USER,MD_NAMESPACE_UTS};
            struct md_namespace_reference ref=md_namespace_reference_capture(kinds[command-0xff01],
                &target->resources,target->identity.user_namespace);
            t->proc_file=md_proc_file_open_namespace(&ref,&t->identity,O_RDONLY|O_CLOEXEC);
            md_namespace_reference_release(&ref);
            if (!t->proc_file) error=-errno;
        }
    } else if (!error) {
        int signal=(int)t->original.regs[1],code=frame->code;
        if (signal<0 || signal>=NSIG || t->original.regs[3]) error=-EINVAL;
        else if (!signal_allowed(t,target,signal)) error=-EPERM;
        else if (t->original.regs[2] && (code>=0 || code==SI_TKILL) && target->tgid!=t->tgid) error=-EPERM;
        else error=init_signal_suppressed(t,target,signal);
    }
    frame->result=error;
    buffer_reply(t,MD_PIDFD_DONE);
}
static void pidfd_capture_step(struct thread *t,unsigned command,long value) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->buffer,&span);
    struct md_pidfd_frame *frame=error ? NULL : span.data;
    if (!error && command==MD_BUFFER_PIDFD_SENT) error=md_fd_capture_sent(&t->pidfd_capture,value);
    if (!error && command==MD_BUFFER_PIDFD_OBSERVED)
        error=md_fd_capture_observed(&t->pidfd_capture,t->resources.files,&frame->capture);
    if (!error) error=md_fd_capture_progress(&t->pidfd_capture,t->resources.files,t->tgid,1,&frame->capture);
    if (error>0) { buffer_reply(t,error); return; }
    pid_t native=0;
    struct md_received_fds *captured=error ? NULL : md_fd_capture_take(&t->pidfd_capture);
    if (!error) error=!captured || captured->count!=1 ? -EPROTO : md_pid_descriptor_read(captured->fds[0],&native);
    md_fd_capture_cancel(&t->pidfd_capture);
    pidfd_result(t,NULL,native,error);
    md_received_fds_release(captured);
}
static void pidfd_begin(struct thread *t,const struct user_pt_regs *r) {
    (void)r;
    if (!t->buffer.pool || t->pidfd_capture.count || t->fd_job!=MD_FD_USE
            || md_fd_order_state(&t->fd_order)!=MD_RESOURCE_ORDER_RUNNING) { buffer_reply(t,-EINVAL); return; }
    struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)t->original.regs[0],NULL);
    struct md_pid_identity *identity=md_pid_descriptor_identity(object);
    if (identity) {
        pidfd_result(t,identity,md_pid_identity_native(identity),0); md_fd_object_release(object); return;
    }
    md_fd_object_release(object);
    pidfd_capture_step(t,MD_BUFFER_PIDFD_READY,0);
}
struct signal_selection {
    struct thread *from;
    pid_t selector,group,after,next;
    int signal,found,suppressed;
};
static int signal_candidate(struct signal_selection *s,pid_t pid,const struct md_identity *identity) {
    if (pid<=s->after || (s->next && pid>=s->next)) return 0;
    pid_t visible=visible_pid(s->from,pid);
    if (!visible || (s->selector==-1 && (visible==1 || pid==s->from->tgid))
            || (s->selector!=-1 && getpgid(pid)!=s->group)) return 0;
    s->found=1;
    if (!signal_identity_allowed(s->from,identity,pid,s->signal)) return 0;
    s->next=pid; return 1;
}
static void signal_retired_candidate(void *context,pid_t pid,struct md_task_retired_view view) {
    struct signal_selection *s=context;
    if (signal_candidate(s,pid,view.identity)) s->suppressed=0;
}
static long signal_process_next(struct thread *from,pid_t selector,int signal,pid_t after) {
    if (signal<0 || signal>=NSIG) return -EINVAL;
    pid_t group=selector==0 ? getpgid(from->tgid)
        : selector < -1 && selector!=INT32_MIN ? native_pid(from,-selector) : 0;
    if (selector!=-1 && group<=0) return -ESRCH;
    struct signal_selection selection={.from=from,.selector=selector,.group=group,.after=after,.signal=signal};
    for (struct thread *to=threads;to;to=to->next) {
        if (!to->pid || to->pid!=to->tgid || !to->born) continue;
        if (signal_candidate(&selection,to->pid,&to->identity))
            selection.suppressed=init_signal_suppressed(from,to,signal);
    }
    md_task_registry_retired_processes(tasks,signal_retired_candidate,&selection);
    return selection.next ? ((long)selection.next<<1)|selection.suppressed
        : selection.found ? -EPERM : after ? 0 : -ESRCH;
}
static int process_call(struct thread *t,struct user_pt_regs *r,unsigned long *cookie) {
    long nr=r->regs[8];
    int signaling=nr==SYS_kill || nr==SYS_tkill || nr==SYS_tgkill
        || nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo || nr==SYS_pidfd_send_signal;
    /* A later FD selector can replace the PID selector's cookie, including
     * after close/reuse. Terminal IDs still belong to the caller's PID view. */
    int terminal=t->resources.pid && t->phase==IDLE && r->pc!=RAW_GATE
        && r->regs[8]==SYS_ioctl && md_terminal_identity_command((unsigned)r->regs[1]);
    /* Guest credentials never authorize signals against Android's executor
     * UID. The baseline already observes these calls; PID views only add ID
     * translation. Internal delivery uses its validated, positive native PID. */
    if (*cookie!=MD_INTERCEPT_PROCESS && !terminal
            && !(signaling && t->phase==IDLE && r->pc!=RAW_GATE)) return 0;
    *cookie=MD_INTERCEPT_NATIVE;
    if (t->phase!=IDLE) return 0;
    if (nr==SYS_pidfd_open) {
        struct user_pt_regs native=*r;
        native.regs[0]=native_pid(t,(pid_t)r->regs[0]);
        if (!native.regs[0]) { return_value(t,r,-ESRCH); return 1; }
        if (!set_registers(t->pid,&native)) return 1;
        fd_begin(t,&native,0); return 1;
    }
    if (nr==SYS_pidfd_send_signal) {
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"pidfd-signal-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return 1;
    }
    if (!t->resources.pid && !signaling) return 0;
    if (nr==SYS_futex) {
        /* The PID selector intercepts only PI operations. Passing a logical
         * owner TID to the native PI state can deadlock even after unlock.
         * musl exposes LOCK_PI's error from mutexattr_setprotocol, so report
         * unsupported protocol. glibc probes UNLOCK_PI and requires ENOSYS
         * to reject PI mutex creation. Neither probe may modify the word. */
        unsigned op=(unsigned)r->regs[1]&FUTEX_CMD_MASK;
        return_value(t,r,op==FUTEX_UNLOCK_PI ? -ENOSYS : -EOPNOTSUPP); return 1;
    }
    if (nr==SYS_ioctl) {
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"pid-terminal-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return 1;
    }
    if (nr==SYS_getpid || nr==SYS_gettid) {
        pid_t target=nr==SYS_getpid ? md_pid_identity_number(t->resources.pid_group,
            md_pid_identity_namespace(t->resources.pid)) : visible_pid(t,t->pid);
        return_value(t,r,target); return 1;
    }
    if (nr==SYS_kill && (pid_t)r->regs[0]<=0) {
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"pid-group-signal-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return 1;
    }
    struct user_pt_regs native=*r;
    if (nr==SYS_ioprio_get || nr==SYS_ioprio_set) {
        /* User-wide selection needs guest credential enumeration; never pass
         * it through to unrelated processes sharing the Android executor UID. */
        unsigned which=(unsigned)r->regs[0];
        if (which==IOPRIO_WHO_USER) { return_value(t,r,-EOPNOTSUPP); return 1; }
        if (which==IOPRIO_WHO_PROCESS || which==IOPRIO_WHO_PGRP) {
            pid_t target=(pid_t)r->regs[1];
            if (target) {
                pid_t mapped=target>0 ? native_pid(t,target) : 0;
                if (!mapped) { return_value(t,r,-ESRCH); return 1; }
                native.regs[1]=mapped;
            }
        }
    }
    if (nr==SYS_prctl) {
        int init=md_pid_identity_number(t->resources.pid_group,md_pid_identity_namespace(t->resources.pid))==1;
        if (init && r->regs[0]==PR_SET_CHILD_SUBREAPER) {
            md_pid_identity_set_subreaper(t->resources.pid_group,r->regs[1]!=0);
            return_value(t,r,0); return 1;
        }
        if (init && r->regs[0]==PR_GET_CHILD_SUBREAPER) {
            t->process_return=*r; t->process_return.regs[0]=0;
            if (!save_signal_mask(t,&t->mask,"pid-subreaper-mask")
                    || !shield(t) || !skip(t->pid)) return 1;
            native.pc=abi.store_groups; native.regs[0]=r->regs[1]; native.regs[1]=1;
            native.regs[3]=md_pid_identity_subreaper(t->resources.pid_group);
            t->phase=PROCESS_VALUE_STORE;
            if (set_registers(t->pid,&native)) resume(t->pid,0);
            return 1;
        }
    }
    int index=nr==SYS_tgkill || nr==SYS_rt_tgsigqueueinfo ? 1 : 0;
    if (index==1) {
        pid_t group=native_pid(t,(pid_t)r->regs[0]), target=native_pid(t,(pid_t)r->regs[1]);
        struct thread *to=find_thread(target);
        if (group<=0 || target<=0 || (to && to->tgid!=group)) { return_value(t,r,-ESRCH); return 1; }
        native.regs[0]=group;
    }
    if (nr==SYS_kill || nr==SYS_tkill || nr==SYS_tgkill
            || nr==SYS_getpgid || nr==SYS_getsid || nr==SYS_setpgid
            || nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo || nr==SYS_wait4
            || nr==SYS_sched_getaffinity || nr==SYS_sched_setaffinity || nr==SYS_sched_getparam || nr==SYS_sched_setparam
            || nr==SYS_sched_getscheduler || nr==SYS_sched_setscheduler || nr==SYS_sched_getattr || nr==SYS_sched_setattr
            || nr==SYS_sched_rr_get_interval || nr==SYS_get_robust_list || nr==SYS_prlimit64
            || nr==SYS_process_vm_readv || nr==SYS_process_vm_writev || nr==SYS_kcmp) {
        pid_t target=(pid_t)r->regs[index];
        if (target>0) {
            pid_t mapped=native_pid(t,target);
            if (!mapped) { return_value(t,r,nr==SYS_wait4 ? -ECHILD : -ESRCH); return 1; }
            native.regs[index]=mapped;
            if (signaling) {
                struct thread *to=find_thread(mapped);
                int signal=(int)r->regs[index+1];
                if (signal<0 || signal>=NSIG) { return_value(t,r,-EINVAL); return 1; }
                if (to && !to->born) { return_value(t,r,-ESRCH); return 1; }
                if (to && !signal_allowed(t,to,signal)) { return_value(t,r,-EPERM); return 1; }
                if (!to) {
                    struct md_task_retired_view retired=md_task_registry_retired(tasks,mapped);
                    if (!retired.identity) { return_value(t,r,-ESRCH); return 1; }
                    if (!signal_identity_allowed(t,retired.identity,mapped,signal)) {
                        return_value(t,r,-EPERM); return 1;
                    }
                }
                if (to && init_signal_suppressed(t,to,signal)) { return_value(t,r,0); return 1; }
            }
        } else if (nr==SYS_wait4 && target<-1) {
            pid_t mapped=target==INT32_MIN ? 0 : native_pid(t,-target);
            if (!mapped) { return_value(t,r,-ECHILD); return 1; }
            native.regs[index]=(uint64_t)-(long)mapped;
        }
        if (nr==SYS_setpgid && (pid_t)r->regs[1]>0) {
            pid_t mapped=native_pid(t,(pid_t)r->regs[1]);
            if (!mapped) { return_value(t,r,-EPERM); return 1; }
            native.regs[1]=mapped;
        }
        if (nr==SYS_kcmp && (pid_t)r->regs[1]>0) {
            pid_t mapped=native_pid(t,(pid_t)r->regs[1]);
            if (!mapped) { return_value(t,r,-ESRCH); return 1; }
            native.regs[1]=mapped;
        }
    }
    if (nr==SYS_waitid && (r->regs[0]==P_PID || r->regs[0]==P_PGID) && (pid_t)r->regs[1]>0) {
        pid_t mapped=native_pid(t,(pid_t)r->regs[1]);
        if (!mapped) { return_value(t,r,-ECHILD); return 1; }
        native.regs[1]=mapped;
    }
    if (nr==SYS_process_vm_readv || nr==SYS_process_vm_writev) {
        struct thread *target=find_thread((pid_t)native.regs[0]);
        CHECK(!t->remote_memory);
        if (target) { t->remote_memory=md_memory_fork(target->memory,1); CHECK(t->remote_memory); }
    }
    t->original=*r; t->phase=PROCESS_OBSERVING;
    if (set_registers(t->pid,&native)) resume(t->pid,0);
    return 1;
}
static int network_descriptor_call(long nr) {
    return nr==SYS_bind || nr==SYS_connect || nr==SYS_listen || nr==SYS_accept || nr==SYS_accept4
        || nr==SYS_setsockopt || nr==SYS_getsockopt || nr==SYS_getsockname || nr==SYS_getpeername
        || nr==SYS_ioctl || nr==SYS_read || nr==SYS_readv || nr==SYS_write || nr==SYS_writev
        || nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_sendmmsg
        || nr==SYS_recvfrom || nr==SYS_recvmsg || nr==SYS_recvmmsg;
}
static void filter_revoke_entry(struct thread *t,const struct user_pt_regs *r) {
    int group=r->regs[8]==SYS_seccomp && (r->regs[1]&SECCOMP_FILTER_FLAG_TSYNC);
    CHECK(!md_svc_registry_revoke(md_memory_svc(t->memory),t->pid));
    for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid
            && (peer==t || (group && peer->tgid==t->tgid))) {
        peer->cooperative_blocked=1;
        md_broker_cooperative_revoke(broker,peer->pid);
    }
}
static int domain_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    long nr = r->regs[8];
    if (t->ready && t->entered && t->phase==IDLE && cookie==MD_INTERCEPT_OBSERVE && r->pc!=RAW_GATE
            && ((nr==SYS_seccomp && (unsigned)r->regs[0]==SECCOMP_SET_MODE_FILTER)
                || (nr==SYS_prctl && (unsigned)r->regs[0]==PR_SET_SECCOMP))) {
        /* Revoke existing entries before filter commit, including TSYNC. The
         * guest preflight revokes new registration in its own address space;
         * a protected fork need not grant the supervisor memory-write access. */
        filter_revoke_entry(t,r);
        if (!t->registration_revoked
                || md_fd_selector_has_mask(t->fd_selector,MD_FD_SANDBOX_SELECTORS,-1)!=1) {
            t->original=*r; t->filter_preflight=1;
            if (save_signal_mask(t,&t->mask,"filter-admission-mask")
                    && shield(t) && skip(t->pid)) dispatch_with_stack(t);
            return 1;
        }
    }
    if (t->phase==PROCESS_INIT_EXIT && nr==SYS_prctl && r->pc==RAW_GATE
            && r->regs[0]==PR_SET_CHILD_SUBREAPER && r->regs[1]==1
            && !r->regs[2] && !r->regs[3] && !r->regs[4] && !r->regs[5]) {
        /* This is the namespace owner's pending birth operation, not a guest
         * request against its restricted root. Kernel filters still apply. */
        resume(t->pid,0); return 1;
    }
    if (t->phase==IDLE && !t->pid_filter
            && (((nr==SYS_clone || nr==SYS_unshare) && (r->regs[0]&CLONE_NEWPID)) || nr==SYS_setns)) {
        t->original=*r; t->pid_preflight=1;
        if (save_signal_mask(t,&t->mask,"pid-filter-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return 1;
    }
    if (nr == SYS_socket) {
        int error = md_domain_socket_error((int)r->regs[0], (int)r->regs[2]);
        if (error) { return_value(t, r, error); return 1; }
    }
    if (t->ready && t->phase==IDLE) {
        struct md_fd_object *object=network_descriptor_call(nr)
            ? md_fd_origins_object(t->resources.files,(int)r->regs[0],NULL) : NULL;
        int control=md_network_socket_control(object) || md_network_endpoint_is(object) || md_device_events_is(object)
            || (nr==SYS_ioctl && md_network_interface_ioctl(r->regs[1]) && md_network_socket_namespace(object));
        int native_payload=md_socket_payload_native(nr,r->regs[4])
            && !md_network_socket_control(object) && !md_device_events_is(object);
        md_fd_object_release(object);
        /* No pathname, peer address or ancillary result needs adaptation.
         * Keep blocking IO at its original PC/mask so signals and a debugger
         * interrupt do not wait for an internal adapter to finish reading. */
        if (native_payload) { resume(t->pid,0); return 1; }
        int devices=nr==SYS_socket && r->regs[0]==AF_NETLINK && r->regs[2]==NETLINK_KOBJECT_UEVENT
            && md_fs_worker_device_events(filesystem);
        if (control || devices || (nr==SYS_socket && md_network_namespace_managed(t->resources.network))) {
            t->original=*r;
            if (save_signal_mask(t,&t->mask,"network-call-mask")
                    && shield(t) && skip(t->pid)) dispatch_with_stack(t);
            return 1;
        }
    }
    if (t->ready && t->phase==IDLE && (nr==SYS_bind || nr==SYS_connect || nr==SYS_listen
            || nr==SYS_setsockopt || nr==SYS_ioctl)) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)r->regs[0],NULL);
        int offline=md_network_socket_offline(object); md_fd_object_release(object);
        /* Namespace-mutating ioctls and Unix autobind must not reach Android's
         * network. Ordinary inherited descriptions keep their own authority. */
        if (offline && (nr!=SYS_setsockopt || (r->regs[1]==SOL_SOCKET && r->regs[2]==SO_PASSCRED))) {
            return_value(t,r,nr==SYS_ioctl ? -ENOTTY : -EOPNOTSUPP); return 1;
        }
    }
    if (t->ready && t->phase==IDLE && nr==SYS_ioctl && (md_terminal_attributes_command((unsigned)r->regs[1])
            || md_pidfd_namespace_command((unsigned)r->regs[1])
            || md_filesystem_ioctl_command((unsigned)r->regs[1]))) {
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"descriptor-ioctl-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return 1;
    }
    if (nr == SYS_unshare) {
        CHECK(t->phase == IDLE);
        if (!(r->regs[0]&CLONE_NEWUSER) && md_resources_namespace_flags(r->regs[0])
                && !md_identity_capable(&t->identity,CAP_SYS_ADMIN)) {
            return_value(t,r,-EPERM); return 1;
        }
        fd_begin(t,r,0);
        return 1;
    }
    if (nr == SYS_setns) { fd_begin(t,r,0); return 1; }
    if (nr==SYS_clone3 && !t->clone_prepared) {
        CHECK(t->phase==IDLE);
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"clone-arguments-mask") && shield(t) && skip(t->pid))
            dispatch_with_stack(t);
        return 1;
    }
    if (md_clone_call(nr)) {
        unsigned long flags=r->regs[0];
        if (nr==SYS_clone3) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
            flags=((struct clone_args *)span.data)->flags;
        }
        if ((flags&CLONE_PARENT) && md_pid_identity_number(t->resources.pid_group,
                md_pid_identity_namespace(t->resources.pid))==1) {
            return_value(t,r,-EINVAL); return 1;
        }
        if (!md_resources_clone_supported(flags)) {
            return_value(t, r, -EINVAL); return 1;
        }
        if (!(flags&CLONE_NEWUSER) && md_resources_namespace_flags(flags)
                && !md_identity_capable(&t->identity,CAP_SYS_ADMIN)) {
            return_value(t,r,-EPERM); return 1;
        }
        t->cloning=flags; return 0;
    }
    if (nr == SYS_chroot) {
        if ((admitted_path || virtual_identity) && !md_identity_may_chroot(&t->identity)) return_value(t, r, -EPERM);
        else domain_path_call(t, r);
        return 1;
    }
    if (nr==SYS_sethostname || nr==SYS_setdomainname) {
        if (!md_identity_capable_in(&t->identity,md_uts_namespace_owner(t->resources.uts),CAP_SYS_ADMIN)) {
            return_value(t,r,-EPERM); return 1;
        }
        if (cookie==MD_INTERCEPT_DISPATCH) return 0;
    }
    if (!md_domain_restricted(t->resources.fs)) return 0;
    /* FD selectors require adapter admission for the new slot as well as the
     * kernel table mutation. A restricted root does not turn that dispatch
     * into an unobserved native dup, losing the retained object's identity. */
    if (cookie==MD_INTERCEPT_DISPATCH && (nr==SYS_dup || nr==SYS_dup3
            || (nr==SYS_fcntl && ((unsigned)r->regs[1]==F_DUPFD
                || (unsigned)r->regs[1]==F_DUPFD_CLOEXEC)))) return 0;
    /* These selectors operate on retained descriptions, not paths. Their
     * owners validate typed IO and recheck reused slots before native IO;
     * a restricted root must not revoke an already opened pipe or proc file. */
    if (cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_WATCH) return 0;
    if (t->phase==PROC_OPENING && t->metadata.task_open && cookie==MD_INTERCEPT_NATIVE
            && nr==SYS_openat && (int)r->regs[0]==t->metadata.proc_directory
            && r->regs[1]==abi.proc_open_path && r->regs[2]==t->metadata.request.data.args[2]
            && !r->regs[3]
            && md_fd_order_state(&t->metadata.files)==MD_RESOURCE_ORDER_RUNNING) {
        resume(t->pid,0); return 1;
    }
    /* UTS identity is independent of the restricted filesystem. */
    if (nr == SYS_uname && cookie == MD_INTERCEPT_DISPATCH) return 0;
    if (cookie==MD_INTERCEPT_DISPATCH && (md_domain_ipc_call(nr) || nr==SYS_pipe2)) return 0;
    if (nr == SYS_kill || nr == SYS_tgkill || nr == SYS_tkill) {
        long target = (long)r->regs[nr == SYS_tgkill ? 1 : 0];
        struct thread *destination = target > 0 ? find_thread(target) : NULL;
        if (destination && destination->born) {
            resume(t->pid, 0); return 1;
        }
        return_value(t, r, -EPERM); return 1;
    }
    unsigned long args[6];
    for (unsigned i = 0; i < 6; i++) args[i] = r->regs[i];
    if (md_domain_native_call(nr, args)) {
        if (cookie == MD_INTERCEPT_OBSERVE || cookie == MD_INTERCEPT_WATCH
                || cookie==MD_INTERCEPT_FD || cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_MEMORY) return 0;
        resume(t->pid, 0); return 1;
    }
    int error = cookie == MD_INTERCEPT_DISPATCH ? md_domain_path_error(t->resources.fs) : -EPERM;
    if (nr == SYS_chdir) {
        domain_path_call(t, r); return 1;
    }
    if (error && ++restricted_denials <= 32)
        TRACE("PROBE restricted pid=%d nr=%ld result=%d\n", t->pid, nr, error);
    return_value(t, r, error); return 1;
}
static int descriptor_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    if (cookie != MD_INTERCEPT_DISPATCH || r->regs[8] != SYS_lseek) return 0;
    CHECK(t->ready && t->phase == IDLE);
    int fd = duplicate_fd(t->pid, (int)r->regs[0]);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); return 0; }
    /* A retained open-file description needs no namespace lookup or guest
     * memory access. Never cache FD numbers or resume against a replaceable FD.
     * Original seccomp admission and domain checks precede this operation. */
    off_t result = lseek(fd, (off_t)r->regs[1], (int)r->regs[2]);
    long value = result < 0 ? -errno : result;
    close(fd);
    if (statistics) statistics->native_seeks++;
    return_value(t, r, value); return 1;
}
static int debug_syscall_exit(struct thread *t, const struct user_pt_regs *regs) {
    int active=t->debug_call_active;
    t->debug_call_active=t->debug_adapter=0;
    return active && md_debugger_syscall(debugger,t->pid,regs,0);
}
static int debugger_at_boundary(const struct thread *t) {
    return t->phase==IDLE && !t->metadata_restart && !t->fd_running && !t->fd_order.table && !t->fs_order.fs
        && !t->signal_delivery.active;
}
static int debugger_boundary(struct thread *t) {
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    if (machine && md_debugger_traced(debugger,t->pid)) {
        /* An interrupt can land inside a resident entry, outside the syscall
         * adapter. Retire that frame before exposing registers to the debugger;
         * a running payload finishes through its existing return checkpoint. */
        CHECK(!md_svc_registry_revoke(machine,t->pid));
        int deferred=md_svc_machine_flush(machine,&t->machine,t->pid);
        if (deferred==-ESRCH) { t->resume_lost=1; return 1; }
        CHECK(deferred>=0);
        if (deferred) { t->machine.draining=1; return 0; }
    }
    return md_debugger_boundary(debugger,t->pid);
}
static int freezer_parked(const struct thread *t) {
    return t->phase==WATCH_READ || t->phase==IPC_WAITING || t->phase==SOCKET_WAITING || t->phase==FILE_WAITING
        || t->phase==CONTROL_WAITING || t->phase==CLOSE_WAITING || t->phase==EPOLL_WAIT
        || t->phase==DEBUG_PARK_EXIT;
}
static void freezer_progress(void) {
    uint64_t generation=md_cgroup_freezer_generation(freezer);
    if (generation==freezer_generation) return;
    freezer_generation=generation;
    /* Empty frozen groups may acquire newborn members without another write. */
    freezer_enabled=1;
    for (struct thread *t=threads;t;t=t->next) if (t->pid && t->born) {
        if (!md_cgroup_member_freezing(t->resources.cgroup) || t->terminal_stop) {
            md_cgroup_member_frozen(t->resources.cgroup,0);
            if (t->freezer_resume) {
                int sig=t->freezer_signal;
                t->freezer_resume=t->freezer_signal=0;
                resume(t->pid,sig);
            }
            continue;
        }
        if (t->listening || freezer_parked(t)
                || (t->kernel_stopped && debugger_at_boundary(t))) {
            md_cgroup_member_frozen(t->resources.cgroup,1);
        } else if ((debugger_at_boundary(t) || t->phase==OBSERVING || t->phase==PROCESS_OBSERVING)
                && !t->kernel_stopped && t->initial_stop && !t->interrupt_pending
                && !t->resume_lost && memory_available(t->pid)) {
            /* EVENT_WAIT: interrupt confirms suspension; exit retires the
             * member. No guest job-control or signal disposition is changed. */
            tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"cgroup-freeze");
        }
    }
}
static void memory_access_capture(struct thread *t) {
    int error=md_memory_private_access_capture(t->memory,t->pid);
    TRACE("PROBE memory-access pid=%d result=%d\n",t->pid,error);
}
static int svc_client_alive(pid_t pid,void *memory) {
    struct thread *t=find_thread(pid);
    return t && t->memory==memory && !t->terminal_stop;
}
static int svc_clients_prepare(struct thread *t) {
    struct md_svc_registry *r=md_memory_svc(t->memory);
    if (!r || (r->disabled && !r->frames_dirty)) return 1;
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    int fd=access ? access->memory : -1;
    int error=md_svc_registry_prepare_fork(r,t->pid,fd);
    if (!error) error=md_svc_registry_identity(r,t->pid,fd,md_memory_sole_owner(t->memory) ? t->pid : 0);
    if (!error) error=md_svc_registry_collect_clients(r,t->pid,fd,svc_client_alive,t->memory);
    if (error==-EIO || error==-EFAULT) {
        int displaced=md_task_stop_displaced(checkpoints.trace,t->pid);
        if (displaced>0) error=-ESRCH;
        else if (displaced<0) error=displaced;
    }
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    if (error) errno=-error;
    CHECK(!error);
    return 1;
}
static void resume(pid_t pid, int sig) {
    struct thread *t = thread(pid);
    if (t->resume_lost) return;
    if (t->retained_signal) {
        CHECK(!sig || sig==t->retained_signal);
        sig=t->retained_signal;
    }
    if (entry_fence_owner==t && t->phase==IDLE) {
        if (t->entry_fence_replay) t->entry_fence_replay=0;
        else entry_fence_owner=NULL;
    }
    if (entry_fence_park(t)) {
        CHECK(!t->memory_resume || !sig || sig==t->memory_signal);
        t->memory_resume=1; if (sig) t->memory_signal=sig;
        return;
    }
    if (!memory_available(pid)) {
        CHECK(!t->memory_resume || !sig || sig==t->memory_signal);
        t->memory_resume=1; if (sig) t->memory_signal=sig;
        struct md_task_quiesce *barrier=memory_execution ? memory_barrier
            : mapping_execution ? mapping_barrier : task_action_barrier;
        if (ENTRY_QUIESCING) barrier=entry_fence_barrier;
        /* A lent actor has a separate resume owner until its registers return. */
        if (!task_action_execution || task_action_execution->actor!=t || !task_action_execution->borrowed)
            CHECK(!md_task_quiesce_add(barrier,pid,1,0));
        return;
    }
    if (t->terminal_stop) {
        if (t->terminal_close_pending) {
            t->terminal_close_pending=0;
            md_resources_exit_files(&t->resources,&t->closed);
            close_event_begin(t,PTRACE_EVENT_EXIT,t->close_message);
            return;
        }
        /* No guest instruction or syscall remains after the exit event. Keep
         * ownership until waitpid reaps it, without resuming adapter/debug steps. */
        if (tracee_request(t,PTRACE_CONT,NULL,NULL,"continue-exit")) t->kernel_stopped=0;
        return;
    }
    if (t->memory_access_pending && t->born && t->initial_stop) {
        t->memory_access_pending=0;
        memory_access_capture(t);
    }
    if (t->pid_birth_child) {
        /* EVENT_WAIT: the stopped newborn publishes shared SETTID fields before
         * either task returns to libc. Completion or death releases the parent;
         * launch cancellation bounds a missing event. No late parent rewrite may
         * race the newborn's mutex ownership or kernel CHILD_CLEARTID store. */
        CHECK(!t->pid_birth_waiting || !sig || sig==t->pid_birth_signal);
        t->pid_birth_waiting=1;
        if (sig) t->pid_birth_signal=sig;
        return;
    }
    if (t->phase==IDLE && t->pid_init_pending && t->initial_stop) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return;
        t->original=regs;
        if (!save_signal_mask(t,&t->mask,"pid-init-mask") || !shield(t)) return;
        regs.pc=RAW_GATE-4; regs.regs[8]=SYS_prctl;
        regs.regs[0]=PR_SET_CHILD_SUBREAPER; regs.regs[1]=1;
        for (unsigned i=2;i<6;++i) regs.regs[i]=0;
        t->phase=PROCESS_INIT_ENTRY; t->pid_init_pending=0;
        if (!set_registers(pid,&regs)) return;
    }
    if (t->phase==IDLE && t->pid_store_count && t->initial_stop) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return;
        t->original=regs;
        if (!save_signal_mask(t,&t->mask,"pid-store-mask") || !shield(t)) return;
        regs.pc=abi.store_groups; regs.regs[0]=t->pid_stores[0].address;
        regs.regs[1]=1; regs.regs[3]=t->pid_stores[0].value;
        --t->pid_store_count; t->pid_stores[0]=t->pid_stores[1];
        t->phase=PROCESS_TID_STORE;
        if (!set_registers(pid,&regs)) return;
    }
    if (t->phase==IDLE && t->initial_stop) {
        pid_birth_release(t);
        if (t->debug_birth) {
            t->debug_birth=0;
            if (md_debugger_newborn(debugger,pid)) return;
        }
    }
    CHECK(!md_task_registry_publish(tasks, t->pid, t->tgid, &t->identity, &t->resources));
    enum phase phase = t->phase;
    if (freezer_enabled && md_cgroup_member_freezing(t->resources.cgroup)) {
        if (debugger_at_boundary(t)) {
            CHECK(!t->freezer_resume || !sig || !t->freezer_signal || sig==t->freezer_signal);
            t->freezer_resume=1;
            if (sig) t->freezer_signal=sig;
            md_cgroup_member_frozen(t->resources.cgroup,1);
            return;
        }
        if (freezer_parked(t)) md_cgroup_member_frozen(t->resources.cgroup,1);
    }
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    if (!svc_clients_prepare(t)) return;
    if (machine && !machine->disabled && (md_memory_exposed(t->memory)
            || t->debug_options || md_debugger_traced(debugger,pid)))
        CHECK(!md_svc_registry_revoke(machine,pid));
    if (t->trace_options_dirty && debugger_options(pid,t->debug_options)) return;
    if (phase!=IDLE && t->debug_call_active) t->debug_adapter=1;
    if (debugger_at_boundary(t) && t->entered && t->process_image) {
        if (t->debug_call_active && t->debug_adapter) {
            struct user_pt_regs regs;
            if (!registers(pid,&regs)) return;
            if (debug_syscall_exit(t,&regs)) return;
        }
        if (debugger_boundary(t)) return;
    }
    if (debugger_at_boundary(t) && t->debug_step_call) {
        t->debug_step_call=0;
        siginfo_t info={.si_signo=SIGTRAP,.si_code=TRAP_TRACE};
        if (md_debugger_stop(debugger,pid,(SIGTRAP<<8)|0x7f,&info,0)) return;
    }
    if (broker) {
        int eligible=t->ready && phase==IDLE && t->endpoint[0] && !md_domain_restricted(t->resources.fs);
        int opens=eligible && !t->application_filter && !t->debug_options
            && !t->debug_call_active && !t->debug_step_call && !md_debugger_traced(debugger,pid);
        struct md_svc_registry *registry=md_memory_svc(t->memory);
        int discovery=opens && registry && !registry->disabled && !md_memory_exposed(t->memory);
        cooperative_memory_publish(t,discovery && !t->cooperative_blocked);
        struct md_broker_code_ranges ranges={.size=MD_SVC_CODE_BYTES};
        _Static_assert(MD_SVC_CODE_POOLS<=8,"broker code range capacity");
        if (registry) {
            ranges.count=registry->pool_count;
            for (unsigned i=0;i<ranges.count;++i) ranges.starts[i]=registry->pools[i].address;
        }
        CHECK(!md_broker_task(broker,pid,eligible ? MD_BROKER_READ|(opens ? MD_BROKER_OPEN : 0)
            |(discovery ? MD_BROKER_DISCOVERY : 0)
            |(opens && !t->cooperative_blocked ? MD_BROKER_COOPERATIVE : 0) : 0,
            RAW_GATE,abi.watch_return,&ranges));
    }
    int publish_signal=md_signal_delivery_pending(&t->signal_delivery) && !t->signal_delivery.active
        && !t->signal_shielded && !t->signal_return_pending;
    if (!sig && (t->machine.draining || publish_signal) && debugger_at_boundary(t)
            && !t->debug_call_active) {
        /* Carrier delivery resumes with CONT. A native syscall must first
         * publish its exit to the guest debugger, even when its result was
         * replaced without entering an adapter phase. A cold entry fence also
         * retires native aliases here, without waiting for another signal. */
        int deferred=md_svc_machine_flush(md_memory_svc(t->memory),&t->machine,pid);
        int error=deferred || !publish_signal ? (deferred<0 ? deferred : 0)
            : md_signal_delivery_publish(&t->signal_delivery,pid,t->tgid);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
    t->kernel_resume=t->signal_delivery.active ? PTRACE_CONT : (t->metadata_restart && phase==IDLE
            && (t->metadata_restart->stage==METADATA_ENTRY || t->metadata_restart->stage==METADATA_WAIT))
            || t->signal_return_pending || t->machine.syscall_exit || (phase==IDLE && t->machine.retry_svc)
            || t->direct==DIRECT_ADMIT || t->fd_running || phase == OBSERVING || phase==PROCESS_OBSERVING
            || phase==PROCESS_INIT_ENTRY || phase==PROCESS_INIT_EXIT || phase == EXPORT_ENTRY || phase == EXPORT_NOTIFY
            || phase == EXPORT_RETURN || phase == EXECUTING || phase == WATCH_CANCEL
            || phase == WATCH_ENTRY || phase == WATCH_READ || phase==DEBUG_WAIT_ENTRY || phase==DEBUG_WAIT_EXIT
            || phase==DEBUG_PARK_ENTRY || phase==DEBUG_PARK_EXIT
            || phase==IPC_WAIT_ENTRY || phase==IPC_WAITING || phase==SOCKET_WAIT_ENTRY || phase==SOCKET_WAITING
            || phase==CALL_RESTART_ENTRY || phase==CALL_RESTART_EXIT || phase==EPOLL_PROBE || phase==EPOLL_CALL || phase==EPOLL_WAIT
            || phase==FILE_WAIT_ENTRY || phase==FILE_WAITING
            || phase==CONTROL_WAIT_ENTRY || phase==CONTROL_WAITING || phase==CLOSE_WAIT_ENTRY || phase==CLOSE_WAITING
            || phase==WAIT_CANCEL_EXIT || phase==CLONE_ENTRY || phase==CLONE_ADMISSION
            || phase==RESULT_REPLAY_ENTRY || phase==RESULT_REPLAY_EXIT
            || (debugger_at_boundary(t) && t->entered && t->process_image
                && (md_debugger_syscall_mode(debugger,pid) || md_debugger_observing(debugger,pid))) ? PTRACE_SYSCALL
                : debugger_at_boundary(t) && t->entered && md_debugger_step(debugger,pid) ? PTRACE_SINGLESTEP : PTRACE_CONT;
    if (!native_trace(t->kernel_resume,
            pid, NULL, (void *)(uintptr_t)sig)) { t->kernel_stopped=0; t->retained_signal=0; return; }
    CHECK(errno == ESRCH);
    /* EVENT_WAIT: thread-group exit or exec may win a stopped-thread resume.
     * A terminal stop may still precede reaping. Only the exact exit/exec event
     * releases ownership; the process deadline bounds a missing event. */
    t->resume_lost = 1;
}
static int shield(struct thread *t) {
    uint64_t mask = UINT64_MAX;
    int sync[] = {SIGKILL, SIGSTOP, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGSYS};
    for (unsigned i = 0; i < sizeof(sync) / sizeof(*sync); i++) mask &= ~(UINT64_C(1) << (sync[i] - 1));
    if (!signal_mask(t,mask,"shield-signals")) return 0;
    t->signal_shielded=1; return 1;
}
static int save_signal_mask(struct thread *t,uint64_t *mask,const char *reason) {
    if (!tracee_request(t,PTRACE_GETSIGMASK,(void *)sizeof(*mask),mask,reason)) return 0;
    return 1;
}
static int signal_mask(struct thread *t,uint64_t mask,const char *reason) {
    if (!tracee_request(t,PTRACE_SETSIGMASK,(void *)sizeof(mask),&mask,reason)) return 0;
    t->signal_shielded=0; return 1;
}
static int restore_mask(struct thread *t) {
    return signal_mask(t,t->mask,"restore-mask");
}
static int direct_begin(struct thread *t) {
    long nr=t->original.regs[8];
    int status=nr!=SYS_openat;
    if (!t->endpoint[0] || t->direct || t->buffer.pool || t->application_filter
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || (status ? nr!=SYS_unlinkat && nr!=SYS_renameat && nr!=SYS_renameat2
                && nr!=SYS_linkat && nr!=SYS_mkdirat : nr!=SYS_openat)) return 0;
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=t->original.regs[i];
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
        error=md_fs_buffer_invoke_status(buffer_service,t->pid,t->resources.fs,&q,&t->buffer_call);
    } else {
        if (md_buffers_take(md_memory_buffers(t->memory),&t->buffer)) return 0;
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->buffer,&span));
        struct md_fs_buffer_frame *frame=span.data;
        memset(&frame->input,0,offsetof(struct md_fs_buffer_input,path));
        memset(frame->input.capture_length,0,sizeof(frame->input.capture_length));
        memset(frame->input.capture_flags,0,sizeof(frame->input.capture_flags));
        frame->input.base[0]=q.directory[0]; frame->input.base[1]=-1;
        frame->input.length[0]=strlen(q.path[0])+1;
        memcpy(frame->input.path[0],q.path[0],frame->input.length[0]);
        frame->input.flags=q.flags; frame->input.mode=q.mode; frame->input.attributes=q.attributes;
        error=md_fs_buffer_invoke(buffer_service,t->pid,t->resources.fs,q.operation,&t->buffer,&t->buffer_call);
    }
    if (error) { CHECK(!buffer_release(t)); return 0; }
    if (status && !skip(t->pid)) { md_fs_buffer_cancel(&t->buffer_call); return 1; }
    t->direct=status ? DIRECT_STATUS : DIRECT_OPEN; t->phase=DISPATCHING;
    t->direct_flags=q.flags; t->direct_mode=q.mode;
    t->buffer_registers=t->original; t->buffer_registers.regs[1]=q.operation;
    if (statistics) {
        if (status) ++statistics->direct_status;
        else ++statistics->direct_open;
    }
    /* EVENT_WAIT: worker completion or task death owns the captured request.
     * No guest instruction runs before completion; mutations are never replayed. */
    adapter_progress(); return 1;
}
static void direct_status_complete(struct thread *t,const struct md_fs_result *result,int error) {
    CHECK(!t->buffer_call && !t->buffer.pool && t->kernel_stopped);
    int delegate=error==MD_FS_BUFFER_NEEDS_TASK || (!error && result->host_path);
    long value=error ? error : result->error;
    t->direct=DIRECT_NONE;
    if (delegate) {
        if (statistics) ++statistics->direct_fallback;
        if (save_signal_mask(t,&t->mask,"direct-fallback-mask")
                && shield(t)) dispatch_with_stack(t);
        return;
    }
    struct user_pt_regs regs=t->original; regs.regs[0]=value;
    if (!set_registers(t->pid,&regs)) return;
    t->phase=IDLE;
    if (!completed_event_resume(t)) resume(t->pid,0);
}
static void direct_complete(struct thread *t,int error) {
    CHECK(!t->buffer_call && t->kernel_stopped);
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->buffer,&span));
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
            t->direct=DIRECT_ADMIT;
            if (statistics) ++statistics->direct_admit;
            resume(t->pid,0); return;
        }
        t->direct=DIRECT_PREPARE_RESULT;
        if (save_signal_mask(t,&t->mask,"direct-result-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    CHECK(!buffer_cleanup(t)); t->direct=DIRECT_NONE;
    if (delegate) {
        if (statistics) ++statistics->direct_fallback;
        if (save_signal_mask(t,&t->mask,"direct-fallback-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    struct user_pt_regs regs=t->original; regs.regs[0]=value;
    if (!skip(t->pid) || !set_registers(t->pid,&regs)) return;
    t->phase=IDLE;
    if (!completed_event_resume(t)) resume(t->pid,0);
}
static uintptr_t dispatch_input(struct thread *t,unsigned policy) {
    if (t->dispatch_prepared) return md_memory_buffer_address(t->memory,&t->buffer);
    long nr=(long)t->original.regs[8];
    unsigned long count=nr==SYS_ppoll ? t->original.regs[1] : t->original.regs[0];
    int poll=(nr==SYS_ppoll || nr==SYS_pselect6) && count<=INT_MAX
        && (nr!=SYS_ppoll || count<=md_process_native_nofile(t->resources.process));
    int exec=(nr==SYS_execve || nr==SYS_execveat) && t->endpoint[0];
    /* Network and device-event adapters own their buffer lifecycle; only the
     * ordinary receive adapter consumes this prepared frame. */
    if (t->buffer.pool || (policy&(MD_DISPATCH_NETWORK_DATA|MD_DISPATCH_NETWORK_CONTROL|MD_DISPATCH_DEVICE_EVENTS))
            || (nr!=SYS_recvmsg && nr!=SYS_recvmmsg && !poll && !exec)) return 0;
    /* Opportunistic admission, not a retained per-thread cache. Pool pressure
     * keeps the adapter's normal queued TAKE; unrelated calls allocate nothing. */
    if (md_buffers_take_sized(md_memory_buffers(t->memory),poll ? md_poll_bytes(count) : 0,&t->buffer)) return 0;
    uintptr_t address=md_memory_buffer_address(t->memory,&t->buffer);
    if (address) {
        t->dispatch_prepared=1;
        if (nr==SYS_recvmsg) {
            struct md_buffer_span span;
            CHECK(!md_buffer_span(&t->buffer,&span));
            ((struct md_task_receive_frame *)span.data)->prepared=0;
        }
    }
    else CHECK(!buffer_release(t));
    return address;
}
static int receive_prepared(struct thread *t,const struct user_pt_regs *continuation,unsigned policy) {
    if (t->original.regs[8]!=SYS_recvmsg || !t->dispatch_prepared || !t->endpoint[0]
            || (policy&(MD_DISPATCH_NETWORK_DATA|MD_DISPATCH_NETWORK_CONTROL|MD_DISPATCH_DEVICE_EVENTS|MD_DISPATCH_OFFLINE_SOCKET))
            || t->receive || t->fd_order.table || t->fd_running || t->fs_order.fs
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->buffer,&span));
    struct md_task_receive_frame *frame=span.data;
    struct msghdr *original=&frame->original;
    struct iovec local={original,sizeof(*original)},remote={(void *)t->original.regs[1],local.iov_len};
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
    owner->request=(struct md_task_receive){.socket=(int)t->original.regs[0],
        .flags=(int)t->original.regs[2],.count=1,.inline_receive=1,.finish_error=1};
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
    t->receive=owner;
    if (!continuation) {
        if (statistics) ++statistics->entry_receives;
        long result;
        CHECK(!md_ipc_io_receive(&owner->io,&owner->received,&frame->io,owner->request.flags,&result));
        owner->ready=1;
        if (result<0) {
            result=buffer_result_finish(t,0,result);
            receive_release(owner); t->receive=NULL;
            t->phase=IDLE;
            return_value(t,&t->original,result);
        } else {
            frame->io_completed=frame->prepared=1; frame->result=result;
            if (save_signal_mask(t,&t->mask,"receive-publication-mask") && shield(t) && skip(t->pid))
                dispatch_with_stack(t);
        }
        return 1;
    }
    owner->continuation=*continuation; t->phase=DISPATCHING;
    socket_io_progress(t);
    return 1;
}
static unsigned dispatch_policy(struct thread *t) {
    unsigned policy = md_domain_scoped(t->resources.fs) || md_mount_namespace_managed_paths(t->resources.mounts)
        || t->resources.landlock;
    if (md_network_namespace_managed(t->resources.network)) policy|=MD_DISPATCH_NETWORK_SCOPED;
    if (t->original.regs[8]==SYS_socket && t->original.regs[0]==AF_NETLINK
            && t->original.regs[2]==NETLINK_KOBJECT_UEVENT && md_fs_worker_device_events(filesystem))
        policy|=MD_DISPATCH_DEVICE_EVENTS;
    if (network_descriptor_call(t->original.regs[8])) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)t->original.regs[0],NULL);
        if (md_network_socket_offline(object)) policy|=MD_DISPATCH_OFFLINE_SOCKET;
        int interface_ioctl=t->original.regs[8]==SYS_ioctl && md_network_interface_ioctl(t->original.regs[1])
            && md_network_socket_namespace(object);
        if (md_network_socket_control(object) || interface_ioctl) policy|=MD_DISPATCH_NETWORK_CONTROL;
        if (md_network_endpoint_is(object) && !interface_ioctl) policy|=MD_DISPATCH_NETWORK_DATA;
        if (md_device_events_is(object)) policy|=MD_DISPATCH_DEVICE_EVENTS;
        md_fd_object_release(object);
    }
    return policy;
}
static int receive_entry(struct thread *t) {
    if (t->original.regs[8]!=SYS_recvmsg || t->buffer.pool
            || t->broker_event.held || t->metadata_restart) return 0;
    unsigned policy=dispatch_policy(t);
    if (!dispatch_input(t,policy)) return 0;
    /* A completed nonblocking error needs no guest continuation, stack or
     * signal shield. Successful IO retains custody until normal publication. */
    if (receive_prepared(t,NULL,policy)) return 1;
    CHECK(!buffer_cleanup(t));
    return 0;
}
static void dispatch(struct thread *t) {
    if (t->direct==DIRECT_PREPARE_RESULT) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->buffer,&span));
        struct user_pt_regs regs=t->original;
        regs.pc=abi.buffer_result; regs.sp=t->stack; regs.regs[30]=abi.done_entry;
        regs.regs[0]=t->direct_flags; regs.regs[1]=t->direct_mode;
        regs.regs[2]=md_memory_buffer_address(t->memory,&t->buffer);
        CHECK(regs.regs[2]);
        if (direct_addfd(t,span.data)) {
            regs.pc=abi.buffer_enter; regs.regs[0]=MD_FS_BUFFER_RESULT_ADMIT;
        }
        t->direct=DIRECT_RESULT; t->phase=DISPATCHING;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return;
    }
    struct user_pt_regs regs = t->original;
    regs.pc = DISPATCH; regs.sp = t->stack; regs.regs[30] = abi.done_entry;
    regs.regs[0] = t->original.regs[8];
    for (unsigned i = 0; i < 6; i++) regs.regs[i + 1] = t->original.regs[i];
    unsigned policy=dispatch_policy(t);
    regs.regs[9] = md_dispatch_control(policy,t->proc_file ? (int)md_proc_file_kind(t->proc_file) : -1);
    regs.regs[10]=dispatch_input(t,policy);
    if (receive_prepared(t,&regs,policy)) return;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = DISPATCHING; resume(t->pid, 0);
}
static void dispatch_with_stack(struct thread *t) {
    if (proc_write_direct(t)) return;
    if (!t->stack) t->stack = md_memory_stack_take(t->memory);
    if (t->stack) dispatch(t);
    else {
        struct user_pt_regs regs = t->original; regs.pc = ALLOCATE;
        if (!set_registers(t->pid, &regs)) return;
        t->phase = ALLOCATING; resume(t->pid, 0);
    }
}
static int helper_newborn(pid_t pid) {
    int held=md_task_quiesce_newborn(memory_barrier,pid);
    if (held) return held;
    struct thread *t=find_thread(pid);
    if (!t) return 0;
    CHECK(!t->born && t->initial_stop);
    release_thread(t);
    return 1;
}
static void mapping_delivery_release(struct mapping_owner *owner) {
    md_fd_origins_finish(&owner->delivery,-ECANCELED);
    if (owner->loan) {
        CHECK(owner->loan->number<0);
        md_fd_loan_end(owner->loan->loan);
        md_fd_loan_release(owner->loan->loan);
        md_fd_origins_unpin(owner->loan->loan_table);
        free(owner->loan); owner->loan=NULL;
    }
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
}
static void mapping_guard_release(struct mapping_owner *owner) {
    if (owner!=mapping_execution) return;
    if (owner->borrowed) {
        CHECK(!md_task_quiesce_return(mapping_barrier,owner->direct.call.actor));
        owner->borrowed=0;
    }
    CHECK(!md_task_quiesce_release(mapping_barrier));
    mapping_execution=NULL;
}
static void mapping_complete(void *context, struct md_task_helper_result result) {
    long value=result.value;
    CHECK(result.descriptor==-1);
    struct mapping_owner *owner=context;
    int guarded=owner==mapping_execution;
    mapping_guard_release(owner);
    mapping_delivery_release(owner);
    if (owner->file) {
        md_memory_mapping_finish(owner->memory,&owner->change,value);
        if (value>=0) CHECK(!md_shm_unmapped(owner->space,(uintptr_t)value,
            (owner->request.size+md_page_size-1)&~(md_page_size-1),owner->group,owner->process));
    } else if (value>=0) {
        unsigned long args[4]={(unsigned long)value};
        CHECK(!md_shm_call(NULL,owner->space,&owner->pending,NULL,owner->group,owner->process,MD_SHM_ATTACHED,args,NULL));
    } else CHECK(!md_shm_abort(&owner->pending));
    CHECK(!md_shm_space_release(owner->space));
    md_pid_identity_release(owner->process);
    if (owner->request.source>=0) close(owner->request.source);
    if (owner->order.queue && md_resource_order_cancel(&owner->order)==1)
        CHECK(!md_resource_order_complete(&owner->order));
    md_memory_release(owner->memory);
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    if (t && t->mapping==owner) {
        struct user_pt_regs regs=owner->continuation;
        regs.regs[0]=value;
        t->mapping=NULL; t->kernel_stopped=1;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
    }
    free(owner);
    if (guarded) task_action_unpark();
}
static void mapping_retired(void *context,long error) {
    struct mapping_owner *owner=context;
    CHECK(!error || error==-ECANCELED);
    mapping_complete(owner,(struct md_task_helper_result){
        .value=error ? error : owner->direct.result,.descriptor=-1});
}
static void mapping_retire(struct mapping_owner *owner,struct thread *t) {
    if (owner->borrowed) {
        CHECK(!md_task_quiesce_return(mapping_barrier,owner->direct.call.actor));
        owner->borrowed=0;
    }
    owner->retiring=1;
    owner->loan->retired=mapping_retired; owner->loan->retired_context=owner;
    loan_retire(owner->loan,t,&owner->files,NULL); owner->loan=NULL;
}
static void mapping_discard(struct mapping_owner *owner) {
    if (owner->retiring) return;
    if (owner->direct.stage==MD_TASK_MAPPING_MAP)
        md_memory_mapping_abandon(owner->memory,&owner->change);
    if (owner->loan && owner->loan->number>=0) {
        owner->direct.result=-ECANCELED;
        mapping_retire(owner,NULL); return;
    }
    long value=owner->direct.stage==MD_TASK_MAPPING_DONE ? owner->direct.result : -ECANCELED;
    mapping_complete(owner,(struct md_task_helper_result){.value=value,.descriptor=-1});
}
static int mapping_helper_start(struct thread *t) {
    struct mapping_owner *owner=t->mapping;
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,.mapping=owner->request};
    int error=md_task_helper_begin(helpers,t->pid,t->tgid,&owner->continuation,&abi,
        owner->memory,&t->buffer,&request,mapping_complete,owner);
    if (!error) {
        owner->started=1; t->kernel_stopped=0;
        close(owner->request.source); owner->request.source=-1;
    }
    return error;
}
static int mapping_notification(struct thread *t,const struct seccomp_notif *q) {
    struct mapping_owner *owner=t->mapping;
    if (!owner || !md_task_mapping_notification(&owner->direct,q)) return 0;
    CHECK(owner->loan && md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_RUNNING);
    struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
        .srcfd=owner->request.source,.newfd_flags=O_CLOEXEC};
    int number=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
    if (number<0) {
        int error=errno;
        if (error!=ENOENT) {
            struct seccomp_notif_resp reply={.id=q->id,.error=-error};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        }
    } else {
        md_fd_origins_finish(&owner->delivery,number);
        owner->loan->number=owner->direct.descriptor=number;
    }
    return 1;
}
static int mapping_event(pid_t pid,int status) {
    struct thread *t=find_thread(pid);
    struct mapping_owner *owner=t ? t->mapping : NULL;
    if (!owner || !owner->direct.call.stage || owner->direct.stage==MD_TASK_MAPPING_DONE) return 0;
    unsigned event=(unsigned)status>>16;
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) return 0;
    if (task_call_signal(t,&owner->direct.call,status)) return 1;
    int owned_interrupt=md_task_call_expects_interrupt(&owner->direct.call);
    if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP && !owned_interrupt) {
        task_call_resume(t,&owner->direct.call,0);
        return 1;
    }
    if (event!=PTRACE_EVENT_SECCOMP && !(event==PTRACE_EVENT_STOP && owned_interrupt)
            && (event || WSTOPSIG(status)!=(SIGTRAP|0x80))) return 0;
    if (statistics) ++statistics->stops;
    int result=md_task_mapping_event(&owner->direct,status);
    if (result<0) { CHECK(result==-ESRCH); t->resume_lost=1; return 1; }
    if (result) {
        t->kernel_stopped=1;
        if (owner->direct.result==-EMFILE && owner->direct.descriptor<0) {
            /* Mapping needs no application FD. Retain the private-table path
             * when the optional loan cannot fit; its native errors still apply. */
            mapping_guard_release(owner);
            mapping_delivery_release(owner);
            owner->direct=(struct md_task_mapping){0};
            int error=mapping_helper_start(t);
            if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
            task_action_unpark();
        } else if (owner->loan->number>=0) {
            mapping_retire(owner,t);
        } else mapping_retired(owner,0);
    } else t->kernel_stopped=0;
    adapter_progress();
    return 1;
}
static int mapping_direct_start(struct thread *t) {
    struct mapping_owner *owner=t->mapping;
    if (!owner->files.table) {
        int error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error<0) return error;
    }
    if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_QUEUED) {
        retry_wait(t,RETRY_MAPPING); return 0;
    }
    if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_READY)
        CHECK(!md_fd_order_start(&owner->files));
    /* The running table turn excludes clone/dup publication. A sole stopped
     * owner cannot expose the loan to a peer, even for mutable backing. */
    if (owner->guarded && md_fd_origins_exclusive(&owner->files)) owner->guarded=0;
    if (owner->guarded) {
        if (!mapping_execution) {
            if (quiescence_busy(NULL)) {
                retry_wait(t,RETRY_MAPPING); return 0;
            }
            if (!mapping_barrier) mapping_barrier=md_task_quiesce_new(quiesce_trace);
            if (!mapping_barrier) return -ENOMEM;
            mapping_execution=owner;
            /* EVENT_WAIT: exact shared-table holders stop before a writable
             * backing loan is published. Expiry fails closed; ordinary native
             * and sealed mappings never enter this cold barrier. */
            owner->deadline=md_event_now()+5000000000LL;
            for (struct thread *p=threads;p;p=p->next) if (p->pid && p->resources.files==owner->files.table)
                CHECK(!md_task_quiesce_add(mapping_barrier,p->pid,
                    p==t || p->kernel_stopped || p->listening || p->vfork_child,
                    p->interrupt_pending || birth_stop_pending(p) ? 0 : p->kernel_resume));
            CHECK(!md_task_quiesce_stop(mapping_barrier));
        }
        if (mapping_execution!=owner) { retry_wait(t,RETRY_MAPPING); return 0; }
        if (!md_task_quiesce_ready(mapping_barrier)) return 0;
        struct md_task_quiesce_stop stop;
        CHECK(!md_task_quiesce_borrow(mapping_barrier,t->pid,&stop));
        CHECK(stop.kind==MD_TASK_QUIESCE_HELD);
        owner->borrowed=1;
    }
    struct task_action *loan=calloc(1,sizeof(*loan));
    if (!loan) return -ENOMEM;
    owner->loan=loan; loan->number=-1;
    loan->loan_table=md_fd_origins_pin(t->resources.files);
    loan->loan=md_fd_loan_new(t->resources.files);
    int error=loan->loan ? md_fd_origins_prepare(t->resources.files,NULL,FD_CLOEXEC,&owner->delivery) : -ENOMEM;
    if (!error) error=md_fd_origins_prepare_loan(&owner->delivery,loan->loan);
    if (!error) error=md_task_mapping_begin(&owner->direct,native_trace,t->pid,
        &owner->continuation,&abi,&owner->request);
    if (error && owner->direct.call.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return 0; }
    if (!error) t->kernel_stopped=0;
    return error;
}
static void mapping_start(struct thread *t) {
    retry_clear(t,RETRY_MAPPING);
    struct mapping_owner *owner=t->mapping;
    if (md_resource_order_state(&owner->order)==MD_RESOURCE_ORDER_RUNNING) {
        int error=mapping_direct_start(t);
        if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
        return;
    }
    if (md_resource_order_state(&owner->order)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_MAPPING); return; }
    CHECK(!md_resource_order_start(&owner->order));
    int error=0;
    if (owner->file) {
        error=md_memory_mapping_prepare(owner->memory,&owner->change,MD_MAPPING_MAP,
            owner->request.address,owner->request.size,0,owner->request.flags,
            (owner->noexec ? MD_MAPPING_NOEXEC : 0)|(owner->readonly ? MD_MAPPING_NOWRITE : 0));
        if (!error && !owner->noexec) (void)md_memory_mapping_backing(owner->memory,&owner->change,
            owner->request.source,owner->request.offset);
    }
    t->terminal_observation=1; t->trace_options_dirty=1;
    if (!error) error=debugger_options(t->pid,t->debug_options);
    if (!error && owner->file) {
        owner->guarded=!md_task_mapping_safe(owner->request.source);
        error=mapping_direct_start(t);
        if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
        return;
    }
    if (!error) error=mapping_helper_start(t);
    if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
}
static void mapping_progress(void) {
    struct mapping_owner *owner=mapping_execution;
    if (!owner) return;
    if (md_event_now()>=owner->deadline) {
        fprintf(stderr,"Shroot: mapping timeout caller=%d stage=%d retiring=%d actor=%d\n",
            owner->caller,owner->direct.stage,owner->retiring,
            task_action_execution && task_action_execution->actor ? task_action_execution->actor->pid : 0);
        md_task_quiesce_describe(mapping_barrier,STDERR_FILENO);
        md_broker_describe(broker,STDERR_FILENO);
        md_fs_buffer_describe(buffer_service,STDERR_FILENO);
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->resources.files==owner->files.table) {
            siginfo_t info={0};
            int observed=native_trace(PTRACE_GETSIGINFO,p->pid,NULL,&info)<0 ? -errno : info.si_code;
            fprintf(stderr,"Shroot: mapping peer pid=%d born=%d initial=%d stopped=%d phase=%d resume=%d terminal=%d nr=%lu stop=%d notification=%d readiness=%d\n",
                p->pid,p->born,p->initial_stop,p->kernel_stopped,p->phase,p->kernel_resume,p->terminal_stop,
                (unsigned long)p->original.regs[8],observed,p->memory_notification!=NULL,p->readiness!=NULL);
            md_broker_describe_task(broker,p->pid,STDERR_FILENO);
            if (observed<0) {
                char path[80],state[256];
                snprintf(path,sizeof(path),"/proc/%d/syscall",p->pid);
                int fd=open(path,O_RDONLY|O_CLOEXEC);
                ssize_t size=fd<0 ? -1 : read(fd,state,sizeof(state)-1);
                if (fd>=0) close(fd);
                if (size>=0) state[size]=0;
                fprintf(stderr,"Shroot: admission peer=%d pending=%d yields=%u gate=%#lx syscall=%s\n",
                    p->pid,p->buffer_admission_pending,p->buffer_admission_yields,
                    (unsigned long)abi.buffer_admit,size>=0 ? state : "unavailable");
            }
        }
        errno=ETIMEDOUT; CHECK(0);
    }
    if (!owner->direct.stage && md_task_quiesce_ready(mapping_barrier)) {
        struct thread *t=find_thread(owner->caller);
        CHECK(t && t->mapping==owner);
        int error=mapping_direct_start(t);
        if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    }
}
static int mapping_guard_event(pid_t pid,int status,const struct rusage *usage) {
    if (!mapping_execution || pid==mapping_execution->caller) return 0;
    if (!md_task_quiesce_contains(mapping_barrier,pid)) return 0;
    int error=quiesce_event(mapping_barrier,pid,status,usage);
    if (error) {
        fprintf(stderr,"Shroot: mapping barrier event pid=%d status=%x caller=%d retiring=%d actor=%d error=%d\n",
            pid,status,mapping_execution->caller,mapping_execution->retiring,
            task_action_execution && task_action_execution->actor ? task_action_execution->actor->pid : 0,error);
        errno=-error; CHECK(0);
    }
    return 1;
}
static void mapping_begin(struct thread *t, struct user_pt_regs *regs) {
    struct md_shm *shm=NULL;
    int file=t->original.regs[8]==SYS_mmap;
    long error=file ? 0 : md_ipc_namespace_memory(t->resources.ipc,&shm);
    if (!error && (t->phase!=DISPATCHING || (!file && t->original.regs[8]!=SYS_shmat)
            || t->mapping || !t->buffer.pool || t->buffer_call || t->ipc_transport.packet)) error=-EPERM;
    unsigned flags=t->original.regs[2];
    uintptr_t address=t->original.regs[1];
    if (!file) {
        if (flags&SHM_RND) address&=~(md_page_size-1);
        if (!error && ((flags&~(SHM_RDONLY|SHM_RND|SHM_EXEC)) || (address&(md_page_size-1)))) error=-EINVAL;
    } else if (!t->proc_file || t->control_mapping_fd<0) error=-EBADF;
    struct mapping_owner *owner=error ? NULL : calloc(1,sizeof(*owner));
    if (!error && !owner) error=-ENOMEM;
    int fd=-1;
    if (!error) {
        *owner=(struct mapping_owner){.caller=t->pid,.group=t->tgid,.process=md_pid_identity_retain(t->resources.pid_group),.continuation=*regs,
            .request={.source=-1},.file=file};
        owner->space=md_shm_space_fork(t->shm_space,1);
        owner->memory=md_memory_fork(t->memory,1);
        if (!owner->space || !owner->memory) error=-ENOMEM;
    }
    if (!error && !file) {
        unsigned long args[4]={t->original.regs[0],flags};
        fd=(int)md_shm_call(shm,owner->space,&owner->pending,&t->identity,t->tgid,t->resources.pid_group,MD_SHM_OPEN,args,NULL);
        if (fd<0) error=fd;
    }
    if (!error) {
        if (file) {
            struct md_control_file control=md_proc_file_control(t->proc_file);
            struct md_fs_anchor *origin=control.ops->origin ? control.ops->origin(control.context) : NULL;
            owner->noexec=!!(md_fs_anchor_policy(origin)&MD_MOUNT_NOEXEC);
            owner->readonly=control.ops->mapping_readonly && (t->original.regs[3]&MAP_SHARED);
            owner->request=(struct md_task_mapping_request){.source=t->control_mapping_fd,
                .address=t->original.regs[0],.size=t->original.regs[1],.protection=t->original.regs[2],
                .flags=t->original.regs[3],.offset=t->original.regs[5]};
            t->control_mapping_fd=-1;
            if (owner->noexec && (owner->request.protection&PROT_EXEC)) error=-EPERM;
        } else owner->request=(struct md_task_mapping_request){.source=fd,.address=address,
                .size=md_shm_pending_size(owner->pending),.flags=MAP_SHARED|(address ? MAP_FIXED_NOREPLACE : 0),
                .protection=PROT_READ|((flags&SHM_RDONLY) ? 0 : PROT_WRITE)|((flags&SHM_EXEC) ? PROT_EXEC : 0)};
        fd=-1;
        if (!error) error=md_memory_order_begin(owner->memory,&owner->order,fd_wakeup);
        if (error>=0) { t->mapping=owner; mapping_start(t); return; }
    }
    if (fd>=0) close(fd);
    if (!error) return;
    t->mapping=NULL;
    if (owner) {
        CHECK(!md_shm_abort(&owner->pending));
        CHECK(!md_shm_space_release(owner->space));
        md_pid_identity_release(owner->process);
        if (owner->request.source>=0) close(owner->request.source);
        md_memory_release(owner->memory);
        free(owner);
    }
    regs->regs[0]=error;
    if (set_registers(t->pid,regs)) resume(t->pid,0);
}
/* A native observation needs operands, not a restorable GPR image. Capture
 * the latter only before an error rewrite or task-affine adapter takes over. */
static int memory_context(struct thread *t,struct user_pt_regs *regs) {
    if (!t->memory_arguments_only) return 1;
    if (!registers(t->pid,&t->original)) return 0;
    t->memory_arguments_only=0; *regs=t->original;
    return 1;
}
static void memory_start(struct thread *t) {
    retry_clear(t,RETRY_MEMORY);
    enum md_resource_order_state state=md_resource_order_state(&t->memory_order);
    if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&t->memory_order));
    else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_MEMORY); return; }
    struct user_pt_regs regs=t->original;
    if (t->memory_action==MEMORY_DETACH) {
        if (!save_signal_mask(t,&t->mask,"memory-mask")
                || !shield(t) || !skip(t->pid)) return;
        dispatch_with_stack(t);
    } else if (t->memory_action==MEMORY_FORK) {
        t->phase=IDLE;
        fd_begin(t,&regs,MD_INTERCEPT_FD);
    } else {
        long nr=regs.regs[8];
        int error=0, noexec=0;
        struct md_svc_registry *machine=md_memory_svc(t->memory);
        if (machine) {
            if (nr!=SYS_mmap || (regs.regs[3]&MAP_FIXED))
                error=md_svc_registry_unpatch(machine,t->pid,regs.regs[0],regs.regs[1]);
            if (!error && nr==SYS_mremap && (regs.regs[3]&MREMAP_FIXED))
                error=md_svc_registry_unpatch(machine,t->pid,regs.regs[4],regs.regs[2]);
        }
        int file=nr==SYS_mmap && !(regs.regs[3]&MAP_ANONYMOUS);
        struct md_fd_native native={0};
        if (file && !error) {
            if (!t->memory_files.table) {
                error=md_fd_order_begin(t->resources.files,&t->memory_files,fd_wakeup);
                if (error>=0) error=0;
            }
            if (!error && md_fd_order_state(&t->memory_files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_MEMORY); return; }
            if (!error) {
                CHECK(!md_fd_order_start(&t->memory_files));
                int ordinary=md_fd_origins_native(&t->memory_files,(int)regs.regs[4],&native);
                struct md_proc_file *control=ordinary ? NULL : proc_file_capture(t,(int)regs.regs[4]);
                if (control) {
                    error=md_proc_file_mapping(control,(unsigned)regs.regs[2],(unsigned)regs.regs[3]);
                    struct md_control_file file=md_proc_file_control(control);
                    if (!error && file.ops->mapping) {
                        if (!regs.regs[1] || regs.regs[1]>SIZE_MAX-(md_page_size-1)
                                || (regs.regs[5]&(md_page_size-1))) error=-EINVAL;
                        else {
                            /* Capture the exact OFD, then release both turns
                             * before asking its userspace provider for data. */
                            if (!memory_context(t,&regs)) { md_proc_file_release(control); return; }
                            CHECK(!md_fd_order_complete(&t->memory_files));
                            CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
                            t->proc_file=control;
                            if (!save_signal_mask(t,&t->mask,"mapping-mask")
                                    || !shield(t) || !skip(t->pid)) return;
                            dispatch_with_stack(t); return;
                        }
                    }
                    md_proc_file_release(control);
                }
                struct md_fs_anchor *origin=ordinary ? native.origin
                    : md_fd_origins_get(t->resources.files,(int)regs.regs[4],NULL);
                noexec=!!(md_fs_anchor_policy(origin)&MD_MOUNT_NOEXEC);
                if (!ordinary) md_fs_anchor_release(origin);
                if (!error && noexec && (regs.regs[2]&PROT_EXEC)) error=-EPERM;
            }
        }
        if (!error && nr==SYS_mprotect) error=md_memory_protect_check(t->memory,regs.regs[0],regs.regs[1],regs.regs[2]);
        int overlap=md_shm_intersects(t->shm_space,regs.regs[0],regs.regs[1]);
        if (nr==SYS_mremap && (regs.regs[3]&MREMAP_FIXED))
            overlap|=md_shm_intersects(t->shm_space,regs.regs[4],regs.regs[2]);
        if (!error && overlap && nr==SYS_mremap) error=-ENOTSUP;
        int source=-1; uint64_t offset=0;
        int repair=nr==SYS_mprotect && md_memory_private_access(t->memory)
            && md_memory_file_view(t->memory,regs.regs[0],regs.regs[1],&source,&offset);
        int tracked=nr!=SYS_mprotect && md_memory_mapping_observe(t->memory,regs.regs[0],
            nr==SYS_mremap && !regs.regs[1] ? 1 : regs.regs[1]);
        if (nr==SYS_mremap && (regs.regs[3]&MREMAP_FIXED))
            tracked|=md_memory_mapping_observe(t->memory,regs.regs[4],regs.regs[2]);
        int observe=repair || (nr!=SYS_mprotect && (file || overlap || tracked));
        TRACE("PROBE memory-plan pid=%d nr=%ld address=%#llx size=%llu protection=%#llx flags=%#llx file=%d tracked=%d repair=%d sole=%d\n",
            t->pid,nr,regs.regs[0],regs.regs[1],regs.regs[2],regs.regs[3],file,tracked,repair,
            md_memory_sole_owner(t->memory));
        if (!error && observe && nr!=SYS_mprotect) error=md_memory_mapping_prepare(t->memory,&t->memory_change,
            nr==SYS_mmap ? MD_MAPPING_MAP : nr==SYS_munmap ? MD_MAPPING_UNMAP : MD_MAPPING_REMAP,
            regs.regs[0],regs.regs[1],nr==SYS_mremap ? regs.regs[2] : 0,(unsigned)regs.regs[3],noexec);
        if (!error && file && !noexec && (regs.regs[3]&MAP_TYPE)==MAP_PRIVATE
                && md_memory_private_access(t->memory)) {
            int number=(int)regs.regs[4],captured=0;
            /* The running file-table turn authenticates this immutable pin.
             * Typed providers, uncertain slots and denied pin access still
             * take the exact kernel-descriptor capture path below. */
            if (native.kind==S_IFREG) {
                captured=md_memory_mapping_pin(t->memory,&t->memory_change,
                    md_fs_anchor_fd(native.origin),regs.regs[5]);
            }
            if (captured<=0) {
                int fd=duplicate_fd(t->pid,number);
                t->memory_backing_pending=fd<0;
                captured=fd<0 ? fd : md_memory_mapping_backing(t->memory,&t->memory_change,fd,regs.regs[5]);
                if (fd>=0) close(fd);
            }
            /* This optional authority cannot fail native mmap or relax noexec.
             * Denied direct access uses task-affine capture after native mmap. */
            TRACE("PROBE memory-backing pid=%d fd=%d retained=%d result=%d\n",
                t->pid,number,!!t->memory_change.backing,captured);
        }
        if (error || !observe) {
            if (error && !memory_context(t,&regs)) return;
            if (t->memory_files.table) CHECK(!md_fd_order_complete(&t->memory_files));
            CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
            t->memory_arguments_only=0;
            t->phase=IDLE;
            if (error) return_value(t,&regs,error); else resume(t->pid,0);
        } else { t->phase=OBSERVING; resume(t->pid,0); }
    }
}
static void memory_begin(struct thread *t, const struct user_pt_regs *regs, enum memory_action action,int arguments_only) {
    CHECK(!t->memory_order.queue && !t->memory_action);
    t->memory_arguments_only=arguments_only;
    t->original=*regs; t->memory_action=action; t->phase=MEMORY_WAIT; t->memory_backing_pending=0;
    CHECK(md_memory_order_begin(t->memory,&t->memory_order,fd_wakeup)>=0);
    /* EVENT_WAIT: address-space publication precedes competing unmap/fork;
     * task exit cancels its queued turn. fd_ready drives progress, not polling. */
    memory_start(t);
}
static int memory_observation(struct thread *t,const struct ptrace_syscall_info *info,
        struct user_pt_regs *observed,int *native_entry) {
    long nr=(long)info->seccomp.nr;
    if (info->arch!=AUDIT_ARCH_AARCH64
            || (nr!=SYS_mmap && nr!=SYS_munmap && nr!=SYS_mprotect && nr!=SYS_mremap)
            || info->seccomp.ret_data!=MD_INTERCEPT_MEMORY || t->entry_fence_result
            || t->memory_order.queue || t->memory_action || t->debug_options
            || t->debug_call_active || t->debug_step_call || md_debugger_traced(debugger,t->pid)) return 0;
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    *observed=(struct user_pt_regs){.pc=info->instruction_pointer,.sp=info->stack_pointer};
    observed->regs[8]=nr;
    for (unsigned i=0;i<6;++i) observed->regs[i]=info->seccomp.args[i];
    *native_entry=0;
    if (md_svc_registry_context(machine,observed->pc,observed->sp)) {
        /* The exact kernel alias has already restored the caller's context.
         * A private stack or any other resident PC still needs full recovery. */
        if (t->application_filter || t->cooperative_blocked || md_memory_exposed(t->memory)
                || (observed->sp>machine->frames && observed->sp-machine->frames<=MD_SVC_POOL_SIZE)) return 0;
        *native_entry=md_svc_machine_native_entry(machine,observed);
        if (!*native_entry) return 0;
    }
    return 1;
}
static int memory_observed(struct thread *t,const struct ptrace_syscall_info *info) {
    struct user_pt_regs observed;
    int native_entry;
    if (!memory_observation(t,info,&observed,&native_entry)) return 0;
    if (native_entry!=1) {
        struct user_pt_regs discovery=observed;
        if (native_entry) discovery.pc=md_svc_registry_call_origin(md_memory_svc(t->memory),observed.pc);
        machine_observe(t,&discovery);
    }
    if (statistics) ++statistics->calls[observed.regs[8]].trace;
    memory_begin(t,&observed,MEMORY_KERNEL,1);
    return 1;
}
static void proc_lookup_start(struct thread *t) {
    retry_clear(t,RETRY_PROC_LOOKUP);
    if (md_fd_order_state(&t->proc_lookup)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_PROC_LOOKUP); return; }
    CHECK(t->phase==PROC_LOOKUP && !md_fd_order_start(&t->proc_lookup));
    struct user_pt_regs regs=t->original;
    long nr=regs.regs[8];
    struct md_proc_file *file=proc_file_capture(t,(int)regs.regs[nr==SYS_mmap ? 4 : 0]);
    t->proc_splice_write=!file;
    if (!file && (nr==SYS_sendfile || nr==SYS_tee || nr==SYS_splice || nr==SYS_copy_file_range))
        file=proc_file_capture(t,(int)regs.regs[nr==SYS_sendfile || nr==SYS_tee ? 1 : 2]);
    CHECK(!md_fd_order_complete(&t->proc_lookup));
    t->phase=IDLE;
    /* A selector collision changes the filter cookie, not syscall ownership.
     * Ordinary directory cursors and filesystem metadata still need their
     * filesystem adapter, including after the pre-sandbox all-FD selector. */
    struct md_control_file operations=md_proc_file_control(file);
    if (md_proc_file_filesystem_paths(file) && (nr==SYS_fstatfs || nr==SYS_syncfs
            || ((nr==SYS_fsync || nr==SYS_fdatasync) && (!operations.ops || !operations.ops->command))
            || (nr==SYS_ioctl && md_filesystem_ioctl_command((unsigned)regs.regs[1])))) {
        md_proc_file_release(file); file=NULL;
    }
    int filesystem=!file && (nr==SYS_lseek || nr==SYS_getdents64 || nr==SYS_fstatfs
        || nr==SYS_fsync || nr==SYS_fdatasync || nr==SYS_syncfs
        || (nr==SYS_ioctl && md_filesystem_ioctl_command((unsigned)regs.regs[1])));
    if (nr==SYS_mmap) {
        md_proc_file_release(file);
        memory_begin(t,&regs,MEMORY_KERNEL,0); return;
    }
    if (file && nr==SYS_fcntl) {
        long result=0;
        if (regs.regs[1]==F_SETFL && (regs.regs[2]&O_NOATIME)
                && !(md_proc_file_flags(file)&(O_NOATIME|O_PATH))) {
            struct stat st;
            struct md_proc_file_node node=md_proc_file_get_node(file);
            struct proc_owner owner=proc_owner(node.process);
            result=md_proc_file_control(file).ops && !md_proc_file_task(md_proc_file_kind(file)) ? md_proc_file_control_stat(file,&st)
                : !owner.identity ? -ESRCH : proc_node_stat(owner,md_proc_file_kind(file),&node,&st);
            if (!result) result=md_file_noatime(&st,&t->identity);
        }
        int handled=result ? 1 : md_proc_file_fcntl(file,(int)regs.regs[1],regs.regs[2],&result);
        md_proc_file_release(file);
        if (handled) return_value(t,&regs,result); else resume(t->pid,0);
        return;
    }
    if (file) {
        unsigned long args[6];
        for (unsigned i=0;i<6;++i) args[i]=regs.regs[i];
        struct md_proc_io_spec spec;
        struct md_control_file control=md_proc_file_control(file);
        int namespace_ioctl=nr==SYS_ioctl && (md_proc_file_namespace(md_proc_file_kind(file))
            || (control.ops && control.ops->ioctl));
        int error=nr==SYS_fstatfs || namespace_ioctl ? 0 : md_proc_io_decode(nr,args,&spec);
        if (!error && nr==SYS_splice) {
            if (!control.ops || !control.ops->splice) error=-EINVAL;
            spec.writing=t->proc_splice_write;
        }
        if (error && nr==SYS_ioctl) error=-ENOTTY;
        if (!error && namespace_ioctl && (md_proc_file_flags(file)&O_PATH)) error=-EBADF;
        if (!error && nr!=SYS_fstatfs && !namespace_ioctl) error=md_proc_file_access(file,
            spec.command ? MD_PROC_FILE_COMMAND : spec.seek ? MD_PROC_FILE_SEEK : spec.directory ? MD_PROC_FILE_GETDENTS
                : spec.writing ? MD_PROC_FILE_WRITE : MD_PROC_FILE_READ);
        if (error) { md_proc_file_release(file); return_value(t,&regs,error); return; }
        t->proc_file=file;
    } else if (!filesystem && nr!=SYS_read && nr!=SYS_readv && nr!=SYS_ioctl && nr!=SYS_splice && nr!=SYS_tee && nr!=SYS_sendfile) {
        resume(t->pid,0); return;
    }
    t->watch_wait=0;
    if (!save_signal_mask(t,&t->mask,"proc-lookup-mask")
            || !shield(t) || !skip(t->pid)) return;
    if (file || filesystem) {
        calls++; t->calls++; t->debug_step_call=md_debugger_step(debugger,t->pid);
        dispatch_with_stack(t);
    } else { t->phase=WATCH_CANCEL; resume(t->pid,0); }
}
static void proc_lookup_begin(struct thread *t,const struct user_pt_regs *regs) {
    if (!t->ready || t->phase!=IDLE || t->proc_file || t->proc_lookup.table)
        fprintf(stderr,"Shroot: proc lookup conflict pid=%d ready=%d phase=%d file=%d lookup=%d pc=%#llx nr=%llu original=%llu originalPc=%#llx\n",
            t->pid,t->ready,t->phase,!!t->proc_file,!!t->proc_lookup.table,
            regs->pc,regs->regs[8],t->original.regs[8],t->original.pc);
    CHECK(t->ready && t->phase==IDLE && !t->proc_file && !t->proc_lookup.table);
    t->original=*regs; t->phase=PROC_LOOKUP;
    CHECK(md_fd_order_begin(t->resources.files,&t->proc_lookup,fd_wakeup)>=0);
    /* EVENT_WAIT: preceding kernel close/dup and its metadata publication must
     * precede classification of a possibly reused number. Exit cancels lookup. */
    proc_lookup_start(t);
}
static void memory_capture_complete(void *context,struct md_task_helper_result result) {
    struct memory_capture *capture=context;
    struct thread *t=capture->caller ? find_thread(capture->caller) : NULL;
    CHECK(result.descriptor==-1);
    if (t) {
        CHECK(t->memory_capture==capture);
        int error=(int)result.value;
        if (!error && result.received && result.received->count==1)
            error=md_memory_mapping_backing(t->memory,&t->memory_change,result.received->fds[0],t->original.regs[5]);
        TRACE("PROBE memory-backing-export pid=%d retained=%d result=%d\n",t->pid,!!t->memory_change.backing,error);
        md_memory_mapping_finish(t->memory,&t->memory_change,(long)capture->returned.regs[0]);
        if (t->memory_files.table) CHECK(!md_fd_order_complete(&t->memory_files));
        CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
        t->memory_arguments_only=0;
        t->memory_capture=NULL; t->kernel_stopped=1; t->phase=IDLE;
        if (set_registers(t->pid,&capture->returned)
                && signal_mask(t,capture->mask,"memory-capture-mask"))
            resume(t->pid,0);
    }
    md_received_fds_release(result.received);
    CHECK(!md_buffer_release(&capture->buffer));
    free(capture);
}
static void memory_captured(void *context) {
    struct memory_capture *capture=context;
    struct thread *t=capture->caller ? find_thread(capture->caller) : NULL;
    if (t && t->memory_files.table) CHECK(!md_fd_order_complete(&t->memory_files));
}
static int memory_capture_begin(struct thread *t,const struct user_pt_regs *returned) {
    /* Optional backing capture must not add clone/sendmsg requirements to an
     * already successful mmap. A filter can kill, not merely reject, the
     * helper's creation. Retain the ordinary memory path in that domain. */
    if (t->application_filter || !t->memory_backing_pending || (long)returned->regs[0]<0
            || t->memory_change.backing || t->memory_change.restrictions
            || t->original.regs[8]!=SYS_mmap || (t->original.regs[3]&MAP_ANONYMOUS)
            || (t->original.regs[3]&MAP_TYPE)!=MAP_PRIVATE || !md_memory_private_access(t->memory)) return 0;
    struct memory_capture *capture=calloc(1,sizeof(*capture));
    if (!capture) return 0;
    capture->caller=t->pid; capture->returned=*returned;
    int error=md_buffers_take(md_memory_buffers(t->memory),&capture->buffer);
    if (error) { free(capture); return 0; }
    if (!save_signal_mask(t,&capture->mask,"memory-capture-mask")
            || !shield(t)) { md_buffer_release(&capture->buffer); free(capture); return 0; }
    int fd=(int)t->original.regs[4];
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_EXPORT,.descriptors=&fd,.descriptor_count=1,
        .captured=memory_captured};
    t->terminal_observation=1; t->trace_options_dirty=1;
    error=debugger_options(t->pid,t->debug_options);
    t->memory_capture=capture;
    if (!error) error=md_task_helper_begin(helpers,t->pid,t->tgid,returned,&abi,t->memory,&capture->buffer,
        &request,memory_capture_complete,capture);
    if (error) memory_capture_complete(capture,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
    return 1;
}
static void ipc_wait_complete(struct thread *t) {
    struct user_pt_regs returned;
    if (!registers(t->pid,&returned) || !shield(t)) return;
    ipc_unwatch(t);
    long value=(long)returned.regs[0];
    TRACE("PROBE ipc-wait pid=%d value=%ld waking=%d\n",t->pid,value,t->ipc_waking);
    int interrupted=md_syscall_restarting(value);
    if (value<0 && !(t->ipc_waking && interrupted)) t->ipc_cancel=interrupted ? -EINTR : value;
    t->ipc_waking=0;
    dispatch_with_stack(t);
}
static void debugger_park_complete(struct thread *t) {
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return;
    long value=(long)regs.regs[0];
    int waking=t->debug_wait_waking; t->debug_wait_waking=0;
    int interrupted=md_syscall_restarting(value);
    TRACE("PROBE debugger park pid=%d value=%ld waking=%d\n",t->pid,value,waking);
    t->phase=IDLE;
    if (!signal_mask(t,t->debug_wait_mask,"debug-park-unmask")) return;
    md_debugger_wait_resumed(debugger,t->pid,waking && (interrupted || value==-ENOSYS || !value) ? 0
        : interrupted ? -512 : value<0 ? value : -EIO);
}
static void readiness_interrupted(struct thread *t) {
    /* The kernel owns an atomic wait's temporary mask until signal return.
     * FD cleanup and queued carriers must not shield this continuation before
     * the next syscall entry proves the signal frame has been committed. */
    t->signal_return_pending=1;
    readiness_release(t); CHECK(!buffer_release(t));
    struct user_pt_regs regs=t->original; regs.regs[0]=(uint64_t)-EINTR;
    if (!set_registers(t->pid,&regs)) return;
    md_memory_stack_return(t->memory,t->stack); t->stack=0;
    t->phase=IDLE; resume(t->pid,0);
}
static void socket_wait_complete(struct thread *t) {
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return;
    socket_unwatch(t);
    long value=(long)regs.regs[0];
    TRACE("PROBE socket-wait pid=%d value=%ld waking=%d\n",t->pid,value,t->socket_waking);
    int restart=md_syscall_restarting(value);
    int waking=t->socket_waking; t->socket_waking=0;
    if (t->readiness && restart && !waking) {
        readiness_interrupted(t); return;
    }
    if (!shield(t)) return;
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
static int readiness_native_event(struct thread *t,int status) {
    unsigned event=(unsigned)status>>16;
    int signal=WSTOPSIG(status);
    if (t->phase==WAIT_CANCEL_EXIT && t->wait_resume==EPOLL_CALL) {
        if (event || signal!=(SIGTRAP|0x80)) return 0;
        struct ptrace_syscall_info info={0};
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"epoll-cancel-exit")) return 1;
        struct readiness_owner *o=t->readiness;
        CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT && o && (o->evicting || o->finishing));
        /* Only a drained exit can lend its continuation to FD retirement.
         * Rewriting an entry to the adapter's PC would re-enter seccomp with
         * the reply in x0 and mistake that value for a service command. */
        t->phase=DISPATCHING; t->wait_resume=IDLE;
        if (o->evicting) { o->parked=1; CHECK(!md_event_signal(fd_ready)); }
        else readiness_reply(t,o->finish_result);
        return 1;
    }
    if (t->phase!=EPOLL_CALL && t->phase!=EPOLL_WAIT) return 0;
    struct user_pt_regs regs;
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
                t->socket_waking,t->application_filter,o->evicting);
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
        int error=o->poll ? 0 : md_epoll_wait_observe(&o->wait,md_control_epoll_from_object(o->queue));
        if (!error && o->wait.job.started) error=socket_watch(t);
        if (error) {
            o->finishing=1; o->finish_result=error;
            md_epoll_wait_cancel(&o->wait);
            wait_cancel_entry(t,EPOLL_CALL);
            return 1;
        }
        if (restore_mask(t)) { t->phase=EPOLL_WAIT; resume(t->pid,0); }
        return 1;
    }
    if (!(event==MD_TASK_CHECKPOINT_EVENT || (!event && signal==(SIGTRAP|0x80))
            || (event==PTRACE_EVENT_STOP && signal==SIGTRAP && t->socket_waking))) return 0;
    if (!registers(t->pid,&regs)) return 1;
    if (event==PTRACE_EVENT_STOP && t->phase==EPOLL_CALL && regs.pc==abi.task_call) {
        struct readiness_owner *o=t->readiness;
        CHECK(regs.regs[8]==(unsigned long)o->native_call.number
            && !memcmp(regs.regs,o->native_call.value,sizeof(o->native_call.value)));
        /* A quiescence interrupt can precede SVC. No result exists yet;
         * consume this service wake and retain the shield until real entry. */
        t->socket_waking=0;
        resume(t->pid,0); return 1;
    }
    if (regs.pc!=(event==MD_TASK_CHECKPOINT_EVENT ? abi.task_completed+4 : abi.task_return))
        fprintf(stderr,"Shroot: readiness stop pid=%d phase=%d status=%#x pc=%#lx return=%#lx completed=%#lx nr=%lu value=%ld waking=%d\n",
            t->pid,t->phase,status,(unsigned long)regs.pc,(unsigned long)abi.task_return,
            (unsigned long)abi.task_completed,(unsigned long)regs.regs[8],(long)regs.regs[0],t->socket_waking);
    CHECK(regs.pc==(event==MD_TASK_CHECKPOINT_EVENT ? abi.task_completed+4 : abi.task_return));
    long value=(long)regs.regs[0];
    int restart=md_syscall_restarting(value);
    int waking=t->socket_waking; t->socket_waking=0;
    TRACE("PROBE readiness-return pid=%d value=%ld waking=%d virtual=%d blocking=%d\n",
        t->pid,value,waking,t->readiness->wait.virtual_count,t->readiness->wait.blocking);
    socket_unwatch(t);
    md_epoll_wait_cancel(&t->readiness->wait);
    if ((restart || value==-EINTR) && !waking) {
        struct readiness_owner *o=t->readiness;
        long ready=o->wait.virtual_count;
        if (!ready) {
            struct md_buffer_span span;
            ready=readiness_frame(t,&span);
            if (!ready) {
                struct md_epoll_frame *frame=span.data;
                frame->native_output=0;
                ready=o->poll ? md_poll_wait_interrupted(o->poll)
                    : md_control_epoll_collect(md_control_epoll_from_object(o->queue),frame->events,frame->capacity);
            }
        }
        /* Virtual readiness may arrive alongside a guest signal. Consume it
         * once, without inspecting task-affine native descriptors here. */
        if (ready>0) {
            if (shield(t)) readiness_reply(t,ready);
            return 1;
        }
        readiness_interrupted(t); return 1;
    }
    if (!shield(t)) return 1;
    if (waking && (restart || value==-EINTR)) value=0;
    readiness_task_progress(t,1,value); return 1;
}
static void file_wait_complete(struct thread *t) {
    struct user_pt_regs returned;
    if (!registers(t->pid,&returned)) return;
    if (!shield(t)) return;
    if (!md_fs_buffer_open_ready(t->buffer_call)) {
        /* EVENT_WAIT: the exact helper acknowledges interruption. Keep this
         * task stopped, not the supervisor or other guest threads. A completed
         * endpoint wins; only an unpaired open retains kernel SA_RESTART. */
        t->phase=FILE_INTERRUPTING;
        CHECK(!md_fs_buffer_open_interrupt(t->buffer_call));
        return;
    }
    t->phase=DISPATCHING;
    if (!md_fs_buffer_open_interrupted(t->buffer_call)) {
        /* Direct publication captures its own shield. Do not let it mistake
         * this wait's temporary mask for the application's saved mask. */
        if (t->direct==DIRECT_OPEN && !restore_mask(t)) return;
        CHECK(!md_fs_buffer_open_resume(t->buffer_call));
        return;
    }
    /* The helper confirmed that no endpoint was admitted. The kernel owns
     * the application's restart decision; the adapter never replays a path. */
    CHECK(!buffer_cleanup(t)); t->direct=DIRECT_NONE;
    t->broker_event.held=0;
    long value=(long)returned.regs[0];
    if (value==-EINTR || md_syscall_restarting(value)) restart_call(t,&t->original,t->mask,RESTART_SIGNAL);
    else {
        struct user_pt_regs regs=t->original; regs.regs[0]=value<0 ? value : -EIO;
        if (!set_registers(t->pid,&regs) || !restore_mask(t)) return;
        md_memory_stack_return(t->memory,t->stack); t->stack=0;
        t->phase=IDLE; resume(t->pid,0);
    }
}
static void control_wait_complete(struct thread *t) {
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs) || !shield(t)) return;
    t->phase=DISPATCHING;
    if (t->control_done) {
        t->control_done=0; control_deliver(t,t->control_result); return;
    }
    long value=(long)regs.regs[0];
    int interrupted=value==-EINTR || md_syscall_restarting(value);
    /* Providers explicitly admit repeatable preparation, never committed
     * writes/ioctls. Ordinary IO retains its unadmitted/no-prefix rule. */
    long retry=interrupted && t->control_pending && t->control_pending->retry_preparation
        ? RESTART_PREPARATION : interrupted && !t->control_pending && !t->proc_io.executed
            && !t->proc_io.transferred && t->original.regs[8]!=SYS_splice ? RESTART_SIGNAL : 0;
    retry_clear(t,RETRY_CONTROL); retry_clear(t,RETRY_PROC_IO);
    if (t->control_pending) { t->control_pending->dispose(t->control_pending); t->control_pending=NULL; }
    CHECK(!md_proc_file_io_cancel(&t->proc_io));
    t->proc_waiting=0;
    control_deliver(t,retry ? retry : interrupted ? -EINTR : value<0 ? value : -EIO);
}
static int interrupted_operation(struct thread *t) {
    if (t->phase==MEMORY_WAIT || t->phase==PROC_LOOKUP || t->mapping) return 1;
    /* EVENT_WAIT: INTERRUPT cancels the exact notification. Under SYSCALL the
     * acknowledgement is a syscall-exit stop; under CONT it is EVENT_STOP. */
    if (t->phase==EXPORT_CANCEL) { export_start(t); return 1; }
    if (t->phase==PROC_CANCEL) { proc_query_start(t); return 1; }
    if (t->phase==IPC_WAITING && t->ipc_waking) { ipc_wait_complete(t); return 1; }
    if (t->phase==SOCKET_WAITING && t->socket_waking) { socket_wait_complete(t); return 1; }
    if (t->phase==FILE_WAITING && md_fs_buffer_open_ready(t->buffer_call)) { file_wait_complete(t); return 1; }
    if (t->phase==CONTROL_WAITING && t->control_done) { control_wait_complete(t); return 1; }
    if (t->phase==CLOSE_WAITING && t->close_done) { close_wait_complete(t); return 1; }
    if (t->phase==DEBUG_PARK_EXIT && t->debug_wait_waking) { debugger_park_complete(t); return 1; }
    if (t->phase!=DELEGATE_CANCEL) return 0;
    const struct seccomp_notif *q=&t->metadata.request;
    CHECK(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&q->id)==-1 && errno==ENOENT);
    struct user_pt_regs regs;
    if (t->metadata_restart) regs=t->metadata_restart->saved;
    else if (!registers(t->pid,&regs)) return 1;
    regs.pc=q->data.instruction_pointer; regs.regs[8]=q->data.nr;
    for (unsigned i=0;i<6;i++) regs.regs[i]=q->data.args[i];
    int machine=md_svc_machine_return(md_memory_svc(t->memory),&t->machine,t->pid,&regs);
    if (machine==-ESRCH) { t->resume_lost=1; return 1; }
    if (machine<0) errno=-machine;
    CHECK(machine>=0);
    if (machine) {
        notification_release(t); t->phase=IDLE;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return 1;
    }
    if (machine_observe(t,&regs) && !t->metadata_restart && !t->entry_fence_result) {
        /* The notification was cancelled before any effect. Only a newly
         * published site can enter here; an existing gate's fallback must
         * complete through Base instead of retrying the same gate forever. */
        notification_release(t); t->phase=IDLE; regs.pc-=4;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return 1;
    }
    t->original=regs; calls++; t->calls++;
    if (receive_entry(t)) return 1;
    if (!save_signal_mask(t,&t->mask,"get-mask") || !shield(t)) return 1;
    dispatch_with_stack(t);
    return 1;
}
static void cancellation_arm(unsigned seconds) {
    if (cancellation_timer<0) {
        cancellation_timer=timerfd_create(CLOCK_MONOTONIC,TFD_CLOEXEC|TFD_NONBLOCK);
        CHECK(cancellation_timer>=0);
    }
    const struct itimerspec timer={.it_value.tv_sec=seconds};
    CHECK(!timerfd_settime(cancellation_timer,0,&timer,NULL));
}
static void cancel_tree(int status) {
    result=status; cancelling=1;
    cancellation_arm(2);
    CHECK(!md_process_signal_children(SIGTERM));
}
static void describe_pending_tasks(void) {
    unsigned shown=0,total=0;
    for (struct thread *t=threads;t;t=t->next) if (t->pid) {
        ++total;
        if (shown==16) continue;
        ++shown;
        fprintf(stderr,"Shroot: pending pid=%d tgid=%d phase=%d stopped=%d listening=%d interrupt=%d syscall=%llu buffer=%d broker=%d ipc=%d outgoing=%d\n",
            t->pid,t->tgid,t->phase,t->kernel_stopped,t->listening,t->interrupt_pending,t->original.regs[8],
            t->buffer_call!=NULL,t->broker_completion!=NULL,t->ipc_pending!=NULL,t->outgoing!=NULL);
    }
    if (total>shown) fprintf(stderr,"Shroot: pending tasks shown=%u total=%u\n",shown,total);
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
        CHECK(!md_process_signal_children(SIGKILL));
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
            TRACE("PROBE cancelled signal=%u\n", info.ssi_signo);
            cancel_tree(128 + info.ssi_signo);
        }
    }
}
static int pid_gone(pid_t pid) {
    char path[64]; struct stat st;
    snprintf(path,sizeof(path),"/proc/%d",pid);
    return fstatat(AT_FDCWD,path,&st,0)<0 && errno==ENOENT;
}
static int pid_collection_waiting(const struct thread *t) {
    return t && t->pid && (t->phase==PROCESS_OBSERVING || t->phase==PROCESS_INFO_LOAD
        || t->phase==PROCESS_INFO_STORE || t->phase==DEBUG_WAIT_ENTRY || t->phase==DEBUG_WAIT_EXIT);
}
static void pid_collect(void) {
    if (!pid_collection_pending || !md_task_registry_has_retired(tasks)) {
        pid_collection_pending=0; pid_collection_blocker=NULL; return;
    }
    /* Thread slots remain allocated until teardown. Revalidate the last waiter
     * instead of rescanning the tree at every unrelated syscall event. A stale
     * hint never authorizes collection: only the complete scan below can. */
    if (pid_collection_waiting(pid_collection_blocker)) return;
    pid_collection_blocker=NULL;
    for (struct thread *t=threads;t;t=t->next) if (pid_collection_waiting(t)) {
        pid_collection_blocker=t; return;
    }
    /* Exit may precede translation of a completed kernel wait. Only quiescent
     * wait ownership permits collecting automatically reaped identities. */
    md_task_registry_collect(tasks,pid_gone); pid_collection_pending=0;
}
static void progress_events(const struct pollfd *events) {
    if (events[0].revents) drain();
    if (events[6].revents) cancellation_expired();
    if (events[2].revents) { CHECK(!md_fs_worker_dispatch(filesystem)); adapter_progress(); }
    if (events[3].revents) fd_progress();
    if (events[4].revents) ipc_events_ready();
    if (events[5].revents) socket_events_ready();
    if (events[1].revents & POLLIN) external_stat();
    if (events[1].revents & POLLHUP) { close(listener); listener = -1; }
}
enum { EVENT_SOURCES = 7 };
static void event_sources(struct pollfd events[EVENT_SOURCES]) {
    const struct pollfd current[EVENT_SOURCES] = {{signal_fd,POLLIN,0}, {broker ? -1 : listener,POLLIN,0},
        {md_fs_worker_fd(filesystem),POLLIN,0}, {fd_ready,POLLIN,0},
        {ipc_events,POLLIN,0}, {socket_events,POLLIN,0}, {cancellation_timer,POLLIN,0}};
    memcpy(events,current,sizeof(current));
}
static int entry_fence_adapter_drained(const struct thread *t) {
    return !t->metadata_restart && !t->fd_running && !t->fd_order.table && !t->fs_order.fs
        && !t->signal_delivery.active && !t->machine.draining && !t->machine.syscall_exit
        && !t->machine.signal_alias && !t->direct && !t->buffer_call && !t->buffer_waiting
        && !t->broker_completion;
}
static int entry_fence_boundary(const struct thread *t) {
    return t->phase==IDLE && entry_fence_adapter_drained(t);
}
static int entry_fence_drained(const struct thread *t) {
    if (entry_fence_boundary(t)) return 1;
    /* An admitted native readiness wait can depend on the filter installer.
     * Its syscall has passed kernel admission; retain its owned completion
     * across TSYNC instead of waiting for the application event. Dumpability
     * still needs the full adapter boundary before remote access is revoked. */
    if (entry_fence_protected || !entry_fence_adapter_drained(t)) return 0;
    /* Native fallback and broker-owned reads have already admitted the original
     * call and released task-affine preparation. A blocking inotify read may
     * itself await the installer. MSG_PEEK is only a preparatory wait and must
     * still drain before its task-affine operation can cross the transition. */
    if (t->phase==WATCH_READ && t->watch_wait!=1) return 1;
    return t->phase==EPOLL_WAIT && t->readiness
        && t->readiness->native_caller && !t->readiness->evicting && !t->readiness->finishing;
}
static int entry_fence_park(const struct thread *t) {
    return entry_fence_protected && entry_fence_memory && entry_fence_stage!=3 && t && t->memory==entry_fence_memory
        && t->initial_stop && t!=entry_fence_owner && !t->entry_fence_waiting && !t->terminal_stop
        && entry_fence_boundary(t);
}
static int entry_fence_adapt(const struct thread *t) {
    return !entry_fence_protected && entry_fence_memory && entry_fence_stage!=3 && t
        && t->memory==entry_fence_memory && t!=entry_fence_owner && !t->entry_fence_waiting
        && entry_fence_boundary(t);
}
static int entry_fence_admission(struct thread *t,const struct user_pt_regs *r,int status,const struct rusage *usage) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int filter=(r->regs[8]==SYS_seccomp && r->regs[0]==SECCOMP_SET_MODE_FILTER)
        || (r->regs[8]==SYS_prctl && r->regs[0]==PR_SET_SECCOMP && r->regs[1]==SECCOMP_MODE_FILTER);
    int protected=r->regs[8]==SYS_prctl && r->regs[0]==PR_SET_DUMPABLE && !r->regs[1];
    if (!t->entered || t->phase!=IDLE || r->pc==RAW_GATE || t==entry_fence_owner
            || (!filter && !protected)) return 0;
    int unrestricted=0;
    if (filter) {
        int group=r->regs[8]==SYS_seccomp && (r->regs[1]&SECCOMP_FILTER_FLAG_TSYNC);
        for (struct thread *p=threads;p;p=p->next) if (p->pid
                && (p==t || (group && p->tgid==t->tgid)) && !p->application_filter) {
            unrestricted=1; break;
        }
    }
    if ((!registry || registry->entry_fence_drained) && !unrestricted) return 0;
    /* Revoke before taking the cold barrier. Calls already inside the adapter
     * finish before a new filter or loss of remote memory access. Explicit API
     * clients do not require patch ownership and retain their protected path. */
    if (filter) filter_revoke_entry(t,r);
    else CHECK(!md_svc_registry_revoke(registry,t->pid));
    if (!md_svc_registry_resident(registry) && !unrestricted) {
        if (registry) registry->entry_fence_drained=1;
        return 0;
    }
    CHECK(!t->entry_fence_waiting);
    ++entry_fence_waiters; t->entry_fence_waiting=1; t->entry_fence_status=status;
    t->entry_fence_dumpability=protected;
    if (usage) t->entry_fence_usage=*usage;
    return 1;
}
static void entry_fence_progress(void) {
    if (!entry_fence_memory) {
        /* Admission and retirement own the count. Ordinary task events must
         * not scan every live/retired slot for a rare protection transition. */
        if (!entry_fence_waiters) return;
        if (quiescence_busy(NULL)) return;
        struct thread *caller=threads;
        while (caller && (!caller->pid || !caller->entry_fence_waiting)) caller=caller->next;
        if (!caller) return;
        entry_fence_memory=md_memory_fork(caller->memory,1); CHECK(entry_fence_memory);
        entry_fence_barrier=md_task_quiesce_new(quiesce_trace); CHECK(entry_fence_barrier);
        entry_fence_protected=caller->entry_fence_dumpability;
        entry_fence_stage=entry_fence_protected ? 1 : 2;
        entry_fence_slots=0; entry_fence_retry_at=0;
        entry_fence_error=0; entry_fence_deadline=md_event_now()+5000000000LL;
        if (!entry_fence_protected) {
            if (statistics) ++statistics->entry_fence_transitions;
            return;
        }
        if (statistics) ++statistics->entry_fence_quiescences;
        int resident=md_svc_registry_resident(md_memory_svc(entry_fence_memory));
        /* Only this mm owns the revoked text and resident gates. Unrelated
         * children can be at independent helper/exec boundaries. */
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory
                && (resident || !entry_fence_boundary(p)))
            CHECK(!md_task_quiesce_add(entry_fence_barrier,p->pid,p->kernel_stopped || p->listening || p->vfork_child,
                p->interrupt_pending || birth_stop_pending(p) ? 0 : p->kernel_resume));
        CHECK(!md_task_quiesce_stop(entry_fence_barrier));
    }
    if (md_event_now()>=entry_fence_deadline) {
        if (!ENTRY_QUIESCING) {
            /* Admission stays revoked, but the requested kernel transition
             * must not commit before every resident entry has drained. */
            for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory
                    && !p->entry_fence_waiting && !p->terminal_stop && !entry_fence_drained(p))
                TRACE("PROBE entry-fence pending pid=%d phase=%d nr=%llu watch=%d draining=%d exit=%d alias=%d direct=%d buffer=%d waiting=%d broker=%d fd=%d fs=%d signal=%d\n",
                    p->pid,p->phase,p->original.regs[8],p->watch_wait,p->machine.draining,p->machine.syscall_exit,!!p->machine.signal_alias,
                    !!p->direct,!!p->buffer_call,p->buffer_waiting,!!p->broker_completion,
                    !!p->fd_order.table,!!p->fs_order.fs,p->signal_delivery.active);
            entry_fence_error=-EBUSY; entry_fence_deadline=INT64_MAX;
            return;
        }
        fprintf(stderr,"Shroot: syscall entry drain timeout stage=%d\n",entry_fence_stage);
        md_task_quiesce_describe(entry_fence_barrier,STDERR_FILENO);
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory)
            fprintf(stderr,"Shroot: entry peer pid=%d phase=%d draining=%d exit=%d waiting=%d\n",
                p->pid,p->phase,p->machine.draining,p->machine.syscall_exit,p->entry_fence_waiting);
        errno=ETIMEDOUT; CHECK(0);
    }
    if (!ENTRY_QUIESCING || !md_task_quiesce_ready(entry_fence_barrier)) return;
    if (statistics) ++statistics->entry_fence_transitions;
    struct md_svc_registry *registry=md_memory_svc(entry_fence_memory);
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory
            && !p->terminal_stop && !p->vfork_child) {
        struct md_task_quiesce_stop boundary;
        if (!md_task_quiesce_contains(entry_fence_barrier,p->pid)) continue;
        int error=md_task_quiesce_borrow(entry_fence_barrier,p->pid,&boundary);
        if (error==-ESRCH) continue;
        CHECK(!error);
        unsigned event=(unsigned)boundary.status>>16;
        int terminal=boundary.kind==MD_TASK_QUIESCE_EVENT
            && (event==PTRACE_EVENT_EXEC || event==PTRACE_EVENT_EXIT);
        int syscall_stop=boundary.kind==MD_TASK_QUIESCE_EVENT
            && (event==PTRACE_EVENT_SECCOMP || WSTOPSIG(boundary.status)==(SIGTRAP|0x80));
        if (registry && !terminal && p->initial_stop)
            CHECK(!md_svc_machine_drain(registry,&p->machine,p->pid,
                p->phase==IDLE ? NULL : &p->original,syscall_stop));
        TRACE("PROBE entry-drain pid=%d boundary=%d status=%x phase=%d draining=%d resume=%d stopped=%d\n",
            p->pid,boundary.kind,boundary.status,p->phase,p->machine.draining,p->kernel_resume,p->kernel_stopped);
        if (statistics && p->machine.draining) ++statistics->entry_fence_drains;
        CHECK(!md_task_quiesce_return(entry_fence_barrier,p->pid));
    }
    CHECK(!md_task_quiesce_release(entry_fence_barrier));
    entry_fence_stage=2;
    task_action_unpark();
}
static pid_t entry_fence_take(int *status,struct rusage *usage) {
    if (!entry_fence_memory || ENTRY_QUIESCING) return 0;
    pid_t event=md_task_quiesce_take(entry_fence_barrier,status,usage);
    if (event) return event;
    if (entry_fence_owner) return 0;
    if (entry_fence_stage!=3 && !entry_fence_error) {
        if (!entry_fence_protected) {
            int64_t now=md_event_now();
            if (entry_fence_retry_at>now) return 0;
            entry_fence_retry_at=0;
            struct thread *reader=threads;
            while (reader && (!reader->pid || reader->memory!=entry_fence_memory || !reader->entry_fence_waiting))
                reader=reader->next;
            uint64_t slots=0;
            int error=reader ? md_svc_registry_occupied(md_memory_svc(entry_fence_memory),reader->pid,&slots) : 0;
            if (error) entry_fence_error=error;
            else if (slots) {
                if (statistics) statistics->entry_fence_drains+=(unsigned)__builtin_popcountll(slots&~entry_fence_slots);
                entry_fence_slots|=slots;
                /* EVENT_WAIT: revoked frame owners release their slots; no
                 * interrupt of native waits and no periodic warm-path work. */
                entry_fence_retry_at=now+1000000;
                return 0;
            }
        }
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory
                && !p->entry_fence_waiting && !p->terminal_stop && !p->vfork_child
                && !entry_fence_drained(p)) return 0;
        struct md_svc_registry *registry=md_memory_svc(entry_fence_memory);
        if (registry) registry->entry_fence_drained=1;
    }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory && p->entry_fence_waiting) {
        entry_fence_cancel(p);
        entry_fence_owner=p;
        p->entry_fence_result=entry_fence_error; *status=p->entry_fence_status;
        if (usage) *usage=p->entry_fence_usage;
        return p->pid;
    }
    if (entry_fence_stage!=3) {
        entry_fence_stage=3;
        task_action_unpark();
    }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->entry_fence_held) {
        p->entry_fence_held=0; *status=p->entry_fence_status;
        if (usage) *usage=p->entry_fence_usage;
        return p->pid;
    }
    md_task_quiesce_destroy(entry_fence_barrier); entry_fence_barrier=NULL;
    md_memory_release(entry_fence_memory); entry_fence_memory=NULL; entry_fence_stage=0; entry_fence_error=0;
    entry_fence_protected=0; entry_fence_retry_at=0;
    return 0;
}
static pid_t deferred_event(int *status,struct rusage *usage) {
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
static int publish_birth(struct thread *t,pid_t child,unsigned event) {
    pid_t pid=t->pid;
    if (event==PTRACE_EVENT_VFORK) {
        CHECK(!t->vfork_child);
        t->vfork_child=(pid_t)child;
    }
    struct thread *c = thread(child);
    CHECK(!c->born);
    c->born = 1; c->ready = t->ready;
    c->parent=t->cloning&(CLONE_PARENT|CLONE_THREAD) ? t->parent : t->tgid; c->entered=t->entered;
    c->process_image=t->process_image;
    c->executable=md_process_executable_retain(t->executable);
    c->executable_origin=md_fs_anchor_retain(t->executable_origin);
    /* A newborn has no syscall entry of its own. Adapter setup must
     * not publish the parent's pending clone exit as a child stop. */
    c->debug_call_active=0;
    c->debug_options=t->debug_options;
    c->terminal_observation=t->terminal_observation;
    c->pid_filter=t->pid_filter;
    c->application_filter=t->application_filter;
    c->cooperative_blocked=t->cooperative_blocked;
    c->registration_revoked=t->registration_revoked;
    md_identity_release(&c->identity);
    c->identity = md_identity_copy(&t->identity);
    c->tgid = t->cloning & CLONE_THREAD ? t->tgid : c->pid;
    CHECK(!md_shm_space_release(c->shm_space));
    c->shm_space = md_shm_space_fork(t->shm_space,!!(t->cloning & CLONE_VM)); CHECK(c->shm_space);
    CHECK(!md_resources_release(&c->resources));
    if (t->resources.fs) CHECK(!md_resources_fork(&t->resources, t->cloning, &c->resources));
    CHECK(t->process_reservation);
    c->resources.process=t->process_reservation; t->process_reservation=NULL;
    md_process_member_forked(t->resources.process);
    CHECK(!md_resources_unshare_finish(&c->resources,&c->identity,&t->resource_change,0));
    md_pid_identity_release(c->resources.pid);
    c->resources.pid=t->pid_reservation; t->pid_reservation=NULL;
    int published=md_pid_identity_publish(c->resources.pid,c->pid,c->tgid);
    if (published) errno=-published;
    CHECK(!published);
    c->pid_init_pending=md_pid_identity_number(c->resources.pid,md_pid_identity_namespace(c->resources.pid))==1;
    if (!(t->cloning&CLONE_THREAD)) {
        md_pid_identity_release(c->resources.pid_group);
        c->resources.pid_group=md_pid_identity_retain(c->resources.pid);
    }
    if (t->cloning&CLONE_NEWPID)
        TRACE("PROBE pid-namespace init=%d visible=1 depth=%u\n",c->pid,
            md_pid_namespace_depth(md_pid_identity_namespace(c->resources.pid)));
    t->pid_result=md_pid_identity_number(c->resources.pid,md_pid_identity_namespace(t->resources.pid));
    if (t->pid_parent_address && (t->cloning&CLONE_VM)) {
        CHECK(!t->pid_birth_child && !c->pid_birth_parent);
        pid_store_add(c,t->pid_parent_address,(pid_t)t->pid_result);
        t->pid_parent_address=0;
        t->pid_birth_child=c; c->pid_birth_parent=t;
    }
    if (c->resources.pid && (t->cloning&CLONE_CHILD_SETTID)) {
        uintptr_t address=t->original.regs[4];
        if (t->clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
            address=((struct clone_args *)span.data)->child_tid;
        }
        pid_store_add(c,address,md_pid_identity_number(c->resources.pid,md_pid_identity_namespace(c->resources.pid)));
    }
    md_fd_selector_release(c->fd_selector);
    c->fd_selector=t->fork_selector; t->fork_selector=NULL;
    CHECK(c->fd_selector);
    if (t->fd_running) fd_finish(t,(long)child);
    md_memory_release(c->memory);
    c->memory = md_memory_fork(t->memory, !!(t->cloning & CLONE_VM)); CHECK(c->memory);
    c->memory_access_pending=!(t->cloning&CLONE_VM);
    int untraced=!!(t->cloning&CLONE_UNTRACED);
    t->cloning = 0;
    memcpy(c->endpoint, t->endpoint, sizeof(c->endpoint));
    /* Birth publishes membership before the parent can exit or move.
     * A debugger-held newborn is live even before its first resume. */
    CHECK(!md_task_registry_publish(tasks,c->pid,c->tgid,&c->identity,&c->resources));
    if (cancelling) CHECK(!md_process_signal_children(cancelling == 1 ? SIGTERM : SIGKILL));
    /* EVENT_WAIT: a newborn stop and its parent's birth event can
     * arrive in either order. Both must precede the child's resume. */
    int debug_birth=untraced ? 0 : md_debugger_birth(debugger,pid,c->pid,event); CHECK(debug_birth>=0);
    c->debug_birth=debug_birth;
    if (c->terminal_stop) {
        if (!md_debugger_event(debugger,c->pid,PTRACE_EVENT_EXIT,c->close_message)) resume(c->pid,0);
    } else if (c->initial_stop) resume(c->pid,0);
    return debug_birth;
}
static pid_t kernel_event(int *status,struct rusage *usage) {
    int64_t start=md_cost_begin(statistics ? &statistics->event_collect : NULL);
    int accounting=md_debugger_active(debugger);
    /* Refresh the lossy TID hint before paying for a general kernel scan.
     * The periodic source budget still services all other event owners. */
    if (!task_wait.count && !task_wait.drained) drain();
    pid_t pid=md_task_wait_ready(&task_wait,status,accounting ? usage : NULL);
    /* Deferred events may outlive a debugger attachment. Absent accounting
     * must be explicit, not a previous event's or uninitialized stack data. */
    if (pid>0 && usage && !accounting) *usage=(struct rusage){0};
    md_cost_end(statistics ? &statistics->event_collect : NULL,start);
    return pid;
}
static int checkpoint_event(pid_t pid,int *status) {
    /* A shielded admission may stop at syscall exit rather than EVENT_STOP.
     * The checkpoint driver must not resume that stop behind an active barrier.
     * Keep raw events queued and normalize them through this same path on replay.
     * EVENT_STOP still normalizes a checkpoint-owned synthetic interrupt first. */
    if ((unsigned)*status>>16!=PTRACE_EVENT_STOP
            && ((ENTRY_QUIESCING && md_task_quiesce_waiting(entry_fence_barrier,pid))
                || (memory_execution && md_task_quiesce_waiting(memory_barrier,pid))
                || (mapping_execution && md_task_quiesce_waiting(mapping_barrier,pid))
                || (task_action_execution && md_task_quiesce_waiting(task_action_barrier,pid)))) return 0;
    return md_task_checkpoint_event(&checkpoints,pid,status);
}
static pid_t wait_event(int *status, struct rusage *usage) {
    enum { PROGRESS_BATCH=8 };
    static unsigned progress_budget;
    for (;;) {
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
            event_sources(events);
            if (statistics) ++statistics->event_polls;
            long ready = md_event_poll(events, EVENT_SOURCES);
            CHECK(ready >= 0);
            if (ready) progress_events(events);
            progress_budget=PROGRESS_BATCH;
        }
        --progress_budget;
        /* Progress can return a borrowed stop without generating SIGCHLD.
         * Drain that local event before trusting the kernel's empty queue. */
        pid_t pid=deferred_event(status,usage);
        if (!pid) pid=kernel_event(status,usage);
        if (pid > 0) {
            int checkpoint=checkpoint_event(pid,status);
            if (checkpoint==-ESRCH) continue;
            if (checkpoint<0) fprintf(stderr,"Shroot: checkpoint pid=%d status=%x error=%d\n",pid,*status,checkpoint);
            CHECK(checkpoint>=0);
            if (checkpoint) continue;
            int held=md_broker_hold_event(broker,pid,*status,usage); CHECK(held>=0);
            if (held) continue;
            return pid;
        }
        CHECK(pid == 0 || errno == EINTR);
        if (memory_runnable()) continue;
        /* EVENT_WAIT: task/resource progress, signal or cancellation timer.
         * The timer is serviced even while ptrace continuously has work. */
        int64_t bound = INT64_MAX;
        struct thread *control_owner=control_deadline_owner();
        int64_t control_bound=control_owner ? control_owner->control_pending->deadline : INT64_MAX;
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
                    TRACE("PROBE pending pid=%d phase=%d fdJob=%u fdRunning=%d files=%d fs=%d bufferWaiting=%d\n",
                        t->pid,t->phase,t->fd_job,t->fd_running,md_fd_order_state(&t->fd_order),
                        md_fs_order_state(&t->fs_order),t->buffer_waiting);
                md_fs_buffer_describe(buffer_service,STDERR_FILENO);
            }
            errno = (int)-outcome;
        }
        CHECK(outcome >= 0);
        progress_events(events);
        progress_budget=PROGRESS_BATCH;
    }
}
enum memory_stage { MEMORY_WRITERS, MEMORY_WORKER, MEMORY_VALIDATE, MEMORY_MAP, MEMORY_COPY,
    MEMORY_ADVICE, MEMORY_REPLACE };
struct memory_execution {
    struct thread *actor;
    enum memory_stage stage;
    int64_t deadline, timing[7];
    struct user_pt_regs returned;
    int status;
    struct rusage usage;
    struct md_private_memory mapping;
    struct md_private_memory_plan plan;
    struct md_private_memory_scan *scan;
    size_t copied;
    struct md_interception_buffer buffer;
    struct md_fs_worker_pause pause;
    struct md_memory_replace replacement;
    struct md_memory_replace_call call;
    struct md_task_helper_result result;
    uint64_t mask;
    unsigned advice;
    int complete, calling, shielded, prior_shielded, global;
    long error;
    unsigned other_events;
    unsigned progress_calls;
    int64_t last_progress;
    char scratch[];
};
static int64_t memory_deadline(void) {
    return memory_execution ? memory_execution->deadline : INT64_MAX;
}
static void memory_discard(void) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return;
    /* No helper or replacement may still be executing: either teardown reaped
     * the tree, or an unmodified repair yielded before MEMORY_MAP. */
    md_buffer_release(&execution->buffer);
    md_private_memory_scan_end(execution->scan);
    if (execution->pause.owner) md_fs_worker_pause_end(&execution->pause);
    memory_execution=NULL;
    free(execution);
}
static int memory_available(pid_t pid) {
    return (!entry_fence_memory || !entry_fence_park(find_thread(pid)))
        && (!ENTRY_QUIESCING || !md_task_quiesce_contains(entry_fence_barrier,pid))
        && (!memory_execution || !md_task_quiesce_contains(memory_barrier,pid))
        && (!mapping_execution || !md_task_quiesce_contains(mapping_barrier,pid))
        && (!task_action_execution || !md_task_quiesce_contains(task_action_barrier,pid));
}
static int memory_runnable(void) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return 0;
    switch (execution->stage) {
    case MEMORY_WRITERS: return md_task_quiesce_ready(memory_barrier);
    case MEMORY_WORKER: return !execution->pause.owner || md_fs_worker_pause_state(&execution->pause)!=0;
    case MEMORY_VALIDATE: case MEMORY_COPY: return 1;
    case MEMORY_MAP: return execution->complete;
    case MEMORY_ADVICE: case MEMORY_REPLACE: return !execution->calling;
    }
    return 0;
}
static int memory_participant(void *context,pid_t pid,int stopped,int request) {
    return md_task_quiesce_add(context,pid,stopped,request);
}
static void memory_complete(void *context,struct md_task_helper_result result) {
    struct memory_execution *execution=context;
    CHECK(result.descriptor==-1 && !result.received);
    execution->result=result; execution->complete=1;
}
static void memory_call(struct memory_execution *execution,const struct md_memory_replace_call *call) {
    struct thread *t=execution->actor;
    execution->call=*call; execution->calling=1;
    struct user_pt_regs regs=md_task_registers_entry(abi.task_call,t->original.sp,execution->returned.pstate);
    regs.regs[8]=call->number; memcpy(regs.regs,call->args,sizeof(call->args));
    CHECK(set_registers(t->pid,&regs));
    CHECK(!native_trace(PTRACE_CONT,t->pid,NULL,NULL));
}
static void memory_finish(struct memory_execution *execution) {
    struct thread *t=execution->actor;
    if (execution->shielded) {
        CHECK(signal_mask(t,execution->mask,"memory-finish-mask"));
        t->signal_shielded=execution->prior_shielded;
    }
    struct user_pt_regs restore=execution->returned;
    restore.regs[0]=execution->error ? -EACCES : 0;
    CHECK(set_registers(t->pid,&restore));
    CHECK(!md_buffer_release(&execution->buffer));
    md_private_memory_scan_end(execution->scan);
    if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
    CHECK(!md_task_quiesce_release(memory_barrier));
    execution->timing[6]=md_event_now();
    TRACE("PROBE memory-repair pid=%d address=%#lx size=%zu pages=%zu result=%ld scope=%s otherEvents=%u\n",
        t->pid,(unsigned long)execution->mapping.address,execution->mapping.length,
        execution->mapping.copied_pages,execution->error,execution->global ? "launch" : "address-space",execution->other_events);
    if (diagnostics) {
        int64_t previous=execution->timing[0],cost[6];
        for (unsigned i=0;i<6;++i) {
            int64_t current=execution->timing[i+1] ? execution->timing[i+1] : previous;
            cost[i]=(current-previous)/1000; previous=current;
        }
        TRACE("PROBE memory-repair-cost pid=%d writersUs=%lld workerUs=%lld validateUs=%lld mapUs=%lld copyUs=%lld replaceUs=%lld totalUs=%lld\n",
            t->pid,(long long)cost[0],(long long)cost[1],(long long)cost[2],
            (long long)cost[3],(long long)cost[4],(long long)cost[5],
            (long long)(execution->timing[6]-execution->timing[0])/1000);
    }
    memory_execution=NULL;
    free(execution);
    task_action_unpark();
    if (t->memory_files.table) CHECK(!md_fd_order_complete(&t->memory_files));
    CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
    t->memory_arguments_only=0;
    t->phase=IDLE; resume(t->pid,0);
}
static int memory_yield_retirement(struct memory_execution *execution) {
    if (execution->stage>MEMORY_WORKER) return 0;
    struct task_action *retirement=loan_retirements;
    while (retirement && (!retirement->retirement.complete
            || !md_task_quiesce_contains(memory_barrier,retirement->retirement.task)))
        retirement=retirement->next;
    if (!retirement) return 0;
    /* A losing native birth must close its provisional FD before the broker
     * releases the caller's stop. Do not wait for that close behind our own
     * barrier. The original mprotect exit is replayed, not the syscall; its
     * mapping is validated afresh after the existing task-action owner drains. */
    struct thread *actor=execution->actor;
    TRACE("PROBE memory-repair-defer pid=%d retirement=%d\n",actor->pid,retirement->retirement.task);
    CHECK(!quiesce_event(memory_barrier,actor->pid,execution->status,&execution->usage));
    if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
    CHECK(!md_task_quiesce_release(memory_barrier));
    memory_discard();
    task_action_unpark();
    return 1;
}
static void memory_progress(void) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return;
    if (cancelling) exit(result);
    if (memory_yield_retirement(execution)) return;
    int64_t now=md_event_now();
    if (now>=execution->deadline) {
        fprintf(stderr,"Shroot: memory repair expired pid=%d stage=%d elapsedUs=%lld writersUs=%lld workerAck=%d otherEvents=%u\n",
            execution->actor->pid,execution->stage,(long long)(now-execution->timing[0])/1000,
            execution->timing[1] ? (long long)(execution->timing[1]-execution->timing[0])/1000 : -1LL,
            execution->pause.owner ? md_fs_worker_pause_state(&execution->pause) : -1,execution->other_events);
        fprintf(stderr,"Shroot: memory progress calls=%u idleUs=%lld workerAckUs=%lld\n",
            execution->progress_calls,(long long)(now-execution->last_progress)/1000,
            execution->pause.acknowledged_at ? (long long)(execution->pause.acknowledged_at-execution->timing[0])/1000 : -1LL);
        fprintf(stderr,"Shroot: memory workerUs=%lld validatedUs=%lld\n",
            execution->timing[2] ? (long long)(execution->timing[2]-execution->timing[0])/1000 : -1LL,
            execution->timing[3] ? (long long)(execution->timing[3]-execution->timing[0])/1000 : -1LL);
        md_task_quiesce_describe(memory_barrier,STDERR_FILENO);
        md_task_helpers_describe(helpers,execution->actor->pid,STDERR_FILENO);
        md_broker_describe(broker,STDERR_FILENO);
        errno=ETIMEDOUT; CHECK(0);
    }
    ++execution->progress_calls; execution->last_progress=now;
    struct thread *t=execution->actor;
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    if (execution->stage==MEMORY_WRITERS) {
        if (!md_task_quiesce_ready(memory_barrier)) return;
        execution->timing[1]=md_event_now();
        execution->stage=MEMORY_WORKER;
        if (filesystem) CHECK(!md_fs_worker_pause_begin(filesystem,&execution->pause));
    }
    if (execution->stage==MEMORY_WORKER) {
        if (execution->pause.owner) {
            int ready=md_fs_worker_pause_state(&execution->pause);
            CHECK(ready>=0);
            if (!ready) return;
        }
        execution->timing[2]=md_event_now();
        /* All participant notifications have been cancelled by their observed
         * ptrace stops. The acknowledgement drains any copy that raced that
         * cancellation; subsequent worker work can serve other address spaces. */
        if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
        execution->error=md_private_memory_scan_begin(&execution->mapping,access,&execution->scan);
        if (execution->error) { memory_finish(execution); return; }
        execution->stage=MEMORY_VALIDATE;
    }
    if (execution->stage==MEMORY_VALIDATE) {
        long ready=md_private_memory_scan_step(execution->scan,&execution->plan);
        if (!ready) return;
        execution->error=ready<0 ? ready : 0;
        md_private_memory_scan_end(execution->scan); execution->scan=NULL;
        execution->timing[3]=md_event_now();
        if (execution->error) { memory_finish(execution); return; }
        CHECK(save_signal_mask(t,&execution->mask,"memory-execution-mask"));
        execution->prior_shielded=t->signal_shielded;
        CHECK(shield(t)); execution->shielded=1;
        t->terminal_observation=1; t->trace_options_dirty=1;
        CHECK(!debugger_options(t->pid,t->debug_options));
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,
            .mapping={.source=execution->mapping.source,.size=execution->mapping.length,
                .offset=execution->mapping.offset,.protection=execution->mapping.protection,
                .flags=execution->plan.flags}};
        execution->stage=MEMORY_MAP;
        execution->error=md_task_helper_begin(helpers,t->pid,t->tgid,&execution->returned,&abi,
            t->memory,&execution->buffer,&request,memory_complete,execution);
        if (execution->error) memory_finish(execution);
        return;
    }
    if (execution->stage==MEMORY_MAP) {
        if (!execution->complete) return;
        execution->timing[4]=md_event_now();
        execution->error=execution->result.value<0 ? execution->result.value : 0;
        if (execution->error) { memory_finish(execution); return; }
        execution->mapping.candidate=(uintptr_t)execution->result.value;
        execution->stage=MEMORY_COPY;
    }
    if (execution->stage==MEMORY_COPY) {
        long ready=md_private_memory_copy_step(&execution->mapping,access,&execution->plan,
            &execution->copied,execution->scratch,MD_PRIVATE_MEMORY_COPY_BYTES);
        if (!ready) return;
        execution->error=ready<0 ? ready : 0;
        execution->timing[5]=md_event_now();
        CHECK(!md_memory_replace_begin(&execution->replacement,execution->mapping.address,
            execution->mapping.candidate,execution->mapping.length,md_page_size));
        execution->stage=MEMORY_ADVICE;
    }
    if (execution->calling) return;
    if (execution->stage==MEMORY_ADVICE) {
        int advice=execution->error ? -1 : md_private_memory_advice(&execution->plan,execution->advice++);
        if (advice>=0) {
            struct md_memory_replace_call call={SYS_madvise,
                {execution->mapping.candidate,execution->mapping.length,(unsigned long)advice}};
            memory_call(execution,&call); return;
        }
        if (execution->error) md_memory_replace_cancel(&execution->replacement);
        execution->stage=MEMORY_REPLACE;
    }
    if (execution->stage==MEMORY_REPLACE) {
        struct md_memory_replace_call call;
        int next=md_memory_replace_next(&execution->replacement,&call);
        CHECK(next>=0);
        if (next) { memory_call(execution,&call); return; }
        long replaced=md_memory_replace_result(&execution->replacement);
        if (!execution->error) execution->error=replaced;
        memory_finish(execution);
    }
}
static int quiescence_repair_event(pid_t pid,int status,const struct rusage *usage) {
    if (!quiescence_busy(NULL) || !WIFSTOPPED(status) || (unsigned)status>>16
            || WSTOPSIG(status)!=(SIGTRAP|0x80)) return 0;
    struct thread *t=find_thread(pid);
    if (!t || t->phase!=OBSERVING || t->memory_action!=MEMORY_KERNEL
            || t->original.regs[8]!=SYS_mprotect) return 0;
    if ((memory_execution && memory_execution->actor==t)
            || (task_action_execution && task_action_execution->actor==t)
            || (mapping_execution && mapping_execution->caller==pid)) return 0;
    struct md_task_quiesce **barrier=ENTRY_QUIESCING ? &entry_fence_barrier
        : memory_execution ? &memory_barrier : mapping_execution ? &mapping_barrier
        : task_action_execution ? &task_action_barrier : NULL;
    if (!barrier) return 0;
    if (!*barrier) { *barrier=md_task_quiesce_new(quiesce_trace); CHECK(*barrier); }
    /* EVENT_WAIT: replay this exact mprotect exit after the active transaction
     * releases its stops. Only competing repair is deferred, not the process's
     * whole address space. Never start a second owner behind another barrier. */
    CHECK(!quiesce_event(*barrier,pid,status,usage));
    return 1;
}
/* Repair consumes only its participants' events; helper and replacement
 * continuations return to the same outer event loop as ordinary guest work. */
static int memory_event(pid_t pid,int status,const struct rusage *usage) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return 0;
    if (execution->stage==MEMORY_MAP && md_task_helper_owns(helpers,execution->actor->pid,pid)) {
        CHECK(md_task_helper_event(helpers,pid,status)==1);
        return 1;
    }
    if (execution->calling && pid==execution->actor->pid) {
        CHECK(WIFSTOPPED(status));
        unsigned event=(unsigned)status>>16;
        if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL)); return 1;
        }
        struct user_pt_regs regs;
        CHECK(registers(pid,&regs));
        if (event==PTRACE_EVENT_SECCOMP) {
            unsigned long cookie;
            CHECK(!native_trace(PTRACE_GETEVENTMSG,pid,NULL,&cookie));
            CHECK(cookie==MD_INTERCEPT_TASK && regs.pc==abi.task_return
                && regs.regs[8]==(unsigned long)execution->call.number);
            for (unsigned i=0;i<6;++i) CHECK(regs.regs[i]==execution->call.args[i]);
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL));
        } else {
            CHECK(event==MD_TASK_CHECKPOINT_EVENT && regs.pc==abi.task_completed+4);
            execution->calling=0;
            if (execution->stage==MEMORY_ADVICE) execution->error=(long)regs.regs[0];
            else CHECK(!md_memory_replace_observed(&execution->replacement,(long)regs.regs[0]));
        }
        return 1;
    }
    if (!md_task_quiesce_contains(memory_barrier,pid)) {
        struct md_interception_memory *helper=md_task_helper_memory(helpers,pid);
        if (helper && !execution->global && helper!=execution->actor->memory) { ++execution->other_events; return 0; }
        struct thread *t=find_thread(pid);
        int hold=execution->global || !t || !t->born || t->memory==execution->actor->memory
            || t->remote_memory==execution->actor->memory;
        if (!hold && WIFSTOPPED(status)) {
            unsigned event=(unsigned)status>>16;
            if (event==PTRACE_EVENT_SECCOMP || event==MD_TASK_CHECKPOINT_EVENT) {
                struct user_pt_regs regs;
                CHECK(registers(pid,&regs));
                pid_t target=0;
                if (event==PTRACE_EVENT_SECCOMP) {
                    if (regs.regs[8]==SYS_ptrace) target=native_pid(t,(pid_t)regs.regs[1]);
                    else if (regs.regs[8]==SYS_process_vm_readv || regs.regs[8]==SYS_process_vm_writev)
                        target=native_pid(t,(pid_t)regs.regs[0]);
                } else if (regs.pc==abi.buffer_control+4 && regs.regs[0]==MD_BUFFER_MEMORY_OPEN)
                    target=(pid_t)regs.regs[1];
                struct thread *remote=find_thread(target);
                hold=remote && remote->memory==execution->actor->memory;
            }
        }
        if (!hold) { ++execution->other_events; return 0; }
        CHECK(!md_task_quiesce_add(memory_barrier,pid,1,0));
    }
    CHECK(!quiesce_event(memory_barrier,pid,status,usage));
    return 1;
}
static int memory_repair(struct thread *t,const struct user_pt_regs *returned,int status,const struct rusage *usage) {
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    if (!access) return 0;
    struct md_private_memory mapping={.address=t->original.regs[0],.length=t->original.regs[1],
        .page_size=md_page_size,.protection=(int)t->original.regs[2],.source=-1};
    if (mapping.length>SIZE_MAX-(md_page_size-1)) return 0;
    mapping.length=(mapping.length+md_page_size-1)&~(md_page_size-1);
    if (!md_memory_file_view(t->memory,mapping.address,mapping.length,&mapping.source,&mapping.offset)) return 0;
    if (md_private_memory_validate(&mapping)) return 0;
    CHECK(!quiescence_busy(NULL));
    struct memory_execution *execution=calloc(1,sizeof(*execution)+MD_PRIVATE_MEMORY_COPY_BYTES);
    if (!execution) return 0;
    long error=md_buffers_take(md_memory_buffers(t->memory),&execution->buffer);
    if (error) { free(execution); return 0; }
    if (!memory_barrier) { memory_barrier=md_task_quiesce_new(quiesce_trace); CHECK(memory_barrier); }
    if (statistics) ++statistics->memory_repairs;
    execution->actor=t; execution->returned=*returned; execution->mapping=mapping;
    execution->status=status; execution->usage=*usage;
    execution->global=md_memory_exposed(t->memory);
    execution->timing[0]=md_event_now();
    /* EVENT_WAIT: writer, worker and helper acknowledgements share one failure
     * deadline. Expiry terminates the tree, never resumes uncertain mappings. */
    execution->deadline=execution->timing[0]+5000000000LL;
    memory_execution=execution;
    for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid
            && (execution->global || peer->memory==t->memory || peer->remote_memory==t->memory))
        CHECK(!md_task_quiesce_add(memory_barrier,peer->pid,
            peer==t || peer->kernel_stopped || peer->listening || peer->vfork_child,
            peer->interrupt_pending || birth_stop_pending(peer) ? 0 : peer->kernel_resume));
    CHECK(!md_task_helpers_visit(helpers,execution->global ? NULL : t->memory,memory_participant,memory_barrier));
    CHECK(!md_task_quiesce_stop(memory_barrier));
    return 1;
}
int main(int argc, char **argv) {
    CHECK(argc >= 2 && md_launch_identity(getuid(), geteuid(), getgid(), getegid()));
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    CHECK(md_page_size >= 4096 && md_page_size <= 65536 && !(md_page_size & (md_page_size - 1)));
    int argument = 1;
    int command_access = 0, command_owner = -1;
    char command_directory[PATH_MAX];
    long seconds = 0;
    while (argument < argc) {
        if (!strcmp(argv[argument], "--diagnostics")) diagnostics = 1;
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
    setvbuf(stderr, NULL, _IONBF, 0);
    struct md_debugger_host debug_host={.parent=debugger_parent,.group=debugger_group,.uid=debugger_uid,.allowed=debugger_allowed,
        .visible_pid=debugger_visible_pid,.native_pid=debugger_native_pid,.visible_uid=debugger_visible_uid,.reaped=debugger_reaped,
        .attachable=debugger_attachable,.observe=debugger_observe,.interrupt=debugger_interrupt,.options=debugger_options,
        .listen=debugger_listen,.usage=debugger_usage,.wait=debugger_wait,.wait_wake=debugger_wait_wake,
        .complete=debugger_complete,.resume=resume_signal};
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
            || !strcmp(argv[argument], "--bind-create"))) {
        CHECK(argument+2 < argc && attachment_count < MD_FS_MOUNTS_MAX);
        attachments[attachment_count++] = (struct md_fs_attachment){
            .source=argv[argument+1], .target=argv[argument+2],
            .flags=!strcmp(argv[argument], "--bind-ro") ? MD_FS_ATTACHMENT_READONLY
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
            struct md_filesystem view = {.store=accounts};
            CHECK(!md_fs_mounts_open(&view, attachments, attachment_count));
            int resolved = md_guest_user_resolve(&view, selected_user, &launch_identity);
            md_fs_mounts_close(&view); md_inode_store_close(accounts);
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
    thread(leader)->born = 1;
    /* A caller can install filters before starting Shroot. Treat inherited
     * no_new_privs conservatively: its ERRNO rules can outrank service TRACE
     * markers, so use the same pre-filter protocol as guest-installed filters. */
    thread(leader)->application_filter=!!inherited_no_new_privs;
    close(channel[1]);
    int keep[] = {signal_fd, channel[0], command_owner};
    CHECK(!md_process_close_fds(keep, command_owner >= 0 ? 3 : 2));
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
        md_broker_retirement_handler(broker,broker_retire);
        buffer_service=md_fs_buffer_service_new(filesystem,tasks,fd_wakeup,&task_wait,buffer_complete); CHECK(buffer_service);
        md_fs_buffer_admission(buffer_service,memory_available);
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
        struct md_fd_object *pin=md_fd_native_pin_new(fd);
        if (!pin) {
            TRACE("PROBE inherited-fd pin=%d error=%d\n",fd,errno);
            continue;
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
    while (live || md_fs_buffer_children(buffer_service) || md_task_helpers_count(helpers)) {
        pid_collect();
        int status; struct rusage usage; pid_t pid = wait_event(&status, &usage);
        struct thread *signalled=WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP
            ? find_thread(pid) : NULL;
        if (signalled && signalled->machine.signal_alias) {
            int error=md_svc_machine_signal_committed(md_memory_svc(signalled->memory),
                &signalled->machine,pid,status);
            if (error==-ESRCH) { signalled->resume_lost=1; continue; }
            CHECK(!error);
        }
        if (ENTRY_QUIESCING && md_task_quiesce_contains(entry_fence_barrier,pid)) {
            CHECK(!quiesce_event(entry_fence_barrier,pid,status,&usage));
            continue;
        }
        if (WIFSTOPPED(status) && (unsigned)status>>16!=PTRACE_EVENT_EXIT
                && (unsigned)status>>16!=PTRACE_EVENT_EXEC) {
            struct thread *peer=find_thread(pid);
            if (entry_fence_park(peer)) {
                CHECK(!peer->entry_fence_held);
                peer->entry_fence_held=1; peer->entry_fence_status=status; peer->entry_fence_usage=usage;
                peer->kernel_stopped=1;
                continue;
            }
        }
        if (quiescence_repair_event(pid,status,&usage)) continue;
        if (memory_event(pid,status,&usage)) continue;
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
        struct thread *t = thread(pid);
        int restored_signal=0;
        if (WIFSTOPPED(status) && !((unsigned)status>>16)) {
            int deliver=0;
            int owned=md_signal_delivery_event(&t->signal_delivery,pid,WSTOPSIG(status),&deliver);
            CHECK(owned>=0);
            if (owned==MD_SIGNAL_CARRIER) { resume(pid,deliver); continue; }
            restored_signal=owned==MD_SIGNAL_RESTORED;
        }
        if (completed_notification_event(t,status,&usage)) continue;
        if (metadata_restart_event(t,status)) continue;
        if (t->signal_shielded && WIFSTOPPED(status) && !((unsigned)status>>16)
                && WSTOPSIG(status)<32 && WSTOPSIG(status)!=SIGSTOP) {
            siginfo_t info;
            if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"shielded-signal")) continue;
            if (info.si_code<=0) {
                int retained=md_signal_delivery_retain(&t->signal_delivery,pid,&info); CHECK(retained>=0);
                notification_interrupted(t);
                resume(pid,retained); continue;
            }
        }
        /* This delivery already passed syscall recovery. Rechecking it on a
         * nondumpable task would defer the same signal for a code-read helper
         * indefinitely, even when no syscall needs replay. */
        if (!restored_signal && interrupted_metadata_event(t,status,&usage)) continue;
        t->kernel_stopped=1;
        t->interrupt_pending=0;
        if (md_debugger_active(debugger)) t->usage=usage;
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            if (pid == leader && !cancelling) result = code;
            if (code) TRACE("PROBE exit pid=%d status=%d ready=%d calls=%u\n", pid, code, t->ready, t->calls);
            int traced=md_debugger_traced(debugger,pid);
            if (t->born && (t->pid==t->tgid || traced)) {
                md_task_registry_retire(tasks,pid,&t->identity);
                pid_collection_pending=1;
            }
            if (t->resources.pid) {
                if (md_pid_identity_exit(t->resources.pid)) {
                    struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
                    for (struct thread *member=threads;member;member=member->next)
                        if (member->pid && member!=t && member->resources.pid
                                && md_pid_namespace_contains(space,md_pid_identity_namespace(member->resources.pid)))
                            syscall(SYS_tgkill,member->tgid,member->pid,SIGKILL);
                }
                md_pid_identity_release(t->resources.pid);
                pid_collection_pending=1;
                t->resources.pid=NULL;
            }
            md_debugger_exit(debugger,pid,status);
            release_thread(t); continue;
        }
        CHECK(WIFSTOPPED(status));
        if (statistics) statistics->stops++;
        unsigned event = (unsigned)status >> 16;
        if (lost_stop_event(t,event)) continue;
        int sig = WSTOPSIG(status);
        if (t->signal_return_pending && !event && sig==(SIGTRAP|0x80)) {
            struct ptrace_syscall_info info={0};
            if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"signal-return-entry")) continue;
            if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                t->signal_return_pending=0;
                CHECK(!md_event_signal(fd_ready));
            }
        }
        if (readiness_native_event(t,status)) continue;
        if (!event && sig!=(SIGTRAP|0x80) && t->phase==IDLE
                && md_memory_svc(t->memory)) {
            int deliver=0;
            int owned=md_svc_machine_signal(md_memory_svc(t->memory),&t->machine,pid,&t->signal_delivery,&deliver);
            if (owned==-ESRCH) { t->resume_lost=1; continue; }
            if (owned<0) errno=-owned;
            CHECK(owned>=0);
            if (owned) { deliver_signal(pid,deliver,restored_signal); continue; }
        }
        if (!event && sig!=(SIGTRAP|0x80)) notification_interrupted(t);
        if (event==PTRACE_EVENT_EXIT) {
            if (!t->born) {
                /* EVENT_WAIT: group death can overtake the newborn's initial
                 * stop. Retain its terminal stop until parent birth publishes
                 * ownership; cancellation still drains it through wait4. */
                t->initial_stop=1; t->terminal_stop=1;
                if (!tracee_request(t,PTRACE_GETEVENTMSG,NULL,&t->close_message,"newborn-exit")) continue;
                t->terminal_close_pending=1;
                continue;
            }
            if (t->fd_running && t->process_reservation && md_clone_call(t->original.regs[8])) {
                unsigned long argument=t->clone_prepared
                    ? md_memory_buffer_address(t->memory,&t->buffer)
                    : t->cloning&~((unsigned long)t->resource_change.namespaces|CLONE_UNTRACED);
                long child=md_task_birth_terminal(native_trace,pid,t->original.regs[8],argument);
                if (child==-ESRCH) { t->resume_lost=1; continue; }
                if (child<0) errno=(int)-child;
                CHECK(child>=0);
                if (child) {
                    unsigned signal=t->cloning&CSIGNAL;
                    if (t->clone_prepared) {
                        struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
                        signal=((struct clone_args *)span.data)->exit_signal;
                    }
                    unsigned birth=t->cloning&CLONE_VFORK ? PTRACE_EVENT_VFORK
                        : signal==SIGCHLD ? PTRACE_EVENT_FORK : PTRACE_EVENT_CLONE;
                    TRACE("PROBE unreported-birth parent=%d child=%ld event=%u\n",pid,child,birth);
                    t->resume_lost=1;
                    publish_birth(t,(pid_t)child,birth);
                }
            }
            struct md_svc_registry *machine=md_memory_svc(t->memory);
            if (machine) {
                int error=md_svc_machine_retire(machine,pid,t->phase==IDLE ? NULL : &t->original);
                if (error==-ESRCH) { t->resume_lost=1; continue; }
                if (error) errno=-error;
                CHECK(!error);
            }
            if (machine && statistics && t->pid==t->tgid) {
                struct md_svc_payload_state state;
                if (!md_svc_registry_read(pid,machine->frames+MD_SVC_POOL_SIZE,&state,sizeof(state)))
                    fprintf(stderr,"SVC_RUNTIME_STATS {\"pid\":%d,\"sites\":%u,\"completed\":%llu,"
                        "\"declined\":%llu,\"unknown\":%llu,\"retiredClients\":%llu,\"revoked\":%u}\n",
                        pid,machine->count,(unsigned long long)state.completed,
                        (unsigned long long)state.declined,(unsigned long long)state.unknown,
                        (unsigned long long)state.retired_clients,machine->disabled);
            }
            /* ESRCH invalidates the preceding stop, not this newly observed
             * terminal stop. Registers may be inspected here, never fabricated. */
            int resume_lost=t->resume_lost;
            t->resume_lost=0; t->terminal_stop=1;
            if (machine) machine->clients_dirty=1;
            /* A live mm participant collects retired slots on its next
             * resume. The exiting task is no longer a reliable memory reader. */
            if (t->fd_running && t->fd_capture) {
                struct user_pt_regs terminal;
                if (!registers(pid,&terminal)) continue;
                long publication;
                if (terminal.pc==abi.capture_gate && terminal.regs[8]==SYS_openat
                        && md_fd_mutation_terminal_open(&t->fd_mutation,terminal.regs[0],&publication))
                    fd_finish(t,publication);
            }
            fd_discard(t);
            adapter_discard(t);
            CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ECANCELED));
            unsigned long message;
            if (!tracee_request(t,PTRACE_GETEVENTMSG,NULL,&message,"debug-exit")) continue;
            TRACE("PROBE terminal-stop pid=%d lost=%d status=%#lx\n",pid,resume_lost,message);
            if (diagnostics && (message&0x7f)==SIGSYS) {
                struct user_pt_regs terminal;
                if (registers(pid,&terminal))
                    TRACE("PROBE filter-death pid=%d nr=%lu pc=%lx arg0=%lx adapted=%lu\n",
                        pid,(unsigned long)terminal.regs[8],(unsigned long)terminal.pc,
                        (unsigned long)terminal.regs[0],(unsigned long)t->original.regs[8]);
            }
            /* Linux exposes the exit stop before retiring files. The virtual
             * debugger may service FUSE itself, so FLUSH starts only after its
             * continuation, never before delivery of PTRACE_EVENT_EXIT. */
            t->terminal_close_pending=1; t->close_message=message;
            if (!md_debugger_event(debugger,pid,event,message)) resume(pid,0);
        } else if (event==PTRACE_EVENT_VFORK_DONE) {
            unsigned long child;
            if (!tracee_request(t,PTRACE_GETEVENTMSG,NULL,&child,"vfork-complete")) continue;
            CHECK(t->vfork_child==(pid_t)child);
            t->vfork_child=0;
            if (!md_debugger_event(debugger,pid,event,child)) resume(pid,0);
        } else if (event == PTRACE_EVENT_EXEC) {
            md_svc_machine_forget(&t->machine);
            /* A non-leader exec replaces the task behind the leader's PID. */
            md_broker_forget(broker, pid);
            cooperative_buffer_release(t);
            completed_event_discard(t);
            unsigned long old; CHECK(!native_trace(PTRACE_GETEVENTMSG, pid, NULL, &old));
            t->debug_exec_old=(pid_t)old;
            struct thread *executing=old && old!=(unsigned)pid ? find_thread((pid_t)old) : t;
            struct md_fd_closed exec_closed={0};
            if (executing) {
                if (executing->fd_running) fd_finish(executing,0);
                else CHECK(!md_resources_exec_closed(&executing->resources,&executing->closed));
                exec_closed=executing->closed; executing->closed=(struct md_fd_closed){0};
            }
            if (old && old != (unsigned)pid) {
                md_debugger_rekey(debugger,(pid_t)old,pid);
                struct thread *former = find_thread((pid_t)old);
                if (former) {
                    md_signal_delivery_clear(&t->signal_delivery);
                    t->signal_delivery=former->signal_delivery;
                    former->signal_delivery=(struct md_signal_delivery){0};
                    t->terminal_observation=former->terminal_observation;
                    t->debug_options=former->debug_options;
                    t->debug_call_active=former->debug_call_active;
                    t->debug_step_call=former->debug_step_call;
                    t->trace_options_dirty=former->trace_options_dirty;
                    t->pid_filter=former->pid_filter;
                    t->application_filter=former->application_filter;
                    t->cooperative_blocked=former->cooperative_blocked;
                    release_operation(t);
                    CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change, -ECANCELED));
                    CHECK(!md_resources_release(&t->resources));
                    t->resources = former->resources;
                    former->resources = (struct md_task_resources){0};
                    md_pid_identity_exec(t->resources.pid,t->resources.pid_group);
                    md_task_registry_reap(tasks,pid);
                    md_pid_identity_release(t->resources.pid);
                    t->resources.pid=md_pid_identity_retain(t->resources.pid_group);
                    exec_images_release(t);
                    memcpy(t->exec_images,former->exec_images,sizeof(t->exec_images));
                    t->execfd_origin=former->execfd_origin; former->execfd_origin=NULL;
                    t->prepared_executable=former->prepared_executable; former->prepared_executable=NULL;
                    t->prepared_executable_origin=former->prepared_executable_origin;
                    former->prepared_executable_origin=NULL;
                    t->exec_mount_flags=former->exec_mount_flags;
                    t->exec_setid=former->exec_setid; former->exec_setid=NULL;
                    former->exec_images[0]=former->exec_images[1]=former->exec_images[2]=-1;
                    md_identity_release(&t->identity);
                    t->identity = md_identity_copy(&former->identity);
                    md_fd_selector_release(t->fd_selector);
                    t->fd_selector = former->fd_selector; former->fd_selector = NULL;
                    memcpy(t->endpoint, former->endpoint, sizeof(t->endpoint));
                    release_thread(former);
                }
            }
            fd_discard(t);
            adapter_discard(t);
            exec_images_activate(t);
            md_memory_stack_return(t->memory, t->stack);
            CHECK(!md_shm_space_release(t->shm_space));
            t->shm_space = md_shm_space_new(); CHECK(t->shm_space);
            ipc_unwatch(t);
            CHECK(!md_ipc_cancel(md_ipc_namespace_active(t->resources.ipc),&t->ipc_pending)); t->ipc_cancel=t->ipc_waking=0;
            md_ipc_transport_close(&t->ipc_transport);
            struct md_interception_memory *memory=md_memory_new_image(t->memory); CHECK(memory);
            md_memory_release(t->memory); t->memory=memory;
            t->memory_access_pending=0;
            t->phase = IDLE; t->stack = 0; t->ready = 0; t->resume_lost = 0; t->terminal_stop=0; images++;
            t->mapped_image = 0; t->entered = 0; t->image_backings_ready=0;
            t->registration_revoked=0;
            t->pid_preflight=t->filter_preflight=0;
            t->process_image=0;
            /* Exec retires the old syscall instruction. Its completion belongs
             * to the executing thread, not a replaced leader's interrupted call. */
            t->debug_replay_entry=0;
            t->debug_adapter=t->debug_call_active;
            CHECK(!t->closed.first); t->closed=exec_closed;
            close_event_begin(t,event,old); adapter_progress();
        } else if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
            int terminal=0;
            long child=md_task_birth_read(native_trace,pid,event,t->original.regs[8],&terminal);
            if (child==-ESRCH) { t->resume_lost=1; continue; }
            if (child<0) errno=(int)-child;
            CHECK(child>0);
            if (terminal) {
                TRACE("PROBE terminal-birth parent=%d child=%ld event=%u\n",pid,child,event);
                t->resume_lost=1;
            }
            if (!publish_birth(t,(pid_t)child,event)) resume(pid,0);
        } else if (event == PTRACE_EVENT_STOP) {
            if (sig == SIGSTOP || sig == SIGTSTP || sig == SIGTTIN || sig == SIGTTOU) {
                if (debugger_at_boundary(t) && md_debugger_stop(debugger,pid,status,NULL,0)) continue;
                /* EVENT_WAIT: preserve the kernel group-stop until SIGCONT or
                 * exit. CONT here would silently discard the application's stop.
                 * Keep the operation phase intact across the listening state. */
                t->listening = 1; group_stops++;
                if (native_trace(PTRACE_LISTEN, pid, NULL, NULL)) {
                    CHECK(errno == ESRCH); t->resume_lost = 1;
                } else t->kernel_stopped=0;
                continue;
            }
            int wake = t->listening;
            if (wake) { CHECK(sig == SIGTRAP); t->listening = 0; group_wakes++; }
            if (interrupted_operation(t)) continue;
            if (wake) { resume(pid, 0); continue; }
            if (t->initial_stop && debugger_at_boundary(t) && t->entered && debugger_boundary(t)) {
                t->initial_stop=1; continue;
            }
            CHECK(sig == SIGTRAP);
            if (t->initial_stop) {
                /* SIGCONT can clear a pending group-stop before this tracee
                 * reports it. The kernel then reports EVENT_STOP/SIGTRAP without
                 * an earlier LISTEN. This is not a second newborn stop. */
                CHECK(t->born); raced_wakes++; resume(pid, 0); continue;
            }
            t->initial_stop = 1;
            if (t->born) resume(pid,0);
        }
        else if (event == PTRACE_EVENT_SECCOMP) {
            struct user_pt_regs regs;
            unsigned long cookie;
            struct ptrace_syscall_info info={0};
            const struct ptrace_syscall_info *observed=NULL;
            if (t->phase==IDLE && t->ready) {
                if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"seccomp-call")) continue;
                if (info.op==PTRACE_SYSCALL_INFO_SECCOMP) {
                    cookie=info.seccomp.ret_data; observed=&info;
                    if (memory_observed(t,&info)) continue;
                }
            }
            if (!observed && !tracee_request(t,PTRACE_GETEVENTMSG,NULL,&cookie,"seccomp-event")) continue;
            struct md_svc_registry *registry=md_memory_svc(t->memory);
            /* Discovery and resident recovery need full context. Ordinary IO
             * outside those regions can use the existing syscall observation,
             * including its original pointers and native wait semantics. */
            int captured=t->phase==IDLE && registry && (!observed
                || md_entry_supported((int)observed->seccomp.nr)
                || md_svc_registry_context(registry,observed->instruction_pointer,observed->stack_pointer));
            if (captured) {
                if (!registers(pid,&regs)) continue;
                int native_entry=0;
                if (!t->application_filter && !t->cooperative_blocked
                        && !t->debug_options && !md_memory_exposed(t->memory))
                    native_entry=md_svc_machine_native_entry(registry,&regs);
                int owned=native_entry ? 0 : md_svc_machine_syscall(md_memory_svc(t->memory),&t->machine,pid,&regs);
                if (owned==-ESRCH) { t->resume_lost=1; continue; }
                if (owned<0) errno=-owned;
                CHECK(owned>=0);
                if (owned) { resume(pid,0); continue; }
                /* Observe the executed site before compact Base handlers can
                 * consume it. Reuse this snapshot for ordinary dispatch too. */
                struct user_pt_regs discovery=regs;
                if (native_entry) discovery.pc=md_svc_registry_call_origin(registry,regs.pc);
                int published=native_entry==1 ? 0 : machine_observe(t,&discovery);
                if (!native_entry && published && !t->entry_fence_result) {
                    struct user_pt_regs entry=regs; entry.pc-=4;
                    int redirected=md_svc_machine_redirect(&t->machine,pid,&entry);
                    if (redirected==-ESRCH) { t->resume_lost=1; continue; }
                    if (redirected<0) errno=-redirected;
                    CHECK(redirected>0);
                    resume(pid,0); continue;
                }
            }
            if (t->phase==WAIT_CANCEL_EXIT) {
                if (skip(pid)) resume(pid,0);
                continue;
            }
            if (entry_fence_adapt(t)) {
                if (!captured && !registers(pid,&regs)) continue;
                captured=1;
                if (!(regs.pc>=checkpoints.begin && regs.pc<checkpoints.end)) {
                    CHECK(!t->entry_fence_held);
                    t->entry_fence_held=1; t->entry_fence_status=status; t->entry_fence_usage=usage;
                    continue;
                }
            }
            if (observed && t->resources.files && fd_native_io(t,observed)) continue;
            if (fd_close_compact(t,cookie,observed)) continue;
            if (cookie&MD_INTERCEPT_CLOSE) cookie=MD_INTERCEPT_FD;
            if (!captured && !registers(pid, &regs)) continue;
            if (t->entry_fence_result) {
                int result=t->entry_fence_result; t->entry_fence_result=0;
                return_value(t,&regs,result); continue;
            }
            if (cookie==MD_INTERCEPT_TASK) { return_value(t,&regs,-EPERM); continue; }
            if (cookie==MD_INTERCEPT_OBSERVE && entry_fence_admission(t,&regs,status,&usage)) continue;
            if (regs.regs[8]==SYS_ptrace && t->phase==IDLE)
                TRACE("PROBE guest debugger caller=%d request=%llu target=%llu address=%#llx data=%#llx traced=%d\n",
                    pid,regs.regs[0],regs.regs[1],regs.regs[2],regs.regs[3],
                    md_debugger_traced(debugger,native_pid(t,(pid_t)regs.regs[1])));
            if (t->phase==IDLE && (regs.regs[8]==SYS_ptrace || regs.regs[8]==SYS_wait4)
                    && md_debugger_call(debugger,pid,&regs)) continue;
            if (statistics) {
                if (regs.regs[8] < 512) statistics->calls[regs.regs[8]].trace++;
                else statistics->unknown++;
            }
            if (regs.regs[8]==SYS_prctl && regs.regs[0]==MD_GUEST_PID_FILTER
                    && regs.pc==RAW_GATE && t->phase==DISPATCHING) {
                long value=regs.regs[1]==1 ? 0 : -EINVAL;
                if (!value) for (struct thread *member=threads;member;member=member->next)
                    if (member->pid && member->tgid==t->tgid) member->pid_filter=1;
                return_value(t,&regs,value); continue;
            }
            if (regs.regs[8]==SYS_prctl && regs.regs[0]==MD_GUEST_PID_SIGNAL_GROUP
                    && regs.pc==RAW_GATE && t->phase==DISPATCHING) {
                long value=t->original.regs[8]!=SYS_kill || (pid_t)t->original.regs[0]>0
                    || (pid_t)regs.regs[1]!=(pid_t)t->original.regs[0]
                    || (int)regs.regs[2]!=(int)t->original.regs[1] ? -EINVAL
                    : signal_process_next(t,(pid_t)regs.regs[1],(int)regs.regs[2],(pid_t)regs.regs[3]);
                return_value(t,&regs,value); continue;
            }
            /* Owned limits translate their target themselves. A newer PID
             * selector must not replay them against Android's shared UID. */
            if (process_limit_call(t,&regs)) continue;
            if (process_call(t,&regs,&cookie)) continue;
            if (identity_call(t, &regs, cookie)) continue;
            if (domain_call(t, &regs, cookie)) continue;
            if (cookie==MD_INTERCEPT_FD || cookie==MD_INTERCEPT_CWD || cookie==MD_INTERCEPT_CAPTURE) {
                if (cookie==MD_INTERCEPT_FD && md_clone_call(regs.regs[8])
                        && (md_shm_space_used(t->shm_space) || md_memory_has_policy(t->memory))
                        && !(t->cloning&CLONE_VM)) {
                    memory_begin(t,&regs,MEMORY_FORK,0); continue;
                }
                int delivery=cookie==MD_INTERCEPT_CAPTURE && (int)regs.regs[4]==MD_FS_BUFFER_SOURCE;
                int capture_flags=delivery ? t->buffer_open.fd>=0
                    && regs.regs[2]==(unsigned long)(t->buffer_open.completion.kind==MD_OPEN_CALLER
                        ? md_open_endpoint_flags((int)t->buffer_open.flags)
                        : O_PATH|(t->buffer_open.flags&(O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW)))
                    && regs.regs[1]==md_memory_buffer_address(t->memory,&t->buffer)+offsetof(struct md_fs_buffer_frame,source)
                    : (regs.regs[2]==(O_PATH|O_CLOEXEC) || regs.regs[2]==(O_PATH|O_DIRECTORY|O_CLOEXEC))
                        && ((int)regs.regs[4]>=0 || (int)regs.regs[4]==AT_FDCWD);
                if (cookie==MD_INTERCEPT_CAPTURE
                        && (regs.pc!=abi.capture_gate || regs.regs[8]!=SYS_openat
                            || (int)regs.regs[0]!=AT_FDCWD || regs.regs[3]
                            || !capture_flags)) {
                    return_value(t,&regs,-EINVAL); continue;
                }
                fd_begin(t,&regs,(unsigned)cookie); continue;
            }
            if (cookie == MD_INTERCEPT_MEMORY) {
                CHECK(t->phase == IDLE);
                memory_begin(t,&regs,MEMORY_KERNEL,0); continue;
            }
            if (descriptor_call(t, &regs, cookie)) continue;
            /* Stacked selectors survive close/reuse and fork/exec. The newest
             * filter's cookie is not the type of the current description. */
            if (cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_WATCH) {
                proc_lookup_begin(t,&regs); continue;
            }
            if (cookie == MD_INTERCEPT_NATIVE) {
                if (t->phase==IDLE && md_debugger_step(debugger,pid)) {
                    t->debug_step_call=1; t->original=regs; t->phase=OBSERVING;
                }
                if (diagnostics && statistics && t->phase == IDLE && regs.regs[8] < 512
                        && statistics->calls[regs.regs[8]].trace <= 4) {
                    TRACE("PROBE native arguments pid=%d nr=%llu args=%#llx,%#llx,%#llx,%#llx,%#llx,%#llx\n",
                        pid,regs.regs[8],regs.regs[0],regs.regs[1],regs.regs[2],regs.regs[3],regs.regs[4],regs.regs[5]);
                    t->original=regs; t->phase=OBSERVING;
                }
                resume(pid, 0); continue;
            }
            if (cookie == MD_INTERCEPT_EXEC) {
                CHECK(t->phase == DISPATCHING);
                if (!restore_mask(t)) continue;
                t->phase = EXECUTING; fd_begin(t,&regs,0); continue;
            }
            if (cookie == MD_INTERCEPT_OBSERVE) {
                CHECK(t->ready && t->phase == IDLE);
                t->filter_length = describe_filter(pid, &regs);
                t->original = regs; t->phase = OBSERVING;
                resume(pid, 0); continue;
            }
            if (cookie==MD_INTERCEPT_READINESS) cookie=MD_INTERCEPT_DISPATCH;
            if (cookie != MD_INTERCEPT_DISPATCH || !t->ready || t->phase != IDLE) {
                int displaced=md_task_stop_displaced(checkpoints.trace,pid);
                CHECK(displaced>=0);
                if (displaced) { t->resume_lost=1; continue; }
                unsigned long raw_cookie=0;
                struct ptrace_syscall_info raw_info={0};
                long raw_event=ptrace(PTRACE_GETEVENTMSG,pid,NULL,&raw_cookie);
                long raw_call=ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(raw_info),&raw_info);
                fprintf(stderr,"guest-runtime: unexpected TRACE pid=%d cookie=%lu ready=%d phase=%d pc=%#llx nr=%llu adapted=%llu rawEvent=%ld/%lu rawCall=%ld/%u/%u/%llu pending=%u\n",
                    pid, cookie, t->ready, t->phase, regs.pc, regs.regs[8], t->original.regs[8],
                    raw_event,raw_cookie,raw_call,raw_info.op,raw_info.seccomp.ret_data,
                    (unsigned long long)raw_info.seccomp.nr,task_wait.pending_count);
            }
            CHECK(cookie == MD_INTERCEPT_DISPATCH && t->ready && t->phase == IDLE);
            t->original = regs; calls++; t->calls++;
            t->debug_step_call=md_debugger_step(debugger,pid);
            if (regs.regs[8]==SYS_shmdt) { memory_begin(t,&regs,MEMORY_DETACH,0); continue; }
            if (direct_begin(t)) continue;
            if (receive_entry(t)) continue;
            if (!save_signal_mask(t,&t->mask,"get-mask")
                    || !shield(t) || !skip(pid)) continue;
            dispatch_with_stack(t);
        } else if (!event && sig == (SIGTRAP | 0x80)) {
            if (t->phase==EPOLL_PROBE) {
                struct user_pt_regs regs;
                if (!registers(pid,&regs)) continue;
                long value=(long)regs.regs[0];
                regs.regs[3]=t->original.regs[3];
                t->phase=IDLE;
                if (value) {
                    t->epoll_started=0;
                    if (set_registers(pid,&regs)) resume(pid,0);
                } else if (save_signal_mask(t,&t->mask,"epoll-probe-mask") && shield(t))
                    dispatch_with_stack(t);
                continue;
            }
            if (t->phase==CLONE_ENTRY) {
                struct ptrace_syscall_info info={0};
                if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"clone-replay-entry")) continue;
                if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                    CHECK(info.entry.nr==SYS_clone3);
                    t->phase=CLONE_ADMISSION;
                } else {
                    CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT);
                    struct user_pt_regs replay=t->original; replay.pc-=4;
                    if (!set_registers(pid,&replay)) continue;
                }
                resume(pid,0); continue;
            }
            if (t->phase==CLONE_ADMISSION) {
                /* A concurrent filter may reject the replay before TRACE.
                 * Retain that kernel result and retire the captured arguments. */
                CHECK(!buffer_cleanup(t)); t->clone_prepared=0;
                if (!restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (t->machine.syscall_exit) {
                int owned=md_svc_machine_exit(&t->machine,pid);
                if (owned==-ESRCH) { t->resume_lost=1; continue; }
                if (owned<0) errno=-owned;
                CHECK(owned==1);
                resume(pid,0); continue;
            }
            if (t->machine.retry_svc) {
                uintptr_t suspended=t->machine.retry_svc;
                struct user_pt_regs observed;
                int owned=md_svc_machine_retry(&t->machine,pid,&observed);
                if (owned==-ESRCH) { t->resume_lost=1; continue; }
                CHECK(owned>=0);
                if (owned) {
                    machine_observe(t,&observed);
                    int error=md_svc_registry_resume(md_memory_svc(t->memory),pid,suspended);
                    if (error==-ESRCH) { t->resume_lost=1; continue; }
                    CHECK(!error);
                    resume(pid,0); continue;
                }
                for (struct thread *member=threads;member;member=member->next)
                    if (member->pid && member->memory==t->memory)
                        md_broker_resample(broker,member->pid,suspended+4);
            }
            if (t->phase==WAIT_CANCEL_EXIT) {
                t->phase=t->wait_resume; t->wait_resume=IDLE;
            }
            if (t->phase==RESULT_REPLAY_ENTRY) {
                /* The gate supplies SVC, never an instruction from a patched
                 * application page. Restore the original context before kernel
                 * seccomp so IP- and argument-dependent rules see that call. */
                if (!set_registers(pid,&t->original)) continue;
                t->phase=RESULT_REPLAY_EXIT; resume(pid,0); continue;
            }
            if (t->phase==RESULT_REPLAY_EXIT) {
                long value;
                int delivered=md_broker_result_finish(broker,pid,&value);
                CHECK(delivered>=0);
                if (!delivered) {
                    struct user_pt_regs regs;
                    if (!registers(pid,&regs)) continue;
                    value=(long)regs.regs[0];
                }
                if (!delivered && md_syscall_restarting(value)) {
                    /* Group stops can cancel admission despite the signal
                     * shield. Keep the completed open, replace only its expired
                     * notification, and reapply the original kernel policy. */
                    buffer_result_begin(t,0);
                    buffer_result_replay(t); continue;
                }
                t->phase=DISPATCHING; buffer_reply(t,value); continue;
            }
            if (t->direct==DIRECT_ADMIT) {
                long value;
                int delivered=md_broker_result_finish(broker,pid,&value);
                CHECK(delivered>=0);
                if (!delivered) {
                    struct user_pt_regs regs;
                    if (!registers(pid,&regs)) continue;
                    value=(long)regs.regs[0];
                }
                if (value==-EINTR || md_syscall_restarting(value)) {
                    if (statistics) ++statistics->direct_retry;
                    /* The mutation is complete. Retry only its cancelled FD
                     * admission under the existing task-affine signal shield. */
                    buffer_result_begin(t,0);
                    t->direct=DIRECT_PREPARE_RESULT;
                    if (save_signal_mask(t,&t->mask,"direct-retry-mask")
                            && shield(t) && skip(pid)) dispatch_with_stack(t);
                    continue;
                }
                CHECK(!buffer_cleanup(t)); t->direct=DIRECT_NONE;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (t->phase==PROCESS_INIT_ENTRY) {
                struct ptrace_syscall_info info;
                if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"pid-init-entry")) continue;
                if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                    CHECK(info.entry.nr==SYS_prctl && info.instruction_pointer==RAW_GATE);
                    t->phase=PROCESS_INIT_EXIT;
                }
                resume(pid,0); continue;
            }
            if (t->phase==PROCESS_INIT_EXIT) {
                struct user_pt_regs regs;
                if (!registers(pid,&regs)) continue;
                if ((long)regs.regs[0]<0) {
                    fprintf(stderr,"Shroot: PID init subreaper admission failed: %ld\n",(long)regs.regs[0]);
                    syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL); resume(pid,0); continue;
                }
                if (!set_registers(pid,&t->original) || !restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (t->phase==PROCESS_OBSERVING) {
                struct user_pt_regs regs;
                if (!registers(pid,&regs)) continue;
                md_memory_release(t->remote_memory); t->remote_memory=NULL;
                long nr=t->original.regs[8], value=(long)regs.regs[0];
                /* Kernel restart retains orig_x0; restore every other argument
                 * before signal delivery can save the interrupted context. */
                memcpy(regs.regs+1,t->original.regs+1,7*sizeof(regs.regs[0]));
                unsigned info_argument=nr==SYS_waitid ? 2 : 1;
                if (((!value && nr==SYS_waitid) || (value>0 && nr==SYS_rt_sigtimedwait))
                        && t->original.regs[info_argument]) {
                    t->process_return=regs;
                    if (!save_signal_mask(t,&t->mask,"pid-info-mask") || !shield(t)) continue;
                    regs.pc=abi.load_groups; regs.regs[0]=t->original.regs[info_argument]; regs.regs[1]=8;
                    t->phase=PROCESS_INFO_LOAD;
                    if (set_registers(pid,&regs)) resume(pid,0);
                    continue;
                }
                if (value>0 && nr==SYS_getppid) t->parent=(pid_t)value;
                if (!value && nr==SYS_prctl && t->original.regs[0]==PR_SET_CHILD_SUBREAPER)
                    md_pid_identity_set_subreaper(t->resources.pid_group,t->original.regs[1]!=0);
                if (value>0 && (nr==SYS_getppid || nr==SYS_wait4 || nr==SYS_getpgid || nr==SYS_getsid
                        || nr==SYS_setsid || nr==SYS_set_tid_address)) {
                    regs.regs[0]=visible_pid(t,(pid_t)value);
                    if (nr==SYS_wait4) md_task_registry_reap(tasks,(pid_t)value);
                }
                if (!set_registers(pid,&regs)) continue;
                if (t->debug_call_active) t->debug_adapter=1;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (t->fd_running) {
                struct user_pt_regs returned;
                if (!registers(pid,&returned)) continue;
                long nr=t->fd_registers.regs[8];
                fd_finish(t,(long)returned.regs[0]);
                if (!t->fd_job && close_begin(t,&returned,nr==SYS_close)) continue;
                if (!md_clone_call(nr) && nr!=SYS_execve && nr!=SYS_execveat) {
                    /* Bootstrap descriptor operations are not completion of
                     * the guest exec; only an entered image exposes stops. */
                    if (t->phase==IDLE && t->entered && t->process_image) {
                        if (debug_syscall_exit(t,&returned)) continue;
                        t->debug_step_call=md_debugger_step(debugger,pid);
                    }
                    resume(pid,0); continue;
                }
            }
            if (interrupted_operation(t)) continue;
            if (t->phase==DEBUG_WAIT_ENTRY) {
                t->phase=DEBUG_WAIT_EXIT; resume(pid,0); continue;
            }
            if (t->phase==DEBUG_WAIT_EXIT) {
                struct user_pt_regs regs;
                if (!registers(pid,&regs)) continue;
                if (!signal_mask(t,t->debug_wait_mask,"debug-wait-unmask")) continue;
                t->phase=IDLE;
                md_debugger_wait_complete(debugger,pid,(long)regs.regs[0]); continue;
            }
            if (t->phase==DEBUG_PARK_ENTRY) {
                if (t->debug_wait_waking) {
                    if (!skip(pid)) continue;
                } else if (!signal_mask(t,t->debug_wait_mask,"debug-park-unmask")) continue;
                t->phase=DEBUG_PARK_EXIT; resume(pid,0); continue;
            }
            if (t->phase==DEBUG_PARK_EXIT) { debugger_park_complete(t); continue; }
            if (t->phase==IDLE) {
                struct ptrace_syscall_info info;
                struct user_pt_regs regs;
                if (!registers(pid,&regs)) continue;
                if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"syscall-exit")) continue;
                if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                    if (t->debug_replay_entry) { t->debug_replay_entry=0; resume(pid,0); continue; }
                    t->debug_call_active=md_debugger_syscall_mode(debugger,pid);
                    if (regs.regs[8]==SYS_waitid && md_debugger_call(debugger,pid,&regs)) continue;
                    if (t->entered && t->process_image && md_debugger_syscall(debugger,pid,&regs,1)) continue;
                } else {
                    if (debug_syscall_exit(t,&regs)) continue;
                }
                resume(pid,0); continue;
            }
            if (t->phase == WATCH_CANCEL) {
                struct user_pt_regs regs = t->original; regs.pc = abi.watch_gate;
                if (!set_registers(pid, &regs)) continue;
                t->phase = WATCH_ENTRY; resume(pid, 0); continue;
            }
            if (t->phase == WATCH_ENTRY) {
                /* Restore before the blocking syscall, not after readiness. */
                if (!restore_mask(t)) continue;
                t->phase = WATCH_READ; resume(pid, 0); continue;
            }
            if (t->phase==CALL_RESTART_ENTRY) {
                TRACE("PROBE call-restart-entry pid=%d\n",pid);
                if (!skip(pid)) continue;
                t->phase=CALL_RESTART_EXIT; resume(pid,0); continue;
            }
            if (t->phase==CALL_RESTART_EXIT) {
                TRACE("PROBE call-restart-exit pid=%d nr=%llu\n",pid,t->original.regs[8]);
                /* Give signal delivery the original syscall context, including
                 * the kernel's orig_x0. Kernel SA_RESTART handling, not a guessed
                 * userspace action table, decides whether to restart it.
                 * Internal preparation uses ERESTARTNOINTR: handlers still run,
                 * but its synthetic wait must not make mmap return EINTR. */
                struct user_pt_regs regs=t->original;
                regs.regs[0]=(uint64_t)(t->call_restart==RESTART_PREPARATION ? -513 : -512);
                int nr=(int)t->original.regs[8]; struct iovec number={&nr,sizeof(nr)};
                if (!tracee_request(t,PTRACE_SETREGSET,(void *)NT_ARM_SYSTEM_CALL,&number,"call-restart-syscall")
                        || !set_registers(pid,&regs) || !restore_mask(t)) continue;
                /* The original interruption may have been consumed while
                 * rebuilding this exit (notably SIGSTOP/SIGCONT). Rearm kernel
                 * signal work so ERESTARTSYS cannot escape to the application.
                 * EVENT_WAIT: the ordinary event loop owns the synthetic stop. */
                if (!tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"call-restart-delivery")) continue;
                md_memory_stack_return(t->memory,t->stack); t->stack=0;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (t->phase==FILE_WAIT_ENTRY || t->phase==CONTROL_WAIT_ENTRY || t->phase==CLOSE_WAIT_ENTRY) {
                int entered=wait_poll_entry(t);
                if (entered<=0) { if (!entered) resume(pid,0); continue; }
            }
            if (t->phase==FILE_WAIT_ENTRY) {
                if (md_fs_buffer_open_ready(t->buffer_call)) {
                    wait_cancel_entry(t,FILE_WAITING);
                    continue;
                }
                if (!restore_mask(t)) continue;
                t->phase=FILE_WAITING; resume(pid,0); continue;
            }
            if (t->phase==FILE_WAITING) { file_wait_complete(t); continue; }
            if (t->phase==CLOSE_WAIT_ENTRY) {
                if (t->close_done) {
                    wait_cancel_entry(t,CLOSE_WAITING);
                    continue;
                }
                uint64_t wait_mask=t->close_phase==DISPATCHING ? t->mask : t->close_mask;
                if (!signal_mask(t,wait_mask,"close-wait-mask")) continue;
                t->phase=CLOSE_WAITING; resume(pid,0); continue;
            }
            if (t->phase==CLOSE_WAITING) { close_wait_complete(t); continue; }
            if (t->phase==CONTROL_WAIT_ENTRY) {
                if (t->control_done) {
                    wait_cancel_entry(t,CONTROL_WAITING);
                    continue;
                }
                if (!restore_mask(t)) continue;
                t->phase=CONTROL_WAITING; resume(pid,0); continue;
            }
            if (t->phase==CONTROL_WAITING) { control_wait_complete(t); continue; }
            if (t->phase==SOCKET_WAIT_ENTRY) {
                if (t->socket_waking) {
                    t->socket_waking=0;
                    if (skip(pid)) socket_io_progress(t);
                    continue;
                }
                int error=socket_watch(t);
                if (error) {
                    if (!skip(pid)) continue;
                    if (t->readiness) {
                        md_epoll_wait_cancel(&t->readiness->wait); readiness_reply(t,error); continue;
                    }
                    struct md_ipc_io *op=socket_operation(t);
                    if (op->job.started) {
                        md_ipc_io_interrupt(op);
                        if (op->native_result==-EINTR) op->native_result=error;
                        socket_io_progress(t);
                    } else socket_complete(t,error);
                    continue;
                }
                if (t->readiness) {
                    struct md_buffer_span span;
                    CHECK(!md_buffer_span(&t->buffer,&span));
                    struct md_epoll_frame *f=span.data;
                    if (f->masked) {
                        struct user_pt_regs regs;
                        if (!registers(pid,&regs)) continue;
                        regs.regs[3]=md_memory_buffer_address(t->memory,&t->buffer)+offsetof(struct md_epoll_frame,mask);
                        if (!set_registers(pid,&regs)) continue;
                    }
                }
                if (!restore_mask(t)) continue;
                t->phase=SOCKET_WAITING; resume(pid,0); continue;
            }
            if (t->phase==SOCKET_WAITING) { socket_wait_complete(t); continue; }
            if (t->phase==IPC_WAIT_ENTRY) {
                int error=ipc_watch(t);
                if (error) {
                    t->ipc_cancel=error;
                    if (skip(pid)) dispatch_with_stack(t);
                    continue;
                }
                if (!restore_mask(t)) continue;
                t->phase=IPC_WAITING; resume(pid,0); continue;
            }
            if (t->phase==IPC_WAITING) {
                ipc_wait_complete(t); continue;
            }
            if (t->phase == WATCH_READ) {
                struct user_pt_regs returned;
                if (!registers(pid, &returned)) continue;
                long value = (long)returned.regs[0];
                if (value == MD_WATCH_TASK_AFFINE || (t->watch_wait == 1 && value > 0)) {
                    if (!shield(t)) continue;
                    t->watch_wait = 0;
                    dispatch_with_stack(t); continue;
                }
                struct user_pt_regs regs = t->original; regs.regs[0] = returned.regs[0];
                if (!set_registers(pid, &regs)) continue;
                if (t->stack) { md_memory_stack_return(t->memory, t->stack); t->stack = 0; }
                md_fs_worker_wake(filesystem);
                t->phase = IDLE; resume(pid, 0); continue;
            }
            if (t->phase == EXPORT_ENTRY) {
                t->phase = EXPORT_NOTIFY;
                resume(pid, 0); continue;
            }
            if (t->phase == EXPORT_RETURN || t->phase == EXPORT_NOTIFY) {
                struct metadata_operation *op = &t->metadata;
                if (!registers(pid, &op->returned)) continue;
                if (t->phase == EXPORT_NOTIFY || (long)op->returned.regs[0] < 0)
                    op->error = (int)op->returned.regs[0];
                /* A newer policy can complete the replay without notifying us,
                 * even with ERRNO|0. Preserve that result and leave output alone. */
                if (t->phase == EXPORT_NOTIFY) op->copied = op->output_size;
                store_next(t); continue;
            }
            if (t->phase == OBSERVING) {
                struct user_pt_regs regs;
                if (!registers(pid, &regs)) continue;
                if (md_clone_call(t->original.regs[8])) {
                    t->cloning = 0;
                    if ((long)regs.regs[0]>0 && t->pid_result) {
                        regs.regs[0]=t->pid_result;
                        if (!set_registers(pid,&regs)) continue;
                    }
                    t->pid_result=0;
                    if ((long)regs.regs[0]>0 && t->pid_parent_address)
                        pid_store_add(t,t->pid_parent_address,(pid_t)regs.regs[0]);
                    t->pid_parent_address=0;
                    if (t->clone_prepared) {
                        CHECK(!buffer_cleanup(t)); t->clone_prepared=0;
                        if (t->signal_shielded && !restore_mask(t)) continue;
                    }
                }
                if ((long)regs.regs[0] >= 0 && (t->original.regs[8] == SYS_munmap || t->original.regs[8] == SYS_mmap)) {
                    size_t length = t->original.regs[1], page = (size_t)getpagesize();
                    CHECK(length <= SIZE_MAX-(page-1));
                    uintptr_t address=t->original.regs[8]==SYS_mmap ? regs.regs[0] : t->original.regs[0];
                    CHECK(!md_shm_unmapped(t->shm_space,address,(length+page-1)&~(page-1),t->tgid,t->resources.pid_group));
                }
                TRACE("PROBE native syscall pid=%d nr=%llu arg0=%#llx arg1=%#llx result=%lld\n",
                    pid, t->original.regs[8], t->original.regs[0], t->original.regs[1], (long long)regs.regs[0]);
                if (t->entered && !regs.regs[0] && ((t->original.regs[8] == SYS_seccomp
                            && t->original.regs[0] == SECCOMP_SET_MODE_FILTER)
                        || (t->original.regs[8] == SYS_prctl && t->original.regs[0] == PR_SET_SECCOMP))) {
                    t->application_filter=1;
                    if (t->original.regs[8]==SYS_seccomp && (t->original.regs[1]&SECCOMP_FILTER_FLAG_TSYNC))
                        for (struct thread *peer=threads;peer;peer=peer->next)
                            if (peer->pid && peer->tgid==t->tgid) peer->application_filter=1;
                    TRACE("PROBE installed-filter pid=%d instructions=%d\n", pid, t->filter_length);
                }
                if (t->memory_action==MEMORY_KERNEL) {
                    if (memory_capture_begin(t,&regs)) continue;
                    if (t->original.regs[8]==SYS_mprotect) {
                        if ((long)regs.regs[0]==-EACCES) {
                            if (memory_repair(t,&regs,status,&usage)) continue;
                        }
                    } else md_memory_mapping_finish(t->memory,&t->memory_change,(long)regs.regs[0]);
                    if (t->memory_files.table) CHECK(!md_fd_order_complete(&t->memory_files));
                    CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
                }
                t->memory_arguments_only=0;
                t->phase = IDLE; resume(pid, 0); continue;
            }
            if (t->phase!=EXECUTING) {
                struct user_pt_regs unexpected;
                if (registers(pid,&unexpected)) fprintf(stderr,"guest-runtime: unexpected syscall-stop pid=%d phase=%d pc=%#llx nr=%llu result=%lld\n",
                    pid,t->phase,unexpected.pc,unexpected.regs[8],(long long)unexpected.regs[0]);
            }
            CHECK(t->phase == EXECUTING);
            if (!shield(t)) continue;
            t->phase = DISPATCHING; resume(pid, 0);
        } else if (event==MD_TASK_CHECKPOINT_EVENT) {
            struct user_pt_regs regs;
            if (!registers(pid, &regs)) continue;
            uintptr_t checkpoint=regs.pc-4;
            if (statistics) {
#define COUNT_CHECKPOINT(field,symbol) if (checkpoint==abi.field) ++statistics->checkpoints[CHECKPOINT_COUNT_##field];
                MD_CHECKPOINT_SITES(COUNT_CHECKPOINT)
#undef COUNT_CHECKPOINT
            }
            if (abi.export_control && checkpoint==abi.export_control && t->ready) {
                export_control(t,&regs); continue;
            }
            if (abi.fd_control && checkpoint==abi.fd_control && t->ready) {
                fd_control(t,&regs); continue;
            }
            if (abi.buffer_control && checkpoint==abi.buffer_control && t->ready) {
                buffer_control(t,&regs); continue;
            }
            if (abi.task_control && checkpoint==abi.task_control && t->ready) {
                mapping_begin(t,&regs); continue;
            }
            if (checkpoint==abi.enter && t->phase==IDLE && t->ready) {
                if (!t->entered) {
                    int result=image_enter(t,regs.regs[2],regs.regs[3],regs.regs[4],regs.regs[5]);
                    /* Ordinary entry commits at this existing stop. Secure
                     * entry returns only to lower native dumpability first. */
                    if (result) { return_value(t,&regs,result); continue; }
                } else if (regs.regs[3] || regs.regs[4] || regs.regs[5]) {
                    return_value(t,&regs,-EPERM); continue;
                }
                if (t->image_backings_ready && !md_memory_svc(t->memory)) {
                    struct md_svc_registry *registry=md_svc_machine_admit(pid,abi.svc_runtime);
                    if (registry) {
                        CHECK(!md_memory_svc_set(t->memory,registry));
                        if (!t->terminal_observation) {
                            t->terminal_observation=1; t->trace_options_dirty=1;
                        }
                    }
                }
                t->process_image=regs.regs[2];
                uint64_t entry=regs.regs[0], stack=regs.regs[1];
                regs=md_task_registers_entry(entry,stack,regs.pstate);
                if (!set_registers(pid,&regs)) continue;
                if (!md_debugger_exec(debugger,pid,t->debug_exec_old ? t->debug_exec_old : pid)) resume(pid,0);
                continue;
            }
            if (!t->ready && t->phase == IDLE && regs.regs[2] == MD_INTERCEPTION_MAGIC) {
                if (!t->resources.fs) CHECK(!md_resources_init(&t->resources,md_fs_worker_mounts(filesystem)));
                struct md_interception_abi received;
                struct iovec local = {&received, sizeof(received)}, remote = {(void *)regs.regs[3], sizeof(received)};
                CHECK(process_vm_readv(pid, &local, 1, &remote, 1, 0) == sizeof(received));
                CHECK(received.magic == MD_INTERCEPTION_MAGIC && received.size == sizeof(received)
                    && received.ready == checkpoint && received.copy_end > received.copy_begin);
                CHECK(!abi.magic || !memcmp(&abi, &received, sizeof(abi)));
                abi = received;
                checkpoints.begin=abi.code_begin; checkpoints.end=abi.code_end;
            }
            if (checkpoint == LOADED_BYTE && t->phase == DOMAIN_PATH) {
                t->metadata.path[t->metadata.path_size++] = (char)regs.regs[2];
                if (regs.regs[2]) domain_path_load(t); else domain_path_finish(t, 0);
                continue;
            }
            if (checkpoint==abi.loaded_groups && t->phase==PROCESS_INFO_LOAD) {
                regs.regs[8]=regs.regs[19];
                _Static_assert(offsetof(siginfo_t,si_pid)==16,"AArch64 siginfo PID offset");
                t->process_info_code=(int)regs.regs[5];
                t->process_info_native=(pid_t)regs.regs[7];
                int translate=(int)regs.regs[3]==SIGCHLD || t->process_info_code==SI_USER || t->process_info_code==SI_TKILL;
                regs.regs[3]=translate ? visible_pid(t,t->process_info_native) : t->process_info_native;
                regs.regs[4]=translate ? process_signal_uid(t,t->process_info_native,(uid_t)regs.regs[8]) : regs.regs[8];
                unsigned info_argument=t->original.regs[8]==SYS_waitid ? 2 : 1;
                regs.regs[0]=t->original.regs[info_argument]+offsetof(siginfo_t,si_pid);
                regs.regs[1]=2; regs.pc=abi.store_groups; t->phase=PROCESS_INFO_STORE;
                if (set_registers(pid,&regs)) resume(pid,0);
                continue;
            }
            if (checkpoint==abi.stored_groups && t->phase==PROCESS_INFO_STORE) {
                if (t->original.regs[8]==SYS_waitid && !(t->original.regs[3]&WNOWAIT) && (t->process_info_code==CLD_EXITED
                        || t->process_info_code==CLD_KILLED || t->process_info_code==CLD_DUMPED))
                    md_task_registry_reap(tasks,t->process_info_native);
                if (!set_registers(pid,&t->process_return) || !restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (checkpoint==abi.stored_groups && t->phase==PROCESS_TID_STORE) {
                if (!set_registers(pid,&t->original) || !restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (checkpoint==abi.stored_groups && t->phase==PROCESS_VALUE_STORE) {
                if (!set_registers(pid,&t->process_return) || !restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (checkpoint == STORED_IDS && t->phase == STORING_IDS) {
                regs = t->original; regs.regs[0] = 0;
                if (!set_registers(pid, &regs) || !restore_mask(t)) continue;
                t->phase = IDLE; resume(pid, 0); continue;
            }
            if (t->phase == IDENTITY_COPY && (checkpoint == abi.loaded_groups || checkpoint == abi.stored_groups)) {
                unsigned count = t->identity_count - t->identity_copied;
                if (count > 16) count = 16;
                if (checkpoint == abi.loaded_groups)
                    for (unsigned i = 0; i < count; i++)
                        t->identity_buffer[t->identity_copied+i] = regs.regs[i==5 ? 19 : i+3];
                t->identity_copied += count; identity_copy_next(t); continue;
            }
            if (checkpoint == EXPORTED && (t->phase == EXPORTING || t->phase == PROC_OPENING)) {
                export_finish(t); continue;
            }
            if (checkpoint == LOADED_BYTE && t->phase == LOADING_PATH) {
                struct metadata_operation *op = &t->metadata;
                op->path[op->path_size++] = (char)regs.regs[2];
                if (regs.regs[2]) load_path(t); else replay(t);
                continue;
            }
            if (checkpoint == STORED && t->phase == STORING) {
                t->metadata.copied += 16; store_next(t); continue;
            }
            if (checkpoint == READY && t->phase == IDLE) {
                memory_access_capture(t);
                CHECK(!read_string(pid, regs.regs[1], t->endpoint, sizeof(t->endpoint)));
                if (admitted_path && !admission.image) {
                    int error = md_admission_open(&admission, t->endpoint, admitted_path);
                    if (error) TRACE("PROBE admission error=%d\n", error);
                    CHECK(!error);
                }
                if ((int)regs.regs[0] >= 0) {
                    CHECK(listener == -1);
                    listener = duplicate_fd(pid, (int)regs.regs[0]); CHECK(listener >= 0);
                    if (broker) md_broker_listen(broker, listener);
                }
                t->ready = 1;
                t->registration_revoked=!!(t->application_filter || t->cooperative_blocked);
                regs.regs[0]=t->registration_revoked ? 0 : MD_INTERCEPTION_COOPERATIVE;
                if (broker && !t->entered && !t->registration_revoked
                        && !t->debug_options && !md_memory_exposed(t->memory))
                    regs.regs[0]|=MD_INTERCEPTION_MACHINE;
                if (statistics) regs.regs[0]|=MD_INTERCEPTION_STATISTICS;
                if (!set_registers(pid, &regs)) continue;
                describe_process(pid, 0);
                TRACE("PROBE guest image ready pid=%d\n", pid); resume(pid, 0);
            } else if (checkpoint == ALLOCATED && t->phase == ALLOCATING) {
                CHECK((long)regs.regs[0] > 0 && !(regs.regs[0] & (md_page_size - 1)));
                t->stack = regs.regs[0]; CHECK(!md_memory_stack_add(t->memory, t->stack)); dispatch(t);
            } else if (checkpoint == DONE && t->phase == DISPATCHING) {
                dispatch_complete(t,(long)regs.regs[0]);
            } else {
                fprintf(stderr,"Shroot: unexpected checkpoint pid=%d pc=%#llx phase=%d\n",pid,regs.pc,t->phase);
                CHECK(0);
            }
        } else {
            siginfo_t debug_info;
            TRACE("PROBE signal stop pid=%d signal=%d phase=%d\n",pid,sig,t->phase);
            if (sig==SIGSTOP && !debugger_at_boundary(t)
                    && !native_trace(PTRACE_GETSIGINFO,pid,NULL,&debug_info)
                    && md_debugger_defer_stop(debugger,pid,&debug_info)) {
                /* EVENT_WAIT: finish the internal operation before publishing
                 * the debugger stop; task exit/cancellation releases its owner.
                 * Delivering SIGSTOP now would group-stop shared resource owners. */
                resume(pid,0); continue;
            }
            if (debugger_at_boundary(t) && !native_trace(PTRACE_GETSIGINFO,pid,NULL,&debug_info)
                    && md_debugger_stop(debugger,pid,status,&debug_info,0)) continue;
            struct user_pt_regs regs;
            if ((sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL
                        || (diagnostics && sig == SIGTRAP))
                    && !registers(pid, &regs)) continue;
            if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == DOMAIN_PATH
                    && regs.pc == LOAD_BYTE) {
                domain_path_finish(t, -EFAULT); continue;
            }
            if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == IDENTITY_COPY
                    && ((regs.pc >= abi.load_groups && regs.pc < abi.loaded_groups)
                        || (regs.pc >= abi.store_groups && regs.pc < abi.stored_groups))) {
                identity_copy_finish(t, -EFAULT); continue;
            }
            if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == STORING_IDS) {
                if (regs.pc >= STORE_IDS && regs.pc < STORED_IDS) {
                    regs = t->original; regs.regs[0] = (uint64_t)-EFAULT;
                    if (!set_registers(pid, &regs) || !restore_mask(t)) continue;
                    t->phase = IDLE; resume(pid, 0); continue;
                }
            }
            if ((sig==SIGSEGV || sig==SIGBUS) && t->phase==PROCESS_TID_STORE
                    && regs.pc>=abi.store_groups && regs.pc<abi.stored_groups) {
                /* Linux ignores a failed CLONE_*SETTID store. The child still
                 * exists; never report a second failed clone to its parent. */
                if (!set_registers(pid,&t->original) || !restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if ((sig==SIGSEGV || sig==SIGBUS) && ((t->phase==PROCESS_INFO_LOAD
                    && regs.pc>=abi.load_groups && regs.pc<abi.loaded_groups)
                    || ((t->phase==PROCESS_INFO_STORE || t->phase==PROCESS_VALUE_STORE)
                        && regs.pc>=abi.store_groups && regs.pc<abi.stored_groups))) {
                t->process_return.regs[0]=(uint64_t)-EFAULT;
                if (!set_registers(pid,&t->process_return) || !restore_mask(t)) continue;
                t->phase=IDLE; resume(pid,0); continue;
            }
            if (sig == SIGSEGV && t->phase == LOADING_PATH && regs.pc == LOAD_BYTE) {
                t->metadata.error = -EFAULT; replay(t); continue;
            }
            if (sig == SIGSEGV && t->phase == STORING && regs.pc == STORE) {
                t->metadata.error = -EFAULT; store_next(t); continue;
            }
            if ((sig==SIGSEGV || sig==SIGBUS) && regs.pc>=COPY_BEGIN && regs.pc<COPY_END
                    && (t->phase==DISPATCHING || cooperative_copying(t))) {
                siginfo_t info;
                if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"guest-copy-fault")) continue;
                if (info.si_code>0) {
                    copy_faults++;
                    regs.regs[0] = (uint64_t)-EFAULT; regs.pc = regs.regs[30];
                    if (set_registers(pid, &regs)) resume(pid, 0);
                    continue;
                }
            }
            if (sig == SIGSYS || sig == SIGSEGV || sig == SIGABRT || sig == SIGILL
                    || (diagnostics && sig == SIGTRAP)) {
                siginfo_t info;
                if (!tracee_request(t, PTRACE_GETSIGINFO, NULL, &info, "signal-info")) continue;
                signals++;
                if (sig == SIGSYS) {
                    unsigned i;
                    for (i = 0; i < 64; i++) {
                        struct denial *d = &denials[i];
                        if (!d->count) *d = (struct denial){.nr = info.si_syscall,
                            .adapted = t->phase == IDLE ? 0 : t->original.regs[8], .phase = t->phase};
                        if (d->nr == (unsigned)info.si_syscall && d->phase == t->phase
                                && d->adapted == (t->phase == IDLE ? 0 : t->original.regs[8])) {
                            d->count++; break;
                        }
                    }
                    if (i == 64 || denials[i].count > 4) { resume(pid, sig); continue; }
                }
                TRACE("PROBE signal pid=%d signal=%d code=%d pc=%#llx nr=%llu phase=%d adapted=%llu address=%p\n",
                    pid, sig, info.si_code, regs.pc, regs.regs[8], t->phase, t->original.regs[8], info.si_addr);
                if (diagnostics && t->process_image) {
                    char image[PATH_MAX];
                    ssize_t length=md_process_image_link(pid,t->process_image,-1,image,sizeof(image));
                    if (length>0) TRACE("PROBE signal-image pid=%d path=%.*s\n",pid,(int)length,image);
                }
                if (sig == SIGSYS)
                    TRACE("PROBE application SIGSYS syscall=%d cookie=%d call=%p\n", info.si_syscall, info.si_errno, info.si_call_addr);
            }
            deliver_signal(pid,sig,restored_signal);
        }
    }
    for (unsigned i = 0; i < 64 && denials[i].count; i++)
        TRACE("PROBE SIGSYS summary nr=%u phase=%d adapted=%u count=%u\n",
            denials[i].nr, denials[i].phase, denials[i].adapted, denials[i].count);
    TRACE("PROBE complete status=%d threads=%u images=%u adapted=%u signals=%u copyFaults=%u fstatFailures=%u sandboxSupportEstablished=false\n",
        result, total, images, calls, signals, copy_faults, fstat_failures);
    TRACE("PROBE external metadata calls=%u errors=%u\n", external_stats, external_errors);
    TRACE("PROBE protected metadata calls=%u\n", protected_stats);
    TRACE("PROBE retained requests=%u errors=%u\n", retained_calls, retained_errors);
    TRACE("PROBE proc-root domain=1 changes=%u denials=%u\n",
        root_changes, restricted_denials);
    TRACE("PROBE job-control stops=%u wakes=%u racedWakes=%u\n",
        group_stops, group_wakes, raced_wakes);
    TRACE("PROBE identity admittedImages=%u changes=%u hostUid=%u\n",
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
