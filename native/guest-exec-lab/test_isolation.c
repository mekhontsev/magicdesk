#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <linux/capability.h>
#include <linux/landlock.h>
#include <linux/sched.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void reap(pid_t pid) {
    int status;
    /* EVENT_WAIT: exact fixture-child exit; the runner's deadline cancels a hang. */
    while (waitpid(pid, &status, 0) < 0) assert(errno == EINTR);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static void namespace_check(const char *name, unsigned long flags) {
    pid_t owner = fork(); assert(owner >= 0);
    if (!owner) {
        errno = 0;
        long result = syscall(SYS_unshare, flags);
        printf("CAPABILITY unshare name=%s flags=%#lx result=%ld errno=%d\n", name, flags, result, errno);
        _exit(0);
    }
    reap(owner);
    errno = 0;
    long child = syscall(SYS_clone, flags | SIGCHLD, 0, 0, 0, 0);
    if (!child) _exit(0);
    printf("CAPABILITY clone name=%s flags=%#lx result=%ld errno=%d\n", name, flags, child, errno);
    if (child > 0) reap(child);
    struct clone_args args = {.flags = flags, .exit_signal = SIGCHLD};
    errno = 0;
    child = syscall(SYS_clone3, &args, sizeof(args));
    if (!child) _exit(0);
    printf("CAPABILITY clone3 name=%s flags=%#lx result=%ld errno=%d\n", name, flags, child, errno);
    if (child > 0) reap(child);
}
int main(void) {
    assert(getuid() == 2000 && geteuid() == 2000);
    setvbuf(stdout, NULL, _IONBF, 0);
    struct __user_cap_header_struct header = {.version = _LINUX_CAPABILITY_VERSION_3};
    struct __user_cap_data_struct caps[2] = {0};
    assert(!syscall(SYS_capget, &header, caps));
    printf("IDENTITY uid=%u euid=%u effective=%08x%08x permitted=%08x%08x nnp=%d seccomp=%d\n",
        getuid(), geteuid(), caps[1].effective, caps[0].effective, caps[1].permitted,
        caps[0].permitted, prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0), prctl(PR_GET_SECCOMP));
    assert(!(caps[0].effective | caps[1].effective | caps[0].permitted | caps[1].permitted));
    namespace_check("user", CLONE_NEWUSER);
    namespace_check("pid", CLONE_NEWPID);
    namespace_check("net", CLONE_NEWNET);
    namespace_check("mount", CLONE_NEWNS);
    namespace_check("user+pid+net", CLONE_NEWUSER | CLONE_NEWPID | CLONE_NEWNET);
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        errno = 0;
        long result = syscall(SYS_chroot, "/proc/self/fdinfo");
        printf("CAPABILITY chroot result=%ld errno=%d\n", result, errno);
        _exit(0);
    }
    reap(child);
    child = fork(); assert(child >= 0);
    if (!child) {
        errno = 0;
        long result = syscall(SYS_setresuid, 0, 0, 0);
        printf("CAPABILITY setresuid-root result=%ld errno=%d uid=%u\n", result, errno, getuid());
        _exit(0);
    }
    reap(child);
    errno = 0;
    long result = syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    printf("CAPABILITY landlock-abi result=%ld errno=%d\n", result, errno);
    unsigned actions[] = {SECCOMP_RET_USER_NOTIF, SECCOMP_RET_TRACE};
    for (unsigned i = 0; i < sizeof(actions) / sizeof(*actions); i++) {
        errno = 0;
        result = syscall(SYS_seccomp, SECCOMP_GET_ACTION_AVAIL, 0, &actions[i]);
        printf("CAPABILITY seccomp-action=%#x result=%ld errno=%d\n", actions[i], result, errno);
    }
    puts("Observations complete; no namespace or sandbox result was emulated");
    return 0;
}
