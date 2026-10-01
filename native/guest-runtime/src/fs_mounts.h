#ifndef MD_FS_MOUNTS_H
#define MD_FS_MOUNTS_H
#include "fs_engine.h"

#define MD_FS_MOUNTS_MAX 16
struct md_fs_attachment { const char *source, *target; int readonly; };
/* Explicit native directories, scoped to one launch. No mount syscall, identity
 * change, source creation or persisted configuration is performed here. */
int md_fs_mounts_open(struct md_filesystem *, const struct md_fs_attachment *, unsigned count);
void md_fs_mounts_close(struct md_filesystem *);
/* Dispatches the composed view; the ordinary inode engine remains the backend. */
void md_fs_mounts_execute(struct md_filesystem *, const struct md_fs_request *,
        struct md_fs_result *, const struct md_fs_output *);
void md_fs_inode_execute(struct md_filesystem *, const struct md_fs_request *,
        struct md_fs_result *, const struct md_fs_output *);
#endif
