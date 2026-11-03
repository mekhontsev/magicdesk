#define _GNU_SOURCE
#include "pid_namespace.h"
#include "namespace_identity.h"
#include "guest_identity.h"
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <linux/reboot.h>

enum { PID_BUCKETS=127 };
struct pid_number {
    struct pid_number *next;
    struct md_pid_identity *identity;
    struct md_pid_namespace *space;
    pid_t number;
};
struct md_pid_namespace {
    atomic_uint references;
    pthread_mutex_t lock;
    struct md_pid_namespace *parent;
    struct md_pid_namespace *registry_next;
    struct md_user_namespace *owner;
    uint64_t inode;
    unsigned depth;
    pid_t next;
    atomic_int dead, reboot_signal;
    int initialized;
    struct pid_number *numbers[PID_BUCKETS];
};
struct md_pid_identity {
    atomic_uint references;
    struct md_pid_namespace *space;
    atomic_int native, group, live, subreaper, reaped;
    unsigned count;
    struct pid_number numbers[];
};
int md_pid_namespace_reboot(struct md_pid_namespace *n,const struct md_identity *id,
        unsigned magic,unsigned magic2,unsigned command) {
    if (!n || !md_identity_capable_in(id,n->owner,CAP_SYS_BOOT)) return -EPERM;
    if (magic!=LINUX_REBOOT_MAGIC1 || (magic2!=LINUX_REBOOT_MAGIC2
            && magic2!=LINUX_REBOOT_MAGIC2A && magic2!=LINUX_REBOOT_MAGIC2B
            && magic2!=LINUX_REBOOT_MAGIC2C)) return -EINVAL;
    int signal;
    switch (command) {
        case LINUX_REBOOT_CMD_RESTART: case LINUX_REBOOT_CMD_RESTART2: signal=SIGHUP; break;
        case LINUX_REBOOT_CMD_HALT: case LINUX_REBOOT_CMD_POWER_OFF: signal=SIGINT; break;
        default: return -EINVAL;
    }
    if (atomic_load(&n->dead)) return -ESRCH;
    return signal;
}
void md_pid_namespace_reboot_started(struct md_pid_namespace *n,int signal) {
    atomic_store(&n->reboot_signal,signal);
}
int md_pid_identity_reboot_signal(const struct md_pid_identity *id) {
    return id && md_pid_identity_number(id,id->space)==1 ? atomic_load(&id->space->reboot_signal) : 0;
}
static pthread_mutex_t registry_lock=PTHREAD_MUTEX_INITIALIZER;
static struct md_pid_namespace *registry;
unsigned md_pid_identity_export(const struct md_pid_identity *id, struct md_pid_number_value *out) {
    if (!id) return 0;
    for (unsigned i=0;i<id->count;++i)
        out[i]=(struct md_pid_number_value){md_pid_namespace_inode(id->numbers[i].space),id->numbers[i].number,0};
    return id->count;
}
int md_pid_numbers_visible(const void *data,size_t size,const struct md_pid_namespace *space,pid_t native) {
    if (size%sizeof(struct md_pid_number_value) || size>MD_PID_LEVELS*sizeof(struct md_pid_number_value)) return -EPROTO;
    if (!space || native<=0) return native;
    uint64_t inode=md_pid_namespace_inode(space);
    for (size_t offset=0;offset<size;offset+=sizeof(struct md_pid_number_value)) {
        struct md_pid_number_value value;
        memcpy(&value,(const char *)data+offset,sizeof(value));
        if (value.reserved || value.number<=0) return -EPROTO;
        if (value.namespace==inode) return value.number;
    }
    return 0;
}
struct md_pid_namespace *md_pid_namespace_new(struct md_pid_namespace *parent, struct md_user_namespace *owner) {
    if (md_pid_namespace_depth(parent)>=MD_PID_LEVELS) { errno=ENOSPC; return NULL; }
    if (parent && !md_user_namespace_contains(parent->owner,owner)) { errno=EINVAL; return NULL; }
    struct md_pid_namespace *n=calloc(1,sizeof(*n));
    if (!n) return NULL;
    int error=pthread_mutex_init(&n->lock,NULL);
    if (error) { free(n); errno=error; return NULL; }
    error=md_namespace_identity_new(&n->inode);
    if (error) { pthread_mutex_destroy(&n->lock); free(n); errno=-error; return NULL; }
    atomic_init(&n->references,1); atomic_init(&n->dead,0); atomic_init(&n->reboot_signal,0);
    n->parent=md_pid_namespace_retain(parent); n->owner=md_user_namespace_retain(owner);
    n->depth=md_pid_namespace_depth(parent)+1; n->next=1;
    pthread_mutex_lock(&registry_lock);
    n->registry_next=registry; registry=n;
    pthread_mutex_unlock(&registry_lock);
    return n;
}
struct md_pid_namespace *md_pid_namespace_retain(struct md_pid_namespace *n) {
    if (n) atomic_fetch_add_explicit(&n->references,1,memory_order_relaxed);
    return n;
}
void md_pid_namespace_release(struct md_pid_namespace *n) {
    if (!n || atomic_fetch_sub_explicit(&n->references,1,memory_order_acq_rel)!=1) return;
    pthread_mutex_lock(&registry_lock);
    struct md_pid_namespace **slot=&registry;
    while (*slot!=n) slot=&(*slot)->registry_next;
    *slot=n->registry_next;
    pthread_mutex_unlock(&registry_lock);
    for (unsigned i=0;i<PID_BUCKETS;++i) assert(!n->numbers[i]);
    md_pid_namespace_release(n->parent); md_user_namespace_release(n->owner);
    pthread_mutex_destroy(&n->lock); free(n);
}
struct md_pid_namespace *md_pid_namespace_by_inode(uint64_t inode) {
    struct md_pid_namespace *result=NULL;
    pthread_mutex_lock(&registry_lock);
    for (struct md_pid_namespace *n=registry;n;n=n->registry_next) if (n->inode==inode) {
        unsigned count=atomic_load_explicit(&n->references,memory_order_relaxed);
        while (count && count<UINT_MAX) {
            if (atomic_compare_exchange_weak_explicit(&n->references,&count,count+1,
                    memory_order_acquire,memory_order_relaxed)) { result=n; break; }
        }
        break;
    }
    pthread_mutex_unlock(&registry_lock);
    return result;
}
struct md_pid_namespace *md_pid_namespace_parent(const struct md_pid_namespace *n) { return n ? n->parent : NULL; }
const struct md_user_namespace *md_pid_namespace_owner(const struct md_pid_namespace *n) { return n ? n->owner : NULL; }
uint64_t md_pid_namespace_inode(const struct md_pid_namespace *n) { return n ? n->inode : UINT64_C(0xeffffffc); }
unsigned md_pid_namespace_depth(const struct md_pid_namespace *n) { return n ? n->depth : 0; }
int md_pid_namespace_dead(const struct md_pid_namespace *n) { return n && atomic_load_explicit(&n->dead,memory_order_acquire); }
int md_pid_namespace_initialized(struct md_pid_namespace *n) {
    if (!n) return 1;
    pthread_mutex_lock(&n->lock);
    int value=n->initialized;
    pthread_mutex_unlock(&n->lock); return value;
}
int md_pid_namespace_contains(const struct md_pid_namespace *parent, const struct md_pid_namespace *child) {
    while (child && child!=parent) child=child->parent;
    return child==parent;
}
static struct pid_number **slot(struct md_pid_namespace *n,pid_t number) {
    struct pid_number **p=&n->numbers[(unsigned)number%PID_BUCKETS];
    while (*p && (*p)->number!=number) p=&(*p)->next;
    return p;
}
static int reserve(struct md_pid_namespace *n, struct pid_number *number,pid_t selected) {
    pthread_mutex_lock(&n->lock);
    int error=0;
    if (md_pid_namespace_dead(n)) error=-ENOMEM;
    if (!error && selected && selected!=1 && !n->initialized) error=-EINVAL;
    if (!error && selected && *slot(n,selected)) error=-EEXIST;
    pid_t start=selected ? selected : !n->initialized && !*slot(n,1) ? 1 : n->next<2 ? 2 : n->next, next=start;
    if (!error) {
        do {
            if (!*slot(n,next)) break;
            next=next==MD_PID_MAX-1 ? 2 : next+1;
        } while (next!=start);
        if (*slot(n,next)) error=-EAGAIN;
    }
    if (!error) {
        number->space=n; number->number=next; *slot(n,next)=number;
        if (!selected) n->next=next==MD_PID_MAX-1 ? 2 : next+1;
    }
    pthread_mutex_unlock(&n->lock); return error;
}
int md_pid_identity_prepare(struct md_pid_namespace *space, struct md_pid_identity **out) {
    return md_pid_identity_prepare_selected(space,NULL,NULL,0,out);
}
int md_pid_identity_prepare_selected(struct md_pid_namespace *space,const struct md_identity *id,
        const pid_t *selected,unsigned count,struct md_pid_identity **out) {
    *out=NULL;
    if (count>md_pid_namespace_depth(space) || (count && (!selected || !id))) return -EINVAL;
    if (!space) return 0;
    struct md_pid_identity *p=calloc(1,sizeof(*p)+space->depth*sizeof(*p->numbers));
    if (!p) return -ENOMEM;
    atomic_init(&p->references,1); atomic_init(&p->native,0); atomic_init(&p->group,0); atomic_init(&p->live,0);
    atomic_init(&p->subreaper,0);
    atomic_init(&p->reaped,0);
    p->space=md_pid_namespace_retain(space);
    for (struct md_pid_namespace *n=space;n;n=n->parent) {
        struct pid_number *number=p->numbers+p->count; number->identity=p;
        pid_t requested=p->count<count ? selected[p->count] : 0;
        int error=0;
        if (p->count<count) {
            if (requested<1 || requested>=MD_PID_MAX) error=-EINVAL;
            else if (!md_identity_capable_in(id,n->owner,CAP_CHECKPOINT_RESTORE)
                    && !md_identity_capable_in(id,n->owner,CAP_SYS_ADMIN)) error=-EPERM;
        }
        if (!error) error=reserve(n,number,requested);
        if (error) { md_pid_identity_release(p); return error; }
        ++p->count;
    }
    *out=p; return 0;
}
int md_pid_identity_publish(struct md_pid_identity *p, pid_t native, pid_t group) {
    if (!p) return 0;
    if (native<=0 || group<=0 || atomic_load_explicit(&p->native,memory_order_relaxed)) return -EINVAL;
    for (unsigned i=0;i<p->count;++i) {
        struct md_pid_namespace *n=p->numbers[i].space;
        if (p->numbers[i].number==1) {
            pthread_mutex_lock(&n->lock); n->initialized=1; pthread_mutex_unlock(&n->lock);
        }
    }
    atomic_store_explicit(&p->group,group,memory_order_relaxed);
    atomic_store_explicit(&p->native,native,memory_order_release);
    atomic_store_explicit(&p->live,1,memory_order_release); return 0;
}
struct md_pid_identity *md_pid_identity_retain(struct md_pid_identity *p) {
    if (p) atomic_fetch_add_explicit(&p->references,1,memory_order_relaxed);
    return p;
}
static struct md_pid_identity *try_retain(struct md_pid_identity *p) {
    unsigned count=atomic_load_explicit(&p->references,memory_order_relaxed);
    while (count && count<UINT_MAX) {
        if (atomic_compare_exchange_weak_explicit(&p->references,&count,count+1,
                memory_order_acquire,memory_order_relaxed)) return p;
    }
    return NULL;
}
static void unreserve(struct md_pid_identity *p) {
    for (unsigned i=0;i<p->count;++i) {
        struct pid_number *number=p->numbers+i; struct md_pid_namespace *n=number->space;
        pthread_mutex_lock(&n->lock);
        struct pid_number **link=slot(n,number->number);
        if (*link==number) *link=number->next;
        pthread_mutex_unlock(&n->lock);
    }
}
void md_pid_identity_release(struct md_pid_identity *p) {
    if (!p || atomic_fetch_sub_explicit(&p->references,1,memory_order_acq_rel)!=1) return;
    unreserve(p);
    md_pid_namespace_release(p->space); free(p);
}
struct md_pid_namespace *md_pid_identity_namespace(const struct md_pid_identity *p) { return p ? p->space : NULL; }
pid_t md_pid_identity_native(const struct md_pid_identity *p) { return p ? atomic_load_explicit(&p->native,memory_order_acquire) : 0; }
pid_t md_pid_identity_group(const struct md_pid_identity *p) { return p ? atomic_load_explicit(&p->group,memory_order_acquire) : 0; }
pid_t md_pid_identity_number(const struct md_pid_identity *p, const struct md_pid_namespace *n) {
    if (!p) return 0;
    if (!n) return md_pid_identity_native(p);
    for (unsigned i=0;i<p->count;++i) if (p->numbers[i].space==n) return p->numbers[i].number;
    return 0;
}
int md_pid_identity_live(const struct md_pid_identity *p) { return p && atomic_load_explicit(&p->live,memory_order_acquire); }
int md_pid_identity_subreaper(const struct md_pid_identity *p) { return p && atomic_load_explicit(&p->subreaper,memory_order_relaxed); }
void md_pid_identity_set_subreaper(struct md_pid_identity *p,int value) {
    if (p) atomic_store_explicit(&p->subreaper,!!value,memory_order_relaxed);
}
int md_pid_identity_exit(struct md_pid_identity *p) {
    if (!p || !atomic_exchange_explicit(&p->live,0,memory_order_acq_rel)) return 0;
    struct md_pid_namespace *n=p->space;
    pthread_mutex_lock(&n->lock);
    struct pid_number *init=*slot(n,1);
    pid_t group=md_pid_identity_group(p);
    int last=init && md_pid_identity_group(init->identity)==group;
    for (unsigned i=0;last && i<PID_BUCKETS;++i)
        for (struct pid_number *other=n->numbers[i];other;other=other->next)
            if (md_pid_identity_live(other->identity) && md_pid_identity_group(other->identity)==group) {
                last=0; break;
            }
    if (last) atomic_store_explicit(&n->dead,1,memory_order_release);
    pthread_mutex_unlock(&n->lock); return last;
}
void md_pid_identity_reap(struct md_pid_identity *p) {
    if (p && !md_pid_identity_live(p)) {
        atomic_store_explicit(&p->reaped,1,memory_order_release);
        unreserve(p);
    }
}
void md_pid_identity_exec(struct md_pid_identity *thread,struct md_pid_identity *group) {
    if (!thread || !group || thread==group) return;
    assert(thread->space==group->space && md_pid_identity_group(thread)==md_pid_identity_native(group));
    atomic_store_explicit(&group->reaped,0,memory_order_release);
    atomic_store_explicit(&group->live,1,memory_order_release);
    atomic_store_explicit(&thread->live,0,memory_order_release);
}
struct md_pid_identity *md_pid_namespace_lookup(struct md_pid_namespace *n,pid_t visible) {
    if (!n || visible<=0) return NULL;
    pthread_mutex_lock(&n->lock);
    struct pid_number *number=*slot(n,visible);
    struct md_pid_identity *p=number && md_pid_identity_native(number->identity)
        && !atomic_load_explicit(&number->identity->reaped,memory_order_acquire)
        ? try_retain(number->identity) : NULL;
    pthread_mutex_unlock(&n->lock); return p;
}
struct md_pid_identity *md_pid_namespace_find(struct md_pid_namespace *n,pid_t native) {
    if (!n || native<=0) return NULL;
    struct md_pid_identity *p=NULL;
    pthread_mutex_lock(&n->lock);
    for (unsigned i=0;i<PID_BUCKETS && !p;++i)
        for (struct pid_number *number=n->numbers[i];number;number=number->next)
            if (md_pid_identity_native(number->identity)==native
                    && !atomic_load_explicit(&number->identity->reaped,memory_order_acquire)) {
                p=try_retain(number->identity); break;
            }
    pthread_mutex_unlock(&n->lock); return p;
}
