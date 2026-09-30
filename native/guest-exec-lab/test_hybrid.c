#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* Native boundary probe only. No guest policy is disabled or reported as a
 * browser certification. The supervisor traces only its own fixture tree. */
enum mode { MIXED, EXEC_REDIRECT, EXEC_ARG0_LIMIT, EXEC_RECHECK_AT, EXEC_REPLAY_RECHECK, PROTECTED_EXEC,
    PROTECTED, PROTECTED_COPY, NOTIFICATION_COPY, FILTER_RACE, COPY_SIGNAL_CONTROL, COPY_SIGNAL_MASK,
    NOTIFY_SIGNAL_EINTR, NOTIFY_SIGNAL_RESTART,
    KILL_CONTROL, TRACER_CONTROL };
static const char *stage;
static int signal_fd;
static sigset_t original_mask;
static pid_t group, members[16];
static unsigned member_count;
static long marker = 12345;
static const char exec_target[] = "/proc/self/exe";
static const char exec_source[] = "/not-present/md-hybrid-guest";
extern const char md_hybrid_copy[], md_hybrid_copy_done[];
extern const char md_hybrid_string[], md_hybrid_string_done[];
extern long md_hybrid_getppid(void);
extern const char md_hybrid_getppid_return[];
extern long md_hybrid_openat(const char *path);
extern const char md_hybrid_openat_return[];
enum copy_phase {
    COPY_TRANSFER, NOTIFY_CANCEL, COPY_PATH, REPLAY_ENTER, REPLAY_LEAVE,
    REPLAY_COMMITTED, REPLAY_SIGNAL, COPY_STEP_INITIAL, COPY_STEP_ENTERED, COPY_AWAIT_BREAK
};
struct copy_step {
    pid_t pid;
    struct user_pt_regs saved;
    struct seccomp_notif original;
    enum copy_phase phase;
    uint64_t saved_mask;
    int masked, inject_signal;
};
static struct copy_step copies[16];
static volatile unsigned char *transfer;
enum { COPY_BYTES = 128 };
static int control_pipe[2], ack_pipe[2];
static int notification_signal_mode(enum mode mode) {
    return mode == NOTIFY_SIGNAL_EINTR || mode == NOTIFY_SIGNAL_RESTART;
}
static int notification_copy_mode(enum mode mode) {
    return mode == NOTIFICATION_COPY || mode == FILTER_RACE || notification_signal_mode(mode);
}
static void cleanup(void) {
    if (!group) return;
    kill(-group, SIGKILL);
    for (unsigned i = 0; i < member_count; ++i) if (members[i]) kill(members[i], SIGKILL);
    /* EVENT_WAIT: reap owned tracees after cancellation; the runner's outer
     * deadline bounds a kernel wait that cannot complete. */
    while (waitpid(-1, NULL, __WALL) > 0 || errno == EINTR) { }
    group = 0;
}
static void failed(const char *expression, unsigned line) {
    fprintf(stderr, "FAIL %s line=%u: %s errno=%d\n", stage, line, expression, errno);
    cleanup();
    exit(1);
}
#define CHECK(x) do { if (!(x)) failed(#x, __LINE__); } while (0)
static void member(pid_t pid) {
    for (unsigned i = 0; i < member_count; ++i) if (members[i] == pid) return;
    CHECK(member_count < sizeof(members) / sizeof(*members));
    members[member_count++] = pid;
}
static void forget(pid_t pid) {
    for (unsigned i = 0; i < member_count; ++i) if (members[i] == pid) { members[i] = 0; return; }
    CHECK(0);
}
static int living(void) {
    for (unsigned i = 0; i < member_count; ++i) if (members[i]) return 1;
    return 0;
}
static void ready(int fd) {
    /* EVENT_WAIT: explicit fixture IPC readiness; expiry fails, never settles. */
    CHECK(md_event_wait_fd(fd, POLLIN, md_event_now() + 5000000000LL) >= 0);
}
static void drain_signals(void) {
    struct signalfd_siginfo info;
    while (read(signal_fd, &info, sizeof(info)) == sizeof(info)) { }
    CHECK(errno == EAGAIN);
}
static pid_t trace_event(int *status, int64_t deadline) {
    for (;;) {
        pid_t pid = waitpid(-1, status, __WALL | WNOHANG);
        if (pid > 0) return pid;
        CHECK(pid == 0 || errno == EINTR);
        /* EVENT_WAIT: SIGCHLD announces ptrace stop/exit. No timed state poll. */
        CHECK(md_event_wait_fd(signal_fd, POLLIN, deadline) >= 0);
        drain_signals();
    }
}
static void resume(pid_t pid, int signal) { CHECK(!ptrace(PTRACE_CONT, pid, 0, signal)); }
static void follow_syscall(pid_t pid) { CHECK(!ptrace(PTRACE_SYSCALL, pid, 0, 0)); }
static struct user_pt_regs registers(pid_t pid) {
    struct user_pt_regs regs;
    struct iovec data = {&regs, sizeof(regs)};
    CHECK(!ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &data) && data.iov_len == sizeof(regs));
    return regs;
}
static void set_registers(pid_t pid, struct user_pt_regs *regs) {
    struct iovec data = {regs, sizeof(*regs)};
    CHECK(!ptrace(PTRACE_SETREGSET, pid, (void *)NT_PRSTATUS, &data));
}
static void syscall_number(pid_t pid, int number) {
    struct iovec data = {&number, sizeof(number)};
    CHECK(!ptrace(PTRACE_SETREGSET, pid, (void *)NT_ARM_SYSTEM_CALL, &data));
}
static int memory_fd(pid_t pid) {
    char path[64]; snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    return open(path, O_RDWR | O_CLOEXEC);
}
static long remote_read(pid_t pid, uintptr_t address, void *buffer, size_t size) {
    struct iovec local = {buffer, size}, remote = {(void *)address, size};
    return syscall(SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0);
}
static void pass_listener(int channel, int listener) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'n'; struct iovec data = {&byte, 1};
    struct msghdr message = {.msg_iov = &data, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &listener, sizeof(listener));
    CHECK(sendmsg(channel, &message, MSG_NOSIGNAL) == 1);
    close(listener); close(channel);
}
static int take_listener(int channel) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte; struct iovec data = {&byte, 1};
    struct msghdr message = {.msg_iov = &data, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ready(channel);
    CHECK(recvmsg(channel, &message, MSG_CMSG_CLOEXEC | MSG_TRUNC) == 1 && byte == 'n');
    CHECK(!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    CHECK(c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS
        && c->cmsg_len == CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&message, c));
    int fd; memcpy(&fd, CMSG_DATA(c), sizeof(fd)); close(channel);
    return fd;
}
static int install_program(struct sock_filter *rules, unsigned count, unsigned flags) {
    struct sock_fprog program = {count, rules};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    int result = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, flags, &program);
    CHECK(result >= 0); return result;
}
static int base_filter(enum mode mode) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 41),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execve, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 42),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execveat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 42),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, mode == MIXED || notification_copy_mode(mode)
            ? SECCOMP_RET_USER_NOTIF : SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    return install_program(rules, sizeof(rules) / sizeof(*rules), SECCOMP_FILTER_FLAG_NEW_LISTENER);
}
static void application_filter(int number, unsigned result) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, number, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, result),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    install_program(rules, sizeof(rules) / sizeof(*rules), 0);
}
static unsigned char signal_stack[65536] __attribute__((aligned(16)));
static volatile sig_atomic_t caught;
static volatile sig_atomic_t helper_context_exposed;
static int expected_restart;
static char *signal_path;
static void inspect_async_context(int signal, siginfo_t *info, void *context) {
    ucontext_t *uc = context;
    uintptr_t pc = uc->uc_mcontext.pc;
    char local;
    if (signal != SIGUSR1 || info->si_code != SI_QUEUE || info->si_value.sival_int != 123
            || !sigismember(&uc->uc_sigmask, SIGUSR2) || sigismember(&uc->uc_sigmask, SIGUSR1)
            || (uintptr_t)&local < (uintptr_t)signal_stack
            || (uintptr_t)&local >= (uintptr_t)signal_stack + sizeof(signal_stack)) _exit(95);
    if (pc >= (uintptr_t)md_hybrid_copy && pc <= (uintptr_t)md_hybrid_copy_done)
        helper_context_exposed = 1;
    else if (pc == (uintptr_t)md_hybrid_getppid_return && uc->uc_mcontext.regs[0] == 555)
        helper_context_exposed = 2;
    else _exit(96);
    if (syscall(SYS_getuid) != 735) _exit(97);
}
static void inspect_interrupted_context(int signal, siginfo_t *info, void *context) {
    ucontext_t *uc = context;
    uintptr_t pc = (uintptr_t)md_hybrid_openat_return - (expected_restart ? 4 : 0);
    long result = expected_restart ? AT_FDCWD : -EINTR;
    if (signal != SIGUSR1 || info->si_code != SI_QUEUE || info->si_value.sival_int != 123
            || uc->uc_mcontext.pc != pc || (long)uc->uc_mcontext.regs[0] != result
            || !sigismember(&uc->uc_sigmask, SIGUSR2) || sigismember(&uc->uc_sigmask, SIGUSR1)) _exit(98);
    if (syscall(SYS_getuid) != 735) _exit(99);
    long fd = md_hybrid_openat("/hybrid-handler");
    char zero = 1;
    if (fd < 0 || read(fd, &zero, 1) != 1 || zero || close(fd)) _exit(100);
    if (expected_restart) {
        static const char replacement[] = "/hybrid-next";
        for (unsigned i = 0; i < sizeof(replacement); ++i) signal_path[i] = replacement[i];
    }
    caught++;
}
static void application_signal(int signal, siginfo_t *info, void *context) {
    char local;
    uintptr_t p = (uintptr_t)&local, start = (uintptr_t)signal_stack;
    if (signal != SIGSYS || info->si_code != 1 || info->si_errno != 73
            || p < start || p >= start + sizeof(signal_stack)) _exit(91);
    if (syscall(SYS_getuid) != 735) _exit(92);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = 777;
    caught++;
}
static void read_zero(int fd) {
    CHECK(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
    char zero = 1; CHECK(read(fd, &zero, 1) == 1 && !zero); close(fd);
}
static void mixed_child(void) {
    read_zero(syscall(SYS_openat, AT_FDCWD, "/hybrid-zero", O_RDONLY | O_CLOEXEC, 0));
    int pipefd[2]; CHECK(!pipe2(pipefd, O_CLOEXEC));
    for (unsigned i = 0; i < 1000; ++i) {
        char sent = 'x', received;
        CHECK(syscall(SYS_getpid) > 0);
        CHECK(write(pipefd[1], &sent, 1) == 1 && read(pipefd[0], &received, 1) == 1 && received == sent);
    }
    close(pipefd[0]); close(pipefd[1]);
    CHECK(syscall(SYS_getppid) == 555);
    application_filter(SYS_getppid, SECCOMP_RET_ERRNO | EACCES);
    errno = 0; CHECK(syscall(SYS_getppid) == -1 && errno == EACCES);
    application_filter(SYS_openat, SECCOMP_RET_ERRNO | EACCES);
    errno = 0; CHECK(syscall(SYS_openat, AT_FDCWD, "/hybrid-zero", O_RDONLY, 0) == -1 && errno == EACCES);
    stack_t stack = {.ss_sp = signal_stack, .ss_size = sizeof(signal_stack)};
    CHECK(!sigaltstack(&stack, NULL));
    struct sigaction action = {.sa_sigaction = application_signal, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGSYS, &action, NULL));
    application_filter(SYS_getppid, SECCOMP_RET_TRAP | 73);
    CHECK(syscall(SYS_getppid) == 777 && caught == 1);
    application_filter(SYS_openat, SECCOMP_RET_TRAP | 73);
    CHECK(syscall(SYS_openat, AT_FDCWD, "/hybrid-zero", O_RDONLY, 0) == 777 && caught == 2);
}
static void exec_pointer_filter(int number, unsigned argument) {
    uintptr_t denied = (uintptr_t)exec_target;
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, number, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args) + argument * 8 + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(denied >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args) + argument * 8),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)denied, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    install_program(rules, sizeof(rules) / sizeof(*rules), 0);
}
static void exec_child(enum mode mode) {
    char *const arguments[] = {"md-hybrid-test", "--after-exec", NULL};
    char *const environment[] = {NULL};
    if (mode == EXEC_ARG0_LIMIT || mode == EXEC_REPLAY_RECHECK) exec_pointer_filter(SYS_execve, 0);
    if (mode == EXEC_RECHECK_AT) exec_pointer_filter(SYS_execveat, 1);
    if (mode == PROTECTED_EXEC) { CHECK(!prctl(PR_SET_DUMPABLE, 0)); marker++; }
    errno = 0;
    if (mode == EXEC_RECHECK_AT) syscall(SYS_execveat, AT_FDCWD, exec_source, arguments, environment, 0);
    else syscall(SYS_execve, exec_source, arguments, environment);
    if (mode == EXEC_RECHECK_AT || mode == EXEC_REPLAY_RECHECK) CHECK(errno == EACCES);
    else CHECK(0);
}
static void protected_child(void) {
    CHECK(!prctl(PR_SET_DUMPABLE, 0));
    marker++;
    CHECK(syscall(SYS_getppid) == 555 && !prctl(PR_GET_DUMPABLE));
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        marker++;
        CHECK(syscall(SYS_getppid) == 555 && !prctl(PR_GET_DUMPABLE));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: exact fork child exit; supervisor/runner deadlines cancel. */
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(marker == 12346);
}
static void notification_copy_child(void) {
    CHECK(!prctl(PR_SET_DUMPABLE, 0));
    char path[32] = "/hybrid-zero";
    read_zero(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, (void *)1, O_RDONLY, 0) == -1 && errno == EFAULT);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        memcpy(path, "/hybrid-fork", sizeof("/hybrid-fork"));
        read_zero(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0));
        CHECK(!prctl(PR_GET_DUMPABLE));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: exact child exit, bounded by the supervisor deadline. */
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    application_filter(SYS_openat, SECCOMP_RET_ERRNO | EACCES);
    errno = 0; CHECK(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY, 0) == -1 && errno == EACCES);
    CHECK(!prctl(PR_GET_DUMPABLE));
}
static void *install_concurrent_filter(void *unused) {
    (void)unused;
    char byte; ready(control_pipe[0]); CHECK(read(control_pipe[0], &byte, 1) == 1 && byte == 'f');
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    install_program(rules, sizeof(rules) / sizeof(*rules), SECCOMP_FILTER_FLAG_TSYNC);
    CHECK(write(ack_pipe[1], &byte, 1) == 1);
    return NULL;
}
static void filter_race_child(void) {
    CHECK(!prctl(PR_SET_DUMPABLE, 0));
    pthread_t worker; CHECK(!pthread_create(&worker, NULL, install_concurrent_filter, NULL));
    char path[32] = "/hybrid-zero";
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0) == -1 && errno == EACCES);
    CHECK(!pthread_join(worker, NULL) && !prctl(PR_GET_DUMPABLE));
}
static void copy_signal_child(enum mode mode) {
    struct sigaction action = {.sa_sigaction = inspect_async_context, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGUSR1, &action, NULL));
    stack_t stack = {.ss_sp = signal_stack, .ss_size = sizeof(signal_stack)};
    CHECK(!sigaltstack(&stack, NULL));
    sigset_t mask, previous, after;
    sigemptyset(&mask); sigaddset(&mask, SIGUSR2);
    CHECK(!sigprocmask(SIG_BLOCK, &mask, &previous));
    CHECK(!prctl(PR_SET_DUMPABLE, 0)); marker++;
    CHECK(md_hybrid_getppid() == 555 && !prctl(PR_GET_DUMPABLE));
    CHECK(helper_context_exposed == (mode == COPY_SIGNAL_CONTROL ? 1 : 2));
    CHECK(!sigprocmask(SIG_SETMASK, &previous, &after));
    sigaddset(&previous, SIGUSR2);
    CHECK(!memcmp(&after, &previous, sizeof(after)));
}
static void notification_signal_child(enum mode mode) {
    expected_restart = mode == NOTIFY_SIGNAL_RESTART;
    struct sigaction action = {.sa_sigaction = inspect_interrupted_context,
        .sa_flags = SA_SIGINFO | (expected_restart ? SA_RESTART : 0)};
    sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGUSR1, &action, NULL));
    sigset_t mask, previous, after;
    sigemptyset(&mask); sigaddset(&mask, SIGUSR2);
    CHECK(!sigprocmask(SIG_BLOCK, &mask, &previous));
    CHECK(!prctl(PR_SET_DUMPABLE, 0));
    char path[32] = "/hybrid-zero";
    signal_path = path;
    long result = md_hybrid_openat(path);
    if (expected_restart) read_zero(result);
    else CHECK(result == -EINTR);
    CHECK(caught == 1 && !prctl(PR_GET_DUMPABLE));
    CHECK(!sigprocmask(SIG_SETMASK, &previous, &after));
    sigaddset(&previous, SIGUSR2); CHECK(!memcmp(&after, &previous, sizeof(after)));
}
static void child_main(enum mode mode, int channel) {
    pass_listener(channel, base_filter(mode));
    switch (mode) {
    case MIXED: mixed_child(); break;
    case EXEC_REDIRECT: case EXEC_ARG0_LIMIT: case EXEC_RECHECK_AT: case EXEC_REPLAY_RECHECK:
    case PROTECTED_EXEC: exec_child(mode); break;
    case PROTECTED: case PROTECTED_COPY: protected_child(); break;
    case NOTIFICATION_COPY: notification_copy_child(); break;
    case FILTER_RACE: filter_race_child(); break;
    case COPY_SIGNAL_CONTROL: case COPY_SIGNAL_MASK: copy_signal_child(mode); break;
    case NOTIFY_SIGNAL_EINTR: case NOTIFY_SIGNAL_RESTART: notification_signal_child(mode); break;
    case KILL_CONTROL:
        application_filter(SYS_getppid, SECCOMP_RET_KILL_PROCESS);
        syscall(SYS_getppid); _exit(93);
    case TRACER_CONTROL: CHECK(syscall(SYS_getppid) == 555); break;
    }
}
static void protected_access(pid_t pid, pid_t first, int retained) {
    long value;
    errno = 0;
    CHECK(ptrace(PTRACE_PEEKDATA, pid, &marker, 0) == -1 && errno == EIO);
    errno = 0;
    CHECK(ptrace(PTRACE_POKEDATA, pid, &marker, 99) == -1 && errno == EIO);
    errno = 0;
    CHECK(remote_read(pid, (uintptr_t)&marker, &value, sizeof(value)) < 0 && errno == EPERM);
    errno = 0;
    CHECK(memory_fd(pid) < 0 && errno == EACCES);
    CHECK(pread(retained, &value, sizeof(value), (off_t)(uintptr_t)&marker) == sizeof(value) && value == 12346);
    printf("OBSERVED protected pid=%d %s: registers available; PEEK/POKE/readv/new mem denied; retained FD reads original mm\n",
        pid, pid == first ? "original" : "fork child");
}
struct counts {
    unsigned trace, notifications, signals, forks, execs, exits, copies, faults, entries, returns, async_signals;
    unsigned fd_transfers, handler_transfers, cancelled_notifications;
};
static void shield_copy(pid_t pid, struct copy_step *step) {
    uint64_t mask = UINT64_MAX;
    const int synchronous[] = {SIGKILL, SIGSTOP, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
    for (unsigned s = 0; s < sizeof(synchronous) / sizeof(*synchronous); ++s)
        mask &= ~(UINT64_C(1) << (synchronous[s] - 1));
    CHECK(!ptrace(PTRACE_GETSIGMASK, pid, sizeof(mask), &step->saved_mask));
    CHECK(!ptrace(PTRACE_SETSIGMASK, pid, sizeof(mask), &mask));
    step->masked = 1;
}
static void unshield_copy(pid_t pid, struct copy_step *step) {
    if (!step->masked) return;
    CHECK(!ptrace(PTRACE_SETSIGMASK, pid, sizeof(step->saved_mask), &step->saved_mask));
    step->masked = 0;
}
static void begin_copy(pid_t pid, struct user_pt_regs regs, enum mode mode) {
    unsigned i;
    for (i = 0; i < sizeof(copies) / sizeof(*copies); i++) if (!copies[i].pid) break;
    CHECK(i < sizeof(copies) / sizeof(*copies));
    copies[i] = (struct copy_step){.pid = pid, .saved = regs};
    if (mode == COPY_SIGNAL_MASK) shield_copy(pid, &copies[i]);
    memset((void *)(transfer + i * COPY_BYTES), 0, COPY_BYTES);
    regs.pc = (uintptr_t)md_hybrid_copy;
    regs.regs[0] = (uintptr_t)(transfer + i * COPY_BYTES);
    regs.regs[1] = (uintptr_t)&marker;
    regs.regs[2] = sizeof(marker);
    syscall_number(pid, -1);
    set_registers(pid, &regs);
    if (mode == COPY_SIGNAL_CONTROL || mode == COPY_SIGNAL_MASK) {
        copies[i].phase = COPY_STEP_INITIAL;
        CHECK(!ptrace(PTRACE_SINGLESTEP, pid, 0, 0));
    } else resume(pid, 0);
}
static void finish_copy(pid_t pid, pid_t first) {
    unsigned i;
    for (i = 0; i < sizeof(copies) / sizeof(*copies); i++) if (copies[i].pid == pid) break;
    CHECK(i < sizeof(copies) / sizeof(*copies));
    struct user_pt_regs regs = registers(pid);
    CHECK(regs.pc == (uintptr_t)md_hybrid_copy_done);
    long value; memcpy(&value, (const void *)(transfer + i * COPY_BYTES), sizeof(value));
    CHECK(value == (pid == first ? 12346 : 12347));
    regs = copies[i].saved;
    regs.regs[0] = 555;
    set_registers(pid, &regs);
    unshield_copy(pid, &copies[i]);
    copies[i].pid = 0;
    resume(pid, 0);
    printf("OBSERVED protected %s memory copied by guest instructions; dumpable unchanged\n",
        pid == first ? "original" : "fork child");
}
static int copy_slot(pid_t pid) {
    for (unsigned i = 0; i < sizeof(copies) / sizeof(*copies); i++) if (copies[i].pid == pid) return (int)i;
    return -1;
}
static void stop_for_notification(int listener, pid_t pid, enum mode mode) {
    int i = copy_slot(pid); CHECK(i >= 0 && copies[i].phase == NOTIFY_CANCEL);
    struct copy_step *step = &copies[i];
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &step->original.id) < 0 && errno == ENOENT);
    step->saved = registers(pid);
    /* Restart only the cancelled, side-effect-free notification at its exact
     * original SVC site. The kernel must check the application policy again. */
    step->saved.pc = step->original.data.instruction_pointer - 4;
    step->saved.regs[8] = step->original.data.nr;
    for (unsigned arg = 0; arg < 6; arg++) step->saved.regs[arg] = step->original.data.args[arg];
    struct user_pt_regs regs = step->saved;
    regs.pc = (uintptr_t)md_hybrid_string;
    regs.regs[0] = (uintptr_t)(transfer + i * COPY_BYTES);
    regs.regs[1] = step->original.data.args[1];
    regs.regs[2] = COPY_BYTES;
    if (notification_signal_mode(mode)) shield_copy(pid, step);
    syscall_number(pid, -1); set_registers(pid, &regs);
    if (step->inject_signal) {
        step->phase = COPY_STEP_INITIAL; CHECK(!ptrace(PTRACE_SINGLESTEP, pid, 0, 0));
    } else { step->phase = COPY_PATH; resume(pid, 0); }
}
static void step_copy_signal(pid_t pid, uintptr_t start, uintptr_t end, enum copy_phase next_phase) {
    int i = copy_slot(pid); CHECK(i >= 0);
    CHECK(copies[i].phase == COPY_STEP_INITIAL || copies[i].phase == COPY_STEP_ENTERED);
    struct user_pt_regs regs = registers(pid);
    /* ARM64 can first stop on the skipped syscall's completion, before
     * executing the first helper instruction. At most one extra step. */
    if (copies[i].phase == COPY_STEP_INITIAL && regs.pc == start) {
        copies[i].phase = COPY_STEP_ENTERED;
        CHECK(!ptrace(PTRACE_SINGLESTEP, pid, 0, 0)); return;
    }
    CHECK(regs.pc > start && regs.pc < end);
    copies[i].phase = next_phase;
    union sigval value = {.sival_int = 123};
    CHECK(!sigqueue(pid, SIGUSR1, value)); resume(pid, 0);
}
static void resume_notification(pid_t pid, enum mode mode) {
    int i = copy_slot(pid); CHECK(i >= 0 && copies[i].phase == COPY_PATH);
    struct user_pt_regs regs = registers(pid);
    CHECK(regs.pc == (uintptr_t)md_hybrid_string_done);
    CHECK(memchr((const void *)(transfer + i * COPY_BYTES), 0, COPY_BYTES));
    CHECK(!memcmp((const void *)(transfer + i * COPY_BYTES), "/hybrid-", 8));
    if (mode == FILTER_RACE) {
        char byte = 'f'; CHECK(write(control_pipe[1], &byte, 1) == 1);
        ready(ack_pipe[0]); CHECK(read(ack_pipe[0], &byte, 1) == 1 && byte == 'f');
    }
    set_registers(pid, &copies[i].saved);
    copies[i].phase = REPLAY_ENTER; follow_syscall(pid);
}
static void replay_stop(pid_t pid, struct counts *counts, enum mode mode) {
    int i = copy_slot(pid); CHECK(i >= 0);
    struct copy_step *step = &copies[i];
    struct user_pt_regs regs = registers(pid);
    if (step->phase == REPLAY_ENTER) {
        CHECK(regs.pc == step->original.data.instruction_pointer);
        CHECK(regs.regs[8] == (unsigned long)step->original.data.nr);
        for (unsigned arg = 0; arg < 6; arg++) CHECK(regs.regs[arg] == step->original.data.args[arg]);
        /* Restore the mask at syscall entry, not while returning from the
         * helper. The kernel owns EINTR/SA_RESTART and the signal frame. */
        unshield_copy(pid, step);
        step->phase = REPLAY_LEAVE; counts->entries++; follow_syscall(pid);
    } else {
        CHECK(step->phase == REPLAY_LEAVE || step->phase == REPLAY_COMMITTED);
        if (notification_signal_mode(mode) && step->inject_signal) {
            /* Linux internal ERESTARTSYS, before its signal restart decision. */
            CHECK(step->phase == REPLAY_LEAVE && (long)regs.regs[0] == -512);
            step->phase = REPLAY_SIGNAL; counts->returns++; resume(pid, 0); return;
        }
        if (mode == FILTER_RACE) CHECK(step->phase == REPLAY_LEAVE && (long)regs.regs[0] == -EACCES);
        else CHECK(step->phase == REPLAY_COMMITTED && (long)regs.regs[0] >= 0);
        step->pid = 0; counts->returns++; resume(pid, 0);
    }
}
static void notification_copy_fault(pid_t pid) {
    int i = copy_slot(pid); CHECK(i >= 0 && copies[i].phase == COPY_PATH);
    struct user_pt_regs regs = registers(pid);
    CHECK(regs.pc >= (uintptr_t)md_hybrid_string && regs.pc < (uintptr_t)md_hybrid_string_done);
    siginfo_t info; CHECK(!ptrace(PTRACE_GETSIGINFO, pid, 0, &info));
    CHECK(info.si_signo == SIGSEGV && info.si_addr == (void *)1);
    regs = copies[i].saved;
    regs.pc = copies[i].original.data.instruction_pointer;
    regs.regs[0] = (uint64_t)-EFAULT;
    set_registers(pid, &regs); copies[i].pid = 0;
    resume(pid, 0);
    puts("OBSERVED invalid guest pointer returns EFAULT without FD injection or guest signal delivery");
}
static void notification(int listener, struct counts *counts, enum mode mode, pid_t first) {
    struct seccomp_notif q = {0};
    int received = ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &q);
    if (received < 0 && errno == ENOENT && notification_signal_mode(mode)) {
        counts->cancelled_notifications++; return;
    }
    CHECK(!received);
    CHECK(q.data.arch == AUDIT_ARCH_AARCH64);
    int valid = ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id);
    if (valid < 0 && errno == ENOENT && notification_signal_mode(mode)) {
        counts->cancelled_notifications++; return;
    }
    CHECK(!valid);
    counts->notifications++;
    if (q.data.nr == SYS_getuid) {
        struct seccomp_notif_resp response = {.id = q.id, .val = 735};
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &response));
        return;
    }
    if (notification_copy_mode(mode)) {
        CHECK(q.data.nr == SYS_openat);
        int i = copy_slot(q.pid);
        if (i < 0) {
            char byte;
            errno = 0; CHECK(remote_read(q.pid, q.data.args[1], &byte, 1) < 0 && errno == EPERM);
            for (i = 0; i < (int)(sizeof(copies) / sizeof(*copies)); i++) if (!copies[i].pid) break;
            CHECK(i < (int)(sizeof(copies) / sizeof(*copies)));
            copies[i] = (struct copy_step){.pid = q.pid, .original = q, .phase = NOTIFY_CANCEL};
            copies[i].inject_signal = notification_signal_mode(mode) && !counts->async_signals;
            memset((void *)(transfer + i * COPY_BYTES), 0, COPY_BYTES);
            CHECK(!ptrace(PTRACE_INTERRUPT, q.pid, 0, 0));
            return;
        }
        struct copy_step *step = &copies[i];
        if (step->phase != REPLAY_LEAVE || q.id == step->original.id)
            fprintf(stderr, "NOTIFY unexpected pid=%u phase=%u id=%llu original=%llu pc=%#llx originalPC=%#llx\n",
                q.pid, step->phase, (unsigned long long)q.id, (unsigned long long)step->original.id,
                (unsigned long long)q.data.instruction_pointer, (unsigned long long)step->original.data.instruction_pointer);
        CHECK(step->phase == REPLAY_LEAVE && q.id != step->original.id);
        CHECK(!memcmp(&q.data, &step->original.data, sizeof(q.data)));
        if (step->inject_signal) return; /* Pending guest signal cancels this ID; no external work. */
        const char *expected = q.pid == (unsigned)first ? "/hybrid-zero" : "/hybrid-fork";
        int handler = 0;
        if (notification_signal_mode(mode)) {
            CHECK(counts->async_signals == 1);
            handler = !counts->handler_transfers;
            expected = handler ? "/hybrid-handler" : "/hybrid-next";
        }
        CHECK(!memcmp((const void *)(transfer + i * COPY_BYTES), expected, strlen(expected) + 1));
        int fd = open("/dev/zero", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
        struct seccomp_notif_addfd add = {.id = q.id, .srcfd = fd,
            .flags = SECCOMP_ADDFD_FLAG_SEND, .newfd_flags = O_CLOEXEC};
        CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0); close(fd);
        counts->fd_transfers++;
        counts->handler_transfers += handler;
        step->phase = REPLAY_COMMITTED;
        printf("OBSERVED protected notification replay: same PC/arguments, fresh ID, one FD transfer (%s)\n", expected);
        return;
    }
    CHECK(q.data.nr == SYS_openat);
    char path[sizeof("/hybrid-zero")];
    CHECK(remote_read(q.pid, q.data.args[1], path, sizeof(path)) == sizeof(path));
    CHECK(!memcmp(path, "/hybrid-zero", sizeof(path)));
    int fd = open("/dev/zero", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
    struct seccomp_notif_addfd add = {.id = q.id, .srcfd = fd,
        .flags = SECCOMP_ADDFD_FLAG_SEND, .newfd_flags = O_CLOEXEC};
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0); close(fd);
    counts->fd_transfers++;
}
static void second_tracer(pid_t target) {
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        group = 0; errno = 0;
        _exit(ptrace(PTRACE_SEIZE, target, 0, 0) == -1 && errno == EPERM ? 0 : 94);
    }
    int status;
    CHECK(trace_event(&status, md_event_now() + 5000000000LL) == child);
    CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
    puts("OBSERVED LIMIT a second tracer cannot attach to the already traced process");
}
static void run(enum mode mode, const char *name) {
    stage = name;
    if (mode == FILTER_RACE) CHECK(!pipe2(control_pipe, O_CLOEXEC) && !pipe2(ack_pipe, O_CLOEXEC));
    int channel[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel));
    pid_t parent = getpid(), child = fork(); CHECK(child >= 0);
    if (!child) {
        group = 0; member_count = 0; close(channel[0]); close(signal_fd);
        if (mode == FILTER_RACE) { close(control_pipe[1]); close(ack_pipe[0]); }
        CHECK(!sigprocmask(SIG_SETMASK, &original_mask, NULL));
        CHECK(!setpgid(0, 0) && !prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == parent);
        errno = 0;
        int ptracer = prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);
        CHECK(!ptracer || errno == EINVAL);
        char byte = 'r';
        CHECK(write(channel[1], &byte, 1) == 1);
        ready(channel[1]); CHECK(read(channel[1], &byte, 1) == 1 && byte == 'g');
        child_main(mode, channel[1]); _exit(0);
    }
    group = child; member_count = 0; member(child); close(channel[1]);
    if (mode == FILTER_RACE) { close(control_pipe[0]); close(ack_pipe[1]); }
    char byte;
    ready(channel[0]); CHECK(read(channel[0], &byte, 1) == 1 && byte == 'r');
    CHECK(!ptrace(PTRACE_SEIZE, child, 0, PTRACE_O_TRACESECCOMP | PTRACE_O_TRACEEXEC
        | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE | PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD));
    CHECK(!ptrace(PTRACE_INTERRUPT, child, 0, 0));
    int status;
    CHECK(trace_event(&status, md_event_now() + 5000000000LL) == child);
    CHECK(WIFSTOPPED(status) && (unsigned)status >> 16 == PTRACE_EVENT_STOP);
    int retained = memory_fd(child); CHECK(retained >= 0);
    long value;
    CHECK(pread(retained, &value, sizeof(value), (off_t)(uintptr_t)&marker) == sizeof(value) && value == marker);
    if (mode == TRACER_CONTROL) second_tracer(child);
    byte = 'g'; CHECK(write(channel[0], &byte, 1) == 1);
    resume(child, 0);
    int listener = take_listener(channel[0]);
    struct counts counts = {0};
    int64_t deadline = md_event_now() + 10000000000LL;
    while (living()) {
        pid_t pid = waitpid(-1, &status, __WALL | WNOHANG);
        if (pid < 0 && errno == EINTR) continue;
        CHECK(pid >= 0);
        if (!pid) {
            struct pollfd events[] = {{signal_fd, POLLIN, 0}, {listener, POLLIN, 0}};
            /* EVENT_WAIT: ptrace SIGCHLD or seccomp notification. Expiry fails
             * and cancels the owned group; it is not a settling delay. */
            CHECK(md_event_wait(events, 2, deadline) >= 0);
            if (events[0].revents) drain_signals();
            if (events[1].revents & POLLIN) notification(listener, &counts, mode, child);
            if (events[1].revents & POLLHUP) {
                close(listener); listener = -1;
            }
            continue;
        }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            if (mode == KILL_CONTROL) CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS);
            else CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
            forget(pid); counts.exits++; continue;
        }
        CHECK(WIFSTOPPED(status)); member(pid);
        unsigned event = (unsigned)status >> 16;
        int signal = WSTOPSIG(status);
        if (event == PTRACE_EVENT_SECCOMP) {
            unsigned long cookie; CHECK(!ptrace(PTRACE_GETEVENTMSG, pid, 0, &cookie));
            struct user_pt_regs regs = registers(pid); counts.trace++;
            if (cookie == 41) {
                CHECK(regs.regs[8] == SYS_getppid);
                if (mode == PROTECTED || mode == PROTECTED_COPY || mode == COPY_SIGNAL_CONTROL || mode == COPY_SIGNAL_MASK)
                    protected_access(pid, child, retained);
                if (mode == PROTECTED_COPY || mode == COPY_SIGNAL_CONTROL || mode == COPY_SIGNAL_MASK) {
                    begin_copy(pid, regs, mode); continue;
                }
                syscall_number(pid, -1); regs.regs[0] = 555; set_registers(pid, &regs);
            } else {
                unsigned argument = mode == EXEC_RECHECK_AT ? 1 : 0;
                CHECK(cookie == 42 && regs.regs[8] == (unsigned long)(argument ? SYS_execveat : SYS_execve) && pid == child);
                CHECK(regs.regs[argument] == (uintptr_t)exec_source);
                if (mode == PROTECTED_EXEC) protected_access(pid, child, retained);
                if (mode == EXEC_REPLAY_RECHECK) {
                    syscall_number(pid, -1);
                    regs.pc -= 4;
                }
                regs.regs[argument] = (uintptr_t)exec_target; set_registers(pid, &regs);
            }
            resume(pid, 0);
        } else if (event == PTRACE_EVENT_EXEC) {
            counts.execs++;
            CHECK(pread(retained, &value, sizeof(value), (off_t)(uintptr_t)&marker) == 0);
            int memory = memory_fd(pid); CHECK(memory >= 0); close(memory);
            resume(pid, 0);
        } else if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
            unsigned long id; CHECK(!ptrace(PTRACE_GETEVENTMSG, pid, 0, &id));
            member(id); counts.forks++; resume(pid, 0);
        } else if (!event && signal == SIGTRAP && mode == PROTECTED_COPY) {
            finish_copy(pid, child); counts.copies++;
        } else if (!event && signal == SIGTRAP && (mode == COPY_SIGNAL_CONTROL || mode == COPY_SIGNAL_MASK)) {
            int i = copy_slot(pid); CHECK(i >= 0);
            if (copies[i].phase == COPY_STEP_INITIAL || copies[i].phase == COPY_STEP_ENTERED) {
                step_copy_signal(pid, (uintptr_t)md_hybrid_copy, (uintptr_t)md_hybrid_copy_done, COPY_AWAIT_BREAK);
            } else { CHECK(copies[i].phase == COPY_AWAIT_BREAK); finish_copy(pid, child); counts.copies++; }
        } else if (!event && signal == SIGTRAP && notification_copy_mode(mode)) {
            int i = copy_slot(pid); CHECK(i >= 0);
            if (copies[i].phase == COPY_STEP_INITIAL || copies[i].phase == COPY_STEP_ENTERED)
                step_copy_signal(pid, (uintptr_t)md_hybrid_string, (uintptr_t)md_hybrid_string_done, COPY_PATH);
            else { resume_notification(pid, mode); counts.copies++; }
        } else if (!event && signal == (SIGTRAP | 0x80)) {
            replay_stop(pid, &counts, mode);
        } else if (event == PTRACE_EVENT_STOP) {
            if (notification_copy_mode(mode) && copy_slot(pid) >= 0)
                stop_for_notification(listener, pid, mode);
            else resume(pid, 0); /* Automatic initial stop of a newly traced child. */
        } else if (!event && signal == SIGSEGV && mode == NOTIFICATION_COPY) {
            notification_copy_fault(pid); counts.faults++;
        } else if (!event && signal == SIGSTOP) resume(pid, 0);
        else if (!event && signal == SIGSYS) { counts.signals++; resume(pid, SIGSYS); }
        else if (!event && signal == SIGUSR1 && (mode == COPY_SIGNAL_CONTROL || mode == COPY_SIGNAL_MASK)) {
            if (mode == COPY_SIGNAL_MASK) CHECK(copy_slot(pid) < 0 && counts.copies == 1);
            counts.async_signals++; resume(pid, SIGUSR1);
        } else if (!event && signal == SIGUSR1 && notification_signal_mode(mode)) {
            int i = copy_slot(pid); CHECK(i >= 0 && copies[i].phase == REPLAY_SIGNAL && !counts.async_signals);
            /* No external work was committed. Retire this operation before
             * the handler: nested calls and kernel restart own fresh requests. */
            copies[i].pid = 0;
            counts.async_signals++; resume(pid, SIGUSR1);
        }
        else if (!event && signal == SIGCHLD) resume(pid, SIGCHLD);
        else CHECK(0);
    }
    close(retained); if (listener >= 0) close(listener); group = 0;
    if (mode == FILTER_RACE) { close(control_pipe[1]); close(ack_pipe[0]); }
    printf("COUNTS %s: trace=%u notifications=%u guestSIGSYS=%u fork=%u exec=%u exits=%u copies=%u faults=%u entries=%u returns=%u async=%u fds=%u cancelled=%u\n",
        name, counts.trace, counts.notifications, counts.signals, counts.forks, counts.execs, counts.exits,
        counts.copies, counts.faults, counts.entries, counts.returns, counts.async_signals,
        counts.fd_transfers, counts.cancelled_notifications);
    for (unsigned i = 0; i < sizeof(copies) / sizeof(*copies); i++) CHECK(!copies[i].pid);
    if (mode == MIXED)
        CHECK(counts.trace == 1 && counts.notifications == 3 && counts.signals == 2 && counts.fd_transfers == 1);
    if (mode == EXEC_REDIRECT) CHECK(counts.trace == 2 && counts.execs == 1);
    if (mode == PROTECTED_EXEC) CHECK(counts.trace == 2 && counts.execs == 1);
    if (mode == EXEC_ARG0_LIMIT) {
        CHECK(counts.trace == 2 && counts.execs == 1);
        puts("OBSERVED LIMIT ARM64 arg0 replay uses orig_x0 for seccomp while execution uses modified x0");
    }
    if (mode == EXEC_RECHECK_AT) CHECK(counts.trace == 1 && !counts.execs);
    if (mode == EXEC_REPLAY_RECHECK) CHECK(counts.trace == 1 && !counts.execs);
    if (mode == PROTECTED || mode == PROTECTED_COPY)
        CHECK(counts.trace == 2 && counts.forks == 1 && counts.exits == 2);
    if (mode == PROTECTED_COPY) CHECK(counts.copies == 2);
    if (mode == NOTIFICATION_COPY)
        CHECK(!counts.trace && counts.notifications == 5 && counts.copies == 2
            && counts.faults == 1 && counts.forks == 1 && counts.exits == 2 && counts.entries == 2 && counts.returns == 2);
    if (mode == FILTER_RACE)
        CHECK(!counts.trace && counts.notifications == 1 && counts.copies == 1 && !counts.faults
            && counts.forks == 1 && counts.exits == 2 && counts.entries == 1 && counts.returns == 1);
    if (mode == COPY_SIGNAL_CONTROL || mode == COPY_SIGNAL_MASK) {
        CHECK(counts.trace == 1 && counts.copies == 1 && counts.async_signals == 1 && counts.notifications == 1);
        puts(mode == COPY_SIGNAL_CONTROL
            ? "OBSERVED LIMIT unshielded async signal exposes copy-helper PC to the application handler"
            : "OBSERVED masked copy retains pending signal, siginfo, original PC/result/mask and application altstack");
    }
    if (notification_signal_mode(mode)) {
        unsigned operations = mode == NOTIFY_SIGNAL_RESTART ? 3 : 2;
        CHECK(!counts.trace && counts.copies == operations && counts.entries == operations
            && counts.returns == operations && counts.async_signals == 1 && counts.fd_transfers == operations - 1
            && counts.handler_transfers == 1);
        puts(mode == NOTIFY_SIGNAL_RESTART
            ? "OBSERVED kernel SA_RESTART, nested handler open and changed pathname recopy; one FD per completed operation"
            : "OBSERVED kernel EINTR with original signal context and nested handler open; interrupted operation transfers no FD");
    }
    if (mode == KILL_CONTROL) CHECK(!counts.trace && !counts.notifications);
    puts("OBSERVED boundary completed (not browser certification)");
}
int main(int argc, char **argv) {
    stage = "identity"; setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 2 && !strcmp(argv[1], "--after-exec")) {
        CHECK(syscall(SYS_getppid) == 555);
        return 0;
    }
    CHECK(argc == 1 && getuid() == 2000 && geteuid() == 2000);
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGCHLD);
    CHECK(!sigprocmask(SIG_BLOCK, &mask, &original_mask));
    signal_fd = signalfd(-1, &mask, SFD_CLOEXEC | SFD_NONBLOCK); CHECK(signal_fd >= 0);
    transfer = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    CHECK(transfer != MAP_FAILED);
    CHECK(!atexit(cleanup));
    run(MIXED, "TRACE/USER_NOTIF, application ERRNO/TRAP, direct data IO");
    run(EXEC_REDIRECT, "register-only exec redirect and image replacement");
    run(EXEC_ARG0_LIMIT, "ARM64 first-argument recheck boundary");
    run(EXEC_RECHECK_AT, "kernel rechecks modified execveat path argument");
    run(EXEC_REPLAY_RECHECK, "same-site fresh SVC rechecks modified arg0");
    run(PROTECTED_EXEC, "protected exec redirect without changing dumpability");
    run(PROTECTED, "tracer attached before dumpable=0, protected fork");
    run(PROTECTED_COPY, "protected-memory transfer through guest instructions");
    run(NOTIFICATION_COPY, "protected notification, bounded copy, same-site replay and ADDFD");
    run(FILTER_RACE, "concurrent TSYNC denial during protected pathname copy");
    run(COPY_SIGNAL_CONTROL, "application signal inside guest-side copy (negative control)");
    run(COPY_SIGNAL_MASK, "signal mask shields copy and restores application context");
    run(NOTIFY_SIGNAL_EINTR, "signal during notified copy preserves kernel EINTR");
    run(NOTIFY_SIGNAL_RESTART, "signal during notified copy preserves kernel SA_RESTART");
    run(KILL_CONTROL, "application KILL outranks TRACE");
    run(TRACER_CONTROL, "single tracer ownership");
    close(signal_fd);
    CHECK(!munmap((void *)transfer, 4096));
    CHECK(!sigprocmask(SIG_SETMASK, &original_mask, NULL));
    puts("Hybrid boundary observations complete; sandbox support NOT established");
    return 0;
}
