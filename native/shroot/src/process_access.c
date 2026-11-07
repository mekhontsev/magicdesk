#include "process_access.h"
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

struct md_process_access {
    atomic_uint references;
    atomic_int dumpable;
    struct md_user_namespace *owner;
};
struct md_process_access *md_process_access_new(struct md_user_namespace *owner) {
    struct md_process_access *p=malloc(sizeof(*p));
    if (p) {
        atomic_init(&p->references,1); atomic_init(&p->dumpable,1);
        p->owner=md_user_namespace_retain(owner);
    }
    return p;
}
struct md_process_access *md_process_access_retain(struct md_process_access *p) {
    if (p) atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed);
    return p;
}
struct md_process_access *md_process_access_fork(struct md_process_access *p,int shared) {
    if (shared) return md_process_access_retain(p);
    struct md_process_access *next=md_process_access_new(p ? p->owner : NULL);
    if (next) atomic_store_explicit(&next->dumpable,md_process_dumpable(p),memory_order_relaxed);
    return next;
}
void md_process_access_release(struct md_process_access *p) {
    if (p && atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)==1) {
        md_user_namespace_release(p->owner); free(p);
    }
}
int md_process_dumpable(const struct md_process_access *p) {
    return p ? atomic_load_explicit(&p->dumpable,memory_order_relaxed) : 1;
}
int md_process_set_dumpable(struct md_process_access *p,unsigned long value) {
    if (value>1) return -EINVAL;
    if (!p) return -ESRCH;
    atomic_store_explicit(&p->dumpable,(int)value,memory_order_relaxed);
    return 0;
}
int md_process_may_inspect(const struct md_identity *from,const struct md_identity *to,int filesystem,
        const struct md_process_access *access) {
    return md_identity_may_inspect(from,to,filesystem)
        && (md_process_dumpable(access)==1 || md_identity_capable_in(from,access->owner,CAP_SYS_PTRACE));
}
void md_process_file_owner(const struct md_identity *identity,const struct md_process_access *access,
        unsigned mode,uint32_t native_uid,uint32_t *uid,uint32_t *gid) {
    if (mode==(S_IFDIR|0555) || (md_process_dumpable(access)==1 && native_uid==getuid())) {
        *uid=identity->uid.effective; *gid=identity->gid.effective;
    } else {
        const struct md_user_namespace *owner=access ? access->owner : identity->user_namespace;
        *uid=md_user_namespace_into(owner,0,0); *gid=md_user_namespace_into(owner,1,0);
        if (*uid==UINT32_MAX) *uid=0;
        if (*gid==UINT32_MAX) *gid=0;
    }
}
static int capability_subset(const struct md_identity *before,const struct md_identity *after) {
    if (before->user_namespace==after->user_namespace)
        return !(after->caps.permitted&~before->caps.permitted);
    for (const struct md_user_namespace *n=after->user_namespace;n;n=md_user_namespace_parent(n))
        if (md_user_namespace_parent(n)==before->user_namespace
                && md_user_namespace_creator(n)==before->uid.effective) return 1;
    return 0;
}
void md_process_access_credentials(struct md_process_access *p,const struct md_identity *before,
        const struct md_identity *after) {
    if (before->uid.effective!=after->uid.effective || before->uid.fs!=after->uid.fs
            || before->gid.effective!=after->gid.effective || before->gid.fs!=after->gid.fs
            || !capability_subset(before,after))
        md_process_set_dumpable(p,0);
}
