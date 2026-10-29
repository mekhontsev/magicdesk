#ifndef MD_COOPERATIVE_CHANNEL_H
#define MD_COOPERATIVE_CHANNEL_H
#include "cooperative_entry.h"
#include "cooperative_native.h"
#include "entry_route.h"
#include "spin_policy.h"
#include <stdatomic.h>
#include <sys/syscall.h>
#include <stddef.h>
#include <fcntl.h>
#include "file_open.h"
#include "memory_admission.h"

static inline int md_entry_open(int number) {
    return number==SYS_openat || number==SYS_openat2;
}
static inline int md_entry_native_open(int number,const uint64_t args[6]) {
    /* open_how is captured by the worker, never dereferenced in the caller. */
    return number==SYS_openat2 || (number==SYS_openat && md_open_native_plan(args[2],1));
}

static inline int md_entry_native_fd(int number,const uint64_t args[6]) {
    if (number==SYS_close || number==SYS_dup || number==SYS_dup3 || number==SYS_lseek) return 1;
    if (number!=SYS_fcntl) return 0;
    switch ((unsigned)args[1]) {
    case F_SETFD: case F_GETFD: case F_GETFL: case F_SETFL:
    case F_DUPFD: case F_DUPFD_CLOEXEC: return 1;
    default: return 0;
    }
}

/* Bootstrap-only initialization, before any guest thread exists. */
void md_cooperative_init(void);

/* Private interpretation of the fixed entry handle. Progress/feedback lives
 * in the runtime-owned channel, independently of a caller's TLS layout. */
enum { MD_ENTRY_CLIENT_CHANNEL, MD_ENTRY_CLIENT_OWNER, MD_ENTRY_CLIENT_CONTROL };
/* The control word's low bit guards reentry; the rest holds registration backoff. */
enum { MD_ENTRY_CLIENT_BUSY = 1 };

_Static_assert(ATOMIC_INT_LOCK_FREE==2,"shared channel state must be lock-free");
_Static_assert(sizeof(atomic_uint)==4,"Linux futex word size");

enum md_entry_state { MD_ENTRY_IDLE, MD_ENTRY_REQUEST, MD_ENTRY_FD_REQUEST, MD_ENTRY_RUNNING,
    MD_ENTRY_RESPONSE, MD_ENTRY_CLOSED, MD_ENTRY_NATIVE, MD_ENTRY_RETIRING };
enum { MD_ENTRY_WAKE = 0x434f4f50, MD_ENTRY_BIND };
/* The open effect chooses its FD. Resolve only a completed, non-birth effect;
 * explicit descriptors are encoded as fd+1, never this sentinel. */
#define MD_ENTRY_RESULT_FD UINT32_MAX
enum md_entry_wait { MD_ENTRY_WAIT_REPLY, MD_ENTRY_WAIT_OPEN, MD_ENTRY_WAIT_COMMIT, MD_ENTRY_WAITS };
static inline enum md_entry_wait md_entry_wait_kind(int number,int native_sent) {
    return native_sent ? MD_ENTRY_WAIT_COMMIT : md_entry_open(number) ? MD_ENTRY_WAIT_OPEN : MD_ENTRY_WAIT_REPLY;
}
struct md_entry_channel {
    _Alignas(64) atomic_uint state;
    atomic_uint enabled, awake, sleeping;
    /* fd+1 or MD_ENTRY_RESULT_FD, issued under an exclusive table turn.
     * Only the identified resident
     * entry may use it: its signal delivery is deferred until entry completes.
     * Every new request, task stop and borrowed execution revokes this lease. */
    atomic_uint native_regular;
    unsigned native_flags;
    _Alignas(64) atomic_uint copying, caller;
    int32_t owner;
    struct md_spin_policy feedback[MD_ENTRY_WAITS];
    uint64_t sequence;
    _Alignas(64) struct md_entry_request {
        uint64_t args[6];
        int32_t number;
        uint32_t reserved;
    } call;
    _Alignas(64) uint64_t receipt;
    enum md_entry_outcome outcome;
    int64_t result;
    _Alignas(64) struct md_entry_native native;
    /* The native FD is provisional until the worker publishes its name. */
    unsigned native_commit;
    char native_path[64];
    /* Worker publishes an immutable, sequence-bound read result. No cursor or
     * mutation may depend on this later, caller-owned copy succeeding. */
    struct {
        uint64_t sequence;
        uintptr_t destination;
        size_t size;
        unsigned char bytes[512];
    } output;
    struct {
        uint64_t sequence;
        uintptr_t address[2];
        char paths[2][4096];
    } input;
    struct md_memory_admission memory;
};
static inline int md_entry_regular_descriptor(const struct md_entry_channel *c) {
    unsigned lease=atomic_load_explicit(&c->native_regular,memory_order_acquire);
    if (!lease) return -1;
    if (lease!=MD_ENTRY_RESULT_FD) return (int)(lease-1);
    if (atomic_load_explicit(&c->native.state,memory_order_acquire)!=MD_NATIVE_DONE
            || c->native.number!=SYS_openat || c->native.result<0 || c->native.result>INT32_MAX) return -1;
    return (int)c->native.result;
}
enum { MD_ENTRY_PENDING_CALL=1, MD_ENTRY_PENDING_NATIVE=2 };
/* Pair with the producer's publication/fence/awake check. Either this sees
 * the work or that producer requests a kernel wake; neither may miss both. */
static inline unsigned md_entry_prepare_wait(struct md_entry_channel *c) {
    atomic_store_explicit(&c->awake,0,memory_order_seq_cst);
    unsigned pending=atomic_load_explicit(&c->state,memory_order_seq_cst)==MD_ENTRY_REQUEST
        ? MD_ENTRY_PENDING_CALL : 0;
    unsigned state=atomic_load_explicit(&c->native.state,memory_order_seq_cst);
    if (state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED || state==MD_NATIVE_UNKNOWN)
        pending|=MD_ENTRY_PENDING_NATIVE;
    return pending;
}
/* Only the worker publishes receipts. A caller which claimed RETIRING already
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
