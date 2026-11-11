#define _GNU_SOURCE
#include "event_signal.h"
#include "fs_continuation.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "identity_call.h"
#include "interception.h"
#include "linux_abi.h"
#include "process_access.h"
#include "process_limits.h"
#include "queue_signal.h"
#include "supervisor_actors.h"
#include "supervisor_execution.h"
#include "supervisor_fs.h"
#include "supervisor_identity.h"
#include "supervisor_internal.h"
#include "supervisor_task.h"
#include "task_helper.h"
#include "task_registry.h"
#include "task_status.h"
#include "user_namespace.h"
#include <stdlib.h>
#include <sys/prctl.h>

static void identity_copy_next(struct thread *t);
static int process_limit_apply(struct thread *t,const struct rlimit *next,struct rlimit *old);
static void identity_limit_finish(struct thread *t,int error,const struct rlimit *old);
static void identity_groups_call(struct thread *t, struct user_pt_regs *r);
static int process_limit_capture_allowed(struct thread *caller,struct thread *target);
static void process_limit_captured(struct thread *t,struct md_task_helper_result result);
static int process_limit_capture(struct thread *t,struct thread *target);

enum { COPY_GROUPS, COPY_CAP_HEADER, COPY_CAP_DATA, COPY_CAP_VERSION, COPY_LIMIT_INPUT, COPY_LIMIT_OUTPUT };

static int process_limit_apply(struct thread *t,const struct rlimit *next,struct rlimit *old) {
    pid_t pid=t->execution.request.nr==SYS_prlimit64 ? (pid_t)t->execution.request.args[0] : 0;
    if (pid) pid=native_pid(t,pid);
    else pid=t->pid;
    struct thread *target=find_thread(pid);
    struct md_task_retired_view retired=md_task_registry_retired(tasks,pid);
    const struct md_identity *id=target ? &target->identity : retired.identity;
    if (!id) return -ESRCH;
    unsigned resource=t->execution.request.args[t->execution.request.nr==SYS_prlimit64 ? 1 : 0];
    int error=md_process_limit(target ? target->resources.process : retired.process,
        &t->identity,id,target && target->tgid==t->tgid,resource,next,old,pid);
    if (target && !next && resource<RLIM_NLIMITS && resource!=RLIMIT_NOFILE && resource!=RLIMIT_NPROC
            && (error==-EACCES || error==-EPERM)
            && !md_process_limit_access(&t->identity,id,target->tgid==t->tgid))
        error=process_limit_capture(t,target);
    return error;
}

static void identity_limit_finish(struct thread *t,int error,const struct rlimit *old) {
    const struct user_pt_regs *r=&t->execution.registers;
    uintptr_t output=r->regs[8]==SYS_prlimit64 ? r->regs[3] : r->regs[8]==SYS_getrlimit ? r->regs[1] : 0;
    t->identity_copy.stage=COPY_LIMIT_OUTPUT;
    if (!error && (output || r->regs[8]==SYS_getrlimit)) {
        memcpy(t->identity_copy.buffer,old,sizeof(*old));
        t->identity_copy.loading=0; t->identity_copy.address=output;
        t->identity_copy.count=sizeof(*old)/sizeof(uint32_t); t->identity_copy.copied=0;
        identity_copy_next(t);
    } else identity_copy_finish(t,error);
}

