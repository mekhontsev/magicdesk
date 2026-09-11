#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <limits.h>
#include <netinet/in.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/signalfd.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

/* Keep these values synchronized with the Java PTY protocol classes. */
#define FRAME_DATA 1
#define FRAME_RESIZE 2
#define FRAME_QUERY_CWD 3
#define FRAME_QUERY_FOREGROUND_PROCESS 4
#define FRAME_HELLO 17
#define FRAME_OUTPUT 18
#define FRAME_CWD 19
#define FRAME_FOREGROUND_PROCESS 20
#define FRAME_HEADER_SIZE 5
#define RESIZE_PAYLOAD_SIZE 8
#define MAX_DATA_FRAME (1024U * 1024U)
#define MAX_STARTUP_COMMAND (64U * 1024U)
#define MAX_PROCESS_NAME 512U
#define RELAY_BUFFER_SIZE 8192U
#define CHILD_EXIT_GRACE_MILLIS 500

struct relay_buffer {
    uint8_t bytes[FRAME_HEADER_SIZE + RELAY_BUFFER_SIZE];
    size_t offset;
    size_t length;
};

static int write_all(int fd, const void *buffer, size_t length) {
    const uint8_t *next = buffer;
    while (length > 0) {
        const ssize_t written = write(fd, next, length);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        next += written;
        length -= (size_t) written;
    }
    return 0;
}

static uint32_t decode_u32(const uint8_t *bytes) {
    return ((uint32_t) bytes[0] << 24U)
            | ((uint32_t) bytes[1] << 16U)
            | ((uint32_t) bytes[2] << 8U)
            | (uint32_t) bytes[3];
}

static void encode_u32(uint8_t *bytes, uint32_t value) {
    bytes[0] = (uint8_t) (value >> 24U);
    bytes[1] = (uint8_t) (value >> 16U);
    bytes[2] = (uint8_t) (value >> 8U);
    bytes[3] = (uint8_t) value;
}

static int write_frame(
        int fd, uint8_t type, const void *payload, uint32_t length) {
    uint8_t header[FRAME_HEADER_SIZE];
    header[0] = type;
    encode_u32(header + 1, length);
    if (write_all(fd, header, sizeof(header)) != 0) {
        return -1;
    }
    return length == 0 || write_all(fd, payload, length) == 0 ? 0 : -1;
}

static int queue_frame(
        struct relay_buffer *output, uint8_t type, const void *payload, uint32_t length) {
    if (output->length != 0 || length > RELAY_BUFFER_SIZE) {
        errno = EOVERFLOW;
        return -1;
    }
    output->bytes[0] = type;
    encode_u32(output->bytes + 1, length);
    memcpy(output->bytes + FRAME_HEADER_SIZE, payload, length);
    output->offset = 0;
    output->length = FRAME_HEADER_SIZE + length;
    return 0;
}

static void close_child_descriptors(int preserved_fd) {
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) {
        return;
    }
    const int directory_fd = dirfd(directory);
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        const int fd = atoi(entry->d_name);
        if (fd > STDERR_FILENO
                && fd != directory_fd
                && fd != preserved_fd) {
            close(fd);
        }
    }
    closedir(directory);
}

