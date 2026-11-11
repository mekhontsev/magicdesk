#define _GNU_SOURCE
#include "host_policy.h"
#include <linux/netlink.h>
#include "adapter_buffer.h"
#include "cgroup.h"
#include "clone_call.h"
#include "cooperative_channel.h"
#include "cooperative_entry.h"
#include "cooperative_native.h"
#include "device_events.h"
#include "elf_admission.h"
#include "entry_route.h"
#include "event_wait.h"
#include "fd_client.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "fs_worker.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "interception_route.h"
#include "linux_abi.h"
#include "mount_namespace.h"
#include "namespace_broker.h"
#include "native_identity.h"
#include "network_endpoint.h"
#include "network_namespace.h"
#include "pid_calls.h"
#include "poll_calls.h"
#include "private_memory.h"
#include "proc_file.h"
#include "process_limits.h"
#include "resource_order.h"
#include "seccomp_install.h"
#include "seccomp_policy.h"
#include "signal_delivery.h"
#include "signal_fd.h"
#include "signal_pending.h"
#include "signal_protocol.h"
#include "signal_view.h"
#include "socket_calls.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_domain.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_identity.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_proc.h"
#include "supervisor_readiness.h"
#include "supervisor_robust.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "svc_machine.h"
#include "svc_registry.h"
#include "svc_runtime.h"
#include "sysv_ipc.h"
#include "sysv_shm.h"
#include "task_call.h"
#include "task_checkpoint.h"
#include "task_quiesce.h"
#include "task_receive.h"
#include "task_registers.h"
#include "task_registry.h"
#include "task_resources.h"
#include "task_stop.h"
#include "uts_namespace.h"
#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif

enum execution_continuation { EXECUTION_RETURN, EXECUTION_CLONE, EXECUTION_PREFLIGHT_RETURN,
    EXECUTION_PREFLIGHT_REPLAY, EXECUTION_RESTART, EXECUTION_IPC_WAIT, EXECUTION_WATCH_WAIT,
    EXECUTION_NATIVE };
const unsigned long trace_options = PTRACE_O_TRACESECCOMP | PTRACE_O_TRACEEXEC
    | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACEVFORKDONE | PTRACE_O_TRACECLONE
    | PTRACE_O_EXITKILL | PTRACE_O_TRACESYSGOOD;

static void describe_process(pid_t pid, uint64_t pc);
static long checkpoint_native_trace(int request, pid_t pid, void *address, void *data);
static int checkpoint_marker(uintptr_t pc);
static int checkpoint_entry_marker(uintptr_t pc,unsigned long command);
struct md_task_checkpoints checkpoints={.trace=checkpoint_native_trace,
    .restricted=checkpoint_restricted,.marker=checkpoint_marker,.entry_marker=checkpoint_entry_marker};
static int machine_discovery(struct thread *t);
static int machine_context(struct thread *t,const struct ptrace_syscall_info *info);
static enum execution_continuation execution_complete(struct thread *t,long value);
static void entry_begin(struct thread *t,struct md_entry_channel *channel);
static void entry_execution_retire(struct thread *t,long result);
static void entry_execution_native_done(struct thread *t,long result);
static void entry_execution_finish(struct thread *t,long result);
static void buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r);
static int resource_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r,unsigned long *cookie);
static int svc_client_alive(pid_t pid,void *memory);
static int svc_clients_prepare(struct thread *t);
static void publish_execution(struct thread *t,int resuming);
static struct md_guest_execution execution_prepare(struct thread *t);
static struct user_pt_regs dispatch_registers(const struct thread *t,const struct md_guest_execution *call);

static void describe_process(pid_t pid, uint64_t pc) {
    if (!diagnostics) return;
    const char *files[] = {"maps", "status"};
    for (unsigned i = 0; i < sizeof(files) / sizeof(*files); i++) {
        char path[64], line[1024];
        snprintf(path, sizeof(path), "/proc/%d/%s", pid, files[i]);
        FILE *file = fopen(path, "re");
        if (!file) { SUPERVISOR_TRACE("PROBE %s error=%d\n", path, errno); continue; }
        unsigned count = 0;
        while (fgets(line, sizeof(line), file) && count++ < 4096) {
            unsigned long long begin = 0, end = 0;
            if (!i) sscanf(line, "%llx-%llx", &begin, &end);
            if (!i ? !pc || (pc >= begin && pc < end) :
                    !strncmp(line, "Seccomp", 7) || !strncmp(line, "NoNewPrivs", 10)
                    || !strncmp(line, "Uid:", 4) || !strncmp(line, "TracerPid:", 10))
                SUPERVISOR_TRACE("PROBE %s %s", path, line);
        }
        fclose(file);
    }
}

static long checkpoint_native_trace(int request, pid_t pid, void *address, void *data) {
    if (statistics) {
        statistics->ptrace_requests++;
        if ((unsigned)request<64) ++statistics->ptrace_operations[0][request];
        else if ((unsigned)request-0x4200<64) ++statistics->ptrace_operations[1][request-0x4200];
    }
    return ptrace(request,pid,address,data);
}

int checkpoint_restricted(pid_t pid) {
    struct thread *t=find_thread(pid);
    if (!t) return 1;
    if (!t->seccomp.application_filter) return 0;
    const struct md_svc_registry *registry=md_memory_svc(t->memory);
    /* Do not emulate checkpoints already admitted by every kernel filter.
     * Portable entry after exec alone does not establish helper admission. */
    return !registry || !md_seccomp_policy_internal_gates(t->seccomp.policy,registry->image);
}

static int checkpoint_marker(uintptr_t pc) {
#define MATCH_MARKER(field,symbol) if (abi.field && pc==abi.field+4) return 1;
    MD_CHECKPOINT_SITES(MATCH_MARKER)
#undef MATCH_MARKER
    return 0;
}

static int checkpoint_entry_marker(uintptr_t pc,unsigned long command) {
    /* Full-register completion and task-context injection retain their
     * post-syscall stop; scalar C returns need no interrupt round trip. */
    if (abi.ready && pc==abi.ready+4) return 1;
    if (abi.fd_control && pc==abi.fd_control+4) return md_fd_control_scalar(command);
    return abi.buffer_control && pc==abi.buffer_control+4
        && (md_adapter_buffer_scalar(command) || md_fs_buffer_scalar(command));
}

int trace_continues(int request) {
    return request==PTRACE_CONT || request==PTRACE_SYSCALL
        || request==PTRACE_SINGLESTEP || request==PTRACE_SYSEMU;
}

long native_trace(int request,pid_t pid,void *address,void *data) {
    long result=md_task_checkpoint_trace(&checkpoints,request,pid,address,data);
    if (result>=0 && trace_continues(request)) {
        /* Barriers retain the current owner's continuation, including a
         * task-affine call's entry/exit stops, not its enclosing guest phase. */
        struct thread *t=find_thread(pid);
        if (t) t->kernel_resume=request;
    }
    return result;
}

int tracee_request(struct thread *t, int request, void *address, void *data, const char *operation) {
    if (t->resume_lost) return 0;
    if (native_trace(request, t->pid, address, data)>=0) {
        if (request==PTRACE_GET_SYSCALL_INFO
                && ((struct ptrace_syscall_info *)data)->op==PTRACE_SYSCALL_INFO_NONE) {
            int displaced=md_task_stop_displaced(checkpoints.trace,t->pid);
            if (displaced<0) errno=-displaced;
            CHECK(displaced>=0);
            if (displaced) { t->resume_lost=1; return 0; }
        }
        if (request==PTRACE_INTERRUPT) t->interrupt_pending=1;
        return 1;
    }
    if (errno!=ESRCH) {
        int error=errno;
        fprintf(stderr,"guest-runtime: ptrace failure pid=%d phase=%d operation=%s request=%d errno=%d\n",
            t->pid,t->phase,operation,request,error);
        errno=error;
        CHECK(errno == ESRCH);
    }
    /* EVENT_WAIT: concurrent group death/exec may invalidate a reported stop.
     * Keep ownership until its exact exit/exec event; the tree deadline bounds
     * missing events. Never substitute registers, mask or a successful resume. */
    SUPERVISOR_TRACE("PROBE lost-stop pid=%d phase=%d operation=%s\n", t->pid, t->phase, operation);
    t->resume_lost = 1; return 0;
}

int lost_stop_event(struct thread *t,unsigned event) {
    if (!t->resume_lost || event==PTRACE_EVENT_EXEC || event==PTRACE_EVENT_EXIT) return 0;
    /* A broker observation can lose the stop before returning that same event
     * to the dispatcher. Discard only a confirmed stale observation; retain
     * the task and all cleanup ownership for its exact terminal event. */
    int displaced=md_task_stop_displaced(checkpoints.trace,t->pid);
    if (displaced<0) errno=-displaced;
    CHECK(displaced==1);
    return 1;
}

static int machine_discovery(struct thread *t) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    return registry && !registry->disabled && t->entered && filter_entry_eligible(t)
        && !t->entry.blocked && !t->debug.options && !md_memory_exposed(t->memory)
        && !t->seccomp.notification_continued && !t->seccomp.admission_policy_valid && t!=entry_fence_owner;
}

static int machine_context(struct thread *t,const struct ptrace_syscall_info *info) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (t->phase!=IDLE || !registry) return 0;
    /* Revocation stops discovery, not recovery of an already resident entry. */
    return !info || (machine_discovery(t) && md_entry_supported((int)info->seccomp.nr))
        || md_svc_registry_context(registry,info->instruction_pointer,info->stack_pointer);
}

int machine_observe(struct thread *t,const struct user_pt_regs *regs) {
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    if (!machine_discovery(t) || !md_entry_supported((int)regs->regs[8])
            || regs->pc<4 || regs->pc==RAW_GATE) return 0;
    /* Publication belongs to the instruction, not this invocation's fast-path
     * eligibility. The common entry checks arguments, policy and ownership on
     * every call; original-context operations retain their Base owners. */
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    unsigned sites=diagnostics ? registry->count : 0;
    int published=md_svc_registry_direct(registry,t->pid,access ? access->memory : -1,regs->pc-4,regs->regs[8]);
    if (diagnostics && registry->count>sites) {
        const struct md_svc_site *site=&registry->sites[registry->count-1];
        if (site->state==MD_SVC_PUBLISHED)
            SUPERVISOR_TRACE("PROBE machine-entry pid=%d svc=%#llx nr=%llu publication=%s\n",
                t->pid,regs->pc-4,regs->regs[8],published==1 ? "first" : "extended");
        else SUPERVISOR_TRACE("PROBE machine-entry-unavailable pid=%d svc=%#llx nr=%llu pools=%u\n",
                t->pid,regs->pc-4,regs->regs[8],registry->pool_count);
    }
    return published==1;
}

int machine_native_entry(struct thread *t,const struct user_pt_regs *regs,unsigned long *cookie) {
    if (t->entry.blocked || t->debug.options || md_memory_exposed(t->memory)) return 0;
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int entry=md_svc_machine_native_entry(registry,regs);
    /* Installing a policy revokes this very alias. Recover its original site
     * before acquiring the fence, so alias retirement cannot replay and drain
     * an already-owned installation a second time. */
    if (entry && md_seccomp_filter_call(regs->regs[8],regs->regs[0],regs->regs[1])) return 0;
    if (!entry || !t->seccomp.application_filter) return entry;
    /* The alias has restored the caller's stack and arguments. Prove its
     * original policy, then retain the existing completion owner instead of
     * cancelling and replaying. Guest observation actions still need replay. */
    if (t->seccomp.admission_policy_valid || !filter_entry_eligible(t)
            || md_seccomp_policy_may_trace(t->seccomp.policy) || md_seccomp_policy_may_notify(t->seccomp.policy)) return 0;
    struct seccomp_data call={.nr=regs->regs[8],.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=md_svc_registry_call_origin(registry,regs->pc)};
    memcpy(call.args,regs->regs,sizeof(call.args));
    uint32_t decision;
    if (md_seccomp_policy_eval(t->seccomp.policy,&call,&decision) || decision!=SECCOMP_RET_ALLOW) return 0;
    if (*cookie==MD_INTERCEPT_TASK) {
        /* The policy mirror's gate can supersede an ordinary TRACE cookie.
         * Recover it with the same classifier as channel and notification
         * ingress. Notification/error owners retain original-site fallback. */
        decision=interception_decision(t,&call);
        unsigned action=decision&SECCOMP_RET_ACTION_FULL;
        if (action!=SECCOMP_RET_ALLOW && action!=SECCOMP_RET_TRACE) return 0;
        *cookie=action==SECCOMP_RET_ALLOW ? MD_INTERCEPT_NATIVE : decision&SECCOMP_RET_DATA;
    }
    return entry;
}

int cooperative_copying(struct thread *t) {
    struct md_buffer_span span;
    if (t->phase!=IDLE || !t->entry.buffer.pool
            || md_buffer_span(&t->entry.buffer,&span) || span.size<sizeof(struct md_entry_channel)) return 0;
    const struct md_entry_channel *c=span.data;
    return c->owner==t->pid && atomic_load_explicit(&c->copying,memory_order_acquire);
}

void cooperative_buffer_release(struct thread *t) {
    t->entry.selectors=NULL; t->entry.selector_revision=0; t->entry.route_flags=0;
    t->entry.control=NULL;
    t->entry.stop.resume=NULL;
    t->entry.stop.reason=MD_CONTEXT_COUNT;
    t->entry.effect=NULL;
    t->entry.execution=NULL;
    t->entry.kernel_context=0;
    if (t->entry.buffer.pool) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->entry.buffer,&span) && span.size>=sizeof(struct md_entry_channel));
        struct md_entry_channel *c=span.data;
        if (statistics) {
            statistics->entry_local_native+=atomic_load_explicit(&c->local_native,memory_order_relaxed);
            for (unsigned i=0;i<MD_CONTEXT_COUNT;++i)
                statistics->entry_local_contexts[i]+=atomic_load_explicit(&c->local_contexts[i],memory_order_relaxed);
        }
        md_memory_admission_detach(t->memory,&c->memory);
        CHECK(!md_buffer_release(&t->entry.buffer));
    }
}

