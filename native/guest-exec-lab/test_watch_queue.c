#define _GNU_SOURCE
#include "../guest-runtime/src/watch_queue.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL queue:%d %s errno=%d\n", \
    __LINE__, #x, errno); exit(1); } } while (0)
static int readable(int fd) {
    struct pollfd p = {fd, POLLIN, 0};
    CHECK(poll(&p, 1, 0) >= 0);
    return !!(p.revents & POLLIN);
}
static int closed(struct md_watch_queue *q) {
    struct pollfd p = {md_watch_queue_lifetime(q), 0, 0};
    CHECK(poll(&p, 1, 0) >= 0);
    return !!(p.revents & (POLLERR | POLLHUP));
}
static int reject(void *context, const void *data, size_t size) {
    CHECK(context == data && size > 0);
    return -EFAULT;
}
static void record(const unsigned char *data, int wd, unsigned mask, unsigned cookie,
        const char *name) {
    struct inotify_event e; memcpy(&e, data, sizeof(e));
    CHECK(e.wd == wd && e.mask == mask && e.cookie == cookie);
    CHECK(e.len == (name ? (strlen(name) + 16) & ~(size_t)15 : 0));
    if (name) {
        CHECK(!strcmp((const char *)data + sizeof(e), name));
        for (size_t i = strlen(name); i < e.len; i++) CHECK(!data[sizeof(e) + i]);
    }
}
static void records(void) {
    struct md_watch_queue *q; int fd;
    CHECK(!md_watch_queue_create(3, IN_NONBLOCK | IN_CLOEXEC, &q, &fd));
    CHECK(fcntl(fd, F_GETFL) & O_NONBLOCK);
    CHECK(fcntl(fd, F_GETFD) & FD_CLOEXEC);
    unsigned char bytes[4096];
    CHECK(!readable(fd) && !md_watch_queue_bytes(q));
    CHECK(!md_watch_queue_emit(q, 9, IN_CREATE, 0, "reserved"));
    CHECK(md_watch_queue_reserve(q, fd, bytes, sizeof(bytes)) == 32);
    CHECK(!readable(fd));
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == -EAGAIN);
    CHECK(!md_watch_queue_emit(q, 9, IN_CREATE, 0, "reserved"));
    CHECK(md_watch_queue_bytes(q) == 64 && !readable(fd));
    CHECK(!md_watch_queue_complete(q, 1));
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == 32);
    CHECK(!md_watch_queue_emit(q, 9, IN_CREATE, 0, "rejected"));
    CHECK(md_watch_queue_reserve(q, fd, bytes, sizeof(bytes)) == 32);
    CHECK(!md_watch_queue_complete(q, 0));
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == 32);
    record(bytes, 9, IN_CREATE, 0, "rejected");
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == -EAGAIN);
    CHECK(!md_watch_queue_emit(q, 4, IN_CREATE, 0, "file"));
    CHECK(!md_watch_queue_emit(q, 4, IN_CREATE, 0, "file"));
    CHECK(md_watch_queue_bytes(q) == 32 && readable(fd));
    CHECK(md_watch_queue_read(q, fd, bytes, 31, NULL, NULL) == -EINVAL);
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), reject, bytes) == -EFAULT);
    CHECK(md_watch_queue_bytes(q) == 32 && readable(fd));
    CHECK(!md_watch_queue_emit(q, 4, IN_MOVED_FROM, 123, "file"));
    CHECK(!md_watch_queue_emit(q, 7, IN_MOVED_TO, 123, "renamed"));
    CHECK(!md_watch_queue_emit(q, 4, IN_CREATE, 0, "overflow"));
    CHECK(!md_watch_queue_emit(q, 4, IN_CREATE, 0, "overflow-again"));
    CHECK(md_watch_queue_bytes(q) == 112);
    CHECK(md_watch_queue_read(q, fd, bytes, 33, NULL, NULL) == 32);
    record(bytes, 4, IN_CREATE, 0, "file");
    CHECK(readable(fd));
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == 80);
    record(bytes, 4, IN_MOVED_FROM, 123, "file");
    record(bytes + 32, 7, IN_MOVED_TO, 123, "renamed");
    record(bytes + 64, -1, IN_Q_OVERFLOW, 0, NULL);
    CHECK(!readable(fd) && !md_watch_queue_bytes(q));
    char name[NAME_MAX + 1]; memset(name, 'x', NAME_MAX); name[NAME_MAX] = 0;
    CHECK(!md_watch_queue_emit(q, 4, IN_CREATE, 0, name));
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == 272);
    record(bytes, 4, IN_CREATE, 0, name);
    for (int i = 0; i < 10000; i++) {
        CHECK(!md_watch_queue_emit(q, i, IN_IGNORED, 0, NULL));
        CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == 16);
        record(bytes, i, IN_IGNORED, 0, NULL);
    }
    CHECK(!md_watch_queue_emit(q, 1, IN_DELETE_SELF, 0, NULL));
    close(fd); CHECK(closed(q)); md_watch_queue_destroy(q);
    puts("PASS watch queue: whole records, coalescing, overflow, short buffer, rejected delivery, reuse");
}
static void descriptors(void) {
    struct md_watch_queue *q; int fd;
    CHECK(!md_watch_queue_create(2, 0, &q, &fd));
    CHECK(!(fcntl(fd, F_GETFL) & O_NONBLOCK) && !(fcntl(fd, F_GETFD) & FD_CLOEXEC));
    int alias = dup(fd); CHECK(alias >= 0);
    int ep = epoll_create1(EPOLL_CLOEXEC); CHECK(ep >= 0);
    struct epoll_event e = {.events = EPOLLIN | EPOLLET, .data.fd = fd};
    CHECK(!epoll_ctl(ep, EPOLL_CTL_ADD, fd, &e));
    CHECK(!epoll_wait(ep, &e, 1, 0));
    CHECK(!md_watch_queue_emit(q, 1, IN_MODIFY, 0, NULL));
    CHECK(epoll_wait(ep, &e, 1, 0) == 1 && e.events == EPOLLIN);
    unsigned char bytes[64];
    CHECK(md_watch_queue_read(q, alias, bytes, sizeof(bytes), NULL, NULL) == 16);
    CHECK(!epoll_wait(ep, &e, 1, 0));
    CHECK(!md_watch_queue_emit(q, 1, IN_MODIFY, 0, NULL));
    CHECK(epoll_wait(ep, &e, 1, 0) == 1);
    close(fd); CHECK(!closed(q)); close(alias); CHECK(closed(q)); close(ep);
    md_watch_queue_destroy(q);
    puts("PASS watch queue: poll/epoll readiness, flags, dup and last-close lifetime");
}
static void transferred(void) {
    struct md_watch_queue *q; int fd;
    CHECK(!md_watch_queue_create(2, IN_NONBLOCK, &q, &fd));
    int sockets[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'x'; struct iovec iov = {&byte, 1};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    CHECK(sendmsg(sockets[0], &msg, MSG_NOSIGNAL) == 1);
    close(fd); CHECK(!closed(q));
    CHECK(!md_watch_queue_emit(q, 5, IN_MODIFY, 0, NULL));
    memset(control.bytes, 0, sizeof(control));
    CHECK(recvmsg(sockets[1], &msg, MSG_CMSG_CLOEXEC) == 1);
    CHECK(!(msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)));
    c = CMSG_FIRSTHDR(&msg); CHECK(c && c->cmsg_len == CMSG_LEN(sizeof(fd)));
    memcpy(&fd, CMSG_DATA(c), sizeof(fd));
    CHECK(readable(fd) && !closed(q));
    unsigned char data[64];
    CHECK(md_watch_queue_read(q, fd, data, sizeof(data), NULL, NULL) == 16);
    record(data, 5, IN_MODIFY, 0, NULL);
    close(fd); CHECK(closed(q));
    close(sockets[0]); close(sockets[1]); md_watch_queue_destroy(q);
    puts("PASS watch queue: queued SCM_RIGHTS retains instance until last receiver closes");
}
static void native_control(void) {
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    unsigned char bytes[64]; struct iovec iov = {bytes, sizeof(bytes)};
    errno = 0; ssize_t zero = read(fd, bytes, 0); int zero_error = errno;
    errno = 0; ssize_t vector = readv(fd, &iov, 1); int vector_error = errno;
    printf("CONTROL native-inotify empty zero-read=%zd errno=%d readv=%zd errno=%d\n",
        zero, zero_error, vector, vector_error);
    CHECK(zero == -1 && zero_error == EAGAIN);
    CHECK(vector == -1 && vector_error == EAGAIN);
    char path[] = "/data/local/tmp/md-watch-control-XXXXXX";
    int file = mkstemp(path); CHECK(file >= 0);
    int wd = inotify_add_watch(fd, path, IN_MODIFY); CHECK(wd >= 0);
    CHECK(!unlink(path)); CHECK(write(file, "x", 1) == 1);
    struct iovec vectors[] = {{bytes, 7}, {bytes + 7, sizeof(bytes) - 7}};
    /* The native legacy readv path calls read once per vector: aggregate
     * capacity cannot rescue a first vector shorter than one whole event. */
    errno = 0; CHECK(readv(fd, vectors, 2) == -1 && errno == EINVAL);
    vectors[0].iov_len = sizeof(struct inotify_event);
    vectors[1].iov_base = bytes + sizeof(struct inotify_event);
    vectors[1].iov_len = sizeof(bytes) - sizeof(struct inotify_event);
    CHECK(readv(fd, vectors, 2) == sizeof(struct inotify_event));
    record(bytes, wd, IN_MODIFY, 0, NULL);
    close(file);
    close(fd);
    puts("PASS native inotify control: records cannot span readv vectors; short vector retains record");
}
static void corrupted_marker(void) {
    struct md_watch_queue *q; int fd;
    CHECK(!md_watch_queue_create(2, 0, &q, &fd));
    CHECK(!md_watch_queue_emit(q, 1, IN_MODIFY, 0, NULL));
    char byte; CHECK(read(fd, &byte, 1) == 1 && byte == 1);
    unsigned char bytes[64];
    CHECK(md_watch_queue_read(q, fd, bytes, sizeof(bytes), NULL, NULL) == -EIO);
    CHECK(!(fcntl(fd, F_GETFL) & O_NONBLOCK));
    close(fd); md_watch_queue_destroy(q);
    puts("PASS watch queue: stolen marker fails without blocking or changing guest flags");
}
int main(void) {
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGPIPE);
    CHECK(!sigprocmask(SIG_BLOCK, &mask, NULL));
    records(); descriptors(); transferred(); native_control(); corrupted_marker(); return 0;
}
