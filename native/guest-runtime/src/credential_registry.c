#include "credential_registry.h"
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum { BUCKETS = 127 };
struct entry { struct entry *next; pid_t tid, tgid; struct md_identity value; };
struct md_credentials { pthread_mutex_t lock; pid_t owner; struct entry *entries[BUCKETS]; };
struct md_credentials *md_credentials_create(void) {
    struct md_credentials *r = calloc(1, sizeof(*r));
    if (!r) return NULL;
    if (pthread_mutex_init(&r->lock, NULL)) { free(r); return NULL; }
    r->owner = getpid(); return r;
}
void md_credentials_destroy(struct md_credentials *r) {
    if (!r) return;
    for (unsigned i = 0; i < BUCKETS; i++) while (r->entries[i]) {
        struct entry *e = r->entries[i]; r->entries[i] = e->next;
        md_identity_release(&e->value); free(e);
    }
    pthread_mutex_destroy(&r->lock); free(r);
}
int md_credentials_publish(struct md_credentials *r, pid_t tid, pid_t tgid, const struct md_identity *v) {
    if (!r) return 0;
    if (tid <= 0 || tgid <= 0) return -EINVAL;
    unsigned bucket = (unsigned)tid % BUCKETS;
    pthread_mutex_lock(&r->lock);
    struct entry *e = r->entries[bucket];
    while (e && e->tid != tid) e = e->next;
    if (!e) {
        e = calloc(1, sizeof(*e));
        if (!e) { pthread_mutex_unlock(&r->lock); return -ENOMEM; }
        e->tid = tid; e->next = r->entries[bucket]; r->entries[bucket] = e;
    }
    if (e->tgid == tgid && !memcmp(&e->value.uid, &v->uid, sizeof(v->uid))
            && !memcmp(&e->value.gid, &v->gid, sizeof(v->gid))
            && !memcmp(&e->value.caps, &v->caps, sizeof(v->caps))
            && e->value.no_new_privs == v->no_new_privs && e->value.groups == v->groups) {
        pthread_mutex_unlock(&r->lock); return 0;
    }
    struct md_identity next = md_identity_copy(v);
    md_identity_release(&e->value); e->value = next; e->tgid = tgid;
    pthread_mutex_unlock(&r->lock); return 0;
}
void md_credentials_forget(struct md_credentials *r, pid_t tid) {
    if (!r || tid <= 0) return;
    pthread_mutex_lock(&r->lock);
    struct entry **slot = &r->entries[(unsigned)tid % BUCKETS];
    while (*slot && (*slot)->tid != tid) slot = &(*slot)->next;
    struct entry *e = *slot;
    if (e) *slot = e->next;
    pthread_mutex_unlock(&r->lock);
    if (e) { md_identity_release(&e->value); free(e); }
}
int md_credentials_read(struct md_credentials *r, pid_t tid, pid_t peer, struct md_identity *out) {
    if (!r || tid <= 0) return -ESRCH;
    pthread_mutex_lock(&r->lock);
    struct entry *e = r->entries[(unsigned)tid % BUCKETS];
    while (e && e->tid != tid) e = e->next;
    int error = !e ? -ESRCH : peer && peer != r->owner && peer != e->tgid ? -EPERM : 0;
    if (!error) *out = md_identity_copy(&e->value);
    pthread_mutex_unlock(&r->lock); return error;
}
