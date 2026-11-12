#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/kcmp.h>
#include <linux/mount.h>
#include <linux/rtnetlink.h>
#include <linux/seccomp.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sched.h>
#include <poll.h>
#include <stdatomic.h>
#include <signal.h>
#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/epoll.h>
#include <sys/signalfd.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>
#include "syscall_catalog.h"

static volatile sig_atomic_t seen, cookie;
static unsigned char stack[65536];
static void handler(int signal, siginfo_t *info, void *context) {
    assert(signal == SIGSYS);
    ++seen; cookie = info->si_errno;
    if (info->si_code == 1) ((ucontext_t *)context)->uc_mcontext.regs[0] = 711;
}
__attribute__((noinline)) static long cold_mount(const char *target) {
    register long x0 __asm__("x0") = (long)"tmpfs";
    register long x1 __asm__("x1") = (long)target;
    register long x2 __asm__("x2") = (long)"tmpfs";
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    register long x8 __asm__("x8") = SYS_mount;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8) : "memory", "cc");
    return x0;
}
static void filter_call(int number,unsigned action) {
    struct sock_filter rules[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)number, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, action),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(rules) / sizeof(*rules), rules};
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
}
static void filter(unsigned action) { filter_call(SYS_mount,action); }
static int accept_calls(void) {
    alarm(20);
    for (unsigned filtered=0;filtered<2;++filtered) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (filtered) filter_call(SYS_accept4,SECCOMP_RET_ERRNO|EUCLEAN);
            errno=0; assert(syscall(SYS_accept,-1,NULL,NULL,~0UL)==-1 && errno==EBADF);
            for (unsigned named=0;named<2;++named) {
                struct sockaddr_un address={.sun_family=AF_UNIX};
                snprintf(address.sun_path+!named,sizeof(address.sun_path)-!named,
                    "%smd-accept-%d",named ? "/tmp/" : "",getpid());
                socklen_t length=offsetof(struct sockaddr_un,sun_path)+!named
                    +strlen(address.sun_path+!named)+named;
                int server=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);
                assert(server>=0 && !bind(server,(void *)&address,length) && !listen(server,4));
                for (unsigned output=0;output<3;++output) {
                    int client=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
                    assert(client>=0 && !connect(client,(void *)&address,length));
                    struct sockaddr_un peer={0}; socklen_t size=sizeof(peer);
                    void *target=output==2 ? (void *)1 : output ? &peer : NULL;
                    errno=0;
                    int accepted=syscall(SYS_accept,server,target,&size,~0UL);
                    if (output==2) assert(accepted==-1 && errno==EFAULT);
                    else {
                        assert(accepted>=0 && !(fcntl(accepted,F_GETFD)&FD_CLOEXEC)
                            && !(fcntl(accepted,F_GETFL)&O_NONBLOCK));
                        if (output) assert(peer.sun_family==AF_UNIX && size==offsetof(struct sockaddr_un,sun_path));
                        assert(write(client,"a",1)==1);
                        char value; assert(read(accepted,&value,1)==1 && value=='a');
                        close(accepted);
                    }
                    close(client);
                }
                close(server); if (named) assert(!unlink(address.sun_path));
            }
            if (filtered) {
                filter_call(SYS_accept,SECCOMP_RET_ERRNO|ENOMSG);
                errno=0; assert(syscall(SYS_accept,-1,NULL,NULL)==-1 && errno==ENOMSG);
                errno=0; assert(syscall(SYS_accept4,-1,NULL,NULL,0)==-1 && errno==EUCLEAN);
            }
            _exit(0);
        }
        int status; assert(waitpid(child,&status,0)==child && !status);
    }
    alarm(0);
    puts("PASS raw accept: addresses, FD flags, EFAULT and original guest-policy admission");
    return 0;
}
static int socket_service_filter(void) {
    alarm(30);
    for (unsigned datagram=0;datagram<2;++datagram) {
        struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
        int type=datagram ? SOCK_DGRAM : SOCK_STREAM;
        int server=socket(AF_INET,type|SOCK_CLOEXEC,0); assert(server>=0);
        assert(!bind(server,(void *)&address,sizeof(address)));
        if (!datagram) assert(!listen(server,4));
        socklen_t size=sizeof(address); assert(!getsockname(server,(void *)&address,&size));
        int channel[2]; assert(!pipe2(channel,O_CLOEXEC));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            close(channel[0]);
            filter_call(SYS_ppoll,SECCOMP_RET_KILL_PROCESS);
            assert(write(channel[1],"r",1)==1);
            struct sockaddr_storage peer; socklen_t length=sizeof(peer); char byte=0;
            int result=datagram ? syscall(SYS_recvfrom,server,&byte,1,0,&peer,&length)
                : syscall(SYS_accept4,server,&peer,&length,SOCK_CLOEXEC);
            assert(result>=0 && peer.ss_family==AF_INET);
            if (datagram) assert(result==1 && byte=='p'); else close(result);
            assert(write(channel[1],"d",1)==1);
            /* The application's own ppoll must still be killed. */
            struct timespec zero={0};
            syscall(SYS_ppoll,NULL,0,&zero,NULL,8);
            _exit(99);
        }
        close(channel[1]); char byte;
        assert(read(channel[0],&byte,1)==1 && byte=='r');
        int blocked=0,status=0,exited=0;
        for (unsigned attempt=0;attempt<2000 && !blocked && !exited;++attempt) {
            exited=waitpid(child,&status,WNOHANG)==child;
            if (exited) break;
            char path[80],text[256]; snprintf(path,sizeof(path),"/proc/%d/syscall",child);
            int fd=open(path,O_RDONLY|O_CLOEXEC);
            if (fd>=0) {
                ssize_t count=read(fd,text,sizeof(text)-1); close(fd);
                if (count>0) {
                    text[count]=0; long number; unsigned long descriptor;
                    blocked=sscanf(text,"%ld %lx",&number,&descriptor)==2
                        && number==(datagram ? SYS_recvfrom : SYS_accept4) && descriptor==(unsigned)server;
                }
            }
            if (!blocked) usleep(1000);
        }
        if (exited) fprintf(stderr,"service filter premature exit: datagram=%u status=%#x\n",datagram,status);
        assert(blocked && !exited);
        int client=socket(AF_INET,type|SOCK_CLOEXEC,0); assert(client>=0);
        assert(!connect(client,(void *)&address,sizeof(address)));
        if (datagram) assert(write(client,"p",1)==1);
        ssize_t completed=read(channel[0],&byte,1);
        assert(waitpid(child,&status,0)==child);
        if (completed!=1) fprintf(stderr,"service filter missing completion: datagram=%u status=%#x\n",datagram,status);
        assert(completed==1 && byte=='d' && WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        close(client); close(channel[0]); close(server);
    }
    alarm(0);
    puts("PASS socket service waits survive forbidden ppoll; application ppoll retains SIGSYS");
    return 0;
}
static int shared_socket_child(void *argument) {
    int *descriptor=argument;
    *descriptor=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_ROUTE);
    assert(*descriptor>=0);
    return 0;
}
static int vfork_shared_socket(void) {
    alarm(20);
    for (unsigned filtered=0;filtered<2;++filtered) {
        pid_t worker=fork(); assert(worker>=0);
        if (!worker) {
            assert(!unshare(CLONE_NEWNET));
            /* Avoid selectors inherited for low descriptors from the launcher. */
            int keepers[128];
            for (unsigned i=0;i<128;++i) { keepers[i]=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0); assert(keepers[i]>=0); }
            if (filtered) filter_call(SYS_getppid,SECCOMP_RET_ERRNO|EUCLEAN);
            void *memory=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            assert(memory!=MAP_FAILED);
            int fd=-1;
            pid_t child=clone(shared_socket_child,(char *)memory+65536,
                CLONE_VM|CLONE_FILES|CLONE_VFORK|SIGCHLD,&fd);
            assert(child>0 && fd>=0);
            int status; assert(waitpid(child,&status,0)==child && !status);
            int domain=0; socklen_t size=sizeof(domain);
            assert(!getsockopt(fd,SOL_SOCKET,SO_DOMAIN,&domain,&size) && domain==AF_NETLINK);
            struct { struct nlmsghdr header; struct ifinfomsg link; } request={
                .header={.nlmsg_len=NLMSG_LENGTH(sizeof(struct ifinfomsg)),.nlmsg_type=RTM_GETLINK,
                    .nlmsg_flags=NLM_F_REQUEST|NLM_F_DUMP,.nlmsg_seq=41},.link={.ifi_family=AF_UNSPEC}};
            struct sockaddr_nl address={.nl_family=AF_NETLINK};
            ssize_t sent=sendto(fd,&request,sizeof(request),0,(void *)&address,sizeof(address));
            if (sent!=sizeof(request)) fprintf(stderr,"shared socket send: result=%zd errno=%d\n",sent,errno);
            assert(sent==sizeof(request));
            char reply[8192]; ssize_t bytes=recv(fd,reply,sizeof(reply),0);
            assert(bytes>=(ssize_t)NLMSG_LENGTH(sizeof(struct ifinfomsg)));
            struct nlmsghdr header; memcpy(&header,reply,sizeof(header));
            assert(header.nlmsg_type==RTM_NEWLINK && header.nlmsg_seq==41);
            close(fd);
            for (unsigned i=0;i<128;++i) close(keepers[i]);
            assert(!munmap(memory,65536)); _exit(0);
        }
        int status; assert(waitpid(worker,&status,0)==worker && !status);
    }
    alarm(0);
    puts("PASS vfork shared files: child creates typed socket, parent resumes with its provider selectors");
    return 0;
}
static int namespace_service_filter(void) {
    alarm(20);
    assert(!unshare(CLONE_NEWNET));
    int net=open("/proc/self/ns/net",O_RDONLY|O_CLOEXEC); assert(net>=0);
    struct sock_filter rules[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_seccomp,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prctl,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,PR_SET_SECCOMP,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    for (unsigned i=0;i<3;++i) assert(!setns(net,CLONE_NEWNET));
    pid_t child=fork(); assert(child>=0);
    if (!child) { syscall(SYS_prctl,PR_SET_SECCOMP,SECCOMP_MODE_STRICT,0); _exit(12); }
    int status;
    assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
    assert(!unshare(CLONE_NEWPID));
    child=fork(); assert(child>=0);
    if (!child) _exit(getpid()==1 ? 0 : 11);
    assert(waitpid(child,&status,0)==child && !status);
    close(net); alarm(0);
    puts("PASS namespace services: network reentry and PID admission with forbidden filter installation");
    return 0;
}
static int relative_path_filter(void) {
    alarm(20);
    char directory[]="/tmp/md-relative-filter-XXXXXX";
    assert(mkdtemp(directory));
    int base=open(directory,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(base>=0);
    int file=openat(base,"file",O_CREAT|O_RDWR|O_CLOEXEC,0600); assert(file>=0);
    struct stat expected; assert(!fstat(file,&expected));
    int proc=open("/proc/self/ns",O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(proc>=0);
    int net=openat(proc,"net",O_RDONLY|O_CLOEXEC); assert(net>=0);
    struct stat network; assert(!fstat(net,&network)); close(net);
    for (unsigned scoped=0;scoped<2;++scoped) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!fchdir(base));
            if (scoped) assert(!chroot(directory));
            filter_call(SYS_readlinkat,SECCOMP_RET_KILL_PROCESS);
            filter_call(SYS_getcwd,SECCOMP_RET_KILL_PROCESS);
            filter_call(SYS_gettid,SECCOMP_RET_KILL_PROCESS);
            filter_call(SYS_getpid,SECCOMP_RET_KILL_PROCESS);
            filter_call(SYS_prctl,SECCOMP_RET_KILL_PROCESS);
            const int bases[]={base,AT_FDCWD};
            for (unsigned i=0;i<sizeof(bases)/sizeof(*bases);++i) {
                int fd=openat(bases[i],"file",O_RDONLY|O_CLOEXEC); assert(fd>=0);
                struct stat st; assert(!fstat(fd,&st) && st.st_ino==expected.st_ino && st.st_dev==expected.st_dev);
                close(fd);
                assert(!fstatat(bases[i],"file",&st,0) && st.st_ino==expected.st_ino);
                assert(!renameat(bases[i],"file",bases[i],"renamed"));
                assert(!renameat(bases[i],"renamed",bases[i],"file"));
            }
            net=openat(proc,"net",O_PATH|O_CLOEXEC); assert(net>=0);
            struct stat st; assert(!fstat(net,&st) && st.st_ino==network.st_ino && st.st_dev==network.st_dev);
            close(net);
            assert(!fchdir(proc));
            net=open("net",O_PATH|O_CLOEXEC); assert(net>=0);
            assert(!fstat(net,&st) && st.st_ino==network.st_ino && st.st_dev==network.st_dev);
            close(net);
            assert(!fchdir(base));
            errno=0; assert(openat(-1,"file",O_RDONLY)==-1 && errno==EBADF);
            errno=0; assert(openat(file,"file",O_RDONLY)==-1 && errno==ENOTDIR);
            /* Internal path resolution must not weaken application policy. */
            pid_t denied=fork(); assert(denied>=0);
            if (!denied) { char value[64]; syscall(SYS_readlinkat,proc,"net",value,sizeof(value)); _exit(99); }
            int status; assert(waitpid(denied,&status,0)==denied && WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
            _exit(0);
        }
        int status; assert(waitpid(child,&status,0)==child && !status);
    }
    close(file); close(proc); assert(!unlinkat(base,"file",0)); close(base);
    assert(!rmdir(directory)); alarm(0);
    puts("PASS relative paths: retained proc/ordinary dirfds and cwd survive filters, chroot and preserve errors");
    return 0;
}
static int socket_signal_fd;
static uintptr_t socket_signal_stack;
static void socket_signal_handler(int number,siginfo_t *info,void *context) {
    (void)info;
    ucontext_t *frame=context;
    assert(frame->uc_mcontext.sp>socket_signal_stack-65536
        && frame->uc_mcontext.sp<socket_signal_stack+65536);
    char value=(char)number;
    assert(write(socket_signal_fd,&value,1)==1);
}
static int socket_signals(void) {
    alarm(45);
    for (unsigned filtered=0;filtered<2;++filtered)
    for (unsigned inet=0;inet<2;++inet)
    for (unsigned operation=0;operation<3;++operation)
    for (unsigned output=0;output<2;++output)
    for (unsigned action=0;action<4;++action) {
        int family=inet ? AF_INET : AF_UNIX;
        int type=operation==2 ? SOCK_DGRAM : SOCK_STREAM;
        struct sockaddr_storage address={0}; socklen_t length;
        if (inet) {
            struct sockaddr_in *a=(void *)&address;
            a->sin_family=AF_INET; a->sin_addr.s_addr=htonl(INADDR_LOOPBACK);
            length=sizeof(*a);
        } else {
            struct sockaddr_un *a=(void *)&address;
            a->sun_family=AF_UNIX;
            snprintf(a->sun_path+1,sizeof(a->sun_path)-1,"md-accept-signal-%d",getpid());
            length=offsetof(struct sockaddr_un,sun_path)+1+strlen(a->sun_path+1);
        }
        int server=socket(family,type|SOCK_CLOEXEC,0);
        assert(server>=0 && !bind(server,(void *)&address,length));
        if (type==SOCK_STREAM) assert(!listen(server,4));
        assert(!getsockname(server,(void *)&address,&length));
        int channel[2]; assert(!pipe2(channel,O_CLOEXEC));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            close(channel[0]); socket_signal_fd=channel[1];
            socket_signal_stack=(uintptr_t)&server;
            struct sigaction sa={.sa_sigaction=socket_signal_handler,
                .sa_flags=SA_SIGINFO|(action>=2 ? SA_RESTART : 0)};
            if (!action) sa.sa_handler=SIG_DFL;
            assert(!sigaction(SIGTERM,&sa,NULL));
            sigset_t mask; sigemptyset(&mask); assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
            if (filtered) filter_call(SYS_getppid,SECCOMP_RET_ERRNO|EUCLEAN);
            if (action==3) {
                struct timeval timeout={.tv_sec=5};
                assert(!setsockopt(server,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)));
            }
            assert(write(channel[1],"r",1)==1);
            struct sockaddr_storage peer; socklen_t size=sizeof(peer);
            char payload;
            errno=0;
            int accepted=operation==2
                ? syscall(SYS_recvfrom,server,&payload,1,0,output ? &peer : NULL,&size)
                : syscall(operation ? SYS_accept4 : SYS_accept,server,output ? &peer : NULL,&size,0);
            if (action==1 || action==3) assert(accepted==-1 && errno==EINTR);
            else {
                assert(action==2 && accepted>=0);
                if (output && size) assert(peer.ss_family==family);
                if (operation==2) assert(accepted==1 && payload=='p'); else close(accepted);
            }
            _exit(0);
        }
        close(channel[1]); char value;
        assert(read(channel[0],&value,1)==1 && value=='r');
        /* Repeated delivery also covers a signal arriving before accept enters.
         * The pipe observes a handler or terminal close, never a settling delay. */
        int status=0, exited=0, notified=0;
        for (unsigned attempt=0;attempt<100 && !exited;++attempt) {
            assert(!kill(child,SIGTERM));
            struct pollfd event={channel[0],POLLIN|POLLHUP,0};
            assert(poll(&event,1,20)>=0);
            if (event.revents&POLLIN) { assert(read(channel[0],&value,1)==1 && value==SIGTERM); notified=1; }
            pid_t waited=waitpid(child,&status,WNOHANG); assert(waited==0 || waited==child);
            exited=waited==child;
            if (action==2 && notified) break;
        }
        if (action==2 && notified) {
            int client=socket(family,type|SOCK_CLOEXEC,0);
            assert(client>=0 && !connect(client,(void *)&address,length));
            if (operation==2) assert(write(client,"p",1)==1);
            assert(waitpid(child,&status,0)==child); exited=1; close(client);
        }
        if (!exited) { kill(child,SIGKILL); waitpid(child,&status,0); }
        close(channel[0]); close(server);
        assert(exited);
        if (!action) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGTERM);
        else assert(notified && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    alarm(0);
    puts("PASS blocking sockets: termination, EINTR, SA_RESTART, receive timeout, Unix/INET, addresses and filters");
    return 0;
}
struct socket_tsync_wait { int server,datagram; atomic_int tid; };
static void *socket_tsync_receiver(void *data) {
    struct socket_tsync_wait *wait=data;
    atomic_store(&wait->tid,(int)syscall(SYS_gettid));
    struct sockaddr_storage address; socklen_t size=sizeof(address); char byte;
    int result=wait->datagram
        ? syscall(SYS_recvfrom,wait->server,&byte,1,0,&address,&size)
        : syscall(SYS_accept4,wait->server,&address,&size,0);
    if (result<0) fprintf(stderr,"socket TSYNC datagram=%d errno=%d\n",wait->datagram,errno);
    assert(result>=0);
    if (wait->datagram) assert(result==1 && byte=='t'); else close(result);
    errno=0;
    assert(syscall(wait->datagram ? SYS_recvfrom : SYS_accept4,-1,NULL,0,0,NULL,0)==-1 && errno==EUCLEAN);
    return NULL;
}
static int socket_tsync(void) {
    alarm(20);
    for (int datagram=0;datagram<2;++datagram) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
            int type=datagram ? SOCK_DGRAM : SOCK_STREAM;
            int server=socket(AF_INET,type|SOCK_CLOEXEC,0); assert(server>=0);
            assert(!bind(server,(void *)&address,sizeof(address)));
            if (!datagram) assert(!listen(server,4));
            socklen_t size=sizeof(address); assert(!getsockname(server,(void *)&address,&size));
            struct socket_tsync_wait wait={.server=server,.datagram=datagram}; atomic_init(&wait.tid,0);
            pthread_t thread; assert(!pthread_create(&thread,NULL,socket_tsync_receiver,&wait));
            int blocked=0;
            for (unsigned attempt=0;attempt<2000 && !blocked;++attempt) {
                int tid=atomic_load(&wait.tid);
                if (tid) {
                    char path[80],text[256]; snprintf(path,sizeof(path),"/proc/self/task/%d/syscall",tid);
                    int fd=open(path,O_RDONLY|O_CLOEXEC); assert(fd>=0);
                    ssize_t count=read(fd,text,sizeof(text)-1); assert(count>=0); close(fd); text[count]=0;
                    long number; unsigned long descriptor;
                    blocked=sscanf(text,"%ld %lx",&number,&descriptor)==2
                        && number==(datagram ? SYS_recvfrom : SYS_accept4) && descriptor==(unsigned)server;
                }
                if (!blocked) usleep(1000);
            }
            assert(blocked);
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,datagram ? SYS_recvfrom : SYS_accept4,0,1),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EUCLEAN),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
            struct sock_fprog program={sizeof(code)/sizeof(*code),code};
            assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program));
            int client=socket(AF_INET,type|SOCK_CLOEXEC,0); assert(client>=0);
            assert(!connect(client,(void *)&address,sizeof(address)));
            if (datagram) assert(write(client,"t",1)==1);
            assert(!pthread_join(thread,NULL)); close(client); close(server); _exit(0);
        }
        int status; assert(waitpid(child,&status,0)==child && !status);
    }
    alarm(0);
    puts("PASS socket TSYNC: pending admitted accept/receive survives, next call obeys new policy");
    return 0;
}
static int socket_child_signals(void) {
    alarm(30);
    struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(INADDR_LOOPBACK)};
    int server=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0); assert(server>=0);
    assert(!bind(server,(void *)&address,sizeof(address)) && !listen(server,4));
    socklen_t size=sizeof(address); assert(!getsockname(server,(void *)&address,&size));
    socket_signal_stack=(uintptr_t)&server;
    socket_signal_fd=open("/dev/null",O_WRONLY|O_CLOEXEC); assert(socket_signal_fd>=0);
    struct sigaction action={.sa_sigaction=socket_signal_handler,.sa_flags=SA_SIGINFO|SA_RESTART};
    assert(!sigaction(SIGCHLD,&action,NULL));
    for (unsigned i=0;i<256;++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            int client=socket(AF_INET,SOCK_STREAM|SOCK_CLOEXEC,0); assert(client>=0);
            assert(!connect(client,(void *)&address,size));
            _exit(0);
        }
        struct sockaddr_storage peer; socklen_t length=sizeof(peer);
        int accepted=accept4(server,(void *)&peer,&length,SOCK_CLOEXEC); assert(accepted>=0);
        int status; assert(waitpid(child,&status,0)==child && !status); close(accepted);
    }
    close(socket_signal_fd); close(server); alarm(0);
    puts("PASS kernel SIGCHLD around socket effects retains the application signal context");
    return 0;
}
__attribute__((noinline)) static long cold_setuid(unsigned uid) {
    register long x0 __asm__("x0")=uid;
    register long x8 __asm__("x8")=SYS_setuid;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8) : "memory", "cc");
    return x0;
}
static int cold_identity(int denied) {
    uid_t before=getuid();
    assert(!before);
    if (denied) filter_call(SYS_setuid,SECCOMP_RET_ERRNO|EUCLEAN);
    assert(cold_setuid(1234)==(denied ? -EUCLEAN : 0));
    uid_t real,effective,saved;
    assert(!getresuid(&real,&effective,&saved));
    assert(real==(denied ? before : 1234) && effective==real && saved==real);
    errno=0;
    assert(syscall(SYS_getresuid,(void *)1,&effective,&saved)==-1 && errno==EFAULT);
    puts("PASS cold credentials: raw setuid, pointer results and guest-filter precedence");
    return 0;
}
static void oom_write(const char *value) {
    int fd=open("/proc/self/oom_score_adj",O_WRONLY|O_CLOEXEC); assert(fd>=0);
    assert(write(fd,value,strlen(value))==(ssize_t)strlen(value)); close(fd);
}
static int oom_read(void) {
    char value[32]={0};
    int fd=open("/proc/self/oom_score_adj",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    assert(read(fd,value,sizeof(value)-1)>0); close(fd); return atoi(value);
}
static int oom_shared_child(void *unused) {
    (void)unused;
    assert(oom_read()==-900); oom_write("-700"); assert(oom_read()==-700);
    return 0;
}
static int oom_sharing(void) {
    void *memory=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED);
    for (unsigned borrow=0;borrow<2;++borrow) {
        oom_write("-900");
        pid_t child=clone(oom_shared_child,(char *)memory+65536,CLONE_VM|SIGCHLD|(borrow ? CLONE_VFORK : 0),NULL);
        assert(child>0);
        int status; assert(waitpid(child,&status,0)==child && !status);
        assert(oom_read()==(borrow ? -900 : -700));
    }
    assert(!munmap(memory,65536));
    puts("PASS OOM shared-mm propagation and private vfork policy");
    return 0;
}
static int init_signal_pipe[2],init_release_pipe[2];
static void init_handler(int signal) {
    char event=signal==SIGUSR1 ? 'U' : 'S';
    assert(signal==SIGUSR1 || signal==SIGSYS);
    ssize_t count=write(init_signal_pipe[1],&event,1);
    if (count!=1) dprintf(2,"init handler signal=%d write=%zd errno=%d\n",signal,count,errno);
    assert(count==1);
}
static int protected_init(void *unused) {
    (void)unused;
    assert(getpid()==1);
    close(init_signal_pipe[0]); close(init_release_pipe[1]);
    struct sigaction action={.sa_handler=init_handler,.sa_flags=SA_RESTART};
    assert(!sigaction(SIGUSR1,&action,NULL) && !sigaction(SIGSYS,&action,NULL));
    action.sa_handler=SIG_DFL;
    assert(!sigaction(SIGTERM,&action,NULL));
    assert(!prctl(PR_SET_DUMPABLE,0));
    assert(write(init_signal_pipe[1],"R",1)==1);
    char event=0;
    ssize_t count=read(init_release_pipe[0],&event,1);
    if (count!=1 || event!='Q') dprintf(2,"init release read=%zd errno=%d event=%d\n",count,errno,event);
    assert(count==1 && event=='Q');
    assert(!prctl(PR_GET_DUMPABLE));
    return 0;
}
static void pid_init_signal_round(void) {
    sigset_t mask; sigemptyset(&mask);
    assert(!sigprocmask(SIG_SETMASK,&mask,NULL));
    assert(!pipe2(init_signal_pipe,O_CLOEXEC) && !pipe2(init_release_pipe,O_CLOEXEC));
    pid_t child=clone(protected_init,stack+sizeof(stack),CLONE_NEWPID|SIGCHLD,NULL);
    assert(child>0);
    close(init_signal_pipe[1]); close(init_release_pipe[0]);
    char event;
    assert(read(init_signal_pipe[0],&event,1)==1 && event=='R');
    assert(!kill(child,SIGTERM) && !kill(child,SIGUSR1));
    assert(read(init_signal_pipe[0],&event,1)==1 && event=='U');
    assert(!kill(child,SIGSYS));
    assert(read(init_signal_pipe[0],&event,1)==1 && event=='S');
    assert(write(init_release_pipe[1],"Q",1)==1);
    int status;
    assert(waitpid(child,&status,0)==child && !status);
    close(init_signal_pipe[0]); close(init_release_pipe[1]);
}
static int pid_init_signals(void) {
    alarm(20);
    for (unsigned round=0;round<32;++round) pid_init_signal_round();
    alarm(0);
    puts("PASS protected PID init: default suppression and caught ordinary/SIGSYS delivery");
    return 0;
}
static _Atomic unsigned entry_ready;
static volatile sig_atomic_t entry_seen;
static uint64_t entry_number;
static void entry_handler(int signal,siginfo_t *info,void *context) {
    ucontext_t *frame=context;
    assert(signal==SIGURG && info->si_signo==SIGURG && info->si_code==SI_TKILL);
    assert(frame->uc_mcontext.regs[0]==713 && frame->uc_mcontext.regs[8]==entry_number);
    entry_seen=1;
}
static void *entry_sender(void *context) {
    pthread_t *target=context;
    /* EVENT_WAIT: receiver publishes its register-only loop; alarm bounds failure. */
    while (!atomic_load_explicit(&entry_ready,memory_order_acquire)) sched_yield();
    assert(!pthread_kill(*target,SIGURG));
    return NULL;
}
static int signal_entry(int filtered) {
    alarm(15);
    struct sigaction action={.sa_sigaction=entry_handler,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGURG,&action,NULL));
    if (filtered) filter(SECCOMP_RET_ALLOW);
    const uint64_t numbers[]={UINT64_MAX,UINT32_MAX,UINT64_C(0x1234ffffffff),SYS_gettid};
    for (unsigned protected=0;protected<2;++protected) {
        assert(!prctl(PR_SET_DUMPABLE,!protected,0,0,0));
        for (unsigned i=0;i<sizeof(numbers)/sizeof(*numbers);++i) {
            entry_number=numbers[i]; entry_ready=0; entry_seen=0;
            pthread_t target=pthread_self(),sender;
            assert(!pthread_create(&sender,NULL,entry_sender,&target));
            register uint64_t x0 __asm__("x0")=713;
            register uint64_t x8 __asm__("x8")=entry_number;
            /* No syscall in this loop: x8 is arbitrary application state at delivery. */
            __asm__ volatile("mov w9, #1\n stlr w9, [%2]\n1: ldr w9, [%3]\n cbz w9, 1b"
                : "+r"(x0), "+r"(x8) : "r"(&entry_ready), "r"(&entry_seen) : "x9", "memory");
            assert(x0==713 && x8==entry_number && entry_seen==1);
            assert(!pthread_join(sender,NULL));
            assert(prctl(PR_GET_DUMPABLE)==!protected);
        }
    }
    assert(!prctl(PR_SET_DUMPABLE,1,0,0,0));
    alarm(0);
    puts("PASS signal entry: arbitrary x8 preserves handler arguments and interrupted context");
    return 0;
}
static int modern_mount(int filtered,int denied) {
    const char *target="/tmp/app-uid-modern-mount";
    assert(!mkdir(target,0700) || errno==EEXIST);
    if (filtered) filter_call(SYS_fsopen,denied ? SECCOMP_RET_ERRNO|EACCES : SECCOMP_RET_ALLOW);
    int context=(int)syscall(SYS_fsopen,"tmpfs",FSOPEN_CLOEXEC);
    if (denied) {
        assert(context==-1 && errno==EACCES);
    } else {
        assert(context>=0);
        assert(!syscall(SYS_fsconfig,context,FSCONFIG_SET_STRING,"size","1m",0));
        assert(!syscall(SYS_fsconfig,context,FSCONFIG_CMD_CREATE,NULL,NULL,0));
        int detached=(int)syscall(SYS_fsmount,context,FSMOUNT_CLOEXEC,0);
        assert(detached>=0 && !close(context));
        assert(!syscall(SYS_move_mount,detached,"",AT_FDCWD,target,MOVE_MOUNT_F_EMPTY_PATH));
        assert(!close(detached));
        const char *marker="/tmp/app-uid-modern-mount/marker";
        int fd=open(marker,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
        assert(fd>=0 && write(fd,"mounted",7)==7 && !close(fd));
        assert(!umount(target));
        assert(access(marker,F_OK)<0 && errno==ENOENT);
    }
    assert(!rmdir(target));
    puts("PASS modern mount: notification and task calls share original-policy admission");
    return 0;
}
static int policy(const char *mode) {
    const char *target = "/tmp/app-uid-mount";
    assert(!mkdir(target, 0700) || errno == EEXIST);
    struct sigaction action = {.sa_sigaction = handler, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask);
    stack_t alternate = {.ss_sp = stack, .ss_size = sizeof(stack)};
    assert(!sigaltstack(&alternate, NULL));
    assert(!sigaction(SIGSYS, &action, NULL));
    sigset_t set;
    sigemptyset(&set); sigaddset(&set, SIGSYS);
    if (!strcmp(mode, "blocked") || !strcmp(mode,"blocked-trap")) assert(!sigprocmask(SIG_BLOCK, &set, NULL));
    if (!strcmp(mode, "ignored") || !strcmp(mode,"ignored-trap")) {
        action.sa_handler = SIG_IGN;
        assert(!sigaction(SIGSYS, &action, NULL));
    }
    if (!strcmp(mode, "allow")) filter(SECCOMP_RET_ALLOW);
    if (!strcmp(mode, "errno")) filter(SECCOMP_RET_ERRNO | EACCES);
    if (!strcmp(mode, "trap") || !strcmp(mode,"blocked-trap") || !strcmp(mode,"ignored-trap")) filter(SECCOMP_RET_TRAP | 73);
    if (!strcmp(mode, "kill")) filter(SECCOMP_RET_KILL_PROCESS);
    long result = cold_mount(target);
    struct sigaction after;
    sigset_t mask;
    assert(!sigaction(SIGSYS, NULL, &after));
    assert(!sigprocmask(SIG_SETMASK, NULL, &mask));
    int handler_ok = after.sa_handler == action.sa_handler;
    int mask_ok = !!sigismember(&mask, SIGSYS) == !strcmp(mode, "blocked");
    long expected = !strcmp(mode, "errno") ? -EACCES : !strcmp(mode, "trap") ? 711 : 0;
    int delivery_ok = seen == (!strcmp(mode, "trap") ? 1 : 0)
        && cookie == (!strcmp(mode, "trap") ? 73 : 0);
    printf("POLICY mode=%s result=%ld expected=%ld seen=%d cookie=%d handler=%d mask=%d\n",
        mode, result, expected, seen, cookie, handler_ok, mask_ok);
    assert(!sigprocmask(SIG_UNBLOCK, &set, NULL));
    if (!result) {
        FILE *f = fopen("/tmp/app-uid-mount/marker", "w"); assert(f);
        assert(fputs("mounted", f) >= 0 && !fclose(f));
        assert(!umount(target));
    }
    if (result != expected || !delivery_ok || !handler_ok || !mask_ok) return 1;
    if (!strcmp(mode, "plain")) { assert(!raise(SIGSYS)); assert(seen == 1); }
    return 0;
}
static int host(void) {
    const long numbers[] = {SYS_getpid, SYS_waitid, SYS_mount, SYS_shmget,
        SYS_pidfd_getfd, SYS_faccessat2, SYS_fchmodat2, SYS_openat2,
        SYS_set_robust_list, SYS_get_robust_list, SYS_rseq, SYS_kcmp};
    for (unsigned i = 0; i < sizeof(numbers) / sizeof(*numbers); ++i) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            /* Invalid arguments: admission only, no mount or descriptor changes. */
            long value = syscall(numbers[i], -1L, -1L, -1L, -1L, -1L, -1L);
            printf("HOST nr=%ld result=%ld errno=%d\n", numbers[i], value, errno);
            _exit(0);
        }
        int status; assert(waitpid(child, &status, 0) == child);
        printf("HOST nr=%ld status=%#x\n", numbers[i], status);
    }
    return 0;
}
static int capability_calls(int unavailable) {
#define NUMBER(n) SYS_##n,
    const long optional[]={MD_HOST_OPTIONAL_CALLS(NUMBER)};
    const long absent[]={MD_UNAVAILABLE_CALLS(NUMBER)};
#undef NUMBER
    const long *numbers=unavailable ? absent : optional;
    unsigned count=unavailable ? sizeof(absent)/sizeof(*absent) : sizeof(optional)/sizeof(*optional);
    const unsigned actions[]={SECCOMP_RET_ALLOW,SECCOMP_RET_ALLOW,
        SECCOMP_RET_ERRNO|EUCLEAN,SECCOMP_RET_TRAP|73,SECCOMP_RET_KILL_PROCESS};
    for (unsigned i=0;i<count;++i) {
        for (unsigned mode=0;mode<sizeof(actions)/sizeof(*actions);++mode) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                seen=cookie=0;
                struct sigaction action={.sa_sigaction=handler,.sa_flags=SA_SIGINFO};
                sigemptyset(&action.sa_mask); assert(!sigaction(SIGSYS,&action,NULL));
                if (mode) filter_call(numbers[i],actions[mode]);
                errno=0;
                /* Invalid sizes/pointers cannot change registrations, NUMA policy or clocks. */
                long result=numbers[i]==SYS_kcmp
                    ? syscall(SYS_kcmp,getpid(),getpid(),KCMP_FILE,STDOUT_FILENO,STDOUT_FILENO)
                    : syscall(numbers[i],-1L,0L,0L,0L,0L,0L);
                if (mode<2) assert(!seen && ((numbers[i]==SYS_kcmp && result==0)
                    || (result==-1 && (unavailable ? errno==ENOSYS
                        : (errno==ENOSYS || errno==EINVAL || errno==ESRCH || errno==EFAULT || errno==EPERM)))));
                else if (mode==2) assert(result==-1 && errno==EUCLEAN && !seen);
                else if (mode==3) assert(result==711 && seen==1 && cookie==73);
                else abort();
                printf("PASS %s call nr=%ld mode=%u result=%ld errno=%d\n",
                    unavailable ? "unavailable" : "optional native",numbers[i],mode,result,errno);
                _exit(0);
            }
            int status; assert(waitpid(child,&status,0)==child);
            assert(mode==4 ? WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS : status==0);
        }
    }
    puts(unavailable ? "PASS unavailable guest providers retain explicit absence and guest policy"
        : "PASS optional native capabilities retain native admission and guest policy");
    return 0;
}
static int protected_proc(void) {
    assert(!prctl(PR_SET_DUMPABLE,0));
    const char *paths[]={"/proc/self/status","/proc/thread-self/stat","/proc/self/limits"};
    for (unsigned i=0;i<sizeof(paths)/sizeof(*paths);++i) {
        struct stat before,after;
        int result=stat(paths[i],&before);
        if (result) fprintf(stderr,"protected proc stat %s: %s\n",paths[i],strerror(errno));
        assert(!result);
        int fd=open(paths[i],O_RDONLY|O_CLOEXEC); assert(fd>=0);
        assert(!fstat(fd,&after) && before.st_dev==after.st_dev && before.st_ino==after.st_ino);
        char text[16384]; ssize_t size=read(fd,text,sizeof(text)-1); assert(size>0);
        text[size]=0;
        if (!i) assert(strstr(text,"Uid:\t0\t0\t0\t0\n"));
        char prefix[17];
        assert(pread(fd,prefix,sizeof(prefix),1)==sizeof(prefix) && !memcmp(prefix,text+1,sizeof(prefix)));
        assert(lseek(fd,0,SEEK_CUR)==size);
        int copy=dup(fd); assert(copy>=0); close(fd);
        assert(!fstat(copy,&after) && before.st_ino==after.st_ino);
        assert(pread(copy,prefix,sizeof(prefix),1)==sizeof(prefix) && !memcmp(prefix,text+1,sizeof(prefix)));
        close(copy);
        assert(prctl(PR_GET_DUMPABLE)==0);
    }
    struct stat threads;
    assert(!stat("/proc/self/task",&threads));
    if (threads.st_nlink!=3) fprintf(stderr,"protected proc task links=%lu\n",(unsigned long)threads.st_nlink);
    assert(threads.st_nlink==3);
    DIR *directory=opendir("/proc/self/task");
    if (!directory) fprintf(stderr,"protected proc task directory: %s\n",strerror(errno));
    assert(directory);
    unsigned count=0;
    struct dirent *entry;
    errno=0;
    while ((entry=readdir(directory))) if (entry->d_name[0]!='.') {
        assert(strtol(entry->d_name,NULL,10)==syscall(SYS_gettid)); ++count;
    }
    assert(!errno && count==1 && !closedir(directory));
    puts("PASS protected proc: path metadata, read, retained FD, logical identity, dumpable unchanged");
    return 0;
}
static int protected_proc_limits(void) {
    assert(!prctl(PR_SET_DUMPABLE,0));
    struct rlimit original; assert(!getrlimit(RLIMIT_NOFILE,&original));
    int parent=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
    for (unsigned slots=0;slots<3;++slots) {
        struct rlimit limit=original; limit.rlim_cur=slots;
        assert(!setrlimit(RLIMIT_NOFILE,&limit));
        const char *directories[]={"/proc/self","/proc/self/fd","/proc/self/fdinfo","/proc/thread-self/fdinfo"};
        for (unsigned i=0;i<sizeof(directories)/sizeof(*directories);++i) {
            int changed=chdir(directories[i]);
            if (changed) fprintf(stderr,"protected proc chdir %s slots=%u: %s\n",directories[i],slots,strerror(errno));
            assert(!changed);
            struct stat st; assert(!stat(".",&st) && S_ISDIR(st.st_mode));
            assert(!fchdir(parent));
        }
        assert(open("/dev/null",O_RDONLY)==-1 && errno==EMFILE);
        struct rlimit observed; assert(!getrlimit(RLIMIT_NOFILE,&observed));
        assert(observed.rlim_cur==slots && observed.rlim_max==original.rlim_max);
        assert(prctl(PR_GET_DUMPABLE)==0);
    }
    assert(!setrlimit(RLIMIT_NOFILE,&original)); close(parent);
    const char *roots[]={"/proc/self/fd","/proc/self/fdinfo"};
    for (unsigned i=0;i<sizeof(roots)/sizeof(*roots);++i) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            struct rlimit limit=original; limit.rlim_cur=0;
            assert(!setrlimit(RLIMIT_NOFILE,&limit));
            assert(!chdir(roots[i]));
            int result=chroot(".");
            if (result) fprintf(stderr,"protected proc chroot %s: %s\n",roots[i],strerror(errno));
            assert(!result && !chdir("/"));
            struct stat st; assert(!stat("/",&st) && S_ISDIR(st.st_mode));
            assert(stat("/../../etc/passwd",&st)==-1 && errno==ENOENT);
            assert(prctl(PR_GET_DUMPABLE)==0);
            _exit(0);
        }
        int status; assert(waitpid(child,&status,0)==child && status==0);
    }
    puts("PASS protected proc: zero/low descriptor limits, chdir/stat and unchanged allocation failure");
    return 0;
}
static uint64_t status_mask(const char *text,const char *field) {
    const char *p=strstr(text,field); assert(p);
    unsigned long long value;
    assert(sscanf(p+strlen(field),"%llx",&value)==1);
    return value;
}
static uint64_t status_signal(const char *text,const char *field) {
    return status_mask(text,field)&(UINT64_C(1)<<(SIGSYS-1));
}
static void signal_status(int disposition,int pending) {
    uint64_t mask;
    assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&mask,sizeof(mask)));
    const char *paths[]={"/proc/self/status","/proc/thread-self/status"};
    for (unsigned i=0;i<sizeof(paths)/sizeof(*paths);++i) {
        FILE *f=fopen(paths[i],"r"); assert(f);
        char text[16384]; size_t n=fread(text,1,sizeof(text)-1,f);
        assert(n && !ferror(f) && feof(f) && !fclose(f)); text[n]=0;
        assert(status_signal(text,"SigBlk:"));
        assert(status_mask(text,"SigBlk:")==mask);
        assert(!!status_signal(text,"SigCgt:")==!disposition);
        assert(!!status_signal(text,"SigIgn:")==(disposition==1));
        assert(!!status_signal(text,"SigPnd:")==pending);
    }
}
static int signal_views(void) {
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR2);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    for (int disposition=0;disposition<3;++disposition) {
        struct sigaction action={.sa_sigaction=handler,.sa_flags=SA_SIGINFO};
        if (disposition) action.sa_handler=disposition==1 ? SIG_IGN : SIG_DFL;
        assert(!sigaction(SIGSYS,&action,NULL));
        signal_status(disposition,0);
        assert(!syscall(SYS_tgkill,getpid(),syscall(SYS_gettid),SIGSYS));
        sigset_t pending; assert(!sigpending(&pending) && sigismember(&pending,SIGSYS));
        signal_status(disposition,1);
        action.sa_handler=SIG_IGN; assert(!sigaction(SIGSYS,&action,NULL));
        assert(!sigpending(&pending) && !sigismember(&pending,SIGSYS));
        signal_status(1,0);
    }
    assert(!sigprocmask(SIG_UNBLOCK,&set,NULL));
    puts("PASS signal views: proc status, blocked ignored signal, pending and disposition flush");
    return 0;
}
static int listener_signal_fd;
static void listener_signal(int signal) {
    assert(signal==SIGUSR1);
    char byte=1; assert(write(listener_signal_fd,&byte,1)==1);
}
static int listener_transfer(int mode) {
    pid_t parent=getpid();
    const char *target="/tmp/listener-mount";
    int interrupted=mode>=7 && mode<=10, restarting=mode==8 || mode==10;
    int adding=mode>=11 && mode<=17, fixed=mode==13 || mode==14 || mode==16;
    int send=mode==11 || mode==14 || mode==15 || mode==16, protected=mode>=15 && mode<=17;
    int cold=mode==5 || mode==6 || mode==9 || mode==10;
    if (cold) assert(!mkdir(target,0700) || errno==EEXIST);
    long number=mode==4 ? SYS_newfstatat : cold ? SYS_mount : SYS_getppid;
    int signal_pipe[2]; assert(!pipe2(signal_pipe,O_CLOEXEC));
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(sockets[0]); close(STDIN_FILENO);
        close(signal_pipe[0]); listener_signal_fd=signal_pipe[1];
        struct sigaction action={.sa_handler=listener_signal,.sa_flags=restarting ? SA_RESTART : 0};
        assert(!sigemptyset(&action.sa_mask) && !sigaction(SIGUSR1,&action,NULL));
        sigset_t unblocked; assert(!sigemptyset(&unblocked) && !sigaddset(&unblocked,SIGUSR1));
        assert(!sigprocmask(SIG_UNBLOCK,&unblocked,NULL));
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        if (fixed) {
            int source=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(source>=0);
            assert(dup2(source,42)==42 && !close(source));
        }
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,number,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
        if (fd!=STDIN_FILENO) fprintf(stderr,"listener allocation: fd=%d errno=%d (%s)\n",fd,errno,strerror(errno));
        assert(fd==STDIN_FILENO);
        char byte=1; struct iovec vector={&byte,1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
        struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
            .msg_control=&control,.msg_controllen=sizeof(control)};
        struct cmsghdr *header=CMSG_FIRSTHDR(&message);
        *header=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
        memcpy(CMSG_DATA(header),&fd,sizeof(fd));
        assert(sendmsg(sockets[1],&message,MSG_NOSIGNAL)==1); close(fd); close(sockets[1]);
        if (mode==18) {
            execl("/proc/self/exe","policy","listener-exec-child",NULL);
            perror("listener exec"); _exit(1);
        }
        struct stat metadata;
        long result=mode==4 ? syscall(SYS_newfstatat,AT_FDCWD,"/bin/sh",&metadata,0)
            : cold ? cold_mount(target) : syscall(SYS_getppid);
        int expected_error=mode==2 ? EACCES : mode==3 ? ENOSYS : interrupted && !restarting ? EINTR : 0;
        long expected=expected_error ? cold ? -expected_error : -1 : mode==1 ? parent
            : mode==4 || mode==6 ? 0 : adding ? fixed ? 42 : result : 713;
        if (result!=expected || (expected_error && errno!=expected_error))
            fprintf(stderr,"notification result: mode=%d value=%ld expected=%ld errno=%d (%s)\n",mode,result,expected,errno,strerror(errno));
        assert(result==expected && (!expected_error || cold || errno==expected_error));
        if (adding) {
            assert(result>=0 && (fcntl((int)result,F_GETFD)&FD_CLOEXEC));
            assert(!fstat((int)result,&metadata) && metadata.st_uid==123 && metadata.st_gid==456);
            char bytes[2]; assert(read((int)result,bytes,sizeof(bytes))==sizeof(bytes) && !memcmp(bytes,"cd",2));
            assert(!close((int)result));
        }
        if (mode==4) assert(S_ISREG(metadata.st_mode));
        if (mode==6) {
            int marker=open("/tmp/listener-mount/marker",O_CREAT|O_WRONLY|O_EXCL,0600);
            assert(marker>=0 && !close(marker) && !umount(target));
            assert(access("/tmp/listener-mount/marker",F_OK)<0 && errno==ENOENT);
        }
        _exit(0);
    }
    close(sockets[1]);
    close(signal_pipe[1]);
    char byte; struct iovec vector={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=&control,.msg_controllen=sizeof(control)};
    assert(recvmsg(sockets[0],&message,MSG_CMSG_CLOEXEC)==1
        && !(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(sizeof(int)));
    int received; memcpy(&received,CMSG_DATA(header),sizeof(received));
    assert(fcntl(received,F_GETFD)&FD_CLOEXEC);
    int listener=dup(received); assert(listener>=0); close(received); close(sockets[0]);
    struct seccomp_notif notification={0};
    int received_result=ioctl(listener,SECCOMP_IOCTL_NOTIF_RECV,&notification);
    if (received_result) fprintf(stderr,"notification receive: value=%d errno=%d (%s)\n",received_result,errno,strerror(errno));
    assert(!received_result);
    assert(notification.pid==(unsigned)child && notification.data.nr==number);
    assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&notification.id));
    if (mode==19) {
        assert(!kill(child,SIGKILL));
        int status;
        assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
        struct seccomp_notif_addfd add={.id=notification.id,.srcfd=(unsigned)fd,.flags=SECCOMP_ADDFD_FLAG_SEND};
        assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==-1 && errno==ENOENT);
        assert(fcntl(fd,F_GETFD)&FD_CLOEXEC);
        assert(!close(fd) && !close(listener) && !close(signal_pipe[0]));
        puts("PASS listener target death: stale ADDFD rejected without consuming source");
        return 0;
    }
    if (interrupted) {
        uint64_t cancelled=notification.id;
        assert(!kill(child,SIGUSR1) && read(signal_pipe[0],&byte,1)==1);
        assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&cancelled)==-1 && errno==ENOENT);
        if (restarting) {
            memset(&notification,0,sizeof(notification));
            assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_RECV,&notification));
            assert(notification.id!=cancelled && notification.data.nr==number);
        }
    }
    close(signal_pipe[0]);
    struct seccomp_notif_resp reply={.id=notification.id,.val=713};
    if (mode==1 || mode==4 || mode==6) { reply.val=0; reply.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE; }
    if (mode==2) { reply.val=0; reply.error=-EACCES; }
    int source=-1;
    if (adding) {
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        source=open("/tmp/listener-addfd",O_CREAT|O_TRUNC|O_RDWR|O_CLOEXEC,0600);
        if (source<0) perror("ADDFD source open");
        assert(source>=0);
        int owned=fchown(source,123,456);
        if (owned) perror("ADDFD source ownership");
        assert(!owned);
        assert(write(source,"abcdef",6)==6 && lseek(source,2,SEEK_SET)==2);
        struct seccomp_notif_addfd add={.id=notification.id,.srcfd=(unsigned)source,.newfd_flags=O_CLOEXEC,
            .flags=(fixed ? SECCOMP_ADDFD_FLAG_SETFD : 0)|(send ? SECCOMP_ADDFD_FLAG_SEND : 0),
            .newfd=fixed ? 42 : 0};
        int transferred=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
        if (transferred<0) fprintf(stderr,"ADDFD mode=%d errno=%d (%s)\n",mode,errno,strerror(errno));
        assert(transferred>=0 && (!fixed || transferred==42));
        if (send) assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&notification.id)==-1 && errno==ENOENT);
        else { assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&notification.id)); reply.val=transferred; }
    }
    if (mode!=3 && (!interrupted || restarting) && !send) assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply));
    close(listener);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    if (adding) assert(lseek(source,0,SEEK_CUR)==4 && !close(source));
    printf("PASS listener mode=%d: FD zero, SCM_RIGHTS, sender close, dup and original-call response\n",mode);
    return 0;
}
static int listener_rejection(void) {
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    close(STDIN_FILENO);
    struct sock_filter invalid[]={BPF_STMT(BPF_LD|BPF_MEM,0),BPF_STMT(BPF_RET|BPF_A,0)};
    struct sock_fprog program={2,invalid};
    assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program)==-1 && errno==EINVAL);
    int fd=open("/dev/null",O_RDONLY|O_CLOEXEC);
    assert(fd==STDIN_FILENO && !close(fd));
    struct sock_filter allow=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
    program=(struct sock_fprog){1,&allow};
    fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd==STDIN_FILENO);
    assert(syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program)==-1 && errno==EBUSY);
    assert(!close(fd));
    fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd==STDIN_FILENO && !close(fd));
    puts("PASS listener rejection: native invalid-BPF rollback, no FD-zero leak, live ancestor and last-close replacement");
    return 0;
}
static int trace_cookie(unsigned cookie) {
    const char *path="/tmp/trace-cookie-created";
    assert(!unlink(path) || errno==ENOENT);
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_rt_sigpending,4,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_newfstatat,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mount,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_TRACE|cookie)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    uint64_t pending=0;
    assert(syscall(SYS_rt_sigpending,&pending,sizeof(pending))==-1 && errno==ENOSYS);
    struct stat st;
    assert(syscall(SYS_newfstatat,AT_FDCWD,"/bin/sh",&st,0)==-1 && errno==ENOSYS);
    assert(syscall(SYS_openat,AT_FDCWD,path,O_CREAT|O_WRONLY|O_EXCL,0600)==-1 && errno==ENOSYS);
    assert(unlink(path)==-1 && errno==ENOENT);
    assert(cold_mount("/tmp")==-ENOSYS);
    printf("PASS unobserved TRACE cookie=%u\n",cookie);
    return 0;
}
static int signal_wait(int filtered) {
    if (filtered) {
        struct sock_filter rule=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog program={1,&rule};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    struct timespec zero={0},invalid={.tv_nsec=1000000000};
    sigset_t set,other,pending;
    sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR1); sigaddset(&set,SIGRTMIN);
    sigaddset(&set,SIGTRAP);
    sigemptyset(&other); sigaddset(&other,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    for (int protected=0;protected<=1;++protected) {
        assert(!prctl(PR_SET_DUMPABLE,!protected,0,0,0));
        assert(!raise(SIGSYS));
        siginfo_t info={0};
        assert(sigtimedwait(&set,&info,&invalid)==-1 && errno==EINVAL);
        assert(sigtimedwait(&other,&info,&zero)==-1 && errno==EAGAIN);
        assert(!raise(SIGRTMIN) && !raise(SIGUSR1) && !raise(SIGTRAP));
        assert(sigtimedwait(&set,&info,&zero)==SIGTRAP);
        assert(sigtimedwait(&set,&info,&zero)==SIGSYS);
        if (info.si_signo!=SIGSYS || info.si_pid!=getpid() || info.si_uid!=getuid())
            fprintf(stderr,"signal sender: signal=%d code=%d pid=%d uid=%u expected=%d:%u\n",
                info.si_signo,info.si_code,info.si_pid,info.si_uid,getpid(),getuid());
        assert(info.si_signo==SIGSYS && info.si_pid==getpid() && info.si_uid==getuid());
        assert(sigtimedwait(&set,&info,&zero)==SIGUSR1);
        assert(sigtimedwait(&set,&info,&zero)==SIGRTMIN);
        assert(!sigpending(&pending) && !sigismember(&pending,SIGSYS));
        assert(!raise(SIGSYS));
        assert(syscall(SYS_rt_sigtimedwait,&set,1,&zero,8)==-1 && errno==EFAULT);
        assert(!sigpending(&pending) && !sigismember(&pending,SIGSYS));
        assert(!raise(SIGSYS));
        assert(syscall(SYS_rt_sigtimedwait,1,&info,&zero,8)==-1 && errno==EFAULT);
        assert(syscall(SYS_rt_sigtimedwait,&set,&info,&zero,7)==-1 && errno==EINVAL);
        assert(!sigpending(&pending) && sigismember(&pending,SIGSYS));
        assert(sigtimedwait(&set,NULL,NULL)==SIGSYS);
        assert(sigtimedwait(&set,&info,&zero)==-1 && errno==EAGAIN);
        assert(!raise(SIGSYS) && !kill(getpid(),SIGUSR1));
        assert(sigtimedwait(&set,&info,&zero)==SIGSYS);
        assert(sigtimedwait(&set,&info,&zero)==SIGUSR1);
        assert(!kill(getpid(),SIGSYS) && !raise(SIGRTMIN));
        assert(sigtimedwait(&set,&info,&zero)==SIGRTMIN);
        assert(sigtimedwait(&set,&info,&zero)==SIGSYS);
    }
    assert(!prctl(PR_SET_DUMPABLE,1,0,0,0));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!sigqueue(parent,SIGSYS,(union sigval){.sival_int=729}));
        _exit(0);
    }
    siginfo_t info={0};
    assert(sigtimedwait(&set,&info,NULL)==SIGSYS && info.si_value.sival_int==729);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!sigprocmask(SIG_UNBLOCK,&set,NULL));
    puts("PASS sigtimedwait: priority, timeout validation, consumption, EFAULT, protected memory and queued payload");
    return 0;
}
struct waiting_thread { int ready[2],ack[2]; atomic_int tid; };
static void *queued_waiter(void *arg) {
    struct waiting_thread *test=arg;
    atomic_store(&test->tid,(int)syscall(SYS_gettid));
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS);
    for (int i=0;i<64;++i) {
        assert(write(test->ready[1],"r",1)==1);
        siginfo_t info={0}; struct timespec timeout={5,0};
        assert(sigtimedwait(&set,&info,&timeout)==SIGSYS);
        assert(info.si_code==SI_QUEUE && info.si_value.sival_int==i);
        assert(write(test->ack[1],"a",1)==1);
    }
    return NULL;
}
static int signal_wait_threads(void) {
    alarm(30);
    sigset_t set,pending; sigemptyset(&set); sigaddset(&set,SIGSYS);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    siginfo_t private={.si_signo=SIGSYS,.si_code=SI_QUEUE};
    private.si_value.sival_int=101;
    assert(!syscall(SYS_rt_tgsigqueueinfo,getpid(),syscall(SYS_gettid),SIGSYS,&private));
    assert(!sigqueue(getpid(),SIGSYS,(union sigval){.sival_int=202}));
    assert(!sigqueue(getpid(),SIGSYS,(union sigval){.sival_int=303}));
    siginfo_t info={0}; struct timespec zero={0};
    assert(sigtimedwait(&set,&info,&zero)==SIGSYS && info.si_value.sival_int==101);
    assert(!sigpending(&pending) && sigismember(&pending,SIGSYS));
    assert(sigtimedwait(&set,&info,&zero)==SIGSYS && info.si_value.sival_int==202);
    assert(sigtimedwait(&set,&info,&zero)==-1 && errno==EAGAIN);
    struct waiting_thread test={0};
    assert(!pipe(test.ready) && !pipe(test.ack));
    pthread_t thread; assert(!pthread_create(&thread,NULL,queued_waiter,&test));
    for (int i=0;i<64;++i) {
        char byte; assert(read(test.ready[0],&byte,1)==1);
        private.si_value.sival_int=i;
        if (i&1) assert(!syscall(SYS_rt_tgsigqueueinfo,getpid(),atomic_load(&test.tid),SIGSYS,&private));
        else assert(!sigqueue(getpid(),SIGSYS,(union sigval){.sival_int=i}));
        assert(read(test.ack[0],&byte,1)==1);
    }
    assert(!pthread_join(thread,NULL));
    for (int i=0;i<2;++i) { close(test.ready[i]); close(test.ack[i]); }
    assert(!sigpending(&pending) && !sigismember(&pending,SIGSYS));
    int pidfd=syscall(SYS_pidfd_open,getpid(),0); assert(pidfd>=0);
    private.si_value.sival_int=404;
    assert(!syscall(SYS_pidfd_send_signal,pidfd,SIGSYS,&private,0));
    assert(sigtimedwait(&set,&info,&zero)==SIGSYS && info.si_value.sival_int==404);
    close(pidfd);
    struct sigaction action={.sa_sigaction=handler,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGSYS,&action,NULL));
    sigset_t other; sigemptyset(&other); sigaddset(&other,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&other,NULL) && !sigprocmask(SIG_UNBLOCK,&set,NULL));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) { usleep(20000); assert(!kill(parent,SIGSYS)); _exit(0); }
    struct timespec limit={5,0};
    assert(sigtimedwait(&other,&info,&limit)==-1 && errno==EINTR && seen==1);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    private.si_code=SI_KERNEL; private.si_errno=93;
    assert(!syscall(SYS_rt_tgsigqueueinfo,getpid(),syscall(SYS_gettid),SIGSYS,&private));
    assert(seen==2 && cookie==93);
    pid_t self=getpid(),tid=syscall(SYS_gettid);
    pidfd=syscall(SYS_pidfd_open,self,0); assert(pidfd>=0);
    for (int i=0;i<128;++i) {
        sig_atomic_t before=seen;
        private.si_code=SI_QUEUE;
        private.si_errno=i;
        switch (i%6) {
        case 0: assert(!kill(self,SIGSYS)); break;
        case 1: assert(!syscall(SYS_tgkill,self,tid,SIGSYS)); break;
        case 2: assert(!syscall(SYS_tkill,tid,SIGSYS)); break;
        case 3: assert(!syscall(SYS_rt_sigqueueinfo,self,SIGSYS,&private)); break;
        case 4: assert(!syscall(SYS_rt_tgsigqueueinfo,self,tid,SIGSYS,&private)); break;
        default: assert(!syscall(SYS_pidfd_send_signal,pidfd,SIGSYS,&private,0)); break;
        }
        assert(seen==before+1);
        if (i%6>=3) assert(cookie==i);
    }
    close(pidfd);
    alarm(0);
    puts("PASS signal queue ownership: private/shared SI_QUEUE, coalescing and 64 cross-thread waits");
    return 0;
}
static volatile sig_atomic_t wait_seen;
static void wait_handler(int signal,siginfo_t *info,void *context) {
    (void)info;
    assert(signal==SIGSYS);
    ucontext_t *uc=context;
    assert(sigismember(&uc->uc_sigmask,SIGSYS) && sigismember(&uc->uc_sigmask,SIGUSR1));
    assert(!sigismember(&uc->uc_sigmask,SIGUSR2));
    sigset_t current;
    assert(!sigprocmask(SIG_SETMASK,NULL,&current));
    assert(sigismember(&current,SIGSYS) && sigismember(&current,SIGUSR2));
    assert(!sigismember(&current,SIGUSR1));
    ++wait_seen;
}
static long masked_wait(int mode,int epoll,const sigset_t *mask,int zero) {
    struct timespec timeout={0};
    struct epoll_event event;
    struct { const sigset_t *mask; size_t size; } arg={mask,8};
    switch (mode) {
    case 0: return syscall(SYS_ppoll,NULL,0,zero ? &timeout : NULL,mask,8);
    case 1: return syscall(SYS_pselect6,0,NULL,NULL,NULL,zero ? &timeout : NULL,&arg);
    case 2: return syscall(SYS_epoll_pwait,epoll,&event,1,zero ? 0 : -1,mask,8);
    case 3: return syscall(SYS_epoll_pwait2,epoll,&event,1,zero ? &timeout : NULL,mask,8);
    default: return syscall(SYS_rt_sigsuspend,mask,8);
    }
}
static int signal_wait_masks(int filtered) {
    if (filtered) {
        struct sock_filter rule=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog program={1,&rule};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    alarm(30);
    struct sigaction action={.sa_sigaction=wait_handler,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGSYS,&action,NULL));
    sigset_t original,temporary,current;
    sigemptyset(&original); sigaddset(&original,SIGSYS); sigaddset(&original,SIGUSR1);
    sigemptyset(&temporary); sigaddset(&temporary,SIGUSR2);
    assert(!sigprocmask(SIG_SETMASK,&original,NULL));
    int epoll=epoll_create1(EPOLL_CLOEXEC); assert(epoll>=0);
    for (int mode=0;mode<5;++mode) {
        assert(!raise(SIGSYS));
        assert(masked_wait(mode,epoll,&temporary,0)==-1 && errno==EINTR);
        assert(wait_seen==mode*2+1);
        assert(!sigprocmask(SIG_SETMASK,NULL,&current));
        assert(sigismember(&current,SIGSYS) && sigismember(&current,SIGUSR1) && !sigismember(&current,SIGUSR2));
        if (mode<4) assert(!masked_wait(mode,epoll,&temporary,1));
        pid_t parent=getpid(),child=fork(); assert(child>=0);
        if (!child) { usleep(20000); assert(!kill(parent,SIGSYS)); _exit(0); }
        assert(masked_wait(mode,epoll,&temporary,0)==-1 && errno==EINTR);
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(wait_seen==mode*2+2);
    }
    close(epoll); alarm(0);
    puts("PASS atomic signal masks: poll/select/epoll/suspend, pending and late signals, handler and ucontext masks");
    return 0;
}
static void *descriptor_update(void *context) {
    int fd=*(int *)context;
    usleep(20000);
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS);
    assert(signalfd(fd,&set,0)==fd);
    return NULL;
}
static int signal_descriptors(int filtered) {
    alarm(30);
    if (filtered) {
        struct sock_filter code=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog program={1,&code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    int fd=signalfd(-1,&set,SFD_NONBLOCK|SFD_CLOEXEC); assert(fd>=0);
    assert(fcntl(fd,F_GETFD)==FD_CLOEXEC);
    assert(signalfd(fd,&set,0)==fd && fcntl(fd,F_GETFD)==FD_CLOEXEC);
    int flags_copy=dup(fd); assert(flags_copy>=0 && fcntl(flags_copy,F_GETFD)==0);
    assert(signalfd(flags_copy,&set,SFD_CLOEXEC)==flags_copy && fcntl(flags_copy,F_GETFD)==0);
    assert(!close(flags_copy));
    struct signalfd_siginfo info[3];
    assert(read(fd,info,sizeof(info))==-1 && errno==EAGAIN);
    assert(!sigqueue(getpid(),SIGSYS,(union sigval){.sival_int=123}));
    struct pollfd pollfd={fd,POLLIN,0};
    assert(poll(&pollfd,1,0)==1 && pollfd.revents==POLLIN);
    assert(!raise(SIGUSR1));
    assert(read(fd,info,127)==-1 && errno==EINVAL);
    assert(read(fd,info,sizeof(info))==256);
    assert(info[0].ssi_signo==SIGUSR1 && info[1].ssi_signo==SIGSYS && info[1].ssi_int==123);
    int copy=dup(fd); assert(copy>=0 && !close(fd)); fd=copy;
    assert(!raise(SIGSYS));
    assert(read(fd,(void *)1,128)==-1 && errno==EFAULT);
    assert(read(fd,info,128)==-1 && errno==EAGAIN);
    sigset_t only; sigemptyset(&only); sigaddset(&only,SIGUSR1);
    assert(signalfd(fd,&only,0)==fd);
    assert(fcntl(fd,F_GETFL)&O_NONBLOCK);
    assert(!raise(SIGSYS));
    assert(read(fd,info,128)==-1 && errno==EAGAIN);
    assert(signalfd(fd,&set,0)==fd);
    assert(read(fd,info,128)==128 && info[0].ssi_signo==SIGSYS);
    assert(signalfd(fd,&only,0)==fd && !raise(SIGSYS));
    pthread_t updater;
    assert(!pthread_create(&updater,NULL,descriptor_update,&fd));
    pollfd=(struct pollfd){fd,POLLIN,0};
    assert(poll(&pollfd,1,5000)==1 && pollfd.revents==POLLIN);
    assert(!pthread_join(updater,NULL));
    assert(read(fd,info,128)==128 && info[0].ssi_signo==SIGSYS);
    assert(signalfd(fd,&set,0)==fd);
    assert(!raise(SIGUSR1) && !raise(SIGSYS));
    struct iovec vector[2]={{info,128},{info+1,128}};
    assert(readv(fd,vector,2)==256 && info[0].ssi_signo==SIGSYS && info[1].ssi_signo==SIGUSR1);
    assert(!raise(SIGSYS));
    vector[0]=(struct iovec){info,17}; vector[1]=(struct iovec){(char *)info+17,111};
    assert(readv(fd,vector,2)==128 && info[0].ssi_signo==SIGSYS);
    assert(!raise(SIGSYS));
    vector[1]=(struct iovec){(void *)1,111};
    assert(readv(fd,vector,2)==-1 && errno==EFAULT);
    assert(read(fd,info,128)==-1 && errno==EAGAIN);
    assert(!raise(SIGSYS));
    vector[0]=(struct iovec){info,SIZE_MAX};
    assert(readv(fd,vector,1)==-1 && errno==EINVAL);
    assert(read(fd,info,128)==128 && info[0].ssi_signo==SIGSYS);
    assert(!fcntl(fd,F_SETFL,0));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) { usleep(20000); assert(!sigqueue(parent,SIGSYS,(union sigval){.sival_int=456})); _exit(0); }
    pollfd=(struct pollfd){fd,POLLIN,0};
    assert(poll(&pollfd,1,5000)==1 && pollfd.revents==POLLIN);
    assert(read(fd,info,128)==128 && info[0].ssi_signo==SIGSYS && info[0].ssi_int==456);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!raise(SIGSYS));
        assert(read(fd,info,128)==128 && info[0].ssi_signo==SIGSYS);
        _exit(0);
    }
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!fcntl(fd,F_SETFL,O_NONBLOCK));
    assert(read(fd,info,128)==-1 && errno==EAGAIN);
    void *output=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); assert(output!=MAP_FAILED);
    assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!raise(SIGSYS));
    assert(read(fd,output,128)==128 && ((struct signalfd_siginfo *)output)->ssi_signo==SIGSYS);
    assert(!munmap(output,4096) && !close(fd));
    alarm(0);
    puts("PASS signalfd: native/logical priority, records, faults, dup, mask updates, flags, blocking, fork and protected output");
    return 0;
}
struct epoll_signal_sender { pthread_t target; int signal; };
static void *epoll_signal_send(void *opaque) {
    struct epoll_signal_sender *s=opaque;
    usleep(20000); assert(!pthread_kill(s->target,s->signal)); return NULL;
}
static int signal_epoll(int filtered) {
    alarm(30);
    if (filtered) {
        struct sock_filter code=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog program={1,&code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    int fd=signalfd(-1,&set,SFD_NONBLOCK|SFD_CLOEXEC),q=epoll_create1(EPOLL_CLOEXEC),p[2];
    assert(fd>=0 && q>=0 && !pipe(p));
    struct epoll_event add={.events=EPOLLIN,.data.u64=1},events[4];
    assert(!epoll_ctl(q,EPOLL_CTL_ADD,p[0],&add));
    assert(!epoll_ctl(q,EPOLL_CTL_ADD,fd,&add));
    assert(epoll_ctl(q,EPOLL_CTL_ADD,fd,&add)==-1 && errno==EEXIST);
    assert(!epoll_wait(q,events,4,0));
    assert(write(p[1],"p",1)==1 && !raise(SIGSYS) && !raise(SIGUSR1));
    assert(epoll_wait(q,events,4,0)==2);
    assert(events[0].data.u64==1 && events[1].data.u64==1);
    char byte; assert(read(p[0],&byte,1)==1);
    struct signalfd_siginfo info[2];
    assert(read(fd,info,sizeof(info))==sizeof(info));
    assert(!epoll_wait(q,events,4,0));
    const unsigned modes[]={EPOLLIN,EPOLLIN|EPOLLET,EPOLLIN|EPOLLONESHOT};
    for (unsigned mode=0;mode<3;++mode) for (int signal=0;signal<2;++signal) {
        add.events=modes[mode]; add.data.u64=UINT64_MAX;
        assert(!epoll_ctl(q,EPOLL_CTL_MOD,fd,&add));
        assert(!raise(signal ? SIGSYS : SIGUSR1));
        assert(epoll_wait(q,events,4,0)==1 && events[0].events==EPOLLIN && events[0].data.u64==UINT64_MAX);
        assert(epoll_wait(q,events,4,0)==(mode ? 0 : 1));
        assert(read(fd,info,128)==128 && info[0].ssi_signo==(unsigned)(signal ? SIGSYS : SIGUSR1));
        if (mode==2) assert(!epoll_ctl(q,EPOLL_CTL_MOD,fd,&add));
        struct epoll_signal_sender sender={pthread_self(),signal ? SIGSYS : SIGUSR1};
        pthread_t peer; assert(!pthread_create(&peer,NULL,epoll_signal_send,&sender));
        assert(epoll_wait(q,events,4,5000)==1 && events[0].data.u64==UINT64_MAX);
        assert(!pthread_join(peer,NULL));
        assert(read(fd,info,128)==128 && info[0].ssi_signo==(unsigned)sender.signal);
        assert(!epoll_wait(q,events,4,0));
    }
    add.events=EPOLLIN|EPOLLET; add.data.u64=77;
    int copy=dup(fd); assert(copy>=0);
    assert(!epoll_ctl(q,EPOLL_CTL_MOD,fd,&add));
    assert(!epoll_ctl(q,EPOLL_CTL_ADD,copy,&add));
    assert(!raise(SIGSYS));
    assert(epoll_wait(q,events,4,0)==2);
    assert(!epoll_wait(q,events,4,0));
    assert(read(copy,info,128)==128 && info[0].ssi_signo==SIGSYS);
    assert(!epoll_ctl(q,EPOLL_CTL_DEL,copy,NULL));
    assert(epoll_ctl(q,EPOLL_CTL_DEL,copy,NULL)==-1 && errno==ENOENT);
    assert(!close(fd) && !raise(SIGSYS));
    assert(epoll_wait(q,events,4,0)==1 && events[0].data.u64==77);
    assert(read(copy,info,128)==128 && !close(copy));
    assert(!epoll_wait(q,events,4,0));
    assert(!close(q) && !close(p[0]) && !close(p[1]));
    alarm(0);
    puts("PASS signalfd epoll: native and logical signals, mixed FD/data collisions, LT/ET/ONESHOT, late targeted wake, dup and DEL");
    return 0;
}
struct signal_epoll_domain { int fd,queue,shared; };
static int signal_epoll_fault(int filtered) {
    alarm(30);
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    int fd=signalfd(-1,&set,SFD_NONBLOCK|SFD_CLOEXEC),q=epoll_create1(EPOLL_CLOEXEC);
    assert(fd>=0 && q>=0);
    void *bad=mmap(NULL,4096,PROT_READ,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); assert(bad!=MAP_FAILED);
    struct epoll_event add={.events=EPOLLIN,.data.u64=19},event;
    assert(!epoll_ctl(q,EPOLL_CTL_ADD,fd,&add));
    if (filtered) {
        struct sock_filter rules[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_ppoll,6,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait2,5,0),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_epoll_pwait,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)q,1,0),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)};
        struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(syscall(SYS_ppoll,NULL,0,NULL,NULL,8)==-1 && errno==EACCES);
        assert(syscall(SYS_epoll_pwait,-1,&event,1,0,NULL,8)==-1 && errno==EACCES);
    }
    const unsigned modes[]={EPOLLIN,EPOLLIN|EPOLLET,EPOLLIN|EPOLLONESHOT};
    for (unsigned mode=0;mode<3;++mode) for (int logical=0;logical<2;++logical) {
        add.events=modes[mode]; assert(!epoll_ctl(q,EPOLL_CTL_MOD,fd,&add));
        int signal=logical ? SIGSYS : SIGUSR1;
        assert(!raise(signal));
        assert(syscall(SYS_epoll_pwait,q,bad,1,0,NULL,8)==-1 && errno==EFAULT);
        int count=epoll_wait(q,&event,1,0);
        fprintf(stderr,"epoll fault mode=%u signal=%d retry=%d\n",mode,signal,count);
        assert(count==1 && event.data.u64==19);
        struct signalfd_siginfo info;
        assert(read(fd,&info,sizeof(info))==sizeof(info) && info.ssi_signo==(unsigned)signal);
    }
    close(fd); close(q); munmap(bad,4096); alarm(0);
    puts("PASS signalfd epoll faults: native/logical LT, ET and ONESHOT retain undelivered events");
    return 0;
}
static int signal_epoll_domain_child(void *opaque) {
    struct signal_epoll_domain *d=opaque;
    struct signalfd_siginfo info;
    struct epoll_event event;
    for (int i=0;i<2;++i) {
        int signal=i ? SIGSYS : SIGUSR1;
        assert(!syscall(SYS_tgkill,syscall(SYS_getpid),syscall(SYS_gettid),signal));
        assert(epoll_wait(d->queue,&event,1,0)==d->shared);
        assert(read(d->fd,&info,sizeof(info))==sizeof(info) && info.ssi_signo==(unsigned)signal);
    }
    return 0;
}
static int signal_epoll_domains(int after_exec) {
    alarm(30);
    if (after_exec) {
        struct signal_epoll_domain d={.fd=atoi(getenv("MD_SIGNAL_FD")),
            .queue=atoi(getenv("MD_SIGNAL_EPOLL")),.shared=1};
        assert(!signal_epoll_domain_child(&d));
        close(d.fd); close(d.queue); alarm(0);
        puts("PASS signalfd epoll domains: fork, CLONE_SIGHAND without CLONE_THREAD and exec");
        return 0;
    }
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS); sigaddset(&set,SIGUSR1);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    struct signal_epoll_domain d={.fd=signalfd(-1,&set,SFD_NONBLOCK),.queue=epoll_create1(0)};
    assert(d.fd>=0 && d.queue>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=97};
    assert(!epoll_ctl(d.queue,EPOLL_CTL_ADD,d.fd,&event));
    pid_t child=fork(); assert(child>=0);
    if (!child) _exit(signal_epoll_domain_child(&d));
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    d.shared=1;
    void *memory=malloc(65536); assert(memory);
    child=clone(signal_epoll_domain_child,(char *)memory+65536,CLONE_VM|CLONE_SIGHAND|SIGCHLD,&d);
    assert(child>=0 && waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    free(memory);
    char number[32]; snprintf(number,sizeof(number),"%d",d.fd); assert(!setenv("MD_SIGNAL_FD",number,1));
    snprintf(number,sizeof(number),"%d",d.queue); assert(!setenv("MD_SIGNAL_EPOLL",number,1));
    execl("/proc/self/exe","guest_policy","signal-epoll-exec",(char *)NULL);
    assert(0); return 1;
}
struct waiting_signal { int descriptor,ready,filtered,synchronized; };
static void *signal_waiter(void *context) {
    struct waiting_signal *w=context;
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS);
    int fd=w->descriptor ? signalfd(-1,&set,SFD_CLOEXEC) : -1;
    if (w->descriptor) assert(fd>=0);
    int q=-1;
    if (w->descriptor==2) {
        q=epoll_create1(EPOLL_CLOEXEC); assert(q>=0);
        struct epoll_event event={.events=EPOLLIN|EPOLLONESHOT,.data.u64=91};
        assert(!epoll_ctl(q,EPOLL_CTL_ADD,fd,&event));
    }
    if (w->filtered) {
        struct sock_filter rules[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mmap,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        assert(mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0)==MAP_FAILED && errno==EACCES);
    }
    assert(write(w->ready,"r",1)==1);
    if (w->descriptor) {
        if (q>=0) {
            struct epoll_event event;
            assert(syscall(SYS_epoll_pwait2,q,&event,1,NULL,&set,8)==1 && event.data.u64==91);
        }
        struct signalfd_siginfo info;
        assert(read(fd,&info,sizeof(info))==sizeof(info) && info.ssi_signo==SIGSYS);
        /* Exercise the kernel boundary after denying mmap, not Bionic's lazy
         * fdsan table allocation for the pressure test's high descriptors. */
        assert(!syscall(SYS_close,fd));
        if (q>=0) assert(!syscall(SYS_close,q));
    } else {
        siginfo_t info;
        assert(sigwaitinfo(&set,&info)==SIGSYS && info.si_signo==SIGSYS);
    }
    if (w->synchronized) assert(syscall(SYS_getppid)==-1 && errno==EACCES);
    return NULL;
}
static int signal_wait_pressure(int filtered,int epoll,int synchronized) {
    alarm(30);
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGSYS);
    assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    enum { COUNT=48 };
    pthread_t workers[COUNT]; struct waiting_signal waiters[COUNT];
    int ready[2]; assert(!pipe(ready));
    for (unsigned i=0;i<COUNT;++i) {
        waiters[i]=(struct waiting_signal){.descriptor=epoll ? 2 : i%2,.ready=ready[1],
            .filtered=filtered,.synchronized=synchronized};
        assert(!pthread_create(workers+i,NULL,signal_waiter,waiters+i));
        char byte; assert(read(ready[0],&byte,1)==1);
    }
    if (synchronized) {
        struct sock_filter rules[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program));
        assert(syscall(SYS_getppid)==-1 && errno==EACCES);
    }
    struct stat st; assert(!stat("/",&st));
    for (unsigned i=0;i<COUNT;++i) assert(!pthread_kill(workers[i],SIGSYS));
    for (unsigned i=0;i<COUNT;++i) assert(!pthread_join(workers[i],NULL));
    close(ready[0]); close(ready[1]); alarm(0);
    puts("PASS concurrent signal waits: 48 readers, unrelated file progress and targeted wakeups");
    return 0;
}
static sigjmp_buf mask_jump;
static volatile sig_atomic_t query_signals;
static void mask_query_handler(int signal) {
    assert(signal==SIGALRM);
    ++query_signals;
}
static int signal_mask_queries(void) {
    struct sigaction action={.sa_handler=mask_query_handler,.sa_flags=SA_RESTART};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGALRM,&action,NULL));
    struct itimerval timer={.it_interval={0,100},.it_value={0,100}};
    for (unsigned blocked=0;blocked<2;++blocked) {
        uint64_t expected=UINT64_C(1)<<(SIGUSR1-1);
        if (blocked) expected|=UINT64_C(1)<<(SIGSYS-1);
        assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,&expected,NULL,sizeof(expected)));
        assert(!setitimer(ITIMER_REAL,&timer,NULL));
        for (unsigned i=0;i<4096;++i) {
            uint64_t actual=0;
            assert(!syscall(SYS_rt_sigprocmask,SIG_SETMASK,NULL,&actual,sizeof(actual)));
            if (actual!=expected)
                fprintf(stderr,"signal query blocked=%u iteration=%u expected=%llx actual=%llx signals=%d\n",
                    blocked,i,(unsigned long long)expected,(unsigned long long)actual,query_signals);
            assert(actual==expected);
        }
        struct itimerval stopped={0}; assert(!setitimer(ITIMER_REAL,&stopped,NULL));
        assert(query_signals>0);
        query_signals=0;
    }
    puts("PASS interrupted signal-mask queries preserve application masks");
    return 0;
}
static int mask_jumping,mask_signal;
static volatile sig_atomic_t edge_seen;
static void mask_edge_handler(int signal,siginfo_t *info,void *context) {
    (void)info;
    assert(signal==mask_signal);
    ucontext_t *uc=context;
    assert(sigismember(&uc->uc_sigmask,SIGSYS) && sigismember(&uc->uc_sigmask,SIGUSR1));
    assert(!sigismember(&uc->uc_sigmask,SIGUSR2));
    sigset_t current; assert(!sigprocmask(SIG_SETMASK,NULL,&current));
    assert(sigismember(&current,SIGUSR2));
    assert(!!sigismember(&current,SIGSYS)==(signal==SIGSYS));
    assert(!!sigismember(&current,SIGUSR1)==(signal==SIGUSR1));
    ++edge_seen;
    if (mask_jumping) siglongjmp(mask_jump,1);
}
static int signal_mask_edges(void) {
    alarm(30);
    struct sigaction action={.sa_sigaction=mask_edge_handler,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGSYS,&action,NULL) && !sigaction(SIGUSR1,&action,NULL));
    sigset_t original,temporary,current;
    sigemptyset(&original); sigaddset(&original,SIGSYS); sigaddset(&original,SIGUSR1);
    sigemptyset(&temporary); sigaddset(&temporary,SIGUSR2);
    int epoll=epoll_create1(EPOLL_CLOEXEC); assert(epoll>=0);
    for (int mode=0;mode<5;++mode) for (int kind=0;kind<4;++kind) {
        assert(!sigprocmask(SIG_SETMASK,&original,NULL));
        mask_signal=kind&1 ? SIGUSR1 : SIGSYS; mask_jumping=kind>=2; edge_seen=0;
        pid_t parent=getpid(),child=fork(); assert(child>=0);
        if (!child) { usleep(20000); assert(!kill(parent,mask_signal)); _exit(0); }
        if (!sigsetjmp(mask_jump,0)) {
            assert(masked_wait(mode,epoll,&temporary,0)==-1 && errno==EINTR);
            assert(!mask_jumping);
        }
        assert(edge_seen==1);
        assert(!sigprocmask(SIG_SETMASK,NULL,&current));
        if (!!sigismember(&current,SIGUSR2)!=mask_jumping
                || !!sigismember(&current,SIGSYS)!=(!mask_jumping || mask_signal==SIGSYS)
                || !!sigismember(&current,SIGUSR1)!=(!mask_jumping || mask_signal==SIGUSR1))
            fprintf(stderr,"temporary mask mode=%d kind=%d jump=%d signal=%d observed SYS=%d USR1=%d USR2=%d\n",
                mode,kind,mask_jumping,mask_signal,sigismember(&current,SIGSYS),
                sigismember(&current,SIGUSR1),sigismember(&current,SIGUSR2));
        assert(!!sigismember(&current,SIGUSR2)==mask_jumping);
        assert(!!sigismember(&current,SIGSYS)==(!mask_jumping || mask_signal==SIGSYS));
        assert(!!sigismember(&current,SIGUSR1)==(!mask_jumping || mask_signal==SIGUSR1));
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    close(epoll); alarm(0);
    puts("PASS temporary masks: native and logical signals, sigreturn and longjmp");
    return 0;
}
static void descriptor_interrupt_handler(int signal) {
    assert(signal==SIGSYS); ++seen;
    char byte=1; assert(write(listener_signal_fd,&byte,1)==1);
}
static int signal_descriptor_interrupt(int restart) {
    alarm(30);
    struct sigaction action={.sa_handler=descriptor_interrupt_handler,.sa_flags=restart ? SA_RESTART : 0};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGSYS,&action,NULL));
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGUSR1); assert(!sigprocmask(SIG_BLOCK,&set,NULL));
    int fd=signalfd(-1,&set,SFD_CLOEXEC); assert(fd>=0);
    int ack[2]; assert(!pipe(ack)); listener_signal_fd=ack[1];
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        close(ack[1]); usleep(20000); assert(!kill(parent,SIGSYS));
        if (restart) { char byte; assert(read(ack[0],&byte,1)==1); assert(!kill(parent,SIGUSR1)); }
        _exit(0);
    }
    struct signalfd_siginfo info;
    long result=read(fd,&info,sizeof(info));
    fprintf(stderr,"signalfd restart=%d result=%ld errno=%d signal=%u seen=%d\n",restart,result,errno,info.ssi_signo,seen);
    if (restart) assert(result==128 && info.ssi_signo==SIGUSR1 && seen==1);
    else assert(result==-1 && errno==EINTR && seen==1);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    close(fd); close(ack[0]); close(ack[1]); alarm(0);
    puts("PASS signalfd interruption and application SA_RESTART");
    return 0;
}
static void create_mode(const char *path,unsigned mode) {
    assert(!unlink(path) || errno==ENOENT);
    int fd=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0666);
    if (fd<0) perror("protected creation");
    assert(fd>=0);
    struct stat st;
    assert(!fstat(fd,&st) && (st.st_mode&0777)==mode);
    assert(!close(fd) && !unlink(path));
}
static int protected_creation(void) {
    assert(!prctl(PR_SET_DUMPABLE,0));
    umask(077);
    create_mode("/tmp/protected-create",0600);
    umask(022);
    create_mode("/tmp/protected-create",0644);
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    create_mode("/tmp/protected-create",0644);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        umask(027); create_mode("/tmp/protected-child",0640);
        assert(!prctl(PR_GET_DUMPABLE)); _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    create_mode("/tmp/protected-create",0644);
    const char *fifo="/tmp/protected-fifo";
    assert(!unlink(fifo) || errno==ENOENT);
    assert(!mkfifo(fifo,0666));
    struct stat st; assert(!stat(fifo,&st) && S_ISFIFO(st.st_mode) && (st.st_mode&0777)==0644);
    assert(!unlink(fifo) && !prctl(PR_GET_DUMPABLE));
    puts("PASS protected creation: live umask, resource publication, fork and FIFO");
    return 0;
}
struct sync_test { atomic_int running,ready; atomic_uint calls; int pipe[2],forking; };
static void *sync_worker(void *arg) {
    struct sync_test *test=arg;
    atomic_store(&test->ready,1);
    while (atomic_load(&test->running)) {
        long result=cold_mount("/tmp/does-not-exist-sync");
        assert(result==-ENOENT || (result<=-100 && result>=-115));
        unsigned count=atomic_fetch_add(&test->calls,1);
        if (test->forking && !(count%8)) {
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                long inherited=cold_mount("/tmp/does-not-exist-sync");
                _exit(inherited==result ? 0 : inherited<=-100 && inherited>=-115 ? 0 : 1);
            }
            int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
        }
    }
    return NULL;
}
static void *sync_blocked(void *arg) {
    struct sync_test *test=arg;
    char byte; assert(read(test->pipe[0],&byte,1)==1 && byte=='x');
    return NULL;
}
static int policy_sync(int forking) {
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    struct sync_test test={.running=1,.forking=forking};
    assert(!pipe(test.pipe));
    pthread_t active,blocked;
    assert(!pthread_create(&active,NULL,sync_worker,&test));
    assert(!pthread_create(&blocked,NULL,sync_blocked,&test));
    while (!atomic_load(&test.ready) || !atomic_load(&test.calls)) sched_yield();
    for (unsigned i=0;i<16;++i) {
        struct sock_filter rules[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_mount,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|(100+i)),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program));
        assert(cold_mount("/tmp/does-not-exist-sync")==-(long)(100+i));
    }
    atomic_store(&test.running,0);
    assert(write(test.pipe[1],"x",1)==1);
    assert(!pthread_join(active,NULL) && !pthread_join(blocked,NULL));
    close(test.pipe[0]); close(test.pipe[1]);
    printf("PASS cold TSYNC: calls=%u fork=%d native blocking peer\n",atomic_load(&test.calls),forking);
    return 0;
}
static long sync_notified_number(int mode) {
    const long extra[]={SYS_getuid,SYS_fcntl,SYS_openat,SYS_newfstatat,SYS_gettid};
    return mode>=4 ? extra[mode-4] : mode&1 ? SYS_getppid : SYS_mount;
}
#define SYNC_CALL_MARKER 0x4d445359
static long sync_notified_call(int mode) {
    struct stat st;
    long args[5]={0};
    long number=sync_notified_number(mode);
    switch (number) {
    case SYS_mount:
        args[0]=(long)"tmpfs"; args[1]=(long)"/tmp/does-not-exist-sync-notify"; args[2]=(long)"tmpfs";
        break;
    case SYS_fcntl: args[0]=STDOUT_FILENO; args[1]=F_GETFD; break;
    case SYS_openat: args[0]=AT_FDCWD; args[1]=(long)"/dev/null"; args[2]=O_RDONLY; break;
    case SYS_newfstatat: args[0]=AT_FDCWD; args[1]=(long)"/"; args[2]=(long)&st; break;
    }
    register long x0 __asm__("x0")=args[0];
    register long x1 __asm__("x1")=args[1];
    register long x2 __asm__("x2")=args[2];
    register long x3 __asm__("x3")=args[3];
    register long x4 __asm__("x4")=args[4];
    register long x5 __asm__("x5")=SYNC_CALL_MARKER;
    register long x8 __asm__("x8")=number;
    __asm__ volatile("svc #0" : "+r"(x0)
        : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    return x0;
}
static void *sync_notified_worker(void *arg) {
    int mode=*(int *)arg,continuing=mode>=2;
    long result=sync_notified_call(mode);
    fprintf(stderr,"TSYNC admitted mode=%d result=%ld\n",mode,result);
    assert(continuing ? sync_notified_number(mode)==SYS_mount ? result==-ENOENT : result>=0 : result==713);
    if (mode==6 && result>=0) assert(!close((int)result));
    result=sync_notified_call(mode);
    assert(result==-EACCES);
    return NULL;
}
static int policy_sync_notification(int mode) {
    int continuing=mode>=2;
    alarm(30);
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    /* libc may open files while creating the first pthread. Admit only the
     * marked fixture calls, so setup never waits for its own listener. */
    struct sock_filter rules[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,sync_notified_number(mode),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[5])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYNC_CALL_MARKER,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
    int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd>=0);
    pthread_t worker; assert(!pthread_create(&worker,NULL,sync_notified_worker,&mode));
    struct seccomp_notif request={0};
    assert(!ioctl(fd,SECCOMP_IOCTL_NOTIF_RECV,&request) && request.data.nr==sync_notified_number(mode)
        && request.data.args[5]==SYNC_CALL_MARKER);
    assert(!ioctl(fd,SECCOMP_IOCTL_NOTIF_ID_VALID,&request.id));
    rules[4].k=SECCOMP_RET_ERRNO|EACCES;
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,&program));
    int valid=ioctl(fd,SECCOMP_IOCTL_NOTIF_ID_VALID,&request.id);
    fprintf(stderr,"TSYNC pending notification: valid=%d errno=%d\n",valid,errno);
    assert(!valid);
    struct seccomp_notif_resp response={.id=request.id,.val=continuing ? 0 : 713,
        .flags=continuing ? SECCOMP_USER_NOTIF_FLAG_CONTINUE : 0};
    assert(!ioctl(fd,SECCOMP_IOCTL_NOTIF_SEND,&response));
    assert(!pthread_join(worker,NULL));
    close(fd); alarm(0);
    puts("PASS TSYNC retains admitted notification and applies new policy to the next call");
    return 0;
}
static void *dumpability_thread(void *unused) {
    (void)unused;
    assert(!prctl(PR_SET_DUMPABLE,0));
    return NULL;
}
static void dumpability_wait(pid_t pid) {
    int status;
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void dumpability_stat(pid_t pid,int permitted) {
    char path[64],text[4096]; snprintf(path,sizeof(path),"/proc/%d/stat",pid);
    int fd=open(path,O_RDONLY|O_CLOEXEC); assert(fd>=0);
    ssize_t size=read(fd,text,sizeof(text)-1); assert(size>0); text[size]=0; close(fd);
    char *end=strrchr(text,')'); assert(end && end[1]==' ');
    char *save=NULL; unsigned field=3;
    for (char *value=strtok_r(end+2," \n",&save);value;value=strtok_r(NULL," \n",&save),++field) {
        if (field==26 || field==27) assert(permitted ? strtoull(value,NULL,10)>1 : !strcmp(value,"1"));
        if (!permitted && ((field>=28 && field<=30) || field==35 || (field>=45 && field<=52)))
            assert(!strcmp(value,"0"));
    }
    assert(field>52);
}
static void dumpability_owner(const char *path,unsigned uid) {
    struct stat st={0};
    int result=stat(path,&st);
    if (result || st.st_uid!=uid || st.st_gid!=uid)
        fprintf(stderr,"dumpability metadata %s: result=%d errno=%d uid=%u gid=%u mode=%o expected=%u\n",
            path,result,errno,st.st_uid,st.st_gid,st.st_mode,uid);
    assert(!result && st.st_uid==uid && st.st_gid==uid);
}
static int dumpability(const char *self) {
    alarm(30);
    assert(prctl(PR_GET_DUMPABLE)==1);
    pthread_t thread;
    assert(!pthread_create(&thread,NULL,dumpability_thread,NULL) && !pthread_join(thread,NULL));
    assert(!prctl(PR_GET_DUMPABLE));
    errno=0; assert(prctl(PR_SET_DUMPABLE,2UL)==-1 && errno==EINVAL && !prctl(PR_GET_DUMPABLE));
    errno=0; assert(prctl(PR_SET_DUMPABLE,1UL<<32)==-1 && errno==EINVAL && !prctl(PR_GET_DUMPABLE));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_GET_DUMPABLE));
        assert(!prctl(PR_SET_DUMPABLE,1));
        _exit(0);
    }
    dumpability_wait(child); assert(!prctl(PR_GET_DUMPABLE));
    child=fork(); assert(child>=0);
    if (!child) { execl(self,self,"dumpability-exec",NULL); _exit(99); }
    dumpability_wait(child); assert(!prctl(PR_GET_DUMPABLE));
    child=fork(); assert(child>=0);
    if (!child) {
        filter_call(SYS_prctl,SECCOMP_RET_ERRNO|EUCLEAN);
        errno=0; assert(prctl(PR_SET_DUMPABLE,1)==-1 && errno==EUCLEAN);
        errno=0; assert(prctl(PR_GET_DUMPABLE)==-1 && errno==EUCLEAN);
        _exit(0);
    }
    dumpability_wait(child);
    assert(!prctl(PR_SET_DUMPABLE,1) && !setgid(1000) && !setuid(1000));
    assert(!prctl(PR_GET_DUMPABLE));
    long probe=73;
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        assert(!raise(SIGSTOP));
        _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    errno=0; assert(ptrace(PTRACE_PEEKDATA,child,&probe,NULL)==-1 && errno==EIO);
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL)); dumpability_wait(child);
    int request[2],reply[2]; assert(!pipe(request) && !pipe(reply));
    child=fork(); assert(child>=0);
    if (!child) {
        close(request[1]); close(reply[0]);
        unsigned char byte=73; uintptr_t address=(uintptr_t)&byte;
        assert(!prctl(PR_GET_DUMPABLE));
        assert(write(reply[1],&address,sizeof(address))==sizeof(address));
        char message; assert(read(request[0],&message,1)==1);
        assert(!prctl(PR_SET_DUMPABLE,1) && write(reply[1],"x",1)==1);
        assert(read(request[0],&message,1)==1);
        assert(!prctl(PR_SET_DUMPABLE,0) && write(reply[1],"x",1)==1);
        assert(read(request[0],&message,1)==1);
        assert(!prctl(PR_SET_DUMPABLE,1) && write(reply[1],"x",1)==1);
        assert(read(request[0],&message,1)==1);
        _exit(0);
    }
    close(request[0]); close(reply[1]);
    uintptr_t address; assert(read(reply[0],&address,sizeof(address))==sizeof(address));
    dumpability_stat(child,0);
    unsigned char byte=0; struct iovec local={&byte,1},remote={(void *)address,1};
    errno=0; assert(process_vm_readv(child,&local,1,&remote,1,0)==-1 && errno==EPERM);
    errno=0; assert(process_vm_writev(child,&local,1,&remote,1,0)==-1 && errno==EPERM);
    assert(!process_vm_readv(child,NULL,0,&remote,1,0));
    assert(!process_vm_readv(child,&local,1,NULL,0,0));
    errno=0; assert(process_vm_readv(child,&local,1,&remote,1,1)==-1 && errno==EINVAL);
    errno=0; assert(process_vm_readv(child,(void *)1,1,&remote,1,0)==-1 && errno==EFAULT);
    errno=0; assert(ptrace(PTRACE_SEIZE,child,NULL,NULL)==-1 && errno==EPERM);
    char path[64]; snprintf(path,sizeof(path),"/proc/%d/auxv",child);
    errno=0; assert(open(path,O_RDONLY|O_CLOEXEC)==-1 && errno==EACCES);
    snprintf(path,sizeof(path),"/proc/%d/mem",child);
    errno=0; assert(open(path,O_RDONLY|O_CLOEXEC)==-1 && errno==EACCES);
    const char *protected[]={"maps","smaps","smaps_rollup","environ","pagemap","io","auxv","mem"};
    for (unsigned i=0;i<sizeof(protected)/sizeof(*protected);++i) {
        snprintf(path,sizeof(path),"/proc/%d/%s",child,protected[i]);
        errno=0; assert(open(path,O_RDONLY|O_CLOEXEC)==-1 && errno==EACCES);
        dumpability_owner(path,0);
        if (!strcmp(protected[i],"auxv") || !strcmp(protected[i],"mem")) {
            errno=0; assert(faccessat(AT_FDCWD,path,R_OK,0)==-1 && errno==EACCES);
        }
        int entry=open(path,O_PATH|O_CLOEXEC);
        if (entry<0) fprintf(stderr,"dumpability O_PATH %s: errno=%d\n",path,errno);
        assert(entry>=0);
        struct stat info; assert(!fstat(entry,&info) && info.st_uid==0 && info.st_gid==0);
        errno=0; assert(read(entry,&byte,1)==-1 && errno==EBADF);
        char retained[64]; snprintf(retained,sizeof(retained),"/proc/self/fd/%d",entry);
        errno=0; int reopened=open(retained,O_RDONLY|O_CLOEXEC);
        if (reopened!=-1 || errno!=EACCES)
            fprintf(stderr,"dumpability reopen %s: fd=%d errno=%d\n",path,reopened,errno);
        assert(reopened==-1 && errno==EACCES);
        close(entry);
    }
    snprintf(path,sizeof(path),"/proc/%d/wchan",child);
    int channel=open(path,O_RDONLY|O_CLOEXEC); assert(channel>=0);
    char text[256]; assert(read(channel,text,sizeof(text))==1 && text[0]=='0'); close(channel);
    snprintf(path,sizeof(path),"/proc/%d/status",child);
    dumpability_owner(path,0);
    snprintf(path,sizeof(path),"/proc/%d/ns",child);
    dumpability_owner(path,0);
    snprintf(path,sizeof(path),"/proc/%d/ns/mnt",child);
    struct stat link; assert(!lstat(path,&link) && S_ISLNK(link.st_mode) && !link.st_uid && !link.st_gid);
    snprintf(path,sizeof(path),"/proc/%d",child);
    dumpability_owner(path,1000);
    snprintf(path,sizeof(path),"/proc/%d/status",child);
    assert(write(request[1],"x",1)==1 && read(reply[0],&byte,1)==1);
    assert(process_vm_readv(child,&local,1,&remote,1,0)==1 && byte==73);
    dumpability_stat(child,1);
    dumpability_owner(path,1000);
    const char *pinned[]={"auxv","mem"};
    for (unsigned i=0;i<sizeof(pinned)/sizeof(*pinned);++i) {
        snprintf(path,sizeof(path),"/proc/%d/%s",child,pinned[i]);
        int entry=open(path,O_PATH|O_CLOEXEC); assert(entry>=0);
        char retained[64]; snprintf(retained,sizeof(retained),"/proc/self/fd/%d",entry);
        int reopened=open(retained,O_RDONLY|O_CLOEXEC); assert(reopened>=0);
        struct stat info; assert(!fstat(reopened,&info) && info.st_uid==1000 && info.st_gid==1000);
        if (!strcmp(pinned[i],"mem"))
            assert(pread(reopened,&byte,1,(off_t)address)==1 && byte==73);
        close(reopened); close(entry);
    }
    snprintf(path,sizeof(path),"/proc/%d/io",child);
    int io=open(path,O_RDONLY|O_CLOEXEC); assert(io>=0);
    snprintf(path,sizeof(path),"/proc/%d/maps",child);
    int maps=open(path,O_RDONLY|O_CLOEXEC); assert(maps>=0);
    assert(write(request[1],"x",1)==1 && read(reply[0],&byte,1)==1);
    errno=0; assert(read(io,text,sizeof(text))==-1 && errno==EACCES);
    assert(read(maps,text,sizeof(text))>0); close(maps);
    assert(write(request[1],"x",1)==1 && read(reply[0],&byte,1)==1);
    assert(read(io,text,sizeof(text))>0); close(io);
    assert(write(request[1],"x",1)==1);
    close(request[1]); close(reply[0]); dumpability_wait(child);
    alarm(0);
    puts("PASS dumpability: threads, fork, exec, credentials, filters and foreign inspection");
    return 0;
}
int main(int argc, char **argv) {
    setbuf(stdout, NULL);
    assert(argc == 2);
    if (!strcmp(argv[1],"dumpability")) return dumpability(argv[0]);
    if (!strcmp(argv[1],"dumpability-exec")) { assert(prctl(PR_GET_DUMPABLE)==1); return 0; }
    if (!strcmp(argv[1], "host")) return host();
    if (!strcmp(argv[1], "optional-native-calls")) return capability_calls(0);
    if (!strcmp(argv[1], "unavailable-calls")) return capability_calls(1);
    if (!strcmp(argv[1], "accept-calls")) return accept_calls();
    if (!strcmp(argv[1], "socket-service-filter")) return socket_service_filter();
    if (!strcmp(argv[1], "vfork-shared-socket")) return vfork_shared_socket();
    if (!strcmp(argv[1], "namespace-service-filter")) return namespace_service_filter();
    if (!strcmp(argv[1], "relative-path-filter")) return relative_path_filter();
    if (!strcmp(argv[1], "socket-signals")) return socket_signals();
    if (!strcmp(argv[1], "socket-tsync")) return socket_tsync();
    if (!strcmp(argv[1], "socket-child-signals")) return socket_child_signals();
    if (!strcmp(argv[1], "proc")) return protected_proc();
    if (!strcmp(argv[1], "proc-limits")) return protected_proc_limits();
    if (!strcmp(argv[1], "protected-creation")) return protected_creation();
    if (!strcmp(argv[1], "modern-mount")) return modern_mount(0,0);
    if (!strcmp(argv[1], "modern-mount-filtered")) return modern_mount(1,0);
    if (!strcmp(argv[1], "modern-mount-denied")) return modern_mount(1,1);
    if (!strcmp(argv[1], "signal-entry")) return signal_entry(0);
    if (!strcmp(argv[1], "signal-entry-filtered")) return signal_entry(1);
    if (!strcmp(argv[1], "signal-descriptors")) return signal_descriptors(0);
    if (!strcmp(argv[1], "signal-descriptors-filtered")) return signal_descriptors(1);
    if (!strcmp(argv[1], "signal-epoll")) return signal_epoll(0);
    if (!strcmp(argv[1], "cold-identity")) return cold_identity(0);
    if (!strcmp(argv[1], "oom-sharing")) return oom_sharing();
    if (!strcmp(argv[1], "pid-init-signals")) return pid_init_signals();
    if (!strcmp(argv[1], "cold-identity-denied")) return cold_identity(1);
    if (!strcmp(argv[1], "signal-epoll-filtered")) return signal_epoll(1);
    if (!strcmp(argv[1], "signal-epoll-domains")) return signal_epoll_domains(0);
    if (!strcmp(argv[1], "signal-epoll-fault")) return signal_epoll_fault(0);
    if (!strcmp(argv[1], "signal-epoll-fault-filtered")) return signal_epoll_fault(1);
    if (!strcmp(argv[1], "signal-epoll-exec")) return signal_epoll_domains(1);
    if (!strcmp(argv[1], "signal-wait-pressure")) return signal_wait_pressure(0,0,0);
    if (!strcmp(argv[1], "signal-wait-pressure-filtered")) return signal_wait_pressure(1,0,0);
    if (!strcmp(argv[1], "signal-wait-tsync")) return signal_wait_pressure(0,0,1);
    if (!strcmp(argv[1], "signal-epoll-pressure")) return signal_wait_pressure(0,1,0);
    if (!strcmp(argv[1], "signal-epoll-pressure-filtered")) return signal_wait_pressure(1,1,0);
    if (!strcmp(argv[1], "signal-epoll-tsync")) return signal_wait_pressure(0,1,1);
    if (!strcmp(argv[1], "signal-mask-edges")) return signal_mask_edges();
    if (!strcmp(argv[1], "signal-mask-queries")) return signal_mask_queries();
    if (!strcmp(argv[1], "signal-descriptor-interrupt")) return signal_descriptor_interrupt(0);
    if (!strcmp(argv[1], "signal-descriptor-restart")) return signal_descriptor_interrupt(1);
    if (!strcmp(argv[1], "tsync-cold")) return policy_sync(0);
    if (!strcmp(argv[1], "tsync-cold-fork")) return policy_sync(1);
    if (!strcmp(argv[1], "tsync-notification")) return policy_sync_notification(0);
    if (!strcmp(argv[1], "tsync-notification-native")) return policy_sync_notification(1);
    if (!strcmp(argv[1], "tsync-continue")) return policy_sync_notification(2);
    if (!strcmp(argv[1], "tsync-continue-native")) return policy_sync_notification(3);
    if (!strcmp(argv[1], "tsync-continue-identity")) return policy_sync_notification(4);
    if (!strcmp(argv[1], "tsync-continue-fcntl")) return policy_sync_notification(5);
    if (!strcmp(argv[1], "tsync-continue-open")) return policy_sync_notification(6);
    if (!strcmp(argv[1], "tsync-continue-stat")) return policy_sync_notification(7);
    if (!strcmp(argv[1], "tsync-continue-pid")) return policy_sync_notification(8);
    if (!strcmp(argv[1], "listener")) return listener_transfer(0);
    if (!strcmp(argv[1], "listener-continue")) return listener_transfer(1);
    if (!strcmp(argv[1], "listener-error")) return listener_transfer(2);
    if (!strcmp(argv[1], "listener-close")) return listener_transfer(3);
    if (!strcmp(argv[1], "listener-stat")) return listener_transfer(4);
    if (!strcmp(argv[1], "listener-reject")) return listener_rejection();
    if (!strcmp(argv[1], "listener-cold")) return listener_transfer(5);
    if (!strcmp(argv[1], "listener-cold-continue")) return listener_transfer(6);
    if (!strcmp(argv[1], "listener-interrupt")) return listener_transfer(7);
    if (!strcmp(argv[1], "listener-restart")) return listener_transfer(8);
    if (!strcmp(argv[1], "listener-cold-interrupt")) return listener_transfer(9);
    if (!strcmp(argv[1], "listener-cold-restart")) return listener_transfer(10);
    if (!strcmp(argv[1], "listener-addfd-send")) return listener_transfer(11);
    if (!strcmp(argv[1], "listener-addfd")) return listener_transfer(12);
    if (!strcmp(argv[1], "listener-addfd-fixed")) return listener_transfer(13);
    if (!strcmp(argv[1], "listener-addfd-fixed-send")) return listener_transfer(14);
    if (!strcmp(argv[1], "listener-addfd-protected-send")) return listener_transfer(15);
    if (!strcmp(argv[1], "listener-addfd-protected-fixed")) return listener_transfer(16);
    if (!strcmp(argv[1], "listener-addfd-protected")) return listener_transfer(17);
    if (!strcmp(argv[1], "listener-exec")) return listener_transfer(18);
    if (!strcmp(argv[1], "listener-target-death")) return listener_transfer(19);
    if (!strcmp(argv[1], "listener-exec-child")) {
        assert(syscall(SYS_getppid)==713);
        puts("PASS listener original policy retained after exec"); return 0;
    }
    if (!strcmp(argv[1], "signal-views")) return signal_views();
    if (!strcmp(argv[1], "signal-wait")) return signal_wait(0);
    if (!strcmp(argv[1], "signal-wait-threads")) return signal_wait_threads();
    if (!strcmp(argv[1], "signal-wait-masks")) return signal_wait_masks(0);
    if (!strcmp(argv[1], "signal-wait-masks-filtered")) return signal_wait_masks(1);
    if (!strcmp(argv[1], "signal-wait-filtered")) return signal_wait(1);
    if (!strncmp(argv[1], "trace-cookie-",13)) return trace_cookie((unsigned)strtoul(argv[1]+13,NULL,10));
    pid_t child = fork(); assert(child >= 0);
    if (!child) _exit(policy(argv[1]));
    int status; assert(waitpid(child, &status, 0) == child);
    int passed = (!strcmp(argv[1], "kill") || !strcmp(argv[1],"blocked-trap") || !strcmp(argv[1],"ignored-trap"))
        ? WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS
        : WIFEXITED(status) && !WEXITSTATUS(status);
    printf("%s policy=%s status=%#x\n", passed ? "PASS" : "FAIL", argv[1], status);
    return passed ? 0 : 1;
}
