#define _GNU_SOURCE
#include "fd_metadata.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>

static const char key[] = "user.md-xattr";
static const char payload[] = "metadata\0with\0bytes";
static void *concurrent(void *argument) {
    char name[64];
    snprintf(name, sizeof(name), "user.md-thread-%lu", (unsigned long)(uintptr_t)argument);
    for (unsigned i = 0; i < 32; ++i) {
        unsigned value = 0;
        assert(!setxattr("file", name, &i, sizeof(i), XATTR_CREATE));
        assert(getxattr("file", name, &value, sizeof(value)) == sizeof(value) && value == i);
        assert(!removexattr("file", name));
    }
    return NULL;
}
static void value_at(const char *path) {
    char value[sizeof(payload)];
    assert(getxattr(path, key, NULL, 0) == sizeof(payload));
    assert(getxattr(path, key, value, sizeof(value)) == sizeof(value));
    assert(!memcmp(value, payload, sizeof(value)));
}
static void list_at(const char *path, int nofollow, int expected) {
    ssize_t size = nofollow ? llistxattr(path, NULL, 0) : listxattr(path, NULL, 0);
    assert(size >= 0 && size < 65536);
    char *names = malloc((size_t)size + 1);
    assert(names);
    assert((nofollow ? llistxattr(path, names, (size_t)size) : listxattr(path, names, (size_t)size)) == size);
    int found = 0;
    for (ssize_t i = 0; i < size;) {
        size_t length = strnlen(names + i, (size_t)(size - i));
        assert(length < (size_t)(size - i));
        found += !strcmp(names + i, key);
        i += (ssize_t)length + 1;
    }
    assert(found == expected);
    free(names);
}
static void proc_identity(const char *path, int nofollow) {
    int fd = open(path, O_PATH | O_CLOEXEC | (nofollow ? O_NOFOLLOW : 0));
    assert(fd >= 0);
    char proc[64];
    snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
    struct stat a, b;
    assert(!fstat(fd, &a) && !stat(proc, &b));
    assert(a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_mode == b.st_mode);
    char reference[256], actual[256];
    errno = 0;
    ssize_t n = nofollow ? lgetxattr(path, "security.selinux", reference, sizeof(reference))
                         : getxattr(path, "security.selinux", reference, sizeof(reference));
    int error = errno;
    unsigned long args[] = {0, (unsigned long)"security.selinux", (unsigned long)actual, sizeof(actual), 0};
    long r = md_fd_xattr(fd, nofollow ? SYS_lgetxattr : SYS_getxattr, args);
    assert(r == (n < 0 ? -error : n));
    if (r > 0)
        assert(!memcmp(reference, actual, (size_t)r));
    close(fd);
}
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc == 3 && !strcmp(argv[2], "verify")) {
        value_at(argv[1]);
        int fd = open(argv[1], O_RDONLY);
        char content[8];
        assert(fd >= 0 && read(fd, content, sizeof(content)) == sizeof(content) &&
               !memcmp(content, "contents", 8));
        close(fd);
        puts("PASS copied/archive xattrs and contents survive a fresh service");
        return 0;
    }
    assert(argc == 2 || (argc == 3 && !strcmp(argv[2], "native")));
    assert(!mkdir(argv[1], 0700) && !chdir(argv[1]));
    int fd = open("file", O_RDWR | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "contents", 8) == 8);
    assert(!symlink("file", "link") && !symlink("absent", "dangling"));
    if (argc == 3) {
        proc_identity("file", 0);
        proc_identity("link", 1);
        proc_identity("dangling", 1);
        puts("PASS retained O_PATH magic links select file and symlink inodes, not symlink text");
    }
    int r = fsetxattr(fd, key, payload, sizeof(payload), XATTR_CREATE);
    int error = errno;
    if (r < 0) {
        assert(setxattr("link", key, payload, sizeof(payload), XATTR_CREATE) == -1 && errno == error);
        printf("LIMIT user xattrs denied by kernel for both path and descriptor (errno=%d)\n", error);
        close(fd);
        return 0;
    }
    value_at("link");
    list_at("file", 0, 1);
    list_at("link", 1, 0);
    char value[sizeof(payload)];
    assert(lgetxattr("link", key, value, sizeof(value)) == -1 && errno == ENODATA);
    assert(lgetxattr("dangling", key, value, sizeof(value)) == -1 && errno == ENODATA);
    assert(getxattr("dangling", key, value, sizeof(value)) == -1 && errno == ENOENT);
    assert(lsetxattr("link", key, "x", 1, 0) == -1 && (errno == EPERM || errno == EACCES));
    assert(lremovexattr("link", key) == -1 && (errno == EPERM || errno == EACCES));
    value_at("file");
    assert(setxattr("link", key, "x", 1, XATTR_CREATE) == -1 && errno == EEXIST);
    assert(setxattr("file", "user.absent", "x", 1, XATTR_REPLACE) == -1 && errno == ENODATA);
    assert(getxattr("file", key, value, 1) == -1 && errno == ERANGE);
    assert(syscall(SYS_getxattr, "file", key, (void *)1, sizeof(value)) == -1 && errno == EFAULT);
    assert(syscall(SYS_setxattr, "file", key, (void *)1, 1, 0) == -1 && errno == EFAULT);
    assert(syscall(SYS_setxattr, "file", (void *)1, "x", 1, 0) == -1 && errno == EFAULT);
    int probe = open("/dev/null", O_RDONLY | O_CLOEXEC);
    assert(probe >= 0);
    close(probe);
    for (unsigned i = 0; i < 128; ++i) {
        assert(getxattr("file", key, value, 1) == -1 && errno == ERANGE);
        assert(setxattr("file", "user.absent", "x", 1, XATTR_REPLACE) == -1 && errno == ENODATA);
    }
    int after = open("/dev/null", O_RDONLY | O_CLOEXEC);
    assert(after == probe);
    close(after);
    pthread_attr_t attributes;
    pthread_t workers[4];
    assert(!pthread_attr_init(&attributes) && !pthread_attr_setstacksize(&attributes, 128 * 1024));
    for (uintptr_t i = 0; i < 4; ++i)
        assert(!pthread_create(&workers[i], &attributes, concurrent, (void *)i));
    /* EVENT_WAIT: metadata workers finish; the outer fixture deadline detects a stuck request. */
    for (unsigned i = 0; i < 4; ++i)
        assert(!pthread_join(workers[i], NULL));
    assert(!pthread_attr_destroy(&attributes));
    puts("PASS concurrent xattrs on 128 KiB stacks and descriptor cleanup after failed operations");
    assert(!lsetxattr("file", key, "short", 5, XATTR_REPLACE));
    assert(fgetxattr(fd, key, value, sizeof(value)) == 5 && !memcmp(value, "short", 5));
    assert(!lremovexattr("file", key));
    assert(!setxattr("link", key, payload, sizeof(payload), XATTR_CREATE));
    assert(!rename("file", "moved"));
    value_at("moved");
    assert(getxattr("link", key, value, sizeof(value)) == -1 && errno == ENOENT);
    assert(!removexattr("moved", key));
    assert(fgetxattr(fd, key, value, sizeof(value)) == -1 && errno == ENODATA);
    list_at("moved", 1, 0);
    assert(!setxattr("moved", key, payload, sizeof(payload), XATTR_CREATE));
    assert(!mkdir("directory", 0700));
    assert(!setxattr("directory", key, payload, sizeof(payload), 0));
    value_at("directory");
    assert(!removexattr("directory", key));
    assert(!chmod("moved", 0200));
    assert(getxattr("moved", key, value, sizeof(value)) == -1 && errno == EACCES);
    assert(fgetxattr(fd, key, value, sizeof(value)) == -1 && errno == EACCES);
    list_at("moved", 0, 1);
    assert(!setxattr("moved", key, payload, sizeof(payload), XATTR_REPLACE));
    assert(!chmod("moved", 0400));
    value_at("moved");
    assert(setxattr("moved", key, "x", 1, 0) == -1 && errno == EACCES);
    assert(fsetxattr(fd, key, "x", 1, 0) == -1 && errno == EACCES);
    assert(fremovexattr(fd, key) == -1 && errno == EACCES);
    assert(!chmod("moved", 0600));
    assert(!unlink("moved"));
    assert(fgetxattr(fd, key, value, sizeof(value)) == sizeof(payload));
    close(fd);
    fd = open("archive-source", O_RDWR | O_CREAT | O_EXCL, 0600);
    assert(fd >= 0 && write(fd, "contents", 8) == 8);
    assert(!fsetxattr(fd, key, payload, sizeof(payload), XATTR_CREATE));
    close(fd);
    puts("PASS xattrs: binary values, path/FD identity, flags, permissions, symlinks and errors");
    return 0;
}
