#include <asm/unistd.h>
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
.global md_raw_syscall
md_raw_syscall:
    svc #0
.global md_raw_return
md_raw_return:
    ret
.size md_raw, .-md_raw

.global md_socket_native
.type md_socket_native, %function
md_socket_native:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
.global md_socket_native_return
md_socket_native_return:
    ret
.size md_socket_native, .-md_socket_native

.global md_enter
.type md_enter, %function
md_enter:
    mov x8, #__NR_gettid
.global md_enter_checkpoint
md_enter_checkpoint:
    svc #0
    ret
.size md_enter, .-md_enter
.section .note.GNU-stack,"",%progbits
