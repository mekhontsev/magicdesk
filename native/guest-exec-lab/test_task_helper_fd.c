#define _GNU_SOURCE
#include "task_helper.h"
#include "task_checkpoint.h"
#include "task_quiesce.h"
#include "task_wait.h"
#include "adapter_frame.h"
#include "fd_table_observation.h"
#include "received_fds.h"
#include "fd_messages.h"
#include "fs_mount_internal.h"
#include <assert.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <poll.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

extern char helper_call[],helper_return[],helper_abort[],caller_ready[];
extern char helper_store[],helper_stored[];
extern void caller_stop(void);
__asm__(".text\n.global helper_call\nhelper_call:\nsvc #0\n"
    ".global helper_return\nhelper_return:\nsvc #0\n"
    ".global helper_abort\nhelper_abort:\nmov x0,#0\nmov x8,#93\nsvc #0\nbrk #0\n"
    ".global helper_store\nhelper_store:\nstr w3,[x0]\n.global helper_stored\nhelper_stored:\nsvc #0\n"
    ".global caller_stop\ncaller_stop:\nmov x8,#178\nsvc #0\nmov x1,#19\nmov x8,#130\nsvc #0\n"
    ".global caller_ready\ncaller_ready:\nret\n");
enum scenario { NORMAL, FULL, DENIED, KILLED_OPEN, CANCEL_CHANNEL, CANCEL_EXPORT,
    CANCEL_SECOND, NO_SLOT, INVALID_INPUT, REPLACE_CAPTURED, CLONE_TRAPPED, CLONE_DENIED,
    ONE_SLOT, CANCEL_RIGHT, FILTER_EPIPE, PEER_CLOSED, SIGNAL_PIPE, CANCEL_IMPORT, PREFIX_SLOTS,
    EXACT_SLOTS, IMPORT_DENIED, CLOSE_DENIED, CONTROL_FAULT, ANCHOR_REJECT, ANCHOR_CANCEL, ANCHOR_DELIVER,
    CANCEL_CAPTURE, REPLACE_PATH, INTERRUPT_OWNER, QUIESCE_NEWBORN };
