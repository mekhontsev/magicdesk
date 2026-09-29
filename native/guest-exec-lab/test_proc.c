#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "%s:%d: %s errno=%d\n", __FILE__, __LINE__, #x, errno); exit(1); } } while (0)
static int namespace;
extern char **environ;
static void alias(char *path, size_t size, unsigned kind, int fd) {
    switch (kind) {
    case 0: snprintf(path, size, "/proc/self/fd/%d", fd); break;
    case 1: snprintf(path, size, "/proc/thread-self/fd/%d", fd); break;
    case 2: snprintf(path, size, "/proc/%d/fd/%d", getpid(), fd); break;
    case 3: snprintf(path, size, "/proc/%d/task/%ld/fd/%d", getpid(), syscall(SYS_gettid), fd); break;
    case 4: snprintf(path, size, "/dev/fd/%d", fd); break;
    default: snprintf(path, size, "/proc/./self/fd//./%d", fd); break;
    }
}
static void text_link(const char *path, const char *expected) {
    char text[4096];
    ssize_t n = readlink(path, text, sizeof(text) - 1);
    CHECK(n >= 0);
    text[n] = 0;
    CHECK(!strcmp(text, expected));
    CHECK(readlink(path, text, 1) == 1 && text[0] == expected[0]);
}
static unsigned descriptors(void) {
    DIR *dir = opendir("/proc/self/fd");
    CHECK(dir);
    unsigned count = 0;
    while (readdir(dir)) ++count;
    closedir(dir);
    return count;
}
static void file_checks(int fd, const char *original, nlink_t links) {
    for (unsigned i = 0; i < 6; ++i) {
        char path[256], text[32];
        alias(path, sizeof(path), i, fd);
        struct stat st, source;
        CHECK(fstat(fd, &source) == 0 && stat(path, &st) == 0);
        CHECK(st.st_dev == source.st_dev && st.st_ino == source.st_ino && st.st_nlink == links);
        CHECK(syscall(SYS_newfstatat, AT_FDCWD, path, (void *)1, 0) == -1 && errno == EFAULT);
        struct statx x;
        CHECK(syscall(SYS_statx, AT_FDCWD, path, 0, STATX_BASIC_STATS, &x) == 0 && x.stx_nlink == links);
        CHECK(lstat(path, &st) == 0 && S_ISLNK(st.st_mode));
        CHECK(open(path, O_RDONLY | O_NOFOLLOW) == -1 && errno == ELOOP);
        int symlink = open(path, O_PATH | O_NOFOLLOW | O_CLOEXEC);
        CHECK(symlink >= 0 && fstat(symlink, &st) == 0 && S_ISLNK(st.st_mode));
        close(symlink);
        CHECK(open(path, O_CREAT | O_EXCL | O_RDWR, 0600) == -1 && errno == EEXIST);
        CHECK(lseek(fd, 2, SEEK_SET) == 2);
        int copy = open(path, O_RDONLY | O_CLOEXEC);
        CHECK(copy >= 0 && fcntl(copy, F_GETFD) == FD_CLOEXEC);
        CHECK(lseek(copy, 0, SEEK_CUR) == 0 && read(copy, text, 4) == 4 && !memcmp(text, "data", 4));
        CHECK(lseek(fd, 0, SEEK_CUR) == 2);
        close(copy);
        if (namespace) CHECK(readlink(path, text, sizeof(text)) == -1 && errno == ENOTSUP);
        else text_link(path, original);
    }
}
struct worker { int fd; };
static void *worker(void *value) {
    struct worker *arg = value;
    for (unsigned i = 0; i < 32; ++i) {
        char path[256], text[4];
        alias(path, sizeof(path), 1 + i % 3, arg->fd);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        struct stat st;
        CHECK(fd >= 0 && fstat(fd, &st) == 0 && read(fd, text, 4) == 4 && !memcmp(text, "data", 4));
        close(fd);
    }
    return NULL;
}
int main(int argc, char **argv) {
    CHECK(argc >= 2);
    if (argc == 3 && !strcmp(argv[1], "spawn")) {
        char data[6];
        CHECK(read(atoi(argv[2]), data, sizeof(data)) == 6 && !memcmp(data, "inside", 6));
        return 0;
    }
    if (argc == 4 && !strcmp(argv[1], "child")) {
        namespace = atoi(argv[3]);
        int fd = atoi(argv[2]);
        char path[128]; alias(path, sizeof(path), 0, fd);
        int copy = open(path, O_RDONLY);
        char data[4]; struct stat st;
        CHECK(copy >= 0 && read(copy, data, 4) == 4 && !memcmp(data, "data", 4));
        CHECK(stat(path, &st) == 0 && st.st_nlink == 0);
        close(copy);
        puts("PASS proc: inherited open-unlinked file is usable after exec");
        return 0;
    }
    namespace = argc > 2 && !strcmp(argv[2], "namespace");
    CHECK(mkdir(argv[1], 0700) == 0 && chdir(argv[1]) == 0);
    unsigned before = descriptors();
    int fd = open("data", O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(fd >= 0 && write(fd, "data", 4) == 4);
    char original[4096], path[4096], other[4096], text[4096];
    snprintf(original, sizeof(original), "%s/data", argv[1]);
    if (namespace) CHECK(link("data", "alias") == 0);
    file_checks(fd, original, namespace ? 2 : 1);
    alias(path, sizeof(path), 0, fd);
    CHECK(fchmod(fd, 0) == 0);
    CHECK(open(path, O_RDONLY) == -1 && errno == EACCES);
    CHECK(pwrite(fd, "data", 4, 0) == 4); /* Existing FD still works; reopening rechecks permissions. */
    CHECK(chmod(path, 0600) == 0 && access(path, R_OK | W_OK) == 0);
    CHECK(setxattr(path, "user.md-proc", "value", 5, 0) == 0);
    CHECK(fgetxattr(fd, "user.md-proc", text, sizeof(text)) == 5 && !memcmp(text, "value", 5));
    CHECK(removexattr(path, "user.md-proc") == 0);
    CHECK(truncate(path, 8) == 0 && lseek(fd, 0, SEEK_END) == 8);
    CHECK(ftruncate(fd, 4) == 0);
    struct timespec times[] = {{1000000000, 12}, {1000000001, 34}};
    struct stat st;
    CHECK(utimensat(AT_FDCWD, path, times, 0) == 0 && fstat(fd, &st) == 0 && st.st_mtim.tv_nsec == 34);
    pthread_attr_t attr;
    CHECK(pthread_attr_init(&attr) == 0 && pthread_attr_setstacksize(&attr, PTHREAD_STACK_MIN) == 0);
    pthread_t threads[4]; struct worker arg = {fd};
    for (unsigned i = 0; i < 4; ++i) CHECK(pthread_create(&threads[i], &attr, worker, &arg) == 0);
    /* EVENT_WAIT: workers finish; outer fixture deadline fails a stuck descriptor operation. */
    for (unsigned i = 0; i < 4; ++i) CHECK(pthread_join(threads[i], NULL) == 0);
    pthread_attr_destroy(&attr);
    puts("PASS proc: file aliases, virtual nlink/statx, independent offsets, permissions and concurrent minimum-stack IO");

    CHECK(mkdir("dir", 0700) == 0);
    int dir = open("dir", O_RDONLY | O_DIRECTORY);
    CHECK(dir >= 0);
    int child = openat(dir, "child", O_CREAT | O_EXCL | O_WRONLY, 0600);
    CHECK(child >= 0 && write(child, "inside", 6) == 6);
    close(child);
    CHECK(symlinkat("child", dir, "link") == 0 && rename("dir", "moved") == 0);
    alias(path, sizeof(path), 0, dir);
    snprintf(other, sizeof(other), "%s/moved", argv[1]);
    text_link(path, other);
    CHECK(syscall(SYS_readlinkat, AT_FDCWD, path, (void *)1, 32) == -1 && errno == EFAULT);
    CHECK(readlink(path, text, 0) == -1 && errno == EINVAL);
    for (unsigned i = 0; i < 6; ++i) {
        alias(path, sizeof(path), i, dir);
        DIR *listing = opendir(path);
        CHECK(listing);
        int saw_child = 0, saw_link = 0; struct dirent *entry;
        while ((entry = readdir(listing))) {
            saw_child |= !strcmp(entry->d_name, "child"); saw_link |= !strcmp(entry->d_name, "link");
        }
        closedir(listing);
        CHECK(saw_child && saw_link);
        strcat(path, "/link");
        CHECK(lstat(path, &st) == 0 && S_ISLNK(st.st_mode));
        text_link(path, "child");
        child = open(path, O_RDONLY);
        CHECK(child >= 0 && read(child, text, sizeof(text)) == 6 && !memcmp(text, "inside", 6));
        close(child);
    }
    alias(path, sizeof(path), 0, dir);
    if (namespace) CHECK(setxattr(path, "system.posix_acl_default", "", 0, 0) == -1 && errno == ENOTSUP);
    CHECK(chdir(path) == 0);
    text_link("/proc/self/cwd", other); text_link("/proc/thread-self/cwd", other);
    CHECK(getcwd(text, sizeof(text)) && !strcmp(text, other));
    CHECK(chdir(argv[1]) == 0);
    strcat(path, "/created");
    child = open(path, O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(child >= 0); close(child);
    CHECK(chmod(path, 0644) == 0 && fstatat(dir, "created", &st, 0) == 0 && (st.st_mode & 0777) == 0644);
    CHECK(unlink(path) == 0);
    alias(path, sizeof(path), 0, dir);
    strcat(path, "/child");
    posix_spawn_file_actions_t actions;
    CHECK(posix_spawn_file_actions_init(&actions) == 0);
    CHECK(posix_spawn_file_actions_addopen(&actions, 198, path, O_RDONLY, 0) == 0);
    char *spawn_args[] = {(char *)getauxval(AT_EXECFN), "spawn", "198", NULL};
    pid_t spawned;
    CHECK(posix_spawn(&spawned, spawn_args[0], &actions, NULL, spawn_args, environ) == 0);
    int spawn_status;
    /* EVENT_WAIT: descriptor-path file action on libc's small spawn stack, bounded by runner. */
    CHECK(waitpid(spawned, &spawn_status, 0) == spawned);
    if (!WIFEXITED(spawn_status) || WEXITSTATUS(spawn_status))
        fprintf(stderr, "posix_spawn child status=%#x\n", spawn_status);
    CHECK(WIFEXITED(spawn_status) && !WEXITSTATUS(spawn_status));
    CHECK(posix_spawn_file_actions_destroy(&actions) == 0);
    puts("PASS proc: posix_spawn opens a virtual-directory descriptor suffix without scratch allocation");
    close(dir);
    text_link("/proc/self/root", "/");
    snprintf(path, sizeof(path), "/proc/self/root%s/data", argv[1]);
    CHECK(stat(path, &st) == 0 && st.st_nlink == (namespace ? 2 : 1));
    child = open(path, O_RDONLY);
    CHECK(child >= 0); close(child);
    const char *exe = (const char *)getauxval(AT_EXECFN);
    text_link("/proc/self/exe", exe); text_link("/proc/thread-self/exe", exe);
    puts("PASS proc: renamed directory, virtual enumeration, suffix operations, cwd/root and executable aliases");

    int pipes[2]; CHECK(pipe(pipes) == 0);
    alias(path, sizeof(path), 0, pipes[0]);
    child = open(path, O_RDONLY | O_NONBLOCK);
    CHECK(child >= 0 && write(pipes[1], "p", 1) == 1 && read(child, text, 1) == 1 && text[0] == 'p');
    CHECK(stat(path, &st) == 0 && S_ISFIFO(st.st_mode));
    CHECK(readlink(path, text, sizeof(text)) > 6 && !memcmp(text, "pipe:[", 6));
    close(child); close(pipes[0]); close(pipes[1]);
    int sockets[2]; CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    alias(path, sizeof(path), 1, sockets[0]);
    CHECK(stat(path, &st) == 0 && S_ISSOCK(st.st_mode));
    CHECK(readlink(path, text, sizeof(text)) > 8 && !memcmp(text, "socket:[", 8));
    CHECK(open(path, O_RDONLY) == -1 && errno == ENXIO);
    close(sockets[0]); close(sockets[1]);
    int host = open("/dev", O_PATH | O_DIRECTORY | O_CLOEXEC);
    CHECK(host >= 0);
    alias(path, sizeof(path), 1, host); strcat(path, "/null");
    child = open(path, O_RDONLY);
    CHECK(child >= 0 && read(child, text, 1) == 0);
    CHECK(stat(path, &st) == 0 && S_ISCHR(st.st_mode));
    close(child); close(host);
    if (namespace) {
        snprintf(path, sizeof(path), "/proc/%d/root", getppid());
        CHECK(open(path, O_RDONLY) == -1 && errno == ENOTSUP);
    }
    int saved = fcntl(0, F_DUPFD_CLOEXEC, 0);
    CHECK(saved >= 0 && dup2(fd, 0) == 0);
    child = open("/dev/stdin", O_RDONLY);
    CHECK(child >= 0 && read(child, text, 4) == 4 && !memcmp(text, "data", 4));
    close(child);
    CHECK(stat("/dev/stdin", &st) == 0 && st.st_nlink == (namespace ? 2 : 1));
    text_link("/dev/stdin", "/proc/self/fd/0");
    CHECK(dup2(saved, 0) == 0); close(saved);
    puts("PASS proc: native pipe descriptors, sockets, host directories and standard-input alias retain kernel semantics");

    CHECK(unlink("data") == 0);
    if (namespace) CHECK(unlink("alias") == 0);
    snprintf(other, sizeof(other), "%s (deleted)", original);
    file_checks(fd, other, 0);
    pid_t pid = fork(); CHECK(pid >= 0);
    if (!pid) {
        char descriptor[32], mode[8];
        snprintf(descriptor, sizeof(descriptor), "%d", fd); snprintf(mode, sizeof(mode), "%d", namespace);
        execl(exe, exe, "child", descriptor, mode, NULL); _exit(2);
    }
    int status;
    /* EVENT_WAIT: exec child's exit; outer fixture timeout cancels failure. */
    CHECK(waitpid(pid, &status, 0) == pid && WIFEXITED(status) && !WEXITSTATUS(status));
    close(fd);
    alias(path, sizeof(path), 0, fd);
    CHECK(open(path, O_RDONLY) == -1 && errno == ENOENT);
    CHECK(stat(path, &st) == -1 && errno == ENOENT);
    CHECK(descriptors() == before);
    puts("PASS proc: deleted inode remains accessible, invalid descriptors fail and no FDs leak");
    return 0;
}
