#ifndef MD_SECCOMP_LISTENER_H
#define MD_SECCOMP_LISTENER_H
#include "fd_object.h"
#include "seccomp_policy.h"

/* Descriptor provenance only. Linux owns the listener and its last-close
 * semantics; retaining this authority does not duplicate a kernel FD. The
 * existing FD table/SCM_RIGHTS owners carry it across aliases and processes. */
struct md_fd_object *md_seccomp_listener_new(struct md_seccomp_policy *);
/* Virtual listener on the same FD/SCM_RIGHTS owner. Its anonymous carrier never
 * exposes Shroot's kernel listener. Outstanding calls retain only queue state;
 * the last descriptor/in-flight-ioctl release closes that queue. */
struct md_seccomp_notify;
struct md_fd_object *md_seccomp_listener_virtual(struct md_seccomp_policy *,
    const struct md_control_opener *,struct md_resource_wakeup *);
struct md_seccomp_notify *md_seccomp_listener_notifications(struct md_fd_object *);
/* Borrowed from a retained object, never looked up by a descriptor number or
 * notification ID alone. NULL denotes an unrelated/unobserved descriptor. */
const struct md_seccomp_policy *md_seccomp_listener_policy(const struct md_fd_object *);
#endif
