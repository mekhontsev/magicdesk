#include <asm/unistd.h>
.text
.balign 4
.global md_test_task_call
md_test_task_call:
    svc #0
.global md_test_task_return
md_test_task_return:
    mov x19, x8
    mov x8, #__NR_gettid
.global md_test_task_completed
md_test_task_completed:
    svc #0
    b md_test_task_completed
.section .note.GNU-stack,"",%progbits
