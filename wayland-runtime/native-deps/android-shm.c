#include "util/shm.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/memfd.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int allocate_shm_file(size_t size) {
    if (size > INT64_MAX) { errno = EFBIG; return -1; }
    int descriptor = syscall(SYS_memfd_create, "magicdesk-wlroots", MFD_CLOEXEC);
    if (descriptor < 0) return -1;
    int result;
    do { result = ftruncate(descriptor, (off_t)size); } while (result < 0 && errno == EINTR);
    if (result < 0) {
        int error = errno;
        close(descriptor);
        errno = error;
        return -1;
    }
    return descriptor;
}

bool allocate_shm_file_pair(size_t size, int *writable, int *readonly) {
    if (size > INT64_MAX) { errno = EFBIG; return false; }
    const char *runtime = getenv("XDG_RUNTIME_DIR");
    if (!runtime || runtime[0] != '/') { errno = EINVAL; return false; }
    int directory = open(runtime, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (directory < 0) return false;
    int writer = -1, reader = -1;
    struct stat status;
    if (fstat(directory, &status) < 0) goto fail;
    if (status.st_uid != getuid() || (status.st_mode & 0777) != 0700) {
        errno = EACCES;
        goto fail;
    }
    uint64_t random;
    arc4random_buf(&random, sizeof(random));
    char name[64];
    snprintf(name, sizeof(name), ".magicdesk-shm-%016" PRIx64, random);
    writer = openat(directory, name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (writer < 0) goto fail;
    reader = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    int open_error = errno;
    int removed = unlinkat(directory, name, 0);
    if (reader < 0) { errno = open_error; goto fail; }
    if (removed < 0 || fchmod(writer, 0) < 0) goto fail;
    int result;
    do { result = ftruncate(writer, (off_t)size); } while (result < 0 && errno == EINTR);
    if (result < 0) goto fail;
    close(directory);
    *writable = writer;
    *readonly = reader;
    return true;
fail:
    {
        int error = errno;
        if (writer >= 0) close(writer);
        if (reader >= 0) close(reader);
        close(directory);
        errno = error;
        return false;
    }
}