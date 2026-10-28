#ifndef MD_FS_BUFFER_SERVICE_H
#define MD_FS_BUFFER_SERVICE_H
#include "interception_buffers.h"
#include "resource_order.h"
#include "file_open.h"
#include "control_file.h"
#include <sys/types.h>
struct md_fs_worker;
struct md_task_registry;
struct md_guest_domain;
struct md_fs_buffer_service;
void md_fs_buffer_admission(struct md_fs_buffer_service *, int (*)(pid_t));
/* Capture on the supervisor thread before admission. Filter installation must
 * drain admitted operations before committing a new transport restriction. */
void md_fs_buffer_restricted(struct md_fs_buffer_service *, int (*)(pid_t));
struct md_fs_buffer_request;
struct md_fs_anchor;
struct md_fd_object;
struct md_fs_task_capture;
struct md_fs_buffer_open {
    int fd; unsigned flags, task_path; struct md_fs_anchor *origin;
    unsigned source_parent; /* Retained named-source directory FD + 1, or zero. */
    struct md_fd_object *object;
    struct md_open_completion completion;
};
/* Transfer the completed result lease to its receiving task; no guest FD yet. */
int md_fs_buffer_open_take(struct md_fs_buffer_request *, struct md_fs_buffer_open *);
void md_fs_buffer_open_release(struct md_fs_buffer_open *);
struct md_fd_object *md_fs_buffer_open_object(const struct md_fs_buffer_open *);
/* A successful watch/directory read retains its request through copy
 * acknowledgement. Only directory reads retain their exact OFD turn;
 * no filesystem or file-table turn is held across copying. */
int md_fs_buffer_ack_pending(const struct md_fs_buffer_request *);
int md_fs_buffer_ack(struct md_fs_buffer_request *, int copied);
/* Captured context and table turn survive the task-local, export-only send.
 * The install function returns an ADDFD_SEND result, not a generic FD number. */
int md_fs_buffer_export_pending(const struct md_fs_buffer_request *);
int md_fs_buffer_export_install(struct md_fs_buffer_request *, int listener, uint64_t notification);
int md_fs_buffer_export_sent(struct md_fs_buffer_request *, int error);
int md_fs_buffer_channel_observed(struct md_fs_buffer_request *);
/* Unknown cwd is captured by a private-table task helper. The request retains
 * its FS/table turns and buffer until that helper has been reaped. Finish
 * always consumes the owned descriptor, including after cancellation. */
int md_fs_buffer_capture_pending(const struct md_fs_buffer_request *);
int md_fs_buffer_capture_start(struct md_fs_buffer_request *);
int md_fs_buffer_capture_target(const struct md_fs_buffer_request *, struct md_fs_task_capture *);
int md_fs_buffer_capture_finish(struct md_fs_buffer_request *, int descriptor, int error);
struct md_task_wait;
struct md_fs_buffer_service *md_fs_buffer_service_new(struct md_fs_worker *, struct md_task_registry *,
        struct md_resource_wakeup *, struct md_task_wait *, void (*complete)(struct md_fs_buffer_request *, pid_t, int));
/* Configure before accepting work. The descriptor host owns the common IO
 * implementation; filesystem providers only supply control-file semantics. */
/* The factory may transfer a per-open poll/mapping carrier through descriptor.
 * It replaces the namespace placeholder only after path/origin validation. */
typedef struct md_fd_object *(*md_control_factory)(const struct md_control_file_ops *,void *,const struct md_identity *,int,struct md_fs_anchor *,int *descriptor);
void md_fs_buffer_control_factory(struct md_fs_buffer_service *,md_control_factory);
/* 1 selects a borrowed provider, 0 is an ordinary FD, negative denies a typed
 * descriptor that cannot serve this operation. Never fall back to its carrier. */
typedef int (*md_control_query)(struct md_fd_object *,unsigned operation,struct md_control_file *);
void md_fs_buffer_control_query(struct md_fs_buffer_service *,md_control_query);
/* Resolve a confirmed proc boundary under the supervisor's task ownership.
 * Returns 1 with an owned typed object, 0 for a native path, or an error. */
typedef int (*md_control_capture)(pid_t,const char *,struct md_fd_object **);
void md_fs_buffer_control_capture(struct md_fs_buffer_service *,md_control_capture);
struct md_program;
/* Supervisor-thread capture of a namespace-resolved proc executable. On
 * success, replaces the proc spelling with an object identity or owned FD. */
typedef int (*md_exec_capture)(pid_t,struct md_program *);
void md_fs_buffer_exec_capture(struct md_fs_buffer_service *,md_exec_capture);
struct md_task_snapshot;
/* Capture a live task's root/cwd after process-access authorization. The
 * worker retains its logical placement, never an Android path as guest text. */
typedef int (*md_proc_path_capture)(pid_t,pid_t,unsigned,struct md_task_snapshot *);
void md_fs_buffer_path_capture(struct md_fs_buffer_service *,md_proc_path_capture);
/* Cancel requests and reap owned children, then stop/join the worker before
 * destroy. Records retain buffers independently of a dead/replaced task. */
void md_fs_buffer_service_destroy(struct md_fs_buffer_service *);
int md_fs_buffer_begin(struct md_fs_buffer_service *, pid_t, struct md_guest_domain *, unsigned operation,
        const struct md_interception_buffer *, struct md_fs_buffer_request **);
