#ifndef MD_SOCKET_CONTROL_H
#define MD_SOCKET_CONTROL_H
#include <sys/socket.h>
#include <string.h>

#ifndef SCM_PIDFD
#define SCM_PIDFD 0x04
#endif
/* Linux may supply a pidfd in addition to the 253 application rights. Both
 * kinds transfer ownership; opaque ancillary bytes never do. */
enum { MD_SOCKET_RECEIVED_FDS=254 };
/* Every transferred descriptor occupies an int after at least one header.
 * Multiple headers and non-FD controls can only reduce this upper bound. */
static inline unsigned md_socket_control_capacity(size_t bytes) {
    size_t count=bytes>CMSG_LEN(0) ? (bytes-CMSG_LEN(0))/sizeof(int) : 0;
    return count>MD_SOCKET_RECEIVED_FDS ? MD_SOCKET_RECEIVED_FDS : (unsigned)count;
}
static inline unsigned md_socket_control_fds(const struct cmsghdr *h) {
    if (h->cmsg_level!=SOL_SOCKET || h->cmsg_len<CMSG_LEN(0)) return 0;
    if (h->cmsg_type==SCM_RIGHTS) return (h->cmsg_len-CMSG_LEN(0))/sizeof(int);
    if (h->cmsg_type!=SCM_PIDFD || h->cmsg_len!=CMSG_LEN(sizeof(int))) return 0;
    int fd; memcpy(&fd,CMSG_DATA(h),sizeof(fd));
    /* The kernel can report a negative errno instead of allocating a pidfd. */
    return fd>=0;
}
#endif
