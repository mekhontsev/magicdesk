#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/prctl.h>
#include <sys/un.h>
#include <unistd.h>
#include <limits.h>

/* Runs in the client's filesystem and credentials, not the X server's. */
static int transfer(int fd, void *data, size_t size, int writing) {
    char *p = data;
    while (size) {
        ssize_t n = writing ? send(fd, p, size, MSG_NOSIGNAL) : read(fd, p, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n;
        size -= (size_t)n;
    }
    return 0;
}

static int reply(int socket, int fd, int error) {
    unsigned char status = error > 0 && error < 256 ? (unsigned char)error : fd < 0 ? EIO : 0;
    struct iovec io = {&status, 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } ancillary = {0};
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1};
    if (fd >= 0) {
        message.msg_control = ancillary.bytes;
        message.msg_controllen = sizeof(ancillary.bytes);
        struct cmsghdr *c = CMSG_FIRSTHDR(&message);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    }
    ssize_t n;
    do { n = sendmsg(socket, &message, MSG_NOSIGNAL); } while (n < 0 && errno == EINTR);
    return n == 1 ? 0 : -1;
}

struct imports {
    char directory[PATH_MAX];
    char *names[128];
    unsigned count;
    uint64_t bytes;
};

static void clear_imports(struct imports *files) {
    if (!*files->directory) return;
    int directory = open(files->directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    for (unsigned i = 0; i < files->count; ++i) {
        if (directory >= 0) unlinkat(directory, files->names[i], 0);
        char container[32]; snprintf(container, sizeof(container), "%u", i);
        if (directory >= 0) unlinkat(directory, container, AT_REMOVEDIR);
        free(files->names[i]);
    }
    if (directory >= 0) close(directory);
    rmdir(files->directory);
}

static int import_file(int socket, const char *name, struct imports *files) {
    unsigned char encoded[8];
    if (transfer(socket, encoded, sizeof(encoded), 0)) return -1;
    uint64_t size = 0;
    for (unsigned i = 0; i < sizeof(encoded); ++i) size = (size << 8) | encoded[i];
    if (!*name || strlen(name) > 240 || strchr(name, '/') || strchr(name, '\\') || !strcmp(name, ".") || !strcmp(name, "..")
            || size > 128ULL*1024*1024 || size > 256ULL*1024*1024-files->bytes || files->count == 128) return -1;
    if (!*files->directory) {
        const char *temporary = getenv("TMPDIR");
        if (!temporary || temporary[0] != '/') temporary = "/tmp";
        if (snprintf(files->directory, sizeof(files->directory), "%s/magicdesk-content.XXXXXX", temporary)
                >= (int)sizeof(files->directory)) { files->directory[0] = 0; return -1; }
        if (!mkdtemp(files->directory)) { files->directory[0] = 0; return -1; }
    }
    char leaf[272], path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/%u", files->directory, files->count);
    if (mkdir(path, 0700)) return -1;
    snprintf(leaf, sizeof(leaf), "%u/%s", files->count, name);
    snprintf(path, sizeof(path), "%s/%s", files->directory, leaf);
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) {
        snprintf(path, sizeof(path), "%s/%u", files->directory, files->count);
        rmdir(path); return -1;
    }
    char buffer[65536];
    int r = 0;
    for (uint64_t copied = 0; !r && copied < size;) {
        size_t count = size-copied > sizeof(buffer) ? sizeof(buffer) : (size_t)(size-copied);
        if (transfer(socket, buffer, count, 0)) { r = -1; break; }
        size_t at = 0;
        while (at < count) {
            ssize_t n = write(fd, buffer+at, count-at);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) { r = -1; break; }
            at += (size_t)n;
        }
        copied += count;
    }
    if (!r && fsync(fd)) r = -1;
    close(fd);
    if (!r && !(files->names[files->count] = strdup(leaf))) r = -1;
    if (r) {
        unlink(path);
        snprintf(path, sizeof(path), "%s/%u", files->directory, files->count);
        rmdir(path); return r;
    }
    files->count++; files->bytes += size;
    uint32_t length = (uint32_t)strlen(path);
    unsigned char response[] = {0, length >> 24, length >> 16, length >> 8, length};
    return transfer(socket, response, sizeof(response), 1) || transfer(socket, path, length, 1) ? -1 : 0;
}

