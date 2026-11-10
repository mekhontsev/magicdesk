#define _GNU_SOURCE
#include "task_retained.h"
#include "task_birth.h"
#include "task_checkpoint.h"
#include "task_registers.h"
#include <elf.h>
#include <errno.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>

enum { BIRTH=1, CLEAR, SESSION, READY };
static int trace(struct md_task_retained *r,int request,pid_t pid,void *address,void *data) {
    return r->parent.trace(request,pid,address,data)<0 ? -errno : 0;
}
static int registers(struct md_task_retained *r,struct user_pt_regs *regs,int writing) {
    return md_task_registers_access(r->parent.trace,r->actor,regs,writing);
}
static int call(struct md_task_retained *r,long number,unsigned long first,unsigned long second) {
    struct user_pt_regs regs=md_task_registers_entry(r->abi.task_call,0,r->pstate);
    regs.regs[0]=first; regs.regs[1]=second; regs.regs[8]=number;
    int error=registers(r,&regs,1);
    return error ? error : trace(r,PTRACE_CONT,r->actor,NULL,NULL);
}
int md_task_retained_begin(struct md_task_retained *r,long (*tracer)(int,pid_t,void *,void *),
        pid_t owner,enum md_task_call_boundary boundary,const struct user_pt_regs *regs,
        const struct md_interception_abi *abi) {
    if (!r || !tracer || owner<=0 || !regs || !abi || !abi->task_call
            || !abi->task_return || !abi->task_completed) return -EINVAL;
    if (r->stage) return -EBUSY;
    *r=(struct md_task_retained){.abi=*abi,.pstate=regs->pstate,.stage=BIRTH};
    struct md_task_call_arguments args={.number=SYS_clone,.value={CLONE_VM,0,0,0,0}};
    return md_task_call_begin(&r->parent,tracer,owner,boundary,regs,abi->task_call,abi->task_return,&args);
}
int md_task_retained_event(struct md_task_retained *r,pid_t pid,int status) {
    if (!r || !r->stage || (pid!=r->actor && pid!=r->parent.actor)) return -EINVAL;
    unsigned event=(unsigned)status>>16;
    if (pid==r->parent.actor && (WIFEXITED(status) || WIFSIGNALED(status)
            || (WIFSTOPPED(status) && (event==PTRACE_EVENT_EXEC || event==PTRACE_EVENT_EXIT)))) {
        r->parent_terminal=1;
        if (WIFSTOPPED(status) && event==PTRACE_EVENT_EXIT && !r->actor) {
            long child=md_task_birth_terminal(r->parent.trace,pid,SYS_clone,CLONE_VM);
            if (child<0) return (int)child;
            r->actor=(pid_t)child;
        }
        return -EAGAIN;
    }
    if (!WIFSTOPPED(status)) return -EAGAIN;
    if (pid==r->parent.actor) {
        if (r->parent_ready) return -EAGAIN;
        if (event==PTRACE_EVENT_CLONE) {
            if (r->actor) return -EPROTO;
            int terminal=0;
            long child=md_task_birth_read(r->parent.trace,pid,event,&terminal);
            if (child<0) return (int)child;
            r->actor=(pid_t)child;
            r->parent_terminal|=terminal;
            if (r->parent_terminal) return -EAGAIN;
            return md_task_call_resume(&r->parent,0);
        }
        if (r->parent_terminal || (!event && WSTOPSIG(status)!=(SIGTRAP|0x80))) return -EAGAIN;
        int ready=md_task_call_event(&r->parent,status);
        if (ready!=1) return ready;
        if (r->parent.result<0) return (int)r->parent.result;
        if (!r->actor || r->parent.result!=r->actor) return -EPROTO;
        r->parent_ready=1;
    } else if (r->stage==BIRTH) {
        if (event!=PTRACE_EVENT_STOP || WSTOPSIG(status)!=SIGTRAP) return -EAGAIN;
        r->stage=CLEAR;
        return call(r,SYS_close_range,0,UINT_MAX);
    } else {
        if (event!=PTRACE_EVENT_SECCOMP && event!=MD_TASK_CHECKPOINT_EVENT) return -EAGAIN;
        struct user_pt_regs regs;
        int error=registers(r,&regs,0);
        if (error) return error;
        if (event==PTRACE_EVENT_SECCOMP) {
            if (regs.pc!=r->abi.task_return || regs.regs[8]!=(unsigned long)(r->stage==CLEAR ? SYS_close_range : SYS_setsid))
                return -EPROTO;
            return trace(r,PTRACE_CONT,pid,NULL,NULL);
        }
        if (regs.pc!=r->abi.task_completed+4) return -EPROTO;
        if ((long)regs.regs[0]<0) return (int)(long)regs.regs[0];
        if (r->stage==CLEAR) {
            if (regs.regs[0]) return -EPROTO;
            r->stage=SESSION; return call(r,SYS_setsid,0,0);
        }
        if (r->stage!=SESSION || regs.regs[0]!=(unsigned)r->actor) return -EPROTO;
        r->stage=READY;
    }
    return r->stage==READY && r->parent_ready && !r->parent_terminal;
}
