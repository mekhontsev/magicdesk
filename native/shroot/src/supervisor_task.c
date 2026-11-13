#define _GNU_SOURCE
#include "adapter_buffer.h"
#include "cgroup.h"
#include "clone_call.h"
#include "cooperative_channel.h"
#include "elf_admission.h"
#include "fd_channel.h"
#include "fd_client.h"
#include "fd_mutation.h"
#include "fd_origins.h"
#include "fd_selector.h"
#include "fs_anchor.h"
#include "fs_buffer_service.h"
#include "fs_location.h"
#include "fs_mount_internal.h"
#include "guest_debugger.h"
#include "guest_domain.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "identity_call.h"
#include "interception.h"
#include "interception_buffers.h"
#include "interception_memory.h"
#include "interception_program.h"
#include "ipc_namespace.h"
#include "ipc_pid.h"
#include "linux_abi.h"
#include "mount_policy.h"
#include "namespace_broker.h"
#include "native_identity.h"
#include "pid_calls.h"
#include "pid_namespace.h"
#include "proc_paths.h"
#include "process_access.h"
#include "process_image_view.h"
#include "process_limits.h"
#include "resource_order.h"
#include "seccomp_policy.h"
#include "signal_actions.h"
#include "signal_delivery.h"
#include "signal_pending.h"
#include "supervisor_actors.h"
#include "supervisor_debug.h"
#include "supervisor_descriptors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_internal.h"
#include "supervisor_identity.h"
#include "supervisor_io.h"
#include "supervisor_memory.h"
#include "supervisor_notifications.h"
#include "supervisor_robust.h"
#include "supervisor_seccomp.h"
#include "supervisor_signals.h"
#include "supervisor_task.h"
#include "supervisor_transfer.h"
#include "svc_frame.h"
#include "svc_machine.h"
#include "svc_payload.h"
#include "svc_registry.h"
#include "svc_runtime.h"
#include "sysv_ipc.h"
#include "sysv_shm.h"
#include "task_birth.h"
#include "task_call.h"
#include "task_registry.h"
#include "task_resources.h"
#include "task_status.h"
#include "task_wait.h"
#include "terminal_calls.h"
#include <linux/futex.h>
#include <linux/ioprio.h>
#include <stdlib.h>
#include <sys/ioctl.h>

struct private_child_selection {
    struct thread *caller;
    pid_t group,*candidates;
    size_t count,capacity;
    unsigned options;
    int error;
};
struct thread *threads;
static int process_reboot_status(pid_t pid,int status);
static struct thread *thread_index[THREAD_BUCKETS];
int pid_collection_pending;
static struct thread *pid_collection_blocker;
struct md_cgroup_member *freezer;
static uint64_t freezer_generation;
int freezer_enabled;

static void exec_backings_release(struct thread *t);
static int image_identity_prepare(struct thread *t,int fd,struct md_exec_identity *next,int *replacement);
static int process_reboot_signal(pid_t pid);
static int process_reboot_status(pid_t pid,int status);
static int private_child_matches(struct private_child_selection *s,pid_t pid);
static void private_child_candidate(struct private_child_selection *s,pid_t pid);
static void private_child_retired(void *context,pid_t pid,struct md_task_retired_view view);
static struct thread *process_thread(pid_t group);
static int pid_collectible(pid_t pid);
static int pid_collection_waiting(const struct thread *t);
static int publish_birth(struct thread *t,pid_t child,unsigned event);

static void exec_backings_release(struct thread *t) {
    for (unsigned i=0;i<2;++i) {
        if (t->image.backings[i]>=0) close(t->image.backings[i]);
        t->image.backings[i]=-1;
    }
}

void exec_images_release(struct thread *t) {
    exec_backings_release(t);
    md_fs_anchor_release(t->execfd_origin); t->execfd_origin=NULL;
    md_exec_image_release(t->image.setid); t->image.setid=NULL;
    md_process_executable_release(t->image.prepared_executable); t->image.prepared_executable=NULL;
    md_fs_anchor_release(t->image.prepared_executable_origin); t->image.prepared_executable_origin=NULL;
    for (unsigned i=0;i<3;++i) {
        if (t->image.images[i]>=0) close(t->image.images[i]);
        t->image.images[i]=-1;
    }
    t->image.images_ready=0;
    t->image.mount_flags=0;
}

void exec_images_activate(struct thread *t) {
    t->image.images_ready=t->image.images[0]>=0;
    md_process_executable_release(t->image.executable);
    t->image.executable=t->image.prepared_executable; t->image.prepared_executable=NULL;
    md_fs_anchor_release(t->image.executable_origin);
    t->image.executable_origin=t->image.prepared_executable_origin; t->image.prepared_executable_origin=NULL;
}

void release_operation(struct thread *t) {
    retry_clear(t,RETRY_EXPORT);
    struct metadata_operation *op=&t->metadata;
    md_fs_buffer_cancel(&op->stat_call);
    md_fd_channel_capture_cancel(&op->capture);
    md_fd_channel_release(op->transport); op->transport=NULL;
    if (op->descriptor>=0) { close(op->descriptor); op->descriptor=-1; }
    if (op->files.table && md_fd_order_cancel(&op->files)==1)
        CHECK(!md_fd_order_complete(&op->files));
    op->waiting=op->checking=0;
}

struct thread *find_thread(pid_t pid) {
    if (pid <= 0) return NULL;
    for (struct thread *t = thread_index[(unsigned)pid % THREAD_BUCKETS]; t; t = t->indexed)
        if (t->pid == pid) return t;
    return NULL;
}

struct thread *thread(pid_t pid) {
    CHECK(pid>0);
    struct thread *t = find_thread(pid);
    if (t) return t;
    for (t = threads; t && t->pid; t = t->next) { }
    if (!t) { t = calloc(1, sizeof(*t)); CHECK(t); t->next = threads; threads = t; }
    CHECK(!t->retries[0].node.list && !t->retries[1].node.list);
    *t = (struct thread){.pid = pid, .tgid = pid, .next = t->next,
        .metadata = {.channel = -1, .descriptor = -1},
        .identity = md_identity_copy(&launch_identity), .memory = md_memory_new(),
        .shm_space = md_shm_space_new(),
        .fs.open = {.fd=-1}, .proc.image_fd=-1, .control.mapping_fd=-1,
        .image.images = {-1,-1,-1}, .image.backings={-1,-1},
        .descriptors.selector = md_fd_selector_new()};
    t->signals.delivery.capture=signal_origin_capture;
    t->signals.delivery.capture_context=t;
    CHECK(t->memory && t->descriptors.selector && t->shm_space);
    unsigned bucket = (unsigned)pid % THREAD_BUCKETS;
    t->indexed = thread_index[bucket]; thread_index[bucket] = t;
    md_task_wait_track(&task_wait,&t->wait_member,pid);
    live++; total++; return t;
}

void pid_birth_release(struct thread *child) {
    struct thread *parent=child->process_op.birth_parent;
    if (!parent) return;
    CHECK(parent->process_op.birth_child==child);
    child->process_op.birth_parent=NULL; parent->process_op.birth_child=NULL;
    if (parent->process_op.birth_waiting) {
        int sig=parent->process_op.birth_signal;
        parent->process_op.birth_waiting=parent->process_op.birth_signal=0;
        resume(parent->pid,sig);
    }
}

void pid_store_add(struct thread *t, uintptr_t address, pid_t value) {
    if (!address) return;
    CHECK(t->process_op.store_count<2);
    t->process_op.stores[t->process_op.store_count].address=address;
    t->process_op.stores[t->process_op.store_count++].value=value;
}

void release_thread(struct thread *t) {
    int buffered=t->fs.buffer.pool || t->fs.call || t->fs.waiting || t->action || t->debug.wait_output;
    debugger_wait_release(t);
    retained_detach(t);
    entry_fence_cancel(t);
    if (entry_fence_owner==t) entry_fence_owner=NULL;
    t->entry.fence_held=0;
    md_svc_machine_forget(&t->machine);
    md_signal_delivery_clear(&t->signals.delivery);
    pid_birth_release(t);
    if (t->process_op.birth_child) {
        t->process_op.birth_child->process_op.birth_parent=NULL;
        t->process_op.birth_child=NULL;
    }
    fd_discard(t);
    adapter_discard(t);
    exec_images_release(t);
    md_process_executable_release(t->image.executable); t->image.executable=NULL;
    md_fs_anchor_release(t->image.executable_origin); t->image.executable_origin=NULL;
    md_task_registry_forget(tasks, t->pid);
    md_resources_exit_files(&t->resources,&t->close.closed);
    close_detach(t);
    md_identity_release(&t->identity);
    CHECK(!md_shm_space_release(t->shm_space)); t->shm_space = NULL;
    ipc_unwatch(t);
    CHECK(!md_ipc_cancel(md_ipc_namespace_active(t->resources.ipc),&t->io.pending));
    md_ipc_transport_close(&t->io.transport);
    CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change, -ECANCELED));
    CHECK(!md_resources_release(&t->resources));
    identity_discard(t);
    md_broker_forget(broker, t->pid);
    cooperative_buffer_release(t);
    release_operation(t);
    filter_install_discard(t);
    guest_notification_discard(t);
    signal_control_discard(t);
    md_signal_actions_release(t->signals.actions); t->signals.actions=NULL;
    md_signal_pending_release(t->signals.pending); t->signals.pending=NULL;
    md_seccomp_policy_replace(&t->seccomp.policy,NULL);
    md_seccomp_policy_release(t->seccomp.admission_policy); t->seccomp.admission_policy=NULL;
    t->seccomp.admission_policy_valid=t->seccomp.admission_policy_pending=0;
    md_memory_stack_return(t->memory, t->execution.stack);
    md_memory_release(t->memory); t->memory = NULL;
    md_memory_release(t->memory_op.remote); t->memory_op.remote=NULL;
    task_continuation_discard(t);
    if (t->broker_completion) {
        md_fs_buffer_open_release(&t->broker_completion->opened);
        free(t->broker_completion); t->broker_completion=NULL;
    }
    completed_event_discard(t);
    md_fd_selector_release(t->descriptors.selector); t->descriptors.selector = NULL;
    md_fd_selector_release(t->descriptors.deferred_selector); t->descriptors.deferred_selector=NULL;
    struct thread **slot = &thread_index[(unsigned)t->pid % THREAD_BUCKETS];
    while (*slot && *slot != t) slot = &(*slot)->indexed;
    CHECK(*slot == t);
    *slot = t->indexed; t->indexed = NULL;
    md_task_wait_untrack(&task_wait,&t->wait_member);
    t->pid = 0; live--;
    if (buffered) adapter_progress();
}

