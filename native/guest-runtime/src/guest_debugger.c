#define _GNU_SOURCE
#include "guest_debugger.h"
#include <elf.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

struct inferior {
    struct inferior *next;
    pid_t pid, owner;
    unsigned long options, message;
    uint64_t sequence;
    int stopped, status, step, terminal, real_child;
    siginfo_t info;
};
struct waiting {
    struct waiting *next;
    pid_t pid;
    struct user_pt_regs registers;
};
struct md_debugger {
    struct md_debugger_host host;
    struct inferior *inferiors;
    struct waiting *waiters;
    uint64_t sequence;
};
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
static long request(int op, pid_t pid, uintptr_t address, void *data) {
    errno=0;
    long r=ptrace(op,pid,(void *)address,data);
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
    while (d->waiters) { struct waiting *p=d->waiters; d->waiters=p->next; free(p); }
    free(d);
}
static int add(struct md_debugger *d, pid_t pid, pid_t owner, unsigned long options) {
    if (find(d,pid)) return -EPERM;
    struct inferior *p=calloc(1,sizeof(*p));
    if (!p) return -ENOMEM;
    *p=(struct inferior){.next=d->inferiors,.pid=pid,.owner=owner,.options=options,
        .real_child=d->host.parent(pid)==owner};
    d->inferiors=p;
    return 0;
}
static int selected(pid_t owner, pid_t wanted, pid_t pid) {
    return wanted==-1 || wanted==pid || (!wanted && getpgid(pid)==getpgid(owner))
        || (wanted < -1 && getpgid(pid)==-wanted);
}
static int wait_result(struct md_debugger *d, pid_t owner, struct user_pt_regs *r, int *children) {
    struct inferior *chosen=NULL;
    *children=0;
    for (struct inferior *p=d->inferiors; p; p=p->next) {
        if (p->owner!=owner || !selected(owner,(pid_t)r->regs[0],p->pid)) continue;
        *children=1;
        if (p->sequence && (!chosen || p->sequence<chosen->sequence)) chosen=p;
    }
    if (!chosen) return 0;
    long value=chosen->pid;
    int error=r->regs[1] ? transfer(owner,r->regs[1],&chosen->status,sizeof(int),1) : 0;
    if (!error && r->regs[3]) {
        /* A stopped inferior has not been reaped: query its native usage through
         * /proc is not equivalent to wait4 rusage. Leave unsupported output explicit. */
        error=-ENOTSUP;
    }
    if (!error) {
        chosen->sequence=0;
        if (chosen->terminal) {
            struct inferior **at=&d->inferiors;
            while (*at!=chosen) at=&(*at)->next;
            *at=chosen->next; free(chosen);
        }
    }
    d->host.complete(owner,r,error ? error : value);
    return 1;
}
static void wake(struct md_debugger *d, pid_t owner) {
    struct waiting **at=&d->waiters;
    while (*at) {
        struct waiting *w=*at;
        int children;
        if (w->pid==owner && wait_result(d,owner,&w->registers,&children)) {
            *at=w->next; free(w); return;
        }
        at=&w->next;
    }
    /* The debugger may use a SIGCHLD self-pipe instead of a blocking wait. */
    kill(owner,SIGCHLD);
}
int md_debugger_stop(struct md_debugger *d, pid_t pid, int status, const siginfo_t *info, unsigned long message) {
    struct inferior *p=find(d,pid);
    if (!p) return 0;
    p->stopped=1; p->status=status; p->message=message; p->step=0;
    p->info=info ? *info : (siginfo_t){.si_signo=WSTOPSIG(status),.si_code=SI_KERNEL};
    p->sequence=++d->sequence;
    wake(d,p->owner);
    return 1;
}
int md_debugger_exec(struct md_debugger *d, pid_t pid) {
    struct inferior *p=find(d,pid);
    if (!p) return 0;
    unsigned event=p->options & PTRACE_O_TRACEEXEC ? PTRACE_EVENT_EXEC : 0;
    return md_debugger_stop(d,pid,(SIGTRAP<<8)|0x7f|(event<<16),NULL,(unsigned long)pid);
}
int md_debugger_event(struct md_debugger *d, pid_t pid, unsigned event, unsigned long message) {
    struct inferior *p=find(d,pid);
    if (!p || !(p->options&(1UL<<event))) return 0;
    return md_debugger_stop(d,pid,(SIGTRAP<<8)|0x7f|(event<<16),NULL,message);
}
int md_debugger_birth(struct md_debugger *d, pid_t parent, pid_t child, unsigned event) {
    struct inferior *p=find(d,parent);
    if (!p || !(p->options & (1UL<<event))) return 0;
    if (add(d,child,p->owner,p->options)) return -ENOMEM;
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
int md_debugger_owns(struct md_debugger *d, pid_t owner, pid_t pid) {
    struct inferior *p=find(d,pid);
    return p && p->owner==owner && d->host.allowed(owner,pid);
}
void md_debugger_exit(struct md_debugger *d, pid_t pid, int status) {
    struct inferior *ending=find(d,pid);
    pid_t notify=0;
    if (ending && !ending->real_child) {
        ending->terminal=1; ending->status=status; ending->stopped=0;
        ending->sequence=++d->sequence; notify=ending->owner;
    }
    struct inferior **at=&d->inferiors;
    pid_t owner=0;
    while (*at) {
        struct inferior *p=*at;
        if ((p->pid==pid && !p->terminal) || p->owner==pid) {
            if (p->pid==pid) owner=p->owner;
            else if (!p->terminal && (p->options & PTRACE_O_EXITKILL)) kill(p->pid,SIGKILL);
            else if (p->stopped) d->host.resume(p->pid,0);
            *at=p->next; free(p);
        } else at=&p->next;
    }
    struct waiting **waiting=&d->waiters;
    while (*waiting) {
        struct waiting *w=*waiting;
        if (w->pid==pid || (w->pid==owner && selected(owner,(pid_t)w->registers.regs[0],pid))) {
            *waiting=w->next;
            /* Real parenthood and exit reaping remain in the kernel. Resume the
             * original wait syscall, without publishing a duplicate exit status. */
            if (w->pid!=pid) d->host.resume(w->pid,0);
            free(w);
        } else waiting=&w->next;
    }
    if (notify) wake(d,notify);
}
static long regset(pid_t caller, pid_t pid, int op, uintptr_t note, uintptr_t address) {
    struct iovec guest;
    int r=transfer(caller,address,&guest,sizeof(guest),0);
    if (r) return r;
    if (guest.iov_len>65536) return -E2BIG;
    void *bytes=calloc(1,guest.iov_len ? guest.iov_len : 1);
    if (!bytes) return -ENOMEM;
    struct iovec local={bytes,guest.iov_len};
    if (op==PTRACE_SETREGSET) r=transfer(caller,(uintptr_t)guest.iov_base,bytes,guest.iov_len,0);
    if (!r) r=(int)request(op,pid,note,&local);
    if (!r && op==PTRACE_GETREGSET) r=transfer(caller,(uintptr_t)guest.iov_base,bytes,local.iov_len,1);
    guest.iov_len=local.iov_len;
    if (!r) r=transfer(caller,address,&guest,sizeof(guest),1);
    free(bytes); return r;
}
static long control(struct md_debugger *d, pid_t caller, const struct user_pt_regs *r) {
    int op=(int)r->regs[0]; pid_t pid=(pid_t)r->regs[1];
    uintptr_t address=r->regs[2], data=r->regs[3];
    if (op==PTRACE_TRACEME) {
        pid_t parent=d->host.parent(caller);
        return parent>0 && d->host.allowed(parent,caller) ? add(d,caller,parent,0) : -EPERM;
    }
    struct inferior *p=find(d,pid);
    if (!p || p->terminal || p->owner!=caller || !d->host.allowed(caller,pid)) return -ESRCH;
    if (!p->stopped) return -ESRCH;
    switch (op) {
    case PTRACE_SETOPTIONS: {
        unsigned long supported=PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACEFORK|PTRACE_O_TRACEVFORK
            |PTRACE_O_TRACECLONE|PTRACE_O_TRACEEXEC|PTRACE_O_TRACEVFORKDONE|PTRACE_O_EXITKILL;
        if (data & ~supported) return -EINVAL;
        p->options=data; return 0;
    }
    case PTRACE_GETEVENTMSG: return transfer(caller,data,&p->message,sizeof(p->message),1);
    case PTRACE_GETSIGINFO: return transfer(caller,data,&p->info,sizeof(p->info),1);
    case PTRACE_SETSIGINFO: {
        siginfo_t info;
        int error=transfer(caller,data,&info,sizeof(info),0);
        if (!error) error=(int)request(op,pid,0,&info);
        if (!error) p->info=info;
        return error;
    }
    case PTRACE_GETREGSET: case PTRACE_SETREGSET: return regset(caller,pid,op,address,data);
    case PTRACE_PEEKTEXT: case PTRACE_PEEKDATA: {
        /* The raw Linux syscall writes through data; libc returns the word. */
        errno=0; long word=ptrace(op,pid,(void *)address,NULL);
        if (word==-1 && errno) return -errno;
        return transfer(caller,data,&word,sizeof(word),1);
    }
    case PTRACE_POKETEXT: case PTRACE_POKEDATA: return request(op,pid,address,(void *)data);
    case PTRACE_CONT: case PTRACE_SINGLESTEP: case PTRACE_DETACH:
        if (data>64) return -EIO;
        p->stopped=0; p->sequence=0; p->step=op==PTRACE_SINGLESTEP;
        if (op==PTRACE_DETACH) {
            struct inferior **at=&d->inferiors;
            while (*at!=p) at=&(*at)->next;
            *at=p->next; free(p);
        }
        d->host.resume(pid,(int)data); return 0;
    case PTRACE_KILL: return kill(pid,SIGKILL) ? -errno : 0;
    default: return -EIO;
    }
}
int md_debugger_call(struct md_debugger *d, pid_t pid, struct user_pt_regs *r) {
    if (r->regs[8]==SYS_ptrace) { d->host.complete(pid,r,control(d,pid,r)); return 1; }
    if (r->regs[8]!=SYS_wait4) return 0;
    int children;
    if (wait_result(d,pid,r,&children)) return 1;
    if (!children) return 0;
    if (r->regs[2]&WNOHANG) { d->host.complete(pid,r,0); return 1; }
    struct waiting *w=malloc(sizeof(*w));
    if (!w) { d->host.complete(pid,r,-ENOMEM); return 1; }
    *w=(struct waiting){.next=d->waiters,.pid=pid,.registers=*r}; d->waiters=w;
    /* EVENT_WAIT: a guest trace stop or actual child exit resumes this wait.
     * Launch cancellation/timeout still owns both debugger and inferior. */
    return 1;
}
