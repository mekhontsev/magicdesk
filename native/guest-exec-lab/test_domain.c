#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/openat2.h>
#include <linux/seccomp.h>
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
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* A closed, read-only syscall subset, not an ELF runtime or virtual chroot.
 * No guest RPC, instruction-pointer exception or successful fake namespace. */
enum mode { CONFINED, INHERITED, PROTECTED, LISTENER_LOSS, KILL_POLICY, SHADOW_LISTENER };
enum { CHILDREN = 7, PATH_BYTES = 4096 };
struct domain {
    pid_t pid;
    int listener, root;
    enum mode mode;
    unsigned requests, admitted, denied, unreadable;
};
static struct domain domains[CHILDREN];
static pid_t owner;
static int child_index = -1;
static unsigned checks;
static char host_secret[PATH_BYTES];
static struct seccomp_notif *request;
static size_t request_size;
static long deadline;

static void cleanup(void) {
    if (getpid() != owner) return;
    for (unsigned i = 0; i < CHILDREN; ++i)
        if (domains[i].pid > 0) kill(domains[i].pid, SIGKILL);
    /* EVENT_WAIT: reap only owned children; the runner bounds cancellation. */
    for (unsigned i = 0; i < CHILDREN; ++i)
        if (domains[i].pid > 0)
            while (waitpid(domains[i].pid, NULL, 0) < 0 && errno == EINTR) { }
}
static void failed(const char *expression, unsigned line) {
    dprintf(2, "FAIL domain=%d line=%u expression=%s errno=%d\n",
        child_index, line, expression, errno);
    cleanup();
    _exit(1);
}
#define CHECK(x) do { if (!(x)) failed(#x, __LINE__); } while (0)
static void ready(int fd) {
    /* EVENT_WAIT: bootstrap/listener or child exit; deadline is failure. */
    CHECK(md_event_wait_fd(fd, POLLIN, deadline) >= 0);
}
static int install(struct sock_filter *rules, unsigned count, unsigned flags) {
    struct sock_fprog program = {count, rules};
    int result = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, flags, &program);
    CHECK(result >= 0);
    return result;
}
#define RULE(nr, action) \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##nr, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, action)
#define ALLOW(nr) RULE(nr, SECCOMP_RET_ALLOW)
static int confinement(void) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        RULE(openat, SECCOMP_RET_USER_NOTIF),
        ALLOW(read), ALLOW(write), ALLOW(close), ALLOW(dup), ALLOW(dup3),
        ALLOW(exit), ALLOW(exit_group), ALLOW(getpid), ALLOW(gettid),
        ALLOW(getuid), ALLOW(geteuid), ALLOW(rt_sigaction), ALLOW(rt_sigreturn),
        ALLOW(rt_sigprocmask), ALLOW(sigaltstack), ALLOW(seccomp),
        /* Bootstrap sends its listener before entering the sealed test body. */
        ALLOW(sendmsg),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prctl, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_DUMPABLE, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_GET_DUMPABLE, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
    };
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    return install(rules, sizeof(rules) / sizeof(*rules), SECCOMP_FILTER_FLAG_NEW_LISTENER);
}
static void policy(int nr, unsigned action) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, action),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    install(rules, sizeof(rules) / sizeof(*rules), 0);
}
static void duplicate_listener(void) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    errno = 0;
    CHECK(syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER,
        SECCOMP_FILTER_FLAG_NEW_LISTENER, &program) == -1 && errno == EBUSY);
    checks++;
}
static void send_listener(int listener) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'L'; struct iovec vector = {&byte, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &listener, sizeof(listener));
    CHECK(sendmsg(3, &message, MSG_NOSIGNAL) == 1);
    CHECK(!close(listener));
    policy(SYS_sendmsg, SECCOMP_RET_ERRNO | EPERM);
    CHECK(read(3, &byte, 1) == 1 && byte == 'G');
    CHECK(!close(3));
}
static int take_listener(int channel) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte; struct iovec vector = {&byte, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ready(channel);
    CHECK(recvmsg(channel, &message, MSG_CMSG_CLOEXEC) == 1 && byte == 'L');
    CHECK(!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    CHECK(c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS
        && c->cmsg_len == CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&message, c));
    int fd; memcpy(&fd, CMSG_DATA(c), sizeof(fd));
    return fd;
}
static long raw_open(int dirfd, const char *path, int flags) {
    register long x0 __asm__("x0") = dirfd;
    register const char *x1 __asm__("x1") = path;
    register long x2 __asm__("x2") = flags;
    register long x3 __asm__("x3") = 0;
    register long x8 __asm__("x8") = SYS_openat;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory", "cc");
    return x0;
}
static void content(int fd, const char *expected) {
    char data[32] = {0};
    CHECK(fd >= 0 && read(fd, data, sizeof(data)) == (ssize_t)strlen(expected));
    CHECK(!strcmp(data, expected));
    CHECK(!close(fd));
    checks++;
}
static void denied_path(const char *path) {
    CHECK(raw_open(AT_FDCWD, path, O_RDONLY | O_CLOEXEC) < 0);
    checks++;
}
#define DENIED(expr) do { errno = 0; CHECK((expr) == -1 && errno == EPERM); checks++; } while (0)
static unsigned char alternate[65536] __attribute__((aligned(16)));
static volatile sig_atomic_t traps;
static void application_trap(int signal, siginfo_t *info, void *context) {
    char local;
    if (signal != SIGSYS || info->si_errno != 73 || info->si_syscall != SYS_openat
            || (uintptr_t)&local < (uintptr_t)alternate
            || (uintptr_t)&local >= (uintptr_t)alternate + sizeof(alternate)) _exit(91);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = -ENOMEDIUM;
    traps++;
}
static void exercise(enum mode mode, const char *expected) {
    if (mode == SHADOW_LISTENER) {
        duplicate_listener();
        policy(SYS_openat, SECCOMP_RET_USER_NOTIF);
        CHECK(raw_open(AT_FDCWD, "/allowed", O_RDONLY) == -ENOSYS);
        CHECK(raw_open(AT_FDCWD, host_secret, O_RDONLY) == -ENOSYS);
        puts("PASS new listener rejected; listenerless shadow fails closed");
        return;
    }
    if (mode == LISTENER_LOSS) {
        CHECK(raw_open(AT_FDCWD, "/allowed", O_RDONLY) == -ENOSYS);
        puts("PASS closed listener fails closed");
        return;
    }
    if (mode == KILL_POLICY) {
        policy(SYS_openat, SECCOMP_RET_KILL_PROCESS);
        raw_open(AT_FDCWD, "/allowed", O_RDONLY);
        _exit(92);
    }
    if (mode == PROTECTED) {
        CHECK(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
        CHECK(raw_open(AT_FDCWD, "/allowed", O_RDONLY) == -EACCES);
        CHECK(prctl(PR_GET_DUMPABLE) == 0);
        puts("PASS protected memory unavailable: denied without changing dumpability");
        return;
    }
    if (mode == INHERITED) {
        content(100, "HOST-SECRET");
        puts("CONTROL inherited host descriptor bypasses path confinement");
        return;
    }
    content(raw_open(AT_FDCWD, "/allowed", O_RDONLY | O_CLOEXEC), expected);
    content(raw_open(AT_FDCWD, "allowed", O_RDONLY), expected);
    content(raw_open(AT_FDCWD, "/inside-link", O_RDONLY), expected);
    content(raw_open(AT_FDCWD, "/../../allowed", O_RDONLY), expected);
    char byte;
    errno = 0; CHECK(read(100, &byte, 1) == -1 && errno == EBADF); checks++;
    denied_path(host_secret);
    denied_path("../secret");
    denied_path("/../secret");
    denied_path("/outside-link");
    denied_path("/relative-link");
    denied_path("/proc/self/root");
    denied_path("/proc/self/fd/100");
    denied_path("/proc/self/mem");
    denied_path("/dev/fd/100");
    CHECK(raw_open(AT_FDCWD, "/", O_PATH | O_DIRECTORY) == -EACCES); checks++;
    CHECK(raw_open(AT_FDCWD, "/allowed", O_WRONLY) == -EACCES); checks++;
    CHECK(raw_open(100, "allowed", O_RDONLY) == -EACCES); checks++;
    CHECK(raw_open(AT_FDCWD, (const char *)1, O_RDONLY) == -EFAULT); checks++;
    char too_long[PATH_BYTES]; memset(too_long, 'a', sizeof(too_long));
    CHECK(raw_open(AT_FDCWD, too_long, O_RDONLY) == -ENAMETOOLONG); checks++;
    /* No direct RPC, descriptor import, process manipulation or alternative path API. */
    DENIED(syscall(SYS_socket, AF_UNIX, SOCK_STREAM, 0));
    DENIED(syscall(SYS_connect, -1, NULL, 0));
    DENIED(syscall(SYS_sendmsg, -1, NULL, 0));
    DENIED(syscall(SYS_recvmsg, -1, NULL, 0));
    DENIED(syscall(SYS_ptrace, PTRACE_SEIZE, owner, NULL, NULL));
    DENIED(syscall(SYS_process_vm_readv, owner, NULL, 0, NULL, 0, 0));
    DENIED(syscall(SYS_process_vm_writev, owner, NULL, 0, NULL, 0, 0));
    DENIED(syscall(SYS_kill, owner, 0));
    DENIED(syscall(SYS_pidfd_open, owner, 0));
    DENIED(syscall(SYS_pidfd_getfd, -1, 0, 0));
    DENIED(syscall(SYS_openat2, AT_FDCWD, host_secret, NULL, 0));
    DENIED(syscall(SYS_name_to_handle_at, AT_FDCWD, host_secret, NULL, NULL, 0));
    DENIED(syscall(SYS_open_by_handle_at, -1, NULL, 0));
    DENIED(syscall(SYS_io_uring_setup, 1, NULL));
    DENIED(syscall(SYS_execve, host_secret, NULL, NULL));
    DENIED(syscall(SYS_clone, SIGCHLD, 0, 0, 0, 0));
    DENIED(syscall(SYS_chroot, "/"));
    DENIED(syscall(SYS_setresuid, 0, 0, 0));
    DENIED(syscall(SYS_prctl, PR_SET_NO_NEW_PRIVS, 0, 0, 0, 0));
    CHECK(getuid() == 2000 && geteuid() == 2000); checks++;
    int fd = raw_open(AT_FDCWD, "/allowed", O_RDONLY);
    CHECK(fd >= 0 && dup3(fd, 100, O_CLOEXEC) == 100 && !close(fd));
    content(100, expected);
    for (unsigned i = 0; i < 128; ++i)
        content(raw_open(AT_FDCWD, "/allowed", O_RDONLY), expected);
    duplicate_listener();
    policy(SYS_openat, SECCOMP_RET_ALLOW);
    content(raw_open(AT_FDCWD, "/allowed", O_RDONLY), expected);
    denied_path(host_secret);
    /* The application can further restrict openat; the broker never receives it. */
    policy(SYS_openat, SECCOMP_RET_ERRNO | EBUSY);
    CHECK(raw_open(AT_FDCWD, "/allowed", O_RDONLY) == -EBUSY); checks++;
    stack_t stack = {.ss_sp = alternate, .ss_size = sizeof(alternate)};
    CHECK(!sigaltstack(&stack, NULL));
    struct sigaction action = {.sa_sigaction = application_trap, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    CHECK(!sigemptyset(&action.sa_mask) && !sigaction(SIGSYS, &action, NULL));
    policy(SYS_openat, SECCOMP_RET_TRAP | 73);
    CHECK(raw_open(AT_FDCWD, "/allowed", O_RDONLY) == -ENOMEDIUM && traps == 1); checks++;
    printf("PASS domain=%d checks=%u data=%s original-seccomp=errno+trap\n", child_index, checks, expected);
}
static int read_path(pid_t pid, uintptr_t address, char *path) {
    size_t offset = 0;
    while (offset < PATH_BYTES) {
        if (address > UINTPTR_MAX - offset) return -EFAULT;
        uintptr_t start = address + offset;
        size_t length = 4096 - start % 4096;
        if (length > PATH_BYTES - offset) length = PATH_BYTES - offset;
        struct iovec local = {path + offset, length}, remote = {(void *)start, length};
        ssize_t count = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (count <= 0) return errno == EPERM || errno == EACCES ? -EACCES : -EFAULT;
        if (memchr(path + offset, 0, count)) return 0;
        offset += count;
    }
    return -ENAMETOOLONG;
}
static void reply(int listener, uint64_t id, int error) {
    struct seccomp_notif_resp response = {.id = id, .error = error};
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &response) == 0 || errno == ENOENT);
}
static void broker(struct domain *domain) {
    memset(request, 0, request_size);
    if (ioctl(domain->listener, SECCOMP_IOCTL_NOTIF_RECV, request)) {
        CHECK(errno == EINTR || errno == ENOENT);
        return;
    }
    CHECK(request->pid == (unsigned)domain->pid && request->data.arch == AUDIT_ARCH_AARCH64
        && request->data.nr == SYS_openat);
    domain->requests++;
    char path[PATH_BYTES];
    int error = 0;
    uint64_t flags = request->data.args[2];
    if ((int)request->data.args[0] != AT_FDCWD || (flags & ~(uint64_t)O_CLOEXEC)) error = -EACCES;
    if (!error) error = read_path(domain->pid, request->data.args[1], path);
    if (error == -EACCES && domain->mode == PROTECTED) domain->unreadable++;
    if (ioctl(domain->listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &request->id)) {
        CHECK(errno == ENOENT);
        return;
    }
    int fd = -1;
    if (!error) {
        /* Resolve the copied path under a supervisor-only pinned root. Never
         * CONTINUE a guest pointer, and never use a guest-supplied host path. */
        struct open_how how = {.flags = O_RDONLY | O_CLOEXEC | O_NONBLOCK,
            .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_XDEV};
        fd = syscall(SYS_openat2, domain->root, path, &how, sizeof(how));
        if (fd < 0) error = -errno;
        else {
            struct stat st;
            CHECK(!fstat(fd, &st));
            if (!S_ISREG(st.st_mode)) error = -EACCES;
        }
    }
    if (error) {
        domain->denied++;
        reply(domain->listener, request->id, error);
    } else {
        struct seccomp_notif_addfd add = {.id = request->id, .srcfd = fd,
            .newfd_flags = flags & O_CLOEXEC, .flags = SECCOMP_ADDFD_FLAG_SEND};
        int result = ioctl(domain->listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
        CHECK(result >= 0 || errno == ENOENT);
        if (result >= 0) domain->admitted++;
    }
    if (fd >= 0) close(fd);
}
static void create_file(int root, const char *path, const char *value) {
    int fd = openat(root, path, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    CHECK(fd >= 0 && write(fd, value, strlen(value)) == (ssize_t)strlen(value) && !close(fd));
}
int main(int argc, char **argv) {
    CHECK(argc == 2 && argv[1][0] == '/');
    owner = getpid();
    setvbuf(stdout, NULL, _IONBF, 0);
    CHECK(getuid() == 2000 && geteuid() == 2000);
    CHECK(sysconf(_SC_PAGESIZE) == 4096);
    deadline = md_event_now() + 20000000000LL;
    for (unsigned i = 0; i < CHILDREN; ++i) domains[i].listener = -1;
    struct seccomp_notif_sizes sizes;
    CHECK(!syscall(SYS_seccomp, SECCOMP_GET_NOTIF_SIZES, 0, &sizes));
    CHECK(sizes.seccomp_notif >= sizeof(*request));
    request_size = sizes.seccomp_notif; request = calloc(1, request_size); CHECK(request);
    sigset_t signals; sigemptyset(&signals); sigaddset(&signals, SIGCHLD);
    CHECK(!sigprocmask(SIG_BLOCK, &signals, NULL));
    int signal_fd = signalfd(-1, &signals, SFD_CLOEXEC | SFD_NONBLOCK); CHECK(signal_fd >= 0);
    int outer = open(argv[1], O_PATH | O_DIRECTORY | O_CLOEXEC); CHECK(outer >= 0);
    create_file(outer, "secret", "HOST-SECRET");
    CHECK(snprintf(host_secret, sizeof(host_secret), "%s/secret", argv[1]) < (int)sizeof(host_secret));
    int secret = openat(outer, "secret", O_RDONLY | O_CLOEXEC); CHECK(secret >= 0);
    const char *names[] = {"a", "b"}, *values[] = {"GUEST-A", "GUEST-B"};
    int roots[2];
    for (unsigned i = 0; i < 2; ++i) {
        CHECK(!mkdirat(outer, names[i], 0700));
        roots[i] = openat(outer, names[i], O_PATH | O_DIRECTORY | O_CLOEXEC); CHECK(roots[i] >= 0);
        create_file(roots[i], "allowed", values[i]);
        CHECK(!symlinkat("/allowed", roots[i], "inside-link"));
        CHECK(!symlinkat(host_secret, roots[i], "outside-link"));
        CHECK(!symlinkat("../secret", roots[i], "relative-link"));
    }
    const enum mode modes[CHILDREN] = {CONFINED, CONFINED, INHERITED, PROTECTED,
        LISTENER_LOSS, KILL_POLICY, SHADOW_LISTENER};
    for (unsigned i = 0; i < CHILDREN; ++i) {
        int channel[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel));
        pid_t pid = fork(); CHECK(pid >= 0);
        if (!pid) {
            child_index = i;
            CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == owner);
            if (channel[1] != 3) CHECK(dup3(channel[1], 3, O_CLOEXEC) == 3);
            /* Preserve a deliberate leak only in the positive control. */
            if (modes[i] == INHERITED) {
                CHECK(dup3(secret, 100, O_CLOEXEC) == 100);
                CHECK(!syscall(SYS_close_range, 4, 99, 0));
                CHECK(!syscall(SYS_close_range, 101, UINT_MAX, 0));
            } else CHECK(!syscall(SYS_close_range, 4, UINT_MAX, 0));
            int null = open("/dev/null", O_RDONLY | O_CLOEXEC); CHECK(null >= 0);
            CHECK(dup3(null, 0, 0) == 0 && !close(null));
            send_listener(confinement());
            exercise(modes[i], values[i % 2]);
            _exit(0);
        }
        close(channel[1]);
        domains[i] = (struct domain){.pid = pid, .listener = take_listener(channel[0]),
            .root = roots[i % 2], .mode = modes[i]};
        if (modes[i] == LISTENER_LOSS) {
            close(domains[i].listener); domains[i].listener = -1;
        }
        CHECK(write(channel[0], "G", 1) == 1); close(channel[0]);
    }
    unsigned living = CHILDREN;
    while (living) {
        struct pollfd fds[CHILDREN + 1] = {{.fd = signal_fd, .events = POLLIN}};
        for (unsigned i = 0; i < CHILDREN; ++i)
            fds[i + 1] = (struct pollfd){.fd = domains[i].listener, .events = POLLIN};
        /* EVENT_WAIT: kernel notifications and SIGCHLD; no settling interval. */
        CHECK(md_event_wait(fds, CHILDREN + 1, deadline) >= 0);
        for (unsigned i = 0; i < CHILDREN; ++i)
            if (fds[i + 1].revents & POLLIN) broker(&domains[i]);
        if (fds[0].revents) {
            struct signalfd_siginfo info;
            while (read(signal_fd, &info, sizeof(info)) == sizeof(info)) { }
            CHECK(errno == EAGAIN);
        }
        for (unsigned i = 0; i < CHILDREN; ++i) {
            struct domain *d = &domains[i];
            if (d->pid <= 0) continue;
            int status; pid_t pid = waitpid(d->pid, &status, WNOHANG);
            CHECK(pid == 0 || pid == d->pid);
            if (!pid) continue;
            d->pid = 0; --living;
            if (d->listener >= 0) close(d->listener);
            d->listener = -1;
            if (d->mode == KILL_POLICY) CHECK(WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS);
            else CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
        }
    }
    for (unsigned i = 0; i < CHILDREN; ++i) {
        struct domain *d = &domains[i];
        printf("BROKER domain=%u requests=%u admitted=%u denied=%u unreadable=%u\n",
            i, d->requests, d->admitted, d->denied, d->unreadable);
        if (d->mode == CONFINED) CHECK(d->requests == 149 && d->admitted == 134 && d->denied == 15);
        else if (d->mode == PROTECTED) CHECK(d->requests == 1 && d->unreadable == 1);
        else CHECK(d->requests == 0);
    }
    puts("PASS domain fixture; no virtual-chroot, credential or browser certification");
    close(secret); close(roots[0]); close(roots[1]); close(outer); close(signal_fd); free(request);
    return 0;
}
