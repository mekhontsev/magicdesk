#define _GNU_SOURCE
#include "cooperative_entry.h"
#include "../shroot/src/cooperative_channel.h"
#include "../shroot/src/posix_acl.h"
#include "../shroot/src/interception.h"
#include "test_syscall_sites.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/landlock.h>
#include <linux/seccomp.h>
#include <linux/stat.h>
#include <linux/openat2.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <setjmp.h>
#include <sys/socket.h>
#include <sys/sem.h>
#include <sys/inotify.h>
#include <sys/utsname.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/vfs.h>
#include <sys/xattr.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static const struct md_entry_api *api;
static struct md_entry_client client;
static const char *path="/tmp/cooperative-file";
static unsigned direct_calls, fallback_calls;
static enum md_entry_outcome stat_try(struct md_entry_client *c,struct stat *st,long *result) {
    struct md_entry_call call={.number=SYS_newfstatat,.args={AT_FDCWD,(uintptr_t)path,(uintptr_t)st,0}};
    enum md_entry_outcome outcome=md_entry_invoke(api,c,&call);
    *result=call.result; return outcome;
}
static long call6(int nr,unsigned long a,unsigned long b,unsigned long c,unsigned long d,unsigned long e,unsigned long f) {
    struct md_entry_call call={.number=nr,.args={a,b,c,d,e,f}};
    enum md_entry_outcome outcome=md_entry_invoke(api,&client,&call);
    assert(outcome!=MD_ENTRY_UNKNOWN);
    if (outcome==MD_ENTRY_COMPLETED) { ++direct_calls; return call.result; }
    ++fallback_calls;
    long result=syscall(nr,a,b,c,d,e,f);
    return result<0 ? -errno : result;
}
/* Separate executed sites: a compact close must discover its own gate, while
 * the shared FD site must admit each subsequently observed syscall number. */
