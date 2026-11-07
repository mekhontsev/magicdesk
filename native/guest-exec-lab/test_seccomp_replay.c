#define _GNU_SOURCE
#include "seccomp_policy.h"
#include <asm/ptrace.h>
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <linux/audit.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

enum { EXPECTED_EMULATION = 617, TRAP_RESULT = 711 };
static volatile sig_atomic_t seen, trap_cookie;
static unsigned char alternate[65536];
static void handler(int number, siginfo_t *info, void *context) {
    assert(number == SIGSYS);
    ++seen;
    if (info->si_code == 1) {
        trap_cookie = info->si_errno;
        ((ucontext_t *)context)->uc_mcontext.regs[0] = TRAP_RESULT;
    }
}

/* One entry address for both the real-kernel oracle and denied-call replay. */
extern const char replay_return[];
__attribute__((noinline)) static long invoke(long nr, unsigned long argument) {
    register unsigned long x0 __asm__("x0") = argument;
    register unsigned long x1 __asm__("x1") = 0;
    register unsigned long x2 __asm__("x2") = 0;
    register unsigned long x3 __asm__("x3") = 0;
    register unsigned long x4 __asm__("x4") = 0;
    register unsigned long x5 __asm__("x5") = 0;
    register unsigned long x8 __asm__("x8") = nr;
    __asm__ volatile("svc #0\n.global replay_return\nreplay_return:"
        : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    return (long)x0;
}

struct program {
    struct sock_filter code[64];
    unsigned count;
};
static void emit(struct program *p, struct sock_filter i) {
    assert(p->count < sizeof(p->code) / sizeof(*p->code));
    p->code[p->count++] = i;
}
#define ST(code, k) emit(p, (struct sock_filter)BPF_STMT(code, k))
#define JP(code, k, t, f) emit(p, (struct sock_filter)BPF_JUMP(code, k, t, f))
static struct sock_fprog view(struct program *p) {
    return (struct sock_fprog){.len = p->count, .filter = p->code};
}
static void make(struct program *p, int mode, long nr, unsigned action) {
    ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    JP(BPF_JMP | BPF_JEQ | BPF_K, nr, 1, 0);
    ST(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    switch (mode) {
    case 1: /* Both halves of an argument, scratch storage, X and RET A. */
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]));
        ST(BPF_ST, 0);
        ST(BPF_LDX | BPF_W | BPF_MEM, 0);
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]) + 4);
        ST(BPF_ALU | BPF_XOR | BPF_X, 0);
        ST(BPF_ALU | BPF_AND | BPF_K, 255);
        ST(BPF_ALU | BPF_OR | BPF_K, SECCOMP_RET_ERRNO);
        ST(BPF_RET | BPF_A, 0);
        break;
    case 2: /* Original PC and architecture, not an adapter address. */
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer));
        JP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(uintptr_t)replay_return, 1, 0);
        ST(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4);
        JP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)((uintptr_t)replay_return >> 32), 1, 0);
        ST(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
        JP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
        ST(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
        ST(BPF_RET | BPF_K, action);
        break;
    case 3:
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]));
        ST(BPF_LDX | BPF_W | BPF_IMM, 3);
        ST(BPF_ALU | BPF_MUL | BPF_X, 0);
        ST(BPF_ALU | BPF_ADD | BPF_K, 19);
        ST(BPF_ALU | BPF_DIV | BPF_X, 0);
        ST(BPF_ALU | BPF_LSH | BPF_X, 0);
        ST(BPF_ALU | BPF_RSH | BPF_K, 2);
        ST(BPF_ALU | BPF_NEG, 0);
        ST(BPF_ALU | BPF_SUB | BPF_K, 1);
        ST(BPF_ALU | BPF_AND | BPF_K, 255);
        ST(BPF_MISC | BPF_TAX, 0);
        ST(BPF_MISC | BPF_TXA, 0);
        ST(BPF_ALU | BPF_OR | BPF_K, SECCOMP_RET_ERRNO);
        ST(BPF_RET | BPF_A, 0);
        break;
    case 4:
        ST(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]));
        JP(BPF_JMP | BPF_JSET | BPF_K, 1, 0, 2);
        JP(BPF_JMP | BPF_JGT | BPF_K, 10, 0, 1);
        ST(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES);
        JP(BPF_JMP | BPF_JGE | BPF_K, 4, 0, 1);
        ST(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EBUSY);
        ST(BPF_JMP | BPF_JA, 0);
        ST(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM);
        break;
    case 5:
        ST(BPF_LD | BPF_W | BPF_IMM, 7);
        ST(BPF_LDX | BPF_W | BPF_IMM, 0);
        ST(BPF_ALU | BPF_DIV | BPF_X, 0);
        ST(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
        break;
    case 6:
        ST(BPF_LD | BPF_W | BPF_IMM, 255);
        ST(BPF_LDX | BPF_W | BPF_IMM, 33);
        ST(BPF_ALU | BPF_RSH | BPF_X, 0);
        ST(BPF_ALU | BPF_OR | BPF_K, SECCOMP_RET_ERRNO);
        ST(BPF_RET | BPF_A, 0);
        break;
    case 7:
        ST(BPF_LDX | BPF_W | BPF_LEN, 0);
        ST(BPF_STX, 15);
        ST(BPF_LD | BPF_W | BPF_MEM, 15);
        ST(BPF_ALU | BPF_OR | BPF_K, SECCOMP_RET_ERRNO);
        ST(BPF_RET | BPF_A, 0);
        break;
    default: ST(BPF_RET | BPF_K, action); break;
    }
}
#undef ST
#undef JP

