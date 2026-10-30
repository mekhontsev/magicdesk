#ifndef MD_PROC_FILE_H
#define MD_PROC_FILE_H
#include "guest_identity.h"
#include "fd_object.h"
#include "resource_order.h"
#include "control_file.h"
#include <stddef.h>
#include <stdint.h>
#include <sys/resource.h>

enum md_proc_file_kind { MD_PROC_FILE_UID_MAP, MD_PROC_FILE_GID_MAP, MD_PROC_FILE_SETGROUPS,
    MD_PROC_FILE_HOSTNAME, MD_PROC_FILE_DOMAINNAME, MD_PROC_FILE_OSTYPE,
    MD_PROC_FILE_OSRELEASE, MD_PROC_FILE_VERSION,
    MD_PROC_FILE_OVERFLOWUID, MD_PROC_FILE_OVERFLOWGID, MD_PROC_FILE_CAP_LAST,
    MD_PROC_FILE_NR_OPEN, MD_PROC_FILE_PID_MAX,
    MD_PROC_FILE_INOTIFY_INSTANCES, MD_PROC_FILE_INOTIFY_WATCHES, MD_PROC_FILE_INOTIFY_QUEUE,
    MD_PROC_FILE_NS_USER, MD_PROC_FILE_NS_MOUNT, MD_PROC_FILE_NS_UTS, MD_PROC_FILE_NS_IPC,
    MD_PROC_FILE_NS_PID, MD_PROC_FILE_NS_PID_CHILDREN, MD_PROC_FILE_NS_NETWORK, MD_PROC_FILE_NS_CGROUP,
    MD_PROC_FILE_NS_DIRECTORY, MD_PROC_FILE_PROCESS_DIRECTORY, MD_PROC_FILE_STATUS, MD_PROC_FILE_STAT, MD_PROC_FILE_LIMITS,
    MD_PROC_FILE_CGROUP, MD_PROC_FILE_CGROUPS,
    MD_PROC_FILE_CONTROL };
struct md_proc_file;
static inline int md_proc_file_task(enum md_proc_file_kind kind) {
    return (unsigned)kind<=MD_PROC_FILE_SETGROUPS || (kind>=MD_PROC_FILE_STATUS && kind<=MD_PROC_FILE_CGROUPS);
}
struct md_cgroup_member;
struct md_cgroup_namespace;
void md_proc_file_cgroup(struct md_proc_file *,struct md_cgroup_member *);
static inline int md_proc_file_cgroup_kind(enum md_proc_file_kind kind) {
    return kind==MD_PROC_FILE_CGROUP || kind==MD_PROC_FILE_CGROUPS;
}
int md_proc_file_control_access(const struct md_proc_file *,const struct md_identity *,unsigned);
struct md_proc_file *md_proc_file_open_control(const struct md_control_file_ops *,void *,
    const struct md_control_opener *,int flags);
/* Retained typed authority and optional per-open carrier for filesystem
 * publication. On failure neither ownership is transferred. */
struct md_fd_object *md_proc_file_control_object(const struct md_control_file_ops *,void *,
    const struct md_control_opener *,int flags,struct md_fs_anchor *,int *descriptor);
struct md_fs_anchor *md_proc_file_origin(const struct md_proc_file *);
int md_proc_file_control_stat(const struct md_proc_file *,struct stat *);
struct md_control_file md_proc_file_control(const struct md_proc_file *);
unsigned long md_proc_file_filesystem(const struct md_proc_file *);
int md_proc_file_mapping(const struct md_proc_file *,unsigned protection,unsigned flags);
int md_proc_file_fcntl(struct md_proc_file *,int command,unsigned long argument,long *result);
static inline int md_proc_file_process(enum md_proc_file_kind kind) {
    return kind==MD_PROC_FILE_STATUS || kind==MD_PROC_FILE_STAT || kind==MD_PROC_FILE_LIMITS;
}
static inline const char *md_proc_file_process_name(enum md_proc_file_kind kind) {
    return kind==MD_PROC_FILE_STAT ? "stat" : kind==MD_PROC_FILE_LIMITS ? "limits" : "status";
}
struct md_pid_namespace;
struct md_process_limits;
struct md_proc_file;
void md_proc_file_pid_view(struct md_proc_file *,struct md_pid_namespace *);
void md_proc_file_process_state(struct md_proc_file *,const struct md_identity *,struct md_pid_namespace *,pid_t tracer,
    const struct md_process_limits *);