void identity_copy_finish(struct thread *t, int error) {
    struct user_pt_regs r = t->execution.registers;
    if (!error && t->identity_copy.stage==COPY_LIMIT_INPUT) {
        struct rlimit next,old;
        memcpy(&next,t->identity_copy.buffer,sizeof(next));
        error=process_limit_apply(t,t->identity_copy.count ? &next : NULL,&old);
        if (error!=-EINPROGRESS) identity_limit_finish(t,error,&old);
        return;
    } else if (!error && t->identity_copy.stage == COPY_CAP_HEADER) {
        uint32_t version = t->identity_copy.buffer[0];
        int pid = (int)t->identity_copy.buffer[1];
        if (pid>0 && t->resources.pid) {
            pid=native_pid(t,pid);
            if (!pid) error=-ESRCH;
        }
        unsigned words = version == _LINUX_CAPABILITY_VERSION_1 ? 1
            : version == _LINUX_CAPABILITY_VERSION_2 || version == _LINUX_CAPABILITY_VERSION_3 ? 2 : 0;
        if (!words) {
            t->identity_copy.buffer[0] = _LINUX_CAPABILITY_VERSION_3;
            t->identity_copy.stage = COPY_CAP_VERSION; t->identity_copy.loading = 0;
            t->identity_copy.count = 1; t->identity_copy.copied = 0;
            t->identity_copy.result = r.regs[8] == SYS_capget && !r.regs[1] ? 0 : -EINVAL;
            identity_copy_next(t); return;
        }
        struct md_identity ids = md_identity_copy(&t->identity);
        if (!error && pid && pid != t->pid) {
            md_identity_release(&ids);
            struct md_task_snapshot snapshot={0};
            error = r.regs[8] == SYS_capset ? -EPERM : md_task_registry_read(tasks, pid, 0, &snapshot);
            ids=md_identity_copy(&snapshot.identity);
            md_task_snapshot_release(&snapshot);
        }
        if (!error && r.regs[8] == SYS_capget && !r.regs[1]) {
            md_identity_release(&ids); t->identity_copy.stage = COPY_CAP_DATA;
        } else if (!error) {
            if (r.regs[8] == SYS_capget) for (unsigned i = 0; i < words; i++) {
                t->identity_copy.buffer[i*3] = ids.caps.effective >> (32*i);
                t->identity_copy.buffer[i*3+1] = ids.caps.permitted >> (32*i);
                t->identity_copy.buffer[i*3+2] = ids.caps.inheritable >> (32*i);
            }
            md_identity_release(&ids);
            t->identity_copy.stage = COPY_CAP_DATA; t->identity_copy.loading = r.regs[8] == SYS_capset;
            t->identity_copy.address = r.regs[1]; t->identity_copy.count = words*3; t->identity_copy.copied = 0;
            identity_copy_next(t); return;
        } else md_identity_release(&ids);
    } else if (!error && t->identity_copy.stage == COPY_CAP_DATA && r.regs[8] == SYS_capset) {
        uint32_t *v = t->identity_copy.buffer;
        error = md_identity_capset(&t->identity, v[1] | ((uint64_t)v[4] << 32),
            v[0] | ((uint64_t)v[3] << 32), v[2] | ((uint64_t)v[5] << 32));
    }
    if (!error && t->identity_copy.stage == COPY_CAP_VERSION) error = t->identity_copy.result;
    if (!error && r.regs[8] == SYS_setgroups)
        error = md_identity_setgroups(&t->identity, t->identity_copy.buffer, t->identity_copy.count);
    r.regs[0] = (uint64_t)(error ? (long)error : r.regs[8] == SYS_getgroups ? (long)t->identity_copy.count : 0);
    free(t->identity_copy.buffer); t->identity_copy.buffer = NULL;
    if (!set_registers(t->pid, &r) || !restore_mask(t)) return;
    t->phase = IDLE; resume(t->pid, 0);
}

static void identity_copy_next(struct thread *t) {
    if (t->identity_copy.copied == t->identity_copy.count) { identity_copy_finish(t, 0); return; }
    struct user_pt_regs r = t->execution.registers;
    unsigned count = t->identity_copy.count - t->identity_copy.copied;
    if (count > 16) count = 16;
    uintptr_t address = t->identity_copy.address, offset = (uintptr_t)t->identity_copy.copied * sizeof(uint32_t);
    if (address > UINTPTR_MAX - offset - count * sizeof(uint32_t)) { identity_copy_finish(t, -EFAULT); return; }
    int loading = t->identity_copy.loading;
    r.pc = loading ? abi.load_groups : abi.store_groups;
    r.regs[0] = address + offset; r.regs[1] = count;
    if (!loading) for (unsigned i = 0; i < count; i++) r.regs[i+3] = t->identity_copy.buffer[t->identity_copy.copied+i];
    if (!set_registers(t->pid, &r)) return;
    t->phase = IDENTITY_COPY; resume(t->pid, 0);
}

