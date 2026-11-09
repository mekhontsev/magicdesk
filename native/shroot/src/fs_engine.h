#ifndef MD_FS_ENGINE_H
#define MD_FS_ENGINE_H
#include "fs_operation.h"
#include "inode_store.h"
#include "mount_namespace.h"
#include "task_registry.h"
struct md_image_catalogue;
struct md_fd_exchanges;
struct md_ofd_orders;
struct md_fs_storage_cache;
struct md_binfmt_registry;
struct md_fs_statistics {
    struct md_cost operation[MD_FS_LAST + 1];
    struct md_cost mount_reconcile, mount_attach;
};
void md_fs_statistics_report(int fd, const struct md_fs_statistics *);
/* The worker borrows its store/images and retains an initial mount view.
 * Authenticated task requests borrow their own retained view for dispatch. */
struct md_filesystem {
    struct md_inode_store *store;
    struct md_fs_storage_cache *storage;
    struct md_inode_store *selected_store;
    struct md_image_catalogue *images;
    struct md_binfmt_registry *binfmt;
    struct md_mount_namespace *mounts;
    struct md_view_object *native_root;
    struct md_view_mount *inode_mount;
    struct md_task_registry *tasks;
    struct md_fd_exchanges *exchanges;
    struct md_ofd_orders *cursors;
    struct md_ipc_credentials *ipc;
    struct md_shm_bridge *shm_bridge;
    struct md_provider_tree *devices; /* borrowed launch-owned /dev projection */
    struct md_device_events *device_events; /* borrowed launch-owned event publisher */
    struct md_pty_events *pty_events; /* launch-owned native terminal observation */
    struct md_control_file render_device; /* launch-admitted optional KGSL discovery */
    struct md_resource_wakeup *wakeup; /* borrowed filesystem event-loop owner */
    const char *ipc_store;
    struct md_fs_statistics *statistics;
    const struct md_task_snapshot *actor;
};
/* Worker-only lazy authority admission, shared by guest IPC and private peer
 * setup. No connection/database ownership crosses into the supervisor. */
int md_fs_ipc_authority(struct md_filesystem *, struct md_ipc_credentials **);
void md_fs_render_prepare(struct md_filesystem *);
/* Request-local authentication, independent of transport. Keep this context
 * until delivery/cancellation finishes; the borrowed filesystem outlives it.
 * No registry lookup is repeated at execution or publication. */
struct md_fs_context {
    struct md_filesystem *filesystem;
    struct md_task_snapshot task;
    int actor, peer, authenticated, has_identity;
};
int md_fs_context_begin(struct md_filesystem *, int actor, int peer, struct md_fs_context *);
void md_fs_context_end(struct md_fs_context *);
/* Optional provider-owned operation gate. NULL requires closing any previous
 * window; mount/provider transitions must not inherit another store's lock. */
struct md_inode_store *md_fs_context_operation_window(const struct md_fs_context *,const struct md_fs_request *,int native_plan);
void md_fs_context_execute(struct md_fs_context *,
        const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
/* Reinstates the admitted task scope, then advances the provider's exact stage.
 * The caller destroys the old continuation unless it is returned again. */
void md_fs_context_resume(struct md_fs_context *,struct md_fs_continuation *,
    struct md_fs_result *,const struct md_fs_output *);
/* Worker-created snapshots use the same retained path provenance as opens.
 * A failed capture closes result.fd; the returned anchor is caller-owned. */
void md_fs_context_capture_result(struct md_fs_context *, struct md_fs_result *, struct md_fs_anchor **);
/* One namespace owner, independent of transport. Input descriptors are borrowed;
 * a successful open transfers result.fd. No guest pointers enter this API.
 * Synchronous internal callers capture and release a context within this call. */
void md_fs_execute(struct md_filesystem *,
        const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
void md_fs_statfs_result(int fd, unsigned policy, const struct md_inode_lifetime *,
        struct md_fs_result *, const struct md_fs_output *);
uint64_t md_fs_statfs_policy(unsigned policy);
/* Compound operations reuse the already selected worker identity and mount view. */
void md_fs_view_execute(struct md_filesystem *, const struct md_fs_request *,
        struct md_fs_result *, const struct md_fs_output *);
void md_fs_xattr(struct md_filesystem *, const struct md_fs_request *,
        struct md_fs_result *, const struct md_fs_output *);
#endif