void cooperative_routes_publish(struct thread *t) {
    if (!t->entry.buffer.pool) return;
    unsigned flags=(t->process_op.filter ? MD_ROUTE_PID : 0)|(t->signals.host ? MD_ROUTE_SIGNALS : 0);
    if (!checkpoint_restricted(t->pid)) flags|=MD_ROUTE_ADMITTED_KERNEL;
    uint64_t revision=md_fd_selector_revision(t->descriptors.selector);
    if (t->entry.selectors==t->descriptors.selector && t->entry.selector_revision==revision && t->entry.route_flags==flags) return;
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->entry.buffer,&span) && span.size>=sizeof(struct md_entry_channel));
    struct md_entry_channel *c=span.data;
    struct md_fd_selector_view view;
    md_fd_selector_export(t->descriptors.selector,&view);
    md_interception_route_publish(&c->routes,&view,flags);
    t->entry.selectors=t->descriptors.selector; t->entry.selector_revision=revision; t->entry.route_flags=flags;
}

void cooperative_memory_publish(struct thread *t,int eligible) {
    if (!t->entry.buffer.pool) return;
    cooperative_routes_publish(t);
    struct md_buffer_span span;
    CHECK(!md_buffer_span(&t->entry.buffer,&span) && span.size>=sizeof(struct md_entry_channel));
    struct md_entry_channel *c=span.data;
    atomic_store_explicit(&c->policy,md_memory_snapshot_address(t->memory,
        md_seccomp_policy_identity(t->seccomp.policy)),memory_order_release);
    md_memory_admission_publish(t->memory,&c->memory,eligible && !md_shm_space_used(t->shm_space));
}

long cooperative_bind(struct thread *t) {
    if (!buffer_service || !broker || !t->entered || t->phase!=IDLE
            || t->entry.blocked || !filter_entry_eligible(t)) return -EOPNOTSUPP;
    if (t->entry.buffer.pool)
        return (long)md_memory_buffer_address(t->memory,&t->entry.buffer);
    int error=md_memory_channel_take(t->memory,&t->entry.buffer);
    if (error) return error;
    struct md_buffer_span span;
    error=md_buffer_span(&t->entry.buffer,&span);
    if (!error && span.size<sizeof(struct md_entry_channel)) error=-ENOBUFS;
    if (!error) error=md_broker_cooperative_bind(broker,t->pid,span.data,abi.buffer_admit);
    t->entry.selectors=NULL;
    if (!error) cooperative_memory_publish(t,machine_discovery(t));
    if (error) {
        cooperative_buffer_release(t);
        return error;
    }
    return (long)md_memory_buffer_address(t->memory,&t->entry.buffer);
}

int registers(pid_t pid, struct user_pt_regs *out) {
    struct iovec iov = {out, sizeof(*out)};
    if (!tracee_request(thread(pid), PTRACE_GETREGSET, (void *)NT_PRSTATUS, &iov, "get-registers")) return 0;
    CHECK(iov.iov_len == sizeof(*out)); return 1;
}

int set_registers(pid_t pid, struct user_pt_regs *regs) {
    struct iovec iov = {regs, sizeof(*regs)};
    return tracee_request(thread(pid), PTRACE_SETREGSET, (void *)NT_PRSTATUS, &iov, "set-registers");
}

int skip(pid_t pid) {
    int nr = -1; struct iovec iov = {&nr, sizeof(nr)};
    return tracee_request(thread(pid), PTRACE_SETREGSET, (void *)NT_ARM_SYSTEM_CALL, &iov, "skip-syscall");
}

int cooperative_native_observe(pid_t pid,struct md_entry_native *native) {
    unsigned state=atomic_load_explicit(&native->state,memory_order_acquire);
    if (state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED) return 0;
    struct user_pt_regs regs={0};
    if (state==MD_NATIVE_RUNNING) {
        if (!registers(pid,&regs)) return -ESRCH;
    }
    long value=(long)regs.regs[0],result=native->result;
    /* A terminal stop may retain openat's zero-extended input register. */
    if (native->number==SYS_openat && (unsigned long)value==(unsigned)AT_FDCWD) value=-512;
    int completed=md_native_stopped(state,regs.pc,value,abi.native_begin,abi.native_call,abi.native_end,&result);
    if (completed<0) return completed;
    if (state==MD_NATIVE_RUNNING) {
        regs.regs[0]=result; regs.pc=abi.native_end;
        if (!set_registers(pid,&regs)) return -ESRCH;
    }
    native->result=result;
    atomic_store_explicit(&native->state,completed,memory_order_release);
    return 0;
}

int entry_reply(struct thread *t,long value,uintptr_t extra) {
    struct md_entry_channel *channel=t->entry.control;
    if (!channel) return 0;
    CHECK(!t->entry.effect);
    t->entry.control=NULL;
    channel->result_extra=extra;
    md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,value);
    return 1;
}

int entry_pending(const struct thread *t) {
    return t->entry.control && t->entry.control->call.reserved==MD_ENTRY_EXECUTION_BEGIN;
}

int entry_context(struct thread *t,enum md_execution_context reason) {
    if (!entry_pending(t)) return 0;
    CHECK(t->phase==IDLE && !t->entry.execution && reason<MD_CONTEXT_COUNT);
    t->entry.control->execution_action=MD_EXECUTION_CONTEXT;
    if (statistics) ++statistics->entry_contexts[reason];
    return entry_reply(t,reason,0);
}

void native_resume(struct thread *t) {
    if (!entry_pending(t)) { resume(t->pid,0); return; }
    t->entry.control->execution_action=MD_EXECUTION_KERNEL;
    if (statistics) ++statistics->entry_native;
    CHECK(entry_reply(t,0,0));
}

void control_return(struct thread *t,struct user_pt_regs *regs,long value,uintptr_t extra) {
    if (entry_reply(t,value,extra)) return;
    regs->regs[0]=value; regs->regs[1]=extra;
    if (set_registers(t->pid,regs)) resume(t->pid,0);
}

void return_value(struct thread *t, struct user_pt_regs *regs, long value) {
    if (entry_pending(t)) {
        struct md_entry_channel *channel=t->entry.control;
        CHECK(t->phase==IDLE && !t->entry.execution);
        CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&t->identity,&t->resources));
        channel->execution_action=MD_EXECUTION_RESULT;
        if (statistics) ++statistics->entry_completions;
        CHECK(entry_reply(t,value,0));
        return;
    }
    if (t->process_op.clone_prepared && regs->regs[8]==SYS_clone3) {
        execution_capture(t,regs); t->phase=OBSERVING;
    }
    if (!skip(t->pid)) return;
    regs->regs[0] = (uint64_t)value;
    if (set_registers(t->pid, regs)) resume(t->pid, 0);
}

void return_scalar(struct thread *t,long value) {
    /* NT_PRSTATUS accepts a prefix. Leave argument registers, PC and PSTATE
     * untouched when the admitted call only needs a return value. */
    uint64_t result=(uint64_t)value;
    struct iovec io={&result,sizeof(result)};
    if (skip(t->pid) && tracee_request(t,PTRACE_SETREGSET,(void *)NT_PRSTATUS,&io,"set-result"))
        resume(t->pid,0);
}

void return_task_value(struct thread *t,struct user_pt_regs *regs,long value,
        enum md_task_call_boundary boundary) {
    /* A borrowed call can consume the application's kernel exit. Publish that
     * exit exactly once; an intact entry still gets its ordinary native stop. */
    if (boundary==MD_TASK_CALL_EXIT && t->debug.call_active) t->debug.adapter=1;
    return_value(t,regs,value);
}

void restart_call(struct thread *t,const struct user_pt_regs *original,uint64_t mask,
        enum call_restart policy) {
    execution_capture(t,original); t->execution.mask=mask; t->execution.restart=policy;
    if (!shield(t)) return;
    struct user_pt_regs regs=*original;
    regs.pc=RAW_GATE-4; regs.regs[8]=SYS_gettid;
    if (!set_registers(t->pid,&regs)) return;
    t->phase=CALL_RESTART_ENTRY; resume(t->pid,0);
}

void restart_boundary(struct thread *t,const struct user_pt_regs *original,uint64_t mask,
        enum call_restart policy,enum md_task_call_boundary boundary) {
    if (boundary!=MD_TASK_CALL_SECCOMP) { restart_call(t,original,mask,policy); return; }
    /* This entry already owns orig_x0. A second gate would overwrite it with
     * the result of the skipped original syscall before SA_RESTART runs. */
    execution_capture(t,original); t->execution.mask=mask; t->execution.restart=policy;
    if (shield(t) && skip(t->pid)) { t->phase=CALL_RESTART_EXIT; resume(t->pid,0); }
}

int entry_require_stop(struct thread *t,enum md_execution_context reason,void (*continuation)(struct thread *)) {
    if (!t->entry.control) return 0;
    CHECK(continuation && reason<MD_CONTEXT_COUNT);
    CHECK(!t->entry.stop.resume && !t->entry.effect);
    struct md_entry_channel *channel=t->entry.control;
    t->entry.control=NULL; t->entry.stop.resume=continuation;
    t->entry.stop.reason=reason;
    if (statistics) {
        ++statistics->entry_promotions;
        ++statistics->entry_stop_reasons[reason];
    }
    /* No replay: the caller supplies a kernel continuation for this already
     * owned operation. Its resource request and result remain retained. */
    md_broker_entry_reply(broker,channel,MD_ENTRY_NEEDS_STOP,0);
    return 1;
}

void entry_effect(struct thread *t,long number,const uint64_t args[6],void (*complete)(struct thread *,long)) {
    CHECK(t->entry.control && !t->entry.stop.resume && !t->entry.effect);
    t->entry.effect=complete;
    if (statistics) ++statistics->entry_effects;
    SUPERVISOR_TRACE("PROBE entry-effect pid=%d nr=%ld args=%llu,%llu,%llu,%llu\n",t->pid,number,
        (unsigned long long)args[0],(unsigned long long)args[1],(unsigned long long)args[2],(unsigned long long)args[3]);
    md_broker_entry_effect(broker,t->entry.control,number,args);
}

void wait_cancel_entry(struct thread *t,enum phase next) {
    CHECK(next==FILE_WAITING || next==CONTROL_WAITING || next==CLOSE_WAITING || next==EPOLL_CALL
        || next==SOCKET_WAITING || next==IPC_WAITING);
    if (t->signals.host) {
        int error=md_task_call_cancel_entry(native_trace,t->pid,abi.task_return);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; return; }
    } else if (!skip(t->pid)) return;
    /* EVENT_WAIT: drain the skipped syscall's exit before restoring adapter
     * registers. At entry, seccomp/kernel return can still overwrite x0. */
    t->execution.wait_resume=next; t->phase=WAIT_CANCEL_EXIT; resume(t->pid,0);
}

/* Retire operation ownership and choose the next action without touching the
 * caller's registers, signal mask or execution transport. */
static enum execution_continuation execution_complete(struct thread *t,long value) {
    CHECK(t->phase==DISPATCHING && !t->control.pending);
    if (t->execution.request.nr==SYS_chroot && !value) {
        root_changes++;
        SUPERVISOR_TRACE("PROBE root-change pid=%d result=0\n",t->pid);
    }
    t->execution.epoll_started=0;
    if (t->execution.prepared) {
        /* Early argument failures may never claim the supplied input buffer. */
        CHECK(!buffer_cleanup(t));
    }
    if (value==MD_CALL_CLONE && t->execution.request.nr==SYS_clone3) {
        CHECK(!t->process_op.clone_prepared && t->fs.buffer.pool);
        t->process_op.clone_prepared=1;
        return EXECUTION_CLONE;
    }
    if (t->fs.direct==DIRECT_RESULT) {
        CHECK(!buffer_cleanup(t)); t->fs.direct=DIRECT_NONE;
    }
    /* Terminal acknowledgement owns the remaining socket custody. */
    if (t->io.receive) {
        CHECK((t->io.receive->ready || t->io.receive->brokered) && !t->io.receive->io_waiting);
        receive_release(t->io.receive); t->io.receive=NULL;
    }
    retry_clear(t,RETRY_PROC_IO);
    CHECK(!md_proc_file_io_cancel(&t->proc.io));
    md_proc_file_release(t->proc.file); t->proc.file=NULL; t->proc.waiting=t->proc.direct=0;
    if (t->process_op.preflight || t->seccomp.preflight) {
        if (t->seccomp.preflight && !value && entry_fence_owner==t) t->entry.fence_replay=1;
        if (t->seccomp.preflight) t->entry.registration_revoked=1;
        /* Replay crosses an application boundary: a signal handler may run
         * before the next filter entry. Never retain its memory turn there. */
        if (t->seccomp.preflight) filter_install_discard(t);
        t->process_op.preflight=t->seccomp.preflight=0;
        if (!value) {
            t->debug.replay_entry=t->debug.call_active;
            t->debug.adapter=0;
        }
        return value ? EXECUTION_PREFLIGHT_RETURN : EXECUTION_PREFLIGHT_REPLAY;
    }
    if (value==RESTART_SIGNAL || value==RESTART_PREPARATION) return EXECUTION_RESTART;
    if (t->memory_op.action==MEMORY_DETACH) {
        CHECK(!md_resource_order_complete(&t->memory_op.order)); t->memory_op.action=0;
    }
    if (value==MD_IPC_WAIT) return EXECUTION_IPC_WAIT;
    if (value==MD_WATCH_WAIT) return EXECUTION_WATCH_WAIT;
    if (value==MD_CALL_NATIVE) return EXECUTION_NATIVE;
    if (value<0 && t->execution.request.nr==SYS_fstat) {
        if (++fstat_failures<=8)
            SUPERVISOR_TRACE("PROBE fstat failed pid=%d fd=%llu result=%ld\n",t->pid,t->execution.request.args[0],value);
    }
    return EXECUTION_RETURN;
}

