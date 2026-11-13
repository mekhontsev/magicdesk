#define _GNU_SOURCE
#include "raw.h"
#include "supervisor_readiness.h"
#include "adapter_buffer.h"
#include "control_file.h"
#include "cooperative_channel.h"
#include "event_wait.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fs_anchor.h"
#include "fs_buffer_service.h"
#include "fs_mount_internal.h"
#include "fs_worker.h"
#include "guest_debugger.h"
#include "guest_execution.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "ipc_namespace.h"
#include "linux_abi.h"
#include "memory_replace.h"
#include "mount_policy.h"
#include "namespace_broker.h"
#include "pid_namespace.h"
#include "private_memory.h"
#include "proc_file.h"
#include "received_fds.h"
#include "resource_order.h"
#include "seccomp_install.h"
#include "seccomp_policy.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_proc.h"
#include "supervisor_robust.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_frame.h"
#include "svc_payload.h"
#include "svc_registry.h"
#include "sysv_shm.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_helper.h"
#include "task_mapping.h"
#include "task_quiesce.h"
#include "task_registers.h"
#include <stdlib.h>
#include <sys/ioctl.h>

static void mapping_discard(struct mapping_owner *owner);

struct probe_owner { pid_t caller; struct user_pt_regs continuation; };
struct md_interception_memory *entry_fence_memory;
struct thread *entry_fence_owner;
int entry_fence_stage, entry_fence_error;
static uint64_t entry_fence_slots;
int64_t entry_fence_retry_at;
static unsigned entry_fence_waiters;
int64_t entry_fence_deadline;
struct md_task_quiesce *mapping_barrier;
struct mapping_owner *mapping_execution;
struct md_task_quiesce *memory_barrier;
int memory_completions_pending;
struct memory_execution *memory_execution;

static void probe_complete(void *context, struct md_task_helper_result result);
void memory_capture_discard(struct thread *t) {
    if (t->memory_op.capture) {
        t->memory_op.capture->caller=0; t->memory_op.capture=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
}

void memory_buffers_discard(struct thread *t) {
    if (t->probe) {
        t->probe->caller=0; t->probe=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
    if (t->memory_op.buffer_mapping) {
        t->memory_op.buffer_mapping->caller=0; t->memory_op.buffer_mapping=NULL;
        CHECK(!md_task_helper_detach(helpers,t->pid));
    }
}

void memory_operation_discard(struct thread *t) {
    if (t->memory_op.svc_pool) {
        struct svc_pool_mapping *owner=t->memory_op.svc_pool;
        if (owner->order.queue && md_resource_order_cancel(&owner->order)==1)
            CHECK(!md_resource_order_complete(&owner->order));
        t->memory_op.svc_pool=NULL; free(owner);
    }
    if (t->memory_op.mapping) {
        struct mapping_owner *owner=t->memory_op.mapping;
        owner->caller=0; t->memory_op.mapping=NULL;
        if (owner->started) CHECK(!md_task_helper_detach(helpers,t->pid));
        else mapping_discard(owner);
    }
    if (t->memory_op.order.queue && md_resource_order_cancel(&t->memory_op.order)==1)
        CHECK(!md_resource_order_complete(&t->memory_op.order));
    if (t->memory_op.files.table && md_fd_order_cancel(&t->memory_op.files)==1)
        CHECK(!md_fd_order_complete(&t->memory_op.files));
    md_memory_mapping_abandon(t->memory,&t->memory_op.change);
    t->memory_op.action=0; t->memory_op.arguments_only=0;
}

static int svc_pool_candidate(struct thread *t,struct svc_pool_mapping *owner,uintptr_t svc) {
    int error=md_svc_registry_pool_candidate(md_memory_svc(t->memory),t->pid,svc,
        owner->address ? owner->address+MD_SVC_CODE_BYTES : 0,&owner->address);
    if (!error) owner->call.arguments=(struct md_task_call_arguments){.number=SYS_mmap,
        .value={owner->address,MD_SVC_CODE_BYTES,PROT_READ|PROT_EXEC,
            MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED_NOREPLACE,(unsigned long)-1,0}};
    return error;
}

int svc_pool_begin(struct thread *t,const struct user_pt_regs *regs,int status,const struct rusage *usage) {
    if (t->memory_op.svc_pool || (t->phase!=IDLE && (status || t->phase!=DELEGATE_CANCEL))
            || !machine_discovery(t)) return 0;
    /* A pool added after an IP-sensitive mirror is not an owned gate in that
     * mirror. Do not substitute its PC for the application's fallback SVC. */
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory
            && !md_seccomp_policy_address_independent(p->seccomp.policy)) return 0;
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    struct svc_pool_mapping *owner=calloc(1,sizeof(*owner));
    if (!owner) return 0;
    int error=md_svc_registry_pool_reserve(registry);
    if (!error) {
        error=md_memory_order_begin(t->memory,&owner->order,NULL);
        if (error==1) error=md_resource_order_start(&owner->order);
        else if (!error) error=-EAGAIN;
    }
    uintptr_t source=md_svc_registry_call_origin(registry,regs->pc)-4;
    if (!error) error=svc_pool_candidate(t,owner,source);
    enum md_task_call_boundary boundary=MD_TASK_CALL_SECCOMP;
    if (!error && !status) {
        struct ptrace_syscall_info info={0};
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"code-pool-boundary")) error=-ESRCH;
        else if (info.op!=PTRACE_SYSCALL_INFO_NONE && info.op!=PTRACE_SYSCALL_INFO_EXIT) error=-EBUSY;
        else boundary=(enum md_task_call_boundary)info.op;
    }
    if (!error) error=md_task_checkpoint_suspend(&checkpoints,t->pid);
    if (!error) {
        owner->status=status; if (usage) owner->usage=*usage;
        struct md_task_call_arguments arguments=owner->call.arguments;
        owner->call=(struct md_task_call){0};
        error=md_task_call_begin(&owner->call,native_trace,t->pid,boundary,
            regs,abi.task_call,abi.task_return,&arguments);
        if (error && owner->call.stage) {
            CHECK(error==-ESRCH); t->resume_lost=1;
        }
        if (!error || owner->call.stage) {
            if (!status) { notification_release(t); t->phase=IDLE; }
            t->memory_op.svc_pool=owner; t->kernel_stopped=0; return 1;
        }
        md_task_checkpoint_restore(&checkpoints,t->pid);
    }
    if (owner->order.queue && md_resource_order_cancel(&owner->order)==1)
        CHECK(!md_resource_order_complete(&owner->order));
    free(owner); return 0;
}

int svc_pool_event(pid_t pid,int status) {
    struct thread *t=find_thread(pid);
    struct svc_pool_mapping *owner=t ? t->memory_op.svc_pool : NULL;
    unsigned event=(unsigned)status>>16;
    if (!owner || !WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) return 0;
    if (task_call_signal(t,&owner->call,status)) return 1;
    int ready=md_task_call_event(&owner->call,status);
    if (ready<0) { CHECK(ready==-ESRCH); t->resume_lost=1; return 1; }
    if (!ready) { t->kernel_stopped=0; return 1; }
    long result=owner->call.result;
    uintptr_t source=md_svc_registry_call_origin(md_memory_svc(t->memory),owner->call.saved.pc)-4;
    if (result==-EEXIST && !svc_pool_candidate(t,owner,source)) {
        struct md_task_call_arguments arguments=owner->call.arguments;
        int error=md_task_call_again(&owner->call,&arguments);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
        else t->kernel_stopped=0;
        return 1;
    }
    if (result>=0) {
        CHECK((uintptr_t)result==owner->address);
        CHECK(!md_svc_registry_pool(md_memory_svc(t->memory),owner->address));
    }
    SUPERVISOR_TRACE("PROBE machine-pool pid=%d address=%#lx result=%ld\n",pid,owner->address,result);
    t->memory_op.svc_pool=NULL; t->kernel_stopped=1;
    md_task_checkpoint_restore(&checkpoints,pid);
    CHECK(!md_resource_order_complete(&owner->order));
    /* The borrowed call restored the exact original seccomp stop. Resume its
     * common dispatcher, but never recursively allocate on this failed try. */
    t->machine.pool_failed=result<0;
    if (owner->status) execution_seccomp_event(t,pid,owner->status,&owner->usage);
    else {
        struct user_pt_regs regs=owner->call.saved;
        if (result>=0 && machine_observe(t,regs.pc,regs.regs[8])==1) {
            regs.pc-=4;
            if (set_registers(pid,&regs)) resume(pid,0);
        } else {
            execution_capture(t,&regs); ++calls; ++t->calls;
            if (receive_entry(t)) { t->machine.pool_failed=0; free(owner); return 1; }
            if (save_signal_mask(t,&t->execution.mask,"code-pool-fallback") && shield(t)) dispatch_with_stack(t);
        }
    }
    t->machine.pool_failed=0;
    free(owner); return 1;
}

static void probe_begin(struct thread *t, const struct user_pt_regs *regs);
static void buffer_mapping_turn_end(struct buffer_mapping *owner);
static void buffer_mapping_release(struct buffer_mapping *owner);
static void buffer_mapping_complete(void *context,struct md_task_helper_result result);
static void buffer_mapping_begin(struct thread *t,const struct user_pt_regs *regs);
static void entry_mapping_begin(struct thread *t,const struct user_pt_regs *regs,int clients);
static void mapping_begin(struct thread *t);
static void mapping_delivery_release(struct mapping_owner *owner);
static void mapping_guard_release(struct mapping_owner *owner);
static void mapping_complete(void *context, struct md_task_helper_result result);
static void mapping_retired(void *context,long error);
static void mapping_retire(struct mapping_owner *owner,struct thread *t);
static int mapping_helper_start(struct thread *t);
static int mapping_direct_start(struct thread *t);
static int memory_context(struct thread *t,struct user_pt_regs *regs);
static int entry_snapshot_change(const struct thread *t,long nr,const __u64 *args);
static int memory_observation(struct thread *t,const struct ptrace_syscall_info *info,
        struct seccomp_data *observed,int *native_entry);
static void memory_capture_complete(void *context,struct md_task_helper_result result);
static void memory_captured(void *context);
static int entry_fence_adapter_drained(const struct thread *t);
static int entry_fence_boundary(const struct thread *t);
static int memory_participant(void *context,pid_t pid,int stopped,int request);
static void memory_complete(void *context,struct md_task_helper_result result);
static void memory_call(struct memory_execution *execution,const struct md_memory_replace_call *call);
static void memory_finish(struct memory_execution *execution);
static void memory_replay_unmodified(struct memory_execution *execution,int status,const struct rusage *usage);
static int memory_yield_retirement(struct memory_execution *execution);

void entry_fence_cancel(struct thread *t) {
    if (t->entry.fence_waiting) {
        CHECK(entry_fence_waiters); --entry_fence_waiters;
        t->entry.fence_waiting=0;
    }
}

