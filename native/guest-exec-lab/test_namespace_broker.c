#define _GNU_SOURCE
#include "namespace_broker.h"
#include "fs_mounts.h"
#include "task_resources.h"
#include "fs_native.h"
#include "inode_internal.h"
#include "../shroot/src/cooperative_channel.h"
#include "../shroot/src/cooperative_exchange.h"
#include "test_syscall_sites.h"
#include "entry_wakeup.h"
#include "process_owner.h"
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <unistd.h>

static atomic_int cancel_pid;
static atomic_int queued_admission;
static struct md_fd_origins *queued_table;
static pid_t queued_owner;
int __real_md_fd_order_begin(struct md_fd_origins *,struct md_fd_order *,struct md_resource_wakeup *);
int __wrap_md_fd_order_begin(struct md_fd_origins *table,struct md_fd_order *turn,struct md_resource_wakeup *wake) {
    if (atomic_load_explicit(&queued_admission,memory_order_acquire)==1
            && table==queued_table && syscall(SYS_gettid)!=queued_owner) {
        atomic_store_explicit(&queued_admission,2,memory_order_release);
        /* EVENT_WAIT: the supervisor first observes active preparation. The
         * fixture alarm fails a lost wake after that changes to QUEUED. */
        while (atomic_load_explicit(&queued_admission,memory_order_acquire)!=3) sched_yield();
    }
    return __real_md_fd_order_begin(table,turn,wake);
}
static int signal_pipe[2];
static void interrupted(int signal) {
    (void)signal;
    int saved=errno;
    if (write(signal_pipe[1],"s",1)!=1) _exit(98);
    errno=saved;
}
ssize_t __real_process_vm_writev(pid_t,const struct iovec *,unsigned long,
        const struct iovec *,unsigned long,unsigned long);
