#ifndef MD_TASK_RESOURCES_H
#define MD_TASK_RESOURCES_H
#include "guest_domain.h"
#include "mount_namespace.h"
#include "fd_origins.h"
#include "sysv_ipc.h"
#include "uts_namespace.h"
#include "ipc_namespace.h"
#include "guest_identity.h"
#include "pid_namespace.h"
#include "network_namespace.h"
#include "cgroup.h"
#include "landlock_policy.h"
#include "process_limits.h"

/* Linux owns the real FD table, cwd/umask and open descriptions. Optional path
 * annotations share their lifetimes without retaining data descriptions. */
struct md_task_resources {
    struct md_process_member *process;
    struct md_guest_domain *fs;
    struct md_mount_namespace *mounts;
    struct md_fd_origins *files;
    struct md_ipc_undo *sem_undo;
    struct md_uts_namespace *uts;
    struct md_ipc_namespace *ipc;
    struct md_pid_identity *pid;
    struct md_pid_identity *pid_group;
    struct md_pid_namespace *pid_children;
    struct md_network_namespace *network;
    struct md_cgroup_member *cgroup;
    struct md_cgroup_namespace *cgroup_namespace;
    struct md_landlock_policy *landlock;
};
struct md_resource_change {
    struct md_task_resources next;
    int copy_mounts;
    int check_root, identity_pending, pid_children_pending;
    struct md_identity identity;
    unsigned long namespaces;
    struct md_mount_namespace *join_mounts;
};
struct md_namespace_reference;
/* Called under the caller's file-table and FS-context turns. Thread-group
 * membership is checked by the supervisor before USER admission. */
int md_resources_setns_prepare(const struct md_task_resources *, const struct md_identity *,
    const struct md_namespace_reference *, unsigned type, struct md_resource_change *);
/* The caller authenticates the exact live pidfd target before taking this
 * resource snapshot. All selected namespaces publish through one change. */
int md_resources_setns_process_prepare(const struct md_task_resources *, const struct md_identity *,
    const struct md_task_resources *, struct md_user_namespace *, unsigned types, struct md_resource_change *);

int md_resources_init(struct md_task_resources *, struct md_mount_namespace *);
int md_resources_fork(const struct md_task_resources *, unsigned long flags,
        struct md_task_resources *);
int md_resources_release(struct md_task_resources *);
/* Confirmed kernel exec only, after any nonleader task-resource transfer. */
int md_resources_exec(struct md_task_resources *);
int md_resources_exec_closed(struct md_task_resources *,struct md_fd_closed *);
/* Terminal kernel stop: relinquish this task's table ownership. The last
 * owner retires its aliases before the task can become waitable. */
void md_resources_exit_files(struct md_task_resources *,struct md_fd_closed *);
/* Prepare before letting the kernel change ownership. Commit only on its
 * successful syscall-exit stop; failure/exit discards the unused candidate. */
int md_resources_unshare_prepare(const struct md_task_resources *, unsigned long flags,
    const struct md_identity *, struct md_resource_change *);
int md_resources_unshare_finish(struct md_task_resources *,
        struct md_identity *, struct md_resource_change *, long result);
int md_resources_clone_supported(unsigned long flags);
int md_resources_clone_cgroup_prepare(const struct md_task_resources *,unsigned long flags,struct md_fs_anchor *,
    const struct md_identity *,struct md_resource_change *);
/* Namespace flags whose effects belong to retained guest resources, not the
 * host clone/unshare call. Admission and native flag removal share this set. */
unsigned long md_resources_namespace_flags(unsigned long flags);
#endif
