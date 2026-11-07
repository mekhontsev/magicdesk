#ifndef MD_SIGNAL_ENTRY_H
#define MD_SIGNAL_ENTRY_H
#define MD_SIGNAL_CONTEXT_MASK 40
#define MD_SIGNAL_INFO_PACKET 24
#define MD_SIGNAL_ENTRY_INFO 32
#ifndef __ASSEMBLER__
#include <stdint.h>
#include <stdatomic.h>
#include <stddef.h>
#include <ucontext.h>
#include <signal.h>
#include <string.h>
struct md_signal_frame { siginfo_t info; ucontext_t context; };
/* EXTRA records contain location-relative pointers. Unknown records keep the
 * original kernel path rather than guessing how to relocate their payload. */
static inline int md_signal_frame_relocatable(const struct md_signal_frame *frame) {
    const unsigned char *data=frame->context.uc_mcontext.__reserved;
    size_t size=sizeof(frame->context.uc_mcontext.__reserved);
    for (size_t offset=0;offset+8<=size;) {
        uint32_t header[2]; memcpy(header,data+offset,sizeof(header));
        if (!header[0]) return !header[1];
        switch (header[0]) {
        case UINT32_C(0x46508001): /* FPSIMD */
        case UINT32_C(0x45535201): /* ESR */
        case UINT32_C(0x53564501): /* SVE */
        case UINT32_C(0x54504902): /* TPIDR2 */
        case UINT32_C(0x54366345): /* ZA */
        case UINT32_C(0x5a544e01): /* ZT */
        case UINT32_C(0x46504d52): /* FPMR */
            break;
        default: return 0;
        }
        if (header[1]<16 || header[1]%16 || header[1]>size-offset) return 0;
        offset+=header[1];
    }
    return 0;
}
struct md_signal_entry {
    uintptr_t handler;
    uint64_t mask;
    _Atomic uint32_t pending;
    uint32_t padding;
    uint64_t reserved;
    siginfo_t info;
};
_Static_assert(offsetof(struct md_signal_entry,pending)==16,"signal entry acknowledgement");
_Static_assert(offsetof(struct md_signal_entry,info)==MD_SIGNAL_ENTRY_INFO,"signal entry information");
_Static_assert(offsetof(siginfo_t,si_value)==MD_SIGNAL_INFO_PACKET,"signal packet transport");
_Static_assert(sizeof(siginfo_t)==128,"signal information size");
_Static_assert(offsetof(ucontext_t,uc_sigmask)==MD_SIGNAL_CONTEXT_MASK,"signal mask offset");
#endif
#endif
