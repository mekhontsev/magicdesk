#define main bridge_main
#include "../magicdesk_pty_bridge.c"
#undef main
#include <assert.h>
#include <sys/prctl.h>

struct fixture { pid_t pid; int input; int output; };

static struct fixture start(void) {
    int input[2], output[2];
    assert(pipe(input) == 0 && pipe(output) == 0);
    const pid_t pid = fork();
    assert(pid >= 0);
    if (pid == 0) {
        assert(dup2(input[0], 0) == 0 && dup2(output[1], 1) == 1);
        close_child_descriptors(-1);
        _exit(pipe_shell_command(access("/system/bin/sh", X_OK) == 0 ? "/system/bin/sh" : "/bin/sh"));
    }
    close(input[0]); close(output[1]);
    return (struct fixture) {pid, input[1], output[0]};
}

static void read_exact(int fd, void *buffer, size_t size) {
    uint8_t *bytes = buffer;
    while (size) {
        const ssize_t count = read(fd, bytes, size);
        assert(count > 0);
        bytes += count; size -= (size_t) count;
    }
}

static size_t frame(int fd, int *kind, uint8_t *bytes) {
    uint8_t header[5];
    read_exact(fd, header, sizeof(header));
    *kind = header[0];
    assert(*kind == PIPE_STDOUT || *kind == PIPE_STDERR);
    const size_t size = decode_u32(header + 1);
    assert(size > 0 && size <= RELAY_BUFFER_SIZE);
    read_exact(fd, bytes, size);
    return size;
}

static int stop(struct fixture f) {
    close(f.input);
    int status;
    assert(waitpid(f.pid, &status, 0) == f.pid && WIFEXITED(status));
    close(f.output);
    return WEXITSTATUS(status);
}

int main(void) {
    // Retain failed fixture jobs so an assertion cannot leave a busy orphan.
    assert(prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) == 0);
    struct fixture f = start();
    const char *command = "export SAVED=kept; cd /; printf 'a\\000\\377OUTEND'; printf 'diagnosticERREND' >&2\n";
    assert(write_all(f.input, command, strlen(command)) == 0);
    uint8_t bytes[RELAY_BUFFER_SIZE], stdout_bytes[128], stderr_bytes[128];
    size_t out = 0, err = 0;
    while (out < 9 || err < 16) {
        int kind;
        const size_t size = frame(f.output, &kind, bytes);
        if (kind == PIPE_STDOUT) { assert(out + size <= 128); memcpy(stdout_bytes + out, bytes, size); out += size; }
        else { assert(err + size <= 128); memcpy(stderr_bytes + err, bytes, size); err += size; }
    }
    assert(out == 9 && memcmp(stdout_bytes, "a\000\377OUTEND", 9) == 0);
    assert(err == 16 && memcmp(stderr_bytes, "diagnosticERREND", 16) == 0);
    command = "printf '%s:%s' \"$SAVED\" \"$PWD\"\n";
    assert(write_all(f.input, command, strlen(command)) == 0);
    int kind;
    const size_t size = frame(f.output, &kind, bytes);
    assert(kind == PIPE_STDOUT && size == 6 && memcmp(bytes, "kept:/", 6) == 0);
    for (int attempt = 0; attempt < 8; attempt++) {
        if (attempt != 0) f = start();
        // Install ignored HUP before fork so every iteration needs SIGKILL.
        command = "trap '' HUP; (while :; do :; done) & printf '%s\\n' $!; wait\n";
        assert(write_all(f.input, command, strlen(command)) == 0);
        const size_t pid_length = frame(f.output, &kind, bytes);
        bytes[pid_length] = 0;
        const pid_t busy = (pid_t) strtol((char *) bytes, NULL, 10);
        assert(busy > 0);
        const int exit_code = stop(f);
        struct process_relationship process;
        const int observed = read_process_relationship(busy, &process);
        const int gone = observed == 0 ? process.state == 'Z' || process.state == 'X'
                : errno == ENOENT || errno == ESRCH;
        if (!gone) (void) kill(busy, SIGKILL);
        int status;
        pid_t reaped;
        do { reaped = waitpid(busy, &status, 0); } while (reaped < 0 && errno == EINTR);
        assert(reaped == busy);
        // Assert the state at relay completion, before the fixture reaps the job.
        assert(gone && exit_code == 128 + SIGKILL);
    }

    f = start();
    command = "while :; do printf 'fill stdout'; printf 'fill stderr' >&2; done\n";
    assert(write_all(f.input, command, strlen(command)) == 0);
    (void) frame(f.output, &kind, bytes);
    // No more reads: control EOF must still cancel a backpressured writer.
    stop(f);
    puts("Pipe shell: binary channels, persistent environment and cancellation under pressure verified");
    return 0;
}
