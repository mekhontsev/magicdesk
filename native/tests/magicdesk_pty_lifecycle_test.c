/* Host-only fixture. The test executable is the PTY child, never a user shell. */
#define main magicdesk_pty_bridge_main
#include "../magicdesk_pty_bridge.c"
#undef main

#include <assert.h>
#include <sys/prctl.h>
#include <sys/signalfd.h>
#include <time.h>

#define PRESSURE_BYTES (512U * 1024U)

static int fixture_read_exact(int fd, void *buffer, size_t length) {
    uint8_t *next = buffer;
    while (length > 0) {
        const ssize_t count = read(fd, next, length);
        if (count <= 0) {
            if (count < 0 && errno == EINTR) {
                continue;
            }
            return count == 0 ? 0 : -1;
        }
        next += count;
        length -= (size_t) count;
    }
    return 1;
}

static int64_t fixture_millis(void) {
    struct timespec now;
    assert(clock_gettime(CLOCK_MONOTONIC, &now) == 0);
    return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int probe_jobs(void) {
    int ready[2];
    assert(pipe(ready) == 0);
    pid_t jobs[3];
    for (size_t i = 0; i < 3; i++) {
        jobs[i] = fork();
        assert(jobs[i] >= 0);
        if (jobs[i] == 0) {
            close(ready[0]);
            if (i == 2) {
                assert(setsid() == getpid());
            } else {
                assert(setpgid(0, 0) == 0);
            }
            signal(SIGHUP, SIG_IGN);
            assert(write_all(ready[1], "R", 1) == 0);
            close(ready[1]);
            for (;;) { pause(); }
        }
    }
    close(ready[1]);
    char ready_bytes[3];
    assert(fixture_read_exact(ready[0], ready_bytes, sizeof(ready_bytes)) == 1);
    close(ready[0]);
    assert(tcsetpgrp(STDIN_FILENO, jobs[0]) == 0);
    // The second job is stopped, so HUP alone cannot deliver its handler.
    assert(kill(jobs[1], SIGSTOP) == 0);
    int status;
    assert(waitpid(jobs[1], &status, WUNTRACED) == jobs[1] && WIFSTOPPED(status));
    uint8_t payload[12];
    for (size_t i = 0; i < 3; i++) { encode_u32(payload + 4 * i, (uint32_t) jobs[i]); }
    assert(write_all(STDOUT_FILENO, payload, sizeof(payload)) == 0);
    for (;;) { pause(); }
}

static int probe(void) {
    struct termios attributes;
    assert(tcgetattr(STDIN_FILENO, &attributes) == 0);
    cfmakeraw(&attributes);
    assert(tcsetattr(STDIN_FILENO, TCSANOW, &attributes) == 0);
    const char *mode = getenv("MAGICDESK_PTY_FIXTURE");
    if (strncmp(mode, "jobs", 4) == 0) { return probe_jobs(); }
    const int ignore_hup = strcmp(mode, "hup") == 0
            || strcmp(mode, "signal") == 0 || strcmp(mode, "oversized") == 0;
    if (ignore_hup) {
        signal(SIGHUP, SIG_IGN);
    }
    assert(write_all(STDOUT_FILENO, "R", 1) == 0);
    if (ignore_hup) {
        for (;;) {
            pause();
        }
    }
    uint8_t byte;
    assert(fixture_read_exact(STDIN_FILENO, &byte, 1) == 1 && byte == 'T');
    if (strcmp(mode, "metadata") == 0) {
        struct winsize size;
        assert(ioctl(STDIN_FILENO, TIOCGWINSZ, &size) == 0);
        assert(size.ws_row == 12 && size.ws_col == 34);
        assert(write_all(STDOUT_FILENO, "D", 1) == 0);
        return 0;
    }
    uint8_t buffer[8192];
    memset(buffer, 'O', sizeof(buffer));
    for (size_t sent = 0; sent < PRESSURE_BYTES; sent += sizeof(buffer)) {
        assert(write_all(STDOUT_FILENO, buffer, sizeof(buffer)) == 0);
    }
    for (size_t received = 0; received < PRESSURE_BYTES; received += sizeof(buffer)) {
        assert(fixture_read_exact(STDIN_FILENO, buffer, sizeof(buffer)) == 1);
        for (size_t i = 0; i < sizeof(buffer); i++) {
            assert(buffer[i] == 'I');
        }
    }
    assert(write_all(STDOUT_FILENO, "D", 1) == 0);
    return 0;
}

static int wait_bridge(pid_t bridge, int notifications, int *status, int timeout) {
    const int64_t deadline = fixture_millis() + timeout;
    for (;;) {
        const pid_t result = waitpid(bridge, status, WNOHANG);
        if (result == bridge) {
            return 1;
        }
        assert(result == 0 || (result < 0 && errno == EINTR));
        const int64_t remaining = deadline - fixture_millis();
        if (remaining <= 0) {
            return 0;
        }
        struct pollfd event = {.fd = notifications, .events = POLLIN};
        if (poll(&event, 1, (int) remaining) > 0) {
            struct signalfd_siginfo info;
            assert(read(notifications, &info, sizeof(info)) == sizeof(info));
        }
    }
}

static int receive_frame(int fd, uint8_t expected, uint8_t *payload, size_t capacity) {
    uint8_t header[FRAME_HEADER_SIZE];
    if (fixture_read_exact(fd, header, sizeof(header)) != 1) {
        return -1;
    }
    const uint32_t length = decode_u32(header + 1);
    if (header[0] != expected || length >= capacity
            || fixture_read_exact(fd, payload, length) != 1) {
        return -1;
    }
    payload[length] = '\0';
    return (int) length;
}

static int pressure(int fd, int fragmented) {
    uint8_t *input = malloc(FRAME_HEADER_SIZE + 1U + PRESSURE_BYTES);
    assert(input != NULL);
    input[0] = FRAME_DATA;
    encode_u32(input + 1, PRESSURE_BYTES + 1U);
    input[FRAME_HEADER_SIZE] = 'T';
    memset(input + FRAME_HEADER_SIZE + 1U, 'I', PRESSURE_BYTES);
    size_t sent = 0;
    if (fragmented) {
        assert(write_all(fd, input, 1) == 0);
        sent = 1;
    }
    uint8_t ready[16];
    if (receive_frame(fd, FRAME_OUTPUT, ready, sizeof(ready)) != 1 || ready[0] != 'R') {
        fprintf(stderr, "FAIL: partial control header prevented initial PTY output\n");
        free(input);
        return 0;
    }
    assert(fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK) == 0);
    const size_t total = FRAME_HEADER_SIZE + 1U + PRESSURE_BYTES;
    size_t received = 0;
    uint8_t header[FRAME_HEADER_SIZE];
    size_t header_used = 0;
    uint32_t payload_remaining = 0;
    const int64_t deadline = fixture_millis() + 3000;
    int ok = 0;
    while (fixture_millis() < deadline) {
        struct pollfd event = {.fd = fd,
                .events = (short) (POLLIN | (sent < total ? POLLOUT : 0))};
        const int result = poll(&event, 1, (int) (deadline - fixture_millis()));
        if (result <= 0) {
            break;
        }
        if ((event.revents & POLLOUT) != 0 && sent < total) {
            const ssize_t count = write(fd, input + sent, total - sent);
            if (count > 0) {
                sent += (size_t) count;
            }
        }
        if ((event.revents & (POLLIN | POLLHUP)) != 0) {
            uint8_t output[8192];
            const ssize_t count = read(fd, output, sizeof(output));
            if (count <= 0) {
                break;
            }
            for (ssize_t i = 0; i < count; i++) {
                if (header_used < sizeof(header)) {
                    header[header_used++] = output[i];
                    if (header_used == sizeof(header)) {
                        assert(header[0] == FRAME_OUTPUT);
                        payload_remaining = decode_u32(header + 1);
                        assert(payload_remaining > 0);
                    }
                } else {
                    assert(output[i] == (received < PRESSURE_BYTES ? 'O' : 'D'));
                    received++;
                    if (--payload_remaining == 0) {
                        header_used = 0;
                    }
                }
            }
        }
        if (sent == total && received == PRESSURE_BYTES + 1U) {
            ok = 1;
            break;
        }
    }
    if (!ok) {
        fprintf(stderr, "FAIL: circular backpressure: sent=%zu/%zu output=%zu/%u\n",
                sent, total, received, PRESSURE_BYTES + 1U);
    }
    free(input);
    return ok;
}

