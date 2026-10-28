#define _GNU_SOURCE
#include "task_checkpoint.h"
#include <asm/ptrace.h>
#include <elf.h>
#include <errno.h>
#include <linux/ptrace.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>

#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif
enum { EMULATING=1, SKIPPED_EXIT, NATIVE_ENTRY, MARKER_INTERRUPT };
struct md_task_checkpoint {
    struct md_task_checkpoint *next;
    pid_t pid;
    int scoped,stage,suspended;
    uintptr_t marker;
    int registers_valid,event_valid,info_valid;
    long info_size;
    unsigned long event;
    struct user_pt_regs registers;
    struct ptrace_syscall_info info;
};
static struct md_task_checkpoint *find(struct md_task_checkpoints *m,pid_t pid,int create) {
    struct md_task_checkpoint **bucket=&m->buckets[(unsigned)pid%MD_TASK_CHECKPOINT_BUCKETS];
    for (struct md_task_checkpoint *c=*bucket;c;c=c->next) if (c->pid==pid) return c;
    if (!create) return NULL;
    struct md_task_checkpoint *c=calloc(1,sizeof(*c));
    if (c) { c->pid=pid; c->next=*bucket; *bucket=c; ++m->count; }
    return c;
}
static int trace(struct md_task_checkpoints *m,int request,pid_t pid,void *address,void *data) {
    return m->trace(request,pid,address,data)<0 ? -errno : 0;
}
int md_task_checkpoint_displaced(struct md_task_checkpoints *m,pid_t pid) {
    siginfo_t info={0};
    int error=trace(m,PTRACE_GETSIGINFO,pid,NULL,&info);
    if (error) return error==-ESRCH ? 1 : error;
    return info.si_signo==SIGTRAP && (info.si_code==(SIGTRAP|(PTRACE_EVENT_EXIT<<8))
        || info.si_code==(SIGTRAP|(PTRACE_EVENT_EXEC<<8)));
}
static int regs(struct md_task_checkpoints *m,pid_t pid,int request,struct user_pt_regs *r) {
    struct iovec io={r,sizeof(*r)};
    return trace(m,request,pid,(void *)NT_PRSTATUS,&io);
}
static void forget(struct md_task_checkpoints *m,pid_t pid) {
    struct md_task_checkpoint **at=&m->buckets[(unsigned)pid%MD_TASK_CHECKPOINT_BUCKETS];
    while (*at && (*at)->pid!=pid) at=&(*at)->next;
    if (*at) { struct md_task_checkpoint *c=*at; *at=c->next; free(c); --m->count; }
}
long md_task_checkpoint_trace(struct md_task_checkpoints *m,int request,pid_t pid,void *address,void *data) {
    struct md_task_checkpoint *c=find(m,pid,0);
    if (c && c->suspended) return m->trace(request,pid,address,data);
    if (c && (c->registers_valid || c->event_valid || c->info_valid)) {
        /* Each observation is handed off once within this stop. Only the
         * other retained observations may intervene; all other requests,
         * including failed mutations, discard the remaining snapshot. */
        if (request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS && data) {
            struct iovec *io=data;
            if (io->iov_base && io->iov_len>=sizeof(c->registers)) {
                if (!c->registers_valid) return m->trace(request,pid,address,data);
                memcpy(io->iov_base,&c->registers,sizeof(c->registers));
                io->iov_len=sizeof(c->registers);
                c->registers_valid=0;
                return 0;
            }
        }
        if (c->event_valid && request==PTRACE_GETEVENTMSG && data) {
            *(unsigned long *)data=c->event; c->event_valid=0;
            return 0;
        }
        if (c->info_valid && request==PTRACE_GET_SYSCALL_INFO && data
                && (uintptr_t)address>=(unsigned long)c->info_size) {
            memcpy(data,&c->info,c->info_size);
            c->info_valid=0;
            return c->info_size;
        }
        c->registers_valid=c->event_valid=c->info_valid=0;
    }
    if (request==PTRACE_SETREGSET && (uintptr_t)address==NT_PRSTATUS && data) {
        struct iovec *io=data;
        if (io->iov_len>=sizeof(struct user_pt_regs)) {
            struct user_pt_regs *r=io->iov_base;
            int scoped=m->begin && r->pc>=m->begin && r->pc<m->end;
            c=find(m,pid,scoped);
            if (scoped && !c) { errno=ENOMEM; return -1; }
            long result=m->trace(request,pid,address,data);
            if (result>=0 && c) { c->scoped=scoped; c->stage=0; }
            return result;
        }
    }
    if (request==PTRACE_CONT) {
        if (c && (c->stage==SKIPPED_EXIT || c->stage==NATIVE_ENTRY)) request=PTRACE_SYSCALL;
        else if (c && c->stage!=MARKER_INTERRUPT && c->scoped && m->restricted(pid)) {
            request=PTRACE_SYSEMU; c->stage=EMULATING;
        }
    } else if (request==PTRACE_SYSCALL || request==PTRACE_SINGLESTEP || request==PTRACE_SYSEMU) {
        if (c) c->stage=0;
    }
    return m->trace(request,pid,address,data);
}
static int marker(struct md_task_checkpoints *m,struct md_task_checkpoint *c,pid_t pid,
        uintptr_t pc,unsigned long command,int emulated,int *status) {
    if (!emulated) {
        /* A TRACE checkpoint is already past filter evaluation. -1 is the
         * documented post-TRACE cancellation, not a new filtered syscall. */
        int number=-1; struct iovec io={&number,sizeof(number)};
        int error=trace(m,PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io);
        if (error) return error;
    }
    if (m->entry_marker && m->entry_marker(pc,command)) {
        c->stage=0;
        *status=(MD_TASK_CHECKPOINT_EVENT<<16)|(SIGTRAP<<8)|0x7f;
        return 0;
    }
    c->marker=pc; c->stage=MARKER_INTERRUPT;
    int error=trace(m,PTRACE_INTERRUPT,pid,NULL,NULL);
    if (!error) error=trace(m,PTRACE_CONT,pid,NULL,NULL);
    return error ? error : 1;
}
static int checkpoint_event(struct md_task_checkpoints *m,pid_t pid,int *status) {
    if (WIFEXITED(*status) || WIFSIGNALED(*status)) { forget(m,pid); return 0; }
    struct md_task_checkpoint *c=find(m,pid,0);
    if (c) c->registers_valid=c->event_valid=c->info_valid=0;
    if (!WIFSTOPPED(*status)) return 0;
    unsigned event=(unsigned)*status>>16;
    int signal=WSTOPSIG(*status);
    if (event==PTRACE_EVENT_EXEC) {
        forget(m,pid);
        c=find(m,pid,1); if (!c) return -ENOMEM;
        c->scoped=1;
        return 0;
    }
    if (c && c->suspended) return 0;
    if (event==PTRACE_EVENT_SECCOMP) {
        if (m->begin && (!c || !c->scoped)) return 0;
        struct ptrace_syscall_info info={0};
        long size=m->trace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info);
        if (size<0) return -errno;
        if (info.op!=PTRACE_SYSCALL_INFO_SECCOMP) return -EPROTO;
        unsigned long cookie=info.seccomp.ret_data;
        if (cookie!=MD_INTERCEPT_CHECKPOINT && (!c || !c->scoped)) return 0;
        if (cookie!=MD_INTERCEPT_CHECKPOINT && !m->marker(info.instruction_pointer)) {
            /* Ordinary syscall owners may need only this entry record.
             * Do not eagerly capture full registers for compact handlers. */
            c->event=cookie; c->event_valid=1;
            /* Preserve the kernel's exact byte count. Future larger records
             * fall back instead of publishing a truncated snapshot. */
            if (size>0 && (unsigned long)size<=sizeof(info)) {
                c->info=info; c->info_size=size; c->info_valid=1;
            }
            return 0;
        }
        c=find(m,pid,1); if (!c) return -ENOMEM;
        return marker(m,c,pid,info.instruction_pointer,info.seccomp.args[0],0,status);
    }
    if (!c || !c->stage) return 0;
    if (c->stage==MARKER_INTERRUPT) {
        if (event!=PTRACE_EVENT_STOP || signal!=SIGTRAP) return 0;
        struct user_pt_regs r;
        int error=regs(m,pid,PTRACE_GETREGSET,&r); if (error) return error;
        if (r.pc!=c->marker) return -EPROTO;
        c->stage=0;
        c->registers=r; c->registers_valid=1;
        *status=(MD_TASK_CHECKPOINT_EVENT<<16)|(SIGTRAP<<8)|0x7f;
        return 0;
    }
    if (event || signal!=(SIGTRAP|0x80)) return 0;
    struct ptrace_syscall_info info={0};
    int error=trace(m,PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info);
    if (error) return error;
    if (c->stage==SKIPPED_EXIT) {
        if (info.op!=PTRACE_SYSCALL_INFO_EXIT) return -EPROTO;
        c->stage=NATIVE_ENTRY;
        error=trace(m,PTRACE_SYSCALL,pid,NULL,NULL);
    } else if (c->stage==NATIVE_ENTRY) {
        if (info.op!=PTRACE_SYSCALL_INFO_ENTRY) return -EPROTO;
        c->stage=EMULATING;
        error=trace(m,PTRACE_SYSEMU,pid,NULL,NULL);
    } else {
        if (info.op!=PTRACE_SYSCALL_INFO_ENTRY) return -EPROTO;
        /* Exact registered sites distinguish markers from native calls, even
         * when x8 is a register-copy payload rather than a syscall number. */
        if (m->marker(info.instruction_pointer))
            return marker(m,c,pid,info.instruction_pointer,info.entry.args[0],1,status);
        struct user_pt_regs r;
        error=regs(m,pid,PTRACE_GETREGSET,&r); if (error) return error;
        r.pc-=4;
        error=regs(m,pid,PTRACE_SETREGSET,&r); if (error) return error;
        c->stage=SKIPPED_EXIT;
        error=trace(m,PTRACE_SYSCALL,pid,NULL,NULL);
    }
    return error ? error : 1;
}
int md_task_checkpoint_event(struct md_task_checkpoints *m,pid_t pid,int *status) {
    int result=checkpoint_event(m,pid,status);
    if (result==-EPROTO) {
        int displaced=md_task_checkpoint_displaced(m,pid);
        if (displaced) return displaced>0 ? -ESRCH : displaced;
    }
    return result;
}
int md_task_checkpoint_suspend(struct md_task_checkpoints *m,pid_t pid) {
    struct md_task_checkpoint *c=find(m,pid,1);
    if (!c) return -ENOMEM;
    if (c->suspended) return -EBUSY;
    c->registers_valid=c->event_valid=c->info_valid=0;
    c->suspended=1;
    return c->stage==EMULATING;
}
void md_task_checkpoint_restore(struct md_task_checkpoints *m,pid_t pid) {
    struct md_task_checkpoint *c=find(m,pid,0);
    if (c) c->suspended=0;
}
void md_task_checkpoints_clear(struct md_task_checkpoints *m) {
    for (unsigned i=0;i<MD_TASK_CHECKPOINT_BUCKETS;++i)
        while (m->buckets[i]) forget(m,m->buckets[i]->pid);
}
