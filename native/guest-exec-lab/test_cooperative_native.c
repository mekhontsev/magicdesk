#define _GNU_SOURCE
#include "../shroot/src/cooperative_exchange.h"
#include "../shroot/src/fs_syscall.h"
#include <assert.h>
#include <asm/ptrace.h>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

static void string_operands(void) {
    const struct { int number; unsigned strings; } cases[]={
        {SYS_openat,2},{SYS_openat2,2},{SYS_unlinkat,2},{SYS_mkdirat,2},
        {SYS_newfstatat,2},{SYS_statx,2},{SYS_readlinkat,2},{SYS_faccessat,2},
        {SYS_faccessat2,2},{SYS_fchmodat,2},{SYS_fchmodat2,2},{SYS_fchownat,2},{SYS_utimensat,2},
        {SYS_renameat,34},{SYS_renameat2,34},{SYS_linkat,34},{SYS_symlinkat,11},
        {SYS_statfs,1},{SYS_fsopen,1},{SYS_listxattr,1},{SYS_llistxattr,1},
        {SYS_getxattr,17},{SYS_lgetxattr,17},{SYS_setxattr,17},{SYS_lsetxattr,17},
        {SYS_removexattr,17},{SYS_lremovexattr,17},
        {SYS_fgetxattr,16},{SYS_fsetxattr,16},{SYS_fremovexattr,16},
        {SYS_flistxattr,0},{SYS_fstat,0},{SYS_fstatfs,0},{SYS_fchmod,0},
        {SYS_fchown,0},{SYS_getdents64,0},{SYS_getuid,0},{SYS_close,0},{-1,0}
    };
    for (unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);++i) {
        assert(md_fs_syscall_strings(cases[i].number)==cases[i].strings);
        /* Distinct, non-dereferenced values in every slot, then a valid set
         * of scalar flags. Both zero and nonzero string operands are legal. */
        uint64_t args[]={11,22,33,44,55,66};
        int nr=cases[i].number;
        if (nr==SYS_newfstatat || nr==SYS_utimensat || nr==SYS_fchmodat2) args[3]=0;
        if (nr==SYS_statx) { args[2]=0; args[3]=STATX_BASIC_STATS; }
        if (nr==SYS_fchownat) args[4]=0;
        if (nr==SYS_faccessat2) args[3]=0;
        if (nr==SYS_fsopen) args[1]=0;
        if (nr==SYS_setxattr || nr==SYS_lsetxattr || nr==SYS_fsetxattr) args[4]=0;
        struct md_fs_syscall decoded;
        struct md_fs_request request;
        int result=md_fs_syscall_decode(&decoded,&request,nr,args,4096);
        if (nr!=SYS_openat2 && nr!=SYS_getuid && nr!=SYS_close && nr!=-1) assert(result>0);
        if (result>0) {
            unsigned selectors=cases[i].strings;
            for (unsigned n=0;n<2;++n,selectors>>=3)
                assert(decoded.path[n]==((selectors&7) ? args[(selectors&7)-1] : 0));
        }
    }
    puts("PASS string operands: caller capture and executor decoding share exact argument selectors");
}

