#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"ipc-launch line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static pid_t server;
static void cleanup(void) { if (server>0) { kill(server,SIGTERM); waitpid(server,NULL,0); } }
static void client(const char *run,const char *store,const char *user,int success,const char *address) {
    pid_t pid=fork(); CHECK(pid>=0);
    if (!pid) {
        char bus[300]; CHECK(snprintf(bus,sizeof(bus),"--bus=%s",address)<(int)sizeof(bus));
        execl(run,run,"--store",store,"--user",user,"--deadline-seconds","15","--",
            "/usr/bin/dbus-send",bus,"--print-reply","--dest=org.freedesktop.DBus","/",
            "org.freedesktop.DBus.ListNames",NULL); _exit(127);
    }
    int status;
    /* EVENT_WAIT: each supervised client's exit, bounded by its launch deadline. */
    CHECK(waitpid(pid,&status,0)==pid && WIFEXITED(status));
    CHECK(success?!WEXITSTATUS(status):WEXITSTATUS(status)==1);
}
static void external(char **argv) {
    int fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0); CHECK(fd>=0);
    struct sockaddr_un a={.sun_family=AF_UNIX};
    snprintf(a.sun_path+1,sizeof(a.sun_path)-1,"md-ipc-native-%d",getpid());
    CHECK(!bind(fd,(void *)&a,offsetof(struct sockaddr_un,sun_path)+1+strlen(a.sun_path+1)) && !listen(fd,1));
    int one=1; CHECK(!setsockopt(fd,SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    char parent[32]; snprintf(parent,sizeof(parent),"%d",getpid());
    server=fork(); CHECK(server>=0);
    if (!server) {
        execl(argv[1],argv[1],"--store",argv[2],"--user","0:0","--deadline-seconds","15",
            "--bind-ro",argv[3],"/fixture","--","/fixture/libmagicdesk_guest_ipc_credentials_guest.so",
            "--external",a.sun_path+1,parent,NULL); _exit(127);
    }
    /* EVENT_WAIT: guest connection/data; socket timeouts bound a failed client. */
    struct timeval timeout={.tv_sec=20};
    CHECK(!setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
    int peerfd=accept4(fd,NULL,NULL,SOCK_CLOEXEC); CHECK(peerfd>=0);
    CHECK(!setsockopt(peerfd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
    char data; struct iovec io={&data,1};
    union { struct cmsghdr align; char bytes[128]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
    CHECK(recvmsg(peerfd,&m,0)==1 && data=='m');
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    CHECK(h && h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_CREDENTIALS && !CMSG_NXTHDR(&m,h));
    struct ucred value; memcpy(&value,CMSG_DATA(h),sizeof(value));
    CHECK(value.uid==2000 && value.gid==2000 && !(m.msg_flags&MSG_CTRUNC));
    int status; CHECK(waitpid(server,&status,0)==server && WIFEXITED(status) && !WEXITSTATUS(status)); server=0;
    close(peerfd); close(fd);
    puts("PASS external endpoint: actual shell credentials, rejected root claim, no transport token");
}
int main(int argc,char **argv) {
    CHECK(argc==4 && getuid()==2000); atexit(cleanup);
    external(argv);
    int pipefd[2]; CHECK(!pipe(pipefd));
    server=fork(); CHECK(server>=0);
    if (!server) {
        close(pipefd[0]); CHECK(dup2(pipefd[1],STDOUT_FILENO)>=0); close(pipefd[1]);
        execl(argv[1],argv[1],"--store",argv[2],"--user","0:0","--deadline-seconds","40","--",
            "/usr/bin/dbus-daemon","--session","--nofork","--print-address=1",NULL); _exit(127);
    }
    close(pipefd[1]); FILE *ready=fdopen(pipefd[0],"r"); CHECK(ready);
    char address[256];
    /* EVENT_WAIT: stock daemon publishes its listen address; server deadline closes the pipe on failure. */
    CHECK(fgets(address,sizeof(address),ready)); fclose(ready);
    address[strcspn(address,"\n")]=0; CHECK(!strncmp(address,"unix:",5));
    client(argv[1],argv[2],"0:0",1,address);
    client(argv[1],argv[2],"65534:65534",0,address);
    client(argv[1],argv[2],"0:0",1,address);
    puts("PASS independent launches: shared root bus, different-user rejection, subsequent root client");
    return 0;
}
