#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fd_origins.h"
#include "fd_exchange.h"
#include "fd_delivery.h"
#include "fd_return.h"
#include "fd_install.h"
#include "fd_channel.h"
#include "fd_receipt.h"
#include "ipc_credentials.h"
#include "fd_messages.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/io_uring.h>
#include <linux/netlink.h>
#include <linux/seccomp.h>
#include <linux/sock_diag.h>
#include <linux/unix_diag.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/epoll.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

enum { LISTENER, REPLACE, CHECK, ACK, DONE, STOP, INSTALLED, CONTINUE };
enum scenario { SPLIT, ATOMIC, CANCEL_SPLIT, CANCEL_ATOMIC, PATH_FD, RIGHTS,
    ORDERED_ATOMIC, ORDERED_RIGHTS };
struct message { int kind, number, error; long position; };
struct fixture { int control, data, source, replacement, target; enum scenario scenario; };

static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned count=0; while (readdir(dir)) ++count;
    closedir(dir); return count;
}
static ssize_t send_packet(int socket, struct message value, int fd) {
    struct iovec vector={&value,sizeof(value)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1};
    if (fd>=0) {
        message.msg_control=control.bytes; message.msg_controllen=sizeof(control);
        struct cmsghdr *c=CMSG_FIRSTHDR(&message);
        c->cmsg_level=SOL_SOCKET; c->cmsg_type=SCM_RIGHTS; c->cmsg_len=CMSG_LEN(sizeof(fd));
        memcpy(CMSG_DATA(c),&fd,sizeof(fd));
    }
    /* EVENT_WAIT: bounded fixture channel; alarm/parent death ends a stuck peer. */
    ssize_t n; do { n=sendmsg(socket,&message,MSG_NOSIGNAL); } while (n<0 && errno==EINTR);
    return n;
}
static void send_message(int socket, struct message value, int fd) {
    ssize_t n=send_packet(socket,value,fd);
    if (n!=sizeof(value)) fprintf(stderr,"send_message kind=%d fd=%d result=%zd errno=%d\n",value.kind,fd,n,errno);
    assert(n==sizeof(value));
}
static struct message receive_message(int socket, int *fd) {
    struct message value;
    struct iovec vector={&value,sizeof(value)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control)};
    /* EVENT_WAIT: explicit peer acknowledgement, not a settling interval. */
    ssize_t n; do { n=recvmsg(socket,&message,MSG_CMSG_CLOEXEC); } while (n<0 && errno==EINTR);
    assert(n==sizeof(value) && !(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
    struct cmsghdr *c=CMSG_FIRSTHDR(&message);
    if (fd) *fd=-1;
    if (c) {
        assert(fd && c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS
            && c->cmsg_len==CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&message,c));
        memcpy(fd,CMSG_DATA(c),sizeof(*fd));
    }
    return value;
}
static void signal_handler(int signal) { (void)signal; }
static int ordered(enum scenario scenario) { return scenario==ORDERED_ATOMIC || scenario==ORDERED_RIGHTS; }
static int listener(int order) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_dup3,0,1),
        BPF_STMT(BPF_RET|BPF_K,order ? SECCOMP_RET_USER_NOTIF : SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int fd=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd>=0); return fd;
}
static void replace(const struct fixture *f, int fd) {
    assert(lseek(fd,0,SEEK_CUR)==11);
    struct stat before, after;
    assert(!fstat(fd,&before));
    assert(dup3(f->replacement,fd,O_CLOEXEC)==fd);
    assert(!fstat(fd,&after));
    /* An inode consistency check cannot distinguish these open descriptions. */
    assert(before.st_dev==after.st_dev && before.st_ino==after.st_ino);
    assert(lseek(fd,0,SEEK_CUR)==29);
}
static void *requester(void *context) {
    struct fixture *f=context;
    if (f->scenario==RIGHTS || f->scenario==ORDERED_RIGHTS) {
        int fd=-1;
        struct message value=receive_message(f->data,&fd);
        assert(value.kind==INSTALLED && fd>=0 && lseek(fd,0,SEEK_CUR)==11);
        send_message(f->control,(struct message){.kind=INSTALLED,.number=fd},-1);
        value=receive_message(f->data,NULL);
        if (value.kind==CHECK) {
            assert(lseek(fd,0,SEEK_CUR)==value.position);
            send_message(f->control,(struct message){.kind=ACK},-1);
            value=receive_message(f->data,NULL);
        }
        assert(value.kind==CONTINUE);
        assert(lseek(fd,0,SEEK_CUR)==29);
        close(fd);
        send_message(f->control,(struct message){.kind=DONE},-1);
        return NULL;
    }
    if (!ordered(f->scenario)) {
        int fd=listener(0);
        send_message(f->control,(struct message){.kind=LISTENER},fd);
        close(fd);
    }
    errno=0;
    long result=syscall(SYS_getppid);
    int error=result<0 ? errno : 0;
    if (f->scenario==SPLIT || f->scenario==ATOMIC || f->scenario==ORDERED_ATOMIC) {
        assert(result==f->target);
        if (f->scenario==ATOMIC || f->scenario==ORDERED_ATOMIC) replace(f,f->target);
        assert(lseek(f->target,0,SEEK_CUR)==29);
    } else {
        assert(result==-1 && error==(f->scenario==PATH_FD ? ENOTSUP : EINTR));
        assert(lseek(f->target,0,SEEK_CUR)==(f->scenario==CANCEL_SPLIT ? 11 : 47));
    }
    send_message(f->control,(struct message){.kind=DONE,.number=(int)result,.error=error,
        .position=lseek(f->target,0,SEEK_CUR)},-1);
    return NULL;
}
static void child(struct fixture *f, pid_t parent) {
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=parent) _exit(1);
    alarm(20);
    struct sigaction action={.sa_handler=signal_handler}; sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGUSR1,&action,NULL));
    if (ordered(f->scenario)) {
        /* Install before creating peers so either peer's dup3 reaches the gate. */
        int fd=listener(1);
        send_message(f->control,(struct message){.kind=LISTENER},fd); close(fd);
    }
    pthread_t task; assert(!pthread_create(&task,NULL,requester,f));
    for (;;) {
        struct message value=receive_message(f->control,NULL);
        if (value.kind==STOP) break;
        if (value.kind==REPLACE) replace(f,value.number);
        else assert(value.kind==CHECK && lseek(value.number,0,SEEK_CUR)==value.position);
        send_message(f->control,(struct message){.kind=ACK},-1);
    }
    /* EVENT_WAIT: requester completion; process alarm is a failure bound. */
    assert(!pthread_join(task,NULL));
    _exit(0);
}
static void peer_command(int control, int kind, int fd, long position) {
    send_message(control,(struct message){.kind=kind,.number=fd,.position=position},-1);
    struct message value=receive_message(control,NULL); assert(value.kind==ACK);
}
static void completed(int control) {
    struct message value=receive_message(control,NULL); assert(value.kind==DONE);
}
static int create_file(const char *path, long position) {
    int fd=open(path,O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC,0600); assert(fd>=0);
    assert(lseek(fd,position,SEEK_SET)==position); return fd;
}
static struct md_fs_anchor *capture(int fd, struct md_mount_namespace *view) {
    struct md_filesystem fs={.mounts=view};
    struct md_fs_anchor *origin=NULL;
    struct md_fs_result result={.fd=fd};
    struct md_fs_output output={.origin=&origin};
    md_view_capture(&fs,view,NULL,&result,&output);
    assert(!result.error && origin); return origin;
}
static void origin_at(struct md_fd_origins *table, int fd, struct md_fs_anchor *expected) {
    unsigned flags;
    struct md_fs_anchor *actual=md_fd_origins_get(table,fd,&flags);
    assert(actual==expected && flags==(expected ? FD_CLOEXEC : 0)); md_fs_anchor_release(actual);
}
static void ordered_handoff(const struct fixture *f, int control, int data) {
    int notify=-1;
    struct message message=receive_message(control,&notify);
    assert(message.kind==LISTENER && notify>=0);
    struct md_mount_namespace *views[]={md_mount_namespace_new(),md_mount_namespace_new()};
    assert(views[0] && views[1]);
    struct md_fs_anchor *source=capture(f->source,views[0]), *replacement=capture(f->replacement,views[1]);
    struct md_fd_origins *files=md_fd_origins_new(), *other=md_fd_origins_new(); assert(files && other);
    struct md_fd_order install={0}, mutation={0}, independent={0};
    struct md_fd_origin_update update;
    assert(!md_fd_origins_prepare(files,source,FD_CLOEXEC,&update));
    assert(md_fd_order_begin(files,&install,NULL)==1 && !md_fd_order_start(&install));
    int target=f->target;
    if (f->scenario==ORDERED_RIGHTS) {
        send_message(data,(struct message){.kind=INSTALLED},f->source);
        message=receive_message(control,NULL); assert(message.kind==INSTALLED);
        target=message.number;
        send_message(control,(struct message){.kind=REPLACE,.number=target},-1);
    } else {
        struct seccomp_notif q={0};
        /* EVENT_WAIT: exact publication request; fixture alarms bound failure. */
        assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_RECV,&q) && q.data.nr==SYS_getppid);
        struct seccomp_notif_addfd add={.id=q.id,.srcfd=f->source,.newfd=target,
            .flags=SECCOMP_ADDFD_FLAG_SETFD|SECCOMP_ADDFD_FLAG_SEND,.newfd_flags=O_CLOEXEC};
        assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==target);
    }
    struct seccomp_notif q={0};
    /* EVENT_WAIT: the competing mutation reaches its kernel admission stop;
     * no timing assumption about when the receiver begins using the FD. */
    assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_RECV,&q) && q.data.nr==SYS_dup3);
    assert((int)q.data.args[0]==f->replacement && (int)q.data.args[1]==target);
    assert(md_fd_order_begin(files,&mutation,NULL)==0 && md_fd_order_start(&mutation)==-EINVAL);
    if (f->scenario==ORDERED_RIGHTS) {
        send_message(data,(struct message){.kind=CHECK,.position=11},-1);
        message=receive_message(control,NULL); assert(message.kind==ACK);
    } else peer_command(control,CHECK,target,11);
    assert(md_fd_order_begin(other,&independent,NULL)==1 && !md_fd_order_start(&independent));
    assert(!md_fd_order_complete(&independent));
    md_fd_origins_finish(&update,target);
    assert(!md_fd_order_complete(&install) && md_fd_order_state(&mutation)==MD_RESOURCE_ORDER_READY);
    origin_at(files,target,source);
    assert(!md_fd_order_start(&mutation));
    assert(!md_fd_origins_prepare(files,replacement,FD_CLOEXEC,&update));
    struct seccomp_notif_resp reply={.id=q.id,.flags=SECCOMP_USER_NOTIF_FLAG_CONTINUE};
    assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_SEND,&reply));
    if (f->scenario==ORDERED_RIGHTS) {
        message=receive_message(control,NULL); assert(message.kind==ACK);
    } else completed(control);
    /* This fixture acknowledgement follows the actual dup3 result. Production
     * integration needs its syscall-exit event, not notification SEND success. */
    md_fd_origins_finish(&update,target);
    assert(!md_fd_order_complete(&mutation));
    origin_at(files,target,replacement);
    if (f->scenario==ORDERED_RIGHTS) {
        send_message(data,(struct message){.kind=CONTINUE},-1); completed(control);
        assert(!md_fd_origins_close_range(files,(unsigned)target,(unsigned)target,0));
        assert(!md_fd_origins_get(files,target,NULL));
    }
    md_fd_origins_release(files); md_fd_origins_release(other);
    md_fs_anchor_release(source); md_fs_anchor_release(replacement);
    md_mount_namespace_release(views[0]); md_mount_namespace_release(views[1]);
    close(notify);
}
static void scenario(enum scenario scenario, const char *directory) {
    unsigned before=descriptors();
    int control[2], data[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,control));
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,data));
    char source_path[PATH_MAX], initial_path[PATH_MAX];
    assert(snprintf(source_path,sizeof(source_path),"%s/source",directory)<(int)sizeof(source_path));
    assert(snprintf(initial_path,sizeof(initial_path),"%s/initial",directory)<(int)sizeof(initial_path));
    int source=create_file(source_path,11), initial=create_file(initial_path,47);
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",source);
    int replacement=open(source_path,O_RDWR|O_CLOEXEC); assert(replacement>=0);
    assert(!unlink(source_path) && !unlink(initial_path));
    assert(lseek(replacement,29,SEEK_SET)==29);
    int target=fcntl(initial,F_DUPFD_CLOEXEC,64); assert(target>=64);
    struct fixture f={control[1],data[1],source,replacement,target,scenario};
    pid_t parent=getpid(), pid=fork(); assert(pid>=0);
    if (!pid) { close(control[0]); close(data[0]); child(&f,parent); }
    close(control[1]); close(data[1]);
    if (ordered(scenario)) ordered_handoff(&f,control[0],data[0]);
    else if (scenario==RIGHTS) {
        send_message(data[0],(struct message){.kind=INSTALLED},source);
        struct message value=receive_message(control[0],NULL); assert(value.kind==INSTALLED);
        peer_command(control[0],REPLACE,value.number,0);
        send_message(data[0],(struct message){.kind=CONTINUE},-1);
        completed(control[0]);
    } else {
        int notify=-1;
        struct message value=receive_message(control[0],&notify);
        assert(value.kind==LISTENER && notify>=0);
        struct seccomp_notif request={0};
        /* EVENT_WAIT: the exact kernel notification; alarm terminates a stalled case. */
        assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_RECV,&request));
        assert(request.data.nr==SYS_getppid);
        struct seccomp_notif_addfd add={.id=request.id,.srcfd=source,.newfd=target,
            .flags=SECCOMP_ADDFD_FLAG_SETFD,.newfd_flags=O_CLOEXEC};
        struct seccomp_notif_resp reply={.id=request.id,.val=target};
        if (scenario==SPLIT || scenario==CANCEL_SPLIT) {
            assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==target);
            assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_ID_VALID,&request.id));
        }
        if (scenario==SPLIT) {
            peer_command(control[0],REPLACE,target,0);
            assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_SEND,&reply)); completed(control[0]);
        } else if (scenario==ATOMIC) {
            add.flags|=SECCOMP_ADDFD_FLAG_SEND;
            assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==target);
            /* The recipient may replace the FD before any later origin publication. */
            completed(control[0]);
        } else if (scenario==PATH_FD) {
            int pinned=open(path,O_PATH|O_CLOEXEC); assert(pinned>=0);
            add.srcfd=pinned;
            assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==-1 && errno==EBADF);
            close(pinned); reply.val=0; reply.error=-ENOTSUP;
            assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_SEND,&reply)); completed(control[0]);
        } else {
            assert(!syscall(SYS_tgkill,pid,request.pid,SIGUSR1));
            completed(control[0]);
            assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_ID_VALID,&request.id)==-1 && errno==ENOENT);
            if (scenario==CANCEL_ATOMIC) {
                add.flags|=SECCOMP_ADDFD_FLAG_SEND;
                assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==-1 && errno==ENOENT);
            } else assert(ioctl(notify,SECCOMP_IOCTL_NOTIF_SEND,&reply)==-1 && errno==ENOENT);
            peer_command(control[0],CHECK,target,scenario==CANCEL_SPLIT ? 11 : 47);
        }
        close(notify);
    }
    send_message(control[0],(struct message){.kind=STOP},-1);
    int status;
    /* EVENT_WAIT: exact fixture child exit, bounded by parent/child alarms. */
    assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
    close(control[0]); close(data[0]); close(source); close(replacement); close(initial); close(target);
    assert(descriptors()==before);
}
static void limit_free_descriptors(unsigned available) {
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    /* Only the isolated receiver changes its limit. Existing high descriptors
     * remain usable; exactly `available` empty slots are below the new bound. */
    for (rlim_t fd=0;fd<limit.rlim_cur && fd<=INT_MAX;++fd) {
        if (fcntl((int)fd,F_GETFD)>=0) continue;
        assert(errno==EBADF);
        if (available) { --available; continue; }
        limit.rlim_cur=fd; assert(!setrlimit(RLIMIT_NOFILE,&limit)); return;
    }
    assert(0 && "fixture cannot establish requested descriptor quota");
}
struct receipt_child {
    int socket, receipt, truncated, flags, limited;
    pid_t parent;
    unsigned capacity, available;
};
static int receive_and_exit(void *opaque) {
    struct receipt_child *f=opaque;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=f->parent) _exit(1);
    struct md_fd_receipt *receipt=mmap(NULL,md_fd_receipt_size(f->capacity),PROT_READ|PROT_WRITE,MAP_SHARED,f->receipt,0);
    assert(receipt!=MAP_FAILED);
    assert(!md_fd_receipt_reset(receipt,f->capacity));
    if (f->truncated) receipt->message.msg_controllen=CMSG_LEN(sizeof(int));
    if (f->limited) limit_free_descriptors(f->available);
    syscall(SYS_recvmsg,f->socket,&receipt->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT|f->flags);
    /* No publication/FD-number acknowledgement from the receiver. */
    _exit(0);
}
static void retained_receipt(const char *directory, unsigned count, int truncated) {
    unsigned before=descriptors();
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/receipt",directory);
    int source=create_file(path,11); assert(!unlink(path));
    struct md_mount_namespace *view=md_mount_namespace_new(); assert(view);
    struct md_fs_anchor *origin=capture(source,view);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_exchanges *exchanges=md_fd_exchanges_create(); assert(exchanges);
    struct md_fd_exchange *exchange=NULL;
    int bases[2]={-1,-1};
    assert(!md_fd_exchange_begin(exchanges,getpid(),files,bases,NULL,&exchange));
    assert(!md_fd_exchange_offer(exchange,origin,count ? count : 1));
    int fd=md_fd_exchange_receipt(exchange); assert(fd>=0);
    unsigned capacity=count ? count : 1;
    size_t size=md_fd_receipt_size(capacity);
    struct md_fd_receipt *receipt=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    assert(receipt!=MAP_FAILED);
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    if (count) {
        unsigned char byte=1;
        struct iovec io={&byte,sizeof(byte)};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control={0};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
            .msg_controllen=CMSG_SPACE(count*sizeof(int))};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(count*sizeof(int))};
        int sources[2]={source,source}; memcpy(CMSG_DATA(&control.align),sources,count*sizeof(int));
        assert(sendmsg(sockets[0],&message,MSG_NOSIGNAL)==1);
    }
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    size_t stack_size=65536;
    void *stack=mmap(NULL,stack_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    struct receipt_child context={.socket=sockets[1],.receipt=fd,.truncated=truncated,
        .parent=getpid(),.capacity=capacity};
    pid_t child=clone(receive_and_exit,(char *)stack+stack_size,CLONE_FILES|SIGCHLD,&context);
    assert(child>0);
    int status;
    /* EVENT_WAIT: actual receiver death; the fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(md_fd_order_cancel(&turn)==1);
    assert(!md_fd_exchange_received(exchange));
    assert(!md_fd_order_complete(&turn));
    int received[2];
    int actual=md_fd_receipt_read(receipt,capacity,received);
    assert(actual==(int)(truncated ? 1 : count));
    assert(!!(receipt->message.msg_flags&MSG_CTRUNC)==!!truncated);
    for (int i=0;i<actual;++i) {
        assert(lseek(received[i],0,SEEK_CUR)==11);
        assert(fcntl(received[i],F_GETFD)==FD_CLOEXEC);
        origin_at(files,received[i],i ? NULL : origin);
        close(received[i]);
    }
    munmap(stack,stack_size); munmap(receipt,size);
    close(sockets[0]); close(sockets[1]); close(source);
    md_fd_exchange_close(exchanges,exchange); md_fd_exchanges_destroy(exchanges);
    md_fd_origins_release(files); md_fs_anchor_release(origin); md_mount_namespace_release(view);
    assert(descriptors()==before);
}
static void origin_vector_handoff(const char *directory, unsigned count, int truncated, int terminal, int available) {
    enum { MAX_RIGHTS=253 };
    assert(count>=4 && count<=MAX_RIGHTS);
    assert(available<0 || terminal);
    unsigned before=descriptors();
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/origin-vector",directory);
    int first=create_file(path,11), second=open(path,O_RDWR|O_CLOEXEC), unknown=open(path,O_RDWR|O_CLOEXEC);
    assert(second>=0 && unknown>=0 && lseek(second,47,SEEK_SET)==47 && lseek(unknown,76,SEEK_SET)==76);
    assert(!unlink(path));
    int pipefd[2], sockets[2];
    assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC) && !socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    struct md_mount_namespace *views[]={md_mount_namespace_new(),md_mount_namespace_new()};
    assert(views[0] && views[1]);
    struct md_fs_anchor *a=capture(first,views[0]), *b=capture(second,views[1]), *p=capture(pipefd[1],views[0]);
    struct md_fd_origins *source=md_fd_origins_new(), *target=md_fd_origins_new(); assert(source && target);
    int handles[]={first,second,pipefd[1],unknown};
    struct md_fs_anchor *known[]={a,b,p,NULL};
    struct md_fd_object *objects[]={md_fs_anchor_object(a),md_fs_anchor_object(b),md_fs_anchor_object(p),NULL};
    struct md_fd_origin_update initial[4];
    assert(!md_fd_origins_prepare_batch(source,objects,0,4,initial));
    assert(!md_fd_origins_finish_batch(initial,4,handles,4));
    int numbers[MAX_RIGHTS]; struct md_fd_object *origins[MAX_RIGHTS];
    for (unsigned i=0;i<count;++i) numbers[i]=handles[i%4];
    assert(!md_fd_origins_capture_objects(source,numbers,count,origins));
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(1);
        char byte='v'; struct iovec io={&byte,1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(MAX_RIGHTS*sizeof(int))]; } control={0};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
            .msg_controllen=CMSG_SPACE(count*sizeof(int))};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(count*sizeof(int))};
        memcpy(CMSG_DATA(&control.align),numbers,count*sizeof(int));
        assert(sendmsg(sockets[0],&message,MSG_NOSIGNAL)==1); _exit(0);
    }
    assert(!close(first) && !close(second) && !close(unknown) && !close(pipefd[1]) && !close(sockets[0]));
    md_fd_origins_release(source);
    int status;
    /* EVENT_WAIT: sender exit leaves only queued kernel references and O_PATH
     * provenance, not retained writable descriptions. The fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    char byte; assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
    size_t stack_size=65536;
    void *stack=terminal ? mmap(NULL,stack_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0) : NULL;
    assert(stack!=MAP_FAILED);
    for (unsigned pass=0;pass<4;++pass) {
        int peek=pass<2;
        struct md_fd_origin_update updates[MAX_RIGHTS];
        int received[MAX_RIGHTS];
        struct md_fd_delivery delivery={0};
        assert(!md_fd_delivery_prepare(&delivery,target,origins,FD_CLOEXEC,count,updates,received));
        struct md_fd_receipt *receipt=delivery.receipt;
        unsigned expected=pass==3 ? 0 : truncated && !peek ? 1 : count;
        if (available>=0 && expected>(unsigned)available) expected=(unsigned)available;
        struct md_fd_order turn={0}, next={0};
        assert(md_fd_order_begin(target,&turn,NULL)==1 && !md_fd_order_start(&turn));
        assert(!md_fd_order_begin(target,&next,NULL));
        if (terminal) {
            struct receipt_child context={.socket=sockets[1],.receipt=md_fd_delivery_receipt(&delivery),
                .truncated=truncated && !peek,.flags=peek ? MSG_PEEK : 0,
                .parent=getpid(),.capacity=count,.limited=available>=0,
                .available=available>=0 ? (unsigned)available : 0};
            child=clone(receive_and_exit,(char *)stack+stack_size,CLONE_FILES|SIGCHLD,&context);
            assert(child>0);
            /* EVENT_WAIT: the exact receiver exits without an ACK. Publication
             * must read retained kernel output before the next table turn. */
            assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
            assert(md_fd_order_cancel(&turn)==1);
        } else {
            assert(!md_fd_receipt_reset(receipt,count));
            if (truncated && !peek) receipt->message.msg_controllen=CMSG_LEN(sizeof(int));
            assert(recvmsg(sockets[1],&receipt->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT|(peek ? MSG_PEEK : 0))
                ==(pass==3 ? 0 : 1));
        }
        assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
        assert(md_fd_delivery_publish(&delivery)==(int)expected);
        assert(pass==3 || receipt->byte=='v');
        assert(!!(receipt->message.msg_flags&MSG_CTRUNC)==!!(pass<3 && expected<count));
        md_fd_delivery_dispose(&delivery);
        assert(!md_fd_order_complete(&turn));
        assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_READY && !md_fd_order_cancel(&next));
        for (unsigned i=0;i<expected;++i) {
            origin_at(target,received[i],known[i%4]);
            assert(fcntl(received[i],F_GETFD)==FD_CLOEXEC);
            if (i%4!=2) assert(lseek(received[i],0,SEEK_CUR)==(i%4==0 ? 11+(long)pass : i%4==1 ? 47 : 76));
        }
        if (expected) assert(lseek(received[0],1,SEEK_CUR)==12+(long)pass);
        if (expected>4) assert(lseek(received[4],0,SEEK_CUR)==12+(long)pass);
        for (unsigned i=0;i<expected;++i) {
            assert(!close(received[i]));
            assert(!md_fd_origins_close_range(target,(unsigned)received[i],(unsigned)received[i],0));
        }
        ssize_t n=read(pipefd[0],&byte,1);
        assert(peek ? n==-1 && errno==EAGAIN : n==0);
    }
    if (stack) assert(!munmap(stack,stack_size));
    for (unsigned i=0;i<count;++i) md_fd_object_release(origins[i]);
    md_fd_origins_release(target);
    md_fs_anchor_release(a); md_fs_anchor_release(b); md_fs_anchor_release(p);
    md_mount_namespace_release(views[0]); md_mount_namespace_release(views[1]);
    assert(!close(pipefd[0]) && !close(sockets[1]) && descriptors()==before);
}
static uint64_t socket_cookie(int fd) {
    uint64_t cookie=0; socklen_t size=sizeof(cookie);
    assert(!getsockopt(fd,SOL_SOCKET,SO_COOKIE,&cookie,&size) && size==sizeof(cookie) && cookie);
    return cookie;
}
static void parcel_lifetime(int fd, int ended) {
    struct pollfd p={.fd=fd,.events=POLLIN|POLLHUP};
    /* EVENT_WAIT: final parcel reference is released; timeout fails the case. */
    int result=poll(&p,1,ended ? 1000 : 0);
    assert(ended ? result==1 && (p.revents&POLLHUP) : result==0);
}
/* Candidate transport using the runtime's message-origin owner. A second queued
 * pipe writer owns liveness; socket shutdown does not imply final release. */
