#define _GNU_SOURCE
#include <linux/netlink.h>
#include "adapter_buffer.h"
#include "clone_call.h"
#include "device_events.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "filesystem_ioctl.h"
#include "fs_worker.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "interception.h"
#include "interception_buffers.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "mount_copy_service.h"
#include "namespace_broker.h"
#include "network_endpoint.h"
#include "network_namespace.h"
#include "pid_calls.h"
#include "pid_namespace.h"
#include "resource_order.h"
#include "seccomp_install.h"
#include "socket_calls.h"
#include "supervisor_actors.h"
#include "supervisor_descriptors.h"
#include "supervisor_domain.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_seccomp.h"
#include "supervisor_task.h"
#include "supervisor_transfer.h"
#include "task_resources.h"
#include "terminal_calls.h"
#include "uts_namespace.h"
static int guest_root_path(struct thread *t, long nr);
static int domain_path_apply(struct thread *t, long nr, const char *path);
static void domain_path_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r);

static int guest_root_path(struct thread *t, long nr) {
    return nr == SYS_chroot && t->endpoint[0] && !md_domain_restricted(t->resources.fs);
}

static int domain_path_apply(struct thread *t, long nr, const char *path) {
    if (nr == SYS_chdir)
        return !strcmp(path, "/") ? md_domain_chdir_root(t->resources.fs) : -ENOTSUP;
    int error = md_domain_restrict(t->resources.fs, t->pid, path);
    if (!error) {
        root_changes++;
        /* CLONE_FS members may run without another ptrace stop. Revoke their
         * published eligibility before acknowledging the shared restriction. */
        if (broker) for (struct thread *member = threads; member; member = member->next)
            if (member->pid && member->resources.fs == t->resources.fs)
                CHECK(!md_broker_task(broker, member->pid, 0, RAW_GATE, abi.watch_return,NULL,0));
    }
    SUPERVISOR_TRACE("PROBE root-change pid=%d result=%d\n", t->pid, error);
    return error;
}

void domain_path_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->execution.registers;
    long nr=t->execution.request.nr;
    if (!error) error = domain_path_apply(t,nr,t->metadata.path);
    r.regs[0] = (uint64_t)error;
    if (!set_registers(t->pid, &r) || !restore_mask(t)) return;
    t->phase = IDLE; resume(t->pid, 0);
}

static void domain_path_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r) {
    if (guest_root_path(t,call->nr)) {
        execution_begin(t,call,r,"root-path-mask"); return;
    }
    char path[4096];
    int error = read_string(t->pid,call->args[0],path,sizeof(path));
    if (error != -EPERM && error != -EACCES) {
        if (!error) error = domain_path_apply(t,call->nr,path);
        return_value(t, r, error); return;
    }
    if (entry_context(t,MD_CONTEXT_MEMORY)) return;
    CHECK(t->ready && t->phase == IDLE);
    execution_retain(t,call,r);
    if (!save_signal_mask(t,&t->execution.mask,"get-mask")
            || !shield(t) || !skip(t->pid)) return;
    transfer_begin(t,&t->execution.registers,t->execution.request.args[0],t->metadata.path,
        sizeof(t->metadata.path),MD_TRANSFER_STRING,domain_path_finish);
}

void domain_buffer_control(struct thread *t,const struct supervisor_control_request *control) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_BUFFER_UTS: {
        if (!t->fs.buffer.pool || t->fs.open.fd>=0 || t->phase!=DISPATCHING || control->args[0] || control->args[1]) break;
        long nr=t->execution.request.nr;
        if (nr!=SYS_uname && nr!=SYS_sethostname && nr!=SYS_setdomainname) break;
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (!result && !t->resources.uts) result=-ENOTSUP;
        if (!result && nr==SYS_uname) md_uts_namespace_read(t->resources.uts,span.data);
        else if (!result) {
            result=uts_access(t,nr);
            if (!result) result=md_uts_namespace_set(t->resources.uts,nr==SYS_setdomainname,span.data,t->execution.request.args[1]);
        }
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}

