#define _GNU_SOURCE
#include "broker.h"
#include "fd_stream.h"
#include "anonymous_buffer.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

enum { CLIENTS = 32 };
struct Pair { int fd[2]; MdhFdStream queue[2]; bool write_closed[2]; };
static volatile sig_atomic_t cancel_fd = -1;
static void cancel(int signal) {
    (void)signal;
    int saved = errno;
    if (cancel_fd >= 0) { char byte = 0; (void)write(cancel_fd, &byte, 1); }
    errno = saved;
}
static void disconnect(struct Pair *p) {
    for (int i = 0; i < 2; ++i) {
        if (p->fd[i] >= 0) close(p->fd[i]);
        mdh_stream_clear(&p->queue[i]);
    }
    *p = (struct Pair){.fd = {-1, -1}};
}
static bool peer(int fd, uid_t uid) {
    struct ucred credential;
    socklen_t size = sizeof(credential);
    return getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credential, &size) == 0 && credential.uid == uid;
}
static int connect_to(const char *name, bool abstract, uid_t uid) {
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    size_t n = strlen(name);
    if (!n || n + 1 > sizeof(address.sun_path)) { errno = ENAMETOOLONG; return -1; }
    memcpy(address.sun_path + (abstract ? 1 : 0), name, n + (abstract ? 0 : 1));
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    if (connect(fd, (void *)&address, offsetof(struct sockaddr_un, sun_path) + n + 1) == 0 && peer(fd, uid)) return fd;
    close(fd); errno = EACCES; return -1;
}