static void open_admission(void) {
    char directory[]="native-directory-XXXXXX";
    assert(mkdtemp(directory));
    int pin=open(directory,O_PATH|O_CLOEXEC); assert(pin>=0);
    assert(!chmod(directory,0400));
    const unsigned modes[]={O_RDONLY,O_PATH};
    for (unsigned removed=0;removed<2;++removed) {
        if (removed) assert(!rmdir(directory));
        for (unsigned m=0;m<2;++m) for (unsigned d=0;d<2;++d) {
            unsigned flags=modes[m]|O_NOFOLLOW|(d ? O_DIRECTORY : 0);
            char path[64];
            unsigned planned=md_open_plan_path(path,0,(unsigned)pin,flags,S_IFDIR);
            for (unsigned route=0;route<2;++route) {
                int fd=route ? md_open_pinned(pin,(int)flags) : open(path,(int)planned);
                assert(fd>=0 && (fcntl(fd,F_GETFL)&~O_LARGEFILE)==(int)flags);
                struct stat actual,original;
                assert(!fstat(fd,&actual) && !fstat(pin,&original));
                assert(actual.st_dev==original.st_dev && actual.st_ino==original.st_ino);
                assert(!close(fd));
            }
        }
    }
    assert(!close(pin));
    for (unsigned i=0;i<8;++i) {
        unsigned flags=O_PATH | (i&1 ? O_DIRECTORY : 0) | (i&2 ? O_CLOEXEC : 0)
            | (i&4 ? O_NOFOLLOW : 0);
        uint64_t args[]={AT_FDCWD,1,flags,0,0,0};
        for (unsigned birth=0;birth<2;++birth)
            assert(md_open_native_plan(flags,birth));
        assert(md_entry_native_open(SYS_openat,args));
        assert(md_open_native_kind(flags,S_IFDIR));
        assert(md_open_native_kind(flags,S_IFREG)==!(flags&O_NOFOLLOW));
        assert(!md_open_native_kind(flags,S_IFLNK));
        char path[64];
        unsigned opened=md_open_plan_path(path,123,456,flags,S_IFDIR);
        assert(opened==flags && !strcmp(path,flags&O_NOFOLLOW ? "/proc/123/fd/456/" : "/proc/123/fd/456"));
    }
    assert(md_open_native_plan(O_RDONLY|O_NOFOLLOW,0));
    assert(md_open_native_plan(O_CREAT|O_EXCL|O_RDWR,1));
    puts("PASS native open admission: directory NOFOLLOW plans, exact non-directory custody and source flags");
}

static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); assert(d);
    unsigned count=0; struct dirent *entry;
    while ((entry=readdir(d))) if (entry->d_name[0]!='.') ++count;
    assert(!closedir(d)); return count;
}
static void stopped(pid_t child,int signal) {
    int status; assert(waitpid(child,&status,0)==child);
    assert(WIFSTOPPED(status) && WSTOPSIG(status)==signal);
}
/* Break at one instruction per child. Single-stepping an ldaxr/stlxr pair
 * would disturb the exclusive monitor instead of exercising the real gate. */
