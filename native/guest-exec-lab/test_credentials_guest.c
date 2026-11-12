#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/capability.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/fsuid.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr,"credentials line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static void child_done(pid_t child) {
    int status;
    /* EVENT_WAIT: child exit; the launch deadline terminates a stuck fixture. */
    CHECK(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
}
static void groups(void) {
    gid_t input[65], output[65];
    for (unsigned i=0;i<65;i++) input[i]=3000+64-i;
    CHECK(!setgroups(65,input));
    CHECK(getgroups(0,NULL)==65 && getgroups(65,output)==65);
    for (unsigned i=0;i<65;i++) CHECK(output[i]==3000+i);
    errno=0; CHECK(getgroups(64,output)==-1 && errno==EINVAL);
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    void *bad=mmap(NULL,page,PROT_NONE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0); CHECK(bad!=MAP_FAILED);
    errno=0; CHECK(syscall(SYS_setgroups,65,bad)==-1 && errno==EFAULT);
    CHECK(getgroups(65,output)==65 && output[0]==3000);
    errno=0; CHECK(syscall(SYS_getgroups,65,bad)==-1 && errno==EFAULT);
    CHECK(!munmap(bad,page));
    input[32]=(gid_t)-1;
    errno=0; CHECK(setgroups(65,input)==-1 && errno==EINVAL);
    CHECK(getgroups(65,output)==65 && output[64]==3064);
}
static void structured_identity(void) {
    size_t page=(size_t)sysconf(_SC_PAGESIZE);
    unsigned char *memory=mmap(NULL,page*2,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    CHECK(memory!=MAP_FAILED && !mprotect(memory+page,page,PROT_NONE));
    uint32_t ids[3]={1,2,3};
    CHECK(!syscall(SYS_getresuid,ids,ids+1,ids+2));
    CHECK(!ids[0] && !ids[1] && !ids[2]);
    ids[0]=9; ids[2]=9;
    errno=0; CHECK(syscall(SYS_getresgid,ids,memory+page,ids+2)==-1 && errno==EFAULT);
    CHECK(!ids[0] && ids[2]==9);
    struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3};
    struct __user_cap_data_struct caps[2];
    CHECK(!syscall(SYS_capget,&header,caps) && !syscall(SYS_capset,&header,caps));
    errno=0; CHECK(syscall(SYS_capget,&header,memory+page)==-1 && errno==EFAULT);
    header.version=0;
    CHECK(!syscall(SYS_capget,&header,NULL) && header.version==_LINUX_CAPABILITY_VERSION_3);
    header.version=0;
    errno=0; CHECK(syscall(SYS_capget,&header,caps)==-1 && errno==EINVAL);
    CHECK(header.version==_LINUX_CAPABILITY_VERSION_3);
    struct rlimit before,after,next;
    CHECK(!getrlimit(RLIMIT_NPROC,&before)); next=before;
    if (next.rlim_cur>1) --next.rlim_cur;
    errno=0; CHECK(syscall(SYS_prlimit64,0,RLIMIT_NPROC,&next,memory+page)==-1 && errno==EFAULT);
    CHECK(!getrlimit(RLIMIT_NPROC,&after) && after.rlim_cur==next.rlim_cur);
    CHECK(!setrlimit(RLIMIT_NPROC,&before));
    enum { count=65536 };
    gid_t *input=malloc(count*sizeof(*input)),*output=malloc(count*sizeof(*output));
    CHECK(input && output);
    for (unsigned i=0;i<count;++i) input[i]=100000+count-i-1;
    CHECK(!setgroups(count,input) && getgroups(count,output)==count);
    for (unsigned i=0;i<count;++i) CHECK(output[i]==100000+i);
    for (unsigned i=0;i<page/sizeof(gid_t);++i) ((gid_t *)memory)[i]=200000+i;
    errno=0; CHECK(syscall(SYS_setgroups,page/sizeof(gid_t)+1,memory)==-1 && errno==EFAULT);
    CHECK(getgroups(count,output)==count && output[0]==100000 && output[count-1]==100000+count-1);
    CHECK(!setgroups(0,NULL));
    free(input); free(output); CHECK(!munmap(memory,page*2));
    puts("PASS structured credentials, complete group capture and committed limit output faults");
}
static void *thread_ids(void *unused) {
    (void)unused;
    CHECK(syscall(SYS_getuid)==0);
    CHECK(!syscall(SYS_setresuid,1000,1000,0));
    CHECK(syscall(SYS_getuid)==1000 && syscall(SYS_geteuid)==1000);
    CHECK(!syscall(SYS_tgkill,getpid(),getpid(),0));
    return NULL;
}
static void *libc_ids(void *context) {
    int *sync=context; char byte;
    CHECK(write(sync[3],"r",1)==1);
    /* EVENT_WAIT: main thread finishes the libc set-ID broadcast; launch deadline bounds failure. */
    CHECK(read(sync[0],&byte,1)==1);
    /* Bionic exports raw thread-local set-ID stubs; Linux libc broadcasts them. */
#ifdef __BIONIC__
    CHECK(getuid()==0 && geteuid()==0 && getgid()==0 && getegid()==0);
    CHECK(getgroups(0,NULL)==65);
#else
    CHECK(getuid()==1000 && geteuid()==1000 && getgid()==1234 && getegid()==1234);
    CHECK(getgroups(0,NULL)==0);
#endif
    return NULL;
}
static void libc_broadcast(void) {
    pid_t child=fork(); CHECK(child>=0);
    if (!child) {
        int sync[4]; CHECK(!pipe(sync) && !pipe(sync+2));
        pthread_t thread; CHECK(!pthread_create(&thread,NULL,libc_ids,sync));
        char byte;
        /* EVENT_WAIT: worker is alive before libc broadcasts credentials to all threads. */
        CHECK(read(sync[2],&byte,1)==1);
        CHECK(!setgroups(0,NULL) && !setgid(1234) && !setuid(1000));
        CHECK(write(sync[1],"d",1)==1 && !pthread_join(thread,NULL));
        for (unsigned i=0;i<4;i++) CHECK(!close(sync[i]));
        _exit(0);
    }
    child_done(child);
}
static void signal_credentials(void) {
    pid_t parent=getpid(),outside=getppid();
    errno=0; CHECK(kill(outside,0)==-1 && errno==ESRCH);
    int outside_fd=syscall(SYS_pidfd_open,outside,0); CHECK(outside_fd>=0);
    errno=0; CHECK(syscall(SYS_pidfd_send_signal,outside_fd,0,NULL,0)==-1 && errno==ESRCH);
    CHECK(!close(outside_fd));
    errno=0; CHECK(kill(-1,0)==-1 && errno==ESRCH);
    int ready[2]; CHECK(!pipe(ready));
    pid_t victim=fork(); CHECK(victim>=0);
    if (!victim) {
        CHECK(!close(ready[0]) && !setuid(30001));
        CHECK(write(ready[1],"r",1)==1);
        /* EVENT_WAIT: the same-UID cleanup child kills this fixture process. */
        for (;;) pause();
    }
    CHECK(!close(ready[1])); char byte;
    /* EVENT_WAIT: victim has adopted its guest build-user identity. */
    CHECK(read(ready[0],&byte,1)==1 && !close(ready[0]));
    pid_t cleaner=fork(); CHECK(cleaner>=0);
    if (!cleaner) {
        int fd=syscall(SYS_pidfd_open,parent,0); CHECK(fd>=0);
        CHECK(!setuid(30001));
        /* These harmless probes must fail first even on an unfixed runtime;
         * never attempt the broadcast SIGKILL with native UID authority. */
        errno=0; CHECK(kill(parent,0)==-1 && errno==EPERM);
        errno=0; CHECK(syscall(SYS_tkill,parent,0)==-1 && errno==EPERM);
        errno=0; CHECK(syscall(SYS_tgkill,parent,parent,0)==-1 && errno==EPERM);
        siginfo_t info={.si_code=SI_QUEUE};
        errno=0; CHECK(syscall(SYS_rt_sigqueueinfo,parent,0,&info)==-1 && errno==EPERM);
        errno=0; CHECK(syscall(SYS_rt_tgsigqueueinfo,parent,parent,0,&info)==-1 && errno==EPERM);
        errno=0; CHECK(syscall(SYS_pidfd_send_signal,fd,0,NULL,0)==-1 && errno==EPERM);
        CHECK(!close(fd) && !kill(victim,0) && !kill(-1,0));
        CHECK(!kill(-1,SIGKILL));
        _exit(0);
    }
    child_done(cleaner);
    int status;
    /* EVENT_WAIT: only the selected build user's process has been killed. */
    CHECK(waitpid(victim,&status,0)==victim && WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL);
    puts("PASS signal credentials and broadcast cleanup without a PID namespace");
}
int main(int argc, char **argv) {
    if (argc==2 && !strcmp(argv[1],"signals")) {
        signal_credentials(); groups();
        pthread_t thread; CHECK(!pthread_create(&thread,NULL,thread_ids,NULL));
        CHECK(!pthread_join(thread,NULL)); CHECK(getuid()==0 && geteuid()==0);
        libc_broadcast(); return 0;
    }
    if (argc==2 && !strcmp(argv[1],"exec")) {
        CHECK(getuid()==1000 && geteuid()==1000 && getgid()==1234 && getegid()==1234);
        CHECK(getauxval(AT_UID)==1000 && getauxval(AT_EUID)==1000);
        CHECK(getauxval(AT_GID)==1234 && !getauxval(AT_SECURE));
        CHECK(prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0)==1);
        errno=0; CHECK(setuid(0)==-1 && errno==EPERM);
        puts("PASS exec identity and no-new-privs"); return 0;
    }
    CHECK(getuid()==0 && geteuid()==0 && getauxval(AT_UID)==0);
    /* Permission assertions must not depend on the Android executor's umask. */
    umask(0022);
    structured_identity();
    signal_credentials();
    groups();
    pid_t child=fork(); CHECK(child>=0);
    if (!child) { CHECK(!prctl(PR_SET_DUMPABLE,0)); groups(); _exit(0); }
    child_done(child);
    pthread_t thread; CHECK(!pthread_create(&thread,NULL,thread_ids,NULL));
    CHECK(!pthread_join(thread,NULL)); CHECK(getuid()==0 && geteuid()==0);
    libc_broadcast();
    CHECK(!mkdir("/tmp/credential-calls",0755));
    int fd=open("/tmp/credential-calls/file",O_CREAT|O_RDWR,0600); CHECK(fd>=0);
    const unsigned acl_version=2;
    CHECK(fsetxattr(fd,"system.posix_acl_access",&acl_version,sizeof(acl_version),0)==0);
    CHECK(!fchown(fd,1000,1000)); CHECK(write(fd,"x",1)==1);
    CHECK(!mkdir("/tmp/credential-calls/secret",0700));
    int readable=open("/tmp/credential-calls/secret/readable",O_CREAT|O_WRONLY,0644);
    CHECK(readable>=0 && !close(readable));
    int directory=open("/tmp/credential-calls/secret",O_PATH|O_DIRECTORY); CHECK(directory>=0);
    CHECK(!syscall(SYS_faccessat2,AT_FDCWD,"",X_OK,AT_EMPTY_PATH));
    CHECK(!syscall(SYS_faccessat2,directory,"",X_OK,AT_EMPTY_PATH));
    errno=0; CHECK(syscall(SYS_faccessat2,-1,"",F_OK,AT_EMPTY_PATH)==-1 && errno==EBADF);
    int private=open("/tmp/credential-calls/private",O_CREAT|O_WRONLY,0600); CHECK(private>=0 && !close(private));
    child=fork(); CHECK(child>=0);
    if (!child) {
        CHECK(!setresgid(1000,1000,0)); CHECK(!setresuid(1000,0,0));
        CHECK(setfsuid(1234)==0 && setfsuid((uid_t)-1)==1234);
        int access_error=access("/tmp/credential-calls/file",R_OK);
        if (access_error) {
            int saved=errno; struct stat st;
            CHECK(!fstat(fd,&st));
            fprintf(stderr,"real access error=%d uid=%u euid=%u fsuid=%u gid=%u owner=%u:%u mode=%o\n",
                saved,getuid(),geteuid(),setfsuid((uid_t)-1),getgid(),st.st_uid,st.st_gid,st.st_mode);
            errno=saved;
        }
        CHECK(!access_error);
        errno=0; long access_result=syscall(SYS_faccessat2,directory,"",X_OK,AT_EMPTY_PATH|0x200);
        if (access_result!=-1 || errno!=EACCES) {
            int saved=errno; struct stat info;
            struct __user_cap_header_struct header={.version=_LINUX_CAPABILITY_VERSION_3};
            struct __user_cap_data_struct caps[2]={{0}};
            CHECK(!fstat(directory,&info) && !syscall(SYS_capget,&header,caps));
            fprintf(stderr,"access result=%ld errno=%d uid=%u euid=%u fsuid=%u owner=%u mode=%o caps=%x:%x\n",
                access_result,saved,getuid(),geteuid(),setfsuid((uid_t)-1),info.st_uid,info.st_mode,caps[1].effective,caps[0].effective);
            errno=saved;
        }
        CHECK(access_result==-1 && errno==EACCES);
        errno=0; CHECK(fchdir(directory)==-1 && errno==EACCES);
        CHECK(setfsuid(0)==1234);
        errno=0; CHECK(syscall(SYS_faccessat2,directory,"",X_OK,AT_EMPTY_PATH)==-1 && errno==EACCES);
        CHECK(!syscall(SYS_faccessat2,directory,"",X_OK,AT_EMPTY_PATH|0x200));
        errno=0; CHECK(access("/tmp/credential-calls/secret/readable",R_OK)==-1 && errno==EACCES);
        CHECK(!syscall(SYS_faccessat2,AT_FDCWD,"/tmp/credential-calls/secret/readable",R_OK,0x200));
        CHECK(!setresuid(1000,1000,0));
        int watch=inotify_init1(IN_CLOEXEC); CHECK(watch>=0);
        errno=0; CHECK(inotify_add_watch(watch,"/tmp/credential-calls/private",IN_MODIFY)==-1 && errno==EACCES);
        CHECK(!close(watch));
        CHECK(setfsuid(1234)==1000);
        CHECK(!faccessat(AT_FDCWD,"/tmp/credential-calls/file",R_OK,AT_EACCESS));
        errno=0; CHECK(fchdir(directory)==-1 && errno==EACCES);
        CHECK(write(fd,"y",1)==1);
        errno=0; CHECK(setgroups(0,NULL)==-1 && errno==EPERM);
        CHECK(!setuid(0)); CHECK(!setgid(1234)); CHECK(!setuid(1000));
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        execl(argv[0],argv[0],"exec",(char *)NULL); CHECK(0);
    }
    child_done(child);
    CHECK(!fchmod(fd,0000)); CHECK(!faccessat(AT_FDCWD,"/tmp/credential-calls/file",R_OK|W_OK,AT_EACCESS));
    errno=0; CHECK(faccessat(AT_FDCWD,"/tmp/credential-calls/file",X_OK,AT_EACCESS)==-1 && errno==EACCES);
    CHECK(!fchmod(fd,0700)); CHECK(!faccessat(AT_FDCWD,"/tmp/credential-calls/file",X_OK,AT_EACCESS));
    struct stat st; CHECK(!fstat(fd,&st) && st.st_uid==1000 && st.st_gid==1000 && (st.st_mode&07777)==0700);
    CHECK(!close(fd) && !close(directory));
    CHECK(!unlink("/tmp/credential-calls/file") && !unlink("/tmp/credential-calls/private")
        && !unlink("/tmp/credential-calls/secret/readable")
        && !rmdir("/tmp/credential-calls/secret") && !rmdir("/tmp/credential-calls"));
    puts("PASS raw credentials, groups, protected memory, thread locality, fork, filesystem access");
    return 0;
}
