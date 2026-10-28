#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/time.h>
#include <sys/prctl.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <unistd.h>
#include <limits.h>
#include "magicdesk_guest_session.inc"

static int exec_session(const char *protocol, int count, char **command) {
    char **args = calloc((size_t)count + 6, sizeof(*args));
    if (!args) return -1;
    args[0] = "sh";
    // Load the guest profile before D-Bus so activated services inherit its graphics policy.
    args[1] = "-lc";
    args[2] = (char *)md_guest_session;
    args[3] = "magicdesk-session";
    args[4] = (char *)protocol;
    for (int i = 0; i < count; ++i) args[i + 5] = command[i];
    execvp(args[0], args);
    int error = errno;
    free(args);
    errno = error;
    return -1;
}

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
    int terminal;
    int lock_plus_one;
};

/* All mutations stay beneath the owned directory, including after guest edits. */
static int remove_tree(int parent, const char *name) {
    int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return unlinkat(parent, name, 0) && errno != ENOENT ? -1 : 0;
    DIR *dir = fdopendir(fd);
    if (!dir) { close(fd); return -1; }
    int result = 0;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (remove_tree(fd, entry->d_name)) result = -1;
    }
    closedir(dir);
    return unlinkat(parent, name, AT_REMOVEDIR) ? -1 : result;
}

static int ensure_directory(struct imports *files) {
    if (*files->directory) return 0;
    const char *temporary = getenv("TMPDIR");
    if (!temporary || temporary[0] != '/') temporary = "/tmp";
    int parent = -1;
    if (files->terminal) {
        parent = open(temporary, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (parent < 0) return -1;
        /* Serialize admission with orphan collection, not with file transfers. */
        if (flock(parent, LOCK_EX)) { close(parent); return -1; }
        DIR *dir = fdopendir(dup(parent));
        if (dir) {
            struct dirent *entry;
            while ((entry = readdir(dir))) {
                if (strncmp(entry->d_name, "magicdesk-terminal.", 19) || strlen(entry->d_name) != 25) continue;
                int owner = openat(parent, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
                if (owner < 0) continue;
                if (!flock(owner, LOCK_EX | LOCK_NB)) remove_tree(parent, entry->d_name);
                close(owner);
            }
            closedir(dir);
        }
    }
    if (snprintf(files->directory, sizeof(files->directory), "%s/%s.XXXXXX", temporary,
            files->terminal ? "magicdesk-terminal" : "magicdesk-content")
            >= (int)sizeof(files->directory) || !mkdtemp(files->directory)) {
        files->directory[0] = 0;
        if (parent >= 0) close(parent);
        return -1;
    }
    if (files->terminal) {
        int fd = open(files->directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0 || flock(fd, LOCK_EX | LOCK_NB)) {
            if (fd >= 0) close(fd);
            rmdir(files->directory); files->directory[0] = 0; close(parent); return -1;
        }
        files->lock_plus_one = fd + 1;
        close(parent);
    }
    return 0;
}

static int path_reply(int socket, const char *path) {
    uint32_t length = (uint32_t)strlen(path);
    unsigned char response[] = {0, length >> 24, length >> 16, length >> 8, length};
    return transfer(socket, response, sizeof(response), 1) || transfer(socket, (void *)path, length, 1) ? -1 : 0;
}

static void clear_imports(struct imports *files) {
    if (!*files->directory) return;
    int directory = open(files->directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory >= 0) {
        DIR *dir = fdopendir(dup(directory));
        if (dir) {
            struct dirent *entry;
            while ((entry = readdir(dir))) {
                if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) remove_tree(directory, entry->d_name);
            }
            closedir(dir);
        }
    }
    for (unsigned i = 0; i < files->count; ++i) free(files->names[i]);
    if (directory >= 0) close(directory);
    rmdir(files->directory);
    if (files->lock_plus_one) { close(files->lock_plus_one - 1); files->lock_plus_one = 0; }
}

static int receive_file(int socket, int fd, uint64_t size) {
    char buffer[65536];
    for (uint64_t copied = 0; copied < size;) {
        size_t count = size-copied > sizeof(buffer) ? sizeof(buffer) : (size_t)(size-copied);
        if (transfer(socket, buffer, count, 0)) return -1;
        size_t at = 0;
        while (at < count) {
            ssize_t n = write(fd, buffer+at, count-at);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) return -1;
            at += (size_t)n;
        }
        copied += count;
    }
    return fsync(fd);
}