static int metadata(int fd, const char *cwd, pid_t child) {
    uint8_t output[PATH_MAX + 1];
    assert(receive_frame(fd, FRAME_OUTPUT, output, sizeof(output)) == 1 && output[0] == 'R');
    assert(write_frame(fd, FRAME_DATA, "", 0) == 0);
    uint8_t size[RESIZE_PAYLOAD_SIZE];
    encode_u32(size, 12);
    encode_u32(size + 4, 34);
    assert(write_frame(fd, FRAME_RESIZE, size, sizeof(size)) == 0);
    assert(write_frame(fd, FRAME_QUERY_CWD, "", 0) == 0);
    assert(receive_frame(fd, FRAME_CWD, output, sizeof(output)) > 0);
    assert(strcmp((char *) output, cwd) == 0);
    assert(write_frame(fd, FRAME_QUERY_FOREGROUND_PROCESS, "", 0) == 0);
    assert(receive_frame(fd, FRAME_FOREGROUND_PROCESS, output, sizeof(output)) > 8);
    assert(decode_u32(output) == (uint32_t) child);
    assert(decode_u32(output + 4) == (uint32_t) child);
    assert(write_frame(fd, FRAME_DATA, "T", 1) == 0);
    return receive_frame(fd, FRAME_OUTPUT, output, sizeof(output)) == 1 && output[0] == 'D';
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "-i") == 0) {
        return probe();
    }
    assert(argc == 2);
    // Reap fixture jobs even when the production relay has already exited.
    assert(prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) == 0);
    signal(SIGPIPE, SIG_IGN);
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, SIGCHLD);
    assert(sigprocmask(SIG_BLOCK, &mask, NULL) == 0);
    const int notifications = signalfd(-1, &mask, SFD_CLOEXEC);
    assert(notifications >= 0);
    const int server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    assert(server >= 0);
    struct sockaddr_in address = {.sin_family = AF_INET,
            .sin_addr = {.s_addr = htonl(INADDR_LOOPBACK)}};
    assert(bind(server, (struct sockaddr *) &address, sizeof(address)) == 0);
    assert(listen(server, 1) == 0);
    socklen_t size = sizeof(address);
    assert(getsockname(server, (struct sockaddr *) &address, &size) == 0);
    char executable[PATH_MAX];
    const ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1U);
    assert(length > 0);
    executable[length] = '\0';
    char cwd[PATH_MAX];
    assert(getcwd(cwd, sizeof(cwd)) != NULL);
    assert(setenv("MAGICDESK_PTY_FIXTURE", argv[1], 1) == 0);
    const pid_t bridge = fork();
    assert(bridge >= 0);
    if (bridge == 0) {
        close(server);
        close(notifications);
        char port[16];
        snprintf(port, sizeof(port), "%u", ntohs(address.sin_port));
        char *arguments[] = {executable, "--socket", port,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "24", "80", cwd, executable, executable, "", NULL};
        _exit(magicdesk_pty_bridge_main(10, arguments));
    }
    struct pollfd pending = {.fd = server, .events = POLLIN};
    assert(poll(&pending, 1, 3000) == 1);
    const int fd = accept(server, NULL, NULL);
    assert(fd >= 0);
    close(server);
    const struct timeval timeout = {.tv_sec = 3};
    assert(setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0);
    uint8_t hello[128];
    assert(receive_frame(fd, FRAME_HELLO, hello, sizeof(hello)) > 0);
    int child = -1;
    assert(sscanf((char *) hello, "%*s %d", &child) == 1 && child > 0);
    const int hup = strcmp(argv[1], "hup") == 0;
    const int stop_signal = strcmp(argv[1], "signal") == 0;
    const int oversized = strcmp(argv[1], "oversized") == 0;
    const int with_jobs = strncmp(argv[1], "jobs", 4) == 0;
    pid_t jobs[3] = {0};
    int ok;
    if (with_jobs) {
        uint8_t payload[16];
        assert(receive_frame(fd, FRAME_OUTPUT, payload, sizeof(payload)) == 12);
        for (size_t i = 0; i < 3; i++) {
            jobs[i] = (pid_t) decode_u32(payload + 4 * i);
            assert(jobs[i] > 0);
        }
        ok = 1;
        if (strcmp(argv[1], "jobs-signal") == 0) { assert(kill(bridge, SIGTERM) == 0); }
    } else if (hup || stop_signal || oversized) {
        uint8_t ready[16];
        ok = receive_frame(fd, FRAME_OUTPUT, ready, sizeof(ready)) == 1 && ready[0] == 'R';
        if (stop_signal) {
            const uint8_t partial = FRAME_DATA;
            assert(write_all(fd, &partial, 1) == 0);
            assert(kill(bridge, SIGTERM) == 0);
        } else if (oversized) {
            uint8_t header[FRAME_HEADER_SIZE] = {FRAME_DATA};
            encode_u32(header + 1, MAX_DATA_FRAME + 1U);
            assert(write_all(fd, header, sizeof(header)) == 0);
        }
    } else if (strcmp(argv[1], "metadata") == 0) {
        ok = metadata(fd, cwd, child);
    } else {
        ok = pressure(fd, strcmp(argv[1], "fragmented") == 0);
    }
    if (!stop_signal && !oversized && strcmp(argv[1], "jobs-signal") != 0) {
        close(fd);
    }
    int status = 0;
    if (!wait_bridge(bridge, notifications, &status, 2000)) {
        fprintf(stderr, "FAIL: bridge did not terminate after owner EOF%s\n",
                hup ? " with a child ignoring HUP" : "");
        ok = 0;
        kill(-child, SIGKILL);
        kill(bridge, SIGTERM);
        if (!wait_bridge(bridge, notifications, &status, 2000)) {
            kill(bridge, SIGKILL);
            assert(waitpid(bridge, &status, 0) == bridge);
        }
    }
    if (stop_signal || oversized || strcmp(argv[1], "jobs-signal") == 0) {
        close(fd);
    }
    if (with_jobs) {
        for (size_t i = 0; i < 3; i++) {
            int job_status;
            if (i == 2) {
                if (waitpid(jobs[i], &job_status, WNOHANG) != 0) {
                    fprintf(stderr, "FAIL: process in an independent session was terminated\n");
                    ok = 0;
                    continue;
                }
            } else if (wait_bridge(jobs[i], notifications, &job_status, 1000)) {
                continue;
            } else {
                fprintf(stderr, "FAIL: terminal job %d survived PTY teardown\n", jobs[i]);
                ok = 0;
            }
            assert(kill(jobs[i], SIGKILL) == 0);
            assert(wait_bridge(jobs[i], notifications, &job_status, 1000));
        }
    }
    close(notifications);
    if (ok) {
        assert(WIFEXITED(status));
        assert(kill(child, 0) == -1 && errno == ESRCH);
        printf("PASS: %s\n", argv[1]);
    }
    return ok ? 0 : 1;
}
