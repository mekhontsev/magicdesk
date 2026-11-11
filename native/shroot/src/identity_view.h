#ifndef MD_IDENTITY_VIEW_H
#define MD_IDENTITY_VIEW_H
#include "identity_call.h"
#include <stdatomic.h>

/* Each scalar is independently observable, just like separate Linux calls.
 * Zero means unavailable; bit 32 distinguishes a published UID/GID of zero. */
struct md_identity_view { atomic_uint_fast64_t ids[4]; };
static inline int md_identity_view_index(long nr) {
    switch (nr) {
    case SYS_getuid: return 0;
    case SYS_geteuid: return 1;
    case SYS_getgid: return 2;
    case SYS_getegid: return 3;
    default: return -1;
    }
}
static inline int md_identity_view_read(const struct md_identity_view *view,long nr,int64_t *result) {
    int i=md_identity_view_index(nr);
    uint64_t value=i<0 ? 0 : atomic_load_explicit(&view->ids[i],memory_order_acquire);
    if (!value) return 0;
    *result=(uint32_t)value;
    return 1;
}
static inline void md_identity_view_publish(struct md_identity_view *view,const struct md_identity *id) {
    const long calls[]={SYS_getuid,SYS_geteuid,SYS_getgid,SYS_getegid};
    for (unsigned group=0;group<2;++group) {
        int stable=md_user_namespace_map_stable(id->user_namespace,group);
        for (unsigned i=group*2;i<group*2+2;++i) {
            uint64_t value=stable ? (UINT64_C(1)<<32)|(uint32_t)md_identity_scalar_result(id,calls[i]) : 0;
            if (atomic_load_explicit(&view->ids[i],memory_order_relaxed)!=value)
                atomic_store_explicit(&view->ids[i],value,memory_order_release);
        }
    }
}
#endif
