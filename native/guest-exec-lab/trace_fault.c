#define _GNU_SOURCE
#include <assert.h>
#include <elf.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
    assert(argc > 1);
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        execv(argv[1], argv + 1); _exit(127);
    }
    int status; assert(waitpid(child, &status, 0) == child);
    assert(!ptrace(PTRACE_SETOPTIONS, child, 0, PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK |
        PTRACE_O_TRACEVFORK | PTRACE_O_TRACEEXEC | PTRACE_O_EXITKILL));
    assert(!ptrace(PTRACE_CONT, child, 0, 0));
    pid_t pid; int result = 0;
    // EVENT_WAIT: traced process tree exits; caller's timeout bounds a hung tree.
    while ((pid = waitpid(-1, &status, __WALL)) > 0) {
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            if (pid == child) result = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            continue;
        }
        int signal = WSTOPSIG(status);
        unsigned event = (unsigned)status >> 16;
        if (!event && (signal == SIGSEGV || signal == SIGBUS || signal == SIGILL
                || signal == SIGABRT || signal == SIGTRAP)) {
            struct user_pt_regs regs; struct iovec io = {&regs, sizeof(regs)};
            siginfo_t info;
            assert(!ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &io));
            assert(!ptrace(PTRACE_GETSIGINFO, pid, 0, &info));
            fprintf(stderr, "FAULT pid=%d signal=%d code=%d address=%p pc=%llx sp=%llx lr=%llx\n",
                pid, signal, info.si_code, info.si_addr, regs.pc, regs.sp, regs.regs[30]);
            for (unsigned i = 0; i < 31; ++i) fprintf(stderr, "x%u=%llx%c", i, regs.regs[i], i % 4 == 3 ? '\n' : ' ');
            char path[64], line[1024]; snprintf(path, sizeof(path), "/proc/%d/maps", pid);
            FILE *maps = fopen(path, "r");
            if (maps) { while (fgets(line, sizeof(line), maps)) fputs(line, stderr); fclose(maps); }
        }
        assert(!ptrace(PTRACE_CONT, pid, 0, event || signal == SIGSTOP ? 0 : signal));
    }
    return result;
}
