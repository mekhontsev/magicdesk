#define _GNU_SOURCE
#include "cooperative_channel.h"
#include "adapter_buffer.h"
#include "cgroup.h"
#include "clone_call.h"
#include "control_epoll.h"
#include "control_file.h"
#include "control_ioctl.h"
#include "descriptor_calls.h"
#include "descriptor_inode.h"
#include "device_events.h"
#include "event_wait.h"
#include "fd_capture.h"
#include "fd_client.h"
#include "fd_exchange.h"
#include "fd_filter.h"
#include "fd_filter_program.h"
#include "fd_mutation.h"
#include "fd_object.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "file_open.h"
#include "fs_anchor.h"
#include "fs_buffer.h"
#include "fs_buffer_service.h"
#include "fs_location.h"
#include "fs_mount_internal.h"
#include "fs_operation.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "mount_copy_service.h"
#include "network_internal.h"
#include "network_namespace.h"
#include "network_packet.h"
#include "network_route.h"
#include "pid_calls.h"
#include "pid_descriptor.h"
#include "pid_namespace.h"
#include "proc_file.h"
#include "proc_namespace.h"
#include "process_limits.h"
#include "received_fds.h"
#include "resource_order.h"
#include "signal_pending.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_domain.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_io.h"
#include "supervisor_proc.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "task_helper.h"
#include "task_registry.h"
#include "task_resources.h"
#include <linux/audit.h>
#include <linux/close_range.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
static void fd_policy_release(struct thread *t);
static int resources_unshare_prepare(struct thread *t,unsigned long flags);
static void fd_native_start(struct thread *t);
static void fd_entry_complete(struct thread *t,long result);
static void close_event_complete(struct thread *t);
static void close_return(struct thread *t);
static int close_begin(struct thread *t,const struct user_pt_regs *returned,int errors);
static int network_namespace_capture(void *context,struct md_network_target_reference reference,
        struct md_network_namespace **out);
static void control_capture_step(struct thread *t,unsigned command,long value);
static void pidfd_result(struct thread *t,struct md_pid_identity *identity,pid_t native,long error);
static void pidfd_capture_step(struct thread *t,unsigned command,long value);
static void pidfd_begin(struct thread *t);

void fd_return(struct thread *t,struct user_pt_regs *regs,long result) {
    if (t->entry.control && t->entry.execution) control_return(t,NULL,result,0);
    else return_value(t,regs,result);
}

static void fd_policy_release(struct thread *t) {
    if (t->descriptors.policy_order.queue && md_resource_order_cancel(&t->descriptors.policy_order)==1)
        CHECK(!md_resource_order_complete(&t->descriptors.policy_order));
}

void fd_discard(struct thread *t) {
    /* A temporary close may borrow this descriptor turn. Reconcile its native
     * receipt before cancellation admits another mutation of the same slot. */
    task_action_discard(t);
    /* Fork owns the address-space turn even before kernel admission. Early
     * quota/allocation failure must retire it just like a completed clone. */
    if (t->memory_op.action==MEMORY_FORK) {
        if (md_resource_order_cancel(&t->memory_op.order)==1)
            CHECK(!md_resource_order_complete(&t->memory_op.order));
        t->memory_op.action=0;
    }
    md_process_member_release(t->process_op.reservation); t->process_op.reservation=NULL;
    retry_clear(t,RETRY_FD);
    md_fd_selector_release(t->descriptors.fork_selector); t->descriptors.fork_selector=NULL;
    for (unsigned i=0;i<2;++i) md_fd_origins_finish(&t->descriptors.pair_updates[i],-1);
    md_pid_identity_release(t->process_op.pid_reservation); t->process_op.pid_reservation=NULL;
    md_mount_copy_cancel(&t->descriptors.mount_copy);
    if (t->descriptors.task) {
        md_fs_buffer_cancel(&t->fs.call);
        t->descriptors.task=t->descriptors.running=0;
        return;
    }
    if (t->descriptors.running && t->descriptors.cwd && t->descriptors.mutation.nr!=SYS_openat)
        CHECK(!md_domain_set_cwd(t->descriptors.fs_order.fs,NULL));
    md_fs_anchor_release(t->descriptors.origin); t->descriptors.origin=NULL;
    if (t->descriptors.fs_order.fs && md_fs_order_cancel(&t->descriptors.fs_order)==1)
        CHECK(!md_fs_order_complete(&t->descriptors.fs_order));
    if (t->descriptors.running) CHECK(!md_fd_mutation_abandon(&t->descriptors.mutation,t->descriptors.order.table));
    else md_fd_mutation_discard(&t->descriptors.mutation);
    if (t->descriptors.order.table) {
        int running=md_fd_order_cancel(&t->descriptors.order);
        /* Called only after a terminal task event, never merely on timeout. */
        if (running==1) {
            if (t->descriptors.job==MD_FD_RECEIVE) CHECK(!md_fd_exchange_received(t->descriptors.exchange));
            CHECK(!md_fd_order_complete(&t->descriptors.order));
        }
    }
    t->descriptors.running=0; t->descriptors.job=0; t->descriptors.cwd=0; t->descriptors.capture=0;
    t->descriptors.duplicate_attempted=0; t->descriptors.duplicate_result=-1;
    md_fd_exchange_close(fd_exchanges,t->descriptors.exchange); t->descriptors.exchange=NULL;
    fd_policy_release(t);
}

void fd_finish(struct thread *t, long result) {
    if (t->descriptors.task) {
        /* The adapter must acknowledge execution. Fatal filter exits can
         * expose unchanged arguments as if they were a syscall result. */
        t->descriptors.task=t->descriptors.running=0;
        return;
    }
    long nr=t->descriptors.mutation.nr;
    if (md_clone_call(nr) && t->memory_op.action==MEMORY_FORK) {
        CHECK(!md_resource_order_complete(&t->memory_op.order)); t->memory_op.action=0;
    }
    int exec=nr==SYS_execve || nr==SYS_execveat;
    if (exec || nr==SYS_unshare || (nr==SYS_close_range && (t->descriptors.mutation.args[2]&CLOSE_RANGE_UNSHARE)))
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,result));
    if (md_clone_call(nr))
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ECANCELED));
    if (md_clone_call(nr)) {
        md_process_member_release(t->process_op.reservation); t->process_op.reservation=NULL;
        md_pid_identity_release(t->process_op.pid_reservation); t->process_op.pid_reservation=NULL;
        md_fd_selector_release(t->descriptors.fork_selector); t->descriptors.fork_selector=NULL;
    }
    if (exec && !result) md_fd_origins_exec_collect(t->resources.files,&t->close.closed);
    if (t->descriptors.cwd && nr!=SYS_openat && !result)
        CHECK(!md_domain_set_cwd(t->descriptors.fs_order.fs,md_domain_restricted(t->descriptors.fs_order.fs) ? NULL : t->descriptors.origin));
    md_fs_anchor_release(t->descriptors.origin); t->descriptors.origin=NULL;
    CHECK(!md_fd_mutation_finish_closed(&t->descriptors.mutation,t->resources.files,result,&t->close.closed));
    if (t->descriptors.fs_order.fs) CHECK(!md_fs_order_complete(&t->descriptors.fs_order));
    if (t->descriptors.job==MD_FD_DUPLICATE_BEGIN)
        t->descriptors.duplicate_result=nr==SYS_close || result<0 ? -1 : (int)result;
    else CHECK(!md_fd_order_complete(&t->descriptors.order));
    t->descriptors.running=0; t->descriptors.cwd=0; t->descriptors.capture=0;
    fd_policy_release(t);
}

void mount_copy_complete(struct md_mount_copy_request *request, int actor, int error) {
    struct thread *t=find_thread(actor);
    if (!t || t->descriptors.mount_copy!=request) return;
    t->descriptors.mount_copy=NULL;
    if (!error) error=md_mount_copy_take(request,&t->resource_change.next.mounts,&t->resource_change.next.fs);
    if (t->descriptors.job==MD_FD_USE && t->execution.request.nr==SYS_setns) {
        setns_complete(t,error); return;
    }
    if (error) {
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error));
        fd_discard(t); fd_return(t,&t->descriptors.registers,error); return;
    }
    fd_native_start(t);
}

static int resources_unshare_prepare(struct thread *t,unsigned long flags) {
    int error=md_resources_unshare_prepare(&t->resources,flags,&t->identity,&t->resource_change);
    if (error==-ENOSPC && (flags&CLONE_NEWUSER) && md_task_registry_has_retired(tasks)) {
        /* A native wait may reap after the exit-time collection. Retry only
         * on quota pressure, preserving in-flight PID translation owners. */
        pid_collection_pending=1; pid_collect();
        error=md_resources_unshare_prepare(&t->resources,flags,&t->identity,&t->resource_change);
    }
    return error;
}

