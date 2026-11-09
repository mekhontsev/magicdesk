#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <linux/futex.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <grp.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t mutex;
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
static volatile pid_t parent_tid, child_tid;
static int clone_child(void *unused) {
    (void)unused;
    assert(parent_tid==syscall(SYS_gettid));
    assert(child_tid==parent_tid);
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
        parent_tid=child_tid=-1;
        pid_t child=clone(clone_child,stack+length,CLONE_VM|CLONE_PARENT_SETTID
            |CLONE_CHILD_SETTID|CLONE_CHILD_CLEARTID|SIGCHLD,NULL,&parent_tid,NULL,&child_tid);
        assert(child>0 && parent_tid==child);
        int status;
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(!child_tid);
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
static void robust_process(const char *mode) {
    assert(!strcmp(mode,"exit") || !strcmp(mode,"kill") || !strcmp(mode,"exec"));
    pthread_mutex_t *lock=mmap(NULL,sizeof(*lock),PROT_READ|PROT_WRITE,
        MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(lock!=MAP_FAILED);
    robust_mutex_init(lock,1);
    int channel[2]; assert(!pipe(channel));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(channel[0]);
        assert(!pthread_mutex_lock(lock));
        assert(write(channel[1],"x",1)==1);
        if (!strcmp(mode,"kill")) for (;;) pause();
        if (!strcmp(mode,"exec")) {
            execl("/bin/true","true",NULL);
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
int main(int argc,char **argv) {
    if (argc==2) {
        if (!strcmp(argv[1],"--robust-query")) { robust_query(); return 0; }
        if (!strcmp(argv[1],"--robust-thread")) { robust_thread(); return 0; }
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
