#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "mount_graph.h"
#include "fs_wire.h"
#include "mount_topology.h"
#include "inode_lifetime.h"
#include "user_namespace.h"
#include "namespace_identity.h"
#include "native_mounts.h"
#include "inode_mount_boundary.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* IDs belong to mount instances, not array positions or native inode numbers.
 * Never recycle an ID while retained locations may still name an older view. */
static atomic_uint next_mount_id = 1;
struct md_view_mount *md_view_mount_array_new(void) {
    void *p=mmap(NULL,MD_FS_MOUNTS_MAX*sizeof(struct md_view_mount),PROT_READ|PROT_WRITE,
        MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    return p==MAP_FAILED ? NULL : p;
}
void md_view_mount_array_free(struct md_view_mount *entries) {
    if (entries) munmap(entries,MD_FS_MOUNTS_MAX*sizeof(*entries));
}
struct md_fs_mounts *md_view_tree_new(void) {
    struct md_fs_mounts *tree=calloc(1,sizeof(*tree));
    if (!tree) return NULL;
    tree->mounts=md_view_mount_array_new();
    if (!tree->mounts) { int error=errno; free(tree); errno=error; return NULL; }
    return tree;
}
void md_view_tree_free(struct md_fs_mounts *tree) {
    if (!tree) return;
    md_view_mount_array_free(tree->mounts); free(tree);
}
void md_view_mount_lookup_changed(struct md_mount_namespace *view) {
    if (view && view->tree) view->tree->lookup.valid=0;
}
static unsigned placement_hash(const struct md_view_mount *parent,const struct stat *st) {
    uint64_t key=(uint64_t)st->st_ino ^ ((uint64_t)st->st_dev*UINT64_C(0x9e3779b97f4a7c15))
        ^ (uint64_t)(uintptr_t)parent;
    key^=key>>33; key*=UINT64_C(0xff51afd7ed558ccd); key^=key>>33;
    return (unsigned)key&1023;
}
static void placement_index(struct md_mount_namespace *view) {
    struct md_fs_mounts *tree=view->tree;
    if (tree->lookup.valid && tree->lookup.count==tree->count) return;
    memset(tree->lookup.buckets,0,sizeof(tree->lookup.buckets));
    memset(tree->lookup.children,0,(tree->count+1)*sizeof(*tree->lookup.children));
    /* Reverse insertion retains ascending slot precedence on collisions.
     * Topology publication/retirement invalidates this one shared index. */
    for (unsigned end=tree->count+1;end;--end) {
        unsigned i=end-1;
        struct md_view_mount *m=md_view_mount_node(view,i);
        if (m->source<0 || m->detached) continue;
        unsigned hash=placement_hash(m->parent,&m->guest);
        tree->lookup.next[i]=tree->lookup.buckets[hash];
        tree->lookup.buckets[hash]=(uint16_t)(i+1);
        int parent=md_view_mount_slot(view,m->parent,tree->count);
        if (parent>=0) tree->lookup.children[parent]|=md_inode_mount_bit(m->guest.st_dev,m->guest.st_ino);
    }
    tree->lookup.count=tree->count; tree->lookup.valid=1;
}
uint64_t md_view_mount_children(struct md_mount_namespace *view,const struct md_view_mount *parent) {
    if (!view || !view->tree) return 0;
    int slot=md_view_mount_slot(view,parent,view->tree->count);
    if (slot<0) return UINT64_MAX;
    placement_index(view);
    return view->tree->lookup.children[slot];
}
struct md_view_mount *md_view_mount_lookup(struct md_mount_namespace *view,
        const struct md_view_mount *parent,const struct stat *st) {
    struct md_fs_mounts *tree=view ? view->tree : NULL;
    if (!tree) return NULL;
    if (tree->count<=8) {
        for (unsigned i=0;i<=tree->count;++i) {
            struct md_view_mount *m=md_view_mount_node(view,i);
            if (m->source>=0 && !m->detached && m->parent==parent && md_view_same(st,&m->guest)) return m;
        }
        return NULL;
    }
    placement_index(view);
    for (unsigned link=tree->lookup.buckets[placement_hash(parent,st)];link;link=tree->lookup.next[link-1]) {
        struct md_view_mount *m=md_view_mount_node(view,link-1);
        if (m->parent==parent && md_view_same(st,&m->guest)) return m;
    }
    return NULL;
}
int md_view_mount_vacant(struct md_view_mount *entries,unsigned *count) {
    for (unsigned i=0;i<*count;++i)
        if (!entries[i].id && entries[i].generation<UINT64_MAX) return (int)i;
    if (*count>=MD_FS_MOUNTS_MAX) return -ENOSPC;
    unsigned i=(*count)++;
    entries[i]=(struct md_view_mount){.source=-1,.target=-1}; return (int)i;
}
int md_mount_topology_order(unsigned count, const unsigned *parents, unsigned *order) {
    if (count>MD_FS_MOUNTS_MAX+1 || (count && (!parents || !order))) return -EINVAL;
    unsigned pass[count+1], chain[count+1], offsets[count+1];
    for (unsigned i=0;i<count;++i) pass[i]=UINT_MAX;
    memset(offsets,0,sizeof(offsets));
    for (unsigned i=0;i<count;++i) if (parents[i]>count || parents[i]==i+1) return -EINVAL;
    /* Preserve ascending scan-pass order without rescanning an entire tree
     * for each reversed edge. A child preceding its parent needs one more
     * pass; the forest is visited once, then stably bucketed by pass number. */
    for (unsigned i=0;i<count;++i) if (pass[i]==UINT_MAX) {
        unsigned next=i, depth=0;
        while (pass[next]==UINT_MAX) {
            pass[next]=UINT_MAX-1; chain[depth++]=next;
            if (!parents[next]) break;
            next=parents[next]-1;
        }
        while (depth) {
            unsigned node=chain[--depth], p=parents[node];
            if (p && pass[p-1]==UINT_MAX-1) return -ELOOP;
            pass[node]=p ? pass[p-1]+(node<p-1) : 0;
            ++offsets[pass[node]+1];
        }
    }
    for (unsigned i=1;i<count;++i) offsets[i]+=offsets[i-1];
    for (unsigned i=0;i<count;++i) order[offsets[pass[i]]++]=i;
    return 0;
}
int md_view_topology_order(const struct md_mount_namespace *view, unsigned *order) {
    const struct md_fs_mounts *tree=view ? view->tree : NULL;
    if (!view || !order) return -EINVAL;
    unsigned count=tree ? tree->count : 0;
    if (count>MD_FS_MOUNTS_MAX) return -EINVAL;
    unsigned parents[count+1];
    memset(parents,0,sizeof(parents));
    for (unsigned i=0;i<=count;++i) {
        const struct md_view_mount *m=i ? &tree->mounts[i-1] : &view->base;
        if (!m->id) continue;
        if (!m->parent) { if (m!=view->root && !m->detached) return -EINVAL; continue; }
        if (m==view->root) return -EINVAL;
        if (m->parent==&view->base) { parents[i]=1; continue; }
        int slot=md_view_mount_slot(view,m->parent,count);
        if (slot<=0 || !tree->mounts[slot-1].id) return -EINVAL;
        parents[i]=(unsigned)slot+1;
    }
    return md_mount_topology_order(count+1,parents,order);
}
int md_mount_id_allocate(unsigned *out) {
    unsigned next=atomic_load_explicit(&next_mount_id,memory_order_relaxed);
    do {
        if (next==UINT_MAX) return -EOVERFLOW;
    } while (!atomic_compare_exchange_weak_explicit(&next_mount_id,&next,next+1,
        memory_order_relaxed,memory_order_relaxed));
    *out=next; return 0;
}
struct md_mount_namespace *md_mount_namespace_new(void) {
    struct md_mount_namespace *n=calloc(1,sizeof(*n));
    if (n) {
        n->base=(struct md_view_mount){.source=-1,.target=-1,.backing=MD_MOUNT_INODE,.generation=1};
        n->root=&n->base;
        int r=md_mount_id_allocate(&n->base.id);
        if (!r) r=md_mount_id_allocate(&n->shm_id);
        if (!r) r=md_namespace_identity_new(&n->inode);
        if (!r) r=-pthread_mutex_init(&n->domains_lock,NULL);
        if (r) { free(n); errno=-r; return NULL; }
        r=-pthread_mutex_init(&n->tables_lock,NULL);
        if (r) { pthread_mutex_destroy(&n->domains_lock); free(n); errno=-r; return NULL; }
        atomic_init(&n->refs,1);
        atomic_init(&n->managed_paths,0);
        atomic_init(&n->detached_mounts,0);
        r=md_mount_family_new(n);
        if (r) { md_mount_namespace_release(n); errno=-r; return NULL; }
    }
    return n;
}
struct md_mount_namespace *md_mount_namespace_retain(struct md_mount_namespace *n) {
    if (n) atomic_fetch_add_explicit(&n->refs,1,memory_order_relaxed);
    return n;
}
uint64_t md_mount_namespace_inode(const struct md_mount_namespace *n) { return n->inode; }
struct md_user_namespace *md_mount_namespace_owner(const struct md_mount_namespace *n) { return n ? n->owner : NULL; }
void md_mount_namespace_set_owner(struct md_mount_namespace *n, struct md_user_namespace *owner) {
    struct md_user_namespace *old=n->owner;
    n->owner=md_user_namespace_retain(owner); md_user_namespace_release(old);
}
int md_mount_namespace_lock_inherited(struct md_mount_namespace *n) {
    struct md_mount_members members={0};
    int error=md_mount_family_capture(n,&members);
    unsigned count=n->tree ? n->tree->count : 0;
    for (unsigned i=0;!error && i<=count;++i) {
        struct md_view_mount *m=md_view_mount_node(n,i);
        if (!m->id) continue;
        m->locked=1; error=md_mount_policy_lock(&m->policy);
        if (!error && m->links.shared) {
            struct md_mount_links links={0};
            error=md_mount_links_change(&members,&m->links,MS_SLAVE,&links);
            if (!error) { md_mount_links_release(&m->links); m->links=links; }
        }
    }
    md_mount_members_release(&members);
    return error;
}
void md_view_mount_close(struct md_view_mount *mount) {
    md_mount_links_release(&mount->links);
    md_mount_policy_close(&mount->policy);
    md_inode_lifetime_release(mount->storage);
    md_pid_namespace_release(mount->pid_namespace);
    md_devpts_release(mount->devpts);
    md_cgroup_release(mount->cgroup);
    md_sysfs_release(mount->sysfs);
    md_provider_tree_release(mount->provider);
    md_ipc_namespace_release(mount->ipc_namespace);
    md_control_file_release(mount->file);
    if (mount->source>=0) close(mount->source);
    if (mount->target>=0) close(mount->target);
    *mount=(struct md_view_mount){.source=-1,.target=-1,.detached=1,.generation=mount->generation};
}
static int host_backing(const struct md_view_mount *m) {
    return m && (m->backing==MD_MOUNT_NATIVE || m->backing==MD_MOUNT_PROC);
}
int md_view_mount_prepare_transport(struct md_view_mount *m) {
    if (m->local_custody) return 0;
    int fds[2]; unsigned count=0;
    if (host_backing(m)) fds[count++]=m->source;
    if (host_backing(m->parent)) fds[count++]=m->target;
    if (!count) return 0;
    int r=md_fs_rights_transferable(fds,count);
    if (r<0) return r;
    m->local_custody=!r;
    return 0;
}
int md_view_mount_clone(struct md_view_mount *out,const struct md_view_mount *source,int target,
        struct md_view_mount *parent) {
    uint64_t generation=out->generation;
    *out=(struct md_view_mount){.source=-1,.target=-1,.generation=generation+1,
        .parent=parent,.backing=source->backing,.locked=source->locked,
        .root_providers=source->root_providers,
        .local_custody=source->local_custody,
        .links=md_mount_links_copy(&source->links),
        .storage=md_inode_lifetime_retain(source->storage),
        .pid_namespace=md_pid_namespace_retain(source->pid_namespace),
        .devpts=md_devpts_retain(source->devpts),.cgroup=md_cgroup_retain(source->cgroup),
        .sysfs=md_sysfs_retain(source->sysfs),.provider=md_provider_tree_retain(source->provider),
        .ipc_namespace=md_ipc_namespace_retain(source->ipc_namespace),
        .file=md_control_file_retain(source->file),
        .policy={.local=md_mount_policy_flags(&source->policy)}};
    atomic_init(&out->anchors,0);
    int r=generation==UINT64_MAX ? -EOVERFLOW : md_mount_id_allocate(&out->id);
    if (!r && (out->source=fcntl(source->source,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    if (!r && (out->target=fcntl(target,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    if (!r && (fstat(out->source,&out->native) || fstat(out->target,&out->guest))) r=-errno;
    if (!r) r=md_view_mount_prepare_transport(out);
    return r;
}
void md_view_mount_forget(struct md_fs_mounts *tree, struct md_view_mount *mount) {
    tree->lookup.valid=0;
    for (unsigned i=0;i<MD_VIEW_BUCKETS;++i) {
        struct md_view_object **p=&tree->by_id[i];
        while (*p) {
            if ((*p)->mount==mount) *p=(*p)->id_next;
            else p=&(*p)->id_next;
        }
    }
    for (unsigned i=0;i<MD_VIEW_BUCKETS;++i) {
        struct md_view_object **p=&tree->table[i];
        while (*p) {
            if ((*p)->mount!=mount) { p=&(*p)->next; continue; }
            struct md_view_object *old=*p; *p=old->next;
            close(old->fd); free(old); --tree->objects;
        }
    }
    md_view_mount_close(mount);
}
void md_mount_namespace_release(struct md_mount_namespace *n) {
    if (!n || atomic_fetch_sub_explicit(&n->refs,1,memory_order_acq_rel)!=1) return;
    md_mount_family_leave(n);
    struct md_fs_mounts *m=n->tree;
    if (m) {
        for (unsigned i=0;i<MD_VIEW_BUCKETS;++i) {
            struct md_view_object *p=m->table[i];
            while (p) { struct md_view_object *next=p->next; close(p->fd); free(p); p=next; }
        }
        for (unsigned i=0;i<m->count;++i) md_view_mount_close(&m->mounts[i]);
        md_view_tree_free(m);
    }
    md_mount_graph_close(n->graph);
    md_user_namespace_release(n->owner);
    md_view_mount_close(&n->base);
    pthread_mutex_destroy(&n->domains_lock);
    pthread_mutex_destroy(&n->tables_lock);
    md_native_mounts_release(n->native_mounts);
    free(n->native_ids); free(n);
}
int md_mount_namespace_attached(const struct md_mount_namespace *n) {
    return n && n->tree && n->tree->count;
}
int md_mount_namespace_managed_paths(const struct md_mount_namespace *n) {
    return n && atomic_load_explicit(&n->managed_paths,memory_order_acquire);
}
