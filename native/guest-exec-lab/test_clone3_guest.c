#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <linux/sched.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/futex.h>
#include <stdatomic.h>
#include <stddef.h>
#include <signal.h>
#include <stdint.h>
#include <limits.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <stdlib.h>
#include <unistd.h>

static uint64_t expected_mask;
static void mask_unchanged(void) {
    uint64_t mask=0;
    assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&mask,sizeof(mask)));
    assert(mask==expected_mask);
}
static void reaped(pid_t child) {
    int status; pid_t result;
    do result=waitpid(child,&status,0); while (result<0 && errno==EINTR);
    assert(result==child && status==0);
    mask_unchanged();
}
extern long clone_stack(struct clone_args *,size_t,void (*)(void));
__asm__(".text\n.global clone_stack\n.type clone_stack,%function\nclone_stack:\n"
    "stp x19,x30,[sp,#-16]!\nmov x19,x2\nmov x8,#435\nsvc #0\n"
    "cbnz x0,1f\nblr x19\nmov x0,#0\nmov x8,#93\nsvc #0\nbrk #0\n"
    "1: ldp x19,x30,[sp],#16\nret\n.size clone_stack,.-clone_stack\n");
static atomic_int stack_child_ran;
static atomic_ulong observed_tls;
static void on_stack(void) {
    mask_unchanged(); atomic_store_explicit(&stack_child_ran,1,memory_order_release);
}
static void on_stack_tls(void) {
    unsigned long value;
    __asm__ volatile("mrs %0,tpidr_el0" : "=r"(value));
    /* This thread deliberately has no libc TLS. Return directly to the raw
     * exit trampoline without calling libc or accessing errno. */
    atomic_store_explicit(&observed_tls,value,memory_order_release);
}
static void thread_reaped(int *tid,long child) {
    /* EVENT_WAIT: kernel clear_child_tid releases the stack after thread exit;
     * the process alarm bounds a missing wake or incorrect TID publication. */
    for (;;) {
        int value=__atomic_load_n(tid,__ATOMIC_ACQUIRE);
        if (!value) break;
        assert(value==child);
        long result=syscall(SYS_futex,tid,FUTEX_WAIT,value,NULL,NULL,0);
        assert(!result || (result==-1 && (errno==EAGAIN || errno==EINTR)));
    }
}
static void stack_cases(void) {
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    for (unsigned i=0;i<2;++i) {
        struct clone_args a={.flags=CLONE_VM|(i ? CLONE_VFORK|CLONE_FILES : 0),
            .exit_signal=SIGCHLD,.stack=(uintptr_t)stack,.stack_size=65536};
        stack_child_ran=0;
        long child=clone_stack(&a,sizeof(a),on_stack);
        assert(child>0); reaped((pid_t)child); assert(stack_child_ran);
    }
    int tid=-1;
    struct clone_args thread={.flags=CLONE_VM|CLONE_THREAD|CLONE_SIGHAND|CLONE_FILES
        |CLONE_PARENT_SETTID|CLONE_CHILD_CLEARTID,.parent_tid=(uintptr_t)&tid,.child_tid=(uintptr_t)&tid,
        .stack=(uintptr_t)stack,.stack_size=65536};
    stack_child_ran=0;
    long child=clone_stack(&thread,sizeof(thread),on_stack);
    assert(child>0);
    thread_reaped(&tid,child);
    assert(atomic_load_explicit(&stack_child_ran,memory_order_acquire));
    unsigned long parent_tls,current_tls;
    __asm__ volatile("mrs %0,tpidr_el0" : "=r"(parent_tls));
    thread.flags|=CLONE_SETTLS;
    thread.tls=(uintptr_t)stack+32768;
    child=clone_stack(&thread,sizeof(thread),on_stack_tls);
    assert(child>0);
    thread_reaped(&tid,child);
    assert(atomic_load_explicit(&observed_tls,memory_order_acquire)==thread.tls);
    __asm__ volatile("mrs %0,tpidr_el0" : "=r"(current_tls));
    assert(parent_tls==current_tls);
    assert(!munmap(stack,65536));
}
static void argument_cases(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    unsigned char *memory=mmap(NULL,2*page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && !mprotect(memory+page,page,PROT_NONE));
    size_t sizes[]={CLONE_ARGS_SIZE_VER0,CLONE_ARGS_SIZE_VER1,CLONE_ARGS_SIZE_VER2};
    for (unsigned i=0;i<sizeof(sizes)/sizeof(*sizes);++i) {
        struct clone_args input={.exit_signal=SIGCHLD};
        void *args=memory+page-sizes[i];
        memcpy(args,&input,sizes[i]);
        assert(!mprotect(memory,page,PROT_READ));
        long child=syscall(SYS_clone3,args,sizes[i]);
        if (!child) _exit(0);
        assert(child>0); reaped((pid_t)child);
        assert(!memcmp(args,&input,sizes[i]));
        assert(syscall(SYS_clone3,args,sizes[i]+1)==-1 && errno==EFAULT);
        assert(!mprotect(memory,page,PROT_READ|PROT_WRITE));
    }
    struct clone_args args={.exit_signal=SIGCHLD,.set_tid=1};
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EINVAL);
    args.set_tid=0; args.set_tid_size=1;
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EINVAL);
    args.set_tid=1;
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EFAULT);
    args.exit_signal=UINT64_C(1)<<32;
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EINVAL);
    args=(struct clone_args){.flags=CLONE_INTO_CGROUP|CLONE_DETACHED,
        .exit_signal=SIGCHLD,.cgroup=INT_MAX};
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EINVAL);
    args.flags=CLONE_INTO_CGROUP|CLONE_VM|CLONE_SIGHAND|CLONE_CLEAR_SIGHAND;
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EINVAL);
    assert(syscall(SYS_clone3,memory,page+1)==-1 && errno==E2BIG);
    assert(!munmap(memory,2*page));
}
static volatile sig_atomic_t signal_count;
static void interrupted(int signal) {
    (void)signal;
    int saved=errno;
    int fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
    if (fd>=0) close(fd);
    ++signal_count; errno=saved;
}
static void clear_sighand_case(void) {
    struct sigaction caught={.sa_handler=interrupted},ignored={.sa_handler=SIG_IGN},old[2],actual;
    sigemptyset(&caught.sa_mask); sigemptyset(&ignored.sa_mask);
    assert(!sigaction(SIGUSR1,&caught,&old[0]) && !sigaction(SIGWINCH,&ignored,&old[1]));
    struct clone_args args={.flags=CLONE_CLEAR_SIGHAND,.exit_signal=SIGCHLD};
    long child=syscall(SYS_clone3,&args,sizeof(args));
    assert(child>=0);
    if (!child) {
        assert(!sigaction(SIGUSR1,NULL,&actual) && actual.sa_handler==SIG_DFL);
        assert(!sigaction(SIGWINCH,NULL,&actual) && actual.sa_handler==SIG_IGN);
        mask_unchanged(); _exit(0);
    }
    reaped((pid_t)child);
    assert(!sigaction(SIGUSR1,NULL,&actual) && actual.sa_handler==interrupted);
    args.flags|=CLONE_SIGHAND|CLONE_VM;
    assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EINVAL);
    assert(!sigaction(SIGUSR1,&old[0],NULL) && !sigaction(SIGWINCH,&old[1],NULL));
}
static void signal_cases(void) {
    struct sigaction action={.sa_handler=interrupted},previous;
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR1,&action,&previous));
    int channel[2]; assert(!pipe(channel));
    pid_t parent=getpid(),sender=fork(); assert(sender>=0);
    if (!sender) {
        close(channel[1]); char byte;
        while (read(channel[0],&byte,1)==1) assert(!kill(parent,SIGUSR1));
        close(channel[0]); _exit(0);
    }
    close(channel[0]);
    for (unsigned i=0;i<64;++i) {
        assert(write(channel[1],"x",1)==1);
        /* Reuse the warmed clone3 site while signals cross stack/TLS preflight.
         * Its prepared replay must not enter the resident adapter again. */
        if (!(i%8)) stack_cases();
        struct clone_args args={.exit_signal=SIGCHLD};
        long child=syscall(SYS_clone3,&args,sizeof(args));
        if (!child) { mask_unchanged(); _exit(0); }
        assert(child>0); reaped((pid_t)child);
    }
    close(channel[1]); reaped(sender);
    assert(signal_count && !sigaction(SIGUSR1,&previous,NULL));
}
static void denied_case(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone3,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        struct clone_args args={.exit_signal=SIGCHLD};
        assert(syscall(SYS_clone3,&args,sizeof(args))==-1 && errno==EPERM);
        mask_unchanged();
        _exit(0);
    }
    reaped(child);
}
static void fork_case(unsigned long flags,int protected) {
    int parent=-1,child_tid=-1,pidfd=-1;
    struct clone_args args={.flags=flags,.exit_signal=SIGCHLD,
        .parent_tid=(uintptr_t)&parent,.child_tid=(uintptr_t)&child_tid,.pidfd=(uintptr_t)&pidfd};
    struct clone_args before=args;
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    long child=syscall(SYS_clone3,&args,sizeof(args));
    assert(child>=0 && !memcmp(&args,&before,sizeof(args)));
    mask_unchanged();
    if (!child) {
        pid_t self=(pid_t)syscall(SYS_getpid);
        if (flags&CLONE_NEWPID) assert(self==1);
        if (flags&CLONE_CHILD_SETTID) assert(child_tid==self);
        _exit(0);
    }
    if (flags&CLONE_PARENT_SETTID) assert(parent==child);
    if (flags&CLONE_PIDFD) {
        assert(pidfd>=0 && (fcntl(pidfd,F_GETFD)&FD_CLOEXEC));
        struct pollfd p={pidfd,POLLIN,0};
        assert(poll(&p,1,5000)==1 && (p.revents&POLLIN));
        siginfo_t info={0};
        assert(!waitid(P_PIDFD,pidfd,&info,WEXITED|WNOWAIT));
        assert(info.si_pid==child && info.si_status==0);
        assert(!close(pidfd));
    }
    reaped((pid_t)child);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,1));
}
static void cgroup_cases(void);
static void selected_pid_cases(void) {
    pid_t selected[3]={1,0,0};
    struct clone_args init={.flags=CLONE_NEWPID,.exit_signal=SIGCHLD,
        .set_tid=(uintptr_t)selected,.set_tid_size=1};
    long child=syscall(SYS_clone3,&init,sizeof(init));
    assert(child>=0);
    if (child) { reaped((pid_t)child); return; }
    assert(syscall(SYS_getpid)==1);
    FILE *limit=fopen("/proc/sys/kernel/pid_max","re"); assert(limit);
    int maximum=0; assert(fscanf(limit,"%d",&maximum)==1 && maximum>42); fclose(limit);
    int pidfd=-1,tid=-1;
    struct clone_args a={.flags=CLONE_PIDFD|CLONE_PARENT_SETTID,.exit_signal=SIGCHLD,
        .pidfd=(uintptr_t)&pidfd,.parent_tid=(uintptr_t)&tid,
        .set_tid=(uintptr_t)selected,.set_tid_size=1};
    selected[0]=0;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EINVAL);
    selected[0]=maximum;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EINVAL);
    selected[0]=42; a.set_tid_size=3;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EINVAL);
    a.set_tid_size=1; a.flags|=CLONE_SIGHAND;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EINVAL);
    a.flags&=~CLONE_SIGHAND;
    child=syscall(SYS_clone3,&a,sizeof(a));
    if (!child) { assert(syscall(SYS_getpid)==42); _exit(0); }
    assert(child==42 && tid==42 && pidfd>=0);
    struct pollfd p={pidfd,POLLIN,0};
    assert(poll(&p,1,5000)==1);
    int retained=pidfd;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EEXIST);
    reaped(42);
    child=syscall(SYS_clone3,&a,sizeof(a));
    if (!child) { assert(syscall(SYS_getpid)==42); _exit(0); }
    assert(child==42 && pidfd!=retained);
    reaped(42); close(pidfd); close(retained);
    selected[0]=1; selected[1]=77; a.set_tid_size=2; a.flags=CLONE_NEWPID;
    child=syscall(SYS_clone3,&a,sizeof(a));
    if (!child) { assert(syscall(SYS_getpid)==1 && syscall(SYS_getppid)==0); _exit(0); }
    assert(child==77); reaped(77);
    cgroup_cases();
    _exit(0);
}
static void cgroup_cases(void) {
    char path[]="/tmp/md-clone3-XXXXXX";
    assert(mkdtemp(path));
    assert(!mount("none",path,"cgroup2",0,NULL));
    int root=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    assert(!mkdirat(root,"child",0755));
    int target=openat(root,"child",O_PATH|O_CLOEXEC); assert(target>=0);
    struct clone_args a={.flags=CLONE_INTO_CGROUP,.cgroup=(unsigned)target,.exit_signal=SIGCHLD};
    int wrong[2]; assert(!pipe(wrong));
    a.cgroup=(unsigned)wrong[0];
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EINVAL);
    close(wrong[0]); close(wrong[1]);
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EBADF);
    a.cgroup=(unsigned)target;
    for (int protected=0;protected<2;++protected) {
        int channel[2]; assert(!pipe(channel));
        int pidfd=-1; a.flags|=CLONE_PIDFD; a.pidfd=(uintptr_t)&pidfd;
        if (protected) a.flags|=CLONE_NEWCGROUP;
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        long child=syscall(SYS_clone3,&a,sizeof(a));
        if (!child) {
            if (protected) {
                int fd=open("/proc/self/cgroup",O_RDONLY); assert(fd>=0);
                char membership[256]={0}; assert(read(fd,membership,sizeof(membership)-1)>0);
                assert(strstr(membership,"0::/\n")); close(fd);
            }
            close(channel[1]); char byte;
            assert(read(channel[0],&byte,1)==1); close(channel[0]); _exit(0);
        }
        assert(child>0); close(channel[0]);
        assert(!syscall(SYS_pidfd_send_signal,pidfd,0,NULL,0));
        int ns=ioctl(pidfd,0xff04,0); assert(ns>=0);
        struct stat own,peer;
        assert(!stat("/proc/self/ns/net",&own) && !fstat(ns,&peer) && own.st_ino==peer.st_ino);
        close(ns);
        pid_t filtered=fork(); assert(filtered>=0);
        if (!filtered) {
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,3,0),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,2,0),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone3,1,0),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
            struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
            long signal_result=syscall(SYS_pidfd_send_signal,pidfd,0,NULL,0);
            if (signal_result) fprintf(stderr,"filtered pidfd signal: pid=%ld protected=%d errno=%d\n",
                syscall(SYS_getpid),protected,errno);
            assert(!signal_result);
            _exit(0);
        }
        reaped(filtered);
        pid_t denied=fork(); assert(denied>=0);
        if (!denied) {
            assert(!setgid(1000) && !setuid(1000));
            assert(syscall(SYS_pidfd_send_signal,pidfd,0,NULL,0)==-1 && errno==EPERM);
            _exit(0);
        }
        reaped(denied);
        int fd=openat(target,"cgroup.procs",O_RDONLY); assert(fd>=0);
        char text[128]={0}; assert(read(fd,text,sizeof(text)-1)>0); close(fd);
        assert(strtol(text,NULL,10)==child);
        assert(unlinkat(root,"child",AT_REMOVEDIR)==-1 && errno==EBUSY);
        assert(write(channel[1],"x",1)==1); close(channel[1]); reaped(child);
        assert(!close(pidfd));
        if (protected) assert(!prctl(PR_SET_DUMPABLE,1));
    }
    a.flags|=CLONE_SIGHAND;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==EINVAL);
    assert(!unlinkat(root,"child",AT_REMOVEDIR));
    a.flags&=~CLONE_SIGHAND;
    assert(syscall(SYS_clone3,&a,sizeof(a))==-1 && errno==ENODEV);
    close(target); close(root);
    assert(!umount(path) && !rmdir(path));
}
int main(int argc,char **argv) {
    alarm(60);
    sigset_t block,previous;
    sigemptyset(&block); sigaddset(&block,SIGUSR2);
    assert(!sigprocmask(SIG_BLOCK,&block,&previous));
    assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&expected_mask,sizeof(expected_mask)));
    int probe=argc==2 && !strcmp(argv[1],"--probe");
    int native=argc==2 && !strcmp(argv[1],"--native");
    struct clone_args args={.exit_signal=SIGCHLD};
    long child=syscall(SYS_clone3,&args,sizeof(args));
    if (!child) _exit(0);
    if (child<0) {
        int error=errno;
        printf("clone3: errno=%d (%s)\n",error,strerror(error));
        return probe ? 0 : 1;
    }
    reaped((pid_t)child);
    if (probe) { puts("PASS clone3 fork/wait"); return 0; }
    assert(syscall(SYS_clone3,&args,0)==-1 && errno==EINVAL);
    assert(syscall(SYS_clone3,(void *)1,sizeof(args))==-1 && errno==EFAULT);
    struct clone_args invalid={.flags=CLONE_SIGHAND,.exit_signal=SIGCHLD};
    assert(syscall(SYS_clone3,&invalid,sizeof(invalid))==-1 && errno==EINVAL);
    mask_unchanged();
    struct { struct clone_args args; unsigned long extra; } extended={.args=args,.extra=1};
    assert(syscall(SYS_clone3,&extended,sizeof(extended))==-1 && errno==E2BIG);
    extended.extra=0;
    child=syscall(SYS_clone3,&extended,sizeof(extended));
    if (!child) _exit(0);
    assert(child>0); reaped((pid_t)child);
    argument_cases();
    stack_cases();
    clear_sighand_case();
    signal_cases();
    denied_case();
    if (!native) { selected_pid_cases(); cgroup_cases(); }
    for (int protected=0;protected<2;++protected) {
        fork_case(0,protected);
        fork_case(CLONE_PARENT_SETTID|CLONE_CHILD_SETTID,protected);
        fork_case(CLONE_PIDFD|CLONE_PARENT_SETTID|CLONE_CHILD_SETTID,protected);
        if (!native) fork_case(CLONE_NEWPID|CLONE_NEWNS|CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWNET
            |CLONE_PIDFD|CLONE_PARENT_SETTID|CLONE_CHILD_SETTID,protected);
    }
    puts("PASS clone3 fork, TIDs, pidfd, protected callers and namespace lifecycle");
    assert(!sigprocmask(SIG_SETMASK,&previous,NULL));
    return 0;
}
