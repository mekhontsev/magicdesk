#include "cooperative_channel.h"
#include "interception.h"
#include "raw.h"
#include "spin_policy.h"
#include "file_open.h"
#include "fs_syscall_strings.h"
#include <errno.h>
#include <limits.h>
#include <linux/futex.h>
#include <sys/mman.h>
#include <time.h>

extern long md_guest_entry_kernel(long,const uint64_t *);
static unsigned char offers[512];
static unsigned char strings[512];
static uintptr_t registration_allowed=1;
void md_cooperative_revoke(void) {
    __atomic_store_n(&registration_allowed,0,__ATOMIC_RELEASE);
}
void md_cooperative_init(void) {
    for (unsigned i=0;i<sizeof(offers);++i) {
        enum md_entry_route route=md_entry_route(i);
        offers[i]=(route!=MD_ENTRY_CALLER ? MD_ENTRY_ORDINARY : 0)
            |(route==MD_ENTRY_FD ? MD_ENTRY_CANCELLATION : 0)
            |(route==MD_ENTRY_MEMORY ? MD_ENTRY_NATIVE_MEMORY : 0);
        strings[i]=(unsigned char)md_fs_syscall_strings(i);
    }
}
static enum md_entry_outcome kernel_call(struct md_entry_call *call) {
    if (!call->kernel) call->kernel=md_guest_entry_kernel;
    return MD_ENTRY_KERNEL_CALL;
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
enum { CALL_NATIVE=1, CALL_DESCRIPTOR=2, CALL_NATIVE_SENT=4 };
static enum md_entry_outcome progress(struct md_entry_client *,struct md_entry_call *);
static enum md_entry_outcome publish(struct md_entry_client *,struct md_entry_call *);
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
static void native_effect(void *context) { md_cooperative_native(context); }
static int native_memory(struct md_entry_client *client,long tid,long number,const uint64_t *args,int64_t *result) {
    if (tid<=0 || (uintptr_t)tid!=client->opaque[MD_ENTRY_CLIENT_OWNER]) return 0;
    /* Growth flags can affect pages outside the supplied range. Such calls
     * need the ordinary memory owner, as do unknown protection semantics. */
    if (!md_memory_native_arguments(number,args[2],args[3])) return 0;
    struct md_entry_channel *c=(void *)client->opaque[MD_ENTRY_CLIENT_CHANNEL];
    if (!c || !atomic_load_explicit(&c->enabled,memory_order_acquire)
            || !md_memory_admission_allows(&c->memory,args[0],args[1],md_page_size)) return 0;
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
    if (md_entry_open(call->number)) c->native.args[1]=(uintptr_t)c->native_path;
    call->effect=native_effect; call->effect_context=&c->native; call->next=resume_native;
    return MD_ENTRY_NATIVE_EFFECT;
}
static enum md_entry_outcome invoke_identified(struct md_entry_client *client,long tid,struct md_entry_call *call) {
    if (!client || !call || call->reserved || !md_entry_supported(call->number)) return MD_ENTRY_DECLINED;
    call->next=NULL;
    uintptr_t control=__atomic_fetch_or(&client->opaque[MD_ENTRY_CLIENT_CONTROL],MD_ENTRY_CLIENT_BUSY,__ATOMIC_ACQUIRE);
    if (control&MD_ENTRY_CLIENT_BUSY) return MD_ENTRY_DECLINED;
    call->continuation[CONT_CONTROL]=control;
    call->continuation[CONT_CHANNEL]=0;
    struct md_entry_channel *c=(void *)client->opaque[MD_ENTRY_CLIENT_CHANNEL];
    enum md_entry_outcome outcome=MD_ENTRY_DECLINED;
    if (c && !atomic_load_explicit(&c->enabled,memory_order_acquire)) goto kernel;
    int probe=0,immediate=0;
    if (md_entry_route(call->number)==MD_ENTRY_READINESS && tid>0 && !call->args[4]) {
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
         * selectors still collect virtual events; application filters revoke
         * this entry before installation. No raw gate or FD cache is used. */
        uint64_t args[6];
        for (unsigned i=0;i<6;++i) args[i]=call->args[i];
        args[3]=0;
        long result=md_guest_entry_kernel(SYS_epoll_pwait,args);
        if (result || immediate) {
            call->result=result;
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
    }
    if (md_entry_route(call->number)==MD_ENTRY_TASK || md_entry_route(call->number)==MD_ENTRY_READINESS) goto kernel;
    if (!c) {
        /* Monotonic per-image hint, revoked before an application filter can
         * forbid helpers. Existing channels retain their per-task eligibility;
         * this flag never grants authority to the supervisor or worker. */
        if (!__atomic_load_n(&registration_allowed,__ATOMIC_ACQUIRE)) {
            control=(uintptr_t)UINT32_MAX<<1;
            goto kernel;
        }
        uint32_t retry=(uint32_t)(control>>1);
        if (retry) {
            if (retry!=UINT32_MAX) control=(uintptr_t)(retry-1)<<1;
            goto kernel;
        }
        /* This entry has the application's signal mask. A synchronous BRK
         * would unblock a blocked SIGTRAP and reset its disposition in Linux,
         * even when ptrace consumes the stop. Admission uses the syscall gate. */
        long address=md_guest_buffer_admit(MD_ENTRY_BIND);
        if (address<0) {
            control=(uintptr_t)(address==-EAGAIN || address==-ENOMEM || address==-EINTR ? 128 : UINT32_MAX)<<1;
            goto kernel;
        }
        c=(void *)address;
        client->opaque[MD_ENTRY_CLIENT_CHANNEL]=(uintptr_t)c;
        client->opaque[MD_ENTRY_CLIENT_OWNER]=(uintptr_t)c->owner;
    } else if ((uintptr_t)(tid>0 ? tid : RAW0(gettid))!=client->opaque[MD_ENTRY_CLIENT_OWNER]) goto done;
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
    if (tid>0 && (call->number==SYS_lseek || call->number==SYS_fcntl)
            && (int)call->args[0]>=0 && md_entry_regular_descriptor(c)==(int)call->args[0]) {
        /* The kernel still owns the shared OFD offset and validates whence.
         * No cached result or predicted next operation is involved. */
        if (call->number==SYS_lseek) {
            call->result=RAW3(lseek,call->args[0],call->args[1],call->args[2]);
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
        unsigned command=(unsigned)call->args[1];
        if (command==F_GETFD || (command==F_SETFD && (call->args[2]&FD_CLOEXEC)==c->native_flags)) {
            call->result=command==F_GETFD ? c->native_flags : 0;
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
        if (command==F_GETFL) {
            call->result=RAW3(fcntl,call->args[0],command,call->args[2]);
            return finish(client,call,MD_ENTRY_COMPLETED);
        }
    }
    if (md_entry_route(call->number)==MD_ENTRY_MEMORY) goto kernel;
    int native=md_entry_native_fd(call->number,call->args)
        || md_entry_native_open(call->number,call->args);
    int descriptor=md_entry_route(call->number)==MD_ENTRY_FD && !native;
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
    outcome=kernel_call(call);
    goto done;
}
static enum md_entry_outcome invoke(struct md_entry_client *client,struct md_entry_call *call) {
    return invoke_identified(client,0,call);
}
static enum md_entry_outcome publish(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    atomic_store_explicit(&c->native_regular,0,memory_order_release);
    unsigned flags=(unsigned)call->continuation[CONT_FLAGS];
    int native=!!(flags&CALL_NATIVE),descriptor=!!(flags&CALL_DESCRIPTOR);
    if (!native || md_entry_open(call->number)) capture_paths(c,call);
    ++c->sequence; c->call.number=call->number; c->call.reserved=0;
    for (unsigned i=0;i<6;++i) c->call.args[i]=call->args[i];
    call->continuation[CONT_SEQUENCE]=c->sequence;
    atomic_store_explicit(&c->state,descriptor ? MD_ENTRY_FD_REQUEST : MD_ENTRY_REQUEST,memory_order_seq_cst);
    call->result=-EIO;
    if (descriptor) {
        /* The original syscall is the only publication carrier. Its kernel
         * admission also preserves libc cancellation regions and filters.
         * No descriptor effect is allowed before the exact notification. */
        call->next=resume_kernel;
        return kernel_call(call);
    } else if (!atomic_load_explicit(&c->awake,memory_order_seq_cst))
        md_guest_buffer_admit(MD_ENTRY_WAKE);
    return progress(client,call);
}
static enum md_entry_outcome progress(struct md_entry_client *client,struct md_entry_call *call) {
    struct md_entry_channel *c=continuation(client,call);
    if (!c) return finish(client,call,MD_ENTRY_UNKNOWN);
    unsigned flags=(unsigned)call->continuation[CONT_FLAGS];
    int descriptor=!!(flags&CALL_DESCRIPTOR);
    long descriptor_result=call->result;
    enum md_entry_outcome outcome=MD_ENTRY_UNKNOWN;
    unsigned budget=0, parked=0, checks=0, native_sent=!!(flags&CALL_NATIVE_SENT);
    /* The native plan and its publication are independent waits. A slow
     * creation must not disable bounded spinning for short metadata replies. */
    enum md_entry_wait kind=md_entry_wait_kind(call->number,native_sent);
    struct md_spin_policy policy=c->feedback[kind];
    for (;;) {
        unsigned state=atomic_load_explicit(&c->state,memory_order_acquire);
        if (state==MD_ENTRY_NATIVE && !native_sent) {
            /* Receiving the offered effect is the response to this wait. Its
             * caller-side execution and retirement are a separate phase. */
            if (budget) md_spin_result(&policy,!parked);
            c->feedback[kind]=policy;
            if (atomic_load_explicit(&c->native.state,memory_order_acquire)==MD_NATIVE_READY) {
                /* Reenter libc's cancellation check before the effect, never
                 * after a successful open. Filtered callers cannot get a plan. */
                if (md_entry_open(call->number) && call->cancel) {
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
            if (outcome!=MD_ENTRY_DECLINED && outcome!=MD_ENTRY_COMPLETED) outcome=MD_ENTRY_UNKNOWN;
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
        if (state==MD_ENTRY_REQUEST || state==MD_ENTRY_RUNNING || (state==MD_ENTRY_NATIVE && native_sent)) {
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
    /* DECLINED is an explicit pre-execution transfer of ownership. Once the
     * worker accepts a call, UNKNOWN is terminal and must never enter here. */
    if (outcome==MD_ENTRY_DECLINED) {
        outcome=kernel_call(call);
    }
    return finish(client,call,outcome);
}
static long thread_identity(void) { return RAW0(gettid); }
const struct md_entry_api md_cooperative_entry={
    .version=MD_ENTRY_VERSION,.size=sizeof(struct md_entry_api),.try_syscall=invoke,
    .offers=offers,.syscall_count=sizeof(offers),.thread_identity=thread_identity,.native_memory=native_memory,
    .try_identified=invoke_identified
};
