#define _GNU_SOURCE
#include "event_wait.h"
#include "fs_service.h"
#include "fs_engine.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sqlite3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(expr) do { if (!(expr)) { \
    fprintf(stderr, "%s:%d: %s (errno=%d)\n", __FILE__, __LINE__, #expr, errno); exit(1); \
} } while (0)
static char root[PATH_MAX];
static unsigned serial;
enum fault { NORMAL, SIGNAL, HOLD_AFTER, COUNT_CLOSE, HOLD_DIRECTORY, COUNT_REPLY };
struct service { pid_t pid; int stop, events, control; char endpoint[96], directory[PATH_MAX]; };
struct hooks { enum fault fault; int notify, control; pid_t client; };
static void byte(int fd) { CHECK(write(fd, "x", 1) == 1); }
static void event(int fd) {
    char value;
    /* EVENT_WAIT: exact peer checkpoint; the outer runner kills a stuck fixture. */
    CHECK(read(fd, &value, 1) == 1 && value == 'x');
}
static void joined(pid_t pid, int expected_signal) {
    int status;
    /* EVENT_WAIT: child exit, bounded by the fixture runner, not a settling delay. */
    CHECK(waitpid(pid, &status, 0) == pid);
    CHECK(expected_signal ? WIFSIGNALED(status) && WTERMSIG(status) == expected_signal
        : WIFEXITED(status) && WEXITSTATUS(status) == 0);
}
static void observe(enum md_fs_checkpoint point, const struct md_fs_packet *packet, void *context) {
    struct hooks *h = context;
    if (point == MD_FS_REPLY_SENT) {
        if (h->fault == COUNT_REPLY) byte(h->notify);
        return;
    }
    if (point == MD_FS_CONNECTION_CLOSED) {
        if (h->fault == COUNT_CLOSE) byte(h->notify);
        return;
    }
    if (point != MD_FS_AFTER_DISPATCH) return;
    if (h->fault == HOLD_DIRECTORY && packet->operation == MD_FS_GETDENTS) { byte(h->notify); event(h->control); }
    if (packet->operation != MD_FS_CREATE) return;
    if (h->fault == SIGNAL) { CHECK(kill(h->client, SIGUSR1) == 0); event(h->control); }
    if (h->fault == HOLD_AFTER) { byte(h->notify); event(h->control); }
}
static void start(struct service *s, enum fault fault, const char *existing, unsigned timeout) {
    snprintf(s->endpoint, sizeof(s->endpoint), "md-fs-lab-%ld-%u", (long)getpid(), serial++);
    if (existing) CHECK(snprintf(s->directory, sizeof(s->directory), "%s", existing) < PATH_MAX);
    else CHECK(snprintf(s->directory, sizeof(s->directory), "%s/store-%u", root, serial) < PATH_MAX);
    struct md_inode_store *store;
    CHECK(md_inode_store_open(s->directory, !existing, &store) == 0); md_inode_store_close(store);
    int listener = md_fs_listen(s->endpoint); CHECK(listener >= 0);
    CHECK(md_fs_listen(s->endpoint) == -EADDRINUSE);
    int stop[2], ready[2], notice[2], control[2];
    CHECK(pipe2(stop, O_CLOEXEC) == 0 && pipe2(ready, O_CLOEXEC) == 0
        && pipe2(notice, O_CLOEXEC) == 0 && pipe2(control, O_CLOEXEC) == 0);
    pid_t parent = getpid(); s->pid = fork(); CHECK(s->pid >= 0);
    if (!s->pid) {
        close(stop[1]); close(ready[0]); close(notice[0]); close(control[1]);
        CHECK(md_inode_store_open(s->directory, 0, &store) == 0);
        struct hooks hooks = {fault, notice[1], control[0], parent};
        md_fs_observe(observe, &hooks); byte(ready[1]); close(ready[1]);
        CHECK(md_fs_serve(&(struct md_filesystem){.store=store}, listener, stop[0], timeout, NULL, NULL) == 0);
        md_inode_store_close(store); close(listener); close(stop[0]); close(notice[1]); close(control[0]);
        _exit(0);
    }
    close(listener); close(stop[0]); close(ready[1]); close(notice[1]); close(control[0]);
    s->stop = stop[1]; s->events = notice[0]; s->control = control[1]; event(ready[0]); close(ready[0]);
}
static void stop(struct service *s, int killed) {
    if (!killed) byte(s->stop);
    joined(s->pid, killed ? SIGKILL : 0);
    close(s->stop); close(s->events); close(s->control);
    struct md_inode_store *store; struct md_inode_audit audit;
    CHECK(md_inode_store_open(s->directory, 0, &store) == 0);
    CHECK(md_inode_audit(store, &audit) == 0); md_inode_store_close(store);
}
static struct md_fs_response call(const char *endpoint, uint32_t op, int a, const char *path,
        int b, const char *second, uint32_t flags, uint32_t mode, int error) {
    struct md_fs_request q = {.operation=op, .flags=flags, .mode=mode, .directory={a,b}, .path={path,second}};
    struct md_fs_response out;
    long r = md_fs_call(endpoint, 5000, &q, &out);
    if (r || out.result.error != error) fprintf(stderr, "rpc op=%u path=%s transport=%ld remote=%d expected=%d delivery=%d\n",
        op, path ? path : "", r, out.result.error, error, out.delivery);
    CHECK(r == 0 && out.delivery == MD_FS_REPLIED && out.result.error == error);
    CHECK(out.result.fd < 0 || (fcntl(out.result.fd, F_GETFD) & FD_CLOEXEC));
    return out;
}
static void semantics(const char *exe) {
    struct service s; start(&s, NORMAL, NULL, 5000);
    const char *e = s.endpoint;
    call(e, MD_FS_MKDIR, -1, "d", -1, NULL, 0, 0700, 0);
    int dir = call(e, MD_FS_OPEN, -1, "d", -1, NULL, O_RDONLY | O_DIRECTORY, 0, 0).result.fd;
    int a = call(e, MD_FS_CREATE, dir, "a", -1, NULL, 0, 0600, 0).result.fd;
    CHECK(a >= 0 && ftruncate(a, 4096) == 0 && pwrite(a, "shared", 6, 0) == 6);
    call(e, MD_FS_LINK, dir, "a", dir, "b", 0, 0, 0);
    int b = call(e, MD_FS_OPEN, dir, "b", -1, NULL, O_RDWR, 0, 0).result.fd;
    struct stat sa, sb; CHECK(fstat(a, &sa) == 0 && fstat(b, &sb) == 0 && sa.st_ino == sb.st_ino);
    struct md_fs_response result = call(e, MD_FS_FSTAT, a, NULL, -1, NULL, 0, 0, 0);
    CHECK(result.result.info.inode == sa.st_ino && result.result.info.links == 2 && result.result.info.uid == getuid());
    char *ma = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, a, 0);
    char *mb = mmap(NULL, 4096, PROT_READ, MAP_SHARED, b, 0);
    CHECK(ma != MAP_FAILED && mb != MAP_FAILED); memcpy(ma, "mapped", 6); CHECK(!memcmp(mb, "mapped", 6));
    CHECK(flock(a, LOCK_EX | LOCK_NB) == 0 && flock(b, LOCK_EX | LOCK_NB) == -1 && errno == EWOULDBLOCK);
    CHECK(flock(a, LOCK_UN) == 0);
    call(e, MD_FS_SYMLINK, dir, "sym", -1, "b", 0, 0, 0);
    result = call(e, MD_FS_READLINK, dir, "sym", -1, NULL, 0, 0, 0);
    CHECK(result.result.size == 1 && result.data[0] == 'b');
    result = call(e, MD_FS_STAT, dir, "sym", -1, NULL, 0, 0, 0); CHECK(result.result.info.inode == sa.st_ino);
    result = call(e, MD_FS_OPEN_IMAGE, dir, "sym", -1, NULL, 0, 0, 0);
    struct md_image_identity image;
    memcpy(&image, result.data, result.result.size);
    CHECK(!strcmp(image.path, "/d/b") && strlen(image.object) == 32);
    CHECK(!fstat(result.result.fd, &sb) && sb.st_ino == sa.st_ino);
    close(result.result.fd);
    call(e, MD_FS_OPEN_IMAGE, dir, "sym", -1, NULL, O_NOFOLLOW, 0, -ELOOP);
    call(e, MD_FS_OPEN_IMAGE, dir, "b", -1, NULL, O_CREAT, 0, -EINVAL);
    call(e, MD_FS_OPEN_IMAGE, dir, "missing", -1, NULL, 0, 0, -ENOENT);
    call(e, MD_FS_OPEN_IMAGE, -1, "/d", -1, NULL, 0, 0, -EACCES);
    call(e, MD_FS_RENAME, -1, "d", -1, "moved", 0, 0, 0);
    result = call(e, MD_FS_PATH, dir, NULL, -1, NULL, 0, 0, 0); CHECK(!strcmp(result.data, "/moved"));
    call(e, MD_FS_UNLINK, dir, "a", -1, NULL, 0, 0, 0);
    call(e, MD_FS_UNLINK, dir, "b", -1, NULL, 0, 0, 0);
    result = call(e, MD_FS_FSTAT, b, NULL, -1, NULL, 0, 0, 0); CHECK(result.result.info.links == 0);
    result = call(e, MD_FS_OBJECT_ID, b, NULL, -1, NULL, 0, 0, 0);
    CHECK(result.result.size == 33);
    char object[33]; memcpy(object, result.data, sizeof(object));
    CHECK(!strcmp(object, image.object));
    int reopened = call(e, MD_FS_OPEN_OBJECT, -1, object, -1, NULL, O_RDONLY, 0, 0).result.fd;
    CHECK(fstat(reopened, &sb) == 0 && sa.st_ino == sb.st_ino);
    close(reopened);
    call(e, MD_FS_OPEN_OBJECT, -1, "../namespace.db", -1, NULL, O_RDONLY, 0, -EINVAL);
    call(e, MD_FS_OPEN_OBJECT, -1, object, -1, NULL, O_WRONLY, 0, -EINVAL);
    call(e, MD_FS_OPEN_OBJECT, -1, object, -1, NULL, O_RDONLY | O_DIRECTORY, 0, -ENOTDIR);
    CHECK(!memcmp(mb, "mapped", 6));
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        CHECK(fcntl(b, F_SETFD, 0) == 0);
        char value[32]; snprintf(value, sizeof(value), "%d", b);
        execl(exe, exe, "child", e, value, NULL); _exit(2);
    }
    joined(child, 0);
    int foreign = open("/dev/null", O_RDONLY); CHECK(foreign >= 0);
    call(e, MD_FS_FSTAT, foreign, NULL, -1, NULL, 0, 0, -EXDEV); close(foreign);
    call(e, MD_FS_UNLINK, dir, "sym", -1, NULL, 0, 0, 0);
    call(e, MD_FS_UNLINK, -1, "moved", -1, NULL, AT_REMOVEDIR, 0, 0);
    result = call(e, MD_FS_FSTAT, dir, NULL, -1, NULL, 0, 0, 0); CHECK(!result.result.info.links);
    call(e, MD_FS_PATH, dir, NULL, -1, NULL, 0, 0, -ENOENT);
    munmap(ma, 4096); munmap(mb, 4096); close(a); close(b); close(dir);
    stop(&s, 0);
    puts("PASS filesystem RPC: native FD/mmap/locks, links, symlinks, moved dirfd and fork/exec");
}
static void *worker(void *context) {
    const char *e = context;
    for (unsigned i = 0; i < 32; ++i) {
        struct md_fs_response r = call(e, MD_FS_STAT, -1, "/", -1, NULL, 0, 0, 0);
        CHECK(S_ISDIR(r.result.info.mode));
    }
    return NULL;
}
static void contention(void) {
    struct service s; start(&s, NORMAL, NULL, 5000);
    int ready[2], release[2]; CHECK(pipe2(ready, O_CLOEXEC) == 0 && pipe2(release, O_CLOEXEC) == 0);
    pid_t locker = fork(); CHECK(locker >= 0);
    if (!locker) {
        close(ready[0]); close(release[1]);
        char path[PATH_MAX]; CHECK(snprintf(path, sizeof(path), "%s/namespace.db", s.directory) < PATH_MAX);
        sqlite3 *db; CHECK(sqlite3_open(path, &db) == SQLITE_OK);
        CHECK(sqlite3_exec(db, "BEGIN EXCLUSIVE", NULL, NULL, NULL) == SQLITE_OK);
        byte(ready[1]); event(release[0]);
        CHECK(sqlite3_exec(db, "COMMIT", NULL, NULL, NULL) == SQLITE_OK);
        CHECK(sqlite3_close(db) == SQLITE_OK); _exit(0);
    }
    close(ready[1]); close(release[0]); event(ready[0]);
    call(s.endpoint, MD_FS_CREATE, -1, "blocked", -1, NULL, 0, 0600, -EAGAIN);
    byte(release[1]); close(ready[0]); close(release[1]); joined(locker, 0);
    call(s.endpoint, MD_FS_STAT, -1, "blocked", -1, NULL, 0, 0, -ENOENT);
    int fd = call(s.endpoint, MD_FS_CREATE, -1, "blocked", -1, NULL, 0, 0600, 0).result.fd;
    close(fd); stop(&s, 0);
    puts("PASS external SQLite contention bypassing store admission remains explicit, without replay");
}
static void concurrent(void) {
    struct service s; start(&s, NORMAL, NULL, 5000);
    pthread_t threads[8];
    for (unsigned i = 0; i < 8; ++i) CHECK(pthread_create(&threads[i], NULL, worker, s.endpoint) == 0);
    /* EVENT_WAIT: worker completion; outer timeout cancels the fixture on a stuck RPC. */
    for (unsigned i = 0; i < 8; ++i) CHECK(pthread_join(threads[i], NULL) == 0);
    stop(&s, 0);
    puts("PASS 256 concurrent calls without shared client socket or database connection");
}
static const char *signal_endpoint;
static int signal_control;
static volatile sig_atomic_t active, nested, nested_error;
static void signal_call(int signo) {
    (void)signo;
    if (!active || write(signal_control, "x", 1) != 1) { nested_error = 1; return; }
    struct md_fs_request request = {.operation=MD_FS_STAT, .directory={-1,-1}, .path={"/",NULL}};
    struct md_fs_response result;
    int saved = errno;
    if (md_fs_call(signal_endpoint, 5000, &request, &result) || result.result.error
            || result.delivery != MD_FS_REPLIED || errno != saved) nested_error = 1;
    ++nested;
}
static void reentrant(void) {
    struct service s; start(&s, SIGNAL, NULL, 5000);
    signal_endpoint = s.endpoint; signal_control = s.control;
    struct sigaction action = {.sa_handler = signal_call}, old;
    sigemptyset(&action.sa_mask); CHECK(sigaction(SIGUSR1, &action, &old) == 0);
    for (unsigned i = 0; i < 8; ++i) {
        char name[32]; snprintf(name, sizeof(name), "signal-%u", i);
        struct md_fs_request q = {.operation=MD_FS_CREATE, .mode=0600, .directory={-1,-1}, .path={name,NULL}};
        struct md_fs_response out;
        active = 1; errno = EDOM;
        long r = md_fs_call(s.endpoint, 5000, &q, &out);
        CHECK(errno == EDOM); active = 0;
        CHECK(r == 0 && !out.result.error && out.result.fd >= 0); close(out.result.fd);
    }
    CHECK(nested == 8 && !nested_error); CHECK(sigaction(SIGUSR1, &old, NULL) == 0);
    stop(&s, 0);
    puts("PASS nested signal-handler RPC during pending outer call; errno unchanged");
}
static int connect_peer(const char *endpoint) {
    struct sockaddr_un address; long n = md_fs_address(endpoint, &address); CHECK(n > 0);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0); CHECK(fd >= 0);
    CHECK(connect(fd, (struct sockaddr *)&address, (socklen_t)n) == 0); return fd;
}
struct watch_delivery { struct service *service; int fd, kill; };
static int reject_watch_delivery(void *context, const void *data, size_t size) {
    struct watch_delivery *d = context;
    const struct inotify_event *event = data;
    CHECK(size == 32 && event->mask == IN_CREATE && !strcmp(event->name, "first"));
    if (d->kill) { CHECK(!kill(d->service->pid, SIGKILL)); return 0; }
    struct md_fs_request q = {.operation = MD_FS_WATCH_READ, .directory = {d->fd, -1}, .capacity = 64};
    struct md_fs_response out;
    CHECK(!md_fs_call(d->service->endpoint, 5000, &q, &out) && out.result.error == -EAGAIN);
    struct pollfd ready = {d->fd, POLLIN, 0};
    CHECK(!poll(&ready, 1, 0));
    close(call(d->service->endpoint, MD_FS_CREATE, -1, "second", -1, NULL, 0, 0600, 0).result.fd);
    return -EFAULT;
}
static void watch_delivery(void) {
    for (int killed = 0; killed < 2; ++killed) {
        struct service s; start(&s, NORMAL, NULL, 5000);
        int dir = call(s.endpoint, MD_FS_OPEN, -1, "/", -1, NULL, O_RDONLY | O_DIRECTORY, 0, 0).result.fd;
        int fd = call(s.endpoint, MD_FS_WATCH_CREATE, -1, NULL, -1, NULL, IN_NONBLOCK, 0, 0).result.fd;
        call(s.endpoint, MD_FS_WATCH_ADD, fd, NULL, dir, NULL, IN_CREATE, 0, 0);
        close(call(s.endpoint, MD_FS_CREATE, -1, "first", -1, NULL, 0, 0600, 0).result.fd);
        struct md_fs_request q = {.operation = MD_FS_WATCH_READ, .directory = {fd, -1}, .capacity = 64};
        struct md_fs_response out;
        struct watch_delivery d = {&s, fd, killed};
        long r = md_fs_call_deliver(s.endpoint, 5000, &q, &out, reject_watch_delivery, &d);
        CHECK(killed ? r < 0 && out.delivery == MD_FS_UNCONFIRMED : r == -EFAULT && out.delivery == MD_FS_REPLIED);
        if (!killed) {
            CHECK(!md_fs_call(s.endpoint, 5000, &q, &out) && !out.result.error && out.result.size == 64);
            const struct inotify_event *a = (void *)out.data, *b = (void *)(out.data + 32);
            CHECK(!strcmp(a->name, "first") && !strcmp(b->name, "second"));
            CHECK(!md_fs_call(s.endpoint, 5000, &q, &out) && out.result.error == -EAGAIN);
            close(call(s.endpoint, MD_FS_CREATE, -1, "third", -1, NULL, 0, 0600, 0).result.fd);
            int peer = connect_peer(s.endpoint);
            struct md_fs_packet packet = {.magic = MD_FS_MAGIC, .version = MD_FS_VERSION,
                .operation = MD_FS_WATCH_READ, .descriptors = 1, .length = {1, 1}, .capacity = 64};
            struct md_fs_rights rights = {.count = 1, .fd = {fd}};
            CHECK(md_fs_send(peer, &packet, offsetof(struct md_fs_packet, data) + 2, &rights) > 0);
            struct md_fs_reply reply;
            CHECK(!md_event_wait_fd(peer, POLLIN, md_event_now() + 5000000000LL));
            CHECK(md_fs_receive(peer, &reply, sizeof(reply), &rights) > 0 && !rights.count && reply.size == 32);
            /* EVENT_WAIT: withheld acknowledgement expires this peer. Timeout
             * must retire its lease, never replay possibly delivered records. */
            CHECK(!md_event_wait_fd(peer, POLLIN, md_event_now() + 7000000000LL));
            CHECK(!md_fs_receive(peer, &reply, sizeof(reply), &rights)); close(peer);
            CHECK(!md_fs_call(s.endpoint, 5000, &q, &out) && out.result.error == -EAGAIN);
        }
        close(fd); close(dir); stop(&s, killed);
    }
    puts("PASS watch RPC: reserved readiness, concurrent requests, rejected copy, missing ACK and service death");
}
static int fd_count(pid_t pid) {
    char path[64]; snprintf(path, sizeof(path), "/proc/%ld/fd", (long)pid);
    DIR *dir = opendir(path); CHECK(dir != NULL);
    int count = 0; struct dirent *entry;
    while ((entry = readdir(dir))) if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) ++count;
    closedir(dir); return count;
}
static void malformed(void) {
    struct service s; start(&s, COUNT_CLOSE, NULL, 5000);
    call(s.endpoint, MD_FS_STAT, -1, "/", -1, NULL, 0, 0, 0); event(s.events);
    int before = fd_count(s.pid);
    int source = open("/dev/null", O_RDONLY); CHECK(source >= 0);
    for (unsigned i = 0; i < 96; ++i) {
        struct md_fs_packet q = {.magic = MD_FS_MAGIC, .version = MD_FS_VERSION,
            .operation = MD_FS_MKDIR, .mode = 0700, .length = {5,1}, .data = "oops"};
        size_t n = offsetof(struct md_fs_packet, data) + 6;
        struct md_fs_rights rights = {0};
        switch (i % 6) {
        case 0: q.version = 99; break;
        case 1: q.length[0] = UINT32_MAX; break;
        case 2: q.data[4] = 'z'; break;
        case 3: rights.count = 1; rights.fd[0] = source; break;
        case 4: n = 1; break;
        case 5: n = sizeof(q); q.length[0] = PATH_MAX; q.length[1] = PATH_MAX; break;
        }
        int socket = connect_peer(s.endpoint);
        CHECK(md_fs_send(socket, &q, n, &rights) == (long)n);
        struct md_fs_reply reply; struct md_fs_rights output;
        CHECK(md_event_wait_fd(socket, POLLIN, md_event_now() + 5000000000LL) == 0);
        CHECK(md_fs_receive(socket, &reply, sizeof(reply), &output) >= (long)offsetof(struct md_fs_reply, data));
        CHECK(reply.error == -EPROTO && !output.count); close(socket); event(s.events);
    }
    for (unsigned i = 0; i < 2; ++i) {
        struct { struct md_fs_packet q; char extra[8]; } packet = {
            .q = {.magic = MD_FS_MAGIC, .version = MD_FS_VERSION, .operation = MD_FS_MKDIR,
                .mode = 0700, .length = {5,1}, .data = "oops"}};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(3 * sizeof(int))]; } control = {0};
        struct iovec io = {&packet, i ? offsetof(struct md_fs_packet, data) + 6 : sizeof(packet)};
        struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1,
            .msg_control = control.bytes, .msg_controllen = sizeof(control.bytes)};
        struct cmsghdr *c = (struct cmsghdr *)control.bytes;
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(3 * sizeof(int));
        int sources[3] = {source, source, source}; memcpy(CMSG_DATA(c), sources, sizeof(sources));
        int socket = connect_peer(s.endpoint);
        CHECK(sendmsg(socket, &message, MSG_NOSIGNAL) == (ssize_t)io.iov_len);
        struct md_fs_reply reply; struct md_fs_rights output;
        CHECK(md_event_wait_fd(socket, POLLIN, md_event_now() + 5000000000LL) == 0);
        CHECK(md_fs_receive(socket, &reply, sizeof(reply), &output) >= (long)offsetof(struct md_fs_reply, data));
        CHECK(reply.error == -EPROTO && !output.count); close(socket); event(s.events);
    }
    CHECK(fd_count(s.pid) == before); close(source);
    call(s.endpoint, MD_FS_STAT, -1, "oops", -1, NULL, 0, 0, -ENOENT); event(s.events);
    call(s.endpoint, 999, -1, NULL, -1, NULL, 0, 0, -ENOTSUP); event(s.events);
    stop(&s, 0);
    puts("PASS malformed requests rejected before mutation; transferred FDs do not leak");
}
static void idle(void) {
    struct service s; start(&s, NORMAL, NULL, 200);
    int socket = connect_peer(s.endpoint);
    call(s.endpoint, MD_FS_STAT, -1, "/", -1, NULL, 0, 0, 0);
    CHECK(md_event_wait_fd(socket, POLLIN, md_event_now() + 5000000000LL) == 0);
    char byte; CHECK(read(socket, &byte, 1) == 0); close(socket);
    stop(&s, 0);
    puts("PASS idle peer does not block other requests and is closed at its failure deadline");
}
static void reply_lifetime(void) {
    struct service s; start(&s, COUNT_REPLY, NULL, 5000);
    int socket = connect_peer(s.endpoint);
    struct md_fs_packet q = {.magic = MD_FS_MAGIC, .version = MD_FS_VERSION,
        .operation = MD_FS_CREATE, .mode = 0600, .length = {5,1}, .data = "held"};
    struct md_fs_rights rights = {0};
    CHECK(md_fs_send(socket, &q, offsetof(struct md_fs_packet, data) + 6, &rights) > 0);
    event(s.events);
    /* Another completed request makes the service advance beyond the first send. */
    call(s.endpoint, MD_FS_STAT, -1, "held", -1, NULL, 0, 0, 0); event(s.events);
    struct md_fs_reply reply;
    CHECK(md_fs_receive(socket, &reply, sizeof(reply), &rights) == (long)offsetof(struct md_fs_reply, data));
    CHECK(!reply.error && rights.count == 1);
    CHECK(write(rights.fd[0], "once", 4) == 4);
    md_fs_close_rights(&rights);
    CHECK(md_fs_receive(socket, &reply, sizeof(reply), &rights) == -EAGAIN);
    close(socket);
    struct md_fs_response out = call(s.endpoint, MD_FS_STAT, -1, "held", -1, NULL, 0, 0, 0); event(s.events);
    CHECK(out.result.info.size == 4 && out.result.info.links == 1);
    stop(&s, 0);
    puts("PASS reply survives until client close; another request progresses without replay");
}
static void lost_reply(void) {
    struct service s; start(&s, HOLD_AFTER, NULL, 5000);
    pid_t killer = fork(); CHECK(killer >= 0);
    if (!killer) { event(s.events); CHECK(kill(s.pid, SIGKILL) == 0); _exit(0); }
    struct md_fs_request q = {.operation=MD_FS_CREATE, .mode=0600, .directory={-1,-1}, .path={"committed",NULL}};
    struct md_fs_response out;
    CHECK(md_fs_call(s.endpoint, 5000, &q, &out) < 0 && out.delivery == MD_FS_UNCONFIRMED && out.result.fd == -1);
    joined(killer, 0); stop(&s, 1);
    char saved[PATH_MAX]; strcpy(saved, s.directory);
    start(&s, NORMAL, saved, 5000);
    out = call(s.endpoint, MD_FS_STAT, -1, "committed", -1, NULL, 0, 0, 0);
    CHECK(out.result.info.links == 1 && out.result.info.size == 0);
    stop(&s, 0);
    puts("PASS server death after commit is UNCONFIRMED, no replay; namespace recovered");
}
enum reply_fault { UNEXPECTED_FD, REPLY_TIMEOUT, IMAGE_SHORT, IMAGE_ID, IMAGE_ID_END,
    IMAGE_RELATIVE, IMAGE_END, IMAGE_NUL, IMAGE_NO_FD, REPLY_FAULT_COUNT };