__attribute__((noinline)) static long discovery_close(int fd) {
    register long x0 __asm__("x0")=fd,x8 __asm__("x8")=SYS_close;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8) : "memory","cc");
    return x0;
}
__attribute__((noinline)) static long discovery_fd(int number,long a,long b,long c) {
    register long x0 __asm__("x0")=a,x1 __asm__("x1")=b,x2 __asm__("x2")=c,x8 __asm__("x8")=number;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x8) : "memory","cc");
    return x0;
}
static void fd_discovery(void) {
    const char *name="/tmp/fd-discovery";
    int retired=-1;
    for (unsigned i=0;i<128;++i) {
        int fd=open(name,O_CREAT|O_RDWR,0600); assert(fd>=0);
        int alias=(int)discovery_fd(SYS_dup,fd,0,0); assert(alias>=0 && alias!=fd);
        assert(!discovery_fd(SYS_fcntl,alias,F_SETFD,FD_CLOEXEC));
        assert(discovery_fd(SYS_fcntl,alias,F_GETFD,0)==FD_CLOEXEC);
        long flags=discovery_fd(SYS_fcntl,alias,F_GETFL,0);
        assert(flags>=0 && (flags&O_ACCMODE)==O_RDWR);
        assert(discovery_fd(SYS_lseek,alias,0,SEEK_CUR)==0);
        for (unsigned seek=1;seek<=64;++seek) {
            assert(discovery_fd(SYS_lseek,alias,1,SEEK_CUR)==seek);
            assert(discovery_fd(SYS_lseek,alias,-1,SEEK_SET)==-EINVAL);
        }
        assert(lseek(fd,0,SEEK_CUR)==64);
        assert(!discovery_close(alias) && !discovery_close(fd));
        retired=fd;
    }
    /* Error admission must not warm the valid-close site for this test. */
    assert(discovery_close(retired)==-EBADF && discovery_close(-1)==-EBADF);
    int fd=open(name,O_RDWR); assert(fd>=0);
    assert(discovery_fd(SYS_lseek,fd,0,SEEK_SET)==0);
    assert(discovery_fd(SYS_lseek,fd,1,SEEK_CUR)==1);
    int pipes[2]; assert(!pipe(pipes));
    assert(dup3(pipes[0],fd,O_CLOEXEC)==fd);
    assert(discovery_fd(SYS_lseek,fd,0,SEEK_CUR)==-ESPIPE);
    assert(!close(pipes[0]) && !close(pipes[1]) && !close(fd));
    int directory=open("/tmp",O_DIRECTORY|O_RDONLY); assert(directory>=0);
    char entries[4096];
    assert(discovery_fd(SYS_lseek,directory,0,SEEK_SET)==0);
    assert(syscall(SYS_getdents64,directory,entries,sizeof(entries))>0);
    assert(discovery_fd(SYS_lseek,directory,0,SEEK_CUR)>0);
    assert(!close(directory));
    assert(!unlink(name));
    puts("PASS native FD discovery: executed close, dup, fcntl and lseek sites without explicit entry API");
}
static void site_discovery(void) {
    int fd=open(path,O_CREAT|O_RDWR,0600); assert(fd>=0);
    assert(write(fd,"payload",7)==7 && !close(fd));
    for (unsigned site=0;site<MD_TEST_SYSCALL_SITES;++site) {
        md_test_syscall_fn call=md_test_syscall_site(site);
        for (unsigned repeat=0;repeat<16;++repeat) {
            struct stat st;
            assert(!call(AT_FDCWD,(uintptr_t)path,(uintptr_t)&st,0,0,0,SYS_newfstatat));
            assert(st.st_size==7 && S_ISREG(st.st_mode));
        }
    }
    assert(!unlink(path));
    puts("PASS native site discovery: 32 distinct SVCs without explicit entry API");
}
static void proc_discovery(void) {
    int directory=open("/proc/self/ns",O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(directory>=0);
    char entries[4096],first[4096];
    long expected=0;
    for (unsigned site=0;site<MD_TEST_SYSCALL_SITES;++site) {
        md_test_syscall_fn call=md_test_syscall_site(site);
        for (unsigned repeat=0;repeat<2;++repeat) {
            assert(lseek(directory,0,SEEK_SET)==0);
            long count=call(directory,(uintptr_t)entries,sizeof(entries),0,0,0,SYS_getdents64);
            assert(count>0 && count<=(long)sizeof(entries));
            if (!expected) { expected=count; memcpy(first,entries,(size_t)count); }
            assert(count==expected && !memcmp(entries,first,(size_t)count));
            assert(call(directory,(uintptr_t)entries,sizeof(entries),0,0,0,SYS_getdents64)==0);
        }
    }
    assert(!close(directory));
    puts("PASS native proc discovery: cold and warm sites retain directory contents, cursor and request ownership");
}
static void reap(pid_t child) {
    int status;
    while (waitpid(child,&status,0)<0) assert(errno==EINTR);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) fprintf(stderr,"child status=%#x\n",status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
struct cursor_peer { int target, source, command, reply; };
static int cursor_replace(void *argument) {
    struct cursor_peer *p=argument;
    char byte;
    assert(read(p->command,&byte,1)==1);
    assert(dup3(p->source,p->target,O_CLOEXEC)==p->target);
    assert(write(p->reply,"x",1)==1);
    assert(read(p->command,&byte,1)==1);
    return 0;
}
static volatile sig_atomic_t cursor_signals;
static void cursor_signal(int sig) { (void)sig; ++cursor_signals; }
static void cursor_contracts(void) {
    const char *name="/tmp/cursor-lease";
    int fd=open(name,O_CREAT|O_RDWR,0600); assert(fd>=0);
    assert(!close(fd));
    for (unsigned i=0;i<64;++i) {
        int flags=i&1 ? FD_CLOEXEC : 0;
        fd=open(name,O_RDWR|(flags ? O_CLOEXEC : 0)); assert(fd>=0);
        assert(discovery_fd(SYS_fcntl,fd,F_GETFD,0)==flags);
        assert(!discovery_fd(SYS_fcntl,fd,F_SETFD,flags));
        assert((discovery_fd(SYS_fcntl,fd,F_GETFL,0)&O_ACCMODE)==O_RDWR);
        assert(!discovery_fd(SYS_fcntl,fd,F_SETFD,flags^FD_CLOEXEC));
        assert(discovery_fd(SYS_fcntl,fd,F_GETFD,0)==(flags^FD_CLOEXEC));
        assert(!discovery_close(fd));
        assert(discovery_fd(SYS_fcntl,fd,F_GETFD,0)==-EBADF);
    }
    fd=open(name,O_RDWR); assert(fd>=0);
    struct sigaction action={.sa_handler=cursor_signal,.sa_flags=SA_RESTART},previous;
    sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGALRM,&action,&previous));
    struct itimerval timer={.it_interval={0,100},.it_value={0,100}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    for (unsigned i=1;i<=16384;++i) assert(discovery_fd(SYS_lseek,fd,1,SEEK_CUR)==i);
    timer=(struct itimerval){0};
    assert(!setitimer(ITIMER_REAL,&timer,NULL) && cursor_signals>0);
    assert(!sigaction(SIGALRM,&previous,NULL)); alarm(60);
    assert(lseek(fd,0,SEEK_CUR)==16384);
    int command[2],reply[2],replacement[2];
    assert(!pipe(command) && !pipe(reply) && !pipe(replacement));
    struct cursor_peer peer={fd,replacement[0],command[0],reply[1]};
    size_t size=512*1024;
    void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    assert(discovery_fd(SYS_lseek,fd,0,SEEK_SET)==0);
    pid_t child=clone(cursor_replace,(char *)stack+size,CLONE_FILES|SIGCHLD,&peer);
    assert(child>=0);
    /* Read/write stay native: no unrelated intercepted call may revoke an
     * incorrectly issued exclusive-table lease after these seeks. */
    assert(discovery_fd(SYS_lseek,fd,1,SEEK_CUR)==1);
    assert(discovery_fd(SYS_lseek,fd,1,SEEK_CUR)==2);
    assert(write(command[1],"x",1)==1);
    char byte; assert(read(reply[0],&byte,1)==1);
    assert(discovery_fd(SYS_fcntl,fd,F_GETFD,0)==FD_CLOEXEC);
    assert(discovery_fd(SYS_lseek,fd,0,SEEK_CUR)==-ESPIPE);
    assert(write(command[1],"x",1)==1);
    reap(child); assert(!munmap(stack,size));
    assert(!close(fd));
    for (unsigned i=0;i<2;++i) { close(command[i]); close(reply[i]); close(replacement[i]); }
    child=fork(); assert(child>=0);
    if (!child) {
        fd=open(name,O_RDWR); assert(fd>=0);
        assert(discovery_fd(SYS_lseek,fd,0,SEEK_SET)==0);
        assert(discovery_fd(SYS_lseek,fd,1,SEEK_CUR)==1);
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_lseek,1,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fcntl,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        for (unsigned i=0;i<64;++i) {
            assert(discovery_fd(SYS_lseek,fd,1,SEEK_CUR)==-EACCES);
            assert(discovery_fd(SYS_fcntl,fd,F_GETFD,0)==-EACCES);
            assert(discovery_fd(SYS_fcntl,fd,F_SETFD,0)==-EACCES);
        }
        assert(!close(fd)); _exit(0);
    }
    reap(child); assert(!unlink(name));
    puts("PASS native cursor lease: flags, FD reuse, signal-exact offsets, shared-table replacement and seccomp revocation");
}
struct jit_entry_peer { atomic_int stage; };
static void jit_stage(struct jit_entry_peer *peer,int stage) {
    atomic_store(&peer->stage,stage);
    assert(syscall(SYS_futex,&peer->stage,FUTEX_WAKE,1,0,0,0)>=0);
}
static void jit_wait(struct jit_entry_peer *peer,int stage) {
    /* EVENT_WAIT: peer/repair completion; the fixture alarm bounds a lost resume. */
    for (int current;(current=atomic_load(&peer->stage))<stage;) {
        long result=syscall(SYS_futex,&peer->stage,FUTEX_WAIT,current,0,0,0);
        assert(!result || errno==EINTR || errno==EAGAIN);
    }
}
static void *jit_entry_waiter(void *context) {
    struct jit_entry_peer *peer=context;
    struct md_entry_client local={0}; struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    jit_stage(peer,1); jit_wait(peer,2);
    /* No intercepted syscall may repair stale broker state before this call. */
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
    return NULL;
}
struct jit_internal_peer {
    int semaphore;
    struct jit_entry_peer completed;
};
static void *jit_internal_waiter(void *context) {
    struct jit_internal_peer *peer=context;
    struct sembuf take={.sem_op=-1};
    long result=syscall(SYS_semop,peer->semaphore,&take,1);
    if (result) fprintf(stderr,"JIT internal wait result=%ld errno=%d\n",result,errno);
    assert(!result);
    jit_stage(&peer->completed,1);
    assert(syscall(SYS_semop,peer->semaphore,&take,1)==-1 && errno==EINTR);
    return NULL;
}
static void jit_entry_resume(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    for (unsigned i=0;i<8;++i) {
        int fd=memfd_create("cooperative-jit",MFD_CLOEXEC); assert(fd>=0);
        assert(!ftruncate(fd,page));
        unsigned *code=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
        assert(code!=MAP_FAILED && !close(fd));
        code[0]=0x528004a0; code[1]=0xd65f03c0; /* mov w0,37; ret */
        __builtin___clear_cache((char *)code,(char *)(code+2));
        struct jit_entry_peer peer={0}; pthread_t thread;
        assert(!pthread_create(&thread,NULL,jit_entry_waiter,&peer)); jit_wait(&peer,1);
        int semaphore=(int)syscall(SYS_semget,IPC_PRIVATE,1,IPC_CREAT|0600);
        assert(semaphore>=0 && !syscall(SYS_semctl,semaphore,0,SETVAL,0));
        struct jit_internal_peer waiter={.semaphore=semaphore};
        pthread_t internal;
        assert(!pthread_create(&internal,NULL,jit_internal_waiter,&waiter));
        long waiting;
        do { waiting=syscall(SYS_semctl,semaphore,0,GETNCNT,0); assert(waiting>=0); }
        while (!waiting);
        assert(!mprotect(code,page,PROT_READ|PROT_EXEC));
        int (*run)(void)=(void *)code; assert(run()==37);
        assert(syscall(SYS_semctl,semaphore,0,GETNCNT,0)==1);
        struct sembuf give={.sem_op=1};
        assert(!syscall(SYS_semop,semaphore,&give,1));
        jit_wait(&waiter.completed,1);
        /* EVENT_WAIT: the same repaired task is waiting again before the real
         * signal; the fixture alarm bounds a missing interruption. */
        do { waiting=syscall(SYS_semctl,semaphore,0,GETNCNT,0); assert(waiting>=0); }
        while (!waiting);
        assert(!pthread_kill(internal,SIGUSR2));
        assert(!pthread_join(internal,NULL) && !syscall(SYS_semctl,semaphore,0,IPC_RMID,0));
        jit_stage(&peer,2);
        assert(!pthread_join(thread,NULL) && !munmap(code,page));
    }
    puts("PASS cooperative JIT resume: interrupted peers retain direct entry after private-memory repair");
    puts("PASS JIT internal wait: memory repair does not manufacture application EINTR");
    puts("PASS JIT application signal: the repaired waiter still returns EINTR for a real signal");
}
static void *jit_busy_peer(void *context) {
    atomic_int *stop=context;
    struct md_entry_client local={0};
    while (!atomic_load(stop)) {
        const char *shared="/tmp/cooperative-jit-race";
        struct md_entry_call open_call={.number=SYS_openat,
            .args={AT_FDCWD,(uintptr_t)shared,O_CREAT|O_RDWR|O_CLOEXEC,0600}};
        enum md_entry_outcome outcome=md_entry_invoke(api,&local,&open_call);
        assert(outcome!=MD_ENTRY_UNKNOWN);
        int fd=outcome==MD_ENTRY_COMPLETED ? (int)open_call.result : open(shared,O_CREAT|O_RDWR|O_CLOEXEC,0600);
        assert(fd>=0);
        struct md_entry_call close_call={.number=SYS_close,.args={fd}};
        outcome=md_entry_invoke(api,&local,&close_call);
        assert(outcome!=MD_ENTRY_UNKNOWN);
        assert(!(outcome==MD_ENTRY_COMPLETED ? close_call.result : close(fd)));
        struct md_entry_call remove_call={.number=SYS_unlinkat,.args={AT_FDCWD,(uintptr_t)shared,0}};
        outcome=md_entry_invoke(api,&local,&remove_call);
        assert(outcome!=MD_ENTRY_UNKNOWN);
        long removed=outcome==MD_ENTRY_COMPLETED ? remove_call.result : unlink(shared) ? -errno : 0;
        assert(!removed || removed==-ENOENT);
    }
    return NULL;
}
static void jit_busy(void) {
    atomic_int stop=0;
    pthread_t peers[4];
    for (unsigned i=0;i<4;++i) assert(!pthread_create(&peers[i],NULL,jit_busy_peer,&stop));
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    for (unsigned i=0;i<128;++i) {
        int fd=memfd_create("cooperative-jit-busy",MFD_CLOEXEC); assert(fd>=0);
        assert(!ftruncate(fd,page));
        unsigned *code=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
        assert(code!=MAP_FAILED && !close(fd));
        code[0]=0x528004a0; code[1]=0xd65f03c0;
        __builtin___clear_cache((char *)code,(char *)(code+2));
        assert(!mprotect(code,page,PROT_READ|PROT_EXEC));
        int (*run)(void)=(void *)code; assert(run()==37);
        assert(!munmap(code,page));
    }
    atomic_store(&stop,1);
    for (unsigned i=0;i<4;++i) assert(!pthread_join(peers[i],NULL));
    puts("PASS cooperative JIT busy: native FD plans retire during memory repair");
}
static void filter(unsigned flags) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_newfstatat,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,flags,&program));
}
static pthread_barrier_t filter_barrier;
static void *filtered_exec(void *executable) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,MD_ENTRY_BIND,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    char *argv[]={executable,"--filtered-exec",NULL};
    execv(executable,argv); _exit(99);
}
static void filter_exec(const char *executable) {
    for (unsigned nonleader=0;nonleader<2;++nonleader) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (!nonleader) filtered_exec((void *)executable);
            pthread_t peer;
            assert(!pthread_create(&peer,NULL,filtered_exec,(void *)executable));
            /* EVENT_WAIT: successful exec replaces the leader; return fails. */
            assert(!pthread_join(peer,NULL)); _exit(98);
        }
        reap(child);
    }
    puts("PASS filter exec: leader and nonleader retain registration revocation");
}
static void barrier(pthread_barrier_t *value) {
    int result=pthread_barrier_wait(value); assert(!result || result==PTHREAD_BARRIER_SERIAL_THREAD);
}
static void *filtered_peer(void *unused) {
    (void)unused;
    struct md_entry_client local={0},fresh={0}; struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    barrier(&filter_barrier); barrier(&filter_barrier);
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && result==-EACCES);
    assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && result==-EACCES);
    assert((fresh.opaque[MD_ENTRY_CLIENT_CONTROL]>>1)==UINT32_MAX);
    assert(syscall(SYS_newfstatat,AT_FDCWD,path,&st,0)==-1 && errno==EACCES);
    return NULL;
}
static void filters(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client fresh={0}; struct stat st; long result;
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        pthread_t peer;
        assert(!pthread_barrier_init(&filter_barrier,NULL,2));
        assert(!pthread_create(&peer,NULL,filtered_peer,NULL)); barrier(&filter_barrier);
        filter(SECCOMP_FILTER_FLAG_TSYNC);
        barrier(&filter_barrier);
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && result==-EACCES);
        assert(syscall(SYS_newfstatat,AT_FDCWD,path,&st,0)==-1 && errno==EACCES);
        assert(!pthread_join(peer,NULL));
        _exit(0);
    }
    reap(child); puts("PASS cooperative entry: TSYNC peer/fresh clients use kernel admission without forbidden helper syscalls");
}
static long required_call(struct md_entry_client *local,int nr,const uint64_t args[6]) {
    struct md_entry_call call={.number=nr};
    memcpy(call.args,args,sizeof(call.args));
    assert(md_entry_invoke(api,local,&call)==MD_ENTRY_COMPLETED);
    return call.result;
}
static void scalar_credentials(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client local={0};
        const int calls[]={SYS_getuid,SYS_geteuid,SYS_getgid,SYS_getegid};
        assert(required_call(&local,SYS_getuid,(uint64_t[6]){0})==getuid());
        struct md_entry_channel *channel=(void *)local.opaque[MD_ENTRY_CLIENT_CHANNEL];
        assert(channel);
        for (unsigned round=0;round<4;++round) {
            if (round==1) {
                assert(!setresgid(7,8,0));
                assert(!setresuid(9,10,0));
            } else if (round==2) {
                assert(!seteuid(0));
                assert(!setresuid(0,0,0) && !setresgid(0,0,0));
            } else if (round==3) {
                assert(!unshare(CLONE_NEWUSER));
            }
            for (unsigned i=0;i<4;++i) {
                uint64_t sequence=channel->sequence;
                long result=required_call(&local,calls[i],(uint64_t[6]){0});
                if (round<3) assert(channel->sequence==sequence+1);
                /* Native comparison can itself use lazy machine entry and
                 * the same thread channel. Check this call's increment first. */
                assert(result==syscall(calls[i]));
            }
        }
        _exit(0);
    }
    reap(child);
    puts("PASS cooperative entry: scalar credentials observe live IDs and user namespace translation");
}
static unsigned live_descriptors(void) {
    DIR *directory=opendir("/proc/self/fd"); assert(directory);
    unsigned count=0; struct dirent *entry;
    while ((entry=readdir(directory))) if (entry->d_name[0]!='.') ++count;
    assert(!closedir(directory)); return count;
}
static pthread_barrier_t native_barrier;
static struct md_entry_client *native_client;
static int native_action, native_checks;
static void native_signal(int number) { (void)number; }
static void native_checkpoint(void) {
    struct md_entry_channel *channel=(void *)native_client->opaque[MD_ENTRY_CLIENT_CHANNEL];
    if (channel && atomic_load(&channel->state)==MD_ENTRY_NATIVE) {
        assert(atomic_load(&channel->native.state)==MD_NATIVE_READY); ++native_checks;
        if (native_action==1) assert(!raise(SIGUSR2));
        else if (native_action==2) { barrier(&native_barrier); barrier(&native_barrier); }
        else if (native_action==3) _exit(0);
    }
}
/* Stop at the explicit pre-effect handoff to inspect discarded ownership,
 * before the original caller is allowed to execute its kernel operation. */
