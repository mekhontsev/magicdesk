#define _GNU_SOURCE
#include "task_wait.h"
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/signalfd.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static unsigned calls, general, directed, misses;
pid_t __real_wait4(pid_t, int *, int, struct rusage *);
pid_t __wrap_wait4(pid_t pid, int *status, int options, struct rusage *usage) {
    calls++;
    if (pid==-1) general++; else directed++;
    pid_t result=__real_wait4(pid,status,options,usage);
    if (!result) misses++;
    return result;
}
static void cancel_collected(void) {
    enum { CHILDREN=48 };
    pid_t children[CHILDREN];
    struct md_task_wait state={0};
    struct md_task_wait_member members[CHILDREN]={0};
    for (unsigned i=0;i<CHILDREN;++i) {
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) {
            assert(!ptrace(PTRACE_TRACEME,0,0,0));
            raise(SIGSTOP); _exit(0);
        }
        children[i]=pid;
        int status; assert(waitpid(pid,&status,__WALL)==pid && WIFSTOPPED(status));
        assert(!ptrace(PTRACE_SETOPTIONS,pid,0,PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL));
        assert(!ptrace(PTRACE_CONT,pid,0,0));
        siginfo_t info={0};
        /* EVENT_WAIT: observe but do not consume this exact EXIT stop.
         * The fixture alarm bounds a missing terminal event. */
        assert(!waitid(P_PID,pid,&info,WSTOPPED|WNOWAIT|__WALL));
        assert(info.si_code==CLD_TRAPPED && info.si_status==(SIGTRAP|(PTRACE_EVENT_EXIT<<8)));
        md_task_wait_track(&state,&members[i],pid);
    }
    state.directed=64;
    int status; pid_t first=md_task_wait_ready(&state,&status,NULL,0);
    assert(first>0 && (unsigned)status>>16==PTRACE_EVENT_EXIT && state.pending_count);
    assert(!ptrace(PTRACE_CONT,first,0,0));
    for (unsigned i=0;i<CHILDREN;++i) kill(children[i],SIGKILL);
    unsigned reaped=0;
    for (;;) {
        pid_t pid=md_task_wait_take(&state,&status,NULL);
        if (!pid) pid=waitpid(-1,&status,__WALL);
        if (pid<0) { assert(errno==ECHILD); break; }
        if (WIFSTOPPED(status)) assert(!ptrace(PTRACE_CONT,pid,0,0));
        else { assert(WIFEXITED(status) || WIFSIGNALED(status)); ++reaped; }
    }
    assert(reaped==CHILDREN && !state.pending_count);
    puts("PASS collected cancellation: terminal batch drained before blocking reap");
}
int main(int argc,char **argv) {
    int idle=argc==2 && !strcmp(argv[1],"--idle");
    int small=argc==2 && !strcmp(argv[1],"--small");
    assert(argc==1 || idle || small);
    enum { CAPACITY=388 };
    unsigned hot=idle || small ? 4 : 48, total=idle ? CAPACITY : hot, requests=idle || small ? 2048 : 128;
    sigset_t mask;
    sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
    assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
    int fd=signalfd(-1,&mask,SFD_CLOEXEC|SFD_NONBLOCK);
    assert(fd>=0);
    pid_t children[CAPACITY];
    unsigned seen[CAPACITY]={0};
    pid_t parent=getpid();
    struct timespec started,ended;
    assert(!clock_gettime(CLOCK_MONOTONIC,&started));
    alarm(30);
    int gate[2]; assert(!pipe(gate));
    for (unsigned i=0;i<total;i++) {
        children[i]=fork(); assert(children[i]>=0);
        if (!children[i]) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
            assert(!ptrace(PTRACE_TRACEME,0,0,0));
            raise(SIGSTOP);
            if (i>=hot) { char byte; assert(read(gate[0],&byte,1)==1); _exit(0); }
            for (unsigned j=0;j<requests;j++) syscall(SYS_getpid);
            _exit(20+i);
        }
    }
    struct md_task_wait state={0};
    struct md_task_wait_member members[CAPACITY]={0};
    for (unsigned i=0;i<total;++i) md_task_wait_track(&state,&members[i],children[i]);
    unsigned exited=0, stops=0, empty=0;
    while (exited<hot) {
        struct signalfd_siginfo info[8];
        ssize_t bytes;
        do { bytes=read(fd,info,sizeof(info)); } while (bytes<0 && errno==EINTR);
        assert((bytes<0 && errno==EAGAIN) || (bytes>0 && bytes%(ssize_t)sizeof(*info)==0));
        for (ssize_t i=0;i<bytes/(ssize_t)sizeof(*info);i++) {
            assert(info[i].ssi_signo==SIGCHLD);
            md_task_wait_hint(&state,(pid_t)info[i].ssi_pid);
        }
        int status;
        pid_t pid=md_task_wait_ready(&state,&status,NULL,1);
        /* A directed miss while runnable must be flushed before parking. */
        if (!pid) pid=md_task_wait_ready(&state,&status,NULL,0);
        if (pid<0 && errno==EINTR) continue;
        assert(pid>=0);
        if (!pid) {
            empty++;
            unsigned before=calls;
            for (unsigned i=0;i<32;i++) assert(!md_task_wait_ready(&state,&status,NULL,0));
            assert(calls==before);
            struct pollfd item={fd,POLLIN,0};
            /* EVENT_WAIT: blocked SIGCHLD invalidates the drained observation;
             * the alarm bounds missing notification or unreaped children. */
            int ready;
            do { ready=poll(&item,1,-1); } while (ready<0 && errno==EINTR);
            assert(ready==1 && (item.revents&POLLIN));
            continue;
        }
        unsigned i=0;
        while (i<total && children[i]!=pid) i++;
        assert(i<total);
        if (WIFEXITED(status)) {
            assert(i<hot && WEXITSTATUS(status)==20+(int)i && seen[i]>=requests*2);
            md_task_wait_untrack(&state,&members[i]);
            children[i]=0; exited++; continue;
        }
        assert(WIFSTOPPED(status));
        if (!seen[i]) assert(!ptrace(PTRACE_SETOPTIONS,pid,0,
            PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL));
        seen[i]++; stops++;
        assert(!ptrace(i<hot ? PTRACE_SYSCALL : PTRACE_CONT,pid,0,0));
    }
    assert(stops>=hot*requests*2);
    if (idle) {
        for (unsigned i=hot;i<total;++i) assert(!kill(children[i],SIGKILL));
        for (unsigned i=hot;i<total;++i) {
            int status; assert(waitpid(children[i],&status,__WALL)==children[i]
                && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            md_task_wait_untrack(&state,&members[i]);
        }
    }
    close(gate[0]); close(gate[1]);
    cancel_collected();
    alarm(0); close(fd);
    assert(!clock_gettime(CLOCK_MONOTONIC,&ended));
    double elapsed=ended.tv_sec-started.tv_sec+(ended.tv_nsec-started.tv_nsec)/1e9;
    printf("PASS live wait collection: children=%u stops=%u empty=%u calls=%u general=%u directed=%u misses=%u seconds=%.6f\n",
        exited,stops,empty,calls,general,directed,misses,elapsed);
    return 0;
}
