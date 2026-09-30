#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
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
int main(int argc, char **argv) {
    assert(argc == 2);
    if (!strcmp(argv[1], "prepare")) prepare();
    else if (!strcmp(argv[1], "metadata")) metadata();
    else if (!strcmp(argv[1], "spawn")) spawn();
    else return 2;
    return 0;
}
