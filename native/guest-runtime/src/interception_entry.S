#include <asm/unistd.h>
.text
.balign 4
.global md_guest_ready
.type md_guest_ready, %function
md_guest_ready:
    brk #0x5b0
    ret
.size md_guest_ready, .-md_guest_ready
.global md_guest_done
md_guest_done:
    brk #0x5b1
.global md_guest_exported
md_guest_exported:
    brk #0x5b3
.global md_guest_store
md_guest_store:
    stp x2, x3, [x0]
.global md_guest_stored
md_guest_stored:
    brk #0x5b4
.global md_guest_load_byte
md_guest_load_byte:
    ldrb w2, [x0]
.global md_guest_loaded_byte
md_guest_loaded_byte:
    brk #0x5b5
.global md_guest_store_ids
md_guest_store_ids:
    str w3, [x0]
    str w4, [x1]
    str w5, [x2]
.global md_guest_stored_ids
md_guest_stored_ids:
    brk #0x5b6
.global md_guest_allocate
.type md_guest_allocate, %function
md_guest_allocate:
    adrp x20, md_page_size
    ldr x20, [x20, :lo12:md_page_size]
    mov x0, #0
    mov x1, #0x80000
    add x1, x1, x20, lsl #1
    mov x2, #0
    mov x3, #0x22
    mov x4, #-1
    mov x5, #0
    mov x8, #__NR_mmap
    svc #0
    tbnz x0, #63, md_guest_allocated
    mov x19, x0
    add x0, x0, x20
    mov x1, #0x80000
    mov x2, #3
    mov x8, #__NR_mprotect
    svc #0
    tbnz x0, #63, 1f
    add x0, x19, #0x80000
    add x0, x0, x20
    b md_guest_allocated
1:
    mov x21, x0
    mov x0, x19
    mov x1, #0x80000
    add x1, x1, x20, lsl #1
    mov x8, #__NR_munmap
    svc #0
    mov x0, x21
.global md_guest_allocated
md_guest_allocated:
    brk #0x5b2
.size md_guest_allocate, .-md_guest_allocate
.balign 4
.global md_read_memory
.global md_write_memory
.type md_read_memory, %function
.type md_write_memory, %function
md_read_memory:
md_write_memory:
    cbz x2, 3f
.global md_guest_copy_begin
md_guest_copy_begin:
2:
    ldrb w9, [x1], #1
    strb w9, [x0], #1
    subs x2, x2, #1
    b.ne 2b
.global md_guest_copy_end
md_guest_copy_end:
3:
    mov x0, #0
    ret
.size md_read_memory, .-md_read_memory
.size md_write_memory, .-md_write_memory
.section .note.GNU-stack,"",%progbits
