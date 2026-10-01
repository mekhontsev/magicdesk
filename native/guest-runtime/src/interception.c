#define _GNU_SOURCE
#include "bootstrap.h"
#include "file_calls.h"
#include "socket_calls.h"
#include "raw.h"
#include "interception.h"
#include "guest_domain.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <sys/prctl.h>
#include <sys/socket.h>

long md_guest_export(int channel, int descriptor) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    char byte = 'F';
    struct iovec vector = {&byte, 1};
    struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
        .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &descriptor, sizeof(descriptor));
    long result = RAW3(sendmsg, channel, &message, MSG_NOSIGNAL | MSG_DONTWAIT);
    RAW1(close, channel);
    return result;
}
/* Task-affine procfs lookup. No caller pathname is consumed here; the broker
 * passes a retained proc-root descriptor and checks the resulting capability. */
long md_guest_export_proc_fds(int channel, int directory) {
    long fd = RAW4(openat, directory, "self/fd/", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    RAW1(close, directory);
    if (fd < 0) { RAW1(close, channel); return fd; }
    long result = md_guest_export(channel, (int)fd);
    RAW1(close, fd);
    return result;
}

extern void md_guest_ready(int, const char *, uint64_t, const struct md_interception_abi *);
extern void md_guest_done(void), md_guest_allocate(void), md_guest_allocated(void);
extern void md_guest_exported(void), md_guest_store(void), md_guest_stored(void);
extern void md_guest_load_byte(void), md_guest_loaded_byte(void);
extern void md_guest_store_ids(void), md_guest_stored_ids(void);
extern char md_guest_copy_begin[], md_guest_copy_end[];
long md_guest_dispatch(long nr, unsigned long a0, unsigned long a1, unsigned long a2,
        unsigned long a3, unsigned long a4, unsigned long a5) {
    unsigned long args[] = {a0, a1, a2, a3, a4, a5};
    switch (nr) {
#define CALL(name) case SYS_##name:
        MD_FILE_CALLS(CALL)
            return md_file_call(&md_files, md_executable, nr, args);
        MD_SOCKET_CALLS(CALL)
            return md_socket_call(&md_files, md_executable, nr, args);
#undef CALL
        case SYS_execve: return md_guest_exec((void *)a0, (void *)a1, (void *)a2);
        case SYS_execveat: return md_guest_execat(a0, (void *)a1, (void *)a2, (void *)a3, a4);
        default: return -ENOSYS;
    }
}
static const struct md_interception_abi abi = {
    .magic = MD_INTERCEPTION_MAGIC, .size = sizeof(struct md_interception_abi),
    .ready = (uintptr_t)md_guest_ready, .done = (uintptr_t)md_guest_done,
    .allocate = (uintptr_t)md_guest_allocate, .allocated = (uintptr_t)md_guest_allocated,
    .dispatch = (uintptr_t)md_guest_dispatch, .export_fd = (uintptr_t)md_guest_export,
    .exported = (uintptr_t)md_guest_exported, .proc_export = (uintptr_t)md_guest_export_proc_fds,
    .store = (uintptr_t)md_guest_store, .stored = (uintptr_t)md_guest_stored,
    .load_byte = (uintptr_t)md_guest_load_byte, .loaded_byte = (uintptr_t)md_guest_loaded_byte,
    .store_ids = (uintptr_t)md_guest_store_ids, .stored_ids = (uintptr_t)md_guest_stored_ids,
    .raw_gate = (uintptr_t)md_raw_return,
    .copy_begin = (uintptr_t)md_guest_copy_begin, .copy_end = (uintptr_t)md_guest_copy_end
};

int md_interception_map_image(int fd, int loader) {
    long result = RAW3(prctl, MD_GUEST_MAP_IMAGE, fd, loader);
    return result < 0 ? (int)result : 0;
}
int md_interception_enter_image(void *aux, unsigned count) {
    long result = RAW3(prctl, MD_GUEST_ENTER_IMAGE, aux, count);
    if (result > 0) result = RAW2(prctl, PR_SET_DUMPABLE, 0);
    return (int)result;
}

int md_interception_install(int inherited) {
    long result = 0;
    int listener = -1;
    if (!inherited) {
        uintptr_t gate = (uintptr_t)md_raw_return;
#define COUNT(name) + 1
        enum { transport_instructions = 2 * (1 MD_GATE_TRANSPORT_CALLS(COUNT)) };
#undef COUNT
#define TRACE(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_DISPATCH),
#define OBSERVE(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_OBSERVE),
#define NATIVE(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
            /* Descriptor metadata must remain serviceable after an application
             * installs a higher-precedence filter or becomes nondumpable. */
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prctl, 0, 5),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_MAP_IMAGE, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, MD_GUEST_ENTER_IMAGE, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            OBSERVE(clone) OBSERVE(clone3) OBSERVE(chroot)
            NATIVE(read) NATIVE(write) NATIVE(readv) NATIVE(writev)
            NATIVE(pread64) NATIVE(pwrite64) NATIVE(close)
            NATIVE(futex) NATIVE(set_robust_list) NATIVE(get_robust_list)
            NATIVE(mmap) NATIVE(munmap) NATIVE(mprotect) NATIVE(madvise) NATIVE(brk)
            NATIVE(rt_sigaction) NATIVE(rt_sigprocmask) NATIVE(rt_sigreturn) NATIVE(sigaltstack)
            NATIVE(getpid) NATIVE(getppid) NATIVE(gettid)
            NATIVE(getrandom) NATIVE(clock_gettime)
            NATIVE(clock_nanosleep) NATIVE(nanosleep) NATIVE(ppoll) NATIVE(pselect6)
            NATIVE(epoll_pwait) NATIVE(epoll_ctl) NATIVE(epoll_create1)
            NATIVE(eventfd2) NATIVE(timerfd_create) NATIVE(timerfd_settime) NATIVE(timerfd_gettime)
            NATIVE(exit) NATIVE(exit_group) NATIVE(set_tid_address)
            MD_DOMAIN_KERNEL_CALLS(NATIVE)
            /* Compare the full policy argument, not only its low kernel int. */
#define NATIVE_ARGUMENT(n, index, value) \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 6), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index]) + 4), \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 3), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[index])), \
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, value, 0, 1), \
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW), \
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            MD_DOMAIN_KERNEL_ARGUMENTS(NATIVE_ARGUMENT)
#undef NATIVE_ARGUMENT
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(gate >> 32), 0, 8 + transport_instructions),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)gate, 0, 6 + transport_instructions),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            /* Already unconditional kernel operations in every domain. The
             * original application call still enters its adapter, and every
             * application filter still evaluates these internal syscalls. */
            MD_GATE_TRANSPORT_CALLS(NATIVE)
            /* Loader/adapter fstat already requests native descriptor metadata,
             * never logical inode or admitted set-ID metadata. Keep application
             * filters in force without a worker/supervisor round trip. */
            NATIVE(fstat)
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execve, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_EXEC),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execveat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_EXEC),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_NATIVE),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fstat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#define IDENTITY(n) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_##n, 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_IDENTITY),
            IDENTITY(getuid) IDENTITY(geteuid) IDENTITY(getgid) IDENTITY(getegid)
            IDENTITY(getresuid) IDENTITY(getresgid) IDENTITY(setresuid) IDENTITY(setresgid)
            IDENTITY(setuid) IDENTITY(setgid) IDENTITY(setreuid) IDENTITY(setregid)
            IDENTITY(setfsuid) IDENTITY(setfsgid) IDENTITY(setgroups)
#undef IDENTITY
            OBSERVE(unshare) OBSERVE(setns)
            OBSERVE(seccomp)
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_openat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_newfstatat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getdents64, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_prctl, 0, 5),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_SECCOMP, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, PR_SET_DUMPABLE, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_OBSERVE),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            MD_FILE_CALLS(TRACE)
            MD_SOCKET_CALLS(TRACE)
            TRACE(execve) TRACE(execveat)
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_NATIVE)
        };
#undef TRACE
#undef OBSERVE
#undef NATIVE
        struct sock_fprog program = {sizeof(filter) / sizeof(filter[0]), filter};
        result = RAW5(prctl, PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
        if (!result) {
            result = RAW3(seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &program);
            if (result >= 0) { listener = (int)result; result = 0; }
        }
    } else if (RAW1(prctl, PR_GET_SECCOMP) != SECCOMP_MODE_FILTER) result = -EINVAL;
    if (!result) md_guest_ready(listener, md_files.endpoint, MD_INTERCEPTION_MAGIC, &abi);
    if (listener >= 0) RAW1(close, listener);
    return result;
}
