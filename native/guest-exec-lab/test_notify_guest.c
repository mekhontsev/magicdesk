#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <errno.h>
#include <linux/netlink.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

static volatile sig_atomic_t interrupted;
static void interrupt_receive(int signal) { (void)signal; interrupted=1; }
static void device_receive(int monitor) {
    char data[128];
    struct sockaddr_nl from;
    socklen_t size=sizeof(from);
    struct iovec input={data,sizeof(data)};
    struct msghdr message={.msg_iov=&input,.msg_iovlen=1,.msg_name=&from,.msg_namelen=sizeof(from)};
    assert(recv(monitor,data,sizeof(data),MSG_DONTWAIT)==-1 && errno==EAGAIN);
    assert(recvfrom(monitor,data,sizeof(data),MSG_DONTWAIT,(void *)&from,&size)==-1 && errno==EAGAIN);
    assert(recvmsg(monitor,&message,MSG_DONTWAIT)==-1 && errno==EAGAIN);
    assert(read(monitor,data,sizeof(data))==-1 && errno==EAGAIN);
    assert(readv(monitor,&input,1)==-1 && errno==EAGAIN);
    assert(!fcntl(monitor,F_SETFL,fcntl(monitor,F_GETFL)&~O_NONBLOCK));
    struct sigaction action={.sa_handler=interrupt_receive},previous;
    sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGALRM,&action,&previous));
    /* EVENT_WAIT: recurring signals interrupt the actual blocking receive;
     * the service deadline fails a lost wake, without a startup timing race. */
    const struct itimerval timer={.it_value.tv_usec=50000,.it_interval.tv_usec=50000};
    assert(!setitimer(ITIMER_REAL,&timer,NULL));
    assert(recvmsg(monitor,&message,0)==-1 && errno==EINTR);
    assert(interrupted);
    assert(!setitimer(ITIMER_REAL,&(struct itimerval){0},NULL));
    assert(!sigaction(SIGALRM,&previous,NULL));
    puts("PASS device events: native receive variants, empty queue and interrupted blocking wait");
}

int main(int argc,char **argv) {
    assert(argc==2 && !chdir(argv[1]));
    /* Device observation belongs to the launch even without Android input. */
    int monitor=socket(AF_NETLINK,SOCK_RAW|SOCK_NONBLOCK|SOCK_CLOEXEC,NETLINK_KOBJECT_UEVENT);
    assert(monitor>=0);
    struct sockaddr_nl events={.nl_family=AF_NETLINK,.nl_groups=2};
    assert(!bind(monitor,(void *)&events,sizeof(events)));
    socklen_t event_size=sizeof(events);
    assert(!getsockname(monitor,(void *)&events,&event_size) && event_size==sizeof(events)
        && events.nl_family==AF_NETLINK && events.nl_groups==2 && events.nl_pid);
    int protocol=0; socklen_t protocol_size=sizeof(protocol);
    assert(!getsockopt(monitor,SOL_SOCKET,SO_PROTOCOL,&protocol,&protocol_size) && protocol==NETLINK_KOBJECT_UEVENT);
    device_receive(monitor); close(monitor);
    const char *address=getenv("NOTIFY_SOCKET");
    struct sockaddr_un to={.sun_family=AF_UNIX},from={.sun_family=AF_UNIX};
    assert(address && strlen(address)<sizeof(to.sun_path));
    strcpy(to.sun_path,address);
    socklen_t length=offsetof(struct sockaddr_un,sun_path)+strlen(address)+1;
    if (*address=='@') { to.sun_path[0]=0; --length; }
    int notify=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0); assert(notify>=0);
    int size=snprintf(from.sun_path+1,sizeof(from.sun_path)-1,"md-fdstore-%d",getpid());
    assert(size>0 && !bind(notify,(void *)&from,offsetof(struct sockaddr_un,sun_path)+1+size));
    if (!access("fdstore-created",F_OK)) {
        assert(getenv("LISTEN_PID") && atoi(getenv("LISTEN_PID"))==getpid());
        assert(getenv("LISTEN_FDS") && atoi(getenv("LISTEN_FDS"))==2);
        assert(getenv("LISTEN_FDNAMES") && !strcmp(getenv("LISTEN_FDNAMES"),"test-state:test-state"));
        unsigned files=0,sockets=0;
        for (int fd=3;fd<5;++fd) {
            struct stat st; assert(!fstat(fd,&st));
            char data[32]={0};
            if (S_ISREG(st.st_mode)) {
                assert(lseek(fd,0,SEEK_CUR)==3 && read(fd,data,sizeof(data))==12);
                assert(!strcmp(data,"stored-state")); ++files;
            } else {
                assert(S_ISSOCK(st.st_mode) && recv(fd,data,sizeof(data),0)==12);
                assert(!strcmp(data,"queued-state") && !recv(fd,data,sizeof(data),0)); ++sockets;
            }
            close(fd);
        }
        assert(files==1 && sockets==1);
        int marker=open("fdstore-restored",O_CREAT|O_WRONLY|O_TRUNC,0600); assert(marker>=0);
        static const char passed[]="PASS descriptor store restart\n";
        assert(write(marker,passed,sizeof(passed)-1)==sizeof(passed)-1); close(marker);
    } else {
        int pair[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
        assert(send(pair[1],"queued-state",12,0)==12); close(pair[1]);
        int fd=memfd_create("test-state",MFD_CLOEXEC); assert(fd>=0);
        assert(write(fd,"---stored-state",15)==15 && lseek(fd,3,SEEK_SET)==3);
        int fds[]={fd,pair[0]};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(fds))]; } control={0};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(sizeof(fds))};
        memcpy(CMSG_DATA(&control.align),fds,sizeof(fds));
        char body[]="FDSTORE=1\nFDNAME=test-state\nFDPOLL=0"; struct iovec io={body,sizeof(body)-1};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_name=&to,.msg_namelen=length,
            .msg_control=&control,.msg_controllen=sizeof(control)};
        assert(sendmsg(notify,&message,MSG_NOSIGNAL)==sizeof(body)-1); close(fd); close(pair[0]);
        int marker=open("fdstore-created",O_CREAT|O_WRONLY,0600); assert(marker>=0); close(marker);
    }
    ssize_t sent=sendto(notify,"READY=1",7,MSG_NOSIGNAL,(void *)&to,length);
    if (sent<0) perror("notify READY sendto");
    assert(sent==7); close(notify);
    /* EVENT_WAIT: systemd service termination; the outer launch deadline
     * cancels a missing restart instead of manufacturing a successful result. */
    pause();
    return 1;
}
