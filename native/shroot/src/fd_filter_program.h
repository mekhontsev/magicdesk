#ifndef MD_FD_FILTER_PROGRAM_H
#define MD_FD_FILTER_PROGRAM_H
#include "fd_selector.h"
#include "interception.h"
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/syscall.h>

/* Enter with the syscall number in A; leave it unchanged for other calls.
 * Linux close consumes the low unsigned-int argument, not its upper 32 bits. */
#define MD_FD_CLOSE_FILTER \
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_close,0,5), \
    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])), \
    BPF_JUMP(BPF_JMP|BPF_JGT|BPF_K,MD_INTERCEPT_CLOSE_MAX,2,0), \
    BPF_STMT(BPF_ALU|BPF_OR|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_CLOSE), \
    BPF_STMT(BPF_RET|BPF_A,0), \
    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_FD)

struct md_fd_filter_gates { uintptr_t raw,watch,task,native,adapter; };
static inline struct md_fd_filter_gates md_fd_filter_runtime_gates(const struct md_interception_abi *abi) {
    return (struct md_fd_filter_gates){.raw=abi->raw_gate,.watch=abi->watch_return,
        .task=abi->task_return,.native=abi->native_return,.adapter=abi->adapter_native_return};
}
enum { MD_FD_FILTER_GATES=5, MD_FD_FILTER_EXEMPTIONS=5*MD_FD_FILTER_GATES+1,
    MD_FD_FILTER_INSTRUCTIONS=291 };
struct md_fd_filter_program {
    struct sock_filter code[MD_FD_FILTER_INSTRUCTIONS];
    unsigned short count;
};
struct md_task_filter_image {
    struct md_fd_filter_program code;
    struct sock_fprog program;
};
/* Pure construction, shared by task-local and supervisor-directed admission.
 * No installation, allocation or pointers into another address space. */
int md_fd_filter_build(enum md_fd_selector_kind,int fd,
    const struct md_fd_filter_gates *,struct md_fd_filter_program *);
#endif
