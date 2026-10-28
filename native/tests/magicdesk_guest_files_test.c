#define main guest_files_main
#include "../magicdesk_guest_files.c"
#undef main
#include <assert.h>
#include <sys/wait.h>

static void send_request(int socket, unsigned char operation, const char *path) {
    unsigned length = (unsigned)strlen(path);
    unsigned char header[] = {operation, length >> 24, length >> 16, length >> 8, length};
    assert(!transfer(socket, header, sizeof(header), 1));
    assert(!transfer(socket, (void *)path, length, 1));
}

static void mutation(int socket, unsigned char operation, const char *path) {
    send_request(socket, operation, path);
    if (operation == 4) {
        unsigned char size[] = {0,0,0,0,0,0,0,4};
        assert(!transfer(socket, size, sizeof(size), 1));
        unsigned char admitted; assert(!transfer(socket, &admitted, 1, 0) && !admitted);
        assert(!transfer(socket, "body", 4, 1));
    }
    unsigned char status;
    assert(!transfer(socket, &status, 1, 0) && !status);
}

static void read_path(int socket, char path[4097]) {
    unsigned char reply[5]; assert(!transfer(socket, reply, sizeof(reply), 0));
    assert(!reply[0]);
    unsigned size = (unsigned)reply[1]<<24 | (unsigned)reply[2]<<16 | (unsigned)reply[3]<<8 | reply[4];
    assert(size && size <= 4096);
    assert(!transfer(socket, path, size, 0)); path[size] = 0;
}

static void terminal_files(void) {
    struct imports live = {.terminal = 1}, second = {.terminal = 1};
    assert(!ensure_directory(&live));
    char original[PATH_MAX]; strcpy(original, live.directory);
    assert(!ensure_directory(&second));
    assert(!access(original, F_OK)); // A second terminal must not prune a live owner.
    close(live.lock_plus_one - 1); live.lock_plus_one = 0;
    struct imports fresh = {.terminal = 1};
    assert(!ensure_directory(&fresh));
    assert(access(original, F_OK) && errno == ENOENT);
    clear_imports(&second); clear_imports(&fresh);

    struct imports paths = {0};
    assert(!ensure_directory(&paths));
    char bad1[] = "../escape", bad2[] = "safe//escape", bad3[] = "/absolute", *leaf;
    assert(relative_parent(&paths, bad1, &leaf) < 0);
    assert(relative_parent(&paths, bad2, &leaf) < 0);
    assert(relative_parent(&paths, bad3, &leaf) < 0);
    clear_imports(&paths);

    int sockets[2]; assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, sockets));
    pid_t child = fork(); assert(child >= 0);
    if (!child) { close(sockets[0]); _exit(run_worker_mode(sockets[1], getppid(), 1)); }
    close(sockets[1]);
    send_request(sockets[0], 6, ".");
    char root[4097]; read_path(sockets[0], root);
    mutation(sockets[0], 3, "one");
    mutation(sockets[0], 3, "one/folder");
    mutation(sockets[0], 4, "one/folder/a ' file");
    mutation(sockets[0], 3, "two");
    mutation(sockets[0], 4, "two/a ' file");
    char path[8192], target[8192];
    snprintf(path, sizeof(path), "%s/one/escape", root);
    snprintf(target, sizeof(target), "%s/two", root);
    assert(!symlink(target, path));
    mutation(sockets[0], 5, "one");
    snprintf(path, sizeof(path), "%s/two/a ' file", root);
    assert(!access(path, F_OK)); // Recursive removal never follows guest-created links.
    mutation(sockets[0], 5, "one"); // Idempotent after manual deletion.
    send_request(sockets[0], 4, "two/partial");
    unsigned char size[] = {0,0,0,0,0,0,0,9};
    assert(!transfer(sockets[0], size, sizeof(size), 1));
    unsigned char admitted; assert(!transfer(sockets[0], &admitted, 1, 0) && !admitted);
    assert(!transfer(sockets[0], "part", 4, 1));
    close(sockets[0]);
    int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status));
    assert(access(root, F_OK) && errno == ENOENT);
}

