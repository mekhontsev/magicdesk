#define _GNU_SOURCE
#include "task_signal_control.h"
#include "task_pending.h"
#include <errno.h>
#include <string.h>
#include <sys/syscall.h>

static int advance(struct md_task_signal_control *c,int ready) {
    struct md_signal_operation *op=&c->operation;
    for (;;) {
        if (ready==MD_SIGNAL_PENDING) {
            uint64_t found;
            int result=md_task_pending_mask(c->trace,c->pid,op->pending_wanted,&found);
            ready=md_signal_operation_pending_done(op,result,found);
        } else if (!ready && op->copying) {
            struct md_signal_transfer *copy=&op->transfer;
            ssize_t copied=md_task_copy_direct(c->pid,copy->address,copy->data,copy->size,copy->writing);
            if (copied!=-EPERM && copied!=-EACCES) {
                ready=md_signal_operation_copy_done(op,copied);
                continue;
            }
            c->copy=(struct md_task_copy){0};
            int result=md_task_copy_begin(&c->copy,c->trace,c->pid,c->boundary,&c->original,
                c->call,c->returned,copy->address,copy->data,copy->size,copy->writing,
                op->scratch,op->peer,op->capacity);
            if (result==1) { ready=md_signal_operation_copy_done(op,c->copy.result); continue; }
            if (result<0 && !c->copy.call.stage) {
                ready=md_signal_operation_copy_done(op,result);
                continue;
            }
            return result;
        } else if (!ready && op->invoking) {
            int result=c->native.stage ? md_task_call_again(&c->native,&op->effect)
                : md_task_call_begin(&c->native,c->trace,c->pid,c->boundary,&c->original,
                    c->call,c->returned,&op->effect);
            /* A retained operation consumes the admitted entry exactly once. */
            if (!result) result=md_task_call_retire_entry(&c->native);
            return result;
        } else return ready;
    }
}
static int initialize(struct md_task_signal_control *c,long (*trace)(int,pid_t,void *,void *),
        pid_t pid,const struct user_pt_regs *r,uintptr_t call,uintptr_t returned) {
    if (!c || !trace || pid<=0 || !r) return -EINVAL;
    *c=(struct md_task_signal_control){.trace=trace,.pid=pid,.original=*r,
        .boundary=MD_TASK_CALL_SECCOMP,.call=call,.returned=returned};
    return 0;
}
int md_task_signal_wait_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        const struct seccomp_data *request,uintptr_t call,uintptr_t returned,
        struct md_signal_pending *pending,int *blocked,void (*translate)(void *,siginfo_t *),
        void *context,void *scratch,uintptr_t peer,size_t capacity) {
    if (!request || request->nr!=SYS_rt_sigtimedwait
            || capacity<sizeof(struct md_task_copy_scratch)+sizeof(siginfo_t)) return -EINVAL;
    int error=initialize(c,trace,pid,r,call,returned);
    return error ? error : advance(c,md_signal_operation_begin(&c->operation,request,NULL,pending,
        blocked,translate,context,scratch,peer,capacity));
}
int md_task_signal_descriptor_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        const struct seccomp_data *request,uintptr_t call,uintptr_t returned,
        struct md_signal_pending *pending,int *blocked,void (*translate)(void *,siginfo_t *),
        void *context,void *scratch,uintptr_t peer,size_t capacity) {
    if (!request || (request->nr!=SYS_signalfd4 && request->nr!=SYS_read && request->nr!=SYS_readv)
            || capacity<sizeof(struct md_task_copy_scratch)+sizeof(siginfo_t)) return -EINVAL;
    int error=initialize(c,trace,pid,r,call,returned);
    return error ? error : advance(c,md_signal_operation_begin(&c->operation,request,NULL,pending,
        blocked,translate,context,scratch,peer,capacity));
}
int md_task_signal_control_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        const struct seccomp_data *request,uintptr_t call,uintptr_t returned,
        struct md_signal_actions *actions,int *blocked,void *scratch,uintptr_t peer,size_t capacity) {
    if (!request || request->nr!=SYS_rt_sigaction
            || capacity<sizeof(struct md_task_copy_scratch)+sizeof(struct md_kernel_action)) return -EINVAL;
    int error=initialize(c,trace,pid,r,call,returned);
    return error ? error : advance(c,md_signal_operation_begin(&c->operation,request,actions,NULL,
        blocked,NULL,NULL,scratch,peer,capacity));
}
int md_task_signal_default_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        uintptr_t call,uintptr_t returned,struct md_signal_actions *actions,int *blocked,
        const struct md_kernel_action *action,void *scratch,uintptr_t peer,size_t capacity) {
    int error=initialize(c,trace,pid,r,call,returned);
    if (error) return error;
    c->boundary=MD_TASK_CALL_USER;
    return advance(c,md_signal_operation_default_begin(&c->operation,actions,blocked,action,scratch,peer,capacity));
}
int md_task_signal_wait_entry_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        uintptr_t call,uintptr_t returned,struct md_signal_actions *actions,int *blocked,
        const struct md_kernel_action *action,const struct md_signal_wait_view *wait,
        void *scratch,uintptr_t peer,size_t capacity) {
    if (capacity<sizeof(struct md_task_copy_scratch)+sizeof(uint64_t)) return -EINVAL;
    int error=initialize(c,trace,pid,r,call,returned);
    if (error) return error;
    c->boundary=MD_TASK_CALL_USER;
    return advance(c,md_signal_operation_entry_begin(&c->operation,r->regs[2],(int)r->regs[0],
        actions,blocked,action,wait,scratch,peer,capacity));
}
int md_task_signal_control_event(struct md_task_signal_control *c,int status) {
    if (c->operation.copying) {
        int result=md_task_copy_event(&c->copy,status);
        if (result!=1) return result;
        return advance(c,md_signal_operation_copy_done(&c->operation,c->copy.result));
    }
    if (c->operation.invoking) {
        int result=md_task_call_event(&c->native,status);
        if (result!=1) return result;
        c->boundary=c->native.boundary;
        return advance(c,md_signal_operation_effect_done(&c->operation,c->native.result));
    }
    return -EINVAL;
}
int md_task_signal_wait_park(struct md_task_signal_control *c,uintptr_t page) {
    return c ? advance(c,md_signal_operation_park(&c->operation,page)) : -EINVAL;
}
int md_task_signal_control_continue(struct md_task_signal_control *c) {
    return c ? advance(c,md_signal_operation_continue(&c->operation)) : -EINVAL;
}
int md_task_signal_descriptor_admit(struct md_task_signal_control *c,struct md_signal_fd *file) {
    return c ? advance(c,md_signal_operation_descriptor_admit(&c->operation,file)) : -EINVAL;
}
int md_task_signal_control_wake(struct md_task_signal_control *c) {
    return c && md_signal_operation_wake(&c->operation) ? md_task_call_interrupt(&c->native) : 0;
}
int md_task_signal_control_parked(const struct md_task_signal_control *c) {
    return c && md_signal_operation_parked(&c->operation) && c->native.boundary==MD_TASK_CALL_EXIT;
}
void md_task_signal_control_quiesced(struct md_task_signal_control *c) {
    if (md_task_signal_control_parked(c)) md_signal_operation_quiesced(&c->operation);
}
int md_task_signal_descriptor_changed(struct md_task_signal_control *c,const struct md_signal_fd *file) {
    return c && md_signal_operation_descriptor_changed(&c->operation,file) ? md_task_call_interrupt(&c->native) : 0;
}
void md_task_signal_control_dispose(struct md_task_signal_control *c) {
    md_signal_operation_dispose(&c->operation);
}
struct md_task_call *md_task_signal_control_call(struct md_task_signal_control *c) {
    return c->operation.copying ? &c->copy.call : c->operation.invoking ? &c->native : NULL;
}
