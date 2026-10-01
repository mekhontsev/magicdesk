#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#ifndef MD_GUEST_PROBE
#include "../guest-runtime/src/fs_rpc.h"
#endif
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* Boundary observations, not a browser or sandbox certification. All filters
 * and fatal-signal controls run in owned children, never in the launcher. */
static const char *stage;
static pid_t owned, server;
enum { MD_SYS_SECCOMP = 1 }; /* Linux SIGSYS si_code, absent from some libc headers. */
static void cleanup(void) {
    pid_t children[] = {owned, server};
    for (unsigned i = 0; i < 2; i++) {
        if (children[i] <= 0) continue;
        kill(children[i], SIGKILL);
        while (waitpid(children[i], NULL, 0) < 0 && errno == EINTR) { }
    }
    owned = server = 0;
}
static void failed(const char *what, unsigned line) {
    fprintf(stderr, "FAIL %s line=%u: %s errno=%d\n", stage, line, what, errno);
    cleanup();
    exit(1);
}
#define CHECK(x) do { if (!(x)) failed(#x, __LINE__); } while (0)
static void ready(int fd) {
    /* EVENT_WAIT: owned process exit or explicit service-ready event;
     * deadline failure kills only this fixture's children. */
    CHECK(md_event_wait_fd(fd, POLLIN, md_event_now() + 5000000000LL) >= 0);
}
static void reap(pid_t pid, int expected_signal) {
    int fd = syscall(SYS_pidfd_open, pid, 0); CHECK(fd >= 0);
    ready(fd);
    int status;
    CHECK(waitpid(pid, &status, 0) == pid);
    close(fd);
    if (pid == owned) owned = 0;
    if (pid == server) server = 0;
    if (expected_signal) CHECK(WIFSIGNALED(status) && WTERMSIG(status) == expected_signal);
    else CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
static void run(const char *name, void (*test)(void), int expected_signal) {
    stage = name;
    pid_t parent = getpid(), pid = fork(); CHECK(pid >= 0);
    if (!pid) {
        owned = server = 0;
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL));
        CHECK(getppid() == parent);
        test();
        _exit(0);
    }
    owned = pid;
    reap(pid, expected_signal);
    printf("OBSERVED %s\n", name);
}
static int add_filter(int number, unsigned action, unsigned flags) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, number, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, action),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(rules[0]), rules};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    int result = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, flags, &program);
    CHECK(result >= 0);
    return result;
}
static volatile sig_atomic_t cookie;
static void result_handler(int number, siginfo_t *info, void *context) {
    if (number != SIGSYS || info->si_code != MD_SYS_SECCOMP) _exit(91);
    cookie = info->si_errno;
    ((ucontext_t *)context)->uc_mcontext.regs[0] = 711;
}
#ifndef MD_GUEST_PROBE
extern long md_policy_probe(long number);
extern const char md_policy_probe_return[];
static void install(void (*handler)(int, siginfo_t *, void *), int flags) {
    struct sigaction action = {.sa_sigaction = handler, .sa_flags = SA_SIGINFO | flags};
    sigemptyset(&action.sa_mask);
    CHECK(!sigaction(SIGSYS, &action, NULL));
}
static void trap_over_errno(void) {
    install(result_handler, 0);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 42, 0);
    add_filter(SYS_getppid, SECCOMP_RET_ERRNO | EACCES, 0);
    CHECK(syscall(SYS_getppid) == 711 && cookie == 42);
}
static void errno_before_trap(void) {
    install(result_handler, 0);
    add_filter(SYS_getppid, SECCOMP_RET_ERRNO | EACCES, 0);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 42, 0);
    CHECK(syscall(SYS_getppid) == 711 && cookie == 42);
}
static void newest_trap(void) {
    install(result_handler, 0);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 42, 0);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 73, 0);
    CHECK(syscall(SYS_getppid) == 711 && cookie == 73);
}
static void notification_priority(void) {
    install(result_handler, 0);
    int listener = add_filter(SYS_getppid, SECCOMP_RET_USER_NOTIF, SECCOMP_FILTER_FLAG_NEW_LISTENER);
    add_filter(SYS_getppid, SECCOMP_RET_ERRNO | EACCES, 0);
    errno = 0;
    CHECK(syscall(SYS_getppid) == -1 && errno == EACCES && !cookie);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 73, 0);
    CHECK(syscall(SYS_getppid) == 711 && cookie == 73);
    struct pollfd fd = {listener, POLLIN, 0};
    /* Immediate readiness inspection, not a state-polling wait. */
    CHECK(!poll(&fd, 1, 0));
    close(listener);
}
static unsigned char application_stack[65536] __attribute__((aligned(16)));
static volatile sig_atomic_t runtime_calls, application_calls, user_signals;
static void multiplex(int number, siginfo_t *info, void *context) {
    volatile char position = 0;
    uintptr_t p = (uintptr_t)&position, bottom = (uintptr_t)application_stack;
    if (number != SIGSYS || p < bottom || p >= bottom + sizeof(application_stack)) _exit(92);
    if (info->si_code != MD_SYS_SECCOMP) { user_signals++; return; }
    ucontext_t *uc = context;
    if (info->si_errno == 42 && info->si_syscall == SYS_getppid) {
        runtime_calls++;
        uc->uc_mcontext.regs[0] = 711;
    } else if (info->si_errno == 73 && info->si_syscall == SYS_getuid) {
        application_calls++;
        if (syscall(SYS_getppid) != 711) _exit(93);
        uc->uc_mcontext.regs[0] = 722;
    } else _exit(94);
}
static void cooperative_signals(void) {
    stack_t stack = {.ss_sp = application_stack, .ss_size = sizeof(application_stack)};
    CHECK(!sigaltstack(&stack, NULL));
    install(multiplex, SA_ONSTACK | SA_NODEFER);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 42, 0);
    add_filter(SYS_getuid, SECCOMP_RET_TRAP | 73, 0);
    CHECK(syscall(SYS_getuid) == 722 && application_calls == 1 && runtime_calls == 1);
    CHECK(!raise(SIGSYS) && user_signals == 1);
    stack_t actual;
    CHECK(!sigaltstack(NULL, &actual) && actual.ss_sp == stack.ss_sp && !(actual.ss_flags & SS_ONSTACK));
}
static void blocked_signal(void) {
    install(result_handler, SA_NODEFER);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 42, 0);
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGSYS);
    CHECK(!sigprocmask(SIG_BLOCK, &mask, NULL));
    syscall(SYS_getppid);
    _exit(95);
}
static void helper_filtered_handler(int number, siginfo_t *info, void *context) {
    if (number != SIGSYS || info->si_errno != 42) _exit(96);
    errno = 0;
    long result = syscall(SYS_getuid);
    if (result != -1 || errno != EPERM) _exit(97);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = (uint64_t)-EPERM;
}
static void helper_filter(void) {
    install(helper_filtered_handler, 0);
    add_filter(SYS_getppid, SECCOMP_RET_TRAP | 42, 0);
    add_filter(SYS_getuid, SECCOMP_RET_ERRNO | EPERM, 0);
    errno = 0;
    CHECK(syscall(SYS_getppid) == -1 && errno == EPERM);
}
static void policy_gate_handler(int number, siginfo_t *info, void *context) {
    if (number != SIGSYS || info->si_code != MD_SYS_SECCOMP || info->si_errno != 42) _exit(100);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = md_policy_probe(info->si_syscall);
}
static void install_policy_gate(void) {
    install(policy_gate_handler, SA_NODEFER);
    uintptr_t gate = (uintptr_t)md_policy_probe_return;
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(gate >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)gate, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP | 42),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(rules[0]), rules};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
}
static void policy_gate(void) {
    install_policy_gate();
    /* No listener: an allowed probe fails closed instead of executing getppid. */
    errno = 0;
    CHECK(syscall(SYS_getppid) == -1 && errno == ENOSYS);
    add_filter(SYS_getppid, SECCOMP_RET_ERRNO | EACCES, 0);
    errno = 0;
    CHECK(syscall(SYS_getppid) == -1 && errno == EACCES);
}
static void policy_gate_ip(void) {
    install_policy_gate();
    uintptr_t gate = (uintptr_t)md_policy_probe_return;
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(gate >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)gate, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(rules[0]), rules};
    CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
    /* The application denied the original call site. Reissuing it from the
     * gate changes the decision: no listener gives ENOSYS, not that EACCES. */
    errno = 0;
    CHECK(syscall(SYS_getppid) == -1 && errno == ENOSYS);
}
static void namespace_control(void) {
    errno = 0;
    int result = unshare(CLONE_NEWUSER), error = result < 0 ? errno : 0;
    printf("CAPABILITY userNamespace result=%d errno=%d\n", result, error);
    fflush(stdout);
}
static char endpoint[108];
static void rpc_policy(void) {
    add_filter(SYS_openat, SECCOMP_RET_ERRNO | EACCES, 0);
    errno = 0;
    CHECK(syscall(SYS_openat, AT_FDCWD, "/dev/null", O_RDONLY, 0) == -1 && errno == EACCES);
    struct md_fs_request request = {.operation = MD_FS_OPEN, .directory = {-1, -1},
        .path = {"/sandbox-marker", NULL}, .flags = O_RDONLY};
    struct md_fs_response result;
    CHECK(!md_fs_call(endpoint, 3000, &request, &result) && !result.result.error && result.result.fd >= 0);
    char marker;
    CHECK(read(result.result.fd, &marker, 1) == 1 && marker == 'm');
    close(result.result.fd);
    add_filter(SYS_connect, SECCOMP_RET_ERRNO | EACCES, 0);
    CHECK(md_fs_call(endpoint, 3000, &request, &result) == -EACCES);
}
static void filesystem_service(const char *executable, const char *store) {
    stage = "service startup";
    int startup[2], stop[2]; CHECK(!pipe2(startup, O_CLOEXEC) && !pipe2(stop, O_CLOEXEC));
    snprintf(endpoint, sizeof(endpoint), "md-sandbox-%d", getpid());
    char ready_text[24], stop_text[24];
    snprintf(ready_text, sizeof(ready_text), "%d", startup[1]);
    snprintf(stop_text, sizeof(stop_text), "%d", stop[0]);
    pid_t parent = getpid();
    server = fork(); CHECK(server >= 0);
    if (!server) {
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == parent);
        close(startup[0]); close(stop[1]);
        CHECK(!fcntl(startup[1], F_SETFD, 0) && !fcntl(stop[0], F_SETFD, 0));
        execl(executable, executable, store, endpoint, ready_text, stop_text, NULL);
        _exit(98);
    }
    close(startup[1]); close(stop[0]);
    ready(startup[0]); int error = -1;
    CHECK(read(startup[0], &error, sizeof(error)) == sizeof(error) && !error);
    close(startup[0]);
    struct md_fs_request request = {.operation = MD_FS_CREATE, .directory = {-1, -1},
        .path = {"/sandbox-marker", NULL}, .mode = 0600};
    struct md_fs_response result;
    CHECK(!md_fs_call(endpoint, 3000, &request, &result) && !result.result.error && result.result.fd >= 0);
    CHECK(write(result.result.fd, "m", 1) == 1); close(result.result.fd);
    run("LIMIT direct filesystem RPC bypasses openat denial; blocking connect closes this route", rpc_policy, 0);
    close(stop[1]); reap(server, 0);
}
#else
static void guest_signals(void) {
    struct sigaction action = {.sa_sigaction = result_handler, .sa_flags = SA_SIGINFO};
    sigemptyset(&action.sa_mask);
    errno = 0;
    CHECK(sigaction(SIGSYS, &action, NULL) == 0);
    unsigned char memory[32768]; stack_t stack = {.ss_sp = memory, .ss_size = sizeof(memory)};
    errno = 0;
    CHECK(sigaltstack(&stack, NULL) == 0);
    stack.ss_flags = SS_DISABLE;
    CHECK(sigaltstack(&stack, NULL) == 0);
}
static void guest_errno(void) {
    add_filter(SYS_openat, SECCOMP_RET_ERRNO | EACCES, 0);
    int fd = syscall(SYS_openat, AT_FDCWD, "/", O_RDONLY | O_DIRECTORY, 0);
    CHECK(fd == -1 && errno == EACCES);
}
static void guest_trap(void) {
    struct sigaction action = {.sa_sigaction = result_handler, .sa_flags = SA_SIGINFO};
    sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGSYS, &action, NULL) == 0);
    add_filter(SYS_openat, SECCOMP_RET_TRAP | 73, 0);
    int fd = syscall(SYS_openat, AT_FDCWD, "/", O_RDONLY | O_DIRECTORY, 0);
    CHECK(fd == 711 && cookie == 73);
}
static void guest_kill(void) {
    add_filter(SYS_openat, SECCOMP_RET_KILL_PROCESS, 0);
    syscall(SYS_openat, AT_FDCWD, "/", O_RDONLY | O_DIRECTORY, 0);
    _exit(99);
}
#endif
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    stage = "identity";
    CHECK(getuid() == 2000 && geteuid() == 2000);
