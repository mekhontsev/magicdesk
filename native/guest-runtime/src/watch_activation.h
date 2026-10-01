#ifndef MD_WATCH_ACTIVATION_H
#define MD_WATCH_ACTIVATION_H
/* Installed seccomp selectors belong to a thread group, not its address space
 * or descriptor table. Fork copies them, threads share them, exec retains them. */
struct md_watch_activation;
struct md_watch_activation *md_watch_activation_new(void);
struct md_watch_activation *md_watch_activation_fork(struct md_watch_activation *, int shared);
void md_watch_activation_release(struct md_watch_activation *);
int md_watch_activation_has(struct md_watch_activation *, unsigned fd);
int md_watch_activation_mark(struct md_watch_activation *, unsigned fd);
#endif
