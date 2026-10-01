#ifndef MD_INODE_INTERNAL_H
#define MD_INODE_INTERNAL_H
#include "inode_store.h"
#include <limits.h>
#include <sqlite3.h>
#include "guest_identity.h"

#define MDI_ROOT "00000000000000000000000000000000"
#define MDI_SOURCES 256
enum mdi_query { MDI_NODE, MDI_FD, MDI_LOOKUP, MDI_DIRECTORY_NAME, MDI_READDIR,
    MDI_BEGIN, MDI_BEGIN_WRITE, MDI_COMMIT, MDI_ROLLBACK, MDI_EVENT, MDI_EVENT_TRIM,
    MDI_EVENT_END, MDI_EVENT_SCAN, MDI_EVENT_NAMES, MDI_BACKING_OBJECT,
    MDI_QUERY_COUNT };
struct md_inode_store {
    sqlite3 *db;
    int objects, root, watch_presence, locked, recording, readonly, reflink_unavailable;
    const struct md_identity *identity;
    int sources[MDI_SOURCES];
    struct md_inode_watches *watches;
    struct md_inode_statistics *statistics;
    sqlite3_stmt *queries[MDI_QUERY_COUNT];
#ifdef MD_INODE_TESTING
    void (*observe)(enum md_inode_checkpoint, void *);
    void *context;
#endif
};
struct mdi_node {
    char id[33], parent[33], backing[33];
    mode_t kind;
    dev_t device, logical_device;
    ino_t inode, logical_inode;
    nlink_t links;
    int attached, shared, source;
    int mode;
    uint32_t uid, gid;
};
struct mdi_location {
    struct mdi_node node, parent;
    char name[NAME_MAX+1];
    int exists, special, trailing;
};
/* ENTRY retains the final directory entry (unlink/rename/create). NOFOLLOW
 * retains a final symlink except when a trailing slash requires traversal. */
enum mdi_follow { MDI_FOLLOW, MDI_NOFOLLOW, MDI_ENTRY };
int mdi_sql_error(int);
int mdi_sql_failure(int);
int mdi_sql(struct md_inode_store *, const char *);
int mdi_begin(struct md_inode_store *, int write);
int mdi_prepare(struct md_inode_store *, const char *, sqlite3_stmt **);
int mdi_query_acquire(struct md_inode_store *, enum mdi_query, sqlite3_stmt **);
int mdi_query_release(sqlite3_stmt *, int result);
int mdi_step(struct md_inode_store *, sqlite3_stmt *);
int mdi_bind_id(sqlite3_stmt *, int, const char *);
int mdi_bind_name(sqlite3_stmt *, int, const char *);
int mdi_finish(struct md_inode_store *, int);
int mdi_name_valid(const char *);
int mdi_node(struct md_inode_store *, const char *, struct mdi_node *);
int mdi_fd(struct md_inode_store *, int, struct mdi_node *);
int mdi_fd_membership(struct md_inode_store *, int, struct mdi_node *, int *attached);
int mdi_lookup(struct md_inode_store *, const char *parent, const char *, struct mdi_node *);
int mdi_stat(struct md_inode_store *, const struct mdi_node *, struct stat *);
int mdi_backing_stat(struct md_inode_store *, const struct mdi_node *, struct stat *);
int mdi_fstat(struct md_inode_store *, int, struct mdi_node *, struct stat *);
int mdi_access(struct md_inode_store *, const struct mdi_node *, int);
int mdi_permission(struct md_inode_store *, const struct mdi_node *, int, int real);
int mdi_sticky(struct md_inode_store *, const struct mdi_location *);
int mdi_metadata(struct md_inode_store *, const struct mdi_node *, uint32_t, uint32_t, mode_t);
int mdi_walk(struct md_inode_store *, int, const char *, enum mdi_follow, int missing, struct mdi_location *);
int mdi_walk_resolved(struct md_inode_store *, int, const char *, enum mdi_follow, int missing,
        uint64_t resolve, struct mdi_location *);
int mdi_location_path(struct md_inode_store *, const struct mdi_location *, char *, size_t);
int mdi_parent_writable(struct md_inode_store *, const struct mdi_node *);
int mdi_ancestor(struct md_inode_store *, const char *ancestor, const char *child);
int mdi_add_name(struct md_inode_store *, const char *parent, const char *name, const char *object);
int mdi_delete_name(struct md_inode_store *, const char *parent, const char *name);
int mdi_reparent(struct md_inode_store *, const struct mdi_node *, const char *parent);
int mdi_empty(struct md_inode_store *, const struct mdi_node *);
int mdi_allocate(struct md_inode_store *, mode_t kind, mode_t mode, int flags, const char *target,
        const char *parent, struct mdi_node *, int *fd);
int mdi_commit(struct md_inode_store *, int);
int mdi_random_id(char [33]);
/* Called within the namespace write transaction, before exposing any writable
 * descriptor. Native data IO remains outside the namespace after this point. */
int mdi_copy_up(struct md_inode_store *, struct mdi_node *);
int mdi_sources_open(struct md_inode_store *);
int mdi_backing_directory(struct md_inode_store *, const struct mdi_node *);
int mdi_event(struct md_inode_store *, const char *parent, const struct mdi_node *,
        const char *name, unsigned mask, unsigned *cookie);
int mdi_removed_event(struct md_inode_store *, const struct mdi_location *, int parent_event);
#endif
