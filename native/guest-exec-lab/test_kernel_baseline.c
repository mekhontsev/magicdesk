#define _GNU_SOURCE
#include "task_descriptor.h"
#include "task_lifetime.h"
#include "fd_filesystem.h"
#include "linux_abi.h"
#include "kernel_profile.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/magic.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/eventfd.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static void pidfd_filesystem(void) {
    int fd=(int)syscall(SYS_pidfd_open,getpid(),0), event=eventfd(0,EFD_CLOEXEC);
    int file=memfd_create("anon_inode:[pidfd]",MFD_CLOEXEC);
    assert(fd>=0 && event>=0 && file>=0);
    struct statfs native,value;
    int available=!syscall(SYS_fstatfs,fd,&native);
    assert(available || errno==EACCES || errno==EPERM);
    assert(!md_fd_filesystem(fd,&value));
    assert(value.f_type==ANON_INODE_FS_MAGIC || value.f_type==0x50494446);
    assert(value.f_bsize==(unsigned long)getpagesize() && value.f_frsize==(unsigned long)getpagesize() && value.f_namelen==255);
    if (available) assert(!memcmp(&native,&value,sizeof(value)));
    struct sock_filter rules[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fstatfs,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(rules)/sizeof(*rules),rules};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
    struct statfs fallback;
    assert(!md_fd_filesystem(fd,&fallback) && !memcmp(&value,&fallback,sizeof(value)));
    int copy=dup(fd); assert(copy>=0 && !close(fd));
    assert(!md_fd_filesystem(copy,&fallback));
    assert(md_fd_filesystem(event,&fallback)==-EACCES);
    assert(md_fd_filesystem(file,&fallback)==-EACCES);
    close(copy); close(event); close(file);
    puts("PASS pidfd statfs fallback, duplicate and non-pidfd denial");
}

