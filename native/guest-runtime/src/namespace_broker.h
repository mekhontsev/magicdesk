#ifndef MD_NAMESPACE_BROKER_H
#define MD_NAMESPACE_BROKER_H
#include "fs_worker.h"
#include <linux/seccomp.h>
struct md_namespace_broker;
struct md_namespace_broker *md_broker_create(struct md_fs_worker *, int statistics);
void md_broker_listen(struct md_namespace_broker *, int listener);
/* Publish before resuming a tracee; forget retires its eligibility. */
int md_broker_task(struct md_namespace_broker *, int pid, int eligible, uintptr_t raw_gate, uintptr_t watch_gate);
void md_broker_forget(struct md_namespace_broker *, int pid);
int md_broker_complete(struct md_namespace_broker *,
        void (*delegate)(const struct seccomp_notif *));
void md_broker_destroy(struct md_namespace_broker *);
#endif
