#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* Native kernel controls, not the guest adapter. Every filter belongs to an
 * owned child; the launcher keeps its original signals, credentials and filters. */
static pid_t owned;
static const char *stage;
static void cleanup(void) {
    if (owned <= 0) return;
    kill(-owned, SIGKILL);
    kill(owned, SIGKILL);
    while (waitpid(owned, NULL, 0) < 0 && errno == EINTR) { }
    owned = 0;
}
static void failed(const char *expression, unsigned line) {
    fprintf(stderr, "FAIL %s line=%u: %s errno=%d\n", stage, line, expression, errno);
    cleanup();
    exit(1);
}
#define CHECK(x) do { if (!(x)) failed(#x, __LINE__); } while (0)
static void ready(int fd) {
    /* EVENT_WAIT: exact channel/notification/process event; a deadline fails
     * the fixture and kills only its owned process group. */
    CHECK(md_event_wait_fd(fd, POLLIN, md_event_now() + 5000000000LL) >= 0);
}
static void send_bytes(int fd, const void *data, size_t size) {
    CHECK(send(fd, data, size, MSG_NOSIGNAL) == (ssize_t)size);
}
static void receive_bytes(int fd, void *data, size_t size) {
    ready(fd);
    CHECK(recv(fd, data, size, MSG_TRUNC) == (ssize_t)size);
}
static void ack(int fd) { const char byte = 'a'; send_bytes(fd, &byte, 1); }
static void await_ack(int fd) { char byte; receive_bytes(fd, &byte, 1); CHECK(byte == 'a'); }
struct hello { uintptr_t address; int source; pid_t pid; int error; };
static void send_fd(int channel, int fd, const struct hello *value) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct iovec iov = {(void *)value, sizeof(*value)};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
    if (fd >= 0) {
        message.msg_control = control.bytes; message.msg_controllen = sizeof(control);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS;
        cmsg->cmsg_len = CMSG_LEN(sizeof(fd));
        memcpy(CMSG_DATA(cmsg), &fd, sizeof(fd));
    }
    CHECK(sendmsg(channel, &message, MSG_NOSIGNAL) == sizeof(*value));
}
static int receive_fd(int channel, struct hello *value) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct iovec iov = {value, sizeof(*value)};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ready(channel);
    CHECK(recvmsg(channel, &message, MSG_CMSG_CLOEXEC | MSG_TRUNC) == sizeof(*value));
    CHECK(!(message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)));
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
    if (value->error) { CHECK(!cmsg); return -1; }
    CHECK(cmsg && cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_RIGHTS
        && cmsg->cmsg_len == CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&message, cmsg));
    int fd; memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    CHECK(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
    return fd;
}
static int filter(const int *numbers, unsigned count) {
    struct sock_filter rules[48] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    };
    CHECK(count <= 20);
    unsigned n = 4;
    for (unsigned i = 0; i < count; i++) {
        rules[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, numbers[i], 0, 1);
        rules[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF);
    }
    rules[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    struct sock_fprog program = {n, rules};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    int listener = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
    CHECK(listener >= 0);
    return listener;
}
static struct seccomp_notif request(int listener, int number) {
    ready(listener);
    struct seccomp_notif q = {0};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &q));
    CHECK(q.data.arch == AUDIT_ARCH_AARCH64 && q.data.nr == number && q.pid);
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id));
    return q;
}
static void reply(int listener, uint64_t id, long value, int error, unsigned flags) {
    struct seccomp_notif_resp out = {.id = id, .val = value, .error = -error, .flags = flags};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &out));
}
static void inject_zero(int listener, uint64_t id) {
    int fd = open("/dev/zero", O_RDONLY | O_CLOEXEC); CHECK(fd >= 0);
    struct seccomp_notif_addfd add = {.id = id, .srcfd = fd,
        .flags = SECCOMP_ADDFD_FLAG_SEND, .newfd_flags = O_CLOEXEC};
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0);
    CHECK(!close(fd));
}
static void read_zero(int fd) {
    CHECK(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
    char value = 1; CHECK(read(fd, &value, 1) == 1 && !value);
    CHECK(!close(fd));
}
struct child { int channel, pidfd; pid_t pid; };
static struct child start(void (*run)(int)) {
    int channel[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel));
    pid_t parent = getpid(), pid = fork(); CHECK(pid >= 0);
    if (!pid) {
        owned = 0;
        close(channel[0]);
        CHECK(!setpgid(0, 0));
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL));
        CHECK(getppid() == parent);
        run(channel[1]);
        _exit(0);
    }
    owned = pid;
    close(channel[1]);
    int pidfd = syscall(SYS_pidfd_open, pid, 0); CHECK(pidfd >= 0);
    return (struct child){channel[0], pidfd, pid};
}
static void finish(struct child c) {
    ready(c.pidfd);
    int status;
    CHECK(waitpid(c.pid, &status, 0) == c.pid);
    CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
    owned = 0;
    close(c.pidfd); close(c.channel);
}
static void publish_listener(int channel, int listener, uintptr_t address, int source) {
    struct hello h = {.address = address, .source = source, .pid = getpid()};
    send_fd(channel, listener, &h);
    close(listener);
    await_ack(channel);
}
static int proc_memory(pid_t pid) {
    char path[64]; snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    return open(path, O_RDWR | O_CLOEXEC);
}
static int read_remote(pid_t pid, uintptr_t address, unsigned long *value) {
    struct iovec local = {value, sizeof(*value)}, remote = {(void *)address, sizeof(*value)};
    long n = syscall(SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0);
    return n == sizeof(*value) ? 0 : n < 0 ? errno : EIO;
}
static void memory_value(int fd, uintptr_t address, unsigned long expected) {
    unsigned long value = 0;
    CHECK(pread(fd, &value, sizeof(value), (off_t)address) == sizeof(value) && value == expected);
}
static unsigned long marker = 0x123456;
static void protected_child(int channel) {
    int source = open("/dev/zero", O_RDONLY | O_CLOEXEC); CHECK(source >= 0);
    int retained = open("/proc/self/mem", O_RDWR | O_CLOEXEC); CHECK(retained >= 0);
    const int numbers[] = {SYS_getppid};
    publish_listener(channel, filter(numbers, 1), (uintptr_t)&marker, source);
    CHECK(!prctl(PR_SET_DUMPABLE, 0));
    marker++;
    unsigned long self_value;
    CHECK(!read_remote(getpid(), (uintptr_t)&marker, &self_value) && self_value == marker);
    read_zero(syscall(SYS_getppid));
    /* Test cooperation, including a real denial. Do not change dumpable to
     * manufacture a memory capability the application did not export. */
    int memory = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
    struct hello h = {.address = (uintptr_t)&marker, .source = source,
        .pid = getpid(), .error = memory < 0 ? errno : 0};
    send_fd(channel, memory, &h); if (memory >= 0) close(memory);
    await_ack(channel);
    h.error = 0;
    send_fd(channel, source, &h);
    await_ack(channel);
    pid_t descendant = fork(); CHECK(descendant >= 0);
    if (!descendant) {
        marker++;
        CHECK(!prctl(PR_GET_DUMPABLE));
        memory_value(retained, (uintptr_t)&marker, marker - 1);
        memory = open("/proc/self/mem", O_RDWR | O_CLOEXEC);
        h.pid = getpid(); h.error = memory < 0 ? errno : 0;
        send_fd(channel, memory, &h); if (memory >= 0) close(memory);
        read_zero(syscall(SYS_getppid));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: owned descendant exit; the fixture's parent deadline kills
     * this entire owned group if it cannot finish. */
    CHECK(waitpid(descendant, &status, 0) == descendant);
    CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
    close(source); close(retained);
}
static void test_protected(void) {
    stage = "protected process and descendant";
    struct child c = start(protected_child);
    struct hello h;
    int listener = receive_fd(c.channel, &h);
    CHECK(h.pid == c.pid);
    int memory = proc_memory(c.pid); CHECK(memory >= 0);
    memory_value(memory, h.address, marker);
    unsigned long value;
    CHECK(!read_remote(c.pid, h.address, &value) && value == marker);
    int duplicate = syscall(SYS_pidfd_getfd, c.pidfd, h.source, 0); CHECK(duplicate >= 0);
    close(duplicate);
    ack(c.channel);
    struct seccomp_notif q = request(listener, SYS_getppid);
    CHECK(q.pid == (unsigned)c.pid);
    int read_error = read_remote(c.pid, h.address, &value);
    errno = 0;
    duplicate = syscall(SYS_pidfd_getfd, c.pidfd, h.source, 0);
    int fd_error = duplicate < 0 ? errno : 0;
    if (duplicate >= 0) close(duplicate);
    CHECK(read_error == EPERM && fd_error == EPERM);
    memory_value(memory, h.address, marker + 1);
    inject_zero(listener, q.id);
    int exported = receive_fd(c.channel, &h);
    CHECK(exported < 0 && h.error == EACCES);
    ack(c.channel);
    exported = receive_fd(c.channel, &h);
    CHECK(exported >= 0 && !h.error);
    read_zero(exported);
    ack(c.channel);
    exported = receive_fd(c.channel, &h);
    CHECK(h.pid != c.pid && exported < 0 && h.error == EACCES);
    q = request(listener, SYS_getppid); CHECK(q.pid == (unsigned)h.pid);
    CHECK(read_remote(h.pid, h.address, &value) == EPERM);
    errno = 0;
    int fresh = proc_memory(h.pid); CHECK(fresh < 0 && errno == EACCES);
    memory_value(memory, h.address, marker + 1);
    inject_zero(listener, q.id);
    close(memory); close(listener);
    finish(c);
    puts("PASS protected boundary: self read, retained memory, explicit SCM_RIGHTS and ADDFD work; remote read/getfd and fresh self mem denied; inherited mem refers to parent, not fork child");
}

static volatile sig_atomic_t caught;
static void interrupted(int signal) { if (signal == SIGUSR1) caught++; }
static int descriptor_count(void) {
    int n = 0;
    for (int fd = 0; fd < 128; fd++) if (fcntl(fd, F_GETFD) >= 0) n++;
    return n;
}
static void cancelled_child(int channel) {
    struct sigaction action = {.sa_handler = interrupted}; sigemptyset(&action.sa_mask);
    CHECK(!sigaction(SIGUSR1, &action, NULL));
    const int numbers[] = {SYS_openat};
    publish_listener(channel, filter(numbers, 1), 0, 0);
    int before = descriptor_count();
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, "/notification-cancel", O_RDONLY, 0) == -1 && errno == EINTR);
    CHECK(caught == 1);
    ack(channel); await_ack(channel);
    CHECK(descriptor_count() == before);
    read_zero(syscall(SYS_openat, AT_FDCWD, "/notification-next", O_RDONLY | O_CLOEXEC, 0));
    CHECK(descriptor_count() == before);
}
static void test_cancelled(void) {
    stage = "cancelled notification";
    struct child c = start(cancelled_child); struct hello h;
    int listener = receive_fd(c.channel, &h); ack(c.channel);
    struct seccomp_notif q = request(listener, SYS_openat);
    CHECK(!kill(c.pid, SIGUSR1)); await_ack(c.channel);
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id) < 0 && errno == ENOENT);
    int source = open("/dev/zero", O_RDONLY | O_CLOEXEC); CHECK(source >= 0);
    struct seccomp_notif_addfd add = {.id = q.id, .srcfd = source,
        .flags = SECCOMP_ADDFD_FLAG_SEND, .newfd_flags = O_CLOEXEC};
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) < 0 && errno == ENOENT);
    close(source);
    struct seccomp_notif_resp out = {.id = q.id, .val = 99};
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &out) < 0 && errno == ENOENT);
    ack(c.channel);
    struct seccomp_notif next = request(listener, SYS_openat); CHECK(next.id != q.id);
    inject_zero(listener, next.id);
    close(listener); finish(c);
    puts("PASS cancellation: EINTR, stale ID/reply/ADDFD rejected, no descriptor leak or request replay");
}

