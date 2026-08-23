#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define FRAME_DATA 1
#define FRAME_RESIZE 2
#define FRAME_HEADER_SIZE 5
#define RESIZE_PAYLOAD_SIZE 8
#define MAX_DATA_FRAME (1024U * 1024U)

static volatile sig_atomic_t stop_requested;
static volatile sig_atomic_t shell_pid = -1;

static void handle_stop_signal(int signal_number) {
    (void) signal_number;
    stop_requested = 1;
    if (shell_pid > 0) {
        kill((pid_t) shell_pid, SIGHUP);
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

static void close_child_descriptors(void) {
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) {
        return;
    }
    const int directory_fd = dirfd(directory);
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        const int fd = atoi(entry->d_name);
        if (fd > STDERR_FILENO && fd != directory_fd) {
            close(fd);
        }
    }
    closedir(directory);
}

static int open_shell_pty(
        const char *working_directory,
        unsigned short rows,
        unsigned short columns,
        pid_t *child_pid) {
    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        return -1;
    }
    char slave_name[64];
    if (grantpt(master) != 0
            || unlockpt(master) != 0
            || ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        close(master);
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
        return -1;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        close(master);
        return -1;
    }
    if (pid > 0) {
        *child_pid = pid;
        return master;
    }

    sigset_t signals;
    sigfillset(&signals);
    (void) sigprocmask(SIG_UNBLOCK, &signals, NULL);
    close(master);
    if (setsid() < 0) {
        _exit(126);
    }
    const int slave = open(slave_name, O_RDWR);
    if (slave < 0) {
        _exit(126);
    }
    if (dup2(slave, STDIN_FILENO) < 0
            || dup2(slave, STDOUT_FILENO) < 0
            || dup2(slave, STDERR_FILENO) < 0) {
        _exit(126);
    }
    close_child_descriptors();
    if (chdir(working_directory) != 0) {
        perror("chdir");
    }
    (void) setenv("TERM", "xterm-256color", 1);
    (void) setenv("COLORTERM", "truecolor", 1);
    (void) setenv("SHELL", "/system/bin/sh", 1);
    execl("/system/bin/sh", "sh", "-i", (char *) NULL);
    perror("exec /system/bin/sh");
    _exit(127);
}

static int relay_control_frame(int master) {
    uint8_t header[FRAME_HEADER_SIZE];
    const int header_result = read_exact(STDIN_FILENO, header, sizeof(header));
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
            const int result = read_exact(STDIN_FILENO, buffer, chunk);
            if (result <= 0 || write_all(master, buffer, chunk) != 0) {
                return result == 0 ? 0 : -1;
            }
            remaining -= (uint32_t) chunk;
        }
        return 1;
    }
    if (header[0] == FRAME_RESIZE && length == RESIZE_PAYLOAD_SIZE) {
        uint8_t payload[RESIZE_PAYLOAD_SIZE];
        const int result = read_exact(STDIN_FILENO, payload, sizeof(payload));
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
    errno = EPROTO;
    return -1;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s ROWS COLUMNS DIRECTORY\n", argv[0]);
        return 2;
    }
    const long rows_value = strtol(argv[1], NULL, 10);
    const long columns_value = strtol(argv[2], NULL, 10);
    if (rows_value < 2 || rows_value > UINT16_MAX
            || columns_value < 2 || columns_value > UINT16_MAX
            || argv[3][0] != '/') {
        fputs("invalid terminal dimensions or directory\n", stderr);
        return 2;
    }

    struct sigaction stop_action;
    memset(&stop_action, 0, sizeof(stop_action));
    stop_action.sa_handler = handle_stop_signal;
    sigemptyset(&stop_action.sa_mask);
    (void) sigaction(SIGTERM, &stop_action, NULL);
    (void) sigaction(SIGHUP, &stop_action, NULL);

    pid_t child = -1;
    const int master = open_shell_pty(
            argv[3],
            (unsigned short) rows_value,
            (unsigned short) columns_value,
            &child);
    if (master < 0) {
        perror("open pty");
        return 1;
    }
    shell_pid = child;
    if (dprintf(STDOUT_FILENO, "MAGICDESK_PTY %d\n", child) < 0) {
        (void) kill(child, SIGHUP);
        close(master);
        return 1;
    }

    uint8_t output[8192];
    while (!stop_requested) {
        struct pollfd descriptors[2] = {
            {.fd = STDIN_FILENO, .events = POLLIN},
            {.fd = master, .events = POLLIN}
        };
        const int ready = poll(descriptors, 2, -1);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if ((descriptors[0].revents & (POLLIN | POLLHUP)) != 0) {
            if (relay_control_frame(master) <= 0) {
                break;
            }
        }
        if ((descriptors[1].revents & (POLLIN | POLLHUP)) != 0) {
            const ssize_t count = read(master, output, sizeof(output));
            if (count <= 0) {
                if (count < 0 && errno == EINTR) {
                    continue;
                }
                break;
            }
            if (write_all(STDOUT_FILENO, output, (size_t) count) != 0) {
                break;
            }
        }
        if ((descriptors[0].revents & (POLLERR | POLLNVAL)) != 0
                || (descriptors[1].revents & (POLLERR | POLLNVAL)) != 0) {
            break;
        }
    }

    if (child > 0) {
        (void) kill(child, SIGHUP);
    }
    close(master);
    int status = 0;
    while (child > 0 && waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 1;
}