static int open_shell_pty(
        const char *working_directory,
        const char *shell_path,
        const char *command_shell_path,
        const char *startup_command,
        int login_shell,
        unsigned short rows,
        unsigned short columns,
        pid_t *child_pid) {
    int exec_status[2];
    if (pipe(exec_status) != 0) {
        return -1;
    }
    if (fcntl(exec_status[1], F_SETFD, FD_CLOEXEC) != 0) {
        const int error_number = errno;
        close(exec_status[0]);
        close(exec_status[1]);
        errno = error_number;
        return -1;
    }
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        close(exec_status[0]);
        close(exec_status[1]);
        return -1;
    }
    char slave_name[64];
    if (grantpt(master) != 0
            || unlockpt(master) != 0
            || ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        close(master);
        close(exec_status[0]);
        close(exec_status[1]);
        return -1;
    }

    struct termios terminal_attributes;
    if (tcgetattr(master, &terminal_attributes) == 0) {
        terminal_attributes.c_iflag |= IUTF8;
        terminal_attributes.c_iflag &= ~(IXON | IXOFF);
        (void) tcsetattr(master, TCSANOW, &terminal_attributes);
    }
    const struct winsize size = {
        .ws_row = rows,
        .ws_col = columns,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    if (ioctl(master, TIOCSWINSZ, &size) != 0) {
        close(master);
        close(exec_status[0]);
        close(exec_status[1]);
        return -1;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        close(master);
        close(exec_status[0]);
        close(exec_status[1]);
        return -1;
    }
    if (pid > 0) {
        close(exec_status[1]);
        int child_error = 0;
        int pipe_error = 0;
        uint8_t *next = (uint8_t *) &child_error;
        size_t remaining = sizeof(child_error);
        while (remaining > 0) {
            const ssize_t count = read(exec_status[0], next, remaining);
            if (count == 0) {
                break;
            }
            if (count < 0) {
                if (errno == EINTR) {
                    continue;
                }
                pipe_error = errno;
                break;
            }
            next += count;
            remaining -= (size_t) count;
        }
        close(exec_status[0]);
        if (pipe_error != 0 || remaining != sizeof(child_error)) {
            if (pipe_error != 0 || remaining != 0) {
                child_error = pipe_error == 0 ? EIO : pipe_error;
            }
            (void) kill(-pid, SIGHUP);
            close(master);
            while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {
            }
            errno = child_error == 0 ? EIO : child_error;
            return -1;
        }
        *child_pid = pid;
        return master;
    }

    close(exec_status[0]);
    sigset_t signals;
    sigfillset(&signals);
    (void) sigprocmask(SIG_UNBLOCK, &signals, NULL);
    close(master);
    if (setsid() < 0) {
        const int child_error = errno;
        (void) write_all(
                exec_status[1], &child_error, sizeof(child_error));
        _exit(126);
    }
    const int slave = open(slave_name, O_RDWR);
    if (slave < 0) {
        const int child_error = errno;
        (void) write_all(
                exec_status[1], &child_error, sizeof(child_error));
        _exit(126);
    }
    if (dup2(slave, STDIN_FILENO) < 0
            || dup2(slave, STDOUT_FILENO) < 0
            || dup2(slave, STDERR_FILENO) < 0) {
        const int child_error = errno;
        (void) write_all(
                exec_status[1], &child_error, sizeof(child_error));
        _exit(126);
    }
    close_child_descriptors(exec_status[1]);
    // Binder startup commands arrive after the ready handshake, so cwd setup
    // must succeed even when no command was passed directly to this helper.
    if (chdir(working_directory) != 0) {
        const int child_error = errno;
        perror("chdir");
        (void) write_all(exec_status[1], &child_error, sizeof(child_error));
        _exit(126);
    }
    (void) setenv("PWD", working_directory, 1);
    const char *shell_name = strrchr(shell_path, '/');
    shell_name = shell_name == NULL ? shell_path : shell_name + 1;
    char login_name[PATH_MAX];
    if (login_shell) {
        (void) snprintf(login_name, sizeof(login_name), "-%s", shell_name);
        (void) setenv("TERM", "xterm-256color", 1);
        (void) setenv("COLORTERM", "truecolor", 1);
    } else {
        (void) snprintf(login_name, sizeof(login_name), "%s", shell_name);
    }
    if (startup_command[0] == '\0') {
        const char *bash_rc = getenv("MAGICDESK_BASH_RC");
        if (login_shell && strcmp(shell_name, "bash") == 0 && bash_rc != NULL) {
            execl(shell_path, "bash", "--rcfile", bash_rc, "-i", (char *) NULL);
        } else {
            execl(shell_path, login_name, "-i", (char *) NULL);
        }
    } else {
        static const char interactive_shell[] =
                "\nif [ \"${MAGICDESK_TERMUX_SHELL##*/}\" = bash ] && [ -n \"${MAGICDESK_BASH_RC:-}\" ]; then\n"
                "  exec \"$MAGICDESK_TERMUX_SHELL\" --rcfile \"$MAGICDESK_BASH_RC\" -i\n"
                "else exec \"$MAGICDESK_TERMUX_SHELL\" -i; fi";
        const size_t command_length = strlen(startup_command);
        char *command = malloc(command_length + sizeof(interactive_shell));
        if (command == NULL) {
            const int child_error = errno;
            (void) write_all(
                    exec_status[1], &child_error, sizeof(child_error));
            _exit(126);
        }
        memcpy(command, startup_command, command_length);
        memcpy(
                command + command_length,
                interactive_shell,
                sizeof(interactive_shell));
        if (setenv("MAGICDESK_TERMUX_SHELL", shell_path, 1) != 0) {
            const int child_error = errno;
            free(command);
            (void) write_all(
                    exec_status[1], &child_error, sizeof(child_error));
            _exit(126);
        }
        execl(
                command_shell_path,
                "bash",
                "-lc",
                command,
                (char *) NULL);
        free(command);
    }
    const int child_error = errno;
    perror("exec shell");
    (void) write_all(exec_status[1], &child_error, sizeof(child_error));
    _exit(127);
}

