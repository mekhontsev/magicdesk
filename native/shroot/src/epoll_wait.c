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
    if (w->job.started) md_ipc_io_job_cancel(&w->job);
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
        if (q && !w->virtual_count && count<capacity) {
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
    *call=(struct md_task_call_arguments){.number=number,.value={(unsigned long)descriptor,
        frame->native_output ? frame->output
            : address+offsetof(struct md_epoll_frame,events)+w->virtual_count*sizeof(struct epoll_event),
        (unsigned long)count,timeout,frame->masked ? address+offsetof(struct md_epoll_frame,mask) : 0,8}};
    int error=md_epoll_wait_observe(w,q);
    if (error) { *out=error; return 0; }
    return 1;
}
enum { TASK_QUEUE=1, NATIVE_QUEUE=2, QUEUES_WAIT=3, TASK_READ, TASK_WRITE, VIRTUAL_WRITE };
int md_epoll_wait_collecting(const struct md_epoll_wait *w) {
    return w->task_phase==TASK_QUEUE || w->task_phase==TASK_READ || w->task_phase==TASK_WRITE;
}
void md_epoll_wait_resample(struct md_epoll_wait *w) {
    w->task_phase=QUEUES_WAIT;
    w->task_sampled=0; w->task_count=0;
}
static int task_output(struct md_epoll_wait *w,struct md_epoll_frame *frame,int count,int reading) {
    frame->copy_count=count; frame->copy_offset=w->task_count; frame->copy_read=reading;
    return 2;
}
int md_epoll_wait_task_affine(struct md_epoll_wait *w,struct md_control_epoll *q,
        const struct md_control_actor *actor,struct md_epoll_frame *frame,uintptr_t address,
        int descriptor,int task_descriptor,int completed,long value,
        struct md_task_call_arguments *call,long *out) {
    md_epoll_wait_initialize(w,frame);
    int capacity=frame->capacity;
    if (capacity<=0 || capacity>MD_EPOLL_EVENTS) { *out=-EINVAL; return 0; }
    frame->native_output=1;
    w->task_copied=0;
    if (completed) {
        md_epoll_wait_cancel(w);
        if (w->task_phase==TASK_WRITE || w->task_phase==VIRTUAL_WRITE) {
            if (w->task_phase==TASK_WRITE) {
                md_control_epoll_task_delivery_finish(q,!value); w->task_copied=1;
            }
            if (value<0) { *out=w->task_count ? w->task_count : value; return 0; }
            w->task_count+=frame->copy_count;
            if (w->task_phase==VIRTUAL_WRITE) { *out=w->task_count; return 0; }
        } else if (value<0) {
            *out=w->task_count ? w->task_count : value; return 0;
        } else if (w->task_phase==QUEUES_WAIT) {
            w->task_sampled=w->task_count=0;
        } else {
            if (value>capacity-w->task_count) { *out=-EPROTO; return 0; }
            if (w->task_phase==TASK_QUEUE && value>0) {
                w->task_phase=TASK_READ;
                return task_output(w,frame,(int)value,1);
            }
            if (w->task_phase==TASK_QUEUE || w->task_phase==TASK_READ) {
                int native=w->task_phase==TASK_READ ? frame->copy_count : 0;
                value=md_control_epoll_task_collect(q,actor,frame->task_events,native,
                    frame->events,native ? native : capacity-w->task_count);
                if (value<0) { *out=w->task_count ? w->task_count : value; return 0; }
                if (value) {
                    w->task_phase=TASK_WRITE;
                    return task_output(w,frame,(int)value,0);
                }
                md_control_epoll_task_delivery_finish(q,1); w->task_copied=1;
            }
            w->task_count+=(int)value;
        }
    }
    if (!w->task_sampled) w->task_first=md_control_epoll_turn(q) ? TASK_QUEUE : NATIVE_QUEUE;
    if (w->task_count==capacity) { *out=w->task_count; return 0; }
    if (w->task_sampled!=(TASK_QUEUE|NATIVE_QUEUE)) {
        int next=w->task_sampled ? (TASK_QUEUE|NATIVE_QUEUE)^w->task_sampled : w->task_first;
        w->task_sampled|=next; w->task_phase=next; w->blocking=0;
        *call=(struct md_task_call_arguments){.number=SYS_epoll_pwait,.value={
            next==TASK_QUEUE ? task_descriptor : descriptor,
            frame->output+w->task_count*sizeof(struct epoll_event),
            capacity-w->task_count,0,frame->masked ? address+offsetof(struct md_epoll_frame,mask) : 0,8}};
        return 1;
    }
    int count=md_control_epoll_collect(q,frame->events,capacity-w->task_count);
    if (count<0) { *out=w->task_count ? w->task_count : count; return 0; }
    if (count) { w->task_phase=VIRTUAL_WRITE; return task_output(w,frame,count,0); }
    if (w->task_count || md_event_now()>=w->deadline) { *out=w->task_count; return 0; }
    int64_t remaining=w->deadline==INT64_MAX ? INT64_MAX : w->deadline-md_event_now();
    if (remaining<0) remaining=0;
    frame->park=(struct md_epoll_park){.timeout={remaining/1000000000LL,remaining%1000000000LL},
        .queues={{descriptor,POLLIN,0},{task_descriptor,POLLIN,0}},.mask=frame->mask};
    w->task_phase=QUEUES_WAIT; w->blocking=1;
    *call=(struct md_task_call_arguments){.number=SYS_ppoll,.value={
        address+offsetof(struct md_epoll_frame,park)+offsetof(struct md_epoll_park,queues),2,
        remaining==INT64_MAX ? 0 : address+offsetof(struct md_epoll_frame,park)+offsetof(struct md_epoll_park,timeout),
        frame->masked ? address+offsetof(struct md_epoll_frame,park)+offsetof(struct md_epoll_park,mask) : 0,8}};
    int error=md_epoll_wait_observe(w,q);
    if (error) { *out=error; return 0; }
    return 1;
}