void dispatch_complete(struct thread *t,long value) {
    enum execution_continuation next=execution_complete(t,value);
    pid_t pid=t->pid;
    struct user_pt_regs regs=t->execution.registers;
    switch (next) {
    case EXECUTION_CLONE:
        regs.pc-=4;
        if (!set_registers(pid,&regs)) return;
        md_memory_stack_return(t->memory,t->execution.stack); t->execution.stack=0;
        t->phase=CLONE_ENTRY; resume(pid,0); return;
    case EXECUTION_PREFLIGHT_RETURN:
    case EXECUTION_PREFLIGHT_REPLAY:
        if (next==EXECUTION_PREFLIGHT_REPLAY) regs.pc-=4;
        else regs.regs[0]=value;
        if (!set_registers(pid,&regs) || !restore_mask(t)) return;
        md_memory_stack_return(t->memory,t->execution.stack); t->execution.stack=0;
        t->phase=IDLE; resume(pid,0); return;
    case EXECUTION_RESTART:
        restart_call(t,&t->execution.registers,t->execution.mask,(enum call_restart)value); return;
    case EXECUTION_IPC_WAIT: {
        int64_t deadline=md_ipc_deadline(t->io.pending);
        int64_t remaining=0;
        if (deadline!=INT64_MAX) {
            int64_t now=md_event_now(); CHECK(now>=0);
            remaining=deadline>now ? deadline-now : 0;
        }
        regs.pc=abi.ipc_wait; regs.sp=t->execution.stack;
        regs.regs[30]=abi.done_entry;
        regs.regs[1]=remaining/1000000000LL; regs.regs[2]=remaining%1000000000LL;
        regs.regs[3]=deadline!=INT64_MAX;
        if (!set_registers(pid,&regs)) return;
        /* EVENT_WAIT: IPC mutation or owner death; semtimedop's deadline
         * returns EAGAIN. Signals interrupt without restarting the operation. */
        t->phase=IPC_WAIT_ENTRY; resume(pid,0); return;
    }
    case EXECUTION_WATCH_WAIT:
    case EXECUTION_NATIVE:
        regs.pc=RAW_GATE-4;
        t->watch_wait=next==EXECUTION_WATCH_WAIT ? 1 : 2;
        if (t->watch_wait==1) {
            /* EVENT_WAIT: native MSG_PEEK waits without consuming queue data.
             * Original signals/restart policy apply; the worker stays runnable. */
            regs.regs[8]=SYS_recvfrom;
            regs.regs[1]=t->execution.stack-16; regs.regs[2]=1;
            regs.regs[3]=MSG_PEEK; regs.regs[4]=regs.regs[5]=0;
        }
        if (!set_registers(pid,&regs)) return;
        t->phase=WATCH_ENTRY; resume(pid,0); return;
    case EXECUTION_RETURN:
        break;
    }
    regs.regs[0]=value;
    if (!set_registers(pid,&regs) || !restore_mask(t)) return;
    md_memory_stack_return(t->memory,t->execution.stack); t->execution.stack=0;
    if (completed_event_resume(t)) return;
    if (t->io.socket_sigpipe) {
        /* A checkpoint is not a signal-delivery stop. Publish application
         * SIGPIPE through the common delivery owner. */
        siginfo_t info={.si_signo=SIGPIPE,.si_code=SI_USER,.si_pid=t->tgid,.si_uid=getuid()};
        CHECK(!md_signal_delivery_hold(&t->signals.delivery,&info));
        t->io.socket_sigpipe=0;
    }
    t->phase=IDLE; resume(pid,0);
}

int uts_access(const struct thread *t,long number) {
    return number==SYS_uname || md_identity_capable_in(&t->identity,
        md_uts_namespace_owner(t->resources.uts),CAP_SYS_ADMIN) ? 0 : -EPERM;
}

void execution_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r,const char *mask_reason) {
    long nr=call->nr;
    if (!entry_pending(t)) {
        execution_capture(t,r);
        if (save_signal_mask(t,&t->execution.mask,mask_reason) && shield(t) && skip(t->pid)) dispatch_with_stack(t);
        return;
    }
    uint64_t args[6]; memcpy(args,call->args,sizeof(args));
    enum md_execution_context reason=md_execution_context_needed(nr,args,MD_INTERCEPT_DISPATCH);
    if (reason!=MD_CONTEXT_COUNT) { entry_context(t,reason); return; }
    struct md_entry_channel *channel=t->entry.control;
    t->entry.control=NULL; t->entry.execution=channel; t->execution.request=*call;
    t->phase=DISPATCHING; ++calls; ++t->calls;
    channel->execution=execution_prepare(t);
    channel->execution_action=MD_EXECUTION_GUEST;
    publish_execution(t,0);
    md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,0);
}

static void entry_begin(struct thread *t,struct md_entry_channel *channel) {
    struct seccomp_data call={.nr=(int)channel->call.number,.arch=AUDIT_ARCH_AARCH64,
        .instruction_pointer=channel->instruction_pointer};
    memcpy(call.args,channel->call.args,sizeof(call.args));
    t->entry.control=channel;
    if (statistics) ++statistics->entry_executions;
    uint32_t decision=interception_decision(t,&call);
    unsigned action=decision&SECCOMP_RET_ACTION_FULL;
    if (action==SECCOMP_RET_ERRNO) {
        unsigned error=decision&SECCOMP_RET_DATA;
        return_value(t,NULL,-(long)(error>4095 ? 4095 : error)); return;
    }
    CHECK(action==SECCOMP_RET_ALLOW || action==SECCOMP_RET_TRACE || action==SECCOMP_RET_USER_NOTIF);
    unsigned long cookie=action==SECCOMP_RET_ALLOW ? MD_INTERCEPT_NATIVE
        : action==SECCOMP_RET_USER_NOTIF ? MD_INTERCEPT_DISPATCH : decision&SECCOMP_RET_DATA;
    if (cookie&MD_INTERCEPT_CLOSE) cookie=MD_INTERCEPT_FD;
    if (cookie==MD_INTERCEPT_SIGNAL || call.nr==SYS_signalfd4) {
        entry_context(t,MD_CONTEXT_SIGNAL); return;
    }
    if (call.nr==SYS_ptrace || call.nr==SYS_wait4 || call.nr==SYS_waitid) {
        entry_context(t,call.nr==SYS_ptrace ? MD_CONTEXT_DEBUG : MD_CONTEXT_WAIT); return;
    }
    if (resource_call(t,&call,NULL,&cookie)) return;
    if (cookie==MD_INTERCEPT_NATIVE) { native_resume(t); return; }
    if (cookie==MD_INTERCEPT_OBSERVE) { entry_context(t,MD_CONTEXT_PROCESS); return; }
    CHECK(cookie==MD_INTERCEPT_DISPATCH || cookie==MD_INTERCEPT_READINESS);
    execution_begin(t,&call,NULL,"entry-dispatch");
}

static void entry_execution_retire(struct thread *t,long result) {
    struct md_entry_channel *channel=t->entry.execution;
    CHECK(channel && !t->entry.control && !t->entry.stop.resume && !t->entry.effect && !t->signals.shielded);
    if (statistics) ++statistics->entry_completions;
    t->entry.execution=NULL; t->entry.kernel_context=0; t->phase=IDLE;
    CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&t->identity,&t->resources));
    publish_execution(t,0);
    md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,result);
    if (md_signal_delivery_pending(&t->signals.delivery))
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"entry-signal-delivery");
}

static void entry_execution_native_done(struct thread *t,long result) {
    CHECK(t->entry.control==t->entry.execution);
    t->entry.control=NULL;
    entry_execution_retire(t,result);
}

static void entry_execution_finish(struct thread *t,long result) {
    enum execution_continuation next=execution_complete(t,result);
    if (next==EXECUTION_NATIVE) {
        /* Classification may require a real stop before choosing native IO.
         * The admitted nonblocking task operation still uses the same journal,
         * without repeating either classification or the application syscall. */
        uint64_t args[6]; memcpy(args,t->execution.request.args,sizeof(args));
        CHECK(md_execution_context_needed(t->execution.request.nr,args,MD_INTERCEPT_NATIVE)==MD_CONTEXT_COUNT);
        t->entry.control=t->entry.execution;
        entry_effect(t,t->execution.request.nr,args,entry_execution_native_done);
        return;
    }
    if (next!=EXECUTION_RETURN)
        fprintf(stderr,"entry completion pid=%d nr=%d action=%d result=%ld\n",
            t->pid,t->execution.request.nr,next,result);
    CHECK(next==EXECUTION_RETURN);
    entry_execution_retire(t,result);
}

void entry_control(pid_t pid,struct md_entry_channel *channel) {
    struct thread *t=find_thread(pid);
    unsigned command=(unsigned)channel->call.number;
    if (t && channel->call.reserved==MD_ENTRY_EXECUTION_BEGIN) {
        if (t->phase!=IDLE || t->entry.execution || t->entry.control || !t->ready
                || !channel->instruction_pointer || !md_entry_supported(command)) {
            md_broker_entry_reply(broker,channel,MD_ENTRY_DECLINED,0); return;
        }
        entry_begin(t,channel); return;
    }
    if (t && channel->call.reserved==MD_ENTRY_EXECUTION_END) {
        CHECK(t->entry.execution==channel && !t->entry.control && !t->entry.stop.resume);
        long result=(long)channel->call.args[0];
        entry_execution_finish(t,result);
        return;
    }
    unsigned kind=channel->call.reserved;
    if (kind==MD_ENTRY_EFFECT_DONE) {
        CHECK(t && t->entry.control==channel && t->entry.effect);
        unsigned state=atomic_load_explicit(&channel->native.state,memory_order_acquire);
        CHECK(state==MD_NATIVE_DONE || state==MD_NATIVE_CANCELLED);
        long result=channel->native.result;
        atomic_store_explicit(&channel->native.state,MD_NATIVE_EMPTY,memory_order_release);
        SUPERVISOR_TRACE("PROBE entry-effect-done pid=%d result=%ld\n",t->pid,result);
        void (*complete)(struct thread *,long)=t->entry.effect;
        t->entry.effect=NULL;
        complete(t,result); return;
    }
    if (t && !t->entry.control && kind==MD_ENTRY_RESOURCE_CONTROL) {
        unsigned long args[4];
        for (unsigned i=0;i<4;++i) args[i]=channel->call.args[i+1];
        long result=resource_control(t,command,channel->call.args[0],args);
        md_broker_entry_reply(broker,channel,MD_ENTRY_COMPLETED,result); return;
    }
    int fd=kind==MD_ENTRY_FD_CONTROL;
    if (!t || t->entry.control || (fd ? !md_fd_control_shared(command)
            : kind!=MD_ENTRY_BUFFER_CONTROL || !(md_adapter_buffer_shared(command) || md_fs_buffer_scalar(command)))) {
        md_broker_entry_reply(broker,channel,MD_ENTRY_DECLINED,0);
        return;
    }
    struct supervisor_control_request control={.command=command,
        .args={channel->call.args[0],channel->call.args[1],channel->call.args[2]}};
    t->entry.control=channel;
    if (fd) fd_control(t,&control,NULL);
    else buffer_control(t,&control,NULL);
}

