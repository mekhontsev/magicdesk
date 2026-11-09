#define _GNU_SOURCE
#include "task_robust.h"
#include "task_checkpoint.h"
#include "task_copy.h"
#include "task_registers.h"
#include <elf.h>
#include <errno.h>
#include <linux/futex.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>

static int trace(struct md_task_robust *t,int request,void *address,void *data) {
    return t->trace(request,t->actor,address,data)<0 ? -errno : 0;
}
static int registers(struct md_task_robust *t,struct user_pt_regs *r,int writing) {
    struct iovec io={r,sizeof(*r)};
    return trace(t,writing ? PTRACE_SETREGSET : PTRACE_GETREGSET,(void *)NT_PRSTATUS,&io);
}
static int execute(struct md_task_robust *t) {
    const struct md_robust_request *q=&t->cleanup.request;
    struct user_pt_regs r=md_task_registers_entry(
        q->operation==MD_ROBUST_CMPXCHG ? t->abi.compare_exchange : t->abi.task_call,0,t->pstate);
    r.regs[0]=q->address;
    r.regs[1]=q->operation==MD_ROBUST_CMPXCHG ? q->expected : FUTEX_WAKE;
    r.regs[2]=q->operation==MD_ROBUST_CMPXCHG ? q->replacement : 1;
    r.regs[8]=q->operation==MD_ROBUST_CMPXCHG ? SYS_gettid : SYS_futex;
    int error=registers(t,&r,1);
    if (!error) error=trace(t,PTRACE_CONT,NULL,NULL);
    if (!error) t->running=1;
    return error;
}
int md_task_robust_progress(struct md_task_robust *t) {
    if (!t || !t->actor) return -EINVAL;
    if (t->running) return 0;
    for (unsigned budget=32;budget;--budget) {
        const struct md_robust_request *q=&t->cleanup.request;
        if (q->operation==MD_ROBUST_DONE) return 1;
        if (q->operation!=MD_ROBUST_READ) return execute(t);
        uint64_t data[3]={0};
        ssize_t count=md_task_copy_direct(t->actor,q->address,data,q->size,0);
        if (count<0 && count!=-EFAULT) return (int)count;
        md_robust_complete(&t->cleanup,count==(ssize_t)q->size ? 0 : -EFAULT,data);
    }
    return 2;
}
int md_task_robust_begin(struct md_task_robust *t,long (*tracer)(int,pid_t,void *,void *),
        pid_t actor,const struct md_interception_abi *abi,uintptr_t head,uint32_t owner) {
    if (!t || !tracer || actor<=0 || !abi || !abi->compare_exchange || !abi->exchanged
            || !abi->task_call || !abi->task_return || !abi->task_completed
            || !owner || (owner&~FUTEX_TID_MASK)) return -EINVAL;
    if (t->running || (t->actor && t->cleanup.request.operation)) return -EBUSY;
    *t=(struct md_task_robust){.trace=tracer,.actor=actor,.abi=*abi};
    struct user_pt_regs r;
    int error=registers(t,&r,0);
    if (error) return error;
    t->pstate=r.pstate;
    md_robust_begin(&t->cleanup,head,owner);
    return md_task_robust_progress(t);
}
int md_task_robust_event(struct md_task_robust *t,int status) {
    if (!t || !t->running) return -EINVAL;
    if (!WIFSTOPPED(status)) return -EAGAIN;
    unsigned event=(unsigned)status>>16;
    int signal=WSTOPSIG(status),fault=0;
    if (event!=MD_TASK_CHECKPOINT_EVENT && event!=PTRACE_EVENT_SECCOMP
            && (event || (signal!=SIGSEGV && signal!=SIGBUS))) return -EAGAIN;
    struct user_pt_regs r;
    int error=registers(t,&r,0);
    if (error) return error;
    enum md_robust_operation operation=t->cleanup.request.operation;
    if (!event) {
        siginfo_t info;
        error=trace(t,PTRACE_GETSIGINFO,NULL,&info);
        if (error) return error;
        if (info.si_code<=0 || operation!=MD_ROBUST_CMPXCHG
                || r.pc<t->abi.compare_exchange || r.pc>=t->abi.exchanged) return -EAGAIN;
        fault=-EFAULT;
    } else if (event==PTRACE_EVENT_SECCOMP) {
        if (operation!=MD_ROBUST_WAKE || r.pc!=t->abi.task_return || r.regs[8]!=SYS_futex)
            return -EPROTO;
        return trace(t,PTRACE_CONT,NULL,NULL);
    } else if (r.pc!=(operation==MD_ROBUST_CMPXCHG ? t->abi.exchanged : t->abi.task_completed)+4)
        return -EPROTO;
    uint32_t observed=(uint32_t)r.regs[0];
    if (operation==MD_ROBUST_WAKE && (long)r.regs[0]<0) fault=(int)(long)r.regs[0];
    md_robust_complete(&t->cleanup,fault,&observed);
    t->running=0;
    return md_task_robust_progress(t);
}