static void loopback_files(void) {
    assert(connect_bridge("tcp:0", 1) < 0 && errno == EINVAL);
    assert(connect_bridge("tcp:65536", 1) < 0 && errno == EINVAL);
    assert(connect_bridge("tcp:123x", 1) < 0 && errno == EINVAL);
    assert(connect_bridge("tcp:1234", 0) < 0 && errno == EINVAL);
    int listener = socket(AF_INET, SOCK_STREAM, 0); assert(listener >= 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    assert(!bind(listener, (struct sockaddr *)&address, sizeof(address)) && !listen(listener, 1));
    socklen_t length = sizeof(address);
    assert(!getsockname(listener, (struct sockaddr *)&address, &length));
    char endpoint[32], secret[65]; memset(secret, 'a', 64); secret[64] = 0;
    snprintf(endpoint, sizeof(endpoint), "tcp:%u", ntohs(address.sin_port));
    for (int rejected = 1; rejected >= 0; --rejected) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            close(listener);
            assert(!setenv("MAGICDESK_GUEST_FILES_SOCKET", endpoint, 1));
            assert(!setenv("MAGICDESK_GUEST_FILES_TOKEN", secret, 1));
            char *args[] = {"helper", "--serve", NULL};
            _exit(guest_files_main(2, args));
        }
        int peer = accept(listener, NULL, NULL); assert(peer >= 0);
        struct timeval timeout = {.tv_sec = 5};
        assert(!setsockopt(peer, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)));
        char hello[64]; assert(!transfer(peer, hello, sizeof(hello), 0) && !memcmp(hello, secret, 64));
        unsigned char result = (unsigned char)rejected;
        assert(!transfer(peer, &result, 1, 1));
        char root[4097] = "";
        if (!rejected) {
            send_request(peer, 6, "."); read_path(peer, root);
            mutation(peer, 3, "files"); mutation(peer, 4, "files/copy");
            mutation(peer, 5, "files");
        }
        close(peer);
        int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status));
        assert(WEXITSTATUS(status) == rejected);
        if (!rejected) assert(access(root, F_OK) && errno == ENOENT);
    }
    close(listener);
}

static int request(int socketFd, const char *path, int expected) {
    uint32_t size = (uint32_t)strlen(path);
    unsigned char header[] = {1, size >> 24, size >> 16, size >> 8, size};
    for (size_t i = 0; i < sizeof(header); i++) assert(!transfer(socketFd, header + i, 1, 1));
    assert(!transfer(socketFd, (void *)path, size, 1));
    unsigned char status;
    struct iovec io = {&status, 1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } ancillary = {0};
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1,
            .msg_control = ancillary.bytes, .msg_controllen = sizeof(ancillary.bytes)};
    assert(recvmsg(socketFd, &message, 0) == 1);
    assert(status == expected);
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    if (expected) { assert(!c); return -1; }
    assert(c && c->cmsg_type == SCM_RIGHTS && c->cmsg_len == CMSG_LEN(sizeof(int)));
    int fd;
    memcpy(&fd, CMSG_DATA(c), sizeof(fd));
    return fd;
}

int main(void) {
    char directory[4096], file[4128], fifo[4128], link[4128], large[4128];
    snprintf(directory, sizeof(directory), "%s/guest-files.XXXXXX", getenv("TMPDIR") ?: "/tmp");
    assert(mkdtemp(directory));
    snprintf(file, sizeof(file), "%s/space ' file", directory);
    snprintf(fifo, sizeof(fifo), "%s/fifo", directory);
    snprintf(link, sizeof(link), "%s/link", directory);
    snprintf(large, sizeof(large), "%s/large", directory);
    int fd = open(file, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "data", 4) == 4); close(fd);
    assert(!mkfifo(fifo, 0600)); assert(!symlink(file, link));
    fd = open(large, O_WRONLY | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && !ftruncate(fd, 128LL * 1024 * 1024 + 1)); close(fd);
    int sockets[2]; assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, sockets));
    pid_t worker = fork(); assert(worker >= 0);
    if (!worker) {
        close(sockets[0]); _exit(run_worker(sockets[1], getppid()));
    }
    close(sockets[1]);
    fd = request(sockets[0], link, 0);
    assert(!unlink(file));
    char bytes[4]; assert(read(fd, bytes, sizeof(bytes)) == 4 && !memcmp(bytes, "data", 4)); close(fd);
    request(sockets[0], file, ENOENT);
    request(sockets[0], directory, EINVAL);
    request(sockets[0], fifo, EINVAL);
    request(sockets[0], large, EINVAL);
    const char *name = "copied ' file";
    unsigned length = (unsigned)strlen(name);
    unsigned char import[] = {2, 0, 0, 0, length};
    assert(!transfer(sockets[0], import, sizeof(import), 1));
    assert(!transfer(sockets[0], (void *)name, length, 1));
    unsigned char size[] = {0,0,0,0,0,0,0,4};
    assert(!transfer(sockets[0], size, sizeof(size), 1));
    assert(!transfer(sockets[0], "body", 4, 1));
    unsigned char response[5]; assert(!transfer(sockets[0], response, sizeof(response), 0));
    assert(!response[0]);
    length = (unsigned)response[1]<<24 | (unsigned)response[2]<<16 | (unsigned)response[3]<<8 | response[4];
    char imported[4097]; assert(length && length < sizeof(imported));
    assert(!transfer(sockets[0], imported, length, 0)); imported[length] = 0;
    assert(!strcmp(strrchr(imported, '/')+1, name));
    fd = request(sockets[0], imported, 0);
    assert(read(fd, bytes, 4) == 4 && !memcmp(bytes, "body", 4)); close(fd);
    assert(!kill(worker, SIGTERM));
    assert(read(sockets[0], bytes, sizeof(bytes)) == 0);
    close(sockets[0]);
    int status; assert(waitpid(worker, &status, 0) == worker && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(access(imported, F_OK) < 0 && errno == ENOENT);
    unlink(fifo); unlink(link); unlink(large); rmdir(directory);
    terminal_files();
    loopback_files();
    puts("Guest file descriptors: literal paths, symlinks, unlink, nonregular/large rejection and owner loss verified");
}
