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
#include "supervisor_request.h"
#include "guest_execution.h"
#include <linux/seccomp.h>
#include <sys/resource.h>

enum phase { IDLE, ALLOCATING, DISPATCHING, EXECUTING, OBSERVING, NATIVE_WAIT, TRANSFERRING,
    EXPORT_CANCEL, EXPORTING, EXPORT_ENTRY, EXPORT_NOTIFY, EXPORT_RETURN,
    DELEGATE_CANCEL, PROC_CANCEL, PROC_OPENING,
    WATCH_CANCEL, WATCH_ENTRY, WATCH_READ, ADAPTER_NATIVE, IPC_WAIT_ENTRY, IPC_WAITING,
    SOCKET_WAIT_ENTRY, SOCKET_WAITING, CALL_RESTART_ENTRY, CALL_RESTART_EXIT,
    FILE_WAIT_ENTRY, FILE_WAITING, FILE_INTERRUPTING, CONTROL_WAIT_ENTRY, CONTROL_WAITING, CLOSE_WAIT_ENTRY, CLOSE_WAITING,
    WAIT_CANCEL_EXIT, EPOLL_PROBE, EPOLL_CALL, EPOLL_WAIT,
    DEBUG_WAIT_BUFFER, DEBUG_WAIT_ENTRY, DEBUG_WAIT_EXIT, DEBUG_PARK_ENTRY, DEBUG_PARK_EXIT,
    MEMORY_WAIT, RESULT_REPLAY_ENTRY, RESULT_REPLAY_EXIT, PROCESS_OBSERVING,
    PROCESS_INIT_ENTRY, PROCESS_INIT_EXIT, PROC_LOOKUP,
    CLONE_ENTRY, CLONE_ADMISSION, FILTER_CAPTURE, SIGNAL_CONTROL, SIGNAL_ENTRY, SIGNAL_NATIVE_EXIT,
    GUEST_NOTIFICATION, FILTER_MAPPING };

enum memory_action { MEMORY_NONE, MEMORY_KERNEL, MEMORY_DETACH, MEMORY_FORK };

enum retry_kind {
    RETRY_TASK_ACTION=1u<<0, RETRY_MAPPING=1u<<1, RETRY_MEMORY=1u<<2,
    RETRY_BUFFER=1u<<3, RETRY_DESCRIPTOR=1u<<4, RETRY_RECEIVE=1u<<5,
    RETRY_OUTGOING=1u<<6, RETRY_PROC_DELIVERY=1u<<7, RETRY_RETURN=1u<<8,
    RETRY_FD=1u<<9, RETRY_EXPORT=1u<<10, RETRY_CONTROL=1u<<11,
    RETRY_PROC_IO=1u<<12, RETRY_PROC_LOOKUP=1u<<13, RETRY_READINESS=1u<<14, RETRY_CLOSE=1u<<15,
    RETRY_FILTER=1u<<16, RETRY_SIGNAL=1u<<17, RETRY_DEBUG_WAIT=1u<<18, RETRY_TRANSFER=1u<<19
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
    size_t copied, output_size;
    uintptr_t output;
    char path[4096];
    struct stat value;
};

struct retry_owner {
    struct md_pending_node node;
    struct thread *thread;
    unsigned mask;
};

struct supervisor_robust_state {
    uintptr_t head;
    uintptr_t registering_head;
    uint32_t tid;
    int registering;
    int terminal;
    int replay;
    int status;
    struct rusage usage;
    struct user_pt_regs registers;
    enum md_task_call_boundary boundary;
    struct thread *subject;
};

struct supervisor_memory_op_state {
    struct md_pending_node continuation;
    struct md_interception_memory *remote;
    struct md_interception_memory *exposure;
    int resume;
    int signal;
    struct seccomp_notif *notification;
    struct buffer_mapping *buffer_mapping;
    struct mapping_owner *mapping;
    struct memory_capture *capture;
    struct svc_pool_mapping *svc_pool;
    int backing_pending;
    struct md_resource_order order;
    enum memory_action action;
    int arguments_only;
    struct md_fd_order files;
    struct md_mapping_change change;
    int access_pending;
};

