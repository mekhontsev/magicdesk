#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <linux/futex.h>
#include <pthread.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <grp.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/signalfd.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t mutex;
static const char *program;
static pthread_barrier_t ready, done;
static void *credential_worker(void *unused) {
    (void)unused;
    pthread_barrier_wait(&ready);
    pthread_barrier_wait(&done);
    assert(getuid()==0 && getgid()==0);
    return NULL;
}
static void credentials(void) {
    pthread_t workers[3];
    assert(!pthread_barrier_init(&ready,NULL,4));
    assert(!pthread_barrier_init(&done,NULL,4));
    for (unsigned i=0;i<3;++i) assert(!pthread_create(&workers[i],NULL,credential_worker,NULL));
    pthread_barrier_wait(&ready);
    assert(!setgroups(0,NULL));
    assert(!setgid(0));
    assert(!setuid(0));
    pthread_barrier_wait(&done);
    for (unsigned i=0;i<3;++i) assert(!pthread_join(workers[i],NULL));
    assert(!pthread_barrier_destroy(&ready));
    assert(!pthread_barrier_destroy(&done));
}
static volatile pid_t parent_tid, child_tid, redirected_tid;
static unsigned clear_target;
/* Exercise the kernel clone contract: libc wrappers may reject clear_child_tid
 * for application-created tasks before issuing any syscall. */
extern long identity_clone(unsigned long,void *,volatile pid_t *,void *,volatile pid_t *,int (*)(void *));
__asm__(".text\n.global identity_clone\n.type identity_clone,%function\nidentity_clone:\n"
    "stp x19,x30,[sp,#-16]!\nmov x19,x5\nmov x8,#220\nsvc #0\n"
    "cbnz x0,1f\nmov x0,#0\nblr x19\nmov x8,#93\nsvc #0\nbrk #0\n"
    "1: ldp x19,x30,[sp],#16\nret\n.size identity_clone,.-identity_clone\n");
