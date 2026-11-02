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
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/uio.h>
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
struct parallel_read { const char *path; unsigned completed; };
static void *read_parallel(void *context) {
    struct parallel_read *work=context;
    char buffer[4096];
    int r=pthread_barrier_wait(&start); CHECK(!r || r==PTHREAD_BARRIER_SERIAL_THREAD);
    for (unsigned i=0;i<4000;++i) {
        int fd=open(work->path,O_RDONLY|O_CLOEXEC); CHECK(fd>=0);
        CHECK(fcntl(fd,F_GETFD)==FD_CLOEXEC);
        int flags=fcntl(fd,F_GETFL); CHECK(flags>=0);
        CHECK(!fcntl(fd,F_SETFL,flags|O_NONBLOCK));
        ssize_t size=read(fd,buffer,sizeof(buffer));
        CHECK(size==13 && !memcmp(buffer,"parallel-read",13));
        CHECK(!read(fd,buffer,sizeof(buffer)));
        CHECK(!close(fd));
        ++work->completed;
    }
    return NULL;
}
int test_parallel_read_guest(const char *base) {
    char path[PATH_MAX];
    CHECK(snprintf(path,sizeof(path),"%s/parallel-read-%ld",base,(long)getpid())<(int)sizeof(path));
    int fd=open(path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600); CHECK(fd>=0);
    CHECK(write(fd,"parallel-read",13)==13 && !close(fd));
    pthread_t initialized;
    CHECK(!pthread_create(&initialized,NULL,thread_initialize,NULL));
    CHECK(!pthread_join(initialized,NULL));
    unsigned before=descriptors();
    enum { COUNT=32 };
    pthread_t threads[COUNT]; struct parallel_read work[COUNT];
    CHECK(!pthread_barrier_init(&start,NULL,COUNT));
    for (unsigned i=0;i<COUNT;++i) {
        work[i]=(struct parallel_read){.path=path};
        CHECK(!pthread_create(&threads[i],NULL,read_parallel,&work[i]));
    }
    unsigned completed=0;
    for (unsigned i=0;i<COUNT;++i) {
        CHECK(!pthread_join(threads[i],NULL));
        CHECK(work[i].completed==4000); completed+=work[i].completed;
    }
    CHECK(!pthread_barrier_destroy(&start)); descriptor_balance(before); CHECK(!unlink(path));
    printf("PASS parallel read: threads=%u completed=%u, nonblocking regular IO, exact bytes/EOF and no FD growth\n",COUNT,completed);
    return 0;
}
struct read_capture { int watch, stream; };
static void *read_captured(void *context) {
    struct read_capture *work=context;
    union { struct inotify_event event; char bytes[256]; } buffer;
    int available=0;
    CHECK(!ioctl(work->watch,FIONREAD,&available) && available>0);
    struct iovec vector={buffer.bytes,sizeof(buffer.bytes)};
    CHECK(readv(work->watch,&vector,1)==available);
    CHECK(buffer.event.mask==IN_CREATE && !strcmp(buffer.event.name,"event"));
    errno=0; CHECK(read(work->watch,buffer.bytes,sizeof(buffer.bytes))==-1 && errno==EAGAIN);
    CHECK(read(work->stream,buffer.bytes,1)==1 && buffer.bytes[0]=='a');
    vector=(struct iovec){buffer.bytes,2};
    CHECK(readv(work->stream,&vector,1)==2 && !memcmp(buffer.bytes,"bc",2));
    CHECK(!read(work->stream,buffer.bytes,sizeof(buffer.bytes)));
    CHECK(!close(work->stream));
    errno=0; CHECK(read(work->stream,buffer.bytes,sizeof(buffer.bytes))==-1 && errno==EBADF);
    return NULL;
}
static void read_capture_cycle(const char *base) {
    pthread_t thread;
    char path[PATH_MAX],event[PATH_MAX];
    CHECK(snprintf(path,sizeof(path),"%s/read-capture-%ld",base,(long)getpid())<(int)sizeof(path));
    CHECK(snprintf(event,sizeof(event),"%s/event",path)<(int)sizeof(event));
    CHECK(!mkdir(path,0700));
    int watch=inotify_init1(IN_CLOEXEC|IN_NONBLOCK); CHECK(watch>=0);
    CHECK(inotify_add_watch(watch,path,IN_CREATE)>=0);
    int file=open(event,O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600); CHECK(file>=0 && !close(file));
    int pair[2]; CHECK(!socketpair(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0,pair));
    /* Reusing a watch-selected slot must preserve native IO ownership. The
     * non-leader also reads a live watch, exercising both capture outcomes. */
    int stream=dup(watch); CHECK(stream>=0 && !close(stream));
    CHECK(dup3(pair[0],stream,O_CLOEXEC)==stream && !close(pair[0]));
    CHECK(write(pair[1],"abc",3)==3 && !close(pair[1]));
    struct read_capture work={watch,stream};
    CHECK(!pthread_create(&thread,NULL,read_captured,&work));
    CHECK(!pthread_join(thread,NULL));
    CHECK(!close(watch) && !unlink(event) && !rmdir(path));
}
int test_read_capture_guest(const char *base) {
    /* Initialize Bionic threading and the optional table-owned export channel
     * before checking that repeated fallback IO does not grow the FD table. */
    read_capture_cycle(base);
    unsigned before=descriptors();
    for (unsigned i=0;i<8;++i) { read_capture_cycle(base); descriptor_balance(before); }
    puts("PASS read capture: non-leader watch, reused native stream, read/readv/ioctl, EOF and EBADF");
    return 0;
}
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
