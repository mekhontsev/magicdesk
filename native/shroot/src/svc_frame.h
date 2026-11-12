#ifndef MD_SVC_FRAME_H
#define MD_SVC_FRAME_H

/* SVC private frames. SP distributes contention; it is NOT task identity.
 * Each slot reserves metadata, a guard, and a private stack in 64K-aligned
 * ranges. Untouched pages remain demand-zero on smaller-page kernels. */
#define MD_SVC_POOL_HIGH 0x21
#define MD_SVC_POOL_BASE 0x2100000000UL
#define MD_SVC_SLOT_SHIFT 18
#define MD_SVC_SLOT_SIZE (1 << MD_SVC_SLOT_SHIFT)
#define MD_SVC_SLOT_COUNT 64
#define MD_SVC_SLOT_ALTERNATIVES 4
#define MD_SVC_SLOT_XOR(attempt) (((attempt)&1)*32+((attempt)&2)*8)
#define MD_SVC_POOL_SIZE (MD_SVC_SLOT_COUNT*MD_SVC_SLOT_SIZE)
#define MD_SVC_FRAME_OFFSET 64
#define MD_SVC_GUARD_STATE 8
#define MD_SVC_GUARD_OFFSET 65536
#define MD_SVC_GUARD_SIZE 65536
#define MD_SVC_OWNER_OFFSET 384
#define MD_SVC_SAVED_SP 864
#define MD_SVC_CLIENT_OFFSET 872
#define MD_SVC_NATIVE_OFFSET 880

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
int md_svc_frame_relocate(unsigned char *,size_t,uintptr_t);
size_t md_svc_single_size(void);
#endif

#endif
