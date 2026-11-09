#include <asm/unistd.h>
.text
.balign 4
.global md_guest_compare_exchange
.type md_guest_compare_exchange, %function
md_guest_compare_exchange:
    /* Stack/TLS-free: the private executor may retain an exited image's mm.
     * x0=word, w1=expected, w2=replacement; return the observed word in w0. */
1:
    ldaxr w3, [x0]
    cmp w3, w1
    b.ne 2f
    stlxr w4, w2, [x0]
    cbnz w4, 1b
    b 3f
2:
    clrex
3:
    mov w0, w3
    mov x19, x8
    mov x8, #__NR_gettid
.global md_guest_exchanged
md_guest_exchanged:
    svc #0
    b md_guest_exchanged
.size md_guest_compare_exchange, .-md_guest_compare_exchange
.section .note.GNU-stack,"",%progbits
