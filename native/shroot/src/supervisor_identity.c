#define _GNU_SOURCE
#include "event_signal.h"
#include "fs_continuation.h"
#include "guest_execution.h"
#include "guest_identity.h"
#include "identity_call.h"
#include "identity_transfer.h"
#include "adapter_buffer.h"
#include "interception_buffers.h"
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

static int process_limit_capture(struct thread *t,struct thread *target);

void identity_discard(struct thread *t) {
    free(t->identity_transfer.groups);
    memset(&t->identity_transfer,0,sizeof(t->identity_transfer));
}

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

static long identity_groups(struct thread *t,unsigned operation,unsigned long offset,
        union md_identity_transfer *p) {
    const struct seccomp_data *call=&t->execution.request;
    int setting=call->nr==SYS_setgroups;
    unsigned count=setting ? (unsigned)call->args[0] : md_identity_group_count(&t->identity);
    if (setting && (!md_identity_capable(&t->identity,CAP_SETGID)
            || !md_user_namespace_may_setgroups(t->identity.user_namespace))) return -EPERM;
    if ((int)call->args[0]<0 || (setting && count>MD_IDENTITY_GROUPS_MAX)
            || (!setting && call->args[0] && (unsigned)call->args[0]<count)) return -EINVAL;
    if (operation==MD_IDENTITY_PREPARE && !offset) {
        identity_discard(t);
        if (setting && count) {
            t->identity_transfer.groups=malloc((size_t)count*sizeof(uint32_t));
            if (!t->identity_transfer.groups) return -ENOMEM;
        }
        t->identity_transfer.count=count;
        return count;
    }
    if (count!=t->identity_transfer.count) return -EINVAL;
    if (operation==MD_IDENTITY_COMMIT && setting && !offset) {
        if (t->identity_transfer.copied!=count) return -EINVAL;
        return md_identity_setgroups(&t->identity,t->identity_transfer.groups,count);
    }
    if (operation!=MD_IDENTITY_TRANSFER || offset>=count) return -EINVAL;
    unsigned n=count-(unsigned)offset;
    if (n>MD_IDENTITY_TRANSFER_WORDS) n=MD_IDENTITY_TRANSFER_WORDS;
    if (setting) {
        if (!t->identity_transfer.groups || offset!=t->identity_transfer.copied) return -EINVAL;
        memcpy(t->identity_transfer.groups+offset,p->words,n*sizeof(uint32_t));
        t->identity_transfer.copied+=n;
    } else for (unsigned i=0;i<n;++i)
        p->words[i]=md_identity_visible_id(&t->identity,1,md_identity_group_data(&t->identity)[offset+i]);
    return 0;
}

static long identity_capabilities(struct thread *t,unsigned operation,union md_identity_transfer *p) {
    uint32_t version=p->capabilities.version;
    unsigned words=version==_LINUX_CAPABILITY_VERSION_1 ? 1
        : version==_LINUX_CAPABILITY_VERSION_2 || version==_LINUX_CAPABILITY_VERSION_3 ? 2 : 0;
    if (!words) return -EINVAL;
    int setting=t->execution.request.nr==SYS_capset;
    int pid=p->capabilities.pid;
    if (pid>0 && t->resources.pid) {
        pid=native_pid(t,pid);
        if (!pid) return -ESRCH;
    }
    if (setting && pid && pid!=t->pid) return -EPERM;
    if (operation==MD_IDENTITY_COMMIT && setting) {
        uint32_t v[6]={0};
        memcpy(v,p->capabilities.data,words*3*sizeof(uint32_t));
        return md_identity_capset(&t->identity,v[1]|((uint64_t)v[4]<<32),
            v[0]|((uint64_t)v[3]<<32),v[2]|((uint64_t)v[5]<<32));
    }
    if (operation!=MD_IDENTITY_PREPARE) return -EINVAL;
    struct md_task_snapshot snapshot={0};
    const struct md_identity *id=&t->identity;
    int error=0;
    if (pid && pid!=t->pid) {
        error=md_task_registry_read(tasks,pid,0,&snapshot);
        id=&snapshot.identity;
    }
    if (!error && !setting) for (unsigned i=0;i<words;++i) {
        p->capabilities.data[i*3]=id->caps.effective>>(32*i);
        p->capabilities.data[i*3+1]=id->caps.permitted>>(32*i);
        p->capabilities.data[i*3+2]=id->caps.inheritable>>(32*i);
    }
    md_task_snapshot_release(&snapshot);
    return error;
}

void identity_buffer_control(struct thread *t,const struct supervisor_control_request *control) {
    long result=-EINVAL;
    struct md_buffer_span span;
    const struct seccomp_data *call=&t->execution.request;
    if (t->phase==DISPATCHING && t->fs.buffer.pool && t->fs.open.fd<0
            && md_identity_transfer_call(call->nr) && !md_buffer_span(&t->fs.buffer,&span)
            && span.size>=sizeof(union md_identity_transfer)) {
        union md_identity_transfer *p=span.data;
        unsigned operation=control->args[0];
        unsigned long offset=control->args[1];
        long nr=call->nr;
        if (nr==SYS_getgroups || nr==SYS_setgroups) result=identity_groups(t,operation,offset,p);
        else if (offset) result=-EINVAL;
        else if (nr==SYS_capget || nr==SYS_capset) result=identity_capabilities(t,operation,p);
        else if ((nr==SYS_getresuid || nr==SYS_getresgid) && operation==MD_IDENTITY_PREPARE) {
            int group=nr==SYS_getresgid;
            const struct md_identity_ids *ids=group ? &t->identity.gid : &t->identity.uid;
            p->words[0]=md_identity_visible_id(&t->identity,group,ids->real);
            p->words[1]=md_identity_visible_id(&t->identity,group,ids->effective);
            p->words[2]=md_identity_visible_id(&t->identity,group,ids->saved);
            result=0;
        } else if ((nr==SYS_getrlimit || nr==SYS_setrlimit || nr==SYS_prlimit64)
                && operation==MD_IDENTITY_COMMIT) {
            struct rlimit next=p->limit,old;
            result=process_limit_apply(t,nr==SYS_setrlimit || (nr==SYS_prlimit64 && call->args[2]) ? &next : NULL,&old);
            if (result==-EINPROGRESS) return;
            if (!result) p->limit=old;
        }
    }
    buffer_reply(t,result); adapter_progress();
}

int process_limit_call(struct thread *t,const struct seccomp_data *call,struct user_pt_regs *r) {
    if (call->instruction_pointer == RAW_GATE) return 0;
    long nr=call->nr;
    unsigned resource=call->args[nr==SYS_prlimit64 ? 1 : 0];
    if (t->phase==IDLE && t->ready
            && (nr==SYS_prlimit64 || nr==SYS_getrlimit || nr==SYS_setrlimit)
            && (resource==RLIMIT_NPROC || resource==RLIMIT_NOFILE
                || (nr==SYS_prlimit64 && call->args[0] && !call->args[2]))) {
        execution_begin(t,call,r,"process-limit-transfer"); return 1;
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
    if (md_identity_transfer_call(nr)) { execution_begin(t,call,r,"identity-transfer"); return 1; }
    switch (nr) {
    case SYS_getuid: case SYS_geteuid: case SYS_getgid: case SYS_getegid:
        value=md_identity_scalar_result(&t->identity,nr); break;
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
    if (!error) {
        struct md_buffer_span span;
        error=md_buffer_span(&t->fs.buffer,&span);
        if (!error) ((union md_identity_transfer *)span.data)->limit=value;
    }
    buffer_reply(t,error); adapter_progress();
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
