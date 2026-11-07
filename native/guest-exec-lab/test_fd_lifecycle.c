#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/close_range.h>
#include <linux/filter.h>
#include <linux/kcmp.h>
#include <linux/seccomp.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/sock_diag.h>
#include <linux/unix_diag.h>
#include <elf.h>
#include <asm/ptrace.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

enum phase { LIVE, EXIT_NORMAL, EXIT_FILTER, EXIT_KILL };
struct observation {
    pid_t parent;
    int protected, deny_close, phase;
    int fd, attempted, result, error;
};
static int wait_child(pid_t child) {
    /* EVENT_WAIT: exact ptrace/child event; the fixture alarm bounds failure. */
    int status; pid_t result;
    do { result=waitpid(child,&status,__WALL); } while (result<0 && errno==EINTR);
    assert(result==child); return status;
}
static void filter(int call, unsigned action) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,call,0,1),
        BPF_STMT(BPF_RET|BPF_K,action),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
__attribute__((noreturn)) static void cleanup(int fd, struct observation *o) {
    o->attempted=1;
    o->result=close(fd); o->error=o->result<0 ? errno : 0;
    _exit(29);
}
static int traced_child(void *argument) {
    struct observation *o=argument;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=o->parent) _exit(99);
    assert(!ptrace(PTRACE_TRACEME,0,0,0));
    raise(SIGSTOP);
    if (o->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    filter(SYS_getppid,SECCOMP_RET_KILL_THREAD);
    if (o->deny_close) filter(SYS_close,SECCOMP_RET_ERRNO|EPERM);
    o->fd=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0); assert(o->fd>=0);
    raise(SIGSTOP);
    if (o->phase==EXIT_FILTER) syscall(SYS_getppid);
    _exit(17);
}
static void redirect(pid_t child, int fd, struct observation *o) {
    struct user_pt_regs registers;
    struct iovec vector={&registers,sizeof(registers)};
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&vector));
    assert(vector.iov_len==sizeof(registers));
    registers.pc=(uintptr_t)cleanup;
    registers.regs[0]=fd; registers.regs[1]=(uintptr_t)o;
    assert(!ptrace(PTRACE_SETREGSET,child,(void *)NT_PRSTATUS,&vector));
}
static void terminal(int protected, enum phase phase, int deny_close) {
    struct observation *o=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(o!=MAP_FAILED);
    *o=(struct observation){.parent=getpid(),.protected=protected,.deny_close=deny_close,
        .phase=phase,.fd=-1};
    void *stack=malloc(1024*1024); assert(stack);
    pid_t child=clone(traced_child,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,o);
    assert(child>0);
    int status=wait_child(child); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL));
    assert(!ptrace(PTRACE_CONT,child,0,0));
    status=wait_child(child); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP && o->fd>=0);
    int target=o->fd; assert(fcntl(target,F_GETFD)>=0);
    if (phase==LIVE) redirect(child,target,o);
    assert(!ptrace(PTRACE_CONT,child,0,phase==EXIT_KILL ? SIGKILL : 0));
    status=wait_child(child);
    assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_EXIT);
    if (phase!=LIVE) redirect(child,target,o);
    assert(!ptrace(PTRACE_CONT,child,0,0));
    status=wait_child(child);
    if (phase==EXIT_FILTER || phase==EXIT_KILL)
        assert(WIFSIGNALED(status) && WTERMSIG(status)==(phase==EXIT_FILTER ? SIGSYS : SIGKILL));
    else assert(WIFEXITED(status) && WEXITSTATUS(status)==(phase==LIVE ? 29 : 17));
    if (phase==LIVE) {
        assert(o->attempted && o->result==(deny_close ? -1 : 0) && o->error==(deny_close ? EPERM : 0));
        if (!deny_close) assert(fcntl(target,F_GETFD)==-1 && errno==EBADF);
    } else assert(!o->attempted);
    if (phase!=LIVE || deny_close) assert(fcntl(target,F_GETFD)>=0 && !close(target));
    printf("PASS ptrace cleanup boundary protected=%d phase=%d denyClose=%d ran=%d error=%d\n",
        protected,phase,deny_close,o->attempted,o->error);
    free(stack); assert(!munmap(o,4096));
}
enum object_kind { REGULAR, DIRECTORY, SYMLINK };
static int path_source(const char *directory, enum object_kind kind) {
    char path[4096]; snprintf(path,sizeof(path),"%s/object",directory);
    if (kind==DIRECTORY) assert(!mkdir(path,0700));
    else if (kind==SYMLINK) assert(!symlink("absent-target",path));
    else {
        int data=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(data>=0);
        assert(write(data,"x",1)==1 && !close(data));
    }
    int source=open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC); assert(source>=0);
    assert(!(kind==DIRECTORY ? rmdir(path) : unlink(path)));
    return source;
}
static void path_transfer(const char *directory, enum object_kind kind,
        int protected_receiver, int protected_provider, int free_slots) {
    int source=path_source(directory,kind);
    struct stat expected; assert(!fstat(source,&expected));
    int pipefd[2]; assert(!pipe2(pipefd,O_CLOEXEC));
    int saved_dumpable=prctl(PR_GET_DUMPABLE); assert(saved_dumpable>=0);
    if (protected_provider) assert(!prctl(PR_SET_DUMPABLE,0));
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(99);
        assert(!close(source) && !close(pipefd[0]));
        assert(!prctl(PR_SET_DUMPABLE,protected_receiver ? 0 : 1));
        if (free_slots>=0) {
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            assert(limit.rlim_max>=64 && pipefd[1]<64);
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            int last=-1, next;
            while ((next=fcntl(pipefd[1],F_DUPFD_CLOEXEC,0))>=0) last=next;
            assert(errno==EMFILE && last>=0);
            if (free_slots) assert(!close(last));
        }
        filter(SYS_socket,SECCOMP_RET_KILL_PROCESS);
        filter(SYS_connect,SECCOMP_RET_KILL_PROCESS);
        filter(SYS_recvmsg,SECCOMP_RET_KILL_PROCESS);
        char path[64]; snprintf(path,sizeof(path),"/proc/%d/fd/%d",parent,source);
        int fd=open(path,O_PATH|O_CLOEXEC);
        int result[2]={fd<0 ? errno : 0,0};
        if (protected_provider && result[0]==ENOENT) {
            /* hidepid can conceal a live protected provider, not only its FD. */
            assert(!kill(parent,0));
            snprintf(path,sizeof(path),"/proc/%d",parent);
            struct stat hidden;
            assert(stat(path,&hidden)==-1 && errno==ENOENT);
        }
        if (fd>=0) {
            struct stat actual; assert(!fstat(fd,&actual));
            result[1]=actual.st_dev==expected.st_dev && actual.st_ino==expected.st_ino
                && (actual.st_mode&S_IFMT)==(expected.st_mode&S_IFMT);
            assert((fcntl(fd,F_GETFL)&O_PATH) && !close(fd));
        }
        assert(write(pipefd[1],result,sizeof(result))==sizeof(result));
        _exit(0);
    }
    assert(!close(pipefd[1]));
    /* EVENT_WAIT: result from the exact child; alarm bounds denial or failure. */
    int result[2]={-1,-1}; assert(read(pipefd[0],result,sizeof(result))==sizeof(result));
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    struct stat retained; assert(!fstat(source,&retained));
    assert(retained.st_dev==expected.st_dev && retained.st_ino==expected.st_ino);
    if (protected_provider) assert(!prctl(PR_SET_DUMPABLE,saved_dumpable));
    assert(!close(pipefd[0]) && !close(source));
    int expected_denial=protected_provider || free_slots==0;
    if (free_slots==0) assert(result[0]==EMFILE);
    if (protected_provider && free_slots)
        assert(result[0]==EACCES || result[0]==EPERM || result[0]==ENOENT);
    printf("%s proc O_PATH kind=%d protectedReceiver=%d protectedProvider=%d freeSlots=%d errno=%d exact=%d\n",
        expected_denial || (!result[0] && result[1]) ? "PASS" : "LIMIT",
        kind,protected_receiver,protected_provider,free_slots,result[0],result[1]);
}
union bridge_frame { uint64_t words[8]; char text[64]; };
extern void md_lifecycle_bridge(union bridge_frame *);
extern char md_lifecycle_bridge_trap[];
static unsigned count_low_fds(void) {
    unsigned count=0;
    for (int fd=0;fd<64;++fd) if (fcntl(fd,F_GETFD)>=0) ++count;
    return count;
}
static void path_bridge(const char *directory, enum object_kind kind, int protected, int free_slots) {
    int source=path_source(directory,kind);
    struct stat expected; assert(!fstat(source,&expected));
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(99);
        assert(!close(source));
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_max>=64);
        limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int last=-1, next;
        while ((next=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=next;
        assert(errno==EMFILE && last>=0);
        if (free_slots) assert(!close(last));
        filter(SYS_socket,SECCOMP_RET_KILL_PROCESS);
        filter(SYS_connect,SECCOMP_RET_KILL_PROCESS);
        filter(SYS_recvmsg,SECCOMP_RET_KILL_PROCESS);
        unsigned before=count_low_fds();
        union bridge_frame frame={.text="open retained O_PATH"};
        md_lifecycle_bridge(&frame);
        assert(count_low_fds()==before && !strncmp(frame.text,"/proc/",6));
        int fd=open(frame.text,O_PATH|O_CLOEXEC);
        if (!free_slots) assert(fd==-1 && errno==EMFILE);
        else {
            assert(fd>=0 && count_low_fds()==before+1);
            struct stat actual; assert(!fstat(fd,&actual));
            assert(actual.st_dev==expected.st_dev && actual.st_ino==expected.st_ino
                && (actual.st_mode&S_IFMT)==(expected.st_mode&S_IFMT));
            assert(!close(fd));
        }
        assert(count_low_fds()==before);
        _exit(0);
    }
    int status=wait_child(child); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_EXITKILL));
    assert(!ptrace(PTRACE_CONT,child,0,0));
    status=wait_child(child); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGTRAP);
    struct user_pt_regs registers;
    struct iovec vector={&registers,sizeof(registers)};
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&vector));
    assert(registers.pc==(uintptr_t)md_lifecycle_bridge_trap);
    union bridge_frame frame; memcpy(frame.words,registers.regs,sizeof(frame));
    assert(!strcmp(frame.text,"open retained O_PATH"));
    memset(&frame,0,sizeof(frame));
    snprintf(frame.text,sizeof(frame.text),"/proc/%d/fd/%d",parent,source);
    memcpy(registers.regs,frame.words,sizeof(frame)); registers.pc+=4;
    assert(!ptrace(PTRACE_SETREGSET,child,(void *)NT_PRSTATUS,&vector));
    assert(!ptrace(PTRACE_CONT,child,0,0));
    status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(source));
    printf("PASS register request + retained O_PATH kind=%d protected=%d freeSlots=%d noPrivateFD=1\n",
        kind,protected,free_slots);
}
static void reopen_description(const char *directory) {
    char path[4096]; snprintf(path,sizeof(path),"%s/data",directory);
    int source=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(source>=0);
    assert(!unlink(path) && lseek(source,11,SEEK_SET)==11);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(99);
        assert(!close(source));
        snprintf(path,sizeof(path),"/proc/%d/fd/%d",parent,source);
        int fd=open(path,O_RDWR|O_CLOEXEC); assert(fd>=0);
        assert(lseek(fd,0,SEEK_CUR)==0 && lseek(fd,29,SEEK_SET)==29 && !close(fd));
        _exit(0);
    }
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(lseek(source,0,SEEK_CUR)==11 && !close(source));
    puts("PASS proc reopen has independent offset: not an SCM_RIGHTS or pidfd_getfd replacement");
}
struct helper_input {
    pid_t owner;
    int ready, release, protected, deny_close;
};
static int private_helper(void *argument) {
    struct helper_input *input=argument;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=input->owner) _exit(98);
    assert(prctl(PR_GET_DUMPABLE)==!input->protected);
    int fd=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0); assert(fd>=0);
    if (input->deny_close) assert(close(fd)==-1 && errno==EPERM);
    assert(write(input->ready,&fd,sizeof(fd))==sizeof(fd));
    /* EVENT_WAIT: owner kills the helper while its private RPC descriptor is
     * live. The outer fixture alarm bounds a missing readiness/terminal event. */
    char byte; assert(read(input->release,&byte,1)==1);
    return 99;
}
static void helper_boundary(int protected, int deny_clone, int deny_close) {
    pid_t owner=getpid();
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=owner) _exit(98);
        int data[2],ready[2],release[2];
        assert(!pipe2(data,O_CLOEXEC|O_NONBLOCK) && !pipe2(ready,O_CLOEXEC)
            && !pipe2(release,O_CLOEXEC));
        void *stack=malloc(1024*1024); assert(stack);
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        if (deny_close) filter(SYS_close,SECCOMP_RET_ERRNO|EPERM);
        if (deny_clone) filter(SYS_clone,SECCOMP_RET_ERRNO|EPERM);
        struct helper_input input={getpid(),ready[1],release[0],protected,deny_close};
        pid_t helper=clone(private_helper,(char *)stack+1024*1024,SIGCHLD,&input);
        if (deny_clone) {
            assert(helper==-1 && errno==EPERM);
            printf("LIMIT private-table helper: application clone filter protected=%d denyClose=%d errno=%d\n",
                protected,deny_close,errno);
        } else {
            assert(helper>0);
            int socket_fd=-1;
            /* EVENT_WAIT: helper owns its private socket; alarm fails a stall. */
            assert(read(ready[0],&socket_fd,sizeof(socket_fd))==sizeof(socket_fd));
            assert(socket_fd>=0 && fcntl(socket_fd,F_GETFD)==-1 && errno==EBADF);
            assert(!syscall(SYS_close_range,data[1],data[1],0));
            char byte;
            assert(read(data[0],&byte,1)==-1 && errno==EAGAIN);
            assert(!kill(helper,SIGKILL));
            int status=wait_child(helper);
            assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            assert(read(data[0],&byte,1)==0);
            printf("PASS private-table helper teardown protected=%d denyClose=%d noParentSocket=1\n",protected,deny_close);
            puts("LIMIT private-table helper: inherited unrelated pipe delays EOF until helper exit");
        }
        free(stack); _exit(0);
    }
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
static int socket_peer(int diagnostic, int socket_fd, uint32_t *peer) {
    struct stat st; assert(!fstat(socket_fd,&st) && st.st_ino<=UINT32_MAX);
    uint64_t cookie=0; socklen_t length=sizeof(cookie);
    assert(!getsockopt(socket_fd,SOL_SOCKET,SO_COOKIE,&cookie,&length) && length==sizeof(cookie));
    struct {
        struct nlmsghdr header;
        struct unix_diag_req request;
    } query={.header={.nlmsg_len=sizeof(query),.nlmsg_type=SOCK_DIAG_BY_FAMILY,
            .nlmsg_flags=NLM_F_REQUEST,.nlmsg_seq=1},
        .request={.sdiag_family=AF_UNIX,.udiag_states=UINT32_MAX,
            .udiag_ino=(uint32_t)st.st_ino,.udiag_show=UDIAG_SHOW_PEER,
            .udiag_cookie={(uint32_t)cookie,(uint32_t)(cookie>>32)}}};
    struct sockaddr_nl kernel={.nl_family=AF_NETLINK};
    if (sendto(diagnostic,&query,sizeof(query),0,(void *)&kernel,sizeof(kernel))<0) return -errno;
    union { struct nlmsghdr align; char bytes[4096]; } reply;
    struct sockaddr_nl source={0}; socklen_t source_size=sizeof(source);
    /* EVENT_WAIT: one exact kernel diagnostic reply; fixture alarm fails a stall. */
    ssize_t received=recvfrom(diagnostic,reply.bytes,sizeof(reply.bytes),0,(void *)&source,&source_size);
    if (received<0) return -errno;
    if (source_size!=sizeof(source) || source.nl_pid || !NLMSG_OK(&reply.align,received)
            || reply.align.nlmsg_seq!=1) return -EPROTO;
    if (reply.align.nlmsg_type==NLMSG_ERROR) {
        if (NLMSG_PAYLOAD(&reply.align,0)<sizeof(struct nlmsgerr)) return -EPROTO;
        const struct nlmsgerr *error=NLMSG_DATA(&reply.align);
        return error->error ? error->error : -EPROTO;
    }
    if (reply.align.nlmsg_type!=SOCK_DIAG_BY_FAMILY
            || NLMSG_PAYLOAD(&reply.align,0)<sizeof(struct unix_diag_msg)) return -EPROTO;
    const struct unix_diag_msg *answer=NLMSG_DATA(&reply.align);
    if (answer->udiag_family!=AF_UNIX || answer->udiag_ino!=st.st_ino
            || memcmp(answer->udiag_cookie,query.request.udiag_cookie,sizeof(answer->udiag_cookie))) return -ESTALE;
    size_t size=NLMSG_PAYLOAD(&reply.align,sizeof(*answer));
    const struct rtattr *attribute=(const void *)((const char *)answer+NLMSG_ALIGN(sizeof(*answer)));
    for (; RTA_OK(attribute,size); attribute=RTA_NEXT(attribute,size)) {
        if (attribute->rta_type!=UNIX_DIAG_PEER) continue;
        if (RTA_PAYLOAD(attribute)!=sizeof(*peer)) return -EPROTO;
        memcpy(peer,RTA_DATA(attribute),sizeof(*peer)); return 0;
    }
    return -ENODATA;
}
static void socket_peer_boundary(void) {
    int diagnostic=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_SOCK_DIAG);
    if (diagnostic<0) {
        printf("LIMIT Unix socket peer diagnostics: create errno=%d\n",errno); return;
    }
    const int types[]={SOCK_STREAM,SOCK_DGRAM,SOCK_SEQPACKET};
    for (unsigned i=0;i<sizeof(types)/sizeof(*types);++i) {
        int sockets[2]; assert(!socketpair(AF_UNIX,types[i]|SOCK_CLOEXEC,0,sockets));
        for (unsigned side=0;side<2;++side) {
            uint32_t peer=0; int result=socket_peer(diagnostic,sockets[side],&peer);
            if (result) printf("LIMIT Unix socket peer diagnostics type=%d side=%u errno=%d\n",types[i],side,-result);
            else {
                struct stat st; assert(!fstat(sockets[!side],&st) && peer==st.st_ino);
                printf("PASS Unix socket peer diagnostics type=%d side=%u exactPeer=1\n",types[i],side);
            }
        }
        assert(!close(sockets[0]) && !close(sockets[1]));
    }
    assert(!close(diagnostic));
}
static int native_kcmp_available(void) {
    /* Only this isolated probe may classify inherited seccomp SIGSYS as denial. */
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(fcntl(STDOUT_FILENO,F_GETFD)>=0);
        long result=syscall(SYS_kcmp,getpid(),getpid(),KCMP_FILE,STDOUT_FILENO,STDOUT_FILENO);
        if (!result) _exit(0);
        assert(result==-1 && (errno==EPERM || errno==EACCES || errno==ENOSYS));
        printf("LIMIT native kcmp: errno=%d\n",errno);
        _exit(77);
    }
    int status=wait_child(child);
    if (WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS) {
        puts("LIMIT native kcmp: inherited seccomp SIGSYS");
        return 0;
    }
    assert(WIFEXITED(status) && (WEXITSTATUS(status)==0 || WEXITSTATUS(status)==77));
    return !WEXITSTATUS(status);
}
static void description_identity(const char *directory, int compare) {
    int fd=open(directory,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    int other=open(directory,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    assert(fd>=0 && other>=0);
    int duplicate=fcntl(fd,F_DUPFD_CLOEXEC,0); assert(duplicate>=0);
    if (compare) {
        long same=syscall(SYS_kcmp,getpid(),getpid(),KCMP_FILE,fd,duplicate);
        assert(!same);
        assert(syscall(SYS_kcmp,getpid(),getpid(),KCMP_FILE,fd,other)>0);
        puts("PASS local OFD comparison: kcmp distinguishes dup from independent open");
    }
    assert(!close(duplicate) && !close(other) && !close(fd));
}
static ssize_t export_attempt(int endpoint, int fd) {
    char byte='f';
    struct iovec vector={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control)};
    struct cmsghdr *c=CMSG_FIRSTHDR(&message);
    c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN(sizeof(fd));
    memcpy(CMSG_DATA(c),&fd,sizeof(fd));
    return sendmsg(endpoint,&message,MSG_DONTWAIT|MSG_NOSIGNAL);
}
static void export_fd(int endpoint, int fd) {
    assert(export_attempt(endpoint,fd)==1);
}
static int import_fd_from(int endpoint, int discard, pid_t peer) {
    char byte=0;
    struct iovec vector={&byte,1};
    union { struct cmsghdr align;
        char bytes[CMSG_SPACE(sizeof(int))+CMSG_SPACE(sizeof(struct ucred))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=discard ? NULL : control.bytes,
        .msg_controllen=discard ? 0 : sizeof(control)};
    assert(recvmsg(endpoint,&message,MSG_DONTWAIT|MSG_CMSG_CLOEXEC)==1 && byte=='f');
    assert(!(message.msg_flags&MSG_TRUNC));
    if (discard) {
        assert(message.msg_flags&MSG_CTRUNC); return -1;
    }
    assert(!(message.msg_flags&MSG_CTRUNC));
    int fd=-1,credentials=0;
    for (struct cmsghdr *c=CMSG_FIRSTHDR(&message);c;c=CMSG_NXTHDR(&message,c)) {
        assert(c->cmsg_level==SOL_SOCKET);
        if (c->cmsg_type==SCM_RIGHTS) {
            assert(fd==-1 && c->cmsg_len==CMSG_LEN(sizeof(int)));
            memcpy(&fd,CMSG_DATA(c),sizeof(fd));
        } else {
            assert(c->cmsg_type==SCM_CREDENTIALS && c->cmsg_len==CMSG_LEN(sizeof(struct ucred))
                && peer>0 && !credentials);
            struct ucred actual; memcpy(&actual,CMSG_DATA(c),sizeof(actual));
            assert(actual.pid==peer && actual.uid==getuid() && actual.gid==getgid());
            credentials=1;
        }
    }
    assert(credentials==!!peer && fd>=0 && (fcntl(fd,F_GETFD)&FD_CLOEXEC)); return fd;
}
static int import_fd(int endpoint, int discard) {
    return import_fd_from(endpoint,discard,0);
}
static long epoll_target(int observer,int number,int candidate) {
    struct kcmp_epoll_slot slot={.efd=observer,.tfd=number};
    long result=syscall(SYS_kcmp,getpid(),getpid(),KCMP_EPOLL_TFD,candidate,&slot);
    return result<0 ? -errno : result;
}
static int epoll_depth(void) {
    int chain[16],count=1;
    chain[0]=epoll_create1(EPOLL_CLOEXEC); assert(chain[0]>=0);
    struct epoll_event event={0};
    for (;count<16;++count) {
        chain[count]=epoll_create1(EPOLL_CLOEXEC); assert(chain[count]>=0);
        if (epoll_ctl(chain[count],EPOLL_CTL_ADD,chain[count-1],&event)) {
            assert(errno==ELOOP); close(chain[count]); break;
        }
    }
    assert(count<16);
    int observer=epoll_create1(EPOLL_CLOEXEC); assert(observer>=0);
    assert(epoll_ctl(observer,EPOLL_CTL_ADD,chain[count-1],&event)==-1 && errno==ELOOP);
    for (int i=count-1;i>=0;--i) close(chain[i]);
    close(observer);
    return count;
}
static void epoll_weak_identity(int compare) {
    unsigned before=count_low_fds();
    int observer=epoll_create1(EPOLL_CLOEXEC),fd=epoll_create1(EPOLL_CLOEXEC);
    int other=epoll_create1(EPOLL_CLOEXEC); assert(observer>=0 && fd>=0 && other>=0);
    int duplicate=fcntl(fd,F_DUPFD_CLOEXEC,0); assert(duplicate>=0);
    struct epoll_event event={0};
    assert(!epoll_ctl(observer,EPOLL_CTL_ADD,fd,&event));
    if (compare) {
        long result=epoll_target(observer,fd,duplicate);
        assert(!result && epoll_target(observer,fd,other)>0);
        int number=fd;
        assert(!close(fd) && !epoll_target(observer,number,duplicate));
        int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,pair));
        export_fd(pair[0],duplicate); close(duplicate); duplicate=-1;
        assert(epoll_target(observer,number,other)>0);
        int received=import_fd(pair[1],0);
        assert(!epoll_target(observer,number,received));
        close(received);
        assert(epoll_target(observer,number,other)==-ENOENT);
        close(pair[0]); close(pair[1]);
        assert(dup3(other,number,O_CLOEXEC)==number);
        assert(epoll_target(observer,number,number)==-ENOENT);
        close(number); fd=-1;
        puts("PASS native weak epoll identity: aliases, queued SCM_RIGHTS, final retirement and FD reuse");
    }
    if (fd>=0) close(fd);
    if (duplicate>=0) close(duplicate);
    close(other); close(observer);
    int depth=epoll_depth();
    printf("LIMIT weak epoll observation: valid native depth=%d rejects observer with ELOOP\n",depth);
    assert(count_low_fds()==before);
}
static void reject_forged_peer(int endpoint, pid_t other) {
    char byte='x'; struct iovec vector={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(struct ucred))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control)};
    struct cmsghdr *c=CMSG_FIRSTHDR(&message);
    c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_CREDENTIALS;
    c->cmsg_len=CMSG_LEN(sizeof(struct ucred));
    struct ucred claimed={other,getuid(),getgid()}; memcpy(CMSG_DATA(c),&claimed,sizeof(claimed));
    assert(sendmsg(endpoint,&message,MSG_DONTWAIT|MSG_NOSIGNAL)==-1 && errno==EPERM);
}
static void queue_retirement(int type) {
    unsigned before=count_low_fds();
    int sockets[2],pipefd[2];
    assert(!socketpair(AF_UNIX,type|SOCK_CLOEXEC|SOCK_NONBLOCK,0,sockets));
    assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    int retained=fcntl(sockets[1],F_DUPFD_CLOEXEC,0); assert(retained>=0);
    export_fd(sockets[0],pipefd[1]); assert(!close(pipefd[1]));
    char byte;
    assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
    assert(!shutdown(sockets[0],SHUT_RDWR) && !close(sockets[0]));
    assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
    assert(!shutdown(retained,SHUT_RDWR));
    assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
    import_fd(retained,1);
    assert(read(pipefd[0],&byte,1)==0);
    assert(!close(retained) && !close(sockets[1]) && !close(pipefd[0]));
    assert(count_low_fds()==before);
    printf("PASS socket queue type=%d: peer close/shutdown retain rights; owner discard releases pipe EOF\n",type);
}
struct export_sender {
    pid_t parent;
    int endpoint, source, rounds, kill_send;
};
static int protected_exporter(void *argument) {
    const struct export_sender *sender=argument;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=sender->parent) _exit(99);
    assert(!prctl(PR_SET_DUMPABLE,0));
    filter(SYS_socket,SECCOMP_RET_ERRNO|EPERM);
    filter(SYS_connect,SECCOMP_RET_ERRNO|EPERM);
    filter(SYS_close,SECCOMP_RET_ERRNO|EPERM);
    filter(SYS_clone,SECCOMP_RET_ERRNO|EPERM);
    assert(close(sender->endpoint)==-1 && errno==EPERM);
    reject_forged_peer(sender->endpoint,sender->parent);
    if (sender->kill_send) filter(SYS_sendmsg,SECCOMP_RET_KILL_THREAD);
    for (int i=0;i<sender->rounds;++i) {
        export_fd(sender->endpoint,sender->source);
        raise(SIGSTOP);
    }
    _exit(0);
}
static void control_send(int fd, int value) {
    assert(send(fd,&value,sizeof(value),MSG_NOSIGNAL)==sizeof(value));
}
static int control_receive(int fd) {
    int value; ssize_t n;
    /* EVENT_WAIT: exact fixture peer acknowledgement; alarm fails a stall. */
    do { n=recv(fd,&value,sizeof(value),0); } while (n<0 && errno==EINTR);
    assert(n==sizeof(value)); return value;
}
static void sender_stopped(pid_t child) {
    int status; pid_t result;
    /* EVENT_WAIT: sender stops after a committed send; alarm bounds failure. */
    do { result=waitpid(child,&status,WUNTRACED); } while (result<0 && errno==EINTR);
    assert(result==child && WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
}
static void export_owner(int control, int endpoint, const char *path, int cancel, int full) {
    int source,pipefd[2]={-1,-1};
    if (cancel) {
        assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK)); source=pipefd[1];
    } else {
        source=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(source>=0);
        assert(lseek(source,17,SEEK_SET)==17 && !flock(source,LOCK_EX|LOCK_NB));
    }
    void *stack=malloc(1024*1024); assert(stack);
    int filled[64],count=0;
    if (full) {
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_max>=64);
        limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int next;
        while ((next=fcntl(control,F_DUPFD_CLOEXEC,0))>=0) {
            assert(count<64); filled[count++]=next;
        }
        assert(errno==EMFILE && count);
    }
    unsigned before=count_low_fds();
    struct export_sender sender={getpid(),endpoint,source,cancel ? 1 : 32,cancel==3};
    pid_t child=clone(protected_exporter,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,&sender);
    assert(child>0);
    control_send(control,child);
    for (int i=0;i<sender.rounds;++i) {
        if (cancel==3) {
            int status=wait_child(child); assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
            assert(count_low_fds()==before && fcntl(endpoint,F_GETFD)>=0);
            assert(!close(source)); char byte; assert(read(pipefd[0],&byte,1)==0);
            assert(!close(pipefd[0])); control_send(control,300);
            assert(control_receive(control)==300); break;
        }
        sender_stopped(child);
        assert(count_low_fds()==before);
        control_send(control,i);
        assert(control_receive(control)==i);
        if (cancel) {
            assert(!kill(child,SIGKILL));
            int status=wait_child(child); assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            assert(fcntl(endpoint,F_GETFD)>=0 && count_low_fds()==before);
            assert(!close(source));
            char byte;
            assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
            control_send(control,100);
            assert(control_receive(control)==100);
            assert(read(pipefd[0],&byte,1)==0);
            assert(!close(pipefd[0]));
        } else {
            assert(lseek(source,0,SEEK_CUR)==18+i);
            assert(!kill(child,SIGCONT));
        }
    }
    if (!cancel) {
        int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
        assert(count_low_fds()==before && !close(source) && !unlink(path));
    }
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    free(stack);
    /* The fixture owner, not the dead sender, owns the infrastructure slot. */
    assert(!syscall(SYS_close_range,endpoint,endpoint,0));
    int replacement=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(replacement>=0);
    if (replacement!=endpoint) { assert(dup3(replacement,endpoint,O_CLOEXEC)==endpoint); assert(!close(replacement)); }
    control_send(control,200);
    assert(control_receive(control)==200);
    struct stat st; assert(!fstat(endpoint,&st) && S_ISCHR(st.st_mode));
    assert(!close(endpoint)); _exit(0);
}
static void owned_export(const char *directory, int cancel, int full) {
    unsigned before=count_low_fds();
    int channel[2],control[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,channel));
    int passcred=1; assert(!setsockopt(channel[0],SOL_SOCKET,SO_PASSCRED,&passcred,sizeof(passcred)));
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,control));
    char path[4096]; snprintf(path,sizeof(path),"%s/export-file",directory);
    pid_t parent=getpid(),owner=fork(); assert(owner>=0);
    if (!owner) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL)); if (getppid()!=parent) _exit(99);
        assert(!close(channel[0]) && !close(control[0]));
        export_owner(control[1],channel[1],path,cancel,full);
    }
    assert(!close(channel[1]) && !close(control[1]));
    pid_t sender=control_receive(control[0]); assert(sender>0 && sender!=owner && sender!=getpid());
    int retained=-1;
    if (cancel==3) {
        assert(control_receive(control[0])==300);
        char byte; assert(recv(channel[0],&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
        control_send(control[0],300);
    }
    for (int i=0;i<(cancel==3 ? 0 : cancel ? 1 : 32);++i) {
        assert(control_receive(control[0])==i);
        if (cancel!=1) {
            retained=import_fd_from(channel[0],0,sender);
            if (!cancel) {
                assert(lseek(retained,0,SEEK_CUR)==17+i && lseek(retained,18+i,SEEK_SET)==18+i);
                int other=open(path,O_RDWR|O_CLOEXEC); assert(other>=0);
                assert(flock(other,LOCK_EX|LOCK_NB)==-1 && (errno==EAGAIN || errno==EWOULDBLOCK));
                assert(!close(other) && !close(retained)); retained=-1;
            }
        }
        control_send(control[0],i);
    }
    if (cancel==1 || cancel==2) {
        assert(control_receive(control[0])==100);
        if (cancel==1) import_fd(channel[0],1);
        else assert(retained>=0 && !close(retained));
        control_send(control[0],100);
    }
    assert(control_receive(control[0])==200);
    char byte; assert(recv(channel[0],&byte,1,MSG_DONTWAIT)==0);
    assert(!close(channel[0])); control_send(control[0],200);
    int status=wait_child(owner); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(control[0]) && count_low_fds()==before);
    printf("PASS table-owned export protected=1 cancel=%d full=%d: exact OFD, no per-call FD growth, host-owned cancellation, kernel sender credentials\n",cancel,full);
}
static int admission_listener(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd>=0); return fd;
}
static void readable(int fd) {
    struct pollfd event={.fd=fd,.events=POLLIN};
    /* EVENT_WAIT: exact fixture message; timeout fails, never implies arrival. */
    int n; do { n=poll(&event,1,5000); } while (n<0 && errno==EINTR);
    assert(n==1 && (event.revents&POLLIN));
}
static void late_export(const char *directory, int slots) {
    unsigned before=count_low_fds();
    int control[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,control));
    char path[4096]; snprintf(path,sizeof(path),"%s/late-export",directory);
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL)); if (getppid()!=parent) _exit(99);
        assert(!close(control[0]));
        int source=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(source>=0);
        assert(lseek(source,47,SEEK_SET)==47);
        int notify=admission_listener(); export_fd(control[1],notify); assert(!close(notify));
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_max>=64);
        limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int last=-1,next;
        while ((next=fcntl(control[1],F_DUPFD_CLOEXEC,0))>=0) last=next;
        assert(errno==EMFILE && last>=0); if (slots) assert(!close(last));
        assert(!prctl(PR_SET_DUMPABLE,0));
        filter(SYS_socket,SECCOMP_RET_ERRNO|EPERM);
        filter(SYS_connect,SECCOMP_RET_ERRNO|EPERM);
        filter(SYS_close,SECCOMP_RET_ERRNO|EPERM);
        filter(SYS_clone,SECCOMP_RET_ERRNO|EPERM);
        control_send(control[1],source);
        long fd=syscall(SYS_getppid);
        if (!slots) {
            assert(fd==-1 && errno==EMFILE && count_low_fds()==64);
        } else {
            assert(fd>=0 && count_low_fds()==64 && (fcntl(fd,F_GETFD)&FD_CLOEXEC));
            reject_forged_peer(fd,parent);
            export_fd(fd,source); control_send(control[1],100);
            assert(control_receive(control[1])==100);
            assert(lseek(source,0,SEEK_CUR)==59);
            assert(close(fd)==-1 && errno==EPERM);
        }
        _exit(0);
    }
    assert(!close(control[1])); readable(control[0]);
    int notify=import_fd(control[0],0);
    int source=control_receive(control[0]);
    int process=syscall(SYS_pidfd_open,child,0); assert(process>=0);
    assert(syscall(SYS_pidfd_getfd,process,source,0)==-1 && errno==EPERM);
    assert(!close(process));
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,channel));
    int passcred=1; assert(!setsockopt(channel[0],SOL_SOCKET,SO_PASSCRED,&passcred,sizeof(passcred)));
    struct seccomp_notif request={0};
    /* EVENT_WAIT: protected caller's admission gate; fixture alarm bounds failure. */
    assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_RECV,&request) && request.pid==(unsigned)child
        && request.data.nr==SYS_getppid);
    struct seccomp_notif_addfd add={.id=request.id,.srcfd=channel[1],
        .flags=SECCOMP_ADDFD_FLAG_SEND,.newfd_flags=O_CLOEXEC};
    int installed=ioctl(notify,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
    if (!slots) {
        assert(installed==-1 && errno==EMFILE);
        struct seccomp_notif_resp reply={.id=request.id,.error=-EMFILE};
        assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_SEND,&reply));
    } else {
        assert(installed>=0 && control_receive(control[0])==100);
        int received=import_fd_from(channel[0],0,child);
        assert(lseek(received,0,SEEK_CUR)==47 && lseek(received,59,SEEK_SET)==59);
        assert(!close(received)); control_send(control[0],100);
    }
    assert(!close(channel[1]));
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    char byte; assert(recv(channel[0],&byte,1,MSG_DONTWAIT)==0);
    assert(!close(channel[0]) && !close(notify) && !close(control[0]) && !unlink(path));
    assert(count_low_fds()==before);
    printf("PASS late table-owned export protected=1 slots=%d: pidfd_getfd denied, ADDFD admission respects limit, exact OFD\n",slots);
}
struct scope_input { pid_t parent; int endpoint, mode; };
static int scope_child(void *argument) {
    const struct scope_input *input=argument;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL)); if (getppid()!=input->parent) _exit(99);
    if (input->mode==0) assert(!close(input->endpoint));
    else if (input->mode==1)
        assert(!syscall(SYS_close_range,input->endpoint,input->endpoint,CLOSE_RANGE_UNSHARE));
    else {
        char number[24]; snprintf(number,sizeof(number),"%d",input->endpoint);
        execl("/proc/self/exe","fd-lifecycle","--closed-on-exec",number,(char *)NULL);
        _exit(98);
    }
    assert(fcntl(input->endpoint,F_GETFD)==-1 && errno==EBADF); _exit(0);
}
static void export_scope(int mode) {
    unsigned before=count_low_fds();
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    struct scope_input input={getpid(),channel[1],mode};
    void *stack=malloc(1024*1024); assert(stack);
    pid_t child=clone(scope_child,(char *)stack+1024*1024,
        SIGCHLD|(mode ? CLONE_FILES : 0),&input); assert(child>0);
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(fcntl(channel[1],F_GETFD)&FD_CLOEXEC);
    control_send(channel[1],42); assert(control_receive(channel[0])==42);
    assert(!close(channel[1])); char byte; assert(recv(channel[0],&byte,1,MSG_DONTWAIT)==0);
    assert(!close(channel[0]) && count_low_fds()==before); free(stack);
    printf("PASS export ownership scope=%s: kernel split/close preserves surviving table\n",
        mode==0 ? "fork" : mode==1 ? "close_range-unshare" : "shared-table-exec");
}
static void export_quota(void) {
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL)); if (getppid()!=parent) _exit(99);
        unsigned before=count_low_fds();
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_max>=64);
        limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        int channel[2],pipefd[2];
        assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,channel));
        assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        int capacity=131072; assert(!setsockopt(channel[1],SOL_SOCKET,SO_SNDBUF,&capacity,sizeof(capacity)));
        unsigned sent=0;
        while (sent<256 && export_attempt(channel[1],pipefd[1])==1) ++sent;
        assert(sent>0 && sent<256 && errno==ETOOMANYREFS);
        assert(!close(pipefd[1])); char byte;
        assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
        for (unsigned i=0;i<sent;++i) import_fd(channel[0],1);
        assert(read(pipefd[0],&byte,1)==0);
        assert(!close(pipefd[0]) && !close(channel[0]) && !close(channel[1]));
        assert(count_low_fds()==before);
        printf("PASS export quota: native ETOOMANYREFS after %u rights, cancelled queue releases EOF\n",sent);
        _exit(0);
    }
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
struct owned_receipt { int fd, result, flags, number, channel, kill_receive; };
static int protected_receiver(void *argument) {
    struct owned_receipt *r=argument;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && !prctl(PR_SET_DUMPABLE,0));
    filter(SYS_socket,SECCOMP_RET_ERRNO|EPERM);
    filter(SYS_connect,SECCOMP_RET_ERRNO|EPERM);
    filter(SYS_close,SECCOMP_RET_ERRNO|EPERM);
    filter(SYS_clone,SECCOMP_RET_ERRNO|EPERM);
    if (r->kill_receive) filter(SYS_recvmsg,SECCOMP_RET_KILL_THREAD);
    raise(SIGSTOP);
    char byte; struct iovec vector={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control)};
    r->result=(int)recvmsg(r->channel,&message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT);
    r->flags=message.msg_flags;
    struct cmsghdr *h=CMSG_FIRSTHDR(&message);
    if (h) {
        assert(h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS && h->cmsg_len==CMSG_LEN(sizeof(int)));
        memcpy(&r->fd,CMSG_DATA(h),sizeof(r->fd)); r->number=1;
    }
    raise(SIGSTOP); _exit(0);
}
static void owned_receive_case(int cancel, int slots) {
    unsigned before=count_low_fds();
    struct rlimit saved,limit; assert(!getrlimit(RLIMIT_NOFILE,&saved));
    limit=saved; limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int channel[2],pipefd[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,channel));
    assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    struct owned_receipt *receipt=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(receipt!=MAP_FAILED);
    *receipt=(struct owned_receipt){.fd=-1,.channel=channel[1],.kill_receive=cancel==1};
    void *stack=malloc(1024*1024); assert(stack);
    pid_t child=clone(protected_receiver,(char *)stack+1024*1024,CLONE_FILES|SIGCHLD,receipt);
    assert(child>0); sender_stopped(child);
    export_fd(channel[0],pipefd[1]); assert(!close(pipefd[1]));
    int filled[64],count=0,fd;
    while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) { assert(count<64); filled[count++]=fd; }
    assert(errno==EMFILE && count);
    if (slots) assert(!close(filled[--count]));
    unsigned admitted=count_low_fds(); char byte;
    assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
    assert(!kill(child,SIGCONT));
    if (cancel==1) {
        int status=wait_child(child); assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        assert(!receipt->number && count_low_fds()==admitted);
        assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
        /* The supervisor retains this same receiving endpoint, not merely its
         * peer. Discard needs no allocation in the full shared guest table. */
        import_fd(channel[1],1);
    } else {
        sender_stopped(child);
        assert(receipt->result==1 && receipt->number==slots);
        assert(!!(receipt->flags&MSG_CTRUNC)==!slots);
        assert(count_low_fds()==admitted+(unsigned)slots);
        if (cancel==2) assert(!kill(child,SIGKILL));
        else assert(!kill(child,SIGCONT));
        int status=wait_child(child);
        assert(cancel==2 ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL
            : WIFEXITED(status) && !WEXITSTATUS(status));
        assert(recv(channel[1],&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
        if (slots) {
            assert(fcntl(receipt->fd,F_GETFD)==FD_CLOEXEC);
            assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
            /* A published descriptor belongs to the surviving table, not to
             * transport cancellation. Only its application owner closes it. */
            assert(!close(receipt->fd));
        }
    }
    assert(read(pipefd[0],&byte,1)==0 && count_low_fds()==admitted);
    for (int i=0;i<count;++i) assert(!close(filled[i]));
    assert(!close(channel[0]) && !close(channel[1]) && !close(pipefd[0]));
    assert(!munmap(receipt,4096) && !setrlimit(RLIMIT_NOFILE,&saved)); free(stack);
    assert(count_low_fds()==before);
    printf("PASS table-owned receive cancel=%d slots=%d: retained receive queue, exact published ownership, truncation and pipe EOF\n",cancel,slots);
}
static void owned_receive(int cancel, int slots) {
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        owned_receive_case(cancel,slots); _exit(0);
    }
    int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
}
int main(int argc, char **argv) {
    if (argc==2 && !strcmp(argv[1],"--epoll-identity")) {
        setbuf(stdout,NULL); alarm(30); epoll_weak_identity(native_kcmp_available()); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--closed-on-exec")) {
        int fd=atoi(argv[2]); assert(fd>=3 && fcntl(fd,F_GETFD)==-1 && errno==EBADF);
        return 0;
    }
    assert(argc==2 && !mkdir(argv[1],0700));
    setbuf(stdout,NULL); alarm(30);
    for (int protected=0;protected<2;++protected) {
        terminal(protected,LIVE,0);
        terminal(protected,LIVE,1);
        for (int phase=EXIT_NORMAL;phase<=EXIT_KILL;++phase) terminal(protected,phase,0);
        for (int kind=REGULAR;kind<=SYMLINK;++kind) {
            path_transfer(argv[1],kind,protected,0,-1);
            path_transfer(argv[1],kind,protected,0,0);
            path_transfer(argv[1],kind,protected,0,1);
            path_transfer(argv[1],kind,protected,1,-1);
            path_bridge(argv[1],kind,protected,0);
            path_bridge(argv[1],kind,protected,1);
        }
    }
    reopen_description(argv[1]);
    for (int protected=0;protected<2;++protected)
        for (int deny_clone=0;deny_clone<2;++deny_clone)
            for (int deny_close=0;deny_close<2;++deny_close)
                helper_boundary(protected,deny_clone,deny_close);
    socket_peer_boundary();
    int compare=native_kcmp_available();
    description_identity(argv[1],compare);
    epoll_weak_identity(compare);
    const int socket_types[]={SOCK_STREAM,SOCK_DGRAM,SOCK_SEQPACKET};
    for (unsigned i=0;i<sizeof(socket_types)/sizeof(*socket_types);++i) queue_retirement(socket_types[i]);
    for (int cancel=0;cancel<4;++cancel)
        for (int full=0;full<2;++full) owned_export(argv[1],cancel,full);
    late_export(argv[1],0);
    late_export(argv[1],1);
    for (int mode=0;mode<3;++mode) export_scope(mode);
    export_quota();
    for (int cancel=0;cancel<3;++cancel)
        for (int slots=0;slots<2;++slots) owned_receive(cancel,slots);
    puts("LIMIT table-owned receive: native controls only; production association and receipt coverage belongs to fd-handoff/fs-return");
    assert(!rmdir(argv[1]));
    puts("PASS native private-FD lifecycle experiment");
    return 0;
}