static uint64_t nanos(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC, &t));
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static void native_io(unsigned count, int traced) {
    int fds[2]; assert(!pipe(fds));
    struct pollfd fd = {.fd = fds[0], .events = POLLIN};
    char value = 42, received;
    uint64_t start = nanos();
    for (unsigned i = 0; i < count; ++i) {
        assert(write(fds[1], &value, 1) == 1);
        assert(poll(&fd, 1, 0) == 1 && (fd.revents & POLLIN));
        assert(read(fds[0], &received, 1) == 1 && received == value);
    }
    printf("NATIVE_IO traced=%d iterations=%u ns=%llu\n", traced, count,
        (unsigned long long)(nanos() - start));
    close(fds[0]); close(fds[1]);
}

struct scenario { const char *name; int program; unsigned older, newer; int state; unsigned long argument; };
static int one(const struct scenario *test, int traced, unsigned iterations) {
    long nr = traced ? SYS_mount : SYS_getpid;
    struct program programs[2] = {{0}};
    make(&programs[0], test->program, nr, test->older);
    unsigned count = test->newer ? 2 : 1;
    if (count == 2) make(&programs[1], 0, nr, test->newer);
    struct seccomp_data original = {.nr = nr, .arch = AUDIT_ARCH_AARCH64,
        .instruction_pointer = (uintptr_t)replay_return, .args = {test->argument}};
    uint32_t decision = SECCOMP_RET_ALLOW;
    for (unsigned i = 0; i < count; ++i) {
        struct sock_fprog program = view(&programs[i]); uint32_t result;
        assert(!md_seccomp_program_eval(&program, &original, &result));
        decision = md_seccomp_policy_merge(decision, result);
    }
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        pid_t self = getpid(), tid = (pid_t)syscall(SYS_gettid);
        struct sigaction action = {.sa_sigaction = handler, .sa_flags = SA_SIGINFO | SA_ONSTACK};
        sigemptyset(&action.sa_mask);
        stack_t stack = {.ss_sp = alternate, .ss_size = sizeof(alternate)};
        assert(!sigaltstack(&stack, NULL) && !sigaction(SIGSYS, &action, NULL));
        sigset_t set; sigemptyset(&set); sigaddset(&set, SIGSYS);
        assert(!sigprocmask(SIG_UNBLOCK, &set, NULL));
        if (traced) { assert(!ptrace(PTRACE_TRACEME, 0, NULL, NULL)); assert(!raise(SIGSTOP)); }
        assert(!raise(SIGSYS) && seen == 1);
        if (test->state == 1) assert(!sigprocmask(SIG_BLOCK, &set, NULL));
        if (test->state == 2) { action.sa_handler = SIG_IGN; assert(!sigaction(SIGSYS, &action, NULL)); }
        assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
        for (unsigned i = 0; i < count; ++i) {
            struct sock_fprog program = view(&programs[i]);
            long installed = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program);
            if (installed) fprintf(stderr, "FILTER_REJECTED name=%s errno=%d\n", test->name, errno);
            assert(!installed);
        }
        if (iterations) native_io(iterations, traced);
        long value = invoke(nr, test->argument);
        unsigned type = decision & SECCOMP_RET_ACTION_FULL, data = decision & SECCOMP_RET_DATA;
        long expected = type == SECCOMP_RET_ERRNO ? -(long)(data > 4095 ? 4095 : data)
            : type == SECCOMP_RET_TRAP ? TRAP_RESULT : traced ? EXPECTED_EMULATION : self;
        struct sigaction after; sigset_t mask;
        assert(!sigaction(SIGSYS, NULL, &after) && !sigprocmask(SIG_SETMASK, NULL, &mask));
        int intact = after.sa_handler == action.sa_handler && !!sigismember(&mask, SIGSYS) == (test->state == 1);
        printf("REPLAY name=%s traced=%d value=%ld expected=%ld action=%#x seen=%d cookie=%d stateIntact=%d\n",
            test->name, traced, value, expected, decision, seen, trap_cookie, intact);
        assert(value == expected);
        assert(seen == (type == SECCOMP_RET_TRAP ? 2 : 1));
        assert(trap_cookie == (type == SECCOMP_RET_TRAP ? (int)data : 0));
        if (!intact) _exit(42);
        if (!test->state) assert(!syscall(SYS_tgkill, self, tid, SIGSYS)
            && seen == (type == SECCOMP_RET_TRAP ? 3 : 2));
        _exit(0);
    }
    int status, stops = 0, traps = 0, external = 0;
    for (;;) {
        assert(waitpid(child, &status, __WALL) == child);
        if (!WIFSTOPPED(status)) break;
        assert(traced);
        ++stops;
        int signal = WSTOPSIG(status);
        if (signal == SIGSTOP) {
            assert(!ptrace(PTRACE_SETOPTIONS, child, NULL, (void *)PTRACE_O_EXITKILL));
            signal = 0;
        } else if (signal == SIGSYS) {
            siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, child, NULL, &info));
            if (info.si_code == 1 && info.si_syscall == SYS_mount) {
                ++traps;
                struct user_pt_regs r; struct iovec io = {&r, sizeof(r)};
                assert(!ptrace(PTRACE_GETREGSET, child, (void *)NT_PRSTATUS, &io));
                struct seccomp_data call = {.nr = info.si_syscall, .arch = info.si_arch,
                    .instruction_pointer = (uintptr_t)info.si_call_addr};
                memcpy(call.args, r.regs, sizeof(call.args));
                assert(!memcmp(&call, &original, sizeof(call)));
                uint32_t result = SECCOMP_RET_ALLOW;
                for (unsigned i = 0; i < count; ++i) {
                    struct sock_fprog p = view(&programs[i]); uint32_t next;
                    assert(!md_seccomp_program_eval(&p, &call, &next));
                    result = md_seccomp_policy_merge(result, next);
                }
                unsigned type = result & SECCOMP_RET_ACTION_FULL, data = result & SECCOMP_RET_DATA;
                if (type == SECCOMP_RET_ALLOW || type == SECCOMP_RET_ERRNO) {
                    r.regs[0] = type == SECCOMP_RET_ALLOW ? EXPECTED_EMULATION : md_seccomp_policy_error(result);
                    assert(!ptrace(PTRACE_SETREGSET, child, (void *)NT_PRSTATUS, &io));
                    signal = 0;
                } else assert(type == SECCOMP_RET_TRAP && info.si_errno == (int)data);
            } else ++external;
        }
        assert(!ptrace(PTRACE_CONT, child, NULL, (void *)(long)signal));
    }
    unsigned type = decision & SECCOMP_RET_ACTION_FULL;
    int killed = type == SECCOMP_RET_KILL_PROCESS || type == SECCOMP_RET_KILL_THREAD;
    int damaged = traced && test->state;
    int passed = killed ? WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS
        : WIFEXITED(status) && WEXITSTATUS(status) == (damaged ? 42 : 0);
    if (traced && !killed) passed &= stops == (damaged ? 3 : 4) && traps == 1 && external == (damaged ? 1 : 2);
    printf("%s name=%s traced=%d status=%#x stops=%d denied=%d external=%d%s\n",
        passed ? "PASS" : "FAIL", test->name, traced, status, stops, traps, external,
        damaged ? " LIMIT=SIGSYS-state-not-transparent" : "");
    return !passed;
}

