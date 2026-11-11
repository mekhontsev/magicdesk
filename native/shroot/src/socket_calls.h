#ifndef MD_SOCKET_CALLS_H
#define MD_SOCKET_CALLS_H
#include "fs.h"
#include <sys/socket.h>
#include <sys/syscall.h>

/* Address and identity metadata are adapted; payload and FD transport is native. */
#include "syscall_catalog.h"
static inline int md_socket_payload_native(long nr, unsigned long address) {
    return !address && (nr==SYS_sendto || nr==SYS_recvfrom);
}
/* Only application effects use this gate; internal IPC stays shielded. */
static inline int md_socket_wait_call(long nr, unsigned long arg2, unsigned long arg3) {
    switch (nr) {
    case SYS_accept: case SYS_accept4: case SYS_connect: return 1;
    case SYS_recvfrom: case SYS_sendto: case SYS_sendmmsg: case SYS_recvmmsg: return !(arg3&MSG_DONTWAIT);
    case SYS_recvmsg: case SYS_sendmsg: return !(arg2&MSG_DONTWAIT);
    default: return 0;
    }
}
long md_socket_invoke(long, const unsigned long *);
long md_socket_call(const struct md_fs *, const char *, long, const unsigned long *,unsigned long);
long md_socket_pair(const struct md_fs *, const unsigned long *, int offline);
long md_socket_offline_error(long nr, const unsigned long *);
#endif
