.text
.balign 4
.global md_policy_probe
.type md_policy_probe, %function
md_policy_probe:
    mov x8, x0
    svc #0
.global md_policy_probe_return
md_policy_probe_return:
    ret
.size md_policy_probe, .-md_policy_probe
.section .note.GNU-stack,"",%progbits
