#include "supervisor_transfer.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "interception_memory.h"
#include "task_copy.h"
#include "raw.h"
#include <stdlib.h>
#include <string.h>

struct supervisor_transfer {
    struct user_pt_regs saved;
    enum phase previous;
    struct md_interception_buffer buffer;
    uintptr_t stack,address;
    void *data;
    size_t size;
    enum md_guest_transfer_kind kind;
    void (*complete)(struct thread *,int);
    int allocating;
};

void transfer_discard(struct thread *t) {
    retry_clear(t,RETRY_TRANSFER);
    struct supervisor_transfer *copy=t->transfer;
    if (!copy) return;
    CHECK(!md_buffer_release(&copy->buffer));
    md_memory_stack_return(t->memory,copy->stack);
    free(copy); t->transfer=NULL;
}

static void transfer_finish(struct thread *t,int error) {
    struct supervisor_transfer *copy=t->transfer;
    void (*complete)(struct thread *,int)=copy->complete;
    enum phase previous=copy->previous;
    if (!set_registers(t->pid,&copy->saved)) return;
    transfer_discard(t); t->phase=previous;
    complete(t,error);
}

void transfer_progress(struct thread *t) {
    retry_clear(t,RETRY_TRANSFER);
    struct supervisor_transfer *copy=t->transfer;
    CHECK(copy && t->phase==TRANSFERRING && !copy->allocating);
    int error=copy->buffer.pool ? 0 : md_buffers_take_reserved(md_memory_buffers(t->memory),&copy->buffer);
    if (error==-EAGAIN) { retry_wait(t,RETRY_TRANSFER); return; }
    if (error) { transfer_finish(t,error); return; }
    if (!copy->stack) copy->stack=md_memory_stack_take(t->memory);
    struct user_pt_regs regs=copy->saved;
    if (!copy->stack) {
        regs.pc=ALLOCATE; copy->allocating=1;
    } else {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&copy->buffer,&span) && span.size>=sizeof(struct md_guest_transfer));
        struct md_guest_transfer *request=span.data;
        request->address=copy->address; request->size=copy->size; request->kind=copy->kind;
        if (copy->kind==MD_TRANSFER_WRITE) memcpy(request->data,copy->data,copy->size);
        regs.pc=abi.transfer; regs.sp=copy->stack; regs.regs[30]=abi.done_entry;
        regs.regs[0]=md_memory_buffer_address(t->memory,&copy->buffer);
        CHECK(regs.regs[0]);
    }
    if (set_registers(t->pid,&regs)) resume(t->pid,0);
}

void transfer_begin(struct thread *t,const struct user_pt_regs *regs,uintptr_t address,void *data,size_t size,
        enum md_guest_transfer_kind kind,void (*complete)(struct thread *,int)) {
    CHECK(!t->transfer && complete && regs && size<=sizeof(((struct md_guest_transfer *)0)->data));
    ssize_t copied=kind==MD_TRANSFER_STRING ? read_string(t->pid,address,data,size)
        : md_task_copy_direct(t->pid,address,data,size,kind==MD_TRANSFER_WRITE);
    if (copied!=-EPERM && copied!=-EACCES) {
        int error=copied<0 ? (int)copied : kind==MD_TRANSFER_STRING || copied==(ssize_t)size ? 0 : -EFAULT;
        complete(t,error); return;
    }
    struct supervisor_transfer *copy=calloc(1,sizeof(*copy));
    if (!copy) { complete(t,-ENOMEM); return; }
    *copy=(struct supervisor_transfer){.saved=*regs,.previous=t->phase,.address=address,
        .data=data,.size=size,.kind=kind,.complete=complete};
    t->transfer=copy; t->phase=TRANSFERRING;
    transfer_progress(t);
}

int transfer_checkpoint(struct thread *t,uintptr_t checkpoint,const struct user_pt_regs *regs) {
    struct supervisor_transfer *copy=t->transfer;
    if (!copy || t->phase!=TRANSFERRING) return 0;
    if (copy->allocating && checkpoint==ALLOCATED) {
        long value=(long)regs->regs[0];
        if (value<=0) { transfer_finish(t,value<0 ? (int)value : -ENOMEM); return 1; }
        CHECK(!(value&(md_page_size-1)));
        copy->stack=(uintptr_t)value; CHECK(!md_memory_stack_add(t->memory,copy->stack));
        copy->allocating=0; transfer_progress(t); return 1;
    }
    if (copy->allocating || checkpoint!=DONE) return 0;
    int error=(int)regs->regs[0];
    if (!error && copy->kind!=MD_TRANSFER_WRITE) {
        struct md_buffer_span span; CHECK(!md_buffer_span(&copy->buffer,&span));
        struct md_guest_transfer *request=span.data;
        size_t size=copy->size;
        if (copy->kind==MD_TRANSFER_STRING) {
            size=strnlen((char *)request->data,size);
            if (size==copy->size) error=-ENAMETOOLONG;
            else ++size;
        }
        if (!error) memcpy(copy->data,request->data,size);
    }
    transfer_finish(t,error); return 1;
}
