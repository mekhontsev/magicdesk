#ifndef MD_INTERCEPTION_H
#define MD_INTERCEPTION_H
#include <stdint.h>

#define MD_INTERCEPTION_MAGIC UINT64_C(0x4d44494e54455231)
#define MD_GUEST_MAP_IMAGE 0x4d440101
#define MD_GUEST_ENTER_IMAGE 0x4d440102
#define MD_GUEST_WATCH_FILTER 0x4d440103
#define MD_GUEST_SHM 0x4d440104
#define MD_GUEST_SHM_FILTER 0x4d440105
#define MD_GUEST_PROC_IMAGE 0x4d440106
#define MD_GUEST_IPC 0x4d440107
#define MD_PROC_IMAGE_LINK (1U << 31)
#define MD_WATCH_TASK_AFFINE (-INT64_C(0x4000000000000000))
#define MD_WATCH_WAIT (MD_WATCH_TASK_AFFINE + 1)
#define MD_WATCH_NATIVE (MD_WATCH_TASK_AFFINE + 2)
#define MD_IPC_WAIT (MD_WATCH_TASK_AFFINE + 3)

enum md_interception_kind {
    MD_INTERCEPT_DISPATCH = 41,
    MD_INTERCEPT_EXEC = 42,
    MD_INTERCEPT_OBSERVE = 43,
    MD_INTERCEPT_NATIVE = 44,
    MD_INTERCEPT_IDENTITY = 45,
    MD_INTERCEPT_WATCH = 46,
    MD_INTERCEPT_MEMORY = 47
};

/* Bootstrap-owned entry points, published before entering guest code. The
 * supervisor reads this descriptor at each image handshake, not linker symbols. */
struct md_interception_abi {
    uint64_t magic, size;
    uintptr_t ready, done, allocate, allocated, dispatch, export_fd, exported;
    uintptr_t proc_export, store, stored, load_byte, loaded_byte;
    uintptr_t store_ids, stored_ids, raw_gate, copy_begin, copy_end;
    uintptr_t watch_gate;
    uintptr_t load_groups, loaded_groups, store_groups, stored_groups;
    uintptr_t enter;
    uintptr_t ipc_wait, ipc_wait_result;
};

int md_interception_install(int inherited);
long md_shm_dispatch(long, const unsigned long *);
long md_ipc_dispatch(long, const unsigned long *);
int md_interception_map_image(int fd, int loader);
int md_interception_enter_image(void *aux, unsigned count);
#endif
