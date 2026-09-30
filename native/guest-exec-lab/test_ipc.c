#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%d: %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static socklen_t address(struct sockaddr_un *out, const char *path) {
    *out = (struct sockaddr_un){.sun_family = AF_UNIX};
    CHECK(strlen(path) < sizeof(out->sun_path));
    strcpy(out->sun_path, path);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);
}
static void ready(int fd) {
    struct pollfd p = {fd, POLLIN, 0};
    // EVENT_WAIT: peer data/connection, ten seconds is a failure bound.
    int r; do { r = poll(&p, 1, 10000); } while (r < 0 && errno == EINTR);
    CHECK(r == 1 && (p.revents & POLLIN));
}
static void name(int fd, int peer, const char *path) {
    struct sockaddr_un seen;
    socklen_t size = sizeof(seen);
    CHECK((peer ? getpeername(fd, (struct sockaddr *)&seen, &size)
        : getsockname(fd, (struct sockaddr *)&seen, &size)) == 0);
    CHECK(seen.sun_family == AF_UNIX && !strcmp(seen.sun_path, path));
    CHECK(size == offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);
    size = sizeof(sa_family_t);
    CHECK((peer ? getpeername(fd, (struct sockaddr *)&seen, &size)
        : getsockname(fd, (struct sockaddr *)&seen, &size)) == 0);
    CHECK(size == offsetof(struct sockaddr_un, sun_path) + strlen(path) + 1);
}
static int bind_at(const char *path, int type) {
    int fd = socket(AF_UNIX, type | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr;
    socklen_t size = address(&addr, path);
    CHECK(fd >= 0 && bind(fd, (struct sockaddr *)&addr, size) == 0);
    struct stat st; CHECK(!stat(path, &st) && S_ISSOCK(st.st_mode));
    name(fd, 0, path);
    return fd;
}
static void transfer(int from, int to) {
    int fd = memfd_create("ipc-buffer", MFD_CLOEXEC); CHECK(fd >= 0);
    CHECK(ftruncate(fd, 4096) == 0);
    char *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    CHECK(p != MAP_FAILED); strcpy(p, "native buffer");
    union { struct cmsghdr align; char data[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'b'; struct iovec iov = {&byte, 1};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = &control, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    CHECK(sendmsg(from, &msg, MSG_NOSIGNAL) == 1);
    ready(to);
    memset(&control, 0, sizeof(control));
    CHECK(recvmsg(to, &msg, MSG_CMSG_CLOEXEC) == 1);
    c = CMSG_FIRSTHDR(&msg); CHECK(c && c->cmsg_type == SCM_RIGHTS);
    int received; memcpy(&received, CMSG_DATA(c), sizeof(received));
    CHECK(fcntl(received, F_GETFD) == FD_CLOEXEC);
    char data[32]; CHECK(pread(received, data, sizeof(data), 0) == sizeof(data));
    CHECK(!strcmp(data, p));
    CHECK(pwrite(received, "changed", 8, 0) == 8 && !strcmp(p, "changed"));
    close(received); close(fd); CHECK(!munmap(p, 4096));
}
static void connection(const char *path, int listener, const char *bound) {
    struct sockaddr_un addr;
    socklen_t size = address(&addr, path);
    int client = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); CHECK(client >= 0);
    CHECK(!connect(client, (struct sockaddr *)&addr, size));
    name(client, 1, bound);
    ready(listener);
    int peer = accept4(listener, (struct sockaddr *)&addr, &size, SOCK_CLOEXEC); CHECK(peer >= 0);
    transfer(client, peer); transfer(peer, client);
    close(peer); close(client);
}
static int batch_send(int fd, struct mmsghdr *messages, unsigned count, unsigned flags) {
    // Exercise the ABI directly: libc may return zero before checking fd for
    // an empty vector, or implement batching as individual sendmsg calls.
    return (int)syscall(SYS_sendmmsg, fd, messages, count, flags);
}
static void stream_batch(void) {
    int pair[2]; CHECK(!socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair));
    int capacity = 4096;
    CHECK(!setsockopt(pair[0], SOL_SOCKET, SO_SNDBUF, &capacity, sizeof(capacity)));
    char payload[65536] = {0};
    struct iovec vectors[] = {{payload, sizeof(payload)}, {NULL, 0}};
    struct mmsghdr messages[2] = {
        {.msg_hdr = {.msg_iov = vectors, .msg_iovlen = 1}, .msg_len = 99},
        {.msg_hdr = {.msg_iov = vectors + 1, .msg_iovlen = 1}, .msg_len = 99},
    };
    CHECK(batch_send(pair[0], messages, 2, MSG_DONTWAIT | MSG_NOSIGNAL) == 1);
    CHECK(messages[0].msg_len > 0 && messages[0].msg_len < sizeof(payload));
    CHECK(messages[1].msg_len == 99);
    CHECK(recv(pair[1], payload, sizeof(payload), MSG_DONTWAIT) == messages[0].msg_len);
    close(pair[0]); close(pair[1]);
}
static void batch(void) {
    stream_batch();
    int first = bind_at("batch-first", SOCK_DGRAM);
    int second = bind_at("batch-second", SOCK_DGRAM);
    int client = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0); CHECK(client >= 0);
    CHECK(!symlink("batch-first", "batch-alias"));
    struct sockaddr_un addresses[3];
    struct iovec vectors[3] = {{"first", 5}, {"second", 6}, {"missing", 7}};
    struct mmsghdr messages[3] = {0};
    const char *paths[] = {"batch-alias", "batch-second", "batch-absent"};
    for (unsigned i = 0; i < 3; ++i) {
        messages[i].msg_hdr.msg_name = addresses + i;
        messages[i].msg_hdr.msg_namelen = address(addresses + i, paths[i]);
        messages[i].msg_hdr.msg_iov = vectors + i;
        messages[i].msg_hdr.msg_iovlen = 1;
        messages[i].msg_len = 99;
    }
    CHECK(batch_send(client, messages, 3, MSG_NOSIGNAL) == 2);
    CHECK(messages[0].msg_len == 5 && messages[1].msg_len == 6 && messages[2].msg_len == 99);
    char bytes[16];
    ready(first); CHECK(recv(first, bytes, sizeof(bytes), 0) == 5 && !memcmp(bytes, "first", 5));
    ready(second); CHECK(recv(second, bytes, sizeof(bytes), 0) == 6 && !memcmp(bytes, "second", 6));
    CHECK(batch_send(client, messages + 2, 1, 0) == -1 && errno == ENOENT);
    CHECK(batch_send(client, NULL, 0, 0) == 0);
    CHECK(batch_send(-1, NULL, 0, 0) == -1 && errno == EBADF);
    CHECK(batch_send(client, (void *)1, 1, 0) == -1 && errno == EFAULT);

    // Kernel sends first, then writes msg_len: a read-only vector fails without
    // reporting a completed element, but its datagram must not be duplicated.
    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    struct mmsghdr *readonly = mmap(NULL, page, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(readonly != MAP_FAILED); *readonly = messages[0];
    CHECK(!mprotect(readonly, page, PROT_READ));
    CHECK(batch_send(client, readonly, 1, 0) == -1 && errno == EFAULT);
    ready(first); CHECK(recv(first, bytes, sizeof(bytes), 0) == 5);
    CHECK(recv(first, bytes, sizeof(bytes), MSG_DONTWAIT) == -1 && errno == EAGAIN);
    CHECK(!munmap(readonly, page));

    CHECK(!connect(client, (struct sockaddr *)&addresses[0], messages[0].msg_hdr.msg_namelen));
    int memory = memfd_create("batch-fd", MFD_CLOEXEC); CHECK(memory >= 0);
    CHECK(write(memory, "shared", 6) == 6);
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    messages[0].msg_hdr.msg_name = NULL; messages[0].msg_hdr.msg_namelen = 0;
    messages[0].msg_hdr.msg_control = &control; messages[0].msg_hdr.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&messages[0].msg_hdr);
    cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS; cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &memory, sizeof(memory));
    CHECK(batch_send(client, messages, 1, 0) == 1 && messages[0].msg_len == 5);
    memset(&control, 0, sizeof(control));
    struct iovec input = {bytes, sizeof(bytes)};
    struct msghdr received = {.msg_iov = &input, .msg_iovlen = 1,
        .msg_control = &control, .msg_controllen = sizeof(control)};
    ready(first); CHECK(recvmsg(first, &received, MSG_CMSG_CLOEXEC) == 5);
    cmsg = CMSG_FIRSTHDR(&received); CHECK(cmsg && cmsg->cmsg_type == SCM_RIGHTS);
    int fd; memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    CHECK(fcntl(fd, F_GETFD) == FD_CLOEXEC);
    CHECK(pread(fd, bytes, sizeof(bytes), 0) == 6 && !memcmp(bytes, "shared", 6));
    close(fd); close(memory); close(client); close(first); close(second);
    CHECK(!unlink("batch-first") && !unlink("batch-second") && !unlink("batch-alias"));
    puts("PASS Unix sendmmsg: pathname/connected batches, partial errors, faulted result and SCM_RIGHTS");
}
static void local(const char *directory) {
    CHECK(!mkdir(directory, 0700) && !chdir(directory));
    batch();
    mode_t old = umask(0027);
    int server = bind_at("stream", SOCK_STREAM);
    umask(old);
    struct stat st; CHECK(!lstat("stream", &st) && (st.st_mode & 0777) == 0750);
    CHECK(!listen(server, 8));
    connection("stream", server, "stream");
    int second = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(second >= 0);
    struct sockaddr_un addr; socklen_t size = address(&addr, "stream");
    CHECK(bind(second, (struct sockaddr *)&addr, size) == -1 && errno == EADDRINUSE);
    CHECK(!symlink("stream", "alias")); connection("alias", server, "stream");
    CHECK(!unlink("alias"));
    CHECK(!chmod("stream", 0000));
    CHECK(connect(second, (struct sockaddr *)&addr, size) == -1 && errno == EACCES);
    CHECK(!chmod("stream", 0700));
    CHECK(!unlink("stream"));
    CHECK(connect(second, (struct sockaddr *)&addr, size) == -1 && errno == ENOENT);
    CHECK(!bind(second, (struct sockaddr *)&addr, size) && !listen(second, 8));
    connection("stream", second, "stream");
    CHECK(!rename("stream", "moved"));
    name(second, 0, "stream");
    // The kernel retains the original bound name across namespace rename.
    int client = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(client >= 0);
    size = address(&addr, "moved"); CHECK(!connect(client, (struct sockaddr *)&addr, size));
    name(client, 1, "stream"); close(client);
    close(server); close(second);
    CHECK(!stat("moved", &st) && S_ISSOCK(st.st_mode));
    client = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(client >= 0);
    CHECK(connect(client, (struct sockaddr *)&addr, size) == -1 && errno == ECONNREFUSED);
    close(client); CHECK(!unlink("moved"));

    server = bind_at("dgram", SOCK_DGRAM);
    client = bind_at("source", SOCK_DGRAM);
    size = address(&addr, "dgram");
    CHECK(sendto(client, "one", 3, 0, (struct sockaddr *)&addr, size) == 3);
    char bytes[8]; size = sizeof(addr); ready(server);
    CHECK(recvfrom(server, bytes, sizeof(bytes), 0, (struct sockaddr *)&addr, &size) == 3);
    CHECK(!strcmp(addr.sun_path, "source"));
    CHECK(sendto(server, "two", 3, 0, (struct sockaddr *)&addr, size) == 3);
    struct iovec iov = {bytes, sizeof(bytes)};
    struct msghdr msg = {.msg_name = &addr, .msg_namelen = sizeof(addr), .msg_iov = &iov, .msg_iovlen = 1};
    ready(client); CHECK(recvmsg(client, &msg, 0) == 3 && !strcmp(addr.sun_path, "dgram"));
    close(client); close(server); CHECK(!unlink("source") && !unlink("dgram"));

    struct sockaddr_un longest = {.sun_family = AF_UNIX};
    memset(longest.sun_path, 'l', sizeof(longest.sun_path));
    server = socket(AF_UNIX, SOCK_DGRAM, 0); CHECK(server >= 0);
    CHECK(!bind(server, (struct sockaddr *)&longest, sizeof(longest)));
    size = sizeof(addr); CHECK(!getsockname(server, (struct sockaddr *)&addr, &size));
    CHECK(size == sizeof(longest) + 1 && !memcmp(&addr, &longest, sizeof(addr)));
    char long_path[109]; memset(long_path, 'l', 108); long_path[108] = 0;
    CHECK(!unlink(long_path)); close(server);
    CHECK(!chdir("/") && !rmdir(directory));
    puts("PASS pathname sockets: names, umask, permissions, SCM_RIGHTS, unlink/rebind, rename and stale listeners");
}
static int shared(const char *key, int create) {
    int fd = shm_open(key, O_RDWR | (create ? O_CREAT | O_EXCL : 0), 0600);
    CHECK(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC));
    if (create) CHECK(!ftruncate(fd, 4096));
    return fd;
}
static void shared_paths(void) {
    int dev = open("/dev", O_PATH | O_DIRECTORY | O_CLOEXEC); CHECK(dev >= 0);
    int memory = openat(dev, "./shm/md-ipc-path", O_RDWR | O_CREAT | O_EXCL, 0600);
    CHECK(memory >= 0);
    struct stat a, b;
    CHECK(!fstat(memory, &a) && !fstatat(dev, "shm/md-ipc-path", &b, 0));
    CHECK(a.st_ino == b.st_ino && a.st_dev == b.st_dev);
    CHECK(!chdir("/dev"));
    CHECK(!stat("./shm/md-ipc-path", &b) && a.st_ino == b.st_ino);
    CHECK(!renameat(dev, "shm/md-ipc-path", AT_FDCWD, "shm/md-ipc-renamed"));
    CHECK(!unlinkat(dev, "shm/md-ipc-renamed", 0));
    CHECK(!chdir("/"));
    close(memory); close(dev);
    puts("PASS shared memory mount: absolute, dirfd and host cwd paths agree");
}
static void independent(const char *path, const char *key, int serving) {
    int memory = shared(key, serving);
    char *p = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, memory, 0); CHECK(p != MAP_FAILED);
    int fd;
    int notify = -1;
    int watch = -1;
    if (serving) {
        strcpy(p, "server");
        char watch_path[256]; CHECK(snprintf(watch_path, sizeof(watch_path), "/dev/shm%s", key) < (int)sizeof(watch_path));
        notify = inotify_init1(IN_CLOEXEC | IN_NONBLOCK); CHECK(notify >= 0);
        watch = inotify_add_watch(notify, watch_path, IN_MODIFY); CHECK(watch >= 0);
        int listener = bind_at(path, SOCK_STREAM); CHECK(!listen(listener, 4));
        puts("READY"); fflush(stdout);
        ready(listener); fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC); CHECK(fd >= 0);
        close(listener);
    } else {
        CHECK(!strcmp(p, "server")); CHECK(pwrite(memory, "client", 7, 0) == 7);
        struct sockaddr_un addr; socklen_t length = address(&addr, path);
        fd = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(fd >= 0);
        CHECK(!connect(fd, (struct sockaddr *)&addr, length));
        CHECK(write(fd, "c", 1) == 1);
    }
    char byte;
    ready(fd); CHECK(read(fd, &byte, 1) == 1);
    if (serving) {
        CHECK(byte == 'c' && !strcmp(p, "client"));
        ready(notify);
        struct inotify_event event;
        CHECK(read(notify, &event, sizeof(event)) == sizeof(event));
        CHECK(event.wd == watch && event.mask == IN_MODIFY && !event.len);
        CHECK(!inotify_rm_watch(notify, watch)); close(notify);
        CHECK(!shm_unlink(key)); strcpy(p, "unlinked");
        CHECK(write(fd, "s", 1) == 1);
        ready(fd); CHECK(read(fd, &byte, 1) == 1 && byte == 'x');
        CHECK(!unlink(path));
    } else {
        CHECK(byte == 's' && !strcmp(p, "unlinked"));
        CHECK(shm_open(key, O_RDONLY, 0) == -1 && errno == ENOENT);
        CHECK(write(fd, "x", 1) == 1);
    }
    close(fd); close(memory); CHECK(!munmap(p, 4096));
    puts("PASS independent launches: pathname IPC, shared memory, file notification and unlinked lifetime");
}
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 3 && !strcmp(argv[1], "local")) local(argv[2]);
    else if (argc == 2 && !strcmp(argv[1], "paths")) shared_paths();
    else if (argc == 4 && (!strcmp(argv[1], "serve") || !strcmp(argv[1], "client")))
        independent(argv[2], argv[3], !strcmp(argv[1], "serve"));
    else if (argc == 4 && !strcmp(argv[1], "absent")) {
        CHECK(shm_open(argv[3], O_RDONLY, 0) == -1 && errno == ENOENT);
        int fd = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(fd >= 0);
        struct sockaddr_un addr; socklen_t size = address(&addr, argv[2]);
        CHECK(connect(fd, (struct sockaddr *)&addr, size) == -1 && errno == ENOENT);
        close(fd);
        puts("PASS a different store does not resolve another distribution's socket or shared memory");
    }
    else return 2;
    return 0;
}
