#define _GNU_SOURCE
#include "namespace_broker.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/stat.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

static volatile sig_atomic_t caught;
static atomic_int done;
static atomic_uint requested, completed;
static pthread_t reader;
extern char metadata_kernel_return[];
__attribute__((noinline)) static long metadata_kernel(long number,const unsigned long *args) {
    register long x8 __asm__("x8")=number;
    register long x0 __asm__("x0")=args[0], x1 __asm__("x1")=args[1], x2 __asm__("x2")=args[2];
    register long x3 __asm__("x3")=args[3], x4 __asm__("x4")=args[4], x5 __asm__("x5")=args[5];
    __asm__ volatile("svc 0\n.global metadata_kernel_return\nmetadata_kernel_return:"
        : "+r"(x0) : "r"(x8),"r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5) : "memory","cc");
    return x0;
}
static void signal_handler(int signal) { assert(signal==SIGUSR1); ++caught; }
static void *sender(void *unused) {
    (void)unused;
    unsigned previous=0;
    while (!atomic_load(&done)) {
        unsigned current=atomic_load(&requested);
        if (current==previous) { sched_yield(); continue; }
        /* Finite bursts exercise every operation without requiring progress
         * against an unbounded producer faster than signal delivery. Standard
         * signals coalesce, so the reader verifies delivery, not send counts. */
        for (unsigned i=0;i<32;++i) {
            assert(!pthread_kill(reader,SIGUSR1));
            sched_yield();
        }
        previous=current;
        atomic_store(&completed,current);
    }
    return NULL;
}
static void install_filter(void) {
    uintptr_t site=(uintptr_t)metadata_kernel_return;
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_faccessat2,0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[2])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,X_OK,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|ENOMSG),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_fgetxattr,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)+4),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)(site>>32),0,3),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,instruction_pointer)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,(uint32_t)site,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EACCES)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program));
}
static void reads(int file,int directory,int filtered,int protected,int restart) {
    struct stat st;
    struct statx sx;
    struct statfs space;
    char value[256];
    const char *name="metadata-notification",*attribute="user.metadata";
#define P(p) ((unsigned long)(p))
    struct { int nr; unsigned long args[6]; long result; } calls[]={
        {SYS_newfstatat,{directory,P(name),P(&st),0},0},
        {SYS_fstat,{file,P(&st)},0},
        {SYS_statx,{directory,P(name),0,STATX_BASIC_STATS,P(&sx)},0},
        {SYS_readlinkat,{directory,P("metadata-notification-link"),P(value),sizeof(value)},21},
        {SYS_faccessat,{directory,P(name),R_OK},0},
        {SYS_faccessat2,{directory,P(name),R_OK,AT_EACCESS},0},
        {SYS_statfs,{P(name),P(&space)},0},
        {SYS_fstatfs,{file,P(&space)},0},
        {SYS_getxattr,{P(name),P(attribute),P(value),sizeof(value)},5},
        {SYS_lgetxattr,{P(name),P(attribute),P(value),sizeof(value)},5},
        {SYS_fgetxattr,{file,P(attribute),P(value),sizeof(value)},5},
        {SYS_listxattr,{P(name),P(value),sizeof(value)},-1},
        {SYS_llistxattr,{P(name),P(value),sizeof(value)},-1},
        {SYS_flistxattr,{file,P(value),sizeof(value)},-1}
    };
#undef P
    for (int nr=0;nr<512;++nr) if (md_broker_metadata_call(nr)) {
        unsigned found=0;
        for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) found+=calls[i].nr==nr;
        assert(found==1);
    }
    struct sigaction action={.sa_handler=signal_handler,.sa_flags=restart ? SA_RESTART : 0};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR1,&action,NULL));
    if (filtered) {
        install_filter();
        assert(syscall(SYS_fgetxattr,file,attribute,value,sizeof(value))==-1 && errno==EACCES);
    }
    if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
    caught=0; atomic_store(&done,0);
    atomic_store(&requested,0); atomic_store(&completed,0); reader=pthread_self();
    pthread_t peer; assert(!pthread_create(&peer,NULL,sender,NULL));
    unsigned bursts=0;
    for (unsigned round=0;round<32;++round) for (unsigned i=0;i<sizeof(calls)/sizeof(*calls);++i) {
        memset(value,0xa5,sizeof(value));
        sig_atomic_t before=caught;
        atomic_store(&requested,++bursts);
        unsigned long *a=calls[i].args;
        long result=metadata_kernel(calls[i].nr,a);
        if (result<0) { errno=(int)-result; result=-1; }
        if (result<0 || (calls[i].result>=0 && result!=calls[i].result))
            fprintf(stderr,"metadata nr=%d result=%ld errno=%d filtered=%d protected=%d restart=%d\n",
                calls[i].nr,result,errno,filtered,protected,restart);
        assert(result>=0 && (calls[i].result<0 || result==calls[i].result));
        if (i<2) assert(st.st_size==7);
        else if (i==2) assert(sx.stx_size==7);
        else if (i==3) assert(!memcmp(value,name,strlen(name)));
        else if (i==6 || i==7) assert(space.f_type);
        else if (i>=8 && i<=10) assert(!memcmp(value,"value",5));
        else if (i>=11) assert(memmem(value,result,attribute,strlen(attribute)+1));
        if (filtered) assert(syscall(SYS_faccessat2,directory,name,X_OK,0)==-1 && errno==ENOMSG);
        /* EVENT_WAIT: this operation's producer and at least one handler must
         * complete before the next burst; the existing alarm bounds a failure. */
        while (atomic_load(&completed)!=bursts || caught==before) sched_yield();
    }
    atomic_store(&done,1);
    /* EVENT_WAIT: sender observes completion and joins; fixture alarm bounds failure. */
    assert(!pthread_join(peer,NULL) && caught);
    printf("PASS metadata under signals: 14 families, filtered=%d protected=%d restart=%d signals=%d bursts=%u\n",
        filtered,protected,restart,(int)caught,bursts);
}
int test_metadata_signal_guest(int mode) {
    assert(mode>=0 && mode<8);
    alarm(60); setbuf(stdout,NULL);
    int file=open("metadata-notification",O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);
    assert(file>=0 && write(file,"payload",7)==7);
    assert(!setxattr("metadata-notification","user.metadata","value",5,0));
    assert(!symlink("metadata-notification","metadata-notification-link"));
    int directory=open(".",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(directory>=0);
    {
        pid_t child=fork(); assert(child>=0);
        if (!child) { alarm(60); reads(file,directory,mode&1,!!(mode&2),!!(mode&4)); _exit(0); }
        int status; assert(waitpid(child,&status,0)==child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) fprintf(stderr,"metadata child mode=%d status=%#x\n",mode,status);
        assert(WIFEXITED(status) && !WEXITSTATUS(status));
    }
    assert(!close(file) && !close(directory));
    assert(!unlink("metadata-notification") && !unlink("metadata-notification-link"));
    return 0;
}
