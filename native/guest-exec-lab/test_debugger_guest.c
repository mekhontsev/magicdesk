#define _GNU_SOURCE
#include <assert.h>
#include <asm/ptrace.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <linux/ptrace.h>
#include <linux/perf_event.h>
#include <stdint.h>
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/shm.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

volatile int magic_value = 42;
static int stop(pid_t child) {
    int status;
    struct rusage usage;
    assert(wait4(child, &status, __WALL, &usage) == child);
    assert(WIFSTOPPED(status));
    return status;
}
static void exited(pid_t child, int code) {
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == code);
}
static int untraced_child(void *unused) {
    (void)unused;
    int fd=open("/etc/passwd",O_RDONLY); assert(fd>=0);
    char bytes[64]; assert(read(fd,bytes,sizeof(bytes))>0); assert(!close(fd));
    struct stat st; assert(!stat("/etc/passwd",&st) && st.st_size>0);
    return 42;
}
static void untraced_clone_test(void) {
    for (unsigned traced=0;traced<2;++traced) {
        pid_t parent=fork(); assert(parent>=0);
        if (!parent) {
            if (traced) { assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP); }
            size_t size=1024*1024;
            char *stack=malloc(size); assert(stack);
            for (unsigned shared=0;shared<2;++shared) for (unsigned signal=0;signal<2;++signal) {
                int flags=CLONE_UNTRACED|(shared ? CLONE_FS|CLONE_FILES : 0)|(signal ? SIGCHLD : 0);
                pid_t child=clone(untraced_child,stack+size,flags,NULL); assert(child>0);
                int status;
                /* EVENT_WAIT: child completion; fixture alarm bounds a missing birth/exit. */
                assert(waitpid(child,&status,signal ? 0 : __WCLONE)==child);
                assert(WIFEXITED(status) && WEXITSTATUS(status)==42);
            }
            free(stack); _exit(0);
        }
        if (!traced) { exited(parent,0); continue; }
        assert(WSTOPSIG(stop(parent))==SIGSTOP);
        assert(!ptrace(PTRACE_SETOPTIONS,parent,0,PTRACE_O_TRACEFORK|PTRACE_O_TRACECLONE));
        assert(!ptrace(PTRACE_CONT,parent,0,0));
        for (;;) {
            int status; assert(waitpid(parent,&status,0)==parent);
            if (WIFEXITED(status)) { assert(!WEXITSTATUS(status)); break; }
            assert(WIFSTOPPED(status) && !(status>>16) && WSTOPSIG(status)==SIGCHLD);
            assert(!ptrace(PTRACE_CONT,parent,0,SIGCHLD));
        }
    }
}
static void attach_test(int seize, int trace_exit) {
    int ready[2], release[2]; assert(!pipe(ready) && !pipe(release));
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        close(ready[0]); close(release[1]);
        assert(write(ready[1], "r", 1) == 1);
        char c; assert(read(release[0], &c, 1) == 1); _exit(7);
    }
    close(ready[1]); close(release[0]);
    char c; assert(read(ready[0], &c, 1) == 1); close(ready[0]);
    if (seize) {
        assert(!ptrace(PTRACE_SEIZE, child, 0, PTRACE_O_TRACEEXIT));
        assert(!ptrace(PTRACE_INTERRUPT, child, 0, 0));
        siginfo_t info = {0};
        assert(!waitid(P_PID, child, &info, WSTOPPED | WNOWAIT));
        assert(info.si_pid == child && info.si_code == CLD_TRAPPED);
        assert(info.si_status == (SIGTRAP | PTRACE_EVENT_STOP << 8));
        assert(!waitid(P_PID, child, &info, WSTOPPED | WNOWAIT));
    } else assert(!ptrace(PTRACE_ATTACH, child, 0, 0));
    int status = stop(child);
    assert(WSTOPSIG(status) == (seize ? SIGTRAP : SIGSTOP));
    if (seize) assert((unsigned)status >> 16 == PTRACE_EVENT_STOP);
    unsigned long registers[34]; struct iovec iov = {registers, sizeof(registers)};
    assert(!ptrace(PTRACE_GETREGSET, child, NT_PRSTATUS, &iov));
    if (trace_exit) {
        assert(!ptrace(PTRACE_CONT, child, 0, 0));
        assert(write(release[1], "x", 1) == 1);
        status = stop(child); assert((unsigned)status >> 16 == PTRACE_EVENT_EXIT);
        siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, child, 0, &info));
        assert(info.si_signo == SIGTRAP && info.si_code == (SIGTRAP | PTRACE_EVENT_EXIT << 8));
        unsigned long message; assert(!ptrace(PTRACE_GETEVENTMSG, child, 0, &message));
        assert(message == (7 << 8));
        assert(!ptrace(PTRACE_CONT, child, 0, 0));
    } else {
        assert(!ptrace(PTRACE_DETACH, child, 0, 0));
        assert(write(release[1], "x", 1) == 1);
    }
    close(release[1]); exited(child, 7);
}
static int shared_files_target(void *argument) {
    int *fds=argument;
    assert(write(fds[1],"r",1)==1);
    char byte;
    assert(read(fds[2],&byte,1)==1 && byte=='x');
    return 7;
}
static void shared_files_debugger_test(void) {
    int fds[4]; assert(!pipe(fds) && !pipe(fds+2));
    enum { COUNT=2, STACK=1024*1024 };
    pid_t children[COUNT]; char *stacks[COUNT];
    struct user_pt_regs before[COUNT],after;
    siginfo_t signals[COUNT],signal;
    uint64_t masks[COUNT],mask;
    for (int i=0;i<COUNT;++i) {
        stacks[i]=malloc(STACK); assert(stacks[i]);
        children[i]=clone(shared_files_target,stacks[i]+STACK,CLONE_FILES|SIGCHLD,fds);
        assert(children[i]>0);
        char byte; assert(read(fds[0],&byte,1)==1);
        assert(!ptrace(PTRACE_ATTACH,children[i],0,0));
        assert(WSTOPSIG(stop(children[i]))==SIGSTOP);
        struct iovec io={&before[i],sizeof(before[i])};
        assert(!ptrace(PTRACE_GETREGSET,children[i],NT_PRSTATUS,&io));
        assert(!ptrace(PTRACE_GETSIGINFO,children[i],0,&signals[i]));
        assert(!ptrace(PTRACE_GETSIGMASK,children[i],sizeof(mask),&masks[i]));
    }
    /* Publishing a virtual FD must cover every CLONE_FILES holder, even when
     * this exact debugger owns all of their stops. No inferior may run user code. */
    int file=open("/proc/self/status",O_RDONLY); assert(file>=0);
    char data[256]; assert(read(file,data,sizeof(data))>0);
    int watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); assert(watch>=0);
    assert(inotify_add_watch(watch,".",IN_CREATE)>=0);
    assert(!close(watch) && !close(file));
    for (int i=0;i<COUNT;++i) {
        struct iovec io={&after,sizeof(after)};
        assert(!ptrace(PTRACE_GETREGSET,children[i],NT_PRSTATUS,&io));
        assert(!memcmp(&before[i],&after,sizeof(after)));
        assert(!ptrace(PTRACE_GETSIGINFO,children[i],0,&signal));
        assert(!memcmp(&signals[i],&signal,sizeof(signal)));
        assert(!ptrace(PTRACE_GETSIGMASK,children[i],sizeof(mask),&mask) && mask==masks[i]);
        int status; assert(waitpid(children[i],&status,__WALL|WNOHANG)==0);
        assert(!ptrace(PTRACE_DETACH,children[i],0,0));
        assert(write(fds[3],"x",1)==1);
        exited(children[i],7); free(stacks[i]);
    }
    for (int i=0;i<4;++i) assert(!close(fds[i]));
}
static long stat_with_x7(const char *path, struct stat *st, unsigned long scratch) {
    register long x0 __asm__("x0")=AT_FDCWD;
    register const char *x1 __asm__("x1")=path;
    register struct stat *x2 __asm__("x2")=st;
    register long x3 __asm__("x3")=0;
    register unsigned long x7 __asm__("x7")=scratch;
    register long x8 __asm__("x8")=SYS_newfstatat;
    __asm__ volatile("svc #0" : "+r"(x0), "+r"(x7)
        : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory", "cc");
    return x0;
}
static void syscall_test(void) {
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        assert(syscall(SYS_getpid) == getpid());
        int fd = open("/etc/passwd", O_RDONLY); assert(fd >= 0);
        char bytes[16]; assert(read(fd, bytes, sizeof(bytes)) > 0);
        int copy=syscall(SYS_dup,fd); assert(copy>=0);
        assert(syscall(SYS_dup3,fd,copy,O_CLOEXEC)==copy);
        assert(!fcntl(copy,F_SETFD,0));
        int high=fcntl(copy,F_DUPFD_CLOEXEC,128); assert(high>=128);
        assert(!syscall(SYS_close_range,high,high,0));
        assert(!close(copy) && !close(fd));
        /* An unrelated application register cannot select adapter policy,
         * including after a notification is cancelled at a syscall-exit stop. */
        const unsigned long scratch[]={0,1,2,4,8,15,~0UL};
        const char *paths[]={"/proc","/dev/null","/etc/passwd"};
        for (unsigned p=0;p<sizeof(paths)/sizeof(paths[0]);++p) {
            struct stat expected; assert(!stat(paths[p],&expected));
            for (unsigned i=0;i<sizeof(scratch)/sizeof(scratch[0]);++i) {
                struct stat actual;
                long value=stat_with_x7(paths[p],&actual,scratch[i]);
                if (value) fprintf(stderr,"stat %s x7=%#lx result=%ld\n",paths[p],scratch[i],value);
                assert(!value && actual.st_dev==expected.st_dev && actual.st_ino==expected.st_ino
                    && actual.st_mode==expected.st_mode);
                assert(stat_with_x7("/proc/md-absent-entry",&actual,scratch[i])==-ENOENT);
            }
        }
        _exit(9);
    }
    stop(child);
    assert(!ptrace(PTRACE_SETOPTIONS, child, 0, PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACEEXIT));
    int entries = 0, exits = 0, opening = 0, exiting = 0, status;
    int closing=0, duplicating=0, replacing=0, controlling=0, ranging=0;
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL, child, 0, 0));
        assert(waitpid(child, &status, __WALL) == child);
        if (WIFEXITED(status)) { assert(WEXITSTATUS(status) == 9); break; }
        assert(WIFSTOPPED(status));
        if ((unsigned)status >> 16 == PTRACE_EVENT_EXIT) { exiting++; continue; }
        assert(WSTOPSIG(status) == (SIGTRAP | 0x80));
        struct ptrace_syscall_info info = {0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO, child, sizeof(info), &info) > 0);
        if (info.op == PTRACE_SYSCALL_INFO_ENTRY) {
            entries++; if (info.entry.nr == SYS_openat) opening++;
            closing+=info.entry.nr==SYS_close;
            duplicating+=info.entry.nr==SYS_dup;
            replacing+=info.entry.nr==SYS_dup3;
            controlling+=info.entry.nr==SYS_fcntl;
            ranging+=info.entry.nr==SYS_close_range;
        } else { assert(info.op == PTRACE_SYSCALL_INFO_EXIT); exits++; }
    }
    assert(entries >= 6 && exits == entries - 1 && opening == 1 && exiting == 1);
    assert(closing==2 && duplicating==1 && replacing==1 && controlling==2 && ranging==1);
}
static void syscall_edit_close_test(void) {
    for (int detach=0;detach<2;++detach) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            int watched=(int)syscall(SYS_inotify_init1,O_NONBLOCK|O_CLOEXEC);
            int file=open("/etc/passwd",O_RDONLY|O_CLOEXEC);
            assert(watched>=0 && file>=0);
            char path[64],before[128],after[128];
            snprintf(path,sizeof(path),"/proc/self/fd/%d",watched);
            ssize_t size=readlink(path,before,sizeof(before)); assert(size>0);
            assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
            assert(!syscall(SYS_close,watched,file));
            assert(fcntl(file,F_GETFD)==-1 && errno==EBADF);
            assert(fcntl(watched,F_GETFD)==FD_CLOEXEC);
            assert(readlink(path,after,sizeof(after))==size && !memcmp(before,after,(size_t)size));
            assert(!close(watched)); _exit(0);
        }
        stop(child);
        assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACESYSGOOD));
        int edited=0;
        /* EVENT_WAIT: exact syscall/exit stops; the runner bounds failed progress. */
        for (;;) {
            assert(!ptrace(PTRACE_SYSCALL,child,0,0));
            int status; assert(waitpid(child,&status,__WALL)==child);
            if (WIFEXITED(status)) { assert(!WEXITSTATUS(status) && edited); break; }
            assert(WIFSTOPPED(status) && WSTOPSIG(status)==(SIGTRAP|0x80));
            struct ptrace_syscall_info info={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,child,sizeof(info),&info)>0);
            if (!edited && info.op==PTRACE_SYSCALL_INFO_ENTRY && info.entry.nr==SYS_close) {
                struct user_pt_regs regs;
                struct iovec io={&regs,sizeof(regs)};
                assert(!ptrace(PTRACE_GETREGSET,child,NT_PRSTATUS,&io));
                assert(regs.regs[0]!=regs.regs[1]);
                regs.regs[0]=regs.regs[1];
                assert(!ptrace(PTRACE_SETREGSET,child,NT_PRSTATUS,&io));
                edited=1;
                if (detach) {
                    assert(!ptrace(PTRACE_DETACH,child,0,0)); exited(child,0); break;
                }
            }
        }
    }
}
static void credential_transition_test(void) {
    assert(!getuid());
    for (unsigned detach=0;detach<2;++detach) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
            assert(!setresgid(81,81,81));
            assert(!setresuid(81,81,81));
            assert(getuid()==81 && getgid()==81);
            raise(SIGSTOP);
            _exit(7);
        }
        assert(WSTOPSIG(stop(child))==SIGSTOP);
        assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACESYSGOOD));
        unsigned changed=0,entry=0;
        long number=-1;
        for (;;) {
            assert(!ptrace(PTRACE_SYSCALL,child,0,0));
            int status=stop(child);
            if (WSTOPSIG(status)==SIGSTOP) break;
            assert(WSTOPSIG(status)==(SIGTRAP|0x80));
            struct ptrace_syscall_info info={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,child,sizeof(info),&info)>0);
            if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                assert(!entry); entry=1; number=(long)info.entry.nr;
            } else {
                assert(entry && info.op==PTRACE_SYSCALL_INFO_EXIT); entry=0;
                if (number==SYS_setresgid || number==SYS_setresuid) {
                    assert(!info.exit.rval); ++changed;
                }
            }
        }
        assert(changed==2);
        struct user_pt_regs regs;
        struct iovec io={&regs,sizeof(regs)};
        assert(!ptrace(PTRACE_GETREGSET,child,NT_PRSTATUS,&io));
        assert(!ptrace(PTRACE_SETOPTIONS,child,0,0));
        assert(!ptrace(detach ? PTRACE_DETACH : PTRACE_CONT,child,0,0));
        exited(child,7);
    }
}
static void mixed_test(void) {
    int release[2]; assert(!pipe(release));
    pid_t traced = fork(); assert(traced >= 0);
    if (!traced) {
        close(release[1]); assert(!ptrace(PTRACE_TRACEME, 0, 0, 0)); raise(SIGSTOP);
        char c; assert(read(release[0], &c, 1) == 1); _exit(3);
    }
    close(release[0]); stop(traced);
    assert(!ptrace(PTRACE_CONT, traced, 0, 0));
    pid_t plain = fork(); assert(plain >= 0);
    if (!plain) _exit(4);
    int status; assert(waitpid(-1, &status, 0) == plain);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 4);
    assert(write(release[1], "x", 1) == 1); close(release[1]); exited(traced, 3);
}
static void syscall_exec_test(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        char *args[]={"/bin/true",NULL};
        syscall(SYS_execve,args[0],args,environ); _exit(99);
    }
    stop(child);
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACESYSGOOD|PTRACE_O_TRACEEXEC));
    int active=0, execs=0, entries=0, exits=0;
    uint64_t nr=0;
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,child,0,0));
        int status;
        /* EVENT_WAIT: matching syscall/exec/exit event; fixture alarm bounds failure. */
        assert(waitpid(child,&status,__WALL)==child);
        if (WIFEXITED(status)) { assert(!WEXITSTATUS(status)); break; }
        assert(WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_EXEC) {
            assert(active && nr==SYS_execve && !execs);
            unsigned long former; assert(!ptrace(PTRACE_GETEVENTMSG,child,0,&former));
            assert(former==(unsigned long)child); ++execs; continue;
        }
        assert(WSTOPSIG(status)==(SIGTRAP|0x80));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,child,sizeof(info),&info)>0);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
            assert(!active); active=1; ++entries; nr=info.entry.nr;
        } else {
            assert(info.op==PTRACE_SYSCALL_INFO_EXIT && active);
            if (nr==SYS_execve) assert(execs==1 && !info.exit.rval && !info.exit.is_error);
            active=0; ++exits;
        }
    }
    assert(execs==1 && active && nr==SYS_exit_group && entries==exits+1);
}
static void child_signal(int signal) { (void)signal; syscall(SYS_getpid); }
static void syscall_signal_test(void) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        struct sigaction action={.sa_handler=child_signal,.sa_flags=SA_RESTART};
        assert(!sigaction(SIGUSR1,&action,NULL));
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        for (unsigned i=0;i<32;++i) {
            struct stat st;
            assert(!syscall(SYS_newfstatat,AT_FDCWD,"/dev/null",&st,0));
        }
        _exit(0);
    }
    stop(child);
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACESYSGOOD));
    int active=0, deliver=0, signals=0;
    uint64_t nr=0;
    for (;;) {
        assert(!ptrace(PTRACE_SYSCALL,child,0,deliver)); deliver=0;
        int status;
        /* EVENT_WAIT: exact syscall/signal stop; the fixture alarm bounds it. */
        assert(waitpid(child,&status,__WALL)==child);
        if (WIFEXITED(status)) { assert(!WEXITSTATUS(status)); break; }
        assert(WIFSTOPPED(status));
        if (WSTOPSIG(status)==SIGUSR1) { deliver=SIGUSR1; ++signals; continue; }
        assert(WSTOPSIG(status)==(SIGTRAP|0x80));
        struct ptrace_syscall_info info={0};
        assert(ptrace(PTRACE_GET_SYSCALL_INFO,child,sizeof(info),&info)>0);
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
            if (active) fprintf(stderr,"duplicate syscall entry old=%llu new=%llu\n",
                                (unsigned long long)nr,(unsigned long long)info.entry.nr);
            assert(!active); active=1; nr=info.entry.nr;
            if (nr==SYS_newfstatat) assert(!kill(child,SIGUSR1));
        } else {
            if (!active) fprintf(stderr,"unmatched syscall exit old=%llu value=%lld\n",
                                 (unsigned long long)nr,(long long)info.exit.rval);
            assert(info.op==PTRACE_SYSCALL_INFO_EXIT && active); active=0;
        }
    }
    assert(signals==32 && active && nr==SYS_exit_group);
}
static void kill_stopped_test(void) {
    struct sigaction action={.sa_handler=child_signal,.sa_flags=SA_RESTART};
    assert(!sigaction(SIGCHLD,&action,NULL));
    for (unsigned i=0;i<50;i++) {
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP); _exit(55);
        }
        stop(child); assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_EXITKILL));
        assert(!kill(child,SIGKILL));
        int status=-1; pid_t reaped=waitpid(child,&status,0);
        if (reaped!=child || !WIFSIGNALED(status) || WTERMSIG(status)!=SIGKILL)
            fprintf(stderr,"kill-stopped iteration=%u pid=%d reaped=%d status=%#x errno=%d\n",i,child,reaped,status,errno);
        assert(reaped==child && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    }
}
static void owner_test(int exitkill) {
    int done[2]; assert(!pipe(done));
    pid_t owner=fork(); assert(owner>=0);
    if (!owner) {
        close(done[0]);
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
            assert(write(done[1],"r",1)==1); _exit(0);
        }
        stop(child);
        if (exitkill) assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_EXITKILL));
        _exit(0);
    }
    close(done[1]); exited(owner,0);
    char c; assert(read(done[0],&c,1)==(exitkill ? 0 : 1)); close(done[0]);
}
static void *exec_worker(void *unused) {
    (void)unused;
    execl("/bin/true","true",(char *)0); _exit(99);
}
static void exec_thread_test(int syscalls) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!ptrace(PTRACE_TRACEME,0,0,0)); raise(SIGSTOP);
        pthread_t worker; assert(!pthread_create(&worker,NULL,exec_worker,NULL));
        for (;;) pause();
    }
    stop(child);
    assert(!ptrace(PTRACE_SETOPTIONS,child,0,PTRACE_O_TRACECLONE|PTRACE_O_TRACEEXEC
        |(syscalls ? PTRACE_O_TRACESYSGOOD : 0)));
    int request=syscalls ? PTRACE_SYSCALL : PTRACE_CONT;
    assert(!ptrace(request,child,0,0));
    int execs=0, status, active=0, entries=0, exits=0;
    uint64_t nr=0;
    pid_t executing=0;
    for (;;) {
        pid_t pid=waitpid(-1,&status,__WALL);
        assert(pid>0);
        if (WIFEXITED(status)) {
            assert(!WEXITSTATUS(status)); if (pid==child) break; continue;
        }
        assert(WIFSTOPPED(status));
        if ((unsigned)status>>16==PTRACE_EVENT_EXEC) {
            unsigned long former; assert(!ptrace(PTRACE_GETEVENTMSG,pid,0,&former));
            assert(pid==child && former!=(unsigned long)pid); execs++;
            if (syscalls) { assert(former==(unsigned long)executing); active=1; nr=SYS_execve; }
        } else if (syscalls && WSTOPSIG(status)==(SIGTRAP|0x80)) {
            struct ptrace_syscall_info info={0};
            assert(ptrace(PTRACE_GET_SYSCALL_INFO,pid,sizeof(info),&info)>0);
            if (!execs) {
                if (info.op==PTRACE_SYSCALL_INFO_ENTRY && info.entry.nr==SYS_execve) executing=pid;
            } else if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
                assert(!active); active=1; nr=info.entry.nr; ++entries;
            } else {
                assert(info.op==PTRACE_SYSCALL_INFO_EXIT && active);
                if (nr==SYS_execve) assert(!info.exit.rval && !info.exit.is_error);
                active=0; ++exits;
            }
        }
        assert(!ptrace(request,pid,0,0));
    }
    assert(execs==1);
    if (syscalls) assert(active && nr==SYS_exit_group && entries==exits);
}
static int perf_test(void) {
    struct perf_event_attr attr={.type=PERF_TYPE_SOFTWARE,.size=sizeof(attr),
        .config=PERF_COUNT_SW_TASK_CLOCK,.disabled=1,.exclude_kernel=1,.exclude_hv=1};
    int fd=syscall(SYS_perf_event_open,&attr,0,-1,-1,PERF_FLAG_FD_CLOEXEC);
    if (fd<0 && (errno==EPERM || errno==EACCES || errno==ENOSYS || errno==EOPNOTSUPP)) {
        printf("UNAVAILABLE perf-event errno=%d (%s)\n",errno,strerror(errno)); return 0;
    }
    assert(fd>=0);
    assert(!ioctl(fd,PERF_EVENT_IOC_ENABLE,0));
    volatile unsigned long work=0;
    for (unsigned i=0;i<1000000;i++) work+=i;
    assert(!ioctl(fd,PERF_EVENT_IOC_DISABLE,0));
    uint64_t count=0; assert(read(fd,&count,sizeof(count))==sizeof(count) && count>0);
    close(fd); printf("task-clock=%llu work=%lu\n",(unsigned long long)count,work);
    return 1;
}
static pthread_barrier_t ready_barrier;
static pthread_mutex_t workers_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t workers_done = PTHREAD_COND_INITIALIZER;
static int workers_exit;
static void *gdb_worker(void *unused) {
    (void)unused;
    pthread_barrier_wait(&ready_barrier);
    assert(!pthread_mutex_lock(&workers_lock));
    // EVENT_WAIT: explicit target release; the fixture alarm bounds debugger failures.
    while (!workers_exit) assert(!pthread_cond_wait(&workers_done, &workers_lock));
    assert(!pthread_mutex_unlock(&workers_lock));
    return NULL;
}
static void gdb_target(int threaded, int forks) {
    pthread_t workers[40];
    if (threaded) {
        assert(!pthread_barrier_init(&ready_barrier, NULL, 41));
        for (unsigned i = 0; i < 40; i++)
            assert(!pthread_create(&workers[i], NULL, gdb_worker, NULL));
        pthread_barrier_wait(&ready_barrier);
    }
    puts("READY"); fflush(stdout); char c;
    assert(read(0, &c, 1) == 1); assert(magic_value == 43);
    for (unsigned i = 0; i < (forks ? 4u : 0u); ++i) {
        pid_t child = fork(); assert(child >= 0);
        if (!child) {
            execl("/bin/true", "true", NULL);
            _exit(127);
        }
        exited(child, 0);
    }
    if (threaded) {
        assert(!pthread_mutex_lock(&workers_lock));
        workers_exit = 1;
        assert(!pthread_cond_broadcast(&workers_done));
        assert(!pthread_mutex_unlock(&workers_lock));
        for (unsigned i = 0; i < 40; i++) assert(!pthread_join(workers[i], NULL));
        assert(!pthread_barrier_destroy(&ready_barrier));
    }
}
static pid_t dump_threads[41];
static int dump_active_io;
static void *gdb_io_worker(void *argument);
static atomic_int io_exit;
static atomic_uint io_calls;
static char io_directory[]="/tmp/md-debugger-io.XXXXXX";
static void *dump_worker(void *argument) {
    size_t index=(size_t)argument;
    dump_threads[index]=(pid_t)syscall(SYS_gettid);
    return dump_active_io ? gdb_io_worker(argument) : gdb_worker(NULL);
}
static int dump_collector(void *unused) {
    (void)unused;
    for (unsigned i=0;i<41;++i) {
        assert(!ptrace(PTRACE_ATTACH,dump_threads[i],NULL,NULL));
        /* EVENT_WAIT: each attach publishes its own stop, including a parent
         * waiting for this collector. The fixture alarm bounds missing events. */
        assert(waitpid(dump_threads[i],NULL,__WALL)==dump_threads[i]);
    }
    for (unsigned i=0;i<41;++i) {
        struct user_pt_regs regs;
        struct iovec io={&regs,sizeof(regs)};
        assert(!ptrace(PTRACE_GETREGSET,dump_threads[i],NT_PRSTATUS,&io));
        assert(io.iov_len==sizeof(regs));
        assert(!ptrace(PTRACE_DETACH,dump_threads[i],NULL,NULL));
    }
    return 0;
}
static void self_dump_threads(int active_io) {
    pthread_t workers[40];
    dump_active_io=active_io;
    if (active_io) assert(mkdtemp(io_directory));
    dump_threads[0]=getpid();
    assert(!pthread_barrier_init(&ready_barrier,NULL,41));
    for (size_t i=0;i<40;++i) assert(!pthread_create(&workers[i],NULL,dump_worker,(void *)(i+1)));
    pthread_barrier_wait(&ready_barrier);
    size_t size=1024*1024;
    char *stack=malloc(size); assert(stack);
    for (unsigned shared=0;shared<2;++shared) for (unsigned round=0;round<8;++round) {
        int flags=SIGCHLD|CLONE_UNTRACED|(shared ? CLONE_VM|CLONE_FS|CLONE_FILES : 0);
        pid_t child=clone(dump_collector,stack+size,flags,NULL); assert(child>0);
        exited(child,0);
    }
    free(stack);
    atomic_store(&io_exit,1);
    assert(!pthread_mutex_lock(&workers_lock));
    workers_exit=1;
    assert(!pthread_cond_broadcast(&workers_done));
    assert(!pthread_mutex_unlock(&workers_lock));
    for (unsigned i=0;i<40;++i) assert(!pthread_join(workers[i],NULL));
    assert(!pthread_barrier_destroy(&ready_barrier));
    if (active_io) {
        assert(atomic_load(&io_calls)>0);
        assert(!rmdir(io_directory));
    }
}
static void gdb_poll_target(void) {
    struct pollfd input = {.fd = STDIN_FILENO, .events = POLLIN};
    struct timespec zero = {0};
    for (unsigned i = 0; i < 64; ++i) assert(ppoll(&input, 1, &zero, NULL) == 0);
    puts("READY"); fflush(stdout);
    /* EVENT_WAIT: the debugger releases stdin after inspecting the suspended
     * caller. Warm the same libc entry before its blocking invocation. */
    int result;
    do result = ppoll(&input, 1, NULL, NULL); while (result < 0 && errno == EINTR);
    assert(result == 1 && (input.revents & POLLIN));
    char c;
    assert(read(STDIN_FILENO, &c, 1) == 1 && magic_value == 43);
}
static void *gdb_io_worker(void *argument) {
    char path[128], renamed[128];
    snprintf(path,sizeof(path),"%s/%zu",io_directory,(size_t)argument);
    snprintf(renamed,sizeof(renamed),"%s/%zu.done",io_directory,(size_t)argument);
    pthread_barrier_wait(&ready_barrier);
    while (!atomic_load(&io_exit)) {
        assert(!access("/etc/passwd", R_OK));
        int fd=open(path,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0700);
        if (fd<0) perror("gdb-active-io create");
        assert(fd>=0);
        assert(pwrite(fd,"fixture",7,4096)==7);
        char bytes[7];
        assert(pread(fd,bytes,sizeof(bytes),4096)==sizeof(bytes));
        assert(!memcmp(bytes,"fixture",sizeof(bytes)));
        assert(!close(fd));
        fd=open(path,O_WRONLY|O_NOATIME|O_CLOEXEC);
        if (fd<0) perror("gdb-active-io reopen");
        assert(fd>=0 && !close(fd));
        assert(!rename(path,renamed) && !unlink(renamed));
        atomic_fetch_add(&io_calls, 1);
    }
    return NULL;
}
__attribute__((noinline)) static void io_marker(unsigned iteration) {
    assert(iteration < 16);
}
static void gdb_io_target(void) {
    pthread_t workers[8];
    assert(mkdtemp(io_directory));
    assert(!pthread_barrier_init(&ready_barrier, NULL, 9));
    for (size_t i=0;i<8;++i) assert(!pthread_create(&workers[i], NULL, gdb_io_worker, (void *)i));
    pthread_barrier_wait(&ready_barrier);
    puts("READY"); fflush(stdout);
    char c;
    /* EVENT_WAIT: debugger attachment permits each breakpoint; alarm bounds failure. */
    for (unsigned i=0;i<16;++i) { assert(read(0,&c,1)==1); io_marker(i); }
    atomic_store(&io_exit,1);
    for (unsigned i=0;i<8;++i) assert(!pthread_join(workers[i],NULL));
    assert(atomic_load(&io_calls)>0);
    assert(!pthread_barrier_destroy(&ready_barrier));
    assert(!rmdir(io_directory));
}
__attribute__((noinline)) static void shm_marker(char *address) {
    assert(*address=='S');
}
static void shm_target(void) {
    int id=shmget(IPC_PRIVATE,4096,0600); assert(id>=0);
    for (unsigned n=0;n<4;++n) {
        char *address=shmat(id,NULL,0); assert(address!=(void *)-1);
        *address='S'; shm_marker(address);
        assert(!shmdt(address));
    }
    struct shmid_ds info;
    assert(!shmctl(id,IPC_STAT,&info) && !info.shm_nattch && !shmctl(id,IPC_RMID,NULL));
}
__attribute__((noinline)) static void jit_marker(uint32_t *address, unsigned value) {
    assert(address[0]==(0x52800000u|(value<<5)));
}
static void jit_target(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    int fd=memfd_create("debugger-jit",MFD_CLOEXEC);
    assert(fd>=0 && !ftruncate(fd,page));
    uint32_t *address=mmap(NULL,page,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    assert(address!=MAP_FAILED && !close(fd));
    for (unsigned value=37;value<41;++value) {
        if (value!=37) assert(!mprotect(address,page,PROT_READ|PROT_WRITE));
        /* AArch64 mov w0, #value; ret. No runtime/preload entry points. */
        address[0]=0x52800000u|(value<<5); address[1]=0xd65f03c0u;
        __builtin___clear_cache((char *)address,(char *)(address+2));
        assert(!mprotect(address,page,PROT_READ|PROT_EXEC));
        jit_marker(address,value);
        int (*run)(void)=(void *)address;
        assert(run()==(int)value);
    }
    assert(!munmap(address,page));
}
int main(int argc, char **argv) {
    assert(argc == 2); alarm(20);
    if (!strcmp(argv[1], "attach")) attach_test(0, 0);
    else if (!strcmp(argv[1], "seize")) attach_test(1, 0);
    else if (!strcmp(argv[1], "trace-exit")) attach_test(1, 1);
    else if (!strcmp(argv[1], "syscall")) syscall_test();
    else if (!strcmp(argv[1], "syscall-edit-close")) syscall_edit_close_test();
    else if (!strcmp(argv[1], "credential-transition")) credential_transition_test();
    else if (!strcmp(argv[1], "syscall-exec")) syscall_exec_test();
    else if (!strcmp(argv[1], "syscall-signal")) syscall_signal_test();
    else if (!strcmp(argv[1], "mixed")) mixed_test();
    else if (!strcmp(argv[1], "kill-stopped")) kill_stopped_test();
    else if (!strcmp(argv[1], "owner-exit")) owner_test(0);
    else if (!strcmp(argv[1], "exitkill")) owner_test(1);
    else if (!strcmp(argv[1], "thread-exec")) exec_thread_test(0);
    else if (!strcmp(argv[1], "syscall-thread-exec")) exec_thread_test(1);
    else if (!strcmp(argv[1], "clone-untraced")) untraced_clone_test();
    else if (!strcmp(argv[1], "shared-files-debugger")) shared_files_debugger_test();
    else if (!strcmp(argv[1], "perf-event")) { if (!perf_test()) return 0; }
    else if (!strcmp(argv[1], "gdb-target")) gdb_target(0, 0);
    else if (!strcmp(argv[1], "gdb-poll-target")) gdb_poll_target();
    else if (!strcmp(argv[1], "gdb-thread-target")) gdb_target(1, 0);
    else if (!strcmp(argv[1], "self-dump-threads")) self_dump_threads(0);
    else if (!strcmp(argv[1], "self-dump-io")) self_dump_threads(1);
    else if (!strcmp(argv[1], "gdb-fork-target")) gdb_target(1, 1);
    else if (!strcmp(argv[1], "strace-target")) { magic_value=43; gdb_target(1, 0); }
    else if (!strcmp(argv[1], "gdb-io-target")) gdb_io_target();
    else if (!strcmp(argv[1], "shm-target")) shm_target();
    else if (!strcmp(argv[1], "jit-target")) jit_target();
    else return 2;
    printf("PASS %s\n", argv[1]); return 0;
}