static int image_identity_prepare(struct thread *t,int fd,struct md_exec_identity *next,int *replacement) {
    int matched=admitted_path ? md_admission_match(&admission,t->endpoint,fd) : 0;
    if (matched<0) return matched;
    *next=(struct md_exec_identity){.value=t->identity};
    next->value.uid.saved=next->value.uid.fs=next->value.uid.effective;
    next->value.gid.saved=next->value.gid.fs=next->value.gid.effective;
    next->secure=next->value.uid.real!=next->value.uid.effective
        || next->value.gid.real!=next->value.gid.effective;
    const struct md_exec_image *setid=matched ? admission.image : t->image.setid;
    int error=0;
    if (setid) error=md_identity_prepare_exec(&t->identity,setid,
        md_debugger_traced(debugger,t->pid) || (t->image.mount_flags&MD_MOUNT_NOSUID),next);
    else md_identity_exec(&t->identity,&next->value);
    if (!error && matched) {
        *replacement=md_exec_image_dup(admission.image);
        if (*replacement<0) error=*replacement;
    }
    return error ? error : matched;
}

int image_enter(struct thread *t,uintptr_t image,uintptr_t address,size_t count,unsigned long machine) {
    if (!t->ready || t->entered || !t->image.mapped || count>128 || machine>1) return -EPERM;
    if (!image || image>UINTPTR_MAX-sizeof(struct md_process_image)) return -EFAULT;
    Elf64_auxv_t aux[128];
    struct iovec local={aux,count*sizeof(*aux)},remote={(void *)address,local.iov_len};
    if (process_vm_readv(t->pid,&local,1,&remote,1,0)!=(ssize_t)local.iov_len) return -EFAULT;
    unsigned found=0;
    const struct md_exec_identity *next=&t->image.pending_identity;
    for (size_t i=0;i<count;++i) {
        switch (aux[i].a_type) {
        case AT_UID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,0,next->value.uid.real); found|=1; break;
        case AT_EUID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,0,next->value.uid.effective); found|=2; break;
        case AT_GID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,1,next->value.gid.real); found|=4; break;
        case AT_EGID: aux[i].a_un.a_val=md_identity_visible_id(&next->value,1,next->value.gid.effective); found|=8; break;
        case AT_SECURE: aux[i].a_un.a_val=next->secure; found|=16; break;
        }
    }
    if (found!=31) return -EPROTO;
    /* /proc/auxv retains a snapshot, separately from the loader's live auxv. */
    struct iovec values[2]={local,local}, destinations[2]={remote,
        {(void *)(image+offsetof(struct md_process_image,auxv)),local.iov_len}};
    if (process_vm_writev(t->pid,values,2,destinations,2,0)!=(ssize_t)(2*local.iov_len)) return -EFAULT;
    CHECK(!md_task_registry_publish(tasks,t->pid,t->tgid,&next->value,&t->resources));
    /* The loader has closed its copies, but these exact published images
     * still authenticate the actual mappings. */
    int backings=machine && t->image.backings[0]>=0
        ? md_memory_image_backings(t->memory,t->pid,t->image.backings,t->image.backings[1]>=0 ? 2 : 1) : -ENODEV;
    t->image.backings_ready=!backings;
    SUPERVISOR_TRACE("PROBE entry-image pid=%d prepared=%lu backings=%d filter=%d blocked=%d exposed=%d\n",
        t->pid,machine,backings,t->seccomp.application_filter,t->entry.blocked,md_memory_exposed(t->memory));
    if (diagnostics && !machine) {
        struct md_svc_runtime runtime={0};
        int error=md_svc_registry_read(t->pid,abi.svc_runtime,&runtime,sizeof(runtime));
        SUPERVISOR_TRACE("PROBE entry-preparation pid=%d read=%d error=%d pools=%u\n",
            t->pid,error,runtime.error,runtime.count);
    }
    exec_backings_release(t);
    t->identity=next->value; t->image.mapped=0; t->entered=1;
    CHECK(!md_process_set_dumpable(t->resources.access,!next->secure));
    md_exec_image_release(t->image.setid); t->image.setid=NULL;
    SUPERVISOR_TRACE("PROBE entered-image pid=%d uid=%u euid=%u secure=%d\n",t->pid,
        t->identity.uid.real,t->identity.uid.effective,next->secure);
    if (diagnostics) {
        struct md_fs_location root;
        struct md_fs_anchor *cwd=NULL;
        md_domain_paths(t->resources.fs,&root,&cwd);
        SUPERVISOR_TRACE("PROBE image-cwd pid=%d known=%d\n",t->pid,cwd!=NULL);
        md_fs_anchor_release(cwd);
    }
    return 0;
}

void image_request(struct thread *t, const struct seccomp_notif *q) {
    struct seccomp_notif_resp reply = {.id = q->id};
    if (q->data.instruction_pointer != RAW_GATE || t->phase != IDLE) reply.error = -EPERM;
    else if (q->data.args[0] == MD_GUEST_MAP_IMAGE) {
        if (q->data.args[2]>=MD_IMAGE_TAKE_PROGRAM && q->data.args[2]<=MD_IMAGE_TAKE_EXECFD) {
            unsigned index=q->data.args[2]-MD_IMAGE_TAKE_PROGRAM;
            if (t->entered || !t->image.images_ready || q->data.args[1]
                    || q->data.args[3] || q->data.args[4] || q->data.args[5]
                    || t->image.images[index]<0) reply.error=-EPERM;
            else {
                struct md_exec_identity next={0};
                int replacement=-1,matched=0;
                if (!index) {
                    matched=image_identity_prepare(t,t->image.images[index],&next,&replacement);
                    if (matched<0) { reply.error=matched; goto image_reply; }
                }
                struct md_fd_origin_update update={0};
                int error=index==2 ? md_fd_origins_prepare(t->resources.files,t->execfd_origin,0,&update) : 0;
                if (error) { reply.error=error; goto image_reply; }
                struct seccomp_notif_addfd add={.id=q->id,.flags=SECCOMP_ADDFD_FLAG_SEND,
                    .srcfd=replacement>=0 ? replacement : t->image.images[index],.newfd_flags=index==2 ? 0 : O_CLOEXEC};
                int fd=ioctl(listener,SECCOMP_IOCTL_NOTIF_ADDFD,&add);
                int saved=errno;
                if (index==2) md_fd_origins_finish(&update,fd<0 ? -saved : fd);
                if (fd>=0) {
                    if (index<2) {
                        /* Retain the exact published file until its mappings
                         * are registered; do not reacquire it from /proc/fd. */
                        CHECK(t->image.backings[index]<0);
                        t->image.backings[index]=(int)add.srcfd;
                        if (replacement>=0) close(t->image.images[index]);
                    } else close(t->image.images[index]);
                    if (!index) {
                        t->image.pending_identity=next; t->image.mapped=1;
                        if (matched) {
                            ++admitted_images;
                            SUPERVISOR_TRACE("PROBE admitted-image pid=%d fd=%d euid=%u secure=%d\n",
                                t->pid,fd,next.value.uid.effective,next.secure);
                        } else if (t->image.setid)
                            SUPERVISOR_TRACE("PROBE stored-setid-image pid=%d euid=%u egid=%u secure=%d\n",
                                t->pid,next.value.uid.effective,next.value.gid.effective,next.secure);
                    }
                    t->image.images[index]=-1; return;
                }
                if (replacement>=0) close(replacement);
                reply.error=-saved;
            }
        } else if (t->entered || q->data.args[2]) reply.error = -EPERM;
        else {
            int fd = admitted_path ? duplicate_fd(t->pid, (int)q->data.args[1]) : -1;
            struct md_exec_identity next;
            int replacement=-1;
            int matched=admitted_path && fd<0 ? fd : image_identity_prepare(t,fd,&next,&replacement);
            if (fd >= 0) close(fd);
            if (matched < 0) reply.error = matched;
            else {
                if (matched) {
                    struct seccomp_notif_addfd add = {.id = q->id, .srcfd = replacement,
                        .newfd = q->data.args[1], .newfd_flags = O_CLOEXEC,
                        .flags = SECCOMP_ADDFD_FLAG_SETFD | SECCOMP_ADDFD_FLAG_SEND};
                    int sent = ioctl(listener, SECCOMP_IOCTL_NOTIF_ADDFD, &add);
                    int error = errno; close(replacement);
                    if (sent >= 0) {
                        t->image.pending_identity = next; t->image.mapped = 1; admitted_images++;
                        SUPERVISOR_TRACE("PROBE admitted-image pid=%d fd=%d euid=%u secure=%d\n",
                            t->pid, sent, next.value.uid.effective, next.secure);
                        return;
                    }
                    if (error == ENOENT) return;
                    reply.error = -error;
                }
                if (!reply.error) {
                    t->image.pending_identity = next; t->image.mapped = 1;
                    if (t->image.setid) SUPERVISOR_TRACE("PROBE stored-setid-image pid=%d euid=%u egid=%u secure=%d\n",
                        t->pid,next.value.uid.effective,next.value.gid.effective,next.secure);
                }
            }
        }
    } else reply.error = -EPROTO;
image_reply:
    CHECK(!ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &reply) || errno == ENOENT);
}

