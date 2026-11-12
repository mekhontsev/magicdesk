#define _GNU_SOURCE
#include "process_owner.h"
#include "signal_delivery.h"
#include "seccomp_policy.h"
#include "seccomp_capture.h"
#include "seccomp_reply.h"
#include "seccomp_install.h"
#include "interception_memory.h"
#include "signal_protocol.h"
#include "signal_view.h"
#include "signal_actions.h"
#include "task_memory.h"
#include "task_call.h"
#include "task_copy.h"
#include "interception.h"
#include "guest_debugger.h"
#include "supervisor_request.h"
#include "trace_memory.h"
#include <asm/ptrace.h>
#include <assert.h>
#include <elf.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/audit.h>
#include <linux/ptrace.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <sched.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <setjmp.h>

#ifndef PTRACE_SYSEMU
#define PTRACE_SYSEMU 31
#endif

/* The fixture drives shared owners at exact kernel boundaries. Production
 * integration must use its existing event loop, not this bounded driver. */
enum { TASKS = 8, EMULATED = 617, TRAPPED = 711 };
#define SYSBIT (UINT64_C(1) << (SIGSYS - 1))
struct shared_state {
    _Atomic unsigned startup;
    struct md_kernel_action scratch[TASKS];
    struct md_kernel_action old_scratch[TASKS];
    _Atomic unsigned reset_gate;
    _Atomic unsigned policy_gate;
    struct { struct iovec local, remote; unsigned char bytes[32768]; } copy[TASKS];
};
static struct shared_state *shared;
static struct md_interception_buffers *images;
static void *image_view;
static int traced, scenario;
static int trace_scenario(void) { return (scenario >= 49 && scenario <= 53) || scenario == 58 || scenario == 61 || scenario == 62; }
static unsigned long trace_options(void) {
    return PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_EXITKILL
        | (!traced && scenario == 53 ? 0 : PTRACE_O_TRACESECCOMP);
}
static void reset_entry(void) {
    if (scenario != 21) return;
    atomic_store(&shared->reset_gate, 1);
    syscall(SYS_futex, &shared->reset_gate, FUTEX_WAKE, 1, NULL, NULL, 0);
    /* EVENT_WAIT: sibling inspects the kernel-reset action; suite alarm bounds failure. */
    while (atomic_load(&shared->reset_gate) == 1)
        syscall(SYS_futex, &shared->reset_gate, FUTEX_WAIT, 1, NULL, NULL, 0);
}
extern const char shadow_denied_svc[];
extern const char shadow_entry[];
extern const char shadow_entry_return[];
extern const char shadow_gate[], shadow_gate_return[];
__asm__(".text\n.balign 4\n.global shadow_gate\nshadow_gate:\nsvc #0\n"
    ".global shadow_gate_return\nshadow_gate_return:\nb .\n");
/* SYSEMU stops before admission, so this entry need not borrow a syscall
 * number or clobber a register. The skipped exit restores ARM64's hidden x7. */
__asm__(".text\n.balign 4\n.global shadow_entry\nshadow_entry:\nsvc #0\n"
    ".global shadow_entry_return\nshadow_entry_return:\nb .\n");
