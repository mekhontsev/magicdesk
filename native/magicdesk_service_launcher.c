#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/capability.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* No ART/Binder threads exist here. Identity is established before exec. */
static int enter_shell(void) {
    const gid_t groups[] = {1004, 1007, 1011, 1015, 1028, 1078, 1079,
                           3001, 3002, 3003, 3006, 3009, 3011, 3012};
    int context = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
    if (context < 0) return -1;
    if (setgroups(sizeof(groups) / sizeof(groups[0]), groups) < 0
            || setresgid(2000, 2000, 2000) < 0
            || prctl(PR_SET_KEEPCAPS, 0L, 0L, 0L, 0L) < 0) goto fail;
    for (unsigned cap = 0; ; ++cap) {
        int present = prctl(PR_CAPBSET_READ, cap, 0L, 0L, 0L);
        if (present < 0) {
            if (errno == EINVAL) break;
            goto fail;
        }
        if (present && prctl(PR_CAPBSET_DROP, cap, 0L, 0L, 0L) < 0) goto fail;
    }
    if (setresuid(2000, 2000, 2000) < 0) goto fail;
    struct __user_cap_header_struct header = {_LINUX_CAPABILITY_VERSION_3, 0};
    struct __user_cap_data_struct caps[2] = {{0}, {0}};
    if (syscall(SYS_capset, &header, caps) < 0) goto fail;
    const char domain[] = "u:r:shell:s0";
    if (write(context, domain, sizeof(domain)) != (ssize_t)sizeof(domain)) goto fail;
    close(context);
    return 0;
fail:
    {
        int saved = errno;
        close(context);
        errno = saved;
        return -1;
    }
}

static void child_failed(int fd) {
    int failure = errno;
    (void)!write(fd, &failure, sizeof(failure));
    _exit(1);
}

static long long monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return -1;
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

int main(int argc, char **argv) {
    if (argc != 7 || argv[1][0] != '/'
            || (strcmp(argv[2], "COMMAND") && strcmp(argv[2], "UPDATE"))
            || (strcmp(argv[5], "0") && strcmp(argv[5], "2000"))
            || (getuid() != 0 && getuid() != 2000)
            || (getuid() != 0 && !strcmp(argv[5], "0"))) {
        fputs("invalid privileged service launch\n", stderr);
        return 1;
    }
    int status[2];
    if (pipe2(status, O_CLOEXEC) < 0) { perror("pipe"); return 1; }
    pid_t child = fork();
    if (child < 0) { perror("fork"); return 1; }
    if (!child) {
        close(status[0]);
        if (setsid() < 0) child_failed(status[1]);
        int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd < 0) child_failed(status[1]);
        for (int fd = 0; fd <= 2; ++fd) {
            if (dup2(null_fd, fd) < 0) child_failed(status[1]);
        }
        if (null_fd > 2) close(null_fd);
        if (!strcmp(argv[5], "2000") && getuid() == 0 && enter_shell() < 0) child_failed(status[1]);
        if (setenv("CLASSPATH", argv[1], 1) < 0) child_failed(status[1]);
        char *args[] = {"/system/bin/app_process", "/system/bin", argv[6],
                       "io.github.mekhontsev.magicdesk.ShellServiceProcess",
                       argv[2], argv[3], argv[4], argv[5], NULL};
        execv(args[0], args);
        child_failed(status[1]);
    }
    close(status[1]);
    /* CLOEXEC EOF confirms exec; failures carry errno. Bound only this bootstrap handshake. */
    struct pollfd event = {status[0], POLLIN | POLLHUP, 0};
    const long long started = monotonic_millis();
    int failure = ETIMEDOUT;
    ssize_t count = -1;
    while (started >= 0) {
        const long long now = monotonic_millis();
        if (now < 0 || now - started >= 10000) break;
        int ready = poll(&event, 1, (int)(10000 - (now - started)));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) break;
        count = read(status[0], &failure, sizeof(failure));
        if (count < 0 && errno == EINTR) continue;
        break;
    }
    close(status[0]);
    if (count != 0) {
        kill(child, SIGKILL);
        waitpid(child, NULL, 0);
        fprintf(stderr, "privileged service bootstrap failed: %s\n", strerror(failure));
        return 1;
    }
    printf("MAGICDESK_PID=%d\n", child);
    return 0;
}
