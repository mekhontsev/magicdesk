#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/msg.h>
#include <sys/sem.h>
#include <sys/shm.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

union argument { int value; unsigned short *array; struct semid_ds *info; };
static int ctl(int id, int n, int op, int value) { return semctl(id,n,op,(union argument){.value=value}); }
static void exited(pid_t pid) {
    int status; assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void atomic_ops(void) {
    int id=semget(IPC_PRIVATE,2,0600); assert(id>=0);
    unsigned short values[]={1,0};
    assert(!semctl(id,0,SETALL,(union argument){.array=values}));
    struct sembuf ops[]={{0,-1,IPC_NOWAIT},{1,-1,IPC_NOWAIT}};
    assert(semop(id,ops,2)==-1 && errno==EAGAIN && ctl(id,0,GETVAL,0)==1);
    ops[1]=(struct sembuf){0,1,SEM_UNDO}; ops[0].sem_flg=SEM_UNDO;
    assert(!semop(id,ops,2) && ctl(id,0,GETVAL,0)==1);
    struct semid_ds info; assert(!semctl(id,0,IPC_STAT,(union argument){.info=&info}));
    assert(info.sem_nsems==2 && info.sem_otime && info.sem_perm.uid==getuid());
    assert(ctl(id,0,SETVAL,32768)==-1 && errno==ERANGE && ctl(id,0,GETVAL,0)==1);
    ops[0]=(struct sembuf){2,1,0}; assert(semop(id,ops,1)==-1 && errno==EFBIG);
    assert(!ctl(id,0,IPC_RMID,0));
}
static volatile sig_atomic_t signaled;
static void caught(int sig) { signaled=sig; (void)getuid(); }
static void wait_total(int id, int command, int expected) {
    struct timespec start, now; assert(!clock_gettime(CLOCK_MONOTONIC,&start));
    // STATE_POLL: SysV exposes waiter counts but no registration event. Yield
    // only in this native fixture; the deadline fails, never proves blocking.
    for (;;) {
        int count=ctl(id,0,command,0); assert(count>=0); if (count==expected) return;
        assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-start.tv_sec<5);
        sched_yield();
    }
}
static void wait_count(int id, int command) { wait_total(id,command,1); }
static void wait_registered(int id) { wait_count(id,GETNCNT); }
static void waiting(const char *mode) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0);
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        struct sigaction action={.sa_handler=caught,.sa_flags=SA_RESTART};
        assert(!sigaction(SIGUSR1,&action,NULL));
        struct sembuf op={0,-1,0};
        int r=semop(id,&op,1), error=errno;
        if (!strcmp(mode,"sem-signal")) assert(r==-1 && error==EINTR && signaled==SIGUSR1);
        else if (!strcmp(mode,"sem-remove")) assert(r==-1 && error==EIDRM);
        else assert(!r);
        _exit(0);
    }
    wait_registered(id);
    if (!strcmp(mode,"sem-signal")) assert(!kill(pid,SIGUSR1));
    else if (!strcmp(mode,"sem-remove")) assert(!ctl(id,0,IPC_RMID,0));
    else assert(!ctl(id,0,SETVAL,1));
    exited(pid);
    if (strcmp(mode,"sem-remove")) {
        assert(!ctl(id,0,GETNCNT,0)); assert(!ctl(id,0,IPC_RMID,0));
    }
}
static void timed(void) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0);
    struct sembuf op={0,-1,0};
    struct timespec bound={0,1000000};
    // EVENT_WAIT: an unavailable semaphore reaches the requested timeout.
    assert(semtimedop(id,&op,1,&bound)==-1 && errno==EAGAIN);
    assert(bound.tv_nsec==1000000 && !ctl(id,0,GETNCNT,0));
    assert(!ctl(id,0,IPC_RMID,0));
}
static void *thread_lock(void *data) {
    int id=*(int *)data; struct sembuf op={0,-1,SEM_UNDO}; assert(!semop(id,&op,1)); return NULL;
}
static void undo(const char *self, const char *mode) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0 && !ctl(id,0,SETVAL,1));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        if (!strcmp(mode,"undo-thread")) {
            pthread_t thread; assert(!pthread_create(&thread,NULL,thread_lock,&id));
            assert(!pthread_join(thread,NULL) && ctl(id,0,GETVAL,0)==0);
        } else {
            struct sembuf op={0,-1,SEM_UNDO}; assert(!semop(id,&op,1));
            pid_t grandchild=fork(); assert(grandchild>=0);
            if (!grandchild) _exit(0);
            exited(grandchild); assert(ctl(id,0,GETVAL,0)==0);
            char number[32]; snprintf(number,sizeof(number),"%d",id);
            execl(self,self,"undo-exec",number,(char *)NULL); _exit(99);
        }
        _exit(0);
    }
    exited(pid); assert(ctl(id,0,GETVAL,0)==1);
    assert(!ctl(id,0,IPC_RMID,0));
}
static void messages(void) {
    int id=msgget(IPC_PRIVATE,0600); assert(id>=0);
    struct { long type; char text[16]; } msg={.type=5,.text="hello"};
    assert(!msgsnd(id,&msg,5,0)); msg.type=2; assert(!msgsnd(id,&msg,5,0));
    assert(msgrcv(id,&msg,1,-5,IPC_NOWAIT)==-1 && errno==E2BIG);
    struct msqid_ds info; assert(!msgctl(id,IPC_STAT,&info) && info.msg_qnum==2);
    assert(msgrcv(id,&msg,16,-5,IPC_NOWAIT)==5 && msg.type==2);
    void *bad=mmap(NULL,4096,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); assert(bad!=MAP_FAILED);
    assert(msgrcv(id,bad,16,0,IPC_NOWAIT)==-1 && errno==EFAULT);
    // Linux consumes the selected message before copying it to user memory.
    assert(!msgctl(id,IPC_STAT,&info) && info.msg_qnum==0);
    msg.type=5; assert(!msgsnd(id,&msg,5,0));
    assert(msgrcv(id,&msg,2,0,MSG_NOERROR|IPC_NOWAIT)==2 && msg.type==5 && !memcmp(msg.text,"he",2));
    assert(msgrcv(id,&msg,16,0,IPC_NOWAIT)==-1 && errno==ENOMSG);
    msg.type=7; assert(!msgsnd(id,&msg,0,0)); msg.type=8; assert(!msgsnd(id,&msg,0,0));
    assert(msgrcv(id,&msg,16,7,MSG_EXCEPT|IPC_NOWAIT)==0 && msg.type==8);
    assert(msgrcv(id,&msg,16,0,IPC_NOWAIT)==0 && msg.type==7);
    assert(!msgctl(id,IPC_STAT,&info)); info.msg_qbytes=1; assert(!msgctl(id,IPC_SET,&info));
    assert(!msgsnd(id,&msg,1,0)); assert(msgsnd(id,&msg,1,IPC_NOWAIT)==-1 && errno==EAGAIN);
    assert(!msgctl(id,IPC_RMID,NULL)); assert(!munmap(bad,4096));
}
static void permissions(void) {
    int id=semget(IPC_PRIVATE,1,0200); assert(id>=0);
    struct sembuf op={0,1,0}; assert(!semop(id,&op,1));
    assert(ctl(id,0,GETVAL,0)==-1 && errno==EACCES);
    op.sem_op=0; op.sem_flg=IPC_NOWAIT; assert(semop(id,&op,1)==-1 && errno==EACCES);
    assert(!ctl(id,0,IPC_RMID,0));
}
static void zero_wait(void) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0 && !ctl(id,0,SETVAL,1));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) { struct sembuf op={0,0,0}; assert(!semop(id,&op,1)); _exit(0); }
    wait_count(id,GETZCNT); assert(!ctl(id,0,SETVAL,0)); exited(pid);
    assert(!ctl(id,0,GETZCNT,0) && !ctl(id,0,IPC_RMID,0));
}
static void undo_kill(int clear) {
    int pipefd[2]; assert(!pipe(pipefd));
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0 && !ctl(id,0,SETVAL,1));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        close(pipefd[0]); struct sembuf op={0,-1,SEM_UNDO}; assert(!semop(id,&op,1));
        assert(write(pipefd[1],"L",1)==1); for (;;) pause();
    }
    close(pipefd[1]); char byte; assert(read(pipefd[0],&byte,1)==1); close(pipefd[0]);
    if (clear) assert(!ctl(id,0,SETVAL,3));
    assert(!kill(pid,SIGKILL)); int status;
    assert(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    assert(ctl(id,0,GETVAL,0)==(clear ? 3 : 1)); assert(!ctl(id,0,IPC_RMID,0));
}
static void message_wait(void) {
    int id=msgget(IPC_PRIVATE,0600); assert(id>=0);
    struct msqid_ds info; assert(!msgctl(id,IPC_STAT,&info));
    info.msg_qbytes=1; assert(!msgctl(id,IPC_SET,&info));
    struct { long type; char byte; } message={1,'A'}; assert(!msgsnd(id,&message,1,0));
    int pipefd[2]; assert(!pipe(pipefd));
    pid_t pid=fork(); assert(pid>=0);
    if (!pid) {
        close(pipefd[0]); assert(write(pipefd[1],"W",1)==1);
        message.byte='B'; assert(!msgsnd(id,&message,1,0)); _exit(0);
    }
    close(pipefd[1]); char byte; assert(read(pipefd[0],&byte,1)==1); close(pipefd[0]);
    assert(msgrcv(id,&message,1,0,0)==1 && message.byte=='A');
    assert(msgrcv(id,&message,1,0,0)==1 && message.byte=='B'); exited(pid);
    assert(!msgctl(id,IPC_RMID,NULL));
}
static void buffer_slots(void) {
    enum { count=32000 };
    unsigned short *values=calloc(count,sizeof(*values)); assert(values);
    for (unsigned i=0;i<count;++i) values[i]=i%97;
    int sem=semget(IPC_PRIVATE,count,0600), msg=msgget(IPC_PRIVATE,0600);
    int shm=shmget(IPC_PRIVATE,4096,0600);
    assert(sem>=0 && msg>=0 && shm>=0);
    assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) {}
    assert(errno==EMFILE);
    /* These kernel calls belonged to the per-request packet transport, not
     * the SysV ABI. A real filter makes their accidental return fatal. */
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_pread64,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_read,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_memfd_create,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(!semctl(sem,0,SETALL,(union argument){.array=values}));
    memset(values,0,count*sizeof(*values));
    assert(!semctl(sem,0,GETALL,(union argument){.array=values}));
    for (unsigned i=0;i<count;++i) assert(values[i]==i%97);
    struct semid_ds sem_info;
    assert(!semctl(sem,0,IPC_STAT,(union argument){.info=&sem_info}) && sem_info.sem_nsems==count);
    struct sembuf op={1,-1,IPC_NOWAIT}; assert(!semop(sem,&op,1));
    struct { long type; char data[8192]; } message={.type=11};
    memset(message.data,'x',sizeof(message.data));
    assert(!msgsnd(msg,&message,sizeof(message.data),IPC_NOWAIT));
    memset(message.data,0,sizeof(message.data));
    assert(msgrcv(msg,&message,sizeof(message.data),0,IPC_NOWAIT)==sizeof(message.data));
    for (unsigned i=0;i<sizeof(message.data);++i) assert(message.data[i]=='x');
    struct shmid_ds shm_info;
    assert(!shmctl(shm,IPC_STAT,&shm_info) && shm_info.shm_segsz==4096);
    assert(shmctl(shm,IPC_STAT,(void *)1)==-1 && errno==EFAULT);
    assert(!shmctl(shm,IPC_RMID,NULL) && !msgctl(msg,IPC_RMID,NULL) && !ctl(sem,0,IPC_RMID,0));
    assert(fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)==-1 && errno==EMFILE);
    free(values);
}
static void shm_slots(int denied) {
    for (int protected=0;protected<2;++protected) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            int id=shmget(IPC_PRIVATE,4096,0600); assert(id>=0);
            void *warm=shmat(id,NULL,0); assert(warm!=(void *)-1 && !shmdt(warm));
            if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fstat,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOMSG),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,0,1),
                BPF_STMT(BPF_RET|BPF_K,denied ? SECCOMP_RET_ERRNO|EACCES : SECCOMP_RET_ALLOW),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
            };
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            int last=-1, fd;
            while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=fd;
            int failure=errno;
            if (failure!=EMFILE || last<3)
                fprintf(stderr,"SHM slot setup: protected=%d denied=%d last=%d errno=%d\n",protected,denied,last,failure);
            assert(failure==EMFILE && last>=3);
            struct shmid_ds info;
            for (unsigned round=0;round<16;++round) {
                for (unsigned free_slot=0;free_slot<2;++free_slot) {
                    if (free_slot) assert(!close(last));
                    char *address=shmat(id,NULL,0);
                    if (denied) assert(address==(void *)-1 && errno==EACCES);
                    else {
                        assert(address!=(void *)-1);
                        address[0]='S';
                        assert(!shmctl(id,IPC_STAT,&info) && info.shm_nattch==1);
                        assert(!shmdt(address));
                    }
                    assert(!shmctl(id,IPC_STAT,&info) && !info.shm_nattch);
                    if (free_slot) {
                        assert(fcntl(last,F_GETFD)==-1 && errno==EBADF);
                        assert(fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)==last);
                    } else assert(fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)==-1 && errno==EMFILE);
                }
            }
            for (int n=0;n<64;++n) assert(fcntl(n,F_GETFD)>=0);
            assert(!shmctl(id,IPC_RMID,NULL)); _exit(0);
        }
        exited(child);
    }
}
static int shared_wait(void *data) {
    int id=*(int *)data;
    struct sembuf op={0,-1,0};
    assert(!semop(id,&op,1));
    return 0;
}
struct shm_attempt { int id, mode, protected; void *address; };
static int shm_attempt(void *data) {
    struct shm_attempt *a=data;
    if (a->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,a->mode==0 ? SYS_mmap : a->mode==1 ? SYS_close_range : SYS_clone,0,1),
        BPF_STMT(BPF_RET|BPF_K,a->mode==2 ? SECCOMP_RET_ERRNO|EACCES : SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned n=0;n<8;++n) {
        assert(shmat(a->id,a->address,0)==(void *)-1);
        assert(errno==(a->mode==2 ? EACCES : ECANCELED));
        struct shmid_ds info;
        assert(!shmctl(a->id,IPC_STAT,&info) && !info.shm_nattch);
    }
    return 0;
}
static void shm_terminal(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    int id=shmget(IPC_PRIVATE,page,0600); assert(id>=0);
    char *warm=shmat(id,NULL,0); assert(warm!=(void *)-1 && !shmdt(warm));
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    struct rlimit reduced=limit; reduced.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&reduced));
    int filled[64]; unsigned count=0; int fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(count<64); filled[count++]=fd; }
    assert(errno==EMFILE);
    for (int protected=0;protected<2;++protected) for (int mode=0;mode<3;++mode) {
        struct shm_attempt a={id,mode,protected,warm};
        pid_t child=clone(shm_attempt,(char *)stack+65536,CLONE_VM|CLONE_FILES|SIGCHLD,&a);
        assert(child>0); exited(child);
        for (int n=0;n<64;++n) assert(fcntl(n,F_GETFD)>=0);
        assert(fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)==-1 && errno==EMFILE);
        assert(!prctl(PR_SET_DUMPABLE,1));
        struct shmid_ds info;
        assert(!shmctl(id,IPC_STAT,&info) && !info.shm_nattch);
        char *p=shmat(id,warm,0); assert(p==warm);
        p[0]='T'; assert(!shmdt(p));
    }
    while (count) assert(!close(filled[--count]));
    assert(!setrlimit(RLIMIT_NOFILE,&limit) && !munmap(stack,65536) && !shmctl(id,IPC_RMID,NULL));
}
struct shm_parallel { int id, index; };
static int shm_parallel(void *data) {
    struct shm_parallel *a=data;
    for (unsigned n=0;n<24;++n) {
        char *address=shmat(a->id,NULL,0); assert(address!=(void *)-1);
        address[a->index]=(char)n;
        assert(address[a->index]==(char)n);
        if (!(n%4)) {
            pid_t child=fork(); assert(child>=0);
            if (!child) { assert(address[a->index]==(char)n && !shmdt(address)); _exit(0); }
            exited(child);
        }
        assert(!shmdt(address));
    }
    return 0;
}
static void shm_concurrent(void) {
    int id=shmget(IPC_PRIVATE,4096,0600); assert(id>=0);
    char *warm=shmat(id,NULL,0); assert(warm!=(void *)-1 && !shmdt(warm));
    enum { CHILDREN=6 }; void *stacks[CHILDREN]; pid_t children[CHILDREN];
    struct shm_parallel arguments[CHILDREN];
    for (int i=0;i<CHILDREN;++i) {
        stacks[i]=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(stacks[i]!=MAP_FAILED);
        arguments[i]=(struct shm_parallel){id,i};
        children[i]=clone(shm_parallel,(char *)stacks[i]+65536,CLONE_VM|CLONE_FILES|SIGCHLD,&arguments[i]);
        assert(children[i]>0);
    }
    for (int i=0;i<CHILDREN;++i) { exited(children[i]); assert(!munmap(stacks[i],65536)); }
    struct shmid_ds info;
    assert(!shmctl(id,IPC_STAT,&info) && !info.shm_nattch && !shmctl(id,IPC_RMID,NULL));
}
struct shm_dying { int id, ready, protected; void *address; };
static int shm_dying(void *data) {
    struct shm_dying *a=data;
    if (a->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    char *p=shmat(a->id,a->address,0); assert(p==a->address);
    assert(write(a->ready,"m",1)==1);
    for (;;) {
        assert(!shmdt(p));
        p=shmat(a->id,a->address,0); assert(p==a->address); p[0]='D';
    }
}
static void shm_owner_death(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    int id=shmget(IPC_PRIVATE,page,0600); assert(id>=0);
    char *warm=shmat(id,NULL,0); assert(warm!=(void *)-1 && !shmdt(warm));
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    int ready[2]; assert(!pipe2(ready,O_CLOEXEC));
    for (int protected=0;protected<2;++protected) for (unsigned n=0;n<16;++n) {
        struct shm_dying a={id,ready[1],protected,warm};
        pid_t child=clone(shm_dying,(char *)stack+65536,CLONE_VM|CLONE_FILES|SIGCHLD,&a);
        assert(child>0); char byte;
        /* EVENT_WAIT: a real mapping precedes caller cancellation; child death
         * and the following address-space turn bound pending helper cleanup. */
        assert(read(ready[0],&byte,1)==1 && byte=='m' && !kill(child,SIGKILL));
        int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        int detached=shmdt(warm); assert(!detached || (detached==-1 && errno==EINVAL));
        unsigned char resident;
        assert(mincore(warm,page,&resident)==-1 && errno==ENOMEM);
        struct shmid_ds info;
        assert(!shmctl(id,IPC_STAT,&info) && !info.shm_nattch);
        assert(!prctl(PR_SET_DUMPABLE,1));
    }
    assert(!close(ready[0]) && !close(ready[1]) && !munmap(stack,65536) && !shmctl(id,IPC_RMID,NULL));
}
struct shm_exec { int id, ready; };
static void *shm_exec_worker(void *data) {
    struct shm_exec *a=data;
    char *p=shmat(a->id,NULL,0); assert(p!=(void *)-1);
    assert(write(a->ready,"e",1)==1);
    for (;;) {
        assert(!shmdt(p)); p=shmat(a->id,NULL,0); assert(p!=(void *)-1);
    }
    return NULL;
}
static void shm_exec(const char *program) {
    int id=shmget(IPC_PRIVATE,4096,0600); assert(id>=0);
    for (unsigned n=0;n<16;++n) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            int ready[2]; assert(!pipe2(ready,O_CLOEXEC));
            struct shm_exec a={id,ready[1]}; pthread_t worker;
            assert(!pthread_create(&worker,NULL,shm_exec_worker,&a));
            char byte;
            /* EVENT_WAIT: the worker has an actual mapping before exec kills
             * it and any in-flight helper. Image entry must retire the old MM. */
            assert(read(ready[0],&byte,1)==1 && byte=='e');
            char segment[32]; snprintf(segment,sizeof(segment),"%d",id);
            execl(program,program,"shm-exec-end",segment,NULL); _exit(127);
        }
        exited(child);
        struct shmid_ds info;
        assert(!shmctl(id,IPC_STAT,&info) && !info.shm_nattch);
    }
    assert(!shmctl(id,IPC_RMID,NULL));
}
static void wait_slots(void) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0);
    assert(!prctl(PR_SET_DUMPABLE,0));
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) {}
    assert(errno==EMFILE);
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    for (unsigned i=0;i<17;++i) {
        pid_t pid=clone(shared_wait,(char *)stack+65536,CLONE_FILES|SIGCHLD,&id);
        assert(pid>=0); wait_registered(id);
        if (i<16) {
            assert(!kill(pid,SIGKILL)); int status;
            assert(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        } else { assert(!ctl(id,0,SETVAL,1)); exited(pid); }
        assert(!ctl(id,0,GETNCNT,0));
        for (int n=0;n<64;++n) assert(fcntl(n,F_GETFD)>=0);
        assert(fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0)==-1 && errno==EMFILE);
    }
    struct sembuf op={0,-1,0}; struct timespec bound={0,1000000};
    assert(semtimedop(id,&op,1,&bound)==-1 && errno==EAGAIN);
    assert(!munmap(stack,65536) && !ctl(id,0,IPC_RMID,0));
}
static void *thread_wait(void *data) { shared_wait(data); return NULL; }
static void wait_capacity(void) {
    enum { count=40 };
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0);
    pthread_t threads[count];
    for (unsigned i=0;i<count;++i) assert(!pthread_create(&threads[i],NULL,thread_wait,&id));
    wait_total(id,GETNCNT,count);
    assert(!ctl(id,0,SETVAL,count));
    for (unsigned i=0;i<count;++i) assert(!pthread_join(threads[i],NULL));
    assert(!ctl(id,0,GETNCNT,0) && !ctl(id,0,GETVAL,0) && !ctl(id,0,IPC_RMID,0));
}
static void wait_denied(void) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ppoll,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    struct sembuf op={0,-1,0};
    assert(semop(id,&op,1)==-1 && errno==EACCES);
    assert(!ctl(id,0,GETNCNT,0) && !ctl(id,0,IPC_RMID,0));
}
static void wait_stopped(void) {
    int id=semget(IPC_PRIVATE,1,0600); assert(id>=0);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sembuf op={0,-1,0};
        int result;
        do result=semop(id,&op,1); while (result<0 && errno==EINTR);
        assert(!result); _exit(0);
    }
    wait_registered(id);
    assert(!kill(child,SIGSTOP)); int status;
    assert(waitpid(child,&status,WUNTRACED)==child && WIFSTOPPED(status));
    assert(!ctl(id,0,SETVAL,1));
    assert(waitpid(child,&status,WNOHANG|WCONTINUED)==0);
    assert(!kill(child,SIGCONT)); exited(child);
    assert(!ctl(id,0,GETVAL,0) && !ctl(id,0,GETNCNT,0) && !ctl(id,0,IPC_RMID,0));
}
static void metadata_is(int sem,int msg,int shm,pid_t writer,pid_t creator) {
    int pid=ctl(sem,0,GETPID,0);
    if (pid!=writer) fprintf(stderr,"GETPID=%d expected=%d self=%d\n",pid,writer,getpid());
    assert(pid==writer && ctl(sem,1,GETPID,0)==writer);
    struct msqid_ds queue; assert(!msgctl(msg,IPC_STAT,&queue));
    assert(queue.msg_lspid==writer && queue.msg_lrpid==writer);
    struct shmid_ds memory; assert(!shmctl(shm,IPC_STAT,&memory));
    assert(memory.shm_cpid==creator && memory.shm_lpid==writer);
}
static void *metadata_thread(void *argument) {
    int *ids=argument;
    struct sembuf ops[]={{0,0,0},{1,0,0}};
    assert(!semop(ids[0],ops,2));
    struct { long type; char byte; } message={1,'p'};
    assert(!msgsnd(ids[1],&message,1,IPC_NOWAIT));
    assert(msgrcv(ids[1],&message,1,1,IPC_NOWAIT)==1);
    void *map=shmat(ids[2],NULL,0); assert(map!=(void *)-1);
    assert(!shmdt(map));
    return NULL;
}
static void metadata_write(int sem,int msg,int shm,pid_t creator) {
    unsigned short values[]={0,0};
    assert(!semctl(sem,0,SETALL,(union argument){.array=values}));
    assert(ctl(sem,0,GETPID,0)==getpid() && ctl(sem,1,GETPID,0)==getpid());
    int ids[]={sem,msg,shm}; pthread_t thread;
    assert(!pthread_create(&thread,NULL,metadata_thread,ids));
    assert(!pthread_join(thread,NULL));
    metadata_is(sem,msg,shm,getpid(),creator);
    assert(!kill(ctl(sem,0,GETPID,0),0));
}
static void pid_metadata(const char *self,unsigned levels) {
    int sem=semget(IPC_PRIVATE,2,0600),msg=msgget(IPC_PRIVATE,0600),shm=shmget(IPC_PRIVATE,4096,0600);
    assert(sem>=0 && msg>=0 && shm>=0);
    assert(!ctl(sem,0,GETPID,0));
    metadata_write(sem,msg,shm,getpid());
    int ready[2]; assert(!pipe2(ready,O_CLOEXEC));
    int current=-1;
    if (levels) { current=open("/proc/self/ns/pid",O_RDONLY|O_CLOEXEC); assert(current>=0); }
    if (levels) assert(!unshare(CLONE_NEWPID));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]);
        if (current>=0) close(current);
        pid_t creator=levels ? 0 : getppid();
        if (levels) assert(getpid()==1);
        metadata_is(sem,msg,shm,creator,creator);
        if (levels>1) pid_metadata(self,levels-1);
        int owned=shmget(IPC_PRIVATE,4096,0600); assert(owned>=0);
        assert(write(ready[1],&owned,sizeof(owned))==sizeof(owned)); close(ready[1]);
        char s[32],q[32],m[32],c[32];
        snprintf(s,sizeof(s),"%d",sem); snprintf(q,sizeof(q),"%d",msg);
        snprintf(m,sizeof(m),"%d",shm); snprintf(c,sizeof(c),"%d",creator);
        execl(self,self,"pid-metadata-exec",s,q,m,c,(char *)NULL); _exit(1);
    }
    close(ready[1]); int owned=-1;
    assert(read(ready[0],&owned,sizeof(owned))==sizeof(owned)); close(ready[0]);
    exited(child);
    /* Restore the namespace for subsequent pthread_create after nested PID 1
     * exits; CLONE_THREAD cannot join the child's now-dead namespace. */
    if (current>=0) { assert(!setns(current,CLONE_NEWPID)); close(current); }
    metadata_is(sem,msg,shm,child,getpid());
    struct shmid_ds memory; assert(!shmctl(owned,IPC_STAT,&memory));
    assert(memory.shm_cpid==child && !memory.shm_lpid);
    assert(!shmctl(owned,IPC_RMID,NULL));
    assert(!ctl(sem,0,IPC_RMID,0) && !msgctl(msg,IPC_RMID,NULL) && !shmctl(shm,IPC_RMID,NULL));
}
int main(int argc, char **argv) {
    assert(argc>=2); setbuf(stdout,NULL);
    // EVENT_WAIT: child exit, IPC readiness and signals; alarm fails deadlocks.
    alarm(20);
    if (!strcmp(argv[1],"atomic")) atomic_ops();
    else if (!strcmp(argv[1],"pid-metadata")) pid_metadata(argv[0],2);
    else if (!strcmp(argv[1],"pid-metadata-native")) pid_metadata(argv[0],0);
    else if (!strcmp(argv[1],"pid-metadata-exec")) {
        assert(argc==6 && !prctl(PR_SET_DUMPABLE,0));
        metadata_write(atoi(argv[2]),atoi(argv[3]),atoi(argv[4]),atoi(argv[5]));
    }
    else if (!strcmp(argv[1],"buffer-slots")) buffer_slots();
    else if (!strcmp(argv[1],"shm-slots")) shm_slots(0);
    else if (!strcmp(argv[1],"shm-denied")) shm_slots(1);
    else if (!strcmp(argv[1],"shm-terminal")) shm_terminal();
    else if (!strcmp(argv[1],"shm-concurrent")) shm_concurrent();
    else if (!strcmp(argv[1],"shm-owner-death")) shm_owner_death();
    else if (!strcmp(argv[1],"shm-exec")) shm_exec(argv[0]);
    else if (!strcmp(argv[1],"shm-exec-end")) {
        assert(argc==3); struct shmid_ds info;
        assert(!shmctl(atoi(argv[2]),IPC_STAT,&info) && !info.shm_nattch);
    }
    else if (!strcmp(argv[1],"wait-slots")) wait_slots();
    else if (!strcmp(argv[1],"wait-capacity")) wait_capacity();
    else if (!strcmp(argv[1],"wait-denied")) wait_denied();
    else if (!strcmp(argv[1],"wait-stopped")) wait_stopped();
    else if (!strcmp(argv[1],"sem-signal") || !strcmp(argv[1],"sem-remove") || !strcmp(argv[1],"sem-wake")) waiting(argv[1]);
    else if (!strcmp(argv[1],"timed")) timed();
    else if (!strcmp(argv[1],"undo-fork-exec") || !strcmp(argv[1],"undo-thread")) undo(argv[0],argv[1]);
    else if (!strcmp(argv[1],"undo-exec")) { assert(argc==3 && !ctl(atoi(argv[2]),0,GETVAL,0)); return 0; }
    else if (!strcmp(argv[1],"messages")) messages();
    else if (!strcmp(argv[1],"protected")) {
        assert(!prctl(PR_SET_DUMPABLE,0)); atomic_ops(); messages(); timed(); waiting("sem-wake");
    }
    else if (!strcmp(argv[1],"group-signal")) {
        struct sigaction ignore={.sa_handler=SIG_IGN};
        assert(!sigaction(SIGTERM,&ignore,NULL)); assert(!kill(0,SIGTERM));
    }
    else if (!strcmp(argv[1],"permissions")) permissions();
    else if (!strcmp(argv[1],"zero-wait")) zero_wait();
    else if (!strcmp(argv[1],"undo-kill")) undo_kill(0);
    else if (!strcmp(argv[1],"undo-clear")) undo_kill(1);
    else if (!strcmp(argv[1],"message-wait")) message_wait();
    else if (!strcmp(argv[1],"serve")) {
        assert(argc==3); int id=semget(atoi(argv[2]),1,IPC_CREAT|IPC_EXCL|0600); assert(id>=0);
        puts("SYSV-READY"); struct sembuf op={0,-1,0}; assert(!semop(id,&op,1));
        assert(!ctl(id,0,IPC_RMID,0));
    } else if (!strcmp(argv[1],"wake")) {
        assert(argc==3); int id=semget(atoi(argv[2]),1,0); assert(id>=0);
        struct sembuf op={0,1,0}; assert(!semop(id,&op,1));
    } else if (!strcmp(argv[1],"owner")) {
        assert(argc==3); int id=semget(atoi(argv[2]),1,IPC_CREAT|IPC_EXCL|0600); assert(id>=0);
        assert(!ctl(id,0,SETVAL,1)); struct sembuf op={0,-1,SEM_UNDO}; assert(!semop(id,&op,1));
        printf("SYSV-OWNER %d\n",getppid()); for (;;) pause();
    } else if (!strcmp(argv[1],"peer")) {
        assert(argc==3); int id=semget(atoi(argv[2]),1,0); assert(id>=0);
        struct sigaction action={.sa_handler=caught}; assert(!sigaction(SIGCHLD,&action,NULL));
        pid_t helper=fork(); assert(helper>=0);
        if (!helper) { wait_registered(id); puts("SYSV-PEER-WAITING"); _exit(0); }
        puts("SYSV-PEER"); struct sembuf op={0,-1,0};
        // The observer's SIGCHLD may interrupt this wait. Only acquisition, not
        // that signal or the outer timeout, proves crash-time undo succeeded.
        int result; do { result=semop(id,&op,1); } while (result && errno==EINTR);
        assert(!result);
        exited(helper);
        assert(!ctl(id,0,IPC_RMID,0));
    } else return 2;
    printf("PASS %s\n",argv[1]); return 0;
}