ssize_t __wrap_process_vm_writev(pid_t pid,const struct iovec *local,unsigned long count,
        const struct iovec *remote,unsigned long remote_count,unsigned long flags) {
    ssize_t result=__real_process_vm_writev(pid,local,count,remote,remote_count,flags);
    int expected=pid;
    if (result>0 && atomic_compare_exchange_strong(&cancel_pid,&expected,0)) {
        assert(!kill(pid,SIGUSR1));
        /* EVENT_WAIT: the child's handler proves notification cancellation
         * before SEND; the fixture alarm fails an abandoned acknowledgement. */
        char byte;
        assert(read(signal_pipe[0],&byte,1)==1 && byte=='s');
    }
    return result;
}
static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned count=0;
    for (struct dirent *entry; (entry=readdir(dir));)
        if (strcmp(entry->d_name,".") && strcmp(entry->d_name,"..")) ++count;
    assert(!closedir(dir)); return count;
}
static int listener(int nr) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,nr,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int fd=(int)syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(fd>=0); return fd;
}
static void send_listener(int socket,int fd) {
    char byte='l',control[CMSG_SPACE(sizeof(fd))]={0};
    struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control,.msg_controllen=sizeof(control)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS;
    header->cmsg_len=CMSG_LEN(sizeof(fd)); memcpy(CMSG_DATA(header),&fd,sizeof(fd));
    assert(sendmsg(socket,&message,0)==1);
}
static int receive_listener(int socket) {
    char byte,control[CMSG_SPACE(sizeof(int))]={0};
    struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control,.msg_controllen=sizeof(control)};
    /* EVENT_WAIT: child publishes its actual seccomp listener; alarm bounds failure. */
    assert(recvmsg(socket,&message,MSG_CMSG_CLOEXEC)==1 && byte=='l'
        && !(message.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd,CMSG_DATA(header),sizeof(fd)); return fd;
}
static void reader(int socket,int directory,int cancel,pid_t parent) {
    assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
    if (getppid()!=parent) _exit(99);
    alarm(25);
    struct sigaction action={.sa_handler=interrupted};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR1,&action,NULL));
    int fd=listener(SYS_getdents64); send_listener(socket,fd); assert(!close(fd));
    char byte;
    /* EVENT_WAIT: parent publishes registry and broker before directory reads. */
    assert(read(socket,&byte,1)==1 && byte=='r');
    char data[384]; unsigned seen[96]={0},count=0;
    if (cancel) {
        long n=syscall(SYS_getdents64,directory,data,sizeof(data));
        assert(n==-1 && errno==EINTR);
    }
    assert(syscall(SYS_getdents64,directory,(void *)1,sizeof(data))==-1 && errno==EFAULT);
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    char *partial=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(partial!=MAP_FAILED && !mprotect(partial+page,page,PROT_NONE));
    assert(syscall(SYS_getdents64,directory,partial+page-16,sizeof(data))==-1 && errno==EFAULT);
    assert(!munmap(partial,page*2));
    for (;;) {
        long n=syscall(SYS_getdents64,directory,data,sizeof(data)); assert(n>=0);
        if (!n) break;
        for (size_t at=0;at<(size_t)n;) {
            struct md_directory_entry *entry=(void *)(data+at);
            assert(entry->size>offsetof(struct md_directory_entry,name) && at+entry->size<=(size_t)n);
            if (strcmp(entry->name,".") && strcmp(entry->name,"..")) {
                unsigned index=96;
                assert(sscanf(entry->name,"entry-%u",&index)==1 && index<96 && !seen[index]++);
                ++count;
            }
            at+=entry->size;
        }
    }
    if (count!=96) fprintf(stderr,"directory entries: expected=96 actual=%u cancelled=%d\n",count,cancel);
    _exit(count==96 ? 0 : 97);
}
static void unexpected_delegate(const struct seccomp_notif *request) {
    fprintf(stderr,"unexpected broker fallback: nr=%d pid=%u\n",request->data.nr,request->pid);
    assert(0);
}
static void unexpected_completion(pid_t pid,struct md_broker_completion *result) {
    fprintf(stderr,"unexpected open recovery: pid=%d error=%d\n",pid,result->result.error);
    assert(0);
}
static void failed_opens(const char *store) {
    unsigned before=descriptors();
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        alarm(25); close(sockets[0]);
        int fd=listener(SYS_openat); send_listener(sockets[1],fd); close(fd);
        char byte;
        /* EVENT_WAIT: parent publishes the broker before issuing real opens. */
        assert(read(sockets[1],&byte,1)==1 && byte=='r');
        for (unsigned i=0;i<8;++i) {
            assert(open("/missing",O_RDONLY|O_CLOEXEC)==-1 && errno==ENOENT);
            assert(open("/entries/entry-000/child",O_RDONLY)==-1 && errno==ENOTDIR);
            assert(open("/entries",O_WRONLY)==-1 && errno==EISDIR);
            assert(open("/entries/entry-000",O_CREAT|O_EXCL|O_RDWR,0600)==-1 && errno==EEXIST);
        }
        assert(write(sockets[1],"d",1)==1); _exit(0);
    }
    close(sockets[1]); int fd=receive_listener(sockets[0]);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-open-errors-%d-%d",parent,child);
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(worker)};
    assert(resources.fs && resources.files);
    struct md_identity id=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,child,child,&id,&resources));
    struct md_namespace_broker *broker=md_broker_create(worker,1,unexpected_delegate); assert(broker);
    md_broker_completion_handler(broker,unexpected_completion);
    assert(!md_broker_task(broker,child,MD_BROKER_READ|MD_BROKER_OPEN,0,0,NULL,1));
    md_broker_listen(broker,fd);
    assert(write(sockets[0],"r",1)==1);
    for (;;) {
        struct pollfd events[]={{sockets[0],POLLIN,0},{md_fs_worker_fd(worker),POLLIN,0}};
        /* EVENT_WAIT: direct replies complete without a supervisor recovery. */
        assert(poll(events,2,5000)>0);
        if (events[1].revents) assert(!md_fs_worker_dispatch(worker));
        if (events[0].revents) break;
    }
    char byte; assert(read(sockets[0],&byte,1)==1 && byte=='d');
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_fs_worker_error(worker)); md_broker_forget(broker,child);
    assert(!md_fs_worker_stop(worker)); md_broker_destroy(broker);
    md_task_registry_destroy(tasks); md_identity_release(&id);
    md_domain_release(resources.fs); md_fd_origins_release(resources.files);
    close(fd); close(sockets[0]); assert(descriptors()==before);
    puts("PASS failed open replies: ENOENT/ENOTDIR/EISDIR/EEXIST, no ptrace or task recovery, no FD leak");
}
static void run(const char *store,const char *native,int directory,int cancel) {
    unsigned before=descriptors();
    assert(lseek(directory,0,SEEK_SET)==0);
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    assert(!pipe2(signal_pipe,O_CLOEXEC));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) { close(channel[0]); close(signal_pipe[0]); reader(channel[1],directory,cancel,parent); }
    close(channel[1]); close(signal_pipe[1]);
    int fd=receive_listener(channel[0]);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-dir-broker-%d-%d",parent,child);
    struct md_fs_attachment attachment={.source=native,.target="/native"};
    struct md_fs_worker_launch launch={.attachments=native ? &attachment : NULL,.attachment_count=native ? 1 : 0};
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,&launch,&worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(worker)};
    assert(resources.fs && resources.files);
    struct md_identity identity=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,child,child,&identity,&resources));
    struct md_namespace_broker *broker=md_broker_create(worker,1,unexpected_delegate); assert(broker);
    assert(!md_broker_task(broker,child,1,0,0,NULL,1));
    atomic_store(&cancel_pid,cancel ? child : 0);
    md_broker_listen(broker,fd);
    assert(write(channel[0],"r",1)==1);
    /* EVENT_WAIT: reader completion, bounded by the child and fixture alarms. */
    int status; assert(waitpid(child,&status,0)==child);
    assert(!md_fs_worker_error(worker));
    md_broker_forget(broker,child);
    assert(!md_fs_worker_stop(worker)); md_broker_destroy(broker);
    md_task_registry_destroy(tasks); md_identity_release(&identity);
    md_domain_release(resources.fs); md_fd_origins_release(resources.files);
    assert(!close(fd) && !close(channel[0]) && !close(signal_pipe[0]));
    assert(!atomic_load(&cancel_pid) && descriptors()==before);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    printf("PASS broker directory publication: native=%d cancelledAfterCopy=%d faultAndPartialCopy=1 complete=96 noDescriptorLeak=1\n",native!=NULL,cancel);
}
extern char result_return[];
extern long result_call(unsigned long,unsigned long,unsigned long,unsigned long,unsigned long,unsigned long,long);
__asm__(".text\n.balign 4\n.global result_call\nresult_call:\n"
    "mov x8,x6\nsvc #0\n.global result_return\nresult_return:\nret\n");
