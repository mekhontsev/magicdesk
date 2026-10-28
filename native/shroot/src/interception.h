#ifndef MD_INTERCEPTION_H
#define MD_INTERCEPTION_H
/* Internal admission only: outside Linux's errno range, never application EINTR. */
#define MD_BUFFER_ADMIT_RETRY (-8192)
#ifndef __ASSEMBLER__
#include <stdint.h>

#define MD_INTERCEPTION_MAGIC UINT64_C(0x4d44494e54455231)
#define MD_GUEST_MAP_IMAGE 0x4d440101
#define MD_CHECKPOINT_SITES(X) \
    X(ready,md_guest_ready_checkpoint) X(done,md_guest_done_checkpoint) X(allocated,md_guest_allocated) \
    X(exported,md_guest_exported_checkpoint) X(export_control,md_guest_export_control_checkpoint) \
    X(stored,md_guest_stored) X(loaded_byte,md_guest_loaded_byte) X(stored_ids,md_guest_stored_ids) \
    X(loaded_groups,md_guest_loaded_groups) X(stored_groups,md_guest_stored_groups) \
    X(fd_control,md_guest_fd_control_checkpoint) X(buffer_control,md_guest_buffer_control_checkpoint) \
    X(task_control,md_guest_task_control_checkpoint) X(task_completed,md_guest_task_completed) \
    X(task_collected,md_guest_task_collected) X(watch_completed,md_guest_watch_completed) X(enter,md_enter_checkpoint)
enum { MD_IMAGE_PROGRAM=0, MD_IMAGE_LOADER=1, MD_IMAGE_TAKE_PROGRAM=2, MD_IMAGE_TAKE_LOADER=3, MD_IMAGE_TAKE_EXECFD=4 };
#define MD_GUEST_SHM 0x4d440104
#define MD_GUEST_PROC_IMAGE 0x4d440106
#define MD_GUEST_IPC 0x4d440107
#define MD_GUEST_BUFFER 0x4d440108
#define MD_GUEST_FD_CHANNEL 0x4d440109
#define MD_GUEST_TASK_HELPER 0x4d44010a
#define MD_GUEST_TASK_MAPPING 0x4d44010f
#define MD_GUEST_PROC_FILE 0x4d44010b
enum md_dispatch_policy { MD_DISPATCH_PATHS=1, MD_DISPATCH_NETWORK_SCOPED=2, MD_DISPATCH_OFFLINE_SOCKET=4,
    MD_DISPATCH_NETWORK_CONTROL=8, MD_DISPATCH_NETWORK_DATA=16, MD_DISPATCH_DEVICE_EVENTS=32 };
/* Upper bits identify the already retained typed OFD; zero means no control.
 * This is call-local admission, never a cache indexed by a guest FD number. */
static inline unsigned md_dispatch_control(unsigned policy,int kind) {
    return policy|((unsigned)(kind+1)<<16);
}
static inline int md_dispatch_control_kind(unsigned policy) {
    return (int)(policy>>16)-1;
}
#define MD_GUEST_PID_FILTER 0x4d44010c
#define MD_GUEST_PID_SIGNAL_GROUP 0x4d44010d
enum md_export_control { MD_EXPORT_BEGIN=1, MD_EXPORT_READY, MD_EXPORT_OBSERVED };
#define MD_EXPORT_ADMIT (-4096L)
/* Register-only control works before/inside a mapped filesystem request too. */
struct md_export_reply { long endpoint; uint64_t token; };
#define MD_WATCH_TASK_AFFINE (-INT64_C(0x4000000000000000))
#define MD_WATCH_WAIT (MD_WATCH_TASK_AFFINE + 1)
/* Finish adapter ownership before a native call can block or deliver signals. */
#define MD_CALL_NATIVE (MD_WATCH_TASK_AFFINE + 2)
#define MD_IPC_WAIT (MD_WATCH_TASK_AFFINE + 3)
#define MD_CALL_CLONE (MD_WATCH_TASK_AFFINE + 4)

enum md_interception_kind {
    MD_INTERCEPT_DISPATCH = 41,
    MD_INTERCEPT_EXEC = 42,
    MD_INTERCEPT_OBSERVE = 43,
    MD_INTERCEPT_NATIVE = 44,
    MD_INTERCEPT_IDENTITY = 45,
    MD_INTERCEPT_WATCH = 46,
    MD_INTERCEPT_MEMORY = 47,
    MD_INTERCEPT_FD = 48,
    MD_INTERCEPT_CWD = 49,
    MD_INTERCEPT_CAPTURE = 50,
    MD_INTERCEPT_TASK = 51,
    MD_INTERCEPT_PROC = 52,
    MD_INTERCEPT_PROCESS = 53,
    MD_INTERCEPT_READINESS = 54
};
/* Low descriptor numbers fit in TRACE's data alongside an explicit close tag.
 * Larger/negative numbers retain the full descriptor-observation path. */
enum { MD_INTERCEPT_CLOSE=0x8000, MD_INTERCEPT_CLOSE_MAX=0x7fff };

/* Bootstrap-owned entry points, published before entering guest code. The
 * supervisor reads this descriptor at each image handshake, not linker symbols. */
struct md_interception_abi {
    uint64_t magic, size;
    /* dispatch: x0 syscall, x1..x6 arguments, x9 policy and retained control kind,
     * x10 optional operation-owned input buffer (ninth C argument).
     * x7 is reserved by the kernel at ARM64 syscall stops. */
    uintptr_t ready, done, allocate, allocated, dispatch, export_fd, exported;
    uintptr_t done_entry, exported_entry;
    uintptr_t export_control, store, stored, load_byte, loaded_byte;
    uintptr_t proc_open, proc_open_path;
    uintptr_t store_ids, stored_ids, raw_gate, copy_begin, copy_end;
    uintptr_t watch_gate,watch_return;
    uintptr_t load_groups, loaded_groups, store_groups, stored_groups;
    uintptr_t enter;
    uintptr_t ipc_wait;
    uintptr_t fd_control;
    uintptr_t capture_gate;
    uintptr_t buffer_control;
    uintptr_t task_control, task_call, task_return, task_abort;
    uintptr_t task_collect, task_collected;
    uintptr_t buffer_admit;
    uintptr_t buffer_result;
    uintptr_t buffer_enter;
    uintptr_t native_begin, native_call, native_return, native_end;
    uintptr_t svc_runtime;
    uintptr_t code_begin,code_end;
    uintptr_t task_completed,watch_completed;
};
long md_guest_buffer_admit(unsigned purpose);
struct md_task_call_arguments;
long md_guest_buffer_replay(const struct md_task_call_arguments *);
void md_cooperative_revoke(void);

enum { MD_INTERCEPTION_COOPERATIVE=1, MD_INTERCEPTION_MACHINE=2, MD_INTERCEPTION_STATISTICS=4 };
/* Returns admitted entry capabilities, or a negative errno. */
int md_interception_install(int inherited);
long md_shm_dispatch(long, const unsigned long *);
long md_ipc_dispatch(long, const unsigned long *);
long md_pid_filter_install(void);
int md_interception_map_image(int fd);
__attribute__((noreturn)) void md_interception_enter_image(uintptr_t entry, uintptr_t stack,
    const void *image, void *aux, unsigned count, int machine);
#endif
#endif