int birth_stop_pending(const struct thread *t) {
    /* The initial leader is seized while running, not auto-attached by clone. */
    return t->pid!=leader && t->born && !t->initial_stop;
}

void task_buffer_control(struct thread *t,const struct supervisor_control_request *control) {
    long result=-EINVAL;
    switch (control->command) {
    case MD_BUFFER_CLONE_CGROUP: {
        if (t->phase!=DISPATCHING || t->execution.request.nr!=SYS_clone3
                || !t->fs.buffer.pool || t->descriptors.job!=MD_FD_USE || control->args[1]
                || md_fd_order_state(&t->descriptors.order)!=MD_RESOURCE_ORDER_RUNNING) break;
        struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
        const struct md_clone_request *request=span.data;
        if (!(request->args.flags&CLONE_INTO_CGROUP) || control->args[0]!=request->args.cgroup) break;
        if (t->process_op.clone_cgroup) break;
        t->process_op.clone_cgroup=md_fd_origins_get(t->resources.files,(int)control->args[0],NULL);
        buffer_reply(t,t->process_op.clone_cgroup ? 0 : 1); return;
    }
    case MD_BUFFER_NATIVE_IDENTITY:
        if (control->args[0] || control->args[1]) break;
        result=(long)(((uint64_t)(unsigned)t->tgid<<32)|(unsigned)t->pid); break;
    case MD_BUFFER_PID_NUMBER:
        if (t->phase!=DISPATCHING || control->args[1]>1) break;
        result=control->args[1] ? native_pid(t,(pid_t)control->args[0]) : visible_pid(t,(pid_t)control->args[0]); break;
    case MD_BUFFER_PROCESS_QUERY: {
        if (t->phase!=DISPATCHING || control->args[0] || control->args[1] || !t->fs.buffer.pool) break;
        struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
        struct md_process_query *query=span.data;
        const struct seccomp_data *call=&t->execution.request;
        if (call->nr==SYS_get_robust_list) result=robust_query(t,(pid_t)call->args[0],query);
        else if (call->nr==SYS_prctl && call->args[0]==PR_GET_CHILD_SUBREAPER
                && md_pid_identity_number(t->resources.pid_group,md_pid_identity_namespace(t->resources.pid))==1) {
            query->value[0]=md_pid_identity_subreaper(t->resources.pid_group); result=0;
        }
        break;
    }
    case MD_BUFFER_PROCESS_WAIT: {
        if (t->phase!=DISPATCHING || t->process_op.wait_result!=1
                || control->args[0] || control->args[1] || !t->fs.buffer.pool) break;
        struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
        union md_process_wait *waited=span.data;
        if (t->execution.request.nr==SYS_wait4) {
            pid_t child=(pid_t)t->process_op.result;
            waited->status=process_reboot_status(child,waited->status);
            t->process_op.info_native=child;
            t->process_op.info_code=WIFEXITED(waited->status) ? CLD_EXITED
                : WIFSIGNALED(waited->status) ? CLD_KILLED : 0;
            result=visible_pid(t,child);
        } else if (t->execution.request.nr==SYS_waitid) {
            siginfo_t *info=&waited->info;
            t->process_op.info_native=info->si_pid;
            if (info->si_signo==SIGCHLD)
                process_reboot_info(info->si_pid,&info->si_code,&info->si_status);
            t->process_op.info_code=info->si_code;
            if (info->si_signo==SIGCHLD || info->si_code==SI_USER || info->si_code==SI_TKILL) {
                info->si_uid=process_signal_uid(t,info->si_pid,info->si_uid);
                info->si_pid=visible_pid(t,info->si_pid);
            }
            result=0;
        }
        if (result>=0) t->process_op.wait_result=2;
        break;
    }
    }
    buffer_reply(t,result); adapter_progress();
}

pid_t visible_pid(struct thread *t, pid_t native) {
    struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
    if (!space || native<=0) return native;
    struct md_pid_identity *id=md_pid_namespace_find(space,native);
    pid_t number=md_pid_identity_number(id,space);
    md_pid_identity_release(id); return number;
}

pid_t native_pid(struct thread *t,pid_t visible) {
    struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
    if (!space || visible<=0) return visible;
    struct md_pid_identity *id=md_pid_namespace_lookup(space,visible);
    pid_t number=md_pid_identity_native(id);
    md_pid_identity_release(id); return number;
}

static int process_reboot_signal(pid_t pid) {
    struct thread *t=find_thread(pid);
    return md_pid_identity_reboot_signal(t ? t->resources.pid_group : md_task_registry_retired(tasks,pid).pid);
}

static int process_reboot_status(pid_t pid,int status) {
    if (!WIFEXITED(status) && !WIFSIGNALED(status)) return status;
    int signal=process_reboot_signal(pid);
    return signal ? signal : status;
}

void process_reboot_info(pid_t pid,int *code,int *status) {
    if (*code!=CLD_EXITED && *code!=CLD_KILLED && *code!=CLD_DUMPED) return;
    int signal=process_reboot_signal(pid);
    if (signal) { *code=CLD_KILLED; *status=signal; }
}

static long process_native_result(struct thread *t,long value) {
    long nr=t->execution.request.nr;
    md_memory_release(t->memory_op.remote); t->memory_op.remote=NULL;
    if (t->process_op.inspection_denied) {
        if (value==-ESRCH) value=-EPERM;
        t->process_op.inspection_denied=0;
    }
    if (value>0 && nr==SYS_getppid) t->parent=(pid_t)value;
    if (!value && nr==SYS_prctl && t->execution.request.args[0]==PR_SET_CHILD_SUBREAPER)
        md_pid_identity_set_subreaper(t->resources.pid_group,t->execution.request.args[1]!=0);
    if (value>0 && (nr==SYS_getppid || nr==SYS_wait4 || nr==SYS_getpgid || nr==SYS_getsid
            || nr==SYS_setsid || nr==SYS_set_tid_address)) {
        pid_t native=(pid_t)value;
        value=visible_pid(t,native);
        if (nr==SYS_wait4) process_reap(t,native);
    }
    return value;
}

static int process_wait_output(struct thread *t,long value) {
    const struct seccomp_data *call=&t->execution.request;
    if ((value>0 && call->nr==SYS_wait4 && call->args[1] && process_reboot_signal((pid_t)value))
            || (!value && call->nr==SYS_waitid && call->args[2])) {
        t->process_op.result=value; t->process_op.wait_result=1;
        return 1;
    }
    return 0;
}

static void process_entry_complete(struct thread *t,long value) {
    if (t->process_op.wait_recheck) {
        t->process_op.wait_recheck=0;
        int decision=debugger_call(t,&t->execution.request,NULL);
        if (decision==MD_DEBUG_CALL_HANDLED) return;
        if (decision==MD_DEBUG_CALL_CONTEXT) { entry_context(t,MD_CONTEXT_POLICY); return; }
        struct md_entry_native *native=&t->entry.control->native;
        uint64_t args[6]; memcpy(args,native->args,sizeof(args));
        entry_effect(t,native->number,args,process_entry_complete);
        return;
    }
    if (t->entry.effect_cancelled
            && (t->execution.request.nr==SYS_wait4 || t->execution.request.nr==SYS_waitid)) {
        /* Cancellation has no child result. Rebuild the original signal
         * boundary so attach/group stops do not leak EINTR and application
         * handlers retain the kernel's SA_RESTART decision. */
        entry_restart(t,RESTART_SIGNAL); return;
    }
    if (process_wait_output(t,value)) { entry_execution_begin(t); return; }
    entry_complete(t,process_native_result(t,value));
}