static void boundary(uintptr_t pc,int kind) {
    struct md_entry_native *n=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(n!=MAP_FAILED);
    int fd=open("/proc/self/exe",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    *n=(struct md_entry_native){.state=MD_NATIVE_READY,.number=SYS_openat,
        .args={AT_FDCWD,(uintptr_t)"/proc/self/exe",O_RDONLY|O_CLOEXEC}};
    if (kind==1) { n->number=SYS_close; n->args[0]=fd; }
    if (kind==2) { n->number=SYS_fcntl; n->args[0]=fd; n->args[1]=F_SETFD; n->args[2]=0; }
    if (kind==3) n->args[1]=(uintptr_t)"/proc/self/not-a-file";
    if (kind==4) { n->number=SYS_dup; n->args[0]=fd; }
    if (kind==5) { n->number=SYS_dup3; n->args[0]=fd; n->args[1]=900; n->args[2]=O_CLOEXEC; }
    if (kind==6) { n->number=SYS_fcntl; n->args[0]=fd; n->args[1]=F_DUPFD_CLOEXEC; n->args[2]=900; }
    int queue=-1,event=-1,pair[2]={-1,-1};
    char byte='s';
    struct epoll_event watch={.events=EPOLLIN|EPOLLONESHOT,.data.u64=42},output={0};
    if (kind==7 || kind==8) {
        queue=epoll_create1(EPOLL_CLOEXEC); event=eventfd(1,EFD_CLOEXEC|EFD_NONBLOCK);
        assert(queue>=0 && event>=0);
        memset(n->args,0,sizeof(n->args));
        n->args[0]=queue;
        if (kind==7) {
            n->number=SYS_epoll_ctl; n->args[1]=EPOLL_CTL_ADD;
            n->args[2]=event; n->args[3]=(uintptr_t)&watch;
        } else {
            assert(!epoll_ctl(queue,EPOLL_CTL_ADD,event,&watch));
            n->number=SYS_epoll_pwait; n->args[1]=(uintptr_t)&output; n->args[2]=1;
        }
    }
    if (kind>=9) {
        assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,pair));
        memset(n->args,0,sizeof(n->args));
        n->number=kind==9 ? SYS_sendto : SYS_recvfrom;
        n->args[0]=pair[kind==10]; n->args[1]=(uintptr_t)&byte;
        n->args[2]=1; n->args[3]=MSG_DONTWAIT|MSG_NOSIGNAL;
        if (kind==10) { assert(send(pair[0],&byte,1,0)==1); byte=0; }
    }
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        alarm(5);
        unsigned before=descriptors();
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        assert(!raise(SIGSTOP));
        md_cooperative_native(n);
        unsigned state=atomic_load(&n->state);
        assert(state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED);
        if (state==MD_NATIVE_CANCELLED) {
            assert(n->result==-EINTR && descriptors()==before);
            assert(fcntl(fd,F_GETFD)==FD_CLOEXEC);
        } else if (kind>=7) {
            assert(descriptors()==before && n->result==(kind==7 ? 0 : 1));
            if (kind==8) assert(output.data.u64==42 && output.events==EPOLLIN);
            if (kind==10) assert(byte=='s');
        } else if (!kind) {
            assert(n->result>=0 && descriptors()==before+1);
            assert(fcntl(n->result,F_GETFD)==FD_CLOEXEC);
            assert(!close(n->result));
        } else if (kind==1) {
            assert(!n->result && descriptors()==before-1);
            assert(fcntl(fd,F_GETFD)==-1 && errno==EBADF);
        } else if (kind==2) {
            assert(!n->result && descriptors()==before && !fcntl(fd,F_GETFD));
        } else if (kind==3) assert(n->result==-ENOENT && descriptors()==before);
        else {
            assert(n->result>=0 && descriptors()==before+1);
            assert(fcntl(n->result,F_GETFD)==(kind==4 ? 0 : FD_CLOEXEC));
            if (kind!=4) assert(n->result>=900);
            assert(!close(n->result));
        }
        if (kind==7 || kind==8) {
            /* ONESHOT distinguishes a cancelled wait from a consumed event;
             * replaying the action would lose either its result or its readiness. */
            int ready=state==MD_NATIVE_CANCELLED ? kind==8 : kind==7;
            assert(epoll_wait(queue,&output,1,0)==ready);
            if (ready) assert(output.data.u64==42 && output.events==EPOLLIN);
            assert(!close(queue) && !close(event));
        }
        if (kind>=9) {
            int pending=state==MD_NATIVE_CANCELLED ? kind==10 : kind==9;
            ssize_t got=recv(pair[1],&byte,1,MSG_DONTWAIT);
            if (pending) assert(got==1 && byte=='s');
            else assert(got==-1 && errno==EAGAIN);
            assert(recv(pair[1],&byte,1,MSG_DONTWAIT)==-1 && errno==EAGAIN);
            assert(!close(pair[0]) && !close(pair[1]));
        }
        _exit(0);
    }
    stopped(child,SIGSTOP);
    uintptr_t word=pc&~(uintptr_t)7;
    errno=0; unsigned long saved=ptrace(PTRACE_PEEKTEXT,child,(void *)word,NULL); assert(!errno);
    unsigned shift=(pc-word)*8;
    unsigned long patched=(saved&~(UINT64_C(0xffffffff)<<shift))|(UINT64_C(0xd4200000)<<shift);
    assert(!ptrace(PTRACE_POKETEXT,child,(void *)word,(void *)patched));
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL)); stopped(child,SIGTRAP);
    struct user_pt_regs regs; struct iovec io={&regs,sizeof(regs)};
    assert(!ptrace(PTRACE_GETREGSET,child,(void *)NT_PRSTATUS,&io) && regs.pc==pc);
    unsigned state=atomic_load(&n->state); long result=n->result;
    int completion=md_native_stopped(state,regs.pc,regs.regs[0],(uintptr_t)md_cooperative_native,
        (uintptr_t)md_cooperative_native_call,(uintptr_t)md_cooperative_native_end,&result);
    assert(completion==MD_NATIVE_DONE || completion==MD_NATIVE_CANCELLED);
    if (state==MD_NATIVE_RUNNING) {
        regs.regs[0]=result; regs.pc=(uintptr_t)md_cooperative_native_end;
        assert(!ptrace(PTRACE_SETREGSET,child,(void *)NT_PRSTATUS,&io));
    }
    n->result=result; atomic_store(&n->state,completion);
    assert(!ptrace(PTRACE_POKETEXT,child,(void *)word,(void *)saved));
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    int status; assert(waitpid(child,&status,0)==child);
    if (!WIFEXITED(status) || WEXITSTATUS(status))
        fprintf(stderr,"native boundary kind=%d offset=%zu state=%u status=%#x\n",kind,pc-(uintptr_t)md_cooperative_native,state,status);
    assert(WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(fd) && !munmap(n,4096));
    if (kind==7 || kind==8) assert(!close(queue) && !close(event));
    if (kind>=9) assert(!close(pair[0]) && !close(pair[1]));
}
static struct md_entry_channel channel;
static void descriptor_lease(void) {
    struct md_entry_channel c={0};
    assert(md_entry_view_descriptor(&c)==-1);
    md_fd_view_publish(&c.descriptor,43,FD_CLOEXEC,S_IFREG);
    assert(md_entry_view_descriptor(&c)==42 && c.descriptor.flags==FD_CLOEXEC);
    md_fd_view_publish(&c.descriptor,MD_FD_VIEW_RESULT,0,S_IFREG);
    c.native.number=SYS_openat; c.native.result=42;
    for (unsigned state=MD_NATIVE_EMPTY;state<=MD_NATIVE_CANCELLED;++state) {
        atomic_store(&c.native.state,state);
        assert(md_entry_view_descriptor(&c)==(state==MD_NATIVE_DONE ? 42 : -1));
    }
    atomic_store(&c.native.state,MD_NATIVE_DONE);
    c.native.result=-ENOENT; assert(md_entry_view_descriptor(&c)==-1);
    c.native.result=INT64_MAX; assert(md_entry_view_descriptor(&c)==-1);
    c.native.result=42; c.native.number=SYS_close; assert(md_entry_view_descriptor(&c)==-1);
    c.native.number=SYS_openat; md_fd_view_revoke(&c.descriptor);
    assert(md_entry_view_descriptor(&c)==-1);
    uint64_t args[6]={42,FIONREAD,0};
    assert(md_fd_view_query(SYS_ioctl,args,S_IFREG));
    assert(!md_fd_view_query(SYS_ioctl,args,S_IFCHR));
    assert(!md_fd_view_query(SYS_ioctl,args,S_IFIFO));
    args[1]=F_GETFL;
    assert(md_fd_view_query(SYS_fcntl,args,S_IFIFO));
    puts("PASS native descriptor lease: exact FD or successful open, cancellation, failure and revocation");
}
static pthread_barrier_t phase;
static atomic_int wake_requested;
static void barrier(void) {
    /* EVENT_WAIT: fixture peer reaches its protocol phase; alarm bounds failure. */
    int r=pthread_barrier_wait(&phase); assert(!r || r==PTHREAD_BARRIER_SERIAL_THREAD);
}
static void *retire(void *unused) {
    (void)unused;
    for (unsigned i=0;i<1000;++i) {
        barrier(); assert(!md_entry_native_retire(&channel,1,MD_ENTRY_COMPLETED,42)); barrier();
    }
    return NULL;
}
static void retirement(void) {
    atomic_store(&channel.state,MD_ENTRY_NATIVE);
    assert(md_entry_native_retire(&channel,1,MD_ENTRY_COMPLETED,42)==1);
    assert(atomic_load(&channel.state)==MD_ENTRY_RESPONSE && channel.result==42 && channel.receipt==1);
    atomic_store(&channel.state,MD_ENTRY_RETIRING);
    assert(!md_entry_native_retire(&channel,1,MD_ENTRY_COMPLETED,42));
    assert(atomic_load(&channel.state)==MD_ENTRY_IDLE);
    pthread_t peer; assert(!pthread_barrier_init(&phase,NULL,2));
    assert(!pthread_create(&peer,NULL,retire,NULL));
    for (unsigned i=0;i<1000;++i) {
        channel.sequence=1; atomic_store(&channel.state,MD_ENTRY_RETIRING); barrier();
        channel.sequence=2; channel.call.number=SYS_close; channel.call.args[0]=i;
        unsigned state=i%2 ? MD_ENTRY_REQUEST : MD_ENTRY_FD_REQUEST;
        atomic_store_explicit(&channel.state,state,memory_order_seq_cst); barrier();
        assert(atomic_load(&channel.state)==state && channel.sequence==2);
        assert(channel.call.number==SYS_close && channel.call.args[0]==i);
    }
    assert(!pthread_join(peer,NULL) && !pthread_barrier_destroy(&phase));
    puts("PASS native retirement: synchronous receipt, idle owner and 1000 next-request publication races");
}
static void *publish(void *unused) {
    (void)unused;
    for (unsigned i=0;i<2000;++i) {
        barrier();
        if (i&1) atomic_store(&wake_requested,md_entry_publish_request(&channel,MD_ENTRY_REQUEST));
        else {
            atomic_store_explicit(&channel.native.state,MD_NATIVE_DONE,memory_order_release);
            atomic_thread_fence(memory_order_seq_cst);
        }
        if (!(i&1)) atomic_store(&wake_requested,!atomic_load_explicit(&channel.awake,memory_order_seq_cst));
        barrier();
    }
    return NULL;
}
static void park_handoff(void) {
    assert(!pthread_barrier_init(&phase,NULL,2));
    pthread_t peer; assert(!pthread_create(&peer,NULL,publish,NULL));
    for (unsigned i=0;i<2000;++i) {
        atomic_store(&channel.state,MD_ENTRY_IDLE); atomic_store(&channel.native.state,MD_NATIVE_EMPTY);
        enum md_entry_owner owner=(i%4)==3 ? MD_ENTRY_SUPERVISOR_OWNER : MD_ENTRY_WORKER_OWNER;
        channel.call.reserved=owner==MD_ENTRY_SUPERVISOR_OWNER ? MD_ENTRY_BUFFER_CONTROL : MD_ENTRY_SYSCALL;
        atomic_store(md_entry_owner_awake(&channel,owner),1); atomic_store(&wake_requested,0);
        barrier(); unsigned pending=md_entry_prepare_wait(&channel,owner); barrier();
        assert((pending&(i&1 ? MD_ENTRY_PENDING_CALL : MD_ENTRY_PENDING_NATIVE)) || atomic_load(&wake_requested));
    }
    assert(!pthread_join(peer,NULL) && !pthread_barrier_destroy(&phase));
    puts("PASS exchange park: 2000 worker/supervisor request/completion handoffs cannot lose both readiness and wake");
}
int main(void) {
    alarm(30); unsigned count=0;
    descriptor_lease();
    string_operands();
    open_admission();
    park_handoff();
    for (int kind=0;kind<11;++kind)
        for (uintptr_t pc=(uintptr_t)md_cooperative_native;pc<=(uintptr_t)md_cooperative_native_end;pc+=4) {
            boundary(pc,kind); ++count;
        }
    long result=0;
    for (long restart=-516;restart<=-512;++restart) {
        assert(md_native_stopped(MD_NATIVE_RUNNING,108,restart,100,104,120,&result)
            ==(restart==-515 ? MD_NATIVE_DONE : MD_NATIVE_CANCELLED));
        assert(result==(restart==-515 ? restart : -EINTR));
    }
    assert(md_native_stopped(MD_NATIVE_RUNNING,108,-EINTR,100,104,120,&result)==MD_NATIVE_DONE);
    assert(result==-EINTR);
    assert(md_native_stopped(MD_NATIVE_RUNNING,99,0,100,104,120,&result)==-EPROTO);
    assert(md_native_stopped(MD_NATIVE_RUNNING,121,0,100,104,120,&result)==-EPROTO);
    retirement();
    printf("PASS native journal: %u exact instruction stops, real FD effects, cancellation and restart classification\n",count);
    return 0;
}
