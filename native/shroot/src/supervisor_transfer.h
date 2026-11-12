#ifndef MD_SUPERVISOR_TRANSFER_H
#define MD_SUPERVISOR_TRANSFER_H
#include "guest_transfer.h"
#include "supervisor_thread.h"

/* The caller owns its stopped continuation and signal shield. Completion
 * restores that continuation before invoking its owner, including on EFAULT.
 * Permission-respecting direct access does not allocate or enter the tracee. */
void transfer_begin(struct thread *,const struct user_pt_regs *,uintptr_t,void *,size_t,
    enum md_guest_transfer_kind,void (*complete)(struct thread *,int));
void transfer_progress(struct thread *);
int transfer_checkpoint(struct thread *,uintptr_t,const struct user_pt_regs *);
void transfer_discard(struct thread *);
#endif