static void probe_complete(void *context, struct md_task_helper_result result) {
    struct probe_owner *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    struct stat st;
    long error=result.value;
    if (!error && result.descriptor<0) error=-EPROTO;
    if (!error && fstat(result.descriptor,&st)) error=-errno;
    if (!error && !S_ISDIR(st.st_mode)) error=-ENOTDIR;
    if (result.descriptor>=0) close(result.descriptor);
    if (t && t->probe==owner) {
        t->probe=NULL; t->kernel_stopped=1;
        buffer_capture(t,&owner->continuation);
        buffer_reply(t,error);
    }
    free(owner);
}

static void probe_begin(struct thread *t, const struct user_pt_regs *regs) {
    struct probe_owner *owner=calloc(1,sizeof(*owner));
    int error=owner ? 0 : -ENOMEM;
    if (!error) {
        *owner=(struct probe_owner){t->pid,*regs};
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug.options);
    }
    if (!error) {
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_CAPTURE,
            .path="/proc/thread-self",.flags=O_PATH|O_DIRECTORY};
        error=md_task_helper_begin(helpers,t->pid,t->tgid,regs,&abi,t->memory,&t->fs.buffer,
            &request,probe_complete,owner);
    }
    if (error) { free(owner); buffer_reply(t,error); }
    else { t->probe=owner; t->kernel_stopped=0; }
}

static void buffer_mapping_turn_end(struct buffer_mapping *owner) {
    if (owner->turn.queue && md_resource_order_cancel(&owner->turn)==1)
        CHECK(!md_resource_order_complete(&owner->turn));
    md_memory_release(owner->memory); owner->memory=NULL;
}

static void buffer_mapping_release(struct buffer_mapping *owner) {
    buffer_mapping_turn_end(owner);
    CHECK(!md_buffer_release(&owner->scratch));
    md_buffers_release(owner->buffers);
    md_seccomp_policy_release(owner->policy);
    free(owner);
}

static void buffer_mapping_complete(void *context,struct md_task_helper_result result) {
    struct buffer_mapping *owner=context;
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    CHECK(result.descriptor<0 && !result.received);
    if (t && t->memory_op.buffer_mapping==owner) {
        long error=result.value;
        if (error>=0 && owner->policy) {
            uintptr_t base=(uintptr_t)error;
            struct md_interception_buffer buffer={0};
            struct md_buffer_span span;
            error=md_buffers_take(owner->buffers,&buffer);
            if (!error) error=md_buffer_span(&buffer,&span);
            if (!error) error=md_seccomp_policy_view_write(owner->policy,owner->ancestor,
                span.data,span.size,base,owner->parent_view);
            if (!error) error=md_memory_snapshot_publish(t->memory,
                md_seccomp_policy_identity(owner->policy),owner->buffers,base);
            CHECK(!md_buffer_release(&buffer));
        } else if (error>=0 && owner->clients) {
            uintptr_t base=(uintptr_t)error;
            const struct md_private_memory_access *access=md_memory_private_access(t->memory);
            error=md_svc_registry_client_publish(md_memory_svc(t->memory),t->pid,
                access ? access->memory : -1,owner->client_bucket,base);
            if (!error) error=(long)base;
        } else if (error>=0 && owner->channels)
            error=md_memory_channels_publish(t->memory,owner->buffers,(uintptr_t)error);
        else if (error>=0) error=md_memory_readonly_mapped(t->memory,(uintptr_t)error,
                md_buffers_size(owner->buffers));
        t->memory_op.buffer_mapping=NULL; t->kernel_stopped=1;
        buffer_mapping_turn_end(owner);
        if (error>=0 && owner->channels) error=cooperative_bind(t);
        if (owner->policy) {
            SUPERVISOR_TRACE("PROBE filter-entry-mapped pid=%d result=%ld\n",t->pid,error);
            filter_entry_complete(t,&owner->continuation);
        } else {
            buffer_capture(t,&owner->continuation);
            buffer_reply(t,error);
        }
    }
    buffer_mapping_release(owner);
}

int buffer_mapping_start(struct thread *t,const struct user_pt_regs *regs,
        struct buffer_mapping *owner) {
    owner->caller=t->pid; owner->continuation=*regs;
    int error=md_buffers_take(md_memory_buffers(t->memory),&owner->scratch);
    if (!error) {
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug.options);
    }
    if (!error) {
        struct md_interception_buffers *buffers=owner->buffers;
        const struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,
            .mapping={.source=md_buffers_fd(buffers),.size=md_buffers_size(buffers),
                .protection=PROT_READ|((owner->channels || owner->clients) ? PROT_WRITE : 0),
                .flags=owner->clients ? MAP_PRIVATE : MAP_SHARED}};
        /* The existing private-table mapper needs no application FD slot.
         * Filter admission or channel expansion owns the address-space turn. */
        error=md_task_helper_begin(helpers,t->pid,t->tgid,regs,&abi,t->memory,&owner->scratch,
            &request,buffer_mapping_complete,owner);
    }
    if (error) {
        buffer_mapping_release(owner);
    } else {
        t->memory_op.buffer_mapping=owner; t->kernel_stopped=0;
        if (owner->policy) t->phase=FILTER_MAPPING;
    }
    return error;
}

static void buffer_mapping_begin(struct thread *t,const struct user_pt_regs *regs) {
    struct buffer_mapping *owner=calloc(1,sizeof(*owner));
    int error=owner ? 0 : -ENOMEM;
    if (!error) {
        owner->buffers=md_buffers_retain(md_memory_readonly_buffers(t->memory));
        error=buffer_mapping_start(t,regs,owner);
    }
    if (error) buffer_reply(t,error);
}

static void entry_mapping_begin(struct thread *t,const struct user_pt_regs *regs,int clients) {
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    long result=clients ? md_svc_registry_client_space(md_memory_svc(t->memory),t->pid,
        access ? access->memory : -1,(unsigned)regs->regs[1]) : cooperative_bind(t);
    if (result!=-EAGAIN) { buffer_reply(t,result); return; }
    struct buffer_mapping *owner=calloc(1,sizeof(*owner));
    int error=owner ? 0 : -ENOMEM;
    if (!error) {
        owner->channels=!clients; owner->clients=clients; owner->client_bucket=(unsigned)regs->regs[1];
        owner->memory=md_memory_fork(t->memory,1);
        error=owner->memory ? md_memory_order_begin(owner->memory,&owner->turn,NULL) : -ENOMEM;
        /* A busy mapping owner is not a failed registration. Retry through
         * the entry's existing bounded backoff, without parking a new helper. */
        if (!error) error=-EAGAIN;
        else if (error==1) error=md_resource_order_start(&owner->turn);
        if (!error) error=md_buffers_create(clients ? 1 : 16,
            clients ? sizeof(struct md_svc_client_block) : sizeof(struct md_entry_channel),0,0,&owner->buffers);
    }
    if (error) {
        if (owner) buffer_mapping_release(owner);
    } else error=buffer_mapping_start(t,regs,owner);
    if (error) buffer_reply(t,error);
}

void memory_buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_BUFFER_PROBE:
        if (t->entered || t->phase!=IDLE || !t->fs.buffer.pool || t->probe
                || t->capture || t->memory_op.mapping || control->args[0] || control->args[1]) break;
        /* EVENT_WAIT: the production helper's private table is fully reaped
         * before readiness; the launch deadline bounds missing completion. */
        probe_begin(t,r); return;
    case MD_BUFFER_MAPPED:
        if (t->entered || t->phase!=IDLE) break;
        result=md_memory_buffers_mapped(t->memory,control->args[0],control->args[1]);
        SUPERVISOR_TRACE("PROBE buffer-mapping pid=%d bytes=%llu result=%ld\n",t->pid,control->args[1],result);
        break;
    case MD_BUFFER_READONLY_MAPPED:
        if ((t->entered || t->phase!=IDLE) && !(t->seccomp.preflight && t->phase==DISPATCHING
                && t->seccomp.install && md_resource_order_state(&t->seccomp.install->memory)==MD_RESOURCE_ORDER_RUNNING)) break;
        result=md_memory_readonly_mapped(t->memory,control->args[0],control->args[1]);
        break;
    case MD_BUFFER_READONLY_INSTALL:
        if (control->args[0] || control->args[1] || t->memory_op.buffer_mapping || !t->seccomp.preflight
                || t->phase!=DISPATCHING || !t->seccomp.install
                || md_resource_order_state(&t->seccomp.install->memory)!=MD_RESOURCE_ORDER_RUNNING
                || !md_memory_readonly_buffers(t->memory) || md_memory_readonly_base(t->memory)) break;
        buffer_mapping_begin(t,r); return;
    case MD_BUFFER_CHANNEL_INSTALL:
        if (control->args[0] || control->args[1] || t->memory_op.buffer_mapping) break;
        entry_mapping_begin(t,r,0); return;
    case MD_BUFFER_CLIENT_INSTALL:
        if (control->args[0]>=MD_SVC_CLIENT_BUCKETS || control->args[1] || t->memory_op.buffer_mapping
                || !t->entered || t->phase!=IDLE || t->entry.blocked || !filter_entry_eligible(t)) break;
        entry_mapping_begin(t,r,1); return;
    case MD_BUFFER_MEMORY_MAP:
        if (control->args[0] || control->args[1]) break;
        mapping_begin(t); return;
    case MD_BUFFER_MEMORY_OPEN: {
        if (t->phase!=DISPATCHING || (control->args[1]&~(unsigned long)O_PATH)) break;
        struct thread *target=find_thread((pid_t)control->args[0]);
        if (!target || (!(control->args[1]&O_PATH) && !proc_allowed(t->pid,target->pid))) {
            result=-EACCES; break;
        }
        /* O_PATH admits only the dentry, but its retained pin can outlive a
         * later access-policy change. Keep exposure tracking for both kinds. */
        result=md_memory_exposure_begin(target->memory,&t->memory_op.exposure);
        break;
    }
    case MD_BUFFER_MEMORY_OPEN_RESULT:
        if (t->phase!=DISPATCHING || control->args[1]) break;
        SUPERVISOR_TRACE("PROBE memory-open-result pid=%d result=%ld tracked=%d\n",
            t->pid,(long)control->args[0],t->memory_op.exposure!=NULL);
        md_memory_exposure_end(&t->memory_op.exposure,(long)control->args[0]>=0);
        result=0; break;
    }
    buffer_reply(t,result); adapter_progress();
}

int svc_revoke_entry(struct thread *t) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int error=md_svc_registry_revoke(registry,t->pid);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error);
    return 1;
}

int svc_entry_observe(struct thread *t) {
    struct md_svc_registry *r=md_memory_svc(t->memory);
    if (!r) return 1;
    if (md_memory_exposed(t->memory)) return svc_revoke_entry(t);
    int error=0;
    if (t->debug.options || md_debugger_traced(debugger,t->pid))
        error=md_svc_registry_pause(r,t->pid,MD_SVC_PAUSE_DEBUGGER);
    else if (r->pause_reasons&MD_SVC_PAUSE_DEBUGGER) {
        /* One inferior can share its mm with untraced threads. Resume lazy
         * admission only after the last debugger owner has left that mm. */
        for (struct thread *p=threads;p;p=p->next)
            if (p->pid && p->memory==t->memory && (p->debug.options || md_debugger_traced(debugger,p->pid)))
                return 1;
        error=md_svc_registry_retire_pending(r,t->pid);
        if (!error) error=md_svc_registry_rearm(r,t->pid,MD_SVC_PAUSE_DEBUGGER);
        if (!error) filter_entry_rearm(t);
    } else if (r->revoke_pending) error=md_svc_registry_retire_pending(r,t->pid);
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    CHECK(!error);
    return 1;
}

