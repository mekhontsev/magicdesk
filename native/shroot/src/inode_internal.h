#ifndef MD_INODE_INTERNAL_H
#define MD_INODE_INTERNAL_H
#include "inode_store.h"
#include <limits.h>
#include <sqlite3.h>
#include "guest_identity.h"
#include "posix_acl.h"
#include "inode_lifetime.h"
#include <stdatomic.h>
#include "filesystem_kind.h"
#include "landlock_policy.h"
#include "inode_mount_boundary.h"
struct md_fs_result;
struct md_fs_output;
struct md_inode_boundary {
    char *path; size_t capacity; unsigned operand;
    int mount_root, native_directory;
    uint64_t proc_namespace, proc_link_mount;
    unsigned proc_link_descriptor; /* Optional caller-captured no-follow FD + 1. */
    struct md_fs_anchor *proc_entry;
};

#define MDI_ROOT "00000000000000000000000000000000"
#define MDI_SOURCES 256
struct md_inode_lifetime {
    atomic_uint refs;
    int root, temporary;
    enum md_filesystem_kind filesystem_kind;
    struct md_user_namespace *owner;
    struct md_identity *overlay_identity;
    dev_t device;
    ino_t inode;
    char scope[33];
    int scope_lease;
    int sources[MDI_SOURCES], source_leases[MDI_SOURCES];
};
/* Consumes the validated root/lease FD only on success. */
struct md_inode_lifetime *mdi_lifetime_new(int root, const struct stat *);
enum mdi_query { MDI_NODE, MDI_FD, MDI_LOOKUP, MDI_DIRECTORY_NAME, MDI_READDIR,
    MDI_BEGIN_WRITE, MDI_COMMIT, MDI_ROLLBACK, MDI_EVENT, MDI_EVENT_TRIM,
    MDI_EVENT_END, MDI_EVENT_SCAN, MDI_EVENT_BIRTH, MDI_EVENT_NAMES, MDI_BACKING_OBJECT, MDI_FILE_PATH, MDI_ACL,
    MDI_OVERLAY_LINK, MDI_OVERLAY_NATIVE_NAME, MDI_DATA_VERSION, MDI_ADD_NAME, MDI_DELETE_NAME, MDI_REPARENT,
    MDI_EMPTY, MDI_CREATE_OBJECT, MDI_METADATA, MDI_RESERVE_FIRST, MDI_RESERVE_ADD, MDI_RESERVE_TAKE,
    MDI_DENTRY_CAPTURE, MDI_DENTRY_PATH, MDI_DENTRY_BIRTH,
    MDI_SUPER_READONLY, MDI_OVERLAY_READONLY, MDI_QUERY_COUNT };
struct mdi_read_cache;
struct mdi_path_cache;
struct mdi_stat_location {
    char *path; /* Optional PATH_MAX buffer for mount lookup without a selected view. */
    dev_t device;
    ino_t inode;
    int root;
};
/* Physical directory identity only, valid under the store gate until write
 * admission. Native leaf changes do not replace directory backings. Logical
 * permissions are taken from the current node and are never memoized here. */
struct mdi_directory_check {
    uint64_t epoch;
    dev_t device;
    ino_t inode;
    int objects;
    char backing[33];
};
/* Borrowed O_PATH pins, scoped to one filesystem operation. Identity and type
 * cannot change while pinned; catalogue membership and permissions still can. */