static int discovery_listener;
static atomic_uint discovery_calls;
static void discovery_delegate(const struct seccomp_notif *request) {
    uintptr_t offset=request->data.instruction_pointer-(uintptr_t)md_test_syscall_sites;
    assert(request->data.nr==SYS_faccessat && (request->data.instruction_pointer==(uintptr_t)result_return
        || (offset<MD_TEST_SYSCALL_SITES*MD_TEST_SYSCALL_STRIDE && offset%MD_TEST_SYSCALL_STRIDE==8)));
    atomic_fetch_add(&discovery_calls,1);
    struct seccomp_notif_resp reply={.id=request->id,.error=-EAGAIN};
    assert(!ioctl(discovery_listener,SECCOMP_IOCTL_NOTIF_SEND,&reply));
}
static void discovery(const char *store) {
    unsigned before=descriptors();
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(99);
        alarm(25); close(channel[0]);
        int fd=listener(SYS_faccessat); send_listener(channel[1],fd); close(fd);
        char byte;
        for (unsigned i=0;i<6+MD_TEST_SYSCALL_SITES*2+6;++i) {
            /* EVENT_WAIT: parent advances this exact discovery generation. */
            assert(read(channel[1],&byte,1)==1 && byte=='r');
            unsigned site=i<6 || i>=6+MD_TEST_SYSCALL_SITES*2 ? 0 : (i-6)/2;
            md_test_syscall_fn call=i<6 ? result_call : md_test_syscall_site(site);
            long result=call(AT_FDCWD,(uintptr_t)"/entries",F_OK,0,0,0,SYS_faccessat);
            int cold=i>=6+MD_TEST_SYSCALL_SITES*2+4 || (i<6 ? i==0 || i==3 || i==4 : !(i%2));
            assert(result==(cold ? -EAGAIN : 0));
            assert(write(channel[1],"d",1)==1);
        }
        _exit(0);
    }
    close(channel[1]); discovery_listener=receive_listener(channel[0]);
    atomic_store(&discovery_calls,0);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-discovery-%d-%d",parent,child);
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(worker)};
    assert(resources.fs && resources.files);
    struct md_identity identity=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,child,child,&identity,&resources));
    struct md_namespace_broker *broker=md_broker_create(worker,1,discovery_delegate); assert(broker);
    unsigned eligible=MD_BROKER_READ|MD_BROKER_DISCOVERY;
    assert(!md_broker_task(broker,child,eligible,0,0,NULL,1));
    md_broker_listen(broker,discovery_listener);
    for (unsigned i=0;i<6+MD_TEST_SYSCALL_SITES*2+6;++i) {
        if (i==2) md_broker_resample(broker,child,(uintptr_t)result_return+4);
        if (i==3) md_broker_resample(broker,child,(uintptr_t)result_return);
        if (i==4) md_broker_resample(broker,child,0);
        if (i==5) assert(!md_broker_task(broker,child,eligible,0,0,NULL,1));
        if (i==6+MD_TEST_SYSCALL_SITES*2+2) {
            md_broker_forget(broker,child);
            assert(!md_broker_task(broker,child,eligible,0,0,NULL,1));
        }
        if (i==6+MD_TEST_SYSCALL_SITES*2+4)
            assert(!md_broker_task(broker,child,MD_BROKER_COOPERATIVE|MD_BROKER_DISCOVERY,0,0,NULL,1));
        assert(write(channel[0],"r",1)==1);
        char byte;
        struct pollfd ready[]={{.fd=channel[0],.events=POLLIN},{.fd=md_fs_worker_fd(worker),.events=POLLIN}};
        /* EVENT_WAIT: execute delegated completions until the child confirms
         * its request; timeout fails the fixture rather than settling state. */
        do {
            assert(poll(ready,2,5000)>0);
            if (ready[1].revents) assert(!md_fs_worker_dispatch(worker));
        } while (!ready[0].revents);
        assert(read(channel[0],&byte,1)==1 && byte=='d');
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(atomic_load(&discovery_calls)==7+MD_TEST_SYSCALL_SITES && !md_fs_worker_error(worker));
    md_broker_forget(broker,child);
    assert(!md_fs_worker_stop(worker)); md_broker_destroy(broker);
    md_task_registry_destroy(tasks); md_identity_release(&identity);
    md_domain_release(resources.fs); md_fd_origins_release(resources.files);
    assert(!close(discovery_listener) && !close(channel[0]) && descriptors()==before);
    puts("PASS broker discovery: one cold delegation, exact/all-site resampling, warm calls and eligibility refresh");
    puts("PASS broker discovery cache: 32 sites, bounded repeat suppression, evicted-site retry and new owner");
    puts("PASS broker admission: entry and discovery flags do not authorize raw notifications");
}
static void completed_result(const char *store,int nr) {
    unsigned before=descriptors();
    struct seccomp_data call={.nr=nr,.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=(uintptr_t)result_return,
        .args={6,11,22,33,44,55}};
    if (nr==SYS_gettid) memset(call.args+1,0,5*sizeof(call.args[0]));
    int source=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(source>=0);
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL));
        if (getppid()!=parent) _exit(99);
        alarm(25); close(channel[0]); close(source);
        int fd=listener(nr); send_listener(channel[1],fd); close(fd);
        char byte;
        /* EVENT_WAIT: parent publishes this exact result before admission. */
        assert(read(channel[1],&byte,1)==1 && byte=='r');
        for (unsigned i=0;i<6;++i) {
            call.args[i]^=1;
            assert(result_call(call.args[0],call.args[1],call.args[2],call.args[3],call.args[4],call.args[5],nr)==-EPERM);
            call.args[i]^=1;
        }
        long result=result_call(call.args[0],call.args[1],call.args[2],call.args[3],call.args[4],call.args[5],nr);
        assert(result>=0 && fcntl(result,F_GETFD)==FD_CLOEXEC && read(result,&byte,1)==0);
        assert(result_call(call.args[0],call.args[1],call.args[2],call.args[3],call.args[4],call.args[5],nr)==-EPERM);
        assert(write(channel[1],&result,sizeof(result))==sizeof(result));
        /* EVENT_WAIT: result retirement is acknowledged before receiver exit. */
        assert(read(channel[1],&byte,1)==1 && byte=='f');
        assert(!close(result)); _exit(0);
    }
    close(channel[1]);
    int fd=receive_listener(channel[0]);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-result-broker-%d-%d",parent,child);
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
    struct md_fd_origins *files=md_fd_origins_new(); assert(files);
    struct md_namespace_broker *broker=md_broker_create(worker,1,unexpected_delegate); assert(broker);
    assert(!md_broker_task(broker,child,0,0,0,NULL,1));
    long value=-123;
    assert(!md_broker_result_finish(broker,child,&value) && value==-123);
    assert(!md_broker_result_begin(broker,child,&call,files,source,NULL,O_CLOEXEC));
    assert(md_broker_result_begin(broker,child,&call,files,source,NULL,O_CLOEXEC)==-EBUSY);
    md_broker_listen(broker,fd);
    assert(write(channel[0],"r",1)==1);
    long result;
    /* EVENT_WAIT: child confirms successful admission and rejected replay. */
    assert(read(channel[0],&result,sizeof(result))==sizeof(result));
    assert(md_broker_result_finish(broker,child,&value)==1 && value==result);
    value=-123; assert(!md_broker_result_finish(broker,child,&value) && value==-123);
    assert(write(channel[0],"f",1)==1);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!md_fs_worker_error(worker)); md_broker_forget(broker,child);
    assert(!md_fs_worker_stop(worker)); md_broker_destroy(broker);
    md_task_registry_destroy(tasks); md_fd_origins_release(files);
    assert(!close(source) && !close(fd) && !close(channel[0]) && descriptors()==before);
    printf("PASS completed FD result: carrier=%d exact arguments, rejected replay, confirmed result and no FD leak\n",nr);
}
static unsigned native_provider_released;
static void native_provider_release(struct md_fd_object *object) {
    (void)object; ++native_provider_released;
}
static void managed_proc_reads(const char *store) {
    unsigned before=descriptors();
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new()};
    assert(resources.fs && resources.files);
    struct md_identity id=md_identity_new(1000,1000);
    assert(!md_task_registry_publish(tasks,getpid(),getpid(),&id,&resources));
    struct md_task_snapshot actor={.tid=getpid()+100000,.tgid=getpid()+100000,
        .identity=id,.fs=resources.fs};
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,0,&s));
    struct md_filesystem fs={.store=s,.actor=&actor,.tasks=tasks};
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,sockets));
    char path[96],data[256],expected[256];
    snprintf(path,sizeof(path),"/proc/%d/fd/%d",getpid(),sockets[0]);
    ssize_t length=readlink(path,expected,sizeof(expected)); assert(length>0);
    struct stat native; assert(!fstat(sockets[0],&native));
    struct md_inode_boundary boundary={.path=path,.capacity=sizeof(path)};
    struct md_pid_namespace *private=md_pid_namespace_new(NULL,id.user_namespace); assert(private);
    const uint64_t views[]={0,md_pid_namespace_inode(NULL),md_pid_namespace_inode(private)};
    struct md_fs_output output={.data=data,.capacity=sizeof(data)};
    struct md_fd_order busy={0};
    const struct md_fd_object_type provider={.destroy=native_provider_release};
    struct md_fd_object typed;
    md_fd_object_init(&typed,&provider);
    int dumpable=prctl(PR_GET_DUMPABLE); assert(dumpable>=0);
    assert(!prctl(PR_SET_DUMPABLE,1));
    for (unsigned scenario=0;scenario<7;++scenario) {
        if (scenario==1) {
            assert(md_fd_order_begin(resources.files,&busy,NULL)==1);
            assert(!md_fd_order_start(&busy));
        }
        /* Proc path inspection uses FSCREDS, not the effective UID alone. */
        if (scenario==2) actor.identity.uid.fs=1001;
        if (scenario==3) {
            struct md_fd_origin_update update;
            assert(!md_fd_origins_prepare_object(resources.files,&typed,0,&update));
            md_fd_origins_finish(&update,sockets[0]);
        }
        if (scenario==4) {
            assert(!close(sockets[0]));
            int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
            if (fd!=sockets[0]) { assert(dup2(fd,sockets[0])==sockets[0]); close(fd); }
            assert(!fstat(sockets[0],&native));
            length=readlink(path,expected,sizeof(expected)); assert(length>0);
        }
        if (scenario==5) {
            /* A native service owner is authenticated in this registry but has
             * no guest file table. Its proc observations retain kernel policy. */
            assert(!md_task_registry_publish(tasks,getpid(),getpid(),&id,NULL));
            actor.identity.uid.fs=1001;
        }
        if (scenario==6) assert(!prctl(PR_SET_DUMPABLE,0));
        for (unsigned view=0;view<3;++view) for (unsigned op=0;op<3;++op) {
            boundary.proc_namespace=views[view];
            struct md_fs_request q={.operation=op==2 ? MD_FS_READLINK : MD_FS_STAT,
                .flags=op==1 ? AT_SYMLINK_NOFOLLOW : 0};
            struct md_fs_result out={.fd=-1,.error=-EREMOTE};
            md_fs_native_boundary(&fs,&boundary,&q,&out,&output);
            if (view==2 || (scenario>=1 && scenario<=3) || scenario==6) {
                if (out.error!=-EREMOTE)
                    fprintf(stderr,"proc boundary scenario=%u operation=%u result=%d\n",scenario,op,out.error);
                assert(out.error==-EREMOTE); continue;
            }
            if (out.error || out.host_path || out.fd!=-1)
                fprintf(stderr,"proc boundary scenario=%u view=%u operation=%u result=%d fd=%d\n",
                    scenario,view,op,out.error,out.fd);
            assert(!out.error && !out.host_path && out.fd==-1);
            if (op==2) assert(out.size==(size_t)length && !memcmp(data,expected,out.size));
            else if (op==1) assert(S_ISLNK(out.info.mode));
            else assert(out.info.inode==native.st_ino && out.info.mode==native.st_mode);
        }
        if (scenario==1) assert(!md_fd_order_complete(&busy));
        if (scenario==2) actor.identity.uid.fs=1000;
        if (scenario==3) assert(!md_fd_origins_close_range(resources.files,sockets[0],sockets[0],0));
    }
    assert(!prctl(PR_SET_DUMPABLE,dumpable));
    md_pid_namespace_release(private);
    assert(!close(sockets[0]) && !close(sockets[1]));
    md_task_registry_destroy(tasks); md_fd_origins_release(resources.files);
    md_domain_release(resources.fs); md_identity_release(&id);
    md_fd_object_release(&typed); assert(native_provider_released==1);
    md_inode_store_close(s);
    failed_opens(store);
    assert(descriptors()==before);
    puts("PASS managed proc observations: socket identity, descriptor reuse, credentials, busy table and typed provider fallback, no FD leak");
}
static void stopped_queued_entry(const char *store,int status,int admission) {
    unsigned before=descriptors();
    pid_t pid=getpid();
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    struct md_fs_worker *worker=NULL;
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-queued-entry-%d",pid);
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(worker)};
    assert(resources.fs && resources.files);
    struct md_identity id=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,pid,pid,&id,&resources));
    struct md_namespace_broker *broker=md_broker_create(worker,0,unexpected_delegate); assert(broker);
    assert(!md_broker_task(broker,pid,MD_BROKER_COOPERATIVE,0,0,NULL,1));
    struct md_entry_channel channel;
    assert(!md_broker_cooperative_bind(broker,pid,&channel,1));
    assert(atomic_load(&channel.enabled) && channel.owner==pid);
    /* Valid but unused notification source: this case exercises direct entry. */
    int notification=listener(SYS_reboot);
    md_broker_listen(broker,notification);
    struct md_fd_order owner={0};
    assert(md_fd_order_begin(resources.files,&owner,NULL)==1 && !md_fd_order_start(&owner));
    int fd=open("/dev/null",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    int queued_wake=status==((PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f);
    if (queued_wake) {
        queued_table=resources.files; queued_owner=syscall(SYS_gettid);
        atomic_store_explicit(&queued_admission,1,memory_order_release);
    }
    channel.sequence=1; channel.call.number=SYS_close; channel.call.args[0]=fd;
    atomic_store_explicit(&channel.state,MD_ENTRY_REQUEST,memory_order_release);
    md_broker_resumed(broker,pid);
    /* EVENT_WAIT: shared admission publication; the fixture alarm bounds loss. */
    while (atomic_load_explicit(&channel.state,memory_order_acquire)==MD_ENTRY_REQUEST) sched_yield();
    assert(atomic_load(&channel.state)==MD_ENTRY_RUNNING);
    if (queued_wake) {
        while (atomic_load_explicit(&queued_admission,memory_order_acquire)!=2) sched_yield();
        struct pollfd ready={.fd=md_fs_worker_fd(worker),.events=POLLIN};
        while (poll(&ready,1,0)==1) assert(!md_fs_worker_dispatch(worker));
        int stopped=0;
        assert(md_broker_native_borrow(broker,pid,&stopped)==-EBUSY);
        atomic_store_explicit(&queued_admission,3,memory_order_release);
        /* EVENT_WAIT: QUEUED itself wakes the borrower, before the retained
         * table turn or any guest syscall can supply another event. */
        assert(poll(&ready,1,5000)==1 && !md_fs_worker_dispatch(worker));
        assert(md_broker_native_borrow(broker,pid,&stopped)==1 && !stopped);
        md_broker_native_return(broker,pid);
        atomic_store(&queued_admission,0); queued_table=NULL;
    }
    assert(!md_broker_pending_events(broker));
    /* An undecoded syscall stop belongs to its barrier, not retirement.
     * Even an input register resembling a result cannot complete its journal. */
    channel.native.number=SYS_close; channel.native.result=fd;
    atomic_store(&channel.native.state,MD_NATIVE_RUNNING);
    md_fd_view_publish(&channel.descriptor,fd,FD_CLOEXEC,S_IFCHR);
    md_broker_park(broker,pid);
    assert(!md_broker_pending_events(broker) && !atomic_load(&channel.descriptor.descriptor));
    assert(atomic_load(&channel.native.state)==MD_NATIVE_RUNNING && channel.native.result==fd);
    assert(atomic_load(&channel.state)==MD_ENTRY_RUNNING && fcntl(fd,F_GETFD)==FD_CLOEXEC);
    assert(md_broker_hold_event(broker,pid,status,NULL)==1);
    assert(md_broker_pending_events(broker));
    /* Publishing policy cannot release a kernel stop owned by retirement. */
    assert(!md_broker_task(broker,pid,MD_BROKER_COOPERATIVE,0,0,NULL,0));
    assert(md_broker_pending_events(broker));
    pid_t observed=0; int returned=0;
    while (!observed) {
        /* EVENT_WAIT: retirement publishes the exact stop before the competing
         * resource owner releases; timeout is a regression failure. */
        struct pollfd ready={.fd=md_fs_worker_fd(worker),.events=POLLIN};
        assert(poll(&ready,1,5000)==1);
        assert(!md_fs_worker_dispatch(worker));
        int stopped=0;
        int borrowed=md_broker_native_borrow(broker,pid,&stopped);
        assert(borrowed==admission);
        /* Injection owns the held boundary until its registers are restored.
         * Publishing it early replays the old stop as an injected-call event. */
        if (borrowed==1) {
            assert(stopped && !md_broker_take_event(broker,&returned,NULL));
            assert(md_broker_pending_events(broker));
            md_broker_native_return(broker,pid);
        }
        observed=md_broker_take_event(broker,&returned,NULL);
    }
    assert(observed==pid && returned==status && !md_broker_pending_events(broker));
    assert(atomic_load(&channel.state)==MD_ENTRY_RESPONSE && channel.receipt==1
        && channel.outcome==MD_ENTRY_DECLINED);
    /* The stopped task cannot publish another ordinary call, but an owned
     * adapter may already have published a control/acknowledgement. Preserve
     * it for resume, including when a signal arrives before worker admission. */
    for (unsigned kind=MD_ENTRY_BUFFER_CONTROL;kind<=MD_ENTRY_NATIVE_REQUEST;++kind) {
        if (kind==MD_ENTRY_EXECUTION_BEGIN) continue;
        channel.sequence=2; channel.call.reserved=kind;
        atomic_store_explicit(&channel.state,MD_ENTRY_REQUEST,memory_order_release);
        assert(!md_broker_hold_event(broker,pid,status,NULL));
        assert(atomic_load(&channel.state)==MD_ENTRY_REQUEST && channel.receipt==1);
    }
    channel.call.reserved=MD_ENTRY_EXECUTION_BEGIN;
    assert(!md_broker_hold_event(broker,pid,status,NULL));
    assert(atomic_load(&channel.state)==MD_ENTRY_RESPONSE && channel.receipt==2
        && channel.outcome==MD_ENTRY_DECLINED);
    assert(md_fd_order_state(&owner)==MD_RESOURCE_ORDER_RUNNING && fcntl(fd,F_GETFD)==FD_CLOEXEC);
    assert(!md_fd_order_complete(&owner) && !close(fd));
    md_broker_forget(broker,pid);
    assert(!md_fs_worker_stop(worker)); md_broker_destroy(broker);
    assert(!close(notification));
    md_task_registry_destroy(tasks); md_domain_release(resources.fs);
    md_fd_origins_release(resources.files); md_identity_release(&id);
    assert(descriptors()==before);
    puts("PASS stopped cooperative reservation: queued close retires without waiting for the stopped table owner or executing an effect");
}
static struct {
    struct md_namespace_broker *broker;
    struct md_fs_worker *worker;
    struct md_entry_channel channel;
    int listener,received,control,native;
    pid_t owner;
} registration;
static void registration_control(pid_t pid,struct md_entry_channel *c) {
    assert(syscall(SYS_gettid)==registration.owner && pid==c->owner);
    assert((c->call.reserved==MD_ENTRY_BUFFER_CONTROL || c->call.reserved==MD_ENTRY_EFFECT_DONE)
        && atomic_load(&c->state)==MD_ENTRY_RUNNING);
    int stopped=0;
    assert(md_broker_native_borrow(registration.broker,pid,&stopped)==-EBUSY);
    if (registration.native && c->call.reserved==MD_ENTRY_BUFFER_CONTROL) {
        uint64_t args[6]={7,0,1,registration.native==2 ? (uint64_t)-1 : 0};
        md_broker_entry_effect(registration.broker,c,SYS_epoll_pwait,args);
        assert(atomic_load(&c->control_awake)==(registration.native==1));
        return;
    }
    md_broker_entry_reply(registration.broker,c,MD_ENTRY_COMPLETED,0);
}
static void registration_delegate(const struct seccomp_notif *request) {
    assert(request->data.nr==SYS_gettid && !registration.received++);
    struct md_entry_channel *c=&registration.channel;
    assert(!md_broker_task(registration.broker,request->pid,MD_BROKER_COOPERATIVE,0,0,NULL,1));
    assert(!md_broker_cooperative_bind(registration.broker,request->pid,c,1));
    struct seccomp_notif_resp reply={.id=request->id,.val=request->pid};
    assert(!ioctl(registration.listener,SECCOMP_IOCTL_NOTIF_SEND,&reply));
    /* Publish the next caller request while this delegated operation still
     * owns the task. Force the worker's pre-wait scan to precede retirement. */
    c->sequence=1; c->call.number=SYS_getuid;
    c->call.reserved=registration.control ? MD_ENTRY_BUFFER_CONTROL : MD_ENTRY_SYSCALL;
    atomic_store(&c->sleeping,1);
    atomic_store(&c->state,MD_ENTRY_REQUEST);
    md_broker_resumed(registration.broker,request->pid);
    if (registration.control) {
        struct md_entry_channel primer;
        if (registration.control==2) {
            assert(!md_broker_task(registration.broker,registration.owner,MD_BROKER_COOPERATIVE,0,0,NULL,1));
            assert(!md_broker_cooperative_bind(registration.broker,registration.owner,&primer,1));
            primer.sequence=1; primer.call.reserved=MD_ENTRY_BUFFER_CONTROL;
            atomic_store_explicit(&primer.state,MD_ENTRY_REQUEST,memory_order_release);
            md_broker_resumed(registration.broker,registration.owner);
        }
        assert(!!md_broker_entry_progress(registration.broker)==(registration.control==2));
        assert(!atomic_load(&c->control_awake));
        if (registration.control==2) {
            assert(atomic_load(&primer.state)==MD_ENTRY_RESPONSE && primer.receipt==1);
            md_broker_forget(registration.broker,registration.owner);
        }
    } else {
        /* EVENT_WAIT: worker acknowledges its park intent; alarm bounds failure. */
        while (atomic_load(&c->awake)) sched_yield();
    }
    assert(atomic_load(&c->state)==MD_ENTRY_REQUEST);
}
static void *registration_publish(void *unused) {
    (void)unused;
    struct md_entry_channel *c=&registration.channel;
    for (uint64_t sequence=4;sequence<132;++sequence) {
        c->sequence=sequence;
        atomic_store_explicit(&c->state,MD_ENTRY_REQUEST,memory_order_release);
        md_broker_resumed(registration.broker,c->owner);
        /* EVENT_WAIT: the control owner publishes this exact receipt; the
         * fixture alarm fails a lost wake across snapshot/park handoff. */
        while (atomic_load_explicit(&c->state,memory_order_acquire)!=MD_ENTRY_RESPONSE) sched_yield();
        assert(c->receipt==sequence && c->outcome==MD_ENTRY_COMPLETED);
    }
    return NULL;
}
static void registration_retirement(const char *store,int control) {
    unsigned before=descriptors();
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    pid_t parent=getpid(),child=fork(); assert(child>=0);
    if (!child) {
        assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==parent);
        close(sockets[0]);
        int fd=listener(SYS_gettid); send_listener(sockets[1],fd); close(fd);
        char byte;
        /* EVENT_WAIT: parent installs the broker, then retires the request. */
        assert(read(sockets[1],&byte,1)==1 && byte=='r');
        assert(syscall(SYS_gettid)==getpid());
        assert(read(sockets[1],&byte,1)==1 && byte=='c'); _exit(0);
    }
    close(sockets[1]); memset(&registration,0,sizeof(registration));
    registration.control=control; registration.owner=syscall(SYS_gettid);
    registration.listener=receive_listener(sockets[0]);
    struct md_task_registry *tasks=md_task_registry_create(); assert(tasks);
    char endpoint[80]; snprintf(endpoint,sizeof(endpoint),"md-entry-retirement-%d",parent);
    assert(!md_fs_worker_start(store,endpoint,NULL,tasks,NULL,NULL,&registration.worker));
    struct md_task_resources resources={.fs=md_domain_new(),.files=md_fd_origins_new(),
        .mounts=md_fs_worker_mounts(registration.worker)};
    assert(resources.fs && resources.files);
    struct md_identity identity=md_identity_new(0,0);
    assert(!md_task_registry_publish(tasks,child,child,&identity,&resources));
    registration.broker=md_broker_create(registration.worker,0,registration_delegate);
    md_broker_entry_handler(registration.broker,registration_control);
    md_broker_entry_target(registration.broker,parent);
    assert(registration.broker && !md_broker_task(registration.broker,child,MD_BROKER_READ,0,0,NULL,1));
    md_broker_listen(registration.broker,registration.listener);
    assert(write(sockets[0],"r",1)==1);
    while (!registration.received) {
        /* EVENT_WAIT: supervisor receives the delegated registration. */
        struct pollfd ready={md_fs_worker_fd(registration.worker),POLLIN,0};
        assert(poll(&ready,1,5000)==1 && !md_fs_worker_dispatch(registration.worker));
    }
    struct md_entry_channel *c=&registration.channel;
    assert(c->control_target==parent);
    for (;;) {
        if (control) md_broker_entry_progress(registration.broker);
        unsigned state=atomic_load(&c->state);
        if (state==MD_ENTRY_RESPONSE) break;
        assert(state==MD_ENTRY_REQUEST || state==MD_ENTRY_RUNNING);
        const struct timespec bound={.tv_sec=5};
        /* EVENT_WAIT: retirement alone must wake the parked worker. No later
         * guest syscall or external event is allowed to rescue this request. */
        long r=syscall(SYS_futex,&c->state,FUTEX_WAIT,state,&bound,NULL,0);
        assert(!r || errno==EAGAIN || errno==EINTR);
    }
    assert(atomic_load(&c->state)==MD_ENTRY_RESPONSE && c->receipt==1
        && c->outcome==MD_ENTRY_COMPLETED && !c->result);
    if (control) for (unsigned update=0;update<2;++update) {
        assert(!atomic_load(&c->control_awake));
        int stopped=(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f;
        assert(!md_broker_hold_event(registration.broker,child,stopped,NULL));
        c->sequence=update+2;
        atomic_store(&c->state,MD_ENTRY_REQUEST);
        assert(!md_broker_task(registration.broker,child,MD_BROKER_COOPERATIVE,0,0,NULL,0));
        md_broker_entry_wake(registration.broker,child);
        assert(!md_broker_entry_progress(registration.broker));
        assert(atomic_load(&c->state)==MD_ENTRY_REQUEST);
        if (update) assert(!md_broker_task(registration.broker,child,MD_BROKER_COOPERATIVE,0,0,NULL,1));
        else md_broker_resumed(registration.broker,child);
        /* Resumption alone must target the supervisor, not the worker. */
        assert(md_broker_entry_progress(registration.broker));
        assert(atomic_load(&c->state)==MD_ENTRY_RESPONSE && c->receipt==update+2
            && c->outcome==MD_ENTRY_COMPLETED && !c->result);
        /* A dispatched callback requires one resource-owner progress pass,
         * but an idle channel must not keep the supervisor spinning. */
        assert(!md_broker_entry_progress(registration.broker));
    }
    if (control) {
        pthread_t publisher;
        assert(!pthread_create(&publisher,NULL,registration_publish,NULL));
        for (;;) {
            int progress=md_broker_entry_progress(registration.broker);
            if (atomic_load_explicit(&c->state,memory_order_acquire)==MD_ENTRY_RESPONSE && c->receipt==131) break;
            if (progress) continue;
            /* EVENT_WAIT: either a cold enrollment or a retained hot request
             * wakes this owner; no new notification rescues a missed request. */
            struct pollfd ready={md_fs_worker_fd(registration.worker),POLLIN,0};
            assert(poll(&ready,1,5000)==1 && !md_fs_worker_dispatch(registration.worker));
        }
        assert(!pthread_join(publisher,NULL));
        for (unsigned blocking=0;blocking<2;++blocking) {
            registration.native=blocking+1;
            c->sequence=132+2*blocking; c->call.reserved=MD_ENTRY_BUFFER_CONTROL;
            assert(md_entry_publish_request(c,MD_ENTRY_REQUEST));
            md_broker_resumed(registration.broker,c->owner);
            assert(md_broker_entry_progress(registration.broker));
            assert(atomic_load(&c->state)==MD_ENTRY_NATIVE && !atomic_load(&c->control_awake));
            assert(!md_broker_entry_progress(registration.broker));
            /* Completion after park must enroll the exact control owner,
             * whether the native operation blocked or returned immediately. */
            c->native.result=0; atomic_store(&c->native.state,MD_NATIVE_DONE);
            ++c->sequence; c->call.reserved=MD_ENTRY_EFFECT_DONE;
            assert(md_entry_publish_request(c,MD_ENTRY_REQUEST));
            md_broker_resumed(registration.broker,c->owner);
            assert(md_broker_entry_progress(registration.broker));
            assert(atomic_load(&c->state)==MD_ENTRY_RESPONSE && c->receipt==c->sequence);
        }
        registration.native=0;
    }
    if (control==2) {
        for (unsigned i=0;i<128;++i) {
            c->sequence=136+i; c->call.reserved=MD_ENTRY_BUFFER_CONTROL;
            assert(md_entry_publish_request(c,MD_ENTRY_REQUEST));
            md_broker_entry_wake(registration.broker,INT_MAX);
            assert(!md_broker_entry_progress(registration.broker));
            md_broker_entry_wake(registration.broker,c->owner);
            md_broker_entry_wake(registration.broker,c->owner);
            assert(md_broker_entry_progress(registration.broker));
            assert(c->receipt==136+i && atomic_load(&c->state)==MD_ENTRY_RESPONSE);
            md_broker_entry_wake(registration.broker,c->owner);
            assert(!md_broker_entry_progress(registration.broker));
        }
    }
    assert(write(sockets[0],"c",1)==1); int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    md_broker_forget(registration.broker,child);
    md_broker_entry_wake(registration.broker,child);
    assert(!md_broker_entry_progress(registration.broker));
    assert(!md_fs_worker_stop(registration.worker)); md_broker_destroy(registration.broker);
    close(registration.listener); close(sockets[0]);
    md_task_registry_destroy(tasks); md_domain_release(resources.fs);
    md_fd_origins_release(resources.files); md_identity_release(&identity);
    assert(descriptors()==before);
    puts("PASS delegated retirement: published next request wakes after parked worker without another guest syscall");
}
static void direct_wake_transport(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct md_kernel_action ignored={.handler=(uintptr_t)SIG_IGN},restored;
        assert(!RAW4(rt_sigaction,MD_ENTRY_WAKE_SIGNAL,&ignored,0,8));
        uint64_t before,after;
        assert(!RAW4(rt_sigprocmask,SIG_SETMASK,0,&before,8));
        struct rlimit files,none;
        assert(!getrlimit(RLIMIT_NOFILE,&files));
        none=files; none.rlim_cur=0;
        assert(!setrlimit(RLIMIT_NOFILE,&none));
        assert(md_entry_wakeup_open()==-EMFILE);
        assert(!setrlimit(RLIMIT_NOFILE,&files));
        assert(!RAW4(rt_sigprocmask,SIG_SETMASK,0,&after,8) && before==after);
        assert(!RAW4(rt_sigaction,MD_ENTRY_WAKE_SIGNAL,0,&restored,8));
        assert(restored.handler==ignored.handler && !restored.mask && !restored.flags);
        int fd=md_entry_wakeup_open(); assert(fd>=0);
        const pid_t owner=getpid();
        pid_t sender=fork(); assert(sender>=0);
        if (!sender) {
            for (int i=1;i<=128;++i) assert(!md_entry_wakeup_send(owner,i));
            _exit(0);
        }
        unsigned received=0;
        while (received<128) {
            struct pollfd ready={fd,POLLIN,0};
            /* EVENT_WAIT: the exact native endpoint receives queued caller
             * hints; bounded failure catches coalescing or lost delivery. */
            assert(poll(&ready,1,5000)==1);
            int callers[32],count=md_entry_wakeup_read(fd,callers,32);
            assert(count>0);
            for (int i=0;i<count;++i) assert(callers[i]==(int)++received);
        }
        int status;
        assert(waitpid(sender,&status,0)==sender && WIFEXITED(status) && !WEXITSTATUS(status));
        int caller;
        assert(!md_entry_wakeup_read(fd,&caller,1));
        assert(md_entry_wakeup_send(INT_MAX,owner)==-ESRCH);
        struct rlimit previous,empty;
        assert(!getrlimit(RLIMIT_SIGPENDING,&previous));
        empty=previous; empty.rlim_cur=0;
        assert(!setrlimit(RLIMIT_SIGPENDING,&empty));
        assert(md_entry_wakeup_send(owner,owner)==-EAGAIN);
        assert(!setrlimit(RLIMIT_SIGPENDING,&previous));
        assert(!md_entry_wakeup_send(owner,owner));
        assert(md_entry_wakeup_read(fd,&caller,1)==1 && caller==owner);
        assert(!close(fd)); _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS direct control wake: open rollback, queued identities, bounded reads, dead owner and queue exhaustion");
}
void md_test_namespace_broker(const char *root) {
    setbuf(stdout,NULL); alarm(60);
    direct_wake_transport();
    extern void md_test_notification_result(void);
    md_test_notification_result();
    assert(!mkdir(root,0700));
    char store[4096],native[4096];
    snprintf(store,sizeof(store),"%s/store",root); snprintf(native,sizeof(native),"%s/native",root);
    assert(!mkdir(native,0700));
    struct md_inode_store *s=NULL; assert(!md_inode_store_open(store,1,&s));
    assert(!md_inode_mkdir(s,-1,"/entries",0755));
    assert(!md_inode_mkdir(s,-1,"/native",0755));
    int directory=md_inode_open(s,-1,"/entries",O_RDONLY|O_DIRECTORY|O_CLOEXEC,0);
    int attached=open(native,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(directory>=0 && attached>=0);
    for (unsigned i=0;i<96;++i) {
        char name[32]; snprintf(name,sizeof(name),"entry-%03u",i);
        int fd=md_inode_open(s,directory,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);
        assert(fd>=0 && !close(fd));
        fd=openat(attached,name,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600); assert(fd>=0 && !close(fd));
    }
    md_inode_store_close(s);
    for (int cancelled=0;cancelled<2;++cancelled) {
        run(store,NULL,directory,cancelled);
        run(store,native,attached,cancelled);
        /* A native description without a guest bind uses the same cursor
         * publication contract, not an adapter replay after copying. */
        run(store,NULL,attached,cancelled);
    }
    assert(!close(directory) && !close(attached));
    completed_result(store,SYS_fstat); completed_result(store,SYS_gettid);
    managed_proc_reads(store);
    stopped_queued_entry(store,(PTRACE_EVENT_STOP<<16)|(SIGTRAP<<8)|0x7f,1);
    stopped_queued_entry(store,(PTRACE_EVENT_SECCOMP<<16)|(SIGTRAP<<8)|0x7f,1);
    stopped_queued_entry(store,((SIGTRAP|0x80)<<8)|0x7f,1);
    stopped_queued_entry(store,(SIGCHLD<<8)|0x7f,-EBUSY);
    stopped_queued_entry(store,(SIGSEGV<<8)|0x7f,-EBUSY);
    stopped_queued_entry(store,(PTRACE_EVENT_STOP<<16)|(SIGSTOP<<8)|0x7f,-EBUSY);
    stopped_queued_entry(store,(PTRACE_EVENT_CLONE<<16)|(SIGTRAP<<8)|0x7f,-EBUSY);
    stopped_queued_entry(store,(PTRACE_EVENT_EXEC<<16)|(SIGTRAP<<8)|0x7f,-ESRCH);
    stopped_queued_entry(store,(PTRACE_EVENT_EXIT<<16)|(SIGTRAP<<8)|0x7f,-ESRCH);
    stopped_queued_entry(store,SIGKILL,-ESRCH);
    discovery(store); registration_retirement(store,0); registration_retirement(store,1);
    registration_retirement(store,2); alarm(0);
    puts("PASS broker directory notification ownership");
}
