#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fuse.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int device,events[2],stopping[2],release_events[2];
static atomic_int delay_flush,flush_error;
static atomic_uint flushes,releases;
static _Atomic(const char *) stage="startup";
static void begin_stage(const char *name) {
    stage=name;
    fprintf(stderr,"FUSE close stage=%s\n",name);
}
static void response(uint64_t unique,int error,const void *data,size_t size) {
    unsigned char packet[512];
    struct fuse_out_header h={.len=sizeof(h)+size,.unique=unique,.error=error};
    assert(h.len<=sizeof(packet)); memcpy(packet,&h,sizeof(h));
    if (size) memcpy(packet+sizeof(h),data,size);
    ssize_t result=write(device,packet,h.len);
    if (result!=h.len) fprintf(stderr,"reply unique=%llu error=%d size=%u result=%zd errno=%d\n",
        (unsigned long long)unique,error,h.len,result,errno);
    assert(result==h.len);
}
static struct fuse_attr attributes(uint64_t inode) {
    return (struct fuse_attr){.ino=inode,.mode=inode==1 ? S_IFDIR|0755 : S_IFREG|0644,
        .nlink=1,.blksize=4096};
}
static void *serve(void *unused) {
    (void)unused;
    for (;;) {
        struct pollfd pollers[]={{device,POLLIN,0},{stopping[0],POLLIN,0}};
        /* EVENT_WAIT: a daemon request or fixture shutdown; timeout fails. */
        int ready=poll(pollers,2,30000);
        /* Child/debugger signals do not terminate the daemon. The fixture's
         * process-wide alarm still bounds repeated interruptions. */
        if (ready<0 && errno==EINTR) continue;
        if (ready<=0) fprintf(stderr,"daemon wait stage=%s result=%d errno=%d flushes=%u releases=%u\n",
            stage,ready,errno,atomic_load(&flushes),atomic_load(&releases));
        assert(ready>0);
        if (pollers[1].revents) return NULL;
        unsigned char input[65536];
        ssize_t size=read(device,input,sizeof(input)); assert(size>=(ssize_t)sizeof(struct fuse_in_header));
        struct fuse_in_header h; memcpy(&h,input,sizeof(h)); assert(h.len==size);
        switch (h.opcode) {
        case FUSE_INIT: {
            struct fuse_init_out out={.major=7,.minor=38,.max_write=4096};
            response(h.unique,0,&out,sizeof(out)); break;
        }
        case FUSE_LOOKUP: {
            assert(!strcmp((char *)input+sizeof(h),"file"));
            struct fuse_entry_out out={.nodeid=2,.generation=1,.attr=attributes(2)};
            response(h.unique,0,&out,sizeof(out)); break;
        }
        case FUSE_GETATTR: {
            struct fuse_attr_out out={.attr=attributes(h.nodeid)};
            response(h.unique,0,&out,sizeof(out)); break;
        }
        case FUSE_OPEN: {
            struct fuse_open_out out={.fh=42}; response(h.unique,0,&out,sizeof(out)); break;
        }
        case FUSE_FLUSH:
            atomic_fetch_add(&flushes,1);
            if (atomic_load(&delay_flush)) assert(write(events[1],&h.unique,sizeof(h.unique))==sizeof(h.unique));
            else response(h.unique,atomic_load(&flush_error),NULL,0);
            break;
        case FUSE_RELEASE:
            atomic_fetch_add(&releases,1); response(h.unique,0,NULL,0);
            assert(write(release_events[1],"r",1)==1); break;
        case FUSE_FORGET: case FUSE_BATCH_FORGET: break;
        default: response(h.unique,-ENOSYS,NULL,0); break;
        }
    }
}
static uint64_t pending_flush(void) {
    struct pollfd p={events[0],POLLIN,0};
    /* EVENT_WAIT: the daemon has actually received FLUSH, not a settling delay. */
    assert(poll(&p,1,5000)==1);
    uint64_t unique; assert(read(p.fd,&unique,sizeof(unique))==sizeof(unique)); return unique;
}
struct closing { int fd,result,error,replacement,range; atomic_int done; };
static void *close_thread(void *arg) {
    struct closing *c=arg;
    /* Test the kernel result: some libc close wrappers suppress EINTR. */
    c->result=c->range ? syscall(SYS_close_range,c->fd,c->fd,0)
        : c->replacement ? dup2(c->replacement,c->fd) : syscall(SYS_close,c->fd);
    c->error=errno; atomic_store(&c->done,1); return NULL;
}
static volatile sig_atomic_t signalled;
static void signal_handler(int number) { signalled=number; }
static void join(pthread_t t) {
    struct timespec end; assert(!clock_gettime(CLOCK_REALTIME,&end)); end.tv_sec+=10;
    assert(!pthread_timedjoin_np(t,NULL,&end));
}
static void delayed_close(const char *path,int interrupt) {
    struct closing c={.fd=open(path,O_RDONLY)}; assert(c.fd>=0);
    atomic_store(&delay_flush,1);
    pthread_t thread; assert(!pthread_create(&thread,NULL,close_thread,&c));
    uint64_t unique=pending_flush(); assert(!atomic_load(&c.done));
    errno=0; assert(fcntl(c.fd,F_GETFD)==-1 && errno==EBADF);
    int replacement=open("/dev/null",O_RDONLY); assert(replacement>=0);
    if (replacement!=c.fd) { assert(dup2(replacement,c.fd)==c.fd); close(replacement); }
    assert(fcntl(c.fd,F_GETFD)>=0); /* The shared FD table is no longer blocked. */
    if (interrupt) {
        assert(!pthread_kill(thread,SIGUSR1)); join(thread);
        assert(signalled==SIGUSR1 && c.result==-1 && c.error==EINTR);
        response(unique,0,NULL,0);
    } else {
        response(unique,-EIO,NULL,0); join(thread);
        assert(c.result==-1 && c.error==EIO);
    }
    assert(fcntl(c.fd,F_GETFD)>=0); assert(!close(c.fd));
    atomic_store(&delay_flush,0);
}
struct exec_args { const char *path; int marker; };
static void *exec_thread(void *arg) {
    struct exec_args *e=arg;
    char marker[24]; snprintf(marker,sizeof(marker),"%d",e->marker);
    execl(e->path,e->path,"--exec-child",marker,NULL); _exit(127);
}
static void traced_exit(const char *path) {
    int fd=open(path,O_RDONLY),gate[2]; assert(fd>=0 && !pipe2(gate,O_CLOEXEC));
    atomic_store(&delay_flush,1);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(gate[1]); char byte; assert(read(gate[0],&byte,1)==1); _exit(0);
    }
    close(gate[0]);
    assert(!ptrace(PTRACE_SEIZE,child,0,PTRACE_O_TRACEEXIT));
    assert(write(gate[1],"x",1)==1); close(gate[1]);
    /* EVENT_WAIT: the debugger sees the terminal stop before file retirement.
     * The fixture alarm bounds a wrong FLUSH-before-debugger dependency. */
    int status; assert(waitpid(child,&status,__WALL)==child && WIFSTOPPED(status));
    begin_stage("traced-exit-stop");
    assert((unsigned)status>>16==PTRACE_EVENT_EXIT);
    struct pollfd p={events[0],POLLIN,0}; assert(!poll(&p,1,0));
    assert(!ptrace(PTRACE_CONT,child,0,0));
    uint64_t unique=pending_flush();
    begin_stage("traced-exit-flush");
    assert(!waitpid(child,&status,__WALL|WNOHANG));
    response(unique,0,NULL,0);
    assert(waitpid(child,&status,__WALL)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    begin_stage("traced-exit-reaped");
    atomic_store(&delay_flush,0); assert(!close(fd));
}
int main(int argc,char **argv) {
    if (argc==3 && !strcmp(argv[1],"--exec-child")) {
        assert(write(atoi(argv[2]),"e",1)==1); return 0;
    }
    if (argc==2 && !strcmp(argv[1],"--filtered")) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,UINT32_MAX,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
        struct sock_fprog program={.len=sizeof(code)/sizeof(*code),.filter=code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
    }
    alarm(60);
    struct sigaction action={.sa_handler=signal_handler}; sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGUSR1,&action,NULL));
    char work[]="/tmp/md-fuse-close.XXXXXX"; assert(mkdtemp(work));
    device=open("/dev/fuse",O_RDWR|O_CLOEXEC); assert(device>=0);
    assert(!pipe2(events,O_CLOEXEC) && !pipe2(stopping,O_CLOEXEC) && !pipe2(release_events,O_CLOEXEC));
    char options[128]; snprintf(options,sizeof(options),"fd=%d,rootmode=40000,user_id=0,group_id=0",device);
    assert(!mount("close-test",work,"fuse",MS_NOSUID|MS_NODEV,options));
    pthread_t daemon; assert(!pthread_create(&daemon,NULL,serve,NULL));
    char path[256]; snprintf(path,sizeof(path),"%s/file",work);
    int fd=open(path,O_RDONLY); assert(fd>=0);
    begin_stage("alias-close");
    int alias=dup(fd); assert(alias>=0);
    atomic_store(&flush_error,-EIO); errno=0;
    assert(close(alias)==-1 && errno==EIO && atomic_load(&flushes)==1);
    errno=0; assert(fcntl(alias,F_GETFD)==-1 && errno==EBADF);
    assert(fcntl(fd,F_GETFD)>=0 && !atomic_load(&releases));
    atomic_store(&flush_error,0); assert(!close(fd) && atomic_load(&flushes)==2);
    begin_stage("delayed-close"); delayed_close(path,0);
    begin_stage("interrupted-close"); delayed_close(path,1);
    begin_stage("killed-close");
    fd=open(path,O_RDONLY); assert(fd>=0);
    atomic_store(&delay_flush,1);
    pid_t killed=fork(); assert(killed>=0);
    if (!killed) { close(fd); _exit(125); }
    uint64_t interrupted=pending_flush();
    assert(!kill(killed,SIGKILL));
    int killed_status;
    assert(waitpid(killed,&killed_status,0)==killed && WIFSIGNALED(killed_status)
        && WTERMSIG(killed_status)==SIGKILL);
    response(interrupted,0,NULL,0);
    atomic_store(&delay_flush,0); assert(!close(fd));
    for (unsigned mode=0;mode<5;++mode) {
        begin_stage((const char *[]) {"dup-close","exit","exec","range-close","thread-exec"}[mode]);
        fd=open(path,O_RDONLY); assert(fd>=0);
        atomic_store(&delay_flush,1);
        if (!mode || mode==3) {
            int replacement=open("/dev/null",O_RDONLY); assert(replacement>=0);
            struct closing c={.fd=fd,.replacement=replacement,.range=mode==3}; pthread_t thread;
            assert(!pthread_create(&thread,NULL,close_thread,&c));
            uint64_t unique=pending_flush(); assert(!atomic_load(&c.done));
            if (!mode) assert(fcntl(fd,F_GETFD)>=0);
            else { errno=0; assert(fcntl(fd,F_GETFD)==-1 && errno==EBADF); }
            response(unique,-EIO,NULL,0); join(thread);
            assert(c.result==(!mode ? fd : 0)); close(replacement);
        } else {
            int marker[2]; assert(!pipe2(marker,O_NONBLOCK));
            pid_t child=fork(); assert(child>=0);
            if (!child) {
                close(marker[0]);
                if (mode==2 || mode==4) {
                    assert(!fcntl(fd,F_SETFD,FD_CLOEXEC));
                    struct exec_args args={argv[0],marker[1]};
                    if (mode==2) exec_thread(&args);
                    pthread_t worker; assert(!pthread_create(&worker,NULL,exec_thread,&args));
                    for (;;) pause();
                }
                _exit(0);
            }
            close(marker[1]);
            uint64_t unique=pending_flush();
            int status; assert(!waitpid(child,&status,WNOHANG));
            char byte; errno=0;
            assert(read(marker[0],&byte,1)==-1 && errno==EAGAIN);
            response(unique,0,NULL,0);
            assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
            if (mode!=1) assert(read(marker[0],&byte,1)==1 && byte=='e');
            close(marker[0]);
        }
        atomic_store(&delay_flush,0); if (mode!=3) assert(!close(fd));
    }
    begin_stage("traced-exit"); traced_exit(path);
    begin_stage("unsupported-flush");
    fd=open(path,O_RDONLY); assert(fd>=0);
    atomic_store(&flush_error,-ENOSYS); assert(!close(fd));
    unsigned final_flushes=atomic_load(&flushes);
    fd=open(path,O_RDONLY); assert(fd>=0 && !close(fd));
    assert(atomic_load(&flushes)==final_flushes);
    for (unsigned i=0;i<12;++i) {
        struct pollfd p={release_events[0],POLLIN,0}; char byte;
        /* EVENT_WAIT: all opened descriptions reached RELEASE before unmount. */
        assert(poll(&p,1,5000)==1 && read(p.fd,&byte,1)==1);
    }
    assert(atomic_load(&releases)==12);
    assert(write(stopping[1],"x",1)==1); join(daemon);
    assert(!umount(work));
    close(device); close(events[0]); close(events[1]); close(stopping[0]); close(stopping[1]);
    close(release_events[0]); close(release_events[1]);
    assert(!rmdir(work));
    puts("PASS FUSE guest close: flush errors, aliases, concurrent FD reuse, signal, dup replacement, exit and exec");
    return 0;
}
