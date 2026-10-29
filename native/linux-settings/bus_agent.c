#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "bus_agent.h"
#include "bus_wire.h"
#include "../magicdesk_unix_connect.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

typedef struct { int socket, failed; unsigned matches[3]; DBusConnection *bus[3]; MdBusFds fds; } Agent;
static int close_inherited(int keep) {
    DIR *directory = opendir("/proc/self/fd");
    if (!directory) return -1;
    struct dirent *entry;
    while ((entry = readdir(directory))) {
        char *end; long fd = strtol(entry->d_name, &end, 10);
        if (!*end && fd > 2 && fd != keep && fd != dirfd(directory)) close((int)fd);
    }
    closedir(directory); return 0;
}
static int transfer(int fd, void *buffer, size_t length, int writing) {
    unsigned char *p = buffer;
    while (length) {
        ssize_t n = writing ? send(fd, p, length, MSG_NOSIGNAL) : recv(fd, p, length, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n; length -= (size_t)n;
    }
    return 0;
}
static int send_frame(Agent *a, MdBusWire *w) {
    if (w->failed) return -1;
    MdBusWire header = {0}; md_bus_write_u32(&header, (uint32_t)w->size);
    int result = transfer(a->socket, header.data, header.size, 1) || transfer(a->socket, w->data, w->size, 1);
    md_bus_wire_clear(&header); return result ? -1 : 0;
}
static int response(Agent *a, uint32_t id, uint32_t kind, unsigned bus, DBusMessage *message,
        const char *error, const char *detail) {
    MdBusWire w = {0}; uint64_t before = a->fds.next;
    md_bus_write_u32(&w, id); md_bus_write_u32(&w, kind); md_bus_write_u32(&w, bus);
    md_bus_write_text(&w, message ? dbus_message_get_sender(message) : NULL);
    md_bus_write_text(&w, message ? dbus_message_get_path(message) : NULL);
    md_bus_write_text(&w, message ? dbus_message_get_interface(message) : NULL);
    md_bus_write_text(&w, message ? dbus_message_get_member(message) : NULL);
    md_bus_write_text(&w, error); md_bus_write_text(&w, detail);
    int result = 0;
    if (message) result = md_bus_values_write(&w, message, &a->fds);
    else md_bus_write_u32(&w, 0);
    if (result || w.failed) {
        for (unsigned i = 0; i < MD_BUS_FD_LIMIT; ++i) if (a->fds.fd[i] >= 0 && a->fds.serial[i] > before) {
            close(a->fds.fd[i]); a->fds.fd[i] = -1;
        }
        md_bus_wire_clear(&w);
        if (kind == 2) return -1; // Never silently lose an event: disconnect invalidates all observations.
        return response(a, id, 1, bus, NULL, "LIMIT_EXCEEDED", "D-Bus result exceeds transport limits");
    }
    result = send_frame(a, &w); md_bus_wire_clear(&w); return result;
}
static DBusHandlerResult signal_filter(DBusConnection *connection, DBusMessage *message, void *data) {
    Agent *a = data;
    if (dbus_message_get_type(message) != DBUS_MESSAGE_TYPE_SIGNAL) return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
    unsigned bus = 0; while (bus < 3 && a->bus[bus] != connection) ++bus;
    if (bus < 3 && response(a, 0, 2, bus, message, NULL, NULL)) a->failed = 1;
    return DBUS_HANDLER_RESULT_NOT_YET_HANDLED;
}
static DBusConnection *connect_bus(Agent *a, unsigned bus, DBusError *error) {
    if (a->bus[bus]) return a->bus[bus];
    DBusConnection *connection = NULL;
    if (bus < 2) connection = dbus_bus_get_private(bus == 0 ? DBUS_BUS_SESSION : DBUS_BUS_SYSTEM, error);
    else {
        DBusConnection *session = connect_bus(a, 0, error); if (!session) return NULL;
        DBusMessage *request = dbus_message_new_method_call("org.a11y.Bus", "/org/a11y/bus", "org.a11y.Bus", "GetAddress");
        if (!request) { dbus_set_error_const(error, DBUS_ERROR_NO_MEMORY, "Cannot allocate accessibility request"); return NULL; }
        DBusMessage *reply = dbus_connection_send_with_reply_and_block(session, request, 3000, error);
        dbus_message_unref(request); if (!reply) return NULL;
        const char *address = NULL;
        if (dbus_message_get_args(reply, error, DBUS_TYPE_STRING, &address, DBUS_TYPE_INVALID)) {
            connection = dbus_connection_open_private(address, error);
            if (connection && !dbus_bus_register(connection, error)) {
                dbus_connection_close(connection); dbus_connection_unref(connection); connection = NULL;
            }
        }
        dbus_message_unref(reply);
    }
    if (connection) {
        dbus_connection_set_exit_on_disconnect(connection, FALSE);
        dbus_connection_set_max_received_size(connection, MD_BUS_FRAME_LIMIT * 2);
        if (!dbus_connection_add_filter(connection, signal_filter, a, NULL)) {
            dbus_connection_close(connection); dbus_connection_unref(connection);
            dbus_set_error_const(error, DBUS_ERROR_NO_MEMORY, "Cannot observe D-Bus connection"); return NULL;
        }
        a->bus[bus] = connection;
    }
    return connection;
}
static int launch(Agent *a, MdBusWire *w, uint32_t id) {
    char *argv[257] = {0}, *env[33] = {0}; int result = -1;
    uint32_t argc = md_bus_read_u32(w), envc = 0;
    if (!argc || argc > 256) goto done;
    for (unsigned i = 0; i < argc; ++i) if (!(argv[i] = md_bus_read_text(w))) goto done;
    envc = md_bus_read_u32(w); if (envc > 32) goto done;
    for (unsigned i = 0; i < envc; ++i) {
        env[i] = md_bus_read_text(w);
        if (!env[i] || !strchr(env[i], '=') || env[i][0] == '=') goto done;
    }
    if (w->failed || w->at != w->size || !*argv[0]) goto done;
    int pipefd[2]; if (pipe2(pipefd, O_CLOEXEC)) goto done;
    pid_t pid = fork();
    if (!pid) {
        close(pipefd[0]);
        struct sigaction action = {.sa_handler = SIG_DFL}; sigemptyset(&action.sa_mask);
        sigaction(SIGTERM, &action, NULL); sigaction(SIGCHLD, &action, NULL);
        if (close_inherited(pipefd[1])) {
            int error = errno; (void)write(pipefd[1], &error, sizeof(error)); _exit(127);
        }
        unsetenv("MAGICDESK_AUTOMATION_TOKEN"); unsetenv("MAGICDESK_AUTOMATION_SOCKET"); unsetenv("MAGICDESK_AUTOMATION_HELPER");
        for (unsigned i = 0; i < envc; ++i) {
            char *equal = strchr(env[i], '='); *equal = 0;
            if (setenv(env[i], equal + 1, 1)) {
                int error = errno; (void)write(pipefd[1], &error, sizeof(error)); _exit(127);
            }
        }
        execvp(argv[0], argv);
        int error = errno; (void)write(pipefd[1], &error, sizeof(error)); _exit(127);
    }
    close(pipefd[1]);
    if (pid < 0) { close(pipefd[0]); goto done; }
    struct pollfd event = {.fd = pipefd[0], .events = POLLIN};
    // EVENT_WAIT: exec closes the error pipe; deadline cancels only this newly created command.
    int ready; do { ready = poll(&event, 1, 5000); } while (ready < 0 && errno == EINTR);
    int error = 0; ssize_t n = ready > 0 ? read(pipefd[0], &error, sizeof(error)) : -1;
    close(pipefd[0]);
    if (n != 0) {
        if (n < 0) kill(pid, SIGKILL);
        result = response(a, id, 1, 0, NULL, "LAUNCH_FAILED", n > 0 ? strerror(error) : "Launch deadline exceeded");
    } else {
        DBusMessage *message = dbus_message_new_signal("/org/magicdesk/automation", "org.magicdesk.Automation", "Launched");
        dbus_uint32_t value = (dbus_uint32_t)pid;
        if (message && dbus_message_append_args(message, DBUS_TYPE_UINT32, &value, DBUS_TYPE_INVALID))
            result = response(a, id, 0, 0, message, NULL, NULL);
        if (message) dbus_message_unref(message);
    }
done:
    for (unsigned i = 0; i < 257; ++i) free(argv[i]);
    for (unsigned i = 0; i < 33; ++i) free(env[i]);
    if (result == -1) return response(a, id, 1, 0, NULL, "INVALID_ARGUMENT", "Invalid or unavailable Linux launch");
    return result;
}
static int request(Agent *a, MdBusWire *w) {
    uint32_t id = md_bus_read_u32(w), op = md_bus_read_u32(w), bus = md_bus_read_u32(w), timeout = md_bus_read_u32(w);
    if (w->failed || !id || bus >= 3 || timeout < 1 || timeout > 10000) return -1;
    if (op == 6) return launch(a, w, id);
    DBusError error = DBUS_ERROR_INIT;
    DBusMessage *message = NULL, *reply = NULL;
    char *destination = NULL, *path = NULL, *interface = NULL, *member = NULL, *text = NULL;
    int result = 0;
    if (op == 1) {
        destination = md_bus_read_text(w); path = md_bus_read_text(w);
        interface = md_bus_read_text(w); member = md_bus_read_text(w);
        if (!destination || !path || !interface || !member || !dbus_validate_bus_name(destination, NULL)
                || !dbus_validate_path(path, NULL) || !dbus_validate_interface(interface, NULL)
                || !dbus_validate_member(member, NULL)) goto invalid;
        message = dbus_message_new_method_call(destination, path, interface, member);
        if (!message || md_bus_values_read(w, message, &a->fds)) goto invalid;
    } else if (op == 2 || op == 3 || op == 5) {
        text = md_bus_read_text(w); if (!text || strlen(text) > 4096) goto invalid;
        if (op == 2 && a->matches[bus] >= 64) goto invalid;
    } else if (op != 4) goto invalid;
    if (w->failed || w->at != w->size) goto invalid;
    if (op == 5) {
        if (md_bus_fd_release(&a->fds, text)) goto invalid;
    } else if (op == 4) {
        if (a->bus[bus]) { dbus_connection_close(a->bus[bus]); dbus_connection_unref(a->bus[bus]); a->bus[bus] = NULL; a->matches[bus] = 0; }
    } else {
        DBusConnection *connection = connect_bus(a, bus, &error);
        if (connection) {
            if (op == 1) reply = dbus_connection_send_with_reply_and_block(connection, message, (int)timeout, &error);
            else {
                message = dbus_message_new_method_call(DBUS_SERVICE_DBUS, DBUS_PATH_DBUS, DBUS_INTERFACE_DBUS,
                        op == 2 ? "AddMatch" : "RemoveMatch");
                if (!message || !dbus_message_append_args(message, DBUS_TYPE_STRING, &text, DBUS_TYPE_INVALID)) goto invalid;
                reply = dbus_connection_send_with_reply_and_block(connection, message, (int)timeout, &error);
                if (reply) { if (op == 2) ++a->matches[bus]; else if (a->matches[bus]) --a->matches[bus]; }
            }
        }
    }
    result = response(a, id, dbus_error_is_set(&error) ? 1 : 0, bus, reply, error.name, error.message);
    goto done;
invalid:
    result = response(a, id, 1, bus, NULL, "INVALID_ARGUMENT", "Invalid typed D-Bus request");
done:
    free(destination); free(path); free(interface); free(member); free(text);
    if (message) dbus_message_unref(message);
    if (reply) dbus_message_unref(reply);
    dbus_error_free(&error); return result;
}
static int hello(Agent *a) {
    DBusMessage *message = dbus_message_new_signal("/org/magicdesk/automation", "org.magicdesk.Automation", "Ready");
    if (!message) return -1;
    dbus_uint32_t uid = getuid(), pid = getpid();
    char directory[PATH_MAX], pipe_helper[PATH_MAX];
    const char *cwd = getcwd(directory, sizeof(directory));
    const char *shell = getenv("SHELL"), *helper = getenv("MAGICDESK_AUTOMATION_HELPER");
    const char *display = getenv("DISPLAY"), *wayland = getenv("WAYLAND_DISPLAY");
    if (!cwd) cwd = "/";
    if (!shell || *shell != '/' || access(shell, X_OK)) shell = "/bin/sh";
    const char *slash = helper ? strrchr(helper, '/') : NULL;
    int count = slash ? snprintf(pipe_helper, sizeof(pipe_helper), "%.*s/libmagicdesk_pty_bridge.so", (int)(slash - helper), helper) : -1;
    const char *pipe = count > 0 && (size_t)count < sizeof(pipe_helper) ? pipe_helper : "";
    if (!display) display = "";
    if (!wayland) wayland = "";
    int result = -1;
    if (dbus_message_append_args(message, DBUS_TYPE_UINT32, &uid, DBUS_TYPE_UINT32, &pid,
            DBUS_TYPE_STRING, &cwd, DBUS_TYPE_STRING, &shell, DBUS_TYPE_STRING, &pipe,
            DBUS_TYPE_STRING, &display, DBUS_TYPE_STRING, &wayland, DBUS_TYPE_INVALID))
        result = response(a, 0, 3, 0, message, NULL, NULL);
    dbus_message_unref(message); return result;
}
int md_bus_agent_serve(int socket) {
    Agent a = {.socket = socket}; md_bus_fds_init(&a.fds);
    struct timeval timeout = {.tv_sec = 5};
    if (setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))
            || setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))) return 1;
    if (hello(&a)) return 1;
    while (!a.failed) {
        struct pollfd items[4] = {{.fd = socket, .events = POLLIN}};
        int queued = 0;
        for (unsigned i = 0; i < 3; ++i) {
            items[i + 1].fd = -1;
            if (!a.bus[i]) continue;
            if (!dbus_connection_get_is_connected(a.bus[i]) || !dbus_connection_get_unix_fd(a.bus[i], &items[i + 1].fd)) { a.failed = 1; break; }
            items[i + 1].events = POLLIN | (dbus_connection_has_messages_to_send(a.bus[i]) ? POLLOUT : 0);
            queued |= dbus_connection_get_dispatch_status(a.bus[i]) == DBUS_DISPATCH_DATA_REMAINS;
        }
        if (a.failed) break;
        // EVENT_WAIT: command or D-Bus traffic. No idle polling; a partial command has a five-second IO bound.
        int n = poll(items, 4, queued ? 0 : -1);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 || items[0].revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        if (items[0].revents & POLLIN) {
            unsigned char header[4]; MdBusWire h = {.data = header, .size = sizeof(header)};
            if (transfer(socket, header, sizeof(header), 0)) break;
            uint32_t size = md_bus_read_u32(&h);
            if (size < 16 || size > MD_BUS_FRAME_LIMIT) break;
            MdBusWire w = {.data = malloc(size), .size = size, .capacity = size};
            if (!w.data) break;
            int failed = transfer(socket, w.data, size, 0) || request(&a, &w);
            md_bus_wire_clear(&w); if (failed) break;
        }
        for (unsigned i = 0; i < 3 && !a.failed; ++i) if (a.bus[i]) {
            if (!dbus_connection_read_write(a.bus[i], 0)) { a.failed = 1; break; }
            for (unsigned batch = 0; batch < 64 && dbus_connection_get_dispatch_status(a.bus[i]) == DBUS_DISPATCH_DATA_REMAINS; ++batch)
                if (dbus_connection_dispatch(a.bus[i]) == DBUS_DISPATCH_NEED_MEMORY) { a.failed = 1; break; }
        }
    }
    for (unsigned i = 0; i < 3; ++i) if (a.bus[i]) { dbus_connection_close(a.bus[i]); dbus_connection_unref(a.bus[i]); }
    md_bus_fds_clear(&a.fds); return 0;
}
static volatile sig_atomic_t owner_socket = -1;
static void owner_died(int signal) { (void)signal; if (owner_socket >= 0) shutdown(owner_socket, SHUT_RDWR); }
int md_bus_agent_start(void) {
    const char *endpoint = getenv("MAGICDESK_AUTOMATION_SOCKET"), *token = getenv("MAGICDESK_AUTOMATION_TOKEN");
    if (!endpoint && !token) return 0;
    if (!endpoint || !token || strlen(token) != 64 || strlen(endpoint) > 106 || !*endpoint) return -1;
    int socket_fd = md_unix_connect(endpoint);
    if (socket_fd < 0) return -1;
    struct timeval timeout = {.tv_sec = 5};
    unsigned char accepted;
    if (setsockopt(socket_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))
            || setsockopt(socket_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))
            || transfer(socket_fd, (void *)token, 64, 1) || transfer(socket_fd, &accepted, 1, 0) || accepted) {
        close(socket_fd); return -1;
    }
    pid_t parent = getpid(), worker = fork();
    if (worker < 0) { close(socket_fd); return -1; }
    if (!worker) {
        owner_socket = socket_fd;
        struct sigaction action = {.sa_handler = owner_died}; sigemptyset(&action.sa_mask);
        if (sigaction(SIGTERM, &action, NULL) || prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) _exit(1);
        struct sigaction reap = {.sa_handler = SIG_IGN}; sigemptyset(&reap.sa_mask);
        if (sigaction(SIGCHLD, &reap, NULL) || close_inherited(socket_fd)) _exit(1);
        int null = open("/dev/null", O_RDWR | O_CLOEXEC);
        for (int i = 0; i < 3; ++i) { if (null >= 0) dup2(null, i); else close(i); }
        if (null > 2) close(null);
        _exit(md_bus_agent_serve(socket_fd));
    }
    close(socket_fd);
    unsetenv("MAGICDESK_AUTOMATION_SOCKET"); unsetenv("MAGICDESK_AUTOMATION_TOKEN");
    unsetenv("MAGICDESK_AUTOMATION_HELPER");
    return 0;
}