struct mdi_directory_pins {
    unsigned count;
    struct { int fd; dev_t device; ino_t inode; } entries[2];
};
struct md_inode_store {
    unsigned refs;
    sqlite3 *db;
    int objects, watch_presence, locked, recording, readonly, reflink_unavailable;
    int mount_readonly, super_readonly, super_checked, nodev;
    int native_files;
    sqlite3_int64 super_version;
    struct md_inode_lifetime *lifetime;
    const struct md_identity *identity;
    const struct md_landlock_policy *landlock;
    int image_open;
    const char *root_id;
    unsigned creation_mask;
    struct md_inode_boundary *boundary;
    struct mdi_mount_boundary *mount_boundary;
    struct mdi_stat_location *stat_location;
    const struct mdi_directory_pins *directory_pins;
    struct md_inode_watches *watches;
    struct mdi_fifo_owner *fifos;
    struct md_inode_statistics *statistics;
    struct mdi_overlay_writer *overlay;
    int write_transaction;
    int sync_requested;
    unsigned directory_sync_batch;
    struct mdi_read_cache *read_cache;
    struct mdi_path_cache *path_cache;
    int cache_checked;
    unsigned read_depth;
    int operation_window;
    int operation_window_paused;
    sqlite3_int64 locked_version;
    unsigned directory_check_count;
    uint64_t directory_check_epoch;
    struct mdi_directory_check directory_checks[256];
    sqlite3_stmt *queries[MDI_QUERY_COUNT];
    struct mdi_publication *publication;
    sqlite3_int64 operation_changes;
    sqlite3_int64 event_sequence;
#ifdef MD_INODE_TESTING
    void (*observe)(enum md_inode_checkpoint, void *);
    void *context;
#endif
};
static inline int mdi_window_retained(const struct md_inode_store *s) {
    return s->operation_window && !s->operation_window_paused;
}
static inline void mdi_directory_checks_reset(struct md_inode_store *s) {
    s->directory_check_count=0;
    ++s->directory_check_epoch;
}
static inline int mdi_readonly(const struct md_inode_store *s) {
    return s->readonly || s->mount_readonly || s->super_readonly;
}
int mdi_super_refresh(struct md_inode_store *, sqlite3_int64 version);
static inline const char *mdi_root(const struct md_inode_store *s) {
    return s->root_id ? s->root_id : s->lifetime->scope;
}
struct mdi_node {
    char id[33], parent[33], backing[NAME_MAX+34], scope[33];
    mode_t kind;
    dev_t device, logical_device, rdev;
    ino_t inode, logical_inode;
    nlink_t links;
    int attached, shared, source;
    int mode;
    uint32_t uid, gid;
    unsigned acl_mask;
    /* Native leaves are never cached. A positive descriptor borrows fd+1 only
     * for the enclosing operation, including after unlink or pathname reuse. */
    int native, descriptor;
};
struct mdi_location {
    struct mdi_node node, parent;
    char name[NAME_MAX+1];
    int exists, special, trailing;
};
/* Optional final native-leaf capture. The caller owns fd on success; metadata
 * and identity come from that exact descriptor, never another pathname lookup. */
struct mdi_path_pin { int fd; struct stat physical; };
/* Borrowed physical directory, or -1. Never substitutes catalogue admission. */
int mdi_directory_pin(const struct md_inode_store *,const struct mdi_node *);
int mdi_overlay_native_name(struct md_inode_store *,const struct mdi_node *,const char *);
int mdi_overlay_publish_leaf(struct md_inode_store *,const struct mdi_node *,const struct mdi_node *,const char *);
int mdi_bind_body(struct md_inode_store *,const struct mdi_node *,struct mdi_node *);
struct mdi_cache_key {
    enum mdi_query query;
    uint64_t device, inode;
    const char *id, *name;
};
struct mdi_path_name { char parent[33], name[NAME_MAX+1]; int ambiguous; };
int mdi_cache_name_get(struct md_inode_store *, const struct mdi_cache_key *, struct mdi_path_name *);
void mdi_cache_name_put(struct md_inode_store *, const struct mdi_cache_key *, const struct mdi_path_name *);
int mdi_cache_number_get(struct md_inode_store *, const struct mdi_cache_key *, uint64_t *);
void mdi_cache_number_put(struct md_inode_store *, const struct mdi_cache_key *, uint64_t);
/* A NULL node stores/reads only the query's result, e.g. catalogue emptiness. */
int mdi_cache_get(struct md_inode_store *, const struct mdi_cache_key *, struct mdi_node *, int *result);
void mdi_cache_put(struct md_inode_store *, const struct mdi_cache_key *, const struct mdi_node *, int result);
void mdi_cache_invalidate(struct md_inode_store *);
void mdi_cache_snapshot(struct md_inode_store *, sqlite3_int64 version);
enum mdi_dependency_kind { MDI_DEP_OBJECT, MDI_DEP_ENTRY, MDI_DEP_CHILDREN, MDI_DEP_PATH, MDI_DEP_BACKING };
struct mdi_dependency { unsigned slot; uint64_t version; };
uint64_t mdi_cache_epoch(const struct md_inode_store *);
struct mdi_dependency mdi_cache_dependency(struct md_inode_store *,enum mdi_dependency_kind,
    const void *,size_t,const void *,size_t);
