#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/futex.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/utsname.h>
#include <sys/auxv.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <sys/ptrace.h>
#include <ucontext.h>
#include <unistd.h>

static void memory(void) {
    assert(syscall(SYS_openat, AT_FDCWD, 1, O_RDONLY, 0) == -1 && errno == EFAULT);
    int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC);
    assert(fd >= 0);
    struct stat st;
    assert(!fstat(fd, &st) && S_ISREG(st.st_mode));
    assert(syscall(SYS_fstat, fd, 1) == -1 && errno == EFAULT);
    assert(!fstat(fd, &st) && st.st_size > 0);
    assert(!close(fd));
}
struct broker_context { int descriptor; char directory[128]; ino_t inode; };
static int broker_private_files(void *data) {
    struct broker_context *context = data;
    assert(!chdir(context->directory));
    for (unsigned i = 0; i < 64; ++i) {
        int fd = open("item", O_RDONLY); assert(fd >= 0);
        assert(dup2(fd, context->descriptor) == context->descriptor);
        if (fd != context->descriptor) close(fd);
        struct stat st;
        assert(!fstat(context->descriptor, &st) && st.st_ino == context->inode);
        assert(!fstatat(AT_FDCWD, "item", &st, 0) && st.st_ino == context->inode);
    }
    return 0;
}
static void broker_files(void) {
    struct broker_context context;
    snprintf(context.directory, sizeof(context.directory), "/tmp/md-broker-%d", getpid());
    assert(!mkdir(context.directory, 0700));
    char file[160], alias[160];
    snprintf(file, sizeof(file), "%s/item", context.directory);
    snprintf(alias, sizeof(alias), "%s/alias", context.directory);
    int created = open(file, O_CREAT | O_EXCL | O_RDWR, 0600); assert(created >= 0);
    assert(write(created, "payload", 7) == 7);
    struct stat original, st;
    assert(!fstat(created, &original)); context.inode = original.st_ino;
    assert(!link(file, alias) && !stat(alias, &st) && st.st_ino == original.st_ino && st.st_nlink == 2);
    int dir = open(context.directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC); assert(dir >= 0);
    int fd = openat(dir, "item", O_RDONLY | O_CLOEXEC); assert(fd >= 0);
    assert(fcntl(fd, F_GETFD) == FD_CLOEXEC && !fstatat(fd, "", &st, AT_EMPTY_PATH));
    assert(st.st_ino == original.st_ino);
    close(fd);
    fd = openat(dir, "item", O_RDONLY); assert(fd >= 0 && !fcntl(fd, F_GETFD)); close(fd);
    long page = sysconf(_SC_PAGESIZE);
    char *pages = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(pages != MAP_FAILED && !mprotect(pages + page, page, PROT_NONE));
    char *boundary = pages + page - strlen(file) - 1; strcpy(boundary, file);
    fd = open(boundary, O_RDONLY); assert(fd >= 0); close(fd);
    assert(!stat(boundary, &st) && st.st_ino == original.st_ino);
    assert(syscall(SYS_openat, AT_FDCWD, pages + page, O_RDONLY, 0) == -1 && errno == EFAULT);
    assert(!mprotect(pages, page, PROT_READ));
    assert(syscall(SYS_fstat, created, pages) == -1 && errno == EFAULT);
    assert(syscall(SYS_newfstatat, dir, "item", pages, 0) == -1 && errno == EFAULT);
    munmap(pages, page * 2);
    context.descriptor = open("/etc/passwd", O_RDONLY); assert(context.descriptor >= 0);
    struct stat parent; assert(!fstat(context.descriptor, &parent));
    char cwd[4096], after[4096]; assert(getcwd(cwd, sizeof(cwd)));
    size_t stack_size = 256 * 1024;
    void *stack = mmap(NULL, stack_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(stack != MAP_FAILED);
    int tid = 1;
    /* Same thread group, but private FD table and cwd. A leader pidfd cannot
     * stand in for the notification's exact task identity. */
    assert(clone(broker_private_files, (char *)stack + stack_size,
        CLONE_VM | CLONE_SIGHAND | CLONE_THREAD | CLONE_CHILD_SETTID | CLONE_CHILD_CLEARTID,
        &context, NULL, NULL, &tid) > 0);
    /* EVENT_WAIT: kernel clears/wakes child TID after thread exit; runner bounds failure. */
    int live;
    while ((live = __atomic_load_n(&tid, __ATOMIC_ACQUIRE))) {
        long r = syscall(SYS_futex, &tid, FUTEX_WAIT, live, NULL, NULL, 0);
        assert(!r || errno == EAGAIN || errno == EINTR);
    }
    munmap(stack, stack_size);
    assert(getcwd(after, sizeof(after)) && !strcmp(cwd, after));
    assert(!fstat(context.descriptor, &st) && st.st_ino == parent.st_ino);
    close(context.descriptor);
    assert(!unlink(file) && !unlink(alias));
    assert(!fstat(created, &st) && st.st_ino == original.st_ino && st.st_nlink == 0 && st.st_size == 7);
    close(created); close(dir); assert(!rmdir(context.directory));
    memory();
    puts("PASS broker paths, guard pages, CLOEXEC, hardlinks, unlinked identity and task-private FD/cwd");
}
static void directory_entry(int fd, const char *name) {
    struct { uint64_t inode; int64_t offset; unsigned short length; unsigned char type; char name[256]; } entry;
    assert(syscall(SYS_getdents64, fd, &entry, 24) == 24);
    assert(entry.length == 24 && !strcmp(entry.name, name));
}
static void broker_directories(void) {
    char path[128]; snprintf(path, sizeof(path), "/tmp/md-directories-%d", getpid());
    assert(!mkdir(path, 0700));
    int fd = open(path, O_RDONLY | O_DIRECTORY), duplicate = dup(fd); assert(fd >= 0 && duplicate >= 0);
    int file = openat(fd, "item", O_CREAT | O_EXCL | O_RDWR, 0600); assert(file >= 0); close(file);
    long page = sysconf(_SC_PAGESIZE);
    char *memory = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(memory != MAP_FAILED && !mprotect(memory + page, page, PROT_NONE));
    assert(syscall(SYS_getdents64, fd, memory + page, 24) == -1 && errno == EFAULT);
    assert(lseek(duplicate, 0, SEEK_CUR) == 0);
    assert(syscall(SYS_getdents64, fd, memory + page - 8, 24) == -1 && errno == EFAULT);
    assert(lseek(duplicate, 0, SEEK_CUR) == 0);
    assert(syscall(SYS_getdents64, fd, memory, 1) == -1 && errno == EINVAL);
    directory_entry(fd, "."); directory_entry(duplicate, "..");
    pid_t child = fork(); assert(child >= 0);
    if (!child) { directory_entry(fd, "item"); _exit(0); }
    int status; assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(syscall(SYS_getdents64, duplicate, memory, page) == 0);
    assert(lseek(fd, 0, SEEK_SET) == 0);
    child = fork(); assert(child >= 0);
    if (!child) {
        struct sock_filter code[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getdents64, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EIO),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
        };
        struct sock_fprog filter = {sizeof(code) / sizeof(*code), code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &filter));
        assert(syscall(SYS_getdents64, duplicate, memory, page) == -1 && errno == EIO);
        _exit(0);
    }
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(lseek(fd, 0, SEEK_CUR) == 0);
    child = fork(); assert(child >= 0);
    if (!child) {
        assert(!prctl(PR_SET_DUMPABLE, 0));
        directory_entry(duplicate, "."); directory_entry(duplicate, ".."); _exit(0);
    }
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    directory_entry(fd, "item");
    int native = open("/proc/self/fd", O_RDONLY | O_DIRECTORY); assert(native >= 0);
    assert(syscall(SYS_getdents64, native, memory, page) > 0); close(native);
    assert(!unlinkat(fd, "item", 0)); close(duplicate); close(fd); assert(!rmdir(path));
    assert(!munmap(memory, page * 2));
    puts("PASS broker directories: shared cursors, partial/failed output, fork, application filters, protected fallback and native procfs");
}
static int broker_shared_root(void *data) {
    int *pipes = data;
    char byte;
    /* EVENT_WAIT: parent primes its broker state before shared-root restriction. */
    assert(read(pipes[0], &byte, 1) == 1 && byte == 'R');
    assert(!chroot("/proc/self/fdinfo"));
    assert(write(pipes[3], "R", 1) == 1);
    /* EVENT_WAIT: keep the proc-root owner alive until the parent checks denial. */
    assert(read(pipes[0], &byte, 1) == 1 && byte == 'F');
    return 0;
}
static void broker_shared_domain(void) {
    int pipes[4]; assert(!pipe(pipes) && !pipe(pipes + 2));
    size_t size = 256 * 1024;
    void *stack = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(stack != MAP_FAILED);
    pid_t child = clone(broker_shared_root, (char *)stack + size, CLONE_FS | SIGCHLD, pipes);
    assert(child > 0);
    int fd = open("/etc/passwd", O_RDONLY); assert(fd >= 0); close(fd);
    assert(write(pipes[1], "R", 1) == 1);
    char byte;
    /* EVENT_WAIT: only native pipe IO separates this task's broker eligibility
     * from the sibling's successful change. No intervening ptrace stop refreshes it. */
    assert(read(pipes[2], &byte, 1) == 1 && byte == 'R');
    fd = open("/etc/passwd", O_RDONLY);
    int open_denied = fd == -1 && errno == EACCES;
    if (fd >= 0) close(fd);
    struct stat st;
    int stat_denied = stat("/etc/passwd", &st) == -1 && errno == EACCES;
    assert(write(pipes[1], "F", 1) == 1);
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(open_denied && stat_denied);
    munmap(stack, size);
    for (unsigned i = 0; i < 4; ++i) close(pipes[i]);
    puts("PASS shared-root restriction revokes sibling broker eligibility");
}
static void *worker(void *unused) { (void)unused; memory(); return NULL; }
static void *exiting_worker(void *unused) {
    (void)unused;
    for (;;) memory();
}
static void group_exit(int protected) {
    for (unsigned i = 0; i < 32; i++) {
        int ready[2]; assert(!pipe2(ready, O_CLOEXEC));
        pid_t pid = fork(); assert(pid >= 0);
        if (!pid) {
            close(ready[0]);
            if (protected) assert(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
            pthread_t threads[4];
            for (unsigned n = 0; n < 4; n++) assert(!pthread_create(&threads[n], NULL, exiting_worker, NULL));
            assert(write(ready[1], "R", 1) == 1);
            exiting_worker(NULL); _exit(90);
        }
        close(ready[1]); char byte;
        /* EVENT_WAIT: child confirms concurrent adaptation before group death;
         * the runner deadline fails lost lifecycle events rather than retrying. */
        assert(read(ready[0], &byte, 1) == 1 && byte == 'R'); close(ready[0]);
        assert(!kill(pid, SIGKILL));
        int status;
        assert(waitpid(pid, &status, 0) == pid && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    }
    memory();
    puts("PASS concurrent group death retains exact exit ownership: 32 groups");
}
static void *stopped_worker(void *data) {
    int *pipe = data;
    assert(write(pipe[1], "R", 1) == 1);
    char byte;
    /* EVENT_WAIT: main thread releases the worker after all stop/continue cycles. */
    ssize_t n;
    do { n = read(pipe[0], &byte, 1); } while (n < 0 && errno == EINTR);
    assert(n == 1 && byte == 'F');
    return NULL;
}
static void job_control(void) {
    int progress[2]; assert(!pipe2(progress, O_CLOEXEC | O_NONBLOCK));
    pid_t pid = fork(); assert(pid >= 0);
    if (!pid) {
        close(progress[0]);
        int ready[2], finish[2]; assert(!pipe(ready) && !pipe(finish));
        int worker_pipe[] = {finish[0], ready[1]};
        pthread_t thread;
        assert(!pthread_create(&thread, NULL, stopped_worker, worker_pipe));
        char byte;
        assert(read(ready[0], &byte, 1) == 1 && byte == 'R');
        for (unsigned i = 0; i < 32; i++) {
            assert(!raise(SIGSTOP));
            memory();
            assert(write(progress[1], "C", 1) == 1);
        }
        assert(write(finish[1], "F", 1) == 1 && !pthread_join(thread, NULL));
        _exit(0);
    }
    close(progress[1]);
    for (unsigned i = 0; i < 32; i++) {
        int status;
        /* EVENT_WAIT: the actual parent sees each completed group-stop before
         * permitting further execution. The probe deadline fails missing stops. */
        assert(waitpid(pid, &status, WUNTRACED) == pid && WIFSTOPPED(status)
            && WSTOPSIG(status) == SIGSTOP);
        char byte;
        if (i) assert(read(progress[0], &byte, 1) == 1 && byte == 'C');
        assert(read(progress[0], &byte, 1) == -1 && errno == EAGAIN);
        assert(!kill(pid, SIGCONT));
    }
    int status;
    assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && !WEXITSTATUS(status));
    char byte;
    assert(read(progress[0], &byte, 1) == 1 && byte == 'C');
    assert(read(progress[0], &byte, 1) == 0 && !close(progress[0]));
    pid = fork(); assert(pid >= 0);
    if (!pid) { assert(!raise(SIGSTOP)); _exit(90); }
    assert(waitpid(pid, &status, WUNTRACED) == pid && WIFSTOPPED(status));
    assert(!kill(pid, SIGKILL));
    assert(waitpid(pid, &status, 0) == pid && WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    puts("PASS group-stop preserves both threads and resumes only after SIGCONT: 32 cycles");
    puts("PASS SIGKILL reaps a listening child without SIGCONT");
}
static void children(void) {
    for (unsigned n = 0; n < 64; n++) {
        pid_t pid = fork(); assert(pid >= 0);
        if (!pid) { memory(); _exit(0); }
        int status;
        /* EVENT_WAIT: reap this exact child; the outer probe deadline fails a hang. */
        assert(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && !WEXITSTATUS(status));
        pthread_t workers[4];
        for (unsigned i = 0; i < 4; i++) assert(!pthread_create(&workers[i], NULL, worker, NULL));
        for (unsigned i = 0; i < 4; i++) assert(!pthread_join(workers[i], NULL));
    }
    puts("PASS memory EFAULT and fork/thread birth: 64 children, 256 threads");
}
static void denial(int nr, int external) {
    int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC); assert(fd >= 0);
    struct stat st;
    assert(!fstat(fd, &st));
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(filter) / sizeof(filter[0]), filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
    /* fstat itself is allowed. Its transport must stay outside this policy. */
    if (external) assert(!fstat(fd, &st) && S_ISREG(st.st_mode) && st.st_size > 0);
    else assert(fstat(fd, &st) == -1 && errno == EPERM);
    if (external) {
        /* A new application denial must preempt the external listener. */
        struct sock_filter denied[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fstat, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EKEYREJECTED),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog policy = {sizeof(denied) / sizeof(*denied), denied};
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
        assert(fstat(fd, &st) == -1 && errno == EKEYREJECTED);
        puts("PASS application fstat denial preempts external metadata");
    }
    assert(!close(fd));
}
static void addressless(void) {
    int pair[2];
    assert(!socketpair(AF_UNIX, SOCK_SEQPACKET, 0, pair));
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getsockopt, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EKEYREJECTED),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(filter) / sizeof(*filter), filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
    assert(syscall(SYS_recvmsg, -1, 1, 0) == -1 && errno == EBADF);
    assert(syscall(SYS_recvmsg, pair[1], 1, 0) == -1 && errno == EFAULT);
    char byte = 'A', received = 0;
    struct iovec io = {&received, 1};
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1};
    assert(write(pair[0], &byte, 1) == 1);
    assert(recvmsg(pair[1], &message, 0) == 1 && received == byte);
    assert(write(pair[0], &byte, 1) == 1);
    assert(recvfrom(pair[1], &received, 1, 0, NULL, NULL) == 1 && received == byte);
    int domain = 0; socklen_t length = sizeof(domain);
    assert(getsockopt(pair[1], SOL_SOCKET, SO_DOMAIN, &domain, &length) == -1 && errno == EKEYREJECTED);
    assert(!close(pair[0]) && !close(pair[1]));
    puts("PASS addressless socket IO preserves getsockopt denial");
}
static void self_exec(const char *mode) {
    if (!strcmp(mode, "self-exec")) {
        char identity[4096];
        ssize_t n = readlink("/proc/self/exe", identity, sizeof(identity) - 1);
        assert(n > 0 && n < (ssize_t)sizeof(identity) - 1); identity[n] = 0;
        assert(!setenv("MD_CONTROL_IDENTITY", identity, 1));
        char *args[] = {identity, "self-exec-child", NULL};
        execv("/proc/self/exe", args);
        assert(0);
    }
    char identity[4096];
    ssize_t n = readlink("/proc/self/exe", identity, sizeof(identity) - 1);
    assert(n > 0 && n < (ssize_t)sizeof(identity) - 1); identity[n] = 0;
    assert(getenv("MD_CONTROL_IDENTITY") && !strcmp(identity, getenv("MD_CONTROL_IDENTITY")));
    assert(!strcmp((const char *)getauxval(AT_EXECFN), "/proc/self/exe"));
    puts("PASS proc self exec preserves executable identity");
}
static void protected_metadata(void) {
    int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC); assert(fd >= 0);
    struct stat before, after;
    assert(!fstat(fd, &before));
    assert(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    assert(!fstat(fd, &after));
    assert(before.st_dev == after.st_dev && before.st_ino == after.st_ino
        && before.st_mode == after.st_mode && before.st_nlink == after.st_nlink);
    assert(syscall(SYS_fstat, fd, 1) == -1 && errno == EFAULT);
    assert(!fstat(fd, &after));
    assert(fstat(-1, &after) == -1 && errno == EBADF);
    assert(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    assert(!close(fd));
    puts("PASS protected metadata preserves identity, EFAULT, EBADF and dumpable=0");
}
static void protected_exec(void) {
    assert(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    char *arguments[] = {"/tmp/guest-browser-controls/md-browser-control", "protected-metadata", NULL};
    execv(arguments[0], arguments);
    perror("protected exec");
    abort();
}
static void *thread_exec(void *unused) { (void)unused; protected_exec(); return NULL; }
static void protected_thread_exec(void) {
    pthread_t child;
    assert(!pthread_create(&child, NULL, thread_exec, NULL));
    /* EVENT_WAIT: nonleader exec replaces this thread group; return is failure. */
    assert(!pthread_join(child, NULL));
    abort();
}
static void deny_syscall(int nr, int error) {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nr, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | error),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog policy = {sizeof(filter) / sizeof(*filter), filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
}
static void protected_denial(int zero) {
    int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC); assert(fd >= 0);
    struct stat before, after;
    memset(&before, 0xa5, sizeof(before)); after = before;
    assert(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    if (!zero) {
        deny_syscall(SYS_sendmsg, EKEYREJECTED);
        assert(fstat(fd, &after) == -1 && errno == EKEYREJECTED);
        assert(!memcmp(&before, &after, sizeof(before)));
    }
    deny_syscall(SYS_fstat, zero ? 0 : EACCES);
    int result = fstat(fd, &after);
    assert(zero ? result == 0 : result == -1 && errno == EACCES);
    assert(!memcmp(&before, &after, sizeof(before)));
    assert(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    assert(!close(fd));
    puts("PASS protected policy denial preserves output and dumpable=0");
}
struct retained_thread { int directory, descriptor, ready, finish; };
static void *inspect_retained_thread(void *data) {
    struct retained_thread *test = data;
    char number[32]; snprintf(number, sizeof(number), "%d", test->descriptor);
    struct stat st;
    assert(!fstatat(test->directory, number, &st, 0) && S_ISREG(st.st_mode));
    assert(write(test->ready, "R", 1) == 1);
    char byte;
    /* EVENT_WAIT: main thread validates the real proc thread count before exit. */
    assert(read(test->finish, &byte, 1) == 1 && byte == 'F');
    return NULL;
}
static void helper_client(int retained) {
    assert(getenv("SBX_D") && getenv("SBX_HELPER_PID") && getenv("SBX_CHROME_API_PRV"));
    int channel = atoi(getenv("SBX_D"));
    pid_t helper = atoi(getenv("SBX_HELPER_PID"));
    assert(channel >= 0 && helper > 0 && !getenv("SBX_PID_NS") && !getenv("SBX_NET_NS"));
    int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC); assert(fd >= 0);
    int proc = retained ? open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC) : -1;
    if (retained) assert(proc >= 0);
    assert(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    assert(write(channel, "C", 1) == 1);
    int status;
    /* EVENT_WAIT: exact stock helper completion; the outer process deadline fails hangs. */
    assert(waitpid(helper, &status, 0) == helper && WIFEXITED(status) && !WEXITSTATUS(status));
    char byte; assert(read(channel, &byte, 1) == 1 && byte == 'O');
    assert(!close(channel));
    assert(open("/etc/passwd", O_RDONLY) == -1);
    assert(syscall(SYS_openat, AT_FDCWD, "/proc/self/exe", O_RDONLY, 0) == -1);
    assert(syscall(SYS_openat, AT_FDCWD, "/data/local/tmp", O_RDONLY | O_DIRECTORY, 0) == -1);
    assert(syscall(SYS_openat2, AT_FDCWD, "/proc/self/root", NULL, 0) == -1);
    assert(socket(AF_INET, SOCK_STREAM, 0) == -1);
    assert(syscall(SYS_ptrace, PTRACE_TRACEME, 0, 0, 0) == -1 && errno == EPERM);
    assert(syscall(SYS_process_vm_readv, getppid(), NULL, 0, NULL, 0, 0) == -1 && errno == EPERM);
    assert(syscall(SYS_pidfd_getfd, -1, 0, 0) == -1 && errno == EPERM);
    assert(syscall(SYS_kill, 1, 0) == -1 && errno == EPERM);
    puts("PASS restricted process access, networking and host path denials");
    struct stat st; assert(!fstat(fd, &st) && S_ISREG(st.st_mode));
    if (retained) {
        int copy = openat(proc, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        assert(copy >= 0 && (fcntl(copy, F_GETFD) & FD_CLOEXEC));
        assert(!fstatat(copy, "self/task/", &st, 0) && st.st_nlink == 3);
        int descriptors = openat(copy, "self/fd/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        assert(descriptors >= 0);
        char number[32]; snprintf(number, sizeof(number), "%d", fd);
        assert(!fstatat(descriptors, number, &st, 0) && S_ISREG(st.st_mode));
        snprintf(number, sizeof(number), "%d", proc);
        assert(!fstatat(descriptors, number, &st, 0) && S_ISDIR(st.st_mode));
        assert(syscall(SYS_openat, copy, 1, O_RDONLY, 0) == -1 && errno == EFAULT);
        assert(syscall(SYS_newfstatat, copy, "self/task/", 1, 0) == -1 && errno == EFAULT);
        assert(openat(copy, "/data/local/tmp", O_RDONLY | O_DIRECTORY) == -1 && errno == EACCES);
        assert(openat(copy, "../data/local/tmp", O_RDONLY | O_DIRECTORY) == -1 && errno == EXDEV);
        assert(openat(copy, "self/root", O_RDONLY | O_DIRECTORY) == -1);
        assert(openat(copy, "self/task/", O_WRONLY | O_CREAT, 0600) == -1 && errno == ENOTSUP);
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            assert(!fstatat(copy, "self/task/", &st, 0) && st.st_nlink == 3);
            int child_fds = openat(copy, "self/fd/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
            assert(child_fds >= 0); close(child_fds); _exit(0);
        }
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
        int ready[2], finish[2];
        assert(!pipe(ready) && !pipe(finish));
        struct retained_thread test = {descriptors, fd, ready[1], finish[0]};
        pthread_t worker;
        assert(!pthread_create(&worker, NULL, inspect_retained_thread, &test));
        /* EVENT_WAIT: owned worker remains alive until the explicit response. */
        assert(read(ready[0], &byte, 1) == 1 && byte == 'R');
        assert(!fstatat(copy, "self/task/", &st, 0) && st.st_nlink == 4);
        assert(write(finish[1], "F", 1) == 1 && !pthread_join(worker, NULL));
        for (int i = 0; i < 2; i++) { assert(!close(ready[i])); assert(!close(finish[i])); }
        assert(!close(copy));
        assert(openat(copy, ".", O_RDONLY | O_DIRECTORY) == -1 && errno == EBADF);
        deny_syscall(SYS_openat, EKEYREJECTED);
        assert(openat(proc, ".", O_RDONLY | O_DIRECTORY) == -1 && errno == EKEYREJECTED);
        struct stat before;
        memset(&before, 0xa5, sizeof(before)); st = before;
        deny_syscall(SYS_newfstatat, EACCES);
        assert(fstatat(descriptors, number, &st, 0) == -1 && errno == EACCES);
        assert(!memcmp(&st, &before, sizeof(st)));
        assert(!close(descriptors) && !close(proc));
        puts("PASS retained proc capabilities, real types, fork, EFAULT, escape and application denial");
    }
    assert(!close(fd));
    assert(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    puts("PASS Debian helper shared root, protected guest ELF and retained metadata");
}
static void identity_ids(uid_t real, uid_t effective, uid_t saved) {
    uid_t r, e, s;
    assert(!getresuid(&r, &e, &s) && r == real && e == effective && s == saved);
    assert(getuid() == real && geteuid() == effective);
    assert(syscall(SYS_getresuid, 1, &e, &s) == -1 && errno == EFAULT);
}
static void identity_wait(pid_t child) {
    int status;
    /* EVENT_WAIT: this exact credential-test child must complete; the outer
     * runner cancels the owned tree if the result never arrives. */
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void identity_exec(const char *self, const char *mode) {
    execl(self, self, mode, (char *)NULL);
    assert(!"credential fixture exec failed");
}
static void identity_control(const char *self, const char *mode) {
    if (!strcmp(mode, "identity-nnp")) {
        identity_ids(2000, 2000, 2000);
        assert(!getauxval(AT_SECURE) && getauxval(AT_EUID) == 2000);
        assert(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
        assert(prctl(PR_SET_NO_NEW_PRIVS, 0, 0, 0, 0) == -1 && errno == EINVAL);
        assert(setresuid(-1, 0, -1) == -1 && errno == EPERM);
        puts("PASS guest no_new_privs suppresses admitted set-ID exec");
        return;
    }
    if (!strcmp(mode, "identity-admitted")) {
        identity_ids(2000, 0, 0);
        assert(getauxval(AT_SECURE) == 1 && getauxval(AT_UID) == 2000 && !getauxval(AT_EUID));
        assert(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
        assert(!getenv("LD_LIBRARY_PATH"));
        unsigned long pair[2];
        int aux = open("/proc/self/auxv", O_RDONLY | O_CLOEXEC); assert(aux >= 0);
        int secure = 0, effective = 0;
        while (read(aux, pair, sizeof(pair)) == sizeof(pair) && pair[0]) {
            if (pair[0] == AT_SECURE) { assert(pair[1] == 1); secure++; }
            if (pair[0] == AT_EUID) { assert(!pair[1]); effective++; }
        }
        assert(secure == 1 && effective == 1 && !close(aux));
        execl("/md-no-such-admitted-program", "absent", (char *)NULL);
        assert(errno == ENOENT); identity_ids(2000, 0, 0);
        assert(chroot((const char *)(uintptr_t)1) == -1 && errno == EFAULT);
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            identity_ids(2000, 0, 0);
            assert(!setresgid(1234, 1234, 1234));
            assert(!setresuid(1234, 1234, 1234));
            identity_ids(1234, 1234, 1234);
            assert(setresuid(0, 0, 0) == -1 && errno == EPERM);
            assert(setresgid(0, 0, 0) == -1 && errno == EPERM);
            assert(chroot("/proc/self/fdinfo") == -1 && errno == EPERM);
            _exit(0);
        }
        identity_wait(child); identity_ids(2000, 0, 0);
        child = fork(); assert(child >= 0);
        if (!child) {
            deny_syscall(SYS_setresuid, EKEYREJECTED);
            assert(setresuid(2000, 2000, 2000) == -1 && errno == EKEYREJECTED);
            identity_ids(2000, 0, 0); _exit(0);
        }
        identity_wait(child);
        assert(!setresuid(2000, 2000, 2000)); identity_ids(2000, 2000, 2000);
        assert(setresuid(-1, 0, -1) == -1 && errno == EPERM);
        assert(chroot("/proc/self/fdinfo") == -1 && errno == EPERM);
        puts("PASS admitted ELF auxv, secure loader, failed exec, fork, permanent drop and kernel denial");
        return;
    }
    assert(!strcmp(mode, "identity-parent"));
    identity_ids(2000, 0, 0);
    assert(getauxval(AT_SECURE) == 1 && !getauxval(AT_EUID));
    assert(!setresuid(2000, 2000, 2000));
    identity_ids(2000, 2000, 2000);
    assert(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 0);
    assert(setresuid(-1, 0, -1) == -1 && errno == EPERM);
    assert(chroot("/proc/self/fdinfo") == -1 && errno == EPERM);
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
        identity_exec(self, "identity-nnp");
    }
    identity_wait(child);
    child = fork(); assert(child >= 0);
    if (!child) {
        assert(!setenv("LD_LIBRARY_PATH", "/md-untrusted-library-path", 1));
        identity_exec(self, "identity-admitted");
    }
    identity_wait(child); identity_ids(2000, 2000, 2000);
    assert(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 0);
    puts("PASS integrated guest identity controls");
}
static void catalogue(void) {
    const char *path = "/tmp/md-chrome-helper-ordinary";
    struct stat by_path, by_fd;
    assert(!stat(path, &by_path) && by_path.st_uid == 0 && by_path.st_gid == 0
        && by_path.st_mode == (S_IFREG | 04755));
    int a = open(path, O_RDONLY | O_CLOEXEC), b = open(path, O_RDONLY | O_CLOEXEC);
    assert(a >= 0 && b >= 0 && !fstat(a, &by_fd));
    assert(by_fd.st_dev == by_path.st_dev && by_fd.st_ino == by_path.st_ino
        && by_fd.st_mode == by_path.st_mode && by_fd.st_uid == by_path.st_uid
        && by_fd.st_gid == by_path.st_gid && by_fd.st_size == by_path.st_size
        && by_fd.st_mtim.tv_sec == by_path.st_mtim.tv_sec
        && by_fd.st_mtim.tv_nsec == by_path.st_mtim.tv_nsec);
    char first[16], second[16];
    assert(read(a, first, sizeof(first)) == sizeof(first) && lseek(b, 0, SEEK_CUR) == 0);
    assert(read(b, second, sizeof(second)) == sizeof(second) && !memcmp(first, second, sizeof(first)));
    assert(!memcmp(first, "\177ELF", 4));
    assert(pwrite(a, "X", 1, 0) == -1 && errno == EPERM);
    assert(ftruncate(a, 0) == -1 && errno == EPERM);
    assert(mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, a, 0) == MAP_FAILED && errno == EPERM);
    assert(open(path, O_WRONLY | O_TRUNC) == -1 && errno == EACCES);
    int opaque = open(path, O_PATH | O_CLOEXEC);
    assert(opaque >= 0 && (fcntl(opaque, F_GETFL) & O_PATH));
    assert(read(opaque, first, 1) == -1 && errno == EBADF);
    assert(!close(opaque));
    char alias[128]; snprintf(alias, sizeof(alias), "/tmp/md-admitted-alias-%d", getpid());
    assert(!link(path, alias) && !stat(alias, &by_fd));
    assert(by_fd.st_ino == by_path.st_ino && by_fd.st_mode == by_path.st_mode
        && by_fd.st_nlink == by_path.st_nlink + 1);
    assert(open(alias, O_WRONLY | O_TRUNC) == -1 && errno == EACCES);
    assert(!unlink(alias));
    int replacement = open(alias, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0755);
    assert(replacement >= 0 && write(replacement, "replacement", 11) == 11 && !close(replacement));
    assert(!stat(alias, &by_fd) && by_fd.st_uid == 2000 && !(by_fd.st_mode & S_ISUID)
        && by_fd.st_ino != by_path.st_ino && !unlink(alias));
    assert(!fstat(a, &by_fd) && by_fd.st_size == by_path.st_size && by_fd.st_nlink == by_path.st_nlink);
    assert(!close(a) && !close(b));
    puts("PASS immutable catalogue metadata, independent opens, seals, hardlinks and replacement");
}
static void exec_offset(const char *self, int child) {
    if (child) {
        int fd = atoi(getenv("MD_EXEC_OFFSET_FD"));
        assert(fd >= 3 && lseek(fd, 0, SEEK_CUR) == 123 && !close(fd));
        puts("PASS descriptor exec preserves caller file position");
        return;
    }
    int fd = open(self, O_RDONLY); assert(fd >= 3 && lseek(fd, 123, SEEK_SET) == 123);
    char number[32]; snprintf(number, sizeof(number), "%d", fd);
    assert(!setenv("MD_EXEC_OFFSET_FD", number, 1));
    char *args[] = {(char *)self, "exec-offset-child", NULL};
    extern char **environ;
    syscall(SYS_execveat, fd, "", args, environ, AT_EMPTY_PATH);
    assert(0);
}
static void transport_signal(int signal, siginfo_t *info, void *context) {
    assert(signal == SIGSYS && (info->si_syscall == SYS_sendmsg || info->si_syscall == SYS_recvmsg));
    ((ucontext_t *)context)->uc_mcontext.regs[0] = (unsigned long)-EKEYREJECTED;
}
static void transport_policy(void) {
    unsigned actions[] = {SECCOMP_RET_ERRNO | EKEYREJECTED, SECCOMP_RET_TRAP, SECCOMP_RET_KILL_PROCESS};
    int calls[] = {SYS_sendmsg, SYS_recvmsg};
    for (unsigned n = 0; n < 2; n++) for (unsigned a = 0; a < 3; a++) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            struct sigaction handler = {.sa_sigaction = transport_signal, .sa_flags = SA_SIGINFO};
            sigemptyset(&handler.sa_mask); assert(!sigaction(SIGSYS, &handler, NULL));
            struct sock_filter filter[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, calls[n], 0, 1),
                BPF_STMT(BPF_RET | BPF_K, actions[a]),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            };
            struct sock_fprog policy = {sizeof(filter) / sizeof(*filter), filter};
            assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
            assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
            /* Brokered reads do not call sendmsg/recvmsg in this task. O_PATH
             * needs SCM_RIGHTS: its internal transport must retain the filter. */
            int fd = open("/etc/passwd", O_RDONLY); assert(fd >= 0); close(fd);
            assert(open("/etc/passwd", O_PATH) == -1 && errno == EKEYREJECTED);
            _exit(0);
        }
        int status;
        /* EVENT_WAIT: exact policy child, bounded by the runner's tree deadline. */
        assert(waitpid(child, &status, 0) == child);
        if (actions[a] == SECCOMP_RET_KILL_PROCESS) assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS);
        else assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    memory();
    puts("PASS adapter transport retains application ERRNO/TRAP/KILL filters");
}
static void kernel_signal(int signal, siginfo_t *info, void *context) {
    assert(signal == SIGSYS && info->si_syscall == SYS_fcntl);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = (unsigned long)-EKEYREJECTED;
}
static void kernel_policy(void) {
    unsigned actions[] = {SECCOMP_RET_ERRNO | EKEYREJECTED, SECCOMP_RET_TRAP, SECCOMP_RET_KILL_PROCESS};
    for (unsigned a = 0; a < 3; a++) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            struct sigaction handler = {.sa_sigaction = kernel_signal, .sa_flags = SA_SIGINFO};
            sigemptyset(&handler.sa_mask); assert(!sigaction(SIGSYS, &handler, NULL));
            struct sock_filter filter[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_fcntl, 0, 1),
                BPF_STMT(BPF_RET | BPF_K, actions[a]),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            };
            struct sock_fprog policy = {sizeof(filter) / sizeof(*filter), filter};
            assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
            assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
            assert(syscall(SYS_fcntl, -1, F_GETFD, 0) == -1 && errno == EKEYREJECTED);
            _exit(0);
        }
        int status;
        /* EVENT_WAIT: exact filtered child; the runner bounds a stuck process. */
        assert(waitpid(child, &status, 0) == child);
        if (actions[a] == SECCOMP_RET_KILL_PROCESS) assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS);
        else assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC); assert(fd >= 0);
    assert(!syscall(SYS_chroot, "/proc/self/fdinfo"));
    assert(syscall(SYS_fcntl, fd, F_GETFD, 0) == FD_CLOEXEC);
    assert(!syscall(SYS_fcntl, fd, F_SETFD, 0));
    int copy = (int)syscall(SYS_fcntl, fd, F_DUPFD_CLOEXEC, 3); assert(copy >= 3);
    assert(!close(copy) && !close(fd));
    struct rlimit limit; assert(!syscall(SYS_prlimit64, 0, RLIMIT_NOFILE, NULL, &limit));
    struct utsname name; assert(!uname(&name));
    char label[16];
    assert(!prctl(PR_SET_NAME, "kernel-policy"));
    assert(!prctl(PR_GET_NAME, label) && !strcmp(label, "kernel-policy"));
    assert(syscall(SYS_fcntl, -1, (1UL << 32) | F_GETFD, 0) == -1 && errno == EPERM);
    assert(syscall(SYS_prctl, (1UL << 32) | PR_GET_NAME, label) == -1 && errno == EPERM);
    assert(syscall(SYS_prlimit64, 1UL << 32, RLIMIT_NOFILE, NULL, &limit) == -1 && errno == EPERM);
    assert(syscall(SYS_fcntl, -1, F_GETOWN, 0) == -1 && errno == EPERM);
    assert(prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) == 0 && prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    puts("PASS native kernel policy retains full-width arguments, domain restrictions and application filters");
}
static unsigned open_descriptors(void) {
    DIR *directory = opendir("/proc/self/fd"); assert(directory);
    unsigned count = 0; struct dirent *entry;
    while ((entry = readdir(directory))) if (entry->d_name[0] != '.') count++;
    assert(!closedir(directory));
    return count;
}
static void prepared_exec_failure(void) {
    int marker = open("/dev/null", O_RDONLY | O_CLOEXEC); assert(marker >= 0);
    assert(!close(marker));
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_execve, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EKEYREJECTED),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog policy = {sizeof(filter) / sizeof(*filter), filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
    char *args[] = {"true", NULL}; extern char **environ;
    unsigned descriptors = open_descriptors();
    for (unsigned i = 0; i < 64; i++) {
        /* Original execveat is admitted; bootstrap execve fails after both ELF
         * descriptors have been prepared and made inheritable. */
        assert(syscall(SYS_execveat, AT_FDCWD, "/bin/true", args, environ, 0) == -1 && errno == EKEYREJECTED);
        int fd = open("/dev/null", O_RDONLY | O_CLOEXEC); assert(fd == marker);
        assert(!close(fd) && open_descriptors() == descriptors);
    }
    filter[1].k = SYS_fstat; filter[2].k = SECCOMP_RET_ERRNO | EIO;
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
    assert(syscall(SYS_execveat, AT_FDCWD, "/bin/true", args, environ, 0) == -1 && errno == EIO);
    puts("PASS failed kernel exec releases both prepared image descriptors");
}
static void seek_signal(int signal, siginfo_t *info, void *context) {
    assert(signal == SIGSYS && info->si_syscall == SYS_lseek && info->si_errno == 73);
    ((ucontext_t *)context)->uc_mcontext.regs[0] = 711;
}
static void seek_policy(int fd, unsigned action) {
    assert(lseek(fd, 37, SEEK_SET) == 37);
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        struct sigaction handler = {.sa_sigaction = seek_signal, .sa_flags = SA_SIGINFO};
        sigemptyset(&handler.sa_mask); assert(!sigaction(SIGSYS, &handler, NULL));
        struct sock_filter filter[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_lseek, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, action),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog policy = {sizeof(filter) / sizeof(*filter), filter};
        assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &policy));
        off_t result = lseek(fd, 1000, SEEK_SET);
        if ((action & SECCOMP_RET_ACTION_FULL) == SECCOMP_RET_ERRNO)
            assert((action & SECCOMP_RET_DATA) ? result == -1 && errno == EKEYREJECTED : result == 0);
        else assert((action & SECCOMP_RET_ACTION_FULL) == SECCOMP_RET_TRAP && result == 711);
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: this exact filtered child exits; the runner bounds a stuck tracee. */
    assert(waitpid(child, &status, 0) == child);
    if (action == SECCOMP_RET_KILL_PROCESS) assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS);
    else assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(lseek(fd, 0, SEEK_CUR) == 37);
}
static void *seek_worker(void *data) {
    int fd = *(int *)data;
    for (unsigned i = 0; i < 128; i++) assert(lseek(fd, 1, SEEK_CUR) >= 0);
    return NULL;
}
static void seek_descriptors(int protected) {
    char path[128]; snprintf(path, sizeof(path), "/tmp/md-seek-%d", getpid());
    int fd = open(path, O_CREAT | O_EXCL | O_RDWR, 0600); assert(fd >= 0);
    int opaque = open(path, O_PATH); assert(opaque >= 0 && !unlink(path));
    if (protected) assert(!prctl(PR_SET_DUMPABLE, 0, 0, 0, 0));
    off_t size = (1ULL << 33) + 64;
    assert(!ftruncate(fd, size) && lseek(fd, -64, SEEK_END) == size - 64);
    assert(lseek(fd, -1, SEEK_SET) == -1 && errno == EINVAL);
    assert(lseek(fd, 0, 99) == -1 && errno == EINVAL);
    assert(lseek(-1, 0, SEEK_CUR) == -1 && errno == EBADF);
    assert(lseek(opaque, 0, SEEK_CUR) == -1 && errno == EBADF);
    int held = dup(fd); assert(held >= 0 && lseek(held, 19, SEEK_SET) == 19);
    pid_t child = fork(); assert(child >= 0);
    if (!child) { assert(lseek(fd, 1, SEEK_CUR) == 20); _exit(0); }
    int status;
    /* EVENT_WAIT: shared file-position update by the child, bounded by the runner. */
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(lseek(held, 0, SEEK_CUR) == 20);
    pthread_t threads[4];
    for (unsigned i = 0; i < 4; i++) assert(!pthread_create(&threads[i], NULL, seek_worker, &held));
    /* EVENT_WAIT: worker completion; no sampling or fixed settling delay. */
    for (unsigned i = 0; i < 4; i++) assert(!pthread_join(threads[i], NULL));
    assert(lseek(fd, 0, SEEK_CUR) == 20 + 4 * 128);
    int dir = open("/", O_RDONLY | O_DIRECTORY); assert(dir >= 0);
    assert(dup3(dir, fd, 0) == fd && lseek(fd, 0, SEEK_SET) == 0);
    char data[512]; assert(syscall(SYS_getdents64, fd, data, sizeof(data)) > 0);
    assert(lseek(fd, 0, SEEK_CUR) > 0 && lseek(fd, 0, SEEK_SET) == 0);
    assert(dup3(opaque, fd, 0) == fd && lseek(fd, 0, SEEK_SET) == -1 && errno == EBADF);
    int pipefd[2]; assert(!pipe(pipefd));
    assert(dup3(pipefd[0], fd, 0) == fd && lseek(fd, 0, SEEK_CUR) == -1 && errno == ESPIPE);
    assert(dup3(held, fd, 0) == fd && lseek(fd, 0, SEEK_CUR) == 20 + 4 * 128);
    if (!protected) {
        seek_policy(fd, SECCOMP_RET_ERRNO | EKEYREJECTED);
        seek_policy(fd, SECCOMP_RET_ERRNO);
        seek_policy(fd, SECCOMP_RET_TRAP | 73);
        seek_policy(fd, SECCOMP_RET_KILL_PROCESS);
    } else assert(prctl(PR_GET_DUMPABLE, 0, 0, 0, 0) == 0);
    assert(!close(dir) && !close(opaque) && !close(pipefd[0]) && !close(pipefd[1]));
    assert(!close(fd) && !close(held));
    puts("PASS seek: offsets, dup/fork/threads, unlinked files, FD reuse, directories and policy");
}
int main(int argc, char **argv) {
    assert(argc == 2);
    setvbuf(stdout, NULL, _IONBF, 0);
    if (!strcmp(argv[1], "memory")) { memory(); puts("PASS memory copy input/output EFAULT"); }
    else if (!strcmp(argv[1], "children")) children();
    else if (!strcmp(argv[1], "group-exit")) group_exit(0);
    else if (!strcmp(argv[1], "group-exit-protected")) group_exit(1);
    else if (!strcmp(argv[1], "job-control")) job_control();
    else if (!strcmp(argv[1], "addressless")) addressless();
    else if (!strcmp(argv[1], "protected-metadata")) protected_metadata();
    else if (!strcmp(argv[1], "protected-exec")) protected_exec();
    else if (!strcmp(argv[1], "protected-thread-exec")) protected_thread_exec();
    else if (!strcmp(argv[1], "protected-denial")) protected_denial(0);
    else if (!strcmp(argv[1], "protected-denial-zero")) protected_denial(1);
    else if (!strcmp(argv[1], "helper-client")) helper_client(0);
    else if (!strcmp(argv[1], "helper-retained")) helper_client(1);
    else if (!strcmp(argv[1], "catalogue")) catalogue();
    else if (!strcmp(argv[1], "seek")) seek_descriptors(0);
    else if (!strcmp(argv[1], "seek-protected")) seek_descriptors(1);
    else if (!strcmp(argv[1], "transport-policy")) transport_policy();
    else if (!strcmp(argv[1], "kernel-policy")) kernel_policy();
    else if (!strcmp(argv[1], "prepared-exec-failure")) prepared_exec_failure();
    else if (!strcmp(argv[1], "broker-files")) broker_files();
    else if (!strcmp(argv[1], "broker-directories")) broker_directories();
    else if (!strcmp(argv[1], "broker-shared-domain")) broker_shared_domain();
    else if (!strcmp(argv[1], "exec-offset") || !strcmp(argv[1], "exec-offset-child"))
        exec_offset(argv[0], !strcmp(argv[1], "exec-offset-child"));
    else if (!strncmp(argv[1], "identity-", 9)) identity_control(argv[0], argv[1]);
    else if (!strcmp(argv[1], "self-exec") || !strcmp(argv[1], "self-exec-child")) self_exec(argv[1]);
    else if (!strcmp(argv[1], "external-socket") || !strcmp(argv[1], "external-connect")) {
        denial(!strcmp(argv[1], "external-socket") ? SYS_socket : SYS_connect, 1);
        puts("PASS external fstat survives denied in-process transport");
    }
    else {
        assert(!strcmp(argv[1], "socket") || !strcmp(argv[1], "connect"));
        denial(!strcmp(argv[1], "socket") ? SYS_socket : SYS_connect, 0);
        puts("CONFIRMED allowed fstat fails when the internal transport is denied");
    }
    return 0;
}
