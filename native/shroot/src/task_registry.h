#ifndef MD_TASK_REGISTRY_H
#define MD_TASK_REGISTRY_H
#include "guest_identity.h"
#include "guest_domain.h"
#include "mount_namespace.h"
#include "fd_origins.h"
#include "process_access.h"
#include <signal.h>

struct md_task_registry;
struct md_kernel_policy;
struct md_signal_origin;
/* Retained launch-global policy, independent of per-task namespace copies. */
struct md_kernel_policy *md_task_registry_kernel_policy(struct md_task_registry *);
struct md_task_resources;
struct md_task_snapshot_state;
struct md_task_snapshot {
    pid_t tid, tgid;
    /* Launch-local task incarnation, stable across resource/credential updates. */
    uint64_t generation;
    struct md_identity identity;
    struct md_process_access *access;
    struct md_guest_domain *fs;
    struct md_fs_location root;
    struct md_mount_namespace *mounts;
    struct md_fd_origins *files;
    struct md_fs_anchor *cwd;
    struct md_pid_identity *pid;
    struct md_pid_identity *pid_group;
    struct md_cgroup_member *cgroup;
    struct md_cgroup_namespace *cgroup_namespace;
    struct md_network_namespace *network;
    struct md_ipc_namespace *ipc;
    struct md_uts_namespace *uts;
    struct md_landlock_policy *landlock;
    /* Registry-owned immutable resource generation; cwd/root stay per-read. */
    struct md_task_snapshot_state *owner;
};
struct md_task_registry *md_task_registry_create(void);
void md_task_registry_destroy(struct md_task_registry *);
int md_task_registry_publish(struct md_task_registry *, pid_t tid, pid_t tgid,
        const struct md_identity *, const struct md_task_resources *);
void md_task_registry_forget(struct md_task_registry *, pid_t tid);
/* The supervisor is the sole writer. Exit drops live resources but preserves
 * proc observations in the existing entry until wait/reaping. No exit allocation. */
void md_task_registry_retire(struct md_task_registry *,pid_t,pid_t parent,const struct md_identity *);
struct md_task_retired_view {
    const struct md_identity *identity;
    struct md_pid_identity *pid;
    struct md_cgroup_member *cgroup;
    struct md_process_member *process;
    pid_t parent;
};
/* Borrowed on the writer thread, only until its next registry mutation. */
struct md_task_retired_view md_task_registry_retired(const struct md_task_registry *,pid_t);
/* Writer-only enumeration, without allocation or live-resource pins. Callback
 * borrows each unreaped process and must not mutate the registry. */
void md_task_registry_retired_processes(const struct md_task_registry *,
    void (*visit)(void *,pid_t,struct md_task_retired_view),void *);
int md_task_registry_has_retired(const struct md_task_registry *);
void md_task_registry_reap(struct md_task_registry *,pid_t);
/* A confirmed native reap may precede observation of a coalesced SIGCHLD.
 * Retain only sender values, not namespaces, credentials or resource charges.
 * Allocation failure keeps the existing retired observation for later cleanup. */
void md_task_registry_reap_deferred(struct md_task_registry *,pid_t);
int md_task_registry_signal_origin(const struct md_task_registry *,const siginfo_t *,struct md_signal_origin *);
void md_task_registry_collect(struct md_task_registry *,int (*gone)(pid_t));
/* Quota pressure may collect kernel-reaped charges while a wait still retains
 * the identity for translation. This does not retire that observation. */
void md_task_registry_collect_limits(struct md_task_registry *,int (*gone)(pid_t));
/* peer==0 is a kernel-notification/internal caller. RPC callers must own the
 * TID's thread group; the supervisor may request on behalf of its tracee. */
/* A successful read retains one immutable resource generation and a fresh cwd
 * anchor until release. Resource contents, including file-table entries, stay live.
 * No registry lock is held while the filesystem resolves the request. */
int md_task_registry_read(struct md_task_registry *, pid_t tid, pid_t peer, struct md_task_snapshot *);
/* As read, but distinguishes a retired guest (-ESTALE) from an external PID
 * (-ESRCH). A native fast path must not expose the host state of a retired guest. */
int md_task_registry_probe(struct md_task_registry *,pid_t tid,pid_t peer,struct md_task_snapshot *);
/* Same live-task admission, retaining only the file table. The caller releases
 * it with md_fd_origins_unpin; namespace/identity state is not captured. */
int md_task_registry_files(struct md_task_registry *,pid_t tid,pid_t peer,struct md_fd_origins **);
/* Credential-only observation, including unreaped tasks. No filesystem/file-table
 * pins or allocations; release the returned identity with md_identity_release. */
struct md_task_credentials {
    pid_t group;
    int retired;
    uint64_t generation;
    struct md_identity identity;
    struct md_process_access *access;
};
void md_task_credentials_release(struct md_task_credentials *);
int md_task_registry_credentials(struct md_task_registry *,pid_t,struct md_task_credentials *);
/* Live application threads only; private helpers never enter the registry.
 * Zero means the group has no live registered threads. No task-table scan. */
unsigned md_task_registry_thread_count(struct md_task_registry *,pid_t group);
/* Proc opens need only identity, PID and cgroup, including unreaped tasks.
 * Release with md_task_snapshot_release; no live filesystem resources pinned. */
int md_task_registry_proc(struct md_task_registry *,pid_t,struct md_task_snapshot *);
/* Retains only the proc handle with this generation, never the observed mask. */
long md_task_snapshot_umask(const struct md_task_snapshot *);
/* Consumes an exact-task status descriptor captured by the task-affine owner.
 * The live mask is still read for each creation, never cached as a value. */
int md_task_snapshot_status_install(const struct md_task_snapshot *,int descriptor);
void md_task_snapshot_release(struct md_task_snapshot *);
#endif