struct thread { int tid, release, eof; };
static int child(void *data) {
    struct thread *t=data;
    assert(!prctl(PR_SET_NAME,"a )\nb",0,0,0));
    /* EVENT_WAIT: the owner releases the thread after descriptor inspection;
     * fixture alarm bounds missing progress. */
    while (!__atomic_load_n(&t->release,__ATOMIC_ACQUIRE)) {
        long r=syscall(SYS_futex,&t->release,FUTEX_WAIT,0,NULL,NULL,0);
        assert(!r || errno==EAGAIN || errno==EINTR);
    }
    return 0;
}
static void exercise(int baseline, int protected, int channel) {
    if (baseline) assert(!md_test_kernel_profile_install(1));
    assert(!prctl(PR_SET_DUMPABLE,!protected));
    struct md_task_lifetime leader={.fd=-1};
    assert(!md_task_lifetime_open(&leader,getpid()));
    if (baseline) assert(leader.proc_stat);
    assert(!md_task_lifetime_alive(&leader)); md_task_lifetime_close(&leader);
    int file=memfd_create("exact-table",MFD_CLOEXEC); assert(file>=0);
    assert(write(file,"a",1)==1 && lseek(file,0,SEEK_SET)==0);
    int pipefd[2]; assert(!pipe2(pipefd,O_CLOEXEC));
    struct thread t={.eof=pipefd[1]};
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    int tid=clone(child,(char *)stack+65536,CLONE_VM|CLONE_SIGHAND|CLONE_THREAD
        |CLONE_PARENT_SETTID|CLONE_CHILD_CLEARTID,&t,&t.tid,NULL,&t.tid);
    assert(tid>0 && !close(pipefd[1]));
    int replacement=memfd_create("other-table",MFD_CLOEXEC); assert(replacement>=0);
    assert(write(replacement,"b",1)==1 && lseek(replacement,0,SEEK_SET)==0);
    assert(dup3(replacement,file,O_CLOEXEC)==file); close(replacement);
    int process=-1, copied=md_task_descriptor_copy(tid,file,&process);
    if (baseline) assert(copied==-ENOTSUP && md_task_descriptor_needs_export(copied));
    else if (copied>=0) { char byte=0; assert(read(copied,&byte,1)==1 && byte=='a'); close(copied); }
    else assert(md_task_descriptor_needs_export(copied));
    if (process>=0) close(process);
    struct md_task_lifetime task={.fd=-1}, copy={.fd=-1};
    assert(!md_task_lifetime_open(&task,tid));
    if (baseline) assert(task.proc_stat);
    assert(!md_task_lifetime_copy(&copy,&task));
    assert(!md_task_lifetime_alive(&task) && !md_task_lifetime_alive(&copy));
    __atomic_store_n(&t.release,1,__ATOMIC_RELEASE);
    assert(syscall(SYS_futex,&t.release,FUTEX_WAKE,1,NULL,NULL,0)>=0);
    /* EVENT_WAIT: table release produces pipe EOF; clear-child-tid permits
     * stack disposal. Both are bounded by the fixture alarm. */
    char byte; assert(read(pipefd[0],&byte,1)==0);
    int current;
    while ((current=__atomic_load_n(&t.tid,__ATOMIC_ACQUIRE))) {
        long r=syscall(SYS_futex,&t.tid,FUTEX_WAIT,current,NULL,NULL,0);
        assert(!r || errno==EAGAIN || errno==EINTR);
    }
    /* The outer parent waits for full group reaping before checking these
     * retained handles. clear-child-tid is not a terminal pidfd event. */
    union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control={0};
    struct iovec vector={&task.proc_stat,sizeof(task.proc_stat)};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS; header->cmsg_len=CMSG_LEN(2*sizeof(int));
    int handles[]={task.fd,copy.fd}; memcpy(CMSG_DATA(header),handles,sizeof(handles));
    assert(sendmsg(channel,&message,0)==sizeof(task.proc_stat));
    md_task_lifetime_close(&task); md_task_lifetime_close(&copy);
    close(file); close(pipefd[0]); assert(!munmap(stack,65536));
    printf("PASS exact-task handles baseline=%d protected=%d\n",baseline,protected);
}
int main(int argc, char **argv) {
    setbuf(stdout,NULL);
    if (argc>1) {
        if (argc<3 || strcmp(argv[1],"--host")) {
            fputs("usage: kernel_baseline [--host NATIVE-FIXTURE ...]\n",stderr); return 2;
        }
        assert(!md_test_kernel_profile_install(1));
        execv(argv[2],argv+2); perror("baseline exec"); return 126;
    }
    alarm(20);
    pid_t metadata=fork(); assert(metadata>=0);
    if (!metadata) { pidfd_filesystem(); _exit(0); }
    int metadata_status;
    assert(waitpid(metadata,&metadata_status,0)==metadata && WIFEXITED(metadata_status) && !WEXITSTATUS(metadata_status));
    assert(!md_task_descriptor_needs_export(-EBADF));
    assert(!md_task_descriptor_needs_export(-EINVAL));
    for (int baseline=0;baseline<2;++baseline) for (int protected=0;protected<2;++protected) {
        int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,channel));
        pid_t pid=fork(); assert(pid>=0);
        if (!pid) { close(channel[0]); exercise(baseline,protected,channel[1]); _exit(0); }
        close(channel[1]);
        /* EVENT_WAIT: full child-group reaping bounds final task teardown. */
        int status; assert(waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status));
        union { struct cmsghdr align; char bytes[CMSG_SPACE(2*sizeof(int))]; } control={0};
        int kind=-1, handles[2]; struct iovec vector={&kind,sizeof(kind)};
        struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
        assert(recvmsg(channel[0],&message,MSG_CMSG_CLOEXEC)==sizeof(kind));
        struct cmsghdr *header=CMSG_FIRSTHDR(&message);
        assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS && header->cmsg_len==CMSG_LEN(sizeof(handles)));
        memcpy(handles,CMSG_DATA(header),sizeof(handles));
        for (unsigned i=0;i<2;++i) {
            struct md_task_lifetime task={handles[i],kind};
            assert(md_task_lifetime_alive(&task)==-ENOENT); md_task_lifetime_close(&task);
        }
        close(channel[0]);
    }
    puts("PASS exact task kernel baseline");
}
