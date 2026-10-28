#include <asm/unistd.h>
#include "interception.h"
.text
.balign 4
.global md_guest_buffer_admit
.type md_guest_buffer_admit, %function
md_guest_buffer_admit:
    mov x9, x0
    mov x1, xzr
    mov x2, xzr
    mov x3, xzr
    mov x4, xzr
    mov x5, xzr
    mov x8, #__NR_gettid
    b 1f
.global md_guest_buffer_replay
.type md_guest_buffer_replay, %function
md_guest_buffer_replay:
    mov x10, x0
    ldr x8, [x10]
    ldp x0, x1, [x10, #8]
    ldp x2, x3, [x10, #24]
    ldp x4, x5, [x10, #40]
    mov x9, x0
1:
    mov x0, x9
    svc #0
.global md_guest_buffer_admitted
md_guest_buffer_admitted:
    /* A pending ptrace interrupt takes its real stop before this retry. */
    cmn x0, #-MD_BUFFER_ADMIT_RETRY
    b.eq 1b
    ret
.size md_guest_buffer_admit, .-md_guest_buffer_admit
.size md_guest_buffer_replay, .-md_guest_buffer_replay
