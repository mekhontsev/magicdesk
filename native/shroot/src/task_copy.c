#define _GNU_SOURCE
#include "task_copy.h"
#include <errno.h>
#include <limits.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

ssize_t md_task_copy_direct(pid_t pid,uintptr_t address,void *data,size_t size,int writing) {
    if (address>UINTPTR_MAX-size || size>SSIZE_MAX) return -EFAULT;
    if (!size) return 0;
    struct iovec local={data,size},remote={(void *)address,size};
    ssize_t result=writing ? process_vm_writev(pid,&local,1,&remote,1,0)
        : process_vm_readv(pid,&local,1,&remote,1,0);
    return result<0 ? -errno : result;
}
int md_task_copy_begin(struct md_task_copy *copy,long (*trace)(int,pid_t,void *,void *),
        pid_t pid,enum md_task_call_boundary boundary,const struct user_pt_regs *regs,
        uintptr_t call,uintptr_t returned,uintptr_t address,void *data,size_t size,int writing,
        void *scratch,uintptr_t peer,size_t capacity) {
    if (!copy || !trace || !regs || !scratch || !peer || (size && !data)) return -EINVAL;
    if (copy->call.stage || copy->scratch) return -EBUSY;
    if (capacity<sizeof(struct md_task_copy_scratch)
            || size>capacity-sizeof(struct md_task_copy_scratch)) return -ENOSPC;
    if (peer>UINTPTR_MAX-capacity || address>UINTPTR_MAX-size || size>SSIZE_MAX) return -EFAULT;
    struct md_task_copy next={.scratch=scratch,.data=data,.size=size,.writing=!!writing};
    next.scratch->local=(struct iovec){(void *)(peer+offsetof(struct md_task_copy_scratch,bytes)),size};
    next.scratch->remote=(struct iovec){(void *)address,size};
    if (writing && size) memcpy(next.scratch->bytes,data,size);
    struct md_task_call_arguments args={.number=writing ? SYS_process_vm_writev : SYS_process_vm_readv,
        .value={pid,peer+offsetof(struct md_task_copy_scratch,local),1,
            peer+offsetof(struct md_task_copy_scratch,remote),1,0}};
    int error=md_task_call_begin(&next.call,trace,pid,boundary,regs,call,returned,&args);
    /* A failed begin can still own an already stopped/cancelled continuation. */
    if (!error || next.call.stage) *copy=next;
    return error;
}
int md_task_copy_event(struct md_task_copy *copy,int status) {
    if (!copy || !copy->scratch) return -EINVAL;
    int result=md_task_call_event(&copy->call,status);
    if (result!=1) return result;
    copy->result=copy->call.result;
    if (copy->result>0) {
        if ((size_t)copy->result>copy->size) copy->result=-EPROTO;
        else if (!copy->writing) memcpy(copy->data,copy->scratch->bytes,(size_t)copy->result);
    }
    copy->scratch=NULL; copy->data=NULL;
    return 1;
}
