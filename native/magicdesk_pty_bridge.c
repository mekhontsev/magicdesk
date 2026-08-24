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
#include <sys/wait.h>
#include <termios.h>
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

static volatile sig_atomic_t stop_requested;
static volatile sig_atomic_t shell_pid = -1;

static void handle_stop_signal(int signal_number) {
    (void) signal_number;
    stop_requested = 1;
    if (shell_pid > 0) {
        kill(-(pid_t) shell_pid, SIGHUP);
    }
}

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

static int read_exact(int fd, void *buffer, size_t length) {
    uint8_t *next = buffer;
    while (length > 0) {
        const ssize_t count = read(fd, next, length);
        if (count == 0) {
            return 0;
        }
        if (count < 0) {
            if (errno == EINTR) {
                if (stop_requested) {
                    return 0;
                }
                continue;
            }
            return -1;
        }
        next += count;
        length -= (size_t) count;
    }
    return 1;
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
    if (chdir(working_directory) != 0) {
        perror("chdir");
    } else {
        (void) setenv("PWD", working_directory, 1);
    }
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
        execl(shell_path, login_name, "-i", (char *) NULL);
    } else {
        static const char interactive_shell[] =
                "\nexec \"$MAGICDESK_TERMUX_SHELL\" -i";
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

static int send_working_directory(
        int output_fd, pid_t child_pid, int framed_output) {
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
    return write_frame(
            output_fd, FRAME_CWD, directory, (uint32_t) length);
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

static int send_foreground_process(
        int output_fd,
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
        return write_frame(
                output_fd,
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
            return write_frame(
                    output_fd,
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
    return write_frame(
            output_fd,
            FRAME_FOREGROUND_PROCESS,
            payload,
            (uint32_t) (8U + name_length));
}

static int relay_control_frame(
        int control_fd,
        int output_fd,
        int master,
        pid_t child_pid,
        int framed_output) {
    uint8_t header[FRAME_HEADER_SIZE];
    const int header_result = read_exact(control_fd, header, sizeof(header));
    if (header_result <= 0) {
        return header_result;
    }
    const uint32_t length = decode_u32(header + 1);
    if (header[0] == FRAME_DATA) {
        if (length > MAX_DATA_FRAME) {
            errno = EOVERFLOW;
            return -1;
        }
        uint8_t buffer[8192];
        uint32_t remaining = length;
        while (remaining > 0) {
            const size_t chunk = remaining < sizeof(buffer)
                    ? remaining : sizeof(buffer);
            const int result = read_exact(control_fd, buffer, chunk);
            if (result <= 0 || write_all(master, buffer, chunk) != 0) {
                return result == 0 ? 0 : -1;
            }
            remaining -= (uint32_t) chunk;
        }
        return 1;
    }
    if (header[0] == FRAME_RESIZE && length == RESIZE_PAYLOAD_SIZE) {
        uint8_t payload[RESIZE_PAYLOAD_SIZE];
        const int result = read_exact(control_fd, payload, sizeof(payload));
        if (result <= 0) {
            return result;
        }
        const uint32_t rows = decode_u32(payload);
        const uint32_t columns = decode_u32(payload + 4);
        if (rows < 2 || rows > UINT16_MAX
                || columns < 2 || columns > UINT16_MAX) {
            errno = EINVAL;
            return -1;
        }
        const struct winsize size = {
            .ws_row = (unsigned short) rows,
            .ws_col = (unsigned short) columns,
            .ws_xpixel = 0,
            .ws_ypixel = 0
        };
        return ioctl(master, TIOCSWINSZ, &size) == 0 ? 1 : -1;
    }
    if (header[0] == FRAME_QUERY_CWD && length == 0) {
        return send_working_directory(
                output_fd, child_pid, framed_output) == 0 ? 1 : -1;
    }
    if (header[0] == FRAME_QUERY_FOREGROUND_PROCESS && length == 0) {
        return send_foreground_process(
                output_fd,
                master,
                child_pid,
                framed_output) == 0 ? 1 : -1;
    }
    errno = EPROTO;
    return -1;
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

static int relay_pty(
        int control_fd,
        int output_fd,
        int master,
        pid_t child,
        int framed_output) {
    uint8_t output[8192];
    while (!stop_requested) {
        struct pollfd descriptors[2] = {
            {.fd = control_fd, .events = POLLIN},
            {.fd = master, .events = POLLIN}
        };
        const int ready = poll(descriptors, 2, -1);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if ((descriptors[0].revents & (POLLIN | POLLHUP)) != 0) {
            if (relay_control_frame(
                    control_fd,
                    output_fd,
                    master,
                    child,
                    framed_output) <= 0) {
                return 0;
            }
        }
        if ((descriptors[1].revents & (POLLIN | POLLHUP)) != 0) {
            const ssize_t count = read(master, output, sizeof(output));
            if (count <= 0) {
                if (count < 0 && errno == EINTR) {
                    continue;
                }
                return 0;
            }
            const int write_result = framed_output
                    ? write_frame(
                            output_fd,
                            FRAME_OUTPUT,
                            output,
                            (uint32_t) count)
                    : write_all(output_fd, output, (size_t) count);
            if (write_result != 0) {
                return -1;
            }
        }
        if ((descriptors[0].revents & (POLLERR | POLLNVAL)) != 0
                || (descriptors[1].revents & (POLLERR | POLLNVAL)) != 0) {
            return -1;
        }
    }
    return 0;
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

    struct sigaction stop_action;
    memset(&stop_action, 0, sizeof(stop_action));
    stop_action.sa_handler = handle_stop_signal;
    sigemptyset(&stop_action.sa_mask);
    (void) sigaction(SIGTERM, &stop_action, NULL);
    (void) sigaction(SIGHUP, &stop_action, NULL);
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
        return 1;
    }
    shell_pid = child;
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
            (void) kill(-child, SIGHUP);
            close(master);
            if (control_fd >= 0) {
                close(control_fd);
            }
            return 1;
        }
    } else if (dprintf(
            STDOUT_FILENO, "MAGICDESK_PTY %d\n", child) < 0) {
        (void) kill(-child, SIGHUP);
        close(master);
        return 1;
    }

    (void) relay_pty(
            control_fd, output_fd, master, child, socket_mode);

    if (child > 0) {
        (void) kill(-child, SIGHUP);
    }
    close(master);
    if (socket_mode) {
        close(control_fd);
    }
    int status = 0;
    while (child > 0 && waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 1;
}
