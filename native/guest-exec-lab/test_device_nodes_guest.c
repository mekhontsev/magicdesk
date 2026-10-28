#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <poll.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

static void same(int fd,const struct stat *expected) {
    struct stat st;
    assert(!fstat(fd,&st));
    if (st.st_dev!=expected->st_dev || st.st_ino!=expected->st_ino
            || st.st_rdev!=expected->st_rdev || st.st_mode!=expected->st_mode)
        fprintf(stderr,"device fd=%d actual=%llu:%llu/%llu/%o expected=%llu:%llu/%llu/%o\n",fd,
            (unsigned long long)st.st_dev,(unsigned long long)st.st_ino,(unsigned long long)st.st_rdev,st.st_mode,
            (unsigned long long)expected->st_dev,(unsigned long long)expected->st_ino,
            (unsigned long long)expected->st_rdev,expected->st_mode);
    assert(st.st_dev==expected->st_dev && st.st_ino==expected->st_ino
        && st.st_rdev==expected->st_rdev && st.st_mode==expected->st_mode);
    assert(!fstatat(fd,"",&st,AT_EMPTY_PATH) && st.st_ino==expected->st_ino
        && st.st_dev==expected->st_dev && st.st_rdev==expected->st_rdev && st.st_mode==expected->st_mode);
    struct statx x;
    assert(!syscall(SYS_statx,fd,"",AT_EMPTY_PATH,STATX_BASIC_STATS,&x));
    assert(x.stx_ino==expected->st_ino && x.stx_mode==expected->st_mode
        && makedev(x.stx_dev_major,x.stx_dev_minor)==expected->st_dev
        && makedev(x.stx_rdev_major,x.stx_rdev_minor)==expected->st_rdev);
}
static void full_io(int fd) {
    char data[65553];
    memset(data,1,sizeof(data));
    assert(read(fd,data,sizeof(data))==sizeof(data));
    for (unsigned i=0;i<sizeof(data);++i) assert(!data[i]);
    assert(write(fd,"x",1)<0 && errno==ENOSPC);
    assert(write(fd,"",0)<0 && errno==ENOSPC);
    assert(pwrite(fd,"x",1,19)<0 && errno==ENOSPC);
    struct iovec iov[2]={{data,3},{data+3,5}};
    assert(writev(fd,iov,2)<0 && errno==ENOSPC);
    memset(data,1,8); assert(readv(fd,iov,2)==8);
    for (unsigned i=0;i<8;++i) assert(!data[i]);
    assert(pread(fd,data,8,31)==8);
    assert(lseek(fd,99,SEEK_SET)==0 && lseek(fd,-99,SEEK_CUR)==0 && lseek(fd,99,SEEK_END)==0);
    struct pollfd p={.fd=fd,.events=POLLIN|POLLOUT};
    assert(poll(&p,1,0)==1 && p.revents==(POLLIN|POLLOUT));
}
static void full_transfer(int fd,const struct stat *st) {
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET,0,pair));
    pid_t child=fork(); assert(child>=0);
    char byte='x',control[CMSG_SPACE(sizeof(int))]={0};
    struct iovec iov={&byte,1};
    struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    if (!child) {
        close(pair[0]); close(fd);
        /* EVENT_WAIT: exact descriptor message; the test alarm bounds failure. */
        assert(recvmsg(pair[1],&msg,0)==1);
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg); assert(c && c->cmsg_type==SCM_RIGHTS);
        int received; memcpy(&received,CMSG_DATA(c),sizeof(received));
        same(received,st); full_io(received); close(received); _exit(0);
    }
    close(pair[1]);
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg); c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS;
    c->cmsg_len=CMSG_LEN(sizeof(fd)); memcpy(CMSG_DATA(c),&fd,sizeof(fd));
    assert(sendmsg(pair[0],&msg,0)==1); close(pair[0]);
    int status;
    /* EVENT_WAIT: exact receiver exit; the test alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
int test_device_nodes_guest(void) {
    alarm(45);
    assert(!unshare(CLONE_NEWNS));
    assert(!mkdir("/tmp/devices",0700));
    assert(!mount("none","/tmp/devices","tmpfs",0,NULL));
    const char *root="/tmp/devices";
    assert(!chdir(root));
    assert(!mknod("null",S_IFCHR|0666,makedev(1,3)));
    struct stat original;
    assert(!stat("null",&original) && S_ISCHR(original.st_mode) && original.st_rdev==makedev(1,3));
    int fd=open("null",O_RDWR|O_CLOEXEC); if (fd<0) perror("logical null open"); assert(fd>=0);
    same(fd,&original);
    char data[32]; assert(read(fd,data,sizeof(data))==0 && write(fd,"test",4)==4);
    int copy=dup(fd); assert(copy>=0); same(copy,&original);
    assert(!link("null","alias") && !rename("null","renamed"));
    assert(!unlink("renamed"));
    same(fd,&original);
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",fd);
    int again=open(path,O_RDWR); assert(again>=0); same(again,&original); close(again);
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET,0,pair));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(pair[0]); close(fd); close(copy);
        char byte,control[CMSG_SPACE(sizeof(int))];
        struct iovec iov={&byte,1};
        struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
        /* EVENT_WAIT: one descriptor from parent; alarm bounds a missing sender. */
        assert(recvmsg(pair[1],&msg,0)==1);
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg); assert(c && c->cmsg_type==SCM_RIGHTS);
        int received; memcpy(&received,CMSG_DATA(c),sizeof(received));
        same(received,&original); assert(write(received,"x",1)==1); close(received); _exit(0);
    }
    close(pair[1]);
    char byte='x',control[CMSG_SPACE(sizeof(int))]={0};
    struct iovec iov={&byte,1};
    struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg); c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS;
    c->cmsg_len=CMSG_LEN(sizeof(fd)); memcpy(CMSG_DATA(c),&fd,sizeof(fd));
    assert(sendmsg(pair[0],&msg,0)==1); close(pair[0]);
    int status;
    /* EVENT_WAIT: exact child exit; alarm terminates incomplete FD transfer. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!fchmod(fd,0600)); assert(!stat("alias",&original) && (original.st_mode&0777)==0600);
    same(copy,&original);
    assert(!mknod("zero",S_IFCHR|0666,makedev(1,5)));
    int zero=open("zero",O_RDONLY); assert(zero>=0);
    memset(data,1,sizeof(data)); assert(read(zero,data,sizeof(data))==sizeof(data));
    for (unsigned i=0;i<sizeof(data);++i) assert(!data[i]); close(zero);
    assert(!mknod("full",S_IFCHR|0666,makedev(1,7)));
    int full=open("full",O_RDWR|O_NOFOLLOW); assert(full>=0);
    struct stat full_st;
    assert(!stat("full",&full_st)); same(full,&full_st); full_io(full);
    int full_copy=dup(full); assert(full_copy>=0); full_io(full_copy); close(full_copy);
    assert(!link("full","full-alias"));
    snprintf(path,sizeof(path),"/proc/self/fd/%d",full);
    again=open(path,O_RDWR); assert(again>=0); same(again,&full_st); full_io(again); close(again);
    full_transfer(full,&full_st);
    assert(!fchmod(full,0600)); assert(!stat("full-alias",&full_st)); same(full,&full_st);
    assert(!unlink("full")); full_io(full);
    int canonical=open("/dev/full",O_RDWR|O_NOFOLLOW); assert(canonical>=0);
    full_io(canonical); close(canonical);
    assert(!mknod("unknown",S_IFCHR|0600,makedev(240,123)));
    assert(open("unknown",O_RDONLY)<0 && errno==ENXIO);
    int unknown=open("unknown",O_PATH); assert(unknown>=0); close(unknown);
    assert(!mknod("block",S_IFBLK|0600,makedev(8,0)));
    assert(open("block",O_RDONLY)<0 && errno==ENXIO);
    assert(!mknod("whiteout",S_IFCHR|0600,0));
    assert(!stat("whiteout",&original) && S_ISCHR(original.st_mode) && !original.st_rdev);
    assert(open("whiteout",O_RDONLY)<0 && errno==ENXIO);
    assert(!mount(NULL,root,NULL,MS_REMOUNT|MS_NODEV,NULL));
    assert(open("alias",O_RDONLY)<0 && errno==EACCES);
    assert(open("full-alias",O_RDONLY)<0 && errno==EACCES);
    full_io(full); close(full);
    int pin=open("alias",O_PATH); assert(pin>=0); close(pin);
    assert(write(fd,"x",1)==1); close(fd); close(copy);
    assert(!mount(NULL,root,NULL,MS_REMOUNT,NULL));
    assert(!chmod(root,0777));
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!setuid(1234));
        assert(open("full-alias",O_RDONLY)<0 && errno==EACCES);
        assert(mknod("forbidden",S_IFCHR|0666,makedev(1,3))<0 && errno==EPERM);
        _exit(0);
    }
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!chdir("/") && !umount2(root,0) && !rmdir(root));
    puts("PASS guest device nodes: native IO, metadata, aliases, SCM_RIGHTS, unknown devices, permissions and nodev");
    return 0;
}
