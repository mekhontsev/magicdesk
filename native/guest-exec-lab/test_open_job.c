#define _GNU_SOURCE
#include "file_open_job.h"
#include "event_wait.h"
#include "fifo_readiness.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int fork_gate=-1;
pid_t __real_fork(void);
pid_t __wrap_fork(void) {
    pid_t child=__real_fork();
    if (!child && fork_gate>=0) {
        /* EVENT_WAIT: the test releases this child only after its owner dies.
         * The outer alarm bounds a missing acknowledgement. */
        char byte;
        ssize_t count;
        do { count=read(fork_gate,&byte,1); } while (count<0 && errno==EINTR);
        if (count!=1) _exit(124);
    }
    return child;
}
static int fds(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    int n=0; while (readdir(d)) ++n;
    assert(!closedir(d)); return n;
}
static int wait_child(pid_t child) {
    int pidfd=(int)syscall(SYS_pidfd_open,child,0); assert(pidfd>=0);
    /* EVENT_WAIT: exact helper exit; timeout fails rather than assuming open. */
    assert(md_event_wait_fd(pidfd,POLLIN,md_event_now()+5000000000LL)>=0);
    int status; assert(waitpid(child,&status,0)==child && !close(pidfd));
    return status;
}
static int reap(struct md_open_job *job) {
    return md_open_job_finish(job,wait_child(job->pid),NULL);
}
static int pin_pipe(int *control) {
    int pipefd[2]; assert(!pipe2(pipefd,O_CLOEXEC));
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",pipefd[0]);
    int pin=open(path,O_PATH|O_CLOEXEC); assert(pin>=0);
    assert(!close(pipefd[0]) && !close(pipefd[1]));
    char temporary[128]; snprintf(temporary,sizeof(temporary),"/data/local/tmp/md-open-job-%d-XXXXXX",getpid());
    *control=mkostemp(temporary,O_CLOEXEC);
    assert(*control>=0 && !unlink(temporary));
    assert(*control>=0 && !ftruncate(*control,8));
    return pin;
}
static int start(struct md_open_job *job, int pin, int flags, struct md_open_completion completion) {
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",completion.control);
    int control=open(path,O_RDWR|O_CLOEXEC); assert(control>=0);
    completion.control=control;
    int error=md_open_job_start(job,pin,flags,completion);
    assert(!close(control)); return error;
}
static void missing_close_range(void) {
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_close_range,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOSYS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void owner_death(int before_admission) {
    assert(!prctl(PR_SET_CHILD_SUBREAPER,1));
    int control,pin=pin_pipe(&control),message[2],gate[2];
    assert(!pipe2(message,O_CLOEXEC) && !pipe2(gate,O_CLOEXEC));
    pid_t owner=fork(); assert(owner>=0);
    if (!owner) {
        if (before_admission) fork_gate=gate[0];
        struct md_open_job job={0};
        assert(!start(&job,pin,O_RDONLY,(struct md_open_completion){.kind=MD_OPEN_PIPE,.control=control}));
        assert(write(message[1],&job.pid,sizeof(job.pid))==sizeof(job.pid));
        for (;;) pause();
    }
    pid_t helper;
    /* EVENT_WAIT: helper identity is published by its exact owner; timeout fails. */
    assert(md_event_wait_fd(message[0],POLLIN,md_event_now()+5000000000LL)>=0);
    assert(read(message[0],&helper,sizeof(helper))==sizeof(helper) && helper>0);
    assert(!kill(owner,SIGKILL));
    int status=wait_child(owner); assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    if (before_admission) assert(write(gate[1],"x",1)==1);
    status=wait_child(helper);
    assert((WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL) || (WIFEXITED(status) && WEXITSTATUS(status)==125));
    if (before_admission) assert(WIFEXITED(status) && WEXITSTATUS(status)==125);
    assert(!close(control) && !close(pin) && !close(message[0]) && !close(message[1])
        && !close(gate[0]) && !close(gate[1]));
}
static void initial_readiness(void) {
    int control,pin=pin_pipe(&control);
    struct md_open_completion completion={.kind=MD_OPEN_PIPE,.control=control};
    struct md_open_job job={0};
    assert(!start(&job,pin,O_RDONLY|O_NONBLOCK,completion));
    struct md_open_observation opened={0};
    int reader=md_open_job_finish(&job,wait_child(job.pid),&opened);
    assert(reader>=0 && opened.initial_reader);
    struct md_fifo_readiness *gate=md_fifo_readiness_new(control,opened.writers);
    assert(gate && !md_fifo_readiness_attach(gate,reader));
    struct md_control_file file=md_fifo_readiness_control(gate);
    int wake=-1; assert(!file.ops->poll_descriptor(file.context,&wake));
    assert(file.ops->poll(file.context)==0);
    struct pollfd poller={wake,POLLIN,0};
    assert(poll(&poller,1,0)==0);
    assert(!start(&job,pin,O_WRONLY|O_NONBLOCK,completion));
    int writer=reap(&job); assert(writer>=0);
    assert(file.ops->poll(file.context)==0);
    assert(write(writer,"x",1)==1);
    /* EVENT_WAIT: real pipe data/closure, never elapsed time as readiness. */
    assert(md_event_wait_fd(wake,POLLIN,md_event_now()+5000000000LL)>=0);
    assert(file.ops->poll(file.context)&POLLIN);
    char byte; assert(read(reader,&byte,1)==1 && byte=='x');
    assert(file.ops->poll(file.context)==0);
    assert(!close(writer));
    assert(md_event_wait_fd(wake,POLLIN,md_event_now()+5000000000LL)>=0);
    assert(file.ops->poll(file.context)&POLLHUP);
    assert(read(reader,&byte,1)==0 && !close(reader));
    assert(!start(&job,pin,O_WRONLY|O_NONBLOCK,completion));
    assert(reap(&job)==-ENXIO);
    md_fifo_readiness_free(gate);
    assert(!close(control) && !close(pin));
    puts("PASS FIFO readiness: initial HUP suppression, writer, data, EOF, no retained reader");
}
static void interrupted_open(void) {
    int control,pin=pin_pipe(&control);
    uint32_t *generations=mmap(NULL,8,PROT_READ,MAP_SHARED,control,0);
    assert(generations!=MAP_FAILED);
    struct md_open_completion completion={.kind=MD_OPEN_PIPE,.control=control};
    for (unsigned round=0;round<32;++round) {
        struct md_open_job reader={0},writer={0};
        assert(!start(&reader,pin,O_RDONLY,completion));
        assert(!md_open_job_interrupt(&reader));
        assert(reap(&reader)==-EINTR);
        uint32_t before=__atomic_load_n(&generations[0],__ATOMIC_ACQUIRE);
        assert(!start(&reader,pin,O_RDONLY,completion));
        while (__atomic_load_n(&generations[0],__ATOMIC_ACQUIRE)==before) {
            struct timespec timeout={.tv_sec=5};
            /* EVENT_WAIT: the helper publishes its real endpoint before waiting. */
            int r=syscall(SYS_futex,&generations[0],FUTEX_WAIT,before,&timeout,NULL,0);
            assert(!r || errno==EAGAIN);
        }
        if (round&1) {
            assert(!start(&writer,pin,O_WRONLY,completion));
            int w=reap(&writer); assert(w>=0);
            assert(!md_open_job_interrupt(&reader));
            int r=reap(&reader); assert(r>=0);
            assert(write(w,"x",1)==1 && !close(w));
            char byte; assert(read(r,&byte,1)==1 && byte=='x' && !close(r));
        } else {
            assert(!md_open_job_interrupt(&reader) && reap(&reader)==-EINTR);
            assert(!start(&writer,pin,O_WRONLY|O_NONBLOCK,completion) && reap(&writer)==-ENXIO);
        }
    }
    assert(!munmap(generations,8) && !close(control) && !close(pin));
    puts("PASS FIFO interruption: before start, while waiting, after peer admission, no phantom endpoint");
}
int main(void) {
    setbuf(stdout,NULL); alarm(60);
    int before=fds();
    initial_readiness(); assert(fds()==before);
    interrupted_open(); assert(fds()==before);
    owner_death(0); owner_death(1); assert(fds()==before);
    for (unsigned round=0;round<32;++round) {
        if (round==16) missing_close_range();
        int control, pin=pin_pipe(&control);
        struct md_open_completion completion={.kind=MD_OPEN_PIPE,.control=control};
        struct md_open_job reader={0},writer={0};
        assert(!start(&writer,pin,O_WRONLY|O_NONBLOCK,completion));
        int absent=reap(&writer);
        if (absent!=-ENXIO) fprintf(stderr,"unexpected no-reader result=%d\n",absent);
        assert(absent==-ENXIO);
        assert(!start(&reader,pin,O_RDONLY,completion));
        assert(!start(&writer,pin,O_WRONLY,completion));
        int r=reap(&reader),w=reap(&writer); assert(r>=0 && w>=0);
        assert(write(w,"fifo",4)==4 && !close(w));
        char bytes[4]; assert(read(r,bytes,sizeof(bytes))==4 && !memcmp(bytes,"fifo",4));
        assert(read(r,bytes,sizeof(bytes))==0 && !close(r));
        assert(!start(&reader,pin,O_RDONLY,completion));
        assert(!md_open_job_cancel(&reader) && reap(&reader)==-ECANCELED);
        assert(!start(&writer,pin,O_WRONLY|O_NONBLOCK,completion));
        assert(reap(&writer)==-ENXIO);
        assert(!start(&reader,pin,O_RDWR,completion));
        /* EVENT_WAIT: result is queued but not imported; cancellation closes it. */
        assert(md_event_wait_fd(reader.channel,POLLIN,md_event_now()+5000000000LL)>=0);
        assert(!md_open_job_cancel(&reader) && reap(&reader)==-ECANCELED);
        assert(!start(&writer,pin,O_WRONLY|O_NONBLOCK,completion));
        assert(reap(&writer)==-ENXIO);
        assert(!close(control) && !close(pin) && fds()==before);
    }
    /* A parked helper must not retain an unrelated supervisor pipe writer. */
    int control,pin=pin_pipe(&control),unrelated[2];
    assert(!pipe2(unrelated,O_CLOEXEC|O_NONBLOCK));
    struct md_open_job parked={0};
    assert(!start(&parked,pin,O_RDONLY,(struct md_open_completion){.kind=MD_OPEN_PIPE,.control=control}));
    assert(!close(unrelated[1]));
    assert(md_event_wait_fd(unrelated[0],POLLIN|POLLHUP,md_event_now()+5000000000LL)>=0);
    char byte; assert(read(unrelated[0],&byte,1)==0);
    assert(!md_open_job_cancel(&parked) && reap(&parked)==-ECANCELED);
    assert(!close(unrelated[0]) && !close(control) && !close(pin) && fds()==before);
    owner_death(0); owner_death(1); assert(fds()==before);
    puts("PASS deferred open ownership: paired FIFO, ENXIO, EOF, cancellation before/after delivery, owner death, close_range fallback, no inherited FD retention");
    return 0;
}