static int queue_working_directory(
        struct relay_buffer *output, pid_t child_pid, int framed_output) {
    if (!framed_output) {
        errno = EPROTO;
        return -1;
    }
    char process_path[64];
    char directory[PATH_MAX];
    (void) snprintf(
            process_path, sizeof(process_path),
            "/proc/%d/cwd", child_pid);
    const ssize_t length = readlink(
            process_path, directory, sizeof(directory) - 1U);
    if (length < 1) {
        return -1;
    }
    directory[length] = '\0';
    return queue_frame(output, FRAME_CWD, directory, (uint32_t) length);
}

static int read_process_name(
        pid_t process_id, char *name, size_t capacity) {
    char process_path[64];
    char executable[PATH_MAX];
    (void) snprintf(
            process_path, sizeof(process_path),
            "/proc/%d/exe", process_id);
    ssize_t length = readlink(
            process_path, executable, sizeof(executable) - 1U);
    if (length > 0) {
        executable[length] = '\0';
        const char *base = strrchr(executable, '/');
        base = base == NULL ? executable : base + 1;
        if (base[0] != '\0') {
            (void) snprintf(name, capacity, "%s", base);
            return name[0] == '\0' ? -1 : 0;
        }
    }

    (void) snprintf(
            process_path, sizeof(process_path),
            "/proc/%d/comm", process_id);
    const int descriptor = open(process_path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) {
        return -1;
    }
    length = read(descriptor, name, capacity - 1U);
    const int read_error = errno;
    close(descriptor);
    if (length < 1) {
        errno = read_error;
        return -1;
    }
    while (length > 0
            && (name[length - 1] == '\n' || name[length - 1] == '\r')) {
        length--;
    }
    name[length] = '\0';
    return length > 0 ? 0 : -1;
}

static int read_process_relationship(
        pid_t process_id, pid_t *parent_process, pid_t *process_group) {
    char process_path[64];
    char status[1024];
    (void) snprintf(
            process_path, sizeof(process_path),
            "/proc/%d/stat", process_id);
    const int descriptor = open(process_path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) {
        return -1;
    }
    const ssize_t length = read(descriptor, status, sizeof(status) - 1U);
    const int read_error = errno;
    close(descriptor);
    if (length < 1) {
        errno = read_error;
        return -1;
    }
    status[length] = '\0';
    const char *command_end = strrchr(status, ')');
    char state = '\0';
    int parent = -1;
    int group = -1;
    if (command_end == NULL
            || sscanf(command_end + 1, " %c %d %d", &state, &parent, &group)
                    != 3
            || group < 1) {
        errno = EPROTO;
        return -1;
    }
    if (parent_process != NULL) {
        *parent_process = (pid_t) parent;
    }
    *process_group = (pid_t) group;
    return 0;
}