static int process_wait_interrupt(struct thread *t) {
    if (t->entry.effect!=process_entry_complete || !t->entry.waiting) return 0;
    long nr=t->execution.request.nr;
    if (nr!=SYS_wait4 && nr!=SYS_waitid) return 0;
    int error=entry_effect_observe(t);
    if (error==-ESRCH) { t->resume_lost=1; return 1; }
    CHECK(!error);
    /* An internal interrupt can introduce a debugger-owned child while the
     * kernel wait is blocked. Re-admit only a cancelled effect; a completed
     * wait may already have reaped its child and must never be replayed. */
    t->process_op.wait_recheck=atomic_load_explicit(&t->entry.control->native.state,
        memory_order_acquire)==MD_NATIVE_CANCELLED;
    return 1;
}

int process_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r,unsigned long *cookie) {
    long nr=call->nr;
    if (t->phase==IDLE && robust_call(t,call,r,MD_TASK_CALL_SECCOMP)) return 1;
    int signaling=nr==SYS_kill || nr==SYS_tkill || nr==SYS_tgkill
        || nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo || nr==SYS_pidfd_send_signal;
    int inspecting=nr==SYS_process_vm_readv || nr==SYS_process_vm_writev
        || nr==SYS_kcmp || nr==SYS_get_robust_list;
    /* A later FD selector can replace the PID selector's cookie, including
     * after close/reuse. Terminal IDs still belong to the caller's PID view. */
    int terminal=t->resources.pid && t->phase==IDLE && call->instruction_pointer!=RAW_GATE
        && call->nr==SYS_ioctl && md_terminal_identity_command((unsigned)call->args[1]);
    /* Guest credentials never authorize signals against Android's executor
     * UID. The baseline already observes these calls; PID views only add ID
     * translation. Internal delivery uses its validated, positive native PID. */
    if (*cookie!=MD_INTERCEPT_PROCESS && !terminal
            && !((signaling || inspecting || nr==SYS_rt_sigtimedwait || nr==SYS_waitid
                    || (nr==SYS_wait4 && entry_pending(t)))
                && t->phase==IDLE && call->instruction_pointer!=RAW_GATE)) return 0;
    if (t->phase==IDLE && (nr==SYS_getpid || nr==SYS_gettid)
            && (entry_pending(t) || t->resources.pid)) {
        const struct md_pid_identity *identity=nr==SYS_getpid ? t->resources.pid_group : t->resources.pid;
        /* A PID selector can be installed for future children while the
         * caller still uses native IDs and has no virtual identity. */
        pid_t target=identity ? md_pid_identity_number(identity,md_pid_identity_namespace(t->resources.pid))
            : nr==SYS_getpid ? t->tgid : t->pid;
        return_value(t,r,target); return 1;
    }
    *cookie=MD_INTERCEPT_NATIVE;
    if (t->phase!=IDLE) return 0;
    if (nr==SYS_pidfd_open) {
        struct seccomp_data translated=*call;
        translated.args[0]=native_pid(t,(pid_t)call->args[0]);
        if (!translated.args[0]) { return_value(t,r,-ESRCH); return 1; }
        if (entry_pending(t)) { fd_begin(t,&translated,NULL,0); return 1; }
        struct user_pt_regs native=*r;
        native.regs[0]=translated.args[0];
        if (!set_registers(t->pid,&native)) return 1;
        fd_begin(t,&translated,&native,0); return 1;
    }
    if (nr==SYS_pidfd_send_signal) {
        execution_begin(t,call,r,"pidfd-signal-mask");
        return 1;
    }
    if (nr==SYS_rt_sigtimedwait) {
        if (entry_context(t,MD_CONTEXT_SIGNAL)) return 1;
        /* Observed waits need PID translation and must survive service
         * quiescence without publishing its EINTR. Unselected host waits
         * remain native; both translated paths share one deadline owner. */
        int error=signal_control_begin(t,call,r,0,NULL,0,NULL);
        if (error) return_value(t,r,error);
        return 1;
    }
    if (!t->resources.pid && !signaling && !inspecting
            && nr!=SYS_wait4 && nr!=SYS_waitid) return 0;
    if (nr==SYS_futex) {
        /* The PID selector intercepts only PI operations. Passing a logical
         * owner TID to the native PI state can deadlock even after unlock.
         * musl exposes LOCK_PI's error from mutexattr_setprotocol, so report
         * unsupported protocol. glibc probes UNLOCK_PI and requires ENOSYS
         * to reject PI mutex creation. Neither probe may modify the word. */
        unsigned op=(unsigned)call->args[1]&FUTEX_CMD_MASK;
        return_value(t,r,op==FUTEX_UNLOCK_PI ? -ENOSYS : -EOPNOTSUPP); return 1;
    }
    if (nr==SYS_ioctl) {
        execution_begin(t,call,r,"pid-terminal-mask");
        return 1;
    }
    if (nr==SYS_kill && (pid_t)call->args[0]<=0) {
        execution_begin(t,call,r,"pid-group-signal-mask");
        return 1;
    }
    /* Native waits retain their exact result through the signal-aware journal.
     * A restricted inherited policy still needs its original-site admission. */
    if ((nr==SYS_wait4 || nr==SYS_waitid) && entry_pending(t) && checkpoint_restricted(t->pid)) {
        entry_context(t,MD_CONTEXT_POLICY); return 1;
    }
    struct seccomp_data native=*call;
    if (nr==SYS_ioprio_get || nr==SYS_ioprio_set) {
        /* User-wide selection needs guest credential enumeration; never pass
         * it through to unrelated processes sharing the Android executor UID. */
        unsigned which=(unsigned)call->args[0];
        if (which==IOPRIO_WHO_USER) { return_value(t,r,-EOPNOTSUPP); return 1; }
        if (which==IOPRIO_WHO_PROCESS || which==IOPRIO_WHO_PGRP) {
            pid_t target=(pid_t)call->args[1];
            if (target) {
                pid_t mapped=target>0 ? native_pid(t,target) : 0;
                if (!mapped) { return_value(t,r,-ESRCH); return 1; }
                native.args[1]=mapped;
            }
        }
    }
    if (nr==SYS_prctl) {
        int init=md_pid_identity_number(t->resources.pid_group,md_pid_identity_namespace(t->resources.pid))==1;
        if (init && call->args[0]==PR_SET_CHILD_SUBREAPER) {
            md_pid_identity_set_subreaper(t->resources.pid_group,call->args[1]!=0);
            return_value(t,r,0); return 1;
        }
        if (init && call->args[0]==PR_GET_CHILD_SUBREAPER) {
            execution_begin(t,call,r,"pid-subreaper-mask");
            return 1;
        }
    }
    int index=nr==SYS_tgkill || nr==SYS_rt_tgsigqueueinfo ? 1 : 0;
    if (index==1) {
        pid_t group=native_pid(t,(pid_t)call->args[0]), target=native_pid(t,(pid_t)call->args[1]);
        struct thread *to=find_thread(target);
        if (group<=0 || target<=0 || (to && to->tgid!=group)) { return_value(t,r,-ESRCH); return 1; }
        native.args[0]=group;
    }
    if (nr==SYS_kill || nr==SYS_tkill || nr==SYS_tgkill
            || nr==SYS_getpgid || nr==SYS_getsid || nr==SYS_setpgid
            || nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo || nr==SYS_wait4
            || nr==SYS_sched_getaffinity || nr==SYS_sched_setaffinity || nr==SYS_sched_getparam || nr==SYS_sched_setparam
            || nr==SYS_sched_getscheduler || nr==SYS_sched_setscheduler || nr==SYS_sched_getattr || nr==SYS_sched_setattr
            || nr==SYS_sched_rr_get_interval || nr==SYS_get_robust_list || nr==SYS_prlimit64
            || nr==SYS_process_vm_readv || nr==SYS_process_vm_writev || nr==SYS_kcmp) {
        pid_t target=(pid_t)call->args[index];
        if (target>0) {
            pid_t mapped=native_pid(t,target);
            if (!mapped) { return_value(t,r,nr==SYS_wait4 ? -ECHILD : -ESRCH); return 1; }
            native.args[index]=mapped;
            if (signaling) {
                struct thread *to=find_thread(mapped);
                int signal=(int)call->args[index+1];
                if (signal<0 || signal>=NSIG) { return_value(t,r,-EINVAL); return 1; }
                if (to && !to->born) { return_value(t,r,-ESRCH); return 1; }
                if (to && !signal_allowed(t,to,signal)) { return_value(t,r,-EPERM); return 1; }
                if (!to) {
                    struct md_task_retired_view retired=md_task_registry_retired(tasks,mapped);
                    if (!retired.identity) { return_value(t,r,-ESRCH); return 1; }
                    if (!signal_identity_allowed(t,retired.identity,mapped,signal)) {
                        return_value(t,r,-EPERM); return 1;
                    }
                }
                if (to && init_signal_suppressed(t,to,signal)) { return_value(t,r,0); return 1; }
                if (to && to->signals.host && signal==SIGSYS) {
                    if (nr==SYS_rt_sigqueueinfo || nr==SYS_rt_tgsigqueueinfo) {
                        execution_begin(t,call,r,"signal-queue-mask");
                    } else return_value(t,r,signal_logical_send(t,call,NULL));
                    return 1;
                }
            }
        } else if (nr==SYS_wait4 && target<-1) {
            pid_t mapped=target==INT32_MIN ? 0 : native_pid(t,-target);
            if (!mapped) { return_value(t,r,-ECHILD); return 1; }
            native.args[index]=(uint64_t)-(long)mapped;
        }
        if (nr==SYS_setpgid && (pid_t)call->args[1]>0) {
            pid_t mapped=native_pid(t,(pid_t)call->args[1]);
            if (!mapped) { return_value(t,r,-EPERM); return 1; }
            native.args[1]=mapped;
        }
        if (nr==SYS_kcmp && (pid_t)call->args[1]>0) {
            pid_t mapped=native_pid(t,(pid_t)call->args[1]);
            if (!mapped) { return_value(t,r,-ESRCH); return 1; }
            native.args[1]=mapped;
        }
    }
    if (nr==SYS_waitid && (call->args[0]==P_PID || call->args[0]==P_PGID) && (pid_t)call->args[1]>0) {
        pid_t mapped=native_pid(t,(pid_t)call->args[1]);
        if (!mapped) { return_value(t,r,-ECHILD); return 1; }
        native.args[1]=mapped;
    }
    if (nr==SYS_kcmp || nr==SYS_get_robust_list) {
        unsigned count=nr==SYS_kcmp ? 2 : 1;
        for (unsigned i=0;i<count;++i) if ((pid_t)native.args[i]>0
                && !inspect_allowed(t->pid,(pid_t)native.args[i],0)) {
            return_value(t,r,-EPERM); return 1;
        }
    }
    if (nr==SYS_process_vm_readv || nr==SYS_process_vm_writev) {
        struct thread *target=find_thread((pid_t)native.args[0]);
        t->process_op.inspection_denied=target && !inspect_allowed(t->pid,target->pid,0);
        /* Let the kernel validate vectors and empty transfers before reporting
         * the access denial. An impossible PID cannot expose target memory. */
        if (t->process_op.inspection_denied) native.args[0]=(uint64_t)-1;
        CHECK(!t->memory_op.remote);
        if (target && !t->process_op.inspection_denied) {
            t->memory_op.remote=md_memory_fork(target->memory,1); CHECK(t->memory_op.remote);
        }
    }
    if (entry_pending(t)) {
        t->execution.request=*call;
        uint64_t args[6]; memcpy(args,native.args,sizeof(args));
        entry_effect(t,native.nr,args,process_entry_complete);
    } else {
        execution_retain(t,call,r); t->phase=PROCESS_OBSERVING;
        struct user_pt_regs regs=*r;
        memcpy(regs.regs,native.args,sizeof(native.args));
        if (set_registers(t->pid,&regs)) resume(t->pid,0);
    }
    return 1;
}

