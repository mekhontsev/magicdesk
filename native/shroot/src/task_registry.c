#include "task_registry.h"
#include "task_resources.h"
#include "task_status.h"
#include "kernel_policy.h"
#include "signal_origin.h"
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum { BUCKETS = 127 };
struct task_group { struct task_group *next; pid_t pid; unsigned count; };
struct md_task_snapshot_state {
    atomic_uint refs;
    atomic_int status_fd;
    struct md_task_snapshot value;
};
struct reaped_sender {
    uid_t uid;
    unsigned count;
    int reboot_signal;
    struct md_pid_number_value *numbers;
};
struct entry {
    struct entry *next;
    struct entry *retired_next, **retired_previous;
    pid_t tid, tgid, parent;
    uint64_t generation;
    int retired, reaped;
    struct reaped_sender sender;
    struct task_group *group;
    struct md_process_member *process;
    struct md_identity value;
    struct md_process_access *access;
    struct md_guest_domain *fs;
    struct md_mount_namespace *mounts;
    struct md_fd_origins *files;
    struct md_pid_identity *pid;
    struct md_pid_identity *pid_group;
    struct md_cgroup_member *cgroup;
    struct md_cgroup_namespace *cgroup_namespace;
    struct md_network_namespace *network;
    struct md_ipc_namespace *ipc;
    struct md_uts_namespace *uts;
    struct md_landlock_policy *landlock;
    struct md_task_snapshot_state *snapshot;
};
struct md_task_registry { pthread_mutex_t lock; pid_t owner; struct entry *retired, *entries[BUCKETS];
    struct task_group *groups[BUCKETS];
    struct md_kernel_policy *kernel_policy;
    uint64_t generation;
};
/* Caller holds the registry lock or is its sole writer. */
static struct entry *find_entry(const struct md_task_registry *r,pid_t tid) {
    struct entry *e=r->entries[(unsigned)tid%BUCKETS];
    while (e && e->tid!=tid) e=e->next;
    return e;
}
static struct task_group *group_join(struct md_task_registry *r,pid_t pid) {
    unsigned bucket=(unsigned)pid%BUCKETS;
    struct task_group *g=r->groups[bucket];
    while (g && g->pid!=pid) g=g->next;
    if (!g) {
        g=calloc(1,sizeof(*g));
        if (!g) return NULL;
        g->pid=pid; g->next=r->groups[bucket]; r->groups[bucket]=g;
    }
    ++g->count; return g;
}
static void group_leave(struct md_task_registry *r,struct task_group *g) {
    if (!g || --g->count) return;
    struct task_group **slot=&r->groups[(unsigned)g->pid%BUCKETS];
    while (*slot!=g) slot=&(*slot)->next;
    *slot=g->next; free(g);
}
unsigned md_task_registry_thread_count(struct md_task_registry *r,pid_t pid) {
    if (!r || pid<=0) return 0;
    pthread_mutex_lock(&r->lock);
    struct task_group *g=r->groups[(unsigned)pid%BUCKETS];
    while (g && g->pid!=pid) g=g->next;
    unsigned count=g ? g->count : 0;
    pthread_mutex_unlock(&r->lock); return count;
}
struct md_kernel_policy *md_task_registry_kernel_policy(struct md_task_registry *r) {
    if (!r) { errno=EINVAL; return NULL; }
    pthread_mutex_lock(&r->lock);
    if (!r->kernel_policy) r->kernel_policy=md_kernel_policy_new();
    struct md_kernel_policy *policy=md_kernel_policy_retain(r->kernel_policy);
    pthread_mutex_unlock(&r->lock); return policy;
}
static void snapshot_release(struct md_task_snapshot_state *state) {
    if (!state || atomic_fetch_sub_explicit(&state->refs,1,memory_order_acq_rel)!=1) return;
    int fd=atomic_load_explicit(&state->status_fd,memory_order_relaxed);
    if (fd>=0) close(fd);
    md_task_snapshot_release(&state->value);
    free(state);
}
static int identity_equal(const struct md_identity *a,const struct md_identity *b) {
    return !memcmp(&a->uid,&b->uid,sizeof(a->uid)) && !memcmp(&a->gid,&b->gid,sizeof(a->gid))
        && !memcmp(&a->caps,&b->caps,sizeof(a->caps)) && a->no_new_privs==b->no_new_privs
        && a->groups==b->groups && a->user_namespace==b->user_namespace;
}
static void release_live(struct entry *e) {
    md_process_access_release(e->access);
    md_domain_unpin(e->fs); md_mount_namespace_release(e->mounts);
    md_fd_origins_unpin(e->files); md_pid_identity_release(e->pid_group);
    md_cgroup_namespace_release(e->cgroup_namespace);
    md_network_namespace_release(e->network); md_ipc_namespace_release(e->ipc);
    md_uts_namespace_release(e->uts);
    md_landlock_release(e->landlock);
}
static void release_entry(struct entry *e) {
    snapshot_release(e->snapshot);
    md_process_member_release(e->process);
    if (e->retired) md_pid_identity_reap(e->pid);
    release_live(e); md_identity_release(&e->value);
    md_pid_identity_release(e->pid);
    md_cgroup_member_depart(e->cgroup,e->tid); md_cgroup_member_release(e->cgroup);
    free(e->sender.numbers);
    free(e);
}
struct md_task_registry *md_task_registry_create(void) {
    struct md_task_registry *r = calloc(1, sizeof(*r));
    if (!r) return NULL;
    if (pthread_mutex_init(&r->lock, NULL)) { free(r); return NULL; }
    r->owner = getpid(); return r;
}
void md_task_registry_destroy(struct md_task_registry *r) {
    if (!r) return;
    for (unsigned i = 0; i < BUCKETS; i++) while (r->entries[i]) {
        struct entry *e = r->entries[i]; r->entries[i] = e->next;
        release_entry(e);
    }
    for (unsigned i=0;i<BUCKETS;++i) while (r->groups[i]) {
        struct task_group *g=r->groups[i]; r->groups[i]=g->next; free(g);
    }
    md_kernel_policy_release(r->kernel_policy); pthread_mutex_destroy(&r->lock); free(r);
}
int md_task_registry_publish(struct md_task_registry *r, pid_t tid, pid_t tgid,
        const struct md_identity *v, const struct md_task_resources *resources) {
    if (!r) return 0;
    if (tid <= 0 || tgid <= 0) return -EINVAL;
    struct md_process_member *process=resources ? resources->process : NULL, *old_process=NULL;
    int error=md_process_member_identity(process,v);
    if (error) return error;
    /* Publication proves this number belongs to a new live task. */
    if (r->retired) {
        const struct entry *previous=find_entry(r,tid);
        if (previous && previous->retired) md_task_registry_reap(r,tid);
    }
    struct md_guest_domain *fs=resources ? resources->fs : NULL;
    struct md_mount_namespace *mounts=resources ? resources->mounts : NULL, *old_mounts=NULL;
    struct md_guest_domain *old_fs=NULL;
    struct md_fd_origins *files=resources ? resources->files : NULL, *old_files=NULL;
    struct md_pid_identity *pid=resources ? resources->pid : NULL, *old_pid=NULL;
    struct md_pid_identity *group=resources ? resources->pid_group : NULL, *old_group=NULL;
    struct md_cgroup_member *cgroup=resources ? resources->cgroup : NULL, *old_cgroup=NULL;
    struct md_cgroup_namespace *cgroup_ns=resources ? resources->cgroup_namespace : NULL, *old_cgroup_ns=NULL;
    struct md_network_namespace *network=resources ? resources->network : NULL, *old_network=NULL;
    struct md_ipc_namespace *ipc=resources ? resources->ipc : NULL, *old_ipc=NULL;
    struct md_uts_namespace *uts=resources ? resources->uts : NULL, *old_uts=NULL;
    struct md_landlock_policy *landlock=resources ? resources->landlock : NULL, *old_landlock=NULL;
    struct md_process_access *access=resources ? resources->access : NULL, *old_access=NULL;
    unsigned bucket = (unsigned)tid % BUCKETS;
    pthread_mutex_lock(&r->lock);
    struct entry *e = r->entries[bucket];
    while (e && e->tid != tid) e = e->next;
    struct task_group *members=e && e->group && e->group->pid==tgid ? e->group : group_join(r,tgid);
    if (!members) { pthread_mutex_unlock(&r->lock); return -ENOMEM; }
    if (!e) {
        if (r->generation==UINT64_MAX) { group_leave(r,members); pthread_mutex_unlock(&r->lock); return -EOVERFLOW; }
        e = calloc(1, sizeof(*e));
        if (!e) { group_leave(r,members); pthread_mutex_unlock(&r->lock); return -ENOMEM; }
        e->generation=++r->generation;
        e->tid = tid; e->next = r->entries[bucket]; r->entries[bucket] = e;
    }
    if (e->group!=members) { group_leave(r,e->group); e->group=members; }
    int same_identity=e->tgid==tgid && identity_equal(&e->value,v);
    /* Registry publication owns the native membership identity. Moving a task
     * between cgroups changes the member itself, not this binding. Ordinary
     * resumes need neither the hierarchy lock nor another membership commit. */
    int publish_cgroup=e->tgid!=tgid || e->cgroup!=cgroup || e->pid!=pid || e->pid_group!=group;
    struct md_task_snapshot_state *old_snapshot=NULL;
    if (!same_identity || e->fs!=fs || e->mounts!=mounts || e->files!=files || e->pid!=pid
            || e->pid_group!=group || e->cgroup!=cgroup || e->cgroup_namespace!=cgroup_ns
            || e->network!=network || e->ipc!=ipc || e->uts!=uts || e->landlock!=landlock || e->access!=access) {
        old_snapshot=e->snapshot; e->snapshot=NULL;
    }
    if (e->fs != fs) { md_domain_pin(fs); old_fs=e->fs; e->fs=fs; }
    if (e->process!=process) { old_process=e->process; e->process=md_process_member_retain(process); }
    if (e->mounts != mounts) {
        old_mounts=e->mounts; e->mounts=md_mount_namespace_retain(mounts);
    }
    if (e->files!=files) { old_files=e->files; e->files=md_fd_origins_pin(files); }
    if (e->pid!=pid) { old_pid=e->pid; e->pid=md_pid_identity_retain(pid); }
    if (e->pid_group!=group) { old_group=e->pid_group; e->pid_group=md_pid_identity_retain(group); }
    if (e->cgroup!=cgroup) { old_cgroup=e->cgroup; e->cgroup=md_cgroup_member_retain(cgroup); }
    if (e->cgroup_namespace!=cgroup_ns) {
        old_cgroup_ns=e->cgroup_namespace; e->cgroup_namespace=md_cgroup_namespace_retain(cgroup_ns);
    }
    if (publish_cgroup) md_cgroup_member_publish(cgroup,tid,tgid,pid,group);
    if (e->network!=network) {
        old_network=e->network; e->network=md_network_namespace_retain(network);
    }
    if (e->ipc!=ipc) { old_ipc=e->ipc; e->ipc=md_ipc_namespace_retain(ipc); }
    if (e->uts!=uts) { old_uts=e->uts; e->uts=md_uts_namespace_retain(uts); }
    if (e->landlock!=landlock) { old_landlock=e->landlock; e->landlock=md_landlock_retain(landlock); }
    /* Successful credential publication is the common commit point for scalar,
     * copied and namespace operations. A new exec mm has its own initial state. */
    if (access && e->access==access && e->tgid && !same_identity)
        md_process_access_credentials(access,&e->value,v);
    if (e->access!=access) { old_access=e->access; e->access=md_process_access_retain(access); }
    if (same_identity) goto published;
    struct md_identity next = md_identity_copy(v);
    md_identity_release(&e->value); e->value = next; e->tgid = tgid;
published:
    pthread_mutex_unlock(&r->lock);
    snapshot_release(old_snapshot);
    md_process_member_release(old_process);
    md_domain_unpin(old_fs); md_mount_namespace_release(old_mounts);
    md_fd_origins_unpin(old_files);
    md_pid_identity_release(old_pid);
    md_pid_identity_release(old_group);
    md_cgroup_member_depart(old_cgroup,tid); md_cgroup_member_release(old_cgroup);
    md_cgroup_namespace_release(old_cgroup_ns);
    md_network_namespace_release(old_network);
    md_ipc_namespace_release(old_ipc);
    md_uts_namespace_release(old_uts);
    md_landlock_release(old_landlock);
    md_process_access_release(old_access);
    return 0;
}
void md_task_registry_forget(struct md_task_registry *r, pid_t tid) {
    if (!r || tid <= 0) return;
    pthread_mutex_lock(&r->lock);
    struct entry **slot = &r->entries[(unsigned)tid % BUCKETS];
    while (*slot && (*slot)->tid != tid) slot = &(*slot)->next;
    struct entry *e = *slot;
    if (e && e->retired) e=NULL;
    if (e) { *slot = e->next; group_leave(r,e->group); e->group=NULL; }
    pthread_mutex_unlock(&r->lock);
    if (e) { md_cgroup_member_reaped(e->cgroup); release_entry(e); }
}
void md_task_registry_retire(struct md_task_registry *r,pid_t tid,pid_t parent,const struct md_identity *identity) {
    if (!r || tid<=0) return;
    pthread_mutex_lock(&r->lock);
    struct entry *e=r->entries[(unsigned)tid%BUCKETS];
    while (e && e->tid!=tid) e=e->next;
    struct entry live={0};
    if (e && !e->retired) {
        live=*e;
        group_leave(r,e->group); e->group=NULL;
        e->value=md_identity_copy(identity);
        e->parent=parent;
        e->retired=1; e->retired_next=r->retired; e->retired_previous=&r->retired;
        if (r->retired) r->retired->retired_previous=&e->retired_next;
        r->retired=e;
        e->fs=NULL; e->mounts=NULL; e->files=NULL; e->pid_group=NULL;
        e->cgroup_namespace=NULL; e->network=NULL; e->ipc=NULL; e->uts=NULL; e->landlock=NULL; e->access=NULL;
        e->snapshot=NULL;
    }
    pthread_mutex_unlock(&r->lock);
    if (live.tid) {
        md_cgroup_member_depart(live.cgroup,tid);
        md_identity_release(&live.value); release_live(&live);
        snapshot_release(live.snapshot);
    }
}
struct md_task_retired_view md_task_registry_retired(const struct md_task_registry *r,pid_t tid) {
    if (!r || tid<=0) return (struct md_task_retired_view){0};
    const struct entry *e=find_entry(r,tid);
    return e && e->retired ? (struct md_task_retired_view){e->reaped ? NULL : &e->value,e->pid,e->cgroup,e->process,e->parent}
        : (struct md_task_retired_view){0};
}
int md_task_registry_has_retired(const struct md_task_registry *r) { return r && r->retired!=NULL; }
void md_task_registry_retired_processes(const struct md_task_registry *r,
        void (*visit)(void *,pid_t,struct md_task_retired_view),void *context) {
    if (!r) return;
    for (const struct entry *e=r->retired;e;e=e->retired_next) if (e->tid==e->tgid && !e->reaped)
        visit(context,e->tid,(struct md_task_retired_view){&e->value,e->pid,e->cgroup,e->process,e->parent});
}
void md_task_registry_reap(struct md_task_registry *r,pid_t tid) {
    if (!r || tid<=0) return;
    pthread_mutex_lock(&r->lock);
    struct entry **slot=&r->entries[(unsigned)tid%BUCKETS];
    while (*slot && (*slot)->tid!=tid) slot=&(*slot)->next;
    struct entry *e=*slot;
    if (e && !e->retired) e=NULL;
    if (e) {
        *slot=e->next; *e->retired_previous=e->retired_next;
        if (e->retired_next) e->retired_next->retired_previous=e->retired_previous;
    }
    pthread_mutex_unlock(&r->lock);
    if (e) { md_cgroup_member_reaped(e->cgroup); release_entry(e); }
}
void md_task_registry_reap_deferred(struct md_task_registry *r,pid_t tid) {
    if (!r || tid<=0) return;
    pthread_mutex_lock(&r->lock);
    struct entry *e=find_entry(r,tid);
    struct entry released={0};
    if (e && e->retired && !e->reaped) {
        unsigned count=md_pid_namespace_depth(md_pid_identity_namespace(e->pid));
        struct md_pid_number_value *numbers=count ? malloc(count*sizeof(*numbers)) : NULL;
        if (!count || numbers) {
            released=*e;
            e->sender=(struct reaped_sender){.uid=e->value.uid.real,.numbers=numbers,
                .reboot_signal=md_pid_identity_reboot_signal(e->pid),
                .count=md_pid_identity_export(e->pid,numbers)};
            e->reaped=1; e->value=(struct md_identity){0};
            e->pid=NULL; e->cgroup=NULL; e->process=NULL;
        }
    }
    pthread_mutex_unlock(&r->lock);
    if (released.tid) {
        md_identity_release(&released.value);
        md_pid_identity_reap(released.pid); md_pid_identity_release(released.pid);
        md_cgroup_member_reaped(released.cgroup); md_cgroup_member_release(released.cgroup);
        md_process_member_release(released.process);
    }
}
int md_task_registry_signal_origin(const struct md_task_registry *r,const siginfo_t *info,
        struct md_signal_origin *out) {
    if (!r || !md_signal_child_notice(info) || info->si_pid<=0) return 0;
    const struct entry *e=find_entry(r,info->si_pid);
    if (!e) return 0;
    *out=(struct md_signal_origin){.info=*info,.valid=1};
    if (e->reaped) {
        out->uid=e->sender.uid; out->count=e->sender.count;
        out->reboot_signal=e->sender.reboot_signal;
        if (out->count) memcpy(out->numbers,e->sender.numbers,out->count*sizeof(*out->numbers));
    } else {
        out->uid=e->value.uid.real; out->count=md_pid_identity_export(e->pid,out->numbers);
        out->reboot_signal=md_pid_identity_reboot_signal(e->pid);
    }
    return 1;
}
void md_task_registry_collect(struct md_task_registry *r,int (*gone)(pid_t)) {
    if (!md_task_registry_has_retired(r)) return;
    struct entry *e=r->retired;
    while (e) {
        struct entry *next=e->retired_next;
        if (!md_pid_identity_live(e->pid) && gone(e->tid)) md_task_registry_reap(r,e->tid);
        e=next;
    }
}
void md_task_registry_collect_limits(struct md_task_registry *r,int (*gone)(pid_t)) {
    for (struct entry *e=r->retired;e;e=e->retired_next) if (e->process && gone(e->tid)) {
        md_cgroup_member_reaped(e->cgroup);
        md_process_member_release(e->process); e->process=NULL;
    }
}
static int admitted_entry(struct md_task_registry *r,pid_t tid,pid_t peer,struct entry **out) {
    struct entry *e=r->entries[(unsigned)tid%BUCKETS];
    while (e && e->tid!=tid) e=e->next;
    *out=e;
    return !e ? -ESRCH : e->retired ? -ESTALE : peer && peer!=r->owner && peer!=e->tgid ? -EPERM : 0;
}
int md_task_registry_files(struct md_task_registry *r,pid_t tid,pid_t peer,struct md_fd_origins **out) {
    *out=NULL;
    if (!r || tid<=0) return -ESRCH;
    pthread_mutex_lock(&r->lock);
    struct entry *e;
    int error=admitted_entry(r,tid,peer,&e);
    if (!error) *out=md_fd_origins_pin(e->files);
    pthread_mutex_unlock(&r->lock);
    return error==-ESTALE ? -ESRCH : error;
}
int md_task_registry_probe(struct md_task_registry *r, pid_t tid, pid_t peer,
        struct md_task_snapshot *out) {
    *out=(struct md_task_snapshot){0};
    if (!r || tid <= 0) return -ESRCH;
    pthread_mutex_lock(&r->lock);
    struct entry *e;
    int error=admitted_entry(r,tid,peer,&e);
    if (!error && !e->snapshot) {
        struct md_task_snapshot_state *state=calloc(1,sizeof(*state));
        if (!state) error=-ENOMEM;
        else {
            atomic_init(&state->refs,1);
            atomic_init(&state->status_fd,-1);
            struct md_task_snapshot *value=&state->value;
            value->tid=e->tid; value->tgid=e->tgid;
            value->generation=e->generation;
            value->identity=md_identity_copy(&e->value);
            value->access=md_process_access_retain(e->access);
            value->fs=e->fs; md_domain_pin(value->fs);
            value->mounts=md_mount_namespace_retain(e->mounts);
            value->files=md_fd_origins_pin(e->files);
            value->pid=md_pid_identity_retain(e->pid);
            value->pid_group=md_pid_identity_retain(e->pid_group);
            value->cgroup=md_cgroup_member_retain(e->cgroup);
            value->cgroup_namespace=md_cgroup_namespace_retain(e->cgroup_namespace);
            value->network=md_network_namespace_retain(e->network);
            value->ipc=md_ipc_namespace_retain(e->ipc);
            value->uts=md_uts_namespace_retain(e->uts);
            value->landlock=md_landlock_retain(e->landlock);
            e->snapshot=state;
        }
    }
    if (!error) {
        *out=e->snapshot->value;
        out->owner=e->snapshot;
        atomic_fetch_add_explicit(&out->owner->refs,1,memory_order_relaxed);
        md_domain_paths(out->fs,&out->root,&out->cwd);
    }
    pthread_mutex_unlock(&r->lock); return error;
}
int md_task_registry_read(struct md_task_registry *r,pid_t tid,pid_t peer,struct md_task_snapshot *out) {
    int error=md_task_registry_probe(r,tid,peer,out);
    return error==-ESTALE ? -ESRCH : error;
}
int md_task_registry_credentials(struct md_task_registry *r,pid_t tid,struct md_task_credentials *out) {
    *out=(struct md_task_credentials){0};
    if (!r || tid<=0) return -ESRCH;
    pthread_mutex_lock(&r->lock);
    struct entry *e=r->entries[(unsigned)tid%BUCKETS];
    while (e && e->tid!=tid) e=e->next;
    if (e && e->reaped) e=NULL;
    int found=e!=NULL;
    if (e) *out=(struct md_task_credentials){.group=e->tgid,.retired=e->retired,
        .access=md_process_access_retain(e->access),.generation=e->generation,.identity=md_identity_copy(&e->value)};
    pthread_mutex_unlock(&r->lock);
    return found ? 0 : -ESRCH;
}
void md_task_credentials_release(struct md_task_credentials *value) {
    md_process_access_release(value->access); value->access=NULL;
    md_identity_release(&value->identity);
}
int md_task_registry_proc(struct md_task_registry *r,pid_t tid,struct md_task_snapshot *out) {
    *out=(struct md_task_snapshot){0};
    if (!r || tid<=0) return -ESRCH;
    pthread_mutex_lock(&r->lock);
    struct entry *e=r->entries[(unsigned)tid%BUCKETS];
    while (e && e->tid!=tid) e=e->next;
    if (e && e->reaped) e=NULL;
    int found=e!=NULL;
    if (e) *out=(struct md_task_snapshot){.tid=tid,.tgid=e->tgid,.generation=e->generation,.identity=md_identity_copy(&e->value),
        .access=md_process_access_retain(e->access),
        .pid=md_pid_identity_retain(e->pid),.cgroup=md_cgroup_member_retain(e->cgroup)};
    pthread_mutex_unlock(&r->lock);
    return found ? 0 : -ESRCH;
}
int md_task_snapshot_status_install(const struct md_task_snapshot *snapshot,int descriptor) {
    if (descriptor<0) return -EBADF;
    if (!snapshot->owner) { close(descriptor); return -EINVAL; }
    int expected=-1;
    if (!atomic_compare_exchange_strong_explicit(&snapshot->owner->status_fd,&expected,descriptor,
            memory_order_release,memory_order_acquire)) close(descriptor);
    return 0;
}
long md_task_snapshot_umask(const struct md_task_snapshot *snapshot) {
    if (!snapshot->owner) return md_task_umask(snapshot->tid);
    atomic_int *slot=&snapshot->owner->status_fd;
    int fd=atomic_load_explicit(slot,memory_order_acquire);
    if (fd<0) {
        long opened=md_task_status_open(snapshot->tid);
        if (opened<0) return opened;
        if (atomic_compare_exchange_strong_explicit(slot,&fd,(int)opened,
                memory_order_release,memory_order_acquire)) fd=(int)opened;
        else close((int)opened);
    }
    return md_task_umask_fd(fd);
}
void md_task_snapshot_release(struct md_task_snapshot *snapshot) {
    if (snapshot->owner) {
        md_fs_anchor_release(snapshot->cwd);
        snapshot_release(snapshot->owner);
        *snapshot=(struct md_task_snapshot){0};
        return;
    }
    md_identity_release(&snapshot->identity);
    md_process_access_release(snapshot->access);
    md_domain_unpin(snapshot->fs);
    md_mount_namespace_release(snapshot->mounts);
    md_fd_origins_unpin(snapshot->files);
    md_fs_anchor_release(snapshot->cwd);
    md_pid_identity_release(snapshot->pid);
    md_pid_identity_release(snapshot->pid_group);
    md_cgroup_member_release(snapshot->cgroup);
    md_cgroup_namespace_release(snapshot->cgroup_namespace);
    md_network_namespace_release(snapshot->network);
    md_ipc_namespace_release(snapshot->ipc);
    md_uts_namespace_release(snapshot->uts);
    md_landlock_release(snapshot->landlock);
    *snapshot=(struct md_task_snapshot){0};
}