static int read_size(int socket, uint64_t *size) {
    unsigned char encoded[8];
    if (transfer(socket, encoded, sizeof(encoded), 0)) return -1;
    *size = 0;
    for (unsigned i = 0; i < sizeof(encoded); ++i) *size = (*size << 8) | encoded[i];
    return *size > 128ULL*1024*1024 ? -1 : 0;
}

static int relative_parent(struct imports *files, char *path, char **leaf) {
    if (ensure_directory(files) || !*path || *path == '/' || strchr(path, '\\')) return -1;
    int parent = open(files->directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    char *component = path;
    while (parent >= 0) {
        char *slash = strchr(component, '/');
        if (slash) *slash = 0;
        if (!*component || !strcmp(component, ".") || !strcmp(component, "..")) break;
        if (!slash) { *leaf = component; return parent; }
        int next = openat(parent, component, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(parent);
        parent = next;
        component = slash + 1;
    }
    if (parent >= 0) close(parent);
    return -1;
}

static int mutate_import(int socket, int operation, char *path, struct imports *files) {
    uint64_t size = 0;
    if (operation == 4 && read_size(socket, &size)) return -1;
    char *leaf = NULL;
    int parent = relative_parent(files, path, &leaf);
    if (parent < 0) return -1;
    int result = -1;
    if (operation == 3) result = mkdirat(parent, leaf, 0700);
    else if (operation == 5) result = remove_tree(parent, leaf);
    else if (operation == 4) {
        int fd = openat(parent, leaf, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        unsigned char admitted = fd < 0 ? EIO : 0;
        if (transfer(socket, &admitted, 1, 1)) {
            if (fd >= 0) { close(fd); unlinkat(parent, leaf, 0); }
            close(parent); return -1;
        }
        if (fd < 0) { close(parent); return 0; }
        if (fd >= 0) {
            result = receive_file(socket, fd, size);
            close(fd);
            if (result) unlinkat(parent, leaf, 0);
        }
    }
    close(parent);
    unsigned char status = result ? EIO : 0;
    /* A failed upload leaves unread body bytes; disconnect rather than reinterpret them. */
    if (operation == 4 && result) return -1;
    return transfer(socket, &status, 1, 1);
}

static int import_file(int socket, const char *name, struct imports *files) {
    uint64_t size = 0;
    if (read_size(socket, &size)) return -1;
    if (!*name || strlen(name) > 240 || strchr(name, '/') || strchr(name, '\\') || !strcmp(name, ".") || !strcmp(name, "..")
            || size > 128ULL*1024*1024 || size > 256ULL*1024*1024-files->bytes || files->count == 128) return -1;
    if (ensure_directory(files)) return -1;
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
    int r = receive_file(socket, fd, size);
    close(fd);
    if (!r && !(files->names[files->count] = strdup(leaf))) r = -1;
    if (r) {
        unlink(path);
        snprintf(path, sizeof(path), "%s/%u", files->directory, files->count);
        rmdir(path); return r;
    }
    files->count++; files->bytes += size;
    return path_reply(socket, path);
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
        if (operation == 6) {
            if (ensure_directory(files) || path_reply(socket, files->directory)) return 1;
            continue;
        }
        if (operation >= 3 && operation <= 5) {
            if (mutate_import(socket, operation, path, files)) return 1;
            continue;
        }
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

static int run_worker_mode(int socket, pid_t parent, int terminal) {
    worker_socket = socket;
    struct sigaction action = {.sa_handler = stop_worker};
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) || prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent) return 1;
    // EVENT_WAIT: file requests, channel closure or command death; either owner loss releases imports.
    struct imports files = {.terminal = terminal};
    int result = serve(socket, &files);
    clear_imports(&files);
    return result;
}

static int run_worker(int socket, pid_t parent) { return run_worker_mode(socket, parent, 0); }

static int connect_bridge(const char *endpoint, int terminal) {
    struct sockaddr_storage storage = {0};
    socklen_t size;
    int tcp = !strncmp(endpoint, "tcp:", 4);
    if (tcp) {
        char *end;
        long port = strtol(endpoint + 4, &end, 10);
        if (!terminal || *end || port < 1 || port > 65535) { errno = EINVAL; return -1; }
        struct sockaddr_in *address = (struct sockaddr_in *)&storage;
        address->sin_family = AF_INET;
        address->sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address->sin_port = htons((uint16_t)port);
        size = sizeof(*address);
    } else {
        struct sockaddr_un *address = (struct sockaddr_un *)&storage;
        size_t length = strlen(endpoint);
        if (!length || length > sizeof(address->sun_path) - 2) { errno = EINVAL; return -1; }
        address->sun_family = AF_UNIX;
        memcpy(address->sun_path + 1, endpoint, length);
        size = offsetof(struct sockaddr_un, sun_path) + 1 + length;
    }
    int fd = socket(storage.ss_family, SOCK_STREAM | SOCK_CLOEXEC, 0), one = 1;
    if (fd < 0) return -1;
    struct timeval timeout = {.tv_sec = 10};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))
            || setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout))
            || (tcp && setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one)))
            || connect(fd, (struct sockaddr *)&storage, size)) {
        int error = errno; close(fd); errno = error; return -1;
    }
    return fd;
}

