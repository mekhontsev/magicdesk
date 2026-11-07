#define _GNU_SOURCE
#include "process_limits.h"
#include "process_oom.h"
#include "task_status.h"
#include <assert.h>
#include <errno.h>
#include <sched.h>
#include <stdlib.h>
#include <unistd.h>

enum { BUCKETS=127 };
struct scope {
    struct scope *next;
    struct md_user_namespace_key key;
    uint64_t ceiling;
};
struct account {
    struct account *next, *parent;
    struct scope *scope;
    uint32_t uid;
    uint64_t count;
};
struct accounting {
    unsigned members;
    struct scope *scopes;
    struct account *accounts[BUCKETS];
};
struct limits {
    unsigned references;
    struct md_process_limits value;
    rlim_t native_nofile_max;
    rlim_t reserved_nofile;
    struct md_process_oom oom;
};
struct md_process_member {
    unsigned references;
    struct accounting *owner;
    struct account *account;
    struct limits *limits;
    int exec_check;
};
static struct scope *scope_get(struct accounting *owner, struct md_user_namespace *space, uint64_t ceiling) {
    struct md_user_namespace_key key=md_user_namespace_key(space);
    for (struct scope *s=owner->scopes;s;s=s->next)
        if (s->key.device==key.device && s->key.inode==key.inode && s->key.generation==key.generation) return s;
    struct scope *s=calloc(1,sizeof(*s));
    if (!s) return NULL;
    /* The accounting cache preserves ceilings, but must not pin namespaces
     * after their last task/handle dies and keep namespace quotas charged. */
    *s=(struct scope){.next=owner->scopes,.key=key,
        .ceiling=space ? ceiling : UINT64_MAX};
    owner->scopes=s; return s;
}
static struct account *account_get(struct accounting *owner,struct md_user_namespace *space,
        uint32_t uid,uint64_t ceiling) {
    unsigned bucket=(uid ^ md_user_namespace_inode(space))%BUCKETS;
    struct md_user_namespace_key key=md_user_namespace_key(space);
    for (struct account *a=owner->accounts[bucket];a;a=a->next)
        if (a->uid==uid && a->scope->key.device==key.device && a->scope->key.inode==key.inode
                && a->scope->key.generation==key.generation) return a;
    struct scope *s=scope_get(owner,space,ceiling);
    if (!s) return NULL;
    struct account *parent=NULL;
    if (space) {
        parent=account_get(owner,(struct md_user_namespace *)md_user_namespace_parent(space),
            md_user_namespace_creator(space),ceiling);
        if (!parent) return NULL;
    }
    struct account *a=calloc(1,sizeof(*a));
    if (!a) return NULL;
    *a=(struct account){.next=owner->accounts[bucket],.parent=parent,.scope=s,.uid=uid};
    owner->accounts[bucket]=a; return a;
}
static void charge(struct account *a,int delta) {
    for (;a;a=a->parent) {
        assert(delta>0 || a->count);
        a->count+=delta;
    }
}
static int overlimit(const struct account *a,uint64_t limit,unsigned adding) {
    for (;a;a=a->parent) {
        if (a->count>limit || adding>limit-a->count) return 1;
        limit=a->scope->ceiling;
    }
    return 0;
}
static void accounting_release(struct accounting *owner) {
    if (!owner || --owner->members) return;
    for (unsigned i=0;i<BUCKETS;++i) while (owner->accounts[i]) {
        struct account *a=owner->accounts[i]; owner->accounts[i]=a->next;
        assert(!a->count); free(a);
    }
    while (owner->scopes) {
        struct scope *s=owner->scopes; owner->scopes=s->next;
        free(s);
    }
    free(owner);
}
int md_process_limits_read(struct md_process_limits *limits) {
    if (getrlimit(RLIMIT_NPROC,&limits->nproc) || getrlimit(RLIMIT_NOFILE,&limits->nofile)) return -errno;
    return 0;
}
struct md_process_member *md_process_member_new(const struct md_identity *identity,const struct md_process_limits *limit) {
    struct md_process_member *m=calloc(1,sizeof(*m));
    if (!m) return NULL;
    m->references=1;
    m->owner=calloc(1,sizeof(*m->owner));
    m->limits=calloc(1,sizeof(*m->limits));
    if (m->owner) m->owner->members=1;
    if (m->limits) *m->limits=(struct limits){.references=1,.value=*limit,.native_nofile_max=limit->nofile.rlim_max};
    if (!m->owner || !m->limits || md_process_member_identity(m,identity)) {
        md_process_member_release(m); return NULL;
    }
    return m;
}
struct md_process_member *md_process_member_retain(struct md_process_member *m) {
    if (m) ++m->references;
    return m;
}
void md_process_member_release(struct md_process_member *m) {
    if (!m || --m->references) return;
    charge(m->account,-1);
    if (m->limits && !--m->limits->references) free(m->limits);
    accounting_release(m->owner); free(m);
}
int md_process_member_identity(struct md_process_member *m,const struct md_identity *identity) {
    struct md_user_namespace_key key=md_user_namespace_key(identity->user_namespace);
    if (!m || (m->account && m->account->uid==identity->uid.real
            && m->account->scope->key.device==key.device && m->account->scope->key.inode==key.inode
            && m->account->scope->key.generation==key.generation)) return 0;
    struct account *next=account_get(m->owner,identity->user_namespace,identity->uid.real,m->limits->value.nproc.rlim_cur);
    if (!next) return -ENOMEM;
    int changed=m->account && m->account->uid!=identity->uid.real;
    charge(next,1); charge(m->account,-1); m->account=next;
    if (changed) m->exec_check=identity->uid.real && overlimit(next,m->limits->value.nproc.rlim_cur,0);
    return 0;
}
int md_process_member_fork(struct md_process_member *parent,const struct md_identity *identity,
        unsigned long flags,struct md_process_member **out) {
    *out=NULL;
    if (!parent) return -EINVAL;
    if (overlimit(parent->account,parent->limits->value.nproc.rlim_cur,1)
            && identity->uid.real && !md_identity_capable_in(identity,NULL,CAP_SYS_RESOURCE)
            && !md_identity_capable_in(identity,NULL,CAP_SYS_ADMIN)) return -EAGAIN;
    struct md_process_member *m=calloc(1,sizeof(*m));
    if (!m) return -ENOMEM;
    m->references=1; m->owner=parent->owner; ++m->owner->members;
    if (flags&CLONE_THREAD) { m->limits=parent->limits; ++m->limits->references; }
    else {
        m->limits=malloc(sizeof(*m->limits));
        if (m->limits) {
            *m->limits=*parent->limits; m->limits->references=1;
            m->limits->reserved_nofile=0;
        }
    }
    if (!m->limits) { md_process_member_release(m); return -ENOMEM; }
    m->account=parent->account; charge(m->account,1);
    *out=m; return 0;
}
void md_process_member_forked(struct md_process_member *m) { if (m) m->exec_check=0; }
int md_process_member_exec(struct md_process_member *m) {
    if (!m || !m->exec_check) return 0;
    if (overlimit(m->account,m->limits->value.nproc.rlim_cur,0)) return -EAGAIN;
    m->exec_check=0; return 0;
}
struct md_process_limits md_process_limits_value(const struct md_process_member *m) { return m->limits->value; }
struct md_process_oom *md_process_member_oom(struct md_process_member *m) { return m ? &m->limits->oom : NULL; }
rlim_t md_process_native_nofile(const struct md_process_member *m) {
    rlim_t soft=m->limits->value.nofile.rlim_cur, ceiling=m->limits->native_nofile_max;
    return soft<ceiling ? soft : ceiling;
}
static int native_nofile(struct limits *limits,pid_t pid,rlim_t soft,rlim_t reserve) {
    struct rlimit native={.rlim_max=limits->native_nofile_max};
    if (reserve>native.rlim_max) return -EMFILE;
    native.rlim_cur=soft<native.rlim_max ? soft : native.rlim_max;
    if (native.rlim_cur<reserve) native.rlim_cur=reserve;
    return prlimit(pid,RLIMIT_NOFILE,&native,NULL) ? -errno : 0;
}
int md_process_nofile_reserve(struct md_process_member *m,pid_t pid,rlim_t minimum) {
    if (!m || !minimum) return -EINVAL;
    if (m->limits->reserved_nofile) return -EBUSY;
    int error=native_nofile(m->limits,pid,m->limits->value.nofile.rlim_cur,minimum);
    if (!error) m->limits->reserved_nofile=minimum;
    return error;
}
int md_process_nofile_release(struct md_process_member *m,pid_t pid) {
    if (!m || !m->limits->reserved_nofile) return -EINVAL;
    int error=native_nofile(m->limits,pid,m->limits->value.nofile.rlim_cur,0);
    if (!error) m->limits->reserved_nofile=0;
    return error;
}
int md_process_limit_access(const struct md_identity *actor,const struct md_identity *target,int same_group) {
    return same_group || md_identity_capable_in(actor,target->user_namespace,CAP_SYS_RESOURCE)
        || (actor->uid.real==target->uid.real && actor->uid.real==target->uid.effective
            && actor->uid.real==target->uid.saved && actor->gid.real==target->gid.real
            && actor->gid.real==target->gid.effective && actor->gid.real==target->gid.saved) ? 0 : -EPERM;
}
int md_process_limit(struct md_process_member *m,const struct md_identity *actor,
        const struct md_identity *target,int same_group,unsigned resource,
        const struct rlimit *next,struct rlimit *old,pid_t native_pid) {
    if (!m) return -ESRCH;
    struct rlimit *value=resource==RLIMIT_NPROC ? &m->limits->value.nproc
        : resource==RLIMIT_NOFILE ? &m->limits->value.nofile : NULL;
    if (resource>=RLIM_NLIMITS || (!value && next)) return -EINVAL;
    if (next && next->rlim_cur>next->rlim_max) return -EINVAL;
    int access=md_process_limit_access(actor,target,same_group);
    if (access) return access;
    if (!value) {
        struct rlimit native;
        int error=(int)md_task_limit(native_pid ? native_pid : getpid(),resource,&native);
        if (!error && old) *old=native;
        return error;
    }
    if (next && resource==RLIMIT_NOFILE && next->rlim_max>MD_PROCESS_NOFILE_MAX) return -EPERM;
    if (next && next->rlim_max>value->rlim_max
            && !md_identity_capable_in(actor,NULL,CAP_SYS_RESOURCE)) return -EPERM;
    if (next && resource==RLIMIT_NOFILE) {
        /* Retain the executor's hard ceiling so a guest hard-limit reduction
         * remains reversible. The kernel still enforces every FD allocation. */
        int error=native_nofile(m->limits,native_pid,next->rlim_cur,m->limits->reserved_nofile);
        if (error) return error;
    }
    if (old) *old=*value;
    if (next) *value=*next;
    return 0;
}
