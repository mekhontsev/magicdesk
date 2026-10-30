#define _GNU_SOURCE
#include "task_resources.h"
#include "proc_namespace.h"
#include <errno.h>
#include <sched.h>
#include <linux/sched.h>

int md_resources_init(struct md_task_resources *r, struct md_mount_namespace *mounts) {
    *r = (struct md_task_resources){.fs=md_domain_new(), .sem_undo=md_ipc_undo_new(),
        .mounts=md_mount_namespace_retain(mounts), .files=md_fd_origins_new(), .uts=md_uts_namespace_new(),
        .ipc=md_ipc_namespace_new(NULL,0), .network=md_network_namespace_new(NULL,0),
        .cgroup=md_cgroup_member_new()};
    if (r->cgroup) r->cgroup_namespace=md_cgroup_namespace_new(r->cgroup,NULL);
    if (r->fs && r->sem_undo && r->files && r->uts && r->ipc && r->network && r->cgroup_namespace) { md_domain_bind_mounts(r->fs,mounts); return 0; }
    md_resources_release(r); return -ENOMEM;
}
int md_resources_release(struct md_task_resources *r) {
    md_process_member_release(r->process);
    md_domain_release(r->fs);
    md_mount_namespace_release(r->mounts);
    md_fd_origins_release(r->files);
    md_uts_namespace_release(r->uts);
    md_pid_identity_release(r->pid);
    md_pid_identity_release(r->pid_group);
    md_pid_namespace_release(r->pid_children);
    md_network_namespace_release(r->network);
    md_cgroup_member_release(r->cgroup);
    md_cgroup_namespace_release(r->cgroup_namespace);
    md_landlock_release(r->landlock);
    int error = md_ipc_undo_release(md_ipc_namespace_active(r->ipc), r->sem_undo);
    md_ipc_namespace_release(r->ipc);
    *r = (struct md_task_resources){0};
    return error;
}
int md_resources_fork(const struct md_task_resources *r, unsigned long flags,
        struct md_task_resources *out) {
    *out = (struct md_task_resources){.fs=md_domain_fork(r->fs, !!(flags & CLONE_FS))};
    if (!out->fs) return -ENOMEM;
    if (r->files) {
        out->files=md_fd_origins_fork(r->files,!!(flags & CLONE_FILES));
        if (!out->files) { md_resources_release(out); return -ENOMEM; }
    }
    if (r->cgroup && !(out->cgroup=md_cgroup_member_fork(r->cgroup))) {
        md_resources_release(out); return -ENOMEM;
    }
    out->sem_undo = md_ipc_undo_fork(r->sem_undo, !!(flags & CLONE_SYSVSEM));
    if (out->sem_undo) {
        out->mounts=md_mount_namespace_retain(r->mounts);
        out->uts=md_uts_namespace_retain(r->uts);
        out->ipc=md_ipc_namespace_retain(r->ipc);
        out->pid=md_pid_identity_retain(r->pid);
        out->pid_group=md_pid_identity_retain(r->pid_group);
        out->pid_children=md_pid_namespace_retain(r->pid_children);
        out->network=md_network_namespace_retain(r->network);
        out->cgroup_namespace=md_cgroup_namespace_retain(r->cgroup_namespace);
        out->landlock=md_landlock_retain(r->landlock);
        return 0;
    }
    md_resources_release(out); return -ENOMEM;
}
int md_resources_exec_closed(struct md_task_resources *r,struct md_fd_closed *closed) {
    if (!r->files) return 0;
    struct md_fd_origins *next=md_fd_origins_unshare_prepare(r->files);
    if (!next) return -ENOMEM;
    md_fd_origins_exec_collect(next,closed);
    struct md_fd_origins *old=r->files; r->files=next; md_fd_origins_release(old);
    return 0;
}
int md_resources_exec(struct md_task_resources *r) { return md_resources_exec_closed(r,NULL); }
void md_resources_exit_files(struct md_task_resources *r,struct md_fd_closed *closed) {
    md_fd_origins_exit_collect(r->files,closed);
    md_fd_origins_release(r->files); r->files=NULL;
}
int md_resources_clone_supported(unsigned long flags) {
    return (!(flags&CLONE_NEWUSER) || !(flags&(CLONE_FS|CLONE_THREAD)))
        && (!(flags&CLONE_NEWPID) || !(flags&CLONE_THREAD))
        && (flags & (CLONE_NEWNS | CLONE_FS)) != (CLONE_NEWNS | CLONE_FS)
        && (flags & (CLONE_NEWIPC | CLONE_SYSVSEM)) != (CLONE_NEWIPC | CLONE_SYSVSEM);
}
unsigned long md_resources_namespace_flags(unsigned long flags) {
    return flags&(CLONE_NEWNS|CLONE_NEWUTS|CLONE_NEWIPC|CLONE_NEWUSER|CLONE_NEWPID|CLONE_NEWNET|CLONE_NEWCGROUP);
}
int md_resources_unshare_prepare(const struct md_task_resources *r, unsigned long flags,
        const struct md_identity *identity, struct md_resource_change *change) {
    /* VM/SIGHAND/THREAD checks remain kernel-owned, including the requirement
     * that the caller is alone. Namespace flags are not successful no-ops. */
    unsigned long supported = CLONE_FS | CLONE_FILES | CLONE_SYSVSEM
        | CLONE_VM | CLONE_SIGHAND | CLONE_THREAD | md_resources_namespace_flags(~0UL);
    *change = (struct md_resource_change){0};
    unsigned long namespaces = CLONE_NEWTIME;
    if (flags & ~(supported | namespaces)) return -EINVAL;
    if (flags & namespaces) return -ENOTSUP;
    if ((flags & CLONE_NEWNS) && !r->mounts) return -ENOTSUP;
    unsigned long creating=md_resources_namespace_flags(flags);
    if (creating && !identity) return -EINVAL;
    if (creating && !(flags&CLONE_NEWUSER) && !md_identity_capable(identity,CAP_SYS_ADMIN)) return -EPERM;
    struct md_user_namespace *owner=identity ? identity->user_namespace : NULL;
    if (flags&CLONE_NEWUSER) {
        if (md_domain_restricted(r->fs) || (!r->mounts && md_domain_scoped(r->fs))) return -EPERM;
        change->identity=md_identity_copy(identity); change->identity_pending=1;
        int error=md_identity_enter_user_namespace(&change->identity);
        if (error) { md_resources_unshare_finish(NULL,NULL,change,error); return error; }
        owner=change->identity.user_namespace;
        change->check_root=r->mounts!=NULL;
        flags|=CLONE_FS;
    }
    struct md_task_resources next = {0};
    int error=-ENOMEM;
    if (flags&CLONE_NEWCGROUP) {
        next.cgroup_namespace=md_cgroup_namespace_new(r->cgroup,owner);
        if (!next.cgroup_namespace) { error=-errno; goto failed; }
    }
    if (flags&CLONE_NEWNET) {
        next.network=md_network_namespace_copy(r->network,owner);
        if (!next.network) { error=-errno; goto failed; }
    }
    if (flags&CLONE_NEWPID) {
        if (r->pid_children!=md_pid_identity_namespace(r->pid)) { error=-EINVAL; goto failed; }
        next.pid_children=md_pid_namespace_new(r->pid_children,owner);
        if (!next.pid_children) { error=-errno; goto failed; }
        change->pid_children_pending=1;
    }
    if ((flags & CLONE_FS) && !(flags & CLONE_NEWNS) && !change->check_root) {
        next.fs = md_domain_unshare_prepare(r->fs);
        if (!next.fs) goto failed;
    }
    if (flags & (CLONE_SYSVSEM|CLONE_NEWIPC)) {
        next.sem_undo = md_ipc_undo_new();
        if (!next.sem_undo) goto failed;
    }
    if ((flags & CLONE_FILES) && r->files) {
        next.files=md_fd_origins_unshare_prepare(r->files);
        if (!next.files) goto failed;
    }
    if (flags & CLONE_NEWUTS) {
        next.uts=md_uts_namespace_copy(r->uts,owner);
        if (!next.uts) goto failed;
    }
    if (flags & CLONE_NEWIPC) {
        next.ipc=md_ipc_namespace_copy(r->ipc,owner);
        if (!next.ipc) goto failed;
    }
    change->next=next; change->copy_mounts=!!(flags&CLONE_NEWNS); change->namespaces=creating;
    return 0;
failed:
    md_resources_release(&next);
    md_resources_unshare_finish(NULL,NULL,change,error);
    return error;
}
int md_resources_clone_cgroup_prepare(const struct md_task_resources *r,unsigned long flags,struct md_fs_anchor *origin,
        const struct md_identity *id,struct md_resource_change *change) {
    int error=origin ? md_cgroup_member_clone(r->cgroup,origin,id,r->cgroup_namespace,
        !!(flags&CLONE_THREAD),&change->next.cgroup) : -EINVAL;
    if (!error && (flags&CLONE_NEWCGROUP)) {
        struct md_user_namespace *owner=change->identity_pending
            ? change->identity.user_namespace : id->user_namespace;
        struct md_cgroup_namespace *ns=md_cgroup_namespace_new(change->next.cgroup,owner);
        if (!ns) error=-errno;
        else {
            md_cgroup_namespace_release(change->next.cgroup_namespace);
            change->next.cgroup_namespace=ns;
        }
    }
    return error;
}
int md_resources_unshare_finish(struct md_task_resources *r,
        struct md_identity *identity, struct md_resource_change *change, long result) {
    int invalid=!result && (!r || (change->identity_pending && !identity)
        || (change->copy_mounts && (!change->next.mounts || !change->next.fs))
        || (change->join_mounts && (!change->next.mounts || !change->next.fs))
        || (change->check_root && !change->next.fs));
    if (!result && !invalid) {
        if (change->next.cgroup) {
            struct md_cgroup_member *old=r->cgroup;
            r->cgroup=change->next.cgroup; change->next.cgroup=old;
        }
        if (change->next.cgroup_namespace) {
            struct md_cgroup_namespace *old=r->cgroup_namespace;
            r->cgroup_namespace=change->next.cgroup_namespace; change->next.cgroup_namespace=old;
        }
        if (change->next.network) {
            struct md_network_namespace *old=r->network;
            r->network=change->next.network; change->next.network=old;
        }
        if (change->pid_children_pending) {
            struct md_pid_namespace *old=r->pid_children;
            r->pid_children=change->next.pid_children; change->next.pid_children=old;
        }
        if (change->identity_pending) {
            struct md_identity old=*identity; *identity=change->identity; change->identity=old;
        }
        if (change->next.uts) {
            struct md_uts_namespace *old=r->uts;
            r->uts=change->next.uts; change->next.uts=old;
        }
        if (change->next.mounts) {
            struct md_mount_namespace *old=r->mounts;
            r->mounts=change->next.mounts; change->next.mounts=old;
        }
        if (change->next.fs) {
            struct md_guest_domain *old = r->fs;
            r->fs = change->next.fs; change->next.fs = old;
        }
        if (change->next.sem_undo) {
            struct md_ipc_undo *old = r->sem_undo;
            r->sem_undo = change->next.sem_undo; change->next.sem_undo = old;
        }
        if (change->next.files) {
            struct md_fd_origins *old=r->files;
            r->files=change->next.files; change->next.files=old;
        }
        if (change->next.ipc) {
            struct md_ipc_namespace *old=r->ipc;
            r->ipc=change->next.ipc; change->next.ipc=old;
        } else if (change->next.sem_undo) {
            change->next.ipc=md_ipc_namespace_retain(r->ipc);
        }
    }
    int error = md_resources_release(&change->next);
    if (change->identity_pending) md_identity_release(&change->identity);
    md_mount_namespace_release(change->join_mounts);
    *change = (struct md_resource_change){0};
    return invalid ? -EINVAL : error;
}
static int setns_add(const struct md_task_resources *r, const struct md_identity *id,
        const struct md_namespace_reference *target, struct md_resource_change *change) {
    if (target->kind==MD_NAMESPACE_USER) {
        if (md_domain_shared(r->fs)) return -EINVAL;
        change->identity=md_identity_copy(id); change->identity_pending=1;
        return md_identity_join_user_namespace(&change->identity,target->value.user);
    }
    const struct md_user_namespace *owner=md_namespace_reference_owner(target);
    if (!md_identity_capable(id,CAP_SYS_ADMIN) || !md_identity_capable_in(id,owner,CAP_SYS_ADMIN)) return -EPERM;
    switch (target->kind) {
    case MD_NAMESPACE_CGROUP:
        change->next.cgroup_namespace=md_cgroup_namespace_retain(target->value.cgroup); break;
    case MD_NAMESPACE_NETWORK:
        change->next.network=md_network_namespace_retain(target->value.network); break;
    case MD_NAMESPACE_MOUNT:
        if (!md_identity_capable(id,CAP_SYS_CHROOT)) return -EPERM;
        if (md_domain_shared(r->fs)) return -EINVAL;
        change->join_mounts=md_mount_namespace_retain(target->value.mount); break;
    case MD_NAMESPACE_UTS:
        change->next.uts=md_uts_namespace_retain(target->value.uts); break;
    case MD_NAMESPACE_IPC:
        change->next.sem_undo=md_ipc_undo_new();
        if (!change->next.sem_undo) return -ENOMEM;
        change->next.ipc=md_ipc_namespace_retain(target->value.ipc); break;
    case MD_NAMESPACE_PID: case MD_NAMESPACE_PID_CHILDREN:
        if (!md_pid_namespace_contains(md_pid_identity_namespace(r->pid),target->value.pid)) return -EINVAL;
        change->next.pid_children=md_pid_namespace_retain(target->value.pid);
        change->pid_children_pending=1; break;
    default: return -EINVAL;
    }
    return 0;
}
int md_resources_setns_prepare(const struct md_task_resources *r, const struct md_identity *id,
        const struct md_namespace_reference *target, unsigned type, struct md_resource_change *change) {
    *change=(struct md_resource_change){0};
    if (!target || !id || (type && type!=md_namespace_reference_type(target))) return -EINVAL;
    int error=setns_add(r,id,target,change);
    if (error) md_resources_unshare_finish(NULL,NULL,change,error);
    return error;
}
int md_resources_setns_process_prepare(const struct md_task_resources *r, const struct md_identity *id,
        const struct md_task_resources *target, struct md_user_namespace *user,
        unsigned types, struct md_resource_change *change) {
    *change=(struct md_resource_change){0};
    if (!types || (types&~(md_resources_namespace_flags(~0UL)|CLONE_NEWTIME))) return -EINVAL;
    if (types&CLONE_NEWTIME) return -EOPNOTSUPP;
    /* Retain one candidate before publication. In particular, later admission
     * uses the selected user namespace without changing the caller on failure. */
    static const enum md_namespace_kind kinds[]={MD_NAMESPACE_USER,MD_NAMESPACE_MOUNT,
        MD_NAMESPACE_UTS,MD_NAMESPACE_IPC,MD_NAMESPACE_PID,MD_NAMESPACE_CGROUP,MD_NAMESPACE_NETWORK};
    for (unsigned i=0;i<sizeof(kinds)/sizeof(*kinds);++i) {
        struct md_namespace_reference ref={.kind=kinds[i]};
        if (!(types&md_namespace_reference_type(&ref))) continue;
        ref=md_namespace_reference_capture(kinds[i],target,user);
        int error=setns_add(r,change->identity_pending ? &change->identity : id,&ref,change);
        md_namespace_reference_release(&ref);
        if (error) { md_resources_unshare_finish(NULL,NULL,change,error); return error; }
    }
    return 0;
}
