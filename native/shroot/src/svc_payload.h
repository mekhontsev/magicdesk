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
    struct md_svc_client_slot clients[MD_SVC_INLINE_CLIENTS];
    uintptr_t client_buckets[MD_SVC_CLIENT_BUCKETS];
};
extern unsigned char __start_md_svc_payload[],__stop_md_svc_payload[];
int md_svc_payload_dispatch(uint64_t *registers,struct md_svc_payload_state *,uintptr_t original_pc);
#endif
