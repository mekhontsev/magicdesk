#ifndef MD_SOCKET_NAMESPACE_H
#define MD_SOCKET_NAMESPACE_H
#include "fs.h"
#include <sys/un.h>
long md_namespace_socket_bind(const struct md_fs *, int socket, const char *path);
long md_namespace_socket_address(const struct md_fs *, const char *, struct sockaddr_un *, unsigned *);
long md_namespace_socket_output(const struct md_fs *, long, const unsigned long *);
#endif
