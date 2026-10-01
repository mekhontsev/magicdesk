#ifndef MD_FS_WORKER_H
#define MD_FS_WORKER_H
#include "inode_store.h"
struct md_image_catalogue;
struct md_fs_worker;
struct md_fs_work {
    struct md_fs_work *next;
};
/* One worker owns the namespace, image catalogue and both request transports.
 * Published work transfers to the supervisor. Queue locks never cover IO. */
int md_fs_worker_start(const char *store, const char *endpoint, const char *admit,
        int statistics, struct md_fs_worker **out);
int md_fs_worker_fd(struct md_fs_worker *);
void md_fs_worker_wake(struct md_fs_worker *);
struct md_fs_work *md_fs_worker_completed(struct md_fs_worker *);
void md_fs_worker_publish(struct md_fs_worker *, struct md_fs_work *);
void md_fs_worker_notifications(struct md_fs_worker *, int, void *,
        int (*)(void *, struct md_inode_store *, struct md_image_catalogue *, short));
int md_fs_worker_error(struct md_fs_worker *);
int md_fs_worker_stop(struct md_fs_worker *);
#endif
