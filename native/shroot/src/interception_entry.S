#include <asm/unistd.h>
.text
.balign 4
/* Markers have a fixed syscall identity, never a register-copy payload or the
 * preceding native call's number. x19 preserves x8 for bounded group loads. */
.macro MARKER name
    mov x19, x8
    mov x8, #__NR_gettid
.global \name
\name:
    svc #0
.endm
.global md_guest_entry_kernel
.type md_guest_entry_kernel, %function
md_guest_entry_kernel:
    /* This is an application syscall site, never the raw adapter gate.
     * Kernel seccomp sees the real number and all six original arguments. */
    mov x8, x0
    mov x9, x1
    ldp x0, x1, [x9]
    ldp x2, x3, [x9, #16]
    ldp x4, x5, [x9, #32]
    svc #0
    ret
.size md_guest_entry_kernel, .-md_guest_entry_kernel
.global md_guest_dispatch_entry
.type md_guest_dispatch_entry, %function
md_guest_dispatch_entry:
    /* The kernel restores x7 after ARM64 syscall stops. Supervisor policy
     * arrives in x9; only userspace turns it into the eighth C argument. */
    mov x7, x9
    stp x10, x30, [sp, #-16]!
    bl md_guest_dispatch
    ldr x30, [sp, #8]
    add sp, sp, #16
    ret
.size md_guest_dispatch_entry, .-md_guest_dispatch_entry
.global md_guest_task_call
.global md_guest_task_collect
md_guest_task_collect:
    bl md_task_receive_collect
    mov x8, #__NR_gettid
.global md_guest_task_collected
md_guest_task_collected:
    svc #0
md_guest_task_call:
    svc #0
.global md_guest_task_return
md_guest_task_return:
    MARKER md_guest_task_completed
.global md_guest_task_abort
md_guest_task_abort:
    mov x0, #0
    mov x8, #__NR_exit
    svc #0
.global md_guest_task_abort_return
md_guest_task_abort_return:
    brk #0x5bf
.global md_guest_open_anchor
.type md_guest_open_anchor, %function
md_guest_open_anchor:
    /* x4 selects cwd or a source FD for ordered provenance capture; the
     * kernel uses only openat's four arguments. */
    mov x8, #__NR_openat
    svc #0
.global md_guest_anchor_return
md_guest_anchor_return:
    ret
.size md_guest_open_anchor, .-md_guest_open_anchor
.global md_guest_ipc_wait
md_guest_ipc_wait:
    sub sp, sp, #16
    stp x1, x2, [sp]
    mov x2, sp
    cmp x3, #0
    csel x2, x2, xzr, ne
    mov x0, xzr
    mov x1, xzr
    mov x3, xzr
    mov x4, #8
    mov x8, #__NR_ppoll
    b md_raw_return - 4
.balign 4
.global md_guest_watch_gate
md_guest_watch_gate:
    svc #0
.global md_guest_watch_return
md_guest_watch_return:
    MARKER md_guest_watch_completed
.global md_guest_store
md_guest_store:
    stp x2, x3, [x0]
    MARKER md_guest_stored
.global md_guest_load_byte
md_guest_load_byte:
    ldrb w2, [x0]
    MARKER md_guest_loaded_byte
.global md_guest_store_ids
md_guest_store_ids:
    str w3, [x0]
    str w4, [x1]
    str w5, [x2]
    MARKER md_guest_stored_ids
/* Bounded task-affine credential transfer, including nondumpable programs.
 * x0 points at 1..16 gids, x1 is their count, w3..w18 carry their values. */
.global md_guest_load_groups
md_guest_load_groups:
.irp reg,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18
    ldr w\reg, [x0], #4
    subs x1, x1, #1
    b.eq 1f
.endr
1:
    MARKER md_guest_loaded_groups
.global md_guest_store_groups
md_guest_store_groups:
.irp reg,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18
    str w\reg, [x0], #4
    subs x1, x1, #1
    b.eq 1f
.endr
1:
    MARKER md_guest_stored_groups
.global md_guest_allocate
.type md_guest_allocate, %function
md_guest_allocate:
    adrp x20, md_page_size
    ldr x20, [x20, :lo12:md_page_size]
    mov x0, #0
    mov x1, #0x80000
    add x1, x1, x20, lsl #1
    mov x2, #0
    mov x3, #0x22
    mov x4, #-1
    mov x5, #0
    mov x8, #__NR_mmap
    bl md_raw_syscall
    tbnz x0, #63, 2f
    mov x19, x0
    add x0, x0, x20
    mov x1, #0x80000
    mov x2, #3
    mov x8, #__NR_mprotect
    bl md_raw_syscall
    tbnz x0, #63, 1f
    add x0, x19, #0x80000
    add x0, x0, x20
    b 2f
1:
    mov x21, x0
    mov x0, x19
    mov x1, #0x80000
    add x1, x1, x20, lsl #1
    mov x8, #__NR_munmap
    bl md_raw_syscall
    mov x0, x21
2:
    MARKER md_guest_allocated
.size md_guest_allocate, .-md_guest_allocate
.section .note.GNU-stack,"",%progbits
