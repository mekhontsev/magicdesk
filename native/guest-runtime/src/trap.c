#define _GNU_SOURCE
#include "bootstrap.h"
#include "file_calls.h"
#include "socket_calls.h"
#include "thread_context.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <sys/prctl.h>
#include <ucontext.h>

static long dispatch(long nr, unsigned long *a, ucontext_t *uc) {
    long r;
    switch (nr) {
#define FILE_CASE(name) case SYS_##name:
    MD_FILE_CALLS(FILE_CASE)
#undef FILE_CASE
        return md_file_call(&md_files, md_executable, nr, a);
#define SOCKET_CASE(name) case SYS_##name:
    MD_SOCKET_CALLS(SOCKET_CASE)
#undef SOCKET_CASE
        return md_socket_call(&md_files, md_executable, nr, a);
    case SYS_execve:
        return md_guest_exec((const char *)a[0], (char *const *)a[1], (char *const *)a[2]);
    case SYS_clone:
        return md_thread_clone(a, uc);
    case SYS_sigaltstack:
        return md_thread_altstack(a);
    case SYS_exit:
        md_thread_exit((int)a[0], uc);
    case SYS_clone3:
        // CLONE_CLEAR_SIGHAND would discard our inherited syscall handler. clone3
        // needs a native child-return gate; ENOSYS selects libc's ordinary clone path.
        return -ENOSYS;
    case SYS_execveat:
        return md_guest_execat((int)a[0], (const char *)a[1], (char *const *)a[2], (char *const *)a[3], (int)a[4]);
    case SYS_rt_sigaction:
        if (a[0] == SIGSYS) return -ENOTSUP;
        return RAW4(rt_sigaction, a[0], a[1], a[2], a[3]);
    case SYS_rt_sigprocmask: {
        if (a[3] != sizeof(uint64_t)) return -EINVAL;
        uint64_t mask = 0, old;
        memcpy(&old, &uc->uc_sigmask, sizeof(old));
        if (a[1]) {
            r = md_read_memory(&mask, (void *)a[1], sizeof(mask));
            if (r < 0) return r;
            if (a[0] != SIG_BLOCK && a[0] != SIG_UNBLOCK && a[0] != SIG_SETMASK) return -EINVAL;
        }
        if (a[2] && (r = md_write_memory((void *)a[2], &old, sizeof(old))) < 0) return r;
        if (a[1]) {
            // Like libc's internal signals, runtime-owned SIGSYS is excluded from guest masks.
            uint64_t next = a[0] == SIG_BLOCK ? old | mask : a[0] == SIG_UNBLOCK ? old & ~mask : mask;
            next &= ~((1UL << (SIGKILL - 1)) | (1UL << (SIGSTOP - 1)) | (1UL << (SIGSYS - 1)));
            memcpy(&uc->uc_sigmask, &next, sizeof(next));
        }
        return 0;
    }
    default: return -ENOSYS;
    }
}
static void handle(int signal, siginfo_t *info, void *context) {
    if (signal != SIGSYS || info->si_code != 1 /* Linux SYS_SECCOMP */) md_die("unexpected SIGSYS", -EINVAL);
    ucontext_t *uc = context;
    unsigned long a[6];
    for (unsigned i = 0; i < 6; ++i) a[i] = uc->uc_mcontext.regs[i];
    uc->uc_mcontext.regs[0] = (unsigned long)dispatch(info->si_syscall, a, uc);
}

int md_install_trap(int inherited) {
    int initialized = md_thread_initialize();
    if (initialized < 0) return initialized;
    struct kernel_action { void (*handler)(int, siginfo_t *, void *); unsigned long flags;
        void (*restorer)(void); uint64_t mask; } action = {
            handle, SA_SIGINFO | SA_NODEFER | SA_ONSTACK | 0x04000000, md_signal_return, 0};
    // Guest signal handlers may interrupt translation and make file syscalls themselves.
    // No mutable scratch state is shared; do not block their nested synchronous SIGSYS.
    _Static_assert(sizeof(action) == 32, "AArch64 kernel sigaction ABI");
    long r = RAW4(rt_sigaction, SIGSYS, &action, 0, sizeof(uint64_t));
    if (r < 0) return (int)r;
    uint64_t mask = 1UL << (SIGSYS - 1);
    // Restore the runtime-owned signal independently of the inherited guest mask.
    r = RAW4(rt_sigprocmask, SIG_UNBLOCK, &mask, 0, sizeof(mask));
    if (r < 0) return (int)r;
    if (inherited) return RAW1(prctl, PR_GET_SECCOMP) == SECCOMP_MODE_FILTER ? 0 : -EINVAL;
    uintptr_t gate = (uintptr_t)md_raw_return;
    uintptr_t clone_gate = (uintptr_t)md_clone_return;
#define TRAP(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(gate >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)gate, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(clone_gate >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)clone_gate, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        MD_FILE_CALLS(TRAP)
        MD_SOCKET_CALLS(TRAP)
        TRAP(execve) TRAP(execveat) TRAP(clone) TRAP(clone3)
        TRAP(exit) TRAP(sigaltstack) TRAP(rt_sigaction) TRAP(rt_sigprocmask)
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
#undef TRAP
    struct sock_fprog program = {sizeof(filter) / sizeof(filter[0]), filter};
    r = RAW5(prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    return r < 0 ? (int)r : (int)RAW3(seccomp, SECCOMP_SET_MODE_FILTER, 0, &program);
}
