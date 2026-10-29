#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/close_range.h>
#include <linux/seccomp.h>
#include <linux/sched.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <sched.h>
#include <sys/eventfd.h>
#include <sys/signalfd.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/mount.h>
#include <time.h>
#include <poll.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <ucontext.h>

static volatile sig_atomic_t limit_traps;
static int trap_socket;
static void limit_trap(int signal,siginfo_t *info,void *context) {
    assert(signal==SIGSYS && info->si_syscall==SYS_prlimit64);
    char byte='x';
    struct iovec part={&byte,1};
    struct msghdr message={.msg_iov=&part,.msg_iovlen=1};
    assert(sendmsg(trap_socket,&message,0)==1);
    ++limit_traps;
    ((ucontext_t *)context)->uc_mcontext.regs[0]=(unsigned long)-EPERM;
}
static void poll_sandbox(int protected) {
    assert(!unshare(CLONE_NEWUTS));
    int fd=open("/proc/sys/kernel/hostname",O_RDONLY|O_CLOEXEC);
    int event=eventfd(1,EFD_CLOEXEC),sockets[2];
    assert(fd>=0 && event>=0 && !socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,sockets));
    int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
    struct epoll_event interest={.events=EPOLLIN,.data.u64=91},result;
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,event,&interest));
    trap_socket=sockets[0];
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    struct sigaction action={.sa_sigaction=limit_trap,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGSYS,&action,NULL));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prlimit64,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clock_gettime,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog policy={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&policy));
    struct timespec zero={0};
    long empty=syscall(SYS_ppoll,NULL,0,&zero,NULL,8);
    if (empty) fprintf(stderr,"poll sandbox empty=%ld errno=%d\n",empty,errno);
    assert(!empty);
    assert(syscall(SYS_ppoll,NULL,65,&zero,NULL,8)==-1 && errno==EINVAL);
    /* A virtual provider elsewhere in the table must not change a native-only
     * selection. Exercise classification/terminal cleanup, not the empty-table shortcut. */
    struct pollfd native={event,POLLIN,0};
    assert(syscall(SYS_ppoll,&native,1,&zero,NULL,8)==1 && (native.revents&POLLIN));
    fd_set set; FD_ZERO(&set); FD_SET(event,&set);
    assert(syscall(SYS_pselect6,event+1,&set,NULL,NULL,&zero,NULL)==1 && FD_ISSET(event,&set));
    /* The early epoll path still uses the caller's kernel wait and validates
     * original pointers/arguments, even with another virtual provider present. */
    assert(syscall(SYS_epoll_pwait,ep,&result,1,0,NULL,8)==1 && result.data.u64==91);
    assert(syscall(SYS_epoll_pwait2,ep,&result,1,&zero,NULL,8)==1 && result.data.u64==91);
    assert(syscall(SYS_epoll_pwait,ep,&result,0,0,NULL,8)==-1 && errno==EINVAL);
    assert(syscall(SYS_epoll_pwait,-1,&result,1,0,NULL,8)==-1 && errno==EBADF);
    assert(syscall(SYS_epoll_pwait,ep,NULL,1,0,NULL,8)==-1 && errno==EFAULT);
    assert(syscall(SYS_epoll_pwait2,ep,&result,1,(void *)1,NULL,8)==-1 && errno==EFAULT);
    assert(!limit_traps);
    assert(!sethostname("poll-sandbox",12));
    struct pollfd polls[]={{fd,0,0},{event,POLLIN,0}};
    assert(syscall(SYS_ppoll,polls,2,&zero,NULL,8)==2);
    assert((polls[0].revents&POLLERR) && (polls[1].revents&POLLIN) && !limit_traps);
    uint64_t one;
    assert(read(event,&one,sizeof(one))==sizeof(one));
    struct timespec timeout={0,10000000};
    assert(!syscall(SYS_ppoll,polls,2,&timeout,NULL,8));
    assert(!timeout.tv_sec && !timeout.tv_nsec);
    assert(syscall(SYS_prlimit64,0,RLIMIT_NOFILE,NULL,&limit)==-1 && errno==EPERM);
    assert(limit_traps==1);
    char byte; assert(read(sockets[1],&byte,1)==1 && byte=='x');
    close(ep); close(fd); close(event); close(sockets[0]); close(sockets[1]);
    puts("PASS poll sandbox: native/virtual readiness, caller nofile limit and application SIGSYS preserved");
}