static int origin_message_offer(struct md_fd_messages *messages, struct md_fd_message **record,
        struct md_fd_origins *source,
        const int *fds, unsigned count, struct md_ipc_credentials *authority, int sender) {
    struct md_ipc_connection destination;
    assert(!md_ipc_credentials_connection(authority,sender,&destination));
    destination.side^=1;
    int pair[2], lifetime[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,pair)
        && !pipe2(lifetime,O_CLOEXEC|O_NONBLOCK));
    struct md_fd_order turn={0};
    assert(md_fd_order_begin(source,&turn,NULL)==1 && !md_fd_order_start(&turn));
    assert(!md_fd_message_capture(source,fds,count,record));
    union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))]; } control={0};
    control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,
        .cmsg_len=CMSG_LEN(count*sizeof(int))};
    memcpy(CMSG_DATA(&control.align),fds,count*sizeof(int));
    char byte='o'; struct iovec io={&byte,1};
    struct msghdr packet={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
        .msg_controllen=CMSG_SPACE(count*sizeof(int))};
    assert(sendmsg(pair[0],&packet,MSG_DONTWAIT|MSG_NOSIGNAL)==1);
    assert(!md_fd_order_complete(&turn));
    /* A second packet leaves all 253 slots in the first packet for payload.
     * Peeking payload never installs the lifetime writer in the recipient. */
    send_message(pair[0],(struct message){.kind=ACK},lifetime[1]);
    assert(!close(lifetime[1]) && !close(pair[0]));
    assert(!md_fd_messages_publish(messages,*record,&destination,pair[1],lifetime[0]));
    return pair[1];
}
static struct md_fd_message *origin_message_find(struct md_fd_messages *messages,
        struct md_ipc_credentials *authority, int destination, int carrier, int *error) {
    struct md_ipc_connection connection;
    *error=md_ipc_credentials_connection(authority,destination,&connection);
    struct md_fd_message *record=NULL;
    if (!*error) *error=md_fd_messages_find(messages,&connection,carrier,&record);
    return record;
}
static void origin_message_dispatch(struct md_fd_messages *messages, unsigned retired) {
    unsigned before=md_fd_messages_count(messages);
    struct pollfd event={.fd=md_fd_messages_fd(messages),.events=POLLIN};
    /* EVENT_WAIT: lifetime EOF makes the aggregate readable; expiry fails the
     * case, never authorizes retirement. Live-message assertions do not wait. */
    assert(poll(&event,1,retired ? 1000 : 0)==(retired ? 1 : 0));
    assert(md_fd_messages_dispatch(messages)==(int)retired);
    assert(md_fd_messages_count(messages)==before-retired);
}
static int origin_message_receive(int route, int peek) {
    struct message ignored;
    struct iovec io={&ignored,sizeof(ignored)};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr packet={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    assert(recvmsg(route,&packet,MSG_DONTWAIT|MSG_CMSG_CLOEXEC|(peek ? MSG_PEEK : 0))==sizeof(ignored));
    assert(!(packet.msg_flags&(MSG_CTRUNC|MSG_TRUNC)));
    struct cmsghdr *header=CMSG_FIRSTHDR(&packet);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(sizeof(int)) && !CMSG_NXTHDR(&packet,header));
    int fd; memcpy(&fd,CMSG_DATA(header),sizeof(fd)); return fd;
}
static void origin_message_publish(const struct md_fd_message *record, int carrier,
        struct md_fd_origins *target, unsigned delivered, const off_t *positions) {
    unsigned count=md_fd_message_count(record);
    struct md_fd_object *const *origins=md_fd_message_origins(record);
    struct md_fd_origin_update updates[MD_FD_RECEIPT_MAX]; int numbers[MD_FD_RECEIPT_MAX];
    struct md_fd_delivery delivery={0};
    assert(!md_fd_delivery_prepare(&delivery,target,origins,FD_CLOEXEC,count,updates,numbers));
    struct md_fd_order turn={0}, next={0};
    assert(md_fd_order_begin(target,&turn,NULL)==1 && !md_fd_order_start(&turn));
    assert(!md_fd_order_begin(target,&next,NULL));
    assert(!md_fd_receipt_reset(delivery.receipt,count));
    delivery.receipt->message.msg_controllen=CMSG_LEN(delivered*sizeof(int));
    assert(recvmsg(carrier,&delivery.receipt->message,MSG_DONTWAIT|MSG_PEEK|MSG_CMSG_CLOEXEC)==1);
    assert(delivery.receipt->byte=='o');
    assert(!!(delivery.receipt->message.msg_flags&MSG_CTRUNC)==(delivered<count));
    assert(md_fd_delivery_publish(&delivery)==(int)delivered);
    assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
    md_fd_delivery_dispose(&delivery);
    assert(!md_fd_order_complete(&turn) && !md_fd_order_cancel(&next));
    for (unsigned i=0;i<delivered;++i) {
        origin_at(target,numbers[i],md_fs_anchor_from_object(origins[i]));
        assert(fcntl(numbers[i],F_GETFD)==FD_CLOEXEC);
        if (positions) assert(lseek(numbers[i],0,SEEK_CUR)==positions[i]);
        assert(!close(numbers[i]) && !md_fd_origins_close_range(target,numbers[i],numbers[i],0));
    }
}
static void origin_message_register(struct md_ipc_credentials *authority, const int sockets[2]) {
    struct md_identity identity=md_identity_new(0,0);
    struct md_fs_request request={.operation=MD_FS_IPC,.flags=MD_IPC_PAIR,
        .directory={sockets[0],sockets[1]},.peer=getpid()};
    struct md_fs_result result;
    md_ipc_credentials_execute(authority,&identity,NULL,&request,&result,NULL);
    assert(!result.error && result.fd==-1); md_identity_release(&identity);
}
static void origin_message_association(const char *directory, struct md_ipc_credentials *authority, int type, int discard) {
    unsigned before=descriptors();
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/message-origin",directory);
    int a=create_file(path,11), b=open(path,O_RDWR|O_CLOEXEC), unknown=open(path,O_RDONLY|O_CLOEXEC);
    assert(b>=0 && unknown>=0 && lseek(b,47,SEEK_SET)==47 && !unlink(path));
    int pipefd[2], route[2], other[2];
    assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK) && !socketpair(AF_UNIX,type|SOCK_CLOEXEC,0,route)
        && !socketpair(AF_UNIX,type|SOCK_CLOEXEC,0,other));
    origin_message_register(authority,route); origin_message_register(authority,other);
    struct md_mount_namespace *views[]={md_mount_namespace_new(),md_mount_namespace_new()};
    assert(views[0] && views[1]);
    struct md_fs_anchor *known[]={capture(a,views[0]),capture(b,views[1]),capture(pipefd[1],views[0]),NULL};
    struct md_fd_object *objects[4];
    for (unsigned i=0;i<4;++i) objects[i]=md_fs_anchor_object(known[i]);
    struct md_fd_origins *source=md_fd_origins_new(), *target=md_fd_origins_new(); assert(source && target);
    int handles[]={a,b,pipefd[1],unknown};
    struct md_fd_origin_update initial[4];
    assert(!md_fd_origins_prepare_batch(source,objects,0,4,initial));
    assert(!md_fd_origins_finish_batch(initial,4,handles,4));
    struct md_fd_messages *messages=NULL;
    assert(!md_fd_messages_new(&messages));
    struct md_fd_message *records[3]={0}; int carriers[3];
    const unsigned sizes[]={4,253,8}, order[]={2,0,1};
    for (unsigned i=0;i<3;++i) {
        int inputs[MD_FD_RECEIPT_MAX];
        for (unsigned j=0;j<sizes[i];++j) inputs[j]=handles[(j+i)%4];
        carriers[i]=origin_message_offer(messages,records+i,source,inputs,sizes[i],authority,route[0]);
        for (unsigned j=0;j<sizes[i];++j) assert(md_fd_message_origins(records[i])[j]==objects[(j+i)%4]);
    }
    /* Reusing the original number must change neither the carrier's kernel
     * descriptions nor its retained mount vector. Both opens share one inode. */
    struct md_fd_origin_update replacement={0};
    assert(!md_fd_origins_prepare_copy(source,b,FD_CLOEXEC,&replacement));
    assert(dup3(b,a,O_CLOEXEC)==a); md_fd_origins_finish(&replacement,a);
    md_fd_origins_release(source);
    int error;
    assert(!origin_message_find(messages,authority,other[1],carriers[0],&error) && error==-EXDEV);
    assert(!origin_message_find(messages,authority,route[0],carriers[0],&error) && error==-EXDEV);
    assert(!origin_message_find(messages,authority,route[1],route[0],&error) && error==-ENODATA);
    assert(!origin_message_find(messages,authority,route[1],unknown,&error) && error==-ENODATA);
    assert(!origin_message_find(NULL,authority,route[1],carriers[0],&error) && error==-ENODATA);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        for (unsigned i=0;i<3;++i)
            send_message(route[0],(struct message){.kind=DONE,.number=123},carriers[order[i]]);
        _exit(0);
    }
    for (unsigned i=0;i<3;++i) assert(!close(carriers[i]));
    assert(!close(a) && !close(b) && !close(unknown) && !close(pipefd[1]) && !close(route[0]));
    int status;
    /* EVENT_WAIT: sender exit leaves only kernel queues and metadata anchors;
     * the process alarm bounds a failed handoff. No resource turn spans IO. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    for (unsigned i=0;i<3;++i) {
        unsigned index=order[i];
        if (discard) {
            struct message bytes;
            assert(read(route[1],&bytes,sizeof(bytes))==sizeof(bytes));
        } else for (unsigned pass=0;pass<3;++pass) {
            int carrier=origin_message_receive(route[1],pass<2);
            struct md_fd_message *record=origin_message_find(messages,authority,route[1],carrier,&error);
            assert(!error && record==records[index]);
            unsigned delivered=pass==1 ? 1 : md_fd_message_count(record);
            const off_t offsets[]={11,47,-1,0}; off_t positions[MD_FD_RECEIPT_MAX];
            for (unsigned j=0;j<delivered;++j) positions[j]=offsets[(j+index)%4];
            origin_message_publish(record,carrier,target,delivered,positions);
            assert(!close(carrier));
            md_fd_message_release(record);
            if (pass<2) origin_message_dispatch(messages,0);
        }
        origin_message_dispatch(messages,1);
        assert(md_fd_message_count(records[index])==sizes[index]);
        char byte; ssize_t n=read(pipefd[0],&byte,1);
        assert(i==2 ? n==0 : n==-1 && errno==EAGAIN);
        md_fd_message_release(records[index]);
    }
    md_fd_messages_free(messages);
    md_fd_origins_release(target);
    for (unsigned i=0;i<4;++i) md_fs_anchor_release(known[i]);
    md_mount_namespace_release(views[0]); md_mount_namespace_release(views[1]);
    assert(!close(route[1]) && !close(other[0]) && !close(other[1]) && !close(pipefd[0]));
    assert(descriptors()==before);
    printf("PASS message-bound origins type=%d discard=%d: cookie lookup, reordered messages, same-inode mounts, source reuse/exit, peer rejection, peek, truncation and EOF\n",type,discard);
}
static int origin_message_live(unsigned inode, uint64_t cookie) {
    int fd=socket(AF_NETLINK,SOCK_DGRAM|SOCK_CLOEXEC,NETLINK_SOCK_DIAG);
    if (fd<0) return -errno;
    struct { struct nlmsghdr header; struct unix_diag_req body; } request={
        .header={.nlmsg_len=NLMSG_LENGTH(sizeof(struct unix_diag_req)),.nlmsg_type=SOCK_DIAG_BY_FAMILY,
            .nlmsg_flags=NLM_F_REQUEST,.nlmsg_seq=1},
        .body={.sdiag_family=AF_UNIX,.udiag_states=UINT_MAX,.udiag_ino=inode,
            .udiag_cookie={(uint32_t)cookie,(uint32_t)(cookie>>32)}}};
    struct sockaddr_nl address={.nl_family=AF_NETLINK};
    int error=0;
    if (sendto(fd,&request,request.header.nlmsg_len,MSG_DONTWAIT,(void *)&address,sizeof(address))
            !=request.header.nlmsg_len) error=-errno;
    if (!error) {
        struct pollfd wait={.fd=fd,.events=POLLIN};
        /* EVENT_WAIT: exact kernel diagnostic reply, not a retry loop.
         * Timeout leaves liveness unknown and fails this observation. */
        int ready=poll(&wait,1,1000);
        if (ready<=0) error=ready<0 ? -errno : -ETIMEDOUT;
    }
    if (!error) {
        union { struct nlmsghdr align; char bytes[4096]; } response;
        struct iovec io={response.bytes,sizeof(response)};
        struct msghdr packet={.msg_name=&address,.msg_namelen=sizeof(address),.msg_iov=&io,.msg_iovlen=1};
        ssize_t size=recvmsg(fd,&packet,MSG_DONTWAIT);
        if (size<0) error=-errno;
        else if (packet.msg_flags&MSG_TRUNC || packet.msg_namelen!=sizeof(address) || address.nl_pid
                || !NLMSG_OK(&response.align,size) || response.align.nlmsg_seq!=1) error=-EPROTO;
        else if (response.align.nlmsg_type==NLMSG_ERROR) {
            if (response.align.nlmsg_len<NLMSG_LENGTH(sizeof(struct nlmsgerr))) error=-EPROTO;
            else error=((struct nlmsgerr *)NLMSG_DATA(&response.align))->error;
            if (!error) error=-EPROTO;
        } else if (response.align.nlmsg_type!=SOCK_DIAG_BY_FAMILY
                || response.align.nlmsg_len<NLMSG_LENGTH(sizeof(struct unix_diag_msg))) error=-EPROTO;
        else {
            const struct unix_diag_msg *reply=NLMSG_DATA(&response.align);
            if (reply->udiag_family!=AF_UNIX || reply->udiag_ino!=inode
                    || reply->udiag_cookie[0]!=(uint32_t)cookie
                    || reply->udiag_cookie[1]!=(uint32_t)(cookie>>32)) error=-EPROTO;
        }
    }
    assert(!close(fd)); return error;
}
static void origin_message_shutdown(void) {
    for (int how=SHUT_RD;how<=SHUT_RDWR;++how) {
        unsigned before=descriptors(); int pair[2], pipefd[2];
        assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,pair)
            && !pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
        send_message(pair[0],(struct message){.kind=DONE},pipefd[1]);
        struct stat identity; assert(!fstat(pair[1],&identity) && identity.st_ino<=UINT_MAX);
        uint64_t cookie=socket_cookie(pair[1]);
        int live=origin_message_live((unsigned)identity.st_ino,cookie);
        if (live) {
            assert(live==-EPERM || live==-EACCES || live==-EPROTONOSUPPORT || live==-EAFNOSUPPORT || live==-ENOENT);
            printf("UNAVAILABLE exact Unix socket diagnostic errno=%d\n",-live);
        } else assert(origin_message_live((unsigned)identity.st_ino,cookie^1)==-ESTALE);
        int epoll=epoll_create1(EPOLL_CLOEXEC); assert(epoll>=0);
        struct epoll_event event={.events=EPOLLIN|EPOLLHUP|EPOLLET};
        assert(!epoll_ctl(epoll,EPOLL_CTL_ADD,pair[0],&event));
        assert(!close(pipefd[1]) && !shutdown(pair[1],how));
        struct pollfd observed={.fd=pair[0],.events=POLLIN|POLLHUP};
        assert(poll(&observed,1,0)>=0);
        int pending=!!(observed.revents&POLLHUP), fd=-1;
        if (!live) assert(!origin_message_live((unsigned)identity.st_ino,cookie));
        int shutdown_event=epoll_wait(epoll,&event,1,0);
        assert(shutdown_event>=0);
        struct message payload=receive_message(pair[1],&fd);
        assert(payload.kind==DONE && fd>=0 && !close(fd));
        char byte; assert(read(pipefd[0],&byte,1)==0);
        assert(!close(pair[1]));
        /* EVENT_WAIT: final close after an already-observed shutdown. Missing
         * notification is an explicit limitation, never evidence of closure. */
        int close_event=epoll_wait(epoll,&event,1,1000);
        assert(close_event>=0);
        if (!live) assert(origin_message_live((unsigned)identity.st_ino,cookie)==-ENOENT);
        assert(!close(epoll) && !close(pair[0]) && !close(pipefd[0]) && descriptors()==before);
        printf("OBS carrier shutdown=%d hup=%d queued-rights-readable=1\n",how,pending);
        printf("OBS carrier liveness diagnostic=%d shutdown-event=%d close-event=%d\n",!live,shutdown_event,close_event);
        if (pending) puts("LIMIT carrier HUP alone is not proof of final closure: shutdown retains readable queued rights");
        if (!close_event) puts("LIMIT final carrier close has no fresh epoll event after shutdown");
    }
}
static void origin_message_custody(struct md_ipc_credentials *authority, int how, int consume) {
    unsigned before=descriptors();
    int pipefd[2], route[2];
    assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC) && !socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,route));
    origin_message_register(authority,route);
    struct md_fd_origins *source=md_fd_origins_new(), *target=md_fd_origins_new(); assert(source && target);
    struct md_fd_messages *messages=NULL; struct md_fd_message *record=NULL;
    assert(!md_fd_messages_new(&messages));
    int carrier=origin_message_offer(messages,&record,source,&pipefd[1],1,authority,route[0]);
    md_fd_origins_release(source);
    assert(!close(pipefd[1]));
    int alias=fcntl(carrier,F_DUPFD_CLOEXEC,0); assert(alias>=0);
    assert(!shutdown(carrier,how));
    origin_message_dispatch(messages,0);
    int error;
    struct md_fd_message *pin=origin_message_find(messages,authority,route[1],alias,&error);
    assert(pin==record && !error);
    origin_message_publish(pin,alias,target,1,NULL);
    if (consume) {
        char byte; struct iovec io={&byte,1};
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control;
        struct msghdr packet={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
        assert(recvmsg(alias,&packet,MSG_DONTWAIT|MSG_CMSG_CLOEXEC)==1 && byte=='o');
        struct cmsghdr *header=CMSG_FIRSTHDR(&packet);
        assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
            && header->cmsg_len==CMSG_LEN(sizeof(int)));
        int fd; memcpy(&fd,CMSG_DATA(header),sizeof(fd)); assert(!close(fd));
    }
    origin_message_dispatch(messages,0);
    char byte; ssize_t n=read(pipefd[0],&byte,1);
    assert(consume ? n==0 : n==-1 && errno==EAGAIN);
    assert(!close(carrier)); origin_message_dispatch(messages,0);
    assert(!close(alias)); origin_message_dispatch(messages,1);
    assert(read(pipefd[0],&byte,1)==0);
    md_fd_messages_free(messages); md_fd_message_release(record);
    assert(md_fd_message_count(pin)==1 && !md_fd_message_origins(pin)[0]);
    md_fd_message_release(pin); md_fd_origins_release(target);
    assert(!close(pipefd[0]) && !close(route[0]) && !close(route[1]) && descriptors()==before);
    printf("PASS pipe-owned origin custody shutdown=%d consume=%d: readable rights after shutdown, aliases, last-reference EOF and exact FD balance\n",how,consume);
}
static void origin_message_cycle(struct md_ipc_credentials *authority, int type) {
    unsigned before=descriptors();
    int route[2], pipefd[2];
    assert(!socketpair(AF_UNIX,type|SOCK_CLOEXEC,0,route) && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    origin_message_register(authority,route);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    int inputs[]={route[1],pipefd[1]};
    struct md_fd_messages *messages=NULL; struct md_fd_message *record=NULL;
    assert(!md_fd_messages_new(&messages));
    int carrier=origin_message_offer(messages,&record,files,inputs,2,authority,route[0]);
    md_fd_origins_release(files);
    send_message(route[0],(struct message){.kind=DONE},carrier);
    assert(!close(carrier) && !close(route[1]) && !close(pipefd[1]));
    struct pollfd ended={.fd=md_fd_messages_fd(messages),.events=POLLIN};
    /* EVENT_WAIT: Unix GC releases the queue-owned lifetime writer. Expiry is
     * unobserved collection, not permission to retire metadata. */
    int observed=poll(&ended,1,3000); assert(observed>=0);
    if (observed) {
        char byte;
        assert((ended.revents&POLLIN) && md_fd_messages_dispatch(messages)==1
            && !md_fd_messages_count(messages) && read(pipefd[0],&byte,1)==0);
    }
    md_fd_messages_free(messages); md_fd_message_release(record);
    assert(!close(route[0]) && !close(pipefd[0]) && descriptors()==before);
    printf("%s pipe-owned origin cycle type=%d: kernel GC releases data and metadata lifetime together\n",
        observed ? "PASS" : "UNOBSERVED",type);
}
static void parcel_source_quota(rlim_t, int);
void test_ipc_connections(const char *);
void test_ipc_store_cleanup(const char *);
static void origin_message_experiment(const char *directory) {
    unsigned before=descriptors();
    test_ipc_connections(directory);
    assert(descriptors()==before);
    struct md_ipc_credentials *authority=NULL;
    assert(!md_ipc_credentials_open(directory,MD_IPC_TRANSPORT_NATIVE,&authority));
    const int types[]={SOCK_STREAM,SOCK_DGRAM,SOCK_SEQPACKET};
    for (unsigned i=0;i<3;++i) for (int discard=0;discard<2;++discard)
        origin_message_association(directory,authority,types[i],discard);
    origin_message_shutdown();
    for (int how=SHUT_RD;how<=SHUT_RDWR;++how) for (int consume=0;consume<2;++consume)
        origin_message_custody(authority,how,consume);
    for (unsigned i=0;i<3;++i) origin_message_cycle(authority,types[i]);
    md_ipc_credentials_close(authority);
    test_ipc_store_cleanup(directory);
    const rlim_t limits[]={64,252,253,254,255};
    for (unsigned i=0;i<sizeof(limits)/sizeof(*limits);++i) parcel_source_quota(limits[i],1);
    puts("OBS origin custody uses a queued pipe writer, not socket HUP or privileged diagnostics");
    puts("LIMIT connection identity is not transport negotiation: native peers, cross-launch origins and FD quotas remain outside this candidate");
    puts("PASS message-origin association experiment");
}
enum parcel_action { PARCEL_DELIVER, PARCEL_TRUNCATE, PARCEL_READ, PARCEL_CLOSE,
    PARCEL_PEEK, PARCEL_PEEK_DISCARD, PARCEL_PARTIAL_READ, PARCEL_SEND_FAIL };
/* Native transport experiment, not an application adapter. The fixture knows
 * the peer/cookie binding; no native endpoint is given this private envelope. */
static void kernel_parcel(const char *directory, int type, enum parcel_action action) {
    unsigned before=descriptors();
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/kernel-parcel",directory);
    int source=create_file(path,11), pathfd=open(path,O_PATH|O_CLOEXEC);
    assert(pathfd>=0 && !unlink(path));
    int pipefd[2], envelope[2], route[2];
    assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC|SOCK_NONBLOCK,0,envelope));
    assert(!socketpair(AF_UNIX,type|SOCK_CLOEXEC,0,route));
    if (action==PARCEL_SEND_FAIL) { assert(!close(route[1])); route[1]=-1; }
    uint64_t cookie=socket_cookie(envelope[1]);
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(1);
        union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))]; } control={0};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,
            .cmsg_len=CMSG_LEN(MD_FD_RECEIPT_MAX*sizeof(int))};
        int *fds=(void *)CMSG_DATA(&control.align);
        for (unsigned i=0;i<MD_FD_RECEIPT_MAX;++i)
            fds[i]=i%3==0 ? source : i%3==1 ? pipefd[1] : pathfd;
        char byte=1; struct iovec io={&byte,1};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
        assert(sendmsg(envelope[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT)==1);
        if (action==PARCEL_SEND_FAIL) {
            int fd=envelope[1];
            memcpy(CMSG_DATA(&control.align),&fd,sizeof(fd));
            control.align.cmsg_len=CMSG_LEN(sizeof(fd)); message.msg_controllen=CMSG_SPACE(sizeof(fd));
            assert(sendmsg(route[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT)==-1);
            assert(errno==(type==SOCK_DGRAM ? ECONNREFUSED : EPIPE));
        } else send_message(route[0],(struct message){.kind=DONE},envelope[1]);
        _exit(0);
    }
    assert(!close(envelope[1]) && !close(source) && !close(pathfd)
        && !close(pipefd[1]) && !close(route[0]));
    int status;
    /* EVENT_WAIT: sender exit, leaving only native queued references. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    char byte;
    if (action==PARCEL_SEND_FAIL) {
        parcel_lifetime(envelope[0],1);
        assert(read(pipefd[0],&byte,1)==0);
        assert(!close(envelope[0]) && !close(pipefd[0]) && descriptors()==before);
        return;
    }
    assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
    parcel_lifetime(envelope[0],0);
    unsigned capacity=action==PARCEL_TRUNCATE ? 1 : MD_FD_RECEIPT_MAX;
    size_t size=md_fd_receipt_size(capacity);
    struct md_fd_receipt *receipt=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(receipt!=MAP_FAILED);
    if (action==PARCEL_CLOSE) {
        assert(!close(route[1])); route[1]=-1;
    } else if (action==PARCEL_READ) {
        struct message payload;
        assert(read(route[1],&payload,sizeof(payload))==sizeof(payload) && payload.kind==DONE);
    } else if (action==PARCEL_PARTIAL_READ) {
        assert(read(route[1],&byte,1)==1);
    } else {
        int parcel=-1;
        if (action==PARCEL_PEEK_DISCARD) {
            struct message payload;
            assert(recv(route[1],&payload,sizeof(payload),MSG_PEEK)==sizeof(payload) && payload.kind==DONE);
            parcel_lifetime(envelope[0],0);
        }
        if (action==PARCEL_PEEK) {
            union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
            struct message payload; struct iovec io={&payload,sizeof(payload)};
            struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
            assert(recvmsg(route[1],&message,MSG_PEEK|MSG_CMSG_CLOEXEC)==sizeof(payload) && payload.kind==DONE);
            assert(control.align.cmsg_level==SOL_SOCKET && control.align.cmsg_type==SCM_RIGHTS
                && control.align.cmsg_len==CMSG_LEN(sizeof(int)));
            memcpy(&parcel,CMSG_DATA(&control.align),sizeof(parcel));
            assert(read(route[1],&payload,sizeof(payload))==sizeof(payload));
        } else {
            assert(receive_message(route[1],&parcel).kind==DONE);
        }
        assert(parcel>=0 && socket_cookie(parcel)==cookie);
        for (unsigned pass=0;pass<2;++pass) {
            int fds[MD_FD_RECEIPT_MAX];
            assert(!md_fd_receipt_reset(receipt,capacity));
            assert(recvmsg(parcel,&receipt->message,MSG_PEEK|MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==1);
            assert(md_fd_receipt_read(receipt,capacity,fds)==(int)capacity);
            assert(!!(receipt->message.msg_flags&MSG_CTRUNC)==(capacity<MD_FD_RECEIPT_MAX));
            for (unsigned i=0;i<capacity;++i) {
                assert(fcntl(fds[i],F_GETFD)==FD_CLOEXEC);
                if (i%3==0) assert(lseek(fds[i],0,SEEK_CUR)==11+(long)pass);
                else if (i%3==2) {
                    struct stat data, path;
                    assert((fcntl(fds[i],F_GETFL)&O_PATH) && !fstat(fds[0],&data) && !fstat(fds[i],&path));
                    assert(data.st_dev==path.st_dev && data.st_ino==path.st_ino);
                    assert(read(fds[i],&byte,1)==-1 && errno==EBADF);
                }
            }
            assert(lseek(fds[0],1,SEEK_CUR)==12+(long)pass);
            for (unsigned i=0;i<capacity;++i) assert(!close(fds[i]));
            parcel_lifetime(envelope[0],0);
            assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
        }
        assert(!close(parcel));
    }
    parcel_lifetime(envelope[0],1);
    assert(read(pipefd[0],&byte,1)==0);
    assert(!munmap(receipt,size) && !close(envelope[0]) && !close(pipefd[0]));
    if (route[1]>=0) assert(!close(route[1]));
    assert(descriptors()==before);
}
static void parcel_fd_pressure(int wrapped, unsigned count, unsigned available) {
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(1);
        int route[2], parcel[2]={-1,-1}, source=open("/dev/null",O_RDWR|O_CLOEXEC);
        assert(source>=0 && !socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,route));
        if (wrapped) assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,parcel));
        union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))]; } control={0};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(count*sizeof(int))};
        int *sources=(void *)CMSG_DATA(&control.align);
        for (unsigned i=0;i<count;++i) sources[i]=source;
        char byte=1; struct iovec io={&byte,1};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=CMSG_SPACE(count*sizeof(int))};
        assert(sendmsg(wrapped ? parcel[0] : route[0],&message,MSG_NOSIGNAL)==1);
        if (wrapped) send_message(route[0],(struct message){.kind=DONE},parcel[1]);
        if (wrapped) assert(!close(parcel[1]));
        limit_free_descriptors(available);
        unsigned capacity=count; size_t size=md_fd_receipt_size(capacity);
        struct md_fd_receipt *receipt=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(receipt!=MAP_FAILED);
        int received[MD_FD_RECEIPT_MAX], transport=-1, delivered=0;
        if (wrapped) {
            union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } outer={0};
            struct message payload; struct iovec vector={&payload,sizeof(payload)};
            struct msghdr packet={.msg_iov=&vector,.msg_iovlen=1,.msg_control=outer.bytes,.msg_controllen=sizeof(outer.bytes)};
            assert(recvmsg(route[1],&packet,MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==sizeof(payload));
            if (available) {
                assert(!(packet.msg_flags&MSG_CTRUNC) && outer.align.cmsg_len==CMSG_LEN(sizeof(int)));
                memcpy(&transport,CMSG_DATA(&outer.align),sizeof(transport));
            } else assert((packet.msg_flags&MSG_CTRUNC) && !packet.msg_controllen);
        }
        if (!wrapped || transport>=0) {
            assert(!md_fd_receipt_reset(receipt,capacity));
            assert(recvmsg(wrapped ? transport : route[1],&receipt->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT|MSG_PEEK)==1);
            delivered=md_fd_receipt_read(receipt,capacity,received);
            int expected=(int)available-(wrapped ? 1 : 0);
            if (expected>(int)count) expected=(int)count;
            assert(delivered==expected && !!(receipt->message.msg_flags&MSG_CTRUNC)==(delivered<(int)count));
        }
        for (int i=0;i<delivered;++i) assert(!close(received[i]));
        if (transport>=0) assert(!close(transport));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: child-owned quota comparison; the fixture alarm bounds failure. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
/* The relay channel and receipt are prepared before applying the guest quota.
 * This tests the delivery boundary, not lazy channel admission at a full table. */
static int parcel_relay(const char *directory, unsigned count, unsigned available,
        int peek, int terminal, int protected_receiver, int wrong_cookie) {
    pid_t owner=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=owner) _exit(1);
        unsigned before=descriptors();
        char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/relay",directory);
        int source=create_file(path,11), pathfd=open(path,O_PATH|O_CLOEXEC), pipefd[2];
        assert(pathfd>=0 && !unlink(path) && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        int route[2], envelope[2], relay[2];
        assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,route)
            && !socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,envelope)
            && !socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,relay));
        uint64_t cookie=socket_cookie(wrong_cookie ? route[1] : envelope[1]);
        struct md_mount_namespace *views[]={md_mount_namespace_new(),md_mount_namespace_new()};
        assert(views[0] && views[1]);
        struct md_fs_anchor *known[]={capture(source,views[0]),capture(pathfd,views[1]),NULL};
        struct md_fd_object *origins[MD_FD_RECEIPT_MAX];
        int sources[MD_FD_RECEIPT_MAX], handles[]={source,pathfd,pipefd[1]};
        for (unsigned i=0;i<count;++i) { sources[i]=handles[i%3]; origins[i]=md_fs_anchor_object(known[i%3]); }
        struct md_fd_origins *files=md_fd_origins_new(); assert(files);
        struct md_fd_origin_update updates[MD_FD_RECEIPT_MAX]; int numbers[MD_FD_RECEIPT_MAX];
        struct md_fd_delivery delivery={0};
        assert(!md_fd_delivery_prepare(&delivery,files,origins,FD_CLOEXEC,count,updates,numbers));
        union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))]; } control={0};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,
            .cmsg_len=CMSG_LEN(count*sizeof(int))};
        memcpy(CMSG_DATA(&control.align),sources,count*sizeof(int));
        char byte='r'; struct iovec io={&byte,1};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
            .msg_controllen=CMSG_SPACE(count*sizeof(int))};
        assert(sendmsg(envelope[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT)==1);
        send_message(route[0],(struct message){.kind=DONE},envelope[1]);
        assert(!close(envelope[1]) && !close(route[0]) && !close(source) && !close(pathfd) && !close(pipefd[1]));
        pid_t parent=getpid(), broker=fork(); assert(broker>=0);
        if (!broker) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
            if (getppid()!=parent) _exit(1);
            assert(!close(relay[0]) && !close(route[1]) && !close(envelope[0]) && !close(pipefd[0]));
            int parcel=-1;
            struct message request=receive_message(relay[1],&parcel);
            if (request.kind==STOP) { assert(parcel<0); _exit(0); }
            assert(request.kind==CHECK && parcel>=0);
            /* Both the peer and issued endpoint are fixture-owned identities;
             * the sender's payload is not accepted as a provenance claim. */
            struct ucred peer; socklen_t length=sizeof(peer);
            assert(!getsockopt(relay[1],SOL_SOCKET,SO_PEERCRED,&peer,&length)
                && length==sizeof(peer) && peer.pid==parent && peer.uid==getuid());
            int accepted=socket_cookie(parcel)==cookie;
            if (accepted) {
                message.msg_controllen=CMSG_LEN(count*sizeof(int)); message.msg_flags=0;
                assert(recvmsg(parcel,&message,MSG_PEEK|MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==1
                    && !(message.msg_flags&(MSG_CTRUNC|MSG_TRUNC)));
                assert(control.align.cmsg_len==CMSG_LEN(count*sizeof(int)));
            }
            assert(!close(parcel));
            /* EVENT_WAIT: the receiver has released its temporary endpoint slot;
             * the outer fixture alarm bounds failure, not delivery completion. */
            assert(receive_message(relay[1],NULL).kind==CONTINUE);
            send_message(relay[1],(struct message){.kind=ACK,.error=accepted ? 0 : EACCES},-1);
            if (accepted) {
                assert(sendmsg(relay[1],&message,MSG_NOSIGNAL|MSG_DONTWAIT)==1);
                int *fds=(void *)CMSG_DATA(&control.align);
                for (unsigned i=0;i<count;++i) assert(!close(fds[i]));
            }
            _exit(0);
        }
        assert(!close(relay[1]));
        if (protected_receiver) assert(!prctl(PR_SET_DUMPABLE,0));
        struct rlimit old_limit; assert(!getrlimit(RLIMIT_NOFILE,&old_limit));
        limit_free_descriptors(available);
        union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } outer={0};
        struct message payload; struct iovec vector={&payload,sizeof(payload)};
        struct msghdr packet={.msg_iov=&vector,.msg_iovlen=1,.msg_control=outer.bytes,.msg_controllen=sizeof(outer.bytes)};
        assert(recvmsg(route[1],&packet,MSG_CMSG_CLOEXEC|MSG_DONTWAIT|(peek ? MSG_PEEK : 0))==sizeof(payload));
        assert(payload.kind==DONE);
        int expected=0, export_limited=0;
        if (!available) {
            assert((packet.msg_flags&MSG_CTRUNC) && !packet.msg_controllen);
            send_message(relay[0],(struct message){.kind=STOP},-1);
        } else {
            assert(!(packet.msg_flags&MSG_CTRUNC) && outer.align.cmsg_len==CMSG_LEN(sizeof(int)));
            int parcel; memcpy(&parcel,CMSG_DATA(&outer.align),sizeof(parcel));
            ssize_t exported=send_packet(relay[0],(struct message){.kind=CHECK},parcel);
            if (exported<0) {
                int error=errno; assert(error==ETOOMANYREFS);
                struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
                printf("LIMIT relay export count=%u available=%u softLimit=%llu peek=%d protected=%d errno=%d\n",
                    count,available,(unsigned long long)limit.rlim_cur,peek,protected_receiver,error);
                export_limited=1;
            } else assert(exported==sizeof(struct message));
            assert(!close(parcel));
            if (export_limited) send_message(relay[0],(struct message){.kind=STOP},-1);
            else {
                send_message(relay[0],(struct message){.kind=CONTINUE},-1);
                struct message answer=receive_message(relay[0],NULL);
                assert(answer.kind==ACK && answer.error==(wrong_cookie ? EACCES : 0));
            }
            if (!wrong_cookie && !export_limited) {
                struct pollfd ready={.fd=relay[0],.events=POLLIN};
                /* EVENT_WAIT: payload queued on the private relay; expiry fails
                 * the experiment. Blocking readiness is outside the table turn. */
                assert(poll(&ready,1,1000)==1 && (ready.revents&POLLIN));
                struct md_fd_order turn={0}, next={0};
                assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
                assert(!md_fd_order_begin(files,&next,NULL));
                if (terminal) {
                    size_t stack_size=65536;
                    void *stack=mmap(NULL,stack_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
                    assert(stack!=MAP_FAILED);
                    struct receipt_child context={.socket=relay[0],.receipt=md_fd_delivery_receipt(&delivery),
                        .parent=getpid(),.capacity=count};
                    pid_t receiver=clone(receive_and_exit,(char *)stack+stack_size,CLONE_FILES|SIGCHLD,&context);
                    assert(receiver>0);
                    int status;
                    /* EVENT_WAIT: terminal receiver, without a result/FD ACK. */
                    assert(waitpid(receiver,&status,0)==receiver && WIFEXITED(status) && !WEXITSTATUS(status));
                    assert(!munmap(stack,stack_size) && md_fd_order_cancel(&turn)==1);
                } else {
                    assert(!md_fd_receipt_reset(delivery.receipt,count));
                    assert(recvmsg(relay[0],&delivery.receipt->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==1);
                }
                expected=(int)(available<count ? available : count);
                assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
                assert(md_fd_delivery_publish(&delivery)==expected);
                assert(delivery.receipt->byte=='r'
                    && !!(delivery.receipt->message.msg_flags&MSG_CTRUNC)==(expected<(int)count));
                assert(!md_fd_order_complete(&turn) && !md_fd_order_cancel(&next));
                for (int i=0;i<expected;++i) {
                    origin_at(files,numbers[i],known[i%3]);
                    assert(fcntl(numbers[i],F_GETFD)==FD_CLOEXEC);
                    if (i%3==0) assert(lseek(numbers[i],0,SEEK_CUR)==11);
                    if (i%3==1) assert(fcntl(numbers[i],F_GETFL)&O_PATH);
                    assert(!close(numbers[i]));
                }
            }
        }
        assert(!setrlimit(RLIMIT_NOFILE,&old_limit));
        int status;
        /* EVENT_WAIT: broker exit releases its transient data descriptions. */
        assert(waitpid(broker,&status,0)==broker && WIFEXITED(status) && !WEXITSTATUS(status));
        ssize_t n=read(pipefd[0],&byte,1);
        assert(peek ? n==-1 && errno==EAGAIN : n==0);
        assert(!close(route[1]));
        parcel_lifetime(envelope[0],1);
        assert(read(pipefd[0],&byte,1)==0);
        assert(!close(pipefd[0]) && !close(envelope[0]) && !close(relay[0]));
        md_fd_delivery_dispose(&delivery); md_fd_origins_release(files);
        md_fs_anchor_release(known[0]); md_fs_anchor_release(known[1]);
        md_mount_namespace_release(views[0]); md_mount_namespace_release(views[1]);
        assert(descriptors()==before);
        _exit(export_limited ? 2 : 0);
    }
    int status;
    /* EVENT_WAIT: isolated quota/dumpability scenario, bounded by fixture alarm. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status)
        && (WEXITSTATUS(status)==0 || WEXITSTATUS(status)==2));
    return WEXITSTATUS(status)==2;
}
static int parcel_cycle(int wrapped, int type) {
    unsigned before=descriptors();
    int route[2], parcel[2]={-1,-1}, pipefd[2];
    assert(!socketpair(AF_UNIX,type|SOCK_CLOEXEC,0,route) && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    if (wrapped) assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,parcel));
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control={0};
    control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(2*sizeof(int))};
    int sources[]={route[1],pipefd[1]}; memcpy(CMSG_DATA(&control.align),sources,sizeof(sources));
    char byte=1; struct iovec io={&byte,1};
    struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control.bytes)};
    assert(sendmsg(wrapped ? parcel[0] : route[0],&message,MSG_NOSIGNAL)==1);
    if (wrapped) {
        send_message(route[0],(struct message){.kind=DONE},parcel[1]);
        assert(!close(parcel[1]));
    }
    assert(!close(route[1]) && !close(pipefd[1]));
    struct pollfd ended={.fd=pipefd[0],.events=POLLIN|POLLHUP};
    /* EVENT_WAIT: Unix GC releases a self-reference (or a two-socket cycle).
     * Expiry is an unobserved native lifetime, never evidence of collection. */
    int observed=poll(&ended,1,3000); assert(observed>=0);
    if (observed) {
        assert((ended.revents&POLLHUP) && read(pipefd[0],&byte,1)==0);
        if (wrapped) parcel_lifetime(parcel[0],1);
    }
    if (wrapped) assert(!close(parcel[0]));
    assert(!close(route[0]) && !close(pipefd[0]) && descriptors()==before);
    return observed;
}
static void pidfd_pull(const char *directory, int protected_source, int protected_receiver) {
    unsigned before=descriptors();
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/pidfd-pull",directory);
    int source=create_file(path,11), pathfd=open(path,O_PATH|O_CLOEXEC), pipefd[2];
    assert(pathfd>=0 && !unlink(path) && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    pid_t parent=getpid(), provider=fork(); assert(provider>=0);
    if (!provider) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(1);
        assert(prctl(PR_GET_DUMPABLE)==1);
        if (protected_source) assert(!prctl(PR_SET_DUMPABLE,0));
        pid_t owner=getpid();
        pid_t receiver=fork(); assert(receiver>=0);
        if (!receiver) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
            if (getppid()!=owner) _exit(1);
            /* Compare the pull direction without changing Android identity or
             * making a protected provider dumpable. Inherited copies are not
             * used as substitutes for descriptors pulled from the provider. */
            int sources[]={source,pathfd,pipefd[1]};
            assert(!close(source) && !close(pathfd) && !close(pipefd[0]) && !close(pipefd[1]));
            assert(!prctl(PR_SET_DUMPABLE,protected_receiver ? 0 : 1));
            int process=syscall(SYS_pidfd_open,owner,0); assert(process>=0);
            int copied[3]={-1,-1,-1}, errors[3]={0};
            for (unsigned i=0;i<3;++i) {
                copied[i]=syscall(SYS_pidfd_getfd,process,sources[i],0);
                if (copied[i]<0) errors[i]=errno;
                else {
                    assert(fcntl(copied[i],F_GETFD)==FD_CLOEXEC);
                    if (i==0) assert(lseek(copied[i],0,SEEK_CUR)==11);
                    if (i==1) assert(fcntl(copied[i],F_GETFL)&O_PATH);
                    if (i==2) assert(write(copied[i],"p",1)==1);
                    assert(!close(copied[i]));
                }
            }
            printf("%s pidfd pull sourceProtected=%d receiverProtected=%d regularErrno=%d pathErrno=%d pipeErrno=%d\n",
                errors[0] || errors[1] || errors[2] ? "LIMIT" : "PASS",
                protected_source,protected_receiver,errors[0],errors[1],errors[2]);
            fflush(stdout);
            assert(!close(process));
            _exit(0);
        }
        int status;
        /* EVENT_WAIT: exact receiver completion; outer fixture alarm bounds failure. */
        assert(waitpid(receiver,&status,0)==receiver && WIFEXITED(status) && !WEXITSTATUS(status));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: provider owns its dumpability change; wait before removing its inputs. */
    assert(waitpid(provider,&status,0)==provider && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(source) && !close(pathfd) && !close(pipefd[0]) && !close(pipefd[1]));
    assert(descriptors()==before);
}
static void local_ring_probe(void) {
    unsigned before=descriptors();
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(1);
        struct io_uring_params parameters={0};
        int ring=(int)syscall(SYS_io_uring_setup,2,&parameters);
        if (ring<0) {
            printf("LIMIT local registered-file candidate: io_uring_setup errno=%d uid=%u\n",errno,getuid());
        } else {
            printf("PROBE local registered-file candidate: io_uring_setup features=%#x uid=%u\n",parameters.features,getuid());
            assert(!close(ring));
        }
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: optional capability probe exits or receives SIGSYS from the
     * host policy. The fixture alarm bounds an unresponsive child. */
    assert(waitpid(child,&status,0)==child);
    if (WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS)
        puts("LIMIT local registered-file candidate: io_uring_setup blocked by SIGSYS");
    else assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(descriptors()==before);
}
static void parcel_source_quota(rlim_t limit, int lifetime_marker) {
    unsigned before=descriptors();
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(1);
        int route[2], parcel[2], pipefd[2];
        assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,route)
            && !socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,parcel)
            && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        struct rlimit original; assert(!getrlimit(RLIMIT_NOFILE,&original));
        assert(limit<=original.rlim_cur);
        struct rlimit restricted=original; restricted.rlim_cur=limit;
        assert(!setrlimit(RLIMIT_NOFILE,&restricted));
        union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))]; } control={0};
        control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,
            .cmsg_len=CMSG_LEN(MD_FD_RECEIPT_MAX*sizeof(int))};
        int *sources=(void *)CMSG_DATA(&control.align);
        for (unsigned i=0;i<MD_FD_RECEIPT_MAX;++i) sources[i]=pipefd[1];
        char byte='s'; struct iovec io={&byte,1};
        struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
            .msg_controllen=sizeof(control.bytes)};
        ssize_t direct=sendmsg(route[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT);
        int direct_error=direct<0 ? errno : 0;
        assert(direct==1 || direct_error==ETOOMANYREFS);
        if (direct==1) assert(recv(route[1],&byte,1,MSG_DONTWAIT)==1);
        ssize_t inner=sendmsg(parcel[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT);
        int inner_error=inner<0 ? errno : 0;
        assert(inner==1 || inner_error==ETOOMANYREFS);
        ssize_t outer=-1; int outer_error=0, marker_error=0;
        if (inner==1) {
            control.align.cmsg_len=CMSG_LEN(sizeof(int));
            message.msg_controllen=CMSG_SPACE(sizeof(int));
            if (lifetime_marker) {
                memcpy(CMSG_DATA(&control.align),&pipefd[1],sizeof(int));
                ssize_t marker=sendmsg(parcel[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT);
                marker_error=marker<0 ? errno : 0;
                assert(marker==1 || marker_error==ETOOMANYREFS);
            }
            if (!marker_error) {
                memcpy(CMSG_DATA(&control.align),&parcel[1],sizeof(int));
                outer=sendmsg(route[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT);
                outer_error=outer<0 ? errno : 0;
                assert(outer==1 || outer_error==ETOOMANYREFS);
            }
        }
        assert(!close(pipefd[1]) && !close(parcel[1]));
        if (outer==1) {
            assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
            assert(recv(route[1],&byte,1,MSG_DONTWAIT)==1);
        }
        assert(read(pipefd[0],&byte,1)==0);
        parcel_lifetime(parcel[0],1);
        printf("%s source quota limit=%llu lifetimeMarker=%d directErrno=%d innerErrno=%d markerErrno=%d outerErrno=%d\n",
            direct==1 && inner==1 ? outer==1 ? "PASS" : "LIMIT" : "UNOBSERVED",
            (unsigned long long)limit,lifetime_marker,direct_error,inner_error,marker_error,outer_error);
        assert(!close(route[0]) && !close(route[1]) && !close(parcel[0]) && !close(pipefd[0]));
        _exit(0);
    }
    int status;
    /* EVENT_WAIT: sender owns its quota and queues. Exact exit precedes the next
     * case; the fixture alarm bounds failure without changing the shell limit. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(descriptors()==before);
}
struct local_parcel {
    struct md_fd_receipt *envelope, *payload;
    uint64_t cookie;
    socklen_t cookie_size;
    int error;
};
struct local_parcel_child {
    struct local_parcel *result;
    pid_t parent;
    int socket, slot, peek, protected_receiver, terminal_stage;
    unsigned capacity, available;
    uint64_t expected_cookie;
};
static int local_parcel_receive(void *opaque) {
    struct local_parcel_child *f=opaque;
    struct local_parcel *r=f->result;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=f->parent) _exit(1);
    if (f->protected_receiver) assert(!prctl(PR_SET_DUMPABLE,0));
    assert(!md_fd_receipt_reset(r->envelope,1) && !md_fd_receipt_reset(r->payload,f->capacity));
    limit_free_descriptors(f->available);
    assert(recvmsg(f->socket,&r->envelope->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT|(f->peek ? MSG_PEEK : 0))==1);
    int parcel=-1;
    int count=md_fd_receipt_read(r->envelope,1,&parcel);
    assert(count==(f->available ? 1 : 0));
    if (!count || f->terminal_stage==0) _exit(0);
    r->cookie_size=sizeof(r->cookie);
    assert(!getsockopt(parcel,SOL_SOCKET,SO_COOKIE,&r->cookie,&r->cookie_size)
        && r->cookie_size==sizeof(r->cookie));
    if (r->cookie!=f->expected_cookie) { r->error=EACCES; _exit(0); }
    int moved=dup3(parcel,f->slot,O_CLOEXEC);
    if (moved<0) { r->error=errno; _exit(0); }
    assert(moved==f->slot && socket_cookie(f->slot)==r->cookie);
    if (f->terminal_stage==1) _exit(0);
    assert(!close(parcel));
    if (f->terminal_stage==2) _exit(0);
    assert(recvmsg(f->slot,&r->payload->message,MSG_PEEK|MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==1);
    /* The owner has both kernel receipts even if no userspace result is sent.
     * It must distinguish the old envelope number from a reused payload slot. */
    _exit(0);
}
static void parcel_local_slot(const char *directory, unsigned available, int peek,
        int protected_receiver, int terminal_stage, int high_slot, int wrong_cookie) {
    unsigned before=descriptors();
    int slot=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(slot>=0);
    if (high_slot) {
        int high=fcntl(slot,F_DUPFD_CLOEXEC,512); assert(high>=0);
        assert(!close(slot)); slot=high;
    }
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/local-slot",directory);
    int source=create_file(path,11), pathfd=open(path,O_PATH|O_CLOEXEC), pipefd[2];
    assert(pathfd>=0 && !unlink(path) && !pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
    int route[2], envelope[2];
    assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,route)
        && !socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,envelope));
    uint64_t cookie=socket_cookie(envelope[1]);
    unsigned capacity=MD_FD_RECEIPT_MAX;
    struct md_mount_namespace *views[]={md_mount_namespace_new(),md_mount_namespace_new()};
    assert(views[0] && views[1]);
    struct md_fs_anchor *known[]={capture(source,views[0]),capture(pathfd,views[1]),NULL};
    struct md_fd_object *origins[MD_FD_RECEIPT_MAX];
    int sources[MD_FD_RECEIPT_MAX], handles[]={source,pathfd,pipefd[1]};
    for (unsigned i=0;i<capacity;++i) { sources[i]=handles[i%3]; origins[i]=md_fs_anchor_object(known[i%3]); }
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_origin_update updates[MD_FD_RECEIPT_MAX]; int numbers[MD_FD_RECEIPT_MAX];
    struct md_fd_delivery delivery={0};
    assert(!md_fd_delivery_prepare(&delivery,files,origins,FD_CLOEXEC,capacity,updates,numbers));
    size_t outer_size=md_fd_receipt_size(1);
    struct local_parcel *result=mmap(NULL,sizeof(*result)+outer_size,PROT_READ|PROT_WRITE,
        MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(result!=MAP_FAILED);
    result->envelope=(void *)(result+1); result->payload=delivery.receipt;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(MD_FD_RECEIPT_MAX*sizeof(int))]; } control={0};
    control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,
        .cmsg_len=CMSG_LEN(capacity*sizeof(int))};
    memcpy(CMSG_DATA(&control.align),sources,capacity*sizeof(int));
    char byte='l'; struct iovec io={&byte,1};
    struct msghdr message={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,
        .msg_controllen=CMSG_SPACE(capacity*sizeof(int))};
    assert(sendmsg(envelope[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT)==1);
    memcpy(CMSG_DATA(&control.align),&envelope[1],sizeof(int));
    control.align.cmsg_len=CMSG_LEN(sizeof(int)); message.msg_controllen=CMSG_SPACE(sizeof(int));
    assert(sendmsg(route[0],&message,MSG_NOSIGNAL|MSG_DONTWAIT)==1);
    assert(!close(envelope[1]) && !close(route[0]) && !close(source) && !close(pathfd) && !close(pipefd[1]));
    size_t stack_size=65536;
    void *stack=mmap(NULL,stack_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    struct md_fd_order turn={0}, next={0};
    assert(md_fd_order_begin(files,&turn,NULL)==1 && !md_fd_order_start(&turn));
    assert(!md_fd_order_begin(files,&next,NULL));
    struct local_parcel_child context={.result=result,.parent=getpid(),.socket=route[1],.slot=slot,
        .peek=peek,.protected_receiver=protected_receiver,.terminal_stage=terminal_stage,
        .capacity=capacity,.available=available,.expected_cookie=cookie^(wrong_cookie ? 1 : 0)};
    pid_t child=clone(local_parcel_receive,(char *)stack+stack_size,CLONE_FILES|SIGCHLD,&context);
    assert(child>0);
    int status;
    /* EVENT_WAIT: exact receiver exit retains custody and payload receipts.
     * Only nonblocking kernel operations run under this table turn. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(md_fd_order_cancel(&turn)==1);
    int expected=terminal_stage==3 && !high_slot && !wrong_cookie
        ? (int)(available<capacity ? available : capacity) : 0;
    assert(result->error==(!available || terminal_stage==0 ? 0 : wrong_cookie ? EACCES : high_slot ? EBADF : 0));
    if (available && terminal_stage>0) assert(result->cookie==cookie);
    assert(md_fd_delivery_publish(&delivery)==expected);
    for (int i=0;i<expected;++i) {
        origin_at(files,numbers[i],known[i%3]);
        assert(fcntl(numbers[i],F_GETFD)==FD_CLOEXEC);
        if (i%3==0) assert(lseek(numbers[i],0,SEEK_CUR)==11);
        if (i%3==1) assert(fcntl(numbers[i],F_GETFL)&O_PATH);
    }
    /* The outer number may already name an installed application FD. Its old
     * receipt is not permission to close that slot a second time. */
    int temporary=-1, outer_count=md_fd_receipt_read(result->envelope,1,&temporary), reused=0;
    assert(outer_count==(available ? 1 : 0));
    for (int i=0;i<expected;++i) if (numbers[i]==temporary) reused=1;
    if (outer_count && !reused && fcntl(temporary,F_GETFD)>=0) {
        assert(socket_cookie(temporary)==cookie && !close(temporary));
    }
    assert(!close(slot));
    assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
    assert(!md_fd_order_complete(&turn) && !md_fd_order_cancel(&next));
    for (int i=0;i<expected;++i) assert(!close(numbers[i]));
    ssize_t n=read(pipefd[0],&byte,1);
    assert(peek ? n==-1 && errno==EAGAIN : n==0);
    assert(!close(route[1])); parcel_lifetime(envelope[0],1);
    assert(read(pipefd[0],&byte,1)==0);
    assert(!close(pipefd[0]) && !close(envelope[0]));
    assert(!munmap(stack,stack_size) && !munmap(result,sizeof(*result)+outer_size));
    md_fd_delivery_dispose(&delivery); md_fd_origins_release(files);
    md_fs_anchor_release(known[0]); md_fs_anchor_release(known[1]);
    md_mount_namespace_release(views[0]); md_mount_namespace_release(views[1]);
    assert(descriptors()==before);
}
static void parcel_experiment(const char *directory) {
    local_ring_probe();
    const rlim_t source_limits[]={64,252,253,254};
    for (unsigned i=0;i<sizeof(source_limits)/sizeof(*source_limits);++i)
        parcel_source_quota(source_limits[i],0);
    for (int provider=0;provider<2;++provider)
        for (int receiver=0;receiver<2;++receiver) pidfd_pull(directory,provider,receiver);
    for (unsigned type=0;type<3;++type)
        for (enum parcel_action action=PARCEL_DELIVER;action<=PARCEL_SEND_FAIL;++action)
            kernel_parcel(directory,type==0 ? SOCK_STREAM : type==1 ? SOCK_DGRAM : SOCK_SEQPACKET,action);
    puts("PASS native kernel-parcel experiment: 253 rights, O_PATH, retained cookie, sender exit, peek/discard, partial read, truncation, queue close, failed send and EOF");
    for (unsigned available=0;available<=4;++available) {
        parcel_fd_pressure(0,3,available); parcel_fd_pressure(1,3,available);
    }
    puts("LIMIT kernel parcel under RLIMIT_NOFILE: private endpoint consumes one receiving slot; native delivers one more FD");
    const unsigned slots[]={0,1,2,3,4,252,253,254};
    unsigned relay_limited=0;
    for (unsigned i=0;i<sizeof(slots)/sizeof(*slots);++i) {
        parcel_fd_pressure(0,MD_FD_RECEIPT_MAX,slots[i]);
        for (int protected_receiver=0;protected_receiver<2;++protected_receiver)
            for (int peek=0;peek<2;++peek)
                relay_limited+=parcel_relay(directory,MD_FD_RECEIPT_MAX,slots[i],peek,1,protected_receiver,0);
    }
    puts("PASS direct native delivery of 253 queued rights: exact available prefix at receiver quotas 0-254");
    for (unsigned available=0;available<=4;++available)
        assert(!parcel_relay(directory,3,available,0,0,1,0));
    assert(!parcel_relay(directory,3,3,0,0,1,1));
    assert(!parcel_relay(directory,3,3,1,0,1,1));
    printf("%s preconnected parcel relay: %u scenarios blocked by native in-flight FD quota\n",relay_limited ? "LIMIT" : "UNOBSERVED",relay_limited);
    puts("PASS admitted parcel relay: exact available prefix, O_PATH, protected receiver, peek, terminal receipt, wrong-cookie rejection and EOF");
    puts("LIMIT parcel relay requires a preconnected channel and receipt; lazy admission, source/in-flight quotas, native peers and cross-launch provenance are not established");
    for (unsigned i=0;i<sizeof(slots)/sizeof(*slots);++i)
        for (int protected_receiver=0;protected_receiver<2;++protected_receiver)
            for (int peek=0;peek<2;++peek)
                parcel_local_slot(directory,slots[i],peek,protected_receiver,3,0,0);
    for (int stage=0;stage<3;++stage)
        for (int peek=0;peek<2;++peek)
            parcel_local_slot(directory,1,peek,1,stage,0,0);
    parcel_local_slot(directory,1,0,1,3,0,1);
    parcel_local_slot(directory,1,1,1,3,0,1);
    puts("PASS local parcel custody: 253 rights, quotas 0-254, kernel cookie, O_PATH, protected receiver, peek, terminal phases, reused FD numbers and EOF without reverse FD export");
    parcel_local_slot(directory,1,0,1,3,1,0);
    parcel_local_slot(directory,1,1,1,3,1,0);
    puts("LIMIT local custody needs an owned preallocated slot below RLIMIT_NOFILE; dup3 rejects a retained slot above a lowered limit");
    for (unsigned i=0;i<3;++i) {
        int type=i==0 ? SOCK_STREAM : i==1 ? SOCK_DGRAM : SOCK_SEQPACKET;
        int direct=parcel_cycle(0,type), wrapped=parcel_cycle(1,type);
        printf("%s Unix GC type=%d native=%s parcel=%s\n",direct && wrapped ? "PASS" : "UNOBSERVED",type,
            direct ? "EOF" : "unobserved",wrapped ? "EOF" : "unobserved");
    }
}
struct mapped_receiver {
    struct md_fd_receipt *receipt;
    uint64_t *token;
    long result;
    int endpoint, mode;
    unsigned slots;
    pid_t parent;
};
static int mapped_receive(void *opaque) {
    struct mapped_receiver *c=opaque;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==c->parent);
    assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    if (c->mode==1) _exit(0);
    if (c->mode==3) {
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,0,1),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    }
    limit_free_descriptors(c->slots);
    long result=recvmsg(c->endpoint,&c->receipt->message,MSG_CMSG_CLOEXEC|MSG_DONTWAIT);
    c->result=result<0 ? -errno : result;
    _exit(0);
}
static void mapped_return(const char *directory, int mode, unsigned slots, int path_only) {
    unsigned before=descriptors();
    struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
    int endpoint=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0); assert(endpoint>=0);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_fd_origin_update update={0};
    assert(!md_fd_origins_prepare_channel(files,channel,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,endpoint);
    struct md_fd_origins *copy=md_fd_origins_fork(files,0); assert(copy);
    int pipefd[2]; assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/mapped-return",directory);
    int spare=create_file(path,17);
    int source=path_only ? open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC) : pipefd[1]; assert(source>=0);
    if (path_only) assert(!close(pipefd[1]));
    assert(!unlink(path));
    struct md_mount_namespace *view=md_mount_namespace_new(); assert(view);
    struct md_fs_anchor *origin=capture(source,view);
    void *memory=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(memory!=MAP_FAILED);
    struct mapped_receiver *context=memory;
    struct md_fd_receipt *receipt=(void *)((char *)memory+128);
    uint64_t *token=(void *)((char *)memory+512);
    assert(!md_fd_receipt_reset(receipt,1)); receipt->vector=(struct iovec){token,sizeof(*token)};
    *context=(struct mapped_receiver){.receipt=receipt,.token=token,.endpoint=endpoint,
        .mode=mode,.slots=slots,.parent=getpid(),.result=-ECANCELED};
    struct md_fd_return first={0},second={0},third={0};
    assert(!md_fd_origins_abandon_range(files,endpoint,endpoint));
    assert(!md_fd_return_begin(&first,files,source,md_fs_anchor_object(origin),FD_CLOEXEC,receipt,NULL));
    assert(md_fd_return_progress(&first,NULL)==MD_FD_RETURN_CHECK && first.endpoint==endpoint);
    assert(!md_fd_return_cancel(&first));
    assert(!md_fd_return_begin(&first,files,source,md_fs_anchor_object(origin),FD_CLOEXEC,receipt,NULL));
    assert(md_fd_return_progress(&first,NULL)==MD_FD_RETURN_CHECK && first.endpoint==endpoint);
    struct md_fd_channel_observation observation={.error=-EPERM};
    assert(md_fd_return_observed(&first,&observation)==-EPERM && first.checking && !first.offered);
    observation=(struct md_fd_channel_observation){.length=sizeof(observation.cookie),.flags=FD_CLOEXEC};
    assert(!getsockopt(endpoint,SOL_SOCKET,SO_COOKIE,&observation.cookie,&observation.length));
    assert(!md_fd_return_observed(&first,&observation));
    assert(md_fd_return_progress(&first,NULL)==MD_FD_RETURN_READY);
    uint64_t expected=first.request;
    assert(!close(source));
    struct md_fd_receipt *other=(void *)((char *)memory+1024), *last=(void *)((char *)memory+2048);
    assert(!md_fd_receipt_reset(other,1) && !md_fd_receipt_reset(last,1));
    assert(!md_fd_return_begin(&second,copy,spare,NULL,FD_CLOEXEC,other,NULL));
    assert(md_fd_return_progress(&second,NULL)==MD_FD_RETURN_WAIT);
    assert(md_resource_order_state(&second.channel_order)==MD_RESOURCE_ORDER_QUEUED);
    assert(!md_fd_return_begin(&third,files,spare,NULL,FD_CLOEXEC,last,NULL));
    assert(md_fd_return_progress(&third,NULL)==MD_FD_RETURN_WAIT);
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    pid_t child=clone(mapped_receive,(char *)stack+65536,CLONE_FILES|SIGCHLD,context); assert(child>0);
    /* EVENT_WAIT: exact terminal event, not a guest FD-number acknowledgement.
     * The fixture alarm bounds failure; publication precedes queued mutations. */
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    int number=-1,count=md_fd_receipt_read(receipt,1,&number);
    int expected_count=(mode==0 || mode==2) && slots;
    assert(count==expected_count);
    if (mode==0) {
        assert(context->result==8 && *token==expected);
        assert(md_fd_return_finish(&first,context->result,*token)==(count ? number : -EMFILE));
    } else assert(!md_fd_return_cancel(&first));
    assert(md_resource_order_state(&second.channel_order)==MD_RESOURCE_ORDER_READY);
    assert(!md_fd_return_cancel(&second));
    assert(md_fd_return_progress(&third,NULL)==MD_FD_RETURN_READY);
    assert(!md_fd_return_cancel(&third));
    if (count) {
        origin_at(files,number,origin);
        assert(fcntl(number,F_GETFD)==FD_CLOEXEC);
        if (path_only) assert(fcntl(number,F_GETFL)==(O_PATH|O_NOFOLLOW));
        else { char byte; assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN); }
        assert(!close(number));
        assert(!md_fd_origins_close_range(files,(unsigned)number,(unsigned)number,0));
    }
    char byte; assert(read(pipefd[0],&byte,1)==0);
    assert(!close(pipefd[0]) && !close(spare) && !close(endpoint));
    md_fd_origins_release(files); md_fd_origins_release(copy); md_fd_channel_release(channel);
    md_fs_anchor_release(origin); md_mount_namespace_release(view);
    assert(!munmap(stack,65536) && !munmap(memory,4096) && descriptors()==before);
    printf("PASS mapped result ownership mode=%d slots=%u path=%d: exact terminal receipt, fork channel order, cancellation and EOF\n",mode,slots,path_only);
}
static int install_signal_socket;
static void install_signal(int signal) {
    (void)signal;
    char byte;
    assert(write(install_signal_socket,"i",1)==1);
    /* EVENT_WAIT: parent verifies cancellation before releasing syscall restart. */
    assert(read(install_signal_socket,&byte,1)==1 && byte=='r');
}
static void atomic_install(const char *directory, int available, int protected, int cloexec, int cancel) {
    unsigned before=descriptors();
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/install",directory);
    int source=create_file(path,11); assert(!unlink(path));
    struct md_mount_namespace *view=md_mount_namespace_new(); assert(view);
    struct md_fs_anchor *origin=capture(source,view);
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(sockets[0]);
        if (cancel>=3) {
            install_signal_socket=sockets[1];
            struct sigaction action={.sa_handler=install_signal,.sa_flags=SA_RESTART};
            sigemptyset(&action.sa_mask);
            assert(!sigaction(SIGUSR1,&action,NULL));
        }
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        int notify=listener(0);
        send_message(sockets[1],(struct message){.kind=LISTENER},notify);
        close(notify);
        if (available>=0) {
            struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
            limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
            int previous=-1, fd;
            while ((fd=fcntl(source,F_DUPFD_CLOEXEC,0))>=0) previous=fd;
            assert(errno==EMFILE && previous>=0);
            if (available) assert(!close(previous));
        }
        long result=syscall(SYS_getppid);
        if (!available || cancel==2 || cancel==7) assert(result==-1 && errno==(!available ? EMFILE : ECANCELED));
        else {
            assert(result>=0 && lseek((int)result,0,SEEK_CUR)==11);
            assert(fcntl((int)result,F_GETFD)==(cloexec ? FD_CLOEXEC : 0));
        }
        send_message(sockets[1],(struct message){.kind=ACK,.number=(int)result},-1);
        assert(receive_message(sockets[1],NULL).kind==DONE);
        _exit(0);
    }
    close(sockets[1]);
    int notify=-1;
    assert(receive_message(sockets[0],&notify).kind==LISTENER && notify>=0);
    struct seccomp_notif request={0};
    assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_RECV,&request));
    struct md_fd_order previous={0}, next={0};
    if (cancel==2 || cancel>=4) assert(md_fd_order_begin(files,&previous,NULL)==1 && !md_fd_order_start(&previous));
    struct md_fd_install operation={0};
    if (cancel>=6) {
        assert(!md_fd_install_borrow(&operation,&previous,notify,request.id,source,
            md_fs_anchor_object(origin),cloexec ? O_CLOEXEC : 0));
    } else assert(!md_fd_install_begin(&operation,files,notify,request.id,source,md_fs_anchor_object(origin),cloexec ? O_CLOEXEC : 0,NULL));
    assert(!md_fd_order_begin(files,&next,NULL));
    if (cancel>=3 && cancel<6) {
        uint64_t previous_notification=request.id;
        assert(!kill(child,SIGUSR1));
        char byte;
        assert(read(sockets[0],&byte,1)==1 && byte=='i');
        if (cancel==3) {
            assert(md_fd_install_progress(&operation)==MD_FD_INSTALL_CANCELLED);
            assert(!operation.retire_error && !operation.files.table);
            assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_READY && !md_fd_order_cancel(&next));
        } else if (cancel==4) {
            assert(md_fd_install_progress(&operation)==MD_FD_INSTALL_CANCELLED);
            assert(!operation.retire_error && !operation.files.table);
            assert(md_fd_order_state(&previous)==MD_RESOURCE_ORDER_RUNNING);
            assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
        }
        assert(write(sockets[0],"r",1)==1);
        /* EVENT_WAIT: SA_RESTART issues a new notification for the same result. */
        memset(&request,0,sizeof(request));
        assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_RECV,&request) && request.id!=previous_notification);
        assert(!md_fd_install_begin(&operation,files,notify,request.id,source,md_fs_anchor_object(origin),cloexec ? O_CLOEXEC : 0,NULL));
        if (cancel>=4) assert(!md_fd_order_cancel(&next) && !md_fd_order_complete(&previous));
        assert(!md_fd_order_begin(files,&next,NULL));
    }
    int status, result;
    if (cancel==1) {
        assert(!kill(child,SIGKILL));
        assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        assert(md_fd_install_progress(&operation)==MD_FD_INSTALL_CANCELLED);
    } else {
        if (cancel==2 || cancel==7) {
            if (cancel==2) assert(md_fd_install_progress(&operation)==MD_FD_INSTALL_WAIT);
            assert(!md_fd_install_cancel(&operation));
            assert(md_fd_order_state(&previous)==MD_RESOURCE_ORDER_RUNNING);
            assert(!md_fd_order_complete(&previous));
            result=-ECANCELED;
        } else {
            result=md_fd_install_progress(&operation);
            assert(available ? result>=0 : result==-EMFILE);
            if (result>=0) {
                unsigned flags;
                struct md_fs_anchor *actual=md_fd_origins_get(files,result,&flags);
                assert(actual==origin && flags==(cloexec ? FD_CLOEXEC : 0));
                md_fs_anchor_release(actual);
            }
        }
        if (result<0) {
            struct seccomp_notif_resp reply={.id=request.id,.error=result};
            assert(!ioctl(notify,SECCOMP_IOCTL_NOTIF_SEND,&reply));
        }
        struct message accepted=receive_message(sockets[0],NULL);
        assert(accepted.kind==ACK && accepted.number==(result<0 ? -1 : result));
        send_message(sockets[0],(struct message){.kind=DONE},-1);
        assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    }
    assert(!operation.files.table && !operation.retire_error && !md_fd_install_cancel(&operation));
    if (cancel==6) {
        assert(md_fd_order_state(&previous)==MD_RESOURCE_ORDER_RUNNING);
        assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_QUEUED);
        assert(!md_fd_order_complete(&previous));
    }
    assert(md_fd_order_state(&next)==MD_RESOURCE_ORDER_READY && !md_fd_order_cancel(&next));
    close(notify); close(sockets[0]); close(source);
    md_fd_origins_release(files); md_fs_anchor_release(origin); md_mount_namespace_release(view);
    assert(descriptors()==before);
}
int main(int argc, char **argv) {
    setvbuf(stdout,NULL,_IOLBF,0);
    assert(argc==2 || (argc==3 && (!strcmp(argv[2],"--kernel-parcel") || !strcmp(argv[2],"--origin-messages"))));
    assert(!mkdir(argv[1],0700));
    alarm(30);
    if (argc==3) {
        if (!strcmp(argv[2],"--origin-messages")) origin_message_experiment(argv[1]);
        else parcel_experiment(argv[1]);
        assert(!rmdir(argv[1]));
        return 0;
    }
    unsigned before=descriptors();
    test_ipc_connections(argv[1]); assert(descriptors()==before);
    scenario(SPLIT,argv[1]);
    puts("PASS split ADDFD: CLONE_FILES peer replaces same-inode description before reply");
    scenario(ATOMIC,argv[1]);
    puts("PASS atomic ADDFD_SEND: recipient can reuse FD before later metadata publication");
    scenario(CANCEL_SPLIT,argv[1]);
    puts("PASS cancelled split delivery retains installed FD despite EINTR and stale notification");
    scenario(CANCEL_ATOMIC,argv[1]);
    puts("PASS cancelled atomic delivery leaves prior destination untouched");
    scenario(PATH_FD,argv[1]);
    puts("PASS ADDFD rejects O_PATH without replacing destination");
    scenario(RIGHTS,argv[1]);
    puts("PASS SCM_RIGHTS: shared-table replacement precedes receiver acknowledgement");
    scenario(ORDERED_ATOMIC,argv[1]);
    puts("PASS ordered ADDFD_SEND: origin published before an intercepted shared-table replacement");
    scenario(ORDERED_RIGHTS,argv[1]);
    puts("PASS ordered SCM_RIGHTS: exact origins follow kernel completion, independent table progresses");
    for (int protected=0;protected<2;++protected) for (int cloexec=0;cloexec<2;++cloexec) {
        atomic_install(argv[1],0,protected,cloexec,0);
        atomic_install(argv[1],1,protected,cloexec,0);
        atomic_install(argv[1],-1,protected,cloexec,1);
        atomic_install(argv[1],-1,protected,cloexec,2);
        atomic_install(argv[1],1,protected,cloexec,3);
        atomic_install(argv[1],1,protected,cloexec,4);
        atomic_install(argv[1],1,protected,cloexec,5);
        atomic_install(argv[1],1,protected,cloexec,6);
        atomic_install(argv[1],1,protected,cloexec,7);
    }
    puts("PASS production atomic FD installation: protected receivers, 0/1 slots, CLOEXEC, origin ordering, queued cancellation, dead recipient and signal restart");
    retained_receipt(argv[1],0,0);
    retained_receipt(argv[1],1,0);
    retained_receipt(argv[1],2,0);
    retained_receipt(argv[1],2,1);
    puts("PASS retained kernel receipt after CLONE_FILES receiver death: no delivery, one/two FDs and truncated delivery");
    origin_vector_handoff(argv[1],4,0,0,-1);
    origin_vector_handoff(argv[1],253,0,0,-1);
    origin_vector_handoff(argv[1],253,1,0,-1);
    puts("PASS origin-vector kernel transfer: 253 FDs, two mounts of one inode, unknown origins, sender exit, peek, truncation and EOF");
    origin_vector_handoff(argv[1],5,0,1,-1);
    origin_vector_handoff(argv[1],253,0,1,-1);
    origin_vector_handoff(argv[1],253,1,1,-1);
    puts("PASS vector receipts after CLONE_FILES receiver exit: 253 FDs, peek, truncation, zero delivery and queued mutation ordering");
    const int slots[]={0,1,2,3,4,252,253,254};
    for (unsigned i=0;i<sizeof(slots)/sizeof(*slots);++i) {
        origin_vector_handoff(argv[1],253,0,1,slots[i]);
        origin_vector_handoff(argv[1],253,1,1,slots[i]);
    }
    puts("PASS vector receipts under RLIMIT_NOFILE: exact installed prefix, peek, control truncation, terminal receiver and EOF");
    for (int mode=0;mode<4;++mode) for (unsigned slots=0;slots<2;++slots)
        for (int path=0;path<2;++path) mapped_return(argv[1],mode,slots,path);
    puts("PASS native FD publication boundaries and descriptor cleanup");
    assert(!rmdir(argv[1]));
    return 0;
}
