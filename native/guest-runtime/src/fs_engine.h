#ifndef MD_FS_ENGINE_H
#define MD_FS_ENGINE_H
#include "fs_rpc.h"
#include "inode_store.h"
struct md_image_catalogue;
/* Directory delivery runs synchronously before committing the shared cursor.
 * Other operations publish their ordinary result. NULL retains local delivery. */
struct md_fs_output { int (*deliver)(void *, const void *, size_t); void *context; };
/* One namespace owner, independent of transport. Input descriptors are borrowed;
 * a successful open transfers result.fd. No guest pointers enter this API. */
void md_fs_execute(struct md_inode_store *, struct md_image_catalogue *,
        const struct md_fs_request *, struct md_fs_result *, const struct md_fs_output *);
#endif
