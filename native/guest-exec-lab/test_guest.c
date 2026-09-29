#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int (*next_value)(void);
static volatile sig_atomic_t interrupted;
static void signal_handler(int signal) { interrupted = signal; }
static void *thread_main(void *unused) {
    (void)unused;
    assert(next_value() == 41);
    for (int i = 0; i < 50; ++i) {
        int fd = open("/etc/md-guest-fixture", O_RDONLY);
        assert(fd >= 0);
        close(fd);
    }
    return NULL;
}

static void wait_child(pid_t pid, int expected) {
    int status;
    // EVENT_WAIT: exact child exit; the device runner's command timeout fails the fixture on a hang.
    assert(waitpid(pid, &status, 0) == pid);
    if (!WIFEXITED(status) || WEXITSTATUS(status) != expected)
        fprintf(stderr, "child pid=%d status=%#x expected=%d\n", pid, status, expected);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == expected);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 3 && !strcmp(argv[1], "child")) {
        assert(!strcmp(argv[0], "preserved-argv0"));
        assert(!strcmp(argv[2], "argument with spaces"));
        assert(getenv("MD_FIXTURE") && !strcmp(getenv("MD_FIXTURE"), "kept"));
        int fd = open("/etc/md-guest-fixture", O_RDONLY);
        assert(fd >= 0);
        close(fd);
        return 37;
    }
    assert(getuid() == 2000 && geteuid() == 2000);
    char value[64] = {0};
    int fd = open("/etc/md-guest-fixture", O_RDONLY);
    assert(fd >= 0 && read(fd, value, sizeof(value) - 1) == 12);
    assert(!strcmp(value, "guest-value\n"));
    close(fd);
    FILE *stream = fopen("/etc/md-guest-fixture", "r");
    assert(stream && fgets(value, sizeof(value), stream));
    fclose(stream);
    DIR *directory = opendir("/etc");
    assert(directory);
    closedir(directory);
    struct stat metadata;
    assert(!stat("/etc/md-guest-fixture", &metadata) && metadata.st_size == 12);
    assert(!chdir("/etc"));
    char *cwd = getcwd(NULL, 0);
    assert(cwd && !strcmp(cwd, "/etc"));
    free(cwd);
    int dirfd = open(".", O_RDONLY | O_DIRECTORY);
    assert(dirfd >= 0);
    fd = openat(dirfd, "md-guest-fixture", O_RDONLY);
    assert(fd >= 0);
    close(fd);
    close(dirfd);
    puts("PASS libc, identity, file APIs, cwd and dirfd");

    assert(!mkdir("/tmp/files", 0700));
    assert(!mkdir("/tmp/files/dir/", 0700));
    assert(!rmdir("/tmp/files/dir/"));
    assert(!symlink("/tmp/files/absent", "/tmp/files/dangling"));
    assert(open("/tmp/files/dangling", O_CREAT | O_EXCL | O_WRONLY, 0600) == -1 && errno == EEXIST);
    assert(!unlink("/tmp/files/dangling"));
    fd = open("/tmp/files/a", O_CREAT | O_WRONLY | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "x", 1) == 1);
    close(fd);
    assert(!symlink("/tmp/files/a", "/tmp/files/absolute"));
    assert(!symlink("../files/a", "/tmp/files/relative"));
    assert(!stat("/tmp/files/absolute", &metadata) && metadata.st_size == 1);
    assert(!stat("/tmp/files/relative", &metadata) && metadata.st_size == 1);
    assert(!lstat("/tmp/files/absolute", &metadata) && S_ISLNK(metadata.st_mode));
    assert(open("/tmp/files/absolute", O_RDONLY | O_NOFOLLOW) == -1 && errno == ELOOP);
    int linked = link("/tmp/files/a", "/tmp/files/hard");
    if (linked < 0) {
        assert(errno == EACCES);
        puts("LIMIT hard links denied by selected shell policy (EACCES)");
    } else {
        assert(!unlink("/tmp/files/hard"));
    }
    assert(!rename("/tmp/files/a", "/tmp/files/moved"));
    assert(!unlink("/tmp/files/moved"));
    assert(!unlink("/tmp/files/absolute"));
    assert(!unlink("/tmp/files/relative"));
    assert(!rmdir("/tmp/files"));
    puts("PASS symlinks, create, rename, unlink and explicit hard-link outcome");

    void *plugin = dlopen("/usr/lib/md-fixture.so", RTLD_NOW | RTLD_LOCAL);
    assert(plugin);
    next_value = dlsym(plugin, "next_value");
    assert(next_value && next_value() == 41 && next_value() == 42);
    pthread_t threads[4];
    for (int i = 0; i < 4; ++i) assert(!pthread_create(threads + i, NULL, thread_main, NULL));
    // EVENT_WAIT: worker termination; bounded by the enclosing fixture command timeout.
    for (int i = 0; i < 4; ++i) assert(!pthread_join(threads[i], NULL));
    assert(next_value() == 43);
    assert(!dlclose(plugin));
    assert(signal(SIGUSR1, signal_handler) != SIG_ERR);
    assert(!raise(SIGUSR1) && interrupted == SIGUSR1);
    puts("PASS dlopen, libc TLS, threads and signals without a custom linker");

    pid_t pid = fork();
    assert(pid >= 0);
    if (!pid) {
        char *args[] = {"preserved-argv0", "child", "argument with spaces", NULL};
        char *env[] = {"MD_FIXTURE=kept", NULL};
        execve("/usr/bin/md-fixture", args, env);
        _exit(90);
    }
    wait_child(pid, 37);
    pid = fork();
    assert(pid >= 0);
    if (!pid) {
        char *args[] = {"md-script", NULL};
        execvp("md-script", args);
        _exit(90);
    }
    wait_child(pid, 23);
    puts("PASS fork/execve, explicit env, argv0, PATH and shebang");

    int sockets[2];
    assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, sockets));
    pid = fork();
    assert(pid >= 0);
    if (!pid) {
        close(sockets[0]);
        assert(write(sockets[1], "ipc", 3) == 3);
        _exit(0);
    }
    close(sockets[1]);
    assert(read(sockets[0], value, sizeof(value)) == 3);
    close(sockets[0]);
    wait_child(pid, 0);
    puts("PASS inherited Unix socket");

    errno = 0;
    long raw = syscall(SYS_openat, AT_FDCWD, "/etc/md-guest-fixture", O_RDONLY, 0);
    assert(raw >= 0);
    close((int)raw);
    puts("PASS direct syscall file access");
    char *spawn_args[] = {"preserved-argv0", "child", "argument with spaces", NULL};
    char *spawn_env[] = {"MD_FIXTURE=kept", NULL};
    assert(!posix_spawn(&pid, "/usr/bin/md-fixture", NULL, NULL, spawn_args, spawn_env));
    wait_child(pid, 37);
    puts("PASS posix_spawn through libc-internal exec");
    return 0;
}
