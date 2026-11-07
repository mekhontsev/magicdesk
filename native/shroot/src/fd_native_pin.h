#ifndef MD_FD_NATIVE_PIN_H
#define MD_FD_NATIVE_PIN_H
#include "fd_object.h"
#include "guest_identity.h"

/* Native input provenance without retaining its data OFD. This is not a
 * filesystem/mount authority: the worker still resolves guest membership. */
struct md_fd_object *md_fd_native_pin_new(int fd,const struct md_identity *);
/* Borrowed O_PATH descriptor, or -1 for another provider. */
int md_fd_native_pin_descriptor(const struct md_fd_object *);
#endif
