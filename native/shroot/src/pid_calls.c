#include "interception.h"
#include "pid_calls.h"
#include "raw.h"
#include "fd_client.h"
#include "proc_control.h"
#include "fd_filter.h"
#include "descriptor_capture.h"
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>

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
    result=RAW3(ioctl,a[0],a[1],&value);
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
        long next=RAW4(prctl,MD_GUEST_PID_SIGNAL_GROUP,selector,signal,last);
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
    frame->code=a[2] ? info.si_code : 0;
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
    result=RAW5(prctl,MD_GUEST_PROC_FILE,MD_PROC_FILE_PIDFD_NAMESPACE,a[0],a[1],0);
    md_adapter_buffer_release();
    if (result>=0) {
        long armed=md_fd_filter_arm(MD_FD_SELECTOR_PROC,(int)result);
        if (armed<0) { RAW1(close,result); return armed; }
    }
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
