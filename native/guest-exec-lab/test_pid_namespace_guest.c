#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/ioprio.h>
#include <linux/capability.h>
#include <linux/reboot.h>
#include <sched.h>
#include <pthread.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/signalfd.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

static void reap(pid_t child) {
    int status=0;
    pid_t result=waitpid(child,&status,0);
    if (result!=child || status) fprintf(stderr,"pid wait expected=%d got=%d status=%x errno=%d\n",child,result,status,errno);
    assert(result==child && status==0);
}
static void wait_init_leader_exit(void) {
    /* EVENT_WAIT: leader retirement; the fixture alarm bounds failure. */
    char text[4096];
    for (;;) {
        int fd=open("/proc/self/status",O_RDONLY); assert(fd>=0);
        ssize_t n=read(fd,text,sizeof(text)-1); close(fd); assert(n>0); text[n]=0;
        if (strstr(text,"State:\tZ")) return;
        sched_yield();
    }
}
static void *reboot_thread(void *value) {
    wait_init_leader_exit();
    syscall(SYS_reboot,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,*(unsigned *)value,NULL);
    _exit(91);
}
struct reboot_request { unsigned command; int reader,child,thread; };
static int reboot_init(void *value) {
    struct reboot_request *request=value;
    unsigned command=request->command;
    close(request->reader);
    assert(getpid()==1);
    errno=0;
    assert(syscall(SYS_reboot,0,LINUX_REBOOT_MAGIC2,command,NULL)==-1 && errno==EINVAL);
    errno=0;
    assert(syscall(SYS_reboot,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,LINUX_REBOOT_CMD_CAD_OFF,NULL)==-1 && errno==EINVAL);
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3};
    struct __user_cap_data_struct saved[2],reduced[2];
    assert(!syscall(SYS_capget,&header,saved)); memcpy(reduced,saved,sizeof(saved));
    reduced[0].effective&=~(1U<<CAP_SYS_BOOT);
    assert(!syscall(SYS_capset,&header,reduced));
    errno=0;
    assert(syscall(SYS_reboot,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,command,NULL)==-1 && errno==EPERM);
    assert(!syscall(SYS_capset,&header,saved));
    /* Namespace shutdown overrides blocked/ignored application signals. */
    assert(signal(SIGINT,SIG_IGN)!=SIG_ERR && signal(SIGHUP,SIG_IGN)!=SIG_ERR);
    sigset_t mask; sigfillset(&mask); assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
    pid_t descendant=fork(); assert(descendant>=0);
    if (!descendant) for (;;) pause();
    if (request->thread) {
        pthread_t thread; assert(!pthread_create(&thread,NULL,reboot_thread,&request->command));
        pthread_exit(NULL);
    }
    if (request->child) {
        pid_t caller=fork(); assert(caller>=0);
        if (caller) for (;;) pause();
    }
    syscall(SYS_reboot,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,command,(void *)1);
    _exit(90);
}
static void reboot_cases(void *stack) {
    errno=0;
    assert(syscall(SYS_reboot,LINUX_REBOOT_MAGIC1,LINUX_REBOOT_MAGIC2,LINUX_REBOOT_CMD_POWER_OFF,NULL)==-1 && errno==EPERM);
    unsigned commands[]={LINUX_REBOOT_CMD_POWER_OFF,LINUX_REBOOT_CMD_HALT,LINUX_REBOOT_CMD_RESTART,LINUX_REBOOT_CMD_RESTART2,
        LINUX_REBOOT_CMD_POWER_OFF};
    for (unsigned i=0;i<sizeof(commands)/sizeof(*commands);++i) {
        int pipefd[2]; assert(!pipe(pipefd));
        sigset_t mask,previous; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
        assert(!sigprocmask(SIG_BLOCK,&mask,&previous));
        struct reboot_request request={commands[i],pipefd[0],i&1,i==4};
        pid_t child=clone(reboot_init,(char *)stack+1024*1024,CLONE_NEWPID|SIGCHLD,&request);
        assert(child>0);
        close(pipefd[1]);
        assert(!prctl(PR_SET_DUMPABLE,0));
        siginfo_t info={0};
        assert(!waitid(P_PID,child,&info,WEXITED|WNOWAIT));
        int signal=i==2 || i==3 ? SIGHUP : SIGINT;
        if (info.si_code!=CLD_KILLED || info.si_status!=signal)
            fprintf(stderr,"reboot info code=%d status=%d expected=%d\n",info.si_code,info.si_status,signal);
        assert(info.si_pid==child && info.si_code==CLD_KILLED && info.si_status==signal);
        struct timespec timeout={1,0}; info=(siginfo_t){0};
        assert(sigtimedwait(&mask,&info,&timeout)==SIGCHLD);
        if (info.si_pid!=child || info.si_code!=CLD_KILLED || info.si_status!=signal)
            fprintf(stderr,"reboot SIGCHLD pid=%d expected=%d code=%d status=%d expected=%d\n",
                info.si_pid,child,info.si_code,info.si_status,signal);
        assert(info.si_pid==child && info.si_code==CLD_KILLED && info.si_status==signal);
        int status; assert(waitpid(child,&status,0)==child && WIFSIGNALED(status) && WTERMSIG(status)==signal);
        char byte; assert(read(pipefd[0],&byte,1)==0); close(pipefd[0]);
        assert(!sigprocmask(SIG_SETMASK,&previous,NULL));
        assert(!prctl(PR_SET_DUMPABLE,1));
    }
    puts("PASS namespace poweroff/restart: capability, invalid commands, ignored signals, child/thread requests, descendant cleanup and protected parent wait status");
}
static void *worker(void *unused) {
    (void)unused;
    assert(getpid()==1 && syscall(SYS_gettid)>1 && getppid()==0);
    return (void *)42;
}
static void link_is(const char *path,const char *expected) {
    char value[128]={0};
    ssize_t n=readlink(path,value,sizeof(value)-1);
    if (n<0 || strcmp(value,expected)) fprintf(stderr,"proc link %s = %s (n=%zd errno=%d), expected %s\n",path,value,n,errno,expected);
    assert(n>0 && !strcmp(value,expected));
}
static void inherited_proc_status(unsigned depth) {
    int fd=open("/proc/self/status",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    char text[16384]; ssize_t n=read(fd,text,sizeof(text)-1); assert(n>0); text[n]=0; close(fd);
    const char *names[]={"\nNSpid:","\nNStgid:"};
    for (unsigned i=0;i<2;++i) {
        char *line=strstr(text,names[i]); assert(line); line+=strlen(names[i]);
        unsigned count=0; long last=0;
        while (*line && *line!='\n') {
            if (*line=='\t' || *line==' ') { ++line; continue; }
            char *end; last=strtol(line,&end,10); assert(end!=line && last>0); line=end; ++count;
        }
        if (count!=depth || last!=getpid()) fprintf(stderr,"inherited proc depth=%u expected=%u last=%ld pid=%d\n%s",count,depth,last,getpid(),text);
        assert(count==depth && last==getpid());
    }
    puts("PASS inherited proc status retains the complete PID namespace chain");
}
static void proc_cases(void) {
    assert(!unshare(CLONE_NEWNS));
    assert(!mkdir("/tmp/pid-proc",0700));
    assert(!mount("proc","/tmp/pid-proc","proc",MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL));
    assert(!syscall(SYS_faccessat2,AT_FDCWD,"/tmp/pid-proc/1/status",R_OK,0x200));
    assert(!syscall(SYS_faccessat2,AT_FDCWD,"/tmp/pid-proc/1/status",W_OK,0x200));
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3};
    struct __user_cap_data_struct saved[2],reduced[2];
    assert(!syscall(SYS_capget,&header,saved));
    memcpy(reduced,saved,sizeof(saved));
    reduced[0].effective&=~(1U<<CAP_DAC_OVERRIDE);
    assert(!syscall(SYS_capset,&header,reduced));
    errno=0;
    assert(syscall(SYS_faccessat2,AT_FDCWD,"/tmp/pid-proc/1/status",W_OK,0x200)==-1 && errno==EACCES);
    assert(!syscall(SYS_capset,&header,saved));
    link_is("/tmp/pid-proc/self","1");
    link_is("/tmp/pid-proc/thread-self","1/task/1");
    DIR *dir=opendir("/tmp/pid-proc"); assert(dir);
    struct dirent *entry; unsigned processes=0;
    while ((entry=readdir(dir))) if (entry->d_name[0]>='0' && entry->d_name[0]<='9') {
        assert(!strcmp(entry->d_name,"1")); ++processes;
    }
    assert(processes==1);
    int fd=openat(dirfd(dir),"1/ns/pid",O_RDONLY|O_CLOEXEC); assert(fd>=0); close(fd);
    fd=openat(dirfd(dir),"self/status",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    struct stat st; assert(!fstat(fd,&st) && (st.st_mode&0777)==0444);
    char text[8192]={0}; ssize_t n=read(fd,text,sizeof(text)-1); assert(n>0);
    assert(strstr(text,"\nPid:\t1\n") && strstr(text,"\nPPid:\t0\n"));
    assert(strstr(text,"\nNSpid:\t1\n"));
    assert(strstr(text,"\nUid:\t0\t0\t0\t0\n") && strstr(text,"\nTracerPid:\t0\n"));
    assert(lseek(fd,0,SEEK_SET)==0); assert(read(fd,text,1)==1); close(fd);
    fd=openat(dirfd(dir),"1/task/1/stat",O_RDONLY|O_CLOEXEC); assert(fd>=0);
    n=read(fd,text,sizeof(text)-1); assert(n>0); text[n]=0;
    assert(text[0]=='1' && text[1]==' '); close(fd);
    closedir(dir);
    puts("PASS mounted proc namespace: self, thread-self, enumeration and relative namespace handles");
}
static void zombie_signal_case(int credentials) {
    pid_t child=fork(); assert(child>=0);
    if (!child) { assert(setsid()==getpid()); _exit(23); }
    siginfo_t info={0};
    /* EVENT_WAIT: WNOWAIT proves exit while retaining kernel PID/group identity. */
    assert(!waitid(P_PID,child,&info,WEXITED|WNOWAIT));
    assert(info.si_pid==child && info.si_status==23);
    assert(getpgid(child)==child);
    assert(!kill(child,0));
    assert(!kill(-child,0));
    assert(!kill(-child,SIGKILL));
    if (credentials) {
        pid_t sender=fork(); assert(sender>=0);
        if (!sender) {
            assert(!setresgid(1001,1001,1001) && !setresuid(1001,1001,1001));
            errno=0; assert(kill(child,0)==-1 && errno==EPERM);
            errno=0; assert(kill(-child,0)==-1 && errno==EPERM);
            _exit(0);
        }
        reap(sender);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && WEXITSTATUS(status)==23);
    errno=0; assert(kill(child,0)==-1 && errno==ESRCH);
    errno=0; assert(kill(-child,0)==-1 && errno==ESRCH);
    puts("PASS unreaped process/group signals retain identity and permission checks until wait");
}
static volatile sig_atomic_t reaped_sender,reaped_uid,reaped_status;
static void reaped_handler(int signal,siginfo_t *info,void *context) {
    (void)signal; (void)context;
    reaped_sender=info->si_pid; reaped_uid=info->si_uid; reaped_status=info->si_status;
}
static void *reaped_waiter(void *argument) {
    pid_t child=*(pid_t *)argument;
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
    siginfo_t info={0}; struct timespec timeout={5,0};
    assert(sigtimedwait(&mask,&info,&timeout)==SIGCHLD);
    fprintf(stderr,"reaped SIGCHLD child=%d sender=%d uid=%d expected=%d status=%d\n",
        child,info.si_pid,info.si_uid,getuid(),info.si_status);
    assert(info.si_pid==child && info.si_uid==getuid() && info.si_status==23);
    return NULL;
}
static void *reaped_exec_worker(void *argument) {
    char child[32]; snprintf(child,sizeof(child),"%d",*(pid_t *)argument);
    execl("/bin/test","/bin/test","pid-namespace","reaped-exec",child,NULL);
    _exit(90);
}
static void reaped_exec_case(void) {
    pid_t parent=fork(); assert(parent>=0);
    if (!parent) {
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
        assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(23);
        assert(waitpid(child,NULL,0)==child);
        pthread_t worker; assert(!pthread_create(&worker,NULL,reaped_exec_worker,&child));
        pthread_join(worker,NULL); _exit(91);
    }
    reap(parent);
    puts("PASS reaped child SIGCHLD survives nonleader exec");
}
static void reaped_signal_cases(void) {
    sigset_t mask,saved; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
    struct sigaction action={.sa_sigaction=reaped_handler,.sa_flags=SA_SIGINFO},old;
    sigemptyset(&action.sa_mask);
    assert(!sigaction(SIGCHLD,&action,&old));
    assert(!sigprocmask(SIG_BLOCK,&mask,&saved));
    siginfo_t info; struct timespec zero={0};
    while (sigtimedwait(&mask,&info,&zero)==SIGCHLD) {}
    for (int mode=0;mode<7;++mode) {
        if (mode==6) {
            action.sa_flags|=SA_NOCLDWAIT;
            assert(!sigaction(SIGCHLD,&action,NULL));
        }
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(23);
        if (mode==6) {
            errno=0; assert(waitpid(child,NULL,0)==-1 && errno==ECHILD);
        } else if (mode==1) {
            info=(siginfo_t){0}; assert(!waitid(P_PID,child,&info,WEXITED));
            assert(info.si_pid==child && info.si_uid==getuid());
        } else {
            int status; assert(waitpid(child,&status,0)==child);
            assert(WIFEXITED(status) && WEXITSTATUS(status)==23);
        }
        errno=0; assert(kill(child,0)==-1 && errno==ESRCH);
        /* A second child coalesces with the first pending notice, even after
         * both wait results have removed their process-table entries. */
        pid_t second=fork(); assert(second>=0);
        if (!second) _exit(24);
        if (mode==6) { errno=0; assert(waitpid(second,NULL,0)==-1 && errno==ECHILD); }
        else assert(waitpid(second,NULL,0)==second);
        if (mode==2) {
            pthread_t worker; assert(!pthread_create(&worker,NULL,reaped_waiter,&child));
            assert(!pthread_join(worker,NULL));
        } else if (mode==4 || mode==5) {
            int fd=signalfd(-1,&mask,SFD_CLOEXEC|SFD_NONBLOCK); assert(fd>=0);
            struct signalfd_siginfo record={0};
            if (mode==4) assert(read(fd,&record,sizeof(record))==sizeof(record));
            else {
                int copy=dup(fd); assert(copy>=0); close(fd); fd=copy;
                struct iovec vectors[]={{&record,17},{(char *)&record+17,sizeof(record)-17}};
                assert(readv(fd,vectors,2)==sizeof(record));
            }
            if (record.ssi_signo!=SIGCHLD || record.ssi_pid!=(unsigned)child
                    || record.ssi_uid!=getuid() || record.ssi_status!=23)
                fprintf(stderr,"signalfd mode=%d signo=%u pid=%u expected=%u uid=%u expected=%u status=%u\n",
                    mode,record.ssi_signo,record.ssi_pid,(unsigned)child,record.ssi_uid,getuid(),record.ssi_status);
            assert(record.ssi_signo==SIGCHLD && record.ssi_pid==(unsigned)child
                && record.ssi_uid==getuid() && record.ssi_status==23);
            close(fd);
        } else if (mode==3) {
            reaped_sender=0;
            assert(!sigprocmask(SIG_UNBLOCK,&mask,NULL));
            assert(reaped_sender==child && reaped_uid==(sig_atomic_t)getuid() && reaped_status==23);
            assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        } else reaped_waiter(&child);
    }
    siginfo_t queued={.si_signo=SIGCHLD,.si_code=SI_QUEUE,.si_pid=64321,.si_uid=7654};
    assert(!syscall(SYS_rt_sigqueueinfo,getpid(),SIGCHLD,&queued));
    assert(sigtimedwait(&mask,&info,&zero)==SIGCHLD);
    assert(info.si_pid==64321 && info.si_uid==7654 && info.si_code==SI_QUEUE);
    assert(!sigaction(SIGCHLD,&old,NULL));
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL));
    puts("PASS reaped child SIGCHLD retains sender identity across wait, coalescing and threads");
}
static void reaped_debugger_case(int detach) {
    int pipefd[2]; assert(!pipe(pipefd));
    pid_t tracee=fork(); assert(tracee>=0);
    if (!tracee) {
        close(pipefd[0]);
        struct sigaction action={.sa_sigaction=reaped_handler,.sa_flags=SA_SIGINFO};
        sigemptyset(&action.sa_mask); assert(!sigaction(SIGCHLD,&action,NULL));
        sigset_t mask; sigemptyset(&mask); sigaddset(&mask,SIGCHLD);
        assert(!sigprocmask(SIG_BLOCK,&mask,NULL));
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL)); raise(SIGSTOP);
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(23);
        assert(waitpid(child,NULL,0)==child);
        assert(write(pipefd[1],&child,sizeof(child))==sizeof(child)); close(pipefd[1]);
        reaped_sender=0;
        assert(!sigprocmask(SIG_UNBLOCK,&mask,NULL));
        assert(reaped_sender==child && reaped_uid==(sig_atomic_t)getuid() && reaped_status==(detach ? 24 : 23));
        _exit(0);
    }
    close(pipefd[1]);
    int status; assert(waitpid(tracee,&status,0)==tracee && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_CONT,tracee,NULL,NULL));
    pid_t child; assert(read(pipefd[0],&child,sizeof(child))==sizeof(child)); close(pipefd[0]);
    assert(waitpid(tracee,&status,0)==tracee && WIFSTOPPED(status) && WSTOPSIG(status)==SIGCHLD);
    siginfo_t info={0}; assert(!ptrace(PTRACE_GETSIGINFO,tracee,NULL,&info));
    assert(info.si_pid==child && info.si_uid==getuid() && info.si_status==23);
    assert(!ptrace(PTRACE_SETSIGINFO,tracee,NULL,&info));
    info=(siginfo_t){0}; assert(!ptrace(PTRACE_GETSIGINFO,tracee,NULL,&info));
    assert(info.si_pid==child && info.si_uid==getuid() && info.si_status==23);
    info.si_status=24; info.si_errno=71;
    assert(!ptrace(PTRACE_SETSIGINFO,tracee,NULL,&info));
    info=(siginfo_t){0}; assert(!ptrace(PTRACE_GETSIGINFO,tracee,NULL,&info));
    assert(info.si_pid==child && info.si_uid==getuid() && info.si_status==24 && info.si_errno==71);
    if (!detach) { info.si_status=23; assert(!ptrace(PTRACE_SETSIGINFO,tracee,NULL,&info)); }
    assert(!ptrace(detach ? PTRACE_DETACH : PTRACE_CONT,tracee,NULL,(void *)SIGCHLD)); reap(tracee);
    puts("PASS debugger and recipient retain reaped SIGCHLD identity");
}
static void wait_cases(void) {
    reaped_signal_cases();
    reaped_debugger_case(0); reaped_debugger_case(1);
    reaped_exec_case();
    zombie_signal_case(1);
    pid_t child=fork(); assert(child>0 || !child);
    if (!child) _exit(17);
    siginfo_t info={0};
    assert(!waitid(P_PID,child,&info,WEXITED|WNOWAIT));
    assert(info.si_pid==child && info.si_uid==getuid() && info.si_code==CLD_EXITED && info.si_status==17);
    char path[128],text[8192],expected[64];
    snprintf(path,sizeof(path),"/tmp/pid-proc/%d/status",child);
    int zombie=open(path,O_RDONLY|O_CLOEXEC); assert(zombie>=0);
    ssize_t n=read(zombie,text,sizeof(text)-1); assert(n>0); text[n]=0;
    snprintf(expected,sizeof(expected),"\nPid:\t%d\n",child);
    assert(strstr(text,expected) && strstr(text,"State:\tZ"));
    assert(strstr(text,"\nUid:\t0\t0\t0\t0\n")); close(zombie);
    info=(siginfo_t){0};
    assert(!waitid(P_PID,child,&info,WEXITED));
    assert(info.si_pid==child && info.si_status==17);
    errno=0; assert(waitpid(child,NULL,WNOHANG)==-1 && errno==ECHILD);
    errno=0; assert(open(path,O_RDONLY)==-1 && errno==ENOENT);
    int barrier[2]; assert(!pipe(barrier));
    child=fork(); assert(child>=0);
    if (!child) { close(barrier[1]); char byte; assert(read(barrier[0],&byte,1)==0); _exit(19); }
    close(barrier[0]);
    int fd=syscall(SYS_pidfd_open,child,0); assert(fd>=0);
    assert(!syscall(SYS_pidfd_send_signal,fd,0,NULL,0));
    close(barrier[1]);
    assert(!prctl(PR_SET_DUMPABLE,0));
    info=(siginfo_t){0};
    assert(!syscall(SYS_waitid,3,fd,&info,WEXITED,NULL));
    assert(info.si_pid==child && info.si_uid==getuid() && info.si_status==19);
    assert(!prctl(PR_SET_DUMPABLE,1));
    errno=0; assert(syscall(SYS_pidfd_send_signal,fd,0,NULL,0)==-1 && errno==ESRCH);
    snprintf(path,sizeof(path),"/tmp/pid-proc/%d/status",child);
    errno=0; assert(open(path,O_RDONLY)==-1 && errno==ENOENT);
    close(fd);
    pthread_t thread; void *value=NULL;
    assert(!pthread_create(&thread,NULL,worker,NULL));
    assert(!pthread_join(thread,&value) && value==(void *)42);
    puts("PASS PID waitid WNOWAIT, pidfd wait, protected output and thread join");
}
static void io_priority_cases(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!setpgid(0,0));
        int value=IOPRIO_PRIO_VALUE(IOPRIO_CLASS_BE,7);
        assert(!syscall(SYS_ioprio_set,IOPRIO_WHO_PROCESS,getpid(),value));
        assert(syscall(SYS_ioprio_get,IOPRIO_WHO_PROCESS,0)==value);
        assert(syscall(SYS_ioprio_get,IOPRIO_WHO_PROCESS,getpid())==value);
        value=IOPRIO_PRIO_VALUE(IOPRIO_CLASS_BE,6);
        assert(!syscall(SYS_ioprio_set,IOPRIO_WHO_PGRP,getpgrp(),value));
        assert(syscall(SYS_ioprio_get,IOPRIO_WHO_PGRP,getpgrp())==value);
        errno=0;
        assert(syscall(SYS_ioprio_get,IOPRIO_WHO_PROCESS,0x7fffffff)==-1 && errno==ESRCH);
        errno=0;
        assert(syscall(SYS_ioprio_set,IOPRIO_WHO_USER,0,value)==-1 && errno==EOPNOTSUPP);
        _exit(0);
    }
    reap(child);
    puts("PASS I/O priority translates process/group IDs and rejects native user-wide selection");
}
static void orphan_cases(void) {
    int value=-1;
    assert(!prctl(PR_GET_CHILD_SUBREAPER,&value) && value==0);
    assert(!prctl(PR_SET_CHILD_SUBREAPER,0));
    errno=0; assert(prctl(PR_GET_CHILD_SUBREAPER,(void *)1)==-1 && errno==EFAULT);
    int identity[2], gate[2]; assert(!pipe(identity) && !pipe(gate));
    pid_t parent=fork(); assert(parent>=0);
    if (!parent) {
        close(identity[0]); close(gate[1]);
        pid_t orphan=fork(); assert(orphan>=0);
        if (!orphan) {
            pid_t number=getpid(); assert(write(identity[1],&number,sizeof(number))==sizeof(number));
            close(identity[1]); char byte;
            assert(read(gate[0],&byte,1)==0);
            assert(getppid()==1); _exit(23);
        }
        _exit(0);
    }
    close(identity[1]); close(gate[0]);
    pid_t orphan=0; assert(read(identity[0],&orphan,sizeof(orphan))==sizeof(orphan)); close(identity[0]);
    reap(parent); close(gate[1]);
    int status=0; assert(waitpid(orphan,&status,0)==orphan && WIFEXITED(status) && WEXITSTATUS(status)==23);
    puts("PASS PID init adopts and reaps orphan with native wait semantics");
}
static void autoreap_case(void) {
    struct sigaction ignored={.sa_handler=SIG_IGN},saved;
    sigemptyset(&ignored.sa_mask); assert(!sigaction(SIGCHLD,&ignored,&saved));
    int gate[2]; assert(!pipe(gate));
    pid_t child=fork(); assert(child>=0);
    if (!child) { close(gate[1]); char byte; assert(read(gate[0],&byte,1)==0); _exit(0); }
    close(gate[0]);
    int fd=syscall(SYS_pidfd_open,child,0); assert(fd>=0);
    close(gate[1]);
    struct pollfd event={.fd=fd,.events=POLLIN};
    /* EVENT_WAIT: pidfd terminal readiness; timeout fails the fixture. */
    assert(poll(&event,1,5000)==1 && (event.revents&POLLIN));
    errno=0; assert(waitpid(child,NULL,0)==-1 && errno==ECHILD);
    errno=0; assert(syscall(SYS_pidfd_send_signal,fd,0,NULL,0)==-1 && errno==ESRCH);
    close(fd);
    assert(!sigaction(SIGCHLD,&saved,NULL));
    puts("PASS ignored SIGCHLD retains native autoreaping and pidfd terminal state");
}
static volatile sig_atomic_t delivered_sender;
static void signal_sender(int signal,siginfo_t *info,void *context) {
    (void)signal; (void)context; delivered_sender=info->si_pid;
}
static void signal_cases(void) {
    struct sigaction action={.sa_sigaction=signal_sender,.sa_flags=SA_SIGINFO};
    sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR1,&action,NULL));
    sigset_t blocked,saved; sigemptyset(&blocked); sigaddset(&blocked,SIGUSR2);
    assert(!sigprocmask(SIG_BLOCK,&blocked,&saved));
    int ready[2],gate[2]; assert(!pipe(ready) && !pipe(gate));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(ready[0]); close(gate[1]);
        assert(write(ready[1],"r",1)==1); close(ready[1]);
        siginfo_t info={0}; struct timespec timeout={5,0};
        assert(sigtimedwait(&blocked,&info,&timeout)==SIGUSR2);
        assert(info.si_pid==1 && info.si_uid==getuid() && info.si_code==SI_USER);
        char byte;
        while (read(gate[0],&byte,1)<0) assert(errno==EINTR);
        assert(delivered_sender==1); _exit(0);
    }
    close(ready[1]); close(gate[0]); char byte;
    assert(read(ready[0],&byte,1)==1); close(ready[0]);
    assert(!kill(child,SIGUSR2));
    assert(!kill(0,SIGUSR1));
    assert(delivered_sender==1); close(gate[1]); reap(child);
    siginfo_t queued={.si_signo=SIGUSR2,.si_code=SI_QUEUE,.si_pid=64321,.si_uid=getuid()};
    assert(!syscall(SYS_rt_sigqueueinfo,1,SIGUSR2,&queued));
    struct timespec timeout={5,0};
    assert(sigtimedwait(&blocked,&queued,&timeout)==SIGUSR2 && queued.si_pid==64321);
    assert(!sigprocmask(SIG_SETMASK,&saved,NULL));
    puts("PASS PID signals: group sender, blocked siginfo and queued payload");
}
static void pidfd_transfer(void) {
    int channel[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET,0,channel));
    int fd=syscall(SYS_pidfd_open,1,0); assert(fd>=0);
    pid_t child=fork(); assert(child>=0);
    char byte='p',control[CMSG_SPACE(sizeof(int))]={0};
    struct iovec data={&byte,1};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    if (!child) {
        close(channel[0]); close(fd);
        assert(recvmsg(channel[1],&message,MSG_CMSG_CLOEXEC)==1);
        struct cmsghdr *cmsg=CMSG_FIRSTHDR(&message);
        assert(cmsg && cmsg->cmsg_level==SOL_SOCKET && cmsg->cmsg_type==SCM_RIGHTS);
        memcpy(&fd,CMSG_DATA(cmsg),sizeof(fd));
        assert(fcntl(fd,F_GETFD)&FD_CLOEXEC);
        assert(!prctl(PR_SET_DUMPABLE,0));
        assert(!syscall(SYS_pidfd_send_signal,fd,SIGKILL,NULL,0));
        assert(!syscall(SYS_pidfd_send_signal,fd,SIGSTOP,NULL,0));
        close(fd); close(channel[1]); _exit(0);
    }
    close(channel[1]);
    struct cmsghdr *cmsg=CMSG_FIRSTHDR(&message);
    cmsg->cmsg_level=SOL_SOCKET; cmsg->cmsg_type=SCM_RIGHTS; cmsg->cmsg_len=CMSG_LEN(sizeof(fd));
    memcpy(CMSG_DATA(cmsg),&fd,sizeof(fd));
    assert(sendmsg(channel[0],&message,0)==1);
    close(fd); close(channel[0]); reap(child);
    puts("PASS transferred pidfd retains PID1 policy in protected receiver");
}
static void *socket_credential_sender(void *data) {
    int fd=*(int *)data;
    struct ucred peer={0}; socklen_t size=sizeof(peer);
    assert(syscall(SYS_gettid)!=getpid());
    assert(!getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&size));
    assert(size==sizeof(peer) && peer.pid==1 && peer.uid==getuid());
    char byte='c',control[CMSG_SPACE(sizeof(peer))]={0};
    struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    *header=(struct cmsghdr){.cmsg_level=SOL_SOCKET,.cmsg_type=SCM_CREDENTIALS,.cmsg_len=CMSG_LEN(sizeof(peer))};
    peer=(struct ucred){getpid(),getuid(),getgid()};
    memcpy(CMSG_DATA(header),&peer,sizeof(peer));
    assert(sendmsg(fd,&message,0)==1);
    return NULL;
}
static void socket_credentials(void) {
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET,0,pair));
    int enabled=1;
    assert(!setsockopt(pair[0],SOL_SOCKET,SO_PASSCRED,&enabled,sizeof(enabled)));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        close(pair[0]);
        pthread_t thread;
        assert(!pthread_create(&thread,NULL,socket_credential_sender,&pair[1]));
        assert(!pthread_join(thread,NULL));
        close(pair[1]); _exit(0);
    }
    close(pair[1]); reap(child);
    char byte,control[CMSG_SPACE(sizeof(struct ucred))]={0};
    struct iovec data={&byte,1};
    struct msghdr message={.msg_iov=&data,.msg_iovlen=1,.msg_control=control,.msg_controllen=sizeof(control)};
    assert(recvmsg(pair[0],&message,0)==1 && byte=='c' && !(message.msg_flags&MSG_CTRUNC));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_CREDENTIALS);
    struct ucred peer; memcpy(&peer,CMSG_DATA(header),sizeof(peer));
    assert(peer.pid==child && peer.uid==getuid() && peer.gid==getgid());
    close(pair[0]);
    puts("PASS PID socket peer and queued SCM_CREDENTIALS after sender reaping");
}
static void *debug_exec_worker(void *unused) {
    (void)unused;
    execl("/bin/test","/bin/test","pid-namespace","debug-exec",NULL);
    _exit(91);
}
static void debugger_exec(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,NULL,NULL));
        raise(SIGSTOP);
        pthread_t thread; assert(!pthread_create(&thread,NULL,debug_exec_worker,NULL));
        pthread_exit(NULL);
    }
    int status; assert(waitpid(child,&status,0)==child && WIFSTOPPED(status));
    assert(!ptrace(PTRACE_SETOPTIONS,child,NULL,(void *)(PTRACE_O_TRACECLONE|PTRACE_O_TRACEEXEC)));
    assert(!ptrace(PTRACE_CONT,child,NULL,NULL));
    pid_t former=0; int exec_seen=0;
    for (;;) {
        pid_t pid=waitpid(-1,&status,__WALL); assert(pid>0);
        if (WIFEXITED(status)) {
            assert(!WEXITSTATUS(status));
            if (pid==child && exec_seen) break;
            continue;
        }
        assert(WIFSTOPPED(status));
        unsigned event=(unsigned)status>>16;
        if (event==PTRACE_EVENT_CLONE || event==PTRACE_EVENT_EXEC) {
            unsigned long message=0;
            assert(!ptrace(PTRACE_GETEVENTMSG,pid,NULL,&message));
            if (event==PTRACE_EVENT_CLONE) { former=(pid_t)message; assert(former>child); }
            else { assert(pid==child && former && message==(unsigned long)former); exec_seen=1; }
        }
        assert(!ptrace(PTRACE_CONT,pid,NULL,NULL));
    }
    assert(exec_seen);
    puts("PASS debugger nonleader exec reports namespace-visible former TID");
}
static int init(void *inherited) {
    printf("pid init self=%d tid=%ld parent=%d\n",getpid(),syscall(SYS_gettid),getppid());
    assert(getpid()==1 && syscall(SYS_gettid)==1 && getppid()==0);
    inherited_proc_status(2);
    struct ucred outside; socklen_t size=sizeof(outside);
    int fd=*(int *)inherited;
    assert(!getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&outside,&size) && !outside.pid);
    close(fd);
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(getpid()==2 && syscall(SYS_gettid)==2 && getppid()==1);
        assert(!kill(1,0));
        errno=0; assert(syscall(SYS_tgkill,2,1,SIGKILL)==-1 && errno==ESRCH);
        assert(!kill(1,SIGKILL) && !kill(1,SIGSTOP) && !kill(1,SIGTERM));
        int handle=syscall(SYS_pidfd_open,1,0); assert(handle>=0);
        int copy=dup(handle); assert(copy>=0); close(handle);
        assert(!prctl(PR_SET_DUMPABLE,0));
        assert(!syscall(SYS_pidfd_send_signal,copy,SIGKILL,NULL,0));
        assert(!syscall(SYS_pidfd_send_signal,copy,SIGSTOP,NULL,0));
        assert(!prctl(PR_SET_DUMPABLE,1)); close(copy);
        _exit(0);
    }
    assert(child==2); reap(child);
    proc_cases();
    wait_cases();
    io_priority_cases();
    orphan_cases();
    autoreap_case();
    signal_cases();
    pidfd_transfer();
    socket_credentials();
    debugger_exec();
    assert(!unshare(CLONE_NEWPID));
    assert(getpid()==1 && getppid()==0);
    char path[64];
    errno=0;
    assert(readlink("/tmp/pid-proc/self/ns/pid_for_children",path,sizeof(path))==-1 && errno==ENOENT);
    child=fork(); assert(child>=0);
    if (!child) { assert(getpid()==1 && getppid()==0); inherited_proc_status(3); _exit(0); }
    assert(child>3); reap(child);
    return 0;
}
static void *exec_worker(void *unused) {
    (void)unused;
    assert(getpid()==1 && syscall(SYS_gettid)>1);
    wait_init_leader_exit();
    char *args[]={"/bin/test","pid-namespace","exec",NULL};
    execv(args[0],args); perror("PID init nonleader exec"); _exit(90);
}
static int threaded_init(void *unused) {
    (void)unused;
    pthread_t thread; assert(!pthread_create(&thread,NULL,exec_worker,NULL));
    pthread_exit(NULL);
}
static int sibling_init(void *unused) {
    (void)unused;
    assert(getpid()==1 && getppid()==0);
    errno=0;
    assert(syscall(SYS_clone,CLONE_PARENT|SIGCHLD,0,NULL,NULL,0)==-1 && errno==EINVAL);
    return 23;
}
static void parent_namespace_case(void *stack) {
    int pair[2]; assert(!pipe(pair));
    pid_t helper=fork(); assert(helper>=0);
    if (!helper) {
        close(pair[0]);
        int flags=CLONE_PARENT|CLONE_NEWPID|CLONE_NEWUSER|CLONE_NEWNS
            |CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWNET|SIGCHLD;
        pid_t child=clone(sibling_init,(char *)stack+1024*1024,flags,NULL);
        assert(child>0);
        assert(write(pair[1],&child,sizeof(child))==sizeof(child));
        errno=0; assert(waitpid(child,NULL,0)==-1 && errno==ECHILD);
        _exit(0);
    }
    close(pair[1]);
    pid_t child; assert(read(pair[0],&child,sizeof(child))==sizeof(child)); close(pair[0]);
    reap(helper);
    int status; assert(waitpid(child,&status,0)==child);
    assert(WIFEXITED(status) && WEXITSTATUS(status)==23);
    puts("PASS CLONE_PARENT with new PID/user namespaces preserves parent wait and init restrictions");
}
int test_pid_namespace_guest(int argc,char **argv) {
    setbuf(stdout,NULL); alarm(30);
    if (argc==3 && !strcmp(argv[2],"cleanup")) {
        assert(!rmdir("/tmp/pid-proc") || errno==ENOENT); return 0;
    }
    if (argc==3 && !strcmp(argv[2],"zombie-signal-native")) {
        zombie_signal_case(0); return 0;
    }
    if (argc==3 && !strcmp(argv[2],"reaped-signal")) {
        reaped_signal_cases(); reaped_debugger_case(0); reaped_debugger_case(1); reaped_exec_case();
        pid_t unprivileged=fork(); assert(unprivileged>=0);
        if (!unprivileged) { assert(!setuid(1000)); reaped_signal_cases(); _exit(0); }
        reap(unprivileged); return 0;
    }
    if (argc==4 && !strcmp(argv[2],"reaped-exec")) {
        pid_t child=(pid_t)strtol(argv[3],NULL,10); assert(child>0);
        reaped_waiter(&child); return 0;
    }
    if (argc==3 && !strcmp(argv[2],"debug-exec")) {
        assert(getpid()>1 && syscall(SYS_gettid)==getpid());
        return 0;
    }
    if (argc==3 && !strcmp(argv[2],"exec")) {
        assert(getpid()==1 && syscall(SYS_gettid)==1);
        pid_t child=fork(); assert(child>=0);
        if (!child) _exit(0);
        reap(child);
        puts("PASS PID init nonleader exec preserves namespace admission");
        return 0;
    }
    void *stack=malloc(1024*1024); assert(stack);
    reboot_cases(stack);
    parent_namespace_case(stack);
    pid_t original=getpid();
    int pair[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET,0,pair));
    pid_t child=clone(init,(char *)stack+1024*1024,CLONE_NEWPID|SIGCHLD,&pair[0]);
    if (child<0) perror("clone PID namespace");
    assert(child>0); reap(child);
    close(pair[0]); close(pair[1]);
    child=clone(threaded_init,(char *)stack+1024*1024,CLONE_NEWPID|SIGCHLD,NULL);
    assert(child>0); reap(child);
    assert(getpid()==original);
    assert(!unshare(CLONE_NEWPID));
    child=fork(); assert(child>=0);
    if (!child) _exit(0);
    reap(child);
    errno=0; assert(fork()==-1 && errno==ENOMEM);
    free(stack);
    puts("PASS guest PID namespaces: init, parent, fork, wait and nested admission");
    return 0;
}