int freezer_parked(const struct thread *t) {
    return task_parked(t) || t->phase==DEBUG_PARK_EXIT;
}

void freezer_progress(void) {
    uint64_t generation=md_cgroup_freezer_generation(freezer);
    if (generation==freezer_generation) return;
    freezer_generation=generation;
    /* Empty frozen groups may acquire newborn members without another write. */
    freezer_enabled=1;
    for (struct thread *t=threads;t;t=t->next) if (t->pid && t->born) {
        if (md_cgroup_member_freezing(t->resources.cgroup) || t->freezer_resume)
            SUPERVISOR_TRACE("PROBE freezer-member generation=%llu pid=%d phase=%d freezing=%d stopped=%d listening=%d held=%d boundary=%d delivery=%d memory=%d\n",
                (unsigned long long)generation,t->pid,t->phase,md_cgroup_member_freezing(t->resources.cgroup),
                t->kernel_stopped,t->listening,t->freezer_resume,debugger_at_boundary(t),t->signals.delivery.active,
                memory_available(t->pid));
        if (!md_cgroup_member_freezing(t->resources.cgroup) || t->terminal_stop) {
            md_cgroup_member_frozen(t->resources.cgroup,0);
            if (t->freezer_resume) {
                int sig=t->freezer_signal;
                t->freezer_resume=t->freezer_signal=0;
                resume(t->pid,sig);
            }
            continue;
        }
        if (t->listening || freezer_parked(t)
                || (t->kernel_stopped && debugger_at_boundary(t))) {
            md_cgroup_member_frozen(t->resources.cgroup,1);
        } else if ((debugger_at_boundary(t) || t->phase==OBSERVING || t->phase==PROCESS_OBSERVING)
                && !t->kernel_stopped && t->initial_stop && !t->interrupt_pending
                && !t->resume_lost && memory_available(t->pid)) {
            /* EVENT_WAIT: interrupt confirms suspension; exit retires the
             * member. No guest job-control or signal disposition is changed. */
            tracee_request(t,PTRACE_INTERRUPT,NULL,NULL,"cgroup-freeze");
        }
    }
}

static int private_child_matches(struct private_child_selection *s,pid_t pid) {
    pid_t known_parent,group; int signal;
    int known=retained_child_identity(retained_actor(pid),&known_parent,&group,&signal);
    long parent=known ? known_parent : md_task_parent(pid);
    if (parent==-ENOENT || parent==-ESRCH) return 0;
    if (parent<0) return (int)parent;
    if (parent!=s->caller->tgid) return 0;
    if (s->group || !(s->options&__WALL)) {
        int error=known ? 0 : md_task_child_class(pid,&group,&signal);
        if (error==-ENOENT || error==-ESRCH) return 0;
        if (error) return error;
        if (s->group && group!=s->group) return 0;
        if (!(s->options&__WALL) && ((signal==SIGCHLD)==!!(s->options&__WCLONE))) return 0;
    }
    return 1;
}

static void private_child_candidate(struct private_child_selection *s,pid_t pid) {
    pid_t number=visible_pid(s->caller,pid);
    if (!number || s->error) return;
    int matches=private_child_matches(s,pid);
    if (matches<0) s->error=matches;
    else if (matches) {
        if (s->count==s->capacity) {
            size_t capacity=s->capacity ? s->capacity*2 : 16;
            if (capacity>SIZE_MAX/sizeof(*s->candidates)) { s->error=-ENOMEM; return; }
            pid_t *next=realloc(s->candidates,capacity*sizeof(*next));
            if (!next) { s->error=-ENOMEM; return; }
            s->candidates=next; s->capacity=capacity;
        }
        s->candidates[s->count++]=number;
    }
}

static void private_child_retired(void *context,pid_t pid,struct md_task_retired_view view) {
    (void)view;
    private_child_candidate(context,pid);
}

int private_wait_children(pid_t pid,const struct seccomp_data *call,pid_t **candidates,size_t *count) {
    *candidates=NULL; *count=0;
    if (!retained_memories) return 0;
    struct thread *t=find_thread(pid);
    CHECK(t);
    int id=call->nr==SYS_waitid;
    pid_t selected=(pid_t)call->args[id ? 1 : 0];
    if (id && call->args[0]==P_PIDFD) return 0;
    if ((id && call->args[0]==P_PID) || (!id && selected>0)) {
        return retained_actor(native_pid(t,selected))!=NULL;
    }
    struct private_child_selection selection={.caller=t,
        .options=(unsigned)call->args[id ? 3 : 2]};
    int grouped=id ? call->args[0]==P_PGID : selected!=-1;
    if (grouped) {
        if (selected==INT32_MIN) return -ECHILD;
        selection.group=selected ? native_pid(t,id ? selected : -selected) : getpgid(pid);
        if (selection.group<0) return -errno;
        if (!selection.group) return -ECHILD;
    }
    int hidden=0;
    for (struct retained_memory *m=retained_memories;m;m=m->next) if (m->retained.actor) {
        int matches=private_child_matches(&selection,m->retained.actor);
        if (matches<0) return matches;
        if (matches) { hidden=1; break; }
    }
    if (!hidden) return 0;
    for (struct thread *p=threads;p;p=p->next)
        if (p->pid && p->pid==p->tgid) private_child_candidate(&selection,p->pid);
    md_task_registry_retired_processes(tasks,private_child_retired,&selection);
    if (selection.error) { free(selection.candidates); return selection.error; }
    *candidates=selection.candidates; *count=selection.count;
    return 1;
}

int pid_gone(pid_t pid) {
    /* Proc invisibility is not process death. EPERM still proves existence. */
    return kill(pid,0)<0 && errno==ESRCH;
}

static struct thread *process_thread(pid_t group) {
    struct thread *t=find_thread(group);
    if (t) return t;
    for (t=threads;t;t=t->next) if (t->pid && t->tgid==group) return t;
    return NULL;
}

static int pid_collectible(pid_t pid) {
    struct thread *parent=process_thread(md_task_registry_retired(tasks,pid).parent);
    return (!parent || !md_signal_pending_child_dirty(parent->signals.pending)) && pid_gone(pid);
}

static int pid_collection_waiting(const struct thread *t) {
    return t && t->pid && (t->entry.effect==process_entry_complete
        || t->phase==PROCESS_OBSERVING || t->process_op.wait_result
        || debugger_wait_pending(t));
}