static pid_t find_process_group_member(
        pid_t process_group, pid_t preferred_parent) {
    DIR *directory = opendir("/proc");
    if (directory == NULL) {
        return -1;
    }
    pid_t selected = -1;
    pid_t preferred = -1;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        char *end = NULL;
        const long value = strtol(entry->d_name, &end, 10);
        if (entry->d_name[0] == '\0'
                || end == NULL
                || *end != '\0'
                || value < 1
                || value > INT_MAX) {
            continue;
        }
        pid_t candidate_parent = -1;
        pid_t candidate_group = -1;
        if (read_process_relationship(
                (pid_t) value,
                &candidate_parent,
                &candidate_group) == 0
                && candidate_group == process_group) {
            if (selected < 0 || value < selected) {
                selected = (pid_t) value;
            }
            if (candidate_parent == preferred_parent
                    && (preferred < 0 || value < preferred)) {
                preferred = (pid_t) value;
            }
        }
    }
    closedir(directory);
    return preferred > 0 ? preferred : selected;
}

static int queue_foreground_process(
        struct relay_buffer *output,
        int master,
        pid_t shell_process,
        int framed_output) {
    if (!framed_output) {
        errno = EPROTO;
        return -1;
    }
    const pid_t process_group = tcgetpgrp(master);
    if (process_group < 1) {
        const uint8_t unavailable[8] = {0};
        return queue_frame(
                output,
                FRAME_FOREGROUND_PROCESS,
                unavailable,
                sizeof(unavailable));
    }
    char name[MAX_PROCESS_NAME + 1U];
    pid_t process_id = process_group;
    if (process_group == shell_process) {
        const pid_t shell_child = find_process_group_member(
                process_group, shell_process);
        if (shell_child > 0 && shell_child != shell_process) {
            process_id = shell_child;
        }
    }
    if (read_process_name(process_id, name, sizeof(name)) != 0) {
        process_id = find_process_group_member(process_group, -1);
        if (process_id < 1
                || read_process_name(process_id, name, sizeof(name)) != 0) {
            const uint8_t unavailable[8] = {0};
            return queue_frame(
                    output,
                    FRAME_FOREGROUND_PROCESS,
                    unavailable,
                    sizeof(unavailable));
        }
    }
    const size_t name_length = strnlen(name, MAX_PROCESS_NAME);
    if (name_length < 1U || name_length > MAX_PROCESS_NAME) {
        errno = EPROTO;
        return -1;
    }
    uint8_t payload[8U + MAX_PROCESS_NAME];
    encode_u32(payload, (uint32_t) process_id);
    encode_u32(payload + 4U, (uint32_t) process_group);
    memcpy(payload + 8U, name, name_length);
    return queue_frame(
            output,
            FRAME_FOREGROUND_PROCESS,
            payload,
            (uint32_t) (8U + name_length));
}

struct control_frame {
    uint8_t header[FRAME_HEADER_SIZE];
    size_t header_used;
    uint32_t remaining;
    uint8_t resize[RESIZE_PAYLOAD_SIZE];
    size_t resize_used;
};