enum { NATIVE, MASK, ACTION, PENDING, RETURN, POLICY_ERRNO, INSTALL, HANDLER_ENTRY };
struct task {
    pid_t pid, tgid;
    unsigned slot;
    int phase, action_signal;
    int waiting_action, waiting_signal, held_exit;
    int action_setting, blocked, entry_signal;
    long result;
    uint64_t previous_mask;
    struct md_kernel_action requested_action, entry_action;
    struct user_pt_regs original;
    struct md_signal_delivery pending;
    struct md_signal_actions *actions;
    struct md_seccomp_policy *policy;
    struct md_seccomp_capture capture;
    struct md_interception_memory *memory;
    struct md_resource_order turn;
    int forcing;
    int entering;
    int notification, continuing, ioctl;
    struct md_seccomp_replies replies;
    struct md_seccomp_reply *reply;
    int receiver_held;
    uint64_t notification_mask;
    struct user_pt_regs notified;
    struct seccomp_data notified_data;
    struct user_pt_regs ioctl_original;
    struct md_interception_buffer ioctl_input;
    uint64_t entry_mask;
    uint64_t forced_mask;
    siginfo_t forced_info;
    struct user_pt_regs forced_registers;
};
struct tracer {
    struct task tasks[TASKS];
    struct { pid_t pid; int status; } newborn[TASKS];
    struct md_signal_actions *actions;
    unsigned controls, denied, signals, exits;
    unsigned read_entries;
    unsigned action_queued;
    pid_t admission_owner;
    pid_t leader;
    unsigned installs;
    unsigned held_conflicts;
    unsigned entry_injected, return_injected;
    struct md_debugger *debugger;
};
static struct md_kernel_action action_at(const struct task *t,int signal) {
    struct md_kernel_action action;
    assert(!md_signal_actions_read(t->actions,signal,&action));
    return action;
}
static struct tracer *debug_owner;
static int debug_mask(pid_t, int, uint64_t *);
static int debug_parent(pid_t pid) { (void)pid; return getpid(); }
static int debug_group(pid_t pid) { return pid; }
static uid_t debug_uid(pid_t pid) { (void)pid; return getuid(); }
static int debug_allowed(pid_t a, pid_t b) { (void)a; (void)b; return 1; }
static int debug_observe(pid_t pid) { (void)pid; return 0; }
static int debug_options(pid_t pid, unsigned long options) { (void)pid; (void)options; return 0; }
static void debug_usage(pid_t pid, struct rusage *usage) { (void)pid; *usage = (struct rusage){0}; }
static long debug_result;
static void debug_complete(pid_t pid,long result) { (void)pid; debug_result=result; }
static unsigned debug_resumed;
static void debug_resume(pid_t pid,int signal,const struct md_signal_origin *origin) {
    (void)pid; assert(!signal && (!origin || !origin->valid)); ++debug_resumed;
}
static long debug_call(struct md_debugger *d, unsigned op, pid_t target, uintptr_t address, uintptr_t data) {
    struct user_pt_regs r = {.regs = {op, target, address, data}}; r.regs[8] = SYS_ptrace;
    assert(md_debugger_call(d,getpid(),syscall_description(&r))); return debug_result;
}
static long debug_request(struct md_debugger *d, unsigned op, pid_t target, uintptr_t address, void *data) {
    if (d) return debug_call(d, op, target, address, (uintptr_t)data);
    long result = ptrace(op, target, (void *)address, data); return result < 0 ? -errno : result;
}
static void debug_inspect(struct md_debugger *d, pid_t pid, struct user_pt_regs *r, unsigned cookie) {
    if (d) {
        assert(md_debugger_seccomp(d, pid, r, cookie));
        int status = 0;
        struct user_pt_regs wait = {.regs = {pid, (uintptr_t)&status, WNOHANG | __WALL}};
        wait.regs[8] = SYS_wait4;
        assert(md_debugger_call(d,getpid(),syscall_description(&wait)) && debug_result == pid);
        assert((unsigned)status >> 16 == PTRACE_EVENT_SECCOMP && WSTOPSIG(status) == SIGTRAP);
    }
    unsigned long message = 0;
    assert(!debug_request(d, PTRACE_GETEVENTMSG, pid, 0, &message) && message == cookie);
    struct ptrace_syscall_info info = {0};
    long size = debug_request(d, PTRACE_GET_SYSCALL_INFO, pid, sizeof(info), &info);
    assert(size == (long)(offsetof(struct ptrace_syscall_info, seccomp.ret_data) + sizeof(info.seccomp.ret_data)));
    assert(info.op == PTRACE_SYSCALL_INFO_SECCOMP && info.seccomp.ret_data == cookie
        && info.seccomp.nr == r->regs[8] && info.instruction_pointer == r->pc
        && info.stack_pointer == r->sp && !memcmp(info.seccomp.args, r->regs, sizeof(info.seccomp.args)));
    siginfo_t signal; assert(!debug_request(d, PTRACE_GETSIGINFO, pid, 0, &signal));
    assert(signal.si_signo == SIGTRAP && signal.si_code == (SIGTRAP | (PTRACE_EVENT_SECCOMP << 8)));
    uint64_t mask;
    assert(!debug_request(d, PTRACE_GETSIGMASK, pid, sizeof(mask), &mask) && (mask & SYSBIT));
    uint64_t changed = mask & ~SYSBIT;
    assert(!debug_request(d, PTRACE_SETSIGMASK, pid, sizeof(changed), &changed));
    assert(!debug_request(d, PTRACE_GETSIGMASK, pid, sizeof(changed), &changed) && !(changed & SYSBIT));
    assert(!debug_request(d, PTRACE_SETSIGMASK, pid, sizeof(mask), &mask));
    struct user_pt_regs got; struct iovec span = {&got, sizeof(got)};
    assert(!debug_request(d, PTRACE_GETREGSET, pid, NT_PRSTATUS, &span));
    assert(!memcmp(&got, r, sizeof(got)));
    if (scenario == 50) { r->regs[8] = -1; r->regs[0] = 444; }
    if (scenario == 51) r->regs[8] = SYS_getppid;
    if (scenario == 52) r->regs[1] = 21;
    if (scenario == 58) r->regs[0] = 21;
    if (scenario == 61) r->regs[1] = 24;
    span = (struct iovec){r, sizeof(*r)};
    assert(!debug_request(d, PTRACE_SETREGSET, pid, NT_PRSTATUS, &span));
    int number = r->regs[8]; span = (struct iovec){&number, sizeof(number)};
    assert(!debug_request(d, PTRACE_SETREGSET, pid, NT_ARM_SYSTEM_CALL, &span));
    if (d) assert(!debug_call(d, PTRACE_CONT, pid, 0, 0));
}
static struct task *find(struct tracer *owner, pid_t pid) {
    for (unsigned i = 0; i < TASKS; ++i) if (owner->tasks[i].pid == pid) return &owner->tasks[i];
    return NULL;
}
static struct task *add(struct tracer *owner, pid_t pid, unsigned blocked, struct task *parent) {
    for (unsigned i = 0; i < TASKS; ++i) if (!owner->tasks[i].pid) {
        owner->tasks[i] = (struct task){.pid = pid, .slot = i, .blocked = blocked,
            .tgid = parent && (parent->original.regs[0]&CLONE_THREAD) ? parent->tgid : pid,
            .policy = md_seccomp_policy_retain(parent ? parent->policy : NULL),
            .memory = parent ? md_memory_fork(parent->memory, !!(parent->original.regs[0]&CLONE_VM)) : md_memory_new(),
            .actions = md_signal_actions_fork(parent ? parent->actions : owner->actions,
                !parent || !!(parent->original.regs[0]&CLONE_SIGHAND),0)};
        assert(owner->tasks[i].memory && owner->tasks[i].actions);
        return &owner->tasks[i];
    }
    abort();
}
static void retire_turn(struct task *t) {
    enum md_resource_order_state state = md_resource_order_state(&t->turn);
    if (state == MD_RESOURCE_ORDER_IDLE) return;
    assert(!(state == MD_RESOURCE_ORDER_QUEUED || state == MD_RESOURCE_ORDER_READY
        ? md_resource_order_cancel(&t->turn) : md_resource_order_complete(&t->turn)));
}
static void replace_policy(struct task *t, struct md_seccomp_policy *policy) {
    md_seccomp_policy_retain(policy); md_seccomp_policy_release(t->policy); t->policy = policy;
}
static uint32_t evaluate(struct task *t, const struct user_pt_regs *r) {
    struct seccomp_data call = {.nr = r->regs[8], .arch = AUDIT_ARCH_AARCH64, .instruction_pointer = r->pc};
    memcpy(call.args, r->regs, sizeof(call.args));
    uint32_t result; assert(!md_seccomp_policy_eval(t->policy, &call, &result)); return result;
}
static uint64_t host_mask(pid_t pid) {
    uint64_t mask;
    assert(!ptrace(PTRACE_GETSIGMASK, pid, (void *)sizeof(mask), &mask)); return mask;
}
static void set_host_mask(pid_t pid, uint64_t mask) {
    assert(!ptrace(PTRACE_SETSIGMASK, pid, (void *)sizeof(mask), &mask));
}
static void cancel_call(struct task *t, struct user_pt_regs *r) {
    int number = -1; struct iovec io = {&number, sizeof(number)};
    assert(!ptrace(PTRACE_SETREGSET, t->pid, (void *)NT_ARM_SYSTEM_CALL, &io));
    set_registers(t->pid, r);
}
static int user_copy(pid_t pid, uintptr_t address, void *data, size_t size, int write) {
    ssize_t count = md_task_copy_direct(pid, address, data, size, write);
    if (count == -EPERM || count == -EACCES) return (int)count;
    assert(count >= 0 || count == -EFAULT);
    return count == (ssize_t)size ? 0 : -EFAULT;
}
static long native_trace(int request, pid_t pid, void *address, void *data) {
    return ptrace(request, pid, address, data);
}
static int task_copy(struct task *t, uintptr_t address, void *data, size_t size, int write) {
    int result = user_copy(t->pid, address, data, size, write);
    if (result != -EPERM && result != -EACCES) return result;
    assert(size <= sizeof(shared->copy[t->slot].bytes));
    __typeof__(shared->copy[0]) *copy = &shared->copy[t->slot];
    struct ptrace_syscall_info info = {0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO, t->pid, (void *)sizeof(info), &info) >= 0);
    assert(info.op == PTRACE_SYSCALL_INFO_SECCOMP || info.op == PTRACE_SYSCALL_INFO_EXIT
        || (info.op == PTRACE_SYSCALL_INFO_ENTRY && t->entering));
    struct user_pt_regs saved = registers(t->pid);
    struct md_task_copy call = {0};
    enum md_task_call_boundary boundary = info.op == PTRACE_SYSCALL_INFO_ENTRY
        ? MD_TASK_CALL_EMULATED_ENTRY : (enum md_task_call_boundary)info.op;
    assert(!md_task_copy_begin(&call, native_trace, t->pid, boundary,
        &saved, (uintptr_t)shadow_gate, (uintptr_t)shadow_gate_return,
        address, data, size, write, copy, (uintptr_t)copy, sizeof(*copy)));
    /* Fixture driver only: the production native-call owner is asynchronous.
     * This exact actor runs kernel fault-aware self-copy; no forced proc write
     * or synthetic SIGSEGV changes application protection or signal state. */
    for (;;) {
        int status;
        /* EVENT_WAIT: native-call completion; the fixture alarm bounds failure. */
        assert(waitpid(t->pid, &status, __WALL) == t->pid);
        int ready = md_task_copy_event(&call, status); assert(ready >= 0);
        if (ready) break;
    }
    assert(call.result >= 0 || call.result == -EFAULT);
    return call.result == (long)size ? 0 : -EFAULT;
}
static struct md_kernel_action logical_action(struct task *t,
        struct md_kernel_action native) {
    return md_signal_actions_decode(t->actions,t->action_signal,native,(uintptr_t)shadow_entry);
}
static void publish(struct task *t) {
    if (t->blocked) return;
    if (action_at(t, SIGSYS).handler == (uintptr_t)SIG_IGN) md_signal_delivery_clear(&t->pending);
    else assert(!md_signal_delivery_publish(&t->pending, t->pid, t->tgid));
}
static int debug_mask(pid_t pid, int write, uint64_t *mask) {
    struct task *t = find(debug_owner, pid); assert(t);
    if (write) {
        t->blocked = !!(*mask & SYSBIT);
        set_host_mask(pid, *mask & md_signal_blockable_mask() & ~SYSBIT);
        publish(t);
    } else *mask = md_signal_view_mask(host_mask(pid), t->blocked);
    return 0;
}
static void notification_finish(struct task *t) {
    assert(md_seccomp_reply_result(&t->replies, t->result, &t->result, &t->continuing) >= 0);
    md_seccomp_replies_clear(&t->replies);
    struct user_pt_regs r = t->notified;
    r.regs[0] = t->continuing ? EMULATED : t->result;
    t->notification = t->continuing = t->receiver_held = 0;
    set_host_mask(t->pid, t->notification_mask);
    set_registers(t->pid, &r);
    assert(!ptrace(PTRACE_CONT, t->pid, NULL, NULL));
}
static int notification_ioctl(struct tracer *owner, struct task *t) {
    struct user_pt_regs r = registers(t->pid);
    if (r.regs[8] != SYS_ioctl || (r.regs[1] != SECCOMP_IOCTL_NOTIF_RECV
            && r.regs[1] != SECCOMP_IOCTL_NOTIF_SEND)) return 0;
    t->ioctl_original = r; t->ioctl = 1;
    if (r.regs[1] == SECCOMP_IOCTL_NOTIF_SEND) {
        struct seccomp_notif_resp response;
        if (!task_copy(t, r.regs[2], &response, sizeof(response), 0)) {
            struct seccomp_notif_resp native=response;
            for (unsigned i = 0; i < TASKS; ++i) {
                struct task *target = &owner->tasks[i];
                if (target->notification && target->replies.id == response.id) {
                    assert(md_seccomp_reply_prepare(&target->replies, &response, &native, &t->reply) >= 0);
                    break;
                }
            }
            assert(!md_buffers_take(images, &t->ioctl_input));
            struct md_buffer_span span; assert(!md_buffer_span(&t->ioctl_input, &span));
            memcpy(span.data, &native, sizeof(native));
            if (scenario == 66) {
                response.val=999;
                assert(!task_copy(t, r.regs[2], &response, sizeof(response), 1));
            }
            r.regs[2] = (uintptr_t)image_view + span.offset;
            set_registers(t->pid, &r);
        }
    }
    /* No address-space turn is held while a real kernel listener waits. */
    assert(!ptrace(PTRACE_SYSCALL, t->pid, NULL, NULL)); return 1;
}
static void notification_ioctl_complete(struct tracer *owner, struct task *t) {
    struct user_pt_regs r = registers(t->pid);
    long result = r.regs[0]; r = t->ioctl_original; r.regs[0] = result;
    if (!result && r.regs[1] == SECCOMP_IOCTL_NOTIF_RECV) {
        struct seccomp_notif request;
        assert(!task_copy(t, r.regs[2], &request, sizeof(request), 0));
        struct task *target = find(owner, request.pid);
        if (target && target->notification) {
            assert(request.data.nr == SYS_getpid
                && request.data.instruction_pointer == (uintptr_t)shadow_gate_return);
            target->replies.id = request.id;
            request.data = target->notified_data;
            assert(!task_copy(t, r.regs[2], &request, sizeof(request), 1));
        }
    }
    md_seccomp_reply_complete(t->reply, 1, result); t->reply=NULL;
    md_buffer_release(&t->ioctl_input); t->ioctl = 0;
    set_registers(t->pid, &r); assert(!ptrace(PTRACE_CONT, t->pid, NULL, NULL));
}
static void control(struct tracer *owner, struct task *t) {
    struct user_pt_regs r = registers(t->pid); t->original = r;
    int installing = md_seccomp_filter_call(r.regs[8], r.regs[0], r.regs[1]);
    /* All selected controls can observe policy or signal state. A TSYNC native
     * commit must be published before any sibling consumes either state. These
     * are nonblocking controls; ordinary IO never acquires this turn. */
    if (md_resource_order_state(&t->turn) == MD_RESOURCE_ORDER_IDLE) {
        int ready = md_memory_order_begin(t->memory, &t->turn, NULL); assert(ready >= 0);
        if (!ready) { t->waiting_action = 1; ++owner->action_queued; return; }
    }
    assert(!md_resource_order_start(&t->turn));
    assert(!owner->admission_owner); owner->admission_owner = t->pid;
    ++owner->controls;
    if (t->policy) {
        uint32_t result = evaluate(t, &r);
        if ((result & SECCOMP_RET_ACTION_FULL) == SECCOMP_RET_ERRNO) {
            t->result = md_seccomp_policy_error(result);
            t->phase = POLICY_ERRNO; cancel_call(t, &r);
            assert(!ptrace(PTRACE_SYSCALL, t->pid, NULL, NULL)); return;
        }
        /* Other denial actions need their own delivery owner; this fixture
         * deliberately admits only the tested ALLOW/ERRNO delegation. */
        assert((result & SECCOMP_RET_ACTION_FULL) == SECCOMP_RET_ALLOW);
    }
    if (r.regs[8] == SYS_ioctl) {
        owner->admission_owner = 0; retire_turn(t);
        if (notification_ioctl(owner, t)) return;
        t->phase = NATIVE; assert(!ptrace(PTRACE_SYSCALL, t->pid, NULL, NULL)); return;
    }
    if (installing) {
        struct sock_fprog program; assert(!task_copy(t, r.regs[2], &program, sizeof(program), 0));
        assert(program.len && program.len <= BPF_MAXINSNS);
        struct sock_filter code[BPF_MAXINSNS];
        assert(!task_copy(t, (uintptr_t)program.filter, code, program.len * sizeof(*code), 0));
        program.filter = code;
        const uint32_t delegated[] = {SYS_seccomp, SYS_prctl, SYS_rt_sigaction, SYS_rt_sigprocmask,
            SYS_rt_sigpending, SYS_rt_sigreturn, SYS_process_vm_readv, SYS_process_vm_writev, SYS_ioctl};
        const struct md_seccomp_route notification = {
            .number = SYS_getpid, .instruction_pointer = (uintptr_t)shadow_gate_return};
        assert(!md_seccomp_capture_prepare(&t->capture, images, (uintptr_t)image_view,
            t->policy, &program, delegated, sizeof(delegated) / sizeof(*delegated), MD_INTERCEPT_TASK,
            scenario >= 38 ? &notification : NULL));
        r.regs[2] = t->capture.program; t->phase = INSTALL;
        set_registers(t->pid, &r);
        assert(!ptrace(PTRACE_SYSCALL, t->pid, NULL, NULL)); return;
    }
    switch (r.regs[8]) {
    case SYS_rt_sigprocmask: {
        t->phase = MASK; t->result = 0;
        uint64_t mask = host_mask(t->pid);
        assert(!(mask & SYSBIT));
        t->previous_mask = md_signal_view_mask(mask, t->blocked);
        if (r.regs[3] != 8) t->result = -EINVAL;
        else if (r.regs[1]) {
            uint64_t requested;
            t->result = task_copy(t, r.regs[1], &requested, 8, 0);
            if (t->result) { cancel_call(t, &r); break; }
            uint64_t next;
            t->result = md_signal_view_update(t->previous_mask, (int)r.regs[0], requested, &next);
            if (!t->result) {
                t->blocked = !!(next & SYSBIT);
                set_host_mask(t->pid, next & ~SYSBIT);
            }
        }
        cancel_call(t, &r); break;
    }
    case SYS_rt_sigaction: {
        t->phase = ACTION; t->action_signal = (int)r.regs[0]; t->action_setting = 0;
        struct md_kernel_action requested;
        t->result = r.regs[3] != 8 ? -EINVAL : r.regs[1]
            ? task_copy(t, r.regs[1], &requested, sizeof(requested), 0) : 0;
        if (!t->result && (t->action_signal <= 0 || t->action_signal >= NSIG
                || (r.regs[1] && (t->action_signal == SIGKILL || t->action_signal == SIGSTOP))))
            t->result = -EINVAL;
        if (t->result) { t->phase = POLICY_ERRNO; cancel_call(t, &r); break; }
        if (r.regs[1]) {
            t->requested_action = requested;
            struct md_kernel_action native = md_signal_view_native(t->action_signal, requested,
                (uintptr_t)shadow_entry);
            shared->scratch[t->slot] = native;
            r.regs[1] = (uintptr_t)&shared->scratch[t->slot];
            t->action_setting = 1;
        }
        r.regs[2] = r.regs[2] ? (uintptr_t)&shared->old_scratch[t->slot] : 0;
        set_registers(t->pid, &r); break;
    }
    case SYS_rt_sigpending:
        t->phase = PENDING;
        r.regs[0] = (uintptr_t)&shared->old_scratch[t->slot];
        set_registers(t->pid, &r); break;
    case SYS_rt_sigreturn:
        t->phase = RETURN;
        if (scenario == 60 && !owner->return_injected++) assert(!syscall(SYS_tkill, t->pid, SIGSYS));
        break;
    default: t->phase = NATIVE; break;
    }
    assert(!ptrace(PTRACE_SYSCALL, t->pid, NULL, NULL));
}
static void complete(struct tracer *owner, struct task *t) {
    struct ptrace_syscall_info info = {0};
    assert(ptrace(PTRACE_GET_SYSCALL_INFO, t->pid, (void *)sizeof(info), &info) >= 0);
    assert(info.op == PTRACE_SYSCALL_INFO_EXIT);
    struct user_pt_regs r = registers(t->pid);
    if (t->phase == HANDLER_ENTRY) {
        assert(t->entering);
        r.pc = t->entry_action.handler; r.regs[0] = t->entry_signal;
        set_registers(t->pid, &r);
        set_host_mask(t->pid, t->entry_mask); t->entering = 0;
    } else if (t->phase == RETURN) {
        uint64_t mask = host_mask(t->pid);
        t->blocked = !!(mask & SYSBIT);
        set_host_mask(t->pid, mask & ~SYSBIT);
    } else if (t->phase == INSTALL) {
        unsigned flags = md_seccomp_filter_flags(t->original.regs[8], t->original.regs[1]);
        struct md_seccomp_policy *committed = NULL;
        int published = md_seccomp_capture_complete(&t->capture, flags, (long)r.regs[0], &committed);
        assert(published >= 0);
        if (published) {
            ++owner->installs; replace_policy(t, committed);
            if (flags & SECCOMP_FILTER_FLAG_TSYNC)
                for (unsigned i = 0; i < TASKS; ++i)
                    if (owner->tasks[i].pid && owner->tasks[i].tgid==t->tgid)
                        replace_policy(&owner->tasks[i], committed);
        }
        md_seccomp_policy_release(committed);
        unsigned long result = r.regs[0]; r = t->original; r.regs[0] = result;
        set_registers(t->pid, &r);
    } else if (t->phase == MASK || t->phase == POLICY_ERRNO) {
        if (t->phase == MASK && !t->result && t->original.regs[2])
            t->result = task_copy(t, t->original.regs[2], &t->previous_mask, 8, 1);
        r.regs[0] = t->result; set_registers(t->pid, &r);
    } else if (t->phase == ACTION && !(long)r.regs[0]) {
        if (t->original.regs[2]) {
            struct md_kernel_action old = logical_action(t, shared->old_scratch[t->slot]);
            r.regs[0] = task_copy(t, t->original.regs[2], &old, sizeof(old), 1);
        }
        /* copy_to_user failure does not undo the kernel's action exchange. */
        if (t->action_setting) {
            assert(!md_signal_actions_commit(t->actions,t->action_signal,&t->requested_action));
            if (t->action_signal == SIGSYS && action_at(t, SIGSYS).handler == (uintptr_t)SIG_IGN)
                for (unsigned i = 0; i < TASKS; ++i)
                    if (owner->tasks[i].tgid==t->tgid) md_signal_delivery_clear(&owner->tasks[i].pending);
        }
        set_registers(t->pid, &r);
    } else if (t->phase == PENDING && !(long)r.regs[0]) {
        uint64_t mask; memcpy(&mask, &shared->old_scratch[t->slot], sizeof(mask));
        if (md_signal_delivery_pending(&t->pending)) mask |= SYSBIT;
        assert(t->original.regs[1] <= sizeof(mask));
        r.regs[0] = task_copy(t, t->original.regs[0], &mask, t->original.regs[1], 1);
    }
    if (t->phase == ACTION || t->phase == PENDING) {
        unsigned long result = r.regs[0]; r = t->original; r.regs[0] = result;
        set_registers(t->pid, &r);
    }
    if (owner->admission_owner == t->pid) owner->admission_owner = 0;
    retire_turn(t);
    t->phase = NATIVE;
    publish(t);
    assert(!ptrace(PTRACE_CONT, t->pid, NULL, NULL));
}
static void release_held_install(struct tracer *owner) {
    for (unsigned i = 0; i < TASKS; ++i) {
        struct task *t = &owner->tasks[i];
        if (t->pid && t->held_exit) {
            t->held_exit = 0; ++owner->held_conflicts; complete(owner, t); return;
        }
    }
}
static int supervise(pid_t leader) {
    struct tracer owner = {.leader = leader};
    owner.actions=md_signal_actions_new(); assert(owner.actions);
    for (int signal=1;signal<NSIG;++signal) {
        if (signal==SIGKILL || signal==SIGSTOP) continue;
        struct md_kernel_action inherited;
        assert(!syscall(SYS_rt_sigaction,signal,NULL,&inherited,8));
        assert(!md_signal_actions_commit(owner.actions,signal,&inherited));
    }
    if (traced && trace_scenario()) {
        debug_owner = &owner;
        const struct md_debugger_host host = {.trace = native_trace, .seccomp_events = 1, .signal_mask = debug_mask,
            .parent = debug_parent, .group = debug_group, .uid = debug_uid,
            .allowed = debug_allowed, .traceme = debug_allowed, .attachable = debug_allowed, .observe = debug_observe,
            .options = debug_options, .usage = debug_usage, .complete = debug_complete, .resume = debug_resume};
        owner.debugger = md_debugger_create(&host); assert(owner.debugger);
        assert(!debug_call(owner.debugger, PTRACE_SEIZE, leader, 0,
            scenario == 53 ? 0 : PTRACE_O_TRACESECCOMP));
    }
    add(&owner, leader, 0, NULL);
    int living = 1, leader_status = -1;
    while (living) {
        int status; pid_t pid = 0;
        if (!owner.admission_owner) {
            for (unsigned i = 0; i < TASKS; ++i) {
                struct task *ready = &owner.tasks[i];
                if (ready->pid && ready->waiting_signal
                        && md_resource_order_state(&ready->turn) != MD_RESOURCE_ORDER_QUEUED) {
                    pid = ready->pid; status = (ready->waiting_signal << 8) | 0x7f;
                    ready->waiting_signal = 0; break;
                }
                if (ready->pid && ready->waiting_action && md_resource_order_state(&ready->turn) == MD_RESOURCE_ORDER_READY) {
                    ready->waiting_action = 0; control(&owner, ready); break;
                }
            }
        }
        if (!pid) for (unsigned i = 0; i < TASKS; ++i)
            if (owner.newborn[i].pid && find(&owner, owner.newborn[i].pid)) {
                pid = owner.newborn[i].pid; status = owner.newborn[i].status;
                owner.newborn[i].pid = 0; break;
            }
        if (!pid) pid = waitpid(-1, &status, __WALL);
        assert(pid > 0);
        struct task *t = find(&owner, pid);
        if (!t) {
            printf("SHADOW_EARLY pid=%d status=%#x\n", pid, status);
            assert(WIFSTOPPED(status) && (WSTOPSIG(status) == SIGSTOP
                || ((unsigned)status >> 16) == PTRACE_EVENT_STOP));
            unsigned i;
            for (i = 0; i < TASKS; ++i) if (!owner.newborn[i].pid) break;
            assert(i < TASKS); owner.newborn[i].pid = pid; owner.newborn[i].status = status;
            continue;
        }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            if (pid == leader) leader_status = status;
            else assert(WIFEXITED(status) && !WEXITSTATUS(status));
            md_signal_delivery_clear(&t->pending);
            md_signal_actions_release(t->actions); t->actions=NULL;
            md_seccomp_reply_complete(t->reply, 0, 0); t->reply=NULL;
            md_buffer_release(&t->ioctl_input);
            md_seccomp_replies_clear(&t->replies);
            for (unsigned i=0;i<TASKS;++i)
                if (owner.tasks[i].receiver_held) notification_finish(&owner.tasks[i]);
            md_seccomp_capture_discard(&t->capture); md_seccomp_policy_release(t->policy);
            retire_turn(t); md_memory_release(t->memory);
            if (owner.admission_owner == pid) owner.admission_owner = 0;
            t->pid = 0; --living; continue;
        }
        assert(WIFSTOPPED(status));
        unsigned event = (unsigned)status >> 16;
        int signal = WSTOPSIG(status);
        if (event == PTRACE_EVENT_CLONE || event == PTRACE_EVENT_FORK) {
            unsigned long next; assert(!ptrace(PTRACE_GETEVENTMSG, pid, NULL, &next));
            add(&owner, next, t->blocked, t); ++living;
            if (traced) {
                retire_turn(t);
                if (owner.admission_owner == pid) owner.admission_owner = 0;
                assert(!ptrace(PTRACE_SYSCALL, pid, NULL, NULL)); continue;
            }
            signal = 0;
        } else if (event == PTRACE_EVENT_SECCOMP) {
            unsigned long cookie; assert(!ptrace(PTRACE_GETEVENTMSG, pid, NULL, &cookie));
            if (!traced && trace_scenario()) {
                struct user_pt_regs r = registers(pid);
                debug_inspect(NULL, pid, &r, cookie);
                assert(!ptrace(PTRACE_CONT, pid, NULL, NULL)); continue;
            }
            if (cookie == 102) {
                /* Queue at the exact empty-pipe read admission. The kernel
                 * itself decides EINTR/restart; no timing delay is involved. */
                if (!owner.read_entries++) assert(!syscall(SYS_tkill, pid, SIGUSR1));
                signal = 0;
            } else {
                assert(cookie == MD_INTERCEPT_TASK);
                control(&owner, t);
                release_held_install(&owner); continue;
            }
        }
        else if (signal == (SIGTRAP | 0x80)) {
            if (t->notification) {
                struct ptrace_syscall_info info = {0};
                assert(ptrace(PTRACE_GET_SYSCALL_INFO, pid, (void *)sizeof(info), &info) >= 0);
                if (t->notification == 1) {
                    assert(info.op == PTRACE_SYSCALL_INFO_ENTRY && info.entry.nr == SYS_getpid);
                    t->notification = 2;
                    assert(!ptrace(PTRACE_SYSCALL, pid, NULL, NULL));
                } else {
                    assert(t->notification == 2 && info.op == PTRACE_SYSCALL_INFO_EXIT);
                    t->result = info.exit.rval; t->notification = 3;
                    if (scenario>=63 && scenario<=65) {
                        int sender=0;
                        for (unsigned i=0;i<TASKS;++i) sender|=owner.tasks[i].pid && owner.tasks[i].reply!=NULL;
                        t->receiver_held=sender;
                    }
                    if (!t->receiver_held) notification_finish(t);
                }
                continue;
            }
            if (t->ioctl) {
                struct user_pt_regs r=registers(pid);
                if (scenario>=63 && scenario<=65 && t->ioctl_original.regs[1]==SECCOMP_IOCTL_NOTIF_SEND
                        && !r.regs[0]) {
                    /* Lose the sender without observing its SEND completion.
                     * The receiver must use its own kernel result as witness. */
                    r.regs[8]=SYS_exit; r.regs[0]=0; r.pc=(uintptr_t)shadow_gate;
                    set_registers(pid,&r);
                    assert(!ptrace(PTRACE_CONT,pid,NULL,NULL)); continue;
                }
                notification_ioctl_complete(&owner, t); continue;
            }
            if (t->entering == 1) {
                struct ptrace_syscall_info info = {0};
                assert(ptrace(PTRACE_GET_SYSCALL_INFO, pid, (void *)sizeof(info), &info) >= 0);
                assert(info.op == PTRACE_SYSCALL_INFO_ENTRY
                    && info.instruction_pointer == (uintptr_t)shadow_entry_return);
                struct user_pt_regs entered = registers(pid);
                assert(entered.regs[0] == (unsigned)t->entry_signal);
                uintptr_t saved_address = entered.regs[2] + offsetof(ucontext_t, uc_sigmask);
                uint64_t saved;
                assert(!task_copy(t, saved_address, &saved, sizeof(saved), 0));
                uint64_t mask = md_signal_view_enter(t->entry_signal, &t->entry_action, t->blocked, NULL, &saved);
                assert(!task_copy(t, saved_address, &saved, sizeof(saved), 1));
                t->blocked = !!(mask & SYSBIT);
                t->entry_mask = mask & ~SYSBIT; t->phase = HANDLER_ENTRY; t->entering = 2;
                struct md_kernel_action entered_action;
                assert(!md_signal_actions_enter(t->actions,t->entry_signal,&entered_action));
                assert(entered_action.handler==t->entry_action.handler);
                if (scenario == 59 && t->entry_signal == SIGUSR1 && !owner.entry_injected) {
                    ++owner.entry_injected; assert(!syscall(SYS_tkill, pid, SIGSYS));
                }
                ++owner.controls;
                assert(!ptrace(PTRACE_SYSCALL, pid, NULL, NULL)); continue;
            }
            if (t->forcing == 1) {
                struct ptrace_syscall_info info = {0};
                assert(ptrace(PTRACE_GET_SYSCALL_INFO, pid, (void *)sizeof(info), &info) >= 0);
                assert(info.op == PTRACE_SYSCALL_INFO_ENTRY && info.entry.nr == SYS_mount
                    && info.instruction_pointer == (uintptr_t)shadow_gate_return);
                /* Restore the original IP before filter evaluation, without
                 * fetching the application's possibly replaced instruction. */
                set_registers(pid, &t->forced_registers); t->forcing = 2;
                assert(!ptrace(PTRACE_CONT, pid, NULL, NULL)); continue;
            }
            ++owner.exits;
            if ((scenario == 26 || scenario == 27) && t->phase == INSTALL
                    && (md_seccomp_filter_flags(t->original.regs[8], t->original.regs[1]) & SECCOMP_FILTER_FLAG_TSYNC)) {
                assert(!registers(pid).regs[0]); t->held_exit = 1;
                atomic_store(&shared->policy_gate, 1);
                syscall(SYS_futex, &shared->policy_gate, FUTEX_WAKE, 1, NULL, NULL, 0);
                continue;
            }
            complete(&owner, t); continue;
        }
        else if (signal == SIGSTOP || event == PTRACE_EVENT_STOP) {
            assert(!ptrace(PTRACE_SETOPTIONS, pid, NULL, (void *)trace_options()));
            if (pid == leader && atomic_load(&shared->startup) == 1) {
                atomic_store(&shared->startup, 2);
                syscall(SYS_futex, &shared->startup, FUTEX_WAKE, 1, NULL, NULL, 0);
            }
            signal = 0;
        } else if (signal == SIGSYS) {
            if (t->entering) {
                siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, pid, NULL, &info));
                assert(info.si_code != 1);
                assert(!md_signal_delivery_hold(&t->pending, &info));
                assert(!ptrace(t->entering == 1 ? PTRACE_SYSEMU : PTRACE_SYSCALL, pid, NULL, NULL));
                continue;
            }
            if (owner.admission_owner && owner.admission_owner != pid) {
                t->waiting_signal = signal; release_held_install(&owner); continue;
            }
            assert(md_signal_delivery_event(&t->pending, pid, signal, &signal) >= 0);
            siginfo_t info; assert(!ptrace(PTRACE_GETSIGINFO, pid, NULL, &info));
            if (info.si_code == 1 && info.si_syscall == SYS_mount) {
                ++owner.denied;
                struct user_pt_regs original = registers(pid);
                original.pc = (uintptr_t)info.si_call_addr; original.regs[8] = info.si_syscall;
                unsigned decision = evaluate(t, &original);
                unsigned type = decision & SECCOMP_RET_ACTION_FULL;
                unsigned long orig_x0 = original.regs[0];
                if (type == SECCOMP_RET_TRACE) {
                    assert(owner.debugger);
                    if (scenario == 53) {
                        assert(!md_debugger_seccomp(owner.debugger, pid, &original, decision & SECCOMP_RET_DATA));
                        original.regs[0] = -ENOSYS;
                    } else {
                        debug_inspect(owner.debugger, pid, &original, decision & SECCOMP_RET_DATA);
                        if ((long)original.regs[8] >= 0) {
                            /* ARM64's syscall_get_arguments reads hidden orig_x0,
                             * not a debugger's replacement of visible x0. */
                            struct user_pt_regs admission = original; admission.regs[0] = orig_x0;
                            unsigned rechecked = evaluate(t, &admission);
                            unsigned action = rechecked & SECCOMP_RET_ACTION_FULL;
                            assert(action == SECCOMP_RET_TRACE || action == SECCOMP_RET_ERRNO
                                || action == SECCOMP_RET_ALLOW || action == SECCOMP_RET_USER_NOTIF);
                            if (action == SECCOMP_RET_USER_NOTIF) { type = action; decision = rechecked; }
                            else original.regs[0] = action == SECCOMP_RET_ERRNO ? md_seccomp_policy_error(rechecked) : EMULATED;
                        }
                    }
                    if (type != SECCOMP_RET_USER_NOTIF) {
                        set_registers(pid, &original);
                        assert(!ptrace(PTRACE_CONT, pid, NULL, NULL)); continue;
                    }
                }
                if (type == SECCOMP_RET_USER_NOTIF) {
                    struct seccomp_data call = {.nr = original.regs[8], .arch = AUDIT_ARCH_AARCH64,
                        .instruction_pointer = original.pc};
                    memcpy(call.args, original.regs, sizeof(call.args));
                    call.args[0] = orig_x0;
                    const struct md_seccomp_policy *winner;
                    assert(!md_seccomp_policy_decide(t->policy, &call, &decision, &winner) && winner);
                    t->notified = original; t->notification = 1;
                    t->notified_data = call;
                    t->notification_mask = host_mask(pid);
                    /* The admitted carrier is a native wait, so retain the
                     * application's logical mask while it is in the kernel.
                     * Otherwise a blocked SIGSYS could cancel a visible ID. */
                    set_host_mask(pid, t->notification_mask | (t->blocked ? SYSBIT : 0));
                    struct user_pt_regs carrier = original;
                    carrier.regs[0] = orig_x0;
                    carrier.pc = (uintptr_t)shadow_gate; carrier.regs[8] = SYS_getpid;
                    carrier.regs[5] = (uintptr_t)winner;
                    set_registers(pid, &carrier);
                    assert(!ptrace(PTRACE_SYSCALL, pid, NULL, NULL)); continue;
                }
                if (t->forcing) {
                    assert(t->forcing == 2);
                    t->forcing = 0;
                    set_host_mask(pid, t->forced_mask & ~SYSBIT);
                    assert(!ptrace(PTRACE_SETSIGINFO, pid, NULL, &t->forced_info));
                } else if (type == SECCOMP_RET_ALLOW || type == SECCOMP_RET_ERRNO) {
                    struct user_pt_regs r = registers(pid);
                    r.regs[0] = type == SECCOMP_RET_ALLOW ? EMULATED : md_seccomp_policy_error(decision);
                    set_registers(pid, &r); signal = 0;
                } else {
                    assert(type == SECCOMP_RET_TRAP);
                    if (t->blocked) {
                        /* A genuine guest TRAP must retain Linux's forced
                         * default disposition. Replay through an owned gate
                         * and original admission data with a real blocked mask so
                         * the kernel performs that reset, without injecting
                         * rt_sigaction through the application's filter. */
                        assert(!md_signal_actions_commit(t->actions,SIGSYS,&(struct md_kernel_action){0}));
                        t->blocked = 0;
                        t->forcing = 1; t->forced_info = info;
                        t->forced_mask = host_mask(pid);
                        set_host_mask(pid, UINT64_MAX & ~(UINT64_C(1) << (SIGKILL - 1))
                            & ~(UINT64_C(1) << (SIGSTOP - 1)));
                        struct user_pt_regs r = registers(pid); t->forced_registers = r;
                        assert(r.pc == (uintptr_t)shadow_denied_svc + 4);
                        /* Negative control: replay must not execute this site. */
                        uint32_t nop = UINT32_C(0xd503201f);
                        copy_memory(pid, (uintptr_t)shadow_denied_svc, &nop, sizeof(nop), 1);
                        r.pc = (uintptr_t)shadow_gate; set_registers(pid, &r);
                        assert(!ptrace(PTRACE_SYSCALL, pid, NULL, NULL)); continue;
                    } else if (action_at(t, SIGSYS).handler == (uintptr_t)SIG_IGN) {
                        assert(!md_signal_actions_commit(t->actions,SIGSYS,&(struct md_kernel_action){0}));
                    }
                }
            } else if (signal) {
                ++owner.signals;
                if (t->blocked) {
                    assert(!md_signal_delivery_hold(&t->pending, &info)); signal = 0;
                } else if (action_at(t, SIGSYS).handler == (uintptr_t)SIG_IGN) signal = 0;
            }
            release_held_install(&owner);
        }
        if (traced && signal && !event && owner.admission_owner && owner.admission_owner != pid) {
            t->waiting_signal = signal; release_held_install(&owner); continue;
        }
        if (traced && signal && !event && action_at(t, signal).handler != (uintptr_t)SIG_DFL
                && action_at(t, signal).handler != (uintptr_t)SIG_IGN) {
            if (md_resource_order_state(&t->turn) == MD_RESOURCE_ORDER_IDLE)
                assert(md_memory_order_begin(t->memory, &t->turn, NULL) >= 0);
            if (md_resource_order_state(&t->turn) != MD_RESOURCE_ORDER_READY) {
                t->waiting_signal = signal; continue;
            }
            assert(!md_resource_order_start(&t->turn));
            assert(!owner.admission_owner); owner.admission_owner = pid;
            t->entry_action = action_at(t, signal); t->entry_signal = signal;
            assert(!t->entering); t->entering = 1;
        }
        assert(!ptrace(t->entering ? PTRACE_SYSEMU : PTRACE_CONT, pid, NULL, (void *)(long)signal));
    }
    md_signal_actions_release(owner.actions);
    printf("SHADOW_STOPS controls=%u exits=%u denied=%u external=%u readEntries=%u actionQueued=%u installs=%u\n",
        owner.controls, owner.exits, owner.denied, owner.signals, owner.read_entries, owner.action_queued, owner.installs);
    if (scenario == 8 || scenario == 9 || scenario == 19)
        assert(!traced || (owner.controls == 8 + owner.installs && owner.exits == 8 + owner.installs && owner.denied == 1));
    if ((scenario == 26 || scenario == 27) && traced) assert(owner.held_conflicts == 1);
    if (traced && scenario == 59) assert(owner.entry_injected == 1);
    if (traced && scenario == 60) assert(owner.return_injected >= 1);
    if (owner.debugger) { assert(debug_resumed == (scenario != 53)); md_debugger_destroy(owner.debugger); debug_resumed = 0; debug_owner = NULL; }
    return leader_status;
}