void memory_access_capture(struct thread *t) {
    int error=md_memory_private_access_capture(t->memory,t->pid);
    SUPERVISOR_TRACE("PROBE memory-access pid=%d result=%d\n",t->pid,error);
}

static void mapping_delivery_release(struct mapping_owner *owner) {
    md_fd_origins_finish(&owner->delivery,-ECANCELED);
    if (owner->loan) {
        CHECK(owner->loan->number<0);
        md_fd_loan_end(owner->loan->loan);
        md_fd_loan_release(owner->loan->loan);
        md_fd_origins_unpin(owner->loan->loan_table);
        free(owner->loan); owner->loan=NULL;
    }
    if (owner->files.table && md_fd_order_cancel(&owner->files)==1)
        CHECK(!md_fd_order_complete(&owner->files));
}

static void mapping_guard_release(struct mapping_owner *owner) {
    if (owner!=mapping_execution) return;
    if (owner->borrowed) {
        CHECK(!md_task_quiesce_return(mapping_barrier,owner->direct.call.actor));
        owner->borrowed=0;
    }
    CHECK(!md_task_quiesce_release(mapping_barrier));
    mapping_execution=NULL;
}

static void mapping_complete(void *context, struct md_task_helper_result result) {
    long value=result.value;
    CHECK(result.descriptor==-1);
    struct mapping_owner *owner=context;
    int guarded=owner==mapping_execution;
    mapping_guard_release(owner);
    mapping_delivery_release(owner);
    if (owner->file) {
        md_memory_mapping_finish(owner->memory,&owner->change,value);
        if (value>=0) CHECK(!md_shm_unmapped(owner->space,(uintptr_t)value,
            (owner->request.size+md_page_size-1)&~(md_page_size-1),owner->group,owner->process));
    } else if (value>=0) {
        unsigned long args[4]={(unsigned long)value};
        CHECK(!md_shm_call(NULL,owner->space,&owner->pending,NULL,owner->group,owner->process,MD_SHM_ATTACHED,args,NULL));
    } else CHECK(!md_shm_abort(&owner->pending));
    CHECK(!md_shm_space_release(owner->space));
    md_pid_identity_release(owner->process);
    if (owner->request.source>=0) close(owner->request.source);
    if (owner->order.queue && md_resource_order_cancel(&owner->order)==1)
        CHECK(!md_resource_order_complete(&owner->order));
    md_memory_release(owner->memory);
    struct thread *t=owner->caller ? find_thread(owner->caller) : NULL;
    if (t && t->memory_op.mapping==owner) {
        t->memory_op.mapping=NULL;
        if (owner->admitted) {
            t->kernel_stopped=1;
            buffer_capture(t,&owner->continuation);
        }
        buffer_reply(t,value);
    }
    free(owner);
    if (guarded) task_action_unpark();
}

static void mapping_retired(void *context,long error) {
    struct mapping_owner *owner=context;
    CHECK(!error || error==-ECANCELED);
    mapping_complete(owner,(struct md_task_helper_result){
        .value=error ? error : owner->direct.result,.descriptor=-1});
}

static void mapping_retire(struct mapping_owner *owner,struct thread *t) {
    if (owner->borrowed) {
        CHECK(!md_task_quiesce_return(mapping_barrier,owner->direct.call.actor));
        owner->borrowed=0;
    }
    owner->retiring=1;
    owner->loan->retired=mapping_retired; owner->loan->retired_context=owner;
    loan_retire(owner->loan,t,&owner->files,NULL); owner->loan=NULL;
}

static void mapping_discard(struct mapping_owner *owner) {
    if (owner->retiring) return;
    if (owner->direct.stage==MD_TASK_MAPPING_MAP)
        md_memory_mapping_abandon(owner->memory,&owner->change);
    if (owner->loan && owner->loan->number>=0) {
        owner->direct.result=-ECANCELED;
        mapping_retire(owner,NULL); return;
    }
    long value=owner->direct.stage==MD_TASK_MAPPING_DONE ? owner->direct.result : -ECANCELED;
    mapping_complete(owner,(struct md_task_helper_result){.value=value,.descriptor=-1});
}

static int mapping_helper_start(struct thread *t) {
    struct mapping_owner *owner=t->memory_op.mapping;
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,.mapping=owner->request};
    int error=md_task_helper_begin(helpers,t->pid,t->tgid,&owner->continuation,&abi,
        owner->memory,&t->fs.buffer,&request,mapping_complete,owner);
    if (!error) {
        owner->started=1; t->kernel_stopped=0;
        close(owner->request.source); owner->request.source=-1;
    }
    return error;
}

int mapping_notification(struct thread *t,const struct seccomp_notif *q) {
    struct mapping_owner *owner=t->memory_op.mapping;
    if (!owner || !md_task_mapping_notification(&owner->direct,q)) return 0;
    CHECK(owner->loan && md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_RUNNING);
    struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
        .srcfd=owner->request.source,.newfd_flags=O_CLOEXEC};
    int number=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
    if (number<0) {
        int error=errno;
        if (error!=ENOENT) {
            struct seccomp_notif_resp reply={.id=q->id,.error=-error};
            CHECK(!ioctl(listener,SECCOMP_IOCTL_NOTIF_SEND,&reply) || errno==ENOENT);
        }
    } else {
        md_fd_origins_finish(&owner->delivery,number);
        owner->loan->number=owner->direct.descriptor=number;
    }
    return 1;
}

int mapping_event(pid_t pid,int status) {
    struct thread *t=find_thread(pid);
    struct mapping_owner *owner=t ? t->memory_op.mapping : NULL;
    if (!owner || !owner->direct.call.stage || owner->direct.stage==MD_TASK_MAPPING_DONE) return 0;
    unsigned event=(unsigned)status>>16;
    if (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC) return 0;
    if (task_call_signal(t,&owner->direct.call,status)) return 1;
    int owned_interrupt=md_task_call_expects_interrupt(&owner->direct.call);
    if (event!=PTRACE_EVENT_SECCOMP && !(event==PTRACE_EVENT_STOP && owned_interrupt)
            && (event || WSTOPSIG(status)!=(SIGTRAP|0x80))) return 0;
    if (statistics) ++statistics->stops;
    int result=md_task_mapping_event(&owner->direct,status);
    if (result<0) { CHECK(result==-ESRCH); t->resume_lost=1; return 1; }
    if (result) {
        t->kernel_stopped=1;
        if (owner->direct.result==-EMFILE && owner->direct.descriptor<0) {
            /* Mapping needs no application FD. Retain the private-table path
             * when the optional loan cannot fit; its native errors still apply. */
            mapping_guard_release(owner);
            mapping_delivery_release(owner);
            owner->direct=(struct md_task_mapping){0};
            int error=mapping_helper_start(t);
            if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
            task_action_unpark();
        } else if (owner->loan->number>=0) {
            mapping_retire(owner,t);
        } else mapping_retired(owner,0);
    } else t->kernel_stopped=0;
    adapter_progress();
    return 1;
}

static int mapping_direct_start(struct thread *t) {
    struct mapping_owner *owner=t->memory_op.mapping;
    if (!owner->files.table) {
        int error=md_fd_order_begin(t->resources.files,&owner->files,fd_wakeup);
        if (error<0) return error;
    }
    if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_QUEUED) {
        retry_wait(t,RETRY_MAPPING); return 0;
    }
    if (md_fd_order_state(&owner->files)==MD_RESOURCE_ORDER_READY)
        CHECK(!md_fd_order_start(&owner->files));
    /* The running table turn excludes clone/dup publication. A sole stopped
     * owner cannot expose the loan to a peer, even for mutable backing. */
    if (owner->guarded && md_fd_origins_exclusive(&owner->files)) owner->guarded=0;
    if (owner->guarded) {
        if (!mapping_execution) {
            if (quiescence_busy(NULL)) {
                retry_wait(t,RETRY_MAPPING); return 0;
            }
            if (!mapping_barrier) mapping_barrier=md_task_quiesce_new(quiesce_trace);
            if (!mapping_barrier) return -ENOMEM;
            mapping_execution=owner;
            /* EVENT_WAIT: exact shared-table holders stop before a writable
             * backing loan is published. Expiry fails closed; ordinary native
             * and sealed mappings never enter this cold barrier. */
            owner->deadline=md_event_now()+5000000000LL;
            for (struct thread *p=threads;p;p=p->next) if (p->pid && p->resources.files==owner->files.table)
                CHECK(!quiesce_task_add(mapping_barrier,p,p==t));
            CHECK(!md_task_quiesce_stop(mapping_barrier));
        }
        if (mapping_execution!=owner) { retry_wait(t,RETRY_MAPPING); return 0; }
        if (!md_task_quiesce_ready(mapping_barrier)) return 0;
        struct md_task_quiesce_stop stop;
        CHECK(!md_task_quiesce_borrow(mapping_barrier,t->pid,&stop));
        CHECK(stop.kind==MD_TASK_QUIESCE_HELD);
        owner->borrowed=1;
    }
    struct task_action *loan=calloc(1,sizeof(*loan));
    if (!loan) return -ENOMEM;
    owner->loan=loan; loan->number=-1;
    loan->loan_table=md_fd_origins_pin(t->resources.files);
    loan->loan=md_fd_loan_new(t->resources.files);
    int error=loan->loan ? md_fd_origins_prepare(t->resources.files,NULL,FD_CLOEXEC,&owner->delivery) : -ENOMEM;
    if (!error) error=md_fd_origins_prepare_loan(&owner->delivery,loan->loan);
    if (!error) error=md_task_mapping_begin(&owner->direct,native_trace,t->pid,
        &owner->continuation,&abi,&owner->request);
    if (error && owner->direct.call.stage) { CHECK(error==-ESRCH); t->resume_lost=1; return 0; }
    if (!error) t->kernel_stopped=0;
    return error;
}

static void mapping_admitted(struct thread *t) {
    struct mapping_owner *owner=t->memory_op.mapping;
    /* Admission, access checks and the memory turn need no registers. Only
     * the private-table mapper or guarded FD loan borrows a stopped task. */
    if (entry_require_stop(t,MD_CONTEXT_MEMORY,0,mapping_admitted)) return;
    int error=0;
    if (!owner->admitted) {
        owner->continuation=t->fs.registers;
        owner->admitted=1;
        t->terminal_observation=1; t->trace_options_dirty=1;
        error=debugger_options(t->pid,t->debug.options);
        if (!error && owner->file) owner->guarded=!md_task_mapping_safe(owner->request.source);
    }
    if (!error) error=owner->file ? mapping_direct_start(t) : mapping_helper_start(t);
    if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
}

