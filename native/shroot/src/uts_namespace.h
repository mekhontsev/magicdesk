#ifndef MD_UTS_NAMESPACE_H
#define MD_UTS_NAMESPACE_H
#include <stddef.h>
#include <sys/utsname.h>
#include "user_namespace.h"

/* Supervisor-owned names. Shared by fork/exec; NEWUTS copies before commit. */
struct md_uts_namespace;
struct md_control_event;
struct md_uts_view;
struct md_uts_subscription {
    struct md_uts_namespace *owner;
    struct md_uts_subscription *next;
    struct md_uts_view *view;
};
void md_uts_namespace_subscribe(struct md_uts_namespace *,struct md_uts_subscription *,struct md_uts_view *);
void md_uts_namespace_unsubscribe(struct md_uts_subscription *);
/* Borrowed table notification source. Like Linux's UTS sysctl table, namespace
 * copies share notifications, while reads still select the actor's names. */
struct md_control_event *md_uts_namespace_event(const struct md_uts_namespace *,int domain);
struct md_uts_namespace *md_uts_namespace_new(void);
uint64_t md_uts_namespace_inode(const struct md_uts_namespace *);
struct md_uts_namespace *md_uts_namespace_retain(struct md_uts_namespace *);
struct md_uts_namespace *md_uts_namespace_copy(const struct md_uts_namespace *, struct md_user_namespace *owner);
const struct md_user_namespace *md_uts_namespace_owner(const struct md_uts_namespace *);
void md_uts_namespace_release(struct md_uts_namespace *);
void md_uts_namespace_read(const struct md_uts_namespace *, struct utsname *);
int md_uts_namespace_set(struct md_uts_namespace *, int domain, const void *, size_t);
#endif
