#ifndef MD_SVC_DIRECT_H
#define MD_SVC_DIRECT_H
#include <stddef.h>
#include <stdint.h>
#include <asm/ptrace.h>
#include "cooperative_native.h"

/* SVC-only entry. A reversible guard establishes the full x8
 * value before borrowing it. All non-completions reach miss with original
 * registers, flags and SP; the owner must execute the original syscall. */
size_t md_svc_direct_size(void);
struct md_svc_direct_fallback { uintptr_t mismatch,declined,busy; };
int md_svc_direct_plan(uintptr_t svc,uint64_t number,uintptr_t destination,
    uintptr_t dispatch,uintptr_t frames,struct md_svc_direct_fallback,void *output,size_t capacity,
    uint32_t *replacement);
/* Original state enters a native syscall, then resumes after the original SVC.
 * Filter and signal owners must project this alias back to the original PC. */
int md_svc_direct_kernel(uintptr_t svc,uintptr_t destination,uint32_t output[2]);
struct md_svc_direct_context {
    uintptr_t svc,gate,frames;
    uint64_t number;
    unsigned checkpoint_number;
    uintptr_t checkpoint;
};
extern const unsigned char md_svc_signal_checkpoint[];
typedef int (*md_svc_direct_memory)(void *,uintptr_t,void *,size_t,int write);
/* Optional signal rendezvous after the dispatcher returns. No private TRAP. */
#define MD_SVC_DIRECT_PENDING 392
size_t md_svc_direct_signal_size(void);
int md_svc_direct_signal_plan(const struct md_svc_direct_context *,uintptr_t dispatch,
    struct md_svc_direct_fallback,void *output,size_t,uint32_t *replacement);
uintptr_t md_svc_direct_checkpoint(const struct md_svc_direct_context *);
/* 0 outside, 1 recovered at original boundary, 2 waiting for dispatcher,
 * negative errno on failed reconstruction. Completed kernel checkpoint:
 * caller first skips its syscall, then publishes the recovered registers. */
int md_svc_direct_recover(const struct md_svc_direct_context *,struct user_pt_regs *,
    md_svc_direct_memory,void *);
/* A terminal native call has no remaining adapter ownership. Recover it only
 * for an actual delivery, preserving the kernel's pending restart decision. */
enum { MD_SVC_NATIVE_RESTART=3 };
int md_svc_direct_native_recover(const struct md_svc_direct_context *,const struct md_native_gate *,
    struct user_pt_regs *,md_svc_direct_memory,void *);
/* Explicit terminal handoff: the execution owner has retired all C effects.
 * Restore the original call from its claimed frame and release that frame. */
int md_svc_direct_unwind(const struct md_svc_direct_context *,struct user_pt_regs *,
    md_svc_direct_memory,void *);
#endif
