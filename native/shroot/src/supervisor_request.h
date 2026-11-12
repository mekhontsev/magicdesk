#ifndef MD_SUPERVISOR_REQUEST_H
#define MD_SUPERVISOR_REQUEST_H

#include <asm/ptrace.h>
#include <linux/audit.h>
#include <linux/seccomp.h>
#include <linux/ptrace.h>

/* Logical syscall input, independent of the transport that admitted it. */
static inline struct seccomp_data syscall_description(const struct user_pt_regs *regs) {
    struct seccomp_data call={.nr=regs->regs[8],.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=regs->pc};
    for (unsigned i=0;i<6;++i) call.args[i]=regs->regs[i];
    return call;
}

/* A seccomp stop already supplies every syscall operand, without a GPR read. */
static inline struct seccomp_data syscall_observation(const struct ptrace_syscall_info *info) {
    struct seccomp_data call={.nr=info->seccomp.nr,.arch=info->arch,
        .instruction_pointer=info->instruction_pointer};
    for (unsigned i=0;i<6;++i) call.args[i]=info->seccomp.args[i];
    return call;
}

struct supervisor_control_request {
    __u64 command;
    __u64 args[3];
};

static inline struct supervisor_control_request control_description(const struct user_pt_regs *regs) {
    return (struct supervisor_control_request){.command=regs->regs[0],
        .args={regs->regs[1],regs->regs[2],regs->regs[3]}};
}

#endif
