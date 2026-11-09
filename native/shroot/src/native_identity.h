#ifndef MD_NATIVE_IDENTITY_H
#define MD_NATIVE_IDENTITY_H
#include "adapter_buffer.h"
#include "raw.h"

/* Adapter bookkeeping must not issue application-filtered getpid/gettid.
 * The scalar reply belongs to the calling task, including CLONE_VM children. */
static inline uint64_t md_native_ids(void) {
#ifndef MD_USE_LIBC
    if (md_adapter_buffer_ready())
        return (uint64_t)md_guest_buffer_control(MD_BUFFER_NATIVE_IDENTITY,0,0);
#endif
    return ((uint64_t)RAW0(getpid)<<32)|(unsigned)RAW0(gettid);
}
static inline int md_native_tid(void) {
#ifdef MD_USE_LIBC
    return (int)RAW0(gettid);
#else
    return (int)(uint32_t)md_native_ids();
#endif
}
#endif
