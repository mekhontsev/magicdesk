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
    cbnz x8,single_busy_8
    single_mark owned
    md_svc_slot x8
    str x30,[x8,#MD_SVC_FRAME_OFFSET+240]
md_svc_single_prefix:
    nop
    single_mark borrow_lr
    md_svc_slot x30
    add x30,x30,#MD_SVC_FRAME_OFFSET
    md_svc_save_gprs x30
    single_mark saved_gprs
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
.section .note.GNU-stack,"",%progbits
