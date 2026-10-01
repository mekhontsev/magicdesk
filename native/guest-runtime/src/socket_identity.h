#ifndef MD_SOCKET_IDENTITY_H
#define MD_SOCKET_IDENTITY_H
#include "fs.h"
#include <sys/un.h>
long md_socket_identity_call(const struct md_fs *, unsigned, int, int, long, const struct sockaddr_un *, unsigned);
long md_socket_identity_option(const struct md_fs *, const unsigned long *);
long md_socket_identity_hidden(const struct md_fs *, int, const struct sockaddr_un *, unsigned);
#endif
