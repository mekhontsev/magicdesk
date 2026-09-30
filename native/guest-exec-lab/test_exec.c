#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static int filters(void) {
    FILE *f = fopen("/proc/self/status", "r");
    assert(f);
    char line[256]; int count = -1;
    while (fgets(line, sizeof(line), f)) if (sscanf(line, "Seccomp_filters: %d", &count) == 1) break;
    fclose(f);
    assert(count > 0);
    return count;
}
static int mappings(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    assert(f);
    int count = 0, c;
    while ((c = fgetc(f)) != EOF) if (c == '\n') ++count;
    assert(!ferror(f));
    fclose(f);
    return count;
}
static void wait_child(pid_t pid, int code) {
    int status;
    // EVENT_WAIT: exact child exit; the runner kills the command on timeout.
    assert(waitpid(pid, &status, 0) == pid);
    if (!WIFEXITED(status) || WEXITSTATUS(status) != code)
        fprintf(stderr, "child=%d status=%#x expected=%d\n", pid, status, code);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == code);
}
static void *small_stack(void *ignored) {
    (void)ignored;
    for (int i = 0; i < 20; ++i) {
        int fd = open("/etc/md-guest-fixture", O_RDONLY);
        assert(fd >= 0);
        close(fd);
    }
    return NULL;
}
static pthread_t signal_target;
static volatile sig_atomic_t signal_reads, signal_failure;
static void file_signal(int value) {
    (void)value;
    int saved = errno;
    int fd = open("/etc/md-guest-fixture", O_RDONLY);
    if (fd < 0) signal_failure = 1;
    else { close(fd); ++signal_reads; }
    errno = saved;
}
static void *send_signals(void *ignored) {
    (void)ignored;
    for (int i = 0; i < 2000; ++i) {
        assert(!pthread_kill(signal_target, SIGUSR2));
        sched_yield();
    }
    return NULL;
}
int main(int argc, char **argv) {
    assert(syscall(SYS_clone3, NULL, 0) == -1 && errno == ENOSYS);
    setvbuf(stdout, NULL, _IONBF, 0);
    char comm[16] = {0};
    assert(!prctl(PR_GET_NAME, comm));
    assert(!strcmp(comm, "md-exec-fixture"));
    if (argc == 5 && !strcmp(argv[1], "chain")) {
        int remaining = atoi(argv[2]), count = atoi(argv[3]), pid = atoi(argv[4]);
        assert(filters() == count && getpid() == pid);
        assert(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
        assert(!strcmp((const char *)getauxval(AT_EXECFN), "/usr/bin/md-exec-fixture"));
        char path[4096];
        ssize_t size = readlink("/proc/self/exe", path, sizeof(path) - 1);
        assert(size > 0); path[size] = 0;
        assert(!strcmp(path, "/usr/bin/md-exec-fixture"));
        int executable = open("/proc/self/exe", O_RDONLY);
        assert(executable >= 0); close(executable);
        if (!remaining) { puts("PASS 64 execs: same PID, one unchanged filter stack, no-new-privileges"); return 0; }
        char next[32]; snprintf(next, sizeof(next), "%d", remaining - 1);
        char *args[] = {argv[0], "chain", next, argv[3], argv[4], NULL};
        execve("/usr/bin/md-exec-fixture", args, environ);
        abort();
    }

    size_t page = (size_t)sysconf(_SC_PAGESIZE);
    char *memory = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(memory != MAP_FAILED && !mprotect(memory + page, page, PROT_NONE));
    const char path[] = "/etc/md-guest-fixture";
    memcpy(memory + page - sizeof(path), path, sizeof(path));
    int fd = (int)syscall(SYS_openat, AT_FDCWD, memory + page - sizeof(path), O_RDONLY, 0);
    assert(fd >= 0); close(fd);
    assert(syscall(SYS_openat, AT_FDCWD, memory + page, O_RDONLY, 0) == -1 && errno == EFAULT);
    assert(syscall(SYS_getcwd, memory + page, page) == -1 && errno == EFAULT);
    assert(!munmap(memory, page * 2));
    puts("PASS bad pointers return EFAULT; page-boundary NUL is readable");
    assert(!faccessat(AT_FDCWD, "/etc/md-guest-fixture", R_OK, AT_EACCESS));
    assert(!syscall(SYS_faccessat2, AT_FDCWD, "/etc/md-guest-fixture", R_OK, 0x200));
    puts("PASS effective-identity access uses Linux flags, not build-libc aliases");

    char *bad_args[] = {"missing", NULL};
    assert(execve("/not-installed", bad_args, environ) == -1 && errno == ENOENT);
    pid_t pid;
    assert(posix_spawn(&pid, "/not-installed", NULL, NULL, bad_args, environ) == ENOENT);
    // Failed preparation must retain neither descriptors nor mappings.
    int before = mappings();
    for (int i = 0; i < 64; ++i)
        assert(execve("/not-installed", bad_args, environ) == -1 && errno == ENOENT);
    assert(mappings() == before);
    assert(syscall(SYS_execveat, -1, "", bad_args, environ, 0) == -1 && errno == ENOENT);
    assert(syscall(SYS_openat2, AT_FDCWD, "/etc/md-guest-fixture", NULL, 0) == -1 && errno == EINVAL);
    struct sigaction reserved = {.sa_handler = SIG_DFL};
    assert(sigaction(SIGSYS, &reserved, NULL) == -1 && errno == ENOTSUP);
    puts("PASS failed exec/spawn preserve caller/errno/mappings; unsupported entry points are explicit");

    before = mappings();
    for (int i = 0; i < 8; ++i) {
        pid = vfork();
        assert(pid >= 0);
        if (!pid) {
            execve("/not-installed", bad_args, environ);
            _exit(errno == ENOENT ? 37 : 38);
        }
        wait_child(pid, 37);
        assert(mappings() == before);
    }
    char *true_args[] = {"true", NULL};
    pid = vfork();
    assert(pid >= 0);
    if (!pid) { execve("/bin/true", true_args, environ); _exit(39); }
    wait_child(pid, 0);
    assert(mappings() == before);
    puts("PASS vfork with inherited guest stack: failed exec, exit and successful exec");

    pthread_attr_t attr;
    assert(!pthread_attr_init(&attr));
    long minimum_stack = sysconf(_SC_THREAD_STACK_MIN);
    assert(minimum_stack > 0);
    assert(!pthread_attr_setstacksize(&attr, (size_t)minimum_stack));
    pthread_t thread;
    assert(!pthread_create(&thread, &attr, small_stack, NULL));
    // EVENT_WAIT: worker completion, bounded by the runner's command deadline.
    assert(!pthread_join(thread, NULL));
    assert(!pthread_attr_destroy(&attr));
    printf("PASS file interception on libc's minimum thread stack (%ld bytes)\n", minimum_stack);

    assert(!pthread_attr_init(&attr));
    assert(!pthread_attr_setstacksize(&attr, (size_t)minimum_stack));
    before = mappings();
    for (int i = 0; i < 32; ++i) {
        assert(!pthread_create(&thread, &attr, small_stack, NULL));
        // EVENT_WAIT: exact thread termination; the runner's deadline fails a hang.
        assert(!pthread_join(thread, NULL));
        assert(mappings() == before);
        assert(posix_spawn(&pid, "/not-installed", NULL, NULL, bad_args, environ) == ENOENT);
        assert(mappings() == before);
    }
    assert(!pthread_attr_destroy(&attr));
    puts("PASS thread and failed-spawn syscall stacks have bounded lifetimes");

    stack_t alternate = {0};
    assert(!sigaltstack(NULL, &alternate) && (alternate.ss_flags & SS_DISABLE));
    alternate = (stack_t){.ss_sp = memory, .ss_size = 32768};
    assert(sigaltstack(&alternate, NULL) == -1 && errno == ENOTSUP);
    puts("LIMIT application-owned signal stacks are explicitly unsupported");

    struct sigaction action = {.sa_handler = file_signal, .sa_flags = SA_RESTART};
    assert(!sigemptyset(&action.sa_mask) && !sigaction(SIGUSR2, &action, NULL));
    signal_target = pthread_self();
    assert(!pthread_create(&thread, NULL, send_signals, NULL));
    for (int i = 0; i < 1000; ++i) {
        fd = open("/etc/md-guest-fixture", O_RDONLY);
        assert(fd >= 0); close(fd);
    }
    // EVENT_WAIT: finite signal sender exits; enclosing command timeout fails a hang.
    assert(!pthread_join(thread, NULL));
    assert(signal_reads > 0 && !signal_failure);
    printf("PASS reentrant file calls from guest signal handlers (%d deliveries)\n", signal_reads);

    posix_spawn_file_actions_t actions;
    posix_spawnattr_t spawn_attr;
    assert(!posix_spawn_file_actions_init(&actions));
    assert(!posix_spawn_file_actions_addopen(&actions, 1, "/tmp/md-spawn-output", O_CREAT | O_TRUNC | O_WRONLY, 0600));
    assert(!posix_spawn_file_actions_addchdir_np(&actions, "/etc"));
    assert(!posix_spawnattr_init(&spawn_attr));
    sigset_t mask;
    assert(!sigfillset(&mask));
    assert(!posix_spawnattr_setsigmask(&spawn_attr, &mask));
    assert(!posix_spawnattr_setflags(&spawn_attr, POSIX_SPAWN_SETSIGMASK));
    char *args[] = {"cat", "md-guest-fixture", NULL};
    assert(!posix_spawn(&pid, "/bin/cat", &actions, &spawn_attr, args, environ));
    wait_child(pid, 0);
    assert(!posix_spawn_file_actions_destroy(&actions));
    assert(!posix_spawnattr_destroy(&spawn_attr));
    char output[32] = {0}; fd = open("/tmp/md-spawn-output", O_RDONLY);
    assert(fd >= 0 && read(fd, output, sizeof(output)) == 12 && !strcmp(output, "guest-value\n"));
    close(fd); assert(!unlink("/tmp/md-spawn-output"));
    puts("PASS posix_spawn file actions, cwd and full signal mask");

    char count[32], identity[32];
    snprintf(count, sizeof(count), "%d", filters());
    snprintf(identity, sizeof(identity), "%d", getpid());
    char *chain[] = {argv[0], "chain", "64", count, identity, NULL};
    execve("/usr/bin/md-exec-fixture", chain, environ);
    abort();
}
