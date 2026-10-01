#define _GNU_SOURCE
#include <errno.h>
#include <linux/filter.h>
#include <linux/netlink.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr,"guest-interfaces line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static int probe(int family,int type,int protocol) {
    int fd=(int)syscall(SYS_socket,family,type,protocol);
    if (fd<0) return errno;
    CHECK(!close(fd)); return 0;
}
static void unavailable(void) {
    CHECK(probe(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_AUDIT)==EPROTONOSUPPORT);
    CHECK(probe(AF_NETLINK,SOCK_DGRAM|SOCK_NONBLOCK,NETLINK_AUDIT)==EPROTONOSUPPORT);
    CHECK(syscall(SYS_socket,(1UL<<32)|AF_NETLINK,SOCK_RAW,(1UL<<32)|NETLINK_AUDIT)==-1 && errno==EPROTONOSUPPORT);
}
static void trap(int sig,siginfo_t *info,void *context) {
    CHECK(sig==SIGSYS && info->si_syscall==SYS_socket);
    ((ucontext_t *)context)->uc_mcontext.regs[0]=(unsigned long)-EKEYREJECTED;
}
static void filters(void) {
    unsigned actions[]={SECCOMP_RET_ERRNO|EKEYREJECTED,SECCOMP_RET_TRAP,SECCOMP_RET_KILL_PROCESS};
    for (unsigned i=0;i<sizeof(actions)/sizeof(*actions);i++) {
        pid_t child=fork(); CHECK(child>=0);
        if (!child) {
            struct sigaction action={.sa_sigaction=trap,.sa_flags=SA_SIGINFO};
            sigemptyset(&action.sa_mask); CHECK(!sigaction(SIGSYS,&action,NULL));
            struct sock_filter code[]={
                BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
                BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_socket,0,1),
                BPF_STMT(BPF_RET|BPF_K,actions[i]),
                BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW),
            };
            struct sock_fprog policy={sizeof(code)/sizeof(*code),code};
            CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
            CHECK(!syscall(SYS_seccomp,SECCOMP_SET_MODE_FILTER,0,&policy));
            CHECK(probe(AF_NETLINK,SOCK_RAW,NETLINK_AUDIT)==EKEYREJECTED);
            _exit(0);
        }
        int status;
        /* EVENT_WAIT: exact child exit; the guest launch deadline bounds failure. */
        CHECK(waitpid(child,&status,0)==child);
        if (actions[i]==SECCOMP_RET_KILL_PROCESS) CHECK(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        else CHECK(WIFEXITED(status) && WEXITSTATUS(status)==0);
    }
}
int main(int argc,char **argv) {
    CHECK(argc==2 && (!strcmp(argv[1],"native") || !strcmp(argv[1],"guest")));
    int guest=!strcmp(argv[1],"guest");
    if (guest) unavailable();
    else printf("native-audit=%d\n",probe(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_AUDIT));
    printf("controls unix=%d inet=%d route=%d diag=%d invalid=%d\n",
        probe(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0),probe(AF_INET,SOCK_DGRAM,0),
        probe(AF_NETLINK,SOCK_RAW,NETLINK_ROUTE),probe(AF_NETLINK,SOCK_RAW,NETLINK_SOCK_DIAG),
        probe(-1,SOCK_STREAM,0));
    if (guest) {
        filters();
        CHECK(!prctl(PR_SET_DUMPABLE,0,0,0,0)); unavailable();
        puts("PASS guest audit unavailable, unchanged socket controls, ERRNO/TRAP/KILL precedence, protected task");
    }
    return 0;
}