void pid_collect(void) {
    if (!pid_collection_pending || !md_task_registry_has_retired(tasks)) {
        pid_collection_pending=0; pid_collection_blocker=NULL; return;
    }
    /* Thread slots remain allocated until teardown. Revalidate the last waiter
     * instead of rescanning the tree at every unrelated syscall event. A stale
     * hint never authorizes collection: only the complete scan below can. */
    if (pid_collection_waiting(pid_collection_blocker)) return;
    pid_collection_blocker=NULL;
    for (struct thread *t=threads;t;t=t->next) if (pid_collection_waiting(t)) {
        pid_collection_blocker=t; return;
    }
    /* Exit may precede translation of a completed kernel wait. Only quiescent
     * wait ownership permits collecting automatically reaped identities. */
    md_task_registry_collect(tasks,pid_collectible); pid_collection_pending=0;
}

static int publish_birth(struct thread *t,pid_t child,unsigned event) {
    pid_t pid=t->pid;
    if (event==PTRACE_EVENT_VFORK) {
        CHECK(!t->process_op.vfork_child);
        t->process_op.vfork_child=(pid_t)child;
    }
    struct thread *c = thread(child);
    CHECK(!c->born);
    c->born = 1; c->ready = t->ready;
    c->process_op.vfork_borrowed=event==PTRACE_EVENT_VFORK || ((t->process_op.cloning&CLONE_THREAD) && t->process_op.vfork_borrowed);
    c->parent=t->process_op.cloning&(CLONE_PARENT|CLONE_THREAD) ? t->parent : t->tgid; c->entered=t->entered;
    c->image.process_image=t->image.process_image;
    c->image.executable=md_process_executable_retain(t->image.executable);
    c->image.executable_origin=md_fs_anchor_retain(t->image.executable_origin);
    /* A newborn has no syscall entry of its own. Adapter setup must
     * not publish the parent's pending clone exit as a child stop. */
    c->debug.call_active=0;
    c->debug.options=t->debug.options;
    c->terminal_observation=t->terminal_observation;
    c->process_op.filter=t->process_op.filter;
    int prior=t->seccomp.admission_policy_valid && !(t->process_op.cloning&CLONE_THREAD);
    c->seccomp.application_filter=prior ? t->seccomp.admission_application_filter : t->seccomp.application_filter;
    md_seccomp_policy_replace(&c->seccomp.policy,prior ? t->seccomp.admission_policy : t->seccomp.policy);
    c->signals.host=t->signals.host; c->signals.host_blocked=t->signals.host_blocked;
    if (t->signals.pending) {
        md_signal_pending_release(c->signals.pending);
        c->signals.pending=md_signal_pending_fork(t->signals.pending,!!(t->process_op.cloning&CLONE_THREAD),
            !!(t->process_op.cloning&CLONE_SIGHAND));
        CHECK(c->signals.pending);
    }
    if (t->signals.actions) {
        c->signals.actions=md_signal_actions_fork(t->signals.actions,!!(t->process_op.cloning&CLONE_SIGHAND),
            !!(t->process_op.cloning&CLONE_CLEAR_SIGHAND));
        CHECK(c->signals.actions);
    }
    c->entry.blocked=t->entry.blocked;
    c->entry.registration_revoked=t->entry.registration_revoked;
    md_identity_release(&c->identity);
    c->identity = md_identity_copy(&t->identity);
    c->tgid = t->process_op.cloning & CLONE_THREAD ? t->tgid : c->pid;
    CHECK(!md_shm_space_release(c->shm_space));
    c->shm_space = md_shm_space_fork(t->shm_space,!!(t->process_op.cloning & CLONE_VM)); CHECK(c->shm_space);
    CHECK(!md_resources_release(&c->resources));
    if (t->resources.fs) CHECK(!md_resources_fork(&t->resources,t->process_op.cloning,t->resource_change.next.cgroup,&c->resources));
    CHECK(t->process_op.reservation);
    c->resources.process=t->process_op.reservation; t->process_op.reservation=NULL;
    md_process_member_forked(t->resources.process);
    CHECK(!md_resources_unshare_finish(&c->resources,&c->identity,&t->resource_change,0));
    md_pid_identity_release(c->resources.pid);
    c->resources.pid=t->process_op.pid_reservation; t->process_op.pid_reservation=NULL;
    int published=md_pid_identity_publish(c->resources.pid,c->pid,c->tgid);
    if (published) errno=-published;
    CHECK(!published);
    c->process_op.init_pending=md_pid_identity_number(c->resources.pid,md_pid_identity_namespace(c->resources.pid))==1;
    if (!(t->process_op.cloning&CLONE_THREAD)) {
        md_pid_identity_release(c->resources.pid_group);
        c->resources.pid_group=md_pid_identity_retain(c->resources.pid);
    }
    if (t->process_op.cloning&CLONE_NEWPID)
        SUPERVISOR_TRACE("PROBE pid-namespace init=%d visible=1 depth=%u\n",c->pid,
            md_pid_namespace_depth(md_pid_identity_namespace(c->resources.pid)));
    t->process_op.result=md_pid_identity_number(c->resources.pid,md_pid_identity_namespace(t->resources.pid));
    if (t->process_op.parent_address && (t->process_op.cloning&CLONE_VM)) {
        CHECK(!t->process_op.birth_child && !c->process_op.birth_parent);
        pid_store_add(c,t->process_op.parent_address,(pid_t)t->process_op.result);
        t->process_op.parent_address=0;
        t->process_op.birth_child=c; c->process_op.birth_parent=t;
    }
    if (c->resources.pid && (t->process_op.cloning&CLONE_CHILD_SETTID)) {
        uintptr_t address=t->execution.request.args[4];
        if (t->process_op.clone_prepared) {
            struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
            address=((struct clone_args *)span.data)->child_tid;
        }
        pid_store_add(c,address,md_pid_identity_number(c->resources.pid,md_pid_identity_namespace(c->resources.pid)));
    }
    md_fd_selector_release(c->descriptors.selector);
    c->descriptors.selector=t->descriptors.fork_selector; t->descriptors.fork_selector=NULL;
    CHECK(c->descriptors.selector);
    if (t->descriptors.running) fd_finish(t,(long)child);
    md_memory_release(c->memory);
    c->memory = md_memory_fork(t->memory, !!(t->process_op.cloning & CLONE_VM)); CHECK(c->memory);
    /* Linux resets robust registration in every new thread, including fork.
     * Only CLONE_VM inherits the private executor's address-space lifetime. */
    if ((t->process_op.cloning&CLONE_VM) && t->memory_actor) {
        c->memory_actor=t->memory_actor; ++c->memory_actor->members;
    }
    c->memory_op.access_pending=!(t->process_op.cloning&CLONE_VM);
    int untraced=!!(t->process_op.cloning&CLONE_UNTRACED);
    t->process_op.cloning = 0;
    memcpy(c->endpoint, t->endpoint, sizeof(c->endpoint));
    /* Birth publishes membership before the parent can exit or move.
     * A debugger-held newborn is live even before its first resume. */
    CHECK(!md_task_registry_publish(tasks,c->pid,c->tgid,&c->identity,&c->resources));
    if (cancelling) cancel_children(cancelling == 1 ? SIGTERM : SIGKILL);
    /* EVENT_WAIT: a newborn stop and its parent's birth event can
     * arrive in either order. Both must precede the child's resume. */
    int debug_birth=untraced ? 0 : md_debugger_birth(debugger,pid,c->pid,event); CHECK(debug_birth>=0);
    c->debug.birth=debug_birth;
    if (c->terminal_stop) {
        if (!md_debugger_event(debugger,c->pid,PTRACE_EVENT_EXIT,c->close.message)) resume(c->pid,0);
    } else if (c->initial_stop) resume(c->pid,0);
    return debug_birth;
}

void task_terminal_event(struct thread *t,pid_t pid,int status) {
    status=process_reboot_status(pid,status);
    int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    if (pid == leader && !cancelling) result = code;
    if (code) SUPERVISOR_TRACE("PROBE exit pid=%d status=%d ready=%d calls=%u\n", pid, code, t->ready, t->calls);
    int traced=md_debugger_traced(debugger,pid);
    if (t->born && (t->pid==t->tgid || traced)) {
        md_task_registry_retire(tasks,pid,t->parent,&t->identity);
        struct thread *parent=process_thread(t->parent);
        if (parent) md_signal_pending_child_changed(parent->signals.pending);
        pid_collection_pending=1;
    }
    if (t->resources.pid) {
        if (md_pid_identity_exit(t->resources.pid)) {
            struct md_pid_namespace *space=md_pid_identity_namespace(t->resources.pid);
            for (struct thread *member=threads;member;member=member->next)
                if (member->pid && member!=t && member->resources.pid
                        && md_pid_namespace_contains(space,md_pid_identity_namespace(member->resources.pid)))
                    syscall(SYS_tgkill,member->tgid,member->pid,SIGKILL);
        }
        md_pid_identity_release(t->resources.pid);
        pid_collection_pending=1;
        t->resources.pid=NULL;
    }
    md_debugger_exit(debugger,pid,status);
    release_thread(t); return;
}

