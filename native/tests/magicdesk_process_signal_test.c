#define main process_signal_main
#include "../magicdesk_process_signal.c"
#undef main
#include <assert.h>
#include <sys/wait.h>

static unsigned long long start_ticks(pid_t pid) {
    char name[64], line[4096], *save;
    snprintf(name, sizeof(name), "/proc/%d/stat", pid);
    FILE *file = fopen(name, "r"); assert(file);
    assert(fgets(line, sizeof(line), file)); fclose(file);
    char *field = strtok_r(strrchr(line, ')') + 1, " ", &save);
    for (int i = 0; i < 19; i++) { assert(field); field = strtok_r(NULL, " ", &save); }
    return strtoull(field, NULL, 10);
}

int main(void) {
    int pipefd[2]; assert(pipe(pipefd) == 0);
    pid_t pid = fork(); assert(pid >= 0);
    if (pid == 0) { close(pipefd[1]); char c; (void) read(pipefd[0], &c, 1); _exit(0); }
    close(pipefd[0]);
    unsigned long long start = start_ticks(pid);
    assert(signal_process(pid, getuid(), start + 1, SIGTERM) < 0 && errno == ESTALE);
    assert(kill(pid, 0) == 0);
    assert(signal_process(pid, getuid() + 1, start, SIGTERM) < 0 && errno == ESTALE);
    assert(signal_process(pid, getuid(), start, SIGSTOP) < 0 && errno == EINVAL);
    if (signal_process(pid, getuid(), start, SIGTERM) != 0) {
        int unavailable = errno;
        kill(pid, SIGKILL); waitpid(pid, NULL, 0); close(pipefd[1]);
        assert(unavailable == ENOSYS || unavailable == EINVAL || unavailable == EBADF);
        puts("Safe signalling unsupported by host kernel; identity checks passed");
        return 0;
    }
    int status; assert(waitpid(pid, &status, 0) == pid);
    assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGTERM);
    close(pipefd[1]);
    puts("Process signal identity fixtures passed");
    return 0;
}
