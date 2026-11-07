#ifndef MD_SIGNAL_VIEW_H
#define MD_SIGNAL_VIEW_H
#include "process_owner.h"
#include <signal.h>
#include <stdint.h>

/* Translation only. Kernel action exchange, delivery ordering and fault-aware
 * user copies belong to the stopped-task owner. No process-global state. */
#define MD_SIGNAL_SYS_BIT (UINT64_C(1) << (SIGSYS - 1))
/* Sparse projection over a native proc snapshot; unrelated signal bits retain
 * their kernel value. Only the stopped-task coordinator supplies this view. */
struct md_signal_status { uint64_t known, blocked_known, blocked, pending, shared_pending, caught, ignored; };
/* An atomic wait saves the old mask but enters handlers under the temporary
 * one. Keep both until kernel restoration or handler-frame admission. */
struct md_signal_wait_view { uint64_t saved,temporary; int active; };
uint64_t md_signal_view_mask(uint64_t native,int blocked);
int md_signal_view_update(uint64_t previous,int how,uint64_t requested,uint64_t *out);
struct md_kernel_action md_signal_view_native(int signal,struct md_kernel_action,uintptr_t entry);
struct md_kernel_action md_signal_view_action(int signal,struct md_kernel_action native,
    const struct md_kernel_action *logical,uintptr_t entry);
/* Update the saved application mask and compute the handler's logical mask.
 * Call before any application handler instruction, including nonlocal exits. */
uint64_t md_signal_view_enter(int signal,const struct md_kernel_action *,
    int blocked,const struct md_signal_wait_view *,uint64_t *saved);
#endif
