#include "cooperative_exchange.h"
#include "adapter_buffer.h"
#include "interception.h"
#include "raw.h"
#include "spin_policy.h"
#include "file_open.h"
#include "fs_syscall_strings.h"
#include "seccomp_program.h"
#include "svc_runtime.h"
#include "svc_payload.h"
#include "svc_direct.h"
#include "fs_buffer.h"
#include "fd_client.h"
#include "resource_control.h"
#include "descriptor_calls.h"
#include "entry_wakeup.h"
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <linux/audit.h>
#include <sys/mman.h>
#include <time.h>

extern long md_guest_entry_kernel(long,const uint64_t *);
static unsigned char offers[512];
static unsigned char strings[512];
static struct md_interception_routes routes;
int md_cooperative_routes_init(const struct md_interception_abi *abi) {
    return md_interception_routes_init(&routes,abi);
}
uintptr_t md_cooperative_registration=1;
void md_cooperative_revoke(void) {
    __atomic_store_n(&md_cooperative_registration,0,__ATOMIC_RELEASE);
}
void md_cooperative_init(void) {
    for (unsigned i=0;i<sizeof(offers);++i) {
        enum md_entry_route route=md_entry_route(i);
        offers[i]=MD_ENTRY_ORDINARY
            |(route==MD_ENTRY_FD ? MD_ENTRY_CANCELLATION : 0)
            |(route==MD_ENTRY_MEMORY ? MD_ENTRY_NATIVE_MEMORY : 0);
        strings[i]=(unsigned char)md_fs_syscall_strings(i);
    }
}
static enum md_entry_outcome kernel_call(struct md_entry_call *call) {
    if (!call->kernel) call->kernel=md_guest_entry_kernel;
    return MD_ENTRY_KERNEL_CALL;
}
static void native_effect(void *);
static enum md_entry_outcome admitted_kernel_call(const struct md_entry_channel *c,struct md_entry_call *call) {
    if (atomic_load_explicit(&c->routes.flags,memory_order_acquire)&MD_ROUTE_ADMITTED_KERNEL) {
        enum md_execution_context context=md_execution_native_context(call->number,call->args);
        if (context==MD_CONTEXT_WAIT) {
            call->effect=native_effect;
            return MD_ENTRY_NATIVE_CALL;
        }
        if (context==MD_CONTEXT_COUNT) {
            call->result=md_guest_entry_kernel(call->number,call->args);
            return MD_ENTRY_COMPLETED;
        }
    }
    return kernel_call(call);
}
static void capture_paths(struct md_entry_channel *c,const struct md_entry_call *call) {
    unsigned selectors=(unsigned)call->number<sizeof(strings) ? strings[call->number] : 0;
    /* An older capture cannot match the next monotonic request sequence.
     * Pathless calls need not acquire/write the input buffer's cache line. */
    if (!selectors) return;
    atomic_store_explicit(&c->copying,1,memory_order_release);
    for (unsigned i=0;i<2;++i,selectors>>=3) {
        uintptr_t path=(selectors&7) ? call->args[(selectors&7)-1] : 0;
        c->input.address[i]=0;
        if (path && !md_read_string(c->input.paths[i],sizeof(c->input.paths[i]),(void *)path))
            c->input.address[i]=path;
    }
    atomic_store_explicit(&c->copying,0,memory_order_release);
    c->input.sequence=c->sequence+1;
}
enum { CONT_CONTROL, CONT_CHANNEL, CONT_SEQUENCE, CONT_FLAGS };
enum { CALL_NATIVE=1, CALL_DESCRIPTOR=2, CALL_NATIVE_SENT=4, CALL_KIND_SHIFT=3,
    CALL_BEGIN=MD_ENTRY_EXECUTION_BEGIN<<CALL_KIND_SHIFT,
    CALL_END=MD_ENTRY_EXECUTION_END<<CALL_KIND_SHIFT };
