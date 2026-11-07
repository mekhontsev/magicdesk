#define _GNU_SOURCE
#include "kernel_profile.h"
#include "linux_abi.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

struct condition { unsigned argument; uint32_t mask, value; };
enum { PROFILE_5_15=1, PROFILE_CAPTURE=2 };
struct restriction {
    const char *name;
    unsigned number, error, count;
    struct condition conditions[2];
};
#define EQ(arg,value) {arg,UINT32_MAX,value}
#define BITS(arg,mask,value) {arg,mask,value}
/* Numeric UAPI values deliberately do not depend on the build host's headers.
 * ioctl matching uses type/number where the ABI's payload size may vary. */
static const struct restriction restrictions[]={
    {"dupfd-query",SYS_fcntl,EINVAL,1,{EQ(1,1027)}},
    {"pidfd-thread",SYS_pidfd_open,EINVAL,1,{BITS(1,MD_PIDFD_THREAD,MD_PIDFD_THREAD)}},
    {"notification-sync-wake",SYS_ioctl,EINVAL,1,{EQ(1,_IOW('!',4,uint64_t))}},
    {"dmabuf-export-sync-file",SYS_ioctl,ENOTTY,1,{BITS(1,0xffff,('b'<<8)|2)}},
    {"dmabuf-import-sync-file",SYS_ioctl,ENOTTY,1,{BITS(1,0xffff,('b'<<8)|3)}},
    {"pidfd-ioctl",SYS_ioctl,ENOTTY,1,{BITS(1,0xff00,0xff00)}},
    {"procmap-query",SYS_ioctl,ENOTTY,1,{BITS(1,0xffff,('f'<<8)|17)}},
    {"memfd-exec",SYS_memfd_create,EINVAL,1,{BITS(1,0x10,0x10)}},
    {"memfd-noexec-seal",SYS_memfd_create,EINVAL,1,{BITS(1,0x08,0x08)}},
    {"execve-check",SYS_execveat,EINVAL,1,{BITS(4,0x10000,0x10000)}},
    {"peer-pidfd",SYS_getsockopt,ENOPROTOOPT,2,{EQ(1,SOL_SOCKET),EQ(2,77)}},
    {"pass-pidfd-get",SYS_getsockopt,ENOPROTOOPT,2,{EQ(1,SOL_SOCKET),EQ(2,76)}},
    {"pass-pidfd-set",SYS_setsockopt,ENOPROTOOPT,2,{EQ(1,SOL_SOCKET),EQ(2,76)}},
    {"notification-killable-wait",SYS_seccomp,EINVAL,2,
        {EQ(0,SECCOMP_SET_MODE_FILTER),BITS(1,0x20,0x20)}},
    {"notification-restart",SYS_seccomp,EINVAL,2,
        {EQ(0,SECCOMP_SET_MODE_FILTER),BITS(1,0x40,0x40)}},
};
#undef EQ
#undef BITS

static unsigned result_errno(unsigned error,unsigned index,int probe) {
    /* Probe-only errno values cannot be mistaken for an already unsupported
     * host operation. The selector program itself is identical. */
    return probe ? 3000+index : error;
}
static int verify(int probe) {
    for (unsigned i=0;probe && i<sizeof(restrictions)/sizeof(*restrictions);++i) {
        const struct restriction *r=&restrictions[i];
        unsigned long args[6]={0};
        for (unsigned j=0;j<r->count;++j) args[r->conditions[j].argument]=r->conditions[j].value;
        errno=0;
        long result=syscall(SYS_getppid,args[0],args[1],args[2],args[3],args[4],r->number);
        if (result!=-1 || errno!=(int)result_errno(r->error,i+1,probe)) {
            fprintf(stderr,"kernel profile: %s returned %ld errno=%d\n",r->name,result,errno);
            return -EPROTO;
        }
    }
    /* ARM64's last syscall in 5.15 is process_mrelease (448). New syscall
     * numbers are rejected only in the native owners, never in the guest. */
    if (probe && (syscall(SYS_getppid,0,0,0,0,0,449)!=-1 || errno!=(int)result_errno(ENOSYS,0,1))) return -EPROTO;
    if (probe && (syscall(SYS_getppid,0,0,0,0,0,452)!=-1 || errno!=(int)result_errno(ENOSYS,0,1))) return -EPROTO;
    int fd=(int)syscall(SYS_pidfd_open,getpid(),0);
    if (fd<0) return -errno;
    close(fd);
    fd=memfd_create("kernel-profile",MFD_CLOEXEC);
    if (fd<0) return -errno;
    close(fd);
    int pair[2];
    if (socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair)) return -errno;
    int type=0, bytes=-1; socklen_t size=sizeof(type);
    int error=getsockopt(pair[0],SOL_SOCKET,SO_TYPE,&type,&size) || type!=SOCK_STREAM
        || ioctl(pair[0],FIONREAD,&bytes) || bytes ? -EPROTO : 0;
    close(pair[0]); close(pair[1]);
    return error;
}

