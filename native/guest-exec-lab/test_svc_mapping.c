#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <setjmp.h>
#include <signal.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>
#include <sys/socket.h>
#include <ucontext.h>

static long (*call)(void);
static unsigned number;
extern long image_uid(void);
extern long live_uid(void);
extern long filtered_uid(long);
extern long live_open(long,const char *,long,long);
extern long live_unmap(void *,size_t);
extern long live_protect(void *,size_t,int);
extern long live_map(void *,size_t,int,int,int,long);
extern long live_remap(void *,size_t,size_t,int,void *);
extern long live_uname(struct utsname *);
extern long live_mknod(long,const char *,long,long);
extern long live_prctl(long,long,long,long,long);
extern long live_epoll(long,void *,long,long,void *,long);
extern long live_native_read(long,void *,long,long);
extern long live_ioctl(long,unsigned long,void *,long);
extern long live_wait4(long,void *,long,void *);
extern long live_waitid(long,long,void *,long,void *);
__asm__(".pushsection .text\n.p2align 16\n.global image_uid\n"
    "image_uid:\nmov x8,#174\nsvc #0\nret\n.p2align 16\n"
    ".global live_uid\nlive_uid:\nmov x8,#174\nsvc #0\nret\n"
    ".global filtered_uid\nfiltered_uid:\nmov x8,#174\nsvc #0\nret\n"
    ".global live_open\nlive_open:\nmov x8,#56\nsvc #0\nret\n"
    ".global live_unmap\nlive_unmap:\nmov x8,#215\nsvc #0\nret\n"
    ".global live_protect\nlive_protect:\nmov x8,#226\nsvc #0\nret\n"
    ".global live_map\nlive_map:\nmov x8,#222\nsvc #0\nret\n"
    ".global live_remap\nlive_remap:\nmov x8,#216\nsvc #0\nret\n"
    ".global live_uname\nlive_uname:\nmov x8,#160\nsvc #0\nret\n"
    ".global live_mknod\nlive_mknod:\nmov x8,#33\nsvc #0\nret\n"
    ".global live_prctl\nlive_prctl:\nmov x8,#167\nsvc #0\nret\n"
    ".global live_epoll\nlive_epoll:\nmov x8,#22\nsvc #0\nret\n"
    ".global live_native_read\nlive_native_read:\nmov x8,#63\nsvc #0\nret\n"
    ".global live_ioctl\nlive_ioctl:\nmov x8,#29\nsvc #0\nret\n"
    ".global live_wait4\nlive_wait4:\nmov x8,#260\nsvc #0\nret\n"
    ".global live_waitid\nlive_waitid:\nmov x8,#95\nsvc #0\nret\n.popsection");
