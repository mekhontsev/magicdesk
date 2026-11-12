#include "svc_frame.h"
#include "svc_frame.inc"
.arch_extension lse
.text
.balign 16
.global md_svc_single_begin,md_svc_single_end,md_svc_single_prefix
.global md_svc_single_dispatch,md_svc_single_done_branch,md_svc_single_kernel_branch
.global md_svc_single_state
.global md_svc_single_owner
.global md_svc_single_origin
.global md_svc_single_busy_branch,md_svc_single_done_replay,md_svc_single_kernel_replay,md_svc_single_busy_replay
.macro single_mark name
.global md_svc_single_\name
md_svc_single_\name:
.endm
md_svc_single_begin:
    md_svc_slot x8
    single_mark claim
    swpal x8,x8,[x8]
    single_mark claimed
    cbnz x8,md_svc_single_probe1
    single_mark owned
    md_svc_slot x8
    single_mark frame
    str x30,[x8,#MD_SVC_FRAME_OFFSET+240]
    single_mark borrow_lr
    mov x30,x8
    add x30,x30,#MD_SVC_FRAME_OFFSET
md_svc_single_prefix:
    nop
    md_svc_save_gprs x30
    single_mark saved_gprs
    /* Collision probes rewrite the claim word, never the native journal.
     * Clear the previous journal before entering private-stack execution. */
    str xzr,[x30,#MD_SVC_NATIVE_OFFSET-MD_SVC_FRAME_OFFSET]
    ldr x1,single_owner_8
    str x1,[x30,#MD_SVC_OWNER_OFFSET-MD_SVC_FRAME_OFFSET]
    mrs x8,nzcv
    str x8,[x30,#248]
    mov x8,sp
    str x8,[x30,#MD_SVC_SAVED_SP-MD_SVC_FRAME_OFFSET]
    single_mark saved_sp
    mov x0,x30
    sub x30,x30,#MD_SVC_FRAME_OFFSET
    add sp,x30,#(MD_SVC_SLOT_SIZE >> 12),lsl#12
    single_mark private_sp
    ldr x16,single_dispatch_8
    ldr x1,single_state_8
    ldr x2,single_origin_8
    single_mark invoke
    blr x16
    single_mark returned
    sub x8,sp,#(MD_SVC_SLOT_SIZE >> 12),lsl#12
    add x8,x8,#MD_SVC_FRAME_OFFSET
    adr x30,single_done_8
    cbnz w0,1f
    adr x30,single_kernel_8
1:  str x30,[x8,#272]
    single_mark outcome
    ldr x30,[x8,#MD_SVC_SAVED_SP-MD_SVC_FRAME_OFFSET]
    mov sp,x30
    ldr x30,[x8,#248]
    msr nzcv,x30
    md_svc_restore_gprs x8,8
    ldr x30,[x8,#272]
    br x30
single_done_8:
    single_mark done
    ldr x30,[x8,#240]
    sub x8,x8,#MD_SVC_FRAME_OFFSET
    stlr xzr,[x8]
md_svc_single_done_replay:
    nop
md_svc_single_done_branch:
    b .
single_kernel_8:
    single_mark kernel
    ldr x30,[x8,#240]
    sub x8,x8,#MD_SVC_FRAME_OFFSET
    stlr xzr,[x8]
md_svc_single_kernel_replay:
    nop
md_svc_single_kernel_branch:
    b .
single_busy_8:
md_svc_single_busy_replay:
    nop
md_svc_single_busy_branch:
    b .
/* A blocked native wait retains its frame. Probe disjoint alternatives before
 * falling back; only x8 is scratch until the selected frame saves x30. */
.macro single_probe attempt,next
    single_mark probe\attempt
    md_svc_slot x8,\attempt
    single_mark claimed\attempt
    swpal x8,x8,[x8]
    single_mark tested\attempt
    cbnz x8,\next
    md_svc_slot x8,\attempt
    b md_svc_single_frame
.endm
    single_probe 1,md_svc_single_probe2
    single_probe 2,md_svc_single_probe3
    single_probe 3,single_busy_8
    single_mark probes_end
.balign 8
md_svc_single_dispatch:
single_dispatch_8:
    .quad 0
md_svc_single_state:
single_state_8:
    .quad 0
md_svc_single_owner:
single_owner_8:
    .quad 0
md_svc_single_origin:
single_origin_8:
    .quad 0
md_svc_single_end:
.balign 4
.global md_svc_signal_checkpoint
md_svc_signal_checkpoint:
    svc #0
    brk #0
.section .note.GNU-stack,"",%progbits