struct supervisor_entry_state {
    struct md_uts_subscription uts;
    int fence_waiting;
    int fence_held;
    int fence_replay;
    int fence_status;
    int fence_result;
    struct rusage fence_usage;
    struct md_interception_buffer buffer;
    const struct md_fd_selector *selectors;
    uint64_t selector_revision;
    unsigned int route_flags;
    struct md_entry_channel *control;
    struct md_entry_channel *execution;
    int admission;
    int kernel_context;
    struct {
        enum md_execution_context reason;
        void (*resume)(struct thread *);
    } stop;
    struct {
        struct thread *next, **previous;
        int64_t deadline;
        void (*complete)(struct thread *, long);
    } wait;
    void (*effect)(struct thread *, long);
    int effect_cancelled;
    int waiting;
    int error;
    int blocked;
    int registration_revoked;
};

struct supervisor_fs_state {
    uint64_t admission_id;
    int admission_pending;
    unsigned int admission_yields;
    struct md_interception_buffer buffer;
    struct md_fs_buffer_request *call;
    enum { DIRECT_NONE, DIRECT_STATUS, DIRECT_OPEN, DIRECT_PREPARE_RESULT, DIRECT_ADMIT, DIRECT_RESULT } direct;
    unsigned int direct_flags;
    unsigned int direct_mode;
    struct md_fs_buffer_open open;
    int result_replay;
    struct user_pt_regs registers;
    struct supervisor_control_request request;
    struct md_fd_order descriptor_order;
    unsigned int descriptor_operation;
    unsigned long descriptor_argument;
    struct md_fd_return returned;
    int return_waiting;
    int rights_delivered;
    int waiting;
};

struct supervisor_signals_state {
    int retained;
    struct md_signal_delivery delivery;
    struct md_signal_origin origin;
    int shielded;
    int return_pending;
    struct md_signal_actions *actions;
    struct signal_control *control;
    int host;
    int host_blocked;
    int native_query;
    uint64_t handler_mask;
    int handler_mask_known;
    struct md_signal_wait_view wait_view;
    uint64_t query_mask;
    int query_active;
    struct md_signal_pending *pending;
};

struct supervisor_descriptors_state {
    struct md_fd_capture pidfd_capture;
    struct md_fd_selector *selector;
    struct md_fd_selector *fork_selector;
    struct md_fd_selector *deferred_selector;
    pid_t selector_exit;
    struct md_mount_copy_request *mount_copy;
    struct md_fd_order order;
    struct md_fs_order fs_order;
    struct md_fs_anchor *origin;
    struct md_fd_mutation mutation;
    struct md_fd_origin_update pair_updates[2];
    struct user_pt_regs registers;
    struct seccomp_data call;
    struct supervisor_control_request request;
    int running;
    int cwd;
    int capture;
    int task;
    unsigned int job;
    int duplicate_attempted;
    int duplicate_result;
    struct md_fd_exchange *exchange;
    struct md_resource_order policy_order;
};

struct supervisor_io_state {
    struct outgoing_owner *outgoing;
    struct receive_owner *receive;
    int socket_waking;
    int socket_sigpipe;
    struct md_ipc_pending *pending;
    struct md_ipc_transport transport;
    int cancel;
    int waking;
    uint64_t event;
};

struct supervisor_proc_state {
    struct md_fd_install delivery;
    struct md_fs_buffer_request *snapshot;
    struct seccomp_notif snapshot_notification;
    int image_fd;
    struct md_proc_file *file;
    int splice_write;
    struct md_proc_file_io io;
    struct md_fd_order lookup;
    int waiting;
    int direct;
};

struct supervisor_control_state {
    struct md_fd_capture capture;
    struct md_received_fds *input;
    struct md_fd_object *origin;
    int input_number;
    struct md_control_pending *pending;
    struct md_control_pending *publication;
    size_t size;
    int done;
    long result;
    int mapping_fd;
};

struct supervisor_close_state {
    struct md_fd_closed closed;
    struct md_control_pending *pending;
    void (*complete)(struct thread *,long);
    struct user_pt_regs registers;
    enum phase phase;
    uint64_t mask;
    int mask_shielded;
    long result;
    int done;
    int errors;
    int terminal_pending;
    unsigned int event;
    unsigned long message;
};

struct supervisor_execution_state {
    enum phase wait_resume;
    int wait_error;
    int prepared;
    int64_t epoll_started;
    uint64_t stack;
    uint64_t mask;
    struct user_pt_regs registers;
    struct seccomp_data request;
    enum call_restart restart;
};

struct supervisor_seccomp_state {
    int length;
    int application_filter;
    struct md_seccomp_policy *policy;
    struct md_seccomp_policy *admission_policy;
    int admission_policy_valid;
    int admission_policy_pending;
    int admission_application_filter;
    struct filter_install *install;
    struct guest_notification *notification;
    int notification_continued;
    int preflight;
};

