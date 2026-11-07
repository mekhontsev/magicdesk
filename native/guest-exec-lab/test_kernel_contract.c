#define _GNU_SOURCE
#include "test_ancillary_contract.h"
#include <asm/ptrace.h>
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/openat2.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include "native_probe.h"

static pid_t child(void) {
    pid_t parent = getpid(), pid = fork(); assert(pid >= 0);
    if (!pid) {
        assert(!prctl(PR_SET_PDEATHSIG, SIGKILL));
        if (getppid() != parent) _exit(99);
    }
    return pid;
}
static int stopped(pid_t pid) {
    int status;
    assert(waitpid(pid, &status, __WALL) == pid && WIFSTOPPED(status));
    return status;
}
static void exited(pid_t pid, int code) {
    int status;
    assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == code);
}
static void observed_info(const char *name, pid_t pid, const siginfo_t *info) {
    printf("OBS %s pid=%d uid=%d signo=%d code=%d status=%d\n", name,
        info->si_pid == pid, info->si_uid == getuid(), info->si_signo,
        info->si_code, info->si_status);
}
static void waitid_peek(void) {
    pid_t pid = child();
    if (!pid) {
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP); _exit(19);
    }
    siginfo_t info;
    for (int i = 0; i < 2; i++) {
        memset(&info, 0, sizeof(info));
        assert(!waitid(P_PID, pid, &info, WSTOPPED | WNOWAIT));
        observed_info("peek", pid, &info);
    }
    assert(!waitid(P_PID, pid, &info, WSTOPPED)); observed_info("consume", pid, &info);
    memset(&info, 0xa5, sizeof(info));
    assert(!waitid(P_PID, pid, &info, WSTOPPED | WNOHANG));
    printf("OBS empty pid=%d signo=%d\n", info.si_pid, info.si_signo);
    assert(!ptrace(PTRACE_CONT, pid, 0, 0));
    assert(!waitid(P_PID, pid, &info, WEXITED | WNOWAIT));
    observed_info("exit", pid, &info); exited(pid, 19);
}
static void wait_errors(void) {
    pid_t pid = child();
    if (!pid) {
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP); _exit(0);
    }
    siginfo_t info;
    assert(!waitid(P_PID, pid, &info, WSTOPPED | WNOWAIT));
    errno = 0;
    int status = 0, result = waitpid(pid, &status, __WALL | 0x10000000);
    printf("OBS invalid-wait4 result=%d errno=%d\n", result == -1 ? -1 : result == pid, errno);
    errno = 0;
    result = waitid(P_PID, pid, &info, WSTOPPED | WNOWAIT | WNOHANG | 0x10000000);
    printf("OBS invalid-waitid result=%d errno=%d\n", result, errno);
    assert(WSTOPSIG(stopped(pid)) == SIGSTOP);
    assert(!kill(pid, SIGKILL));
    assert(waitpid(pid, &status, __WALL) == pid && WIFSIGNALED(status));
}
static volatile sig_atomic_t wait_signal_seen, wait_signal_target;
static void wait_signal_handler(int signal) {
    (void)signal;
    wait_signal_seen=1;
    if (wait_signal_target) kill(wait_signal_target,SIGUSR1);
}
static void debugger_wait_signal(int id,int restart) {
    pid_t pid=child();
    if (!pid) {
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        for (;;) pause();
    }
    assert(WSTOPSIG(stopped(pid))==SIGSTOP);
    struct sigaction action={.sa_handler=wait_signal_handler,.sa_flags=restart ? SA_RESTART : 0}, previous;
    sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGALRM,&action,&previous));
    sigset_t blocked, saved;
    sigemptyset(&blocked); sigaddset(&blocked,SIGCHLD);
    assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
    wait_signal_seen=0; wait_signal_target=restart ? pid : 0;
    assert(!ptrace(PTRACE_CONT,pid,0,0));
    /* EVENT_WAIT: the alarm is the signal stimulus, not readiness evidence.
     * No inferior event can release the wait before this handler runs. The
     * outer supervisor deadline bounds a signal that cannot be delivered. */
    alarm(1);
    siginfo_t info={0}; int status=0;
    errno=0;
    int result=id ? waitid(P_PID,pid,&info,WSTOPPED) : waitpid(pid,&status,__WALL);
    int error=errno;
    assert(wait_signal_seen);
    if (restart) {
        assert(result==(id ? 0 : pid));
        assert(id ? info.si_pid==pid && info.si_code==CLD_TRAPPED && info.si_status==SIGUSR1
                  : WIFSTOPPED(status) && WSTOPSIG(status)==SIGUSR1);
    } else {
        assert(result==-1 && error==EINTR);
        assert(!kill(pid,SIGUSR1));
        assert(WSTOPSIG(stopped(pid))==SIGUSR1);
    }
    assert(!sigaction(SIGALRM,&previous,NULL)); alarm(20);
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL));
    assert(!ptrace(PTRACE_DETACH,pid,0,0));
    assert(!kill(pid,SIGKILL));
    assert(waitpid(pid,&status,0)==pid && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    printf("OBS debugger-wait id=%d restart=%d signal=1 detached=1\n",id,restart);
}
static void await_native_wait(pid_t parent, int syscall_number) {
    char path[64], bytes[512];
    snprintf(path, sizeof(path), "/proc/%d/syscall", parent);
    int fd = open(path, O_RDONLY); assert(fd >= 0);
    struct timespec start, now; assert(!clock_gettime(CLOCK_MONOTONIC, &start));
    // Bounded state observation: prove the parent is inside the kernel wait
    // before TRACEME. Expiry fails the fixture, never substitutes for evidence.
    for (;;) {
        assert(lseek(fd, 0, SEEK_SET) == 0);
        ssize_t length = read(fd, bytes, sizeof(bytes)-1); assert(length > 0);
        bytes[length] = 0;
        int observed;
        if (sscanf(bytes, "%d", &observed) == 1 && observed == syscall_number) break;
        assert(!clock_gettime(CLOCK_MONOTONIC, &now) && now.tv_sec-start.tv_sec < 5);
        sched_yield();
    }
    close(fd);
}
struct late_wait {
    int id, ready;
    pid_t pid;
};
static void *late_waiter(void *argument) {
    struct late_wait *wait = argument;
    pid_t tid = syscall(SYS_gettid);
    assert(write(wait->ready, &tid, sizeof(tid)) == sizeof(tid));
    int status;
    if (wait->id) {
        siginfo_t info;
        assert(!waitid(P_PID, wait->pid, &info, WSTOPPED));
        assert(info.si_pid == wait->pid && info.si_code == CLD_TRAPPED && info.si_status == SIGSTOP);
    } else {
        assert(waitpid(wait->pid, &status, __WALL) == wait->pid);
        assert(WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP);
    }
    return NULL;
}
static void traceme_late_wait(int id, int threaded) {
    sigset_t blocked, saved;
    sigemptyset(&blocked); sigaddset(&blocked, SIGCHLD);
    assert(!sigprocmask(SIG_BLOCK, &blocked, &saved));
    int ready[2]; assert(!pipe(ready));
    pid_t pid = child();
    if (!pid) {
        close(ready[1]); pid_t waiter;
        assert(read(ready[0], &waiter, sizeof(waiter)) == sizeof(waiter)); close(ready[0]);
        await_native_wait(waiter, id ? SYS_waitid : SYS_wait4);
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0));
        raise(SIGSTOP); _exit(23);
    }
    close(ready[0]);
    struct late_wait wait = {.id=id, .ready=ready[1], .pid=pid};
    if (threaded) {
        pthread_t worker; assert(!pthread_create(&worker, NULL, late_waiter, &wait));
        assert(!pthread_join(worker, NULL));
    } else {
        late_waiter(&wait);
    }
    close(ready[1]);
    assert(!ptrace(PTRACE_DETACH, pid, 0, 0)); exited(pid, 23);
    assert(!sigprocmask(SIG_SETMASK, &saved, NULL));
    puts("OBS late-traceme stopped=1 detached=1 exited=23");
}
static void ptrace_options(void) {
    int ready[2], release[2]; assert(!pipe(ready) && !pipe(release));
    pid_t pid = child();
    if (!pid) {
        close(ready[0]); close(release[1]);
        assert(write(ready[1], "r", 1) == 1);
        char c; assert(read(release[0], &c, 1) == 1); _exit(0);
    }
    close(ready[1]); close(release[0]); char c;
    assert(read(ready[0], &c, 1) == 1); close(ready[0]);
    errno = 0; long result = ptrace(PTRACE_SEIZE, pid, 1, 0);
    printf("OBS seize-address result=%ld errno=%d\n", result, errno); assert(result == -1);
    errno = 0; result = ptrace(PTRACE_SEIZE, pid, 0, 1UL << 31);
    printf("OBS seize-options result=%ld errno=%d\n", result, errno); assert(result == -1);
    assert(!ptrace(PTRACE_SEIZE, pid, 0, PTRACE_O_EXITKILL));
    assert(!ptrace(PTRACE_INTERRUPT, pid, 0, 0)); stopped(pid);
    errno = 0; result = ptrace(PTRACE_SETOPTIONS, pid, 0, 1UL << 31);
    printf("OBS set-options result=%ld errno=%d\n", result, errno);
    assert(!ptrace(PTRACE_DETACH, pid, 0, 0));
    assert(write(release[1], "x", 1) == 1); close(release[1]); exited(pid, 0);
}
static void syscall_birth(void) {
    pid_t pid=child();
    if (!pid) {
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        pid_t newborn=syscall(SYS_clone,SIGCHLD,0,0,0,0);
        assert(newborn>=0);
        if (!newborn) { syscall(SYS_getpid); _exit(0); }
        exited(newborn,0); _exit(0);
    }
    stopped(pid);
    assert(!ptrace(PTRACE_SETOPTIONS,pid,0,PTRACE_O_TRACEFORK|PTRACE_O_TRACESYSGOOD));
    assert(!ptrace(PTRACE_SYSCALL,pid,0,0));
    unsigned live=1, calls=0; int first=-1;
    while (live) {
        int status; pid_t task=waitpid(-1,&status,__WALL); assert(task>0);
        if (WIFEXITED(status)) { assert(!WEXITSTATUS(status)); --live; continue; }
        assert(WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_FORK) ++live;
        if (task!=pid && WSTOPSIG(status)==(SIGTRAP|0x80)) {
            struct ptrace_syscall_info info={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,task,sizeof(info),&info)>=24);
            if (!calls++) first=info.op;
        }
        assert(!ptrace(PTRACE_SYSCALL,task,0,0));
    }
    assert(first==PTRACE_SYSCALL_INFO_ENTRY && calls==3);
    printf("OBS syscall-birth first=%d calls=%u\n",first,calls);
}
static void syscall_info(void) {
    pid_t pid = child();
    if (!pid) {
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        errno=0;
        assert(syscall(SYS_chdir,"")==-1 && errno==ENOENT);
        _exit(0);
    }
    stopped(pid);
    struct ptrace_syscall_info initial={0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(initial),&initial)>=24);
    assert(initial.op==PTRACE_SYSCALL_INFO_NONE && initial.arch==AUDIT_ARCH_AARCH64
        && initial.instruction_pointer && initial.stack_pointer);
    puts("OBS signal-stop syscall-info common-fields=1");
    assert(!ptrace(PTRACE_SYSCALL, pid, 0, 0)); stopped(pid);
    struct user_pt_regs registers;
    struct iovec registers_io={&registers,sizeof(registers)};
    assert(!ptrace(PTRACE_GETREGSET,pid,NT_PRSTATUS,&registers_io));
    printf("OBS syscall-register entry=%llu\n",(unsigned long long)registers.regs[7]);
    assert(registers.regs[7]==0);
    struct ptrace_syscall_info info = {0};
    long size = ptrace(PTRACE_GET_SYSCALL_INFO, pid, sizeof(info), &info);
    printf("OBS no-sysgood size=%ld op=%u\n", size, info.op);
    assert(!ptrace(PTRACE_SETOPTIONS, pid, 0, PTRACE_O_TRACESYSGOOD));
    assert(!ptrace(PTRACE_SYSCALL, pid, 0, 0));
    assert(WSTOPSIG(stopped(pid)) == (SIGTRAP | 0x80));
    registers_io.iov_len=sizeof(registers);
    assert(!ptrace(PTRACE_GETREGSET,pid,NT_PRSTATUS,&registers_io));
    printf("OBS syscall-register exit=%llu\n",(unsigned long long)registers.regs[7]);
    assert(registers.regs[7]==1);
    memset(&info, 0, sizeof(info));
    size = ptrace(PTRACE_GET_SYSCALL_INFO, pid, sizeof(info), &info);
    printf("OBS sysgood size=%ld op=%u\n", size, info.op);
    unsigned char short_info[8]; memset(short_info, 0xa5, sizeof(short_info));
    long short_size = ptrace(PTRACE_GET_SYSCALL_INFO, pid, 1, short_info);
    int intact = 1; for (size_t i = 1; i < sizeof(short_info); i++) intact &= short_info[i] == 0xa5;
    printf("OBS short size=%ld op=%u intact=%d\n", short_size, short_info[0], intact);
    printf("OBS zero size=%ld\n", ptrace(PTRACE_GET_SYSCALL_INFO, pid, 0, NULL));
    assert(!ptrace(PTRACE_CONT, pid, 0, 0)); exited(pid, 0);
    syscall_birth();
}
static volatile sig_atomic_t usr1, usr2;
static void signal_handler(int number) { if (number == SIGUSR1) usr1++; else usr2++; }
static void signal_delivery(void) {
    pid_t pid = child();
    if (!pid) {
        struct sigaction action = {.sa_handler = signal_handler};
        assert(!sigaction(SIGUSR1, &action, NULL) && !sigaction(SIGUSR2, &action, NULL));
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        raise(SIGUSR1); raise(SIGUSR1);
        assert(!usr1 && usr2 == 1); _exit(0);
    }
    stopped(pid); assert(!ptrace(PTRACE_CONT, pid, 0, 0));
    for (int i = 0; i < 2; i++) {
        assert(WSTOPSIG(stopped(pid)) == SIGUSR1);
        siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, pid, 0, &info));
        printf("OBS signal pid=%d uid=%d signo=%d code=%d\n",
            info.si_pid == pid, info.si_uid == getuid(), info.si_signo, info.si_code);
        assert(!ptrace(PTRACE_CONT, pid, 0, i ? SIGUSR2 : 0));
    }
    exited(pid, 0);
}
static void file_lifetime(void) {
    assert(!mkdir("old", 0700));
    int dir = open("old", O_DIRECTORY | O_RDONLY); assert(dir >= 0);
    int fd = openat(dir, "file", O_CREAT | O_EXCL | O_RDWR, 0600); assert(fd >= 0);
    assert(write(fd, "payload", 7) == 7);
    int linked = !linkat(dir, "file", dir, "alias", 0);
    if (!linked) {
        fprintf(stderr, "UNAVAILABLE native hardlink errno=%d\n", errno);
        assert(errno == EACCES || errno == EPERM);
    }
    assert(!rename("old", "new") && !symlink("missing", "old"));
    struct stat first, second;
    assert(!fstatat(dir, linked ? "alias" : "file", &first, 0) && !fstat(fd, &second));
    assert(first.st_ino == second.st_ino && first.st_dev == second.st_dev && first.st_nlink == (linked ? 2 : 1));
    assert(!unlinkat(dir, "file", 0));
    if (linked) assert(!unlinkat(dir, "alias", 0));
    assert(!fstat(fd, &first) && first.st_nlink == 0);
    int pair[2]; assert(!socketpair(AF_UNIX, SOCK_DGRAM, 0, pair));
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'x'; struct iovec iov = {&byte, 1};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS; cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(fd));
    assert(sendmsg(pair[0], &msg, 0) == 1); close(fd);
    memset(&control, 0, sizeof(control)); assert(recvmsg(pair[1], &msg, 0) == 1);
    cmsg = CMSG_FIRSTHDR(&msg); assert(cmsg && cmsg->cmsg_type == SCM_RIGHTS);
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    char data[7]; assert(pread(fd, data, sizeof(data), 0) == 7 && !memcmp(data, "payload", 7));
    assert(!fstat(fd, &second) && second.st_ino == first.st_ino && second.st_nlink == 0);
    close(fd); close(dir); close(pair[0]); close(pair[1]);
    assert(!unlink("old") && !rmdir("new")); puts("OBS dirfd-hardlink-unlink-rights=1");
}
static int open_resolved(int dir, const char *path, unsigned long flags) {
    struct open_how how = {.flags = O_RDONLY, .resolve = flags};
    return syscall(SYS_openat2, dir, path, &how, sizeof(how));
}
static void resolution(void) {
    assert(!mkdir("root", 0700));
    int dir = open("root", O_DIRECTORY | O_RDONLY); assert(dir >= 0);
    int fd = openat(dir, "file", O_CREAT | O_EXCL | O_RDWR, 0600); assert(fd >= 0); close(fd);
    assert(!symlinkat("file", dir, "link"));
    errno = 0; fd = open_resolved(dir, "../outside", RESOLVE_BENEATH);
    printf("OBS beneath fd=%d errno=%d\n", fd, errno); assert(fd < 0);
    errno = 0; fd = open_resolved(dir, "link", RESOLVE_NO_SYMLINKS);
    printf("OBS no-symlinks fd=%d errno=%d\n", fd, errno); assert(fd < 0);
    fd = open_resolved(dir, "/file", RESOLVE_IN_ROOT); assert(fd >= 0); close(fd);
    struct { struct open_how how; uint64_t tail; } extended = {.how.flags = O_RDONLY};
    fd = syscall(SYS_openat2, dir, "file", &extended, sizeof(extended)); assert(fd >= 0); close(fd);
    extended.tail = 1; errno = 0;
    fd = syscall(SYS_openat2, dir, "file", &extended, sizeof(extended));
    printf("OBS extended fd=%d errno=%d\n", fd, errno); assert(fd < 0);
    assert(!unlinkat(dir, "file", 0) && !unlinkat(dir, "link", 0)); close(dir); assert(!rmdir("root"));
}
static pthread_barrier_t barrier;
static void rendezvous(void) {
    int result = pthread_barrier_wait(&barrier);
    assert(!result || result == PTHREAD_BARRIER_SERIAL_THREAD);
}
static void *rename_worker(void *unused) {
    (void)unused;
    for (int i = 0; i < 64; i++) {
        rendezvous();
        assert(!syscall(SYS_renameat2, AT_FDCWD, "root/slot", AT_FDCWD, "root/swap", RENAME_EXCHANGE));
        rendezvous();
    }
    return NULL;
}
static void resolution_race(void) {
    assert(!mkdir("root", 0700) && !mkdir("root/slot", 0700) && !mkdir("outside", 0700));
    int fd = open("root/slot/value", O_CREAT | O_EXCL | O_WRONLY, 0600); assert(fd >= 0);
    assert(write(fd, "I", 1) == 1); close(fd);
    fd = open("outside/value", O_CREAT | O_EXCL | O_WRONLY, 0600); assert(fd >= 0);
    assert(write(fd, "O", 1) == 1); close(fd);
    assert(!symlink("../outside", "root/swap"));
    int dir = open("root", O_DIRECTORY | O_RDONLY); assert(dir >= 0);
    assert(!pthread_barrier_init(&barrier, NULL, 2));
    pthread_t worker; assert(!pthread_create(&worker, NULL, rename_worker, NULL));
    for (int i = 0; i < 64; i++) {
        rendezvous(); fd = open_resolved(dir, "slot/value", RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS);
        if (fd >= 0) { char c; assert(read(fd, &c, 1) == 1 && c == 'I'); close(fd); }
        else assert(errno == ELOOP || errno == EAGAIN || errno == EXDEV);
        rendezvous();
    }
    assert(!pthread_join(worker, NULL) && !pthread_barrier_destroy(&barrier));
    fd = open_resolved(dir, "slot/value", RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS); assert(fd >= 0); close(fd);
    assert(!unlink("root/slot/value") && !unlink("root/swap") && !unlink("outside/value"));
    close(dir); assert(!rmdir("root/slot") && !rmdir("root") && !rmdir("outside"));
    puts("OBS rename-confinement=1");
}
struct proc_owners { int original, replacement; };
static void proc_path_result(const char *name, const char *path) {
    int fd=open(path,O_PATH|O_CLOEXEC), open_error=fd<0 ? errno : 0;
    if (fd>=0) assert(!close(fd));
    struct stat st;
    int result=stat(path,&st), stat_error=result<0 ? errno : 0;
    printf("OBS proc-path=%s open=%d errno=%d stat=%d errno=%d\n",
        name,fd<0 ? -1 : 0,open_error,result,stat_error);
}
static void proc_value(const char *prefix, int fd, char expected) {
    char path[128], value=0;
    snprintf(path,sizeof(path),"%s/fd/%d",prefix,fd);
    int copy=open(path,O_RDONLY|O_CLOEXEC); assert(copy>=0);
    assert(read(copy,&value,1)==1 && value==expected && !close(copy));
}
static void *proc_owner_thread(void *opaque) {
    struct proc_owners *c=opaque;
    assert(!unshare(CLONE_FILES|CLONE_FS));
    assert(dup3(c->replacement,c->original,O_CLOEXEC)==c->original);
    assert(!chdir("other"));
    proc_value("/proc/self",c->original,'a');
    proc_value("/proc/thread-self",c->original,'b');
    struct stat leader, own, current;
    assert(!stat("/proc/self/cwd",&leader) && !stat("/proc/thread-self/cwd",&own) && !stat(".",&current));
    assert(leader.st_ino!=own.st_ino && own.st_ino==current.st_ino);
    char path[128];
    snprintf(path,sizeof(path),"/proc/thread-self/task/%ld/fd/%d",syscall(SYS_gettid),c->original);
    proc_path_result("thread-task",path);
    snprintf(path,sizeof(path),"/proc/0%ld/cwd",syscall(SYS_gettid));
    proc_path_result("padded-tid",path);
    return NULL;
}
static void proc_owners(void) {
    struct proc_owners c={.original=open("first",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600),
        .replacement=open("second",O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600)};
    assert(c.original>=0 && c.replacement>=0 && !mkdir("other",0700));
    assert(write(c.original,"a",1)==1 && write(c.replacement,"b",1)==1);
    char path[128];
    snprintf(path,sizeof(path),"/proc/self/fd/0%d",c.original);
    proc_path_result("padded-fd",path);
    pthread_t thread; assert(!pthread_create(&thread,NULL,proc_owner_thread,&c));
    /* EVENT_WAIT: the worker verifies its detached resources; alarm bounds failure. */
    assert(!pthread_join(thread,NULL));
    proc_value("/proc/self",c.original,'a');
    proc_value("/proc/thread-self",c.original,'a');
    assert(!close(c.original) && !close(c.replacement));
    puts("OBS proc-self-and-thread-self independent-fs=1 independent-files=1");
}
int main(int argc, char **argv) {
    md_test_native_probe(argc,argv);
    assert((argc==3 || (argc==4 && !strcmp(argv[3],"--filtered"))) && !chdir(argv[2])); setbuf(stdout, NULL);
    if (argc==4) {
        struct sock_filter instructions[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,CLONE_THREAD,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(instructions)/sizeof(*instructions),instructions};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        assert(!prctl(PR_SET_DUMPABLE,0));
    }
    // EVENT_WAIT: ptrace stops, pipes and barriers; alarm fails a stuck fixture.
    alarm(20);
    if (!strcmp(argv[1], "waitid-peek")) waitid_peek();
    else if (!strcmp(argv[1], "wait-errors")) wait_errors();
    else if (!strcmp(argv[1], "debug-wait4-signal")) debugger_wait_signal(0,0);
    else if (!strcmp(argv[1], "debug-waitid-signal")) debugger_wait_signal(1,0);
    else if (!strcmp(argv[1], "debug-wait4-restart")) debugger_wait_signal(0,1);
    else if (!strcmp(argv[1], "debug-waitid-restart")) debugger_wait_signal(1,1);
    else if (!strcmp(argv[1], "traceme-late-wait4")) traceme_late_wait(0, 0);
    else if (!strcmp(argv[1], "traceme-late-waitid")) traceme_late_wait(1, 0);
    else if (!strcmp(argv[1], "traceme-thread-wait4")) traceme_late_wait(0, 1);
    else if (!strcmp(argv[1], "traceme-thread-waitid")) traceme_late_wait(1, 1);
    else if (!strcmp(argv[1], "ptrace-options")) ptrace_options();
    else if (!strcmp(argv[1], "syscall-info")) syscall_info();
    else if (!strcmp(argv[1], "signal-delivery")) signal_delivery();
    else if (!strcmp(argv[1], "file-lifetime")) file_lifetime();
    else if (!strcmp(argv[1], "resolution")) resolution();
    else if (!strcmp(argv[1], "resolution-race")) resolution_race();
    else if (!strcmp(argv[1], "proc-owners")) proc_owners();
    else if (!md_test_ancillary_contract(argv[1])) return 2;
    printf("PASS %s\n", argv[1]); return 0;
}