void fd_start(struct thread *t) {
    retry_clear(t,RETRY_FD);
    if (!t->descriptors.order.table && t->descriptors.policy_order.queue) {
        enum md_resource_order_state state=md_resource_order_state(&t->descriptors.policy_order);
        if (state==MD_RESOURCE_ORDER_READY) CHECK(!md_resource_order_start(&t->descriptors.policy_order));
        else if (state!=MD_RESOURCE_ORDER_RUNNING) { retry_wait(t,RETRY_FD); return; }
        CHECK(md_fd_order_begin(t->resources.files,&t->descriptors.order,fd_wakeup)>=0);
    }
    if (md_fd_order_state(&t->descriptors.order)==MD_RESOURCE_ORDER_QUEUED
            || md_fs_order_state(&t->descriptors.fs_order)==MD_RESOURCE_ORDER_QUEUED) {
        retry_wait(t,RETRY_FD); return;
    }
    if (md_fd_order_state(&t->descriptors.order)==MD_RESOURCE_ORDER_READY)
        CHECK(!md_fd_order_start(&t->descriptors.order));
    struct user_pt_regs *r=&t->descriptors.registers;
    const struct seccomp_data *call=&t->descriptors.call;
    if (t->descriptors.job==MD_FD_DUPLICATE_BEGIN) {
        int error=md_fd_origins_validate(&t->descriptors.order,t->descriptors.request.args[0]);
        if (error) {
            CHECK(!md_fd_order_complete(&t->descriptors.order)); t->descriptors.job=0;
            control_return(t,r,error,0);
            return;
        }
    }
    if ((!t->descriptors.job || t->descriptors.job==MD_FD_DUPLICATE_BEGIN) && !fd_loans_ready(t)) return;
    int needs_fs=t->descriptors.job==MD_FD_INPUT ? (int)t->descriptors.request.args[1]==AT_FDCWD || (int)t->descriptors.request.args[2]==AT_FDCWD
        : t->descriptors.job==MD_FD_USE ? t->execution.request.nr==SYS_setns
        : !t->descriptors.job && (t->descriptors.cwd || md_clone_call(call->nr)
            || (call->nr==SYS_unshare && (call->args[0]&(CLONE_FS|CLONE_NEWNS|CLONE_NEWUSER))));
    if (needs_fs) {
        if (!t->descriptors.fs_order.fs) {
            int ready=md_fs_order_begin(t->resources.fs,&t->descriptors.fs_order,fd_wakeup);
            CHECK(ready>=0);
            /* EVENT_WAIT: the CLONE_FS owner completes its exact mutation;
             * terminal task cleanup cancels both ordered resource records. */
            if (!ready) { retry_wait(t,RETRY_FD); return; }
        }
        CHECK(!md_fs_order_start(&t->descriptors.fs_order));
    }
    if (t->descriptors.job) {
        long result=0;
        if (t->descriptors.job==MD_FD_DUPLICATE_BEGIN) {
            struct md_fd_observation view;
            result=md_fd_origins_observe(t->resources.files,(int)t->execution.request.args[0],&view);
            if (!result) result=view.selectors;
        }
        if (t->descriptors.job==MD_FD_PAIR_BEGIN) {
            struct md_fd_object *inode=t->execution.request.nr==SYS_pipe2
                ? md_descriptor_inode_new(&t->identity,S_IFIFO|0600) : md_network_socket_new(t->resources.network);
            struct md_fd_object *objects[2]={inode,inode};
            result=inode ? md_fd_origins_prepare_batch(t->resources.files,objects,
                t->execution.request.args[1]&O_CLOEXEC ? FD_CLOEXEC : 0,2,t->descriptors.pair_updates) : -ENOMEM;
            md_fd_object_release(inode);
        }
        if (t->descriptors.job==MD_FD_INPUT) {
            int bases[2]={(int)t->descriptors.request.args[1],(int)t->descriptors.request.args[2]};
            struct md_fs_anchor *cwd=NULL;
            if (needs_fs) {
                struct md_fs_location root;
                md_domain_paths(t->descriptors.fs_order.fs,&root,&cwd);
            }
            result=md_fd_exchange_begin(fd_exchanges,t->pid,t->resources.files,bases,cwd,&t->descriptors.exchange);
            md_fs_anchor_release(cwd);
            if (t->descriptors.fs_order.fs) CHECK(!md_fs_order_complete(&t->descriptors.fs_order));
            if (!result) result=(long)md_fd_exchange_id(t->descriptors.exchange);
        }
        if (result<0) {
            CHECK(!md_fd_order_complete(&t->descriptors.order)); t->descriptors.job=0;
        }
        control_return(t,r,result,t->descriptors.request.args[0]);
        return;
    }
    int error=0;
    if (call->nr==SYS_execve || call->nr==SYS_execveat)
        error=md_resources_exec_prepare(&t->resources,t->identity.user_namespace,&t->resource_change);
    else if (call->nr==SYS_unshare
            || (call->nr==SYS_close_range && (call->args[2]&CLOSE_RANGE_UNSHARE)))
        error=resources_unshare_prepare(t,call->nr==SYS_unshare ? call->args[0] : CLONE_FILES);
    if (md_clone_call(call->nr) && md_resources_namespace_flags(t->process_op.cloning))
        error=resources_unshare_prepare(t,md_resources_namespace_flags(t->process_op.cloning));
    if (!error && (t->resource_change.copy_mounts || t->resource_change.check_root)) {
        error=md_mount_copy_submit(mount_copy_service,t->pid,t->resources.mounts,t->resources.fs,
            t->resource_change.identity_pending ? t->resource_change.identity.user_namespace : t->identity.user_namespace,
            t->resource_change.copy_mounts,t->resource_change.check_root,&t->descriptors.mount_copy);
        /* EVENT_WAIT: worker publishes an independent mount/FS candidate;
         * terminal cancellation retires it without changing the task's view. */
        if (!error) return;
    }
    if (error) {
        CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error));
        fd_discard(t); fd_return(t,r,error); return;
    }
    fd_native_start(t);
}