static void buffer_control(struct thread *t,const struct supervisor_control_request *control,const struct user_pt_regs *r) {
    if (t->entry.stop.resume) {
        CHECK(r && !t->entry.control && control->command==t->fs.request.command
            && control->args[0]==t->fs.request.args[0] && control->args[1]==t->fs.request.args[1]);
        void (*continuation)(struct thread *)=t->entry.stop.resume;
        CHECK(t->entry.stop.reason<MD_CONTEXT_COUNT);
        t->entry.stop.resume=NULL; t->entry.stop.reason=MD_CONTEXT_COUNT;
        buffer_capture(t,r);
        continuation(t); return;
    }
    long result=-EINVAL;
    if (statistics && control->command<128) ++statistics->buffer_commands[control->command];
    if (t->fs.waiting || t->fs.return_waiting || (t->fs.call && !md_fs_buffer_ack_pending(t->fs.call)
            && !md_fs_buffer_export_pending(t->fs.call)
            && !md_fs_buffer_snapshot_pending(t->fs.call)
            && !md_fs_buffer_task_pending(t->fs.call))) { CHECK(0); }
    t->fs.request=*control;
    if (r) t->fs.registers=*r;
    if (!buffer_service && control->command!=MD_BUFFER_MAPPED && control->command!=MD_BUFFER_READONLY_MAPPED
            && control->command!=MD_BUFFER_READONLY_INSTALL
            && control->command!=MD_BUFFER_SIGNAL_ENABLE && control->command!=MD_BUFFER_TAKE
            && control->command!=MD_BUFFER_RELEASE && control->command!=MD_BUFFER_PROBE && control->command!=MD_BUFFER_UTS
            && control->command!=MD_BUFFER_PROC_IO && control->command!=MD_BUFFER_PROC_ACK
            && control->command!=MD_BUFFER_FINISH && control->command!=MD_BUFFER_PROC_START
            && control->command!=MD_BUFFER_PROC_OPEN && control->command!=MD_BUFFER_PROC_META && control->command!=MD_FS_BUFFER_RIGHTS_READY
            && control->command!=MD_FS_BUFFER_RIGHTS_DONE && control->command!=MD_FS_BUFFER_CHANNEL_OBSERVED
            && control->command!=MD_BUFFER_PID_NUMBER && control->command!=MD_BUFFER_NATIVE_IDENTITY
            && control->command!=MD_BUFFER_DESCRIPTOR_META && control->command!=MD_BUFFER_PIDFD_NAMESPACE
            && control->command!=MD_BUFFER_PIDFD_SIGNAL
            && control->command!=MD_BUFFER_PIDFD_READY && control->command!=MD_BUFFER_PIDFD_SENT && control->command!=MD_BUFFER_PIDFD_OBSERVED
            && control->command!=MD_BUFFER_CONTROL_IOCTL_SPEC && control->command!=MD_BUFFER_CONTROL_IOCTL
            && control->command!=MD_BUFFER_CONTROL_MAP && control->command!=MD_BUFFER_CONTROL_IOCTL_LAYOUT
            && control->command!=MD_BUFFER_NETWORK_OPEN && control->command!=MD_BUFFER_NETWORK_CALL && control->command!=MD_BUFFER_NETWORK_DATA
            && control->command!=MD_BUFFER_NETWORK_CREDENTIALS
            && control->command!=MD_BUFFER_MEMORY_OPEN && control->command!=MD_BUFFER_MEMORY_OPEN_RESULT
            && !(control->command>=MD_BUFFER_EPOLL_BEGIN && control->command<=MD_BUFFER_POLL_COPY)
            && control->command!=MD_FS_BUFFER_RIGHTS_BEGIN) {
        buffer_reply(t,-ENOTSUP); return;
    }
    if (t->io.transport.packet && control->command!=MD_BUFFER_RELEASE) {
        buffer_reply(t,-EBUSY); return;
    }
    if (t->io.outgoing && control->command!=MD_FS_BUFFER_SEND_COMMIT && control->command!=MD_BUFFER_RELEASE
            && control->command!=MD_FS_BUFFER_IPC_IO_PREPARE && control->command!=MD_FS_BUFFER_IPC_IO_COPY
            && control->command!=MD_FS_BUFFER_IPC_CAPTURE_READY && control->command!=MD_FS_BUFFER_IPC_CAPTURE_SENT
            && control->command!=MD_FS_BUFFER_IPC_CAPTURE_OBSERVED) {
        buffer_reply(t,-EBUSY); return;
    }
    int snapshot=md_fs_buffer_snapshot_pending(t->fs.call);
    if (t->fs.call && control->command!=MD_FS_BUFFER_ACK && control->command!=MD_FS_BUFFER_SENT
            && control->command!=MD_FS_BUFFER_CHANNEL_OBSERVED
            && !(md_fs_buffer_task_pending(t->fs.call)
                && (control->command==MD_BUFFER_RELEASE || control->command==MD_FS_BUFFER_TASK_DONE))
            && !(snapshot && (control->command==MD_FS_BUFFER_SNAPSHOT_APPEND
                || control->command==MD_FS_BUFFER_SNAPSHOT_FINISH || control->command==MD_BUFFER_RELEASE))) {
        buffer_reply(t,-EBUSY); return;
    }
    if (t->fs.returned.files.table && control->command!=MD_FS_BUFFER_RIGHTS_READY
            && control->command!=MD_FS_BUFFER_RIGHTS_DONE && control->command!=MD_FS_BUFFER_CHANNEL_OBSERVED) {
        buffer_reply(t,-EBUSY); return;
    }
    switch (control->command) {
    case MD_FS_BUFFER_RESULT_REPLAY:
    case MD_BUFFER_TAKE:
    case MD_FS_BUFFER_CALL:
    case MD_FS_BUFFER_EXEC_PREPARE:
    case MD_FS_BUFFER_EXEC_CANCEL:
    case MD_FS_BUFFER_SNAPSHOT_BEGIN:
    case MD_FS_BUFFER_SNAPSHOT_APPEND:
    case MD_FS_BUFFER_SNAPSHOT_FINISH:
    case MD_BUFFER_RELEASE:
    case MD_FS_BUFFER_TASK_DONE:
    case MD_BUFFER_FINISH:
    case MD_FS_BUFFER_ACK:
    case MD_FS_BUFFER_SENT:
    case MD_FS_BUFFER_CHANNEL_OBSERVED:
    case MD_FS_BUFFER_RIGHTS_BEGIN:
    case MD_FS_BUFFER_RIGHTS_READY:
    case MD_FS_BUFFER_RIGHTS_DONE:
        fs_buffer_control(t,control); return;
    case MD_BUFFER_POLL_BEGIN:
    case MD_BUFFER_POLL_LOAD:
    case MD_BUFFER_POLL_COPY:
    case MD_BUFFER_EPOLL_BEGIN:
    case MD_BUFFER_EPOLL_CAPTURE_READY:
    case MD_BUFFER_EPOLL_CAPTURE_SENT:
    case MD_BUFFER_EPOLL_CAPTURE_OBSERVED:
    case MD_BUFFER_EPOLL_WAIT:
        readiness_buffer_control(t,control,r); return;
    case MD_FS_BUFFER_IPC_IO_PREPARE:
    case MD_FS_BUFFER_IPC_IO_COPY:
    case MD_FS_BUFFER_IPC_IO_SELECT:
    case MD_FS_BUFFER_IPC_CAPTURE_READY:
    case MD_FS_BUFFER_IPC_CAPTURE_SENT:
    case MD_FS_BUFFER_IPC_CAPTURE_OBSERVED:
    case MD_FS_BUFFER_IPC_RECEIVE:
    case MD_FS_BUFFER_SEND_PREPARE:
    case MD_FS_BUFFER_SEND_COMMIT:
    case MD_FS_BUFFER_TASK_RECEIVE:
    case MD_BUFFER_NETWORK_DATA:
    case MD_FS_BUFFER_RECEIVED_DROP:
    case MD_FS_BUFFER_RECEIVED_INSPECT:
    case MD_FS_BUFFER_RECEIVED_RIGHTS:
    case MD_BUFFER_IPC_PREPARE:
    case MD_BUFFER_NETWORK_OPEN:
    case MD_BUFFER_NETWORK_CALL:
    case MD_BUFFER_NETWORK_CREDENTIALS:
    case MD_BUFFER_SHM_STAT:
        io_buffer_control(t,control,r); return;
    case MD_BUFFER_PROBE:
    case MD_BUFFER_MAPPED:
    case MD_BUFFER_READONLY_MAPPED:
    case MD_BUFFER_READONLY_INSTALL:
    case MD_BUFFER_CHANNEL_INSTALL:
    case MD_BUFFER_CLIENT_INSTALL:
    case MD_BUFFER_MEMORY_OPEN:
    case MD_BUFFER_MEMORY_OPEN_RESULT:
        memory_buffer_control(t,control,r); return;
    case MD_BUFFER_SIGNAL_ENABLE:
        signals_buffer_control(t,control); return;
    case MD_FS_BUFFER_PROC_LINK:
    case MD_BUFFER_PROC_OPEN:
    case MD_BUFFER_PROC_META:
    case MD_BUFFER_PROC_START:
    case MD_BUFFER_PROC_IO:
    case MD_BUFFER_PROC_ACK:
        proc_buffer_control(t,control); return;
    case MD_BUFFER_DESCRIPTOR_META:
    case MD_BUFFER_PIDFD_NAMESPACE:
    case MD_BUFFER_PIDFD_SIGNAL:
    case MD_BUFFER_PIDFD_READY:
    case MD_BUFFER_PIDFD_SENT:
    case MD_BUFFER_PIDFD_OBSERVED:
    case MD_BUFFER_CONTROL_MAP:
    case MD_BUFFER_CONTROL_CAPTURE:
    case MD_BUFFER_CONTROL_CAPTURE_READY:
    case MD_BUFFER_CONTROL_CAPTURE_SENT:
    case MD_BUFFER_CONTROL_CAPTURE_OBSERVED:
    case MD_BUFFER_CONTROL_IOCTL_SPEC:
    case MD_BUFFER_CONTROL_IOCTL_LAYOUT:
    case MD_BUFFER_CONTROL_IOCTL:
        descriptors_buffer_control(t,control); return;
    case MD_BUFFER_CLONE_CGROUP:
    case MD_BUFFER_NATIVE_IDENTITY:
    case MD_BUFFER_PID_NUMBER:
        task_buffer_control(t,control); return;
    case MD_BUFFER_UTS:
        domain_buffer_control(t,control); return;
    }
    buffer_reply(t,result); adapter_progress();
}

static int resource_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r,unsigned long *cookie) {
    /* All admitted ingresses retain the same resource-owner order. Owned
     * limits translate their target before a stacked PID selector can do so. */
    if (process_limit_call(t,call,r) || process_call(t,call,r,cookie)
            || identity_call(t,call,r,*cookie) || domain_call(t,call,r,*cookie)) return 1;
    switch (*cookie) {
    case MD_INTERCEPT_FD:
        if (md_clone_call(call->nr) && (md_shm_space_used(t->shm_space) || md_memory_has_policy(t->memory))
                && !(t->process_op.cloning&CLONE_VM)) {
            memory_begin(t,r,MEMORY_FORK,0); return 1;
        }
        break;
    case MD_INTERCEPT_CAPTURE:
        /* A resident request must first recover its genuine kernel carrier;
         * the six-argument channel frame is not that carrier's authority. */
        if (entry_context(t,MD_CONTEXT_DESCRIPTOR)) return 1;
        if (!fd_capture_valid(t,r)) { return_value(t,r,-EINVAL); return 1; }
        break;
    case MD_INTERCEPT_CWD: break;
    case MD_INTERCEPT_MEMORY:
        CHECK(t->phase==IDLE);
        memory_begin(t,r,MEMORY_KERNEL,0); return 1;
    default:
        if (descriptor_call(t,call,r,*cookie)) return 1;
        /* Stacked selectors survive close/reuse and fork/exec. Their newest
         * cookie does not establish the current description's type. */
        if (*cookie==MD_INTERCEPT_PROC || *cookie==MD_INTERCEPT_WATCH) {
            proc_lookup_begin(t,r); return 1;
        }
        return 0;
    }
    if (!entry_context(t,md_clone_call(call->nr) ? MD_CONTEXT_BIRTH : MD_CONTEXT_DESCRIPTOR))
        fd_begin(t,r,(unsigned)*cookie);
    return 1;
}

static int svc_client_alive(pid_t pid,void *memory) {
    struct thread *t=find_thread(pid);
    return t && t->memory==memory && !t->terminal_stop;
}

static int svc_clients_prepare(struct thread *t) {
    struct md_svc_registry *r=md_memory_svc(t->memory);
    if (!r || (r->disabled && !r->frames_dirty)) return 1;
    const struct md_private_memory_access *access=md_memory_private_access(t->memory);
    int fd=access ? access->memory : -1;
    int error=md_svc_registry_prepare_fork(r,t->pid,fd);
    if (!error) error=md_svc_registry_identity(r,t->pid,fd,md_memory_sole_owner(t->memory) ? t->pid : 0);
    if (!error) error=md_svc_registry_collect_clients(r,t->pid,fd,svc_client_alive,t->memory);
    if (error==-EIO || error==-EFAULT) {
        int displaced=md_task_stop_displaced(checkpoints.trace,t->pid);
        if (displaced>0) error=-ESRCH;
        else if (displaced<0) error=displaced;
    }
    if (error==-ESRCH) { t->resume_lost=1; return 0; }
    if (error) errno=-error;
    CHECK(!error);
    return 1;
}

static void publish_execution(struct thread *t,int resuming) {
    if (!broker) return;
    int eligible=t->ready && t->phase==IDLE && t->endpoint[0];
    int paths=!md_domain_restricted(t->resources.fs);
    int raw=eligible && paths && !md_seccomp_policy_may_trace(t->seccomp.policy) && !md_seccomp_policy_may_notify(t->seccomp.policy);
    int direct=eligible && filter_entry_eligible(t) && !t->debug.options
        && !t->debug.call_active && !t->debug.step_call && !md_debugger_traced(debugger,t->pid);
    int opens=direct && paths && !t->seccomp.application_filter;
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    int discovery=direct && registry && !registry->disabled && !md_memory_exposed(t->memory);
    cooperative_memory_publish(t,discovery && !t->entry.blocked);
    struct md_broker_code_ranges ranges={.size=MD_SVC_CODE_BYTES};
    _Static_assert(MD_SVC_CODE_POOLS<=8,"broker code range capacity");
    if (registry) {
        ranges.count=registry->pool_count;
        for (unsigned i=0;i<ranges.count;++i) ranges.starts[i]=registry->pools[i].address;
    }
    CHECK(!md_broker_task(broker,t->pid,eligible ? (raw ? MD_BROKER_READ : 0)|(opens ? MD_BROKER_OPEN : 0)
        |(discovery ? MD_BROKER_DISCOVERY : 0)
        |(direct && !t->entry.blocked ? MD_BROKER_COOPERATIVE : 0) : 0,
        RAW_GATE,abi.watch_return,&ranges,resuming));
}