int mdi_cache_dependencies_current(const struct md_inode_store *,const struct mdi_dependency *,unsigned);
enum { MDI_PATH_PREFIX_DEPTH=32 };
/* Only directory prefixes are remembered, never terminal entries, native
 * attributes or authority. Symlinks and constrained walks keep their walker. */
struct mdi_path_prefix { unsigned count; struct mdi_node nodes[MDI_PATH_PREFIX_DEPTH]; };
int mdi_path_prefix_get(struct md_inode_store *,const char *,struct mdi_path_prefix *,size_t *);
void mdi_path_prefix_put(struct md_inode_store *,const char *,size_t,const struct mdi_path_prefix *);
void mdi_path_cache_destroy(struct md_inode_store *);
int mdi_landlock(struct md_inode_store *,const struct mdi_node *,const struct mdi_node *,uint64_t);
int mdi_landlock_object(struct md_inode_store *,const struct mdi_node *,uint64_t);
/* ENTRY retains the final directory entry (unlink/rename/create). NOFOLLOW
 * retains a final symlink except when a trailing slash requires traversal. */
enum mdi_follow { MDI_FOLLOW, MDI_NOFOLLOW, MDI_ENTRY };
int mdi_sql_error(int);
int mdi_sql_failure(int);
int mdi_sql(struct md_inode_store *, const char *);
/* Publication durability follows the destination lifetime. Transient mounts
 * retain transaction rollback, but have no power-loss persistence contract. */
int mdi_sync(struct md_inode_store *, int fd);
/* The caller holds the store gate, outside a SQLite transaction. */
int mdi_checkpoint(struct md_inode_store *);
/* Unpublished construction in one writer transaction. Only the objects
 * directory is deferred; end must succeed before the caller commits. */
int mdi_sync_batch_begin(struct md_inode_store *);
int mdi_sync_batch_end(struct md_inode_store *,int result);
enum { MDI_RESERVE_BATCH = 32 };
/* Claims one durable, previously unpublished physical file in this transaction.
 * No live backing is recycled. The caller still syncs its final attributes. */
int mdi_reserve_file(struct md_inode_store *, char id[33], int flags, mode_t mode);
int mdi_begin(struct md_inode_store *, int write);
/* Runtime custody only: no guest inode, name, attribute or content changes.
 * Channel lifetime remains writable on readonly guest mounts/superblocks. */
int mdi_runtime_begin(struct md_inode_store *);
/* Promote the sole read snapshot under its already-held exclusive store gate.
 * Never releases the gate, re-resolves the pathname, or replays an operation. */
