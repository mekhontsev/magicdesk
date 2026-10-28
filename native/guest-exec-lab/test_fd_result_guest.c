#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr,"result-custody line=%d %s errno=%d\n",__LINE__,#x,errno); exit(1); } } while (0)
static char directory[PATH_MAX];
static pthread_barrier_t start;
static unsigned descriptors(void) {
    DIR *d=opendir("/proc/self/fd"); CHECK(d);
    unsigned n=0;
    for (struct dirent *e;(e=readdir(d));) if (e->d_name[0]!='.') ++n;
    CHECK(!closedir(d)); return n;
}
static void descriptor_balance(unsigned expected) {
    unsigned actual=descriptors();
    if (actual!=expected) {
        fprintf(stderr,"result-custody descriptors expected=%u actual=%u\n",expected,actual);
        DIR *d=opendir("/proc/self/fd"); CHECK(d);
        for (struct dirent *e;(e=readdir(d));) if (e->d_name[0]!='.') {
            char target[PATH_MAX];
            ssize_t n=readlinkat(dirfd(d),e->d_name,target,sizeof(target)-1);
            if (n>=0) { target[n]=0; fprintf(stderr,"result-custody fd=%s target=%s\n",e->d_name,target); }
        }
        CHECK(!closedir(d));
    }
    CHECK(actual==expected);
}
static void cycle(unsigned owner,unsigned round) {
    char path[512],renamed[512],data[64],readback[64]={0};
    CHECK(snprintf(path,sizeof(path),"%s/%u-file",directory,owner)<(int)sizeof(path));
    CHECK(snprintf(renamed,sizeof(renamed),"%s/%u-renamed",directory,owner)<(int)sizeof(renamed));
    int n=snprintf(data,sizeof(data),"owner=%u round=%u",owner,round);
    int flags=round&1 ? O_CLOEXEC : 0;
    int fd=open(path,O_CREAT|O_EXCL|O_RDWR|flags,0600); CHECK(fd>=0);
    CHECK(fcntl(fd,F_GETFD)==(flags ? FD_CLOEXEC : 0));
    CHECK(write(fd,data,(size_t)n)==n);
    int copy=dup(fd); CHECK(copy>=0 && fcntl(copy,F_GETFD)==0);
    CHECK(lseek(copy,0,SEEK_SET)==0 && read(fd,readback,sizeof(readback))==n);
    CHECK(!memcmp(data,readback,(size_t)n));
    errno=0; CHECK(open(path,O_CREAT|O_EXCL|O_RDWR,0600)==-1 && errno==EEXIST);
    CHECK(!rename(path,renamed) && !unlink(renamed));
    struct stat s; CHECK(!fstat(copy,&s) && s.st_nlink==0);
    CHECK(!close(copy) && !close(fd));
    errno=0; CHECK(open(path,O_RDONLY)==-1 && errno==ENOENT);
}
static void *thread(void *context) {
    unsigned owner=(unsigned)(uintptr_t)context;
    int r=pthread_barrier_wait(&start); CHECK(!r || r==PTHREAD_BARRIER_SERIAL_THREAD);
    for (unsigned i=0;i<128;++i) cycle(owner,i);
    return NULL;
}
static void *thread_initialize(void *context) { return context; }
static void reap(pid_t pid,int killed) {
    int status; CHECK(waitpid(pid,&status,0)==pid);
    CHECK(killed ? WIFSIGNALED(status) && WTERMSIG(status)==SIGKILL : WIFEXITED(status) && WEXITSTATUS(status)==0);
}
static void cleanup(void) {
    DIR *d=opendir(directory); CHECK(d);
    for (struct dirent *e;(e=readdir(d));) {
        if (!strcmp(e->d_name,".") || !strcmp(e->d_name,"..")) continue;
        CHECK(!unlinkat(dirfd(d),e->d_name,0));
    }
    CHECK(!closedir(d));
}
int test_fd_result_guest(const char *base,int mode) {
    CHECK(!(mode&~3));
    /* Bionic can open its process-wide trace marker on the first pthread.
     * Initialize threading before measuring IO-owned descriptor lifetime. */
    pthread_t initialized;
    CHECK(!pthread_create(&initialized,NULL,thread_initialize,NULL));
    CHECK(!pthread_join(initialized,NULL));
    CHECK(snprintf(directory,sizeof(directory),"%s/result-custody-%ld",base,(long)getpid())<(int)sizeof(directory));
    CHECK(!mkdir(directory,0700));
    if (mode&1) CHECK(!prctl(PR_SET_DUMPABLE,0));
    if (mode&2) {
        struct sock_filter rule=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog filter={.len=1,.filter=&rule};
        CHECK(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        CHECK(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
    }
    cycle(0,0);
    unsigned before=descriptors();
    CHECK(!pthread_barrier_init(&start,NULL,4));
    pthread_t threads[4];
    for (uintptr_t i=0;i<4;++i) CHECK(!pthread_create(&threads[i],NULL,thread,(void *)i));
    for (unsigned i=0;i<4;++i) CHECK(!pthread_join(threads[i],NULL));
    CHECK(!pthread_barrier_destroy(&start));
    descriptor_balance(before);
    puts("PASS shared FD table: concurrent opens, CLOEXEC, duplicate OFD, unlink, exact errors"); fflush(stdout);
    for (unsigned round=0;round<32;++round) {
        int ready[2]; CHECK(!pipe2(ready,O_CLOEXEC));
        pid_t child=fork(); CHECK(child>=0);
        if (!child) {
            CHECK(!close(ready[0]));
            cycle(0,round);
            CHECK(write(ready[1],"x",1)==1 && !close(ready[1]));
            for (unsigned i=0;i<10000;++i) cycle(0,i);
            _exit(0);
        }
        CHECK(!close(ready[1]));
        /* EVENT_WAIT: child confirms its first completed open; the runner bounds
         * failure and reaps the whole tree. No scheduling delay is assumed. */
        char byte; CHECK(read(ready[0],&byte,1)==1 && !close(ready[0]));
        for (unsigned i=0;i<round%16;++i) sched_yield();
        CHECK(!kill(child,SIGKILL)); reap(child,1); cleanup();
    }
    descriptor_balance(before);
    CHECK(!rmdir(directory));
    puts("PASS cancellation stress: 32 killed children, no guest FD growth");
    return 0;
}
