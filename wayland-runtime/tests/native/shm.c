#include "util/shm.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

int main(void) {
    const char *temporary = getenv("TMPDIR");
    char directory[4096];
    snprintf(directory, sizeof(directory), "%s/mdw-shm-XXXXXX", temporary ? temporary : "/tmp");
    assert(mkdtemp(directory));
    assert(setenv("XDG_RUNTIME_DIR", directory, 1) == 0);
    int writer = -1, reader = -1;
    assert(!allocate_shm_file_pair(SIZE_MAX, &writer, &reader));
    assert(errno == EFBIG && writer == -1 && reader == -1);
    assert(allocate_shm_file_pair(4096, &writer, &reader));
    assert((fcntl(writer, F_GETFD) & FD_CLOEXEC) != 0);
    assert((fcntl(reader, F_GETFD) & FD_CLOEXEC) != 0);
    assert((fcntl(reader, F_GETFL) & O_ACCMODE) == O_RDONLY);
    uint32_t value = 0x12345678, received = 0;
    assert(pwrite(writer, &value, sizeof(value), 0) == sizeof(value));
    assert(pread(reader, &received, sizeof(received), 0) == sizeof(received));
    assert(received == value);
    assert(pwrite(reader, &value, sizeof(value), 0) < 0);
    assert(mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, reader, 0) == MAP_FAILED);
    struct stat status;
    assert(fstat(reader, &status) == 0 && (status.st_mode & 0777) == 0);
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", reader);
    assert(open(path, O_RDWR | O_CLOEXEC) < 0);
    close(reader);
    close(writer);
    assert(rmdir(directory) == 0);
    puts("Android shared-memory FD ownership passed");
    return 0;
}