static void identity_groups_call(struct thread *t, struct user_pt_regs *r) {
    int setting = r->regs[8] == SYS_setgroups;
    unsigned count = setting ? (unsigned)r->regs[0] : md_identity_group_count(&t->identity);
    int error = 0;
    if (setting && (!md_identity_capable(&t->identity, CAP_SETGID)
            || !md_user_namespace_may_setgroups(t->identity.user_namespace))) error = -EPERM;
    else if ((int)r->regs[0] < 0 || (setting && (unsigned)r->regs[0] > MD_IDENTITY_GROUPS_MAX)
            || (!setting && r->regs[0] && (unsigned)r->regs[0] < count)) error = -EINVAL;
    if (error || (!setting && !r->regs[0])) { return_value(t, r, error ? error : (long)count); return; }
    if (!count) { if (setting) md_identity_setgroups(&t->identity, NULL, 0); return_value(t, r, 0); return; }
    uint32_t *buffer = malloc((size_t)count * sizeof(*buffer));
    if (!buffer) { return_value(t, r, -ENOMEM); return; }
    if (!setting) for (unsigned i=0;i<count;++i)
        buffer[i]=md_identity_visible_id(&t->identity,1,md_identity_group_data(&t->identity)[i]);
    struct iovec local = {buffer, (size_t)count * sizeof(*buffer)}, remote = {(void *)r->regs[1], local.iov_len};
    ssize_t copied = setting ? process_vm_readv(t->pid, &local, 1, &remote, 1, 0)
        : process_vm_writev(t->pid, &local, 1, &remote, 1, 0);
    if (copied == (ssize_t)local.iov_len) {
        error = setting ? md_identity_setgroups(&t->identity, buffer, count) : 0;
        free(buffer); return_value(t, r, error ? error : setting ? 0 : (long)count); return;
    }
    /* Task-affine copies preserve protected-memory and EFAULT semantics without
     * relaxing dumpability. Identity publishes only after the complete read. */
    t->identity_copy.buffer = buffer; t->identity_copy.count = count; t->identity_copy.copied = 0; execution_capture(t,r);
    t->identity_copy.address = r->regs[1]; t->identity_copy.loading = setting; t->identity_copy.stage = COPY_GROUPS;
    if (!save_signal_mask(t,&t->execution.mask,"get-mask")
            || !shield(t) || !skip(t->pid)) return;
    identity_copy_next(t);
}

int process_limit_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r) {
    if (call->instruction_pointer == RAW_GATE) return 0;
    long nr=call->nr;
    unsigned resource=call->args[nr==SYS_prlimit64 ? 1 : 0];
    if (t->phase==IDLE && t->ready
            && (nr==SYS_prlimit64 || nr==SYS_getrlimit || nr==SYS_setrlimit)
            && (resource==RLIMIT_NPROC || resource==RLIMIT_NOFILE
                || (nr==SYS_prlimit64 && call->args[0] && !call->args[2]))) {
        if (entry_context(t,MD_CONTEXT_COPY)) return 1;
        t->identity_copy.buffer=calloc(1,sizeof(struct rlimit));
        if (!t->identity_copy.buffer) { return_value(t,r,-ENOMEM); return 1; }
        execution_capture(t,r); t->identity_copy.stage=COPY_LIMIT_INPUT;
        t->identity_copy.address=nr==SYS_prlimit64 ? call->args[2] : nr==SYS_setrlimit ? call->args[1] : 0;
        t->identity_copy.count=t->identity_copy.address || nr==SYS_setrlimit ? sizeof(struct rlimit)/sizeof(uint32_t) : 0;
        t->identity_copy.loading=1; t->identity_copy.copied=0;
        if (save_signal_mask(t,&t->execution.mask,"process-limit-copy")
                && shield(t) && skip(t->pid)) identity_copy_next(t);
        return 1;
    }
    return 0;
}

