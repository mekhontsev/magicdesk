#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

// ARM64 Linux ABI; bookworm's userspace headers predate fchmodat2.
#if defined(__aarch64__) && !defined(SYS_fchmodat2)
#define SYS_fchmodat2 452
#endif

#define CHECK(expr)                                                                                          \
    do {                                                                                                     \
        if (!(expr)) {                                                                                       \
            fprintf(stderr, "%s:%d: %s (errno=%d)\n", __FILE__, __LINE__, #expr, errno);                     \
            exit(1);                                                                                         \
        }                                                                                                    \
    } while (0)
static void *creator(void *argument) {
    int *won = argument;
    int fd = open("race", O_RDWR | O_CREAT | O_EXCL, 0666);
    if (fd < 0) {
        CHECK(errno == EEXIST);
        return NULL;
    }
    *won = 1;
    close(fd);
    return NULL;
}
int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "child")) {
        int fd = atoi(argv[2]);
        struct stat st;
        CHECK(fstat(fd, &st) == 0 && st.st_nlink == 0 && st.st_size == 4096);
        char text[6];
        CHECK(pread(fd, text, 6, 0) == 6 && !memcmp(text, "shared", 6));
        char cwd[256];
        CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/tmp/ns-moved"));
        int created = open("child", O_CREAT | O_EXCL | O_WRONLY, 0777);
        CHECK(created >= 0 && fstat(created, &st) == 0 && (st.st_mode & 0777) == 0700);
        close(created);
        puts("PASS namespace exec preserves cwd, real umask and open-unlinked FD");
        return 0;
    }
    CHECK(getuid() == 2000 && mkdir("/tmp/ns-check", 0700) == 0 && chdir("/tmp/ns-check") == 0);
    umask(0077);
    int fd = open("a", O_CREAT | O_EXCL | O_RDWR, 0666);
    CHECK(fd >= 0 && !(fcntl(fd, F_GETFD) & FD_CLOEXEC));
    CHECK(ftruncate(fd, 4096) == 0 && pwrite(fd, "shared", 6, 0) == 6 && link("a", "b") == 0);
    struct stat a, b;
    CHECK(fstat(fd, &a) == 0 && stat("b", &b) == 0 && a.st_ino == b.st_ino && a.st_nlink == 2 &&
          b.st_nlink == 2 && (a.st_mode & 0777) == 0600);
    CHECK(setxattr("a", "user.md-link", "shared", 6, 0) == 0);
    char attr[6];
    CHECK(getxattr("b", "user.md-link", attr, sizeof(attr)) == 6 && !memcmp(attr, "shared", 6));
    CHECK(removexattr("b", "user.md-link") == 0);
    CHECK(fgetxattr(fd, "user.md-link", attr, sizeof(attr)) == -1 && errno == ENODATA);
    CHECK(syscall(SYS_faccessat2, fd, "", R_OK | W_OK, AT_EMPTY_PATH) == 0);
    CHECK(syscall(SYS_faccessat2, -1, "", R_OK, AT_EMPTY_PATH) == -1 && errno == EBADF);
    CHECK(mknodat(AT_FDCWD, "regular-node", S_IFREG | 0644, 0) == 0);
    CHECK(stat("regular-node", &b) == 0 && S_ISREG(b.st_mode) && (b.st_mode & 0777) == 0600);
    CHECK(mknodat(AT_FDCWD, "regular-node", 0600, 0) == -1 && errno == EEXIST);
    CHECK(mknodat(-1, "bad-node", 0600, 0) == -1 && errno == EBADF);
    CHECK(mknodat(AT_FDCWD, "regular-node/child", 0600, 0) == -1 && errno == ENOTDIR);
    CHECK(mknodat(AT_FDCWD, "fifo-node", S_IFIFO | 0600, 0) == -1 && errno == ENOTSUP);
    CHECK(unlink("regular-node") == 0);
    CHECK(setxattr(".", "system.posix_acl_default", "", 1, 0) == -1 && errno == EINVAL);
    int directory = open(".", O_RDONLY | O_DIRECTORY);
    CHECK(directory >= 0);
    CHECK(fsetxattr(directory, "system.posix_acl_default", "", 1, 0) == -1 && errno == EINVAL);
    close(directory);
    CHECK(openat(-1, "a", O_RDONLY) == -1 && errno == EBADF);
    CHECK(fstat(-1, &b) == -1 && errno == EBADF);
    CHECK(fstatat(-1, "", &b, AT_EMPTY_PATH) == -1 && errno == EBADF);
    CHECK(linkat(AT_FDCWD, "a", -1, "bad-base", 0) == -1 && errno == EBADF);
    char entries[256];
    CHECK(syscall(SYS_getdents64, -1, entries, sizeof(entries)) == -1 && errno == EBADF);
    struct statx x;
    CHECK(statx(AT_FDCWD, "b", 0, STATX_BASIC_STATS, &x) == 0 && x.stx_nlink == 2 && x.stx_ino == a.st_ino);
    int other = open("b", O_RDWR | O_CLOEXEC);
    CHECK(other >= 0 && (fcntl(other, F_GETFD) & FD_CLOEXEC));
    char *one = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    char *two = mmap(NULL, 4096, PROT_READ, MAP_SHARED, other, 0);
    CHECK(one != MAP_FAILED && two != MAP_FAILED && !memcmp(two, "shared", 6));
    CHECK(flock(fd, LOCK_EX | LOCK_NB) == 0 && flock(other, LOCK_EX | LOCK_NB) == -1 && errno == EWOULDBLOCK);
    CHECK(flock(fd, LOCK_UN) == 0 && unlink("a") == 0 && unlink("b") == 0);
    CHECK(fstat(other, &b) == 0 && b.st_nlink == 0 && !memcmp(two, "shared", 6));
    CHECK(symlink("target", "pending") == 0);
    int created = open("pending", O_CREAT | O_RDWR, 0666);
    CHECK(created >= 0);
    close(created);
    CHECK(lstat("pending", &b) == 0 && S_ISLNK(b.st_mode) && stat("target", &b) == 0);
    CHECK(open("pending", O_CREAT | O_EXCL | O_RDWR, 0600) == -1 && errno == EEXIST);
    created = open("zero", O_CREAT | O_EXCL | O_RDONLY, 0000);
    CHECK(created >= 0);
    CHECK((fcntl(created, F_GETFL) & O_ACCMODE) == O_RDONLY && fstat(created, &b) == 0 &&
          (b.st_mode & 0777) == 0);
    CHECK(open("zero", O_RDONLY) == -1 && errno == EACCES);
    close(created);
    pthread_t threads[8];
    int wins[8] = {0}, total = 0;
    for (unsigned i = 0; i < 8; ++i)
        CHECK(pthread_create(&threads[i], NULL, creator, &wins[i]) == 0);
    /* EVENT_WAIT: competing creators finish; outer fixture deadline detects a stuck call. */
    for (unsigned i = 0; i < 8; ++i) {
        CHECK(pthread_join(threads[i], NULL) == 0);
        total += wins[i];
    }
    CHECK(total == 1);
    DIR *dir = opendir(".");
    CHECK(dir);
    unsigned count = 0;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (strcmp(entry->d_name, ".") && strcmp(entry->d_name, ".."))
            ++count;
    }
    CHECK(count == 4);
    rewinddir(dir);
    CHECK(readdir(dir));
    closedir(dir);
    pid_t masks[2];
    for (unsigned i = 0; i < 2; ++i) {
        masks[i] = fork();
        CHECK(masks[i] >= 0);
        if (!masks[i]) {
            umask(i ? 0077 : 0022);
            int f = open(i ? "mask-private" : "mask-public", O_CREAT | O_EXCL | O_WRONLY, 0666);
            CHECK(f >= 0 && fstat(f, &b) == 0 && (b.st_mode & 0777) == (i ? 0600 : 0644));
            close(f);
            _exit(0);
        }
    }
    /* EVENT_WAIT: distinct child fs_struct masks, bounded by the fixture runner. */
    for (unsigned i = 0; i < 2; ++i) {
        int status;
        CHECK(waitpid(masks[i], &status, 0) == masks[i] && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    CHECK(rename("/tmp/ns-check", "/tmp/ns-moved") == 0);
    char cwd[256];
    CHECK(getcwd(cwd, sizeof(cwd)) && !strcmp(cwd, "/tmp/ns-moved"));
    pid_t child = fork();
    CHECK(child >= 0);
    if (!child) {
        char value[32];
        snprintf(value, sizeof(value), "%d", fd);
        execl("/usr/bin/md-namespace-fixture", "md-namespace-fixture", "child", value, NULL);
        _exit(2);
    }
    int status;
    /* EVENT_WAIT: actual exec'd child exit, bounded by the fixture runner. */
    CHECK(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    munmap(one, 4096);
    munmap(two, 4096);
    close(fd);
    close(other);
    CHECK(chmod("target", 0644) == 0 && stat("target", &b) == 0 && (b.st_mode & 0777) == 0644);
    long mode_result = syscall(SYS_fchmodat2, AT_FDCWD, "target", 0600, 0);
    if (mode_result == -1 && errno == ENOSYS) {
        puts("LIMIT kernel fchmodat2 unavailable; legacy chmod checked");
    } else {
        CHECK(mode_result == 0 && stat("target", &b) == 0 && (b.st_mode & 0777) == 0600);
        int mode_fd = open("target", O_PATH | O_CLOEXEC);
        CHECK(mode_fd >= 0);
        CHECK(syscall(SYS_fchmodat2, mode_fd, "", 0640, AT_EMPTY_PATH) == 0);
        CHECK(fstat(mode_fd, &b) == 0 && (b.st_mode & 0777) == 0640);
        CHECK(syscall(SYS_fchmodat2, AT_FDCWD, "target", 0600, 0x40000000) == -1 && errno == EINVAL);
        CHECK(syscall(SYS_fchmodat2, AT_FDCWD, (void *)1, 0600, 0) == -1 && errno == EFAULT);
        CHECK(symlink("target", "mode-link") == 0);
        CHECK(syscall(SYS_fchmodat2, AT_FDCWD, "mode-link", 0600, AT_SYMLINK_NOFOLLOW) == -1 && errno == EOPNOTSUPP);
        CHECK(stat("target", &b) == 0 && (b.st_mode & 0777) == 0640);
        close(mode_fd);
        puts("PASS namespace fchmodat2: path, O_PATH, flags, pointers and nofollow symlink");
    }
    CHECK(truncate("target", 42) == 0 && stat("target", &b) == 0 && b.st_size == 42);
    struct timespec times[2] = {{1000000000, 123}, {1000000001, 456}};
    CHECK(utimensat(AT_FDCWD, "target", times, 0) == 0 && stat("target", &b) == 0 &&
          b.st_mtim.tv_sec == times[1].tv_sec && b.st_mtim.tv_nsec == times[1].tv_nsec);
    CHECK(chown("target", 0, 0) == -1 && (errno == EPERM || errno == EACCES));
    int notifications = inotify_init1(IN_CLOEXEC);
    CHECK(notifications >= 0);
    int watch = inotify_add_watch(notifications, ".", IN_CREATE);
    CHECK(watch > 0);
    created = open("notification-created", O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(created >= 0); close(created);
    char events[256];
    CHECK(read(notifications, events, sizeof(events)) > (ssize_t)sizeof(struct inotify_event));
    const struct inotify_event *event = (const void *)events;
    CHECK(event->wd == watch && event->mask == IN_CREATE && !strcmp(event->name, "notification-created"));
    close(notifications);
    puts("PASS namespace syscalls: atomic open, hard links, stat/statx, mmap/flock, symlinks and directory "
         "cursors");
    puts("PASS namespace hard-link xattrs, descriptor access checks and malformed ACL rejection");
    puts("PASS namespace directory notifications through the guest syscall adapter");
    puts("LIMIT cross-mount symlinks are not implemented");
    return 0;
}