static void fd_native_start(struct thread *t) {
    struct user_pt_regs *r=&t->descriptors.registers;
    const struct seccomp_data *call=&t->descriptors.call;
    if (!t->descriptors.job && !t->descriptors.cwd && !t->descriptors.capture && t->phase==IDLE
            && call->nr==SYS_fcntl && (unsigned)call->args[1]==F_SETFD
            && !t->debug.options && !t->debug.call_active && !t->debug.step_call
            && !md_debugger_traced(debugger,t->pid) && md_fd_origins_exclusive(&t->descriptors.order)) {
        struct md_fd_native native;
        if (md_fd_origins_native(&t->descriptors.order,(int)call->args[0],&native)
                && (call->args[2]&FD_CLOEXEC)==native.flags) {
            /* Kernel denial or success leaves the same metadata. No peer can
             * change this private table before the admitted call executes. */
            CHECK(!md_fd_order_complete(&t->descriptors.order));
            if (statistics) ++statistics->unchanged_fd_flags;
            native_continue(t); return;
        }
    }
    int admission=0;
    if (md_clone_call(call->nr) && !t->process_op.reservation) {
        admission=md_process_member_fork(t->resources.process,&t->identity,t->process_op.cloning,&t->process_op.reservation);
        if (admission==-EAGAIN && md_task_registry_has_retired(tasks)) {
            md_task_registry_collect_limits(tasks,pid_gone);
            admission=md_process_member_fork(t->resources.process,&t->identity,t->process_op.cloning,&t->process_op.reservation);
        }
    }
    else if (call->nr==SYS_execve || call->nr==SYS_execveat) {
        admission=md_process_member_exec(t->resources.process);
        if (admission==-EAGAIN && md_task_registry_has_retired(tasks)) {
            md_task_registry_collect_limits(tasks,pid_gone);
            admission=md_process_member_exec(t->resources.process);
        }
    }
    if (!admission && md_clone_call(call->nr) && !(t->process_op.cloning&CLONE_INTO_CGROUP)
            && t->resources.cgroup && !t->resource_change.next.cgroup) {
        t->resource_change.next.cgroup=md_cgroup_member_fork(t->resources.cgroup);
        if (!t->resource_change.next.cgroup) admission=-errno;
    }
    if (admission) {
        md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,admission);
        fd_discard(t); fd_return(t,r,admission); return;
    }
    if (md_clone_call(call->nr) && !t->process_op.pid_reservation) {
        struct md_pid_namespace *space=t->resource_change.next.pid_children
            ? t->resource_change.next.pid_children : t->resources.pid_children;
        struct md_clone_request *request=NULL;
        if (t->process_op.clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span)); request=span.data;
        }
        unsigned count=request ? request->args.set_tid_size : 0;
        unsigned depth=md_pid_namespace_depth(space), selected=count<depth ? count : depth;
        const struct md_identity *id=t->resource_change.identity_pending ? &t->resource_change.identity : &t->identity;
        int error=(t->process_op.cloning&CLONE_THREAD) && space!=md_pid_identity_namespace(t->resources.pid)
            ? -EINVAL : count>depth+1 ? -EINVAL
            : md_pid_identity_prepare_selected(space,id,request ? request->tids : NULL,selected,&t->process_op.pid_reservation);
        if (!error && request && (t->process_op.cloning&CLONE_INTO_CGROUP))
            error=md_resources_clone_cgroup_prepare(&t->resources,t->process_op.cloning,t->process_op.clone_cgroup,
                &t->identity,&t->resource_change);
        if (error) {
            md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,error);
            fd_discard(t); fd_return(t,r,error); return;
        }
    }
    unsigned long args[6];
    for (unsigned i=0;i<6;++i) args[i]=call->args[i];
    int error=0;
    if (!error) error=md_fd_mutation_prepare(t->resources.files,call->nr,args,&t->descriptors.mutation);
    if (!error && call->nr==SYS_epoll_create1) {
        struct md_control_epoll *queue=md_control_epoll_new();
        error=queue ? md_fd_origins_prepare_object(t->resources.files,md_control_epoll_object(queue),
            call->args[0]&EPOLL_CLOEXEC ? FD_CLOEXEC : 0,&t->descriptors.mutation.update) : -ENOMEM;
        md_control_epoll_release(queue);
    }
    if (!error && call->nr==SYS_pidfd_open) {
        struct thread *target=find_thread((pid_t)call->args[0]);
        if (target && target->resources.pid) {
            struct md_fd_object *object=md_pid_descriptor_new(target->resources.pid_group);
            error=object ? md_fd_origins_prepare_object(t->resources.files,object,FD_CLOEXEC,&t->descriptors.mutation.update) : -ENOMEM;
            md_fd_object_release(object);
        }
    }
    if (!error && t->descriptors.cwd && call->nr==SYS_fchdir)
        t->descriptors.origin=md_fd_origins_get(t->resources.files,(int)call->args[0],NULL);
    if (!error && t->descriptors.cwd && call->nr==SYS_chdir && t->fs.buffer.pool
            && t->fs.open.task_path==MD_FS_TASK_CHDIR
            && call->args[0]==md_memory_buffer_address(t->memory,&t->fs.buffer)+offsetof(struct md_fs_buffer_frame,source))
        t->descriptors.origin=md_fs_anchor_retain(t->fs.open.origin);
    if (!error && t->descriptors.capture) {
        if ((int)call->args[4]==MD_FS_BUFFER_SOURCE) t->descriptors.origin=md_fs_anchor_retain(t->fs.open.origin);
        else if ((int)call->args[4]==AT_FDCWD) {
            struct md_fs_location root;
            md_domain_paths(t->descriptors.fs_order.fs,&root,&t->descriptors.origin);
        } else t->descriptors.origin=md_fd_origins_get(t->resources.files,(int)call->args[4],NULL);
        struct md_fd_object *object=(int)call->args[4]==MD_FS_BUFFER_SOURCE
            ? md_fs_buffer_open_object(&t->fs.open) : md_fs_anchor_object(t->descriptors.origin);
        error=md_fd_origins_prepare_object(t->resources.files,object,
            call->args[2]&O_CLOEXEC ? FD_CLOEXEC : 0,&t->descriptors.mutation.update);
        SUPERVISOR_TRACE("PROBE path-capture pid=%d base=%d known=%d\n",t->pid,(int)call->args[4],t->descriptors.origin!=NULL);
    }
    if (error) {
        md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ECANCELED);
        fd_discard(t); fd_return(t,r,error); return;
    }
    if (md_clone_call(call->nr)) {
        /* Snapshot before kernel clone. Concurrent TSYNC may reach the parent
         * after the child copied its filters; a later metadata snapshot would
         * incorrectly claim that the child inherited that new selector. */
        t->descriptors.fork_selector=md_fd_selector_fork(t->descriptors.selector,!!(t->process_op.cloning&CLONE_THREAD));
        if (!t->descriptors.fork_selector) {
            md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ENOMEM);
            fd_discard(t); fd_return(t,r,-ENOMEM); return;
        }
        execution_retain(t,call,r); t->phase=OBSERVING;
        uintptr_t parent=call->args[2];
        if (t->process_op.clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
            parent=((struct clone_args *)span.data)->parent_tid;
        }
        t->process_op.parent_address=t->process_op.pid_reservation && (t->process_op.cloning&CLONE_PARENT_SETTID) ? parent : 0;
    }
    if (t->process_op.clone_prepared || t->resource_change.namespaces || (call->nr==SYS_clone && (t->process_op.cloning&CLONE_UNTRACED))) {
        struct user_pt_regs native=*r;
        if (t->process_op.clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
            struct clone_args *args=span.data;
            unsigned depth=md_pid_namespace_depth(t->resource_change.next.pid_children
                ? t->resource_change.next.pid_children : t->resources.pid_children);
            unsigned selected=args->set_tid_size<depth ? args->set_tid_size : depth;
            args->flags&=~((uint64_t)t->resource_change.namespaces|CLONE_UNTRACED|CLONE_INTO_CGROUP);
            native.regs[0]=md_memory_buffer_address(t->memory,&t->fs.buffer);
            args->set_tid_size-=selected;
            args->set_tid=args->set_tid_size ? native.regs[0]+offsetof(struct md_clone_request,tids)
                +selected*sizeof(pid_t) : 0;
            native.regs[1]=sizeof(*args);
            CHECK(native.regs[0]);
        } else native.regs[0]&=~(uint64_t)t->resource_change.namespaces;
        /* Guest debugger policy cannot detach a child from runtime ownership. */
        if (call->nr==SYS_clone) native.regs[0]&=~(uint64_t)CLONE_UNTRACED;
        if (call->nr==SYS_unshare && t->resource_change.copy_mounts) native.regs[0]|=CLONE_FS;
        if (call->nr==SYS_unshare && t->resource_change.identity_pending) native.regs[0]|=CLONE_FS|CLONE_THREAD;
        if (call->nr==SYS_unshare && (t->resource_change.namespaces&CLONE_NEWPID)) native.regs[0]|=CLONE_THREAD;
        if (call->nr==SYS_unshare && (t->resource_change.namespaces&CLONE_NEWIPC)) native.regs[0]|=CLONE_SYSVSEM;
        if (!set_registers(t->pid,&native)) return;
    }
    /* Argument replay is internal. Restore the application's mask only after
     * admission, immediately before kernel clone copies it into the child. */
    if (t->process_op.clone_prepared && !restore_mask(t)) return;
    t->descriptors.running=1;
    if (t->entry.control) {
        uint64_t args[6]; memcpy(args,call->args,sizeof(args));
        entry_effect(t,call->nr,args,fd_entry_complete);
    } else resume(t->pid,0);
}

void fd_begin(struct thread *t,const struct seccomp_data *call,const struct user_pt_regs *r,unsigned operation) {
    long nr=call->nr;
    /* Birth, FS topology and file-table unsharing change the kernel carrier.
     * Ordinary slot mutations need only the ordered effect and its receipt. */
    if ((md_clone_call(nr) || nr==SYS_execve || nr==SYS_execveat || nr==SYS_unshare
            || operation==MD_INTERCEPT_CAPTURE || operation==MD_INTERCEPT_CWD
            || (nr==SYS_close_range && (call->args[2]&CLOSE_RANGE_UNSHARE)))
            && entry_context(t,md_clone_call(nr) ? MD_CONTEXT_BIRTH
                : nr==SYS_execve || nr==SYS_execveat ? MD_CONTEXT_EXEC : MD_CONTEXT_NAMESPACE)) return;
    if (t->descriptors.job==MD_FD_DUPLICATE_BEGIN) {
        /* The adapter already owns discovery/admission and the table turn.
         * Observe its exact duplicate or rollback close under that same turn;
         * unrelated native mutations cannot borrow it. */
        int duplicate=!t->descriptors.duplicate_attempted && nr==t->execution.request.nr;
        unsigned arguments=nr==SYS_dup ? 1 : 3;
        if (duplicate) for (unsigned i=0;i<arguments;++i)
            if (call->args[i]!=t->execution.request.args[i]) duplicate=0;
        int rollback=t->descriptors.duplicate_attempted && t->descriptors.duplicate_result>=0
            && nr==SYS_close && (int)call->args[0]==t->descriptors.duplicate_result;
        if (operation!=MD_INTERCEPT_FD || t->phase!=DISPATCHING || t->descriptors.running
                || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING || (!duplicate && !rollback)) {
            struct user_pt_regs rejected;
            if (r) rejected=*r;
            fd_return(t,r ? &rejected : NULL,-EPERM); return;
        }
        t->descriptors.duplicate_attempted=1; descriptor_retain(t,call,r);
        fd_native_start(t); return;
    }
    if (operation==MD_INTERCEPT_CWD && nr==SYS_chdir
            && md_fs_buffer_task_pending(t->fs.call)) {
        CHECK(!t->descriptors.order.table && !t->descriptors.running && t->phase==DISPATCHING);
        int error=call->args[0]!=md_memory_buffer_address(t->memory,&t->fs.buffer)
            +offsetof(struct md_fs_buffer_frame,source) ? -EPERM : md_fs_buffer_task_start(t->fs.call);
        descriptor_retain(t,call,r);
        if (error) { return_value(t,&t->descriptors.registers,error); return; }
        t->descriptors.task=t->descriptors.running=1;
        resume(t->pid,0); return;
    }
    if (!t->resources.files) { native_continue(t); return; }
    CHECK(!t->descriptors.order.table && !t->descriptors.running);
    t->descriptors.job=0;
    t->descriptors.capture=operation==MD_INTERCEPT_CAPTURE;
    if (t->descriptors.capture && !t->terminal_observation) {
        /* Retain terminal registers independently of a guest debugger. Enable
         * once per task lifetime, not around each native descriptor operation. */
        t->terminal_observation=1; t->trace_options_dirty=1;
    }
    t->descriptors.cwd=operation==MD_INTERCEPT_CWD || (t->descriptors.capture && (int)call->args[4]==AT_FDCWD);
    descriptor_retain(t,call,r);
    if (md_memory_readonly_buffers(t->memory) && !t->memory_op.order.queue
            && (md_clone_call(nr) || nr==SYS_execve || nr==SYS_execveat)) {
        CHECK(!t->descriptors.policy_order.queue);
        CHECK(md_memory_order_begin(t->memory,&t->descriptors.policy_order,fd_wakeup)>=0);
        /* EVENT_WAIT: filter commit/publication precedes birth or exec. Birth
         * releases at its event, not the later vfork return; the child may exec
         * while its parent is still waiting. Native data IO acquires no turn. */
        fd_start(t); return;
    }
    int ready=md_fd_order_begin(t->resources.files,&t->descriptors.order,fd_wakeup);
    CHECK(ready>=0);
    if (ready) fd_start(t); else retry_wait(t,RETRY_FD);
    /* EVENT_WAIT: another operation owns this file table. fd_ready signals its
     * exact completion; the launch deadline cancels the owned process tree. */
}

