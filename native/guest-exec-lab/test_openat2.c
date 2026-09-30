#define _GNU_SOURCE
#include "file_calls.h"
#include "raw.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static long open_how(const struct md_fs *fs, void *how, size_t size) {
    unsigned long args[6] = {(unsigned long)AT_FDCWD, (unsigned long)"/fixture",
        (unsigned long)how, size};
    return md_file_call(fs, "/fixture", SYS_openat2, args);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    struct md_fs fs = {0};
    assert(md_copy(fs.root, sizeof(fs.root), argv[1]) == 0);
    char path[PATH_MAX];
    assert(snprintf(path, sizeof(path), "%s/fixture", argv[1]) < (int)sizeof(path));
    int file = open(path, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC, 0600);
    assert(file >= 0); close(file);
    unsigned char *bytes = calloc(1, 16384);
    assert(bytes);
    struct open_how *how = (void *)bytes;
    how->flags = O_RDONLY | O_CLOEXEC;
    const size_t pages[] = {4096, 16384};
    for (unsigned p = 0; p < sizeof(pages) / sizeof(*pages); ++p) {
        // Argument-validation bounds only, not kernel page-size emulation.
        md_page_size = pages[p];
        long fd = open_how(&fs, bytes, md_page_size);
        assert(fd >= 0 && fcntl(fd, F_GETFD) == FD_CLOEXEC);
        close((int)fd);
        bytes[md_page_size - 1] = 1;
        assert(open_how(&fs, bytes, md_page_size) == -E2BIG);
        bytes[md_page_size - 1] = 0;
        assert(open_how(&fs, bytes, md_page_size + 1) == -E2BIG);
        assert(open_how(&fs, bytes, sizeof(*how) - 1) == -EINVAL);
        assert(open_how(&fs, (void *)1, sizeof(*how)) == -EFAULT);
        how->flags |= 1ULL << 63;
        assert(open_how(&fs, bytes, sizeof(*how)) == -EINVAL);
        how->flags &= ~(1ULL << 63);
    }
    free(bytes);
    assert(unlink(path) == 0);
    puts("PASS openat2 extensible arguments: zero tail, unknown bytes/flags, bad pointers and 4/16 KiB bounds");
    return 0;
}
