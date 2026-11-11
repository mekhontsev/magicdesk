#include "uts_namespace.h"
#include "namespace_identity.h"
#include "control_event.h"
#include "uts_view.h"
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

struct md_uts_namespace {
    atomic_uint references;
    uint64_t inode;
    struct utsname names;
    struct md_user_namespace *owner;
    struct md_control_event *events[2];
    pthread_mutex_t lock;
    struct md_uts_subscription *subscribers;
};
struct md_uts_namespace *md_uts_namespace_new(void) {
    struct md_uts_namespace *n=calloc(1,sizeof(*n));
    if (!n) return NULL;
    if (uname(&n->names)) { free(n); return NULL; }
    int error=md_namespace_identity_new(&n->inode);
    if (error) { free(n); errno=-error; return NULL; }
    atomic_init(&n->references,1);
    pthread_mutex_init(&n->lock,NULL);
    for (unsigned i=0;i<2;++i) {
        n->events[i]=md_control_event_new();
        if (!n->events[i]) { int saved=errno; md_uts_namespace_release(n); errno=saved; return NULL; }
    }
    return n;
}
struct md_uts_namespace *md_uts_namespace_retain(struct md_uts_namespace *n) {
    if (n) atomic_fetch_add_explicit(&n->references,1,memory_order_relaxed);
    return n;
}
struct md_uts_namespace *md_uts_namespace_copy(const struct md_uts_namespace *n, struct md_user_namespace *owner) {
    if (!n) return NULL;
    struct md_uts_namespace *copy=calloc(1,sizeof(*copy));
    if (copy) {
        int error=md_namespace_identity_new(&copy->inode);
        if (error) { free(copy); errno=-error; return NULL; }
        atomic_init(&copy->references,1); copy->owner=md_user_namespace_retain(owner);
        pthread_mutex_init(&copy->lock,NULL);
        md_uts_namespace_read(n,&copy->names);
        for (unsigned i=0;i<2;++i) copy->events[i]=md_control_event_retain(n->events[i]);
    }
    return copy;
}
void md_uts_namespace_release(struct md_uts_namespace *n) {
    if (n && atomic_fetch_sub_explicit(&n->references,1,memory_order_acq_rel)==1) {
        for (unsigned i=0;i<2;++i) md_control_event_release(n->events[i]);
        pthread_mutex_destroy(&n->lock);
        md_user_namespace_release(n->owner); free(n);
    }
}
struct md_control_event *md_uts_namespace_event(const struct md_uts_namespace *n,int domain) {
    return n ? n->events[!!domain] : NULL;
}
const struct md_user_namespace *md_uts_namespace_owner(const struct md_uts_namespace *n) { return n->owner; }
uint64_t md_uts_namespace_inode(const struct md_uts_namespace *n) { return n->inode; }
void md_uts_namespace_read(const struct md_uts_namespace *n, struct utsname *names) {
    pthread_mutex_lock((pthread_mutex_t *)&n->lock);
    *names=n->names;
    pthread_mutex_unlock((pthread_mutex_t *)&n->lock);
}
int md_uts_namespace_set(struct md_uts_namespace *n, int domain, const void *name, size_t size) {
    if (!n || size>=sizeof(n->names.nodename)) return -EINVAL;
    pthread_mutex_lock(&n->lock);
    char *target=domain ? n->names.domainname : n->names.nodename;
    if (size) memcpy(target,name,size);
    memset(target+size,0,sizeof(n->names.nodename)-size);
    for (struct md_uts_subscription *s=n->subscribers;s;s=s->next)
        md_uts_view_publish(s->view,&n->names);
    pthread_mutex_unlock(&n->lock);
    return md_control_event_publish(n->events[!!domain]);
}
void md_uts_namespace_unsubscribe(struct md_uts_subscription *s) {
    struct md_uts_namespace *n=s->owner;
    if (!n) return;
    pthread_mutex_lock(&n->lock);
    struct md_uts_subscription **at=&n->subscribers;
    while (*at && *at!=s) at=&(*at)->next;
    if (*at) *at=s->next;
    atomic_fetch_or_explicit(&s->view->sequence,1,memory_order_release);
    *s=(struct md_uts_subscription){0};
    pthread_mutex_unlock(&n->lock);
    md_uts_namespace_release(n);
}
void md_uts_namespace_subscribe(struct md_uts_namespace *n,struct md_uts_subscription *s,struct md_uts_view *view) {
    if (s->owner==n && s->view==view) return;
    md_uts_namespace_unsubscribe(s);
    if (!n || !view) return;
    pthread_mutex_lock(&n->lock);
    *s=(struct md_uts_subscription){.owner=md_uts_namespace_retain(n),.next=n->subscribers,.view=view};
    n->subscribers=s;
    md_uts_view_publish(view,&n->names);
    pthread_mutex_unlock(&n->lock);
}
