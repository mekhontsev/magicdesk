#define _GNU_SOURCE
#include "task_wait.h"
#include "fd_selector.h"
#include "fs_worker.h"
#include "fs_service.h"
#include "fs_engine.h"
#include "task_resources.h"
#include "event_wait.h"
#include "interception_buffers.h"
#include "fs_buffer.h"
#include "fs_wire.h"
#include "fs_buffer_service.h"
#include "fd_mutation.h"
#include "fd_channel.h"
#include "received_fds.h"
#include "ipc_credentials.h"
#include "proc_file.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <elf.h>
#include <asm/ptrace.h>
#include <signal.h>
#include <sched.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

union frame {
    uint64_t words[8];
    struct { char path[40]; uint64_t device, inode; uint32_t type, reserved; } result;
};
_Static_assert(sizeof(union frame)==64,"register frame");
extern void md_lifecycle_bridge(union frame *);
extern char md_lifecycle_bridge_trap[];
extern long md_lifecycle_open(const char *, int);
extern char md_lifecycle_open_gate[],md_lifecycle_open_return[];

enum cancellation { NORMAL, BEFORE_ADMISSION, BEFORE_DELIVERY, DURING_WORK,
    OPEN_ENTRY, OPEN_EXIT, OPEN_FILTER, OPEN_RACE };

#define BUFFER_SETUP 0x4d444255
static int setup_listener=-1;

struct payload {
    unsigned kind;
    char path[PATH_MAX];
    union frame reply;
    pid_t peer;
    unsigned peer_before, peer_after;
    int reopen_error;
    int cancellation, survivor_fd, unsigned_argument;
};
static void object_path(char *path) {
    path[0]='/'; memset(path+1,'x',200); path[201]=0;
}

