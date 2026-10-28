.text
.global md_interruptible_syscall
.type md_interruptible_syscall, %function
md_interruptible_syscall:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
.global md_interruptible_begin
md_interruptible_begin:
    ldar w9, [x6]
    cbnz w9, md_interruptible_cancelled
    svc #0
.global md_interruptible_end
md_interruptible_end:
    ret
.global md_interruptible_cancelled
md_interruptible_cancelled:
    mov x0, #-4
    ret
.size md_interruptible_syscall, .-md_interruptible_syscall
.section .note.GNU-stack,"",%progbits