static int clone_child(void *unused) {
    (void)unused;
    assert(parent_tid==syscall(SYS_gettid));
    assert(child_tid==parent_tid);
    /* Repeat at the same syscall site before changing the kernel exit target;
     * both cold and resident entries must return the visible thread identity. */
    for (unsigned i=0;i<3;++i) {
        volatile pid_t *target=&child_tid;
        if (i==2 && clear_target) target=clear_target==1 ? &redirected_tid
            : clear_target==2 ? NULL : (void *)1;
        assert(syscall(SYS_set_tid_address,target)==parent_tid);
    }
    return 0;
}
static void *worker(void *unused) {
    (void)unused;
    assert(!pthread_mutex_lock(&mutex));
    pid_t tid=(pid_t)syscall(SYS_gettid);
    int error=pthread_mutex_unlock(&mutex);
    if (error) fprintf(stderr,"thread tid=%d mutex unlock=%d\n",tid,error);
    assert(!error);
    return NULL;
}
static void threads(void) {
    pthread_mutexattr_t attr;
    assert(!pthread_mutexattr_init(&attr));
    assert(!pthread_mutexattr_settype(&attr,PTHREAD_MUTEX_RECURSIVE));
    assert(!pthread_mutex_init(&mutex,&attr));
    assert(!pthread_mutexattr_destroy(&attr));
    for (unsigned i=0;i<64;++i) {
        pthread_t thread;
        assert(!pthread_create(&thread,NULL,worker,NULL));
        assert(!pthread_join(thread,NULL));
    }
    assert(!pthread_mutex_destroy(&mutex));
    size_t length=1024*1024;
    char *stack=malloc(length);
    assert(stack);
    for (unsigned i=0;i<16;++i) {
        clear_target=i%4;
        parent_tid=child_tid=-1;
        redirected_tid=-1;
        pid_t child=(pid_t)identity_clone(CLONE_VM|CLONE_PARENT_SETTID
            |CLONE_CHILD_SETTID|CLONE_CHILD_CLEARTID|SIGCHLD,stack+length,&parent_tid,NULL,&child_tid,clone_child);
        if (child<=0 || parent_tid!=child)
            fprintf(stderr,"clone publication iteration=%u child=%d parent_tid=%d child_tid=%d errno=%d\n",
                i,child,parent_tid,child_tid,errno);
        assert(child>0 && parent_tid==child);
        int status;
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(child_tid==(clear_target ? child : 0));
        assert(redirected_tid==(clear_target==1 ? 0 : -1));
    }
    free(stack);
}
static void priority_inheritance(int virtual) {
    int word=0, other=0;
    const int operations[]={FUTEX_LOCK_PI,FUTEX_UNLOCK_PI,FUTEX_TRYLOCK_PI,
        FUTEX_WAIT_REQUEUE_PI,FUTEX_CMP_REQUEUE_PI,FUTEX_LOCK_PI2};
    if (virtual) for (unsigned i=0;i<sizeof(operations)/sizeof(*operations);++i)
        for (unsigned flags=0;flags<4;++flags) {
            errno=0;
            assert(syscall(SYS_futex,&word,operations[i]
                |(flags&1 ? FUTEX_PRIVATE_FLAG : 0)|(flags&2 ? FUTEX_CLOCK_REALTIME : 0),
                1,NULL,&other,0)==-1
                && errno==(operations[i]==FUTEX_UNLOCK_PI ? ENOSYS : EOPNOTSUPP));
            assert(!word && !other);
        }
    pthread_mutexattr_t attr;
    assert(!pthread_mutexattr_init(&attr));
    assert(!pthread_mutexattr_settype(&attr,PTHREAD_MUTEX_RECURSIVE));
    int error=pthread_mutexattr_setprotocol(&attr,PTHREAD_PRIO_INHERIT);
    assert(!error || (virtual && error==ENOTSUP));
    if (!error) error=pthread_mutex_init(&mutex,&attr);
    if (virtual) {
        assert(error==ENOTSUP);
        assert(!pthread_mutexattr_setprotocol(&attr,PTHREAD_PRIO_NONE));
        assert(!pthread_mutex_init(&mutex,&attr));
    } else assert(!error);
    assert(!pthread_mutexattr_destroy(&attr));
    for (unsigned i=0;i<16;++i) {
        assert(!pthread_mutex_lock(&mutex));
        pthread_t thread; assert(!pthread_create(&thread,NULL,worker,NULL));
        assert(!pthread_mutex_unlock(&mutex));
        assert(!pthread_join(thread,NULL));
    }
    assert(!pthread_mutex_destroy(&mutex));
    puts("PASS PI futex admission and ordinary mutex fallback");
}
static void robust_mutex_init(pthread_mutex_t *,int);
static void robust_query(void) {
    pthread_mutex_t lock;
    /* musl registers lazily for process-shared robust locks. */
    robust_mutex_init(&lock,1);
    assert(!pthread_mutex_lock(&lock));
    struct robust_list_head *head=NULL;
    size_t length=0;
    assert(!syscall(SYS_get_robust_list,0,&head,&length));
    assert(head && length==sizeof(*head) && head->list.next);
    assert(syscall(SYS_set_robust_list,head,sizeof(*head)-1)==-1 && errno==EINVAL);
    struct robust_list_head *again=NULL;
    assert(!syscall(SYS_get_robust_list,syscall(SYS_gettid),&again,&length));
    assert(again==head && length==sizeof(*head));
    length=0;
    errno=0;
    assert(syscall(SYS_get_robust_list,0,(void *)1,&length)==-1 && errno==EFAULT);
    assert(length==sizeof(*head));
    again=NULL;
    errno=0;
    assert(syscall(SYS_get_robust_list,0,&again,(void *)1)==-1 && errno==EFAULT);
    assert(!again);
    assert(!pthread_mutex_unlock(&lock));
    assert(!pthread_mutex_destroy(&lock));
    puts("PASS robust list registration and query");
}
static void robust_mutex_init(pthread_mutex_t *lock,int shared) {
    pthread_mutexattr_t attr;
    assert(!pthread_mutexattr_init(&attr));
    assert(!pthread_mutexattr_setrobust(&attr,PTHREAD_MUTEX_ROBUST));
    if (shared) assert(!pthread_mutexattr_setpshared(&attr,PTHREAD_PROCESS_SHARED));
    assert(!pthread_mutex_init(lock,&attr));
    assert(!pthread_mutexattr_destroy(&attr));
}
static void robust_recover(pthread_mutex_t *lock) {
    struct timespec deadline;
    assert(!clock_gettime(CLOCK_REALTIME,&deadline));
    deadline.tv_sec+=3;
    int error=pthread_mutex_timedlock(lock,&deadline);
    if (error!=EOWNERDEAD) fprintf(stderr,"robust owner death: result=%d (%s)\n",error,strerror(error));
    assert(error==EOWNERDEAD);
    assert(!pthread_mutex_consistent(lock));
    assert(!pthread_mutex_unlock(lock));
    assert(!pthread_mutex_trylock(lock));
    assert(!pthread_mutex_unlock(lock));
}
static void robust_children(void) {
    pthread_mutex_t lock;
    robust_mutex_init(&lock,1);
    assert(!pthread_mutex_lock(&lock));
    const unsigned options[]={0,__WCLONE,__WALL};
    for (unsigned i=0;i<sizeof(options)/sizeof(*options);++i) {
        int status;
        errno=0;
        long result=syscall(SYS_wait4,-1,&status,WNOHANG|options[i],NULL);
        if (result!=-1 || errno!=ECHILD)
            fprintf(stderr,"childless wait options=%#x result=%ld errno=%d\n",options[i],result,errno);
        assert(result==-1 && errno==ECHILD);
        siginfo_t info={0};
        errno=0;
        assert(syscall(SYS_waitid,P_ALL,0,&info,WEXITED|WNOHANG|options[i],NULL)==-1 && errno==ECHILD);
    }
    for (unsigned i=0;i<4;++i) {
        int clone_wait=i&1;
        pid_t child=(pid_t)syscall(SYS_clone,clone_wait ? 0 : SIGCHLD,0,0,0,0);
        assert(child>=0);
        if (!child) {
            /* Allow the parent to enter its blocking wait before completion. */
            struct timespec pause={.tv_nsec=20000000};
            syscall(SYS_nanosleep,&pause,NULL);
            _exit(47);
        }
        unsigned flags=clone_wait ? __WCLONE : __WALL;
        if (i<2) {
            int status;
            assert(waitpid(-1,&status,flags)==child && WIFEXITED(status) && WEXITSTATUS(status)==47);
        } else {
            siginfo_t info={0};
            assert(!waitid(P_ALL,0,&info,WEXITED|WNOWAIT|flags));
            assert(info.si_pid==child && info.si_code==CLD_EXITED && info.si_status==47);
            assert(!waitid(P_ALL,0,&info,WEXITED|flags) && info.si_pid==child);
        }
        int status;
        assert(waitpid(-1,&status,WNOHANG|__WALL)==-1 && errno==ECHILD);
    }
    for (unsigned observe=0;observe<2;++observe) {
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(49);
        assert(!syscall(SYS_waitid,P_ALL,0,NULL,WEXITED|__WALL|(observe ? WNOWAIT : 0),NULL));
        int status;
        if (observe)
            assert(waitpid(child,&status,__WALL)==child && WIFEXITED(status) && WEXITSTATUS(status)==49);
        assert(waitpid(-1,&status,WNOHANG|__WALL)==-1 && errno==ECHILD);
    }
    int channel[2]; assert(!pipe(channel));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[1]);
        assert(!raise(SIGSTOP));
        char byte; assert(read(channel[0],&byte,1)==0);
        _exit(0);
    }
    close(channel[0]);
    siginfo_t info={0};
    assert(!waitid(P_ALL,0,&info,WSTOPPED|__WALL));
    assert(info.si_pid==child && info.si_code==CLD_STOPPED && info.si_status==SIGSTOP);
    assert(!kill(child,SIGCONT));
    assert(!waitid(P_ALL,0,&info,WCONTINUED|__WALL));
    assert(info.si_pid==child && info.si_code==CLD_CONTINUED);
    close(channel[1]);
    int status;
    assert(waitpid(-1,&status,__WALL)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!pthread_mutex_unlock(&lock));
    assert(!pthread_mutex_destroy(&lock));
    puts("PASS robust executor excluded from childless waits");
}
static pthread_barrier_t child_barrier;
static pid_t thread_child;
static void *robust_child_worker(void *unused) {
    (void)unused;
    thread_child=(pid_t)syscall(SYS_clone,0,0,0,0,0); assert(thread_child>=0);
    if (!thread_child) for (;;) pause();
    pthread_barrier_wait(&child_barrier);
    pthread_barrier_wait(&child_barrier);
    assert(!kill(thread_child,SIGKILL));
    int status;
    assert(waitpid(-1,&status,__WCLONE|__WNOTHREAD)==thread_child && WIFSIGNALED(status));
    return NULL;
}
static void robust_thread_children(void) {
    pthread_mutex_t lock;
    robust_mutex_init(&lock,1); assert(!pthread_mutex_lock(&lock));
    assert(!pthread_barrier_init(&child_barrier,NULL,2));
    pthread_t worker; assert(!pthread_create(&worker,NULL,robust_child_worker,NULL));
    pthread_barrier_wait(&child_barrier);
    int status;
    assert(waitpid(-1,&status,WNOHANG|__WCLONE|__WNOTHREAD)==-1 && errno==ECHILD);
    assert(waitpid(-1,&status,WNOHANG|__WCLONE)==0);
    pthread_barrier_wait(&child_barrier);
    assert(!pthread_join(worker,NULL));
    assert(!pthread_barrier_destroy(&child_barrier));
    assert(!pthread_mutex_unlock(&lock)); assert(!pthread_mutex_destroy(&lock));
    puts("PASS robust child wait retains exact native parent-thread eligibility");
}
static void robust_notices(void) {
    pthread_mutex_t lock;
    robust_mutex_init(&lock,1); assert(!pthread_mutex_lock(&lock));
    sigset_t mask,old; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
    assert(!pthread_sigmask(SIG_BLOCK,&mask,&old));
    int fd=signalfd(-1,&mask,SFD_NONBLOCK|SFD_CLOEXEC); assert(fd>=0);
    for (unsigned i=0;i<12;++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            pthread_mutex_t child_lock;
            robust_mutex_init(&child_lock,1); assert(!pthread_mutex_lock(&child_lock));
            if (i&1) { execl(program,program,"--exec-complete",NULL); _exit(120); }
            _exit(0);
        }
        siginfo_t exited={0};
        assert(!waitid(P_PID,child,&exited,WEXITED|WNOWAIT) && exited.si_pid==child);
        struct pollfd poller={.fd=fd,.events=POLLIN};
        assert(poll(&poller,1,2000)==1);
        struct signalfd_siginfo info;
        assert(read(fd,&info,sizeof(info))==sizeof(info));
        if (info.ssi_pid!=(unsigned)child)
            fprintf(stderr,"unexpected child notice: expected=%d actual=%u code=%d\n",child,info.ssi_pid,info.ssi_code);
        assert(info.ssi_signo==SIGCHLD && info.ssi_pid==(unsigned)child && info.ssi_code==CLD_EXITED);
        int status;
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    struct pollfd poller={.fd=fd,.events=POLLIN};
    assert(poll(&poller,1,100)==0);
    close(fd); assert(!pthread_sigmask(SIG_SETMASK,&old,NULL));
    assert(!pthread_mutex_unlock(&lock)); assert(!pthread_mutex_destroy(&lock));
    puts("PASS robust retirement preserves real SIGCHLD and excludes private notices from signalfd");
}
static void *robust_worker(void *lock) {
    assert(!pthread_mutex_lock(lock));
    return NULL;
}
static void robust_thread(void) {
    pthread_mutex_t lock;
    robust_mutex_init(&lock,0);
    for (unsigned i=0;i<8;++i) {
        pthread_t thread;
        assert(!pthread_create(&thread,NULL,robust_worker,&lock));
        assert(!pthread_join(thread,NULL));
        robust_recover(&lock);
    }
    assert(!pthread_mutex_destroy(&lock));
    puts("PASS robust mutex thread owner death");
}
struct robust_exec_owner { pthread_mutex_t *lock; int channel; };
static void *robust_exec_worker(void *context) {
    struct robust_exec_owner *owner=context;
    assert(!pthread_mutex_lock(owner->lock));
    assert(write(owner->channel,"x",1)==1);
    execl(program,program,"--exec-complete",NULL);
    _exit(120);
}
static void robust_process(const char *mode) {
    assert(!strcmp(mode,"exit") || !strcmp(mode,"kill") || !strcmp(mode,"exec") || !strcmp(mode,"thread-exec"));
    pthread_mutex_t *lock=mmap(NULL,sizeof(*lock),PROT_READ|PROT_WRITE,
        MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(lock!=MAP_FAILED);
    robust_mutex_init(lock,1);
    int channel[2]; assert(!pipe(channel));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[0]);
        if (!strcmp(mode,"thread-exec")) {
            struct robust_exec_owner owner={lock,channel[1]};
            pthread_t thread; assert(!pthread_create(&thread,NULL,robust_exec_worker,&owner));
            for (;;) pause();
        }
        assert(!pthread_mutex_lock(lock));
        assert(write(channel[1],"x",1)==1);
        if (!strcmp(mode,"kill")) for (;;) pause();
        if (!strcmp(mode,"exec")) {
            execl(program,program,"--exec-complete",NULL);
            _exit(120);
        }
        _exit(0);
    }
    close(channel[1]);
    char byte; assert(read(channel[0],&byte,1)==1);
    close(channel[0]);
    int killed=!strcmp(mode,"kill");
    if (killed) assert(!kill(child,SIGKILL));
    int status;
    assert(waitpid(child,&status,0)==child);
    assert(killed ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL
        : WIFEXITED(status) && !WEXITSTATUS(status));
    robust_recover(lock);
    assert(!pthread_mutex_destroy(lock));
    assert(!munmap(lock,sizeof(*lock)));
    printf("PASS robust shared mutex owner %s\n",mode);
}
static void robust_orphan(void) {
    assert(!prctl(PR_SET_CHILD_SUBREAPER,1));
    pthread_mutex_t *lock=mmap(NULL,sizeof(*lock),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(lock!=MAP_FAILED); robust_mutex_init(lock,1);
    int channel[2]; assert(!pipe(channel));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[0]);
        pid_t orphan=fork(); assert(orphan>=0);
        if (!orphan) {
            assert(!pthread_mutex_lock(lock));
            pid_t self=getpid(); assert(write(channel[1],&self,sizeof(self))==sizeof(self));
            for (;;) pause();
        }
        _exit(0);
    }
    close(channel[1]);
    pid_t orphan; assert(read(channel[0],&orphan,sizeof(orphan))==sizeof(orphan)); close(channel[0]);
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!kill(orphan,SIGKILL));
    assert(waitpid(-1,&status,__WALL)==orphan && WIFSIGNALED(status));
    robust_recover(lock);
    assert(waitpid(-1,&status,WNOHANG|__WALL)==-1 && errno==ECHILD);
    assert(!pthread_mutex_destroy(lock)); assert(!munmap(lock,sizeof(*lock)));
    puts("PASS robust owner death and private cleanup after subreaper adoption");
}
int main(int argc,char **argv) {
    program=argv[0];
    if (argc==2) {
        if (!strcmp(argv[1],"--exec-complete")) return 0;
        if (!strcmp(argv[1],"--robust-query")) { robust_query(); return 0; }
        if (!strcmp(argv[1],"--robust-thread")) { robust_thread(); return 0; }
        if (!strcmp(argv[1],"--robust-children")) { robust_children(); return 0; }
        if (!strcmp(argv[1],"--robust-thread-children")) { robust_thread_children(); return 0; }
        if (!strcmp(argv[1],"--robust-notices")) { robust_notices(); return 0; }
        if (!strcmp(argv[1],"--robust-orphan")) { robust_orphan(); return 0; }
        if (!strncmp(argv[1],"--robust-",9)) { robust_process(argv[1]+9); return 0; }
        assert(!strcmp(argv[1],"--pid-futex"));
        priority_inheritance(getpid()==1);
        assert(!prctl(PR_SET_DUMPABLE,0));
        priority_inheritance(getpid()==1);
        return 0;
    }
    assert(argc==1);
    credentials();
    threads();
    assert(!prctl(PR_SET_DUMPABLE,0));
    credentials();
    threads();
    puts("PASS thread identity: recursive mutex ownership is stable from first instruction");
    return 0;
}
