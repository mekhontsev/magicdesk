#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "watch line=%d %s errno=%d\n", __LINE__, #x, errno); exit(1); } } while (0)
static union { struct inotify_event align; char bytes[65536]; } buffer;
static ssize_t events(int fd) {
    struct pollfd p = {fd, POLLIN, 0};
    /* EVENT_WAIT: actual inotify readability; expiry fails the fixture. */
    CHECK(poll(&p, 1, 5000) == 1 && (p.revents & POLLIN));
    ssize_t n = read(fd, buffer.bytes, sizeof(buffer.bytes));
    CHECK(n > 0); return n;
}
static struct inotify_event *one(int fd, unsigned mask, const char *name) {
    ssize_t n = events(fd);
    struct inotify_event *e = &buffer.align;
    CHECK(n == (ssize_t)(sizeof(*e) + e->len));
    CHECK(e->mask == mask);
    CHECK(name ? e->len && !strcmp(name, e->name) : !e->len);
    return e;
}
static void create(const char *name) {
    int fd = open(name, O_CREAT | O_EXCL | O_RDWR, 0600); CHECK(fd >= 0); CHECK(!close(fd));
}
static int filters(void) {
    FILE *status = fopen("/proc/self/status", "r"); CHECK(status);
    char line[256]; int count = -1;
    while (fgets(line, sizeof(line), status)) if (sscanf(line, "Seccomp_filters: %d", &count) == 1) break;
    CHECK(!fclose(status) && count > 0); return count;
}
static int exec_watch(int fd, int round, int expected) {
    one(fd, IN_CREATE, "exec-event");
    CHECK(!unlink("exec-event"));
    int transient = inotify_init1(IN_CLOEXEC | IN_NONBLOCK); CHECK(transient >= 0);
    CHECK(!close(transient));
    int count = filters();
    if (expected) CHECK(count == expected);
    if (!round) { CHECK(!close(fd)); puts("PASS inherited watch and stable filters across 64 execs"); return 0; }
    create("exec-event");
    char descriptor[24], remaining[24], installed[24];
    snprintf(descriptor, sizeof(descriptor), "%d", fd);
    snprintf(remaining, sizeof(remaining), "%d", round - 1);
    snprintf(installed, sizeof(installed), "%d", count);
    execl("/bin/watch", "watch", "exec", descriptor, remaining, installed, (char *)NULL);
    CHECK(0); return 1;
}
static volatile sig_atomic_t interrupted;
static int signal_writer = -1;
static void signal_handler(int number) {
    (void)number; interrupted++;
    if (signal_writer >= 0 && write(signal_writer, "x", 1) != 1) _exit(1);
}
struct reader { int fd; char name[16]; };
static void *read_thread(void *context) {
    struct reader *r = context;
    char event[32]; CHECK(read(r->fd, event, sizeof(event)) == 32);
    struct inotify_event *e = (void *)event;
    CHECK(e->mask == IN_CREATE && strlen(e->name) < sizeof(r->name));
    strcpy(r->name, e->name); return NULL;
}
static void transfer(int socket, int fd) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char value = 1; struct iovec vector = {&value, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *h = CMSG_FIRSTHDR(&message);
    h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(h), &fd, sizeof(fd)); CHECK(sendmsg(socket, &message, 0) == 1);
}
static int receive_fd(int socket, int batch) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char value; struct iovec vector = {&value, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    if (batch) {
        struct mmsghdr messages[] = {{.msg_hdr = message}};
        CHECK(recvmmsg(socket, messages, 1, MSG_CMSG_CLOEXEC, NULL) == 1 && messages[0].msg_len == 1);
        message = messages[0].msg_hdr;
    } else CHECK(recvmsg(socket, &message, MSG_CMSG_CLOEXEC) == 1);
    struct cmsghdr *h = CMSG_FIRSTHDR(&message);
    CHECK(h && h->cmsg_level == SOL_SOCKET && h->cmsg_type == SCM_RIGHTS && h->cmsg_len == CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd, CMSG_DATA(h), sizeof(fd)); return fd;
}
static int shared(const char *mode, const char *directory) {
    if (!strcmp(mode, "setup")) { CHECK(!mkdir(directory, 0700)); return 0; }
    CHECK(!chdir(directory));
    if (!strcmp(mode, "writer")) {
        create("payload"); CHECK(!rename("payload", "renamed")); CHECK(!unlink("renamed"));
        return 0;
    }
    CHECK(!strcmp(mode, "observer"));
    int fd = inotify_init1(IN_CLOEXEC); CHECK(fd >= 0);
    int wd = inotify_add_watch(fd, ".", IN_CREATE | IN_MOVED_FROM | IN_MOVED_TO | IN_DELETE); CHECK(wd > 0);
    puts("READY"); fflush(stdout);
    unsigned masks[] = {IN_CREATE, IN_MOVED_FROM, IN_MOVED_TO, IN_DELETE};
    const char *names[] = {"payload", "payload", "renamed", "renamed"};
    unsigned seen = 0, cookie = 0;
    while (seen < 4) {
        ssize_t n = events(fd);
        for (size_t pos = 0; pos < (size_t)n;) {
            struct inotify_event *e = (void *)(buffer.bytes + pos);
            CHECK(seen < 4 && e->wd == wd && e->mask == masks[seen] && !strcmp(e->name, names[seen]));
            if (seen == 1) { cookie = e->cookie; CHECK(cookie); }
            if (seen == 2) CHECK(e->cookie == cookie);
            seen++; pos += sizeof(*e) + e->len;
        }
    }
    CHECK(!close(fd)); puts("PASS independent launch journal"); fflush(stdout);
    return 0;
}
static void describe_data(int fd, const char *phase) {
    printf("PHASE %s\n", phase);
    for (;;) {
        ssize_t size = read(fd, buffer.bytes, sizeof(buffer.bytes));
        if (size < 0 && errno == EAGAIN) break;
        CHECK(size > 0);
        for (size_t pos = 0; pos < (size_t)size;) {
            struct inotify_event *e = (void *)(buffer.bytes + pos);
            CHECK(!(e->mask & IN_Q_OVERFLOW));
            printf("EVENT mask=%x name=%s\n", e->mask, e->len ? e->name : "-");
            pos += sizeof(*e) + e->len;
        }
    }
}
static int alias_history(const char *directory) {
    CHECK(!mkdir(directory, 0700) && !chdir(directory));
    int file = open("first", O_CREAT | O_EXCL | O_RDWR, 0600); CHECK(file >= 0);
    CHECK(!link("first", "alias"));
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    CHECK(inotify_add_watch(fd, ".", IN_MODIFY | IN_CLOSE_WRITE) > 0);
    CHECK(write(file, "a", 1) == 1); describe_data(fd, "linked-write");
    CHECK(!rename("first", "moved"));
    CHECK(write(file, "b", 1) == 1); describe_data(fd, "renamed-write");
    CHECK(!unlink("moved"));
    CHECK(write(file, "c", 1) == 1); describe_data(fd, "unlinked-open-write");
    CHECK(!close(file)); describe_data(fd, "last-close");
    CHECK(!close(fd));
    return 0;
}
int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "aliases")) return alias_history(argv[2]);
    if (argc == 3 && !strcmp(argv[1], "protected-all")) {
        CHECK(!prctl(PR_SET_DUMPABLE, 0));
        argv[1] = argv[2]; argc = 2;
    }
    if (argc == 5 && !strcmp(argv[1], "exec")) return exec_watch(atoi(argv[2]), atoi(argv[3]), atoi(argv[4]));
    if (argc == 4 && !strcmp(argv[1], "shared")) return shared(argv[2], argv[3]);
    if (argc == 3 && !strcmp(argv[1], "protected")) {
        CHECK(!mkdir(argv[2], 0700) && !chdir(argv[2]));
        int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
        CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
        CHECK(!prctl(PR_SET_DUMPABLE, 0));
        create("protected");
        one(fd, IN_CREATE, "protected");
        CHECK(prctl(PR_GET_DUMPABLE) == 0);
        int bytes = -1; CHECK(!ioctl(fd, FIONREAD, &bytes) && !bytes);
        create("vector");
        struct iovec v = {buffer.bytes, sizeof(buffer.bytes)};
        CHECK(readv(fd, &v, 1) == 32 && !strcmp(buffer.align.name, "vector"));
        CHECK(!close(fd)); puts("PASS protected read/readv/ioctl without changing dumpability"); return 0;
    }
    if (argc == 2 && !strcmp(argv[1], "churn")) {
        int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
        int before = filters();
        for (int i = 512; i < 4096; ++i) {
            int alias = fcntl(fd, F_DUPFD_CLOEXEC, i);
            if (alias < 0) {
                fprintf(stderr, "CHURN failed descriptor=%d errno=%d filters=%d initial=%d\n", i, errno, filters(), before);
                return 1;
            }
            CHECK(alias == i && !close(alias));
        }
        CHECK(filters() <= before + 512);
        int count = filters();
        for (int i = 4096; i < 8192; ++i) {
            CHECK(dup3(fd, i, O_CLOEXEC) == i && !close(i));
        }
        CHECK(filters() == count);
        int ordinary = open("/dev/zero", O_RDONLY); CHECK(ordinary >= 0);
        char byte = 1; CHECK(read(ordinary, &byte, 1) == 1 && !byte);
        CHECK(!close(ordinary));
        CHECK(!close(fd)); printf("PASS distinct descriptor churn filters=%d initial=%d\n", filters(), before); return 0;
    }
    CHECK(argc == 2);
    CHECK(!mkdir(argv[1], 0700)); CHECK(!chdir(argv[1]));
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    CHECK(fcntl(fd, F_GETFL) & O_NONBLOCK); CHECK(fcntl(fd, F_GETFD) & FD_CLOEXEC);
    int wd = inotify_add_watch(fd, ".", IN_CREATE | IN_DELETE | IN_MOVED_FROM | IN_MOVED_TO | IN_UNMOUNT); CHECK(wd > 0);
    errno = 0; CHECK(read(fd, buffer.bytes, 0) == -1 && errno == EAGAIN);
    create("one");
    struct pollfd p = {fd, POLLIN, 0}; CHECK(poll(&p, 1, 5000) == 1);
    int available = 0; CHECK(!ioctl(fd, FIONREAD, &available) && available >= 32);
    int sink = open("/dev/null", O_WRONLY); CHECK(sink >= 0);
    CHECK(sendfile(sink, fd, NULL, 1) == -1 && errno == EINVAL); CHECK(!close(sink));
    errno = 0; CHECK(read(fd, buffer.bytes, 1) == -1 && errno == EINVAL);
    struct iovec vectors[] = {{buffer.bytes, 8}, {buffer.bytes + 8, 64}};
    errno = 0; CHECK(readv(fd, vectors, 2) == -1 && errno == EINVAL);
    errno = 0; CHECK(read(fd, (void *)1, sizeof(buffer.bytes)) == -1 && errno == EFAULT);
    CHECK(one(fd, IN_CREATE, "one")->wd == wd);
    CHECK(!ioctl(fd, FIONREAD, &available) && !available);
    CHECK(!link("one", "two")); one(fd, IN_CREATE, "two");
    CHECK(!rename("two", "three"));
    ssize_t n = events(fd);
    struct inotify_event *a = &buffer.align, *b = (void *)(buffer.bytes + sizeof(*a) + a->len);
    CHECK(n == (ssize_t)(2 * sizeof(*a) + a->len + b->len));
    CHECK(a->mask == IN_MOVED_FROM && b->mask == IN_MOVED_TO && a->cookie && a->cookie == b->cookie);
    CHECK(!strcmp(a->name, "two") && !strcmp(b->name, "three"));
    CHECK(!unlink("three")); one(fd, IN_DELETE, "three");
    CHECK(!mkdir("dir", 0700)); one(fd, IN_CREATE | IN_ISDIR, "dir");
    CHECK(!rmdir("dir")); one(fd, IN_DELETE | IN_ISDIR, "dir");
    puts("PASS logical directory create/link/rename/delete and exact read boundaries"); fflush(stdout);
    int copy = dup(fd); CHECK(copy >= 0); CHECK(!close(fd)); fd = copy;
    create("dup"); one(fd, IN_CREATE, "dup");
    copy = fcntl(fd, F_DUPFD_CLOEXEC, 100); CHECK(copy >= 100);
    CHECK(!close(fd)); fd = copy;
    create("fcntl"); one(fd, IN_CREATE, "fcntl");
    CHECK(dup3(fd, 120, O_CLOEXEC) == 120); CHECK(!close(fd)); fd = 120;
    create("dup3"); one(fd, IN_CREATE, "dup3");
    CHECK(!inotify_rm_watch(fd, wd)); one(fd, IN_IGNORED, NULL);
    wd = inotify_add_watch(fd, ".", IN_CREATE | IN_ONESHOT); CHECK(wd > 0);
    create("oneshot"); n = events(fd);
    a = &buffer.align; b = (void *)(buffer.bytes + sizeof(*a) + a->len);
    CHECK(n == (ssize_t)(2 * sizeof(*a) + a->len) && a->mask == IN_CREATE && b->mask == IN_IGNORED && b->wd == wd);
    CHECK(inotify_rm_watch(fd, wd) == -1 && errno == EINVAL);
    CHECK(!close(fd));
    puts("PASS dup/fcntl lifetime, explicit removal and one-shot"); fflush(stdout);
    fd = inotify_init1(IN_NONBLOCK); CHECK(fd >= 0);
    CHECK(!(fcntl(fd, F_GETFD) & FD_CLOEXEC));
    wd = inotify_add_watch(fd, "one", IN_MODIFY | IN_CLOSE_WRITE); CHECK(wd > 0);
    copy = open("one", O_WRONLY); CHECK(copy >= 0); CHECK(write(copy, "x", 1) == 1);
    one(fd, IN_MODIFY, NULL); CHECK(!close(copy)); one(fd, IN_CLOSE_WRITE, NULL);
    CHECK(!close(fd));
    puts("PASS native file IO events"); fflush(stdout);
    fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); CHECK(fd >= 0);
    wd = inotify_add_watch(fd, "/dev/null", IN_ATTRIB); CHECK(wd > 0);
    CHECK(!inotify_rm_watch(fd, wd)); one(fd, IN_IGNORED, NULL); CHECK(!close(fd));
    puts("PASS explicitly mapped host object registration"); fflush(stdout);
    fd = inotify_init1(0); CHECK(fd >= 0);
    wd = inotify_add_watch(fd, ".", IN_CREATE); CHECK(wd > 0);
    int barrier[2]; CHECK(!pipe(barrier));
    pid_t child = fork(); CHECK(child >= 0);
    if (!child) {
        close(barrier[1]); char byte; CHECK(read(barrier[0], &byte, 1) == 1);
        create("fork"); _exit(0);
    }
    close(barrier[0]); CHECK(write(barrier[1], "x", 1) == 1); close(barrier[1]);
    CHECK(read(fd, buffer.bytes, sizeof(buffer.bytes)) >= 32);
    CHECK(buffer.align.mask == IN_CREATE && !strcmp(buffer.align.name, "fork"));
    int status; CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    struct sigaction action = {.sa_handler = signal_handler}; sigemptyset(&action.sa_mask);
    CHECK(!sigaction(SIGALRM, &action, NULL));
    /* Failure/cancellation bound for an intentionally empty blocking read. */
    alarm(1); CHECK(read(fd, buffer.bytes, sizeof(buffer.bytes)) == -1 && errno == EINTR);
    CHECK(interrupted == 1); alarm(0);
    action.sa_flags = SA_RESTART; CHECK(!sigaction(SIGALRM, &action, NULL));
    CHECK(!pipe(barrier)); child = fork(); CHECK(child >= 0);
    if (!child) {
        close(barrier[1]); char byte; CHECK(read(barrier[0], &byte, 1) == 1);
        create("restarted"); _exit(0);
    }
    close(barrier[0]); signal_writer = barrier[1];
    /* EVENT_WAIT: the delivered signal releases the writer, then SA_RESTART
     * must complete this same blocking read. Runner deadline bounds failure. */
    alarm(1); CHECK(read(fd, buffer.bytes, sizeof(buffer.bytes)) == 32);
    CHECK(interrupted == 2 && buffer.align.mask == IN_CREATE && !strcmp(buffer.align.name, "restarted"));
    signal_writer = -1; close(barrier[1]); alarm(0);
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    create("vector-a"); create("vector-b");
    vectors[0] = (struct iovec){buffer.bytes, 32}; vectors[1] = (struct iovec){buffer.bytes + 32, 32};
    CHECK(readv(fd, vectors, 2) == 64);
    CHECK(!strcmp(buffer.align.name, "vector-a") && !strcmp(((struct inotify_event *)(buffer.bytes+32))->name, "vector-b"));
    create("partial-vector"); CHECK(readv(fd, vectors, 2) == 32);
    CHECK(!strcmp(buffer.align.name, "partial-vector"));
    CHECK(!close(fd));
    puts("PASS blocking fork writer, EINTR/SA_RESTART and partial readv"); fflush(stdout);
    fd = inotify_init1(0); CHECK(fd >= 0);
    CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
    struct reader readers[2] = {{.fd = fd}, {.fd = fd}};
    pthread_t threads[2];
    CHECK(!pthread_create(&threads[0], NULL, read_thread, &readers[0]));
    CHECK(!pthread_create(&threads[1], NULL, read_thread, &readers[1]));
    create("thread-a"); create("thread-b");
    CHECK(!pthread_join(threads[0], NULL) && !pthread_join(threads[1], NULL));
    CHECK(strcmp(readers[0].name, readers[1].name));
    CHECK(!close(fd));
    puts("PASS concurrent readers consume each event once"); fflush(stdout);
    fd = inotify_init1(IN_NONBLOCK); CHECK(fd >= 0);
    CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
    int sockets[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
    transfer(sockets[0], fd); CHECK(!close(fd));
    create("queued-rights");
    copy = open("/dev/zero", O_RDONLY); CHECK(copy == fd);
    char zero = 1; CHECK(read(copy, &zero, 1) == 1 && !zero);
    fd = receive_fd(sockets[1], 0); CHECK(fd >= 0 && fd != copy);
    one(fd, IN_CREATE, "queued-rights");
    transfer(sockets[0], fd); CHECK(!close(fd));
    create("batch-rights");
    fd = receive_fd(sockets[1], 1); CHECK(fd >= 0);
    one(fd, IN_CREATE, "batch-rights");
    CHECK(!close(sockets[0]) && !close(sockets[1]));
    int ordinary = fcntl(copy, F_DUPFD_CLOEXEC, 200); CHECK(ordinary >= 200);
    for (int i = 0; i < 10000; ++i) CHECK(read(ordinary, &zero, 1) == 1 && !zero);
    CHECK(!close(ordinary) && !close(copy) && !close(fd));
    puts("PASS queued SCM_RIGHTS lifetime, descriptor reuse and unrelated native reads"); fflush(stdout);
    child = fork(); CHECK(child >= 0);
    if (!child) {
        fd = inotify_init1(IN_NONBLOCK); CHECK(fd >= 0);
        CHECK(inotify_add_watch(fd, ".", IN_CREATE) > 0);
        create("exec-event");
        return exec_watch(fd, 64, 0);
    }
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    return 0;
}