static void publication_check(uintptr_t function,int patched) {
    uint32_t word=*(volatile const uint32_t *)(function+4);
    assert(patched ? (word&0xfc000000)==0x14000000 : word==0xd4000001);
}
static void debugger_rearm(int patched,int filtered) {
    alarm(30);
    if (filtered) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={1,code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    int ready[2],proceed[2]; assert(!pipe(ready) && !pipe(proceed));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        char byte;
        for (unsigned round=0;round<3;++round) {
            for (unsigned i=0;i<32;++i) assert(live_uid()==getuid());
            publication_check((uintptr_t)live_uid,patched);
            assert(write(ready[1],"x",1)==1);
            /* EVENT_WAIT: the debugger detaches before releasing this round. */
            assert(read(proceed[0],&byte,1)==1);
        }
        for (unsigned i=0;i<32;++i) assert(live_uid()==getuid());
        publication_check((uintptr_t)live_uid,patched);
        _exit(0);
    }
    close(ready[1]); close(proceed[0]);
    for (unsigned round=0;round<3;++round) {
        char byte; assert(read(ready[0],&byte,1)==1);
        pid_t tracer=round==2 ? fork() : 0; assert(tracer>=0);
        if (!tracer) {
            assert(!ptrace(round==1 ? PTRACE_SEIZE : PTRACE_ATTACH,child,NULL,NULL));
            if (round==1) assert(!ptrace(PTRACE_INTERRUPT,child,NULL,NULL));
            int status; assert(waitpid(child,&status,__WALL)==child && WIFSTOPPED(status));
            errno=0;
            long word=ptrace(PTRACE_PEEKTEXT,child,(void *)live_uid+4,NULL);
            assert(!errno && (uint32_t)word==0xd4000001);
            if (round==2) _exit(0);
            assert(!ptrace(PTRACE_DETACH,child,NULL,NULL));
        } else {
            int status; assert(waitpid(tracer,&status,0)==tracer && WIFEXITED(status) && !WEXITSTATUS(status));
        }
        assert(write(proceed[1],"x",1)==1);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(ready[0]); close(proceed[1]);
    puts("PASS SVC debugger rearm: attach, seize, owner exit, original instructions and resumed publication");
}
static void native_ioctls(int patched,int filtered) {
    publication_check((uintptr_t)live_ioctl,0);
    assert(live_ioctl(-1,FIONBIO,NULL,0)==-EBADF);
    publication_check((uintptr_t)live_ioctl,patched);
    int pipefd[2]; assert(!pipe(pipefd));
    int alias=dup(pipefd[0]); assert(alias>=0);
    for (unsigned i=0;i<64;++i) {
        int enabled=i&1;
        assert(!live_ioctl(pipefd[0],FIONBIO,&enabled,0));
        assert(!!(fcntl(alias,F_GETFL)&O_NONBLOCK)==enabled);
        assert(live_ioctl(alias,FIONBIO,(void *)1,0)==-EFAULT);
        if (filtered) {
            int denied=!enabled;
            assert(live_ioctl(alias,FIONBIO,&denied,7)==-EACCES);
            assert(!!(fcntl(pipefd[0],F_GETFL)&O_NONBLOCK)==enabled);
        }
    }
    assert(!close(alias) && !close(pipefd[0]) && !close(pipefd[1]));
}
static void first_encounter(int patched,int filtered) {
    if (filtered) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_uname,1,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,7,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    publication_check((uintptr_t)live_prctl,0);
    assert(live_prctl(PR_GET_DUMPABLE,0,0,0,0)>=0);
    publication_check((uintptr_t)live_prctl,patched);
    for (unsigned i=0;i<32;++i) assert(live_prctl(PR_GET_DUMPABLE,0,0,0,0)>=0);
    publication_check((uintptr_t)live_epoll,0);
    struct epoll_event event;
    assert(live_epoll(-1,&event,1,1,NULL,8)==-EBADF);
    publication_check((uintptr_t)live_epoll,patched);
    for (unsigned i=0;i<64;++i) {
        assert(live_epoll(-1,&event,1,0,NULL,8)==-EBADF);
        if (filtered) assert(live_epoll(7,&event,1,0,NULL,8)==-EACCES);
    }
    publication_check((uintptr_t)live_uname,0);
    assert(live_uname((void *)1)==-EFAULT);
    publication_check((uintptr_t)live_uname,patched);
    struct utsname name;
    for (unsigned i=0;i<32;++i) {
        assert(!live_uname(&name) && !strcmp(name.sysname,"Linux"));
        assert(live_uname((void *)7)==(filtered ? -EACCES : -EFAULT));
    }
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    publication_check((uintptr_t)live_map,0);
    int image=open("/proc/self/exe",O_RDONLY|O_CLOEXEC); assert(image>=0);
    long mapped=live_map(NULL,page,PROT_READ,MAP_PRIVATE,image,0);
    assert(mapped>0);
    publication_check((uintptr_t)live_map,patched);
    assert(*(const unsigned char *)mapped==0x7f && !close(image));
    assert(live_map((void *)mapped,page,PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0)==mapped);
    assert(!munmap((void *)mapped,page));
    for (unsigned i=0;i<64;++i) {
        mapped=live_map(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(mapped>0); *(volatile unsigned *)mapped=i;
        assert(!live_protect((void *)mapped,page,PROT_READ));
        long moved=live_remap((void *)mapped,page,page*2,MREMAP_MAYMOVE,NULL);
        assert(moved>0 && *(volatile unsigned *)moved==i);
        assert(!live_unmap((void *)moved,page*2));
        assert(live_protect((void *)1,page,PROT_READ)==-EINVAL);
    }
    native_ioctls(patched,0);
    /* A failed cold admission is not retirement of a published gate. Changing
     * one byte's protection changes the whole page, including this tail site. */
    void *writable=mmap(NULL,page,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(writable!=MAP_FAILED);
    uint32_t *deferred=(uint32_t *)((char *)writable+page-16);
    deferred[0]=0xd2800008|(SYS_getuid<<5); deferred[1]=0xd4000001; deferred[2]=0xd65f03c0;
    __builtin___clear_cache((char *)deferred,(char *)(deferred+3));
    long (*invoke)(void)=(void *)deferred;
    for (unsigned i=0;i<32;++i) { assert(invoke()==getuid()); publication_check((uintptr_t)invoke,0); }
    assert(!live_protect(writable,1,PROT_READ|PROT_EXEC));
    assert(invoke()==getuid()); publication_check((uintptr_t)invoke,patched);
    assert(!munmap(writable,page));
    assert(live_wait4(-1,NULL,WNOHANG,NULL)==-ECHILD);
    publication_check((uintptr_t)live_wait4,patched);
    assert(live_waitid(P_ALL,0,NULL,WEXITED|WNOHANG,NULL)==-ECHILD);
    publication_check((uintptr_t)live_waitid,patched);
    for (unsigned i=0;i<8;++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(23);
        siginfo_t info={0};
        assert(!live_waitid(P_PID,child,&info,WEXITED|WNOWAIT,NULL));
        assert(info.si_pid==child && info.si_code==CLD_EXITED && info.si_status==23);
        int status=0;
        assert(live_wait4(child,&status,0,NULL)==child);
        assert(WIFEXITED(status) && WEXITSTATUS(status)==23);
        assert(live_wait4(child,NULL,WNOHANG,NULL)==-ECHILD);
    }
    puts("PASS SVC first encounter: common prctl entry, task execution, failed arguments and mapping effects");
}
static size_t unmap_page;
static atomic_int *native_seen;
static pid_t native_reader;
static int native_pipe[2];
static int native_jump;
static sigjmp_buf native_escape;
static void native_signal(int number,siginfo_t *info,void *opaque) {
    (void)info;
    ucontext_t *u=opaque;
    assert(number==SIGUSR1);
    uintptr_t pc=u->uc_mcontext.pc;
    if (pc!=(uintptr_t)live_native_read+4 && pc!=(uintptr_t)live_native_read+8) return;
    assert(u->uc_mcontext.regs[8]==SYS_read);
    uintptr_t sp=u->uc_mcontext.sp,here=(uintptr_t)&u;
    assert(sp>here && sp-here<65536);
    atomic_store_explicit(native_seen,1,memory_order_release);
    if (native_jump) siglongjmp(native_escape,1);
}
static void native_sender(void) {
    /* EVENT_WAIT: deliver until the handler observes the original wait site;
     * the fixture alarm bounds failure, not a presumed settling interval. */
    while (!atomic_load_explicit(native_seen,memory_order_acquire)) {
        assert(!kill(native_reader,SIGUSR1));
        struct timespec pause={0,100000}; nanosleep(&pause,NULL);
    }
    assert(write(native_pipe[1],"x",1)==1);
    _exit(0);
}
static void native_waits(int patched) {
    alarm(30);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_read,1,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ioctl,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,7,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    native_ioctls(patched,1);
    char byte;
    int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    for (unsigned i=0;i<1024;++i) assert(!live_native_read(fd,&byte,1,0));
    publication_check((uintptr_t)live_native_read,patched);
    assert(live_native_read(fd,&byte,1,7)==-EACCES);
    native_seen=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(native_seen!=MAP_FAILED); native_reader=getpid();
    for (unsigned mode=0;mode<3;++mode) {
        struct sigaction action={.sa_sigaction=native_signal,.sa_flags=SA_SIGINFO|(mode ? SA_RESTART : 0)};
        sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR1,&action,NULL));
        assert(!pipe(native_pipe)); atomic_store(native_seen,0); native_jump=mode==2;
        pid_t peer=fork(); assert(peer>=0);
        if (!peer) native_sender();
        if (!sigsetjmp(native_escape,1)) {
            long result=live_native_read(native_pipe[0],&byte,1,0);
            assert(result==(mode ? 1 : -EINTR));
            assert(!native_jump);
        } else assert(native_jump);
        int status;
        assert(waitpid(peer,&status,0)==peer && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(atomic_load(native_seen));
        assert(!close(native_pipe[0]) && !close(native_pipe[1]));
        for (unsigned i=0;i<128;++i) assert(!live_native_read(fd,&byte,1,0));
    }
    assert(!close(fd) && !munmap(native_seen,4096)); alarm(0);
    puts("PASS SVC native waits: policy, original signal frame, EINTR, restart and longjmp");
}
static volatile sig_atomic_t task_signals;
static void task_alarm(int signal) { assert(signal==SIGALRM); ++task_signals; }
static void *task_signal_wait(void *unused) {
    (void)unused;
    int ep=epoll_create1(EPOLL_CLOEXEC),fd=eventfd(0,EFD_CLOEXEC);
    assert(ep>=0 && fd>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=91};
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    for (unsigned i=0;i<512;++i) {
        long result=syscall(SYS_epoll_pwait,ep,&event,1,i&1,NULL,8);
        if (result && !(result==-1 && errno==EINTR))
            fprintf(stderr,"signal wait fd=%d result=%ld errno=%d round=%u\n",ep,result,errno,i);
        assert(!result || (result==-1 && errno==EINTR));
    }
    assert(!close(fd) && !close(ep));
    return NULL;
}
static void task_signal_completion(void) {
    struct sigaction action={.sa_handler=task_alarm},previous;
    assert(!sigemptyset(&action.sa_mask) && !sigaction(SIGALRM,&action,&previous));
    struct itimerval timer={.it_interval={0,100},.it_value={0,100}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    pthread_t peers[4];
    for (unsigned i=0;i<4;++i) assert(!pthread_create(peers+i,NULL,task_signal_wait,NULL));
    char path[96]; snprintf(path,sizeof(path),"/tmp/svc-task-once-%d",getpid());
    for (unsigned i=0;i<512;++i) {
        /* Creation exposes accidental replay: a second effect would change
         * success into EEXIST, unlike an idempotent metadata query. */
        assert(!live_mknod(AT_FDCWD,path,S_IFREG|0600,0));
        struct stat st; assert(!stat(path,&st) && S_ISREG(st.st_mode));
        assert(!unlink(path));
    }
    for (unsigned i=0;i<4;++i) assert(!pthread_join(peers[i],NULL));
    timer=(struct itimerval){0}; assert(!setitimer(ITIMER_REAL,&timer,NULL));
    assert(task_signals && !sigaction(SIGALRM,&previous,NULL));
    puts("PASS SVC task signals: non-idempotent creation and concurrent empty epoll across interruption");
}
static void task_signal_exec(const char *path) {
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    char *args[]={(char *)path,"task-signals",NULL};
    execv(path,args);
    assert(!"exec with inherited signal-test filter failed");
}
static volatile sig_atomic_t policy_traps;
static void policy_trap(int number,siginfo_t *info,void *opaque) {
    ucontext_t *context=opaque;
    assert(number==SIGSYS && info->si_code==SYS_SECCOMP && info->si_syscall==SYS_getuid
        && info->si_errno==7 && context->uc_mcontext.pc==(uintptr_t)filtered_uid+8);
    ++policy_traps; context->uc_mcontext.regs[0]=(uint64_t)-777L;
}
static void policy_loop(long uid) {
    for (unsigned i=0;i<256;++i) {
        assert(filtered_uid(0)==uid);
        assert(filtered_uid(1)==-EACCES);
        assert(filtered_uid(2)==-777);
        assert(live_uid()==uid);
    }
}
static void filter_origin(void) {
    long uid=getuid();
    for (unsigned i=0;i<64;++i) assert(filtered_uid(0)==uid);
    struct sigaction action={.sa_sigaction=policy_trap,.sa_flags=SA_SIGINFO};
    assert(!sigemptyset(&action.sa_mask) && !sigaction(SIGSYS,&action,NULL));
    uintptr_t pc=(uintptr_t)filtered_uid+8;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,10),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)pc,0,8),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(pc>>32),0,6),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,1,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP|7),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    policy_loop(uid); assert(policy_traps==256);
    pid_t child=fork(); assert(child>=0);
    if (!child) { policy_loop(uid); assert(policy_traps==512); _exit(0); }
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS SVC filter origin: original PC and arguments, ALLOW/ERRNO/TRAP, signal context and fork");
}
static void filter_readiness(uint32_t denial) {
    int fd=eventfd(1,EFD_CLOEXEC|EFD_NONBLOCK),epoll=epoll_create1(EPOLL_CLOEXEC);
    assert(fd>=0 && epoll>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=73};
    assert(!epoll_ctl(epoll,EPOLL_CTL_ADD,fd,&event));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[3])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,0,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    code[4].k=denial;
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned i=0;i<512;++i) {
        assert(syscall(SYS_epoll_pwait,epoll,&event,1,-1,NULL,8)==1 && event.data.u64==73);
        assert(syscall(SYS_epoll_pwait2,epoll,&event,1,NULL,NULL,8)==1 && event.data.u64==73);
    }
    errno=0;
    assert(syscall(SYS_epoll_pwait,epoll,&event,1,0,NULL,8)==-1
        && errno==((denial&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ERRNO ? EACCES : ENOSYS));
    assert(!close(epoll) && !close(fd));
    puts("PASS SVC filter readiness: original timeout admission, native ready probe, denied guest probe");
}
static void action_filter(uint32_t decision) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,9,0,1),
        BPF_STMT(BPF_RET|BPF_K,decision),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void *killed_thread(void *unused) {
    (void)unused;
    long uid=getuid();
    for (unsigned i=0;i<64;++i) assert(filtered_uid(0)==uid);
    action_filter(SECCOMP_RET_KILL_THREAD);
    for (unsigned i=0;i<64;++i) assert(filtered_uid(0)==uid);
    filtered_uid(9);
    _exit(99);
}
static void filter_actions(void) {
    const uint32_t actions[]={SECCOMP_RET_LOG,SECCOMP_RET_TRACE|123,
        SECCOMP_RET_USER_NOTIF,SECCOMP_RET_TRAP|7,SECCOMP_RET_KILL_PROCESS};
    for (unsigned i=0;i<sizeof(actions)/sizeof(*actions);++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            long uid=getuid();
            for (unsigned n=0;n<64;++n) assert(filtered_uid(0)==uid);
            struct sigaction handler={.sa_sigaction=policy_trap,.sa_flags=SA_SIGINFO};
            assert(!sigemptyset(&handler.sa_mask) && !sigaction(SIGSYS,&handler,NULL));
            action_filter(actions[i]);
            for (unsigned n=0;n<64;++n) assert(filtered_uid(0)==uid);
            unsigned action=actions[i]&SECCOMP_RET_ACTION_FULL;
            long value=filtered_uid(9);
            assert(action!=SECCOMP_RET_KILL_PROCESS);
            assert(value==(action==SECCOMP_RET_LOG ? uid : action==SECCOMP_RET_TRAP ? -777 : -ENOSYS));
            assert(policy_traps==(action==SECCOMP_RET_TRAP));
            for (unsigned n=0;n<64;++n) assert(filtered_uid(0)==uid);
            _exit(0);
        }
        int status; assert(waitpid(child,&status,0)==child);
        if ((actions[i]&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_KILL_PROCESS)
            assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        else assert(WIFEXITED(status) && !WEXITSTATUS(status));
    }
    pthread_t peer; assert(!pthread_create(&peer,NULL,killed_thread,NULL));
    assert(!pthread_join(peer,NULL));
    puts("PASS SVC filter actions: LOG, TRACE, USER_NOTIF, TRAP, KILL_PROCESS and KILL_THREAD");
}
static void filter_probe_invalid(void) {
    struct sock_filter allow[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={1,allow};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,NULL)<0 && errno==EFAULT);
    assert(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,NULL,0,0)<0 && errno==EFAULT);
    assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,1U<<31,&program)<0 && errno==EINVAL);
    program.len=0;
    assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)<0 && errno==EINVAL);
}
static void filter_exec(const char *path,int child,int waits) {
    if (!child) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,5),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,1,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,2,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP|7),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        char *argv[]={(char *)path,waits<0 ? "filter-exec-child" : "filter-exec-waits-child",
            waits==1 ? "patched" : "original",NULL};
        execv(path,argv);
        assert(!"exec with inherited filter failed");
    }
    assert(prctl(PR_GET_SECCOMP,0,0,0,0)==SECCOMP_MODE_FILTER);
    if (waits>=0) { native_waits(waits); return; }
    filter_probe_invalid();
    struct sigaction action={.sa_sigaction=policy_trap,.sa_flags=SA_SIGINFO};
    assert(!sigemptyset(&action.sa_mask) && !sigaction(SIGSYS,&action,NULL));
    long uid=filtered_uid(0);
    for (unsigned i=0;i<512;++i) {
        assert(filtered_uid(0)==uid);
        assert(filtered_uid(1)==-EACCES);
        assert(filtered_uid(2)==-777);
        struct stat value;
        assert(!stat(path,&value) && value.st_size>0);
    }
    assert(policy_traps==512);
    puts("PASS SVC filter exec: inherited policy, new image entry, original SIGSYS context");
}
static void *filter_probe_exec(void *path) {
    char *argv[]={path,"filter-probe-child",NULL};
    execv(path,argv);
    assert(!"exec after rejected filter failed");
    return NULL;
}
static void filter_probe(const char *path,int exec_mode) {
    filter_probe_invalid();
    if (!exec_mode) {
        for (unsigned i=0;i<2048;++i) {
            struct stat value;
            assert(!stat(path,&value) && value.st_size>0);
        }
        puts("PASS SVC filter probe: failed installation preserves entry");
    } else if (exec_mode==2) {
        pthread_t peer;
        assert(!pthread_create(&peer,NULL,filter_probe_exec,(void *)path));
        assert(!pthread_join(peer,NULL));
        assert(!"nonleader exec returned");
    } else filter_probe_exec((void *)path);
}
static volatile sig_atomic_t unmap_signals;
static pthread_barrier_t channel_barrier;
static pthread_mutex_t channel_turn=PTHREAD_MUTEX_INITIALIZER;
static int channel_serial;
static int channel_signals;
static _Thread_local _Alignas(64) uintptr_t channel_stack;
static _Thread_local volatile sig_atomic_t channel_received;
static atomic_int channel_signal_running,channel_signal_count;
struct channel_senders { pthread_t *threads; unsigned count; };
static void channel_signal(int signal,siginfo_t *info,void *context) {
    (void)info;
    assert(signal==SIGUSR1);
    uintptr_t sp=((ucontext_t *)context)->uc_mcontext.sp;
    /* A service mapping stack is not the interrupted application's stack. */
    assert(channel_stack && sp<=channel_stack && channel_stack-sp<1024*1024);
    ++channel_received;
    atomic_fetch_add_explicit(&channel_signal_count,1,memory_order_relaxed);
}
static void *channel_send(void *context) {
    const struct channel_senders *senders=context;
    /* Stress cold registration, then let all execution modes drain. An
     * unbounded producer measures signal throughput instead of helper custody. */
    for (unsigned wave=0;wave<128 && atomic_load_explicit(&channel_signal_running,memory_order_relaxed);++wave) {
        for (unsigned i=0;i<senders->count;++i) assert(!pthread_kill(senders->threads[i],SIGUSR1));
        struct timespec pause={.tv_nsec=100000};
        nanosleep(&pause,NULL);
    }
    return NULL;
}
static void channel_sync(void) {
    int result=pthread_barrier_wait(&channel_barrier);
    assert(!result || result==PTHREAD_BARRIER_SERIAL_THREAD);
}
static void *channel_thread(void *path) {
    uintptr_t stack;
    __asm__ volatile("mov %0,sp" : "=r"(stack));
    channel_stack=stack;
    if (channel_signals) channel_sync();
    channel_sync();
    if (channel_serial) assert(!pthread_mutex_lock(&channel_turn));
    for (unsigned i=0;i<1024;++i) {
        struct stat value;
        assert(!stat(path,&value) && value.st_size>0);
    }
    if (channel_serial) assert(!pthread_mutex_unlock(&channel_turn));
    /* Keep every registered channel alive until all peers finish. */
    channel_sync();
    channel_sync();
    /* Cold expansion and signal recovery may legitimately choose Base. Keep
     * the accelerated-completion quota independent of that scheduling race. */
    if (channel_serial) assert(!pthread_mutex_lock(&channel_turn));
    for (unsigned i=0;i<1024;++i) {
        struct stat value;
        assert(!stat(path,&value) && value.st_size>0);
    }
    if (channel_serial) assert(!pthread_mutex_unlock(&channel_turn));
    if (channel_signals) assert(channel_received>0);
    return NULL;
}
static void channel_threads(const char *path,int filtered,int many,int full) {
    unsigned count=many ? 192 : 48;
    channel_serial=many;
    alarm(90);
    struct sigaction previous;
    if (channel_signals) {
        struct sigaction action={.sa_sigaction=channel_signal,.sa_flags=SA_SIGINFO|SA_RESTART};
        assert(!sigaction(SIGUSR1,&action,&previous));
    }
    if (filtered) {
        struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={1,code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    assert(!pthread_barrier_init(&channel_barrier,NULL,count+1));
    for (unsigned round=0;round<2;++round) {
        pthread_t peers[192];
        for (unsigned i=0;i<count;++i) assert(!pthread_create(&peers[i],NULL,channel_thread,(void *)path));
        pthread_t sender;
        struct channel_senders senders={peers,count};
        if (channel_signals) {
            channel_sync();
            atomic_store(&channel_signal_running,1);
            assert(!pthread_create(&sender,NULL,channel_send,&senders));
        }
        struct rlimit limit;
        int fds[128],used=0;
        if (full) {
            assert(!getrlimit(RLIMIT_NOFILE,&limit));
            struct rlimit restricted={128,limit.rlim_max};
            assert(!setrlimit(RLIMIT_NOFILE,&restricted));
            for (;;) {
                int fd=dup(STDERR_FILENO);
                if (fd<0) { assert(errno==EMFILE); break; }
                assert(used<128); fds[used++]=fd;
            }
        }
        channel_sync(); channel_sync();
        if (channel_signals) {
            atomic_store(&channel_signal_running,0);
            assert(!pthread_join(sender,NULL));
        }
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            for (unsigned i=0;i<1024;++i) {
                struct stat value;
                assert(!stat(path,&value) && value.st_size>0);
            }
            _exit(0);
        }
        int status;
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        if (full) {
            while (used) assert(!close(fds[--used]));
            assert(!setrlimit(RLIMIT_NOFILE,&limit));
        }
        channel_sync();
        for (unsigned i=0;i<count;++i) assert(!pthread_join(peers[i],NULL));
    }
    assert(!pthread_barrier_destroy(&channel_barrier));
    if (channel_signals) {
        assert(atomic_load(&channel_signal_count)>0);
        assert(!sigaction(SIGUSR1,&previous,NULL));
    }
    puts("PASS SVC channels: concurrent registration, filtered calls and reuse");
}
static void unmap_signal(int signal) {
    (void)signal;
    int saved=errno;
    void *p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED && !live_unmap(p,1));
    ++unmap_signals;
    errno=saved;
}
static int unmap_loop(void *unused) {
    (void)unused;
    for (unsigned i=0;i<4096;++i) {
        void *p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(p!=MAP_FAILED);
        assert(live_unmap((char *)p+1,1)==-EINVAL);
        assert(!live_unmap(p,1));
    }
    return 0;
}
static void native_unmap(void) {
    alarm(30);
    unmap_page=(size_t)sysconf(_SC_PAGESIZE);
    unmap_loop(NULL);
    struct sigaction sa={.sa_handler=unmap_signal};
    sigemptyset(&sa.sa_mask);
    assert(!sigaction(SIGALRM,&sa,NULL));
    struct itimerval timer={.it_interval={.tv_usec=1000},.it_value={.tv_usec=1000}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    unmap_loop(NULL);
    timer=(struct itimerval){0};
    assert(!setitimer(ITIMER_REAL,&timer,NULL) && unmap_signals>0);
    sa.sa_handler=SIG_DFL;
    assert(!sigaction(SIGALRM,&sa,NULL));
    alarm(30);
    void *p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_munmap,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(live_unmap(p,1)==-EACCES);
    *(volatile char *)p=42;
    puts("PASS SVC native unmap: rounded ranges, invalid address, signal reentry and filter revocation");
}
static void protect_loop(unsigned count) {
    void *p=mmap(NULL,unmap_page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);
    for (unsigned i=0;i<count;++i) {
        assert(!live_protect(p,1,PROT_READ|PROT_EXEC));
        assert(!live_protect(p,1,PROT_NONE));
        /* The second page is not included by page rounding. */
        *((volatile char *)p+unmap_page)=42;
        assert(!live_protect(p,1,PROT_READ|PROT_WRITE));
        *(volatile char *)p=43;
        assert(live_protect((char *)p+1,1,PROT_READ)==-EINVAL);
        assert(live_protect(p,1,PROT_READ|PROT_GROWSDOWN)==-EINVAL);
    }
    assert(!munmap(p,unmap_page*2));
}
static void protect_signal(int signal) {
    (void)signal;
    int saved=errno;
    protect_loop(1);
    ++unmap_signals;
    errno=saved;
}
static void native_protect(void) {
    alarm(30);
    unmap_page=(size_t)sysconf(_SC_PAGESIZE);
    protect_loop(4096);
    struct sigaction sa={.sa_handler=protect_signal};
    sigemptyset(&sa.sa_mask);
    assert(!sigaction(SIGALRM,&sa,NULL));
    struct itimerval timer={.it_interval={.tv_usec=1000},.it_value={.tv_usec=1000}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    protect_loop(4096);
    timer=(struct itimerval){0};
    assert(!setitimer(ITIMER_REAL,&timer,NULL) && unmap_signals>0);
    sa.sa_handler=SIG_DFL;
    assert(!sigaction(SIGALRM,&sa,NULL));
    alarm(30);
    void *p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mprotect,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(live_protect(p,1,PROT_NONE)==-EACCES);
    *(volatile char *)p=44;
    assert(!munmap(p,unmap_page));
    puts("PASS SVC native protect: rounded ranges, growth fallback, signal reentry and filter revocation");
}
static void map_loop(unsigned count) {
    char *p=mmap(NULL,unmap_page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);
    p[unmap_page]=42;
    for (unsigned i=0;i<count;++i) {
        p[0]=43;
        assert(live_map(p,1,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0)==(long)p);
        assert(!p[0] && p[unmap_page]==42);
        assert(live_map(p+1,1,PROT_READ,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0)==-EINVAL);
        assert(live_map(p,1,PROT_READ,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,1)==-EINVAL);
    }
    assert(!munmap(p,unmap_page*2));
}
static void map_signal(int signal) {
    (void)signal;
    int saved=errno;
    map_loop(1);
    ++unmap_signals;
    errno=saved;
}
static void native_map(void) {
    alarm(30);
    unmap_page=(size_t)sysconf(_SC_PAGESIZE);
    map_loop(4096);
    struct sigaction sa={.sa_handler=map_signal};
    sigemptyset(&sa.sa_mask);
    assert(!sigaction(SIGALRM,&sa,NULL));
    struct itimerval timer={.it_interval={.tv_usec=1000},.it_value={.tv_usec=1000}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    map_loop(4096);
    timer=(struct itimerval){0};
    assert(!setitimer(ITIMER_REAL,&timer,NULL) && unmap_signals>0);
    sa.sa_handler=SIG_DFL;
    assert(!sigaction(SIGALRM,&sa,NULL));
    alarm(30);
    char *p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED); *p=44;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(live_map(p,1,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0)==-EACCES);
    assert(*p==44 && !munmap(p,unmap_page));
    /* stdio may allocate after mmap has deliberately been denied. */
    static const char result[]="PASS SVC native map: anonymous replacement, rounded ranges, invalid arguments, signal reentry and filter revocation\n";
    assert(write(STDOUT_FILENO,result,sizeof(result)-1)==sizeof(result)-1);
}
static void remap_loop(unsigned count) {
    char *p=mmap(NULL,unmap_page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);
    p[0]=42;
    for (unsigned i=0;i<count;++i) {
        assert(live_remap(p,unmap_page*2,unmap_page,0,NULL)==(long)p);
        long result=live_remap(p,unmap_page,unmap_page*2,MREMAP_MAYMOVE,NULL);
        assert((unsigned long)result<(unsigned long)-4095);
        p=(char *)result;
        assert(p[0]==42 && !p[unmap_page]);
        p[unmap_page]=43;
        assert(live_remap(p+1,unmap_page,unmap_page,0,NULL)==-EINVAL);
        assert(live_remap(p,unmap_page,0,MREMAP_MAYMOVE,NULL)==-EINVAL);
    }
    assert(!munmap(p,unmap_page*2));
}
static void remap_signal(int signal) {
    (void)signal;
    int saved=errno;
    remap_loop(1);
    ++unmap_signals;
    errno=saved;
}
static void native_remap(void) {
    alarm(30);
    unmap_page=(size_t)sysconf(_SC_PAGESIZE);
    remap_loop(4096);
    struct sigaction sa={.sa_handler=remap_signal};
    sigemptyset(&sa.sa_mask);
    assert(!sigaction(SIGALRM,&sa,NULL));
    struct itimerval timer={.it_interval={.tv_usec=1000},.it_value={.tv_usec=1000}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    remap_loop(4096);
    timer=(struct itimerval){0};
    assert(!setitimer(ITIMER_REAL,&timer,NULL) && unmap_signals>0);
    sa.sa_handler=SIG_DFL;
    assert(!sigaction(SIGALRM,&sa,NULL));
    alarm(30);
    /* Fixed remapping must still use the owner and retain file provenance. */
    char *blocked=mmap(NULL,unmap_page*3,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(blocked!=MAP_FAILED && !mprotect(blocked+unmap_page,unmap_page,PROT_READ|PROT_WRITE));
    blocked[unmap_page]=41;
    long moved=live_remap(blocked+unmap_page,unmap_page,unmap_page*2,MREMAP_MAYMOVE,NULL);
    assert((unsigned long)moved<(unsigned long)-4095 && moved!=(long)(blocked+unmap_page));
    assert(*(char *)moved==41);
    assert(!munmap((void *)moved,unmap_page*2) && !munmap(blocked,unmap_page*3));
    int fd=open("/proc/self/exe",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    char *file=mmap(NULL,unmap_page,PROT_READ,MAP_PRIVATE,fd,0); assert(file!=MAP_FAILED);
    char *p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);
    assert(live_remap(file,unmap_page,unmap_page,MREMAP_MAYMOVE|MREMAP_FIXED,p)==(long)p);
    assert(!memcmp(p,"\177ELF",4));
    assert(!mprotect(p,unmap_page,PROT_NONE));
    assert(!mprotect(p,unmap_page,PROT_READ));
    assert(!memcmp(p,"\177ELF",4));
    assert(!munmap(p,unmap_page) && !close(fd));
    p=mmap(NULL,unmap_page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED); *p=44;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mremap,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(live_remap(p,unmap_page,unmap_page,MREMAP_MAYMOVE,NULL)==-EACCES);
    assert(*p==44 && !munmap(p,unmap_page));
    puts("PASS SVC native remap: resize, content, fixed file fallback, signal reentry and filter revocation");
}
static void image_protection(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    void *address=(void *)((uintptr_t)image_uid&~(page-1));
    long expected=getuid();
    for (unsigned i=0;i<64;++i) assert(image_uid()==expected);
    for (unsigned i=0;i<3;++i) {
        assert(!mprotect(address,page,PROT_NONE));
        assert(!mprotect(address,page,PROT_READ|PROT_EXEC));
        assert(image_uid()==expected);
        assert(!mprotect(address,page,PROT_READ));
        assert(!mprotect(address,page,PROT_READ|PROT_EXEC));
        assert(image_uid()==expected);
    }
}
static int exercise(void *unused) {
    (void)unused;
    long expected=syscall(number);
    for (unsigned i=0;i<1024;++i) assert(call()==expected);
    return 0;
}
static void joined(pid_t child) {
    int status=0;
    pid_t waited=child>0 ? waitpid(child,&status,0) : -1;
    if (waited!=child || !WIFEXITED(status) || WEXITSTATUS(status))
        fprintf(stderr,"mapping child=%d waited=%d status=%#x errno=%d\n",child,waited,status,errno);
    assert(child>0 && waited==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static atomic_uint ready, finished;
static long expected_uid;
static int protected;
enum { FILTER_WAITERS = 6 };
static int waiters, wait_provider, wake_pipes[FILTER_WAITERS][2];
static int watch_descriptor;
static pid_t waiter_tids[FILTER_WAITERS];
static const char *image_path;
static void *waiting_peer(void *argument) {
    unsigned index=(unsigned)(uintptr_t)argument;
    int epoll=-1;
    struct epoll_event event={.events=EPOLLIN,.data.fd=wake_pipes[index][0]};
    sigset_t mask;
    sigemptyset(&mask); sigaddset(&mask,SIGUSR2);
    if (index==1) {
        epoll=epoll_create1(EPOLL_CLOEXEC); assert(epoll>=0);
        assert(!epoll_ctl(epoll,EPOLL_CTL_ADD,event.data.fd,&event));
    }
    waiter_tids[index]=(pid_t)syscall(SYS_gettid);
    if (index==5) {
        char byte;
        struct iovec vector={&byte,1};
        struct msghdr message={.msg_iov=&vector,.msg_iovlen=1};
        /* Warm this same wrapper before the admitted receive must survive
         * another thread's TSYNC and its subsequent readiness publication. */
        assert(recvmsg(wake_pipes[index][0],&message,MSG_DONTWAIT)==-1 && errno==EAGAIN);
        atomic_fetch_add(&ready,1);
        assert(recvmsg(wake_pipes[index][0],&message,0)==1 && byte=='x');
        return NULL;
    }
    atomic_fetch_add(&ready,1);
    if (index==4) {
        char events[4096];
        assert(read(watch_descriptor,events,sizeof(events))>0);
        struct inotify_event *event=(void *)events;
        assert((event->mask&IN_CREATE) && !strcmp(event->name,"filter-wait.event"));
        return NULL;
    }
    if (index==1) {
        int result=epoll_wait(epoll,&event,1,-1);
        if (result!=1 || event.events!=EPOLLIN)
            fprintf(stderr,"filter waiter epoll result=%d events=%x errno=%d\n",result,event.events,errno);
        assert(result==1 && event.events==EPOLLIN);
    }
    else if (index==3) {
        fd_set set; FD_ZERO(&set); FD_SET(wake_pipes[index][0],&set);
        assert(pselect(wake_pipes[index][0]+1,&set,NULL,NULL,NULL,&mask)==1);
        assert(FD_ISSET(wake_pipes[index][0],&set));
    } else {
        struct pollfd items[65];
        for (unsigned i=0;i<65;++i) items[i]=(struct pollfd){.fd=-1};
        unsigned count=index==2 ? 65 : 1;
        items[count-1]=(struct pollfd){.fd=wake_pipes[index][0],.events=POLLIN};
        int result=index==2 ? ppoll(items,count,NULL,&mask) : poll(items,count,-1);
        assert(result==1 && items[count-1].revents==POLLIN);
    }
    int mask_result=pthread_sigmask(SIG_SETMASK,NULL,&mask);
    if (mask_result || sigismember(&mask,SIGUSR2))
        fprintf(stderr,"filter waiter index=%u mask-result=%d USR2=%d\n",
            index,mask_result,sigismember(&mask,SIGUSR2));
    assert(!mask_result && !sigismember(&mask,SIGUSR2));
    char byte=0;
    assert(read(wake_pipes[index][0],&byte,1)==1 && byte=='x');
    if (epoll>=0) assert(!close(epoll));
    return NULL;
}
static void waiter_blocked_tid(pid_t tid,long number,int peek_descriptor) {
    char path[96],data[256];
    snprintf(path,sizeof(path),"/proc/self/task/%d/syscall",tid);
    for (;;) {
        int fd=open(path,O_RDONLY|O_CLOEXEC); assert(fd>=0);
        ssize_t n=read(fd,data,sizeof(data)-1); assert(n>0);
        assert(!close(fd)); data[n]=0;
        long current=-1;
        if (sscanf(data,"%ld",&current)==1 && current==number) return;
        /* A resident watch read retains its original operation while waiting
         * non-consumingly on the same queue. Observe that exact kernel wait,
         * not an arbitrary futex or presumed startup delay. */
        unsigned long descriptor,buffer,size,flags;
        if (peek_descriptor>=0 && sscanf(data,"%ld %lx %lx %lx %lx",
                &current,&descriptor,&buffer,&size,&flags)==5 && current==SYS_recvfrom
                && (int)descriptor==peek_descriptor && size==1 && flags==MSG_PEEK) return;
        sched_yield();
    }
}
static void waiter_blocked(unsigned index,long number) {
    waiter_blocked_tid(waiter_tids[index],number,index==4 ? watch_descriptor : -1);
}
static atomic_int native_waiter_tid;
static int native_waiter_patched;
static void *native_fence_peer(void *unused) {
    (void)unused;
    assert(!unshare(CLONE_FILES));
    char byte;
    assert(!live_native_read(native_pipe[0],&byte,0,0));
    publication_check((uintptr_t)live_native_read,native_waiter_patched);
    atomic_store_explicit(&native_waiter_tid,(int)syscall(SYS_gettid),memory_order_release);
    long result=live_native_read(native_pipe[0],&byte,1,0);
    if (result!=1 || byte!='x')
        fprintf(stderr,"native wait fence: read=%ld byte=%u tid=%d\n",result,(unsigned char)byte,(int)syscall(SYS_gettid));
    assert(result==1 && byte=='x');
    assert(live_native_read(native_pipe[0],&byte,1,0)==-EACCES);
    return NULL;
}
static void native_wait_fence(int patched) {
    alarm(30); native_waiter_patched=patched;
    struct sock_filter allow[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog initial={1,allow};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&initial));
    assert(!pipe(native_pipe));
    pthread_t peer; assert(!pthread_create(&peer,NULL,native_fence_peer,NULL));
    while (!atomic_load_explicit(&native_waiter_tid,memory_order_acquire)) sched_yield();
    waiter_blocked_tid(atomic_load(&native_waiter_tid),SYS_read,-1);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_read,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)native_pipe[0],0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program));
    assert(write(native_pipe[1],"x",1)==1 && !pthread_join(peer,NULL));
    assert(!close(native_pipe[0]) && !close(native_pipe[1])); alarm(0);
    puts("PASS SVC native wait fence: admitted read survives TSYNC, next read denied");
}
static void *racing_peer(void *unused) {
    (void)unused;
    struct utsname name;
    for (unsigned i=0;i<64;++i) {
        assert(live_uid()==expected_uid);
        assert(!live_uname(&name) && !strcmp(name.sysname,"Linux"));
    }
    atomic_fetch_add(&ready,1);
    while (!atomic_load(&finished)) {
        long result=live_uid();
        assert(result==expected_uid || result==-EACCES);
        assert(!live_uname(&name) && !strcmp(name.sysname,"Linux"));
        result=live_open(AT_FDCWD,image_path,O_RDONLY,0);
        assert(result>=0 || result==-EACCES);
        if (result>=0) assert(!close((int)result));
    }
    for (unsigned i=0;i<64;++i) assert(live_uid()==(protected ? expected_uid : -EACCES));
    long result=live_open(AT_FDCWD,image_path,O_RDONLY,0);
    if (protected) { assert(result>=0); assert(!close((int)result)); }
    else assert(result==-EACCES);
    return NULL;
}
static void forked_filter(void) {
    expected_uid=getuid(); protected=1;
    pthread_t peers[4];
    for (unsigned i=0;i<4;++i) assert(!pthread_create(&peers[i],NULL,racing_peer,NULL));
    while (atomic_load(&ready)!=4) sched_yield();
    for (unsigned round=0;round<24;++round) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            alarm(15);
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            long result=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
            if (result) fprintf(stderr,"fork filter round=%u result=%ld errno=%d\n",round,result,errno);
            assert(!result);
            _exit(0);
        }
        joined(child);
    }
    atomic_store(&finished,1);
    for (unsigned i=0;i<4;++i) assert(!pthread_join(peers[i],NULL));
    puts("PASS SVC fork filter: active parent peers, copied frames and child filter admission");
}
static int dying_peer(void *unused) {
    (void)unused;
    for (unsigned i=0;i<1024;++i) assert(live_uid()==expected_uid);
    atomic_fetch_add(&ready,1);
    for (;;) assert(live_uid()==expected_uid);
}
static void exited_filter(void) {
    for (unsigned round=0;round<24;++round) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            alarm(15); expected_uid=getuid(); protected=1;
            pthread_t peers[2];
            for (unsigned i=0;i<2;++i) assert(!pthread_create(&peers[i],NULL,racing_peer,NULL));
            size_t size=1024*1024;
            void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            assert(stack!=MAP_FAILED);
            pid_t peer=clone(dying_peer,(char *)stack+size,CLONE_VM|SIGCHLD,NULL);
            assert(peer>0);
            while (atomic_load(&ready)!=3) sched_yield();
            assert(!kill(peer,SIGKILL));
            int status;
            assert(waitpid(peer,&status,0)==peer && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
            struct sock_fprog program={1,code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            long result=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
            if (result) fprintf(stderr,"exit filter round=%u result=%ld errno=%d\n",round,result,errno);
            assert(!result);
            atomic_store(&finished,1);
            for (unsigned i=0;i<2;++i) assert(!pthread_join(peers[i],NULL));
            _exit(0);
        }
        joined(child);
    }
    puts("PASS SVC exit filter: killed shared-mm peer and filter admission");
}
static void entry_transition_race(void) {
    for (unsigned round=0;round<24;++round) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            alarm(20);
            expected_uid=getuid();
            /* An unrelated FIFO requires full vector admission;
             * the selected pipe must still wait in the native kernel. */
            int provider=-1;
            if (wait_provider) {
                assert(!mkfifo("filter-wait.fifo",0600));
                provider=open("filter-wait.fifo",O_RDONLY|O_NONBLOCK|O_CLOEXEC);
                assert(provider>=0 && !unlink("filter-wait.fifo"));
            }
            pthread_t peers[4];
            pthread_t sleepers[FILTER_WAITERS];
            int filtered_epoll=-1;
            if (waiters) {
                watch_descriptor=inotify_init1(IN_CLOEXEC); assert(watch_descriptor>=0);
                assert(inotify_add_watch(watch_descriptor,".",IN_CREATE)>=0);
                filtered_epoll=epoll_create1(EPOLL_CLOEXEC); assert(filtered_epoll>=0);
                int source=provider>=0 ? provider : watch_descriptor;
                struct epoll_event event={.events=EPOLLIN,.data.fd=source};
                assert(!epoll_ctl(filtered_epoll,EPOLL_CTL_ADD,source,&event));
            }
            if (waiters) for (unsigned i=0;i<FILTER_WAITERS;++i) {
                if (i==5) assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,wake_pipes[i]));
                else assert(!pipe2(wake_pipes[i],O_CLOEXEC));
                assert(!pthread_create(&sleepers[i],NULL,waiting_peer,(void *)(uintptr_t)i));
            }
            for (unsigned i=0;i<4;++i) assert(!pthread_create(&peers[i],NULL,racing_peer,NULL));
            while (atomic_load(&ready)!=4+(waiters ? FILTER_WAITERS : 0)) sched_yield();
            if (waiters) {
                /* Observe actual kernel admission, not a presumed startup delay. */
                waiter_blocked(2,SYS_ppoll);
                waiter_blocked(3,SYS_pselect6);
                waiter_blocked(4,SYS_read);
            }
            if (protected) {
                assert(!prctl(PR_SET_DUMPABLE,0,0,0,0));
                assert(!prctl(PR_GET_DUMPABLE,0,0,0,0));
            } else {
                struct sock_filter code[]={
                    BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,1),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
                    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,1),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
                    BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
                };
                struct sock_fprog program={sizeof(code)/sizeof(*code),code};
                assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
                long result=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program);
                if (result) fprintf(stderr,"filter race round=%u TSYNC result=%ld errno=%d\n",round,result,errno);
                assert(!result);
            }
            if (waiters) {
                /* The shared table requires exact FD custody even when the
                 * application denies the service's unrelated gettid call. */
                struct epoll_event event;
                assert(epoll_wait(filtered_epoll,&event,1,0)==0);
                assert(epoll_wait(filtered_epoll,&event,1,1)==0);
                struct timespec timeout={.tv_nsec=1000000};
                assert(syscall(SYS_epoll_pwait2,filtered_epoll,&event,1,&timeout,NULL,8)==0);
                assert(!close(filtered_epoll));
            }
            atomic_store(&finished,1);
            if (waiters) assert(!mkdir("filter-wait.event",0700));
            if (waiters) for (unsigned i=0;i<FILTER_WAITERS;++i) {
                assert(write(wake_pipes[i][1],"x",1)==1);
                assert(!pthread_join(sleepers[i],NULL));
                assert(!close(wake_pipes[i][0]) && !close(wake_pipes[i][1]));
            }
            if (waiters) assert(!close(watch_descriptor) && !rmdir("filter-wait.event"));
            if (provider>=0) assert(!close(provider));
            for (unsigned i=0;i<4;++i) assert(!pthread_join(peers[i],NULL));
            if (protected) {
                assert(!prctl(PR_GET_DUMPABLE,0,0,0,0));
                pid_t next=fork(); assert(next>=0);
                if (!next) {
                    assert(!prctl(PR_GET_DUMPABLE,0,0,0,0));
                    racing_peer(NULL);
                    _exit(0);
                }
                joined(next);
                assert(!prctl(PR_GET_DUMPABLE,0,0,0,0));
                /* Restore only after protected operations and fork, so the
                 * harness can read final resident-entry counters at exit. */
                assert(!prctl(PR_SET_DUMPABLE,1,0,0,0));
            }
            _exit(0);
        }
        joined(child);
    }
    puts(protected ? "PASS SVC protected race: 24 transitions, live peers and protected fork"
        : "PASS SVC filter race: 24 TSYNC installs, live peers, forbidden helper and post-filter denial");
}
static void distant_sites(int patched,int filtered) {
    if (filtered) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,7,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    long uid=getuid();
    enum { COUNT=24 };
    void *sites[COUNT];
    for (unsigned i=0;i<COUNT;++i) {
        void *address=(void *)(UINT64_C(0x3000000000)+(uint64_t)i*0x20000000);
        sites[i]=mmap(address,page,PROT_READ|PROT_WRITE,
            MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,-1,0);
        assert(sites[i]==address);
        unsigned nr=(i&1) ? SYS_openat : SYS_getuid;
        const uint32_t code[]={0xd2800008|(nr<<5),0xd4000001,0xd65f03c0};
        memcpy(address,code,sizeof(code));
        __builtin___clear_cache(address,(char *)address+sizeof(code));
        assert(!mprotect(address,page,PROT_READ|PROT_EXEC));
        long (*invoke)(long,const char *,long)=address;
        long argument=(i&1) ? AT_FDCWD : 0,expected=(i&1) ? -ENOENT : uid;
        assert(invoke(argument,"/absent-svc-fixture",O_RDONLY)==expected);
        publication_check((uintptr_t)address,patched);
        for (unsigned j=0;j<32;++j) assert(invoke(argument,"/absent-svc-fixture",O_RDONLY)==expected);
        if (filtered && !(i&1)) assert(invoke(7,NULL,0)==-EACCES);
    }
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        for (unsigned i=0;i<COUNT;++i)
            assert(((long (*)(long,const char *,long))sites[i])((i&1) ? AT_FDCWD : 0,
                "/absent-svc-fixture",O_RDONLY)==((i&1) ? -ENOENT : uid));
        _exit(0);
    }
    joined(child);
    for (unsigned i=0;i<COUNT;++i) assert(!munmap(sites[i],page));
    puts("PASS SVC distant sites: 24 regions, first-call publication, policy denial and fork");
}
int main(int argc,char **argv) {
    if (argc>1 && !strcmp(argv[1],"debugger-rearm")) {
        debugger_rearm(argc>2 && !strcmp(argv[2],"patched"),argc>3); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"distant-sites")) {
        distant_sites(argc>2 && !strcmp(argv[2],"patched"),argc>3); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"task-signals")) { task_signal_completion(); return 0; }
    if (argc>1 && !strcmp(argv[1],"task-signals-exec")) { task_signal_exec(argv[0]); return 0; }
    if (argc>1 && !strcmp(argv[1],"first-encounter")) {
        first_encounter(argc>2 && !strcmp(argv[2],"patched"),argc>3); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"filter-origin")) { filter_origin(); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-readiness")) { filter_readiness(SECCOMP_RET_ERRNO|EACCES); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-readiness-trace")) { filter_readiness(SECCOMP_RET_TRACE|13); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-readiness-notify")) { filter_readiness(SECCOMP_RET_USER_NOTIF); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-actions")) { filter_actions(); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-exec")) { filter_exec(argv[0],0,-1); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-exec-child")) { filter_exec(argv[0],1,-1); return 0; }
    if (argc>1 && (!strcmp(argv[1],"filter-exec-waits") || !strcmp(argv[1],"filter-exec-waits-child"))) {
        filter_exec(argv[0],!strcmp(argv[1],"filter-exec-waits-child"),argc>2 && !strcmp(argv[2],"patched"));
        return 0;
    }
    if (argc>1 && !strcmp(argv[1],"filter-probe")) { filter_probe(argv[0],0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels")) { channel_threads(argv[0],0,0,0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels-signals")) { channel_signals=1; channel_threads(argv[0],0,0,0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels-signals-filter")) { channel_signals=1; channel_threads(argv[0],1,0,0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels-filter")) { channel_threads(argv[0],1,0,0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels-many")) { channel_threads(argv[0],0,1,0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels-many-filter")) { channel_threads(argv[0],1,1,0); return 0; }
    if (argc>1 && !strcmp(argv[1],"channels-full-filter")) { channel_threads(argv[0],1,0,1); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-probe-exec")) { filter_probe(argv[0],1); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-probe-exec-thread")) { filter_probe(argv[0],2); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-probe-child")) { filter_exec(argv[0],0,-1); return 0; }
    image_path=argv[0];
    if (argc>1 && !strcmp(argv[1],"native-waits")) {
        native_waits(argc>2 && !strcmp(argv[2],"patched")); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"native-wait-fence")) {
        native_wait_fence(argc>2 && !strcmp(argv[2],"patched")); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"native-unmap")) { native_unmap(); return 0; }
    if (argc>1 && !strcmp(argv[1],"native-protect")) { native_protect(); return 0; }
    if (argc>1 && !strcmp(argv[1],"native-map")) { native_map(); return 0; }
    if (argc>1 && !strcmp(argv[1],"native-remap")) { native_remap(); return 0; }
    if (argc>1 && !strcmp(argv[1],"protected-race")) { protected=1; entry_transition_race(); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-race")) { entry_transition_race(); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-wait")) { waiters=1; entry_transition_race(); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-wait-provider")) {
        waiters=wait_provider=1; entry_transition_race(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"filter-fork")) { forked_filter(); return 0; }
    if (argc>1 && !strcmp(argv[1],"filter-exit")) { exited_filter(); return 0; }
    image_protection();
    pid_t image_child=fork(); assert(image_child>=0);
    if (!image_child) { image_protection(); _exit(0); }
    joined(image_child);
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    uintptr_t hint=((uintptr_t)main+0x2000000)&~(page-1);
    void *code=mmap((void *)hint,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(code!=MAP_FAILED);
    void *stack=mmap(NULL,1024*1024,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    call=(long (*)(void))code;
    const unsigned numbers[]={SYS_getpid,SYS_getppid,SYS_getuid,SYS_getgid};
    for (unsigned version=0;version<sizeof(numbers)/sizeof(*numbers);++version) {
        number=numbers[version];
        void *copy=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(copy!=MAP_FAILED);
        uint32_t program[]={0xd2800008|(numbers[version]<<5),0xd4000001,0xd65f03c0};
        memcpy(copy,program,sizeof(program));
        __builtin___clear_cache(copy,(char *)copy+sizeof(program));
        assert(!mprotect(copy,page,PROT_READ|PROT_EXEC));
        assert(mremap(copy,page,page,MREMAP_FIXED|MREMAP_MAYMOVE,code)==code);
        exercise(NULL);
        /* SIGCHLD selects PTRACE_EVENT_FORK even with CLONE_VM. The mm
         * owner must follow clone flags, not the ptrace event's name. */
        joined(clone(exercise,(char *)stack+1024*1024,CLONE_VM|SIGCHLD,NULL));
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(exercise(NULL));
        joined(child); exercise(NULL);
    }
    assert(!munmap(stack,1024*1024) && !munmap(code,page));
    puts("PASS SVC mapping: ELF protection, address reuse, fork copies and CLONE_VM with SIGCHLD");
}
