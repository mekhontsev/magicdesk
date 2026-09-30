#ifndef MD_INTERCEPTION_H
#define MD_INTERCEPTION_H
#include <stdint.h>

#define MD_INTERCEPTION_MAGIC UINT64_C(0x4d44494e54455231)
#define MD_GUEST_MAP_IMAGE 0x4d440101
#define MD_GUEST_ENTER_IMAGE 0x4d440102

enum md_interception_kind {
    MD_INTERCEPT_DISPATCH = 41,
    MD_INTERCEPT_EXEC = 42,
    MD_INTERCEPT_OBSERVE = 43,
    MD_INTERCEPT_NATIVE = 44,
    MD_INTERCEPT_IDENTITY = 45
};

/* Bootstrap-owned entry points, published before entering guest code. The
 * supervisor reads this descriptor at each image handshake, not linker symbols. */
struct md_interception_abi {
    uint64_t magic, size;
    uintptr_t ready, done, allocate, allocated, dispatch, export_fd, exported;
    uintptr_t proc_export, store, stored, load_byte, loaded_byte;
    uintptr_t store_ids, stored_ids, raw_gate, copy_begin, copy_end;
};

int md_interception_install(int inherited);
int md_interception_map_image(int fd, int loader);
int md_interception_enter_image(void *aux, unsigned count);
#endif