/* Capture retained origins under the same FS/table turns, without running task
 * helpers or exporting descriptors. NEEDS_TASK is unexecuted, never an errno. */
enum { MD_FS_BUFFER_NEEDS_TASK=1 };
int md_fs_buffer_invoke(struct md_fs_buffer_service *, pid_t, struct md_guest_domain *, unsigned operation,
        const struct md_interception_buffer *, struct md_fs_buffer_request **);
/* Stopped-task status mutations use owned operands and a scalar receipt, not
 * guest-mapped output or FD delivery. The caller stays stopped until completion.
 * The same capture, worker, cancellation and namespace owners execute them. */
struct md_fs_request;
struct md_fs_result;
int md_fs_buffer_invoke_status(struct md_fs_buffer_service *,pid_t,struct md_guest_domain *,
    const struct md_fs_request *,struct md_fs_buffer_request **);
/* Borrowed during completion only; NULL for mapped-buffer requests. */
const struct md_fs_result *md_fs_buffer_status_result(const struct md_fs_buffer_request *);
/* Inspect an already captured descriptor without a guest buffer or another
 * file-table turn. Accepted submission owns descriptor; failure leaves it with
 * the caller. The ordinary receipt/cancellation owner retains the task context. */
int md_fs_buffer_stat_begin(struct md_fs_buffer_service *,pid_t,struct md_guest_domain *,
    int descriptor,struct md_fs_buffer_request **);
/* Copies owned bytes, then creates a read-only snapshot on the worker. No guest
 * buffer/table turn is retained; completion uses open_take and normal cancellation. */
int md_fs_buffer_snapshot_copy(struct md_fs_buffer_service *,pid_t,struct md_guest_domain *,
    unsigned flags,const void *,size_t,struct md_fs_buffer_request **);
/* Continue delivery of an already executed open. On accepted submission, moves
 * opened into the request; no path lookup or mutation is repeated. */
int md_fs_buffer_adopt(struct md_fs_buffer_service *,pid_t,struct md_guest_domain *,unsigned operation,unsigned flags,
    const struct md_interception_buffer *,struct md_fs_result *,struct md_fs_buffer_open *,struct md_fs_buffer_request **);
/* Inspect one captured rights vector using a shared custody reference. No
 * application table/FS turn or per-descriptor duplication is needed. */
struct md_received_fds;
int md_fs_buffer_inspect_begin(struct md_fs_buffer_service *, pid_t, struct md_guest_domain *,
        const struct md_interception_buffer *, struct md_received_fds *, struct md_fs_buffer_request **);
/* In the completion callback only: 1 means a successfully inspected message
 * (possibly native, with NULL expanded custody); 0 means another operation. */
int md_fs_buffer_inspect_take(struct md_fs_buffer_request *, struct md_received_fds **);
/* Prepared executable descriptions stay outside the guest table until exec. */
int md_fs_buffer_exec_begin(struct md_fs_buffer_service *, pid_t, struct md_guest_domain *,
        const struct md_interception_buffer *, struct md_fs_buffer_request **, int traced);
struct md_exec_image;
int md_fs_buffer_exec_take(struct md_fs_buffer_request *, int descriptors[3], unsigned *mount_flags, int *anonymous,
    struct md_fs_anchor **origin,struct md_fs_anchor **execfd_origin,struct md_exec_image **);
struct md_landlock_policy *md_fs_buffer_landlock_take(struct md_fs_buffer_request *);
int md_fs_buffer_snapshot_begin(struct md_fs_buffer_service *, pid_t, struct md_guest_domain *,
        const struct md_interception_buffer *, unsigned flags, struct md_fs_buffer_request **);
int md_fs_buffer_snapshot_pending(const struct md_fs_buffer_request *);
int md_fs_buffer_snapshot_advance(struct md_fs_buffer_request *, int finish);
/* A descriptor-based cwd change retains both resource turns until adapter
 * acknowledgement, not ambiguous terminal registers. Only its original
 * descriptor may enter this continuation. A pre-entry denial can also finish. */
int md_fs_buffer_task_pending(const struct md_fs_buffer_request *);
int md_fs_buffer_task_start(struct md_fs_buffer_request *);
int md_fs_buffer_task_finish(struct md_fs_buffer_request **, long result);
int md_fs_buffer_progress(struct md_fs_buffer_service *);
/* Deferred opens are owned children, reaped by the supervisor's SIGCHLD loop.
 * A cancelled request retains its child record until the exact terminal event. */
int md_fs_buffer_children(const struct md_fs_buffer_service *);
int md_fs_buffer_child(struct md_fs_buffer_service *, pid_t, int status);
/* The completion callback announces an isolated blocking open twice: waiting,
 * then ready. The owner resumes publication only at its stopped-task boundary. */
int md_fs_buffer_open_waiting(const struct md_fs_buffer_request *);
int md_fs_buffer_open_ready(const struct md_fs_buffer_request *);
int md_fs_buffer_open_interrupt(struct md_fs_buffer_request *);
int md_fs_buffer_open_interrupted(const struct md_fs_buffer_request *);
int md_fs_buffer_open_resume(struct md_fs_buffer_request *);
void md_fs_buffer_cancel(struct md_fs_buffer_request **);
/* Supervisor-thread diagnostics at a failed wait boundary, never polling. */
void md_fs_buffer_describe(struct md_fs_buffer_service *, int fd);
#endif
