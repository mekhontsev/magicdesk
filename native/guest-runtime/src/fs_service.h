#ifndef MD_FS_SERVICE_H
#define MD_FS_SERVICE_H
#include "inode_store.h"
#include "fs_engine.h"
#include "fs_wire.h"
struct md_image_catalogue;
struct md_fs_statistics { struct md_cost operation[MD_FS_LAST + 1]; };
int md_fs_listen(const char *abstract_name);
struct md_fs_work_source {
    int fd;
    void *context;
    void (*ready)(void *);
    int (*notification_fd)(void *);
    int (*notification)(void *, struct md_filesystem *, short);
};
/* Calling thread exclusively owns store. Stop-FD readiness ends the service;
 * caller retains both listener/stop FDs. Peer requests never own its lifetime. */
int md_fs_serve(struct md_filesystem *, int listener, int stop_fd,
        unsigned timeout_ms, struct md_fs_statistics *, const struct md_fs_work_source *);
#ifdef MD_FS_TESTING
enum md_fs_checkpoint { MD_FS_BEFORE_DISPATCH, MD_FS_AFTER_DISPATCH, MD_FS_REPLY_SENT, MD_FS_CONNECTION_CLOSED };
void md_fs_observe(void (*)(enum md_fs_checkpoint, const struct md_fs_packet *, void *), void *);
#endif
#endif