static inline int md_proc_file_directory(enum md_proc_file_kind kind) {
    return kind==MD_PROC_FILE_NS_DIRECTORY || kind==MD_PROC_FILE_PROCESS_DIRECTORY;
}
struct md_uts_namespace;
static inline int md_proc_file_sysctl(enum md_proc_file_kind kind) {
    return kind>=MD_PROC_FILE_HOSTNAME && kind<=MD_PROC_FILE_INOTIFY_QUEUE;
}
struct md_proc_file;
struct md_namespace_reference;
static inline int md_proc_file_namespace(enum md_proc_file_kind kind) {
    return kind>=MD_PROC_FILE_NS_USER && kind<=MD_PROC_FILE_NS_CGROUP;
}
static inline const char *md_proc_file_namespace_name(enum md_proc_file_kind kind) {
    switch (kind) {
    case MD_PROC_FILE_NS_USER: return "user";
    case MD_PROC_FILE_NS_MOUNT: return "mnt";
    case MD_PROC_FILE_NS_UTS: return "uts";
    case MD_PROC_FILE_NS_IPC: return "ipc";
    case MD_PROC_FILE_NS_PID: case MD_PROC_FILE_NS_PID_CHILDREN: return "pid";
    case MD_PROC_FILE_NS_NETWORK: return "net";
    case MD_PROC_FILE_NS_CGROUP: return "cgroup";
    default: return NULL;
    }
}
struct md_proc_file *md_proc_file_open_namespace(const struct md_namespace_reference *,
    const struct md_identity *, int flags);
const struct md_namespace_reference *md_proc_file_namespace_reference(const struct md_proc_file *);
struct md_proc_file_node { uint64_t device, inode; int process; };
int md_proc_file_task_stat(int,const struct md_identity *,enum md_proc_file_kind,
    struct md_proc_file_node *,struct stat *);
int md_proc_file_task_access(const struct md_identity *,const struct stat *,unsigned,int effective);
void md_proc_file_place(struct md_proc_file *,struct md_fs_anchor *,const struct stat *);
/* Descriptor-host identity of the proc entry, independent of the carrier FD. */
void md_proc_file_node(struct md_proc_file *, struct md_proc_file_node);
struct md_proc_file_node md_proc_file_get_node(const struct md_proc_file *);
enum md_proc_file_kind md_proc_file_kind(const struct md_proc_file *);
int md_proc_file_filesystem_paths(const struct md_proc_file *);
struct md_proc_file *md_proc_file_reopen(const struct md_proc_file *,
    const struct md_control_opener *, int flags);
struct md_fd_object *md_proc_file_object(struct md_proc_file *);
struct md_proc_file *md_proc_file_from_object(struct md_fd_object *);
/* Map opens retain the target user namespace and opener credentials. Sysctl
 * operations select the caller's current namespace, including after unshare.
 * Path admission/DAC belong to the descriptor host.
 * The supervisor serializes IO; process text retains a per-open read snapshot. */
struct md_proc_file *md_proc_file_open(struct md_user_namespace *,
    enum md_proc_file_kind, const struct md_identity *opener, int flags);
struct md_proc_file *md_proc_file_retain(struct md_proc_file *);
void md_proc_file_release(struct md_proc_file *);
/* Inert kernel carrier; proc contents and operations are served by the owner. */
int md_proc_file_descriptor(const struct md_proc_file *);
int md_proc_file_flags(const struct md_proc_file *);
long md_proc_file_namespace_entries(uint64_t inode,int64_t *position,void *,size_t capacity);
long md_proc_file_read_at(struct md_proc_file *, const struct md_uts_namespace *, const struct md_cgroup_namespace *,
    void *, size_t capacity, int64_t offset);
long md_proc_file_write_at(struct md_proc_file *, const struct md_identity *writer,
    struct md_uts_namespace *,
    const void *, size_t size, int64_t offset, size_t page_size);

enum md_proc_file_operation { MD_PROC_FILE_READ, MD_PROC_FILE_WRITE, MD_PROC_FILE_SEEK,
    MD_PROC_FILE_GETDENTS, MD_PROC_FILE_COMMAND };
int md_proc_file_access(const struct md_proc_file *, enum md_proc_file_operation);
struct md_proc_file_io {
    struct md_resource_order turn;
    struct md_proc_file *file;
    enum md_proc_file_operation operation;
    int positional, whence, executed, offered;
    int64_t offset, next_position;
    size_t length, read_page, transferred;
    long result;
    struct md_control_actor actor;
    struct md_control_pending *pending;
};
/* Caller owns zero-initialized operation storage until finish/cancel. The turn
 * pins the open description, never a descriptor number or a task lifetime. */
int md_proc_file_io_begin(struct md_proc_file_io *, struct md_proc_file *,
    enum md_proc_file_operation, int positional, int64_t offset, int whence,
    size_t length, struct md_resource_wakeup *);
long md_proc_file_io_execute(struct md_proc_file_io *, const struct md_identity *,
    struct md_uts_namespace *,
    void *bytes, size_t capacity, size_t page_size);
int md_proc_file_io_offer(struct md_proc_file_io *);
int md_proc_file_io_more(const struct md_proc_file_io *);
/* A complete read-page acknowledgement advances its cursor but retains the
 * OFD turn. The next execute uses the remaining count and position. */
int md_proc_file_io_advance(struct md_proc_file_io *, long copied);
/* A failed/partial read copy preserves/advances only the confirmed prefix.
 * Writes and seeks have already committed and must never replay. */
int md_proc_file_io_finish(struct md_proc_file_io *, long copied);
/* After an offered read, unknown delivery retires the entire offered prefix. */
int md_proc_file_io_cancel(struct md_proc_file_io *);
#endif