int mdi_write(struct md_inode_store *);
int mdi_prepare(struct md_inode_store *, const char *, sqlite3_stmt **);
int mdi_query_acquire(struct md_inode_store *, enum mdi_query, sqlite3_stmt **);
int mdi_query_release(sqlite3_stmt *, int result);
int mdi_step(struct md_inode_store *, sqlite3_stmt *);
int mdi_bind_id(sqlite3_stmt *, int, const char *);
int mdi_bind_name(sqlite3_stmt *, int, const char *);
int mdi_finish(struct md_inode_store *, int);
int mdi_name_valid(const char *);
int mdi_node(struct md_inode_store *, const char *, struct mdi_node *);
int mdi_super_preserve_writer(struct md_inode_store *,struct mdi_node *);
int mdi_fd(struct md_inode_store *, int, struct mdi_node *);
int mdi_fd_membership(struct md_inode_store *, int, struct mdi_node *, int *attached);
int mdi_lookup(struct md_inode_store *, const struct mdi_node *parent, const char *, struct mdi_node *, struct stat *, struct mdi_path_pin *);
int mdi_catalogue_lookup(struct md_inode_store *, const char *parent, const char *, struct mdi_node *);
int mdi_stat(struct md_inode_store *, const struct mdi_node *, struct stat *);
ssize_t mdi_readlink(struct md_inode_store *,const struct mdi_node *,char *,size_t,int empty);
void mdi_stat_project(const struct mdi_node *, struct stat *);
int mdi_backing_stat(struct md_inode_store *, const struct mdi_node *, struct stat *);
int mdi_backing_fstat(const struct mdi_node *, int, struct stat *);
int mdi_fstat(struct md_inode_store *, int, struct mdi_node *, struct stat *);
int mdi_fstat_origin(struct md_inode_store *, int, struct mdi_node *, struct stat *, int native_leaf);
int mdi_access(struct md_inode_store *, const struct mdi_node *, int);
int mdi_permission(struct md_inode_store *, const struct mdi_node *, int, int real);
int mdi_permission_as(struct md_inode_store *, const struct mdi_node *, int, const struct md_identity *);
int mdi_permission_stat(struct md_inode_store *, const struct mdi_node *, struct stat *);
int mdi_open_permission(struct md_inode_store *, const struct mdi_node *, int flags, const struct stat *observed);
int mdi_sticky(struct md_inode_store *, const struct mdi_location *);
int mdi_sticky_as(struct md_inode_store *, const struct mdi_location *, const struct md_identity *);
int mdi_metadata(struct md_inode_store *, const struct mdi_node *, uint32_t, uint32_t, mode_t);
int mdi_device_number(struct md_inode_store *, struct mdi_node *, dev_t);
int mdi_file_capability(struct md_inode_store *, const struct mdi_node *, const void *, size_t);
int mdi_acl_store(struct md_inode_store *, const struct mdi_node *, unsigned, const void *, size_t);
int mdi_acl_permission(struct md_inode_store *, const struct mdi_node *, const struct md_identity *, unsigned);
int mdi_acl_chmod(struct md_inode_store *, const struct mdi_node *, unsigned);
int mdi_acl_inherit(struct md_inode_store *, const struct mdi_node *, const struct mdi_node *, unsigned);
void md_inode_acl(struct md_inode_store *, const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
int mdi_attributes(struct md_inode_store *, const struct mdi_node *, void *, size_t);
int mdi_xattr_permission(struct md_inode_store *, const struct mdi_node *, const char *, int);
int mdi_xattr_permission_as(struct md_inode_store *, const struct mdi_node *, const char *, int, const struct md_identity *);
int mdi_setattr_permission(struct md_inode_store *, const struct mdi_node *, const struct md_fs_request *, const struct md_identity *);
void md_inode_xattr(struct md_inode_store *, const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
void md_inode_xattr_origin(struct md_inode_store *, const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *, int native_leaf);
int mdi_walk(struct md_inode_store *, int, const char *, enum mdi_follow, int missing, struct mdi_location *);
int mdi_walk_stat(struct md_inode_store *, int, const char *, enum mdi_follow, struct mdi_location *, struct stat *);
int mdi_path_step(struct md_inode_store *, const struct mdi_node *, const char *,
        const char *tail, uint64_t resolve, struct mdi_node *, struct stat *, struct mdi_path_pin *);
int mdi_host_boundary(struct md_inode_store *, const char *parent, const char *name, const char *tail, uint64_t resolve);
int mdi_walk_resolved(struct md_inode_store *, int, const char *, enum mdi_follow, int missing,
        uint64_t resolve, struct mdi_location *);
int mdi_walk_pinned(struct md_inode_store *, int, const char *, enum mdi_follow, int missing,
        uint64_t resolve, struct mdi_location *, struct mdi_path_pin *);
int mdi_location_path(struct md_inode_store *, const struct mdi_location *, char *, size_t);
int mdi_node_path(struct md_inode_store *, const struct mdi_node *, char *, size_t);
int mdi_dentry_capture(struct md_inode_store *,const struct mdi_location *,uint64_t *);
int mdi_dentry_move(struct md_inode_store *,uint64_t,const char *,const char *);
int mdi_parent_writable(struct md_inode_store *, const struct mdi_node *);
int mdi_ancestor(struct md_inode_store *, const char *ancestor, const char *child);
int mdi_add_name(struct md_inode_store *, const char *parent, const char *name, const char *object);
int mdi_delete_name(struct md_inode_store *, const char *parent, const char *name);
int mdi_reparent(struct md_inode_store *, const struct mdi_node *, const char *parent);
int mdi_empty(struct md_inode_store *, const struct mdi_node *);
int mdi_allocate(struct md_inode_store *, mode_t kind, mode_t mode, int flags, const char *target,
        const char *parent, struct mdi_node *, int *fd);
int mdi_allocate_root(struct md_inode_store *, struct mdi_node *);
int mdi_commit(struct md_inode_store *, int);
/* Publication ownership follows the SQL commit, even if event/sync retirement fails. */
int mdi_commit_owned(struct md_inode_store *,int,int *committed);
int mdi_register_object(struct md_inode_store *,const char *,mode_t,int,uint32_t,uint32_t,
        const struct stat *,const char *,struct mdi_node *);
int mdi_random_id(char [33]);
/* Called within the namespace write transaction, before exposing any writable
 * descriptor. Native data IO remains outside the namespace after this point. */
int mdi_copy_up(struct md_inode_store *, struct mdi_node *);
int mdi_copy_attributes(int source, int destination);
int mdi_copy_body(struct md_inode_store *, int source, int destination, const struct stat *);
int mdi_replace_attributes(int source, int destination);
int mdi_overlay_replace_attributes(int source, int destination);
int mdi_sources_open(struct md_inode_store *);
/* Caller owns exclusive maintenance or an unpublished store. No live lower
 * store or ordinary open description may depend on untracked physical bodies. */
int mdi_discard_untracked_backings(struct md_inode_store *);
int mdi_source_register(struct md_inode_store *, int objects, int *source);
int mdi_import_shared(struct md_inode_store *, int source, const char *backing, struct mdi_node *);
int mdi_backing_directory(struct md_inode_store *, const struct mdi_node *);
int mdi_backing_open(struct md_inode_store *, const struct mdi_node *, int flags);
int mdi_backing_path(struct md_inode_store *, const struct mdi_node *, char *, size_t);
int mdi_fifo_prepare(struct md_inode_store *, int, struct md_open_completion *);
int mdi_channel(struct md_inode_store *, const struct mdi_node *, char [33], int *source);
int mdi_channel_copy(struct md_inode_store *, const struct mdi_node *, struct md_inode_store *, const struct mdi_node *);
int mdi_fifo_descriptor(struct md_inode_store *, const struct stat *, struct mdi_node *);
void mdi_fifo_close(struct md_inode_store *);
int mdi_event(struct md_inode_store *, const char *parent, const struct mdi_node *,
        const char *name, unsigned mask, unsigned *cookie);
int mdi_removed_event(struct md_inode_store *, const struct mdi_location *, int parent_event);
int mdi_watch_marker(struct md_inode_store *,const char *);
int mdi_events_committed(struct md_inode_store *);
#endif
