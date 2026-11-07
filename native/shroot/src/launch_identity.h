#ifndef MD_LAUNCH_IDENTITY_H
#define MD_LAUNCH_IDENTITY_H
#include "host_policy.h"
/* The caller selects authority. No set-id executable or per-operation elevation. */
static inline int md_launch_identity(long uid, long euid, long gid, long egid) {
    if ((uid == 2000 || uid == 0) && uid == euid && gid == egid) return 1;
    return md_app_uid_admit(uid, euid, gid, egid);
}
#endif
