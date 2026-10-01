#define _GNU_SOURCE
#include "event_wait.h"
#include "fs_rpc.h"
#include "fs_worker.h"
#include "fs_mounts.h"
#include "namespace_broker.h"
#include "guest_domain.h"
#include "elf_admission.h"
#include "interception.h"
#include "interception_stacks.h"
#include "watch_activation.h"
#include "launch_identity.h"
#include "credential_registry.h"
#include "guest_user.h"
#include "guest_accounts.h"
#include "process_owner.h"
#include "raw.h"
#include "profile.h"
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/pidfd.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <unistd.h>

/* One supervisor owns one launched tree. It never attaches to Android tasks. */
static struct md_interception_abi abi;
#define READY abi.ready
#define DONE abi.done
#define ALLOCATE abi.allocate
#define ALLOCATED abi.allocated
#define DISPATCH abi.dispatch
#define EXPORT abi.export_fd
#define EXPORTED abi.exported
#define PROC_EXPORT abi.proc_export
#define STORE abi.store
#define STORED abi.stored
#define LOAD_BYTE abi.load_byte
#define LOADED_BYTE abi.loaded_byte
#define STORE_IDS abi.store_ids
#define STORED_IDS abi.stored_ids
#define RAW_GATE abi.raw_gate
#define COPY_BEGIN abi.copy_begin
#define COPY_END abi.copy_end
static int diagnostics;
struct interception_statistics {
    uint64_t ptrace_requests, unknown, native_seeks, stops;
    struct md_cost rpc;
    struct { uint64_t trace, notification; } calls[512];
};
static struct interception_statistics *statistics;
#define TRACE(...) do { if (diagnostics) fprintf(stderr, __VA_ARGS__); } while (0)
enum phase { IDLE, ALLOCATING, DISPATCHING, EXECUTING, OBSERVING,
    EXPORT_CANCEL, EXPORTING, EXPORT_ENTRY, EXPORT_NOTIFY, EXPORT_RETURN, STORING,
    DELEGATE_CANCEL, LOADING_PATH, PROC_CANCEL, PROC_OPENING, STORING_IDS, DOMAIN_PATH,
    WATCH_CANCEL, WATCH_ENTRY, WATCH_READ, IDENTITY_COPY };
