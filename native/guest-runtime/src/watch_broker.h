#ifndef MD_WATCH_BROKER_H
#define MD_WATCH_BROKER_H
#include <linux/seccomp.h>
struct md_inode_store;
struct md_watch_broker;
struct md_watch_broker *md_watch_broker_create(void);
/* Takes the retained reader, including on failure. Worker-thread only. */
int md_watch_broker_submit(struct md_watch_broker *, struct md_inode_store *, int listener,
        const struct seccomp_notif *, int reader);
int md_watch_broker_progress(struct md_watch_broker *, struct md_inode_store *);
void md_watch_broker_destroy(struct md_watch_broker *);
#endif
