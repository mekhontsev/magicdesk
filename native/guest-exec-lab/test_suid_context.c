#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#include <elf.h>
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
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>

/* Compile the original helper without source changes. --main exercises its
 * entry point and a native exec into the fixture client, not a set-ID ELF. */
#define main chromium_helper_main
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-parameter"
#include "sandbox/linux/suid/sandbox.c"
#pragma clang diagnostic pop
#undef main

enum { MAX_TASKS = 16 };
struct fs_context { int root, cwd, host_root; unsigned references; };
enum copy_phase { COPY_IDLE, COPY_CANCEL, COPY_RUNNING, COPY_ENTRY, COPY_EXIT, COPY_COMMITTED };
struct task {
    pid_t pid;
    struct fs_context *fs;
    int born, stopped;
    unsigned long cloning;
    int can_exec;
    uintptr_t copy_base;
    enum copy_phase phase;
    struct user_pt_regs saved;
    struct seccomp_notif original;
    uint64_t mask;
    int copy_error;
    char path[PATH_MAX];
};
static struct task tasks[MAX_TASKS];
static struct fs_context contexts[MAX_TASKS];
static pid_t owner;
static int signal_fd, listener = -1;
static long deadline;
static unsigned shared_forks, private_forks, chroots, notifications, denials;
static unsigned protected_copies, replay_returns;
static unsigned namespace_rejections, images;
static int full_main, copy_fd, supervisor;
static char (*transfer)[PATH_MAX];
static char marker[PATH_MAX];
static char executable[PATH_MAX];
extern const char md_hybrid_string[], md_hybrid_string_done[];
extern void md_suid_client_ready(void *);

static void cleanup(void) {
    if (!supervisor || getpid() != owner) return;
    for (unsigned i = 0; i < MAX_TASKS; ++i) if (tasks[i].pid > 0) kill(tasks[i].pid, SIGKILL);
    /* EVENT_WAIT: cancellation reaps only this traced tree; outer runner bounds it. */
    while (waitpid(-1, NULL, __WALL) > 0 || errno == EINTR) { }
}
static void failed(const char *what, unsigned line) {
    fprintf(stderr, "FAIL suid-context pid=%d line=%u %s errno=%d\n", getpid(), line, what, errno);
    cleanup(); _exit(1);
}
#define CHECK(x) do { if (!(x)) failed(#x, __LINE__); } while (0)
static void ready(int fd) {
    /* EVENT_WAIT: explicit bootstrap IPC readiness; timeout fails the fixture. */
    CHECK(md_event_wait_fd(fd, POLLIN, deadline) >= 0);
}
static int filter(void) {
#define RULE(n, a) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), BPF_STMT(BPF_RET | BPF_K, a)
#define ALLOW(n) RULE(n, SECCOMP_RET_ALLOW)
#define NOTIFY(n) RULE(n, SECCOMP_RET_USER_NOTIF)
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        NOTIFY(openat), NOTIFY(newfstatat), NOTIFY(chdir), NOTIFY(chroot),
        NOTIFY(execve),
        RULE(clone, SECCOMP_RET_TRACE | 51),
        ALLOW(read), ALLOW(write), ALLOW(close), ALLOW(fstat), ALLOW(exit), ALLOW(exit_group),
        ALLOW(getpid), ALLOW(gettid), ALLOW(getuid), ALLOW(geteuid), ALLOW(wait4),
        ALLOW(getresuid), ALLOW(getresgid), ALLOW(setresuid), ALLOW(setresgid),
        ALLOW(rt_sigaction), ALLOW(rt_sigprocmask), ALLOW(rt_sigreturn), ALLOW(futex),
        ALLOW(mmap), ALLOW(munmap), ALLOW(mprotect), ALLOW(madvise), ALLOW(brk),
        ALLOW(pipe2), ALLOW(socketpair), ALLOW(seccomp), ALLOW(setrlimit),
        ALLOW(getrandom), ALLOW(clock_gettime), ALLOW(set_tid_address),
        /* Removed by a second immutable filter before the tested helper runs. */
        ALLOW(sendmsg),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fcntl, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, F_GETFL, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, F_GETFD, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prctl, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_DUMPABLE, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_GET_DUMPABLE, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prlimit64, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
    };
