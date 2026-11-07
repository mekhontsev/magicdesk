#define _GNU_SOURCE
#include "test_syscall_registers.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

_Static_assert(ATOMIC_INT_LOCK_FREE==2,"signal counter must be lock-free");
static _Atomic unsigned signals;
static _Atomic unsigned traps;
static _Atomic unsigned job_ready,job_caught;
static int job_signal;
static pthread_t job_reader;
extern void metadata_job_wait(_Atomic unsigned *,_Atomic unsigned *);
__asm__(".text\n.balign 4\n.global metadata_job_wait\nmetadata_job_wait:\n"
    "mov x8,#80\nmov w9,#1\nstlr w9,[x0]\n"
    "1: ldar w9,[x1]\ncbz w9,1b\nret\n");
static void job_handler(int signal) {
    assert(signal==job_signal);
    atomic_fetch_add_explicit(&job_caught,1,memory_order_release);
}
static void *job_sender(void *unused) {
    (void)unused;
    /* EVENT_WAIT: the reader is in non-SVC code with a metadata syscall number
     * still in x8. The fixture deadline bounds delivery and the reader wait. */
    while (!atomic_load_explicit(&job_ready,memory_order_acquire)) sched_yield();
    assert(!pthread_kill(job_reader,job_signal));
    return NULL;
}
static void metadata_job_delivery(void) {
    const int signals[]={SIGCONT,SIGTSTP,SIGTTIN,SIGTTOU};
    for (unsigned i=0;i<sizeof(signals)/sizeof(*signals);++i) {
        job_signal=signals[i]; job_reader=pthread_self();
        atomic_store(&job_ready,0); atomic_store(&job_caught,0);
        struct sigaction action={.sa_handler=job_handler},previous;
        assert(!sigaction(job_signal,&action,&previous));
        pthread_t sender; assert(!pthread_create(&sender,NULL,job_sender,NULL));
        metadata_job_wait(&job_ready,&job_caught);
        assert(!pthread_join(sender,NULL) && atomic_load(&job_caught)==1);
        assert(!sigaction(job_signal,&previous,NULL));
    }
    puts("PASS retained metadata job signals: four exact-once handlers without an executed SVC");
}
static void interrupted(int signal,siginfo_t *info,void *context) {
    int saved=errno;
    const ucontext_t *entry=context;
    if (sigismember(&entry->uc_sigmask,SIGALRM))
        fprintf(stderr,"shared memory entry mask: alarm blocked pc=%#llx sp=%#llx\n",
            entry->uc_mcontext.pc,entry->uc_mcontext.sp);
    assert(sigismember(&entry->uc_sigmask,SIGALRM)==0);
    sigset_t mask; int read_mask=sigprocmask(SIG_SETMASK,NULL,&mask);
    if (read_mask) {
        ucontext_t *frame=context;
        fprintf(stderr,"shared memory mask read: result=%d errno=%d tid=%ld interrupted=%#llx\n",
            read_mask,errno,syscall(SYS_gettid),frame->uc_mcontext.pc);
    }
    assert(!read_mask);
    assert(signal==SIGALRM && info->si_signo==SIGALRM && info->si_code==SI_KERNEL);
    if (sigismember(&mask,SIGUSR2)!=1 || sigismember(&mask,SIGTRAP)!=1) {
        ucontext_t *frame=context;
        fprintf(stderr,"shared memory signal mask: usr2=%d trap=%d pc=%#llx\n",
            sigismember(&mask,SIGUSR2),sigismember(&mask,SIGTRAP),frame->uc_mcontext.pc);
    }
    assert(sigismember(&mask,SIGUSR2)==1 && sigismember(&mask,SIGTRAP)==1);
    ++signals; errno=saved;
}
static void denied(int signal,siginfo_t *info,void *context) {
    assert(signal==SIGSYS && info->si_syscall==SYS_fstat && info->si_arch==AUDIT_ARCH_AARCH64);
    ucontext_t *saved=context;
    saved->uc_mcontext.regs[0]=(unsigned long)-EACCES;
    ++traps;
}
static void filter(void) {
    struct sigaction action={.sa_sigaction=denied,.sa_flags=SA_SIGINFO};
    assert(!sigaction(SIGSYS,&action,NULL));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fstat,0,5),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)-2,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)-3,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
