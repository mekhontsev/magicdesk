#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/keyctl.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int denied_call=SYS_keyctl;
static void absent(void) {
    assert(syscall(SYS_keyctl,KEYCTL_JOIN_SESSION_KEYRING,"shroot-fixture",0,0,0)==-1 && errno==ENOSYS);
    assert(syscall(SYS_keyctl,KEYCTL_GET_KEYRING_ID,KEY_SPEC_SESSION_KEYRING,1,0,0)==-1 && errno==ENOSYS);
    assert(syscall(SYS_add_key,"user","shroot-fixture","data",4,KEY_SPEC_SESSION_KEYRING)==-1 && errno==ENOSYS);
    assert(syscall(SYS_request_key,"user","shroot-fixture",NULL,KEY_SPEC_SESSION_KEYRING)==-1 && errno==ENOSYS);
    assert(syscall(SYS_keyctl,-1,1,1,1,1)==-1 && errno==ENOSYS);
    const int queues[]={SYS_mq_open,SYS_mq_unlink,SYS_mq_timedsend,
        SYS_mq_timedreceive,SYS_mq_notify,SYS_mq_getsetattr};
    for (unsigned i=0;i<sizeof(queues)/sizeof(*queues);++i)
        assert(syscall(queues[i],0,0,0,0,0,0)==-1 && errno==ENOSYS);
    assert(syscall(SYS_name_to_handle_at,AT_FDCWD,"/",NULL,NULL,0)==-1 && errno==ENOSYS);
    assert(syscall(SYS_open_by_handle_at,-1,NULL,O_RDONLY)==-1 && errno==ENOSYS);
    assert(syscall(SYS_io_uring_setup,128,NULL)==-1 && errno==ENOSYS);
    assert(syscall(SYS_io_uring_enter,-1,0,0,0,NULL,0)==-1 && errno==ENOSYS);
    assert(syscall(SYS_io_uring_register,-1,0,NULL,0)==-1 && errno==ENOSYS);
}
static void trapped(int signal,siginfo_t *info,void *context) {
    (void)context;
    _exit(signal==SIGSYS && info->si_code==SYS_SECCOMP
        && info->si_syscall==denied_call && info->si_errno==37 ? 0 : 98);
}
static void filter(unsigned result) {
    struct sock_filter code[]={
        BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),
        BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,denied_call,0,1),
        BPF_STMT(BPF_RET|BPF_K,result),
        BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
    struct sock_fprog program={sizeof(code)/sizeof(*code),code};
    assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
    assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&program));
}
int test_keyring_guest(int argc,char **argv) {
    alarm(30); absent();
    if (argc==3 && !strcmp(argv[2],"exec")) return 0;
    const int calls[]={SYS_keyctl,SYS_name_to_handle_at,SYS_open_by_handle_at,
        SYS_io_uring_setup,SYS_io_uring_enter,SYS_io_uring_register};
    for (unsigned call=0;call<sizeof(calls)/sizeof(*calls);++call)
    for (unsigned mode=0;mode<7;++mode) {
        denied_call=calls[call];
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            if (mode==0) { execl(argv[0],argv[0],"keyring","exec",(char *)NULL); _exit(99); }
            if (mode==1) assert(!prctl(PR_SET_DUMPABLE,0));
            if (mode==2) assert(!unshare(CLONE_NEWUSER|CLONE_NEWNS));
            if (mode==3) assert(!chroot("/tmp"));
            if (mode==4) {
                filter(SECCOMP_RET_ERRNO|EACCES);
                assert(syscall(denied_call,0,0,0,0,0,0)==-1 && errno==EACCES);
                _exit(0);
            }
            if (mode==5) {
                filter(SECCOMP_RET_KILL_PROCESS);
                syscall(denied_call,0,0,0,0,0,0); _exit(99);
            }
            if (mode==6) {
                struct sigaction action={.sa_sigaction=trapped,.sa_flags=SA_SIGINFO};
                assert(!sigaction(SIGSYS,&action,NULL));
                filter(SECCOMP_RET_TRAP|37);
                syscall(denied_call,0,0,0,0,0,0); _exit(99);
            }
            absent(); _exit(0);
        }
        /* EVENT_WAIT: exact child exit; alarm fails a stuck lifecycle. */
        int status; assert(waitpid(child,&status,0)==child);
        if (mode==5) assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS);
        else assert(WIFEXITED(status) && !WEXITSTATUS(status));
    }
    puts("PASS guest keyring absence: queues, file handles and io_uring, fork, exec, protected caller, user namespace, chroot and application filters");
    return 0;
}
