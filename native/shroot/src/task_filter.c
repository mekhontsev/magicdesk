#define _GNU_SOURCE
#include "task_filter.h"
#include <errno.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

int md_task_filter_begin(struct md_task_filter *f,long (*operation)(int,pid_t,void *,void *),
        pid_t actor,enum md_task_call_boundary boundary,const struct user_pt_regs *resume,
        uintptr_t call,uintptr_t returned,struct md_task_filter_image *image,uintptr_t image_address,
        enum md_fd_selector_kind kind,int fd,const struct md_fd_filter_gates *gates) {
    if (!f || !image || !image_address || image_address>UINTPTR_MAX-sizeof(*image)) return -EINVAL;
    if (f->task.stage) return -EBUSY;
    int error=md_fd_filter_build(kind,fd,gates,&image->code);
    if (error) return error;
    return md_task_filter_begin_program(f,operation,actor,boundary,resume,call,returned,image,image_address);
}
int md_task_filter_begin_program(struct md_task_filter *f,long (*operation)(int,pid_t,void *,void *),
        pid_t actor,enum md_task_call_boundary boundary,const struct user_pt_regs *resume,
        uintptr_t call,uintptr_t returned,struct md_task_filter_image *image,uintptr_t image_address) {
    if (!f || !image || !image_address || image_address>UINTPTR_MAX-sizeof(*image)
            || !image->code.count || image->code.count>MD_FD_FILTER_INSTRUCTIONS) return -EINVAL;
    if (f->task.stage) return -EBUSY;
    image->program=(struct sock_fprog){image->code.count,
        (void *)(image_address+offsetof(struct md_task_filter_image,code.code))};
    *f=(struct md_task_filter){.program=image_address+offsetof(struct md_task_filter_image,program)};
    struct md_task_call_arguments args={.number=SYS_seccomp,
        .value={SECCOMP_SET_MODE_FILTER,SECCOMP_FILTER_FLAG_TSYNC,f->program}};
    return md_task_call_begin(&f->task,operation,actor,boundary,resume,call,returned,&args);
}
int md_task_filter_retry_thread(struct md_task_filter *f) {
    if (!f || f->task.result!=-EPERM || f->thread_only) return -EINVAL;
    struct md_task_call_arguments args={.number=SYS_prctl,
        .value={PR_SET_SECCOMP,SECCOMP_MODE_FILTER,f->program}};
    int error=md_task_call_again(&f->task,&args);
    if (!error) f->thread_only=1;
    return error;
}
int md_task_filter_event(struct md_task_filter *f,int status) {
    return f ? md_task_call_event(&f->task,status) : -EINVAL;
}
