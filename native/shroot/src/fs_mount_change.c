#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include "mount_topology.h"
#include "mount_graph.h"
#include "fs_storage.h"
#include "fs_mount_propagation.h"
#include "fs_continuation.h"
#include "host_fs_view.h"
#include "fs_context.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <linux/mount.h>
#include <linux/magic.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>
#include <unistd.h>

int md_view_require_root(struct md_filesystem *fs,const struct md_view_location *p) {
    if (!p->mount) return -EINVAL;
    /* The initial inode mount has no materialized source until relocation.
     * Its logical root identity is independent of path spelling and chroot. */
    if (md_view_base(p->view,p->mount) && p->mount->source<0) {
        char id[33];
        int r=md_inode_object_id(fs->store,p->fd,id);
        return r ? r : strcmp(id,MDI_ROOT) ? -EINVAL : 0;
    }
    struct stat st;
    return fstat(p->fd,&st) ? -errno : md_view_same(&st,&p->mount->native) ? 0 : -EINVAL;
}
static int walk_operand(struct md_filesystem *fs, const struct md_fs_request *q, unsigned index,
        struct md_view_location *out,struct md_view_walk_pending **pending) {
    int clone=q->operation==MD_FS_OPEN_TREE;
    int follow=clone ? !(q->mode&AT_SYMLINK_NOFOLLOW)
        : q->mode==MD_FS_MOUNT_SETATTR ? !(q->flags&AT_SYMLINK_NOFOLLOW)
        : q->mode==MD_FS_MOUNT_UMOUNT ? !(q->flags&UMOUNT_NOFOLLOW)
        : q->mode!=MD_FS_MOUNT_MOVE_FD || (q->flags&(index ? MOVE_MOUNT_F_SYMLINKS : MOVE_MOUNT_T_SYMLINKS));
    uint64_t resolve=MD_VIEW_TASK_PATH|((clone || (index==1 && q->mode==MD_FS_MOUNT_BIND)) ? MD_VIEW_PROVIDER_SOURCE : 0)
        |(!index && !clone ? MD_VIEW_MOUNT_TARGET : 0);
    int r;
    if (pending && *pending) {
        r=md_view_walk_resume(fs,*pending,out,NULL);
        if (r!=-EINPROGRESS) { md_view_walk_destroy(*pending); *pending=NULL; }
    } else if (!*q->path[index]) r=md_view_descriptor(fs,q->directory[index],q->origin[index],out);
    else if (pending) r=md_view_walk_async(fs,q->directory[index],q->origin[index],q->path[index],follow,0,resolve,out,NULL,pending);
    else r=md_view_walk(fs,q->directory[index],q->origin[index],q->path[index],follow,0,resolve,out);
    if (r==-EREMOTE && fs->store->boundary) fs->store->boundary->operand=index;
    /* Legacy mount targets follow mounts above a retained dentry, including
     * proc-FD links and cwd after pivot_root(".", "."). FD-based mount APIs
     * instead name the selected mount itself, even when another covers it. */
    if (!r && !clone && q->mode!=MD_FS_MOUNT_SETATTR && q->mode!=MD_FS_MOUNT_MOVE_FD)
        r=md_view_location_mountpoint(out);
    return r;
}
static int operand(struct md_filesystem *fs, const struct md_fs_request *q, unsigned index,
        struct md_view_location *out,const struct md_view_location *resolved) {
    int r=0;
    if (!resolved || resolved[index].fd<0) {
        r=walk_operand(fs,q,index,out,NULL);
        if (r) return r;
    } else {
        *out=resolved[index]; out->fd=out->parent=-1;
        out->fd=fcntl(resolved[index].fd,F_DUPFD_CLOEXEC,3);
        if (out->fd<0) return -errno;
        if (resolved[index].parent>=0) {
            out->parent=fcntl(resolved[index].parent,F_DUPFD_CLOEXEC,3);
            if (out->parent<0) { int error=-errno; md_view_location_close(out); return error; }
        }
    }
    struct md_filesystem selected=*fs; selected.mounts=out->view;
    int recursive=q->operation==MD_FS_OPEN_TREE ? !!(q->mode&AT_RECURSIVE)
        : q->mode==MD_FS_MOUNT_SETATTR ? !!(q->flags&AT_RECURSIVE)
        : q->mode==MD_FS_MOUNT_UMOUNT || q->mode==MD_FS_MOUNT_MOVE
            || q->mode==MD_FS_MOUNT_MOVE_FD || q->mode==MD_FS_MOUNT_PIVOT
            || ((q->flags&MS_REC) && (q->mode!=MD_FS_MOUNT_BIND || index==1));
    /* Recursive bind copies the source subtree. Its target is only an
     * attachment point; unrelated target-view mounts need no reconciliation. */
    r=recursive ? md_view_mount_reconcile(&selected)
        : md_view_mount_reconcile_branch(&selected,out->mount);
    if (r) md_view_location_close(out);
    return r;
}
static int descendant(const struct md_view_mount *child, const struct md_view_mount *parent) {
    for (;child;child=child->parent) if (child==parent) return 1;
    return 0;
}
static int policy_check(struct md_filesystem *fs, struct md_view_mount *mount, unsigned flags) {
    int r=md_mount_policy_check(&mount->policy,flags);
    if (!r && !(flags&MD_MOUNT_READONLY) && md_view_inode(mount)) {
        struct md_inode_store *store=NULL;
        r=md_fs_storage_get(fs,mount,&store);
        if (!r && store->readonly) r=-EROFS;
    }
    return r;
}
static int attribute_check(const struct mount_attr *a) {
    uint64_t known=MOUNT_ATTR_RDONLY|MOUNT_ATTR_NOSUID|MOUNT_ATTR_NODEV|MOUNT_ATTR_NOEXEC
        |MOUNT_ATTR__ATIME|MOUNT_ATTR_NODIRATIME|MOUNT_ATTR_IDMAP|MOUNT_ATTR_NOSYMFOLLOW;
    uint64_t propagation=a->propagation;
    if (((a->attr_set|a->attr_clr)&~known) || (a->attr_clr&MOUNT_ATTR_IDMAP)
            || (propagation&~(uint64_t)(MS_PRIVATE|MS_SLAVE|MS_SHARED|MS_UNBINDABLE))
            || (propagation && (propagation&(propagation-1)))) return -EINVAL;
    unsigned atime=a->attr_set&MOUNT_ATTR__ATIME, clear=a->attr_clr&MOUNT_ATTR__ATIME;
    if (clear ? clear!=MOUNT_ATTR__ATIME || (atime!=MOUNT_ATTR_RELATIME
            && atime!=MOUNT_ATTR_NOATIME && atime!=MOUNT_ATTR_STRICTATIME) : atime!=0) return -EINVAL;
    if ((a->attr_set|a->attr_clr)&MOUNT_ATTR_IDMAP) return -EOPNOTSUPP;
    return 0;
}
static int attributes(struct md_filesystem *fs, const struct md_fs_request *q,
        const struct md_view_location *target, const struct mount_attr *a) {
    struct md_view_mount *root=target->mount ? target->mount : &fs->mounts->base;
    int error=md_view_require_root(fs,target);
    if (error) return error;
    /* A detached fsmount root has no parent. An unmounted old placement is not
     * a detached construction tree and must not regain namespace authority. */
    for (const struct md_view_mount *m=root;m;m=m->parent)
        if (m->detached && m->parent) return -EINVAL;
    unsigned nodes=fs->mounts->tree ? fs->mounts->tree->count+1 : 1;
    struct md_view_mount *mounts[nodes];
    unsigned policies[nodes], count=0;
    unsigned set=md_fs_mount_attributes(a->attr_set), clear=md_fs_mount_attributes(a->attr_clr);
    for (unsigned i=0;i<nodes;++i) {
        struct md_view_mount *m=md_view_mount_node(fs->mounts,i);
        if (!m->id || (m!=root && (!(q->flags&AT_RECURSIVE) || m->detached || !descendant(m,root)))) continue;
        unsigned flags=(md_mount_policy_flags(&m->policy)&MD_MOUNT_FLAGS&~clear)|set;
        int r=policy_check(fs,m,flags);
        if (r) return r;
        mounts[count]=m; policies[count++]=flags;
    }
    if (a->propagation) {
        int r=md_view_mount_propagation(fs,root,(unsigned)a->propagation,!!(q->flags&AT_RECURSIVE));
        if (r) return r;
    }
    /* All fallible checks precede publication. The filesystem worker is the
     * sole writer; policies and placements cannot change between these passes. */
    for (unsigned i=0;i<count;++i) {
        int r=md_mount_policy_set(&mounts[i]->policy,policies[i]);
        if (r) return r;
    }
    return 0;
}
static int beneath(struct md_filesystem *fs, int fd, const struct md_view_location *source) {
    struct stat st;
    if (fstat(source->fd,&st)) return -errno;
    /* A file bind has no descendants. Its inode can have several names, none
     * of which is needed to retain the selected file as the new mount root. */
    if (!S_ISDIR(st.st_mode)) return 0;
    return md_view_backing_contains(fs,source->mount,source->fd,fd);
}
static int publish(struct md_filesystem *fs, unsigned first,
        struct md_view_mount *entries, unsigned count) {
    if (!fs->mounts->graph) return 0;
    struct md_fs_mounts *tree=fs->mounts->tree;
    if (first+count>MD_FS_MOUNTS_MAX) return -ENOSPC;
    for (unsigned i=0;i<count;++i) {
        if (!entries[i].id) continue;
        int r=md_mount_policy_share(&entries[i].policy);
        if (r<0) return r;
    }
    unsigned size=tree ? tree->count : 0;
    struct md_mount_layout layout={.base=&fs->mounts->base,.root=fs->mounts->root,
        .entries=entries,.count=count};
    if (!first && count>=size) return md_mount_graph_update(fs->mounts,&layout);
    unsigned capacity=first+count>size ? first+count : size;
    struct md_view_mount *candidate=calloc(capacity,sizeof(*candidate));
    if (!candidate) return -ENOMEM;
    if (tree) memcpy(candidate,tree->mounts,size*sizeof(*candidate));
    memcpy(candidate+first,entries,count*sizeof(*entries));
    if (first+count>size) size=first+count;
    layout.entries=candidate; layout.count=size;
    int r=md_mount_graph_update(fs->mounts,&layout);
    free(candidate); return r;
}
int md_view_mount_collect(struct md_filesystem *fs) {
    int r=md_view_mount_reconcile(fs);
    return r ? r : md_view_mount_collect_detached(fs);
}
static int collect_admission(struct md_filesystem *fs) {
    /* At capacity discover deleted placements before reporting ENOSPC. The
     * common admission only retires objects already known to be detached. */
    return fs->mounts->tree && fs->mounts->tree->count==MD_FS_MOUNTS_MAX
        ? md_view_mount_collect(fs) : md_view_mount_collect_detached(fs);
}
int md_view_mount_collect_detached(struct md_filesystem *fs) {
    struct md_fs_mounts *tree=fs->mounts->tree;
    if (!tree) return 0;
    if (md_mount_graph_foreign(fs->mounts)) return -EPERM;
    if (!atomic_load_explicit(&fs->mounts->detached_mounts,memory_order_acquire)) return 0;
    unsigned retire[tree->count+1], order[tree->count+1];
    int leases[tree->count+1];
    memset(retire,0,sizeof(retire));
    for (unsigned i=0;i<tree->count;++i) leases[i]=-1;
    int r=md_view_topology_order(fs->mounts,order);
    if (r) return r;
    for (unsigned i=0;i<tree->count;++i) {
        struct md_view_mount *m=&tree->mounts[i];
        if (!m->id || !m->detached || atomic_load_explicit(&m->anchors,memory_order_relaxed)
                || (fs->native_root && fs->native_root->mount==m)
                || md_domain_mount_root_used(fs->mounts,m->id)) continue;
        r=md_mount_graph_exclude(fs->mounts,m,&leases[i]);
        if (r==-EBUSY) { r=0; continue; }
        if (r) break;
        retire[i+1]=1;
    }
    /* A retained child keeps its ancestors, including a detached subtree.
     * Independent OFD leases exclude new foreign origins until publication. */
    for (unsigned n=tree->count+1;n;--n) {
        unsigned i=order[n-1];
        struct md_view_mount *m=md_view_mount_node(fs->mounts,i);
        if (!retire[i] && m->parent) {
            unsigned parent=md_view_base(fs->mounts,m->parent) ? 0
                : (unsigned)(m->parent-tree->mounts)+1;
            retire[parent]=0;
        }
    }
    unsigned count=0;
    for (unsigned i=0;i<tree->count;++i) count+=!!retire[i+1];
    struct md_view_mount *next=NULL;
    if (!r && count) {
        next=calloc(tree->count,sizeof(*next));
        if (!next) r=-ENOMEM;
        else {
            memcpy(next,tree->mounts,tree->count*sizeof(*next));
            for (unsigned i=0;i<tree->count;++i) if (retire[i+1])
                next[i]=(struct md_view_mount){.source=-1,.target=-1,.detached=1,.generation=next[i].generation};
        }
    }
    if (!r && count) r=publish(fs,0,next,tree->count);
    if (!r) for (unsigned i=0;i<tree->count;++i)
        if (retire[i+1]) md_view_mount_forget(tree,&tree->mounts[i]);
    for (unsigned i=0;i<tree->count;++i) if (leases[i]>=0) close(leases[i]);
    free(next);
    if (!r) md_fs_storage_prune(fs->storage);
    if (!r) {
        int detached=0;
        for (unsigned i=0;i<tree->count;++i) detached|=tree->mounts[i].id && tree->mounts[i].detached;
        atomic_store_explicit(&fs->mounts->detached_mounts,detached,memory_order_release);
    }
    return r;
}
static int prepare(struct md_view_mount *out, int source, int target,
        struct md_view_mount *parent, enum md_mount_backing backing,
        struct md_inode_lifetime *storage, unsigned policy) {
    *out=(struct md_view_mount){.source=-1,.target=-1,.parent=parent,.backing=backing,
        .generation=out->generation+1,
        .storage=md_inode_lifetime_retain(storage)};
    atomic_init(&out->anchors,0);
    int r=md_mount_policy_set(&out->policy,policy);
    if (!r) r=md_mount_id_allocate(&out->id);
    if (!r && (out->source=fcntl(source,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    if (!r && (out->target=fcntl(target,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    if (!r && (fstat(out->source,&out->native) || fstat(out->target,&out->guest))) r=-errno;
    return r;
}
static int bind(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_view_location *target,struct md_view_mount **out,const struct md_view_location *resolved) {
    if (q->flags&~(MS_BIND|MS_REC)) return -ENOTSUP;
    unsigned input=q->operation==MD_FS_OPEN_TREE ? 0 : 1;
    struct md_view_location source={.fd=-1,.parent=-1};
    int r=0;
    int native_source=0;
    struct md_control_file file={0};
    unsigned policy=0;
    if (!r && q->control.ops) {
        const struct md_control_file_ops *ops=q->control.ops;
        if (!ops->at || !ops->descriptor) r=-EOPNOTSUPP;
        else if (md_view_file_cycle(q->control,fs->mounts)) r=-EINVAL;
        else {
            void *context=ops->at(q->control.context,NULL);
            if (!context) r=-errno;
            else {
                file=(struct md_control_file){ops,context};
                source.fd=ops->descriptor(context,O_PATH);
                if (source.fd<0) r=source.fd;
                else native_source=1;
            }
        }
    }
    if (!r && !native_source && q->path[input] && !*q->path[input] && (!q->origin[input] || !q->origin[input]->view)) {
        struct md_view_object *object=NULL;
        int found=q->origin[input] ? -EXDEV : md_view_identity(fs,q->directory[input],&object);
        if (q->origin[input]) r=md_view_anchor_check(fs,q->directory[input],q->origin[input]);
        char inode[33];
        if (found==-EXDEV) found=md_inode_object_id(fs->store,q->directory[input],inode);
        if (found==-EXDEV) {
            source.fd=fcntl(q->directory[input],F_DUPFD_CLOEXEC,3);
            if (source.fd<0) r=-errno;
            else native_source=1;
            if (!r) r=md_mount_native_policy(source.fd,&policy);
        } else if (found) r=found;
    }
    if (!r && !native_source) {
        r=operand(fs,q,input,&source,resolved);
        if (!r) policy=md_mount_policy_flags(source.mount ? &source.mount->policy : &source.view->base.policy);
        if (!r && source.mount) file=md_control_file_retain(source.mount->file);
        if (!r && source.mount && source.mount->links.unbindable) r=-EINVAL;
        if (!r && source.mount && md_mount_links_foreign(&source.mount->links)) r=-EXDEV;
    }
    if (!r && md_view_file_cycle(file,fs->mounts)) r=-EINVAL;
    struct md_view_mount next={.source=-1,.target=-1};
    struct md_provider_tree *devices=NULL;
    int shared=-1;
    struct md_inode_lifetime *shared_store=NULL;
    if (!r && source.implicit_devices) {
        int parent=md_fs_private_mounts_parent(fs->store);
        if (parent<0) r=parent;
        else {
            devices=md_host_fs_new(parent,MD_HOST_FS_DEV);
            if (!devices) r=-errno;
            close(parent);
        }
        if (!r && (q->flags&MS_REC)) {
            struct md_fs_storage_scope scope;
            r=md_fs_storage_enter(fs,source.mount,&scope);
            if (!r) {
                shared=md_inode_open(scope.store,source.fd,"shm",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
                if (shared<0) r=shared;
                else shared_store=md_inode_lifetime_retain(scope.store->lifetime);
                md_fs_storage_leave(&scope);
            }
        }
    }
    if (!r && (fstat(source.fd,&next.native) || (target && fstat(target->fd,&next.guest)))) r=-errno;
    if (!r && target && !!S_ISDIR(next.native.st_mode)!=!!S_ISDIR(next.guest.st_mode)) r=-ENOTDIR;
    if (!r && !fs->mounts->tree) {
        fs->mounts->tree=md_view_tree_new();
        if (!fs->mounts->tree) r=-ENOMEM;
    }
    struct md_fs_mounts *tree=fs->mounts->tree;
    unsigned count=tree ? tree->count : 0, prepared=0;
    struct md_fs_mounts *original=source.view ? source.view->tree : NULL;
    unsigned capacity=count+2+((q->flags&MS_REC) && original ? original->count : 0);
    if (capacity>MD_FS_MOUNTS_MAX) capacity=MD_FS_MOUNTS_MAX;
    unsigned slots[capacity];
    struct md_view_mount *pending=md_view_mount_array_new();
    if (!pending) r=-ENOMEM;
    if (pending && tree) memcpy(pending,tree->mounts,count*sizeof(*pending));
    int root_slot=-1;
    if (!r) {
        root_slot=md_view_mount_vacant(pending,&count);
        if (root_slot<0) r=root_slot;
    }
    if (!r) {
        slots[prepared++]=(unsigned)root_slot;
        enum md_mount_backing backing=devices ? MD_MOUNT_PROVIDER : native_source ? MD_MOUNT_NATIVE
            : source.mount ? source.mount->backing : MD_MOUNT_INODE;
        if (native_source) {
            struct statfs type;
            if (fstatfs(source.fd,&type)) r=-errno;
            else if (type.f_type==PROC_SUPER_MAGIC) backing=MD_MOUNT_PROC;
        }
        int device_fd=devices ? openat(md_provider_tree_root(devices),".",O_PATH|O_DIRECTORY|O_CLOEXEC) : -1;
        int source_fd=devices ? device_fd : source.fd;
        if (!r) r=devices && device_fd<0 ? -errno : prepare(&pending[root_slot],source_fd,target ? target->fd : source_fd,target ? target->mount : NULL,
            backing,
            !devices && source.mount ? source.mount->storage : NULL,
            policy);
        if (device_fd>=0) close(device_fd);
        pending[root_slot].detached=!target;
        if (!r && devices) pending[root_slot].provider=md_provider_tree_retain(devices);
        if (!r && source.mount && !devices) {
            if (target) pending[root_slot].links=md_mount_links_copy(&source.mount->links);
            pending[root_slot].pid_namespace=md_pid_namespace_retain(source.mount->pid_namespace);
            pending[root_slot].devpts=md_devpts_retain(source.mount->devpts);
            pending[root_slot].cgroup=md_cgroup_retain(source.mount->cgroup);
            pending[root_slot].sysfs=md_sysfs_retain(source.mount->sysfs);
            pending[root_slot].provider=md_provider_tree_retain(source.mount->provider);
            pending[root_slot].ipc_namespace=md_ipc_namespace_retain(source.mount->ipc_namespace);
        }
        if (!r) pending[root_slot].file=md_control_file_retain(file);
    }
    int shared_slot=-1;
    if (!r && shared>=0) {
        shared_slot=md_view_mount_vacant(pending,&count);
        if (shared_slot<0) r=shared_slot;
        else {
            slots[prepared++]=(unsigned)shared_slot;
            int covered=openat(md_provider_tree_root(devices),"shm",O_PATH|O_DIRECTORY|O_CLOEXEC);
            if (covered<0) r=-errno;
            else {
                r=prepare(&pending[shared_slot],shared,covered,&tree->mounts[root_slot],
                    MD_MOUNT_INODE,shared_store,policy);
                close(covered);
            }
        }
    }
    if (!(q->flags&MS_REC)) for (unsigned i=0;!r && original && i<original->count;++i) {
        struct md_view_mount *m=&original->mounts[i];
        if (!m->id || m->detached || !m->locked || m->parent!=source.mount) continue;
        struct md_filesystem selected=*fs; selected.mounts=source.view;
        r=md_view_mount_reconcile_branch(&selected,m);
        if (r || m->detached) continue;
        int found=beneath(fs,m->target,&source);
        if (found) r=found<0 ? found : -EINVAL;
    }
    unsigned nodes=native_source ? 0 : original ? original->count+1 : 1;
    unsigned included[nodes+1], order[nodes+1];
    memset(included,0,sizeof(included));
    if (!r && !native_source && (q->flags&MS_REC)) r=md_view_topology_order(source.view,order);
    if (q->flags&MS_REC) for (unsigned n=0;!r && n<nodes;++n) {
        unsigned i=order[n];
        struct md_view_mount *m=md_view_mount_node(source.view,i);
        if (!m->id || m->source<0 || m->detached || m==source.mount || m->links.unbindable) continue;
        unsigned parent=(unsigned)root_slot;
        if (m->parent==source.mount) {
            int found=beneath(fs,m->target,&source);
            if (found<0) { r=found; break; }
            if (!found) continue;
            if (devices) {
                struct md_view_location memory=source; memory.fd=shared;
                found=beneath(fs,m->target,&memory);
                if (found<0) { r=found; break; }
                if (!found) continue;
                parent=(unsigned)shared_slot;
            }
        } else {
            parent=0;
            for (unsigned j=0;j<nodes;++j)
                if (m->parent==md_view_mount_node(source.view,j)) parent=included[j];
            if (!parent) continue;
            --parent;
        }
        int slot=md_view_mount_vacant(pending,&count);
        if (slot<0) { r=slot; break; }
        included[i]=(unsigned)slot+1; slots[prepared++]=(unsigned)slot;
        r=md_view_mount_clone(&pending[slot],m,m->target,&tree->mounts[parent]);
        if (!target) md_mount_links_release(&pending[slot].links);
        if (!r && md_view_file_cycle(m->file,fs->mounts)) r=-EINVAL;
    }
    /* No tree entry is visible until the complete recursive copy owns all its
     * descriptors. Failed preparation retires only the unpublished candidates. */
    if (!r) r=md_view_mount_attach(fs,pending,count,slots,prepared,(unsigned)root_slot);
    if (!r) {
        if (out) *out=&tree->mounts[root_slot];
    } else for (unsigned i=0;i<prepared;++i) md_view_mount_close(&pending[slots[i]]);
    if (shared>=0) close(shared);
    md_inode_lifetime_release(shared_store);
    md_provider_tree_release(devices);
    md_control_file_release(file); md_view_location_close(&source); md_view_mount_array_free(pending); return r;
}
static void open_tree(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *result,const struct md_fs_output *out,const struct md_view_location *resolved) {
    *result=(struct md_fs_result){.fd=-1};
    int r=!fs->mounts || !fs->store->identity || !md_identity_capable_in(fs->store->identity,
        md_mount_namespace_owner(fs->mounts),CAP_SYS_ADMIN) ? -EPERM : 0;
    if (!r && (!out || !out->origin)) r=-EOPNOTSUPP;
    if (!r) r=collect_admission(fs);
    struct md_fs_request clone=*q;
    clone.flags=MS_BIND|((q->mode&AT_RECURSIVE) ? MS_REC : 0);
    struct md_view_mount *mount=NULL;
    if (!r) r=bind(fs,&clone,NULL,&mount,resolved);
    if (!r) {
        result->fd=fcntl(mount->source,F_DUPFD_CLOEXEC,3);
        if (result->fd<0) r=-errno;
        else {
            md_view_capture(fs,fs->mounts,mount,result,out);
            r=result->error;
        }
    }
    if (mount) {
        atomic_store_explicit(&fs->mounts->managed_paths,1,memory_order_release);
        if (r) md_view_mount_collect(fs);
    }
    result->error=r;
}
int md_view_attach_source(struct md_filesystem *fs, const struct md_mount_source *source,
        const struct md_view_location *target, struct md_view_mount **out) {
    if (!fs || !fs->mounts || !fs->storage || !source || source->fd<0
            || (target && target->view!=fs->mounts))
        return -EINVAL;
    struct stat st;
    int r=target ? md_view_mount_reconcile_branch(fs,target->mount) : 0;
    if (!r) r=collect_admission(fs);
    if (!r && fstat(source->fd,&st)) r=-errno;
    int directory=!r && S_ISDIR(st.st_mode);
    if (!r && !directory && !S_ISREG(st.st_mode)) r=-EOPNOTSUPP;
    if (!r && target && fstat(target->fd,&st)) r=-errno;
    if (!r && directory!=!!S_ISDIR(st.st_mode)) r=directory ? -ENOTDIR : -EISDIR;
    if (!r && !directory && !S_ISREG(st.st_mode)) r=-EINVAL;
    if (r) return r;
    if (!r && !fs->mounts->tree) {
        fs->mounts->tree=md_view_tree_new();
        if (!fs->mounts->tree) r=-ENOMEM;
    }
    if (r) return r;
    struct md_fs_mounts *tree=fs->mounts->tree;
    unsigned count=tree->count;
    struct md_view_mount *pending=md_view_mount_array_new();
    if (!pending) return -ENOMEM;
    memcpy(pending,tree->mounts,count*sizeof(*pending));
    int slot=md_view_mount_vacant(pending,&count);
    if (slot<0) { md_view_mount_array_free(pending); return slot; }
    r=prepare(&pending[slot],source->fd,target ? target->fd : source->fd,
        target ? target->mount : NULL,source->backing,source->storage,source->policy);
    if (!r) {
        pending[slot].detached=!target;
        pending[slot].pid_namespace=md_pid_namespace_retain(source->pid);
        pending[slot].devpts=md_devpts_retain(source->devpts);
        pending[slot].cgroup=md_cgroup_retain(source->cgroup);
        pending[slot].sysfs=md_sysfs_retain(source->sysfs);
        pending[slot].provider=md_provider_tree_retain(source->provider);
        pending[slot].ipc_namespace=md_ipc_namespace_retain(source->ipc);
    }
    unsigned root_slot=(unsigned)slot;
    if (!r) r=md_view_mount_attach(fs,pending,count,&root_slot,1,root_slot);
    if (!r) {
        if (out) *out=&tree->mounts[slot];
        atomic_store_explicit(&fs->mounts->managed_paths,1,memory_order_release);
    }
    else md_view_mount_close(&pending[slot]);
    md_view_mount_array_free(pending); return r;
}
static int unmount(struct md_filesystem *fs, const struct md_fs_request *q,
        const struct md_view_location *target) {
    if (q->flags&~(MNT_DETACH|UMOUNT_NOFOLLOW)) return -ENOTSUP;
    int error=md_view_require_root(fs,target);
    if (error) return error;
    if (target->mount->detached) return -EINVAL;
    if (target->mount->locked) return -EINVAL;
    if (target->mount==fs->mounts->root) return -EBUSY;
    struct md_fs_mounts *tree=fs->mounts->tree;
    int lease=-1;
    if (!(q->flags&MNT_DETACH)) {
        if (atomic_load_explicit(&target->mount->anchors,memory_order_relaxed)) return -EBUSY;
        if ((fs->native_root && fs->native_root->mount==target->mount)
                || md_domain_mount_root_used(fs->mounts,target->mount->id)) return -EBUSY;
        if (!fs->mounts->base.detached && fs->mounts->base.parent==target->mount) return -EBUSY;
        for (unsigned i=0;i<tree->count;++i)
            if (!tree->mounts[i].detached && tree->mounts[i].parent==target->mount) return -EBUSY;
        int r=target->mount->parent && target->mount->parent->links.shared
            ? 0 : md_mount_graph_exclude(fs->mounts,target->mount,&lease);
        if (r) return r;
    }
    int r=md_view_mount_propagate_detach(fs,target->mount,!!(q->flags&MNT_DETACH));
    if (lease>=0) close(lease);
    return r;
}
static int move(struct md_filesystem *fs, const struct md_fs_request *q,
        const struct md_view_location *target,const struct md_view_location *resolved) {
    int by_fd=q->mode==MD_FS_MOUNT_MOVE_FD;
    if (!by_fd && q->flags!=MS_MOVE) return -ENOTSUP;
    if (by_fd && (q->flags&~(MOVE_MOUNT_F_EMPTY_PATH|MOVE_MOUNT_T_EMPTY_PATH
            |MOVE_MOUNT_F_SYMLINKS|MOVE_MOUNT_T_SYMLINKS))) return -EOPNOTSUPP;
    struct md_view_location source={.fd=-1,.parent=-1};
    int r=operand(fs,q,1,&source,resolved);
    if (!r) r=source.view!=fs->mounts ? -EINVAL : md_view_require_root(fs,&source);
    /* Only an unplaced mount-FD root may be attached. An already unmounted
     * tree is not an anonymous mount namespace and cannot be resurrected. */
    if (!r && source.mount->detached && (!by_fd || source.mount->parent)) r=-EINVAL;
    for (const struct md_view_mount *m=target->mount;!r && m;m=m->parent)
        if (m->detached) r=-EINVAL;
    if (!r && source.mount->locked) r=-EINVAL;
    if (!r && source.mount->parent && source.mount->parent->links.shared) r=-EINVAL;
    if (!r && source.mount==fs->mounts->root) r=-EBUSY;
    if (!r && descendant(target->mount,source.mount)) r=-EINVAL;
    struct stat st;
    if (!r && fstat(target->fd,&st)) r=-errno;
    if (!r && !!S_ISDIR(st.st_mode)!=!!S_ISDIR(source.mount->native.st_mode)) r=-ENOTDIR;
    int covered=-1;
    if (!r && (covered=fcntl(target->fd,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    if (!r && target->mount->links.shared) {
        struct md_fs_mounts *tree=fs->mounts->tree;
        struct md_view_mount base=fs->mounts->base;
        struct md_view_mount *pending=md_view_mount_array_new();
        if (!pending) { close(covered); md_view_location_close(&source); return -ENOMEM; }
        memcpy(pending,tree->mounts,tree->count*sizeof(*pending));
        unsigned slots[tree->count+1],used=0,order[tree->count+1];
        unsigned root=source.mount==&fs->mounts->base ? 0 : (unsigned)(source.mount-tree->mounts)+1;
        r=md_view_topology_order(fs->mounts,order);
        for (unsigned n=0;!r && n<=tree->count;++n) {
            unsigned i=order[n];
            struct md_view_mount *m=md_view_mount_node(fs->mounts,i);
            if (!m->id || (m!=source.mount && m->detached) || !descendant(m,source.mount)) continue;
            struct md_view_mount *next=i ? &pending[i-1] : &base;
            slots[used++]=i; next->links=md_mount_links_copy(&m->links);
        }
        struct md_view_mount *next=root ? &pending[root-1] : &base;
        next->target=covered; next->guest=st;
        next->parent=target->mount; next->detached=0;
        if (!r) r=md_view_mount_place(fs,&base,pending,tree->count,slots,used,root);
        if (r) {
            for (unsigned i=0;i<used;++i) md_mount_links_release(slots[i] ? &pending[slots[i]-1].links : &base.links);
            close(covered);
        }
        md_view_mount_array_free(pending); md_view_location_close(&source); return r;
    }
    if (!r) {
        struct md_view_mount *m=source.mount;
        struct md_view_mount next=*m;
        next.target=covered; next.guest=st; next.parent=target->mount; next.detached=0;
        if (m==&fs->mounts->base) {
            struct md_mount_layout l={.base=&next,.root=fs->mounts->root,
                .entries=fs->mounts->tree->mounts,.count=fs->mounts->tree->count};
            r=md_mount_graph_update(fs->mounts,&l);
        } else r=publish(fs,(unsigned)(m-fs->mounts->tree->mounts),&next,1);
    }
    if (!r) {
        struct md_view_mount *m=source.mount;
        int old=m->target;
        md_view_mount_lookup_changed(fs->mounts);
        m->target=covered; m->guest=st; m->parent=target->mount; m->detached=0;
        close(old);
    } else if (covered>=0) close(covered);
    md_view_location_close(&source); return r;
}
static int anchor(struct md_filesystem *fs, int fd, struct md_view_mount *mount,
        struct md_fs_anchor **out) {
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    int copy=fcntl(fd,F_DUPFD_CLOEXEC,3);
    if (copy<0) return -errno;
    *out=md_view_anchor_new(md_inode_store_lifetime(fs->store),fs->mounts,mount,copy,st.st_dev,st.st_ino);
    if (!*out) { int r=-errno; close(copy); return r; }
    return 0;
}
static int pivot(struct md_filesystem *fs, struct md_guest_domain *context,
        const struct md_fs_request *q, const struct md_view_location *next,const struct md_view_location *resolved) {
    if (q->flags) return -EINVAL;
    if (!context) return -EINVAL;
    struct md_view_mount *old=md_view_root_mount(fs);
    int error=md_view_require_root(fs,next);
    if (error) return error;
    if (old->detached || next->mount->detached) return -EINVAL;
    if (next->mount->locked) return -EINVAL;
    if (old==next->mount) return -EBUSY;
    if (!descendant(next->mount,old)) return -EINVAL;
    int root_fd=md_view_root_fd(fs);
    if (root_fd<0) return root_fd;
    struct md_view_mount base=fs->mounts->base;
    int initialized=0, r=0;
    if (old==&fs->mounts->base && base.source<0) {
        const char *selected=fs->store->root_id; fs->store->root_id=NULL;
        base.source=md_inode_open(fs->store,MD_INODE_ROOT,"/",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
        fs->store->root_id=selected;
        if (base.source<0) r=base.source;
        else {
            initialized=1;
            base.target=fcntl(base.source,F_DUPFD_CLOEXEC,3);
            if (base.target<0) r=-errno;
            if (!r && fstat(base.source,&base.native)) r=-errno;
            base.guest=base.native;
        }
    }
    const struct md_view_mount *previous=old==&fs->mounts->base ? &base : old;
    struct stat current;
    if (!r && fstat(root_fd,&current)) r=-errno;
    if (!r && !md_view_same(&current,&previous->native)) r=-EINVAL;
    struct md_view_location put={.fd=-1,.parent=-1};
    if (!r) r=operand(fs,q,1,&put,resolved);
    if (!r && ((old->parent && old->parent->links.shared)
            || (next->mount->parent && next->mount->parent->links.shared) || put.mount->links.shared)) r=-EINVAL;
    if (!r && put.view!=fs->mounts) r=-EINVAL;
    if (!r && put.mount==old) r=-EBUSY;
    if (!r && !descendant(put.mount,next->mount)) r=-EINVAL;
    struct stat st;
    if (!r && fstat(put.fd,&st)) r=-errno;
    if (!r && (!S_ISDIR(st.st_mode) || !S_ISDIR(next->mount->native.st_mode))) r=-ENOTDIR;
    if (!r && !st.st_nlink) r=-ENOENT;
    struct md_fs_anchor *from=NULL, *to=NULL;
    struct md_fs_location before, after;
    if (!r) r=anchor(fs,root_fd,old,&from);
    if (!r) r=anchor(fs,next->fd,next->mount,&to);
    if (!r) r=md_fs_root_capture(fs,root_fd,from,&before);
    if (!r) r=md_fs_root_capture(fs,next->fd,to,&after);
    int covered=-1;
    if (!r && (covered=fcntl(put.fd,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    int inherited_target=previous->target;
    struct stat inherited_guest=previous->guest;
    struct md_view_mount *inherited_parent=previous->parent;
    struct md_view_mount *pending=NULL;
    if (!r) {
        unsigned count=fs->mounts->tree->count;
        pending=calloc(count ? count : 1,sizeof(*pending));
        if (!pending) r=-ENOMEM;
    }
    if (!r) {
        struct md_fs_mounts *tree=fs->mounts->tree;
        memcpy(pending,tree->mounts,tree->count*sizeof(*pending));
        struct md_view_mount *a=old==&fs->mounts->base ? &base : &pending[old-tree->mounts];
        struct md_view_mount *b=next->mount==&fs->mounts->base ? &base : &pending[next->mount-tree->mounts];
        b->target=inherited_target; b->guest=inherited_guest; b->parent=inherited_parent;
        a->target=covered; a->guest=st; a->parent=put.mount;
        a->root_providers=1;
        if (a->locked) { b->locked=1; a->locked=0; }
        struct md_mount_layout l={.base=&base,.entries=pending,.count=tree->count,
            .root=old==fs->mounts->root ? next->mount : fs->mounts->root};
        r=md_mount_graph_update(fs->mounts,&l);
    }
    free(pending);
    if (!r) {
        /* Both mount placements and all process-location replacements are
         * prepared before publication. Stable mount objects keep old FD
         * provenance; only root/cwd references exactly at the old root move. */
        struct md_view_mount *new=next->mount;
        md_view_mount_lookup_changed(fs->mounts);
        close(new->target); new->target=inherited_target; new->guest=inherited_guest; new->parent=inherited_parent;
        if (initialized) { old->source=base.source; old->native=base.native; }
        old->target=covered; old->guest=st; old->parent=put.mount;
        old->root_providers=1;
        if (old->locked) { new->locked=1; old->locked=0; }
        if (fs->mounts->root==old) fs->mounts->root=new;
        md_domain_relocate_roots(fs->mounts,&before,&after,from,to);
        md_domain_relocate_root(context,&before,&after,from,to);
    } else {
        if (covered>=0) close(covered);
        if (initialized) {
            close(base.source);
            if (inherited_target>=0) close(inherited_target);
        }
    }
    close(root_fd);
    md_fs_anchor_release(from); md_fs_anchor_release(to);
    md_view_location_close(&put); return r;
}
static int mount_change(struct md_filesystem *fs, struct md_guest_domain *context,
        const struct md_fs_request *q,const struct md_view_location *resolved) {
    if (!fs->mounts) return -ENOTSUP;
    if (!fs->store->identity || !md_identity_capable_in(fs->store->identity,
            md_mount_namespace_owner(fs->mounts),CAP_SYS_ADMIN)) return -EPERM;
    struct mount_attr attr={0};
    if (q->mode==MD_FS_MOUNT_SETATTR) {
        memcpy(&attr,q->value,sizeof(attr));
        if (!attr.attr_set && !attr.attr_clr && !attr.propagation) return 0;
        int error=attribute_check(&attr);
        if (error) return error;
    }
    /* Release unused detached storage before a filesystem reserves shared
     * resources such as overlay upper/work directories. Live origins retain
     * their mount pins; collection never infers closure from a pathname. */
    int r=q->mode==MD_FS_MOUNT_OVERLAY ? md_view_mount_collect(fs)
        : collect_admission(fs);
    struct md_view_location target={.fd=-1,.parent=-1};
    if (!r) r=operand(fs,q,0,&target,resolved);
    if (!r && target.view!=fs->mounts) r=-EINVAL;
    if (!r) switch (q->mode) {
    case MD_FS_MOUNT_BIND:
        r=bind(fs,q,&target,NULL,resolved); break;
    case MD_FS_MOUNT_UMOUNT:
        r=unmount(fs,q,&target); break;
    case MD_FS_MOUNT_MOVE: case MD_FS_MOUNT_MOVE_FD:
        r=move(fs,q,&target,resolved); break;
    case MD_FS_MOUNT_PIVOT:
        r=pivot(fs,context,q,&target,resolved); break;
    case MD_FS_MOUNT_SETATTR:
        r=attributes(fs,q,&target,&attr); break;
    case MD_FS_MOUNT_REMOUNT:
        if (q->flags&~(MS_REMOUNT|MS_BIND|MD_FS_MOUNT_FLAGS)) r=-ENOTSUP;
        else {
            struct md_view_mount *mount=target.mount;
            r=md_view_require_root(fs,&target);
            if (!r) r=policy_check(fs,mount,md_fs_mount_flags(q->flags));
            unsigned long type=mount->provider ? md_provider_tree_filesystem(mount->provider) : 0;
            if (!r && !(q->flags&MS_BIND) && (type==SYSFS_MAGIC || type==PROC_SUPER_MAGIC || type==BINFMTFS_MAGIC
                    || mount->devpts
                    || md_view_inode(mount)))
                r=md_fs_super_reconfigure(fs,mount,!!(q->flags&MS_RDONLY),q->value_size ? q->value : NULL);
            else if (!r && q->value_size && *(const char *)q->value) r=-EOPNOTSUPP;
            if (!r) r=md_mount_policy_set(&mount->policy,md_fs_mount_flags(q->flags));
        }
        break;
    case MD_FS_MOUNT_PROPAGATION: {
        unsigned kind=q->flags&~MS_REC;
        if (!kind || (kind&(kind-1)) || (kind&~(MS_PRIVATE|MS_SLAVE|MS_SHARED|MS_UNBINDABLE))) r=-EINVAL;
        else if ((r=md_view_require_root(fs,&target))) break;
        else r=md_view_mount_propagation(fs,target.mount,kind,!!(q->flags&MS_REC));
        break;
    }
    case MD_FS_MOUNT_TMPFS: case MD_FS_MOUNT_RAMFS: case MD_FS_MOUNT_OVERLAY: case MD_FS_MOUNT_FUSE: case MD_FS_MOUNT_BINFMT:
    case MD_FS_MOUNT_PROC: case MD_FS_MOUNT_DEVPTS: case MD_FS_MOUNT_CGROUP: case MD_FS_MOUNT_CGROUP2: case MD_FS_MOUNT_SYSFS: case MD_FS_MOUNT_MQUEUE: {
        struct stat st;
        r=fstat(target.fd,&st) ? -errno : !S_ISDIR(st.st_mode) ? -ENOTDIR : 0;
        struct md_mount_source source={.fd=-1};
        if (!r) r=md_mount_source_create(fs,q,&source);
        if (!r) r=md_view_attach_source(fs,&source,&target,NULL);
        md_mount_source_close(&source); break;
    }
    default: r=-ENOTSUP; break;
    }
    if (!r) atomic_store_explicit(&fs->mounts->managed_paths,1,memory_order_release);
    md_view_location_close(&target);
    /* Retire an unused superblock at unmount, not at the next unrelated mount.
     * Providers may have a daemon blocked waiting for this lifetime to end. */
    if (!r && q->mode==MD_FS_MOUNT_UMOUNT) md_mount_family_retire(fs->mounts);
    return r;
}
int md_fs_mount_change(struct md_filesystem *fs,struct md_guest_domain *context,const struct md_fs_request *q) {
    return mount_change(fs,context,q,NULL);
}

struct mount_operation {
    struct md_fs_continuation base;
    struct md_view_walk_pending *walk;
    struct md_view_location locations[2];
    struct md_fs_anchor *pins[2];
    unsigned index;
};
static int mount_ready(struct md_fs_continuation *base) {
    return md_view_walk_ready(((struct mount_operation *)base)->walk);
}
static void mount_clear(struct mount_operation *p) {
    md_view_walk_destroy(p->walk);
    for (unsigned i=0;i<2;++i) { md_view_location_close(&p->locations[i]); md_fs_anchor_release(p->pins[i]); }
}
static void mount_destroy(struct md_fs_continuation *base) {
    mount_clear((void *)base); md_fs_continuation_clear(base); free(base);
}
static void mount_execute(struct md_filesystem *,const struct md_fs_request *,
    struct md_fs_result *,const struct md_fs_output *,struct mount_operation *);
static void mount_resume(struct md_fs_continuation *base,struct md_filesystem *fs,
        struct md_fs_result *out,const struct md_fs_output *output) {
    mount_execute(fs,&base->request,out,output,(void *)base);
}
static void mount_execute(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output,struct mount_operation *pending) {
    *out=(struct md_fs_result){.fd=-1};
    if (!fs->mounts || !fs->store->identity || !md_identity_capable_in(fs->store->identity,
            md_mount_namespace_owner(fs->mounts),CAP_SYS_ADMIN)) { out->error=-EPERM; return; }
    if (q->operation==MD_FS_MOUNT && q->mode==MD_FS_MOUNT_SETATTR) {
        struct mount_attr attr; memcpy(&attr,q->value,sizeof(attr));
        if (!attr.attr_set && !attr.attr_clr && !attr.propagation) return;
        out->error=attribute_check(&attr);
        if (out->error) return;
    }
    struct mount_operation local={.locations={{.fd=-1,.parent=-1},{.fd=-1,.parent=-1}}};
    struct mount_operation *p=pending ? pending : &local;
    int clone=q->operation==MD_FS_OPEN_TREE;
    unsigned count=!clone && (q->mode==MD_FS_MOUNT_BIND || q->mode==MD_FS_MOUNT_MOVE
        || q->mode==MD_FS_MOUNT_MOVE_FD || q->mode==MD_FS_MOUNT_PIVOT) ? 2 : 1;
    for (;p->index<count;++p->index) {
        unsigned i=p->index;
        /* Typed bind endpoints and inherited native FDs have a separate,
         * synchronous authority path in bind(). Never reinterpret carriers. */
        if ((clone || (i==1 && q->mode==MD_FS_MOUNT_BIND)) && (q->control.ops
                || (!*q->path[i] && (!q->origin[i] || !q->origin[i]->view)))) continue;
        out->error=walk_operand(fs,q,i,&p->locations[i],output && output->continuation ? &p->walk : NULL);
        if (out->error==-EINPROGRESS) {
            for (unsigned n=0;n<i;++n) if (!p->pins[n] && p->locations[n].fd>=0) {
                out->error=md_view_anchor_duplicate(fs,p->locations[n].view,p->locations[n].mount,
                    p->locations[n].fd,&p->pins[n]);
                if (out->error) break;
            }
            if (out->error && out->error!=-EINPROGRESS) break;
            if (!pending) {
                pending=calloc(1,sizeof(*pending));
                out->error=pending ? md_fs_continuation_capture(&pending->base,q) : -ENOMEM;
                if (out->error) { free(pending); break; }
                pending->base.ready=mount_ready; pending->base.resume=mount_resume; pending->base.destroy=mount_destroy;
                pending->walk=p->walk; pending->index=p->index;
                memcpy(pending->locations,p->locations,sizeof(p->locations));
                memcpy(pending->pins,p->pins,sizeof(p->pins));
            }
            out->error=-EINPROGRESS; *output->continuation=&pending->base; return;
        }
        if (out->error) break;
    }
    if (!out->error) {
        /* Pin inputs through collection/publication. Unmount has one operand
         * and must not count its own selection as an open-description lease. */
        if (clone || q->mode!=MD_FS_MOUNT_UMOUNT) for (unsigned i=0;i<count;++i) {
            if (!p->pins[i] && p->locations[i].fd>=0) out->error=md_view_anchor_duplicate(fs,
                p->locations[i].view,p->locations[i].mount,p->locations[i].fd,&p->pins[i]);
            if (out->error) break;
        }
    }
    if (!out->error) {
        if (clone) open_tree(fs,q,out,output,p->locations);
        else out->error=mount_change(fs,fs->actor ? fs->actor->fs : NULL,q,p->locations);
    }
    if (p==&local) mount_clear(p);
}
void md_fs_mount_execute(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    mount_execute(fs,q,out,output,NULL);
}
void md_fs_open_tree(struct md_filesystem *fs,const struct md_fs_request *q,
        struct md_fs_result *out,const struct md_fs_output *output) {
    mount_execute(fs,q,out,output,NULL);
}