static int read_control(
        int control_fd, int master, pid_t child, int framed_output,
        struct control_frame *frame, struct relay_buffer *input,
        struct relay_buffer *output) {
    void *destination;
    size_t capacity;
    if (frame->header_used < FRAME_HEADER_SIZE) {
        destination = frame->header + frame->header_used;
        capacity = FRAME_HEADER_SIZE - frame->header_used;
    } else if (frame->header[0] == FRAME_DATA) {
        destination = input->bytes;
        capacity = frame->remaining < RELAY_BUFFER_SIZE
                ? frame->remaining : RELAY_BUFFER_SIZE;
    } else {
        destination = frame->resize + frame->resize_used;
        capacity = RESIZE_PAYLOAD_SIZE - frame->resize_used;
    }
    const ssize_t count = read(control_fd, destination, capacity);
    if (count <= 0) {
        return count < 0 && (errno == EINTR || errno == EAGAIN) ? 1 : (int) count;
    }
    if (frame->header_used < FRAME_HEADER_SIZE) {
        frame->header_used += (size_t) count;
        if (frame->header_used < FRAME_HEADER_SIZE) {
            return 1;
        }
        frame->remaining = decode_u32(frame->header + 1);
        frame->resize_used = 0;
        const uint8_t type = frame->header[0];
        if (type == FRAME_DATA && frame->remaining <= MAX_DATA_FRAME) {
            if (frame->remaining == 0) {
                frame->header_used = 0;
            }
            return 1;
        }
        if (type == FRAME_RESIZE && frame->remaining == RESIZE_PAYLOAD_SIZE) {
            return 1;
        }
        if (type == FRAME_QUERY_CWD && frame->remaining == 0) {
            frame->header_used = 0;
            return queue_working_directory(output, child, framed_output) == 0 ? 1 : -1;
        }
        if (type == FRAME_QUERY_FOREGROUND_PROCESS && frame->remaining == 0) {
            frame->header_used = 0;
            return queue_foreground_process(output, master, child, framed_output) == 0 ? 1 : -1;
        }
        errno = type == FRAME_DATA ? EOVERFLOW : EPROTO;
        return -1;
    }
    frame->remaining -= (uint32_t) count;
    if (frame->header[0] == FRAME_DATA) {
        input->offset = 0;
        input->length = (size_t) count;
    } else {
        frame->resize_used += (size_t) count;
        if (frame->remaining == 0) {
            const uint32_t rows = decode_u32(frame->resize);
            const uint32_t columns = decode_u32(frame->resize + 4);
            if (rows < 2 || rows > UINT16_MAX || columns < 2 || columns > UINT16_MAX) {
                errno = EINVAL;
                return -1;
            }
            const struct winsize size = {
                .ws_row = (unsigned short) rows,
                .ws_col = (unsigned short) columns
            };
            if (ioctl(master, TIOCSWINSZ, &size) != 0) {
                return -1;
            }
        }
    }
    if (frame->remaining == 0) {
        frame->header_used = 0;
    }
    return 1;
}

static int flush_buffer(int fd, struct relay_buffer *buffer) {
    const ssize_t count = write(fd, buffer->bytes + buffer->offset, buffer->length);
    if (count < 0) {
        return errno == EINTR || errno == EAGAIN ? 0 : -1;
    }
    if (count == 0) {
        errno = EIO;
        return -1;
    }
    buffer->offset += (size_t) count;
    buffer->length -= (size_t) count;
    return 0;
}

static int connect_loopback(long port) {
    if (port < 1 || port > UINT16_MAX) {
        errno = EINVAL;
        return -1;
    }
    const int socket_fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (socket_fd < 0) {
        return -1;
    }
    const struct sockaddr_in address = {
        .sin_family = AF_INET,
        .sin_port = htons((uint16_t) port),
        .sin_addr = {.s_addr = htonl(INADDR_LOOPBACK)}
    };
    if (connect(
            socket_fd,
            (const struct sockaddr *) &address,
            sizeof(address)) != 0) {
        close(socket_fd);
        return -1;
    }
    return socket_fd;
}

