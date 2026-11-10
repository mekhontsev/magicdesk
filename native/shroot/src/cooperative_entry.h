#ifndef MD_COOPERATIVE_ENTRY_H
#define MD_COOPERATIVE_ENTRY_H
#include <stdint.h>

/* Private auxv contract, independent of the libc ABI. The caller retains its
 * original SVC instruction, including clone/signal-return stack semantics. */
#define MD_AT_COOPERATIVE 0x4d440001UL
#define MD_ENTRY_VERSION 10
enum md_entry_outcome { MD_ENTRY_DECLINED, MD_ENTRY_COMPLETED, MD_ENTRY_UNKNOWN,
    MD_ENTRY_CHECK_CANCEL, MD_ENTRY_KERNEL_CALL, MD_ENTRY_NATIVE_EFFECT };
struct md_entry_client;
struct md_entry_call;
typedef enum md_entry_outcome (*md_entry_step_fn)(struct md_entry_client *,struct md_entry_call *);
typedef long (*md_entry_kernel_fn)(long,const uint64_t *);
struct md_entry_call {
    uint64_t args[6];
    int64_t result;
    int32_t number;
    uint32_t reserved;
    /* Original architectural PC after SVC, never an internal helper address.
     * Zero retains kernel admission when an application policy is present. */
    uintptr_t instruction_pointer;
    /* Optional caller-owned SVC continuation. A libc uses the same site with
     * entry enabled or disabled, preserving instruction-pointer filter policy.
     * It receives only the original syscall, never a synthetic preflight. */
    md_entry_kernel_fn kernel;
    /* Caller-owned checkpoints run without a runtime C frame on the stack. */
    void (*cancel)(void);
    /* Step output, private to the driver below. The runtime may suspend only
     * at an explicit caller action. Never replay an abandoned continuation. */
    md_entry_step_fn next;
    void (*effect)(void *);
    void *effect_context;
    uintptr_t continuation[4];
};
/* Fixed caller storage, not the transport's state layout. Zero once per thread
 * and after fork; never inspect/copy an active handle or share it across threads.
 * Reentry/foreign handles never publish into another active channel. A disabled
 * channel retains the original kernel call without probing helper identity.
 * UNKNOWN forbids replay. */
struct md_entry_client {
    uintptr_t opaque[3];
};
enum { MD_ENTRY_ORDINARY = 1, MD_ENTRY_CANCELLATION = 2, MD_ENTRY_NATIVE_MEMORY = 4 };
struct md_entry_api {
    uint32_t version, size;
    md_entry_step_fn try_syscall;
    /* Immutable for this process image; flags describe caller integration, not
     * engine routes or authority. Unlisted calls retain their original SVC. */
    const unsigned char *offers;
    uint32_t syscall_count, reserved;
    /* Runtime identity, not the guest-visible PID namespace number. Call only
     * while helper syscalls are admitted; filtered callers retain their SVC. */
    long (*thread_identity)(void);
    /* Resident SVC driver only: its frame defers application handlers until
     * completion. Generic clients keep the original kernel continuation. */
    int (*native_memory)(struct md_entry_client *,long,long,const uint64_t *,int64_t *,uintptr_t);
    /* Resident driver has the native identity from thread_identity or the
     * supervisor's sole-mm-owner publication, never a guest namespace PID. */
    enum md_entry_outcome (*try_identified)(struct md_entry_client *,long,struct md_entry_call *);
};
static inline int md_entry_api_valid(const struct md_entry_api *api) {
    return api && api->version==MD_ENTRY_VERSION && api->size>=sizeof(*api)
        && api->try_syscall && api->offers && !api->reserved && api->thread_identity;
}
static inline int md_entry_offered(const struct md_entry_api *api,long number,unsigned kind) {
    return api && (unsigned long)number<api->syscall_count && (api->offers[number]&kind);
}
/* Compiled in libc/the caller, with its unwind metadata. In particular a
 * cancellation or forced unwind never crosses an opaque runtime C frame. */
static inline enum md_entry_outcome md_entry_invoke(const struct md_entry_api *api,
        struct md_entry_client *client,struct md_entry_call *call) {
    enum md_entry_outcome outcome=api->try_syscall(client,call);
    for (;;) {
        md_entry_step_fn next=call->next;
        switch (outcome) {
        case MD_ENTRY_CHECK_CANCEL:
            if (!call->cancel || !next) return MD_ENTRY_UNKNOWN;
            call->cancel(); break;
        case MD_ENTRY_KERNEL_CALL:
            if (!call->kernel) return MD_ENTRY_UNKNOWN;
            call->result=call->kernel(call->number,call->args);
            if (!next) return MD_ENTRY_COMPLETED;
            break;
        case MD_ENTRY_NATIVE_EFFECT:
            if (!call->effect || !next) return MD_ENTRY_UNKNOWN;
            call->effect(call->effect_context); break;
        default: return outcome;
        }
        outcome=next(client,call);
    }
}
extern const struct md_entry_api md_cooperative_entry;
#endif
