#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "inode_lifetime.h"
#include "inode_fifo.h"
#include "mount_graph.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void destroy(struct md_fd_object *object) {
    struct md_fs_anchor *p=(void *)object;
    close(p->fd);
    if (p->mount_pin>=0) close(p->mount_pin);
    md_inode_fifo_release(p->fifo_custody);
    if (p->mount && atomic_fetch_sub_explicit(&p->mount->anchors,1,memory_order_acq_rel)==1)
        md_mount_family_retire(p->view);
    md_mount_namespace_release(p->view);
    md_inode_lifetime_release(p->lifetime); free(p->child); free(p);
}
static const struct md_fd_object_type type={.destroy=destroy};
struct md_fd_object *md_fs_anchor_object(struct md_fs_anchor *p) { return p ? &p->object : NULL; }
struct md_fs_anchor *md_fs_anchor_from_object(struct md_fd_object *p) {
    return !p ? NULL : p->type==&type ? (void *)p : p->type->origin ? p->type->origin(p) : NULL;
}
struct md_fs_anchor *md_fs_anchor_retain(struct md_fs_anchor *p) {
    md_fd_object_retain(md_fs_anchor_object(p)); return p;
}
void md_fs_anchor_release(struct md_fs_anchor *p) { md_fd_object_release(md_fs_anchor_object(p)); }
int md_fs_anchor_fd(const struct md_fs_anchor *p) { return p ? p->fd : -1; }
unsigned md_fs_anchor_kind(const struct md_fs_anchor *p) { return p ? p->kind : 0; }
int md_fs_anchor_virtual(const struct md_fs_anchor *p) { return p && p->child; }
int md_fs_anchor_native_leaf(const struct md_fs_anchor *p,const struct md_inode_store *s,int fd) {
    return p && p->native_leaf && fd==p->fd && md_inode_lifetime_same(p->lifetime,md_inode_store_lifetime(s));
}
unsigned md_fs_anchor_policy(const struct md_fs_anchor *p) {
    if (!p || !p->view) return 0;
    const struct md_view_mount *mount=p->mount ? p->mount : &p->view->base;
    return md_view_policy_flags(mount);
}
struct md_cgroup_view *md_fs_anchor_cgroup(const struct md_fs_anchor *p) {
    return p && p->mount ? p->mount->cgroup : NULL;
}
void md_fs_anchor_mount_info(const struct md_fs_anchor *p,struct md_fs_info *info) {
    if (!p || !p->mount) return;
    md_fs_info_mount(info,p->mount->id,
        !p->child && p->device==p->mount->native.st_dev && p->inode==p->mount->native.st_ino);
}
int md_fs_anchor_same(const struct md_fs_anchor *a, const struct md_fs_anchor *b) {
    return a && b && a->view==b->view && a->mount==b->mount
        && a->device==b->device && a->inode==b->inode
        && a->dentry==b->dentry && a->native_leaf==b->native_leaf
        && (a->child && b->child ? !strcmp(a->child,b->child) : a->child==b->child)
        && md_inode_lifetime_same(a->lifetime,b->lifetime);
}
int md_fs_anchor_check_fd(const struct md_fs_anchor *p, int fd) {
    if (!p || fd<0) return -EINVAL;
    /* The retained anchor owns this descriptor until its final release. Only
     * a separately supplied descriptor needs a kernel identity check. */
    if (fd==p->fd) return 0;
    if (p->child) return -ESTALE;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    return (st.st_dev==p->device && st.st_ino==p->inode)
        || (p->endpoint.inode && st.st_dev==p->endpoint.device && st.st_ino==p->endpoint.inode)
        ? 0 : -ESTALE;
}
static struct md_fs_anchor *create(struct md_inode_lifetime *lifetime,
        struct md_mount_namespace *view, struct md_view_mount *mount, int fd, int pin, dev_t device, ino_t inode) {
    if (!mount && view) mount=&view->base;
    struct md_fs_anchor *p=calloc(1,sizeof(*p));
    if (!p) return NULL;
    md_fd_object_init(&p->object,&type);
    p->lifetime=md_inode_lifetime_retain(lifetime); p->view=md_mount_namespace_retain(view);
    p->mount=mount; p->fd=fd; p->mount_pin=pin; p->device=device; p->inode=inode;
    if (mount) atomic_fetch_add_explicit(&mount->anchors,1,memory_order_relaxed);
    return p;
}
struct md_fs_anchor *md_view_anchor_new(struct md_inode_lifetime *lifetime,
        struct md_mount_namespace *view, struct md_view_mount *mount, int fd, dev_t device, ino_t inode) {
    if (!mount && view) mount=&view->base;
    int pin=-1;
    if (mount && md_mount_graph_foreign(view)) {
        pin=md_mount_graph_acquire_pin(view,mount);
        if (pin<0) { errno=-pin; return NULL; }
    }
    struct md_fs_anchor *p=create(lifetime,view,mount,fd,pin,device,inode);
    if (!p && pin>=0) { int error=errno; close(pin); errno=error; }
    return p;
}
struct md_fs_anchor *md_view_anchor_import(struct md_inode_lifetime *lifetime,
        struct md_mount_namespace *view, struct md_view_mount *mount, int fd, int pin,
        dev_t device, ino_t inode) {
    int r=md_mount_graph_check_pin(view,pin);
    if (r) { errno=-r; return NULL; }
    return create(lifetime,view,mount,fd,pin,device,inode);
}
int md_view_anchor_check(struct md_filesystem *fs, int fd, const struct md_fs_anchor *p) {
    if (!md_inode_lifetime_same(md_inode_store_lifetime(fs->store),p->lifetime)) return -ESTALE;
    return md_fs_anchor_check_fd(p,fd);
}
int md_view_anchor_duplicate(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,struct md_fs_anchor **out) {
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    return md_view_anchor_pin(fs,view,mount,fd,&st,out);
}
int md_view_anchor_pin(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int fd,const struct stat *st,struct md_fs_anchor **out) {
    int copy=fcntl(fd,F_DUPFD_CLOEXEC,3);
    if (copy<0) return -errno;
    struct md_fs_anchor *origin=md_view_anchor_new(md_inode_store_lifetime(fs->store),
        view,mount,copy,st->st_dev,st->st_ino);
    if (!origin) { int error=-errno; close(copy); return error; }
    origin->kind=st->st_mode&S_IFMT;
    *out=origin; return 0;
}
int md_view_anchor_child(struct md_filesystem *fs,struct md_mount_namespace *view,
        struct md_view_mount *mount,int parent,const struct stat *metadata,const char *name,struct md_fs_anchor **out) {
    if (!name || !*name || strchr(name,'/') || !strcmp(name,".") || !strcmp(name,"..")) return -EINVAL;
    int copy=fcntl(parent,F_DUPFD_CLOEXEC,3);
    if (copy<0) return -errno;
    struct md_fs_anchor *p=md_view_anchor_new(md_inode_store_lifetime(fs->store),view,mount,copy,
        metadata->st_dev,metadata->st_ino);
    if (!p) { int error=-errno; close(copy); return error; }
    p->child=strdup(name); p->kind=S_IFREG;
    if (!p->child) { md_fs_anchor_release(p); return -ENOMEM; }
    *out=p; return 0;
}
void md_view_capture(struct md_filesystem *fs, struct md_mount_namespace *view,
        struct md_view_mount *mount, struct md_fs_result *out, const struct md_fs_output *output) {
    if (out->error || out->fd<0 || !output || !output->origin) return;
    int r=0;
    if (!view && mount) r=-EINVAL;
    int transfer=out->open_completion.kind==MD_OPEN_PLAN;
    struct stat st={0};
    if (transfer) {
        st.st_dev=out->open_completion.backing_device;
        st.st_ino=out->open_completion.backing_inode;
        st.st_mode=out->open_completion.mode;
    } else if (!r && fstat(out->fd,&st)) r=-errno;
    int fd=-1;
    if (!r && transfer) fd=out->fd;
    else if (!r) {
        char proc[64]; snprintf(proc,sizeof(proc),"/proc/self/fd/%d",out->fd);
        fd=open(proc,O_PATH|O_CLOEXEC);
        if (fd<0) r=-errno;
    }
    struct md_fs_anchor *p=r ? NULL : md_view_anchor_new(md_inode_store_lifetime(fs->store),
        view,mount,fd,st.st_dev,st.st_ino);
    if (!r && !p) r=-errno;
    if (r) {
        if (fd>=0 && !transfer) close(fd);
        close(out->fd); out->fd=-1; out->error=r;
        if (out->open_completion.kind==MD_OPEN_PIPE) close(out->open_completion.control);
        out->open_completion=(struct md_open_completion){0};
        return;
    }
    p->kind=st.st_mode&S_IFMT;
    p->native_leaf=out->open_completion.native_leaf;
    p->dentry=out->open_completion.dentry;
    *output->origin=p;
    if (transfer) out->fd=-1;
}