#undef NOTIFY
#undef ALLOW
#undef RULE
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    int result = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
    CHECK(result >= 0); return result;
}
static void pass_listener(int fd) {
    union { struct cmsghdr align; char data[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'L'; struct iovec vector = {&byte, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.data, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    CHECK(sendmsg(3, &message, MSG_NOSIGNAL) == 1 && !close(fd));
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_sendmsg, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
    CHECK(!close(3));
}
static int take_listener(int channel) {
    union { struct cmsghdr align; char data[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte; struct iovec vector = {&byte, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.data, .msg_controllen = sizeof(control)};
    ready(channel);
    CHECK(recvmsg(channel, &message, MSG_CMSG_CLOEXEC) == 1 && byte == 'L');
    CHECK(!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    CHECK(c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS
        && c->cmsg_len == CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&message, c));
    int fd; memcpy(&fd, CMSG_DATA(c), sizeof(fd)); return fd;
}
static struct task *task(pid_t pid) {
    for (unsigned i = 0; i < MAX_TASKS; ++i) if (tasks[i].pid == pid) return &tasks[i];
    for (unsigned i = 0; i < MAX_TASKS; ++i) if (!tasks[i].pid) {
        tasks[i] = (struct task){.pid = pid}; return &tasks[i];
    }
    CHECK(0); return NULL;
}
static struct fs_context *copy_context(const struct fs_context *source) {
    for (unsigned i = 0; i < MAX_TASKS; ++i) if (!contexts[i].references) {
        struct fs_context *fs = &contexts[i];
        *fs = (struct fs_context){.root = dup(source->root), .cwd = dup(source->cwd),
            .host_root = source->host_root, .references = 1};
        CHECK(fs->root >= 0 && fs->cwd >= 0); return fs;
    }
    CHECK(0); return NULL;
}
static void release_context(struct fs_context *fs) {
    CHECK(fs && fs->references);
    if (!--fs->references) { close(fs->root); close(fs->cwd); }
}
static struct user_pt_regs registers(pid_t pid) {
    struct user_pt_regs result; struct iovec io = {&result, sizeof(result)};
    CHECK(!ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &io)); return result;
}
static void set_registers(pid_t pid, struct user_pt_regs *regs) {
    struct iovec io = {regs, sizeof(*regs)};
    CHECK(!ptrace(PTRACE_SETREGSET, pid, (void *)NT_PRSTATUS, &io));
}
static void resume(pid_t pid, int signal) { CHECK(!ptrace(PTRACE_CONT, pid, 0, signal)); }
static void replay(pid_t pid) { CHECK(!ptrace(PTRACE_SYSCALL, pid, 0, 0)); }
static void start_copy(struct task *t) {
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &t->original.id) == -1 && errno == ENOENT);
    t->saved = registers(t->pid);
    t->saved.pc = t->original.data.instruction_pointer - 4;
    t->saved.regs[8] = t->original.data.nr;
    for (unsigned i = 0; i < 6; ++i) t->saved.regs[i] = t->original.data.args[i];
    CHECK(!ptrace(PTRACE_GETSIGMASK, t->pid, sizeof(t->mask), &t->mask));
    uint64_t mask = UINT64_MAX;
    const int synchronous[] = {SIGKILL, SIGSTOP, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
    for (unsigned i = 0; i < sizeof(synchronous) / sizeof(*synchronous); ++i)
        mask &= ~(UINT64_C(1) << (synchronous[i] - 1));
    CHECK(!ptrace(PTRACE_SETSIGMASK, t->pid, sizeof(mask), &mask));
    struct user_pt_regs regs = t->saved;
    memset(transfer[t - tasks], 0xff, PATH_MAX);
    regs.pc = (uintptr_t)md_hybrid_string;
    CHECK(t->copy_base && t->copy_base <= UINTPTR_MAX - MAX_TASKS * PATH_MAX);
    regs.regs[0] = t->copy_base + (t - tasks) * PATH_MAX;
    regs.regs[1] = t->original.data.args[t->original.data.nr == SYS_openat
        || t->original.data.nr == SYS_newfstatat ? 1 : 0];
    regs.regs[2] = PATH_MAX;
    int skip = -1; struct iovec io = {&skip, sizeof(skip)};
    CHECK(!ptrace(PTRACE_SETREGSET, t->pid, (void *)NT_ARM_SYSTEM_CALL, &io));
    set_registers(t->pid, &regs);
    t->phase = COPY_RUNNING; resume(t->pid, 0);
}
static void copied(struct task *t, int fault) {
    struct user_pt_regs r = registers(t->pid);
    if (fault) {
        CHECK(r.pc >= (uintptr_t)md_hybrid_string && r.pc < (uintptr_t)md_hybrid_string_done);
        t->copy_error = -EFAULT;
    } else {
        CHECK(r.pc == (uintptr_t)md_hybrid_string_done);
        /* Shared memory is transport only. The private snapshot is used for
         * resolution, and its authority still comes from the kernel task. */
        memcpy(t->path, transfer[t - tasks], PATH_MAX);
        t->copy_error = memchr(t->path, 0, PATH_MAX) ? 0 : -ENAMETOOLONG;
    }
    protected_copies++;
    set_registers(t->pid, &t->saved);
    t->phase = COPY_ENTRY; replay(t->pid);
}
static void replay_stop(struct task *t) {
    struct user_pt_regs r = registers(t->pid);
    if (t->phase == COPY_ENTRY) {
        CHECK(r.pc == t->original.data.instruction_pointer && r.regs[8] == (unsigned)t->original.data.nr);
        for (unsigned i = 0; i < 6; ++i) CHECK(r.regs[i] == t->original.data.args[i]);
        CHECK(!ptrace(PTRACE_SETSIGMASK, t->pid, sizeof(t->mask), &t->mask));
        t->phase = COPY_EXIT; replay(t->pid);
    } else {
        CHECK(t->phase == COPY_COMMITTED);
        t->phase = COPY_IDLE; t->copy_error = 0; replay_returns++; resume(t->pid, 0);
    }
}
static int living(void) {
    for (unsigned i = 0; i < MAX_TASKS; ++i) if (tasks[i].pid > 0) return 1;
    return 0;
}
static void trace_stop(pid_t pid, int status) {
    struct task *t = task(pid);
    if (WIFEXITED(status) || WIFSIGNALED(status)) {
        CHECK(WIFEXITED(status) && !WEXITSTATUS(status) && t->born && t->phase == COPY_IDLE);
        release_context(t->fs); *t = (struct task){0}; return;
    }
    CHECK(WIFSTOPPED(status));
    unsigned event = (unsigned)status >> 16;
    if (event == PTRACE_EVENT_SECCOMP) {
        struct user_pt_regs r = registers(pid);
        CHECK(t->born && r.regs[8] == SYS_clone && !t->cloning);
        if (full_main && (r.regs[0] == (SIGCHLD | CLONE_NEWPID | CLONE_NEWNET)
                || r.regs[0] == (SIGCHLD | CLONE_NEWPID))) {
            /* This explicit guest model has no PID/network namespaces. It
             * reports unsupported, never successful isolation or host authority.
             * Native shell EPERM is measured separately, not hidden by this test. */
            int skip = -1; struct iovec io = {&skip, sizeof(skip)};
            CHECK(!ptrace(PTRACE_SETREGSET, pid, (void *)NT_ARM_SYSTEM_CALL, &io));
            r.regs[0] = (uint64_t)-EINVAL; set_registers(pid, &r);
            namespace_rejections++; resume(pid, 0); return;
        }
        CHECK(r.regs[0] == SIGCHLD || r.regs[0] == (SIGCHLD | CLONE_FS));
        t->cloning = r.regs[0]; resume(pid, 0);
    } else if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_CLONE) {
        unsigned long child; CHECK(!ptrace(PTRACE_GETEVENTMSG, pid, 0, &child));
        CHECK(t->cloning && t->fs);
        struct task *next = task(child); CHECK(!next->born);
        if (t->cloning & CLONE_FS) {
            next->fs = t->fs; next->fs->references++; shared_forks++;
        } else { next->fs = copy_context(t->fs); private_forks++; }
        next->born = 1; next->copy_base = t->copy_base; t->cloning = 0;
        if (next->stopped) { next->stopped = 0; resume(child, 0); }
        resume(pid, 0);
    } else if (event == PTRACE_EVENT_EXEC) {
        CHECK(full_main && t->born && t->phase == COPY_COMMITTED && !t->can_exec);
        CHECK(t->original.data.nr == SYS_execve);
        t->phase = COPY_IDLE; t->copy_error = 0; images++; replay_returns++;
        t->copy_base = 0;
        resume(pid, 0);
    } else if (event == PTRACE_EVENT_STOP) {
        if (t->phase == COPY_CANCEL) start_copy(t);
        else if (t->born) resume(pid, 0); else t->stopped = 1;
    } else if (!event && WSTOPSIG(status) == SIGTRAP && full_main
            && t->phase == COPY_IDLE && !t->copy_base) {
        struct user_pt_regs r = registers(pid);
        CHECK(r.pc == (uintptr_t)md_suid_client_ready && r.regs[0]
            && r.regs[0] <= UINTPTR_MAX - MAX_TASKS * PATH_MAX);
        /* The new image publishes only its copy-buffer address, never a
         * domain identity or permission. ASLR need not preserve the old mapping. */
        t->copy_base = r.regs[0]; r.pc += 4; set_registers(pid, &r); resume(pid, 0);
    } else if (!event && WSTOPSIG(status) == SIGTRAP && t->phase == COPY_RUNNING) copied(t, 0);
    else if (!event && WSTOPSIG(status) == SIGSEGV && t->phase == COPY_RUNNING) copied(t, 1);
    else if (!event && WSTOPSIG(status) == (SIGTRAP | 0x80)) replay_stop(t);
    else if (!event && WSTOPSIG(status) == SIGCHLD) resume(pid, SIGCHLD);
    else {
        struct user_pt_regs r = registers(pid);
        fprintf(stderr, "UNEXPECTED pid=%d status=%#x phase=%d pc=%#llx nr=%llu x0=%#llx\n",
            pid, status, t->phase, r.pc, r.regs[8], r.regs[0]);
        CHECK(0);
    }
}
static int memory(pid_t pid, uintptr_t address, void *buffer, size_t size, int write) {
    struct iovec local = {buffer, size}, remote = {(void *)address, size};
    ssize_t result = write ? process_vm_writev(pid, &local, 1, &remote, 1, 0)
        : process_vm_readv(pid, &local, 1, &remote, 1, 0);
    return result == (ssize_t)size ? 0 : result < 0 ? -errno : -EFAULT;
}
static int read_path(pid_t pid, uintptr_t address, char *out) {
    for (unsigned i = 0; i < PATH_MAX; ++i) {
        if (address > UINTPTR_MAX - i) return -EFAULT;
        int r = memory(pid, address + i, out + i, 1, 0);
        if (r) return r;
        if (!out[i]) return 0;
    }
    return -ENAMETOOLONG;
}
static int same_directory(int a, int b) {
    struct stat x, y; CHECK(!fstat(a, &x) && !fstat(b, &y));
    return x.st_dev == y.st_dev && x.st_ino == y.st_ino;
}
static int resolve(struct task *t, const char *path, int flags) {
    char translated[PATH_MAX];
    if (t->fs->host_root && !strncmp(path, "/proc/self", 10) && (!path[10] || path[10] == '/')) {
        int n = snprintf(translated, sizeof(translated), "/proc/%d%s", t->pid, path + 10);
        if (n < 0 || n >= (int)sizeof(translated)) return -ENAMETOOLONG;
        path = translated;
    }
    /* This probe admits absolute paths and relative paths only when cwd is
     * root. General root/cwd walks belong to the inode-store implementation. */
    if (*path != '/' && !same_directory(t->fs->root, t->fs->cwd)) return -ENOTSUP;
    struct open_how how = {.flags = flags | O_CLOEXEC,
        .resolve = RESOLVE_IN_ROOT | RESOLVE_NO_MAGICLINKS};
    int fd = syscall(SYS_openat2, t->fs->root, path, &how, sizeof(how));
    return fd < 0 ? -errno : fd;
}
static void notify_request(void) {
    struct seccomp_notif q = {0};
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &q)) { CHECK(errno == ENOENT || errno == EINTR); return; }
    struct task *t = task(q.pid); CHECK(t->born && t->fs);
    notifications++;
    int nr = q.data.nr;
    CHECK(q.data.arch == AUDIT_ARCH_AARCH64 && (nr == SYS_openat || nr == SYS_newfstatat
        || nr == SYS_chdir || nr == SYS_chroot || nr == SYS_execve));
    char path[PATH_MAX] = {0};
    int error;
    if (t->phase == COPY_EXIT) {
        CHECK(q.id != t->original.id && !memcmp(&q.data, &t->original.data, sizeof(q.data)));
        memcpy(path, t->path, sizeof(path));
        error = t->copy_error;
        t->phase = COPY_COMMITTED;
    } else {
        CHECK(t->phase == COPY_IDLE);
        error = read_path(q.pid, q.data.args[nr == SYS_openat || nr == SYS_newfstatat ? 1 : 0], path);
        if (error == -EPERM || error == -EACCES) {
            t->original = q; t->phase = COPY_CANCEL;
            CHECK(!ptrace(PTRACE_INTERRUPT, t->pid, 0, 0)); return;
        }
    }
    if (!error && (nr == SYS_openat || nr == SYS_newfstatat) && (int)q.data.args[0] != AT_FDCWD) error = -EACCES;
    if (!error && nr == SYS_openat && (q.data.args[2] & ~(uint64_t)O_CLOEXEC)) error = -EACCES;
    if (!error && nr == SYS_newfstatat && q.data.args[3]) error = -ENOTSUP;
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id)) { CHECK(errno == ENOENT); return; }
    if (nr == SYS_execve) {
        /* Narrow native-image handoff control: one pre-authorized fixture
         * image, single-threaded caller, no CLONE_VM, no installed handlers.
         * CONTINUE on a pathname is NOT an arbitrary hostile exec design. */
        if (!error && (!full_main || !t->can_exec || !t->fs->host_root
                || strcmp(path, executable))) error = -EACCES;
        if (!error) t->can_exec = 0;
        struct seccomp_notif_resp reply = {.id = q.id, .error = error,
            .flags = error ? 0 : SECCOMP_USER_NOTIF_FLAG_CONTINUE};
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply));
        printf("EXEC pid=%u result=%d fixture-only=true\n", q.pid, error);
        return;
    }
    int fd = error ? -1 : resolve(t, path, nr == SYS_openat ? O_RDONLY | O_NONBLOCK : O_PATH);
    if (!error && fd < 0) error = fd;
    if (!error && (nr == SYS_chdir || nr == SYS_chroot)) {
        struct stat st; CHECK(!fstat(fd, &st));
        if (!S_ISDIR(st.st_mode)) error = -ENOTDIR;
        else {
            /* Fixture launch explicitly permits virtual root changes. This
             * is not guest-UID/capability emulation or a native host chroot. */
            int *slot = nr == SYS_chroot ? &t->fs->root : &t->fs->cwd;
            close(*slot); *slot = fd; fd = -1;
            if (nr == SYS_chroot) { t->fs->host_root = 0; chroots++; }
        }
    }
    if (!error && nr == SYS_newfstatat) {
        struct stat st; CHECK(!fstat(fd, &st));
        error = memory(q.pid, q.data.args[2], &st, sizeof(st), 1);
    }
    if (!error && nr == SYS_openat) {
        struct stat st; CHECK(!fstat(fd, &st));
        if (!S_ISREG(st.st_mode)) error = -EACCES;
        else {
            struct seccomp_notif_addfd add = {.id = q.id, .srcfd = fd,
                .flags = SECCOMP_ADDFD_FLAG_SEND, .newfd_flags = q.data.args[2] & O_CLOEXEC};
            int r = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
            if (r < 0 && errno != ENOENT) error = -errno;
            else { close(fd); return; }
        }
    }
    if (fd >= 0) close(fd);
    if (error) denials++;
    printf("CONTEXT pid=%u nr=%d path=%s result=%d refs=%u\n", q.pid, nr,
        error == -EFAULT ? "<invalid>" : path, error, t->fs->references);
    struct seccomp_notif_resp reply = {.id = q.id, .error = error};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}