void resume(pid_t pid, int sig) {
    struct thread *t = thread(pid);
    if (t->resume_lost) return;
    if (retained_task_held(t)) return;
    if (!t->signals.host_blocked && t->phase==IDLE && t->entered && t->signals.pending) {
        struct md_signal_pending_value pending;
        if (md_signal_pending_peek(t->signals.pending,&pending)) {
            CHECK(!md_signal_delivery_generated(&t->signals.delivery,&pending.info));
            CHECK(md_signal_pending_take(t->signals.pending,&pending));
        }
    }
    if (t->signals.retained) {
        CHECK(!sig || sig==t->signals.retained);
        sig=t->signals.retained;
    }
    if (entry_fence_owner==t && t->phase==IDLE) {
        if (t->entry.fence_replay) t->entry.fence_replay=0;
        else entry_fence_owner=NULL;
    }
    if (!memory_available(pid)) {
        CHECK(!t->memory_op.resume || !sig || sig==t->memory_op.signal);
        t->memory_op.resume=1; if (sig) t->memory_op.signal=sig;
        struct md_task_quiesce *barrier=memory_execution ? memory_barrier
            : mapping_execution ? mapping_barrier : task_action_barrier;
        if (retained_creation || retained_retirement) barrier=retained_barrier;
        if (policy_installer && pid!=policy_installer->pid) barrier=policy_barrier;
        /* A lent actor has a separate resume owner until its registers return. */
        if (!task_action_execution || task_action_execution->actor!=t || !task_action_execution->borrowed)
            CHECK(!md_task_quiesce_add(barrier,pid,1,0));
        return;
    }
    if (t->phase==IDLE && t->seccomp.admission_policy_valid && !t->seccomp.admission_policy_pending) {
        md_seccomp_policy_release(t->seccomp.admission_policy); t->seccomp.admission_policy=NULL;
        t->seccomp.admission_policy_valid=0;
    }
    if (t->terminal_stop) {
        if (t->close.terminal_pending) {
            t->close.terminal_pending=0;
            md_resources_exit_files(&t->resources,&t->close.closed);
            close_event_begin(t,PTRACE_EVENT_EXIT,t->close.message);
            return;
        }
        /* No guest instruction or syscall remains after the exit event. Keep
         * ownership until waitpid reaps it, without resuming adapter/debug steps. */
        if (tracee_request(t,PTRACE_CONT,NULL,NULL,"continue-exit")) t->kernel_stopped=0;
        return;
    }
    /* A retained asynchronous signal or carrier can resume through this
     * gateway while a native-call owner is restoring its boundary. Preserve
     * that owner's SYSCALL/SYSEMU continuation, not the enclosing guest phase. */
    struct md_task_call *borrowed=task_call_continuation(t);
    if (borrowed) {
        task_call_resume(t,borrowed,sig);
        if (!t->kernel_stopped) t->signals.retained=0;
        return;
    }
    if (t->memory_op.access_pending && t->born && t->initial_stop) {
        t->memory_op.access_pending=0;
        memory_access_capture(t);
    }
    if (t->process_op.birth_child) {
        /* EVENT_WAIT: the stopped newborn publishes shared SETTID fields before
         * either task returns to libc. Completion or death releases the parent;
         * launch cancellation bounds a missing event. No late parent rewrite may
         * race the newborn's mutex ownership or kernel CHILD_CLEARTID store. */
        CHECK(!t->process_op.birth_waiting || !sig || sig==t->process_op.birth_signal);
        t->process_op.birth_waiting=1;
        if (sig) t->process_op.birth_signal=sig;
        return;
    }
    if (t->phase==IDLE && t->process_op.init_pending && t->initial_stop) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return;
        execution_capture(t,&regs);
        if (!save_signal_mask(t,&t->execution.mask,"pid-init-mask") || !shield(t)) return;
        regs.pc=RAW_GATE-4; regs.regs[8]=SYS_prctl;
        regs.regs[0]=PR_SET_CHILD_SUBREAPER; regs.regs[1]=1;
        for (unsigned i=2;i<6;++i) regs.regs[i]=0;
        t->phase=PROCESS_INIT_ENTRY; t->process_op.init_pending=0;
        if (!set_registers(pid,&regs)) return;
    }
    if (t->phase==IDLE && t->process_op.store_count && t->initial_stop) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return;
        execution_capture(t,&regs);
        if (!save_signal_mask(t,&t->execution.mask,"pid-store-mask") || !shield(t)) return;
        regs.pc=abi.store_groups; regs.regs[0]=t->process_op.stores[0].address;
        regs.regs[1]=1; regs.regs[3]=t->process_op.stores[0].value;
        --t->process_op.store_count; t->process_op.stores[0]=t->process_op.stores[1];
        t->phase=PROCESS_TID_STORE;
        if (!set_registers(pid,&regs)) return;
    }
    if (t->phase==IDLE && t->initial_stop) {
        pid_birth_release(t);
        if (t->debug.birth) {
            t->debug.birth=0;
            if (md_debugger_newborn(debugger,pid)) return;
        }
    }
    CHECK(!md_task_registry_publish(tasks, t->pid, t->tgid, &t->identity, &t->resources));
    enum phase phase = t->phase;
    if (freezer_enabled && md_cgroup_member_freezing(t->resources.cgroup)) {
        if (debugger_at_boundary(t)) {
            CHECK(!t->freezer_resume || !sig || !t->freezer_signal || sig==t->freezer_signal);
            t->freezer_resume=1;
            if (sig) t->freezer_signal=sig;
            md_cgroup_member_frozen(t->resources.cgroup,1);
            return;
        }
        if (freezer_parked(t)) md_cgroup_member_frozen(t->resources.cgroup,1);
    }
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    if (!svc_clients_prepare(t)) return;
    if (machine && !machine->disabled && (md_memory_exposed(t->memory)
            || t->debug.options || md_debugger_traced(debugger,pid)))
        if (!svc_revoke_entry(t)) return;
    if (machine && machine->revoke_pending) {
        int error=md_svc_registry_revoke(machine,pid);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
    if (t->trace_options_dirty && debugger_options(pid,t->debug.options)) return;
    if (phase!=IDLE && t->debug.call_active) t->debug.adapter=1;
    if (debugger_at_boundary(t) && t->entered && t->image.process_image) {
        if (t->debug.call_active && t->debug.adapter) {
            struct user_pt_regs regs;
            if (!registers(pid,&regs)) return;
            if (debug_syscall_exit(t,&regs)) return;
        }
        if (debugger_boundary(t)) return;
    }
    if (debugger_at_boundary(t) && t->debug.step_call) {
        t->debug.step_call=0;
        siginfo_t info={.si_signo=SIGTRAP,.si_code=TRAP_TRACE};
        if (md_debugger_stop(debugger,pid,(SIGTRAP<<8)|0x7f,&info,0)) return;
    }
    /* A stopped helper can shield a shared execution, but the resident C
     * frame already defers handlers. Restore its borrowed mask before returning
     * to that frame; only actual kernel waits retain their own mask transition. */
    if (t->entry.execution && phase==DISPATCHING && t->signals.shielded) {
        CHECK(t->entry.kernel_context);
        if (!restore_mask(t)) return;
    }
    publish_execution(t,1);
    int publish_signal=md_signal_delivery_pending(&t->signals.delivery) && !t->signals.delivery.active
        && !t->signals.shielded && !t->signals.return_pending;
    if (!sig && (t->machine.draining || publish_signal) && debugger_at_boundary(t)
            && !t->debug.call_active) {
        /* Carrier delivery resumes with CONT. A native syscall must first
         * publish its exit to the guest debugger, even when its result was
         * replaced without entering an adapter phase. A cold entry fence also
         * retires native aliases here, without waiting for another signal. */
        int deferred=md_svc_machine_flush(md_memory_svc(t->memory),&t->machine,pid);
        int error=deferred || !publish_signal ? (deferred<0 ? deferred : 0)
            : md_signal_delivery_publish(&t->signals.delivery,pid,t->tgid);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        CHECK(!error);
    }
    signal_query_publish(t);
    t->kernel_resume=phase==CALL_RESTART_ENTRY ? PTRACE_SYSEMU
        : t->signals.delivery.active && phase!=SIGNAL_NATIVE_EXIT ? PTRACE_CONT : (t->metadata_restart && phase==IDLE
            && (t->metadata_restart->stage==METADATA_ENTRY || t->metadata_restart->stage==METADATA_WAIT))
            || t->signals.return_pending || t->machine.syscall_exit || (phase==IDLE && t->machine.retry_svc)
            || t->fs.direct==DIRECT_ADMIT || t->descriptors.running || phase == OBSERVING || phase==PROCESS_OBSERVING
            || phase==PROCESS_INIT_ENTRY || phase==PROCESS_INIT_EXIT || phase == EXPORT_ENTRY || phase == EXPORT_NOTIFY
            || phase == EXPORT_RETURN || phase == EXECUTING || phase == WATCH_CANCEL
            || phase == WATCH_ENTRY || phase == WATCH_READ || phase==SOCKET_NATIVE || phase==DEBUG_WAIT_ENTRY || phase==DEBUG_WAIT_EXIT
            || phase==DEBUG_PARK_ENTRY || phase==DEBUG_PARK_EXIT
            || phase==IPC_WAIT_ENTRY || phase==IPC_WAITING || phase==SOCKET_WAIT_ENTRY || phase==SOCKET_WAITING
            || phase==CALL_RESTART_ENTRY || phase==CALL_RESTART_EXIT || phase==EPOLL_PROBE || phase==EPOLL_CALL || phase==EPOLL_WAIT
            || phase==FILE_WAIT_ENTRY || phase==FILE_WAITING
            || phase==CONTROL_WAIT_ENTRY || phase==CONTROL_WAITING || phase==CLOSE_WAIT_ENTRY || phase==CLOSE_WAITING
            || phase==WAIT_CANCEL_EXIT || phase==CLONE_ENTRY || phase==CLONE_ADMISSION
            || phase==SIGNAL_NATIVE_EXIT
            || phase==RESULT_REPLAY_ENTRY || phase==RESULT_REPLAY_EXIT
            || (debugger_at_boundary(t) && t->entered && t->image.process_image
                && md_debugger_syscall_mode(debugger,pid)) ? PTRACE_SYSCALL
                : debugger_at_boundary(t) && t->entered && md_debugger_step(debugger,pid) ? PTRACE_SINGLESTEP : PTRACE_CONT;
    if (!native_trace(t->kernel_resume,
            pid, NULL, (void *)(uintptr_t)sig)) { t->kernel_stopped=0; t->signals.retained=0; return; }
    CHECK(errno == ESRCH);
    /* EVENT_WAIT: thread-group exit or exec may win a stopped-thread resume.
     * A terminal stop may still precede reaping. Only the exact exit/exec event
     * releases ownership; the process deadline bounds a missing event. */
    t->resume_lost = 1;
}

int shield(struct thread *t) {
    uint64_t mask=md_signal_async_mask();
    /* Only the app-UID pre-entry gate requires an unblocked SIGSYS. Native
     * pending signals must remain queued until the guest's atomic wait. */
    if (!t->signals.host) mask |= MD_SIGNAL_SYS_BIT;
    if (!signal_mask(t,mask,"shield-signals")) return 0;
    t->signals.shielded=1; return 1;
}

int save_signal_mask(struct thread *t,uint64_t *mask,const char *reason) {
    if (!tracee_request(t,PTRACE_GETSIGMASK,(void *)sizeof(*mask),mask,reason)) return 0;
    SUPERVISOR_TRACE("PROBE signal-mask-save pid=%d reason=%s mask=%#llx phase=%d task=%d\n",
        t->pid,reason,(unsigned long long)*mask,t->phase,!!t->entry.execution);
    return 1;
}

int signal_mask(struct thread *t,uint64_t mask,const char *reason) {
    SUPERVISOR_TRACE("PROBE signal-mask-set pid=%d reason=%s mask=%#llx phase=%d task=%d\n",
        t->pid,reason,(unsigned long long)mask,t->phase,!!t->entry.execution);
    if (!tracee_request(t,PTRACE_SETSIGMASK,(void *)sizeof(mask),&mask,reason)) return 0;
    t->signals.handler_mask_known=0;
    t->signals.shielded=0; return 1;
}

int restore_mask(struct thread *t) {
    return signal_mask(t,t->execution.mask,"restore-mask");
}

uintptr_t dispatch_input(struct thread *t,unsigned policy) {
    if (t->execution.prepared) return md_memory_buffer_address(t->memory,&t->fs.buffer);
    long nr=(long)t->execution.request.nr;
    unsigned long count=nr==SYS_ppoll ? t->execution.request.args[1] : t->execution.request.args[0];
    int poll=(nr==SYS_ppoll || nr==SYS_pselect6) && count<=INT_MAX
        && (nr!=SYS_ppoll || count<=md_process_native_nofile(t->resources.process));
    int exec=(nr==SYS_execve || nr==SYS_execveat) && t->endpoint[0];
    /* Network and device-event adapters own their buffer lifecycle; only the
     * ordinary receive adapter consumes this prepared frame. */
    if (t->fs.buffer.pool || (policy&(MD_DISPATCH_NETWORK_DATA|MD_DISPATCH_NETWORK_CONTROL|MD_DISPATCH_DEVICE_EVENTS))
            || (nr!=SYS_recvmsg && nr!=SYS_recvmmsg && !poll && !exec)) return 0;
    /* Opportunistic admission, not a retained per-thread cache. Pool pressure
     * keeps the adapter's normal queued TAKE; unrelated calls allocate nothing. */
    if (md_buffers_take_sized(md_memory_buffers(t->memory),poll ? md_poll_bytes(count) : 0,&t->fs.buffer)) return 0;
    uintptr_t address=md_memory_buffer_address(t->memory,&t->fs.buffer);
    if (address) {
        t->execution.prepared=1;
        if (nr==SYS_recvmsg) {
            struct md_buffer_span span;
            CHECK(!md_buffer_span(&t->fs.buffer,&span));
            ((struct md_task_receive_frame *)span.data)->prepared=0;
        }
    }
    else CHECK(!buffer_release(t));
    return address;
}

