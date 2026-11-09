#include "signal_pending.h"
#include <errno.h>
#include <stdlib.h>

struct slot { siginfo_t info; uint64_t token; int occupied; };
struct group { unsigned references; struct slot pending; struct md_signal_origin child; int child_dirty; };
struct wait_queue { unsigned references; uint64_t domain,revision; };
static uint64_t next_domain;
struct md_signal_pending { struct slot private; struct group *group; struct wait_queue *wait;
    struct md_signal_origin child; };
static struct wait_queue *wait_new(void) {
    struct wait_queue *q=calloc(1,sizeof(*q));
    if (q) { q->references=1; q->domain=++next_domain; }
    return q;
}
struct md_signal_pending *md_signal_pending_new(void) {
    return md_signal_pending_fork(NULL,0,0);
}
struct md_signal_pending *md_signal_pending_fork(struct md_signal_pending *from,int shared,int handlers) {
    struct md_signal_pending *p=calloc(1,sizeof(*p));
    if (!p) return NULL;
    if (shared && from) { p->group=from->group; ++p->group->references; }
    else {
        p->group=calloc(1,sizeof(*p->group));
        if (!p->group) { free(p); return NULL; }
        p->group->references=1;
    }
    if (handlers && from) { p->wait=from->wait; ++p->wait->references; }
    else p->wait=wait_new();
    if (!p->wait) { if (!--p->group->references) free(p->group); free(p); return NULL; }
    return p;
}
int md_signal_pending_exec(struct md_signal_pending *p) {
    if (!p) return 0;
    /* de_thread removes same-group peers before exec unshares sighand. Only
     * a CLONE_SIGHAND peer in another process requires a new wait queue. */
    if (p->wait->references<=p->group->references) return 0;
    struct wait_queue *next=wait_new();
    if (!next) return -ENOMEM;
    if (!--p->wait->references) free(p->wait);
    p->wait=next; return 0;
}
void md_signal_pending_release(struct md_signal_pending *p) {
    if (!p) return;
    if (!--p->group->references) free(p->group);
    if (!--p->wait->references) free(p->wait);
    free(p);
}
int md_signal_pending_put(struct md_signal_pending *p,enum md_signal_scope scope,const siginfo_t *info) {
    if (!p || !info || info->si_signo<=0 || info->si_signo>=32
            || (scope!=MD_SIGNAL_PRIVATE && scope!=MD_SIGNAL_SHARED)) return -EINVAL;
    struct slot *slot=scope==MD_SIGNAL_SHARED ? &p->group->pending : &p->private;
    if (slot->occupied) return slot->info.si_signo==info->si_signo ? 0 : -EINVAL;
    ++slot->token;
    if (!slot->token) ++slot->token;
    slot->occupied=1; slot->info=*info;
    ++p->wait->revision;
    return 1;
}
int md_signal_pending_peek(const struct md_signal_pending *p,struct md_signal_pending_value *out) {
    if (!p || !out) return 0;
    const struct slot *slot=p->private.occupied ? &p->private : &p->group->pending;
    if (!slot->occupied) return 0;
    *out=(struct md_signal_pending_value){.info=slot->info,.token=slot->token,
        .scope=slot==&p->private ? MD_SIGNAL_PRIVATE : MD_SIGNAL_SHARED};
    return 1;
}
int md_signal_pending_take(struct md_signal_pending *p,const struct md_signal_pending_value *value) {
    if (!p || !value || (value->scope!=MD_SIGNAL_PRIVATE && value->scope!=MD_SIGNAL_SHARED)) return 0;
    struct slot *slot=value->scope==MD_SIGNAL_SHARED ? &p->group->pending : &p->private;
    if (!slot->occupied || slot->token!=value->token) return 0;
    slot->occupied=0;
    return 1;
}
int md_signal_pending_has(const struct md_signal_pending *p,enum md_signal_scope scope) {
    return p && (scope==MD_SIGNAL_PRIVATE ? p->private.occupied
        : scope==MD_SIGNAL_SHARED ? p->group->pending.occupied : 0);
}
void md_signal_pending_clear(struct md_signal_pending *p,int shared) {
    if (!p) return;
    p->private.occupied=0;
    if (shared) p->group->pending.occupied=0;
}
uint64_t md_signal_pending_domain(const struct md_signal_pending *p) { return p ? p->wait->domain : 0; }
uint64_t md_signal_pending_revision(const struct md_signal_pending *p) { return p ? p->wait->revision : 0; }
struct md_signal_origin *md_signal_pending_child(struct md_signal_pending *p,enum md_signal_scope scope) {
    return !p ? NULL : scope==MD_SIGNAL_PRIVATE ? &p->child
        : scope==MD_SIGNAL_SHARED ? &p->group->child : NULL;
}
const struct md_signal_origin *md_signal_pending_origin(struct md_signal_pending *p,const siginfo_t *info) {
    if (!p) return NULL;
    if (md_signal_origin_matches(&p->child,info)) return &p->child;
    return md_signal_origin_matches(&p->group->child,info) ? &p->group->child : NULL;
}
void md_signal_pending_consumed(struct md_signal_pending *p,const siginfo_t *info) {
    if (!p) return;
    if (md_signal_origin_matches(&p->child,info)) p->child.valid=0;
    else if (md_signal_origin_matches(&p->group->child,info)) p->group->child.valid=0;
}
void md_signal_pending_child_changed(struct md_signal_pending *p) { if (p) p->group->child_dirty=1; }
int md_signal_pending_child_dirty(const struct md_signal_pending *p) { return p && p->group->child_dirty; }
void md_signal_pending_child_observed(struct md_signal_pending *p) { if (p) p->group->child_dirty=0; }