int fd_close_compact(struct thread *t,unsigned long cookie,const struct ptrace_syscall_info *info) {
    if ((!(cookie&MD_INTERCEPT_CLOSE) && cookie!=MD_INTERCEPT_FD)
            || t->phase!=IDLE || t->descriptors.job || t->descriptors.running || t->descriptors.order.table
            || t->descriptors.fs_order.fs || !t->resources.files
            || t->debug.options || t->debug.call_active || t->debug.step_call
            || md_debugger_traced(debugger,t->pid) || md_fd_origins_has_loans(t->resources.files)) return 0;
    int number;
    if (info) {
        if (info->op!=PTRACE_SYSCALL_INFO_SECCOMP || info->arch!=AUDIT_ARCH_AARCH64
                || info->seccomp.nr!=SYS_close) return 0;
        number=(int)info->seccomp.args[0];
    } else {
        /* Only our own filter can encode the descriptor. An application TRACE
         * cookie is not authority, even if it happens to match this encoding. */
        if (t->seccomp.application_filter || !(cookie&MD_INTERCEPT_CLOSE)) return 0;
        number=(int)(cookie&MD_INTERCEPT_CLOSE_MAX);
    }
    int ready=md_fd_order_begin(t->resources.files,&t->descriptors.order,fd_wakeup);
    CHECK(ready>=0);
    if (!ready) { CHECK(!md_fd_order_cancel(&t->descriptors.order)); return 0; }
    CHECK(!md_fd_order_start(&t->descriptors.order));
    if (md_fd_origins_close_untracked(&t->descriptors.order,number)) {
        CHECK(!md_fd_order_complete(&t->descriptors.order));
        if (statistics) { ++statistics->untracked_close; ++statistics->calls[SYS_close].trace; }
        resume(t->pid,0); return 1;
    }
    /* No register rewrite or helper is needed. Keep the same table turn until
     * the actual close result, including EBADF and late writeback errors. */
    unsigned long args[6]={(unsigned long)(long)number};
    CHECK(!md_fd_mutation_prepare(t->resources.files,SYS_close,args,&t->descriptors.mutation));
    t->descriptors.cwd=t->descriptors.capture=0; t->descriptors.running=1;
    if (statistics) { ++statistics->compact_close; ++statistics->calls[SYS_close].trace; }
    resume(t->pid,0); return 1;
}

int fd_duplicate_prepared(struct thread *t,const struct seccomp_data *call,unsigned long cookie) {
    long nr=call->nr;
    if (cookie!=MD_INTERCEPT_DISPATCH || t->phase!=IDLE || !t->ready
            || !t->resources.files || t->descriptors.job || t->descriptors.running || t->descriptors.order.table
            || t->descriptors.fs_order.fs || t->debug.options || t->debug.call_active || t->debug.step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    if (nr!=SYS_dup && nr!=SYS_dup3 && !(nr==SYS_fcntl
            && ((unsigned)call->args[1]==F_DUPFD || (unsigned)call->args[1]==F_DUPFD_CLOEXEC))) return 0;
    /* All possible destinations already have every provider selector. There
     * is no adapter discovery or filter installation left to do. The normal
     * mutation owner still serializes the table, retires loans and publishes
     * the copied authority only after the actual kernel result. */
    return md_fd_selector_has_mask(t->descriptors.selector,MD_FD_SANDBOX_SELECTORS,-1)==1;
}

int fd_native_classify(struct thread *t,const struct seccomp_data *call,unsigned cookie) {
    if (call->arch!=AUDIT_ARCH_AARCH64 || !md_descriptor_native_candidate(call->nr,(unsigned)call->args[1])
            || t->descriptors.job || t->descriptors.running || t->descriptors.order.table || t->descriptors.fs_order.fs
            || t->debug.options || t->debug.call_active || t->debug.step_call
            || md_debugger_traced(debugger,t->pid)) return 0;
    unsigned long nr=call->nr;
    int poll=nr==SYS_ppoll || nr==SYS_pselect6;
    int epoll=nr==SYS_epoll_pwait || nr==SYS_epoll_pwait2;
    if (t->signals.host && ((poll && call->args[nr==SYS_ppoll ? 3 : 5])
            || (epoll && call->args[4]))) return 0;
    if (epoll ? cookie!=MD_INTERCEPT_READINESS && cookie!=MD_INTERCEPT_DISPATCH
            : poll ? cookie!=MD_INTERCEPT_DISPATCH
            : cookie!=MD_INTERCEPT_PROC && cookie!=MD_INTERCEPT_WATCH) return 0;
    struct md_fd_order turn={0};
    int ready=md_fd_order_begin(t->resources.files,&turn,fd_wakeup);
    CHECK(ready>=0);
    if (!ready) { CHECK(!md_fd_order_cancel(&turn)); return 0; }
    CHECK(!md_fd_order_start(&turn));
    /* The sole table owner cannot replace the slot before its continuation. No
     * turn is held across blocking IO, and an absent annotation is no proof.
     * Read actual syscall arguments, never trust an application's TRACE data. */
    /* Poll already classifies under this turn before native fallback. If no
     * object can provide readiness, the same decision needs no guest vector.
     * The kernel retains pointer, limit, timeout and signal-mask validation. */
    int native=0,probe=0;
    if (epoll) {
        struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)call->args[0],NULL);
        int pure=md_control_epoll_native_wait(object);
        native=pure
            && (!md_control_epoll_from_object(object) || (nr==SYS_epoll_pwait && !(int)call->args[3]));
        probe=pure && !native && nr==SYS_epoll_pwait && t->phase==IDLE
            && !call->args[4] && !t->seccomp.application_filter
            && !t->machine.syscall_exit && !t->machine.retry_svc;
        md_fd_object_release(object);
    } else native=poll ? md_fd_origins_native_poll(&turn) : md_fd_origins_exclusive(&turn)
        && md_fd_origins_native_io(&turn,(int)call->args[0]);
    if (!native && nr==SYS_ppoll) {
        /* Common short vectors need no adapter round trip merely because an
         * unrelated FD has a provider. As in the full poll path, classification
         * ends before the kernel wait; the kernel validates the original input.
         * Failed/partial remote reads and larger vectors retain that full path. */
        struct pollfd items[32];
        uint64_t count=call->args[1];
        if (count<=sizeof(items)/sizeof(*items)) {
            struct iovec local={items,count*sizeof(*items)},
                remote={(void *)(uintptr_t)call->args[0],local.iov_len};
            if (!count || process_vm_readv(t->pid,&local,1,&remote,1,0)==(ssize_t)local.iov_len)
                native=md_fd_origins_native_poll_vector(&turn,items,count);
        }
    }
    CHECK(!md_fd_order_complete(&turn));
    return native ? 1 : probe ? 2 : 0;
}
int fd_native_io(struct thread *t,const struct ptrace_syscall_info *info) {
    struct seccomp_data call=syscall_observation(info);
    int action=fd_native_classify(t,&call,info->seccomp.ret_data);
    if (action==2) {
        /* A ready native queue needs neither an adapter stack nor a captured
         * descriptor. Probe in the caller so signalfd and the application's
         * output pointer retain kernel semantics. An empty result has no
         * effect and falls through to owned blocking admission. */
        struct user_pt_regs regs;
        if (!registers(t->pid,&regs)) return 1;
        execution_retain(t,&call,&regs); t->execution.epoll_started=md_event_now();
        regs.regs[3]=0;
        if (set_registers(t->pid,&regs)) { t->phase=EPOLL_PROBE; resume(t->pid,0); }
        return 1;
    }
    if (!action) return 0;
    if (statistics) { ++statistics->native_io; ++statistics->calls[call.nr].trace; }
    /* An original-site call still passes the kernel's application filter.
     * Retain its exit across TSYNC: interrupting a blocked read would restart
     * it at that site under a newer policy. Resident native calls use the
     * admitted gate and have no exit observer. */
    if (t->phase==IDLE && t->seccomp.application_filter) {
        uint64_t args[6]; memcpy(args,call.args,sizeof(args));
        if (md_execution_native_context(call.nr,args)==MD_CONTEXT_WAIT)
            t->phase=NATIVE_WAIT;
    }
    resume(t->pid,0); return 1;
}

