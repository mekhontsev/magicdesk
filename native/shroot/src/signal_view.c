#include "signal_view.h"
#include "signal_protocol.h"
#include <errno.h>

uint64_t md_signal_view_mask(uint64_t native,int blocked) {
    return (native&~MD_SIGNAL_SYS_BIT)|(blocked ? MD_SIGNAL_SYS_BIT : 0);
}
int md_signal_view_update(uint64_t previous,int how,uint64_t requested,uint64_t *out) {
    if (!out) return -EINVAL;
    uint64_t next;
    if (how==SIG_BLOCK) next=previous|requested;
    else if (how==SIG_UNBLOCK) next=previous&~requested;
    else if (how==SIG_SETMASK) next=requested;
    else return -EINVAL;
    *out=next&md_signal_blockable_mask();
    return 0;
}
static int caught(const struct md_kernel_action *action) {
    return action->handler!=(uintptr_t)SIG_DFL && action->handler!=(uintptr_t)SIG_IGN;
}
struct md_kernel_action md_signal_view_native(int signal,struct md_kernel_action action,uintptr_t entry) {
    action.mask&=~MD_SIGNAL_SYS_BIT;
    if (caught(&action)) {
        if (entry) action.handler=entry;
        action.flags|=SA_SIGINFO;
        if (signal==SIGSYS) action.flags|=SA_NODEFER;
    } else if (signal==SIGSYS) action.handler=(uintptr_t)SIG_DFL;
    return action;
}
struct md_kernel_action md_signal_view_action(int signal,struct md_kernel_action native,
        const struct md_kernel_action *logical,uintptr_t entry) {
    if (caught(logical)) {
        /* The kernel can already have reset a SA_RESETHAND handler. Preserve
         * that disposition and native flag normalization, not our last write. */
        if (entry && native.handler==entry) native.handler=logical->handler;
        uintptr_t injected=SA_SIGINFO|(signal==SIGSYS ? SA_NODEFER : 0);
        native.flags=(native.flags&~injected)|(logical->flags&injected);
        native.mask=logical->mask&md_signal_blockable_mask();
    } else if (signal==SIGSYS && logical->handler==(uintptr_t)SIG_IGN
            && native.handler==(uintptr_t)SIG_DFL) native.handler=(uintptr_t)SIG_IGN;
    native.mask|=logical->mask&MD_SIGNAL_SYS_BIT;
    return native;
}
uint64_t md_signal_view_enter(int signal,const struct md_kernel_action *action,
        int blocked,const struct md_signal_wait_view *wait,uint64_t *saved) {
    *saved=wait && wait->active ? wait->saved : md_signal_view_mask(*saved,blocked);
    uint64_t mask=(wait && wait->active ? wait->temporary : *saved)|action->mask;
    if (!(action->flags&SA_NODEFER)) mask|=UINT64_C(1)<<(signal-1);
    return mask&md_signal_blockable_mask();
}
