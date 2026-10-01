#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"ipc-credentials line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static void peer(int fd, pid_t pid, uid_t uid, gid_t gid) {
    struct ucred c; socklen_t n=sizeof(c);
    CHECK(!getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&c,&n));
    if (c.pid!=pid || c.uid!=uid || c.gid!=gid) fprintf(stderr,"peer actual=%d:%u:%u expected=%d:%u:%u\n",c.pid,c.uid,c.gid,pid,uid,gid);
    CHECK(n==sizeof(c) && c.pid==pid && c.uid==uid && c.gid==gid);
}
static void done(pid_t pid) {
    int s;
    /* EVENT_WAIT: child exit; the guest launch deadline bounds a stuck fixture. */
    CHECK(waitpid(pid,&s,0)==pid && WIFEXITED(s) && WEXITSTATUS(s)==0);
}
static void stream(int abstract) {
    int server=socket(AF_UNIX,SOCK_STREAM,0); CHECK(server>=0);
    struct sockaddr_un a={.sun_family=AF_UNIX};
    snprintf(a.sun_path+abstract,sizeof(a.sun_path)-abstract,"%smd-ipc-%d",abstract?"":"/tmp/",getpid());
    socklen_t n=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+abstract+strlen(a.sun_path+abstract)+(abstract?0:1));
    CHECK(!bind(server,(void *)&a,n));
    if (!abstract) CHECK(!chmod(a.sun_path,0777));
    CHECK(!listen(server,4));
    int ready[2]; CHECK(!pipe(ready));
    pid_t parent=getpid(), child=fork(); CHECK(child>=0);
    if (!child) {
        CHECK(!setgid(2345) && !setuid(1234));
        int client=socket(AF_UNIX,SOCK_STREAM,0); CHECK(client>=0);
        CHECK(!connect(client,(void *)&a,n));
        peer(client,parent,0,0);
        errno=0; CHECK(connect(client,(void *)&a,n)==-1 && errno==EISCONN);
        peer(client,parent,0,0);
        struct sockaddr_un local; socklen_t length=sizeof(local);
        CHECK(!getsockname(client,(void *)&local,&length)); CHECK(length==offsetof(struct sockaddr_un,sun_path));
        CHECK(write(ready[1],"r",1)==1);
        char b; CHECK(read(client,&b,1)==1 && b=='q');
        close(client); _exit(0);
    }
    close(ready[1]); char b;
    /* EVENT_WAIT: connect has captured the listener credentials, before accept. */
    CHECK(read(ready[0],&b,1)==1);
    int client=accept(server,NULL,NULL); CHECK(client>=0);
    peer(client,child,1234,2345);
    CHECK(!setresuid(0,2000,0)); peer(client,child,1234,2345); CHECK(!setresuid(0,0,0));
    int copy=dup(client); CHECK(copy>=0); close(client); peer(copy,child,1234,2345);
    CHECK(write(copy,"q",1)==1); done(child);
    peer(copy,child,1234,2345);
    close(copy); close(server); close(ready[0]); if (!abstract) CHECK(!unlink(a.sun_path));
}
static void send_identity(int fd, uid_t uid, int right, int success) {
    char data='m'; struct iovec io={&data,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))+CMSG_SPACE(sizeof(int))]; } c={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=c.bytes,
        .msg_controllen=CMSG_SPACE(sizeof(struct ucred))+(right>=0?CMSG_SPACE(sizeof(int)):0)};
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(struct ucred)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS};
    struct ucred cred={getpid(),uid,getgid()}; memcpy(CMSG_DATA(h),&cred,sizeof(cred));
    if (right>=0) {
        h=(void *)(c.bytes+CMSG_SPACE(sizeof(cred)));
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        memcpy(CMSG_DATA(h),&right,sizeof(right));
    }
    if (success) CHECK(sendmsg(fd,&m,0)==1);
    else { errno=0; CHECK(sendmsg(fd,&m,0)==-1 && errno==EPERM); }
}
static void receive_identity(int fd, pid_t pid, uid_t uid, int flags, int batch, int rights) {
    char data; struct iovec io={&data,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))+CMSG_SPACE(sizeof(int))]; } c={0};
    struct mmsghdr m={.msg_hdr={.msg_iov=&io,.msg_iovlen=1,.msg_control=c.bytes,
        .msg_controllen=CMSG_SPACE(sizeof(struct ucred))+(rights?CMSG_SPACE(sizeof(int)):0)}};
    CHECK((batch?recvmmsg(fd,&m,1,flags,NULL):recvmsg(fd,&m.msg_hdr,flags))==1 && data=='m');
    int seen=0, descriptors=0;
    for (struct cmsghdr *h=CMSG_FIRSTHDR(&m.msg_hdr);h;h=CMSG_NXTHDR(&m.msg_hdr,h)) {
        CHECK(h->cmsg_level==SOL_SOCKET);
        if (h->cmsg_type==SCM_CREDENTIALS) {
            struct ucred value; memcpy(&value,CMSG_DATA(h),sizeof(value));
            CHECK(value.pid==pid && value.uid==uid && value.gid==0); seen++;
        } else {
            CHECK(h->cmsg_type==SCM_RIGHTS && h->cmsg_len==CMSG_LEN(sizeof(int)));
            int right; memcpy(&right,CMSG_DATA(h),sizeof(right));
            peer(right,getpid(),0,0); close(right); descriptors++;
        }
    }
    CHECK(seen==1 && descriptors==rights && !(m.msg_hdr.msg_flags&MSG_CTRUNC));
}
static void messages(void) {
    int pair[2], transferred[2];
    CHECK(!socketpair(AF_UNIX,SOCK_STREAM,0,pair) && !socketpair(AF_UNIX,SOCK_STREAM,0,transferred));
    int one=1; CHECK(!setsockopt(pair[0],SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        send_identity(pair[1],0,transferred[0],1);
        CHECK(!setuid(1234));
        send_identity(pair[1],0,-1,0);
        send_identity(pair[1],1234,-1,1);
        _exit(0);
    }
    done(child); // Credentials must describe send-time identity after sender exit.
    receive_identity(pair[0],child,0,MSG_PEEK|MSG_CMSG_CLOEXEC,0,1);
    receive_identity(pair[0],child,0,MSG_CMSG_CLOEXEC,1,1);
    receive_identity(pair[0],child,1234,0,0,0);
    close(pair[0]); close(pair[1]); close(transferred[0]); close(transferred[1]);
    puts("PASS explicit message credentials: queued identity, rejected forgery, peek, recv batch, SCM_RIGHTS");
}
static void truncation(void) {
    int p[2], one=1; CHECK(!socketpair(AF_UNIX,SOCK_STREAM,0,p));
    CHECK(!setsockopt(p[0],SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    const size_t capacities[]={1,sizeof(struct cmsghdr),CMSG_LEN(sizeof(struct ucred))-1,CMSG_SPACE(sizeof(struct ucred))};
    for (unsigned i=0;i<sizeof(capacities)/sizeof(*capacities);i++) {
        send_identity(p[1],0,-1,1);
        union { struct cmsghdr align; char bytes[128]; } control={0};
        char data; struct iovec io={&data,1};
        struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=capacities[i]};
        CHECK(recvmsg(p[0],&m,MSG_CMSG_CLOEXEC)==1 && data=='m');
        CHECK(m.msg_controllen<=capacities[i]);
        CHECK(!!(m.msg_flags&MSG_CTRUNC)==(capacities[i]<CMSG_LEN(sizeof(struct ucred))));
        for (struct cmsghdr *h=CMSG_FIRSTHDR(&m);h;h=CMSG_NXTHDR(&m,h))
            CHECK(h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_CREDENTIALS);
    }
    for (unsigned passcred=0;passcred<2;passcred++) {
        CHECK(!setsockopt(p[0],SOL_SOCKET,SO_PASSCRED,&passcred,sizeof(passcred)));
        for (unsigned batch=0;batch<2;batch++) {
            send_identity(p[1],0,-1,1);
            char data; struct iovec io={&data,1};
            struct mmsghdr m={.msg_hdr={.msg_iov=&io,.msg_iovlen=1}};
            CHECK((batch?recvmmsg(p[0],&m,1,0,NULL):recvmsg(p[0],&m.msg_hdr,0))==1 && data=='m');
            CHECK(!m.msg_hdr.msg_controllen && !!(m.msg_hdr.msg_flags&MSG_CTRUNC)==passcred);
        }
    }
    close(p[0]); close(p[1]);
    puts("PASS ancillary truncation: no hidden descriptor publication");
}
static void bound_reuse(void) {
    int listener=socket(AF_UNIX,SOCK_STREAM,0); CHECK(listener>=0);
    struct sockaddr_un target={.sun_family=AF_UNIX}, local={.sun_family=AF_UNIX};
    snprintf(target.sun_path+1,sizeof(target.sun_path)-1,"md-ipc-reuse-server-%d",getpid());
    snprintf(local.sun_path+1,sizeof(local.sun_path)-1,"md-ipc-reuse-client-%d",getpid());
    socklen_t tn=offsetof(struct sockaddr_un,sun_path)+1+strlen(target.sun_path+1);
    socklen_t ln=offsetof(struct sockaddr_un,sun_path)+1+strlen(local.sun_path+1);
    CHECK(!bind(listener,(void *)&target,tn) && !listen(listener,4));
    int retained[2];
    for (unsigned i=0;i<2;i++) {
        CHECK(!setresuid(0,i?1234:0,0));
        int client=socket(AF_UNIX,SOCK_STREAM,0); CHECK(client>=0);
        CHECK(!bind(client,(void *)&local,ln) && !connect(client,(void *)&target,tn));
        CHECK(!setresuid(0,0,0));
        retained[i]=accept(listener,NULL,NULL); CHECK(retained[i]>=0);
        peer(retained[i],getpid(),i?1234:0,0); close(client);
    }
    peer(retained[0],getpid(),0,0); peer(retained[1],getpid(),1234,0);
    close(retained[0]); close(retained[1]); close(listener);
    puts("PASS bound-name reuse retains old connection identity");
}
static void external(const char *name,pid_t server) {
    int fd=socket(AF_UNIX,SOCK_STREAM,0); CHECK(fd>=0);
    struct sockaddr_un address={.sun_family=AF_UNIX};
    CHECK(strlen(name)<sizeof(address.sun_path)-1);
    memcpy(address.sun_path+1,name,strlen(name));
    CHECK(!connect(fd,(void *)&address,offsetof(struct sockaddr_un,sun_path)+1+strlen(name)));
    peer(fd,server,2000,2000);
    send_identity(fd,0,-1,0);
    /* The actual kernel credentials remain authoritative outside this store. */
    CHECK(!setgid(2000)); send_identity(fd,2000,-1,1);
    close(fd);
}
int main(int argc,char **argv) {
    CHECK(getuid()==0);
    if (argc==4 && !strcmp(argv[1],"--external")) { external(argv[2],(pid_t)atoi(argv[3])); return 0; }
    CHECK(argc==1);
    gid_t groups[]={7,11,19}; CHECK(!setgroups(3,groups));
    int p[2]; CHECK(!socketpair(AF_UNIX,SOCK_STREAM,0,p));
    peer(p[0],getpid(),0,0); peer(p[1],getpid(),0,0);
    gid_t actual[3]; socklen_t n=sizeof(actual);
    CHECK(!getsockopt(p[0],SOL_SOCKET,SO_PEERGROUPS,actual,&n));
    CHECK(n==sizeof(actual) && !memcmp(actual,groups,sizeof(groups)));
    n=sizeof(gid_t); errno=0;
    CHECK(getsockopt(p[0],SOL_SOCKET,SO_PEERGROUPS,actual,&n)==-1 && errno==ERANGE && n==sizeof(actual));
    struct ucred short_value={0}; n=sizeof(pid_t);
    CHECK(!getsockopt(p[0],SOL_SOCKET,SO_PEERCRED,&short_value,&n) && n==sizeof(pid_t) && short_value.pid==getpid());
    CHECK(!setgroups(0,NULL)); peer(p[0],getpid(),0,0);
    n=sizeof(actual); CHECK(!getsockopt(p[0],SOL_SOCKET,SO_PEERGROUPS,actual,&n) && n==sizeof(actual));
    close(p[0]); close(p[1]);
    stream(0); stream(1);
    messages();
    truncation();
    bound_reuse();
    puts("PASS connection snapshots: pair, groups, pathname, abstract, fork, drop, dup, peer exit");
    return 0;
}