void mapping_start(struct thread *t) {
    retry_clear(t,RETRY_MAPPING);
    struct mapping_owner *owner=t->memory_op.mapping;
    if (md_resource_order_state(&owner->order)==MD_RESOURCE_ORDER_RUNNING) {
        mapping_admitted(t); return;
    }
    if (md_resource_order_state(&owner->order)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_MAPPING); return; }
    CHECK(!md_resource_order_start(&owner->order));
    int error=0;
    if (owner->file) {
        error=md_memory_mapping_prepare(owner->memory,&owner->change,MD_MAPPING_MAP,
            owner->request.address,owner->request.size,0,owner->request.flags,
            (owner->noexec ? MD_MAPPING_NOEXEC : 0)|(owner->readonly ? MD_MAPPING_NOWRITE : 0));
        if (!error && !owner->noexec) (void)md_memory_mapping_backing(owner->memory,&owner->change,
            owner->request.source,owner->request.offset);
    }
    if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else mapping_admitted(t);
}

void mapping_progress(void) {
    struct mapping_owner *owner=mapping_execution;
    if (!owner) return;
    if (md_event_now()>=owner->deadline) {
        fprintf(stderr,"Shroot: mapping timeout caller=%d stage=%d retiring=%d actor=%d\n",
            owner->caller,owner->direct.stage,owner->retiring,
            task_action_execution && task_action_execution->actor ? task_action_execution->actor->pid : 0);
        md_task_quiesce_describe(mapping_barrier,STDERR_FILENO);
        md_broker_describe(broker,STDERR_FILENO);
        md_fs_buffer_describe(buffer_service,STDERR_FILENO);
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->resources.files==owner->files.table) {
            siginfo_t info={0};
            int observed=native_trace(PTRACE_GETSIGINFO,p->pid,NULL,&info)<0 ? -errno : info.si_code;
            fprintf(stderr,"Shroot: mapping peer pid=%d born=%d initial=%d stopped=%d phase=%d resume=%d terminal=%d nr=%lu stop=%d notification=%d readiness=%d\n",
                p->pid,p->born,p->initial_stop,p->kernel_stopped,p->phase,p->kernel_resume,p->terminal_stop,
                (unsigned long)p->execution.request.nr,observed,p->memory_op.notification!=NULL,p->readiness!=NULL);
            md_broker_describe_task(broker,p->pid,STDERR_FILENO);
            if (observed<0) {
                char path[80],state[256];
                snprintf(path,sizeof(path),"/proc/%d/syscall",p->pid);
                int fd=open(path,O_RDONLY|O_CLOEXEC);
                ssize_t size=fd<0 ? -1 : read(fd,state,sizeof(state)-1);
                if (fd>=0) close(fd);
                if (size>=0) state[size]=0;
                fprintf(stderr,"Shroot: admission peer=%d pending=%d yields=%u gate=%#lx syscall=%s\n",
                    p->pid,p->fs.admission_pending,p->fs.admission_yields,
                    (unsigned long)abi.buffer_admit,size>=0 ? state : "unavailable");
            }
        }
        errno=ETIMEDOUT; CHECK(0);
    }
    if (!owner->direct.stage && md_task_quiesce_ready(mapping_barrier)) {
        struct thread *t=find_thread(owner->caller);
        CHECK(t && t->memory_op.mapping==owner);
        int error=mapping_direct_start(t);
        if (error) mapping_complete(owner,(struct md_task_helper_result){.value=error,.descriptor=-1});
    }
}

int mapping_guard_event(pid_t pid,int status,const struct rusage *usage) {
    if (!mapping_execution || pid==mapping_execution->caller) return 0;
    if (!md_task_quiesce_contains(mapping_barrier,pid)) return 0;
    int error=quiesce_event(mapping_barrier,pid,status,usage);
    if (error) {
        fprintf(stderr,"Shroot: mapping barrier event pid=%d status=%x caller=%d retiring=%d actor=%d error=%d\n",
            pid,status,mapping_execution->caller,mapping_execution->retiring,
            task_action_execution && task_action_execution->actor ? task_action_execution->actor->pid : 0,error);
        errno=-error; CHECK(0);
    }
    return 1;
}

static void mapping_begin(struct thread *t) {
    struct md_shm *shm=NULL;
    int file=t->execution.request.nr==SYS_mmap;
    long error=file ? 0 : md_ipc_namespace_memory(t->resources.ipc,&shm);
    if (!error && (t->phase!=DISPATCHING || (!file && t->execution.request.nr!=SYS_shmat)
            || t->memory_op.mapping || !t->fs.buffer.pool || t->fs.call || t->io.transport.packet)) error=-EPERM;
    unsigned flags=t->execution.request.args[2];
    uintptr_t address=t->execution.request.args[1];
    if (!file) {
        if (flags&SHM_RND) address&=~(md_page_size-1);
        if (!error && ((flags&~(SHM_RDONLY|SHM_RND|SHM_EXEC)) || (address&(md_page_size-1)))) error=-EINVAL;
    } else if (!t->proc.file || t->control.mapping_fd<0) error=-EBADF;
    struct mapping_owner *owner=error ? NULL : calloc(1,sizeof(*owner));
    if (!error && !owner) error=-ENOMEM;
    int fd=-1;
    if (!error) {
        *owner=(struct mapping_owner){.caller=t->pid,.group=t->tgid,.process=md_pid_identity_retain(t->resources.pid_group),
            .request={.source=-1},.file=file};
        owner->space=md_shm_space_fork(t->shm_space,1);
        owner->memory=md_memory_fork(t->memory,1);
        if (!owner->space || !owner->memory) error=-ENOMEM;
    }
    if (!error && !file) {
        unsigned long args[4]={t->execution.request.args[0],flags};
        fd=(int)md_shm_call(shm,owner->space,&owner->pending,&t->identity,t->tgid,t->resources.pid_group,MD_SHM_OPEN,args,NULL);
        if (fd<0) error=fd;
    }
    if (!error) {
        if (file) {
            struct md_control_file control=md_proc_file_control(t->proc.file);
            struct md_fs_anchor *origin=control.ops->origin ? control.ops->origin(control.context) : NULL;
            owner->noexec=!!(md_fs_anchor_policy(origin)&MD_MOUNT_NOEXEC);
            owner->readonly=control.ops->mapping_readonly && (t->execution.request.args[3]&MAP_SHARED);
            owner->request=(struct md_task_mapping_request){.source=t->control.mapping_fd,
                .address=t->execution.request.args[0],.size=t->execution.request.args[1],.protection=t->execution.request.args[2],
                .flags=t->execution.request.args[3],.offset=t->execution.request.args[5]};
            t->control.mapping_fd=-1;
            if (owner->noexec && (owner->request.protection&PROT_EXEC)) error=-EPERM;
        } else owner->request=(struct md_task_mapping_request){.source=fd,.address=address,
                .size=md_shm_pending_size(owner->pending),.flags=MAP_SHARED|(address ? MAP_FIXED_NOREPLACE : 0),
                .protection=PROT_READ|((flags&SHM_RDONLY) ? 0 : PROT_WRITE)|((flags&SHM_EXEC) ? PROT_EXEC : 0)};
        fd=-1;
        if (!error) error=md_memory_order_begin(owner->memory,&owner->order,fd_wakeup);
        if (error>=0) { t->memory_op.mapping=owner; mapping_start(t); return; }
    }
    if (fd>=0) close(fd);
    if (!error) return;
    t->memory_op.mapping=NULL;
    if (owner) {
        CHECK(!md_shm_abort(&owner->pending));
        CHECK(!md_shm_space_release(owner->space));
        md_pid_identity_release(owner->process);
        if (owner->request.source>=0) close(owner->request.source);
        md_memory_release(owner->memory);
        free(owner);
    }
    buffer_reply(t,error);
}

/* A native observation needs operands, not a restorable GPR image. Capture
 * the latter only before an error rewrite or task-affine adapter takes over. */
static int memory_context(struct thread *t,struct user_pt_regs *regs) {
    if (!t->memory_op.arguments_only) return 1;
    if (!registers(t->pid,&t->execution.registers)) return 0;
    t->memory_op.arguments_only=0; *regs=t->execution.registers;
    return 1;
}

static void memory_kernel_release(struct thread *t,long value) {
    md_memory_mapping_finish(t->memory,&t->memory_op.change,value);
    if (t->memory_op.files.table) CHECK(!md_fd_order_complete(&t->memory_op.files));
    CHECK(!md_resource_order_complete(&t->memory_op.order));
    t->memory_op.action=0; t->memory_op.arguments_only=0;
}

static size_t mapping_length(size_t length) {
    return length<=SIZE_MAX-(md_page_size-1) ? (length+md_page_size-1)&~(md_page_size-1) : 0;
}

void memory_kernel_complete(struct thread *t,long value) {
    const struct seccomp_data *call=&t->execution.request;
    if (value>=0) {
        struct md_svc_registry *machine=md_memory_svc(t->memory);
        if (call->nr!=SYS_mmap)
            md_svc_registry_mapping_changed(machine,call->args[0],mapping_length(call->args[1]));
        if (call->nr==SYS_mmap || call->nr==SYS_mremap)
            md_svc_registry_mapping_changed(machine,(uintptr_t)value,mapping_length(call->args[call->nr==SYS_mmap ? 1 : 2]));
    }
    if (value>=0 && (call->nr==SYS_munmap || call->nr==SYS_mmap)) {
        size_t length=call->args[1];
        CHECK(length<=SIZE_MAX-(md_page_size-1));
        uintptr_t address=call->nr==SYS_mmap ? (uintptr_t)value : call->args[0];
        CHECK(!md_shm_unmapped(t->shm_space,address,(length+md_page_size-1)&~(md_page_size-1),
            t->tgid,t->resources.pid_group));
    }
    memory_kernel_release(t,value);
}

static void memory_entry_complete(struct thread *t,long value) {
    memory_kernel_complete(t,value);
    t->phase=IDLE;
    cooperative_memory_publish(t,machine_discovery(t));
    entry_complete(t,value);
}

static void memory_entry_context(struct thread *t) {
    /* No native effect has run. Release preparation before replaying at the
     * original kernel boundary; do not abandon it as an unknown mapping. */
    memory_kernel_release(t,-ECANCELED);
    t->phase=IDLE;
    CHECK(entry_context(t,MD_CONTEXT_MEMORY));
}

static int memory_entry_mutation(const struct thread *t,const struct seccomp_data *call) {
    const struct md_svc_registry *machine=md_memory_svc(t->memory);
    return entry_snapshot_change(t,call->nr,call->args)
        || ((call->nr!=SYS_mmap || (call->args[3]&MAP_FIXED))
            && md_svc_registry_intersects(machine,call->args[0],call->args[1]))
        || (call->nr==SYS_mremap && (call->args[3]&MREMAP_FIXED)
            && md_svc_registry_intersects(machine,call->args[4],call->args[2]));
}