static void read_marker(int fd) {
    char data[32] = {0};
    CHECK(fd >= 0 && read(fd, data, sizeof(data)) == 14 && !strcmp(data, "CONTEXT-MARKER"));
    CHECK(!close(fd));
}
static void blocked_path(const char *path) {
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, path, O_RDONLY, 0) == -1 && errno == ESRCH);
}
static void client_work(int retained, int control, pid_t private) {
    CHECK(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    read_marker(open(marker, O_RDONLY));
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, (const char *)1, O_RDONLY, 0) == -1 && errno == EFAULT);
    int ipc = atoi(getenv(kSandboxDescriptorEnvironmentVarName));
    pid_t helper = atoi(getenv(kSandboxHelperPidEnvironmentVarName));
    CHECK(ipc >= 0 && helper > 0);
    CHECK(write(ipc, &kMsgChrootMe, 1) == 1);
    int status;
    /* EVENT_WAIT: actual Chromium helper exit and its protocol reply, not a delay. */
    CHECK(waitpid(helper, &status, 0) == helper && WIFEXITED(status) && !WEXITSTATUS(status));
    char reply; CHECK(read(ipc, &reply, 1) == 1 && reply == kMsgChrootSuccessful);
    CHECK(!close(ipc));
    blocked_path(marker);
    blocked_path("/proc/self/exe");
    blocked_path("/proc/self/root");
    blocked_path("/../../proc/self/exe");
    blocked_path("../../proc/self/exe");
    errno = 0; CHECK(syscall(SYS_openat2, AT_FDCWD, marker, NULL, 0) == -1 && errno == EPERM);
    errno = 0; CHECK(syscall(SYS_connect, -1, NULL, 0) == -1 && errno == EPERM);
    errno = 0; CHECK(syscall(SYS_sendmsg, -1, NULL, 0) == -1 && errno == EPERM);
    errno = 0; CHECK(syscall(SYS_process_vm_readv, owner, NULL, 0, NULL, 0, 0) == -1 && errno == EPERM);
    read_marker(retained);
    puts("PASS shared root restricted; previously admitted file descriptor remains valid");
    CHECK(write(control, "G", 1) == 1 && !close(control));
    CHECK(waitpid(private, &status, 0) == private && WIFEXITED(status) && !WEXITSTATUS(status));
    pid_t later = syscall(SYS_clone, SIGCHLD, 0, 0, 0, 0); CHECK(later >= 0);
    if (!later) {
        CHECK(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
        blocked_path(marker);
        struct sock_filter policy[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EKEYREJECTED),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = {sizeof(policy) / sizeof(*policy), policy};
        CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
        errno = 0;
        CHECK(syscall(SYS_openat, AT_FDCWD, marker, O_RDONLY, 0) == -1 && errno == EKEYREJECTED);
        puts("PASS later fork inherits the restricted filesystem context");
        puts("PASS application seccomp denial takes precedence in protected child");
        _exit(0);
    }
    CHECK(waitpid(later, &status, 0) == later && WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(getuid() == 2000 && geteuid() == 2000 && prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    puts("PASS stock Chromium SpawnChrootHelper protocol and CLONE_FS restriction");
}
static void child_work(void) {
    pass_listener(filter());
    read_marker(open(marker, O_RDONLY));
    int retained = open(marker, O_RDONLY); CHECK(retained >= 0);
    int control[2]; CHECK(!pipe2(control, 0));
    pid_t private = syscall(SYS_clone, SIGCHLD, 0, 0, 0, 0); CHECK(private >= 0);
    if (!private) {
        CHECK(!close(control[1]) && !close(retained));
        char byte; CHECK(read(control[0], &byte, 1) == 1 && byte == 'G');
        read_marker(open(marker, O_RDONLY));
        puts("PASS private fork retains its pre-helper filesystem context");
        _exit(0);
    }
    CHECK(!close(control[0]));
    if (full_main) {
        CHECK(!setenv(kSandboxEnvironmentApiRequest, "1", 1));
        CHECK(!setenv("SANDBOX_TMPDIR", "/fixture-environment", 1));
        char held[32], pipe[32], pid[32], fd[32];
        snprintf(held, sizeof(held), "%d", retained);
        snprintf(pipe, sizeof(pipe), "%d", control[1]);
        snprintf(pid, sizeof(pid), "%d", private);
        snprintf(fd, sizeof(fd), "%d", copy_fd);
        char *args[] = {"chrome-sandbox", executable, "--suid-client", marker,
            held, pipe, pid, fd, NULL};
        CHECK(chromium_helper_main(8, args) == 0);
        CHECK(0);
    }
    CHECK(SpawnChrootHelper() && DropRoot());
    client_work(retained, control[1], private);
}
int main(int argc, char **argv) {
    owner = getpid();
    setvbuf(stdout, NULL, _IONBF, 0);
    CHECK(getuid() == 2000 && geteuid() == 2000);
    if (argc == 7 && !strcmp(argv[1], "--suid-client")) {
        CHECK(strlen(argv[2]) < sizeof(marker)); strcpy(marker, argv[2]);
        int fd = atoi(argv[6]);
        transfer = mmap(NULL, MAX_TASKS * PATH_MAX, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        CHECK(transfer != MAP_FAILED && !close(fd));
        md_suid_client_ready(transfer);
        CHECK(getenv(kSandboxEnvironmentApiProvides)
            && !strcmp(getenv(kSandboxEnvironmentApiProvides), "1"));
        CHECK(!getenv(kSandboxPIDNSEnvironmentVarName) && !getenv(kSandboxNETNSEnvironmentVarName));
        CHECK(getenv("TMPDIR") && !strcmp(getenv("TMPDIR"), "/fixture-environment"));
        CHECK(!getenv("SANDBOX_TMPDIR"));
        /* Like the zygote, disable dumpability again after exec resets it. */
        CHECK(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
        client_work(atoi(argv[3]), atoi(argv[4]), atoi(argv[5]));
        puts("PASS stock Chromium main, environment, native exec and protected client");
        return 0;
    }
    CHECK((argc == 2 || (argc == 3 && !strcmp(argv[2], "--main"))) && argv[1][0] == '/');
    supervisor = 1;
    full_main = argc == 3;
    CHECK(argv[0][0] == '/' && strlen(argv[0]) < sizeof(executable));
    strcpy(executable, argv[0]);
    deadline = md_event_now() + 20000000000LL;
    copy_fd = syscall(SYS_memfd_create, "suid-context-copy", MFD_CLOEXEC); CHECK(copy_fd >= 0);
    CHECK(!ftruncate(copy_fd, MAX_TASKS * PATH_MAX));
    transfer = mmap(NULL, MAX_TASKS * PATH_MAX, PROT_READ | PROT_WRITE, MAP_SHARED, copy_fd, 0);
    CHECK(transfer != MAP_FAILED && sysconf(_SC_PAGESIZE) == 4096);
    CHECK(snprintf(marker, sizeof(marker), "%s/marker", argv[1]) < (int)sizeof(marker));
    int fd = open(marker, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    CHECK(fd >= 0 && write(fd, "CONTEXT-MARKER", 14) == 14 && !close(fd));
    struct seccomp_notif_sizes sizes; CHECK(!syscall(SYS_seccomp, SECCOMP_GET_NOTIF_SIZES, 0, &sizes));
    CHECK(sizes.seccomp_notif == sizeof(struct seccomp_notif));
    sigset_t signals; sigemptyset(&signals); sigaddset(&signals, SIGCHLD);
    CHECK(!sigprocmask(SIG_BLOCK, &signals, NULL));
    signal_fd = signalfd(-1, &signals, SFD_NONBLOCK | SFD_CLOEXEC); CHECK(signal_fd >= 0);
    int channel[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel));
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == owner);
        CHECK(dup3(copy_fd, 200, 0) == 200); copy_fd = 200;
        if (channel[1] != 3) CHECK(dup3(channel[1], 3, O_CLOEXEC) == 3);
        CHECK(!syscall(SYS_close_range, 4, 199, 0));
        CHECK(!syscall(SYS_close_range, 201, UINT_MAX, 0));
        int null = open("/dev/null", O_RDONLY | O_CLOEXEC); CHECK(null >= 0);
        CHECK(dup3(null, 0, 0) == 0 && !close(null));
        CHECK(write(3, "R", 1) == 1);
        char byte; CHECK(read(3, &byte, 1) == 1 && byte == 'G');
        child_work(); _exit(0);
    }
    close(channel[1]);
    int root = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC); CHECK(root >= 0);
    struct fs_context initial = {.root = root, .cwd = root, .host_root = 1};
    *task(child) = (struct task){.pid = child, .fs = copy_context(&initial), .born = 1,
        .can_exec = full_main, .copy_base = (uintptr_t)transfer};
    close(root);
    ready(channel[0]); char byte; CHECK(read(channel[0], &byte, 1) == 1 && byte == 'R');
    CHECK(!ptrace(PTRACE_SEIZE, child, 0, PTRACE_O_TRACEFORK | PTRACE_O_TRACECLONE
        | PTRACE_O_TRACESECCOMP | PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEEXEC));
    CHECK(write(channel[0], "G", 1) == 1);
    listener = take_listener(channel[0]); close(channel[0]);
    while (living()) {
        int status; pid_t pid = waitpid(-1, &status, __WALL | WNOHANG);
        if (pid > 0) { trace_stop(pid, status); continue; }
        CHECK(pid == 0 || errno == EINTR);
        struct pollfd events[] = {{signal_fd, POLLIN, 0}, {listener, POLLIN, 0}};
        /* EVENT_WAIT: ptrace child lifecycle and authenticated syscall notifications. */
        CHECK(md_event_wait(events, 2, deadline) >= 0);
        if (events[0].revents) {
            struct signalfd_siginfo info;
            while (read(signal_fd, &info, sizeof(info)) == sizeof(info)) { }
            CHECK(errno == EAGAIN);
        }
        if (events[1].revents & POLLIN) notify_request();
        if (events[1].revents & POLLHUP) { close(listener); listener = -1; }
    }
    CHECK(shared_forks == 1 && private_forks == 2 && chroots == 1);
    /* The static Bionic exec startup also probes two absent property files. */
    CHECK(denials == 7 + 2 * (unsigned)full_main);
    CHECK(protected_copies == 8 + (unsigned)full_main && replay_returns == protected_copies);
    CHECK(images == (unsigned)full_main && namespace_rejections == 2 * (unsigned)full_main);
    for (unsigned i = 0; i < MAX_TASKS; ++i) CHECK(!contexts[i].references);
    printf("PASS suid context: shared=%u private=%u chroots=%u notifications=%u denied=%u\n",
        shared_forks, private_forks, chroots, notifications, denials);
    printf("PROTECTED copies=%u kernel-replayed=%u dumpable-unchanged=0\n", protected_copies, replay_returns);
    printf("HELPER main=%d native-images=%u unsupported-namespace-requests=%u\n",
        full_main, images, namespace_rejections);
    puts("NOT CERTIFIED: set-ID, arbitrary ELF lifecycle and Chromium rendering");
    if (listener >= 0) close(listener);
    close(signal_fd);
    munmap(transfer, MAX_TASKS * PATH_MAX);
    close(copy_fd);
    return 0;
}
