#define _GNU_SOURCE
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_stop.h"
#include "task_birth.h"
#include "adapter_buffer.h"
#include "fs_buffer.h"
#include "fd_client.h"
#include "signal_delivery.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/ptrace.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <asm/ptrace.h>

#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif
#define STRINGIFY_(n) #n
#define STRINGIFY(n) STRINGIFY_(n)

/* Isolated protocol experiment, not a second runtime implementation. */
extern char checkpoint_brk[],checkpoint_step[],checkpoint_syscall[];
__asm__(".text\n.balign 4\n.global checkpoint_brk\ncheckpoint_brk:\nbrk #0\n"
    ".global checkpoint_step\ncheckpoint_step:\nnop\n"
    ".global checkpoint_syscall\ncheckpoint_syscall:\nsvc #0\nb .\n");
extern char sysemu_gate[];
__asm__(".text\n.balign 4\n.global sysemu_gate\nsysemu_gate:\nsvc #0\nstr x23,[x24]\nb .\n");
extern char sysemu_preserved[],sysemu_preserved_svc[],sysemu_resumed[];
extern long sysemu_pause(pid_t,int,volatile uint64_t *);
/* Stop the actor thread, not its group: a process-directed STOP can instead
 * stop the untraced TSYNC peer before it acknowledges filter installation. */
__asm__(".text\n.balign 4\n.global sysemu_preserved\nsysemu_preserved:\n"
    "mov x18,x7\nmov x7,#0x1234\nmov x17,x7\nmov x7,x18\n"
    ".global sysemu_preserved_svc\nsysemu_preserved_svc:\nsvc #0\nstr x23,[x24]\nb .\n"
    ".global sysemu_pause\nsysemu_pause:\nmov x7,#0x4321\nmov x8,#" STRINGIFY(SYS_tkill) "\nsvc #0\n"
    ".global sysemu_resumed\nsysemu_resumed:\nstr x7,[x2]\nret\n");
extern char sysemu_native[],sysemu_native_return[];
__asm__(".text\n.balign 4\n.global sysemu_native\nsysemu_native:\nsvc #0\n"
    "str x0,[x23]\nmov x8,#" STRINGIFY(SYS_gettid) "\n"
    ".global sysemu_native_return\nsysemu_native_return:\nsvc #0\nstr x0,[x24]\nb .\n");
extern char sysemu_copy[],sysemu_copy_marker[];
__asm__(".text\n.balign 4\n.global sysemu_copy\nsysemu_copy:\n"
    ".irp reg,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18\nldr x\\reg,[x0],#8\n.endr\n"
    ".irp reg,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18\nstr x\\reg,[x2],#8\n.endr\n"
    ".global sysemu_copy_marker\nsysemu_copy_marker:\nsvc #0\nb .\n");
extern long replay_original(long,int,void *,uint64_t *);
extern char replay_original_svc[];
__asm__(".text\n.balign 4\n.global replay_original\nreplay_original:\n"
    "mov x8,x0\nmov x0,x1\nmov x1,x2\nmov x2,#1\nmov x7,#0x4321\n"
    ".global replay_original_svc\nreplay_original_svc:\nsvc #0\nstr x7,[x3]\nret\n");
enum { BREAKPOINT, SINGLESTEP, SYSCALL, SECCOMP, SYSEMU, SYSEMU_CONTEXT,
    IGNORE=1, BLOCK=2, PROTECTED=4, FILTER=8,
    REPLAY=16, EXHAUSTED=32, PENDING=64, DENY_SKIP=128, EXTERNAL=256, SHARED=512,
    TRACE=1024, DENY_GATE=2048 };