void memory_start(struct thread *t) {
    retry_clear(t,RETRY_MEMORY);
    enum md_resource_order_state state=md_resource_order_state(&t->memory_op.order);
    if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&t->memory_op.order));
    else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_MEMORY); return; }
    const struct seccomp_data *call=&t->execution.request;
    int shared=entry_pending(t);
    if (shared && memory_entry_mutation(t,call)) { memory_entry_context(t); return; }
    struct user_pt_regs regs;
    if (!t->memory_op.arguments_only) regs=t->execution.registers;
    if (t->memory_op.action==MEMORY_DETACH) {
        if (shared) { t->phase=IDLE; entry_execution_begin(t); return; }
        ++calls; ++t->calls; t->debug.step_call=md_debugger_step(debugger,t->pid);
        if (!save_signal_mask(t,&t->execution.mask,"memory-mask")
                || !shield(t) || !skip(t->pid)) return;
        dispatch_with_stack(t);
    } else if (t->memory_op.action==MEMORY_FORK) {
        t->phase=IDLE;
        fd_begin(t,call,&regs,MD_INTERCEPT_FD);
    } else {
        long nr=call->nr;
        int error=0, noexec=0;
        struct md_svc_registry *machine=md_memory_svc(t->memory);
        if (machine && !shared) {
            if (nr!=SYS_mmap || (call->args[3]&MAP_FIXED))
                error=md_svc_registry_unpatch(machine,t->pid,call->args[0],call->args[1]);
            if (!error && nr==SYS_mremap && (call->args[3]&MREMAP_FIXED))
                error=md_svc_registry_unpatch(machine,t->pid,call->args[4],call->args[2]);
        }
        int file=nr==SYS_mmap && !(call->args[3]&MAP_ANONYMOUS);
        struct md_fd_native native={0};
        if (file && !error) {
            if (!t->memory_op.files.table) {
                error=md_fd_order_begin(t->resources.files,&t->memory_op.files,fd_wakeup);
                if (error>=0) error=0;
            }
            if (!error && md_fd_order_state(&t->memory_op.files)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_MEMORY); return; }
            if (!error) {
                CHECK(!md_fd_order_start(&t->memory_op.files));
                int ordinary=md_fd_origins_native(&t->memory_op.files,(int)call->args[4],&native);
                struct md_proc_file *control=ordinary ? NULL : proc_file_capture(t,(int)call->args[4]);
                if (control) {
                    error=md_proc_file_mapping(control,(unsigned)call->args[2],(unsigned)call->args[3]);
                    struct md_control_file file=md_proc_file_control(control);
                    if (!error && file.ops->mapping) {
                        if (!call->args[1] || call->args[1]>SIZE_MAX-(md_page_size-1)
                                || (call->args[5]&(md_page_size-1))) error=-EINVAL;
                        else {
                            /* Capture the exact OFD, then release both turns
                             * before asking its userspace provider for data. */
                            if (!shared && !memory_context(t,&regs)) { md_proc_file_release(control); return; }
                            CHECK(!md_fd_order_complete(&t->memory_op.files));
                            CHECK(!md_resource_order_complete(&t->memory_op.order)); t->memory_op.action=0;
                            t->proc.file=control;
                            if (shared) {
                                t->memory_op.arguments_only=0;
                                t->phase=IDLE; entry_execution_begin(t); return;
                            }
                            if (!save_signal_mask(t,&t->execution.mask,"mapping-mask")
                                    || !shield(t) || !skip(t->pid)) return;
                            dispatch_with_stack(t); return;
                        }
                    }
                    md_proc_file_release(control);
                }
                struct md_fs_anchor *origin=ordinary ? native.origin
                    : md_fd_origins_get(t->resources.files,(int)call->args[4],NULL);
                noexec=!!(md_fs_anchor_policy(origin)&MD_MOUNT_NOEXEC);
                if (!ordinary) md_fs_anchor_release(origin);
                if (!error && noexec && (call->args[2]&PROT_EXEC)) error=-EPERM;
            }
        }
        if (!error && nr==SYS_mprotect) error=md_memory_protect_check(t->memory,call->args[0],call->args[1],call->args[2]);
        int overlap=md_shm_intersects(t->shm_space,call->args[0],call->args[1]);
        if (nr==SYS_mremap && (call->args[3]&MREMAP_FIXED))
            overlap|=md_shm_intersects(t->shm_space,call->args[4],call->args[2]);
        if (!error && overlap && nr==SYS_mremap) error=-ENOTSUP;
        int source=-1; uint64_t offset=0;
        int repair=nr==SYS_mprotect && md_memory_private_access(t->memory)
            && md_memory_file_view(t->memory,call->args[0],call->args[1],&source,&offset);
        if (!error && shared && repair) { memory_entry_context(t); return; }
        int tracked=nr!=SYS_mprotect && md_memory_mapping_observe(t->memory,call->args[0],
            nr==SYS_mremap && !call->args[1] ? 1 : call->args[1]);
        if (nr==SYS_mremap && (call->args[3]&MREMAP_FIXED))
            tracked|=md_memory_mapping_observe(t->memory,call->args[4],call->args[2]);
        int deferred=md_svc_registry_deferred(machine,call->args[0],mapping_length(call->args[1]));
        if (nr==SYS_mremap && (call->args[3]&MREMAP_FIXED))
            deferred|=md_svc_registry_deferred(machine,call->args[4],mapping_length(call->args[2]));
        int observe=repair || deferred || (nr!=SYS_mprotect && (file || overlap || tracked));
        SUPERVISOR_TRACE("PROBE memory-plan pid=%d nr=%ld address=%#llx size=%llu protection=%#llx flags=%#llx file=%d tracked=%d repair=%d sole=%d\n",
            t->pid,nr,call->args[0],call->args[1],call->args[2],call->args[3],file,tracked,repair,
            md_memory_sole_owner(t->memory));
        if (!error && observe && nr!=SYS_mprotect) error=md_memory_mapping_prepare(t->memory,&t->memory_op.change,
            nr==SYS_mmap ? MD_MAPPING_MAP : nr==SYS_munmap ? MD_MAPPING_UNMAP : MD_MAPPING_REMAP,
            call->args[0],call->args[1],nr==SYS_mremap ? call->args[2] : 0,(unsigned)call->args[3],noexec);
        if (!error && file && !noexec && (call->args[3]&MAP_TYPE)==MAP_PRIVATE
                && md_memory_private_access(t->memory)) {
            int number=(int)call->args[4],captured=0;
            /* The running file-table turn authenticates this immutable pin.
             * Typed providers, uncertain slots and denied pin access still
             * take the exact kernel-descriptor capture path below. */
            if (native.kind==S_IFREG) {
                captured=md_memory_mapping_pin(t->memory,&t->memory_op.change,
                    md_fs_anchor_fd(native.origin),call->args[5]);
            }
            if (captured<=0) {
                int fd=duplicate_fd(t->pid,number);
                t->memory_op.backing_pending=fd<0;
                captured=fd<0 ? fd : md_memory_mapping_backing(t->memory,&t->memory_op.change,fd,call->args[5]);
                if (fd>=0) close(fd);
            }
            /* This optional authority cannot fail native mmap or relax noexec.
             * Denied direct access uses task-affine capture after native mmap. */
            SUPERVISOR_TRACE("PROBE memory-backing pid=%d fd=%d retained=%d result=%d\n",
                t->pid,number,!!t->memory_op.change.backing,captured);
        }
        if (shared) {
            if (error) {
                memory_kernel_release(t,error); t->phase=IDLE; entry_complete(t,error);
            } else if (t->memory_op.backing_pending && !t->seccomp.application_filter) {
                memory_entry_context(t);
            } else {
                uint64_t args[6]; memcpy(args,call->args,sizeof(args));
                t->phase=IDLE;
                entry_effect(t,nr,args,memory_entry_complete);
            }
        } else if (error || !observe) {
            if (error && !memory_context(t,&regs)) return;
            memory_kernel_release(t,error ? error : -ECANCELED);
            t->phase=IDLE;
            if (error) return_value(t,&regs,error); else resume(t->pid,0);
        } else { t->phase=OBSERVING; resume(t->pid,0); }
    }
}

void memory_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *regs,enum memory_action action) {
    if (action!=MEMORY_KERNEL && action!=MEMORY_DETACH && entry_context(t,MD_CONTEXT_MEMORY)) return;
    CHECK(!t->memory_op.order.queue && !t->memory_op.action);
    t->memory_op.arguments_only=!regs;
    CHECK(regs || action==MEMORY_KERNEL || (action==MEMORY_DETACH && entry_pending(t)));
    execution_retain(t,call,regs);
    t->memory_op.action=action; t->phase=MEMORY_WAIT; t->memory_op.backing_pending=0;
    CHECK(md_memory_order_begin(t->memory,&t->memory_op.order,fd_wakeup)>=0);
    /* EVENT_WAIT: address-space publication precedes competing unmap/fork;
     * task exit cancels its queued turn. fd_ready drives progress, not polling. */
    memory_start(t);
}

static int entry_snapshot_change(const struct thread *t,long nr,const __u64 *args) {
    if (!md_memory_snapshots_valid(t->memory)) return 0;
    if (nr==SYS_shmat && (args[2]&SHM_REMAP))
        return md_memory_snapshot_intersects(t->memory,0,SIZE_MAX);
    if (nr!=SYS_mmap && nr!=SYS_munmap && nr!=SYS_mprotect && nr!=SYS_mremap) return 0;
    if ((nr!=SYS_mmap || (args[3]&MAP_FIXED))
            && md_memory_snapshot_intersects(t->memory,args[0],args[1])) return 1;
    return nr==SYS_mremap && (args[3]&MREMAP_FIXED)
        && md_memory_snapshot_intersects(t->memory,args[4],args[2]);
}

static int memory_observation(struct thread *t,const struct ptrace_syscall_info *info,
        struct seccomp_data *observed,int *native_entry) {
    long nr=(long)info->seccomp.nr;
    if (info->arch!=AUDIT_ARCH_AARCH64
            || (nr!=SYS_mmap && nr!=SYS_munmap && nr!=SYS_mprotect && nr!=SYS_mremap)
            || info->seccomp.ret_data!=MD_INTERCEPT_MEMORY || t->entry.fence_result
            || t->memory_op.order.queue || t->memory_op.action || t->debug.options
            || t->debug.call_active || t->debug.step_call || md_debugger_traced(debugger,t->pid)) return 0;
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    *observed=syscall_observation(info);
    *native_entry=0;
    if (entry_snapshot_change(t,nr,info->seccomp.args)) return 0;
    if (md_svc_registry_context(machine,info->instruction_pointer,info->stack_pointer)) {
        /* The exact kernel alias has already restored the caller's context.
         * A private stack or any other resident PC still needs full recovery. */
        if (info->stack_pointer>machine->frames && info->stack_pointer-machine->frames<=MD_SVC_POOL_SIZE) return 0;
        unsigned long cookie=info->seccomp.ret_data;
        *native_entry=machine_native_entry(t,observed,&cookie);
        if (!*native_entry) return 0;
    }
    return 1;
}

