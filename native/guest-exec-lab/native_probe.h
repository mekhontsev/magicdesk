#ifndef MD_TEST_NATIVE_PROBE_H
#define MD_TEST_NATIVE_PROBE_H
#include "kernel_profile.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static inline int md_test_capture_socket(int fd) {
    assert(fd>=0 && fcntl(MD_TEST_CAPTURE_FD,F_GETFD)==-1 && errno==EBADF);
    assert(dup3(fd,MD_TEST_CAPTURE_FD,O_CLOEXEC)==MD_TEST_CAPTURE_FD);
    assert(!close(fd));
    return MD_TEST_CAPTURE_FD;
}
static inline void md_test_protected_proc_probe(void) {
    int ready[2], done[2];
    assert(!pipe(ready) && !pipe(done));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(done[1]);
        assert(!prctl(PR_SET_DUMPABLE,0,0,0,0));
        assert(write(ready[1],"r",1)==1);
        char byte;
        /* EVENT_WAIT: parent finishes inspecting the protected child; the
         * fixture command deadline terminates both on a missing response. */
        assert(read(done[0],&byte,1)==1);
        _exit(0);
    }
    close(ready[1]); close(done[0]);
    char byte, path[64];
    /* EVENT_WAIT: child publishes its non-dumpable state before the probe. */
    assert(read(ready[0],&byte,1)==1);
    snprintf(path,sizeof(path),"/proc/%d/cmdline",child);
    int fd=open(path,O_RDONLY|O_CLOEXEC), error=fd<0 ? errno : 0;
    if (fd>=0) {
        ssize_t n=read(fd,&byte,1);
        if (n<0) error=errno; else assert(n==1);
        assert(!close(fd));
    }
    assert(write(done[1],"d",1)==1);
    close(ready[0]); close(done[1]);
    int status;
    /* EVENT_WAIT: exact child termination, bounded by the command deadline. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    if (!error) exit(0);
    assert(error==ENOENT || error==EPERM || error==EACCES);
    fprintf(stderr,"UNAVAILABLE protected-proc errno=%d\n",error);
    exit(77);
}

/* Run before the fixture: inherited seccomp may kill this process outright.
 * Only this explicit capability probe may classify SIGSYS as unavailable. */
static inline void md_test_native_probe(int argc,char **argv) {
    if (argc!=2) return;
    if (!strcmp(argv[1],"--probe-syscall-skip")) {
        assert(syscall(-1)==-1 && errno==ENOSYS);
        exit(0);
    }
    if (!strcmp(argv[1],"--probe-protected-proc")) md_test_protected_proc_probe();
    if (!strcmp(argv[1],"--probe-executable-metadata")) {
        struct stat st;
        assert(!stat("/proc/self/exe",&st));
        if (!chmod("/proc/self/exe",st.st_mode&07777)) exit(0);
        assert(errno==EPERM || errno==EACCES || errno==EROFS);
        fprintf(stderr,"UNAVAILABLE executable-metadata errno=%d\n",errno);
        exit(77);
    }
    if (!strcmp(argv[1],"--probe-faccessat2")) {
        if (!syscall(SYS_faccessat2,AT_FDCWD,".",R_OK,AT_EACCESS)) exit(0);
        assert(errno==ENOSYS || errno==EPERM || errno==EACCES);
        fprintf(stderr,"UNAVAILABLE faccessat2 errno=%d\n",errno);
        exit(77);
    }
    if (strcmp(argv[1],"--probe-openat2")) return;
    struct { uint64_t flags,mode,resolve; } how={.flags=O_PATH|O_CLOEXEC};
    int fd=(int)syscall(SYS_openat2,AT_FDCWD,".",&how,sizeof(how));
    if (fd>=0) { assert(!close(fd)); exit(0); }
    assert(errno==ENOSYS || errno==EPERM || errno==EACCES);
    fprintf(stderr,"UNAVAILABLE openat2 errno=%d\n",errno);
    exit(77);
}
#endif
