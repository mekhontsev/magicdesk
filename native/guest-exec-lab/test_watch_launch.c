#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static pid_t children[8];
static unsigned count;
static void cleanup(void) {
    for (unsigned i = 0; i < count; ++i) if (children[i] > 0) kill(children[i], SIGTERM);
    while (waitpid(-1, NULL, 0) > 0 || errno == EINTR) { }
}
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"launch line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static pid_t launch(const char *run, const char *store, const char *mode, int *output) {
    int p[2]; CHECK(!pipe(p));
    pid_t pid = fork(); CHECK(pid >= 0);
    if (!pid) {
        close(p[0]); CHECK(dup2(p[1], STDOUT_FILENO) == STDOUT_FILENO); close(p[1]);
        if (!strcmp(mode, "gio")) {
            execl(run, run, "--statistics", "--store", store, "--home", "/tmp", "--cwd", "/", "--",
                "/bin/watch-gio", "/tmp/shared", (char *)NULL);
            _exit(127);
        }
        execl(run, run, "--store", store, "--home", "/tmp", "--cwd", "/", "--", "/bin/watch", "shared", mode,
            "/tmp/shared", (char *)NULL);
        _exit(127);
    }
    close(p[1]); *output = p[0]; children[count++] = pid;
    return pid;
}
static void complete(pid_t pid, int output) {
    char bytes[512]; ssize_t n;
    while ((n = read(output, bytes, sizeof(bytes))) > 0) CHECK(write(STDOUT_FILENO, bytes, (size_t)n) == n);
    CHECK(!n); close(output);
    int status; CHECK(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && !WEXITSTATUS(status));
    for (unsigned i = 0; i < count; ++i) if (children[i] == pid) children[i] = 0;
}
static void ready(int output) {
    const char *expected = "READY\n";
    for (unsigned i = 0; expected[i]; ++i) {
        struct pollfd p = {output, POLLIN, 0};
        /* EVENT_WAIT: observer publishes successful watch registration; expiry fails. */
        CHECK(poll(&p, 1, 5000) == 1);
        char c; CHECK(read(output, &c, 1) == 1 && c == expected[i]);
    }
}
int main(int argc, char **argv) {
    CHECK(argc == 3 || (argc == 4 && !strcmp(argv[3], "gio"))); CHECK(!atexit(cleanup));
    int output;
    pid_t pid;
    if (argc == 4) {
        int monitor;
        pid_t gio = launch(argv[1], argv[2], "gio", &monitor); ready(monitor);
        pid = launch(argv[1], argv[2], "writer", &output); complete(pid, output);
        complete(gio, monitor); return 0;
    }
    pid = launch(argv[1], argv[2], "setup", &output); complete(pid, output);
    int first, second;
    pid_t a = launch(argv[1], argv[2], "observer", &first); ready(first);
    pid_t b = launch(argv[1], argv[2], "observer", &second); ready(second);
    pid = launch(argv[1], argv[2], "writer", &output); complete(pid, output);
    complete(a, first); complete(b, second);
    puts("PASS two independent watch owners observe a third launch on one store");
    return 0;
}