void setns_complete(struct thread *t,int error) {
    CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error));
    struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
    ((struct md_pidfd_frame *)span.data)->result=error;
    buffer_reply(t,MD_PIDFD_DONE);
}

static void setns_filter_begin(struct thread *t) {
    if (entry_require_stop(t,MD_CONTEXT_POLICY,0,setns_filter_begin)) return;
    struct user_pt_regs continuation;
    if (registers(t->pid,&continuation)) pid_filter_begin(t,&continuation,setns_prepared);
}

void setns_prepared(struct thread *t,long error) {
    if (!error && t->resource_change.pid_children_pending && !t->process_op.filter) {
        setns_filter_begin(t);
        return;
    }
    if (!error && t->resource_change.join_mounts) {
        error=md_mount_join_submit(mount_copy_service,t->pid,t->resource_change.join_mounts,&t->descriptors.mount_copy);
        /* EVENT_WAIT: selected namespace root/cwd resolution. Cancellation
         * discards the entire unpublished namespace/credential candidate. */
        if (!error) return;
    }
    setns_complete(t,error);
}

int domain_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r, unsigned long cookie) {
    long nr = call->nr;
    /* Descriptor-table ioctls retain native validation and FD bookkeeping;
     * a managed/offline socket does not give them network semantics. */
    if (cookie==MD_INTERCEPT_FD && nr==SYS_ioctl) return 0;
    if (nr==SYS_reboot && t->phase==IDLE) {
        if (entry_context(t,MD_CONTEXT_PROCESS)) return 1;
        struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
        int error=md_pid_namespace_reboot(space,&t->identity,call->args[0],call->args[1],call->args[2]);
        if (error<0) { return_value(t,r,error); return 1; }
        struct md_pid_identity *init=md_pid_namespace_lookup(space,1);
        pid_t target=md_pid_identity_native(init);
        md_pid_identity_release(init);
        if (target<=0) { return_value(t,r,-ESRCH); return 1; }
        /* Never issue a host reboot. PID 1 death uses the existing namespace
         * descendant owner; parent wait translation retains the Linux reason. */
        if (kill(target,SIGKILL)) {
            return_value(t,r,-errno); return 1;
        }
        md_pid_namespace_reboot_started(space,error);
        if (t->tgid==target) { resume(t->pid,0); return 1; }
        int number=SYS_exit;
        struct iovec call={&number,sizeof(number)};
        r->regs[8]=number; r->regs[0]=0;
        if (set_registers(t->pid,r) && tracee_request(t,PTRACE_SETREGSET,(void *)NT_ARM_SYSTEM_CALL,&call,"namespace-reboot-exit"))
            resume(t->pid,0);
        return 1;
    }
    if (t->ready && t->entered && t->phase==IDLE && cookie==MD_INTERCEPT_OBSERVE && call->instruction_pointer!=RAW_GATE
            && md_seccomp_filter_call(nr,call->args[0],call->args[1])) {
        if (entry_context(t,MD_CONTEXT_POLICY)) return 1;
        /* Revoke existing entries before filter commit, including TSYNC. The
         * guest preflight revokes new registration in its own address space;
         * a protected fork need not grant the supervisor memory-write access. */
        if (!filter_revoke_entry(t,call)) return 1;
        filter_install_begin(t,call,r); return 1;
    }
    if (t->phase==PROCESS_INIT_EXIT && nr==SYS_prctl && call->instruction_pointer==RAW_GATE
            && call->args[0]==PR_SET_CHILD_SUBREAPER && call->args[1]==1
            && !call->args[2] && !call->args[3] && !call->args[4] && !call->args[5]) {
        /* This is the namespace owner's pending birth operation, not a guest
         * request against its restricted root. Kernel filters still apply. */
        resume(t->pid,0); return 1;
    }
    if (t->phase==IDLE && !t->process_op.filter
            && ((nr==SYS_clone || nr==SYS_unshare) && (call->args[0]&CLONE_NEWPID))) {
        if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
        t->process_op.preflight=1;
        execution_begin(t,call,r,"pid-filter-mask");
        return 1;
    }
    if (nr == SYS_socket) {
        int error = md_domain_socket_error((int)call->args[0], (int)call->args[2]);
        if (error) { return_value(t, r, error); return 1; }
    }
    if (t->ready && t->phase==IDLE) {
        struct md_fd_object *object=network_descriptor_call(nr)
            ? md_fd_origins_object(t->resources.files,(int)call->args[0],NULL) : NULL;
        int control=md_network_socket_control(object) || md_network_endpoint_is(object) || md_device_events_is(object)
            || (nr==SYS_ioctl && md_network_interface_ioctl(call->args[1]) && md_network_socket_namespace(object));
        int native_payload=md_socket_payload_native(nr,call->args[4])
            && !md_network_socket_control(object) && !md_device_events_is(object);
        md_fd_object_release(object);
        /* No pathname, peer address or ancillary result needs adaptation.
         * Keep blocking IO at its original PC/mask so signals and a debugger
         * interrupt do not wait for an internal adapter to finish reading. */
        if (native_payload) { native_continue(t); return 1; }
        int devices=nr==SYS_socket && call->args[0]==AF_NETLINK && call->args[2]==NETLINK_KOBJECT_UEVENT
            && md_fs_worker_device_events(filesystem);
        if (control || devices || (nr==SYS_socket && md_network_namespace_managed(t->resources.network))) {
            execution_begin(t,call,r,"network-call-mask");
            return 1;
        }
    }
    if (t->ready && t->phase==IDLE && (nr==SYS_bind || nr==SYS_connect || nr==SYS_listen
            || nr==SYS_setsockopt || nr==SYS_ioctl)) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)call->args[0],NULL);
        int offline=md_network_socket_offline(object); md_fd_object_release(object);
        /* Namespace-mutating ioctls and Unix autobind must not reach Android's
         * network. Ordinary inherited descriptions keep their own authority. */
        if (offline && (nr!=SYS_setsockopt || (call->args[1]==SOL_SOCKET && call->args[2]==SO_PASSCRED))) {
            return_value(t,r,nr==SYS_ioctl ? -ENOTTY : -EOPNOTSUPP); return 1;
        }
    }
    if (t->ready && t->phase==IDLE && nr==SYS_ioctl && (md_terminal_attributes_command((unsigned)call->args[1])
            || md_pidfd_namespace_command((unsigned)call->args[1])
            || md_filesystem_ioctl_command((unsigned)call->args[1]))) {
        execution_begin(t,call,r,"descriptor-ioctl-mask");
        return 1;
    }
    if (nr == SYS_unshare) {
        CHECK(t->phase == IDLE);
        if (!(call->args[0]&CLONE_NEWUSER) && md_resources_namespace_flags(call->args[0])
                && !md_identity_capable(&t->identity,CAP_SYS_ADMIN)) {
            return_value(t,r,-EPERM); return 1;
        }
        fd_begin(t,call,r,0);
        return 1;
    }
    if (nr == SYS_setns) {
        execution_begin(t,call,r,"namespace-entry-mask");
        return 1;
    }
    if (nr==SYS_clone3 && !t->process_op.clone_prepared) {
        if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
        CHECK(t->phase==IDLE);
        execution_begin(t,call,r,"clone-arguments-mask");
        return 1;
    }
    if (md_clone_call(nr)) {
        if (entry_context(t,MD_CONTEXT_BIRTH)) return 1;
        unsigned long flags=call->args[0];
        if (nr==SYS_clone3) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
            flags=((struct clone_args *)span.data)->flags;
        }
        if ((flags&CLONE_PARENT) && md_pid_identity_number(t->resources.pid_group,
                md_pid_identity_namespace(t->resources.pid))==1) {
            return_value(t,r,-EINVAL); return 1;
        }
        if (!md_resources_clone_supported(flags)) {
            return_value(t, r, -EINVAL); return 1;
        }
        if (!(flags&CLONE_NEWUSER) && md_resources_namespace_flags(flags)
                && !md_identity_capable(&t->identity,CAP_SYS_ADMIN)) {
            return_value(t,r,-EPERM); return 1;
        }
        t->process_op.cloning=flags; return 0;
    }
    if (nr == SYS_chroot) {
        if ((admitted_path || virtual_identity) && !md_identity_may_chroot(&t->identity)) return_value(t, r, -EPERM);
        else domain_path_call(t,call,r);
        return 1;
    }
    if (nr==SYS_sethostname || nr==SYS_setdomainname) {
        int error=uts_access(t,nr);
        if (error) {
            return_value(t,r,error); return 1;
        }
        if (cookie==MD_INTERCEPT_DISPATCH) return 0;
    }
    if (!md_domain_restricted(t->resources.fs)) return 0;
    /* FD selectors require adapter admission for the new slot as well as the
     * kernel table mutation. A restricted root does not turn that dispatch
     * into an unobserved native dup, losing the retained object's identity. */
    if (cookie==MD_INTERCEPT_DISPATCH && (nr==SYS_dup || nr==SYS_dup3
            || (nr==SYS_fcntl && ((unsigned)call->args[1]==F_DUPFD
                || (unsigned)call->args[1]==F_DUPFD_CLOEXEC)))) return 0;
    /* These selectors operate on retained descriptions, not paths. Their
     * owners validate typed IO and recheck reused slots before native IO;
     * a restricted root must not revoke an already opened pipe or proc file. */
    if (cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_WATCH) return 0;
    if (t->phase==PROC_OPENING && t->metadata.task_open && cookie==MD_INTERCEPT_NATIVE
            && nr==SYS_openat && (int)call->args[0]==t->metadata.proc_directory
            && call->args[1]==abi.proc_open_path && call->args[2]==t->metadata.request.data.args[2]
            && !call->args[3]
            && md_fd_order_state(&t->metadata.files)==MD_RESOURCE_ORDER_RUNNING) {
        resume(t->pid,0); return 1;
    }
    /* UTS identity is independent of the restricted filesystem. */
    if (nr == SYS_uname && cookie == MD_INTERCEPT_DISPATCH) return 0;
    if (cookie==MD_INTERCEPT_DISPATCH && (md_domain_ipc_call(nr) || nr==SYS_pipe2)) return 0;
    if (nr == SYS_kill || nr == SYS_tgkill || nr == SYS_tkill) {
        long target = (long)call->args[nr == SYS_tgkill ? 1 : 0];
        struct thread *destination = target > 0 ? find_thread(target) : NULL;
        if (destination && destination->born) {
            native_continue(t); return 1;
        }
        return_value(t, r, -EPERM); return 1;
    }
    unsigned long args[6];
    for (unsigned i = 0; i < 6; i++) args[i] = call->args[i];
    if (md_domain_native_call(nr, args)) {
        if (cookie == MD_INTERCEPT_OBSERVE || cookie == MD_INTERCEPT_WATCH
                || cookie==MD_INTERCEPT_FD || cookie==MD_INTERCEPT_PROC || cookie==MD_INTERCEPT_MEMORY) return 0;
        native_continue(t); return 1;
    }
    int error = cookie == MD_INTERCEPT_DISPATCH ? md_domain_path_error(t->resources.fs) : -EPERM;
    if (nr == SYS_chdir) {
        domain_path_call(t,call,r);
        return 1;
    }
    if (error && ++restricted_denials <= 32)
        SUPERVISOR_TRACE("PROBE restricted pid=%d nr=%ld result=%d\n", t->pid, nr, error);
    return_value(t, r, error); return 1;
}