int memory_observed(struct thread *t,const struct ptrace_syscall_info *info) {
    struct seccomp_data observed;
    int native_entry;
    if (!memory_observation(t,info,&observed,&native_entry)) return 0;
    if (native_entry!=1) {
        uintptr_t discovery=native_entry ? md_svc_registry_call_origin(md_memory_svc(t->memory),observed.instruction_pointer)
            : observed.instruction_pointer;
        if (machine_observe(t,discovery,observed.nr)==-ENOSPC && !t->machine.pool_failed) return 0;
    }
    if (statistics) ++statistics->calls[observed.nr].trace;
    memory_begin(t,&observed,NULL,MEMORY_KERNEL);
    return 1;
}

static void memory_capture_complete(void *context,struct md_task_helper_result result) {
    struct memory_capture *capture=context;
    struct thread *t=capture->caller ? find_thread(capture->caller) : NULL;
    CHECK(result.descriptor==-1);
    if (t) {
        CHECK(t->memory_op.capture==capture);
        int error=(int)result.value;
        if (!error && result.received && result.received->count==1)
            error=md_memory_mapping_backing(t->memory,&t->memory_op.change,result.received->fds[0],t->execution.request.args[5]);
        SUPERVISOR_TRACE("PROBE memory-backing-export pid=%d retained=%d result=%d\n",t->pid,!!t->memory_op.change.backing,error);
        memory_kernel_complete(t,(long)capture->returned.regs[0]);
        t->memory_op.capture=NULL; t->kernel_stopped=1; t->phase=IDLE;
        if (set_registers(t->pid,&capture->returned)
                && signal_mask(t,capture->mask,"memory-capture-mask"))
            resume(t->pid,0);
    }
    md_received_fds_release(result.received);
    CHECK(!md_buffer_release(&capture->buffer));
    free(capture);
}

static void memory_captured(void *context) {
    struct memory_capture *capture=context;
    struct thread *t=capture->caller ? find_thread(capture->caller) : NULL;
    if (t && t->memory_op.files.table) CHECK(!md_fd_order_complete(&t->memory_op.files));
}

int memory_capture_begin(struct thread *t,const struct user_pt_regs *returned) {
    /* Optional backing capture must not add clone/sendmsg requirements to an
     * already successful mmap. A filter can kill, not merely reject, the
     * helper's creation. Retain the ordinary memory path in that domain. */
    if (t->seccomp.application_filter || !t->memory_op.backing_pending || (long)returned->regs[0]<0
            || t->memory_op.change.backing || t->memory_op.change.restrictions
            || t->execution.request.nr!=SYS_mmap || (t->execution.request.args[3]&MAP_ANONYMOUS)
            || (t->execution.request.args[3]&MAP_TYPE)!=MAP_PRIVATE || !md_memory_private_access(t->memory)) return 0;
    struct memory_capture *capture=calloc(1,sizeof(*capture));
    if (!capture) return 0;
    capture->caller=t->pid; capture->returned=*returned;
    int error=md_buffers_take(md_memory_buffers(t->memory),&capture->buffer);
    if (error) { free(capture); return 0; }
    if (!save_signal_mask(t,&capture->mask,"memory-capture-mask")
            || !shield(t)) { md_buffer_release(&capture->buffer); free(capture); return 0; }
    int fd=(int)t->execution.request.args[4];
    struct md_task_helper_request request={.kind=MD_TASK_HELPER_EXPORT,.descriptors=&fd,.descriptor_count=1,
        .captured=memory_captured};
    t->terminal_observation=1; t->trace_options_dirty=1;
    error=debugger_options(t->pid,t->debug.options);
    t->memory_op.capture=capture;
    if (!error) error=md_task_helper_begin(helpers,t->pid,t->tgid,returned,&abi,t->memory,&capture->buffer,
        &request,memory_capture_complete,capture);
    if (error) memory_capture_complete(capture,(struct md_task_helper_result){.value=error,.descriptor=-1});
    else t->kernel_stopped=0;
    return 1;
}

static int entry_fence_adapter_drained(const struct thread *t) {
    return !t->metadata_restart && !t->descriptors.running && !t->descriptors.order.table && !t->descriptors.fs_order.fs
        && !t->descriptors.policy_order.queue
        && !t->memory_op.order.queue && !t->memory_op.files.table
        && !t->signals.delivery.active && !t->machine.draining && !t->machine.syscall_exit
        && !t->machine.signal_alias && !t->fs.direct && !t->fs.call && !t->fs.waiting
        && !t->broker_completion;
}

static int entry_fence_boundary(const struct thread *t) {
    return t->phase==IDLE && entry_fence_adapter_drained(t);
}

int entry_fence_drained(const struct thread *t) {
    if (entry_fence_boundary(t)) return 1;
    /* An admitted native readiness wait can depend on the filter installer.
     * Its syscall has passed kernel admission; retain its owned completion
     * across TSYNC instead of waiting for the application event. */
    if (!entry_fence_adapter_drained(t)) return 0;
    if (t->entry.execution && t->entry.wait.complete) return 1;
    if (t->phase==SOCKET_WAITING && !t->readiness
            && ((t->io.outgoing && !t->io.outgoing->files.table && !t->io.outgoing->fs_order.fs)
                || (t->io.receive && !t->io.receive->files.table))) return 1;
    if (t->entry.execution && entry_native_journal(t)) {
        /* A resident native wait retains its completion, not a preparation
         * turn. Watch reads and readiness providers use the same journal;
         * neither may require the installing thread to supply its event. */
        const struct readiness_owner *o=t->readiness;
        if (!o || (!o->files.table && !o->publication_memory.queue
                && !o->evicting && !o->finishing)) return 1;
    }
    if (signal_control_parked(t)) return 1;
    if (t->phase==PROCESS_OBSERVING && (t->execution.request.nr==SYS_wait4 || t->execution.request.nr==SYS_waitid)) return 1;
    /* Native fallback and broker-owned reads have already admitted the original
     * call and released task-affine preparation. A blocking inotify read may
     * itself await the installer. MSG_PEEK is only a preparatory wait and must
     * still drain before its task-affine operation can cross the transition. */
    if ((t->phase==WATCH_READ && t->watch_wait!=1) || t->phase==ADAPTER_NATIVE || t->phase==NATIVE_WAIT) return 1;
    return t->phase==EPOLL_WAIT && t->readiness
        && t->readiness->native_caller && !t->readiness->evicting && !t->readiness->finishing;
}

int entry_fence_adapt(const struct thread *t) {
    return entry_fence_memory && entry_fence_stage!=3 && t
        && t->memory==entry_fence_memory && t!=entry_fence_owner && !t->entry.fence_waiting
        && entry_fence_boundary(t);
}

int entry_fence_admission(struct thread *t,const struct user_pt_regs *r,int status,const struct rusage *usage) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    struct seccomp_data call=syscall_description(r);
    int filter=md_seccomp_filter_call(call.nr,call.args[0],call.args[1]);
    if (!t->entered || t->phase!=IDLE || r->pc==RAW_GATE) return 0;
    int snapshot=entry_snapshot_change(t,call.nr,call.args);
    if (!filter && !snapshot) return 0;
    if (snapshot && (t==entry_fence_owner || !registry || registry->entry_fence_drained
            || !md_svc_registry_resident(registry))) {
        md_memory_snapshots_invalidate(t->memory);
        if (!svc_revoke_entry(t)) return 1;
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
            p->entry.blocked=1; md_broker_cooperative_revoke(broker,p->pid);
        }
        return 0;
    }
    if (t==entry_fence_owner) return 0;
    int unrestricted=0;
    if (filter) {
        int group=md_seccomp_filter_flags(call.nr,call.args[1])&SECCOMP_FILTER_FLAG_TSYNC;
        for (struct thread *p=threads;p;p=p->next) if (p->pid
                && (p==t || (group && p->tgid==t->tgid)) && !p->seccomp.application_filter) {
            unrestricted=1; break;
        }
    }
    if ((!registry || registry->entry_fence_drained) && !unrestricted) return 0;
    /* Revoked entries drain before the new filter commits. No thread-stop
     * barrier is needed for the logical dumpability policy. */
    if (snapshot) {
        if (!svc_revoke_entry(t)) return 1;
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==t->memory) {
            p->entry.blocked=1; md_broker_cooperative_revoke(broker,p->pid);
        }
    } else if (!filter_revoke_entry(t,&call)) return 1;
    if (!md_svc_registry_resident(registry) && !unrestricted) {
        if (registry) registry->entry_fence_drained=1;
        return 0;
    }
    CHECK(!t->entry.fence_waiting);
    ++entry_fence_waiters; t->entry.fence_waiting=1; t->entry.fence_status=status;
    if (usage) t->entry.fence_usage=*usage;
    return 1;
}

void entry_fence_progress(void) {
    if (!entry_fence_memory) {
        if (!entry_fence_waiters || quiescence_busy(NULL)) return;
        struct thread *caller=threads;
        while (caller && (!caller->pid || !caller->entry.fence_waiting)) caller=caller->next;
        if (!caller) return;
        entry_fence_memory=md_memory_fork(caller->memory,1); CHECK(entry_fence_memory);
        entry_fence_stage=2; entry_fence_slots=0; entry_fence_retry_at=0;
        entry_fence_error=0; entry_fence_deadline=md_event_now()+5000000000LL;
        if (statistics) ++statistics->entry_fence_transitions;
        return;
    }
    if (md_event_now()>=entry_fence_deadline) {
        /* Admission remains revoked; a filter cannot commit over an active entry. */
        if (diagnostics) for (struct thread *p=threads;p;p=p->next)
            if (p->pid && p->memory==entry_fence_memory) {
                struct user_pt_regs r={0};
                if (p->kernel_stopped) registers(p->pid,&r);
                SUPERVISOR_TRACE("PROBE entry-fence-timeout pid=%d phase=%d drained=%d waiting=%d held=%d direct=%d call=%d metadata=%d pc=%llx sp=%llx\n",
                    p->pid,p->phase,entry_fence_drained(p),p->entry.fence_waiting,p->entry.fence_held,
                    p->fs.direct,p->fs.call!=NULL,p->metadata_restart!=NULL,r.pc,r.sp);
            }
        entry_fence_error=-EBUSY; entry_fence_deadline=INT64_MAX;
    }
}

pid_t entry_fence_take(int *status,struct rusage *usage) {
    if (!entry_fence_memory) return 0;
    if (entry_fence_owner) return 0;
    if (entry_fence_stage!=3 && !entry_fence_error) {
        {
            int64_t now=md_event_now();
            if (entry_fence_retry_at>now) return 0;
            entry_fence_retry_at=0;
            struct thread *reader=threads;
            while (reader && (!reader->pid || reader->memory!=entry_fence_memory || !reader->entry.fence_waiting))
                reader=reader->next;
            uint64_t slots=0;
            int error=reader ? md_svc_registry_occupied(md_memory_svc(entry_fence_memory),reader->pid,&slots) : 0;
            if (error) entry_fence_error=error;
            else if (slots) {
                if (statistics) statistics->entry_fence_drains+=(unsigned)__builtin_popcountll(slots&~entry_fence_slots);
                entry_fence_slots|=slots;
                /* EVENT_WAIT: revoked frame owners release their slots; no
                 * interrupt of native waits and no periodic warm-path work. */
                entry_fence_retry_at=now+1000000;
                return 0;
            }
        }
        for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory
                && !p->entry.fence_waiting && !p->terminal_stop && !p->process_op.vfork_child
                && !entry_fence_drained(p)) return 0;
        struct md_svc_registry *registry=md_memory_svc(entry_fence_memory);
        if (registry) registry->entry_fence_drained=1;
    }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->memory==entry_fence_memory && p->entry.fence_waiting) {
        entry_fence_cancel(p);
        entry_fence_owner=p;
        p->entry.fence_result=entry_fence_error; *status=p->entry.fence_status;
        if (usage) *usage=p->entry.fence_usage;
        return p->pid;
    }
    if (entry_fence_stage!=3) {
        entry_fence_stage=3;
        task_action_unpark();
    }
    for (struct thread *p=threads;p;p=p->next) if (p->pid && p->entry.fence_held) {
        p->entry.fence_held=0; *status=p->entry.fence_status;
        if (usage) *usage=p->entry.fence_usage;
        return p->pid;
    }
    md_memory_release(entry_fence_memory); entry_fence_memory=NULL; entry_fence_stage=0; entry_fence_error=0;
    entry_fence_retry_at=0;
    return 0;
}