void task_exit_event(struct thread *t,pid_t pid,unsigned event) {
    if (t->born && !process_thread(t->parent)) {
        long parent=md_task_parent(pid);
        if (parent>=0) t->parent=(pid_t)parent;
    }
    if (!t->born) {
        /* EVENT_WAIT: group death can overtake the newborn's initial
         * stop. Retain its terminal stop until parent birth publishes
         * ownership; cancellation still drains it through wait4. */
        t->initial_stop=1; t->terminal_stop=1;
        if (!tracee_request(t,PTRACE_GETEVENTMSG,NULL,&t->close.message,"newborn-exit")) return;
        t->close.terminal_pending=1;
        return;
    }
    if (t->descriptors.running && t->process_op.reservation && md_clone_call(t->execution.request.nr)) {
        unsigned long argument=t->process_op.clone_prepared
            ? md_memory_buffer_address(t->memory,&t->fs.buffer)
            : t->process_op.cloning&~((unsigned long)t->resource_change.namespaces|CLONE_UNTRACED);
        long child=md_task_birth_terminal(native_trace,pid,t->execution.request.nr,argument);
        if (child==-ESRCH) { t->resume_lost=1; return; }
        if (child<0) errno=(int)-child;
        CHECK(child>=0);
        if (child) {
            unsigned signal=t->process_op.cloning&CSIGNAL;
            if (t->process_op.clone_prepared) {
                struct md_buffer_span span; CHECK(!md_buffer_span(&t->fs.buffer,&span));
                signal=((struct clone_args *)span.data)->exit_signal;
            }
            unsigned birth=t->process_op.cloning&CLONE_VFORK ? PTRACE_EVENT_VFORK
                : signal==SIGCHLD ? PTRACE_EVENT_FORK : PTRACE_EVENT_CLONE;
            SUPERVISOR_TRACE("PROBE unreported-birth parent=%d child=%ld event=%u\n",pid,child,birth);
            t->resume_lost=1;
            publish_birth(t,(pid_t)child,birth);
        }
    }
    struct md_svc_registry *machine=md_memory_svc(t->memory);
    if (machine) {
        int error=md_svc_machine_retire(native_trace,machine,pid,t->phase==IDLE ? NULL : &t->execution.registers);
        if (error==-ESRCH) { t->resume_lost=1; return; }
        if (error) errno=-error;
        CHECK(!error);
    }
    if (machine && statistics && t->pid==t->tgid) {
        struct md_svc_payload_state state;
        if (!md_svc_registry_read(pid,machine->frames+MD_SVC_POOL_SIZE,&state,sizeof(state)))
            fprintf(stderr,"SVC_RUNTIME_STATS {\"pid\":%d,\"sites\":%u,\"completed\":%llu,"
                "\"declined\":%llu,\"unknown\":%llu,\"retiredClients\":%llu,\"revoked\":%u,"
                "\"filteredCompleted\":%llu,\"nativeCompleted\":%llu}\n",
                pid,machine->count,(unsigned long long)state.completed,
                (unsigned long long)state.declined,(unsigned long long)state.unknown,
                (unsigned long long)state.retired_clients,machine->disabled,
                (unsigned long long)(machine->filter_statistics && state.completed>=machine->filter_completed_start
                    ? state.completed-machine->filter_completed_start : 0),
                (unsigned long long)state.native_completed);
    }
    /* ESRCH invalidates the preceding stop, not this newly observed
     * terminal stop. Registers may be inspected here, never fabricated. */
    int resume_lost=t->resume_lost;
    t->resume_lost=0; t->terminal_stop=1;
    if (machine) machine->clients_dirty=1;
    /* A live mm participant collects retired slots on its next
     * resume. The exiting task is no longer a reliable memory reader. */
    if (t->descriptors.running && t->descriptors.capture) {
        struct user_pt_regs terminal;
        if (!registers(pid,&terminal)) return;
        long publication;
        if (terminal.pc==abi.capture_gate && terminal.regs[8]==SYS_openat
                && md_fd_mutation_terminal_open(&t->descriptors.mutation,terminal.regs[0],&publication))
            fd_finish(t,publication);
    }
    fd_discard(t);
    adapter_discard(t);
    CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change,-ECANCELED));
    unsigned long message;
    if (!tracee_request(t,PTRACE_GETEVENTMSG,NULL,&message,"debug-exit")) return;
    message=(unsigned)process_reboot_status(pid,(int)message);
    SUPERVISOR_TRACE("PROBE terminal-stop pid=%d lost=%d status=%#lx\n",pid,resume_lost,message);
    if (signal_diagnostics && !diagnostics && (message&0x7f))
        SUPERVISOR_SIGNAL_TRACE("PROBE terminal-stop pid=%d lost=%d status=%#lx\n",pid,resume_lost,message);
    if (diagnostics && (message&0x7f)==SIGSYS) {
        struct user_pt_regs terminal;
        if (registers(pid,&terminal))
            SUPERVISOR_TRACE("PROBE filter-death pid=%d nr=%lu pc=%lx arg0=%lx adapted=%lu\n",
                pid,(unsigned long)terminal.regs[8],(unsigned long)terminal.pc,
                (unsigned long)terminal.regs[0],(unsigned long)t->execution.request.nr);
    }
    /* Linux exposes the exit stop before retiring files. The virtual
     * debugger may service FUSE itself, so FLUSH starts only after its
     * continuation, never before delivery of PTRACE_EVENT_EXIT. */
    t->close.terminal_pending=1; t->close.message=message;
    if (!md_debugger_event(debugger,pid,event,message)) resume(pid,0);
}

void task_vfork_done_event(struct thread *t,pid_t pid,unsigned event) {
    unsigned long child;
    if (!tracee_request(t,PTRACE_GETEVENTMSG,NULL,&child,"vfork-complete")) return;
    CHECK(t->process_op.vfork_child==(pid_t)child);
    t->process_op.vfork_child=0;
    if (!md_debugger_event(debugger,pid,event,child)) resume(pid,0);
}

void task_exec_event(struct thread *t,pid_t pid,unsigned event) {
    task_action_discard(t);
    md_svc_machine_forget(&t->machine);
    /* A non-leader exec replaces the task behind the leader's PID. */
    md_broker_forget(broker, pid);
    cooperative_buffer_release(t);
    completed_event_discard(t);
    unsigned long old; CHECK(!native_trace(PTRACE_GETEVENTMSG, pid, NULL, &old));
    t->debug.exec_old=(pid_t)old;
    struct thread *executing=old && old!=(unsigned)pid ? find_thread((pid_t)old) : t;
    struct md_fd_closed exec_closed={0};
    if (executing) {
        if (executing->descriptors.running) fd_finish(executing,0);
        else CHECK(!md_resources_exec_closed(&executing->resources,executing->identity.user_namespace,&executing->close.closed));
        exec_closed=executing->close.closed; executing->close.closed=(struct md_fd_closed){0};
    }
    if (old && old != (unsigned)pid) {
        md_debugger_rekey(debugger,(pid_t)old,pid);
        struct thread *former = find_thread((pid_t)old);
        if (former) {
            md_signal_delivery_clear(&t->signals.delivery);
            t->signals.delivery=former->signals.delivery;
            t->signals.delivery.capture_context=t;
            former->signals.delivery=(struct md_signal_delivery){0};
            t->signals.origin=former->signals.origin;
            former->signals.origin.valid=0;
            t->terminal_observation=former->terminal_observation;
            t->debug.options=former->debug.options;
            t->debug.call_active=former->debug.call_active;
            t->debug.step_call=former->debug.step_call;
            t->trace_options_dirty=former->trace_options_dirty;
            t->process_op.filter=former->process_op.filter;
            t->seccomp.application_filter=former->seccomp.application_filter;
            md_seccomp_policy_replace(&t->seccomp.policy,NULL);
            t->seccomp.policy=former->seccomp.policy; former->seccomp.policy=NULL;
            md_signal_actions_release(t->signals.actions);
            t->signals.actions=former->signals.actions; former->signals.actions=NULL;
            t->signals.host=former->signals.host;
            t->signals.host_blocked=former->signals.host_blocked;
            md_signal_pending_release(t->signals.pending);
            t->signals.pending=former->signals.pending; former->signals.pending=NULL;
            t->entry.blocked=former->entry.blocked;
            release_operation(t);
            CHECK(!md_resources_unshare_finish(&t->resources,&t->identity,&t->resource_change, -ECANCELED));
            CHECK(!md_resources_release(&t->resources));
            t->resources = former->resources;
            former->resources = (struct md_task_resources){0};
            md_pid_identity_exec(t->resources.pid,t->resources.pid_group);
            md_task_registry_reap(tasks,pid);
            md_pid_identity_release(t->resources.pid);
            t->resources.pid=md_pid_identity_retain(t->resources.pid_group);
            exec_images_release(t);
            memcpy(t->image.images,former->image.images,sizeof(t->image.images));
            t->execfd_origin=former->execfd_origin; former->execfd_origin=NULL;
            t->image.prepared_executable=former->image.prepared_executable; former->image.prepared_executable=NULL;
            t->image.prepared_executable_origin=former->image.prepared_executable_origin;
            former->image.prepared_executable_origin=NULL;
            t->image.mount_flags=former->image.mount_flags;
            t->image.setid=former->image.setid; former->image.setid=NULL;
            former->image.images[0]=former->image.images[1]=former->image.images[2]=-1;
            md_identity_release(&t->identity);
            t->identity = md_identity_copy(&former->identity);
            md_fd_selector_release(t->descriptors.selector);
            t->descriptors.selector = former->descriptors.selector; former->descriptors.selector = NULL;
            memcpy(t->endpoint, former->endpoint, sizeof(t->endpoint));
            release_thread(former);
        }
    }
    fd_discard(t);
    adapter_discard(t);
    signal_control_discard(t);
    CHECK(!md_signal_pending_exec(t->signals.pending));
    if (t->signals.actions) {
        struct md_signal_actions *next=md_signal_actions_exec(t->signals.actions); CHECK(next);
        md_signal_actions_release(t->signals.actions); t->signals.actions=next;
    }
    exec_images_activate(t);
    md_memory_stack_return(t->memory, t->execution.stack);
    CHECK(!md_shm_space_release(t->shm_space));
    t->shm_space = md_shm_space_new(); CHECK(t->shm_space);
    ipc_unwatch(t);
    CHECK(!md_ipc_cancel(md_ipc_namespace_active(t->resources.ipc),&t->io.pending)); t->io.cancel=t->io.waking=0;
    md_ipc_transport_close(&t->io.transport);
    struct md_interception_memory *memory=md_memory_new_image(t->memory); CHECK(memory);
    t->process_op.vfork_borrowed=0;
    retained_detach(t);
    md_memory_release(t->memory); t->memory=memory;
    t->memory_op.access_pending=0;
    t->phase = IDLE; t->execution.stack = 0; t->ready = 0; t->resume_lost = 0; t->terminal_stop=0; images++;
    t->image.mapped = 0; t->entered = 0; t->image.backings_ready=0;
    /* Entry revocation belongs to the retired address space. Only
     * inherited application policy still needs admission in this image. */
    t->entry.blocked=!!t->seccomp.application_filter;
    t->entry.registration_revoked=0;
    t->process_op.preflight=t->seccomp.preflight=0;
    t->image.process_image=0;
    /* Exec retires the old syscall instruction. Its completion belongs
     * to the executing thread, not a replaced leader's interrupted call. */
    t->debug.replay_entry=0;
    t->debug.adapter=t->debug.call_active;
    CHECK(!t->close.closed.first); t->close.closed=exec_closed;
    close_event_begin(t,event,old); adapter_progress();
}

