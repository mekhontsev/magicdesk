#ifndef MD_SOCKET_CALLS_H
#define MD_SOCKET_CALLS_H
#include "fs.h"

/* Address-bearing input calls only. Payload IO and received FDs remain native. */
#define MD_SOCKET_CALLS(X) X(bind) X(connect) X(sendto) X(sendmsg) X(sendmmsg)
long md_socket_call(const struct md_fs *, const char *, long, const unsigned long *);
#endif
