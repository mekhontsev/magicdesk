#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/msg.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static const char *const names[]={"user","mnt","uts","ipc"};
static const unsigned types[]={CLONE_NEWUSER,CLONE_NEWNS,CLONE_NEWUTS,CLONE_NEWIPC};
static void namespaces(int fd[4]) {
    for (unsigned i=0;i<4;++i) {
        char path[64]; snprintf(path,sizeof(path),"/proc/self/ns/%s",names[i]);
        fd[i]=open(path,O_RDONLY|O_CLOEXEC); assert(fd[i]>=0);
    }
}
static void map(const char *path, const char *text) {
    int fd=open(path,O_WRONLY); assert(fd>=0);
    assert(write(fd,text,strlen(text))==(ssize_t)strlen(text)); close(fd);
}
static void transfer(int socket, int fd[4], int receive) {
    union { struct cmsghdr align; char bytes[CMSG_SPACE(4*sizeof(int))]; } control={0};
    char byte=0; struct iovec data={&byte,1};
    struct msghdr msg={.msg_iov=&data,.msg_iovlen=1,.msg_control=control.bytes,
        .msg_controllen=sizeof(control.bytes)};
    if (!receive) {
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
        c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN(4*sizeof(int));
        memcpy(CMSG_DATA(c),fd,4*sizeof(int));
        assert(sendmsg(socket,&msg,0)==1);
    } else {
        assert(recvmsg(socket,&msg,MSG_CMSG_CLOEXEC)==1 && !(msg.msg_flags&MSG_CTRUNC));
        struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
        assert(c && c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS && c->cmsg_len==CMSG_LEN(4*sizeof(int)));
        memcpy(fd,CMSG_DATA(c),4*sizeof(int));
    }
}
static void finished(pid_t pid) {
    int status;
    /* EVENT_WAIT: exact child exit; the fixture alarm bounds failure. */
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
}
static int shared_fs(void *data) {
    const int *fd=data;
    assert(setns(fd[0],CLONE_NEWUSER)<0 && errno==EINVAL);
    assert(setns(fd[1],CLONE_NEWNS)<0 && errno==EINVAL);
    return 0;
}
static void *thread_entry(void *data) {
    const int *fd=data;
    assert(setns(fd[0],CLONE_NEWUSER)<0 && errno==EINVAL);
    return NULL;
}
static void pidfd_namespaces(int logical_pid) {
    pid_t caller=fork(); assert(caller>=0);
    if (caller) { finished(caller); return; }
    if (logical_pid) assert(!unshare(CLONE_NEWPID));
    assert(!setgid(1000) && !setuid(1000));
    int ready[2], finish[2]; assert(!pipe(ready) && !pipe(finish));
    pid_t target=fork(); assert(target>=0);
    if (!target) {
        close(ready[0]); close(finish[1]);
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS|CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWNET));
        map("/proc/self/uid_map","0 1000 1\n"); map("/proc/self/setgroups","deny\n");
        map("/proc/self/gid_map","0 1000 1\n");
        assert(!sethostname("pidfd-joined",12));
        assert(!mount("tmpfs","/tmp/setns","tmpfs",0,"mode=0755"));
        int fd=open("/tmp/setns/pidfd",O_CREAT|O_RDWR,0600); assert(fd>=0); close(fd);
        char byte;
        /* The creating parent retains authority in this descendant user
         * namespace even when the target is nondumpable. */
        assert(!prctl(PR_SET_DUMPABLE,0));
        assert(write(ready[1],"x",1)==1);
        /* EVENT_WAIT: parent completes live-target admission before owner exit. */
        assert(read(finish[0],&byte,1)==1); _exit(0);
    }
    close(ready[1]); close(finish[0]); char byte;
    /* EVENT_WAIT: child's namespace setup, bounded by the fixture alarm. */
    assert(read(ready[0],&byte,1)==1); close(ready[0]);
    int pidfd=syscall(SYS_pidfd_open,target,0); assert(pidfd>=0);
    int copy=dup(pidfd); assert(copy>=0); close(pidfd); pidfd=copy;
    assert(setns(pidfd,0)<0 && errno==EINVAL);
    assert(setns(pidfd,CLONE_FILES)<0 && errno==EINVAL);
    assert(setns(finish[1],CLONE_NEWUSER)<0 && errno==EINVAL);
    assert(setns(pidfd,CLONE_NEWNET)<0 && errno==EPERM);
    int original=open("/proc/self/ns/user",O_RDONLY); assert(original>=0);
    struct stat before, after; assert(!fstat(original,&before));
    /* The selected cgroup namespace belongs to the ancestor: reject the whole
     * candidate after preparing user/UTS/IPC, without publishing any of them. */
    assert(setns(pidfd,CLONE_NEWUSER|CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWCGROUP)<0 && errno==EPERM);
    assert(!stat("/proc/self/ns/user",&after) && before.st_ino==after.st_ino);
    assert(getuid()==1000 && access("/tmp/setns/pidfd",F_OK)<0 && errno==ENOENT);
    char hostname[128]; assert(!gethostname(hostname,sizeof(hostname)) && strcmp(hostname,"pidfd-joined"));
    assert(!setns(pidfd,CLONE_NEWUSER|CLONE_NEWNS|CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWNET));
    assert(getuid()==0 && !access("/tmp/setns/pidfd",F_OK));
    assert(!gethostname(hostname,sizeof(hostname)) && !strcmp(hostname,"pidfd-joined"));
    assert(setns(pidfd,CLONE_NEWUSER)<0 && errno==EINVAL);
    assert(setns(original,CLONE_NEWUSER)<0 && errno==EPERM); close(original);
    assert(write(finish[1],"x",1)==1); close(finish[1]); finished(target);
    assert(setns(pidfd,CLONE_NEWNET)<0 && errno==ESRCH); close(pidfd);
    _exit(0);
}
void test_setns_guest(void) {
    alarm(60); setbuf(stdout,NULL);
    assert(!chmod("/",0755) && !chmod("/tmp",01777));
    assert(!mkdir("/tmp/setns",0755));
    pidfd_namespaces(0);
    pidfd_namespaces(1);
    int old[4]; namespaces(old);
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    pid_t producer=fork(); assert(producer>=0);
    if (!producer) {
        close(sockets[0]);
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS|CLONE_NEWUTS|CLONE_NEWIPC));
        map("/proc/self/uid_map","0 0 1\n"); map("/proc/self/setgroups","deny\n");
        map("/proc/self/gid_map","0 0 1\n");
        assert(!sethostname("joined",6));
        assert(!mount("tmpfs","/tmp/setns","tmpfs",0,"mode=0755"));
        int file=open("/tmp/setns/data",O_CREAT|O_RDWR,0600); assert(file>=0);
        assert(write(file,"retained",8)==8); close(file);
        int queue=msgget(0x534e53,IPC_CREAT|0600); assert(queue>=0);
        struct { long type; char text[8]; } message={.type=1};
        memcpy(message.text,"retained",sizeof(message.text));
        assert(!msgsnd(queue,&message,sizeof(message.text),0));
        int fd[4]; namespaces(fd); transfer(sockets[1],fd,0);
        for (unsigned i=0;i<4;++i) close(fd[i]);
        _exit(0);
    }
    close(sockets[1]); int next[4]; transfer(sockets[0],next,1); close(sockets[0]);
    finished(producer);
    void *stack=malloc(65536); assert(stack);
    pid_t shared=clone(shared_fs,(char *)stack+65536,CLONE_FS|SIGCHLD,next); assert(shared>=0);
    finished(shared); free(stack);
    pthread_t thread; assert(!pthread_create(&thread,NULL,thread_entry,next));
    assert(!pthread_join(thread,NULL));
    assert(access("/tmp/setns/data",F_OK)<0 && errno==ENOENT);
    assert(msgget(0x534e53,0)<0 && errno==ENOENT);
    pid_t consumer=fork(); assert(consumer>=0);
    if (!consumer) {
        assert(!chdir("/tmp"));
        assert(setns(next[2],CLONE_NEWNS)<0 && errno==EINVAL);
        assert(setns(-1,0)<0 && errno==EBADF);
        for (unsigned i=1;i<4;++i) assert(!setns(next[i],types[i]));
        char cwd[128], hostname[128];
        assert(getcwd(cwd,sizeof(cwd)) && !strcmp(cwd,"/"));
        assert(!gethostname(hostname,sizeof(hostname)) && !strcmp(hostname,"joined"));
        int file=open("/tmp/setns/data",O_RDONLY); assert(file>=0);
        assert(read(file,cwd,sizeof(cwd))==8 && !memcmp(cwd,"retained",8)); close(file);
        int queue=msgget(0x534e53,0); assert(queue>=0);
        struct { long type; char text[8]; } message;
        assert(msgrcv(queue,&message,sizeof(message.text),1,IPC_NOWAIT)==8 && !memcmp(message.text,"retained",8));
        assert(!msgctl(queue,IPC_RMID,NULL));
        for (unsigned i=1;i<4;++i) assert(!setns(old[i],0));
        assert(access("/tmp/setns/data",F_OK)<0 && errno==ENOENT);
        assert(!setns(next[0],CLONE_NEWUSER));
        assert(!getuid() && !getgid());
        assert(setns(next[0],0)<0 && errno==EINVAL);
        assert(setns(old[0],0)<0 && errno==EPERM);
        assert(setns(old[2],0)<0 && errno==EPERM);
        assert(!setns(next[2],0));
        assert(!gethostname(hostname,sizeof(hostname)) && !strcmp(hostname,"joined"));
        _exit(0);
    }
    finished(consumer);
    for (unsigned i=0;i<4;++i) { close(old[i]); close(next[i]); }
    assert(!rmdir("/tmp/setns"));
    puts("PASS guest setns: pidfd atomic entry/rollback, SCM-held namespaces, owner exit, root/cwd, IPC/UTS and authority");
}
