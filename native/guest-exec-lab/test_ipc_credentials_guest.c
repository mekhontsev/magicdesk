#define _GNU_SOURCE
#include "native_probe.h"
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/capability.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <netinet/in.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"ipc-credentials line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static void unchanged_descriptors(const int *before,unsigned count) {
    unsigned changed=0;
    for (unsigned i=0;i<count;++i) {
        int flags=fcntl(i,F_GETFD);
        if (flags==before[i]) continue;
        char path[64],target[256]; snprintf(path,sizeof(path),"/proc/self/fd/%u",i);
        ssize_t n=readlink(path,target,sizeof(target)-1); target[n<0 ? 0 : n]=0;
        fprintf(stderr,"descriptor fd=%u before=%d after=%d target=%s\n",i,before[i],flags,target);
        ++changed;
    }
    CHECK(!changed);
}
static void peer(int fd, pid_t pid, uid_t uid, gid_t gid) {
    struct ucred c; socklen_t n=sizeof(c);
    CHECK(!getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&c,&n));
    if (c.pid!=pid || c.uid!=uid || c.gid!=gid) fprintf(stderr,"peer actual=%d:%u:%u expected=%d:%u:%u\n",c.pid,c.uid,c.gid,pid,uid,gid);
    CHECK(n==sizeof(c) && c.pid==pid && c.uid==uid && c.gid==gid);
}
static void done(pid_t pid) {
    int s;
    /* EVENT_WAIT: child exit; the guest launch deadline bounds a stuck fixture. */
    CHECK(waitpid(pid,&s,0)==pid);
    if (!WIFEXITED(s) || WEXITSTATUS(s)) fprintf(stderr,"child %d status=%#x\n",pid,s);
    CHECK(WIFEXITED(s) && WEXITSTATUS(s)==0);
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
static void socket_paths(void) {
    CHECK(!mkdir("/tmp/socket-paths",0700));
    int dir=open("/tmp/socket-paths",O_PATH|O_DIRECTORY|O_CLOEXEC); CHECK(dir>=0);
    CHECK(!rename("/tmp/socket-paths","/tmp/socket-moved"));
    pid_t parent=getpid();
    for (unsigned foreign=0;foreign<2;++foreign) {
        pid_t child=foreign ? fork() : 0; CHECK(child>=0);
        if (!child) {
            struct sockaddr_un a={.sun_family=AF_UNIX};
            if (foreign) snprintf(a.sun_path,sizeof(a.sun_path),"/proc/%d/fd/%d/attach",parent,dir);
            else snprintf(a.sun_path,sizeof(a.sun_path),"/proc/thread-self/fd/%d/attach",dir);
            printf("socket path %s\n",a.sun_path);
            int server=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0); CHECK(server>=0);
            CHECK(!bind(server,(void *)&a,sizeof(a)) && !listen(server,1));
            struct sockaddr_un observed={0}; socklen_t size=sizeof(observed);
            CHECK(!getsockname(server,(void *)&observed,&size));
            CHECK(size==offsetof(struct sockaddr_un,sun_path)+strlen(a.sun_path)+1
                && !strcmp(a.sun_path,observed.sun_path));
            int client=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0); CHECK(client>=0);
            CHECK(!connect(client,(void *)&a,sizeof(a)));
            int accepted=accept4(server,NULL,NULL,SOCK_CLOEXEC); CHECK(accepted>=0);
            char value; CHECK(write(client,"s",1)==1 && read(accepted,&value,1)==1 && value=='s');
            CHECK(!unlinkat(dir,"attach",0));
            CHECK(!close(accepted) && !close(client) && !close(server));
            if (foreign) _exit(0);
        }
        if (foreign) done(child);
    }
    struct sockaddr_un a={.sun_family=AF_UNIX};
    snprintf(a.sun_path,sizeof(a.sun_path),"/proc/self/fd/%d/attach",dir);
    CHECK(!close(dir));
    int server=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0); CHECK(server>=0);
    /* The reused FD is a socket, never the former directory. */
    CHECK(bind(server,(void *)&a,sizeof(a))<0 && (errno==ENOTDIR || errno==ENOENT || errno==EBADF));
    CHECK(!close(server) && !rmdir("/tmp/socket-moved"));
    puts("PASS socket paths: retained and foreign proc-FD directories, rename, original names and closed/reused descriptors");
}
static void socket_mount_paths(const char *self) {
    CHECK(!unshare(CLONE_NEWNS));
    char directory[64]; snprintf(directory,sizeof(directory),"/tmp/md-socket-mount-%d",getpid());
    CHECK(!mkdir(directory,0700));
    CHECK(!mount("none",directory,"tmpfs",0,"mode=0700"));
    struct sockaddr_un address={.sun_family=AF_UNIX};
    snprintf(address.sun_path,sizeof(address.sun_path),"%s/socket",directory);
    socklen_t size=offsetof(struct sockaddr_un,sun_path)+strlen(address.sun_path)+1;
    for (unsigned i=0;i<3;++i) {
        int types[]={SOCK_STREAM,SOCK_SEQPACKET,SOCK_DGRAM};
        int fd=socket(AF_UNIX,types[i],0); CHECK(fd>=0);
        CHECK(!bind(fd,(void *)&address,size));
        int client=socket(AF_UNIX,types[i],0); CHECK(client>=0);
        if (types[i]!=SOCK_DGRAM) CHECK(!listen(fd,1));
        CHECK(!connect(client,(void *)&address,size));
        CHECK(!unlink(address.sun_path));
        pid_t child=fork(); CHECK(child>=0);
        if (!child) {
            CHECK(!unshare(CLONE_NEWNS));
            CHECK(!umount2(directory,MNT_DETACH));
            for (unsigned peer=0;peer<2;++peer) {
                struct sockaddr_un observed={0}; socklen_t n=sizeof(observed);
                CHECK(!(peer ? getpeername(client,(void *)&observed,&n) : getsockname(fd,(void *)&observed,&n)));
                CHECK(n==size && !memcmp(&observed,&address,size));
            }
            CHECK(!fcntl(fd,F_SETFD,0));
            char number[32]; snprintf(number,sizeof(number),"%d",fd);
            execl(self,"ipc-credentials","--socket-name",number,address.sun_path,(char *)NULL);
            perror("socket-name exec");
            _exit(99);
        }
        done(child); close(client); close(fd);
    }
    CHECK(!umount(directory) && !rmdir(directory));
    puts("PASS socket names: tmpfs, unlink, detached mounts, inherited/exec descriptors and peer addresses");
}
static void send_claim(int fd, pid_t pid, uid_t uid, int right, int error) {
    char data='m'; struct iovec io={&data,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))+CMSG_SPACE(sizeof(int))]; } c={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=c.bytes,
        .msg_controllen=CMSG_SPACE(sizeof(struct ucred))+(right>=0?CMSG_SPACE(sizeof(int)):0)};
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(struct ucred)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS};
    struct ucred cred={pid,uid,getgid()}; memcpy(CMSG_DATA(h),&cred,sizeof(cred));
    if (right>=0) {
        h=(void *)(c.bytes+CMSG_SPACE(sizeof(cred)));
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        memcpy(CMSG_DATA(h),&right,sizeof(right));
    }
    if (!error) CHECK(sendmsg(fd,&m,0)==1);
    else { errno=0; CHECK(sendmsg(fd,&m,0)==-1 && errno==error); }
}
static void send_identity(int fd, uid_t uid, int right, int success) {
    send_claim(fd,getpid(),uid,right,success ? 0 : EPERM);
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
static volatile sig_atomic_t broken_count, broken_code, broken_pid;
static void broken_pipe(int signal, siginfo_t *info, void *context) {
    (void)context;
    if (signal==SIGPIPE) { ++broken_count; broken_code=info->si_code; broken_pid=info->si_pid; }
}
static void send_signals(int mode, int credentials) {
    int pair[2]; CHECK(!socketpair(AF_UNIX,SOCK_STREAM,0,pair));
    CHECK(!shutdown(pair[1],SHUT_WR));
    struct sigaction action={.sa_sigaction=broken_pipe,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGPIPE,&action,NULL));
    if (mode==2) {
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGPIPE);
        CHECK(!sigprocmask(SIG_BLOCK,&mask,NULL));
    }
    char byte='s'; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
        .msg_controllen=credentials ? CMSG_SPACE(sizeof(struct ucred)) : CMSG_SPACE(sizeof(int))};
    control.align=(struct cmsghdr){.cmsg_len=credentials ? CMSG_LEN(sizeof(struct ucred)) : CMSG_LEN(sizeof(int)),
        .cmsg_level=SOL_SOCKET,.cmsg_type=credentials ? SCM_CREDENTIALS : SCM_RIGHTS};
    if (credentials) {
        struct ucred cred={getpid(),getuid(),getgid()}; memcpy(CMSG_DATA(&control.align),&cred,sizeof(cred));
    } else memcpy(CMSG_DATA(&control.align),&pair[0],sizeof(pair[0]));
    if (mode==3 || mode==4) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,0,4),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)pair[1],0,2),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(uintptr_t)&m,mode==3 ? 1 : 0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPIPE)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
    }
    CHECK(sendmsg(pair[1],&m,mode==1 ? MSG_NOSIGNAL : 0)==-1 && errno==EPIPE);
    if (mode==2) {
        sigset_t pending; CHECK(!sigpending(&pending));
        CHECK(!broken_count && sigismember(&pending,SIGPIPE));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGPIPE);
        CHECK(!sigprocmask(SIG_UNBLOCK,&mask,NULL));
    }
    /* Mode 3 rejects the original call. Mode 4 accepts it but rejects a helper's
     * replacement header; the captured host send now reaches the real EPIPE. */
    CHECK(broken_count==((mode==0 || mode==2 || mode==4) ? 1 : 0));
    if (broken_count) CHECK(broken_pid==getpid() && broken_code==SI_USER);
    close(pair[0]); close(pair[1]);
    printf("PASS %s send SIGPIPE mode=%d\n",credentials ? "credential" : "captured",mode);
}
static void send_inputs(int protected, int full) {
    int pair[2], one=1; CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
    CHECK(!setsockopt(pair[0],SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    CHECK(!prctl(PR_SET_DUMPABLE,0)); peer(pair[1],getpid(),0,0);
    CHECK(!prctl(PR_SET_DUMPABLE,!protected));
    int held[256], count=0;
    if (full) {
        struct rlimit limits; CHECK(!getrlimit(RLIMIT_NOFILE,&limits));
        limits.rlim_cur=128; CHECK(!setrlimit(RLIMIT_NOFILE,&limits));
        int fd;
        while ((fd=dup(pair[0]))>=0) { CHECK(count<256); held[count++]=fd; }
        CHECK(errno==EMFILE);
        send_identity(pair[1],0,pair[0],1);
        for (int i=0;i<count;++i) close(held[i]);
        receive_identity(pair[0],getpid(),0,MSG_CMSG_CLOEXEC,0,1);
    } else {
        /* A private token must not fill an invalid application input slot. */
        close(0);
        char byte='b'; struct iovec io={&byte,1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))+CMSG_SPACE(sizeof(int))]; } control={0};
        struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
        control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(struct ucred)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS};
        struct ucred cred={getpid(),0,0}; memcpy(CMSG_DATA(&control.align),&cred,sizeof(cred));
        struct cmsghdr *h=(void *)(control.bytes+CMSG_SPACE(sizeof(cred)));
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        int invalid=0; memcpy(CMSG_DATA(h),&invalid,sizeof(invalid));
        CHECK(sendmsg(pair[1],&m,MSG_NOSIGNAL)==-1 && errno==EBADF);
        CHECK(recv(pair[0],&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
        CHECK(fcntl(0,F_GETFD)==-1 && errno==EBADF);
    }
    close(pair[0]); close(pair[1]);
    printf("PASS credential send inputs protected=%d full=%d\n",protected,full);
}
enum { SEND_LIFETIME_BYTES=1024*1024 };
struct paused_send { int socket, right, protected, credentials; };
static int paused_sender(void *opaque) {
    struct paused_send *p=opaque;
    CHECK(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (p->protected) CHECK(!prctl(PR_SET_DUMPABLE,0));
    char *bytes=malloc(SEND_LIFETIME_BYTES); CHECK(bytes); memset(bytes,'m',SEND_LIFETIME_BYTES);
    struct iovec io={bytes,SEND_LIFETIME_BYTES};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))+CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
        .msg_controllen=CMSG_SPACE(sizeof(int))+(p->credentials ? CMSG_SPACE(sizeof(struct ucred)) : 0)};
    struct cmsghdr *h=&control.align;
    if (p->credentials) {
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(struct ucred)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS};
        struct ucred cred={getpid(),0,0}; memcpy(CMSG_DATA(h),&cred,sizeof(cred));
        h=(void *)(control.bytes+CMSG_SPACE(sizeof(cred)));
    }
    *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
    memcpy(CMSG_DATA(h),&p->right,sizeof(p->right));
    CHECK(sendmsg(p->socket,&m,MSG_NOSIGNAL)==SEND_LIFETIME_BYTES);
    free(bytes);
    return 0;
}
static void receive_right(int fd) {
    char byte; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control;
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    CHECK(recvmsg(fd,&m,MSG_CMSG_CLOEXEC)==1 && byte=='m' && !(m.msg_flags&MSG_CTRUNC));
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    CHECK(h && h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS
        && h->cmsg_len==CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&m,h));
    int right; memcpy(&right,CMSG_DATA(h),sizeof(right));
    CHECK(fcntl(right,F_GETFD)==FD_CLOEXEC);
    peer(right,getpid(),0,0); CHECK(!close(right));
}
static void prepare_rights_delivery(void) {
    /* Admit reusable return transport with an actual FD transfer. Logical
     * dumpability must not be used to select a host capture mechanism. */
    int pair[2]; CHECK(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,pair));
    send_identity(pair[1],getuid(),pair[1],1);
    receive_right(pair[0]);
    CHECK(!close(pair[0]) && !close(pair[1]));
}
static void send_lifetime(int protected, int cancel, int credentials) {
    int pair[2], right[2], unrelated[2], one=1, capacity=4096;
    CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
    CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,right));
    CHECK(!pipe2(unrelated,O_CLOEXEC|O_NONBLOCK));
    if (credentials) CHECK(!setsockopt(pair[0],SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    CHECK(!setsockopt(pair[1],SOL_SOCKET,SO_SNDBUF,&capacity,sizeof(capacity)));
    prepare_rights_delivery();
    size_t size=1024*1024; void *stack=malloc(size); CHECK(stack);
    int before[256]; for (unsigned i=0;i<256;++i) before[i]=fcntl(i,F_GETFD);
    struct paused_send context={pair[1],right[0],protected,credentials};
    pid_t child=clone(paused_sender,(char *)stack+size,CLONE_FILES|SIGCHLD,&context); CHECK(child>0);
    /* EVENT_WAIT: the first payload byte proves the helper entered sendmsg.
     * Its bounded socket cannot accept the remaining megabyte until we drain it.
     * The enclosing launch deadline bounds missing delivery/cleanup. */
    if (credentials) receive_identity(pair[0],child,0,MSG_CMSG_CLOEXEC,0,1);
    else receive_right(pair[0]);
    CHECK(!close(unrelated[1])); before[unrelated[1]]=-1;
    char byte; CHECK(read(unrelated[0],&byte,1)==0);
    /* Table turns must be released and only captured inputs retained while
     * the syscall is blocked. Reusing the original slot cannot retarget it. */
    CHECK(dup3(unrelated[0],pair[1],O_CLOEXEC)==pair[1]);
    if (cancel) CHECK(!kill(child,SIGKILL));
    size_t received=1;
    char bytes[8192]; ssize_t n;
    while ((n=read(pair[0],bytes,sizeof(bytes)))>0) {
        received+=(size_t)n;
        for (ssize_t i=0;i<n;++i) CHECK(bytes[i]=='m');
    }
    CHECK(!n && (cancel ? received<SEND_LIFETIME_BYTES : received==SEND_LIFETIME_BYTES));
    int status; CHECK(waitpid(child,&status,0)==child);
    CHECK(cancel ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL : WIFEXITED(status) && !WEXITSTATUS(status));
    unchanged_descriptors(before,256);
    CHECK(!close(pair[0]) && !close(pair[1]) && !close(right[0]) && !close(right[1]));
    CHECK(!close(unrelated[0])); free(stack);
    printf("PASS %s send lifecycle protected=%d cancel=%d\n",credentials ? "credential" : "captured",protected,cancel);
}
static void nonunix_rights(void) {
    int fd=socket(AF_INET,SOCK_DGRAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0); CHECK(fd>=0);
    struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    CHECK(!bind(fd,(void *)&address,sizeof(address)));
    socklen_t length=sizeof(address); CHECK(!getsockname(fd,(void *)&address,&length));
    CHECK(!connect(fd,(void *)&address,length));
    char byte='n'; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
    memcpy(CMSG_DATA(&control.align),&fd,sizeof(fd));
    int before[256]; for (unsigned i=0;i<256;++i) before[i]=fcntl(i,F_GETFD);
    errno=0; ssize_t sent=sendmsg(fd,&message,MSG_NOSIGNAL);
    if (sent!=1) fprintf(stderr,"non-Unix send result=%zd errno=%d\n",sent,errno);
    CHECK(sent==1);
    byte=0; ssize_t received=recvmsg(fd,&message,MSG_DONTWAIT);
    if (received!=1 || byte!='n' || message.msg_controllen)
        fprintf(stderr,"non-Unix receive result=%zd errno=%d byte=%d control=%zu flags=%#x\n",
            received,errno,byte,message.msg_controllen,message.msg_flags);
    CHECK(received==1 && byte=='n' && !message.msg_controllen);
    CHECK(recv(fd,&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,1,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getsockopt,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        message.msg_name=&address; message.msg_namelen=length; message.msg_control=NULL;
        CHECK(sendmsg(fd,&message,MSG_NOSIGNAL)==1);
        struct mmsghdr batch[2]={{.msg_hdr=message},{.msg_hdr=message}};
        CHECK(sendmmsg(fd,batch,2,MSG_NOSIGNAL)==2 && batch[0].msg_len==1 && batch[1].msg_len==1);
        CHECK(sendto(fd,&byte,1,MSG_NOSIGNAL,(void *)&address,length)==1);
        _exit(0);
    }
    done(child);
    for (unsigned i=0;i<4;++i) CHECK(recv(fd,&byte,1,MSG_DONTWAIT)==1 && byte=='n');
    CHECK(recv(fd,&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
    unchanged_descriptors(before,256);
    CHECK(!close(fd)); puts("PASS non-Unix ancillary send: native payload, no rights and unchanged caller table");
}
static void send_contract(void) {
    nonunix_rights();
    for (int credentials=0;credentials<2;++credentials) for (int mode=0;mode<(credentials ? 5 : 3);++mode) {
        pid_t child=fork(); CHECK(child>=0);
        if (!child) { send_signals(mode,credentials); exit(0); }
        done(child);
    }
    for (int protected=0;protected<2;++protected) for (int full=0;full<2;++full) {
        pid_t child=fork(); CHECK(child>=0);
        if (!child) { send_inputs(protected,full); exit(0); }
        done(child);
    }
    for (int credentials=0;credentials<2;++credentials)
        for (int protected=0;protected<2;++protected) for (int cancel=0;cancel<2;++cancel)
            send_lifetime(protected,cancel,credentials);
}
struct paused_receive { int socket, protected; };
static int paused_receiver(void *opaque) {
    struct paused_receive *p=opaque;
    CHECK(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (p->protected) CHECK(!prctl(PR_SET_DUMPABLE,0));
    char *bytes=malloc(2*SEND_LIFETIME_BYTES); CHECK(bytes);
    struct iovec io={bytes,2*SEND_LIFETIME_BYTES};
    union { struct cmsghdr align; char bytes[128]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    CHECK(recvmsg(p->socket,&m,MSG_WAITALL)==2*SEND_LIFETIME_BYTES);
    CHECK(!m.msg_controllen);
    for (unsigned i=0;i<2*SEND_LIFETIME_BYTES;++i) CHECK(bytes[i]=='r');
    free(bytes); return 0;
}
static void receive_lifetime(int protected, int cancel) {
    int pair[2], unrelated[2], capacity=4096;
    CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
    CHECK(!pipe2(unrelated,O_CLOEXEC|O_NONBLOCK));
    CHECK(!setsockopt(pair[1],SOL_SOCKET,SO_SNDBUF,&capacity,sizeof(capacity)));
    prepare_rights_delivery();
    size_t size=1024*1024; void *stack=malloc(size); CHECK(stack);
    int before[256]; for (unsigned i=0;i<256;++i) before[i]=fcntl(i,F_GETFD);
    struct paused_receive context={pair[0],protected};
    pid_t child=clone(paused_receiver,(char *)stack+size,CLONE_FILES|SIGCHLD,&context); CHECK(child>0);
    char *bytes=malloc(SEND_LIFETIME_BYTES); CHECK(bytes); memset(bytes,'r',SEND_LIFETIME_BYTES);
    /* EVENT_WAIT: a megabyte cannot fit in the bounded send buffer. Successful
     * send proves the receiver entered MSG_WAITALL and drained its first half.
     * Launch deadline bounds both the receive and exact cancellation/reaping. */
    CHECK(send(pair[1],bytes,SEND_LIFETIME_BYTES,MSG_NOSIGNAL)==SEND_LIFETIME_BYTES);
    CHECK(!close(unrelated[1])); before[unrelated[1]]=-1;
    char byte; CHECK(read(unrelated[0],&byte,1)==0);
    CHECK(dup3(unrelated[0],pair[0],O_CLOEXEC)==pair[0]);
    if (cancel) CHECK(!kill(child,SIGKILL));
    else CHECK(send(pair[1],bytes,SEND_LIFETIME_BYTES,MSG_NOSIGNAL)==SEND_LIFETIME_BYTES);
    int status; CHECK(waitpid(child,&status,0)==child);
    CHECK(cancel ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL : WIFEXITED(status) && !WEXITSTATUS(status));
    CHECK(send(pair[1],"x",1,MSG_NOSIGNAL)==-1 && errno==EPIPE);
    unchanged_descriptors(before,256);
    CHECK(!close(pair[0]) && !close(pair[1]) && !close(unrelated[0])); free(bytes); free(stack);
    printf("PASS receive lifecycle protected=%d cancel=%d\n",protected,cancel);
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
static void claimed_process(void) {
    int pair[2], one=1;
    CHECK(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,pair)
        && !setsockopt(pair[0],SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    pid_t parent=getpid(), child=fork(); CHECK(child>=0);
    if (!child) {
        send_claim(pair[1],parent,1234,-1,0);
        send_claim(pair[1],0,0,-1,ESRCH);
        CHECK(!setuid(1234));
        send_claim(pair[1],parent,1234,-1,EPERM);
        send_claim(pair[1],getpid(),1234,-1,0);
        _exit(0);
    }
    done(child);
    receive_identity(pair[0],parent,1234,MSG_PEEK,0,0);
    receive_identity(pair[0],parent,1234,0,0,0);
    receive_identity(pair[0],child,1234,0,0,0);
    CHECK(!close(pair[0]) && !close(pair[1]));
    puts("PASS claimed process: privileged parent PID, unauthorized/missing rejection, peek and sender exit");
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
static void external(const char *name,pid_t server,uid_t uid,gid_t gid) {
    int fd=socket(AF_UNIX,SOCK_STREAM,0); CHECK(fd>=0);
    struct sockaddr_un address={.sun_family=AF_UNIX};
    CHECK(strlen(name)<sizeof(address.sun_path)-1);
    memcpy(address.sun_path+1,name,strlen(name));
    CHECK(!connect(fd,(void *)&address,offsetof(struct sockaddr_un,sun_path)+1+strlen(name)));
    if (!server) {
        CHECK(!prctl(PR_SET_DUMPABLE,0));
        struct sock_filter filter[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prctl,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(filter)/sizeof(*filter),filter};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
        int snapshot=open("/proc/self/mountinfo",O_RDONLY|O_CLOEXEC);
        char content[32];
        CHECK(snapshot>=0 && read(snapshot,content,sizeof(content))>0 && !close(snapshot));
    }
    peer(fd,server,uid,gid);
    send_identity(fd,0,-1,1);
    /* Guest self claims map to actual authority; forged guest claims still fail. */
    CHECK(!setgid(1234) && !setuid(1234));
    send_identity(fd,0,-1,0);
    send_identity(fd,1234,-1,1);
    int one=1; CHECK(!setsockopt(fd,SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    CHECK(write(fd,"r",1)==1);
    char byte,control[CMSG_SPACE(sizeof(struct ucred))]={0}; struct iovec data={&byte,1};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    CHECK(recvmsg(fd,&message,0)==1 && byte=='n' && !(message.msg_flags&MSG_CTRUNC));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    CHECK(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_CREDENTIALS);
    struct ucred cred; memcpy(&cred,CMSG_DATA(header),sizeof(cred));
    CHECK(cred.pid==server && cred.uid==uid && cred.gid==gid);
    close(fd);
}
static void peer_full_table(int protected, unsigned count) {
    gid_t *groups=malloc(65536*sizeof(*groups)), *actual=malloc(65536*sizeof(*actual));
    CHECK(groups && actual);
    for (unsigned i=0;i<count;++i) groups[i]=2*i+1;
    CHECK(!setgroups(count,groups));
    int p[2]; CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,p));
    CHECK(!setgroups(0,NULL));
    if (protected) CHECK(!prctl(PR_SET_DUMPABLE,0));
    /* Admit the table-owned input channel before exhausting application slots. */
    peer(p[0],getpid(),0,0);
    struct rlimit old, limit; CHECK(!getrlimit(RLIMIT_NOFILE,&old));
    limit=old; limit.rlim_cur=64; CHECK(!setrlimit(RLIMIT_NOFILE,&limit));
    int owned[64], used=0, fd;
    while ((fd=fcntl(p[0],F_DUPFD_CLOEXEC,0))>=0) { CHECK(used<64); owned[used++]=fd; }
    CHECK(errno==EMFILE);
    long page=sysconf(_SC_PAGESIZE);
    void *bad=mmap(NULL,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); CHECK(bad!=MAP_FAILED);
    for (unsigned repeat=0;repeat<12;++repeat) {
        peer(p[0],getpid(),0,0);
        socklen_t n=count*sizeof(*groups);
        CHECK(!getsockopt(p[0],SOL_SOCKET,SO_PEERGROUPS,actual,&n));
        CHECK(n==count*sizeof(*groups) && !memcmp(actual,groups,n));
        n=0;
        int r=getsockopt(p[0],SOL_SOCKET,SO_PEERGROUPS,bad,&n);
        CHECK(count ? r==-1 && errno==ERANGE && n==count*sizeof(*groups) : !r && !n);
        n=sizeof(struct ucred);
        CHECK(getsockopt(p[0],SOL_SOCKET,SO_PEERCRED,bad,&n)==-1 && errno==EFAULT);
        CHECK(getsockopt(p[0],SOL_SOCKET,SO_PEERCRED,actual,bad)==-1 && errno==EFAULT);
        n=(socklen_t)-1;
        CHECK(getsockopt(p[0],SOL_SOCKET,SO_PEERCRED,actual,&n)==-1 && errno==EINVAL);
        n=0; CHECK(!getsockopt(p[0],SOL_SOCKET,SO_PEERCRED,bad,&n) && !n);
        if (count) {
            n=count*sizeof(*groups);
            CHECK(getsockopt(p[0],SOL_SOCKET,SO_PEERGROUPS,bad,&n)==-1 && errno==EFAULT);
        }
        for (int i=0;i<used;++i) CHECK(fcntl(owned[i],F_GETFD)==FD_CLOEXEC);
        CHECK(fcntl(p[0],F_DUPFD_CLOEXEC,0)==-1 && errno==EMFILE);
    }
    CHECK(!munmap(bad,page));
    for (int i=0;i<used;++i) CHECK(!close(owned[i]));
    CHECK(!setrlimit(RLIMIT_NOFILE,&old));
    close(p[0]); close(p[1]); free(groups); free(actual);
    printf("PASS peer identity full-table: protected=%d groups=%u, snapshot and output faults\n",protected,count);
}
struct custody { int socket, receive, protected; };
static int custody_child(void *argument) {
    struct custody *c=argument;
    CHECK(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    /* Modes 2/3 combine protected inspection with a filter denying sendmsg.
     * The allowed receive must not inherit a denial of an internal export. */
    if (c->protected || c->receive==2 || c->receive==3) CHECK(!prctl(PR_SET_DUMPABLE,0));
    char byte=c->receive ? 0 : 'm'; struct iovec io={&byte,1};
    union { struct cmsghdr align; char data[128]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.data,
        .msg_controllen=c->receive ? sizeof(control.data) : CMSG_SPACE(sizeof(struct ucred))};
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(struct ucred)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS};
    struct ucred cred={getpid(),getuid(),getgid()}; memcpy(CMSG_DATA(h),&cred,sizeof(cred));
    struct sock_filter send_filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)c->socket,0,2),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(uintptr_t)&m,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD)
    };
    struct sock_filter receive_filter[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,c->receive==1 ? SYS_fstat : SYS_sendmsg,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog filter={.len=c->receive ? sizeof(receive_filter)/sizeof(*receive_filter)
        : sizeof(send_filter)/sizeof(*send_filter),.filter=c->receive ? receive_filter : send_filter};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
    struct mmsghdr batch={.msg_hdr=m};
    ssize_t n=c->receive==3 ? recvmmsg(c->socket,&batch,1,MSG_CMSG_CLOEXEC,NULL)
        : c->receive ? recvmsg(c->socket,&m,MSG_CMSG_CLOEXEC) : sendmsg(c->socket,&m,0);
    if (c->receive==3) m=batch.msg_hdr;
    CHECK(n==1 && byte=='m');
    unsigned received=0;
    if (c->receive) for (h=CMSG_FIRSTHDR(&m);h;h=CMSG_NXTHDR(&m,h))
        if (h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS) {
            const int *fds=(const void *)CMSG_DATA(h);
            for (unsigned i=0;i<(h->cmsg_len-CMSG_LEN(0))/sizeof(int);++i) {
                CHECK(fcntl(fds[i],F_GETFD)==FD_CLOEXEC);
                CHECK(!close(fds[i])); ++received;
            }
        }
    CHECK(received==(unsigned)!!c->receive);
    return 0;
}
static void custody(int guest, int receive, int protected) {
    int pair[2], transferred[2], one=1;
    CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
    CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,transferred));
    CHECK(!setsockopt(pair[0],SOL_SOCKET,SO_PASSCRED,&one,sizeof(one)));
    if (guest) prepare_rights_delivery();
    if (receive) send_identity(pair[1],getuid(),transferred[0],1);
    int before[256];
    for (unsigned i=0;i<256;++i) before[i]=fcntl(i,F_GETFD);
    struct custody c={receive ? pair[0] : pair[1],receive,protected};
    size_t size=1024*1024; void *stack=malloc(size); CHECK(stack);
    pid_t child=clone(custody_child,(char *)stack+size,CLONE_FILES|SIGCHLD,&c); CHECK(child>0);
    int status;
    /* EVENT_WAIT: exact caller exit; the launch deadline bounds missing cleanup. */
    CHECK(waitpid(child,&status,0)==child);
    CHECK(WIFEXITED(status) && !WEXITSTATUS(status));
    unsigned changed=0;
    for (unsigned i=0;i<256;++i) {
        int flags=fcntl(i,F_GETFD);
        if (flags==before[i]) continue;
        char path[64], target[256]; snprintf(path,sizeof(path),"/proc/self/fd/%u",i);
        ssize_t n=readlink(path,target,sizeof(target)-1); target[n<0 ? 0 : n]=0;
        fprintf(stderr,"custody receive=%d protected=%d fd=%u before=%d after=%d target=%s\n",
            receive,protected,i,before[i],flags,target); ++changed;
    }
    CHECK(!changed);
    free(stack); close(pair[0]); close(pair[1]); close(transferred[0]); close(transferred[1]);
    printf("PASS IPC caller custody receive=%d protected=%d guest=%d\n",receive,protected,guest);
}
static void origin_descriptors(const int *fds, unsigned expected, int flags) {
    for (unsigned i=0;i<expected;++i) {
        CHECK(fcntl(fds[i],F_GETFD)==(flags&MSG_CMSG_CLOEXEC ? FD_CLOEXEC : 0));
        if (i==252) { struct stat st; CHECK(!fstat(fds[i],&st) && S_ISFIFO(st.st_mode)); }
        else if (i%70) CHECK((fcntl(fds[i],F_GETFL)&O_PATH)==O_PATH);
        else {
            char text[7]; CHECK(pread(fds[i],text,sizeof(text),0)==sizeof(text) && !memcmp(text,"payload",7));
            CHECK(lseek(fds[i],0,SEEK_CUR)==2);
        }
    }
    if (expected>1) {
        /* A bare native directory has backing names, not the guest path. This
         * lookup requires the imported view and published descriptor origin. */
        int fd=openat(fds[1],"origin-payload",O_RDONLY); CHECK(fd>=0);
        char text[7]; CHECK(read(fd,text,sizeof(text))==sizeof(text) && !memcmp(text,"payload",7));
        CHECK(!close(fd));
    }
    for (unsigned i=0;i<expected;++i) CHECK(!close(fds[i]));
}
static void origin_rights(struct msghdr *m, unsigned expected, int flags, unsigned mode) {
    struct cmsghdr *rights=NULL; unsigned credentials=0;
    for (struct cmsghdr *h=CMSG_FIRSTHDR(m);h;h=CMSG_NXTHDR(m,h)) {
        CHECK(h->cmsg_level==SOL_SOCKET);
        if (h->cmsg_type==SCM_CREDENTIALS) {
            struct ucred c; CHECK(h->cmsg_len==CMSG_LEN(sizeof(c)));
            memcpy(&c,CMSG_DATA(h),sizeof(c));
            CHECK(c.pid>0 && c.uid==1234 && c.gid==2345); ++credentials;
        } else { CHECK(h->cmsg_type==SCM_RIGHTS && !rights); rights=h; }
    }
    CHECK(credentials==(mode==10 || mode==11 ? 1U : 0U));
    if (mode==13) {
        CHECK(rights && rights->cmsg_len==CMSG_LEN(sizeof(int)) && !(m->msg_flags&MSG_CTRUNC));
        int fd; memcpy(&fd,CMSG_DATA(rights),sizeof(fd));
        int type=0; socklen_t size=sizeof(type);
        CHECK(!getsockopt(fd,SOL_SOCKET,SO_TYPE,&type,&size) && type==SOCK_SEQPACKET);
        CHECK(!close(fd)); return;
    }
    CHECK(!!(m->msg_flags&MSG_CTRUNC)==(expected<253));
    if (!expected) { CHECK(!rights); return; }
    CHECK(rights && rights->cmsg_len==CMSG_LEN(expected*sizeof(int)));
    int fds[253]; memcpy(fds,CMSG_DATA(rights),expected*sizeof(int));
    origin_descriptors(fds,expected,flags);
}
static void capture_channel_admission(int socket, int full);
static int admit_shared_channel(void *unused) {
    (void)unused;
    struct sock_filter code[]={BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
    int socket_fd=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0); CHECK(socket_fd>=0);
    socket_fd=md_test_capture_socket(socket_fd);
    capture_channel_admission(socket_fd,0);
    CHECK(!close(socket_fd));
    return 0;
}
static void warm_shared_channel(void) {
    /* A filtered child admits one shared-table channel. The parent remains
     * unfiltered, so its subsequent IO tests ordinary channel reuse. */
    int filter=prctl(PR_GET_SECCOMP);
    size_t size=1024*1024;
    void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    CHECK(stack!=MAP_FAILED);
    pid_t child=clone(admit_shared_channel,(char *)stack+size,CLONE_FILES|SIGCHLD,NULL);
    CHECK(child>=0); done(child);
    CHECK(prctl(PR_GET_SECCOMP)==filter && !munmap(stack,size));
}
static void origin_send(unsigned mode, int protected) {
    CHECK(!getenv("MAGICDESK_TEST_CAPTURE_FALLBACK") && !prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0));
    int warm=protected&4; protected&=1;
    if (warm) warm_shared_channel();
    CHECK(mode<16);
    int file=open("/tmp/outgoing-file",O_CREAT|O_RDWR|O_TRUNC|O_CLOEXEC,0600);
    CHECK(file>=0 && write(file,"send",4)==4 && lseek(file,1,SEEK_SET)==1);
    int directory=open("/tmp",O_PATH|O_DIRECTORY|O_CLOEXEC); CHECK(directory>=0);
    if (protected) CHECK(!prctl(PR_SET_DUMPABLE,0));
    unsigned count=mode==10 ? 0 : mode ? 253 : 1;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))+CMSG_SPACE(sizeof(int))+CMSG_SPACE(sizeof(struct ucred))]; } control={0};
    unsigned prefix=mode==3 ? 100 : count;
    control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(prefix*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
    for (unsigned i=0;i<count;++i) {
        int fd=mode==4 && i==count-1 ? -1 : i%2 ? directory : file;
        size_t offset=i<prefix ? CMSG_LEN(0)+i*sizeof(int)
            : CMSG_SPACE(prefix*sizeof(int))+CMSG_LEN(0)+(i-prefix)*sizeof(int);
        memcpy(control.bytes+offset,&fd,sizeof(fd));
    }
    if (mode==3) *(struct cmsghdr *)(control.bytes+CMSG_SPACE(prefix*sizeof(int)))=
        (struct cmsghdr){.cmsg_len=CMSG_LEN((count-prefix)*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
    char byte='s'; struct iovec io={mode==7 ? (void *)1 : &byte,1};
    struct mmsghdr messages[2]={0};
    messages[0].msg_hdr=(struct msghdr){.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
        .msg_controllen=mode==3 ? CMSG_SPACE(prefix*sizeof(int))+CMSG_SPACE((count-prefix)*sizeof(int)) : CMSG_SPACE(count*sizeof(int))};
    if (mode==9 || mode==10 || mode==14) {
        if (!count) messages[0].msg_hdr.msg_controllen=0;
        struct cmsghdr *h=(void *)(control.bytes+messages[0].msg_hdr.msg_controllen);
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(struct ucred)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS};
        struct ucred cred={getpid()+(mode==14),1234,2345}; memcpy(CMSG_DATA(h),&cred,sizeof(cred));
        messages[0].msg_hdr.msg_controllen+=CMSG_SPACE(sizeof(cred));
        if (mode==14) {
            struct __user_cap_header_struct header={_LINUX_CAPABILITY_VERSION_3,0};
            struct __user_cap_data_struct caps[2];
            CHECK(!syscall(SYS_capget,&header,caps));
            caps[CAP_SYS_ADMIN/32].effective&=~(1U<<(CAP_SYS_ADMIN%32));
            CHECK(!syscall(SYS_capset,&header,caps));
        }
    } else if (mode==11 || mode==12) {
        struct cmsghdr *h=(void *)(control.bytes+messages[0].msg_hdr.msg_controllen);
        *h=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=mode==11 ? SOL_SOCKET : IPPROTO_IP,
            .cmsg_type=mode==11 ? 0x7fffffff : IP_TTL};
        int ttl=32; memcpy(CMSG_DATA(h),&ttl,sizeof(ttl));
        messages[0].msg_hdr.msg_controllen+=CMSG_SPACE(sizeof(ttl));
    }
    messages[1]=messages[0];
    int held[1024]; unsigned held_count=0; struct rlimit limit;
    if (mode==2) {
        CHECK(!getrlimit(RLIMIT_NOFILE,&limit)); struct rlimit next=limit; next.rlim_cur=1024;
        CHECK(!setrlimit(RLIMIT_NOFILE,&next));
        int fd; while ((fd=dup(STDIN_FILENO))>=0) { CHECK(held_count<1024); held[held_count++]=fd; }
        CHECK(errno==EMFILE);
    }
    if (mode==5) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_sendmsg,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,STDIN_FILENO,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
    }
    if (mode==8 || mode==13) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getsockopt,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SO_DOMAIN,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        int domain; socklen_t length=sizeof(domain);
        CHECK(getsockopt(STDIN_FILENO,SOL_SOCKET,SO_DOMAIN,&domain,&length)==-1 && errno==EACCES);
        if (!warm) capture_channel_admission(STDIN_FILENO,0);
    }
    if (mode==15) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        CHECK(syscall(SYS_gettid)==-1 && errno==EACCES);
    }
    int before[1024]; for (unsigned i=0;i<1024;++i) before[i]=fcntl((int)i,F_GETFD);
    int batch=mode==6 || mode==13;
    int result=batch ? sendmmsg(STDIN_FILENO,messages,2,MSG_NOSIGNAL)
        : (int)sendmsg(STDIN_FILENO,&messages[0].msg_hdr,MSG_NOSIGNAL);
    /* Private channel admission is independent of the application's gettid
     * policy, on both cold admission and shared-table reuse. */
    int expected=mode==4 ? EBADF : mode==5 ? EACCES : mode==7 ? EFAULT : mode==11 ? EINVAL : mode==14 ? EPERM : 0;
    if (expected) CHECK(result==-1 && errno==expected);
    else CHECK(result==(batch ? 2 : 1));
    if (mode==15) CHECK(syscall(SYS_gettid)==-1 && errno==EACCES);
    unsigned admitted=0;
    for (unsigned i=0;i<1024;++i) {
        int after=fcntl((int)i,F_GETFD);
        if (before[i]==after) continue;
        CHECK(mode==15 && !warm && before[i]<0 && after==FD_CLOEXEC);
        int type=0; socklen_t size=sizeof(type);
        CHECK(!getsockopt((int)i,SOL_SOCKET,SO_TYPE,&type,&size) && type==SOCK_SEQPACKET);
        ++admitted;
    }
    CHECK(admitted==(unsigned)(mode==15 && !warm));
    if (mode==2) CHECK(!setrlimit(RLIMIT_NOFILE,&limit));
    while (held_count) CHECK(!close(held[--held_count]));
    CHECK(!close(directory) && !close(file));
    printf("PASS capsule guest sender mode=%u protected=%d\n",mode,protected);
}
static void origin_receive(unsigned mode, int protected) {
    CHECK(!getenv("MAGICDESK_TEST_CAPTURE_FALLBACK") && !prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0));
    int filtered=protected&2, warm=protected&4; protected&=1;
    if (warm) warm_shared_channel();
    CHECK(mode<15);
    /* The host-only fixture restriction retains private-helper SCM quotas;
     * logical dumpability is varied independently of native FD capture. */
    if (protected || mode==9) CHECK(!prctl(PR_SET_DUMPABLE,0));
    if (filtered) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getsockopt,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SO_DOMAIN,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
        CHECK(syscall(SYS_clone,0,0,0,0,0)==-1 && errno==EPERM);
        int domain; socklen_t size=sizeof(domain);
        CHECK(getsockopt(STDIN_FILENO,SOL_SOCKET,SO_DOMAIN,&domain,&size)==-1 && errno==EACCES);
    }
    for (unsigned pass=0;pass<(!mode || mode>=4 ? 2U : 1U);++pass) {
        union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))+CMSG_SPACE(sizeof(struct ucred))]; } control[2]={0};
        char bytes[2]={0}; struct iovec io[2]; struct mmsghdr m[2]={0};
        int peek=(!mode || mode>=4) && !pass;
        unsigned count=mode==3 ? 2 : 1, expected=mode==1 ? 3 : mode==2 ? 0 : 253;
        for (unsigned i=0;i<count;++i) {
            io[i]=(struct iovec){&bytes[i],1};
            m[i].msg_hdr=(struct msghdr){.msg_iov=&io[i],.msg_iovlen=1,
                .msg_control=expected ? control[i].bytes : NULL,
                .msg_controllen=expected==253 ? sizeof(control[i]) : expected ? CMSG_LEN(expected*sizeof(int)) : 0};
        }
        int flags=MSG_DONTWAIT|(peek ? MSG_PEEK : 0)|(protected ? MSG_CMSG_CLOEXEC : 0);
        size_t page=(size_t)sysconf(_SC_PAGESIZE);
        char *memory=NULL; struct msghdr *target=&m[0].msg_hdr;
        int before[1024];
        int held[1024]; unsigned held_count=0;
        struct rlimit limit={0};
        if (mode==14) {
            memory=mmap(NULL,3*page,PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            CHECK(memory!=MAP_FAILED);
            target->msg_control=memory;
        }
        if (!peek && mode>=4 && mode<10) {
            for (unsigned i=0;i<1024;++i) before[i]=fcntl((int)i,F_GETFD);
            if (mode<7) {
                memory=mmap(NULL,3*page,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
                CHECK(memory!=MAP_FAILED);
                if (mode==4) {
                    target->msg_control=memory+page-CMSG_LEN(3*sizeof(int));
                    CHECK(!mprotect(memory+page,page,PROT_NONE)); expected=3;
                } else if (mode==5) {
                    target->msg_control=memory+page-CMSG_LEN(0);
                    CHECK(!mprotect(memory,page,PROT_READ));
                } else {
                    target=(void *)(memory+2*page); *target=m[0].msg_hdr;
                    CHECK(!mprotect(target,page,PROT_READ));
                }
            } else if (mode<9) {
                /* Saturate table slots independently of the per-UID Unix
                 * in-flight-right quota exercised by mode 9. */
                CHECK(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_cur>=1024);
                struct rlimit next=limit; next.rlim_cur=1024;
                CHECK(!setrlimit(RLIMIT_NOFILE,&next));
                int fd;
                while ((fd=dup(STDIN_FILENO))>=0) { CHECK(held_count<1024); held[held_count++]=fd; }
                CHECK(errno==EMFILE);
                expected=mode==7 ? 3 : 0;
                for (unsigned i=0;i<expected;++i) CHECK(!close(held[--held_count]));
            } else {
                CHECK(!getrlimit(RLIMIT_NOFILE,&limit));
                unsigned left=3;
                for (unsigned i=0;i<1024;++i) if (before[i]<0) {
                    if (left) { --left; continue; }
                    struct rlimit next=limit; next.rlim_cur=i;
                    CHECK(!setrlimit(RLIMIT_NOFILE,&next)); break;
                }
            }
        }
        int result=mode==3 ? recvmmsg(STDIN_FILENO,m,count,flags,NULL) : (int)recvmsg(STDIN_FILENO,target,flags);
        if (mode==14) CHECK(!mprotect(memory,3*page,PROT_READ|PROT_WRITE));
        if (result!=(int)count)
            fprintf(stderr,"origin receive mode=%u protected=%d filtered=%d pass=%u result=%d errno=%d\n",
                mode,protected,!!filtered,pass,result,errno);
        CHECK(!peek && (mode==6 || mode==9) ? result==-1 && errno==(mode==6 ? EFAULT : ETOOMANYREFS)
            : result==(int)count);
        if (mode>=7 && mode<10 && !peek) CHECK(!setrlimit(RLIMIT_NOFILE,&limit));
        while (held_count) CHECK(!close(held[--held_count]));
        if (mode==9 && !peek) {
            for (unsigned i=0;i<1024;++i) CHECK(fcntl((int)i,F_GETFD)==before[i]);
            continue;
        }
        if (!peek && (mode==7 || mode==8)) {
            for (unsigned i=expected;i<253;++i) {
                int untouched; memcpy(&untouched,control[0].bytes+CMSG_LEN(0)+i*sizeof(int),sizeof(untouched));
                CHECK(!untouched);
            }
        }
        for (unsigned i=0;i<count;++i) {
            CHECK(bytes[i]=='s' && (mode!=3 || m[i].msg_len==1));
            if (!peek && (mode==5 || mode==6)) {
                int installed[253]; unsigned n=0;
                for (unsigned fd=0;fd<1024;++fd) if (before[fd]<0 && fcntl((int)fd,F_GETFD)>=0) {
                    CHECK(n<253); installed[n++]=(int)fd;
                }
                CHECK(n==expected); origin_descriptors(installed,n,flags);
            } else origin_rights(&m[i].msg_hdr,expected,flags,mode);
        }
        if (memory) CHECK(!munmap(memory,3*page));
    }
    printf("PASS capsule guest receiver mode=%u protected=%d filtered=%d\n",mode,protected,!!filtered);
}
static void addressed_receive(int socket, unsigned mode) {
    /* EVENT_WAIT: one test datagram; expiry is a failed delivery, not readiness. */
    struct pollfd ready={.fd=socket,.events=POLLIN}; CHECK(poll(&ready,1,15000)==1);
    char data=0; struct iovec io={&data,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))+CMSG_SPACE(sizeof(struct ucred))]; } control;
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=&control,.msg_controllen=sizeof(control)};
    ssize_t received=recvmsg(socket,&m,MSG_CMSG_CLOEXEC);
    if (received!=(mode==7 ? 0 : 1))
        fprintf(stderr,"addressed receive mode=%u socket=%d result=%zd errno=%d\n",mode,socket,received,errno);
    CHECK(received==(mode==7 ? 0 : 1));
    CHECK((mode==7 || data=='a') && !(m.msg_flags&(MSG_CTRUNC|MSG_TRUNC)));
    unsigned count=0;
    for (struct cmsghdr *h=CMSG_FIRSTHDR(&m);h;h=CMSG_NXTHDR(&m,h)) {
        if (h->cmsg_level!=SOL_SOCKET || h->cmsg_type!=SCM_RIGHTS) continue;
        unsigned n=(h->cmsg_len-CMSG_LEN(0))/sizeof(int);
        for (unsigned i=0;i<n;++i) {
            int fd; memcpy(&fd,(char *)CMSG_DATA(h)+i*sizeof(fd),sizeof(fd));
            struct stat st; CHECK(!fstat(fd,&st));
            CHECK(i%2 ? S_ISDIR(st.st_mode) && (fcntl(fd,F_GETFL)&O_PATH) : S_ISCHR(st.st_mode));
            CHECK(fcntl(fd,F_GETFD)&FD_CLOEXEC); CHECK(!close(fd));
        }
        count+=n;
    }
    CHECK(count==(mode==0 || mode==4 ? 0 : mode==2 ? 253 : 1));
}
static void capture_channel_admission(int socket, int full) {
    /* The native launcher denies pidfd capture. Logical dumpability is varied
     * independently; neither mode may change the required channel custody. */
    int dumpable=prctl(PR_GET_DUMPABLE);
    CHECK(dumpable>=0 && !prctl(PR_SET_DUMPABLE,0));
    int before[1024];
    for (unsigned i=0;i<1024;++i) before[i]=fcntl(i,F_GETFD);
    char byte; struct iovec io={&byte,1};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1};
    CHECK(recvmsg(socket,&m,MSG_DONTWAIT)==-1 && errno==(full ? EMFILE : EAGAIN));
    unsigned admitted=0;
    for (unsigned i=0;i<1024;++i) {
        int after=fcntl(i,F_GETFD);
        if (before[i]==after) continue;
        CHECK(!full && before[i]<0 && after==FD_CLOEXEC);
        int type=0; socklen_t size=sizeof(type);
        CHECK(!getsockopt(i,SOL_SOCKET,SO_TYPE,&type,&size) && type==SOCK_SEQPACKET);
        ++admitted;
    }
    CHECK(admitted==(full ? 0U : 1U));
    CHECK(!prctl(PR_SET_DUMPABLE,dumpable));
}
static void addressed_send(const char *path, unsigned protected, unsigned full, int local, uid_t uid, gid_t gid) {
    CHECK(!getenv("MAGICDESK_TEST_CAPTURE_FALLBACK") && !prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0));
    if (!strcmp(path,"addressed")) CHECK(!chdir("/attached"));
    struct sockaddr_un a={.sun_family=AF_UNIX};
    CHECK(strlen(path)<sizeof(a.sun_path)); strcpy(a.sun_path,path);
    socklen_t length=offsetof(struct sockaddr_un,sun_path)+strlen(path)+1;
    if (*path=='@') { a.sun_path[0]=0; a.sun_path[strlen(path)+1]='z'; length+=2; }
    int server=-1;
    if (local>0) { server=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0); CHECK(server>=0 && !bind(server,(void *)&a,length)); }
    int socket_fd=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0), file=open("/dev/null",O_RDONLY|O_CLOEXEC);
    int directory=open("/dev",O_PATH|O_DIRECTORY|O_CLOEXEC); CHECK(socket_fd>=0 && file>=0 && directory>=0);
    socket_fd=md_test_capture_socket(socket_fd);
    if (protected) CHECK(!prctl(PR_SET_DUMPABLE,0));
    /* SO_DOMAIN is unavailable to the guest; custody, not a preflight guess,
     * must select the address path and ancillary transport. */
    struct sock_filter code[]={BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getsockopt,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SO_DOMAIN,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog filter={sizeof(code)/sizeof(*code),code};
    CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0) && !prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
    int held[1024]; unsigned held_count=0; struct rlimit previous;
    if (full) {
        CHECK(!getrlimit(RLIMIT_NOFILE,&previous)); struct rlimit limit=previous; limit.rlim_cur=1024;
        CHECK(!setrlimit(RLIMIT_NOFILE,&limit));
        int fd; while ((fd=dup(file))>=0) { CHECK(held_count<1024); held[held_count++]=fd; }
        CHECK(errno==EMFILE);
        capture_channel_admission(socket_fd,1);
        CHECK(held_count && !close(held[--held_count]));
    }
    /* Any required export channel is admitted once. Every subsequent send
     * preserves this exact baseline, including a full application table. */
    capture_channel_admission(socket_fd,0);
    for (unsigned mode=0;mode<9;++mode) {
        char byte='a'; struct iovec io={mode==6 ? (void *)1 : &byte,mode==7 ? 0 : 1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))+CMSG_SPACE(sizeof(struct ucred))]; } control={0};
        unsigned rights=mode==0 || mode==4 ? 0 : mode==2 ? 253 : 1;
        size_t size=rights ? CMSG_SPACE(rights*sizeof(int)) : 0;
        control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(rights*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        for (unsigned i=0;i<rights;++i) { int fd=i%2 ? directory : file; memcpy(control.bytes+CMSG_LEN(0)+i*sizeof(int),&fd,sizeof(fd)); }
        if (mode==2 || mode==5 || mode==8) {
            struct cmsghdr *h=(void *)(control.bytes+size);
            *h=(struct cmsghdr){.cmsg_level=mode==8 ? IPPROTO_IP : SOL_SOCKET,
                .cmsg_type=mode==2 ? SCM_CREDENTIALS : mode==8 ? IP_TTL : 0x7fffffff,
                .cmsg_len=CMSG_LEN(mode==2 ? sizeof(struct ucred) : sizeof(int))};
            struct ucred cred={getpid(),uid,gid}; memcpy(CMSG_DATA(h),&cred,mode==2 ? sizeof(cred) : sizeof(int));
            size+=CMSG_SPACE(mode==2 ? sizeof(cred) : sizeof(int));
        }
        struct mmsghdr messages[2]={{.msg_hdr={.msg_name=&a,.msg_namelen=length,.msg_iov=&io,.msg_iovlen=1,
            .msg_control=size ? &control : NULL,.msg_controllen=size}}}; messages[1]=messages[0];
        int before[1024]; for (unsigned i=0;i<1024;++i) before[i]=fcntl(i,F_GETFD);
        long result=mode==3 ? sendmmsg(socket_fd,messages,2,MSG_NOSIGNAL) : mode==4
            ? sendto(socket_fd,&byte,1,MSG_NOSIGNAL,(void *)&a,length) : sendmsg(socket_fd,&messages[0].msg_hdr,MSG_NOSIGNAL);
        if (mode==5 || mode==6) CHECK(result==-1 && errno==(mode==5 ? EINVAL : EFAULT));
        else if (local<0) CHECK(result==-1 && errno==ECONNREFUSED);
        else CHECK(result==(mode==3 ? 2 : mode==7 ? 0 : 1));
        for (unsigned i=0;i<1024;++i) CHECK(before[i]==fcntl(i,F_GETFD));
        if (local>0 && mode!=5 && mode!=6) {
            while (held_count) CHECK(!close(held[--held_count]));
            for (unsigned i=0;i<(mode==3 ? 2U : 1U);++i) addressed_receive(server,mode);
            if (full) {
                int fd; while ((fd=dup(file))>=0) { CHECK(held_count<1024); held[held_count++]=fd; }
                CHECK(errno==EMFILE);
            }
        }
    }
    if (full) CHECK(!setrlimit(RLIMIT_NOFILE,&previous));
    while (held_count) CHECK(!close(held[--held_count]));
    CHECK(!close(socket_fd) && !close(file) && !close(directory));
    if (local>0) { CHECK(!close(server)); if (*path!='@') CHECK(!unlink(path)); }
    printf("PASS addressed sender protected=%u full=%u local=%d\n",protected,full,local);
}
static void addressed_fixture(const char *runner, const char *store, const char *directory) {
    alarm(180);
    int cwd=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); CHECK(cwd>=0 && !chdir(directory));
    for (unsigned type=0;type<4;++type) for (unsigned protected=0;protected<2;++protected)
            for (unsigned full=0;full<2;++full) {
        int local=type>=2, server=-1;
        const char *path="addressed";
        if (!local) {
            struct sockaddr_un a={.sun_family=AF_UNIX}; strcpy(a.sun_path,path);
            server=socket(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0);
            CHECK(server>=0);
            if (bind(server,(void *)&a,offsetof(struct sockaddr_un,sun_path)+strlen(path)+1)) {
                CHECK(errno==EACCES); CHECK(!close(server));
                puts("LIMIT native socket-file creation; checking retained native path rejection instead");
                int file=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); CHECK(file>=0 && !close(file));
                server=-1; local=-1;
            }
        }
        pid_t child=fork(); CHECK(child>=0);
        if (!child) {
            char p[8],f[8],l[8],u[16],g[16]; snprintf(p,sizeof(p),"%u",protected); snprintf(f,sizeof(f),"%u",full);
            snprintf(l,sizeof(l),"%d",local); snprintf(u,sizeof(u),"%u",getuid()); snprintf(g,sizeof(g),"%u",getgid());
            execl(runner,runner,"--store",store,"--user","0:0","--bind",directory,"/attached","--deadline-seconds","20",
                "--env","MAGICDESK_TEST_CAPTURE_FALLBACK=1",
                "--","/bin/test","--address-send",type==0 ? "/attached/addressed" : type==1 ? "addressed"
                    : type==2 ? "/tmp/addressed" : "@md-binary-address",p,f,l,u,g,(char *)NULL);
            _exit(90);
        }
        if (!local) for (unsigned mode=0;mode<9;++mode) if (mode!=5 && mode!=6)
            for (unsigned i=0;i<(mode==3 ? 2U : 1U);++i) addressed_receive(server,mode);
        done(child);
        if (local<=0) { if (server>=0) CHECK(!close(server)); CHECK(!unlink(path)); }
    }
    CHECK(!fchdir(cwd) && !close(cwd));
    puts("PASS addressed send custody: absolute/relative anchors, inode and binary abstract destinations");
}
static int boundary_send(int socket, int fd, int batch) {
    char byte='n'; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(sizeof(int))};
    memcpy(CMSG_DATA(&control.align),&fd,sizeof(fd));
    struct mmsghdr m={.msg_hdr={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)}};
    return batch ? sendmmsg(socket,&m,1,MSG_NOSIGNAL) : (int)sendmsg(socket,&m.msg_hdr,MSG_NOSIGNAL);
}
static int boundary_receive(int socket) {
    char byte; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    /* EVENT_WAIT: peer publishes one descriptor; launch/alarm expiry fails. */
    CHECK(recvmsg(socket,&m,MSG_CMSG_CLOEXEC)==1 && byte=='n' && !(m.msg_flags&MSG_CTRUNC));
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    CHECK(h && h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS && h->cmsg_len==CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd,CMSG_DATA(h),sizeof(fd)); return fd;
}
static void boundary_guest(int mode, int protected) {
    if (protected) CHECK(!prctl(PR_SET_DUMPABLE,0));
    int framed[2]; CHECK(!socketpair(AF_UNIX,SOCK_STREAM,0,framed));
    int file=(int)syscall(SYS_memfd_create,"boundary",0); CHECK(file>=0 && write(file,"v",1)==1);
    CHECK(boundary_send(framed[0],file,0)==1);
    for (int batch=0;batch<2;++batch) {
        CHECK(boundary_send(STDIN_FILENO,framed[1],batch)==-1 && errno==EXDEV);
    }
    int received=boundary_receive(framed[1]); char byte;
    CHECK(pread(received,&byte,1,0)==1 && byte=='v' && !close(received));
    CHECK(!close(framed[0]) && !close(framed[1]));
    int raw=mode==1 ? 3 : mode==2 ? boundary_receive(STDIN_FILENO) : socket(AF_UNIX,SOCK_STREAM,0);
    CHECK(raw>=0);
    if (!mode) CHECK(boundary_send(STDIN_FILENO,raw,protected)==1);
    int server=socket(AF_UNIX,SOCK_STREAM,0); CHECK(server>=0);
    struct sockaddr_un a={.sun_family=AF_UNIX};
    snprintf(a.sun_path+1,sizeof(a.sun_path)-1,"md-boundary-%d",getpid());
    socklen_t size=offsetof(struct sockaddr_un,sun_path)+1+strlen(a.sun_path+1);
    CHECK(!bind(server,(void *)&a,size) && !listen(server,1) && !connect(raw,(void *)&a,size));
    int accepted=accept(server,NULL,NULL); CHECK(accepted>=0);
    CHECK(write(STDIN_FILENO,"r",1)==1);
    CHECK(boundary_send(accepted,file,protected)==1);
    CHECK(read(accepted,&byte,1)==1 && byte=='k');
    CHECK(!close(accepted) && !close(server) && !close(raw) && !close(file));
    printf("PASS guest native boundary mode=%d protected=%d: rejected sendmsg/sendmmsg preserves queued capsule, native peer receives real FD\n",mode,protected);
}
static void boundary_fixture(const char *runner, const char *store) {
    alarm(120);
    for (int mode=0;mode<3;++mode) for (int protected=0;protected<2;++protected) {
        int control[2]; CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,control));
        int raw=mode ? socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0) : -1;
        CHECK(!mode || raw>=0);
        pid_t child=fork(); CHECK(child>=0);
        if (!child) {
            CHECK(!prctl(PR_SET_PDEATHSIG,SIGKILL));
            CHECK(dup2(control[1],STDIN_FILENO)==STDIN_FILENO);
            if (mode==1) CHECK(dup2(raw,3)==3 && !fcntl(3,F_SETFD,0));
            char m[4], p[4]; snprintf(m,sizeof(m),"%d",mode); snprintf(p,sizeof(p),"%d",protected);
            execl(runner,runner,"--store",store,"--user","0:0","--deadline-seconds","20",
                "--","/bin/test","--boundary",m,p,(char *)NULL); _exit(90);
        }
        CHECK(!close(control[1]));
        if (!mode) raw=boundary_receive(control[0]);
        if (mode==2) CHECK(boundary_send(control[0],raw,0)==1);
        char ready;
        CHECK(read(control[0],&ready,1)==1 && ready=='r');
        /* EVENT_WAIT: guest connect and accepted-side publication; alarm fails. */
        struct pollfd pollfd={raw,POLLIN,0};
        CHECK(poll(&pollfd,1,20000)==1 && (pollfd.revents&POLLIN));
        int file=boundary_receive(raw); char byte;
        CHECK(pread(file,&byte,1,0)==1 && byte=='v' && write(raw,"k",1)==1 && !close(file));
        done(child); CHECK(!close(raw));
        CHECK(!read(control[0],&byte,1) && !close(control[0]));
    }
}
#include "test_ipc_io_guest.c"
int main(int argc,char **argv) {
    setvbuf(stdout,NULL,_IONBF,0);
    if (argc==5 && !strcmp(argv[1],"--send-lifetime")) {
        alarm(20); send_lifetime(atoi(argv[2]),atoi(argv[3]),atoi(argv[4])); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--io")) { io_contract((unsigned)atoi(argv[2])); return 0; }
    if (argc==2 && !strcmp(argv[1],"--nonunix-rights")) { alarm(20); nonunix_rights(); return 0; }
    if (argc==4 && !strcmp(argv[1],"--boundary-fixture")) { boundary_fixture(argv[2],argv[3]); return 0; }
    if (argc==5 && !strcmp(argv[1],"--address-fixture")) { addressed_fixture(argv[2],argv[3],argv[4]); return 0; }
    if (argc==8 && !strcmp(argv[1],"--address-send")) {
        addressed_send(argv[2],atoi(argv[3]),atoi(argv[4]),atoi(argv[5]),atoi(argv[6]),atoi(argv[7])); return 0;
    }
    if (argc==4 && (!strcmp(argv[1],"--custody") || !strcmp(argv[1],"--custody-native"))) {
        alarm(20);
        custody(!strcmp(argv[1],"--custody"),atoi(argv[2]),atoi(argv[3])); return 0;
    }
    CHECK(getuid()==0);
    if (argc==2 && !strcmp(argv[1],"--claimed-process")) {
        alarm(30); claimed_process();
        CHECK(!unshare(CLONE_NEWPID));
        pid_t child=fork(); CHECK(child>=0);
        if (!child) { CHECK(getpid()==1); claimed_process(); _exit(0); }
        done(child); return 0;
    }
    if (argc==4 && !strcmp(argv[1],"--socket-name")) {
        struct sockaddr_un observed={0}; socklen_t n=sizeof(observed);
        CHECK(!getsockname(atoi(argv[2]),(void *)&observed,&n));
        CHECK(n==offsetof(struct sockaddr_un,sun_path)+strlen(argv[3])+1 && !strcmp(observed.sun_path,argv[3]));
        return 0;
    }
    if (argc==2 && !strcmp(argv[1],"--socket-mounts")) { alarm(30); socket_mount_paths(argv[0]); return 0; }
    if (argc==2 && !strcmp(argv[1],"--socket-paths")) { alarm(30); socket_paths(); return 0; }
    if (argc==4 && !strcmp(argv[1],"--boundary")) { boundary_guest(atoi(argv[2]),atoi(argv[3])); return 0; }
    if (argc==4 && !strcmp(argv[1],"--origin-receive")) { origin_receive((unsigned)atoi(argv[2]),atoi(argv[3])); return 0; }
    if (argc==4 && !strcmp(argv[1],"--origin-send")) { origin_send((unsigned)atoi(argv[2]),atoi(argv[3])); return 0; }
    if (argc==6 && !strcmp(argv[1],"--external")) {
        external(argv[2],(pid_t)atoi(argv[3]),(uid_t)atoi(argv[4]),(gid_t)atoi(argv[5])); return 0;
    }
    if (argc==6 && !strcmp(argv[1],"--external-pid")) {
        CHECK(!unshare(CLONE_NEWPID));
        pid_t child=fork(); CHECK(child>=0);
        if (!child) { CHECK(getpid()==1); external(argv[2],0,(uid_t)atoi(argv[4]),(gid_t)atoi(argv[5])); _exit(0); }
        done(child); puts("PASS native peers across PID namespace with protected memory and prctl denied"); return 0;
    }
    CHECK(argc==1);
    socket_paths();
    pid_t mounted=fork(); CHECK(mounted>=0);
    if (!mounted) { socket_mount_paths(argv[0]); _exit(0); }
    done(mounted);
    send_contract();
    for (int protected=0;protected<2;++protected) for (int cancel=0;cancel<2;++cancel)
        receive_lifetime(protected,cancel);
    for (unsigned protected=0;protected<2;++protected) for (unsigned size=0;size<3;++size) {
        pid_t child=fork(); CHECK(child>=0);
        if (!child) { peer_full_table(protected,size==0 ? 0 : size==1 ? 3 : 65536); exit(0); }
        done(child);
    }
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
    claimed_process();
    truncation();
    bound_reuse();
    puts("PASS connection snapshots: pair, groups, pathname, abstract, fork, drop, dup, peer exit");
    return 0;
}
