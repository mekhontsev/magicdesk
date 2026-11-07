#define _GNU_SOURCE
#include "mount_graph.h"
#include "fs_mount_internal.h"
#include "mount_topology.h"
#include "sealed_manifest.h"
#include "inode_internal.h"
#include "mount_stream.h"
#include "mount_pin.h"
#include "temporary_directory.h"
#include "host_fs_view.h"
#include "procfs_view.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum { MAGIC=0x4d444d47U, VERSION=17, HEADER=144 };
enum { INODE=1, DETACHED=2, RETIRED=4, LOCKED=8, PROC=16, PARENTLESS=32, ROOT_PROVIDERS=64, HOST_SYSFS=128, HOST_DEVFS=256, HOST_DEVICES=512, HOST_PROCFS=1024 };
#define HOST_PROVIDERS (HOST_SYSFS|HOST_DEVFS|HOST_DEVICES|HOST_PROCFS)
struct entry { uint32_t parent, flags; struct md_fd_identity source, target, policy, super_policy;
    uint32_t storage, descriptor; uint64_t generation, proc_namespace;
    uint32_t shared, master, unbindable, reserved; struct md_procfs_policy proc_policy; };
struct storage { struct md_fd_identity root; uint32_t start, count; };
struct manifest {
    uint32_t magic, version, count, storages;
    struct md_fd_identity root_policy;
    struct md_fd_identity stream, pin;
    uint64_t revision, unavailable;
    struct md_fd_identity base_source, base_target;
    uint32_t root, base_parent, base_flags, base_descriptor;
    uint32_t shared, master, unbindable, reserved;
    struct entry *entries;
    struct storage *storage;
    void *allocation;
};
struct md_mount_graph {
    int fd, reader, writer, pin;
    uint64_t revision, observed;
    struct md_fd_identity identity;
};
_Static_assert(sizeof(struct entry)==128 && sizeof(struct storage)==24,"mount graph entry layout");
_Static_assert(offsetof(struct manifest,entries)==HEADER,"mount graph header layout");

static void manifest_release(struct manifest *m) {
    free(m->allocation); *m=(struct manifest){0};
}