static char signal_stack[65536];
static void sandbox_handler(int signal, siginfo_t *info, void *context) {
    char local;
    if (signal != SIGSYS || (uintptr_t)&local < (uintptr_t)signal_stack
            || (uintptr_t)&local >= (uintptr_t)signal_stack + sizeof(signal_stack)) _exit(110);
    caught++;
    if (info->si_code > 0) {
        if (info->si_syscall != SYS_getppid) _exit(111);
        ((ucontext_t *)context)->uc_mcontext.regs[0] = 73;
    }
}
static void sandbox_setup(void) {
    stack_t stack = {.ss_sp = signal_stack, .ss_size = sizeof(signal_stack)};
    CHECK(!sigaltstack(&stack, NULL));
    struct sigaction action = {.sa_sigaction = sandbox_handler, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGSYS, &action, NULL));
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getpgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program));
}
static void *notification_thread(void *unused) {
    (void)unused;
    CHECK(syscall(SYS_getuid) == 811);
    return NULL;
}
static void sandbox_child(int channel) {
    const int numbers[] = {SYS_getppid, SYS_getpgid, SYS_getuid};
    publish_listener(channel, filter(numbers, 3), 0, 0);
    sandbox_setup();
    sigset_t set, pending; sigemptyset(&set); sigaddset(&set, SIGSYS);
    CHECK(!sigprocmask(SIG_BLOCK, &set, NULL));
    CHECK(!raise(SIGSYS) && !caught);
    CHECK(!sigpending(&pending) && sigismember(&pending, SIGSYS));
    CHECK(!sigprocmask(SIG_UNBLOCK, &set, NULL) && caught == 1);
    CHECK(syscall(SYS_getppid) == 73 && caught == 2);
    errno = 0; CHECK(syscall(SYS_getpgid, 0) == -1 && errno == EACCES);
    pthread_t thread; CHECK(!pthread_create(&thread, NULL, notification_thread, NULL));
    CHECK(!pthread_join(thread, NULL));
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) { CHECK(syscall(SYS_getppid) == 73); CHECK(syscall(SYS_getuid) == 811); _exit(0); }
    int status;
    /* EVENT_WAIT: native fork child; the enclosing fixture owns the deadline. */
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    stack_t current;
    CHECK(!sigaltstack(NULL, &current) && current.ss_sp == signal_stack && !current.ss_flags);
}
static void test_sandbox(void) {
    stage = "native signals and application filter";
    struct child c = start(sandbox_child); struct hello h;
    int listener = receive_fd(c.channel, &h); ack(c.channel);
    unsigned pids[2];
    for (unsigned i = 0; i < 2; i++) {
        struct seccomp_notif q = request(listener, SYS_getuid);
        pids[i] = q.pid; CHECK(q.pid != (unsigned)c.pid);
        reply(listener, q.id, 811, 0, 0);
    }
    CHECK(pids[0] != pids[1]);
    finish(c); close(listener);
    puts("PASS native signals: altstack, SIGSYS masks/pending/TRAP, guest ERRNO/TSYNC priority, fork and pthread notification IDs");
}