void task_birth_event(struct thread *t,pid_t pid,unsigned event) {
    int terminal=0;
    long child=md_task_birth_read(native_trace,pid,event,&terminal);
    if (child==-ESRCH) { t->resume_lost=1; return; }
    if (child<0) errno=(int)-child;
    CHECK(child>0);
    if (terminal) {
        SUPERVISOR_TRACE("PROBE terminal-birth parent=%d child=%ld event=%u\n",pid,child,event);
        t->resume_lost=1;
    }
    if (!publish_birth(t,(pid_t)child,event)) resume(pid,0);
}

void task_stop_event(struct thread *t,pid_t pid,int status,int sig) {
    if (sig == SIGSTOP || sig == SIGTSTP || sig == SIGTTIN || sig == SIGTTOU) {
        if (debugger_at_boundary(t) && md_debugger_stop(debugger,pid,status,NULL,0)) return;
        task_group_stop(t);
        return;
    }
    int wake = t->listening;
    if (wake) {
        CHECK(sig == SIGTRAP); t->listening = 0; group_wakes++;
        md_debugger_child_changed(debugger,pid);
    }
    if (process_wait_interrupt(t)) {
        if (!t->resume_lost) resume(pid,0);
        return;
    }
    if (interrupted_operation(t)) return;
    if (wake) { resume(pid, 0); return; }
    if (t->initial_stop && debugger_at_boundary(t) && t->entered && debugger_boundary(t)) {
        t->initial_stop=1; return;
    }
    CHECK(sig == SIGTRAP);
    if (t->initial_stop) {
        /* SIGCONT can clear a pending group-stop before this tracee
         * reports it. The kernel then reports EVENT_STOP/SIGTRAP without
         * an earlier LISTEN. This is not a second newborn stop. */
        CHECK(t->born); raced_wakes++; resume(pid, 0); return;
    }
    t->initial_stop = 1;
    if (t->born) resume(pid,0);
}

int task_clone_exit(struct thread *t,pid_t pid) {
    if (t->phase==CLONE_ENTRY) {
        struct ptrace_syscall_info info={0};
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"clone-replay-entry")) return 1;
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
            CHECK(info.entry.nr==SYS_clone3);
            t->phase=CLONE_ADMISSION;
        } else {
            CHECK(info.op==PTRACE_SYSCALL_INFO_EXIT);
            struct user_pt_regs replay=t->execution.registers;
            replay.pc=md_svc_registry_replay(md_memory_svc(t->memory),replay.pc);
            if (!set_registers(pid,&replay)) return 1;
        }
        resume(pid,0); return 1;
    }
    if (t->phase==CLONE_ADMISSION) {
        /* A concurrent filter may reject the replay before TRACE.
         * Retain that kernel result and retire the captured arguments. */
        CHECK(!buffer_cleanup(t)); t->process_op.clone_prepared=0;
        if (!restore_mask(t)) return 1;
        t->phase=IDLE; resume(pid,0); return 1;
    }
    return 0;
}

int task_process_exit(struct thread *t,pid_t pid) {
    if (t->phase==PROCESS_INIT_ENTRY) {
        struct ptrace_syscall_info info;
        if (!tracee_request(t,PTRACE_GET_SYSCALL_INFO,(void *)sizeof(info),&info,"pid-init-entry")) return 1;
        if (info.op==PTRACE_SYSCALL_INFO_ENTRY) {
            CHECK(info.entry.nr==SYS_prctl && info.instruction_pointer==RAW_GATE);
            t->phase=PROCESS_INIT_EXIT;
        }
        resume(pid,0); return 1;
    }
    if (t->phase==PROCESS_INIT_EXIT) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return 1;
        if ((long)regs.regs[0]<0) {
            fprintf(stderr,"Shroot: PID init subreaper admission failed: %ld\n",(long)regs.regs[0]);
            syscall(SYS_tgkill,t->tgid,t->pid,SIGKILL); resume(pid,0); return 1;
        }
        if (!set_registers(pid,&t->execution.registers) || !restore_mask(t)) return 1;
        t->phase=IDLE; resume(pid,0); return 1;
    }
    if (t->phase==PROCESS_OBSERVING) {
        struct user_pt_regs regs;
        if (!registers(pid,&regs)) return 1;
        long value=(long)regs.regs[0];
        /* Kernel restart retains orig_x0; restore every other argument
         * before signal delivery can save the interrupted context. */
        memcpy(regs.regs+1,t->execution.registers.regs+1,7*sizeof(regs.regs[0]));
        if (process_wait_output(t,value)) {
            /* The wait effect has completed. C copies and translates its
             * result without replaying the wait or packing siginfo in GPRs. */
            if (save_signal_mask(t,&t->execution.mask,"pid-wait-result-mask") && shield(t)) dispatch_with_stack(t);
            return 1;
        }
        regs.regs[0]=process_native_result(t,value);
        if (!set_registers(pid,&regs)) return 1;
        if (t->debug.call_active) t->debug.adapter=1;
        t->phase=IDLE; resume(pid,0); return 1;
    }
    return 0;
}

void task_wait_complete(struct thread *t,long result) {
    if (!t->process_op.wait_result) return;
    if (t->process_op.wait_result==2 && result>=0) {
        if ((t->execution.request.nr==SYS_wait4 || (t->execution.request.nr==SYS_waitid && !(t->execution.request.args[3]&WNOWAIT))) && (t->process_op.info_code==CLD_EXITED
                || t->process_op.info_code==CLD_KILLED || t->process_op.info_code==CLD_DUMPED))
            process_reap(t,t->process_op.info_native);
    }
    t->process_op.wait_result=0; t->process_op.info_code=0; t->process_op.info_native=0;
}

static void pid_store_complete(struct thread *t,int error) {
    /* Linux ignores an inaccessible CLONE_*SETTID destination. The birth has
     * already happened; do not turn its completed effect into a failed clone. */
    (void)error;
    --t->process_op.store_count; t->process_op.stores[0]=t->process_op.stores[1];
    if (!restore_mask(t)) return;
    resume(t->pid,0);
}

void pid_store_begin(struct thread *t) {
    struct user_pt_regs regs;
    if (!registers(t->pid,&regs)) return;
    execution_capture(t,&regs);
    if (!save_signal_mask(t,&t->execution.mask,"pid-store-mask") || !shield(t)) return;
    transfer_begin(t,&regs,t->process_op.stores[0].address,&t->process_op.stores[0].value,
        sizeof(pid_t),MD_TRANSFER_WRITE,pid_store_complete);
}
