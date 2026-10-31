#ifndef MD_WATCH_CALLS_H
#define MD_WATCH_CALLS_H
#include "fs.h"
#include <sys/ioctl.h>
#include <sys/syscall.h>
/* Call only after typed control/terminal/filesystem owners have classified it. */
static inline int md_watch_ioctl_native(long nr,unsigned long command) {
    return nr==SYS_ioctl && (unsigned)command!=FIONREAD;
}
long md_watch_call(const struct md_fs *, long, const unsigned long *);
long md_watch_contains(const struct md_fs *, int fd);
long md_watch_read_local(const struct md_fs *, long, const unsigned long *);
#endif
