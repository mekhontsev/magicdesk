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
#include "guest_execution.h"
#include "interception_route.h"
#include "identity_view.h"
#include "uts_view.h"
#include "fd_view.h"

static inline int md_entry_open(int number) {
    return number==SYS_openat || number==SYS_openat2;
}
static inline int md_entry_native_open(int number,const uint64_t args[6]) {
    /* open_how is captured by the worker, never dereferenced in the caller. */
    return number==SYS_openat2 || (number==SYS_openat && md_open_native_plan(args[2],1));
}

static inline int md_entry_native_fd(int number,const uint64_t args[6]) {
    if (number==SYS_epoll_pwait) return !(int)args[3] && !args[4];
    if (number==SYS_ioctl) return (unsigned)args[1]==FIONREAD;
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
    MD_ENTRY_RESPONSE, MD_ENTRY_CLOSED, MD_ENTRY_NATIVE, MD_ENTRY_RETIRING, MD_ENTRY_PARKED };
enum { MD_ENTRY_WAKE = 0x434f4f50, MD_ENTRY_BIND };
enum md_entry_request_kind { MD_ENTRY_SYSCALL, MD_ENTRY_BUFFER_CONTROL, MD_ENTRY_FD_CONTROL,
    MD_ENTRY_RESOURCE_CONTROL, MD_ENTRY_EFFECT_DONE, MD_ENTRY_EXECUTION_BEGIN, MD_ENTRY_EXECUTION_END,
    MD_ENTRY_NATIVE_REQUEST };
static inline int md_entry_control_request(unsigned kind) {
    return kind>=MD_ENTRY_BUFFER_CONTROL && kind<=MD_ENTRY_NATIVE_REQUEST;
}
/* The open effect chooses its FD. Resolve only a completed, non-birth effect;
 * explicit descriptors are encoded as fd+1, never this sentinel. */
enum md_entry_wait { MD_ENTRY_WAIT_REPLY, MD_ENTRY_WAIT_OPEN, MD_ENTRY_WAIT_COMMIT, MD_ENTRY_WAITS };
static inline enum md_entry_wait md_entry_wait_kind(int number,int native_sent) {
    return native_sent ? MD_ENTRY_WAIT_COMMIT : md_entry_open(number) ? MD_ENTRY_WAIT_OPEN : MD_ENTRY_WAIT_REPLY;
}
struct md_entry_channel {
    _Alignas(64) atomic_uint state;
    atomic_uint enabled, awake, control_awake, sleeping;
    /* Published by the coordinator before enabling this thread's channel. */
    atomic_uintptr_t policy;
    /* fd+1 or MD_FD_VIEW_RESULT, issued under an exclusive table turn.
     * Only the identified resident
     * entry may use it: its signal delivery is deferred until entry completes.
     * Every new request, task stop and borrowed execution revokes this lease. */
    struct md_fd_view descriptor;
    _Alignas(64) atomic_uint copying, caller;
    int32_t owner, control_target;
    struct md_spin_policy feedback[MD_ENTRY_WAITS];
    uint64_t sequence;
    _Alignas(64) struct md_entry_request {
        uint64_t args[6];
        int32_t number;
        uint32_t reserved;
    } call;
    struct md_guest_execution execution;
    enum md_execution_action execution_action;
    unsigned return_checkpoint;
    uintptr_t instruction_pointer;
    _Alignas(64) uint64_t receipt;
    enum md_entry_outcome outcome;
    int64_t result;
    uintptr_t result_extra;
    _Alignas(64) struct md_entry_native native;
    /* An admitted C adapter owns this journal without a request/receipt turn.
     * Only a stopped-task observer may cancel it; normal retirement is local. */
    struct md_entry_native adapter_native;
    /* Published with the control receipt: this owner must account for the
     * native result before C continues (for example, descriptor duplication). */
    unsigned adapter_native_managed;
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
    struct md_identity_view identity;
    struct md_uts_view uts;
    /* Cold routing publication must not split the request/lease cache lines. */
    struct md_interception_route_view routes;
    unsigned route_statistics;
    atomic_uint_fast64_t local_native;
};
static inline int md_entry_view_descriptor(const struct md_entry_channel *c) {
    unsigned lease=atomic_load_explicit(&c->descriptor.descriptor,memory_order_acquire);
    if (!lease) return -1;
    if (lease!=MD_FD_VIEW_RESULT) return (int)(lease-1);
    if (atomic_load_explicit(&c->native.state,memory_order_acquire)!=MD_NATIVE_DONE
            || c->native.number!=SYS_openat || c->native.result<0 || c->native.result>INT32_MAX) return -1;
    return (int)c->native.result;
}
#endif
