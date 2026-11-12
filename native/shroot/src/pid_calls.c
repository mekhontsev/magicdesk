#include "interception.h"
#include "pid_calls.h"
#include "raw.h"
#include "fd_client.h"
#include "proc_control.h"
#include "fd_filter.h"
#include "descriptor_capture.h"
#include "resource_control.h"
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>

long md_process_query(long nr,const unsigned long *a) {
    struct md_adapter_input input=md_adapter_buffer_begin(MD_BUFFER_PROCESS_QUERY,0);
    if (!input.buffer) return input.value;
    const struct md_process_query *p=input.buffer;
    long result=input.value;
    if (!result && nr==SYS_get_robust_list) {
        /* Linux publishes the length first, even if writing the head fails. */
        result=md_write_memory((void *)a[2],&p->value[1],sizeof(uint64_t));
        if (!result) result=md_write_memory((void *)a[1],&p->value[0],sizeof(uint64_t));
    } else if (!result) {
        int value=(int)p->value[0];
        result=md_write_memory((void *)a[1],&value,sizeof(value));
    }
    long released=md_adapter_buffer_release();
    return result ? result : released;
}

long md_process_wait_result(long nr,const unsigned long *a) {
    long memory=md_adapter_buffer_take();
    if (memory<0) return memory;
    size_t size=nr==SYS_wait4 ? sizeof(int) : sizeof(siginfo_t);
    void *address=(void *)a[nr==SYS_wait4 ? 1 : 2];
    long result=md_read_memory((void *)memory,address,size);
    if (!result) {
        result=md_guest_buffer_control(MD_BUFFER_PROCESS_WAIT,0,0);
        if (result>=0) {
            long error=md_write_memory(address,(void *)memory,size);
            if (error) result=error;
        }
    }
    long released=md_adapter_buffer_release();
    return result<0 ? result : released<0 ? released : result;
}

long md_pid_terminal(const unsigned long *a) {
    int value=0;
    int writing=(unsigned)a[1]==TIOCSPGRP;
    long result=writing ? md_read_memory(&value,(void *)a[2],sizeof(value)) : 0;
    if (result) return result;
    if (writing && value>0) {
        long native=md_pid_native(value);
        if (native<=0) return native ? native : -ESRCH;
        value=(int)native;
    }
    result=md_fd_control(MD_FD_USE,0,0,0);
    if (result) return result;
    result=writing ? md_guest_native_call(SYS_ioctl,(unsigned long[6]){a[0],a[1],(uintptr_t)&value})
        : RAW3(ioctl,a[0],a[1],&value);
    if (!result && !writing) {
        long visible=value>0 ? md_pid_visible(value) : value;
        if (visible<0) result=visible;
        else { value=(int)visible; result=md_write_memory((void *)a[2],&value,sizeof(value)); }
    }
    long released=md_fd_control(MD_FD_RELEASE,0,0,0);
    return result ? result : released;
}

long md_pid_signal_group(int selector,int signal) {
    int last=0,sent=0;
    long failure=-ESRCH;
    for (;;) {
        long next=md_guest_resource_control(MD_GUEST_PID_SIGNAL_GROUP,(unsigned)selector,signal,last,0,0);
        if (next<=0) return sent ? 0 : next ? next : failure;
        last=(int)(next>>1);
        long result=next&1 ? 0 : RAW2(kill,last,signal);
        if (!result) sent=1;
        else if (result!=-ESRCH) failure=result;
    }
}
static long pidfd_capture(long state,struct md_pidfd_frame *frame,int fd) {
    static const struct md_descriptor_capture_protocol protocol={MD_BUFFER_PIDFD_ADMIT,
        MD_BUFFER_PIDFD_READY,MD_BUFFER_PIDFD_SENT,MD_BUFFER_PIDFD_OBSERVED,MD_PIDFD_DONE,NULL};
    state=md_descriptor_capture_inputs(state,&frame->capture,&fd,1,&protocol);
    return state==MD_PIDFD_DONE ? frame->result : state;
}
long md_pidfd_signal(const unsigned long *a) {
    siginfo_t info;
    long result=a[2] ? md_read_memory(&info,(void *)a[2],sizeof(info)) : 0;
    if (result) return result;
    if (a[2] && info.si_signo!=(int)a[1]) return -EINVAL;
    result=md_adapter_buffer_take();
    if (result<0) return result;
    struct md_pidfd_frame *frame=(void *)result;
    frame->info=a[2] ? info : (siginfo_t){0};
    result=md_fd_control(MD_FD_USE,0,0,0);
    if (result) { md_adapter_buffer_release(); return result; }
    result=pidfd_capture(md_guest_buffer_control(MD_BUFFER_PIDFD_SIGNAL,0,0),frame,(int)a[0]);
    if (!result) result=RAW4(pidfd_send_signal,(int)a[0],(int)a[1],a[2] ? &info : 0,(unsigned)a[3]);
    else if (result==1) result=0;
    long released=md_fd_control(MD_FD_RELEASE,0,0,0);
    md_adapter_buffer_release();
    return result ? result : released;
}
long md_pidfd_namespace(const unsigned long *a) {
    long result=md_adapter_buffer_take();
    if (result<0) return result;
    struct md_pidfd_frame *frame=(void *)result;
    result=md_fd_control(MD_FD_USE,0,0,0);
    if (result) { md_adapter_buffer_release(); return result; }
    result=pidfd_capture(md_guest_buffer_control(MD_BUFFER_PIDFD_NAMESPACE,a[0],a[1]),frame,(int)a[0]);
    int native=result==MD_CALL_NATIVE;
    if (native) result=RAW3(ioctl,a[0],a[1],a[2]);
    long released=md_fd_control(MD_FD_RELEASE,0,0,0);
    if (!result && released) result=released;
    if (result || native) { md_adapter_buffer_release(); return result; }
    result=md_proc_control_pidfd(frame,(int)a[0],(unsigned)a[1]);
    md_adapter_buffer_release();
    return result;
}
long md_setns(const unsigned long *a) {
    long result=md_adapter_buffer_take();
    if (result<0) return result;
    struct md_pidfd_frame *frame=(void *)result;
    result=md_fd_control(MD_FD_USE,0,0,0);
    if (!result) {
        result=pidfd_capture(md_guest_buffer_control(MD_BUFFER_PIDFD_NAMESPACE,a[0],a[1]),frame,(int)a[0]);
        long released=md_fd_control(MD_FD_RELEASE,0,0,0);
        if (!result) result=released;
    }
    md_adapter_buffer_release();
    return result;
}
