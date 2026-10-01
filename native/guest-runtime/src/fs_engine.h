#ifndef MD_FS_ENGINE_H
#define MD_FS_ENGINE_H
#include "fs_operation.h"
#include "inode_store.h"
struct md_image_catalogue;
struct md_fs_mounts;
/* One launch view borrows its store and admitted images. Attachments belong to
 * the same worker, never to persisted image metadata or a global namespace. */
struct md_filesystem {
    struct md_inode_store *store;
    struct md_image_catalogue *images;
    struct md_fs_mounts *mounts;
    struct md_credentials *credentials;
    struct md_ipc_credentials *ipc;
    const char *ipc_store;
};
/* One namespace owner, independent of transport. Input descriptors are borrowed;
 * a successful open transfers result.fd. No guest pointers enter this API. */
void md_fs_execute(struct md_filesystem *,
        const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
void md_fs_stat_info(const struct stat *, struct md_fs_info *);
#endif
