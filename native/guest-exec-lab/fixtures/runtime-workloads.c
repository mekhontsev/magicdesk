#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void directory(const char *path) {
    assert(!mkdir(path, 0700) || errno == EEXIST);
}
static void prepare(void) {
    directory("/bench/tree");
    char path[128], bytes[32]; memset(bytes, 42, sizeof(bytes));
    for (unsigned d = 0; d < 16; d++) {
        snprintf(path, sizeof(path), "/bench/tree/d%u", d); directory(path);
        for (unsigned f = 0; f < 16; f++) {
            snprintf(path, sizeof(path), "/bench/tree/d%u/f%u", d, f);
            int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
            assert(fd >= 0 && write(fd, bytes, sizeof(bytes)) == sizeof(bytes) && !close(fd));
        }
    }
}
static void metadata(void) {
    unsigned files = 0;
    char path[128];
    for (unsigned pass = 0; pass < 16; pass++) for (unsigned d = 0; d < 16; d++) {
        snprintf(path, sizeof(path), "/bench/tree/d%u", d);
        DIR *dir = opendir(path); assert(dir);
        struct dirent *entry;
        unsigned count = 0;
        errno = 0;
        while ((entry = readdir(dir))) {
            if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
            struct stat a, b;
            assert(!fstatat(dirfd(dir), entry->d_name, &a, AT_SYMLINK_NOFOLLOW));
            int fd = openat(dirfd(dir), entry->d_name, O_RDONLY | O_CLOEXEC); assert(fd >= 0);
            assert(!fstat(fd, &b) && a.st_ino == b.st_ino && a.st_dev == b.st_dev);
            assert(S_ISREG(b.st_mode) && b.st_size == 32 && b.st_nlink == 1);
            unsigned char bytes[32];
            assert(read(fd, bytes, sizeof(bytes)) == sizeof(bytes));
            for (unsigned i = 0; i < sizeof(bytes); i++) assert(bytes[i] == 42);
            assert(!close(fd)); count++; files++; errno = 0;
        }
        assert(!errno && count == 16 && !closedir(dir));
    }
    assert(files == 4096);
    printf("MD_WORKLOAD_OK metadata %u\n", files);
}
static void spawn(void) {
    for (unsigned i = 0; i < 128; i++) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) { execl("/bin/true", "true", NULL); _exit(127); }
        int status;
        /* EVENT_WAIT: exact child exit, bounded by the benchmark's process deadline. */
        assert(waitpid(child, &status, 0) == child);
        assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    puts("MD_WORKLOAD_OK spawn 128");
}
static int watch_tree(void) {
    int fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC); assert(fd >= 0);
    for (unsigned i = 0; i < 16; ++i) {
        char path[64]; snprintf(path, sizeof(path), "/bench/tree/d%u", i);
        assert(inotify_add_watch(fd, path, IN_OPEN | IN_ACCESS | IN_CLOSE_NOWRITE) > 0);
    }
    return fd;
}
static unsigned drain(int fd) {
    char bytes[65536]; unsigned count = 0;
    for (;;) {
        ssize_t n = read(fd, bytes, sizeof(bytes));
        if (n < 0 && errno == EAGAIN) return count;
        assert(n > 0);
        for (size_t at = 0; at < (size_t)n;) {
            struct inotify_event e; memcpy(&e, bytes + at, sizeof(e));
            assert(!(e.mask & IN_Q_OVERFLOW));
            at += sizeof(e) + e.len; count++;
        }
    }
}
static uint64_t now(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC, &t));
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
int main(int argc, char **argv) {
    assert(argc == 2 || argc == 3);
    int watch = -1, stop = -1; pid_t observer = -1;
    const char *mode = argc == 3 ? argv[2] : "none";
    if (!strcmp(mode, "self")) watch = watch_tree();
    else if (!strcmp(mode, "external")) {
        int ready[2], end[2]; assert(!pipe2(ready, O_CLOEXEC) && !pipe2(end, O_CLOEXEC));
        observer = fork(); assert(observer >= 0);
        if (!observer) {
            close(ready[0]); close(end[1]);
            int fd = watch_tree(); assert(write(ready[1], "x", 1) == 1); close(ready[1]);
            struct pollfd fds[] = {{fd, POLLIN, 0}, {end[0], POLLIN, 0}};
            unsigned events = 0;
            for (;;) {
                /* EVENT_WAIT: data or parent completion; outer process deadline cancels hangs. */
                assert(poll(fds, 2, -1) > 0);
                events += drain(fd);
                if (fds[1].revents) break;
            }
            if (!strcmp(argv[1], "metadata")) assert(events > 0);
            printf("MD_WATCH events=%u mode=external\n", events); fflush(stdout);
            close(fd); close(end[0]); _exit(0);
        }
        close(ready[1]); close(end[0]); char byte;
        /* EVENT_WAIT: observer installed all watches, never a startup delay. */
        assert(read(ready[0], &byte, 1) == 1); close(ready[0]); stop = end[1];
    } else assert(!strcmp(mode, "none"));
    uint64_t start = now();
    if (!strcmp(argv[1], "prepare")) prepare();
    else if (!strcmp(argv[1], "metadata")) metadata();
    else if (!strcmp(argv[1], "spawn")) spawn();
    else return 2;
    uint64_t elapsed = now() - start;
    if (watch >= 0) {
        unsigned count = drain(watch);
        if (!strcmp(argv[1], "metadata")) assert(count > 0);
        printf("MD_WATCH events=%u mode=self\n", count); close(watch);
    }
    if (observer > 0) {
        close(stop); int status;
        assert(waitpid(observer, &status, 0) == observer && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    printf("MD_ELAPSED workload=%s watch=%s ns=%llu\n", argv[1], mode, (unsigned long long)elapsed);
    return 0;
}