static int valid_token(const char *token) {
    if (token == NULL || strlen(token) != 64U) {
        return 0;
    }
    for (size_t index = 0; index < 64U; index++) {
        const char value = token[index];
        if (!((value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f'))) {
            return 0;
        }
    }
    return 1;
}

static int nonblocking(int fd) {
    const int flags = fcntl(fd, F_GETFL);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static int relay_pty(
        int control_fd, int output_fd, int master, pid_t child,
        int framed_output, int notifications) {
    if (nonblocking(control_fd) != 0 || nonblocking(output_fd) != 0
            || nonblocking(master) != 0) {
        return -1;
    }
    struct control_frame frame = {0};
    struct relay_buffer input = {0};
    struct relay_buffer output = {0};
    int master_eof = 0;
    int master_write_closed = 0;
    for (;;) {
        if (master_eof && output.length == 0) {
            return 0;
        }
        const int read_control_ready = !master_eof && !master_write_closed
                && input.length == 0 && output.length == 0;
        const int read_master_ready = !master_eof && output.length == 0;
        const int write_master_ready = !master_write_closed && input.length > 0;
        // Only this poll owner reads/writes either direction. A full bounded
        // queue pauses its producer, never the opposite direction or shutdown.
        struct pollfd descriptors[4] = {
            {.fd = control_fd,
                .events = (short) (POLLRDHUP | (read_control_ready ? POLLIN : 0))},
            {.fd = read_master_ready || write_master_ready ? master : -1,
                .events = (short) ((read_master_ready ? POLLIN : 0)
                        | (write_master_ready ? POLLOUT : 0))},
            {.fd = output.length > 0 ? output_fd : -1, .events = POLLOUT},
            {.fd = notifications, .events = POLLIN}
        };
        const int ready = poll(descriptors, 4, -1);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if ((descriptors[3].revents & POLLIN) != 0) {
            struct signalfd_siginfo event;
            if (read(notifications, &event, sizeof(event)) == sizeof(event)
                    && (event.ssi_signo == SIGTERM || event.ssi_signo == SIGHUP)) {
                return 0;
            }
        }
        if ((descriptors[0].revents & (POLLRDHUP | POLLHUP | POLLERR | POLLNVAL)) != 0) {
            return 0;
        }
        if ((descriptors[2].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            return -1;
        }
        if ((descriptors[2].revents & POLLOUT) != 0
                && flush_buffer(output_fd, &output) != 0) {
            return -1;
        }
        if ((descriptors[1].revents & POLLOUT) != 0
                && flush_buffer(master, &input) != 0) {
            if (errno != EIO) {
                return -1;
            }
            master_write_closed = 1;
            input.length = 0;
        }
        if (read_control_ready && output.length == 0
                && (descriptors[0].revents & POLLIN) != 0
                && read_control(control_fd, master, child, framed_output,
                        &frame, &input, &output) <= 0) {
            return 0;
        }
        if (read_master_ready && output.length == 0
                && (descriptors[1].revents & (POLLIN | POLLHUP | POLLERR)) != 0) {
            const size_t prefix = framed_output ? FRAME_HEADER_SIZE : 0;
            const ssize_t count = read(master, output.bytes + prefix, RELAY_BUFFER_SIZE);
            if (count > 0) {
                output.offset = 0;
                output.length = prefix + (size_t) count;
                if (framed_output) {
                    output.bytes[0] = FRAME_OUTPUT;
                    encode_u32(output.bytes + 1, (uint32_t) count);
                }
            } else if (count == 0 || errno == EIO) {
                master_eof = 1;
                master_write_closed = 1;
                input.length = 0;
            } else if (errno != EINTR && errno != EAGAIN) {
                return -1;
            }
        } else if ((descriptors[1].revents & POLLHUP) != 0) {
            // Drain already queued output before observing the master's EOF.
            master_write_closed = 1;
            input.length = 0;
        }
        if ((descriptors[1].revents & POLLNVAL) != 0) {
            return -1;
        }
    }
}

static int64_t monotonic_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return -1;
    }
    return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int await_child_exit(pid_t child, int notifications, int *status) {
    const int64_t started = monotonic_millis();
    if (started < 0) {
        return -1;
    }
    const int64_t deadline = started + CHILD_EXIT_GRACE_MILLIS;
    for (;;) {
        const pid_t result = waitpid(child, status, WNOHANG);
        if (result == child) {
            return 1;
        }
        if (result < 0 && errno != EINTR) {
            return -1;
        }
        const int64_t now = monotonic_millis();
        if (now < 0) {
            return -1;
        }
        if (now >= deadline) {
            return 0;
        }
        // SIGCHLD is blocked and queued in this descriptor before fork, so
        // exit between waitpid and poll cannot lose the wakeup. No state polling.
        struct pollfd event = {.fd = notifications, .events = POLLIN};
        const int ready = poll(&event, 1, (int) (deadline - now));
        if (ready < 0 && errno != EINTR) {
            return -1;
        }
        if (ready > 0) {
            struct signalfd_siginfo info;
            (void) read(notifications, &info, sizeof(info));
        }
    }
}

static int stop_shell(pid_t child, int master, int notifications) {
    (void) kill(-child, SIGHUP);
    close(master);
    int status = 0;
    int exited = await_child_exit(child, notifications, &status);
    if (exited == 0) {
        // Escalate only the owned shell process group after its HUP grace.
        (void) kill(-child, SIGKILL);
        (void) kill(child, SIGKILL);
        exited = await_child_exit(child, notifications, &status);
    }
    if (exited != 1) {
        return 1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status)
            : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 1;
}

int main(int argc, char **argv) {
    const int socket_mode = argc == 10 && strcmp(argv[1], "--socket") == 0;
    if (argc != 4 && !socket_mode) {
        fprintf(stderr,
                "usage: %s ROWS COLUMNS DIRECTORY\n"
                "       %s --socket PORT TOKEN ROWS COLUMNS DIRECTORY SHELL COMMAND_SHELL COMMAND\n",
                argv[0], argv[0]);
        return 2;
    }
    const int argument_offset = socket_mode ? 3 : 0;
    const long rows_value = strtol(argv[argument_offset + 1], NULL, 10);
    const long columns_value = strtol(argv[argument_offset + 2], NULL, 10);
    const char *working_directory = argv[argument_offset + 3];
    const char *shell_path = socket_mode ? argv[7] : "/system/bin/sh";
    const char *command_shell_path = socket_mode ? argv[8] : shell_path;
    const char *startup_command = socket_mode ? argv[9] : "";
    if (rows_value < 2 || rows_value > UINT16_MAX
            || columns_value < 2 || columns_value > UINT16_MAX
            || working_directory[0] != '/'
            || shell_path[0] != '/'
            || command_shell_path[0] != '/'
            || strlen(startup_command) > MAX_STARTUP_COMMAND
            || (socket_mode && !valid_token(argv[3]))) {
        fputs("invalid terminal dimensions or directory\n", stderr);
        return 2;
    }

    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGTERM);
    sigaddset(&signals, SIGHUP);
    sigaddset(&signals, SIGCHLD);
    if (sigprocmask(SIG_BLOCK, &signals, NULL) != 0) {
        return 1;
    }
    const int notifications = signalfd(-1, &signals, SFD_CLOEXEC | SFD_NONBLOCK);
    if (notifications < 0) {
        return 1;
    }
    (void) signal(SIGPIPE, SIG_IGN);

    pid_t child = -1;
    const int master = open_shell_pty(
            working_directory,
            shell_path,
            command_shell_path,
            startup_command,
            socket_mode,
            (unsigned short) rows_value,
            (unsigned short) columns_value,
            &child);
    if (master < 0) {
        perror("open pty");
        close(notifications);
        return 1;
    }
    int control_fd = STDIN_FILENO;
    int output_fd = STDOUT_FILENO;
    if (socket_mode) {
        control_fd = connect_loopback(strtol(argv[2], NULL, 10));
        output_fd = control_fd;
        char hello[96];
        const int hello_length = snprintf(
                hello, sizeof(hello), "%s %d", argv[3], child);
        if (control_fd < 0
                || hello_length < 1
                || (size_t) hello_length >= sizeof(hello)
                || write_frame(
                        output_fd,
                        FRAME_HELLO,
                        hello,
                        (uint32_t) hello_length) != 0) {
            (void) stop_shell(child, master, notifications);
            if (control_fd >= 0) {
                close(control_fd);
            }
            close(notifications);
            return 1;
        }
    } else if (dprintf(
            STDOUT_FILENO, "MAGICDESK_PTY %d\n", child) < 0) {
        (void) stop_shell(child, master, notifications);
        close(notifications);
        return 1;
    }

    (void) relay_pty(
            control_fd, output_fd, master, child, socket_mode, notifications);

    const int exit_code = stop_shell(child, master, notifications);
    if (socket_mode) {
        close(control_fd);
    }
    close(notifications);
    return exit_code;
}
