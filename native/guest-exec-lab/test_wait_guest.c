#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/prctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void epoll_custody(void) {
    int fd=eventfd(1,EFD_CLOEXEC|EFD_NONBLOCK),ep=epoll_create1(EPOLL_CLOEXEC);
    assert(fd>=0 && ep>=0);
    int duplicate=fcntl(ep,F_DUPFD_CLOEXEC,0); assert(duplicate>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=UINT64_MAX},out;
    assert(!epoll_ctl(duplicate,EPOLL_CTL_ADD,fd,&event));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        int result=epoll_wait(ep,&out,1,0);
        if (result!=1 || out.data.u64!=UINT64_MAX)
            fprintf(stderr,"epoll custody: result=%d errno=%d events=%x data=%llx\n",
                result,errno,out.events,(unsigned long long)out.data.u64);
        assert(result==1 && out.data.u64==UINT64_MAX);
        event.data.u64=0;
        assert(!epoll_ctl(duplicate,EPOLL_CTL_MOD,fd,&event));
        _exit(0);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(epoll_wait(ep,&out,1,0)==1 && out.data.u64==0);
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,pair));
    union { struct cmsghdr align; char data[CMSG_SPACE(2*sizeof(int))]; } control={0};
    char byte='e'; struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.data,.msg_controllen=sizeof(control.data)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS;
    header->cmsg_len=CMSG_LEN(2*sizeof(int));
    int *rights=(void *)CMSG_DATA(header); rights[0]=ep; rights[1]=duplicate;
    assert(sendmsg(pair[0],&message,MSG_NOSIGNAL)==1);
    assert(!close(ep) && !close(duplicate));
    assert(recvmsg(pair[1],&message,MSG_CMSG_CLOEXEC)==1
        && !(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
    header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(2*sizeof(int)));
    rights=(void *)CMSG_DATA(header);
    for (int i=0;i<2;++i) {
        assert(fcntl(rights[i],F_GETFD)==FD_CLOEXEC);
        assert(epoll_wait(rights[i],&out,1,0)==1 && out.data.u64==0);
    }
    assert(!epoll_ctl(rights[0],EPOLL_CTL_DEL,fd,NULL));
    assert(!epoll_wait(rights[1],&out,1,0));
    close(rights[0]); close(rights[1]); close(pair[0]); close(pair[1]); close(fd);
    puts("PASS native epoll custody: dup/fork and queued rights after sender aliases close");
}
static void sandbox(void) {
    struct sock_filter filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)<0 && errno==EPERM);
}
int main(int argc,char **argv) {
    assert(argc==2);
    int filtered=atoi(argv[1]);
    int ep=epoll_create1(EPOLL_CLOEXEC),fd=eventfd(1,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(ep>=0 && fd>=0 && fd<FD_SETSIZE);
    struct epoll_event event={.events=EPOLLIN,.data.u64=UINT64_C(0x1020304050607080)},out;
    assert(!epoll_ctl(ep,EPOLL_CTL_ADD,fd,&event));
    if (filtered) sandbox();
    epoll_custody();
    struct timespec start,end; assert(!clock_gettime(CLOCK_MONOTONIC,&start));
    for (unsigned i=0;i<10000;++i) {
        struct pollfd p={fd,POLLIN,0};
        struct timespec zero={0};
        assert(syscall(SYS_ppoll,&p,1,&zero,NULL,8)==1 && p.revents==POLLIN);
        assert(syscall(SYS_epoll_pwait,ep,&out,1,0,NULL,8)==1);
        assert(out.events==EPOLLIN && out.data.u64==event.data.u64);
        assert(syscall(SYS_epoll_pwait2,ep,&out,1,&zero,NULL,8)==1);
        assert(out.events==EPOLLIN && out.data.u64==event.data.u64);
        fd_set read; FD_ZERO(&read); FD_SET(fd,&read);
        assert(syscall(SYS_pselect6,fd+1,&read,NULL,NULL,&zero,NULL)==1 && FD_ISSET(fd,&read));
    }
    assert(!clock_gettime(CLOCK_MONOTONIC,&end));
    close(fd); close(ep);
    uint64_t ns=(end.tv_sec-start.tv_sec)*UINT64_C(1000000000)+end.tv_nsec-start.tv_nsec;
    printf("PASS native wait fast paths filtered=%d iterations=10000 elapsedNs=%llu\n",filtered,(unsigned long long)ns);
    return 0;
}
