#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

int test_kernel_profile_guest(void) {
    alarm(30);
    assert(!getenv("MAGICDESK_TEST_KERNEL_PROFILE"));
    assert(!prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0));
    const char *path="/tmp/kernel-profile-file";
    int fd=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(fd>=0);
    assert(!syscall(452 /* fchmodat2 */,AT_FDCWD,path,0640,0));
    struct stat value; assert(!fstat(fd,&value) && (value.st_mode&0777)==0640);
    assert(!syscall(452,fd,"",0600,AT_EMPTY_PATH));
    assert(!fstat(fd,&value) && (value.st_mode&0777)==0600);
    close(fd); assert(!unlink(path));
    int process=(int)syscall(SYS_pidfd_open,getpid(),0); assert(process>=0);
    int ns=ioctl(process,0xff09 /* PIDFD_GET_USER_NAMESPACE */,0); assert(ns>=0);
    close(ns); close(process);
    struct statx metadata;
    assert(!syscall(SYS_statx,AT_FDCWD,"/",0,STATX_BASIC_STATS|STATX_MNT_ID|0x4000,&metadata));
    assert((metadata.stx_mask&STATX_MNT_ID) && !(metadata.stx_mask&0x4000));
    /* Guest filters must remain independent of the host test filter. */
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        execl("/bin/test","test","kernel-profile-child",NULL); _exit(127);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS kernel profile guest: metadata, namespaces, statx and independent no_new_privs");
    return 0;
}
int test_kernel_profile_child(void) {
    assert(!getenv("MAGICDESK_TEST_KERNEL_PROFILE"));
    assert(prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0)==1);
    return 0;
}
