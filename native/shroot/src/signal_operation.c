#define _GNU_SOURCE
#include "signal_operation.h"
#include "signal_view.h"
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
struct mask_exchange { uint64_t requested,previous; };
enum { INPUT, EFFECT, OUTPUT, DONE };
static int finished(struct md_signal_operation *c,long result) {
    if (c->descriptor==2 && c->descriptor_copied) result=(long)c->descriptor_copied;
    c->result=result; c->stage=DONE; return 1;
}
static int transfer(struct md_signal_operation *c,uintptr_t address,void *data,size_t size,int writing) {
    c->transfer=(struct md_signal_transfer){address,data,size,writing};
    c->copying=1;
    return 0;
}
static int invoke(struct md_signal_operation *c,const struct md_task_call_arguments *args) {
    c->effect=*args; c->invoking=1;
    return 0;
}
enum { WAIT_SET, WAIT_TIMEOUT, WAIT_PROBE, WAIT_NATIVE, WAIT_OUTPUT, WAIT_RECORD, WAIT_COPY, WAIT_DONE,
    WAIT_ALLOCATE, WAIT_PARK_COPY, WAIT_PARKED, WAIT_FETCH, WAIT_PRIORITY };
enum { FD_INPUT, FD_CAPTURE, FD_ADMISSION, FD_FLAGS, FD_EFFECT, FD_COMPLETE, FD_VECTOR_DONE };
static int descriptor_progress(struct md_signal_operation *c) {
    const struct seccomp_data *r=&c->request;
    if (c->descriptor_stage==FD_INPUT) {
        c->descriptor_stage=FD_CAPTURE;
        if (c->descriptor==1) {
            if (r->args[2]!=sizeof(uint64_t)) return finished(c,-EINVAL);
            return transfer(c,r->args[1],&c->wait_set,sizeof(c->wait_set),0);
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
            return transfer(c,r->args[1],c->vectors,c->vector_count*sizeof(*c->vectors),0);
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
        return md_signal_operation_continue(c);
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
static int wait_probe(struct md_signal_operation *c,uint64_t set) {
    struct md_signal_wait_exchange *exchange=c->scratch;
    *exchange=(struct md_signal_wait_exchange){.set=set};
    struct md_task_call_arguments args={.number=SYS_rt_sigtimedwait,
        .value={c->peer+offsetof(struct md_signal_wait_exchange,set),c->peer+offsetof(struct md_signal_wait_exchange,info),
            c->peer+offsetof(struct md_signal_wait_exchange,timeout),sizeof(uint64_t)}};
    return invoke(c,&args);
}
static int mask_progress(struct md_signal_operation *c) {
    const struct seccomp_data *r=&c->request;
    if (c->stage==INPUT) {
        if (r->args[3]!=sizeof(uint64_t)) return finished(c,-EINVAL);
        c->stage=EFFECT;
        if (r->args[1]) return transfer(c,r->args[1],&c->next_mask,sizeof(c->next_mask),0);
    }
    if (c->stage==EFFECT) {
        struct mask_exchange *exchange=c->scratch;
        *exchange=(struct mask_exchange){.requested=c->next_mask&~MD_SIGNAL_SYS_BIT};
        struct md_task_call_arguments args={.number=SYS_rt_sigprocmask,
            .value={r->args[0],r->args[1] ? c->peer+offsetof(struct mask_exchange,requested) : 0,
                c->peer+offsetof(struct mask_exchange,previous),sizeof(uint64_t)}};
        return invoke(c,&args);
    }
    if (c->stage==OUTPUT) {
        c->stage=DONE;
        if (r->args[2]) return transfer(c,r->args[2],&c->previous_mask,sizeof(c->previous_mask),1);
    }
    return finished(c,0);
}
static int wait_progress(struct md_signal_operation *c) {
    const struct seccomp_data *r=&c->request;
    for (;;) {
        if (c->stage==WAIT_PARK_COPY) { c->stage=WAIT_PARKED; return MD_SIGNAL_WAIT_PARKED; }
        if (c->stage==WAIT_FETCH) {
            c->stage=WAIT_OUTPUT;
            return transfer(c,c->wait_peer+offsetof(struct md_signal_wait_exchange,info),
                &c->wait_info,sizeof(c->wait_info),0);
        }
        if (c->stage==WAIT_SET) {
            if (r->args[3]!=sizeof(uint64_t)) return finished(c,-EINVAL);
            ++c->stage;
            return transfer(c,r->args[0],&c->wait_set,sizeof(c->wait_set),0);
        }
        if (c->stage==WAIT_TIMEOUT) {
            ++c->stage;
            if (r->args[2]) {
                return transfer(c,r->args[2],&c->wait_timeout,sizeof(c->wait_timeout),0);
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
            c->pending_wanted=c->wait_set&~MD_SIGNAL_SYS_BIT;
            c->pending_probe=c->wait_set&preceding;
            if (directed) c->pending_wanted&=preceding;
            c->stage=WAIT_PRIORITY;
            return MD_SIGNAL_PENDING;
        }
        if (c->stage==WAIT_PRIORITY) {
            uint64_t native=c->pending_result;
            if (!native && c->selected.scope==MD_SIGNAL_PRIVATE) {
                if (!md_signal_pending_take(c->pending_owner,&c->selected)) return -EPROTO;
                c->wait_result=SIGSYS; c->wait_info=c->selected.info;
                c->stage=WAIT_OUTPUT;
                continue;
            }
            return wait_probe(c,native ? native : c->pending_probe);
        }
        if (c->stage==WAIT_OUTPUT) {
            c->stage=WAIT_DONE;
            if (c->translate) c->translate(c->context,&c->wait_info);
            if (c->descriptor==2) {
                md_signal_fd_info(&c->wait_info,&c->record);
                c->record_copied=0; c->stage=WAIT_RECORD;
            } else if (r->args[1]) {
                return transfer(c,r->args[1],&c->wait_info,sizeof(c->wait_info),1);
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
            return transfer(c,(uintptr_t)vector->iov_base+c->vector_offset,
                (char *)&c->record+c->record_copied,c->copy_length,1);
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
static int progress(struct md_signal_operation *c) {
    if (c->request.nr==SYS_rt_sigprocmask) return mask_progress(c);
    if (c->waiting) return wait_progress(c);
    if (c->descriptor) return descriptor_progress(c);
    const struct seccomp_data *r=&c->request;
    for (;;) {
        if (c->entering) {
            uintptr_t address=c->signal_frame+offsetof(ucontext_t,uc_sigmask);
            if (address<c->signal_frame) return finished(c,-EFAULT);
            if (c->stage==INPUT) {
                ++c->stage;
                return transfer(c,address,&c->previous_mask,sizeof(c->previous_mask),0);
            }
            if (c->stage==EFFECT) {
                uint64_t native=c->previous_mask;
                c->next_mask=md_signal_view_enter(c->signal,&c->previous,*c->blocked,&c->wait_view,&c->previous_mask);
                ++c->stage;
                if (c->previous_mask!=native) {
                    return transfer(c,address,&c->previous_mask,sizeof(c->previous_mask),1);
                }
            }
            *c->blocked=!!(c->next_mask&MD_SIGNAL_SYS_BIT);
            return finished(c,0);
        }
        if (c->stage==INPUT) {
            if (r->args[3]!=sizeof(uint64_t)) return finished(c,-EINVAL);
            ++c->stage;
            if (r->args[1]) {
                return transfer(c,r->args[1],&c->requested,sizeof(c->requested),0);
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
                return transfer(c,output,&c->previous,sizeof(c->previous),1);
            }
        }
        return finished(c,0);
    }
}
static int wait_timed(const struct md_signal_operation *c) {
    return c->descriptor==2 ? c->descriptor_nonblock || c->descriptor_copied : !!c->request.args[2];
}
int md_signal_operation_park(struct md_signal_operation *c,uintptr_t page) {
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
    return transfer(c,page,exchange,sizeof(*exchange),1);
}
int md_signal_operation_continue(struct md_signal_operation *c) {
    if (!c || (!c->waiting && !c->descriptor) || c->invoking || c->copying) return -EINVAL;
    if (!c->waiting || c->stage!=WAIT_PARKED) return progress(c);
    /* Preparation released its mapping turn. A virtual signal or descriptor
     * update during that interval had no native wait to interrupt. */
    int pending=md_signal_pending_has(c->pending_owner,MD_SIGNAL_PRIVATE)
        || md_signal_pending_has(c->pending_owner,MD_SIGNAL_SHARED);
    if ((pending && ((c->wait_set&MD_SIGNAL_SYS_BIT) || !*c->blocked))
            || (c->descriptor==2 && c->wait_set!=md_signal_fd_mask(c->signal_file))) {
        c->wait_native=0; c->stage=WAIT_PROBE;
        return MD_SIGNAL_WAIT_ACQUIRE;
    }
    c->stage=WAIT_NATIVE;
    struct md_task_call_arguments args={.number=SYS_rt_sigtimedwait,
        .flags=MD_TASK_CALL_INTERRUPTIBLE,
        .value={c->wait_peer+offsetof(struct md_signal_wait_exchange,set),c->wait_peer+offsetof(struct md_signal_wait_exchange,info),
            wait_timed(c) ? c->wait_peer+offsetof(struct md_signal_wait_exchange,timeout) : 0,sizeof(uint64_t)}};
    c->wait_woken=0;
    return invoke(c,&args);
}
void md_signal_operation_dispose(struct md_signal_operation *c) {
    free(c->vectors); c->vectors=NULL;
}
int md_signal_operation_descriptor_admit(struct md_signal_operation *c,struct md_signal_fd *file) {
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
int md_signal_operation_wake(struct md_signal_operation *c) {
    if (!c || !c->waiting || c->stage!=WAIT_NATIVE || !c->invoking
            || (!(c->wait_set&MD_SIGNAL_SYS_BIT) && *c->blocked)) return 0;
    c->wait_woken=!!(c->wait_set&MD_SIGNAL_SYS_BIT);
    return 1;
}
int md_signal_operation_parked(const struct md_signal_operation *c) {
    return c && c->waiting && c->stage==WAIT_NATIVE && c->invoking && !c->copying;
}
void md_signal_operation_quiesced(struct md_signal_operation *c) {
    if (md_signal_operation_parked(c)) c->wait_woken=1;
}
int md_signal_operation_descriptor_changed(struct md_signal_operation *c,const struct md_signal_fd *file) {
    if (!c || c->signal_file!=file || !c->waiting || c->stage!=WAIT_NATIVE || !c->invoking) return 0;
    c->wait_woken=1;
    return 1;
}
int md_signal_operation_effect_done(struct md_signal_operation *c,long value) {
    if (!c->invoking || c->copying) return -EINVAL;
    c->invoking=0;
    c->effect_result=value;
    if (c->descriptor && !c->waiting) {
        if (c->descriptor_stage==FD_FLAGS && value>=0) {
            c->descriptor_flags=(int)value;
            c->descriptor_stage=FD_EFFECT;
            const struct seccomp_data *r=&c->request;
            struct md_task_call_arguments args={.number=SYS_signalfd4,
                .value={r->args[0],c->peer,8,r->args[3]}};
            return invoke(c,&args);
        }
        c->descriptor_result=value; c->descriptor_stage=FD_COMPLETE;
        return MD_SIGNAL_FD_RELEASE;
    }
    if (c->waiting) {
        if (c->stage==WAIT_ALLOCATE)
            return value<0 ? finished(c,value) : MD_SIGNAL_WAIT_STORAGE;
        if (c->wait_native) {
            c->wait_native=0;
            if (c->wait_woken && (value==-EINTR || md_syscall_restarting(value))) c->stage=WAIT_PROBE;
            else {
                c->wait_result=md_syscall_restarting(value) ? -EINTR : value;
                c->stage=value>0 ? WAIT_FETCH : WAIT_DONE;
            }
            return MD_SIGNAL_WAIT_ACQUIRE;
        }
        if (c->wait_interrupt) {
            c->wait_interrupt=0;
            if (value==-EAGAIN) return finished(c,-EINTR);
        }
        if (value==-EAGAIN) {
            struct md_signal_pending_value current;
            if (!md_signal_pending_peek(c->pending_owner,&current)
                    || current.scope!=c->selected.scope || current.token!=c->selected.token
                    || !md_signal_pending_take(c->pending_owner,&c->selected)) {
                c->stage=WAIT_PROBE;
                return progress(c);
            }
            c->wait_result=SIGSYS; c->wait_info=c->selected.info;
        } else if (value>0) {
            c->wait_result=value;
            c->wait_info=((struct md_signal_wait_exchange *)c->scratch)->info;
        } else return finished(c,value);
        c->stage=WAIT_OUTPUT;
        return progress(c);
    }
    if (value) return finished(c,value);
    if (c->request.nr==SYS_rt_sigprocmask) {
        struct mask_exchange *exchange=c->scratch;
        c->previous_mask=md_signal_view_mask(exchange->previous,*c->blocked);
        if (c->request.args[1]) {
            uint64_t next;
            int error=md_signal_view_update(c->previous_mask,(int)c->request.args[0],c->next_mask,&next);
            if (error) return error;
            *c->blocked=!!(next&MD_SIGNAL_SYS_BIT);
            c->next_mask=next;
        }
        c->stage=OUTPUT;
        return progress(c);
    }
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
    return progress(c);
}

int md_signal_operation_copy_done(struct md_signal_operation *c,long copied) {
    if (!c->copying || c->invoking) return -EINVAL;
    c->copying=0;
    if (copied!=(long)c->transfer.size) return finished(c,copied<0 ? copied : -EFAULT);
    return progress(c);
}
int md_signal_operation_pending_done(struct md_signal_operation *c,int error,uint64_t mask) {
    if (c->stage!=WAIT_PRIORITY || c->copying || c->invoking) return -EINVAL;
    if (error) return error;
    c->pending_result=mask;
    return progress(c);
}
int md_signal_operation_begin(struct md_signal_operation *c,const struct seccomp_data *request,
        struct md_signal_actions *actions,struct md_signal_pending *pending,int *blocked,
        void (*translate)(void *,siginfo_t *),void *context,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !request || !blocked || !scratch || !peer || capacity<sizeof(struct exchange)
            || capacity<sizeof(struct md_signal_wait_exchange)) return -EINVAL;
    int descriptor=request->nr==SYS_signalfd4 ? 1
        : request->nr==SYS_read || request->nr==SYS_readv ? 2 : 0;
    if (!descriptor && request->nr!=SYS_rt_sigtimedwait && request->nr!=SYS_rt_sigaction
            && request->nr!=SYS_rt_sigprocmask) return -EINVAL;
    if (request->nr==SYS_rt_sigaction && !actions) return -EINVAL;
    *c=(struct md_signal_operation){.request=*request,.scratch=scratch,.peer=peer,.capacity=capacity,
        .actions=actions,.pending_owner=pending,.blocked=blocked,.translate=translate,.context=context,
        .descriptor=descriptor,.waiting=request->nr==SYS_rt_sigtimedwait};
    return progress(c);
}
int md_signal_operation_default_begin(struct md_signal_operation *c,struct md_signal_actions *actions,
        int *blocked,const struct md_kernel_action *action,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !actions || !blocked || !action || !scratch || !peer || capacity<sizeof(struct exchange)) return -EINVAL;
    *c=(struct md_signal_operation){.actions=actions,.blocked=blocked,.scratch=scratch,.peer=peer,
        .capacity=capacity,.force_default=1,.stage=EFFECT,.requested=*action};
    c->requested.handler=(uintptr_t)SIG_DFL;
    return progress(c);
}
int md_signal_operation_entry_begin(struct md_signal_operation *c,uintptr_t frame,int signal,
        struct md_signal_actions *actions,int *blocked,const struct md_kernel_action *action,
        const struct md_signal_wait_view *wait,void *scratch,uintptr_t peer,size_t capacity) {
    if (!c || !actions || !blocked || !action || !wait || !wait->active || !scratch || !peer
            || capacity<sizeof(uint64_t) || signal<=0 || signal>=NSIG) return -EINVAL;
    *c=(struct md_signal_operation){.actions=actions,.blocked=blocked,.scratch=scratch,.peer=peer,
        .capacity=capacity,.previous=*action,.entering=1,.signal=signal,.signal_frame=frame,.wait_view=*wait};
    return progress(c);
}
