#include "svc_payload.h"
#include "cooperative_native.h"
#include "svc_direct.h"
#include <sys/mman.h>
#include <sys/syscall.h>

/* The claimed slot already contains the saved application context. Only this
 * small leaf prologue uses its upper stack before the guard exists; no API or
 * guest callback may run until protection succeeds. The slot lock serializes
 * publication, and fork inherits both the flag and the protected mapping. */
static __attribute__((always_inline)) inline int prepare_stack(uint64_t *r) {
    uintptr_t frame=(uintptr_t)r-MD_SVC_FRAME_OFFSET;
    int64_t *ready=(void *)(frame+MD_SVC_GUARD_STATE);
    if (!*ready) {
        register long x0 __asm__("x0")=frame+MD_SVC_GUARD_OFFSET;
        register long x1 __asm__("x1")=MD_SVC_GUARD_SIZE;
        register long x2 __asm__("x2")=PROT_NONE;
        register long x8 __asm__("x8")=SYS_mprotect;
        __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x8) : "memory","cc");
        *ready=x0 ? -1 : 1;
    }
    return *ready==1;
}
static __attribute__((always_inline)) inline struct md_entry_client *client_slot(
        struct md_svc_client_slot *slots,unsigned count,unsigned probes,unsigned hash,uint64_t tid,int claim) {
    struct md_svc_client_slot *available=0;
    for (unsigned j=0;j<probes;++j) {
        unsigned i=(hash+j)%count;
        uint64_t owner=__atomic_load_n(&slots[i].tid,__ATOMIC_ACQUIRE);
        if (!owner && !available) available=&slots[i];
        if (owner==tid) return &slots[i].client;
    }
    if (claim && available) {
        uint64_t empty=0;
        if (__atomic_compare_exchange_n(&available->tid,&empty,tid,0,
                __ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE)) return &available->client;
    }
    return 0;
}

/* Relocation-free entry driver: no TLS, libc, stack protector,
 * SIMD or external helper calls. The private-stack guard is the only internal
 * syscall; application operations use the supplied cooperative API.
 * Shared by the bootstrap and copied laboratory fixtures. The supervisor
 * revokes admission before filter installation; no helper runs until the
 * original policy view and owned gates are ready for re-admission. */
__attribute__((section("md_svc_payload"),noinline))
int md_svc_payload_dispatch(uint64_t *r,struct md_svc_payload_state *s,uintptr_t original_pc) {
    if (!__atomic_load_n(&s->admitted,__ATOMIC_ACQUIRE)) return 0;
    if (!prepare_stack(r)) return 0;
    struct md_entry_client **scope=(void *)((uintptr_t)r-MD_SVC_FRAME_OFFSET+MD_SVC_CLIENT_OFFSET);
    *scope=NULL;
    const struct md_entry_api *api=s->api;
    if (!md_entry_api_valid(api) || !md_entry_offered(api,(long)r[8],MD_ENTRY_ORDINARY)) return 0;
    long tid=(long)__atomic_load_n(&s->sole_owner,__ATOMIC_ACQUIRE);
    if (!tid) tid=api->thread_identity();
    if (tid<=0) return 0;
    struct md_entry_client *client=md_svc_client_find(s,(uint64_t)tid);
    if (!client) {
        unsigned bucket=(unsigned)tid%MD_SVC_CLIENT_BUCKETS;
        struct md_svc_client_block *head=(void *)__atomic_load_n(&s->client_buckets[bucket],__ATOMIC_ACQUIRE);
        struct md_svc_client_block *block=head;
        if (!client) client=client_slot(s->clients,MD_SVC_INLINE_CLIENTS,
            MD_SVC_CLIENT_PROBES,(unsigned)tid,(uint64_t)tid,1);
        for (block=head;!client && block;block=(void *)block->next)
            client=client_slot(block->slots,MD_SVC_CLIENT_BLOCK,MD_SVC_CLIENT_BLOCK,
                (unsigned)tid/MD_SVC_CLIENT_BUCKETS,(uint64_t)tid,1);
        if (!client && api->client_block) {
            block=(void *)api->client_block(bucket);
            if (block) client=client_slot(block->slots,MD_SVC_CLIENT_BLOCK,MD_SVC_CLIENT_BLOCK,
                (unsigned)tid/MD_SVC_CLIENT_BUCKETS,(uint64_t)tid,1);
        }
    }
    if (!client) return 0;
    *scope=client;
    int64_t result;
    if (md_entry_offered(api,(long)r[8],MD_ENTRY_NATIVE_MEMORY)
            && api->native_memory && api->native_memory(client,tid,(long)r[8],r,&result,original_pc)) {
        r[0]=(uint64_t)result;
        if (s->statistics) __atomic_fetch_add(&s->completed,1,__ATOMIC_RELAXED);
        return 1;
    }
    struct md_entry_call call={.number=(int)r[8],.args={r[0],r[1],r[2],r[3],r[4],r[5]},
        .instruction_pointer=original_pc};
    enum md_entry_outcome outcome=api->try_identified
        ? api->try_identified(client,tid,&call) : api->try_syscall(client,&call);
    for (;;) {
        md_entry_step_fn next=call.next;
        if (call.return_checkpoint) {
            uintptr_t frame=(uintptr_t)r-MD_SVC_FRAME_OFFSET;
            __atomic_store_n((uint64_t *)(frame+MD_SVC_DIRECT_PENDING),1,__ATOMIC_RELEASE);
        }
        if (outcome==MD_ENTRY_NATIVE_CALL) {
            uintptr_t frame=(uintptr_t)r-MD_SVC_FRAME_OFFSET;
            if (__atomic_load_n((uint64_t *)(frame+MD_SVC_DIRECT_PENDING),__ATOMIC_ACQUIRE)) return 0;
            struct md_entry_native *native=(void *)(frame+MD_SVC_NATIVE_OFFSET);
            native->number=call.number; native->result=-EINTR;
            for (unsigned i=0;i<6;++i) native->args[i]=call.args[i];
            /* From publication onward, signal recovery may abandon this C
             * stack. There is no client/channel or resource turn to release.
             * The original operation precedes any subsequent policy fence. */
            atomic_store_explicit(&native->state,MD_NATIVE_READY,memory_order_release);
            /* A signal may have been retained between the first pending
             * check and terminal publication. Do not sleep ahead of it. */
            if (__atomic_load_n((uint64_t *)(frame+MD_SVC_DIRECT_PENDING),__ATOMIC_ACQUIRE)) return 0;
            call.effect(native);
            call.result=native->result;
            if (s->statistics) __atomic_fetch_add(&s->native_completed,1,__ATOMIC_RELAXED);
            outcome=MD_ENTRY_COMPLETED;
        }
        if (outcome==MD_ENTRY_COMPLETED) {
            r[0]=(uint64_t)call.result;
            if (s->statistics) __atomic_fetch_add(&s->completed,1,__ATOMIC_RELAXED);
            return 1;
        }
        if (outcome==MD_ENTRY_DECLINED || (outcome==MD_ENTRY_KERNEL_CALL && !next)) {
            if (s->statistics) __atomic_fetch_add(&s->declined,1,__ATOMIC_RELAXED);
            return 0;
        }
        if (outcome!=MD_ENTRY_NATIVE_EFFECT && outcome!=MD_ENTRY_KERNEL_CALL) {
            if (s->statistics) __atomic_fetch_add(&s->unknown,1,__ATOMIC_RELAXED);
            /* Never replay an operation with an unknown committed effect. */
            __builtin_trap();
        }
        outcome=md_entry_advance(client,&call,outcome);
    }
}
