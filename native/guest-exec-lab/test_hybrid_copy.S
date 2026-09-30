#include <asm/unistd.h>
.text
.balign 4
.global md_hybrid_copy
.type md_hybrid_copy, %function
md_hybrid_copy:
    cbz x2, md_hybrid_copy_done
1:
    ldrb w3, [x1], #1
    strb w3, [x0], #1
    subs x2, x2, #1
    b.ne 1b
.global md_hybrid_copy_done
md_hybrid_copy_done:
    brk #0x5a1
.size md_hybrid_copy, .-md_hybrid_copy
.balign 4
.global md_hybrid_string
.type md_hybrid_string, %function
md_hybrid_string:
    cbz x2, md_hybrid_string_done
2:
    ldrb w3, [x1], #1
    strb w3, [x0], #1
    cbz w3, md_hybrid_string_done
    subs x2, x2, #1
    b.ne 2b
.global md_hybrid_string_done
md_hybrid_string_done:
    brk #0x5a2
.size md_hybrid_string, .-md_hybrid_string
.balign 4
.global md_hybrid_getppid
.type md_hybrid_getppid, %function
md_hybrid_getppid:
    mov x8, #__NR_getppid
    svc #0
.global md_hybrid_getppid_return
md_hybrid_getppid_return:
    ret
.size md_hybrid_getppid, .-md_hybrid_getppid
.balign 4
.global md_hybrid_openat
.type md_hybrid_openat, %function
md_hybrid_openat:
    mov x1, x0
    mov x0, #-100
    mov x2, #0x80000
    mov x3, #0
    mov x8, #__NR_openat
    svc #0
.global md_hybrid_openat_return
md_hybrid_openat_return:
    ret
.size md_hybrid_openat, .-md_hybrid_openat
.section .note.GNU-stack,"",%progbits
