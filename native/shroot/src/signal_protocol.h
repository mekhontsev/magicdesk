#ifndef MD_SIGNAL_PROTOCOL_H
#define MD_SIGNAL_PROTOCOL_H
#include <signal.h>
#include <stdint.h>

static inline uint64_t md_signal_blockable_mask(void) {
    return UINT64_MAX & ~(UINT64_C(1)<<(SIGKILL-1)) & ~(UINT64_C(1)<<(SIGSTOP-1));
}

/* Service checkpoints are ptrace events, not forced application signals. */
static inline uint64_t md_signal_protocol_mask(void) {
    return md_signal_blockable_mask();
}
static inline uint64_t md_signal_async_mask(void) {
    return md_signal_blockable_mask() & ~(UINT64_C(1)<<(SIGSEGV-1))
        & ~(UINT64_C(1)<<(SIGBUS-1)) & ~(UINT64_C(1)<<(SIGILL-1))
        & ~(UINT64_C(1)<<(SIGFPE-1)) & ~(UINT64_C(1)<<(SIGSYS-1));
}
static inline int md_signal_synchronous(const siginfo_t *info) {
    return info->si_code>0 && (info->si_signo==SIGTRAP || info->si_signo==SIGILL
        || info->si_signo==SIGBUS || info->si_signo==SIGSEGV || info->si_signo==SIGFPE
        || info->si_signo==SIGSYS);
}
#endif