static void byte_read(int fd) {
    char c;
    ssize_t n;
    do n=read(fd,&c,1); while (n<0 && errno==EINTR);
    if (n!=1) fprintf(stderr,"byte read fd=%d result=%zd errno=%d\n",fd,n,errno);
    assert(n==1);
}
static void byte_write(int fd) { assert(write(fd,"x",1)==1); }
static void concurrent_admission(void) {
    assert(!unshare(CLONE_NEWNS|CLONE_NEWUTS));
    assert(!mount("proc","/proc","proc",0,NULL));
    int source=open("/proc/sys/kernel/hostname",O_RDONLY|O_CLOEXEC);
    int ready[2],go[2];
    assert(source>=0 && !pipe2(ready,O_CLOEXEC) && !pipe2(go,O_CLOEXEC));
    pid_t children[6];
    for (unsigned i=0;i<6;++i) {
        children[i]=fork(); assert(children[i]>=0);
        if (children[i]) continue;
        close(ready[0]); close(go[1]);
        byte_write(ready[1]); byte_read(go[0]);
        for (unsigned round=0;round<64;++round) {
            int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
            struct epoll_event event={.data.u64=42},out;
            int result=epoll_ctl(ep,EPOLL_CTL_ADD,source,&event);
            if (result) fprintf(stderr,"admission child=%u round=%u errno=%d\n",i,round,errno);
            assert(!result);
            assert(!epoll_wait(ep,&out,1,0));
            struct pollfd item={source,0,0};
            assert(!poll(&item,1,0));
            assert(!epoll_ctl(ep,EPOLL_CTL_DEL,source,NULL));
            assert(!close(ep));
        }
        _exit(0);
    }
    close(ready[1]); close(go[0]);
    for (unsigned i=0;i<6;++i) byte_read(ready[0]);
    for (unsigned i=0;i<6;++i) byte_write(go[1]);
    close(ready[0]); close(go[1]);
    for (unsigned i=0;i<6;++i) {
        int status;
        /* EVENT_WAIT: every independent readiness/admission owner completes;
         * the launch deadline fails a cycle between their descriptor turns. */
        assert(waitpid(children[i],&status,0)==children[i] && status==0);
    }
    close(source);
    puts("PASS mixed epoll: concurrent cold admission drains earlier readiness turns");
}
static volatile sig_atomic_t signalled;
static void on_signal(int number) { assert(number==SIGUSR1); signalled=1; }
static void native_ready(void) {
    int ep=epoll_create1(EPOLL_CLOEXEC),fd=eventfd(1,EFD_CLOEXEC);
    assert(ep>=0 && fd>=0);
    struct epoll_event event={.events=EPOLLIN|EPOLLONESHOT,.data.u64=73},out;
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    for (unsigned round=0;round<8;++round) {
        assert(!epoll_ctl(ep,EPOLL_CTL_MOD,fd,&event));
        long result=round&1 ? syscall(SYS_epoll_pwait2,ep,&out,1,NULL,NULL,8)
            : syscall(SYS_epoll_pwait,ep,&out,1,-1,NULL,8);
        assert(result==1 && out.events==EPOLLIN && out.data.u64==73);
        assert(!epoll_wait(ep,&out,1,0));
        struct timespec zero={0};
        assert(!syscall(SYS_epoll_pwait2,ep,&out,1,&zero,NULL,8));
    }
    assert(!epoll_ctl(ep,EPOLL_CTL_MOD,fd,&event));
    struct timespec invalid={.tv_sec=-1};
    assert(syscall(SYS_epoll_pwait2,ep,&out,1,&invalid,NULL,8)==-1 && errno==EINVAL);
    assert(syscall(SYS_epoll_pwait2,ep,&out,1,(void *)1,NULL,8)==-1 && errno==EFAULT);
    assert(syscall(SYS_epoll_pwait,ep,NULL,1,-1,NULL,8)==-1 && errno==EFAULT);
    assert(syscall(SYS_epoll_pwait,ep,&out,0,-1,NULL,8)==-1 && errno==EINVAL);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog policy={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&policy));
    assert(syscall(SYS_epoll_pwait,ep,&out,1,0,NULL,8)==-1 && errno==EACCES);
    assert(syscall(SYS_epoll_pwait,ep,&out,1,-1,NULL,8)==1 && out.data.u64==73);
    close(ep); close(fd);
    puts("PASS mixed epoll: native ready result, one-shot consumption, pointers and argument-sensitive filter");
}
static void native_wait_signals(void) {
    struct sigaction action={.sa_handler=on_signal}; sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGUSR1,&action,NULL));
    sigset_t before,after;
    assert(!sigprocmask(SIG_SETMASK,NULL,&before) && !sigismember(&before,SIGUSR1));
    int ep=epoll_create1(EPOLL_CLOEXEC),fd=eventfd(0,EFD_CLOEXEC);
    assert(ep>=0 && fd>=0);
    struct epoll_event event={.events=EPOLLIN,.data.fd=fd};
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    timer_t timer;
    struct sigevent notification={.sigev_notify=SIGEV_SIGNAL,.sigev_signo=SIGUSR1};
    assert(!timer_create(CLOCK_MONOTONIC,&notification,&timer));
    for (unsigned mode=0;mode<6;++mode) {
        signalled=0;
        struct itimerspec fire={.it_value={0,50000000},.it_interval={0,50000000}};
        assert(!timer_settime(timer,0,&fire,NULL));
        struct timespec limit={1,0};
        struct pollfd poll={fd,POLLIN,0};
        fd_set set; FD_ZERO(&set); FD_SET(fd,&set);
        /* EVENT_WAIT: timer interrupts a native-only wait after selector admission;
         * the one-second syscall deadline must not replace signal delivery. */
        long result=mode==0 ? syscall(SYS_ppoll,&poll,1,&limit,NULL,8)
            : mode==1 ? syscall(SYS_pselect6,fd+1,&set,NULL,NULL,&limit,NULL)
            : mode==2 || mode==4 ? syscall(SYS_epoll_pwait,ep,&event,1,mode==4 ? -1 : 1000,NULL,8)
            : syscall(SYS_epoll_pwait2,ep,&event,1,mode==5 ? NULL : &limit,NULL,8);
        int error=errno;
        struct itimerspec stopped={0};
        assert(!timer_settime(timer,0,&stopped,NULL));
        if (result!=-1 || error!=EINTR || !signalled)
            fprintf(stderr,"native wait mode=%u result=%ld errno=%d signal=%d\n",mode,result,error,signalled);
        assert(result==-1 && error==EINTR && signalled);
        assert(!sigprocmask(SIG_SETMASK,NULL,&after));
        for (int signal=1;signal<NSIG;++signal)
            assert(sigismember(&before,signal)==sigismember(&after,signal));
    }
    assert(!timer_delete(timer)); close(fd); close(ep);
    puts("PASS mixed epoll: native-only poll/select/epoll preserve asynchronous signals");
}
static void signal_wait(int ep) {
    struct sigaction action={.sa_handler=on_signal}; sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGUSR1,&action,NULL));
    sigset_t blocked,saved,after;
    sigemptyset(&blocked); sigaddset(&blocked,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
    for (unsigned round=0;round<16;++round) {
        signalled=0;
        assert(!raise(SIGUSR1));
        struct epoll_event out={0};
        int result=epoll_pwait(ep,&out,1,1000,&saved),error=errno;
        if (result!=-1 || error!=EINTR || !signalled)
            fprintf(stderr,"pending signal wait round=%u result=%d errno=%d signal=%d events=%#x data=%llu\n",
                round,result,error,signalled,out.events,(unsigned long long)out.data.u64);
        assert(result==-1 && error==EINTR && signalled);
        assert(!sigprocmask(SIG_SETMASK,NULL,&after) && sigismember(&after,SIGUSR1)==1);
    }
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL));
    puts("PASS mixed epoll: atomic signal mask, EINTR and restoration");
}
static void interrupted_poll_clone(int fd) {
    sigset_t blocked,saved,after;
    sigemptyset(&blocked); sigaddset(&blocked,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
    signalled=0;
    assert(!raise(SIGUSR1));
    struct pollfd source={fd,0,0};
    struct timespec limit={1,0};
    /* EVENT_WAIT: pending SIGUSR1 interrupts the virtual-source wait. The
     * next adapter must not inherit its retired prepared-buffer ownership. */
    assert(syscall(SYS_ppoll,&source,1,&limit,&saved,8)==-1 && errno==EINTR && signalled);
    assert(!sigprocmask(SIG_SETMASK,NULL,&after) && sigismember(&after,SIGUSR1)==1);
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL));
    struct clone_args args={.exit_signal=SIGCHLD};
    pid_t child=syscall(SYS_clone3,&args,sizeof(args)); assert(child>=0);
    if (!child) _exit(0);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS interrupted poll: signal mask, buffer retirement and following clone3");
}
static void signal_ready(int ep) {
    sigset_t blocked,saved,after;
    sigemptyset(&blocked); sigaddset(&blocked,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
    signalled=0;
    for (unsigned i=0;i<2;++i) {
        assert(!sethostname(i ? "ready-signal-b" : "ready-signal-a",14));
        assert(!raise(SIGUSR1));
        struct epoll_event out={0};
        assert(epoll_pwait(ep,&out,1,1000,&saved)==1 && out.data.u64==42 && (out.events&EPOLLERR));
        assert(!sigprocmask(SIG_SETMASK,NULL,&after) && sigismember(&after,SIGUSR1)==1);
        assert(!epoll_wait(ep,&out,1,0));
    }
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL) && signalled);
    puts("PASS mixed epoll: ready virtual event with pending signal, single consumption and mask restoration");
}
static void signal_descriptor(int ep) {
    sigset_t blocked,saved;
    sigemptyset(&blocked); sigaddset(&blocked,SIGUSR2);
    assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
    int fd=signalfd(-1,&blocked,SFD_CLOEXEC|SFD_NONBLOCK); assert(fd>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=44},out;
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    assert(!raise(SIGUSR2));
    assert(epoll_wait(ep,&out,1,0)==1 && out.data.u64==44 && (out.events&EPOLLIN));
    struct signalfd_siginfo info;
    assert(read(fd,&info,sizeof(info))==sizeof(info) && info.ssi_signo==SIGUSR2);
    assert(!epoll_wait(ep,&out,1,0));
    int go[2]; assert(!pipe2(go,O_CLOEXEC));
    pid_t owner=getpid(),child=fork(); assert(child>=0);
    if (!child) { byte_read(go[0]); assert(!kill(owner,SIGUSR2)); _exit(0); }
    byte_write(go[1]);
    assert(epoll_wait(ep,&out,1,3000)==1 && out.data.u64==44 && (out.events&EPOLLIN));
    assert(read(fd,&info,sizeof(info))==sizeof(info) && info.ssi_signo==SIGUSR2);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(go[0]); close(go[1]);
    assert(!epoll_ctl(ep,EPOLL_CTL_DEL,fd,NULL)); close(fd);
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL));
    puts("PASS mixed epoll: task-affine signalfd readiness and process-signal wake");
}
static void virtual_wake(int ep) {
    int go[2]; assert(!pipe2(go,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) { byte_read(go[0]); assert(!sethostname("wake-virtual",12)); _exit(0); }
    byte_write(go[1]);
    struct epoll_event out={0};
    errno=0;
    int result=epoll_wait(ep,&out,1,3000),error=errno;
    if (result!=1 || out.data.u64!=42 || !(out.events&EPOLLERR))
        fprintf(stderr,"virtual wake: result=%d errno=%d events=%#x data=%llu\n",result,error,
            out.events,(unsigned long long)out.data.u64);
    assert(result==1 && out.data.u64==42 && (out.events&EPOLLERR));
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(go[0]); close(go[1]);
    assert(!epoll_wait(ep,&out,1,0));
    puts("PASS mixed epoll: virtual source wakes a caller-context native wait");
}
static void shared_owner(int protected) {
    int fd=open("/proc/sys/kernel/hostname",O_RDONLY|O_CLOEXEC),ep=epoll_create1(EPOLL_CLOEXEC);
    int go[2],ready[2]; assert(fd>=0 && ep>=0 && !pipe2(go,O_CLOEXEC) && !pipe2(ready,O_CLOEXEC));
    int alias=dup(ep); assert(alias>=0);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0,0,0,0));
        byte_write(ready[1]); byte_read(go[0]);
        struct epoll_event event;
        assert(epoll_wait(alias,&event,1,0)==1 && event.data.u64==84 && (event.events&EPOLLERR));
        assert(!epoll_wait(alias,&event,1,0));
        _exit(0);
    }
    byte_read(ready[0]);
    struct epoll_event event={.events=0,.data.u64=84};
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    assert(!sethostname(protected ? "protected-owner" : "shared-owner",protected ? 15 : 12));
    byte_write(go[1]);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(go[0]); close(go[1]); close(ready[0]); close(ready[1]); close(alias); close(ep); close(fd);
    printf("PASS epoll cross-owner activation protected=%d\n",protected);
}

