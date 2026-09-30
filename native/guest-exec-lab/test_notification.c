#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/sched.h>
#include <poll.h>
#include <setjmp.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

/* Capability experiment only. No filter or signal disposition touches the caller. */
static volatile sig_atomic_t delivered;
static char alternate[65536];
static sigjmp_buf jump;
static int notified_open(void) {
    int fd = syscall(SYS_openat, AT_FDCWD, "/guest-notification-probe", O_RDONLY | O_CLOEXEC, 0);
    char byte = 1;
    int result = fd >= 0 && read(fd, &byte, 1) == 1 && byte == 0;
    if (fd >= 0) close(fd);
    return result;
}
static int on_alternate(void *pointer) {
    return (uintptr_t)pointer >= (uintptr_t)alternate
        && (uintptr_t)pointer < (uintptr_t)alternate + sizeof(alternate);
}
static void guest_signal(int signal, siginfo_t *info, void *context) {
    char local;
    if (signal != SIGSYS || info->si_syscall != SYS_getppid
            || !on_alternate(&local) || !notified_open()) _exit(120);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = 42;
    delivered = 1;
}
static void guest_jump(int signal) {
    char local;
    if (signal != SIGUSR1 || !on_alternate(&local) || !notified_open()) _exit(120);
    siglongjmp(jump, 1);
}
static int wait_readable(int fd) {
    struct pollfd pfd = {fd, POLLIN, 0};
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) return 0;
    int64_t deadline = (int64_t)now.tv_sec * 1000000000 + now.tv_nsec + 5000000000LL;
    /* EVENT_WAIT: child notification or receipt; timeout aborts this isolated probe. */
    for (;;) {
        if (clock_gettime(CLOCK_MONOTONIC, &now)) return 0;
        int64_t left = deadline - (int64_t)now.tv_sec * 1000000000 - now.tv_nsec;
        if (left <= 0) return 0;
        struct timespec timeout = {left / 1000000000, left % 1000000000};
        int n = ppoll(&pfd, 1, &timeout, NULL);
        if (n < 0 && errno == EINTR) continue;
        return n == 1 && (pfd.revents & POLLIN);
    }
}
struct hello { int error, source; uintptr_t destination; };
static void child(int channel) {
    stack_t stack = {.ss_sp = alternate, .ss_size = sizeof(alternate)};
    struct sigaction action = {.sa_sigaction = guest_signal, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask);
    if (sigaltstack(&stack, NULL) || sigaction(SIGSYS, &action, NULL)) _exit(121);
    action.sa_handler = guest_jump; action.sa_flags = SA_ONSTACK;
    if (sigaction(SIGUSR1, &action, NULL)) _exit(121);
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) _exit(122);
    int listener = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
    char destination[32] = "unmodified";
    struct hello value = {listener < 0 ? errno : 0, channel, (uintptr_t)destination};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct iovec iov = {&value, sizeof(value)};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
    if (listener >= 0) {
        message.msg_control = control.bytes; message.msg_controllen = sizeof(control);
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
        cmsg->cmsg_level = SOL_SOCKET; cmsg->cmsg_type = SCM_RIGHTS; cmsg->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(cmsg), &listener, sizeof(listener));
    }
    if (sendmsg(channel, &message, 0) != sizeof(value)) _exit(123);
    if (listener < 0) _exit(124);
    close(listener);
    int native_io = notified_open();
    long answer = syscall(SYS_getppid);
    if (!sigsetjmp(jump, 1)) { raise(SIGUSR1); _exit(125); }
    stack_t current;
    int restored = !sigaltstack(NULL, &current) && current.ss_sp == alternate && current.ss_flags == 0;
    int result[] = {native_io, !strcmp(destination, "written-by-parent"), answer == 42 && delivered && restored};
    if (send(channel, result, sizeof(result), 0) != sizeof(result)) _exit(125);
    _exit(result[0] && result[1] && result[2] ? 0 : 1);
}
static int namespace_error(void) {
    pid_t pid = fork();
    if (pid < 0) return errno;
    if (!pid) _exit(syscall(SYS_unshare, CLONE_NEWUSER) < 0 ? errno : 0);
    int status;
    while (waitpid(pid, &status, 0) < 0) if (errno != EINTR) return errno;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 255;
}
static int nested_reply(int listener) {
    if (!wait_readable(listener)) return 0;
    struct seccomp_notif request = {0};
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &request) || request.data.nr != SYS_openat) return 0;
    int fd = open("/dev/zero", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    struct seccomp_notif_addfd add = {.id = request.id, .flags = SECCOMP_ADDFD_FLAG_SEND,
        .srcfd = (unsigned)fd, .newfd_flags = O_CLOEXEC};
    int result = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0;
    close(fd);
    return result;
}
int main(void) {
    int user_namespace = namespace_error();
    int channel[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, channel)) return 125;
    pid_t pid = fork();
    if (pid < 0) return 125;
    if (!pid) { close(channel[0]); child(channel[1]); }
    close(channel[1]);
    int result = 1, listener = -1, memory_read = 0, memory_write = 0, duplicate = 0, inject = 0;
    int read_error = 0, write_error = 0, duplicate_error = 0, inject_error = 0;
    if (!wait_readable(channel[0])) goto done;
    struct hello value;
    struct iovec iov = {&value, sizeof(value)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    if (recvmsg(channel[0], &message, MSG_CMSG_CLOEXEC) != sizeof(value)) goto done;
    printf("{\"uid\":%u,\"userNamespaceErrno\":%d,\"listenerErrno\":%d", getuid(), user_namespace, value.error);
    if (value.error) { puts("}"); goto done; }
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS
            || cmsg->cmsg_len != CMSG_LEN(sizeof(int))) { puts("}"); goto done; }
    memcpy(&listener, CMSG_DATA(cmsg), sizeof(listener));
    if (!wait_readable(listener)) { puts("}"); goto done; }
    struct seccomp_notif request = {0};
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &request)) { puts("}"); goto done; }
    char path[64] = {0};
    struct iovec local = {path, sizeof("/guest-notification-probe")};
    struct iovec remote = {(void *)request.data.args[1], local.iov_len};
    memory_read = syscall(SYS_process_vm_readv, pid, &local, 1, &remote, 1, 0) == (long)local.iov_len;
    if (!memory_read) read_error = errno;
    memory_read = memory_read && !strcmp(path, "/guest-notification-probe");
    local.iov_base = "written-by-parent"; local.iov_len = sizeof("written-by-parent");
    remote.iov_base = (void *)value.destination; remote.iov_len = local.iov_len;
    memory_write = syscall(SYS_process_vm_writev, pid, &local, 1, &remote, 1, 0) == (long)local.iov_len;
    if (!memory_write) write_error = errno;
    int pidfd = syscall(SYS_pidfd_open, pid, 0);
    int copied = pidfd < 0 ? -1 : syscall(SYS_pidfd_getfd, pidfd, value.source, 0);
    if (copied < 0) duplicate_error = errno; else { duplicate = 1; close(copied); }
    if (pidfd >= 0) close(pidfd);
    int zero = open("/dev/zero", O_RDONLY | O_CLOEXEC);
    struct seccomp_notif_addfd add = {.id = request.id, .flags = SECCOMP_ADDFD_FLAG_SEND,
        .srcfd = (unsigned)zero, .newfd_flags = O_CLOEXEC};
    inject = zero >= 0 && ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0;
    if (!inject) {
        inject_error = errno;
        struct seccomp_notif_resp reply = {.id = request.id, .error = -EPERM};
        ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply);
    }
    if (zero >= 0) close(zero);
    int nested = nested_reply(listener) && nested_reply(listener);
    int receipt[3] = {0};
    if (wait_readable(channel[0]) && recv(channel[0], receipt, sizeof(receipt), 0) == sizeof(receipt))
        result = !(memory_read && memory_write && duplicate && inject && nested && receipt[0] && receipt[1] && receipt[2]);
    printf(",\"memoryRead\":%d,\"memoryReadErrno\":%d,\"memoryWrite\":%d,\"memoryWriteErrno\":%d,"
        "\"pidfdGetfd\":%d,\"pidfdGetfdErrno\":%d,\"addfd\":%d,\"addfdErrno\":%d,"
        "\"nativeIo\":%d,\"observedWrite\":%d,\"guestSignalAndStack\":%d,\"nestedAndLongjmp\":%d}\n",
        memory_read, read_error, memory_write, write_error, duplicate, duplicate_error,
        inject, inject_error, receipt[0], receipt[1], receipt[2], nested);
done:
    if (listener >= 0) close(listener);
    close(channel[0]);
    kill(pid, SIGKILL);
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) { }
    return result;
}