static enum md_entry_outcome progress(struct md_entry_client *,struct md_entry_call *);
static enum md_entry_outcome publish(struct md_entry_client *,struct md_entry_call *);
static enum md_entry_outcome task_execute(struct md_entry_client *,struct md_entry_call *);
static void wake_owner(struct md_entry_channel *c) {
    if (md_entry_control_request(c->call.reserved) && c->control_target>0
            && (atomic_load_explicit(&c->routes.flags,memory_order_acquire)&MD_ROUTE_ADMITTED_KERNEL)
            && !md_entry_wakeup_send(c->control_target,c->owner)) return;
    md_guest_buffer_admit(MD_ENTRY_WAKE);
}
extern long md_guest_buffer_control_kernel(unsigned,unsigned long,unsigned long);
static struct md_entry_channel *continuation(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=(void *)call->continuation[CONT_CHANNEL];
    return c && (uintptr_t)c==client->opaque[MD_ENTRY_CLIENT_CHANNEL]
        && (__atomic_load_n(&client->opaque[MD_ENTRY_CLIENT_CONTROL],__ATOMIC_ACQUIRE)&MD_ENTRY_CLIENT_BUSY)
        && c->sequence==call->continuation[CONT_SEQUENCE] ? c : NULL;
}
static enum md_entry_outcome finish(struct md_entry_client *client,struct md_entry_call *call,
        enum md_entry_outcome outcome) {
    call->next=NULL;
    struct md_entry_channel *c=(void *)call->continuation[CONT_CHANNEL];
    if (c) atomic_store_explicit(&c->caller,0,memory_order_release);
    __atomic_store_n(&client->opaque[MD_ENTRY_CLIENT_CONTROL],call->continuation[CONT_CONTROL],__ATOMIC_RELEASE);
    return outcome;
}
static enum md_entry_outcome resume_kernel(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    unsigned pending=MD_ENTRY_FD_REQUEST;
    if (atomic_compare_exchange_strong_explicit(&c->state,&pending,MD_ENTRY_IDLE,
            memory_order_acq_rel,memory_order_acquire)) {
        /* This unadmitted sequence can be reused. Unlike ordinary pathless
         * calls, withdrawal must invalidate any capture published for it. */
        c->input.sequence=0;
        --c->sequence;
        return finish(client,call,MD_ENTRY_COMPLETED);
    }
    return progress(client,call);
}
static enum md_entry_outcome resume_native(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    call->continuation[CONT_FLAGS]|=CALL_NATIVE_SENT;
    if (call->continuation[CONT_FLAGS]>>CALL_KIND_SHIFT) {
        /* A control owner retains its operation across the action. Publish an
         * acknowledgement, never execute its original command a second time. */
        if (c->sequence==UINT64_MAX) return finish(client,call,MD_ENTRY_UNKNOWN);
        ++c->sequence;
        c->call.reserved=MD_ENTRY_EFFECT_DONE;
        call->continuation[CONT_FLAGS]&=~CALL_NATIVE_SENT;
        call->continuation[CONT_SEQUENCE]=c->sequence;
        if (md_entry_publish_request(c,MD_ENTRY_REQUEST)) wake_owner(c);
        return progress(client,call);
    }
    if (!c->native_commit && atomic_load_explicit(&c->native.state,memory_order_acquire)==MD_NATIVE_DONE) {
        long result=c->native.result;
        unsigned offered=MD_ENTRY_NATIVE;
        /* Only the completed effect returns early. The worker retains the
         * table turn and pin until retirement; no future call is anticipated. */
        if (atomic_compare_exchange_strong_explicit(&c->state,&offered,MD_ENTRY_RETIRING,
                memory_order_seq_cst,memory_order_acquire)) {
            if (!atomic_load_explicit(&c->awake,memory_order_seq_cst)) md_guest_buffer_admit(MD_ENTRY_WAKE);
            call->result=result;
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
    }
    atomic_thread_fence(memory_order_seq_cst);
    if (!atomic_load_explicit(&c->awake,memory_order_seq_cst)) md_guest_buffer_admit(MD_ENTRY_WAKE);
    return progress(client,call);
}
static void native_effect(void *context) {
    struct md_entry_native *native=context,*frame_native=NULL;
    uintptr_t sp;
    __asm__ volatile("mov %0, sp" : "=r"(sp));
    uintptr_t frames=md_svc_runtime.frames;
    if (frames && sp>frames && sp<=frames+MD_SVC_POOL_SIZE) {
        uintptr_t frame=(sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
        frame_native=(void *)(frame+MD_SVC_NATIVE_OFFSET);
        if (frame_native==native) frame_native=NULL;
        else {
            /* The admitted operation may outlive a policy fence, but a signal
             * must finish its control owner before abandoning the private stack. */
            atomic_store_explicit(&frame_native->state,MD_NATIVE_OWNED,memory_order_release);
            /* A nonblocking probe must still produce its admitted result:
             * cancelling it on an already deferred signal can make its owner
             * repeat the same probe without ever reaching signal delivery. */
            if (md_execution_native_context(native->number,native->args)==MD_CONTEXT_WAIT
                    && __atomic_load_n((uint64_t *)(frame+MD_SVC_DIRECT_PENDING),__ATOMIC_ACQUIRE)) {
                native->result=-EINTR;
                atomic_store_explicit(&native->state,MD_NATIVE_CANCELLED,memory_order_release);
            }
        }
    }
    md_cooperative_native(native);
    if (frame_native) atomic_store_explicit(&frame_native->state,MD_NATIVE_EMPTY,memory_order_release);
}
enum { POLICY_FALLBACK, POLICY_ALLOW, POLICY_RESULT };
static int admit(const struct md_entry_channel *c,long number,const uint64_t *args,
        uintptr_t pc,int64_t *result) {
    const struct md_seccomp_view *view=(const void *)atomic_load_explicit(&c->policy,memory_order_acquire);
    if (!view) return POLICY_ALLOW;
    if (!pc) return POLICY_FALLBACK;
    struct seccomp_data call={.nr=number,.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=pc};
    for (unsigned i=0;i<6;++i) call.args[i]=args[i];
    uint32_t decision;
    if (md_seccomp_view_eval(view,&call,&decision)) return POLICY_FALLBACK;
    switch (decision&SECCOMP_RET_ACTION_FULL) {
    case SECCOMP_RET_ALLOW: return POLICY_ALLOW;
    case SECCOMP_RET_ERRNO: *result=md_seccomp_policy_error(decision); return POLICY_RESULT;
    default: return POLICY_FALLBACK;
    }
}
static int native_memory(struct md_entry_client *client,long tid,long number,const uint64_t *args,int64_t *result,
        uintptr_t pc) {
    if (tid<=0 || (uintptr_t)tid!=client->opaque[MD_ENTRY_CLIENT_OWNER]) return 0;
    /* Growth flags can affect pages outside the supplied range. Such calls
     * need the ordinary memory owner, as do unknown protection semantics. */
    if (!md_memory_native_arguments(number,args[2],args[3])) return 0;
    struct md_entry_channel *c=(void *)client->opaque[MD_ENTRY_CLIENT_CHANNEL];
    if (!c || !atomic_load_explicit(&c->enabled,memory_order_acquire)
            || !md_memory_admission_allows(&c->memory,args[0],args[1],md_page_size)) return 0;
    int policy=admit(c,number,args,pc,result);
    if (policy!=POLICY_ALLOW) return policy==POLICY_RESULT;
    /* Anonymous replacement/remap creates no backing provenance. The same
     * exclusion proof as unmap owns the old range; Linux validates all args. */
    *result=number==SYS_mmap ? RAW6(mmap,args[0],args[1],args[2],args[3],args[4],args[5])
        : number==SYS_munmap ? RAW2(munmap,args[0],args[1])
        : number==SYS_mremap ? RAW5(mremap,args[0],args[1],args[2],args[3],args[4])
        : RAW3(mprotect,args[0],args[1],args[2]);
    return 1;
}
static enum md_entry_outcome offer_native(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    if (!(call->continuation[CONT_FLAGS]>>CALL_KIND_SHIFT) && md_entry_open(call->number))
        c->native.args[1]=(uintptr_t)c->native_path;
    call->effect=native_effect; call->effect_context=&c->native; call->next=resume_native;
    return MD_ENTRY_NATIVE_EFFECT;
}
static enum md_entry_outcome invoke_identified(struct md_entry_client *client,long tid,struct md_entry_call *call) {
    if (!client || !call || call->reserved) return MD_ENTRY_DECLINED;
    enum md_entry_route route=md_entry_route(call->number);
    if (!md_entry_supported(call->number)) return MD_ENTRY_DECLINED;
    int task_route=route==MD_ENTRY_TASK || route==MD_ENTRY_READINESS || route==MD_ENTRY_MEMORY;
    /* The resident frontend owns the frame that defers application handlers
     * across C execution. Ordinary API clients retain their kernel context. */
    int task_execution=tid>0 && task_route;
    call->next=NULL;
    call->return_checkpoint=0;
    uintptr_t control=__atomic_fetch_or(&client->opaque[MD_ENTRY_CLIENT_CONTROL],MD_ENTRY_CLIENT_BUSY,__ATOMIC_ACQUIRE);
    if (control&MD_ENTRY_CLIENT_BUSY) return MD_ENTRY_DECLINED;
    call->continuation[CONT_CONTROL]=control;
    call->continuation[CONT_CHANNEL]=0;
    struct md_entry_channel *c=(void *)client->opaque[MD_ENTRY_CLIENT_CHANNEL];
    enum md_entry_outcome outcome=MD_ENTRY_DECLINED;
    int admitted=0;
    if (c && !atomic_load_explicit(&c->enabled,memory_order_acquire)) goto kernel;
    if (!c) {
        uintptr_t registration=__atomic_load_n(&md_cooperative_registration,__ATOMIC_ACQUIRE);
        if (!registration) { control=(uintptr_t)UINT32_MAX<<1; goto kernel; }
        /* Unfiltered task routes need no channel. Filtered probes need the
         * original policy even when this is the thread's first offered call. */
        if (registration==1 && task_route && !task_execution) goto kernel;
        uint32_t retry=(uint32_t)(control>>1);
        if (retry) {
            if (retry!=UINT32_MAX) control=(uintptr_t)(retry-1)<<1;
            goto kernel;
        }
        long address=md_guest_buffer_admit(MD_ENTRY_BIND);
        if (address==-EAGAIN) address=md_guest_buffer_control(MD_BUFFER_CHANNEL_INSTALL,0,0);
        if (address<0) {
            control=(uintptr_t)(address==-EAGAIN || address==-ENOMEM || address==-EINTR ? 128 : UINT32_MAX)<<1;
            goto kernel;
        }
        c=(void *)address;
        client->opaque[MD_ENTRY_CLIENT_CHANNEL]=(uintptr_t)c;
        client->opaque[MD_ENTRY_CLIENT_OWNER]=(uintptr_t)c->owner;
    }
    if (c) {
        if ((uintptr_t)(tid>0 ? tid : RAW0(gettid))!=client->opaque[MD_ENTRY_CLIENT_OWNER]) goto done;
        int policy=admit(c,call->number,call->args,call->instruction_pointer,&call->result);
        if (policy==POLICY_FALLBACK) goto kernel;
        if (policy==POLICY_RESULT) return finish(client,call,MD_ENTRY_COMPLETED);
        admitted=1;
    }
    if (c && md_identity_view_read(&c->identity,call->number,&call->result))
        return finish(client,call,MD_ENTRY_COMPLETED);
    if (c && tid>0 && call->number==SYS_uname) {
        struct utsname names;
        unsigned idle=0;
        if (md_uts_view_read(&c->uts,&names)
                && atomic_compare_exchange_strong_explicit(&c->caller,&idle,1,memory_order_acquire,memory_order_relaxed)) {
            atomic_store_explicit(&c->copying,1,memory_order_release);
            call->result=md_write_memory((void *)call->args[0],&names,sizeof(names));
            atomic_store_explicit(&c->copying,0,memory_order_release);
            atomic_store_explicit(&c->caller,0,memory_order_release);
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
    }
    if (task_route) {
        /* Guest policy was evaluated above at the original SVC. Local routing
         * then avoids a supervisor round trip for ordinary native operations
         * and unconditional errors from our own interception contract. */
        struct seccomp_data data={.nr=call->number,.arch=AUDIT_ARCH_AARCH64,
            .instruction_pointer=call->instruction_pointer};
        memcpy(data.args,call->args,sizeof(data.args));
        uint32_t decision;
        if (md_interception_route_eval(&routes,&c->routes,&data,&decision)) goto kernel;
        if (decision==SECCOMP_RET_ALLOW) {
            if (c->route_statistics) atomic_fetch_add_explicit(&c->local_native,1,memory_order_relaxed);
            outcome=tid>0 ? admitted_kernel_call(c,call) : kernel_call(call); goto done;
        }
        if ((decision&SECCOMP_RET_ACTION_FULL)==SECCOMP_RET_ERRNO) {
            call->result=md_seccomp_policy_error(decision);
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
    }
    int probe=0,immediate=0;
    if (c && route==MD_ENTRY_READINESS && tid>0 && !call->args[4]) {
        probe=(call->number==SYS_epoll_pwait && (int)call->args[3]<0)
            || (call->number==SYS_epoll_pwait2 && !call->args[3]);
        if (c && (uintptr_t)tid==client->opaque[MD_ENTRY_CLIENT_OWNER]
                && call->number==SYS_epoll_pwait2 && call->args[3]) {
            struct timespec timeout;
            unsigned idle=0;
            if (atomic_compare_exchange_strong_explicit(&c->caller,&idle,1,
                    memory_order_acquire,memory_order_relaxed)) {
                atomic_store_explicit(&c->copying,1,memory_order_release);
                int error=md_read_memory(&timeout,(void *)call->args[3],sizeof(timeout));
                atomic_store_explicit(&c->copying,0,memory_order_release);
                atomic_store_explicit(&c->caller,0,memory_order_release);
                immediate=!error && !timeout.tv_sec && !timeout.tv_nsec;
            }
            probe=immediate;
        }
    }
    if (probe) {
        /* Infinite or zero-time unmasked waits need no deadline adjustment.
         * Both ABIs use the scalar zero-time kernel probe. Dynamic queue
         * selectors still collect virtual events. The original call has passed
         * policy admission; the owned probe is not a second guest request.
         * No raw gate or FD cache is used. */
        uint64_t args[6];
        for (unsigned i=0;i<6;++i) args[i]=call->args[i];
        args[3]=0;
        unsigned idle=0;
        if (atomic_load_explicit(&c->policy,memory_order_acquire)
                && atomic_compare_exchange_strong_explicit(&c->caller,&idle,1,memory_order_acquire,memory_order_relaxed)) {
            call->continuation[CONT_CHANNEL]=(uintptr_t)c;
            unsigned state=atomic_load_explicit(&c->state,memory_order_acquire);
            if ((state==MD_ENTRY_IDLE || state==MD_ENTRY_RETIRING) && c->sequence!=UINT64_MAX) {
                struct md_entry_call request=*call;
                request.number=SYS_epoll_pwait;
                memcpy(request.args,args,sizeof(args));
                request.continuation[CONT_SEQUENCE]=c->sequence;
                request.continuation[CONT_FLAGS]=CALL_NATIVE;
                enum md_entry_outcome result=publish(client,&request);
                while (result==MD_ENTRY_NATIVE_EFFECT) result=md_entry_advance(client,&request,result);
                if (result==MD_ENTRY_COMPLETED && (request.result || immediate)) {
                    call->result=request.result; return result;
                }
                if (result==MD_ENTRY_UNKNOWN) return result;
                if (result==MD_ENTRY_COMPLETED && task_execution) {
                    /* The zero-time probe has retired its request. Continue
                     * the original admitted wait through its owner, not Base. */
                    uintptr_t previous=__atomic_fetch_or(&client->opaque[MD_ENTRY_CLIENT_CONTROL],
                        MD_ENTRY_CLIENT_BUSY,__ATOMIC_ACQUIRE);
                    if (previous&MD_ENTRY_CLIENT_BUSY) return kernel_call(call);
                    call->continuation[CONT_CONTROL]=previous;
                    call->continuation[CONT_CHANNEL]=0;
                    goto classified;
                }
                return kernel_call(call);
            }
            atomic_store_explicit(&c->caller,0,memory_order_release);
            call->continuation[CONT_CHANNEL]=0;
        }
        long result=md_guest_entry_kernel(SYS_epoll_pwait,args);
        if (result || immediate) {
            call->result=result;
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
    }
classified:
    if (task_route && !task_execution) goto kernel;
    if (!atomic_load_explicit(&c->enabled,memory_order_acquire)) goto kernel;
    /* Different entry adapters in one thread share the channel. The client
     * guard alone cannot exclude a signal handler using another adapter. */
    unsigned idle=0;
    if (!atomic_compare_exchange_strong_explicit(&c->caller,&idle,1,
            memory_order_acquire,memory_order_relaxed)) goto kernel;
    call->continuation[CONT_CHANNEL]=(uintptr_t)c;
    unsigned available=atomic_load_explicit(&c->state,memory_order_acquire);
    if (!atomic_load_explicit(&c->enabled,memory_order_acquire)
            || (available!=MD_ENTRY_IDLE && available!=MD_ENTRY_RETIRING)
            || c->sequence==UINT64_MAX) goto done;
    if (tid>0 && (call->number==SYS_lseek || call->number==SYS_fcntl || call->number==SYS_ioctl)
            && (int)call->args[0]>=0 && md_entry_view_descriptor(c)==(int)call->args[0]
            && (md_fd_view_query(call->number,call->args,c->descriptor.kind)
                || (call->number==SYS_fcntl && (unsigned)call->args[1]==F_SETFD
                    && (call->args[2]&FD_CLOEXEC)==c->descriptor.flags))) {
        /* The kernel still owns the shared OFD offset and validates whence.
         * No cached result or predicted next operation is involved. */
        if (call->number==SYS_lseek) {
            call->result=RAW3(lseek,call->args[0],call->args[1],call->args[2]);
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
        unsigned command=(unsigned)call->args[1];
        if (call->number==SYS_fcntl && (command==F_GETFD || command==F_SETFD)) {
            call->result=command==F_GETFD ? c->descriptor.flags : 0;
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
        if (call->number==SYS_ioctl) {
            /* Reuse the admitted native-effect gate, not the generic raw gate
             * whose ioctl continuation requires a stop. Keep the query journal
             * private: the channel may still be retiring the preceding open. */
            struct md_entry_native query={.state=MD_NATIVE_READY,.number=(int)call->number,.result=-EINTR};
            memcpy(query.args,call->args,sizeof(query.args));
            md_cooperative_native(&query);
            call->result=query.result;
        } else call->result=RAW3(fcntl,call->args[0],command,call->args[2]);
        return finish(client,call,MD_ENTRY_COMPLETED);
    }
    int native_fd=md_entry_native_fd(call->number,call->args);
    if (task_execution && !native_fd) {
        call->continuation[CONT_SEQUENCE]=c->sequence;
        call->continuation[CONT_FLAGS]=CALL_BEGIN;
        return task_execute(client,call);
    }
    int native=native_fd || md_entry_native_open(call->number,call->args);
    int descriptor=route==MD_ENTRY_FD && !native;
    call->continuation[CONT_CHANNEL]=(uintptr_t)c;
    call->continuation[CONT_SEQUENCE]=c->sequence;
    call->continuation[CONT_FLAGS]=(native ? CALL_NATIVE : 0)
        |(descriptor ? CALL_DESCRIPTOR : 0);
    if (native && md_entry_open(call->number) && call->cancel) {
        call->next=publish;
        return MD_ENTRY_CHECK_CANCEL;
    }
    return publish(client,call);
done:
    call->continuation[CONT_CONTROL]=control;
    return finish(client,call,outcome);
kernel:
    /* Without policy admission only a caller-owned application site is safe.
     * The runtime fallback cannot stand in for an unknown original PC. */
    if (!admitted && __atomic_load_n(&md_cooperative_registration,__ATOMIC_ACQUIRE)!=1
            && !call->kernel) goto done;
    outcome=kernel_call(call);
    goto done;
}
static enum md_entry_outcome invoke(struct md_entry_client *client,struct md_entry_call *call) {
    return invoke_identified(client,0,call);
}
static enum md_entry_outcome publish(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    md_fd_view_revoke(&c->descriptor);
    unsigned flags=(unsigned)call->continuation[CONT_FLAGS];
    int native=!!(flags&CALL_NATIVE),descriptor=!!(flags&CALL_DESCRIPTOR);
    unsigned kind=flags>>CALL_KIND_SHIFT;
    if (!kind && (!native || md_entry_open(call->number))) capture_paths(c,call);
    ++c->sequence; c->call.number=call->number;
    c->call.reserved=kind;
    if (kind==MD_ENTRY_EXECUTION_BEGIN) c->instruction_pointer=call->instruction_pointer;
    for (unsigned i=0;i<6;++i) c->call.args[i]=call->args[i];
    call->continuation[CONT_SEQUENCE]=c->sequence;
    int wake=md_entry_publish_request(c,descriptor ? MD_ENTRY_FD_REQUEST : MD_ENTRY_REQUEST);
    call->result=-EIO;
    if (descriptor) {
        /* The original syscall is the only publication carrier. Its kernel
         * admission also preserves libc cancellation regions and filters.
         * No descriptor effect is allowed before the exact notification. */
        call->next=resume_kernel;
        return kernel_call(call);
    } else if (wake)
        wake_owner(c);
    return progress(client,call);
}
static enum md_entry_outcome progress(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    unsigned flags=(unsigned)call->continuation[CONT_FLAGS];
    unsigned request=flags>>CALL_KIND_SHIFT;
    int descriptor=!!(flags&CALL_DESCRIPTOR);
    long descriptor_result=call->result;
    enum md_entry_outcome outcome=MD_ENTRY_UNKNOWN;
    unsigned budget=0, parked=0, checks=0, native_sent=!!(flags&CALL_NATIVE_SENT);
    struct md_entry_native *wait_frame=NULL;
    /* The native plan and its publication are independent waits. A slow
     * creation must not disable bounded spinning for short metadata replies. */
    enum md_entry_wait kind=md_entry_wait_kind(call->number,native_sent);
    struct md_spin_policy policy=c->feedback[kind];
    for (;;) {
        unsigned state=atomic_load_explicit(&c->state,memory_order_acquire);
        if (state!=MD_ENTRY_PARKED && wait_frame) {
            atomic_store_explicit(&wait_frame->state,MD_NATIVE_EMPTY,memory_order_release);
            wait_frame=NULL;
        }
        if (state==MD_ENTRY_PARKED && !wait_frame) {
            uintptr_t sp;
            __asm__ volatile("mov %0, sp" : "=r"(sp));
            if (md_svc_frame_client(md_svc_runtime.frames,sp)==client) {
                uintptr_t frame=(sp-1)&~(uintptr_t)(MD_SVC_SLOT_SIZE-1);
                wait_frame=(void *)(frame+MD_SVC_NATIVE_OFFSET);
                /* The owner has admitted the operation and released its turns.
                 * A policy fence may cross this wait, but signals must still
                 * finish the owner before abandoning the resident stack. */
                atomic_store_explicit(&wait_frame->state,MD_NATIVE_OWNED,memory_order_release);
            }
            parked=1;
        }
        if (state==MD_ENTRY_NATIVE && !native_sent) {
            /* Receiving the offered effect is the response to this wait. Its
             * caller-side execution and retirement are a separate phase. */
            if (budget) md_spin_result(&policy,!parked);
            c->feedback[kind]=policy;
            if (atomic_load_explicit(&c->native.state,memory_order_acquire)==MD_NATIVE_READY) {
                /* Reenter the caller's cancellation check before the effect,
                 * never after a successful open. Policy admission is retained. */
                if (!request && md_entry_open(call->number) && call->cancel) {
                    call->next=offer_native;
                    return MD_ENTRY_CHECK_CANCEL;
                }
                return offer_native(client,call);
            }
            return resume_native(client,call);
        }
        if (state==MD_ENTRY_RESPONSE) {
            if (c->receipt!=c->sequence) break;
            outcome=c->outcome; call->result=c->result;
            if (outcome!=MD_ENTRY_DECLINED && outcome!=MD_ENTRY_COMPLETED
                    && !(outcome==MD_ENTRY_DISPATCH && !request && !descriptor)
                    && !(request && outcome==MD_ENTRY_NEEDS_STOP)) outcome=MD_ENTRY_UNKNOWN;
            if (descriptor && outcome!=MD_ENTRY_UNKNOWN) {
                outcome=MD_ENTRY_COMPLETED; call->result=descriptor_result;
            }
            if (!descriptor && outcome==MD_ENTRY_COMPLETED && call->result>=0
                    && c->output.sequence==c->sequence) {
                if (c->output.size>sizeof(c->output.bytes)) {
                    outcome=MD_ENTRY_UNKNOWN; call->result=-EPROTO;
                } else {
                    atomic_store_explicit(&c->copying,1,memory_order_release);
                    long copied=md_write_memory((void *)c->output.destination,c->output.bytes,c->output.size);
                    atomic_store_explicit(&c->copying,0,memory_order_release);
                    if (copied) call->result=copied;
                }
            }
            atomic_store_explicit(&c->state,MD_ENTRY_IDLE,memory_order_release);
            break;
        }
        if (state==MD_ENTRY_CLOSED) break;
        /* Bound active work without borrowing the application's clock mappings.
         * A wake resumes the same wait; it never replenishes the spin budget. */
        if (!checks && !parked) budget=md_spin_budget(&policy,32768);
        if (checks<budget) {
            ++checks;
            __asm__ volatile("yield" ::: "memory");
            continue;
        }
        parked=1;
        atomic_store_explicit(&c->sleeping,1,memory_order_seq_cst);
        state=atomic_load_explicit(&c->state,memory_order_seq_cst);
        if (state==MD_ENTRY_REQUEST || state==MD_ENTRY_RUNNING || state==MD_ENTRY_PARKED
                || (state==MD_ENTRY_NATIVE && native_sent)) {
            /* EVENT_WAIT: exact response or owner closure. Launch teardown
             * terminates the task. Never abandon an accepted output pointer
             * while its worker can still write into the caller's stack. */
            RAW6(futex,&c->state,FUTEX_WAIT,state,0,0,0);
        }
        atomic_store_explicit(&c->sleeping,0,memory_order_seq_cst);
    }
    /* A response ready after parking is not evidence that spinning helped. */
    if (budget) md_spin_result(&policy,!parked && outcome==MD_ENTRY_COMPLETED);
    c->feedback[kind]=policy;
    if (outcome==MD_ENTRY_DISPATCH) {
        uintptr_t sp;
        __asm__ volatile("mov %0, sp" : "=r"(sp));
        if (md_svc_frame_client(md_svc_runtime.frames,sp)==client) {
            /* The worker released all turns without committing an effect.
             * Transfer to the same typed owner as initial task admission;
             * only that owner may request an actual stopped context. */
            call->continuation[CONT_FLAGS]=CALL_BEGIN;
            return task_execute(client,call);
        }
        outcome=kernel_call(call);
    }
    /* DECLINED is an explicit pre-execution transfer of ownership. Once the
     * worker accepts a call, UNKNOWN is terminal and must never enter here. */
    if (outcome==MD_ENTRY_DECLINED && (!request || request==MD_ENTRY_EXECUTION_BEGIN)) {
        outcome=kernel_call(call);
    }
    return finish(client,call,outcome);
}
static enum md_entry_outcome task_execute(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=(void *)call->continuation[CONT_CHANNEL];
    struct md_entry_call request=*call;
    int started=0;
    for (;;) {
        enum md_entry_outcome outcome=publish(client,&request);
        while (outcome==MD_ENTRY_NATIVE_EFFECT) outcome=md_entry_advance(client,&request,outcome);
        if (!started) *call=request;
        call->result=request.result;
        if (outcome==MD_ENTRY_NEEDS_STOP) {
            /* Use the owner's final result, which can change during a retained
             * wait in either executor. Recover the original frame without
             * replaying effects or requiring a preceding C adapter. */
            md_guest_buffer_control_kernel(MD_BUFFER_FINISH,0,(unsigned long)request.result);
            return MD_ENTRY_UNKNOWN;
        }
        if (outcome!=MD_ENTRY_COMPLETED) return started ? MD_ENTRY_UNKNOWN : outcome;
        call->return_checkpoint=c->return_checkpoint;
        switch (c->execution_action) {
        case MD_EXECUTION_RESULT: return outcome;
        case MD_EXECUTION_NATIVE:
            call->effect=native_effect;
            return MD_ENTRY_NATIVE_CALL;
        case MD_EXECUTION_CONTEXT:
            return started ? MD_ENTRY_UNKNOWN : kernel_call(call);
        case MD_EXECUTION_GUEST: break;
        default: return MD_ENTRY_UNKNOWN;
        }
        /* Initial execution and retained continuations use the same action
         * contract. A resumed owner has not admitted a second syscall. */
        started=1;
        struct md_guest_execution execution=c->execution;
        long result=md_guest_execute(&execution);
        uintptr_t control=__atomic_fetch_or(&client->opaque[MD_ENTRY_CLIENT_CONTROL],MD_ENTRY_CLIENT_BUSY,__ATOMIC_ACQUIRE);
        unsigned idle=0;
        if ((control&MD_ENTRY_CLIENT_BUSY) || !atomic_compare_exchange_strong_explicit(
                &c->caller,&idle,1,memory_order_acquire,memory_order_relaxed)) return MD_ENTRY_UNKNOWN;
        request=*call;
        request.continuation[CONT_CONTROL]=control;
        request.continuation[CONT_CHANNEL]=(uintptr_t)c;
        request.continuation[CONT_SEQUENCE]=c->sequence;
        request.continuation[CONT_FLAGS]=CALL_END;
        request.args[0]=(uint64_t)result;
    }
}
extern struct md_adapter_input md_guest_buffer_start_kernel(unsigned,unsigned long,unsigned long);
extern struct md_fd_observation md_guest_fd_control_kernel(unsigned,uint64_t,long,long);
struct control_reply { long value; uintptr_t extra; };
static int control_request(unsigned kind,unsigned command,const uint64_t args[6],
        struct control_reply *reply) {
    if (!md_svc_runtime.frames) return 0;
    struct md_svc_payload_state *state=(void *)(md_svc_runtime.frames+MD_SVC_POOL_SIZE);
    uintptr_t sp;
    __asm__ volatile("mov %0, sp" : "=r"(sp));
    struct md_entry_client *client=md_svc_frame_client(md_svc_runtime.frames,sp);
    /* A Base adapter owns a kernel continuation. Only a resident execution
     * can retain these multi-step controls without that continuation. */
    if (!client && kind==MD_ENTRY_BUFFER_CONTROL
            && (md_adapter_buffer_resident(command) || md_fs_buffer_resident(command))) return 0;
    long tid=client ? (long)client->opaque[MD_ENTRY_CLIENT_OWNER] : 0;
    if (!tid) {
        tid=(long)__atomic_load_n(&state->sole_owner,__ATOMIC_ACQUIRE);
        if (!tid) tid=RAW0(gettid);
        client=tid>0 ? md_svc_client_find(state,(uint64_t)tid) : NULL;
    }
    if (!client) return 0;
    uintptr_t control=__atomic_fetch_or(&client->opaque[MD_ENTRY_CLIENT_CONTROL],MD_ENTRY_CLIENT_BUSY,__ATOMIC_ACQUIRE);
    if (control&MD_ENTRY_CLIENT_BUSY) return 0;
    struct md_entry_call call={.number=(int)command};
    for (unsigned i=0;i<6;++i) call.args[i]=args[i];
    call.continuation[CONT_CONTROL]=control;
    struct md_entry_channel *c=(void *)client->opaque[MD_ENTRY_CLIENT_CHANNEL];
    unsigned idle=0;
    if (!c || (uintptr_t)tid!=client->opaque[MD_ENTRY_CLIENT_OWNER]
            || !atomic_compare_exchange_strong_explicit(&c->caller,&idle,1,memory_order_acquire,memory_order_relaxed))
        goto fallback;
    call.continuation[CONT_CHANNEL]=(uintptr_t)c;
    if (atomic_load_explicit(&c->state,memory_order_acquire)!=MD_ENTRY_IDLE || c->sequence==UINT64_MAX)
        goto fallback;
    call.continuation[CONT_SEQUENCE]=c->sequence;
    call.continuation[CONT_FLAGS]=(uintptr_t)kind<<CALL_KIND_SHIFT;
    enum md_entry_outcome outcome=publish(client,&call);
    while (outcome==MD_ENTRY_NATIVE_EFFECT) outcome=md_entry_advance(client,&call,outcome);
    if (outcome==MD_ENTRY_COMPLETED) {
        *reply=(struct control_reply){call.result,c->result_extra}; return 1;
    }
    if (outcome!=MD_ENTRY_DECLINED && outcome!=MD_ENTRY_NEEDS_STOP) md_die("entry control completion",-EPROTO);
    return 0;
fallback:
    finish(client,&call,MD_ENTRY_DECLINED);
    return 0;
}
long md_guest_buffer_control(unsigned command,unsigned long first,unsigned long second) {
    struct control_reply reply;
    if ((md_adapter_buffer_shared(command) || md_fs_buffer_shared(command))
            && control_request(MD_ENTRY_BUFFER_CONTROL,command,(uint64_t[6]){first,second},&reply)) return reply.value;
    return md_guest_buffer_control_kernel(command,first,second);
}
struct md_adapter_input md_guest_buffer_start(unsigned command,unsigned long first,unsigned long second) {
    struct control_reply reply;
    if ((md_adapter_buffer_shared(command) || md_fs_buffer_shared(command))
            && control_request(MD_ENTRY_BUFFER_CONTROL,command,(uint64_t[6]){first,second},&reply))
        return (struct md_adapter_input){reply.value,(void *)reply.extra};
    return md_guest_buffer_start_kernel(command,first,second);
}
struct md_fd_observation md_guest_fd_control(unsigned command,uint64_t id,long first,long second) {
    struct control_reply reply;
    if (md_fd_control_shared(command)
            && control_request(MD_ENTRY_FD_CONTROL,command,(uint64_t[6]){id,(uint64_t)first,(uint64_t)second},&reply))
        return (struct md_fd_observation){reply.value,reply.extra};
    return md_guest_fd_control_kernel(command,id,first,second);
}
long md_guest_resource_control(unsigned service,unsigned operation,
        unsigned long first,unsigned long second,unsigned long third,unsigned long fourth) {
    struct control_reply reply;
    if (control_request(MD_ENTRY_RESOURCE_CONTROL,service,(uint64_t[6]){operation,first,second,third,fourth},&reply))
        return reply.value;
    return RAW6(prctl,service,operation,first,second,third,fourth);
}
extern long md_guest_native_call_kernel(long,long,long,long,long,long,long);
long md_guest_native_call(long number,const unsigned long args[6]) {
    uintptr_t sp;
    __asm__ volatile("mov %0, sp" : "=r"(sp));
    struct md_entry_client *client=md_svc_frame_client(md_svc_runtime.frames,sp);
    if (client) {
        struct md_entry_channel *c=(void *)client->opaque[MD_ENTRY_CLIENT_CHANNEL];
        uint64_t values[6]; memcpy(values,args,sizeof(values));
        enum md_execution_context context=md_execution_native_context(number,values);
        if (c && !c->adapter_native_managed
                && (atomic_load_explicit(&c->routes.flags,memory_order_acquire)&MD_ROUTE_ADMITTED_KERNEL)
                && (context==MD_CONTEXT_COUNT || context==MD_CONTEXT_WAIT)) {
            struct md_entry_native *native=&c->adapter_native;
            native->number=(int32_t)number;
            memcpy(native->args,values,sizeof(values));
            native->result=0;
            atomic_store_explicit(&native->state,MD_NATIVE_READY,memory_order_release);
            native_effect(native);
            unsigned state=atomic_load_explicit(&native->state,memory_order_acquire);
            if (state!=MD_NATIVE_DONE && state!=MD_NATIVE_CANCELLED)
                md_die("adapter native completion",-EPROTO);
            long result=state==MD_NATIVE_CANCELLED ? RESTART_SIGNAL : native->result;
            atomic_store_explicit(&native->state,MD_NATIVE_EMPTY,memory_order_release);
            if (c->route_statistics) atomic_fetch_add_explicit(&c->local_native,1,memory_order_relaxed);
            return result;
        }
        struct control_reply reply;
        if (!control_request(MD_ENTRY_NATIVE_REQUEST,(unsigned)number,values,&reply))
            md_die("entry native completion",-EPROTO);
        return reply.value;
    }
    return md_guest_native_call_kernel(number,args[0],args[1],args[2],args[3],args[4],args[5]);
}
static long thread_identity(void) { return RAW0(gettid); }
static uintptr_t client_block(unsigned bucket) {
    long result=md_guest_buffer_control(MD_BUFFER_CLIENT_INSTALL,bucket,0);
    return result>0 ? (uintptr_t)result : 0;
}
const struct md_entry_api md_cooperative_entry={
    .version=MD_ENTRY_VERSION,.size=sizeof(struct md_entry_api),.try_syscall=invoke,
    .offers=offers,.syscall_count=sizeof(offers),.thread_identity=thread_identity,.native_memory=native_memory,
    .try_identified=invoke_identified,.client_block=client_block
};