int64_t memory_deadline(void) {
    return memory_execution ? memory_execution->deadline : INT64_MAX;
}

void memory_discard(void) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return;
    /* No helper or replacement may still be executing: either teardown reaped
     * the tree, or an unmodified repair yielded before MEMORY_MAP. */
    md_buffer_release(&execution->buffer);
    md_private_memory_scan_end(execution->scan);
    if (execution->pause.owner) md_fs_worker_pause_end(&execution->pause);
    memory_execution=NULL;
    free(execution);
}

int memory_available(pid_t pid) {
    return (!(retained_creation || retained_retirement) || !md_task_quiesce_contains(retained_barrier,pid))
        && (!policy_installer || pid==policy_installer->pid || !md_task_quiesce_contains(policy_barrier,pid))
        && (!memory_execution || !md_task_quiesce_contains(memory_barrier,pid))
        && (!mapping_execution || !md_task_quiesce_contains(mapping_barrier,pid))
        && (!task_action_execution || !md_task_quiesce_contains(task_action_barrier,pid));
}

int memory_runnable(void) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return 0;
    switch (execution->stage) {
    case MEMORY_WRITERS: return md_task_quiesce_ready(memory_barrier);
    case MEMORY_WORKER: return !execution->pause.owner || md_fs_worker_pause_state(&execution->pause)!=0;
    case MEMORY_VALIDATE: case MEMORY_COPY: return 1;
    case MEMORY_ACTOR: return execution->executor && !execution->executor->creator;
    case MEMORY_MAP: return execution->complete;
    case MEMORY_ADVICE: case MEMORY_REPLACE: return !execution->calling;
    }
    return 0;
}

static int memory_participant(void *context,pid_t pid,int stopped,int request) {
    return md_task_quiesce_add(context,pid,stopped,request);
}

static void memory_complete(void *context,struct md_task_helper_result result) {
    struct memory_execution *execution=context;
    CHECK(result.descriptor==-1 && !result.received);
    execution->result=result; execution->complete=1;
}

static void memory_call(struct memory_execution *execution,const struct md_memory_replace_call *call) {
    pid_t actor=execution->executor->retained.actor;
    execution->call=*call; execution->calling=1;
    struct user_pt_regs regs=md_task_registers_entry(abi.task_call,0,execution->returned.pstate);
    regs.regs[8]=call->number; memcpy(regs.regs,call->args,sizeof(call->args));
    CHECK(!md_task_registers_access(native_trace,actor,&regs,1));
    CHECK(!native_trace(PTRACE_CONT,actor,NULL,NULL));
}

static void memory_finish(struct memory_execution *execution) {
    struct thread *t=execution->actor;
    int terminal=execution->terminal;
    if (!terminal) {
        struct user_pt_regs restore=execution->returned;
        restore.regs[0]=execution->error ? -EACCES : 0;
        CHECK(set_registers(t->pid,&restore));
    }
    CHECK(!md_buffer_release(&execution->buffer));
    md_private_memory_scan_end(execution->scan);
    if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
    CHECK(!md_task_quiesce_release(memory_barrier));
    execution->timing[6]=md_event_now();
    SUPERVISOR_TRACE("PROBE memory-repair pid=%d address=%#lx size=%zu pages=%zu result=%ld scope=%s otherEvents=%u\n",
        t->pid,(unsigned long)execution->mapping.address,execution->mapping.length,
        execution->mapping.copied_pages,execution->error,execution->global ? "launch" : "address-space",execution->other_events);
    if (diagnostics) {
        int64_t previous=execution->timing[0],cost[6];
        for (unsigned i=0;i<6;++i) {
            int64_t current=execution->timing[i+1] ? execution->timing[i+1] : previous;
            cost[i]=(current-previous)/1000; previous=current;
        }
        SUPERVISOR_TRACE("PROBE memory-repair-cost pid=%d writersUs=%lld workerUs=%lld validateUs=%lld mapUs=%lld copyUs=%lld replaceUs=%lld totalUs=%lld\n",
            t->pid,(long long)cost[0],(long long)cost[1],(long long)cost[2],
            (long long)cost[3],(long long)cost[4],(long long)cost[5],
            (long long)(execution->timing[6]-execution->timing[0])/1000);
    }
    memory_execution=NULL;
    free(execution);
    task_action_unpark();
    if (t->memory_op.files.table) CHECK(!md_fd_order_complete(&t->memory_op.files));
    CHECK(!md_resource_order_complete(&t->memory_op.order)); t->memory_op.action=0;
    t->memory_op.arguments_only=0;
    t->phase=IDLE;
    if (!terminal) resume(t->pid,0);
}

static void memory_replay_unmodified(struct memory_execution *execution,int status,const struct rusage *usage) {
    CHECK(execution->stage<MEMORY_MAP);
    CHECK(!quiesce_event(memory_barrier,execution->actor->pid,status,usage));
    if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
    CHECK(!md_task_quiesce_release(memory_barrier));
    memory_discard();
    task_action_unpark();
}

static int memory_yield_retirement(struct memory_execution *execution) {
    if (execution->stage>MEMORY_WORKER) return 0;
    struct task_action *retirement=loan_retirements;
    while (retirement && (!retirement->retirement.complete
            || !md_task_quiesce_contains(memory_barrier,retirement->retirement.task)))
        retirement=retirement->next;
    if (!retirement) return 0;
    /* A losing native birth must close its provisional FD before the broker
     * releases the caller's stop. Do not wait for that close behind our own
     * barrier. The original mprotect exit is replayed, not the syscall; its
     * mapping is validated afresh after the existing task-action owner drains. */
    struct thread *actor=execution->actor;
    SUPERVISOR_TRACE("PROBE memory-repair-defer pid=%d retirement=%d\n",actor->pid,retirement->retirement.task);
    memory_replay_unmodified(execution,execution->status,&execution->usage);
    return 1;
}

void memory_progress(void) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return;
    if (cancelling) exit(result);
    if (memory_yield_retirement(execution)) return;
    int64_t now=md_event_now();
    if (now>=execution->deadline) {
        fprintf(stderr,"Shroot: memory repair expired pid=%d stage=%d elapsedUs=%lld writersUs=%lld workerAck=%d otherEvents=%u\n",
            execution->actor->pid,execution->stage,(long long)(now-execution->timing[0])/1000,
            execution->timing[1] ? (long long)(execution->timing[1]-execution->timing[0])/1000 : -1LL,
            execution->pause.owner ? md_fs_worker_pause_state(&execution->pause) : -1,execution->other_events);
        fprintf(stderr,"Shroot: memory progress calls=%u idleUs=%lld workerAckUs=%lld\n",
            execution->progress_calls,(long long)(now-execution->last_progress)/1000,
            execution->pause.acknowledged_at ? (long long)(execution->pause.acknowledged_at-execution->timing[0])/1000 : -1LL);
        fprintf(stderr,"Shroot: memory workerUs=%lld validatedUs=%lld\n",
            execution->timing[2] ? (long long)(execution->timing[2]-execution->timing[0])/1000 : -1LL,
            execution->timing[3] ? (long long)(execution->timing[3]-execution->timing[0])/1000 : -1LL);
        md_task_quiesce_describe(memory_barrier,STDERR_FILENO);
        md_task_helpers_describe(helpers,execution->actor->pid,STDERR_FILENO);
        md_broker_describe(broker,STDERR_FILENO);
        errno=ETIMEDOUT; CHECK(0);
    }
    ++execution->progress_calls; execution->last_progress=now;
    struct thread *t=execution->actor;
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    if (execution->stage==MEMORY_WRITERS) {
        if (!md_task_quiesce_ready(memory_barrier)) return;
        execution->timing[1]=md_event_now();
        execution->stage=MEMORY_WORKER;
        if (filesystem) CHECK(!md_fs_worker_pause_begin(filesystem,&execution->pause));
    }
    if (execution->stage==MEMORY_WORKER) {
        if (execution->pause.owner) {
            int ready=md_fs_worker_pause_state(&execution->pause);
            CHECK(ready>=0);
            if (!ready) return;
        }
        execution->timing[2]=md_event_now();
        /* All participant notifications have been cancelled by their observed
         * ptrace stops. The acknowledgement drains any copy that raced that
         * cancellation; subsequent worker work can serve other address spaces. */
        if (execution->pause.owner) CHECK(!md_fs_worker_pause_end(&execution->pause));
        execution->error=md_private_memory_scan_begin(&execution->mapping,access,&execution->scan);
        if (execution->error) { memory_finish(execution); return; }
        execution->stage=MEMORY_VALIDATE;
    }
    if (execution->stage==MEMORY_VALIDATE) {
        long ready=md_private_memory_scan_step(execution->scan,&execution->plan);
        if (!ready) return;
        execution->error=ready<0 ? ready : 0;
        md_private_memory_scan_end(execution->scan); execution->scan=NULL;
        execution->timing[3]=md_event_now();
        if (execution->error) { memory_finish(execution); return; }
        execution->executor=retained_memory_get(t);
        if (!execution->executor) { execution->error=-ENOMEM; memory_finish(execution); return; }
        execution->stage=MEMORY_ACTOR;
        if (!execution->executor->ready) {
            /* The caller already owns the mm turn and writer barrier. Reuse
             * that ownership for the same private executor as robust cleanup. */
            retained_creation_begin(execution->executor,t,&execution->returned,MD_TASK_CALL_EXIT);
            return;
        }
    }
    if (execution->stage==MEMORY_ACTOR) {
        if (execution->executor->creator) return;
        execution->error=execution->executor->error;
        if (execution->error) { memory_finish(execution); return; }
        CHECK(execution->executor->ready && !execution->executor->cleaning);
        pid_t actor=execution->executor->retained.actor;
        struct user_pt_regs saved;
        CHECK(!md_task_registers_access(native_trace,actor,&saved,0));
        struct md_task_helper_request request={.kind=MD_TASK_HELPER_MAP,
            .mapping={.source=execution->mapping.source,.size=execution->mapping.length,
                .offset=execution->mapping.offset,.protection=execution->mapping.protection,
                .flags=execution->plan.flags}};
        execution->stage=MEMORY_MAP;
        execution->error=md_task_helper_borrow(helpers,actor,actor,MD_TASK_CALL_USER,&saved,&abi,
            t->memory,&execution->buffer,&request,memory_complete,execution);
        if (execution->error) memory_finish(execution);
        return;
    }
    if (execution->stage==MEMORY_MAP) {
        if (!execution->complete) return;
        execution->timing[4]=md_event_now();
        if (execution->result.value<0) {
            execution->error=execution->result.value; memory_finish(execution); return;
        }
        execution->mapping.candidate=(uintptr_t)execution->result.value;
        execution->stage=MEMORY_COPY;
    }
    if (execution->stage==MEMORY_COPY) {
        long ready=execution->error ? execution->error
            : md_private_memory_copy_step(&execution->mapping,access,&execution->plan,
                &execution->copied,execution->scratch,MD_PRIVATE_MEMORY_COPY_BYTES);
        if (!ready) return;
        execution->error=ready<0 ? ready : 0;
        execution->timing[5]=md_event_now();
        CHECK(!md_memory_replace_begin(&execution->replacement,execution->mapping.address,
            execution->mapping.candidate,execution->mapping.length,md_page_size));
        execution->stage=MEMORY_ADVICE;
    }
    if (execution->calling) return;
    if (execution->stage==MEMORY_ADVICE) {
        int advice=execution->error ? -1 : md_private_memory_advice(&execution->plan,execution->advice++);
        if (advice>=0) {
            struct md_memory_replace_call call={SYS_madvise,
                {execution->mapping.candidate,execution->mapping.length,(unsigned long)advice}};
            memory_call(execution,&call); return;
        }
        if (execution->error) md_memory_replace_cancel(&execution->replacement);
        execution->stage=MEMORY_REPLACE;
    }
    if (execution->stage==MEMORY_REPLACE) {
        struct md_memory_replace_call call;
        int next=md_memory_replace_next(&execution->replacement,&call);
        CHECK(next>=0);
        if (next) { memory_call(execution,&call); return; }
        long replaced=md_memory_replace_result(&execution->replacement);
        if (!execution->error) execution->error=replaced;
        memory_finish(execution);
    }
}

