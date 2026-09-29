#ifndef MD_LAUNCH_IDENTITY_H
#define MD_LAUNCH_IDENTITY_H
/* The caller selects authority. No set-id executable or per-operation elevation. */
static inline int md_launch_identity(long uid, long euid, long gid, long egid) {
    return (uid == 2000 || uid == 0) && uid == euid && gid == egid;
}
#endif
