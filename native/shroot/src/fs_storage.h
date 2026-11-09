#ifndef MD_FS_STORAGE_H
#define MD_FS_STORAGE_H
#include "fs_engine.h"
struct md_view_mount;
struct md_fs_storage_connections;
enum { MD_FS_STORAGE_CACHE_LIMIT = 16 };
/* Worker-owned connections, separate from mount placement and retained backing
 * custody. Borrowed filesystem contexts share this owner, never copy its list.
 * Capacity bounds idle caching; active scopes and deferred effects are pinned. */
struct md_fs_storage_cache {
    struct md_fs_storage_connections *connections;
    unsigned count;
};
/* Unscoped results are borrowed until the next cache miss. */
int md_fs_storage_get(struct md_filesystem *, const struct md_view_mount *, struct md_inode_store **);
int md_fs_storage_readonly(struct md_filesystem *, const struct md_view_mount *);
void md_fs_storage_prune(struct md_fs_storage_cache *);
void md_fs_storage_close(struct md_fs_storage_cache *);
/* Scoped request policy on a selected connection. No store state travels with
 * mount copies or FD origins; the worker restores policy before its next call. */
struct md_fs_storage_scope {
    struct md_inode_store *store;
    const struct md_identity *identity;
    const struct md_landlock_policy *landlock;
    int image_open;
    const char *root;
    struct md_inode_boundary *boundary;
    unsigned mask;
};
int md_fs_storage_enter(struct md_filesystem *, const struct md_view_mount *,
        struct md_fs_storage_scope *);
void md_fs_storage_leave(struct md_fs_storage_scope *);
#endif