struct shared_memory_case { const char *directory; unsigned thread; int filtered; };
#define REQUIRE(expression) do { if (!(expression)) { \
    fprintf(stderr,"shared memory thread=%u iteration=%u: %s errno=%d signals=%d\n", \
        c->thread,i,#expression,errno,signals); abort(); } } while (0)
static void *shared_memory(void *opaque) {
    const struct shared_memory_case *c=opaque;
    char link[640];
    assert(snprintf(link,sizeof(link),"%s/link",c->directory)<(int)sizeof(link));
    for (unsigned i=0;i<1024;++i) {
        char path[640];
        REQUIRE(snprintf(path,sizeof(path),"%s/%u-%u",c->directory,c->thread,i)<(int)sizeof(path));
        int rw,ro;
        do { rw=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); } while (rw<0 && errno==EINTR);
        REQUIRE(rw>=0);
        do { ro=open(path,O_RDONLY|O_CLOEXEC); } while (ro<0 && errno==EINTR);
        REQUIRE(ro>=0);
        struct stat before,write_stat,read_stat;
        /* Stock shared-memory users do not retry fstat on a regular file. */
        REQUIRE(!fstat(rw,&before) && before.st_size==0);
        int result;
        do { result=ftruncate(rw,8); } while (result && errno==EINTR);
        REQUIRE(!result);
        REQUIRE(!fstat(rw,&write_stat) && write_stat.st_size==8);
        REQUIRE(!test_syscall_registers(SYS_fstat,ro,(unsigned long)&read_stat,0x1234,0x5678)
            && read_stat.st_size==8);
        REQUIRE(write_stat.st_dev==read_stat.st_dev && write_stat.st_ino==read_stat.st_ino);
        struct stat path_stat;
        REQUIRE(!fstatat(AT_FDCWD,path,&path_stat,0) && path_stat.st_size==8
            && path_stat.st_ino==read_stat.st_ino);
        char target[16];
        REQUIRE(readlink(link,target,sizeof(target))==6 && !memcmp(target,"target",6));
        if (c->filtered) {
            REQUIRE(syscall(SYS_fstat,-2,&path_stat)==-1 && errno==EACCES);
            REQUIRE(syscall(SYS_fstat,-3,&path_stat)==-1 && errno==EACCES);
        }
        REQUIRE((fcntl(rw,F_GETFL)&O_ACCMODE)==O_RDWR);
        REQUIRE((fcntl(ro,F_GETFL)&O_ACCMODE)==O_RDONLY);
        unsigned long *w=mmap(NULL,8,PROT_READ|PROT_WRITE,MAP_SHARED,rw,0);
        REQUIRE(w!=MAP_FAILED);
        const unsigned long *r=mmap(NULL,8,PROT_READ,MAP_SHARED,ro,0);
        REQUIRE(r!=MAP_FAILED);
        *w=i+1; REQUIRE(*r==i+1);
        do { result=unlink(path); } while (result && errno==EINTR);
        REQUIRE(!result && !close(rw) && !close(ro));
        REQUIRE(*r==i+1 && !munmap(w,8) && !munmap((void *)r,8));
    }
    return NULL;
}
int test_shared_memory_guest(const char *root,int protected,int restart) {
    char directory[512];
    assert(snprintf(directory,sizeof(directory),"%s/md-shared-memory-XXXXXX",root)<(int)sizeof(directory));
    assert(mkdtemp(directory));
    char link[640]; assert(snprintf(link,sizeof(link),"%s/link",directory)<(int)sizeof(link));
    assert(!symlink("target",link));
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    if (restart==4) metadata_job_delivery();
    sigset_t mask,original;
    sigemptyset(&mask); sigaddset(&mask,SIGUSR2); sigaddset(&mask,SIGTRAP);
    assert(!sigprocmask(SIG_BLOCK,&mask,&original));
    struct sigaction trap={.sa_sigaction=interrupted,.sa_flags=SA_SIGINFO},original_trap,current_trap;
    if (restart&16) trap=(struct sigaction){.sa_handler=SIG_IGN};
    assert(!sigaction(SIGTRAP,&trap,&original_trap));
    if (restart&2) filter();
    struct sigaction action={.sa_sigaction=interrupted,.sa_flags=SA_SIGINFO|((restart&1) ? SA_RESTART : 0)};
    assert(!sigaction(SIGALRM,&action,NULL));
    pid_t stopper=0;
    if (restart&4) {
        pid_t parent=getpid();
        stopper=fork(); assert(stopper>=0);
        if (!stopper) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
            if (getppid()!=parent) _exit(99);
            for (unsigned i=0;i<64;++i) {
                /* Intentional adversarial signal timing, not a readiness wait. */
                usleep(1000); assert(!kill(parent,SIGSTOP));
                usleep(1000); assert(!kill(parent,SIGCONT));
            }
            _exit(0);
        }
    }
    /* Signals stress syscall completion, not readiness or settling. The
     * launch deadline bounds the entire fixture and all its peers. */
    unsigned interval=restart&8 ? 100 : 1000;
    struct itimerval timer={.it_interval={0,interval},.it_value={0,interval}};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    struct shared_memory_case cases[2]={{directory,0,restart&2},{directory,1,restart&2}};
    pthread_t peer; assert(!pthread_create(&peer,NULL,shared_memory,&cases[1]));
    shared_memory(&cases[0]);
    assert(!pthread_join(peer,NULL));
    if (stopper) {
        int status; pid_t waited;
        do { waited=waitpid(stopper,&status,0); } while (waited<0 && errno==EINTR);
        assert(waited==stopper && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    timer=(struct itimerval){0}; assert(!setitimer(ITIMER_REAL,&timer,NULL));
    assert(signals>0 && !unlink(link) && !rmdir(directory));
    assert(!(restart&2) || traps==2048);
    assert(!sigprocmask(SIG_SETMASK,NULL,&mask));
    if (sigismember(&mask,SIGUSR2)!=1 || sigismember(&mask,SIGTRAP)!=1
            || sigismember(&mask,SIGALRM)!=0 || sigismember(&mask,SIGSYS)!=0)
        fprintf(stderr,"shared memory final mask: usr2=%d trap=%d alarm=%d sys=%d\n",
            sigismember(&mask,SIGUSR2),sigismember(&mask,SIGTRAP),
            sigismember(&mask,SIGALRM),sigismember(&mask,SIGSYS));
    assert(sigismember(&mask,SIGUSR2)==1 && sigismember(&mask,SIGTRAP)==1
        && sigismember(&mask,SIGALRM)==0 && sigismember(&mask,SIGSYS)==0);
    assert(!sigaction(SIGTRAP,NULL,&current_trap));
    assert((restart&16) ? current_trap.sa_handler==SIG_IGN : current_trap.sa_sigaction==interrupted);
    assert(!sigaction(SIGTRAP,&original_trap,NULL));
    assert(!sigprocmask(SIG_SETMASK,&original,NULL));
    printf("PASS shared memory under signals: protected=%d restart=%d delivered=%d\n",protected,restart,signals);
    return 0;
}
