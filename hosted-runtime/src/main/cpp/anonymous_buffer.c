#define _GNU_SOURCE
#include "anonymous_buffer.h"
#include <errno.h>
#include <string.h>
#include <linux/memfd.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/xattr.h>
#include <unistd.h>

int mdh_buffer_label(int fd, char label[MDH_LABEL_BYTES]) {
    ssize_t size = fgetxattr(fd, "security.selinux", label, MDH_LABEL_BYTES - 1);
    if (size <= 0) return -1;
    label[size] = 0;
    return 0;
}

int mdh_buffer_admit(int fd, void *context) {
    MdhBufferAccess *access = context;
    struct stat st;
    struct statfs fs;
    if (fstat(fd, &st) < 0 || fstatfs(fd, &fs) < 0) return -1;
    /* Never relabel linked files, devices, pipes or DMA-BUFs. */
    if (!S_ISREG(st.st_mode) || st.st_nlink != 0 || fs.f_type != 0x01021994) return 0;
    char label[MDH_LABEL_BYTES];
    if (mdh_buffer_label(fd, label) < 0) return -1;
    if (!strcmp(label, access->target)) return 0;
    if (strcmp(label, access->source)) { errno = EACCES; return -1; }
    return fsetxattr(fd, "security.selinux", access->target, strlen(access->target) + 1, 0);
}

int mdh_buffer_access_init(MdhBufferAccess *access, const char *target) {
    if (!target || !*target || strlen(target) >= sizeof(access->target)) { errno = EINVAL; return -1; }
    int fd = syscall(SYS_memfd_create, "MagicDesk-buffer-admission", MFD_CLOEXEC);
    if (fd < 0) return -1;
    strcpy(access->target, target);
    if (mdh_buffer_label(fd, access->source) == 0 && mdh_buffer_admit(fd, access) == 0 &&
            write(fd, "MDWB", 4) == 4) return fd;
    int error = errno;
    close(fd); errno = error;
    return -1;
}