static void deny_seccomp(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void cold_admission(int protected,int infinite) {
    assert(!unshare(CLONE_NEWNS|CLONE_NEWUTS));
    assert(!mount("proc","/proc","proc",0,NULL));
    int fd=open("/proc/sys/kernel/hostname",O_RDONLY|O_CLOEXEC),ep=epoll_create1(EPOLL_CLOEXEC);
    int ready[2]; assert(fd>=0 && ep>=0 && !pipe2(ready,O_CLOEXEC));
    int alias=dup(ep); assert(alias>=0);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        if (protected) { deny_seccomp(); assert(!prctl(PR_SET_DUMPABLE,0,0,0,0)); }
        sigset_t blocked,saved,after;
        sigemptyset(&blocked); sigaddset(&blocked,SIGUSR2);
        assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
        byte_write(ready[1]);
        struct epoll_event event={0};
        int count=infinite==2 ? syscall(SYS_epoll_pwait2,alias,&event,1,NULL,NULL,8)
            : epoll_pwait(alias,&event,1,infinite ? -1 : 2000,infinite ? NULL : &saved);
        if (count!=1) fprintf(stderr,"cold admission: count=%d errno=%d protected=%d\n",count,errno,protected);
        assert(count==1 && event.data.u64==85 && (event.events&EPOLLERR));
        assert(!sigprocmask(SIG_SETMASK,NULL,&after) && sigismember(&after,SIGUSR2));
        assert(!epoll_wait(alias,&event,1,0));
        _exit(0);
    }
    byte_read(ready[0]);
    /* Test scheduling window: the child enters an empty native queue before
     * its first virtual interest. Readiness itself is asserted by the event. */
    assert(!poll(NULL,0,100));
    struct epoll_event event={.events=EPOLLET|EPOLLONESHOT,.data.u64=85};
    int added=epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event);
    if (added) fprintf(stderr,"cold admission ADD: errno=%d protected=%d\n",errno,protected);
    assert(!added);
    assert(!sethostname("cold-admission",14));
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(ready[0]); close(ready[1]); close(alias); close(ep); close(fd);
    printf("PASS mixed epoll: blocked native wait receives first virtual interest protected=%d infinite=%d\n",
        protected,infinite);
}
struct table_holder_context { int fd; pid_t parent; };
static int table_holder(void *context) {
    const struct table_holder_context *owner=context;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=owner->parent) return 0;
    /* EVENT_WAIT: parent finishes its assertions; parent death kills the
     * keeper so a failed test does not wait for the outer launch deadline. */
    byte_read(owner->fd);
    return 0;
}
static int64_t now_ms(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC,&t));
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static int loan_slot(int ep,int other) {
    char path[64],name[128];
    for (int fd=3;fd<128;++fd) if (fd!=ep && fd!=other) {
        snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
        ssize_t n=readlink(path,name,sizeof(name)-1);
        if (n<0) continue;
        name[n]=0;
        if (!strcmp(name,"anon_inode:[eventpoll]")) return fd;
    }
    return -1;
}
static int await_loan(int ep,int other,int present) {
    int64_t end=now_ms()+5000;
    /* EVENT_WAIT: observe an actual installed/retired epoll alias, not a
     * guessed settling delay. The deadline fails an absent lifecycle event. */
    do {
        int fd=loan_slot(ep,other);
        if ((fd>=0)==present) return fd;
        sched_yield();
    } while (now_ms()<end);
    assert(!"epoll loan lifecycle deadline"); return -1;
}
struct race_context { int ep,event,other,ready,mode; pid_t parent; };
static int race_mutator(void *opaque) {
    struct race_context *c=opaque;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=c->parent) return 0;
    int slot=await_loan(c->ep,c->other,1);
    if (c->mode==0) assert(dup3(c->event,slot,O_CLOEXEC)==slot);
    if (c->mode==1) assert(!syscall(SYS_close_range,slot,slot,0));
    if (c->mode==2) assert(dup3(c->other,c->ep,O_CLOEXEC)==c->ep);
    if (c->mode==3) {
        pid_t child=fork(); assert(child>=0);
        if (!child) { assert(loan_slot(c->ep,c->other)<0); _exit(0); }
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    if (c->mode==5) {
        assert(!unshare(CLONE_FILES));
        assert(loan_slot(c->ep,c->other)<0);
    }
    if (c->mode==6) assert(!syscall(SYS_close_range,slot,slot,CLOSE_RANGE_CLOEXEC));
    assert(write(c->ready,&slot,sizeof(slot))==sizeof(slot));
    uint64_t one=1; assert(write(c->event,&one,sizeof(one))==sizeof(one));
    return 0;
}
static int race_waiter(void *opaque) {
    struct race_context *c=opaque;
    struct epoll_event out;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=c->parent) return 0;
    byte_write(c->ready);
    epoll_wait(c->ep,&out,1,-1);
    assert(!"killed waiter returned"); return 0;
}
static void shared_races(int fd) {
    for (int mode=0;mode<7;++mode) {
        int ep=epoll_create1(EPOLL_CLOEXEC),event=eventfd(0,EFD_CLOEXEC),other=epoll_create1(EPOLL_CLOEXEC);
        int ready[2]; assert(ep>=0 && event>=0 && other>=0 && !pipe2(ready,O_CLOEXEC));
        struct epoll_event e={.events=0,.data.u64=42},out;
        assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&e));
        e=(struct epoll_event){.events=EPOLLIN,.data.u64=43};
        assert(!epoll_ctl(ep,EPOLL_CTL_ADD,event,&e));
        void *stack=malloc(65536); assert(stack);
        struct race_context c={ep,event,other,ready[1],mode,getpid()};
        pid_t child=clone(mode==4 ? race_waiter : race_mutator,(char *)stack+65536,CLONE_FILES|SIGCHLD,&c);
        assert(child>0);
        if (mode==4) {
            byte_read(ready[0]);
            await_loan(ep,other,1);
            assert(!kill(child,SIGKILL));
        } else {
            int count=epoll_wait(ep,&out,1,10000);
            if (count!=1) fprintf(stderr,"race mode=%d count=%d errno=%d\n",mode,count,errno);
            assert(count==1 && out.data.u64==43);
            int slot; assert(read(ready[0],&slot,sizeof(slot))==sizeof(slot));
            if (!mode) { assert(fcntl(slot,F_GETFD)==FD_CLOEXEC); close(slot); }
        }
        int status; assert(waitpid(child,&status,0)==child);
        assert(mode==4 ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL : WIFEXITED(status) && !WEXITSTATUS(status));
        await_loan(ep,other,0);
        free(stack); close(ep); close(event); close(other); close(ready[0]); close(ready[1]);
        printf("PASS shared epoll custody mode=%d: exact queue and no retained alias\n",mode);
    }
}
static void mixed(int filtered,int shared) {
    assert(!unshare(CLONE_NEWNS|CLONE_NEWUTS));
    assert(!mount("proc","/proc","proc",0,NULL));
    if (filtered) deny_seccomp();
    int fd=open("/proc/sys/kernel/hostname",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    int ep=epoll_create1(EPOLL_CLOEXEC),event=eventfd(0,EFD_CLOEXEC); assert(ep>=0 && event>=0);
    struct pollfd chunks[513];
    for (unsigned i=0;i<513;++i) chunks[i]=(struct pollfd){-1,0,0};
    chunks[0]=(struct pollfd){event,POLLIN,0};
    chunks[512]=(struct pollfd){fd,0,0};
    assert(!poll(chunks,513,0));
    assert(!sethostname("poll-chunks",11));
    assert(poll(chunks,513,0)==1 && (chunks[512].revents&POLLERR));
    assert(!poll(chunks,513,0));
    struct epoll_event e={.events=0,.data.u64=42},out[4];
    puts("READY virtual ADD");
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&e));
    puts("READY virtual ADD completed");
    e=(struct epoll_event){.events=EPOLLIN,.data.u64=43};
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,event,&e));
    int holder_pipe[2]={-1,-1}; void *holder_stack=NULL; pid_t holder=0;
    struct table_holder_context holder_context={0};
    if (shared) {
        assert(!pipe2(holder_pipe,O_CLOEXEC));
        holder_stack=malloc(65536); assert(holder_stack);
        holder_context=(struct table_holder_context){holder_pipe[0],getpid()};
        holder=clone(table_holder,(char *)holder_stack+65536,CLONE_FILES|SIGCHLD,&holder_context);
        assert(holder>0);
    }
    assert(!epoll_wait(ep,out,4,0));
    uint64_t one=1; assert(write(event,&one,sizeof(one))==sizeof(one));
    assert(epoll_wait(ep,out,4,0)==1 && out[0].data.u64==43);
    assert(read(event,&one,sizeof(one))==sizeof(one));
    assert(!sethostname("epoll-test",10));
    assert(epoll_wait(ep,out,4,0)==1 && out[0].data.u64==42 && (out[0].events&EPOLLERR));
    assert(!epoll_wait(ep,out,4,0));
    pid_t child=fork(); assert(child>=0);
    if (!child) { assert(write(event,&one,sizeof(one))==sizeof(one)); _exit(0); }
    assert(epoll_wait(ep,out,4,3000)==1 && out[0].data.u64==43);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(read(event,&one,sizeof(one))==sizeof(one));
    assert(!epoll_wait(ep,out,4,1));
    virtual_wake(ep);
    e.data.u64=42;
    assert(!epoll_ctl(ep,EPOLL_CTL_MOD,event,&e));
    assert(write(event,&one,sizeof(one))==sizeof(one) && !sethostname("equal-data",10));
    assert(epoll_wait(ep,out,2,0)==2 && out[0].data.u64==42 && out[1].data.u64==42
        && ((out[0].events|out[1].events)&(EPOLLIN|EPOLLERR))==(EPOLLIN|EPOLLERR));
    assert(read(event,&one,sizeof(one))==sizeof(one));
    signal_wait(ep);
    signal_ready(ep);
    signal_descriptor(ep);
    native_wait_signals();
    interrupted_poll_clone(fd);
    if (shared) {
        byte_write(holder_pipe[1]);
        assert(waitpid(holder,&status,0)==holder && WIFEXITED(status) && !WEXITSTATUS(status));
        close(holder_pipe[0]); close(holder_pipe[1]); free(holder_stack);
        /* The following scenarios own their epoll queues independently. */
        close(ep); ep=-1; close(event); event=-1;
        shared_races(fd);
    }
    if (ep>=0) {
        assert(!epoll_ctl(ep,EPOLL_CTL_DEL,fd,NULL));
        assert(!epoll_ctl(ep,EPOLL_CTL_DEL,event,NULL));
    }
    close(ep); close(fd); close(event);
    puts("PASS mixed epoll: virtual hostname, native eventfd, fork, blocking wait and timeout");
    shared_owner(0); shared_owner(1);
}
static void close_filtered(int retirement_denied) {
    assert(!unshare(CLONE_NEWNS|CLONE_NEWUTS));
    assert(!mount("proc","/proc","proc",0,NULL));
    int fd=open("/proc/sys/kernel/hostname",O_RDONLY|O_CLOEXEC),ep=epoll_create1(EPOLL_CLOEXEC);
    int release[2]; assert(fd>=0 && ep>=0 && !pipe2(release,O_CLOEXEC));
    struct epoll_event e={.data.u64=42},out;
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&e));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_close,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_close_range,0,1),
        BPF_STMT(BPF_RET|BPF_K,retirement_denied ? SECCOMP_RET_ERRNO|EPERM : SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog policy={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&policy));
    void *stack=malloc(65536); assert(stack);
    struct table_holder_context c={release[0],getpid()};
    pid_t peer=clone(table_holder,(char *)stack+65536,CLONE_FILES|SIGCHLD,&c); assert(peer>0);
    assert(!epoll_wait(ep,&out,1,0));
    assert(!retirement_denied);
    assert(!sethostname("close-policy",12));
    assert(epoll_wait(ep,&out,1,0)==1 && out.data.u64==42);
    assert(loan_slot(ep,-1)<0);
    assert(close(ep)==-1 && errno==EPERM);
    byte_write(release[1]);
    int status; assert(waitpid(peer,&status,0)==peer && WIFEXITED(status) && !WEXITSTATUS(status));
    free(stack);
    puts("PASS mixed epoll: denied close retains application policy; exact close_range retirement");
}

