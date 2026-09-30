#ifndef MD_INODE_INTERNAL_H
#define MD_INODE_INTERNAL_H
#include "inode_store.h"
#include <limits.h>
#include <sqlite3.h>

#define MDI_ROOT "00000000000000000000000000000000"
enum mdi_query { MDI_NODE, MDI_FD, MDI_LOOKUP, MDI_LINK_COUNT, MDI_QUERY_COUNT };
struct md_inode_store {
    sqlite3 *db;
    int objects, locked;
    struct md_inode_statistics *statistics;
    sqlite3_stmt *queries[MDI_QUERY_COUNT];
#ifdef MD_INODE_TESTING
    void (*observe)(enum md_inode_checkpoint, void *);
    void *context;
#endif
};
struct mdi_node {
    char id[33], parent[33];
    mode_t kind;
    dev_t device;
    ino_t inode;
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
int mdi_step(struct md_inode_store *, sqlite3_stmt *);
int mdi_bind_id(sqlite3_stmt *, int, const char *);
int mdi_bind_name(sqlite3_stmt *, int, const char *);
int mdi_finish(struct md_inode_store *, int);
int mdi_name_valid(const char *);
int mdi_node(struct md_inode_store *, const char *, struct mdi_node *);
int mdi_fd(struct md_inode_store *, int, struct mdi_node *);
int mdi_lookup(struct md_inode_store *, const char *parent, const char *, struct mdi_node *);
int mdi_stat(struct md_inode_store *, const struct mdi_node *, struct stat *);
int mdi_access(struct md_inode_store *, const struct mdi_node *, int);
int mdi_walk(struct md_inode_store *, int, const char *, enum mdi_follow, int missing, struct mdi_location *);
int mdi_walk_resolved(struct md_inode_store *, int, const char *, enum mdi_follow, int missing,
        uint64_t resolve, struct mdi_location *);
int mdi_parent_writable(struct md_inode_store *, const struct mdi_node *);
int mdi_ancestor(struct md_inode_store *, const char *ancestor, const char *child);
int mdi_add_name(struct md_inode_store *, const char *parent, const char *name, const char *object);
int mdi_delete_name(struct md_inode_store *, const char *parent, const char *name);
int mdi_reparent(struct md_inode_store *, const struct mdi_node *, const char *parent);
int mdi_empty(struct md_inode_store *, const struct mdi_node *);
int mdi_allocate(struct md_inode_store *, mode_t kind, mode_t mode, int flags, const char *target,
        const char *parent, struct mdi_node *, int *fd);
int mdi_commit(struct md_inode_store *, int);
#endif
