#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static double now(void) {
    struct timespec t; assert(!clock_gettime(CLOCK_MONOTONIC,&t));
    return t.tv_sec+t.tv_nsec*1e-9;
}
static double cpu(const struct rusage *r) {
    return r->ru_utime.tv_sec+r->ru_stime.tv_sec
        +(r->ru_utime.tv_usec+r->ru_stime.tv_usec)*1e-6;
}
int main(int argc,char **argv) {
    assert(argc==3 || argc==4);
    unsigned count=(unsigned)strtoul(argv[2],NULL,10);
    assert(count && count<=10000000);
    unsigned blocks=argc==4 ? (unsigned)strtoul(argv[3],NULL,10) : 1;
    assert(blocks && blocks<=64);
    int blocking=!strcmp(argv[1],"epoll-ready");
    int idle=!strcmp(argv[1],"idle"),epoll=blocking || !strcmp(argv[1],"epoll");
    int cycle=!strcmp(argv[1],"file-cycle"),opening=!strcmp(argv[1],"file-open");
    int files=cycle || opening || !strcmp(argv[1],"file-stat");
    int memory=!strcmp(argv[1],"memory");
    int duplicate=!strcmp(argv[1],"fd-dup"),metadata=!strcmp(argv[1],"fd-stat");
    int flags=!strcmp(argv[1],"fd-flags"),control=!strcmp(argv[1],"fd-ioctl");
    assert(files || memory || idle || epoll || duplicate || metadata || flags || control || !strcmp(argv[1],"poll"));
    assert(files || blocks==1);
    char directory[]="/tmp/md-readiness-XXXXXX";
    int dir=-1;
    if (files) {
        assert(mkdtemp(directory));
        dir=open(directory,O_RDONLY|O_DIRECTORY|O_CLOEXEC); assert(dir>=0);
        if (!cycle) {
            int file=openat(dir,"file",O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC,0600);
            assert(file>=0 && !close(file));
        }
    }
    int fd=eventfd(idle ? 0 : 1,EFD_CLOEXEC|EFD_NONBLOCK); assert(fd>=0);
    int queue=epoll_create1(EPOLL_CLOEXEC); assert(queue>=0);
    struct epoll_event event={.events=EPOLLIN,.data.u64=42},received;
    assert(!epoll_ctl(queue,EPOLL_CTL_ADD,fd,&event));
    struct pollfd p={.fd=fd,.events=POLLIN};
    if (getenv("MD_BENCH_FILTER")) {
        struct sock_filter instruction=BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW);
        struct sock_fprog filter={.len=1,.filter=&instruction};
        assert(!prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0));
        assert(!prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&filter));
    }
    struct {
        double elapsed, cpu;
        long voluntary, involuntary;
        int start_cpu, end_cpu;
    } samples[64];
    for (unsigned block=0;block<blocks;++block) {
        samples[block].start_cpu=sched_getcpu();
        struct rusage before,after; assert(!getrusage(RUSAGE_SELF,&before));
        double start=now();
        for (unsigned i=0;i<count;++i) {
            if (files) {
                if (cycle || opening) {
                    int file=openat(dir,"file",O_RDONLY|O_CLOEXEC|(cycle ? O_CREAT|O_EXCL : 0),0600);
                    assert(file>=0 && !close(file));
                    if (cycle) assert(!renameat(dir,"file",dir,"renamed") && !unlinkat(dir,"renamed",0));
                } else {
                    struct stat st; assert(!fstatat(dir,"file",&st,0) && S_ISREG(st.st_mode));
                }
            } else if (duplicate) {
                int copy=fcntl(fd,F_DUPFD_CLOEXEC,0);
                assert(copy>=0 && !close(copy));
            } else if (metadata) {
                struct stat st; assert(!fstat(fd,&st));
            } else if (flags) {
                assert(fcntl(fd,F_GETFL)>=0);
            } else if (control) {
                assert(!ioctl(fd,FIONBIO,&(int){1}));
            } else if (memory) {
                size_t size=(size_t)getpagesize()*16;
                void *address=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
                assert(address!=MAP_FAILED);
                *(volatile unsigned char *)address=(unsigned char)i;
                assert(!munmap(address,size));
            } else if (epoll) {
                assert(epoll_wait(queue,&received,1,blocking ? -1 : 0)==1);
                assert(received.events==EPOLLIN && received.data.u64==42);
            } else {
                struct timespec timeout={.tv_sec=idle ? 2 : 0};
                assert(ppoll(&p,1,&timeout,NULL)==(idle ? 0 : 1));
                assert(p.revents==(idle ? 0 : POLLIN));
            }
        }
        samples[block].elapsed=now()-start;
        assert(!getrusage(RUSAGE_SELF,&after));
        samples[block].cpu=cpu(&after)-cpu(&before);
        samples[block].voluntary=after.ru_nvcsw-before.ru_nvcsw;
        samples[block].involuntary=after.ru_nivcsw-before.ru_nivcsw;
        samples[block].end_cpu=sched_getcpu();
    }
    /* Output after the entire series: logging must not park the worker between blocks. */
    for (unsigned block=0;block<blocks;++block)
        printf("READINESS_BENCH {\"workload\":\"%s\",\"block\":%u,\"iterations\":%u,"
            "\"seconds\":%.9f,\"callerCpuSeconds\":%.9f,\"voluntarySwitches\":%ld,"
            "\"involuntarySwitches\":%ld,\"startCpu\":%d,\"endCpu\":%d}\n",
            argv[1],block,count,samples[block].elapsed,samples[block].cpu,
            samples[block].voluntary,samples[block].involuntary,
            samples[block].start_cpu,samples[block].end_cpu);
    assert(!close(queue) && !close(fd));
    if (files) {
        if (!cycle) assert(!unlinkat(dir,"file",0));
        assert(!close(dir) && !rmdir(directory));
    }
    return 0;
}
