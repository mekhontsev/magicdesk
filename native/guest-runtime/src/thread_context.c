#define _GNU_SOURCE
#include "thread_context.h"
#include "raw.h"
#include <asm/sigcontext.h>
#include <errno.h>
#include <linux/sched.h>
#include <signal.h>
#include <sys/mman.h>

#define MD_SIGNAL_STACK_SIZE (512U * 1024U)
struct thread_context { int parent_owned; };
struct signal_frame { siginfo_t info; ucontext_t uc; };
_Static_assert(offsetof(struct signal_frame, uc) == 128, "AArch64 rt_sigframe ABI");

static size_t mapping_size(void) { return MD_SIGNAL_STACK_SIZE + 3 * md_page_size; }
static long allocate(void) {
    long address = RAW6(mmap, 0, mapping_size(), PROT_NONE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (address < 0) return address;
    long result = RAW3(mprotect, address + md_page_size,
        MD_SIGNAL_STACK_SIZE + md_page_size, PROT_READ | PROT_WRITE);
    if (result < 0) { RAW2(munmap, address, mapping_size()); return result; }
    return address;
}
static stack_t signal_stack(long mapping) {
    return (stack_t){.ss_sp = (void *)(mapping + 2 * md_page_size),
        .ss_size = MD_SIGNAL_STACK_SIZE};
}
int md_thread_initialize(void) {
    long mapping = allocate();
    if (mapping < 0) return (int)mapping;
    stack_t stack = signal_stack(mapping);
    long result = RAW2(sigaltstack, &stack, 0);
    if (result < 0) RAW2(munmap, mapping, mapping_size());
    return (int)result;
}

// The kernel owns this frame format. Preserve all extension records, including
// SVE/SME data outside __reserved; only extra_context's absolute pointer moves.
static long copy_frame(struct signal_frame *source, uintptr_t top,
        struct signal_frame **out) {
    size_t bytes = sizeof(*source), extra_offset = 0;
    unsigned char *records = source->uc.uc_mcontext.__reserved;
    size_t capacity = sizeof(source->uc.uc_mcontext.__reserved);
    for (size_t offset = 0; offset + sizeof(struct _aarch64_ctx) <= capacity;) {
        struct _aarch64_ctx *record = (void *)(records + offset);
        if (!record->magic && !record->size) break;
        if (record->size < 16 || record->size % 16 || record->size > capacity - offset)
            return -EINVAL;
        if (record->magic == EXTRA_MAGIC) {
            if (extra_offset || record->size != sizeof(struct extra_context)) return -EINVAL;
            struct extra_context *extra = (void *)record;
            uintptr_t expected = ((uintptr_t)extra + sizeof(*extra) + 16);
            if (extra->datap != expected || extra->size > MD_SIGNAL_STACK_SIZE / 2)
                return -ENOTSUP;
            size_t end = extra->datap - (uintptr_t)source + extra->size;
            if (end > bytes) bytes = end;
            extra_offset = (uintptr_t)extra - (uintptr_t)source;
        }
        offset += record->size;
    }
    if (bytes > MD_SIGNAL_STACK_SIZE / 2) return -E2BIG;
    struct signal_frame *target = (void *)((top - bytes) & ~15UL);
    memcpy(target, source, bytes);
    if (extra_offset) {
        struct extra_context *extra = (void *)((char *)target + extra_offset);
        extra->datap += (uintptr_t)target - (uintptr_t)source;
    }
    *out = target;
    return 0;
}

long md_thread_clone(unsigned long *a, ucontext_t *uc) {
    unsigned long flags = a[0];
    // A separate process sharing VM could exec while its parent retains the
    // mapping. Admit only lifetimes with kernel thread-exit or vfork ownership.
    if ((flags & CLONE_VM) && !(flags & (CLONE_THREAD | CLONE_VFORK))) return -ENOTSUP;
    // A fork without a replacement stack can return through its private copy
    // of the existing frame. Shared-VM children require their own signal stack.
    if (!(flags & CLONE_VM) && !a[1])
        return RAW5(clone, flags, 0, a[2], a[3], a[4]);
    uintptr_t child_stack = a[1] ? a[1] : (flags & CLONE_VFORK) ? uc->uc_mcontext.sp : 0;
    if (!child_stack || child_stack % 16) return -EINVAL;
    long mapping = allocate();
    if (mapping < 0) return mapping;
    stack_t stack = signal_stack(mapping);
    struct thread_context *thread = (void *)(mapping + md_page_size);
    thread->parent_owned = !!(flags & CLONE_VFORK);
    struct signal_frame *frame;
    long result = copy_frame((void *)((char *)uc - offsetof(struct signal_frame, uc)),
        (uintptr_t)stack.ss_sp + stack.ss_size, &frame);
    if (result < 0) { RAW2(munmap, mapping, mapping_size()); return result; }
    frame->uc.uc_stack = stack;
    frame->uc.uc_mcontext.regs[0] = 0;
    frame->uc.uc_mcontext.sp = child_stack;
    uint64_t mask = ~(1UL << (SIGSYS - 1)), previous;
    result = RAW4(rt_sigprocmask, SIG_SETMASK, &mask, &previous, sizeof(mask));
    if (!result) {
        result = md_clone_resume(flags, frame, (void *)a[2], (void *)a[3], (void *)a[4]);
        RAW4(rt_sigprocmask, SIG_SETMASK, &previous, 0, sizeof(previous));
    }
    // vfork completion is the kernel event proving that the child has exec'd
    // or exited. Only then may the suspended parent release its shared mapping.
    if (result < 0 || !(flags & CLONE_VM) || (flags & CLONE_VFORK))
        RAW2(munmap, mapping, mapping_size());
    return result;
}
void md_clone_child(void *address) {
    struct signal_frame *frame = address;
    long result = RAW2(sigaltstack, &frame->uc.uc_stack, 0);
    if (result < 0) md_die("install child syscall stack", result);
    md_restore_frame(frame);
}
long md_thread_altstack(unsigned long *a) {
    stack_t requested;
    if (a[0]) {
        long result = md_read_memory(&requested, (void *)a[0], sizeof(requested));
        if (result < 0) return result;
        // Runtime-owned SIGSYS must never consume a libc-sized guest stack.
        // Application-owned alternate signal stacks need a separate signal adapter.
        if (requested.ss_flags != SS_DISABLE) return -ENOTSUP;
    }
    stack_t disabled = {.ss_flags = SS_DISABLE};
    return a[1] ? md_write_memory((void *)a[1], &disabled, sizeof(disabled)) : 0;
}
void md_thread_exit(int status, ucontext_t *uc) {
    uintptr_t mapping = (uintptr_t)uc->uc_stack.ss_sp - 2 * md_page_size;
    struct thread_context *thread = (void *)(mapping + md_page_size);
    uint64_t mask = ~(1UL << (SIGSYS - 1));
    RAW4(rt_sigprocmask, SIG_SETMASK, &mask, 0, sizeof(mask));
    if (!thread->parent_owned) md_unmap_exit((void *)mapping, mapping_size(), status);
    RAW1(exit, status);
    __builtin_unreachable();
}
