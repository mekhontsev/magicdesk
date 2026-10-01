#define _GNU_SOURCE
#include "../guest-runtime/src/event_wait.h"
#include "../guest-runtime/src/watch_queue.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

/* Transport experiment, not an installed guest adapter. Shared test output
 * deliberately isolates blocking-read semantics from protected memory copy. */
extern long md_watch_read_call(int, void *, size_t, long);
extern char md_watch_read_return[], md_watch_read_gate[], md_watch_read_gate_return[];
static pid_t child;
static const char *stage;
static sigset_t original_mask;
static int signals, reader, restart_mode;
static int scoped;
static volatile sig_atomic_t caught;
static unsigned char *output;
static struct md_watch_queue *queue;
enum { ROUNDS = 10000 };
enum mode { RECORDS, EINTR_TEST, RESTART_TEST, IP_FILTER, REUSED, VECTOR,
    NATIVE_RECORDS, NATIVE_EINTR, NATIVE_RESTART, NATIVE_IP_FILTER };
enum phase { IDLE, CANCEL, ENTER, LEAVE, NATIVE_WAIT };
static int native_mode(enum mode mode) { return mode >= NATIVE_RECORDS; }
static int signal_mode(enum mode mode) {
    return mode == EINTR_TEST || mode == RESTART_TEST || mode == NATIVE_EINTR || mode == NATIVE_RESTART;
}
static void cleanup(void) {
    if (!child) return;
    kill(child, SIGKILL);
    /* EVENT_WAIT: reap this fixture's child after cancellation; the outer
     * fixture runner bounds unexpected kernel teardown hangs. */
    while (waitpid(child, NULL, __WALL) < 0 && errno == EINTR) { }
    child = 0;
}
static void failure(const char *check, int line) {
    fprintf(stderr, "FAIL %s:%d %s errno=%d\n", stage, line, check, errno);
    cleanup(); exit(1);
}
#define CHECK(x) do { if (!(x)) failure(#x, __LINE__); } while (0)
static void ready(int fd) {
    /* EVENT_WAIT: fixture handshake, not a settling delay. */
    CHECK(md_event_wait_fd(fd, POLLIN, md_event_now() + 5000000000LL) >= 0);
}
static void send_fd(int socket, int fd) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'n'; struct iovec iov = {&byte, 1};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(c), &fd, sizeof(fd));
    CHECK(sendmsg(socket, &msg, MSG_NOSIGNAL) == 1);
}
static int receive_fd(int socket) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte; struct iovec iov = {&byte, 1};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ready(socket);
    CHECK(recvmsg(socket, &msg, MSG_CMSG_CLOEXEC) == 1 && byte == 'n');
    CHECK(!(msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
    CHECK(c && c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS
        && c->cmsg_len == CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&msg, c));
    int fd; memcpy(&fd, CMSG_DATA(c), sizeof(fd)); return fd;
}
static int filter(int lazy) {
    if (lazy && scoped) {
        /* Descriptor numbers are conservative interception hints, not retained
         * identities. Production must revalidate the open-file description and
         * cover descriptor publication/duplication before using this selector. */
        struct sock_filter rules[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_read, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_readv, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, reader, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 71),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog p = {sizeof(rules) / sizeof(*rules), rules};
        CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &p));
        return 0;
    }
    uintptr_t gate = (uintptr_t)md_watch_read_gate_return;
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, lazy ? SECCOMP_RET_ALLOW : SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_read, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_readv, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(gate >> 32), 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)gate, 0, 2),
        BPF_STMT(BPF_RET | BPF_K, lazy ? SECCOMP_RET_ALLOW : SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, lazy ? SECCOMP_RET_TRACE | 71 : SECCOMP_RET_ALLOW),
    };
    struct sock_fprog p = {sizeof(rules) / sizeof(*rules), rules};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    int result = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER,
        lazy ? SECCOMP_FILTER_FLAG_TSYNC : SECCOMP_FILTER_FLAG_NEW_LISTENER, &p);
    CHECK(lazy ? result == 0 : result >= 0); return result;
}
static void site_filter(void) {
    uintptr_t site = (uintptr_t)md_watch_read_return;
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_read, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(site >> 32), 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)site, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
    };
    struct sock_fprog p = {sizeof(rules) / sizeof(*rules), rules};
    CHECK(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
}
static void interrupted(int sig, siginfo_t *info, void *context) {
    ucontext_t *u = context;
    if (sig != SIGUSR1 || info->si_code != SI_QUEUE || info->si_value.sival_int != 31
            || u->uc_mcontext.pc != (uintptr_t)md_watch_read_return - (restart_mode ? 4 : 0)
            || (long)u->uc_mcontext.regs[0] != (restart_mode ? reader : -EINTR)
            || u->uc_mcontext.regs[1] != (uintptr_t)output
            || u->uc_mcontext.regs[2] != 128) _exit(91);
    if (syscall(SYS_getuid) != 2000) _exit(92);
    caught++;
}
static void read_records(enum mode mode) {
    if (mode == IP_FILTER || mode == NATIVE_IP_FILTER) {
        site_filter();
        CHECK(md_watch_read_call(reader, output, 128, SYS_read) == (mode == IP_FILTER ? -EACCES : 32));
        return;
    }
    if (mode == VECTOR) {
        struct iovec *vectors = (void *)(output + 1024);
        memset(output + 256, 0xaa, 512);
        vectors[0] = (struct iovec){output + 256, 31};
        vectors[1] = (struct iovec){output + 512, 128};
        CHECK(md_watch_read_call(reader, vectors, 2, SYS_readv) == -EINVAL);
        vectors[0].iov_len = 64;
        CHECK(md_watch_read_call(reader, vectors, 2, SYS_readv) == 32);
        for (unsigned i = 512; i < 640; i++) CHECK(output[i] == 0xaa);
        memcpy(output, output + 256, 32);
    } else if (signal_mode(mode)) {
        restart_mode = mode == RESTART_TEST || mode == NATIVE_RESTART;
        struct sigaction action = {.sa_sigaction = interrupted,
            .sa_flags = SA_SIGINFO | (restart_mode ? SA_RESTART : 0)};
        sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGUSR1, &action, NULL));
        long r = md_watch_read_call(reader, output, 128, SYS_read);
        if (!restart_mode) { CHECK(r == -EINTR); r = md_watch_read_call(reader, output, 128, SYS_read); }
        CHECK(r == 32 && caught == 1);
    } else {
        CHECK(md_watch_read_call(reader, output, 31, SYS_read) == -EINVAL);
        CHECK(md_watch_read_call(reader, output, 128, SYS_read) == 32);
    }
    struct inotify_event event; memcpy(&event, output, sizeof(event));
    CHECK(event.wd == 9 && event.mask == IN_CREATE && event.len == 16);
    CHECK(!strcmp((char *)output + sizeof(event), "created"));
}
static struct user_pt_regs registers(void) {
    struct user_pt_regs r; struct iovec v = {&r, sizeof(r)};
    CHECK(!ptrace(PTRACE_GETREGSET, child, (void *)NT_PRSTATUS, &v) && v.iov_len == sizeof(r));
    return r;
}
static void put_registers(struct user_pt_regs *r) {
    struct iovec v = {r, sizeof(*r)}; CHECK(!ptrace(PTRACE_SETREGSET, child, (void *)NT_PRSTATUS, &v));
}
static void skip(void) {
    int number = -1; struct iovec v = {&number, sizeof(number)};
    CHECK(!ptrace(PTRACE_SETREGSET, child, (void *)NT_ARM_SYSTEM_CALL, &v));
}
static void resume(int request, int signal) { CHECK(!ptrace(request, child, 0, signal)); }
static void drain(void) {
    struct signalfd_siginfo info;
    while (read(signals, &info, sizeof(info)) == sizeof(info)) { }
    CHECK(errno == EAGAIN);
}
static int watch_descriptor(int pidfd, int fd) {
    if (!scoped && fd != reader) return 0;
    int retained = syscall(SYS_pidfd_getfd, pidfd, fd, 0); CHECK(retained >= 0);
    struct stat expected, actual;
    CHECK(!fstat(reader, &expected) && !fstat(retained, &actual));
    close(retained);
    return actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino;
}
static void run(enum mode mode, const char *name) {
    stage = name;
    CHECK(!md_watch_queue_create(16, IN_CLOEXEC, &queue, &reader));
    if (mode == RECORDS || mode == REUSED || mode == VECTOR
            || mode == NATIVE_RECORDS || mode == NATIVE_IP_FILTER)
        CHECK(!md_watch_queue_emit(queue, 9, IN_CREATE, 0, "created"));
    int sockets[2]; CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets));
    pid_t parent = getpid(); child = fork(); CHECK(child >= 0);
    if (!child) {
        close(signals); close(sockets[0]); close(md_watch_queue_lifetime(queue));
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == parent);
        CHECK(!sigprocmask(SIG_SETMASK, &original_mask, NULL));
        char byte = 'r'; CHECK(write(sockets[1], &byte, 1) == 1);
        ready(sockets[1]); CHECK(read(sockets[1], &byte, 1) == 1 && byte == 'g');
        int listener = filter(0); send_fd(sockets[1], listener); close(listener); close(sockets[1]);
        CHECK(syscall(SYS_getuid) == 2000);
        int zero = open("/dev/zero", O_RDONLY | O_CLOEXEC); CHECK(zero >= 0);
        int64_t start = md_event_now();
        for (int i = 0; i < ROUNDS; i++) CHECK(md_watch_read_call(zero, output, 128, SYS_read) == 128);
        int64_t native = md_event_now() - start;
        filter(1);
        start = md_event_now();
        for (int i = 0; i < ROUNDS; i++) CHECK(md_watch_read_call(zero, output, 128, SYS_read) == 128);
        int64_t traced = md_event_now() - start;
        read_records(mode);
        if (mode == REUSED) {
            CHECK(dup3(zero, reader, O_CLOEXEC) == reader);
            CHECK(md_watch_read_call(reader, output, 128, SYS_read) == 128);
        }
        printf("MEASURE %s ordinary-read count=%d before_ns=%lld after_ns=%lld\n", name, ROUNDS,
            (long long)native, (long long)traced);
        fflush(stdout); _exit(0);
    }
    close(sockets[1]); ready(sockets[0]); char byte;
    CHECK(read(sockets[0], &byte, 1) == 1 && byte == 'r');
    CHECK(!ptrace(PTRACE_SEIZE, child, 0, PTRACE_O_TRACESECCOMP | PTRACE_O_TRACESYSGOOD | PTRACE_O_EXITKILL));
    int pidfd = syscall(SYS_pidfd_open, child, 0); CHECK(pidfd >= 0);
    byte = 'g'; CHECK(write(sockets[0], &byte, 1) == 1);
    int listener = receive_fd(sockets[0]); close(sockets[0]);
    enum phase phase = IDLE;
    struct user_pt_regs saved = {0};
    unsigned trace = 0, notifications = 0, forwarded = 0, interrupted_count = 0;
    unsigned read_notifications = 0;
    uint64_t pending = 0;
    int64_t deadline = md_event_now() + 10000000000LL;
    int exited = 0;
    while (!exited) {
        int status; pid_t pid = waitpid(child, &status, __WALL | WNOHANG);
        if (pid < 0 && errno == EINTR) continue;
        CHECK(pid >= 0);
        if (pid) {
            if (WIFEXITED(status) || WIFSIGNALED(status)) {
                if (!(WIFEXITED(status) && !WEXITSTATUS(status))) fprintf(stderr, "child status=%x\n", status);
                CHECK(WIFEXITED(status) && !WEXITSTATUS(status)); child = 0; exited = 1; continue;
            }
            CHECK(WIFSTOPPED(status));
            unsigned event = (unsigned)status >> 16; int signal = WSTOPSIG(status);
            if (event == PTRACE_EVENT_SECCOMP) {
                struct user_pt_regs r = registers(); trace++;
                CHECK(phase == IDLE && (r.regs[8] == SYS_read || r.regs[8] == SYS_readv));
                if (!watch_descriptor(pidfd, (int)r.regs[0])) { resume(PTRACE_CONT, 0); continue; }
                saved = r; forwarded++;
                if (native_mode(mode)) {
                    if (signal_mode(mode) && interrupted_count)
                        CHECK(!md_watch_queue_emit(queue, 9, IN_CREATE, 0, "created"));
                    phase = NATIVE_WAIT; resume(PTRACE_SYSCALL, 0);
                    if (signal_mode(mode) && !interrupted_count)
                        CHECK(!sigqueue(child, SIGUSR1, (union sigval){.sival_int = 31}));
                } else { skip(); phase = CANCEL; resume(PTRACE_SYSCALL, 0); }
            } else if (!event && signal == (SIGTRAP | 0x80)) {
                struct user_pt_regs r = registers();
                if (phase == CANCEL) {
                    r = saved; r.pc = (uintptr_t)md_watch_read_gate;
                    put_registers(&r); phase = ENTER; resume(PTRACE_SYSCALL, 0);
                } else if (phase == ENTER) {
                    CHECK(r.pc == (uintptr_t)md_watch_read_gate_return);
                    phase = LEAVE; resume(PTRACE_SYSCALL, 0);
                } else if (phase == NATIVE_WAIT) {
                    long result = r.regs[0];
                    if (result >= 0) {
                        CHECK(result == 1);
                        /* Single-reader probe only: put back the consumed marker
                         * before exercising the queue API. Not a concurrent
                         * reader ownership design for the production runtime. */
                        CHECK(write(md_watch_queue_lifetime(queue), "\1", 1) == 1);
                        result = md_watch_queue_read(queue, reader, output, saved.regs[2], NULL, NULL);
                    } else CHECK(result == -512);
                    r = saved; r.regs[0] = result; put_registers(&r);
                    phase = IDLE; resume(PTRACE_CONT, 0);
                } else {
                    CHECK(phase == LEAVE);
                    long result = r.regs[0]; r = saved; r.regs[0] = result;
                    put_registers(&r); phase = IDLE; resume(PTRACE_CONT, 0);
                }
            } else if (!event && signal == SIGUSR1) {
                CHECK(phase == IDLE);
                if (!native_mode(mode)) {
                    CHECK(pending);
                    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &pending) < 0 && errno == ENOENT);
                }
                pending = 0; interrupted_count++; resume(PTRACE_CONT, signal);
            } else { fprintf(stderr, "unexpected phase=%d event=%u signal=%d\n", phase, event, signal); CHECK(0); }
            continue;
        }
        struct pollfd p[] = {{signals, POLLIN, 0}, {listener, POLLIN, 0}};
        /* EVENT_WAIT: ptrace progress or seccomp request; timeout fails and
         * cancels the owned child, never signifies successful completion. */
        CHECK(md_event_wait(p, 2, deadline) >= 0);
        if (p[0].revents) drain();
        if (!(p[1].revents & POLLIN)) continue;
        struct seccomp_notif request = {0};
        if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &request) && errno == EINTR) continue;
        CHECK(request.id && request.pid == (unsigned)child); notifications++;
        struct seccomp_notif_resp reply = {.id = request.id};
        if (request.data.nr == SYS_getuid) reply.val = 2000;
        else {
            CHECK((request.data.nr == SYS_read || request.data.nr == SYS_readv) && phase == LEAVE);
            CHECK(request.data.instruction_pointer == (uintptr_t)md_watch_read_gate_return);
            CHECK((int)request.data.args[0] == reader);
            void *destination = output; size_t capacity = request.data.args[2];
            if (request.data.nr == SYS_readv) {
                CHECK(mode == VECTOR && request.data.args[1] == (uintptr_t)(output + 1024)
                    && request.data.args[2] == 2);
                struct iovec *vectors = (void *)(output + 1024);
                CHECK(vectors[0].iov_base == output + 256);
                capacity = vectors[0].iov_len; destination = vectors[0].iov_base;
                CHECK(capacity == 31 || capacity == 64);
                /* These controls finish in the first vector (error or short
                 * read). Multi-vector pending delivery is not certified here. */
            } else CHECK(request.data.args[1] == (uintptr_t)output);
            read_notifications++;
            if ((mode == EINTR_TEST || mode == RESTART_TEST) && !interrupted_count) {
                CHECK(!pending && !md_watch_queue_bytes(queue)); pending = request.id;
                CHECK(!sigqueue(child, SIGUSR1, (union sigval){.sival_int = 31})); continue;
            }
            if (mode == EINTR_TEST || mode == RESTART_TEST)
                CHECK(!md_watch_queue_emit(queue, 9, IN_CREATE, 0, "created"));
            long result = md_watch_queue_read(queue, reader, destination, capacity, NULL, NULL);
            if (result < 0) reply.error = result; else reply.val = result;
        }
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply));
    }
    CHECK(trace == (scoped ? 0u : ROUNDS) + forwarded + (mode == REUSED ? 1u : 0u));
    CHECK(forwarded == (mode == IP_FILTER || mode == NATIVE_IP_FILTER ? 1u : 2u));
    CHECK(read_notifications == (mode == IP_FILTER || native_mode(mode) ? 0u : 2u));
    CHECK(interrupted_count == (signal_mode(mode) ? 1u : 0u));
    printf("PASS %s traces=%u forwarded=%u notifications=%u signals=%u\n",
        stage, trace, forwarded, notifications, interrupted_count); fflush(stdout);
    close(pidfd); close(listener); close(reader); md_watch_queue_destroy(queue); drain();
}
int main(void) {
    stage = "setup"; CHECK(getuid() == 2000);
    output = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    CHECK(output != MAP_FAILED);
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGCHLD); sigaddset(&mask, SIGPIPE);
    CHECK(!sigprocmask(SIG_BLOCK, &mask, &original_mask));
    signals = signalfd(-1, &mask, SFD_CLOEXEC | SFD_NONBLOCK); CHECK(signals >= 0);
    run(RECORDS, "lazy-records"); run(EINTR_TEST, "lazy-eintr");
    run(RESTART_TEST, "lazy-restart"); run(IP_FILTER, "gate-IP-filter-limit");
    run(NATIVE_RECORDS, "same-site-records"); run(NATIVE_EINTR, "same-site-eintr");
    run(NATIVE_RESTART, "same-site-restart"); run(NATIVE_IP_FILTER, "same-site-IP-filter");
    scoped = 1;
    run(RECORDS, "scoped-records"); run(EINTR_TEST, "scoped-eintr");
    run(RESTART_TEST, "scoped-restart"); run(NATIVE_IP_FILTER, "scoped-same-site-IP-filter");
    run(REUSED, "scoped-fd-reuse");
    run(VECTOR, "scoped-readv-first-vector");
    close(signals); munmap(output, 4096); return 0;
}