static void native_declined(struct md_entry_call *call) {
    enum md_entry_outcome outcome=api->try_syscall(&client,call);
    for (;;) {
        md_entry_step_fn next=call->next;
        if (outcome==MD_ENTRY_CHECK_CANCEL) { assert(next && call->cancel); call->cancel(); }
        else if (outcome==MD_ENTRY_NATIVE_EFFECT) { assert(next && call->effect); call->effect(call->effect_context); }
        else {
            assert(outcome==MD_ENTRY_KERNEL_CALL && call->kernel && !next);
            return;
        }
        outcome=next(&client,call);
    }
}
static void *replace_pinned_file(void *unused) {
    (void)unused;
    struct md_entry_client local={0}; struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    barrier(&native_barrier);
    barrier(&native_barrier);
    /* Absolute worker mutations do not borrow the opener's file-table turn.
     * This checkpoint deliberately holds that turn before the native effect. */
    assert(!required_call(&local,SYS_renameat2,(uint64_t[]){AT_FDCWD,(uintptr_t)"/tmp/entry-pinned",
        AT_FDCWD,(uintptr_t)"/tmp/entry-old",0,0}));
    assert(!required_call(&local,SYS_renameat2,(uint64_t[]){AT_FDCWD,(uintptr_t)"/tmp/entry-replacement",
        AT_FDCWD,(uintptr_t)"/tmp/entry-pinned",0,0}));
    assert(!required_call(&local,SYS_unlinkat,(uint64_t[]){AT_FDCWD,(uintptr_t)"/tmp/entry-old",0,0,0,0}));
    barrier(&native_barrier); return NULL;
}
static void native_descriptors(void) {
    unsigned before=live_descriptors();
    int descriptors[192];
    for (unsigned i=0;i<192;++i) {
        int fd=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_RDONLY|O_CLOEXEC,0,0,0});
        assert(fd>=0); descriptors[i]=fd;
        assert(required_call(&client,SYS_fcntl,(uint64_t[]){fd,F_GETFD,0,0,0,0})==FD_CLOEXEC);
        assert(!required_call(&client,SYS_fcntl,(uint64_t[]){fd,F_SETFD,0,0,0,0}));
        assert(fcntl(fd,F_GETFD)==0);
        assert(!required_call(&client,SYS_fcntl,(uint64_t[]){fd,F_SETFD,FD_CLOEXEC,0,0,0}));
        assert(fcntl(fd,F_GETFD)==FD_CLOEXEC);
    }
    for (unsigned i=0;i<192;++i) {
        int fd=descriptors[i],copy=required_call(&client,SYS_fcntl,(uint64_t[]){fd,F_DUPFD_CLOEXEC,512,0,0,0});
        assert(copy>=512 && fcntl(copy,F_GETFD)==FD_CLOEXEC);
        int alias=required_call(&client,SYS_dup,(uint64_t[]){fd,0,0,0,0,0});
        assert(alias>=0 && !fcntl(alias,F_GETFD));
        assert(required_call(&client,SYS_dup3,(uint64_t[]){copy,alias,O_CLOEXEC,0,0,0})==alias);
        assert(fcntl(alias,F_GETFD)==FD_CLOEXEC);
        assert(required_call(&client,SYS_dup3,(uint64_t[]){alias,alias,0,0,0,0})==-EINVAL);
        assert(!required_call(&client,SYS_fcntl,(uint64_t[]){alias,F_SETFL,O_NONBLOCK,0,0,0}));
        assert(required_call(&client,SYS_fcntl,(uint64_t[]){fd,F_GETFL,0,0,0,0})&O_NONBLOCK);
        assert(!required_call(&client,SYS_fcntl,(uint64_t[]){fd,F_SETFL,0,0,0,0}));
        assert(!(fcntl(alias,F_GETFL)&O_NONBLOCK));
        assert(required_call(&client,SYS_lseek,(uint64_t[]){alias,2,SEEK_SET,0,0,0})==2);
        assert(lseek(fd,0,SEEK_CUR)==2);
        assert(required_call(&client,SYS_lseek,(uint64_t[]){fd,-1,SEEK_SET,0,0,0})==-EINVAL);
        assert(!required_call(&client,SYS_close,(uint64_t[]){alias,0,0,0,0,0}));
        struct stat st; assert(!fstat(copy,&st) && st.st_size==7);
        assert(!required_call(&client,SYS_close,(uint64_t[]){fd,0,0,0,0,0}));
        assert(required_call(&client,SYS_close,(uint64_t[]){fd,0,0,0,0,0})==-EBADF);
        assert(!syscall(SYS_close_range,copy,copy,0));
    }
    assert(live_descriptors()==before);
    int watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(watch>=0);
    assert(inotify_add_watch(watch,path,IN_OPEN|IN_CLOSE_NOWRITE)>0);
    int watched=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_RDONLY,0,0,0});
    assert(watched>=0 && !required_call(&client,SYS_close,(uint64_t[]){watched,0,0,0,0,0}));
    char events[1024] __attribute__((aligned(8))); ssize_t bytes=read(watch,events,sizeof(events));
    assert(bytes>0); unsigned opens=0,closes=0;
    for (size_t at=0;at<(size_t)bytes;) {
        struct inotify_event *e=(void *)(events+at);
        opens+=!!(e->mask&IN_OPEN); closes+=!!(e->mask&IN_CLOSE_NOWRITE);
        at+=sizeof(*e)+e->len;
    }
    assert(opens==1 && closes==1);
    assert(read(watch,events,sizeof(events))==-1 && errno==EAGAIN && !close(watch));
    puts("PASS native FD watches: exactly one open and one close event");
    const char *writable="/tmp/entry-writable";
    int initial=open(writable,O_CREAT|O_EXCL|O_RDWR,0600); assert(initial>=0);
    assert(write(initial,"old",3)==3 && !close(initial));
    watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(watch>=0);
    assert(inotify_add_watch(watch,writable,IN_OPEN|IN_CLOSE_WRITE|IN_MODIFY)>0);
    int writer=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)writable,O_RDWR|O_APPEND|O_CREAT,0600,0,0});
    assert(writer>=0 && (fcntl(writer,F_GETFL)&(O_ACCMODE|O_APPEND))==(O_RDWR|O_APPEND));
    assert(write(writer,"!",1)==1);
    assert(!required_call(&client,SYS_close,(uint64_t[]){writer,0,0,0,0,0}));
    bytes=read(watch,events,sizeof(events)); assert(bytes>0); opens=closes=0;
    unsigned modifications=0;
    for (size_t at=0;at<(size_t)bytes;) {
        struct inotify_event *e=(void *)(events+at);
        opens+=!!(e->mask&IN_OPEN); closes+=!!(e->mask&IN_CLOSE_WRITE);
        modifications+=!!(e->mask&IN_MODIFY); at+=sizeof(*e)+e->len;
    }
    assert(opens==1 && closes==1 && modifications==1);
    assert(read(watch,events,sizeof(events))==-1 && errno==EAGAIN && !close(watch));
    writer=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)writable,O_WRONLY|O_TRUNC,0,0,0});
    struct stat truncated; assert(writer>=0 && !fstat(writer,&truncated) && !truncated.st_size);
    assert(!required_call(&client,SYS_close,(uint64_t[]){writer,0,0,0,0,0}));
    native_client=&client; native_action=1;
    struct md_entry_call call={.number=SYS_openat,.args={AT_FDCWD,(uintptr_t)path,O_RDONLY|O_CLOEXEC},.cancel=native_checkpoint};
    native_declined(&call);
    assert(native_checks==1 && live_descriptors()==before);
    initial=open(writable,O_WRONLY); assert(initial>=0 && write(initial,"preserved",9)==9 && !close(initial));
    call.args[1]=(uintptr_t)writable; call.args[2]=O_WRONLY|O_TRUNC;
    native_declined(&call);
    assert(!stat(writable,&truncated) && truncated.st_size==9);
    assert(live_descriptors()==before && !unlink(writable));
    writer=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)writable,O_CREAT|O_RDWR|O_TRUNC,0600,0,0});
    assert(writer>=0 && !fstat(writer,&truncated) && truncated.st_size==0);
    assert(write(writer,"created",7)==7);
    assert(!required_call(&client,SYS_close,(uint64_t[]){writer,0,0,0,0,0}));
    assert(!stat(writable,&truncated) && truncated.st_size==7 && !unlink(writable));
    call.args[2]=O_RDONLY|O_CLOEXEC;
    puts("PASS native writable plans: append flags, exact watch effects, truncate and pre-effect cancellation");
    int fd=open("/tmp/entry-pinned",O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0);
    assert(write(fd,"old",3)==3 && !close(fd));
    fd=open("/tmp/entry-replacement",O_CREAT|O_EXCL|O_RDWR,0600); assert(fd>=0);
    assert(write(fd,"replacement",11)==11 && !close(fd));
    assert(!pthread_barrier_init(&native_barrier,NULL,2)); pthread_t peer;
    assert(!pthread_create(&peer,NULL,replace_pinned_file,NULL));
    barrier(&native_barrier);
    native_action=2; call.args[1]=(uintptr_t)"/tmp/entry-pinned";
    assert(md_entry_invoke(api,&client,&call)==MD_ENTRY_COMPLETED && call.result>=0);
    fd=(int)call.result; char data[4]={0}; struct stat st;
    assert(read(fd,data,3)==3 && !strcmp(data,"old"));
    assert(!fstat(fd,&st) && st.st_size==3);
    assert(!required_call(&client,SYS_close,(uint64_t[]){fd,0,0,0,0,0}));
    assert(!pthread_join(peer,NULL) && !pthread_barrier_destroy(&native_barrier));
    assert(!unlink("/tmp/entry-pinned"));
    for (unsigned i=0;i<8;++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            struct md_entry_client local={0}; native_client=&local; native_action=3;
            call.args[1]=(uintptr_t)path;
            md_entry_invoke(api,&local,&call); _exit(90);
        }
        reap(child);
    }
    assert(live_descriptors()==before);
    puts("PASS native FD plans: 192 live opens, flags, dup, close_range, signal cancellation, replaced/unlinked pin and terminal retirement");
}
static void task_completion(void) {
    struct md_entry_client *local=&client;
    struct utsname names;
    assert(!required_call(local,SYS_uname,(uint64_t[]){(uintptr_t)&names,0,0,0,0,0}));
    assert(!strcmp(names.sysname,"Linux"));
    assert(required_call(local,SYS_getuid,(uint64_t[6]){0})==0);
    int pair[2];
    assert(!required_call(local,SYS_pipe2,(uint64_t[]){(uintptr_t)pair,O_CLOEXEC,0,0,0,0}));
    assert(fcntl(pair[0],F_GETFD)==FD_CLOEXEC && fcntl(pair[1],F_GETFD)==FD_CLOEXEC);
    assert(write(pair[1],"p",1)==1); char value;
    assert(read(pair[0],&value,1)==1 && value=='p'); close(pair[0]); close(pair[1]);
    assert(!required_call(local,SYS_socketpair,(uint64_t[]){AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,(uintptr_t)pair,0,0}));
    assert(write(pair[1],"s",1)==1 && read(pair[0],&value,1)==1 && value=='s');
    close(pair[0]); close(pair[1]);
    int fd=required_call(local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_PATH|O_CLOEXEC,0,0,0});
    assert(fd>=0 && (fcntl(fd,F_GETFL)&O_PATH) && fcntl(fd,F_GETFD)==FD_CLOEXEC);
    struct stat st; assert(!fstat(fd,&st) && st.st_size==7); close(fd);
    for (unsigned i=0;i<64;++i) {
        fd=required_call(local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)"/tmp/entry-once",O_CREAT|O_EXCL|O_RDWR,0600,0,0});
        assert(fd>=0); close(fd);
        assert(required_call(local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)"/tmp/entry-once",O_CREAT|O_EXCL|O_RDWR,0600,0,0})==-EEXIST);
        assert(!unlink("/tmp/entry-once"));
    }
    assert(local->opaque[MD_ENTRY_CLIENT_CHANNEL]);
    for (unsigned i=0;i<128;++i) {
        fd=required_call(local,SYS_fsopen,(uint64_t[]){(uintptr_t)"tmpfs",1,0,0,0,0});
        assert(fd>=0 && fcntl(fd,F_GETFD)==FD_CLOEXEC); close(fd);
    }
    assert(required_call(local,SYS_fsopen,(uint64_t[]){(uintptr_t)"tmpfs",2,0,0,0,0})==-EINVAL);
    puts("PASS cooperative entry: task completion shares FD, O_PATH, pipe, socket, identity and UTS owners; fsopen custody");
}
static void create_admission(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client local={0};
        native_client=&local; native_action=0; native_checks=0;
        const char *name="/tmp/entry-create-probe";
        struct md_entry_call call={.number=SYS_openat,
            .args={AT_FDCWD,(uintptr_t)name,O_CREAT|O_RDWR,0600},.cancel=native_checkpoint};
        assert(md_entry_invoke(api,&local,&call)==MD_ENTRY_COMPLETED && call.result>=0);
        assert(!close(call.result) && native_checks==1);
        struct md_entry_channel *channel=(void *)local.opaque[MD_ENTRY_CLIENT_CHANNEL];
        assert(channel);
        mode_t expected=0;
        call.args[3]=0666;
        for (unsigned i=0;i<128;++i) {
            /* Both branches must retain native FD allocation and live umask,
             * without caching an absent name or predicting the next syscall. */
            mode_t mask=(i&2) ? 0077 : 0022;
            umask(mask);
            if (!(i&1)) { assert(!unlink(name)); expected=0666&~mask; }
            assert(md_entry_invoke(api,&local,&call)==MD_ENTRY_COMPLETED && call.result>=0);
            struct stat st; assert(!fstat(call.result,&st) && (st.st_mode&0777)==expected);
            assert(write(call.result,"x",1)==1 && !close(call.result));
            assert(native_checks==2+(int)i);
        }
        assert(md_entry_invoke(api,&local,&call)==MD_ENTRY_COMPLETED && call.result>=0);
        assert(!close(call.result) && native_checks==130);
        assert(!unlink(name));
        assert(md_entry_invoke(api,&local,&call)==MD_ENTRY_COMPLETED && call.result>=0);
        assert(!close(call.result) && native_checks==131);
        call.args[2]=O_RDONLY;
        assert(md_entry_invoke(api,&local,&call)==MD_ENTRY_COMPLETED && call.result>=0);
        assert(!close(call.result) && native_checks==132);
        assert(!unlink(name)); _exit(0);
    }
    reap(child);
    puts("PASS native create admission: live umask, native birth/existing effects and independent readonly plans");
}
struct birth_race { int remove_parent; };
/* Drive the public ABI one action at a time around a real signal-delivery stop. */
static enum md_entry_outcome signal_at_effect(struct md_entry_call *call,int before) {
    enum md_entry_outcome outcome=api->try_syscall(&client,call);
    for (;;) {
        md_entry_step_fn next=call->next;
        if (outcome==MD_ENTRY_CHECK_CANCEL) { assert(next && call->cancel); call->cancel(); }
        else if (outcome==MD_ENTRY_NATIVE_EFFECT) {
            if (before) assert(!raise(SIGUSR2));
            assert(next && call->effect); call->effect(call->effect_context);
            if (!before) assert(!raise(SIGUSR2));
        } else if (outcome==MD_ENTRY_KERNEL_CALL) {
            assert(call->kernel); call->result=call->kernel(call->number,call->args);
            if (!next) return MD_ENTRY_COMPLETED;
        } else return outcome;
        outcome=next(&client,call);
    }
}
static void native_interruptions(void) {
    unsigned count=live_descriptors();
    for (unsigned before=0;before<2;++before) {
        for (unsigned i=0;i<32;++i) {
            int fd=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_RDONLY,0,0,0});
            assert(fd>=0);
            struct md_entry_call call={.number=SYS_lseek,.args={fd,1,SEEK_CUR}};
            enum md_entry_outcome outcome=signal_at_effect(&call,before);
            if (outcome!=MD_ENTRY_COMPLETED || call.result!=1)
                fprintf(stderr,"native signal before=%u seek outcome=%d result=%ld\n",before,outcome,(long)call.result);
            assert(outcome==MD_ENTRY_COMPLETED && call.result==1);
            assert(lseek(fd,0,SEEK_CUR)==1);
            call=(struct md_entry_call){.number=SYS_dup,.args={fd}};
            assert(signal_at_effect(&call,before)==MD_ENTRY_COMPLETED && call.result>=0);
            int alias=(int)call.result;
            assert(live_descriptors()==count+2);
            call=(struct md_entry_call){.number=SYS_close,.args={alias}};
            assert(signal_at_effect(&call,before)==MD_ENTRY_COMPLETED && !call.result);
            assert(fcntl(alias,F_GETFD)==-1 && errno==EBADF);
            call=(struct md_entry_call){.number=SYS_close,.args={fd}};
            assert(signal_at_effect(&call,before)==MD_ENTRY_COMPLETED && !call.result);
            assert(live_descriptors()==count);
        }
    }
    puts("PASS native signal boundaries: no pre-effect EINTR, exactly one seek/dup/close and no FD leaks");
}
static void *race_birth(void *argument) {
    struct birth_race *race=argument;
    struct md_entry_client local={0};
    struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    barrier(&native_barrier);
    barrier(&native_barrier);
    if (race->remove_parent)
        assert(!required_call(&local,SYS_unlinkat,(uint64_t[]){AT_FDCWD,
            (uintptr_t)"/tmp/entry-birth-parent",AT_REMOVEDIR,0,0,0}));
    else
        assert(!required_call(&local,SYS_renameat2,(uint64_t[]){AT_FDCWD,
            (uintptr_t)"/tmp/entry-birth-winner",AT_FDCWD,(uintptr_t)"/tmp/entry-birth-race",0,0}));
    barrier(&native_barrier);
    return NULL;
}
static void birth_retirement(void) {
    native_client=&client; native_action=2;
    unsigned before=live_descriptors();
    assert(!pthread_barrier_init(&native_barrier,NULL,2));
    for (unsigned i=0;i<24;++i) {
        struct birth_race race={.remove_parent=i%3==2};
        if (race.remove_parent) assert(!mkdir("/tmp/entry-birth-parent",0700));
        else {
            int fd=open("/tmp/entry-birth-winner",O_CREAT|O_EXCL|O_WRONLY,0600);
            assert(fd>=0 && write(fd,"winner",6)==6 && !close(fd));
        }
        pthread_t peer; assert(!pthread_create(&peer,NULL,race_birth,&race));
        barrier(&native_barrier);
        const char *name=race.remove_parent ? "/tmp/entry-birth-parent/value" : "/tmp/entry-birth-race";
        unsigned flags=O_CREAT|O_RDWR|((i%3)==1 ? 0 : O_EXCL)|((i&1) ? O_CLOEXEC : 0);
        struct md_entry_call call={.number=SYS_openat,
            .args={AT_FDCWD,(uintptr_t)name,flags,0600},.cancel=native_checkpoint};
        assert((i&4 ? signal_at_effect(&call,0) : md_entry_invoke(api,&client,&call))==MD_ENTRY_COMPLETED);
        assert(!pthread_join(peer,NULL));
        if (race.remove_parent) assert(call.result==-ENOENT);
        else {
            if (flags&O_EXCL) assert(call.result==-EEXIST);
            else {
                char bytes[8]={0};
                assert(call.result>=0 && read(call.result,bytes,6)==6 && !strcmp(bytes,"winner"));
                assert(!close(call.result));
            }
            assert(!unlink(name));
        }
        assert(live_descriptors()==before);
    }
    assert(!pthread_barrier_destroy(&native_barrier)); native_action=0;
    puts("PASS native birth retirement: exclusive/nonexclusive conflicts, parent removal, real CLOEXEC flags and no provisional FD leaks");
}
static void default_acl(const char *directory) {
    struct { uint32_t version; struct md_acl_entry entries[5]; } acl={.version=2,.entries={
        {MD_ACL_USER_OBJ,7,UINT32_MAX},{MD_ACL_USER,4,1000},{MD_ACL_GROUP_OBJ,0,UINT32_MAX},
        {MD_ACL_MASK,4,UINT32_MAX},{MD_ACL_OTHER,0,UINT32_MAX}}};
    assert(!setxattr(directory,MD_ACL_DEFAULT_NAME,&acl,sizeof(acl),0));
}
static int host_births;
static atomic_int birth_readers_running,birth_reader_calls;
static void *birth_reader(void *unused) {
    (void)unused;
    struct md_entry_client local={0}; struct stat st; long result;
    while (atomic_load(&birth_readers_running)) {
        assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
        atomic_fetch_add(&birth_reader_calls,1);
    }
    return NULL;
}
static void concurrent_births(void) {
    pthread_t readers[2]; atomic_store(&birth_readers_running,1);
    for (unsigned i=0;i<2;++i) assert(!pthread_create(&readers[i],NULL,birth_reader,NULL));
    for (unsigned i=0;i<128;++i) {
        const char *name="/tmp/entry-birth-watch-dir/concurrent";
        int fd=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)name,O_CREAT|O_EXCL|O_RDWR,0600,0,0});
        assert(fd>=0 && !close(fd) && !unlink(name));
    }
    atomic_store(&birth_readers_running,0);
    for (unsigned i=0;i<2;++i) assert(!pthread_join(readers[i],NULL));
    assert(atomic_load(&birth_reader_calls)>0);
    puts("PASS catalogue birth concurrency: independent metadata read windows cannot enclose publication");
}
static void birth_watches(int acl) {
    unsigned before=live_descriptors();
    const char *directory="/tmp/entry-birth-watch-dir";
    const char *name="/tmp/entry-birth-watch-dir/value";
    if (!host_births) assert(!mkdir(directory,0700));
    if (acl) default_acl(directory);
    mode_t previous=umask(0077);
    int watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(watch>=0);
    assert(inotify_add_watch(watch,directory,IN_CREATE|IN_OPEN|IN_CLOSE_WRITE|IN_MOVED_TO)>0);
    for (unsigned mode=0;mode<=0666;mode+=0666) {
        int fd=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)name,O_CREAT|O_EXCL|O_RDWR,mode,0,0});
        struct stat st;
        assert(fd>=0 && !fstat(fd,&st) && (st.st_mode&0777)==(mode ? (acl ? 0640 : 0600) : 0));
        assert(!required_call(&client,SYS_close,(uint64_t[]){fd,0,0,0,0,0}));
        char events[1024] __attribute__((aligned(8)));
        ssize_t bytes=read(watch,events,sizeof(events)); assert(bytes>0);
        unsigned count=0;
        const unsigned expected[]={IN_CREATE,IN_OPEN,IN_CLOSE_WRITE};
        for (size_t at=0;at<(size_t)bytes;) {
            struct inotify_event *event=(void *)(events+at);
            assert(count<3 && event->mask==expected[count++] && event->len
                && !strcmp(event->name,"value"));
            at+=sizeof(*event)+event->len;
        }
        assert(count==3 && read(watch,events,sizeof(events))==-1 && errno==EAGAIN);
        assert(!unlink(name));
        native_client=&client; native_action=1;
        struct md_entry_call call={.number=SYS_openat,
            .args={AT_FDCWD,(uintptr_t)name,O_CREAT|O_EXCL|O_RDWR,mode},.cancel=native_checkpoint};
        native_declined(&call);
        assert(stat(name,&st)==-1 && errno==ENOENT);
        assert(read(watch,events,sizeof(events))==-1 && errno==EAGAIN);
        native_action=0;
    }
    assert(!close(watch) && (host_births || !rmdir(directory)) && live_descriptors()==before);
    umask(previous);
    printf("PASS native birth watches: ACL=%d, CREATE/OPEN/CLOSE once, inherited/mode-zero metadata and no events or name on cancellation\n",acl);
}
static void native_xattr_pins(void) {
    const char *directory="/tmp/entry-xattr-pins",*name="/tmp/entry-xattr-pins/value";
    const char *alias="/tmp/entry-xattr-pins/alias",*attribute="user.pin";
    unsigned before=live_descriptors();
    assert(!mkdir(directory,0777) && !chmod(directory,0777));
    default_acl(directory);
    int fd=required_call(&client,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)name,O_CREAT|O_EXCL|O_RDWR,0666,0,0});
    assert(fd>=0 && !setxattr(name,attribute,"first",5,0));
    for (unsigned promoted=0;promoted<2;++promoted) {
        if (promoted) assert(!link(name,alias));
        char list[512];
        long size=required_call(&client,SYS_listxattr,(uint64_t[]){(uintptr_t)name,(uintptr_t)list,sizeof(list),0,0,0});
        assert(size>0 && (size_t)size<=sizeof(list));
        int acl=0,value=0;
        for (size_t at=0;at<(size_t)size;at+=strlen(list+at)+1) {
            acl|=!strcmp(list+at,MD_ACL_ACCESS_NAME); value|=!strcmp(list+at,attribute);
            assert(strncmp(list+at,"user.shroot.",12));
        }
        assert(acl && value);
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            struct md_entry_client local={0};
            char bytes[16];
            for (unsigned identity=1000;identity<=1001;++identity) {
                assert(!seteuid(identity));
                long result=required_call(&local,SYS_getxattr,
                    (uint64_t[]){(uintptr_t)name,(uintptr_t)attribute,(uintptr_t)bytes,sizeof(bytes),0,0});
                assert(identity==1000 ? result==5 && !memcmp(bytes,"first",5) : result==-EACCES);
                assert(!seteuid(0));
            }
            _exit(0);
        }
        reap(child);
    }
    assert(!rename(name,"/tmp/entry-xattr-pins/moved") && !unlink(alias));
    assert(!unlink("/tmp/entry-xattr-pins/moved"));
    char value[16];
    assert(required_call(&client,SYS_fgetxattr,(uint64_t[]){fd,(uintptr_t)attribute,(uintptr_t)value,sizeof(value),0,0})==5
        && !memcmp(value,"first",5));
    assert(!close(fd) && !rmdir(directory) && live_descriptors()==before);
    puts("PASS native xattr pins: live ACL denial, hidden attributes, hardlink promotion and unlinked descriptor");
}
static void native_open_exhaustion(int acl) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client local={0};
        const char *directory="/tmp/entry-emfile-dir";
        const char *name="/tmp/entry-emfile-dir/existing";
        if (!host_births) assert(!mkdir(directory,0700));
        if (acl) default_acl(directory);
        int source=open(name,O_CREAT|O_EXCL|O_RDWR,0600);
        assert(source>=0 && write(source,"preserved",9)==9);
        int watch=inotify_init1(IN_NONBLOCK|IN_CLOEXEC); assert(watch>=0);
        assert(inotify_add_watch(watch,directory,IN_CREATE|IN_OPEN|IN_MOVED_TO)>0);
        struct stat st; long result;
        assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_cur>=128);
        limit.rlim_cur=128; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int fillers[128],count=0;
        for (;;) {
            int fd=fcntl(source,F_DUPFD_CLOEXEC,0);
            if (fd<0) { assert(errno==EMFILE); break; }
            assert(count<128); fillers[count++]=fd;
        }
        assert(required_call(&local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)name,O_WRONLY|O_TRUNC,0,0,0})==-EMFILE);
        assert(!fstat(source,&st) && st.st_size==9);
        const char *unborn="/tmp/entry-emfile-dir/unborn";
        assert(required_call(&local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)unborn,O_CREAT|O_EXCL|O_RDWR,0600,0,0})==-EMFILE);
        int observed=stat(unborn,&st), observation_error=errno;
        char events[1024] __attribute__((aligned(8)));
        assert(read(watch,events,sizeof(events))==-1 && errno==EAGAIN);
        while (count) assert(!close(fillers[--count]));
        int after=stat(unborn,&st), after_error=errno;
        if (observed!=-1 || observation_error!=ENOENT || after!=-1 || after_error!=ENOENT)
            fprintf(stderr,"EMFILE birth observation: full=%d/%d released=%d/%d\n",
                observed,observation_error,after,after_error);
        assert(observed==-1 && observation_error==ENOENT && after==-1 && after_error==ENOENT);
        int fd=required_call(&local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)name,O_WRONLY|O_TRUNC,0,0,0});
        assert(fd>=0 && !fstat(source,&st) && !st.st_size);
        assert(!close(fd) && !close(source) && !close(watch) && !unlink(name)
            && (host_births || !rmdir(directory))); _exit(0);
    }
    reap(child);
    printf("PASS native open exhaustion: ACL=%d, EMFILE precedes birth/truncate/watch events; freed descriptor capacity admits a fresh effect\n",acl);
}
static void filter_arguments(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client local={0}; struct stat st; long result;
        assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,O_TRUNC,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(required_call(&local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_RDWR|O_TRUNC,0,0,0})==-EACCES);
        assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
        int fd=required_call(&local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_RDONLY,0,0,0});
        assert(fd>=0); char content[8]={0}; assert(read(fd,content,7)==7 && !strcmp(content,"payload")); close(fd);
        _exit(0);
    }
    reap(child); puts("PASS cooperative entry: seccomp argument denial precedes mutation; allowed metadata/open still complete");
}
extern char entry_test_kernel_return[];
__attribute__((noinline)) static long caller_kernel(long number,const uint64_t *args) {
    register long x8 __asm__("x8")=number;
    register long x0 __asm__("x0")=args[0], x1 __asm__("x1")=args[1], x2 __asm__("x2")=args[2];
    register long x3 __asm__("x3")=args[3], x4 __asm__("x4")=args[4], x5 __asm__("x5")=args[5];
    __asm__ volatile("svc 0\n.global entry_test_kernel_return\nentry_test_kernel_return:"
        : "+r"(x0) : "r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5) : "memory","cc");
    return x0;
}
static void filter_instruction(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        uintptr_t site=(uintptr_t)entry_test_kernel_return;
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getuid,0,4),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(site>>32),0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)site,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        struct md_entry_client local={0};
        struct md_entry_call call={.number=SYS_getuid,.kernel=caller_kernel};
        assert(md_entry_invoke(api,&local,&call)==MD_ENTRY_COMPLETED && !call.result);
        assert(syscall(SYS_getuid)==-1 && errno==EACCES);
        _exit(0);
    }
    reap(child); puts("PASS cooperative entry: kernel filters see the caller-owned instruction site");
}
static pthread_barrier_t capacity_barrier;
static atomic_uint capacity_completed,capacity_declined;
static void *capacity_peer(void *unused) {
    (void)unused;
    struct md_entry_client local={0}; struct stat st; long result;
    enum md_entry_outcome outcome=stat_try(&local,&st,&result);
    assert(outcome!=MD_ENTRY_UNKNOWN);
    assert(outcome==MD_ENTRY_COMPLETED && !result);
    if (local.opaque[MD_ENTRY_CLIENT_CHANNEL]) atomic_fetch_add(&capacity_completed,1);
    else atomic_fetch_add(&capacity_declined,1);
    barrier(&capacity_barrier); return NULL;
}
static void capacity(void) {
    pthread_t peers[16];
    for (unsigned wave=0;wave<2;++wave) {
        atomic_store(&capacity_completed,0); atomic_store(&capacity_declined,0);
        assert(!pthread_barrier_init(&capacity_barrier,NULL,17));
        for (unsigned i=0;i<16;++i) assert(!pthread_create(&peers[i],NULL,capacity_peer,NULL));
        barrier(&capacity_barrier);
        for (unsigned i=0;i<16;++i) assert(!pthread_join(peers[i],NULL));
        assert(!pthread_barrier_destroy(&capacity_barrier));
        assert(atomic_load(&capacity_completed)==7 && atomic_load(&capacity_declined)==9);
    }
    puts("PASS cooperative entry: bounded channel exhaustion falls back; exited threads release all seven borrowed slots");
}
struct reader_burst {
    pthread_barrier_t ready;
    atomic_int done;
    atomic_uint calls;
    int filtered;
};
static void *burst_reader(void *argument) {
    struct reader_burst *burst=argument;
    if (burst->filtered) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        long installed=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program);
        if (installed) fprintf(stderr,"burst filter failed: result=%ld errno=%d\n",installed,errno);
        assert(!installed);
        assert(syscall(SYS_getppid)==-1 && errno==EACCES);
    }
    struct md_entry_client local={0}; struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    assert(!!local.opaque[MD_ENTRY_CLIENT_CHANNEL]==!burst->filtered);
    barrier(&burst->ready);
    unsigned calls=0;
    do {
        assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
        assert(st.st_size==7 && ((st.st_mode&0777)==0600 || (st.st_mode&0777)==0644));
        ++calls;
    } while (!atomic_load_explicit(&burst->done,memory_order_acquire));
    atomic_fetch_add(&burst->calls,calls);
    if (burst->filtered) assert(syscall(SYS_getppid)==-1 && errno==EACCES);
    return NULL;
}
static void reader_writer_bursts(void) {
    for (int filtered=0;filtered<2;++filtered) {
        struct reader_burst burst={.filtered=filtered};
        pthread_t readers[3];
        assert(!pthread_barrier_init(&burst.ready,NULL,4));
        for (unsigned i=0;i<3;++i) assert(!pthread_create(&readers[i],NULL,burst_reader,&burst));
        barrier(&burst.ready);
        if (filtered) {
            struct md_entry_client fresh={0}; struct stat st; long result;
            assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
            assert(!fresh.opaque[MD_ENTRY_CLIENT_CHANNEL]);
            struct md_entry_channel *registered=(void *)client.opaque[MD_ENTRY_CLIENT_CHANNEL];
            assert(registered && atomic_load(&registered->enabled));
        }
        for (unsigned i=0;i<128;++i) {
            unsigned mode=i&1 ? 0600 : 0644;
            if (i&1) assert(!chmod(path,mode));
            else assert(!required_call(&client,SYS_fchmodat,(uint64_t[6]){AT_FDCWD,(uintptr_t)path,mode}));
            struct stat st; long result;
            assert(stat_try(&client,&st,&result)==MD_ENTRY_COMPLETED && !result && (st.st_mode&0777)==mode);
        }
        atomic_store_explicit(&burst.done,1,memory_order_release);
        /* EVENT_WAIT: every reader exits after the writer completes; the outer
         * fixture deadline detects starvation instead of relaxing this test. */
        for (unsigned i=0;i<3;++i) assert(!pthread_join(readers[i],NULL));
        assert(atomic_load(&burst.calls)>=3);
        assert(!pthread_barrier_destroy(&burst.ready));
    }
    puts("PASS execution bursts: continuous direct/filtered readers admit 256 worker/kernel mutations with fresh metadata");
}
static void *threaded(void *unused) {
    (void)unused;
    struct md_entry_client local={0}; struct stat st; long result;
    char directory[80],renamed[80];
    snprintf(directory,sizeof(directory),"tmp/entry-mutate-%ld",syscall(SYS_gettid));
    snprintf(renamed,sizeof(renamed),"%s-moved",directory);
    for (unsigned i=0;i<200;++i) {
        enum md_entry_outcome outcome=stat_try(&local,&st,&result);
        assert(outcome==MD_ENTRY_COMPLETED && !result && st.st_size==7);
        int fd=required_call(&local,SYS_openat,(uint64_t[]){AT_FDCWD,(uintptr_t)path,O_RDONLY|O_CLOEXEC,0,0,0});
        assert(fd>=0 && !fstat(fd,&st) && st.st_size==7);
        assert(!required_call(&local,SYS_close,(uint64_t[]){fd,0,0,0,0,0}));
        if (!(i%8)) {
            /* Relative mutations queue on the same table as peers' native
             * effects. A ready waiter must close any retained read window. */
            assert(!required_call(&local,SYS_mkdirat,(uint64_t[]){AT_FDCWD,(uintptr_t)directory,0700,0,0,0}));
            assert(!required_call(&local,SYS_renameat2,(uint64_t[]){AT_FDCWD,(uintptr_t)directory,AT_FDCWD,(uintptr_t)renamed,0,0}));
            long result=required_call(&local,SYS_unlinkat,(uint64_t[]){AT_FDCWD,(uintptr_t)renamed,AT_REMOVEDIR,0,0,0});
            if (result) fprintf(stderr,"queued mutation result=%ld path=%s\n",result,renamed);
            assert(!result);
        }
    }
    return NULL;
}
static volatile sig_atomic_t signals;
static volatile sig_atomic_t strict_reentry;
static void handler(int signal) {
    (void)signal;
    struct stat st; long result;
    enum md_entry_outcome outcome=stat_try(&client,&st,&result);
    assert(outcome!=MD_ENTRY_UNKNOWN);
    if (strict_reentry) assert(outcome==MD_ENTRY_DECLINED);
    if (outcome==MD_ENTRY_DECLINED) assert(!stat(path,&st));
    else assert(!result);
    ++signals;
}
static atomic_int signalling;
static pthread_t signal_target;
static void *signal_peer(void *unused) {
    (void)unused;
    for (unsigned i=0;i<1000 && atomic_load(&signalling);++i) {
        assert(!pthread_kill(signal_target,SIGUSR1)); sched_yield();
    }
    return NULL;
}
static void interrupted(void) {
    pthread_t peer; signal_target=pthread_self(); atomic_store(&signalling,1);
    assert(!pthread_create(&peer,NULL,signal_peer,NULL));
    for (unsigned i=0;i<1000;++i) {
        struct stat st; long result;
        assert(stat_try(&client,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
    }
    atomic_store(&signalling,0); assert(!pthread_join(peer,NULL)); assert(signals>1);
    puts("PASS cooperative entry: in-flight signals, nested native fallback and original stack/output ownership");
}
static sigjmp_buf escape;
static volatile sig_atomic_t escape_armed;
static atomic_int escape_running;
static void escape_handler(int signal) {
    (void)signal;
    if (escape_armed) siglongjmp(escape,1);
}
static void *escape_sender(void *unused) {
    (void)unused;
    while (atomic_load(&escape_running)) {
        assert(!pthread_kill(signal_target,SIGUSR2)); sched_yield();
    }
    return NULL;
}
static void abandoned_memory(void) {
    for (unsigned wave=0;wave<16;++wave) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            struct sigaction action={.sa_handler=escape_handler}; sigemptyset(&action.sa_mask);
            assert(!sigaction(SIGUSR2,&action,NULL));
            void *memory=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            assert(memory!=MAP_FAILED);
            client=(struct md_entry_client){0};
            pthread_t peer; signal_target=pthread_self(); atomic_store(&escape_running,1);
            assert(!pthread_create(&peer,NULL,escape_sender,NULL));
            if (!sigsetjmp(escape,1)) {
                escape_armed=1;
                for (;;) {
                    long result;
                    assert(stat_try(&client,memory,&result)==MD_ENTRY_COMPLETED && !result);
                }
            }
            escape_armed=0; atomic_store(&escape_running,0);
            memset(memory,0xa5,4096);
            assert(!pthread_join(peer,NULL));
            struct md_entry_client fresh={0}; struct stat st; long result;
            for (unsigned i=0;i<128;++i)
                assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
            for (unsigned i=0;i<4096;++i) assert(((unsigned char *)memory)[i]==0xa5);
            assert(!munmap(memory,4096)); _exit(0);
        }
        reap(child);
    }
    puts("PASS cooperative entry: signal longjmp releases output ownership before caller memory reuse");
}
static void context_changes(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client fresh={0}; struct stat st; long result;
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        assert(!unshare(CLONE_NEWNS));
        assert(!mkdir("/tmp/cooperative-view",0700));
        assert(!mount("/tmp","/tmp/cooperative-view",NULL,MS_BIND,NULL));
        path="/tmp/cooperative-view/cooperative-file";
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
        assert(!umount("/tmp/cooperative-view") && !rmdir("/tmp/cooperative-view"));
        assert(!chdir("/tmp") && !chroot(".")); path="/cooperative-file";
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
        assert(!stat(path,&st) && st.st_size==7);
        path="/bin/probe";
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && result==-ENOENT);
        _exit(0);
    }
    reap(child);
    child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client fresh={0}; struct stat st; long result;
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL)); raise(SIGSTOP);
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result && !stat(path,&st));
        struct md_entry_client traced={0};
        assert(stat_try(&traced,&st,&result)==MD_ENTRY_COMPLETED && !result && !stat(path,&st)); _exit(0);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL)); reap(child);
    puts("PASS cooperative entry: private mount views, restricted root and debugger use the shared ownership/dispatch policy");
}
static void credentials(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client fresh={0}; struct stat st; long result;
        assert(!mkdir("/tmp/cooperative-private",0700)); path="/tmp/cooperative-private/.";
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        assert(!seteuid(1000));
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && result==-EACCES);
        assert(!seteuid(0));
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        assert(!rmdir("/tmp/cooperative-private")); _exit(0);
    }
    reap(child); puts("PASS cooperative entry: identity changes recheck directory permissions inside reused channels");
}
static void pid_namespace(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWPID));
        pid_t init=fork(); assert(init>=0);
        if (!init) {
            assert(getpid()==1 && syscall(SYS_gettid)==1);
            assert(api->thread_identity()>1);
            struct md_entry_client fresh={0}; struct stat st; long result;
            for (unsigned i=0;i<100;++i)
                assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
            _exit(0);
        }
        reap(init); _exit(0);
    }
    reap(child); puts("PASS cooperative entry: private PID 1 retains authenticated native thread identity across requests");
}
static void landlocked(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_client fresh={0}; struct stat st; long result;
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        struct landlock_ruleset_attr rule={.handled_access_fs=LANDLOCK_ACCESS_FS_READ_FILE};
        int set=syscall(SYS_landlock_create_ruleset,&rule,sizeof(rule),0); assert(set>=0);
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_landlock_restrict_self,set,0) && !close(set));
        /* Landlock restricts opening file data, not stat metadata. Both entry
         * routes must preserve that distinction after the domain changes. */
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
        assert(call6(SYS_openat,AT_FDCWD,(uintptr_t)path,O_RDONLY,0,0,0)==-EACCES);
        _exit(0);
    }
    reap(child); puts("PASS cooperative entry: Landlock keeps direct metadata and rejects file-data fallback");
}
static pthread_barrier_t teardown_barrier;
static void *teardown_peer(void *unused) {
    (void)unused;
    struct md_entry_client local={0}; struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    barrier(&teardown_barrier);
    for (;;) assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result);
    return NULL;
}
static void teardown(void) {
    for (unsigned i=0;i<16;++i) {
        int ready[2]; assert(!pipe2(ready,O_CLOEXEC));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!close(ready[0]));
            pthread_t peers[2];
            assert(!pthread_barrier_init(&teardown_barrier,NULL,3));
            for (unsigned j=0;j<2;++j) assert(!pthread_create(&peers[j],NULL,teardown_peer,NULL));
            barrier(&teardown_barrier);
            assert(write(ready[1],"r",1)==1);
            if (i%2) _exit(0);
            for (;;) pause();
        }
        assert(!close(ready[1]));
        char byte; ssize_t n;
        do { n=read(ready[0],&byte,1); } while (n<0 && errno==EINTR);
        assert(n==1 && byte=='r' && !close(ready[0]));
        if (i%2) reap(child);
        else {
            assert(!kill(child,SIGKILL));
            int status; pid_t waited;
            do { waited=waitpid(child,&status,0); } while (waited<0 && errno==EINTR);
            assert(waited==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        }
        struct stat st; long result;
        assert(stat_try(&client,&st,&result)==MD_ENTRY_COMPLETED && !result);
    }
    puts("PASS cooperative entry: SIGKILL/exit_group retire active peers without leaking channel slots or stopping the owner");
}
static long direct6(int nr,unsigned long a,unsigned long b,unsigned long c,unsigned long d,unsigned long e,unsigned long f) {
    struct md_entry_call call={.number=nr,.args={a,b,c,d,e,f}};
    assert(md_entry_invoke(api,&client,&call)==MD_ENTRY_COMPLETED);
    ++direct_calls; return call.result;
}
static void filesystem_operations(void) {
    const char *directory="/tmp/cooperative-directory", *moved="/tmp/cooperative-moved";
    assert(!direct6(SYS_mkdirat,AT_FDCWD,(uintptr_t)directory,0700,0,0,0));
    assert(direct6(SYS_mkdirat,AT_FDCWD,(uintptr_t)directory,0700,0,0,0)==-EEXIST);
    int base=direct6(SYS_openat,AT_FDCWD,(uintptr_t)directory,O_DIRECTORY|O_RDONLY|O_CLOEXEC,0,0,0);
    assert(base>=0);
    for (unsigned i=0;i<4;++i) {
        unsigned flags=O_PATH|O_NOFOLLOW|(i&1 ? O_DIRECTORY : 0)|(i&2 ? O_CLOEXEC : 0);
        int pin=direct6(SYS_openat,base,(uintptr_t)".",flags,0,0,0);
        assert(pin>=0 && fcntl(pin,F_GETFL)==(int)(flags&~O_CLOEXEC)
            && fcntl(pin,F_GETFD)==(flags&O_CLOEXEC ? FD_CLOEXEC : 0));
        struct stat a,b; assert(!fstat(base,&a) && !fstat(pin,&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
        assert(!direct6(SYS_close,pin,0,0,0,0,0));
    }
    assert(!direct6(SYS_linkat,AT_FDCWD,(uintptr_t)path,base,(uintptr_t)"linked",0,0));
    assert(!direct6(SYS_symlinkat,(uintptr_t)"linked",base,(uintptr_t)"symlink",0,0,0));
    struct statx sx;
    assert(!direct6(SYS_statx,base,(uintptr_t)"linked",0,STATX_BASIC_STATS,(uintptr_t)&sx,0));
    struct stat st; assert(!stat(path,&st) && sx.stx_ino==st.st_ino && sx.stx_size==7 && sx.stx_nlink==2);
    assert(direct6(SYS_statx,base,(uintptr_t)"linked",AT_STATX_SYNC_TYPE,STATX_BASIC_STATS,(uintptr_t)&sx,0)==-EINVAL);
    assert(!direct6(SYS_faccessat,base,(uintptr_t)"linked",R_OK,0,0,0));
    assert(!direct6(SYS_fchmodat,base,(uintptr_t)"linked",0640,0,0,0));
    assert(!stat(path,&st) && (st.st_mode&0777)==0640);
    assert(!direct6(SYS_fchownat,base,(uintptr_t)"linked",123,456,0,0));
    assert(!stat(path,&st) && st.st_uid==123 && st.st_gid==456);
    assert(!direct6(SYS_fchownat,base,(uintptr_t)"linked",0,0,0,0));
    int metadata=openat(base,"linked",O_RDWR|O_CLOEXEC); assert(metadata>=0);
    assert(!direct6(SYS_fchmod,metadata,0600,0,0,0,0));
    assert(!direct6(SYS_fchown,metadata,321,654,0,0,0));
    assert(!fstat(metadata,&st) && (st.st_mode&0777)==0600 && st.st_uid==321 && st.st_gid==654);
    assert(!direct6(SYS_fchown,metadata,0,0,0,0,0));
    const char *attribute="user.cooperative";
    char value[64];
    assert(!direct6(SYS_setxattr,(uintptr_t)path,(uintptr_t)attribute,(uintptr_t)"value",5,XATTR_CREATE,0));
    assert(direct6(SYS_fgetxattr,metadata,(uintptr_t)attribute,0,0,0,0)==5);
    assert(direct6(SYS_getxattr,(uintptr_t)path,(uintptr_t)attribute,(uintptr_t)value,4,0,0)==-ERANGE);
    assert(direct6(SYS_fgetxattr,metadata,(uintptr_t)attribute,(uintptr_t)value,sizeof(value),0,0)==5);
    assert(!memcmp(value,"value",5));
    assert(direct6(SYS_listxattr,(uintptr_t)path,(uintptr_t)value,sizeof(value),0,0,0)>0);
    assert(!direct6(SYS_fsetxattr,metadata,(uintptr_t)attribute,(uintptr_t)"replacement",11,XATTR_REPLACE,0));
    assert(direct6(SYS_lgetxattr,(uintptr_t)path,(uintptr_t)attribute,1,11,0,0)==-EFAULT);
    assert(!direct6(SYS_fremovexattr,metadata,(uintptr_t)attribute,0,0,0,0));
    assert(direct6(SYS_getxattr,(uintptr_t)path,(uintptr_t)attribute,0,0,0,0)==-ENODATA);
    struct timespec times[2]={{123456,123},{123457,456}};
    assert(!direct6(SYS_utimensat,metadata,0,(uintptr_t)times,0,0,0));
    assert(!fstat(metadata,&st) && st.st_atim.tv_sec==123456 && st.st_mtim.tv_nsec==456);
    assert(!direct6(SYS_utimensat,base,(uintptr_t)"linked",0,0,0,0));
    assert(direct6(SYS_utimensat,metadata,0,(uintptr_t)times,AT_SYMLINK_NOFOLLOW,0,0)==-EINVAL);
    int pin=openat(base,"linked",O_PATH|O_CLOEXEC); assert(pin>=0);
    assert(direct6(SYS_fchmod,pin,0777,0,0,0,0)==-EBADF);
    assert(direct6(SYS_fchown,pin,1,2,0,0,0)==-EBADF);
    assert(direct6(SYS_fgetxattr,pin,(uintptr_t)attribute,0,0,0,0)==-EBADF);
    assert(direct6(SYS_utimensat,pin,0,(uintptr_t)times,0,0,0)==-EBADF);
    assert(!close(pin) && !close(metadata));
    struct statfs space,space_fd;
    assert(!direct6(SYS_statfs,(uintptr_t)path,(uintptr_t)&space,0,0,0,0));
    assert(!direct6(SYS_fstatfs,base,(uintptr_t)&space_fd,0,0,0,0));
    assert(space.f_type==space_fd.f_type && space.f_bsize==space_fd.f_bsize);
    assert(direct6(SYS_statfs,(uintptr_t)path,1,0,0,0,0)==-EFAULT);
    struct open_how how={.flags=O_PATH|O_CLOEXEC,.resolve=RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS};
    pin=direct6(SYS_openat2,base,(uintptr_t)"linked",(uintptr_t)&how,sizeof(how),0,0);
    assert(pin>=0 && (fcntl(pin,F_GETFL)&O_PATH) && fcntl(pin,F_GETFD)==FD_CLOEXEC);
    assert(!fstat(pin,&st) && st.st_size==7 && !close(pin));
    assert(direct6(SYS_openat2,base,(uintptr_t)"symlink",(uintptr_t)&how,sizeof(how),0,0)==-ELOOP);
    assert(direct6(SYS_openat2,base,(uintptr_t)"../cooperative-file",(uintptr_t)&how,sizeof(how),0,0)==-EXDEV);
    how.resolve=RESOLVE_IN_ROOT;
    pin=direct6(SYS_openat2,base,(uintptr_t)"/linked",(uintptr_t)&how,sizeof(how),0,0);
    assert(pin>=0 && !fstat(pin,&st) && st.st_size==7 && !close(pin));
    how.flags|=O_TRUNC;
    assert(direct6(SYS_openat2,base,(uintptr_t)"linked",(uintptr_t)&how,sizeof(how),0,0)==-EINVAL);
    assert(!direct6(SYS_renameat,base,(uintptr_t)"linked",base,(uintptr_t)"renamed",0,0));
    assert(!direct6(SYS_unlinkat,base,(uintptr_t)"renamed",0,0,0,0));
    assert(!direct6(SYS_unlinkat,base,(uintptr_t)"symlink",0,0,0,0));
    assert(!chdir(directory));
    assert(!direct6(SYS_mkdirat,AT_FDCWD,(uintptr_t)"cwd-first",0700,0,0,0));
    assert(!direct6(SYS_renameat,AT_FDCWD,(uintptr_t)"cwd-first",AT_FDCWD,(uintptr_t)"cwd-second",0,0));
    assert(!direct6(SYS_unlinkat,AT_FDCWD,(uintptr_t)"cwd-second",AT_REMOVEDIR,0,0,0));
    assert(!chdir("/"));
    char entries[4096];
    assert(direct6(SYS_getdents64,base,1,sizeof(entries),0,0,0)==-EFAULT);
    assert(!direct6(SYS_lseek,base,0,SEEK_CUR,0,0,0));
    assert(direct6(SYS_getdents64,base,(uintptr_t)entries,sizeof(entries),0,0,0)>0);
    assert(lseek(base,0,SEEK_CUR)>0);
    assert(!direct6(SYS_getdents64,base,(uintptr_t)entries,sizeof(entries),0,0,0));
    assert(!close(base));
    assert(!direct6(SYS_renameat2,AT_FDCWD,(uintptr_t)directory,AT_FDCWD,(uintptr_t)moved,0,0));
    assert(!direct6(SYS_unlinkat,AT_FDCWD,(uintptr_t)moved,AT_REMOVEDIR,0,0,0));
    assert(!stat(path,&st) && st.st_nlink==1);
    puts("PASS cooperative entry: direct mutations, relative dirfds/cwd, statx, access, permissions and acknowledged directory cursor");
}
static volatile sig_atomic_t copy_signals;
static void copy_signal(int signal) { copy_signals|=signal==SIGSEGV ? 1 : 2; }
static void output_faults(int fd) {
    struct sigaction action={.sa_handler=copy_signal},old_segv,old_bus;
    sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGSEGV,&action,&old_segv) && !sigaction(SIGBUS,&action,&old_bus));
    size_t page=(size_t)getpagesize();
    char *memory=mmap(NULL,2*page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && !mprotect(memory+page,page,PROT_NONE));
    memset(memory+page-16,0xa5,16);
    assert(direct6(SYS_newfstatat,AT_FDCWD,(uintptr_t)path,(uintptr_t)(memory+page-16),0,0,0)==-EFAULT);
    struct stat st;
    assert(!direct6(SYS_fstat,fd,(uintptr_t)&st,0,0,0,0));
    assert(!memcmp(memory+page-16,&st,16));
    assert(!mprotect(memory,page,PROT_READ));
    assert(direct6(SYS_fstat,fd,(uintptr_t)memory,0,0,0,0)==-EFAULT);
    assert(!munmap(memory,2*page));
    memory=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_SHARED,fd,page);
    assert(memory!=MAP_FAILED);
    assert(direct6(SYS_fstat,fd,(uintptr_t)memory,0,0,0,0)==-EFAULT);
    assert(!munmap(memory,page) && !copy_signals);
    assert(!raise(SIGSEGV) && !raise(SIGBUS) && copy_signals==3);
    assert(!sigaction(SIGSEGV,&old_segv,NULL) && !sigaction(SIGBUS,&old_bus,NULL));
    assert(!direct6(SYS_fstat,fd,(uintptr_t)&st,0,0,0,0) && st.st_size==7);
    puts("PASS cooperative output: partial/readonly/SIGBUS faults, reusable receipt and unrelated signal delivery");
}
static void input_faults(void) {
    size_t page=(size_t)getpagesize(),length=strlen(path)+1;
    char *memory=mmap(NULL,2*page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED && !mprotect(memory+page,page,PROT_NONE));
    char *end=memory+page-length;
    memcpy(end,path,length);
    struct stat st;
    assert(!direct6(SYS_newfstatat,AT_FDCWD,(uintptr_t)end,(uintptr_t)&st,0,0,0) && st.st_size==7);
    long fd=direct6(SYS_openat,AT_FDCWD,(uintptr_t)end,O_RDONLY,0,0,0);
    assert(fd>=0 && !direct6(SYS_close,fd,0,0,0,0,0));
    assert(call6(SYS_newfstatat,AT_FDCWD,(uintptr_t)(memory+page),(uintptr_t)&st,0,0,0)==-EFAULT);
    memory[page-1]='a';
    assert(call6(SYS_newfstatat,AT_FDCWD,(uintptr_t)end,(uintptr_t)&st,0,0,0)==-EFAULT);
    assert(!munmap(memory,2*page));
    memory=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED);
    memset(memory,'a',page);
    assert(call6(SYS_newfstatat,AT_FDCWD,(uintptr_t)memory,(uintptr_t)&st,0,0,0)==-ENAMETOOLONG);
    assert(!munmap(memory,page));
    assert(!direct6(SYS_newfstatat,AT_FDCWD,(uintptr_t)path,(uintptr_t)&st,0,0,0));
    puts("PASS cooperative input: terminal NUL, inaccessible continuation, bounded pathname and channel reuse");
}
static long reject_carrier(long nr,const uint64_t args[6]) {
    assert(nr==SYS_fsopen && args[1]==1);
    return -EACCES;
}
static void input_sequences(int fd) {
    struct stat st;
    assert(!direct6(SYS_newfstatat,AT_FDCWD,(uintptr_t)path,(uintptr_t)&st,0,0,0));
    struct md_entry_channel *channel=(void *)client.opaque[MD_ENTRY_CLIENT_CHANNEL];
    assert(channel && channel->input.sequence==channel->sequence);
    uint64_t captured=channel->input.sequence;
    for (unsigned i=0;i<32;++i) {
        assert(!direct6(SYS_fstat,fd,(uintptr_t)&st,0,0,0,0) && st.st_size==7);
        assert(!direct6(SYS_getuid,0,0,0,0,0,0));
        assert(channel->input.sequence==captured && channel->sequence>captured);
    }
    struct md_entry_call rejected={.number=SYS_fsopen,
        .args={(uintptr_t)"tmpfs",1},.kernel=reject_carrier};
    uint64_t before=channel->sequence;
    assert(md_entry_invoke(api,&client,&rejected)==MD_ENTRY_COMPLETED && rejected.result==-EACCES);
    assert(channel->sequence==before && !channel->input.sequence);
    assert(!direct6(SYS_fstat,fd,(uintptr_t)&st,0,0,0,0) && st.st_size==7);
    assert(channel->sequence==before+1 && !channel->input.sequence);
    assert(!direct6(SYS_newfstatat,AT_FDCWD,(uintptr_t)path,(uintptr_t)&st,0,0,0));
    assert(channel->input.sequence==channel->sequence);
    puts("PASS cooperative input sequences: pathless requests leave capture untouched; rejected carrier invalidates reusable sequence");
}
static void *protected_copy(void *unused) {
    (void)unused;
    struct md_entry_client local={0};
    struct stat st; long result;
    assert(stat_try(&local,&st,&result)==MD_ENTRY_COMPLETED && !result && st.st_size==7);
    struct md_entry_channel *channel=(void *)local.opaque[MD_ENTRY_CLIENT_CHANNEL];
    assert(channel && channel->input.sequence==channel->sequence && channel->output.sequence==channel->sequence);
    return NULL;
}
static void filtered_close(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        int fd=open(path,O_RDONLY); assert(fd>=0 && fd<190);
        assert(dup2(fd,190)==190 && dup2(fd,191)==191 && !close(fd));
        struct sock_filter instructions[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_close,0,4),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,191,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|MD_INTERCEPT_CLOSE|191),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(instructions)/sizeof(*instructions),instructions};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        assert(!close(190));
        assert(close(191)==-1 && errno==EPERM);
        struct stat st;
        assert(fstat(190,&st)==-1 && errno==EBADF);
        assert(!fstat(191,&st) && st.st_size==7);
        assert(close(-1)==-1 && errno==EBADF);
        _exit(0);
    }
    reap(child);
    puts("PASS filtered close: actual syscall FD, forged TRACE cookie, kernel denial and EBADF preserve slot ownership");
}
static enum md_entry_outcome identified_entry(struct md_entry_client *owner,struct md_entry_call *call) {
    return api->try_identified(owner,api->thread_identity(),call);
}
static void contracts(const char *executable) {
    assert(sizeof(struct md_entry_client)==3*sizeof(uintptr_t));
    struct md_entry_api invalid=*api;
    assert(md_entry_api_valid(&invalid));
    invalid.version++; assert(!md_entry_api_valid(&invalid)); invalid=*api;
    invalid.size=sizeof(invalid)-1; assert(!md_entry_api_valid(&invalid)); invalid=*api;
    invalid.try_syscall=NULL; assert(!md_entry_api_valid(&invalid)); invalid=*api;
    invalid.offers=NULL; assert(!md_entry_api_valid(&invalid)); invalid=*api;
    invalid.thread_identity=NULL; assert(!md_entry_api_valid(&invalid)); invalid=*api;
    invalid.try_identified=NULL; assert(md_entry_api_valid(&invalid)); invalid=*api;
    invalid.reserved=1; assert(!md_entry_api_valid(&invalid)); invalid=*api;
    invalid.size+=16; assert(md_entry_api_valid(&invalid));
    assert(!md_entry_api_valid(NULL) && !md_entry_offered(NULL,SYS_openat,MD_ENTRY_ORDINARY));
    assert(!md_entry_offered(api,-1,MD_ENTRY_ORDINARY));
    assert(!md_entry_offered(api,api->syscall_count,MD_ENTRY_ORDINARY));
    for (unsigned nr=0;nr<api->syscall_count;++nr) {
        enum md_entry_route route=md_entry_route(nr);
        assert(!!md_entry_offered(api,nr,MD_ENTRY_ORDINARY)==(route!=MD_ENTRY_CALLER));
        assert(!!md_entry_offered(api,nr,MD_ENTRY_CANCELLATION)==(route==MD_ENTRY_FD));
        assert(!!md_entry_offered(api,nr,MD_ENTRY_NATIVE_MEMORY)==(route==MD_ENTRY_MEMORY));
    }
    puts("PASS cooperative ABI: runtime-owned offers, opaque client, bounds and descriptor admission");
    struct md_entry_client unused={0};
    for (int nr=-1;nr<512;++nr) {
        if (md_entry_supported(nr)) continue;
        struct md_entry_call call={.number=nr};
        assert(md_entry_invoke(api,&unused,&call)==MD_ENTRY_DECLINED);
        assert(!unused.opaque[0] && !unused.opaque[1] && !unused.opaque[2]);
    }
    long fd=call6(SYS_openat,AT_FDCWD,(uintptr_t)path,O_CREAT|O_RDWR|O_TRUNC,0600,0,0); assert(fd>=0);
    assert(call6(SYS_write,fd,(uintptr_t)"payload",7,0,0,0)==7);
    scalar_credentials();
    native_descriptors();
    native_interruptions();
    task_completion(); create_admission(); birth_retirement();
    for (unsigned acl=0;acl<2;++acl) { native_open_exhaustion(acl); birth_watches(acl); }
    native_xattr_pins();
    filtered_close();
    filter_arguments(); filter_instruction();
    struct stat st,native; long result;
    enum md_entry_outcome first=stat_try(&client,&st,&result);
    assert(first==MD_ENTRY_COMPLETED && !result && st.st_size==7);
    assert(api->try_identified);
    struct md_entry_api identified=*api;
    identified.try_syscall=identified_entry;
    struct md_entry_call sampled={.number=SYS_newfstatat,
        .args={AT_FDCWD,(uintptr_t)path,(uintptr_t)&st}};
    assert(md_entry_invoke(&identified,&client,&sampled)==MD_ENTRY_COMPLETED && !sampled.result && st.st_size==7);
    assert(!stat(path,&native) && st.st_ino==native.st_ino && st.st_mode==native.st_mode);
    assert(call6(SYS_fstat,fd,(uintptr_t)&st,0,0,0,0)==0 && st.st_size==7);
    assert(call6(SYS_newfstatat,AT_FDCWD,(uintptr_t)path,1,0,0,0)==-EFAULT);
    assert(call6(SYS_newfstatat,AT_FDCWD,1,(uintptr_t)&st,0,0,0)==-EFAULT);
    output_faults(fd); input_faults(); input_sequences(fd);
    unlink("/tmp/cooperative-link"); assert(!symlink("cooperative-file","/tmp/cooperative-link"));
    char link[64]={0};
    assert(call6(SYS_readlinkat,AT_FDCWD,(uintptr_t)"/tmp/cooperative-link",(uintptr_t)link,sizeof(link),0,0)==16);
    assert(!strcmp(link,"cooperative-file"));
    assert(call6(SYS_readlinkat,AT_FDCWD,(uintptr_t)"/tmp/cooperative-link",(uintptr_t)link,0,0,0)==-EINVAL);
    assert(!unlink("/tmp/cooperative-link"));
    assert(!call6(SYS_close,fd,0,0,0,0,0));
    assert(call6(SYS_fstat,fd,(uintptr_t)&st,0,0,0,0)==-EBADF);
    filesystem_operations();
    struct sigaction action={.sa_handler=handler}; sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGUSR1,&action,NULL));
    strict_reentry=1;
    client.opaque[MD_ENTRY_CLIENT_CONTROL]=MD_ENTRY_CLIENT_BUSY;
    assert(!raise(SIGUSR1) && signals==1);
    client.opaque[MD_ENTRY_CLIENT_CONTROL]=0; strict_reentry=0;
    struct md_entry_call unsupported={.number=SYS_clone};
    assert(md_entry_invoke(api,&client,&unsupported)==MD_ENTRY_DECLINED);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_entry_channel *inherited=(void *)client.opaque[MD_ENTRY_CLIENT_CHANNEL];
        assert(inherited && client.opaque[MD_ENTRY_CLIENT_OWNER]==(uintptr_t)inherited->owner);
        uint64_t sequence=inherited->sequence;
        enum md_entry_outcome outcome=stat_try(&client,&st,&result);
        /* The parent may enter a task-affine wait before this child runs.
         * Its disabled channel takes the original kernel call without a
         * helper identity probe; an enabled foreign channel declines. */
        assert(outcome==MD_ENTRY_DECLINED || (outcome==MD_ENTRY_COMPLETED && !result && st.st_size==7));
        assert(inherited->sequence==sequence && !atomic_load(&inherited->caller));
        sampled=(struct md_entry_call){.number=SYS_newfstatat,
            .args={AT_FDCWD,(uintptr_t)path,(uintptr_t)&st}};
        outcome=md_entry_invoke(&identified,&client,&sampled);
        assert(outcome==MD_ENTRY_DECLINED || (outcome==MD_ENTRY_COMPLETED && !sampled.result && st.st_size==7));
        assert(inherited->sequence==sequence && !atomic_load(&inherited->caller));
        struct md_entry_client fresh={0};
        assert(stat_try(&fresh,&st,&result)==MD_ENTRY_COMPLETED && !result);
        execl(executable,executable,"exec",NULL); _exit(90);
    }
    reap(child);
    pthread_t peers[4];
    for (unsigned i=0;i<4;++i) assert(!pthread_create(&peers[i],NULL,threaded,NULL));
    for (unsigned i=0;i<4;++i) assert(!pthread_join(peers[i],NULL));
    capacity(); interrupted(); abandoned_memory(); jit_entry_resume(); context_changes(); credentials(); pid_namespace(); landlocked(); teardown();
    assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!call6(SYS_newfstatat,AT_FDCWD,(uintptr_t)path,(uintptr_t)&st,0,0,0) && st.st_size==7);
    struct md_entry_channel *channel=(void *)client.opaque[MD_ENTRY_CLIENT_CHANNEL];
    assert(channel && channel->input.sequence==channel->sequence
        && channel->output.sequence==channel->sequence && channel->output.size==sizeof(st));
    assert(channel->input.address[0]==(uintptr_t)path && channel->output.destination==(uintptr_t)&st);
    pthread_t protected_thread;
    assert(!pthread_create(&protected_thread,NULL,protected_copy,NULL));
    /* EVENT_WAIT: the new protected thread registers, copies and releases its channel. */
    assert(!pthread_join(protected_thread,NULL));
    assert(!prctl(PR_SET_DUMPABLE,1));
    puts("PASS cooperative protected copy: existing/fresh channels use sequence-bound input and output without remote memory access");
    filters(); filter_exec(executable);
    /* A thread-local filter closes new registration for this image, even after
     * that thread exits. Verify capacity before this irreversible admission. */
    reader_writer_bursts();
    assert(!call6(SYS_renameat2,AT_FDCWD,(uintptr_t)path,AT_FDCWD,(uintptr_t)"/tmp/cooperative-renamed",0,0));
    assert(!call6(SYS_unlinkat,AT_FDCWD,(uintptr_t)"/tmp/cooperative-renamed",0,0,0,0));
    assert(direct_calls && fallback_calls);
    puts("PASS cooperative entry: metadata, pointers, native IO/FDs, signal reentry, fork/exec, threads and nondumpable copy");
}
static void initial_stack(int argc,char **argv,int empty) {
    extern char **environ;
    char *next=argv[0];
    for (int i=0;i<argc;++i) {
        assert(argv[i]==next); next+=strlen(next)+1;
    }
    assert(!argv[argc]);
    if (empty) assert(!environ[0]);
    for (size_t i=0;environ[i];++i) {
        assert(environ[i]==next); next+=strlen(next)+1;
    }
    assert((char *)getauxval(AT_EXECFN)==next && !strcmp(next,"/bin/probe"));
    if (!empty) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            char *args[]={"preserved-argv0","--stack-empty","-silent",NULL};
            char *env[]={NULL};
            execve("/bin/probe",args,env); _exit(99);
        }
        reap(child);
    }
    puts("PASS initial stack: contiguous argv, environment and exec invocation");
}
int main(int argc,char **argv) {
    alarm(60); setbuf(stdout,NULL);
    struct sigaction action={.sa_handler=native_signal}; sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGUSR2,&action,NULL));
    api=(void *)getauxval(MD_AT_COOPERATIVE);
    assert(md_entry_api_valid(api));
    if (argc>1 && !strcmp(argv[1],"--filtered-exec")) {
        struct stat st; long result;
        assert(stat_try(&client,&st,&result)==MD_ENTRY_COMPLETED && !result);
        assert((client.opaque[MD_ENTRY_CLIENT_CONTROL]>>1)==UINT32_MAX);
        puts("PASS inherited filter: fresh client uses kernel without registration"); return 0;
    }
    if (argc>1 && (!strcmp(argv[1],"--initial-stack") || !strcmp(argv[1],"--stack-empty"))) {
        initial_stack(argc,argv,!strcmp(argv[1],"--stack-empty")); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--fd-discovery")) {
        fd_discovery(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--cursor-lease")) {
        cursor_contracts(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--site-discovery")) {
        site_discovery(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--proc-discovery")) {
        proc_discovery(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--jit-busy")) {
        int fd=open(path,O_CREAT|O_RDWR,0600); assert(fd>=0 && write(fd,"payload",7)==7 && !close(fd));
        jit_busy(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--jit-resume")) {
        int fd=open(path,O_CREAT|O_RDWR,0600); assert(fd>=0 && write(fd,"payload",7)==7 && !close(fd));
        jit_entry_resume(); return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--native-signals")) {
        int fd=open(path,O_CREAT|O_RDWR,0600); assert(fd>=0 && write(fd,"payload",7)==7 && !close(fd));
        native_interruptions(); return 0;
    }
    if (argc>1 && (!strcmp(argv[1],"--birth-retirement") || !strcmp(argv[1],"--bursts"))) {
        int fd=open(path,O_CREAT|O_RDWR,0600); assert(fd>=0 && write(fd,"payload",7)==7 && !close(fd));
        if (!strcmp(argv[1],"--birth-retirement")) birth_retirement();
        else reader_writer_bursts();
        return 0;
    }
    if (argc>1 && !strcmp(argv[1],"--host-births")) {
        host_births=1;
        int fd=open(path,O_CREAT|O_RDWR,0600); assert(fd>=0 && write(fd,"payload",7)==7 && !close(fd));
        for (unsigned acl=0;acl<2;++acl) { native_open_exhaustion(acl); birth_watches(acl); }
        concurrent_births();
        puts("PASS catalogue births: host-permission parents preserve exact FD admission and publication");
        return 0;
    }
    if (argc>1 && !strcmp(argv[1],"exec")) {
        struct stat st; long result;
        assert(stat_try(&client,&st,&result)==MD_ENTRY_COMPLETED && !result);
        puts("PASS cooperative entry: fresh auxv and channel after exec"); return 0;
    }
    contracts(argv[0]); return 0;
}