int identity_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r, unsigned long cookie) {
    if (call->instruction_pointer == RAW_GATE) return 0;
    long nr = call->nr, value;
    if (nr==SYS_prctl && ((int)call->args[0]==PR_GET_DUMPABLE || (int)call->args[0]==PR_SET_DUMPABLE)) {
        value=(int)call->args[0]==PR_GET_DUMPABLE ? md_process_dumpable(t->resources.access)
            : md_process_set_dumpable(t->resources.access,call->args[1]);
        return_value(t,r,value); return 1;
    }
    if (nr == SYS_prctl) {
        unsigned long args[4] = {call->args[1], call->args[2], call->args[3], call->args[4]};
        value = md_identity_cap_prctl(&t->identity, call->args[0], args);
        if (value != -ENOTSUP) { return_value(t, r, value); return 1; }
    }
    if (nr == SYS_prctl && (call->args[0] == PR_GET_NO_NEW_PRIVS || call->args[0] == PR_SET_NO_NEW_PRIVS)) {
        CHECK(t->phase == IDLE && t->ready);
        if (call->args[2] || call->args[3] || call->args[4]) value = -EINVAL;
        else if (call->args[0] == PR_GET_NO_NEW_PRIVS) value = call->args[1] ? -EINVAL : t->identity.no_new_privs;
        else value = md_identity_no_new_privs(&t->identity, call->args[1]);
        return_value(t, r, value); return 1;
    }
    if (cookie != MD_INTERCEPT_IDENTITY) return 0;
    CHECK(t->ready && t->phase == IDLE);
    switch (nr) {
    case SYS_capget: case SYS_capset:
        if (entry_context(t,MD_CONTEXT_COPY)) return 1;
        t->identity_copy.buffer = calloc(6, sizeof(uint32_t));
        if (!t->identity_copy.buffer) { return_value(t, r, -ENOMEM); return 1; }
        t->identity_copy.stage = COPY_CAP_HEADER; t->identity_copy.address = call->args[0];
        t->identity_copy.loading = 1; t->identity_copy.count = 2; t->identity_copy.copied = 0; execution_capture(t,r);
        if (!save_signal_mask(t,&t->execution.mask,"get-mask")
                || !shield(t) || !skip(t->pid)) return 1;
        identity_copy_next(t); return 1;
    case SYS_getgroups: case SYS_setgroups:
        if (!entry_context(t,MD_CONTEXT_COPY)) identity_groups_call(t,r);
        return 1;
    case SYS_getuid: case SYS_geteuid: case SYS_getgid: case SYS_getegid:
        value=md_identity_scalar_result(&t->identity,nr); break;
    case SYS_getresuid: case SYS_getresgid: {
        if (entry_context(t,MD_CONTEXT_COPY)) return 1;
        const struct md_identity_ids *ids = nr == SYS_getresuid ? &t->identity.uid : &t->identity.gid;
        execution_capture(t,r);
        if (!save_signal_mask(t,&t->execution.mask,"get-mask")
                || !shield(t) || !skip(t->pid)) return 1;
        int group=nr==SYS_getresgid;
        r->pc = STORE_IDS; r->regs[3] = md_identity_visible_id(&t->identity,group,ids->real);
        r->regs[4] = md_identity_visible_id(&t->identity,group,ids->effective);
        r->regs[5] = md_identity_visible_id(&t->identity,group,ids->saved);
        if (!set_registers(t->pid, r)) return 1;
        t->phase = STORING_IDS; resume(t->pid, 0); return 1;
    }
    case SYS_setresuid:
        value = md_identity_setresuid(&t->identity, call->args[0], call->args[1], call->args[2]); break;
    case SYS_setresgid:
        value = md_identity_setresgid(&t->identity, call->args[0], call->args[1], call->args[2]); break;
    case SYS_setuid: value = md_identity_setuid(&t->identity, call->args[0]); break;
    case SYS_setgid: value = md_identity_setgid(&t->identity, call->args[0]); break;
    case SYS_setreuid: value = md_identity_setreuid(&t->identity, call->args[0], call->args[1]); break;
    case SYS_setregid: value = md_identity_setregid(&t->identity, call->args[0], call->args[1]); break;
    case SYS_setfsuid: value = md_identity_setfsuid(&t->identity, call->args[0]); break;
    case SYS_setfsgid: value = md_identity_setfsgid(&t->identity, call->args[0]); break;
    default: value = -ENOTSUP; break;
    }
    if ((nr == SYS_setresuid || nr == SYS_setresgid) && !value) {
        identity_changes++;
        SUPERVISOR_TRACE("PROBE identity-change pid=%d uid=%u euid=%u suid=%u gid=%u egid=%u\n",
            t->pid, t->identity.uid.real, t->identity.uid.effective, t->identity.uid.saved,
            t->identity.gid.real, t->identity.gid.effective);
    }
    return_value(t, r, value); return 1;
}