struct supervisor_process_op_state {
    unsigned long cloning;
    int clone_prepared;
    struct md_fs_anchor *clone_cgroup;
    pid_t vfork_child;
    int vfork_borrowed;
    int filter;
    int preflight;
    struct md_pid_identity *pid_reservation;
    struct md_process_member *reservation;
    long result;
    struct { uintptr_t address; pid_t value; } stores[2];
    unsigned int store_count;
    uintptr_t parent_address;
    struct thread *birth_parent;
    struct thread *birth_child;
    int birth_waiting;
    int birth_signal;
    int init_pending;
    int wait_result;
    int wait_recheck;
    int inspection_denied;
    int info_code;
    pid_t info_native;
};

struct supervisor_image_state {
    struct md_exec_identity pending_identity;
    int images[3];
    int backings[2];
    struct md_process_executable *executable;
    struct md_process_executable *prepared_executable;
    struct md_fs_anchor *executable_origin;
    struct md_fs_anchor *prepared_executable_origin;
    int images_ready;
    unsigned int mount_flags;
    struct md_exec_image *setid;
    int mapped;
    int backings_ready;
    uintptr_t process_image;
};

struct supervisor_debug_state {
    int step_call;
    int birth;
    pid_t exec_old;
    int call_active;
    int adapter;
    unsigned long options;
    uint64_t wait_mask;
    struct debug_wait_output *wait_output;
    int wait_waking;
    int replay_entry;
};

struct supervisor_identity_transfer {
    uint32_t *groups;
    unsigned count, copied;
};

struct thread {
    struct thread *next;
    struct thread *indexed;
    struct retry_owner retries[2];
    struct md_interception_memory *memory;
    struct retained_memory *memory_actor;
    struct md_svc_machine_thread machine;
    struct md_broker_completion *broker_completion;
    struct { int held, status, has_info; struct rusage usage; siginfo_t info; } broker_event;
    struct metadata_restart *metadata_restart;
    int metadata_signal_restored;
    int metadata_signal_requeued;
    struct thread *broker_next;
    struct capture_owner *capture;
    struct probe_owner *probe;
    struct task_action *action;
    struct readiness_owner *readiness;
    pid_t pid;
    pid_t tgid;
    pid_t parent;
    int ready;
    int born;
    int initial_stop;
    int resume_lost;
    int listening;
    int terminal_stop;
    enum phase phase;
    unsigned int calls;
    char endpoint[108];
    struct metadata_operation metadata;
    struct md_task_resources resources;
    struct md_resource_change resource_change;
    struct md_identity identity;
    struct md_fs_anchor *execfd_origin;
    int entered;
    int kernel_stopped;
    int kernel_resume;
    int interrupt_pending;
    int trace_options_dirty;
    int freezer_resume;
    int freezer_signal;
    int terminal_observation;
    struct rusage usage;
    int watch_wait;
    struct md_shm_space *shm_space;
    struct md_task_wait_member wait_member;

    struct supervisor_robust_state robust;
    struct supervisor_memory_op_state memory_op;
    struct supervisor_entry_state entry;
    struct supervisor_fs_state fs;
    struct supervisor_signals_state signals;
    struct supervisor_descriptors_state descriptors;
    struct supervisor_io_state io;
    struct supervisor_proc_state proc;
    struct supervisor_control_state control;
    struct supervisor_close_state close;
    struct supervisor_execution_state execution;
    struct supervisor_transfer *transfer;
    struct supervisor_seccomp_state seccomp;
    struct supervisor_process_op_state process_op;
    struct supervisor_image_state image;
    struct supervisor_debug_state debug;
    struct supervisor_identity_transfer identity_transfer;
};

static inline void execution_retain(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs) {
    t->execution.request=*call;
    if (regs) t->execution.registers=*regs;
}

/* Only a stopped-task ingress decodes a machine frame into a request. */
static inline void execution_capture(struct thread *t,const struct user_pt_regs *regs) {
    struct seccomp_data call=syscall_description(regs);
    execution_retain(t,&call,regs);
}

static inline void buffer_capture(struct thread *t,const struct user_pt_regs *regs) {
    t->fs.registers=*regs;
    t->fs.request=control_description(regs);
}

static inline void descriptor_retain(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs) {
    t->descriptors.call=*call;
    if (regs) t->descriptors.registers=*regs;
}

#endif
