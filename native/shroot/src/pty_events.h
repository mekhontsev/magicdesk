#ifndef MD_PTY_EVENTS_H
#define MD_PTY_EVENTS_H
struct md_pty_events;
struct md_fd_object;
struct md_fs_anchor;
struct md_inode_store;
/* Launch-owned PTYs only. No data FD is retained and no data IO is adapted.
 * Native inotify admission is probed once on the first allocated slave. */
int md_pty_events_open(struct md_pty_events **,int,struct md_fs_anchor *,int flags,
    int caller_open,struct md_fd_object **);
int md_pty_events_watch(struct md_pty_events *,struct md_inode_store *,int instance,int object,unsigned mask);
void md_pty_events_release(struct md_pty_events *);
#endif
