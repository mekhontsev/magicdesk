.text
.global md_clone_resume
.type md_clone_resume,%function
md_clone_resume:
    mov x8, #220
    svc #0
.global md_clone_return
md_clone_return:
    cbnz x0, 1f
    mov x0, sp
    bl md_clone_child
    brk #0
1:  ret
.size md_clone_resume, .-md_clone_resume

.global md_restore_frame
.type md_restore_frame,%function
md_restore_frame:
    mov sp, x0
    b md_signal_return
.size md_restore_frame, .-md_restore_frame

.global md_unmap_exit
.type md_unmap_exit,%function
md_unmap_exit:
    mov x9, x2
    // No stack access after releasing the thread's runtime stack.
    mov x2, x1
    mov x1, x0
    mov x0, #215
    adr x30, 1f
    b md_raw
1:  mov x1, x9
    mov x0, #93
    b md_raw
.size md_unmap_exit, .-md_unmap_exit
.section .note.GNU-stack,"",%progbits
