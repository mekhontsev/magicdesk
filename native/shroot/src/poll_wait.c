#define _GNU_SOURCE
#include "poll_wait.h"
#include "epoll_wait.h"
#include "event_wait.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <sys/syscall.h>

struct item { struct pollfd poll; struct md_fd_object *object; };
struct md_poll_wait {
    unsigned count,loaded;
    int virtual,select;
    struct item *items;
    struct pollfd *sources;
};
struct md_poll_wait *md_poll_wait_new(unsigned count,int select) {
    struct md_poll_wait *p=calloc(1,sizeof(*p));
    if (!p) return NULL;
    p->count=count; p->select=select;
    p->items=calloc(count ? count : 1,sizeof(*p->items));
    p->sources=calloc(count ? count : 1,sizeof(*p->sources));
    if (!p->items || !p->sources) { md_poll_wait_free(p); return NULL; }
    return p;
}
void md_poll_wait_free(struct md_poll_wait *p) {
    if (!p) return;
    for (unsigned i=0;i<p->loaded;++i) md_fd_object_release(p->items[i].object);
    free(p->items); free(p->sources); free(p);
}
int md_poll_wait_load(struct md_poll_wait *p,struct md_fd_origins *files,const struct md_poll_frame *f) {
    if (f->offset!=p->loaded || f->count>MD_POLL_CHUNK || f->count>p->count-p->loaded) return -EINVAL;
    for (unsigned i=0;i<f->count;++i) {
        struct item *item=&p->items[p->loaded++];
        item->poll=f->items[i];
        if (item->poll.revents && item->poll.revents!=POLLNVAL) return -EINVAL;
        if (p->select && item->poll.revents) return -EBADF;
        if (item->poll.fd<0 || item->poll.revents) continue;
        item->object=md_fd_origins_object(files,item->poll.fd,NULL);
        struct md_control_file ready=md_fd_object_readiness(item->object);
        if (ready.ops && ready.ops->poll) p->virtual=1;
    }
    return 0;
}
int md_poll_wait_virtual(const struct md_poll_wait *p) { return p->virtual; }
int md_poll_wait_copy(const struct md_poll_wait *p,struct md_poll_frame *f,unsigned offset) {
    if (offset>p->count) return -EINVAL;
    f->offset=offset; f->count=p->count-offset;
    if (f->count>MD_POLL_CHUNK) f->count=MD_POLL_CHUNK;
    for (unsigned i=0;i<f->count;++i) f->items[i]=p->items[offset+i].poll;
    return 0;
}
int md_poll_wait_interrupted(struct md_poll_wait *p) {
    int count=0,error=0;
    for (unsigned i=0;i<p->count;++i) {
        struct item *item=&p->items[i];
        item->poll.revents=0;
        struct md_control_file provider=md_fd_object_readiness(item->object);
        if (!provider.ops || !provider.ops->poll) continue;
        long mask=provider.ops->poll(provider.context);
        if (mask<0) { error=(int)mask; continue; }
        item->poll.revents=(short)mask&(item->poll.events|POLLERR|POLLHUP);
        count+=!!item->poll.revents;
    }
    return count ? count : error;
}
int md_poll_wait_task(struct md_poll_wait *p,struct md_epoll_wait *wait,struct md_poll_frame *f,
        uintptr_t address,int completed,long value,struct md_task_call_arguments *call,long *result) {
    md_epoll_wait_initialize(wait,&f->wait);
    struct pollfd *native=md_poll_native(f);
    unsigned words=(p->count+63U)/64;
    unsigned long *sets=(void *)(native+p->count);
    if (completed && value<0) { *result=value; return 0; }
    unsigned ready=0; int probe=0;
    for (unsigned i=0;i<p->count;++i) {
        struct item *item=&p->items[i];
        struct md_control_file provider=md_fd_object_readiness(item->object);
        p->sources[i]=(struct pollfd){-1,0,0};
        if (provider.ops && provider.ops->poll) {
            long mask=provider.ops->poll(provider.context);
            if (mask<0) { *result=mask; return 0; }
            if (!provider.ops->poll_native || !provider.ops->poll_native(provider.context)) {
                /* A native probe must not erase an already consumed virtual
                 * event (for example a proc-sysctl generation notification). */
                short pending=completed ? item->poll.revents : 0;
                item->poll.revents=pending|((short)mask&(item->poll.events|POLLERR|POLLHUP));
                int fd=-1,error=provider.ops->poll_wait_descriptor
                    ? provider.ops->poll_wait_descriptor(provider.context,&fd)
                    : provider.ops->poll_descriptor ? provider.ops->poll_descriptor(provider.context,&fd) : 0;
                if (error) { *result=error; return 0; }
                p->sources[i]=(struct pollfd){fd,POLLIN,0};
                native[i]=(struct pollfd){-1,0,0};
                ready+=!!item->poll.revents; continue;
            }
            if (completed && native[i].fd!=item->poll.fd) probe=1;
        }
        if (completed && native[i].fd==item->poll.fd && value>0 && item->poll.fd>=0 && item->poll.revents!=POLLNVAL) {
            if (!p->select) item->poll.revents=native[i].revents;
            else {
                short mask=0;
                for (unsigned set=0;set<3;++set)
                    if (sets[set*words+i/64]&(1UL<<(i%64))) mask|=set==0 ? POLLIN : set==1 ? POLLOUT : POLLPRI;
                item->poll.revents=mask;
            }
        } else if (!completed || native[i].fd!=item->poll.fd) {
            native[i]=item->poll; native[i].revents=0;
            item->poll.revents=0;
        }
        ready+=!!item->poll.revents;
    }
    if (completed && !probe && (ready || md_event_now()>=wait->deadline)) { *result=ready; return 0; }
    wait->blocking=completed && !ready && !probe;
    wait->virtual_count=ready;
    int64_t remaining=wait->blocking ? wait->deadline==INT64_MAX ? INT64_MAX : wait->deadline-md_event_now() : 0;
    if (remaining<0) remaining=0;
    f->wait.timeout=(struct timespec){remaining/1000000000LL,remaining%1000000000LL};
    uintptr_t timeout=remaining==INT64_MAX ? 0 : address+offsetof(struct md_poll_frame,wait.timeout);
    uintptr_t mask=f->wait.masked ? address+offsetof(struct md_poll_frame,wait.mask) : 0;
    uintptr_t vector=address+sizeof(*f);
    if (p->select) {
        memset(sets,0,3*words*sizeof(*sets));
        for (unsigned i=0;i<p->count;++i) if (native[i].fd>=0) {
            short events=native[i].events;
            for (unsigned set=0;set<3;++set) if (events&(set==0 ? POLLIN : set==1 ? POLLOUT : POLLPRI))
                sets[set*words+i/64]|=1UL<<(i%64);
        }
        uintptr_t bits=vector+p->count*sizeof(*native);
        f->signal[0]=mask; f->signal[1]=8;
        *call=(struct md_task_call_arguments){SYS_pselect6,{p->count,bits,bits+words*sizeof(*sets),
            bits+2*words*sizeof(*sets),timeout,mask ? address+offsetof(struct md_poll_frame,signal) : 0}};
    } else *call=(struct md_task_call_arguments){SYS_ppoll,{vector,p->count,timeout,mask,8}};
    if (wait->blocking) {
        /* EVENT_WAIT: provider readiness only. The caller's native wait owns
         * its signal mask and deadline; its completion cancels this helper. */
        long args[5]={(long)p->sources,p->count,0,0,8};
        int error=md_ipc_io_job_start(&wait->job,SYS_ppoll,args);
        if (error) { *result=error; return 0; }
    }
    return 1;
}
