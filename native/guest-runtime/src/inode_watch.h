#ifndef MD_INODE_WATCH_H
#define MD_INODE_WATCH_H
#include <stddef.h>
#include <sys/types.h>
struct md_inode_store;
/* Namespace-worker owned. Descriptors are borrowed; identity is verified from
 * the retained kernel object, never a guest descriptor number. */
int md_inode_watch_create(struct md_inode_store *, int flags);
int md_inode_watch_add(struct md_inode_store *, int instance, int object, unsigned mask);
int md_inode_watch_remove(struct md_inode_store *, int instance, int wd);
int md_inode_watch_contains(struct md_inode_store *, int instance);
ssize_t md_inode_watch_bytes(struct md_inode_store *, int instance);
ssize_t md_inode_watch_read(struct md_inode_store *, int instance, void *, size_t,
        int (*deliver)(void *, const void *, size_t), void *);
int md_inode_watch_pollfd(struct md_inode_store *);
int md_inode_watch_pump(struct md_inode_store *);
void md_inode_watch_close(struct md_inode_store *);
#endif