struct peer_channel { int request, reply; };
static void *observe_handler(void *opaque) {
    struct peer_channel *channel = opaque;
    char command;
    assert(read(channel->request, &command, 1) == 1);
    struct sigaction action;
    assert(!sigaction(SIGSYS, NULL, &action));
    char reset = action.sa_handler == SIG_DFL;
    assert(write(channel->reply, &reset, 1) == 1);
    return NULL;
}

static void shared_disposition(void) {
    int request[2], reply[2]; assert(!pipe(request) && !pipe(reply));
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        close(request[1]); close(reply[0]);
        struct sigaction action = {.sa_sigaction = handler, .sa_flags = SA_SIGINFO};
        sigemptyset(&action.sa_mask);
        assert(!sigaction(SIGSYS, &action, NULL));
        sigset_t set; sigemptyset(&set); sigaddset(&set, SIGSYS);
        assert(!sigprocmask(SIG_BLOCK, &set, NULL));
        struct peer_channel channel = {.request = request[0], .reply = reply[1]};
        pthread_t peer; assert(!pthread_create(&peer, NULL, observe_handler, &channel));
        assert(!ptrace(PTRACE_TRACEME, 0, NULL, NULL) && !raise(SIGSTOP));
        assert(invoke(SYS_mount, 0) == EXPECTED_EMULATION);
        assert(!pthread_join(peer, NULL));
        _exit(0);
    }
    close(request[0]); close(reply[1]);
    int status; assert(waitpid(child, &status, 0) == child);
    assert(WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS, child, NULL, (void *)PTRACE_O_EXITKILL));
    assert(!ptrace(PTRACE_CONT, child, NULL, NULL));
    assert(waitpid(child, &status, 0) == child && WIFSTOPPED(status) && WSTOPSIG(status) == SIGSYS);
    siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, child, NULL, &info));
    assert(info.si_code == 1 && info.si_syscall == SYS_mount);
    /* The victim stays stopped. Its sibling observes the shared disposition
     * before the tracer gets any opportunity to repair or resume the victim. */
    char command = 1, reset;
    assert(write(request[1], &command, 1) == 1 && read(reply[0], &reset, 1) == 1);
    assert(reset == 1);
    struct user_pt_regs r; struct iovec io = {&r, sizeof(r)};
    assert(!ptrace(PTRACE_GETREGSET, child, (void *)NT_PRSTATUS, &io));
    r.regs[0] = EXPECTED_EMULATION;
    assert(!ptrace(PTRACE_SETREGSET, child, (void *)NT_PRSTATUS, &io));
    assert(!ptrace(PTRACE_CONT, child, NULL, NULL));
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(request[1]); close(reply[0]);
    puts("LIMIT shared-sighand: sibling observed SIG_DFL while victim remained at SIGSYS delivery stop");
}

