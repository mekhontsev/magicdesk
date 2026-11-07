#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <linux/stat.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
static void empty_readlink(int fd) {
    char text[32];
    errno=0; assert(readlinkat(fd,"",text,sizeof(text))==-1 && errno==ENOENT);
    errno=0; assert(readlinkat(fd,"",text,0)==-1 && errno==EINVAL);
}
int test_native_metadata_guest(void) {
    int fd=syscall(SYS_memfd_create,"metadata",MFD_CLOEXEC|MFD_ALLOW_SEALING); assert(fd>=0);
    assert(!ftruncate(fd,6));
    struct stat st; assert(!fstat(fd,&st));
    empty_readlink(fd);
    for (unsigned i=0;i<2;++i) {
        struct statx x={0}; unsigned mask=STATX_ALL|(i ? STATX_MNT_ID : 0);
        int r=syscall(SYS_statx,fd,"",AT_EMPTY_PATH,mask,&x);
        printf("memfd statx mask=%x result=%d errno=%d returned=%x mount=%llu\n",mask,r,errno,x.stx_mask,(unsigned long long)x.stx_mnt_id);
        fflush(stdout); assert(!r && x.stx_size==6 && x.stx_ino==st.st_ino);
    }
    close(fd);
    int pipefd[2]; assert(!pipe2(pipefd,O_CLOEXEC));
    empty_readlink(pipefd[0]); empty_readlink(pipefd[1]); close(pipefd[0]); close(pipefd[1]);
    puts("PASS native metadata: anonymous memfd statx and empty-path non-links"); return 0;
}
