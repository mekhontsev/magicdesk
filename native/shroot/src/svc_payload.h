#ifndef MD_SVC_PAYLOAD_H
#define MD_SVC_PAYLOAD_H
#include "cooperative_entry.h"
#include "svc_frame.h"
#define MD_SVC_PAYLOAD_SIZE 65536
#define MD_SVC_INLINE_CLIENTS 128
enum { MD_SVC_CLIENT_PROBES=4, MD_SVC_CLIENT_BUCKETS=16, MD_SVC_CLIENT_BLOCK=32 };
struct md_svc_client_slot { uint64_t tid; struct md_entry_client client; };
struct md_svc_client_block {
    uintptr_t next;
    struct md_svc_client_slot slots[MD_SVC_CLIENT_BLOCK];
};
struct md_svc_payload_state {
    const struct md_entry_api *api;
    uint64_t completed,declined,unknown;
    uint64_t admitted,revoked;
    uint64_t retired_clients;
    /* Supervisor-published native identity of the sole mm owner, or zero.
     * Fork/CLONE_VM publication refreshes it before either task resumes. */
    uint64_t sole_owner;
    uint64_t statistics;
    uint64_t native_completed;
    struct md_svc_client_slot clients[MD_SVC_INLINE_CLIENTS];
    uintptr_t client_buckets[MD_SVC_CLIENT_BUCKETS];
};
/* This is the runtime's exclusively claimed stack, not the application's SP
 * or TLS. Nested adapters borrow the client resolved by this exact entry. */
static inline struct md_entry_client *md_svc_frame_client(uintptr_t frames,uintptr_t sp) {
    if (!frames || sp-frames>=MD_SVC_POOL_SIZE) return NULL;
    uintptr_t offset=(sp-frames)&(MD_SVC_SLOT_SIZE-1);
    if (offset<MD_SVC_GUARD_OFFSET+MD_SVC_GUARD_SIZE) return NULL;
    uintptr_t frame=sp-offset;
    return *(struct md_entry_client **)(frame+MD_SVC_CLIENT_OFFSET);
}
/* Lookup only: publication and retirement remain with the entry registry. */
static __attribute__((always_inline)) inline struct md_entry_client *md_svc_client_find(
        struct md_svc_payload_state *s,uint64_t tid) {
    for (unsigned j=0;j<MD_SVC_CLIENT_PROBES;++j) {
        struct md_svc_client_slot *slot=&s->clients[(tid+j)%MD_SVC_INLINE_CLIENTS];
        if (__atomic_load_n(&slot->tid,__ATOMIC_ACQUIRE)==tid) return &slot->client;
    }
    struct md_svc_client_block *block=(void *)__atomic_load_n(
        &s->client_buckets[tid%MD_SVC_CLIENT_BUCKETS],__ATOMIC_ACQUIRE);
    for (;block;block=(void *)block->next)
        for (unsigned j=0;j<MD_SVC_CLIENT_BLOCK;++j) {
            struct md_svc_client_slot *slot=&block->slots[(tid/MD_SVC_CLIENT_BUCKETS+j)%MD_SVC_CLIENT_BLOCK];
            if (__atomic_load_n(&slot->tid,__ATOMIC_ACQUIRE)==tid) return &slot->client;
        }
    return NULL;
}
extern unsigned char __start_md_svc_payload[],__stop_md_svc_payload[];
int md_svc_payload_dispatch(uint64_t *registers,struct md_svc_payload_state *,uintptr_t original_pc);
#endif
