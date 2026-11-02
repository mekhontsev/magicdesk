#ifndef MD_INODE_FIFO_H
#define MD_INODE_FIFO_H

struct md_inode_store;
struct md_inode_lifetime;
struct mdi_fifo_owner;
/* Channel identity and transferable path-only custody. Neither retains a data
 * endpoint or a SQLite connection. An empty identity denotes a native pipe. */
int md_inode_fifo_channel(struct md_inode_store *, int fd, char channel[33]);
int md_inode_fifo_adopt(struct md_inode_lifetime *, const char channel[33], int fd,
    struct mdi_fifo_owner **);
struct mdi_fifo_owner *md_inode_fifo_retain(struct md_inode_store *);
void md_inode_fifo_release(struct mdi_fifo_owner *);
#endif