int quiescence_repair_event(pid_t pid,int status,const struct rusage *usage) {
    struct md_task_quiesce **barrier=quiescence_barrier(NULL);
    if (!barrier || !WIFSTOPPED(status) || (unsigned)status>>16
            || WSTOPSIG(status)!=(SIGTRAP|0x80)) return 0;
    struct thread *t=find_thread(pid);
    if (!t || t->phase!=OBSERVING || t->memory_op.action!=MEMORY_KERNEL
            || t->execution.request.nr!=SYS_mprotect) return 0;
    if ((retained_creation && retained_creation->creator==t)
            || (retained_retirement && retained_retirement->actor==t)
            || policy_installer==t
            || (memory_execution && memory_execution->actor==t)
            || (task_action_execution && task_action_execution->actor==t)
            || (mapping_execution && mapping_execution->caller==pid)) return 0;
    if (!*barrier) { *barrier=md_task_quiesce_new(quiesce_trace); CHECK(*barrier); }
    /* EVENT_WAIT: replay this exact mprotect exit after the active transaction
     * releases its stops. Only competing repair is deferred, not the process's
     * whole address space. Never start a second owner behind another barrier. */
    CHECK(!quiesce_event(*barrier,pid,status,usage));
    return 1;
}

/* Repair consumes only its participants' events; helper and replacement
 * continuations return to the same outer event loop as ordinary guest work. */
int memory_event(pid_t pid,int status,const struct rusage *usage) {
    struct memory_execution *execution=memory_execution;
    if (!execution) return 0;
    unsigned event=(unsigned)status>>16;
    if (execution->stage==MEMORY_ACTOR && execution->executor && retained_creation==execution->executor
            && (pid==execution->actor->pid || retained_actor(pid)==execution->executor)) {
        int consumed=retained_event(pid,status,usage);
        if (execution->executor->retained.parent_terminal && !retained_creation) {
            memory_replay_unmodified(execution,status,usage);
            return 1;
        }
        return consumed;
    }
    if (pid==execution->actor->pid && execution->stage<MEMORY_MAP
            && (WIFEXITED(status) || WIFSIGNALED(status)
                || (WIFSTOPPED(status) && (event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)))) {
        /* Preparation has not changed mappings or registers. Replay the actual
         * lifecycle event, not the superseded mprotect exit, before releasing
         * its task and address-space owners in the ordinary dispatcher. */
        memory_replay_unmodified(execution,status,usage);
        return 1;
    }
    if (execution->stage==MEMORY_MAP && md_task_helper_owns(helpers,execution->executor->retained.actor,pid)) {
        CHECK(md_task_helper_event(helpers,pid,status)==1);
        return 1;
    }
    if (execution->stage>=MEMORY_MAP && pid==execution->actor->pid
            && (!WIFSTOPPED(status) || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)) {
        /* The private mm executor survives group death and de_thread. Retain
         * the exact lifecycle event until its candidate is committed or rolled
         * back; never restore a dead caller's registers or reused FD numbers. */
        execution->terminal=1; execution->error=-ECANCELED;
        md_memory_replace_cancel(&execution->replacement);
        CHECK(!md_task_quiesce_terminal(memory_barrier,pid,status,usage));
        return 1;
    }
    if (execution->calling && pid==execution->executor->retained.actor) {
        CHECK(WIFSTOPPED(status));
        unsigned event=(unsigned)status>>16;
        if (event==PTRACE_EVENT_STOP && WSTOPSIG(status)==SIGTRAP) {
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL)); return 1;
        }
        struct user_pt_regs regs;
        CHECK(!md_task_registers_access(native_trace,pid,&regs,0));
        if (event==PTRACE_EVENT_SECCOMP) {
            unsigned long cookie;
            CHECK(!native_trace(PTRACE_GETEVENTMSG,pid,NULL,&cookie));
            CHECK(cookie==MD_INTERCEPT_TASK && regs.pc==abi.task_return
                && regs.regs[8]==(unsigned long)execution->call.number);
            for (unsigned i=0;i<6;++i) CHECK(regs.regs[i]==execution->call.args[i]);
            CHECK(!native_trace(PTRACE_CONT,pid,NULL,NULL));
        } else {
            CHECK(event==MD_TASK_CHECKPOINT_EVENT && regs.pc==abi.task_completed+4);
            execution->calling=0;
            if (execution->stage==MEMORY_ADVICE) {
                if (!execution->error) execution->error=(long)regs.regs[0];
            }
            else CHECK(!md_memory_replace_observed(&execution->replacement,(long)regs.regs[0]));
        }
        return 1;
    }
    if (!md_task_quiesce_contains(memory_barrier,pid)) {
        struct md_interception_memory *helper=md_task_helper_memory(helpers,pid);
        if (helper && !execution->global && helper!=execution->actor->memory) { ++execution->other_events; return 0; }
        struct thread *t=find_thread(pid);
        int hold=execution->global || !t || !t->born || t->memory==execution->actor->memory
            || t->memory_op.remote==execution->actor->memory;
        if (!hold && WIFSTOPPED(status)) {
            unsigned event=(unsigned)status>>16;
            if (event==PTRACE_EVENT_SECCOMP || event==MD_TASK_CHECKPOINT_EVENT) {
                struct user_pt_regs regs;
                CHECK(registers(pid,&regs));
                pid_t target=0;
                if (event==PTRACE_EVENT_SECCOMP) {
                    if (regs.regs[8]==SYS_ptrace) target=native_pid(t,(pid_t)regs.regs[1]);
                    else if (regs.regs[8]==SYS_process_vm_readv || regs.regs[8]==SYS_process_vm_writev)
                        target=native_pid(t,(pid_t)regs.regs[0]);
                } else if (regs.pc==abi.buffer_control+4 && regs.regs[0]==MD_BUFFER_MEMORY_OPEN)
                    target=(pid_t)regs.regs[1];
                struct thread *remote=find_thread(target);
                hold=remote && remote->memory==execution->actor->memory;
            }
        }
        if (!hold) { ++execution->other_events; return 0; }
        CHECK(!md_task_quiesce_add(memory_barrier,pid,1,0));
    }
    if (execution->stage<MEMORY_MAP
            && !(execution->executor && execution->executor->creator)
            && (!WIFSTOPPED(status)
            || event==PTRACE_EVENT_EXIT || event==PTRACE_EVENT_EXEC)) {
        /* A writer's exec may need this terminal sibling before acknowledging
         * INTERRUPT. No mapping changed yet: replay the peer's lifecycle and
         * the original mprotect exit, not another execution of mprotect. */
        CHECK(!md_task_quiesce_terminal(memory_barrier,pid,status,usage));
        memory_replay_unmodified(execution,execution->status,&execution->usage);
        return 1;
    }
    CHECK(!quiesce_event(memory_barrier,pid,status,usage));
    return 1;
}

int memory_repair(struct thread *t,const struct user_pt_regs *returned,int status,const struct rusage *usage) {
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    if (!access) return 0;
    struct md_private_memory mapping={.address=t->execution.request.args[0],.length=t->execution.request.args[1],
        .page_size=md_page_size,.protection=(int)t->execution.request.args[2],.source=-1};
    if (mapping.length>SIZE_MAX-(md_page_size-1)) return 0;
    mapping.length=(mapping.length+md_page_size-1)&~(md_page_size-1);
    if (!md_memory_file_view(t->memory,mapping.address,mapping.length,&mapping.source,&mapping.offset)) return 0;
    if (md_private_memory_validate(&mapping)) return 0;
    CHECK(!quiescence_busy(NULL));
    struct memory_execution *execution=calloc(1,sizeof(*execution)+MD_PRIVATE_MEMORY_COPY_BYTES);
    if (!execution) return 0;
    long error=md_buffers_take(md_memory_buffers(t->memory),&execution->buffer);
    if (error) { free(execution); return 0; }
    if (!memory_barrier) { memory_barrier=md_task_quiesce_new(quiesce_trace); CHECK(memory_barrier); }
    if (statistics) ++statistics->memory_repairs;
    execution->actor=t; execution->returned=*returned; execution->mapping=mapping;
    execution->status=status; execution->usage=*usage;
    execution->global=md_memory_exposed(t->memory);
    execution->timing[0]=md_event_now();
    /* EVENT_WAIT: writer, worker and helper acknowledgements share one failure
     * deadline. Expiry terminates the tree, never resumes uncertain mappings. */
    execution->deadline=execution->timing[0]+5000000000LL;
    memory_execution=execution;
    for (struct thread *peer=threads;peer;peer=peer->next) if (peer->pid
            && (execution->global || peer->memory==t->memory || peer->memory_op.remote==t->memory))
        CHECK(!quiesce_task_add(memory_barrier,peer,peer==t));
    CHECK(!md_task_helpers_visit(helpers,execution->global ? NULL : t->memory,memory_participant,memory_barrier));
    CHECK(!md_task_quiesce_stop(memory_barrier));
    return 1;
}
