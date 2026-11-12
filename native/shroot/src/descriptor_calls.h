#ifndef MD_DESCRIPTOR_CALLS_H
#define MD_DESCRIPTOR_CALLS_H
#include <sys/syscall.h>
#include <fcntl.h>
#include "terminal_calls.h"
#define MD_DESCRIPTOR_IOCTL_QUERIES(X) \
    X(ioctl,FIONREAD) X(ioctl,TIOCGWINSZ) X(ioctl,TCGETS) X(ioctl,TCGETS2)
#define MD_DESCRIPTOR_SERVICE_QUERIES(X) \
    X(fcntl,F_GETFD) X(fcntl,F_GETFL) X(fcntl,F_GET_SEALS) \
    MD_DESCRIPTOR_IOCTL_QUERIES(X)
static inline int md_descriptor_query_call(long nr,unsigned command) {
#define QUERY(name,value) || (nr==SYS_##name && command==value)
    return 0 MD_DESCRIPTOR_IOCTL_QUERIES(QUERY);
#undef QUERY
}
/* Candidate IO only. Its current descriptor owner still decides whether a
 * native continuation is valid under the file-table turn. */
static inline int md_descriptor_stream_call(long nr) {
    return nr==SYS_read || nr==SYS_write || nr==SYS_readv || nr==SYS_writev;
}
static inline int md_descriptor_io_call(long nr) {
    switch (nr) {
    case SYS_read: case SYS_write: case SYS_readv: case SYS_writev:
    case SYS_pread64: case SYS_pwrite64: case SYS_preadv: case SYS_pwritev:
    case SYS_preadv2: case SYS_pwritev2: case SYS_ppoll: case SYS_pselect6:
    case SYS_epoll_pwait: case SYS_epoll_pwait2: return 1;
    default: return 0;
    }
}
static inline int md_descriptor_native_candidate(long nr,unsigned command) {
    return md_descriptor_io_call(nr) || md_descriptor_query_call(nr,command);
}
struct md_fs;
enum { MD_DESCRIPTOR_ANONYMOUS=0x10000, MD_DESCRIPTOR_FILESYSTEM };
long md_descriptor_kind(int fd);
/* Interception requirements come from the typed owner, not its proc kind. */
long md_descriptor_duplicate(const struct md_fs *, long nr, const unsigned long *);
long md_descriptor_pair(long nr, const unsigned long *);
long md_descriptor_metadata(const struct md_fs *, int fd, long nr, const unsigned long *);
#endif
