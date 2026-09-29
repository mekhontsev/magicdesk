#ifndef MD_SOCKET_CALLS_H
#define MD_SOCKET_CALLS_H
#include "fs.h"

/* Only address metadata is translated. Payloads and received FDs remain native. */
#define MD_SOCKET_CALLS(X) X(bind) X(connect) X(sendto) X(sendmsg) X(sendmmsg) \
    X(getsockname) X(getpeername) X(accept) X(accept4) X(recvfrom) X(recvmsg)
long md_socket_call(const struct md_fs *, const char *, long, const unsigned long *);
#endif
