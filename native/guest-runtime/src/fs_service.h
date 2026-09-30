#ifndef MD_FS_SERVICE_H
#define MD_FS_SERVICE_H
#include "inode_store.h"
#include "fs_wire.h"
struct md_image_catalogue;
int md_fs_listen(const char *abstract_name);
/* Calling thread exclusively owns store. Stop-FD readiness ends the service;
 * caller retains both listener/stop FDs. Peer requests never own its lifetime. */
int md_fs_serve(struct md_inode_store *, struct md_image_catalogue *, int listener, int stop_fd, unsigned timeout_ms);
#ifdef MD_FS_TESTING
enum md_fs_checkpoint { MD_FS_BEFORE_DISPATCH, MD_FS_AFTER_DISPATCH, MD_FS_CONNECTION_CLOSED };
void md_fs_observe(void (*)(enum md_fs_checkpoint, const struct md_fs_packet *, void *), void *);
#endif
#endif
