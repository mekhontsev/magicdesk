#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <linux/openat2.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char **environ;
static int exec_shared_child(void *context) {
    execve("/proc/self/exe",context,environ);
    _exit(99);
}
static void exec_access(void) {
    for (int dumpable=0;dumpable<2;++dumpable) {
        int ready[2],finish[2]; assert(!pipe(ready) && !pipe(finish));
        char output[32],input[32];
        snprintf(output,sizeof(output),"%d",ready[1]);
        snprintf(input,sizeof(input),"%d",finish[0]);
        char *args[]={"test","proc-exec","access-child",output,input,NULL};
        size_t size=65536;
        void *stack=malloc(size); assert(stack);
        assert(!prctl(PR_SET_DUMPABLE,dumpable));
        pid_t child=clone(exec_shared_child,(char *)stack+size,CLONE_VM|CLONE_VFORK|SIGCHLD,args);
        assert(child>0);
        close(ready[1]); close(finish[0]);
        char byte;
        /* EVENT_WAIT: the exec child acknowledges its new mm, then changes
         * its own access state; neither operation may alter the parent's mm. */
        for (unsigned step=0;step<2;++step) {
            assert(read(ready[0],&byte,1)==1);
            assert(prctl(PR_GET_DUMPABLE)==dumpable);
            assert(write(finish[1],"!",1)==1);
        }
        int status; assert(waitpid(child,&status,0)==child && !status);
        close(ready[0]); close(finish[1]); free(stack);
    }
    assert(!prctl(PR_SET_DUMPABLE,1));
    puts("PASS vfork exec: independent mm access state and unchanged parent");
}
static void image_metadata(const char *proc) {
    pid_t parent=getpid(), child=fork(); assert(child>=0);
    if (!child) {
        const char *names[]={"cmdline","auxv"};
        for (unsigned i=0;i<sizeof(names)/sizeof(*names);++i) {
            char path[256],bytes[64];
            snprintf(path,sizeof(path),"%s/%d/%s",proc,parent,names[i]);
            struct stat st,link,opened;
            assert(!stat(path,&st) && S_ISREG(st.st_mode));
            assert(!lstat(path,&link) && link.st_ino==st.st_ino);
            struct statx sx;
            assert(!syscall(SYS_statx,AT_FDCWD,path,0,STATX_BASIC_STATS,&sx));
            assert(sx.stx_ino==st.st_ino && sx.stx_mode==st.st_mode);
            assert(!faccessat(AT_FDCWD,path,F_OK,0));
            assert(!faccessat(AT_FDCWD,path,R_OK,0));
            int fd=open(path,O_PATH|O_CLOEXEC); assert(fd>=0);
            assert(!fstat(fd,&opened) && opened.st_ino==st.st_ino);
            errno=0; assert(read(fd,bytes,sizeof(bytes))==-1 && errno==EBADF); close(fd);
            struct open_how how={.flags=O_PATH|O_CLOEXEC,.resolve=RESOLVE_NO_SYMLINKS};
            fd=syscall(SYS_openat2,AT_FDCWD,path,&how,sizeof(how)); assert(fd>=0);
            assert(!fstat(fd,&opened) && opened.st_ino==st.st_ino); close(fd);
            how.flags=O_RDONLY|O_CLOEXEC;
            fd=syscall(SYS_openat2,AT_FDCWD,path,&how,sizeof(how)); assert(fd>=0);
            assert(read(fd,bytes,sizeof(bytes))>0); close(fd);
            errno=0; assert(readlink(path,bytes,sizeof(bytes))==-1 && errno==EINVAL);
            strncat(path,"/child",sizeof(path)-strlen(path)-1);
            errno=0; assert(stat(path,&st)==-1 && errno==ENOTDIR);
        }
        _exit(0);
    }
    /* EVENT_WAIT: child checks a live foreign process in this proc mount. */
    int status=0; assert(waitpid(child,&status,0)==child && !status);
    puts("PASS foreign process image metadata and native access checks");
}
static volatile sig_atomic_t wait_signals;
static void wait_signal(int signal) { assert(signal==SIGUSR2); ++wait_signals; }
static void signal_wait_metadata(const char *proc) {
    for (unsigned mode=0;mode<4;++mode) {
        int ready[2]; assert(!pipe(ready));
        pid_t parent=getpid(),child=fork(); assert(child>=0);
        if (!child) {
            close(ready[0]);
            sigset_t set,before,after; sigemptyset(&set); sigaddset(&set,SIGUSR1);
            assert(!sigprocmask(SIG_BLOCK,&set,NULL));
            struct sigaction action={.sa_handler=wait_signal,.sa_flags=SA_RESTART};
            sigemptyset(&action.sa_mask); assert(!sigaction(SIGUSR2,&action,NULL));
            assert(!sigprocmask(SIG_SETMASK,NULL,&before));
            assert(write(ready[1],"!",1)==1);
            siginfo_t info;
            struct timespec bound={mode==3 ? 0 : 20,mode==3 ? 250000000 : 0};
            errno=0;
            int result=mode ? sigtimedwait(&set,&info,&bound) : sigwaitinfo(&set,&info);
            if (mode==2) assert(result==-1 && errno==EINTR && wait_signals==1);
            else if (mode==3) assert(result==-1 && errno==EAGAIN && !wait_signals);
            else assert(result==SIGUSR1 && info.si_pid==parent && info.si_code==SI_USER);
            assert(!sigprocmask(SIG_SETMASK,NULL,&after));
            for (int signal=1;signal<NSIG;++signal)
                assert(sigismember(&before,signal)==sigismember(&after,signal));
            assert(write(ready[1],"!",1)==1); close(ready[1]);
            _exit(0);
        }
        close(ready[1]);
        char byte; assert(read(ready[0],&byte,1)==1);
        if (mode==3) assert(!fcntl(ready[0],F_SETFL,O_NONBLOCK));
        /* EVENT_WAIT: the child cannot finish until all metadata requests
         * complete and the parent sends the exact signal it is awaiting. */
        for (unsigned round=0;mode==3 || round<64;++round) {
            if (mode==3) {
                ssize_t count=read(ready[0],&byte,1);
                if (count==1) break;
                assert(count==-1 && errno==EAGAIN);
            }
            char path[256]; struct stat st;
            snprintf(path,sizeof(path),"%s/%d/ctty",proc,child);
            errno=0; assert(lstat(path,&st)==-1 && errno==ENOENT);
            snprintf(path,sizeof(path),"%s/%d/cmdline",proc,child);
            int found=lstat(path,&st);
            assert((!found && S_ISREG(st.st_mode)) || (mode==3 && errno==ENOENT));
        }
        if (mode!=3) {
            assert(!kill(child,mode==2 ? SIGUSR2 : SIGUSR1));
            assert(read(ready[0],&byte,1)==1);
        }
        close(ready[0]);
        int status; assert(waitpid(child,&status,0)==child && !status);
    }
    puts("PASS foreign proc metadata: signal consumption, EINTR, timeout and unchanged masks");
}
static void execute(const char *path,int base,int protected) {
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        if (protected) assert(!prctl(PR_SET_DUMPABLE,0));
        char *args[]={(char *)path,"proc-exec","target",NULL};
        errno=0;
        assert(syscall(SYS_execveat,base,path,args,environ,AT_SYMLINK_NOFOLLOW)==-1 && errno==ELOOP);
        syscall(SYS_execveat,base,path,args,environ,0);
        fprintf(stderr,"foreign executable %s: %s\n",path,strerror(errno));
        _exit(99);
    }
    int status=0;
    /* EVENT_WAIT: exact exec child exit; alarm bounds a missing completion. */
    assert(waitpid(child,&status,0)==child);
    assert(WIFEXITED(status) && WEXITSTATUS(status)==37);
}
static void exercise(const char *proc,const char *directory,int native) {
    int root=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC); assert(root>=0);
    for (unsigned scope=0;scope<2;++scope) {
        const char *relative=scope ? proc+1 : proc;
        struct open_how how={.flags=O_PATH|O_DIRECTORY|O_CLOEXEC,
            .resolve=(scope ? RESOLVE_BENEATH : RESOLVE_IN_ROOT)|RESOLVE_NO_MAGICLINKS};
        int fd=syscall(SYS_openat2,root,relative,&how,sizeof(how));
        if (fd<0) perror("scoped proc root");
        assert(fd>=0);
        struct stat st,expected;
        assert(!fstat(fd,&st) && !stat(proc,&expected));
        assert(st.st_dev==expected.st_dev && st.st_ino==expected.st_ino);
        close(fd);
        how.resolve|=RESOLVE_NO_XDEV;
        errno=0;
        assert(syscall(SYS_openat2,root,relative,&how,sizeof(how))==-1 && errno==EXDEV);
    }
    close(root);
    puts("PASS scoped proc root opens retain mount-crossing policy");
    image_metadata(proc);
    signal_wait_metadata(proc);
    char path[256],alias[256];
    const char *nonlinks[]={"", "/sys", "/uptime", "/stat", "/filesystems", "/sys/kernel/hostname"};
    for (unsigned i=0;i<sizeof(nonlinks)/sizeof(nonlinks[0]);++i) {
        snprintf(path,sizeof(path),"%s%s",proc,nonlinks[i]);
        struct stat st; int inspected=lstat(path,&st);
        if (inspected && native && (errno==EACCES || errno==EPERM)) {
            printf("UNAVAILABLE native proc metadata: path=%s errno=%d\n",path,errno);
            continue;
        }
        if (inspected) perror(path);
        assert(!inspected && !S_ISLNK(st.st_mode));
        errno=0;
        assert(readlink(path,alias,sizeof(alias))==-1 && errno==EINVAL);
        int fd=open(path,O_PATH|O_NOFOLLOW|O_CLOEXEC); assert(fd>=0);
        errno=0;
        ssize_t length=readlinkat(fd,"",alias,sizeof(alias));
        if (length!=-1 || errno!=ENOENT)
            fprintf(stderr,"empty readlink %s: result=%zd errno=%d\n",path,length,errno);
        assert(length==-1 && errno==ENOENT);
        errno=0; assert(readlinkat(fd,"",alias,0)==-1 && errno==EINVAL);
        errno=0; assert(syscall(SYS_readlinkat,fd,"",alias,0x80000000UL)==-1 && errno==EINVAL);
        close(fd);
    }
    snprintf(path,sizeof(path),"%s/uptime",proc);
    int uptime=open(path,O_RDONLY|O_CLOEXEC); assert(uptime>=0);
    char sample[128]={0}; struct timespec before,after;
    assert(!clock_gettime(CLOCK_BOOTTIME,&before));
    assert(read(uptime,sample,sizeof(sample)-1)>0);
    assert(!clock_gettime(CLOCK_BOOTTIME,&after));
    double elapsed,idle;
    assert(sscanf(sample,"%lf %lf",&elapsed,&idle)==2
        && elapsed>=before.tv_sec-1 && elapsed<=after.tv_sec+1 && idle>=0);
    assert(!lseek(uptime,0,SEEK_SET));
    memset(sample,0,sizeof(sample)); assert(read(uptime,sample,sizeof(sample)-1)>0);
    double refreshed;
    assert(sscanf(sample,"%lf %lf",&refreshed,&idle)==2 && refreshed>=elapsed);
    close(uptime);
    snprintf(path,sizeof(path),"%s/stat",proc);
    FILE *statistics=fopen(path,"r");
    if (!statistics && native && (errno==EACCES || errno==EPERM))
        puts("UNAVAILABLE native proc boot epoch");
    else {
        assert(statistics);
        for (unsigned pass=0;pass<2;++pass) {
            assert(fgets(sample,sizeof(sample),statistics));
            unsigned long long user,nice,system,idle;
            assert(sscanf(sample,"cpu %llu %llu %llu %llu",&user,&nice,&system,&idle)==4
                && user+nice+system+idle>0);
            long long epoch=-1;
            while (fgets(sample,sizeof(sample),statistics))
                if (sscanf(sample,"btime %lld",&epoch)==1) break;
            struct timespec wall,boot;
            assert(!clock_gettime(CLOCK_REALTIME,&wall));
            assert(!clock_gettime(CLOCK_BOOTTIME,&boot));
            assert(epoch>0 && llabs(epoch-((long long)wall.tv_sec-boot.tv_sec))<=2);
            rewind(statistics);
        }
        fclose(statistics);
    }
    snprintf(path,sizeof(path),"%s/no-such-proc-entry",proc);
    errno=0;
    assert(readlink(path,alias,sizeof(alias))==-1 && errno==ENOENT);
    puts("PASS proc readlink: existing non-links, empty-path descriptors and missing paths");
    snprintf(path,sizeof(path),"%s/%d/exe",proc,getpid());
    execute(path,AT_FDCWD,0);
    execute(path,AT_FDCWD,1);
    snprintf(alias,sizeof(alias),"%s/proc-image-alias",directory);
    assert(!symlink(path,alias));
    execute(alias,AT_FDCWD,0);
    assert(!unlink(alias));
    snprintf(path,sizeof(path),"%s/%d",proc,getpid());
    int base=open(path,O_PATH|O_DIRECTORY|O_CLOEXEC); assert(base>=0);
    execute("exe",base,0); close(base);
    puts("PASS foreign executable selection");
}
int test_proc_exec_guest(int argc, char **argv) {
    if (argc==3 && !strcmp(argv[2],"target")) return 37;
    if (argc==5 && !strcmp(argv[2],"access-child")) {
        assert(prctl(PR_GET_DUMPABLE)==1);
        int output=atoi(argv[3]),input=atoi(argv[4]);
        for (unsigned step=0;step<2;++step) {
            if (step) assert(!prctl(PR_SET_DUMPABLE,0));
            assert(write(output,"!",1)==1);
            char byte; assert(read(input,&byte,1)==1);
        }
        return 0;
    }
    setbuf(stdout,NULL); alarm(40);
    assert(argc==4);
    exec_access();
    if (!strcmp(argv[2],"memfd")) {
        int source=open("/proc/self/exe",O_RDONLY|O_CLOEXEC); assert(source>=0);
        int fd=memfd_create("proc-exec",MFD_CLOEXEC|MFD_ALLOW_SEALING); assert(fd>=0);
        char bytes[16384]; ssize_t n;
        while ((n=read(source,bytes,sizeof(bytes)))>0) assert(write(fd,bytes,(size_t)n)==n);
        assert(n==0); close(source);
        struct stat st; assert(!fstat(fd,&st) && (st.st_mode&0111));
        assert(!fcntl(fd,F_ADD_SEALS,F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL));
        char *args[]={"anonymous","proc-exec","direct",argv[3],NULL};
        syscall(SYS_execveat,fd,"",args,environ,AT_EMPTY_PATH);
        perror("memfd image"); return 99;
    }
    if (!strcmp(argv[2],"pid")) {
        assert(!unshare(CLONE_NEWNS|CLONE_NEWPID));
        pid_t child=fork(); assert(child>=0);
        if (!child) {
            assert(getpid()==1);
            char proc[256]; snprintf(proc,sizeof(proc),"%s/proc-exec",argv[3]);
            assert(!mkdir(proc,0700));
            assert(!mount("proc",proc,"proc",MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL));
            exercise(proc,argv[3],0);
            assert(!umount(proc) && !rmdir(proc)); _exit(0);
        }
        int status=0; assert(waitpid(child,&status,0)==child && !status);
        return 0;
    }
    exercise("/proc",argv[3],!strcmp(argv[2],"native"));
    return 0;
}
