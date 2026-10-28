#define _GNU_SOURCE
#include "epoll_wait.h"
#include "event_wait.h"
#include <errno.h>
#include <limits.h>
#include <sys/syscall.h>

void md_epoll_wait_initialize(struct md_epoll_wait *w,const struct md_epoll_frame *frame) {
    if (w->initialized) return;
    w->initialized=1; w->deadline=INT64_MAX;
    if (frame->timed) {
        int64_t now=md_event_now();
        if (frame->timeout.tv_sec<(INT64_MAX-now-frame->timeout.tv_nsec)/1000000000LL)
            w->deadline=now+frame->timeout.tv_sec*1000000000LL+frame->timeout.tv_nsec;
    }
}
void md_epoll_wait_cancel(struct md_epoll_wait *w) {
    if (w->job.started) md_ipc_io_job_finish(&w->job,1);
}
int md_epoll_wait_observe(struct md_epoll_wait *w,struct md_control_epoll *q) {
    int descriptor=md_control_epoll_descriptor(q);
    if (!w->blocking || w->job.started || descriptor<0) return 0;
    w->sources[0]=(struct pollfd){descriptor,POLLIN,0};
    long args[5]={(long)w->sources,1,0,0,8};
    return md_ipc_io_job_start(&w->job,SYS_ppoll,args);
}
int md_epoll_wait_task(struct md_epoll_wait *w,struct md_control_epoll *q,
        struct md_epoll_frame *frame,uintptr_t address,long number,int descriptor,
        int completed,long native_result,struct md_task_call_arguments *call,long *out) {
    md_epoll_wait_initialize(w,frame);
    int capacity=frame->capacity;
    if (capacity<=0 || capacity>MD_EPOLL_EVENTS) { *out=-EINVAL; return 0; }
    if (completed) {
        md_epoll_wait_cancel(w);
        if (native_result<0) { *out=w->virtual_count ? w->virtual_count : native_result; return 0; }
        if (native_result>capacity-w->virtual_count) { *out=-EPROTO; return 0; }
        if (frame->native_output && native_result>0) { *out=native_result; return 0; }
        frame->native_output=0;
        int count=w->virtual_count+(int)native_result;
        if (!w->virtual_count && count<capacity) {
            int n=md_control_epoll_collect(q,frame->events+count,capacity-count);
            if (n<0 && !count) { *out=n; return 0; }
            if (n>0) count+=n;
        }
        if (count || md_event_now()>=w->deadline) { *out=count; return 0; }
        w->blocking=1; w->virtual_count=0;
    } else if (!w->task_started) {
        w->task_started=1;
        if (md_control_epoll_descriptor(q)<0) w->blocking=1;
        else if (md_control_epoll_turn(q)) {
            int n=md_control_epoll_collect(q,frame->events,(capacity+1)/2);
            if (n<0 || n==capacity) { *out=n; return 0; }
            w->virtual_count=n;
        }
    }
    int64_t remaining=w->blocking ? w->deadline==INT64_MAX ? INT64_MAX : w->deadline-md_event_now() : 0;
    if (remaining<0) remaining=0;
    frame->timeout=(struct timespec){remaining/1000000000LL,remaining%1000000000LL};
    int count=capacity-w->virtual_count;
    /* With no virtual transport, the kernel owns output validation and event
     * consumption together. Copying a staged native result afterwards would
     * lose a one-shot event when the application's pointer faults. Cold virtual
     * admission can interrupt this wait but cannot invent a native result. */
    frame->native_output=md_control_epoll_descriptor(q)<0;
    if (md_control_epoll_descriptor(q)>=0 && !w->virtual_count && count>1) count=(capacity+1)/2;
    unsigned long timeout=number==SYS_epoll_pwait2 ? (remaining==INT64_MAX ? 0
        : address+offsetof(struct md_epoll_frame,timeout)) : remaining==INT64_MAX ? (unsigned long)-1
        : remaining/1000000>=INT_MAX ? INT_MAX : (unsigned long)((remaining+999999)/1000000);
    *call=(struct md_task_call_arguments){number,{(unsigned long)descriptor,
        frame->native_output ? frame->output
            : address+offsetof(struct md_epoll_frame,events)+w->virtual_count*sizeof(struct epoll_event),
        (unsigned long)count,timeout,frame->masked ? address+offsetof(struct md_epoll_frame,mask) : 0,8}};
    int error=md_epoll_wait_observe(w,q);
    if (error) { *out=error; return 0; }
    return 1;
}