unsigned dispatch_policy(struct thread *t) {
    unsigned policy = md_domain_scoped(t->resources.fs) || md_mount_namespace_managed_paths(t->resources.mounts)
        || t->resources.landlock;
    if (md_network_namespace_managed(t->resources.network)) policy|=MD_DISPATCH_NETWORK_SCOPED;
    if (t->execution.request.nr==SYS_socket && t->execution.request.args[0]==AF_NETLINK
            && t->execution.request.args[2]==NETLINK_KOBJECT_UEVENT && md_fs_worker_device_events(filesystem))
        policy|=MD_DISPATCH_DEVICE_EVENTS;
    if (network_descriptor_call(t->execution.request.nr)) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)t->execution.request.args[0],NULL);
        if (md_network_socket_offline(object)) policy|=MD_DISPATCH_OFFLINE_SOCKET;
        int interface_ioctl=t->execution.request.nr==SYS_ioctl && md_network_interface_ioctl(t->execution.request.args[1])
            && md_network_socket_namespace(object);
        if (md_network_socket_control(object) || interface_ioctl) policy|=MD_DISPATCH_NETWORK_CONTROL;
        if (md_network_endpoint_is(object) && !interface_ioctl) policy|=MD_DISPATCH_NETWORK_DATA;
        if (md_device_events_is(object)) policy|=MD_DISPATCH_DEVICE_EVENTS;
        md_fd_object_release(object);
    }
    return policy;
}

static struct md_guest_execution execution_prepare(struct thread *t) {
    struct md_guest_execution call={.number=(long)t->execution.request.nr,.policy=dispatch_policy(t),
        .control=t->proc.file ? (int)md_proc_file_kind(t->proc.file) : -1};
    for (unsigned i=0;i<6;++i) call.args[i]=t->execution.request.args[i];
    call.prepared=dispatch_input(t,call.policy);
    return call;
}

static struct user_pt_regs dispatch_registers(const struct thread *t,const struct md_guest_execution *call) {
    struct user_pt_regs regs=t->execution.registers;
    regs.pc=DISPATCH; regs.sp=t->execution.stack; regs.regs[30]=abi.done_entry;
    regs.regs[0]=call->number;
    for (unsigned i=0;i<6;++i) regs.regs[i+1]=call->args[i];
    regs.regs[9]=md_dispatch_control(call->policy,call->control);
    regs.regs[10]=call->prepared;
    return regs;
}

void dispatch(struct thread *t) {
    if (t->fs.direct==DIRECT_PREPARE_RESULT) {
        struct md_buffer_span span;
        CHECK(!md_buffer_span(&t->fs.buffer,&span));
        struct user_pt_regs regs=t->execution.registers;
        regs.pc=abi.buffer_result; regs.sp=t->execution.stack; regs.regs[30]=abi.done_entry;
        regs.regs[0]=t->fs.direct_flags; regs.regs[1]=t->fs.direct_mode;
        regs.regs[2]=md_memory_buffer_address(t->memory,&t->fs.buffer);
        CHECK(regs.regs[2]);
        if (direct_addfd(t,span.data)) {
            regs.pc=abi.buffer_enter; regs.regs[0]=MD_FS_BUFFER_RESULT_ADMIT;
        }
        t->fs.direct=DIRECT_RESULT; t->phase=DISPATCHING;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return;
    }
    struct md_guest_execution call=execution_prepare(t);
    struct user_pt_regs regs=dispatch_registers(t,&call);
    if (receive_prepared(t,&regs,call.policy)) return;
    if (!set_registers(t->pid, &regs)) return;
    t->phase = DISPATCHING; resume(t->pid, 0);
}

void dispatch_with_stack(struct thread *t) {
    if (proc_write_direct(t)) return;
    if (!t->execution.stack) t->execution.stack = md_memory_stack_take(t->memory);
    if (t->execution.stack) dispatch(t);
    else {
        struct user_pt_regs regs = t->execution.registers; regs.pc = ALLOCATE;
        if (!set_registers(t->pid, &regs)) return;
        t->phase = ALLOCATING; resume(t->pid, 0);
    }
}

void signal_wait_restore(struct thread *t) {
    if (!t->signals.wait_view.active) return;
    t->signals.host_blocked=!!(t->signals.wait_view.saved&MD_SIGNAL_SYS_BIT);
    t->signals.wait_view.active=0;
}

int interrupted_operation(struct thread *t) {
    if (t->phase==MEMORY_WAIT || t->phase==PROC_LOOKUP || t->memory_op.mapping) return 1;
    struct user_pt_regs regs;
    const struct user_pt_regs *context=NULL;
    if (notification_unexecuted(t) && !t->metadata_restart) {
        if (!registers(t->pid,&regs)) return 1;
        context=&regs;
        if (notification_entry_context(t,context,&t->metadata.request.data)) {
            /* The interrupt can reach a new lazy entry instead of the
             * cancelled syscall. Retire only unexecuted admission and let the
             * entry unwind; never combine its private SP with application PC. */
            notification_interrupted(t);
            resume(t->pid,0);
            return 1;
        }
    }
    /* EVENT_WAIT: INTERRUPT cancels the exact notification. Under SYSCALL the
     * acknowledgement is a syscall-exit stop; under CONT it is EVENT_STOP. */
    if (t->phase==EXPORT_CANCEL) { export_start(t,context); return 1; }
    if (t->phase==PROC_CANCEL) { proc_query_start(t); return 1; }
    if (t->phase==IPC_WAITING && t->io.waking) { ipc_wait_complete(t); return 1; }
    if (t->phase==SOCKET_WAITING && t->io.socket_waking) { socket_wait_complete(t); return 1; }
    if (t->phase==FILE_WAITING && md_fs_buffer_open_ready(t->fs.call)) { file_wait_complete(t); return 1; }
    if (t->phase==CONTROL_WAITING && t->control.done) { control_wait_complete(t); return 1; }
    if (t->phase==CLOSE_WAITING && t->close.done) { close_wait_complete(t); return 1; }
    if (t->phase==DEBUG_PARK_EXIT && t->debug.wait_waking) { debugger_park_complete(t); return 1; }
    if (t->phase!=DELEGATE_CANCEL) return 0;
    const struct seccomp_notif *q=&t->metadata.request;
    CHECK(ioctl(listener,SECCOMP_IOCTL_NOTIF_ID_VALID,&q->id)==-1 && errno==ENOENT);
    if (t->metadata_restart) regs=t->metadata_restart->saved;
    else CHECK(context);
    regs.pc=q->data.instruction_pointer; regs.regs[8]=q->data.nr;
    for (unsigned i=0;i<6;i++) regs.regs[i]=q->data.args[i];
    if (q->data.nr==SYS_rt_sigprocmask) {
        uint64_t mask;
        if (t->metadata_restart) mask=t->metadata_restart->mask;
        else if (t->signals.delivery.active) mask=t->signals.delivery.mask;
        else if (!save_signal_mask(t,&mask,"signal-query-admission")) return 1;
        mask=md_signal_view_mask(mask,t->signals.host_blocked);
        notification_release(t); t->phase=IDLE;
        int error=signal_control_begin(t,&regs,0,NULL,mask,NULL);
        if (error) return_task_value(t,&regs,error,MD_TASK_CALL_EXIT);
        return 1;
    }
    if (t->seccomp.notification && t->seccomp.notification->promoting) {
        struct guest_notification *n=t->seccomp.notification;
        struct ptrace_syscall_info observed={0};
        CHECK(native_trace(PTRACE_GET_SYSCALL_INFO,t->pid,(void *)sizeof(observed),&observed)>=0);
        CHECK(observed.op==PTRACE_SYSCALL_INFO_NONE || observed.op==PTRACE_SYSCALL_INFO_EXIT);
        notification_release(t); t->phase=GUEST_NOTIFICATION; execution_capture(t,&regs);
        int error=md_task_checkpoint_suspend(&checkpoints,t->pid);
        CHECK(!error); n->checkpoint_suspended=1; n->promoting=0;
        struct md_task_call_arguments args={.number=SYS_gettid,.value={MD_GUEST_SECCOMP_LISTENER,1},
            .flags=MD_TASK_CALL_INTERRUPTIBLE};
        error=md_task_call_begin(&n->call,native_trace,t->pid,(enum md_task_call_boundary)observed.op,
            &regs,abi.task_call,abi.task_return,&args);
        if (error) { CHECK(error==-ESRCH); t->resume_lost=1; }
        else t->kernel_stopped=0;
        return 1;
    }
    int machine=md_svc_machine_return(md_memory_svc(t->memory),&t->machine,t->pid,&regs);
    if (machine==-ESRCH) { t->resume_lost=1; return 1; }
    if (machine<0) errno=-machine;
    CHECK(machine>=0);
    if (machine) {
        notification_release(t); t->phase=IDLE;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return 1;
    }
    if (machine_observe(t,&regs) && !t->metadata_restart && !t->entry.fence_result) {
        /* The notification was cancelled before any effect. Only a newly
         * published site can enter here; an existing gate's fallback must
         * complete through Base instead of retrying the same gate forever. */
        notification_release(t); t->phase=IDLE; regs.pc-=4;
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
        return 1;
    }
    execution_capture(t,&regs); calls++; t->calls++;
    if (receive_entry(t)) return 1;
    if (!save_signal_mask(t,&t->execution.mask,"get-mask") || !shield(t)) return 1;
    dispatch_with_stack(t);
    return 1;
}