static void exec_child(int channel) {
    const int numbers[] = {SYS_execve, SYS_execveat, SYS_getppid};
    publish_listener(channel, filter(numbers, 3), (uintptr_t)&marker, 0);
    CHECK(!syscall(SYS_close_range, 3U, ~0U, 0));
    char *const arguments[] = {"md-notification-lifecycle-test", "--post-exec", NULL};
    char *const environment[] = {NULL};
    /* The first request is deliberately answered with zero: no image replacement
     * has happened. Only CONTINUE on the second request performs a real exec. */
    CHECK(syscall(SYS_execve, "/proc/self/exe", arguments, environment) == 0);
    CHECK(syscall(SYS_getppid) == 812 && marker == 0x123456);
    marker++;
    errno = 0;
    CHECK(syscall(SYS_execve, "/no-such-notification-exec", arguments, environment) == -1 && errno == ENOENT);
    CHECK(syscall(SYS_getppid) == 814);
    syscall(SYS_execve, "/proc/self/exe", arguments, environment);
    _exit(112);
}
static int post_exec(void) {
    CHECK(syscall(SYS_getppid) == 813);
    CHECK(descriptor_count() == 3);
    return 0;
}
static void test_exec(void) {
    stage = "exec and listener lifetime";
    struct child c = start(exec_child); struct hello h;
    int listener = receive_fd(c.channel, &h);
    int old_memory = proc_memory(c.pid); CHECK(old_memory >= 0);
    ack(c.channel);
    struct seccomp_notif q = request(listener, SYS_execve); CHECK(q.pid == (unsigned)c.pid);
    reply(listener, q.id, 0, 0, 0);
    q = request(listener, SYS_getppid); CHECK(q.pid == (unsigned)c.pid);
    memory_value(old_memory, h.address, marker);
    reply(listener, q.id, 812, 0, 0);
    q = request(listener, SYS_execve); CHECK(q.pid == (unsigned)c.pid);
    reply(listener, q.id, 0, 0, SECCOMP_USER_NOTIF_FLAG_CONTINUE);
    q = request(listener, SYS_getppid); CHECK(q.pid == (unsigned)c.pid);
    memory_value(old_memory, h.address, marker + 1);
    reply(listener, q.id, 814, 0, 0);
    q = request(listener, SYS_execve); CHECK(q.pid == (unsigned)c.pid);
    reply(listener, q.id, 0, 0, SECCOMP_USER_NOTIF_FLAG_CONTINUE);
    q = request(listener, SYS_getppid); CHECK(q.pid == (unsigned)c.pid);
    unsigned long value;
    CHECK(pread(old_memory, &value, sizeof(value), (off_t)h.address) == 0);
    int new_memory = proc_memory(c.pid); CHECK(new_memory >= 0);
    memory_value(new_memory, h.address, marker);
    reply(listener, q.id, 813, 0, 0);
    close(new_memory); close(old_memory); close(listener); finish(c);
    puts("PASS exec: fake success does not exec; failed exec retains image; CONTINUE preserves PID/filter after close_range; old mem FD cannot access new image");
}
static void orphaned_child(int channel) {
    const int numbers[] = {SYS_getppid};
    publish_listener(channel, filter(numbers, 1), 0, 0);
    errno = 0;
    CHECK(syscall(SYS_getppid) == -1 && errno == ENOSYS);
}
static void test_listener_loss(void) {
    stage = "listener loss";
    struct child c = start(orphaned_child); struct hello h;
    int listener = receive_fd(c.channel, &h); close(listener);
    ack(c.channel); finish(c);
    puts("PASS listener loss: explicit ENOSYS instead of unmediated execution");
}
int main(int argc, char **argv) {
    stage = "startup";
    setbuf(stdout, NULL);
    if (argc == 2 && !strcmp(argv[1], "--post-exec")) return post_exec();
    CHECK(argc == 1 && getuid() == 2000 && geteuid() == 2000);
    CHECK(!atexit(cleanup));
    test_protected();
    test_cancelled();
    test_sandbox();
    test_exec();
    test_listener_loss();
    puts("PASS notification lifecycle controls (no tracer, no guest runtime)");
    return 0;
}
