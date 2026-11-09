#ifndef MD_ROBUST_FUTEX_H
#define MD_ROBUST_FUTEX_H
#include <stddef.h>
#include <stdint.h>

/* Kernel robust-list ABI, independent of the executor's libc. Only terminal
 * cleanup uses this cursor; ordinary futex IO remains native. Addresses belong
 * to the dying task's retained mm, and owner is its TID in its own PID view. */
struct md_robust_head { uint64_t next, offset, pending; };
enum md_robust_operation { MD_ROBUST_DONE, MD_ROBUST_READ, MD_ROBUST_CMPXCHG, MD_ROBUST_WAKE };
struct md_robust_request {
    enum md_robust_operation operation;
    uintptr_t address;
    size_t size;
    uint32_t expected, replacement;
};
struct md_robust_cleanup {
    uintptr_t head, entry, next, pending, offset;
    uint32_t owner, value;
    unsigned remaining, stage;
    int next_error, processing_pending;
    struct md_robust_request request;
};
void md_robust_begin(struct md_robust_cleanup *,uintptr_t head,uint32_t owner);
/* READ supplies request.size bytes; CMPXCHG supplies the observed old word,
 * including mismatch. Wake errors and invalid userspace lists are best-effort
 * terminal cleanup, not application signals or a failed exit. */
void md_robust_complete(struct md_robust_cleanup *,int error,const void *result);
#endif
