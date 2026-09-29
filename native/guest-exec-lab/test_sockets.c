#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <netinet/in.h>
#include <unistd.h>
#ifdef MD_SOCKET_DRIVER
#include "socket_calls.h"
#include "raw.h"
static int use_adapter;
static struct md_fs test_fs;
static long socket_input(long nr, long a0, long a1, long a2, long a3, long a4, long a5) {
    if (!use_adapter) return syscall(nr, a0, a1, a2, a3, a4, a5);
    unsigned long args[] = {a0, a1, a2, a3, a4, a5};
    long r = md_socket_call(&test_fs, "/test", nr, args);
    if (r < 0) { errno = (int)-r; return -1; }
    return r;
}
#define connect(f,a,n) socket_input(SYS_connect,f,(long)(a),n,0,0,0)
#define bind(f,a,n) socket_input(SYS_bind,f,(long)(a),n,0,0,0)
#define sendto(f,b,n,g,a,s) socket_input(SYS_sendto,f,(long)(b),n,g,(long)(a),s)
#define sendmsg(f,m,g) socket_input(SYS_sendmsg,f,(long)(m),g,0,0,0)
#define sendmmsg(f,m,n,g) socket_input(SYS_sendmmsg,f,(long)(m),n,g,0,0)
#endif

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s errno=%d\n", __FILE__, __LINE__, #x, errno); exit(1); } } while (0)
static unsigned descriptors(void) {
    DIR *d = opendir("/proc/self/fd"); CHECK(d);
    unsigned n = 0; while (readdir(d)) ++n;
    closedir(d); return n;
}
static void readable(int fd) {
    struct pollfd p = {fd, POLLIN, 0};
    /* EVENT_WAIT: socket data or connection; ten seconds is a fixture failure bound. */
    int r; do { r = poll(&p, 1, 10000); } while (r < 0 && errno == EINTR);
    CHECK(r == 1 && (p.revents & POLLIN));
}
static socklen_t address(struct sockaddr_un *out, const char *base, const char *name) {
    memset(out, 0, sizeof(*out)); out->sun_family = AF_UNIX;
    int abstract = base[0] == '@';
    int n = snprintf(out->sun_path + abstract, sizeof(out->sun_path) - abstract, "%s/%s", base + abstract, name);
    CHECK(n > 0 && n < (int)sizeof(out->sun_path) - abstract);
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + n + 1);
}
static long svc_connect(int fd, struct sockaddr_un *addr, socklen_t length) {
#ifdef MD_SOCKET_DRIVER
    if (use_adapter) {
        unsigned long args[6] = {fd, (unsigned long)addr, length, 0, 0, 0};
        return md_socket_call(&test_fs, "/test", SYS_connect, args);
    }
#endif
    register long x0 __asm__("x0") = fd;
    register long x1 __asm__("x1") = (long)addr;
    register long x2 __asm__("x2") = length;
    register long x8 __asm__("x8") = SYS_connect;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory", "cc");
    return x0;
}
static void transfer(int from, int to) {
    int fd = (int)syscall(SYS_memfd_create, "md-socket-fixture", MFD_CLOEXEC); CHECK(fd >= 0);
    CHECK(write(fd, "shared", 6) == 6 && lseek(fd, 0, SEEK_SET) == 0);
    union { struct cmsghdr align; char data[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'f'; struct iovec vector = {&byte, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.data, .msg_controllen = sizeof(control.data)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&message);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    CHECK(sendmsg(from, &message, MSG_NOSIGNAL) == 1);
    memset(&control, 0, sizeof(control)); byte = 0;
    readable(to);
    CHECK(recvmsg(to, &message, MSG_CMSG_CLOEXEC) == 1 && byte == 'f');
    CHECK(!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    c = CMSG_FIRSTHDR(&message);
    CHECK(c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len == CMSG_LEN(sizeof(int)));
    int received; memcpy(&received, CMSG_DATA(c), sizeof(received));
    CHECK(fcntl(received, F_GETFD) == FD_CLOEXEC);
    struct stat a, b; CHECK(fstat(fd, &a) == 0 && fstat(received, &b) == 0 && a.st_ino == b.st_ino && a.st_dev == b.st_dev);
    char data[6]; CHECK(read(received, data, 6) == 6 && !memcmp(data, "shared", 6));
    CHECK(lseek(fd, 0, SEEK_CUR) == 6);
    void *mapped = mmap(NULL, 6, PROT_READ | PROT_WRITE, MAP_SHARED, received, 0);
    CHECK(mapped != MAP_FAILED); memcpy(mapped, "mapped", 6);
    CHECK(pread(fd, data, 6, 0) == 6 && !memcmp(data, "mapped", 6));
    munmap(mapped, 6); close(received); close(fd);
}
static void connection(const char *base, const char *name, int listener, int type, pid_t creator) {
    struct sockaddr_un addr; socklen_t length = address(&addr, base, name);
    int client = socket(AF_UNIX, type | SOCK_CLOEXEC | SOCK_NONBLOCK, 0); CHECK(client >= 0);
    CHECK(svc_connect(client, &addr, length) == 0);
    readable(listener);
    int peer = accept4(listener, NULL, NULL, SOCK_CLOEXEC); CHECK(peer >= 0);
    struct ucred credentials; socklen_t size = sizeof(credentials);
    CHECK(getsockopt(client, SOL_SOCKET, SO_PEERCRED, &credentials, &size) == 0);
    CHECK(credentials.pid == creator && credentials.uid == getuid());
    size = sizeof(credentials);
    CHECK(getsockopt(peer, SOL_SOCKET, SO_PEERCRED, &credentials, &size) == 0 && credentials.pid == getpid());
    transfer(client, peer); transfer(peer, client);
    CHECK(shutdown(client, SHUT_WR) == 0);
    char byte; readable(peer); CHECK(read(peer, &byte, 1) == 0);
    close(peer); close(client);
}
static void datagrams(const char *base, int server, int guest) {
    struct sockaddr_un addr; socklen_t length = address(&addr, base, "d");
    int client = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0); CHECK(client >= 0);
    CHECK(sendto(client, "to", 2, 0, (struct sockaddr *)&addr, length) == 2);
    char data[8]; readable(server); CHECK(recv(server, data, sizeof(data), 0) == 2 && !memcmp(data, "to", 2));
    struct iovec vectors[] = {{"send", 4}, {"msg", 3}};
    struct msghdr message = {.msg_name = &addr, .msg_namelen = length, .msg_iov = vectors, .msg_iovlen = 2};
    CHECK(sendmsg(client, &message, 0) == 7);
    CHECK(message.msg_name == &addr && message.msg_namelen == length);
    readable(server); CHECK(recv(server, data, sizeof(data), 0) == 7 && !memcmp(data, "sendmsg", 7));
    CHECK(connect(client, (struct sockaddr *)&addr, length) == 0);
    transfer(client, server);
    struct sockaddr disconnect = {.sa_family = AF_UNSPEC};
    CHECK(connect(client, &disconnect, sizeof(disconnect)) == 0);
    CHECK(send(client, "x", 1, 0) == -1 && errno == ENOTCONN);
    struct mmsghdr batch = {.msg_hdr = message};
    if (guest) CHECK(sendmmsg(client, &batch, 1, 0) == -1 && errno == ENOTSUP);
    else {
        CHECK(sendmmsg(client, &batch, 1, 0) == 1 && batch.msg_len == 7);
        readable(server); CHECK(recv(server, data, sizeof(data), 0) == 7);
    }
    CHECK(recv(server, data, sizeof(data), MSG_DONTWAIT) == -1 && errno == EAGAIN);
    close(client);
}
static void native_addresses(void) {
    struct sockaddr_un addr = {.sun_family = AF_UNIX};
    int n = snprintf(addr.sun_path + 1, sizeof(addr.sun_path) - 1, "md-socket-%d", getpid());
    memcpy(addr.sun_path + n + 2, "tail", 4);
    socklen_t length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + n + 6);
    int server = socket(AF_UNIX, SOCK_DGRAM, 0), client = socket(AF_UNIX, SOCK_DGRAM, 0);
    CHECK(server >= 0 && client >= 0 && bind(server, (struct sockaddr *)&addr, length) == 0);
    CHECK(sendto(client, "a", 1, 0, (struct sockaddr *)&addr, length) == 1);
    char byte; readable(server); CHECK(read(server, &byte, 1) == 1 && byte == 'a');
    struct sockaddr_un seen; socklen_t size = sizeof(seen);
    CHECK(getsockname(server, (struct sockaddr *)&seen, &size) == 0 && size == length && !memcmp(&addr, &seen, length));
    CHECK(bind(client, (struct sockaddr *)&addr, sizeof(sa_family_t)) == 0);
    size = sizeof(seen);
    CHECK(getsockname(client, (struct sockaddr *)&seen, &size) == 0 && size > 2 && !seen.sun_path[0]);
    close(server); close(client);
    int ip = socket(AF_INET, SOCK_DGRAM, 0); CHECK(ip >= 0);
    struct sockaddr_in loopback = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    CHECK(bind(ip, (struct sockaddr *)&loopback, sizeof(loopback)) == 0);
    size = sizeof(loopback); CHECK(getsockname(ip, (struct sockaddr *)&loopback, &size) == 0);
    CHECK(sendto(ip, "i", 1, 0, (struct sockaddr *)&loopback, size) == 1);
    readable(ip); CHECK(read(ip, &byte, 1) == 1 && byte == 'i'); close(ip);
    puts("PASS sockets: binary abstract names, autobind, AF_UNSPEC and IP remain native");
}
static void errors(const char *base, int guest) {
    struct sockaddr_un addr; socklen_t length = address(&addr, base, "absent");
    int fd = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(fd >= 0);
    CHECK(connect(fd, (struct sockaddr *)&addr, length) == -1 && errno == ENOENT);
    CHECK(syscall(SYS_connect, fd, (void *)1, sizeof(addr)) == -1 && errno == EFAULT);
    CHECK(syscall(SYS_sendmsg, fd, (void *)1, 0) == -1 && errno == EFAULT);
    CHECK(connect(fd, (struct sockaddr *)&addr, sizeof(addr) + 1) == -1 && errno == EINVAL);
    CHECK(svc_connect(-1, &addr, length) == -EBADF);
    length = address(&addr, base, "new");
    if (guest) {
        CHECK(bind(fd, (struct sockaddr *)&addr, length) == -1 && errno == ENOTSUP);
        struct stat st; CHECK(lstat(addr.sun_path, &st) == -1 && errno == ENOENT);
    } else { CHECK(bind(fd, (struct sockaddr *)&addr, length) == 0); CHECK(unlink(addr.sun_path) == 0); }
    length = address(&addr, base, "regular");
    int file = open(addr.sun_path, O_CREAT | O_EXCL | O_RDWR, 0600); CHECK(file >= 0); close(file);
    CHECK(connect(fd, (struct sockaddr *)&addr, length) == -1 && errno == ECONNREFUSED);
    CHECK(unlink(addr.sun_path) == 0);
    length = address(&addr, base, "denied/socket");
    CHECK(connect(fd, (struct sockaddr *)&addr, length) == -1 && errno == EACCES);
    address(&addr, base, "denied"); CHECK(chmod(addr.sun_path, 0700) == 0); close(fd);
    puts("PASS sockets: native permission/type failures, invalid pointers and explicit creation/batch limits");
}
static int client(const char *base, const char *files, int stream, int packet, int datagram, pid_t creator, int guest) {
    unsigned before = descriptors();
    connection(base, "s", stream, SOCK_STREAM, creator);
    connection(base, "p", packet, SOCK_SEQPACKET, creator);
    datagrams(base, datagram, guest);
    puts("PASS sockets: stream/seqpacket/datagram, SCM_RIGHTS, credentials and shared mmap");
    native_addresses(); errors(files, guest);
    CHECK(descriptors() == before);
    puts("PASS sockets: descriptor counts unchanged after success and failure");
    return 0;
}
#ifdef MD_SOCKET_DRIVER
static int driver(int argc, char **argv) {
    CHECK(argc == 3 || argc == 4);
    const char *mode = argv[1], *root = argv[2];
    int native = !strcmp(mode, "native") || !strcmp(mode, "adapter");
    int probe = !strcmp(mode, "probe"), abstract = argc == 4 && !strcmp(argv[3], "abstract");
    CHECK(native || probe || !strcmp(mode, "direct") || !strcmp(mode, "namespace"));
    char directory[4096];
    CHECK(snprintf(directory, sizeof(directory), "%s/%smd-sockets-%s", root, native ? "" : "tmp/", mode) < (int)sizeof(directory));
    CHECK(mkdir(directory, 0700) == 0);
    int dir = open(directory, O_PATH | O_DIRECTORY); CHECK(dir >= 0);
    char host[64]; snprintf(host, sizeof(host), "/proc/self/fd/%d", dir);
    if (probe) {
        struct stat st; CHECK(fstat(dir, &st) == 0 && st.st_uid == getuid() && (st.st_mode & 0777) == 0700);
        int file = openat(dir, "file", O_CREAT | O_EXCL | O_WRONLY, 0600); CHECK(file >= 0); close(file);
        for (unsigned i = 0; i < 2; ++i) {
            struct sockaddr_un addr; socklen_t size = address(&addr, i ? host : directory, i ? "alias" : "plain");
            int fd = socket(AF_UNIX, SOCK_STREAM, 0); CHECK(fd >= 0);
            CHECK(bind(fd, (struct sockaddr *)&addr, size) == -1 && errno == EACCES); close(fd);
        }
        close(dir); puts("LIMIT native shell denies pathname bind on both ordinary and proc paths; regular-file creation succeeds");
        return 77;
    }
    CHECK(mkdirat(dir, "denied", 0000) == 0);
    char endpoints[96];
    if (abstract) snprintf(endpoints, sizeof(endpoints), "@md-sockets-%d", getpid());
    else snprintf(endpoints, sizeof(endpoints), "%s", host);
    int fds[3], types[] = {SOCK_STREAM, SOCK_SEQPACKET, SOCK_DGRAM};
    const char *names[] = {"s", "p", "d"};
    for (unsigned i = 0; i < 3; ++i) {
        struct sockaddr_un addr; socklen_t length = address(&addr, endpoints, names[i]);
        fds[i] = socket(AF_UNIX, types[i], 0); CHECK(fds[i] >= 0);
        CHECK(bind(fds[i], (struct sockaddr *)&addr, length) == 0);
        if (i < 2) CHECK(listen(fds[i], 8) == 0);
    }
    pid_t creator = getpid();
    if (native) {
        md_page_size = (size_t)sysconf(_SC_PAGESIZE);
        use_adapter = !strcmp(mode, "adapter");
        CHECK(md_copy(test_fs.root, sizeof(test_fs.root), root) == 0);
        if (use_adapter) {
            connection("/md-sockets-adapter", "s", fds[0], SOCK_STREAM, creator);
            puts("PASS sockets: explicit adapter resolves guest-root path to pinned native socket");
        }
        int r = client(endpoints, host, fds[0], fds[1], fds[2], creator, use_adapter);
        for (unsigned i = 0; i < 3; ++i) close(fds[i]);
        close(dir); return r;
    }
    char launcher[4096], store[4096], base[96], numbers[4][32];
    CHECK(snprintf(launcher, sizeof(launcher), "%s/../%s", root,
        !strcmp(mode, "direct") ? "libmagicdesk_guest_bootstrap.so" : "libmagicdesk_guest_run.so") < (int)sizeof(launcher));
    if (abstract) snprintf(base, sizeof(base), "%s", endpoints);
    else if (!strcmp(mode, "direct")) snprintf(base, sizeof(base), "/tmp/md-sockets-direct");
    else snprintf(base, sizeof(base), "%s", host);
    for (unsigned i = 0; i < 3; ++i) snprintf(numbers[i], sizeof(numbers[i]), "%d", fds[i]);
    snprintf(numbers[3], sizeof(numbers[3]), "%d", creator);
    char *args[16] = {launcher}; unsigned n = 1;
    if (!strcmp(mode, "namespace")) {
        CHECK(snprintf(store, sizeof(store), "%s/tmp/imported-rootfs", root) < (int)sizeof(store));
        args[n++] = "--store"; args[n++] = store; args[n++] = "--";
    } else args[n++] = (char *)root;
    args[n++] = "/usr/bin/md-socket-fixture"; args[n++] = "client"; args[n++] = base; args[n++] = host;
    for (unsigned i = 0; i < 4; ++i) args[n++] = numbers[i];
    args[n] = NULL; execv(launcher, args); CHECK(0); return 1;
}
#endif
int main(int argc, char **argv) {
    if (argc == 8 && !strcmp(argv[1], "client"))
        return client(argv[2], argv[3], atoi(argv[4]), atoi(argv[5]), atoi(argv[6]), (pid_t)atoi(argv[7]), 1);
#ifdef MD_SOCKET_DRIVER
    return driver(argc, argv);
#else
    CHECK(0); return 1;
#endif
}