struct snapshot { unsigned char present[512]; };
struct completed {
    int called, captured, mutate, socket, source;
    struct md_task_helper_result result;
    struct md_fd_origins *files;
    struct md_fd_order turn, mutation;
};
static pid_t early_child;
static const char *directory_path;
static long native_trace(int request, pid_t pid, void *address, void *data) {
    return ptrace(request,pid,address,data);
}
static int restricted(pid_t pid) { (void)pid; return 1; }
static int marker(uintptr_t pc) {
    return pc==(uintptr_t)helper_return+4 || pc==(uintptr_t)helper_stored+4;
}
static struct md_task_checkpoints checkpoints={.trace=native_trace,.restricted=restricted,.marker=marker};
static long trace(int request,pid_t pid,void *address,void *data) {
    return md_task_checkpoint_trace(&checkpoints,request,pid,address,data);
}
static int newborn(pid_t pid) {
    if (!early_child) return 0;
    assert(early_child==pid); early_child=0; return 1;
}
static void completed(void *context, struct md_task_helper_result result) {
    struct completed *c=context; assert(!c->called++); c->result=result;
}
static void captured(void *context) {
    struct completed *c=context; assert(!c->called && !c->captured++);
    assert(!md_fd_order_complete(&c->turn));
    if (c->mutate) {
        assert(md_fd_order_state(&c->mutation)==MD_RESOURCE_ORDER_READY
            && !md_fd_order_start(&c->mutation));
        assert(send(c->socket,"m",1,MSG_NOSIGNAL)==1); char byte;
        /* EVENT_WAIT: the CLONE_FILES sibling confirms same-inode replacement
         * after helper clone, before any export; alarm fails a missing reply. */
        assert(recv(c->socket,&byte,1,0)==1 && byte=='m');
        assert(!md_fd_origins_close_range(c->files,(unsigned)c->source,(unsigned)c->source,0));
        assert(!md_fd_order_complete(&c->mutation));
    }
    md_fd_origins_release(c->files); c->files=NULL;
}
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned count=0; while (readdir(d)) ++count;
    assert(!closedir(d)); return count;
}
static void transfer_listener(int socket, int fd) {
    char byte=1; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
    memcpy(CMSG_DATA(&control.align),&fd,sizeof(fd));
    struct msghdr msg={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    assert(sendmsg(socket,&msg,MSG_NOSIGNAL)==1);
}
static int accept_listener(int socket) {
    char byte; struct iovec io={&byte,1};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr msg={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    /* EVENT_WAIT: child publishes its filter listener; alarm fails a stall. */
    assert(recvmsg(socket,&msg,MSG_CMSG_CLOEXEC)==1 && !(msg.msg_flags&(MSG_CTRUNC|MSG_TRUNC)));
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
    assert(c && c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_RIGHTS && c->cmsg_len==CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd,CMSG_DATA(c),sizeof(fd)); return fd;
}
static void native_control_fault(void) {
    unsigned before=descriptors();
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
    transfer_listener(pair[0],pair[0]);
    char byte; struct iovec io={&byte,1};
    struct msghdr msg={.msg_iov=&io,.msg_iovlen=1,.msg_control=(void *)4096,
        .msg_controllen=CMSG_SPACE(sizeof(int))};
    /* Linux reports failed SCM_RIGHTS publication as truncation, including
     * invalid control memory. It does not expose the underlying EFAULT. */
    assert(recvmsg(pair[1],&msg,MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==1
        && (msg.msg_flags&MSG_CTRUNC) && !msg.msg_controllen);
    close(pair[0]); close(pair[1]); assert(descriptors()==before);
}
struct mutation { int socket, file, replacement; pid_t parent; };
static int mutate(void *context) {
    struct mutation *m=context;
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==m->parent);
    char byte;
    /* EVENT_WAIT: the exact private-table clone releases this fixture action;
     * parent death and the outer alarm bound cancellation. */
    assert(recv(m->socket,&byte,1,0)==1 && byte=='m');
    assert(dup3(m->replacement,m->file,O_CLOEXEC)==m->file);
    assert(send(m->socket,"m",1,MSG_NOSIGNAL)==1);
    return 0;
}
static void actor(int socket, int writer, int file, int path, int replacement, int protected,
        enum scenario scenario, struct snapshot *snapshot, int sending) {
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    char start;
    /* EVENT_WAIT: parent seizes before protected state; alarm bounds setup. */
    assert(read(socket,&start,1)==1);
    pid_t sibling=-1;
    void *sibling_stack=NULL;
    if (scenario==REPLACE_CAPTURED) {
        sibling_stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(sibling_stack!=MAP_FAILED);
        struct mutation m={socket,file,replacement,getpid()};
        sibling=clone(mutate,(char *)sibling_stack+65536,CLONE_FILES|SIGCHLD,&m);
        assert(sibling>0);
    }
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_gettid,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,MD_GUEST_TASK_HELPER,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int listener=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(listener>=0); transfer_listener(socket,listener);
    /* Capture may retain only its requested inputs, not install caller slots.
     * The pipe also checks that helper teardown releases inherited writers. */
    int keep[]={STDERR_FILENO,writer,socket,file,path,replacement};
    unsigned kept=file<0 ? 2 : replacement<0 ? 5 : 6;
    for (unsigned i=0;i<kept;++i) for (unsigned j=i;j && keep[j]<keep[j-1];--j) {
        int fd=keep[j]; keep[j]=keep[j-1]; keep[j-1]=fd;
    }
    unsigned first=0;
    for (unsigned i=0;i<kept;++i) {
        if ((unsigned)keep[i]>first) assert(!syscall(SYS_close_range,first,keep[i]-1,0));
        first=(unsigned)keep[i]+1;
    }
    assert(!syscall(SYS_close_range,first,~0U,0));
    if (scenario==FULL) {
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
        limit.rlim_cur=sending>1 ? 512 : 64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        while (fcntl(writer,F_DUPFD_CLOEXEC,0)>=0) {}
        assert(errno==EMFILE);
    }
    if (scenario==NO_SLOT || scenario==ONE_SLOT || scenario==PREFIX_SLOTS || scenario==EXACT_SLOTS) {
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
        limit.rlim_cur=scenario==EXACT_SLOTS ? sending+1 : scenario==PREFIX_SLOTS ? sending : scenario==ONE_SLOT ? 1 : 0;
        assert(!setrlimit(RLIMIT_NOFILE,&limit));
    }
    if (scenario==DENIED || scenario==KILLED_OPEN || scenario==CLONE_DENIED || scenario==CLONE_TRAPPED || scenario==FILTER_EPIPE
            || scenario==IMPORT_DENIED || scenario==CLOSE_DENIED) {
        struct sock_filter denied[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,scenario==CLONE_DENIED || scenario==CLONE_TRAPPED ? SYS_clone
                : scenario==IMPORT_DENIED ? SYS_recvmsg : scenario==CLOSE_DENIED ? SYS_close
                : file<0 && !sending ? SYS_openat : SYS_sendmsg,0,1),
            BPF_STMT(BPF_RET|BPF_K,scenario==CLONE_TRAPPED ? SECCOMP_RET_TRAP : scenario==KILLED_OPEN ? SECCOMP_RET_KILL_THREAD
                : SECCOMP_RET_ERRNO|(scenario==CLONE_DENIED ? EPERM : scenario==FILTER_EPIPE ? EPIPE : EACCES)),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog deny={sizeof(denied)/sizeof(*denied),denied};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&deny));
    }
    for (unsigned i=0;i<sizeof(snapshot->present);++i) snapshot->present[i]=fcntl((int)i,F_GETFD)>=0;
    struct sigaction trap={.sa_handler=SIG_IGN},observed;
    sigemptyset(&trap.sa_mask); assert(!sigaction(SIGTRAP,&trap,NULL));
    caller_stop();
    assert(!sigaction(SIGTRAP,NULL,&observed) && observed.sa_handler==SIG_IGN);
    for (unsigned i=0;i<sizeof(snapshot->present);++i) assert(snapshot->present[i]==(fcntl((int)i,F_GETFD)>=0));
    if (sibling>0) {
        /* EVENT_WAIT: the exact mutation sibling must exit before fixture
         * teardown; the parent fixture alarm bounds failure. */
        int status; assert(waitpid(sibling,&status,0)==sibling && WIFEXITED(status) && !WEXITSTATUS(status));
        assert(lseek(file,0,SEEK_CUR)==4 && !munmap(sibling_stack,65536));
    }
    _exit(0);
}
static struct user_pt_regs registers(pid_t pid) {
    struct user_pt_regs r; struct iovec io={&r,sizeof(r)};
    assert(!ptrace(PTRACE_GETREGSET,pid,(void *)NT_PRSTATUS,&io)); return r;
}
static int wait_child(pid_t pid) {
    /* EVENT_WAIT: exact stop/exit, with fixture alarm as the failure bound. */
    int status; pid_t got;
    for (;;) {
        do { got=waitpid(pid,&status,__WALL); } while (got<0 && errno==EINTR);
        assert(got==pid);
        int owned=md_task_checkpoint_event(&checkpoints,pid,&status); assert(owned>=0);
        if (!owned) break;
    }
    assert(got==pid); return status;
}
static void check_directory(int fd, const struct snapshot *snapshot) {
    struct stat st; assert(!fstat(fd,&st) && S_ISDIR(st.st_mode));
    assert((fcntl(fd,F_GETFL)&(O_PATH|O_ACCMODE))==O_RDONLY);
    assert(fcntl(fd,F_GETFD)==FD_CLOEXEC);
    DIR *directory=fdopendir(dup(fd)); assert(directory);
    rewinddir(directory); struct dirent *e; unsigned char present[sizeof(snapshot->present)]={0}; errno=0;
    while ((e=readdir(directory))) {
        if (e->d_name[0]=='.') continue;
        char *end; unsigned long i=strtoul(e->d_name,&end,10);
        assert(!*end && i<sizeof(present)); present[i]=1;
    }
    assert(!errno && !memcmp(present,snapshot->present,sizeof(present)));
    assert(!closedir(directory));
    struct md_fd_table_observation o={0}; int output[3]={-1,-1,-1};
    assert(!md_fd_table_observation_begin(&o,fd,3));
    assert(!md_fd_table_observation_read(&o,output) && output[0]==-1);
}
static struct md_fd_message *capture_message(struct completed *c, const int *inputs, unsigned count,
        struct md_fs_anchor *origin, int path) {
    c->files=md_fd_origins_new(); assert(c->files);
    struct md_fd_origin_update update;
    assert(md_fd_order_begin(c->files,&c->turn,NULL)==1 && !md_fd_order_start(&c->turn));
    assert(!md_fd_origins_prepare(c->files,origin,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,c->source);
    assert(!md_fd_origins_prepare(c->files,origin,FD_CLOEXEC,&update));
    md_fd_origins_finish(&update,path);
    struct md_fd_message *message=NULL;
    assert(!md_fd_message_capture(c->files,inputs,count,&message));
    if (c->mutate) assert(!md_fd_order_begin(c->files,&c->mutation,NULL)
        && md_fd_order_state(&c->mutation)==MD_RESOURCE_ORDER_QUEUED);
    return message;
}
static int participant(void *context,pid_t pid,int stopped,int request) {
    return md_task_quiesce_add(context,pid,stopped,request);
}
static void quiesce_newborn(struct md_task_helpers *helpers,pid_t owner) {
    /* Select the parent first: the helper's initial stop is still unclaimed
     * when an unrelated executable-memory repair pauses all writers. */
    int status=wait_child(owner);
    assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_CLONE);
    unsigned long child; assert(!ptrace(PTRACE_GETEVENTMSG,owner,NULL,&child));
    assert(md_task_helper_event(helpers,owner,status)==1);
    struct md_task_quiesce *q=md_task_quiesce_new(trace); assert(q);
    assert(!md_task_helpers_visit(helpers,NULL,participant,q));
    assert(!md_task_quiesce_stop(q));
    while (!md_task_quiesce_ready(q)) {
        /* EVENT_WAIT: both writers must stop; the fixture alarm bounds failure. */
        pid_t pid=waitpid(-1,&status,__WALL); assert(pid>0);
        int checkpoint=md_task_checkpoint_event(&checkpoints,pid,&status); assert(checkpoint>=0);
        if (checkpoint) continue;
        assert(!md_task_quiesce_event(q,pid,status,NULL));
    }
    assert(!md_task_quiesce_release(q));
    int initial=0; pid_t pid;
    while ((pid=md_task_quiesce_take(q,&status,NULL))) {
        if (pid==(pid_t)child) {
            assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_STOP);
            ++initial;
        }
        assert(md_task_helper_event(helpers,pid,status)==1);
    }
    assert(initial==1);
    md_task_quiesce_destroy(q);
}
struct send_frame {
    struct msghdr message;
    struct iovec vector;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))]; } control;
    char byte;
    struct md_task_send_import import;
    struct sockaddr_un address;
};
static void exercise_request(int protected, enum scenario scenario, unsigned exported, int sending,
        const struct md_task_helper_request *capture) {
    unsigned before=descriptors(); early_child=0;
    checkpoints.begin=(uintptr_t)helper_call; checkpoints.end=(uintptr_t)helper_stored+4;
    int reference=capture ? open(capture->path,capture->flags|O_CLOEXEC) : -1;
    assert(!capture || reference>=0);
    sigset_t signals,previous; sigemptyset(&signals); sigaddset(&signals,SIGCHLD);
    assert(!sigprocmask(SIG_BLOCK,&signals,&previous));
    int signal_fd=signalfd(-1,&signals,SFD_CLOEXEC|SFD_NONBLOCK); assert(signal_fd>=0);
    int sockets[2],pipes[2];
    assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    assert(!pipe2(pipes,O_CLOEXEC|O_NONBLOCK));
    int file=-1,path=-1,replacement=-1,inputs[254];
    if (exported || sending>1) {
        char name[4096];
        assert(snprintf(name,sizeof(name),"%s/capture-XXXXXX",directory_path)<(int)sizeof(name));
        file=mkostemp(name,O_CLOEXEC); assert(file>=0);
        assert(write(file,"hello",5)==5 && lseek(file,1,SEEK_SET)==1);
        path=open(name,O_PATH|O_CLOEXEC); assert(path>=0);
        if (scenario==REPLACE_CAPTURED) {
            replacement=open(name,O_RDWR|O_CLOEXEC); assert(replacement>=0);
            assert(lseek(replacement,4,SEEK_SET)==4);
        }
        assert(!unlink(name));
        for (unsigned i=0;i<exported;++i) inputs[i]=i==exported-1 ? pipes[1]
            : !i ? sockets[1] : i==2 ? path : file;
        if (scenario==INVALID_INPUT) inputs[exported-1]=127;
    }
    struct md_interception_memory *memory=md_memory_new(); assert(memory);
    assert(!md_memory_buffers_prepare(memory,1,sizeof(union md_adapter_frame),0,0));
    struct md_interception_buffers *pool=md_memory_buffers(memory);
    size_t size=md_buffers_size(pool);
    void *mapping=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_SHARED,md_buffers_fd(pool),0);
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    struct snapshot *snapshot=mmap(NULL,sizeof(*snapshot),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(mapping!=MAP_FAILED && stack!=MAP_FAILED && snapshot!=MAP_FAILED);
    uintptr_t top=(uintptr_t)stack+65536;
    assert(!md_memory_buffers_mapped(memory,(uintptr_t)mapping,size));
    assert(!md_memory_stack_add(memory,top)); md_memory_stack_return(memory,top);
    struct md_interception_buffer buffer={0}; assert(!md_buffers_take(pool,&buffer));
    pid_t child=fork(); assert(child>=0);
    if (!child) actor(sockets[1],pipes[1],file,path,replacement,protected,scenario,snapshot,sending);
    assert(!ptrace(PTRACE_SEIZE,child,0,PTRACE_O_TRACECLONE|PTRACE_O_TRACEEXIT|PTRACE_O_EXITKILL|PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACESECCOMP));
    assert(write(sockets[0],"S",1)==1);
    int listener=accept_listener(sockets[0]);
    int status=wait_child(child); assert(WIFSTOPPED(status) && WSTOPSIG(status)==SIGSTOP && !(status>>16));
    struct user_pt_regs saved=registers(child); assert(saved.pc==(uintptr_t)caller_ready);
    struct md_task_wait wait={0};
    struct md_task_helper_callbacks callbacks={.trace=trace,.newborn=newborn,.wait=&wait};
    struct md_task_helpers *helpers=md_task_helpers_new(&callbacks); assert(helpers);
    struct md_interception_abi abi={.task_call=(uintptr_t)helper_call,.task_return=(uintptr_t)helper_return,
        .task_completed=(uintptr_t)helper_return,
        .task_abort=(uintptr_t)helper_abort,.store_groups=(uintptr_t)helper_store,.stored_groups=(uintptr_t)helper_stored};
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_FD_TABLE,.path="/proc/self/fd"};
    struct completed c={0};
    assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&request,completed,&c)==-EINVAL);
    request.path=NULL; request.flags=O_DIRECTORY;
    assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&request,completed,&c)==-EINVAL);
    request.flags=0;
    if (capture) request=*capture;
    if (exported) {
        request=(struct md_task_helper_request){.kind=MD_TASK_HELPER_EXPORT,
            .descriptors=inputs,.descriptor_count=exported,.captured=captured};
        struct md_task_helper_request invalid=request; invalid.descriptor_count=255;
        assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
        invalid=request; invalid.captured=NULL;
        assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
        int bad=-1; invalid=request; invalid.descriptors=&bad; invalid.descriptor_count=1;
        assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EBADF);
    }
    int directory=-1,cancelled=0; unsigned sent=0,admissions=0;
    struct md_received_fds *retained=NULL;
    struct md_mount_namespace *view=NULL; struct md_fs_anchor *origin=NULL;
    if (exported) {
        view=md_mount_namespace_new(); assert(view);
        struct md_filesystem fs={.mounts=view};
        struct md_fs_result result={.fd=file};
        struct md_fs_output output={.origin=&origin};
        md_view_capture(&fs,view,NULL,&result,&output); assert(!result.error && origin);
    }
    unsigned rounds=scenario==NORMAL || scenario==FULL ? 2 : 1;
    for (unsigned round=0;round<rounds;++round) {
        c=(struct completed){.mutate=scenario==REPLACE_CAPTURED,.socket=sockets[0],.source=file};
        int offered[254];
        struct md_fd_message *message=NULL;
        unsigned skip=exported==254 ? 1 : 0;
        if (exported) message=capture_message(&c,inputs+skip,exported-skip,origin,path);
        if (exported) { memcpy(offered,inputs,exported*sizeof(int)); request.descriptors=offered; }
        int destination=-1,native_anchor=-1;
        struct sockaddr_un native_name={.sun_family=AF_UNIX};
        struct md_received_fds *send_inputs=NULL;
        if (sending) {
            /* Created after fork: the source vector cannot be obtained from
             * the caller's FD numbers. Admission retains its exact inputs. */
            int connection[2]; assert(!socketpair(AF_UNIX,
                (scenario==ANCHOR_DELIVER ? SOCK_DGRAM : SOCK_STREAM)|SOCK_CLOEXEC,0,connection));
            if (scenario==ANCHOR_DELIVER) {
                int n=snprintf(native_name.sun_path,sizeof(native_name.sun_path),"%s/addressed-%d",directory_path,getpid());
                assert(n>0 && (unsigned)n<sizeof(native_name.sun_path));
                assert(!bind(connection[1],(void *)&native_name,offsetof(struct sockaddr_un,sun_path)+n+1));
                native_anchor=open(native_name.sun_path,O_PATH|O_CLOEXEC); assert(native_anchor>=0);
            }
            assert(!md_received_fds_reserve(&send_inputs,(unsigned)sending+1));
            send_inputs->fds[send_inputs->count++]=connection[0];
            for (int i=0;i<sending;++i) {
                int source=i%3==0 ? pipes[1] : i%3==1 ? file : path;
                int right=fcntl(source,F_DUPFD_CLOEXEC,0); assert(right>=0);
                send_inputs->fds[send_inputs->count++]=right;
            }
            destination=connection[1];
            if (scenario==PEER_CLOSED || scenario==SIGNAL_PIPE) { close(destination); destination=-1; }
            struct md_buffer_span span; assert(!md_buffer_span(&buffer,&span));
            struct send_frame *p=span.data; memset(p,0,sizeof(*p)); p->byte='s';
            uintptr_t address=md_memory_buffer_address(memory,&buffer); assert(address);
            p->vector=(struct iovec){(void *)(address+offsetof(struct send_frame,byte)),1};
            p->message=(struct msghdr){.msg_iov=(void *)(address+offsetof(struct send_frame,vector)),.msg_iovlen=1,
                .msg_control=(void *)(address+offsetof(struct send_frame,control)),.msg_controllen=CMSG_SPACE(sending*sizeof(int))};
            p->control.align=(struct cmsghdr){.cmsg_len=CMSG_LEN(sending*sizeof(int)),.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS};
            request=(struct md_task_helper_request){.kind=MD_TASK_HELPER_SEND_CAPTURED,.send_inputs=send_inputs,
                .send_import=address+offsetof(struct send_frame,import),
                .send={.message=address,.token_slot=address+offsetof(struct send_frame,control)+CMSG_LEN(0),
                    .socket=0,.flags=scenario==SIGNAL_PIPE ? 0 : MSG_NOSIGNAL}};
            struct md_task_helper_request invalid=request; invalid.send_inputs=NULL;
            assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
            invalid=request; invalid.send.count=1;
            assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
            invalid=request; invalid.send.token_slot=UINTPTR_MAX-1;
            assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
            invalid=request; invalid.send_import=address+span.size;
            assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
            invalid=request; invalid.send.token_slot=4097+CMSG_LEN(0);
            assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
            invalid=request; invalid.send_import=address+offsetof(struct send_frame,control);
            assert(md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&invalid,completed,&c)==-EINVAL);
            if (scenario==CONTROL_FAULT) request.send.token_slot=4096+CMSG_LEN(0);
            if (scenario==ANCHOR_REJECT || scenario==ANCHOR_CANCEL || scenario==ANCHOR_DELIVER) {
                request.send_address=address+offsetof(struct send_frame,address);
                request.send_anchor=scenario==ANCHOR_DELIVER ? native_anchor : path;
            }
        }
        /* The ordinary stop won a race with a writer-barrier interrupt. Its
         * delayed acknowledgement must not complete or cancel the helper. */
        if (scenario==INTERRUPT_OWNER) assert(!ptrace(PTRACE_INTERRUPT,child,0,0));
        int owner_interrupted=0;
        assert(!md_task_helper_begin(helpers,child,child,&saved,&abi,memory,&buffer,&request,completed,&c));
        if (scenario==QUIESCE_NEWBORN) quiesce_newborn(helpers,child);
        if (native_anchor>=0) close(native_anchor);
        md_received_fds_release(send_inputs); request.send_inputs=NULL;
        if (exported) memset(offered,0xff,exported*sizeof(int));
        while (!c.called) {
            pid_t pid;
            while ((pid=waitpid(-1,&status,__WALL|WNOHANG))>0) {
                int checkpoint=md_task_checkpoint_event(&checkpoints,pid,&status); assert(checkpoint>=0);
                if (checkpoint) continue;
                unsigned event=(unsigned)status>>16;
                if (pid==child && event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP)
                    ++owner_interrupted;
                if (capture && (scenario==CANCEL_CAPTURE || scenario==REPLACE_PATH)
                        && pid!=child && WIFSTOPPED(status) && event==MD_TASK_CHECKPOINT_EVENT) {
                    struct user_pt_regs r=registers(pid);
                    if (r.pc==(uintptr_t)helper_return+4 && r.regs[8]==SYS_openat && (long)r.regs[0]>=0) {
                        if (scenario==CANCEL_CAPTURE) {
                            assert(!md_task_helper_detach(helpers,child)); cancelled=1;
                        } else {
                            assert(!unlink(capture->path));
                            int replacement=open(capture->path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
                            assert(replacement>=0); close(replacement);
                        }
                    }
                }
                if ((scenario==CANCEL_EXPORT || scenario==CANCEL_SECOND) && !cancelled && pid!=child && WIFSTOPPED(status)
                        && event==MD_TASK_CHECKPOINT_EVENT) {
                    struct user_pt_regs r=registers(pid);
                    if (r.pc==(uintptr_t)helper_return+4 && r.regs[8]==SYS_sendmsg) {
                        ++sent;
                        if (scenario==CANCEL_EXPORT || sent==2) {
                            assert(!md_task_helper_detach(helpers,child)); cancelled=1;
                        }
                    }
                }
                if ((scenario==CANCEL_IMPORT || scenario==ANCHOR_CANCEL) && !cancelled && pid!=child && WIFSTOPPED(status)
                        && event==MD_TASK_CHECKPOINT_EVENT) {
                    struct user_pt_regs r=registers(pid);
                    if (r.pc==(uintptr_t)helper_return+4 && r.regs[8]==SYS_recvmsg) {
                        assert((long)r.regs[0]==sizeof(uint64_t));
                        assert(!md_task_helper_detach(helpers,child)); cancelled=1;
                    }
                }
                if (scenario==NORMAL && pid!=child && event==PTRACE_EVENT_EXIT) {
                    assert(md_task_helper_retiring(helpers,child,pid));
                    assert(!md_task_helper_retiring(helpers,child+1,pid));
                    assert(!md_task_helper_retiring(helpers,child,child));
                }
                int handled=md_task_helper_event(helpers,pid,status);
                if (pid==child && event==PTRACE_EVENT_CLONE)
                    assert(wait.members==1 && wait.cursor && wait.cursor->pid!=child);
                if (WIFEXITED(status) || WIFSIGNALED(status))
                    assert(!md_task_helper_retiring(helpers,child,pid) && !wait.members && !wait.cursor);
                if (handled<0) fprintf(stderr,"helper event pid=%d owner=%d status=%x error=%d\n",pid,child,status,handled);
                assert(handled>=0);
                if (!handled) {
                    if (cancelled && pid==child) assert(WIFSTOPPED(status));
                    else {
                        assert(pid!=child && WIFSTOPPED(status) && event==PTRACE_EVENT_STOP && !early_child);
                        early_child=pid;
                    }
                }
            }
            assert(pid==0 || (pid<0 && errno==ECHILD));
            if (c.called) break;
            struct pollfd fds[]={{signal_fd,POLLIN,0},{listener,POLLIN,0}};
            /* EVENT_WAIT: ptrace SIGCHLD or exact helper admission, not state
             * sampling; alarm fails missing completion/reaping. */
            int ready; do { ready=poll(fds,2,-1); } while (ready<0 && errno==EINTR);
            assert(ready>0);
            if (fds[0].revents&POLLIN) {
                struct signalfd_siginfo signal;
                while (read(signal_fd,&signal,sizeof(signal))==(ssize_t)sizeof(signal)) {}
                assert(errno==EAGAIN);
            }
            if (fds[1].revents&POLLIN) {
                struct seccomp_notif q={0}; assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_RECV,&q));
                ++admissions;
                if (!cancelled && (scenario==CANCEL_CHANNEL || (scenario==CANCEL_RIGHT && admissions==2))) {
                    assert(!md_task_helper_detach(helpers,child)); cancelled=1;
                } else assert(md_task_helper_notification(helpers,listener,&q)==1);
            }
        }
        assert(c.called==1 && !md_task_helpers_count(helpers) && !early_child);
        assert(!wait.members && !wait.cursor);
        assert(scenario!=INTERRUPT_OWNER || owner_interrupted==1);
        assert(c.captured==(exported && scenario!=CLONE_DENIED && scenario!=CLONE_TRAPPED ? 1 : 0));
        if (c.files) {
            assert((scenario==CLONE_DENIED || scenario==CLONE_TRAPPED) && md_fd_order_cancel(&c.turn)==1
                && !md_fd_order_complete(&c.turn));
            md_fd_origins_release(c.files); c.files=NULL;
        }
        assert(!c.turn.table && !c.mutation.table);
        assert(md_memory_stack_take(memory)==top); md_memory_stack_return(memory,top);
        /* A completed send cannot be rolled back by detaching its caller;
         * preserving the committed byte count prevents accidental replay. */
        if (sending && cancelled && scenario==CANCEL_EXPORT) assert(c.result.value==1 && c.result.descriptor==-1);
        else if (cancelled || scenario==KILLED_OPEN) assert(c.result.value==-ECANCELED && c.result.descriptor==-1);
        else if (scenario==DENIED || scenario==IMPORT_DENIED || scenario==CLOSE_DENIED)
            assert(c.result.value==-EACCES && c.result.descriptor==-1);
        else if (scenario==NO_SLOT || scenario==ONE_SLOT || scenario==PREFIX_SLOTS)
            assert(c.result.value==-EMFILE && c.result.descriptor==-1);
        else if (scenario==INVALID_INPUT) assert(c.result.value==-EBADF && c.result.descriptor==-1);
        else if (scenario==CONTROL_FAULT) assert(c.result.value==-EMFILE && c.result.descriptor==-1);
        else if (scenario==ANCHOR_REJECT) assert(c.result.value==-EISCONN && c.result.descriptor==-1);
        else if (scenario==CLONE_DENIED) assert(c.result.value==-EPERM && c.result.descriptor==-1);
        else if (scenario==CLONE_TRAPPED) assert(c.result.value==-EOPNOTSUPP && c.result.descriptor==-1);
        else if (scenario==FILTER_EPIPE || scenario==PEER_CLOSED || scenario==SIGNAL_PIPE)
            assert(c.result.value==-EPIPE && c.result.descriptor==-1);
        else if (sending) {
            if (c.result.value!=1) fprintf(stderr,"captured send protected=%d scenario=%d rights=%d round=%u result=%ld admissions=%u\n",
                protected,scenario,sending,round,c.result.value,admissions);
            assert(c.result.value==1 && c.result.descriptor==-1 && !c.result.received);
        }
        else if (exported) {
            if (c.result.value || c.result.descriptor!=-1 || !c.result.received
                    || c.result.received->count!=exported || c.result.received->origins)
                fprintf(stderr,"export protected=%d scenario=%d round=%u value=%ld fd=%d count=%u expected=%u origins=%p\n",
                    protected,scenario,round,c.result.value,c.result.descriptor,
                    c.result.received ? c.result.received->count : 0,exported,
                    (void *)(c.result.received ? c.result.received->origins : NULL));
            assert(!c.result.value && c.result.descriptor==-1 && c.result.received
                && c.result.received->count==exported && !c.result.received->origins);
            for (unsigned i=0;i<exported;++i) {
                struct stat original,actual;
                assert(!fstat(inputs[i],&original) && !fstat(c.result.received->fds[i],&actual)
                    && original.st_dev==actual.st_dev && original.st_ino==actual.st_ino);
                assert(fcntl(c.result.received->fds[i],F_GETFD)==FD_CLOEXEC);
                assert(fcntl(c.result.received->fds[i],F_GETFL)==fcntl(inputs[i],F_GETFL));
            }
            struct md_fd_object *annotations[254]={0};
            memcpy(annotations+skip,md_fd_message_origins(message),(exported-skip)*sizeof(*annotations));
            for (unsigned i=0;i<exported;++i)
                if (annotations[i]) assert(!md_fs_anchor_check_fd(md_fs_anchor_from_object(annotations[i]),c.result.received->fds[i]));
            assert(!md_received_fds_annotate(c.result.received,annotations,exported));
            md_received_fds_release(retained); retained=c.result.received;
        }
        else {
            if (c.result.value) fprintf(stderr,"capture=%s protected=%d scenario=%d result=%ld\n",
                capture ? capture->path : "fd-table",protected,scenario,c.result.value);
            assert(!c.result.value && c.result.descriptor>=0);
            if (capture) {
                struct stat expected,actual;
                assert(!fstat(reference,&expected) && !fstat(c.result.descriptor,&actual));
                assert(expected.st_dev==actual.st_dev && expected.st_ino==actual.st_ino
                    && expected.st_mode==actual.st_mode && expected.st_rdev==actual.st_rdev);
                assert(fcntl(c.result.descriptor,F_GETFL)==fcntl(reference,F_GETFL));
                assert(fcntl(c.result.descriptor,F_GETFD)==FD_CLOEXEC);
            } else check_directory(c.result.descriptor,snapshot);
            if (directory>=0) close(directory);
            directory=c.result.descriptor;
        }
        if (!exported || c.result.value) assert(!c.result.received);
        if (sending) {
            assert(c.result.signal.si_signo==(scenario==SIGNAL_PIPE ? SIGPIPE : 0));
            if (destination>=0) {
                int delivered=scenario==NORMAL || scenario==FULL || scenario==CANCEL_EXPORT || scenario==EXACT_SLOTS
                    || scenario==ANCHOR_DELIVER || scenario==INTERRUPT_OWNER || scenario==QUIESCE_NEWBORN;
                if (delivered) {
                    char byte; struct iovec io={&byte,1};
                    union { struct cmsghdr align; char bytes[CMSG_SPACE(253*sizeof(int))]; } control={0};
                    struct msghdr msg={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
                    assert(recvmsg(destination,&msg,MSG_CMSG_CLOEXEC|MSG_DONTWAIT)==1 && byte=='s'
                        && !(msg.msg_flags&(MSG_CTRUNC|MSG_TRUNC)));
                    struct cmsghdr *h=CMSG_FIRSTHDR(&msg);
                    assert(h && h->cmsg_type==SCM_RIGHTS && h->cmsg_level==SOL_SOCKET
                        && h->cmsg_len==CMSG_LEN(sending*sizeof(int)) && !CMSG_NXTHDR(&msg,h));
                    int rights[253]; memcpy(rights,CMSG_DATA(h),sending*sizeof(int));
                    for (int i=0;i<sending;++i) {
                        int source=i%3==0 ? pipes[1] : i%3==1 ? file : path;
                        struct stat a,b;
                        assert(!fstat(rights[i],&a) && !fstat(source,&b) && a.st_dev==b.st_dev && a.st_ino==b.st_ino);
                        assert(fcntl(rights[i],F_GETFD)==FD_CLOEXEC && fcntl(rights[i],F_GETFL)==fcntl(source,F_GETFL));
                        if (i%3==1) assert(lseek(rights[i],0,SEEK_CUR)==1);
                        close(rights[i]);
                    }
                }
                char byte;
                if (scenario==ANCHOR_DELIVER) assert(recv(destination,&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
                else assert(!recv(destination,&byte,1,MSG_DONTWAIT));
                close(destination);
            }
        }
        if (scenario==ANCHOR_DELIVER) assert(!unlink(native_name.sun_path));
        md_fd_message_release(message);
    }
    if (cancelled) assert(!kill(child,SIGKILL));
    else {
        struct iovec io={&saved,sizeof(saved)}; assert(!trace(PTRACE_SETREGSET,child,(void *)NT_PRSTATUS,&io));
        assert(!trace(PTRACE_CONT,child,0,0));
    }
    status=wait_child(child);
    if (!cancelled && scenario==QUIESCE_NEWBORN && WIFSTOPPED(status)
            && (unsigned)status>>16==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
        /* A barrier interrupt can lose to the owner's ordinary stop and arrive
         * after helper completion. Continue in place, as the supervisor does;
         * never restore registers again or replay the completed operation. */
        assert(!ptrace(PTRACE_CONT,child,0,0)); status=wait_child(child);
    }
    if (!WIFSTOPPED(status) || (unsigned)status>>16!=PTRACE_EVENT_EXIT)
        fprintf(stderr,"helper owner exit protected=%d scenario=%d exported=%u sending=%d cancelled=%d status=%#x\n",
            protected,scenario,exported,sending,cancelled,status);
    assert(WIFSTOPPED(status) && (unsigned)status>>16==PTRACE_EVENT_EXIT);
    assert(!ptrace(PTRACE_CONT,child,0,0)); status=wait_child(child);
    assert(cancelled ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL : WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(pipes[1])); char byte;
    if (retained) {
        assert(write(retained->fds[exported-1],"p",1)==1 && read(pipes[0],&byte,1)==1 && byte=='p');
        if (exported>3) {
            assert(read(retained->fds[1],&byte,1)==1 && byte=='e');
            assert(lseek(retained->fds[3],0,SEEK_CUR)==2 && lseek(file,0,SEEK_CUR)==2);
        }
        md_received_fds_release(retained);
    }
    assert(!read(pipes[0],&byte,1));
    if (file>=0) { close(file); close(path); }
    if (replacement>=0) close(replacement);
    md_fs_anchor_release(origin); md_mount_namespace_release(view);
    if (directory>=0) {
        struct md_fd_table_observation o={0};
        if (!capture) assert(md_fd_table_observation_begin(&o,directory,3)==-ENOENT);
        close(directory);
    }
    if (reference>=0) close(reference);
    md_task_helpers_destroy(helpers); assert(!md_buffer_release(&buffer)); md_memory_release(memory);
    assert(!checkpoints.count);
    assert(!munmap(mapping,size) && !munmap(stack,65536) && !munmap(snapshot,sizeof(*snapshot)));
    close(pipes[0]); close(listener); close(sockets[0]); close(sockets[1]); close(signal_fd);
    assert(!sigprocmask(SIG_SETMASK,&previous,NULL));
    assert(descriptors()==before);
    printf("PASS task helper FD custody protected=%d scenario=%d exported=%u sending=%d capture=%s rounds=%u\n",
        protected,scenario,exported,sending,capture ? capture->path : "none",rounds);
}
static void exercise(int protected, enum scenario scenario, unsigned exported, int sending) {
    exercise_request(protected,scenario,exported,sending,NULL);
}
static void path_captures(void) {
    char name[4096],link[4096];
    assert(snprintf(name,sizeof(name),"%s/path-XXXXXX",directory_path)<(int)sizeof(name));
    int fd=mkstemp(name); assert(fd>=0); close(fd);
    assert(snprintf(link,sizeof(link),"%s.link",name)<(int)sizeof(link));
    assert(!symlink(name,link));
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_CAPTURE,.path=name,.flags=O_PATH};
    for (int protected=0;protected<2;++protected) {
        enum scenario scenarios[]={NORMAL,FULL,DENIED,KILLED_OPEN,CANCEL_CHANNEL,CANCEL_CAPTURE,REPLACE_PATH};
        for (unsigned i=0;i<sizeof(scenarios)/sizeof(*scenarios);++i)
            exercise_request(protected,scenarios[i],0,0,&request);
        request.path=link; request.flags=O_PATH|O_NOFOLLOW;
        exercise_request(protected,NORMAL,0,0,&request);
        request.path=directory_path; request.flags=O_PATH|O_DIRECTORY;
        exercise_request(protected,NORMAL,0,0,&request);
        request.path=name; request.flags=O_PATH;
    }
    assert(!unlink(link) && !unlink(name));
    request.path="/dev/full";
    exercise_request(0,NORMAL,0,0,&request);
}
int main(int argc, char **argv) {
    assert(argc==2 || (argc==3 && !strcmp(argv[2],"--native-anchor"))); directory_path=argv[1];
    setvbuf(stdout,NULL,_IOLBF,0); alarm(90);
    if (argc==3) {
        for (int protected=0;protected<2;++protected) exercise(protected,ANCHOR_DELIVER,0,253);
        puts("PASS captured native pathname delivery and exact release"); return 0;
    }
    native_control_fault();
    for (int protected=0;protected<2;++protected) {
        exercise(protected,QUIESCE_NEWBORN,0,0);
        exercise(protected,QUIESCE_NEWBORN,254,0);
        exercise(protected,QUIESCE_NEWBORN,0,253);
    }
    path_captures();
    for (int protected=0;protected<2;++protected)
        for (enum scenario s=NORMAL;s<=CANCEL_EXPORT;++s) exercise(protected,s,0,0);
    for (int protected=0;protected<2;++protected) {
        exercise(protected,INTERRUPT_OWNER,0,0);
        exercise(protected,INTERRUPT_OWNER,254,0);
        exercise(protected,INTERRUPT_OWNER,0,253);
        for (enum scenario s=NORMAL;s<=CLONE_DENIED;++s) exercise(protected,s,254,0);
        exercise(protected,NORMAL,1,0);
        for (enum scenario s=NORMAL;s<=SIGNAL_PIPE;++s)
            if (s!=CANCEL_SECOND && s!=INVALID_INPUT && s!=REPLACE_CAPTURED) exercise(protected,s,0,1);
        for (enum scenario s=NORMAL;s<=CLOSE_DENIED;++s)
            if (s!=CANCEL_SECOND && s!=INVALID_INPUT && s!=REPLACE_CAPTURED) exercise(protected,s,0,253);
        for (enum scenario s=CONTROL_FAULT;s<=ANCHOR_CANCEL;++s) exercise(protected,s,0,253);
    }
    puts("PASS production FD table helper custody and cancellation"); return 0;
}