static int wait_child(pid_t pid) {
    /* EVENT_WAIT: exact child stop/exit; the fixture alarm bounds failure. */
    int status; pid_t result;
    do { result=waitpid(pid,&status,__WALL); } while (result<0 && errno==EINTR);
    assert(result==pid); return status;
}
static int mapping_listener(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_prctl,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,BUFFER_SETUP,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int fd=(int)syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd>=0); return fd;
}
static void no_socket(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,3,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_connect,2,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_recvmsg,1,0),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void kill_open(void) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_openat,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static unsigned low_fds(void) {
    unsigned count=0;
    for (int i=0;i<64;++i) if (fcntl(i,F_GETFD)>=0) ++count;
    return count;
}
static unsigned all_fds(void) {
    DIR *directory=opendir("/proc/self/fd"); assert(directory);
    unsigned count=0; struct dirent *entry;
    while ((entry=readdir(directory)))
        if (strcmp(entry->d_name,".") && strcmp(entry->d_name,"..")) ++count;
    assert(!closedir(directory)); return count;
}
static int shared_peer(void *context) {
    struct payload *data=context;
    alarm(30);
    sigset_t set; sigemptyset(&set); sigaddset(&set,SIGUSR1);
    int signal;
    /* EVENT_WAIT: supervisor releases the surviving CLONE_FILES peer only
     * after receiver death/exit; alarm bounds an abandoned fixture. */
    assert(!sigwait(&set,&signal) && signal==SIGUSR1);
    if (data->survivor_fd>=0) {
        struct stat st;
        assert(low_fds()==data->peer_before+1 && !fstat(data->survivor_fd,&st));
        assert(st.st_dev==data->reply.result.device && st.st_ino==data->reply.result.inode
            && (st.st_mode&S_IFMT)==data->reply.result.type);
        assert(fcntl(data->survivor_fd,F_GETFD)==FD_CLOEXEC);
        assert(fcntl(data->survivor_fd,F_GETFL)&O_PATH);
        assert(!close(data->survivor_fd));
    }
    data->peer_after=low_fds();
    return data->peer_after==data->peer_before ? 0 : 98;
}
static void receiver(int protected, int slots, unsigned kind, pid_t parent,
        struct payload *data, int shared) {
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=parent) _exit(99);
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit) && limit.rlim_max>=64);
    limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
    int last=-1,next;
    while ((next=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) last=next;
    assert(errno==EMFILE && last>=0);
    if (slots) assert(!close(last));
    no_socket();
    unsigned before=low_fds();
    if (shared) {
        sigset_t set; sigemptyset(&set); sigaddset(&set,SIGUSR1);
        assert(!sigprocmask(SIG_BLOCK,&set,NULL));
        size_t size=65536;
        void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(stack!=MAP_FAILED);
        data->peer_before=before;
        data->peer=clone(shared_peer,(char *)stack+size,CLONE_FILES|SIGCHLD,data);
        assert(data->peer>0 && !munmap(stack,size));
    }
    data->kind=kind; object_path(data->path);
    union frame request={.words={1,strlen(data->path)+1}};
    md_lifecycle_bridge(&request);
    assert(request.words[0]==3 && low_fds()==before && !strncmp(data->reply.result.path,"/proc/",6));
    if (data->cancellation>=OPEN_ENTRY) {
        if (data->cancellation==OPEN_FILTER) kill_open();
        md_lifecycle_open(data->reply.result.path,data->unsigned_argument);
        _exit(98);
    }
    int fd=open(data->reply.result.path,O_PATH|O_CLOEXEC);
    int result=fd<0 ? -errno : fd;
    if (slots) {
        struct stat st; assert(fd>=0 && !fstat(fd,&st));
        assert(st.st_dev==data->reply.result.device && st.st_ino==data->reply.result.inode
            && (st.st_mode&S_IFMT)==data->reply.result.type && low_fds()==before+1);
    } else assert(fd==-1 && errno==EMFILE && low_fds()==before);
    union frame receipt={.words={2,(uint64_t)(long)result}};
    md_lifecycle_bridge(&receipt);
    if (fd>=0) assert(!close(fd));
    assert(low_fds()==before);
    _exit(0);
}
static void exec_receiver(int protected, int slots, unsigned kind, pid_t parent,
        int provider_fd, size_t size, int shared) {
    char k[16],p[16],s[16],owner[16],path[80],bytes[32],share[16];
    snprintf(k,sizeof(k),"%u",kind); snprintf(p,sizeof(p),"%d",protected);
    snprintf(s,sizeof(s),"%d",slots); snprintf(owner,sizeof(owner),"%d",parent);
    snprintf(path,sizeof(path),"/proc/%d/fd/%d",parent,provider_fd);
    snprintf(bytes,sizeof(bytes),"%zu",size); snprintf(share,sizeof(share),"%d",shared);
    char *argv[]={"/proc/self/exe","--receiver",k,p,s,owner,path,bytes,share,NULL};
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    no_socket();
    assert(execv(argv[0],argv)==0);
    _exit(99);
}
static void map_receiver(char **argv) {
    size_t size=strtoul(argv[7],NULL,10);
    assert(size>=sizeof(struct payload));
    /* Probe only: proc reopening needs open permission on the memfd inode,
     * unlike kernel descriptor transfer. It is not the setup transport. */
    int probe=open(argv[6],O_RDWR|O_CLOEXEC),reopen_error=probe<0 ? errno : 0;
    if (probe>=0) assert(!close(probe));
    int fd=(int)syscall(SYS_prctl,BUFFER_SETUP,size,0,0,0);
    assert(fd>=0);
    struct stat st; assert(!fstat(fd,&st) && st.st_size==(off_t)size);
    int seals=F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL;
    assert(fcntl(fd,F_GET_SEALS)==seals);
    struct payload *data=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    assert(data!=MAP_FAILED && !close(fd));
    data->reopen_error=reopen_error;
    receiver(atoi(argv[3]),atoi(argv[4]),(unsigned)atoi(argv[2]),atoi(argv[5]),data,atoi(argv[8]));
}
static void read_frame(pid_t child, struct user_pt_regs *r, union frame *f,
        struct md_interception_buffers *bootstrap) {
    int status=wait_child(child);
    if ((unsigned)status>>16==PTRACE_EVENT_EXEC) {
        assert(bootstrap && !ptrace(PTRACE_CONT,child,0,0));
        /* EVENT_WAIT: the new image requests its mapping descriptor; timeout
         * fails setup, never authorizes an unobserved receiver. */
        assert(md_event_wait_fd(setup_listener,POLLIN,md_event_now()+5000000000LL)>=0);
        struct seccomp_notif request={0};
        assert(!ioctl(setup_listener,SECCOMP_IOCTL_NOTIF_RECV,&request));
        assert(request.pid==(unsigned)child && request.data.nr==SYS_prctl
            && request.data.args[0]==BUFFER_SETUP && request.data.args[1]==md_buffers_size(bootstrap));
        struct seccomp_notif_addfd add={.id=request.id,.srcfd=md_buffers_fd(bootstrap),
            .flags=SECCOMP_ADDFD_FLAG_SEND,.newfd_flags=O_CLOEXEC};
        assert(ioctl(setup_listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add)>=0);
        status=wait_child(child);
    }
    assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGTRAP);
    struct iovec v={r,sizeof(*r)};
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&v) && v.iov_len==sizeof(*r));
    assert(r->pc==(uintptr_t)md_lifecycle_bridge_trap);
    memcpy(f->words,r->regs,sizeof(*f));
}
static void resume_frame(pid_t child, struct user_pt_regs *r, const union frame *f) {
    memcpy(r->regs,f->words,sizeof(*f)); r->pc+=4;
    struct iovec v={r,sizeof(*r)};
    assert(!ptrace(PTRACE_SETREGSET,child,(void *)NT_PRSTATUS,&v));
    assert(!ptrace(PTRACE_CONT,child,0,0));
}
static long terminal_open(pid_t child, int cancellation, int slots, const struct md_fd_mutation *mutation) {
    int status=wait_child(child);
    assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGTRAP && !((unsigned)status>>16));
    struct user_pt_regs r;
    struct iovec v={&r,sizeof(r)};
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&v));
    assert(r.pc==(uintptr_t)md_lifecycle_open_gate && r.regs[8]==SYS_openat);
    r.pc+=4; assert(!ptrace(PTRACE_SETREGSET,child,(void *)NT_PRSTATUS,&v));
    assert(!ptrace(PTRACE_SYSCALL,child,0,0));
    status=wait_child(child);
    assert(WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
    if (cancellation==OPEN_ENTRY) assert(!kill(child,SIGKILL));
    else {
        assert(!ptrace(PTRACE_SYSCALL,child,0,0));
        if (cancellation==OPEN_RACE) assert(!kill(child,SIGKILL));
    }
    status=wait_child(child);
    if (WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80)) {
        assert(cancellation==OPEN_EXIT || cancellation==OPEN_RACE || cancellation==OPEN_FILTER);
        if (cancellation==OPEN_FILTER) {
            assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&v));
            printf("PROBE filtered open syscall-exit return=%lld\n",(long long)r.regs[0]);
            assert(!ptrace(PTRACE_CONT,child,0,0));
        } else assert(!kill(child,SIGKILL));
        status=wait_child(child);
    }
    assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_EXIT);
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&v));
    long result;
    assert(md_fd_mutation_terminal_open(mutation,r.regs[0],&result));
    printf("PROBE terminal open phase=%d pc=%#llx register=%lld publication=%ld nr=%llu\n",
        cancellation,r.pc,(long long)r.regs[0],result,r.regs[8]);
    assert(r.pc==(uintptr_t)md_lifecycle_open_return && r.regs[8]==SYS_openat);
    if (cancellation==OPEN_ENTRY || cancellation==OPEN_FILTER) assert(result<0);
    else if (cancellation==OPEN_EXIT) assert(slots ? result>=0 : result==-EMFILE);
    assert(!ptrace(PTRACE_CONT,child,0,0));
    status=wait_child(child);
    assert(WIFSIGNALED(status) && WTERMSIG(status)==(cancellation==OPEN_FILTER ? SIGSYS : SIGKILL));
    return result;
}
struct request {
    struct md_fs_work work;
    struct md_fs_context context;
    struct md_fs_request request;
    struct md_fs_result result;
    struct md_fd_order order;
    struct md_fd_mutation mutation;
    struct md_fs_anchor *origin;
    struct md_interception_buffer buffer;
    struct payload *data;
    char path[PATH_MAX];
    int gate, started;
    int calls, completed, cancelled;
};
static int capture(struct request *q, const struct payload *data, uint64_t length) {
    if (!length || length>sizeof(q->path) || data->kind>2) return -EINVAL;
    memcpy(q->path,data->path,(size_t)length);
    if (memchr(q->path,0,(size_t)length)!=q->path+length-1) return -EINVAL;
    q->request.path[0]=q->path;
    return 0;
}
static void execute(struct md_fs_work *work, struct md_filesystem *fs, int error) {
    struct request *q=(struct request *)work;
    assert(!q->calls++);
    q->result=(struct md_fs_result){.fd=-1,.error=error};
    if (error) { assert(!fs); return; }
    if (q->cancelled) { q->result.error=-ECANCELED; return; }
    error=md_fs_context_begin(fs,q->request.actor,0,&q->context);
    if (error) { q->result.error=error; return; }
    assert(md_fd_order_begin(q->context.task.files,&q->order,NULL)==1);
    assert(!md_fd_order_start(&q->order));
    if (q->gate>=0) {
        uint64_t one=1; assert(write(q->started,&one,sizeof(one))==sizeof(one));
        /* EVENT_WAIT: caller death while this exact request owns its context;
         * only the fixture gate admits execution, expiry fails the test. */
        assert(md_event_wait_fd(q->gate,POLLIN,md_event_now()+5000000000LL)>=0);
        assert(read(q->gate,&one,sizeof(one))==sizeof(one));
    }
    struct md_fs_output output={.origin=&q->origin};
    md_fs_context_execute(&q->context,&q->request,&q->result,&output);
    if (q->result.error) return;
    assert(q->result.fd>=0 && q->origin && !q->result.host_path);
    q->mutation.nr=SYS_openat;
    q->mutation.args[0]=q->data->unsigned_argument ? (unsigned)AT_FDCWD : (unsigned long)AT_FDCWD;
    q->mutation.args[2]=O_PATH|O_CLOEXEC;
    assert(!md_fd_origins_prepare(q->context.task.files,q->origin,FD_CLOEXEC,&q->mutation.update));
    struct stat st; assert(!fstat(q->result.fd,&st));
    /* Remove the guest name before publishing: the result lease, not a path
     * lookup at the receiving task, must keep the selected object alive. */
    struct md_fs_request unlink={.actor=q->request.actor,.operation=MD_FS_UNLINK,
        .directory={-1,-1},.path={q->request.path[0]},
        .flags=S_ISDIR(st.st_mode) ? AT_REMOVEDIR : 0};
    struct md_fs_result removed;
    md_fs_context_execute(&q->context,&unlink,&removed,NULL);
    assert(!removed.error);
    q->data->reply=(union frame){0};
    snprintf(q->data->reply.result.path,sizeof(q->data->reply.result.path),"/proc/%d/fd/%d",getpid(),q->result.fd);
    q->data->reply.result.device=st.st_dev; q->data->reply.result.inode=st.st_ino;
    q->data->reply.result.type=st.st_mode&S_IFMT;
}
static int complete(struct md_fs_work *work) {
    struct request *q=(struct request *)work;
    assert(q->calls==1 && !q->completed++);
    return 0;
}
static void wait_request(struct md_fs_worker *w, struct request *q) {
    /* EVENT_WAIT: worker publishes this exact request; expiry fails the fixture. */
    int64_t deadline=md_event_now()+5000000000LL;
    while (!q->completed) {
        assert(md_event_wait_fd(md_fs_worker_fd(w),POLLIN,deadline)>=0);
        assert(!md_fs_worker_dispatch(w));
    }
}
static void dispose(struct request *q, int fd) {
    assert(!md_fd_mutation_finish(&q->mutation,q->context.task.files,fd));
    if (q->order.table) assert(!md_fd_order_complete(&q->order));
    if (q->result.fd>=0) assert(!close(q->result.fd));
    md_fs_anchor_release(q->origin);
    md_fs_context_end(&q->context);
    assert(!md_buffer_release(&q->buffer));
}
static void prepare(const char *store, unsigned kind) {
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,0,&s));
    char path[PATH_MAX]; object_path(path);
    if (kind==1) assert(!md_inode_mkdir(s,-1,path,0700));
    else if (kind==2) assert(!md_inode_symlink(s,"missing",-1,path));
    else {
        int fd=md_inode_create(s,-1,path,0600); assert(fd>=0 && !close(fd));
    }
    md_inode_store_close(s);
}
static void path_request(const char *store, unsigned kind, int protected, int slots,
        int cancelled, int shared, int exec_image, int unsigned_argument) {
    prepare(store,kind);
    unsigned initial=all_fds();
    struct md_interception_buffers *pool=NULL;
    struct md_interception_buffer task={0},blocked={0};
    struct md_buffer_span span;
    assert(!md_buffers_create(1,sizeof(struct payload),sizeof(struct payload),0,&pool));
    assert(!md_buffers_take(pool,&task) && !md_buffer_span(&task,&span));
    uint64_t generation=task.generation;
    struct payload *data=span.data;
    data->cancellation=cancelled; data->survivor_fd=-1;
    data->unsigned_argument=unsigned_argument;
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(99);
        int fd=md_buffers_fd(pool); assert(!close(fd));
        assert(!close(setup_listener));
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        if (exec_image) exec_receiver(protected,slots,kind,parent,fd,md_buffers_size(pool),shared);
        receiver(protected,slots,kind,parent,data,shared);
    }
    int status=wait_child(child); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP);
    unsigned options=PTRACE_O_EXITKILL|PTRACE_O_TRACEEXEC;
    if (cancelled>=OPEN_ENTRY) options|=PTRACE_O_TRACEEXIT|PTRACE_O_TRACESYSGOOD;
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,options));
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-dispatch-%d-%d",parent,child);
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(worker)};
    assert(resources.fs && resources.files);
    struct md_identity identity=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,child,child,&identity,&resources));
    assert(!ptrace(PTRACE_CONT,child,0,0));
    struct user_pt_regs regs; union frame message;
    read_frame(child,&regs,&message,pool); assert(message.words[0]==1 && data->kind==kind);
    int reopen_error=data->reopen_error;
    assert(shared ? data->peer>0 : !data->peer);
    struct request q={.work={.run=execute,.complete=complete},.cancelled=cancelled==1,
        .data=data,.gate=-1,.started=-1,
        .request={.actor=child,.operation=MD_FS_OPEN,.directory={-1,-1},
            .flags=O_PATH|O_NOFOLLOW|O_CLOEXEC}};
    assert(capture(&q,data,0)==-EINVAL && capture(&q,data,PATH_MAX+1)==-EINVAL);
    assert(capture(&q,data,message.words[1]-1)==-EINVAL);
    assert(!capture(&q,data,message.words[1]) && strlen(q.path)==201);
    /* Neither subsequent payload mutation nor caller death may retarget the
     * worker's already validated, owned request. */
    strcpy(data->path,"/changed-after-capture");
    assert(!md_buffer_pin(&task,&q.buffer));
    if (cancelled==1) {
        assert(!kill(child,SIGKILL)); status=wait_child(child);
        assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        md_task_registry_forget(tasks,child);
        assert(!md_buffer_release(&task));
        assert(md_buffers_take(pool,&blocked)==-EAGAIN);
    }
    if (cancelled==3) {
        q.gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        q.started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        assert(q.gate>=0 && q.started>=0);
    }
    assert(!md_fs_worker_submit(worker,&q.work));
    if (cancelled==3) {
        /* EVENT_WAIT: worker owns the context before the caller is killed;
         * expiry fails the fixture instead of assuming worker progress. */
        assert(md_event_wait_fd(q.started,POLLIN,md_event_now()+5000000000LL)>=0);
        assert(!kill(child,SIGKILL)); status=wait_child(child);
        assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
        md_task_registry_forget(tasks,child);
        assert(!md_buffer_release(&task));
        assert(md_buffers_take(pool,&blocked)==-EAGAIN);
        uint64_t one=1; assert(write(q.gate,&one,sizeof(one))==sizeof(one));
    }
    wait_request(worker,&q);
    if (cancelled==3) assert(!close(q.gate) && !close(q.started));
    int published=0;
    if (cancelled==1) assert(q.result.error==-ECANCELED && !q.context.authenticated);
    else {
        assert(!q.result.error && q.context.actor==child && q.context.task.files==resources.files);
        /* Retirement/reuse of the registry entry cannot retarget a live result. */
        md_task_registry_forget(tasks,child);
        struct md_task_resources replacement={.fs=md_domain_new(),.files=md_fd_origins_new(),
            .mounts=resources.mounts};
        assert(replacement.fs && replacement.files);
        assert(!md_task_registry_publish(tasks,child,child,&identity,&replacement));
        assert(q.context.task.files==resources.files && q.context.task.files!=replacement.files);
        md_task_registry_forget(tasks,child);
        md_domain_release(replacement.fs); md_fd_origins_release(replacement.files);
        if (cancelled==2) {
            assert(!kill(child,SIGKILL)); status=wait_child(child);
            assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            assert(!md_buffer_release(&task));
            assert(md_buffers_take(pool,&blocked)==-EAGAIN);
        } else if (!cancelled || cancelled>=OPEN_ENTRY) {
            message=(union frame){.words={3}};
            resume_frame(child,&regs,&message);
            long fd;
            if (cancelled) fd=terminal_open(child,cancelled,slots,&q.mutation);
            else {
                read_frame(child,&regs,&message,NULL); assert(message.words[0]==2);
                fd=(long)message.words[1]; assert(slots ? fd>=0 : fd==-EMFILE);
            }
            assert(!md_fd_mutation_finish(&q.mutation,resources.files,fd));
            if (fd>=0) {
                published=1;
                unsigned flags=0;
                struct md_fs_anchor *origin=md_fd_origins_get(resources.files,(int)fd,&flags);
                assert(origin==q.origin && flags==FD_CLOEXEC); md_fs_anchor_release(origin);
            }
            assert(!md_fd_order_complete(&q.order));
            if (cancelled) data->survivor_fd=fd<0 ? -1 : (int)fd;
            else {
                resume_frame(child,&regs,&message);
                status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
            }
        }
    }
    if (shared) {
        assert(!kill(data->peer,SIGUSR1)); status=wait_child(data->peer);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
        assert(data->peer_after==data->peer_before);
    }
    assert(!md_buffer_release(&task));
    assert(md_buffers_take(pool,&blocked)==-EAGAIN);
    dispose(&q,-1);
    assert(!md_buffers_take(pool,&blocked) && blocked.generation!=generation);
    for (size_t i=0;i<span.size;++i) assert(!((unsigned char *)data)[i]);
    assert(!md_buffer_release(&blocked)); md_buffers_release(pool);
    md_domain_release(resources.fs); md_fd_origins_release(resources.files);
    assert(!md_fs_worker_stop(worker));
    md_task_registry_destroy(tasks); md_identity_release(&identity);
    if (cancelled==1) {
        struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,0,&s));
        assert(!md_inode_unlink(s,-1,q.path,kind==1 ? AT_REMOVEDIR : 0));
        md_inode_store_close(s);
    }
    assert(all_fds()==initial);
    printf("PASS filesystem worker O_PATH kind=%u protected=%d slots=%d cancelled=%d sharedFiles=%d exec=%d unsignedArgument=%d originPublished=%d noPrivateFD=1 sharedPayload=1 bootstrapProcError=%d\n",
        kind,protected,slots,cancelled,shared,exec_image,unsigned_argument,published,reopen_error);
}
struct queued {
    struct md_fs_work work;
    int gate, started, called, completed, error, callback_error;
};
static void queued_run(struct md_fs_work *work, struct md_filesystem *fs, int error) {
    struct queued *q=(struct queued *)work;
    assert(!q->called++ && (!!fs==!error)); q->error=error;
    if (!error && q->gate>=0) {
        uint64_t one=1; assert(write(q->started,&one,sizeof(one))==sizeof(one));
        /* EVENT_WAIT: fixture-controlled worker stall, released by the test;
         * timeout fails the test, never represents completed filesystem IO. */
        assert(md_event_wait_fd(q->gate,POLLIN,md_event_now()+5000000000LL)>=0);
        assert(read(q->gate,&one,sizeof(one))==sizeof(one));
    }
}
static int queued_complete(struct md_fs_work *work) {
    struct queued *q=(struct queued *)work;
    assert(q->called==1 && !q->completed++); return q->callback_error;
}
static int fail_worker(void *context, struct md_filesystem *fs, short events) {
    (void)context; (void)fs; return events ? -EIO : 0;
}
static void queue_failure(const char *store) {
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-dispatch-failure-%d",getpid());
    struct md_fs_worker *w=NULL;
    assert(!md_fs_worker_start(store,endpoint,NULL,NULL,NULL,NULL,&w));
    int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    int failed=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(gate>=0 && started>=0 && failed>=0);
    md_fs_worker_notifications(w,failed,NULL,fail_worker,NULL);
    struct queued jobs[96]={0};
    for (unsigned i=0;i<96;++i) {
        jobs[i]=(struct queued){.work={.run=queued_run,.complete=queued_complete},
            .gate=i ? -1 : gate,.started=started,.callback_error=i==2 ? -ENOMSG : 0};
        assert(!md_fs_worker_submit(w,&jobs[i].work));
        if (!i) assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
    }
    uint64_t one=1; assert(write(failed,&one,sizeof(one))==sizeof(one));
    assert(write(gate,&one,sizeof(one))==sizeof(one));
    unsigned count=0; int callback_error=0;
    int64_t deadline=md_event_now()+5000000000LL;
    /* EVENT_WAIT: all accepted work is completed or cancelled by worker failure;
     * a callback failure must not strand the remaining completion batch. */
    while (count<96 || !md_fs_worker_error(w)) {
        assert(md_event_wait_fd(md_fs_worker_fd(w),POLLIN,deadline)>=0);
        int error=md_fs_worker_dispatch(w);
        assert(!error || error==-EIO || error==-ENOMSG);
        callback_error|=error==-ENOMSG;
        count=0; for (unsigned i=0;i<96;++i) count+=jobs[i].completed;
    }
    unsigned cancelled=0;
    for (unsigned i=0;i<96;++i) { assert(!jobs[i].error || jobs[i].error==-EIO); cancelled+=jobs[i].error!=0; }
    struct queued rejected={.work={.run=queued_run,.complete=queued_complete}};
    assert(md_fs_worker_submit(w,&rejected.work)==-ESHUTDOWN && !rejected.called);
    assert(callback_error && cancelled && cancelled<96 && md_fs_worker_stop(w)==-EIO);
    assert(!close(gate) && !close(started) && !close(failed));
    printf("PASS worker queue accepted=96 completed=%u cancelled=%u callbackErrorPreserved=1\n",count,cancelled);
}
struct pause_job {
    struct md_fs_work work;
    atomic_int *allowed;
    atomic_int called;
    int error, completed, held;
};
static int pause_job_ready(struct md_fs_work *work) {
    return !((struct pause_job *)work)->held;
}
static void pause_job_run(struct md_fs_work *work, struct md_filesystem *fs, int error) {
    struct pause_job *job=(void *)work;
    assert(!!fs==!error && (error || atomic_load(job->allowed)));
    job->error=error; assert(!atomic_fetch_add(&job->called,1));
}
static int pause_job_complete(struct md_fs_work *work) {
    struct pause_job *job=(void *)work;
    assert(atomic_load(&job->called)==1 && !job->completed++); return 0;
}
static void pause_wait(struct md_fs_worker *worker, struct md_fs_worker_pause *pause) {
    int64_t deadline=md_event_now()+5000000000LL;
    /* EVENT_WAIT: this token's worker acknowledgement, not a settling interval.
     * Shutdown or expiry fails the requested quiescence. */
    while (!md_fs_worker_pause_state(pause)) {
        assert(md_event_wait_fd(md_fs_worker_fd(worker),POLLIN,deadline)>=0);
        assert(!md_fs_worker_dispatch(worker));
    }
    assert(md_fs_worker_pause_state(pause)==1);
}
static void worker_start_failure(const char *store) {
    unsigned before=all_fds();
    char endpoint[80],missing[PATH_MAX];
    snprintf(endpoint,sizeof(endpoint),"md-worker-start-%d",getpid());
    assert(snprintf(missing,sizeof(missing),"%s/missing-runtime",store)<PATH_MAX);
    struct md_fs_worker *worker=NULL;
    assert(md_fs_worker_start(missing,endpoint,NULL,NULL,NULL,NULL,&worker)==-ENOENT);
    assert(!worker && all_fds()==before);
    assert(md_fs_worker_start(store,"",NULL,NULL,NULL,NULL,&worker)==-EINVAL);
    assert(!worker && all_fds()==before);
    int listener=md_fs_listen(endpoint); assert(listener>=0);
    assert(md_fs_worker_start(store,endpoint,NULL,NULL,NULL,NULL,&worker)==-EADDRINUSE);
    assert(!worker && fcntl(listener,F_GETFD)>=0 && all_fds()==before+1);
    assert(!close(listener));
    assert(!md_fs_worker_start(store,endpoint,NULL,NULL,NULL,NULL,&worker));
    assert(!md_fs_worker_stop(worker) && all_fds()==before);
    puts("PASS worker runtime lifecycle: early/late startup failure, borrowed listener and clean restart");
}
static void worker_pause(const char *store) {
    unsigned before=all_fds();
    for (int stop=0;stop<2;++stop) {
        char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-worker-pause-%d-%d",getpid(),stop);
        struct md_fs_worker *worker=NULL;
        assert(!md_fs_worker_start(store,endpoint,NULL,NULL,NULL,NULL,&worker));
        struct md_fs_worker_pause pause={0}, other={0};
        atomic_int allowed=0;
        struct pause_job jobs[8]={0};
        assert(!md_fs_worker_pause_begin(worker,&pause));
        assert(md_fs_worker_pause_begin(worker,&other)==-EBUSY && !other.owner);
        pause_wait(worker,&pause);
        for (unsigned i=0;i<8;++i) {
            jobs[i]=(struct pause_job){.work={.run=pause_job_run,.complete=pause_job_complete},.allowed=&allowed};
            assert(!md_fs_worker_submit(worker,&jobs[i].work));
        }
        if (stop) {
            assert(!md_fs_worker_stop(worker) && !pause.owner);
            for (unsigned i=0;i<8;++i)
                assert(atomic_load(&jobs[i].called)==1 && jobs[i].error==-ESHUTDOWN && !jobs[i].completed);
        } else {
            /* A second worker on the same store must remain independent: the
             * pause retains no database lock or global filesystem exclusion. */
            char second_endpoint[80]; snprintf(second_endpoint,sizeof(second_endpoint),"md-worker-other-%d",getpid());
            struct md_fs_worker *second=NULL;
            assert(!md_fs_worker_start(store,second_endpoint,NULL,NULL,NULL,NULL,&second));
            atomic_int second_allowed=1;
            struct pause_job independent={.work={.run=pause_job_run,.complete=pause_job_complete},.allowed=&second_allowed};
            assert(!md_fs_worker_submit(second,&independent.work));
            int64_t deadline=md_event_now()+5000000000LL;
            while (!independent.completed) {
                assert(md_event_wait_fd(md_fs_worker_fd(second),POLLIN,deadline)>=0);
                assert(!md_fs_worker_dispatch(second));
            }
            assert(!md_fs_worker_stop(second));
            for (unsigned i=0;i<8;++i) assert(!atomic_load(&jobs[i].called));
            atomic_store(&allowed,1);
            assert(!md_fs_worker_pause_end(&pause));
            assert(md_fs_worker_pause_state(&pause)==-EINVAL);
            unsigned complete=0;
            while (complete<8) {
                assert(md_event_wait_fd(md_fs_worker_fd(worker),POLLIN,deadline)>=0);
                assert(!md_fs_worker_dispatch(worker));
                complete=0; for (unsigned i=0;i<8;++i) { complete+=jobs[i].completed; assert(!jobs[i].error); }
            }
            for (unsigned cycle=0;cycle<16;++cycle) {
                assert(!md_fs_worker_pause_begin(worker,&pause)); pause_wait(worker,&pause);
                assert(!md_fs_worker_pause_end(&pause));
            }
            struct pause_job held={.work={.run=pause_job_run,.complete=pause_job_complete,
                .ready=pause_job_ready},.allowed=&allowed,.held=1};
            struct pause_job free_job={.work={.run=pause_job_run,.complete=pause_job_complete},.allowed=&allowed};
            assert(!md_fs_worker_submit(worker,&held.work));
            assert(!md_fs_worker_submit(worker,&free_job.work));
            deadline=md_event_now()+5000000000LL;
            /* EVENT_WAIT: the second completion proves that the held first
             * completion does not block unrelated work on this same worker. */
            while (!free_job.completed) {
                assert(md_event_wait_fd(md_fs_worker_fd(worker),POLLIN,deadline)>=0);
                assert(!md_fs_worker_dispatch(worker));
            }
            assert(atomic_load(&held.called)==1 && !held.completed);
            struct pollfd quiet={md_fs_worker_fd(worker),POLLIN,0};
            assert(!poll(&quiet,1,0));
            held.held=0;
            assert(!md_fs_worker_dispatch(worker) && held.completed==1);
            int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
            assert(gate>=0 && started>=0);
            struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
            assert(!md_fs_worker_submit(worker,&blocked.work));
            assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
            assert(!md_fs_worker_pause_begin(worker,&pause));
            assert(!md_fs_worker_pause_state(&pause));
            assert(!md_fs_worker_pause_end(&pause));
            uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
            deadline=md_event_now()+5000000000LL;
            while (!blocked.completed) {
                assert(md_event_wait_fd(md_fs_worker_fd(worker),POLLIN,deadline)>=0);
                assert(!md_fs_worker_dispatch(worker));
            }
            assert(!blocked.error && !md_fs_worker_stop(worker));
            assert(!close(gate) && !close(started));
        }
        assert(all_fds()==before);
    }
    puts("PASS worker quiescence: confirmed pause, independent workers, deferred work, cancellation before acknowledgement, reuse and shutdown without FD leaks");
}
struct root_capture { struct md_fs_work work; struct md_fs_anchor *origin; int completed; };
static void root_run(struct md_fs_work *work, struct md_filesystem *fs, int error) {
    struct root_capture *r=(void *)work; assert(!error);
    struct md_fs_request q={.actor=getpid(),.operation=MD_FS_OPEN,.directory={-1,-1},
        .path={"/",""},.flags=O_PATH|O_DIRECTORY|O_CLOEXEC};
    struct md_fs_result result;
    struct md_fs_output output={.origin=&r->origin};
    md_fs_execute(fs,&q,&result,&output);
    assert(!result.error && result.fd>=0 && r->origin); assert(!close(result.fd));
}
static int root_complete(struct md_fs_work *work) {
    ((struct root_capture *)work)->completed=1; return 0;
}
static int buffer_callbacks,buffer_error;
static pid_t buffer_actor;
static struct md_fs_buffer_request *buffer_pending;
static struct md_fs_buffer_open buffered_open={.fd=-1};
static struct md_fs_result status_result;
static void buffer_completed(struct md_fs_buffer_request *request, pid_t actor, int error) {
    assert(request && actor==(buffer_actor ? buffer_actor : getpid())); ++buffer_callbacks; buffer_error=error;
    buffer_pending=md_fs_buffer_ack_pending(request) || md_fs_buffer_snapshot_pending(request)
        || md_fs_buffer_export_pending(request) || md_fs_buffer_task_pending(request)
        || md_fs_buffer_capture_pending(request) ? request : NULL;
    const struct md_fs_result *status=md_fs_buffer_status_result(request);
    if (status) { status_result=*status; return; }
    if (!error) assert(md_fs_buffer_open_take(request,&buffered_open)>=0);
}
static void worker_event(struct md_fs_worker *w) {
    /* EVENT_WAIT: exact completion on the retained worker; timeout fails. */
    assert(md_event_wait_fd(md_fs_worker_fd(w),POLLIN,md_event_now()+5000000000LL)>=0);
    assert(!md_fs_worker_dispatch(w));
}
static long status_call(struct md_fs_buffer_service *service,struct md_fs_worker *worker,
        struct md_guest_domain *fs,const struct md_fs_request *q) {
    int callbacks=buffer_callbacks;
    struct md_fs_buffer_request *r=NULL;
    assert(!md_fs_buffer_invoke_status(service,getpid(),fs,q,&r));
    assert(!md_fs_buffer_progress(service));
    while (buffer_callbacks==callbacks) worker_event(worker);
    assert(!buffer_pending && buffered_open.fd==-1);
    if (!buffer_error) assert(status_result.fd==-1 && !status_result.host_path);
    return buffer_error ? buffer_error : status_result.error;
}
static void status_delivery(struct md_fs_buffer_service *service,struct md_fs_worker *worker,
        struct md_task_resources *resources,struct md_resource_wakeup *token) {
    unsigned before=all_fds();
    char path[64]="/scalar-owned";
    struct md_fs_request q={.operation=MD_FS_MKDIR,.directory={-1,-1},.path={path},.mode=0700};
    struct md_fs_buffer_request *r=NULL;
    q.operation=MD_FS_OPEN;
    assert(md_fs_buffer_invoke_status(service,getpid(),resources->fs,&q,&r)==-EINVAL && !r);
    q.operation=MD_FS_MKDIR;
    for (unsigned cancelled=0;cancelled<2;++cancelled) {
        struct md_fd_order held={0};
        assert(md_fd_order_begin(resources->files,&held,token)==1 && !md_fd_order_start(&held));
        int callbacks=buffer_callbacks;
        assert(!md_fs_buffer_invoke_status(service,getpid(),resources->fs,&q,&r));
        strcpy(path,"/scalar-replaced");
        assert(!md_fs_buffer_progress(service) && buffer_callbacks==callbacks);
        if (cancelled) md_fs_buffer_cancel(&r);
        assert(!md_fd_order_complete(&held));
        assert(!md_fs_buffer_progress(service));
        if (!cancelled) {
            while (buffer_callbacks==callbacks) worker_event(worker);
            assert(!buffer_error && !status_result.error && status_result.fd==-1);
            r=NULL;
        } else assert(!r && buffer_callbacks==callbacks);
        strcpy(path,"/scalar-owned");
    }
    assert(status_call(service,worker,resources->fs,&q)==-EEXIST);
    q.operation=MD_FS_RENAME; q.path[1]="/scalar-moved"; q.mode=0;
    assert(!status_call(service,worker,resources->fs,&q));
    assert(status_call(service,worker,resources->fs,&q)==-ENOENT);
    q.operation=MD_FS_UNLINK; q.path[0]="/scalar-replaced"; q.path[1]=NULL; q.flags=AT_REMOVEDIR;
    assert(status_call(service,worker,resources->fs,&q)==-ENOENT);
    q.path[0]="/scalar-moved";
    int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(gate>=0 && started>=0);
    struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
    assert(!md_fs_worker_submit(worker,&blocked.work));
    /* EVENT_WAIT: worker acknowledges the gate before accepted mutation cancellation. */
    assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
    int callbacks=buffer_callbacks;
    assert(!md_fs_buffer_invoke_status(service,getpid(),resources->fs,&q,&r));
    assert(!md_fs_buffer_progress(service)); md_fs_buffer_cancel(&r);
    struct queued sentinel={.work={.run=queued_run,.complete=queued_complete},.gate=-1,.started=-1};
    assert(!md_fs_worker_submit(worker,&sentinel.work));
    uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
    while (!sentinel.completed) worker_event(worker);
    assert(blocked.completed && buffer_callbacks==callbacks && !r);
    assert(!close(gate) && !close(started));
    assert(status_call(service,worker,resources->fs,&q)==-ENOENT);
    q.path[0]="relative"; q.directory[0]=123;
    assert(status_call(service,worker,resources->fs,&q)==MD_FS_BUFFER_NEEDS_TASK);
    assert(all_fds()==before);
    puts("PASS scalar receipts: owned operands, exact errno, queued capture, cancellation before/after admission, no replay or mapped buffer");
}
static struct md_fs_buffer_request *buffer_request(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, struct md_guest_domain *fs,
        const struct md_interception_buffer *buffer, unsigned operation) {
    int callbacks=buffer_callbacks;
    struct md_fs_buffer_request *request=NULL;
    assert(!md_fs_buffer_begin(service,getpid(),fs,operation,buffer,&request));
    assert(!md_fs_buffer_progress(service));
    while (buffer_callbacks==callbacks) worker_event(worker);
    assert(!buffer_error);
    return buffer_pending;
}
static void owned_snapshot_delivery(struct md_fs_buffer_service *service,struct md_fs_worker *worker,
        struct md_task_resources *resources,struct md_resource_wakeup *token) {
    unsigned before=all_fds();
    struct md_fs_buffer_request *request=NULL;
    assert(md_fs_buffer_snapshot_copy(service,getpid(),resources->fs,O_RDWR,"x",1,&request)==-EACCES);
    for (unsigned cancel=0;cancel<2;++cancel) for (unsigned empty=0;empty<2;++empty) {
        struct md_fs_worker_pause pause={0};
        assert(!md_fs_worker_pause_begin(worker,&pause)); pause_wait(worker,&pause);
        struct md_fd_order files={0};
        assert(md_fd_order_begin(resources->files,&files,token)==1 && !md_fd_order_start(&files));
        char bytes[8192]; memset(bytes,'a',sizeof(bytes));
        size_t size=empty ? 0 : sizeof(bytes);
        int callbacks=buffer_callbacks;
        assert(!md_fs_buffer_snapshot_copy(service,getpid(),resources->fs,O_RDONLY,bytes,size,&request));
        memset(bytes,'b',sizeof(bytes));
        assert(!md_fs_buffer_progress(service) && buffer_callbacks==callbacks);
        if (cancel) md_fs_buffer_cancel(&request);
        struct queued sentinel={.work={.run=queued_run,.complete=queued_complete},.gate=-1,.started=-1};
        assert(!md_fs_worker_submit(worker,&sentinel.work));
        assert(!md_fs_worker_pause_end(&pause));
        while (!sentinel.completed) worker_event(worker);
        assert(buffer_callbacks==callbacks+!cancel);
        if (!cancel) {
            assert(!buffer_error && buffered_open.fd>=0);
            int fd=buffered_open.fd;
            assert(read(fd,bytes,sizeof(bytes))==(ssize_t)size);
            for (size_t i=0;i<size;++i) assert(bytes[i]=='a');
            assert(read(fd,bytes,1)==0 && lseek(fd,0,SEEK_SET)==0);
            assert(write(fd,"x",1)==-1 && errno==EBADF);
            assert((fcntl(fd,F_GETFL)&O_ACCMODE)==O_RDONLY && (fcntl(fd,F_GETFD)&FD_CLOEXEC));
            md_fs_buffer_open_release(&buffered_open); request=NULL;
        }
        assert(!md_fd_order_complete(&files) && all_fds()==before);
    }
    puts("PASS owned snapshot receipt: paused worker, immutable bytes, empty/read-only seekable results and cancelled custody");
}
static void captured_stat_delivery(struct md_fs_buffer_service *service,struct md_fs_worker *worker,
        struct md_task_resources *resources,struct md_resource_wakeup *token,struct md_fs_anchor *base) {
    unsigned before=all_fds();
    struct md_fs_buffer_request *request=NULL;
    assert(md_fs_buffer_stat_begin(service,getpid(),resources->fs,-1,&request)==-EINVAL && !request);
    for (unsigned native=0;native<2;++native) for (unsigned cancel=0;cancel<2;++cancel) {
        struct md_fs_worker_pause pause={0};
        assert(!md_fs_worker_pause_begin(worker,&pause)); pause_wait(worker,&pause);
        struct md_fd_order files={0};
        assert(md_fd_order_begin(resources->files,&files,token)==1 && !md_fd_order_start(&files));
        int fd=native ? eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK)
            : fcntl(md_fs_anchor_fd(base),F_DUPFD_CLOEXEC,3);
        assert(fd>=0);
        struct stat observed; assert(!fstat(fd,&observed));
        int callbacks=buffer_callbacks;
        assert(!md_fs_buffer_stat_begin(service,getpid(),resources->fs,fd,&request));
        assert(!md_fs_buffer_progress(service) && buffer_callbacks==callbacks);
        assert(md_fs_worker_pause_state(&pause)==1 && fcntl(fd,F_GETFD)>=0);
        if (cancel) md_fs_buffer_cancel(&request);
        struct queued sentinel={.work={.run=queued_run,.complete=queued_complete},.gate=-1,.started=-1};
        assert(!md_fs_worker_submit(worker,&sentinel.work));
        assert(!md_fs_worker_pause_end(&pause));
        while (!sentinel.completed) worker_event(worker);
        assert(buffer_callbacks==callbacks+!cancel && fcntl(fd,F_GETFD)==-1 && errno==EBADF);
        if (!cancel) {
            assert(!buffer_error && !status_result.error && !status_result.host_path);
            if (native) assert(status_result.info.mode==observed.st_mode
                && status_result.info.inode==observed.st_ino && status_result.info.uid==observed.st_uid);
            else assert(S_ISDIR(status_result.info.mode) && status_result.info.uid==getuid());
            request=NULL;
        }
        assert(!md_fd_order_complete(&files));
        assert(all_fds()==before);
    }
    int fd=eventfd(0,EFD_CLOEXEC); assert(fd>=0);
    struct md_guest_domain *other=md_domain_new(); assert(other);
    assert(md_fs_buffer_stat_begin(service,getpid(),other,fd,&request)==-ESTALE && !request);
    assert(fcntl(fd,F_GETFD)>=0 && !close(fd));
    md_domain_release(other);
    assert(all_fds()==before);
    puts("PASS captured metadata receipt: paused worker, retained descriptor, no table wait, logical/native metadata, cancellation and failed admission custody");
}
static void buffer_channel_check(struct md_fs_buffer_service *service, struct md_fs_worker *worker,
        struct md_task_registry *tasks, struct md_task_resources *resources, const struct md_identity *identity,
        const struct md_interception_buffer *buffer, struct md_fs_buffer_frame *frame) {
    unsigned before=all_fds();
    struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
    int endpoint=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0); assert(endpoint>=0);
    int input=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(input>=0);
    int command[2],ack[2]; assert(!pipe2(command,O_CLOEXEC) && !pipe2(ack,O_CLOEXEC));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_DUMPABLE,0) && !prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        char action=0; assert(write(ack[1],&action,1)==1);
        /* EVENT_WAIT: parent issues one exact observer/export action at a time;
         * the fixture alarm and parent-owned cancellation bound failure. */
        while (read(command[0],&action,1)==1 && action!='q') {
            if (action=='o' || action=='d') {
                if (action=='d') {
                    struct sock_filter code[]={
                        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getsockopt,0,1),
                        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
                        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
                    };
                    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
                    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
                }
                struct md_fd_channel_observation *o=&frame->channel.observation;
                *o=(struct md_fd_channel_observation){.length=sizeof(o->cookie)};
                int flags=fcntl(frame->channel.number,F_GETFD);
                if (flags<0) o->error=-errno;
                else {
                    o->flags=(unsigned)flags;
                    if (getsockopt(frame->channel.number,SOL_SOCKET,SO_COOKIE,&o->cookie,&o->length)) o->error=-errno;
                }
            } else {
                assert(action=='s' && frame->export.number==endpoint && frame->export.mask==1);
                struct md_fs_rights rights={.fd={input},.count=1};
                assert(md_fs_send(endpoint,&frame->export.request,sizeof(uint64_t),&rights)==sizeof(uint64_t));
            }
            assert(write(ack[1],&action,1)==1);
        }
        _exit(0);
    }
    char action; assert(read(ack[0],&action,1)==1);
    assert(!md_task_registry_publish(tasks,child,child,identity,resources));
    struct md_fd_origin_update update={0};
    assert(!md_fd_origins_prepare_channel(resources->files,channel,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,endpoint);
    buffer_actor=child;
    for (unsigned mode=0;mode<3;++mode) {
        assert(!md_fd_origins_abandon_range(resources->files,endpoint,endpoint));
        *frame=(struct md_fs_buffer_frame){.input={.base={input,-1}}};
        int callbacks=buffer_callbacks;
        struct md_fs_buffer_request *request=NULL;
        assert(!md_fs_buffer_begin(service,child,resources->fs,MD_FS_WATCH_CONTAINS,buffer,&request));
        assert(!md_fs_buffer_progress(service));
        assert(buffer_callbacks==callbacks+1 && buffer_error==MD_FS_BUFFER_CHANNEL_CHECK && buffer_pending==request);
        assert(frame->channel.number==endpoint);
        if (mode==1) {
            action='d'; assert(write(command[1],&action,1)==1 && read(ack[0],&action,1)==1);
            assert(frame->channel.observation.error==-EPERM);
            assert(!md_fs_buffer_channel_observed(request));
            assert(buffer_callbacks==callbacks+2 && buffer_error==-EPERM && !buffer_pending);
        } else if (mode==2) {
            assert(!kill(child,SIGKILL));
            int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
            md_fs_buffer_cancel(&request); assert(!request);
        } else {
            action='o'; assert(write(command[1],&action,1)==1 && read(ack[0],&action,1)==1);
            assert(!md_fs_buffer_channel_observed(request));
            assert(buffer_callbacks==callbacks+2 && buffer_error==MD_FS_BUFFER_EXPORT && buffer_pending==request);
            action='s'; assert(write(command[1],&action,1)==1 && read(ack[0],&action,1)==1);
            assert(!md_fs_buffer_export_sent(request,0));
            while (buffer_callbacks==callbacks+2) worker_event(worker);
            assert(!buffer_error && !buffer_pending && !frame->reply.result.error && frame->reply.result.position==0);
        }
    }
    buffer_actor=0; buffer_pending=NULL; md_task_registry_forget(tasks,child);
    assert(!md_fd_origins_close_range(resources->files,endpoint,endpoint,0));
    md_fd_channel_release(channel);
    assert(!close(endpoint) && !close(input) && !close(command[0]) && !close(command[1])
        && !close(ack[0]) && !close(ack[1]));
    assert(all_fds()==before);
    puts("PASS mapped channel check: protected actor, exact kernel cookie, resumed export, denied observation and cancelled request");
}
static void buffer_snapshot_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, struct md_guest_domain *fs,
        const struct md_interception_buffer *buffer, struct md_fs_buffer_frame *frame) {
    unsigned initial=all_fds();
    for (unsigned mode=0;mode<8;++mode) {
        struct md_fs_buffer_request *request=NULL;
        unsigned flags=mode==7 ? O_PATH : O_RDONLY|O_CLOEXEC;
        unsigned char expected[16384];
        for (unsigned i=0;i<sizeof(expected);++i) expected[i]=(unsigned char)(i*13+7);
        int cancelled=0;
        for (unsigned step=0;step<4;++step) {
            int callbacks=buffer_callbacks;
            int queued=mode<3 && step==(mode==0 ? 0U : mode==1 ? 1U : 3U);
            int gate=-1,started=-1;
            struct queued blocked={.work={.run=queued_run,.complete=queued_complete}};
            if (queued) {
                gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
                assert(gate>=0 && started>=0);
                blocked.gate=gate; blocked.started=started;
                assert(!md_fs_worker_submit(worker,&blocked.work));
                /* EVENT_WAIT: worker is occupied before snapshot submission. */
                assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
            }
            if (!step) {
                assert(!md_fs_buffer_snapshot_begin(service,getpid(),fs,buffer,flags,&request));
                assert(!md_fs_buffer_progress(service));
            } else {
                if (step<3) {
                    memcpy(frame->snapshot.data,expected+(step-1)*8192,8192);
                    frame->snapshot.size=8192;
                }
                assert(!md_fs_buffer_snapshot_advance(request,step==3));
                if (queued && step<3) memset(frame->snapshot.data,0,8192);
            }
            if (queued) {
                md_fs_buffer_cancel(&request); assert(!request);
                uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
                /* A queued sentinel completes after cancellation disposal. */
                struct root_capture tail={.work={.run=root_run,.complete=root_complete}};
                assert(!md_fs_worker_submit(worker,&tail.work));
                while (!tail.completed) worker_event(worker);
                md_fs_anchor_release(tail.origin);
                assert(blocked.completed && buffer_callbacks==callbacks);
                assert(!close(gate) && !close(started));
                buffer_pending=NULL; cancelled=1; break;
            }
            while (buffer_callbacks==callbacks) worker_event(worker);
            assert(!buffer_error);
            request=buffer_pending;
            if (step<3) {
                assert(request && buffered_open.fd<0);
                if ((mode==3 && !step) || (mode==4 && step==1) || (mode==5 && step==2)) {
                    md_fs_buffer_cancel(&request); assert(!request);
                    buffer_pending=NULL; cancelled=1; break;
                }
                if (!step) {
                    frame->snapshot.size=sizeof(frame->snapshot.data)+1;
                    assert(md_fs_buffer_snapshot_advance(request,0)==-EINVAL);
                    frame->snapshot.size=0;
                    assert(md_fs_buffer_snapshot_advance(request,0)==-EINVAL);
                }
            }
        }
        if (!cancelled) {
            assert(!request && buffered_open.fd>=0 && buffered_open.flags==flags && buffered_open.origin);
            struct stat st; assert(!fstat(buffered_open.fd,&st) && st.st_size==sizeof(expected));
            struct stat anchored;
            assert(!fstat(md_fs_anchor_fd(buffered_open.origin),&anchored)
                && anchored.st_dev==st.st_dev && anchored.st_ino==st.st_ino);
            if (flags&O_PATH) assert(fcntl(buffered_open.fd,F_GETFL)&O_PATH);
            else {
                unsigned char actual[sizeof(expected)];
                assert(read(buffered_open.fd,actual,sizeof(actual))==sizeof(actual));
                assert(!memcmp(actual,expected,sizeof(actual)));
                assert(lseek(buffered_open.fd,0,SEEK_SET)==0);
                assert(write(buffered_open.fd,"x",1)==-1 && errno==EBADF);
            }
            md_fs_buffer_open_release(&buffered_open);
        }
        assert(all_fds()==initial);
    }
    puts("PASS mapped snapshots: worker-only construction, chunk validation, read-only/O_PATH results and cancellation at every stage");
}
static void buffer_reopen_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, struct md_guest_domain *fs,
        const struct md_interception_buffer *buffer, struct md_fs_buffer_frame *frame) {
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.mode=0600,
        .path={"/mapped-reopen"}}};
    frame->input.length[0]=strlen(frame->input.path[0])+1;
    assert(!buffer_request(service,worker,fs,buffer,MD_FS_CREATE));
    assert(!frame->reply.result.error && buffered_open.fd>=0 && buffered_open.origin);
    struct md_fs_buffer_open source=buffered_open; buffered_open=(struct md_fs_buffer_open){.fd=-1};
    const char bytes[]="mapped reopen data";
    assert(write(source.fd,bytes,sizeof(bytes))==sizeof(bytes));
    *frame=(struct md_fs_buffer_frame){.input={.base={source.fd,-1}}};
    assert(!buffer_request(service,worker,fs,buffer,MD_FS_OBJECT_ID) && !frame->reply.result.error);
    char object[33]; memcpy(object,frame->reply.data,sizeof(object));
    for (unsigned mutable=0;mutable<2;++mutable) for (unsigned nofollow=0;nofollow<2;++nofollow) {
        *frame=(struct md_fs_buffer_frame){.input={.base={source.fd,-1},.mode=mutable,
            .flags=O_PATH|O_CLOEXEC|(nofollow ? O_NOFOLLOW : 0)}};
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_REOPEN));
        assert(!frame->reply.result.error && buffered_open.fd>=0 && buffered_open.origin
            && frame->transfer==MD_FS_BUFFER_ANCHOR && (fcntl(buffered_open.fd,F_GETFL)&O_PATH));
        struct stat before,after;
        assert(!fstat(source.fd,&before) && !fstat(buffered_open.fd,&after));
        assert(before.st_dev==after.st_dev && before.st_ino==after.st_ino);
        int delivered=open(frame->source,frame->source_flags);
        assert(delivered>=0 && !fstat(delivered,&after));
        assert(before.st_dev==after.st_dev && before.st_ino==after.st_ino);
        assert(frame->source_flags==(O_PATH|(buffered_open.flags&(O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW))));
        assert(!close(delivered));
        md_fs_buffer_open_release(&buffered_open);
    }
    for (unsigned path=0;path<2;++path) {
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.flags=O_CLOEXEC|(path ? O_PATH : O_RDONLY),
            .length={sizeof(object),0}}};
        memcpy(frame->input.path[0],object,sizeof(object));
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_OPEN_OBJECT));
        assert(!frame->reply.result.error && buffered_open.fd>=0 && buffered_open.origin
            && frame->transfer==(path ? MD_FS_BUFFER_ANCHOR : MD_FS_BUFFER_ADDFD));
        if (!path) {
            char readback[sizeof(bytes)];
            assert(read(buffered_open.fd,readback,sizeof(readback))==sizeof(readback));
            assert(!memcmp(bytes,readback,sizeof(bytes)));
        }
        md_fs_buffer_open_release(&buffered_open);
    }
    md_fs_buffer_open_release(&source);
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.path={"/mapped-reopen"}}};
    frame->input.length[0]=strlen(frame->input.path[0])+1;
    assert(!buffer_request(service,worker,fs,buffer,MD_FS_UNLINK) && !frame->reply.result.error);
    unsigned before=all_fds();
    for (unsigned i=0;i<16;++i) {
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1}}};
        memcpy(frame->input.object[0],object,sizeof(object));
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_FSTAT));
        assert(!frame->reply.result.error && !frame->reply.result.info.links
            && frame->reply.result.info.size==sizeof(bytes) && buffered_open.fd==-1);
        assert(all_fds()==before);
    }
    for (unsigned cancel=0;cancel<2;++cancel) {
        int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        assert(gate>=0 && started>=0);
        struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
        assert(!md_fs_worker_submit(worker,&blocked.work));
        /* EVENT_WAIT: worker reaches the controlled gate; timeout fails. */
        assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1}}};
        memcpy(frame->input.object[0],object,sizeof(object));
        int callbacks=buffer_callbacks;
        struct md_fs_buffer_request *request=NULL;
        assert(!md_fs_buffer_begin(service,getpid(),fs,MD_FS_FSTAT,buffer,&request));
        memset(frame->input.object[0],'x',sizeof(frame->input.object[0]));
        assert(!md_fs_buffer_progress(service));
        if (cancel) { md_fs_buffer_cancel(&request); assert(!request); }
        struct queued sentinel={.work={.run=queued_run,.complete=queued_complete},.gate=-1,.started=-1};
        assert(!md_fs_worker_submit(worker,&sentinel.work));
        uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
        while (!sentinel.completed) worker_event(worker);
        assert(blocked.completed && buffer_callbacks==callbacks+!cancel && buffered_open.fd==-1);
        if (!cancel) assert(!buffer_error && !frame->reply.result.error
            && !frame->reply.result.info.links && frame->reply.result.info.size==sizeof(bytes));
        assert(!close(gate) && !close(started) && all_fds()==before);
    }
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.capacity=512}};
    memcpy(frame->input.object[0],object,sizeof(object));
    struct md_fs_buffer_request *invalid=NULL;
    assert(md_fs_buffer_begin(service,getpid(),fs,MD_FS_GETDENTS,buffer,&invalid)==-EINVAL && !invalid);
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1}}};
    memset(frame->input.object[0],'x',sizeof(frame->input.object[0]));
    assert(md_fs_buffer_begin(service,getpid(),fs,MD_FS_FSTAT,buffer,&invalid)==-EINVAL && !invalid);
    assert(all_fds()==before);
    puts("PASS mapped reopen and object access: immutable/mutable origins, nofollow, data/O_PATH results, captured identity and queued cancellation");
}
static int buffer_source_restricted;
static int buffer_source_policy(pid_t pid) {
    assert(pid==getpid()); return buffer_source_restricted;
}
static void buffer_leaf_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker,struct md_guest_domain *fs,
        const struct md_interception_buffer *buffer,struct md_fs_buffer_frame *frame) {
    md_fs_buffer_restricted(service,buffer_source_policy);
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.mode=0700,
        .path={"/mapped-native-parent"},.length={sizeof("/mapped-native-parent"),0}}};
    assert(!buffer_request(service,worker,fs,buffer,MD_FS_MKDIR) && !frame->reply.result.error);
    for (int restricted=0;restricted<2;++restricted) {
        buffer_source_restricted=restricted;
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.mode=0600,
            .flags=O_CREAT|O_EXCL|O_RDWR,.path={"/mapped-native-parent/leaf"},
            .length={sizeof("/mapped-native-parent/leaf"),0}}};
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_OPEN));
        assert(!frame->reply.result.error && buffered_open.fd>=0);
        struct stat expected,actual; assert(!fstat(buffered_open.fd,&expected));
        md_fs_buffer_open_release(&buffered_open);
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},
            .flags=O_PATH|O_NOFOLLOW|O_CLOEXEC,.path={"/mapped-native-parent/leaf"},
            .length={sizeof("/mapped-native-parent/leaf"),0}}};
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_OPEN));
        assert(!frame->reply.result.error && buffered_open.fd>=0);
        assert(frame->transfer==(restricted ? MD_FS_BUFFER_ANCHOR : MD_FS_BUFFER_RIGHTS));
        int delivered=restricted ? open(frame->source,frame->source_flags)
            : fcntl(buffered_open.fd,F_DUPFD_CLOEXEC,0);
        assert(delivered>=0 && fcntl(delivered,F_GETFL)==(O_PATH|O_NOFOLLOW));
        assert(!fstat(delivered,&actual) && expected.st_dev==actual.st_dev && expected.st_ino==actual.st_ino);
        md_fs_buffer_open_release(&buffered_open);
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},
            .path={"/mapped-native-parent/leaf"},.length={sizeof("/mapped-native-parent/leaf"),0}}};
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_UNLINK) && !frame->reply.result.error);
        assert(!fstat(delivered,&actual) && expected.st_ino==actual.st_ino && !close(delivered));
    }
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.flags=AT_REMOVEDIR,
        .path={"/mapped-native-parent"},.length={sizeof("/mapped-native-parent"),0}}};
    assert(!buffer_request(service,worker,fs,buffer,MD_FS_UNLINK) && !frame->reply.result.error);
    md_fs_buffer_restricted(service,NULL);
    puts("PASS O_PATH leaf delivery: exact FD without publication, restricted source, flags and identity after unlink");
}
static void buffer_native_path_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker,struct md_guest_domain *fs,
        const struct md_interception_buffer *buffer,struct md_fs_buffer_frame *frame) {
    unsigned before=all_fds();
    const char *paths[]={"/dev/null","/dev/full"};
    for (unsigned i=0;i<sizeof(paths)/sizeof(*paths);++i) {
        /* Native providers reopen admitted non-links through a retained pin,
         * normalizing NOFOLLOW before delivery to the caller. */
        struct md_fs_buffer_open opened={.fd=open(paths[i],O_PATH|O_CLOEXEC)};
        assert(opened.fd>=0);
        struct stat expected,actual;
        assert(!fstat(opened.fd,&expected));
        struct md_fs_result result={.fd=-1};
        struct md_fs_buffer_request *request=NULL;
        *frame=(struct md_fs_buffer_frame){.source_verify=1};
        int callbacks=buffer_callbacks;
        assert(!md_fs_buffer_adopt(service,getpid(),fs,MD_FS_OPEN,O_PATH|O_NOFOLLOW|O_CLOEXEC,
            buffer,&result,&opened,&request) && opened.fd==-1);
        while (buffer_callbacks==callbacks) worker_event(worker);
        assert(!buffer_error && !buffer_pending && buffered_open.fd>=0);
        assert(frame->transfer==MD_FS_BUFFER_ANCHOR && frame->source_flags==(O_PATH|O_CLOEXEC));
        assert(!frame->source_verify);
        assert(buffered_open.flags==frame->source_flags);
        int delivered=open(frame->source,frame->source_flags);
        assert(delivered>=0 && !fstat(delivered,&actual));
        assert(actual.st_dev==expected.st_dev && actual.st_ino==expected.st_ino
            && actual.st_rdev==expected.st_rdev && actual.st_mode==expected.st_mode);
        assert(!close(delivered));
        md_fs_buffer_open_release(&buffered_open);
        assert(all_fds()==before);
    }
    for (unsigned scenario=0;scenario<3;++scenario) {
        char directory[]="/data/local/tmp/md-buffer-link-XXXXXX",moved[sizeof(directory)+8];
        assert(mkdtemp(directory));
        int parent=open(directory,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(parent>=0);
        char name[NAME_MAX+1]; memset(name,'l',NAME_MAX); name[NAME_MAX]=0;
        assert(!symlinkat("first",parent,name));
        struct md_fs_buffer_open opened={.fd=openat(parent,name,O_PATH|O_NOFOLLOW|O_CLOEXEC)};
        assert(opened.fd>=0);
        struct md_fs_result result={.fd=-1}; struct md_fs_buffer_request *request=NULL;
        *frame=(struct md_fs_buffer_frame){0};
        int callbacks=buffer_callbacks;
        assert(!md_fs_buffer_adopt(service,getpid(),fs,MD_FS_OPEN,O_PATH|O_NOFOLLOW|O_CLOEXEC,
            buffer,&result,&opened,&request) && opened.fd==-1);
        while (buffer_callbacks==callbacks) worker_event(worker);
        assert(!buffer_error && !buffer_pending && buffered_open.fd>=0);
        assert(frame->transfer==MD_FS_BUFFER_ANCHOR && frame->source_verify && buffered_open.source_parent);
        if (scenario==1) {
            snprintf(moved,sizeof(moved),"%s-moved",directory);
            assert(!rename(directory,moved));
        } else if (scenario==2) {
            assert(!unlinkat(parent,name,0) && !symlinkat("second",parent,name));
        }
        int delivered=open(frame->source,frame->source_flags); struct stat actual;
        assert(delivered>=0 && !fstat(delivered,&actual) && S_ISLNK(actual.st_mode));
        assert(md_fs_buffer_source_matches(frame,actual.st_dev,actual.st_ino)==(scenario!=2));
        assert(!close(delivered));
        md_fs_buffer_open_release(&buffered_open);
        assert(!unlinkat(parent,name,0) && !close(parent) && !rmdir(scenario==1 ? moved : directory));
        assert(all_fds()==before);
    }
    puts("PASS native O_PATH delivery: normalized flags, pinned link parent, replacement detection, no SCM_RIGHTS or descriptor leak");
}
static void buffer_cwd_delivery(struct md_fs_buffer_service *service, struct md_fs_worker *worker,
        const struct md_task_resources *resources, const struct md_interception_buffer *buffer,
        struct md_fs_buffer_frame *frame, struct md_fs_anchor *base, struct md_resource_wakeup *wake) {
    assert(!md_domain_set_cwd(resources->fs,base));
    unsigned before=all_fds();
    int fd=open("/data/local/tmp",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    struct stat expected; assert(!fstat(fd,&expected));
    for (unsigned scenario=0;scenario<5;++scenario) {
        assert(!md_domain_set_cwd(resources->fs,base));
        *frame=(struct md_fs_buffer_frame){.input={.base={fd,-1},.mode=MD_FS_TASK_FCHDIR}};
        struct md_fs_buffer_request *request=buffer_request(service,worker,resources->fs,buffer,MD_FS_TASK_PATH);
        assert(md_fs_buffer_task_pending(request) && !frame->reply.result.error
            && buffered_open.fd==-1 && frame->transfer==MD_FS_BUFFER_TASK_PATH);
        struct md_fd_order files={0}; struct md_fs_order fs={0};
        assert(md_fd_order_begin(resources->files,&files,wake)==1);
        assert(!md_fs_order_begin(resources->fs,&fs,wake));
        /* Input capture no longer leases the caller's descriptor number. */
        int replacement=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(replacement>=0);
        assert(dup3(replacement,fd,O_CLOEXEC)==fd && !close(replacement));
        struct stat captured; assert(!stat(frame->source,&captured));
        assert(captured.st_dev==expected.st_dev && captured.st_ino==expected.st_ino);
        if (scenario!=2 && scenario!=4) {
            assert(!md_fs_buffer_task_start(request));
            assert(md_fs_buffer_task_start(request)==-EINVAL);
        }
        if (scenario<2 || scenario==4) assert(!md_fs_buffer_task_finish(&request,scenario ? -EACCES : 0));
        else md_fs_buffer_cancel(&request);
        buffer_pending=NULL;
        assert(!request && md_fs_buffer_task_finish(&request,0)==-EINVAL);
        assert(md_fd_order_state(&files)==MD_RESOURCE_ORDER_READY && !md_fd_order_start(&files));
        assert(md_fs_order_state(&fs)==MD_RESOURCE_ORDER_READY && !md_fs_order_start(&fs));
        struct md_fs_location root; struct md_fs_anchor *cwd=NULL;
        md_domain_paths(resources->fs,&root,&cwd);
        if (scenario==3) assert(!cwd);
        else {
            struct stat actual,prior;
            assert(cwd && !fstat(md_fs_anchor_fd(cwd),&actual) && !fstat(md_fs_anchor_fd(base),&prior));
            const struct stat *selected=scenario ? &prior : &expected;
            assert(actual.st_dev==selected->st_dev && actual.st_ino==selected->st_ino);
        }
        md_fs_anchor_release(cwd);
        assert(!md_fs_order_complete(&fs) && !md_fd_order_complete(&files));
        assert(!md_domain_set_cwd(resources->fs,base));
        replacement=open("/data/local/tmp",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(replacement>=0);
        assert(dup3(replacement,fd,O_CLOEXEC)==fd && !close(replacement));
        assert(fcntl(fd,F_GETFD)==FD_CLOEXEC && all_fds()==before+1);
    }
    assert(!close(fd) && all_fds()==before);
    puts("PASS task-affine cwd: captured object despite FD reuse, ordered FS publication, native error, pre-call and terminal cancellation");
}
static void buffer_cwd_capture(struct md_fs_buffer_service *service, struct md_fs_worker *worker,
        const struct md_task_resources *resources, const struct md_interception_buffer *buffer,
        struct md_fs_buffer_frame *frame, struct md_fs_anchor *base, struct md_resource_wakeup *wake) {
    unsigned before=all_fds();
    for (unsigned scenario=0;scenario<5;++scenario) {
        assert(!md_domain_set_cwd(resources->fs,NULL));
        *frame=(struct md_fs_buffer_frame){.input={.base={AT_FDCWD,-1}}};
        struct md_fs_buffer_request *request=buffer_request(service,worker,resources->fs,buffer,MD_FS_FSTAT);
        assert(request && md_fs_buffer_capture_pending(request));
        struct md_fd_order files={0}; struct md_fs_order fs={0};
        assert(!md_fd_order_begin(resources->files,&files,wake));
        assert(!md_fs_order_begin(resources->fs,&fs,wake));
        int fd=fcntl(md_fs_anchor_fd(base),F_DUPFD_CLOEXEC,0); assert(fd>=0);
        assert(!md_fs_buffer_capture_start(request));
        assert(md_fs_buffer_capture_start(request)==-EINVAL);
        int callbacks=buffer_callbacks;
        if (scenario>=2) {
            struct md_fs_buffer_request *pending=request;
            md_fs_buffer_cancel(&pending); assert(!pending);
            assert(md_fd_order_state(&files)==MD_RESOURCE_ORDER_QUEUED);
            assert(md_fs_order_state(&fs)==MD_RESOURCE_ORDER_QUEUED);
        }
        assert(!md_fs_buffer_capture_finish(request,fd,scenario==1 || scenario==3 ? -EACCES : 0));
        buffer_pending=NULL;
        if (!scenario) {
            while (buffer_callbacks==callbacks) worker_event(worker);
            assert(!buffer_error && !frame->reply.result.error && S_ISDIR(frame->reply.result.info.mode));
        } else if (scenario==1) assert(buffer_callbacks==callbacks+1 && buffer_error==-EACCES);
        else assert(buffer_callbacks==callbacks);
        assert(md_fd_order_state(&files)==MD_RESOURCE_ORDER_READY && !md_fd_order_start(&files));
        assert(md_fs_order_state(&fs)==MD_RESOURCE_ORDER_READY && !md_fs_order_start(&fs));
        assert(!md_fs_order_complete(&fs) && !md_fd_order_complete(&files));
        assert(fcntl(fd,F_GETFD)==-1 && errno==EBADF);
        assert(!md_domain_set_cwd(resources->fs,base));
        assert(all_fds()==before);
    }
    puts("PASS unknown cwd capture: retained FS/files turns, terminal cancellation, worker publication and exact FD cleanup");
}
static void buffer_kernel_capture(struct md_fs_buffer_service *service, struct md_fs_worker *worker,
        const struct md_task_resources *resources, const struct md_interception_buffer *buffer,
        struct md_fs_buffer_frame *frame, struct md_fs_anchor *base, struct md_resource_wakeup *wake) {
    unsigned before=all_fds();
    for (unsigned scenario=0;scenario<4;++scenario) {
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1}}};
        char paths[2][80];
        snprintf(paths[0],sizeof(paths[0]),"/proc/self/fd/%d",md_fs_anchor_fd(base));
        snprintf(paths[1],sizeof(paths[1]),"/proc/self/fd/%d/.",md_fs_anchor_fd(base));
        for (unsigned i=0;i<2;++i) {
            frame->input.capture_length[i]=strlen(paths[i])+1;
            strcpy(frame->input.capture_path[i],paths[i]); frame->input.capture_flags[i]=O_PATH|O_DIRECTORY;
            strcpy(frame->input.path[i],i ? "capture-missing-b" : "capture-missing-a");
            frame->input.length[i]=strlen(frame->input.path[i])+1;
        }
        struct md_fs_buffer_request *r=buffer_request(service,worker,resources->fs,buffer,MD_FS_RENAME);
        assert(r && md_fs_buffer_capture_pending(r));
        memset(&frame->input,0,sizeof(frame->input));
        struct md_fs_task_capture target;
        assert(!md_fs_buffer_capture_target(r,&target) && !strcmp(target.path,paths[0]));
        assert(!md_fs_buffer_capture_start(r));
        int first=fcntl(md_fs_anchor_fd(base),F_DUPFD_CLOEXEC,0); assert(first>=0);
        assert(!md_fs_buffer_capture_finish(r,first,0) && md_fs_buffer_capture_pending(r));
        assert(!md_fs_buffer_capture_target(r,&target) && !strcmp(target.path,paths[1]));
        struct md_fd_order files={0}; struct md_fs_order fs={0};
        assert(!md_fd_order_begin(resources->files,&files,wake));
        assert(!md_fs_order_begin(resources->fs,&fs,wake));
        if (scenario==2) md_fs_buffer_cancel(&r);
        else {
            assert(!md_fs_buffer_capture_start(r));
            int second=fcntl(md_fs_anchor_fd(base),F_DUPFD_CLOEXEC,0); assert(second>=0);
            if (scenario==3) {
                struct md_fs_buffer_request *pending=r;
                md_fs_buffer_cancel(&pending); assert(!pending);
            }
            int callbacks=buffer_callbacks;
            assert(!md_fs_buffer_capture_finish(r,second,scenario==1 ? -EACCES : 0));
            if (!scenario) {
                while (buffer_callbacks==callbacks) worker_event(worker);
                assert(!buffer_error && frame->reply.result.error==-ENOENT);
            } else if (scenario==1) assert(buffer_callbacks==callbacks+1 && buffer_error==-EACCES);
            else assert(buffer_callbacks==callbacks);
            assert(fcntl(second,F_GETFD)==-1 && errno==EBADF);
        }
        buffer_pending=NULL;
        assert(md_fd_order_state(&files)==MD_RESOURCE_ORDER_READY && !md_fd_order_start(&files));
        assert(md_fs_order_state(&fs)==MD_RESOURCE_ORDER_READY && !md_fs_order_start(&fs));
        assert(!md_fs_order_complete(&fs) && !md_fd_order_complete(&files));
        assert(fcntl(first,F_GETFD)==-1 && errno==EBADF && all_fds()==before);
    }
    puts("PASS kernel path capture: immutable operands, ordered two-source admission and complete/failed/cancelled FD ownership");
}
static void buffer_seek_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, const struct md_task_resources *resources,
        const struct md_interception_buffer *buffer, struct md_fs_buffer_frame *frame,
        struct md_fs_anchor *base) {
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",md_fs_anchor_fd(base));
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(fd>=0);
    int duplicate=dup(fd); assert(duplicate>=0);
    struct md_fd_origin_update update={0};
    assert(!md_fd_origins_prepare(resources->files,base,0,&update));
    md_fd_origins_finish(&update,fd);
    assert(lseek(fd,3,SEEK_SET)==3);
    int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(gate>=0 && started>=0);
    struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
    assert(!md_fs_worker_submit(worker,&blocked.work));
    /* EVENT_WAIT: worker has entered the controlled gate; timeout fails. */
    assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
    *frame=(struct md_fs_buffer_frame){.input={.base={fd,-1},.flags=SEEK_CUR,.offset=2}};
    int callbacks=buffer_callbacks;
    struct md_fs_buffer_request *request=NULL;
    assert(!md_fs_buffer_begin(service,getpid(),resources->fs,MD_FS_SEEKDIR,buffer,&request));
    assert(!md_fs_buffer_progress(service));
    int replacement=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(replacement>=0);
    assert(dup3(replacement,fd,O_CLOEXEC)==fd && !close(replacement));
    assert(!md_fd_origins_close_range(resources->files,fd,fd,0));
    uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
    while (buffer_callbacks==callbacks) worker_event(worker);
    assert(blocked.completed && !buffer_error && !buffer_pending && !frame->reply.result.error);
    assert(frame->reply.result.position==5 && lseek(duplicate,0,SEEK_CUR)==5 && lseek(fd,0,SEEK_CUR)==0);
    *frame=(struct md_fs_buffer_frame){.input={.base={md_fs_anchor_fd(base),-1},.flags=SEEK_CUR}};
    assert(!buffer_request(service,worker,resources->fs,buffer,MD_FS_SEEKDIR));
    assert(frame->reply.result.error==-EBADF);
    assert(!close(fd) && !close(duplicate) && !close(gate) && !close(started));
    puts("PASS mapped directory seek: shared offset, exact captured OFD after number reuse, O_PATH rejection");
}
static void buffer_directory_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, const struct md_task_resources *resources, struct md_fs_anchor *base) {
    char path[64]; snprintf(path,sizeof(path),"/proc/self/fd/%d",md_fs_anchor_fd(base));
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC), independent=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    int alias=dup(fd); assert(fd>=0 && independent>=0 && alias>=0);
    struct md_interception_buffers *pool=NULL; assert(!md_buffers_create(3,sizeof(struct md_fs_buffer_frame),sizeof(struct md_fs_buffer_frame),0,&pool));
    struct md_interception_buffer buffers[3]={{0}};
    struct md_fs_buffer_frame *frames[3];
    for (unsigned i=0;i<3;++i) {
        struct md_buffer_span span;
        assert(!md_buffers_take(pool,buffers+i) && !md_buffer_span(buffers+i,&span)); frames[i]=span.data;
    }
    for (unsigned mode=0;mode<4;++mode) {
        assert(lseek(fd,0,SEEK_SET)==0 && lseek(independent,0,SEEK_SET)==0);
        *frames[0]=(struct md_fs_buffer_frame){.input={.base={fd,-1},.capacity=24}};
        struct md_fs_buffer_request *read=buffer_request(service,worker,resources->fs,buffers,MD_FS_GETDENTS);
        assert(read && frames[0]->reply.result.size==24 && !frames[0]->reply.result.error);
        assert(lseek(alias,0,SEEK_CUR)==0);
        int callbacks=buffer_callbacks;
        *frames[1]=(struct md_fs_buffer_frame){.input={.base={alias,-1},.flags=SEEK_CUR}};
        struct md_fs_buffer_request *seek=NULL;
        assert(!md_fs_buffer_begin(service,getpid(),resources->fs,MD_FS_SEEKDIR,buffers+1,&seek));
        assert(!md_fs_buffer_progress(service) && buffer_callbacks==callbacks);
        *frames[2]=(struct md_fs_buffer_frame){.input={.base={independent,-1},.flags=SEEK_SET,.offset=7}};
        assert(!buffer_request(service,worker,resources->fs,buffers+2,MD_FS_SEEKDIR));
        assert(frames[2]->reply.result.position==7 && lseek(alias,0,SEEK_CUR)==0);
        callbacks=buffer_callbacks;
        if (mode==3) md_fs_buffer_cancel(&seek);
        if (mode==2) md_fs_buffer_cancel(&read);
        else { assert(!md_fs_buffer_ack(read,mode==1 ? -EFAULT : 0)); read=NULL; }
        /* EVENT_WAIT: worker completion commits/rejects the exact read; then
         * the queued alias seek can run. Timeout fails, never settles state. */
        worker_event(worker);
        if (mode!=2) assert(buffer_callbacks==callbacks+1);
        assert(lseek(alias,0,SEEK_CUR)==(mode==1 ? 0 : 1));
        if (seek) {
            callbacks=buffer_callbacks;
            assert(!md_fs_buffer_progress(service));
            while (buffer_callbacks==callbacks) worker_event(worker);
            seek=NULL;
            assert(!buffer_error && !frames[1]->reply.result.error
                && frames[1]->reply.result.position==(mode==1 ? 0 : 1));
        }
    }
    for (unsigned i=0;i<3;++i) assert(!md_buffer_release(buffers+i));
    md_buffers_release(pool);
    assert(!close(fd) && !close(alias) && !close(independent));
    puts("PASS mapped directory delivery: exact OFD ordering, independent opens, rejected copy and both cancellation owners");
}
static void buffer_watch_delivery(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, struct md_guest_domain *fs,
        struct md_interception_buffers *pool, struct md_interception_buffer *buffer,
        struct md_fs_buffer_frame *frame, int root) {
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.flags=IN_CLOEXEC|IN_NONBLOCK}};
    assert(!buffer_request(service,worker,fs,buffer,MD_FS_WATCH_CREATE));
    assert(!frame->reply.result.error && buffered_open.fd>=0);
    int watch=buffered_open.fd; buffered_open.fd=-1; md_fs_buffer_open_release(&buffered_open);
    for (int scenario=0;scenario<4;++scenario) {
        *frame=(struct md_fs_buffer_frame){.input={.base={watch,root},.flags=IN_CREATE}};
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_WATCH_ADD));
        assert(!frame->reply.result.error && frame->reply.result.position>0);
        int wd=(int)frame->reply.result.position;
        *frame=(struct md_fs_buffer_frame){.input={.base={watch,-1},.flags=(unsigned)wd}};
        assert(!buffer_request(service,worker,fs,buffer,MD_FS_WATCH_REMOVE));
        assert(!frame->reply.result.error);
        *frame=(struct md_fs_buffer_frame){.input={.base={watch,-1},.capacity=sizeof(struct inotify_event)}};
        struct md_fs_buffer_request *request=NULL;
        int callbacks=buffer_callbacks;
        if (scenario==2) {
            int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
            assert(gate>=0 && started>=0);
            struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
            assert(!md_fs_worker_submit(worker,&blocked.work));
            assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
            assert(!md_fs_buffer_begin(service,getpid(),fs,MD_FS_WATCH_READ,buffer,&request));
            assert(!md_fs_buffer_progress(service));
            md_fs_buffer_cancel(&request);
            assert(!md_buffer_release(buffer));
            uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
            for (;;) {
                worker_event(worker);
                int error=md_buffers_take(pool,buffer);
                if (!error) break;
                assert(error==-EAGAIN);
            }
            assert(blocked.completed && buffer_callbacks==callbacks);
            assert(!close(gate) && !close(started));
            *frame=(struct md_fs_buffer_frame){.input={.base={watch,-1},.capacity=sizeof(struct inotify_event)}};
        }
        request=buffer_request(service,worker,fs,buffer,MD_FS_WATCH_READ);
        assert(request && !frame->reply.result.error && frame->reply.result.size==sizeof(struct inotify_event));
        struct inotify_event event; memcpy(&event,frame->reply.data,sizeof(event));
        assert(event.wd==wd && event.mask==IN_IGNORED && !event.len);
        assert(md_fs_buffer_ack(request,1)==-EINVAL && md_fs_buffer_ack_pending(request));
        callbacks=buffer_callbacks;
        if (scenario==3) md_fs_buffer_cancel(&request);
        else assert(!md_fs_buffer_ack(request,scenario==0 ? -EFAULT : 0));
        if (scenario==3) {
            assert(!md_buffer_release(buffer));
            for (;;) {
                worker_event(worker);
                int error=md_buffers_take(pool,buffer);
                if (!error) break;
                assert(error==-EAGAIN);
            }
            assert(buffer_callbacks==callbacks);
        } else {
            while (buffer_callbacks==callbacks) worker_event(worker);
            assert(!buffer_error && !buffer_pending);
        }
        *frame=(struct md_fs_buffer_frame){.input={.base={watch,-1},.capacity=sizeof(struct inotify_event)}};
        request=buffer_request(service,worker,fs,buffer,MD_FS_WATCH_READ);
        if (scenario==0) {
            assert(request && !frame->reply.result.error);
            memcpy(&event,frame->reply.data,sizeof(event));
            assert(event.wd==wd && event.mask==IN_IGNORED);
            callbacks=buffer_callbacks;
            assert(!md_fs_buffer_ack(request,0));
            while (buffer_callbacks==callbacks) worker_event(worker);
            assert(!buffer_error && !buffer_pending);
        } else assert(!request && frame->reply.result.error==-EAGAIN);
    }
    assert(!close(watch));
    puts("PASS mapped watch delivery: acknowledgement, rejected copy, cancellation before and after offering records");
}
static void buffer_received_inspection(struct md_fs_buffer_service *service,
        struct md_fs_worker *worker, struct md_task_registry *tasks,
        const struct md_task_resources *resources, const struct md_identity *identity,
        struct md_interception_buffers *pool, struct md_interception_buffer *buffer,
        struct md_fs_buffer_frame *frame) {
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets));
    /* This fixture supplies a native vector, not an authenticated capsule.
     * Registering an ORIGINS_V1 pair would correctly reject that wire format. */
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.flags=IN_CLOEXEC|IN_NONBLOCK}};
    assert(!buffer_request(service,worker,resources->fs,buffer,MD_FS_WATCH_CREATE));
    assert(!frame->reply.result.error && buffered_open.fd>=0);
    int watch=buffered_open.fd; buffered_open.fd=-1; md_fs_buffer_open_release(&buffered_open);
    for (unsigned cancelled=0;cancelled<2;++cancelled) {
        int pipefd[2]; assert(!pipe2(pipefd,O_NONBLOCK|O_CLOEXEC));
        struct md_received_fds *sources=NULL; assert(!md_received_fds_reserve(&sources,4));
        sources->fds[0]=dup(sockets[1]); sources->fds[1]=dup(watch);
        sources->fds[2]=pipefd[1]; sources->fds[3]=open("/dev/null",O_RDONLY|O_CLOEXEC);
        assert(sources->fds[0]>=0 && sources->fds[1]>=0 && sources->fds[3]>=0); sources->count=4;
        struct md_fs_buffer_request *request=NULL;
        *frame=(struct md_fs_buffer_frame){0};
        assert(md_fs_buffer_inspect_begin(service,getpid(),resources->fs,buffer,sources,&request)==-EINVAL);
        frame->rights.count=254;
        assert(md_fs_buffer_inspect_begin(service,getpid(),resources->fs,buffer,sources,&request)==-EINVAL);
        frame->rights.count=1;
        const int invalid[]={0,-1,4,INT_MAX};
        for (unsigned i=0;i<sizeof(invalid)/sizeof(*invalid);++i) {
            frame->rights.index[0]=invalid[i];
            assert(md_fs_buffer_inspect_begin(service,getpid(),resources->fs,buffer,sources,&request)==-EBADF);
            assert(!request && sources->references==1);
        }
        int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        assert(gate>=0 && started>=0);
        struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
        assert(!md_fs_worker_submit(worker,&blocked.work));
        /* EVENT_WAIT: hold the worker until captured indices and ownership are
         * deliberately changed; timeout fails the fixture. */
        assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
        frame->rights.count=253;
        for (unsigned i=0;i<253;++i) frame->rights.index[i]=1+i%3;
        int callbacks=buffer_callbacks;
        unsigned before=all_fds();
        struct md_fd_order files={0}; struct md_fs_order fs={0};
        assert(md_fd_order_begin(resources->files,&files,NULL)==1 && !md_fd_order_start(&files));
        assert(md_fs_order_begin(resources->fs,&fs,NULL)==1 && !md_fs_order_start(&fs));
        assert(!md_fs_buffer_inspect_begin(service,getpid(),resources->fs,buffer,sources,&request));
        assert(sources->references==2 && all_fds()==before);
        memset(frame->rights.index,0,sizeof(frame->rights.index)); frame->rights.count=0;
        md_received_fds_release(sources);
        md_task_registry_forget(tasks,getpid());
        if (cancelled) md_fs_buffer_cancel(&request);
        assert(!md_buffer_release(buffer));
        struct md_interception_buffer next={0};
        assert(md_buffers_take(pool,&next)==-EAGAIN);
        char byte; assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
        uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
        int inspected=0;
        for (;;) {
            worker_event(worker);
            if (!cancelled && buffer_callbacks==callbacks+1) {
                if (buffer_error || buffer_pending || buffered_open.fd!=-1)
                    fprintf(stderr,"received inspection: error=%d pending=%d fd=%d\n",
                        buffer_error,buffer_pending!=NULL,buffered_open.fd);
                assert(!buffer_error && !buffer_pending && buffered_open.fd==-1);
                assert(frame->received_message.count==253 && frame->received_message.first[253]==253);
                for (unsigned i=0;i<253;++i)
                    assert(frame->received_message.first[i]==i && frame->received_message.entries[i].index==1+(int)(i%3)
                        && !frame->received_message.entries[i].flags
                        && frame->received_message.entries[i].selectors==(i%3==0 ? 1U<<MD_FD_SELECTOR_WATCH : 0));
                inspected=1;
            }
            int error=md_buffers_take(pool,buffer);
            if (!error) break;
            assert(error==-EAGAIN);
        }
        assert(blocked.completed && read(pipefd[0],&byte,1)==0);
        assert(buffer_callbacks==callbacks+(cancelled ? 0 : 1));
        assert(cancelled || inspected);
        assert(md_fd_order_state(&files)==MD_RESOURCE_ORDER_RUNNING
            && md_fs_order_state(&fs)==MD_RESOURCE_ORDER_RUNNING);
        assert(!md_fs_order_complete(&fs) && !md_fd_order_complete(&files));
        assert(!md_task_registry_publish(tasks,getpid(),getpid(),identity,resources));
        assert(!close(gate) && !close(started) && !close(pipefd[0]));
    }
    assert(!close(watch) && !close(sockets[0]) && !close(sockets[1]));
    puts("PASS received vector inspection: 253 ordered/repeated inputs, no FD duplication or table turns, captured context, rejected indices and cancellation EOF");
}
static void buffer_service_lifetime(const char *store, unsigned cancelled_operation) {
    unsigned before=all_fds();
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-buffer-service-%d",getpid());
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(worker)};
    assert(resources.fs && resources.files);
    struct md_identity identity=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,getpid(),getpid(),&identity,&resources));
    int wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(wake>=0);
    struct md_resource_wakeup *token=NULL; assert(!md_resource_wakeup_create(wake,&token));
    struct md_task_wait wait={0};
    struct md_fs_buffer_service *service=md_fs_buffer_service_new(worker,tasks,token,&wait,buffer_completed); assert(service);
    md_fs_buffer_control_factory(service,md_proc_file_control_object);
    struct md_interception_buffers *pool=NULL; assert(!md_buffers_create(1,sizeof(struct md_fs_buffer_frame),sizeof(struct md_fs_buffer_frame),0,&pool));
    struct md_interception_buffer task={0},next={0};
    assert(!md_buffers_take(pool,&task));
    struct md_buffer_span span; assert(!md_buffer_span(&task,&span));
    struct md_fs_buffer_frame *frame=span.data;
    *frame=(struct md_fs_buffer_frame){.input={.base={AT_FDCWD,-1}}};
    struct md_fs_buffer_request *request=NULL;
    /* Stopped-task admission must decline before execution when obtaining an
     * origin would require code in the task. Both ordering turns are released. */
    for (unsigned i=0;i<2;++i) {
        *frame=(struct md_fs_buffer_frame){.input={.base={i ? 123 : AT_FDCWD,-1},
            .flags=O_CREAT|O_EXCL|O_RDWR,.mode=0600,.length={17,0},.path={"not-yet-executed"}}};
        frame->input.length[0]=strlen(frame->input.path[0])+1;
        assert(!md_fs_buffer_invoke(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request));
        assert(!md_fs_buffer_progress(service)); request=NULL;
        assert(buffer_error==MD_FS_BUFFER_NEEDS_TASK && !buffer_pending && buffered_open.fd==-1);
        struct md_fd_order files={0}; struct md_fs_order fs={0};
        assert(md_fd_order_begin(resources.files,&files,token)==1 && !md_fd_order_start(&files));
        assert(md_fs_order_begin(resources.fs,&fs,token)==1 && !md_fs_order_start(&fs));
        assert(!md_fs_order_complete(&fs) && !md_fd_order_complete(&files));
    }
    *frame=(struct md_fs_buffer_frame){.input={.base={AT_FDCWD,-1}}};
    buffer_callbacks=0;
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_PATH,&task,&request));
    assert(!md_fs_buffer_progress(service));
    assert(buffer_callbacks==1 && !buffer_error && md_fs_buffer_capture_pending(request));
    md_fs_buffer_cancel(&request); buffer_pending=NULL;
    assert(!request && buffer_callbacks==1);
    struct md_fs_order held={0};
    assert(md_fs_order_begin(resources.fs,&held,token)==1 && !md_fs_order_start(&held));
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_PATH,&task,&request));
    assert(!md_fs_buffer_progress(service) && buffer_callbacks==1);
    md_fs_buffer_cancel(&request); assert(!request && buffer_callbacks==1);
    assert(!md_fs_order_complete(&held));
    struct root_capture root={.work={.run=root_run,.complete=root_complete}};
    assert(!md_fs_worker_submit(worker,&root.work));
    while (!root.completed) worker_event(worker);
    assert(!md_domain_set_cwd(resources.fs,root.origin)); md_fs_anchor_release(root.origin);
    assert(!md_task_registry_publish(tasks,getpid(),getpid(),&identity,&resources));
    *frame=(struct md_fs_buffer_frame){.input={.base={AT_FDCWD,-1}}};
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_PATH,&task,&request));
    assert(!md_fs_buffer_progress(service));
    while (buffer_callbacks==1) worker_event(worker);
    request=NULL;
    struct md_fs_response *reply=span.data;
    assert(buffer_callbacks==2 && !buffer_error && reply->delivery==MD_FS_REPLIED
        && !reply->result.error && reply->result.fd==-1 && reply->result.size==2 && !strcmp(reply->data,"/"));
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1},.flags=O_PATH|O_CLOEXEC,.length={2,0},.path={"/"}}};
    frame->input.length[0]=0;
    assert(md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request)==-EINVAL && !request);
    frame->input.length[0]=2;
    frame->input.attributes.creation_mask=01000;
    assert(md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request)==-EINVAL && !request);
    frame->input.attributes.creation_mask=0;
    frame->input.capacity=1;
    assert(md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request)==-EINVAL && !request);
    frame->input.capacity=0;
    frame->input.length[1]=PATH_MAX+1;
    assert(md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request)==-EINVAL && !request);
    frame->input.length[1]=0;
    assert(md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_WATCH_READ,&task,&request)==-EINVAL && !request);
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request));
    strcpy(frame->input.path[0],"/changed-after-admission");
    assert(!md_fs_buffer_progress(service));
    while (buffer_callbacks==2) worker_event(worker);
    request=NULL;
    struct stat st;
    assert(!buffer_error && buffered_open.fd>=0 && buffered_open.origin
        && !fstat(buffered_open.fd,&st) && S_ISDIR(st.st_mode));
    assert(frame->reply.result.fd==-1 && !frame->reply.result.error && !strncmp(frame->source,"/proc/",6));
    struct md_fs_anchor *base=md_fs_anchor_retain(buffered_open.origin);
    md_fs_buffer_open_release(&buffered_open);
    struct md_fd_origin_update update={0};
    assert(!md_fd_origins_prepare(resources.files,base,0,&update)); md_fd_origins_finish(&update,123);
    struct md_fd_order files={0};
    assert(md_fd_order_begin(resources.files,&files,token)==1 && !md_fd_order_start(&files));
    *frame=(struct md_fs_buffer_frame){.input={.base={123,-1},.flags=O_PATH,.length={2,0},.path={"."}}};
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request));
    assert(!md_fs_buffer_progress(service) && buffer_callbacks==3);
    md_fs_buffer_cancel(&request); assert(!request);
    assert(!md_fd_order_complete(&files));
    assert(md_fd_order_begin(resources.files,&files,token)==1 && !md_fd_order_start(&files));
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request));
    assert(!md_fs_buffer_progress(service) && buffer_callbacks==3);
    assert(!md_fd_origins_close_range(resources.files,123,123,0));
    assert(!md_fd_order_complete(&files));
    assert(!md_fs_buffer_progress(service)); request=NULL;
    assert(buffer_callbacks==4 && buffer_error==-EBADF && buffered_open.fd==-1);
    assert(!md_fd_origins_prepare(resources.files,base,0,&update)); md_fd_origins_finish(&update,123);
    assert(!md_fs_buffer_invoke(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request));
    assert(!md_fs_buffer_progress(service));
    /* The completed capture releases the table before worker IO. Reusing the
     * number and replacing the registry cannot change the admitted base. */
    assert(md_fd_order_begin(resources.files,&files,token)==1 && !md_fd_order_start(&files));
    assert(!md_fd_origins_close_range(resources.files,123,123,0));
    assert(!md_fd_order_complete(&files));
    md_task_registry_forget(tasks,getpid());
    while (buffer_callbacks==4) worker_event(worker);
    request=NULL;
    assert(!buffer_error && buffered_open.fd>=0 && !fstat(buffered_open.fd,&st) && S_ISDIR(st.st_mode));
    md_fs_buffer_open_release(&buffered_open);
    assert(!md_task_registry_publish(tasks,getpid(),getpid(),&identity,&resources));
    {
        int callbacks=buffer_callbacks;
        int fd=fcntl(md_fs_anchor_fd(base),F_DUPFD_CLOEXEC,0); assert(fd>=0);
        struct stat original; assert(!fstat(fd,&original));
        int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
        assert(gate>=0 && started>=0);
        struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
        assert(!md_fs_worker_submit(worker,&blocked.work));
        assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
        *frame=(struct md_fs_buffer_frame){.input={.base={fd,-1},.flags=O_PATH,.length={2,0},.path={"."}}};
        assert(!md_fs_buffer_begin(service,getpid(),resources.fs,MD_FS_OPEN,&task,&request));
        assert(!md_fs_buffer_progress(service));
        int replacement=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(replacement>=0);
        assert(dup3(replacement,fd,O_CLOEXEC)==fd && !close(replacement));
        uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
        while (buffer_callbacks==callbacks) worker_event(worker);
        request=NULL;
        assert(!buffer_error && !frame->reply.result.error && buffered_open.fd>=0);
        assert(!fstat(buffered_open.fd,&st) && st.st_dev==original.st_dev && st.st_ino==original.st_ino);
        md_fs_buffer_open_release(&buffered_open);
        assert(blocked.completed && !close(fd) && !close(gate) && !close(started));
    }
    buffer_reopen_delivery(service,worker,resources.fs,&task,frame);
    buffer_leaf_delivery(service,worker,resources.fs,&task,frame);
    buffer_native_path_delivery(service,worker,resources.fs,&task,frame);
    status_delivery(service,worker,&resources,token);
    captured_stat_delivery(service,worker,&resources,token,base);
    owned_snapshot_delivery(service,worker,&resources,token);
    buffer_cwd_delivery(service,worker,&resources,&task,frame,base,token);
    buffer_cwd_capture(service,worker,&resources,&task,frame,base,token);
    buffer_kernel_capture(service,worker,&resources,&task,frame,base,token);
    buffer_snapshot_delivery(service,worker,resources.fs,&task,frame);
    buffer_seek_delivery(service,worker,&resources,&task,frame,base);
    buffer_directory_delivery(service,worker,&resources,base);
    /* Watch queue destruction is event-driven after the last reader closes;
     * keep that unrelated retirement outside the channel's exact FD baseline. */
    buffer_channel_check(service,worker,tasks,&resources,&identity,&task,frame);
    buffer_received_inspection(service,worker,tasks,&resources,&identity,pool,&task,frame);
    buffer_watch_delivery(service,worker,resources.fs,pool,&task,frame,md_fs_anchor_fd(base));
    md_fs_anchor_release(base);
    const unsigned operations[]={MD_FS_TEMPORARY,MD_FS_MOUNT_TABLE};
    for (unsigned i=0;i<sizeof(operations)/sizeof(*operations);++i) {
        int callbacks=buffer_callbacks;
        *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1}}};
        assert(!md_fs_buffer_begin(service,getpid(),resources.fs,operations[i],&task,&request));
        assert(!md_fs_buffer_progress(service));
        while (buffer_callbacks==callbacks) worker_event(worker);
        request=NULL;
        assert(!buffer_error && frame->reply.delivery==MD_FS_REPLIED && !frame->reply.result.error
            && frame->reply.result.fd==-1 && frame->transfer==MD_FS_BUFFER_ADDFD && !frame->source[0]);
        assert(buffered_open.fd>=0 && !(fcntl(buffered_open.fd,F_GETFL)&O_PATH));
        if (operations[i]==MD_FS_MOUNT_TABLE) {
            struct md_proc_file *file=md_proc_file_from_object(buffered_open.object);
            assert(file && !md_proc_file_control_stat(file,&st) && S_ISREG(st.st_mode));
            struct md_control_file control=md_proc_file_control(file);
            char data[4096]; struct md_control_actor actor={.pid=getpid(),.identity=&identity,.resources=&resources};
            long bytes=control.ops->read(control.context,&actor,data,sizeof(data)-1,0);
            assert(bytes>0); data[bytes]=0; assert(strstr(data," / "));
            assert(frame->reply.result.selectors==md_fd_object_selectors(buffered_open.object));
        } else assert(!fstat(buffered_open.fd,&st) && S_ISREG(st.st_mode));
        md_fs_buffer_open_release(&buffered_open);
    }
    int callbacks=buffer_callbacks;
    int gate=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),started=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    assert(gate>=0 && started>=0);
    struct queued blocked={.work={.run=queued_run,.complete=queued_complete},.gate=gate,.started=started};
    assert(!md_fs_worker_submit(worker,&blocked.work));
    assert(md_event_wait_fd(started,POLLIN,md_event_now()+5000000000LL)>=0);
    *frame=(struct md_fs_buffer_frame){.input={.base={-1,-1}}};
    if (cancelled_operation==MD_FS_OPEN) {
        frame->input.flags=O_PATH|O_CLOEXEC; frame->input.length[0]=2;
        strcpy(frame->input.path[0],"/");
    }
    assert(!md_fs_buffer_begin(service,getpid(),resources.fs,cancelled_operation,&task,&request));
    assert(!md_fs_buffer_progress(service));
    md_fs_buffer_cancel(&request); assert(!request);
    assert(!md_buffer_release(&task));
    md_task_registry_forget(tasks,getpid());
    md_domain_release(resources.fs); md_fd_origins_release(resources.files);
    assert(md_buffers_take(pool,&next)==-EAGAIN);
    uint64_t one=1; assert(write(gate,&one,sizeof(one))==sizeof(one));
    /* EVENT_WAIT: cancelled work still owns its slot until worker completion;
     * every failed acquisition here follows a new completion event. */
    for (;;) {
        worker_event(worker);
        int error=md_buffers_take(pool,&next);
        if (!error) break;
        assert(error==-EAGAIN);
    }
    assert(blocked.completed && buffer_callbacks==callbacks && buffered_open.fd==-1);
    assert(!md_buffer_release(&next)); md_buffers_release(pool);
    assert(!md_fs_worker_stop(worker)); md_fs_buffer_service_destroy(service);
    md_task_registry_destroy(tasks); md_identity_release(&identity);
    md_resource_wakeup_release(token);
    assert(!close(wake) && !close(gate) && !close(started));
    assert(all_fds()==before);
    printf("PASS production buffer service: cwd, path snapshots, ordered dirfd capture/reuse, path/data result, queued cancellation and terminal worker retirement operation=%u\n",cancelled_operation);
}
static pid_t export_actor;
static int export_callbacks,export_error;
static void export_completed(struct md_fs_buffer_request *request, pid_t actor, int error) {
    assert(actor==export_actor); ++export_callbacks; export_error=error;
    assert(request);
}
static void send_export(int socket, uint64_t id, int fd, unsigned count) {
    struct iovec vector={&id,sizeof(id)};
    union { struct cmsghdr align; char data[CMSG_SPACE(2*sizeof(int))]; } control={0};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.data,.msg_controllen=CMSG_SPACE(count*sizeof(int))};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS; header->cmsg_len=CMSG_LEN(count*sizeof(int));
    for (unsigned i=0;i<count;++i) memcpy(CMSG_DATA(header)+i*sizeof(int),&fd,sizeof(fd));
    assert(sendmsg(socket,&message,MSG_NOSIGNAL)==sizeof(id));
}
static void buffer_export_lifetime(const char *store) {
    for (unsigned scenario=0;scenario<8;++scenario) {
        unsigned before=all_fds();
        struct md_fd_channel *channel=NULL; assert(!md_fd_channel_new(&channel));
        int send=fcntl(md_fd_channel_endpoint(channel),F_DUPFD_CLOEXEC,0), command[2],pipefd[2];
        assert(send>=0 && !socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,command));
        assert(!pipe2(pipefd,O_CLOEXEC|O_NONBLOCK));
        pid_t parent=getpid(),child=fork(); assert(child>=0);
        if (!child) {
            assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
            md_fd_channel_release(channel);
            assert(!close(command[0]) && !close(pipefd[0]) && !prctl(PR_SET_DUMPABLE,0));
            char ready='r'; assert(write(command[1],&ready,1)==1);
            uint64_t id;
            /* EVENT_WAIT: exact admitted request token; parent death/alarm ends failure. */
            assert(read(command[1],&id,sizeof(id))==sizeof(id));
            if (scenario!=4 && scenario!=7) {
                send_export(send,scenario==1 ? id+1 : id,pipefd[1],scenario==3 ? 2 : 1);
                if (scenario==2) send_export(send,id,pipefd[1],1);
            }
            assert(!close(pipefd[1]) && !close(send) && !close(command[1]));
            _exit(0);
        }
        export_actor=child; export_callbacks=0;
        assert(!close(send) && !close(command[1]));
        char ready;
        /* EVENT_WAIT: nondumpable child readiness before capture admission. */
        assert(md_event_wait_fd(command[0],POLLIN,md_event_now()+5000000000LL)>=0);
        assert(read(command[0],&ready,1)==1 && ready=='r');
        struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
        char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-buffer-export-%d-%u",getpid(),scenario);
        struct md_fs_worker *worker=NULL;
        assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
        struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
            .mounts=md_fs_worker_mounts(worker)};
        assert(resources.fs && resources.files);
        struct md_fd_origin_update update;
        assert(!md_fd_origins_prepare_channel(resources.files,channel,FD_CLOEXEC,&update));
        md_fd_origins_finish(&update,send);
        struct md_identity identity=md_identity_new(0,0);
        assert(!md_task_registry_publish(tasks,child,child,&identity,&resources));
        int wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK); assert(wake>=0);
        struct md_resource_wakeup *token=NULL; assert(!md_resource_wakeup_create(wake,&token));
        struct md_task_wait wait={0};
        struct md_fs_buffer_service *service=md_fs_buffer_service_new(worker,tasks,token,&wait,export_completed);
        struct md_interception_buffers *pool=NULL;
        assert(service && !md_buffers_create(1,sizeof(struct md_fs_buffer_frame),sizeof(struct md_fs_buffer_frame),0,&pool));
        struct md_interception_buffer buffer={0}; struct md_buffer_span span;
        assert(!md_buffers_take(pool,&buffer) && !md_buffer_span(&buffer,&span));
        struct md_fs_buffer_frame *frame=span.data;
        *frame=(struct md_fs_buffer_frame){.input={.base={pipefd[1],-1},.flags=SEEK_SET}};
        struct md_fs_buffer_request *request=NULL;
        assert(!md_fs_buffer_begin(service,child,resources.fs,MD_FS_SEEKDIR,&buffer,&request));
        assert(!md_fs_buffer_progress(service) && export_callbacks==1 && export_error==MD_FS_BUFFER_EXPORT);
        assert(md_fs_buffer_export_pending(request) && frame->export.number==send && frame->export.mask==1);
        uint64_t id=frame->export.request; assert(id);
        if (scenario==4) send_export(md_fd_channel_endpoint(channel),id,pipefd[1],1);
        assert(!close(pipefd[1]) && write(command[0],&id,sizeof(id))==sizeof(id));
        int status=wait_child(child); assert(WIFEXITED(status) && !WEXITSTATUS(status));
        char byte;
        if (scenario!=7) assert(read(pipefd[0],&byte,1)==-1 && errno==EAGAIN);
        if (scenario==5 || scenario==7) {
            md_fs_buffer_cancel(&request); assert(!request && export_callbacks==1);
        } else {
            assert(!md_fs_buffer_export_sent(request,scenario==6 ? -EIO : 0)); request=NULL;
            /* EVENT_WAIT: a valid export reaches the worker; its rejection of
             * the pipe as a directory retires the exact captured description. */
            while (export_callbacks<2) worker_event(worker);
            assert(export_callbacks==2);
            int expected=scenario==1 || scenario==3 || scenario==4 ? -EPROTO : scenario==6 ? -EIO : 0;
            assert(export_error==expected);
            if (!expected) {
                assert(frame->reply.delivery==MD_FS_REPLIED && frame->reply.result.error<0);
            }
        }
        assert(read(pipefd[0],&byte,1)==0);
        assert(!md_buffer_release(&buffer)); md_buffers_release(pool);
        assert(!md_fs_worker_stop(worker)); md_fs_buffer_service_destroy(service);
        md_task_registry_destroy(tasks); md_identity_release(&identity);
        md_fd_origins_release(resources.files); md_domain_release(resources.fs);
        md_resource_wakeup_release(token); md_fd_channel_release(channel);
        assert(!close(command[0]) && !close(pipefd[0]) && !close(wake));
        assert(all_fds()==before);
        printf("PASS production protected export scenario=%u: authenticated request, exact capture, terminal cancellation and pipe EOF\n",scenario);
    }
}
void md_test_ipc_peer_worker(const char *);
void md_test_ipc_origins(const char *);
void md_test_ipc_origin_child(const char *,int,unsigned);
void md_test_ipc_origin_guest(const char *,const char *);
void md_test_namespace_broker(const char *);
void md_test_input_session(const char *);
void md_test_fuse_device(const char *);
void md_test_mount_copy_service(const char *);
static void operation_window(struct md_inode_store *s) {
    int fd=md_inode_create(s,-1,"scope-data",0600); assert(fd>=0 && !close(fd));
    assert(!md_inode_symlink(s,"scope-data",-1,"scope-link"));
    struct md_filesystem fs={.store=s};
    struct md_fs_context context;
    assert(!md_fs_context_begin(&fs,0,0,&context));
    char data[512];
    struct md_fs_output output={.data=data,.capacity=sizeof(data)};
    for (unsigned generation=0;generation<4;++generation) {
        char value='a'+generation;
        struct md_fs_request q={.operation=MD_FS_XATTR,.directory={-1,-1},
            .path={"/scope-data","user.scope"},.mode=MD_FS_XATTR_SET,
            .capacity=1,.value=&value,.value_size=1};
        assert(md_fs_context_operation_window(&context,&q,0)==s);
        struct md_fs_result result;
        assert(!md_inode_operation_window_begin(s));
        md_fs_context_execute(&context,&q,&result,&output); assert(!result.error);
        for (unsigned i=0;i<32;++i) {
            q=(struct md_fs_request){.operation=MD_FS_ACCESS,.directory={-1,-1},
                .path={"/scope-data",NULL},.mode=R_OK};
            assert(md_fs_context_operation_window(&context,&q,0)==s);
            md_fs_context_execute(&context,&q,&result,NULL); assert(!result.error);
            q.operation=MD_FS_READLINK; q.path[0]="/scope-link"; q.mode=0; q.capacity=sizeof(data);
            assert(md_fs_context_operation_window(&context,&q,0)==s);
            md_fs_context_execute(&context,&q,&result,&output);
            assert(!result.error && result.size==10 && !memcmp(data,"scope-data",10));
            q.operation=MD_FS_XATTR; q.path[0]="/scope-data"; q.path[1]="user.scope";
            q.mode=MD_FS_XATTR_GET;
            assert(md_fs_context_operation_window(&context,&q,0)==s);
            md_fs_context_execute(&context,&q,&result,&output);
            assert(!result.error && result.size==1 && data[0]==value);
            q.path[1]=""; q.mode=MD_FS_XATTR_LIST;
            assert(md_fs_context_operation_window(&context,&q,0)==s);
            md_fs_context_execute(&context,&q,&result,&output);
            assert(!result.error && result.size>=sizeof("user.scope"));
            int found=0;
            for (size_t at=0;at<result.size;at+=strlen(data+at)+1)
                found|=!strcmp(data+at,"user.scope");
            assert(found);
        }
        q.mode=MD_FS_XATTR_REMOVE; q.path[1]="user.scope";
        assert(md_fs_context_operation_window(&context,&q,0)==s);
        md_fs_context_execute(&context,&q,&result,&output); assert(!result.error);
        q.mode=MD_FS_XATTR_GET;
        md_fs_context_execute(&context,&q,&result,&output); assert(result.error==-ENODATA);
        assert(!md_inode_operation_window_end(s));
        q.operation=MD_FS_CHMOD; assert(md_fs_context_operation_window(&context,&q,0)==s);
        q.operation=MD_FS_OPEN; q.flags=O_CREAT; assert(md_fs_context_operation_window(&context,&q,1)==s);
        q.flags=O_CREAT|O_EXCL|O_RDWR; assert(md_fs_context_operation_window(&context,&q,1)==s);
        q.operation=MD_FS_RENAME; q.path[1]="relative";
        assert(!md_fs_context_operation_window(&context,&q,0));
        q.path[1]="/other";
        assert(md_fs_context_operation_window(&context,&q,0)==s);
        q.object[1]="foreign";
        assert(!md_fs_context_operation_window(&context,&q,0)); q.object[1]=NULL;
        q.operation=MD_FS_ACCESS; q.path[0]="scope-data"; q.flags=0;
        assert(!md_fs_context_operation_window(&context,&q,0));
    }
    md_fs_context_end(&context);
    assert(!md_inode_unlink(s,-1,"scope-link",0) && !md_inode_unlink(s,-1,"scope-data",0));
    puts("PASS operation windows: access, readlink, metadata, mutations and both operand authorities");
}
int main(int argc, char **argv) {
    extern void md_test_provider_directory(const char *);
    extern void md_test_fs_deferred(const char *);
    extern void md_test_prepare_libc_descriptors(void);
    md_test_prepare_libc_descriptors();
    if (argc==3 && !strcmp(argv[1],"--provider-directory")) { md_test_provider_directory(argv[2]); return 0; }
    if (argc==3 && !strcmp(argv[1],"--input")) { md_test_input_session(argv[2]); return 0; }
    if (argc==3 && !strcmp(argv[1],"--fuse-device")) { md_test_fuse_device(argv[2]); return 0; }
    if (argc==3 && !strcmp(argv[1],"--worker-pause")) {
        alarm(30); setbuf(stdout,NULL);
        struct md_inode_store *store=NULL; assert(!md_inode_store_open(argv[2],1,&store));
        md_inode_store_close(store); worker_start_failure(argv[2]); worker_pause(argv[2]); md_test_mount_copy_service(argv[2]);
        md_test_fs_deferred(argv[2]); return 0;
    }
    if (argc==3 && !strcmp(argv[1],"--broker-directory")) { md_test_namespace_broker(argv[2]); return 0; }
    if (argc==3 && !strcmp(argv[1],"--ipc-peer")) { md_test_ipc_peer_worker(argv[2]); return 0; }
    if (argc==3 && !strcmp(argv[1],"--ipc-origins")) { md_test_ipc_origins(argv[2]); return 0; }
    if (argc==5 && !strcmp(argv[1],"--ipc-origin-child")) { md_test_ipc_origin_child(argv[2],atoi(argv[3]),(unsigned)atoi(argv[4])); return 0; }
    if (argc==4 && !strcmp(argv[1],"--ipc-origin-guest")) { md_test_ipc_origin_guest(argv[2],argv[3]); return 0; }
    if (argc==9 && !strcmp(argv[1],"--receiver")) { map_receiver(argv); return 99; }
    assert(argc==2 && !mkdir(argv[1],0700));
    setbuf(stdout,NULL); alarm(60);
    assert(!prctl(PR_SET_CHILD_SUBREAPER,1));
    setup_listener=mapping_listener();
    char store[4096]; snprintf(store,sizeof(store),"%s/store",argv[1]);
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,1,&s));
    operation_window(s);
    md_inode_store_close(s);
    for (int exec_image=0;exec_image<2;++exec_image) for (int shared=0;shared<2;++shared)
        for (unsigned kind=0;kind<3;++kind) for (int protected=0;protected<2;++protected) {
        for (int slots=0;slots<2;++slots) path_request(store,kind,protected,slots,0,shared,exec_image,0);
        for (int cancelled=1;cancelled<=3;++cancelled) path_request(store,kind,protected,1,cancelled,shared,exec_image,0);
        for (int cancelled=OPEN_ENTRY;cancelled<=OPEN_RACE;++cancelled)
            for (int unsigned_argument=0;unsigned_argument<2;++unsigned_argument)
                for (int slots=0;slots<2;++slots)
                    path_request(store,kind,protected,slots,cancelled,shared,exec_image,unsigned_argument);
    }
    queue_failure(store);
    worker_start_failure(store);
    worker_pause(store);
    md_test_fs_deferred(store);
    md_test_mount_copy_service(store);
    buffer_service_lifetime(store,MD_FS_OPEN);
    buffer_service_lifetime(store,MD_FS_TEMPORARY);
    buffer_export_lifetime(store);
    assert(!close(setup_listener));
    puts("PASS filesystem worker descriptor transport experiment");
    return 0;
}
