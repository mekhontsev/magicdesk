#ifndef MD_WATCH_CALLS_H
#define MD_WATCH_CALLS_H
#include "fs.h"
long md_watch_call(const struct md_fs *, long, const unsigned long *);
long md_watch_arm(const struct md_fs *, int);
long md_watch_read_local(const struct md_fs *, long, const unsigned long *);
int md_watch_received(const struct md_fs *, const void *message);
#endif
