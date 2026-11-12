#ifndef MD_COOPERATIVE_NATIVE_H
#define MD_COOPERATIVE_NATIVE_H
/* Shared with the leaf assembly routine; independent of libc's entry ABI. */
#define MD_NATIVE_EMPTY 0
#define MD_NATIVE_READY 1
#define MD_NATIVE_RUNNING 2
#define MD_NATIVE_DONE 3
#define MD_NATIVE_UNKNOWN 4
#define MD_NATIVE_CANCELLED 5
/* Private resident frame: a control owner, not this frame, retires the effect. */
#define MD_NATIVE_OWNED 6
#define MD_NATIVE_RESULT 8
#define MD_NATIVE_ARGS 16
#ifndef __ASSEMBLER__
#include "syscall_result.h"
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
struct md_entry_native {
    atomic_uint state;
    int32_t number;
    int64_t result;
    uint64_t args[6];
};
struct md_native_gate { uintptr_t begin,call,end; };
_Static_assert(offsetof(struct md_entry_native,result)==MD_NATIVE_RESULT,"native result offset");
_Static_assert(offsetof(struct md_entry_native,args)==MD_NATIVE_ARGS,"native args offset");
void md_cooperative_native(struct md_entry_native *);
extern char md_cooperative_native_call[], md_cooperative_native_return[], md_cooperative_native_end[];
/* A stop before the effect is not a completed close(-EINTR): Linux's latter
 * result releases the slot. Keep that distinction through FD retirement. */
static inline int md_native_stopped(unsigned state,uintptr_t pc,long value,
        uintptr_t begin,uintptr_t call,uintptr_t end,long *result) {
    if (state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED) return (int)state;
    if (state==MD_NATIVE_READY) { *result=-EINTR; return MD_NATIVE_CANCELLED; }
    if (state!=MD_NATIVE_RUNNING || pc<begin || pc>end) return -EPROTO;
    if (pc<=call || md_syscall_restarting(value)) {
        *result=-EINTR; return MD_NATIVE_CANCELLED;
    }
    *result=value; return MD_NATIVE_DONE;
}
#endif
#endif