/* Session-owned helper, not a renderer or a process supervisor for guest programs. */
int wayland_broker(int argc, char **argv) {
    if (argc != 7 || geteuid() != 0 || strlen(argv[6]) != 64) return 2;
    char *end;
    errno = 0;
    unsigned long value = strtoul(argv[3], &end, 10);
    if (errno || !*argv[3] || *end || value == 0 || value > INT_MAX) return 2;
    uid_t owner = (uid_t)value;
    const char *upstream = argv[2], *label = argv[4], *authorization = argv[6];
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    char directory[sizeof(address.sun_path)], name[32];
    if (upstream[0] != '/' || strlen(upstream) >= sizeof(directory)) return 2;
    strcpy(directory, upstream);
    char *slash = strrchr(directory, '/');
    if (!slash) return 2;
    *slash = 0;
    struct stat st;
    if (lstat(directory, &st) < 0 || !S_ISDIR(st.st_mode) || st.st_uid != owner ||
            (st.st_mode & 0777) != 0755) return 2;
    char parent[sizeof(directory)]; strcpy(parent, directory);
    slash = strrchr(parent, '/');
    if (!slash) return 2;
    *slash = 0;
    if (lstat(parent, &st) < 0 || !S_ISDIR(st.st_mode) || st.st_uid != owner ||
            (st.st_mode & 0777) != 0700) return 2;
    snprintf(name, sizeof(name), "wayland-%ld", (long)getpid());
    if (snprintf(address.sun_path, sizeof(address.sun_path), "%s/%s", directory, name) >= (int)sizeof(address.sun_path)) return 2;

    int status = 1, control = -1, listener = -1, signals[2] = {-1, -1};
    bool bound = false, admitted = false;
    struct Pair *pairs = NULL;
    MdhFdStream hello = {0};
    MdhBufferAccess access;
    control = connect_to(argv[5], true, owner);
    if (control < 0) goto done;
    int check = connect_to(upstream, false, owner);
    if (check < 0) goto done;
    close(check);
    int probe = mdh_buffer_access_init(&access, label);
    if (probe < 0) goto done;
    hello.fds[hello.count++] = probe;
    hello.used = (size_t)snprintf(hello.bytes, sizeof(hello.bytes), "%s%s\n", authorization, name);
    listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (listener < 0 || bind(listener, (void *)&address, sizeof(address)) < 0) goto done;
    bound = true;
    if (chmod(address.sun_path, 0666) < 0 || listen(listener, CLIENTS) < 0 ||
            pipe2(signals, O_CLOEXEC | O_NONBLOCK) < 0) goto done;
    cancel_fd = signals[1];
    struct sigaction action = {.sa_handler = cancel};
    sigaction(SIGTERM, &action, NULL); sigaction(SIGINT, &action, NULL);
    pairs = calloc(CLIENTS, sizeof(*pairs));
    if (!pairs) goto done;
    for (int i = 0; i < CLIENTS; ++i) pairs[i].fd[0] = pairs[i].fd[1] = -1;
    status = 0;
    for (;;) {
        struct pollfd fds[3 + CLIENTS * 2] = {
            {.fd = control, .events = POLLIN | (hello.used ? POLLOUT : 0)},
            {.fd = signals[0], .events = POLLIN},
            {.fd = admitted ? listener : -1, .events = POLLIN}};
        for (int i = 0; i < CLIENTS; ++i) for (int j = 0; j < 2; ++j) {
            struct Pair *p = &pairs[i];
            short events = (!p->queue[j].used && !p->queue[j].eof ? POLLIN : 0) |
                (p->queue[1-j].used ? POLLOUT : 0);
            fds[3+i*2+j] = (struct pollfd){.fd = events ? p->fd[j] : -1, .events = events};
        }
        // EVENT_WAIT: stream readiness or owner/signal cancellation; no periodic work.
        if (poll(fds, 3 + CLIENTS*2, -1) < 0) { if (errno == EINTR) continue; status = 1; break; }
        if (fds[1].revents) break;
        if ((fds[0].revents & POLLOUT) && mdh_stream_send(&hello, control) < 0) { status = 1; break; }
        if (fds[0].revents & POLLIN) {
            char ack;
            ssize_t n = read(control, &ack, 1);
            if (n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (n == 0) break;
            if (n != 1 || ack != 0 || admitted || hello.used) { status = 1; break; }
            admitted = true;
        }
        if (fds[0].revents & (POLLHUP | POLLERR | POLLNVAL)) break;
        if (fds[2].revents & POLLIN) {
            int fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK), slot = 0;
            for (; slot < CLIENTS && pairs[slot].fd[0] >= 0; ++slot) { }
            if (fd >= 0 && slot < CLIENTS) {
                pairs[slot].fd[0] = fd;
                pairs[slot].fd[1] = connect_to(upstream, false, owner);
                if (pairs[slot].fd[1] < 0) disconnect(&pairs[slot]);
            } else if (fd >= 0) close(fd);
        }
        for (int i = 0; i < CLIENTS; ++i) {
            struct Pair *p = &pairs[i];
            for (int j = 0; j < 2 && p->fd[0] >= 0; ++j) {
                short e = fds[3+i*2+j].revents;
                if ((e & POLLOUT) && mdh_stream_send(&p->queue[1-j], p->fd[j]) < 0) { disconnect(p); break; }
                if ((e & (POLLIN | POLLHUP)) && !p->queue[j].used && !p->queue[j].eof &&
                        mdh_stream_receive(&p->queue[j], p->fd[j], j == 0 ? mdh_buffer_admit : NULL, &access) < 0) {
                    perror("Wayland client descriptor admission"); disconnect(p); break;
                }
                if (e & (POLLERR | POLLNVAL)) { disconnect(p); break; }
            }
            if (p->fd[0] < 0) continue;
            for (int j = 0; j < 2; ++j) if (p->queue[j].eof && !p->queue[j].used && !p->write_closed[1-j]) {
                shutdown(p->fd[1-j], SHUT_WR); p->write_closed[1-j] = true;
            }
            if (p->write_closed[0] && p->write_closed[1]) disconnect(p);
        }
    }
done:
    if (status) perror("Wayland root broker");
    cancel_fd = -1;
    if (pairs) for (int i = 0; i < CLIENTS; ++i) disconnect(&pairs[i]);
    free(pairs); mdh_stream_clear(&hello);
    if (listener >= 0) close(listener);
    if (bound) unlink(address.sun_path);
    if (control >= 0) close(control);
    for (int i = 0; i < 2; ++i) if (signals[i] >= 0) close(signals[i]);
    return status;
}
