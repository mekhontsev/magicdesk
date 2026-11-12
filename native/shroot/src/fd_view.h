#ifndef MD_FD_VIEW_H
#define MD_FD_VIEW_H
#include <stdatomic.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <fcntl.h>

/* Value-only authority published under an exclusive descriptor-table turn.
 * The resident caller defers signals. Stops, borrowed execution and every new
 * owner request revoke the view before another actor can replace the slot.
 * Mutable OFD state stays in the kernel; no offset/status/size is cached. */
#define MD_FD_VIEW_RESULT UINT32_MAX
struct md_fd_view {
    atomic_uint descriptor;
    unsigned flags,kind;
};
static inline void md_fd_view_revoke(struct md_fd_view *v) {
    atomic_store_explicit(&v->descriptor,0,memory_order_release);
}
static inline void md_fd_view_publish(struct md_fd_view *v,unsigned descriptor,unsigned flags,unsigned kind) {
    v->flags=flags; v->kind=kind;
    atomic_store_explicit(&v->descriptor,descriptor,memory_order_release);
}
/* Ordinary data-file queries only. A device's ioctl with the same numeric
 * command can have different semantics and must keep its typed owner. */
static inline int md_fd_view_query(long number,const uint64_t args[6],unsigned kind) {
    if (number==SYS_fcntl) return (unsigned)args[1]==F_GETFD || (unsigned)args[1]==F_GETFL;
    if (kind!=S_IFREG) return 0;
    return number==SYS_lseek || (number==SYS_ioctl && (unsigned)args[1]==FIONREAD);
}
#endif