void fd_control(struct thread *t,const struct supervisor_control_request *control,struct user_pt_regs *r) {
    unsigned operation=(unsigned)control->command;
    uint64_t id=control->args[0];
    long result=0;
    uintptr_t extra=control->args[0];
    if (operation==MD_FD_KIND || operation==MD_FD_SELECTORS) {
        if (t->phase!=DISPATCHING || id || (long)control->args[1]<INT_MIN
                || (long)control->args[1]>INT_MAX || control->args[2]) result=-EINVAL;
        else {
            if (operation==MD_FD_SELECTORS) {
                struct md_fd_observation view;
                result=md_fd_origins_observe(t->resources.files,(int)control->args[1],&view);
                extra=result ? 0 : view.generation;
                if (!result) result=view.selectors;
            } else {
                struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)control->args[1],NULL);
                struct md_proc_file *file=md_proc_file_from_object(object);
                result=md_proc_file_filesystem_paths(file) ? MD_DESCRIPTOR_FILESYSTEM
                    : file ? md_proc_file_kind(file)+1 : md_descriptor_inode_is(object) ? MD_DESCRIPTOR_ANONYMOUS : 0;
                md_fd_object_release(object);
            }
        }
    } else if (operation==MD_FD_DUPLICATE_BEGIN) {
        long nr=t->execution.request.nr;
        unsigned command=(unsigned)t->execution.request.args[1];
        int duplicate=nr==SYS_dup || nr==SYS_dup3
            || (nr==SYS_fcntl && (command==F_DUPFD || command==F_DUPFD_CLOEXEC));
        if (t->phase!=DISPATCHING || !duplicate || !id || control->args[1] || control->args[2]
                || t->descriptors.order.table || t->descriptors.running || t->descriptors.job) result=-EINVAL;
        else {
            if (r) t->descriptors.registers=*r;
            t->descriptors.request=*control; t->descriptors.job=operation;
            t->descriptors.duplicate_attempted=0; t->descriptors.duplicate_result=-1;
            int ready=md_fd_order_begin(t->resources.files,&t->descriptors.order,fd_wakeup);
            if (ready<0) { t->descriptors.job=0; result=ready; }
            else { if (ready) fd_start(t); else retry_wait(t,RETRY_FD); return; }
        }
    } else if (operation==MD_FD_DUPLICATE_END) {
        if (id || control->args[1] || control->args[2] || t->descriptors.job!=MD_FD_DUPLICATE_BEGIN || t->descriptors.running
                || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            CHECK(!md_fd_order_complete(&t->descriptors.order)); t->descriptors.job=0;
            t->descriptors.duplicate_attempted=0; t->descriptors.duplicate_result=-1;
        }
    } else if (operation==MD_FD_SELECTOR_EXIT) {
        if (t->phase!=DISPATCHING || !id || id>INT_MAX || control->args[1] || control->args[2]) result=-EINVAL;
        else if (md_task_helper_retiring(helpers,t->tgid,(pid_t)id)) {
            /* EVENT_WAIT: the exact helper's terminal wait event releases
             * this caller. The launch deadline bounds failure/cancellation. */
            t->descriptors.selector_exit=(pid_t)id;
            retry_wait(t,RETRY_TASK_ACTION);
            SUPERVISOR_TRACE("PROBE selector exit wait pid=%d helper=%d\n",t->pid,t->descriptors.selector_exit);
            return;
        } else if (syscall(SYS_tgkill,t->tgid,(pid_t)id,0)==-1 && errno==ESRCH) {
            /* The exit can already have been collected before this trap. */
            result=0;
        } else result=-ENOTSUP;
    } else if (operation==MD_FD_SELECTOR_QUERY || operation==MD_FD_SELECTOR_INSTALL) {
        if (t->phase!=DISPATCHING || id>=MD_FD_SELECTOR_KINDS || (long)control->args[1]< -1
                || (long)control->args[1]>INT_MAX || control->args[2]) result=-EINVAL;
        else {
            result=selector_has(t,(unsigned)id,(int)control->args[1]);
            if (operation==MD_FD_SELECTOR_INSTALL && result>=0) {
                if (result) result=0;
                else if (t->action) result=-EBUSY;
                else if (!task_action_new(t)) result=-ENOMEM;
                else {
                    t->action->continuation=*r;
                    t->action->kind=(enum md_fd_selector_kind)id;
                    t->action->number=(int)control->args[1];
                    task_action_start(t); return;
                }
            }
        }
    } else if (operation==MD_FD_INPUT || operation==MD_FD_RECEIVE || operation==MD_FD_USE || operation==MD_FD_PAIR_BEGIN) {
        if ((operation==MD_FD_PAIR_BEGIN && (t->phase!=DISPATCHING
                    || (t->execution.request.nr!=SYS_pipe2 && (t->execution.request.nr!=SYS_socketpair
                        || !md_network_namespace_managed(t->resources.network)))))
                || t->descriptors.order.table || (operation==MD_FD_USE || operation==MD_FD_PAIR_BEGIN ? id || control->args[1] || control->args[2]
                : operation==MD_FD_INPUT ? t->descriptors.exchange!=NULL
                : !id || id!=md_fd_exchange_id(t->descriptors.exchange))) result=-EINVAL;
        else if (t->resources.files) {
            if (r) t->descriptors.registers=*r;
            t->descriptors.request=*control; t->descriptors.job=operation;
            int ready=md_fd_order_begin(t->resources.files,&t->descriptors.order,fd_wakeup);
            if (ready<0) { t->descriptors.job=0; result=ready; }
            else { if (ready) fd_start(t); else retry_wait(t,RETRY_FD); return; }
        }
    } else if (operation==MD_FD_PAIR_END) {
        if (id || t->descriptors.job!=MD_FD_PAIR_BEGIN || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            int ends[2]={(int)control->args[1],(int)control->args[2]};
            if (ends[0]==-1 && ends[1]==-1) {
                for (unsigned i=0;i<2;++i) md_fd_origins_finish(&t->descriptors.pair_updates[i],-1);
            } else result=md_fd_origins_finish_batch(t->descriptors.pair_updates,2,ends,2);
            for (unsigned i=0;i<2;++i) md_fd_origins_finish(&t->descriptors.pair_updates[i],-1);
            CHECK(!md_fd_order_complete(&t->descriptors.order)); t->descriptors.job=0;
        }
    } else if (operation==MD_FD_RELEASE) {
        if (id || control->args[1] || control->args[2] || t->descriptors.job!=MD_FD_USE
                || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            if (t->descriptors.fs_order.fs) CHECK(!md_fs_order_complete(&t->descriptors.fs_order));
            CHECK(!md_fd_order_complete(&t->descriptors.order)); t->descriptors.job=0;
        }
    } else if (!id || id!=md_fd_exchange_id(t->descriptors.exchange)) result=-ESTALE;
    else if (operation==MD_FD_SENT || operation==MD_FD_RECEIVED) {
        if (t->descriptors.job!=(operation==MD_FD_SENT ? MD_FD_INPUT : MD_FD_RECEIVE)
                || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING) result=-EINVAL;
        else {
            if (operation==MD_FD_RECEIVED) result=md_fd_exchange_received(t->descriptors.exchange);
            CHECK(!md_fd_order_complete(&t->descriptors.order)); t->descriptors.job=0;
        }
    } else if (operation==MD_FD_END) {
        if (t->descriptors.order.table) result=-EBUSY;
        else { md_fd_exchange_close(fd_exchanges,t->descriptors.exchange); t->descriptors.exchange=NULL; }
    } else result=-EINVAL;
    if (result<0) SUPERVISOR_TRACE("PROBE fd-control pid=%d op=%u id=%llu result=%ld\n",t->pid,operation,
        (unsigned long long)id,result);
    if (r) r->regs[0]=result;
    if (operation==MD_FD_DUPLICATE_END && !result && close_begin(t,r,0)) return;
    control_return(t,r,result,extra);
}

void close_detach(struct thread *t) {
    retry_clear(t,RETRY_CLOSE);
    if (t->close.pending) { t->close.pending->dispose(t->close.pending); t->close.pending=NULL; }
    struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources,.wakeup=fd_wakeup};
    while (t->close.closed.first) {
        struct md_control_pending *pending=NULL;
        md_fd_closed_next(&t->close.closed,&actor,&pending);
        if (pending) pending->dispose(pending);
    }
    t->close.done=0; t->close.event=0; t->close.complete=NULL;
}

static void close_entry_ready(struct thread *t,long error) {
    void (*complete)(struct thread *,long)=t->close.complete;
    long result=t->close.result;
    CHECK(complete);
    if (error) {
        if (t->close.errors && !result) result=error;
        close_detach(t);
    } else { t->close.complete=NULL; t->close.done=0; }
    complete(t,result);
}

static void fd_entry_reply(struct thread *t,long result) {
    if (t->entry.execution) control_return(t,NULL,result,0);
    else entry_complete(t,result);
}

static void fd_entry_complete(struct thread *t,long result) {
    long nr=t->descriptors.call.nr;
    /* A cancelled effect never entered the kernel. In particular, it is not
     * close(EINTR), which commits slot removal on Linux. */
    fd_finish(t,t->entry.effect_cancelled ? -ECANCELED : result);
    if (t->entry.effect_cancelled && t->entry.execution) result=RESTART_SIGNAL;
    if (!t->close.closed.first) { fd_entry_reply(t,result); return; }
    CHECK(!t->close.complete);
    t->close.result=result; t->close.errors=nr==SYS_close;
    t->close.complete=fd_entry_reply;
    close_progress(t);
    /* EVENT_WAIT: provider completion or signal releases the retained reply;
     * the actual close is committed and its table turn has already ended. */
    if (t->close.complete) CHECK(entry_wait_begin(t,INT64_MAX,close_entry_ready));
}

static void close_event_complete(struct thread *t) {
    t->close.event=0; t->close.done=0;
    resume(t->pid,0);
}

