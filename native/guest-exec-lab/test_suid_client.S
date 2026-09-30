.text
.balign 4
.global md_suid_client_ready
.type md_suid_client_ready, %function
md_suid_client_ready:
    brk #0x5a3
    ret
.size md_suid_client_ready, .-md_suid_client_ready
.section .note.GNU-stack,"",%progbits
