#define _GNU_SOURCE
#include "task_descriptor.h"
#include "linux_abi.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

int md_task_pidfd_open(pid_t actor, int *thread_handle) {
    if (actor<=0) return -EINVAL;
    int fd=(int)syscall(SYS_pidfd_open,actor,MD_PIDFD_THREAD);
    int exact=fd>=0;
    if (fd<0 && errno==EINVAL) {
        fd=(int)syscall(SYS_pidfd_open,actor,0);
        if (fd<0 && errno==EINVAL) return -ENOTSUP;
    }
    if (fd>=0 && thread_handle) *thread_handle=exact;
    return fd<0 ? -errno : fd;
}
int md_task_descriptor_needs_export(int error) {
    return error==-ENOTSUP || error==-ENOSYS || error==-EPERM || error==-EACCES;
}
int md_task_descriptor_copy(pid_t actor, int number, int *process) {
    if (actor<=0 || !process) return -EINVAL;
    if (number<0) return -EBADF;
    if (actor==getpid()) {
        int fd=fcntl(number,F_DUPFD_CLOEXEC,0);
        return fd<0 ? -errno : fd;
    }
    if (*process<0) {
        *process=md_task_pidfd_open(actor,NULL);
        if (*process<0) return *process;
    }
    int fd=(int)syscall(SYS_pidfd_getfd,*process,number,0);
    return fd<0 ? -errno : fd;
}
void md_task_descriptor_release(int *process) {
    if (*process>=0) close(*process);
    *process=-1;
}
int md_task_descriptor_pin(pid_t actor,int number) {
    if (actor<=0 || number<0) return -EINVAL;
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/fd/%d",actor,number);
    int fd=open(path,O_PATH|O_CLOEXEC);
    return fd<0 ? -errno : fd;
}
