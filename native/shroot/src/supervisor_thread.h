#ifndef MD_SUPERVISOR_THREAD_H
#define MD_SUPERVISOR_THREAD_H

/* Private lifecycle state shared by supervisor operation owners. */
#include "task_resources.h"
#include "signal_delivery.h"
#include "signal_view.h"
#include "svc_machine.h"
#include "fd_channel.h"
#include "fd_install.h"
#include "fd_return.h"
#include "fd_mutation.h"
#include "fd_capture.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "proc_file.h"
#include "sysv_ipc.h"
#include "task_wait.h"
#include "pending.h"
#include "elf_admission.h"
#include "task_mapping.h"
#include <linux/seccomp.h>
#include <sys/resource.h>

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

enum retry_kind {
    RETRY_TASK_ACTION=1u<<0, RETRY_MAPPING=1u<<1, RETRY_MEMORY=1u<<2,
    RETRY_BUFFER=1u<<3, RETRY_DESCRIPTOR=1u<<4, RETRY_RECEIVE=1u<<5,
    RETRY_OUTGOING=1u<<6, RETRY_PROC_DELIVERY=1u<<7, RETRY_RETURN=1u<<8,
    RETRY_FD=1u<<9, RETRY_EXPORT=1u<<10, RETRY_CONTROL=1u<<11,
    RETRY_PROC_IO=1u<<12, RETRY_PROC_LOOKUP=1u<<13, RETRY_READINESS=1u<<14, RETRY_CLOSE=1u<<15,
    RETRY_FILTER=1u<<16, RETRY_SIGNAL=1u<<17, RETRY_DEBUG_WAIT=1u<<18
};

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

#endif
