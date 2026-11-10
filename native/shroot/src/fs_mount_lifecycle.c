#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "fs_storage.h"
#include "mount_graph.h"
#include "mount_topology.h"
#include "inode_internal.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>

static unsigned node_index(const struct md_mount_namespace *view, const struct md_view_mount *mount) {
    return mount==&view->base ? 0 : (unsigned)(mount-view->tree->mounts)+1;
}

static int detach(struct md_filesystem *fs, const unsigned *removed) {
    struct md_mount_namespace *view=fs->mounts;
    struct md_fs_mounts *tree=view->tree;
    struct md_view_mount base=view->base;
    unsigned count=tree ? tree->count : 0;
    struct md_view_mount *next=calloc(count ? count : 1,sizeof(*next));
    if (!next) return -ENOMEM;
    if (count) memcpy(next,tree->mounts,count*sizeof(*next));
    if (removed[0]) { base.detached=1; base.links=(struct md_mount_links){0}; }
    for (unsigned i=0;i<count;++i) if (removed[i+1]) {
        next[i].detached=1; next[i].links=(struct md_mount_links){0};
    }
    struct md_mount_layout layout={.base=&base,.root=view->root,.entries=next,.count=count};
    /* Foreign readers can derive deletion from the same retained store. They
     * cannot publish topology, and never gain authority over the owner graph. */
    int r=md_mount_graph_foreign(view) ? 0 : md_mount_graph_update(view,&layout);
    if (!r) {
        md_view_mount_lookup_changed(view);
        atomic_store_explicit(&view->detached_mounts,1,memory_order_release);
        view->base.detached=base.detached;
        if (removed[0]) md_mount_links_release(&view->base.links);
        for (unsigned i=0;i<count;++i) {
            tree->mounts[i].detached=next[i].detached;
            if (removed[i+1]) md_mount_links_release(&tree->mounts[i].links);
        }
        md_mount_family_retire(view);
    }
    free(next); return r;
}

int md_view_mount_detach(struct md_filesystem *fs, const struct md_view_mount *root) {
    if (md_mount_graph_foreign(fs->mounts)) return -EPERM;
    unsigned count=fs->mounts->tree ? fs->mounts->tree->count : 0;
    unsigned removed[count+1], order[count+1];
    memset(removed,0,sizeof(removed));
    int r=md_view_topology_order(fs->mounts,order);
    if (r) return r;
    for (unsigned n=0;n<=count;++n) {
        unsigned i=order[n];
        const struct md_view_mount *m=md_view_mount_node(fs->mounts,i);
        removed[i]=m==root || (m->parent && removed[node_index(fs->mounts,m->parent)]);
    }
    return detach(fs,removed);
}

static int finish_snapshot(struct md_fs_storage_scope *scope,int result) {
    if (scope->store) {
        result=mdi_finish(scope->store,result);
        md_fs_storage_leave(scope);
    }
    return result;
}
static int target_removed(struct md_filesystem *fs,const struct md_view_mount *m,
        struct md_fs_storage_scope *scope) {
    struct stat st;
    int r=0;
    if (md_view_inode(m->parent)) {
        struct md_inode_lifetime *storage=m->parent->storage ? m->parent->storage : fs->store->lifetime;
        if (scope->store && !md_inode_lifetime_same(storage,scope->store->lifetime))
            r=finish_snapshot(scope,0);
        if (!r && !scope->store) {
            r=md_fs_storage_enter(fs,m->parent,scope);
            if (!r) {
                r=mdi_begin(scope->store,0);
                if (r) md_fs_storage_leave(scope);
            }
        }
        if (!r) r=md_inode_fstat(scope->store,m->target,&st);
    } else {
        r=finish_snapshot(scope,0);
        if (!r && fstat(m->target,&st)) r=-errno;
    }
    /* proc dentries can lose their task before their retained target FD is
     * released. ENOENT and an unlinked inode both retire that placement. */
    return r ? (r==-ENOENT ? 1 : r) : !st.st_nlink;
}
int md_view_mount_reconcile_branch(struct md_filesystem *fs,struct md_view_mount *mount) {
    struct md_mount_namespace *view=fs->mounts;
    if (!view || !view->tree || !mount || !mount->parent || mount->detached) return 0;
    struct md_cost *cost=fs->statistics ? &fs->statistics->mount_reconcile : NULL;
    int64_t start=md_cost_begin(cost);
    struct md_fs_storage_scope scope={0};
    struct md_view_mount *missing=NULL;
    int r=0;
    unsigned depth=0;
    for (struct md_view_mount *m=mount;m && m->parent && !r;m=m->parent) {
        if (++depth>view->tree->count+1) { r=-ELOOP; break; }
        if (m->detached) break;
        int removed=target_removed(fs,m,&scope);
        if (removed<0) r=removed;
        else if (removed) missing=m;
    }
    r=finish_snapshot(&scope,r);
    if (!r && missing) {
        /* Publication and allocation are exceptional: a live branch needs
         * neither a topology sort nor an array proportional to the tree. */
        unsigned count=view->tree->count, removed[count+1],order[count+1];
        memset(removed,0,sizeof(removed));
        r=md_view_topology_order(view,order);
        for (unsigned n=0;!r && n<=count;++n) {
            unsigned i=order[n];
            const struct md_view_mount *m=md_view_mount_node(view,i);
            removed[i]=m==missing || (m->parent && removed[node_index(view,m->parent)]);
        }
        if (!r) r=detach(fs,removed);
    }
    md_cost_end(cost,start);
    return r;
}
static int reconcile(struct md_filesystem *fs,const struct md_view_mount *root) {
    struct md_mount_namespace *view=fs->mounts;
    if (!view || !view->tree) return 0;
    struct md_cost *cost=fs->statistics ? &fs->statistics->mount_reconcile : NULL;
    int64_t start=md_cost_begin(cost);
    unsigned removed[view->tree->count+1], order[view->tree->count+1], changed=0;
    unsigned char selected[view->tree->count+1];
    memset(removed,0,sizeof(removed));
    memset(selected,0,sizeof(selected));
    int r=md_view_topology_order(view,order);
    struct md_fs_storage_scope scope={0};
    for (unsigned n=0;!r && n<=view->tree->count;++n) {
        unsigned i=order[n];
        const struct md_view_mount *m=md_view_mount_node(view,i);
        selected[i]=!root || m==root || (m->parent && selected[node_index(view,m->parent)]);
        if (!selected[i]) continue;
        if (!m->id || m->detached || !m->parent) continue;
        if (removed[node_index(view,m->parent)]) removed[i]=1;
        else {
            /* One fresh snapshot per consecutive store run. Never hold
             * a store gate across another provider or topology changes. */
            int missing=target_removed(fs,m,&scope);
            if (missing<0) r=missing;
            else removed[i]=(unsigned)missing;
        }
        changed|=removed[i];
    }
    r=finish_snapshot(&scope,r);
    if (!r && changed) r=detach(fs,removed);
    md_cost_end(cost,start);
    return r;
}
int md_view_mount_reconcile(struct md_filesystem *fs) {
    return reconcile(fs,NULL);
}
int md_view_mount_reconcile_subtree(struct md_filesystem *fs,struct md_view_mount *root) {
    int r=md_view_mount_reconcile_branch(fs,root);
    if (r || (root && (root->detached || !md_view_mount_children(fs->mounts,root)))) return r;
    return reconcile(fs,root);
}
