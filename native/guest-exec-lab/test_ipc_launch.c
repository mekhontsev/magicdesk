#define _GNU_SOURCE
#include <assert.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

static pid_t launch(char **args, int output) {
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) {
        if (output != STDOUT_FILENO) { assert(dup2(output, STDOUT_FILENO) == STDOUT_FILENO); close(output); }
        execv(args[0], args);
        perror("launch IPC fixture"); _exit(126);
    }
    return pid;
}
static void reap(pid_t pid) {
    int status;
    // EVENT_WAIT: exact launcher exit; the enclosing timeout terminates this test tree.
    assert(waitpid(pid, &status, 0) == pid);
    if (!WIFEXITED(status) || WEXITSTATUS(status)) fprintf(stderr, "IPC launch %d status=%#x\n", pid, status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
int main(int argc, char **argv) {
    assert((argc == 3 || argc == 4) && getuid() == 2000);
    char socket[80], memory[80];
    snprintf(socket, sizeof(socket), "/tmp/md-ipc-%d.sock", getpid());
    snprintf(memory, sizeof(memory), "/md-ipc-%d", getpid());
    char *args[] = {argv[1], "--store", argv[2], "--", "/usr/bin/md-ipc-fixture", "serve", socket, memory, NULL};
    int pipefd[2]; assert(!pipe(pipefd));
    pid_t server = launch(args, pipefd[1]); close(pipefd[1]);
    struct pollfd p = {pipefd[0], POLLIN, 0};
    // EVENT_WAIT: server publishes readiness after bind/listen/shm setup, not a startup delay.
    assert(poll(&p, 1, 10000) == 1 && (p.revents & POLLIN));
    FILE *stream = fdopen(pipefd[0], "r"); assert(stream);
    char line[256]; assert(fgets(line, sizeof(line), stream));
    assert(line[0] == 'R' && line[1] == 'E' && line[2] == 'A' && line[3] == 'D' && line[4] == 'Y' && line[5] == '\n');
    if (argc == 4) {
        args[2] = argv[3]; args[5] = "absent";
        reap(launch(args, STDOUT_FILENO));
    }
    args[2] = argv[2]; args[5] = "client";
    pid_t client = launch(args, STDOUT_FILENO);
    reap(client); reap(server);
    while (fgets(line, sizeof(line), stream)) fputs(line, stdout);
    assert(!ferror(stream)); fclose(stream);
    puts("PASS two independent launch supervisors and filesystem services share IPC without a data proxy");
    return 0;
}
