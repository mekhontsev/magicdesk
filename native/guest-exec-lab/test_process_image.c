#define _GNU_SOURCE
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/syscall.h>
#include <unistd.h>

struct open_how { uint64_t flags, mode, resolve; };
enum { NO_XDEV = 1, NO_MAGICLINKS = 2, NO_SYMLINKS = 4, BENEATH = 8, IN_ROOT = 16, CACHED = 32 };
static int extended(int fd, const char *path, uint64_t flags, uint64_t resolve) {
    struct open_how how = {flags, 0, resolve};
    return (int)syscall(SYS_openat2, fd, path, &how, sizeof(how));
}
static void image(int base, const char *name, int argc, char **argv) {
    char buffer[8192];
    int fd = openat(base, name, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { fprintf(stderr, "open image %s base=%d: %s\n", name, base, strerror(errno)); }
    assert(fd >= 0);
    ssize_t n = read(fd, buffer, sizeof(buffer)); assert(n > 0);
    size_t offset = 0;
    for (int i = 0; i < argc; ++i) {
        size_t bytes = strlen(argv[i]) + 1;
        assert(offset + bytes <= (size_t)n && !memcmp(buffer + offset, argv[i], bytes));
        offset += bytes;
    }
    assert(offset == (size_t)n && read(fd, buffer, 1) == 0);
    assert(lseek(fd, 0, SEEK_SET) == 0);
    close(fd);
}
int main(int argc, char **argv) {
    image(AT_FDCWD, "/proc/self/cmdline", argc, argv);
    char *original[16]; assert(argc > 0 && argc < 16);
    memcpy(original, argv, (size_t)argc * sizeof(*argv));
    argv[0] = (char *)1;
    image(AT_FDCWD, "/proc/self/cmdline", argc, original);
    argv[0] = original[0];
    char first = argv[0][0]; argv[0][0] = 'X';
    image(AT_FDCWD, "/proc/self/cmdline", argc, original);
    argv[0][0] = first;
    image(AT_FDCWD, "/proc/thread-self/cmdline", argc, argv);
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", getpid());
    image(AT_FDCWD, path, argc, argv);
    int proc = open("/proc/self", O_PATH | O_DIRECTORY); assert(proc >= 0);
    image(proc, "cmdline", argc, argv);
    int fd = openat(proc, "auxv", O_RDONLY); assert(fd >= 0);
    Elf64_auxv_t aux[128];
    ssize_t n = read(fd, aux, sizeof(aux));
    assert(n > 0 && n % sizeof(*aux) == 0);
    int headers = 0, entry = 0;
    for (size_t i = 0; i < (size_t)n / sizeof(*aux); ++i) {
        assert(aux[i].a_un.a_val == getauxval(aux[i].a_type));
        headers |= aux[i].a_type == AT_PHDR; entry |= aux[i].a_type == AT_ENTRY;
    }
    assert(headers && entry); close(fd);
    fd = extended(proc, "cmdline", O_RDONLY, NO_MAGICLINKS | BENEATH);
    assert(fd >= 0); close(fd);
    fd = extended(proc, "cmdline", O_RDONLY, NO_XDEV | NO_SYMLINKS | BENEATH);
    assert(fd >= 0); close(fd); close(proc);
    int root = open("/etc", O_PATH | O_DIRECTORY); assert(root >= 0);
    fd = extended(root, "os-release", O_RDONLY, BENEATH | NO_MAGICLINKS);
    /* Distribution os-release may be an absolute symlink: use passwd instead. */
    if (fd >= 0) close(fd); else assert(errno == EXDEV);
    fd = extended(root, "passwd", O_RDONLY | O_CLOEXEC, BENEATH | NO_XDEV | NO_SYMLINKS);
    assert(fd >= 0 && (fcntl(fd, F_GETFD) & FD_CLOEXEC)); close(fd);
    assert(extended(root, "../bin/sh", O_RDONLY, BENEATH) == -1 && errno == EXDEV);
    assert(extended(root, "/passwd", O_RDONLY, BENEATH) == -1 && errno == EXDEV);
    fd = extended(root, "/passwd", O_RDONLY, IN_ROOT); assert(fd >= 0); close(fd);
    assert(extended(root, "passwd", O_RDONLY, CACHED) == -1 && errno == EAGAIN);
    assert(extended(root, "passwd", O_RDONLY, BENEATH | IN_ROOT) == -1 && errno == EINVAL);
    struct { struct open_how how; unsigned char tail[73]; } larger = {{O_RDONLY, 0, BENEATH}, {0}};
    fd = syscall(SYS_openat2, root, "passwd", &larger, sizeof(larger));
    assert(fd >= 0); close(fd);
    larger.tail[72] = 1;
    assert(syscall(SYS_openat2, root, "passwd", &larger, sizeof(larger)) == -1 && errno == E2BIG);
    assert(syscall(SYS_openat2, root, "passwd", &larger, sizeof(larger.how) - 1) == -1 && errno == EINVAL);
    assert(syscall(SYS_openat2, root, "passwd", &larger, getauxval(AT_PAGESZ) + 1) == -1 && errno == E2BIG);
    close(root);
    puts("PASS process image: guest argv/auxv aliases and native seek; openat2 scoped resolution");
    return 0;
}