int main(void) {
    setbuf(stdout, NULL);
    const struct scenario cases[] = {
        {"allow", 0, SECCOMP_RET_ALLOW, 0, 0, 0},
        {"errno", 0, SECCOMP_RET_ERRNO | EACCES, 0, 0, 0},
        {"errno-clamp", 0, SECCOMP_RET_ERRNO | 65535, 0, 0, 0},
        {"errno-zero", 0, SECCOMP_RET_ERRNO, 0, 0, 0},
        {"trap", 0, SECCOMP_RET_TRAP | 73, 0, 0, 0},
        {"kill", 0, SECCOMP_RET_KILL_PROCESS, 0, 0, 0},
        {"kill-thread", 0, SECCOMP_RET_KILL_THREAD, 0, 0, 0},
        {"dynamic-arg", 1, 0, 0, 0, UINT64_C(0x123456789abcdef0)},
        {"ip-arch", 2, SECCOMP_RET_ERRNO | EIO, 0, 0, 0},
        {"alu", 3, 0, 0, 0, UINT32_MAX},
        {"division-zero", 5, 0, 0, 0, 0},
        {"variable-shift", 6, 0, 0, 0, 0},
        {"length-scratch", 7, 0, 0, 0, 0},
        {"jump-odd", 4, 0, 0, 0, 101},
        {"jump-even", 4, 0, 0, 0, 100},
        {"jump-small", 4, 0, 0, 0, 2},
        {"stack-errno-allow", 0, SECCOMP_RET_ERRNO | EIO, SECCOMP_RET_ALLOW, 0, 0},
        {"stack-newest", 0, SECCOMP_RET_ERRNO | EIO, SECCOMP_RET_ERRNO | EACCES, 0, 0},
        {"stack-trap-errno", 0, SECCOMP_RET_TRAP | 43, SECCOMP_RET_ERRNO | EACCES, 0, 0},
        {"stack-kill", 0, SECCOMP_RET_ERRNO | EIO, SECCOMP_RET_KILL_PROCESS, 0, 0},
        {"blocked", 0, SECCOMP_RET_ALLOW, 0, 1, 0},
        {"ignored", 0, SECCOMP_RET_ALLOW, 0, 2, 0},
    };
    int failed = 0;
    for (unsigned i = 0; i < sizeof(cases) / sizeof(*cases); ++i)
        for (int traced = 0; traced <= 1; ++traced) failed += one(&cases[i], traced, 0);
    for (unsigned count = 1024; count <= 65536; count *= 8)
        for (int traced = 0; traced <= 1; ++traced) failed += one(&cases[0], traced, count);
    shared_disposition();
    printf("REPLAY_SUMMARY failed=%d signalStateVirtualization=not-implemented\n", failed);
    return !!failed;
}
