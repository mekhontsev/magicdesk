#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

static void names_are(const char *host, const char *domain) {
    struct utsname names;
    assert(!uname(&names));
    assert(!strcmp(names.nodename,host) && !strcmp(names.domainname,domain));
}
static void completed(pid_t child) {
    int status;
    /* EVENT_WAIT: exact child exit; fixture alarm bounds stalled progress. */
    assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void content(int fd, const char *expected) {
    char data[256]={0};
    ssize_t n=pread(fd,data,sizeof(data),0);
    assert(n==(ssize_t)strlen(expected) && !memcmp(data,expected,(size_t)n));
}
static void overflow_ids(void) {
    const char *paths[]={"/proc/sys/kernel/overflowuid","/proc/sys/kernel/overflowgid"};
    for (unsigned i=0;i<2;++i) {
        int fd=open(paths[i],O_RDONLY|O_CLOEXEC); assert(fd>=0);
        content(fd,"65534\n");
        struct stat a,b;
        assert(!stat(paths[i],&a) && !fstat(fd,&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
        assert((a.st_mode&0777)==0444);
        assert(open(paths[i],O_WRONLY)<0 && errno==EACCES);
        assert(access(paths[i],W_OK)<0 && errno==EACCES);
        assert(pwrite(fd,"1",1,0)<0 && errno==EBADF);
        char tail[16]={0}; assert(pread(fd,tail,sizeof(tail),3)==3 && !strcmp(tail,"34\n"));
        assert(!pread(fd,tail,sizeof(tail),6));
        close(fd);
    }
}
static void capability_limit(void) {
    const char *path="/proc/sys/kernel/cap_last_cap";
    int fd=open(path,O_RDONLY|O_CLOEXEC); assert(fd>=0);
    char text[32]={0};
    ssize_t size=read(fd,text,sizeof(text)-1); assert(size>1);
    char *end; long last=strtol(text,&end,10);
    assert(last>0 && last<64 && !strcmp(end,"\n"));
    for (int i=0;i<=last;++i) assert(prctl(PR_CAPBSET_READ,i,0,0,0)>=0);
    assert(prctl(PR_CAPBSET_READ,last+1,0,0,0)<0 && errno==EINVAL);
    assert(open(path,O_WRONLY)<0 && errno==EACCES);
    struct stat a,b; assert(!fstat(fd,&a) && !stat(path,&b) && a.st_ino==b.st_ino);
    assert((a.st_mode&0777)==0444);
    close(fd);
}
static void proc_files(void) {
    overflow_ids();
    capability_limit();
    const char *path="/proc/sys/kernel/hostname";
    int fd=open(path,O_RDWR|O_CLOEXEC);
    if (fd<0) perror("open guest UTS sysctl");
    assert(fd>=0);
    content(fd,"parent\n");
    struct stat a,b;
    assert(!stat(path,&a) && !fstat(fd,&b) && a.st_ino==b.st_ino && a.st_dev==b.st_dev);
    assert((a.st_mode&0777)==0644 && !a.st_uid && !a.st_gid);
    int directory=open("/proc/sys/kernel",O_PATH|O_DIRECTORY); assert(directory>=0);
    struct open_how how={.flags=O_RDONLY|O_CLOEXEC,.resolve=RESOLVE_BENEATH};
    int relative=syscall(SYS_openat2,directory,"hostname",&how,sizeof(how));
    if (relative<0) perror("openat2 relative UTS sysctl");
    assert(relative>=0);
    content(relative,"parent\n"); close(relative);
    assert(syscall(SYS_openat2,directory,"../kernel/hostname",&how,sizeof(how))<0 && errno==EXDEV);
    close(directory);
    int readonly=open("/proc/sys/kernel/ostype",O_RDONLY); assert(readonly>=0);
    struct utsname names; assert(!uname(&names));
    char value[256]; snprintf(value,sizeof(value),"%s\n",names.sysname); content(readonly,value); close(readonly);
    assert(open("/proc/sys/kernel/ostype",O_WRONLY)<0 && errno==EACCES);
    assert(access(path,X_OK)<0 && errno==EACCES);
    assert(pwrite(fd,(void *)1,5,0)<0 && errno==EFAULT); content(fd,"parent\n");
    struct iovec vec[]={{"joined",6},{"-name\n",6}};
    assert(writev(fd,vec,2)==12); content(fd,"joined-name\n");
    assert(lseek(fd,0,SEEK_CUR)==12);
    assert(write(fd,"ignored",7)==7 && lseek(fd,0,SEEK_CUR)==12);
    content(fd,"joined-name\n");
    assert(pwrite(fd,"X\ntrailing",10,3)==10); content(fd,"joiX\n");
    char large[8192]; memset(large,'a',sizeof(large));
    assert(pwrite(fd,large,sizeof(large),0)==sizeof(large));
    memset(value,'a',64); value[64]='\n'; value[65]=0; content(fd,value);
    assert(!sethostname("parent",6));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        assert(!unshare(CLONE_NEWUTS));
        assert(pwrite(fd,"sysctl-child",12,0)==12);
        names_are("sysctl-child","outer"); content(fd,"sysctl-child\n");
        int domain=open("/proc/sys/kernel/domainname",O_RDWR); assert(domain>=0);
        assert(write(domain,"changed\n",8)==8); names_are("sysctl-child","changed");
        close(domain); _exit(0);
    }
    completed(child); names_are("parent","outer"); content(fd,"parent\n");
    child=fork(); assert(child>=0);
    if (!child) {
        assert(!setuid(1000));
        assert(!unshare(CLONE_NEWUSER|CLONE_NEWUTS));
        overflow_ids();
        capability_limit();
        assert(!sethostname("scoped-capability",17));
        assert(pwrite(fd,"denied",6,0)<0 && errno==EPERM);
        assert(open(path,O_WRONLY)<0 && errno==EACCES);
        content(fd,"scoped-capability\n"); _exit(0);
    }
    completed(child); content(fd,"parent\n"); close(fd);
}
static int isolated(void *executable) {
    names_are("parent","outer");
    assert(!sethostname("child",5) && !setdomainname("inner",5));
    pid_t child=fork(); assert(child>=0);
    if (!child) {
        names_are("child","inner");
        assert(!unshare(CLONE_NEWUTS));
        assert(!sethostname("nested",6));
        names_are("nested","inner");
        _exit(0);
    }
    completed(child); names_are("child","inner");
    execl(executable,"test","uts-namespace","exec",NULL);
    _exit(1);
}
int test_uts_namespace_guest(int argc, char **argv) {
    alarm(60);
    if (argc==3) { names_are("child","inner"); return 0; }
    struct utsname before; assert(!uname(&before));
    assert(!strcmp(before.nodename,"launch-name"));
    assert(!sethostname("parent",6) && !setdomainname("outer",5));
    proc_files();
    pid_t child=fork(); assert(child>=0);
    if (!child) { assert(!sethostname("shared",6)); _exit(0); }
    completed(child); names_are("shared","outer");
    assert(!sethostname("parent",6));
    void *stack=malloc(1024*1024); assert(stack);
    child=clone(isolated,(char *)stack+1024*1024,CLONE_NEWUTS|SIGCHLD,argv[0]); assert(child>=0);
    completed(child); names_are("parent","outer");
    assert(clone(isolated,(char *)stack+1024*1024,CLONE_NEWUTS|CLONE_SIGHAND|SIGCHLD,NULL)<0 && errno==EINVAL);
    free(stack); names_are("parent","outer");
    assert(unshare(CLONE_NEWUTS|CLONE_NEWTIME)<0 && errno==ENOTSUP);
    names_are("parent","outer");
    assert(!prctl(PR_SET_DUMPABLE,0));
    overflow_ids();
    assert(!sethostname("protected",9)); names_are("protected","outer");
    int protected=open("/proc/sys/kernel/hostname",O_RDWR); assert(protected>=0);
    content(protected,"protected\n");
    assert(pwrite(protected,"protected-io",12,0)==12);
    names_are("protected-io","outer");
    assert(!sethostname("protected",9)); close(protected);
    char large[65]={0}; assert(sethostname(large,sizeof(large))<0 && errno==EINVAL);
    assert(syscall(SYS_sethostname,1,4)<0 && errno==EFAULT);
    names_are("protected","outer");
    assert(!sethostname("",0)); names_are("","outer");
    assert(!setuid(1234));
    assert(sethostname("denied",6)<0 && errno==EPERM);
    assert(syscall(SYS_sethostname,1,sizeof(large))<0 && errno==EPERM);
    assert(syscall(SYS_setdomainname,1,4)<0 && errno==EPERM);
    assert(unshare(CLONE_NEWUTS)<0 && errno==EPERM);
    names_are("","outer");
    puts("PASS guest UTS namespaces: launch, shared fork, clone, nested unshare, exec, protected memory, rollback and permissions");
    return 0;
}