static struct md_mount_graph *create(void) {
    struct md_mount_graph *g=calloc(1,sizeof(*g));
    if (!g) return NULL;
    g->fd=g->reader=g->writer=g->pin=-1;
    return g;
}
int md_mount_graph_foreign(const struct md_mount_namespace *view) {
    return view && view->graph && view->graph->writer<0;
}
static int slot(const struct md_mount_namespace *view, const struct md_view_mount *m) {
    return view && view->graph && m ? md_view_mount_slot(view,m,view->tree ? view->tree->count : 0) : -ESTALE;
}
int md_mount_graph_acquire_pin(struct md_mount_namespace *view, const struct md_view_mount *m) {
    int index=slot(view,m);
    if (index<0 || !m->id) return -ESTALE;
    uint64_t generation=m->generation;
    int detached=m->detached;
    int pin=md_mount_pin_acquire(view->graph->pin,(unsigned)index);
    if (pin<0) return pin;
    /* A writer may have retired this slot between path resolution and lock
     * acquisition. Refresh while holding the lease before adopting its origin. */
    int r=md_mount_graph_sync(view);
    if (!r && (!m->id || m->generation!=generation || m->detached!=detached)) r=-ESTALE;
    if (r) { close(pin); return r; }
    return pin;
}
int md_mount_graph_exclude(const struct md_mount_namespace *view, const struct md_view_mount *m, int *lease) {
    *lease=-1;
    if (!view || !view->graph) return 0;
    if (view->graph->writer<0) return -EPERM;
    int index=slot(view,m);
    if (index<0) return index;
    int fd=md_mount_pin_exclude(view->graph->pin,(unsigned)index);
    if (fd<0) return fd;
    *lease=fd; return 0;
}
int md_mount_graph_check_pin(const struct md_mount_namespace *view, int pin) {
    return view && view->graph ? md_mount_pin_check(view->graph->pin,pin) : -EPROTO;
}
static size_t manifest_size(const struct manifest *m) {
    return HEADER+m->count*sizeof(struct entry)+m->storages*sizeof(struct storage);
}
static size_t manifest_bytes(const struct manifest *m, unsigned char *bytes) {
    size_t n=HEADER+m->count*sizeof(struct entry);
    memcpy(bytes,m,HEADER);
    if (m->count) memcpy(bytes+HEADER,m->entries,m->count*sizeof(struct entry));
    if (m->storages) memcpy(bytes+n,m->storage,m->storages*sizeof(struct storage));
    return manifest_size(m);
}
static int manifest_same(const struct manifest *a, const struct manifest *b) {
    return a->count==b->count && a->storages==b->storages
        && !memcmp(a,b,HEADER)
        && (!a->count || !memcmp(a->entries,b->entries,a->count*sizeof(struct entry)))
        && (!a->storages || !memcmp(a->storage,b->storage,a->storages*sizeof(struct storage)));
}
static unsigned stores(const struct md_view_mount *entries, unsigned size, struct md_inode_lifetime **owners) {
    unsigned count=0;
    for (unsigned i=0;i<size;++i) {
        struct md_inode_lifetime *owner=entries[i].storage;
        if (!owner) continue;
        unsigned j=0; while (j<count && !md_inode_lifetime_same(owner,owners[j])) ++j;
        if (j==count) owners[count++]=owner;
    }
    return count;
}
unsigned md_mount_graph_rights(struct md_mount_namespace *view) {
    if (!view || (view->tree && view->tree->count>MD_FS_MOUNTS_MAX)) return 0;
    if (md_mount_graph_sync(view)) return 0;
    struct md_inode_lifetime *owners[(view->tree ? view->tree->count : 0)+1];
    unsigned count=stores(view->tree ? view->tree->mounts : NULL,view->tree ? view->tree->count : 0,owners), rights=4;
    for (unsigned i=0;view->tree && i<view->tree->count;++i)
        if (view->tree->mounts[i].id) rights+=3+!!view->tree->mounts[i].provider;
    if (view->base.source>=0) rights+=2;
    for (unsigned i=0;i<count;++i) rights+=md_inode_custody_rights(owners[i]);
    return rights;
}
void md_mount_graph_close(struct md_mount_graph *graph) {
    if (graph) {
        if (graph->fd>=0) close(graph->fd);
        if (graph->reader>=0) close(graph->reader);
        if (graph->writer>=0) close(graph->writer);
        if (graph->pin>=0) close(graph->pin);
        free(graph);
    }
}
static int compare_fd(const void *a, const void *b) {
    int x=*(const int *)a, y=*(const int *)b; return (x>y)-(x<y);
}
void md_mount_graph_parcel_close(struct md_mount_graph_parcel *p) {
    if (!p) return;
    unsigned count=p->count>MD_MOUNT_GRAPH_FDS ? MD_MOUNT_GRAPH_FDS : p->count;
    if (p->fd) qsort(p->fd,count,sizeof(int),compare_fd);
    for (unsigned i=0;p->fd && i<count;++i)
        if (p->fd[i]>=0 && (!i || p->fd[i]!=p->fd[i-1])) close(p->fd[i]);
    free(p->fd); *p=(struct md_mount_graph_parcel){0};
}
static int cloexec(int fd) {
    int flags=fcntl(fd,F_GETFD);
    return flags<0 ? -errno : flags&FD_CLOEXEC ? 0 : -EINVAL;
}
static int validate_rights(const struct md_mount_graph_parcel *p) {
    int *sorted=calloc(p->count,sizeof(int));
    if (!sorted) return -ENOMEM;
    memcpy(sorted,p->fd,p->count*sizeof(int)); qsort(sorted,p->count,sizeof(int),compare_fd);
    int r=0;
    for (unsigned i=0;!r && i<p->count;++i)
        r=i && sorted[i]==sorted[i-1] ? -EPROTO : cloexec(sorted[i]);
    free(sorted); return r;
}
static int path_object(int fd, struct md_fd_identity expected, struct stat *st, int directory) {
    int flags=fcntl(fd,F_GETFL);
    if (flags<0) return -errno;
    if (!(flags&O_PATH)) return -EPROTO;
    if (fstat(fd,st)) return -errno;
    if (directory && !S_ISDIR(st->st_mode)) return -EPROTO;
    return md_fd_identity_same(md_fd_identity(st),expected) ? 0 : -EPROTO;
}
static int source_object(int fd,struct md_fd_identity expected,struct stat *st,unsigned flags) {
    if (!(flags&HOST_PROVIDERS)) return path_object(fd,expected,st,0);
    int mode=fcntl(fd,F_GETFL);
    if (mode<0) return -errno;
    if ((mode&O_PATH) || (mode&O_ACCMODE)!=O_RDONLY) return -EPROTO;
    if (fstat(fd,st)) return -errno;
    if (!md_fd_identity_same(md_fd_identity(st),expected)) return -EPROTO;
    return md_temporary_directory_check(fd);
}
static int topology(const struct manifest *m) {
    if (m->reserved || m->unbindable>1 || (m->unbindable && (m->shared || m->master))
            || (m->shared && m->shared==m->master)) return -EPROTO;
    if (m->root>m->count || m->base_parent>m->count || (m->base_flags&~(DETACHED|LOCKED|ROOT_PROVIDERS))) return -EPROTO;
    if (m->root ? (!m->base_descriptor || !m->base_parent) : (m->base_parent || (m->base_flags&DETACHED))) return -EPROTO;
    if (m->base_parent && (m->entries[m->base_parent-1].flags&RETIRED)) return -EPROTO;
    if (m->root && (m->entries[m->root-1].parent || (m->entries[m->root-1].flags&(DETACHED|RETIRED)))) return -EPROTO;
    unsigned parents[m->count+1], order[m->count+1];
    memset(parents,0,sizeof(parents));
    parents[0]=m->root ? m->base_parent+1 : 0;
    for (unsigned i=0;i<m->count;++i) {
        const struct entry *e=&m->entries[i];
        if (e->reserved || e->unbindable>1 || (e->unbindable && (e->shared || e->master))
                || (e->shared && e->shared==e->master)) return -EPROTO;
        if ((e->flags&PARENTLESS) && (e->parent || !(e->flags&DETACHED) || m->root==i+1)) return -EPROTO;
        parents[i+1]=(e->flags&(RETIRED|PARENTLESS)) || m->root==i+1 ? 0 : e->parent+1;
    }
    return md_mount_topology_order(m->count+1,parents,order);
}
static int parse_manifest(unsigned char *bytes,size_t n,struct manifest *out) {
    if (n<HEADER) return -EPROTO;
    memcpy(out,bytes,HEADER);
    if (out->magic!=MAGIC || out->version!=VERSION || out->storages>out->count
            || out->count>MD_FS_MOUNTS_MAX || manifest_size(out)!=n
            || !out->revision) return -EPROTO;
    if (out->unavailable) {
        struct manifest expected={.magic=MAGIC,.version=VERSION,
            .stream=out->stream,.revision=out->revision,.unavailable=EOPNOTSUPP};
        return n!=HEADER || memcmp(out,&expected,HEADER) ? -EPROTO : 0;
    }
    out->entries=(struct entry *)(bytes+HEADER);
    out->storage=(struct storage *)(bytes+HEADER+out->count*sizeof(struct entry));
    unsigned owners=0, next=3;
    if (out->base_descriptor) {
        if (out->base_descriptor!=next) return -EPROTO;
        next+=2;
    } else if (out->base_source.device || out->base_source.inode || out->base_target.device
            || out->base_target.inode) return -EPROTO;
    for (unsigned i=0;i<out->count;++i) {
        const struct entry *e=&out->entries[i];
        if (!e->generation || e->flags&~(INODE|DETACHED|RETIRED|LOCKED|PROC|PARENTLESS|ROOT_PROVIDERS|HOST_PROVIDERS) || e->storage>out->storages
                || e->storage>owners+1 || (e->storage && !(e->flags&INODE))) return -EPROTO;
        if (!!(e->flags&PROC)!=!!e->proc_namespace || (e->flags&(INODE|PROC))==(INODE|PROC)) return -EPROTO;
        if (e->flags&HOST_PROCFS) {
            if (!(e->flags&PROC) || !md_procfs_policy_valid(&e->proc_policy)) return -EPROTO;
        } else if (memcmp(&e->proc_policy,&(struct md_procfs_policy){0},sizeof(e->proc_policy))) return -EPROTO;
        unsigned type=e->flags&(INODE|HOST_PROVIDERS|((e->flags&HOST_PROCFS) ? 0 : PROC));
        if (type && (type&(type-1))) return -EPROTO;
        if (e->flags&RETIRED) {
            struct entry empty={.flags=DETACHED|RETIRED,.generation=e->generation};
            if (memcmp(e,&empty,sizeof(empty))) return -EPROTO;
            continue;
        }
        if (e->descriptor!=next) return -EPROTO;
        next+=3+!!(e->flags&HOST_PROVIDERS);
        if (!(e->flags&HOST_PROVIDERS) && (e->super_policy.device || e->super_policy.inode)) return -EPROTO;
        if (e->parent && (e->parent>out->count || (out->entries[e->parent-1].flags&RETIRED))) return -EPROTO;
        if (e->storage==owners+1) ++owners;
        for (unsigned j=0;j<i;++j) if (!(e->flags&DETACHED) && !(out->entries[j].flags&DETACHED)
                && out->root!=i+1 && out->root!=j+1
                && e->parent==out->entries[j].parent
                && md_fd_identity_same(e->target,out->entries[j].target)) return -EPROTO;
    }
    if (owners!=out->storages) return -EPROTO;
    for (unsigned i=0;i<owners;++i) {
        const struct storage *s=&out->storage[i];
        if (s->start!=next || s->count<2 || s->count>MD_INODE_CUSTODY_FDS) return -EPROTO;
        next+=s->count;
        for (unsigned j=0;j<i;++j)
            if (md_fd_identity_same(s->root,out->storage[j].root)) return -EPROTO;
    }
    return topology(out) ? -EPROTO : 0;
}
static int read_manifest(int fd,struct manifest *out,struct md_fd_identity *id) {
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    if (st.st_size<HEADER || (uint64_t)st.st_size>HEADER+(sizeof(struct entry)+sizeof(struct storage))*MD_FS_MOUNTS_MAX)
        return -EPROTO;
    unsigned char *bytes=malloc((size_t)st.st_size);
    if (!bytes) return -ENOMEM;
    size_t n=0;
    int r=md_sealed_manifest_read(fd,bytes,(size_t)st.st_size,&n,id);
    if (!r) r=parse_manifest(bytes,n,out);
    if (r) { free(bytes); *out=(struct manifest){0}; }
    else out->allocation=bytes;
    return r;
}
static struct md_mount_layout layout(struct md_mount_namespace *view) {
    return (struct md_mount_layout){.base=&view->base,.root=view->root,
        .entries=view->tree ? view->tree->mounts : NULL,.count=view->tree ? view->tree->count : 0};
}
static int mount_slot(const struct md_mount_namespace *view, const struct md_view_mount *m, unsigned count) {
    return md_view_mount_slot(view,m,count);
}
static int mount_portable(const struct md_view_mount *m) {
    if (m->local_custody || m->devpts || m->cgroup || m->sysfs || m->ipc_namespace || m->file.ops) return 0;
    if (!m->provider) return 1;
    struct stat root;
    return (md_host_fs_kind(m->provider) || (m->backing==MD_MOUNT_PROC && md_procfs_export(m->provider,NULL)))
        && !fstat(md_provider_tree_root(m->provider),&root)
        && md_view_same(&m->native,&root);
}
static int layout_portable(const struct md_mount_namespace *view,const struct md_mount_layout *l) {
    for (unsigned i=0;i<=l->count;++i) {
        const struct md_view_mount *m=i ? &l->entries[i-1] : l->base;
        if (!m->id) continue;
        if (!mount_portable(m)) return 0;
        if (!m->parent) continue;
        int slot=mount_slot(view,m->parent,l->count);
        if (slot<0) return 0;
        const struct md_view_mount *parent=slot ? &l->entries[slot-1] : l->base;
        /* Host projection carriers may open locally yet be rejected by
         * SCM_RIGHTS. Only guest-owned attachment points are portable. */
        if (parent->provider && md_provider_tree_private(parent->provider,m->target)!=1) return 0;
    }
    return 1;
}
int md_mount_graph_portable(const struct md_mount_namespace *view) {
    if (!view) return 1;
    struct md_mount_layout l={.base=&view->base,.root=view->root,
        .entries=view->tree ? view->tree->mounts : NULL,.count=view->tree ? view->tree->count : 0};
    return layout_portable(view,&l);
}
static int describe(struct md_mount_namespace *view, const struct md_mount_layout *l, struct manifest *out,
        int fds[MD_MOUNT_GRAPH_PLACEMENT_FDS], struct md_inode_lifetime **owners) {
    if (l->count>MD_FS_MOUNTS_MAX) return -EINVAL;
    if (!layout_portable(view,l)) return -EOPNOTSUPP;
    const struct md_view_mount *entries=l->entries;
    unsigned count=l->count;
    *out=(struct manifest){.magic=MAGIC,.version=VERSION,
        .stream=view->graph->identity,.revision=view->graph->revision};
    out->allocation=calloc(count ? count : 1,sizeof(struct entry)+sizeof(struct storage));
    if (!out->allocation) return -ENOMEM;
    out->entries=out->allocation;
    out->storage=(struct storage *)(out->entries+count);
    int root=md_mount_policy_share(&view->base.policy);
    if (root<0) return root;
    struct stat root_stat;
    if (fstat(root,&root_stat)) return -errno;
    out->root_policy=md_fd_identity(&root_stat); fds[1]=root;
    if (fstat(view->graph->pin,&root_stat)) return -errno;
    out->pin=md_fd_identity(&root_stat); fds[2]=view->graph->pin;
    out->count=count;
    if (out->count>MD_FS_MOUNTS_MAX) return -EINVAL;
    int root_slot=mount_slot(view,l->root,count), base_parent=mount_slot(view,l->base->parent,count);
    if (root_slot<0 || base_parent<0) return -EINVAL;
    out->root=(unsigned)root_slot; out->base_parent=(unsigned)base_parent;
    out->shared=md_mount_group_id(l->base->links.shared);
    out->master=md_mount_group_id(l->base->links.master);
    out->unbindable=l->base->links.unbindable;
    out->base_flags=(l->base->detached ? DETACHED : 0) | (l->base->locked ? LOCKED : 0);
    if (l->base->root_providers) out->base_flags|=ROOT_PROVIDERS;
    if (l->base->source>=0) {
        int r=path_object(l->base->source,md_fd_identity(&l->base->native),&(struct stat){0},1);
        if (!r) r=path_object(l->base->target,md_fd_identity(&l->base->guest),&(struct stat){0},1);
        if (r) return r;
        out->base_descriptor=3;
        out->base_source=md_fd_identity(&l->base->native); out->base_target=md_fd_identity(&l->base->guest);
        fds[3]=l->base->source; fds[4]=l->base->target;
    }
    out->storages=stores(entries,count,owners);
    unsigned next=out->base_descriptor ? 5 : 3;
    for (unsigned i=0;i<count;++i) if (entries[i].id) next+=3+!!entries[i].provider;
    for (unsigned i=0;i<out->storages;++i) {
        out->storage[i]=(struct storage){.root={owners[i]->device,owners[i]->inode},
            .start=next,.count=md_inode_custody_rights(owners[i])};
        next+=out->storage[i].count;
    }
    next=out->base_descriptor ? 5 : 3;
    for (unsigned i=0;i<count;++i) {
        const struct md_view_mount *m=&entries[i]; struct entry *e=&out->entries[i];
        if (!m->generation) return -EINVAL;
        e->generation=m->generation;
        if (!m->id) { e->flags=DETACHED|RETIRED; continue; }
        e->shared=md_mount_group_id(m->links.shared);
        e->master=md_mount_group_id(m->links.master);
        e->unbindable=m->links.unbindable;
        /* A private device view cannot be flattened into a native attachment. */
        if (!mount_portable(m)) return -EOPNOTSUPP;
        e->descriptor=next; next+=3+!!m->provider;
        if (m->storage) {
            if (!md_view_inode(m)) return -EINVAL;
            unsigned j=0; while (j<out->storages && !md_inode_lifetime_same(m->storage,owners[j])) ++j;
            if (j==out->storages) return -ESTALE;
            e->storage=j+1;
        }
        e->flags=(md_view_inode(m) ? INODE : 0) | (m->detached ? DETACHED : 0) | (m->locked ? LOCKED : 0);
        if (m->root_providers) e->flags|=ROOT_PROVIDERS;
        if (m->provider) {
            enum md_host_fs_view kind=md_host_fs_kind(m->provider);
            e->flags|=md_procfs_export(m->provider,&e->proc_policy) ? HOST_PROCFS
                : kind==MD_HOST_FS_SYS ? HOST_SYSFS : kind==MD_HOST_FS_DEVICES ? HOST_DEVICES : HOST_DEVFS;
        }
        if (!m->parent && l->root!=&view->tree->mounts[i]) e->flags|=PARENTLESS;
        if (m->backing==MD_MOUNT_PROC) {
            e->flags|=PROC; e->proc_namespace=md_pid_namespace_inode(m->pid_namespace);
        }
        if (!md_view_base(view,m->parent)) {
            unsigned j=0;
            while (j<count && m->parent!=&view->tree->mounts[j]) ++j;
            if (j==count || !entries[j].id) return -EINVAL;
            e->parent=j+1;
        }
        int source=m->provider ? md_provider_tree_root(m->provider) : m->source;
        int r=source_object(source,md_fd_identity(&m->native),&(struct stat){0},e->flags);
        if (!r) r=path_object(m->target,md_fd_identity(&m->guest),&(struct stat){0},0);
        if (r) return r;
        e->source=md_fd_identity(&m->native); e->target=md_fd_identity(&m->guest);
        for (unsigned j=0;j<i;++j) if (!(e->flags&DETACHED) && !(out->entries[j].flags&DETACHED)
                && out->root!=i+1 && out->root!=j+1
                && e->parent==out->entries[j].parent
                && md_fd_identity_same(e->target,out->entries[j].target)) return -EINVAL;
        int policy=md_mount_policy_share((struct md_mount_policy *)&m->policy);
        if (policy<0) return policy;
        struct stat st; if (fstat(policy,&st)) return -errno;
        e->policy=md_fd_identity(&st);
        fds[e->descriptor]=source; fds[e->descriptor+1]=m->target; fds[e->descriptor+2]=policy;
        if (m->provider) {
            int super=md_mount_policy_share(md_provider_tree_super_policy(m->provider));
            if (super<0) return super;
            if (fstat(super,&st)) return -errno;
            e->super_policy=md_fd_identity(&st); fds[e->descriptor+3]=super;
        }
    }
    return topology(out);
}
static unsigned placement_rights(const struct manifest *data) {
    unsigned count=data->base_descriptor ? 5 : 3;
    for (unsigned i=0;i<data->count;++i) if (!(data->entries[i].flags&RETIRED))
        count+=3+!!(data->entries[i].flags&HOST_PROVIDERS);
    return count;
}
static int parcel(const struct manifest *data, int *fds, struct md_inode_lifetime **owners,
        struct md_mount_graph_parcel *out) {
    unsigned placement=placement_rights(data), rights=placement;
    for (unsigned i=0;i<data->storages;++i) rights+=data->storage[i].count;
    struct md_mount_graph_parcel candidate={.fd=calloc(rights,sizeof(int))};
    if (!candidate.fd) return -ENOMEM;
    int r=0;
    for (unsigned i=0;i<placement;++i) {
        int fd=fcntl(fds[i],F_DUPFD_CLOEXEC,3);
        if (fd<0) { r=-errno; break; }
        candidate.fd[candidate.count++]=fd;
    }
    for (unsigned i=0;!r && i<data->storages;++i) {
        struct md_inode_custody_parcel p={0};
        r=md_inode_custody_export(owners[i],&p);
        if (!r && (p.count!=data->storage[i].count || candidate.count!=data->storage[i].start
                || p.count>rights-candidate.count)) r=-ESTALE;
        if (!r) { memcpy(candidate.fd+candidate.count,p.fd,p.count*sizeof(int)); candidate.count+=p.count; p.count=0; }
        md_inode_custody_parcel_close(&p);
    }
    if (r) md_mount_graph_parcel_close(&candidate);
    else *out=candidate;
    return r;
}
struct prepared { int manifest; struct md_mount_graph_parcel parcel; struct md_mount_stream_change stream; };
static int prepare_update(struct md_mount_namespace *view,const struct md_mount_layout *l,struct prepared *out) {
    struct md_mount_graph *g=view->graph;
    if (g->writer<0) return -EPERM;
    if (g->revision==UINT64_MAX) return -EOVERFLOW;
    if (l->count>MD_FS_MOUNTS_MAX) return -EINVAL;
    struct manifest data={0}; int fds[6+4*l->count];
    struct md_inode_lifetime *owners[l->count+1];
    int r=describe(view,l,&data,fds,owners);
    /* Exportability is not mount authority. A local typed provider publishes
     * an unavailable revision so foreign views cannot use stale topology or
     * mistake its Android carrier for the logical provider. */
    if (r==-EOPNOTSUPP) {
        manifest_release(&data);
        data=(struct manifest){.magic=MAGIC,.version=VERSION,
            .stream=g->identity,.unavailable=EOPNOTSUPP};
        r=0;
    }
    if (r) { manifest_release(&data); return r; }
    data.revision=g->revision+1;
    size_t size=manifest_size(&data);
    unsigned char *bytes=malloc(size);
    if (!bytes) { manifest_release(&data); return -ENOMEM; }
    manifest_bytes(&data,bytes);
    struct md_fd_identity id;
    int fd=md_sealed_manifest_create("shroot-mount-graph",bytes,size,&id);
    free(bytes);
    if (fd<0) { manifest_release(&data); return fd; }
    fds[0]=fd;
    out->manifest=fd;
    if (!data.unavailable) r=parcel(&data,fds,owners,&out->parcel);
    if (!r) out->stream=(struct md_mount_stream_change){g->reader,g->writer,g->revision,
        data.unavailable ? &out->manifest : out->parcel.fd,data.unavailable ? 1 : out->parcel.count};
    manifest_release(&data); return r;
}
int md_mount_graph_update_many(const struct md_mount_graph_change *changes,unsigned count) {
    if (count && !changes) return -EINVAL;
    unsigned used=0;
    for (unsigned i=0;i<count;++i) used+=changes[i].view && changes[i].view->graph;
    if (!used) return 0;
    struct prepared *p=calloc(used,sizeof(*p));
    struct md_mount_stream_change *streams=calloc(used,sizeof(*streams));
    if (!p || !streams) { free(p); free(streams); return -ENOMEM; }
    for (unsigned i=0;i<used;++i) p[i].manifest=-1;
    int r=0; unsigned n=0;
    for (unsigned i=0;i<count && !r;++i) {
        if (!changes[i].view || !changes[i].view->graph) continue;
        r=prepare_update(changes[i].view,&changes[i].layout,p+n);
        if (!r) streams[n]=p[n].stream;
        ++n;
    }
    if (!r) r=md_mount_stream_publish_many(streams,used);
    n=0;
    for (unsigned i=0;i<count && !r;++i) {
        struct md_mount_graph *g=changes[i].view ? changes[i].view->graph : NULL;
        if (!g) continue;
        if (g->fd>=0) close(g->fd);
        g->fd=p[n].manifest; p[n++].manifest=-1; ++g->revision;
    }
    for (unsigned i=0;i<used;++i) {
        if (p[i].manifest>=0) close(p[i].manifest);
        md_mount_graph_parcel_close(&p[i].parcel);
    }
    free(streams); free(p); return r;
}
int md_mount_graph_update(struct md_mount_namespace *view,const struct md_mount_layout *layout) {
    const struct md_mount_graph_change change={view,*layout};
    return md_mount_graph_update_many(&change,1);
}
int md_mount_graph_export(struct md_mount_namespace *view, int root, struct md_mount_graph_parcel *out) {
    if (!view || !out || out->count || out->fd) return -EINVAL;
    int r=md_mount_graph_sync(view);
    if (r) return r;
    if (!md_mount_graph_portable(view)) return -EOPNOTSUPP;
    if (!view->graph) {
        struct md_mount_graph *g=create();
        if (!g) return -ENOMEM;
        int pin=md_mount_pin_create(root);
        if (pin<0) r=pin; else g->pin=pin;
        if (!r) r=md_mount_stream_new(&g->reader,&g->writer);
        struct stat st;
        if (!r && fstat(g->reader,&st)) r=-errno;
        if (!r) {
            g->identity=md_fd_identity(&st); view->graph=g;
            struct md_mount_layout l=layout(view);
            r=md_mount_graph_update(view,&l);
        }
        if (r) { view->graph=NULL; md_mount_graph_close(g); return r; }
    }
    struct manifest data={0},known={0};
    struct md_mount_layout l=layout(view);
    int fds[6+4*l.count]; struct md_inode_lifetime *owners[l.count+1]; struct md_fd_identity id;
    r=describe(view,&l,&data,fds,owners);
    if (!r) r=read_manifest(view->graph->fd,&known,&id);
    if (!r && !manifest_same(&known,&data)) r=-ESTALE;
    if (r) { manifest_release(&known); manifest_release(&data); return r; }
    fds[0]=view->graph->fd;
    struct md_mount_graph_parcel p={0};
    r=parcel(&data,fds,owners,&p);
    manifest_release(&known); manifest_release(&data);
    if (!r) {
        int *grown=realloc(p.fd,(p.count+1)*sizeof(int));
        if (!grown) r=-ENOMEM;
        else {
            p.fd=grown;
            int reader=fcntl(view->graph->reader,F_DUPFD_CLOEXEC,3);
            if (reader<0) r=-errno; else p.fd[p.count++]=reader;
        }
    }
    if (r) md_mount_graph_parcel_close(&p); else *out=p;
    return r;
}
static int take(struct md_mount_graph_parcel *p, unsigned index) {
    int fd=p->fd[index]; p->fd[index]=-1; return fd;
}
static int same_objects(const struct manifest *old, const struct manifest *next) {
    if (old->revision>next->revision) return same_objects(next,old);
    unsigned count=old->count<next->count ? old->count : next->count;
    if (!md_fd_identity_same(old->root_policy,next->root_policy)
            || !md_fd_identity_same(old->pin,next->pin)) return -ESTALE;
    if (old->base_descriptor && (!next->base_descriptor
            || !md_fd_identity_same(old->base_source,next->base_source))) return -ESTALE;
    for (unsigned i=0;i<count;++i) {
        const struct entry *a=&old->entries[i], *b=&next->entries[i];
        if (a->generation>b->generation) return -ESTALE;
        if (a->generation<b->generation) continue;
        if (b->flags&RETIRED) continue;
        if (a->flags&RETIRED) return -ESTALE;
        if ((a->flags&(INODE|PROC|HOST_PROVIDERS))!=(b->flags&(INODE|PROC|HOST_PROVIDERS)) || a->proc_namespace!=b->proc_namespace
                || memcmp(&a->proc_policy,&b->proc_policy,sizeof(a->proc_policy))
                || !md_fd_identity_same(a->source,b->source)
                || !md_fd_identity_same(a->policy,b->policy)
                || !md_fd_identity_same(a->super_policy,b->super_policy)
                || !!a->storage!=!!b->storage) return -ESTALE;
        if (a->storage && !md_fd_identity_same(old->storage[a->storage-1].root,
                next->storage[b->storage-1].root)) return -ESTALE;
    }
    return 0;
}
static int merge_known(struct md_mount_namespace *view,struct md_mount_namespace *next,
        struct manifest before,struct manifest after) {
    int r=same_objects(&before,&after);
    if (!r && before.count>after.count) r=-ESTALE;
    for (unsigned i=0;!r && i<before.count;++i) {
        const struct entry *a=&before.entries[i], *b=&after.entries[i];
        if ((a->generation!=b->generation || (b->flags&RETIRED))
                && atomic_load_explicit(&view->tree->mounts[i].anchors,memory_order_relaxed)) r=-EBUSY;
    }
    if (r) return r;
    if (!view->tree && after.count) {
        view->tree=md_view_tree_new();
        if (!view->tree) return -ENOMEM;
    }
    /* Existing slots and cached object IDs remain stable. Only prepared
     * placement descriptors change; no fallible operation follows publication. */
    md_view_mount_lookup_changed(view);
    for (unsigned i=0;i<after.count;++i) {
        struct md_view_mount *a=&view->tree->mounts[i], *b=&next->tree->mounts[i];
        unsigned parent=after.entries[i].parent;
        if (i>=before.count || a->generation!=b->generation || !a->id || !b->id) {
            if (i<before.count) md_view_mount_forget(view->tree,a);
            *a=*b;
            *b=(struct md_view_mount){.source=-1,.target=-1};
        } else {
            close(a->target); a->target=b->target; b->target=-1;
            a->guest=b->guest; a->detached=b->detached;
            a->locked=b->locked;
            a->root_providers=b->root_providers;
            md_mount_links_release(&a->links); a->links=b->links;
            b->links=(struct md_mount_links){0};
        }
        a->parent=a->id && after.root!=i+1 && !(after.entries[i].flags&PARENTLESS)
            ? (parent ? &view->tree->mounts[parent-1] : &view->base) : NULL;
    }
    if (view->base.source>=0) close(view->base.source);
    if (view->base.target>=0) close(view->base.target);
    view->base.source=next->base.source; next->base.source=-1;
    view->base.target=next->base.target; next->base.target=-1;
    view->base.native=next->base.native; view->base.guest=next->base.guest;
    view->base.detached=next->base.detached;
    view->base.locked=next->base.locked;
    view->base.root_providers=next->base.root_providers;
    md_mount_links_release(&view->base.links); view->base.links=next->base.links;
    next->base.links=(struct md_mount_links){0};
    view->base.parent=after.base_parent ? &view->tree->mounts[after.base_parent-1] : NULL;
    view->root=after.root ? &view->tree->mounts[after.root-1] : &view->base;
    if (view->tree) view->tree->count=after.count;
    close(view->graph->fd); view->graph->fd=next->graph->fd; next->graph->fd=-1;
    view->graph->revision=next->graph->revision;
    return 0;
}
static int merge(struct md_mount_namespace *view,struct md_mount_namespace *next) {
    struct manifest before={0},after={0}; struct md_fd_identity id;
    int r=read_manifest(view->graph->fd,&before,&id);
    if (!r) r=read_manifest(next->graph->fd,&after,&id);
    if (!r) r=merge_known(view,next,before,after);
    manifest_release(&before); manifest_release(&after); return r;
}
static int import_known(struct md_mount_graph_parcel *p,struct md_mount_namespace **out,struct manifest data) {
    int r=0;
    if (data.unavailable) {
        if (p->count!=2) return -EPROTO;
        struct stat stream;
        if (fstat(p->fd[1],&stream)) return -errno;
        if (!S_ISSOCK(stream.st_mode) || !md_fd_identity_same(md_fd_identity(&stream),data.stream)) return -EPROTO;
        if (*out && (!(*out)->graph || !md_fd_identity_same((*out)->graph->identity,data.stream)
                || data.revision<=(*out)->graph->revision)) return -ESTALE;
        return -EOPNOTSUPP;
    }
    unsigned rights=placement_rights(&data);
    for (unsigned i=0;i<data.storages;++i) rights+=data.storage[i].count;
    if (p->count!=rights+1) return -EPROTO;
    struct stat root_stat;
    if (fstat(p->fd[1],&root_stat)) return -errno;
    if (!md_fd_identity_same(md_fd_identity(&root_stat),data.root_policy)) return -EPROTO;
    if (fstat(p->fd[2],&root_stat)) return -errno;
    if (!md_fd_identity_same(md_fd_identity(&root_stat),data.pin)
            || !S_ISREG(root_stat.st_mode) || root_stat.st_nlink || root_stat.st_size) return -EPROTO;
    struct stat stream;
    if (fstat(p->fd[rights],&stream)) return -errno;
    if (!S_ISSOCK(stream.st_mode) || !md_fd_identity_same(md_fd_identity(&stream),data.stream)) return -EPROTO;
    if (*out && (!(*out)->graph || !md_fd_identity_same((*out)->graph->identity,data.stream))) return -ESTALE;
    if (data.base_descriptor) {
        r=path_object(p->fd[3],data.base_source,&root_stat,1);
        if (!r) r=path_object(p->fd[4],data.base_target,&root_stat,1);
        if (r) return r;
    }
    /* Validate even an interned graph's rights; identity never excuses swapped
     * or truncated descriptors. A known view retains its original caches/IDs. */
    for (unsigned i=0;i<data.count;++i) {
        struct entry *e=&data.entries[i]; struct stat st;
        if (e->flags&RETIRED) continue;
        r=source_object(p->fd[e->descriptor],e->source,&st,e->flags);
        if (!r) r=path_object(p->fd[e->descriptor+1],e->target,&st,0);
        if (!r && fstat(p->fd[e->descriptor+2],&st)) r=-errno;
        if (!r && !md_fd_identity_same(md_fd_identity(&st),e->policy)) r=-EPROTO;
        if (!r && (e->flags&HOST_PROVIDERS)) {
            if (fstat(p->fd[e->descriptor+3],&st)) r=-errno;
            else if (!md_fd_identity_same(md_fd_identity(&st),e->super_policy)) r=-EPROTO;
        }
        if (r) return r;
    }
    struct md_inode_lifetime *owners[data.storages+1];
    memset(owners,0,sizeof(owners));
    for (unsigned i=0;!r && i<data.storages;++i) {
        const struct storage *s=&data.storage[i]; struct stat st;
        if (fstat(p->fd[s->start+1],&st)) { r=-errno; break; }
        if (!md_fd_identity_same(md_fd_identity(&st),s->root)) { r=-EPROTO; break; }
        struct md_inode_custody_parcel custody={.count=s->count};
        for (unsigned j=0;j<s->count;++j) custody.fd[j]=take(p,s->start+j);
        r=md_inode_custody_import(&custody,custody.fd[1],&owners[i]);
    }
    if (!r && *out) {
        struct manifest known={0};
        struct md_mount_layout l=layout(*out);
        int borrowed[6+4*l.count]; struct md_inode_lifetime *existing[l.count+1];
        r=describe(*out,&l,&known,borrowed,existing);
        if (!r && known.revision==data.revision && !manifest_same(&known,&data)) r=-ESTALE;
        if (!r) r=same_objects(&known,&data);
        if (!r && known.revision<data.revision && (*out)->graph->writer>=0) r=-ESTALE;
        int complete=r || known.revision>=data.revision;
        manifest_release(&known);
        if (complete) goto done;
    }
    if (r) goto done;
    struct md_mount_namespace *view=md_mount_namespace_new();
    if (!view) { r=-errno; goto done; }
    atomic_store_explicit(&view->managed_paths,1,memory_order_relaxed);
    view->graph=create();
    if (!view->graph) { md_mount_namespace_release(view); r=-ENOMEM; goto done; }
    view->graph->fd=take(p,0); view->graph->reader=take(p,rights);
    view->graph->revision=data.revision; view->graph->identity=data.stream;
    view->graph->pin=take(p,2);
    r=md_mount_policy_import(&view->base.policy,p->fd[1]);
    if (!r) take(p,1);
    if (!r) r=md_mount_links_observe(data.shared,data.master,data.unbindable,&view->base.links);
    if (data.count) {
        view->tree=md_view_tree_new();
        if (!view->tree) r=-ENOMEM;
    }
    for (unsigned i=0;!r && i<data.count;++i) {
        struct md_view_mount *m=&view->tree->mounts[i];
        struct entry *e=&data.entries[i];
        m->source=m->target=-1; m->generation=e->generation;
        ++view->tree->count;
        if (e->flags&RETIRED) { m->detached=1; continue; }
        r=md_mount_links_observe(e->shared,e->master,e->unbindable,&m->links);
        if (r) break;
        m->backing=(e->flags&INODE) ? MD_MOUNT_INODE : e->flags&PROC ? MD_MOUNT_PROC
            : e->flags&HOST_PROVIDERS ? MD_MOUNT_PROVIDER : MD_MOUNT_NATIVE;
        if ((e->flags&PROC) && e->proc_namespace!=md_pid_namespace_inode(NULL)) {
            m->pid_namespace=md_pid_namespace_by_inode(e->proc_namespace);
            if (!m->pid_namespace) { r=-EXDEV; break; }
        }
        m->storage=e->storage ? md_inode_lifetime_retain(owners[e->storage-1]) : NULL;
        atomic_init(&m->anchors,0);
        m->detached=!!(e->flags&DETACHED);
        m->locked=!!(e->flags&LOCKED);
        m->root_providers=!!(e->flags&ROOT_PROVIDERS);
        m->source=take(p,e->descriptor); m->target=take(p,e->descriptor+1);
        if (fstat(m->source,&m->native) || fstat(m->target,&m->guest)) r=-errno;
        if (!r && (e->flags&HOST_PROVIDERS)) {
            m->provider=e->flags&HOST_PROCFS ? md_procfs_import(m->source,&e->proc_policy)
                : md_host_fs_import(m->source,e->flags&HOST_SYSFS ? MD_HOST_FS_SYS
                : e->flags&HOST_DEVICES ? MD_HOST_FS_DEVICES : MD_HOST_FS_DEV);
            if (!m->provider) r=-errno;
            if (!r) r=md_mount_policy_import(md_provider_tree_super_policy(m->provider),p->fd[e->descriptor+3]);
            if (!r) take(p,e->descriptor+3);
        }
        if (!r) r=md_mount_policy_import(&m->policy,p->fd[e->descriptor+2]);
        if (!r) { take(p,e->descriptor+2); r=md_mount_id_allocate(&m->id); }
        m->parent=data.root==i+1 || (e->flags&PARENTLESS) ? NULL
            : e->parent ? &view->tree->mounts[e->parent-1] : &view->base;
    }
    if (!r && data.base_descriptor) {
        view->base.source=take(p,3); view->base.target=take(p,4);
        if (fstat(view->base.source,&view->base.native) || fstat(view->base.target,&view->base.guest)) r=-errno;
    }
    if (!r) {
        view->base.parent=data.base_parent ? &view->tree->mounts[data.base_parent-1] : NULL;
        view->base.detached=!!(data.base_flags&DETACHED);
        view->base.locked=!!(data.base_flags&LOCKED);
        view->base.root_providers=!!(data.base_flags&ROOT_PROVIDERS);
        view->root=data.root ? &view->tree->mounts[data.root-1] : &view->base;
    }
    if (!r && *out) r=merge(*out,view);
    if (r || *out) md_mount_namespace_release(view);
    else *out=view;
done:
    for (unsigned i=0;i<data.storages;++i) md_inode_lifetime_release(owners[i]);
    return r;
}
static int import(struct md_mount_graph_parcel *p,struct md_mount_namespace **out) {
    if (!out || !p->fd || !p->count || p->count>MD_MOUNT_GRAPH_FDS) return -EINVAL;
    int r=validate_rights(p);
    struct manifest data={0}; struct md_fd_identity id;
    if (!r) r=read_manifest(p->fd[0],&data,&id);
    if (!r) r=import_known(p,out,data);
    manifest_release(&data); return r;
}
int md_mount_graph_import(struct md_mount_graph_parcel *p, struct md_mount_namespace **out) {
    if (!p) return -EINVAL;
    int r=import(p,out); md_mount_graph_parcel_close(p); return r;
}
int md_mount_graph_sync(struct md_mount_namespace *view) {
    if (!view || !view->graph || view->graph->writer>=0) return 0;
    uint64_t sequence,revision=0;
    int r=md_mount_stream_revision(view->graph->reader,&sequence);
    if (r || sequence==view->graph->observed) return r;
    if (sequence<view->graph->observed) return -ESTALE;
    struct md_mount_graph_parcel p={0};
    int settled=0;
    r=md_mount_stream_read(view->graph->reader,&revision,&p.fd,&p.count,&settled);
    if (!r && revision<view->graph->revision) r=-ESTALE;
    int changed=!r && revision!=view->graph->revision;
    if (changed) {
        int *grown=realloc(p.fd,(p.count+1)*sizeof(int));
        if (!grown) r=-ENOMEM;
        else {
            p.fd=grown;
            int fd=fcntl(view->graph->reader,F_DUPFD_CLOEXEC,3);
            if (fd<0) r=-errno; else p.fd[p.count++]=fd;
        }
    }
    if (!r && changed) r=import(&p,&view);
    if (!r && settled) view->graph->observed=sequence;
    md_mount_graph_parcel_close(&p);
    return r;
}
