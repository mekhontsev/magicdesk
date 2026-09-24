#define _GNU_SOURCE
#include "anonymous_buffer.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(void) {
    int fd = syscall(SYS_memfd_create, "buffer-access-fixture", MFD_CLOEXEC);
    assert(fd >= 0);
    char original[MDH_LABEL_BYTES], after[MDH_LABEL_BYTES];
    if (mdh_buffer_label(fd, original) < 0) { close(fd); return 77; }
    MdhBufferAccess access;
    int probe = mdh_buffer_access_init(&access, original);
    assert(probe >= 0);
    char bytes[4]; assert(pread(probe, bytes, 4, 0) == 4 && !memcmp(bytes, "MDWB", 4));
    assert(!strcmp(access.source, original) && !strcmp(access.target, original));
    assert(mdh_buffer_admit(fd, &access) == 0);
    strcpy(access.source, "different-source"); strcpy(access.target, "different-recipient");
    assert(mdh_buffer_admit(fd, &access) == -1 && errno == EACCES);
    assert(mdh_buffer_label(fd, after) == 0 && !strcmp(original, after));
    int pipefd[2]; assert(pipe2(pipefd, O_CLOEXEC) == 0);
    assert(mdh_buffer_admit(pipefd[0], &access) == 0);
    assert(mdh_buffer_admit(pipefd[1], &access) == 0);
    int linked = open("/dev/null", O_RDONLY | O_CLOEXEC); assert(linked >= 0);
    assert(mdh_buffer_admit(linked, &access) == 0);
    close(linked); close(pipefd[0]); close(pipefd[1]); close(probe); close(fd);
    puts("Anonymous buffers: own label, probe bytes, foreign-label rejection and non-buffer preservation passed");
}