struct report { int changed,mask_changed,delivered,progress; };
static void unexpected(int signal) { (void)signal; _exit(90); }
static uint64_t bit(int signal) { return UINT64_C(1)<<(signal-1); }
static struct user_pt_regs registers(pid_t pid) {
    struct user_pt_regs r;
    struct iovec io={&r,sizeof(r)};
    assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
    return r;
}
static void set_registers(pid_t pid,const struct user_pt_regs *r) {
    struct iovec io={(void *)r,sizeof(*r)};
    assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
}
static void set_mask(pid_t pid,uint64_t mask) {
    assert(!ptrace(PTRACE_SETSIGMASK,pid,(void *)sizeof(mask),&mask));
}
static int stopped(pid_t pid) {
    int status;
    /* EVENT_WAIT: the exact tracee stop; the fixture alarm bounds failure. */
    assert(waitpid(pid,&status,0)==pid);
    if (!WIFSTOPPED(status)) fprintf(stderr,"unexpected tracee status=%#x\n",status);
    assert(WIFSTOPPED(status));
    return WSTOPSIG(status);
}
static void resume(pid_t pid,int request,int signal) {
    assert(!ptrace(request,pid,NULL,(void *)(intptr_t)signal));
}
static void child(unsigned flags,struct report *out) {
    pid_t self=(pid_t)syscall(SYS_getpid);
    sigset_t set,before,after;
    sigemptyset(&set); sigaddset(&set,SIGUSR1);
    if (flags&BLOCK) sigaddset(&set,SIGTRAP);
    assert(!sigprocmask(SIG_SETMASK,&set,NULL));
    assert(!sigprocmask(SIG_SETMASK,NULL,&before));
    struct sigaction action={.sa_handler=flags&IGNORE ? SIG_IGN : unexpected};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask,SIGUSR2);
    action.sa_flags=SA_RESTART;
    assert(!sigaction(SIGTRAP,&action,NULL));
    if (flags&EXHAUSTED) {
        struct rlimit limit;
        assert(!getrlimit(RLIMIT_SIGPENDING,&limit)); limit.rlim_cur=0;
        assert(!setrlimit(RLIMIT_SIGPENDING,&limit));
    }
    assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
    if (flags&PROTECTED) assert(!prctl(PR_SET_DUMPABLE,0));
    if (flags&FILTER) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
            BPF_STMT(BPF_RET|BPF_K,flags&TRACE ? SECCOMP_RET_TRACE : SECCOMP_RET_KILL_PROCESS),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,UINT32_MAX,0,1),
            BPF_STMT(BPF_RET|BPF_K,flags&DENY_SKIP ? SECCOMP_RET_KILL_PROCESS : SECCOMP_RET_ALLOW),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpgid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOKEY),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        if (flags&DENY_GATE) {
            struct sock_filter deny[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            program=(struct sock_fprog){sizeof(deny)/sizeof(*deny),deny};
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        }
    }
    assert(!syscall(SYS_kill,self,SIGSTOP));
    ++out->progress;
    assert(!sigaction(SIGTRAP,NULL,&action));
    out->changed=action.sa_handler!=(flags&IGNORE ? SIG_IGN : unexpected)
        || !(action.sa_flags&SA_RESTART) || !sigismember(&action.sa_mask,SIGUSR2);
    assert(!sigprocmask(SIG_SETMASK,NULL,&after));
    for (int i=1;i<NSIG;++i)
        if (sigismember(&before,i)!=sigismember(&after,i)) out->mask_changed=1;
    if (flags&REPLAY) {
        sigemptyset(&set); sigaddset(&set,SIGUSR1); sigaddset(&set,SIGTRAP);
        unsigned seen=0;
        for (unsigned i=0;i<2;++i) {
            struct timespec timeout={.tv_sec=1}; siginfo_t info;
            int n=sigtimedwait(&set,&info,&timeout);
            assert(n==SIGUSR1 || n==SIGTRAP);
            unsigned which=n==SIGUSR1 ? 1 : 2;
            assert(!(seen&which)); seen|=which;
            if (flags&EXHAUSTED) assert(info.si_code==SI_USER && !info.si_pid && !info.si_uid);
            else assert(info.si_code==SI_TKILL && info.si_pid==12345 && info.si_uid==54321);
        }
        assert(seen==3); out->delivered=2;
        struct timespec zero={0}; siginfo_t info;
        assert(sigtimedwait(&set,&info,&zero)==-1 && errno==EAGAIN);
    }
    if (flags&FILTER) assert(syscall(SYS_getpgid,0)==-1 && errno==ENOKEY);
    _exit(0);
}
struct child_arguments { unsigned flags; struct report *out; };
static int shared_child(void *opaque) {
    struct child_arguments *args=opaque;
    child(args->flags,args->out);
    return 99;
}
static void checkpoint(pid_t pid,struct user_pt_regs saved,int mode) {
    saved.pc=(uintptr_t)(mode==BREAKPOINT ? checkpoint_brk
        : mode==SINGLESTEP ? checkpoint_step : mode==SYSEMU_CONTEXT ? sysemu_preserved : checkpoint_syscall);
    saved.regs[8]=SYS_gettid;
    set_registers(pid,&saved);
    resume(pid,mode>=SYSEMU ? PTRACE_SYSEMU : mode==BREAKPOINT || mode==SECCOMP ? PTRACE_CONT
        : mode==SINGLESTEP ? PTRACE_SINGLESTEP : PTRACE_SYSCALL,0);
    int signal=stopped(pid);
    if (mode==BREAKPOINT || mode==SINGLESTEP) {
        siginfo_t info;
        assert(signal==SIGTRAP && !ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
        assert(info.si_code==(mode==BREAKPOINT ? TRAP_BRKPT : TRAP_TRACE));
        return;
    }
    assert(signal==(mode==SECCOMP ? SIGTRAP : SIGTRAP|0x80));
    struct ptrace_syscall_info info={0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
    assert(info.op==(mode==SECCOMP ? PTRACE_SYSCALL_INFO_SECCOMP : PTRACE_SYSCALL_INFO_ENTRY)
        && info.entry.nr==SYS_gettid);
    if (mode>=SYSEMU) return;
    int skip=-1; struct iovec io={&skip,sizeof(skip)};
    assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io));
    if (mode==SECCOMP) return;
    resume(pid,PTRACE_SYSCALL,0);
    assert(stopped(pid)==(SIGTRAP|0x80));
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
    assert(info.op==PTRACE_SYSCALL_INFO_EXIT);
}
static void replay(pid_t pid,struct user_pt_regs saved,unsigned flags,const struct report *out) {
    const int signals[]={SIGUSR1,SIGTRAP};
    uint64_t blocked=bit(SIGUSR1)|bit(SIGTRAP);
    for (unsigned i=0;i<2;++i) {
        int n=signals[i];
        if (flags&PENDING) {
            siginfo_t newer={.si_signo=n,.si_code=SI_QUEUE};
            newer.si_pid=getpid(); newer.si_uid=getuid();
            assert(!syscall(SYS_rt_tgsigqueueinfo,pid,pid,n,&newer));
        }
        /* External delivery is independent of the guest's syscall policy and
         * needs no instruction execution; syscall-exit injection is a control. */
        set_mask(pid,blocked&~bit(n));
        if (flags&EXTERNAL) assert(!syscall(SYS_tkill,pid,n));
        resume(pid,PTRACE_CONT,flags&EXTERNAL ? 0 : n);
        assert(stopped(pid)==n);
        assert(!out->progress);
        siginfo_t original={.si_signo=n,.si_code=SI_TKILL};
        original.si_pid=12345; original.si_uid=54321;
        assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&original));
        set_mask(pid,blocked);
        if (flags&EXTERNAL) {
            if (i==0) {
                /* The previous real signal is requeued under its original
                 * blocked mask while the next real signal creates the stop. */
                int next=signals[i+1];
                if (flags&PENDING) assert(!syscall(SYS_tkill,pid,next));
                assert(!syscall(SYS_tkill,pid,next));
                set_mask(pid,blocked&~bit(next));
                resume(pid,PTRACE_CONT,n);
                assert(stopped(pid)==next);
                assert(!out->progress);
                original.si_signo=next;
                assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&original));
                set_mask(pid,blocked); set_registers(pid,&saved);
                /* Leave the last signal for the caller's final resume. */
                return;
            }
            assert(0);
        }
        struct user_pt_regs gate=saved;
        gate.pc=(uintptr_t)checkpoint_syscall; gate.regs[8]=SYS_gettid;
        set_registers(pid,&gate);
        resume(pid,PTRACE_SYSCALL,n);
        assert(stopped(pid)==(SIGTRAP|0x80));
        int skip=-1; struct iovec io={&skip,sizeof(skip)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io));
        resume(pid,PTRACE_SYSCALL,0);
        assert(stopped(pid)==(SIGTRAP|0x80));
    }
}
static double run(int mode,unsigned flags,unsigned repetitions) {
    if (mode==SECCOMP) flags|=FILTER|TRACE|DENY_SKIP;
    if (mode>=SYSEMU) flags|=FILTER|DENY_SKIP;
    struct report *out=mmap(NULL,sizeof(*out),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(out!=MAP_FAILED);
    struct sigaction original;
    assert(!sigaction(SIGTRAP,NULL,&original));
    void *stack=NULL;
    struct child_arguments args={flags,out};
    if (flags&SHARED) { stack=malloc(65536); assert(stack); }
    pid_t pid=flags&SHARED ? clone(shared_child,(char *)stack+65536,
        CLONE_VM|CLONE_SIGHAND|SIGCHLD,&args) : fork();
    assert(pid>=0);
    if (!pid) child(flags,out);
    assert(stopped(pid)==SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,
        (void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    struct user_pt_regs saved=registers(pid);
    uint64_t mask;
    assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask));
    if (flags&DENY_GATE) {
        struct user_pt_regs gate=saved;
        gate.pc=(uintptr_t)checkpoint_syscall; gate.regs[8]=SYS_gettid;
        set_registers(pid,&gate); resume(pid,PTRACE_CONT,0);
        int status;
        assert(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        assert(!out->progress && !munmap(out,sizeof(*out))); return 0;
    }
    struct timespec begin,end;
    assert(!clock_gettime(CLOCK_MONOTONIC,&begin));
    if (!(flags&EXTERNAL))
        for (unsigned i=0;i<repetitions;++i) { checkpoint(pid,saved,mode); assert(!out->progress); }
    assert(!clock_gettime(CLOCK_MONOTONIC,&end));
    if (flags&REPLAY) replay(pid,saved,flags,out);
    set_registers(pid,&saved); set_mask(pid,mask);
    resume(pid,PTRACE_CONT,flags&EXTERNAL ? SIGTRAP : 0);
    int status;
    assert(waitpid(pid,&status,0)==pid);
    if (mode==SYSCALL && flags&DENY_SKIP && !(flags&EXTERNAL)) {
        assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        assert(!munmap(out,sizeof(*out))); return 0;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status))
        fprintf(stderr,"mode=%d flags=%u status=%#x\n",mode,flags,status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    int expected=(mode==BREAKPOINT || mode==SINGLESTEP) && (flags&(IGNORE|BLOCK));
    assert(out->changed==!!expected && !out->mask_changed);
    if (flags&SHARED) {
        struct sigaction observed;
        assert(!sigaction(SIGTRAP,NULL,&observed));
        assert(observed.sa_handler==(expected ? SIG_DFL : flags&IGNORE ? SIG_IGN : unexpected));
        assert(!sigaction(SIGTRAP,&original,NULL)); free(stack);
    }
    assert(out->delivered==(flags&REPLAY ? 2 : 0));
    assert(out->progress==1);
    assert(!munmap(out,sizeof(*out)));
    return (end.tv_sec-begin.tv_sec)*1000.0+(end.tv_nsec-begin.tv_nsec)/1e6;
}

struct sync_filter { int request,reply; uintptr_t gate; };
static int checkpoint_filter(unsigned flags,uintptr_t gate,int allow_skip) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(gate>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)gate,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,UINT32_MAX,0,1),
        BPF_STMT(BPF_RET|BPF_K,allow_skip ? SECCOMP_RET_ALLOW : SECCOMP_RET_KILL_PROCESS),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpgid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOKEY),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    return (int)syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,flags,&program);
}
static int strict_filter(unsigned flags,uintptr_t gate) {
    return checkpoint_filter(flags,gate,0);
}
static void *sync_filter_thread(void *opaque) {
    struct sync_filter *fds=opaque;
    char byte;
    assert(read(fds->request,&byte,1)==1);
    int result=strict_filter(SECCOMP_FILTER_FLAG_TSYNC,fds->gate);
    assert(syscall(SYS_write,fds->reply,&result,sizeof(result))==sizeof(result));
    /* The thread's cleanup is deliberately syscall-only: gettid is denied
     * for every thread after TSYNC, including libc's thread exit machinery. */
    syscall(SYS_exit,0);
    __builtin_unreachable();
}
static void strict_sysemu(int protected,int tsync,int negative,int convention,int by_ip) {
    uintptr_t checkpoint=(uintptr_t)(convention ? sysemu_preserved_svc : sysemu_gate)+4;
    volatile uint64_t *executed=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(executed!=MAP_FAILED);
    int start[2],request[2],reply[2];
    assert(!pipe(start) && !pipe(request) && !pipe(reply));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=SIG_IGN};
        assert(!sigaction(SIGTRAP,&action,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGTRAP);
        assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        struct sync_filter fds={request[0],reply[1],checkpoint};
        pthread_t thread;
        if (tsync) assert(!pthread_create(&thread,NULL,sync_filter_thread,&fds));
        else assert(!strict_filter(0,checkpoint));
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,executed+1));
        assert(executed[1]==0x4321);
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGTRAP));
        assert(syscall(SYS_getpgid,0)==-1 && errno==ENOKEY);
        assert(!*executed);
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"g",1)==1);
    assert(stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    assert(saved.regs[7]==0x4321);
    gate.pc=(uintptr_t)(convention ? sysemu_preserved : sysemu_gate);
    gate.regs[8]=by_ip ? SYS_getpid : SYS_gettid;
    if (!convention) gate.regs[7]=0x77665544;
    gate.regs[23]=1; gate.regs[24]=(uintptr_t)executed;
    set_registers(pid,&gate); resume(pid,PTRACE_SYSEMU,0);
    assert(stopped(pid)==(SIGTRAP|0x80));
    struct user_pt_regs entry=registers(pid);
    assert(entry.pc==checkpoint && entry.regs[7]==0);
    if (convention) assert(entry.regs[18]==saved.regs[7] && entry.regs[17]==0x1234);
    if (tsync) {
        assert(write(request[1],"s",1)==1);
        int result;
        /* EVENT_WAIT: the peer acknowledges completed TSYNC, not elapsed time. */
        assert(read(reply[0],&result,sizeof(result))==sizeof(result) && !result);
    }
    int status;
    if (!convention) {
        entry.regs[7]=0x11223344;
        set_registers(pid,&entry);
        /* ARM64 hides/restores x7 at syscall stops. A requested interrupt gives
         * a non-syscall stop before the original user continuation can execute. */
        assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
        resume(pid,PTRACE_CONT,0);
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGTRAP && (unsigned)status>>16==PTRACE_EVENT_STOP);
        struct user_pt_regs outside=registers(pid);
        assert(outside.pc==checkpoint && outside.regs[7]==gate.regs[7] && !*executed);
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0 && info.op==PTRACE_SYSCALL_INFO_NONE);
    }
    set_registers(pid,negative ? &gate : &saved);
    struct user_pt_regs outside=registers(pid);
    const struct user_pt_regs *wanted=negative ? &gate : &saved;
    for (unsigned i=0;i<31;++i) assert(outside.regs[i]==wanted->regs[i]);
    assert(outside.pc==wanted->pc && outside.sp==wanted->sp && outside.pstate==wanted->pstate);
    resume(pid,PTRACE_CONT,0);
    assert(waitpid(pid,&status,0)==pid);
    if (negative) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
    else assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!*executed && !munmap((void *)executed,4096));
    close(start[0]); close(start[1]); close(request[0]); close(request[1]); close(reply[0]); close(reply[1]);
}
static struct ptrace_syscall_info call_info(pid_t pid,unsigned operation) {
    struct ptrace_syscall_info info={0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0 && info.op==operation);
    return info;
}
static void native_admission(pid_t pid) {
    struct ptrace_syscall_info original=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    struct user_pt_regs gate=registers(pid); gate.pc-=4;
    assert(original.instruction_pointer==gate.pc+4 && original.entry.nr==gate.regs[8]);
    /* An already-emulated entry cannot become native by changing its resume
     * mode. Leave that entry skipped, then re-enter the identical SVC/args. */
    set_registers(pid,&gate); resume(pid,PTRACE_SYSCALL,0);
    assert(stopped(pid)==(SIGTRAP|0x80)); call_info(pid,PTRACE_SYSCALL_INFO_EXIT);
    resume(pid,PTRACE_SYSCALL,0); assert(stopped(pid)==(SIGTRAP|0x80));
    struct ptrace_syscall_info native=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    assert(native.instruction_pointer==original.instruction_pointer
        && native.entry.nr==original.entry.nr
        && !memcmp(native.entry.args,original.entry.args,sizeof(native.entry.args)));
}
static void native_within_sysemu(int protected,int policy) {
    volatile long *output=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(output!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe(start) && !pipe2(data,O_NONBLOCK));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=SIG_IGN}; assert(!sigaction(SIGTRAP,&action,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!strict_filter(0,(uintptr_t)sysemu_native_return+4));
        if (policy==2) {
            uintptr_t ip=(uintptr_t)sysemu_native+4;
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(ip>>32),0,3),
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)ip,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EXDEV),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog p={sizeof(code)/sizeof(*code),code};
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&p));
        }
        assert(!syscall(SYS_kill,syscall(SYS_getpid),SIGSTOP));
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        assert(!output[1]); _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    gate.pc=(uintptr_t)sysemu_native;
    gate.regs[8]=policy==1 ? SYS_getpgid : SYS_write;
    gate.regs[0]=policy==1 ? 0 : data[1]; gate.regs[1]=(uintptr_t)"q"; gate.regs[2]=1;
    gate.regs[23]=(uintptr_t)output; gate.regs[24]=(uintptr_t)(output+1);
    set_registers(pid,&gate); resume(pid,PTRACE_SYSEMU,0);
    assert(stopped(pid)==(SIGTRAP|0x80));
    native_admission(pid);
    assert(!output[0] && !output[1]);
    /* This entry was reached under SYSCALL, so it still executes through
     * seccomp. SYSEMU arms the following checkpoint, not this native call. */
    resume(pid,PTRACE_SYSEMU,0); assert(stopped(pid)==(SIGTRAP|0x80));
    struct ptrace_syscall_info marker=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    assert(marker.instruction_pointer==(uintptr_t)sysemu_native_return+4 && marker.entry.nr==SYS_gettid);
    assert(output[0]==(policy==1 ? -ENOKEY : policy==2 ? -EXDEV : 1) && !output[1]);
    char bytes[2];
    if (!policy) assert(read(data[0],bytes,sizeof(bytes))==1 && bytes[0]=='q');
    assert(read(data[0],bytes,sizeof(bytes))==-1 && errno==EAGAIN);
    assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL)); resume(pid,PTRACE_CONT,0);
    assert(stopped(pid)==SIGTRAP); call_info(pid,PTRACE_SYSCALL_INFO_NONE);
    assert(!output[1]); set_registers(pid,&saved); resume(pid,PTRACE_CONT,0);
    int status; assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!munmap((void *)output,4096)); close(start[0]); close(start[1]); close(data[0]); close(data[1]);
}
struct signal_observation {
    volatile sig_atomic_t count;
    siginfo_t info;
    uintptr_t pc,sp,x0,x7,handler_stack;
    uint64_t result,progress;
};
static struct signal_observation *signal_observation;
static void observe_signal(int signal,siginfo_t *info,void *context) {
    ucontext_t *uc=context;
    volatile int local=signal;
    struct signal_observation *out=signal_observation;
    out->info=*info;
    out->pc=uc->uc_mcontext.pc; out->sp=uc->uc_mcontext.sp;
    out->x0=uc->uc_mcontext.regs[0]; out->x7=uc->uc_mcontext.regs[7];
    out->handler_stack=(uintptr_t)&local;
    ++out->count;
}
static void pending_native_signal(int protected,int kind,int process_directed) {
    struct signal_observation *out=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(out!=MAP_FAILED);
    char *alternate=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(alternate!=MAP_FAILED);
    int start[2]; assert(!pipe(start));
    int signal=kind==0 ? SIGUSR1 : kind==1 ? SIGTRAP : SIGCONT;
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        signal_observation=out;
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        stack_t stack={.ss_sp=alternate,.ss_size=65536}; assert(!sigaltstack(&stack,NULL));
        struct sigaction action={.sa_sigaction=observe_signal,.sa_flags=SA_SIGINFO|SA_ONSTACK|SA_RESTART};
        assert(!sigaction(signal,&action,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGUSR2);
        assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!strict_filter(0,(uintptr_t)sysemu_gate+4));
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,&out->result));
        assert(out->result==0x4321 && !out->progress && out->count==1);
        struct sigaction current; assert(!sigaction(signal,NULL,&current));
        assert(current.sa_sigaction==observe_signal && (current.sa_flags&SA_ONSTACK));
        assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGUSR2)
            && !sigismember(&mask,signal));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    uint64_t mask;
    assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask));
    assert(saved.pc==(uintptr_t)sysemu_resumed && saved.regs[7]==0x4321);
    gate.pc=(uintptr_t)sysemu_gate; gate.regs[8]=SYS_gettid; gate.regs[7]=0x1357;
    gate.regs[23]=1; gate.regs[24]=(uintptr_t)&out->progress;
    set_registers(pid,&gate); set_mask(pid,UINT64_MAX&~bit(SIGSTOP)&~bit(SIGKILL));
    resume(pid,PTRACE_SYSEMU,0); assert(stopped(pid)==(SIGTRAP|0x80));
    call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    int status;
    if (kind==2) {
        assert(!kill(pid,SIGSTOP)); resume(pid,PTRACE_SYSEMU,0);
        assert(stopped(pid)==SIGSTOP);
        assert(!out->progress && !out->count);
        resume(pid,PTRACE_SYSEMU,SIGSTOP);
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGSTOP && (unsigned)status>>16==PTRACE_EVENT_STOP);
        resume(pid,PTRACE_LISTEN,0);
        assert(!kill(pid,SIGCONT));
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGTRAP && (unsigned)status>>16==PTRACE_EVENT_STOP);
    } else {
        if (process_directed) assert(!kill(pid,signal));
        else assert(!syscall(SYS_tkill,pid,signal));
        assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL)); resume(pid,PTRACE_CONT,0);
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGTRAP && (unsigned)status>>16==PTRACE_EVENT_STOP);
    }
    assert(!out->progress && !out->count);
    call_info(pid,PTRACE_SYSCALL_INFO_NONE);
    set_registers(pid,&saved); set_mask(pid,mask); resume(pid,PTRACE_CONT,0);
    assert(stopped(pid)==signal);
    siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
    assert(info.si_pid==getpid() && info.si_uid==getuid()
        && info.si_code==(kind==2 || process_directed ? SI_USER : SI_TKILL));
    resume(pid,PTRACE_CONT,signal);
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(out->count==1 && out->info.si_signo==signal && out->info.si_code==info.si_code
        && out->info.si_pid==info.si_pid && out->info.si_uid==info.si_uid);
    assert(out->pc==saved.pc && out->sp==saved.sp && out->x0==saved.regs[0] && out->x7==saved.regs[7]);
    assert(out->handler_stack>=(uintptr_t)alternate && out->handler_stack<(uintptr_t)alternate+65536);
    assert(!munmap(out,4096) && !munmap(alternate,65536)); close(start[0]); close(start[1]);
}
static void interrupted_native_call(int protected,int restart) {
    struct signal_observation *out=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(out!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe(start) && !pipe(data));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        signal_observation=out;
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_sigaction=observe_signal,.sa_flags=SA_SIGINFO|(restart ? SA_RESTART : 0)};
        assert(!sigaction(SIGUSR1,&action,NULL));
        action=(struct sigaction){.sa_handler=SIG_IGN}; assert(!sigaction(SIGTRAP,&action,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!strict_filter(0,(uintptr_t)sysemu_native_return+4));
        uint64_t x7;
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,&x7) && x7==0x4321);
        assert(out->count==1 && !out->progress);
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    char *byte=(char *)out+sizeof(*out);
    gate.pc=(uintptr_t)sysemu_native; gate.regs[8]=SYS_read;
    gate.regs[0]=data[0]; gate.regs[1]=(uintptr_t)byte; gate.regs[2]=1;
    gate.regs[23]=(uintptr_t)&out->result; gate.regs[24]=(uintptr_t)&out->progress;
    set_registers(pid,&gate); resume(pid,PTRACE_SYSEMU,0);
    assert(stopped(pid)==(SIGTRAP|0x80)); native_admission(pid);
    assert(!kill(pid,SIGUSR1)); resume(pid,PTRACE_SYSEMU,0);
    assert(stopped(pid)==SIGUSR1 && !out->count && !out->progress);
    struct user_pt_regs interrupted=registers(pid);
    /* ARM64 exposes the provisional restart before ptrace delivery. The
     * handler's SA_RESTART decision is applied only after this stop. */
    assert(interrupted.pc==gate.pc && (long)interrupted.regs[0]==data[0]);
    /* The handler is application code, not an emulated service. Its actual
     * rt_sigreturn must execute, and then the resumed service regains SYSEMU. */
    resume(pid,PTRACE_SYSCALL,SIGUSR1); assert(stopped(pid)==(SIGTRAP|0x80));
    struct ptrace_syscall_info returning=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    assert(returning.entry.nr==SYS_rt_sigreturn && out->count==1 && !out->progress);
    assert(out->pc==gate.pc+(restart ? 0 : 4) && (long)out->x0==(restart ? data[0] : -EINTR)
        && out->sp==interrupted.sp && out->x7==interrupted.regs[7]);
    assert(write(data[1],"q",1)==1);
    resume(pid,PTRACE_SYSEMU,0); assert(stopped(pid)==(SIGTRAP|0x80));
    if (restart) {
        struct ptrace_syscall_info again=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
        assert(again.entry.nr==SYS_read && again.instruction_pointer==gate.pc+4);
        native_admission(pid);
        resume(pid,PTRACE_SYSEMU,0); assert(stopped(pid)==(SIGTRAP|0x80));
    }
    struct ptrace_syscall_info checkpoint=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    assert(checkpoint.entry.nr==SYS_gettid
        && checkpoint.instruction_pointer==(uintptr_t)sysemu_native_return+4);
    assert((long)out->result==(restart ? 1 : -EINTR) && !out->progress);
    if (restart) assert(*byte=='q');
    else { char observed; assert(read(data[0],&observed,1)==1 && observed=='q'); }
    assert(!fcntl(data[0],F_SETFL,O_NONBLOCK));
    char observed; assert(read(data[0],&observed,1)==-1 && errno==EAGAIN);
    assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL)); resume(pid,PTRACE_CONT,0);
    assert(stopped(pid)==SIGTRAP); call_info(pid,PTRACE_SYSCALL_INFO_NONE);
    set_registers(pid,&saved); resume(pid,PTRACE_CONT,0);
    int status; assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!munmap(out,4096)); close(start[0]); close(start[1]); close(data[0]); close(data[1]);
}
struct replay_observation {
    struct signal_observation signal[2];
    struct signal_observation additional[2];
    uint64_t x7,progress;
};
static struct replay_observation *replay_observation;
static struct signal_observation *next_observation(struct replay_observation *out,int signal) {
    unsigned slot=signal==SIGTRAP;
    struct signal_observation *observed=&out->signal[slot];
    if (observed->count) observed=&out->additional[slot];
    assert(!observed->count); return observed;
}
static void replay_handler(int signal,siginfo_t *info,void *context) {
    assert(signal==SIGUSR1 || signal==SIGTRAP);
    signal_observation=next_observation(replay_observation,signal);
    observe_signal(signal,info,context);
}
static void sysemu_signal_replay(int protected,int ignored,int blocked,int pending) {
    struct replay_observation *out=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    char *alternate=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(out!=MAP_FAILED && alternate!=MAP_FAILED);
    int start[2]; assert(!pipe(start));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        replay_observation=out;
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        stack_t stack={.ss_sp=alternate,.ss_size=65536}; assert(!sigaltstack(&stack,NULL));
        sigset_t signals; sigemptyset(&signals); sigaddset(&signals,SIGUSR1); sigaddset(&signals,SIGTRAP);
        struct sigaction action={.sa_sigaction=replay_handler,.sa_mask=signals,
            .sa_flags=SA_SIGINFO|SA_ONSTACK|SA_RESTART};
        assert(!sigaction(SIGUSR1,&action,NULL));
        if (ignored) action.sa_handler=SIG_IGN;
        assert(!sigaction(SIGTRAP,&action,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGUSR2);
        if (blocked) { sigaddset(&mask,SIGUSR1); sigaddset(&mask,SIGTRAP); }
        assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!strict_filter(0,(uintptr_t)sysemu_gate+4));
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,&out->x7));
        assert(out->x7==0x4321 && !out->progress);
        if (blocked) {
            for (unsigned i=0;i<2;++i) {
                siginfo_t info; struct timespec timeout={.tv_sec=1};
                int signal=sigtimedwait(&signals,&info,&timeout);
                assert(signal==SIGUSR1 || signal==SIGTRAP);
                struct signal_observation *observed=&out->signal[signal==SIGTRAP];
                assert(!observed->count); observed->info=info; ++observed->count;
            }
            struct timespec zero={0}; siginfo_t info;
            assert(sigtimedwait(&signals,&info,&zero)==-1 && errno==EAGAIN);
        }
        assert(!sigaction(SIGTRAP,NULL,&action));
        assert(ignored ? action.sa_handler==SIG_IGN : action.sa_sigaction==replay_handler);
        assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGUSR2)
            && sigismember(&mask,SIGUSR1)==blocked && sigismember(&mask,SIGTRAP)==blocked);
        assert(syscall(SYS_getpgid,0)==-1 && errno==ENOKEY);
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    uint64_t mask,shield=UINT64_MAX&~bit(SIGSTOP)&~bit(SIGKILL);
    assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask));
    gate.pc=(uintptr_t)sysemu_gate; gate.regs[8]=SYS_gettid;
    gate.regs[23]=1; gate.regs[24]=(uintptr_t)&out->progress;
    set_mask(pid,shield); set_registers(pid,&gate); resume(pid,PTRACE_SYSEMU,0);
    assert(stopped(pid)==(SIGTRAP|0x80)); call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
    const int signals[]={SIGUSR1,SIGTRAP};
    for (unsigned i=0;i<2;++i) {
        int signal=signals[i];
        if (pending) {
            siginfo_t newer={.si_signo=signal,.si_code=SI_QUEUE};
            newer.si_pid=getpid(); newer.si_uid=getuid();
            assert(!syscall(SYS_rt_tgsigqueueinfo,pid,pid,signal,&newer));
        }
        /* The emulated SVC cannot run or encounter seccomp. Its resume queues
         * the real signal number, not a forced TRAP or a reserved carrier. */
        set_mask(pid,shield&~bit(signal)); resume(pid,PTRACE_CONT,signal);
        assert(stopped(pid)==signal && !out->progress);
        assert(!out->signal[0].count && !out->signal[1].count);
        siginfo_t original={.si_signo=signal,.si_code=SI_TKILL};
        original.si_pid=12345; original.si_uid=54321;
        assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&original));
        set_mask(pid,shield); set_registers(pid,&gate);
        resume(pid,PTRACE_SYSEMU,signal);
        assert(stopped(pid)==(SIGTRAP|0x80)); call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
        assert(!out->progress && !out->signal[0].count && !out->signal[1].count);
    }
    set_registers(pid,&saved); set_mask(pid,mask); resume(pid,PTRACE_CONT,0);
    unsigned delivered=0; int status;
    for (;;) {
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFSTOPPED(status)) break;
        int signal=WSTOPSIG(status);
        assert(!blocked && !((unsigned)status>>16) && (signal==SIGUSR1 || signal==SIGTRAP));
        assert(++delivered<=2);
        siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
        assert(info.si_signo==signal && info.si_code==SI_TKILL && info.si_pid==12345 && info.si_uid==54321);
        resume(pid,PTRACE_CONT,signal);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status) && !out->progress);
    for (unsigned i=0;i<2;++i) {
        struct signal_observation *observed=&out->signal[i];
        assert(observed->count==(i==1 && ignored && !blocked ? 0 : 1));
        if (!observed->count) continue;
        assert(observed->info.si_signo==signals[i] && observed->info.si_code==SI_TKILL
            && observed->info.si_pid==12345 && observed->info.si_uid==54321);
        if (!blocked) {
            assert(observed->pc==saved.pc && observed->sp==saved.sp
                && observed->x0==saved.regs[0] && observed->x7==saved.regs[7]);
            assert(observed->handler_stack>=(uintptr_t)alternate && observed->handler_stack<(uintptr_t)alternate+65536);
        }
    }
    assert(!munmap(out,4096) && !munmap(alternate,65536)); close(start[0]); close(start[1]);
}
static void sysemu_register_copy(int protected,int fault,int negative) {
    uint64_t *source=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    uint64_t *output=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(source!=MAP_FAILED && output!=MAP_FAILED);
    for (unsigned i=0;i<16;++i) source[i]=0x98760000+i;
    source[5]=SYS_getpid;
    int start[2],data[2]; assert(!pipe(start) && !pipe(data));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction trap={.sa_handler=SIG_IGN}; assert(!sigaction(SIGTRAP,&trap,NULL));
        struct sigaction segv={.sa_handler=fault==2 ? SIG_IGN : unexpected};
        assert(!sigaction(SIGSEGV,&segv,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGTRAP);
        if (fault==3) sigaddset(&mask,SIGSEGV);
        assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
        if (fault) assert(!mprotect(source,4096,PROT_NONE));
        /* Native copy_from_user returns EFAULT without a forced userspace
         * signal, including for a blocked/ignored SIGSEGV disposition. */
        if (fault) {
            assert(syscall(SYS_write,data[1],source,8)==-1 && errno==EFAULT);
            struct sigaction observed; assert(!sigaction(SIGSEGV,NULL,&observed));
            assert(observed.sa_handler==segv.sa_handler);
        }
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        /* An unarmed service marker must be fatal, not execute a payload
         * register as an accidental native syscall number. */
        assert(!strict_filter(0,(uintptr_t)sysemu_copy_marker+4));
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,output+16));
        assert(output[16]==0x4321);
        assert(!sigaction(SIGTRAP,NULL,&trap) && trap.sa_handler==SIG_IGN);
        assert(!sigaction(SIGSEGV,NULL,&segv));
        assert(segv.sa_handler==(fault>=2 ? SIG_DFL : unexpected));
        assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGTRAP));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    gate.pc=(uintptr_t)sysemu_copy; gate.regs[0]=(uintptr_t)source; gate.regs[2]=(uintptr_t)output;
    set_registers(pid,&gate); resume(pid,negative ? PTRACE_CONT : PTRACE_SYSEMU,0);
    int status;
    assert(waitpid(pid,&status,0)==pid);
    if (negative) {
        assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
    } else {
        assert(WIFSTOPPED(status));
        if (fault) {
            assert(WSTOPSIG(status)==SIGSEGV);
            siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
            assert(info.si_code==SEGV_ACCERR && info.si_addr==source);
            struct user_pt_regs failed=registers(pid); assert(failed.pc==(uintptr_t)sysemu_copy);
        } else {
            assert(WSTOPSIG(status)==(SIGTRAP|0x80));
            struct ptrace_syscall_info marker=call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
            assert(marker.instruction_pointer==(uintptr_t)sysemu_copy_marker+4 && marker.entry.nr==SYS_getpid);
            struct user_pt_regs observed=registers(pid);
            for (unsigned i=0;i<16;++i) if (i!=4) assert(observed.regs[i+3]==source[i]);
            assert(observed.regs[7]==0);
            assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL)); resume(pid,PTRACE_CONT,0);
            assert(stopped(pid)==SIGTRAP); call_info(pid,PTRACE_SYSCALL_INFO_NONE);
            observed=registers(pid);
            for (unsigned i=0;i<16;++i) assert(observed.regs[i+3]==source[i]);
        }
        set_registers(pid,&saved); resume(pid,PTRACE_CONT,0);
        assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    for (unsigned i=0;i<16;++i) assert(output[i]==(fault ? 0 : source[i]));
    assert(!munmap(source,4096) && !munmap(output,4096));
    close(start[0]); close(start[1]); close(data[0]); close(data[1]);
}
static ssize_t kernel_copy(pid_t self,int writing,void *guest,void *trusted,size_t size) {
    struct iovec local={trusted,size},remote={guest,size};
    return syscall(writing ? SYS_process_vm_writev : SYS_process_vm_readv,
        self,&local,1,&remote,1,0);
}
static void copy_signal_state(int disposition) {
    const int signals[]={SIGSEGV,SIGBUS};
    sigset_t mask; assert(!sigprocmask(SIG_SETMASK,NULL,&mask));
    for (unsigned i=0;i<2;++i) {
        struct sigaction action; assert(!sigaction(signals[i],NULL,&action));
        assert(action.sa_handler==(disposition==1 ? SIG_IGN : unexpected));
        assert(sigismember(&mask,signals[i])==(disposition==2));
    }
}
static void deny_copy_calls(int first,int second) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,first,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,second,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(code[0]),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void kernel_copy_faults(int protected,int disposition) {
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=disposition==1 ? SIG_IGN : unexpected};
        assert(!sigaction(SIGSEGV,&action,NULL) && !sigaction(SIGBUS,&action,NULL));
        sigset_t mask; sigemptyset(&mask);
        if (disposition==2) { sigaddset(&mask,SIGSEGV); sigaddset(&mask,SIGBUS); }
        assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
        size_t page=(size_t)sysconf(_SC_PAGESIZE);
        pid_t self=getpid();
        /* Valid, inaccessible, unmapped, truncated file, read-only, and a
         * valid prefix ending at a protection boundary. No signal repair. */
        for (int kind=0;kind<6;++kind) for (int writing=0;writing<2;++writing) {
            int fd=-1;
            if (kind==3) {
                fd=memfd_create("copy-fault",MFD_CLOEXEC);
                assert(fd>=0 && !ftruncate(fd,(off_t)(2*page)));
            }
            unsigned char *mapping=mmap(NULL,2*page,PROT_READ|PROT_WRITE,
                MAP_SHARED|(fd<0 ? MAP_ANONYMOUS : 0),fd,0);
            assert(mapping!=MAP_FAILED); memset(mapping,0x5a,2*page);
            unsigned char *guest=kind==5 ? mapping+page-8 : mapping;
            if (kind==1) assert(!mprotect(mapping,2*page,PROT_NONE));
            if (kind==2) assert(!munmap(mapping,2*page));
            if (kind==3) assert(!ftruncate(fd,0));
            if (kind==4) assert(!mprotect(mapping,2*page,PROT_READ));
            if (kind==5) assert(!mprotect(mapping+page,page,PROT_NONE));
            unsigned char trusted[16]; memset(trusted,0x33,sizeof(trusted));
            ssize_t result=kernel_copy(self,writing,guest,trusted,sizeof(trusted));
            if (!kind || (kind==4 && !writing)) assert(result==sizeof(trusted));
            else if (kind==5) assert(result==8);
            else assert(result==-1 && errno==EFAULT);
            copy_signal_state(disposition);
            if (result>0) {
                const unsigned char *copied=writing ? guest : trusted;
                for (ssize_t i=0;i<result;++i) assert(copied[i]==(writing ? 0x33 : 0x5a));
                if (!writing && kind==5)
                    for (size_t i=8;i<sizeof(trusted);++i) assert(trusted[i]==0x33);
            }
            if (kind!=2) assert(!munmap(mapping,2*page));
            if (fd>=0) close(fd);
        }
        int pipes[2]; assert(!pipe(pipes));
        unsigned char trusted[16]={0},guest[16]={0};
        deny_copy_calls(SYS_process_vm_readv,SYS_process_vm_writev);
        for (int writing=0;writing<2;++writing)
            assert(kernel_copy(self,writing,guest,trusted,sizeof(trusted))==-1 && errno==EPERM);
        assert(write(pipes[1],guest,sizeof(guest))==sizeof(guest));
        assert(read(pipes[0],trusted,sizeof(trusted))==sizeof(trusted));
        deny_copy_calls(SYS_read,SYS_write);
        assert(write(pipes[1],guest,sizeof(guest))==-1 && errno==EPERM);
        assert(read(pipes[0],trusted,sizeof(trusted))==-1 && errno==EPERM);
        copy_signal_state(disposition);
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: exact copy-control child exit; the fixture alarm bounds failure. */
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
extern long md_read_memory(void *,const void *,size_t);
static void copy_costs(void) {
    unsigned char source[128],output[128]; memset(source,0x5a,sizeof(source));
    pid_t self=getpid(); int pipes[2]; assert(!pipe(pipes));
    const char *names[]={"userspace","process-vm-read","process-vm-write","pipe-roundtrip"};
    for (unsigned round=0;round<5;++round) for (unsigned i=0;i<4;++i) {
        unsigned mode=(i+round)%4;
        memset(output,0,sizeof(output));
        struct timespec start,end; assert(!clock_gettime(CLOCK_MONOTONIC,&start));
        for (unsigned n=0;n<32768;++n) {
            if (!mode) assert(!md_read_memory(output,source,sizeof(source)));
            else if (mode<3) {
                assert(kernel_copy(self,mode==2,mode==2 ? output : source,
                    mode==2 ? source : output,sizeof(source))==sizeof(source));
            } else {
                assert(write(pipes[1],source,sizeof(source))==sizeof(source));
                assert(read(pipes[0],output,sizeof(output))==sizeof(output));
            }
        }
        assert(!clock_gettime(CLOCK_MONOTONIC,&end));
        assert(!memcmp(source,output,sizeof(source)));
        double ms=(end.tv_sec-start.tv_sec)*1000.0+(end.tv_nsec-start.tv_nsec)/1e6;
        printf("copy round=%u mode=%s 32768_copies_128_bytes_ms=%.3f\n",round,names[mode],ms);
    }
    close(pipes[0]); close(pipes[1]);
}
static void replay_at_native_entry(int protected,int restart,int writing,int seccomp) {
    struct signal_observation *out=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(out!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe(start) && !pipe(data));
    long number=writing ? SYS_write : SYS_read;
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        signal_observation=out;
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_sigaction=observe_signal,.sa_flags=SA_SIGINFO|(restart ? SA_RESTART : 0)};
        assert(!sigaction(SIGUSR1,&action,NULL));
        action=(struct sigaction){.sa_handler=SIG_IGN}; assert(!sigaction(SIGTRAP,&action,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!strict_filter(0,(uintptr_t)sysemu_gate+4));
        if (seccomp) {
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)number,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        }
        assert(!syscall(SYS_kill,syscall(SYS_getpid),SIGSTOP));
        byte='q';
        out->result=replay_original(number,data[writing ? 1 : 0],&byte,&out->progress);
        assert(out->count==1 && out->progress==0x4321);
        assert((long)out->result==(writing || restart ? 1 : -EINTR) && byte=='q');
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,
        (void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    for (;;) {
        resume(pid,PTRACE_SYSCALL,0); assert(stopped(pid)==(SIGTRAP|0x80));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY && info.instruction_pointer==(uintptr_t)replay_original_svc+4) {
            assert(info.entry.nr==(unsigned long)number); break;
        }
    }
    if (seccomp) {
        resume(pid,PTRACE_SYSCALL,0);
        assert(stopped(pid)==SIGTRAP); call_info(pid,PTRACE_SYSCALL_INFO_SECCOMP);
    }
    /* Ordinary ptrace signal semantics admit the original syscall, not a
     * cancelled -1 call. read gets a kernel restart result, write completes
     * once, and the later real delivery stop retains the original metadata. */
    assert(!syscall(SYS_tkill,pid,SIGUSR1)); resume(pid,PTRACE_SYSCALL,0);
    assert(stopped(pid)==(SIGTRAP|0x80));
    struct ptrace_syscall_info exited=call_info(pid,PTRACE_SYSCALL_INFO_EXIT);
    assert(exited.exit.rval==(writing ? 1 : -512));
    resume(pid,PTRACE_CONT,0); assert(stopped(pid)==SIGUSR1);
    siginfo_t original={.si_signo=SIGUSR1,.si_code=SI_TKILL};
    original.si_pid=12345; original.si_uid=54321;
    assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&original));
    if (!writing) assert(write(data[1],"q",1)==1);
    resume(pid,PTRACE_CONT,SIGUSR1);
    int status; unsigned replayed=0;
    for (;;) {
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFSTOPPED(status)) break;
        assert(seccomp && !writing && restart && !replayed++ && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
        struct ptrace_syscall_info info=call_info(pid,PTRACE_SYSCALL_INFO_SECCOMP);
        assert(info.seccomp.nr==SYS_read && info.instruction_pointer==(uintptr_t)replay_original_svc+4);
        resume(pid,PTRACE_CONT,0);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(replayed==(unsigned)(seccomp && !writing && restart));
    assert(out->info.si_code==SI_TKILL && out->info.si_pid==12345 && out->info.si_uid==54321);
    assert(out->pc==(uintptr_t)replay_original_svc+(!writing && restart ? 0 : 4));
    assert((long)out->x0==(writing ? 1 : restart ? data[0] : -EINTR) && out->x7==0x4321);
    assert(!fcntl(data[0],F_SETFL,O_NONBLOCK));
    char byte[2];
    if (writing || !restart) assert(read(data[0],byte,2)==1 && byte[0]=='q');
    assert(read(data[0],byte,2)==-1 && errno==EAGAIN);
    assert(!munmap(out,4096)); close(start[0]); close(start[1]); close(data[0]); close(data[1]);
}
static long task_trace(int request,pid_t pid,void *address,void *data) {
    return ptrace(request,pid,address,data);
}
static int checkpoint_restricted;
static unsigned checkpoint_register_reads,checkpoint_register_writes,checkpoint_event_reads,checkpoint_info_reads;
static long checkpoint_trace(int request,pid_t pid,void *address,void *data) {
    if (request==PTRACE_GETREGSET && (uintptr_t)address==NT_PRSTATUS) ++checkpoint_register_reads;
    if (request==PTRACE_SETREGSET && (uintptr_t)address==NT_PRSTATUS) ++checkpoint_register_writes;
    if (request==PTRACE_GETEVENTMSG) ++checkpoint_event_reads;
    if (request==PTRACE_GET_SYSCALL_INFO) ++checkpoint_info_reads;
    return ptrace(request,pid,address,data);
}
static int checkpoint_policy(pid_t pid) { (void)pid; return checkpoint_restricted; }
extern char unified_native[],md_guest_buffer_control_checkpoint[];
__asm__(".text\n.balign 4\n.global unified_native\nunified_native:\nsvc #0\n"
    "str x0,[x23]\nmov x8,x25\nb md_guest_buffer_control\n");
