#ifndef MD_TASK_REGISTERS_H
#define MD_TASK_REGISTERS_H
#include <asm/ptrace.h>
#include <elf.h>
#include <errno.h>
#include <stdint.h>
#include <sys/ptrace.h>
#include <sys/uio.h>

/* Private task owners do not have a guest thread record. Keep raw register
 * access separate from the supervisor's guest lifecycle/ESRCH handling. */
static inline int md_task_registers_access(long (*trace)(int,pid_t,void *,void *),
        pid_t pid,struct user_pt_regs *regs,int writing) {
    struct iovec io={regs,sizeof(*regs)};
    if (trace(writing ? PTRACE_SETREGSET : PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)<0) return -errno;
    return io.iov_len==sizeof(*regs) ? 0 : -EIO;
}

/* A fresh continuation discards operands/NZCV, not the processor controls
 * selected by exec or the application (including SSBS). */
static inline struct user_pt_regs md_task_registers_entry(uint64_t pc,uint64_t sp,uint64_t pstate) {
    return (struct user_pt_regs){.pc=pc,.sp=sp,.pstate=pstate&~UINT64_C(0xf0000000)};
}
#endif
