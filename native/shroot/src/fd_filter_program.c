#define _GNU_SOURCE
#include "fd_filter_program.h"
#include "interception.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>

/* Runtime gates normally share one code mapping. Group their high words so
 * every persistent selector does not spend five instructions per gate. */
static void exemptions(const struct md_fd_filter_gates *gates,struct md_fd_filter_program *out) {
    const uintptr_t addresses[]={gates->raw,gates->watch,gates->task,gates->native};
    unsigned seen=0;
    for (unsigned i=0;i<sizeof(addresses)/sizeof(*addresses);++i) {
        if (!addresses[i] || (seen&(1u<<i))) continue;
        unsigned low[4],count=0,high=(unsigned)(addresses[i]>>32);
        for (unsigned j=i;j<sizeof(addresses)/sizeof(*addresses);++j)
            if (addresses[j] && (unsigned)(addresses[j]>>32)==high) {
                seen|=1u<<j;
                unsigned k=0;
                while (k<count && low[k]!=(unsigned)addresses[j]) ++k;
                if (k==count) low[count++]=(unsigned)addresses[j];
            }
        out->code[out->count++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,
            offsetof(struct seccomp_data,instruction_pointer)+4);
        out->code[out->count++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,high,0,1+2*count);
        out->code[out->count++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,
            offsetof(struct seccomp_data,instruction_pointer));
        for (unsigned k=0;k<count;++k) {
            out->code[out->count++]=(struct sock_filter)BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,low[k],0,1);
            out->code[out->count++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        }
    }
}
static int finish(const struct md_fd_filter_gates *gates,struct md_fd_filter_program *out) {
    unsigned count=out->count,actions[3],targets[3],used=0;
    /* Resolve policy first. Unrelated syscall numbers return ALLOW without an
     * IP/argument load, preserving the kernel's seccomp allow cache. Only the
     * selected TRACE decisions need the exact internal-gate exemptions. */
    for (unsigned i=0;i<count;++i) {
        struct sock_filter *insn=&out->code[i];
        if (insn->code!=(BPF_RET|BPF_K) || (insn->k&SECCOMP_RET_ACTION_FULL)!=SECCOMP_RET_TRACE) continue;
        unsigned action=insn->k,j=0;
        while (j<used && actions[j]!=action) ++j;
        if (j==used) {
            if (used==3 || out->count+21>MD_FD_FILTER_INSTRUCTIONS) return -E2BIG;
            actions[used]=action; targets[used++]=out->count;
            exemptions(gates,out);
            out->code[out->count++]=(struct sock_filter)BPF_STMT(BPF_RET|BPF_K,action);
        }
        *insn=(struct sock_filter)BPF_STMT(BPF_JMP|BPF_JA,targets[j]-i-1);
    }
    return 0;
}
int md_fd_filter_build(enum md_fd_selector_kind kind,int fd,
        const struct md_fd_filter_gates *gates,struct md_fd_filter_program *out) {
    if (!out) return -EINVAL;
    out->count=0;
    if (!gates || fd< -1 || kind<0 || kind>=MD_FD_SELECTOR_KINDS) return -EINVAL;
    unsigned mask=fd==-1 ? 0 : MD_FD_SELECTOR_MASK;
    out->code[out->count++]=(struct sock_filter)BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr));
#define ADAPT(n) BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_##n,0,1), \
    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_DISPATCH),
#define DUPLICATES \
    ADAPT(dup) ADAPT(dup3) \
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fcntl,0,4), \
    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])), \
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_DUPFD,1,0), \
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_DUPFD_CLOEXEC,0,1), \
    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_DISPATCH), \
    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
#define CONTROL(n,argument,cookie) \
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_##n,0,5), \
    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[argument])), \
    BPF_STMT(BPF_ALU|BPF_AND|BPF_K,mask), \
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)fd&mask,0,1), \
    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|(cookie)), \
    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
#define PROC(n,argument) CONTROL(n,argument,MD_INTERCEPT_PROC)
#define COPY_PROGRAM(...) do { \
    struct sock_filter code[]={__VA_ARGS__}; \
    _Static_assert(1+3*21+sizeof(code)/sizeof(*code)<=MD_FD_FILTER_INSTRUCTIONS,"FD filter capacity"); \
    for (unsigned i=0;i<sizeof(code)/sizeof(*code);++i) out->code[out->count++]=code[i]; \
    return finish(gates,out); \
} while (0)
    if (kind==MD_FD_SELECTOR_WATCH) COPY_PROGRAM(
        /* Other ioctls have no watch semantics; provider filters own theirs. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ioctl,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,FIONREAD,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_read,5,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_readv,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ioctl,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_splice,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_tee,1,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendfile,2,7),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_STMT(BPF_JMP|BPF_JA,1),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,mask),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)fd&mask,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_WATCH),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        DUPLICATES
    );
    if (kind==MD_FD_SELECTOR_PROC) COPY_PROGRAM(
        /* Controlled shared mappings may have a writable provider backing.
         * Only tasks admitting controlled descriptors acquire this check. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mprotect,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,PROT_WRITE,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_MEMORY),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        PROC(read,0) PROC(write,0) PROC(readv,0) PROC(writev,0)
        PROC(pread64,0) PROC(pwrite64,0)
        PROC(preadv,0) PROC(pwritev,0) PROC(preadv2,0) PROC(pwritev2,0)
        PROC(lseek,0) PROC(ioctl,0)
        /* Anonymous mmap ignores the FD, including adapter stack allocation. */
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
        BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,MAP_ANONYMOUS,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        PROC(mmap,4) PROC(getdents64,0) PROC(fstatfs,0)
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fcntl,0,10),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_GETFL,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_SETFL,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_GET_SEALS,1,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,F_ADD_SEALS,0,5),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_STMT(BPF_ALU|BPF_AND|BPF_K,mask),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)fd&mask,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_PROC),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        PROC(ftruncate,0) PROC(fsync,0) PROC(fdatasync,0)
        PROC(sendfile,0) PROC(sendfile,1) PROC(splice,0) PROC(splice,2)
        PROC(tee,0) PROC(tee,1) PROC(copy_file_range,0) PROC(copy_file_range,2)
        DUPLICATES
    );
    if (kind==MD_FD_SELECTOR_EPOLL_TARGET) COPY_PROGRAM(
        CONTROL(epoll_ctl,2,MD_INTERCEPT_READINESS)
        DUPLICATES
    );
    if (kind==MD_FD_SELECTOR_EPOLL) COPY_PROGRAM(
        CONTROL(epoll_ctl,0,MD_INTERCEPT_READINESS)
        CONTROL(epoll_pwait,0,MD_INTERCEPT_READINESS)
        CONTROL(epoll_pwait2,0,MD_INTERCEPT_READINESS)
        DUPLICATES
    );
    if (kind==MD_FD_SELECTOR_POLL) COPY_PROGRAM(
        ADAPT(ppoll) ADAPT(pselect6)
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    );
    return -EINVAL;
#undef COPY_PROGRAM
#undef PROC
#undef CONTROL
#undef DUPLICATES
#undef ADAPT
}