static void close_return(struct thread *t) {
    struct user_pt_regs regs=t->close.registers;
    regs.regs[0]=(uint64_t)t->close.result;
    if (!set_registers(t->pid,&regs) || !signal_mask(t,t->close.mask,"close-restore-mask")) return;
    t->signals.shielded=t->close.mask_shielded;
    t->phase=t->close.phase; t->close.done=0;
    if (t->phase==IDLE && t->entered && t->image.process_image) {
        if (debug_syscall_exit(t,&regs)) return;
        t->debug.step_call=md_debugger_step(debugger,t->pid);
    }
    resume(t->pid,0);
}

void close_progress(struct thread *t) {
    retry_clear(t,RETRY_CLOSE);
    struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources,.wakeup=fd_wakeup};
    while (t->close.pending || t->close.closed.first) {
        long result=t->close.pending ? t->close.pending->resume(t->close.pending,NULL,0)
            : md_fd_closed_next(&t->close.closed,&actor,&t->close.pending);
        if (result==-EINPROGRESS) { retry_wait(t,RETRY_CLOSE); return; }
        if (t->close.pending) { t->close.pending->dispose(t->close.pending); t->close.pending=NULL; }
        if (result<0 && t->close.errors && !t->close.result) t->close.result=result;
    }
    t->close.done=1;
    if (t->close.complete) {
        if (t->entry.wait.complete) entry_wait_complete(t,0);
        else close_entry_ready(t,0);
        return;
    }
    if (t->close.event) { close_event_complete(t); return; }
    if (t->phase==CLOSE_WAITING && !t->listening)
        tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"close-provider-ready");
}

void close_event_begin(struct thread *t,unsigned event,unsigned long message) {
    if (event==PTRACE_EVENT_EXIT) md_cgroup_member_account_exit(t->resources.cgroup);
    t->close.event=event; t->close.message=message; t->close.errors=0;
    /* EVENT_WAIT: terminal provider replies before exec continuation/exit.
     * The ptrace event already owns the stop; no guest memory or FD turn is
     * retained. Fatal termination abandons the wait, not accepted requests. */
    if (event==PTRACE_EVENT_EXIT && WIFSIGNALED((int)message)) {
        close_detach(t); t->close.event=event;
        close_event_complete(t); return;
    }
    close_progress(t);
}

static int close_begin(struct thread *t,const struct user_pt_regs *returned,int errors) {
    if (!t->close.closed.first) return 0;
    struct user_pt_regs captured;
    if (!returned) {
        if (!registers(t->pid,&captured)) return 1;
        returned=&captured;
    }
    t->close.registers=*returned; t->close.result=(long)returned->regs[0]; t->close.phase=t->phase; t->close.errors=errors;
    close_progress(t);
    if (t->close.done && t->close.result==(long)returned->regs[0]) { t->close.done=0; return 0; }
    t->close.mask_shielded=t->signals.shielded;
    if (!save_signal_mask(t,&t->close.mask,"close-get-mask")
            || !shield(t)) return 1;
    if (t->close.done) { close_return(t); return 1; }
    /* EVENT_WAIT: a confirmed close waits for its provider, without retaining
     * the FD-table turn. Signals interrupt this wait but never replay close. */
    wait_poll_start(t,CLOSE_WAIT_ENTRY);
    return 1;
}

void close_wait_complete(struct thread *t) {
    if (!shield(t)) return;
    if (!t->close.done) {
        /* The slot is already closed. Retain terminal provider work, but let
         * the interrupted caller return; another thread may reuse that slot. */
        close_detach(t);
        if (t->close.errors && !t->close.result) t->close.result=-EINTR;
    }
    close_return(t);
}

static int network_namespace_capture(void *context,struct md_network_target_reference reference,
        struct md_network_namespace **out) {
    struct thread *t=context;
    if (reference.kind==MD_NETWORK_TARGET_PID) {
        struct thread *target=find_thread(native_pid(t,reference.value));
        if (!target || !target->born || !target->resources.network) return -ESRCH;
        *out=md_network_namespace_retain(target->resources.network); return 0;
    }
    if (reference.kind!=MD_NETWORK_TARGET_FD) return -EINVAL;
    struct md_proc_file *file=proc_file_capture(t,reference.value);
    const struct md_namespace_reference *ref=md_proc_file_namespace_reference(file);
    int error=!ref || ref->kind!=MD_NAMESPACE_NETWORK || (md_proc_file_flags(file)&O_PATH) ? -EINVAL : 0;
    if (!error) *out=md_network_namespace_retain(ref->value.network);
    md_proc_file_release(file); return error;
}

void control_input_discard(struct thread *t) {
    md_fd_capture_cancel(&t->control.capture);
    md_received_fds_release(t->control.input); t->control.input=NULL;
    md_fd_object_release(t->control.origin); t->control.origin=NULL;
    if (t->fs.descriptor_operation==MD_BUFFER_CONTROL_CAPTURE) {
        if (md_fd_order_cancel(&t->fs.descriptor_order)==1)
            CHECK(!md_fd_order_complete(&t->fs.descriptor_order));
        t->fs.descriptor_operation=0;
    }
}

static void control_capture_step(struct thread *t,unsigned command,long value) {
    struct md_buffer_span span;
    struct md_control_file file=md_proc_file_control(t->proc.file);
    struct md_control_ioctl spec={0};
    int error=md_buffer_span(&t->fs.buffer,&span);
    if (!error) error=!md_control_ioctl_available(file) ? -ENOTTY
        : file.ops->ioctl_spec(file.context,(unsigned)t->execution.request.args[1],&spec);
    if (!error && (md_control_ioctl_size(&spec)<0 || !(spec.direction&MD_CONTROL_FD)
            || md_control_ioctl_capture_offset(&spec)+sizeof(struct md_fd_capture_frame)>span.size)) error=-EINVAL;
    struct md_fd_capture_frame *frame=error ? NULL : (void *)((char *)span.data+md_control_ioctl_capture_offset(&spec));
    if (!error && command==MD_BUFFER_CONTROL_CAPTURE) {
        memcpy(&t->control.input_number,(char *)span.data+spec.descriptor,sizeof(int));
        t->control.origin=md_fd_origins_object(t->resources.files,t->control.input_number,NULL);
        error=md_fd_capture_native(t->pid,&t->control.input_number,1,&t->control.input);
        if (error>0) error=0;
    }
    if (!error && !t->control.input) {
        if (command==MD_BUFFER_CONTROL_CAPTURE_SENT) error=md_fd_capture_sent(&t->control.capture,value);
        if (!error && command==MD_BUFFER_CONTROL_CAPTURE_OBSERVED)
            error=md_fd_capture_observed(&t->control.capture,t->resources.files,frame);
        if (!error) error=md_fd_capture_progress(&t->control.capture,t->resources.files,t->tgid,1,frame);
        if (error>0) { buffer_reply(t,error); return; }
        if (!error) {
            t->control.input=md_fd_capture_take(&t->control.capture);
            if (!t->control.input) error=-EPROTO;
        }
    }
    if (!error) error=md_received_fds_annotate(t->control.input,&t->control.origin,1);
    CHECK(!md_fd_order_complete(&t->fs.descriptor_order));
    t->fs.descriptor_operation=0;
    if (error) control_input_discard(t);
    buffer_reply(t,error);
}

void buffer_descriptor_start(struct thread *t) {
    retry_clear(t,RETRY_DESCRIPTOR);
    if (md_fd_order_state(&t->fs.descriptor_order)!=MD_RESOURCE_ORDER_READY) { retry_wait(t,RETRY_DESCRIPTOR); return; }
    CHECK(t->phase==DISPATCHING && !md_fd_order_start(&t->fs.descriptor_order));
    if (t->fs.descriptor_operation==MD_BUFFER_CONTROL_CAPTURE) {
        control_capture_step(t,MD_BUFFER_CONTROL_CAPTURE,0); return;
    }
    int network=t->fs.descriptor_operation==MD_BUFFER_NETWORK_CALL;
    int fd=network ? (int)t->execution.request.args[0] : (int)t->fs.descriptor_argument;
    struct md_fd_object *object=md_fd_origins_object(t->resources.files,fd,NULL);
    struct md_buffer_span span;
    long result=md_buffer_span(&t->fs.buffer,&span),nr=t->execution.request.nr;
    if (!result && network) {
        unsigned long args[6]; for (unsigned i=0;i<6;++i) args[i]=t->execution.request.args[i];
        struct md_network_target resolver={.context=t,.resolve=network_namespace_capture};
        struct md_network_request *request=span.data;
        int sending=nr==SYS_sendto || nr==SYS_sendmsg;
        const struct md_network_send *destination=sending ? &request->destination : NULL;
        size_t capacity=span.size>offsetof(struct md_network_request,data)
            ? span.size-offsetof(struct md_network_request,data) : 0;
        void *data=(unsigned char *)span.data+offsetof(struct md_network_request,data);
        if (span.size<sizeof(*request)) result=-EOVERFLOW;
        else if (t->fs.descriptor_argument>capacity) result=-EMSGSIZE;
        else if (md_device_events_is(object)) {
            uint32_t groups=0;
            result=md_network_netlink_destination(destination,&groups);
            if (!result) result=groups ? -EOPNOTSUPP
                : md_device_events_call(object,nr,args,data,capacity,t->fs.descriptor_argument,visible_pid(t,t->tgid));
        } else result=md_network_socket_call(object,&t->identity,nr,args,data,capacity,
            t->fs.descriptor_argument,visible_pid(t,t->tgid),destination,t->resources.pid_group,&resolver);
    } else if (!result) {
        if (!md_descriptor_inode_is(object)) result=-EBADF;
        else if (nr==SYS_fchown || nr==SYS_fchownat) {
            unsigned offset=nr==SYS_fchown ? 1 : 2;
            result=md_descriptor_inode_chown(object,&t->identity,
                (uint32_t)t->execution.request.args[offset],(uint32_t)t->execution.request.args[offset+1]);
        } else if (nr==SYS_fchmod || nr==SYS_fchmodat || nr==SYS_fchmodat2)
            result=md_descriptor_inode_chmod(object,&t->identity,(mode_t)t->execution.request.args[nr==SYS_fchmod ? 1 : 2]);
        else if (nr==SYS_fstat || nr==SYS_newfstatat || nr==SYS_statx)
            result=md_descriptor_inode_stat(object,&t->identity,span.data);
        else result=-EOPNOTSUPP;
    }
    md_fd_object_release(object);
    CHECK(!md_fd_order_complete(&t->fs.descriptor_order));
    t->fs.descriptor_operation=0;
    buffer_reply(t,result);
}

