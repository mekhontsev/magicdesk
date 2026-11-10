#define _GNU_SOURCE
#include "seccomp_policy.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

/* One original call site for both paths, with the same complete arguments.
 * This is a workload-scoped prototype, not a process-wide sandbox. */
extern long entry_call(long,const uint64_t[6]);
extern char entry_svc[];
__asm__(".text\n.balign 4\n.global entry_call\nentry_call:\n"
    "mov x8,x0\nmov x9,x1\nldp x0,x1,[x9]\nldp x2,x3,[x9,#16]\n"
    "ldp x4,x5,[x9,#32]\n.global entry_svc\nentry_svc:\nsvc #0\nret\n");
static struct sock_fprog program;
static const struct md_seccomp_view *view;
static int userspace;
static uint64_t evaluated,denied;
static volatile sig_atomic_t trapped;
static uint32_t trap_decision;
static long checked(long number,const uint64_t args[6]) {
    if (userspace) {
        struct seccomp_data call={.nr=number,.arch=AUDIT_ARCH_AARCH64,
            .instruction_pointer=(uintptr_t)entry_svc+4};
        memcpy(call.args,args,sizeof(call.args));
        uint32_t decision;
        assert(!md_seccomp_view_eval(view,&call,&decision));
        ++evaluated;
        switch (decision&SECCOMP_RET_ACTION_FULL) {
        case SECCOMP_RET_ALLOW: break;
        case SECCOMP_RET_ERRNO: ++denied; return md_seccomp_policy_error(decision);
        default: fprintf(stderr,"unsupported prototype action %#x on %ld\n",decision,number); abort();
        }
    }
    return entry_call(number,args);
}
static uint32_t decision(long number,const uint64_t args[6]) {
    struct seccomp_data call={.nr=number,.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=(uintptr_t)entry_svc+4};
    memcpy(call.args,args,sizeof(call.args));
    uint32_t result;
    assert(!md_seccomp_program_eval(&program,&call,&result)); return result;
}
static void install(void) {
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_SPEC_ALLOW,&program));
}
static void trap(int number,siginfo_t *info,void *opaque) {
    ucontext_t *context=opaque;
    if (number!=SIGSYS || info->si_code!=SYS_SECCOMP || info->si_syscall!=SYS_process_vm_readv
            || info->si_arch!=AUDIT_ARCH_AARCH64 || info->si_errno!=(int)(trap_decision&SECCOMP_RET_DATA)
            || context->uc_mcontext.pc!=(uintptr_t)entry_svc+4) _exit(91);
    ++trapped; context->uc_mcontext.regs[0]=(uint64_t)-7777L;
}
static void oracle(const uint64_t rename_args[6]) {
    uint64_t args[6]={0};
    trap_decision=decision(SYS_process_vm_readv,args);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sigaction action={.sa_sigaction=trap,.sa_flags=SA_SIGINFO};
        assert(!sigemptyset(&action.sa_mask) && !sigaction(SIGSYS,&action,NULL));
        install();
        uint32_t expected=decision(SYS_renameat,rename_args);
        assert((expected&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ERRNO);
        assert(entry_call(SYS_renameat,rename_args)==md_seccomp_policy_error(expected));
        if ((trap_decision&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_TRAP) {
            assert(entry_call(SYS_process_vm_readv,args)==-7777 && trapped==1);
        }
        _exit(0);
    }
    int status;
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static uint64_t now(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC,&t));
    return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;
}
int main(int argc,char **argv) {
    assert(argc==6);
    FILE *input=fopen(argv[1],"rb"); assert(input);
    static struct sock_filter code[BPF_MAXINSNS];
    size_t length=fread(code,1,sizeof(code),input);
    int trailing=fgetc(input);
    assert(length && length%sizeof(*code)==0 && !ferror(input) && trailing==EOF);
    assert(!fclose(input));
    program=(struct sock_fprog){.len=length/sizeof(*code),.filter=code};
    assert(!strcmp(argv[3],"kernel") || !strcmp(argv[3],"userspace") || !strcmp(argv[3],"none"));
    userspace=!strcmp(argv[3],"userspace");
    if (userspace) {
        struct md_seccomp_policy *policy=NULL;
        size_t size;
        assert(!md_seccomp_policy_capture(NULL,&program,&policy));
        assert(!md_seccomp_policy_view_size(policy,0,&size));
        void *mapping=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(mapping!=MAP_FAILED);
        assert(!md_seccomp_policy_view_write(policy,0,mapping,size,(uintptr_t)mapping,0));
        assert(!mprotect(mapping,size,PROT_READ));
        view=mapping;
        md_seccomp_policy_release(policy);
    }
    char path[4096],renamed[4096];
    assert(snprintf(path,sizeof(path),"%s/input",argv[2])<(int)sizeof(path));
    assert(snprintf(renamed,sizeof(renamed),"%s/renamed",argv[2])<(int)sizeof(renamed));
    assert(!mkdir(argv[2],0700) || errno==EEXIST);
    int fd=open(path,O_CREAT|O_RDWR|O_TRUNC|O_CLOEXEC,0600); assert(fd>=0);
    assert(write(fd,"data",4)==4);
    struct stat stat;
    uint64_t stat_args[6]={(uint64_t)fd,(uintptr_t)&stat};
    uint64_t path_args[6]={(uint64_t)(long)AT_FDCWD,(uintptr_t)path,(uintptr_t)&stat,0};
    uint64_t open_args[6]={(uint64_t)(long)AT_FDCWD,(uintptr_t)path,O_RDONLY|O_CLOEXEC};
    uint64_t rename_args[6]={(uint64_t)(long)AT_FDCWD,(uintptr_t)path,(uint64_t)(long)AT_FDCWD,(uintptr_t)renamed};
    oracle(rename_args);
    long number=!strcmp(argv[4],"fstat") ? SYS_fstat
        : !strcmp(argv[4],"stat") ? SYS_newfstatat : !strcmp(argv[4],"opens") ? SYS_openat : -1;
    assert(number>=0);
    const uint64_t *args=number==SYS_fstat ? stat_args : number==SYS_newfstatat ? path_args : open_args;
    assert(decision(number,args)==SECCOMP_RET_ALLOW);
    uint64_t close_args[6]={(uint64_t)fd};
    assert(decision(SYS_close,close_args)==SECCOMP_RET_ALLOW);
    char *end; unsigned long count=strtoul(argv[5],&end,10);
    assert(!*end && count && count<=10000000);
    if (!strcmp(argv[3],"kernel")) install();
    if (strcmp(argv[3],"none")) {
        uint32_t expected=decision(SYS_renameat,rename_args);
        assert(checked(SYS_renameat,rename_args)==md_seccomp_policy_error(expected));
    }
    uint64_t before=now(),sum=0;
    for (unsigned long i=0;i<count;++i) {
        long result=checked(number,args);
        if (number==SYS_openat) {
            assert(result>=0); close_args[0]=(uint64_t)result;
            assert(!checked(SYS_close,close_args)); sum++;
        } else { assert(!result && stat.st_size==4 && S_ISREG(stat.st_mode)); sum+=stat.st_size; }
    }
    uint64_t elapsed=now()-before;
    assert(!entry_call(SYS_fstat,stat_args) && stat.st_size==4);
    assert(!entry_call(SYS_close,(uint64_t[6]){(uint64_t)fd}));
    printf("{\"workload\":\"%s\",\"policy\":\"%s\",\"instructions\":%u,\"iterations\":%lu,"
        "\"elapsedNs\":%llu,\"evaluated\":%llu,\"denied\":%llu,\"checksum\":%llu}\n",
        argv[4],argv[3],program.len,count,(unsigned long long)elapsed,(unsigned long long)evaluated,
        (unsigned long long)denied,(unsigned long long)sum);
    return 0;
}
