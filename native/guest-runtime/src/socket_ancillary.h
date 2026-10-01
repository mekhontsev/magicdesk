#ifndef MD_SOCKET_ANCILLARY_H
#define MD_SOCKET_ANCILLARY_H
#include "fs.h"
long md_socket_ancillary_send(const struct md_fs *, const char *, const unsigned long *);
long md_socket_ancillary_receive(const struct md_fs *, const char *, const unsigned long *);
long md_socket_ancillary_batch(const struct md_fs *, const unsigned long *);
long md_socket_address_call(const struct md_fs *, const char *, long, const unsigned long *);
#endif