void buffer_descriptor_begin(struct thread *t,const struct supervisor_control_request *control) {
    CHECK(!t->fs.descriptor_order.table);
    t->fs.descriptor_operation=(unsigned)control->command;
    t->fs.descriptor_argument=control->args[0];
    int error=md_fd_order_begin(t->resources.files,&t->fs.descriptor_order,fd_wakeup);
    if (error<0) { t->fs.descriptor_operation=0; buffer_reply(t,error); return; }
    /* EVENT_WAIT: preceding close/dup publication, cancelled on task death.
     * Only nonblocking typed operations and retained namespace-FD lookup run
     * under this turn; it ends before the adapter copies the result to userspace. */
    buffer_descriptor_start(t);
}

void descriptors_buffer_control(struct thread *t,const struct supervisor_control_request *control) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_BUFFER_DESCRIPTOR_META: {
        if (t->phase!=DISPATCHING || !t->fs.buffer.pool || (long)control->args[1]!=t->execution.request.nr) break;
        buffer_descriptor_begin(t,control); return;
    }
    case MD_BUFFER_PIDFD_NAMESPACE:
        if (t->phase!=DISPATCHING || t->proc.file
                || (t->execution.request.nr!=SYS_ioctl && t->execution.request.nr!=SYS_setns)
                || control->args[0]!=t->execution.request.args[0] || control->args[1]!=t->execution.request.args[1]
                || (t->execution.request.nr==SYS_ioctl && !md_pidfd_namespace_command((unsigned)control->args[1]))) break;
        pidfd_begin(t); return;
    case MD_BUFFER_PIDFD_SIGNAL:
        if (t->phase!=DISPATCHING || t->execution.request.nr!=SYS_pidfd_send_signal || control->args[0] || control->args[1]) break;
        pidfd_begin(t); return;
    case MD_BUFFER_PIDFD_READY:
    case MD_BUFFER_PIDFD_SENT:
    case MD_BUFFER_PIDFD_OBSERVED:
        if (t->phase!=DISPATCHING || !t->descriptors.pidfd_capture.count || control->args[1]) break;
        pidfd_capture_step(t,(unsigned)control->command,(long)control->args[0]); return;
    case MD_BUFFER_CONTROL_MAP: {
        if (t->phase!=DISPATCHING || !t->proc.file || t->execution.request.nr!=SYS_mmap
                || !t->fs.buffer.pool || t->control.pending || t->control.mapping_fd>=0
                || control->args[0] || control->args[1]) break;
        struct md_control_file file=md_proc_file_control(t->proc.file);
        if (!file.ops || !file.ops->mapping) { result=-ENODEV; break; }
        struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,
            .resources=&t->resources,.wakeup=fd_wakeup};
        result=file.ops->mapping(file.context,&actor,(unsigned)t->execution.request.args[2],
            (unsigned)t->execution.request.args[3],&t->control.pending);
        if (t->control.pending) {
            if (result==-EINPROGRESS) {
                t->control.size=0; retry_wait(t,RETRY_CONTROL); control_park(t); return;
            }
            t->control.pending->dispose(t->control.pending); t->control.pending=NULL;
        } else if (result==-EINPROGRESS) result=-EPROTO;
        control_reply(t,result); return;
    }
    case MD_BUFFER_CONTROL_CAPTURE:
        if (t->phase!=DISPATCHING || !t->proc.file || !t->fs.buffer.pool || t->execution.request.nr!=SYS_ioctl
                || t->control.input || t->control.origin || t->control.capture.count || control->args[0] || control->args[1]) break;
        buffer_descriptor_begin(t,control); return;
    case MD_BUFFER_CONTROL_CAPTURE_READY:
    case MD_BUFFER_CONTROL_CAPTURE_SENT:
    case MD_BUFFER_CONTROL_CAPTURE_OBSERVED:
        if (t->phase!=DISPATCHING || !t->control.capture.count || control->args[1]
                || t->fs.descriptor_operation!=MD_BUFFER_CONTROL_CAPTURE
                || md_fd_order_state(&t->fs.descriptor_order)!=MD_RESOURCE_ORDER_RUNNING) break;
        control_capture_step(t,(unsigned)control->command,(long)control->args[0]); return;
    case MD_BUFFER_CONTROL_IOCTL_LAYOUT:
    case MD_BUFFER_CONTROL_IOCTL: {
        if (t->phase!=DISPATCHING || !t->proc.file || t->execution.request.nr!=SYS_ioctl
                || control->args[0] || control->args[1]) break;
        struct md_control_file file=md_proc_file_control(t->proc.file);
        if (!md_control_ioctl_available(file)) { result=-ENOTTY; break; }
        struct md_control_ioctl spec={0};
        unsigned command=(unsigned)t->execution.request.args[1];
        result=file.ops->ioctl_spec(file.context,command,&spec);
        if (result) break;
        long size=md_control_ioctl_size(&spec);
        if (size<0) { result=size; break; }
        struct md_buffer_span span;
        result=md_buffer_span(&t->fs.buffer,&span);
        if (!result && (size_t)size>span.size) result=-EOVERFLOW;
        if (control->command==MD_BUFFER_CONTROL_IOCTL_LAYOUT) {
            if (!result && span.size<sizeof(spec)) result=-EOVERFLOW;
            if (!result) memcpy(span.data,&spec,sizeof(spec));
            break;
        }
        if (t->control.pending || t->control.publication) { result=-EBUSY; break; }
        struct md_control_actor actor={.pid=t->pid,.identity=&t->identity,.resources=&t->resources,.wakeup=fd_wakeup,
            .descriptor=t->control.input,.descriptor_number=t->control.input_number};
        if (!result && (spec.direction&MD_CONTROL_FD)) {
            int number; memcpy(&number,(char *)span.data+spec.descriptor,sizeof(number));
            if (!actor.descriptor || actor.descriptor->count!=1 || number!=actor.descriptor_number) result=-EBADF;
        }
        if (!result && !md_control_ioctl_constant(&spec,span.data)) result=file.ops->ioctl_async
            ? file.ops->ioctl_async(file.context,&actor,command,t->execution.request.args[2],span.data,(size_t)size,&t->control.pending)
            : file.ops->ioctl(file.context,&actor,command,t->execution.request.args[2],span.data,(size_t)size);
        SUPERVISOR_TRACE("PROBE control-ioctl pid=%d command=%x result=%ld pending=%d\n",t->pid,command,result,t->control.pending!=NULL);
        if ((result>=0 || result==-EINPROGRESS)
                && !!(spec.direction&MD_CONTROL_ACK)!=!!(t->control.pending && t->control.pending->copied))
            result=-EPROTO;
        if (t->control.pending) {
            if (result==-EINPROGRESS) {
                /* EVENT_WAIT: device-provider completion or request deadline;
                 * no file-table/OFD turn is held across a userspace reply. */
                t->control.size=(size_t)size; retry_wait(t,RETRY_CONTROL); control_park(t); return;
            }
            control_pending_complete(t,result);
        } else if (result==-EINPROGRESS) result=-EPROTO;
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}

