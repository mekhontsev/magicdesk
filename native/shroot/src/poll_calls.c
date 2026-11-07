#include "poll_calls.h"
#include "adapter_buffer.h"
#include "raw.h"
#include "interception.h"
#include <errno.h>
#include <limits.h>

static long input(struct md_poll_frame *f,const unsigned long *a) {
    if (!f->select) {
        long error=md_read_memory(f->items,(void *)(a[0]+f->offset*sizeof(struct pollfd)),
            f->count*sizeof(struct pollfd));
        if (error) return error;
    } else {
        for (unsigned i=0;i<f->count;++i) {
            unsigned fd=f->offset+i;
            short events=0;
            for (unsigned set=0;set<3;++set) if (a[1+set]) {
                unsigned long bits;
                long error=md_read_memory(&bits,(void *)(a[1+set]+(fd/64)*sizeof(bits)),sizeof(bits));
                if (error) return error;
                if (bits&(1UL<<(fd%64))) events|=set==0 ? POLLIN|POLLRDNORM|POLLRDBAND
                    : set==1 ? POLLOUT|POLLWRNORM|POLLWRBAND : POLLPRI;
            }
            f->items[i]=(struct pollfd){events ? (int)fd : -1,events,0};
        }
    }
    for (unsigned i=0;i<f->count;++i) f->items[i].revents=0;
    return 0;
}
static long output(struct md_poll_frame *f,const unsigned long *a,long *selected) {
    if (!f->select) {
        for (unsigned i=0;i<f->count;++i) {
            long error=md_write_memory((void *)(a[0]+(f->offset+i)*sizeof(struct pollfd)
                +offsetof(struct pollfd,revents)),&f->items[i].revents,sizeof(short));
            if (error) return error;
        }
        return 0;
    }
    for (unsigned set=0;set<3;++set) if (a[set+1]) {
        unsigned long bits[MD_POLL_CHUNK/64]={0};
        for (unsigned i=0;i<f->count;++i) {
            short asked=f->items[i].events,ready=f->items[i].revents;
            int match=set==0 ? (asked&POLLIN) && (ready&(POLLIN|POLLRDNORM|POLLRDBAND|POLLHUP|POLLERR))
                : set==1 ? (asked&POLLOUT) && (ready&(POLLOUT|POLLWRNORM|POLLWRBAND|POLLERR))
                : (asked&POLLPRI) && (ready&POLLPRI);
            if (match) { bits[i/64]|=1UL<<(i%64); ++*selected; }
        }
        long error=md_write_memory((void *)(a[set+1]+f->offset/8),bits,((f->count+63)/64)*sizeof(*bits));
        if (error) return error;
    }
    return 0;
}
long md_poll_call(long nr,const unsigned long *a,unsigned long prepared) {
    int select=nr==SYS_pselect6;
    int suspend=nr==SYS_rt_sigsuspend;
    unsigned long count=suspend ? 0 : select ? a[0] : a[1];
    if (count>INT_MAX) return -EINVAL;
    long result=0;
    long memory=prepared ? (long)prepared : md_guest_buffer_control(MD_BUFFER_TAKE,md_poll_bytes(count),0);
    if (memory<0) return memory;
    struct md_poll_frame *f=(void *)memory;
    memset(f,0,sizeof(*f)); f->select=select; f->total=count;
    unsigned long timeout=suspend ? 0 : select ? a[4] : a[2];
    unsigned long mask=suspend ? a[0] : select ? 0 : a[3],size=suspend ? a[1] : select ? 0 : a[4];
    if (suspend && size!=8) result=-EINVAL;
    if (suspend && !mask) result=-EFAULT;
    if (select && a[5]) {
        struct { unsigned long mask,size; } sig;
        result=md_read_memory(&sig,(void *)a[5],sizeof(sig));
        mask=sig.mask; size=sig.size;
    }
    if (!result && mask) {
        if (size!=8) result=-EINVAL;
        else result=md_read_memory(&f->wait.mask,(void *)mask,8);
        f->wait.masked=1;
    }
    if (!result && timeout) {
        result=md_read_memory(&f->wait.timeout,(void *)timeout,sizeof(f->wait.timeout));
        f->wait.timed=1;
        if (!result && (f->wait.timeout.tv_sec<0 || f->wait.timeout.tv_nsec<0
                || f->wait.timeout.tv_nsec>=1000000000)) result=-EINVAL;
    }
    struct timespec original=f->wait.timeout;
    f->count=count>MD_POLL_CHUNK ? MD_POLL_CHUNK : count;
    if (!result) result=input(f,a);
    if (!result) result=md_guest_buffer_control(MD_BUFFER_POLL_BEGIN,0,0);
    while (result==MD_POLL_LOAD) {
        result=input(f,a);
        if (!result) result=md_guest_buffer_control(MD_BUFFER_POLL_LOAD,0,0);
    }
    if (result==MD_EPOLL_CAPTURED) result=md_guest_buffer_control(MD_BUFFER_EPOLL_WAIT,0,0);
    if (result>=0 && result!=MD_EPOLL_NATIVE) {
        long selected=0;
        for (unsigned offset=0;offset<count;offset+=MD_POLL_CHUNK) {
            long error=md_guest_buffer_control(MD_BUFFER_POLL_COPY,offset,0);
            if (!error) error=output(f,a,&selected);
            if (error) { result=error; break; }
        }
        if (select && result>=0) result=selected;
    }
    if (result!=MD_EPOLL_NATIVE && (result>=0 || result==-EINTR) && timeout) {
        int64_t elapsed=f->elapsed;
        struct timespec remaining=original;
        remaining.tv_sec-=elapsed/1000000000LL; remaining.tv_nsec-=elapsed%1000000000LL;
        if (remaining.tv_nsec<0) { --remaining.tv_sec; remaining.tv_nsec+=1000000000; }
        if (remaining.tv_sec<0) remaining=(struct timespec){0};
        /* Like the native syscall, an unwritable timeout does not lose ready events. */
        md_write_memory((void *)timeout,&remaining,sizeof(remaining));
    }
    return md_adapter_buffer_finish(0,result==MD_EPOLL_NATIVE ? MD_CALL_NATIVE : result);
}