int main(int argc, char **argv) {
    int at = 1;
    const char *protocol = NULL;
    int files = 1;
    int standalone = argc == 2 && !strcmp(argv[1], "--serve");
    if (at < argc && !strcmp(argv[at], "--session")) {
        if (++at == argc) return 2;
        protocol = argv[at++];
        if (strcmp(protocol, "x11") && strcmp(protocol, "x11-routed") && strcmp(protocol, "wayland")) return 2;
        files = at < argc && !strcmp(argv[at], "--files");
        if (files) ++at;
    }
    if (!standalone && (at >= argc || strcmp(argv[at++], "--") || at == argc)) {
        fprintf(stderr, "Usage: guest helper [--session x11|x11-routed|wayland [--files]] -- PROGRAM ARG...\n");
        return 2;
    }
    if (!files) {
        exec_session(protocol, argc - at, argv + at);
        perror("Guest session");
        return 127;
    }
    const char *endpoint = getenv("MAGICDESK_GUEST_FILES_SOCKET");
    const char *token = getenv("MAGICDESK_GUEST_FILES_TOKEN");
    if (!endpoint || !token || strlen(token) != 64) {
        fprintf(stderr, "Guest file bridge requires its session environment and -- PROGRAM ARG...\n");
        return 2;
    }
    int socketFd = connect_bridge(endpoint, standalone);
    if (socketFd < 0 || transfer(socketFd, (void *)token, 64, 1)) {
        perror("Guest file bridge connection"); return 1;
    }
    unsigned char accepted;
    if (transfer(socketFd, &accepted, 1, 0) || accepted != 0) {
        fprintf(stderr, "Guest file bridge authorization failed\n"); return 1;
    }
    struct timeval timeout = {0};
    if (setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout))) return 1;
    unsetenv("MAGICDESK_GUEST_FILES_TOKEN");
    unsetenv("MAGICDESK_GUEST_FILES_SOCKET");
    if (standalone) return run_worker_mode(socketFd, getppid(), 1);
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
    if (protocol) exec_session(protocol, argc - at, argv + at);
    else execvp(argv[at], argv + at);
    perror("Guest command");
    kill(worker, SIGTERM);
    return 127;
}