static void pidfd_result(struct thread *t,struct md_pid_identity *identity,pid_t native,long error) {
    struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
    struct md_pidfd_frame *frame=span.data;
    int namespace=t->execution.request.nr==SYS_ioctl;
    int joining=t->execution.request.nr==SYS_setns;
    struct thread *target=native>0 ? find_thread(native) : NULL;
    if (!error && joining && !native) error=-EINVAL;
    /* A pidfd's flag contract precedes target liveness and ptrace admission. */
    if (!error && joining) error=md_resources_setns_process_flags((unsigned)t->execution.request.args[1]);
    if (!error && (native<0 || (identity && (!target || target->resources.pid_group!=identity)))) error=-ESRCH;
    if (!error && target && !target->born) error=-ESRCH;
    if (!error && !target) error=namespace ? MD_CALL_NATIVE : -ESRCH;
    else if (!error && joining) {
        if (!inspect_allowed(t->pid,target->pid,0)) error=-EPERM;
        else error=md_resources_setns_process_prepare(&t->resources,&t->identity,&target->resources,
            target->identity.user_namespace,(unsigned)t->execution.request.args[1],&t->resource_change);
    } else if (!error && namespace) {
        unsigned command=(unsigned)t->execution.request.args[1];
        if (t->execution.request.args[2]) error=-EINVAL;
        else if (target!=t && !proc_allowed(t->pid,target->pid)) error=-EACCES;
        else if (command==0xff07 || command==0xff08) error=-EOPNOTSUPP;
        else {
            static const enum md_namespace_kind kinds[]={MD_NAMESPACE_CGROUP,MD_NAMESPACE_IPC,
                MD_NAMESPACE_MOUNT,MD_NAMESPACE_NETWORK,MD_NAMESPACE_PID,MD_NAMESPACE_PID_CHILDREN,
                MD_NAMESPACE_USER,MD_NAMESPACE_USER,MD_NAMESPACE_USER,MD_NAMESPACE_UTS};
            struct md_namespace_reference ref=md_namespace_reference_capture(kinds[command-0xff01],
                &target->resources,target->identity.user_namespace);
            t->proc.file=md_proc_file_open_namespace(&ref,&t->identity,O_RDONLY|O_CLOEXEC);
            md_namespace_reference_release(&ref);
            if (!t->proc.file) error=-errno;
        }
    } else if (!error) {
        int signal=(int)t->execution.request.args[1],code=frame->info.si_code;
        if (signal<0 || signal>=NSIG || t->execution.request.args[3]) error=-EINVAL;
        else if (!signal_allowed(t,target,signal)) error=-EPERM;
        else if (t->execution.request.args[2] && (code>=0 || code==SI_TKILL) && target->tgid!=t->tgid) error=-EPERM;
        else error=init_signal_suppressed(t,target,signal);
        if (!error && signal==SIGSYS && target->signals.host) {
            siginfo_t info=frame->info;
            if (!t->execution.request.args[2]) {
                info=(siginfo_t){.si_signo=SIGSYS,.si_code=SI_USER};
                info.si_pid=t->tgid; info.si_uid=getuid();
            }
            error=signal_logical_enqueue(target,MD_SIGNAL_SHARED,&info);
            if (!error) error=1;
        }
    }
    if (joining) { setns_prepared(t,(int)error); return; }
    frame->result=error;
    buffer_reply(t,MD_PIDFD_DONE);
}

static void pidfd_capture_step(struct thread *t,unsigned command,long value) {
    struct md_buffer_span span;
    int error=md_buffer_span(&t->fs.buffer,&span);
    struct md_pidfd_frame *frame=error ? NULL : span.data;
    if (!error && command==MD_BUFFER_PIDFD_SENT) error=md_fd_capture_sent(&t->descriptors.pidfd_capture,value);
    if (!error && command==MD_BUFFER_PIDFD_OBSERVED)
        error=md_fd_capture_observed(&t->descriptors.pidfd_capture,t->resources.files,&frame->capture);
    if (!error) error=md_fd_capture_progress(&t->descriptors.pidfd_capture,t->resources.files,t->tgid,1,&frame->capture);
    if (error>0) { buffer_reply(t,error); return; }
    pid_t native=0;
    struct md_received_fds *captured=error ? NULL : md_fd_capture_take(&t->descriptors.pidfd_capture);
    if (!error) error=!captured || captured->count!=1 ? -EPROTO : md_pid_descriptor_read(captured->fds[0],&native);
    md_fd_capture_cancel(&t->descriptors.pidfd_capture);
    pidfd_result(t,NULL,native,error);
    md_received_fds_release(captured);
}

static void pidfd_begin(struct thread *t) {
    if (!t->fs.buffer.pool || t->descriptors.pidfd_capture.count || t->descriptors.job!=MD_FD_USE
            || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING) { buffer_reply(t,-EINVAL); return; }
    if (t->execution.request.nr==SYS_setns) {
        if (md_fs_order_state(&t->descriptors.fs_order)!=MD_RESOURCE_ORDER_RUNNING) { buffer_reply(t,-EINVAL); return; }
        struct md_proc_file *file=proc_file_capture(t,(int)t->execution.request.args[0]);
        const struct md_namespace_reference *ref=md_proc_file_namespace_reference(file);
        unsigned types=(unsigned)t->execution.request.args[1];
        int error=0;
        if (file && (md_proc_file_flags(file)&O_PATH)) error=-EBADF;
        if (!error && (ref ? ref->kind==MD_NAMESPACE_USER : !!(types&CLONE_NEWUSER)))
            for (struct thread *other=threads;other;other=other->next)
                if (other!=t && other->pid && other->tgid==t->tgid) { error=-EINVAL; break; }
        if (!error && ref) error=md_resources_setns_prepare(&t->resources,&t->identity,ref,types,&t->resource_change);
        int handled=error || ref;
        md_proc_file_release(file);
        if (handled) { setns_prepared(t,error); return; }
    }
    struct md_fd_object *object=md_fd_origins_object(t->resources.files,(int)t->execution.request.args[0],NULL);
    struct md_pid_identity *identity=md_pid_descriptor_identity(object);
    if (identity) {
        pidfd_result(t,identity,md_pid_identity_native(identity),0); md_fd_object_release(object); return;
    }
    md_fd_object_release(object);
    pidfd_capture_step(t,MD_BUFFER_PIDFD_READY,0);
}

int network_descriptor_call(long nr) {
    return nr==SYS_bind || nr==SYS_connect || nr==SYS_listen || nr==SYS_accept || nr==SYS_accept4
        || nr==SYS_setsockopt || nr==SYS_getsockopt || nr==SYS_getsockname || nr==SYS_getpeername
        || nr==SYS_ioctl || nr==SYS_read || nr==SYS_readv || nr==SYS_write || nr==SYS_writev
        || nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_sendmmsg
        || nr==SYS_recvfrom || nr==SYS_recvmsg || nr==SYS_recvmmsg;
}

int descriptor_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r, unsigned long cookie) {
    if (cookie != MD_INTERCEPT_DISPATCH || call->nr != SYS_lseek) return 0;
    CHECK(t->ready && t->phase == IDLE);
    int fd = duplicate_fd(t->pid, (int)call->args[0]);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode)) { close(fd); return 0; }
    /* A retained open-file description needs no namespace lookup or guest
     * memory access. Never cache FD numbers or resume against a replaceable FD.
     * Original seccomp admission and domain checks precede this operation. */
    off_t result = lseek(fd, (off_t)call->args[1], (int)call->args[2]);
    long value = result < 0 ? -errno : result;
    close(fd);
    if (statistics) statistics->native_seeks++;
    return_value(t, r, value); return 1;
}

int fd_capture_valid(const struct thread *t,const struct seccomp_data *call) {
    const __u64 *args=call->args;
    if (call->instruction_pointer!=abi.capture_gate || call->nr!=SYS_openat || (int)args[0]!=AT_FDCWD || args[3])
        return 0;
    if ((int)args[4]!=MD_FS_BUFFER_SOURCE)
        return (args[2]==(O_PATH|O_CLOEXEC) || args[2]==(O_PATH|O_DIRECTORY|O_CLOEXEC))
            && ((int)args[4]>=0 || (int)args[4]==AT_FDCWD);
    /* Caller-owned terminal opens can retry append admission with the same
     * pinned offer. Other captures publish only an O_PATH description. */
    int caller_flags=args[2]==(unsigned long)t->fs.open.flags
        || ((t->fs.open.flags&O_APPEND)
            && args[2]==(unsigned long)(t->fs.open.flags&~O_APPEND));
    return t->fs.open.fd>=0
        && (t->fs.open.completion.kind==MD_OPEN_CALLER ? caller_flags
            : args[2]==(unsigned long)(O_PATH|(t->fs.open.flags&(O_CLOEXEC|O_DIRECTORY|O_NOFOLLOW))))
        && args[1]==md_memory_buffer_address(t->memory,&t->fs.buffer)+offsetof(struct md_fs_buffer_frame,source);
}

int descriptors_native_exit(struct thread *t,pid_t pid) {
    if (t->descriptors.running && !t->entry.effect) {
        long result;
        if (!stopped_result(t,&result)) return 1;
        long nr=t->descriptors.task ? t->descriptors.call.nr : t->descriptors.mutation.nr;
        fd_finish(t,result);
        if (!t->descriptors.job && close_begin(t,NULL,nr==SYS_close)) return 1;
        if (!md_clone_call(nr) && nr!=SYS_execve && nr!=SYS_execveat) {
            /* Bootstrap descriptor operations are not completion of
             * the guest exec; only an entered image exposes stops. */
            if (t->phase==IDLE && t->entered && t->image.process_image) {
                if (debug_syscall_exit(t,NULL)) return 1;
                t->debug.step_call=md_debugger_step(debugger,pid);
            }
            resume(pid,0); return 1;
        }
    }
    return 0;
}

int descriptors_close_exit(struct thread *t,pid_t pid) {
    if (t->phase==CLOSE_WAIT_ENTRY) {
        if (t->close.done) {
            wait_cancel_entry(t,CLOSE_WAITING);
            return 1;
        }
        uint64_t wait_mask=t->close.phase==DISPATCHING ? t->execution.mask : t->close.mask;
        if (!signal_mask(t,wait_mask,"close-wait-mask")) return 1;
        t->phase=CLOSE_WAITING; resume(pid,0); return 1;
    }
    if (t->phase==CLOSE_WAITING) { close_wait_complete(t); return 1; }
    return 0;
}