static int filter_probe(void) {
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,UINT_MAX,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    long result=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
    if (result) {
        printf("FAIL readiness selector admission result=%ld errno=%d\n",result,errno);
        errno=0;
        result=prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program,0,0);
        printf("PROBE per-thread prctl filter result=%ld errno=%d\n",result,errno);
        return 1;
    }
    struct epoll_event event;
    assert(syscall(SYS_epoll_pwait,-1,&event,1,0,NULL,8)==-1 && errno==EACCES);
    int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
    assert(!syscall(SYS_epoll_pwait,ep,&event,1,0,NULL,8));
    close(ep);
    puts("PASS readiness selector admission under inherited service filters");
    return 0;
}
static void native_wait_capacity(void) {
    enum { WAITERS=64 };
    assert(!filter_probe());
    int ready[2],fd=eventfd(0,EFD_CLOEXEC|EFD_SEMAPHORE);
    assert(fd>=0 && !pipe2(ready,O_CLOEXEC));
    pid_t children[WAITERS];
    for (unsigned i=0;i<WAITERS;++i) {
        children[i]=fork(); assert(children[i]>=0);
        if (children[i]) continue;
        close(ready[0]);
        int ep=epoll_create1(EPOLL_CLOEXEC); assert(ep>=0);
        struct epoll_event event={.events=EPOLLIN,.data.u64=73},out;
        assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
        byte_write(ready[1]);
        /* EVENT_WAIT: the parent releases all waiters only after every child
         * is ready. The launch deadline fails transport-capacity deadlocks. */
        long result=i&1 ? syscall(SYS_epoll_pwait2,ep,&out,1,NULL,NULL,8)
            : syscall(SYS_epoll_pwait,ep,&out,1,-1,NULL,8);
        assert(result==1 && out.events==EPOLLIN && out.data.u64==73);
        uint64_t one; assert(read(fd,&one,sizeof(one))==sizeof(one) && one==1);
        close(ep); _exit(0);
    }
    close(ready[1]);
    for (unsigned i=0;i<WAITERS;++i) byte_read(ready[0]);
    int directory=open("/tmp",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    close(directory);
    uint64_t count=WAITERS; assert(write(fd,&count,sizeof(count))==sizeof(count));
    for (unsigned i=0;i<WAITERS;++i) {
        int status;
        assert(waitpid(children[i],&status,0)==children[i] && status==0);
    }
    close(ready[0]); close(fd);
    puts("PASS mixed epoll: 64 filtered forked native waits leave shared transport available");
}
int main(int argc,char **argv) {
    setbuf(stdout,NULL);
    if (argc==2 && !strcmp(argv[1],"--native-ready")) { native_ready(); return 0; }
    if (argc==2 && !strcmp(argv[1],"--native-capacity")) { native_wait_capacity(); return 0; }
    if (argc==2 && !strcmp(argv[1],"--cold-admission")) {
        for (int infinite=0;infinite<3;++infinite) { cold_admission(0,infinite); cold_admission(1,infinite); }
        return 0;
    }
    if (argc==2 && !strcmp(argv[1],"--concurrent-admission")) { concurrent_admission(); return 0; }
    if (argc==2 && !strcmp(argv[1],"--poll-sandbox")) { poll_sandbox(0); return 0; }
    if (argc==2 && !strcmp(argv[1],"--poll-sandbox-protected")) { poll_sandbox(1); return 0; }
    if (argc==2 && !strcmp(argv[1],"--mixed")) { mixed(0,0); return 0; }
    if (argc==2 && !strcmp(argv[1],"--filtered")) { mixed(1,0); return 0; }
    if (argc==2 && !strcmp(argv[1],"--shared")) { mixed(0,1); return 0; }
    if (argc==2 && !strcmp(argv[1],"--shared-filtered")) { mixed(1,1); return 0; }
    if (argc==2 && !strcmp(argv[1],"--close-filtered")) { close_filtered(0); return 0; }
    if (argc==2 && !strcmp(argv[1],"--retire-filtered")) { close_filtered(1); return 0; }
    assert(argc>=2 && !strcmp(argv[1],"--filter"));
    int result=filter_probe();
    if (result || argc==2) return result;
    execv(argv[2],argv+2);
    perror("readiness exec");
    return 1;
}