void execution_seccomp_event(struct thread *t,pid_t pid,int status,const struct rusage *usage) {
    struct user_pt_regs regs;
    unsigned long cookie;
    struct ptrace_syscall_info info={0};
    const struct ptrace_syscall_info *observed=NULL;
    if (t->phase==IDLE && t->ready) {
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"seccomp-call")) return;
        if (info.op==PTRACE_SYSCALL_INFO_SECCOMP) {
            cookie=info.seccomp.ret_data; observed=&info;
            if (memory_observed(t,&info)) return;
        }
    }
    if (!observed && !tracee_request(t,PTRACE_GETEVENTMSG,NULL,&cookie,"seccomp-event")) return;
    struct md_svc_registry *registry=md_memory_svc(t->memory);
    /* Discovery and resident recovery need full context. Ordinary IO
     * outside those regions can use the existing syscall observation,
     * including its original pointers and native wait semantics. */
    int captured=machine_context(t,observed);
    if (captured) {
        if (!registers(pid,&regs)) return;
        int native_entry=machine_native_entry(t,&regs,&cookie);
        if (native_entry && observed) info.seccomp.ret_data=cookie;
        int owned=native_entry ? 0 : md_svc_machine_syscall(md_memory_svc(t->memory),&t->machine,pid,&regs);
        if (owned==-ESRCH) { t->resume_lost=1; return; }
        if (owned<0) errno=-owned;
        CHECK(owned>=0);
        if (owned) { resume(pid,0); return; }
        /* Observe the executed site before compact Base handlers can
         * consume it. Reuse this snapshot for ordinary dispatch too. */
        struct user_pt_regs discovery=regs;
        if (native_entry) discovery.pc=md_svc_registry_call_origin(registry,regs.pc);
        int published=native_entry==1 ? 0 : machine_observe(t,&discovery);
        if (!native_entry && published && !t->entry.fence_result) {
            struct user_pt_regs entry=regs; entry.pc-=4;
            int redirected=md_svc_machine_redirect(&t->machine,pid,&entry);
            if (redirected==-ESRCH) { t->resume_lost=1; return; }
            if (redirected<0) errno=-redirected;
            CHECK(redirected>0);
            resume(pid,0); return;
        }
    }
    if (t->phase==WAIT_CANCEL_EXIT) {
        if (skip(pid)) resume(pid,0);
        return;
    }
    if (entry_fence_adapt(t)) {
        if (!captured && !registers(pid,&regs)) return;
        captured=1;
        if (!(regs.pc>=checkpoints.begin && regs.pc<checkpoints.end)) {
            CHECK(!t->entry.fence_held);
            t->entry.fence_held=1; t->entry.fence_status=status; t->entry.fence_usage=(*usage);
            return;
        }
    }
    if (t->entered && t->phase==IDLE && md_seccomp_policy_may_trace(admission_policy(t))) {
        if (!captured && !registers(pid,&regs)) return;
        captured=1;
        struct seccomp_data call={.nr=regs.regs[8],.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=regs.pc};
        memcpy(call.args,regs.regs,sizeof(call.args));
        if (guest_trace_rejected(t,&call)) { return_value(t,&regs,-ENOSYS); return; }
    }
    if (t->entered && t->phase==IDLE && md_seccomp_policy_may_notify(admission_policy(t))) {
        if (!captured && !registers(pid,&regs)) return;
        captured=1;
        struct seccomp_data call={.nr=regs.regs[8],.arch=AUDIT_ARCH_AARCH64,.instruction_pointer=regs.pc};
        memcpy(call.args,regs.regs,sizeof(call.args));
        if (t->seccomp.notification_continued) {
            t->seccomp.notification_continued=0; cookie=guest_notification_route(t,&call);
        } else if (guest_notification_begin(t,&call,&regs,NULL,NULL)) return;
    }
    if (observed && t->resources.files && fd_native_io(t,observed)) return;
    if (fd_close_compact(t,cookie,observed)) return;
    if (cookie&MD_INTERCEPT_CLOSE) cookie=MD_INTERCEPT_FD;
    if (observed && cookie==MD_INTERCEPT_SIGNAL && t->signals.host && t->entered
            && t->phase==IDLE && observed->instruction_pointer!=RAW_GATE
            && observed->instruction_pointer!=abi.task_return
            && signal_native_call(t,observed->seccomp.nr,
                observed->seccomp.args[1],observed->seccomp.args[2],observed->seccomp.args[3])) return;
    if (!captured && !registers(pid, &regs)) return;
    /* An owned nonblocking wait may encounter either the baseline
     * waitid observer or a stacked PID selector. Its exit belongs to
     * the same wait owner; do not start a second observation. */
    if ((t->phase==DEBUG_WAIT_ENTRY || t->phase==DEBUG_WAIT_EXIT)
            && (regs.regs[8]==SYS_wait4 || regs.regs[8]==SYS_waitid)) {
        resume(pid,0); return;
    }
    if (cookie==MD_INTERCEPT_SIGNAL && !t->entered) { resume(pid,0); return; }
    int signal_descriptor=regs.regs[8]==SYS_signalfd4;
    if (t->entered && t->phase==IDLE
            && (regs.regs[8]==SYS_read || regs.regs[8]==SYS_readv)) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)regs.regs[0],NULL);
        int selected=md_signal_fd_from_object(object)!=NULL;
        md_fd_object_release(object);
        if (selected) signal_descriptor=1;
    }
    if (signal_descriptor && t->entered && t->phase==IDLE
            && regs.pc!=RAW_GATE && regs.pc!=abi.task_return) {
        int error=signal_control_begin(t,&regs,0,NULL,0,NULL);
        if (error) return_value(t,&regs,error);
        return;
    }
    if (t->signals.host && t->entered && t->phase==IDLE
            && (cookie==MD_INTERCEPT_SIGNAL || regs.regs[8]==SYS_rt_sigtimedwait)) {
        if (regs.pc==RAW_GATE || regs.pc==abi.task_return) { resume(pid,0); return; }
        /* Kernel admission at this application site is authoritative:
         * ERRNO/TRAP/KILL outrank our selector. Do not re-admit using a
         * policy which a concurrent TSYNC may have published later. */
        if (signal_native_call(t,regs.regs[8],regs.regs[1],regs.regs[2],regs.regs[3])) return;
        if (regs.regs[8]==SYS_rt_sigaction || regs.regs[8]==SYS_rt_sigpending) {
            int error=signal_control_begin(t,&regs,0,NULL,0,NULL);
            if (error) return_value(t,&regs,error);
            return;
        }
        if (regs.regs[8]==SYS_rt_sigtimedwait || regs.regs[8]==SYS_signalfd4
                || regs.regs[8]==SYS_read || regs.regs[8]==SYS_readv) {
            int error=signal_control_begin(t,&regs,0,NULL,0,NULL);
            if (error) return_value(t,&regs,error);
            return;
        } else if (regs.regs[8]==SYS_ppoll || regs.regs[8]==SYS_pselect6
                || regs.regs[8]==SYS_epoll_pwait || regs.regs[8]==SYS_epoll_pwait2 || regs.regs[8]==SYS_rt_sigsuspend)
            cookie=MD_INTERCEPT_DISPATCH;
        else cookie=md_seccomp_filter_call(regs.regs[8],regs.regs[0],regs.regs[1])
            ? MD_INTERCEPT_OBSERVE : MD_INTERCEPT_NATIVE;
    }
    if (t->entry.fence_result) {
        int result=t->entry.fence_result; t->entry.fence_result=0;
        return_value(t,&regs,result); return;
    }
    if (cookie==MD_INTERCEPT_TASK) {
        /* Only an already-owned wait may use the service gate when
         * the application's filter denies ppoll. The original call
         * has passed admission; a direct application ppoll has not. */
        if (regs.pc==abi.task_return && regs.regs[8]==SYS_ppoll
                && !regs.regs[0] && !regs.regs[1]
                && (task_parked(t) || t->phase==DEBUG_PARK_EXIT)) {
            resume(pid,0); return;
        }
        /* Wait with the application mask; retire the native exit before
         * allowing a handler to observe the adapter's stack or pointers.
         * Original accept admission owns accept4(...,0), while direct
         * accept4 calls retain their own application filter policy. */
        int substituted=t->execution.request.nr==SYS_accept && regs.regs[8]==SYS_accept4;
        if (t->phase==DISPATCHING && regs.pc==abi.socket_return
                && md_socket_wait_call(regs.regs[8],regs.regs[2],regs.regs[3])
                && (!substituted || (regs.regs[0]==t->execution.request.args[0]
                    && !regs.regs[3] && !!regs.regs[1]==!!t->execution.request.args[1]))) {
            if (!restore_mask(t)) return;
            t->phase=SOCKET_NATIVE; resume(pid,0);
        }
        else return_value(t,&regs,-EPERM);
        return;
    }
    if (entry_fence_admission(t,&regs,status,usage)) return;
    if (fd_duplicate_prepared(t,&regs,cookie)) {
        if (statistics) ++statistics->calls[regs.regs[8]].trace;
        fd_begin(t,&regs,MD_INTERCEPT_FD); return;
    }
    if (regs.regs[8]==SYS_ptrace && t->phase==IDLE)
        SUPERVISOR_TRACE("PROBE guest debugger caller=%d request=%llu target=%llu address=%#llx data=%#llx traced=%d\n",
            pid,regs.regs[0],regs.regs[1],regs.regs[2],regs.regs[3],
            md_debugger_traced(debugger,native_pid(t,(pid_t)regs.regs[1])));
    if (t->phase==IDLE && (regs.regs[8]==SYS_ptrace || regs.regs[8]==SYS_wait4
            || regs.regs[8]==SYS_waitid)
            && md_debugger_call(debugger,pid,&regs)) return;
    if (statistics) {
        if (regs.regs[8] < 512) statistics->calls[regs.regs[8]].trace++;
        else statistics->unknown++;
    }
    if (regs.regs[8]==SYS_prctl && regs.regs[0]==MD_GUEST_PID_SIGNAL_GROUP
            && regs.pc==RAW_GATE && t->phase==DISPATCHING) {
        long value=t->execution.request.nr!=SYS_kill || (pid_t)t->execution.request.args[0]>0
            || (pid_t)regs.regs[1]!=(pid_t)t->execution.request.args[0]
            || (int)regs.regs[2]!=(int)t->execution.request.args[1] ? -EINVAL
            : signal_process_next(t,(pid_t)regs.regs[1],(int)regs.regs[2],(pid_t)regs.regs[3]);
        return_value(t,&regs,value); return;
    }
    struct seccomp_data request=syscall_description(&regs);
    if (resource_call(t,&request,&regs,&cookie)) return;
    if (cookie == MD_INTERCEPT_NATIVE) {
        if (t->phase==IDLE && md_debugger_step(debugger,pid)) {
            t->debug.step_call=1; execution_capture(t,&regs); t->phase=OBSERVING;
        }
        if (diagnostics && statistics && t->phase == IDLE && regs.regs[8] < 512
                && statistics->calls[regs.regs[8]].trace <= 4) {
            SUPERVISOR_TRACE("PROBE native arguments pid=%d nr=%llu args=%#llx,%#llx,%#llx,%#llx,%#llx,%#llx\n",
                pid,regs.regs[8],regs.regs[0],regs.regs[1],regs.regs[2],regs.regs[3],regs.regs[4],regs.regs[5]);
            execution_capture(t,&regs); t->phase=OBSERVING;
        }
        resume(pid, 0); return;
    }
    if (cookie == MD_INTERCEPT_EXEC) {
        CHECK(t->phase == DISPATCHING);
        if (!restore_mask(t)) return;
        t->phase = EXECUTING; fd_begin(t,&regs,0); return;
    }
    if (cookie == MD_INTERCEPT_OBSERVE) {
        CHECK(t->ready && t->phase == IDLE);
        t->seccomp.length = describe_filter(pid, &regs);
        execution_capture(t,&regs); t->phase = OBSERVING;
        resume(pid, 0); return;
    }
    if (cookie==MD_INTERCEPT_READINESS) cookie=MD_INTERCEPT_DISPATCH;
    if (cookie != MD_INTERCEPT_DISPATCH || !t->ready || t->phase != IDLE) {
        int displaced=md_task_stop_displaced(checkpoints.trace,pid);
        CHECK(displaced>=0);
        if (displaced) { t->resume_lost=1; return; }
        unsigned long raw_cookie=0;
        struct ptrace_syscall_info raw_info={0};
        long raw_event=ptrace(PTRACE_GETEVENTMSG,pid,NULL,&raw_cookie);
        long raw_call=ptrace(PTRACE_GET_SYSCALL_INFO,pid,(void *)sizeof(raw_info),&raw_info);
        fprintf(stderr,"guest-runtime: unexpected TRACE pid=%d cookie=%lu ready=%d phase=%d pc=%#llx nr=%llu adapted=%d rawEvent=%ld/%lu rawCall=%ld/%u/%u/%llu pending=%u\n",
            pid, cookie, t->ready, t->phase, regs.pc, regs.regs[8], t->execution.request.nr,
            raw_event,raw_cookie,raw_call,raw_info.op,raw_info.seccomp.ret_data,
            (unsigned long long)raw_info.seccomp.nr,task_wait.pending_count);
    }
    CHECK(cookie == MD_INTERCEPT_DISPATCH && t->ready && t->phase == IDLE);
    execution_capture(t,&regs); calls++; t->calls++;
    t->debug.step_call=md_debugger_step(debugger,pid);
    if (regs.regs[8]==SYS_shmdt) { memory_begin(t,&regs,MEMORY_DETACH,0); return; }
    if (direct_begin(t)) return;
    if (receive_entry(t)) return;
    if (!save_signal_mask(t,&t->execution.mask,"get-mask")
            || !shield(t) || !skip(pid)) return;
    dispatch_with_stack(t);
}

