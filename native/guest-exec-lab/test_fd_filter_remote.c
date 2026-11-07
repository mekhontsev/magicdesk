#define _GNU_SOURCE
#include "fd_filter_program.h"
#include "task_filter.h"
#include "task_quiesce.h"
#include "interception.h"
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char remote_call[],remote_return[];
extern void remote_pause(void);
extern char remote_paused[];
extern long remote_signal_pause(pid_t,int,unsigned long *);
extern char remote_signal_paused[];
#define STRINGIFY_(n) #n
#define STRINGIFY(n) STRINGIFY_(n)
__asm__(".text\n.global remote_call\nremote_call:\nsvc #0\n"
    ".global remote_return\nremote_return:\nbrk #0\n"
    ".global remote_pause\nremote_pause:\n.global remote_paused\nremote_paused:\nbrk #0\nret\n"
    ".global remote_signal_pause\nremote_signal_pause:\nmov x7,#0x4321\nmov x8,#" STRINGIFY(SYS_kill) "\nsvc #0\n"
    ".global remote_signal_paused\nremote_signal_paused:\nstr x7,[x2]\nret\n");
struct shared {
    struct md_task_filter_image image;
    int waiting,completed,first_error;
    pid_t sibling;
    int signal_epoll;
    struct epoll_event signal_event;
    char read_byte;
    struct pollfd poll;
    struct timespec sleep;
};
static struct user_pt_regs get(pid_t pid) {
    struct user_pt_regs r; struct iovec io={&r,sizeof(r)};
    assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)); return r;
}
static int stop(pid_t pid) {
    /* EVENT_WAIT: exact tracee stop/exit; fixture alarm bounds failure. */
    int status; pid_t got;
    do { got=waitpid(pid,&status,__WALL); } while (got<0 && errno==EINTR);
    assert(got==pid); return status;
}
static void unexpected_trap(int number) { (void)number; _exit(92); }
static void child(int start,int reader,int ep,struct shared *shared,int denied,int reading,int protected) {
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    char byte; assert(read(start,&byte,1)==1);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct sigaction trap={.sa_handler=unexpected_trap},observed;
    sigemptyset(&trap.sa_mask); assert(!sigaction(SIGTRAP,&trap,NULL));
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGUSR1); sigaddset(&mask,SIGTRAP);
    assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpgid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOKEY),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,0,1),
        BPF_STMT(BPF_RET|BPF_K,denied>0 ? SECCOMP_RET_ERRNO|EPERM : SECCOMP_RET_TRACE|MD_INTERCEPT_TASK),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    shared->waiting=1;
    struct epoll_event event={0};
    int n=reading ? (int)read(reader,&byte,1) : epoll_wait(ep,&event,1,-1);
    shared->first_error=n<0 ? errno : 0;
    if (!reading) {
        if (n<0 && errno==EINTR) n=epoll_wait(ep,&event,1,-1);
        assert(n==1 && event.data.u64==123);
        assert(read(reader,&byte,1)==1);
    }
    assert(n==1 && byte=='q');
    assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGUSR1)==1
        && sigismember(&mask,SIGUSR2)==0 && sigismember(&mask,SIGTRAP)==1);
    assert(!sigaction(SIGTRAP,NULL,&observed) && observed.sa_handler==unexpected_trap);
    assert(syscall(SYS_getpgid,0)==-1 && errno==ENOKEY);
    shared->completed=1; _exit(0);
}
static long trace(int request,pid_t pid,void *address,void *data) {
    return ptrace(request,pid,address,data);
}
static void install_at(pid_t pid,int fd,struct shared *shared,const struct user_pt_regs *saved,
        long expected,enum md_task_call_boundary boundary,int retry_thread) {
    struct md_fd_filter_gates gates={.raw=(uintptr_t)remote_return};
    struct md_task_filter call={0};
    assert(!md_task_filter_begin(&call,trace,pid,boundary,saved,
        (uintptr_t)remote_call,(uintptr_t)remote_return,&shared->image,(uintptr_t)&shared->image,
        MD_FD_SELECTOR_PROC,fd,&gates));
    int ready;
    do { ready=md_task_filter_event(&call,stop(pid)); } while (!ready);
    if (retry_thread) {
        assert(ready==1 && call.task.result==-EPERM && !md_task_filter_retry_thread(&call));
        do { ready=md_task_filter_event(&call,stop(pid)); } while (!ready);
    }
    assert(ready==1 && call.task.result==expected);
    struct ptrace_syscall_info info={0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0 && info.op==(unsigned)boundary);
    if (boundary==MD_TASK_CALL_EXIT) assert(info.exit.rval==(long)saved->regs[0]);
    if (boundary==MD_TASK_CALL_SECCOMP)
        assert(call.task.cookie==(!expected && (int)saved->regs[0]==fd ? MD_INTERCEPT_PROC : 123));
}
static void install(pid_t pid,int fd,struct shared *shared,const struct user_pt_regs *saved,long expected) {
    install_at(pid,fd,shared,saved,expected,MD_TASK_CALL_USER,0);
}
static int run(int denied,int reading,int protected) {
    struct shared *shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe2(start,O_CLOEXEC) && !pipe2(data,O_CLOEXEC));
    int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=123};
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,data[0],&event));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) child(start[0],data[0],ep,shared,denied,reading,protected);
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(!ptrace(PTRACE_INTERRUPT,pid,0,0));
    assert(WIFSTOPPED(stop(pid)));
    assert(write(start[1],"s",1)==1);
    /* Observe the intended wait's entry rather than a timed settling delay. */
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
        int status=stop(pid); assert(WIFSTOPPED(status));
        struct user_pt_regs r=get(pid);
        if (shared->waiting && r.regs[8]==(reading ? SYS_read : SYS_epoll_pwait)
                && (long)r.regs[0]==(reading ? data[0] : ep)) break;
    }
    assert(!ptrace(PTRACE_CONT,pid,0,0));
    struct md_task_quiesce *barrier=md_task_quiesce_new(trace); assert(barrier);
    assert(!md_task_quiesce_add(barrier,pid,0,PTRACE_CONT) && !md_task_quiesce_stop(barrier));
    int status=stop(pid); assert(WIFSTOPPED(status));
    assert(!md_task_quiesce_event(barrier,pid,status,NULL) && md_task_quiesce_ready(barrier));
    struct md_task_quiesce_stop boundary;
    assert(!md_task_quiesce_borrow(barrier,pid,&boundary) && boundary.kind==MD_TASK_QUIESCE_SYNTHETIC);
    struct user_pt_regs saved=get(pid);
    assert((unsigned)status>>16==PTRACE_EVENT_STOP);
    if (denied>=0) install(pid,data[0],shared,&saved,denied ? -EPERM : 0);
    assert(write(data[1],"q",1)==1);
    assert(!md_task_quiesce_return(barrier,pid) && !md_task_quiesce_release(barrier));
    assert(!md_task_quiesce_take(barrier,&status,NULL));
    md_task_quiesce_destroy(barrier);
    unsigned intercepted=0;
    for (;;) {
        status=stop(pid);
        if (WIFEXITED(status)) break;
        assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
        unsigned long cookie; assert(!ptrace(PTRACE_GETEVENTMSG,pid,0,&cookie));
        struct user_pt_regs r=get(pid);
        assert(cookie==MD_INTERCEPT_PROC && r.regs[8]==SYS_read && (int)r.regs[0]==data[0]);
        ++intercepted;
        assert(!ptrace(PTRACE_CONT,pid,0,0));
    }
    assert(!WEXITSTATUS(status) && shared->completed && intercepted==(denied==0 ? 1u : 0u));
    int first_error=shared->first_error;
    close(ep); close(start[0]); close(start[1]); close(data[0]); close(data[1]);
    assert(!munmap(shared,sizeof(*shared)));
    return first_error;
}
struct sibling {
    struct shared *shared;
    int ready,release,reader,divergent;
};
static void *sibling(void *context) {
    struct sibling *s=context;
    if (s->divergent) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog p={1,code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&p));
    }
    s->shared->sibling=(pid_t)syscall(SYS_gettid);
    assert(write(s->ready,"r",1)==1);
    char byte;
    /* EVENT_WAIT: the owner returns from remote admission before releasing
     * this sibling; fixture alarm bounds a failed admission continuation. */
    assert(read(s->release,&byte,1)==1 && byte=='r');
    assert(read(s->reader,&byte,1)==1 && byte=='q');
    return NULL;
}
static void group(int divergent,int protected) {
    struct shared *shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);
    int start[2],data[2],ready[2],release[2];
    assert(!pipe2(start,O_CLOEXEC) && !pipe2(data,O_CLOEXEC)
        && !pipe2(ready,O_CLOEXEC) && !pipe2(release,O_CLOEXEC));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        struct sibling s={shared,ready[1],release[0],data[0],divergent};
        pthread_t thread; assert(!pthread_create(&thread,NULL,sibling,&s));
        assert(read(ready[0],&byte,1)==1 && byte=='r');
        remote_pause();
        assert(write(release[1],"r",1)==1);
        assert(read(data[0],&byte,1)==1 && byte=='q');
        assert(!pthread_join(thread,NULL));
        shared->completed=1; _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACECLONE|PTRACE_O_TRACESECCOMP|PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1);
    pid_t other=0;
    for (;;) {
        /* EVENT_WAIT: clone, newborn or exact owner pause. No sampling delay. */
        int status; pid_t got=waitpid(-1,&status,__WALL); assert(got>0 && WIFSTOPPED(status));
        unsigned event=(unsigned)status>>16;
        if (got==pid && !event && WSTOPSIG(status)==SIGTRAP) {
            struct user_pt_regs r=get(pid); assert(r.pc==(uintptr_t)remote_paused);
            break;
        }
        if (event==PTRACE_EVENT_CLONE) {
            unsigned long tid; assert(!ptrace(PTRACE_GETEVENTMSG,got,0,&tid));
            assert(got==pid && !other); other=(pid_t)tid;
        } else assert(event==PTRACE_EVENT_STOP);
        assert(!ptrace(PTRACE_CONT,got,0,0));
    }
    assert(other>0 && shared->sibling==other);
    struct user_pt_regs saved=get(pid); saved.pc+=4;
    install(pid,data[0],shared,&saved,divergent ? other : 0);
    assert(write(data[1],"qq",2)==2);
    assert(!ptrace(PTRACE_CONT,pid,0,0));
    unsigned intercepted=0,exited=0;
    while (exited<2) {
        int status; pid_t got=waitpid(-1,&status,__WALL);
        assert(got==pid || got==other);
        if (WIFEXITED(status)) { assert(!WEXITSTATUS(status)); ++exited; continue; }
        assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
        struct user_pt_regs r=get(got); unsigned long cookie;
        assert(!ptrace(PTRACE_GETEVENTMSG,got,0,&cookie));
        assert(cookie==MD_INTERCEPT_PROC && r.regs[8]==SYS_read && (int)r.regs[0]==data[0]);
        ++intercepted; assert(!ptrace(PTRACE_CONT,got,0,0));
    }
    assert(shared->completed && intercepted==(divergent ? 0u : 2u));
    close(start[0]); close(start[1]); close(data[0]); close(data[1]);
    close(ready[0]); close(ready[1]); close(release[0]); close(release[1]);
    assert(!munmap(shared,sizeof(*shared)));
}
static void syscall_page_instruction(uint32_t *code,int replaced) {
    code[1]=replaced ? 0xd4200000 : 0xd4000001;
    __builtin___clear_cache((char *)code,(char *)(code+3));
}
static uint32_t *syscall_page(unsigned nr) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uint32_t *code_page=mmap(NULL,page,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(code_page!=MAP_FAILED);
    code_page[0]=0xd2800008|(nr<<5); code_page[2]=0xd65f03c0;
    syscall_page_instruction(code_page,0); return code_page;
}
static void pending_call(int boundary,int denied,int protected,int cancelled) {
    struct shared *shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);
    uint32_t *code_page=syscall_page(SYS_write);
    uintptr_t remote_written=(uintptr_t)(code_page+2);
    int start[2],data[2]; assert(!pipe2(start,O_CLOEXEC) && !pipe2(data,O_CLOEXEC|O_NONBLOCK));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=SIG_IGN};
        assert(!sigaction(SIGTRAP,&action,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGTRAP);
        assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_write,0,7),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(uintptr_t)remote_written,1,0),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uintptr_t)remote_written>>32,1,0),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|123),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,0,1),
            BPF_STMT(BPF_RET|BPF_K,denied ? SECCOMP_RET_ERRNO|EPERM : SECCOMP_RET_TRACE|MD_INTERCEPT_TASK),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog p={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&p));
        shared->waiting=1;
        assert(((long (*)(int,const void *,size_t))code_page)(data[1],"q",1)==1);
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGTRAP));
        shared->completed=1; _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(!ptrace(PTRACE_INTERRUPT,pid,0,0)); assert(WIFSTOPPED(stop(pid)));
    assert(write(start[1],"s",1)==1);
    struct user_pt_regs saved;
    int original_status;
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
        int status=stop(pid); assert(WIFSTOPPED(status));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0);
        saved=get(pid);
        if (shared->waiting && saved.regs[8]==SYS_write && info.op==boundary) { original_status=status; break; }
    }
    if (cancelled) {
        assert(boundary==MD_TASK_CALL_ENTRY || boundary==MD_TASK_CALL_SECCOMP);
        /* Emulate a successful write without its side effect, then lend the
         * same stop to another owner. x8 deliberately remains SYS_write. */
        saved.regs[0]=1;
        struct iovec io={&saved,sizeof(saved)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
        int number=-1;
        io=(struct iovec){&number,sizeof(number)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io));
    }
    struct md_task_quiesce *barrier=md_task_quiesce_new(trace); assert(barrier);
    assert(!md_task_quiesce_add(barrier,pid,1,0));
    struct rusage usage={.ru_maxrss=123};
    assert(!md_task_quiesce_event(barrier,pid,original_status,&usage));
    struct md_task_quiesce_stop borrowed;
    assert(!md_task_quiesce_borrow(barrier,pid,&borrowed)
        && borrowed.kind==MD_TASK_QUIESCE_EVENT && borrowed.status==original_status);
    /* The executing syscall is already stopped. Its source page no longer
     * contains SVC, as after lazy publication; replay must not fetch it again. */
    syscall_page_instruction(code_page,1);
    install_at(pid,data[1],shared,&saved,denied==1 ? -EPERM : 0,boundary,denied==2);
    if (cancelled) {
        int number=0;
        struct iovec io={&number,sizeof(number)};
        assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_ARM_SYSTEM_CALL,&io));
        assert(number==-1);
    }
    syscall_page_instruction(code_page,0);
    assert(!md_task_quiesce_return(barrier,pid) && !md_task_quiesce_release(barrier));
    int replay;
    assert(md_task_quiesce_take(barrier,&replay,&usage)==pid && replay==original_status && usage.ru_maxrss==123);
    assert(!md_task_quiesce_take(barrier,&replay,NULL)); md_task_quiesce_destroy(barrier);
    if (cancelled && boundary==MD_TASK_CALL_ENTRY) {
        /* The borrower restored the owner's skipped entry. Its owner must
         * drain a harmless admitted syscall, not submit -1 to Android seccomp. */
        assert(!md_task_call_cancel_entry(trace,pid,(uintptr_t)remote_return));
        for (;;) {
            assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
            int status=stop(pid); assert(WIFSTOPPED(status));
            struct ptrace_syscall_info info={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0);
            if (info.op==PTRACE_SYSCALL_INFO_EXIT) break;
            assert(info.op==PTRACE_SYSCALL_INFO_SECCOMP);
        }
        struct iovec io={&saved,sizeof(saved)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
    }
    for (;;) {
        assert(!ptrace(PTRACE_CONT,pid,0,0));
        int status=stop(pid);
        if (WIFEXITED(status)) { assert(!WEXITSTATUS(status)); break; }
        assert(boundary==PTRACE_SYSCALL_INFO_ENTRY && (unsigned)status>>16==PTRACE_EVENT_SECCOMP);
    }
    char byte[2]; assert(shared->completed);
    if (!cancelled) assert(read(data[0],byte,2)==1 && byte[0]=='q');
    assert(read(data[0],byte,2)==-1 && errno==EAGAIN);
    close(start[0]); close(start[1]); close(data[0]); close(data[1]);
    assert(!munmap(code_page,(size_t)sysconf(_SC_PAGESIZE)));
    assert(!munmap(shared,sizeof(*shared)));
}
struct call_events { int foreign_interrupts,group_stops,writer,interrupted; long number; };
static int call_event(struct md_task_call *call,struct call_events *events) {
    int status=stop(call->actor);
    if (events->writer>=0 && !events->interrupted && WIFSTOPPED(status)
            && !((unsigned)status>>16) && WSTOPSIG(status)==(SIGTRAP|0x80)) {
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,call->actor,sizeof(info),&info)>0);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY && info.entry.nr==(unsigned long)events->number) {
            events->interrupted=1;
            assert(!kill(call->actor,SIGSTOP));
        }
    }
    if (WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP) {
        if ((unsigned)status>>16==PTRACE_EVENT_STOP) {
            ++events->group_stops;
            assert(!ptrace(PTRACE_LISTEN,call->actor,0,0));
            if (events->writer>=0) assert(write(events->writer,"q",1)==1);
            assert(!kill(call->actor,SIGCONT)); return 0;
        }
        assert(!((unsigned)status>>16));
        return md_task_call_resume(call,SIGSTOP);
    }
    if (WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP
            && WSTOPSIG(status)==SIGTRAP && !md_task_call_expects_interrupt(call)) {
        ++events->foreign_interrupts;
        return md_task_call_resume(call,0);
    }
    return md_task_call_event(call,status);
}
static void task_context(int protected,int interrupted,int boundary) {
    struct shared *shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);
    int start[2],data[2]; assert(!pipe2(start,O_CLOEXEC) && !pipe2(data,O_CLOEXEC));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGUSR1);
        assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        int signal=signalfd(-1,&mask,SFD_NONBLOCK|SFD_CLOEXEC); assert(signal>=0);
        int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
        struct epoll_event event={.events=EPOLLIN,.data.u64=0xfdeede};
        assert(!epoll_ctl(ep,EPOLL_CTL_ADD,signal,&event));
        shared->signal_epoll=ep;
        assert(!raise(SIGUSR1));
        struct sigaction action={.sa_handler=SIG_IGN};
        assert(!sigaction(SIGTRAP,&action,NULL));
        sigaddset(&mask,SIGTRAP);
        assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        unsigned long x7;
        assert(!remote_signal_pause(getpid(),SIGSTOP,&x7) && x7==0x4321);
        assert(!sigaction(SIGTRAP,NULL,&action) && action.sa_handler==SIG_IGN);
        struct signalfd_siginfo info;
        assert(read(signal,&info,sizeof(info))==sizeof(info) && info.ssi_signo==SIGUSR1);
        assert(!sigprocmask(SIG_SETMASK,NULL,&mask) && sigismember(&mask,SIGUSR1)==1
            && sigismember(&mask,SIGUSR2)==0 && sigismember(&mask,SIGTRAP)==1);
        shared->completed=1; _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1);
    int status=stop(pid); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    struct user_pt_regs saved=get(pid);
    assert(saved.pc==(uintptr_t)remote_signal_paused && saved.regs[7]==0x4321);
    struct user_pt_regs original=saved;
    if (boundary!=MD_TASK_CALL_USER) {
        saved.pc=(uintptr_t)remote_call; saved.regs[8]=SYS_getpid;
        struct iovec io={&saved,sizeof(saved)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
        assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
        assert(WSTOPSIG(stop(pid))==(SIGTRAP|0x80));
        saved=get(pid);
    }
    struct md_task_call call={0};
    struct md_task_call_arguments args={.number=SYS_epoll_pwait,.value=
        {(unsigned long)shared->signal_epoll,(uintptr_t)&shared->signal_event,1,0,0,8}};
    if (interrupted==3) args=(struct md_task_call_arguments){.number=SYS_read,.value=
        {(unsigned long)data[0],(uintptr_t)&shared->read_byte,1}};
    if (interrupted==4) {
        shared->poll=(struct pollfd){.fd=data[0],.events=POLLIN};
        args=(struct md_task_call_arguments){.number=SYS_ppoll,.value={(uintptr_t)&shared->poll,1,0,0,8}};
    }
    if (interrupted==5) {
        shared->sleep=(struct timespec){.tv_nsec=1000000};
        args=(struct md_task_call_arguments){.number=SYS_clock_nanosleep,.value=
            {CLOCK_MONOTONIC,0,(uintptr_t)&shared->sleep,0}};
    }
    if (interrupted==1) assert(!ptrace(PTRACE_INTERRUPT,pid,0,0));
    if (interrupted==2) assert(!kill(pid,SIGSTOP));
    if (boundary!=MD_TASK_CALL_USER && interrupted>=3) assert(!kill(pid,SIGSTOP));
    assert(!md_task_call_begin(&call,trace,pid,boundary,&saved,
        (uintptr_t)remote_call,(uintptr_t)remote_return,&args));
    int ready;
    struct call_events events={.writer=interrupted>=3 ? data[1] : -1,.number=args.number,
        .interrupted=boundary!=MD_TASK_CALL_USER && interrupted>=3};
    do { ready=call_event(&call,&events); } while (!ready);
    if (ready!=1) fprintf(stderr,"task context interrupted=%d stage=%u error=%d result=%ld\n",
        interrupted,call.stage,ready,call.result);
    assert(ready==1 && call.result==(interrupted==5 ? 0 : 1));
    if (interrupted>=3) assert(events.interrupted);
    if (interrupted==5) assert(call.active_number==SYS_restart_syscall);
    else if (interrupted==4) assert(shared->poll.revents==POLLIN);
    else if (interrupted==3) assert(shared->read_byte=='q');
    else assert(shared->signal_event.events==EPOLLIN && shared->signal_event.data.u64==0xfdeede);
    args=(struct md_task_call_arguments){.number=SYS_getpid};
    assert(!md_task_call_again(&call,&args));
    do { ready=call_event(&call,&events); } while (!ready);
    assert(ready==1 && call.result==pid);
    assert(!interrupted || events.foreign_interrupts);
    assert(events.group_stops==(interrupted>=2));
    struct user_pt_regs restored=get(pid); assert(!memcmp(&restored,&saved,sizeof(saved)));
    if (boundary!=MD_TASK_CALL_USER) {
        assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
        assert(WSTOPSIG(stop(pid))==(SIGTRAP|0x80));
        assert(!ptrace(PTRACE_INTERRUPT,pid,0,0));
        assert(!ptrace(PTRACE_CONT,pid,0,0));
        assert((unsigned)stop(pid)>>16==PTRACE_EVENT_STOP);
        struct iovec io={&original,sizeof(original)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&io));
    }
    assert(!ptrace(PTRACE_CONT,pid,0,0));
    status=stop(pid);
    if (interrupted>=2) {
        assert(WIFSTOPPED(status) && !((unsigned)status>>16) && WSTOPSIG(status)==SIGCONT);
        siginfo_t info;
        assert(!ptrace(PTRACE_GETSIGINFO,pid,0,&info) && info.si_signo==SIGCONT
            && info.si_code==SI_USER && info.si_pid==getpid());
        assert(!ptrace(PTRACE_CONT,pid,0,SIGCONT));
        status=stop(pid);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status) && shared->completed);
    close(start[0]); close(start[1]); close(data[0]); close(data[1]);
    assert(!munmap(shared,sizeof(*shared)));
}
static void caught_signal(int signal) { assert(signal==SIGUSR2); }
static void restart_exit(int protected,int interrupted) {
    struct shared *shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);
    uint32_t *code_page=syscall_page(SYS_read);
    int start[2],data[2]; assert(!pipe2(start,O_CLOEXEC) && !pipe2(data,O_CLOEXEC));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=caught_signal,.sa_flags=SA_RESTART};
        sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR2,&action,NULL));
        shared->waiting=1;
        assert(((long (*)(int,void *,size_t))code_page)(data[0],&byte,1)==1 && byte=='q');
        shared->completed=1; _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(!ptrace(PTRACE_INTERRUPT,pid,0,0)); assert(WIFSTOPPED(stop(pid)));
    assert(write(start[1],"s",1)==1);
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,pid,0,0)); assert(WIFSTOPPED(stop(pid)));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0);
        if (shared->waiting && info.op==PTRACE_SYSCALL_INFO_ENTRY
                && info.entry.nr==SYS_read && (int)info.entry.args[0]==data[0]) break;
    }
    if (interrupted) assert(!ptrace(PTRACE_INTERRUPT,pid,0,0));
    else assert(!kill(pid,SIGUSR2));
    assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
    int status=stop(pid); assert(WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
    struct user_pt_regs saved=get(pid);
    assert((long)saved.regs[0]==-512);
    assert(saved.pc==(uintptr_t)(code_page+2));
    syscall_page_instruction(code_page,1);
    uint64_t before,after;
    assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(before),&before));
    struct md_task_call call={0};
    struct md_task_call_arguments args={.number=SYS_getpid};
    assert(!md_task_call_begin(&call,trace,pid,MD_TASK_CALL_EXIT,&saved,
        (uintptr_t)remote_call,(uintptr_t)remote_return,&args));
    int done=0;
    while (!done) { done=md_task_call_event(&call,stop(pid)); assert(done>=0); }
    assert(call.result==pid);
    assert(!md_task_call_again(&call,&args));
    do { done=md_task_call_event(&call,stop(pid)); assert(done>=0); } while (!done);
    assert(call.result==pid);
    struct user_pt_regs unchanged=get(pid); assert(!memcmp(&saved,&unchanged,sizeof(saved)));
    assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(after),&after) && before==after);
    syscall_page_instruction(code_page,0);
    if (interrupted) assert(write(data[1],"q",1)==1);
    assert(!ptrace(PTRACE_CONT,pid,0,0));
    status=stop(pid);
    if ((unsigned)status>>16==PTRACE_EVENT_STOP) {
        assert(WSTOPSIG(status)==SIGTRAP && !ptrace(PTRACE_CONT,pid,0,0));
        status=stop(pid);
    }
    if (!interrupted) {
        assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGUSR2);
        assert(write(data[1],"q",1)==1);
        assert(!ptrace(PTRACE_CONT,pid,0,SIGUSR2));
        status=stop(pid);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status) && shared->completed);
    close(start[0]); close(start[1]); close(data[0]); close(data[1]);
    assert(!munmap(code_page,(size_t)sysconf(_SC_PAGESIZE)));
    assert(!munmap(shared,sizeof(*shared)));
}
static void killed_signal(int signal) { (void)signal; _exit(94); }
static void killed_injection(int protected,int boundary,int interruptible,int blocked,int caught) {
    int start[2]; assert(!pipe(start));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct sigaction action={.sa_handler=caught ? killed_signal : SIG_IGN};
        assert(!sigaction(SIGSYS,&action,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGSYS);
        assert(!sigprocmask(blocked ? SIG_BLOCK : SIG_UNBLOCK,&mask,NULL));
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|123),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(!syscall(SYS_tkill,syscall(SYS_gettid),SIGSTOP));
        assert(syscall(SYS_getpid)>0); _exit(93);
    }
    /* Deliberately omit TRACEEXIT: death must not be mistaken for a completed
     * injected call even when the kernel first publishes a syscall-exit stop. */
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1);
    int status=stop(pid); assert(WSTOPSIG(status)==SIGSTOP);
    if (boundary) for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,pid,0,0)); status=stop(pid);
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0);
        if (info.op==(unsigned)boundary) break;
    }
    struct md_task_call call={0}; struct user_pt_regs saved=get(pid);
    struct md_task_call_arguments args={.number=SYS_getppid,
        .flags=interruptible ? MD_TASK_CALL_INTERRUPTIBLE : 0};
    assert(!md_task_call_begin(&call,trace,pid,boundary,&saved,
        (uintptr_t)remote_call,(uintptr_t)remote_return,&args));
    for (unsigned n=0;;++n) {
        assert(n<20); status=stop(pid);
        if (WIFSIGNALED(status)) { assert(WTERMSIG(status)==SIGSYS); break; }
        assert(WIFSTOPPED(status));
        int result=md_task_call_event(&call,status);
        if (result) fprintf(stderr,"killed injection boundary=%d interruptible=%d blocked=%d caught=%d stage=%u status=%#x result=%d\n",
            boundary,interruptible,blocked,caught,call.stage,status,result);
        assert(!result);
    }
    close(start[0]); close(start[1]);
}
static volatile sig_atomic_t notification_signal_seen;
static void notification_signal(int signal) { notification_signal_seen=signal; }
static void notification_injection(int protected,int interrupt,int retire) {
    int start[2],sockets[2]; assert(!pipe(start));
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        char byte; assert(read(start[0],&byte,1)==1);
        pid_t self=getpid();
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        if (interrupt) {
            struct sigaction action={.sa_handler=notification_signal};
            sigset_t set; sigemptyset(&set); sigaddset(&set,interrupt);
            assert(!sigaction(interrupt,&action,NULL) && !sigprocmask(SIG_UNBLOCK,&set,NULL));
        }
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getpid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|123),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
        assert(fd>=0);
        struct iovec io={&byte,1};
        union { struct cmsghdr header; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,
            .msg_control=&control,.msg_controllen=sizeof(control)};
        control.header=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(sizeof(fd))};
        memcpy(CMSG_DATA(&control.header),&fd,sizeof(fd));
        assert(sendmsg(sockets[1],&message,0)==1 && !close(fd));
        assert(syscall(SYS_getpid,0x12345678)==(retire ? 713 : self) && notification_signal_seen==interrupt); _exit(0);
    }
    assert(!ptrace(PTRACE_SEIZE,pid,0,(void *)(PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP|PTRACE_O_EXITKILL)));
    assert(write(start[1],"s",1)==1);
    char byte; struct iovec io={&byte,1};
    union { struct cmsghdr header; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=&control,.msg_controllen=sizeof(control)};
    assert(recvmsg(sockets[0],&message,MSG_CMSG_CLOEXEC)==1
        && !(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
    assert(control.header.cmsg_level==SOL_SOCKET && control.header.cmsg_type==SCM_RIGHTS
        && control.header.cmsg_len==CMSG_LEN(sizeof(int)));
    int listener; memcpy(&listener,CMSG_DATA(&control.header),sizeof(listener));
    int status=stop(pid); assert((unsigned)status>>16==PTRACE_EVENT_SECCOMP);
    struct user_pt_regs saved=get(pid);
    struct md_task_call call={0}; struct md_task_call_arguments args={.number=SYS_getppid,
        .value={0x87654321,1,2,3,4,5},.flags=interrupt ? MD_TASK_CALL_INTERRUPTIBLE : 0};
    assert(!md_task_call_begin(&call,trace,pid,MD_TASK_CALL_SECCOMP,&saved,
        (uintptr_t)remote_call,(uintptr_t)remote_return,&args));
    struct seccomp_notif request={0};
    /* EVENT_WAIT: replacement admission, bounded by the fixture's alarm. */
    assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_RECV,&request));
    assert(request.pid==(unsigned)pid && request.data.nr==SYS_getppid
        && request.data.instruction_pointer==(uintptr_t)remote_return);
    assert(request.data.args[0]==0x12345678 && md_task_call_notification(&call,&request));
    for (unsigned i=0;i<6;++i) {
        request.data.args[i]^=1; assert(!md_task_call_notification(&call,&request)); request.data.args[i]^=1;
    }
    if (interrupt) assert(!kill(pid,interrupt));
    else {
        struct seccomp_notif_resp response={.id=request.id,.error=-ENOKEY};
        assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&response));
    }
    if (retire) assert(!md_task_call_retire_entry(&call));
    int ready;
    do { ready=md_task_call_event(&call,stop(pid)); } while (!ready);
    assert(ready==1 && call.result==(interrupt ? -512 : -ENOKEY));
    assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&request.id)==-1 && errno==ENOENT);
    struct user_pt_regs restored=get(pid); assert(!memcmp(&restored,&saved,sizeof(saved)));
    uint64_t mask;
    assert(!ptrace(PTRACE_GETSIGMASK,pid,(void *)sizeof(mask),&mask) && mask==call.mask);
    if (retire) {
        assert(call.boundary==MD_TASK_CALL_EXIT);
        restored.regs[0]=713;
        struct iovec registers={&restored,sizeof(restored)};
        assert(!ptrace(PTRACE_SETREGSET,pid,(void *)NT_PRSTATUS,&registers));
    }
    assert(!ptrace(PTRACE_CONT,pid,0,0)); status=stop(pid);
    if (interrupt) {
        assert(WIFSTOPPED(status) && WSTOPSIG(status)==interrupt);
        assert(!ptrace(PTRACE_CONT,pid,0,(void *)(intptr_t)interrupt)); status=stop(pid);
    }
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    close(listener); close(start[0]); close(start[1]); close(sockets[0]); close(sockets[1]);
}
int main(void) {
    alarm(30);
    for (int protected=0;protected<2;++protected) {
        notification_injection(protected,0,0);
        notification_injection(protected,0,1);
        notification_injection(protected,SIGUSR1,0);
        notification_injection(protected,SIGSYS,0);
    }
    puts("PASS task-context notification: seccomp replacement admission and exact original-call restoration");
    for (int protected=0;protected<2;++protected)
        for (int boundary=MD_TASK_CALL_USER;boundary<=MD_TASK_CALL_SECCOMP;++boundary)
            for (int interruptible=0;interruptible<2;++interruptible)
                for (int blocked=0;blocked<2;++blocked) for (int caught=0;caught<2;++caught)
                    killed_injection(protected,boundary,interruptible,blocked,caught);
    puts("PASS killed task call: all boundaries, interruptible/shielded, blocked/unblocked and ignored/caught SIGSYS, protected tasks, no TRACEEXIT requirement");
    for (int protected=0;protected<2;++protected) for (int reading=0;reading<2;++reading) {
        int baseline=run(-1,reading,protected);
        /* Linux restarts pipe reads, but epoll returns EINTR after the native
         * ptrace interrupt itself. Admission must preserve both outcomes. */
        assert(baseline==(reading ? 0 : EINTR));
        assert(run(0,reading,protected)==baseline && run(1,reading,protected)==baseline);
    }
    for (int protected=0;protected<2;++protected) for (int divergent=0;divergent<2;++divergent)
        group(divergent,protected);
    for (int protected=0;protected<2;++protected) for (int denied=0;denied<3;++denied)
        for (int boundary=PTRACE_SYSCALL_INFO_ENTRY;boundary<=PTRACE_SYSCALL_INFO_SECCOMP;++boundary)
            pending_call(boundary,denied,protected,0);
    for (int protected=0;protected<2;++protected) for (int denied=0;denied<3;++denied) {
        pending_call(MD_TASK_CALL_ENTRY,denied,protected,1);
        pending_call(MD_TASK_CALL_SECCOMP,denied,protected,1);
    }
    for (int protected=0;protected<2;++protected)
        for (int interrupted=0;interrupted<2;++interrupted) restart_exit(protected,interrupted);
    for (int protected=0;protected<2;++protected)
        for (int interrupted=0;interrupted<6;++interrupted) task_context(protected,interrupted,MD_TASK_CALL_USER);
    for (int protected=0;protected<2;++protected)
        for (int interrupted=3;interrupted<6;++interrupted) task_context(protected,interrupted,MD_TASK_CALL_ENTRY);
    puts("PASS remote FD filter: protected tasks, native interrupt/restart, signal masks and seccomp policy");
    puts("PASS group FD filter: TSYNC reaches every sibling; incompatible filter leaves both unchanged");
    puts("PASS FD filter continuation: replaced source, original seccomp PC and exact-once write at entry/seccomp/exit");
    puts("PASS cancelled entry: task borrowing preserves skipped syscall and emulated result before/after seccomp");
    puts("PASS restart-exit calls: recovered kernel arguments, restored registers/mask and native signal restart");
    puts("PASS task-context call: protected signalfd readiness, repeated syscall, SIG_IGN and exact register/mask restoration");
    puts("PASS task-context job control: STOP/LISTEN/CONT, read/ppoll/timer restart and original SIGCONT siginfo");
    return 0;
}
