#define _GNU_SOURCE
#include "ipc_io_job.h"
#include "event_wait.h"
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

static void pending_accept(void) {
    struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    int server=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);
    assert(server>=0 && !bind(server,(void *)&address,sizeof(address)) && !listen(server,4));
    socklen_t length=sizeof(address);
    assert(!getsockname(server,(void *)&address,&length));
    struct md_ipc_io_job job={0};
    long args[5]={server,0,0,SOCK_CLOEXEC,0};
    for (unsigned round=0;round<64;++round) {
        assert(!md_ipc_io_job_start(&job,SYS_accept4,args));
        long result=123;
        for (unsigned wake=0;wake<32;++wake) {
            assert(!md_ipc_io_job_collect(&job,&result));
            assert(result==123 && job.started);
        }
        if (round%2) {
            assert(md_ipc_io_job_cancel(&job)==-EINTR);
        } else {
            int client=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0);
            assert(client>=0 && !connect(client,(void *)&address,length));
            /* EVENT_WAIT: accept completion; expiry fails the fixture. */
            assert(!md_event_wait_fd(md_ipc_io_job_event(&job),POLLIN,md_event_now()+1000000000LL));
            assert(md_ipc_io_job_collect(&job,&result) && result>=0);
            assert(!close((int)result) && !close(client));
        }
        assert(!job.started && md_ipc_io_job_event(&job)==-1);
    }
    assert(!close(server));
}

int main(void) {
    alarm(30);
    pending_accept();
    for (unsigned round=0;round<256;++round) {
        int sockets[2], pipefd[2];
        assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,sockets));
        assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        union control { struct cmsghdr align; char bytes[128]; } outgoing={0}, controls[2]={0};
        struct cmsghdr *h=&outgoing.align;
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        memcpy(CMSG_DATA(h),&pipefd[1],sizeof(int));
        char byte='x', bytes[2]={0}; struct iovec vector={&byte,1}, vectors[2];
        struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=&outgoing,.msg_controllen=CMSG_SPACE(sizeof(int))};
        if (round%3) assert(sendmsg(sockets[0],&message,0)==1);
        assert(!close(pipefd[1]));
        struct mmsghdr messages[2]={0};
        for (unsigned i=0;i<2;++i) {
            vectors[i]=(struct iovec){bytes+i,1};
            messages[i]=(struct mmsghdr){.msg_hdr={.msg_iov=vectors+i,.msg_iovlen=1,
                .msg_control=&controls[i],.msg_controllen=sizeof(controls[i])},.msg_len=UINT_MAX};
        }
        struct md_ipc_io_job job={0};
        long args[5]={sockets[1],(long)messages,round%3==1 ? 1 : 2,MSG_CMSG_CLOEXEC,0};
        assert(!md_ipc_io_job_start(&job,SYS_recvmmsg,args));
        if (round%3==0) {
            long pending=123;
            for (unsigned wake=0;wake<32;++wake) {
                assert(!md_ipc_io_job_collect(&job,&pending));
                assert(pending==123 && job.started);
            }
        }
        if (round%3==1) {
            /* EVENT_WAIT: completed receive exercises cancellation after the
             * kernel has installed rights; expiry fails this fixture. */
            assert(!md_event_wait_fd(md_ipc_io_job_event(&job),POLLIN,md_event_now()+1000000000LL));
        }
        long result;
        if (round%3==1 && round%2) {
            assert(md_ipc_io_job_collect(&job,&result));
            assert(!job.started && result==1);
        } else result=md_ipc_io_job_cancel(&job);
        assert(result==-EINTR || result==1);
        unsigned received=0;
        for (unsigned i=0;i<2;++i) if (messages[i].msg_len!=UINT_MAX) {
            assert(messages[i].msg_len==1 && bytes[i]=='x');
            h=CMSG_FIRSTHDR(&messages[i].msg_hdr);
            assert(h && h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS);
            int fd; memcpy(&fd,CMSG_DATA(h),sizeof(fd)); assert(!close(fd)); ++received;
        }
        assert(received==(result==1));
        assert(!close(sockets[0]) && !close(sockets[1]));
        assert(read(pipefd[0],&byte,1)==0 && !close(pipefd[0]));
    }
    puts("PASS native IO interruption, completion race and FD custody");
    return 0;
}
