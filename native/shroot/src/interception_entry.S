#include <asm/unistd.h>
#include "task_stack.h"
#include "signal_entry.h"
#include "guest_execution.h"
.text
.balign 4
/* Markers have a fixed syscall identity. x19 records the preceding native
 * call so cancellation can recognize its result at any marker instruction. */
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
.global md_guest_signal_entry
.global md_guest_signal_entry_committed
.global md_guest_signal_entry_end
.type md_guest_signal_entry, %function
md_guest_signal_entry:
    /* No interrupted application register is borrowed. Restore the original
     * information before exposing the kernel-built frame to the handler. */
    ldr x9, [x1, #MD_SIGNAL_INFO_PACKET]
    ldp x17, x10, [x9]
    str x10, [x2, #MD_SIGNAL_CONTEXT_MASK]
.irp offset,0,16,32,48,64,80,96,112
    ldp x10, x11, [x9, #(MD_SIGNAL_ENTRY_INFO+\offset)]
    stp x10, x11, [x1, #\offset]
.endr
    add x9, x9, #16
    stlr wzr, [x9]
md_guest_signal_entry_committed:
    br x17
md_guest_signal_entry_end:
.size md_guest_signal_entry, .-md_guest_signal_entry
.global md_guest_dispatch_entry
.type md_guest_dispatch_entry, %function
md_guest_dispatch_entry:
    /* A stopped task has no resident channel. Materialize the same execution
     * record on its owned stack, without another mapping or acknowledgement.
     * x7/x8 belong to the kernel's syscall-stop ABI and are not transport. */
    sub sp, sp, #(MD_EXECUTION_SIZE+8)
    stp x0, x1, [sp]
    stp x2, x3, [sp, #16]
    stp x4, x5, [sp, #32]
    str x6, [sp, #48]
    str w9, [sp, #MD_EXECUTION_POLICY_OFFSET]
    str w10, [sp, #MD_EXECUTION_CONTROL_OFFSET]
    str x11, [sp, #MD_EXECUTION_PREPARED_OFFSET]
    str x30, [sp, #MD_EXECUTION_SIZE]
    mov x0, sp
    bl md_guest_execute
    ldr x30, [sp, #MD_EXECUTION_SIZE]
    add sp, sp, #(MD_EXECUTION_SIZE+8)
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
    b md_guest_task_call
.balign 4
.global md_guest_watch_gate
md_guest_watch_gate:
    svc #0
.global md_guest_watch_return
md_guest_watch_return:
    MARKER md_guest_watch_completed
.global md_guest_allocate
.type md_guest_allocate, %function
md_guest_allocate:
    adrp x20, md_page_size
    ldr x20, [x20, :lo12:md_page_size]
    mov x0, #0
    mov x1, #MD_TASK_STACK_SIZE
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
    mov x1, #MD_TASK_STACK_SIZE
    mov x2, #3
    mov x8, #__NR_mprotect
    bl md_raw_syscall
    tbnz x0, #63, 1f
    add x0, x19, #MD_TASK_STACK_SIZE
    add x0, x0, x20
    b 2f
1:
    mov x21, x0
    mov x0, x19
    mov x1, #MD_TASK_STACK_SIZE
    add x1, x1, x20, lsl #1
    mov x8, #__NR_munmap
    bl md_raw_syscall
    mov x0, x21
2:
    MARKER md_guest_allocated
.size md_guest_allocate, .-md_guest_allocate
.section .note.GNU-stack,"",%progbits
