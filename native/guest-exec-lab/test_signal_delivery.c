#define _GNU_SOURCE
#include "signal_delivery.h"
#include "syscall_result.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <stdio.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <stddef.h>
#include <sys/resource.h>
#include <sys/signalfd.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <asm/ptrace.h>
#include <unistd.h>

static siginfo_t original(int signal) {
    siginfo_t info={.si_signo=signal,.si_code=SI_TKILL};
    info.si_pid=12345; info.si_uid=54321;
    return info;
}
static void unexpected(int signal) { (void)signal; _exit(90); }
static void coalesced_kernel_signal(int signal,int code) {
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        struct sigaction action={.sa_handler=unexpected};
        sigemptyset(&action.sa_mask); assert(!sigaction(signal,&action,NULL));
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        assert(!raise(SIGSTOP));
        assert(syscall(SYS_getpid)>0);
        sigset_t set; sigemptyset(&set); sigaddset(&set,signal);
        siginfo_t info;
        struct timespec bound={.tv_sec=1};
        assert(sigtimedwait(&set,&info,&bound)==signal);
        assert(info.si_code==SI_TKILL && info.si_pid==12345 && info.si_uid==54321);
        bound=(struct timespec){0};
        assert(sigtimedwait(&set,&info,&bound)==-1 && errno==EAGAIN);
        _exit(0);
    }
    int status;
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(!syscall(SYS_tgkill,pid,pid,signal));
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==signal);
    siginfo_t pending={.si_signo=signal,.si_code=code};
    assert(!ptrace(PTRACE_SETSIGINFO,pid,NULL,&pending));
    uint64_t mask=UINT64_C(1)<<(signal-1);
    assert(!ptrace(PTRACE_SETSIGMASK,pid,(void *)sizeof(mask),&mask));
    /* Requeue an asynchronous kernel signal in the task's private pending
     * set, where publishing the same standard carrier must coalesce. */
    assert(!ptrace(PTRACE_SYSCALL,pid,NULL,(void *)(intptr_t)signal));
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
    struct md_signal_delivery q={0};
    siginfo_t info=original(signal);
    assert(!md_signal_delivery_hold(&q,&info));
    assert(!md_signal_delivery_publish(&q,pid,pid) && q.active && q.mask==mask);
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==signal);
    assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&pending) && pending.si_code==code);
    int deliver=signal;
    assert(md_signal_delivery_event(&q,pid,signal,&deliver)==MD_SIGNAL_RESTORED);
    assert(!q.active && !q.first && deliver==signal);
    assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)deliver));
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    md_signal_delivery_clear(&q);
}
static volatile sig_atomic_t realtime_count;
static void realtime_handler(int signal,siginfo_t *info,void *context) {
    (void)context;
    if (signal!=SIGRTMAX || info->si_code!=SI_QUEUE
            || info->si_value.sival_int!=realtime_count+1) _exit(91);
    ++realtime_count;
}
static void realtime_order(int retain_again) {
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        struct sigaction action={.sa_sigaction=realtime_handler,.sa_flags=SA_SIGINFO};
        sigemptyset(&action.sa_mask); assert(!sigaction(SIGRTMAX,&action,NULL));
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        assert(!raise(SIGSTOP));
        while (realtime_count<3) pause();
        _exit(0);
    }
    int status;
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    union sigval value={.sival_int=1}; assert(!sigqueue(pid,SIGRTMAX,value));
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==SIGRTMAX);
    siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info));
    struct md_signal_delivery admission={0};
    assert(!md_signal_delivery_retain(&admission,pid,&info));
    for (int n=2;n<=3;++n) { value.sival_int=n; assert(!sigqueue(pid,SIGRTMAX,value)); }
    assert(!md_signal_delivery_publish(&admission,pid,pid));
    struct md_signal_delivery q=admission;
    admission=(struct md_signal_delivery){0};
    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    unsigned stops=0;
    while (waitpid(pid,&status,0)==pid && WIFSTOPPED(status)) {
        assert(WSTOPSIG(status)==SIGRTMAX && ++stops<=4);
        int deliver=SIGRTMAX;
        int expected=q.realtime ? MD_SIGNAL_RESTORED : MD_SIGNAL_NEW;
        assert(md_signal_delivery_event(&q,pid,SIGRTMAX,&deliver)==expected);
        if (retain_again && stops==1) {
            assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&info) && info.si_value.sival_int==1);
            assert(!md_signal_delivery_retain(&q,pid,&info));
            assert(!md_signal_delivery_publish(&q,pid,pid));
            deliver=0;
        }
        assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)deliver));
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status) && stops==3u+(unsigned)retain_again);
    assert(!q.realtime && !q.carriers[SIGRTMAX] && !md_signal_delivery_pending(&q));
    md_signal_delivery_clear(&q);
}
static uintptr_t pc(pid_t pid) {
    struct user_pt_regs regs;
    struct iovec io={&regs,sizeof(regs)};
    assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io));
    return regs.pc;
}
static void child(unsigned round,int traced) {
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set,SIGUSR1); sigaddset(&set,SIGTRAP); sigaddset(&set,SIGURG);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    struct sigaction action={.sa_handler=unexpected};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGTRAP,&action,NULL));
    if (round==2 || round==3) {
        struct rlimit limit;
        assert(!getrlimit(RLIMIT_SIGPENDING,&limit)); limit.rlim_cur=0;
        assert(!setrlimit(RLIMIT_SIGPENDING,&limit));
    }
    if (traced) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        if (round==1) assert(!raise(SIGURG));
        assert(!raise(SIGSTOP));
        if (round==6) {
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpid,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        }
        if (round>=4 && round<=6) assert(syscall(SYS_getpid)>0);
        if (round>=7) {
            uint64_t changed=UINT64_C(1)<<((round==7 ? SIGUSR2 : SIGURG)-1),observed=0;
            assert(!syscall(SYS_rt_sigprocmask,round==7 ? SIG_BLOCK : SIG_UNBLOCK,&changed,NULL,8));
            assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&observed,8));
            assert(!!(observed&changed)==(round==7));
        }
    } else assert(!raise(SIGUSR1) && !raise(SIGTRAP));
    unsigned count=round==1 || round==3 ? 3 : 2;
    uint64_t seen=0;
    int fd=round==2 || round==3 ? signalfd(-1,&set,SFD_CLOEXEC|SFD_NONBLOCK) : -1;
    if (round==2 || round==3) assert(fd>=0);
    for (unsigned i=0;i<count;++i) {
        int number,code; pid_t sender; uid_t uid;
        if (fd>=0) {
            struct signalfd_siginfo info;
            assert(read(fd,&info,sizeof(info))==sizeof(info));
            number=info.ssi_signo; code=info.ssi_code; sender=info.ssi_pid; uid=info.ssi_uid;
        } else {
            siginfo_t info;
            struct timespec bound={.tv_sec=1};
            assert(sigtimedwait(&set,&info,&bound)>0);
            number=info.si_signo; code=info.si_code; sender=info.si_pid; uid=info.si_uid;
        }
        if (round==2 || round==3) {
            /* Native standard-signal allocation failure also yields SI_USER
             * with zero sender; no userspace implementation can recover
             * information the kernel could not store. Keep delivery itself. */
            assert(code==SI_USER && !sender && !uid);
        } else {
            assert(code==SI_TKILL);
            if (number==SIGURG) assert(round==1 && sender==getpid() && uid==getuid());
            else assert((number==SIGUSR1 || number==SIGTRAP) && sender==12345 && uid==54321);
        }
        assert(!(seen&(UINT64_C(1)<<(number-1))));
        seen|=UINT64_C(1)<<(number-1);
    }
    assert((seen&((UINT64_C(1)<<(SIGTRAP-1))|(UINT64_C(1)<<(SIGUSR1-1))))
        ==((UINT64_C(1)<<(SIGTRAP-1))|(UINT64_C(1)<<(SIGUSR1-1))));
    if (fd>=0) assert(!close(fd));
    assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==unexpected);
    _exit(0);
}
int main(void) {
    alarm(15);
    coalesced_kernel_signal(SIGALRM,SI_KERNEL);
    coalesced_kernel_signal(SIGCHLD,CLD_EXITED);
    coalesced_kernel_signal(SIGIO,POLL_IN);
    realtime_order(0); realtime_order(1);
    for (long value=-4095;value<=4095;++value)
        assert(md_syscall_restarting(value)==(value==-512 || value==-513 || value==-514 || value==-516));
    assert(!md_syscall_restarting(-EINTR));
    pid_t native=fork(); assert(native>=0);
    if (!native) child(2,0);
    int native_status;
    assert(waitpid(native,&native_status,0)==native && WIFEXITED(native_status) && !WEXITSTATUS(native_status));
    for (unsigned round=0;round<9;++round) {
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) child(round,1);
        int status;
        assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
        if (round==4 || round==5) {
            assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)PTRACE_O_TRACESYSGOOD));
            for (unsigned i=0;i<(round==4 ? 2u : 1u);++i) {
                assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
                assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
                struct ptrace_syscall_info call={0};
                assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(call),&call)>0);
                assert(call.op==(i ? PTRACE_SYSCALL_INFO_EXIT : PTRACE_SYSCALL_INFO_ENTRY));
                if (!i) assert(call.entry.nr==SYS_getpid);
            }
        }
        if (round==6) {
            assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP)));
            assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
            assert(waitpid(pid,&status,0)==pid && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
        }
        if (round>=7) {
            assert(!ptrace(PTRACE_SETOPTIONS,pid,NULL,(void *)PTRACE_O_TRACESYSGOOD));
            assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
            assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
            struct ptrace_syscall_info call={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(call),&call)>0);
            assert(call.op==PTRACE_SYSCALL_INFO_ENTRY && call.entry.nr==SYS_rt_sigprocmask);
        }
        uintptr_t stopped=pc(pid);
        struct md_signal_delivery q={0};
        siginfo_t info=original(SIGUSR1);
        assert(!md_signal_delivery_hold(&q,&info));
        info.si_pid=11111; assert(!md_signal_delivery_hold(&q,&info));
        info=original(SIGTRAP); assert(!md_signal_delivery_hold(&q,&info));
        assert(!md_signal_delivery_publish(&q,pid,pid));
        assert(!md_signal_delivery_publish(&q,pid,pid));
        if (round>=7) {
            uint64_t mask=q.mask;
            assert(!ptrace(PTRACE_SETSIGMASK,pid,(void *)sizeof(mask),&mask));
            assert(!ptrace(PTRACE_SYSCALL,pid,NULL,NULL));
            assert(waitpid(pid,&status,0)==pid && WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
            struct ptrace_syscall_info call={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(call),&call)>0);
            assert(call.op==PTRACE_SYSCALL_INFO_EXIT && !call.exit.rval);
            assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask));
            assert(!md_signal_delivery_update_mask(&q,pid,mask) && q.mask==mask);
            stopped=pc(pid);
        }
        if (round==3) {
            assert(!syscall(SYS_tgkill,pid,pid,SIGURG));
            assert(!syscall(SYS_tgkill,pid,pid,SIGTRAP));
        }
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
        unsigned stops=0;
        while (waitpid(pid,&status,0)==pid && WIFSTOPPED(status)) {
            /* The replayed guest call still follows its original seccomp policy. */
            if (round==6 && (unsigned)status>>16==PTRACE_EVENT_SECCOMP) {
                assert(!ptrace(PTRACE_CONT,pid,NULL,NULL)); continue;
            }
            assert(q.active && ++stops<=2);
            int deliver=0;
            int owned=md_signal_delivery_event(&q,pid,WSTOPSIG(status),&deliver);
            assert(owned==(stops<2 ? MD_SIGNAL_CARRIER : MD_SIGNAL_RESTORED));
            if (owned==MD_SIGNAL_RESTORED) {
                siginfo_t restored;
                assert(!ptrace(PTRACE_GETSIGINFO,pid,NULL,&restored));
                assert(!q.active && restored.si_signo==SIGTRAP);
                assert(restored.si_pid==original(SIGTRAP).si_pid);
                deliver=WSTOPSIG(status);
            }
            assert(pc(pid)==stopped);
            assert(!ptrace(PTRACE_CONT,pid,NULL,(void *)(intptr_t)deliver));
        }
        if (!WIFEXITED(status) || WEXITSTATUS(status) || stops!=2)
            fprintf(stderr,"signal round=%u status=%#x stops=%u\n",round,status,stops);
        assert(WIFEXITED(status) && !WEXITSTATUS(status) && stops==2);
        assert(!q.first && !q.last && !q.active);
        md_signal_delivery_clear(&q);
    }
    struct md_signal_delivery q={0};
    siginfo_t info=original(SIGKILL);
    assert(md_signal_delivery_hold(&q,&info)==-EINVAL);
    info=original(SIGSTOP); assert(md_signal_delivery_hold(&q,&info)==-EINVAL);
    info=original(SIGRTMIN); assert(md_signal_delivery_hold(&q,&info)==-EINVAL);
    info=original(SIGTRAP); info.si_code=TRAP_BRKPT;
    assert(md_signal_delivery_hold(&q,&info)==-EINVAL);
    info.si_code=SI_KERNEL; assert(md_signal_delivery_hold(&q,&info)==-EINVAL);
    assert(md_signal_delivery_seccomp(&q,&info)==-EINVAL);
    info=original(SIGSYS); info.si_code=SYS_SECCOMP;
    assert(md_signal_delivery_hold(&q,&info)==-EINVAL);
    assert(!md_signal_delivery_seccomp(&q,&info));
    md_signal_delivery_clear(&q);
    info=original(SIGALRM); info.si_code=SI_KERNEL;
    assert(!md_signal_delivery_hold(&q,&info));
    info=original(SIGCHLD); info.si_code=CLD_EXITED; info.si_status=42;
    assert(!md_signal_delivery_hold(&q,&info));
    info=original(SIGUSR1); assert(!md_signal_delivery_hold(&q,&info));
    md_signal_delivery_clear(&q); assert(!q.first && !q.last && !q.active);
    puts("PASS signal delivery: exact kernel info, coalescing, sigwait/signalfd, no guest execution, exhausted queue, realtime FIFO, owner transfer, repeated custody and native mask changes");
}
