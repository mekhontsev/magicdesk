#ifndef MD_COOPERATIVE_EXCHANGE_H
#define MD_COOPERATIVE_EXCHANGE_H
#include "cooperative_channel.h"

/* One shared protocol, two execution owners. This layer publishes readiness;
 * its caller owns kernel wakeups, operation lifetime and effect cancellation. */
enum md_entry_owner { MD_ENTRY_WORKER_OWNER, MD_ENTRY_SUPERVISOR_OWNER };
static inline enum md_entry_owner md_entry_request_owner(unsigned kind) {
    return md_entry_control_request(kind) ? MD_ENTRY_SUPERVISOR_OWNER : MD_ENTRY_WORKER_OWNER;
}
static inline atomic_uint *md_entry_owner_awake(struct md_entry_channel *c,enum md_entry_owner owner) {
    return owner==MD_ENTRY_SUPERVISOR_OWNER ? &c->control_awake : &c->awake;
}
/* Either publication observes a parked owner or parking observes the request.
 * The returned wake obligation must be fulfilled by the caller's transport. */
static inline int md_entry_publish_request(struct md_entry_channel *c,unsigned state) {
    atomic_uint *awake=md_entry_owner_awake(c,md_entry_request_owner(c->call.reserved));
    atomic_store_explicit(&c->state,state,memory_order_seq_cst);
    return !atomic_load_explicit(awake,memory_order_seq_cst);
}
static inline int md_entry_publish_response(struct md_entry_channel *c,unsigned state) {
    atomic_store_explicit(&c->state,state,memory_order_seq_cst);
    return atomic_load_explicit(&c->sleeping,memory_order_seq_cst)!=0;
}
enum { MD_ENTRY_PENDING_CALL=1, MD_ENTRY_PENDING_NATIVE=2 };
static inline unsigned md_entry_prepare_wait(struct md_entry_channel *c,enum md_entry_owner owner) {
    atomic_store_explicit(md_entry_owner_awake(c,owner),0,memory_order_seq_cst);
    unsigned pending=atomic_load_explicit(&c->state,memory_order_seq_cst)==MD_ENTRY_REQUEST
        && md_entry_request_owner(c->call.reserved)==owner ? MD_ENTRY_PENDING_CALL : 0;
    if (owner==MD_ENTRY_WORKER_OWNER) {
        unsigned state=atomic_load_explicit(&c->native.state,memory_order_seq_cst);
        if (state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED || state==MD_NATIVE_UNKNOWN)
            pending|=MD_ENTRY_PENDING_NATIVE;
    }
    return pending;
}
/* The native-effect owner publishes receipts. A caller which claimed RETIRING already
 * owns the native result and may have queued its next request in call. */
static inline int md_entry_native_retire(struct md_entry_channel *c,uint64_t sequence,
        enum md_entry_outcome outcome,long result) {
    c->receipt=sequence; c->result=result; c->outcome=outcome;
    unsigned offered=MD_ENTRY_NATIVE;
    if (atomic_compare_exchange_strong_explicit(&c->state,&offered,MD_ENTRY_RESPONSE,
            memory_order_seq_cst,memory_order_acquire)) return 1;
    if (offered!=MD_ENTRY_RETIRING && offered!=MD_ENTRY_REQUEST && offered!=MD_ENTRY_FD_REQUEST) return -EPROTO;
    unsigned retiring=MD_ENTRY_RETIRING;
    atomic_compare_exchange_strong_explicit(&c->state,&retiring,MD_ENTRY_IDLE,
        memory_order_release,memory_order_relaxed);
    return 0;
}
#endif
