#define _GNU_SOURCE
#include "fs_storage.h"
#include "storage_database.h"
#include "host_policy.h"
#ifdef MD_KERNEL_TESTING
#include "kernel_profile.h"
#endif
#include "fd_native_pin.h"
#include "watch_calls.h"
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
#include "task_retained.h"
#include "task_robust.h"
#include "task_child_reap.h"
#include "signal_protocol.h"
#include "seccomp_listener.h"
#include "seccomp_notify.h"
#include "interception_program.h"
#include "pid_filter_program.h"
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
#include "fs_continuation.h"
#include "task_mapping.h"
#include "task_quiesce.h"
#include "task_filter.h"
#include "seccomp_install.h"
#include "seccomp_capture.h"
#include "task_copy.h"
#include "task_signal_control.h"
#include "signal_view.h"
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
#include "process_oom.h"
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
#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif
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
#include "task_pending.h"

/* One supervisor owns one launched tree. It never attaches to Android tasks. */
static struct md_interception_abi abi;
static struct md_interception_memory *entry_fence_memory;
static struct thread *entry_fence_owner;
static struct md_task_quiesce *policy_barrier;
static struct thread *policy_installer;
static int policy_barrier_ready;
static int entry_fence_stage, entry_fence_error;
static uint64_t entry_fence_slots;
static int64_t entry_fence_retry_at;
static unsigned entry_fence_waiters;
static int64_t entry_fence_deadline;
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
static int signal_diagnostics;
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
    uint64_t entry_executions, entry_completions, entry_promotions, entry_effects,entry_native;
    uint64_t entry_contexts[MD_CONTEXT_COUNT];
    uint64_t entry_local_contexts[MD_CONTEXT_COUNT],entry_local_native;
    uint64_t signal_frames[5];
    struct md_cost event_collect, event_sleep;
    struct { uint64_t trace, notification; } calls[512];
};
static struct interception_statistics *statistics;
#define TRACE(...) do { if (diagnostics) fprintf(stderr, __VA_ARGS__); } while (0)
#define TRACE_SIGNAL(...) do { if (diagnostics || signal_diagnostics) fprintf(stderr, __VA_ARGS__); } while (0)
enum phase { IDLE, ALLOCATING, DISPATCHING, EXECUTING, OBSERVING,
    EXPORT_CANCEL, EXPORTING, EXPORT_ENTRY, EXPORT_NOTIFY, EXPORT_RETURN, STORING,
    DELEGATE_CANCEL, LOADING_PATH, PROC_CANCEL, PROC_OPENING, STORING_IDS, DOMAIN_PATH,
    WATCH_CANCEL, WATCH_ENTRY, WATCH_READ, SOCKET_NATIVE, IPC_WAIT_ENTRY, IPC_WAITING,
    SOCKET_WAIT_ENTRY, SOCKET_WAITING, CALL_RESTART_ENTRY, CALL_RESTART_EXIT,
    FILE_WAIT_ENTRY, FILE_WAITING, FILE_INTERRUPTING, CONTROL_WAIT_ENTRY, CONTROL_WAITING, CLOSE_WAIT_ENTRY, CLOSE_WAITING,
    WAIT_CANCEL_EXIT, EPOLL_PROBE, EPOLL_CALL, EPOLL_WAIT,
    IDENTITY_COPY, DEBUG_WAIT_BUFFER, DEBUG_WAIT_ENTRY, DEBUG_WAIT_EXIT, DEBUG_PARK_ENTRY, DEBUG_PARK_EXIT,
    MEMORY_WAIT, RESULT_REPLAY_ENTRY, RESULT_REPLAY_EXIT, PROCESS_OBSERVING, PROCESS_TID_STORE,
    PROCESS_INFO_LOAD, PROCESS_INFO_STORE, PROCESS_VALUE_STORE, PROCESS_INIT_ENTRY, PROCESS_INIT_EXIT, PROC_LOOKUP,
    CLONE_ENTRY, CLONE_ADMISSION, FILTER_CAPTURE, SIGNAL_CONTROL, SIGNAL_ENTRY, SIGNAL_NATIVE_EXIT,
    GUEST_NOTIFICATION, FILTER_MAPPING };
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
    struct md_fs_task_capture target;
    struct md_fs_task_subject subject;
    struct md_task_helper_result result;
    int (*authorize)(struct thread *,struct thread *);
    void (*complete)(struct thread *,struct md_task_helper_result);
    int metadata, started, foreign;
    size_t directory_limit;
};
struct filter_install {
    struct md_resource_order memory;
    struct md_fd_order files;
    struct md_fd_origin_update listener;
    struct md_interception_buffer scratch;
    struct md_task_copy copy;
    struct md_seccomp_capture capture;
    struct md_task_call admission;
    struct task_action *loan;
    struct sock_fprog program;
    struct sock_filter code[BPF_MAXINSNS];
    unsigned stage;
    int checkpoint_suspended, virtual_listener, source, admitted, native_completed, retiring;
    long error;
};
#include "signal_entry.h"
struct signal_control {
    struct md_resource_order memory;
    struct md_fd_order files;
    struct md_fd_origin_update publication;
    struct md_fd_object *descriptor;
    struct md_interception_buffer scratch;
    struct md_task_signal_control control;
    struct md_kernel_action action;
    uint64_t entry_mask;
    siginfo_t forced;
    int signal, started, checkpoint_suspended, reacquiring, descriptor_admission;
    unsigned selector;
    long descriptor_result;
    struct md_memory_page *wait_page;
    struct task_action *loan;
    int retiring,retire_error;
    struct thread *entry_thread;
    struct signal_control *entry_next, **entry_previous;
    struct md_signal_entry *entry_packet;
    struct md_signal_wait_view entry_wait_view;
    int entry_blocked;
    int returning;
    siginfo_t entry_info;
};
static struct signal_control *signal_entries;
struct guest_notification {
    struct md_task_call call;
    struct md_seccomp_notify *queue;
    struct md_seccomp_notification *message;
    struct seccomp_data original;
    siginfo_t host_signal;
    int checkpoint_suspended,continuing_native,promoting;
};
struct memory_capture {
    pid_t caller;
    uint64_t mask;
    struct user_pt_regs returned;
    struct md_interception_buffer buffer;
};
struct probe_owner { pid_t caller; struct user_pt_regs continuation; };
struct buffer_mapping {
    pid_t caller;
    struct user_pt_regs continuation;
    struct md_interception_buffer scratch;
    struct md_interception_buffers *buffers;
    struct md_seccomp_policy *policy;
    uint64_t ancestor;
    uintptr_t parent_view;
    struct md_interception_memory *memory;
    struct md_resource_order turn;
    int channels;
    int clients;
    unsigned client_bucket;
};
enum metadata_restart_stage { METADATA_CHECK, METADATA_ENTRY, METADATA_WAIT };
struct metadata_restart {
    enum metadata_restart_stage stage;
    struct user_pt_regs saved;
    uint64_t mask;
    int requeued_signal;
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
    struct md_pending_node pending;
    union { struct md_task_filter filter; struct md_task_call native; };
    struct md_interception_buffer buffer;
    struct user_pt_regs continuation;
    enum md_fd_selector_kind kind;
    enum md_task_call_boundary boundary;
    int selector_number;
    unsigned selector_instructions;
    int pid_filter;
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
    struct capture_owner *capture;
    struct md_process_member *capture_capacity;
    int capture_capacity_needed;
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
    return action->loan || action->capture ? &action->native : &action->filter.task;
}
enum { READINESS_NATIVE_QUEUE, READINESS_TASK_QUEUE, READINESS_QUEUES };
struct readiness_loan {
    struct md_fd_install delivery;
    struct task_action *retirement;
    int number;
};
struct readiness_owner {
    struct md_fd_order files;
    struct md_resource_order publication_memory;
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
    struct readiness_loan loans[READINESS_QUEUES];
    unsigned lending;
    struct md_epoll_task_control *task_control;
    int task_target, task_started, park_pending, restore_completed;
    int delivery_claimed, delivery_waiting, copy_pending, resample;
    int evicting, parked, finishing, signal_ready;
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
    RETRY_PROC_IO=1u<<12, RETRY_PROC_LOOKUP=1u<<13, RETRY_READINESS=1u<<14, RETRY_CLOSE=1u<<15,
    RETRY_FILTER=1u<<16, RETRY_SIGNAL=1u<<17, RETRY_DEBUG_WAIT=1u<<18
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
    struct retained_memory *memory_actor;
    uintptr_t robust_head;
    uint32_t robust_tid;
    int robust_registering, robust_terminal, robust_replay;
    int robust_status;
    struct rusage robust_usage;
    struct user_pt_regs robust_registers;
    enum md_task_call_boundary robust_boundary;
    struct thread *robust_subject;
    struct md_interception_memory *remote_memory;
    struct md_interception_memory *memory_exposure;
    int memory_resume, memory_signal;
    struct md_svc_machine_thread machine;
    int entry_fence_waiting, entry_fence_held, entry_fence_replay;
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
    int metadata_signal_restored, metadata_signal_requeued;
    struct md_signal_delivery signal_delivery;
    struct md_signal_origin signal_origin;
    int signal_shielded;
    int signal_return_pending;
    struct thread *broker_next;
    struct md_interception_buffer buffer;
    struct md_interception_buffer cooperative_buffer;
    const struct md_fd_selector *entry_selectors;
    uint64_t entry_selector_revision;
    unsigned entry_route_flags;
    struct md_entry_channel *entry_control;
    struct md_entry_channel *entry_execution;
    int entry_kernel_context;
    void (*entry_resume)(struct thread *);
    void (*entry_effect)(struct thread *,long);
    int entry_error;
    int cooperative_blocked, registration_revoked;
    struct md_fs_buffer_request *buffer_call;
    enum { DIRECT_NONE, DIRECT_STATUS, DIRECT_OPEN, DIRECT_PREPARE_RESULT, DIRECT_ADMIT, DIRECT_RESULT } direct;
    unsigned direct_flags, direct_mode;
    struct capture_owner *capture;
    struct probe_owner *probe;
    struct buffer_mapping *buffer_mapping;
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
    struct md_fd_capture control_capture;
    struct md_received_fds *control_input;
    struct md_fd_object *control_origin;
    int control_input_number;
    int proc_waiting, proc_direct;
    struct md_control_pending *control_pending;
    struct md_control_pending *control_publication;
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
    int wait_error;
    struct md_fd_return buffer_return;
    int buffer_return_waiting;
    int buffer_rights_delivered;
    int buffer_waiting;
    int dispatch_prepared;
    struct md_fd_selector *fd_selector;
    struct md_fd_selector *fork_selector;
    struct md_fd_selector *deferred_selector;
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
    struct md_seccomp_policy *policy;
    struct md_seccomp_policy *admission_policy;
    int admission_policy_valid, admission_policy_pending, admission_application_filter;
    struct filter_install *filter_install;
    struct guest_notification *notification;
    int notification_continued;
    struct md_signal_actions *signal_actions;
    struct signal_control *signal_control;
    int host_signals, host_signal_blocked;
    int native_signal_query;
    uint64_t signal_handler_mask;
    int signal_handler_mask_known;
    struct md_signal_wait_view signal_wait_view;
    uint64_t signal_query_mask;
    int signal_query_active;
    struct md_signal_pending *signal_pending;
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
    int vfork_borrowed;
    int terminal_observation;
    unsigned long debug_options;
    uint64_t debug_wait_mask;
    struct debug_wait_output *debug_wait_output;
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
    struct md_resource_order fd_policy_order;
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
    int process_inspection_denied;
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
static struct md_pending_queue task_action_queue;
static struct task_action *task_action_execution;
static struct task_action *loan_retirements;
static struct md_task_quiesce *task_action_barrier;
static struct md_task_quiesce *mapping_barrier;
static struct mapping_owner *mapping_execution;
static int task_action_releasing;
static struct readiness_owner *readiness_activation;
static void readiness_start(struct thread *);
static void readiness_release(struct thread *);
static void readiness_delivery_start(struct thread *);
static void readiness_capture(struct thread *,unsigned,long);
static void readiness_progress(struct thread *);
static int readiness_native_event(struct thread *,int);
static void readiness_deliver(struct thread *);
static void readiness_task_progress(struct thread *,int,long);
static void readiness_loan_closed(pid_t,long);
static int fd_loan_evict(struct thread *,struct md_fd_loan *,int);
static int fd_loans_ready(struct thread *);
static void loan_start(struct task_action *);
static void task_action_schedule(void);
static void task_action_discard(struct thread *);
static void capture_complete(void *,struct md_task_helper_result);
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
/* Private actors have wait ownership, never guest task/resource membership.
 * The mm pointer is borrowed from the attached guest tasks, not a writer pin. */
struct retained_memory {
    struct retained_memory *next,*work_next,*indexed;
    struct md_interception_memory *memory;
    struct md_task_wait_member wait;
    struct md_task_retained retained;
    struct md_task_robust cleanup;
    struct md_resource_order order;
    struct thread *creator, *terminal;
    unsigned members,pending;
    int ready, cleaning, retiring, dead, checkpoint_suspended, killed, exit_held,queued;
    long error;
};
enum { RETAINED_ACTOR_BUCKETS=257 };
static struct retained_memory *retained_memories,*retained_creation,*retained_work;
static struct retained_memory *retained_actors[RETAINED_ACTOR_BUCKETS];
static unsigned robust_replays;
struct retained_retirement {
    struct retained_memory *memory;
    struct thread *actor;
    struct md_task_child_reap reap;
    struct md_task_child_reap_scratch scratch;
    struct md_interception_buffer buffer;
    struct md_resource_order order;
    uintptr_t peer;
    int stage, borrowed, synthetic, checkpoint_suspended, broker_borrowed;
};
struct debug_wait_output {
    struct md_interception_buffer buffer;
    struct user_pt_regs registers;
    enum md_debugger_wait_action action;
};
static struct retained_retirement *retained_retirement;
static struct md_task_quiesce *retained_barrier;
static void retained_retirement_progress(void);
static int retained_barrier_event(pid_t,int,const struct rusage *);
static int retained_runnable(void);
static void retained_progress(void);
static int retained_event(pid_t,int,const struct rusage *);
static pid_t retained_take(int *,struct rusage *);
static void retained_detach(struct thread *);
static void retained_cancel(void);
static void retained_destroy(void);
static int robust_call(struct thread *,struct user_pt_regs *,enum md_task_call_boundary);
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
static int task_continuation_available(const struct thread *);
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
static void publish_execution(struct thread *t,int resuming);
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
static void buffer_reply(struct thread *,long);
static void mapping_complete(void *, struct md_task_helper_result);
static void mapping_discard(struct mapping_owner *);
static void mapping_progress(void);
static int mapping_notification(struct thread *,const struct seccomp_notif *);
static void mapping_start(struct thread *);
static void memory_start(struct thread *);
static void memory_begin(struct thread *,const struct user_pt_regs *,enum memory_action,int);
static void proc_lookup_start(struct thread *);
static void buffer_descriptor_start(struct thread *);
static void control_input_discard(struct thread *);
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
static int inspect_allowed(pid_t, pid_t, int);
static void setns_complete(struct thread *,int);
static pid_t visible_pid(struct thread *,pid_t);
static void signal_origin_capture(void *,const siginfo_t *,struct md_signal_origin *);
static void signal_wait_info(void *,siginfo_t *);
static void process_reap(struct thread *,pid_t);
static pid_t native_pid(struct thread *,pid_t);
static void ipc_unwatch(struct thread *);
static void ipc_capture_step(struct thread *, unsigned, long);
static void socket_io_progress(struct thread *);
static int restore_mask(struct thread *);
static const unsigned long trace_options = PTRACE_O_TRACESECCOMP | PTRACE_O_TRACEEXEC
    | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEVFORKDONE | PTRACE_O_TRACECLONE
    | PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD;
static void release_operation(struct thread *t);
static void filter_install_discard(struct thread *);
static int filter_listener_notification(struct thread *,const struct seccomp_notif *);
static void guest_notification_discard(struct thread *);
static int host_policy_dispatch(struct thread *,struct user_pt_regs *,const struct seccomp_data *);
static int domain_call(struct thread *,struct user_pt_regs *,unsigned long);
static int host_policy_deliver(struct thread *,struct user_pt_regs *,const siginfo_t *,int);
static void restart_call(struct thread *,const struct user_pt_regs *,uint64_t,enum call_restart);
static void restart_boundary(struct thread *,const struct user_pt_regs *,uint64_t,enum call_restart,
    enum md_task_call_boundary);
static void return_value(struct thread *,struct user_pt_regs *,long);
static void return_task_value(struct thread *,struct user_pt_regs *,long,enum md_task_call_boundary);
static int skip(pid_t);
static int task_call_signal(struct thread *,struct md_task_call *,int);
static void task_call_resume(struct thread *,struct md_task_call *,int);
static void signal_control_discard(struct thread *);
static void signal_entries_progress(void);
static int signal_entry_committed(struct thread *);
static void signal_control_progress(struct thread *);
static int signal_control_delivery(struct thread *,int,struct md_task_signal_stop *);
static int signal_control_begin(struct thread *,const struct user_pt_regs *,int,const struct md_kernel_action *,uint64_t,
    const siginfo_t *);
static int signal_logical_enqueue(struct thread *,enum md_signal_scope,const siginfo_t *);
static void filter_install_progress(struct thread *);
static void filter_entry_complete(struct thread *,const struct user_pt_regs *);
static int filter_entry_eligible(const struct thread *);
static void policy_sync_progress(void);
static void policy_sync_release(struct thread *);
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
    if (!md_seccomp_filter_call(regs->regs[8],regs->regs[0],regs->regs[1])) return -1;
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
static void cleanup(void) {
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
        md_process_executable_release(threads->executable);
        md_fs_anchor_release(threads->executable_origin);
        fd_discard(threads);
        adapter_discard(threads);
        md_broker_forget(broker,threads->pid);
        cooperative_buffer_release(threads);
        signal_control_discard(threads);
        filter_install_discard(threads);
        guest_notification_discard(threads);
        md_signal_pending_release(threads->signal_pending);
        md_signal_actions_release(threads->signal_actions);
        md_seccomp_policy_release(threads->admission_policy);
        md_seccomp_policy_replace(&threads->policy,NULL);
        md_memory_release(threads->memory);
        md_memory_release(threads->remote_memory);
        free(threads->memory_notification);
        free(threads->metadata_restart);
        if (threads->broker_completion) {
            md_fs_buffer_open_release(&threads->broker_completion->opened);
            free(threads->broker_completion);
        }
        md_fd_selector_release(threads->fd_selector);
        md_fd_selector_release(threads->deferred_selector);
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
static struct md_task_quiesce **quiescence_barrier(const struct mapping_owner *parent) {
    if (retained_creation || retained_retirement) return &retained_barrier;
    if (policy_installer) return &policy_barrier;
    if (memory_execution) return &memory_barrier;
    if (mapping_execution && mapping_execution!=parent) return &mapping_barrier;
    if (task_action_execution || task_action_releasing) return &task_action_barrier;
    return NULL;
}
static int quiescence_busy(const struct mapping_owner *parent) {
    return quiescence_barrier(parent)!=NULL;
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
    t->metadata_signal_requeued=0;
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
static void control_publication_finish(struct thread *t,long result) {
    struct md_control_pending *pending=t->control_publication;
    if (!pending) return;
    t->control_publication=NULL;
    pending->copied(pending,result); pending->dispose(pending);
}
static void control_pending_complete(struct thread *t,long result) {
    struct md_control_pending *pending=t->control_pending;
    t->control_pending=NULL;
    if (!pending) return;
    if (result>=0 && pending->copied) {
        CHECK(!t->control_publication);
        t->control_publication=pending;
    } else pending->dispose(pending);
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
    control_publication_finish(t,-ECANCELED);
    t->control_done=0;
    t->wait_resume=IDLE;
    if (t->proc_lookup.table) CHECK(!md_fd_order_cancel(&t->proc_lookup));
    control_input_discard(t);
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
    if (t->buffer_mapping) {
        t->buffer_mapping->caller=0; t->buffer_mapping=NULL;
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
    return ptrace(request,pid,address,data);
}
static int checkpoint_restricted(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t) return 1;
    if (!t->application_filter) return 0;
    const struct md_svc_registry *registry=md_memory_svc(t->memory);
    /* Do not emulate checkpoints already admitted by every kernel filter.
     * Portable entry after exec alone does not establish helper admission. */
    return !registry || !md_seccomp_policy_internal_gates(t->policy,registry->image);
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
    if (abi.fd_control && pc==abi.fd_control+4) return md_fd_control_scalar(command);
    return abi.buffer_control && pc==abi.buffer_control+4
        && (md_adapter_buffer_scalar(command) || md_fs_buffer_scalar(command));
}
static struct md_task_checkpoints checkpoints={.trace=checkpoint_native_trace,
    .restricted=checkpoint_restricted,.marker=checkpoint_marker,.entry_marker=checkpoint_entry_marker};
static int trace_continues(int request) {
    return request==PTRACE_CONT || request==PTRACE_SYSCALL
        || request==PTRACE_SINGLESTEP || request==PTRACE_SYSEMU;
}
static long native_trace(int request,pid_t pid,void *address,void *data) {
    long result=md_task_checkpoint_trace(&checkpoints,request,pid,address,data);
    if (result>=0 && trace_continues(request)) {
        /* Barriers retain the current owner's continuation, including a
         * task-affine call's entry/exit stops, not its enclosing guest phase. */
        struct thread *t=find_thread(pid);
        if (t) t->kernel_resume=request;
    }
    return result;
}
static void buffer_admission_yield(struct thread *t) {
    if (!t || !t->buffer_admission_pending) return;
    /* A shielded USER_NOTIF cannot take INTERRUPT until answered. Retirement
     * races delivery atomically; an already published operation is not replayed. */
    int sent=md_notification_retire(listener,t->buffer_admission_id,MD_BUFFER_ADMIT_RETRY);
    if (sent<0) errno=-sent;
    CHECK(sent>=0);
    if (sent) ++t->buffer_admission_yields;
    if (t->memory_notification && t->memory_notification->id==t->buffer_admission_id) {
        free(t->memory_notification); t->memory_notification=NULL;
    }
    t->buffer_admission_pending=0;
}
static int buffer_admission_quiescing(pid_t pid) {
    return md_task_quiesce_interrupting(retained_barrier,pid)
        || md_task_quiesce_interrupting(policy_barrier,pid)
        || md_task_quiesce_interrupting(memory_barrier,pid)
        || md_task_quiesce_interrupting(mapping_barrier,pid)
        || md_task_quiesce_interrupting(task_action_barrier,pid);
}
static long quiesce_trace(int request,pid_t pid,void *address,void *data) {
    if (trace_continues(request)) {
        struct thread *t=find_thread(pid);
        if (t) t->interrupt_pending=0;
        /* A barrier and guest debugger can share one INTERRUPT stop. Release
         * through normal admission so the debugger receives its pending stop
         * before guest execution, after the barrier has relinquished ownership. */
        if (t && md_debugger_traced(debugger,pid)) {
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
        if (t && t->signal_control) md_task_signal_control_quiesced(&t->signal_control->control);
    }
    /* Only barrier release restores this unchanged guest continuation.
     * Injected helper execution must retain the broker's stopped state. */
    if (result>=0 && trace_continues(request))
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
    int child=md_task_quiesce_event(barrier,pid,status,usage);
    if (child<=0) return child;
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
    t->signal_delivery.capture=signal_origin_capture;
    t->signal_delivery.capture_context=t;
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
static void debugger_wait_release(struct thread *t) {
    retry_clear(t,RETRY_DEBUG_WAIT);
    if (t->debug_wait_output) {
        CHECK(!md_buffer_release(&t->debug_wait_output->buffer));
        free(t->debug_wait_output); t->debug_wait_output=NULL;
    }
}
static void release_thread(struct thread *t) {
    int buffered=t->buffer.pool || t->buffer_call || t->buffer_waiting || t->action || t->debug_wait_output;
    debugger_wait_release(t);
    retained_detach(t);
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
    filter_install_discard(t);
    guest_notification_discard(t);
    signal_control_discard(t);
    md_signal_actions_release(t->signal_actions); t->signal_actions=NULL;
    md_signal_pending_release(t->signal_pending); t->signal_pending=NULL;
    md_seccomp_policy_replace(&t->policy,NULL);
    md_seccomp_policy_release(t->admission_policy); t->admission_policy=NULL;
    t->admission_policy_valid=t->admission_policy_pending=0;
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
    md_fd_selector_release(t->deferred_selector); t->deferred_selector=NULL;
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
static void export_start(struct thread *t,const struct user_pt_regs *observed) {
    struct metadata_operation *op = &t->metadata;
    op->mask_shielded=t->signal_shielded;
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
     * still authenticate the actual mappings. */
    int backings=machine && t->exec_backings[0]>=0
        ? md_memory_image_backings(t->memory,t->pid,t->exec_backings,t->exec_backings[1]>=0 ? 2 : 1) : -ENODEV;
    t->image_backings_ready=!backings;
    TRACE("PROBE entry-image pid=%d prepared=%lu backings=%d filter=%d blocked=%d exposed=%d\n",
        t->pid,machine,backings,t->application_filter,t->cooperative_blocked,md_memory_exposed(t->memory));
    if (diagnostics && !machine) {
        struct md_svc_runtime runtime={0};
        int error=md_svc_registry_read(t->pid,abi.svc_runtime,&runtime,sizeof(runtime));
        TRACE("PROBE entry-preparation pid=%d read=%d error=%d pools=%u\n",
            t->pid,error,runtime.error,runtime.count);
    }
    exec_backings_release(t);
    t->identity=next->value; t->mapped_image=0; t->entered=1;
    CHECK(!md_process_set_dumpable(t->resources.access,!next->secure));
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
    return 0;
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
static long shm_control(struct thread *t,unsigned op,const unsigned long a[4]) {
    struct md_shm *shm=NULL;
    int r=md_ipc_namespace_memory(t->resources.ipc,&shm);
    if (!r && t->phase!=DISPATCHING) r=-EPERM;
    if (op==MD_SHM_STAT || op==MD_SHM_OPEN || op==MD_SHM_ATTACHED || op==MD_SHM_ABORT) r=-EINVAL;
    struct shmid64_ds info;
    return r ? r : md_shm_call(shm,t->shm_space,NULL,&t->identity,t->tgid,t->resources.pid_group,op,a,&info);
}
static long ipc_control(struct thread *t,unsigned operation,unsigned long argument) {
    struct md_ipc_request call={.nr=t->original.regs[8]};
    for (unsigned i=0;i<6;i++) call.args[i]=t->original.regs[i];
    int r=t->phase!=DISPATCHING ? -EPERM : 0;
    if (!r && (call.nr!=SYS_semget && call.nr!=SYS_semctl
            && call.nr!=SYS_semop && call.nr!=SYS_semtimedop && call.nr!=SYS_msgget
            && call.nr!=SYS_msgctl && call.nr!=SYS_msgsnd && call.nr!=SYS_msgrcv)) r=-EINVAL;
    struct md_ipc *ipc=NULL;
    if (!r) r=md_ipc_namespace_semaphores(t->resources.ipc,&ipc);
    long value=r;
    struct md_ipc_transport *channel=&t->ipc_transport;
    if (!r && operation==MD_IPC_CLOSE) {
        md_ipc_transport_close(channel);
        if (argument) {
            value=md_ipc_cancel(ipc,&t->ipc_pending); t->ipc_cancel=0;
        }
    } else if (!r && operation==MD_IPC_EXECUTE && channel->packet) {
        if (t->ipc_cancel) {
            value=t->ipc_cancel; t->ipc_cancel=0;
            int error=md_ipc_cancel(ipc,&t->ipc_pending); if (error) value=error;
        } else value=md_ipc_call(ipc,&t->ipc_pending,t->resources.sem_undo,&t->identity,t->pid,t->tgid,t->resources.pid_group,channel);
    } else if (!r) value=-EINVAL;
    return value;
}
static long resource_control(struct thread *t,unsigned service,unsigned operation,const unsigned long args[4]) {
    if (service==MD_GUEST_SHM) return shm_control(t,operation,args);
    if (service==MD_GUEST_IPC) return ipc_control(t,operation,args[0]);
    return -EINVAL;
}
static void resource_request(struct thread *t,const struct seccomp_notif *q) {
    unsigned long args[4]={q->data.args[2],q->data.args[3],q->data.args[4],q->data.args[5]};
    long value=q->data.instruction_pointer!=RAW_GATE ? -EPERM
        : resource_control(t,q->data.args[0],q->data.args[1],args);
    struct seccomp_notif_resp reply={.id=q->id};
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
static int machine_discovery(struct thread *t) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    return registry && !registry->disabled && t->entered && filter_entry_eligible(t)
        && !t->cooperative_blocked && !t->debug_options && !md_memory_exposed(t->memory)
        && !t->notification_continued && !t->admission_policy_valid && t!=entry_fence_owner;
}
static int machine_context(struct thread *t,const struct ptrace_syscall_info *info) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (t->phase!=IDLE || !registry) return 0;
    /* Revocation stops discovery, not recovery of an already resident entry. */
    return !info || (machine_discovery(t) && md_entry_supported((int)info->seccomp.nr))
        || md_svc_registry_context(registry,info->instruction_pointer,info->stack_pointer);
}
static int machine_observe(struct thread *t,const struct user_pt_regs *regs) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!machine_discovery(t) || !md_entry_supported((int)regs->regs[8])
            || regs->pc<4 || regs->pc==RAW_GATE) return 0;
    /* Publication belongs to the instruction, not this invocation's fast-path
     * eligibility. The common entry checks arguments, policy and ownership on
     * every call; original-context operations retain their Base owners. */
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    unsigned sites=diagnostics ? registry->count : 0;
    int published=md_svc_registry_direct(registry,t->pid,access ? access->memory : -1,regs->pc-4,regs->regs[8]);
    if (diagnostics && registry->count>sites) {
        const struct md_svc_site *site=&registry->sites[registry->count-1];
        if (site->state==MD_SVC_PUBLISHED)
            TRACE("PROBE machine-entry pid=%d svc=%#llx nr=%llu publication=%s\n",
                t->pid,regs->pc-4,regs->regs[8],published==1 ? "first" : "extended");
        else TRACE("PROBE machine-entry-unavailable pid=%d svc=%#llx nr=%llu pools=%u\n",
                t->pid,regs->pc-4,regs->regs[8],registry->pool_count);
    }
    return published==1;
}
static uint32_t interception_decision(struct thread *,const struct seccomp_data *);
static int machine_native_entry(struct thread *t,const struct user_pt_regs *regs,unsigned long *cookie) {
    if (t->cooperative_blocked || t->debug_options || md_memory_exposed(t->memory)) return 0;
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int entry=md_svc_machine_native_entry(registry,regs);
    /* Installing a policy revokes this very alias. Recover its original site
     * before acquiring the fence, so alias retirement cannot replay and drain
     * an already-owned installation a second time. */
    if (entry && md_seccomp_filter_call(regs->regs[8],regs->regs[0],regs->regs[1])) return 0;
    if (!entry || !t->application_filter) return entry;
    /* The alias has restored the caller's stack and arguments. Prove its
     * original policy, then retain the existing completion owner instead of
     * cancelling and replaying. Guest observation actions still need replay. */
    if (t->admission_policy_valid || !filter_entry_eligible(t)
            || md_seccomp_policy_may_trace(t->policy) || md_seccomp_policy_may_notify(t->policy)) return 0;
    struct seccomp_data call={.nr=regs->regs[8],.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=md_svc_registry_call_origin(registry,regs->pc)};
    memcpy(call.args,regs->regs,sizeof(call.args));
    uint32_t decision;
    if (md_seccomp_policy_eval(t->policy,&call,&decision) || decision!=SECCOMP_RET_ALLOW) return 0;
    if (*cookie==MD_INTERCEPT_TASK) {
        /* The policy mirror's gate can supersede an ordinary TRACE cookie.
         * Recover it with the same classifier as channel and notification
         * ingress. Notification/error owners retain original-site fallback. */
        decision=interception_decision(t,&call);
        unsigned action=decision&SECCOMP_RET_ACTION_FULL;
        if (action!=SECCOMP_RET_ALLOW && action!=SECCOMP_RET_TRACE) return 0;
        *cookie=action==SECCOMP_RET_ALLOW ? MD_INTERCEPT_NATIVE : decision&SECCOMP_RET_DATA;
    }
    return entry;
}
static void notification_release(struct thread *t) {
    /* No operation was admitted. A signal or lazy-entry retry must discard
     * both metadata export and typed descriptor discovery before re-entry. */
    release_operation(t);
    md_proc_file_release(t->proc_file); t->proc_file=NULL;
}
static int notification_unexecuted(const struct thread *t) {
    return t->phase==DELEGATE_CANCEL || (t->phase==EXPORT_CANCEL && t->metadata.previous==IDLE);
}
static int notification_entry_context(struct thread *t,const struct user_pt_regs *regs,
        const struct seccomp_data *call) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!md_svc_registry_context(registry,regs->pc,regs->sp)) return 0;
    uintptr_t origin=md_svc_registry_call_origin(registry,call->instruction_pointer);
    return md_svc_registry_call_origin(registry,regs->pc)!=origin
        && md_svc_registry_call_origin(registry,regs->pc+4)!=origin;
}
static void notification_interrupted(struct thread *t) {
    if (!notification_unexecuted(t)) return;
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
    t->entry_selectors=NULL; t->entry_selector_revision=0; t->entry_route_flags=0;
    t->entry_control=NULL;
    t->entry_resume=NULL;
    t->entry_effect=NULL;
    t->entry_execution=NULL;
    t->entry_kernel_context=0;
    if (t->cooperative_buffer.pool) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->cooperative_buffer,&span) && span.size>=sizeof(struct md_entry_channel));
        struct md_entry_channel *c=span.data;
        if (statistics) {
            statistics->entry_local_native+=atomic_load_explicit(&c->local_native,memory_order_relaxed);
            for (unsigned i=0;i<MD_CONTEXT_COUNT;++i)
                statistics->entry_local_contexts[i]+=atomic_load_explicit(&c->local_contexts[i],memory_order_relaxed);
        }
        md_memory_admission_detach(t->memory,&c->memory);
        CHECK(!md_buffer_release(&t->cooperative_buffer));
    }
}
static void cooperative_routes_publish(struct thread *t) {
    if (!t->cooperative_buffer.pool) return;
    unsigned flags=(t->pid_filter ? MD_ROUTE_PID : 0)|(t->host_signals ? MD_ROUTE_SIGNALS : 0);
    if (!checkpoint_restricted(t->pid)) flags|=MD_ROUTE_ADMITTED_KERNEL;
    uint64_t revision=md_fd_selector_revision(t->fd_selector);
    if (t->entry_selectors==t->fd_selector && t->entry_selector_revision==revision && t->entry_route_flags==flags) return;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->cooperative_buffer,&span) && span.size>=sizeof(struct md_entry_channel));
    struct md_entry_channel *c=span.data;
    struct md_fd_selector_view view;
    md_fd_selector_export(t->fd_selector,&view);
    md_interception_route_publish(&c->routes,&view,flags);
    t->entry_selectors=t->fd_selector; t->entry_selector_revision=revision; t->entry_route_flags=flags;
}
static void cooperative_memory_publish(struct thread *t,int eligible) {
    if (!t->cooperative_buffer.pool) return;
    cooperative_routes_publish(t);
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->cooperative_buffer,&span) && span.size>=sizeof(struct md_entry_channel));
    struct md_entry_channel *c=span.data;
    atomic_store_explicit(&c->policy,md_memory_snapshot_address(t->memory,
        md_seccomp_policy_identity(t->policy)),memory_order_release);
    md_memory_admission_publish(t->memory,&c->memory,eligible && !md_shm_space_used(t->shm_space));
}
static void buffer_setup(struct thread *t, const struct seccomp_notif *q) {
    size_t page=(size_t)getpagesize();
    int readonly=q->data.args[2]==1;
    size_t size=readonly ? (sizeof(struct md_seccomp_image)+page-1)/page*page*MD_SECCOMP_IMAGE_SLOTS
        : (sizeof(union md_adapter_frame)+page-1)/page*page
            *(MD_ADAPTER_BUFFER_SLOTS+MD_ADAPTER_CONTROL_SLOTS+MD_ADAPTER_CHANNEL_SLOTS);
    struct seccomp_notif_resp reply={.id=q->id};
    int preparing=readonly && t->filter_preflight && t->phase==DISPATCHING && t->filter_install
        && md_resource_order_state(&t->filter_install->memory)==MD_RESOURCE_ORDER_RUNNING;
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
        struct md_fd_object *pin=md_fd_native_pin_new(opened.fd,NULL);
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
static void signal_origin_capture(void *context,const siginfo_t *info,struct md_signal_origin *out) {
    struct thread *t=context;
    *out=(struct md_signal_origin){0};
    if (!md_signal_child_notice(info)) return;
    const struct md_signal_origin *saved=&t->signal_origin;
    if (!md_signal_origin_matches(saved,info)) saved=t->signal_delivery.child_origin;
    if (!md_signal_origin_matches(saved,info)) saved=md_signal_pending_origin(t->signal_pending,info);
    if (saved) { *out=*saved; return; }
    struct proc_owner owner=proc_owner(info->si_pid);
    if (!owner.identity) return;
    struct md_pid_identity *pid=owner.resources ? owner.resources->pid
        : md_task_registry_retired(tasks,info->si_pid).pid;
    *out=(struct md_signal_origin){.info=*info,.uid=owner.identity->uid.real,.valid=1,
        .reboot_signal=md_pid_identity_reboot_signal(pid)};
    out->count=md_pid_identity_export(pid,out->numbers);
}
static void signal_origin_translate(struct thread *t,const struct md_signal_origin *origin,siginfo_t *info) {
    info->si_pid=md_pid_numbers_visible(origin->numbers,origin->count*sizeof(*origin->numbers),
        md_pid_identity_namespace(t->resources.pid),info->si_pid);
    info->si_uid=md_identity_visible_id(&t->identity,0,origin->uid);
    if (origin->reboot_signal && (info->si_code==CLD_EXITED || info->si_code==CLD_KILLED || info->si_code==CLD_DUMPED)) {
        info->si_code=CLD_KILLED; info->si_status=origin->reboot_signal;
    }
}
static void signal_origin_consumed(struct thread *t,const siginfo_t *info) {
    md_signal_pending_consumed(t->signal_pending,info);
    if (md_signal_origin_matches(&t->signal_origin,info)) t->signal_origin.valid=0;
    if (md_signal_origin_matches(t->signal_delivery.child_origin,info)) {
        free(t->signal_delivery.child_origin); t->signal_delivery.child_origin=NULL;
    }
}
static int pending_child(void *context,const siginfo_t *info) {
    if (info->si_signo!=SIGCHLD) return 0;
    *(siginfo_t *)context=*info; return 1;
}
static int child_signals_capture(struct thread *t) {
    /* wait consumes a zombie, not its pending SIGCHLD. Preserve only the
     * kernel's actual coalesced slots before releasing registry ownership. */
    if (t) for (int scope=MD_SIGNAL_PRIVATE;scope<=MD_SIGNAL_SHARED;++scope) {
        siginfo_t info={0};
        int present=md_task_pending_scan_scope(native_trace,t->pid,
            scope==MD_SIGNAL_SHARED ? PTRACE_PEEKSIGINFO_SHARED : 0,pending_child,&info);
        if (present==-ESRCH) return 0;
        CHECK(present>=0);
        struct md_signal_origin origin={0};
        if (present) signal_origin_capture(t,&info,&origin);
        *md_signal_pending_child(t->signal_pending,scope)=origin;
    }
    return 1;
}
static void process_reap(struct thread *t,pid_t child) {
    if (child_signals_capture(t)) md_task_registry_reap(tasks,child);
    else md_signal_pending_child_changed(t->signal_pending);
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
    if (t->proc_image_fd>=0 || t->buffer_open.fd>=0) return -EBUSY;
    int fd=md_proc_file_descriptor(file);
    if (fd<0) { TRACE("PROBE proc-carrier pid=%d error=%d\n",t->pid,fd); return fd; }
    struct md_fs_buffer_open opened={.fd=fd,.flags=(unsigned)md_proc_file_flags(file),
        .object=md_fd_object_retain(md_proc_file_object(file))};
    if (!buffer_service) {
        /* Direct-root launches have no filesystem worker. Use the same
         * retained result and rights transfer as their network providers. */
        struct md_buffer_span span;
        int error=md_buffer_span(&t->buffer,&span);
        if (!error && span.size<sizeof(struct md_fs_buffer_frame)) error=-EINVAL;
        if (error) { md_fs_buffer_open_release(&opened); return error; }
        struct md_fs_buffer_frame *frame=span.data;
        frame->reply=(struct md_fs_response){.delivery=MD_FS_REPLIED,.result={.fd=-1,
            .selectors=md_fd_object_selectors(opened.object)}};
        frame->transfer=MD_FS_BUFFER_RIGHTS;
        t->buffer_open=opened;
        buffer_reply(t,0); return 0;
    }
    struct md_fs_result result={.fd=-1};
    int error=md_fs_buffer_adopt(buffer_service,t->pid,t->resources.fs,MD_FS_OPEN,opened.flags,
        &t->buffer,&result,&opened,&t->buffer_call);
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
static int control_file_query(struct md_fd_object *object,unsigned operation,struct md_control_file *out) {
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
static void proc_file_request(struct thread *t, const struct md_proc_open_request *q) {
    struct { long val; int error; } reply={0};
    unsigned operation=(unsigned)q->operation;
    if (t->phase!=DISPATCHING
            || (q->view && operation!=MD_PROC_FILE_OPEN)) reply.error=-EPERM;
    else if (operation==MD_PROC_FILE_PIDFD_NAMESPACE) {
        if (!t->proc_file || t->original.regs[8]!=SYS_ioctl
                || (int)t->original.regs[0]!=q->process || t->original.regs[1]!=q->selector
                || !md_pidfd_namespace_command((unsigned)q->selector) || q->flags!=O_CLOEXEC) reply.error=-EPERM;
        else reply.error=proc_file_publish(t,t->proc_file);
        if (!reply.error) return;
    } else if (operation==MD_PROC_FILE_NAMESPACE_QUERY) {
        const struct md_namespace_reference *ref=md_proc_file_namespace_reference(t->proc_file);
        unsigned command=(unsigned)q->selector;
        if (!ref || t->original.regs[8]!=SYS_ioctl || (int)t->original.regs[0]!=q->process
                || t->original.regs[1]!=command
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
        else if (t->proc_image_fd>=0 || q->view) reply.error=-EINVAL;
        else if (target->executable_origin || !target->executable) reply.error=-EXDEV;
        else {
            struct md_control_file view=md_process_executable_view(target->executable);
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
        else if ((!source && !md_proc_control_kind(kind)) || t->proc_image_fd>=0) reply.error=-EINVAL;
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
            TRACE("PROBE proc-control open pid=%d target=%d kind=%u flags=%x provider=%d\n",
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
    TRACE("PROBE proc-control request pid=%d operation=%u result=%lld error=%d\n",
        t->pid,operation,(long long)reply.val,reply.error);
    buffer_reply(t,reply.error ? reply.error : reply.val);
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
    errno=error; fail(reason,line);
}
static long cooperative_bind(struct thread *t) {
    if (!buffer_service || !broker || !t->entered || t->phase!=IDLE
            || t->cooperative_blocked || !filter_entry_eligible(t)) return -EOPNOTSUPP;
    if (t->cooperative_buffer.pool)
        return (long)md_memory_buffer_address(t->memory,&t->cooperative_buffer);
    int error=md_memory_channel_take(t->memory,&t->cooperative_buffer);
    if (error) return error;
    struct md_buffer_span span;
    error=md_buffer_span(&t->cooperative_buffer,&span);
    if (!error && span.size<sizeof(struct md_entry_channel)) error=-ENOBUFS;
    if (!error) error=md_broker_cooperative_bind(broker,t->pid,span.data,abi.buffer_admit);
    t->entry_selectors=NULL;
    if (!error) cooperative_memory_publish(t,machine_discovery(t));
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
    if (o->loans[o->lending].retirement && o->loans[o->lending].number<0) return MD_FS_BUFFER_EPOLL_LOAN;
    if (!o->received && md_fd_order_state(&o->files)==MD_RESOURCE_ORDER_RUNNING)
        return MD_FS_BUFFER_EPOLL_ADMIT;
    return 0;
}
static struct md_seccomp_policy *admission_policy(const struct thread *t) {
    return t->admission_policy_valid ? t->admission_policy : t->policy;
}
static int guest_policy_boundary(const struct thread *t,const struct seccomp_data *call) {
    /* Helpers execute an already admitted original call. Their scratch
     * arguments and service PC are not another application policy request. */
    uintptr_t pc=call->instruction_pointer;
    return t->entered && t->phase==IDLE && pc!=RAW_GATE && pc!=abi.task_return
        && !(pc>=abi.code_begin && pc<abi.code_end);
}
static int guest_trace_rejected(struct thread *t,const struct seccomp_data *call) {
    if (!guest_policy_boundary(t,call) || !md_seccomp_policy_may_trace(admission_policy(t))) return 0;
    uint32_t decision;
    CHECK(!md_seccomp_policy_eval(admission_policy(t),call,&decision));
    /* Guest TRACE cookies are not service selectors. USER_NOTIF also outranks
     * TRACE, so both interception channels need this original-call decision.
     * Guest TRACESECCOMP observation is not enabled: Linux returns ENOSYS. */
    return (decision&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_TRACE;
}
static void guest_notification_discard(struct thread *t) {
    struct guest_notification *n=t->notification;
    if (!n) return;
    md_seccomp_notification_release(n->message);
    md_seccomp_notify_release(n->queue);
    if (n->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    free(n); t->notification=NULL;
}
static int guest_notification_publish(struct thread *t,const struct seccomp_notif *q) {
    struct guest_notification *n=t->notification;
    struct md_seccomp_notification_kernel kernel={.listener=listener,.id=q->id,.borrowed=1,
        .files=t->resources.files,.identity=&t->identity};
    int error=md_seccomp_notify_begin_kernel(n->queue,t->pid,t->resources.pid,&n->original,&kernel,&n->message);
    TRACE("PROBE guest-notification pid=%d nr=%d result=%d\n",t->pid,n->original.nr,error);
    md_seccomp_notify_release(n->queue); n->queue=NULL;
    if (error) {
        struct seccomp_notif_resp reply={.id=q->id,.error=error};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
    return error;
}
static int guest_notification_begin(struct thread *t,const struct seccomp_data *data,
        const struct user_pt_regs *regs,const struct seccomp_notif *kernel,const siginfo_t *host_signal) {
    if (!guest_policy_boundary(t,data) || !md_seccomp_policy_may_notify(admission_policy(t))) return 0;
    uint32_t decision; const struct md_seccomp_policy *winner=NULL;
    CHECK(!md_seccomp_policy_decide(admission_policy(t),data,&decision,&winner));
    if ((decision&SECCOMP_RET_ACTION_FULL)!=SECCOMP_RET_USER_NOTIF) return 0;
    CHECK(!t->notification);
    struct md_fd_object *object=md_seccomp_policy_listener(winner);
    struct md_seccomp_notify *queue=md_seccomp_notify_retain(md_seccomp_listener_notifications(object));
    md_fd_object_release(object);
    struct guest_notification *n=queue ? calloc(1,sizeof(*n)) : NULL;
    int error=queue ? n ? 0 : -ENOMEM : -ENOSYS;
    if (!error) {
        t->notification=n; n->queue=queue; n->original=*data;
        TRACE("PROBE guest-notification-start pid=%d nr=%d pc=%llx host=%d\n",
            t->pid,data->nr,(unsigned long long)data->instruction_pointer,!!host_signal);
        if (host_signal) n->host_signal=*host_signal;
        if (kernel) {
            /* Cancel only the private transport before publishing any
             * public ID. The same owned carrier then serves both TRACE
             * and USER_NOTIF entries, including concurrent TSYNC. */
            n->promoting=1;
            delegate_begin(t,kernel);
            return 1;
        }
        t->phase=GUEST_NOTIFICATION; t->original=*regs;
        error=md_task_checkpoint_suspend(&checkpoints,t->pid);
        if (error>=0) { CHECK(!error); n->checkpoint_suspended=1; }
        if (!error) {
            struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_SECCOMP_LISTENER,1},
                .flags=MD_TASK_CALL_INTERRUPTIBLE};
            error=md_task_call_begin(&n->call,native_trace,t->pid,
                host_signal ? MD_TASK_CALL_USER : MD_TASK_CALL_SECCOMP,regs,
                abi.task_call,abi.task_return,&args);
            if (error && n->call.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return 1; }
            if (!error) { t->kernel_stopped=0; return 1; }
        }
        guest_notification_discard(t); t->phase=IDLE;
    } else md_seccomp_notify_release(queue);
    if (kernel) {
        struct seccomp_notif_resp reply={.id=kernel->id,.error=error};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    } else { struct user_pt_regs returned=*regs; return_value(t,&returned,error); }
    return 1;
}
static int guest_notification_kernel(struct thread *t,const struct seccomp_notif *q) {
    struct guest_notification *n=t->notification;
    if (!n || !md_task_call_notification(&n->call,q)) return 0;
    if (n->continuing_native) {
        struct seccomp_notif_resp reply={.id=q->id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        return 1;
    }
    CHECK(!n->message);
    guest_notification_publish(t,q); return 1;
}
static unsigned long guest_notification_route(struct thread *,const struct seccomp_data *);
static int guest_notification_event(struct thread *t,int status) {
    struct guest_notification *n=t->notification;
    if (!n || n->promoting) return 0;
    unsigned event=(unsigned)status>>16;
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) return 0;
    if (task_call_signal(t,&n->call,status)) return 1;
    if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)!=SIGTRAP) return 0;
    TRACE("PROBE guest-notification-event pid=%d phase=%d status=%x stage=%u\n",
        t->pid,t->phase,status,n->call.stage);
    struct seccomp_notif_resp response;
    int replied=n->message ? md_seccomp_notification_result(n->message,&response) : 0;
    int continuing=replied>0 && (response.flags&SECCOMP_USER_NOTIF_FLAG_CONTINUE);
    if (replied>0 && (!continuing || n->continuing_native)) CHECK(!md_task_call_retire_entry(&n->call));
    else if (continuing && !n->host_signal.si_signo)
        CHECK(!md_task_call_preserve_admission(&n->call));
    int ready=md_task_call_event(&n->call,status);
    if (ready<0) {
        if (ready!=-ESRCH) {
            struct user_pt_regs observed={0}; registers(t->pid,&observed);
            fprintf(stderr,"Shroot: notification call failed pid=%d error=%d status=%x stage=%u boundary=%d pc=%llx nr=%llu value=%lld\n",
                t->pid,ready,status,n->call.stage,n->call.boundary,observed.pc,observed.regs[8],(long long)observed.regs[0]);
        }
        CHECK(ready==-ESRCH); t->resume_lost=1; return 1;
    }
    if (!ready) { t->kernel_stopped=0; return 1; }
    if (continuing && !n->continuing_native && !n->host_signal.si_signo
            && guest_notification_route(t,&n->original)==MD_INTERCEPT_NATIVE) {
        struct md_task_call_arguments args={.number=n->original.nr,.flags=MD_TASK_CALL_INTERRUPTIBLE};
        memcpy(args.value,n->original.args,sizeof(args.value));
        n->continuing_native=1;
        int error=md_task_call_again(&n->call,&args);
        if (!error) error=md_task_call_retire_entry(&n->call);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
        else t->kernel_stopped=0;
        return 1;
    }
    long result=n->continuing_native ? n->call.result
        : replied>0 ? response.error ? response.error : response.val : n->call.result;
    int continued_native=n->continuing_native;
    if (continued_native) continuing=0;
    siginfo_t host_signal=n->host_signal;
    struct seccomp_data call=n->original;
    uint64_t mask=n->call.mask;
    enum md_task_call_boundary boundary=n->call.boundary;
    guest_notification_discard(t); t->phase=IDLE;
    struct user_pt_regs original=t->original;
    if ((replied<=0 || continued_native) && md_syscall_restarting(result)) {
        restart_boundary(t,&original,mask,RESTART_SIGNAL,boundary);
        return 1;
    }
    if (continuing) {
        if (host_signal.si_signo) {
            if (!host_policy_dispatch(t,&original,&call)) host_policy_deliver(t,&original,&host_signal,1);
            return 1;
        }
        t->notification_continued=1; return 0;
    }
    return_task_value(t,&original,result,boundary); return 1;
}
static const struct md_interception_routes *interception_routes(void) {
    /* Immutable programs belong to this supervisor's bootstrap ABI. Reuse
     * them for channel ingress and USER_NOTIF CONTINUE alike. */
    static struct md_interception_routes routes;
    if (!routes.base.count) CHECK(!md_interception_routes_init(&routes,&abi));
    return &routes;
}
static uint32_t interception_decision(struct thread *t,const struct seccomp_data *call) {
    struct md_fd_selector_view view;
    md_fd_selector_export(t->fd_selector,&view);
    unsigned flags=(t->pid_filter ? MD_ROUTE_PID : 0)|(t->host_signals ? MD_ROUTE_SIGNALS : 0);
    uint32_t decision;
    CHECK(!md_interception_routes_eval(interception_routes(),&view,flags,call,&decision));
    return decision;
}
static unsigned long guest_notification_route(struct thread *t,const struct seccomp_data *call) {
    uint32_t decision=interception_decision(t,call);
    switch (decision&SECCOMP_RET_ACTION_FULL) {
    case SECCOMP_RET_ALLOW: return MD_INTERCEPT_NATIVE;
    case SECCOMP_RET_TRACE: return decision&SECCOMP_RET_DATA;
    case SECCOMP_RET_USER_NOTIF: return MD_INTERCEPT_DISPATCH;
    default: CHECK(0); return MD_INTERCEPT_NATIVE;
    }
}
static void handle_notification(const struct seccomp_notif *request) {
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
    if (t->signal_control && t->signal_control->returning) signal_control_discard(t);
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
        t->buffer_admission_id=q.id; t->buffer_admission_pending=1;
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
        int error=md_notification_rebind(listener,&previous,&next);
        if (error) {
            errno=-error;
            notification_conflict(t,"notification-rebind",&q,NULL,__LINE__);
        }
        t->metadata.request=q;
        if (statistics) ++statistics->notification_rebounds;
        TRACE("PROBE notification rebound pid=%d phase=%d nr=%d\n",t->pid,t->phase,q.data.nr);
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
        if (!t->memory_notification) { t->memory_notification=malloc(sizeof(*request)); CHECK(t->memory_notification); }
        else CHECK(t->memory_notification->id==request->id
            || (ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&t->memory_notification->id)<0 && errno==ENOENT));
        *t->memory_notification=*request; return;
    }
    if (!buffer_admission && guest_trace_rejected(t,&q.data)) {
        struct seccomp_notif_resp reply={.id=q.id,.error=-ENOSYS};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        return;
    }
    if (!buffer_admission && guest_notification_begin(t,&q.data,NULL,&q,NULL)) return;
    if (q.data.nr==SYS_rt_sigprocmask) {
        CHECK(!q.data.args[1]);
        if (!t->host_signals || (!t->metadata_restart && !t->host_signal_blocked && !t->signal_delivery.active) || !q.data.args[2]
                || q.data.args[3]!=sizeof(uint64_t)) {
            struct seccomp_notif_resp reply={.id=q.id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        } else {
            /* A replay is admitted under a temporary shield. Queries must
             * expose the interrupted application's mask, not that shield. */
            uint64_t mask=t->metadata_restart
                ? md_signal_view_mask(t->metadata_restart->mask,t->host_signal_blocked) : t->signal_delivery.active
                ? md_signal_view_mask(t->signal_delivery.mask,t->host_signal_blocked) : t->signal_handler_mask;
            ssize_t copied=t->metadata_restart || t->signal_delivery.active || t->signal_handler_mask_known
                ? md_task_copy_direct(t->pid,q.data.args[2],&mask,sizeof(mask),1) : -EACCES;
            if (copied>=0 || copied==-EFAULT) {
                struct seccomp_notif_resp reply={.id=q.id,
                    .error=copied==sizeof(t->signal_handler_mask) ? 0 : -EFAULT};
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
    if (t->proc_delivery.files.table && t->proc_delivery.notification!=q.id) proc_deliver(t);
    if (q.data.nr==SYS_gettid || replay) {
        int value=-EPERM;
        if (buffer_admission) {
            if (purpose==MD_ENTRY_BIND) {
                long address=cooperative_bind(t);
                TRACE("PROBE entry-bind pid=%d result=%ld phase=%d blocked=%d filter=%d\n",
                    t->pid,address,t->phase,t->cooperative_blocked,t->application_filter);
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
            else if (purpose==MD_BUFFER_CONTROL_CAPTURE_ADMIT && t->control_capture.count
                    && t->buffer_descriptor_operation==MD_BUFFER_CONTROL_CAPTURE
                    && md_fd_order_state(&t->buffer_descriptor_order)==MD_RESOURCE_ORDER_RUNNING)
                value=md_fd_capture_admit(&t->control_capture,t->resources.files,listener,q.id);
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
        else if (q.data.args[0]==MD_GUEST_SHM || q.data.args[0]==MD_GUEST_IPC) resource_request(t,&q);
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
        if (t->phase != IDLE) fprintf(stderr,
            "Shroot: unexpected notification pid=%d phase=%d nr=%d pc=%#llx fd=%llu resume=%d signal=%d packet=%d pending=%d\n",
            t->pid,t->phase,q.data.nr,q.data.instruction_pointer,q.data.args[0],
            t->kernel_resume,t->signal_control ? t->signal_control->signal : 0,
            t->signal_control && t->signal_control->entry_packet,
            t->signal_control && t->signal_control->entry_packet
                ? (int)atomic_load_explicit(&t->signal_control->entry_packet->pending,memory_order_acquire) : -1);
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
    CHECK(t);
    if (t->broker_completion) notification_conflict(t,"duplicate-completion",NULL,result,__LINE__);
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
static void metadata_restart_return(struct thread *t,long value) {
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
static int signal_observe(struct thread *t,struct md_task_signal_stop *stop,int with_registers) {
    if (t->resume_lost) return 0;
    int error=md_task_signal_info(t->pid,stop);
    if (!error && with_registers) error=md_task_signal_registers(t->pid,stop);
    if (!error && with_registers && abi.signal_entry_committed && stop->regs.pc==abi.signal_entry_committed) {
        stop->regs.pc=stop->regs.regs[17];
        if (!set_registers(t->pid,&stop->regs)) return 0;
    }
    if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
    return !error;
}
static int signal_replace_info(struct thread *t,struct md_task_signal_stop *stop,const siginfo_t *info) {
    CHECK(stop->has_info);
    if (!memcmp(&stop->info,info,sizeof(*info))) return 1;
    if (!tracee_request(t,PTRACE_SETSIGINFO,NULL,(void *)info,"signal-info-update")) return 0;
    stop->info=*info;
    return 1;
}
static int interrupted_metadata_event(struct thread *t,int status,const struct rusage *usage,
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
            && !(t->host_signals && regs.regs[8]==SYS_rt_sigprocmask && !regs.regs[1])) return 0;
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
    restart->requeued_signal=t->retained_signal;
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
        if (!md_signal_synchronous(&info)) {
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
            /* This is original-site kernel admission, not a service call.
             * A shield must not turn a caught seccomp TRAP into forced death.
             * Preserve its native SIGSYS bit until the exact syscall exit. */
            uint64_t mask=(md_signal_blockable_mask()&~MD_SIGNAL_SYS_BIT)|(r->mask&MD_SIGNAL_SYS_BIT);
            if (!signal_mask(t,mask,"metadata-admission-mask")) return 1;
            t->signal_shielded=1;
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
            if (t->phase!=IDLE || t->buffer.pool || t->broker_event.held)
                notification_conflict(t,"completed-notification-adoption",NULL,NULL,__LINE__);
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
static int entry_reply(struct thread *t,long value,uintptr_t extra) {
    struct md_entry_channel *channel=t->entry_control;
    if (!channel) return 0;
    CHECK(!t->entry_effect);
    t->entry_control=NULL;
    channel->result_extra=extra;
    md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,value);
    return 1;
}
static int entry_pending(const struct thread *t) {
    return t->entry_control && t->entry_control->call.reserved==MD_ENTRY_EXECUTION_BEGIN;
}
static int entry_context(struct thread *t,enum md_execution_context reason) {
    if (!entry_pending(t)) return 0;
    CHECK(t->phase==IDLE && !t->entry_execution && reason<MD_CONTEXT_COUNT);
    t->entry_control->execution_action=MD_EXECUTION_CONTEXT;
    if (statistics) ++statistics->entry_contexts[reason];
    return entry_reply(t,reason,0);
}
static void native_resume(struct thread *t) {
    if (!entry_pending(t)) { resume(t->pid,0); return; }
    t->entry_control->execution_action=MD_EXECUTION_KERNEL;
    if (statistics) ++statistics->entry_native;
    CHECK(entry_reply(t,0,0));
}
static void execution_begin(struct thread *t,const struct user_pt_regs *r,const char *mask_reason);
static void control_return(struct thread *t,struct user_pt_regs *regs,long value,uintptr_t extra) {
    if (entry_reply(t,value,extra)) return;
    regs->regs[0]=value; regs->regs[1]=extra;
    if (set_registers(t->pid,regs)) resume(t->pid,0);
}
static void return_value(struct thread *t, struct user_pt_regs *regs, long value) {
    if (t->entry_control && t->entry_control->call.reserved==MD_ENTRY_EXECUTION_BEGIN) {
        struct md_entry_channel *channel=t->entry_control;
        CHECK(t->phase==IDLE && !t->entry_execution);
        CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&t->identity,&t->resources));
        channel->execution_action=MD_EXECUTION_RESULT;
        if (statistics) ++statistics->entry_completions;
        CHECK(entry_reply(t,value,0));
        return;
    }
    if (t->clone_prepared && regs->regs[8]==SYS_clone3) {
        t->original=*regs; t->phase=OBSERVING;
    }
    if (!skip(t->pid)) return;
    regs->regs[0] = (uint64_t)value;
    if (set_registers(t->pid, regs)) resume(t->pid, 0);
}
static void return_scalar(struct thread *t,long value) {
    /* NT_PRSTATUS accepts a prefix. Leave argument registers, PC and PSTATE
     * untouched when the admitted call only needs a return value. */
    uint64_t result=(uint64_t)value;
    struct iovec io={&result,sizeof(result)};
    if (skip(t->pid) && tracee_request(t,PTRACE_SETREGSET,(void *)NT_PRSTATUS,&io,"set-result"))
        resume(t->pid,0);
}
static void return_task_value(struct thread *t,struct user_pt_regs *regs,long value,
        enum md_task_call_boundary boundary) {
    /* A borrowed call can consume the application's kernel exit. Publish that
     * exit exactly once; an intact entry still gets its ordinary native stop. */
    if (boundary==MD_TASK_CALL_EXIT && t->debug_call_active) t->debug_adapter=1;
    return_value(t,regs,value);
}
static int debugger_parent(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (t) {
        long parent=md_task_parent(pid);
        if (parent>=0) t->parent=(pid_t)parent;
    }
    return t ? t->parent : 0;
}
static void debugger_reaped(pid_t observer,pid_t pid,int native_wait) {
    if (native_wait) process_reap(find_thread(observer),pid);
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
static int debugger_signal_mask(pid_t pid,int writing,uint64_t *mask) {
    struct thread *t=find_thread(pid);
    if (!t) return -ESRCH;
    uint64_t native=writing ? *mask : 0;
    if (writing && t->host_signals) native&=~MD_SIGNAL_SYS_BIT;
    if (native_trace(writing ? PTRACE_SETSIGMASK : PTRACE_GETSIGMASK,pid,
            (void *)sizeof(native),&native)<0) return -errno;
    if (writing) {
        t->signal_handler_mask_known=0;
        if (t->host_signals) t->host_signal_blocked=!!(*mask&MD_SIGNAL_SYS_BIT);
    } else *mask=t->host_signals ? md_signal_view_mask(native,t->host_signal_blocked) : native;
    return 0;
}
static uid_t debugger_visible_uid(pid_t observer,pid_t sender,uid_t fallback) {
    struct thread *t=find_thread(observer);
    return t ? process_signal_uid(t,sender,fallback) : fallback;
}
static void debugger_signal_origin(pid_t recipient,const siginfo_t *info,struct md_signal_origin *origin) {
    struct thread *t=find_thread(recipient);
    if (t) {
        signal_origin_capture(t,info,origin);
        if (origin->valid) signal_origin_consumed(t,info);
    }
}
static void debugger_signal_translate(pid_t observer,const struct md_signal_origin *origin,siginfo_t *info) {
    struct thread *t=find_thread(observer);
    if (t) signal_origin_translate(t,origin,info);
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
    int allowed=from->tgid==to->tgid || md_process_may_inspect(&from->identity,&to->identity,filesystem,
        to->resources.access);
    if (!allowed) TRACE("PROBE inspect-denied caller=%d target=%d filesystem=%d uid=%u/%u gid=%u/%u target-uid=%u/%u/%u target-gid=%u/%u/%u caps=%#llx/%#llx target-caps=%#llx dumpable=%d same-userns=%d\n",
        caller,pid,filesystem,from->identity.uid.real,from->identity.uid.fs,
        from->identity.gid.real,from->identity.gid.fs,
        to->identity.uid.real,to->identity.uid.effective,to->identity.uid.saved,
        to->identity.gid.real,to->identity.gid.effective,to->identity.gid.saved,
        (unsigned long long)from->identity.caps.permitted,(unsigned long long)from->identity.caps.effective,
        (unsigned long long)to->identity.caps.permitted,md_process_dumpable(to->resources.access),
        from->identity.user_namespace==to->identity.user_namespace);
    return allowed;
}
static int debugger_allowed(pid_t caller, pid_t pid) {
    return inspect_allowed(caller,pid,0);
}
static int debugger_traceme(pid_t parent,pid_t child) {
    struct thread *from=find_thread(parent), *to=find_thread(child);
    return from && to && !md_domain_restricted(from->resources.fs)
        && md_identity_traceme(&from->identity,&to->identity);
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
static int quiesce_task_add(struct md_task_quiesce *barrier,struct thread *t,int stopped) {
    /* A vfork parent is kernel-suspended until its child releases the mm;
     * TRACEVFORKDONE then stops it before any userspace continuation.
     * Waiting for another INTERRUPT can deadlock the child's own continuation.
     * Existing interrupt/birth owners supply their event without another request. */
    return md_task_quiesce_add(barrier,t->pid,
        stopped || t->kernel_stopped || t->listening || t->vfork_child,
        t->interrupt_pending || birth_stop_pending(t) ? 0 : t->kernel_resume);
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
static void restart_boundary(struct thread *t,const struct user_pt_regs *original,uint64_t mask,
        enum call_restart policy,enum md_task_call_boundary boundary) {
    if (boundary!=MD_TASK_CALL_SECCOMP) { restart_call(t,original,mask,policy); return; }
    /* This entry already owns orig_x0. A second gate would overwrite it with
     * the result of the skipped original syscall before SA_RESTART runs. */
    t->original=*original; t->mask=mask; t->call_restart=policy;
    if (shield(t) && skip(t->pid)) { t->phase=CALL_RESTART_EXIT; resume(t->pid,0); }
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
    unsigned index=regs.regs[8]==SYS_waitid ? 1 : 0;
    if ((action==MD_DEBUG_WAIT_PROBE || action==MD_DEBUG_WAIT_REPROBE)
            && (regs.regs[8]==SYS_wait4 || regs.regs[0]==P_PID || regs.regs[0]==P_PGID) && (pid_t)regs.regs[index]>0) {
        pid_t mapped=native_pid(t,(pid_t)regs.regs[index]);
        if (!mapped) {
            debugger_wait_release(t); t->phase=IDLE;
            md_debugger_wait_complete(debugger,pid,-ECHILD,NULL); return;
        }
        regs.regs[index]=mapped;
    }
    if ((action==MD_DEBUG_WAIT_PROBE || action==MD_DEBUG_WAIT_REPROBE)
            && regs.regs[8]==SYS_waitid && !regs.regs[2]) {
        retry_clear(t,RETRY_DEBUG_WAIT);
        if (!t->debug_wait_output) t->debug_wait_output=calloc(1,sizeof(*t->debug_wait_output));
        struct debug_wait_output *out=t->debug_wait_output;
        int error=out ? md_buffers_take(md_memory_buffers(t->memory),&out->buffer) : -ENOMEM;
        if (error==-EAGAIN) {
            out->registers=*original; out->action=action;
            t->phase=DEBUG_WAIT_BUFFER; retry_wait(t,RETRY_DEBUG_WAIT); return;
        }
        if (error) {
            debugger_wait_release(t); t->phase=IDLE;
            md_debugger_wait_complete(debugger,pid,error,NULL); return;
        }
        struct md_buffer_span span; CHECK(!md_buffer_span(&out->buffer,&span));
        memset(span.data,0,sizeof(siginfo_t));
        regs.regs[2]=md_memory_buffer_address(t->memory,&out->buffer); CHECK(regs.regs[2]);
    }
    if (action==MD_DEBUG_WAIT_RESTART) { restart_call(t,original,t->debug_wait_mask,RESTART_SIGNAL); return; }
    if (action==MD_DEBUG_WAIT_PARK) {
        /* EVENT_WAIT: native interruptible poll delivers arbitrary guest
         * signals. Inferior events cancel it with INTERRUPT; no periodic wake
         * or descriptor is needed, and ordinary execution never enters here. */
        t->original=*original; t->debug_wait_waking=0;
        if (!shield(t)) return;
        regs.pc=abi.task_call; regs.regs[8]=SYS_ppoll;
        for (unsigned i=0;i<6;i++) regs.regs[i]=0;
        if (set_registers(pid,&regs)) { t->phase=DEBUG_PARK_ENTRY; resume(pid,0); }
        return;
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
static void debugger_wait_retry(struct thread *t) {
    struct debug_wait_output *out=t->debug_wait_output;
    CHECK(out && t->phase==DEBUG_WAIT_BUFFER);
    struct user_pt_regs regs=out->registers;
    debugger_wait(t->pid,&regs,out->action);
}
enum { COPY_GROUPS, COPY_CAP_HEADER, COPY_CAP_DATA, COPY_CAP_VERSION, COPY_LIMIT_INPUT, COPY_LIMIT_OUTPUT };
static void identity_copy_next(struct thread *t);
static int process_limit_capture(struct thread *,struct thread *);
static int process_limit_apply(struct thread *t,const struct rlimit *next,struct rlimit *old) {
    pid_t pid=t->original.regs[8]==SYS_prlimit64 ? (pid_t)t->original.regs[0] : 0;
    if (pid) pid=native_pid(t,pid);
    else pid=t->pid;
    struct thread *target=find_thread(pid);
    struct md_task_retired_view retired=md_task_registry_retired(tasks,pid);
    const struct md_identity *id=target ? &target->identity : retired.identity;
    if (!id) return -ESRCH;
    unsigned resource=t->original.regs[t->original.regs[8]==SYS_prlimit64 ? 1 : 0];
    int error=md_process_limit(target ? target->resources.process : retired.process,
        &t->identity,id,target && target->tgid==t->tgid,resource,next,old,pid);
    if (target && !next && resource<RLIM_NLIMITS && resource!=RLIMIT_NOFILE && resource!=RLIMIT_NPROC
            && (error==-EACCES || error==-EPERM)
            && !md_process_limit_access(&t->identity,id,target->tgid==t->tgid))
        error=process_limit_capture(t,target);
    return error;
}
static void identity_copy_finish(struct thread *,int);
static void identity_limit_finish(struct thread *t,int error,const struct rlimit *old) {
    const struct user_pt_regs *r=&t->original;
    uintptr_t output=r->regs[8]==SYS_prlimit64 ? r->regs[3] : r->regs[8]==SYS_getrlimit ? r->regs[1] : 0;
    t->identity_stage=COPY_LIMIT_OUTPUT;
    if (!error && (output || r->regs[8]==SYS_getrlimit)) {
        memcpy(t->identity_buffer,old,sizeof(*old));
        t->identity_loading=0; t->identity_address=output;
        t->identity_count=sizeof(*old)/sizeof(uint32_t); t->identity_copied=0;
        identity_copy_next(t);
    } else identity_copy_finish(t,error);
}
static void identity_copy_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->original;
    if (!error && t->identity_stage==COPY_LIMIT_INPUT) {
        struct rlimit next,old;
        memcpy(&next,t->identity_buffer,sizeof(next));
        error=process_limit_apply(t,t->identity_count ? &next : NULL,&old);
        if (error!=-EINPROGRESS) identity_limit_finish(t,error,&old);
        return;
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
        if (entry_context(t,MD_CONTEXT_COPY)) return 1;
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
    if (nr==SYS_prctl && ((int)r->regs[0]==PR_GET_DUMPABLE || (int)r->regs[0]==PR_SET_DUMPABLE)) {
        value=(int)r->regs[0]==PR_GET_DUMPABLE ? md_process_dumpable(t->resources.access)
            : md_process_set_dumpable(t->resources.access,r->regs[1]);
        return_value(t,r,value); return 1;
    }
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
        if (entry_context(t,MD_CONTEXT_COPY)) return 1;
        t->identity_buffer = calloc(6, sizeof(uint32_t));
        if (!t->identity_buffer) { return_value(t, r, -ENOMEM); return 1; }
        t->identity_stage = COPY_CAP_HEADER; t->identity_address = r->regs[0];
        t->identity_loading = 1; t->identity_count = 2; t->identity_copied = 0; t->original = *r;
        if (!save_signal_mask(t,&t->mask,"get-mask")
                || !shield(t) || !skip(t->pid)) return 1;
        identity_copy_next(t); return 1;
    case SYS_getgroups: case SYS_setgroups:
        if (!entry_context(t,MD_CONTEXT_COPY)) identity_groups_call(t,r);
        return 1;
    case SYS_getuid: case SYS_geteuid: case SYS_getgid: case SYS_getegid:
        value=md_identity_scalar_result(&t->identity,nr); break;
    case SYS_getresuid: case SYS_getresgid: {
        if (entry_context(t,MD_CONTEXT_COPY)) return 1;
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
                CHECK(!md_broker_task(broker, member->pid, 0, RAW_GATE, abi.watch_return,NULL,0));
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
static void fd_policy_release(struct thread *t) {
    if (t->fd_policy_order.queue && md_resource_order_cancel(&t->fd_policy_order)==1)
        CHECK(!md_resource_order_complete(&t->fd_policy_order));
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
    fd_policy_release(t);
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
    fd_policy_release(t);
}
static void fd_native_start(struct thread *t);
static void mount_copy_complete(struct md_mount_copy_request *request, int actor, int error) {
    struct thread *t=find_thread(actor);
    if (!t || t->mount_copy!=request) return;
    t->mount_copy=NULL;
    if (!error) error=md_mount_copy_take(request,&t->resource_change.next.mounts,&t->resource_change.next.fs);
    if (t->fd_job==MD_FD_USE && t->original.regs[8]==SYS_setns) {
        setns_complete(t,error); return;
    }
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
    if (!t->fd_order.table && t->fd_policy_order.queue) {
        enum md_resource_order_state state=md_resource_order_state(&t->fd_policy_order);
        if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&t->fd_policy_order));
        else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_FD); return; }
        CHECK(md_fd_order_begin(t->resources.files,&t->fd_order,fd_wakeup)>=0);
    }
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
        : t->fd_job==MD_FD_USE ? t->original.regs[8]==SYS_setns
        : !t->fd_job && (t->fd_cwd || md_clone_call(r->regs[8])
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
        control_return(t,r,result,r->regs[1]);
        return;
    }
    int error=0;
    if (r->regs[8]==SYS_execve || r->regs[8]==SYS_execveat)
        error=md_resources_exec_prepare(&t->resources,t->identity.user_namespace,&t->resource_change);
    else if (r->regs[8]==SYS_unshare
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
    if (!admission && md_clone_call(r->regs[8]) && !(t->cloning&CLONE_INTO_CGROUP)
            && t->resources.cgroup && !t->resource_change.next.cgroup) {
        t->resource_change.next.cgroup=md_cgroup_member_fork(t->resources.cgroup);
        if (!t->resource_change.next.cgroup) admission=-errno;
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
    if (entry_context(t,md_clone_call(r->regs[8]) ? MD_CONTEXT_BIRTH : MD_CONTEXT_DESCRIPTOR)) return;
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
    if (md_memory_readonly_buffers(t->memory) && !t->memory_order.queue
            && (md_clone_call(r->regs[8]) || r->regs[8]==SYS_execve || r->regs[8]==SYS_execveat)) {
        CHECK(!t->fd_policy_order.queue);
        CHECK(md_memory_order_begin(t->memory,&t->fd_policy_order,fd_wakeup)>=0);
        /* EVENT_WAIT: filter commit/publication precedes birth or exec. Birth
         * releases at its event, not the later vfork return; the child may exec
         * while its parent is still waiting. Native data IO acquires no turn. */
        fd_start(t); return;
    }
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
static int fd_duplicate_prepared(struct thread *t,const struct user_pt_regs *r,unsigned long cookie) {
    long nr=r->regs[8];
    if (cookie!=MD_INTERCEPT_DISPATCH || t->phase!=IDLE || !t->ready
            || !t->resources.files || t->fd_job || t->fd_running || t->fd_order.table
            || t->fs_order.fs || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    if (nr!=SYS_dup && nr!=SYS_dup3 && !(nr==SYS_fcntl
            && ((unsigned)r->regs[1]==F_DUPFD || (unsigned)r->regs[1]==F_DUPFD_CLOEXEC))) return 0;
    /* All possible destinations already have every provider selector. There
     * is no adapter discovery or filter installation left to do. The normal
     * mutation owner still serializes the table, retires loans and publishes
     * the copied authority only after the actual kernel result. */
    return md_fd_selector_has_mask(t->fd_selector,MD_FD_SANDBOX_SELECTORS,-1)==1;
}
static int fd_native_io(struct thread *t,const struct ptrace_syscall_info *info) {
    if (info->arch!=AUDIT_ARCH_AARCH64
            || t->fd_job || t->fd_running || t->fd_order.table || t->fs_order.fs
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    unsigned long nr=info->seccomp.nr;
    int poll=nr==SYS_ppoll || nr==SYS_pselect6;
    int epoll=nr==SYS_epoll_pwait || nr==SYS_epoll_pwait2;
    if (t->host_signals && ((poll && info->seccomp.args[nr==SYS_ppoll ? 3 : 5])
            || (epoll && info->seccomp.args[4]))) return 0;
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
static struct task_action *task_action_new(struct thread *t) {
    CHECK(!t->action);
    struct task_action *owner=calloc(1,sizeof(*owner));
    if (!owner) return NULL;
    owner->requester=t; t->action=owner; ++task_action_calls;
    md_pending_add(&task_action_queue,&owner->pending);
    return owner;
}
static void task_action_release(struct task_action *owner) {
    CHECK(owner!=task_action_execution);
    md_pending_cancel(&owner->pending);
    CHECK(!md_buffer_release(&owner->buffer));
    md_fd_selector_release(owner->group);
    md_fd_selector_release(owner->selected_group);
    md_process_member_release(owner->capture_capacity);
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
    if (owner->capture) {
        owner->capture->result.value=-ECANCELED;
        capture_complete(owner->capture,owner->capture->result);
    }
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
    struct capture_owner *capture=owner->capture; owner->capture=NULL;
    owner->retired=NULL;
    if (t) { retry_clear(t,RETRY_TASK_ACTION); t->action=NULL; }
    task_action_release(owner);
    if (waiter) readiness_loan_closed(waiter,result);
    if (retirement.complete) retirement.complete(retirement.context,(int)result);
    if (retired) retired(retired_context,result);
    if (capture) {
        if (result<0) capture->result.value=result;
        capture_complete(capture,capture->result);
    }
    if (t && !retired && !capture) {
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
    if (owner->capture_capacity) {
        int error=md_process_nofile_release(owner->capture_capacity,actor->pid);
        if (error==-ESRCH) {
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer!=actor && peer->pid && peer->tgid==actor->tgid && !peer->terminal_stop) {
                    error=md_process_nofile_release(owner->capture_capacity,peer->pid);
                    if (error!=-ESRCH) break;
                }
        }
        CHECK(!error || error==-ESRCH);
        md_process_member_release(owner->capture_capacity); owner->capture_capacity=NULL;
    }
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
    } else if (!owner->capture && !result && !retry) {
        if (owner->pid_filter) {
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer->tgid==actor->tgid) peer->pid_filter=1;
        } else result=md_fd_selector_installed(owner->group,owner->kind,
            owner->selector_number,owner->selector_instructions);
        if (!result) for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer->tgid==actor->tgid) cooperative_routes_publish(peer);
    }
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
    if (owner->capture && !retry) { task_action_finish(owner,result); return; }
    if (!owner->requester || (result<0 && !retry)) task_action_finish(owner,result);
    else if (result>0) {
        if (!owner->remote && !owner->selected_group && !owner->pid_filter) task_action_finish(owner,result);
        else {
            owner->rejected_group=actor->tgid; owner->rejected_task=(pid_t)result;
            retry_wait(owner->requester,RETRY_TASK_ACTION);
        }
    } else if ((owner->selected_group || owner->pid_filter) && !retry) task_action_finish(owner,0);
    else retry_wait(owner->requester,RETRY_TASK_ACTION);
}
static void capture_borrowed_complete(void *context,struct md_task_helper_result result) {
    struct task_action *owner=context;
    CHECK(owner==task_action_execution && owner->capture);
    owner->actor->kernel_stopped=1;
    owner->capture->result=result;
    task_action_call_complete(result.value,0);
}
static int signal_control_parked(const struct thread *);
static int task_checkpoint_borrow(struct thread *actor,int *owned) {
    /* A parked signal wait already owns checkpoint decoding, but its native
     * call can be restoring a SYSEMU entry when interrupted. The decoder's
     * suspended snapshot predates that call; the live resume owns skip state. */
    if (signal_control_parked(actor) && actor->signal_control->checkpoint_suspended)
        return actor->kernel_resume==PTRACE_SYSEMU;
    int boundary=md_task_checkpoint_suspend(&checkpoints,actor->pid);
    if (boundary>=0) *owned=1;
    return boundary;
}
static void task_action_call_start(struct task_action *owner) {
    struct thread *actor=owner->actor;
    struct user_pt_regs saved=owner->continuation;
    enum md_task_call_boundary boundary=owner->boundary;
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
    int checkpoint=task_checkpoint_borrow(actor,&owner->checkpoint_suspended);
    if (checkpoint<0) { task_action_call_complete(checkpoint,0); return; }
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
    if (owner->capture) {
        struct capture_owner *capture=owner->capture;
        int error=md_buffers_take_reserved(md_memory_buffers(actor->memory),&owner->buffer);
        if (!error && owner->capture_capacity_needed) {
            error=md_process_nofile_reserve(actor->resources.process,actor->pid,2);
            if (!error) owner->capture_capacity=md_process_member_retain(actor->resources.process);
        }
        if (!error) {
            actor->terminal_observation=1; actor->trace_options_dirty=1;
            error=debugger_options(actor->pid,actor->debug_options);
        }
        if (!error) {
            struct md_task_helper_request request={.kind=MD_TASK_HELPER_CAPTURE,
                .path=capture->target.path,.flags=capture->target.flags,
                .capture_metadata=capture->metadata,.directory_limit=capture->directory_limit};
            error=md_task_helper_borrow(helpers,actor->pid,actor->tgid,boundary,&saved,&abi,
                actor->memory,&owner->buffer,&request,capture_borrowed_complete,owner);
        }
        if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
        capture->started=1; actor->kernel_stopped=0; return;
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
        if (owner->pid_filter) {
            struct md_pid_filter_program program;
            md_pid_filter_build(RAW_GATE,abi.task_return,&program);
            _Static_assert(MD_PID_FILTER_INSTRUCTIONS<=MD_FD_FILTER_INSTRUCTIONS,"task filter capacity");
            image->code.count=program.count;
            memcpy(image->code.code,program.code,program.count*sizeof(*program.code));
            error=md_task_filter_begin_program(&owner->filter,native_trace,actor->pid,boundary,
                &saved,abi.task_call,abi.task_return,image,address);
        } else error=md_fd_filter_build(owner->kind,owner->number,&gates,&image->code);
        if (!error && !owner->pid_filter) {
            owner->selector_instructions=image->code.count;
            owner->selector_number=md_fd_selector_target(owner->group,owner->number,image->code.count);
            if (owner->selector_number< -1) error=owner->selector_number;
        }
        if (!error && !owner->pid_filter) error=md_task_filter_begin(&owner->filter,native_trace,actor->pid,boundary,
            &saved,abi.task_call,abi.task_return,span.data,address,owner->kind,owner->selector_number,&gates);
    }
    if (error && owner->filter.task.stage) {
        CHECK(error==-ESRCH); actor->resume_lost=1; return;
    }
    if (error) { task_action_call_complete(error,error==-EAGAIN); return; }
    actor->kernel_stopped=0;
}
static int selector_peer_ready(struct thread *peer,unsigned kind,int number) {
    int ready=md_fd_selector_has(peer->fd_selector,kind,number);
    if (ready || !peer->vfork_child) return ready;
    /* The kernel will not run this parent until its vfork child releases the
     * mm. Retain only bounded selector requirements, never hold the child's
     * publication hostage to a task-affine call in that suspended parent. */
    if (!peer->deferred_selector) peer->deferred_selector=md_fd_selector_new();
    if (!peer->deferred_selector) return -ENOMEM;
    ready=md_fd_selector_has(peer->deferred_selector,kind,number);
    if (ready<0) return ready;
    if (!ready) {
        int error=md_fd_selector_mark(peer->deferred_selector,kind,number);
        if (error) return error;
    }
    return 1;
}
static int selector_has(struct thread *t,unsigned kind,int number) {
    int ready=md_fd_selector_has(t->fd_selector,kind,number);
    if (ready<=0 || number<0) return ready;
    for (struct thread *peer=threads;peer;peer=peer->next)
        if (peer->pid && peer->born && peer->resources.files==t->resources.files
                && peer->fd_selector!=t->fd_selector) {
            ready=selector_peer_ready(peer,kind,number);
            if (ready<=0) return ready;
        }
    return 1;
}
static int task_files_queued(const struct thread *p) {
    /* The table turn has not begun, so none of these owners may execute
     * task-affine work yet. Keep eligibility tied to resource state, not to
     * a syscall family or a scheduler's retry bit. */
    const struct md_fd_order *turns[]={&p->fd_order,&p->proc_lookup,&p->memory_files,
        &p->metadata.files,&p->buffer_descriptor_order,&p->buffer_return.files,
        &p->proc_delivery.files,p->readiness ? &p->readiness->files : NULL,
        p->readiness ? &p->readiness->loans[p->readiness->lending].delivery.files : NULL,
        p->outgoing ? &p->outgoing->files : NULL,p->receive ? &p->receive->files : NULL,
        p->mapping ? &p->mapping->files : NULL,p->signal_control ? &p->signal_control->files : NULL};
    for (unsigned i=0;i<sizeof(turns)/sizeof(*turns);++i)
        if (md_fd_order_state(turns[i])==MD_RESOURCE_ORDER_QUEUED) return 1;
    return md_fs_buffer_waiting_files(p->buffer_call)
        || md_fs_buffer_waiting_files(p->metadata.stat_call);
}
static int signal_control_parked(const struct thread *t) {
    const struct signal_control *o=t->signal_control;
    return t->phase==SIGNAL_CONTROL && o && !o->scratch.pool && !o->memory.queue
        && !o->files.table && md_task_signal_control_parked(&o->control);
}
static int task_parked(const struct thread *t) {
    return t->phase==WATCH_READ || t->phase==IPC_WAITING || t->phase==SOCKET_WAITING || t->phase==FILE_WAITING
        || t->phase==CONTROL_WAITING || t->phase==CLOSE_WAITING || t->phase==EPOLL_WAIT;
}
static int retained_task_held(const struct thread *t) {
    /* Registration and terminal cleanup own the exact stopped continuation,
     * including the interval before their deferred event is replayed. */
    return t->robust_registering || t->robust_terminal;
}
static int task_continuation_available(const struct thread *t) {
    return !retained_task_held(t) && memory_available(t->pid);
}
static int task_completion_available(pid_t pid) {
    struct thread *t=find_thread(pid);
    return t && task_continuation_available(t);
}
static int task_action_borrowable(struct thread *p,struct thread *requester) {
    if (retained_task_held(p)) return 0;
    /* A queued table user is stopped before its operation. Other candidates
     * retain their exact kernel continuation. Never borrow an active worker,
     * mutation or newborn awaiting its first stop. */
    int waiter=p->kernel_stopped && (task_files_queued(p)
        || (p->action && !task_action_call(p->action)->stage));
    int mapping_active=p->mapping
        && md_fd_order_state(&p->mapping->files)!=MD_RESOURCE_ORDER_QUEUED;
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
        && !mapping_active && !p->memory_capture && !p->fd_running && (!traced || debug_held || debug_running)
        && !md_task_helper_owns(helpers,p->pid,p->pid)
        && (waiter || debug_held || (p->freezer_resume && debugger_at_boundary(p))
            /* A provider owns the pending operation, not this native wait.
             * Borrow and restore its exact boundary without cancelling it. */
            || (!p->kernel_stopped && (p->phase==IDLE || p->phase==DISPATCHING
            || task_parked(p) || signal_control_parked(p) || p->phase==PROCESS_OBSERVING)));
}
static int task_action_native_borrow(struct task_action *owner,struct thread *actor,int *stopped) {
    if (retained_task_held(actor)) return -EBUSY;
    *stopped=actor->kernel_stopped;
    int borrowed=md_broker_native_borrow(broker,actor->pid,stopped);
    if (borrowed<0) return borrowed;
    owner->broker_borrowed=borrowed;
    return 0;
}
static void task_action_start(struct thread *t) {
    retry_clear(t,RETRY_TASK_ACTION);
    struct task_action *owner=t->action;
    CHECK(owner && !task_action_call(owner)->stage);
    if (owner->loan) { loan_start(owner); return; }
    if (quiescence_busy(NULL) || retained_task_held(t)) { retry_wait(t,RETRY_TASK_ACTION); return; }
    if (owner->pid_filter) {
        if (t->pid_filter) { task_action_finish(owner,0); return; }
        owner->actor=t; task_action_execution=owner;
        task_action_call_start(owner); return;
    }
    if (owner->capture) {
        struct md_fs_task_subject *target=&owner->capture->subject;
        struct thread *actor=find_thread(target->task);
        struct md_task_credentials credentials;
        int error=md_task_registry_credentials(tasks,target->task,&credentials);
        if (!error) {
            if (credentials.retired || credentials.generation!=target->generation) error=-ENOENT;
            md_task_credentials_release(&credentials);
        }
        if (!error && (!actor || !actor->born || actor->terminal_stop)) error=-ENOENT;
        if (!error) {
            struct md_proc_path path=md_proc_path_for(owner->capture->target.path,0,0);
            int public_dentry=(owner->capture->target.flags&(O_PATH|O_NOFOLLOW))==(O_PATH|O_NOFOLLOW)
                && md_proc_public_task_dentry(path)
                && path.process==actor->pid && !md_domain_restricted(t->resources.fs);
            error=owner->capture->authorize ? owner->capture->authorize(t,actor)
                : public_dentry || proc_allowed(t->pid,actor->pid) ? 0 : -EACCES;
        }
        if (error) { task_action_finish(owner,error); return; }
        if (!task_action_borrowable(actor,t)) { retry_wait(t,RETRY_TASK_ACTION); return; }
        owner->capture_capacity_needed=md_process_native_nofile(actor->resources.process)<2;
        if (owner->capture_capacity_needed) {
            /* Only a zero/one-slot group needs extra native capacity. Keep
             * every guest participant stopped until the private table is gone;
             * normal limits never acquire this group-wide barrier. */
            if (md_task_helpers_group_busy(helpers,actor->tgid)) { retry_wait(t,RETRY_TASK_ACTION); return; }
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer!=actor && peer->tgid==actor->tgid
                        && !task_action_borrowable(peer,t)) { retry_wait(t,RETRY_TASK_ACTION); return; }
        }
        int stopped=0;
        error=task_action_native_borrow(owner,actor,&stopped);
        if (error==-EBUSY) { retry_wait(t,RETRY_TASK_ACTION); return; }
        if (error) { task_action_finish(owner,error); return; }
        owner->actor=actor; owner->remote=1; task_action_execution=owner;
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!quiesce_task_add(task_action_barrier,actor,stopped));
        if (owner->capture_capacity_needed) for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer!=actor && peer->tgid==actor->tgid)
                CHECK(!quiesce_task_add(task_action_barrier,peer,0));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
        task_action_call_start(owner); return;
    }
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
            && md_fd_order_state(&t->buffer_return.files)!=MD_RESOURCE_ORDER_RUNNING
            && !(t->signal_control && md_fd_order_state(&t->signal_control->files)==MD_RESOURCE_ORDER_RUNNING)
            && !(t->readiness && md_fd_order_state(&t->readiness->files)==MD_RESOURCE_ORDER_RUNNING)) {
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
            ready=selector_peer_ready(peer,owner->kind,owner->number);
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
    int stopped=candidate->kernel_stopped;
    if (candidate!=t) {
        int error=task_action_native_borrow(owner,candidate,&stopped);
        if (error==-EBUSY || error==-ESRCH) { retry_wait(t,RETRY_TASK_ACTION); return; }
        if (error) { task_action_finish(owner,error); return; }
    }
    owner->actor=candidate; owner->remote=candidate!=t;
    owner->group=md_fd_selector_fork(candidate->fd_selector,1);
    task_action_execution=owner;
    if (owner->remote) {
        if (!task_action_barrier) { task_action_barrier=md_task_quiesce_new(quiesce_trace); CHECK(task_action_barrier); }
        CHECK(!quiesce_task_add(task_action_barrier,candidate,stopped));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
    }
    task_action_call_start(owner);
}
static void pid_filter_begin(struct thread *t,const struct user_pt_regs *continuation,
        void (*complete)(struct thread *,long)) {
    struct task_action *owner=task_action_new(t);
    if (!owner) {
        if (complete) complete(t,-ENOMEM);
        else { struct user_pt_regs returned=*continuation; return_value(t,&returned,-ENOMEM); }
        return;
    }
    owner->pid_filter=1; owner->number=-1;
    owner->continuation=*continuation; owner->complete=complete;
    task_action_start(t);
}
static void selector_deferred_complete(struct thread *t,long result) {
    if (result<0) {
        fprintf(stderr,"Shroot: cannot admit resumed shared file table: %s\n",strerror((int)-result));
        cancel_tree(125); return;
    }
    enum md_fd_selector_kind kind; int number;
    int missing=md_fd_selector_missing(t->deferred_selector,t->fd_selector,&kind,&number);
    if (missing<0) { selector_deferred_complete(t,missing); return; }
    if (!missing) {
        md_fd_selector_release(t->deferred_selector); t->deferred_selector=NULL;
        t->phase=IDLE; resume(t->pid,0); return;
    }
    struct user_pt_regs saved;
    if (!registers(t->pid,&saved)) return;
    struct task_action *owner=task_action_new(t);
    if (!owner) { selector_deferred_complete(t,-ENOMEM); return; }
    owner->continuation=saved; owner->boundary=MD_TASK_CALL_EXIT;
    owner->kind=kind; owner->number=number;
    owner->selected_group=md_fd_selector_fork(t->fd_selector,1);
    owner->complete=selector_deferred_complete;
    task_action_start(t);
}
static void task_action_progress(struct md_pending_node *node,void *context) {
    (void)context;
    struct task_action *owner=(struct task_action *)node;
    /* Retain blocked work before dispatch: completion may free this owner or
     * cancel another queued action. New actions wait for the next pass. */
    md_pending_add(&task_action_queue,node);
    if (!task_action_execution && owner->requester && owner->requester->pid)
        task_action_start(owner->requester);
}
static void task_action_schedule(void) {
    if (!task_action_calls || memory_execution) return;
    if (task_action_execution) {
        if (!task_action_call(task_action_execution)->stage
                && !(task_action_execution->capture && task_action_execution->capture->started))
            task_action_call_start(task_action_execution);
        return;
    }
    for (struct task_action *s=loan_retirements,*next;s;s=next) {
        next=s->next; loan_start(s);
        if (task_action_execution) return;
    }
    CHECK(!md_pending_dispatch(&task_action_queue,task_action_progress,NULL));
}
static int loan_borrow(struct task_action *owner,struct thread *p,int *alive,int *stopped) {
    if (!p || !p->pid || p->resources.files!=owner->loan_table || p->terminal_stop || p->resume_lost) return 0;
    if (retained_task_held(p) || p->signal_return_pending || (p!=owner->requester && p->pid!=owner->retirement.task
            && !task_action_borrowable(p,owner->requester))) {
        *alive=1; return 0;
    }
    int borrowed=task_action_native_borrow(owner,p,stopped);
    if (borrowed==-ESRCH) return 0;
    *alive=1;
    if (borrowed<0) return 0;
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
        CHECK(!quiesce_task_add(task_action_barrier,candidate,stopped));
        CHECK(!md_task_quiesce_stop(task_action_barrier));
    }
    task_action_call_start(owner);
}
static void loan_retire(struct task_action *owner,struct thread *requester,
        struct md_fd_order *turn,void (*complete)(struct thread *,long)) {
    if (md_fd_loan_active_in(owner->loan,owner->loan_table)) md_fd_loan_end(owner->loan);
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
static struct md_task_call *task_call_continuation(struct thread *t) {
    if (t->signal_control && t->signal_control->started && !t->signal_control->retiring)
        return md_task_signal_control_call(&t->signal_control->control);
    if (t->notification && !t->notification->promoting) return &t->notification->call;
    struct filter_install *install=t->filter_install;
    if (install && !install->retiring) {
        if (install->admission.stage && !install->admitted) return &install->admission;
        if (install->copy.scratch) return &install->copy.call;
    }
    return NULL;
}
static void task_group_stop(struct thread *t) {
    /* EVENT_WAIT: preserve the exact operation until kernel SIGCONT or exit.
     * A group stop is not a completion/error of a borrowed syscall. */
    t->listening=1; group_stops++;
    if (native_trace(PTRACE_LISTEN,t->pid,NULL,NULL)) {
        CHECK(errno==ESRCH); t->resume_lost=1;
    } else {
        t->kernel_stopped=0;
        md_debugger_child_changed(debugger,t->pid);
    }
}
static int task_call_signal(struct thread *t,struct md_task_call *call,int status) {
    if (WIFSTOPPED(status) && !((unsigned)status>>16) && WSTOPSIG(status)==SIGSTOP) {
        task_call_resume(t,call,SIGSTOP); return 1;
    }
    if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)!=SIGTRAP) {
        task_group_stop(t); return 1;
    }
    if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
        t->interrupt_pending=0;
        if (t->listening) {
            t->listening=0; group_wakes++;
            md_debugger_child_changed(debugger,t->pid);
        }
        /* A coordinator wake may arrive after another owner borrowed this
         * task. Preserve that owner's exact continuation; only its own wake
         * completes a call boundary. */
        if (!md_task_call_expects_interrupt(call)) {
            if (statistics) ++statistics->stops;
            task_call_resume(t,call,0); return 1;
        }
    }
    if (!WIFSTOPPED(status) || (unsigned)status>>16 || WSTOPSIG(status)==(SIGTRAP|0x80)) return 0;
    siginfo_t info;
    if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"task-call-signal")) return 1;
    if (md_signal_synchronous(&info)) return 0;
    int retained=md_signal_delivery_retain(&t->signal_delivery,t->pid,&info); CHECK(retained>=0);
    task_call_resume(t,call,retained);
    return 1;
}
static int task_action_event(pid_t pid,int status,const struct rusage *usage) {
    struct task_action *owner=task_action_execution;
    if (!owner) return 0;
    struct thread *t=find_thread(pid);
    unsigned event=(unsigned)status>>16;
    if (t!=owner->actor) {
        if (!md_task_quiesce_contains(task_action_barrier,pid)) return 0;
        CHECK(!quiesce_event(task_action_barrier,pid,status,usage)); return 1;
    }
    int helper=owner->capture && owner->capture->started;
    struct md_task_call *call=helper ? md_task_helper_call(helpers,pid) : task_action_call(owner);
    if (!call) return 0;
    if (!call->stage) {
        TRACE("PROBE selector-stop pid=%d status=%x\n",pid,status);
        CHECK(owner->remote && !quiesce_event(task_action_barrier,pid,status,usage)); return 1;
    }
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) {
        if (helper) return 0;
        if (!owner->remote) { t->action=NULL; owner->requester=NULL; }
        task_action_call_complete(-ESRCH,0); return 0;
    }
    if (task_call_signal(t,call,status)) return 1;
    int owned_interrupt=md_task_call_expects_interrupt(call);
    if (helper) return 0;
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
            struct md_task_call_arguments args={.number=SYS_close_range,
                .value={(unsigned long)owner->number,(unsigned long)owner->number,0}};
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
                else if (!task_action_new(t)) result=-ENOMEM;
                else {
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
        else {
            if (t->fs_order.fs) CHECK(!md_fs_order_complete(&t->fs_order));
            CHECK(!md_fd_order_complete(&t->fd_order)); t->fd_job=0;
        }
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
    control_return(t,r,result,r->regs[1]);
}
static void buffer_reply_signal(struct thread *t, long result, const siginfo_t *signal) {
    struct user_pt_regs r=t->buffer_registers;
    if (r.regs[0]==MD_BUFFER_PROC_START || r.regs[0]==MD_BUFFER_EPOLL_WAIT)
        r.regs[1]=md_memory_buffer_address(t->memory,&t->buffer);
    r.regs[0]=result;
    if (t->entry_control) {
        CHECK(!signal);
        CHECK(entry_reply(t,result,r.regs[1])); return;
    }
    if (signal) CHECK(!md_signal_delivery_hold(&t->signal_delivery,signal));
    if (set_registers(t->pid,&r)) resume(t->pid,0);
}
static void buffer_reply(struct thread *t, long result) {
    buffer_reply_signal(t,result,NULL);
}
static int entry_require_stop(struct thread *t,void (*continuation)(struct thread *)) {
    if (!t->entry_control) return 0;
    CHECK(!t->entry_resume && !t->entry_effect);
    struct md_entry_channel *channel=t->entry_control;
    t->entry_control=NULL; t->entry_resume=continuation;
    if (statistics) ++statistics->entry_promotions;
    /* No replay: the caller supplies a kernel continuation for this already
     * owned operation. Its resource request and result remain retained. */
    md_broker_entry_reply(broker,channel,MD_ENTRY_NEEDS_STOP,0);
    return 1;
}
static void entry_effect(struct thread *t,long number,const uint64_t args[6],void (*complete)(struct thread *,long)) {
    CHECK(t->entry_control && !t->entry_resume && !t->entry_effect);
    t->entry_effect=complete;
    if (statistics) ++statistics->entry_effects;
    TRACE("PROBE entry-effect pid=%d nr=%ld args=%llu,%llu,%llu,%llu\n",t->pid,number,
        (unsigned long long)args[0],(unsigned long long)args[1],(unsigned long long)args[2],(unsigned long long)args[3]);
    md_broker_entry_effect(broker,t->entry_control,number,args);
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
static void readiness_evict_park(struct thread *t,int finished,long result) {
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
static void readiness_release(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    if (!o) return;
    readiness_delivery_release(o);
    for (unsigned n=0;n<READINESS_QUEUES;++n) {
        struct readiness_loan *loan=&o->loans[n];
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
static void readiness_request_loan(struct thread *t) {
    struct readiness_owner *o=t->readiness;
    struct readiness_loan *loan=&o->loans[o->lending];
    if (!readiness_restore(t,READINESS_RESTORE_LOAN,0)) return;
    if (!loan->retirement) {
        struct task_action *s=calloc(1,sizeof(*s));
        if (!s) { readiness_reply(t,-ENOMEM); return; }
        s->loan=md_fd_loan_new(t->resources.files);
        if (!s->loan) { free(s); readiness_reply(t,-ENOMEM); return; }
        s->loan_table=md_fd_origins_pin(t->resources.files);
        s->number=-1; loan->retirement=s;
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
        else readiness_progress(waiter);
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
static int fd_loan_evict(struct thread *mutator,struct md_fd_loan *loan,int number) {
    /* An already finished wait queued its own retirement. Yield this not-yet
     * executed mutation behind that close instead of holding up its owner.
     * A fork copy belongs to a different kernel table and closes independently. */
    for (struct task_action *s=loan_retirements;s;s=s->next)
        if (s->loan==loan && s->loan_table==mutator->resources.files && s->number==number) {
            CHECK(!md_fd_order_complete(&mutator->fd_order));
            CHECK(md_fd_order_begin(mutator->resources.files,&mutator->fd_order,fd_wakeup)>=0);
            retry_wait(mutator,RETRY_FD); return 0;
        }
    if (!md_fd_loan_active_in(loan,mutator->resources.files)) {
        struct task_action *retirement=calloc(1,sizeof(*retirement));
        if (!retirement) {
            fd_discard(mutator); return_value(mutator,&mutator->fd_registers,-ENOMEM); return 0;
        }
        int selected=-1;
        CHECK(md_fd_origins_loan_range(&mutator->fd_order,number,number,&selected,&retirement->loan)==1);
        CHECK(selected==number && retirement->loan==loan);
        retirement->number=number;
        retirement->loan_table=md_fd_origins_pin(mutator->resources.files);
        loan_retire(retirement,mutator,&mutator->fd_order,fd_loan_closed);
        return 0;
    }
    for (struct thread *p=threads;p;p=p->next) {
        struct readiness_owner *o=p->readiness;
        if (!p->pid || !o) continue;
        struct readiness_loan *owned=NULL;
        for (unsigned n=0;n<READINESS_QUEUES;++n)
            if (o->loans[n].retirement && o->loans[n].retirement->loan==loan) owned=&o->loans[n];
        if (!owned) continue;
        o->evicting=1;
        if (o->delivery_waiting) readiness_evict_park(p,0,0);
        if (!o->parked) {
            /* EVENT_WAIT: the calling thread leaves its exact native wait
             * before a destructive table mutation. No table lock spans IO. */
            if (p->phase==EPOLL_WAIT && !p->socket_waking) {
                p->socket_waking=1;
                tracee_request(p,PTRACE_INTERRUPT,NULL,NULL,"epoll-loan-revoke");
            }
            retry_wait(mutator,RETRY_FD); return 0;
        }
        struct task_action *retirement=owned->retirement;
        owned->retirement=NULL; owned->number=-1;
        retirement->loan_waiter=p->pid;
        loan_retire(retirement,mutator,&mutator->fd_order,fd_loan_closed);
        return 0;
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
    int ready=fd_loan_evict(t,loan,number);
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
    } else {
        result=md_fd_admission_observed(o->admission,&o->request,result);
        if (!result) for (struct thread *p=threads;p;p=p->next)
            if (p->pid && p->fd_selector==o->request.group) cooperative_routes_publish(p);
    }
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
static void readiness_start(struct thread *t);
static void readiness_start_stopped(struct thread *t) {
    t->readiness->continuation=t->buffer_registers;
    readiness_start(t);
}
static void readiness_shared_done(struct thread *t,long result) {
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->buffer,&span));
    ((struct md_epoll_frame *)span.data)->result=result;
    readiness_reply(t,MD_EPOLL_DONE);
}
static int readiness_shared(struct thread *t) {
    if (!t->entry_control) return 0;
    struct readiness_owner *o=t->readiness;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->buffer,&span));
    struct md_epoll_frame *frame=span.data;
    struct md_control_file provider=md_fd_object_readiness(o->target);
    int native=o->operation ? !o->task_target && (!provider.ops || !provider.ops->poll)
        : !frame->masked && frame->timed && !frame->timeout.tv_sec && !frame->timeout.tv_nsec
            && md_control_epoll_native_wait(o->queue);
    if (native) {
        uint64_t args[6]; memcpy(args,t->original.regs,sizeof(args));
        long number=t->original.regs[8];
        if (number==SYS_epoll_pwait2) { number=SYS_epoll_pwait; args[3]=0; }
        if (!o->operation) { frame->native_output=1; args[2]=frame->capacity; }
        entry_effect(t,number,args,readiness_shared_done);
    } else {
        /* Classification has no effect. Keep the table turn, then continue
         * with the actual C context for native waits, captures or admission. */
        md_fd_object_release(o->queue); o->queue=NULL;
        md_fd_object_release(o->target); o->target=NULL;
        o->classified=0; o->task_target=0;
        CHECK(entry_require_stop(t,readiness_start_stopped));
    }
    return 1;
}
static void readiness_activated(struct md_fd_object *queue) {
    /* Cold admission only: wake the exact waits that entered before this
     * queue gained its virtual transport. Their owner retains the deadline,
     * native result and temporary signal mask. No other task is stopped. */
    for (struct thread *p=threads;p;p=p->next) {
        struct readiness_owner *o=p->readiness;
        if (p->pid && o && o->queue==queue && p->phase==EPOLL_WAIT
                && !p->socket_waking) {
            p->socket_waking=1;
            tracee_request(p,PTRACE_INTERRUPT,NULL,NULL,"epoll-cold-admission");
        }
    }
}
static void readiness_start(struct thread *t) {
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
        enum md_resource_order_state state=md_fd_order_state(&o->files);
        if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(&o->files));
        else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_READINESS); return; }
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
            if (!queue && !t->host_signals) { readiness_reply(t,MD_EPOLL_NATIVE); return; }
            struct md_epoll_frame *frame;
            struct md_buffer_span span;
            error=md_buffer_span(&t->buffer,&span);
            if (error) { readiness_reply(t,error); return; }
            frame=span.data;
            int masked=t->host_signals && frame->masked;
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
        struct md_fd_object *current=md_fd_origins_object(t->resources.files,(int)t->original.regs[0],NULL);
        int same=target==o->target && current==o->queue;
        md_fd_object_release(target); md_fd_object_release(current);
        if (!same) { readiness_reply(t,-EBADF); return; }
        struct md_buffer_span span;
        error=md_buffer_span(&t->buffer,&span);
        struct md_control_actor actor={.pid=t->pid,.signals=t->signal_pending};
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
    if (o->operation) { readiness_start(t); return; }
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
    for (unsigned n=0;n<READINESS_QUEUES;++n) o->loans[n].number=-1;
    if (!t->policy && (t->original.regs[8]==SYS_epoll_pwait || t->original.regs[8]==SYS_epoll_pwait2
            || t->original.regs[8]==SYS_epoll_ctl)) {
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
    if (t->original.regs[8]==SYS_ppoll || t->original.regs[8]==SYS_pselect6 || t->original.regs[8]==SYS_rt_sigsuspend) {
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
    if (!md_poll_wait_virtual(o->poll) && !(t->host_signals && f->wait.masked)) {
        readiness_reply(t,MD_EPOLL_NATIVE); return;
    }
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
    if (event==PTRACE_EVENT_EXIT) md_cgroup_member_account_exit(t->resources.cgroup);
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
    regs.pc=abi.task_call; regs.regs[8]=SYS_ppoll;
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
        CHECK(info.entry.nr==SYS_ppoll && info.instruction_pointer==abi.task_return);
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
    CHECK(next==FILE_WAITING || next==CONTROL_WAITING || next==CLOSE_WAITING || next==EPOLL_CALL
        || next==SOCKET_WAITING || next==IPC_WAITING);
    if (t->host_signals) {
        int error=md_task_call_cancel_entry(native_trace,t->pid,abi.task_return);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; return; }
    } else if (!skip(t->pid)) return;
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
    control_pending_complete(t,result);
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
        t->proc_io.tasks=tasks;
        t->proc_io.oom=md_proc_file_oom(md_proc_file_kind(t->proc_io.file))
            ? md_process_member_oom(owner.process) : NULL;
        struct md_process_limits limits=owner.process ? md_process_limits_value(owner.process) : (struct md_process_limits){0};
        if (owner.identity) md_proc_file_process_state(t->proc_io.file,owner.identity,owner.pid_namespace,
            md_debugger_owner(debugger,owner.pid),!md_proc_file_inspection(md_proc_file_kind(t->proc_io.file))
                || proc_allowed(t->pid,owner.pid),owner.process ? &limits : NULL);
        if (md_proc_file_kind(t->proc_io.file)==MD_PROC_FILE_STATUS) {
            struct thread *target=find_thread(node.process);
            struct md_signal_status view={0};
            if (target && target->signal_shielded) {
                view.blocked_known=UINT64_MAX; view.blocked=target->mask;
            }
            if (target && target->host_signals) {
                view.known=MD_SIGNAL_SYS_BIT;
                view.blocked=md_signal_view_mask(view.blocked,target->host_signal_blocked);
                view.pending=md_signal_pending_has(target->signal_pending,MD_SIGNAL_PRIVATE) ? MD_SIGNAL_SYS_BIT : 0;
                view.shared_pending=md_signal_pending_has(target->signal_pending,MD_SIGNAL_SHARED) ? MD_SIGNAL_SYS_BIT : 0;
                struct md_kernel_action action;
                if (!md_signal_actions_read(target->signal_actions,SIGSYS,&action)) {
                    view.ignored=action.handler==(uintptr_t)SIG_IGN ? MD_SIGNAL_SYS_BIT : 0;
                    view.caught=action.handler!=(uintptr_t)SIG_IGN && action.handler!=(uintptr_t)SIG_DFL
                        ? MD_SIGNAL_SYS_BIT : 0;
                } else view.known=0;
            }
            md_proc_file_signal_state(t->proc_io.file,&view);
        }
        t->proc_io.actor=(struct md_control_actor){.pid=t->pid,.identity=&t->identity,.resources=&t->resources,
            .filesystem=filesystem,.submit=md_fs_worker_control};
        result=md_proc_file_io_execute(&t->proc_io,&t->identity,t->resources.uts,span.data,span.size,(size_t)getpagesize());
        if (result>0 && t->proc_io.oom && t->proc_io.operation==MD_PROC_FILE_WRITE) {
            struct thread *target=find_thread(node.process);
            int minimum=md_proc_file_kind(t->proc_io.file)==MD_PROC_FILE_OOM_SCORE_ADJ
                && md_identity_capable_in(&t->identity,NULL,CAP_SYS_RESOURCE);
            /* This uncommon policy mutation follows shared-mm process groups.
             * vfork borrows memory, not its parent's OOM policy. No syscall
             * polling or extra per-fork policy allocation is needed. */
            if (target && !target->vfork_borrowed) for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer->born && peer->memory==target->memory && !peer->vfork_borrowed) {
                    struct md_process_oom *state=md_process_member_oom(peer->resources.process);
                    if (!state || state==t->proc_io.oom) continue;
                    state->value=t->proc_io.oom->value;
                    if (minimum) state->minimum=t->proc_io.oom->minimum;
                    state->virtualized|=t->proc_io.oom->virtualized;
                }
        }
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
static void buffer_mapping_turn_end(struct buffer_mapping *owner) {
    if (owner->turn.queue && md_resource_order_cancel(&owner->turn)==1)
        CHECK(!md_resource_order_complete(&owner->turn));
    md_memory_release(owner->memory); owner->memory=NULL;
}
static void buffer_mapping_release(struct buffer_mapping *owner) {
    buffer_mapping_turn_end(owner);
    CHECK(!md_buffer_release(&owner->scratch));
    md_buffers_release(owner->buffers);
    md_seccomp_policy_release(owner->policy);
    free(owner);
}
static void buffer_mapping_complete(void *context,struct md_task_helper_result result) {
    struct buffer_mapping *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0 && !result.received);
    if (t && t->buffer_mapping==owner) {
        long error=result.value;
        if (error>=0 && owner->policy) {
            uintptr_t base=(uintptr_t)error;
            struct md_interception_buffer buffer={0};
            struct md_buffer_span span;
            error=md_buffers_take(owner->buffers,&buffer);
            if (!error) error=md_buffer_span(&buffer,&span);
            if (!error) error=md_seccomp_policy_view_write(owner->policy,owner->ancestor,
                span.data,span.size,base,owner->parent_view);
            if (!error) error=md_memory_snapshot_publish(t->memory,
                md_seccomp_policy_identity(owner->policy),owner->buffers,base);
            CHECK(!md_buffer_release(&buffer));
        } else if (error>=0 && owner->clients) {
            uintptr_t base=(uintptr_t)error;
            const struct md_private_memory_access *access=md_memory_private_access(t->memory);
            error=md_svc_registry_client_publish(md_memory_svc(t->memory),t->pid,
                access ? access->memory : -1,owner->client_bucket,base);
            if (!error) error=(long)base;
        } else if (error>=0 && owner->channels)
            error=md_memory_channels_publish(t->memory,owner->buffers,(uintptr_t)error);
        else if (error>=0) error=md_memory_readonly_mapped(t->memory,(uintptr_t)error,
                md_buffers_size(owner->buffers));
        t->buffer_mapping=NULL; t->kernel_stopped=1;
        buffer_mapping_turn_end(owner);
        if (error>=0 && owner->channels) error=cooperative_bind(t);
        if (owner->policy) {
            TRACE("PROBE filter-entry-mapped pid=%d result=%ld\n",t->pid,error);
            filter_entry_complete(t,&owner->continuation);
        } else {
            t->buffer_registers=owner->continuation;
            buffer_reply(t,error);
        }
    }
    buffer_mapping_release(owner);
}
static int buffer_mapping_start(struct thread *t,const struct user_pt_regs *regs,
        struct buffer_mapping *owner) {
    owner->caller=t->pid; owner->continuation=*regs;
    int error=md_buffers_take(md_memory_buffers(t->memory),&owner->scratch);
    if (!error) {
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug_options);
    }
    if (!error) {
        struct md_interception_buffers *buffers=owner->buffers;
        const struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,
            .mapping={.source=md_buffers_fd(buffers),.size=md_buffers_size(buffers),
                .protection=PROT_READ|((owner->channels || owner->clients) ? PROT_WRITE : 0),
                .flags=owner->clients ? MAP_PRIVATE : MAP_SHARED}};
        /* The existing private-table mapper needs no application FD slot.
         * Filter admission or channel expansion owns the address-space turn. */
        error=md_task_helper_begin(helpers,t->pid,t->tgid,regs,&abi,t->memory,&owner->scratch,
            &request,buffer_mapping_complete,owner);
    }
    if (error) {
        buffer_mapping_release(owner);
    } else {
        t->buffer_mapping=owner; t->kernel_stopped=0;
        if (owner->policy) t->phase=FILTER_MAPPING;
    }
    return error;
}
static void buffer_mapping_begin(struct thread *t,const struct user_pt_regs *regs) {
    struct buffer_mapping *owner=calloc(1,sizeof(*owner));
    int error=owner ? 0 : -ENOMEM;
    if (!error) {
        owner->buffers=md_buffers_retain(md_memory_readonly_buffers(t->memory));
        error=buffer_mapping_start(t,regs,owner);
    }
    if (error) buffer_reply(t,error);
}
static void entry_mapping_begin(struct thread *t,const struct user_pt_regs *regs,int clients) {
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    long result=clients ? md_svc_registry_client_space(md_memory_svc(t->memory),t->pid,
        access ? access->memory : -1,(unsigned)regs->regs[1]) : cooperative_bind(t);
    if (result!=-EAGAIN) { buffer_reply(t,result); return; }
    struct buffer_mapping *owner=calloc(1,sizeof(*owner));
    int error=owner ? 0 : -ENOMEM;
    if (!error) {
        owner->channels=!clients; owner->clients=clients; owner->client_bucket=(unsigned)regs->regs[1];
        owner->memory=md_memory_fork(t->memory,1);
        error=owner->memory ? md_memory_order_begin(owner->memory,&owner->turn,NULL) : -ENOMEM;
        /* A busy mapping owner is not a failed registration. Retry through
         * the entry's existing bounded backoff, without parking a new helper. */
        if (!error) error=-EAGAIN;
        else if (error==1) error=md_resource_order_start(&owner->turn);
        if (!error) error=md_buffers_create(clients ? 1 : 16,
            clients ? sizeof(struct md_svc_client_block) : sizeof(struct md_entry_channel),0,0,&owner->buffers);
    }
    if (error) {
        if (owner) buffer_mapping_release(owner);
    } else error=buffer_mapping_start(t,regs,owner);
    if (error) buffer_reply(t,error);
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
static void readiness_native_call(struct thread *t) {
    struct readiness_owner *o=t->readiness;
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
    int error=md_buffer_span(&t->buffer,&span);
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
    uintptr_t address=md_memory_buffer_address(t->memory,&t->buffer);
    struct md_control_actor actor={.pid=t->pid,.signals=t->signal_pending};
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
    int waiting=result ? 0 : (o->poll ? md_poll_wait_task(o->poll,&o->wait,span.data,address,
            completed,value,&actor,&o->native_call,&result)
        : task_descriptor>=0 ? md_epoll_wait_task_affine(&o->wait,md_control_epoll_from_object(o->queue),
            &actor,span.data,address,o->received ? o->loans[READINESS_NATIVE_QUEUE].number : (int)t->original.regs[0],
            task_descriptor,completed,value,&o->native_call,&result)
        : md_epoll_wait_task(&o->wait,md_control_epoll_from_object(o->queue),span.data,address,
            t->original.regs[8],o->received ? o->loans[READINESS_NATIVE_QUEUE].number : (int)t->original.regs[0],
            completed,value,&o->native_call,&result));
    if (o->wait.task_copied) readiness_delivery_release(o);
    if (o->evicting && waiting!=2) {
        readiness_evict_park(t,!waiting,result); return;
    }
    if (!waiting) { readiness_reply(t,result); return; }
    if (waiting==2) {
        o->copy_pending=1; t->phase=DISPATCHING; t->buffer_registers=o->continuation;
        buffer_reply(t,MD_EPOLL_COPY); return;
    }
    if (!o->poll && task_descriptor>=0 && o->wait.blocking && o->native_call.number==SYS_ppoll) {
        /* The adapter's private stack owns these few wait arguments. Release
         * the shared transport slot before sleeping, including with a mask. */
        o->park_pending=1; t->phase=DISPATCHING; t->buffer_registers=o->continuation;
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
            o->park_pending=1; t->phase=DISPATCHING; t->buffer_registers=o->continuation;
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
static void readiness_progress(struct thread *t) {
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
static void capture_complete(void *context, struct md_task_helper_result result) {
    struct capture_owner *owner=context;
    TRACE("PROBE capture-complete caller=%d subject=%d path=%s flags=%#x metadata=%d directory=%zu value=%ld fd=%d\n",
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
static int process_limit_capture_allowed(struct thread *caller,struct thread *target) {
    return md_process_limit_access(&caller->identity,&target->identity,caller->tgid==target->tgid);
}
static void process_limit_captured(struct thread *t,struct md_task_helper_result result) {
    struct rlimit value;
    unsigned resource=t->original.regs[t->original.regs[8]==SYS_prlimit64 ? 1 : 0];
    int error=(int)result.value;
    if (!error) error=(int)md_task_limit_fd(result.descriptor,resource,&value);
    if (result.descriptor>=0) close(result.descriptor);
    identity_limit_finish(t,error,&value);
}
static int process_limit_capture(struct thread *t,struct thread *target) {
    struct md_task_credentials credentials;
    int error=md_task_registry_credentials(tasks,target->pid,&credentials);
    if (error) return error;
    struct md_fs_task_subject subject={target->pid,credentials.generation};
    md_task_credentials_release(&credentials);
    struct capture_owner *owner=calloc(1,sizeof(*owner)+64);
    if (!owner) return -ENOMEM;
    struct task_action *action=task_action_new(t);
    if (!action) { free(owner); return -ENOMEM; }
    char *path=(char *)(owner+1);
    snprintf(path,64,"/proc/%d/limits",target->pid);
    *owner=(struct capture_owner){.caller=t->pid,.subject=subject,.foreign=1,
        .target={path,O_RDONLY|O_CLOEXEC},.result={.descriptor=-1},
        .authorize=process_limit_capture_allowed,.complete=process_limit_captured};
    t->capture=owner; action->capture=owner;
    CHECK(!md_event_signal(fd_ready));
    return -EINPROGRESS;
}
static void capture_start(struct thread *t, struct md_fs_buffer_request *request) {
    CHECK(!t->capture && !t->mapping);
    TRACE("PROBE buffer-capture pid=%d\n",t->pid);
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
        error=debugger_options(t->pid,t->debug_options);
    }
    if (!error) {
        struct md_task_helper_request capture={.kind=MD_TASK_HELPER_CAPTURE,
            .path=owner->target.path,.flags=owner->target.flags,.capture_metadata=owner->metadata,
            .directory_limit=owner->directory_limit};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,&t->buffer_registers,&abi,
            t->memory,&t->buffer,&capture,capture_complete,owner);
    }
    if (error) {
        free(owner);
        CHECK(!md_fs_buffer_capture_finish(request,-1,error,NULL,NULL,0));
    } else { t->capture=owner; t->kernel_stopped=0; }
}
static int direct_addfd(const struct thread *t, const struct md_fs_buffer_frame *frame) {
    return !t->broker_event.held && (t->direct==DIRECT_OPEN || t->direct==DIRECT_PREPARE_RESULT) && frame->transfer==MD_FS_BUFFER_ADDFD
        && !frame->reply.result.selectors && t->buffer_open.completion.kind==MD_OPEN_READY;
}
static void buffer_result_begin(struct thread *t,int direct) {
    struct seccomp_data call={.nr=direct ? SYS_fstat : SYS_gettid,.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=direct ? t->original.pc : abi.buffer_admit};
    /* Captured policies retain the owned publication gate across TSYNC. Only
     * an uncaptured inherited policy needs original-site result admission. */
    int replay=!direct && !t->policy && t->application_filter
        && md_broker_filesystem_call(t->original.regs[8]);
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
static void buffer_complete(struct md_fs_buffer_request *,pid_t,int);
static void buffer_complete_stopped(struct thread *t) {
    /* The helper can finish while the caller is entering this checkpoint.
     * Its readiness callback then has no parked wait to interrupt. Consume
     * that retained result here, instead of waiting for a second completion. */
    if (md_fs_buffer_open_ready(t->buffer_call)) {
        CHECK(!md_fs_buffer_open_resume(t->buffer_call)); return;
    }
    buffer_complete(t->buffer_call,t->pid,t->entry_error);
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
    if (md_fs_buffer_open_waiting(request) || md_fs_buffer_capture_pending(request)) {
        t->entry_error=error;
        if (entry_require_stop(t,buffer_complete_stopped)) return;
    }
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
    if (t->original.regs[8]==SYS_ioctl) control_input_discard(t);
    control_publication_finish(t,-ECANCELED);
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
enum execution_continuation { EXECUTION_RETURN, EXECUTION_CLONE, EXECUTION_PREFLIGHT_RETURN,
    EXECUTION_PREFLIGHT_REPLAY, EXECUTION_RESTART, EXECUTION_IPC_WAIT, EXECUTION_WATCH_WAIT,
    EXECUTION_NATIVE };
/* Retire operation ownership and choose the next action without touching the
 * caller's registers, signal mask or execution transport. */
static enum execution_continuation execution_complete(struct thread *t,long value) {
    CHECK(t->phase==DISPATCHING && !t->control_pending);
    if (t->original.regs[8]==SYS_chroot && !value) {
        root_changes++;
        TRACE("PROBE root-change pid=%d result=0\n",t->pid);
    }
    t->epoll_started=0;
    if (t->dispatch_prepared) {
        /* Early argument failures may never claim the supplied input buffer. */
        CHECK(!buffer_cleanup(t));
    }
    if (value==MD_CALL_CLONE && t->original.regs[8]==SYS_clone3) {
        CHECK(!t->clone_prepared && t->buffer.pool);
        t->clone_prepared=1;
        return EXECUTION_CLONE;
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
        /* Replay crosses an application boundary: a signal handler may run
         * before the next filter entry. Never retain its memory turn there. */
        if (t->filter_preflight) filter_install_discard(t);
        t->pid_preflight=t->filter_preflight=0;
        if (!value) {
            t->debug_replay_entry=t->debug_call_active;
            t->debug_adapter=0;
        }
        return value ? EXECUTION_PREFLIGHT_RETURN : EXECUTION_PREFLIGHT_REPLAY;
    }
    if (value==RESTART_SIGNAL || value==RESTART_PREPARATION) return EXECUTION_RESTART;
    if (t->memory_action==MEMORY_DETACH) {
        CHECK(!md_resource_order_complete(&t->memory_order)); t->memory_action=0;
    }
    if (value==MD_IPC_WAIT) return EXECUTION_IPC_WAIT;
    if (value==MD_WATCH_WAIT) return EXECUTION_WATCH_WAIT;
    if (value==MD_CALL_NATIVE) return EXECUTION_NATIVE;
    if (value<0 && t->original.regs[8]==SYS_fstat) {
        if (++fstat_failures<=8)
            TRACE("PROBE fstat failed pid=%d fd=%llu result=%ld\n",t->pid,t->original.regs[0],value);
    }
    return EXECUTION_RETURN;
}
static void dispatch_complete(struct thread *t,long value) {
    enum execution_continuation next=execution_complete(t,value);
    pid_t pid=t->pid;
    struct user_pt_regs regs=t->original;
    switch (next) {
    case EXECUTION_CLONE:
        regs.pc-=4;
        if (!set_registers(pid,&regs)) return;
        md_memory_stack_return(t->memory,t->stack); t->stack=0;
        t->phase=CLONE_ENTRY; resume(pid,0); return;
    case EXECUTION_PREFLIGHT_RETURN:
    case EXECUTION_PREFLIGHT_REPLAY:
        if (next==EXECUTION_PREFLIGHT_REPLAY) regs.pc-=4;
        else regs.regs[0]=value;
        if (!set_registers(pid,&regs) || !restore_mask(t)) return;
        md_memory_stack_return(t->memory,t->stack); t->stack=0;
        t->phase=IDLE; resume(pid,0); return;
    case EXECUTION_RESTART:
        restart_call(t,&t->original,t->mask,(enum call_restart)value); return;
    case EXECUTION_IPC_WAIT: {
        int64_t deadline=md_ipc_deadline(t->ipc_pending);
        int64_t remaining=0;
        if (deadline!=INT64_MAX) {
            int64_t now=md_event_now(); CHECK(now>=0);
            remaining=deadline>now ? deadline-now : 0;
        }
        regs.pc=abi.ipc_wait; regs.sp=t->stack;
        regs.regs[30]=abi.done_entry;
        regs.regs[1]=remaining/1000000000LL; regs.regs[2]=remaining%1000000000LL;
        regs.regs[3]=deadline!=INT64_MAX;
        if (!set_registers(pid,&regs)) return;
        /* EVENT_WAIT: IPC mutation or owner death; semtimedop's deadline
         * returns EAGAIN. Signals interrupt without restarting the operation. */
        t->phase=IPC_WAIT_ENTRY; resume(pid,0); return;
    }
    case EXECUTION_WATCH_WAIT:
    case EXECUTION_NATIVE:
        regs.pc=RAW_GATE-4;
        t->watch_wait=next==EXECUTION_WATCH_WAIT ? 1 : 2;
        if (t->watch_wait==1) {
            /* EVENT_WAIT: native MSG_PEEK waits without consuming queue data.
             * Original signals/restart policy apply; the worker stays runnable. */
            regs.regs[8]=SYS_recvfrom;
            regs.regs[1]=t->stack-16; regs.regs[2]=1;
            regs.regs[3]=MSG_PEEK; regs.regs[4]=regs.regs[5]=0;
        }
        if (!set_registers(pid,&regs)) return;
        t->phase=WATCH_ENTRY; resume(pid,0); return;
    case EXECUTION_RETURN:
        break;
    }
    regs.regs[0]=value;
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
    control_publication_finish(t,result);
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
static void control_input_discard(struct thread *t) {
    md_fd_capture_cancel(&t->control_capture);
    md_received_fds_release(t->control_input); t->control_input=NULL;
    md_fd_object_release(t->control_origin); t->control_origin=NULL;
    if (t->buffer_descriptor_operation==MD_BUFFER_CONTROL_CAPTURE) {
        if (md_fd_order_cancel(&t->buffer_descriptor_order)==1)
            CHECK(!md_fd_order_complete(&t->buffer_descriptor_order));
        t->buffer_descriptor_operation=0;
    }
}
static void control_capture_step(struct thread *t,unsigned command,long value) {
    struct md_buffer_span span;
    struct md_control_file file=md_proc_file_control(t->proc_file);
    struct md_control_ioctl spec={0};
    int error=md_buffer_span(&t->buffer,&span);
    if (!error) error=!md_control_ioctl_available(file) ? -ENOTTY
        : file.ops->ioctl_spec(file.context,(unsigned)t->original.regs[1],&spec);
    if (!error && (md_control_ioctl_size(&spec)<0 || !(spec.direction&MD_CONTROL_FD)
            || md_control_ioctl_capture_offset(&spec)+sizeof(struct md_fd_capture_frame)>span.size)) error=-EINVAL;
    struct md_fd_capture_frame *frame=error ? NULL : (void *)((char *)span.data+md_control_ioctl_capture_offset(&spec));
    if (!error && command==MD_BUFFER_CONTROL_CAPTURE) {
        memcpy(&t->control_input_number,(char *)span.data+spec.descriptor,sizeof(int));
        t->control_origin=md_fd_origins_object(t->resources.files,t->control_input_number,NULL);
        error=md_fd_capture_native(t->pid,&t->control_input_number,1,&t->control_input);
        if (error>0) error=0;
    }
    if (!error && !t->control_input) {
        if (command==MD_BUFFER_CONTROL_CAPTURE_SENT) error=md_fd_capture_sent(&t->control_capture,value);
        if (!error && command==MD_BUFFER_CONTROL_CAPTURE_OBSERVED)
            error=md_fd_capture_observed(&t->control_capture,t->resources.files,frame);
        if (!error) error=md_fd_capture_progress(&t->control_capture,t->resources.files,t->tgid,1,frame);
        if (error>0) { buffer_reply(t,error); return; }
        if (!error) {
            t->control_input=md_fd_capture_take(&t->control_capture);
            if (!t->control_input) error=-EPROTO;
        }
    }
    if (!error) error=md_received_fds_annotate(t->control_input,&t->control_origin,1);
    CHECK(!md_fd_order_complete(&t->buffer_descriptor_order));
    t->buffer_descriptor_operation=0;
    if (error) control_input_discard(t);
    buffer_reply(t,error);
}
static void buffer_descriptor_start(struct thread *t) {
    retry_clear(t,RETRY_DESCRIPTOR);
    if (md_fd_order_state(&t->buffer_descriptor_order)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_DESCRIPTOR); return; }
    CHECK(t->phase==DISPATCHING && !md_fd_order_start(&t->buffer_descriptor_order));
    if (t->buffer_descriptor_operation==MD_BUFFER_CONTROL_CAPTURE) {
        control_capture_step(t,MD_BUFFER_CONTROL_CAPTURE,0); return;
    }
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
        size_t capacity=span.size>offsetof(struct md_network_request,data)
            ? span.size-offsetof(struct md_network_request,data) : 0;
        void *data=(unsigned char *)span.data+offsetof(struct md_network_request,data);
        if (span.size<sizeof(*request)) result=-EOVERFLOW;
        else if (t->buffer_descriptor_argument>capacity) result=-EMSGSIZE;
        else if (md_device_events_is(object)) {
            uint32_t groups=0;
            result=md_network_netlink_destination(destination,&groups);
            if (!result) result=groups ? -EOPNOTSUPP
                : md_device_events_call(object,nr,args,data,capacity,t->buffer_descriptor_argument,visible_pid(t,t->tgid));
        } else result=md_network_socket_call(object,&t->identity,nr,args,data,capacity,
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
static void buffer_control(struct thread *t, const struct user_pt_regs *r);
static struct md_guest_execution execution_prepare(struct thread *t);
static int process_call(struct thread *,struct user_pt_regs *,unsigned long *);
static int descriptor_call(struct thread *,struct user_pt_regs *,unsigned long);
static void proc_lookup_begin(struct thread *,const struct user_pt_regs *);
static int uts_access(const struct thread *t,long number) {
    return number==SYS_uname || md_identity_capable_in(&t->identity,
        md_uts_namespace_owner(t->resources.uts),CAP_SYS_ADMIN) ? 0 : -EPERM;
}
static void execution_begin(struct thread *t,const struct user_pt_regs *r,const char *mask_reason) {
    long nr=r->regs[8];
    if (!entry_pending(t)) {
        t->original=*r;
        if (save_signal_mask(t,&t->mask,mask_reason) && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    uint64_t args[6]; memcpy(args,r->regs,sizeof(args));
    enum md_execution_context reason=md_execution_context_needed(nr,args,MD_INTERCEPT_DISPATCH);
    if (reason!=MD_CONTEXT_COUNT) { entry_context(t,reason); return; }
    struct md_entry_channel *channel=t->entry_control;
    t->entry_control=NULL; t->entry_execution=channel; t->original=*r;
    t->phase=DISPATCHING; ++calls; ++t->calls;
    channel->execution=execution_prepare(t);
    channel->execution_action=MD_EXECUTION_GUEST;
    publish_execution(t,0);
    md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,0);
}
static void entry_begin(struct thread *t,struct md_entry_channel *channel) {
    struct user_pt_regs r={.pc=channel->instruction_pointer};
    r.regs[8]=channel->call.number;
    memcpy(r.regs,channel->call.args,sizeof(channel->call.args));
    struct seccomp_data call={.nr=(int)r.regs[8],.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=r.pc};
    memcpy(call.args,channel->call.args,sizeof(call.args));
    t->entry_control=channel;
    if (statistics) ++statistics->entry_executions;
    uint32_t decision=interception_decision(t,&call);
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_ERRNO) {
        unsigned error=decision&SECCOMP_RET_DATA;
        return_value(t,&r,-(long)(error>4095 ? 4095 : error)); return;
    }
    CHECK(action==SECCOMP_RET_ALLOW || action==SECCOMP_RET_TRACE || action==SECCOMP_RET_USER_NOTIF);
    unsigned long cookie=action==SECCOMP_RET_ALLOW ? MD_INTERCEPT_NATIVE
        : action==SECCOMP_RET_USER_NOTIF ? MD_INTERCEPT_DISPATCH : decision&SECCOMP_RET_DATA;
    if (cookie&MD_INTERCEPT_CLOSE) cookie=MD_INTERCEPT_FD;
    if (cookie==MD_INTERCEPT_SIGNAL || r.regs[8]==SYS_signalfd4) {
        entry_context(t,MD_CONTEXT_SIGNAL); return;
    }
    if (r.regs[8]==SYS_ptrace || r.regs[8]==SYS_wait4 || r.regs[8]==SYS_waitid) {
        entry_context(t,r.regs[8]==SYS_ptrace ? MD_CONTEXT_DEBUG : MD_CONTEXT_WAIT); return;
    }
    if (process_limit_call(t,&r) || process_call(t,&r,&cookie)
            || identity_call(t,&r,cookie) || domain_call(t,&r,cookie)) return;
    if (cookie==MD_INTERCEPT_FD || cookie==MD_INTERCEPT_CWD || cookie==MD_INTERCEPT_CAPTURE) {
        fd_begin(t,&r,cookie); return;
    }
    if (cookie==MD_INTERCEPT_MEMORY) { memory_begin(t,&r,MEMORY_KERNEL,0); return; }
    if (descriptor_call(t,&r,cookie)) return;
    if (cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_WATCH) { proc_lookup_begin(t,&r); return; }
    if (cookie==MD_INTERCEPT_NATIVE) { native_resume(t); return; }
    if (cookie==MD_INTERCEPT_OBSERVE) { entry_context(t,MD_CONTEXT_PROCESS); return; }
    CHECK(cookie==MD_INTERCEPT_DISPATCH || cookie==MD_INTERCEPT_READINESS);
    execution_begin(t,&r,"entry-dispatch");
}
static void entry_execution_retire(struct thread *t,long result) {
    struct md_entry_channel *channel=t->entry_execution;
    CHECK(channel && !t->entry_control && !t->entry_resume && !t->entry_effect && !t->signal_shielded);
    if (statistics) ++statistics->entry_completions;
    t->entry_execution=NULL; t->entry_kernel_context=0; t->phase=IDLE;
    CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&t->identity,&t->resources));
    publish_execution(t,0);
    md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,result);
    if (md_signal_delivery_pending(&t->signal_delivery))
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"entry-signal-delivery");
}
static void entry_execution_native_done(struct thread *t,long result) {
    CHECK(t->entry_control==t->entry_execution);
    t->entry_control=NULL;
    entry_execution_retire(t,result);
}
static void entry_execution_finish(struct thread *t,long result) {
    enum execution_continuation next=execution_complete(t,result);
    if (next==EXECUTION_NATIVE) {
        /* Classification may require a real stop before choosing native IO.
         * The admitted nonblocking task operation still uses the same journal,
         * without repeating either classification or the application syscall. */
        uint64_t args[6]; memcpy(args,t->original.regs,sizeof(args));
        CHECK(md_execution_context_needed(t->original.regs[8],args,MD_INTERCEPT_NATIVE)==MD_CONTEXT_COUNT);
        t->entry_control=t->entry_execution;
        entry_effect(t,t->original.regs[8],args,entry_execution_native_done);
        return;
    }
    if (next!=EXECUTION_RETURN)
        fprintf(stderr,"entry completion pid=%d nr=%llu action=%d result=%ld\n",
            t->pid,t->original.regs[8],next,result);
    CHECK(next==EXECUTION_RETURN);
    entry_execution_retire(t,result);
}
static void entry_control(pid_t pid,struct md_entry_channel *channel) {
    struct thread *t=find_thread(pid);
    unsigned command=(unsigned)channel->call.number;
    if (t && channel->call.reserved==MD_ENTRY_EXECUTION_BEGIN) {
        if (t->phase!=IDLE || t->entry_execution || t->entry_control || !t->ready
                || !channel->instruction_pointer || !md_entry_supported(command)) {
            md_broker_entry_reply(broker,channel,MD_ENTRY_DECLINED,0); return;
        }
        entry_begin(t,channel); return;
    }
    if (t && channel->call.reserved==MD_ENTRY_EXECUTION_END) {
        CHECK(t->entry_execution==channel && !t->entry_control && !t->entry_resume);
        long result=(long)channel->call.args[0];
        entry_execution_finish(t,result);
        return;
    }
    unsigned kind=channel->call.reserved;
    if (kind==MD_ENTRY_EFFECT_DONE) {
        CHECK(t && t->entry_control==channel && t->entry_effect);
        unsigned state=atomic_load_explicit(&channel->native.state,memory_order_acquire);
        CHECK(state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED);
        long result=channel->native.result;
        atomic_store_explicit(&channel->native.state,MD_NATIVE_EMPTY,memory_order_release);
        TRACE("PROBE entry-effect-done pid=%d result=%ld\n",t->pid,result);
        void (*complete)(struct thread *,long)=t->entry_effect;
        t->entry_effect=NULL;
        complete(t,result); return;
    }
    if (t && !t->entry_control && kind==MD_ENTRY_RESOURCE_CONTROL) {
        unsigned long args[4];
        for (unsigned i=0;i<4;++i) args[i]=channel->call.args[i+1];
        long result=resource_control(t,command,channel->call.args[0],args);
        md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,result); return;
    }
    int fd=kind==MD_ENTRY_FD_CONTROL;
    if (!t || t->entry_control || (fd ? !md_fd_control_shared(command)
            : kind!=MD_ENTRY_BUFFER_CONTROL || !(md_adapter_buffer_shared(command) || md_fs_buffer_scalar(command)))) {
        md_broker_entry_reply(broker,channel,MD_ENTRY_DECLINED,0);
        return;
    }
    struct user_pt_regs control={.pc=(fd ? abi.fd_control : abi.buffer_control)+4};
    control.regs[0]=command;
    control.regs[1]=channel->call.args[0]; control.regs[2]=channel->call.args[1];
    control.regs[3]=channel->call.args[2];
    t->entry_control=channel;
    if (fd) fd_control(t,&control);
    else buffer_control(t,&control);
}
static void buffer_control(struct thread *t, const struct user_pt_regs *r) {
    if (t->entry_resume) {
        CHECK(!t->entry_control && r->regs[0]==t->buffer_registers.regs[0]
            && r->regs[1]==t->buffer_registers.regs[1] && r->regs[2]==t->buffer_registers.regs[2]);
        void (*continuation)(struct thread *)=t->entry_resume;
        t->entry_resume=NULL; t->buffer_registers=*r;
        continuation(t); return;
    }
    long result=-EINVAL;
    if (statistics && r->regs[0]<128) ++statistics->buffer_commands[r->regs[0]];
    if (t->buffer_waiting || t->buffer_return_waiting || (t->buffer_call && !md_fs_buffer_ack_pending(t->buffer_call)
            && !md_fs_buffer_export_pending(t->buffer_call)
            && !md_fs_buffer_snapshot_pending(t->buffer_call)
            && !md_fs_buffer_task_pending(t->buffer_call))) { CHECK(0); }
    t->buffer_registers=*r;
    if (!buffer_service && r->regs[0]!=MD_BUFFER_MAPPED && r->regs[0]!=MD_BUFFER_READONLY_MAPPED
            && r->regs[0]!=MD_BUFFER_READONLY_INSTALL
            && r->regs[0]!=MD_BUFFER_SIGNAL_ENABLE && r->regs[0]!=MD_BUFFER_TAKE
            && r->regs[0]!=MD_BUFFER_RELEASE && r->regs[0]!=MD_BUFFER_PROBE && r->regs[0]!=MD_BUFFER_UTS
            && r->regs[0]!=MD_BUFFER_PROC_IO && r->regs[0]!=MD_BUFFER_PROC_ACK
            && r->regs[0]!=MD_BUFFER_FINISH && r->regs[0]!=MD_BUFFER_PROC_START
            && r->regs[0]!=MD_BUFFER_PROC_OPEN && r->regs[0]!=MD_BUFFER_PROC_META && r->regs[0]!=MD_FS_BUFFER_RIGHTS_READY
            && r->regs[0]!=MD_FS_BUFFER_RIGHTS_DONE && r->regs[0]!=MD_FS_BUFFER_CHANNEL_OBSERVED
            && r->regs[0]!=MD_BUFFER_PID_NUMBER && r->regs[0]!=MD_BUFFER_NATIVE_IDENTITY
            && r->regs[0]!=MD_BUFFER_DESCRIPTOR_META && r->regs[0]!=MD_BUFFER_PIDFD_NAMESPACE
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
                || (t->original.regs[8]!=SYS_ppoll && t->original.regs[8]!=SYS_pselect6
                    && t->original.regs[8]!=SYS_rt_sigsuspend)) break;
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
                || (!t->readiness->received && !t->readiness->native_caller)) break;
        if (t->readiness->copy_pending) {
            if (r->regs[2]!=MD_EPOLL_COPY || (long)r->regs[1]>0) break;
            t->readiness->copy_pending=0; t->readiness->continuation=*r;
            readiness_task_progress(t,1,(long)r->regs[1]); return;
        }
        if (t->readiness->park_pending) {
            if (!r->regs[1] || r->regs[2]!=MD_EPOLL_PARK) break;
            t->readiness->continuation=*r;
            readiness_park(t,r->regs[1]); return;
        }
        if (r->regs[1] || r->regs[2]) break;
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
    case MD_BUFFER_READONLY_MAPPED:
        if ((t->entered || t->phase!=IDLE) && !(t->filter_preflight && t->phase==DISPATCHING
                && t->filter_install && md_resource_order_state(&t->filter_install->memory)==MD_RESOURCE_ORDER_RUNNING)) break;
        result=md_memory_readonly_mapped(t->memory,r->regs[1],r->regs[2]);
        break;
    case MD_BUFFER_READONLY_INSTALL:
        if (r->regs[1] || r->regs[2] || t->buffer_mapping || !t->filter_preflight
                || t->phase!=DISPATCHING || !t->filter_install
                || md_resource_order_state(&t->filter_install->memory)!=MD_RESOURCE_ORDER_RUNNING
                || !md_memory_readonly_buffers(t->memory) || md_memory_readonly_base(t->memory)) break;
        buffer_mapping_begin(t,r); return;
    case MD_BUFFER_CHANNEL_INSTALL:
        if (r->regs[1] || r->regs[2] || t->buffer_mapping) break;
        entry_mapping_begin(t,r,0); return;
    case MD_BUFFER_CLIENT_INSTALL:
        if (r->regs[1]>=MD_SVC_CLIENT_BUCKETS || r->regs[2] || t->buffer_mapping
                || !t->entered || t->phase!=IDLE || t->cooperative_blocked || !filter_entry_eligible(t)) break;
        entry_mapping_begin(t,r,1); return;
    case MD_BUFFER_SIGNAL_ENABLE: {
        struct { struct md_kernel_action action; uint64_t mask; } initial;
        if (t->entered || t->phase!=IDLE || !md_memory_buffers(t->memory)
                || r->regs[2]!=sizeof(initial)) break;
        ssize_t copied=md_task_copy_direct(t->pid,r->regs[1],&initial,sizeof(initial),0);
        if (copied!=sizeof(initial)) { result=copied<0 ? copied : -EFAULT; break; }
        if (!t->signal_actions) {
            /* This first bootstrap handshake follows the native exec event.
             * Later images and clone children already retain their owner. */
            t->signal_actions=md_signal_actions_exec(NULL);
            if (!t->signal_actions) { result=-ENOMEM; break; }
            result=md_signal_actions_commit(t->signal_actions,SIGSYS,&initial.action);
            if (result) break;
            t->host_signal_blocked=!!(initial.mask&MD_SIGNAL_SYS_BIT);
        }
        if (!t->signal_pending) t->signal_pending=md_signal_pending_new();
        if (!t->signal_pending) { result=-ENOMEM; break; }
        md_signal_actions_entry_configure(t->signal_actions,abi.signal_entry);
        t->host_signals=1; result=0;
        break;
    }
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
        if (result<0) TRACE("PROBE proc-image-link caller=%d target=%d error=%ld\n",t->pid,target->pid,result);
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
        if (t->phase!=DISPATCHING || !t->buffer.pool || r->regs[1]>MD_NETWORK_CONTROL_MAX || r->regs[2]) break;
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
        if (t->phase!=DISPATCHING || t->proc_file
                || (t->original.regs[8]!=SYS_ioctl && t->original.regs[8]!=SYS_setns)
                || r->regs[1]!=t->original.regs[0] || r->regs[2]!=t->original.regs[1]
                || (t->original.regs[8]==SYS_ioctl && !md_pidfd_namespace_command((unsigned)r->regs[2]))) break;
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
    case MD_BUFFER_PROC_OPEN: {
        if (t->phase!=DISPATCHING || r->regs[1] || r->regs[2] || !t->buffer.pool) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_proc_open_request query;
        if (span.size<sizeof(query)) { result=-EOVERFLOW; break; }
        memcpy(&query,span.data,sizeof(query));
        proc_file_request(t,&query); return;
    }
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
    case MD_BUFFER_NATIVE_IDENTITY:
        if (r->regs[1] || r->regs[2]) break;
        result=(long)(((uint64_t)(unsigned)t->tgid<<32)|(unsigned)t->pid); break;
    case MD_BUFFER_PID_NUMBER:
        if (t->phase!=DISPATCHING || r->regs[2]>1) break;
        result=r->regs[2] ? native_pid(t,(pid_t)r->regs[1]) : visible_pid(t,(pid_t)r->regs[1]); break;
    case MD_BUFFER_MEMORY_OPEN: {
        if (t->phase!=DISPATCHING || (r->regs[2]&~(unsigned long)O_PATH)) break;
        struct thread *target=find_thread((pid_t)r->regs[1]);
        if (!target || (!(r->regs[2]&O_PATH) && !proc_allowed(t->pid,target->pid))) {
            result=-EACCES; break;
        }
        /* O_PATH admits only the dentry, but its retained pin can outlive a
         * later access-policy change. Keep exposure tracking for both kinds. */
        result=md_memory_exposure_begin(target->memory,&t->memory_exposure);
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
    case MD_BUFFER_CONTROL_CAPTURE:
        if (t->phase!=DISPATCHING || !t->proc_file || !t->buffer.pool || t->original.regs[8]!=SYS_ioctl
                || t->control_input || t->control_origin || t->control_capture.count || r->regs[1] || r->regs[2]) break;
        buffer_descriptor_begin(t,r); return;
    case MD_BUFFER_CONTROL_CAPTURE_READY: case MD_BUFFER_CONTROL_CAPTURE_SENT: case MD_BUFFER_CONTROL_CAPTURE_OBSERVED:
        if (t->phase!=DISPATCHING || !t->control_capture.count || r->regs[2]
                || t->buffer_descriptor_operation!=MD_BUFFER_CONTROL_CAPTURE
                || md_fd_order_state(&t->buffer_descriptor_order)!=MD_RESOURCE_ORDER_RUNNING) break;
        control_capture_step(t,(unsigned)r->regs[0],(long)r->regs[1]); return;
    case MD_BUFFER_CONTROL_IOCTL_SPEC: case MD_BUFFER_CONTROL_IOCTL_LAYOUT: case MD_BUFFER_CONTROL_IOCTL: {
        if (t->phase!=DISPATCHING || !t->proc_file || t->original.regs[8]!=SYS_ioctl
                || r->regs[1] || r->regs[2]) break;
        struct md_control_file file=md_proc_file_control(t->proc_file);
        if (!md_control_ioctl_available(file)) { result=-ENOTTY; break; }
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
            if (!result && (!(spec.direction&(MD_CONTROL_INDIRECT|MD_CONTROL_FD)) || span.size<sizeof(spec))) result=-EINVAL;
            if (!result) memcpy(span.data,&spec,sizeof(spec));
            break;
        }
        if (t->control_pending || t->control_publication) { result=-EBUSY; break; }
        struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources,.wakeup=fd_wakeup,
            .descriptor=t->control_input,.descriptor_number=t->control_input_number};
        if (!result && (spec.direction&MD_CONTROL_FD)) {
            int number; memcpy(&number,(char *)span.data+spec.descriptor,sizeof(number));
            if (!actor.descriptor || actor.descriptor->count!=1 || number!=actor.descriptor_number) result=-EBADF;
        }
        if (!result) result=file.ops->ioctl_async
            ? file.ops->ioctl_async(file.context,&actor,command,t->original.regs[2],span.data,(size_t)size,&t->control_pending)
            : file.ops->ioctl(file.context,&actor,command,t->original.regs[2],span.data,(size_t)size);
        TRACE("PROBE control-ioctl pid=%d command=%x result=%ld pending=%d\n",t->pid,command,result,t->control_pending!=NULL);
        if ((result>=0 || result==-EINPROGRESS)
                && !!(spec.direction&MD_CONTROL_ACK)!=!!(t->control_pending && t->control_pending->copied))
            result=-EPROTO;
        if (t->control_pending) {
            if (result==-EINPROGRESS) {
                /* EVENT_WAIT: device-provider completion or request deadline;
                 * no file-table/OFD turn is held across a userspace reply. */
                t->control_size=(size_t)size; retry_wait(t,RETRY_CONTROL); control_park(t); return;
            }
            control_pending_complete(t,result);
        } else if (result==-EINPROGRESS) result=-EPROTO;
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
        else if (!result) {
            result=uts_access(t,nr);
            if (!result) result=md_uts_namespace_set(t->resources.uts,nr==SYS_setdomainname,span.data,t->original.regs[1]);
        }
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
    case MD_FS_BUFFER_RIGHTS_BEGIN: {
        if (!t->buffer.pool || r->regs[1] || r->regs[2] || t->buffer_rights_delivered) break;
        if (t->buffer_open.fd<0) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->buffer,&span);
        if (result) break;
        struct md_fs_buffer_frame *frame=span.data;
        if (frame->transfer!=MD_FS_BUFFER_RIGHTS) { result=-EINVAL; break; }
        int flags=t->buffer_open.flags&O_CLOEXEC ? FD_CLOEXEC : 0;
        struct md_fd_object *object=md_fs_buffer_open_object(&t->buffer_open);
        frame->reply.result.selectors=md_fd_object_selectors(object);
        result=md_fd_return_begin(&t->buffer_return,t->resources.files,t->buffer_open.fd,
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
static int process_reboot_signal(pid_t pid) {
    struct thread *t=find_thread(pid);
    return md_pid_identity_reboot_signal(t ? t->resources.pid_group : md_task_registry_retired(tasks,pid).pid);
}
static int process_reboot_status(pid_t pid,int status) {
    if (!WIFEXITED(status) && !WIFSIGNALED(status)) return status;
    int signal=process_reboot_signal(pid);
    return signal ? signal : status;
}
static void process_reboot_info(pid_t pid,int *code,int *status) {
    if (*code!=CLD_EXITED && *code!=CLD_KILLED && *code!=CLD_DUMPED) return;
    int signal=process_reboot_signal(pid);
    if (signal) { *code=CLD_KILLED; *status=signal; }
}
/* Keep stop information native while a guest debugger owns it. Translate only
 * on actual delivery, in the recipient's namespaces rather than the tracer's. */
static void deliver_signal(pid_t pid,int sig,int restored,struct md_task_signal_stop *stop) {
    struct thread *t=thread(pid);
    struct md_task_signal_stop local;
    if (!stop) { md_task_signal_stop_init(&local,native_trace); stop=&local; }
    if (sig && sig!=SIGSTOP && !t->signal_delivery.active
            && ((!restored && md_signal_delivery_pending(&t->signal_delivery))
                || t->signal_shielded || t->metadata_restart)) {
        /* A lower-level owner can finish at an architectural boundary while
         * a higher-level restart still owns that boundary. Retain its signal
         * until every internal owner has restored the application context. */
        if (!signal_observe(t,stop,0)) return;
        siginfo_t info=stop->info;
        if (!md_signal_synchronous(&info) && info.si_signo<32) {
            int retained=md_signal_delivery_retain(&t->signal_delivery,pid,&info); CHECK(retained>=0);
            resume(pid,retained); return;
        }
    }
    if (sig && (t->resources.pid || sig==SIGCHLD)) {
        if (!signal_observe(t,stop,0)) return;
        siginfo_t info=stop->info;
        int fault=md_signal_synchronous(&info);
        if (namespace_init(t) && sig!=SIGKILL && sig!=SIGSTOP && !fault) {
            uint64_t caught=0;
            long error=0;
            int handled=md_signal_actions_caught(t->signal_actions,sig);
            if (handled>=0) caught=handled ? UINT64_C(1)<<(sig-1) : 0;
            else error=md_task_signal_handlers(t->pid,&caught);
            if (error) {
                fprintf(stderr,"Shroot: cannot observe PID init signal disposition: %ld\n",error);
                syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
                resume(pid,0); return;
            }
            if (!(caught&(UINT64_C(1)<<(sig-1)))) { resume(pid,0); return; }
        }
        if (md_signal_native_sender(&info)) {
            signal_wait_info(t,&info);
            if (!signal_replace_info(t,stop,&info)) return;
        }
    }
    if (sig && t->signal_delivery.first && !t->signal_delivery.active) {
        if (!signal_observe(t,stop,0)) return;
        int error=md_signal_delivery_before_handler(&t->signal_delivery,pid,&stop->info);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
    if (signal_control_delivery(t,sig,stop)) return;
    resume(pid,sig);
}
static void resume_signal(pid_t pid,int sig,const struct md_signal_origin *origin) {
    /* Debugger release has already passed ordinary signal admission. */
    struct thread *t=thread(pid);
    if (origin && origin->valid) t->signal_origin=*origin;
    deliver_signal(pid,sig,1,NULL);
    t->signal_origin.valid=0;
}
static int init_signal_suppressed(const struct thread *from,const struct thread *to,int signal) {
    if ((signal!=SIGKILL && signal!=SIGSTOP) || !namespace_init(to)) return 0;
    /* Kernel-only signals may force a child init only from an ancestor PID
     * namespace. Other signals retain blocking/handler semantics at delivery. */
    return md_pid_identity_namespace(from->resources.pid)==md_pid_identity_namespace(to->resources.pid);
}
static void setns_complete(struct thread *t,int error) {
    CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error));
    struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
    ((struct md_pidfd_frame *)span.data)->result=error;
    buffer_reply(t,MD_PIDFD_DONE);
}
static void setns_prepared(struct thread *t,long error) {
    if (!error && t->resource_change.pid_children_pending && !t->pid_filter) {
        struct user_pt_regs continuation;
        if (registers(t->pid,&continuation)) pid_filter_begin(t,&continuation,setns_prepared);
        return;
    }
    if (!error && t->resource_change.join_mounts) {
        error=md_mount_join_submit(mount_copy_service,t->pid,t->resource_change.join_mounts,&t->mount_copy);
        /* EVENT_WAIT: selected namespace root/cwd resolution. Cancellation
         * discards the entire unpublished namespace/credential candidate. */
        if (!error) return;
    }
    setns_complete(t,error);
}
static void pidfd_result(struct thread *t,struct md_pid_identity *identity,pid_t native,long error) {
    struct md_buffer_span span; CHECK(!md_buffer_span(&t->buffer,&span));
    struct md_pidfd_frame *frame=span.data;
    int namespace=t->original.regs[8]==SYS_ioctl;
    int joining=t->original.regs[8]==SYS_setns;
    struct thread *target=native>0 ? find_thread(native) : NULL;
    if (!error && joining && !native) error=-EINVAL;
    /* A pidfd's flag contract precedes target liveness and ptrace admission. */
    if (!error && joining) error=md_resources_setns_process_flags((unsigned)t->original.regs[1]);
    if (!error && (native<0 || (identity && (!target || target->resources.pid_group!=identity)))) error=-ESRCH;
    if (!error && target && !target->born) error=-ESRCH;
    if (!error && !target) error=namespace ? MD_CALL_NATIVE : -ESRCH;
    else if (!error && joining) {
        if (!inspect_allowed(t->pid,target->pid,0)) error=-EPERM;
        else error=md_resources_setns_process_prepare(&t->resources,&t->identity,&target->resources,
            target->identity.user_namespace,(unsigned)t->original.regs[1],&t->resource_change);
    } else if (!error && namespace) {
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
        int signal=(int)t->original.regs[1],code=frame->info.si_code;
        if (signal<0 || signal>=NSIG || t->original.regs[3]) error=-EINVAL;
        else if (!signal_allowed(t,target,signal)) error=-EPERM;
        else if (t->original.regs[2] && (code>=0 || code==SI_TKILL) && target->tgid!=t->tgid) error=-EPERM;
        else error=init_signal_suppressed(t,target,signal);
        if (!error && signal==SIGSYS && target->host_signals) {
            siginfo_t info=frame->info;
            if (!t->original.regs[2]) {
                info=(siginfo_t){.si_signo=SIGSYS,.si_code=SI_USER};
                info.si_pid=t->tgid; info.si_uid=getuid();
            }
            error=signal_logical_enqueue(target,MD_SIGNAL_SHARED,&info);
            if (!error) error=1;
        }
    }
    if (joining) { setns_prepared(t,(int)error); return; }
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
    if (t->original.regs[8]==SYS_setns) {
        if (md_fs_order_state(&t->fs_order)!=MD_RESOURCE_ORDER_RUNNING) { buffer_reply(t,-EINVAL); return; }
        struct md_proc_file *file=proc_file_capture(t,(int)t->original.regs[0]);
        const struct md_namespace_reference *ref=md_proc_file_namespace_reference(file);
        unsigned types=(unsigned)t->original.regs[1];
        int error=0;
        if (file && (md_proc_file_flags(file)&O_PATH)) error=-EBADF;
        if (!error && (ref ? ref->kind==MD_NAMESPACE_USER : !!(types&CLONE_NEWUSER)))
            for (struct thread *other=threads;other;other=other->next)
                if (other!=t && other->pid && other->tgid==t->tgid) { error=-EINVAL; break; }
        if (!error && ref) error=md_resources_setns_prepare(&t->resources,&t->identity,ref,types,&t->resource_change);
        int handled=error || ref;
        md_proc_file_release(file);
        if (handled) { setns_prepared(t,error); return; }
    }
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
static void task_readiness_changed(struct thread *t) {
    struct md_control_actor actor={.pid=t->pid,.signals=t->signal_pending};
    if (t->readiness && t->readiness->wait.blocking && t->phase==EPOLL_WAIT && !t->socket_waking
            && (md_poll_wait_task_ready(t->readiness->poll,&actor)>0
                || md_control_epoll_task_ready(md_control_epoll_from_object(t->readiness->queue),&actor)>0)) {
        t->socket_waking=1;
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"task-readiness");
    }
}
static int signal_pending_wake(struct thread *t) {
    struct signal_control *owner=t->signal_control;
    if (owner && !owner->returning && !owner->entry_packet)
        return md_task_signal_control_wake(&owner->control);
    /* Published entry/return packets may be followed only by native IO. They
     * hold a mapping turn, not an interruptible borrowed syscall: require an
     * acknowledgement for newly deliverable signals as for mapping contention. */
    if (!t->host_signal_blocked && !t->kernel_stopped && !t->interrupt_pending) {
        if (native_trace(PTRACE_INTERRUPT,t->pid,NULL,NULL)<0)
            return errno==ESRCH ? 0 : -errno;
        t->interrupt_pending=1;
    }
    return 0;
}
static int signal_logical_enqueue(struct thread *to,enum md_signal_scope scope,const siginfo_t *info) {
    struct md_kernel_action action;
    int error=md_signal_actions_read(to->signal_actions,SIGSYS,&action);
    if (error) return error;
    if (action.handler==(uintptr_t)SIG_IGN && !to->host_signal_blocked) return 0;
    error=md_signal_pending_put(to->signal_pending,scope,info);
    if (error<0) return error;
    for (struct thread *peer=threads;peer;peer=peer->next) {
        if (!peer->pid || !peer->born || (scope==MD_SIGNAL_PRIVATE ? peer!=to : peer->tgid!=to->tgid)) continue;
        task_readiness_changed(peer);
        error=signal_pending_wake(peer);
        if (error && error!=-ESRCH) return error;
    }
    return 0;
}
static long signal_logical_send(struct thread *from,const struct user_pt_regs *r,const siginfo_t *supplied) {
    long nr=r->regs[8];
    int directed=nr==SYS_tgkill || nr==SYS_tkill || nr==SYS_rt_tgsigqueueinfo;
    int index=nr==SYS_tgkill || nr==SYS_rt_tgsigqueueinfo;
    pid_t pid=native_pid(from,(pid_t)r->regs[index]);
    struct thread *to=find_thread(pid);
    if (!to || !to->born || !to->host_signals) return -ESRCH;
    if (index && to->tgid!=native_pid(from,(pid_t)r->regs[0])) return -ESRCH;
    if (!signal_allowed(from,to,SIGSYS)) return -EPERM;
    if (supplied && (supplied->si_code>=0 || supplied->si_code==SI_TKILL) && pid!=from->pid) return -EPERM;
    if (init_signal_suppressed(from,to,SIGSYS)) return 0;
    siginfo_t info=supplied ? *supplied : (siginfo_t){.si_signo=SIGSYS,.si_code=directed ? SI_TKILL : SI_USER};
    if (!supplied) { info.si_pid=from->tgid; info.si_uid=getuid(); }
    info.si_signo=SIGSYS;
    return signal_logical_enqueue(to,directed ? MD_SIGNAL_PRIVATE : MD_SIGNAL_SHARED,&info);
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
    struct thread *to=selection.next ? find_thread(selection.next) : NULL;
    if (signal==SIGSYS && to && to->host_signals && !selection.suppressed) {
        siginfo_t info={.si_signo=SIGSYS,.si_code=SI_USER};
        info.si_pid=from->tgid; info.si_uid=getuid();
        int error=signal_logical_enqueue(to,MD_SIGNAL_SHARED,&info);
        if (error) return error;
        selection.suppressed=1;
    }
    return selection.next ? ((long)selection.next<<1)|selection.suppressed
        : selection.found ? -EPERM : after ? 0 : -ESRCH;
}
static int process_call(struct thread *t,struct user_pt_regs *r,unsigned long *cookie) {
    long nr=r->regs[8];
    if (t->phase==IDLE && robust_call(t,r,MD_TASK_CALL_SECCOMP)) return 1;
    int signaling=nr==SYS_kill || nr==SYS_tkill || nr==SYS_tgkill
        || nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo || nr==SYS_pidfd_send_signal;
    int inspecting=nr==SYS_process_vm_readv || nr==SYS_process_vm_writev
        || nr==SYS_kcmp || nr==SYS_get_robust_list;
    /* A later FD selector can replace the PID selector's cookie, including
     * after close/reuse. Terminal IDs still belong to the caller's PID view. */
    int terminal=t->resources.pid && t->phase==IDLE && r->pc!=RAW_GATE
        && r->regs[8]==SYS_ioctl && md_terminal_identity_command((unsigned)r->regs[1]);
    /* Guest credentials never authorize signals against Android's executor
     * UID. The baseline already observes these calls; PID views only add ID
     * translation. Internal delivery uses its validated, positive native PID. */
    if (*cookie!=MD_INTERCEPT_PROCESS && !terminal
            && !((signaling || inspecting || nr==SYS_rt_sigtimedwait || nr==SYS_waitid)
                && t->phase==IDLE && r->pc!=RAW_GATE)) return 0;
    if (entry_pending(t)) {
        if (nr==SYS_getpid || nr==SYS_gettid) {
            pid_t target=nr==SYS_getpid ? md_pid_identity_number(t->resources.pid_group,
                md_pid_identity_namespace(t->resources.pid)) : visible_pid(t,t->pid);
            return_value(t,r,target); return 1;
        }
        /* PID translation, native wait/restart and target inspection retain
         * this owner's observed kernel entry/exit, not a second C executor. */
        if (entry_context(t,MD_CONTEXT_PROCESS)) return 1;
    }
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
    if (nr==SYS_rt_sigtimedwait) {
        /* Observed waits need PID translation and must survive service
         * quiescence without publishing its EINTR. Unselected host waits
         * remain native; both translated paths share one deadline owner. */
        int error=signal_control_begin(t,r,0,NULL,0,NULL);
        if (error) return_value(t,r,error);
        return 1;
    }
    if (!t->resources.pid && !signaling && !inspecting
            && nr!=SYS_wait4 && nr!=SYS_waitid) return 0;
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
                if (to && to->host_signals && signal==SIGSYS) {
                    if (nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo) {
                        int error=signal_control_begin(t,r,0,NULL,0,NULL);
                        if (error) return_value(t,r,error);
                    } else return_value(t,r,signal_logical_send(t,r,NULL));
                    return 1;
                }
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
    if (nr==SYS_kcmp || nr==SYS_get_robust_list) {
        unsigned count=nr==SYS_kcmp ? 2 : 1;
        for (unsigned i=0;i<count;++i) if ((pid_t)native.regs[i]>0
                && !inspect_allowed(t->pid,(pid_t)native.regs[i],0)) {
            return_value(t,r,-EPERM); return 1;
        }
    }
    if (nr==SYS_process_vm_readv || nr==SYS_process_vm_writev) {
        struct thread *target=find_thread((pid_t)native.regs[0]);
        t->process_inspection_denied=target && !inspect_allowed(t->pid,target->pid,0);
        /* Let the kernel validate vectors and empty transfers before reporting
         * the access denial. An impossible PID cannot expose target memory. */
        if (t->process_inspection_denied) native.regs[0]=(uint64_t)-1;
        CHECK(!t->remote_memory);
        if (target && !t->process_inspection_denied) {
            t->remote_memory=md_memory_fork(target->memory,1); CHECK(t->remote_memory);
        }
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
static void signal_control_discard(struct thread *t) {
    retry_clear(t,RETRY_SIGNAL);
    struct signal_control *owner=t->signal_control;
    if (!owner) return;
    if (owner->entry_previous) {
        *owner->entry_previous=owner->entry_next;
        if (owner->entry_next) owner->entry_next->entry_previous=owner->entry_previous;
    }
    if (owner->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    md_buffer_release(&owner->scratch);
    if (owner->memory.queue && md_resource_order_cancel(&owner->memory)==1)
        CHECK(!md_resource_order_complete(&owner->memory));
    if (owner->loan) {
        struct task_action *loan=owner->loan;
        if (loan->number>=0 && !cancelling) {
            md_fd_origins_finish(&owner->publication,loan->number);
            loan_retire(loan,NULL,NULL,NULL);
        } else {
            if (loan->loan) md_fd_loan_end(loan->loan);
            md_fd_loan_release(loan->loan); md_fd_origins_unpin(loan->loan_table); free(loan);
        }
    }
    md_fd_origins_finish(&owner->publication,-1);
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
    md_fd_object_release(owner->descriptor);
    md_task_signal_control_dispose(&owner->control);
    md_memory_page_release(owner->wait_page);
    free(owner); t->signal_control=NULL;
}
static void signal_entry_complete(struct thread *t,int failed) {
    struct signal_control *owner=t->signal_control;
    struct md_kernel_action entered;
    CHECK(!md_signal_actions_enter(t->signal_actions,owner->signal,&entered));
    if (failed) {
        /* Frame construction failed before the kernel applied the action's
         * mask. A failed SIGSEGV frame also forces its disposition to DFL. */
        t->host_signal_blocked=owner->entry_blocked;
        t->signal_wait_view=owner->entry_wait_view;
        t->signal_handler_mask_known=0;
        if (owner->signal==SIGSEGV) {
            entered.handler=(uintptr_t)SIG_DFL;
            CHECK(!md_signal_actions_commit(t->signal_actions,SIGSEGV,&entered));
        }
    }
    signal_control_discard(t); t->phase=IDLE;
}
static int signal_entry_committed(struct thread *t) {
    struct signal_control *owner=t->signal_control;
    if (!owner || owner->returning || !owner->entry_packet
            || atomic_load_explicit(&owner->entry_packet->pending,memory_order_acquire)) return 0;
    signal_entry_complete(t,0);
    return 1;
}
static void signal_entries_progress(void) {
    for (struct signal_control *owner=signal_entries,*next;owner;owner=next) {
        next=owner->entry_next;
        struct thread *t=owner->entry_thread;
        int committed=signal_entry_committed(t);
        if (md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
                || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED))
            CHECK(!signal_pending_wake(t));
        if (committed) continue;
        if (!t->kernel_stopped && !t->interrupt_pending) {
            struct md_resource_queue *queue=owner->memory.queue;
            pthread_mutex_lock(&queue->lock);
            int contended=owner->memory.next!=NULL;
            pthread_mutex_unlock(&queue->lock);
            if (contended) {
                /* EVENT_WAIT: only a competing resource mutation needs an
                 * acknowledgement before the handler's next ordinary stop. */
                if (tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"signal-entry-contended"))
                    t->interrupt_pending=1;
            }
        }
    }
}
static void signal_wait_info(void *context,siginfo_t *info) {
    struct thread *t=context;
    if (md_signal_child_notice(info)) {
        struct md_signal_origin origin;
        signal_origin_capture(t,info,&origin);
        if (origin.valid) {
            signal_origin_consumed(t,info);
            signal_origin_translate(t,&origin,info); return;
        }
    }
    if (md_signal_native_sender(info)) {
        if (info->si_signo==SIGCHLD)
            process_reboot_info(info->si_pid,&info->si_code,&info->si_status);
        info->si_uid=process_signal_uid(t,info->si_pid,info->si_uid);
        info->si_pid=visible_pid(t,info->si_pid);
    }
}
static void signal_control_finish(struct thread *,int);
static void signal_query_publish(struct thread *t) {
    if (!t->host_signals) return;
    int native=!t->host_signal_blocked && !t->signal_shielded && !t->signal_delivery.active
        && (t->phase==IDLE || (t->phase==SIGNAL_ENTRY && t->signal_control && t->signal_control->entry_packet))
        && !md_seccomp_policy_may_trace(t->policy) && !md_seccomp_policy_may_notify(t->policy)
        && !t->debug_options && !t->debug_call_active;
    if (native!=t->native_signal_query) {
        md_broker_signal_query(broker,t->pid,native);
        t->native_signal_query=native;
    }
}
static void signal_descriptor_retired(struct thread *t,long error) {
    CHECK(!error);
    signal_control_finish(t,t->signal_control->retire_error);
}
static void signal_descriptor_selected(struct thread *t,long error) {
    struct signal_control *owner=t->signal_control;
    if (error) {
        if (owner->loan && owner->loan->number>=0) {
            struct task_action *loan=owner->loan; owner->loan=NULL;
            owner->retiring=1; owner->retire_error=error<0 ? (int)error : -EAGAIN;
            md_fd_origins_finish(&owner->publication,loan->number);
            loan_retire(loan,t,&owner->files,signal_descriptor_retired);
        } else signal_control_finish(t,error<0 ? (int)error : -EAGAIN);
        return;
    }
    unsigned required=md_fd_object_selectors(owner->descriptor);
    while (owner->selector<MD_FD_SELECTOR_KINDS) {
        unsigned kind=owner->selector++;
        if (!(required&(1U<<kind))) continue;
        int selected=selector_has(t,kind,(int)owner->descriptor_result);
        if (selected<0) { signal_descriptor_selected(t,selected); return; }
        if (selected) continue;
        if (owner->checkpoint_suspended) {
            md_task_checkpoint_restore(&checkpoints,t->pid); owner->checkpoint_suspended=0;
        }
        struct task_action *action=task_action_new(t);
        if (!action) { signal_descriptor_selected(t,-ENOMEM); return; }
        action->continuation=t->original; action->boundary=owner->control.boundary;
        action->kind=(enum md_fd_selector_kind)kind; action->number=(int)owner->descriptor_result;
        action->complete=signal_descriptor_selected;
        task_action_start(t); return;
    }
    md_fd_origins_release_custody(&owner->publication);
    md_fd_origins_finish(&owner->publication,(int)owner->descriptor_result);
    if ((int)t->original.regs[0]>=0)
        CHECK(!md_fd_origins_set_flags(t->resources.files,(int)owner->descriptor_result,owner->control.descriptor_flags));
    if (owner->loan) {
        md_fd_loan_end(owner->loan->loan); md_fd_loan_release(owner->loan->loan);
        md_fd_origins_unpin(owner->loan->loan_table); free(owner->loan); owner->loan=NULL;
    }
    CHECK(!md_fd_order_complete(&owner->files));
    signal_control_finish(t,MD_SIGNAL_WAIT_ACQUIRE);
}
static void signal_control_finish(struct thread *t,int ready) {
    struct signal_control *owner=t->signal_control;
    if (ready==MD_SIGNAL_FD_ACQUIRE) {
        CHECK(!md_resource_order_complete(&owner->memory));
        owner->descriptor_admission=1;
        int error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error<0) signal_control_finish(t,error); else signal_control_progress(t);
        return;
    }
    if (ready==MD_SIGNAL_FD_RELEASE) {
        long value=owner->control.descriptor_result;
        if (owner->control.descriptor==1) {
            if (value>=0) {
                struct md_signal_fd *file=md_signal_fd_from_object(owner->descriptor);
                md_signal_fd_update(file,owner->control.wait_set);
                for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid && peer!=t) {
                    task_readiness_changed(peer);
                    if (peer->signal_control) {
                        int error=md_task_signal_descriptor_changed(&peer->signal_control->control,file);
                        CHECK(!error || error==-ESRCH);
                    }
                }
            }
        }
        if (owner->control.descriptor==1 && value>=0) {
            owner->descriptor_result=value;
            if (owner->loan) owner->loan->number=(int)value;
            signal_descriptor_selected(t,0); return;
        }
        md_fd_origins_finish(&owner->publication,-1);
        CHECK(!md_fd_order_complete(&owner->files));
        ready=MD_SIGNAL_WAIT_ACQUIRE;
    }
    if (ready==MD_SIGNAL_WAIT_RELEASE || ready==MD_SIGNAL_WAIT_STORAGE) {
        if (ready==MD_SIGNAL_WAIT_STORAGE) {
            md_memory_page_publish(owner->wait_page,(uintptr_t)owner->control.native.result);
        } else if (!owner->wait_page) {
            owner->wait_page=md_memory_page_acquire(t->memory);
            if (!owner->wait_page) { signal_control_finish(t,-ENOMEM); return; }
        }
        signal_control_finish(t,md_task_signal_wait_park(&owner->control,md_memory_page_address(owner->wait_page)));
        return;
    }
    if (ready==MD_SIGNAL_WAIT_PARKED) {
        CHECK(!md_buffer_release(&owner->scratch));
        owner->control.scratch=NULL; owner->control.peer=0; owner->control.capacity=0;
        CHECK(!md_resource_order_complete(&owner->memory));
        signal_control_finish(t,md_task_signal_control_continue(&owner->control));
        return;
    }
    if (ready==MD_SIGNAL_WAIT_ACQUIRE) {
        owner->reacquiring=1;
        signal_control_progress(t);
        return;
    }
    if (ready==-ESRCH) { t->resume_lost=1; return; }
    if (!ready) { t->kernel_stopped=0; return; }
    long result=ready<0 ? ready : owner->control.result;
    TRACE("PROBE signal-control-complete pid=%d phase=%d signal=%d result=%ld handler=%#lx mask=%#llx\n",
        t->pid,t->phase,owner->signal,result,(unsigned long)owner->action.handler,
        (unsigned long long)owner->control.next_mask);
    if (owner->forced.si_signo) {
        if (result) {
            fprintf(stderr,"Shroot: forced signal disposition failed: %ld\n",result);
            syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
        } else CHECK(!md_signal_delivery_seccomp(&t->signal_delivery,&owner->forced));
        signal_control_discard(t); t->phase=IDLE; resume(t->pid,0); return;
    }
    if (owner->signal) {
        if (result) {
            fprintf(stderr,"Shroot: signal frame translation failed: %ld\n",result);
            syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL);
            signal_control_discard(t); t->phase=IDLE; resume(t->pid,0); return;
        }
        signal_control_discard(t); t->phase=IDLE;
        /* The kernel applied the action's native mask while building this
         * frame. Frame translation changes only the logical SIGSYS view;
         * borrowed copies restore that same native mask before completion. */
        resume(t->pid,0);
        return;
    }
    struct user_pt_regs original=t->original;
    if (!result && owner->control.sending)
        result=signal_logical_send(t,&original,&owner->control.wait_info);
    int passthrough=owner->control.passthrough;
    int restarting=owner->control.descriptor==2 && result==-EINTR;
    uint64_t restart_mask=owner->control.native.mask;
    enum md_task_call_boundary restart_at=owner->control.native.boundary;
    enum md_task_call_boundary boundary=owner->control.boundary;
    if (!result && original.regs[8]==SYS_rt_sigaction && original.regs[0]==SIGSYS
            && original.regs[1] && owner->control.requested.handler==(uintptr_t)SIG_IGN)
        for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer->tgid==t->tgid) md_signal_pending_clear(peer->signal_pending,1);
    signal_control_discard(t); t->phase=IDLE;
    if (restarting) { restart_boundary(t,&original,restart_mask,RESTART_SIGNAL,restart_at); return; }
    if (passthrough) {
        unsigned long cookie=MD_INTERCEPT_PROCESS;
        if (!process_call(t,&original,&cookie) && set_registers(t->pid,&original)) resume(t->pid,0);
        return;
    }
    if (original.regs[8]==SYS_rt_sigprocmask && t->metadata_restart) {
        metadata_restart_return(t,result); return;
    }
    return_task_value(t,&original,result,boundary);
}
static void signal_control_progress(struct thread *t) {
    retry_clear(t,RETRY_SIGNAL);
    struct signal_control *owner=t->signal_control;
    CHECK(owner && (!owner->started || owner->reacquiring || owner->descriptor_admission));
    if (owner->descriptor_admission) {
        if (md_fd_order_state(&owner->files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_SIGNAL); return; }
        CHECK(!md_fd_order_start(&owner->files));
        owner->descriptor_admission=0;
        int descriptor=(int)t->original.regs[0];
        owner->descriptor=descriptor<0 ? NULL : md_fd_origins_object(t->resources.files,descriptor,NULL);
        int error=0;
        if (t->original.regs[8]==SYS_signalfd4 && !md_signal_fd_from_object(owner->descriptor)) {
            md_fd_object_release(owner->descriptor);
            owner->descriptor=md_signal_fd_object(md_signal_fd_new());
            error=owner->descriptor ? md_fd_origins_prepare_object(t->resources.files,owner->descriptor,
                t->original.regs[3]&SFD_CLOEXEC ? FD_CLOEXEC : 0,&owner->publication) : -ENOMEM;
            if (!error && descriptor<0) {
                owner->loan=calloc(1,sizeof(*owner->loan));
                if (!owner->loan) error=-ENOMEM;
                else {
                    owner->loan->number=-1;
                    owner->loan->loan_table=md_fd_origins_pin(t->resources.files);
                    owner->loan->loan=md_fd_loan_new(t->resources.files);
                    error=owner->loan->loan ? md_fd_origins_prepare_custody(&owner->publication,
                        owner->loan->loan,&owner->files) : -ENOMEM;
                }
            }
        }
        signal_control_finish(t,error ? error
            : md_task_signal_descriptor_admit(&owner->control,md_signal_fd_from_object(owner->descriptor)));
        return;
    }
    /* Buffer admission precedes the memory turn: a returning reader can still
     * own its buffer while another reader is acquiring its FD. Holding the
     * memory queue while awaiting a pool slot would deadlock their completion. */
    int wrapped=owner->signal && md_signal_actions_entry(t->signal_actions,owner->signal);
    int native_frame=owner->signal && !t->signal_wait_view.active && !wrapped;
    int error=owner->scratch.pool || native_frame ? 0 : md_buffers_take(md_memory_buffers(t->memory),&owner->scratch);
    if (error==-EAGAIN) { retry_wait(t,RETRY_SIGNAL); return; }
    if (error) { signal_control_finish(t,error); return; }
    if (!owner->memory.queue) {
        error=md_memory_order_begin(t->memory,&owner->memory,fd_wakeup);
        if (error<0) { signal_control_finish(t,error); return; }
    }
    enum md_resource_order_state state=md_resource_order_state(&owner->memory);
    if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&owner->memory));
    else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_SIGNAL); return; }
    if (owner->reacquiring) {
        struct md_buffer_span span; CHECK(!md_buffer_span(&owner->scratch,&span));
        owner->control.scratch=span.data;
        owner->control.peer=md_memory_buffer_address(t->memory,&owner->scratch);
        owner->control.capacity=span.size;
        owner->reacquiring=0;
        signal_control_finish(t,md_task_signal_control_continue(&owner->control));
        return;
    }
    owner->started=1;
    if (owner->signal) {
        struct md_kernel_action action;
        error=md_signal_actions_read(t->signal_actions,owner->signal,&action);
        if (error || action.handler!=owner->action.handler || action.flags!=owner->action.flags
                || action.mask!=owner->action.mask) {
            /* A preceding disposition exchange won the admission turn. The
             * kernel must select that new action at the retained delivery stop. */
            int signal=owner->signal;
            signal_control_discard(t); t->phase=IDLE;
            deliver_signal(t->pid,signal,1,NULL); return;
        }
        t->phase=SIGNAL_ENTRY;
        if (wrapped) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&owner->scratch,&span));
            struct md_signal_entry *packet=span.data;
            uint64_t saved=owner->entry_mask;
            uint64_t mask=md_signal_view_enter(owner->signal,&owner->action,t->host_signal_blocked,
                &t->signal_wait_view,&saved);
            if (!owner->entry_info.si_signo
                    && !tracee_request(t,PTRACE_GETSIGINFO,NULL,&owner->entry_info,"signal-entry-info")) return;
            *packet=(struct md_signal_entry){.handler=owner->action.handler,
                .mask=saved,.pending=1,.info=owner->entry_info};
            siginfo_t transport=owner->entry_info;
            transport.si_value.sival_ptr=(void *)md_memory_buffer_address(t->memory,&owner->scratch);
            if (!tracee_request(t,PTRACE_SETSIGINFO,NULL,&transport,"signal-entry-packet")) return;
            owner->entry_blocked=t->host_signal_blocked;
            owner->entry_wait_view=t->signal_wait_view;
            t->host_signal_blocked=!!(mask&MD_SIGNAL_SYS_BIT);
            t->signal_handler_mask=mask;
            t->signal_handler_mask_known=1;
            t->signal_wait_view.active=0;
            owner->entry_packet=packet; owner->entry_thread=t;
            owner->entry_next=signal_entries; owner->entry_previous=&signal_entries;
            if (signal_entries) signal_entries->entry_previous=&owner->entry_next;
            signal_entries=owner;
            if (broker) CHECK(!md_broker_task(broker,t->pid,0,RAW_GATE,abi.watch_return,NULL,0));
            signal_query_publish(t);
            t->kernel_resume=PTRACE_CONT;
            if (native_trace(PTRACE_CONT,t->pid,NULL,(void *)(uintptr_t)owner->signal)<0) {
                CHECK(errno==ESRCH); t->resume_lost=1;
            } else t->kernel_stopped=0;
            return;
        }
        /* Outside atomic waits the native frame can carry the logical mask
         * directly. The commit stop removes SIGSYS before the handler runs;
         * no protected-memory copy is needed to amend the saved frame. Never
         * replace a temporary wait mask here: SETSIGMASK clears its restore. */
        if (!t->signal_wait_view.active && t->host_signal_blocked
                && !signal_mask(t,owner->entry_mask|MD_SIGNAL_SYS_BIT,"handler-frame-logical-mask")) return;
        /* EVENT_WAIT: kernel signal-frame construction precedes this interrupt
         * stop; no veneer or application instruction executes before admission.
         * Exit/exec cancels the owner through the ordinary task lifecycle. */
        if (!tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"handler-entry-commit")) return;
        t->kernel_resume=PTRACE_CONT;
        if (native_trace(t->kernel_resume,t->pid,NULL,(void *)(uintptr_t)owner->signal)<0) {
            CHECK(errno==ESRCH); t->resume_lost=1;
        } else t->kernel_stopped=0;
        return;
    }
    int boundary=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (boundary<0) { signal_control_finish(t,boundary); return; }
    CHECK(!boundary); owner->checkpoint_suspended=1;
    struct md_buffer_span span; CHECK(!md_buffer_span(&owner->scratch,&span));
    int ready=owner->forced.si_signo
        ? md_task_signal_default_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_actions,&t->host_signal_blocked,&owner->action,
            span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_rt_sigprocmask
        ? md_task_signal_query_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,owner->entry_mask,
            span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_signalfd4 || t->original.regs[8]==SYS_read || t->original.regs[8]==SYS_readv
        ? md_task_signal_descriptor_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_pending,&t->host_signal_blocked,
            signal_wait_info,t,span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_rt_sigtimedwait
        ? md_task_signal_wait_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_pending,&t->host_signal_blocked,
            signal_wait_info,t,span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : t->original.regs[8]==SYS_rt_sigqueueinfo || t->original.regs[8]==SYS_rt_tgsigqueueinfo
        ? md_task_signal_send_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size)
        : md_task_signal_control_begin(&owner->control,native_trace,t->pid,&t->original,
            abi.task_call,abi.task_return,t->signal_actions,&t->host_signal_blocked,
            (md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
                || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED)) ? MD_SIGNAL_SYS_BIT : 0,
            span.data,md_memory_buffer_address(t->memory,&owner->scratch),span.size);
    signal_control_finish(t,ready);
}
static int signal_return_snapshot(struct thread *t) {
    if (statistics) ++statistics->signal_frames[0];
    uintptr_t base=md_memory_readonly_base(t->memory);
    if (!base || t->signal_control || t->signal_return_pending || t->metadata_restart || t->signal_delivery.active
            || t->debug_options || t->debug_call_active || t->debug_step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    /* A queued carrier must borrow the restored application's mask, not the
     * handler's mask at this pre-execution stop. Pending logical SIGSYS can
     * also become a carrier when the restored mask unblocks it in resume(). */
    if (md_signal_delivery_pending(&t->signal_delivery)
            || md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
            || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED)) return 0;
    if (statistics) ++statistics->signal_frames[1];
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    if (regs.sp%16) return 0;
    struct signal_control *owner=calloc(1,sizeof(*owner));
    if (!owner) return 0;
    struct md_buffer_span span;
    int error=md_buffers_take(md_memory_readonly_buffers(t->memory),&owner->scratch);
    if (!error) error=md_buffer_span(&owner->scratch,&span);
    if (!error && span.size<sizeof(struct md_signal_frame)) error=-ENOSPC;
    if (!error) error=md_memory_order_begin(t->memory,&owner->memory,fd_wakeup);
    if (error>=0 && md_resource_order_state(&owner->memory)==MD_RESOURCE_ORDER_READY) {
        CHECK(!md_resource_order_start(&owner->memory));
        if (statistics) ++statistics->signal_frames[2];
        struct md_signal_frame *frame=span.data;
        ssize_t copied=md_task_copy_direct(t->pid,regs.sp,frame,sizeof(*frame),0);
        if (statistics && copied==sizeof(*frame)) ++statistics->signal_frames[3];
        if (copied==sizeof(*frame) && md_signal_frame_relocatable(frame)) {
            if (statistics) ++statistics->signal_frames[4];
            uint64_t mask;
            memcpy(&mask,&frame->context.uc_sigmask,sizeof(mask));
            t->host_signal_blocked=!!(mask&MD_SIGNAL_SYS_BIT);
            t->signal_handler_mask=mask&md_signal_blockable_mask();
            t->signal_handler_mask_known=1;
            mask&=~MD_SIGNAL_SYS_BIT;
            memcpy(&frame->context.uc_sigmask,&mask,sizeof(mask));
            regs.sp=base+span.offset;
            owner->returning=1; owner->entry_thread=t;
            owner->entry_next=signal_entries; owner->entry_previous=&signal_entries;
            if (signal_entries) signal_entries->entry_previous=&owner->entry_next;
            signal_entries=owner; t->signal_control=owner;
            /* The original syscall has passed kernel seccomp. Its immutable
             * input owns the mapping turn until the next native stop. A
             * competing mapping operation requests an acknowledgement only
             * when needed; ordinary returns need no dedicated exit stop. */
            if (set_registers(t->pid,&regs)) resume(t->pid,0);
            return 1;
        }
    }
    if (owner->memory.queue && md_resource_order_cancel(&owner->memory)==1)
        CHECK(!md_resource_order_complete(&owner->memory));
    md_buffer_release(&owner->scratch); free(owner);
    return 0;
}
static int signal_native_call(struct thread *t,uint64_t number,uint64_t input,uint64_t output,uint64_t size) {
    if (number==SYS_rt_sigreturn) {
        t->signal_handler_mask_known=0;
        if (signal_return_snapshot(t)) return 1;
        if (t->signal_delivery.active && !signal_mask(t,t->signal_delivery.mask,"signal-return-carrier-mask")) return 1;
        t->phase=SIGNAL_NATIVE_EXIT; resume(t->pid,0); return 1;
    }
    if (number==SYS_rt_sigprocmask) {
        if (input || (output && (t->host_signal_blocked || t->signal_delivery.active))) {
            if (input) t->signal_handler_mask_known=0;
            /* This original syscall has passed seccomp admission. Let the
             * kernel query or update its logical mask, with native pointer
             * validation and error/commit ordering.
             * EVENT_WAIT: syscall exit removes the borrowed SIGSYS bit before
             * any application instruction or handler can execute. */
            if (t->host_signal_blocked || t->signal_delivery.active) {
                uint64_t mask;
                if (t->signal_delivery.active) mask=t->signal_delivery.mask;
                else if (!save_signal_mask(t,&mask,"signal-call-mask")) return 1;
                uint64_t logical=md_signal_view_mask(mask,t->host_signal_blocked);
                if (!input && output && size==sizeof(mask)) {
                    ssize_t copied=md_task_copy_direct(t->pid,output,&logical,sizeof(logical),1);
                    /* The query has already passed original-site seccomp.
                     * A kernel-checked copy can complete it at this same stop,
                     * without lending the mask or running a second guest turn.
                     * Denied inspection retains the native protected path. */
                    if (copied>=0 || copied==-EFAULT) {
                        return_scalar(t,copied==sizeof(logical) ? 0 : -EFAULT);
                        return 1;
                    }
                }
                if (!signal_mask(t,logical,"signal-call-logical-mask")) return 1;
                if (!input) {
                    t->signal_query_mask=mask;
                    t->signal_query_active=1;
                }
            }
            t->phase=SIGNAL_NATIVE_EXIT;
        }
        resume(t->pid,0); return 1;
    }
    return 0;
}
static int signal_native_mask_commit(struct thread *t,uint64_t mask) {
    if (!t->signal_delivery.active) return signal_mask(t,mask,"signal-native-restore-mask");
    int error=md_signal_delivery_update_mask(&t->signal_delivery,t->pid,mask);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error); t->signal_handler_mask_known=0;
    return 1;
}
static void signal_native_complete(struct thread *t) {
    if (t->signal_query_active) {
        /* A pure query cannot change the mask, even on EFAULT or EINVAL.
         * No guest instruction runs in this loan; restore its exact snapshot
         * instead of reading the same mask again at the owned syscall exit. */
        if (!signal_native_mask_commit(t,t->signal_query_mask)) return;
        t->signal_query_active=0; t->phase=IDLE; resume(t->pid,0);
        return;
    }
    uint64_t mask;
    if (!save_signal_mask(t,&mask,"signal-native-exit-mask")) return;
    t->host_signal_blocked=!!(mask&MD_SIGNAL_SYS_BIT);
    t->phase=IDLE;
    if ((!t->signal_delivery.active && !(mask&MD_SIGNAL_SYS_BIT))
            || signal_native_mask_commit(t,mask&~MD_SIGNAL_SYS_BIT)) resume(t->pid,0);
}
static int signal_control_begin(struct thread *t,const struct user_pt_regs *r,int signal,
        const struct md_kernel_action *action,uint64_t entry_mask,const siginfo_t *info) {
    CHECK(!t->signal_control);
    struct signal_control *owner=calloc(1,sizeof(*owner));
    if (!owner) return -ENOMEM;
    owner->signal=signal;
    owner->entry_mask=entry_mask;
    if (info) owner->entry_info=*info;
    if (action) owner->action=*action;
    t->signal_control=owner;
    if (r) t->original=*r;
    t->phase=SIGNAL_CONTROL;
    signal_control_progress(t); return 0;
}
static int signal_frame_native(const struct thread *t) {
    return !t->host_signal_blocked && !t->signal_wait_view.active;
}
static int signal_control_delivery(struct thread *t,int signal,struct md_task_signal_stop *stop) {
    if (!signal || !t->host_signals || !t->entered || t->phase!=IDLE) return 0;
    struct md_kernel_action action;
    if (md_signal_actions_read(t->signal_actions,signal,&action)
            || action.handler==(uintptr_t)SIG_DFL || action.handler==(uintptr_t)SIG_IGN) return 0;
    /* A retained carrier restores the application's mask before admission.
     * A blocked signal goes back to the kernel pending queue, not through a
     * handler veneer: no handler frame will be built at this delivery stop. */
    uint64_t mask;
    /* GETSIGMASK exposes saved_sigmask while an interrupted atomic wait
     * retains TIF_RESTORE_SIGMASK. Delivery still uses its temporary mask. */
    if (t->signal_return_pending && t->signal_wait_view.active)
        mask=t->signal_wait_view.temporary;
    else if (!tracee_request(t,PTRACE_GETSIGMASK,(void *)sizeof(mask),&mask,"handler-delivery-mask")) return 1;
    if (mask&(UINT64_C(1)<<(signal-1))) return 0;
    if (signal_frame_native(t) && md_signal_actions_exclusive(t->signal_actions)
            && !md_signal_actions_entry(t->signal_actions,signal)) {
        /* No peer can replace this disposition between selection and native
         * frame construction. The saved frame is already logical; the native
         * action keeps SIGSYS clear while its logical handler mask is owned
         * here. No frame edit or commit stop is necessary. */
        struct md_kernel_action entered;
        CHECK(!md_signal_actions_enter(t->signal_actions,signal,&entered));
        uint64_t saved=mask;
        t->host_signal_blocked=!!(md_signal_view_enter(signal,&entered,0,NULL,&saved)&MD_SIGNAL_SYS_BIT);
        resume(t->pid,signal); return 1;
    }
    int error=signal_control_begin(t,NULL,signal,&action,mask,stop && stop->has_info ? &stop->info : NULL);
    if (error) {
        fprintf(stderr,"Shroot: signal admission failed: %d\n",error);
        syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL); resume(t->pid,0);
    }
    return 1;
}
static int signal_control_event(struct thread *t,int status) {
    struct signal_control *owner=t->signal_control;
    if (!owner || owner->retiring || !owner->started || !WIFSTOPPED(status)
            || (unsigned)status>>16==PTRACE_EVENT_EXIT || (unsigned)status>>16==PTRACE_EVENT_EXEC) return 0;
    if (owner->entry_packet) {
        struct user_pt_regs regs;
        if (atomic_load_explicit(&owner->entry_packet->pending,memory_order_acquire)) {
            if (!registers(t->pid,&regs)) return 1;
            if (regs.pc>=abi.signal_entry && regs.pc<abi.signal_entry_committed) {
                struct md_signal_entry *packet=owner->entry_packet;
                CHECK(md_task_copy_direct(t->pid,regs.regs[1],
                    &packet->info,sizeof(packet->info),1)==sizeof(packet->info));
                CHECK(md_task_copy_direct(t->pid,regs.regs[2]+MD_SIGNAL_CONTEXT_MASK,
                    &packet->mask,sizeof(packet->mask),1)==sizeof(packet->mask));
                regs.pc=packet->handler;
                if (!set_registers(t->pid,&regs)) return 1;
            } else {
                /* A delivery stop before frame construction retains its owner. */
                if ((unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
                    t->kernel_resume=PTRACE_CONT;
                    if (!tracee_request(t,PTRACE_CONT,NULL,NULL,"signal-entry-pending")) return 1;
                    t->kernel_stopped=0; return 1;
                }
                if (!((unsigned)status>>16) && WSTOPSIG(status)==SIGSEGV) {
                    siginfo_t info;
                    if (!tracee_request(t,PTRACE_GETSIGINFO,NULL,&info,"signal-frame-failure")) return 1;
                    if (info.si_code==SI_KERNEL) {
                        signal_entry_complete(t,1); return 0;
                    }
                }
                CHECK(0);
            }
        }
        signal_entry_complete(t,0);
        if ((unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
            resume(t->pid,0); return 1;
        }
        return 0;
    }
    struct md_task_call *call=md_task_signal_control_call(&owner->control);
    if (call) {
        if (task_call_signal(t,call,status)) return 1;
        signal_control_finish(t,md_task_signal_control_event(&owner->control,status)); return 1;
    }
    if (t->phase!=SIGNAL_ENTRY || (unsigned)status>>16!=PTRACE_EVENT_STOP
            || WSTOPSIG(status)!=SIGTRAP) return 0;
    struct md_kernel_action entered;
    CHECK(!md_signal_actions_enter(t->signal_actions,owner->signal,&entered));
    if (!t->signal_wait_view.active) {
        uint64_t saved=owner->entry_mask;
        uint64_t next=md_signal_view_enter(owner->signal,&owner->action,t->host_signal_blocked,NULL,&saved);
        if (t->host_signal_blocked
                && !signal_mask(t,next&~MD_SIGNAL_SYS_BIT,"handler-frame-native-mask")) return 1;
        t->host_signal_blocked=!!(next&MD_SIGNAL_SYS_BIT);
        signal_control_discard(t); t->phase=IDLE; resume(t->pid,0); return 1;
    }
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return 1;
    TRACE("PROBE signal-entry pid=%d phase=%d pc=%#llx signal=%llu\n",t->pid,t->phase,regs.pc,regs.regs[0]);
    CHECK(regs.pc==owner->action.handler && regs.regs[0]==(unsigned)owner->signal);
    struct md_buffer_span span={0};
    if (owner->scratch.pool) CHECK(!md_buffer_span(&owner->scratch,&span));
    int ready=md_task_signal_wait_entry_begin(&owner->control,native_trace,t->pid,&regs,
        abi.task_call,abi.task_return,t->signal_actions,&t->host_signal_blocked,&owner->action,
        &t->signal_wait_view,span.data,
        owner->scratch.pool ? md_memory_buffer_address(t->memory,&owner->scratch) : 0,span.size);
    t->signal_wait_view.active=0;
    signal_control_finish(t,ready); return 1;
}
static int host_policy_deliver(struct thread *t,struct user_pt_regs *regs,const siginfo_t *info,int deferred) {
    struct md_kernel_action current;
    CHECK(!md_signal_actions_read(t->signal_actions,SIGSYS,&current));
    if (t->host_signal_blocked || current.handler==(uintptr_t)SIG_IGN) {
        struct signal_control *owner=calloc(1,sizeof(*owner)); CHECK(owner);
        owner->forced=*info; owner->action=current;
        t->signal_control=owner; t->original=*regs; t->phase=SIGNAL_CONTROL;
        CHECK(md_memory_order_begin(t->memory,&owner->memory,fd_wakeup)>=0);
        signal_control_progress(t); return 1;
    }
    if (deferred) {
        CHECK(!md_signal_delivery_seccomp(&t->signal_delivery,info));
        if (set_registers(t->pid,regs)) resume(t->pid,0);
        return 1;
    }
    return 0;
}
static uint32_t host_policy_interception(const struct seccomp_data *call) {
    if (call->arch!=AUDIT_ARCH_AARCH64) return SECCOMP_RET_ALLOW;
    if (call->nr==SYS_set_robust_list || call->nr==SYS_get_robust_list)
        return SECCOMP_RET_TRACE|MD_INTERCEPT_OBSERVE;
    /* These optional native facilities have no userspace implementation.
     * Report absence only after a host denial, never fake successful
     * registrations, NUMA placement or clock discipline. Guest policy
     * takes precedence; permitted native calls do not enter this path. */
    switch (call->nr) {
#define OPTIONAL(n) case SYS_##n:
        MD_HOST_OPTIONAL_CALLS(OPTIONAL)
#undef OPTIONAL
            return SECCOMP_RET_ERRNO|ENOSYS;
    }
    /* Inherited TRAP outranks our ERRNO/TRACE/USER_NOTIF. Reuse the original
     * interception policy, not a second list of adapter-supported syscalls.
     * Native calls remain denied; guest policy was evaluated before entry. */
    const struct md_interception_routes *routes=interception_routes();
    if (md_seccomp_cache_allows(&routes->base_allow,call)) return SECCOMP_RET_ALLOW;
    struct sock_fprog program={routes->base.count,(struct sock_filter *)routes->base.code}; uint32_t decision;
    CHECK(!md_seccomp_program_eval(&program,call,&decision));
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    unsigned cookie=decision&SECCOMP_RET_DATA;
    if (action==SECCOMP_RET_ERRNO || action==SECCOMP_RET_USER_NOTIF
            || (action==SECCOMP_RET_TRACE && (cookie==MD_INTERCEPT_IDENTITY
                || cookie==MD_INTERCEPT_OBSERVE || cookie==MD_INTERCEPT_DISPATCH))) return decision;
    return SECCOMP_RET_ALLOW;
}
static int host_policy_dispatch(struct thread *t,struct user_pt_regs *regs,const struct seccomp_data *call) {
    uint32_t decision=host_policy_interception(call);
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_ALLOW) return 0;
    /* The kernel syscall exit preceded this SIGSYS. Publish the logical exit
     * only after its result or asynchronous adapter has completed. */
    if (t->debug_call_active) t->debug_adapter=1;
    if (action==SECCOMP_RET_ERRNO) {
        regs->regs[0]=md_seccomp_policy_error(decision);
        if (set_registers(t->pid,regs)) resume(t->pid,0);
        return 1;
    }
    unsigned long cookie=action==SECCOMP_RET_USER_NOTIF ? MD_INTERCEPT_DISPATCH : decision&SECCOMP_RET_DATA;
    regs->pc=call->instruction_pointer; regs->regs[8]=call->nr;
    t->original=*regs; ++calls; ++t->calls;
    if (robust_call(t,regs,MD_TASK_CALL_USER)) return 1;
    if (identity_call(t,regs,cookie) || domain_call(t,regs,cookie)) return 1;
    if (cookie!=MD_INTERCEPT_DISPATCH) return 0;
    if (call->nr==SYS_shmdt) { memory_begin(t,regs,MEMORY_DETACH,0); return 1; }
    if (save_signal_mask(t,&t->mask,"host-policy-dispatch-mask") && shield(t)) dispatch_with_stack(t);
    return 1;
}
static int host_policy_signal(struct thread *t,struct md_task_signal_stop *stop) {
    if (!t->host_signals || !t->entered) return 0;
    if (!signal_observe(t,stop,0)) return 1;
    siginfo_t info=stop->info;
    if (info.si_code!=SYS_SECCOMP) {
        if (t->host_signal_blocked) {
            /* Kernel delivery has already selected this task. Like ptrace's
             * blocked-signal requeue, retain that target; SI_QUEUE does not
             * distinguish rt_sigqueueinfo from rt_tgsigqueueinfo. Guest sends
             * retain their original scope at generation instead. */
            CHECK(md_signal_pending_put(t->signal_pending,MD_SIGNAL_PRIVATE,&info)>=0);
            resume(t->pid,0); return 1;
        }
        struct md_kernel_action action;
        if (!md_signal_actions_read(t->signal_actions,SIGSYS,&action)
                && action.handler==(uintptr_t)SIG_IGN) { resume(t->pid,0); return 1; }
        return 0;
    }
    if (t->phase!=IDLE) return 0;
    if (!signal_observe(t,stop,1)) return 1;
    struct user_pt_regs regs=stop->regs;
    struct seccomp_data call={.nr=info.si_syscall,.arch=info.si_arch,
        .instruction_pointer=(uintptr_t)info.si_call_addr};
    memcpy(call.args,regs.regs,sizeof(call.args));
    uint32_t decision;
    CHECK(!md_seccomp_policy_eval(admission_policy(t),&call,&decision));
    t->admission_policy_pending=0;
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_TRAP) {
        info.si_errno=decision&SECCOMP_RET_DATA;
        if (!signal_replace_info(t,stop,&info)) return 1;
        return host_policy_deliver(t,&regs,&info,0);
    }
    if (action==SECCOMP_RET_ERRNO || action==SECCOMP_RET_TRACE) {
        if (t->debug_call_active) t->debug_adapter=1;
        regs.regs[0]=action==SECCOMP_RET_TRACE ? -ENOSYS : md_seccomp_policy_error(decision);
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return 1;
    }
    if (action==SECCOMP_RET_USER_NOTIF) {
        if (t->debug_call_active) t->debug_adapter=1;
        return guest_notification_begin(t,&call,&regs,NULL,&info);
    }
    if (action!=SECCOMP_RET_ALLOW && action!=SECCOMP_RET_LOG) return 0;
    return host_policy_dispatch(t,&regs,&call);
}
static int svc_revoke_entry(struct thread *t) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (registry) registry->filter_paused=0;
    int error=md_svc_registry_revoke(registry,t->pid);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error);
    return 1;
}
static int filter_revoke_entry(struct thread *t,const struct user_pt_regs *r) {
    int group=md_seccomp_filter_flags(r->regs[8],r->regs[1])&SECCOMP_FILTER_FLAG_TSYNC;
    int error=md_svc_registry_pause_filter(md_memory_svc(t->memory),t->pid);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error);
    for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid
            && (peer==t || (group && peer->tgid==t->tgid))) {
        peer->cooperative_blocked=1;
        md_broker_cooperative_revoke(broker,peer->pid);
    }
    return 1;
}
static int filter_entry_eligible(const struct thread *t) {
    if (!t->application_filter) return 1;
    const struct md_svc_registry *registry=md_memory_svc(t->memory);
    return registry && md_seccomp_policy_entry_compatible(t->policy,registry->image)
        && md_memory_snapshot_address(t->memory,md_seccomp_policy_identity(t->policy));
}
static void filter_entry_rearm(struct thread *t) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!registry || !registry->filter_paused || !registry->entry_fence_drained
            || md_memory_exposed(t->memory) || !md_memory_snapshots_valid(t->memory)) return;
    int filtered=0;
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
        if (!filter_entry_eligible(p) || p->debug_options || md_debugger_traced(debugger,p->pid)) return;
        filtered|=p->application_filter;
    }
    if (filtered && statistics && !registry->filter_statistics) {
        struct md_svc_payload_state state;
        if (!md_svc_registry_read(t->pid,registry->frames+MD_SVC_POOL_SIZE,&state,sizeof(state))) {
            registry->filter_completed_start=state.completed;
            registry->filter_statistics=1;
        }
    }
    uintptr_t mode=filtered ? 2 : 1;
    int error=md_svc_registry_write(t->pid,abi.cooperative_registration,&mode,sizeof(mode));
    if (!error) error=md_svc_registry_rearm_filter(registry,t->pid);
    if (error) { TRACE("PROBE filter-entry-rearm pid=%d error=%d\n",t->pid,error); return; }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
        p->cooperative_blocked=0; p->registration_revoked=0;
        cooperative_memory_publish(p,0);
        /* Paused sites contain their original SVC until observed again. */
        md_broker_resample(broker,p->pid,0);
    }
    TRACE("PROBE filter-entry-ready pid=%d\n",t->pid);
}
static void filter_entry_complete(struct thread *t,const struct user_pt_regs *regs) {
    filter_entry_rearm(t);
    filter_install_discard(t); t->phase=IDLE;
    struct user_pt_regs continuation=*regs;
    if (set_registers(t->pid,&continuation)) resume(t->pid,0);
}
static int filter_entry_map(struct thread *t,const struct user_pt_regs *regs) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!registry || !registry->filter_paused || !t->policy
            || !md_seccomp_policy_entry_compatible(t->policy,registry->image)
            || !md_memory_snapshots_valid(t->memory)) return 0;
    struct buffer_mapping *owner=calloc(1,sizeof(*owner));
    if (!owner) return 0;
    for (const struct md_seccomp_policy *p=md_seccomp_policy_parent(t->policy);p;p=md_seccomp_policy_parent(p)) {
        owner->parent_view=md_memory_snapshot_address(t->memory,md_seccomp_policy_identity(p));
        if (owner->parent_view) { owner->ancestor=md_seccomp_policy_identity(p); break; }
    }
    size_t size=0;
    int error=md_seccomp_policy_view_size(t->policy,owner->ancestor,&size);
    if (!error) error=md_buffers_create_readonly(1,size,&owner->buffers);
    if (error) { free(owner); return 0; }
    owner->policy=md_seccomp_policy_retain(t->policy);
    return !buffer_mapping_start(t,regs,owner);
}
static void filter_install_discard(struct thread *t) {
    retry_clear(t,RETRY_FILTER);
    struct filter_install *install=t->filter_install;
    if (!install) return;
    policy_sync_release(t);
    if (install->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    md_seccomp_capture_discard(&install->capture);
    if (install->virtual_listener && install->source>=0) close(install->source);
    if (install->loan) {
        struct task_action *loan=install->loan;
        if (loan->number>=0 && !cancelling) {
            md_fd_origins_finish(&install->listener,loan->number);
            loan_retire(loan,NULL,NULL,NULL);
        } else {
            if (loan->loan && md_fd_loan_active_in(loan->loan,loan->loan_table)) md_fd_loan_end(loan->loan);
            md_fd_loan_release(loan->loan); md_fd_origins_unpin(loan->loan_table); free(loan);
        }
    }
    md_fd_origins_abandon(&install->listener);
    if (install->files.table && md_fd_order_cancel(&install->files)==1)
        CHECK(!md_fd_order_complete(&install->files));
    md_buffer_release(&install->scratch);
    if (install->memory.queue && md_resource_order_cancel(&install->memory)==1)
        CHECK(!md_resource_order_complete(&install->memory));
    free(install); t->filter_install=NULL;
}
static void filter_install_retired(struct thread *t,long result) {
    CHECK(!result);
    long error=t->filter_install->error;
    int completed=t->filter_install->native_completed;
    filter_entry_rearm(t);
    filter_install_discard(t); t->phase=IDLE;
    struct user_pt_regs original=t->original;
    if (completed) {
        original.regs[0]=error;
        if (set_registers(t->pid,&original)) resume(t->pid,0);
    } else return_value(t,&original,error);
}
static void filter_install_fail(struct thread *t,int error) {
    struct filter_install *install=t->filter_install;
    TRACE("PROBE filter-install-failed pid=%d stage=%u admission=%u error=%d\n",
        t->pid,install->stage,install->admission.stage,error);
    install->error=error;
    if (install->checkpoint_suspended) {
        md_task_checkpoint_restore(&checkpoints,t->pid); install->checkpoint_suspended=0;
    }
    if (install->loan && install->loan->number>=0) {
        struct task_action *loan=install->loan; install->loan=NULL;
        md_fd_origins_finish(&install->listener,loan->number);
        install->retiring=1;
        loan_retire(loan,t,&install->files,filter_install_retired);
    } else filter_install_retired(t,0);
}
static int filter_listener_prepare(struct thread *t) {
    struct filter_install *install=t->filter_install;
    if (!install->virtual_listener || install->listener.table) return 0;
    if (!install->files.table) {
        int error=md_fd_order_begin(t->resources.files,&install->files,fd_wakeup);
        if (error<0) return error;
    }
    if (md_fd_order_state(&install->files)==MD_RESOURCE_ORDER_QUEUED) return -EAGAIN;
    if (md_fd_order_state(&install->files)==MD_RESOURCE_ORDER_READY) CHECK(!md_fd_order_start(&install->files));
    struct md_fd_object *object=md_seccomp_listener_virtual(install->capture.policy,
        &(struct md_control_opener){.identity=&t->identity,.network=t->resources.network},fd_wakeup);
    if (!object) return -errno;
    int error=md_fd_origins_prepare_object(t->resources.files,object,FD_CLOEXEC,&install->listener);
    if (!error) {
        install->source=md_proc_file_descriptor(md_proc_file_from_object(object));
        if (install->source<0) error=install->source;
    }
    md_fd_object_release(object);
    if (!error) {
        struct task_action *loan=calloc(1,sizeof(*loan));
        if (!loan) error=-ENOMEM;
        else {
            install->loan=loan; loan->number=-1;
            loan->loan_table=md_fd_origins_pin(t->resources.files);
            loan->loan=md_fd_loan_new(t->resources.files);
            error=loan->loan ? md_fd_origins_prepare_custody(&install->listener,loan->loan,&install->files) : -ENOMEM;
        }
    }
    return error;
}
static int filter_listener_admit(struct thread *t) {
    struct filter_install *install=t->filter_install;
    if (!install->virtual_listener || install->admitted) return 0;
    int error=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (error>=0) { CHECK(!error); install->checkpoint_suspended=1; }
    if (!error) {
        struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_SECCOMP_LISTENER}};
        error=md_task_call_begin(&install->admission,native_trace,t->pid,MD_TASK_CALL_SECCOMP,
            &t->original,abi.task_call,abi.task_return,&args);
        if (error && install->admission.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return 1; }
        if (!error) { t->kernel_stopped=0; return 1; }
    }
    return error;
}
static int filter_listener_notification(struct thread *t,const struct seccomp_notif *q) {
    struct filter_install *install=t->filter_install;
    if (install) TRACE("PROBE filter-listener-admission pid=%d stage=%u nr=%d pc=%llx expected=%lx args=%llx,%llx,%llx,%llx,%llx,%llx\n",
        t->pid,install->admission.stage,q->data.nr,(unsigned long long)q->data.instruction_pointer,
        (unsigned long)install->admission.returned,(unsigned long long)q->data.args[0],
        (unsigned long long)q->data.args[1],(unsigned long long)q->data.args[2],
        (unsigned long long)q->data.args[3],(unsigned long long)q->data.args[4],(unsigned long long)q->data.args[5]);
    if (!install || !install->virtual_listener || !md_task_call_notification(&install->admission,q)) return 0;
    CHECK(install->loan && install->loan->number<0 && md_fd_order_state(&install->files)==MD_RESOURCE_ORDER_RUNNING);
    struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
        .srcfd=install->source,.newfd_flags=O_CLOEXEC};
    int number=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
    TRACE("PROBE filter-listener-injected pid=%d fd=%d error=%d\n",t->pid,number,number<0 ? errno : 0);
    if (number>=0) install->loan->number=number;
    else if (errno!=ENOENT) {
        struct seccomp_notif_resp reply={.id=q->id,.error=-errno};
        CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
    }
    return 1;
}
static int policy_pending_visit(void *context,const siginfo_t *info) {
    (void)context;
    return info->si_signo==SIGSYS && info->si_code==SYS_SECCOMP;
}
static void policy_admission_retain(struct thread *t,int pending) {
    if (!t->admission_policy_valid) {
        t->admission_policy=md_seccomp_policy_retain(t->policy);
        t->admission_application_filter=t->application_filter;
        t->admission_policy_valid=1;
    }
    t->admission_policy_pending|=pending;
}
static int policy_sync_begin(struct thread *t) {
    if (!(md_seccomp_filter_flags(t->original.regs[8],t->original.regs[1])
            &SECCOMP_FILTER_FLAG_TSYNC)) return 0;
    if (policy_installer==t) return policy_barrier_ready ? 0 : -EAGAIN;
    if (policy_barrier || quiescence_busy(NULL) || md_task_helpers_group_busy(helpers,t->tgid)) return -EAGAIN;
    policy_barrier=md_task_quiesce_new(quiesce_trace);
    if (!policy_barrier) return -ENOMEM;
    policy_installer=t; policy_barrier_ready=0;
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p!=t && p->tgid==t->tgid) {
        /* These admitted waits cannot return to application code without an
         * owned syscall/checkpoint stop. Interrupting them would invalidate a
         * notification ID or replay a socket effect against the new policy.
         * Hold their next event instead of cancelling the native request. */
        if ((p->notification && !p->notification->promoting)
                || signal_control_parked(p) || p->phase==SOCKET_NATIVE) continue;
        CHECK(!quiesce_task_add(policy_barrier,p,0));
    }
    CHECK(!md_task_quiesce_stop(policy_barrier));
    return -EAGAIN;
}
static void policy_sync_progress(void) {
    if (!policy_installer || policy_barrier_ready || !md_task_quiesce_ready(policy_barrier)) return;
    /* Kernel admission precedes signal/ptrace observation. Retain the old
     * immutable head for a collected entry or queued forced signal, including
     * a fork whose child has not yet been published by the coordinator. */
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p!=policy_installer
            && md_task_quiesce_contains(policy_barrier,p->pid)) {
        struct md_task_quiesce_stop stop;
        int error=md_task_quiesce_borrow(policy_barrier,p->pid,&stop);
        if (error==-ESRCH) continue;
        CHECK(!error);
        unsigned event=(unsigned)stop.status>>16;
        int admitted=stop.kind==MD_TASK_QUIESCE_EVENT
            && (event==PTRACE_EVENT_SECCOMP || event==PTRACE_EVENT_CLONE
                || event==PTRACE_EVENT_FORK || event==PTRACE_EVENT_VFORK);
        if (stop.kind==MD_TASK_QUIESCE_EVENT && !event && WSTOPSIG(stop.status)==SIGSYS) {
            siginfo_t info;
            if (native_trace(PTRACE_GETSIGINFO,p->pid,NULL,&info)>=0) admitted=info.si_code==SYS_SECCOMP;
            else CHECK(errno==ESRCH);
        }
        int pending=p->host_signals ? md_task_pending_scan(native_trace,p->pid,policy_pending_visit,NULL) : 0;
        if (pending<0) CHECK(pending==-ESRCH);
        if (admitted || pending>0) policy_admission_retain(p,pending>0);
        CHECK(!md_task_quiesce_return(policy_barrier,p->pid));
    }
    policy_barrier_ready=1;
    filter_install_progress(policy_installer);
}
static void policy_sync_release(struct thread *t) {
    if (policy_installer!=t) return;
    policy_installer=NULL; policy_barrier_ready=0;
    CHECK(!md_task_quiesce_release(policy_barrier));
    task_action_unpark();
}
static void filter_install_progress(struct thread *t) {
    retry_clear(t,RETRY_FILTER);
    struct filter_install *install=t->filter_install;
    CHECK(install && t->phase==FILTER_CAPTURE && !install->copy.scratch);
    enum md_resource_order_state state=md_resource_order_state(&install->memory);
    if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&install->memory));
    else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_FILTER); return; }
    if (!md_memory_readonly_base(t->memory) || !t->registration_revoked
            || md_fd_selector_has_mask(t->fd_selector,MD_FD_SANDBOX_SELECTORS,-1)!=1) {
        t->filter_preflight=1;
        if (save_signal_mask(t,&t->mask,"filter-admission-mask")
                && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    if (!md_memory_readonly_base(t->memory)) { filter_install_fail(t,-EFAULT); return; }
    while (install->stage<2) {
        void *data=install->stage ? (void *)install->code : (void *)&install->program;
        uintptr_t address=install->stage ? (uintptr_t)install->program.filter : t->original.regs[2];
        size_t size=install->stage ? install->program.len*sizeof(*install->code) : sizeof(install->program);
        if (install->stage && (!install->program.len || install->program.len>BPF_MAXINSNS)) {
            filter_install_fail(t,-EINVAL); return;
        }
        ssize_t copied=md_task_copy_direct(t->pid,address,data,size,0);
        if (copied==-EPERM || copied==-EACCES) {
            int error=install->scratch.pool ? 0 : md_buffers_take(md_memory_buffers(t->memory),&install->scratch);
            if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
            struct md_buffer_span span;
            if (!error) error=md_buffer_span(&install->scratch,&span);
            if (!error) {
                int boundary=md_task_checkpoint_suspend(&checkpoints,t->pid);
                if (boundary<0) error=boundary;
                else { CHECK(!boundary); install->checkpoint_suspended=1; }
            }
            if (!error) error=md_task_copy_begin(&install->copy,native_trace,t->pid,MD_TASK_CALL_SECCOMP,
                &t->original,abi.task_call,abi.task_return,address,data,size,0,span.data,
                md_memory_buffer_address(t->memory,&install->scratch),span.size);
            if (error && install->copy.call.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return; }
            if (error) filter_install_fail(t,error);
            else t->kernel_stopped=0;
            return;
        }
        if (copied!=(ssize_t)size) { filter_install_fail(t,copied<0 ? (int)copied : -EFAULT); return; }
        ++install->stage;
    }
    if (install->stage==2) {
        struct sock_fprog program={install->program.len,install->code};
        const uint32_t delegated[]={SYS_gettid,SYS_seccomp,SYS_prctl,SYS_rt_sigaction,SYS_rt_sigprocmask,
            SYS_rt_sigreturn,SYS_rt_sigpending,SYS_rt_sigtimedwait,SYS_process_vm_readv,SYS_process_vm_writev,
            SYS_ppoll,SYS_pselect6,SYS_epoll_pwait,SYS_epoll_pwait2,SYS_signalfd4,SYS_fcntl,SYS_mmap};
        struct md_seccomp_gate gates[4+MD_SVC_CODE_POOLS]={
            {abi.socket_return,abi.socket_return,SYS_accept4,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK},
            {abi.buffer_admit,abi.buffer_admit,SYS_gettid,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK},
            {abi.task_return,abi.task_return,-1,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK}};
        struct md_svc_registry *registry=md_memory_svc(t->memory);
        size_t gate_count=3;
        if (registry) {
            gates[gate_count++]=(struct md_seccomp_gate){abi.code_begin,abi.code_end,-1,SECCOMP_RET_ALLOW};
            for (unsigned i=0;i<registry->pool_count;++i)
                gates[gate_count++]=(struct md_seccomp_gate){registry->pools[i].address,
                    registry->pools[i].address+MD_SVC_CODE_BYTES,-1,SECCOMP_RET_TRACE|MD_INTERCEPT_TASK};
        }
        const struct md_seccomp_route route={.delegation_pc=abi.task_return,.notify_cookie=MD_INTERCEPT_NOTIFY,
            .gates=gates,.gate_count=gate_count,.entry_first=1,.entry_image=registry ? registry->image : 0};
        int error=md_seccomp_capture_prepare(&install->capture,md_memory_readonly_buffers(t->memory),
            md_memory_readonly_base(t->memory),t->policy,&program,
            delegated,t->host_signals ? sizeof(delegated)/sizeof(*delegated) : 3,
            MD_INTERCEPT_TASK,&route);
        if (error==-E2BIG && registry) {
            struct md_seccomp_route fallback=route;
            fallback.gate_count=3; fallback.entry_first=0; fallback.entry_image=0;
            error=md_seccomp_capture_prepare(&install->capture,md_memory_readonly_buffers(t->memory),
                md_memory_readonly_base(t->memory),t->policy,&program,
                delegated,t->host_signals ? sizeof(delegated)/sizeof(*delegated) : 3,
                MD_INTERCEPT_TASK,&fallback);
        }
        if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
        if (error) { filter_install_fail(t,error); return; }
        ++install->stage;
    }
    int error=filter_listener_prepare(t);
    if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
    if (error) { filter_install_fail(t,error); return; }
    error=filter_listener_admit(t);
    if (error>0) return;
    if (error) { filter_install_fail(t,error); return; }
    error=policy_sync_begin(t);
    if (error==-EAGAIN) { retry_wait(t,RETRY_FILTER); return; }
    if (error) { filter_install_fail(t,error); return; }
    struct user_pt_regs call=t->original;
    call.regs[2]=install->capture.program;
    if (install->virtual_listener)
        call.regs[1]&=~(SECCOMP_FILTER_FLAG_NEW_LISTENER|SECCOMP_FILTER_FLAG_WAIT_KILLABLE_RECV);
    t->phase=OBSERVING;
    if (set_registers(t->pid,&call)) resume(t->pid,0);
}
static int filter_install_event(struct thread *t,int status) {
    struct filter_install *install=t->filter_install;
    if (!install || install->retiring || !WIFSTOPPED(status)
            || (unsigned)status>>16==PTRACE_EVENT_EXIT || (unsigned)status>>16==PTRACE_EVENT_EXEC) return 0;
    if (install->admission.stage && !install->admitted) {
        if (task_call_signal(t,&install->admission,status)) return 1;
        int ready=md_task_call_event(&install->admission,status);
        if (ready<0) { CHECK(ready==-ESRCH); t->resume_lost=1; return 1; }
        if (!ready) { t->kernel_stopped=0; return 1; }
        md_task_checkpoint_restore(&checkpoints,t->pid); install->checkpoint_suspended=0;
        if (install->admission.result<0) filter_install_fail(t,(int)install->admission.result);
        else {
            CHECK(install->loan->number==install->admission.result);
            install->admitted=1; filter_install_progress(t);
        }
        return 1;
    }
    if (!install->copy.scratch) return 0;
    if (task_call_signal(t,&install->copy.call,status)) return 1;
    int ready=md_task_copy_event(&install->copy,status);
    if (ready<0) { CHECK(ready==-ESRCH); t->resume_lost=1; return 1; }
    if (!ready) { t->kernel_stopped=0; return 1; }
    long copied=install->copy.result;
    size_t size=install->copy.size;
    install->copy=(struct md_task_copy){0};
    md_task_checkpoint_restore(&checkpoints,t->pid); install->checkpoint_suspended=0;
    if (copied!=(long)size) { filter_install_fail(t,copied<0 ? (int)copied : -EFAULT); return 1; }
    ++install->stage; filter_install_progress(t); return 1;
}
static void filter_install_begin(struct thread *t,const struct user_pt_regs *r) {
    CHECK(!t->filter_install);
    unsigned flags=md_seccomp_filter_flags(r->regs[8],r->regs[1]);
    if (flags&SECCOMP_FILTER_FLAG_NEW_LISTENER) {
        int error=md_seccomp_listener_flags(flags);
        if (error) { filter_entry_rearm(t); struct user_pt_regs call=*r; return_value(t,&call,error); return; }
    }
    struct filter_install *install=calloc(1,sizeof(*install));
    if (!install) { filter_entry_rearm(t); struct user_pt_regs call=*r; return_value(t,&call,-ENOMEM); return; }
    install->source=-1; install->virtual_listener=!!(flags&SECCOMP_FILTER_FLAG_NEW_LISTENER);
    t->filter_install=install; t->original=*r; t->phase=FILTER_CAPTURE;
    int error=md_memory_order_begin(t->memory,&install->memory,fd_wakeup);
    if (error<0) filter_install_fail(t,error);
    else filter_install_progress(t);
}
static int filter_observed(struct thread *t,const struct user_pt_regs *regs) {
    long result=(long)regs->regs[0];
    const struct user_pt_regs *r=&t->original;
    if (!t->entered || !md_seccomp_filter_call(r->regs[8],r->regs[0],r->regs[1])) return 0;
    unsigned flags=md_seccomp_filter_flags(r->regs[8],r->regs[1]);
    unsigned length=t->filter_install ? t->filter_install->program.len : (unsigned)t->filter_length;
    struct md_seccomp_policy *published=NULL;
    if (t->filter_install) {
        struct filter_install *install=t->filter_install;
        if (install->virtual_listener && install->loan) {
            CHECK(install->admitted && result==install->loan->number);
            md_fd_origins_release_custody(&install->listener);
            md_fd_loan_end(install->loan->loan);
            md_fd_loan_release(install->loan->loan);
            md_fd_origins_unpin(install->loan->loan_table); free(install->loan); install->loan=NULL;
        }
        int complete=md_seccomp_capture_complete(&t->filter_install->capture,flags,result,&published);
        CHECK(complete>=0);
        md_fd_origins_finish(&t->filter_install->listener,complete ? (int)result : -1);
    }
    if (!md_seccomp_filter_committed(flags,result)) {
        filter_entry_rearm(t); filter_install_discard(t); return 0;
    }
    if (published) {
        md_seccomp_policy_replace(&t->policy,published);
        if (flags&SECCOMP_FILTER_FLAG_TSYNC)
            for (struct thread *peer=threads;peer;peer=peer->next)
                if (peer->pid && peer!=t && peer->tgid==t->tgid) {
                    md_seccomp_policy_replace(&peer->policy,published);
                }
        md_seccomp_policy_release(published);
    }
    t->application_filter=1;
    if (flags&SECCOMP_FILTER_FLAG_TSYNC)
        for (struct thread *peer=threads;peer;peer=peer->next)
            if (peer->pid && peer->tgid==t->tgid) peer->application_filter=1;
    TRACE("PROBE installed-filter pid=%d instructions=%u\n",t->pid,length);
    if (filter_entry_map(t,regs)) return 1;
    filter_install_discard(t);
    return 0;
}
static int domain_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    long nr = r->regs[8];
    /* Descriptor-table ioctls retain native validation and FD bookkeeping;
     * a managed/offline socket does not give them network semantics. */
    if (cookie==MD_INTERCEPT_FD && nr==SYS_ioctl) return 0;
    if (nr==SYS_reboot && t->phase==IDLE) {
        if (entry_context(t,MD_CONTEXT_PROCESS)) return 1;
        struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
        int error=md_pid_namespace_reboot(space,&t->identity,r->regs[0],r->regs[1],r->regs[2]);
        if (error<0) { return_value(t,r,error); return 1; }
        struct md_pid_identity *init=md_pid_namespace_lookup(space,1);
        pid_t target=md_pid_identity_native(init);
        md_pid_identity_release(init);
        if (target<=0) { return_value(t,r,-ESRCH); return 1; }
        /* Never issue a host reboot. PID 1 death uses the existing namespace
         * descendant owner; parent wait translation retains the Linux reason. */
        if (kill(target,SIGKILL)) {
            return_value(t,r,-errno); return 1;
        }
        md_pid_namespace_reboot_started(space,error);
        if (t->tgid==target) { resume(t->pid,0); return 1; }
        int number=SYS_exit;
        struct iovec call={&number,sizeof(number)};
        r->regs[8]=number; r->regs[0]=0;
        if (set_registers(t->pid,r) && tracee_request(t,PTRACE_SETREGSET,(void *)NT_ARM_SYSTEM_CALL,&call,"namespace-reboot-exit"))
            resume(t->pid,0);
        return 1;
    }
    if (t->ready && t->entered && t->phase==IDLE && cookie==MD_INTERCEPT_OBSERVE && r->pc!=RAW_GATE
            && md_seccomp_filter_call(nr,r->regs[0],r->regs[1])) {
        if (entry_context(t,MD_CONTEXT_POLICY)) return 1;
        /* Revoke existing entries before filter commit, including TSYNC. The
         * guest preflight revokes new registration in its own address space;
         * a protected fork need not grant the supervisor memory-write access. */
        if (!filter_revoke_entry(t,r)) return 1;
        filter_install_begin(t,r); return 1;
    }
    if (t->phase==PROCESS_INIT_EXIT && nr==SYS_prctl && r->pc==RAW_GATE
            && r->regs[0]==PR_SET_CHILD_SUBREAPER && r->regs[1]==1
            && !r->regs[2] && !r->regs[3] && !r->regs[4] && !r->regs[5]) {
        /* This is the namespace owner's pending birth operation, not a guest
         * request against its restricted root. Kernel filters still apply. */
        resume(t->pid,0); return 1;
    }
    if (t->phase==IDLE && !t->pid_filter
            && ((nr==SYS_clone || nr==SYS_unshare) && (r->regs[0]&CLONE_NEWPID))) {
        if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
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
        if (native_payload) { native_resume(t); return 1; }
        int devices=nr==SYS_socket && r->regs[0]==AF_NETLINK && r->regs[2]==NETLINK_KOBJECT_UEVENT
            && md_fs_worker_device_events(filesystem);
        if (control || devices || (nr==SYS_socket && md_network_namespace_managed(t->resources.network))) {
            execution_begin(t,r,"network-call-mask");
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
        execution_begin(t,r,"descriptor-ioctl-mask");
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
    if (nr == SYS_setns) {
        if (entry_context(t,MD_CONTEXT_NAMESPACE)) return 1;
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"namespace-entry-mask") && shield(t) && skip(t->pid))
            dispatch_with_stack(t);
        return 1;
    }
    if (nr==SYS_clone3 && !t->clone_prepared) {
        if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
        CHECK(t->phase==IDLE);
        t->original=*r;
        if (save_signal_mask(t,&t->mask,"clone-arguments-mask") && shield(t) && skip(t->pid))
            dispatch_with_stack(t);
        return 1;
    }
    if (md_clone_call(nr)) {
        if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
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
        else if (!entry_context(t,MD_CONTEXT_NAMESPACE)) domain_path_call(t,r);
        return 1;
    }
    if (nr==SYS_sethostname || nr==SYS_setdomainname) {
        int error=uts_access(t,nr);
        if (error) {
            return_value(t,r,error); return 1;
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
            native_resume(t); return 1;
        }
        return_value(t, r, -EPERM); return 1;
    }
    unsigned long args[6];
    for (unsigned i = 0; i < 6; i++) args[i] = r->regs[i];
    if (md_domain_native_call(nr, args)) {
        if (cookie == MD_INTERCEPT_OBSERVE || cookie == MD_INTERCEPT_WATCH
                || cookie==MD_INTERCEPT_FD || cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_MEMORY) return 0;
        native_resume(t); return 1;
    }
    int error = cookie == MD_INTERCEPT_DISPATCH ? md_domain_path_error(t->resources.fs) : -EPERM;
    if (nr == SYS_chdir) {
        if (!entry_context(t,MD_CONTEXT_NAMESPACE)) domain_path_call(t,r);
        return 1;
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
static int debug_host_policy_visit(void *context,const siginfo_t *info) {
    const struct seccomp_data *call=context;
    return info->si_signo==SIGSYS && info->si_code==SYS_SECCOMP
        && info->si_arch==call->arch && info->si_syscall==call->nr
        && (uintptr_t)info->si_call_addr==call->instruction_pointer;
}
static int debug_host_policy_pending(struct thread *t,const struct user_pt_regs *regs) {
    if (!t->host_signals || !t->debug_call_active || !t->entered) return 0;
    struct seccomp_data call={.nr=regs->regs[8],.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=regs->pc};
    memcpy(call.args,regs->regs,sizeof(call.args));
    int pending=md_task_pending_scan(native_trace,t->pid,debug_host_policy_visit,&call);
    if (pending<=0) return pending;
    uint32_t decision;
    CHECK(!md_seccomp_policy_eval(admission_policy(t),&call,&decision));
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_ALLOW || action==SECCOMP_RET_LOG)
        action=host_policy_interception(&call)&SECCOMP_RET_ACTION_FULL;
    /* Guest TRAP and unsupported host calls retain native exit-then-signal
     * ordering. Only an owned adapter defers the debugger's result. */
    return action==SECCOMP_RET_ERRNO || action==SECCOMP_RET_TRACE || action==SECCOMP_RET_USER_NOTIF;
}
static int debugger_at_boundary(const struct thread *t) {
    return t->phase==IDLE && !t->metadata_restart && !t->fd_running && !t->fd_order.table && !t->fs_order.fs
        && !t->fd_policy_order.queue && !t->signal_delivery.active;
}
static int debugger_boundary(struct thread *t) {
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    if (machine && md_debugger_traced(debugger,t->pid)) {
        /* An interrupt can land inside a resident entry, outside the syscall
         * adapter. Retire that frame before exposing registers to the debugger;
         * a running payload finishes through its existing return checkpoint. */
        if (!svc_revoke_entry(t)) return 1;
        int deferred=md_svc_machine_flush(machine,&t->machine,t->pid);
        if (deferred==-ESRCH) { t->resume_lost=1; return 1; }
        CHECK(deferred>=0);
        if (deferred) { t->machine.draining=1; return 0; }
    }
    return md_debugger_boundary(debugger,t->pid);
}
static int freezer_parked(const struct thread *t) {
    return task_parked(t) || t->phase==DEBUG_PARK_EXIT;
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
static void publish_execution(struct thread *t,int resuming) {
    if (!broker) return;
    int eligible=t->ready && t->phase==IDLE && t->endpoint[0];
    int paths=!md_domain_restricted(t->resources.fs);
    int raw=eligible && paths && !md_seccomp_policy_may_trace(t->policy) && !md_seccomp_policy_may_notify(t->policy);
    int direct=eligible && filter_entry_eligible(t) && !t->debug_options
        && !t->debug_call_active && !t->debug_step_call && !md_debugger_traced(debugger,t->pid);
    int opens=direct && paths && !t->application_filter;
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int discovery=direct && registry && !registry->disabled && !md_memory_exposed(t->memory);
    cooperative_memory_publish(t,discovery && !t->cooperative_blocked);
    struct md_broker_code_ranges ranges={.size=MD_SVC_CODE_BYTES};
    _Static_assert(MD_SVC_CODE_POOLS<=8,"broker code range capacity");
    if (registry) {
        ranges.count=registry->pool_count;
        for (unsigned i=0;i<ranges.count;++i) ranges.starts[i]=registry->pools[i].address;
    }
    CHECK(!md_broker_task(broker,t->pid,eligible ? (raw ? MD_BROKER_READ : 0)|(opens ? MD_BROKER_OPEN : 0)
        |(discovery ? MD_BROKER_DISCOVERY : 0)
        |(direct && !t->cooperative_blocked ? MD_BROKER_COOPERATIVE : 0) : 0,
        RAW_GATE,abi.watch_return,&ranges,resuming));
}
static void resume(pid_t pid, int sig) {
    struct thread *t = thread(pid);
    if (t->resume_lost) return;
    if (retained_task_held(t)) return;
    if (!t->host_signal_blocked && t->phase==IDLE && t->entered && t->signal_pending) {
        struct md_signal_pending_value pending;
        if (md_signal_pending_peek(t->signal_pending,&pending)) {
            CHECK(!md_signal_delivery_generated(&t->signal_delivery,&pending.info));
            CHECK(md_signal_pending_take(t->signal_pending,&pending));
        }
    }
    if (t->retained_signal) {
        CHECK(!sig || sig==t->retained_signal);
        sig=t->retained_signal;
    }
    if (entry_fence_owner==t && t->phase==IDLE) {
        if (t->entry_fence_replay) t->entry_fence_replay=0;
        else entry_fence_owner=NULL;
    }
    if (!memory_available(pid)) {
        CHECK(!t->memory_resume || !sig || sig==t->memory_signal);
        t->memory_resume=1; if (sig) t->memory_signal=sig;
        struct md_task_quiesce *barrier=memory_execution ? memory_barrier
            : mapping_execution ? mapping_barrier : task_action_barrier;
        if (retained_creation || retained_retirement) barrier=retained_barrier;
        if (policy_installer && pid!=policy_installer->pid) barrier=policy_barrier;
        /* A lent actor has a separate resume owner until its registers return. */
        if (!task_action_execution || task_action_execution->actor!=t || !task_action_execution->borrowed)
            CHECK(!md_task_quiesce_add(barrier,pid,1,0));
        return;
    }
    if (t->phase==IDLE && t->admission_policy_valid && !t->admission_policy_pending) {
        md_seccomp_policy_release(t->admission_policy); t->admission_policy=NULL;
        t->admission_policy_valid=0;
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
    /* A retained asynchronous signal or carrier can resume through this
     * gateway while a native-call owner is restoring its boundary. Preserve
     * that owner's SYSCALL/SYSEMU continuation, not the enclosing guest phase. */
    struct md_task_call *borrowed=task_call_continuation(t);
    if (borrowed) {
        task_call_resume(t,borrowed,sig);
        if (!t->kernel_stopped) t->retained_signal=0;
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
        if (!svc_revoke_entry(t)) return;
    if (machine && machine->revoke_pending) {
        int error=md_svc_registry_revoke(machine,pid);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
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
    /* A stopped helper can shield a shared execution, but the resident C
     * frame already defers handlers. Restore its borrowed mask before returning
     * to that frame; only actual kernel waits retain their own mask transition. */
    if (t->entry_execution && phase==DISPATCHING && t->signal_shielded) {
        CHECK(t->entry_kernel_context);
        if (!restore_mask(t)) return;
    }
    publish_execution(t,1);
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
    signal_query_publish(t);
    t->kernel_resume=phase==CALL_RESTART_ENTRY ? PTRACE_SYSEMU
        : t->signal_delivery.active && phase!=SIGNAL_NATIVE_EXIT ? PTRACE_CONT : (t->metadata_restart && phase==IDLE
            && (t->metadata_restart->stage==METADATA_ENTRY || t->metadata_restart->stage==METADATA_WAIT))
            || t->signal_return_pending || t->machine.syscall_exit || (phase==IDLE && t->machine.retry_svc)
            || t->direct==DIRECT_ADMIT || t->fd_running || phase == OBSERVING || phase==PROCESS_OBSERVING
            || phase==PROCESS_INIT_ENTRY || phase==PROCESS_INIT_EXIT || phase == EXPORT_ENTRY || phase == EXPORT_NOTIFY
            || phase == EXPORT_RETURN || phase == EXECUTING || phase == WATCH_CANCEL
            || phase == WATCH_ENTRY || phase == WATCH_READ || phase==SOCKET_NATIVE || phase==DEBUG_WAIT_ENTRY || phase==DEBUG_WAIT_EXIT
            || phase==DEBUG_PARK_ENTRY || phase==DEBUG_PARK_EXIT
            || phase==IPC_WAIT_ENTRY || phase==IPC_WAITING || phase==SOCKET_WAIT_ENTRY || phase==SOCKET_WAITING
            || phase==CALL_RESTART_ENTRY || phase==CALL_RESTART_EXIT || phase==EPOLL_PROBE || phase==EPOLL_CALL || phase==EPOLL_WAIT
            || phase==FILE_WAIT_ENTRY || phase==FILE_WAITING
            || phase==CONTROL_WAIT_ENTRY || phase==CONTROL_WAITING || phase==CLOSE_WAIT_ENTRY || phase==CLOSE_WAITING
            || phase==WAIT_CANCEL_EXIT || phase==CLONE_ENTRY || phase==CLONE_ADMISSION
            || phase==SIGNAL_NATIVE_EXIT
            || phase==RESULT_REPLAY_ENTRY || phase==RESULT_REPLAY_EXIT
            || (debugger_at_boundary(t) && t->entered && t->process_image
                && md_debugger_syscall_mode(debugger,pid)) ? PTRACE_SYSCALL
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
    uint64_t mask=md_signal_async_mask();
    /* Only the app-UID pre-entry gate requires an unblocked SIGSYS. Native
     * pending signals must remain queued until the guest's atomic wait. */
    if (!t->host_signals) mask |= MD_SIGNAL_SYS_BIT;
    if (!signal_mask(t,mask,"shield-signals")) return 0;
    t->signal_shielded=1; return 1;
}
static int save_signal_mask(struct thread *t,uint64_t *mask,const char *reason) {
    if (!tracee_request(t,PTRACE_GETSIGMASK,(void *)sizeof(*mask),mask,reason)) return 0;
    TRACE("PROBE signal-mask-save pid=%d reason=%s mask=%#llx phase=%d task=%d\n",
        t->pid,reason,(unsigned long long)*mask,t->phase,!!t->entry_execution);
    return 1;
}
static int signal_mask(struct thread *t,uint64_t mask,const char *reason) {
    TRACE("PROBE signal-mask-set pid=%d reason=%s mask=%#llx phase=%d task=%d\n",
        t->pid,reason,(unsigned long long)mask,t->phase,!!t->entry_execution);
    if (!tracee_request(t,PTRACE_SETSIGMASK,(void *)sizeof(mask),&mask,reason)) return 0;
    t->signal_handler_mask_known=0;
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
static struct md_guest_execution execution_prepare(struct thread *t) {
    struct md_guest_execution call={.number=(long)t->original.regs[8],.policy=dispatch_policy(t),
        .control=t->proc_file ? (int)md_proc_file_kind(t->proc_file) : -1};
    for (unsigned i=0;i<6;++i) call.args[i]=t->original.regs[i];
    call.prepared=dispatch_input(t,call.policy);
    return call;
}
static struct user_pt_regs dispatch_registers(const struct thread *t,const struct md_guest_execution *call) {
    struct user_pt_regs regs=t->original;
    regs.pc=DISPATCH; regs.sp=t->stack; regs.regs[30]=abi.done_entry;
    regs.regs[0]=call->number;
    for (unsigned i=0;i<6;++i) regs.regs[i+1]=call->args[i];
    regs.regs[9]=md_dispatch_control(call->policy,call->control);
    regs.regs[10]=call->prepared;
    return regs;
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
    struct md_guest_execution call=execution_prepare(t);
    struct user_pt_regs regs=dispatch_registers(t,&call);
    if (receive_prepared(t,&regs,call.policy)) return;
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
    CHECK(!t->born && (t->initial_stop || t->listening));
    /* The first stop can already be a group stop, consumed with LISTEN before
     * the parent's birth event. Transfer ownership without resuming that child;
     * the helper starts at its subsequent CONT acknowledgement. */
    int ready=t->terminal_stop ? MD_TASK_BIRTH_EXIT
        : t->initial_stop && !t->listening ? MD_TASK_BIRTH_READY : MD_TASK_BIRTH_PENDING;
    release_thread(t);
    return ready;
}
static struct retained_memory *retained_actor(pid_t pid) {
    for (struct retained_memory *m=retained_actors[(unsigned)pid%RETAINED_ACTOR_BUCKETS];m;m=m->indexed)
        if (m->retained.actor==pid) return m;
    return NULL;
}
static void retained_schedule(struct retained_memory *m) {
    if (m->queued) return;
    m->queued=1; m->work_next=retained_work; retained_work=m;
}
static void retained_unschedule(struct retained_memory *m) {
    if (!m->queued) return;
    struct retained_memory **link=&retained_work;
    while (*link!=m) link=&(*link)->work_next;
    *link=m->work_next; m->work_next=NULL; m->queued=0;
}
struct private_child_selection {
    struct thread *caller;
    pid_t group,*candidates;
    size_t count,capacity;
    unsigned options;
    int error;
};
static int private_child_matches(struct private_child_selection *s,pid_t pid) {
    long parent=md_task_parent(pid);
    if (parent==-ENOENT || parent==-ESRCH) return 0;
    if (parent<0) return (int)parent;
    if (parent!=s->caller->tgid) return 0;
    if (s->group || !(s->options&__WALL)) {
        pid_t group; int signal;
        int error=md_task_child_class(pid,&group,&signal);
        if (error==-ENOENT || error==-ESRCH) return 0;
        if (error) return error;
        if (s->group && group!=s->group) return 0;
        if (!(s->options&__WALL) && ((signal==SIGCHLD)==!!(s->options&__WCLONE))) return 0;
    }
    return 1;
}
static void private_child_candidate(struct private_child_selection *s,pid_t pid) {
    pid_t number=visible_pid(s->caller,pid);
    if (!number || s->error) return;
    int matches=private_child_matches(s,pid);
    if (matches<0) s->error=matches;
    else if (matches) {
        if (s->count==s->capacity) {
            size_t capacity=s->capacity ? s->capacity*2 : 16;
            if (capacity>SIZE_MAX/sizeof(*s->candidates)) { s->error=-ENOMEM; return; }
            pid_t *next=realloc(s->candidates,capacity*sizeof(*next));
            if (!next) { s->error=-ENOMEM; return; }
            s->candidates=next; s->capacity=capacity;
        }
        s->candidates[s->count++]=number;
    }
}
static void private_child_retired(void *context,pid_t pid,struct md_task_retired_view view) {
    (void)view;
    private_child_candidate(context,pid);
}
static int private_wait_children(pid_t pid,const struct user_pt_regs *regs,pid_t **candidates,size_t *count) {
    *candidates=NULL; *count=0;
    if (!retained_memories) return 0;
    struct thread *t=find_thread(pid);
    CHECK(t);
    int id=regs->regs[8]==SYS_waitid;
    pid_t selected=(pid_t)regs->regs[id ? 1 : 0];
    if (id && regs->regs[0]==P_PIDFD) return 0;
    if ((id && regs->regs[0]==P_PID) || (!id && selected>0)) {
        return retained_actor(native_pid(t,selected))!=NULL;
    }
    struct private_child_selection selection={.caller=t,
        .options=(unsigned)regs->regs[id ? 3 : 2]};
    int grouped=id ? regs->regs[0]==P_PGID : selected!=-1;
    if (grouped) {
        if (selected==INT32_MIN) return -ECHILD;
        selection.group=selected ? native_pid(t,id ? selected : -selected) : getpgid(pid);
        if (selection.group<0) return -errno;
        if (!selection.group) return -ECHILD;
    }
    int hidden=0;
    for (struct retained_memory *m=retained_memories;m;m=m->next) if (m->retained.actor) {
        int matches=private_child_matches(&selection,m->retained.actor);
        if (matches<0) return matches;
        if (matches) { hidden=1; break; }
    }
    if (!hidden) return 0;
    for (struct thread *p=threads;p;p=p->next)
        if (p->pid && p->pid==p->tgid) private_child_candidate(&selection,p->pid);
    md_task_registry_retired_processes(tasks,private_child_retired,&selection);
    if (selection.error) { free(selection.candidates); return selection.error; }
    *candidates=selection.candidates; *count=selection.count;
    return 1;
}
static int robust_managed(const struct thread *t) {
    return md_app_uid_active || md_pid_namespace_depth(md_pid_identity_namespace(t->resources.pid));
}
static struct retained_memory *retained_memory_get(struct thread *t) {
    if (t->memory_actor) return t->memory_actor;
    struct retained_memory *m=calloc(1,sizeof(*m));
    if (!m) return NULL;
    m->memory=t->memory; m->next=retained_memories; retained_memories=m;
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->born && p->memory==t->memory) {
        CHECK(!p->memory_actor); p->memory_actor=m; ++m->members;
    }
    CHECK(t->memory_actor==m);
    retained_schedule(m);
    return m;
}
static void retained_detach(struct thread *t) {
    struct retained_memory *m=t->memory_actor;
    t->memory_actor=NULL; t->robust_head=0; t->robust_tid=0; t->robust_registering=0;
    if (!m) return;
    CHECK(m->members && m->terminal!=t && m->creator!=t);
    if (!--m->members) { m->memory=NULL; m->retiring=1; retained_schedule(m); }
}
static void robust_register_complete(struct thread *t,long error) {
    struct user_pt_regs regs=t->robust_registers;
    t->robust_registering=0;
    if (!error) {
        t->robust_head=regs.regs[0];
        t->robust_tid=(uint32_t)visible_pid(t,t->pid);
    }
    return_task_value(t,&regs,error,t->robust_boundary);
}
static int robust_call(struct thread *t,struct user_pt_regs *regs,enum md_task_call_boundary boundary) {
    long nr=regs->regs[8];
    if ((nr!=SYS_set_robust_list && nr!=SYS_get_robust_list) || !robust_managed(t)) return 0;
    if (entry_context(t,MD_CONTEXT_PROCESS)) return 1;
    if (nr==SYS_get_robust_list) {
        pid_t target=(pid_t)regs->regs[0];
        struct thread *subject=target ? find_thread(native_pid(t,target)) : t;
        long error=0;
        if (!subject || !subject->born || subject->terminal_stop) error=-ESRCH;
        else if (subject!=t && !inspect_allowed(t->pid,subject->pid,0)) error=-EPERM;
        if (!error) {
            uintptr_t head=subject->robust_head;
            size_t size=sizeof(struct md_robust_head);
            /* Native ordering: publish the size before the head, and preserve
             * a partial result if the second userspace pointer faults. */
            ssize_t copied=md_task_copy_direct(t->pid,regs->regs[2],&size,sizeof(size),1);
            if (copied==sizeof(size)) copied=md_task_copy_direct(t->pid,regs->regs[1],&head,sizeof(head),1);
            if (copied!=sizeof(head)) error=copied<0 ? copied : -EFAULT;
        }
        return_task_value(t,regs,error,boundary); return 1;
    }
    if (regs->regs[1]!=sizeof(struct md_robust_head)) {
        return_task_value(t,regs,-EINVAL,boundary); return 1;
    }
    t->robust_registers=*regs; t->robust_boundary=boundary;
    if (!regs->regs[0]) { robust_register_complete(t,0); return 1; }
    t->terminal_observation=1; t->trace_options_dirty=1;
    int error=debugger_options(t->pid,t->debug_options);
    if (error) { robust_register_complete(t,error); return 1; }
    struct retained_memory *m=retained_memory_get(t);
    if (!m) { robust_register_complete(t,-ENOMEM); return 1; }
    if (m->ready) { robust_register_complete(t,0); return 1; }
    t->robust_registering=1;
    retained_schedule(m);
    /* EVENT_WAIT: one mm's first registration creates its private executor;
     * other registrations wait for the same birth, never another actor. */
    CHECK(!md_event_signal(fd_ready));
    return 1;
}
static void retained_creation_finish(struct retained_memory *m,long error) {
    struct thread *t=m->creator;
    if (m->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    m->checkpoint_suspended=0; m->creator=NULL; retained_creation=NULL;
    if (m->order.queue && md_resource_order_cancel(&m->order)==1)
        CHECK(!md_resource_order_complete(&m->order));
    if (retained_barrier) CHECK(!md_task_quiesce_release(retained_barrier));
    m->error=error; m->ready=!error;
    if (t && !m->retained.parent_terminal) {
        t->kernel_stopped=1;
        t->robust_boundary=m->retained.parent.boundary;
    }
    if (error) {
        /* A cancelled birth can still own a native child. Keep its exact PID
         * until the retirement owner has removed both wait and signal state. */
        m->retiring=1;
        for (struct thread *p=threads;p;p=p->next) if (p->memory_actor==m) {
            p->memory_actor=NULL; CHECK(m->members); --m->members;
        }
        m->memory=NULL;
    }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->robust_registering
            && (p->memory_actor==m || (error && p->memory==t->memory))) {
        if (p==t && m->retained.parent_terminal) p->robust_registering=0;
        else robust_register_complete(p,error);
    }
    task_action_unpark();
}
static void retained_creation_begin(struct retained_memory *m,struct thread *t,
        const struct user_pt_regs *regs,enum md_task_call_boundary call) {
    CHECK(!retained_creation && !m->ready && !m->creator && !m->retiring);
    m->creator=t; retained_creation=m;
    t->terminal_observation=1; t->trace_options_dirty=1;
    int error=debugger_options(t->pid,t->debug_options);
    if (error) { retained_creation_finish(m,error); return; }
    int boundary=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (boundary<0) { retained_creation_finish(m,boundary); return; }
    m->checkpoint_suspended=1;
    if (boundary && call==MD_TASK_CALL_ENTRY) call=MD_TASK_CALL_EMULATED_ENTRY;
    error=md_task_retained_begin(&m->retained,native_trace,t->pid,call,regs,&abi);
    if (error && !m->retained.parent.stage) retained_creation_finish(m,error);
    else if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
    else t->kernel_stopped=0;
}
static void retained_track_actor(struct retained_memory *m) {
    if (!m->retained.actor || m->wait.pid) return;
    unsigned bucket=(unsigned)m->retained.actor%RETAINED_ACTOR_BUCKETS;
    m->indexed=retained_actors[bucket]; retained_actors[bucket]=m;
    md_task_wait_track(&task_wait,&m->wait,m->retained.actor);
    int initial=helper_newborn(m->retained.actor);
    if (initial==MD_TASK_BIRTH_READY) {
        int state=md_task_retained_event(&m->retained,m->retained.actor,
            (PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f);
        CHECK(state>=0);
    } else if (initial==MD_TASK_BIRTH_EXIT) {
        m->error=-ESRCH;
    } else CHECK(initial==MD_TASK_BIRTH_PENDING);
}
static void robust_cleanup_complete(struct retained_memory *m,int state) {
    if (state<0) {
        fprintf(stderr,"Shroot: robust cleanup actor=%d error=%d\n",m->retained.actor,state);
        cancel_tree(125); return;
    }
    if (state!=1) { m->cleaning=state==2 ? 2 : 1; return; }
    struct thread *t=m->terminal;
    t->robust_subject->robust_head=0;
    t->robust_subject=NULL; t->robust_terminal=2; ++robust_replays;
    m->terminal=NULL; m->cleaning=0;
    m->cleanup=(struct md_task_robust){0};
}
static void retained_progress(void) {
    if (!retained_work) return;
    retained_retirement_progress();
    for (struct retained_memory *m=retained_work,*next;m;m=next) {
        next=m->work_next;
        if (m->ready && !m->cleaning && !m->pending && !m->retiring) {
            retained_unschedule(m); continue;
        }
        if (m->cleaning==2) robust_cleanup_complete(m,md_task_robust_progress(&m->cleanup));
        if (m->pending && m->ready && !m->cleaning && !m->retiring && !quiescence_busy(NULL)) {
            for (struct thread *t=threads;t;t=t->next) if (t->pid && t->memory_actor==m && t->robust_terminal==1) {
                --m->pending;
                m->terminal=t;
                robust_cleanup_complete(m,md_task_robust_begin(&m->cleanup,native_trace,m->retained.actor,
                    &abi,t->robust_subject->robust_head,t->robust_subject->robust_tid));
                break;
            }
        }
        if (m->ready || m->retiring || m->creator || retained_barrier || quiescence_busy(NULL)) continue;
        struct thread *t;
        for (t=threads;t;t=t->next) if (t->pid && t->memory_actor==m && t->robust_registering) break;
        if (!t) continue;
        if (!m->order.queue) CHECK(md_memory_order_begin(t->memory,&m->order,fd_wakeup)>=0);
        if (md_resource_order_state(&m->order)!=MD_RESOURCE_ORDER_READY) continue;
        CHECK(!md_resource_order_start(&m->order));
        retained_creation_begin(m,t,&t->robust_registers,t->robust_boundary);
    }
}
static void retained_memory_release(struct retained_memory *m) {
    CHECK(!m->members && !m->creator && !m->cleaning);
    struct retained_memory **link=&retained_memories;
    while (*link && *link!=m) link=&(*link)->next;
    CHECK(*link==m); *link=m->next;
    retained_unschedule(m);
    if (m->wait.pid) {
        link=&retained_actors[(unsigned)m->retained.actor%RETAINED_ACTOR_BUCKETS];
        while (*link!=m) link=&(*link)->indexed;
        *link=m->indexed;
    }
    md_task_wait_untrack(&task_wait,&m->wait);
    free(m);
}
static void retained_retirement_finish(int completed) {
    struct retained_retirement *r=retained_retirement;
    struct thread *t=r->actor;
    if (r->checkpoint_suspended) md_task_checkpoint_restore(&checkpoints,t->pid);
    if (r->borrowed) CHECK(!md_task_quiesce_return(retained_barrier,t->pid));
    if (r->broker_borrowed) md_broker_native_return(broker,t->pid);
    CHECK(!md_buffer_release(&r->buffer));
    if (r->order.queue && md_resource_order_cancel(&r->order)==1)
        CHECK(!md_resource_order_complete(&r->order));
    CHECK(!md_task_quiesce_release(retained_barrier));
    if (r->synthetic) t->kernel_stopped=0;
    if (completed) retained_memory_release(r->memory);
    free(r); retained_retirement=NULL;
    task_action_unpark();
}
static int retained_retirement_member(const struct thread *t,pid_t parent) {
    return t->pid && t->born && (t->tgid==parent || md_task_parent(t->pid)==parent);
}
static int retained_retirement_group_ready(pid_t parent) {
    for (struct thread *t=threads;t;t=t->next) if (t->pid && t->born
            && (t->terminal_stop || retained_task_held(t) || md_task_helpers_group_busy(helpers,t->tgid))
            && retained_retirement_member(t,parent)) return 0;
    return 1;
}
static void retained_retirement_progress(void) {
    struct retained_retirement *r=retained_retirement;
    if (r) {
        if (!r->borrowed && !md_task_quiesce_ready(retained_barrier)) return;
        struct retained_memory *m=r->memory;
        struct thread *t=r->actor;
        if (!r->borrowed) {
            struct md_task_quiesce_stop stop;
            CHECK(!md_task_quiesce_borrow(retained_barrier,t->pid,&stop));
            r->borrowed=1; r->synthetic=stop.kind==MD_TASK_QUIESCE_SYNTHETIC;
            if (stop.kind==MD_TASK_QUIESCE_EVENT) {
                unsigned event=(unsigned)stop.status>>16;
                if (event!=PTRACE_EVENT_SECCOMP && (event || WSTOPSIG(stop.status)!=(SIGTRAP|0x80))) {
                    /* No child has been killed yet. Replay a real signal or
                     * lifecycle event before borrowing the next boundary. */
                    retained_retirement_finish(0); return;
                }
            }
        }
        if (!r->stage) {
            CHECK(md_resource_order_state(&r->order)==MD_RESOURCE_ORDER_RUNNING);
            CHECK(!md_buffers_take_reserved(md_memory_buffers(t->memory),&r->buffer));
            r->peer=md_memory_buffer_address(t->memory,&r->buffer);
            m->killed=1;
            CHECK(!kill(m->retained.actor,SIGKILL) || errno==ESRCH);
            if (m->exit_held) CHECK(!native_trace(PTRACE_CONT,m->retained.actor,NULL,NULL) || errno==ESRCH);
            r->stage=1;
        }
        if (!m->dead || r->stage!=1) return;
        struct user_pt_regs saved;
        struct ptrace_syscall_info info={0};
        CHECK(registers(t->pid,&saved));
        CHECK(native_trace(PTRACE_GET_SYSCALL_INFO,t->pid,(void *)sizeof(info),&info)>=0);
        int checkpoint=task_checkpoint_borrow(t,&r->checkpoint_suspended);
        CHECK(checkpoint>=0);
        enum md_task_call_boundary boundary=(enum md_task_call_boundary)info.op;
        if (checkpoint && boundary==MD_TASK_CALL_ENTRY) boundary=MD_TASK_CALL_EMULATED_ENTRY;
        /* Direct children are held with the parent group: a private notice
         * cannot coalesce a new real terminal notice during exact reaping. */
        CHECK(!md_task_child_reap_begin(&r->reap,native_trace,t->pid,t->tgid,m->retained.actor,
            boundary,&saved,abi.task_call,abi.task_return,&r->scratch,r->peer,NULL));
        r->stage=2; t->kernel_stopped=0;
        return;
    }
    if (quiescence_busy(NULL) || retained_barrier) return;
    for (struct retained_memory *m=retained_work,*next;m;m=next) {
        next=m->work_next;
        if (!m->retiring || m->members || m->cleaning || m->creator) continue;
        if (!m->retained.actor) { retained_memory_release(m); continue; }
        long parent=md_task_parent(m->retained.actor);
        if (parent==getpid() || (m->dead && parent==-ENOENT)) {
            if (m->dead) { retained_memory_release(m); continue; }
            if (!m->killed) {
                m->killed=1;
                CHECK(!kill(m->retained.actor,SIGKILL) || errno==ESRCH);
                if (m->exit_held) CHECK(!native_trace(PTRACE_CONT,m->retained.actor,NULL,NULL) || errno==ESRCH);
            }
            continue;
        }
        struct thread *actor=NULL;
        for (struct thread *t=threads;t;t=t->next)
            if (t->pid && t->tgid==parent && t->entered && task_action_borrowable(t,NULL)) { actor=t; break; }
        /* Existing helper/exit owners finish before the parent/children
         * barrier. A resumed exit cannot acknowledge INTERRUPT; in particular
         * a zombie leader is not waitable until its remaining threads exit.
         * Holding those threads here would prevent the event we await. */
        if (!actor || !retained_retirement_group_ready((pid_t)parent)) continue;
        int stopped=actor->kernel_stopped;
        int borrowed=md_broker_native_borrow(broker,actor->pid,&stopped);
        if (borrowed<0) continue;
        r=calloc(1,sizeof(*r)); CHECK(r);
        r->memory=m; r->actor=actor; r->broker_borrowed=borrowed;
        /* Never stop writers while waiting behind their memory transaction.
         * Retirement can retry at the next owner completion; no private child
         * has been killed or exposed to the parent's wait/signal state yet. */
        CHECK(md_memory_order_begin(actor->memory,&r->order,fd_wakeup)>=0);
        if (md_resource_order_state(&r->order)!=MD_RESOURCE_ORDER_READY) {
            CHECK(!md_resource_order_cancel(&r->order));
            if (borrowed) md_broker_native_return(broker,actor->pid);
            free(r); continue;
        }
        CHECK(!md_resource_order_start(&r->order));
        retained_retirement=r;
        retained_barrier=md_task_quiesce_new(quiesce_trace); CHECK(retained_barrier);
        CHECK(!quiesce_task_add(retained_barrier,actor,stopped));
        for (struct thread *t=threads;t;t=t->next) if (t!=actor && retained_retirement_member(t,(pid_t)parent))
            CHECK(!quiesce_task_add(retained_barrier,t,0));
        CHECK(!md_task_quiesce_stop(retained_barrier));
        return;
    }
}
static int retained_barrier_event(pid_t pid,int status,const struct rusage *usage) {
    struct retained_retirement *r=retained_retirement;
    if ((!retained_creation && !r) || !md_task_quiesce_contains(retained_barrier,pid)) return 0;
    unsigned event=(unsigned)status>>16;
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) {
        CHECK(!md_task_quiesce_terminal(retained_barrier,pid,status,usage));
        if (r && (!r->stage || pid==r->actor->pid)) {
            if (pid==r->actor->pid) { r->borrowed=0; r->synthetic=0; }
            /* Before killing the private child, yield every terminal event.
             * exec may be waiting in de_thread for a sibling held here; its
             * INTERRUPT cannot complete until that sibling's exit is replayed.
             * A dying actor's child can be reparented: retain its exact PID for
             * the next real parent, without restoring dead registers. */
            retained_retirement_finish(0);
        }
        return 1;
    }
    if (r && pid==r->actor->pid && r->stage==2) {
        if (task_call_signal(r->actor,&r->reap.call,status)) return 1;
        int state=md_task_child_reap_event(&r->reap,status);
        CHECK(state>=0);
        if (state==1) { r->actor->kernel_stopped=1; retained_retirement_finish(1); }
    } else CHECK(!quiesce_event(retained_barrier,pid,status,usage));
    return 1;
}
static int retained_runnable(void) {
    for (struct retained_memory *m=retained_work;m;m=m->work_next) if (m->cleaning==2) return 1;
    return 0;
}
static pid_t retained_take(int *status,struct rusage *usage) {
    if (retained_barrier && !retained_creation && !retained_retirement) {
        pid_t pid=md_task_quiesce_take(retained_barrier,status,usage);
        if (pid) return pid;
        md_task_quiesce_destroy(retained_barrier); retained_barrier=NULL;
        CHECK(!md_event_signal(fd_ready));
    }
    if (robust_replays) for (struct thread *t=threads;t;t=t->next) if (t->pid && t->robust_terminal==2) {
        --robust_replays;
        *status=t->robust_status; if (usage) *usage=t->robust_usage;
        if (filesystem) memory_completions_pending=1;
        t->robust_terminal=0; t->robust_replay=1; return t->pid;
    }
    return 0;
}
static int retained_event(pid_t pid,int status,const struct rusage *usage) {
    unsigned event=(unsigned)status>>16;
    struct retained_memory *m=retained_actor(pid);
    struct retained_memory *birth=retained_creation;
    if (birth && (pid==birth->retained.parent.actor || m==birth)) {
        struct thread *t=birth->creator;
        if (!m && task_call_signal(t,&birth->retained.parent,status)) return 1;
        int terminal=!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC;
        int state=md_task_retained_event(&birth->retained,pid,status);
        retained_track_actor(birth);
        if (birth->retained.parent_terminal || (terminal && m==birth)) {
            if (m==birth) birth->error=-ESRCH;
            retained_creation_finish(birth,-ESRCH);
            if (!m) return 0;
        } else if (state==-EAGAIN) {
            if (!m && task_call_signal(t,&birth->retained.parent,status)) return 1;
            if (!m) return 0;
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL)); return 1;
        } else if (state<0) {
            if (state==-ESRCH) { if (!m) t->resume_lost=1; return 1; }
            retained_creation_finish(birth,state); return 1;
        } else {
            if (state==1) retained_creation_finish(birth,birth->error);
            return 1;
        }
    }
    if (m) {
        if (!WIFSTOPPED(status)) { m->dead=1; return 1; }
        if (event==PTRACE_EVENT_EXIT) {
            /* The real parent's wait/signalfd must be held before ptrace
             * releases a private child to its final exit and SIGCHLD. */
            m->exit_held=1;
            if (!m->retiring) {
                fprintf(stderr,"Shroot: lost retained memory executor %d\n",pid);
                exit(125);
            }
            if (m->killed) CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL) || errno==ESRCH);
            return 1;
        }
        if (m->cleaning) {
            int state=md_task_robust_event(&m->cleanup,status);
            if (state!=-EAGAIN) { robust_cleanup_complete(m,state); return 1; }
        }
        /* No guest signal handler runs in the stack/TLS-free private actor. */
        if (m->retiring && m->killed) { kill(pid,SIGKILL); native_trace(PTRACE_CONT,pid,NULL,NULL); }
        else if (m->cleaning) CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL));
        return 1;
    }
    struct thread *t=find_thread(pid);
    if (!t) return 0;
    if (t->robust_replay) { t->robust_replay=0; return 0; }
    if (!WIFSTOPPED(status) || (event!=PTRACE_EVENT_EXIT && event!=PTRACE_EVENT_EXEC)) return 0;
    struct thread *subject=t;
    if (event==PTRACE_EVENT_EXEC) {
        unsigned long old;
        if (native_trace(PTRACE_GETEVENTMSG,pid,NULL,&old)<0) { CHECK(errno==ESRCH); return 1; }
        if (old && old!=(unsigned)pid) subject=find_thread((pid_t)old);
    }
    if (!subject || !subject->robust_head) return 0;
    CHECK(subject->memory_actor && subject->memory_actor->ready && t->memory_actor==subject->memory_actor);
    CHECK(!t->robust_terminal);
    ++subject->memory_actor->pending; retained_schedule(subject->memory_actor);
    t->kernel_stopped=1; t->robust_subject=subject;
    t->robust_terminal=1; t->robust_status=status;
    t->robust_usage=usage ? *usage : (struct rusage){0};
    return 1;
}
static void retained_cancel(void) {
    for (struct retained_memory *m=retained_memories;m;m=m->next)
        if (m->retained.actor && !m->dead) kill(m->retained.actor,SIGKILL);
}
static void retained_destroy(void) {
    if (retained_retirement) {
        struct retained_retirement *r=retained_retirement;
        if (r->borrowed) md_task_quiesce_return(retained_barrier,r->actor->pid);
        md_buffer_release(&r->buffer);
        if (r->order.queue && md_resource_order_cancel(&r->order)==1) md_resource_order_complete(&r->order);
        free(r); retained_retirement=NULL;
    }
    md_task_quiesce_destroy(retained_barrier); retained_barrier=NULL;
    while (retained_memories) {
        struct retained_memory *m=retained_memories; retained_memories=m->next;
        md_task_wait_untrack(&task_wait,&m->wait);
        if (m->order.queue && md_resource_order_cancel(&m->order)==1) md_resource_order_complete(&m->order);
        free(m);
    }
    retained_creation=NULL;
    retained_work=NULL; robust_replays=0;
    memset(retained_actors,0,sizeof(retained_actors));
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
                CHECK(!quiesce_task_add(mapping_barrier,p,p==t));
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
    if (entry_context(t,MD_CONTEXT_MEMORY)) return;
    CHECK(!t->memory_order.queue && !t->memory_action);
    t->memory_arguments_only=arguments_only;
    t->original=*regs; t->memory_action=action; t->phase=MEMORY_WAIT; t->memory_backing_pending=0;
    CHECK(md_memory_order_begin(t->memory,&t->memory_order,fd_wakeup)>=0);
    /* EVENT_WAIT: address-space publication precedes competing unmap/fork;
     * task exit cancels its queued turn. fd_ready drives progress, not polling. */
    memory_start(t);
}
static int entry_snapshot_change(const struct thread *t,long nr,const __u64 *args) {
    if (!md_memory_snapshots_valid(t->memory)) return 0;
    if (nr==SYS_shmat && (args[2]&SHM_REMAP))
        return md_memory_snapshot_intersects(t->memory,0,SIZE_MAX);
    if (nr!=SYS_mmap && nr!=SYS_munmap && nr!=SYS_mprotect && nr!=SYS_mremap) return 0;
    if ((nr!=SYS_mmap || (args[3]&MAP_FIXED))
            && md_memory_snapshot_intersects(t->memory,args[0],args[1])) return 1;
    return nr==SYS_mremap && (args[3]&MREMAP_FIXED)
        && md_memory_snapshot_intersects(t->memory,args[4],args[2]);
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
    if (entry_snapshot_change(t,nr,info->seccomp.args)) return 0;
    if (md_svc_registry_context(machine,observed->pc,observed->sp)) {
        /* The exact kernel alias has already restored the caller's context.
         * A private stack or any other resident PC still needs full recovery. */
        if (observed->sp>machine->frames && observed->sp-machine->frames<=MD_SVC_POOL_SIZE) return 0;
        unsigned long cookie=info->seccomp.ret_data;
        *native_entry=machine_native_entry(t,observed,&cookie);
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
            result=md_proc_file_control(file).ops && !md_proc_file_task(md_proc_file_kind(file)) ? md_proc_file_control_stat(file,&st)
                : md_proc_file_retained_stat(file,tasks,0,&st);
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
            || md_control_ioctl_available(control));
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
    } else if (!filesystem && nr!=SYS_read && nr!=SYS_readv
            && (nr!=SYS_ioctl || md_watch_ioctl_native(nr,regs.regs[1]))
            && nr!=SYS_splice && nr!=SYS_tee && nr!=SYS_sendfile) {
        /* Without a typed control, only FIONREAD can require watch adaptation.
         * Other ioctls already passed their domain owners; execute at the
         * original PC/mask, without a cancel/replay trip through WATCH_ENTRY. */
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
    if (entry_context(t,MD_CONTEXT_DESCRIPTOR)) return;
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
    int logical=t->host_signals && !t->host_signal_blocked
        && (md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
            || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED));
    if (logical && t->signal_wait_view.active
            && !signal_mask(t,t->signal_wait_view.temporary&~MD_SIGNAL_SYS_BIT,"wait-delivery-mask")) return;
    t->signal_return_pending=!logical;
    readiness_release(t); CHECK(!buffer_release(t));
    struct user_pt_regs regs=t->original; regs.regs[0]=(uint64_t)-EINTR;
    if (!set_registers(t->pid,&regs)) return;
    md_memory_stack_return(t->memory,t->stack); t->stack=0;
    t->phase=IDLE; resume(t->pid,0);
}
static void signal_wait_restore(struct thread *t) {
    if (!t->signal_wait_view.active) return;
    t->host_signal_blocked=!!(t->signal_wait_view.saved&MD_SIGNAL_SYS_BIT);
    t->signal_wait_view.active=0;
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
static void socket_wait_cancelled(struct thread *t) {
    int error=t->wait_error; t->wait_error=0; t->socket_waking=0;
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
static int readiness_native_event(struct thread *t,int status) {
    unsigned event=(unsigned)status>>16;
    int signal=WSTOPSIG(status);
    if (t->phase==WAIT_CANCEL_EXIT && t->wait_resume==EPOLL_CALL) {
        if (event || signal!=(SIGTRAP|0x80)) return 0;
        struct ptrace_syscall_info info={0};
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"epoll-cancel-exit")) return 1;
        struct readiness_owner *o=t->readiness;
        CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT && o && (o->evicting || o->finishing || o->signal_ready || o->resample));
        /* Only a drained exit can lend its continuation to FD retirement.
         * Rewriting an entry to the adapter's PC would re-enter seccomp with
         * the reply in x0 and mistake that value for a service command. */
        t->phase=DISPATCHING; t->wait_resume=IDLE;
        if (o->signal_ready) {
            if (signal_mask(t,t->signal_wait_view.temporary&~MD_SIGNAL_SYS_BIT,"wait-signal-mask"))
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
        struct md_control_actor actor={.pid=t->pid,.signals=t->signal_pending};
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
        uint64_t mask=t->mask;
        if (t->host_signals) {
            struct md_buffer_span span;
            int error=readiness_frame(t,&span);
            CHECK(!error);
            struct md_epoll_frame *frame=span.data;
            if (frame->masked) {
                mask=md_signal_view_mask(t->mask,t->host_signal_blocked);
                t->signal_wait_view=(struct md_signal_wait_view){.active=1,
                    .saved=mask,.temporary=frame->mask&md_signal_blockable_mask()};
                t->host_signal_blocked=!!(frame->mask&MD_SIGNAL_SYS_BIT);
                if (!t->host_signal_blocked && (md_signal_pending_has(t->signal_pending,MD_SIGNAL_PRIVATE)
                        || md_signal_pending_has(t->signal_pending,MD_SIGNAL_SHARED))) {
                    o->signal_ready=1; wait_cancel_entry(t,EPOLL_CALL); return 1;
                }
            }
        }
        if (signal_mask(t,mask,"readiness-original-mask")) { t->phase=EPOLL_WAIT; resume(t->pid,0); }
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
        long ready=o->wait.virtual_count ? o->wait.virtual_count : o->wait.task_count;
        if (!ready) {
            struct md_buffer_span span;
            ready=readiness_frame(t,&span);
            if (!ready) {
                struct md_epoll_frame *frame=span.data;
                frame->native_output=0;
                struct md_control_actor actor={.pid=t->pid,.signals=t->signal_pending};
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
static enum call_restart control_wait_restart(const struct thread *t) {
    if (t->proc_io.transferred || t->original.regs[8]==SYS_splice) return 0;
    const struct md_control_pending *pending=t->control_pending
        ? t->control_pending : t->proc_io.pending;
    if (pending) return pending->retry_preparation ? RESTART_PREPARATION : 0;
    return t->proc_io.executed ? 0 : RESTART_SIGNAL;
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
    enum call_restart retry=interrupted ? control_wait_restart(t) : 0;
    retry_clear(t,RETRY_CONTROL); retry_clear(t,RETRY_PROC_IO);
    if (t->control_pending) { t->control_pending->dispose(t->control_pending); t->control_pending=NULL; }
    CHECK(!md_proc_file_io_cancel(&t->proc_io));
    t->proc_waiting=0;
    control_deliver(t,retry ? retry : interrupted ? -EINTR : value<0 ? value : -EIO);
}
static int interrupted_operation(struct thread *t) {
    if (t->phase==MEMORY_WAIT || t->phase==PROC_LOOKUP || t->mapping) return 1;
    struct user_pt_regs regs;
    const struct user_pt_regs *context=NULL;
    if (notification_unexecuted(t) && !t->metadata_restart) {
        if (!registers(t->pid,&regs)) return 1;
        context=&regs;
        if (notification_entry_context(t,context,&t->metadata.request.data)) {
            /* The interrupt can reach a new lazy entry instead of the
             * cancelled syscall. Retire only unexecuted admission and let the
             * entry unwind; never combine its private SP with application PC. */
            notification_interrupted(t);
            resume(t->pid,0);
            return 1;
        }
    }
    /* EVENT_WAIT: INTERRUPT cancels the exact notification. Under SYSCALL the
     * acknowledgement is a syscall-exit stop; under CONT it is EVENT_STOP. */
    if (t->phase==EXPORT_CANCEL) { export_start(t,context); return 1; }
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
    if (t->metadata_restart) regs=t->metadata_restart->saved;
    else CHECK(context);
    regs.pc=q->data.instruction_pointer; regs.regs[8]=q->data.nr;
    for (unsigned i=0;i<6;i++) regs.regs[i]=q->data.args[i];
    if (q->data.nr==SYS_rt_sigprocmask) {
        uint64_t mask;
        if (t->metadata_restart) mask=t->metadata_restart->mask;
        else if (t->signal_delivery.active) mask=t->signal_delivery.mask;
        else if (!save_signal_mask(t,&mask,"signal-query-admission")) return 1;
        mask=md_signal_view_mask(mask,t->host_signal_blocked);
        notification_release(t); t->phase=IDLE;
        int error=signal_control_begin(t,&regs,0,NULL,mask,NULL);
        if (error) return_task_value(t,&regs,error,MD_TASK_CALL_EXIT);
        return 1;
    }
    if (t->notification && t->notification->promoting) {
        struct guest_notification *n=t->notification;
        struct ptrace_syscall_info observed={0};
        CHECK(native_trace(PTRACE_GET_SYSCALL_INFO,t->pid,(void *)sizeof(observed),&observed)>=0);
        CHECK(observed.op==PTRACE_SYSCALL_INFO_NONE || observed.op==PTRACE_SYSCALL_INFO_EXIT);
        notification_release(t); t->phase=GUEST_NOTIFICATION; t->original=regs;
        int error=md_task_checkpoint_suspend(&checkpoints,t->pid);
        CHECK(!error); n->checkpoint_suspended=1; n->promoting=0;
        struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_SECCOMP_LISTENER,1},
            .flags=MD_TASK_CALL_INTERRUPTIBLE};
        error=md_task_call_begin(&n->call,native_trace,t->pid,(enum md_task_call_boundary)observed.op,
            &regs,abi.task_call,abi.task_return,&args);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
        else t->kernel_stopped=0;
        return 1;
    }
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
static void cancel_children(int signal) {
    for (struct thread *t=threads;t;t=t->next)
        if (t->pid && t->born && t->pid==t->tgid)
            CHECK(!md_process_signal_child(t->pid,signal));
    CHECK(!md_process_signal_children(signal));
}
static void cancel_tree(int status) {
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
        fprintf(stderr,"Shroot: pending pid=%d tgid=%d phase=%d stopped=%d resume=%d listening=%d interrupt=%d syscall=%llu buffer=%d broker=%d ipc=%d outgoing=%d action=%d readiness=%d\n",
            t->pid,t->tgid,t->phase,t->kernel_stopped,t->kernel_resume,t->listening,t->interrupt_pending,t->original.regs[8],
            t->buffer_call!=NULL,t->broker_completion!=NULL,t->ipc_pending!=NULL,t->outgoing!=NULL,
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
            TRACE("PROBE cancelled signal=%u\n", info.ssi_signo);
            cancel_tree(128 + info.ssi_signo);
        }
    }
}
static int pid_gone(pid_t pid) {
    /* Proc invisibility is not process death. EPERM still proves existence. */
    return kill(pid,0)<0 && errno==ESRCH;
}
static struct thread *process_thread(pid_t group) {
    struct thread *t=find_thread(group);
    if (t) return t;
    for (t=threads;t;t=t->next) if (t->pid && t->tgid==group) return t;
    return NULL;
}
static int pid_collectible(pid_t pid) {
    struct thread *parent=process_thread(md_task_registry_retired(tasks,pid).parent);
    return (!parent || !md_signal_pending_child_dirty(parent->signal_pending)) && pid_gone(pid);
}
static void child_signals_observe(struct thread *t,int status) {
    if (!t || !WIFSTOPPED(status) || !md_signal_pending_child_dirty(t->signal_pending)) return;
    if (!((unsigned)status>>16) && WSTOPSIG(status)==SIGCHLD) {
        siginfo_t info;
        if (native_trace(PTRACE_GETSIGINFO,t->pid,NULL,&info)<0) { CHECK(errno==ESRCH); return; }
        struct md_signal_origin origin;
        signal_origin_capture(t,&info,&origin); t->signal_origin=origin;
    }
    if (!child_signals_capture(t)) return;
    md_signal_pending_child_observed(t->signal_pending);
    pid_collection_pending=1;
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
    md_task_registry_collect(tasks,pid_collectible); pid_collection_pending=0;
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
        && !t->fd_policy_order.queue
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
     * across TSYNC instead of waiting for the application event. */
    if (!entry_fence_adapter_drained(t)) return 0;
    if (signal_control_parked(t)) return 1;
    if (t->phase==PROCESS_OBSERVING && (t->original.regs[8]==SYS_wait4 || t->original.regs[8]==SYS_waitid)) return 1;
    /* Native fallback and broker-owned reads have already admitted the original
     * call and released task-affine preparation. A blocking inotify read may
     * itself await the installer. MSG_PEEK is only a preparatory wait and must
     * still drain before its task-affine operation can cross the transition. */
    if ((t->phase==WATCH_READ && t->watch_wait!=1) || t->phase==SOCKET_NATIVE) return 1;
    return t->phase==EPOLL_WAIT && t->readiness
        && t->readiness->native_caller && !t->readiness->evicting && !t->readiness->finishing;
}
static int entry_fence_adapt(const struct thread *t) {
    return entry_fence_memory && entry_fence_stage!=3 && t
        && t->memory==entry_fence_memory && t!=entry_fence_owner && !t->entry_fence_waiting
        && entry_fence_boundary(t);
}
static int entry_fence_admission(struct thread *t,const struct user_pt_regs *r,int status,const struct rusage *usage) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int filter=md_seccomp_filter_call(r->regs[8],r->regs[0],r->regs[1]);
    if (!t->entered || t->phase!=IDLE || r->pc==RAW_GATE) return 0;
    int snapshot=entry_snapshot_change(t,r->regs[8],r->regs);
    if (!filter && !snapshot) return 0;
    if (snapshot && (t==entry_fence_owner || !registry || registry->entry_fence_drained
            || !md_svc_registry_resident(registry))) {
        md_memory_snapshots_invalidate(t->memory);
        if (!svc_revoke_entry(t)) return 1;
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
            p->cooperative_blocked=1; md_broker_cooperative_revoke(broker,p->pid);
        }
        return 0;
    }
    if (t==entry_fence_owner) return 0;
    int unrestricted=0;
    if (filter) {
        int group=md_seccomp_filter_flags(r->regs[8],r->regs[1])&SECCOMP_FILTER_FLAG_TSYNC;
        for (struct thread *p=threads;p;p=p->next) if (p->pid
                && (p==t || (group && p->tgid==t->tgid)) && !p->application_filter) {
            unrestricted=1; break;
        }
    }
    if ((!registry || registry->entry_fence_drained) && !unrestricted) return 0;
    /* Revoked entries drain before the new filter commits. No thread-stop
     * barrier is needed for the logical dumpability policy. */
    if (snapshot) {
        if (!svc_revoke_entry(t)) return 1;
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
            p->cooperative_blocked=1; md_broker_cooperative_revoke(broker,p->pid);
        }
    } else if (!filter_revoke_entry(t,r)) return 1;
    if (!md_svc_registry_resident(registry) && !unrestricted) {
        if (registry) registry->entry_fence_drained=1;
        return 0;
    }
    CHECK(!t->entry_fence_waiting);
    ++entry_fence_waiters; t->entry_fence_waiting=1; t->entry_fence_status=status;
    if (usage) t->entry_fence_usage=*usage;
    return 1;
}
static void entry_fence_progress(void) {
    if (!entry_fence_memory) {
        if (!entry_fence_waiters || quiescence_busy(NULL)) return;
        struct thread *caller=threads;
        while (caller && (!caller->pid || !caller->entry_fence_waiting)) caller=caller->next;
        if (!caller) return;
        entry_fence_memory=md_memory_fork(caller->memory,1); CHECK(entry_fence_memory);
        entry_fence_stage=2; entry_fence_slots=0; entry_fence_retry_at=0;
        entry_fence_error=0; entry_fence_deadline=md_event_now()+5000000000LL;
        if (statistics) ++statistics->entry_fence_transitions;
        return;
    }
    if (md_event_now()>=entry_fence_deadline) {
        /* Admission remains revoked; a filter cannot commit over an active entry. */
        entry_fence_error=-EBUSY; entry_fence_deadline=INT64_MAX;
    }
}
static pid_t entry_fence_take(int *status,struct rusage *usage) {
    if (!entry_fence_memory) return 0;
    if (entry_fence_owner) return 0;
    if (entry_fence_stage!=3 && !entry_fence_error) {
        {
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
    md_memory_release(entry_fence_memory); entry_fence_memory=NULL; entry_fence_stage=0; entry_fence_error=0;
    entry_fence_retry_at=0;
    return 0;
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
static int publish_birth(struct thread *t,pid_t child,unsigned event) {
    pid_t pid=t->pid;
    if (event==PTRACE_EVENT_VFORK) {
        CHECK(!t->vfork_child);
        t->vfork_child=(pid_t)child;
    }
    struct thread *c = thread(child);
    CHECK(!c->born);
    c->born = 1; c->ready = t->ready;
    c->vfork_borrowed=event==PTRACE_EVENT_VFORK || ((t->cloning&CLONE_THREAD) && t->vfork_borrowed);
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
    int prior=t->admission_policy_valid && !(t->cloning&CLONE_THREAD);
    c->application_filter=prior ? t->admission_application_filter : t->application_filter;
    md_seccomp_policy_replace(&c->policy,prior ? t->admission_policy : t->policy);
    c->host_signals=t->host_signals; c->host_signal_blocked=t->host_signal_blocked;
    if (t->signal_pending) {
        md_signal_pending_release(c->signal_pending);
        c->signal_pending=md_signal_pending_fork(t->signal_pending,!!(t->cloning&CLONE_THREAD),
            !!(t->cloning&CLONE_SIGHAND));
        CHECK(c->signal_pending);
    }
    if (t->signal_actions) {
        c->signal_actions=md_signal_actions_fork(t->signal_actions,!!(t->cloning&CLONE_SIGHAND),
            !!(t->cloning&CLONE_CLEAR_SIGHAND));
        CHECK(c->signal_actions);
    }
    c->cooperative_blocked=t->cooperative_blocked;
    c->registration_revoked=t->registration_revoked;
    md_identity_release(&c->identity);
    c->identity = md_identity_copy(&t->identity);
    c->tgid = t->cloning & CLONE_THREAD ? t->tgid : c->pid;
    CHECK(!md_shm_space_release(c->shm_space));
    c->shm_space = md_shm_space_fork(t->shm_space,!!(t->cloning & CLONE_VM)); CHECK(c->shm_space);
    CHECK(!md_resources_release(&c->resources));
    if (t->resources.fs) CHECK(!md_resources_fork(&t->resources,t->cloning,t->resource_change.next.cgroup,&c->resources));
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
    /* Linux resets robust registration in every new thread, including fork.
     * Only CLONE_VM inherits the private executor's address-space lifetime. */
    if ((t->cloning&CLONE_VM) && t->memory_actor) {
        c->memory_actor=t->memory_actor; ++c->memory_actor->members;
    }
    c->memory_access_pending=!(t->cloning&CLONE_VM);
    int untraced=!!(t->cloning&CLONE_UNTRACED);
    t->cloning = 0;
    memcpy(c->endpoint, t->endpoint, sizeof(c->endpoint));
    /* Birth publishes membership before the parent can exit or move.
     * A debugger-held newborn is live even before its first resume. */
    CHECK(!md_task_registry_publish(tasks,c->pid,c->tgid,&c->identity,&c->resources));
    if (cancelling) cancel_children(cancelling == 1 ? SIGTERM : SIGKILL);
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
                || (task_action_execution && md_task_quiesce_waiting(task_action_barrier,pid)))) return 0;
    return md_task_checkpoint_event(&checkpoints,pid,status);
}
static int tasks_pending(void) {
    return live || retained_memories || md_fs_buffer_children(buffer_service) || md_task_helpers_count(helpers);
}
static pid_t wait_event(int *status, struct rusage *usage) {
    enum { PROGRESS_BATCH=8 };
    static unsigned progress_budget;
    for (;;) {
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
            event_sources(events);
            if (statistics) ++statistics->event_polls;
            long ready = md_event_poll(events, EVENT_SOURCES);
            CHECK(ready >= 0);
            if (ready) progress_events(events);
            progress_budget=PROGRESS_BATCH;
        }
        --progress_budget;
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
            if (returning) md_signal_delivery_checkpoint(&returning->signal_delivery,*status);
            if (returning && returning->signal_control && returning->signal_control->returning)
                signal_control_discard(returning);
            int checkpoint=checkpoint_event(pid,status);
            if (checkpoint==-ESRCH) continue;
            if (checkpoint<0) fprintf(stderr,"Shroot: checkpoint pid=%d status=%x error=%d\n",pid,*status,checkpoint);
            CHECK(checkpoint>=0);
            if (checkpoint) continue;
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
        if (memory_runnable() || retained_runnable()) continue;
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
enum memory_stage { MEMORY_WRITERS, MEMORY_WORKER, MEMORY_VALIDATE, MEMORY_ACTOR, MEMORY_MAP, MEMORY_COPY,
    MEMORY_ADVICE, MEMORY_REPLACE };
struct memory_execution {
    struct thread *actor;
    struct retained_memory *executor;
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
    unsigned advice;
    int complete, calling, terminal, global;
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
    return (!(retained_creation || retained_retirement) || !md_task_quiesce_contains(retained_barrier,pid))
        && (!policy_installer || pid==policy_installer->pid || !md_task_quiesce_contains(policy_barrier,pid))
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
    case MEMORY_ACTOR: return execution->executor && !execution->executor->creator;
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
    pid_t actor=execution->executor->retained.actor;
    execution->call=*call; execution->calling=1;
    struct user_pt_regs regs=md_task_registers_entry(abi.task_call,0,execution->returned.pstate);
    regs.regs[8]=call->number; memcpy(regs.regs,call->args,sizeof(call->args));
    CHECK(!md_task_registers_access(native_trace,actor,&regs,1));
    CHECK(!native_trace(PTRACE_CONT,actor,NULL,NULL));
}
static void memory_finish(struct memory_execution *execution) {
    struct thread *t=execution->actor;
    int terminal=execution->terminal;
    if (!terminal) {
        struct user_pt_regs restore=execution->returned;
        restore.regs[0]=execution->error ? -EACCES : 0;
        CHECK(set_registers(t->pid,&restore));
    }
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
    t->phase=IDLE;
    if (!terminal) resume(t->pid,0);
}
static void memory_replay_unmodified(struct memory_execution *execution,int status,const struct rusage *usage) {
    CHECK(execution->stage<MEMORY_MAP);
    CHECK(!quiesce_event(memory_barrier,execution->actor->pid,status,usage));
    if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
    CHECK(!md_task_quiesce_release(memory_barrier));
    memory_discard();
    task_action_unpark();
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
    memory_replay_unmodified(execution,execution->status,&execution->usage);
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
        execution->executor=retained_memory_get(t);
        if (!execution->executor) { execution->error=-ENOMEM; memory_finish(execution); return; }
        execution->stage=MEMORY_ACTOR;
        if (!execution->executor->ready) {
            /* The caller already owns the mm turn and writer barrier. Reuse
             * that ownership for the same private executor as robust cleanup. */
            retained_creation_begin(execution->executor,t,&execution->returned,MD_TASK_CALL_EXIT);
            return;
        }
    }
    if (execution->stage==MEMORY_ACTOR) {
        if (execution->executor->creator) return;
        execution->error=execution->executor->error;
        if (execution->error) { memory_finish(execution); return; }
        CHECK(execution->executor->ready && !execution->executor->cleaning);
        pid_t actor=execution->executor->retained.actor;
        struct user_pt_regs saved;
        CHECK(!md_task_registers_access(native_trace,actor,&saved,0));
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,
            .mapping={.source=execution->mapping.source,.size=execution->mapping.length,
                .offset=execution->mapping.offset,.protection=execution->mapping.protection,
                .flags=execution->plan.flags}};
        execution->stage=MEMORY_MAP;
        execution->error=md_task_helper_borrow(helpers,actor,actor,MD_TASK_CALL_USER,&saved,&abi,
            t->memory,&execution->buffer,&request,memory_complete,execution);
        if (execution->error) memory_finish(execution);
        return;
    }
    if (execution->stage==MEMORY_MAP) {
        if (!execution->complete) return;
        execution->timing[4]=md_event_now();
        if (execution->result.value<0) {
            execution->error=execution->result.value; memory_finish(execution); return;
        }
        execution->mapping.candidate=(uintptr_t)execution->result.value;
        execution->stage=MEMORY_COPY;
    }
    if (execution->stage==MEMORY_COPY) {
        long ready=execution->error ? execution->error
            : md_private_memory_copy_step(&execution->mapping,access,&execution->plan,
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
    struct md_task_quiesce **barrier=quiescence_barrier(NULL);
    if (!barrier || !WIFSTOPPED(status) || (unsigned)status>>16
            || WSTOPSIG(status)!=(SIGTRAP|0x80)) return 0;
    struct thread *t=find_thread(pid);
    if (!t || t->phase!=OBSERVING || t->memory_action!=MEMORY_KERNEL
            || t->original.regs[8]!=SYS_mprotect) return 0;
    if ((retained_creation && retained_creation->creator==t)
            || (retained_retirement && retained_retirement->actor==t)
            || policy_installer==t
            || (memory_execution && memory_execution->actor==t)
            || (task_action_execution && task_action_execution->actor==t)
            || (mapping_execution && mapping_execution->caller==pid)) return 0;
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
    unsigned event=(unsigned)status>>16;
    if (execution->stage==MEMORY_ACTOR && execution->executor && retained_creation==execution->executor
            && (pid==execution->actor->pid || retained_actor(pid)==execution->executor)) {
        int consumed=retained_event(pid,status,usage);
        if (execution->executor->retained.parent_terminal && !retained_creation) {
            memory_replay_unmodified(execution,status,usage);
            return 1;
        }
        return consumed;
    }
    if (pid==execution->actor->pid && execution->stage<MEMORY_MAP
            && (WIFEXITED(status) || WIFSIGNALED(status)
                || (WIFSTOPPED(status) && (event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)))) {
        /* Preparation has not changed mappings or registers. Replay the actual
         * lifecycle event, not the superseded mprotect exit, before releasing
         * its task and address-space owners in the ordinary dispatcher. */
        memory_replay_unmodified(execution,status,usage);
        return 1;
    }
    if (execution->stage==MEMORY_MAP && md_task_helper_owns(helpers,execution->executor->retained.actor,pid)) {
        CHECK(md_task_helper_event(helpers,pid,status)==1);
        return 1;
    }
    if (execution->stage>=MEMORY_MAP && pid==execution->actor->pid
            && (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)) {
        /* The private mm executor survives group death and de_thread. Retain
         * the exact lifecycle event until its candidate is committed or rolled
         * back; never restore a dead caller's registers or reused FD numbers. */
        execution->terminal=1; execution->error=-ECANCELED;
        md_memory_replace_cancel(&execution->replacement);
        CHECK(!md_task_quiesce_terminal(memory_barrier,pid,status,usage));
        return 1;
    }
    if (execution->calling && pid==execution->executor->retained.actor) {
        CHECK(WIFSTOPPED(status));
        unsigned event=(unsigned)status>>16;
        if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL)); return 1;
        }
        struct user_pt_regs regs;
        CHECK(!md_task_registers_access(native_trace,pid,&regs,0));
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
            if (execution->stage==MEMORY_ADVICE) {
                if (!execution->error) execution->error=(long)regs.regs[0];
            }
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
    if (execution->stage<MEMORY_MAP
            && !(execution->executor && execution->executor->creator)
            && (!WIFSTOPPED(status)
            || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)) {
        /* A writer's exec may need this terminal sibling before acknowledging
         * INTERRUPT. No mapping changed yet: replay the peer's lifecycle and
         * the original mprotect exit, not another execution of mprotect. */
        CHECK(!md_task_quiesce_terminal(memory_barrier,pid,status,usage));
        memory_replay_unmodified(execution,execution->status,&execution->usage);
        return 1;
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
        CHECK(!quiesce_task_add(memory_barrier,peer,peer==t));
    CHECK(!md_task_helpers_visit(helpers,execution->global ? NULL : t->memory,memory_participant,memory_barrier));
    CHECK(!md_task_quiesce_stop(memory_barrier));
    return 1;
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
    struct md_debugger_host debug_host={.parent=debugger_parent,.group=debugger_group,.uid=debugger_uid,.allowed=debugger_allowed,.traceme=debugger_traceme,
        .wait_children=private_wait_children,
        .visible_pid=debugger_visible_pid,.native_pid=debugger_native_pid,.visible_uid=debugger_visible_uid,.reaped=debugger_reaped,
        .signal_origin=debugger_signal_origin,.signal_translate=debugger_signal_translate,
        .attachable=debugger_attachable,.observe=debugger_observe,.interrupt=debugger_interrupt,.options=debugger_options,
        .listen=debugger_listen,.usage=debugger_usage,.wait=debugger_wait,.wait_wake=debugger_wait_wake,
        .complete=debugger_complete,.resume=resume_signal,.signal_mask=debugger_signal_mask};
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
    thread(leader)->signal_pending=md_signal_pending_new(); CHECK(thread(leader)->signal_pending);
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
        md_broker_entry_handler(broker,entry_control);
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
            TRACE("PROBE inherited-fd pin=%d error=%d\n",fd,errno);
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
            int error=md_svc_machine_signal_committed(md_memory_svc(signalled->memory),
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
        if (t->signal_control && t->signal_control->entry_packet) {
            t->kernel_stopped=1;
            t->interrupt_pending=0;
            if (signal_control_event(t,status)) continue;
        }
        struct md_task_signal_stop signal_stop;
        md_task_signal_stop_init(&signal_stop,native_trace);
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
            if (!md_signal_synchronous(&info)) {
                int retained=md_signal_delivery_retain(&t->signal_delivery,pid,&info); CHECK(retained>=0);
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
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            status=process_reboot_status(pid,status);
            int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            if (pid == leader && !cancelling) result = code;
            if (code) TRACE("PROBE exit pid=%d status=%d ready=%d calls=%u\n", pid, code, t->ready, t->calls);
            int traced=md_debugger_traced(debugger,pid);
            if (t->born && (t->pid==t->tgid || traced)) {
                md_task_registry_retire(tasks,pid,t->parent,&t->identity);
                struct thread *parent=process_thread(t->parent);
                if (parent) md_signal_pending_child_changed(parent->signal_pending);
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
                if (t->signal_wait_view.active) {
                    uint64_t mask=t->signal_wait_view.saved&~MD_SIGNAL_SYS_BIT;
                    signal_wait_restore(t);
                    if (!signal_mask(t,mask,"wait-return-mask")) continue;
                }
                CHECK(!md_event_signal(fd_ready));
            }
        }
        if (readiness_native_event(t,status)) continue;
        if (!event && sig!=(SIGTRAP|0x80) && (t->phase==IDLE || t->entry_execution)
                && md_memory_svc(t->memory)) {
            int deliver=0;
            int owned=md_svc_machine_signal(md_memory_svc(t->memory),&t->machine,pid,
                &t->signal_delivery,&signal_stop,&deliver);
            if (owned==-ESRCH) { t->resume_lost=1; continue; }
            if (owned<0) errno=-owned;
            CHECK(owned>=0);
            if (owned) { deliver_signal(pid,deliver,restored_signal,&signal_stop); continue; }
        }
        if (!event && sig!=(SIGTRAP|0x80)) notification_interrupted(t);
        if (event==PTRACE_EVENT_EXIT) {
            if (t->born && !process_thread(t->parent)) {
                long parent=md_task_parent(pid);
                if (parent>=0) t->parent=(pid_t)parent;
            }
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
                        "\"declined\":%llu,\"unknown\":%llu,\"retiredClients\":%llu,\"revoked\":%u,"
                        "\"filteredCompleted\":%llu}\n",
                        pid,machine->count,(unsigned long long)state.completed,
                        (unsigned long long)state.declined,(unsigned long long)state.unknown,
                        (unsigned long long)state.retired_clients,machine->disabled,
                        (unsigned long long)(machine->filter_statistics && state.completed>=machine->filter_completed_start
                            ? state.completed-machine->filter_completed_start : 0));
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
            message=(unsigned)process_reboot_status(pid,(int)message);
            TRACE("PROBE terminal-stop pid=%d lost=%d status=%#lx\n",pid,resume_lost,message);
            if (signal_diagnostics && !diagnostics && (message&0x7f))
                TRACE_SIGNAL("PROBE terminal-stop pid=%d lost=%d status=%#lx\n",pid,resume_lost,message);
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
                else CHECK(!md_resources_exec_closed(&executing->resources,executing->identity.user_namespace,&executing->closed));
                exec_closed=executing->closed; executing->closed=(struct md_fd_closed){0};
            }
            if (old && old != (unsigned)pid) {
                md_debugger_rekey(debugger,(pid_t)old,pid);
                struct thread *former = find_thread((pid_t)old);
                if (former) {
                    md_signal_delivery_clear(&t->signal_delivery);
                    t->signal_delivery=former->signal_delivery;
                    t->signal_delivery.capture_context=t;
                    former->signal_delivery=(struct md_signal_delivery){0};
                    t->signal_origin=former->signal_origin;
                    former->signal_origin.valid=0;
                    t->terminal_observation=former->terminal_observation;
                    t->debug_options=former->debug_options;
                    t->debug_call_active=former->debug_call_active;
                    t->debug_step_call=former->debug_step_call;
                    t->trace_options_dirty=former->trace_options_dirty;
                    t->pid_filter=former->pid_filter;
                    t->application_filter=former->application_filter;
                    md_seccomp_policy_replace(&t->policy,NULL);
                    t->policy=former->policy; former->policy=NULL;
                    md_signal_actions_release(t->signal_actions);
                    t->signal_actions=former->signal_actions; former->signal_actions=NULL;
                    t->host_signals=former->host_signals;
                    t->host_signal_blocked=former->host_signal_blocked;
                    md_signal_pending_release(t->signal_pending);
                    t->signal_pending=former->signal_pending; former->signal_pending=NULL;
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
            signal_control_discard(t);
            CHECK(!md_signal_pending_exec(t->signal_pending));
            if (t->signal_actions) {
                struct md_signal_actions *next=md_signal_actions_exec(t->signal_actions); CHECK(next);
                md_signal_actions_release(t->signal_actions); t->signal_actions=next;
            }
            exec_images_activate(t);
            md_memory_stack_return(t->memory, t->stack);
            CHECK(!md_shm_space_release(t->shm_space));
            t->shm_space = md_shm_space_new(); CHECK(t->shm_space);
            ipc_unwatch(t);
            CHECK(!md_ipc_cancel(md_ipc_namespace_active(t->resources.ipc),&t->ipc_pending)); t->ipc_cancel=t->ipc_waking=0;
            md_ipc_transport_close(&t->ipc_transport);
            struct md_interception_memory *memory=md_memory_new_image(t->memory); CHECK(memory);
            t->vfork_borrowed=0;
            retained_detach(t);
            md_memory_release(t->memory); t->memory=memory;
            t->memory_access_pending=0;
            t->phase = IDLE; t->stack = 0; t->ready = 0; t->resume_lost = 0; t->terminal_stop=0; images++;
            t->mapped_image = 0; t->entered = 0; t->image_backings_ready=0;
            /* Entry revocation belongs to the retired address space. Only
             * inherited application policy still needs admission in this image. */
            t->cooperative_blocked=!!t->application_filter;
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
            long child=md_task_birth_read(native_trace,pid,event,&terminal);
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
                task_group_stop(t);
                continue;
            }
            int wake = t->listening;
            if (wake) {
                CHECK(sig == SIGTRAP); t->listening = 0; group_wakes++;
                md_debugger_child_changed(debugger,pid);
            }
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
            int captured=machine_context(t,observed);
            if (captured) {
                if (!registers(pid,&regs)) continue;
                int native_entry=machine_native_entry(t,&regs,&cookie);
                if (native_entry && observed) info.seccomp.ret_data=cookie;
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
            if (t->entered && t->phase==IDLE && md_seccomp_policy_may_trace(admission_policy(t))) {
                if (!captured && !registers(pid,&regs)) continue;
                captured=1;
                struct seccomp_data call={.nr=regs.regs[8],.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=regs.pc};
                memcpy(call.args,regs.regs,sizeof(call.args));
                if (guest_trace_rejected(t,&call)) { return_value(t,&regs,-ENOSYS); continue; }
            }
            if (t->entered && t->phase==IDLE && md_seccomp_policy_may_notify(admission_policy(t))) {
                if (!captured && !registers(pid,&regs)) continue;
                captured=1;
                struct seccomp_data call={.nr=regs.regs[8],.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=regs.pc};
                memcpy(call.args,regs.regs,sizeof(call.args));
                if (t->notification_continued) {
                    t->notification_continued=0; cookie=guest_notification_route(t,&call);
                } else if (guest_notification_begin(t,&call,&regs,NULL,NULL)) continue;
            }
            if (observed && t->resources.files && fd_native_io(t,observed)) continue;
            if (fd_close_compact(t,cookie,observed)) continue;
            if (cookie&MD_INTERCEPT_CLOSE) cookie=MD_INTERCEPT_FD;
            if (observed && cookie==MD_INTERCEPT_SIGNAL && t->host_signals && t->entered
                    && t->phase==IDLE && observed->instruction_pointer!=RAW_GATE
                    && observed->instruction_pointer!=abi.task_return
                    && signal_native_call(t,observed->seccomp.nr,
                        observed->seccomp.args[1],observed->seccomp.args[2],observed->seccomp.args[3])) continue;
            if (!captured && !registers(pid, &regs)) continue;
            /* An owned nonblocking wait may encounter either the baseline
             * waitid observer or a stacked PID selector. Its exit belongs to
             * the same wait owner; do not start a second observation. */
            if ((t->phase==DEBUG_WAIT_ENTRY || t->phase==DEBUG_WAIT_EXIT)
                    && (regs.regs[8]==SYS_wait4 || regs.regs[8]==SYS_waitid)) {
                resume(pid,0); continue;
            }
            if (cookie==MD_INTERCEPT_SIGNAL && !t->entered) { resume(pid,0); continue; }
            int signal_descriptor=regs.regs[8]==SYS_signalfd4;
            if (t->entered && t->phase==IDLE
                    && (regs.regs[8]==SYS_read || regs.regs[8]==SYS_readv)) {
                struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)regs.regs[0],NULL);
                int selected=md_signal_fd_from_object(object)!=NULL;
                md_fd_object_release(object);
                if (selected) signal_descriptor=1;
            }
            if (signal_descriptor && t->entered && t->phase==IDLE
                    && regs.pc!=RAW_GATE && regs.pc!=abi.task_return) {
                int error=signal_control_begin(t,&regs,0,NULL,0,NULL);
                if (error) return_value(t,&regs,error);
                continue;
            }
            if (t->host_signals && t->entered && t->phase==IDLE
                    && (cookie==MD_INTERCEPT_SIGNAL || regs.regs[8]==SYS_rt_sigtimedwait)) {
                if (regs.pc==RAW_GATE || regs.pc==abi.task_return) { resume(pid,0); continue; }
                /* Kernel admission at this application site is authoritative:
                 * ERRNO/TRAP/KILL outrank our selector. Do not re-admit using a
                 * policy which a concurrent TSYNC may have published later. */
                if (signal_native_call(t,regs.regs[8],regs.regs[1],regs.regs[2],regs.regs[3])) continue;
                if (regs.regs[8]==SYS_rt_sigaction || regs.regs[8]==SYS_rt_sigpending) {
                    int error=signal_control_begin(t,&regs,0,NULL,0,NULL);
                    if (error) return_value(t,&regs,error);
                    continue;
                }
                if (regs.regs[8]==SYS_rt_sigtimedwait || regs.regs[8]==SYS_signalfd4
                        || regs.regs[8]==SYS_read || regs.regs[8]==SYS_readv) {
                    int error=signal_control_begin(t,&regs,0,NULL,0,NULL);
                    if (error) return_value(t,&regs,error);
                    continue;
                } else if (regs.regs[8]==SYS_ppoll || regs.regs[8]==SYS_pselect6
                        || regs.regs[8]==SYS_epoll_pwait || regs.regs[8]==SYS_epoll_pwait2 || regs.regs[8]==SYS_rt_sigsuspend)
                    cookie=MD_INTERCEPT_DISPATCH;
                else cookie=md_seccomp_filter_call(regs.regs[8],regs.regs[0],regs.regs[1])
                    ? MD_INTERCEPT_OBSERVE : MD_INTERCEPT_NATIVE;
            }
            if (t->entry_fence_result) {
                int result=t->entry_fence_result; t->entry_fence_result=0;
                return_value(t,&regs,result); continue;
            }
            if (cookie==MD_INTERCEPT_TASK) {
                /* Only an already-owned wait may use the service gate when
                 * the application's filter denies ppoll. The original call
                 * has passed admission; a direct application ppoll has not. */
                if (regs.pc==abi.task_return && regs.regs[8]==SYS_ppoll
                        && !regs.regs[0] && !regs.regs[1]
                        && (task_parked(t) || t->phase==DEBUG_PARK_EXIT)) {
                    resume(pid,0); continue;
                }
                /* Wait with the application mask; retire the native exit before
                 * allowing a handler to observe the adapter's stack or pointers.
                 * Original accept admission owns accept4(...,0), while direct
                 * accept4 calls retain their own application filter policy. */
                int substituted=t->original.regs[8]==SYS_accept && regs.regs[8]==SYS_accept4;
                if (t->phase==DISPATCHING && regs.pc==abi.socket_return
                        && md_socket_wait_call(regs.regs[8],regs.regs[2],regs.regs[3])
                        && (!substituted || (regs.regs[0]==t->original.regs[0]
                            && !regs.regs[3] && !!regs.regs[1]==!!t->original.regs[1]))) {
                    if (!restore_mask(t)) continue;
                    t->phase=SOCKET_NATIVE; resume(pid,0);
                }
                else return_value(t,&regs,-EPERM);
                continue;
            }
            if (entry_fence_admission(t,&regs,status,&usage)) continue;
            if (fd_duplicate_prepared(t,&regs,cookie)) {
                if (statistics) ++statistics->calls[regs.regs[8]].trace;
                fd_begin(t,&regs,MD_INTERCEPT_FD); continue;
            }
            if (regs.regs[8]==SYS_ptrace && t->phase==IDLE)
                TRACE("PROBE guest debugger caller=%d request=%llu target=%llu address=%#llx data=%#llx traced=%d\n",
                    pid,regs.regs[0],regs.regs[1],regs.regs[2],regs.regs[3],
                    md_debugger_traced(debugger,native_pid(t,(pid_t)regs.regs[1])));
            if (t->phase==IDLE && (regs.regs[8]==SYS_ptrace || regs.regs[8]==SYS_wait4
                    || regs.regs[8]==SYS_waitid)
                    && md_debugger_call(debugger,pid,&regs)) continue;
            if (statistics) {
                if (regs.regs[8] < 512) statistics->calls[regs.regs[8]].trace++;
                else statistics->unknown++;
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
                /* A caller-owned terminal open can retry append admission.
                 * The path/access mode remain the exact pinned offer. */
                int caller_flags=regs.regs[2]==(unsigned long)t->buffer_open.flags
                    || ((t->buffer_open.flags&O_APPEND)
                        && regs.regs[2]==(unsigned long)(t->buffer_open.flags&~O_APPEND));
                int capture_flags=delivery ? t->buffer_open.fd>=0
                    && (t->buffer_open.completion.kind==MD_OPEN_CALLER ? caller_flags
                        : regs.regs[2]==(unsigned long)(O_PATH|(t->buffer_open.flags&(O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW))))
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
            if (t->phase==SOCKET_NATIVE) {
                struct user_pt_regs regs;
                if (!registers(pid,&regs) || !shield(t)) continue;
                if (md_syscall_restarting((long)regs.regs[0])) {
                    regs.regs[0]=(uint64_t)RESTART_SIGNAL;
                    if (!set_registers(pid,&regs)) continue;
                }
                t->phase=DISPATCHING; resume(pid,0); continue;
            }
            if (t->phase==SIGNAL_NATIVE_EXIT) {
                signal_native_complete(t);
                continue;
            }
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
                if (t->phase==SOCKET_WAITING) { socket_wait_cancelled(t); continue; }
                if (t->phase==IPC_WAITING) { dispatch_with_stack(t); continue; }
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
                if (t->process_inspection_denied) {
                    if (value==-ESRCH) regs.regs[0]=(uint64_t)-EPERM;
                    t->process_inspection_denied=0;
                }
                /* Kernel restart retains orig_x0; restore every other argument
                 * before signal delivery can save the interrupted context. */
                memcpy(regs.regs+1,t->original.regs+1,7*sizeof(regs.regs[0]));
                if (value>0 && nr==SYS_wait4 && t->original.regs[1] && process_reboot_signal((pid_t)value)) {
                    t->process_return=regs; t->process_return.regs[0]=visible_pid(t,(pid_t)value);
                    t->process_info_native=(pid_t)value;
                    if (!save_signal_mask(t,&t->mask,"pid-wait-status-mask") || !shield(t)) continue;
                    regs.pc=abi.load_groups; regs.regs[0]=t->original.regs[1]; regs.regs[1]=1;
                    t->phase=PROCESS_INFO_LOAD;
                    if (set_registers(pid,&regs)) resume(pid,0);
                    continue;
                }
                if (!value && nr==SYS_waitid && t->original.regs[2]) {
                    t->process_return=regs;
                    if (!save_signal_mask(t,&t->mask,"pid-info-mask") || !shield(t)) continue;
                    regs.pc=abi.load_groups; regs.regs[0]=t->original.regs[2]; regs.regs[1]=8;
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
                    if (nr==SYS_wait4) process_reap(t,(pid_t)value);
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
                siginfo_t info,*captured=NULL;
                if (t->debug_wait_output) {
                    struct md_buffer_span span;
                    CHECK(!md_buffer_span(&t->debug_wait_output->buffer,&span));
                    memcpy(&info,span.data,sizeof(info)); captured=&info;
                    debugger_wait_release(t);
                }
                t->phase=IDLE;
                md_debugger_wait_complete(debugger,pid,(long)regs.regs[0],captured);
                if (captured) adapter_progress();
                continue;
            }
            if (t->phase==DEBUG_PARK_ENTRY) {
                if (t->debug_wait_waking) {
                    if (md_app_uid_active) {
                        if (!tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"debug-park-ready-entry")) continue;
                    } else
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
                    if (t->entered && t->process_image && md_debugger_syscall(debugger,pid,&regs,1)) continue;
                } else {
                    int pending=debug_host_policy_pending(t,&regs);
                    if (pending<0) { CHECK(pending==-ESRCH); t->resume_lost=1; continue; }
                    if (pending) { resume(pid,0); continue; }
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
                /* SYSEMU skips before inherited seccomp. Replacing the number
                 * with -1 at a normal entry can itself trigger Android SIGSYS. */
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
                    t->wait_error=0;
                    wait_cancel_entry(t,SOCKET_WAITING);
                    continue;
                }
                int error=socket_watch(t);
                if (error) {
                    t->wait_error=error;
                    wait_cancel_entry(t,SOCKET_WAITING);
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
                    wait_cancel_entry(t,IPC_WAITING);
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
                if (t->filter_install) {
                    struct filter_install *install=t->filter_install;
                    if (install->virtual_listener) {
                        install->native_completed=1;
                        if ((long)regs.regs[0]!=0) { filter_install_fail(t,(int)regs.regs[0]); continue; }
                        CHECK(install->loan && install->loan->number>=0);
                        regs.regs[0]=install->loan->number;
                    }
                    regs.regs[1]=t->original.regs[1];
                    regs.regs[2]=t->original.regs[2];
                    if (!set_registers(pid,&regs)) continue;
                }
                if (filter_observed(t,&regs)) continue;
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
                if (t->deferred_selector) { selector_deferred_complete(t,0); continue; }
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
            if (t->entry_execution && !t->entry_kernel_context) {
                if (!save_signal_mask(t,&t->mask,"entry-kernel-context")) continue;
                t->entry_kernel_context=1;
            }
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
                if (t->phase==DISPATCHING && (t->original.regs[8]==SYS_clone
                        || t->original.regs[8]==SYS_clone3 || t->original.regs[8]==SYS_unshare)) {
                    pid_filter_begin(t,&regs,NULL); continue;
                }
                mapping_begin(t,&regs); continue;
            }
            if (checkpoint==abi.enter && t->phase==IDLE && t->ready) {
                if (!t->entered) {
                    int result=image_enter(t,regs.regs[2],regs.regs[3],regs.regs[4],regs.regs[5]);
                    /* Both ordinary and secure images commit at this stop. */
                    if (result) { return_value(t,&regs,result); continue; }
                } else if (regs.regs[3] || regs.regs[4] || regs.regs[5]) {
                    return_value(t,&regs,-EPERM); continue;
                }
                if (t->image_backings_ready && !md_memory_svc(t->memory)) {
                    struct md_svc_registry *registry=md_svc_machine_admit(pid,abi.svc_runtime);
                    if (registry) {
                        CHECK(!md_memory_svc_set(t->memory,registry));
                        if (t->application_filter) {
                            int number=md_seccomp_policy_entry_checkpoint(t->policy);
                            CHECK(number>=0);
                            registry->checkpoint_number=(unsigned)number;
                            CHECK(!md_svc_registry_pause_filter(registry,pid));
                            registry->entry_fence_drained=1;
                        }
                        if (!t->terminal_observation) {
                            t->terminal_observation=1; t->trace_options_dirty=1;
                        }
                    }
                }
                t->process_image=regs.regs[2];
                uint64_t entry=regs.regs[0], stack=regs.regs[1];
                regs=md_task_registers_entry(entry,stack,regs.pstate);
                if (t->application_filter && filter_entry_map(t,&regs)) continue;
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
                if (t->original.regs[8]==SYS_wait4) {
                    int status=process_reboot_status(t->process_info_native,(int)regs.regs[3]);
                    t->process_info_code=WIFEXITED(status) ? CLD_EXITED : WIFSIGNALED(status) ? CLD_KILLED : 0;
                    regs.regs[3]=(unsigned)status; regs.regs[0]=t->original.regs[1]; regs.regs[1]=1;
                    regs.pc=abi.store_groups; t->phase=PROCESS_INFO_STORE;
                    if (set_registers(pid,&regs)) resume(pid,0);
                    continue;
                }
                _Static_assert(offsetof(siginfo_t,si_pid)==16,"AArch64 siginfo PID offset");
                int child=(int)regs.regs[3]==SIGCHLD;
                t->process_info_code=(int)regs.regs[5];
                t->process_info_native=(pid_t)regs.regs[7];
                int status=(int)regs.regs[9];
                if (child) process_reboot_info(t->process_info_native,&t->process_info_code,&status);
                int translate=(int)regs.regs[3]==SIGCHLD || t->process_info_code==SI_USER || t->process_info_code==SI_TKILL;
                unsigned padding=(unsigned)regs.regs[6];
                pid_t sender=translate ? visible_pid(t,t->process_info_native) : t->process_info_native;
                uid_t uid=translate ? process_signal_uid(t,t->process_info_native,(uid_t)regs.regs[8]) : regs.regs[8];
                regs.regs[3]=(unsigned)t->process_info_code; regs.regs[4]=padding;
                regs.regs[5]=(unsigned)sender; regs.regs[6]=uid; regs.regs[7]=(unsigned)status;
                regs.regs[0]=t->original.regs[2]+offsetof(siginfo_t,si_code);
                regs.regs[1]=5; regs.pc=abi.store_groups; t->phase=PROCESS_INFO_STORE;
                if (set_registers(pid,&regs)) resume(pid,0);
                continue;
            }
            if (checkpoint==abi.stored_groups && t->phase==PROCESS_INFO_STORE) {
                if ((t->original.regs[8]==SYS_wait4 || (t->original.regs[8]==SYS_waitid && !(t->original.regs[3]&WNOWAIT))) && (t->process_info_code==CLD_EXITED
                        || t->process_info_code==CLD_KILLED || t->process_info_code==CLD_DUMPED))
                    process_reap(t,t->process_info_native);
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
                if (broker && !t->entered && (!t->registration_revoked
                        || (t->application_filter && md_seccomp_policy_entry_checkpoint(t->policy)>=0))
                        && !t->debug_options && !md_memory_exposed(t->memory))
                    regs.regs[0]|=MD_INTERCEPTION_MACHINE;
                if (statistics) regs.regs[0]|=MD_INTERCEPTION_STATISTICS;
                if (md_app_uid_active) regs.regs[0]|=MD_INTERCEPTION_HOST_POLICY;
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
            TRACE("PROBE signal stop pid=%d signal=%d phase=%d\n",pid,sig,t->phase);
            if (sig==SIGSYS && !restored_signal) {
                int error=md_svc_machine_seccomp(md_memory_svc(t->memory),pid,&signal_stop);
                if (error==-ESRCH) { t->resume_lost=1; continue; }
                CHECK(!error);
            }
            if (sig==SIGSYS && !restored_signal && host_policy_signal(t,&signal_stop)) continue;
            if (sig==SIGSTOP && md_debugger_traced(debugger,pid) && !debugger_at_boundary(t)
                    && signal_observe(t,&signal_stop,0)
                    && md_debugger_defer_stop(debugger,pid,&signal_stop.info)) {
                /* EVENT_WAIT: finish the internal operation before publishing
                 * the debugger stop; task exit/cancellation releases its owner.
                 * Delivering SIGSTOP now would group-stop shared resource owners. */
                resume(pid,0); continue;
            }
            if (md_debugger_traced(debugger,pid) && debugger_at_boundary(t)
                    && signal_observe(t,&signal_stop,0)
                    && md_debugger_stop(debugger,pid,status,&signal_stop.info,0)) continue;
            struct user_pt_regs regs={0};
            if (sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL
                        || ((diagnostics || signal_diagnostics) && sig == SIGTRAP)) {
                if (!signal_observe(t,&signal_stop,1)) continue;
                regs=signal_stop.regs;
            }
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
                if (!signal_observe(t,&signal_stop,0)) continue;
                siginfo_t info=signal_stop.info;
                if (info.si_code>0) {
                    copy_faults++;
                    regs.regs[0] = (uint64_t)-EFAULT; regs.pc = regs.regs[30];
                    if (set_registers(pid, &regs)) resume(pid, 0);
                    continue;
                }
            }
            if (sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL
                    || ((diagnostics || signal_diagnostics) && sig == SIGTRAP)) {
                if (!signal_observe(t,&signal_stop,0)) continue;
                siginfo_t info=signal_stop.info;
                signals++;
                if (sig == SIGSYS && info.si_code==SYS_SECCOMP) {
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
                    if (i == 64 || denials[i].count > 4) {
                        deliver_signal(pid,sig,restored_signal,&signal_stop); continue;
                    }
                }
                TRACE_SIGNAL("PROBE signal pid=%d signal=%d code=%d pc=%#llx nr=%llu phase=%d adapted=%llu address=%p\n",
                    pid, sig, info.si_code, regs.pc, regs.regs[8], t->phase, t->original.regs[8], info.si_addr);
                TRACE_SIGNAL("PROBE signal-context pid=%d group=%d sp=%#llx lr=%#llx x0=%#llx shielded=%d logicalSigsys=%d\n",
                    pid,t->tgid,regs.sp,regs.regs[30],regs.regs[0],t->signal_shielded,t->host_signal_blocked);
                if ((diagnostics || signal_diagnostics) && t->process_image) {
                    char image[PATH_MAX];
                    ssize_t length=md_process_image_link(pid,t->process_image,-1,image,sizeof(image));
                    if (length>0) TRACE_SIGNAL("PROBE signal-image pid=%d path=%.*s\n",pid,(int)length,image);
                }
                if (sig == SIGSYS)
                    TRACE_SIGNAL("PROBE application SIGSYS syscall=%d cookie=%d call=%p\n", info.si_syscall, info.si_errno, info.si_call_addr);
            }
            deliver_signal(pid,sig,restored_signal,&signal_stop);
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
        fprintf(stderr,"MD_EXECUTION taskEntries=%llu completed=%llu promotions=%llu nativeEffects=%llu\n",
            (unsigned long long)statistics->entry_executions,
            (unsigned long long)statistics->entry_completions,
            (unsigned long long)statistics->entry_promotions,
            (unsigned long long)statistics->entry_effects);
        static const char *context_names[MD_CONTEXT_COUNT]={"copy","process","birth","exec","signal","wait",
            "descriptor","memory","policy","namespace","debug"};
        fprintf(stderr,"MD_EXECUTION_NATIVE calls=%llu\n",(unsigned long long)statistics->entry_native);
        fprintf(stderr,"MD_EXECUTION_LOCAL_NATIVE calls=%llu\n",(unsigned long long)statistics->entry_local_native);
        for (unsigned i=0;i<MD_CONTEXT_COUNT;++i) if (statistics->entry_contexts[i])
            fprintf(stderr,"MD_EXECUTION_CONTEXT reason=%s calls=%llu\n",context_names[i],
                (unsigned long long)statistics->entry_contexts[i]);
        for (unsigned i=0;i<MD_CONTEXT_COUNT;++i) if (statistics->entry_local_contexts[i])
            fprintf(stderr,"MD_EXECUTION_LOCAL_CONTEXT reason=%s calls=%llu\n",context_names[i],
                (unsigned long long)statistics->entry_local_contexts[i]);
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
