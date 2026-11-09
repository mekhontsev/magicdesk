#ifndef MD_FD_OBJECT_H
#define MD_FD_OBJECT_H
#include <stdatomic.h>
#include "control_file.h"

/* Embedded in the descriptor's typed authority, never a second allocation.
 * It owns provenance/lifetime, not a duplicate of the application's data OFD. */
struct md_fd_object;
struct md_fd_weak;
struct md_task_readiness_epoch { uint64_t domain, pending, configuration; };
/* Last-reference notification; ordinary authorities have no weak state. */
void md_fd_weak_retire(struct md_fd_weak *);
struct md_fd_object_type {
    void (*destroy)(struct md_fd_object *);
    unsigned selectors;
    /* Additional requirements of a retained object's current state. This may
     * run on a worker; the owner publishes transitions atomically. */
    unsigned (*selector_mask)(const struct md_fd_object *);
    /* Optional metadata whose native OFD can escape without this authority.
     * Reserve against virtual activation, then finish after actual send.
     * A committed/uncertain escape must remain native for its whole lifetime.
     * Both callbacks run with a retained object and may cross worker handoff. */
    int (*export_begin)(struct md_fd_object *);
    void (*export_end)(struct md_fd_object *, int escaped);
    /* An unobserved terminal mutation discarded an alias annotation. Called
     * under the source table turn, after its metadata lock is released. Owners
     * requiring a complete alias inventory must invalidate that assumption. */
    void (*alias_lost)(struct md_fd_object *);
    /* Confirmed publication, including dup/receive. Must be idempotent and
     * must not enter a descriptor table: called under its metadata lock. */
    void (*published)(struct md_fd_object *);
    /* Borrowed provenance and readiness are independent of data IO. A typed
     * open description may retain an ordinary filesystem anchor. */
    struct md_fs_anchor *(*origin)(struct md_fd_object *);
    /* Optional native-OFD annotation can travel as ordinary provenance after
     * export_end invalidates its local-only observation. */
    int portable_origin;
    struct md_control_file (*readiness)(struct md_fd_object *);
    /* Non-consuming, task-affine supplement to native readiness. No owner is
     * captured at open/dup time, and the native descriptor remains pollable. */
    long (*task_poll)(struct md_fd_object *,const struct md_control_actor *);
    /* Epoll edge identity for a task-affine supplement. Domain identifies the
     * registration's wait queue, not the FD opener; pending advances only on
     * a new event and configuration on changes that re-evaluate readiness. */
    struct md_task_readiness_epoch (*task_poll_epoch)(struct md_fd_object *,const struct md_control_actor *);
    /* A confirmed descriptor close, not a reference drop or failed publication.
     * The table turn has ended. A pending result owns its independent lifetime. */
    long (*close)(struct md_fd_object *,const struct md_control_actor *,struct md_control_pending **);
};
struct md_fd_object {
    atomic_uint references;
    const struct md_fd_object_type *type;
    _Atomic(struct md_fd_weak *) weak;
};
static inline struct md_control_file md_fd_object_readiness(struct md_fd_object *o) {
    return o && o->type->readiness ? o->type->readiness(o) : (struct md_control_file){0};
}
static inline int md_fd_object_has_readiness(const struct md_fd_object *o) {
    return o && (o->type->readiness || o->type->task_poll);
}
static inline unsigned md_fd_object_selectors(const struct md_fd_object *o) {
    return o ? o->type->selectors|(o->type->selector_mask ? o->type->selector_mask(o) : 0) : 0;
}
static inline void md_fd_object_init(struct md_fd_object *o, const struct md_fd_object_type *type) {
    atomic_init(&o->references,1); o->type=type; atomic_init(&o->weak,0);
}
static inline struct md_fd_object *md_fd_object_retain(struct md_fd_object *o) {
    if (o) atomic_fetch_add_explicit(&o->references,1,memory_order_relaxed);
    return o;
}
static inline void md_fd_object_release(struct md_fd_object *o) {
    if (o && atomic_fetch_sub_explicit(&o->references,1,memory_order_acq_rel)==1) {
        struct md_fd_weak *weak=atomic_load_explicit(&o->weak,memory_order_acquire);
        if (weak) md_fd_weak_retire(weak);
        o->type->destroy(o);
    }
}
#endif
