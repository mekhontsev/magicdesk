#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <linux/openat2.h>
#include <sched.h>
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
    char path[256],alias[256];
    const char *nonlinks[]={"", "/sys", "/uptime", "/filesystems", "/sys/kernel/hostname"};
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
    setbuf(stdout,NULL); alarm(40);
    assert(argc==4);
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
