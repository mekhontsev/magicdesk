.text
#ifndef MD_NO_START
.global _start
.type _start, %function
_start:
    mov x0, sp
    bl md_boot
    brk #0
.size _start, .-_start
#endif

.global md_raw
.type md_raw, %function
md_raw:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
.global md_raw_return
md_raw_return:
    ret
.size md_raw, .-md_raw

.global md_signal_return
.type md_signal_return, %function
md_signal_return:
    mov x8, #139
    svc #0
    brk #0
.size md_signal_return, .-md_signal_return

.global md_enter
.type md_enter, %function
md_enter:
    mov x9, x0
    mov sp, x1
    mov x0, xzr
    br x9
.size md_enter, .-md_enter
.section .note.GNU-stack,"",%progbits
