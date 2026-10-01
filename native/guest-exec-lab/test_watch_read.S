.text
.balign 4
.global md_watch_read_call
.type md_watch_read_call, %function
md_watch_read_call:
    mov x8, x3
    svc #0
.global md_watch_read_return
md_watch_read_return:
    ret
.size md_watch_read_call, .-md_watch_read_call

.balign 4
.global md_watch_read_gate
md_watch_read_gate:
    svc #0
.global md_watch_read_gate_return
md_watch_read_gate_return:
    ret
.section .note.GNU-stack,"",%progbits
