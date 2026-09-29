#define _GNU_SOURCE
#include "event_wait.h"
#include "raw.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

struct message { char kind; int pid, guard; };
static void wait_failure(void);
static void ready(int fd, short events) {
    /* EVENT_WAIT: fixture handshake/process exit; timeout fails the test, never proves progress. */
    if (md_event_wait_fd(fd, events, md_event_now() + 20000000000LL) != 0) wait_failure();
}

#ifdef MD_GUEST_LIFECYCLE
static void wait_failure(void) { abort(); }
static void report(int socket, char kind, int guard) {
    int fd = RAW2(pidfd_open, getpid(), 0);
    assert(fd >= 0);
    struct message data = {kind, getpid(), guard};
    struct iovec io = {&data, sizeof(data)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1,
                        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    assert(sendmsg(socket, &msg, MSG_NOSIGNAL) == sizeof(data));
    close(fd);
}
int main(int argc, char **argv) {
    assert(argc == 4);
    char environment[32] = {0};
    int environment_fd = open("/etc/md-environment", O_RDONLY);
    assert(environment_fd >= 0);
    assert(read(environment_fd, environment, sizeof(environment)) > 0);
    close(environment_fd);
    assert(!strcmp(environment, argv[3]));
    int socket = atoi(argv[1]), mode = atoi(argv[2]);
    int cancel = mode == 1 || mode == 2 ? mode : 0;
    sigset_t mask;
    assert(sigprocmask(SIG_SETMASK, NULL, &mask) == 0);
    assert(sigismember(&mask, SIGUSR1) && !sigismember(&mask, SIGTERM));
    int rootfd = RAW2(pidfd_open, getpid(), 0), guard = getppid();
    assert(rootfd >= 0);
    report(socket, 'R', guard);
    char command;
    ready(socket, POLLIN);
    assert(read(socket, &command, 1) == 1 && command == 's');
    if (mode == 4) {
        for (int i = 0; i < 32; ++i) {
            pid_t branch = fork();
            assert(branch >= 0);
            if (!branch) {
                assert(setsid() > 0);
                assert(fork() >= 0);
                _exit(0);
            }
        }
    }
    pid_t child = fork();
    assert(child >= 0);
    if (child) {
        if (mode == 3) raise(SIGUSR2);
        _exit(37);
    }
    assert(setsid() > 0);
    if (cancel) assert(signal(SIGTERM, SIG_IGN) != SIG_ERR);
    report(socket, 'C', guard);
    child = fork();
    assert(child >= 0);
    if (child) {
        if (!cancel) _exit(0);
        if (cancel == 2) {
            int status;
            /* EVENT_WAIT: child's SIGSTOP is acknowledged before the driver cancels this tree. */
            assert(waitpid(child, &status, WUNTRACED) == child && WIFSTOPPED(status));
            report(socket, 'S', guard);
        }
        /* EVENT_WAIT: termination fixture intentionally survives until guardian escalation. */
        for (;;) pause();
    }
    ready(rootfd, POLLIN);
    close(rootfd);
    int file = open("/etc/md-guest-fixture", O_RDONLY);
    assert(file >= 0);
    char value[32] = {0};
    assert(read(file, value, sizeof(value)) == 12 && !strcmp(value, "guest-value\n"));
    close(file);
    report(socket, 'L', guard);
    if (cancel == 2) assert(raise(SIGSTOP) == 0);
    ready(socket, POLLIN);
    if (cancel) { /* The test keeps the peer open while the guardian cancels us. */
        _exit(90);
    }
    assert(read(socket, &command, 1) == 1 && command == 'g');
    _exit(0);
}
#else
#include "process_owner.h"
static int known[128], count;
static int track(int fd) { assert(fd >= 0 && count < 128); known[count++] = fd; return fd; }
static void cleanup(void) {
    for (int i = 0; i < count; ++i) md_process_signal(known[i], SIGKILL);
    for (int i = 0; i < count; ++i) {
        md_event_wait_fd(known[i], POLLIN, md_event_now() + 3000000000LL);
        close(known[i]);
    }
    while (waitpid(-1, NULL, WNOHANG | __WALL) > 0) {}
}
static void failure(const char *what, int line) {
    fprintf(stderr, "FAIL lifecycle line %d: %s (errno=%d)\n", line, what, errno);
    exit(1);
}
static void wait_failure(void) { failure("event wait", __LINE__); }
#undef assert
#define assert(x) ((x) ? (void)0 : failure(#x, __LINE__))
struct run { pid_t pid; int fd, socket, guard, service, root, middle, leaf; };
static struct message receive(int socket, int *pidfd) {
    ready(socket, POLLIN);
    struct message data;
    struct iovec io = {&data, sizeof(data)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr msg = {.msg_iov = &io, .msg_iovlen = 1,
                        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    assert(recvmsg(socket, &msg, MSG_CMSG_CLOEXEC) == sizeof(data));
    assert(!(msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    assert(c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS &&
           c->cmsg_len == CMSG_LEN(sizeof(int)));
    memcpy(pidfd, CMSG_DATA(c), sizeof(*pidfd));
    track(*pidfd);
    return data;
}
static int service_fd(pid_t parent, pid_t guard) {
    DIR *proc = opendir("/proc");
    assert(proc);
    int found = -1, children = 0;
    /* Both children are held alive by the root fixture handshake; no concurrent reap. */
    struct dirent *entry;
    while ((entry = readdir(proc))) {
        char *end;
        long pid = strtol(entry->d_name, &end, 10);
        if (*end || pid <= 0 || pid > INT32_MAX) continue;
        char path[128], line[512];
        snprintf(path, sizeof(path), "/proc/%ld/status", pid);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        while (fgets(line, sizeof(line), f)) {
            int ppid;
            if (sscanf(line, "PPid: %d", &ppid) != 1 || ppid != parent) continue;
            ++children;
            if (pid != guard) { assert(found < 0); found = track(RAW2(pidfd_open, pid, 0)); }
            break;
        }
        fclose(f);
    }
    closedir(proc);
    assert(children == 2 && found >= 0);
    return found;
}
static void alive(int fd) {
    struct pollfd item = {fd, POLLIN, 0};
    assert(poll(&item, 1, 0) == 0);
}
static struct run start(const char *runner, const char *store, int mode, const char *environment) {
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) == 0);
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) {
        close(sockets[0]);
        assert(fcntl(sockets[1], F_SETFD, 0) == 0);
        char socket[32], behavior[32];
        snprintf(socket, sizeof(socket), "%d", sockets[1]);
        snprintf(behavior, sizeof(behavior), "%d", mode);
        execl(runner, runner, "--store", store, "--", "/usr/bin/md-lifecycle-fixture", socket, behavior, environment, NULL);
        _exit(126);
    }
    close(sockets[1]);
    struct run run = {.pid = pid, .fd = track(RAW2(pidfd_open, pid, 0)), .socket = sockets[0]};
    struct message data = receive(run.socket, &run.root);
    assert(data.kind == 'R');
    run.guard = track(RAW2(pidfd_open, data.guard, 0));
    run.service = service_fd(pid, data.guard);
    assert(send(run.socket, "s", 1, MSG_NOSIGNAL) == 1);
    data = receive(run.socket, &run.middle);
    assert(data.kind == 'C');
    data = receive(run.socket, &run.leaf);
    assert(data.kind == 'L');
    if (mode == 2) {
        int stopped;
        data = receive(run.socket, &stopped);
        assert(data.kind == 'S');
    }
    ready(run.root, POLLIN);
    alive(run.fd); alive(run.guard); alive(run.service); alive(run.leaf);
    return run;
}
static void finished(struct run *run, int expected) {
    int status;
    ready(run->fd, POLLIN);
    assert(waitpid(run->pid, &status, 0) == run->pid);
    assert(md_process_status(status) == expected);
    ready(run->root, POLLIN); ready(run->middle, POLLIN); ready(run->leaf, POLLIN);
    ready(run->guard, POLLIN); ready(run->service, POLLIN);
    ready(run->socket, POLLIN);
    char extra;
    assert(read(run->socket, &extra, 1) == 0); /* No supervisor retained guest endpoint. */
    close(run->socket);
    while (waitpid(-1, NULL, WNOHANG | __WALL) > 0) {}
}
static void bad_launch(const char *runner, const char *store,
                       const char *program, int expected) {
    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) { execl(runner, runner, "--store", store, "--", program, NULL); _exit(126); }
    int fd = track(RAW2(pidfd_open, pid, 0)), status;
    ready(fd, POLLIN);
    assert(waitpid(pid, &status, 0) == pid && md_process_status(status) == expected);
}
int main(int argc, char **argv) {
    assert(argc == 4 && getuid() == 2000);
    assert(atexit(cleanup) == 0);
    assert(prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) == 0);
    sigset_t mask;
    sigemptyset(&mask); sigaddset(&mask, SIGUSR1);
    assert(sigprocmask(SIG_BLOCK, &mask, NULL) == 0);
    pid_t sentinel = fork();
    assert(sentinel >= 0);
    if (!sentinel) { for (;;) pause(); }
    int sentinel_fd = track(RAW2(pidfd_open, sentinel, 0));
    struct run first = start(argv[1], argv[2], 0, "first");
    struct run second = start(argv[1], argv[3], 0, "second");
    assert(send(first.socket, "g", 1, MSG_NOSIGNAL) == 1);
    finished(&first, 37);
    alive(second.fd); alive(second.leaf); alive(sentinel_fd);
    assert(send(second.socket, "g", 1, MSG_NOSIGNAL) == 1);
    finished(&second, 37);
    puts("PASS lifecycle: double-fork/setsid descendant uses namespace after root exit; root status and concurrent isolation");
    struct run signaled = start(argv[1], argv[2], 3, "first");
    assert(send(signaled.socket, "g", 1, MSG_NOSIGNAL) == 1);
    finished(&signaled, 128 + SIGUSR2);
    for (int i = 0; i < 3; ++i) {
        struct run churn = start(argv[1], argv[2], 4, "first");
        assert(send(churn.socket, "g", 1, MSG_NOSIGNAL) == 1);
        finished(&churn, 37);
    }
    puts("PASS lifecycle: root signal status and 192 rapidly orphaned processes reaped");
    for (int mode = 1; mode <= 2; ++mode) {
        struct run run = start(argv[1], argv[2], mode, "first");
        assert(md_process_signal(run.fd, SIGTERM) == 0);
        finished(&run, 143);
        alive(sentinel_fd);
    }
    puts("PASS lifecycle: TERM escalation, ignored TERM, stopped descendants and unrelated process isolation");
    struct run death = start(argv[1], argv[2], 2, "first");
    assert(md_process_signal(death.fd, SIGKILL) == 0);
    finished(&death, 137);
    alive(sentinel_fd);
    puts("PASS lifecycle: frontend SIGKILL leaves guardian to drain guest tree and stop service");
    struct run broken = start(argv[1], argv[2], 1, "first");
    assert(md_process_signal(broken.service, SIGKILL) == 0);
    finished(&broken, 125);
    alive(sentinel_fd);
    puts("PASS lifecycle: service failure cancels the entire guest tree and reports failure");
    bad_launch(argv[1], argv[2], "/usr/bin/not-installed", 127);
    bad_launch(argv[1], "/tmp/absent-lifecycle-store/nested", "/bin/true", 125);
    assert(md_process_signal(sentinel_fd, SIGKILL) == 0);
    ready(sentinel_fd, POLLIN);
    assert(waitpid(sentinel, NULL, 0) == sentinel);
    int status;
    assert(waitpid(-1, &status, WNOHANG | __WALL) == -1 && errno == ECHILD);
    puts("PASS lifecycle: launch failures and final ECHILD; no retained descendants");
    return 0;
}
#endif