static int checkpoint_site(uintptr_t pc) { return pc==(uintptr_t)md_guest_buffer_control_checkpoint+4; }
extern char entry_scalar_gate[],entry_scalar_return[],md_guest_done_checkpoint[];
extern char md_guest_fd_control_checkpoint[];
__asm__(".text\n.balign 4\n.global entry_scalar_gate\nentry_scalar_gate:\n"
    "mov x0,x22\nblr x21\n"
    ".global entry_scalar_return\nentry_scalar_return:\nstr x0,[x23]\nstr x1,[x23,#16]\nb md_guest_done\n");
static int scalar_site(uintptr_t pc) {
    return checkpoint_site(pc) || pc==(uintptr_t)md_guest_fd_control_checkpoint+4
        || pc==(uintptr_t)md_guest_done_checkpoint+4;
}
static int scalar_entry(uintptr_t pc,unsigned long command) {
    if (pc==(uintptr_t)md_guest_fd_control_checkpoint+4) return md_fd_control_scalar(command);
    return checkpoint_site(pc) && (md_adapter_buffer_scalar(command) || md_fs_buffer_scalar(command));
}
static void scalar_checkpoint(int protected,int restricted,unsigned command,int descriptor) {
    extern struct md_fd_observation md_guest_fd_control(unsigned,uint64_t,long,long);
    volatile uint64_t *output=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(output!=MAP_FAILED);
    int start[2]; assert(!pipe(start));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=SIG_IGN}; assert(!sigaction(SIGTRAP,&action,NULL));
        sigset_t set; sigemptyset(&set); sigaddset(&set,SIGTRAP);
        assert(!sigprocmask(SIG_BLOCK,&set,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
            BPF_STMT(BPF_RET|BPF_K,restricted ? SECCOMP_RET_KILL_PROCESS : SECCOMP_RET_TRACE|MD_INTERCEPT_CHECKPOINT),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,output+1));
        assert(output[0]==0x9876 && output[1]==0x4321 && output[2]==0x7654);
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        assert(!sigprocmask(SIG_SETMASK,NULL,&set) && sigismember(&set,SIGTRAP));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    gate.pc=(uintptr_t)entry_scalar_gate; gate.regs[23]=(uintptr_t)output;
    gate.regs[21]=descriptor ? (uintptr_t)md_guest_fd_control : (uintptr_t)md_guest_buffer_control;
    gate.regs[22]=command;
    checkpoint_restricted=restricted;
    struct md_task_checkpoints runtime={.trace=checkpoint_trace,.restricted=checkpoint_policy,
        .marker=scalar_site,.entry_marker=scalar_entry,.begin=1,.end=UINTPTR_MAX};
    struct iovec io={&gate,sizeof(gate)};
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_CONT,pid,NULL,NULL));
    unsigned seen=0,stops=0; int status;
    while (seen<2) {
        assert(++stops<5 && waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
        int owned=md_task_checkpoint_event(&runtime,pid,&status); assert(owned>=0);
        if (owned) continue;
        assert((unsigned)status>>16==MD_TASK_CHECKPOINT_EVENT);
        gate=registers(pid);
        if (!seen++) {
            uintptr_t marker=descriptor ? (uintptr_t)md_guest_fd_control_checkpoint
                : (uintptr_t)md_guest_buffer_control_checkpoint;
            assert(stops==1 && gate.pc==marker+4 && gate.regs[0]==command);
            gate.regs[0]=0x9876; gate.regs[1]=0x7654;
        } else {
            assert(stops==3 && gate.pc==(uintptr_t)md_guest_done_checkpoint+4 && output[0]==0x9876);
            gate=saved; runtime.begin=runtime.end=0;
        }
        io=(struct iovec){&gate,sizeof(gate)};
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_CONT,pid,NULL,NULL));
    }
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_task_checkpoint_event(&runtime,pid,&status));
    md_task_checkpoints_clear(&runtime);
    assert(!munmap((void *)output,4096)); close(start[0]); close(start[1]);
}
static int nested_task_call(pid_t,int,struct md_task_checkpoints *);
static void unified_checkpoint(int protected,int restricted,int denied,int invalid_number,int injection) {
    volatile long *output=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(output!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe(start) && !pipe2(data,O_NONBLOCK));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=SIG_IGN}; assert(!sigaction(SIGTRAP,&action,NULL));
        sigset_t set; sigemptyset(&set); sigaddset(&set,SIGTRAP);
        assert(!sigprocmask(SIG_SETMASK,&set,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        uintptr_t ip=(uintptr_t)md_guest_buffer_control_checkpoint+4;
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(ip>>32),0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)ip,0,1),
            BPF_STMT(BPF_RET|BPF_K,restricted ? SECCOMP_RET_KILL_PROCESS : SECCOMP_RET_TRACE|MD_INTERCEPT_CHECKPOINT),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpgid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOKEY),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,UINT32_MAX,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_write,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|73),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(!sysemu_pause((pid_t)syscall(SYS_getpid),SIGSTOP,(uint64_t *)(output+2)));
        assert(output[2]==0x4321 && !output[1]);
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        assert(!sigprocmask(SIG_SETMASK,NULL,&set) && sigismember(&set,SIGTRAP));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    struct user_pt_regs saved=registers(pid),gate=saved;
    gate.pc=(uintptr_t)unified_native;
    gate.regs[8]=denied ? SYS_getpgid : SYS_write;
    gate.regs[0]=denied ? 0 : data[1]; gate.regs[1]=(uintptr_t)"q"; gate.regs[2]=1;
    gate.regs[23]=(uintptr_t)output; gate.regs[24]=(uintptr_t)(output+1);
    gate.regs[25]=invalid_number ? UINT64_MAX : SYS_gettid;
    checkpoint_restricted=restricted;
    struct md_task_checkpoints runtime={.trace=checkpoint_trace,.restricted=checkpoint_policy,.marker=checkpoint_site,
        .begin=(uintptr_t)unified_native,.end=(uintptr_t)unified_native+16};
    struct iovec io={&gate,sizeof(gate)};
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_CONT,pid,NULL,NULL));
    unsigned writes=checkpoint_register_writes;
    int status,native_stops=0;
    for (unsigned stops=0;;++stops) {
        assert(stops<16 && waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
        if ((int)stops==injection) {
            printf("checkpoint borrowing protected=%d stage=%u status=%#x\n",protected,stops,status);
            unsigned before=checkpoint_register_writes;
            assert(!nested_task_call(pid,0,&runtime));
            writes+=checkpoint_register_writes-before;
        }
        unsigned before_registers=checkpoint_register_reads;
        int owned=md_task_checkpoint_event(&runtime,pid,&status);
        assert(owned>=0);
        if (owned) continue;
        if ((unsigned)status>>16==PTRACE_EVENT_SECCOMP) {
            assert(!denied && !native_stops++);
            assert(checkpoint_register_reads==before_registers);
            unsigned reads=checkpoint_register_reads,events=checkpoint_event_reads,infos=checkpoint_info_reads;
            struct user_pt_regs actual=registers(pid),delivered;
            struct ptrace_syscall_info actual_info={0},delivered_info={0};
            long size=ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(actual_info),&actual_info);
            assert(size>0 && (unsigned long)size<=sizeof(actual_info));
            unsigned long cookie=0;
            io=(struct iovec){&delivered,sizeof(delivered)};
            if (protected) {
                uint64_t mask;
                assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask));
            }
            /* All three observations retain exactly the same kernel stop,
             * including the production info-before-registers consumer order. */
            if (!invalid_number) assert(md_task_checkpoint_trace(&runtime,PTRACE_GET_SYSCALL_INFO,
                pid,(void *)sizeof(delivered_info),&delivered_info)==size);
            if (invalid_number) assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
            assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETEVENTMSG,pid,NULL,&cookie));
            if (invalid_number) assert(md_task_checkpoint_trace(&runtime,PTRACE_GET_SYSCALL_INFO,
                pid,(void *)sizeof(delivered_info),&delivered_info)==size);
            if (!invalid_number) assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
            assert(cookie==73 && !memcmp(&actual,&delivered,sizeof(actual)));
            assert(!memcmp(&actual_info,&delivered_info,sizeof(actual_info)) && checkpoint_info_reads==infos+protected);
            assert(checkpoint_register_reads==reads+1 && checkpoint_event_reads==events+protected);
            assert(md_task_checkpoint_trace(&runtime,PTRACE_GET_SYSCALL_INFO,
                pid,(void *)sizeof(delivered_info),&delivered_info)==size);
            assert(checkpoint_info_reads==infos+protected+1);
            assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETEVENTMSG,pid,NULL,&cookie));
            assert(cookie==73 && checkpoint_event_reads==events+protected+1);
            assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
            assert(checkpoint_register_reads==reads+2 && !memcmp(&actual,&delivered,sizeof(actual)));
            assert(!md_task_checkpoint_trace(&runtime,PTRACE_CONT,pid,NULL,NULL));
            continue;
        }
        assert((unsigned)status>>16==MD_TASK_CHECKPOINT_EVENT); break;
    }
    assert(native_stops==!denied);
    struct user_pt_regs observed=registers(pid);
    assert(observed.pc==(uintptr_t)md_guest_buffer_control_checkpoint+4 && observed.regs[7]==saved.regs[7]);
    /* Only replay of the native call needs a register write, never the marker. */
    assert(checkpoint_register_writes==writes+restricted);
    assert(observed.regs[8]==SYS_gettid);
    assert((long)observed.regs[0]==(denied ? -ENOKEY : 1));
    unsigned reads=checkpoint_register_reads;
    struct user_pt_regs delivered;
    if (protected && restricted) {
        /* An intervening register write must never expose the old stop. */
        observed.regs[9]^=0x1234;
        io=(struct iovec){&observed,sizeof(observed)};
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
    } else if (protected) {
        uint64_t mask;
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask));
    } else if (restricted) {
        /* Partial regsets retain the kernel's own size/error semantics. */
        io=(struct iovec){&delivered,sizeof(delivered.regs[0])};
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert(io.iov_len==sizeof(delivered.regs[0]) && delivered.regs[0]==observed.regs[0]);
        assert(checkpoint_register_reads==++reads);
    }
    io=(struct iovec){&delivered,sizeof(delivered)};
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
    assert(io.iov_len==sizeof(delivered) && !memcmp(&delivered,&observed,sizeof(delivered)));
    assert(checkpoint_register_reads==reads+(protected || restricted));
    reads=checkpoint_register_reads;
    /* Even an unchanged stop is handed off at most once. */
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
    assert(checkpoint_register_reads==reads+1 && !memcmp(&delivered,&observed,sizeof(delivered)));
    assert(output[0]==(denied ? -ENOKEY : 1) && !output[1]);
    char bytes[2];
    if (!denied) assert(read(data[0],bytes,2)==1 && bytes[0]=='q');
    assert(read(data[0],bytes,2)==-1 && errno==EAGAIN);
    io=(struct iovec){&saved,sizeof(saved)};
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
    assert(!md_task_checkpoint_trace(&runtime,PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_task_checkpoint_event(&runtime,pid,&status));
    md_task_checkpoints_clear(&runtime); assert(!runtime.count);
    assert(!munmap((void *)output,4096)); close(start[0]); close(start[1]); close(data[0]); close(data[1]);
}
static struct md_task_checkpoints *borrowed_checkpoint;
static long borrowed_trace(int request,pid_t pid,void *address,void *data) {
    return borrowed_checkpoint ? md_task_checkpoint_trace(borrowed_checkpoint,request,pid,address,data)
        : task_trace(request,pid,address,data);
}
static int nested_task_call(pid_t pid,int expect_rejected,struct md_task_checkpoints *runtime) {
    struct user_pt_regs saved=registers(pid);
    struct ptrace_syscall_info info={0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
    struct md_task_call call={0};
    struct md_task_call_arguments arguments={.number=SYS_getpid};
    int emulated=runtime ? md_task_checkpoint_suspend(runtime,pid) : 0;
    assert(emulated>=0);
    enum md_task_call_boundary boundary=emulated && info.op==PTRACE_SYSCALL_INFO_ENTRY
        ? MD_TASK_CALL_EMULATED_ENTRY : (enum md_task_call_boundary)info.op;
    borrowed_checkpoint=runtime;
    assert(!md_task_call_begin(&call,borrowed_trace,pid,boundary,
        &saved,(uintptr_t)checkpoint_syscall,(uintptr_t)checkpoint_syscall+4,&arguments));
    unsigned repetitions=0;
    for (unsigned stops=0;;++stops) {
        int status; assert(stops<32 && waitpid(pid,&status,0)==pid);
        if (WIFSIGNALED(status)) {
            assert(expect_rejected && WTERMSIG(status)==SIGSYS); return 1;
        }
        if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_EXIT) {
            assert(expect_rejected);
            unsigned long exiting=0; assert(!ptrace(PTRACE_GETEVENTMSG,pid,NULL,&exiting));
            assert(WIFSIGNALED(exiting) && WTERMSIG(exiting)==SIGSYS);
            resume(pid,PTRACE_CONT,0); continue;
        }
        if (runtime) assert(!md_task_checkpoint_event(runtime,pid,&status));
        int result=md_task_call_event(&call,status);
        if (result<0) fprintf(stderr,"nested call boundary=%u stage=%u status=%#x result=%d\n",info.op,call.stage,status,result);
        assert(result>=0);
        if (result) {
            assert(!expect_rejected && call.result==pid);
            if (runtime && !repetitions++) {
                assert(!md_task_call_again(&call,&arguments)); continue;
            }
            break;
        }
    }
    if (call.result!=pid) fprintf(stderr,"nested result boundary=%u result=%ld expected=%d\n",info.op,call.result,pid);
    assert(!expect_rejected && call.result==pid);
    if (runtime) md_task_checkpoint_restore(runtime,pid);
    borrowed_checkpoint=NULL;
    return 0;
}
static void chained_native_replay(int protected,int restart,int writing,int boundary,int disposition,int pending,int injected,int interposed) {
    struct replay_observation *out=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    char *alternate=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(out!=MAP_FAILED && alternate!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe(start) && !pipe(data));
    long number=writing ? SYS_write : SYS_read;
    uint64_t shield=UINT64_MAX&~bit(SIGSTOP)&~bit(SIGKILL);
    int realtime=SIGRTMIN+3;
    uint64_t mask=bit(SIGUSR2)|bit(realtime)|(disposition>=2 ? bit(SIGTRAP) : 0)
        |(disposition==3 ? bit(SIGUSR1) : 0);
    int effective_restart=restart || interposed==3;
    int reads=writing || effective_restart || disposition==3;
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        replay_observation=out;
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        stack_t stack={.ss_sp=alternate,.ss_size=65536}; assert(!sigaltstack(&stack,NULL));
        sigset_t signals; sigemptyset(&signals); sigaddset(&signals,SIGUSR1); sigaddset(&signals,SIGTRAP);
        struct sigaction action={.sa_sigaction=replay_handler,.sa_mask=signals,
            .sa_flags=SA_SIGINFO|SA_ONSTACK|(restart ? SA_RESTART : 0)};
        assert(!sigaction(SIGUSR1,&action,NULL));
        if (disposition==1) action.sa_handler=SIG_IGN;
        assert(!sigaction(SIGTRAP,&action,NULL));
        assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,&mask,NULL,sizeof(mask)));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        /* Native task injection still cancels/restores the intercepted call;
         * keep that admission domain separate from the strict queue proof. */
        assert(!checkpoint_filter(0,(uintptr_t)sysemu_gate+4,0));
        if (boundary==1) {
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)number,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        }
        assert(!syscall(SYS_kill,syscall(SYS_getpid),SIGSTOP));
        byte='q';
        long result=replay_original(number,data[writing ? 1 : 0],&byte,&out->x7);
        assert(result==(reads ? 1 : -EINTR) && byte=='q' && out->x7==0x4321);
        assert(out->signal[0].count==(disposition==3 ? 0 : 1));
        assert(out->signal[1].count==(disposition==0 ? 1 : 0));
        assert(out->additional[0].count==(pending==2 && disposition!=3 ? 1 : 0));
        assert(out->additional[1].count==(pending==2 && disposition==0 ? 1 : 0));
        if (disposition>=2) {
            sigemptyset(&signals); sigaddset(&signals,SIGTRAP);
            if (disposition==3) sigaddset(&signals,SIGUSR1);
            for (int i=0;i<(disposition==3 ? 2 : 1)*(pending==2 ? 2 : 1);++i) {
                siginfo_t info; struct timespec timeout={.tv_sec=1};
                int signal=sigtimedwait(&signals,&info,&timeout);
                assert(signal==SIGTRAP || (disposition==3 && signal==SIGUSR1));
                struct signal_observation *observed=next_observation(out,signal);
                assert(!observed->count); observed->info=info; ++observed->count;
            }
            siginfo_t info; struct timespec zero={0};
            assert(sigtimedwait(&signals,&info,&zero)==-1 && errno==EAGAIN);
        }
        assert(!sigaction(SIGTRAP,NULL,&action));
        assert(disposition==1 ? action.sa_handler==SIG_IGN : action.sa_sigaction==replay_handler);
        uint64_t actual=0;
        assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&actual,sizeof(actual)) && actual==mask);
        if (pending) {
            sigemptyset(&signals); sigaddset(&signals,realtime);
            for (int i=0;i<2;++i) {
                siginfo_t info; struct timespec timeout={.tv_sec=1};
                assert(sigtimedwait(&signals,&info,&timeout)==realtime);
                assert(info.si_code==SI_QUEUE && info.si_pid==getppid()
                    && info.si_uid==getuid() && info.si_value.sival_int==17+i);
            }
            siginfo_t info; struct timespec zero={0};
            assert(sigtimedwait(&signals,&info,&zero)==-1 && errno==EAGAIN);
        }
        ++out->progress; _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,
        (void *)(intptr_t)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL
            |(injected==2 ? PTRACE_O_TRACEEXIT : 0))));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    for (;;) {
        resume(pid,PTRACE_SYSCALL,0); assert(stopped(pid)==(SIGTRAP|0x80));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(info),&info)>0);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY && info.instruction_pointer==(uintptr_t)replay_original_svc+4) {
            assert(info.entry.nr==(unsigned long)number); break;
        }
    }
    if (boundary==1) {
        resume(pid,PTRACE_SYSCALL,0); assert(stopped(pid)==SIGTRAP);
        call_info(pid,PTRACE_SYSCALL_INFO_SECCOMP);
    }
    int expect_rejected=0;
    if (injected && boundary!=2 && nested_task_call(pid,expect_rejected,NULL)) goto rejected;
    set_mask(pid,shield);
    if (pending) {
        const int signals[]={SIGUSR1,SIGTRAP};
        for (unsigned i=0;i<2;++i) {
            siginfo_t newer={.si_signo=signals[i],.si_code=SI_QUEUE};
            newer.si_pid=getpid(); newer.si_uid=getuid();
            if (pending==2) assert(!syscall(SYS_rt_sigqueueinfo,pid,signals[i],&newer));
            else assert(!syscall(SYS_rt_tgsigqueueinfo,pid,pid,signals[i],&newer));
        }
        for (int i=0;i<2;++i) {
            siginfo_t info={.si_signo=realtime,.si_code=SI_QUEUE};
            info.si_pid=getpid(); info.si_uid=getuid(); info.si_value.sival_int=17+i;
            assert(!syscall(SYS_rt_tgsigqueueinfo,pid,pid,realtime,&info));
        }
    }
    /* For an EXIT boundary, a read already has a pending interrupting signal;
     * a completed write can start replay without any pending signal. */
    if (boundary!=2 || !writing) {
        assert(!syscall(SYS_tkill,pid,SIGUSR1)); set_mask(pid,shield&~bit(SIGUSR1));
    }
    resume(pid,PTRACE_SYSCALL,0); assert(stopped(pid)==(SIGTRAP|0x80));
    struct ptrace_syscall_info exited=call_info(pid,PTRACE_SYSCALL_INFO_EXIT);
    assert(exited.exit.rval==(writing ? 1 : -512));
    if (injected && boundary==2 && nested_task_call(pid,expect_rejected,NULL)) goto rejected;
    if (boundary==2 && writing) {
        assert(!syscall(SYS_tkill,pid,SIGUSR1)); set_mask(pid,shield&~bit(SIGUSR1));
    }
    resume(pid,PTRACE_CONT,0);
    int first=stopped(pid);
    if (injected && boundary==2 && !writing && first==SIGTRAP) {
        call_info(pid,PTRACE_SYSCALL_INFO_NONE);
        resume(pid,PTRACE_CONT,0); first=stopped(pid);
    }
    assert(first==SIGUSR1);
    struct user_pt_regs saved=registers(pid);
    if (saved.regs[7]!=0x4321) fprintf(stderr,"chained x7=%llu injected=%d boundary=%d writing=%d\n",saved.regs[7],injected,boundary,writing);
    assert(saved.pc==(uintptr_t)replay_original_svc+(writing ? 4 : 0) && saved.regs[7]==0x4321);
    siginfo_t original={.si_signo=SIGUSR1,.si_code=SI_TKILL};
    original.si_pid=12345; original.si_uid=54321;
    assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&original));
    if (interposed==3) {
        /* Negative control: a userspace checkpoint leaves the original
         * do_signal frame before its SA_RESTART decision. Restoring registers
         * later cannot reconstruct that kernel-local decision. */
        struct user_pt_regs gate=saved;
        gate.pc=(uintptr_t)sysemu_gate; gate.regs[8]=SYS_gettid;
        gate.regs[23]=1; gate.regs[24]=(uintptr_t)&out->progress;
        set_mask(pid,shield); set_registers(pid,&gate); resume(pid,PTRACE_SYSEMU,SIGUSR1);
        assert(stopped(pid)==(SIGTRAP|0x80)); call_info(pid,PTRACE_SYSCALL_INFO_ENTRY);
        assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL)); resume(pid,PTRACE_CONT,0);
        assert(stopped(pid)==SIGTRAP); call_info(pid,PTRACE_SYSCALL_INFO_NONE);
        set_registers(pid,&saved);
    }
    if (interposed==2) {
        set_mask(pid,shield);
        assert(!kill(pid,SIGSTOP)); resume(pid,PTRACE_CONT,SIGUSR1);
        assert(stopped(pid)==SIGSTOP); resume(pid,PTRACE_CONT,SIGSTOP);
        int status; assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGSTOP && (unsigned)status>>16==PTRACE_EVENT_STOP);
        resume(pid,PTRACE_LISTEN,0); assert(!kill(pid,SIGCONT));
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGTRAP && (unsigned)status>>16==PTRACE_EVENT_STOP);
    }
    set_mask(pid,shield&~bit(SIGTRAP));
    assert(!syscall(SYS_tkill,pid,SIGTRAP));
    if (interposed==1) assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
    resume(pid,PTRACE_CONT,interposed>=2 ? 0 : SIGUSR1);
    if (interposed==1) {
        int status; assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
            && WSTOPSIG(status)==SIGTRAP && (unsigned)status>>16==PTRACE_EVENT_STOP);
        resume(pid,PTRACE_CONT,0);
    }
    assert(stopped(pid)==SIGTRAP);
    struct user_pt_regs current=registers(pid);
    assert(!memcmp(&current,&saved,sizeof(saved)));
    assert(!out->progress && !out->x7 && !out->signal[0].count && !out->signal[1].count);
    original.si_signo=SIGTRAP; assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&original));
    if (!writing) assert(write(data[1],"q",1)==1);
    /* No userspace checkpoint, syscall cancellation, or register rewrite:
     * stay inside the original kernel get_signal/restart decision throughout. */
    set_mask(pid,mask); resume(pid,PTRACE_CONT,SIGTRAP);
    int status; unsigned delivered=0,replayed=0,continued=0;
    for (;;) {
        assert(waitpid(pid,&status,0)==pid);
        if (!WIFSTOPPED(status)) break;
        if ((unsigned)status>>16==PTRACE_EVENT_EXIT) {
            unsigned long exit_status;
            assert(injected==2 && !ptrace(PTRACE_GETEVENTMSG,pid,NULL,&exit_status));
            assert(WIFEXITED(exit_status) && !WEXITSTATUS(exit_status));
            resume(pid,PTRACE_CONT,0); continue;
        }
        if ((unsigned)status>>16==PTRACE_EVENT_SECCOMP) {
            struct ptrace_syscall_info info=call_info(pid,PTRACE_SYSCALL_INFO_SECCOMP);
            /* Blocked sigtimedwait does not use read/write. */
            assert(boundary==1 && !writing && reads && !replayed++
                && info.seccomp.nr==SYS_read && info.instruction_pointer==(uintptr_t)replay_original_svc+4);
            resume(pid,PTRACE_CONT,0); continue;
        }
        int signal=WSTOPSIG(status);
        if (signal==SIGCONT && interposed==2) {
            siginfo_t info; assert(!continued++ && !((unsigned)status>>16));
            assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
            assert(info.si_code==SI_USER && info.si_pid==getpid() && info.si_uid==getuid());
            resume(pid,PTRACE_CONT,SIGCONT); continue;
        }
        assert(!((unsigned)status>>16) && (signal==SIGUSR1 || (pending==2 && signal==SIGTRAP))
            && ++delivered<=(pending==2 ? 3u : 1u));
        siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
        assert((info.si_code==SI_TKILL && signal==SIGUSR1 && info.si_pid==12345 && info.si_uid==54321)
            || (pending==2 && info.si_code==SI_QUEUE && info.si_pid==getpid() && info.si_uid==getuid()));
        resume(pid,PTRACE_CONT,signal);
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status))
        fprintf(stderr,"chained protected=%d restart=%d write=%d boundary=%d disposition=%d pending=%d status=%#x\n",
            protected,restart,writing,boundary,disposition,pending,status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status) && out->progress==1);
    assert(replayed==(unsigned)(boundary==1 && !writing && reads));
    assert(continued==(unsigned)(interposed==2));
    for (unsigned i=0;i<2;++i) {
        struct signal_observation *observed=&out->signal[i];
        assert(observed->count==(i==1 && disposition==1 ? 0 : 1));
        if (!observed->count) continue;
        assert(observed->info.si_signo==(i ? SIGTRAP : SIGUSR1) && observed->info.si_code==SI_TKILL
            && observed->info.si_pid==12345 && observed->info.si_uid==54321);
        if (!(mask&bit(i ? SIGTRAP : SIGUSR1))) {
            assert(observed->pc==(uintptr_t)replay_original_svc+(!writing && effective_restart ? 0 : 4));
            assert((long)observed->x0==(writing ? 1 : effective_restart ? data[0] : -EINTR)
                && observed->x7==0x4321 && observed->sp==saved.sp);
            assert(observed->handler_stack>=(uintptr_t)alternate && observed->handler_stack<(uintptr_t)alternate+65536);
        }
        struct signal_observation *additional=&out->additional[i];
        assert(additional->count==(pending==2 && !(i==1 && disposition==1) ? 1 : 0));
        if (additional->count) {
            assert(additional->info.si_signo==(i ? SIGTRAP : SIGUSR1) && additional->info.si_code==SI_QUEUE
                && additional->info.si_pid==getpid() && additional->info.si_uid==getuid());
            if (!(mask&bit(i ? SIGTRAP : SIGUSR1)))
                assert(additional->pc==observed->pc && additional->sp==observed->sp
                    && additional->x0==observed->x0 && additional->x7==observed->x7);
        }
    }
    assert(!fcntl(data[0],F_SETFL,O_NONBLOCK)); char byte[2];
    if (writing || !reads) assert(read(data[0],byte,2)==1 && byte[0]=='q');
    assert(read(data[0],byte,2)==-1 && errno==EAGAIN);
    goto cleanup;
