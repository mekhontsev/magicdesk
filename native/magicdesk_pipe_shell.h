/* Non-PTY shell transport: input is a command pipe; output frames keep stdout
 * and stderr separate. The relay owns the UNIX session until control EOF. */
#define PIPE_STDOUT 33
#define PIPE_STDERR 34

static int relay_pipe_shell(int input, int output, int error, int notifications) {
    if (nonblocking(STDIN_FILENO) || nonblocking(STDOUT_FILENO)
            || nonblocking(input) || nonblocking(output) || nonblocking(error)) return -1;
    struct relay_buffer pending_input = {0}, pending_output = {0};
    int stdin_open = 1, stdout_open = 1, stderr_open = 1;
    while (stdout_open || stderr_open || pending_output.length) {
        struct pollfd fds[] = {
            {.fd = STDIN_FILENO, .events = pending_input.length ? 0 : POLLIN},
            {.fd = STDOUT_FILENO, .events = pending_output.length ? POLLOUT : 0},
            {.fd = stdin_open ? input : -1, .events = pending_input.length ? POLLOUT : 0},
            {.fd = stdout_open && !pending_output.length ? output : -1, .events = POLLIN},
            {.fd = stderr_open && !pending_output.length ? error : -1, .events = POLLIN},
            {.fd = notifications, .events = POLLIN}
        };
        if (poll(fds, 6, -1) < 0) { if (errno == EINTR) continue; return -1; }
        if (fds[5].revents & POLLIN) {
            struct signalfd_siginfo info;
            if (read(notifications, &info, sizeof(info)) == sizeof(info)
                    && info.ssi_signo != SIGCHLD) return -1;
        }
        if (fds[0].revents & (POLLHUP | POLLERR | POLLNVAL)
                || fds[1].revents & (POLLHUP | POLLERR | POLLNVAL)) return -1;
        if (fds[0].revents & POLLIN) {
            const ssize_t n = read(STDIN_FILENO, pending_input.bytes, RELAY_BUFFER_SIZE);
            if (n == 0) return -1;
            if (n < 0 && errno != EINTR && errno != EAGAIN) return -1;
            if (n > 0) { pending_input.offset = 0; pending_input.length = (size_t) n; }
        }
        if (fds[1].revents & POLLOUT && flush_buffer(STDOUT_FILENO, &pending_output)) return -1;
        if (fds[2].revents & POLLOUT && flush_buffer(input, &pending_input)) return -1;
        if (fds[2].revents & (POLLHUP | POLLERR)) { stdin_open = 0; pending_input.length = 0; }
        for (int i = 3; i <= 4 && !pending_output.length; i++) {
            if (!(fds[i].revents & (POLLIN | POLLHUP))) continue;
            uint8_t bytes[RELAY_BUFFER_SIZE];
            const ssize_t n = read(fds[i].fd, bytes, sizeof(bytes));
            if (n < 0 && errno != EINTR && errno != EAGAIN) return -1;
            if (n == 0) { if (i == 3) stdout_open = 0; else stderr_open = 0; }
            if (n > 0 && queue_frame(&pending_output, i == 3 ? PIPE_STDOUT : PIPE_STDERR,
                    bytes, (uint32_t) n)) return -1;
        }
    }
    return 0;
}

static int pipe_shell_command(const char *shell) {
    sigset_t signals, previous;
    sigemptyset(&signals);
    sigaddset(&signals, SIGTERM);
    sigaddset(&signals, SIGHUP);
    sigaddset(&signals, SIGCHLD);
    if (sigprocmask(SIG_BLOCK, &signals, &previous)) return 1;
    const int notifications = signalfd(-1, &signals, SFD_CLOEXEC | SFD_NONBLOCK);
    if (notifications < 0) return 1;
    (void) signal(SIGPIPE, SIG_IGN);
    int pipes[3][2] = {{-1, -1}, {-1, -1}, {-1, -1}};
    int result = 1;
    for (int i = 0; i < 3; i++) {
        if (pipe(pipes[i]) || fcntl(pipes[i][0], F_SETFD, FD_CLOEXEC)
                || fcntl(pipes[i][1], F_SETFD, FD_CLOEXEC)) goto cleanup;
    }
    const pid_t child = fork();
    if (child < 0) goto cleanup;
    if (child == 0) {
        if (setsid() < 0 || dup2(pipes[0][0], STDIN_FILENO) < 0
                || dup2(pipes[1][1], STDOUT_FILENO) < 0
                || dup2(pipes[2][1], STDERR_FILENO) < 0) _exit(126);
        close_child_descriptors(-1);
        (void) sigprocmask(SIG_SETMASK, &previous, NULL);
        (void) signal(SIGPIPE, SIG_DFL);
        execl(shell, shell, (char *) NULL);
        _exit(127);
    }
    close(pipes[0][0]); pipes[0][0] = -1;
    close(pipes[1][1]); pipes[1][1] = -1;
    close(pipes[2][1]); pipes[2][1] = -1;
    (void) relay_pipe_shell(pipes[0][1], pipes[1][0], pipes[2][0], notifications);
    /* Closing a console cancels its jobs, never the peer receiving its output. */
    result = stop_shell(child, pipes[0][1], notifications);
    pipes[0][1] = -1;
cleanup:
    for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++)
        if (pipes[i][j] >= 0) close(pipes[i][j]);
    close(notifications);
    return result;
}
