#ifndef MD_PROCESS_LIMITS_H
#define MD_PROCESS_LIMITS_H
#include "guest_identity.h"
#include <sys/resource.h>

/* Supervisor-owned task accounting. Registry references retain a zombie's
 * charge until reaping; private runtime helpers never acquire membership. */
struct md_process_member;
/* Guest policy, not a promise of this many physical Android descriptors. */
#define MD_PROCESS_NOFILE_MAX (1024U * 1024U)
struct md_process_limits {
    struct rlimit nproc, nofile;
};
int md_process_limits_read(struct md_process_limits *);
struct md_process_member *md_process_member_new(const struct md_identity *, const struct md_process_limits *);
struct md_process_member *md_process_member_retain(struct md_process_member *);
void md_process_member_release(struct md_process_member *);
int md_process_member_identity(struct md_process_member *, const struct md_identity *);
/* Reserve before native clone; transfer on birth, release on rejection/death. */
int md_process_member_fork(struct md_process_member *, const struct md_identity *,
    unsigned long flags, struct md_process_member **);
void md_process_member_forked(struct md_process_member *);
int md_process_member_exec(struct md_process_member *);
int md_process_limit_access(const struct md_identity *actor,const struct md_identity *target,int same_group);
int md_process_limit(struct md_process_member *, const struct md_identity *actor,
    const struct md_identity *target, int same_group, unsigned resource,
    const struct rlimit *next, struct rlimit *old, pid_t native_pid);
struct md_process_limits md_process_limits_value(const struct md_process_member *);
struct md_process_oom;
/* Borrowed on the supervisor thread; the group shares it across its threads. */
struct md_process_oom *md_process_member_oom(struct md_process_member *);
/* Effective kernel ceiling, including the executor's retained hard limit. */
rlim_t md_process_native_nofile(const struct md_process_member *);
/* Private helper capacity while the caller holds every guest thread in this
 * group. Guest limits stay visible and updates remain committed on release. */
int md_process_nofile_reserve(struct md_process_member *,pid_t,rlim_t);
int md_process_nofile_release(struct md_process_member *,pid_t);
#endif
