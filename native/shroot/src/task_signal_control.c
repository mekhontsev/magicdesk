#define _GNU_SOURCE
#include "task_signal_control.h"
#include "signal_view.h"
#include "task_pending.h"
#include "event_wait.h"
#include "syscall_result.h"
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <limits.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <ucontext.h>

struct exchange { struct md_kernel_action requested, previous; };
enum { INPUT, EFFECT, OUTPUT, DONE };
static int finished(struct md_task_signal_control *c,long result) {
    if (c->descriptor==2 && c->descriptor_copied) result=(long)c->descriptor_copied;
    c->result=result; c->stage=DONE; return 1;
}
static int transfer(struct md_task_signal_control *c,uintptr_t address,void *data,size_t size,int writing) {
    ssize_t copied=md_task_copy_direct(c->pid,address,data,size,writing);
    if (copied!=-EPERM && copied!=-EACCES)
        return copied==(ssize_t)size ? 1 : copied<0 ? (int)copied : -EFAULT;
    c->copy=(struct md_task_copy){0};
    int error=md_task_copy_begin(&c->copy,c->trace,c->pid,c->boundary,&c->original,
        c->call,c->returned,address,data,size,writing,c->scratch,c->peer,c->capacity);
    if (!error || c->copy.call.stage) c->copying=1;
    return error;
}
static int invoke(struct md_task_signal_control *c,const struct md_task_call_arguments *args) {
    int error=c->native.stage ? md_task_call_again(&c->native,args)
        : md_task_call_begin(&c->native,c->trace,c->pid,c->boundary,&c->original,c->call,c->returned,args);
    if (!error || c->native.stage) c->invoking=1;
    /* The signal owner consumes the admitted application call. Borrowed effects
     * may finish after TSYNC, so never replay that call through a newer filter. */
    if (!error) error=md_task_call_retire_entry(&c->native);
    return error;
}
enum { WAIT_SET, WAIT_TIMEOUT, WAIT_PROBE, WAIT_NATIVE, WAIT_OUTPUT, WAIT_RECORD, WAIT_COPY, WAIT_DONE,
    WAIT_ALLOCATE, WAIT_PARK_COPY, WAIT_PARKED, WAIT_FETCH };
