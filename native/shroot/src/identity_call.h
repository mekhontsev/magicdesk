#ifndef MD_IDENTITY_CALL_H
#define MD_IDENTITY_CALL_H
#include "guest_identity.h"
#include <errno.h>
#include <sys/syscall.h>

#define MD_IDENTITY_CALLS(X) \
    X(getuid) X(geteuid) X(getgid) X(getegid) \
    X(getresuid) X(getresgid) X(setresuid) X(setresgid) \
    X(setuid) X(setgid) X(setreuid) X(setregid) \
    X(setfsuid) X(setfsgid) X(setgroups) X(getgroups) \
    X(capget) X(capset)

static inline int md_identity_scalar_update(long number) {
    switch (number) {
    case SYS_setresuid: case SYS_setresgid: case SYS_setuid: case SYS_setgid:
    case SYS_setreuid: case SYS_setregid: case SYS_setfsuid: case SYS_setfsgid: return 1;
    default: return 0;
    }
}

static inline int md_identity_call(long number) {
#define CASE(name) case SYS_##name:
    switch (number) { MD_IDENTITY_CALLS(CASE) return 1; default: return 0; }
#undef CASE
}

/* Scalar credential reads share namespace translation across entry transports.
 * Mutations and pointer-bearing queries retain their existing completion owner. */
static inline int md_identity_scalar_call(long number) {
    return number==SYS_getuid || number==SYS_geteuid || number==SYS_getgid || number==SYS_getegid;
}
static inline long md_identity_scalar_result(const struct md_identity *identity,long number) {
    switch (number) {
    case SYS_getuid: return md_identity_visible_id(identity,0,identity->uid.real);
    case SYS_geteuid: return md_identity_visible_id(identity,0,identity->uid.effective);
    case SYS_getgid: return md_identity_visible_id(identity,1,identity->gid.real);
    case SYS_getegid: return md_identity_visible_id(identity,1,identity->gid.effective);
    default: return -ENOTSUP;
    }
}
#endif