struct metadata_operation {
    enum phase previous;
    struct seccomp_notif request;
    struct user_pt_regs saved, returned;
    uint64_t mask;
    int peer, channel, error, descriptor;
    int proc_attempted, proc_directory, proc_stat, prepared;
    size_t copied, output_size, path_size;
    uintptr_t output;
    char path[4096];
    struct stat value;
};
struct thread {
    struct thread *next;
    struct md_interception_stacks *stacks;
    struct md_watch_activation *watch_activation;
    pid_t pid, tgid;
    int ready, born, initial_stop, resume_lost, listening;
    enum phase phase;
    uint64_t stack, mask;
    struct user_pt_regs original;
    unsigned calls;
    char endpoint[108];
    int filter_length;
    struct metadata_operation metadata;
    struct md_guest_domain *domain;
    unsigned long cloning;
    struct md_identity identity;
    struct md_exec_identity pending_identity;
    int mapped_image, entered;
    int watch_wait;
    uint32_t *identity_buffer;
    unsigned identity_count, identity_copied;
};
static struct thread *threads;
static pid_t leader;
static unsigned live, total, calls, images, signals, copy_faults, fstat_failures;
struct denial { unsigned nr, adapted, count; enum phase phase; };
static struct denial denials[64];
static int result, signal_fd, listener = -1;
static int cancelling;
static int64_t cancellation_deadline;
static unsigned external_stats, external_errors;
static unsigned protected_stats;
static unsigned retained_calls, retained_errors;
static unsigned root_changes, restricted_denials;
static unsigned group_stops, group_wakes, raced_wakes;
static const char *admitted_path;
static struct md_fs_worker *filesystem;
static struct md_namespace_broker *broker;
static struct md_credentials *credentials;
static struct md_identity launch_identity;
static int virtual_identity;
static struct md_admission admission = {.source = -1};
static unsigned admitted_images, identity_changes;
static int registers(pid_t pid, struct user_pt_regs *out);
static int set_registers(pid_t pid, struct user_pt_regs *regs);
static void resume(pid_t pid, int sig);
static int shield(struct thread *t);
static int restore_mask(struct thread *t);
static void dispatch(struct thread *t);
static void release_operation(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    if (op->peer >= 0) { close(op->peer); op->peer = -1; }
    if (op->descriptor >= 0) { close(op->descriptor); op->descriptor = -1; }
    if (op->prepared >= 0) { close(op->prepared); op->prepared = -1; }
}
static int describe_filter(pid_t pid, const struct user_pt_regs *regs) {
    if (!diagnostics) return -1;
    if (!((regs->regs[8] == SYS_seccomp && regs->regs[0] == SECCOMP_SET_MODE_FILTER)
            || (regs->regs[8] == SYS_prctl && regs->regs[0] == PR_SET_SECCOMP))) return -1;
    struct sock_fprog program;
    struct iovec local = {&program, sizeof(program)}, remote = {(void *)regs->regs[2], sizeof(program)};
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    TRACE("PROBE filter pid=%d instructions=%d readError=%d\n", pid,
        n == sizeof(program) ? program.len : -1, n < 0 ? errno : 0);
    return n == sizeof(program) ? program.len : -1;
}
static void describe_process(pid_t pid, uint64_t pc) {
    if (!diagnostics) return;
    const char *files[] = {"maps", "status"};
    for (unsigned i = 0; i < sizeof(files) / sizeof(*files); i++) {
        char path[64], line[1024];
        snprintf(path, sizeof(path), "/proc/%d/%s", pid, files[i]);
        FILE *file = fopen(path, "re");
        if (!file) { TRACE("PROBE %s error=%d\n", path, errno); continue; }
        unsigned count = 0;
        while (fgets(line, sizeof(line), file) && count++ < 4096) {
            unsigned long long begin = 0, end = 0;
            if (!i) sscanf(line, "%llx-%llx", &begin, &end);
            if (!i ? !pc || (pc >= begin && pc < end) :
                    !strncmp(line, "Seccomp", 7) || !strncmp(line, "NoNewPrivs", 10)
                    || !strncmp(line, "Uid:", 4) || !strncmp(line, "TracerPid:", 10))
                TRACE("PROBE %s %s", path, line);
        }
        fclose(file);
    }
}
static void cleanup(void) {
    for (struct thread *t = threads; t; t = t->next) if (t->pid) {
        kill(t->pid, SIGKILL); release_operation(t);
    }
    /* EVENT_WAIT: reap owned tracees after cancellation; outer runner bounds
     * cleanup if the kernel cannot complete an exit. */
    while (waitpid(-1, NULL, __WALL) > 0 || errno == EINTR) { }
    while (threads) {
        struct thread *next = threads->next;
        md_domain_release(threads->domain);
        md_stacks_release(threads->stacks);
        md_watch_activation_release(threads->watch_activation);
        free(threads); threads = next;
    }
    md_admission_close(&admission);
    if (md_fs_worker_stop(filesystem)) _Exit(125);
    filesystem = NULL;
    md_broker_destroy(broker); broker = NULL;
    md_credentials_destroy(credentials); credentials = NULL;
    md_identity_release(&launch_identity);
    if (listener >= 0) close(listener);
    free(statistics);
}
_Noreturn static void fail(const char *what, int line) {
    fprintf(stderr, "guest-runtime: supervisor failure line=%d %s errno=%d\n", line, what, errno);
    exit(125);
}
#define CHECK(x) do { if (!(x)) fail(#x, __LINE__); } while (0)
static long native_trace(int request, pid_t pid, void *address, void *data) {
    if (statistics) statistics->ptrace_requests++;
    return ptrace(request, pid, address, data);
}
static int tracee_request(struct thread *t, int request, void *address, void *data, const char *operation) {
    if (t->resume_lost) return 0;
    if (!native_trace(request, t->pid, address, data)) return 1;
    CHECK(errno == ESRCH);
    /* EVENT_WAIT: concurrent group death/exec may invalidate a reported stop.
     * Keep ownership until its exact exit/exec event; the tree deadline bounds
     * missing events. Never substitute registers, mask or a successful resume. */
    TRACE("PROBE lost-stop pid=%d phase=%d operation=%s\n", t->pid, t->phase, operation);
    t->resume_lost = 1; return 0;
}
static struct thread *find_thread(pid_t pid) {
    for (struct thread *t = threads; t; t = t->next) if (t->pid == pid) return t;
    return NULL;
}
static struct thread *thread(pid_t pid) {
    struct thread *t = find_thread(pid);
    if (t) return t;
    for (t = threads; t && t->pid; t = t->next) { }
    if (!t) { t = calloc(1, sizeof(*t)); CHECK(t); t->next = threads; threads = t; }
    *t = (struct thread){.pid = pid, .tgid = pid, .next = t->next,
        .metadata = {.peer = -1, .descriptor = -1, .prepared = -1},
        .identity = md_identity_copy(&launch_identity), .stacks = md_stacks_new(),
        .watch_activation = md_watch_activation_new()};
    CHECK(t->stacks && t->watch_activation);
    live++; total++; return t;
}
static void release_thread(struct thread *t) {
    md_credentials_forget(credentials, t->pid);
    md_identity_release(&t->identity);
    free(t->identity_buffer); t->identity_buffer = NULL;
    md_broker_forget(broker, t->pid);
    release_operation(t);
    md_domain_release(t->domain); t->domain = NULL;
    md_stacks_return(t->stacks, t->stack);
    md_stacks_release(t->stacks); t->stacks = NULL;
    md_watch_activation_release(t->watch_activation); t->watch_activation = NULL;
    t->pid = 0; live--;
}
static int duplicate_fd(pid_t pid, int descriptor) {
    /* Never substitute a group's leader for another thread's descriptor table.
     * Older kernels can duplicate a leader's descriptors; other threads use
     * their task-affine SCM_RIGHTS continuation instead. */
    int process = syscall(SYS_pidfd_open, pid, PIDFD_THREAD);
    if (process < 0 && errno == EINVAL)
        process = syscall(SYS_pidfd_open, pid, 0);
    if (process < 0) return -errno;
    int fd = syscall(SYS_pidfd_getfd, process, descriptor, 0);
    int error = errno;
    close(process);
    return fd < 0 ? -error : fd;
}
static int read_string(pid_t pid, uintptr_t address, char *text, size_t capacity) {
    for (size_t i = 0; i < capacity; i++) {
        if (address > UINTPTR_MAX - i) return -EFAULT;
        struct iovec local = {text + i, 1}, remote = {(void *)(address + i), 1};
        ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
        if (n != 1) return n < 0 ? -errno : -EFAULT;
        if (!text[i]) return 0;
    }
    return -ENAMETOOLONG;
}
static int descriptor_stat(struct thread *t, int fd, struct stat *st, int native) {
    /* Internal loader validation inspects the actual descriptor. Only the guest
     * ABI receives catalogue ownership/mode; logical set-ID cannot authorize a
     * host ELF or bypass the production loader's set-ID rejection. */
    if (native || !t->endpoint[0]) return fstat(fd, st) ? -errno : 0;
    struct md_fs_request request = {.actor = t->pid, .operation = MD_FS_FSTAT, .directory = {fd, -1}};
    struct md_fs_response response;
    int64_t begin = md_cost_begin(statistics ? &statistics->rpc : NULL);
    int error = md_fs_call(t->endpoint, 5000, &request, &response);
    md_cost_end(statistics ? &statistics->rpc : NULL, begin);
    if (!error) error = response.result.error;
    if (!error || error == -EXDEV) {
        if (fstat(fd, st)) error = -errno;
        else if (!error) {
            st->st_dev = response.result.info.device; st->st_ino = response.result.info.inode;
            st->st_nlink = response.result.info.links; st->st_mode = response.result.info.mode;
            st->st_uid = response.result.info.uid; st->st_gid = response.result.info.gid;
            st->st_rdev = response.result.info.rdev; st->st_size = response.result.info.size;
            st->st_blocks = response.result.info.blocks; st->st_blksize = response.result.info.block_size;
            st->st_atim = (struct timespec){response.result.info.access_seconds, response.result.info.access_nanos};
            st->st_mtim = (struct timespec){response.result.info.modify_seconds, response.result.info.modify_nanos};
            st->st_ctim = (struct timespec){response.result.info.change_seconds, response.result.info.change_nanos};
        }
        else error = 0;
    }
    return error;
}
static void export_begin(struct thread *t, const struct seccomp_notif *q) {
    CHECK(t->phase == IDLE || t->phase == DISPATCHING);
    int channel[2];
    CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC, 0, channel));
    struct seccomp_notif_addfd add = {.id = q->id, .srcfd = channel[1], .newfd_flags = O_CLOEXEC};
    int guest_fd = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
    int error = errno;
    close(channel[1]);
    if (guest_fd < 0) { close(channel[0]); CHECK(error == ENOENT); return; }
    t->metadata = (struct metadata_operation){.previous = t->phase, .request = *q,
        .peer = channel[0], .channel = guest_fd, .descriptor = -1, .prepared = -1,
        .output_size = q->data.nr == SYS_openat ? 0 : sizeof(struct stat),
        .output = q->data.args[q->data.nr == SYS_newfstatat ? 2 : 1]};
    t->phase = EXPORT_CANCEL;
    tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-notification");
}
static void export_start(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    errno = 0;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->request.id) == -1 && errno == ENOENT);
    if (!registers(t->pid, &op->saved)) return;
    op->saved.pc = op->request.data.instruction_pointer;
    op->saved.regs[8] = op->request.data.nr;
    for (unsigned i = 0; i < 6; i++) op->saved.regs[i] = op->request.data.args[i];
    if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(op->mask), &op->mask, "get-mask")
            || !shield(t)) return;
    struct user_pt_regs regs = op->saved;
    regs.pc = EXPORT; regs.regs[30] = EXPORTED;
    regs.regs[0] = op->channel;
    /* ADDFD can occupy the originally invalid requested number. Never export
     * that newly inserted transport as though the application had owned it. */
    regs.regs[1] = (int)op->request.data.args[0] == op->channel ? (uint64_t)-1 : op->request.data.args[0];
    if (!set_registers(t->pid, &regs)) return;
    t->phase = EXPORTING; resume(t->pid, 0);
}
static void replay(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    /* Reenter the original SVC: kernel admission, including any newer application
     * filter, precedes resource access, descriptor injection and output copying. */
    struct user_pt_regs regs = op->saved; regs.pc -= 4;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = EXPORT_ENTRY; resume(t->pid, 0);
}
static void load_path(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    if (op->path_size == sizeof(op->path)) op->error = -ENAMETOOLONG;
    if (op->request.data.args[1] > UINTPTR_MAX - op->path_size) op->error = -EFAULT;
    if (op->error) { replay(t); return; }
    struct user_pt_regs regs = op->saved;
    regs.pc = LOAD_BYTE; regs.regs[0] = op->request.data.args[1] + op->path_size;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = LOADING_PATH; resume(t->pid, 0);
}
static void export_finish(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    struct user_pt_regs regs;
    if (!registers(t->pid, &regs)) return;
    long sent = (long)regs.regs[0];
    op->error = sent < 0 ? (int)sent : sent == 1 ? 0 : -EIO;
    if (!op->error) {
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
        char byte; struct iovec vector = {&byte, 1};
        struct msghdr message = {.msg_iov = &vector, .msg_iovlen = 1,
            .msg_control = control.bytes, .msg_controllen = sizeof(control)};
        CHECK(recvmsg(op->peer, &message, MSG_CMSG_CLOEXEC) == 1 && byte == 'F');
        CHECK(!(message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)));
        struct cmsghdr *header = CMSG_FIRSTHDR(&message);
        CHECK(header && header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS
            && header->cmsg_len == CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&message, header));
        int fd; memcpy(&fd, CMSG_DATA(header), sizeof(fd));
        if (t->phase == PROC_OPENING && op->proc_stat) {
            op->error = descriptor_stat(t, fd, &op->value, 0); close(fd);
        } else if (t->phase == PROC_OPENING) op->prepared = fd; else op->descriptor = fd;
        if (op->request.data.nr == SYS_fstat)
            op->error = descriptor_stat(t, op->descriptor, &op->value,
                op->request.data.instruction_pointer == RAW_GATE);
    }
    if (t->phase == PROC_OPENING && op->proc_stat && op->error == -EBADF) op->error = -ENOENT;
    close(op->peer); op->peer = -1;
    if (op->request.data.nr == SYS_fstat || t->phase == PROC_OPENING) replay(t); else load_path(t);
}
static void store_next(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    if (op->error || op->copied == op->output_size) {
        struct user_pt_regs regs = op->returned;
        if (op->error) regs.regs[0] = (uint64_t)(long)op->error;
        if (!set_registers(t->pid, &regs)
                || !tracee_request(t, PTRACE_SETSIGMASK, (void *)sizeof(op->mask), &op->mask, "restore-mask")) return;
        release_operation(t);
        t->phase = op->previous; protected_stats++; resume(t->pid, 0); return;
    }
    _Static_assert(sizeof(struct stat) % 16 == 0, "register output blocks");
    struct user_pt_regs regs = op->returned;
    regs.pc = STORE; regs.regs[0] = op->output + op->copied;
    memcpy(&regs.regs[2], (const char *)&op->value + op->copied, 16);
    if (!set_registers(t->pid, &regs)) return;
    t->phase = STORING; resume(t->pid, 0);
}
static void proc_query_begin(struct thread *t, const struct seccomp_notif *q, int target) {
    struct metadata_operation *op = &t->metadata;
    int pair[2];
    CHECK(!socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC, 0, pair));
    struct seccomp_notif_addfd add = {.id = q->id, .srcfd = pair[1], .newfd_flags = O_CLOEXEC};
    op->channel = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
    close(pair[1]); CHECK(op->channel >= 0);
    op->proc_stat = target >= 0;
    if (op->proc_stat) op->proc_directory = target == op->channel ? -1 : target;
    else {
        add.srcfd = op->descriptor;
        op->proc_directory = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
        CHECK(op->proc_directory >= 0);
    }
    op->peer = pair[0]; op->request = *q; op->proc_attempted = 1; op->error = 0;
    t->phase = PROC_CANCEL; tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-proc-query");
}
static void proc_query_start(struct thread *t) {
    struct metadata_operation *op = &t->metadata;
    CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &op->request.id) == -1 && errno == ENOENT);
    struct user_pt_regs regs = op->saved;
    regs.pc = op->proc_stat ? EXPORT : PROC_EXPORT; regs.regs[30] = EXPORTED;
    regs.regs[0] = op->channel; regs.regs[1] = op->proc_directory;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = PROC_OPENING; resume(t->pid, 0);
}
static void retained_result(struct thread *t, const struct seccomp_notif *q) {
    struct metadata_operation *op = &t->metadata;
    int fd = -1;
    if (!op->error && !(op->proc_attempted && op->proc_stat)) {
        int flags = q->data.nr == SYS_openat ? (int)q->data.args[2] : O_PATH;
        if (q->data.nr == SYS_newfstatat) {
            if (q->data.args[3] & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT)) op->error = -ENOTSUP;
            if (q->data.args[3] & AT_SYMLINK_NOFOLLOW) flags |= O_NOFOLLOW;
        }
        if (!op->error) {
            if (op->proc_attempted) { fd = op->prepared; op->prepared = -1; }
            else fd = md_domain_open_retained(op->descriptor, t->pid, op->path, flags);
        }
        if (fd == -EACCES && !op->proc_attempted && q->data.nr == SYS_openat
                && md_domain_proc_fd_query(op->descriptor, op->path, flags)) {
            proc_query_begin(t, q, -1); return;
        }
        if ((fd == -EACCES || fd == -ELOOP) && !op->proc_attempted
                && q->data.nr == SYS_newfstatat && !q->data.args[3]) {
            int target = md_domain_proc_fd_number(op->descriptor, t->pid, op->path);
            if (target >= 0) { proc_query_begin(t, q, target); return; }
        }
        if (fd < 0 && !op->error) op->error = fd;
        if (!op->error && q->data.nr == SYS_newfstatat && fstat(fd, &op->value)) op->error = -errno;
    }
    retained_calls++;
    if (op->error) retained_errors++;
    TRACE("PROBE retained pid=%d nr=%d fd=%llu path=%s result=%d\n",
        t->pid, q->data.nr, q->data.args[0], op->path, op->error);
    if (!op->error && q->data.nr == SYS_openat) {
        struct seccomp_notif_addfd add = {.id = q->id, .srcfd = fd,
            .newfd_flags = (unsigned)q->data.args[2] & O_CLOEXEC,
            .flags = SECCOMP_ADDFD_FLAG_SEND};
        int result = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
        if (result < 0) op->error = -errno;
        else { close(fd); t->phase = EXPORT_RETURN; return; }
    }
    if (fd >= 0) close(fd);
    struct seccomp_notif_resp reply = {.id = q->id, .error = op->error};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
    t->phase = EXPORT_RETURN;
}
static void image_request(struct thread *t, const struct seccomp_notif *q) {
    struct seccomp_notif_resp reply = {.id = q->id};
    if (q->data.instruction_pointer != RAW_GATE || t->phase != IDLE) reply.error = -EPERM;
    else if (q->data.args[0] == MD_GUEST_MAP_IMAGE) {
        if (t->entered || q->data.args[2] > 1) reply.error = -EPERM;
        else if (!q->data.args[2]) {
            int fd = admitted_path ? duplicate_fd(t->pid, (int)q->data.args[1]) : -1;
            int matched = !admitted_path ? 0 : fd >= 0 ? md_admission_match(&admission, t->endpoint, fd) : fd;
            if (fd >= 0) close(fd);
            if (matched < 0) reply.error = matched;
            else {
                struct md_exec_identity next = {.value = t->identity};
                next.value.uid.saved = next.value.uid.fs = next.value.uid.effective;
                next.value.gid.saved = next.value.gid.fs = next.value.gid.effective;
                next.secure = next.value.uid.real != next.value.uid.effective
                    || next.value.gid.real != next.value.gid.effective;
                if (matched) reply.error = md_identity_prepare_exec(&t->identity, admission.image, 0, &next);
                if (!reply.error && matched) {
                    fd = md_exec_image_dup(admission.image);
                    if (fd < 0) reply.error = fd;
                    else {
                        struct seccomp_notif_addfd add = {.id = q->id, .srcfd = fd,
                            .newfd = q->data.args[1], .newfd_flags = O_CLOEXEC,
                            .flags = SECCOMP_ADDFD_FLAG_SETFD | SECCOMP_ADDFD_FLAG_SEND};
                        int sent = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
                        int error = errno; close(fd);
                        if (sent >= 0) {
                            t->pending_identity = next; t->mapped_image = 1; admitted_images++;
                            TRACE("PROBE admitted-image pid=%d fd=%d euid=%u secure=%d\n",
                                t->pid, sent, next.value.uid.effective, next.secure);
                            return;
                        }
                        if (error == ENOENT) return;
                        reply.error = -error;
                    }
                }
                if (!reply.error) { t->pending_identity = next; t->mapped_image = 1; }
            }
        }
    } else if (q->data.args[0] == MD_GUEST_ENTER_IMAGE) {
        if (!t->ready || t->entered || q->data.args[2] > 128) reply.error = -EPERM;
        else if (t->mapped_image) {
            size_t count = q->data.args[2];
            Elf64_auxv_t aux[128];
            struct iovec local = {aux, count * sizeof(*aux)}, remote = {(void *)q->data.args[1], local.iov_len};
            if (process_vm_readv(t->pid, &local, 1, &remote, 1, 0) != (ssize_t)local.iov_len) reply.error = -EFAULT;
            unsigned found = 0;
            const struct md_exec_identity *next = &t->pending_identity;
            for (size_t i = 0; !reply.error && i < count; i++) {
                switch (aux[i].a_type) {
                case AT_UID: aux[i].a_un.a_val = next->value.uid.real; found |= 1; break;
                case AT_EUID: aux[i].a_un.a_val = next->value.uid.effective; found |= 2; break;
                case AT_GID: aux[i].a_un.a_val = next->value.gid.real; found |= 4; break;
                case AT_EGID: aux[i].a_un.a_val = next->value.gid.effective; found |= 8; break;
                case AT_SECURE: aux[i].a_un.a_val = next->secure; found |= 16; break;
                }
            }
            if (!reply.error && found != 31) reply.error = -EPROTO;
            if (!reply.error && process_vm_writev(t->pid, &local, 1, &remote, 1, 0) != (ssize_t)local.iov_len)
                reply.error = -EFAULT;
            if (!reply.error) {
                reply.val = next->secure;
                CHECK(!md_credentials_publish(credentials, t->pid, t->tgid, &next->value));
                if (!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply)) {
                    t->identity = next->value; t->mapped_image = 0; t->entered = 1;
                    TRACE("PROBE entered-image pid=%d uid=%u euid=%u secure=%d\n", t->pid,
                        t->identity.uid.real, t->identity.uid.effective, next->secure);
                } else {
                    CHECK(errno == ENOENT);
                    CHECK(!md_credentials_publish(credentials, t->pid, t->tgid, &t->identity));
                }
                return;
            }
        }
    } else reply.error = -EPROTO;
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}
static void handle_notification(const struct seccomp_notif *request) {
    struct seccomp_notif q = *request;
    CHECK(q.data.nr == SYS_fstat || q.data.nr == SYS_openat || q.data.nr == SYS_newfstatat
        || q.data.nr == SYS_getdents64 || q.data.nr == SYS_prctl);
    struct thread *t = find_thread((pid_t)q.pid);
    CHECK(t && t->born);
    if (q.data.nr == SYS_prctl) { image_request(t, &q); return; }
    if (q.data.nr == SYS_getdents64 && md_domain_restricted(t->domain)) {
        CHECK(t->phase == IDLE);
        struct seccomp_notif_resp reply = {.id = q.id, .flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE};
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT); return;
    }
    if (t->phase == EXPORT_NOTIFY) {
        struct metadata_operation *op = &t->metadata;
        CHECK(q.id != op->request.id && !memcmp(&q.data, &op->request.data, sizeof(q.data)));
        if (q.data.nr != SYS_fstat) { retained_result(t, &q); return; }
        struct seccomp_notif_resp reply = {.id = q.id, .error = op->error};
        CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply));
        t->phase = EXPORT_RETURN; return;
    }
    if (q.data.nr != SYS_fstat) {
        if (t->phase != IDLE) TRACE("PROBE unexpected notification pid=%d phase=%d nr=%d pc=%#llx fd=%llu\n",
            t->pid, t->phase, q.data.nr, q.data.instruction_pointer, q.data.args[0]);
        CHECK(t->phase == IDLE);
        if (!md_domain_restricted(t->domain)) {
            t->metadata = (struct metadata_operation){.request = q, .descriptor = -1, .peer = -1, .prepared = -1};
            t->phase = DELEGATE_CANCEL;
            tracee_request(t, PTRACE_INTERRUPT, NULL, NULL, "interrupt-delegation"); return;
        }
        if ((int)q.data.args[0] < 0) {
            struct seccomp_notif_resp reply = {.id = q.id,
                .error = (int)q.data.args[0] == AT_FDCWD ? md_domain_path_error(t->domain) : -EBADF};
            CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT); return;
        }
        export_begin(t, &q); return;
    }
    int fd = duplicate_fd(q.pid, (int)q.data.args[0]);
    if (fd == -EPERM || fd == -EACCES || fd == -EINVAL || fd == -ENOSYS || fd == -ESRCH) {
        export_begin(t, &q); return;
    }
    int error = fd < 0 ? fd : 0;
    struct stat st;
    if (!error) {
        error = descriptor_stat(t, fd, &st, q.data.instruction_pointer == RAW_GATE);
        close(fd);
    }
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id)) { CHECK(errno == ENOENT); return; }
    if (!error) {
        struct iovec local = {&st, sizeof(st)}, remote = {(void *)q.data.args[1], sizeof(st)};
        ssize_t n = process_vm_writev(q.pid, &local, 1, &remote, 1, 0);
        if (n != sizeof(st)) error = n < 0 ? -errno : -EFAULT;
    }
    external_stats++;
    if (error && ++external_errors <= 8)
        TRACE("PROBE external fstat pid=%u fd=%llu result=%d\n", q.pid, q.data.args[0], error);
    struct seccomp_notif_resp reply = {.id = q.id, .error = error};
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}
static void delegated_notification(const struct seccomp_notif *request) {
    handle_notification(request);
}
static void external_stat(void) {
    struct seccomp_notif q = {0};
    if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &q)) {
        CHECK(errno == ENOENT || errno == EINTR); return;
    }
    if (statistics) {
        if ((unsigned)q.data.nr < 512) statistics->calls[q.data.nr].notification++;
        else statistics->unknown++;
    }
    handle_notification(&q);
}
static int registers(pid_t pid, struct user_pt_regs *out) {
    struct iovec iov = {out, sizeof(*out)};
    if (!tracee_request(thread(pid), PTRACE_GETREGSET, (void *)NT_PRSTATUS, &iov, "get-registers")) return 0;
    CHECK(iov.iov_len == sizeof(*out)); return 1;
}
static int set_registers(pid_t pid, struct user_pt_regs *regs) {
    struct iovec iov = {regs, sizeof(*regs)};
    return tracee_request(thread(pid), PTRACE_SETREGSET, (void *)NT_PRSTATUS, &iov, "set-registers");
}
static int skip(pid_t pid) {
    int nr = -1; struct iovec iov = {&nr, sizeof(nr)};
    return tracee_request(thread(pid), PTRACE_SETREGSET, (void *)NT_ARM_SYSTEM_CALL, &iov, "skip-syscall");
}
static void return_value(struct thread *t, struct user_pt_regs *regs, long value) {
    if (!skip(t->pid)) return;
    regs->regs[0] = (uint64_t)value;
    if (set_registers(t->pid, regs)) resume(t->pid, 0);
}
static void identity_copy_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->original;
    if (!error && r.regs[8] == SYS_setgroups)
        error = md_identity_groups(&t->identity, t->identity_buffer, t->identity_count);
    r.regs[0] = (uint64_t)(error ? (long)error : r.regs[8] == SYS_getgroups ? (long)t->identity_count : 0);
    free(t->identity_buffer); t->identity_buffer = NULL;
    if (!set_registers(t->pid, &r) || !restore_mask(t)) return;
    t->phase = IDLE; resume(t->pid, 0);
}
static void identity_copy_next(struct thread *t) {
    if (t->identity_copied == t->identity_count) { identity_copy_finish(t, 0); return; }
    struct user_pt_regs r = t->original;
    unsigned count = t->identity_count - t->identity_copied;
    if (count > 16) count = 16;
    uintptr_t address = r.regs[1], offset = (uintptr_t)t->identity_copied * sizeof(uint32_t);
    if (address > UINTPTR_MAX - offset - count * sizeof(uint32_t)) { identity_copy_finish(t, -EFAULT); return; }
    int loading = r.regs[8] == SYS_setgroups;
    r.pc = loading ? abi.load_groups : abi.store_groups;
    r.regs[0] = address + offset; r.regs[1] = count;
    if (!loading) for (unsigned i = 0; i < count; i++) r.regs[i+3] = t->identity_buffer[t->identity_copied+i];
    if (!set_registers(t->pid, &r)) return;
    t->phase = IDENTITY_COPY; resume(t->pid, 0);
}
static void identity_groups_call(struct thread *t, struct user_pt_regs *r) {
    int setting = r->regs[8] == SYS_setgroups;
    unsigned count = setting ? (unsigned)r->regs[0] : md_identity_group_count(&t->identity);
    int error = 0;
    if (setting && t->identity.uid.effective) error = -EPERM;
    else if ((int)r->regs[0] < 0 || (setting && (unsigned)r->regs[0] > MD_IDENTITY_GROUPS_MAX)
            || (!setting && r->regs[0] && (unsigned)r->regs[0] < count)) error = -EINVAL;
    if (error || (!setting && !r->regs[0])) { return_value(t, r, error ? error : (long)count); return; }
    if (!count) { if (setting) md_identity_groups(&t->identity, NULL, 0); return_value(t, r, 0); return; }
    uint32_t *buffer = malloc((size_t)count * sizeof(*buffer));
    if (!buffer) { return_value(t, r, -ENOMEM); return; }
    if (!setting) memcpy(buffer, md_identity_group_data(&t->identity), (size_t)count * sizeof(*buffer));
    struct iovec local = {buffer, (size_t)count * sizeof(*buffer)}, remote = {(void *)r->regs[1], local.iov_len};
    ssize_t copied = setting ? process_vm_readv(t->pid, &local, 1, &remote, 1, 0)
        : process_vm_writev(t->pid, &local, 1, &remote, 1, 0);
    if (copied == (ssize_t)local.iov_len) {
        error = setting ? md_identity_groups(&t->identity, buffer, count) : 0;
        free(buffer); return_value(t, r, error ? error : setting ? 0 : (long)count); return;
    }
    /* Task-affine copies preserve protected-memory and EFAULT semantics without
     * relaxing dumpability. Identity publishes only after the complete read. */
    t->identity_buffer = buffer; t->identity_count = count; t->identity_copied = 0; t->original = *r;
    if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(t->mask), &t->mask, "get-mask")
            || !shield(t) || !skip(t->pid)) return;
    identity_copy_next(t);
}
static int identity_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    if (r->pc == RAW_GATE) return 0;
    long nr = r->regs[8], value;
    if (nr == SYS_prctl && (r->regs[0] == PR_GET_NO_NEW_PRIVS || r->regs[0] == PR_SET_NO_NEW_PRIVS)) {
        CHECK(t->phase == IDLE && t->ready);
        if (r->regs[2] || r->regs[3] || r->regs[4]) value = -EINVAL;
        else if (r->regs[0] == PR_GET_NO_NEW_PRIVS) value = r->regs[1] ? -EINVAL : t->identity.no_new_privs;
        else value = md_identity_no_new_privs(&t->identity, r->regs[1]);
        return_value(t, r, value); return 1;
    }
    if (cookie != MD_INTERCEPT_IDENTITY) return 0;
    CHECK(t->ready && t->phase == IDLE);
    switch (nr) {
    case SYS_getgroups: case SYS_setgroups: identity_groups_call(t, r); return 1;
    case SYS_getuid: value = t->identity.uid.real; break;
    case SYS_geteuid: value = t->identity.uid.effective; break;
    case SYS_getgid: value = t->identity.gid.real; break;
    case SYS_getegid: value = t->identity.gid.effective; break;
    case SYS_getresuid: case SYS_getresgid: {
        const struct md_identity_ids *ids = nr == SYS_getresuid ? &t->identity.uid : &t->identity.gid;
        t->original = *r;
        if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(t->mask), &t->mask, "get-mask")
                || !shield(t) || !skip(t->pid)) return 1;
        r->pc = STORE_IDS; r->regs[3] = ids->real; r->regs[4] = ids->effective; r->regs[5] = ids->saved;
        if (!set_registers(t->pid, r)) return 1;
        t->phase = STORING_IDS; resume(t->pid, 0); return 1;
    }
    case SYS_setresuid:
        value = md_identity_setresuid(&t->identity, r->regs[0], r->regs[1], r->regs[2]); break;
    case SYS_setresgid:
        value = md_identity_setresgid(&t->identity, r->regs[0], r->regs[1], r->regs[2]); break;
    case SYS_setuid: value = md_identity_setuid(&t->identity, r->regs[0]); break;
    case SYS_setgid: value = md_identity_setgid(&t->identity, r->regs[0]); break;
    case SYS_setreuid: value = md_identity_setreuid(&t->identity, r->regs[0], r->regs[1]); break;
    case SYS_setregid: value = md_identity_setregid(&t->identity, r->regs[0], r->regs[1]); break;
    case SYS_setfsuid: value = md_identity_setfsuid(&t->identity, r->regs[0]); break;
    case SYS_setfsgid: value = md_identity_setfsgid(&t->identity, r->regs[0]); break;
    default: value = -ENOTSUP; break;
    }
    if ((nr == SYS_setresuid || nr == SYS_setresgid) && !value) {
        identity_changes++;
        TRACE("PROBE identity-change pid=%d uid=%u euid=%u suid=%u gid=%u egid=%u\n",
            t->pid, t->identity.uid.real, t->identity.uid.effective, t->identity.uid.saved,
            t->identity.gid.real, t->identity.gid.effective);
    }
    return_value(t, r, value); return 1;
}
static int domain_path_apply(struct thread *t, long nr, const char *path) {
    if (nr == SYS_chdir)
        return !strcmp(path, "/") ? md_domain_chdir_root(t->domain) : -ENOTSUP;
    int error = md_domain_restrict(t->domain, t->pid, path);
    if (!error) {
        root_changes++;
        /* CLONE_FS members may run without another ptrace stop. Revoke their
         * published eligibility before acknowledging the shared restriction. */
        if (broker) for (struct thread *member = threads; member; member = member->next)
            if (member->pid && member->domain == t->domain)
                CHECK(!md_broker_task(broker, member->pid, 0, RAW_GATE, abi.watch_gate + 4));
    }
    TRACE("PROBE root-change pid=%d result=%d\n", t->pid, error);
    return error;
}
static void domain_path_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->original;
    if (!error) error = domain_path_apply(t, r.regs[8], t->metadata.path);
    r.regs[0] = (uint64_t)error;
    if (!set_registers(t->pid, &r) || !restore_mask(t)) return;
    t->phase = IDLE; resume(t->pid, 0);
}
static void domain_path_load(struct thread *t) {
    size_t offset = t->metadata.path_size;
    if (offset == sizeof(t->metadata.path)) { domain_path_finish(t, -ENAMETOOLONG); return; }
    if (t->original.regs[0] > UINTPTR_MAX - offset) { domain_path_finish(t, -EFAULT); return; }
    struct user_pt_regs r = t->original;
    r.pc = LOAD_BYTE; r.regs[0] += offset;
    if (!set_registers(t->pid, &r)) return;
    t->phase = DOMAIN_PATH; resume(t->pid, 0);
}
static void domain_path_call(struct thread *t, struct user_pt_regs *r) {
    char path[4096];
    int error = read_string(t->pid, r->regs[0], path, sizeof(path));
    if (error != -EPERM && error != -EACCES) {
        if (!error) error = domain_path_apply(t, r->regs[8], path);
        return_value(t, r, error); return;
    }
    CHECK(t->ready && t->phase == IDLE);
    t->original = *r; t->metadata.path_size = 0;
    if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(t->mask), &t->mask, "get-mask")
            || !shield(t) || !skip(t->pid)) return;
    domain_path_load(t);
}
static int domain_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    long nr = r->regs[8];
    if (nr == SYS_socket) {
        int error = md_domain_socket_error((int)r->regs[0], (int)r->regs[2]);
        if (error) { return_value(t, r, error); return 1; }
    }
    if (nr == SYS_unshare || nr == SYS_setns) { return_value(t, r, -ENOTSUP); return 1; }
    if (nr == SYS_clone3) { return_value(t, r, -ENOSYS); return 1; }
    if (nr == SYS_clone) {
        if (r->regs[0] & (CLONE_NEWUSER | CLONE_NEWPID | CLONE_NEWNET | CLONE_NEWNS
                | CLONE_NEWIPC | CLONE_NEWUTS)) {
            return_value(t, r, -EINVAL); return 1;
        }
        t->cloning = r->regs[0]; return 0;
    }
    if (nr == SYS_chroot) {
        if ((admitted_path || virtual_identity) && !md_identity_may_chroot(&t->identity)) return_value(t, r, -EPERM);
        else domain_path_call(t, r);
        return 1;
    }
    if (!md_domain_restricted(t->domain)) return 0;
    if (t->phase == PROC_OPENING && cookie == MD_INTERCEPT_NATIVE && nr == SYS_openat
            && (int)r->regs[0] == t->metadata.proc_directory
            && r->regs[2] == (O_RDONLY | O_DIRECTORY | O_CLOEXEC)) {
        resume(t->pid, 0); return 1;
    }
    if (nr == SYS_kill || nr == SYS_tgkill || nr == SYS_tkill) {
        long target = (long)r->regs[nr == SYS_tgkill ? 1 : 0];
        struct thread *destination = target > 0 ? find_thread(target) : NULL;
        if (destination && destination->born) {
            resume(t->pid, 0); return 1;
        }
        return_value(t, r, -EPERM); return 1;
    }
    unsigned long args[6];
    for (unsigned i = 0; i < 6; i++) args[i] = r->regs[i];
    if (md_domain_native_call(nr, args)) {
        if (cookie == MD_INTERCEPT_OBSERVE || cookie == MD_INTERCEPT_WATCH) return 0;
        resume(t->pid, 0); return 1;
    }
    int error = cookie == MD_INTERCEPT_DISPATCH ? md_domain_path_error(t->domain) : -EPERM;
    if (nr == SYS_chdir) {
        domain_path_call(t, r); return 1;
    }
    if (error && ++restricted_denials <= 32)
        TRACE("PROBE restricted pid=%d nr=%ld result=%d\n", t->pid, nr, error);
    return_value(t, r, error); return 1;
}
static int descriptor_call(struct thread *t, struct user_pt_regs *r, unsigned long cookie) {
    if (cookie != MD_INTERCEPT_DISPATCH || r->regs[8] != SYS_lseek) return 0;
    CHECK(t->ready && t->phase == IDLE);
    int fd = duplicate_fd(t->pid, (int)r->regs[0]);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); return 0; }
    /* A retained open-file description needs no namespace lookup or guest
     * memory access. Never cache FD numbers or resume against a replaceable FD.
     * Original seccomp admission and domain checks precede this operation. */
    off_t result = lseek(fd, (off_t)r->regs[1], (int)r->regs[2]);
    long value = result < 0 ? -errno : result;
    close(fd);
    if (statistics) statistics->native_seeks++;
    return_value(t, r, value); return 1;
}
static void resume(pid_t pid, int sig) {
    struct thread *t = thread(pid);
    if (t->resume_lost) return;
    CHECK(!md_credentials_publish(credentials, t->pid, t->tgid, &t->identity));
    enum phase phase = t->phase;
    if (broker) CHECK(!md_broker_task(broker, pid,
        t->ready && phase == IDLE && t->endpoint[0] && !md_domain_restricted(t->domain), RAW_GATE, abi.watch_gate + 4));
    if (!native_trace(phase == OBSERVING || phase == EXPORT_ENTRY || phase == EXPORT_NOTIFY
            || phase == EXPORT_RETURN || phase == EXECUTING || phase == WATCH_CANCEL
            || phase == WATCH_ENTRY || phase == WATCH_READ ? PTRACE_SYSCALL : PTRACE_CONT,
            pid, NULL, (void *)(uintptr_t)sig)) return;
    CHECK(errno == ESRCH);
    /* EVENT_WAIT: thread-group exit or exec may win a stopped-thread resume.
     * Only the exact waitpid exit/exec event releases ownership. A later stop
     * is inconsistent and the existing process deadline bounds a missing event. */
    t->resume_lost = 1;
}
static int shield(struct thread *t) {
    uint64_t mask = UINT64_MAX;
    int sync[] = {SIGKILL, SIGSTOP, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP, SIGSYS};
    for (unsigned i = 0; i < sizeof(sync) / sizeof(*sync); i++) mask &= ~(UINT64_C(1) << (sync[i] - 1));
    return tracee_request(t, PTRACE_SETSIGMASK, (void *)sizeof(mask), &mask, "shield-signals");
}
static int restore_mask(struct thread *t) {
    return tracee_request(t, PTRACE_SETSIGMASK, (void *)sizeof(t->mask), &t->mask, "restore-mask");
}
static void dispatch(struct thread *t) {
    struct user_pt_regs regs = t->original;
    regs.pc = DISPATCH; regs.sp = t->stack; regs.regs[30] = DONE;
    regs.regs[0] = t->original.regs[8];
    for (unsigned i = 0; i < 6; i++) regs.regs[i + 1] = t->original.regs[i];
    if (!set_registers(t->pid, &regs)) return;
    t->phase = DISPATCHING; resume(t->pid, 0);
}
static void dispatch_with_stack(struct thread *t) {
    if (!t->stack) t->stack = md_stacks_take(t->stacks);
    if (t->stack) dispatch(t);
    else {
        struct user_pt_regs regs = t->original; regs.pc = ALLOCATE;
        if (!set_registers(t->pid, &regs)) return;
        t->phase = ALLOCATING; resume(t->pid, 0);
    }
}
static void drain(void) {
    struct signalfd_siginfo info;
    while (read(signal_fd, &info, sizeof(info)) == sizeof(info)) {
        if (info.ssi_signo != SIGCHLD && !cancelling) {
            TRACE("PROBE cancelled signal=%u\n", info.ssi_signo);
            result = 128 + info.ssi_signo; cancelling = 1;
            cancellation_deadline = md_event_now() + 2000000000LL;
            CHECK(!md_process_signal_children(SIGTERM));
        }
    }
    CHECK(errno == EAGAIN);
}
static pid_t wait_event(int *status, int64_t deadline) {
    for (;;) {
        pid_t pid = waitpid(-1, status, __WALL | WNOHANG);
        if (pid > 0) return pid;
        CHECK(pid == 0 || errno == EINTR);
        /* EVENT_WAIT: ptrace stop/exit or cancellation via signalfd; expiry
         * fails and cancels the owned tree, never counts as readiness. */
        struct pollfd events[] = {{signal_fd, POLLIN, 0}, {broker ? -1 : listener, POLLIN, 0},
            {md_fs_worker_fd(filesystem), POLLIN, 0}};
        int64_t bound = cancelling ? cancellation_deadline : deadline;
        long outcome = md_event_wait(events, 3, bound);
        if (outcome == -ETIMEDOUT && cancelling == 1) {
            /* EVENT_WAIT: graceful cancellation expired; drain killed children
             * for at most ten seconds before reporting incomplete cleanup. */
            cancelling = 2; cancellation_deadline = md_event_now() + 10000000000LL;
            CHECK(!md_process_signal_children(SIGKILL)); continue;
        }
        CHECK(outcome >= 0);
        if (events[0].revents) { drain(); continue; }
        if (events[2].revents) CHECK(!md_broker_complete(broker, delegated_notification));
        if (events[1].revents & POLLIN) external_stat();
        if (events[1].revents & POLLHUP) { close(listener); listener = -1; }
    }
}
int main(int argc, char **argv) {
    CHECK(argc >= 2 && md_launch_identity(getuid(), geteuid(), getgid(), getegid()));
    md_page_size = (size_t)sysconf(_SC_PAGESIZE);
    CHECK(md_page_size >= 4096 && md_page_size <= 65536 && !(md_page_size & (md_page_size - 1)));
    int argument = 1;
    long seconds = 0;
    while (argument < argc) {
        if (!strcmp(argv[argument], "--diagnostics")) diagnostics = 1;
        else if (!strcmp(argv[argument], "--statistics")) {
            CHECK(!statistics);
            statistics = calloc(1, sizeof(*statistics));
            CHECK(statistics);
        } else break;
        argument++;
    }
    if (argument < argc && !strcmp(argv[argument], "--deadline-seconds")) {
        CHECK(argument + 2 < argc);
        char *end;
        seconds = strtol(argv[argument + 1], &end, 10);
        CHECK(!*end && seconds >= 1 && seconds <= 3600);
        argument += 2;
    }
    setvbuf(stderr, NULL, _IONBF, 0);
    if (argument < argc && !strcmp(argv[argument], "--admit-elf")) {
        CHECK(argument + 2 < argc);
        admitted_path = argv[argument + 1]; argument += 2;
        CHECK(admitted_path[0] == '/');
    }
    const char *selected_user = NULL, *selected_groups = NULL;
    launch_identity = md_identity_new(getuid(), getgid());
    credentials = md_credentials_create(); CHECK(credentials);
    struct md_identity owner = md_identity_new(getuid(), getgid());
    CHECK(!md_credentials_publish(credentials, getpid(), getpid(), &owner));
    if (argument < argc && !strcmp(argv[argument], "--user")) {
        CHECK(argument + 2 < argc);
        selected_user = argv[argument + 1]; virtual_identity = 1; argument += 2;
    } else {
        int count = getgroups(0, NULL); CHECK(count >= 0);
        gid_t *groups = count ? malloc((size_t)count * sizeof(*groups)) : NULL;
        CHECK(!count || groups);
        CHECK(getgroups(count, groups) == count && !md_identity_groups(&launch_identity, groups, (unsigned)count));
        free(groups);
    }
    if (argument < argc && !strcmp(argv[argument], "--groups")) {
        CHECK(virtual_identity && argument + 2 < argc);
        selected_groups = argv[argument + 1]; argument += 2;
    }
    struct md_fs_attachment attachments[MD_FS_MOUNTS_MAX];
    unsigned attachment_count = 0;
    while (argument < argc && (!strcmp(argv[argument], "--bind") || !strcmp(argv[argument], "--bind-ro"))) {
        CHECK(argument+2 < argc && attachment_count < MD_FS_MOUNTS_MAX);
        attachments[attachment_count++] = (struct md_fs_attachment){
            .source=argv[argument+1], .target=argv[argument+2], .readonly=!strcmp(argv[argument], "--bind-ro")};
        argument += 3;
    }
    const char *store = NULL, *endpoint = NULL;
    if (argument < argc && !strcmp(argv[argument], "--store")) {
        CHECK(argument + 4 < argc && !strcmp(argv[argument + 2], "--endpoint"));
        store = argv[argument + 1]; endpoint = argv[argument + 3]; argument += 4;
    }
    if (selected_user) {
        uint32_t uid, gid;
        if (!md_guest_user_parse(selected_user, &uid, &gid)) launch_identity = md_identity_new(uid, gid);
        else {
            struct md_inode_store *accounts = NULL;
            CHECK(store && !md_inode_store_open(store, 0, &accounts));
            struct md_filesystem view = {.store=accounts};
            CHECK(!md_fs_mounts_open(&view, attachments, attachment_count));
            int resolved = md_guest_user_resolve(&view, selected_user, &launch_identity);
            md_fs_mounts_close(&view); md_inode_store_close(accounts);
            if (resolved) { errno = -resolved; CHECK(!resolved); }
        }
    }
    if (selected_groups) {
        const char *p = selected_groups;
        uint32_t *groups = malloc(MD_IDENTITY_GROUPS_MAX * sizeof(*groups)); CHECK(groups);
        unsigned count = 0;
        while (*p) {
            CHECK(count < MD_IDENTITY_GROUPS_MAX && *p >= '0' && *p <= '9');
            uint64_t value = 0;
            do { value = value * 10 + (unsigned)(*p++ - '0'); CHECK(value < UINT32_MAX); }
            while (*p >= '0' && *p <= '9');
            groups[count++] = (uint32_t)value;
            if (*p) { CHECK(*p++ == ',' && *p); }
        }
        CHECK(!md_identity_groups(&launch_identity, groups, count));
        free(groups);
    }
    int inherited_no_new_privs = prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
    CHECK(inherited_no_new_privs >= 0);
    launch_identity.no_new_privs = (unsigned)inherited_no_new_privs;
    CHECK(argument < argc);
    struct md_process_signals inherited;
    CHECK(md_process_signals_open(&inherited) >= 0);
    signal_fd = inherited.fd;
    CHECK(!prctl(PR_SET_CHILD_SUBREAPER, 1) && !atexit(cleanup));
    int channel[2]; CHECK(!socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel));
    pid_t parent = getpid();
    leader = fork(); CHECK(leader >= 0);
    if (!leader) {
        close(channel[0]); close(signal_fd);
        CHECK(!md_process_signals_restore(&inherited));
        CHECK(!prctl(PR_SET_PDEATHSIG, SIGKILL) && getppid() == parent);
        char byte = 'r'; CHECK(write(channel[1], &byte, 1) == 1);
        /* EVENT_WAIT: supervisor's attach acknowledgement; the outer timeout cancels. */
        CHECK(read(channel[1], &byte, 1) == 1 && byte == 'g'); close(channel[1]);
        execv(argv[argument], argv + argument); _exit(127);
    }
    thread(leader)->born = 1; close(channel[1]);
    int keep[] = {signal_fd, channel[0]};
    CHECK(!md_process_close_fds(keep, 2));
    /* EVENT_WAIT: child readiness precedes attaching; timeout cancels this tree. */
    CHECK(md_event_wait_fd(channel[0], POLLIN, md_event_now() + 5000000000LL) >= 0);
    char byte; CHECK(read(channel[0], &byte, 1) == 1 && byte == 'r');
    CHECK(!native_trace(PTRACE_SEIZE, leader, NULL, (void *)(uintptr_t)(PTRACE_O_TRACESECCOMP | PTRACE_O_TRACEEXEC
        | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE | PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD)));
    if (store) {
        /* The guest was forked with the caller's mask. Only the native owner
         * uses zero; creation requests already contain the guest's masked mode. */
        umask(0);
        CHECK(!md_credentials_publish(credentials, leader, leader, &thread(leader)->identity));
        int startup = md_fs_worker_start(store, endpoint, admitted_path, credentials, attachments, attachment_count, statistics != NULL, &filesystem);
        if (startup) errno = -startup;
        CHECK(!startup);
        broker = md_broker_create(filesystem, statistics != NULL); CHECK(broker);
    }
    byte = 'g'; CHECK(write(channel[0], &byte, 1) == 1); close(channel[0]);
    int64_t deadline = seconds ? md_event_now() + seconds * 1000000000LL : INT64_MAX;
    while (live) {
        int status; pid_t pid = wait_event(&status, deadline);
        struct thread *t = thread(pid);
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            if (pid == leader && !cancelling) result = code;
            if (code) TRACE("PROBE exit pid=%d status=%d ready=%d calls=%u\n", pid, code, t->ready, t->calls);
            release_thread(t); continue;
        }
        CHECK(WIFSTOPPED(status));
        if (statistics) statistics->stops++;
        unsigned event = (unsigned)status >> 16;
        CHECK(!t->resume_lost || event == PTRACE_EVENT_EXEC);
        int sig = WSTOPSIG(status);
        if (event == PTRACE_EVENT_EXEC) {
            /* A non-leader exec replaces the task behind the leader's PID. */
            md_broker_forget(broker, pid);
            unsigned long old; CHECK(!native_trace(PTRACE_GETEVENTMSG, pid, NULL, &old));
            if (old && old != (unsigned)pid) {
                struct thread *former = find_thread((pid_t)old);
                if (former) {
                    release_operation(t);
                    md_domain_release(t->domain);
                    t->domain = former->domain; former->domain = NULL;
                    md_identity_release(&t->identity);
                    t->identity = md_identity_copy(&former->identity);
                    md_watch_activation_release(t->watch_activation);
                    t->watch_activation = former->watch_activation; former->watch_activation = NULL;
                    memcpy(t->endpoint, former->endpoint, sizeof(t->endpoint));
                    release_thread(former);
                }
            }
            md_stacks_return(t->stacks, t->stack);
            md_stacks_release(t->stacks); t->stacks = md_stacks_new(); CHECK(t->stacks);
            t->phase = IDLE; t->stack = 0; t->ready = 0; t->resume_lost = 0; images++;
            t->mapped_image = 0; t->entered = 0;
            resume(pid, 0);
        } else if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
            unsigned long child;
            if (!tracee_request(t, PTRACE_GETEVENTMSG, NULL, &child, "birth-event")) continue;
            struct thread *c = thread(child);
            CHECK(!c->born);
            c->born = 1; c->ready = t->ready;
            md_identity_release(&c->identity);
            c->identity = md_identity_copy(&t->identity);
            c->tgid = t->cloning & CLONE_THREAD ? t->tgid : c->pid;
            md_stacks_release(c->stacks);
            c->stacks = md_stacks_fork(t->stacks, !!(t->cloning & CLONE_VM)); CHECK(c->stacks);
            md_watch_activation_release(c->watch_activation);
            c->watch_activation = md_watch_activation_fork(t->watch_activation, !!(t->cloning & CLONE_THREAD));
            CHECK(c->watch_activation);
            c->domain = md_domain_fork(t->domain, !!(t->cloning & CLONE_FS));
            CHECK(!t->domain || c->domain); t->cloning = 0;
            memcpy(c->endpoint, t->endpoint, sizeof(c->endpoint));
            if (cancelling) CHECK(!md_process_signal_children(cancelling == 1 ? SIGTERM : SIGKILL));
            /* EVENT_WAIT: a newborn stop and its parent's birth event can
             * arrive in either order. Both must precede the child's resume. */
            if (c->initial_stop) resume(c->pid, 0);
            resume(pid, 0);
        } else if (event == PTRACE_EVENT_STOP) {
            if (sig == SIGSTOP || sig == SIGTSTP || sig == SIGTTIN || sig == SIGTTOU) {
                /* EVENT_WAIT: preserve the kernel group-stop until SIGCONT or
                 * exit. CONT here would silently discard the application's stop.
                 * Keep the operation phase intact across the listening state. */
                t->listening = 1; group_stops++;
                if (native_trace(PTRACE_LISTEN, pid, NULL, NULL)) {
                    CHECK(errno == ESRCH); t->resume_lost = 1;
                }
                continue;
            }
            int wake = t->listening;
            if (wake) { CHECK(sig == SIGTRAP); t->listening = 0; group_wakes++; }
            if (t->phase == EXPORT_CANCEL) { export_start(t); continue; }
            if (t->phase == PROC_CANCEL) {
                proc_query_start(t); continue;
            }
            if (t->phase == DELEGATE_CANCEL) {
                const struct seccomp_notif *q = &t->metadata.request;
                CHECK(ioctl(listener, SECCOMP_IOCTL_NOTIF_ID_VALID, &q->id) == -1 && errno == ENOENT);
                struct user_pt_regs regs;
                if (!registers(pid, &regs)) continue;
                regs.pc = q->data.instruction_pointer; regs.regs[8] = q->data.nr;
                for (unsigned i = 0; i < 6; i++) regs.regs[i] = q->data.args[i];
                t->original = regs; calls++; t->calls++;
                if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(t->mask), &t->mask, "get-mask")
                        || !shield(t)) continue;
                if (!t->stack) t->stack = md_stacks_take(t->stacks);
                if (t->stack) dispatch(t);
                else {
                    regs.pc = ALLOCATE;
                    if (!set_registers(pid, &regs)) continue;
                    t->phase = ALLOCATING; resume(pid, 0);
                }
                continue;
            }
            if (wake) { resume(pid, 0); continue; }
            CHECK(sig == SIGTRAP);
            if (t->initial_stop) {
                /* SIGCONT can clear a pending group-stop before this tracee
                 * reports it. The kernel then reports EVENT_STOP/SIGTRAP without
                 * an earlier LISTEN. This is not a second newborn stop. */
                CHECK(t->born); raced_wakes++; resume(pid, 0); continue;
            }
            t->initial_stop = 1;
            if (t->born) resume(pid, 0);
        }
        else if (event == PTRACE_EVENT_SECCOMP) {
            unsigned long cookie;
            if (!tracee_request(t, PTRACE_GETEVENTMSG, NULL, &cookie, "seccomp-event")) continue;
            struct user_pt_regs regs;
            if (!registers(pid, &regs)) continue;
            if (statistics) {
                if (regs.regs[8] < 512) statistics->calls[regs.regs[8]].trace++;
                else statistics->unknown++;
            }
            if (regs.regs[8] == SYS_prctl && regs.regs[0] == MD_GUEST_WATCH_FILTER
                    && regs.pc == RAW_GATE && t->phase == DISPATCHING) {
                long value = regs.regs[2] == 0 ? md_watch_activation_has(t->watch_activation, (unsigned)regs.regs[1])
                    : regs.regs[2] == 1 ? md_watch_activation_mark(t->watch_activation, (unsigned)regs.regs[1]) : -EINVAL;
                return_value(t, &regs, value); continue;
            }
            if (identity_call(t, &regs, cookie)) continue;
            if (domain_call(t, &regs, cookie)) continue;
            if (descriptor_call(t, &regs, cookie)) continue;
            if (cookie == MD_INTERCEPT_WATCH) {
                CHECK(t->ready && t->phase == IDLE);
                t->original = regs;
                t->watch_wait = 0;
                if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(t->mask), &t->mask, "watch-mask")
                        || !shield(t) || !skip(pid)) continue;
                t->phase = WATCH_CANCEL; resume(pid, 0); continue;
            }
            if (cookie == MD_INTERCEPT_NATIVE) { resume(pid, 0); continue; }
            if (cookie == MD_INTERCEPT_EXEC) {
                CHECK(t->phase == DISPATCHING);
                if (!restore_mask(t)) continue;
                t->phase = EXECUTING; resume(pid, 0); continue;
            }
            if (cookie == MD_INTERCEPT_OBSERVE) {
                CHECK(t->ready && t->phase == IDLE);
                t->filter_length = describe_filter(pid, &regs);
                t->original = regs; t->phase = OBSERVING;
                resume(pid, 0); continue;
            }
            if (cookie != MD_INTERCEPT_DISPATCH || !t->ready || t->phase != IDLE)
                TRACE("PROBE unexpected TRACE pid=%d cookie=%lu ready=%d phase=%d pc=%#llx nr=%llu adapted=%llu\n",
                    pid, cookie, t->ready, t->phase, regs.pc, regs.regs[8], t->original.regs[8]);
            CHECK(cookie == MD_INTERCEPT_DISPATCH && t->ready && t->phase == IDLE);
            t->original = regs; calls++; t->calls++;
            if (!tracee_request(t, PTRACE_GETSIGMASK, (void *)sizeof(t->mask), &t->mask, "get-mask")
                    || !shield(t) || !skip(pid)) continue;
            dispatch_with_stack(t);
        } else if (!event && sig == (SIGTRAP | 0x80)) {
            if (t->phase == WATCH_CANCEL) {
                struct user_pt_regs regs = t->original; regs.pc = abi.watch_gate;
                if (!set_registers(pid, &regs)) continue;
                t->phase = WATCH_ENTRY; resume(pid, 0); continue;
            }
            if (t->phase == WATCH_ENTRY) {
                /* Restore before the blocking syscall, not after readiness. */
                if (!restore_mask(t)) continue;
                t->phase = WATCH_READ; resume(pid, 0); continue;
            }
            if (t->phase == WATCH_READ) {
                struct user_pt_regs returned;
                if (!registers(pid, &returned)) continue;
                long value = (long)returned.regs[0];
                if (value == MD_WATCH_TASK_AFFINE || (t->watch_wait == 1 && value > 0)) {
                    if (!shield(t)) continue;
                    t->watch_wait = 0;
                    dispatch_with_stack(t); continue;
                }
                struct user_pt_regs regs = t->original; regs.regs[0] = returned.regs[0];
                if (!set_registers(pid, &regs)) continue;
                if (t->stack) { md_stacks_return(t->stacks, t->stack); t->stack = 0; }
                md_fs_worker_wake(filesystem);
                t->phase = IDLE; resume(pid, 0); continue;
            }
            if (t->phase == PROC_CANCEL) {
                /* EVENT_WAIT: PTRACE_INTERRUPT under PTRACE_SYSCALL reports a
                 * syscall-exit stop instead of EVENT_STOP. Require cancellation
                 * of the exact notification before entering the continuation. */
                proc_query_start(t); continue;
            }
            if (t->phase == EXPORT_ENTRY) {
                t->phase = EXPORT_NOTIFY;
                resume(pid, 0); continue;
            }
            if (t->phase == EXPORT_RETURN || t->phase == EXPORT_NOTIFY) {
                struct metadata_operation *op = &t->metadata;
                if (!registers(pid, &op->returned)) continue;
                if (t->phase == EXPORT_NOTIFY || (long)op->returned.regs[0] < 0)
                    op->error = (int)op->returned.regs[0];
                /* A newer policy can complete the replay without notifying us,
                 * even with ERRNO|0. Preserve that result and leave output alone. */
                if (t->phase == EXPORT_NOTIFY) op->copied = op->output_size;
                store_next(t); continue;
            }
            if (t->phase == OBSERVING) {
                struct user_pt_regs regs;
                if (!registers(pid, &regs)) continue;
                TRACE("PROBE native syscall pid=%d nr=%llu arg0=%#llx arg1=%#llx result=%lld\n",
                    pid, t->original.regs[8], t->original.regs[0], t->original.regs[1], (long long)regs.regs[0]);
                if (!regs.regs[0] && ((t->original.regs[8] == SYS_seccomp
                            && t->original.regs[0] == SECCOMP_SET_MODE_FILTER)
                        || (t->original.regs[8] == SYS_prctl && t->original.regs[0] == PR_SET_SECCOMP)))
                    TRACE("PROBE installed-filter pid=%d instructions=%d\n", pid, t->filter_length);
                t->phase = IDLE; resume(pid, 0); continue;
            }
            CHECK(t->phase == EXECUTING);
            if (!shield(t)) continue;
            t->phase = DISPATCHING; resume(pid, 0);
        } else if (!event && sig == SIGTRAP) {
            struct user_pt_regs regs;
            if (!registers(pid, &regs)) continue;
            if (!t->ready && t->phase == IDLE && regs.regs[2] == MD_INTERCEPTION_MAGIC) {
                struct md_interception_abi received;
                struct iovec local = {&received, sizeof(received)}, remote = {(void *)regs.regs[3], sizeof(received)};
                CHECK(process_vm_readv(pid, &local, 1, &remote, 1, 0) == sizeof(received));
                CHECK(received.magic == MD_INTERCEPTION_MAGIC && received.size == sizeof(received)
                    && received.ready == regs.pc && received.copy_end > received.copy_begin);
                CHECK(!abi.magic || !memcmp(&abi, &received, sizeof(abi)));
                abi = received;
            }
            if (regs.pc == LOADED_BYTE && t->phase == DOMAIN_PATH) {
                t->metadata.path[t->metadata.path_size++] = (char)regs.regs[2];
                if (regs.regs[2]) domain_path_load(t); else domain_path_finish(t, 0);
                continue;
            }
            if (regs.pc == STORED_IDS && t->phase == STORING_IDS) {
                regs = t->original; regs.regs[0] = 0;
                if (!set_registers(pid, &regs) || !restore_mask(t)) continue;
                t->phase = IDLE; resume(pid, 0); continue;
            }
            if (t->phase == IDENTITY_COPY && (regs.pc == abi.loaded_groups || regs.pc == abi.stored_groups)) {
                unsigned count = t->identity_count - t->identity_copied;
                if (count > 16) count = 16;
                if (regs.pc == abi.loaded_groups)
                    for (unsigned i = 0; i < count; i++) t->identity_buffer[t->identity_copied+i] = regs.regs[i+3];
                t->identity_copied += count; identity_copy_next(t); continue;
            }
            if (regs.pc == EXPORTED && (t->phase == EXPORTING || t->phase == PROC_OPENING)) {
                export_finish(t); continue;
            }
            if (regs.pc == LOADED_BYTE && t->phase == LOADING_PATH) {
                struct metadata_operation *op = &t->metadata;
                op->path[op->path_size++] = (char)regs.regs[2];
                if (regs.regs[2]) load_path(t); else replay(t);
                continue;
            }
            if (regs.pc == STORED && t->phase == STORING) {
                t->metadata.copied += 16; store_next(t); continue;
            }
            if (regs.pc == READY && t->phase == IDLE) {
                CHECK(!read_string(pid, regs.regs[1], t->endpoint, sizeof(t->endpoint)));
                if (admitted_path && !admission.image) {
                    int error = md_admission_open(&admission, t->endpoint, admitted_path);
                    if (error) TRACE("PROBE admission error=%d\n", error);
                    CHECK(!error);
                }
                if (!t->domain) { t->domain = md_domain_new(); CHECK(t->domain); }
                if ((int)regs.regs[0] >= 0) {
                    CHECK(listener == -1);
                    listener = duplicate_fd(pid, (int)regs.regs[0]); CHECK(listener >= 0);
                    if (broker) md_broker_listen(broker, listener);
                }
                t->ready = 1; regs.pc += 4;
                if (!set_registers(pid, &regs)) continue;
                describe_process(pid, 0);
                TRACE("PROBE guest image ready pid=%d\n", pid); resume(pid, 0);
            } else if (regs.pc == ALLOCATED && t->phase == ALLOCATING) {
                CHECK((long)regs.regs[0] > 0 && !(regs.regs[0] & (md_page_size - 1)));
                t->stack = regs.regs[0]; CHECK(!md_stacks_add(t->stacks, t->stack)); dispatch(t);
            } else if (regs.pc == DONE && t->phase == DISPATCHING) {
                long value = regs.regs[0];
                if (value == MD_WATCH_WAIT || value == MD_WATCH_NATIVE) {
                    regs = t->original; regs.pc = RAW_GATE - 4;
                    t->watch_wait = value == MD_WATCH_WAIT ? 1 : 2;
                    if (t->watch_wait == 1) {
                        /* EVENT_WAIT: native MSG_PEEK waits for queue readiness
                         * without consuming it. Original signals/restart policy
                         * apply; the namespace owner never waits on this reader. */
                        regs.regs[8] = SYS_recvfrom;
                        regs.regs[1] = t->stack - 16; regs.regs[2] = 1;
                        regs.regs[3] = MSG_PEEK; regs.regs[4] = regs.regs[5] = 0;
                    }
                    if (!set_registers(pid, &regs)) continue;
                    t->phase = WATCH_ENTRY; resume(pid, 0); continue;
                }
                if (value < 0 && t->original.regs[8] == SYS_fstat) {
                    if (++fstat_failures <= 8)
                        TRACE("PROBE fstat failed pid=%d fd=%llu result=%ld\n", pid, t->original.regs[0], value);
                }
                regs = t->original; regs.regs[0] = value;
                if (!set_registers(pid, &regs) || !restore_mask(t)) continue;
                md_stacks_return(t->stacks, t->stack); t->stack = 0;
                t->phase = IDLE; resume(pid, 0);
            } else {
                TRACE("PROBE guest SIGTRAP pid=%d pc=%#llx lr=%#llx phase=%d\n",
                    pid, regs.pc, regs.regs[30], t->phase);
                describe_process(pid, regs.pc);
                resume(pid, SIGTRAP);
            }
        } else {
            struct user_pt_regs regs;
            if ((sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL)
                    && !registers(pid, &regs)) continue;
            if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == DOMAIN_PATH
                    && regs.pc == LOAD_BYTE) {
                domain_path_finish(t, -EFAULT); continue;
            }
            if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == IDENTITY_COPY
                    && ((regs.pc >= abi.load_groups && regs.pc < abi.loaded_groups)
                        || (regs.pc >= abi.store_groups && regs.pc < abi.stored_groups))) {
                identity_copy_finish(t, -EFAULT); continue;
            }
            if ((sig == SIGSEGV || sig == SIGBUS) && t->phase == STORING_IDS) {
                if (regs.pc >= STORE_IDS && regs.pc < STORED_IDS) {
                    regs = t->original; regs.regs[0] = (uint64_t)-EFAULT;
                    if (!set_registers(pid, &regs) || !restore_mask(t)) continue;
                    t->phase = IDLE; resume(pid, 0); continue;
                }
            }
            if (sig == SIGSEGV && t->phase == LOADING_PATH && regs.pc == LOAD_BYTE) {
                t->metadata.error = -EFAULT; replay(t); continue;
            }
            if (sig == SIGSEGV && t->phase == STORING && regs.pc == STORE) {
                t->metadata.error = -EFAULT; store_next(t); continue;
            }
            if (sig == SIGSEGV && t->phase == DISPATCHING) {
                if (regs.pc >= COPY_BEGIN && regs.pc < COPY_END) {
                    copy_faults++;
                    regs.regs[0] = (uint64_t)-EFAULT; regs.pc = regs.regs[30];
                    if (set_registers(pid, &regs)) resume(pid, 0);
                    continue;
                }
            }
            if (sig == SIGSYS || sig == SIGSEGV || sig == SIGABRT || sig == SIGILL) {
                siginfo_t info;
                if (!tracee_request(t, PTRACE_GETSIGINFO, NULL, &info, "signal-info")) continue;
                signals++;
                if (sig == SIGSYS) {
                    unsigned i;
                    for (i = 0; i < 64; i++) {
                        struct denial *d = &denials[i];
                        if (!d->count) *d = (struct denial){.nr = info.si_syscall,
                            .adapted = t->phase == IDLE ? 0 : t->original.regs[8], .phase = t->phase};
                        if (d->nr == (unsigned)info.si_syscall && d->phase == t->phase
                                && d->adapted == (t->phase == IDLE ? 0 : t->original.regs[8])) {
                            d->count++; break;
                        }
                    }
                    if (i == 64 || denials[i].count > 4) { resume(pid, sig); continue; }
                }
                TRACE("PROBE signal pid=%d signal=%d code=%d pc=%#llx nr=%llu phase=%d adapted=%llu address=%p\n",
                    pid, sig, info.si_code, regs.pc, regs.regs[8], t->phase, t->original.regs[8], info.si_addr);
                if (sig == SIGSYS)
                    TRACE("PROBE application SIGSYS syscall=%d cookie=%d call=%p\n", info.si_syscall, info.si_errno, info.si_call_addr);
            }
            resume(pid, sig);
        }
    }
    for (unsigned i = 0; i < 64 && denials[i].count; i++)
        TRACE("PROBE SIGSYS summary nr=%u phase=%d adapted=%u count=%u\n",
            denials[i].nr, denials[i].phase, denials[i].adapted, denials[i].count);
    TRACE("PROBE complete status=%d threads=%u images=%u adapted=%u signals=%u copyFaults=%u fstatFailures=%u sandboxSupportEstablished=false\n",
        result, total, images, calls, signals, copy_faults, fstat_failures);
    TRACE("PROBE external metadata calls=%u errors=%u\n", external_stats, external_errors);
    TRACE("PROBE protected metadata calls=%u\n", protected_stats);
    TRACE("PROBE retained requests=%u errors=%u\n", retained_calls, retained_errors);
    TRACE("PROBE proc-root domain=1 changes=%u denials=%u\n",
        root_changes, restricted_denials);
    TRACE("PROBE job-control stops=%u wakes=%u racedWakes=%u\n",
        group_stops, group_wakes, raced_wakes);
    TRACE("PROBE identity admittedImages=%u changes=%u hostUid=%u\n",
        admitted_images, identity_changes, getuid());
    if (statistics) {
        struct rusage usage;
        if (!getrusage(RUSAGE_SELF, &usage)) fprintf(stderr, "MD_CPU supervisor userUs=%llu systemUs=%llu\n",
            (unsigned long long)usage.ru_utime.tv_sec * 1000000 + usage.ru_utime.tv_usec,
            (unsigned long long)usage.ru_stime.tv_sec * 1000000 + usage.ru_stime.tv_usec);
        fprintf(stderr, "MD_SUPERVISOR stops=%llu rpcCalls=%llu rpcNs=%llu\n",
            (unsigned long long)statistics->stops, (unsigned long long)statistics->rpc.calls,
            (unsigned long long)statistics->rpc.nanoseconds);
        fprintf(stderr, "MD_INTERCEPTION ptraceRequests=%llu unknown=%llu nativeSeeks=%llu\n",
            (unsigned long long)statistics->ptrace_requests, (unsigned long long)statistics->unknown,
            (unsigned long long)statistics->native_seeks);
        for (unsigned nr = 0; nr < 512; nr++)
            if (statistics->calls[nr].trace || statistics->calls[nr].notification)
                fprintf(stderr, "MD_SYSCALL nr=%u trace=%llu notification=%llu\n", nr,
                    (unsigned long long)statistics->calls[nr].trace,
                    (unsigned long long)statistics->calls[nr].notification);
    }
    close(signal_fd); return result;
}