static int serve(int socket, struct imports *files) {
    for (;;) {
        unsigned char operation;
        if (transfer(socket, &operation, 1, 0)) return 0;
        unsigned char length[4];
        if (transfer(socket, length, sizeof(length), 0)) return 0;
        uint32_t size = (uint32_t)length[0] << 24 | (uint32_t)length[1] << 16
                | (uint32_t)length[2] << 8 | length[3];
        char path[4097];
        if (!size || size >= sizeof(path) || transfer(socket, path, size, 0)) return 1;
        if (memchr(path, 0, size)) return 1;
        path[size] = 0;
        if (operation == 2) { if (import_file(socket, path, files)) return 1; continue; }
        if (operation != 1 || path[0] != '/') return 1;
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        int error = fd < 0 ? errno : 0;
        struct stat st;
        if (fd >= 0 && (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size < 0
                || st.st_size > 128LL * 1024 * 1024)) {
            close(fd); fd = -1; error = EINVAL;
        }
        int result = reply(socket, fd, error);
        if (fd >= 0) close(fd);
        if (result) return 1;
    }
}

static volatile sig_atomic_t worker_socket = -1;
static void stop_worker(int signal) {
    (void)signal;
    int saved = errno;
    if (worker_socket >= 0) shutdown(worker_socket, SHUT_RDWR);
    errno = saved;
}

static int run_worker(int socket, pid_t parent) {
    worker_socket = socket;
    struct sigaction action = {.sa_handler = stop_worker};
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) || prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) return 1;
    // EVENT_WAIT: file requests, channel closure or command death; either owner loss releases imports.
    struct imports files = {0};
    int result = serve(socket, &files);
    clear_imports(&files);
    return result;
}

int main(int argc, char **argv) {
    const char *endpoint = getenv("MAGICDESK_GUEST_FILES_SOCKET");
    const char *token = getenv("MAGICDESK_GUEST_FILES_TOKEN");
    if (argc < 3 || strcmp(argv[1], "--") || !endpoint || !token || strlen(token) != 64) {
        fprintf(stderr, "Guest file bridge requires its session environment and -- PROGRAM ARG...\n");
        return 2;
    }
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    size_t length = strlen(endpoint);
    if (!length || length > sizeof(address.sun_path) - 2) return 2;
    memcpy(address.sun_path + 1, endpoint, length);
    int socketFd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct timeval timeout = {.tv_sec = 10};
    if (socketFd < 0 || setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))
            || setsockopt(socketFd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))
            || connect(socketFd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + length)
            || transfer(socketFd, (void *)token, 64, 1)) {
        perror("Guest file bridge connection"); return 1;
    }
    unsigned char accepted;
    if (transfer(socketFd, &accepted, 1, 0) || accepted != 0) {
        fprintf(stderr, "Guest file bridge authorization failed\n"); return 1;
    }
    timeout.tv_sec = 0;
    if (setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))) return 1;
    unsetenv("MAGICDESK_GUEST_FILES_TOKEN");
    unsetenv("MAGICDESK_GUEST_FILES_SOCKET");
    pid_t parent = getpid();
    pid_t worker = fork();
    if (worker < 0) { perror("Guest file bridge fork"); return 1; }
    if (!worker) {
        // The graphical session owns this worker through its socket, not the command's pipes.
        int null = open("/dev/null", O_RDWR);
        for (int i = 0; i < 3; i++) { if (null >= 0) dup2(null, i); else close(i); }
        if (null > 2) close(null);
        _exit(run_worker(socketFd, parent));
    }
    close(socketFd);
    execvp(argv[2], argv + 2);
    perror("Guest command");
    kill(worker, SIGTERM);
    return 127;
}