enum { FD_INPUT, FD_CAPTURE, FD_ADMISSION, FD_FLAGS, FD_EFFECT, FD_COMPLETE, FD_VECTOR_DONE };
static int descriptor_progress(struct md_task_signal_control *c) {
    const struct seccomp_data *r=&c->request;
    if (c->descriptor_stage==FD_INPUT) {
        c->descriptor_stage=FD_CAPTURE;
        if (c->descriptor==1) {
            if (r->args[2]!=sizeof(uint64_t)) return finished(c,-EINVAL);
            int copied=transfer(c,r->args[1],&c->wait_set,sizeof(c->wait_set),0);
            if (copied<0) return c->copying ? copied : finished(c,copied);
            if (!copied) return 0;
        }
    }
    if (c->descriptor_stage==FD_COMPLETE && c->descriptor==2) {
        if (c->descriptor_result<0 || c->passthrough) return finished(c,c->descriptor_result);
        c->descriptor_nonblock=!!(c->descriptor_result&O_NONBLOCK);
        c->descriptor_stage=FD_VECTOR_DONE;
        if (r->nr==SYS_readv) {
            if (r->args[2]>1024) return finished(c,-EINVAL);
            c->vector_count=(unsigned)r->args[2];
            if (!c->vector_count) return finished(c,0);
            c->vectors=calloc(c->vector_count,sizeof(*c->vectors));
            if (!c->vectors) return finished(c,-ENOMEM);
            int copied=transfer(c,r->args[1],c->vectors,c->vector_count*sizeof(*c->vectors),0);
            if (copied<0) return c->copying ? copied : finished(c,copied);
            if (!copied) return 0;
        } else {
            c->scalar=(struct iovec){(void *)r->args[1],r->args[2]}; c->vector_count=1;
        }
    }
    if (c->descriptor_stage==FD_VECTOR_DONE) {
        struct iovec *vectors=c->vectors ? c->vectors : &c->scalar;
        size_t maximum=(size_t)INT_MAX&~((size_t)getpagesize()-1),total=0;
        for (unsigned i=0;i<c->vector_count;++i) {
            if (vectors[i].iov_len>SSIZE_MAX) return finished(c,-EINVAL);
            if ((uintptr_t)vectors[i].iov_base>UINTPTR_MAX-vectors[i].iov_len) return finished(c,-EFAULT);
            if (vectors[i].iov_len>maximum-total) vectors[i].iov_len=maximum-total;
            total+=vectors[i].iov_len;
        }
        c->descriptor_available=total;
        if (total<sizeof(c->record)) return finished(c,-EINVAL);
        c->waiting=1; c->stage=WAIT_PROBE;
        return md_task_signal_control_continue(c);
    }
    if (c->descriptor_stage==FD_CAPTURE) {
        if (c->descriptor==1 && ((unsigned)r->args[3]&~(SFD_CLOEXEC|SFD_NONBLOCK))) return finished(c,-EINVAL);
        c->descriptor_stage=FD_ADMISSION;
        return MD_SIGNAL_FD_ACQUIRE;
    }
    if (c->descriptor_stage==FD_COMPLETE) {
        return finished(c,c->descriptor_result);
    }
    return -EINVAL;
}
struct pending_mask { uint64_t wanted,found; };
static int pending_mask_visit(void *context,const siginfo_t *info) {
    struct pending_mask *p=context;
    if (info->si_signo>0 && info->si_signo<NSIG)
        p->found|=p->wanted&(UINT64_C(1)<<(info->si_signo-1));
    return p->found==p->wanted;
}
static int private_pending(struct md_task_signal_control *c,uint64_t wanted,uint64_t *out) {
    struct pending_mask p={.wanted=wanted};
    int result=wanted ? md_task_pending_scan(c->trace,c->pid,pending_mask_visit,&p) : 0;
    *out=p.found;
    return result<0 ? result : 0;
}
static int wait_probe(struct md_task_signal_control *c,uint64_t set) {
    struct md_signal_wait_exchange *exchange=c->scratch;
    *exchange=(struct md_signal_wait_exchange){.set=set};
    struct md_task_call_arguments args={.number=SYS_rt_sigtimedwait,
        .value={c->peer+offsetof(struct md_signal_wait_exchange,set),c->peer+offsetof(struct md_signal_wait_exchange,info),
            c->peer+offsetof(struct md_signal_wait_exchange,timeout),sizeof(uint64_t)}};
    return invoke(c,&args);
}
static int wait_progress(struct md_task_signal_control *c) {
    const struct seccomp_data *r=&c->request;
    for (;;) {
    if (c->stage==WAIT_PARK_COPY) { c->stage=WAIT_PARKED; return MD_SIGNAL_WAIT_PARKED; }
    if (c->stage==WAIT_FETCH) {
        c->stage=WAIT_OUTPUT;
        int copied=transfer(c,c->wait_peer+offsetof(struct md_signal_wait_exchange,info),
            &c->wait_info,sizeof(c->wait_info),0);
        if (copied<0) return c->copying ? copied : finished(c,copied);
        if (!copied) return 0;
    }
    if (c->stage==WAIT_SET) {
        if (r->args[3]!=sizeof(uint64_t)) return finished(c,-EINVAL);
        ++c->stage;
        int copied=transfer(c,r->args[0],&c->wait_set,sizeof(c->wait_set),0);
        if (copied<0) return c->copying ? copied : finished(c,copied);
        if (!copied) return 0;
    }
    if (c->stage==WAIT_TIMEOUT) {
        ++c->stage;
        if (r->args[2]) {
            int copied=transfer(c,r->args[2],&c->wait_timeout,sizeof(c->wait_timeout),0);
            if (copied<0) return c->copying ? copied : finished(c,copied);
            if (!copied) return 0;
        }
    }
    if (c->stage==WAIT_PROBE) {
        if (c->descriptor==2) {
            if (c->descriptor_available-c->descriptor_copied<sizeof(c->record)) return finished(c,0);
            c->wait_set=md_signal_fd_mask(c->signal_file);
        }
        if (c->wait_timeout.tv_sec<0 || c->wait_timeout.tv_nsec<0
                || c->wait_timeout.tv_nsec>=1000000000) return finished(c,-EINVAL);
        int pending=md_signal_pending_peek(c->pending_owner,&c->selected);
        if (!(c->wait_set&MD_SIGNAL_SYS_BIT) || !pending) {
            if (pending && !*c->blocked) {
                c->wait_interrupt=1;
                return wait_probe(c,c->wait_set);
            }
            c->stage=WAIT_NATIVE;
            c->wait_native=1;
            return MD_SIGNAL_WAIT_RELEASE;
        }
        /* Linux checks the private queue before the shared queue, then gives
         * synchronous signal numbers priority within each queue. */
        const uint64_t preceding=(UINT64_C(1)<<(SIGSEGV-1))|(UINT64_C(1)<<(SIGBUS-1))
            |(UINT64_C(1)<<(SIGILL-1))|(UINT64_C(1)<<(SIGTRAP-1))|(UINT64_C(1)<<(SIGFPE-1));
        int directed=c->selected.scope==MD_SIGNAL_PRIVATE;
        uint64_t native=0,wanted=c->wait_set&~MD_SIGNAL_SYS_BIT;
        if (directed) wanted&=preceding;
        int error=private_pending(c,wanted,&native);
        if (error) return error;
        if (!native && directed) {
            if (!md_signal_pending_take(c->pending_owner,&c->selected)) return -EPROTO;
            c->wait_result=SIGSYS; c->wait_info=c->selected.info;
            c->stage=WAIT_OUTPUT;
            continue;
        }
        return wait_probe(c,native ? native : c->wait_set&preceding);
    }
    if (c->stage==WAIT_OUTPUT) {
        c->stage=WAIT_DONE;
        if (c->translate) c->translate(c->context,&c->wait_info);
        if (c->descriptor==2) {
            md_signal_fd_info(&c->wait_info,&c->record);
            c->record_copied=0; c->stage=WAIT_RECORD;
        } else if (r->args[1]) {
            int copied=transfer(c,r->args[1],&c->wait_info,sizeof(c->wait_info),1);
            if (copied<0) return c->copying ? copied : finished(c,copied);
            if (!copied) return 0;
        }
    }
    if (c->stage==WAIT_RECORD) {
        const struct iovec *vectors=c->vectors ? c->vectors : &c->scalar;
        while (c->vector_index<c->vector_count && c->vector_offset==vectors[c->vector_index].iov_len) {
            ++c->vector_index; c->vector_offset=0;
        }
        if (c->vector_index==c->vector_count) return finished(c,-EFAULT);
        const struct iovec *vector=vectors+c->vector_index;
        c->copy_length=sizeof(c->record)-c->record_copied;
        if (c->copy_length>vector->iov_len-c->vector_offset) c->copy_length=vector->iov_len-c->vector_offset;
        c->stage=WAIT_COPY;
        int copied=transfer(c,(uintptr_t)vector->iov_base+c->vector_offset,
            (char *)&c->record+c->record_copied,c->copy_length,1);
        if (copied<0) return c->copying ? copied : finished(c,copied);
        if (!copied) return 0;
    }
    if (c->stage==WAIT_COPY) {
        c->vector_offset+=c->copy_length; c->record_copied+=c->copy_length;
        if (c->record_copied==sizeof(c->record)) {
            c->descriptor_copied+=sizeof(c->record); c->stage=WAIT_PROBE;
        } else c->stage=WAIT_RECORD;
        continue;
    }
    return finished(c,c->wait_result);
    }
}
static int progress(struct md_task_signal_control *c) {
    if (c->waiting) return wait_progress(c);
    if (c->descriptor) return descriptor_progress(c);
    const struct seccomp_data *r=&c->request;
    for (;;) {
        if (c->entering) {
            uintptr_t address=c->signal_frame+offsetof(ucontext_t,uc_sigmask);
            if (address<c->signal_frame) return finished(c,-EFAULT);
            if (c->stage==INPUT) {
                ++c->stage;
                int copied=transfer(c,address,&c->previous_mask,sizeof(c->previous_mask),0);
                if (copied<0) return c->copying ? copied : finished(c,copied);
                if (!copied) return 0;
            }
            if (c->stage==EFFECT) {
                uint64_t native=c->previous_mask;
                c->next_mask=md_signal_view_enter(c->signal,&c->previous,*c->blocked,&c->wait_view,&c->previous_mask);
                ++c->stage;
                if (c->previous_mask!=native) {
                    int copied=transfer(c,address,&c->previous_mask,sizeof(c->previous_mask),1);
                    if (copied<0) return c->copying ? copied : finished(c,copied);
                    if (!copied) return 0;
                }
            }
            *c->blocked=!!(c->next_mask&MD_SIGNAL_SYS_BIT);
            return finished(c,0);
        }
        if (c->stage==INPUT) {
            if (r->args[3]!=sizeof(uint64_t)) return finished(c,-EINVAL);
            ++c->stage;
            if (r->args[1]) {
                int copied=transfer(c,r->args[1],&c->requested,sizeof(c->requested),0);
                if (copied<0) return c->copying ? copied : finished(c,copied);
                if (!copied) return 0;
            }
        }
        if (c->stage==EFFECT) {
            int signal=c->force_default ? SIGSYS : (int)r->args[0];
            if (signal<=0 || signal>=NSIG || (r->args[1] && (signal==SIGKILL || signal==SIGSTOP)))
                return finished(c,-EINVAL);
            struct exchange *exchange=c->scratch;
            *exchange=(struct exchange){0};
            if (r->args[1] || c->force_default) exchange->requested=md_signal_view_native(signal,c->requested,
                md_signal_actions_entry(c->actions,0));
            struct md_task_call_arguments args={.number=SYS_rt_sigaction,
                .value={signal,(r->args[1] || c->force_default) ? c->peer+offsetof(struct exchange,requested) : 0,
                    c->peer+offsetof(struct exchange,previous),sizeof(uint64_t)}};
            return invoke(c,&args);
        }
        if (c->stage==OUTPUT) {
            ++c->stage;
            uintptr_t output=r->args[2];
            if (output) {
                int copied=transfer(c,output,&c->previous,sizeof(c->previous),1);
                if (copied<0) return c->copying ? copied : finished(c,copied);
                if (!copied) return 0;
            }
        }
        return finished(c,0);
    }
}
int md_task_signal_wait_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        const struct seccomp_data *request,
        uintptr_t call,uintptr_t returned,struct md_signal_pending *pending,int *blocked,
        void (*translate)(void *,siginfo_t *),void *context,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !trace || pid<=0 || !r || !request || !blocked || request->nr!=SYS_rt_sigtimedwait || !scratch || !peer
            || capacity<sizeof(struct md_signal_wait_exchange)
            || capacity<sizeof(struct md_task_copy_scratch)+sizeof(siginfo_t)) return -EINVAL;
    *c=(struct md_task_signal_control){.trace=trace,.pid=pid,.original=*r,.request=*request,
        .boundary=MD_TASK_CALL_SECCOMP,.call=call,.returned=returned,
        .scratch=scratch,.peer=peer,.capacity=capacity,.waiting=1,
        .translate=translate,.context=context,.pending_owner=pending,.blocked=blocked};
    return progress(c);
}
static int wait_timed(const struct md_task_signal_control *c) {
    return c->descriptor==2 ? c->descriptor_nonblock || c->descriptor_copied : !!c->request.args[2];
}
int md_task_signal_wait_park(struct md_task_signal_control *c,uintptr_t page) {
    if (!c || !c->waiting || (c->stage!=WAIT_NATIVE && c->stage!=WAIT_ALLOCATE)
            || c->invoking || c->copying) return -EINVAL;
    if (!page) {
        c->stage=WAIT_ALLOCATE;
        struct md_task_call_arguments args={.number=SYS_mmap,
            .value={0,(unsigned long)getpagesize(),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0}};
        return invoke(c,&args);
    }
    c->wait_peer=page;
    struct md_signal_wait_exchange *exchange=&c->wait_exchange;
    *exchange=(struct md_signal_wait_exchange){.set=c->wait_set};
    int timed=wait_timed(c);
    if (timed) {
        int64_t now=md_event_now();
        if (!c->wait_timed) {
            c->wait_timed=1;
            int64_t seconds=c->wait_timeout.tv_sec;
            c->wait_deadline=seconds>(INT64_MAX-now-c->wait_timeout.tv_nsec)/1000000000
                ? INT64_MAX : now+seconds*1000000000+c->wait_timeout.tv_nsec;
        }
        int64_t left=c->wait_deadline-now;
        if (left<0) left=0;
        exchange->timeout=(struct timespec){left/1000000000,left%1000000000};
    }
    c->stage=WAIT_PARK_COPY;
    int copied=transfer(c,page,exchange,sizeof(*exchange),1);
    if (copied<0) return c->copying ? copied : finished(c,copied);
    return copied ? progress(c) : 0;
}
int md_task_signal_control_continue(struct md_task_signal_control *c) {
    if (!c || (!c->waiting && !c->descriptor) || c->invoking || c->copying) return -EINVAL;
    if (!c->waiting || c->stage!=WAIT_PARKED) return progress(c);
    c->stage=WAIT_NATIVE;
    struct md_task_call_arguments args={.number=SYS_rt_sigtimedwait,
        .flags=MD_TASK_CALL_INTERRUPTIBLE,
        .value={c->wait_peer+offsetof(struct md_signal_wait_exchange,set),c->wait_peer+offsetof(struct md_signal_wait_exchange,info),
            wait_timed(c) ? c->wait_peer+offsetof(struct md_signal_wait_exchange,timeout) : 0,sizeof(uint64_t)}};
    c->wait_woken=0;
    return invoke(c,&args);
}
void md_task_signal_control_dispose(struct md_task_signal_control *c) {
    free(c->vectors); c->vectors=NULL;
}
int md_task_signal_descriptor_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        const struct seccomp_data *request,
        uintptr_t call,uintptr_t returned,struct md_signal_pending *pending,int *blocked,
        void (*translate)(void *,siginfo_t *),void *context,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !trace || pid<=0 || !r || !request || !blocked || !scratch || !peer
            || (request->nr!=SYS_signalfd4 && request->nr!=SYS_read && request->nr!=SYS_readv)
            || capacity<sizeof(struct md_task_copy_scratch)+sizeof(siginfo_t)) return -EINVAL;
    *c=(struct md_task_signal_control){.trace=trace,.pid=pid,.original=*r,.request=*request,
        .boundary=MD_TASK_CALL_SECCOMP,.call=call,.returned=returned,
        .scratch=scratch,.peer=peer,.capacity=capacity,
        .translate=translate,.context=context,.pending_owner=pending,.blocked=blocked,
        .descriptor=request->nr==SYS_signalfd4 ? 1 : 2};
    return progress(c);
}
int md_task_signal_descriptor_admit(struct md_task_signal_control *c,struct md_signal_fd *file) {
    if (!c || c->descriptor_stage!=FD_ADMISSION) return -EINVAL;
    c->signal_file=file;
    if (c->descriptor==2 && !file) {
        c->passthrough=1; c->descriptor_result=0; c->descriptor_stage=FD_COMPLETE;
        return MD_SIGNAL_FD_RELEASE;
    }
    c->descriptor_stage=c->descriptor==1 && (int)c->request.args[0]>=0 ? FD_FLAGS : FD_EFFECT;
    memcpy(c->scratch,&c->wait_set,sizeof(c->wait_set));
    const struct seccomp_data *r=&c->request;
    struct md_task_call_arguments args=c->descriptor_stage==FD_FLAGS
        ? (struct md_task_call_arguments){.number=SYS_fcntl,.value={r->args[0],F_GETFD}}
        : c->descriptor==1
        ? (struct md_task_call_arguments){.number=SYS_signalfd4,.value={r->args[0],c->peer,8,r->args[3]}}
        : (struct md_task_call_arguments){.number=SYS_fcntl,.value={r->args[0],F_GETFL}};
    return invoke(c,&args);
}
int md_task_signal_control_wake(struct md_task_signal_control *c) {
    if (!c || !c->waiting || c->stage!=WAIT_NATIVE || !c->invoking
            || (!(c->wait_set&MD_SIGNAL_SYS_BIT) && *c->blocked)) return 0;
    c->wait_woken=!!(c->wait_set&MD_SIGNAL_SYS_BIT);
    return md_task_call_interrupt(&c->native);
}
int md_task_signal_control_parked(const struct md_task_signal_control *c) {
    return c && c->waiting && c->stage==WAIT_NATIVE && c->invoking && !c->copying
        && c->native.boundary==MD_TASK_CALL_EXIT;
}
void md_task_signal_control_quiesced(struct md_task_signal_control *c) {
    if (md_task_signal_control_parked(c)) c->wait_woken=1;
}
int md_task_signal_descriptor_changed(struct md_task_signal_control *c,const struct md_signal_fd *file) {
    if (!c || c->signal_file!=file || !c->waiting || c->stage!=WAIT_NATIVE || !c->invoking) return 0;
    c->wait_woken=1;
    return md_task_call_interrupt(&c->native);
}
int md_task_signal_default_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        uintptr_t call,uintptr_t returned,struct md_signal_actions *actions,int *blocked,
        const struct md_kernel_action *action,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !trace || pid<=0 || !r || !actions || !blocked || !action || !scratch || !peer
            || capacity<sizeof(struct exchange)) return -EINVAL;
    *c=(struct md_task_signal_control){.trace=trace,.pid=pid,.original=*r,
        .boundary=MD_TASK_CALL_USER,.call=call,.returned=returned,.actions=actions,.blocked=blocked,
        .scratch=scratch,.peer=peer,.capacity=capacity,.force_default=1,.stage=EFFECT,.requested=*action};
    c->requested.handler=(uintptr_t)SIG_DFL;
    return progress(c);
}
int md_task_signal_wait_entry_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        uintptr_t call,uintptr_t returned,struct md_signal_actions *actions,int *blocked,
        const struct md_kernel_action *action,const struct md_signal_wait_view *wait,
        void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !trace || pid<=0 || !r || !actions || !blocked || !action || !wait || !wait->active
            || !scratch || !peer || capacity<sizeof(struct md_task_copy_scratch)+sizeof(uint64_t)
            || !r->regs[0] || r->regs[0]>=NSIG) return -EINVAL;
    *c=(struct md_task_signal_control){.trace=trace,.pid=pid,.original=*r,
        .boundary=MD_TASK_CALL_USER,.call=call,.returned=returned,
        .actions=actions,.blocked=blocked,.scratch=scratch,.peer=peer,.capacity=capacity,
        .previous=*action,.entering=1,.signal=(int)r->regs[0],.signal_frame=r->regs[2],.wait_view=*wait};
    return progress(c);
}
int md_task_signal_control_begin(struct md_task_signal_control *c,
        long (*trace)(int,pid_t,void *,void *),pid_t pid,const struct user_pt_regs *r,
        const struct seccomp_data *request,
        uintptr_t call,uintptr_t returned,struct md_signal_actions *actions,
        int *blocked,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !trace || pid<=0 || !r || !request || !actions || !blocked || !scratch || !peer
            || capacity<sizeof(struct exchange) || capacity<sizeof(struct md_task_copy_scratch)+sizeof(struct md_kernel_action)
            || request->nr!=SYS_rt_sigaction) return -EINVAL;
    *c=(struct md_task_signal_control){.trace=trace,.pid=pid,.original=*r,.request=*request,
        .boundary=MD_TASK_CALL_SECCOMP,.call=call,.returned=returned,
        .actions=actions,.blocked=blocked,.scratch=scratch,.peer=peer,.capacity=capacity};
    return progress(c);
}
struct md_task_call *md_task_signal_control_call(struct md_task_signal_control *c) {
    return c->copying ? &c->copy.call : c->invoking ? &c->native : NULL;
}
int md_task_signal_control_event(struct md_task_signal_control *c,int status) {
    if (c->copying) {
        int result=md_task_copy_event(&c->copy,status);
        if (result!=1) return result;
        c->copying=0;
        if (c->copy.result!=(long)c->copy.size)
            return finished(c,c->copy.result<0 ? c->copy.result : -EFAULT);
    } else if (c->invoking) {
        int result=md_task_call_event(&c->native,status);
        if (result!=1) return result;
        c->invoking=0;
        c->boundary=c->native.boundary;
        if (c->descriptor && !c->waiting) {
            if (c->descriptor_stage==FD_FLAGS && c->native.result>=0) {
                c->descriptor_flags=(int)c->native.result;
                c->descriptor_stage=FD_EFFECT;
                const struct seccomp_data *r=&c->request;
                struct md_task_call_arguments args={.number=SYS_signalfd4,
                    .value={r->args[0],c->peer,8,r->args[3]}};
                return invoke(c,&args);
            }
            c->descriptor_result=c->native.result; c->descriptor_stage=FD_COMPLETE;
            return MD_SIGNAL_FD_RELEASE;
        }
        if (c->waiting) {
            if (c->stage==WAIT_ALLOCATE)
                return c->native.result<0 ? finished(c,c->native.result) : MD_SIGNAL_WAIT_STORAGE;
            if (c->wait_native) {
                c->wait_native=0;
                long value=c->native.result;
                if (c->wait_woken && (value==-EINTR || md_syscall_restarting(value))) c->stage=WAIT_PROBE;
                else {
                    c->wait_result=md_syscall_restarting(value) ? -EINTR : value;
                    c->stage=value>0 ? WAIT_FETCH : WAIT_DONE;
                }
                return MD_SIGNAL_WAIT_ACQUIRE;
            }
            if (c->wait_interrupt) {
                c->wait_interrupt=0;
                if (c->native.result==-EAGAIN) return finished(c,-EINTR);
            }
            if (c->native.result==-EAGAIN) {
                struct md_signal_pending_value current;
                if (!md_signal_pending_peek(c->pending_owner,&current)
                        || current.scope!=c->selected.scope || current.token!=c->selected.token
                        || !md_signal_pending_take(c->pending_owner,&c->selected)) {
                    c->stage=WAIT_PROBE;
                    return progress(c);
                }
                c->wait_result=SIGSYS; c->wait_info=c->selected.info;
            } else if (c->native.result>0) {
                c->wait_result=c->native.result;
                c->wait_info=((struct md_signal_wait_exchange *)c->scratch)->info;
            } else return finished(c,c->native.result);
            c->stage=WAIT_OUTPUT;
            return progress(c);
        }
        if (c->native.result) return finished(c,c->native.result);
        if (c->force_default) {
            int error=md_signal_actions_commit(c->actions,SIGSYS,&c->requested);
            if (error) return error;
            *c->blocked=0;
            return finished(c,0);
        }
        int signal=(int)c->request.args[0];
        struct exchange *exchange=c->scratch;
        c->previous=md_signal_actions_decode(c->actions,signal,exchange->previous,
            md_signal_actions_entry(c->actions,signal));
        if (c->request.args[1]) {
            int error=md_signal_actions_commit(c->actions,signal,&c->requested);
            if (error) return error;
            md_signal_actions_entry_publish(c->actions,signal);
        }
        ++c->stage;
    } else return -EINVAL;
    return progress(c);
}
