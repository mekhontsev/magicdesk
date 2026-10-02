#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/msg.h>
#include <sys/sem.h>
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
static void wait_count(int id, int command) {
    struct timespec start, now; assert(!clock_gettime(CLOCK_MONOTONIC,&start));
    // STATE_POLL: SysV exposes waiter counts but no registration event. Yield
    // only in this native fixture; the deadline fails, never proves blocking.
    for (;;) {
        int count=ctl(id,0,command,0); assert(count>=0); if (count) return;
        assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-start.tv_sec<5);
        sched_yield();
    }
}
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
int main(int argc, char **argv) {
    assert(argc>=2); setbuf(stdout,NULL);
    // EVENT_WAIT: child exit, IPC readiness and signals; alarm fails deadlocks.
    alarm(20);
    if (!strcmp(argv[1],"atomic")) atomic_ops();
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
        assert(argc==4); int id=semget(atoi(argv[2]),1,0); assert(id>=0);
        struct sigaction action={.sa_handler=caught}; assert(!sigaction(SIGCHLD,&action,NULL));
        pid_t helper=fork(); assert(helper>=0);
        if (!helper) { wait_registered(id); assert(!kill(atoi(argv[3]),SIGKILL)); _exit(0); }
        puts("SYSV-PEER"); struct sembuf op={0,-1,0};
        // The killer's SIGCHLD may interrupt this wait. Only acquisition, not
        // that signal or the outer timeout, proves crash-time undo succeeded.
        int result; do { result=semop(id,&op,1); } while (result && errno==EINTR);
        assert(!result);
        exited(helper);
        assert(!ctl(id,0,IPC_RMID,0));
    } else return 2;
    printf("PASS %s\n",argv[1]); return 0;
}