void execution_syscall_event(struct thread *t,pid_t pid,int status,const struct rusage *usage) {
    if ((t->phase==SOCKET_NATIVE) && socket_native_exit(t,pid)) return;
    if (t->phase==SIGNAL_NATIVE_EXIT) {
        signal_native_complete(t);
        return;
    }
    if ((t->phase==EPOLL_PROBE) && readiness_probe_exit(t,pid)) return;
    if (((t->phase==CLONE_ENTRY) || (t->phase==CLONE_ADMISSION)) && task_clone_exit(t,pid)) return;
    if (t->machine.syscall_exit) {
        int owned=md_svc_machine_exit(&t->machine,pid);
        if (owned==-ESRCH) { t->resume_lost=1; return; }
        if (owned<0) errno=-owned;
        CHECK(owned==1);
        resume(pid,0); return;
    }
    if (t->machine.retry_svc) {
        uintptr_t suspended=t->machine.retry_svc;
        struct user_pt_regs observed;
        int owned=md_svc_machine_retry(&t->machine,pid,&observed);
        if (owned==-ESRCH) { t->resume_lost=1; return; }
        CHECK(owned>=0);
        if (owned) {
            machine_observe(t,&observed);
            int error=md_svc_registry_resume(md_memory_svc(t->memory),pid,suspended);
            if (error==-ESRCH) { t->resume_lost=1; return; }
            CHECK(!error);
            resume(pid,0); return;
        }
        for (struct thread *member=threads;member;member=member->next)
            if (member->pid && member->memory==t->memory)
                md_broker_resample(broker,member->pid,suspended+4);
    }
    if (t->phase==WAIT_CANCEL_EXIT) {
        t->phase=t->execution.wait_resume; t->execution.wait_resume=IDLE;
        if (t->phase==SOCKET_WAITING) { socket_wait_cancelled(t); return; }
        if (t->phase==IPC_WAITING) { dispatch_with_stack(t); return; }
    }
    if (((t->phase==RESULT_REPLAY_ENTRY) || (t->phase==RESULT_REPLAY_EXIT)) && fs_result_exit(t,pid)) return;
    if ((t->fs.direct==DIRECT_ADMIT) && fs_direct_exit(t,pid)) return;
    if (((t->phase==PROCESS_INIT_ENTRY) || (t->phase==PROCESS_INIT_EXIT) || (t->phase==PROCESS_OBSERVING)) && task_process_exit(t,pid)) return;
    if ((t->descriptors.running) && descriptors_native_exit(t,pid)) return;
    if (interrupted_operation(t)) return;
    if (((t->phase==DEBUG_WAIT_ENTRY) || (t->phase==DEBUG_WAIT_EXIT) || (t->phase==DEBUG_PARK_ENTRY) || (t->phase==DEBUG_PARK_EXIT)) && debug_wait_exit(t,pid)) return;
    if (t->phase==IDLE) {
        struct ptrace_syscall_info info;
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return;
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"syscall-exit")) return;
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
            if (t->debug.replay_entry) { t->debug.replay_entry=0; resume(pid,0); return; }
            t->debug.call_active=md_debugger_syscall_mode(debugger,pid);
            if (t->entered && t->image.process_image && md_debugger_syscall(debugger,pid,&regs,1)) return;
        } else {
            int pending=debug_host_policy_pending(t,&regs);
            if (pending<0) { CHECK(pending==-ESRCH); t->resume_lost=1; return; }
            if (pending) { resume(pid,0); return; }
            if (debug_syscall_exit(t,&regs)) return;
        }
        resume(pid,0); return;
    }
    if (t->phase == WATCH_CANCEL) {
        struct user_pt_regs regs = t->execution.registers; regs.pc = abi.watch_gate;
        if (!set_registers(pid, &regs)) return;
        t->phase = WATCH_ENTRY; resume(pid, 0); return;
    }
    if (t->phase == WATCH_ENTRY) {
        /* Restore before the blocking syscall, not after readiness. */
        if (!restore_mask(t)) return;
        t->phase = WATCH_READ; resume(pid, 0); return;
    }
    if (t->phase==CALL_RESTART_ENTRY) {
        SUPERVISOR_TRACE("PROBE call-restart-entry pid=%d\n",pid);
        /* SYSEMU skips before inherited seccomp. Replacing the number
         * with -1 at a normal entry can itself trigger Android SIGSYS. */
        t->phase=CALL_RESTART_EXIT; resume(pid,0); return;
    }
    if (t->phase==CALL_RESTART_EXIT) {
        SUPERVISOR_TRACE("PROBE call-restart-exit pid=%d nr=%d\n",pid,t->execution.request.nr);
        /* Give signal delivery the original syscall context, including
         * the kernel's orig_x0. Kernel SA_RESTART handling, not a guessed
         * userspace action table, decides whether to restart it.
         * Internal preparation uses ERESTARTNOINTR: handlers still run,
         * but its synthetic wait must not make mmap return EINTR. */
        struct user_pt_regs regs=t->execution.registers;
        regs.regs[0]=(uint64_t)(t->execution.restart==RESTART_PREPARATION ? -513 : -512);
        int nr=(int)t->execution.request.nr; struct iovec number={&nr,sizeof(nr)};
        if (!tracee_request(t,PTRACE_SETREGSET,(void *)NT_ARM_SYSTEM_CALL,&number,"call-restart-syscall")
                || !set_registers(pid,&regs) || !restore_mask(t)) return;
        /* The original interruption may have been consumed while
         * rebuilding this exit (notably SIGSTOP/SIGCONT). Rearm kernel
         * signal work so ERESTARTSYS cannot escape to the application.
         * EVENT_WAIT: the ordinary event loop owns the synthetic stop. */
        if (!tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"call-restart-delivery")) return;
        md_memory_stack_return(t->memory,t->execution.stack); t->execution.stack=0;
        t->phase=IDLE; resume(pid,0); return;
    }
    if (t->phase==FILE_WAIT_ENTRY || t->phase==CONTROL_WAIT_ENTRY || t->phase==CLOSE_WAIT_ENTRY) {
        int entered=wait_poll_entry(t);
        if (entered<=0) { if (!entered) resume(pid,0); return; }
    }
    if (((t->phase==FILE_WAIT_ENTRY) || (t->phase==FILE_WAITING)) && fs_wait_exit(t,pid)) return;
    if (((t->phase==CLOSE_WAIT_ENTRY) || (t->phase==CLOSE_WAITING)) && descriptors_close_exit(t,pid)) return;
    if (((t->phase==CONTROL_WAIT_ENTRY) || (t->phase==CONTROL_WAITING)) && control_wait_exit(t,pid)) return;
    if (((t->phase==SOCKET_WAIT_ENTRY) || (t->phase==SOCKET_WAITING)) && socket_wait_exit(t,pid)) return;
    if (((t->phase==IPC_WAIT_ENTRY) || (t->phase==IPC_WAITING)) && ipc_wait_exit(t,pid)) return;
    if (t->phase == WATCH_READ) {
        struct user_pt_regs returned;
        if (!registers(pid, &returned)) return;
        long value = (long)returned.regs[0];
        if (value == MD_WATCH_TASK_AFFINE || (t->watch_wait == 1 && value > 0)) {
            if (!shield(t)) return;
            t->watch_wait = 0;
            dispatch_with_stack(t); return;
        }
        struct user_pt_regs regs = t->execution.registers; regs.regs[0] = returned.regs[0];
        if (!set_registers(pid, &regs)) return;
        if (t->execution.stack) { md_memory_stack_return(t->memory, t->execution.stack); t->execution.stack = 0; }
        md_fs_worker_wake(filesystem);
        t->phase = IDLE; resume(pid, 0); return;
    }
    if (((t->phase == EXPORT_ENTRY) || (t->phase == EXPORT_RETURN || t->phase == EXPORT_NOTIFY)) && fs_export_exit(t,pid)) return;
    if (t->phase == OBSERVING) {
        struct user_pt_regs regs;
        if (!registers(pid, &regs)) return;
        if (md_clone_call(t->execution.request.nr)) {
            t->process_op.cloning = 0;
            if ((long)regs.regs[0]>0 && t->process_op.result) {
                regs.regs[0]=t->process_op.result;
                if (!set_registers(pid,&regs)) return;
            }
            t->process_op.result=0;
            if ((long)regs.regs[0]>0 && t->process_op.parent_address)
                pid_store_add(t,t->process_op.parent_address,(pid_t)regs.regs[0]);
            t->process_op.parent_address=0;
            if (t->process_op.clone_prepared) {
                CHECK(!buffer_cleanup(t)); t->process_op.clone_prepared=0;
                if (t->signals.shielded && !restore_mask(t)) return;
            }
        }
        if ((long)regs.regs[0] >= 0 && (t->execution.request.nr == SYS_munmap || t->execution.request.nr == SYS_mmap)) {
            size_t length = t->execution.request.args[1], page = (size_t)getpagesize();
            CHECK(length <= SIZE_MAX-(page-1));
            uintptr_t address=t->execution.request.nr==SYS_mmap ? regs.regs[0] : t->execution.request.args[0];
            CHECK(!md_shm_unmapped(t->shm_space,address,(length+page-1)&~(page-1),t->tgid,t->resources.pid_group));
        }
        SUPERVISOR_TRACE("PROBE native syscall pid=%d nr=%d arg0=%#llx arg1=%#llx result=%lld\n",
            pid, t->execution.request.nr, t->execution.request.args[0], t->execution.request.args[1], (long long)regs.regs[0]);
        if (t->seccomp.install) {
            struct filter_install *install=t->seccomp.install;
            if (install->virtual_listener) {
                install->native_completed=1;
                if ((long)regs.regs[0]!=0) { filter_install_fail(t,(int)regs.regs[0]); return; }
                CHECK(install->loan && install->loan->number>=0);
                regs.regs[0]=install->loan->number;
            }
            regs.regs[1]=t->execution.request.args[1];
            regs.regs[2]=t->execution.request.args[2];
            if (!set_registers(pid,&regs)) return;
        }
        if (filter_observed(t,&regs)) return;
        if (t->memory_op.action==MEMORY_KERNEL) {
            if (memory_capture_begin(t,&regs)) return;
            if (t->execution.request.nr==SYS_mprotect) {
                if ((long)regs.regs[0]==-EACCES) {
                    if (memory_repair(t,&regs,status,usage)) return;
                }
            } else md_memory_mapping_finish(t->memory,&t->memory_op.change,(long)regs.regs[0]);
            if (t->memory_op.files.table) CHECK(!md_fd_order_complete(&t->memory_op.files));
            CHECK(!md_resource_order_complete(&t->memory_op.order)); t->memory_op.action=0;
        }
        t->memory_op.arguments_only=0;
        if (t->descriptors.deferred_selector) { selector_deferred_complete(t,0); return; }
        t->phase = IDLE; resume(pid, 0); return;
    }
    if (t->phase!=EXECUTING) {
        struct user_pt_regs unexpected;
        if (registers(pid,&unexpected)) fprintf(stderr,"guest-runtime: unexpected syscall-stop pid=%d phase=%d pc=%#llx nr=%llu result=%lld\n",
            pid,t->phase,unexpected.pc,unexpected.regs[8],(long long)unexpected.regs[0]);
    }
    CHECK(t->phase == EXECUTING);
    if (!shield(t)) return;
    t->phase = DISPATCHING; resume(pid, 0);
}

void execution_checkpoint_event(struct thread *t,pid_t pid) {
    struct user_pt_regs regs;
    if (!registers(pid, &regs)) return;
    if (t->entry.execution && !t->entry.kernel_context) {
        if (!save_signal_mask(t,&t->execution.mask,"entry-kernel-context")) return;
        t->entry.kernel_context=1;
    }
    uintptr_t checkpoint=regs.pc-4;
    if (statistics) {
#define COUNT_CHECKPOINT(field,symbol) if (checkpoint==abi.field) ++statistics->checkpoints[CHECKPOINT_COUNT_##field];
        MD_CHECKPOINT_SITES(COUNT_CHECKPOINT)
#undef COUNT_CHECKPOINT
    }
    if (abi.export_control && checkpoint==abi.export_control && t->ready) {
        export_control(t,&regs); return;
    }
    if (abi.fd_control && checkpoint==abi.fd_control && t->ready) {
        struct supervisor_control_request control=control_description(&regs);
        fd_control(t,&control,&regs); return;
    }
    if (abi.buffer_control && checkpoint==abi.buffer_control && t->ready) {
        struct supervisor_control_request control=control_description(&regs);
        buffer_control(t,&control,&regs); return;
    }
    if (abi.task_control && checkpoint==abi.task_control && t->ready) {
        if (t->phase==DISPATCHING && (t->execution.request.nr==SYS_clone
                || t->execution.request.nr==SYS_clone3 || t->execution.request.nr==SYS_unshare)) {
            pid_filter_begin(t,&regs,NULL); return;
        }
        mapping_begin(t,&regs); return;
    }
    if (checkpoint==abi.enter && t->phase==IDLE && t->ready) {
        if (!t->entered) {
            int result=image_enter(t,regs.regs[2],regs.regs[3],regs.regs[4],regs.regs[5]);
            /* Both ordinary and secure images commit at this stop. */
            if (result) { return_value(t,&regs,result); return; }
        } else if (regs.regs[3] || regs.regs[4] || regs.regs[5]) {
            return_value(t,&regs,-EPERM); return;
        }
        if (t->image.backings_ready && !md_memory_svc(t->memory)) {
            struct md_svc_registry *registry=md_svc_machine_admit(pid,abi.svc_runtime);
            if (registry) {
                CHECK(!md_memory_svc_set(t->memory,registry));
                if (t->seccomp.application_filter) {
                    int number=md_seccomp_policy_entry_checkpoint(t->seccomp.policy);
                    CHECK(number>=0);
                    registry->checkpoint_number=(unsigned)number;
                    CHECK(!md_svc_registry_pause_filter(registry,pid));
                    registry->entry_fence_drained=1;
                }
                if (!t->terminal_observation) {
                    t->terminal_observation=1; t->trace_options_dirty=1;
                }
            }
        }
        t->image.process_image=regs.regs[2];
        uint64_t entry=regs.regs[0], stack=regs.regs[1];
        regs=md_task_registers_entry(entry,stack,regs.pstate);
        if (t->seccomp.application_filter && filter_entry_map(t,&regs)) return;
        if (!set_registers(pid,&regs)) return;
        if (!md_debugger_exec(debugger,pid,t->debug.exec_old ? t->debug.exec_old : pid)) resume(pid,0);
        return;
    }
    if (!t->ready && t->phase == IDLE && regs.regs[2] == MD_INTERCEPTION_MAGIC) {
        if (!t->resources.fs) CHECK(!md_resources_init(&t->resources,md_fs_worker_mounts(filesystem)));
        struct md_interception_abi received;
        struct iovec local = {&received, sizeof(received)}, remote = {(void *)regs.regs[3], sizeof(received)};
        CHECK(process_vm_readv(pid, &local, 1, &remote, 1, 0) == sizeof(received));
        CHECK(received.magic == MD_INTERCEPTION_MAGIC && received.size == sizeof(received)
            && received.ready == checkpoint && received.copy_end > received.copy_begin);
        CHECK(!abi.magic || !memcmp(&abi, &received, sizeof(abi)));
        abi = received;
        checkpoints.begin=abi.code_begin; checkpoints.end=abi.code_end;
    }
    if ((checkpoint == LOADED_BYTE && t->phase == DOMAIN_PATH) && domain_checkpoint(t,checkpoint,&regs)) return;
    if (((checkpoint==abi.loaded_groups && t->phase==PROCESS_INFO_LOAD)
            || (checkpoint==abi.stored_groups && (t->phase==PROCESS_INFO_STORE
                || t->phase==PROCESS_TID_STORE || t->phase==PROCESS_VALUE_STORE)))
            && task_checkpoint(t,pid,checkpoint,&regs)) return;
    if (((checkpoint==STORED_IDS && t->phase==STORING_IDS)
            || (t->phase==IDENTITY_COPY && (checkpoint==abi.loaded_groups || checkpoint==abi.stored_groups)))
            && identity_checkpoint(t,pid,checkpoint,&regs)) return;
    if (((checkpoint==EXPORTED && (t->phase==EXPORTING || t->phase==PROC_OPENING))
            || (checkpoint==LOADED_BYTE && t->phase==LOADING_PATH)
            || (checkpoint==STORED && t->phase==STORING))
            && fs_checkpoint(t,checkpoint,&regs)) return;
    if (checkpoint == READY && t->phase == IDLE) {
        memory_access_capture(t);
        CHECK(!read_string(pid, regs.regs[1], t->endpoint, sizeof(t->endpoint)));
        if (admitted_path && !admission.image) {
            int error = md_admission_open(&admission, t->endpoint, admitted_path);
            if (error) SUPERVISOR_TRACE("PROBE admission error=%d\n", error);
            CHECK(!error);
        }
        if ((int)regs.regs[0] >= 0) {
            CHECK(listener == -1);
            listener = duplicate_fd(pid, (int)regs.regs[0]); CHECK(listener >= 0);
            if (broker) md_broker_listen(broker, listener);
        }
        t->ready = 1;
        t->entry.registration_revoked=!!(t->seccomp.application_filter || t->entry.blocked);
        regs.regs[0]=t->entry.registration_revoked ? 0 : MD_INTERCEPTION_COOPERATIVE;
        if (broker && !t->entered && (!t->entry.registration_revoked
                || (t->seccomp.application_filter && md_seccomp_policy_entry_checkpoint(t->seccomp.policy)>=0))
                && !t->debug.options && !md_memory_exposed(t->memory))
            regs.regs[0]|=MD_INTERCEPTION_MACHINE;
        if (statistics) regs.regs[0]|=MD_INTERCEPTION_STATISTICS;
        if (md_app_uid_active) regs.regs[0]|=MD_INTERCEPTION_HOST_POLICY;
        if (!set_registers(pid, &regs)) return;
        describe_process(pid, 0);
        SUPERVISOR_TRACE("PROBE guest image ready pid=%d\n", pid); resume(pid, 0);
    } else if (checkpoint == ALLOCATED && t->phase == ALLOCATING) {
        CHECK((long)regs.regs[0] > 0 && !(regs.regs[0] & (md_page_size - 1)));
        t->execution.stack = regs.regs[0]; CHECK(!md_memory_stack_add(t->memory, t->execution.stack)); dispatch(t);
    } else if (checkpoint == DONE && t->phase == DISPATCHING) {
        dispatch_complete(t,(long)regs.regs[0]);
    } else {
        fprintf(stderr,"Shroot: unexpected checkpoint pid=%d pc=%#llx phase=%d\n",pid,regs.pc,t->phase);
        CHECK(0);
    }
}