static void faulty_reply(enum reply_fault fault) {
    int timeout = fault == REPLY_TIMEOUT;
    char endpoint[96]; snprintf(endpoint, sizeof(endpoint), "md-fs-bad-%ld-%u", (long)getpid(), serial++);
    int listener = md_fs_listen(endpoint); CHECK(listener >= 0);
    int stop[2], received[2]; CHECK(pipe2(stop, O_CLOEXEC) == 0 && pipe2(received, O_CLOEXEC) == 0);
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(stop[1]); close(received[0]);
        CHECK(md_event_wait_fd(listener, POLLIN, md_event_now() + 5000000000LL) == 0);
        int socket = accept4(listener, NULL, NULL, SOCK_CLOEXEC); CHECK(socket >= 0);
        CHECK(md_event_wait_fd(socket, POLLIN, md_event_now() + 5000000000LL) == 0);
        struct md_fs_packet q; struct md_fs_rights input;
        CHECK(md_fs_receive(socket, &q, sizeof(q), &input) > 0); md_fs_close_rights(&input); byte(received[1]);
        if (timeout) event(stop[0]);
        else {
            int fd = open("/dev/null", O_RDONLY); CHECK(fd >= 0);
            struct md_fs_reply r = {.magic = MD_FS_MAGIC, .version = MD_FS_VERSION, .descriptors = 1};
            struct md_fs_rights output = {.count = 1, .fd = {fd}};
            if (fault >= IMAGE_SHORT) {
                struct md_image_identity *image = (void *)r.data;
                memset(image->object, 'a', 32);
                strcpy(image->path, "/program");
                r.size = offsetof(struct md_image_identity, path) + strlen(image->path) + 1;
                switch (fault) {
                case IMAGE_SHORT: r.size = 32; break;
                case IMAGE_ID: image->object[0] = 'g'; break;
                case IMAGE_ID_END: image->object[32] = 'a'; break;
                case IMAGE_RELATIVE: image->path[0] = 'a'; break;
                case IMAGE_END: r.data[r.size - 1] = 'a'; break;
                case IMAGE_NUL: image->path[2] = 0; break;
                case IMAGE_NO_FD: output.count = r.descriptors = 0; break;
                default: CHECK(0);
                }
            }
            CHECK(md_fs_send(socket, &r, offsetof(struct md_fs_reply, data) + r.size, &output) > 0); close(fd);
        }
        close(socket); close(listener); _exit(0);
    }
    close(listener); close(stop[0]); close(received[1]);
    struct md_fs_request q = {.operation=MD_FS_STAT, .directory={-1,-1}, .path={"/",NULL}};
    if (fault >= IMAGE_SHORT) q.operation = MD_FS_OPEN_IMAGE;
    struct md_fs_response out; int before = fd_count(getpid());
    CHECK(md_fs_call(endpoint, timeout ? 100 : 5000, &q, &out) == (timeout ? -ETIMEDOUT : -EPROTO));
    CHECK(out.delivery == MD_FS_UNCONFIRMED && out.result.fd == -1 && fd_count(getpid()) == before);
    event(received[0]); if (timeout) byte(stop[1]); close(stop[1]); close(received[0]); joined(child, 0);
}
static struct md_fs_response directory_call(const char *endpoint, int fd, uint32_t op,
        unsigned capacity, int64_t offset, int whence, int error) {
    struct md_fs_request q = {.operation=op, .directory={fd,-1}, .capacity=capacity,
        .offset=offset, .flags=(uint32_t)whence};
    struct md_fs_response out;
    CHECK(md_fs_call(endpoint, 5000, &q, &out) == 0 && out.delivery == MD_FS_REPLIED && out.result.error == error);
    return out;
}
static struct md_inode_dirent entry(const struct md_fs_response *r, const char *name, int type) {
    struct md_inode_dirent d;
    CHECK(r->result.size >= offsetof(struct md_inode_dirent, name));
    memcpy(&d, r->data, offsetof(struct md_inode_dirent, name));
    CHECK(d.size == r->result.size && d.type == type && !strcmp(r->data+offsetof(struct md_inode_dirent, name), name));
    return d;
}
struct reader { const char *endpoint; int fd; uint64_t mask; };
static void *directory_reader(void *context) {
    struct reader *reader = context;
    for (;;) {
        struct md_fs_response r = directory_call(reader->endpoint, reader->fd, MD_FS_GETDENTS, 24, 0, 0, 0);
        if (!r.result.size) break;
        const char *name = r.data+offsetof(struct md_inode_dirent, name);
        unsigned index;
        if (!strcmp(name,".")) index=48;
        else if (!strcmp(name,"..")) index=49;
        else { CHECK(sscanf(name,"n%u",&index) == 1 && index < 48); }
        CHECK(!(reader->mask & (1ULL << index))); reader->mask |= 1ULL << index;
    }
    return NULL;
}
static void directories(const char *exe) {
    struct service s; start(&s, NORMAL, NULL, 5000);
    call(s.endpoint, MD_FS_MKDIR, -1, "d", -1, NULL, 0, 0700, 0);
    int fd = call(s.endpoint, MD_FS_OPEN, -1, "d", -1, NULL, O_RDONLY|O_DIRECTORY, 0, 0).result.fd;
    int f = call(s.endpoint, MD_FS_CREATE, fd, "f", -1, NULL, 0, 0600, 0).result.fd;
    struct stat st; CHECK(fstat(f,&st)==0); close(f);
    call(s.endpoint, MD_FS_SYMLINK, fd, "sym", -1, "f", 0, 0, 0);
    call(s.endpoint, MD_FS_MKDIR, fd, "sub", -1, NULL, 0, 0700, 0);
    call(s.endpoint, MD_FS_LINK, fd, "f", fd, "alias", 0, 0, 0);
    directory_call(s.endpoint, fd, MD_FS_GETDENTS, 1, 0, 0, -EINVAL);
    CHECK(directory_call(s.endpoint, fd, MD_FS_SEEKDIR, 0, 0, SEEK_CUR, 0).result.position==0);
    struct md_fs_response r=directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,0);
    CHECK(entry(&r,".",DT_DIR).next==1);
    int duplicate=dup(fd); CHECK(duplicate>=0);
    r=directory_call(s.endpoint,duplicate,MD_FS_GETDENTS,24,0,0,0); CHECK(entry(&r,"..",DT_DIR).next==2);
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        CHECK(fcntl(fd,F_SETFD,0)==0); char value[32]; snprintf(value,sizeof(value),"%d",fd);
        execl(exe,exe,"directory-child",s.endpoint,value,NULL); _exit(2);
    }
    joined(child,0);
    int64_t cookie=directory_call(s.endpoint,fd,MD_FS_SEEKDIR,0,0,SEEK_CUR,0).result.position; CHECK(cookie>2);
    char saved[PATH_MAX]; strcpy(saved,s.directory); stop(&s,0); start(&s,NORMAL,saved,5000);
    CHECK(directory_call(s.endpoint,duplicate,MD_FS_SEEKDIR,0,0,SEEK_CUR,0).result.position==cookie);
    r=directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,0); entry(&r,"sym",DT_LNK);
    r=directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,0); entry(&r,"sub",DT_DIR);
    r=directory_call(s.endpoint,fd,MD_FS_GETDENTS,32,0,0,0); CHECK(entry(&r,"alias",DT_REG).inode==st.st_ino);
    CHECK(!directory_call(s.endpoint,fd,MD_FS_GETDENTS,PATH_MAX,0,0,0).result.size);
    int independent=call(s.endpoint,MD_FS_OPEN,-1,"d",-1,NULL,O_RDONLY|O_DIRECTORY,0,0).result.fd;
    CHECK(directory_call(s.endpoint,independent,MD_FS_SEEKDIR,0,0,SEEK_CUR,0).result.position==0); close(independent);
    directory_call(s.endpoint,fd,MD_FS_SEEKDIR,0,-1,SEEK_SET,-EINVAL);
    directory_call(s.endpoint,fd,MD_FS_SEEKDIR,0,0,SEEK_END,-EINVAL);
    directory_call(s.endpoint,fd,MD_FS_GETDENTS,PATH_MAX+1,0,0,-EINVAL);
    int path=call(s.endpoint,MD_FS_OPEN,-1,"d",-1,NULL,O_PATH|O_DIRECTORY,0,0).result.fd;
    directory_call(s.endpoint,path,MD_FS_GETDENTS,24,0,0,-EBADF); close(path);
    call(s.endpoint,MD_FS_RENAME,-1,"d",-1,"moved",0,0,0);
    directory_call(s.endpoint,fd,MD_FS_SEEKDIR,0,cookie,SEEK_SET,0);
    call(s.endpoint,MD_FS_UNLINK,fd,"sym",-1,NULL,0,0,0);
    r=directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,0); entry(&r,"sub",DT_DIR);
    close(duplicate); close(fd); stop(&s,0);

    start(&s,NORMAL,NULL,5000);
    call(s.endpoint,MD_FS_MKDIR,-1,"d",-1,NULL,0,0700,0);
    fd=call(s.endpoint,MD_FS_OPEN,-1,"d",-1,NULL,O_RDONLY|O_DIRECTORY,0,0).result.fd;
    for(unsigned i=0;i<48;++i) {
        char name[16]; snprintf(name,sizeof(name),"n%02u",i);
        close(call(s.endpoint,MD_FS_CREATE,fd,name,-1,NULL,0,0600,0).result.fd);
    }
    struct reader readers[8]; pthread_t threads[8]; uint64_t mask=0;
    for(unsigned i=0;i<8;++i) {
        readers[i]=(struct reader){.endpoint=s.endpoint,.fd=fd};
        CHECK(pthread_create(&threads[i],NULL,directory_reader,&readers[i])==0);
    }
    /* EVENT_WAIT: concurrent readers finish at EOF; fixture deadline detects a stuck service. */
    for(unsigned i=0;i<8;++i) {
        CHECK(pthread_join(threads[i],NULL)==0 && !(mask&readers[i].mask)); mask|=readers[i].mask;
    }
    CHECK(mask==(1ULL<<50)-1);
    cookie=directory_call(s.endpoint,fd,MD_FS_SEEKDIR,0,0,SEEK_CUR,0).result.position;
    for(unsigned i=0;i<48;++i) {
        char name[16]; snprintf(name,sizeof(name),"n%02u",i);
        call(s.endpoint,MD_FS_UNLINK,fd,name,-1,NULL,0,0,0);
    }
    close(call(s.endpoint,MD_FS_CREATE,fd,"new",-1,NULL,0,0600,0).result.fd);
    r=directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,0); CHECK(entry(&r,"new",DT_REG).next>cookie);
    call(s.endpoint,MD_FS_UNLINK,fd,"new",-1,NULL,0,0,0);
    call(s.endpoint,MD_FS_UNLINK,-1,"d",-1,NULL,AT_REMOVEDIR,0,0);
    directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,-ENOENT); close(fd); stop(&s,0);
    puts("PASS directory RPC: paging, shared dup/fork/exec cursor, service restart, cookies and concurrent readers");
}
static void lost_directory_reply(void) {
    struct service s; start(&s,NORMAL,NULL,5000);
    int fd=call(s.endpoint,MD_FS_OPEN,-1,"/",-1,NULL,O_RDONLY|O_DIRECTORY,0,0).result.fd;
    char saved[PATH_MAX]; strcpy(saved,s.directory); stop(&s,0); start(&s,HOLD_DIRECTORY,saved,5000);
    pid_t killer=fork(); CHECK(killer>=0);
    if(!killer) { event(s.events); CHECK(kill(s.pid,SIGKILL)==0); _exit(0); }
    struct md_fs_request q={.operation=MD_FS_GETDENTS,.directory={fd,-1},.capacity=24};
    struct md_fs_response out;
    CHECK(md_fs_call(s.endpoint,5000,&q,&out)<0 && out.delivery==MD_FS_UNCONFIRMED);
    joined(killer,0); stop(&s,1); start(&s,NORMAL,saved,5000);
    CHECK(directory_call(s.endpoint,fd,MD_FS_SEEKDIR,0,0,SEEK_CUR,0).result.position==1);
    out=directory_call(s.endpoint,fd,MD_FS_GETDENTS,24,0,0,0); entry(&out,"..",DT_DIR);
    close(fd); stop(&s,0);
    puts("PASS lost getdents reply: shared cursor already advanced, UNCONFIRMED and no replay");
}
static void descriptor_stress(unsigned count) {
    struct service s; start(&s, NORMAL, NULL, 5000);
    int fd = call(s.endpoint, MD_FS_CREATE, -1, "payload", -1, NULL, 0, 0600, 0).result.fd;
    CHECK(write(fd, "test", 4) == 4);
    for (unsigned i = 0; i < count; i++) {
        struct md_fs_response out = call(s.endpoint, MD_FS_FSTAT, fd, NULL, -1, NULL, 0, 0, 0);
        CHECK(out.result.info.size == 4 && S_ISREG(out.result.info.mode));
    }
    close(fd); stop(&s, 0);
    printf("PASS %u descriptor RPC exchanges without response loss\n", count);
}
struct publication { int fd, calls, error; };
static int engine_publish(void *context, const void *data, size_t size) {
    struct publication *p = context;
    CHECK(lseek(p->fd, 0, SEEK_CUR) == 0 && size > PATH_MAX);
    CHECK(!strcmp(((const struct md_inode_dirent *)data)->name, "."));
    p->calls++;
    return p->error;
}
static void engine_buffers(void) {
    char path[PATH_MAX]; CHECK(snprintf(path, sizeof(path), "%s/engine", root) < PATH_MAX);
    struct md_inode_store *s;
    CHECK(!md_inode_store_open(path, 1, &s));
    int file = md_inode_create(s, MD_INODE_ROOT, "source", 0600); CHECK(file >= 0);
    char name[96];
    for (unsigned i = 0; i < 512; i++) {
        snprintf(name, sizeof(name), "%090u", i);
        CHECK(!md_inode_link(s, MD_INODE_ROOT, "source", MD_INODE_ROOT, name, 0));
    }
    int fd = md_inode_open(s, MD_INODE_ROOT, "/", O_RDONLY | O_DIRECTORY, 0); CHECK(fd >= 0);
    unsigned char data[64 * 1024 + 16]; memset(data, 0x5a, sizeof(data));
    struct publication p = {.fd = fd, .error = -ECANCELED};
    struct md_fs_output output = {.data = data, .capacity = sizeof(data) - 16,
        .deliver = engine_publish, .context = &p};
    struct md_fs_request q = {.operation = MD_FS_GETDENTS, .directory = {fd, -1}, .capacity = UINT32_MAX};
    struct md_fs_result r;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, NULL);
    CHECK(r.error == -EFAULT && !r.size && lseek(fd, 0, SEEK_CUR) == 0);
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output);
    CHECK(r.error == -ECANCELED && !r.size && lseek(fd, 0, SEEK_CUR) == 0 && p.calls == 1);
    p.error = 0;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output);
    CHECK(!r.error && r.size > PATH_MAX && r.size <= output.capacity && p.calls == 2);
    for (size_t i = output.capacity; i < sizeof(data); i++) CHECK(data[i] == 0x5a);
    unsigned records = 0, aliases = 0; unsigned char seen[512] = {0};
    for (size_t offset = 0; offset < r.size;) {
        struct md_inode_dirent *e = (void *)(data + offset);
        CHECK(e->size && offset + e->size <= r.size); offset += e->size; records++;
        if (e->name[0] == '.') continue;
        if (!strcmp(e->name, "source")) continue;
        unsigned index = (unsigned)strtoul(e->name, NULL, 10);
        CHECK(index < 512 && !seen[index]); seen[index] = 1; aliases++;
    }
    CHECK(records == 515 && aliases == 512);
    output.deliver = NULL;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(!r.error && !r.size);
    CHECK(md_inode_seekdir(s, fd, 0, SEEK_SET) == 0);
    output.capacity = 24; memset(data, 0x5a, sizeof(data));
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(!r.error && r.size == 24);
    for (size_t i = 24; i < sizeof(data); i++) CHECK(data[i] == 0x5a);
    q = (struct md_fs_request){.operation = MD_FS_FSTAT, .directory = {file, -1}};
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, NULL); CHECK(!r.error && r.info.links == 513);
    q.operation = MD_FS_OBJECT_ID; output.capacity = 32;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(r.error == -ERANGE && !r.size);
    output.capacity = 33;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(!r.error && r.size == 33 && !data[32]);
    q = (struct md_fs_request){.operation = MD_FS_OPEN_IMAGE, .directory = {-1, -1}, .path = {"source", NULL}};
    output.capacity = sizeof(struct md_image_identity) - 1;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(r.error == -ERANGE && r.fd < 0 && !r.size);
    output.capacity = sizeof(struct md_image_identity); memset(data, 0x5a, sizeof(data));
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output);
    CHECK(!r.error && r.fd >= 0 && r.size == 41);
    CHECK(!strcmp(((struct md_image_identity *)data)->path, "/source"));
    for (size_t i = output.capacity; i < sizeof(data); i++) CHECK(data[i] == 0x5a);
    close(r.fd);
    int socket_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); CHECK(socket_fd >= 0);
    q = (struct md_fs_request){.operation = MD_FS_SOCKET_BIND, .directory = {-1, socket_fd},
        .path = {"socket", NULL}, .mode = 0600};
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, NULL); CHECK(!r.error);
    q.operation = MD_FS_SOCKET_ADDRESS;
    output.data = NULL; output.capacity = sizeof(data);
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(r.error == -EFAULT && !r.size);
    output.data = data;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(!r.error && r.size > 33);
    char address[108]; CHECK(r.size < sizeof(address)); memcpy(address, data, r.size);
    q = (struct md_fs_request){.operation = MD_FS_SOCKET_NAME, .path = {address, NULL}};
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, NULL); CHECK(r.error == -EFAULT && !r.size);
    output.capacity = 6;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(r.error == -ERANGE && !r.size);
    output.capacity = 7;
    md_fs_execute(&(struct md_filesystem){.store=s}, &q, &r, &output); CHECK(!r.error && r.size == 7 && !strcmp((char *)data, "socket"));
    close(socket_fd);
    close(file); close(fd); md_inode_store_close(s);
    puts("PASS transport-independent engine: large/bounded output, exact records, publication and metadata-only calls");
}
int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "stress")) {
        CHECK(argv[2][0] == '/' && strlen(argv[2]) < sizeof(root)); strcpy(root, argv[2]);
        CHECK(mkdir(root, 0700) == 0);
        descriptor_stress(100000); return 0;
    }
    if (argc==4 && !strcmp(argv[1],"directory-child")) {
        struct md_fs_response r=directory_call(argv[2],atoi(argv[3]),MD_FS_GETDENTS,24,0,0,0);
        entry(&r,"f",DT_REG); return 0;
    }
    if (argc == 4 && !strcmp(argv[1], "child")) {
        int fd = atoi(argv[3]);
        struct md_fs_response r = call(argv[2], MD_FS_FSTAT, fd, NULL, -1, NULL, 0, 0, 0);
        CHECK(!r.result.info.links && r.result.info.size == 4096); char text[6];
        CHECK(pread(fd, text, sizeof(text), 0) == 6 && !memcmp(text, "mapped", 6)); close(fd); return 0;
    }
    CHECK(argc == 2 && argv[1][0] == '/' && strlen(argv[1]) < sizeof(root)); strcpy(root, argv[1]);
    CHECK(mkdir(root, 0700) == 0);
    engine_buffers();
    semantics(argv[0]); directories(argv[0]); lost_directory_reply(); concurrent(); contention(); reentrant(); malformed(); idle(); lost_reply();
    reply_lifetime(); watch_delivery(); descriptor_stress(10000);
    for (enum reply_fault fault = UNEXPECTED_FD; fault < REPLY_FAULT_COUNT; fault++) faulty_reply(fault);
    struct md_fs_request q = {.operation=MD_FS_STAT, .directory={-1,-1}, .path={"/",NULL}};
    struct md_fs_response result;
    CHECK(md_fs_call("md-fs-lab-not-listening", 100, &q, &result) == -ECONNREFUSED
        && result.delivery == MD_FS_NOT_SENT);
    puts("PASS transport failure/timeout classification and malformed-reply FD cleanup");
    puts("PASS filesystem service fixture (explicit RPC, not guest syscall integration)");
    return 0;
}