static int process_limit_capture_allowed(struct thread *caller,struct thread *target) {
    return md_process_limit_access(&caller->identity,&target->identity,caller->tgid==target->tgid);
}

static void process_limit_captured(struct thread *t,struct md_task_helper_result result) {
    struct rlimit value;
    unsigned resource=t->execution.request.args[t->execution.request.nr==SYS_prlimit64 ? 1 : 0];
    int error=(int)result.value;
    if (!error) error=(int)md_task_limit_fd(result.descriptor,resource,&value);
    if (result.descriptor>=0) close(result.descriptor);
    identity_limit_finish(t,error,&value);
}

static int process_limit_capture(struct thread *t,struct thread *target) {
    struct md_task_credentials credentials;
    int error=md_task_registry_credentials(tasks,target->pid,&credentials);
    if (error) return error;
    struct md_fs_task_subject subject={target->pid,credentials.generation};
    md_task_credentials_release(&credentials);
    struct capture_owner *owner=calloc(1,sizeof(*owner)+64);
    if (!owner) return -ENOMEM;
    struct task_action *action=task_action_new(t);
    if (!action) { free(owner); return -ENOMEM; }
    char *path=(char *)(owner+1);
    snprintf(path,64,"/proc/%d/limits",target->pid);
    *owner=(struct capture_owner){.caller=t->pid,.subject=subject,.foreign=1,
        .target={path,O_RDONLY|O_CLOEXEC},.result={.descriptor=-1},
        .authorize=process_limit_capture_allowed,.complete=process_limit_captured};
    t->capture=owner; action->capture=owner;
    CHECK(!md_event_signal(fd_ready));
    return -EINPROGRESS;
}

int identity_checkpoint(struct thread *t,pid_t pid,uintptr_t checkpoint,struct user_pt_regs *regs) {
    if (checkpoint == STORED_IDS && t->phase == STORING_IDS) {
        (*regs) = t->execution.registers; regs->regs[0] = 0;
        if (!set_registers(pid, regs) || !restore_mask(t)) return 1;
        t->phase = IDLE; resume(pid, 0); return 1;
    }
    if (t->phase == IDENTITY_COPY && (checkpoint == abi.loaded_groups || checkpoint == abi.stored_groups)) {
        unsigned count = t->identity_copy.count - t->identity_copy.copied;
        if (count > 16) count = 16;
        if (checkpoint == abi.loaded_groups)
            for (unsigned i = 0; i < count; i++)
                t->identity_copy.buffer[t->identity_copy.copied+i] = regs->regs[i==5 ? 19 : i+3];
        t->identity_copy.copied += count; identity_copy_next(t); return 1;
    }
    return 0;
}
