#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include "launch_identity.h"

static int execute(const char *supervisor, const char *bootstrap, int denied) {
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) {
        if (denied) {
            struct sock_filter rules[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, denied, 0, 1),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            };
            struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
            assert(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
            assert(syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program) == 0);
        }
        if (bootstrap) execl(supervisor, supervisor, "--deadline-seconds", "10", bootstrap, "--probe", NULL);
        else execl("/system/bin/sh", "sh", "-c", "printf 'independent shell works\\n'", NULL);
        _exit(126);
    }
    int status;
    /* EVENT_WAIT: bounded externally by the fixture timeout; failure never becomes readiness. */
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status));
    return WEXITSTATUS(status);
}

int main(int argc, char **argv) {
    assert(argc == 3 && md_launch_identity(getuid(), geteuid(), getgid(), getegid()));
    assert(md_launch_identity(0, 0, 0, 0));
    assert(md_launch_identity(2000, 2000, 2000, 2000));
    assert(!md_launch_identity(2000, 0, 2000, 0));
    assert(!md_launch_identity(10000, 10000, 10000, 10000));
    for (unsigned i = 0; i < 3; ++i) {
        const int missing[] = {SYS_pidfd_open, SYS_faccessat2, SYS_seccomp};
        assert(execute(argv[1], argv[2], missing[i]) == 126);
        assert(execute(NULL, NULL, missing[i]) == 0);
    }
    assert(execute(argv[1], argv[2], SYS_ptrace) == 125);
    assert(execute(NULL, NULL, SYS_ptrace) == 0);
    assert(execute(argv[1], argv[2], 0) == 0);
    puts("PASS capability isolation: unavailable kernel calls reject guest only; parent and shell unaffected");
    return 0;
}