#ifndef MD_GUEST_PROBE
    CHECK(argc == 3);
    run("LIMIT runtime TRAP outranks later application ERRNO", trap_over_errno, 0);
    run("LIMIT runtime TRAP outranks earlier application ERRNO", errno_before_trap, 0);
    run("kernel uses newest equal-priority TRAP cookie", newest_trap, 0);
    run("USER_NOTIF preserves application ERRNO/TRAP without broker invocation", notification_priority, 0);
    run("cooperative tagged SIGSYS, nested trap and application altstack work", cooperative_signals, 0);
    run("LIMIT blocking SIGSYS kills a process that needs a runtime trap", blocked_signal, SIGSYS);
    run("application filter also restricts syscalls made inside the runtime handler", helper_filter, 0);
    run("two-stage TRAP/USER_NOTIF gate rechecks ERRNO in kernel; absent listener fails closed", policy_gate, 0);
    run("LIMIT reissuing through a gate changes instruction-pointer-based policy", policy_gate_ip, 0);
    run("native namespace capability", namespace_control, 0);
    filesystem_service(argv[1], argv[2]);
#else
    (void)argc; (void)argv;
    run("production adapter preserves application SIGSYS and altstack", guest_signals, 0);
    run("application ERRNO denies openat before runtime adaptation", guest_errno, 0);
    run("application TRAP reaches its own handler with the original cookie", guest_trap, 0);
    run("kernel KILL remains effective inside the production adapter", guest_kill, SIGSYS);
#endif
    puts("Boundary observations complete; sandbox support NOT established");
    return 0;
}
