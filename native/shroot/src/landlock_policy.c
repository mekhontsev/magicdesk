#define _GNU_SOURCE
#include "landlock_policy.h"
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

struct inode_pin { atomic_uint refs; int fd; };
struct rule { struct md_landlock_key key; uint64_t allowed; struct inode_pin *pin; };
static void pin_release(struct inode_pin *pin) {
    if (pin && atomic_fetch_sub(&pin->refs,1)==1) { close(pin->fd); free(pin); }
}
struct md_landlock_ruleset {
    atomic_uint refs;
    uint64_t handled;
    size_t count;
    struct rule *rules;
};
struct md_landlock_policy {
    atomic_uint refs;
    unsigned depth;
    struct md_landlock_policy *parent;
    struct md_landlock_ruleset *rules;
};
struct md_landlock_ruleset *md_landlock_ruleset_new(uint64_t handled) {
    if (!handled || (handled&~MD_LL_ALL)) { errno=handled ? EINVAL : ENOMSG; return NULL; }
    struct md_landlock_ruleset *r=calloc(1,sizeof(*r));
    if (r) { atomic_init(&r->refs,1); r->handled=handled; }
    return r;
}
struct md_landlock_ruleset *md_landlock_ruleset_retain(struct md_landlock_ruleset *r) {
    if (r) atomic_fetch_add(&r->refs,1); return r;
}
void md_landlock_ruleset_release(struct md_landlock_ruleset *r) {
    if (!r || atomic_fetch_sub(&r->refs,1)!=1) return;
    for (size_t i=0;i<r->count;++i) {
        md_inode_lifetime_release(r->rules[i].key.store); pin_release(r->rules[i].pin);
    }
    free(r->rules); free(r);
}
int md_landlock_rule_add(struct md_landlock_ruleset *r,const struct md_landlock_key *key,uint64_t allowed,int native_fd) {
    if (!allowed) return -ENOMSG;
    if (allowed&~r->handled) return -EINVAL;
    for (size_t i=0;i<r->count;++i) {
        struct rule *rule=&r->rules[i];
        if (md_inode_lifetime_same(rule->key.store,key->store) && !strcmp(rule->key.object,key->object)) {
            rule->allowed|=allowed; return 0;
        }
    }
    if (r->count>=SIZE_MAX/sizeof(struct rule)-1) return -ENOMEM;
    struct inode_pin *pin=NULL;
    if (native_fd>=0) {
        pin=malloc(sizeof(*pin));
        if (!pin) return -ENOMEM;
        pin->fd=fcntl(native_fd,F_DUPFD_CLOEXEC,3); atomic_init(&pin->refs,1);
        if (pin->fd<0) { int error=-errno; free(pin); return error; }
    }
    struct rule *next=realloc(r->rules,(r->count+1)*sizeof(*next));
    if (!next) { pin_release(pin); return -ENOMEM; }
    r->rules=next;
    next[r->count]=(struct rule){.key=*key,.allowed=allowed,.pin=pin};
    next[r->count++].key.store=md_inode_lifetime_retain(key->store);
    return 0;
}
struct md_landlock_policy *md_landlock_retain(struct md_landlock_policy *p) {
    if (p) atomic_fetch_add(&p->refs,1); return p;
}
void md_landlock_release(struct md_landlock_policy *p) {
    while (p && atomic_fetch_sub(&p->refs,1)==1) {
        struct md_landlock_policy *parent=p->parent;
        md_landlock_ruleset_release(p->rules); free(p); p=parent;
    }
}
struct md_landlock_policy *md_landlock_restrict(const struct md_landlock_policy *parent,
        const struct md_landlock_ruleset *r) {
    if (parent && parent->depth>=MD_LANDLOCK_LAYERS) { errno=E2BIG; return NULL; }
    struct md_landlock_policy *p=calloc(1,sizeof(*p));
    if (!p) return NULL;
    atomic_init(&p->refs,1);
    p->rules=md_landlock_ruleset_new(r->handled);
    if (!p->rules) { free(p); return NULL; }
    if (r->count) {
        p->rules->rules=malloc(r->count*sizeof(struct rule));
        if (!p->rules->rules) { md_landlock_release(p); return NULL; }
        memcpy(p->rules->rules,r->rules,r->count*sizeof(struct rule));
        for (size_t i=0;i<r->count;++i) {
            md_inode_lifetime_retain(r->rules[i].key.store);
            if (r->rules[i].pin) atomic_fetch_add(&r->rules[i].pin->refs,1);
        }
        p->rules->count=r->count;
    }
    p->parent=md_landlock_retain((struct md_landlock_policy *)parent);
    p->depth=parent ? parent->depth+1 : 1;
    return p;
}
int md_landlock_check(const struct md_landlock_policy *p,uint64_t access,
        int (*matches)(void *,const struct md_landlock_key *),void *context,const uint64_t *inherited) {
    for (unsigned layer=0;p;p=p->parent,++layer) {
        uint64_t missing=access&p->rules->handled&~(inherited ? inherited[layer] : 0);
        for (size_t i=0;missing && i<p->rules->count;++i) {
            const struct rule *r=&p->rules->rules[i];
            if (!(missing&r->allowed)) continue;
            int match=matches(context,&r->key);
            if (match<0) return match;
            if (match) missing&=~r->allowed;
        }
        if (missing) return -EACCES;
    }
    return 0;
}
int md_landlock_collect(const struct md_landlock_policy *p,
        int (*matches)(void *,const struct md_landlock_key *),void *context,uint64_t *grants) {
    for (unsigned layer=0;p;p=p->parent,++layer) {
        for (size_t i=0;i<p->rules->count;++i) {
            const struct rule *r=&p->rules->rules[i];
            if (!(r->allowed&~grants[layer])) continue;
            int match=matches(context,&r->key);
            if (match<0) return match;
            if (match) grants[layer]|=r->allowed;
        }
    }
    return 0;
}
uint64_t md_landlock_open_access(int flags,mode_t kind) {
    if (flags&O_PATH) return 0;
    if (S_ISDIR(kind)) return MD_LL_READ_DIR;
    return ((flags&O_ACCMODE)!=O_WRONLY ? MD_LL_READ_FILE : 0)
        | ((flags&O_ACCMODE)!=O_RDONLY ? MD_LL_WRITE_FILE : 0);
}
uint64_t md_landlock_make_access(mode_t kind) {
    switch (kind&S_IFMT) {
    case S_IFREG: return MD_LL_MAKE_REG;
    case S_IFDIR: return MD_LL_MAKE_DIR;
    case S_IFLNK: return MD_LL_MAKE_SYM;
    case S_IFSOCK: return MD_LL_MAKE_SOCK;
    case S_IFIFO: return MD_LL_MAKE_FIFO;
    case S_IFCHR: return MD_LL_MAKE_CHAR;
    case S_IFBLK: return MD_LL_MAKE_BLOCK;
    default: return 0;
    }
}