static pid_t self;
static volatile sig_atomic_t delivered, inside, frame_seen, context_before, handler_cookie;
static int read_writer;
_Alignas(64) static _Thread_local unsigned char altstack[65536];
__attribute__((noinline)) static long denied_call(void) {
    register long x0 __asm__("x0") = 0;
    register long x1 __asm__("x1") = 0;
    register long x2 __asm__("x2") = 0;
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    register long x8 __asm__("x8") = traced ? SYS_mount : SYS_getpid;
    __asm__ volatile(".global shadow_denied_svc\nshadow_denied_svc:\nsvc #0"
        : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8) : "memory", "cc");
    return x0;
}
static void emulate(void) { assert(denied_call() == (traced ? EMULATED : self)); }
static void send_signal(int signal) { assert(!syscall(SYS_tgkill, self, syscall(SYS_gettid), signal)); }
static void mask_sys(int block) {
    sigset_t set; sigemptyset(&set); sigaddset(&set, SIGSYS);
    assert(!sigprocmask(block ? SIG_BLOCK : SIG_UNBLOCK, &set, NULL));
}
static int blocked(void) {
    sigset_t mask; assert(!sigprocmask(SIG_SETMASK, NULL, &mask));
    return !!sigismember(&mask, SIGSYS);
}
static void guest_handler(int number, siginfo_t *info, void *opaque) {
    ucontext_t *context = opaque;
    unsigned char marker;
    assert((uintptr_t)&marker >= (uintptr_t)altstack
        && (uintptr_t)&marker < (uintptr_t)altstack + sizeof(altstack));
    ++frame_seen;
    if (number == SIGSYS) {
        ++delivered;
        assert(info->si_signo == SIGSYS);
        if (info->si_code == 1) {
            handler_cookie = info->si_errno;
            context->uc_mcontext.regs[0] = TRAPPED;
        } else {
            assert(info->si_code == SI_TKILL && info->si_pid == self);
            assert(blocked() == (scenario != 5));
            emulate();
            if (scenario == 5 && !inside) { inside = 1; send_signal(SIGSYS); inside = 0; }
        }
    } else {
        assert(number == SIGUSR1);
        context_before = !!sigismember(&context->uc_sigmask, SIGSYS);
        assert(blocked());
        emulate();
        int before = delivered;
        send_signal(SIGSYS);
        assert(delivered == before);
        if (scenario == 4) sigdelset(&context->uc_sigmask, SIGSYS);
    }
}
static void install(int signal, int ignored, unsigned flags, int mask) {
    struct sigaction action = {.sa_sigaction = guest_handler, .sa_flags = SA_SIGINFO | SA_ONSTACK | flags};
    sigemptyset(&action.sa_mask);
    if (mask) sigaddset(&action.sa_mask, SIGSYS);
    if (ignored) action.sa_handler = SIG_IGN;
    assert(!sigaction(signal, &action, NULL));
    struct sigaction after; assert(!sigaction(signal, NULL, &after));
    assert(after.sa_handler == action.sa_handler && after.sa_flags == action.sa_flags);
    assert(!!sigismember(&after.sa_mask, SIGSYS) == mask);
}
static void read_handler(int signal, siginfo_t *info, void *context) {
    (void)info; (void)context;
    assert(signal == SIGUSR1 && blocked());
    ++frame_seen; emulate();
    char byte = 7; assert(write(read_writer, &byte, 1) == 1);
}
static void read_restart(int restart) {
    int fds[2]; assert(!pipe(fds)); read_writer = fds[1];
    struct sigaction action = {.sa_sigaction = read_handler, .sa_flags = SA_SIGINFO | SA_ONSTACK | (restart ? SA_RESTART : 0)};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    assert(!sigaction(SIGUSR1, &action, NULL));
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_read, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, fds[0], 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 102),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
    char byte; errno = 0; long result = read(fds[0], &byte, 1);
    assert(restart ? result == 1 && byte == 7 : result == -1 && errno == EINTR);
    assert(frame_seen == 1 && !blocked());
    close(fds[0]); close(fds[1]);
}
static void exact_action_pointer(int prctl_api) {
    struct md_kernel_action action;
    assert(!syscall(SYS_rt_sigaction, SIGSYS, NULL, &action, 8));
    action.flags |= SA_NODEFER;
    uintptr_t address = (uintptr_t)&action;
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ERANGE),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_rt_sigaction, 0, 4),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)address, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1]) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(address >> 32), 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
    };
    struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
    assert(!(prctl_api ? prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &p)
        : syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p)));
    errno = 0; long result = syscall(SYS_rt_sigaction, SIGSYS, &action, NULL, 8);
    printf("ACTION_POINTER traced=%d prctl=%d result=%ld errno=%d\n", traced, prctl_api, result, errno);
    assert(!result);
    struct md_kernel_action rejected = action, previous;
    rejected.handler = (uintptr_t)SIG_IGN;
    errno = 0;
    assert(syscall(SYS_rt_sigaction, SIGSYS, &rejected, NULL, 8) == -1 && errno == EPERM);
    assert(!syscall(SYS_rt_sigaction, SIGSYS, &action, &previous, 8));
    assert(!memcmp(&action, &previous, sizeof(action)));
    errno = 0; assert(syscall(SYS_getppid) == -1 && errno == ERANGE);
    puts("PASS original filter denial preserved without changing action state");
}
static void *peer(void *unused) {
    (void)unused;
    for (unsigned i = 0; i < 128; ++i) {
        struct sigaction after; assert(!sigaction(SIGSYS, NULL, &after));
        assert(after.sa_sigaction == guest_handler);
        mask_sys(1); assert(blocked()); emulate(); assert(blocked()); mask_sys(0);
    }
    return NULL;
}
static void io_loop(unsigned count) {
    int fds[2]; assert(!pipe(fds));
    struct pollfd p = {.fd = fds[0], .events = POLLIN};
    struct timespec start, end; assert(!clock_gettime(CLOCK_MONOTONIC, &start));
    for (unsigned i = 0; i < count; ++i) {
        char byte = 7, result;
        assert(write(fds[1], &byte, 1) == 1 && poll(&p, 1, 0) == 1
            && read(fds[0], &result, 1) == 1 && result == byte);
    }
    assert(!clock_gettime(CLOCK_MONOTONIC, &end));
    printf("SHADOW_IO traced=%d count=%u ns=%lld\n", traced, count,
        (long long)(end.tv_sec - start.tv_sec) * 1000000000 + end.tv_nsec - start.tv_nsec);
    close(fds[0]); close(fds[1]);
}
static void action_a(int signal) { (void)signal; }
static void action_b(int signal) { (void)signal; }
static struct sigaction swap_actions[2];
static unsigned swaps[2][2];
static pthread_barrier_t swap_start;
static void *swap_worker(void *opaque) {
    unsigned which = (uintptr_t)opaque;
    pthread_barrier_wait(&swap_start);
    for (unsigned i = 0; i < 128; ++i) {
        struct sigaction old;
        assert(!sigaction(SIGUSR1, &swap_actions[which], &old));
        unsigned prior = old.sa_handler == action_b;
        assert(old.sa_handler == swap_actions[prior].sa_handler);
        assert(old.sa_flags == swap_actions[prior].sa_flags);
        assert(!!sigismember(&old.sa_mask, SIGSYS) == !prior);
        ++swaps[which][prior];
    }
    return NULL;
}
static void action_swaps(void) {
    swap_actions[0] = (struct sigaction){.sa_handler = action_a, .sa_flags = SA_RESTART};
    swap_actions[1] = (struct sigaction){.sa_handler = action_b, .sa_flags = SA_NODEFER};
    sigemptyset(&swap_actions[0].sa_mask); sigaddset(&swap_actions[0].sa_mask, SIGSYS);
    sigemptyset(&swap_actions[1].sa_mask);
    assert(!sigaction(SIGUSR1, &swap_actions[0], NULL));
    assert(!pthread_barrier_init(&swap_start, NULL, 2));
    pthread_t a, b; assert(!pthread_create(&a, NULL, swap_worker, (void *)0));
    assert(!pthread_create(&b, NULL, swap_worker, (void *)1));
    assert(!pthread_join(a, NULL) && !pthread_join(b, NULL));
    struct sigaction last; assert(!sigaction(SIGUSR1, NULL, &last));
    unsigned last_a = last.sa_handler == action_a;
    assert(swaps[0][0] + swaps[1][0] == 129 - last_a);
    assert(swaps[0][1] + swaps[1][1] == 127 + last_a);
    assert(!pthread_barrier_destroy(&swap_start));
}
static void reset_handler(int signal) { assert(signal == SIGUSR1); reset_entry(); }
static void *reset_worker(void *unused) { (void)unused; send_signal(SIGUSR1); return NULL; }
static void reset_query(void) {
    struct sigaction action = {.sa_handler = reset_handler, .sa_flags = SA_RESETHAND | SA_RESTART};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    assert(!sigaction(SIGUSR1, &action, NULL));
    pthread_t thread; assert(!pthread_create(&thread, NULL, reset_worker, NULL));
    /* EVENT_WAIT: native handler/shadow veneer entry; suite alarm bounds failure. */
    while (!atomic_load(&shared->reset_gate))
        syscall(SYS_futex, &shared->reset_gate, FUTEX_WAIT, 0, NULL, NULL, 0);
    struct sigaction current; assert(!sigaction(SIGUSR1, NULL, &current));
    assert(current.sa_handler == SIG_DFL && current.sa_flags == action.sa_flags
        && sigismember(&current.sa_mask, SIGSYS));
    atomic_store(&shared->reset_gate, 2);
    syscall(SYS_futex, &shared->reset_gate, FUTEX_WAKE, 1, NULL, NULL, 0);
    assert(!pthread_join(thread, NULL));
}
static void action_normalization(void) {
    for (unsigned i = 0; i < 2; ++i) {
        int signal = i ? SIGSYS : SIGUSR1;
        struct md_kernel_action requested = {.handler = (uintptr_t)action_a,
            .flags = SA_RESTART | 0x400U | 0x20000000U, .mask = UINT64_MAX};
        struct md_kernel_action observed;
        assert(!syscall(SYS_rt_sigaction, signal, &requested, NULL, 8));
        assert(!syscall(SYS_rt_sigaction, signal, NULL, &observed, 8));
        assert(observed.handler == requested.handler && observed.flags == SA_RESTART
            && observed.mask == md_signal_blockable_mask());
    }
}
static void action_faults(int output) {
    size_t page = getpagesize();
    void *bad = mmap(NULL, page, output ? PROT_READ : PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(bad != MAP_FAILED);
    struct md_kernel_action before, after, ignored = {.handler = (uintptr_t)SIG_IGN, .flags = SA_RESTART, .mask = SYSBIT};
    assert(!syscall(SYS_rt_sigaction, SIGSYS, NULL, &before, 8));
    errno = 0;
    assert(syscall(SYS_rt_sigaction, SIGSYS, output ? &ignored : bad, output ? bad : &after, 8) == -1 && errno == EFAULT);
    assert(!syscall(SYS_rt_sigaction, SIGSYS, NULL, &after, 8));
    assert(!memcmp(&after, output ? &ignored : &before, sizeof(after)));
    uint64_t mask = SYSBIT;
    errno = 0;
    assert(syscall(SYS_rt_sigprocmask, SIG_BLOCK, output ? &mask : bad, output ? bad : NULL, 8) == -1 && errno == EFAULT);
    assert(blocked() == output); mask_sys(0);
    mask_sys(1);
    errno = 0;
    assert(syscall(SYS_rt_sigprocmask, SIG_SETMASK, NULL, bad, 8) == -1 && errno == EFAULT);
    assert(blocked());
    errno = 0;
    assert(syscall(SYS_rt_sigprocmask, SIG_SETMASK, NULL, bad, 7) == -1 && errno == EINVAL);
    assert(blocked());
    assert(!syscall(SYS_rt_sigprocmask, -1, NULL, NULL, 8));
    assert(blocked()); mask_sys(0);
    if (!output) {
        errno = 0; assert(syscall(SYS_rt_sigaction, 0, bad, NULL, 8) == -1 && errno == EFAULT);
        errno = 0; assert(syscall(SYS_rt_sigaction, SIGSYS, bad, NULL, 0) == -1 && errno == EINVAL);
        errno = 0; assert(syscall(SYS_rt_sigaction, SIGKILL, &ignored, NULL, 8) == -1 && errno == EINVAL);
    }
    assert(!munmap(bad, page));
}
static void pending_lengths(void) {
    const uint64_t expected = SYSBIT | (UINT64_C(1) << (SIGUSR1 - 1));
    assert(!syscall(SYS_rt_sigprocmask, SIG_BLOCK, &expected, NULL, 8));
    send_signal(SIGUSR1); send_signal(SIGSYS);
    size_t page = getpagesize();
    unsigned char *buffer = mmap(NULL, page, PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(buffer != MAP_FAILED);
    for (unsigned length = 0; length <= sizeof(expected); ++length) {
        memset(buffer, 0xa5, 16);
        assert(!syscall(SYS_rt_sigpending, buffer, length));
        assert(!memcmp(buffer, &expected, length));
        for (unsigned i = length; i < 16; ++i) assert(buffer[i] == 0xa5);
    }
    errno = 0; assert(syscall(SYS_rt_sigpending, buffer, 9) == -1 && errno == EINVAL);
    assert(!mprotect(buffer, page, PROT_READ));
    errno = 0; assert(syscall(SYS_rt_sigpending, buffer, 8) == -1 && errno == EFAULT);
    assert(!syscall(SYS_rt_sigpending, NULL, 0));
    assert(!munmap(buffer, page));
}
static void mask_arguments(void) {
    uint64_t mask = SYSBIT, old = 0, before;
    assert(!syscall(SYS_rt_sigprocmask, SIG_SETMASK, NULL, &before, 8));
    assert(!syscall(SYS_rt_sigprocmask, (UINT64_C(1) << 32) | SIG_BLOCK, &mask, &old, 8));
    assert(old == before && blocked());
    mask = 0;
    assert(!syscall(SYS_rt_sigprocmask, (UINT64_C(1) << 32) | SIG_SETMASK, &mask, &old, 8));
    assert(old == (before | SYSBIT) && !blocked());
    assert(!syscall(SYS_rt_sigprocmask, -1, NULL, &old, 8) && !old);
    errno = 0;
    assert(syscall(SYS_rt_sigprocmask, -1, &mask, NULL, 8) == -1 && errno == EINVAL);
}
static sigjmp_buf escape;
static void escaping_handler(int signal) {
    assert(signal == SIGUSR1 && blocked()); emulate(); siglongjmp(escape, 1);
}
static void nonlocal_exit(int save_mask) {
    struct sigaction action = {.sa_handler = escaping_handler, .sa_flags = SA_ONSTACK};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    assert(!sigaction(SIGUSR1, &action, NULL));
    if (!sigsetjmp(escape, save_mask)) { send_signal(SIGUSR1); abort(); }
    assert(blocked() == !save_mask); emulate(); mask_sys(0);
}
static void nested_handler(int signal, siginfo_t *info, void *opaque) {
    (void)info;
    ucontext_t *context = opaque;
    assert(blocked()); emulate();
    if (signal == SIGUSR1) {
        assert(!sigismember(&context->uc_sigmask, SIGSYS));
        send_signal(SIGUSR2); assert(!blocked());
    } else {
        assert(signal == SIGUSR2 && sigismember(&context->uc_sigmask, SIGSYS));
        sigdelset(&context->uc_sigmask, SIGSYS);
    }
    ++frame_seen;
}
static void nested_contexts(void) {
    struct sigaction action = {.sa_sigaction = nested_handler, .sa_flags = SA_SIGINFO | SA_ONSTACK};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    assert(!sigaction(SIGUSR1, &action, NULL) && !sigaction(SIGUSR2, &action, NULL));
    send_signal(SIGUSR1); assert(frame_seen == 2 && !blocked());
}
static void protected_signals(int faults, int filtered) {
    assert(!prctl(PR_SET_DUMPABLE, 0));
    assert(prctl(PR_GET_DUMPABLE) == 0);
    if (filtered) {
        struct sock_filter code[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_process_vm_readv, 1, 0),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_process_vm_writev, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog program = {sizeof(code) / sizeof(*code), code};
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &program));
        errno = 0;
        assert(syscall(SYS_process_vm_readv, self, NULL, 0, NULL, 0, 0) == -1 && errno == EACCES);
    }
    if (faults) {
        struct sigaction segv = {.sa_handler = action_a, .sa_flags = SA_RESETHAND}, after;
        sigemptyset(&segv.sa_mask); sigaddset(&segv.sa_mask, SIGSEGV);
        assert(!sigaction(SIGSEGV, &segv, NULL));
        action_faults(0); action_faults(1);
        assert(!sigaction(SIGSEGV, NULL, &after));
        assert(after.sa_handler == segv.sa_handler && after.sa_flags == segv.sa_flags
            && sigismember(&after.sa_mask, SIGSEGV));
    } else nested_contexts();
    assert(prctl(PR_GET_DUMPABLE) == 0);
}
static _Atomic unsigned policy_ready, policy_go;
static void *filtered_peer(void *unused) {
    (void)unused;
    if (scenario >= 26) mask_sys(1);
    atomic_store(&policy_ready, 1); syscall(SYS_futex, &policy_ready, FUTEX_WAKE, 1, NULL, NULL, 0);
    /* EVENT_WAIT: native TSYNC commit; suite alarm bounds failure. */
    _Atomic unsigned *gate = scenario >= 26 ? &shared->policy_gate : &policy_go;
    while (!atomic_load(gate)) syscall(SYS_futex, gate, FUTEX_WAIT, 0, NULL, NULL, 0);
    if (scenario == 27) {
        uint64_t pending;
        errno = 0; assert(syscall(SYS_rt_sigpending, &pending, 8) == -1 && errno == EACCES);
    }
    if (scenario < 26) mask_sys(1);
    assert(denied_call() == -EACCES && blocked()); mask_sys(0);
    errno = 0; assert(syscall(SYS_getppid) == -1 && errno == EACCES); return NULL;
}
static void filter_tsync(void) {
    pthread_t peer; assert(!pthread_create(&peer, NULL, filtered_peer, NULL));
    /* EVENT_WAIT: initialized sibling; suite alarm bounds failure. */
    while (!atomic_load(&policy_ready)) syscall(SYS_futex, &policy_ready, FUTEX_WAIT, 0, NULL, NULL, 0);
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, traced ? SYS_mount : SYS_getpid, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, scenario == 27 ? SYS_rt_sigpending : SYS_getppid, 1, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(code) / sizeof(*code), code};
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program));
    code[4].k = SECCOMP_RET_ERRNO | EPERM;
    if (scenario >= 26 && !traced) {
        atomic_store(&shared->policy_gate, 1);
        syscall(SYS_futex, &shared->policy_gate, FUTEX_WAKE, 1, NULL, NULL, 0);
    }
    atomic_store(&policy_go, 1); syscall(SYS_futex, &policy_go, FUTEX_WAKE, 1, NULL, NULL, 0);
    assert(denied_call() == -EACCES && !pthread_join(peer, NULL));
    assert(!pthread_create(&peer, NULL, filtered_peer, NULL) && !pthread_join(peer, NULL));
}
static _Atomic unsigned notification_signal;
static long notification_nested_result;
static void notification_handler(int signal) {
    assert((signal == SIGUSR1 || (scenario == 54 && signal == SIGSYS)) && blocked());
    if (scenario == 55) notification_nested_result = denied_call();
    atomic_store(&notification_signal, 1);
    syscall(SYS_futex, &notification_signal, FUTEX_WAKE, 1, NULL, NULL, 0);
}
static int notification_filter(int listener) {
    uintptr_t ip = (uintptr_t)shadow_denied_svc + 4;
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, traced ? SYS_mount : SYS_getpid, 0, 5),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)ip, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ip >> 32, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
    int result = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER,
        listener ? SECCOMP_FILTER_FLAG_NEW_LISTENER : 0, &p);
    assert(result >= 0); return result;
}
static void notification_recv(int fd, struct seccomp_notif *q) {
    *q = (struct seccomp_notif){0};
    assert(!ioctl(fd, SECCOMP_IOCTL_NOTIF_RECV, q));
    assert(q->pid == (unsigned)self && !q->flags && q->data.arch == AUDIT_ARCH_AARCH64
        && q->data.nr == (traced ? SYS_mount : SYS_getpid)
        && q->data.instruction_pointer == (uintptr_t)shadow_denied_svc + 4);
    for (unsigned i = 0; i < 5; ++i) assert(q->data.args[i] == (unsigned)(scenario == 61 && i == 1 ? 24 : 0));
    assert(!ioctl(fd, SECCOMP_IOCTL_NOTIF_ID_VALID, &q->id));
}
static void *notification_listener(void *opaque) {
    int fd = (int)(intptr_t)opaque;
    struct pollfd poller = {.fd = fd, .events = POLLIN};
    assert(poll(&poller, 1, 5000) == 1 && (poller.revents & POLLIN));
    if (scenario == 47) {
        void *readonly = mmap(NULL, 4096, PROT_READ, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        assert(readonly != MAP_FAILED);
        errno = 0; assert(ioctl(fd, SECCOMP_IOCTL_NOTIF_RECV, readonly) == -1 && errno == EFAULT);
        assert(!munmap(readonly, 4096));
    }
    struct seccomp_notif q; notification_recv(fd, &q);
    if (scenario == 54) {
        assert(!syscall(SYS_tgkill, self, self, SIGSYS));
        assert(!ioctl(fd, SECCOMP_IOCTL_NOTIF_ID_VALID, &q.id));
        assert(!atomic_load(&notification_signal));
    }
    if (scenario == 43) { assert(!close(fd)); return NULL; }
    if (scenario == 45 || scenario == 46) {
        uint64_t old = q.id;
        assert(!syscall(SYS_tgkill, self, self, SIGUSR1));
        /* EVENT_WAIT: actual guest handler confirms cancellation, not a delay. */
        while (!atomic_load(&notification_signal))
            syscall(SYS_futex, &notification_signal, FUTEX_WAIT, 0, NULL, NULL, 0);
        errno = 0; assert(ioctl(fd, SECCOMP_IOCTL_NOTIF_ID_VALID, &old) == -1 && errno == ENOENT);
        struct seccomp_notif_resp stale = {.id = old, .val = 333};
        errno = 0; assert(ioctl(fd, SECCOMP_IOCTL_NOTIF_SEND, &stale) == -1 && errno == ENOENT);
        if (scenario == 45) return NULL;
        notification_recv(fd, &q); assert(q.id != old);
    }
    if (scenario == 41) {
        int source = open("/dev/null", O_RDONLY | O_CLOEXEC); assert(source >= 0);
        struct seccomp_notif_addfd add = {.id = q.id, .srcfd = source,
            .flags = SECCOMP_ADDFD_FLAG_SEND, .newfd_flags = O_CLOEXEC};
        assert(ioctl(fd, SECCOMP_IOCTL_NOTIF_ADDFD, &add) >= 0);
        assert(!close(source)); return NULL;
    }
    struct seccomp_notif_resp response = {.id = q.id, .val = 333};
    if (scenario == 39 || scenario == 64) { response.val = 0; response.error = -ERANGE; }
    if (scenario == 40 || scenario == 65) { response.val = 0; response.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE; }
    if (scenario == 56) {
        uintptr_t pointer = (uintptr_t)&response;
        struct sock_filter code[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_ioctl, 0, 7),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2])),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)pointer, 0, 3),
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[2]) + 4),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, pointer >> 32, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
        struct seccomp_notif_resp wrong = response;
        errno = 0; assert(ioctl(fd, SECCOMP_IOCTL_NOTIF_SEND, &wrong) == -1 && errno == EPERM);
    }
    assert(!ioctl(fd, SECCOMP_IOCTL_NOTIF_SEND, &response));
    if (scenario>=63 && scenario<=65) syscall(SYS_exit,0);
    errno = 0;
    assert(ioctl(fd, SECCOMP_IOCTL_NOTIF_SEND, &response) == -1 && (errno == ENOENT || errno == EINPROGRESS));
    return NULL;
}
struct listener_transfer { int inherited, socket; };
static int transferred_listener(void *opaque) {
    struct listener_transfer *args=opaque;
    assert(!close(args->inherited));
    char byte;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control)};
    ssize_t received=recvmsg(args->socket,&message,MSG_CMSG_CLOEXEC);
    printf("NOTIFICATION transfer received=%zd errno=%d flags=%#x\n",received,received<0 ? errno : 0,message.msg_flags);
    assert(received==1 && !(message.msg_flags&(MSG_CTRUNC|MSG_TRUNC)));
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    assert(header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS
        && header->cmsg_len==CMSG_LEN(sizeof(int)));
    int fd; memcpy(&fd,CMSG_DATA(header),sizeof(fd));
    assert(fcntl(fd,F_GETFD)&FD_CLOEXEC);
    notification_listener((void *)(intptr_t)fd);
    assert(!close(fd)); return 0;
}
static void notification_transfer(int fd) {
    int sockets[2]; assert(!socketpair(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0,sockets));
    size_t size=65536;
    void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    struct listener_transfer args={fd,sockets[1]};
    int child=clone(transferred_listener,(char *)stack+size,CLONE_VM|CLONE_SIGHAND|SIGCHLD,&args);
    assert(child>0);
    char byte=0;
    union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control={0};
    struct iovec vector={&byte,1};
    struct msghdr message={.msg_iov=&vector,.msg_iovlen=1,
        .msg_control=control.bytes,.msg_controllen=sizeof(control)};
    struct cmsghdr *header=CMSG_FIRSTHDR(&message);
    header->cmsg_level=SOL_SOCKET; header->cmsg_type=SCM_RIGHTS; header->cmsg_len=CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header),&fd,sizeof(fd));
    assert(sendmsg(sockets[0],&message,MSG_NOSIGNAL)==1);
    assert(!close(fd));
    assert(denied_call()==333);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    assert(!close(sockets[0]) && !close(sockets[1]) && !munmap(stack,size));
}
static int child_actions(void *unused) {
    (void)unused;
    struct sigaction ignored={.sa_handler=SIG_IGN},previous;
    sigemptyset(&ignored.sa_mask);
    assert(!sigaction(SIGSYS,&ignored,&previous) && previous.sa_sigaction==guest_handler);
    return 0;
}
static void action_inheritance(void) {
    mask_sys(1); send_signal(SIGSYS);
    size_t size=65536;
    void *stack=mmap(NULL,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(stack!=MAP_FAILED);
    unsigned flags=SIGCHLD|(scenario==69 ? 0 : CLONE_VM)|(scenario==70 ? CLONE_SIGHAND : 0);
    pid_t child=clone(child_actions,(char *)stack+size,flags,NULL); assert(child>0);
    int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status) && !WEXITSTATUS(status));
    struct sigaction after; assert(!sigaction(SIGSYS,NULL,&after));
    if (scenario==70) {
        assert(after.sa_handler==SIG_IGN);
        install(SIGSYS,0,0,0);
    } else assert(after.sa_sigaction==guest_handler);
    sigset_t pending; assert(!sigpending(&pending) && sigismember(&pending,SIGSYS));
    assert(!delivered); mask_sys(0); assert(delivered==1);
    assert(!munmap(stack,size));
}
static void notification_test(void) {
    struct sigaction action = {.sa_handler = notification_handler, .sa_flags = scenario == 46 ? SA_RESTART : 0};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    assert(!sigaction(scenario == 54 ? SIGSYS : SIGUSR1, &action, NULL));
    mask_sys(1);
    if (scenario == 48) assert(!prctl(PR_SET_DUMPABLE, 0));
    int fd = notification_filter(scenario != 44);
    if (scenario == 67) { notification_transfer(fd); assert(blocked()); mask_sys(0); return; }
    if (scenario == 44) { assert(denied_call() == -ENOSYS); mask_sys(0); return; }
    if (scenario == 42) { assert(!close(fd)); fd = notification_filter(1); }
    if (scenario == 57) {
        assert(!notification_filter(0)); assert(denied_call() == -ENOSYS);
        struct pollfd poller = {.fd = fd, .events = POLLIN}; assert(!poll(&poller, 1, 0));
        assert(!close(fd)); mask_sys(0); return;
    }
    pthread_t thread; assert(!pthread_create(&thread, NULL, notification_listener, (void *)(intptr_t)fd));
    long result;
    if (scenario == 55) { send_signal(SIGUSR1); result = notification_nested_result; }
    else result = denied_call();
    assert(!pthread_join(thread, NULL));
    if (scenario == 41) {
        assert(result >= 0 && (fcntl(result, F_GETFD) & FD_CLOEXEC));
        char byte; assert(!read(result, &byte, 1)); assert(!close(result));
    } else {
        long expected = scenario == 39 || scenario == 64 ? -ERANGE
            : scenario == 40 || scenario == 65 ? (traced ? EMULATED : self)
            : scenario == 43 ? -ENOSYS : scenario == 45 ? -EINTR : 333;
        printf("NOTIFICATION result=%ld expected=%ld\n", result, expected);
        assert(result == expected);
    }
    if (scenario != 43) assert(!close(fd));
    assert(blocked()); mask_sys(0);
    if (scenario == 54) assert(atomic_load(&notification_signal) == 1);
}
static void boundary_handler(int signal) {
    assert(blocked());
    if (signal == SIGSYS) ++delivered;
    else {
        assert(signal == SIGUSR1 && !delivered);
        if (!traced) send_signal(SIGSYS);
        assert(!delivered);
    }
}
static void boundary_signals(void) {
    struct sigaction action = {.sa_handler = boundary_handler};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGSYS);
    assert(!sigaction(SIGSYS, &action, NULL) && !sigaction(SIGUSR1, &action, NULL));
    send_signal(SIGUSR1); assert(delivered == 1 && !blocked());
}
static void trace_test(void) {
    struct sigaction ignored = {.sa_handler = SIG_IGN}; sigemptyset(&ignored.sa_mask);
    assert(!sigaction(SIGTRAP, &ignored, NULL));
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGTRAP);
    assert(!sigprocmask(SIG_BLOCK, &mask, NULL)); mask_sys(1);
    if (scenario == 62) assert(!prctl(PR_SET_DUMPABLE, 0));
    uintptr_t ip = (uintptr_t)shadow_denied_svc + 4;
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EACCES),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, traced ? SYS_mount : SYS_getpid, 0, 10),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)ip, 0, 8),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, instruction_pointer) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ip >> 32, 0, 6),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, scenario == 58
            ? offsetof(struct seccomp_data, args[0]) : offsetof(struct seccomp_data, args[1])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 21, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ERANGE),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 24, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | 0x321),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
    int listener = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER,
        scenario == 61 ? SECCOMP_FILTER_FLAG_NEW_LISTENER : 0, &p);
    assert(listener >= 0);
    pthread_t thread;
    if (scenario == 61) assert(!pthread_create(&thread, NULL, notification_listener, (void *)(intptr_t)listener));
    long expected = scenario == 50 ? 444 : scenario == 51 ? -EACCES : scenario == 52 ? -ERANGE
        : scenario == 53 ? -ENOSYS : scenario == 61 ? 333 : traced ? EMULATED : self;
    long result = denied_call(); printf("TRACE result=%ld expected=%ld\n", result, expected);
    assert(result == expected && blocked()); mask_sys(0);
    if (scenario == 61) { assert(!pthread_join(thread, NULL)); assert(!close(listener)); }
    assert(!sigaction(SIGTRAP, NULL, &ignored) && ignored.sa_handler == SIG_IGN);
    assert(!sigprocmask(SIG_SETMASK, NULL, &mask) && sigismember(&mask, SIGTRAP));
}
static void filters(unsigned decision) {
    if (traced) {
        const unsigned calls[] = {SYS_seccomp, SYS_prctl, SYS_rt_sigaction, SYS_rt_sigprocmask,
            SYS_rt_sigpending, SYS_rt_sigreturn, SYS_clone, SYS_mmap, SYS_mprotect, SYS_mremap, SYS_munmap,
            SYS_process_vm_readv, SYS_process_vm_writev, SYS_ioctl};
        struct sock_filter code[1 + 2 * sizeof(calls) / sizeof(*calls) + 1]; unsigned length = 0;
        code[length++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
        for (unsigned i = 0; i < sizeof(calls) / sizeof(*calls); ++i) {
            code[length++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, calls[i], 0, 1);
            code[length++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRACE | MD_INTERCEPT_TASK);
        }
        code[length++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
        struct sock_fprog p = {length, code};
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
    }
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, traced ? SYS_mount : SYS_getpid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, decision),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
    assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
}
static void exercise(unsigned decision) {
    self = getpid();
    sigset_t empty; sigemptyset(&empty); assert(!sigprocmask(SIG_SETMASK, &empty, NULL));
    stack_t stack = {.ss_sp = altstack, .ss_size = sizeof(altstack)}; assert(!sigaltstack(&stack, NULL));
    if (traced || scenario == 15 || scenario == 16 || trace_scenario()) {
        atomic_store(&shared->startup, 1);
        syscall(SYS_futex, &shared->startup, FUTEX_WAKE, 1, NULL, NULL, 0);
        /* EVENT_WAIT: parent seizes this exact task before filter installation. */
        while (atomic_load(&shared->startup) != 2)
            syscall(SYS_futex, &shared->startup, FUTEX_WAIT, 1, NULL, NULL, 0);
    }
    assert(!prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0));
    filters(decision);
    install(SIGSYS, scenario == 1 || scenario == 14, scenario == 5 ? SA_NODEFER : 0, 0);
    if (scenario == 22) { action_normalization(); return; }
    if (scenario == 23 || scenario == 24) { action_faults(scenario == 24); return; }
    if (scenario >= 25 && scenario <= 27) { filter_tsync(); return; }
    if (scenario == 28) { pending_lengths(); return; }
    if (scenario == 29 || scenario == 30) {
        mask_sys(1); assert(denied_call() == (scenario == 29 ? 0 : -4095));
        assert(blocked()); mask_sys(0); return;
    }
    if (scenario == 31) { mask_arguments(); return; }
    if (scenario == 32 || scenario == 33) { nonlocal_exit(scenario == 32); return; }
    if (scenario == 34) { nested_contexts(); return; }
    if (scenario == 59 || scenario == 60) { boundary_signals(); return; }
    if (scenario >= 68 && scenario <= 70) { action_inheritance(); return; }
    if (trace_scenario()) { trace_test(); return; }
    if (scenario >= 38) { notification_test(); return; }
    if (scenario >= 35) { protected_signals(scenario != 35, scenario == 37); return; }
    if (scenario == 20) { action_swaps(); return; }
    if (scenario == 21) { reset_query(); return; }
    if (scenario >= 17) {
        exact_action_pointer(scenario != 17);
        if (scenario == 19) { mask_sys(1); emulate(); io_loop(65536); mask_sys(0); }
        return;
    }
    if (scenario == 15 || scenario == 16) { read_restart(scenario == 16); return; }
    if (scenario == 13 || scenario == 14) {
        if (scenario == 13) mask_sys(1);
        struct sock_filter code[] = {
            BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
            BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_rt_sigaction, 0, 1),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
            BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        };
        struct sock_fprog p = {sizeof(code) / sizeof(*code), code};
        assert(!syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p));
        denied_call(); abort();
    }
    if (scenario == 10) { mask_sys(1); assert(denied_call() == -EACCES); assert(blocked()); return; }
    if (scenario == 11) { assert(denied_call() == TRAPPED && delivered == 1 && handler_cookie == 73); return; }
    if (scenario == 12) { denied_call(); abort(); }
    if (scenario == 1) { emulate(); send_signal(SIGSYS); assert(!delivered); install(SIGSYS, 0, 0, 0); }
    else if (scenario == 3 || scenario == 4) {
        install(SIGUSR1, 0, 0, 1);
        if (scenario == 4) mask_sys(1);
        send_signal(SIGUSR1);
        assert(context_before == (scenario == 4));
        assert(!blocked() && delivered == 1 && frame_seen == 2);
    } else if (scenario == 5) { send_signal(SIGSYS); assert(delivered == 2 && frame_seen == 2); }
    else if (scenario == 7) {
        pthread_t a, b; assert(!pthread_create(&a, NULL, peer, NULL) && !pthread_create(&b, NULL, peer, NULL));
        assert(!pthread_join(a, NULL) && !pthread_join(b, NULL));
    } else {
        mask_sys(1); assert(blocked());
        if (scenario == 2) {
            send_signal(SIGSYS); send_signal(SIGSYS);
            sigset_t pending; assert(!sigpending(&pending) && sigismember(&pending, SIGSYS));
            assert(!delivered);
        }
        emulate(); assert(blocked());
        if (scenario == 8 || scenario == 9) io_loop(scenario == 8 ? 1024 : 65536);
        struct sigaction after; assert(!sigaction(SIGSYS, NULL, &after) && after.sa_sigaction == guest_handler);
        mask_sys(0);
        if (scenario == 2) assert(delivered == 1);
    }
    assert(!blocked());
}
static unsigned scenario_decision(void) {
    return scenario == 29 ? SECCOMP_RET_ERRNO : scenario == 30 ? SECCOMP_RET_ERRNO | 65535
        : scenario == 10 ? SECCOMP_RET_ERRNO | EACCES : (scenario == 11 || scenario == 13 || scenario == 14) ? SECCOMP_RET_TRAP | 73
        : scenario == 12 ? SECCOMP_RET_KILL_PROCESS : SECCOMP_RET_ALLOW;
}
int main(int argc,char **argv) {
    setbuf(stdout, NULL); alarm(45);
    if (argc==3 && !strcmp(argv[1],"guest")) {
        scenario=atoi(argv[2]);
        assert(scenario>=0 && scenario<=70 && scenario!=15 && scenario!=16 && !trace_scenario());
        shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
        assert(shared!=MAP_FAILED);
        pid_t child=fork(); assert(child>=0);
        if (!child) { exercise(scenario_decision()); _exit(0); }
        int status; assert(waitpid(child,&status,0)==child);
        int okay=scenario>=12 && scenario<=14 ? WIFSIGNALED(status) && WTERMSIG(status)==SIGSYS
            : WIFEXITED(status) && !WEXITSTATUS(status);
        printf("%s production signal scenario=%d status=%#x\n",okay ? "PASS" : "FAIL",scenario,status);
        assert(!munmap(shared,sizeof(*shared)));
        return !okay;
    }
    assert(argc==1);
    const char *names[] = {"blocked", "ignored", "pending-coalesced", "handler-mask", "context-edit", "nested-nodefer",
        "blocked-query", "shared-threads", "io-small", "io-large", "guest-errno", "guest-trap", "guest-kill",
        "guest-trap-blocked", "guest-trap-ignored", "read-eintr", "read-restart", "action-pointer",
        "action-pointer-prctl", "action-pointer-io", "action-swaps", "reset-query",
        "action-normalization", "input-fault", "output-fault", "tsync-signal-policy",
        "tsync-denied-admission", "tsync-control-admission", "pending-lengths",
        "guest-errno-zero", "guest-errno-clamp", "mask-arguments",
        "siglongjmp", "longjmp", "nested-contexts", "protected-signals", "protected-faults", "protected-filter",
        "notify-value", "notify-errno", "notify-continue", "notify-addfd", "notify-newest", "notify-close",
        "notify-no-listener", "notify-cancel", "notify-restart", "notify-recv-fault", "notify-protected",
        "trace-continue", "trace-skip", "trace-number", "trace-arguments", "trace-no-option",
        "notify-blocked-sigsys", "notify-handler", "notify-admission", "notify-unlistened-head",
        "trace-first-argument", "handler-entry-sigsys", "handler-return-sigsys", "trace-to-notification",
        "trace-protected", "notify-sender-death-value", "notify-sender-death-errno",
        "notify-sender-death-continue", "notify-source-mutation", "notify-transferred-listener",
        "vm-private-actions", "fork-private-actions", "sighand-private-pending"};
    unsigned passed = 0;
    for (scenario = 0; scenario < (int)(sizeof(names) / sizeof(*names)); ++scenario) {
        for (traced = 0; traced <= 1; ++traced) {
            shared = mmap(NULL, sizeof(*shared), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
            assert(shared != MAP_FAILED);
            assert(!md_buffers_create_readonly(TASKS, sizeof(struct md_seccomp_image), &images));
            size_t image_size = md_buffers_size(images);
            image_view = mmap(NULL, image_size, PROT_READ, MAP_SHARED, md_buffers_fd(images), 0);
            assert(image_view != MAP_FAILED);
            unsigned decision = scenario_decision();
            printf("SHADOW_BEGIN name=%s traced=%d\n", names[scenario], traced);
            pid_t child = fork(); assert(child >= 0);
            if (!child) { md_buffers_release(images); images = NULL; exercise(decision); _exit(0); }
            int status;
            if (traced || scenario == 15 || scenario == 16 || trace_scenario()) {
                /* EVENT_WAIT: declared child handshake; the suite alarm bounds failure. */
                while (!atomic_load(&shared->startup))
                    syscall(SYS_futex, &shared->startup, FUTEX_WAIT, 0, NULL, NULL, 0);
                assert(!ptrace(PTRACE_SEIZE, child, NULL, (void *)trace_options()));
                assert(!ptrace(PTRACE_INTERRUPT, child, NULL, NULL));
                status = supervise(child);
            }
            else assert(waitpid(child, &status, 0) == child);
            int okay = scenario >= 12 && scenario <= 14 ? WIFSIGNALED(status) && WTERMSIG(status) == SIGSYS
                : WIFEXITED(status) && !WEXITSTATUS(status);
            printf("%s shadow=%s traced=%d status=%#x\n", okay ? "PASS" : "FAIL", names[scenario], traced, status);
            assert(okay);
            ++passed;
            struct md_interception_buffer loans[TASKS] = {0};
            for (unsigned i = 0; i < TASKS; ++i) assert(!md_buffers_take(images, &loans[i]));
            for (unsigned i = 0; i < TASKS; ++i) assert(!md_buffer_release(&loans[i]));
            assert(!munmap(image_view, image_size)); md_buffers_release(images); images = NULL;
            assert(!munmap(shared, sizeof(*shared)));
        }
    }
    printf("SHADOW_SUMMARY passed=%u scope=native-protocol\n", passed);
}