rejected:
    assert(injected==2 && boundary!=1 && !out->progress && !out->x7
        && !out->signal[0].count && !out->signal[1].count);
    assert(!fcntl(data[0],F_SETFL,O_NONBLOCK));
    if (boundary==2 && writing) assert(read(data[0],byte,2)==1 && byte[0]=='q');
    assert(read(data[0],byte,2)==-1 && errno==EAGAIN);
cleanup:
    assert(!munmap(out,4096) && !munmap(alternate,65536));
    close(start[0]); close(start[1]); close(data[0]); close(data[1]);
}
static int pending_shared(pid_t pid,int signal) {
    struct ptrace_peeksiginfo_args args={.flags=PTRACE_PEEKSIGINFO_SHARED,.nr=64};
    siginfo_t info[64];
    long n=ptrace(PTRACE_PEEKSIGINFO,pid,&args,info); assert(n>=0);
    for (long i=0;i<n;++i) if (info[i].si_signo==signal) return 1;
    return 0;
}
static void stale_continue_control(int protected) {
    int start[2]; assert(!pipe(start));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGCONT);
        assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
        assert(!syscall(SYS_kill,syscall(SYS_getpid),SIGSTOP));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)PTRACE_O_EXITKILL));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    assert(!kill(pid,SIGCONT) && pending_shared(pid,SIGCONT));
    assert(!kill(pid,SIGSTOP) && !pending_shared(pid,SIGCONT));
    resume(pid,PTRACE_CONT,0);
    int status; assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
    if (WSTOPSIG(status)==SIGTRAP) {
        assert((unsigned)status>>16==PTRACE_EVENT_STOP);
        resume(pid,PTRACE_CONT,0);
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
    }
    assert(WSTOPSIG(status)==SIGSTOP && !((unsigned)status>>16));
    resume(pid,PTRACE_CONT,SIGSTOP);
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
        && WSTOPSIG(status)==SIGSTOP && (unsigned)status>>16==PTRACE_EVENT_STOP);
    resume(pid,PTRACE_LISTEN,0);
    assert(waitpid(pid,&status,WNOHANG)==0);
    /* The first CONT was invalidated by STOP. Regenerating its retained copy
     * nevertheless wakes the group immediately, even while CONT is blocked. */
    assert(!kill(pid,SIGCONT));
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
        && WSTOPSIG(status)==SIGTRAP && (unsigned)status>>16==PTRACE_EVENT_STOP);
    assert(pending_shared(pid,SIGCONT));
    resume(pid,PTRACE_CONT,0);
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    close(start[0]); close(start[1]);
}
static void retained_continue(int protected,int process) {
    int start[2]; assert(!pipe(start));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=unexpected};
        assert(!sigaction(SIGCONT,&action,NULL));
        assert(!syscall(SYS_kill,syscall(SYS_getpid),SIGSTOP));
        _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,NULL,(void *)PTRACE_O_EXITKILL));
    assert(write(start[1],"s",1)==1 && stopped(pid)==SIGSTOP);
    assert(!(process ? kill(pid,SIGCONT) : syscall(SYS_tkill,pid,SIGCONT)));
    resume(pid,PTRACE_CONT,0);
    int status;
    for (;;) {
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status));
        if (!((unsigned)status>>16)) break;
        assert((unsigned)status>>16==PTRACE_EVENT_STOP);
        resume(pid,PTRACE_CONT,0);
    }
    assert(WSTOPSIG(status)==SIGCONT);
    siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
    struct md_signal_delivery queue={0};
    int signal=md_signal_delivery_retain(&queue,pid,&info); assert(signal==SIGCONT);
    assert(!ptrace(PTRACE_INTERRUPT,pid,NULL,NULL));
    resume(pid,PTRACE_CONT,signal);
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
        && (unsigned)status>>16==PTRACE_EVENT_STOP);
    struct ptrace_peeksiginfo_args args={.nr=1};
    args.flags=process ? PTRACE_PEEKSIGINFO_SHARED : 0;
    assert(ptrace(PTRACE_PEEKSIGINFO,pid,&args,&info)==1 && info.si_signo==SIGCONT);
    assert(!kill(pid,SIGSTOP));
    siginfo_t pending[64]; args.nr=64;
    long count=ptrace(PTRACE_PEEKSIGINFO,pid,&args,pending); assert(count>=0);
    for (long i=0;i<count;++i) assert(pending[i].si_signo!=SIGCONT);
    assert(!md_signal_delivery_publish(&queue,pid,pid) && !md_signal_delivery_pending(&queue));
    resume(pid,PTRACE_CONT,0);
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    resume(pid,PTRACE_CONT,SIGSTOP);
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status)
        && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGSTOP);
    resume(pid,PTRACE_LISTEN,0);
    assert(waitpid(pid,&status,WNOHANG)==0);
    assert(!kill(pid,SIGCONT));
    unsigned continued=0;
    while (waitpid(pid,&status,0)==pid && WIFSTOPPED(status)) {
        if ((unsigned)status>>16) assert((unsigned)status>>16==PTRACE_EVENT_STOP);
        else { assert(WSTOPSIG(status)==SIGCONT); ++continued; }
        resume(pid,PTRACE_CONT,0);
    }
    assert(continued==1 && WIFEXITED(status) && !WEXITSTATUS(status));
    md_signal_delivery_clear(&queue);
    close(start[0]); close(start[1]);
}
static void *terminal_race_worker(void *argument) {
    int clone=argument!=NULL;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,clone ? SYS_clone : SYS_getpid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|73),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
    syscall(clone ? SYS_clone : SYS_getpid,CLONE_THREAD,0,0,0,0);
    _exit(91);
}
static void *birth_waiter(void *unused) {
    (void)unused;
    for (;;) pause();
    return NULL;
}
static void *birth_worker(void *argument) {
    int kind=(int)(intptr_t)argument;
    if (!kind) {
        pthread_t child; assert(!pthread_create(&child,NULL,birth_waiter,NULL));
    } else {
        pid_t child=kind==1 ? fork() : vfork(); assert(child>=0);
        if (!child) _exit(17);
    }
    return birth_waiter(NULL);
}
static void birth_terminal_race(int kind,int protected,int code) {
    int channel[2]; assert(!pipe2(channel,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL)); raise(SIGSTOP);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        pthread_t thread; assert(!pthread_create(&thread,NULL,birth_worker,(void *)(intptr_t)kind));
        char byte; assert(read(channel[0],&byte,1)==1);
        _exit(code);
    }
    int status; assert(waitpid(child,&status,__WALL)==child && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_SETOPTIONS,child,NULL,(void *)(PTRACE_O_TRACECLONE|PTRACE_O_TRACEFORK
        |PTRACE_O_TRACEVFORK|PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL)));
    resume(child,PTRACE_CONT,0);
    pid_t actor=0,held=0; unsigned long newborn=0;
    unsigned event=kind==1 ? PTRACE_EVENT_FORK : kind==2 ? PTRACE_EVENT_VFORK : PTRACE_EVENT_CLONE;
    while (!newborn) {
        pid_t pid=waitpid(-1,&status,__WALL); assert(pid>0 && WIFSTOPPED(status));
        if (pid==child && (unsigned)status>>16==PTRACE_EVENT_CLONE) {
            unsigned long id; assert(!ptrace(PTRACE_GETEVENTMSG,pid,NULL,&id)); actor=(pid_t)id;
            if (held==actor) { resume(actor,PTRACE_CONT,0); held=0; }
        } else if (pid==actor && (unsigned)status>>16==event) {
            assert(!ptrace(PTRACE_GETEVENTMSG,pid,NULL,&newborn) && newborn>0);
            int terminal=-1;
            assert(md_task_birth_read(task_trace,actor,event,SYS_clone,&terminal)==(long)newborn && !terminal);
            break;
        }
        if (pid==child || pid==actor) resume(pid,PTRACE_CONT,0);
        else { assert(!held); held=pid; }
    }
    if (!held) assert(waitpid((pid_t)newborn,&status,__WALL)==(pid_t)newborn && WIFSTOPPED(status));
    else assert(held==(pid_t)newborn);
    assert(write(channel[1],"x",1)==1);
    /* EVENT_WAIT: group exit replaces the collected birth stop; no child is
     * resumed after group exit. The alarm bounds all three terminal events. */
    unsigned exits=0;
    while (exits<(kind ? 2U : 3U)) {
        pid_t pid=waitpid(-1,&status,__WALL);
        assert(pid==child || pid==actor || pid==(pid_t)newborn);
        assert(WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_EXIT) ++exits;
    }
    unsigned long message=1;
    assert(!ptrace(PTRACE_GETEVENTMSG,actor,NULL,&message) && message==(unsigned)code<<8);
    int terminal=0;
    assert(md_task_birth_read(task_trace,actor,event,SYS_clone,&terminal)==(long)newborn && terminal);
    assert(md_task_birth_terminal(task_trace,actor,SYS_clone,ULONG_MAX)==(long)newborn);
    assert(md_task_stop_displaced(task_trace,actor)==1);
    /* A separate process survives its creator's group death. */
    resume((pid_t)newborn,PTRACE_CONT,0);
    resume(actor,PTRACE_CONT,0); resume(child,PTRACE_CONT,0);
    unsigned reaped=0; pid_t pid;
    while ((pid=waitpid(-1,&status,__WALL))>0) {
        assert(pid==child || pid==actor || pid==(pid_t)newborn);
        if (WIFSTOPPED(status)) {
            assert(kind && pid==(pid_t)newborn);
            resume(pid,PTRACE_CONT,0); continue;
        }
        assert(WIFEXITED(status) && WEXITSTATUS(status)==(kind && pid==(pid_t)newborn ? 17 : code));
        ++reaped;
    }
    assert(errno==ECHILD && reaped==3);
    close(channel[0]); close(channel[1]);
}
static void terminal_race(int protected,int entry) {
    int channel[2]; assert(!pipe2(channel,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL)); raise(SIGSTOP);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        pthread_t thread; assert(!pthread_create(&thread,NULL,terminal_race_worker,(void *)(intptr_t)(entry==2)));
        char byte; assert(read(channel[0],&byte,1)==1);
        _exit(0);
    }
    int status; assert(waitpid(child,&status,__WALL)==child && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_SETOPTIONS,child,NULL,(void *)(PTRACE_O_TRACECLONE|PTRACE_O_TRACESECCOMP|PTRACE_O_TRACESYSGOOD
        |PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL)));
    resume(child,PTRACE_CONT,0);
    pid_t actor=0;
    while (!actor) {
        pid_t pid=waitpid(-1,&status,__WALL); assert(pid>0 && WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_SECCOMP) actor=pid;
        else resume(pid,PTRACE_CONT,0);
    }
    struct md_task_checkpoints runtime={.trace=task_trace,.restricted=checkpoint_policy,.marker=checkpoint_site};
    int original=status;
    assert(!md_task_stop_displaced(task_trace,actor));
    assert(!md_task_checkpoint_event(&runtime,actor,&status) && status==original);
    if (entry==1) {
        struct user_pt_regs regs=registers(actor);
        runtime.begin=regs.pc-4; runtime.end=regs.pc+4;
        struct iovec io={&regs,sizeof(regs)};
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_SETREGSET,actor,(void *)NT_PRSTATUS,&io));
        checkpoint_restricted=1;
        assert(!md_task_checkpoint_trace(&runtime,PTRACE_CONT,actor,NULL,NULL));
        assert(waitpid(actor,&original,__WALL)==actor && WSTOPSIG(original)==(SIGTRAP|0x80));
        assert(!md_task_stop_displaced(task_trace,actor));
    }
    struct md_task_call injected={0};
    if (entry==3) {
        struct user_pt_regs saved=registers(actor);
        struct md_task_call_arguments args={.number=SYS_gettid};
        assert(!md_task_call_begin(&injected,task_trace,actor,MD_TASK_CALL_SECCOMP,&saved,
            (uintptr_t)checkpoint_syscall,(uintptr_t)checkpoint_syscall+4,&args));
        assert(waitpid(actor,&original,__WALL)==actor && WSTOPSIG(original)==(SIGTRAP|0x80));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,actor,sizeof(info),&info)>0
            && info.op==PTRACE_SYSCALL_INFO_EXIT);
    }
    assert(write(channel[1],"x",1)==1);
    /* EVENT_WAIT: exit_group replaces the sibling's already-collected entry;
     * the process alarm bounds this exact terminal observation. */
    assert(waitpid(child,&status,__WALL)==child && (unsigned)status>>16==PTRACE_EVENT_EXIT);
    assert(waitpid(actor,&status,__WALL)==actor && (unsigned)status>>16==PTRACE_EVENT_EXIT);
    unsigned long cookie=1;
    assert(!ptrace(PTRACE_GETEVENTMSG,actor,NULL,&cookie) && !cookie);
    assert(md_task_stop_displaced(task_trace,actor)==1);
    if (entry==2) {
        assert(!md_task_birth_terminal(task_trace,actor,SYS_clone,CLONE_THREAD));
    }
    if (entry==3) assert(md_task_call_event(&injected,original)==-ESRCH);
    else assert(md_task_checkpoint_event(&runtime,actor,&original)==-ESRCH);
    resume(actor,PTRACE_CONT,0); resume(child,PTRACE_CONT,0);
    unsigned reaped=0; pid_t pid;
    while ((pid=waitpid(-1,&status,__WALL))>0) {
        assert((pid==child || pid==actor) && WIFEXITED(status) && !WEXITSTATUS(status));
        ++reaped;
    }
    assert(errno==ECHILD && reaped==2);
    md_task_checkpoints_clear(&runtime); close(channel[0]); close(channel[1]);
}
int main(int argc,char **argv) {
    setvbuf(stdout,NULL,_IOLBF,0);
    alarm(60);
    if (argc==1 || (argc==2 && !strcmp(argv[1],"--checkpoint-borrow"))) {
        for (int protected=0;protected<2;++protected)
            for (int denied=0;denied<2;++denied)
                for (int invalid=0;invalid<2;++invalid)
                    for (int stage=0;stage<(denied ? 5 : 6);++stage)
                        unified_checkpoint(protected,1,denied,invalid,stage);
        puts("PASS checkpoint borrowing: intermediate stages retain exact-once IO and registers");
        if (argc==2) return 0;
    }
    for (int kind=0;kind<3;++kind)
        for (int protected=0;protected<2;++protected)
            for (int code=0;code<2;++code) birth_terminal_race(kind,protected,code ? 77 : 0);
    puts("PASS birth stop replacement: 12 controls; clone/fork/vfork, dumpable=0, zero/nonzero exit, surviving children");
    for (int protected=0;protected<2;++protected)
        for (int entry=0;entry<4;++entry) terminal_race(protected,entry);
    puts("PASS terminal stop replacement: exit_group, collected seccomp/SYSEMU, dumpable=0, exact terminal reaping");
    if (argc==2 && !strcmp(argv[1],"--terminal-race")) return 0;
    if (argc==2 && !strcmp(argv[1],"--copy-fault")) {
        for (int protected=0;protected<2;++protected) {
            for (int fault=1;fault<4;++fault) sysemu_register_copy(protected,fault,0);
            for (int disposition=0;disposition<3;++disposition)
                kernel_copy_faults(protected,disposition);
        }
        puts("LIMIT service copy fault: 6 controls; ignored/blocked SIGSEGV still resets before ptrace; native copy returns EFAULT unchanged");
        puts("PASS kernel copy: 72 controls; read/write, dumpable=0, ignored/blocked SEGV/BUS, partial copy, protection, unmap and truncated file");
        puts("LIMIT kernel copy seccomp: 6 policies; self process_vm and pipe copy can both be denied");
        printf("PASS copy fault controls: uid=%u\n",getuid());
        copy_costs();
        return 0;
    }
    if (argc==2 && !strcmp(argv[1],"--job-control")) {
        for (int protected=0;protected<2;++protected)
            for (int process=0;process<2;++process) retained_continue(protected,process);
        puts("PASS retained job control");
        return 0;
    }
    const unsigned scalar_commands[]={MD_BUFFER_MAPPED,MD_BUFFER_TAKE,MD_BUFFER_RELEASE,
        MD_BUFFER_PROC_META,MD_BUFFER_PID_NUMBER,MD_BUFFER_DESCRIPTOR_META,
        MD_BUFFER_MEMORY_OPEN,MD_BUFFER_MEMORY_OPEN_RESULT,MD_BUFFER_CONTROL_IOCTL_SPEC,
        MD_BUFFER_CONTROL_IOCTL_LAYOUT,MD_BUFFER_PROC_ACK,MD_BUFFER_UTS,MD_BUFFER_SHM_STAT,
        MD_BUFFER_NETWORK_CREDENTIALS,
        MD_FS_BUFFER_CALL,MD_FS_BUFFER_EXEC_PREPARE,MD_FS_BUFFER_EXEC_CANCEL,MD_FS_BUFFER_PROC_LINK,
        MD_FS_BUFFER_IPC_IO_PREPARE,MD_FS_BUFFER_IPC_IO_COPY,MD_FS_BUFFER_IPC_IO_SELECT};
    const unsigned fd_commands[]={MD_FD_KIND,MD_FD_SELECTORS,MD_FD_SELECTOR_QUERY,
        MD_FD_SENT,MD_FD_RECEIVED,MD_FD_END,MD_FD_RELEASE,MD_FD_PAIR_END};
    assert(!md_adapter_buffer_scalar(MD_BUFFER_FINISH) && !md_adapter_buffer_scalar(MD_BUFFER_PROC_START));
    assert(!md_adapter_buffer_scalar(MD_BUFFER_CONTROL_IOCTL) && !md_adapter_buffer_scalar(MD_BUFFER_CONTROL_MAP));
    assert(!md_fd_control_scalar(MD_FD_SELECTOR_INSTALL) && !md_fd_control_scalar(MD_FD_DUPLICATE_BEGIN)
        && !md_fd_control_scalar(MD_FD_DUPLICATE_END) && !md_fd_control_scalar(MD_FD_INPUT));
    assert(!md_fs_buffer_scalar(MD_FS_BUFFER_RESULT_REPLAY) && !md_fs_buffer_scalar(MD_BUFFER_FINISH));
    for (unsigned i=0;i<sizeof(scalar_commands)/sizeof(*scalar_commands);++i)
        for (int protected=0;protected<2;++protected)
            for (int restricted=0;restricted<2;++restricted) scalar_checkpoint(protected,restricted,scalar_commands[i],0);
    for (unsigned i=0;i<sizeof(fd_commands)/sizeof(*fd_commands);++i)
        for (int protected=0;protected<2;++protected)
            for (int restricted=0;restricted<2;++restricted) scalar_checkpoint(protected,restricted,fd_commands[i],1);
    printf("PASS scalar checkpoint: %zu cases; entry-only C return, full-register completion, strict seccomp and blocked SIGTRAP\n",
        4*(sizeof(scalar_commands)/sizeof(*scalar_commands)+sizeof(fd_commands)/sizeof(*fd_commands)));
    for (int protected=0;protected<2;++protected)
        for (int restricted=0;restricted<2;++restricted)
            for (int denied=0;denied<2;++denied) for (int invalid=0;invalid<2;++invalid)
                unified_checkpoint(protected,restricted,denied,invalid,-1);
    puts("PASS unified checkpoint owner: 16 cases; TRACE/SYSEMU, protected tasks, denied calls, exact-once IO, x0/x7, invalid incoming x8, SIG_IGN and blocked SIGTRAP");
    unsigned cases=0;
    for (int mode=BREAKPOINT;mode<=SYSEMU_CONTEXT;++mode)
        for (unsigned flags=0;flags<16;++flags) {
            run(mode,flags,1); ++cases;
        }
    for (unsigned flags=0;flags<16;++flags) {
        if (flags&BLOCK) continue;
        for (unsigned variant=0;variant<3;++variant) {
            run(SYSCALL,flags|BLOCK|REPLAY|(variant==1 ? EXHAUSTED : variant==2 ? PENDING : 0),1);
            ++cases;
            run(SYSCALL,flags|BLOCK|REPLAY|EXTERNAL|DENY_SKIP|(variant==1 ? EXHAUSTED : variant==2 ? PENDING : 0),1);
            ++cases;
        }
    }
    for (int mode=BREAKPOINT;mode<=SYSEMU_CONTEXT;++mode)
        for (unsigned flags=0;flags<4;++flags) { run(mode,flags|SHARED,1); ++cases; }
    for (unsigned flags=0;flags<8;++flags) {
        run(SYSCALL,flags|FILTER|DENY_SKIP,1);
        run(SECCOMP,flags|DENY_GATE,1);
    }
    for (int protected=0;protected<2;++protected)
        for (int tsync=0;tsync<2;++tsync)
            for (int negative=0;negative<2;++negative)
                for (int convention=0;convention<2;++convention)
                    for (int by_ip=0;by_ip<2;++by_ip) strict_sysemu(protected,tsync,negative,convention,by_ip);
    puts("PASS SYSEMU strict policy: 32 cases; IP/nr KILL, TSYNC at entry, dumpable=0, full GPR restore, preserved x7");
    for (int protected=0;protected<2;++protected)
        for (int policy=0;policy<3;++policy) native_within_sysemu(protected,policy);
    puts("PASS SYSEMU native admission: 6 cases; original IP/args, exactly-once write, nr/IP denial and protected task");
    for (int protected=0;protected<2;++protected)
        for (int kind=0;kind<3;++kind)
            for (int process=0;process<(kind==2 ? 1 : 2);++process) pending_native_signal(protected,kind,process);
    puts("PASS SYSEMU pending signals: 10 cases; original siginfo/ucontext, altstack, no service signal and native STOP/LISTEN/CONT");
    for (int protected=0;protected<2;++protected)
        for (int restart=0;restart<2;++restart) interrupted_native_call(protected,restart);
    puts("PASS SYSEMU interrupted IO: 4 cases; real handler/sigreturn, EINTR versus SA_RESTART and exactly-once read");
    for (int protected=0;protected<2;++protected)
        for (int ignored=0;ignored<2;++ignored)
            for (int blocked=0;blocked<2;++blocked)
                for (int pending=0;pending<2;++pending) sysemu_signal_replay(protected,ignored,blocked,pending);
    puts("PASS SYSEMU signal replay: 16 cases; ignored/blocked TRAP, handlers/altstack, coalescing, exact info/context and strict seccomp");
    for (int protected=0;protected<2;++protected) {
        for (int negative=0;negative<2;++negative) sysemu_register_copy(protected,0,negative);
        for (int fault=1;fault<4;++fault) sysemu_register_copy(protected,fault,0);
    }
    puts("PASS SYSEMU register copy: 4 cases; payload x3-x18 including x7/x8, restored context and unarmed-marker rejection");
    puts("LIMIT service copy fault: 6 controls; ignored/blocked SIGSEGV still resets before ptrace; native copy returns EFAULT unchanged");
    for (int protected=0;protected<2;++protected)
        for (int restart=0;restart<2;++restart)
            for (int writing=0;writing<2;++writing)
                for (int seccomp=0;seccomp<2;++seccomp) replay_at_native_entry(protected,restart,writing,seccomp);
    puts("PASS native entry replay: 16 cases; no cancelled syscall, strict seccomp, exact siginfo, EINTR/restart and exactly-once write");
    for (int protected=0;protected<2;++protected)
        for (int restart=0;restart<2;++restart)
            for (int writing=0;writing<2;++writing)
                for (int boundary=0;boundary<3;++boundary)
                    for (int disposition=0;disposition<4;++disposition)
                        for (int pending=0;pending<2;++pending)
                            chained_native_replay(protected,restart,writing,boundary,disposition,pending,0,0);
    puts("PASS chained replay: 192 cases; two retained signals, entry/seccomp/exit, native restart, strict filter, masks/altstack and exact-once IO");
    for (int protected=0;protected<2;++protected)
        for (int restart=0;restart<2;++restart)
            for (int writing=0;writing<2;++writing)
                for (int boundary=0;boundary<3;++boundary)
                    for (int disposition=0;disposition<4;++disposition)
                        chained_native_replay(protected,restart,writing,boundary,disposition,1,1,0);
    puts("PASS chained task injection: 96 cases; production task_call, native restart, exact siginfo/registers and single IO, all under strict KILL(-1)");
    for (int interposed=1;interposed<3;++interposed)
        for (int protected=0;protected<2;++protected)
            for (int restart=0;restart<2;++restart)
                for (int writing=0;writing<2;++writing)
                    for (int boundary=0;boundary<3;++boundary)
                        for (int disposition=0;disposition<4;++disposition)
                            chained_native_replay(protected,restart,writing,boundary,disposition,1,0,interposed);
    puts("PASS chained intervening stops: 192 cases; INTERRUPT and native STOP/LISTEN/CONT inside replay, ordered realtime signals retained in kernel");
    for (int protected=0;protected<2;++protected)
        for (int restart=0;restart<2;++restart)
            for (int writing=0;writing<2;++writing)
                for (int boundary=0;boundary<3;++boundary)
                    for (int disposition=0;disposition<4;++disposition)
                        chained_native_replay(protected,restart,writing,boundary,disposition,2,0,0);
    puts("PASS chained process pending: 96 cases; distinct private/shared pending signals retain their own siginfo and delivery count");
    for (int protected=0;protected<2;++protected) {
        for (int boundary=0;boundary<3;++boundary)
            chained_native_replay(protected,0,0,boundary,2,0,0,3);
        stale_continue_control(protected);
        for (int process=0;process<2;++process) retained_continue(protected,process);
    }
    puts("PASS retained job control: 4 cases; private/shared requeue preserves STOP cancellation without regenerating CONT or waking the group");
    puts("LIMIT checkpoint during replay: 6 controls; userspace checkpoint loses the native no-SA_RESTART decision, read restarts instead of EINTR");
    puts("LIMIT regenerated job control: 2 controls; a retained CONT already invalidated by STOP wakes the stopped group when generated again");
    for (int protected=0;protected<2;++protected)
        for (int writing=0;writing<2;++writing)
            for (int boundary=0;boundary<3;boundary+=2)
                chained_native_replay(protected,0,writing,boundary,2,0,2,0);
    puts("PASS task injection admission: 8 controls; ENTRY/EXIT reconstruction never executes a cancelled syscall under KILL(-1)");
    printf("PASS checkpoints: %u positive/control cases, 16 strict-filter rejection controls; uid=%u\n",cases,getuid());
    const char *names[]={"brk","single-step","syscall","seccomp-trace","sysemu","sysemu-context"};
    for (unsigned round=0;round<5;++round)
        for (int i=0;i<6;++i) {
            int mode=(i+round)%6;
            printf("checkpoint round=%u mode=%s 1000_rounds_ms=%.3f\n",round,names[mode],run(mode,0,1000));
        }
    return 0;
}
