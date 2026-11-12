#include "epoll_calls.h"
#include "adapter_buffer.h"
#include "fs_buffer.h"
#include "raw.h"
#include "descriptor_capture.h"
#include "interception.h"
#include <errno.h>
#include <limits.h>

long md_epoll_call(long nr,const unsigned long *a) {
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    struct md_epoll_frame *f=(void *)memory;
    struct md_epoll_park parked;
    memset(f,0,sizeof(*f));
    long result=0;
    if (nr==SYS_epoll_ctl) {
        if ((int)a[1]!=EPOLL_CTL_DEL) result=md_read_memory(&f->event,(void *)a[3],sizeof(f->event));
    } else {
        f->output=a[1];
        if ((int)a[2]<=0 || a[2]>INT_MAX/sizeof(struct epoll_event)) result=-EINVAL;
        f->capacity=a[2]>MD_EPOLL_EVENTS ? MD_EPOLL_EVENTS : (int)a[2];
        if (nr==SYS_epoll_pwait2 && a[3]) {
            result=md_read_memory(&f->timeout,(void *)a[3],sizeof(f->timeout));
            f->timed=1;
            if (!result && (f->timeout.tv_sec<0 || f->timeout.tv_nsec<0 || f->timeout.tv_nsec>=1000000000)) result=-EINVAL;
        } else if (nr==SYS_epoll_pwait && (int)a[3]>=0) {
            f->timed=1; f->timeout=(struct timespec){(int)a[3]/1000,((int)a[3]%1000)*1000000L};
        }
        if (!result && a[4]) {
            if (a[5]!=sizeof(f->mask)) result=-EINVAL;
            else result=md_read_memory(&f->mask,(void *)a[4],sizeof(f->mask));
            f->masked=1;
        }
    }
    if (!result) result=md_guest_buffer_control(MD_BUFFER_EPOLL_BEGIN,0,0);
    if (result>0 && result!=MD_EPOLL_NATIVE && result!=MD_EPOLL_CAPTURED && result!=MD_EPOLL_DONE) {
        int fd=(int)a[nr==SYS_epoll_ctl ? 2 : 0];
        const struct md_descriptor_capture_protocol p={MD_FS_BUFFER_EPOLL_ADMIT,MD_BUFFER_EPOLL_CAPTURE_READY,
            MD_BUFFER_EPOLL_CAPTURE_SENT,MD_BUFFER_EPOLL_CAPTURE_OBSERVED,MD_EPOLL_CAPTURED,
            f->admission.number ? &f->admission : NULL};
        result=md_descriptor_capture_inputs(result,&f->capture,&fd,1,&p);
    }
    while (result==MD_EPOLL_CAPTURED || result==MD_EPOLL_LOAN || result==MD_EPOLL_PARK || result==MD_EPOLL_COPY) {
        if (result==MD_EPOLL_LOAN) {
            result=f->admission.number ? md_guest_buffer_replay(&f->admission)
                : md_guest_buffer_admit(MD_FS_BUFFER_EPOLL_LOAN);
            if (result<0) break;
        }
        int park=result==MD_EPOLL_PARK;
        int copying=result==MD_EPOLL_COPY;
        long copied=0;
        if (copying) {
            size_t size=(size_t)f->copy_count*sizeof(*f->events);
            void *output=(void *)(a[1]+(size_t)f->copy_offset*sizeof(*f->events));
            copied=f->copy_read ? md_read_memory(f->task_events,output,size)
                : md_write_memory(output,f->events,size);
        }
        if (park) parked=f->park;
        struct md_adapter_input reply=md_guest_buffer_start(MD_BUFFER_EPOLL_WAIT,
            park ? (uintptr_t)&parked : (uintptr_t)copied,park ? MD_EPOLL_PARK : copying ? MD_EPOLL_COPY : 0);
        result=reply.value;
        f=reply.buffer;
    }
    if (result==MD_EPOLL_DONE) result=f->result;
    if (result>0 && result!=MD_EPOLL_NATIVE && nr!=SYS_epoll_ctl) {
        if (result>f->capacity) result=-EPROTO;
        else if (!f->native_output
                && md_write_memory((void *)a[1],f->events,(size_t)result*sizeof(*f->events))) result=-EFAULT;
    }
    return md_adapter_buffer_finish(0,result==MD_EPOLL_NATIVE ? MD_CALL_NATIVE : result);
}
