.section .text
.global _start
.hidden _start
_start:
    mov x0, #-100
    adr x1, path
    mov x2, #0
    mov x3, #0
    mov x8, #56
    svc #0
    cmp x0, #0
    b.lt failure
    mov x8, #57
    svc #0
    mov x0, #1
    adr x1, message
    mov x2, #message_size
    mov x8, #64
    svc #0
    cmp x0, x2
    b.ne failure
    mov x0, #0
    b finish
failure:
    mov x0, #1
finish:
    mov x8, #94
    svc #0
    brk #0
path:
    .asciz "/etc/passwd"
message:
    .ascii "PASS freestanding ELF: no libc, interpreter or mapped headers; raw SVC\n"
.equ message_size, . - message
.section .note.GNU-stack,"",%progbits