static int install(int probe) {
    struct sock_filter rules[256]; unsigned n=0;
    /* Exercise identical selectors through getppid's unused arguments. Native
     * probes of newer syscalls may be killed by inherited Android seccomp,
     * whose denial outranks this test profile's ERRNO. The sixth argument is
     * the probe syscall number; no restriction below uses that argument. */
    unsigned number_offset=probe ? offsetof(struct seccomp_data,args)+5*sizeof(uint64_t)
        : offsetof(struct seccomp_data,nr);
#define ADD(code) rules[n++]=(struct sock_filter)code
    ADD(BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,arch)));
    ADD(BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,AUDIT_ARCH_AARCH64,1,0));
    ADD(BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_KILL_PROCESS));
    if (probe) {
        ADD(BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)));
        ADD(BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_getppid,1,0));
        ADD(BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW));
    }
    ADD(BPF_STMT(BPF_LD|BPF_W|BPF_ABS,number_offset));
    ADD(BPF_JUMP(BPF_JMP|BPF_JGE|BPF_K,449,0,1));
    ADD(BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|result_errno(ENOSYS,0,probe)));
    for (unsigned i=0;i<sizeof(restrictions)/sizeof(*restrictions);++i) {
        const struct restriction *r=&restrictions[i];
        if (n+3+3*r->count>=sizeof(rules)/sizeof(*rules)) return -E2BIG;
        ADD(BPF_STMT(BPF_LD|BPF_W|BPF_ABS,number_offset));
        unsigned start=n;
        ADD(BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,r->number,0,0));
        unsigned jumps[2];
        for (unsigned j=0;j<r->count;++j) {
            const struct condition *c=&r->conditions[j];
            if (c->argument>=5) return -EINVAL;
            ADD(BPF_STMT(BPF_LD|BPF_W|BPF_ABS,
                offsetof(struct seccomp_data,args)+c->argument*sizeof(uint64_t)));
            ADD(BPF_STMT(BPF_ALU|BPF_AND|BPF_K,c->mask));
            jumps[j]=n;
            ADD(BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,c->value,0,0));
        }
        ADD(BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|result_errno(r->error,i+1,probe)));
        rules[start].jf=(unsigned char)(n-start-1);
        for (unsigned j=0;j<r->count;++j) rules[jumps[j]].jf=(unsigned char)(n-jumps[j]-1);
    }
    ADD(BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW));
#undef ADD
    struct sock_fprog program={.len=(unsigned short)n,.filter=rules};
    if (prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)
            || syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)) return -errno;
    return verify(probe);
}

static int capture_restriction(void) {
    /* Leave the bootstrap listener and unrelated descriptors available. */
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_pidfd_getfd,0,4),
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,args[1])),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,STDIN_FILENO,1,0),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,MD_TEST_CAPTURE_FD,0,1),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)
    };
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    if (prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)
            || syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&program)) return -errno;
    if (syscall(SYS_pidfd_getfd,-1,0,0)!=-1 || errno!=EPERM
            || syscall(SYS_pidfd_getfd,-1,MD_TEST_CAPTURE_FD,0)!=-1 || errno!=EPERM
            || syscall(SYS_pidfd_getfd,-1,1,0)!=-1 || errno!=EBADF) return -EPROTO;
    fprintf(stderr,"SHROOT_CAPTURE_PROFILE host-only pidfd-getfd=denied descriptors=0,%d\n",MD_TEST_CAPTURE_FD);
    return 0;
}

int md_test_kernel_profile_install(int profile) {
    if (profile&~(PROFILE_5_15|PROFILE_CAPTURE)) return -EINVAL;
    if (!profile) return 0;
    if (profile&PROFILE_CAPTURE) {
        int error=capture_restriction();
        if (error) return error;
    }
    if (!(profile&PROFILE_5_15)) return 0;
    pid_t child=fork();
    if (!child) _exit(install(1) ? 1 : 0);
    if (child<0) return -errno;
    /* EVENT_WAIT: the selector probe must complete before profile admission;
     * the fixture's command deadline bounds a nonterminating child. */
    int status,waited;
    do { waited=waitpid(child,&status,0); } while (waited<0 && errno==EINTR);
    if (waited!=child || !WIFEXITED(status) || WEXITSTATUS(status)) return -EPROTO;
    int error=install(0);
    if (error) return error;
    struct utsname host;
    if (uname(&host)) return -errno;
    fprintf(stderr,"SHROOT_KERNEL_PROFILE {\"profile\":\"linux-5.15\",\"scope\":\"host\","
        "\"verified\":true,\"rules\":%zu,\"kernel\":\"%s\"}\n",
        1+sizeof(restrictions)/sizeof(*restrictions),host.release);
    return 0;
}

int md_test_kernel_profile_prepare(int argc,char **argv) {
    const char *value=getenv("MAGICDESK_TEST_KERNEL_PROFILE");
    int profile=value && !strcmp(value,"linux-5.15") ? PROFILE_5_15 : value ? -EINVAL : 0;
    unsetenv("MAGICDESK_TEST_KERNEL_PROFILE");
    if (profile<0) {
        fputs("Unknown test kernel profile\n",stderr); exit(125);
    }
    const char *capture=getenv("MAGICDESK_TEST_CAPTURE_FALLBACK");
    if (capture && strcmp(capture,"1")) {
        fputs("Unknown test capture profile\n",stderr); exit(125);
    }
    if (capture) profile|=PROFILE_CAPTURE;
    unsetenv("MAGICDESK_TEST_CAPTURE_FALLBACK");
    if (argc==2 && !strcmp(argv[1],"--test-kernel-profile")) {
        int error=profile ? md_test_kernel_profile_install(profile) : -EINVAL;
        if (error) fprintf(stderr,"kernel profile: %s\n",strerror(-error));
        exit(error ? 125 : 0);
    }
    return profile;
}
