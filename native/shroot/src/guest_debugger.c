#define _GNU_SOURCE
#include "guest_debugger.h"
#include "pid_calls.h"
#include <elf.h>
#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include <linux/audit.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>
#ifndef P_PIDFD
#define P_PIDFD 3
#endif

struct inferior {
    struct inferior *next;
    /* The debugger and real parent consume separate exit events. Its admitted
     * visible number remains valid after the parent has reaped native state. */
    pid_t pid, owner, group, number, exec_former;
    uid_t uid;
    unsigned long options, message;
    uint64_t sequence;
    int stopped, status, step, terminal, real_child;
    int seized, pending, syscall_mode, syscall_stop;
    struct ptrace_syscall_info syscall;
    struct rusage usage;
    siginfo_t info, deferred_stop;
    struct md_signal_origin origin;
};
struct waiting {
    struct waiting *next;
    pid_t pid;
    struct seccomp_data call;
    pid_t *candidates;
    size_t candidate,count;
    int probing, retry, filtered, native_children;
};
struct md_debugger {
    struct md_debugger_host host;
    struct inferior *inferiors;
    struct waiting *waiters;
    uint64_t sequence;
};
static void wait_free(struct waiting *w) { free(w->candidates); free(w); }
static struct inferior *find(struct md_debugger *d, pid_t pid) {
    for (struct inferior *p=d->inferiors; p; p=p->next) if (p->pid==pid) return p;
    return NULL;
}
static int transfer(pid_t pid, uintptr_t address, void *bytes, size_t size, int write) {
    if (!size) return 0;
    if (!address || address>UINTPTR_MAX-size) return -EFAULT;
    struct iovec local={bytes,size}, remote={(void *)address,size};
    ssize_t n=write ? process_vm_writev(pid,&local,1,&remote,1,0) : process_vm_readv(pid,&local,1,&remote,1,0);
    return n==(ssize_t)size ? 0 : -EFAULT;
}
static long request(struct md_debugger *d,int op, pid_t pid, uintptr_t address, void *data) {
    errno=0;
    if (!d->host.trace) return -ENOSYS;
    long r=d->host.trace(op,pid,(void *)address,data);
    return r==-1 && errno ? -errno : r;
}
struct md_debugger *md_debugger_create(const struct md_debugger_host *host) {
    struct md_debugger *d=calloc(1,sizeof(*d));
    if (d) d->host=*host;
    return d;
}
void md_debugger_destroy(struct md_debugger *d) {
    if (!d) return;
    while (d->inferiors) { struct inferior *p=d->inferiors; d->inferiors=p->next; free(p); }
    while (d->waiters) { struct waiting *p=d->waiters; d->waiters=p->next; wait_free(p); }
    free(d);
}
static int add(struct md_debugger *d, pid_t pid, pid_t owner, unsigned long options) {
    if (find(d,pid)) return -EPERM;
    struct inferior *p=calloc(1,sizeof(*p));
    if (!p) return -ENOMEM;
    *p=(struct inferior){.next=d->inferiors,.pid=pid,.owner=owner,.options=options,
        .number=d->host.visible_pid ? d->host.visible_pid(owner,pid) : pid,
        /* Nonleader threads have no native parent wait result. Their terminal
         * event belongs to this tracing relationship, even when the debugger
         * is also the thread group's real parent. */
        .real_child=d->host.group(pid)==pid
            && d->host.group(d->host.parent(pid))==d->host.group(owner),.group=getpgid(pid)};
    d->inferiors=p;
    int error=d->host.observe(owner);
    if (error) { d->inferiors=p->next; free(p); }
    return error;
}
static pid_t visible(struct md_debugger *d,pid_t observer,pid_t native) {
    return d->host.visible_pid ? d->host.visible_pid(observer,native) : native;
}
static pid_t native(struct md_debugger *d,pid_t observer,pid_t number) {
    return d->host.native_pid ? d->host.native_pid(observer,number) : number;
}
static uid_t visible_uid(struct md_debugger *d,pid_t observer,pid_t sender,uid_t fallback) {
    return d->host.visible_uid ? d->host.visible_uid(observer,sender,fallback) : fallback;
}
static int selected(struct md_debugger *d,pid_t owner, const struct seccomp_data *call, const struct inferior *p) {
    pid_t pid=p->number, group=visible(d,owner,p->group);
    if (!pid) return 0;
    if (call->nr==SYS_waitid) {
        if (call->args[0]==P_ALL) return 1;
        if (call->args[0]==P_PID) return call->args[1]==(unsigned)pid;
        return call->args[0]==P_PGID && (call->args[1] ? group==(pid_t)call->args[1] : p->group==getpgid(owner));
    }
    pid_t wanted=(pid_t)call->args[0];
    return wanted==-1 || wanted==pid || (!wanted && p->group==getpgid(owner))
        || (wanted < -1 && (int64_t)group==-(int64_t)wanted);
}
static unsigned wait_options(const struct seccomp_data *call) {
    return (unsigned)call->args[call->nr==SYS_waitid ? 3 : 2];
}
static int wait_result(struct md_debugger *d, pid_t owner,const struct seccomp_data *call,int *children) {
    struct inferior *chosen=NULL;
    *children=0;
    for (struct inferior *p=d->inferiors; p; p=p->next) {
        if (d->host.group(p->owner)!=d->host.group(owner) || !selected(d,owner,call,p)) continue;
        *children=1;
        if (call->nr==SYS_waitid && !(wait_options(call)&(p->terminal ? WEXITED : WSTOPPED))) continue;
        if (p->sequence && (!chosen || p->sequence<chosen->sequence)) chosen=p;
    }
    if (!chosen) return 0;
    long value=chosen->number;
    int error;
    uintptr_t usage;
    if (call->nr==SYS_waitid) {
        siginfo_t info={.si_signo=SIGCHLD,.si_pid=(pid_t)value,
            .si_uid=visible_uid(d,owner,chosen->pid,chosen->uid)};
        info.si_code=chosen->terminal ? (WIFEXITED(chosen->status) ? CLD_EXITED
            : WCOREDUMP(chosen->status) ? CLD_DUMPED : CLD_KILLED) : CLD_TRAPPED;
        info.si_status=chosen->terminal ? (WIFEXITED(chosen->status) ? WEXITSTATUS(chosen->status)
            : WTERMSIG(chosen->status)) : (chosen->status>>8);
        error=call->args[2] ? transfer(owner,call->args[2],&info,sizeof(info),1) : 0;
        usage=call->args[4]; value=0;
    } else {
        error=call->args[1] ? transfer(owner,call->args[1],&chosen->status,sizeof(int),1) : 0;
        usage=call->args[3];
    }
    if (!error && usage) error=transfer(owner,usage,&chosen->usage,sizeof(chosen->usage),1);
    if (!error && !(call->nr==SYS_waitid && (wait_options(call)&WNOWAIT))) {
        chosen->sequence=0;
        if (chosen->terminal) {
            struct inferior **at=&d->inferiors;
            while (*at!=chosen) at=&(*at)->next;
            *at=chosen->next;
            if (d->host.reaped) d->host.reaped(owner,chosen->pid,0);
            free(chosen);
        }
    }
    d->host.complete(owner,error ? error : value);
    return 1;
}
static int wake_waiters(struct md_debugger *d,pid_t owner) {
    int waiting=0;
    for (struct waiting *w=d->waiters,*next; w; w=next) {
        next=w->next;
        if (d->host.group(w->pid)==d->host.group(owner)) {
            waiting=1;
            if (w->probing) w->retry=1;
            else d->host.wait_wake(w->pid);
        }
    }
    return waiting;
}
static void wake(struct md_debugger *d, pid_t owner) {
    /* The debugger may use a SIGCHLD self-pipe instead of a blocking wait. */
    if (!wake_waiters(d,owner)) kill(owner,SIGCHLD);
}
void md_debugger_child_changed(struct md_debugger *d,pid_t pid) {
    if (!d || !d->waiters) return;
    pid_t parent=d->host.parent(pid);
    if (parent>0) wake_waiters(d,parent);
}
int md_debugger_stop(struct md_debugger *d, pid_t pid, int status, const siginfo_t *info, unsigned long message) {
    struct inferior *p=find(d,pid);
    if (!p) return 0;
    p->stopped=1; p->status=status; p->message=message; p->step=0;
    p->uid=d->host.uid(pid);
    d->host.usage(pid,&p->usage);
    p->syscall.op=PTRACE_SYSCALL_INFO_NONE;
    p->syscall_stop=0;
    p->info=info ? *info : (siginfo_t){.si_signo=WSTOPSIG(status),
        .si_code=(unsigned)status>>16 ? status>>8 : SI_KERNEL,.si_pid=pid};
    struct md_signal_origin origin={0};
    p->origin.valid=0;
    if (d->host.signal_origin) d->host.signal_origin(pid,&p->info,&origin);
    p->origin=origin;
    p->sequence=++d->sequence;
    wake(d,p->owner);
    return 1;
}
int md_debugger_exec(struct md_debugger *d, pid_t pid, pid_t former) {
    struct inferior *p=find(d,pid);
    if (!p) return 0;
    unsigned event=p->options & PTRACE_O_TRACEEXEC ? PTRACE_EVENT_EXEC : 0;
    if (!event && p->seized) return 0;
    pid_t message=p->exec_former ? p->exec_former : visible(d,p->owner,former);
    p->exec_former=0;
    return md_debugger_stop(d,pid,(SIGTRAP<<8)|0x7f|(event<<16),NULL,(unsigned long)message);
}
int md_debugger_event(struct md_debugger *d, pid_t pid, unsigned event, unsigned long message) {
    struct inferior *p=find(d,pid);
    if (!p || !(p->options&(1UL<<event))) return 0;
    return md_debugger_stop(d,pid,(SIGTRAP<<8)|0x7f|(event<<16),NULL,message);
}
int md_debugger_seccomp(struct md_debugger *d, pid_t pid, const struct user_pt_regs *r, unsigned data) {
    if (!d->host.seccomp_events || !md_debugger_event(d,pid,PTRACE_EVENT_SECCOMP,data)) return 0;
    struct inferior *p=find(d,pid);
    p->syscall=(struct ptrace_syscall_info){.op=PTRACE_SYSCALL_INFO_SECCOMP,
        .arch=AUDIT_ARCH_AARCH64,.instruction_pointer=r->pc,.stack_pointer=r->sp};
    p->syscall.seccomp.nr=r->regs[8];
    p->syscall.seccomp.ret_data=data;
    memcpy(p->syscall.seccomp.args,r->regs,sizeof(p->syscall.seccomp.args));
    return 1;
}
int md_debugger_birth(struct md_debugger *d, pid_t parent, pid_t child, unsigned event) {
    struct inferior *p=find(d,parent);
    if (!p || !(p->options & (1UL<<event))) return 0;
    int error=add(d,child,p->owner,p->options);
    if (error) return error;
    find(d,child)->seized=p->seized;
    return md_debugger_stop(d,parent,(SIGTRAP<<8)|0x7f|(event<<16),NULL,(unsigned long)child);
}
int md_debugger_step(struct md_debugger *d, pid_t pid) {
    struct inferior *p=find(d,pid);
    return p && p->step;
}
int md_debugger_traced(struct md_debugger *d, pid_t pid) {
    struct inferior *p=d ? find(d,pid) : NULL;
    return p && !p->terminal;
}
pid_t md_debugger_owner(struct md_debugger *d,pid_t pid) {
    struct inferior *p=find(d,pid);
    return p && !p->terminal ? p->owner : 0;
}
int md_debugger_stopped_by(struct md_debugger *d,pid_t target,pid_t owner) {
    struct inferior *p=d ? find(d,target) : NULL;
    return p && p->owner==owner && p->stopped && !p->terminal && !p->pending;
}
int md_debugger_running(struct md_debugger *d,pid_t target) {
    struct inferior *p=d ? find(d,target) : NULL;
    return p && !p->terminal && !p->stopped && !p->pending;
}
int md_debugger_stop_pending(struct md_debugger *d,pid_t pid) {
    struct inferior *p=d ? find(d,pid) : NULL;
    return p && !p->terminal && !p->stopped && (p->pending || p->deferred_stop.si_signo);
}
int md_debugger_active(struct md_debugger *d) { return d->inferiors!=NULL || d->waiters!=NULL; }
int md_debugger_newborn(struct md_debugger *d, pid_t pid) {
    struct inferior *p=find(d,pid);
    return p && md_debugger_stop(d,pid,p->seized ? (SIGTRAP<<8)|0x7f|(PTRACE_EVENT_STOP<<16)
        : (SIGSTOP<<8)|0x7f,NULL,0);
}
int md_debugger_defer_stop(struct md_debugger *d, pid_t pid, const siginfo_t *info) {
    struct inferior *p=find(d,pid);
    if (!p || p->terminal || info->si_signo!=SIGSTOP) return 0;
    /* SIGSTOP cannot be masked during an adapter operation. Retain its first
     * delivery like a pending standard signal, without stopping resource owners. */
    if (!p->deferred_stop.si_signo) p->deferred_stop=*info;
    return 1;
}
int md_debugger_boundary(struct md_debugger *d, pid_t pid) {
    struct inferior *p=find(d,pid);
    if (!p || p->stopped) return 0;
    if (p->pending) {
        p->pending=0;
        return md_debugger_newborn(d,pid);
    }
    if (!p->deferred_stop.si_signo) return 0;
    siginfo_t info=p->deferred_stop;
    p->deferred_stop=(siginfo_t){0};
    return md_debugger_stop(d,pid,(SIGSTOP<<8)|0x7f,&info,0);
}
int md_debugger_syscall_mode(struct md_debugger *d, pid_t pid) {
    struct inferior *p=find(d,pid);
    return p && p->syscall_mode;
}
int md_debugger_syscall(struct md_debugger *d, pid_t pid, const struct user_pt_regs *r, int entry) {
    struct inferior *p=find(d,pid);
    if (!p || !p->syscall_mode) return 0;
    int stopped=md_debugger_stop(d,pid,((SIGTRAP|(p->options&PTRACE_O_TRACESYSGOOD ? 0x80 : 0))<<8)|0x7f,NULL,0);
    p->syscall_stop=entry ? 1 : 2;
    p->syscall=(struct ptrace_syscall_info){.op=!(p->options&PTRACE_O_TRACESYSGOOD) ? PTRACE_SYSCALL_INFO_NONE
        : entry ? PTRACE_SYSCALL_INFO_ENTRY : PTRACE_SYSCALL_INFO_EXIT,
        .arch=AUDIT_ARCH_AARCH64,.instruction_pointer=r->pc,.stack_pointer=r->sp};
    if (entry) {
        p->syscall.entry.nr=r->regs[8];
        for (unsigned i=0;i<6;i++) p->syscall.entry.args[i]=r->regs[i];
    } else {
        p->syscall.exit.rval=(int64_t)r->regs[0];
        p->syscall.exit.is_error=(uint64_t)r->regs[0]>=(uint64_t)-4095;
    }
    return stopped;
}
void md_debugger_exit(struct md_debugger *d, pid_t pid, int status) {
    if (!md_debugger_active(d)) return;
    struct inferior *ending=find(d,pid);
    pid_t parent=d->host.parent(pid);
    pid_t notify=0;
    if (ending && !ending->real_child) {
        ending->terminal=1; ending->status=status; ending->stopped=0;
        ending->uid=d->host.uid(pid);
        d->host.usage(pid,&ending->usage);
        ending->sequence=++d->sequence; notify=ending->owner;
    }
    struct inferior **at=&d->inferiors;
    pid_t owner=0;
    while (*at) {
        struct inferior *p=*at;
        if ((p->pid==pid && !p->terminal) || p->owner==pid) {
            *at=p->next;
            if (p->pid==pid) owner=p->owner;
            else if (!p->terminal && (p->options & PTRACE_O_EXITKILL)) kill(p->pid,SIGKILL);
            else {
                d->host.options(p->pid,0);
                if (p->stopped) d->host.resume(p->pid,0,&p->origin);
            }
            if (p->terminal && d->host.reaped) d->host.reaped(pid,p->pid,0);
            free(p);
        } else at=&p->next;
    }
    struct waiting **waiting=&d->waiters;
    while (*waiting) {
        struct waiting *w=*waiting;
        if (w->pid==pid) {
            *waiting=w->next;
            wait_free(w);
        } else waiting=&w->next;
    }
    if (notify) wake(d,notify);
    if (owner) wake(d,owner);
    if (parent && parent!=owner && parent!=notify) wake(d,parent);
}
void md_debugger_rekey(struct md_debugger *d, pid_t old, pid_t pid) {
    struct inferior *source=find(d,old);
    struct inferior **at=&d->inferiors;
    while (*at) {
        struct inferior *p=*at;
        if (p->pid==pid && source && p!=source) { *at=p->next; free(p); continue; }
        if (p->pid==old) {
            p->exec_former=visible(d,p->owner,old);
            p->number=visible(d,p->owner,pid);
            p->pid=pid; p->real_child=d->host.group(d->host.parent(pid))==d->host.group(p->owner);
        }
        if (p->owner==old) p->owner=pid;
        at=&p->next;
    }
    struct waiting **w=&d->waiters;
    while (*w) {
        if ((*w)->pid==old) { struct waiting *gone=*w; *w=gone->next; wait_free(gone); }
        else w=&(*w)->next;
    }
}
static unsigned long supported_options(const struct md_debugger *d) {
    return PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACEFORK|PTRACE_O_TRACEVFORK|PTRACE_O_TRACECLONE
        |PTRACE_O_TRACEEXEC|PTRACE_O_TRACEVFORKDONE|PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL
        |(d->host.seccomp_events ? PTRACE_O_TRACESECCOMP : 0);
}
static long regset(struct md_debugger *d,pid_t caller, struct inferior *p, int op, uintptr_t note, uintptr_t address) {
    struct iovec guest;
    int r=transfer(caller,address,&guest,sizeof(guest),0);
    if (r) return r;
    if (guest.iov_len>65536) return -E2BIG;
    void *bytes=calloc(1,guest.iov_len ? guest.iov_len : 1);
    if (!bytes) return -ENOMEM;
    struct iovec local={bytes,guest.iov_len};
    if (op==PTRACE_SETREGSET) r=transfer(caller,(uintptr_t)guest.iov_base,bytes,guest.iov_len,0);
    size_t marker=offsetof(struct user_pt_regs,regs[7]);
    int syscall_registers=note==NT_PRSTATUS && p->syscall_stop && local.iov_len>=marker+sizeof(uint64_t);
    /* ARM64 reserves x7 at syscall stops. It is a direction marker, not a
     * writable application register, even when our physical stop is a trap. */
    if (!r && syscall_registers && op==PTRACE_SETREGSET) {
        struct user_pt_regs current;
        struct iovec saved={&current,sizeof(current)};
        r=(int)request(d,PTRACE_GETREGSET,p->pid,NT_PRSTATUS,&saved);
        if (!r) memcpy((char *)bytes+marker,&current.regs[7],sizeof(uint64_t));
    }
    if (!r) r=(int)request(d,op,p->pid,note,&local);
    if (!r && syscall_registers && op==PTRACE_GETREGSET) {
        uint64_t direction=p->syscall_stop-1;
        memcpy((char *)bytes+marker,&direction,sizeof(direction));
    }
    if (!r && op==PTRACE_GETREGSET) r=transfer(caller,(uintptr_t)guest.iov_base,bytes,local.iov_len,1);
    guest.iov_len=local.iov_len;
    if (!r) r=transfer(caller,address,&guest,sizeof(guest),1);
    free(bytes); return r;
}
static long control(struct md_debugger *d, pid_t caller, const struct seccomp_data *call) {
    int op=(int)call->args[0]; pid_t pid=native(d,caller,(pid_t)call->args[1]);
    uintptr_t address=call->args[2], data=call->args[3];
    if (op==PTRACE_TRACEME) {
        pid_t parent=d->host.parent(caller);
        return parent>0 && d->host.traceme(parent,caller) ? add(d,caller,parent,0) : -EPERM;
    }
    if (op==PTRACE_ATTACH || op==PTRACE_SEIZE) {
        if (op==PTRACE_SEIZE && (address || (data&~supported_options(d)))) return -EIO;
        if (!d->host.attachable(caller,pid)) return -EPERM;
        int error=add(d,pid,caller,op==PTRACE_SEIZE ? data : 0);
        if (error) return error;
        struct inferior *target=find(d,pid);
        target->seized=op==PTRACE_SEIZE;
        error=d->host.options(pid,target->options);
        if (!error && op==PTRACE_ATTACH) {
            target->pending=1; error=d->host.interrupt(pid);
        }
        if (error) { d->inferiors=target->next; free(target); }
        return error;
    }
    struct inferior *p=find(d,pid);
    /* Admission establishes the relationship. A later UID/GID or domain
     * change does not revoke continuation or detach; memory access is separate. */
    if (!p || p->terminal || p->owner!=caller) return -ESRCH;
    if (op==PTRACE_INTERRUPT) {
        if (!p->seized) return -EIO;
        if (p->stopped) return 0;
        p->pending=1; return d->host.interrupt(pid);
    }
    if (op==PTRACE_KILL) return kill(pid,SIGKILL) ? -errno : 0;
    if (!p->stopped) return -ESRCH;
    switch (op) {
    case PTRACE_LISTEN: {
        if (!p->seized || (unsigned)p->status>>16!=PTRACE_EVENT_STOP) return -EIO;
        int error=d->host.listen(pid);
        if (!error) { p->stopped=0; p->sequence=0; }
        return error;
    }
    case PTRACE_SETOPTIONS: {
        if (data & ~supported_options(d)) return -EINVAL;
        int error=d->host.options(pid,data);
        if (!error) p->options=data;
        return error;
    }
    case PTRACE_GETEVENTMSG: {
        unsigned event=(unsigned)p->status>>16;
        unsigned long message=p->message;
        if (event==PTRACE_EVENT_FORK || event==PTRACE_EVENT_VFORK || event==PTRACE_EVENT_CLONE
                || event==PTRACE_EVENT_VFORK_DONE)
            message=(unsigned long)visible(d,caller,(pid_t)message);
        return transfer(caller,data,&message,sizeof(message),1);
    }
    case PTRACE_GETSIGINFO: {
        siginfo_t info=p->info;
        if (md_signal_origin_matches(&p->origin,&info) && d->host.signal_translate)
            d->host.signal_translate(caller,&p->origin,&info);
        else if (md_signal_native_sender(&info)) {
            info.si_uid=visible_uid(d,caller,info.si_pid,info.si_uid);
            info.si_pid=visible(d,caller,info.si_pid);
        }
        return transfer(caller,data,&info,sizeof(info),1);
    }
    case PTRACE_SETSIGINFO: {
        siginfo_t info;
        struct md_signal_origin origin={0};
        int error=transfer(caller,data,&info,sizeof(info),0);
        if (!error && md_signal_child_notice(&info) && md_signal_origin_matches(&p->origin,&p->info)
                && d->host.signal_translate) {
            siginfo_t visible=p->info;
            d->host.signal_translate(caller,&p->origin,&visible);
            if (info.si_pid==visible.si_pid && info.si_uid==visible.si_uid) {
                /* A reaped sender is no longer in the PID lookup table. */
                origin=p->origin;
                if (info.si_code!=visible.si_code || info.si_status!=visible.si_status)
                    origin.reboot_signal=0;
                info.si_pid=p->info.si_pid; info.si_uid=p->info.si_uid;
                origin.info=info;
            }
        }
        if (!error && !origin.valid && md_signal_native_sender(&info))
            info.si_pid=native(d,caller,info.si_pid);
        if (!error) error=(int)request(d,op,pid,0,&info);
        if (!error) {
            p->info=info;
            if (!origin.valid && d->host.signal_origin) d->host.signal_origin(pid,&info,&origin);
            p->origin=origin;
        }
        return error;
    }
    case PTRACE_GETREGSET: case PTRACE_SETREGSET: return regset(d,caller,p,op,address,data);
    case PTRACE_GET_SYSCALL_INFO: {
        struct ptrace_syscall_info info=p->syscall;
        if (info.op==PTRACE_SYSCALL_INFO_NONE) {
            struct user_pt_regs registers;
            struct iovec io={&registers,sizeof(registers)};
            int error=(int)request(d,PTRACE_GETREGSET,pid,NT_PRSTATUS,&io);
            if (error) return error;
            info.arch=AUDIT_ARCH_AARCH64;
            info.instruction_pointer=registers.pc; info.stack_pointer=registers.sp;
        }
        size_t size=offsetof(struct ptrace_syscall_info,entry);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) size+=sizeof(info.entry);
        if (info.op==PTRACE_SYSCALL_INFO_EXIT) size=offsetof(struct ptrace_syscall_info,exit.is_error)+1;
        if (info.op==PTRACE_SYSCALL_INFO_SECCOMP) size=offsetof(struct ptrace_syscall_info,seccomp.ret_data)+sizeof(info.seccomp.ret_data);
        int error=transfer(caller,data,&info,address<size ? address : size,1);
        return error ? error : (long)size;
    }
    case PTRACE_GETSIGMASK: case PTRACE_SETSIGMASK: {
        uint64_t mask;
        if (address!=sizeof(mask)) return -EINVAL;
        int error=op==PTRACE_SETSIGMASK ? transfer(caller,data,&mask,sizeof(mask),0) : 0;
        if (!error) error=d->host.signal_mask ? d->host.signal_mask(pid,op==PTRACE_SETSIGMASK,&mask)
            : (int)request(d,op,pid,address,&mask);
        if (!error && op==PTRACE_GETSIGMASK) error=transfer(caller,data,&mask,sizeof(mask),1);
        return error;
    }
    case PTRACE_PEEKTEXT: case PTRACE_PEEKDATA: {
        if (!d->host.allowed(caller,pid)) return -EIO;
        /* The raw Linux syscall writes through data; libc returns the word. */
        if (!d->host.trace) return -ENOSYS;
        errno=0; long word=d->host.trace(op,pid,(void *)address,NULL);
        if (word==-1 && errno) return -errno;
        return transfer(caller,data,&word,sizeof(word),1);
    }
    case PTRACE_POKETEXT: case PTRACE_POKEDATA:
        return d->host.allowed(caller,pid) ? request(d,op,pid,address,(void *)data) : -EIO;
    case PTRACE_CONT: case PTRACE_SINGLESTEP: case PTRACE_SYSCALL: case PTRACE_DETACH: {
        if (data>64) return -EIO;
        struct md_signal_origin origin=p->origin;
        p->origin.valid=0;
        p->stopped=0; p->sequence=0; p->step=op==PTRACE_SINGLESTEP;
        p->syscall_mode=op==PTRACE_SYSCALL;
        if (op==PTRACE_DETACH) {
            struct inferior **at=&d->inferiors;
            while (*at!=p) at=&(*at)->next;
            *at=p->next; free(p); d->host.options(pid,0);
        }
        d->host.resume(pid,(int)data,&origin); return 0;
    }
    default: return -EIO;
    }
}
static void wait_probe(struct md_debugger *d,struct waiting *w,enum md_debugger_wait_action action) {
    struct seccomp_data probe=w->call;
    if (w->filtered) {
        pid_t candidate=w->candidates[w->candidate];
        if (probe.nr==SYS_waitid) { probe.args[0]=P_PID; probe.args[1]=candidate; }
        else probe.args[0]=candidate;
    }
    w->probing=1;
    d->host.wait(w->pid,&probe,action);
}
static void wait_retry(struct md_debugger *d,struct waiting *w) {
    w->candidate=0; w->native_children=0; w->retry=0;
    if (w->filtered) {
        free(w->candidates); w->candidates=NULL; w->count=0;
        int filtered=d->host.wait_children(w->pid,&w->call,&w->candidates,&w->count);
        if (filtered>=0) w->filtered=filtered;
        if (filtered<0 || (filtered && !w->count)) {
            md_debugger_wait_complete(d,w->pid,filtered<0 ? filtered : -ECHILD,NULL);
            return;
        }
    }
    wait_probe(d,w,MD_DEBUG_WAIT_REPROBE);
}
int md_debugger_call(struct md_debugger *d,pid_t pid,struct seccomp_data call) {
    if (call.nr!=SYS_wait4 && call.nr!=SYS_waitid && call.nr!=SYS_ptrace) return 0;
    if (call.nr==SYS_ptrace) {
        d->host.complete(pid,control(d,pid,&call)); return MD_DEBUG_CALL_HANDLED;
    }
    unsigned options=wait_options(&call);
    unsigned valid=WNOHANG|WUNTRACED|WCONTINUED|__WALL|__WCLONE|__WNOTHREAD;
    int id=call.nr==SYS_waitid;
    if (id) valid|=WEXITED|WNOWAIT;
    if ((options&~valid) || (id && (!(options&(WEXITED|WSTOPPED|WCONTINUED))
            || (call.args[0]!=P_ALL && call.args[0]!=P_PID && call.args[0]!=P_PGID && call.args[0]!=P_PIDFD)
            || (call.args[0]==P_PID && (int)call.args[1]<=0)))) {
        d->host.complete(pid,-EINVAL); return 1;
    }
    int children;
    int ready=wait_result(d,pid,&call,&children);
    if (ready) return ready;
    pid_t *candidates=NULL; size_t count=0;
    int filtered=d->host.wait_children ? d->host.wait_children(pid,&call,&candidates,&count) : 0;
    if (filtered<0) { free(candidates); d->host.complete(pid,filtered); return 1; }
    if (!children && !filtered) { free(candidates); return 0; }
    if (!children && filtered && !count) { free(candidates); d->host.complete(pid,-ECHILD); return 1; }
    if (d->host.wait_admit && !d->host.wait_admit(pid)) { free(candidates); return MD_DEBUG_CALL_CONTEXT; }
    struct waiting *w=malloc(sizeof(*w));
    if (!w) { free(candidates); d->host.complete(pid,-ENOMEM); return 1; }
    *w=(struct waiting){.next=d->waiters,.pid=pid,.call=call,.probing=1,
        .filtered=filtered,.candidates=candidates,.count=count}; d->waiters=w;
    if (filtered && !count) md_debugger_wait_complete(d,pid,-ECHILD,NULL);
    else wait_probe(d,w,MD_DEBUG_WAIT_PROBE);
    /* EVENT_WAIT: a guest trace stop or actual child exit resumes this wait.
     * Launch cancellation/timeout still owns both debugger and inferior. */
    return 1;
}
void md_debugger_wait_complete(struct md_debugger *d,pid_t pid,long value,const union md_process_wait *captured) {
    struct waiting **at=&d->waiters;
    while (*at && (*at)->pid!=pid) at=&(*at)->next;
    if (!*at) return;
    struct waiting *w=*at;
    int children=0, id=w->call.nr==SYS_waitid, result=0;
    if (id && value==0 && (w->call.args[2] || captured)) {
        siginfo_t info;
        int error=0;
        if (captured) info=captured->info;
        else error=transfer(pid,w->call.args[2],&info,sizeof(info),0);
        if (!error) {
            result=info.si_pid!=0;
            if (result) {
                pid_t reaped=info.si_pid;
                info.si_uid=visible_uid(d,pid,reaped,info.si_uid);
                info.si_pid=visible(d,pid,reaped);
                int error=w->call.args[2] ? transfer(pid,w->call.args[2],&info,sizeof(info),1) : 0;
                if (error) value=error;
                else if (!(wait_options(&w->call)&WNOWAIT) && (info.si_code==CLD_EXITED
                        || info.si_code==CLD_KILLED || info.si_code==CLD_DUMPED)
                        && d->host.reaped) d->host.reaped(pid,reaped,1);
            }
        }
    } else result=value>0;
    if (!result && (value==0 || value==-ECHILD) && w->filtered && w->candidate<w->count) {
        w->native_children|=value==0;
        if (++w->candidate<w->count) { wait_probe(d,w,MD_DEBUG_WAIT_REPROBE); return; }
    }
    if (result || (value<0 && value!=-ECHILD)) {
        if (!id && value>0) {
            pid_t reaped=(pid_t)value;
            value=visible(d,pid,reaped);
            int status;
            int observed=captured || (w->call.args[1]
                && !transfer(pid,w->call.args[1],&status,sizeof(status),0));
            if (captured) status=captured->status;
            if (observed && (WIFEXITED(status) || WIFSIGNALED(status)) && d->host.reaped)
                d->host.reaped(pid,reaped,1);
        }
        d->host.complete(pid,value);
    } else if (wait_result(d,pid,&w->call,&children)) {
        /* The queued trace event owns completion. */
    } else if (w->filtered) {
        if ((!children && !w->native_children) || (wait_options(&w->call)&WNOHANG)) {
            d->host.complete(pid,children || w->native_children ? 0 : -ECHILD);
        } else {
            w->probing=0;
            if (w->retry) wait_retry(d,w);
            else d->host.wait(pid,&w->call,MD_DEBUG_WAIT_PARK);
            return;
        }
    } else if (!children && !value && !(wait_options(&w->call)&WNOHANG)) {
        /* A native nonblocking probe may predate the final tracee exit event.
         * Once no virtual children remain, let the real parent reap normally. */
        struct seccomp_data call=w->call;
        *at=w->next; wait_free(w);
        d->host.wait(pid,&call,MD_DEBUG_WAIT_NATIVE); return;
    } else if (!children || (wait_options(&w->call)&WNOHANG)) {
        d->host.complete(pid,children ? 0 : value);
    } else {
        w->probing=0;
        if (w->retry) {
            w->retry=0; w->probing=1; d->host.wait(pid,&w->call,MD_DEBUG_WAIT_REPROBE);
        } else d->host.wait(pid,&w->call,MD_DEBUG_WAIT_PARK);
        return;
    }
    *at=w->next; wait_free(w);
}
void md_debugger_wait_resumed(struct md_debugger *d, pid_t pid, long error) {
    struct waiting **at=&d->waiters;
    while (*at && (*at)->pid!=pid) at=&(*at)->next;
    if (!*at) return;
    struct waiting *w=*at;
    if (!error) {
        int children;
        if (wait_result(d,pid,&w->call,&children)) {
            *at=w->next; wait_free(w); return;
        }
        wait_retry(d,w);
        return;
    }
    struct seccomp_data call=w->call;
    *at=w->next; wait_free(w);
    /* The kernel signal disposition, including SA_RESTART, owns interruption.
     * Forget the old wait before a handler can issue another wait itself. */
    if (error==-512) d->host.wait(pid,&call,MD_DEBUG_WAIT_RESTART);
    else d->host.complete(pid,error);
}
