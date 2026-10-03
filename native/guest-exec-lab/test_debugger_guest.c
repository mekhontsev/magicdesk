#define _GNU_SOURCE
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <linux/perf_event.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

volatile int magic_value = 42;
static int stop(pid_t child) {
    int status;
    struct rusage usage;
    assert(wait4(child, &status, __WALL, &usage) == child);
    assert(WIFSTOPPED(status));
    return status;
}
static void exited(pid_t child, int code) {
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == code);
}
static void attach_test(int seize, int trace_exit) {
    int ready[2], release[2]; assert(!pipe(ready) && !pipe(release));
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        close(ready[0]); close(release[1]);
        assert(write(ready[1], "r", 1) == 1);
        char c; assert(read(release[0], &c, 1) == 1); _exit(7);
    }
    close(ready[1]); close(release[0]);
    char c; assert(read(ready[0], &c, 1) == 1); close(ready[0]);
    if (seize) {
        assert(!ptrace(PTRACE_SEIZE, child, 0, PTRACE_O_TRACEEXIT));
        assert(!ptrace(PTRACE_INTERRUPT, child, 0, 0));
        siginfo_t info = {0};
        assert(!waitid(P_PID, child, &info, WSTOPPED | WNOWAIT));
        assert(info.si_pid == child && info.si_code == CLD_TRAPPED);
        assert(info.si_status == (SIGTRAP | PTRACE_EVENT_STOP << 8));
        assert(!waitid(P_PID, child, &info, WSTOPPED | WNOWAIT));
    } else assert(!ptrace(PTRACE_ATTACH, child, 0, 0));
    int status = stop(child);
    assert(WSTOPSIG(status) == (seize ? SIGTRAP : SIGSTOP));
    if (seize) assert((unsigned)status >> 16 == PTRACE_EVENT_STOP);
    unsigned long registers[34]; struct iovec iov = {registers, sizeof(registers)};
    assert(!ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov));
    if (trace_exit) {
        assert(!ptrace(PTRACE_CONT, child, 0, 0));
        assert(write(release[1], "x", 1) == 1);
        status = stop(child); assert((unsigned)status >> 16 == PTRACE_EVENT_EXIT);
        siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, child, 0, &info));
        assert(info.si_signo == SIGTRAP && info.si_code == (SIGTRAP | PTRACE_EVENT_EXIT << 8));
        unsigned long message; assert(!ptrace(PTRACE_GETEVENTMSG, child, 0, &message));
        assert(message == (7 << 8));
        assert(!ptrace(PTRACE_CONT, child, 0, 0));
    } else {
        assert(!ptrace(PTRACE_DETACH, child, 0, 0));
        assert(write(release[1], "x", 1) == 1);
    }
    close(release[1]); exited(child, 7);
}
static void syscall_test(void) {
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        assert(syscall(SYS_getpid) == getpid());
        int fd = open("/etc/passwd", O_RDONLY); assert(fd >= 0);
        char bytes[16]; assert(read(fd, bytes, sizeof(bytes)) > 0); close(fd);
        _exit(9);
    }
    stop(child);
    assert(!ptrace(PTRACE_SETOPTIONS, child, 0, PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEEXIT));
    int entries = 0, exits = 0, opening = 0, exiting = 0, status;
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL, child, 0, 0));
        assert(waitpid(child, &status, __WALL) == child);
        if (WIFEXITED(status)) { assert(WEXITSTATUS(status) == 9); break; }
        assert(WIFSTOPPED(status));
        if ((unsigned)status >> 16 == PTRACE_EVENT_EXIT) { exiting++; continue; }
        assert(WSTOPSIG(status) == (SIGTRAP | 0x80));
        struct ptrace_syscall_info info = {0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO, child, sizeof(info), &info) > 0);
        if (info.op == PTRACE_SYSCALL_INFO_ENTRY) {
            entries++; if (info.entry.nr == SYS_openat) opening++;
        } else { assert(info.op == PTRACE_SYSCALL_INFO_EXIT); exits++; }
    }
    assert(entries >= 6 && exits == entries - 1 && opening == 1 && exiting == 1);
}
static void mixed_test(void) {
    int release[2]; assert(!pipe(release));
    pid_t traced = fork(); assert(traced >= 0);
    if (!traced) {
        close(release[1]); assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        char c; assert(read(release[0], &c, 1) == 1); _exit(3);
    }
    close(release[0]); stop(traced);
    assert(!ptrace(PTRACE_CONT, traced, 0, 0));
    pid_t plain = fork(); assert(plain >= 0);
    if (!plain) _exit(4);
    int status; assert(waitpid(-1, &status, 0) == plain);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 4);
    assert(write(release[1], "x", 1) == 1); close(release[1]); exited(traced, 3);
}
static void child_signal(int signal) { (void)signal; syscall(SYS_getpid); }
static void kill_stopped_test(void) {
    struct sigaction action={.sa_handler=child_signal,.sa_flags=SA_RESTART};
    assert(!sigaction(SIGCHLD,&action,NULL));
    for (unsigned i=0;i<50;i++) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP); _exit(55);
        }
        stop(child); assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_EXITKILL));
        assert(!kill(child,SIGKILL));
        int status=-1; pid_t reaped=waitpid(child,&status,0);
        if (reaped!=child || !WIFSIGNALED(status) || WTERMSIG(status)!=SIGKILL)
            fprintf(stderr,"kill-stopped iteration=%u pid=%d reaped=%d status=%#x errno=%d\n",i,child,reaped,status,errno);
        assert(reaped==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    }
}
static void owner_test(int exitkill) {
    int done[2]; assert(!pipe(done));
    pid_t owner=fork(); assert(owner>=0);
    if (!owner) {
        close(done[0]);
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
            assert(write(done[1],"r",1)==1); _exit(0);
        }
        stop(child);
        if (exitkill) assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_EXITKILL));
        _exit(0);
    }
    close(done[1]); exited(owner,0);
    char c; assert(read(done[0],&c,1)==(exitkill ? 0 : 1)); close(done[0]);
}
static void *exec_worker(void *unused) {
    (void)unused;
    execl("/bin/true","true",(char *)0); _exit(99);
}
static void exec_thread_test(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        pthread_t worker; assert(!pthread_create(&worker,NULL,exec_worker,NULL));
        for (;;) pause();
    }
    stop(child);
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACECLONE|PTRACE_O_TRACEEXEC));
    assert(!ptrace(PTRACE_CONT,child,0,0));
    int execs=0, status;
    for (;;) {
        pid_t pid=waitpid(-1,&status,__WALL);
        assert(pid>0);
        if (WIFEXITED(status)) {
            assert(!WEXITSTATUS(status)); if (pid==child) break; continue;
        }
        assert(WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_EXEC) {
            unsigned long former; assert(!ptrace(PTRACE_GETEVENTMSG,pid,0,&former));
            assert(pid==child && former!=(unsigned long)pid); execs++;
        }
        assert(!ptrace(PTRACE_CONT,pid,0,0));
    }
    assert(execs==1);
}
static int perf_test(void) {
    struct perf_event_attr attr={.type=PERF_TYPE_SOFTWARE,.size=sizeof(attr),
        .config=PERF_COUNT_SW_TASK_CLOCK,.disabled=1,.exclude_kernel=1,.exclude_hv=1};
    int fd=syscall(SYS_perf_event_open,&attr,0,-1,-1,PERF_FLAG_FD_CLOEXEC);
    if (fd<0 && (errno==EPERM || errno==EACCES || errno==ENOSYS || errno==EOPNOTSUPP)) {
        printf("UNAVAILABLE perf-event errno=%d (%s)\n",errno,strerror(errno)); return 0;
    }
    assert(fd>=0);
    assert(!ioctl(fd,PERF_EVENT_IOC_ENABLE,0));
    volatile unsigned long work=0;
    for (unsigned i=0;i<1000000;i++) work+=i;
    assert(!ioctl(fd,PERF_EVENT_IOC_DISABLE,0));
    uint64_t count=0; assert(read(fd,&count,sizeof(count))==sizeof(count) && count>0);
    close(fd); printf("task-clock=%llu work=%lu\n",(unsigned long long)count,work);
    return 1;
}
static pthread_barrier_t ready_barrier;
static pthread_mutex_t workers_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t workers_done = PTHREAD_COND_INITIALIZER;
static int workers_exit;
static void *gdb_worker(void *unused) {
    (void)unused;
    pthread_barrier_wait(&ready_barrier);
    assert(!pthread_mutex_lock(&workers_lock));
    // EVENT_WAIT: explicit target release; the fixture alarm bounds debugger failures.
    while (!workers_exit) assert(!pthread_cond_wait(&workers_done, &workers_lock));
    assert(!pthread_mutex_unlock(&workers_lock));
    return NULL;
}
static void gdb_target(int threaded) {
    pthread_t workers[40];
    if (threaded) {
        assert(!pthread_barrier_init(&ready_barrier, NULL, 41));
        for (unsigned i = 0; i < 40; i++)
            assert(!pthread_create(&workers[i], NULL, gdb_worker, NULL));
        pthread_barrier_wait(&ready_barrier);
    }
    puts("READY"); fflush(stdout); char c;
    assert(read(0, &c, 1) == 1); assert(magic_value == 43);
    if (threaded) {
        assert(!pthread_mutex_lock(&workers_lock));
        workers_exit = 1;
        assert(!pthread_cond_broadcast(&workers_done));
        assert(!pthread_mutex_unlock(&workers_lock));
        for (unsigned i = 0; i < 40; i++) assert(!pthread_join(workers[i], NULL));
        assert(!pthread_barrier_destroy(&ready_barrier));
    }
}
int main(int argc, char **argv) {
    assert(argc == 2); alarm(20);
    if (!strcmp(argv[1], "attach")) attach_test(0, 0);
    else if (!strcmp(argv[1], "seize")) attach_test(1, 0);
    else if (!strcmp(argv[1], "trace-exit")) attach_test(1, 1);
    else if (!strcmp(argv[1], "syscall")) syscall_test();
    else if (!strcmp(argv[1], "mixed")) mixed_test();
    else if (!strcmp(argv[1], "kill-stopped")) kill_stopped_test();
    else if (!strcmp(argv[1], "owner-exit")) owner_test(0);
    else if (!strcmp(argv[1], "exitkill")) owner_test(1);
    else if (!strcmp(argv[1], "thread-exec")) exec_thread_test();
    else if (!strcmp(argv[1], "perf-event")) { if (!perf_test()) return 0; }
    else if (!strcmp(argv[1], "gdb-target")) gdb_target(0);
    else if (!strcmp(argv[1], "gdb-thread-target")) gdb_target(1);
    else return 2;
    printf("PASS %s\n", argv[1]); return 0;
}
