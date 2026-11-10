#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/fs.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <elf.h>
#include <unistd.h>
#include "mapping_observation.h"

/* Kernel feasibility fixture, not an adapter implementation. The helper shares
 * an address space but never a file table. Production use additionally needs
 * ptrace-owned mapping commit, signals, debugger invisibility and reaping. */
struct mapping {
    int control, parent, full, protected, killed, thread, shared;
    int tid, phase, caller_tid;
    void *address, *stack; size_t size;
    dev_t device; ino_t inode;
};
static int observation_socket=-1;
struct mapping_source { pid_t pid; dev_t device; ino_t inode; int killed, shared; };
extern long mapping_mmap(void *, size_t, int, int, int, long);
extern char mapping_mmap_return[];
extern char mapping_abort_entry[],mapping_abort_return[];
__asm__(".text\n.global mapping_mmap\nmapping_mmap:\n"
    "mov x8, #222\nsvc #0\n.global mapping_mmap_return\nmapping_mmap_return:\nret\n"
    ".global mapping_abort_entry\nmapping_abort_entry:\nmov x0, #0\nmov x8, #93\nsvc #0\n"
    ".global mapping_abort_return\nmapping_abort_return:\nbrk #0\n");
static unsigned descriptors(void) {
    DIR *dir=opendir("/proc/self/fd"); assert(dir);
    unsigned count=0; while (readdir(dir)) ++count;
    assert(!closedir(dir)); return count;
}
static void send_fd_data(int socket, int fd, const void *data, size_t size) {
    struct iovec io={(void *)data,size};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    control.align=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_RIGHTS,.cmsg_len=CMSG_LEN(sizeof(int))};
    memcpy(CMSG_DATA(&control.align),&fd,sizeof(fd));
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    assert(sendmsg(socket,&m,MSG_NOSIGNAL)==(ssize_t)size);
}
static void send_fd(int socket, int fd) {
    char byte='L'; send_fd_data(socket,fd,&byte,1);
}
static int receive_fd_data(int socket, void *data, size_t size) {
    struct iovec io={data,size};
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct msghdr m={.msg_iov=&io,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    /* EVENT_WAIT: helper publishes its listener; the fixture alarm fails a stall. */
    assert(recvmsg(socket,&m,MSG_CMSG_CLOEXEC)==(ssize_t)size && !(m.msg_flags&(MSG_TRUNC|MSG_CTRUNC)));
    struct cmsghdr *h=CMSG_FIRSTHDR(&m);
    assert(h && h->cmsg_level==SOL_SOCKET && h->cmsg_type==SCM_RIGHTS && h->cmsg_len==CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd,CMSG_DATA(h),sizeof(fd)); return fd;
}
static int receive_fd(int socket) {
    char byte=0; int fd=receive_fd_data(socket,&byte,1); assert(byte=='L'); return fd;
}
static int mapping_child(void *data) {
    struct mapping *m=data;
    if (!m->thread) assert(!prctl(PR_SET_PDEATHSIG,SIGKILL) && getppid()==m->parent);
    else assert(getpid()==m->parent);
    if (m->protected) assert(!prctl(PR_SET_DUMPABLE,0));
    unsigned first=m->control<observation_socket ? (unsigned)m->control : (unsigned)observation_socket;
    unsigned last=m->control>observation_socket ? (unsigned)m->control : (unsigned)observation_socket;
    if (first) assert(!syscall(SYS_close_range,0,first-1,0));
    if (first+1<last) assert(!syscall(SYS_close_range,first+1,last-1,0));
    assert(!syscall(SYS_close_range,last+1,~0U,0));
    int maps=open("/proc/thread-self/maps",O_RDONLY|O_CLOEXEC); assert(maps>=0);
    struct mapping_source source={.pid=(pid_t)syscall(SYS_gettid),.device=m->device,.inode=m->inode,
        .killed=m->killed,.shared=m->shared};
    send_fd_data(observation_socket,maps,&source,sizeof(source));
    assert(!close(maps) && !close(observation_socket));
    uintptr_t abort_gate=(uintptr_t)mapping_abort_return;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)(abort_gate>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(unsigned)abort_gate,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_THREAD),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    int listener=syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_NEW_LISTENER,&program);
    assert(listener>=0); send_fd(m->control,listener); assert(!close(listener));
    if (m->full) {
        struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
        limit.rlim_cur=64; assert(!setrlimit(RLIMIT_NOFILE,&limit));
        while (fcntl(m->control,F_DUPFD_CLOEXEC,0)>=0) {}
        assert(errno==EMFILE);
    }
    if (m->killed) {
        struct sock_filter denied[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,m->killed==1 ? SYS_mmap : m->killed==4 ? SYS_exit : SYS_close,0,1),
            BPF_STMT(BPF_RET|BPF_K,m->killed==4 ? SECCOMP_RET_ERRNO|EPERM : SECCOMP_RET_KILL_THREAD),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog fatal={sizeof(denied)/sizeof(*denied),denied};
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&fatal));
    }
    int fd=syscall(SYS_getppid); assert(fd==0);
    __atomic_store_n(&m->phase,1,__ATOMIC_RELEASE);
    assert(mapping_mmap(m->address,m->size,PROT_READ|PROT_WRITE,
        (m->shared ? MAP_SHARED : MAP_PRIVATE)|MAP_FIXED,fd,0)==(long)m->address);
    __atomic_store_n(&m->phase,2,__ATOMIC_RELEASE);
    assert(!close(fd));
    __atomic_store_n(&m->phase,3,__ATOMIC_RELEASE);
    if (m->thread) { syscall(SYS_exit,0); __builtin_unreachable(); }
    return 0;
}
static void wait_thread(int *address) {
    /* EVENT_WAIT: kernel clear-child-tid; the fixture alarm bounds a missing
     * terminal event. The caller does not infer exit from elapsed time. */
    int tid;
    while ((tid=__atomic_load_n(address,__ATOMIC_ACQUIRE))) {
        long r=syscall(SYS_futex,address,FUTEX_WAIT,tid,NULL,NULL,0);
        assert(!r || (r<0 && (errno==EINTR || errno==EAGAIN)));
    }
}
static int dying_caller(void *data) {
    struct mapping *m=data;
    int flags=CLONE_VM|CLONE_THREAD|CLONE_SIGHAND|CLONE_PARENT_SETTID|CLONE_CHILD_CLEARTID;
    assert(clone(mapping_child,(char *)m->stack+65536,flags,m,&m->tid,NULL,&m->tid)>0);
    syscall(SYS_exit,0); __builtin_unreachable();
}
static void exercise(int full, int protected, int killed, int thread, int caller_exit, int shared) {
    unsigned before=descriptors();
    int dumpable=prctl(PR_GET_DUMPABLE); assert(dumpable>=0);
    struct rlimit limit; assert(!getrlimit(RLIMIT_NOFILE,&limit));
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    int source=memfd_create("mapping-task",MFD_CLOEXEC); assert(source>=0);
    assert(!ftruncate(source,(off_t)page) && pwrite(source,"M",1,0)==1);
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    void *address=mmap(NULL,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    void *caller_stack=caller_exit ? mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0) : NULL;
    assert(address!=MAP_FAILED && stack!=MAP_FAILED && caller_stack!=MAP_FAILED);
    struct mapping m={.control=sockets[1],.parent=getpid(),.full=full,.protected=protected,
        .killed=killed,.thread=thread,.shared=shared,.address=address,.size=page,.stack=stack};
    struct stat backing; assert(!fstat(source,&backing)); m.device=backing.st_dev; m.inode=backing.st_ino;
    unsigned caller_fds=descriptors();
    int fillers[64]; unsigned filled=0;
    if (full) {
        struct rlimit reduced=limit; reduced.rlim_cur=64;
        assert(!setrlimit(RLIMIT_NOFILE,&reduced));
        int fd;
        while ((fd=fcntl(STDERR_FILENO,F_DUPFD_CLOEXEC,0))>=0) {
            assert(filled<64); fillers[filled++]=fd;
        }
        assert(errno==EMFILE && filled);
    }
    int flags=CLONE_VM|(thread ? CLONE_THREAD|CLONE_SIGHAND|CLONE_PARENT_SETTID|CLONE_CHILD_CLEARTID : SIGCHLD);
    pid_t child;
    if (caller_exit) {
        assert(thread);
        assert(clone(dying_caller,(char *)caller_stack+65536,flags|CLONE_FILES,
            &m,&m.caller_tid,NULL,&m.caller_tid)>0);
        wait_thread(&m.caller_tid);
        child=__atomic_load_n(&m.tid,__ATOMIC_ACQUIRE); assert(child>0);
    } else child=clone(mapping_child,(char *)stack+65536,flags,&m,&m.tid,NULL,&m.tid);
    assert(child>0);
    /* Only the fixture runner receives a listener FD. The helper was already
     * cloned with the full table and must release its own inherited copies. */
    if (full) assert(!close(fillers[--filled]));
    int listener=receive_fd(sockets[0]);
    struct seccomp_notif request={0};
    /* EVENT_WAIT: exact helper admission, not a timing assumption about mmap. */
    assert(!ioctl(listener,SECCOMP_IOCTL_NOTIF_RECV,&request) && request.pid==(unsigned)child);
    struct seccomp_notif_addfd add={.id=request.id,.srcfd=source,.newfd=0,
        .flags=SECCOMP_ADDFD_FLAG_SEND|SECCOMP_ADDFD_FLAG_SETFD,.newfd_flags=O_CLOEXEC};
    assert(ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add)==0);
    if (!thread) {
        int status;
        /* EVENT_WAIT: kernel reaps the helper and its private descriptors. */
        assert(waitpid(child,&status,0)==child);
        assert(killed ? WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS : WIFEXITED(status) && !WEXITSTATUS(status));
    } else {
        wait_thread(&m.tid);
    }
    assert(__atomic_load_n(&m.phase,__ATOMIC_ACQUIRE)==(killed==1 || killed>=3 ? 1 : killed==2 ? 2 : 3));
    assert(!close(listener));
    while (filled) assert(!close(fillers[--filled]));
    assert(descriptors()==caller_fds);
    assert(!prctl(PR_SET_DUMPABLE,dumpable));
    assert(!setrlimit(RLIMIT_NOFILE,&limit));
    assert(!mprotect(address,page,PROT_READ|PROT_WRITE));
    assert(*(char *)address==(killed==1 ? 0 : 'M'));
    *(char *)address='C';
    char content=0;
    assert(pread(source,&content,1,0)==1 && content==(shared && killed!=1 ? 'C' : 'M'));
    assert(!munmap(address,page) && !munmap(stack,65536));
    if (caller_stack) assert(!munmap(caller_stack,65536));
    assert(!close(sockets[0]) && !close(sockets[1]) && !close(source));
    assert(descriptors()==before);
    printf("PASS private mapping helper thread=%d full=%d protected=%d killed=%d callerExit=%d shared=%d\n",
        thread,full,protected,killed,caller_exit,shared);
}
static int policy_child(void *data) {
    int *seen=data; __atomic_store_n(seen,1,__ATOMIC_RELEASE);
    syscall(SYS_exit,0); __builtin_unreachable();
}
static void clone_policy(void) {
    pid_t process=fork(); assert(process>=0);
    if (!process) {
        unsigned before=descriptors();
        void *stack=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(stack!=MAP_FAILED && !prctl(PR_SET_DUMPABLE,0));
        struct sock_filter code[]={
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
            BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_clone,0,3),
            BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[0])),
            BPF_JUMP(BPF_JMP|BPF_JSET|BPF_K,CLONE_FILES,1,0),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES),
            BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
        };
        struct sock_fprog program={sizeof(code)/sizeof(*code),code};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
        int tid=0, seen=0;
        unsigned flags=CLONE_VM|CLONE_THREAD|CLONE_SIGHAND|CLONE_PARENT_SETTID|CLONE_CHILD_CLEARTID;
        assert(clone(policy_child,(char *)stack+65536,flags,&seen,&tid,NULL,&tid)==-1 && errno==EACCES);
        assert(!tid && !seen && descriptors()==before);
        assert(clone(policy_child,(char *)stack+65536,flags|CLONE_FILES,&seen,&tid,NULL,&tid)>0);
        wait_thread(&tid); assert(__atomic_load_n(&seen,__ATOMIC_ACQUIRE)==1);
        void *bytes=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(bytes!=MAP_FAILED && !munmap(bytes,4096) && !munmap(stack,65536));
        assert(descriptors()==before); _exit(0);
    }
    int status;
    /* EVENT_WAIT: isolated application-filter control; outer alarm bounds it. */
    assert(waitpid(process,&status,0)==process && WIFEXITED(status) && !WEXITSTATUS(status));
    puts("PASS clone policy: ordinary threads and mmap allowed, private helper denied without FD changes");
}
static void cases(void) {
    for (int shared=0;shared<2;++shared)
      for (int thread=0;thread<2;++thread) for (int full=0;full<2;++full)
        for (int protected=0;protected<2;++protected)
            for (int killed=0;killed<5;++killed)
                for (int caller_exit=0;caller_exit<=thread;++caller_exit)
                    exercise(full,protected,killed,thread,caller_exit,shared);
    clone_policy();
    puts("PASS private mapping task kernel feasibility");
}
struct observed_mapping {
    pid_t pid; unsigned long input; int returned, advanced, maps;
    struct mapping_source source;
};
static unsigned query_unavailable;
static int query_error;
static int mapped_backing(const struct observed_mapping *m, int text) {
    if (!text) {
        struct procmap_query q={.size=sizeof(q),.query_addr=m->input};
        if (ioctl(m->maps,PROCMAP_QUERY,&q)) {
            if (!query_unavailable++) query_error=errno;
            else assert(errno==query_error);
            return -errno;
        }
        return q.inode==m->source.inode && q.dev_major==major(m->source.device)
            && q.dev_minor==minor(m->source.device) && q.vma_offset+m->input-q.vma_start==0
            && !!(q.vma_flags&PROCMAP_QUERY_VMA_SHARED)==m->source.shared;
    }
    struct md_mapping_source source={m->source.device,m->source.inode};
    int result=md_mapping_observe(m->maps,&source,m->input,(size_t)getpagesize(),0,m->source.shared);
    assert(result>=0); return result;
}
int main(void) {
    setbuf(stdout,NULL); alarm(30);
    int observations[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,observations));
    int gate[2]; assert(!pipe2(gate,O_CLOEXEC));
    pid_t runner=fork(); assert(runner>=0);
    if (!runner) {
        assert(!close(observations[0])); observation_socket=observations[1];
        char ready;
        assert(!close(gate[1]));
        /* EVENT_WAIT: attach acknowledgement; the outer alarm fails a stall. */
        assert(read(gate[0],&ready,1)==1 && !close(gate[0]));
        cases(); _exit(0);
    }
    assert(!close(observations[1]));
    assert(!close(gate[0]));
    assert(!ptrace(PTRACE_SEIZE,runner,0,PTRACE_O_TRACECLONE|PTRACE_O_TRACEFORK|PTRACE_O_TRACEEXIT
        |PTRACE_O_TRACESYSGOOD|PTRACE_O_EXITKILL));
    assert(write(gate[1],"x",1)==1 && !close(gate[1]));
    int runner_status=-1, before_mmap=0, returned_mmap=0, advanced_mmap=0, fatal_returned=0;
    int observed_commits=0,observed_denials=0;
    struct observed_mapping mappings[64]={0};
    for (;;) {
        int status;
        /* EVENT_WAIT: owned ptrace birth/terminal events, bounded by alarm. */
        pid_t pid=waitpid(-1,&status,__WALL);
        if (pid<0) { if (errno==EINTR) continue; assert(errno==ECHILD); break; }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            if (pid==runner) runner_status=status;
            for (unsigned i=0;i<64;++i) if (mappings[i].pid==pid) mappings[i]=(struct observed_mapping){0};
            continue;
        }
        assert(WIFSTOPPED(status));
        unsigned event=(unsigned)status>>16;
        if (!event && WSTOPSIG(status)==(SIGTRAP|0x80)) {
            struct ptrace_syscall_info info;
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0);
            struct observed_mapping *record=NULL;
            for (unsigned i=0;i<64;++i) if (mappings[i].pid==pid) record=&mappings[i];
            if (info.instruction_pointer==(unsigned long)mapping_mmap_return
                    && (info.op==PTRACE_SYSCALL_INFO_ENTRY ? info.entry.nr==SYS_mmap : record && !record->returned)) {
                if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                    assert(!record && info.entry.nr==SYS_mmap);
                    for (unsigned i=0;i<64;++i) if (!mappings[i].pid) { record=&mappings[i]; break; }
                    assert(record); *record=(struct observed_mapping){.pid=pid,.input=info.entry.args[0]};
                    record->maps=receive_fd_data(observations[0],&record->source,sizeof(record->source));
                    int query=mapped_backing(record,0);
                    assert(query==0 || query==-EACCES || query==-EPERM || query==-ENOTTY);
                    assert(record->source.pid==pid && !mapped_backing(record,1));
                } else {
                    assert(info.op==PTRACE_SYSCALL_INFO_EXIT && record && !record->returned);
                    assert((unsigned long)info.exit.rval==record->input);
                    record->returned=1; ++returned_mmap;
                    if (record->source.killed==3 || record->source.killed==4) {
                        /* Kill just after kernel mmap, before the helper can
                         * publish any userspace acknowledgement. The existing
                         * close filter kills only this thread, not its peers. */
                        struct user_pt_regs regs; struct iovec io={&regs,sizeof(regs)};
                        assert(!ptrace(PTRACE_GETREGSET,pid,NT_PRSTATUS,&io));
                        if (record->source.killed==3) {
                            regs.pc=(unsigned long)mapping_mmap_return-4;
                            regs.regs[8]=SYS_close; regs.regs[0]=0;
                        } else regs.pc=(unsigned long)mapping_abort_entry;
                        assert(!ptrace(PTRACE_SETREGSET,pid,NT_PRSTATUS,&io));
                    }
                }
            } else if (record && record->returned && !record->advanced && info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                record->advanced=1; ++advanced_mmap;
            }
            assert(!ptrace(PTRACE_SYSCALL,pid,0,0)); continue;
        }
        if (event==PTRACE_EVENT_EXIT) {
            for (unsigned i=0;i<64;++i) if (mappings[i].pid==pid) {
                struct observed_mapping *record=&mappings[i];
                int query=mapped_backing(record,0),committed=mapped_backing(record,1);
                assert(query==committed || query==-EACCES || query==-EPERM || query==-ENOTTY);
                if (committed) ++observed_commits; else ++observed_denials;
                assert(!close(record->maps)); record->maps=-1;
            }
            struct user_pt_regs regs;
            struct iovec io={&regs,sizeof(regs)};
            assert(!ptrace(PTRACE_GETREGSET,pid,NT_PRSTATUS,&io));
            if (regs.pc==(unsigned long)mapping_mmap_return && regs.regs[8]==SYS_mmap) {
                ++before_mmap;
                struct observed_mapping *record=NULL;
                for (unsigned i=0;i<64;++i) if (mappings[i].pid==pid) record=&mappings[i];
                /* This kernel emits a syscall-exit observation even though a
                 * fatal filter prevented mmap. Its x0 is still the requested
                 * address, indistinguishable from a successful fixed mapping. */
                assert(record && !record->advanced
                    && regs.regs[0]==record->input && regs.regs[8]==SYS_mmap);
                fatal_returned+=record->returned;
            }
        }
        int sig=event ? 0 : WSTOPSIG(status);
        assert(!ptrace(event==PTRACE_EVENT_EXIT ? PTRACE_CONT : PTRACE_SYSCALL,pid,0,sig));
    }
    assert(WIFEXITED(runner_status) && !WEXITSTATUS(runner_status)
        && before_mmap==24 && returned_mmap==advanced_mmap+fatal_returned && advanced_mmap==96);
    assert(observed_commits==96 && observed_denials==24 && !close(observations[0]));
    printf("PASS mapping observation: executed=%d fatal=%d misleadingExitStops=%d\n",
        advanced_mmap,before_mmap,fatal_returned);
    printf("PASS retained maps text observation: committed=%d denied=%d\n",observed_commits,observed_denials);
    printf("Binary mapping query: unavailable=%u errno=%d\n",query_unavailable,query_error);
    return 0;
}
