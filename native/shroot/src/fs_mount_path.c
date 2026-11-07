#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include "fs_storage.h"
#include "procfs_view.h"
#include "file_permissions.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

void md_view_location_close(struct md_view_location *p) {
    if (p->parent >= 0) close(p->parent);
    if (p->fd >= 0) close(p->fd);
    p->parent = p->fd = -1;
}
static int duplicate(int fd) { int copy = fcntl(fd, F_DUPFD_CLOEXEC, 3); return copy < 0 ? -errno : copy; }
int md_view_descriptor(struct md_filesystem *fs, int fd, const struct md_fs_anchor *origin,
        struct md_view_location *out) {
    *out=(struct md_view_location){.parent=-1,.fd=-1};
    if (origin && origin->child) return -EOPNOTSUPP;
    int r=origin ? md_view_anchor_check(fs,fd,origin) : 0;
    if (r) return r;
    struct md_mount_namespace *view=origin ? origin->view : fs->mounts;
    struct md_view_mount *mount=origin ? origin->mount : view ? &view->base : NULL;
    if (!origin) {
        struct md_view_object *object=NULL;
        r=md_view_identity(fs,fd,&object);
        if (!r) mount=object->mount;
        else if (r==-EXDEV) {
            char id[33];
            r=md_inode_object_id(fs->store,fd,id);
        }
    }
    if (r) return r;
    out->fd=duplicate(fd);
    if (out->fd<0) { r=out->fd; out->fd=-1; return r; }
    out->view=view; out->mount=mount;
    return 0;
}
int md_view_task_path(struct md_filesystem *fs, int fd, const char *name, const char *tail,
        uint64_t resolve, int native_devices) {
    struct md_inode_boundary *boundary=fs->store->boundary;
    if (!boundary || !boundary->path) return 0;
    char path[PATH_MAX];
    int r=md_view_fdpath(fd,path,sizeof(path));
    if (r) return r;
    int proc=!strncmp(path,"/proc",5) && (!path[5] || path[5]=='/');
    int device=native_devices && !strncmp(path,"/dev",4) && (!path[4] || path[4]=='/');
    if (!proc && !device) return 0;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    if (!S_ISDIR(st.st_mode)) return -ENOTDIR;
    if (resolve&(RESOLVE_BENEATH|RESOLVE_IN_ROOT|RESOLVE_NO_XDEV)) return -EXDEV;
    if (resolve&(RESOLVE_NO_SYMLINKS|RESOLVE_NO_MAGICLINKS)) return -ENOTSUP;
    int n=snprintf(boundary->path,boundary->capacity,"%s/%s%s",path,name,tail);
    return n<0 || (size_t)n>=boundary->capacity ? -ENAMETOOLONG : -EREMOTE;
}
/* Points retain logical identity within one provider snapshot. Only boundary
 * crossing and result publication materialize descriptors. No snapshot or
 * storage lock survives the walk, client copying, or entry to another store. */
struct path_point {
    int fd, logical, metadata_captured;
    struct stat identity;
    struct mdi_node node;
};
struct path_walk {
    struct md_filesystem *fs;
    struct md_fs_storage_scope scope;
    struct md_mount_namespace *view;
    struct md_view_mount *mount;
    uint64_t resolve;
    struct md_provider_lookup **pending;
};
struct md_view_walk_pending {
    struct md_provider_lookup *lookup;
    struct path_point current;
    struct md_mount_namespace *view,*boundary_view;
    struct md_view_mount *mount,*next_mount,*boundary_mount;
    struct md_fs_anchor *pins[3];
    struct stat boundary;
    int boundary_fd,follow,missing,trailing,last;
    uint64_t resolve;
    unsigned links;
    char todo[PATH_MAX],name[NAME_MAX+1];
};
void md_view_walk_destroy(struct md_view_walk_pending *p) {
    if (!p) return;
    if (p->lookup) p->lookup->destroy(p->lookup);
    if (p->current.fd>=0) close(p->current.fd);
    if (p->boundary_fd>=0) close(p->boundary_fd);
    for (unsigned i=0;i<3;++i) md_fs_anchor_release(p->pins[i]);
    free(p);
}
int md_view_walk_ready(struct md_view_walk_pending *p) {
    return p && p->lookup ? p->lookup->ready(p->lookup) : -EINVAL;
}
struct md_fs_task_input *md_view_walk_task_input(struct md_view_walk_pending *p) {
    return p && p->lookup ? p->lookup->task_input : NULL;
}
static void point_close(struct path_point *p) {
    if (p->fd>=0) close(p->fd);
    *p=(struct path_point){.fd=-1};
}
static int point_native(struct path_point *p,int fd) {
    *p=(struct path_point){.fd=fd};
    if (fd<0) return fd;
    return fstat(fd,&p->identity) ? -errno : 0;
}
static int walk_leave(struct path_walk *w,int result) {
    if (w->scope.store) {
        result=mdi_finish(w->scope.store,result);
        md_fs_storage_leave(&w->scope);
    }
    return result;
}
static int walk_select(struct path_walk *w,struct md_mount_namespace *view,
        struct md_view_mount *mount) {
    if (w->scope.store && w->view==view && w->mount==mount) return 0;
    int r=walk_leave(w,0);
    if (r || !md_view_inode(mount)) return r;
    r=md_fs_storage_enter(w->fs,mount,&w->scope);
    if (r) return r;
    if ((w->resolve&MD_VIEW_PROVIDER_SOURCE) && w->scope.store==w->fs->store)
        w->scope.store->boundary=w->scope.boundary;
    if (view!=w->fs->mounts) w->scope.store->root_id=NULL;
    r=mdi_begin(w->scope.store,0);
    if (r) md_fs_storage_leave(&w->scope);
    else { w->view=view; w->mount=mount; }
    return r;
}
static int point_logical(struct path_walk *w,struct path_point *p) {
    if (p->logical) return 0;
    int r=mdi_fd(w->scope.store,p->fd,&p->node);
    if (!r) p->logical=1;
    return r;
}
static int point_fd(struct path_walk *w,struct path_point *p) {
    if (p->fd>=0) return 0;
    struct md_inode_store *s=w->scope.store;
    if (!s || !p->logical) return -EIO;
    int fd=mdi_backing_open(s,&p->node,O_PATH);
    if (fd<0) return fd;
    struct stat st;
    int r=fstat(fd,&st) ? -errno : !md_view_same(&st,&p->identity) ? -EIO : 0;
    if (r) { close(fd); return r; }
    if (s->statistics) ++s->statistics->walk_materializations;
    p->fd=fd; return 0;
}
static int observe(struct path_walk *w,struct md_mount_namespace *view,struct md_view_mount *mount,
        struct path_point *point,struct md_view_observer *observer,int *error) {
    if (!observer || !md_view_inode(mount)) return 0;
    *error=walk_select(w,view,mount);
    if (!*error) *error=point_logical(w,point);
    if (!*error) *error=observer->complete(observer->context,w->scope.store,mount,&point->node,&point->identity);
    observer->completed=1; return 1;
}
static int component(struct path_walk *w,struct md_mount_namespace *view,
        struct md_view_mount *mount,struct path_point *current,const char *name,
        const char *tail,int covered,struct path_point *next) {
    int r=walk_select(w,view,mount);
    if (r) return r;
    if (md_view_inode(mount)) {
        struct md_inode_store *s=w->scope.store;
        r=point_logical(w,current);
        struct md_inode_boundary *boundary=s->boundary;
        if (covered) s->boundary=NULL;
        if (!r) r=mdi_path_step(s,&current->node,name,tail,0,&next->node,&next->identity,NULL);
        s->boundary=boundary;
        if (!r) {
            /* Lookup already read the attributes, including native leaves.
             * Placement needs backing identity, not projected guest dev/ino. */
            next->identity.st_dev=next->node.device;
            next->identity.st_ino=next->node.inode;
            next->logical=1;
            if (s->statistics) ++s->statistics->walk_components;
        }
        return r;
    }
    if (mount && mount->provider) {
        if (mount->sysfs && (r=md_sysfs_refresh(mount->sysfs))) return r;
        const struct md_control_actor actor={.identity=w->fs->store->identity,.wakeup=w->fs->wakeup,
            .pid=w->fs->actor ? w->fs->actor->tid : 0};
        return point_native(next,md_provider_tree_lookup(mount->provider,current->fd,name,&actor,w->pending));
    }
    if (mount && mount->cgroup) {
        struct stat st;
        r=md_cgroup_stat(mount->cgroup,current->fd,&st);
        if (!r) r=md_file_permissions(&st,X_OK,w->fs->store->identity);
        if (r) return r;
    }
    int fd=openat(current->fd,name,O_PATH|O_NOFOLLOW|O_CLOEXEC);
    return point_native(next,fd<0 ? -errno : fd);
}
static int enter(struct md_mount_namespace *view,
        struct path_point *p,struct md_view_mount **mount,uint64_t resolve) {
    struct md_fs_mounts *tree = view ? view->tree : NULL;
    if (!tree) return 0;
    for (unsigned depth=0;;++depth) {
        struct md_view_mount *m=md_view_mount_lookup(view,*mount,&p->identity);
        if (!m) return 0;
        if (depth>tree->count) return -ELOOP;
        if (resolve & RESOLVE_NO_XDEV) return -EXDEV;
        int copy = duplicate(m->source);
        if (copy < 0) return copy;
        point_close(p); *mount=m;
        int r=point_native(p,copy);
        if (r) return r;
    }
}
int md_view_location_mountpoint(struct md_view_location *location) {
    struct path_point point={.fd=-1};
    int error=point_native(&point,location->fd);
    location->fd=-1;
    if (!error) error=enter(location->view,&point,&location->mount,0);
    location->fd=point.fd;
    return error;
}
static int parent(struct path_walk *w, struct md_mount_namespace *view,
        struct md_view_mount **mount, struct path_point *current, uint64_t resolve,
        struct path_point *next) {
    struct md_filesystem *fs=w->fs;
    struct stat st=current->identity;
    int fd=-1, clamped=0;
    /* A stack can contain several mounted roots at the same dentry. Leave all
     * of them before taking one directory step; never use a source's parent. */
    while (*mount && md_view_same(&st,&(*mount)->native)) {
        if (view && *mount==view->root) { clamped=1; break; }
        if (fs->native_root && view==fs->mounts && *mount==fs->native_root->mount
                && md_view_same(&st,&fs->native_root->identity)) { clamped=1; break; }
        if ((*mount)->detached) { clamped=1; break; }
        if (resolve&RESOLVE_NO_XDEV) return -EXDEV;
        fd=(*mount)->target; st=(*mount)->guest; *mount=(*mount)->parent;
    }
    if (fd<0) {
        int r=point_fd(w,current);
        return r ? r : point_native(next,duplicate(current->fd));
    }
    struct path_point target={.fd=-1};
    int r=point_native(&target,duplicate(fd));
    if (!r && clamped) { *next=target; target.fd=-1; }
    else if (!r) r=component(w,view,*mount,&target,"..","",0,next);
    point_close(&target); return r;
}
static int walk_path(struct md_filesystem *fs, int base, const struct md_fs_anchor *origin,
        const char *path, int follow, int missing,
        uint64_t resolve, struct md_view_location *out,struct md_view_observer *observer,struct md_view_walk_pending **pending,
        struct md_view_walk_pending *resume) {
    *out = (struct md_view_location){.parent=-1,.fd=-1};
    struct md_provider_lookup *lookup=NULL;
    struct path_walk walk={.fs=fs,.resolve=resolve,.pending=pending ? &lookup : NULL};
    struct path_point current={.fd=-1},next={.fd=-1};
    struct md_mount_namespace *view=NULL,*boundary_view=NULL;
    struct md_view_mount *mount=NULL,*next_mount=NULL,*boundary_mount=NULL;
    struct stat boundary={0};
    char todo[PATH_MAX],name[NAME_MAX+1];
    int r=0,boundary_fd=-1,trailing=0,last=0;
    unsigned links=0;
    if (resume) {
        if (!resume->lookup) return -EINVAL;
        int ready=md_view_walk_ready(resume);
        if (!ready) return -EINPROGRESS;
        int completed=resume->lookup->complete(resume->lookup);
        if (completed==-EINPROGRESS) return completed;
        current=resume->current; resume->current.fd=-1;
        view=resume->view; mount=resume->mount; next_mount=resume->next_mount;
        boundary_view=resume->boundary_view; boundary_mount=resume->boundary_mount;
        boundary=resume->boundary; boundary_fd=resume->boundary_fd; resume->boundary_fd=-1;
        follow=resume->follow; missing=resume->missing; resolve=resume->resolve;
        walk.resolve=resolve; links=resume->links; trailing=resume->trailing; last=resume->last;
        strcpy(todo,resume->todo); strcpy(name,resume->name);
        if (completed>=0 && resume->lookup->metadata) {
            next=(struct path_point){.fd=completed,.identity=*resume->lookup->metadata,
                .metadata_captured=1}; r=0;
        } else r=point_native(&next,completed);
        resume->lookup->destroy(resume->lookup); resume->lookup=NULL;
        goto component_complete;
    }
    if (!path || !*path) return -ENOENT;
    if (resolve & ~(RESOLVE_BENEATH | RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_XDEV | RESOLVE_CACHED | MD_VIEW_TASK_PATH | MD_VIEW_PROVIDER_SOURCE | MD_VIEW_MOUNT_TARGET)) return -EINVAL;
    if ((resolve & (RESOLVE_BENEATH | RESOLVE_IN_ROOT)) == (RESOLVE_BENEATH | RESOLVE_IN_ROOT)) return -EINVAL;
    if (resolve & RESOLVE_CACHED) return -EAGAIN;
    if (path[0] == '/' && (resolve & RESOLVE_BENEATH)) return -EXDEV;
    if (strlen(path) >= PATH_MAX) return -ENAMETOOLONG;
    strcpy(todo, path);
    int from_root = base == MD_INODE_ROOT || (path[0] == '/' && !(resolve & RESOLVE_IN_ROOT));
    /* A native proc descriptor predates or bypasses logical mounts. Its kernel
     * inode cannot select one of several proc mounts with different PID views.
     * Logical opens retain an origin; only those enter a mounted proc view. */
    if (!from_root && !origin && (resolve&MD_VIEW_TASK_PATH)) {
        /* No walk has occurred: caller completion retains the original scope. */
        int native=md_view_task_path(fs,base,path,"",0,0);
        if (native) return native;
    }
    r = !from_root && origin ? md_view_anchor_check(fs,base,origin) : 0;
    if (r) return r;
    if (!from_root && origin && origin->child) return -ENOTDIR;
    r=point_native(&current,from_root ? md_view_root_fd(fs) : duplicate(base));
    if ((r==-EACCES || r==-EPERM || r==-ENOENT) && current.fd>=0 && !from_root
            && origin && origin->mount && origin->mount->backing==MD_MOUNT_PROC && origin->kind) {
        /* The pinned descriptor already proves identity and inode type. Proc
         * still owns access and attributes; this is not a metadata snapshot. */
        current.identity=(struct stat){.st_dev=origin->device,.st_ino=origin->inode,.st_mode=origin->kind};
        r=0;
    }
    if (r) { point_close(&current); return r; }
    view = !from_root && origin ? origin->view : fs->mounts;
    mount = view ? &view->base : NULL;
    if (from_root) mount=md_view_root_mount(fs);
    else if (origin) mount=origin->mount;
    else {
        struct md_view_object *object = NULL;
        r = md_view_identity(fs, current.fd, &object);
        if (!r) mount=object->mount;
        if (r == -EXDEV) r = 0;
    }
    if (!r && from_root && !fs->native_root) r = enter(view, &current, &mount, resolve);
    int scoped=resolve&(RESOLVE_BENEATH|RESOLVE_IN_ROOT);
    boundary_fd = !r && scoped ? duplicate(current.fd) : -1;
    boundary_mount = mount;
    boundary_view = view;
    if (!r && scoped && boundary_fd < 0) r = boundary_fd;
    boundary=current.identity;
    while (!r) {
        r=walk_select(&walk,view,mount);
        if (r) break;
        if (!S_ISDIR(current.identity.st_mode)) { r = -ENOTDIR; break; }
        char *start = todo; while (*start == '/') ++start;
        if (!*start) {
            if (observe(&walk,view,mount,&current,observer,&r)) break;
            r=point_fd(&walk,&current);
            if (!r) {
                out->parent=duplicate(current.fd); out->fd=current.fd; current.fd=-1;
                out->view=view; out->mount=mount; strcpy(out->name,".");
                out->captured_metadata=current.identity; out->metadata_captured=current.metadata_captured;
                r=out->parent<0 ? out->parent : 0;
            }
            break;
        }
        char *end = start; while (*end && *end != '/') ++end;
        size_t length = (size_t)(end-start);
        if (length > NAME_MAX) { r = -ENAMETOOLONG; break; }
        memcpy(name, start, length); name[length] = 0;
        trailing = *end == '/';
        memmove(todo, end, strlen(end)+1);
        char *rest = todo; while (*rest == '/') ++rest;
        last = !*rest;
        next=(struct path_point){.fd=-1};
        next_mount = mount;
        if (!strcmp(name, "..") && (resolve & (RESOLVE_BENEATH | RESOLVE_IN_ROOT)) && view == boundary_view && mount == boundary_mount && md_view_same(&current.identity, &boundary)) {
            if (resolve & RESOLVE_BENEATH) { r = -EXDEV; break; }
            r=component(&walk,view,mount,&current,".",todo,0,&next);
        } else if (!strcmp(name,"..") && fs->native_root && view==fs->mounts && mount==fs->native_root->mount
                && md_view_same(&current.identity,&fs->native_root->identity)) {
            r=component(&walk,view,mount,&current,".",todo,0,&next);
        } else if (!strcmp(name, "..") && mount && md_view_same(&current.identity, &mount->native)) {
            /* The result may still need its old parent after this step. */
            r=point_fd(&walk,&current);
            if (!r) r = parent(&walk,view,&next_mount,&current,resolve,&next);
        } else if (md_view_inode(mount)) {
            struct md_inode_boundary *boundary=walk.scope.store->boundary;
            r = component(&walk,view,mount,&current,name,todo,0,&next);
            int placement=last && (resolve&MD_VIEW_MOUNT_TARGET);
            int devices=last && (resolve&MD_VIEW_PROVIDER_SOURCE) && boundary && boundary->native_directory;
            if (r==-EREMOTE && view && (view->tree || placement || devices)) {
                /* An explicit mount covers the initial filesystem's implicit
                 * proc/dev provider. Resolve only its covered dentry here;
                 * an ordinary root bind must retain the provider fallback. */
                struct path_point covered={.fd=-1};
                int selected=component(&walk,view,mount,&current,name,todo,1,&covered);
                if (!selected) {
                    selected=enter(view,&covered,&next_mount,resolve);
                    if (selected) r=selected;
                    else if (next_mount!=mount || placement || devices) {
                        out->implicit_devices=devices && next_mount==mount && !placement;
                        next=covered; covered.fd=-1; r=0;
                    }
                }
                point_close(&covered);
            }
            /* Entering an admitted native mount root is not an escape or a
             * magic-link traversal. Delegating an unresolved suffix would be;
             * retain the stricter failure until its walker owns that scope. */
            if (r==-EREMOTE && ((resolve&RESOLVE_NO_XDEV)
                    || ((!boundary || (!boundary->mount_root && !boundary->native_directory))
                        && (resolve&(RESOLVE_IN_ROOT|RESOLVE_BENEATH))))) r=-EXDEV;
            if (r==-EREMOTE && md_view_nosymfollow(mount)) r=-EOPNOTSUPP;
        }
        else {
            if (mount && mount->backing==MD_MOUNT_PROC) {
                next.fd=md_procfs_step(fs,view,mount,current.fd,&current.identity,name,todo,
                    follow>0 || (trailing && follow==0 && todo[strlen(todo)-1]=='/'),resolve,&next.identity,pending ? &lookup : NULL);
                r=next.fd<0 ? next.fd : 0;
            }
            else if (mount && mount->backing==MD_MOUNT_DEVPTS)
                r=point_native(&next,md_devpts_step(mount->devpts,current.fd,name));
            else {
                r=(resolve&MD_VIEW_TASK_PATH) ? md_view_task_path(fs,current.fd,name,todo,resolve,0) : 0;
                if (r==-EREMOTE && md_view_nosymfollow(mount)) r=-EOPNOTSUPP;
                if (!r) r=component(&walk,view,mount,&current,name,todo,0,&next);
            }
        }
        if (r==-EINPROGRESS && lookup && pending) {
            r=walk_leave(&walk,0);
            struct md_fs_anchor *pins[3]={0};
            if (!r) r=md_view_anchor_pin(fs,view,mount,current.fd,&current.identity,&pins[0]);
            if (!r && boundary_fd>=0)
                r=md_view_anchor_duplicate(fs,boundary_view,boundary_mount,boundary_fd,&pins[1]);
            if (!r && next_mount!=mount && next_mount && next_mount->source>=0)
                r=md_view_anchor_duplicate(fs,view,next_mount,next_mount->source,&pins[2]);
            struct md_view_walk_pending *p=resume;
            if (!r && !p) { p=calloc(1,sizeof(*p)); if (!p) r=-ENOMEM; }
            if (!r) {
                for (unsigned i=0;i<3;++i) { md_fs_anchor_release(p->pins[i]); p->pins[i]=pins[i]; }
                p->lookup=lookup; p->current=current; p->boundary_fd=boundary_fd;
                p->view=view; p->mount=mount; p->next_mount=next_mount;
                p->boundary_view=boundary_view; p->boundary_mount=boundary_mount; p->boundary=boundary;
                p->follow=follow; p->missing=missing; p->resolve=resolve; p->links=links;
                p->trailing=trailing; p->last=last; strcpy(p->todo,todo); strcpy(p->name,name);
                *pending=p; return -EINPROGRESS;
            }
            for (unsigned i=0;i<3;++i) md_fs_anchor_release(pins[i]);
            lookup->destroy(lookup); lookup=NULL;
        }
component_complete:
        if (r == -ENOENT && last && missing && (!trailing || missing > 1)) {
            r=point_fd(&walk,&current);
            if (!r) { out->parent=current.fd; current.fd=-1; out->view=view; out->mount=mount; strcpy(out->name,name); }
            point_close(&next); break;
        }
        if (r) { point_close(&next); break; }
        if (strcmp(name,".")) r=enter(view,&next,&next_mount,resolve);
        if (r) { point_close(&next); break; }
        if (S_ISLNK(next.identity.st_mode) && (!last || follow > 0 || (trailing && follow == 0))) {
            if (++links > 40 || (resolve & RESOLVE_NO_SYMLINKS) || md_view_nosymfollow(next_mount)) {
                point_close(&next); r=-ELOOP; break;
            }
            char target[PATH_MAX];
            ssize_t n;
            if (next.logical) {
                int directory=mdi_backing_directory(walk.scope.store,&next.node);
                if (directory<0) { point_close(&next); r=directory; break; }
                n=readlinkat(directory,next.node.backing,target,sizeof(target));
                if (n<0) n=-errno;
            } else if (next_mount->backing==MD_MOUNT_PROC)
                n=md_procfs_readlink(fs,next_mount,next.fd,target,sizeof(target));
            else {
                n=readlinkat(next.fd,"",target,sizeof(target));
                if (n<0) n=-errno;
            }
            point_close(&next);
            if (n < 0) { r = (int)n; break; }
            size_t rest_size = strlen(todo);
            if (!n) { r = -ENOENT; break; }
            if ((size_t)n + rest_size >= sizeof(target)) { r = -ENAMETOOLONG; break; }
            memcpy(target+n, todo, rest_size+1); strcpy(todo, target);
            if (target[0] == '/') {
                if (resolve & RESOLVE_BENEATH) { r = -EXDEV; break; }
                struct md_mount_namespace *root_view = resolve & RESOLVE_IN_ROOT ? boundary_view : fs->mounts;
                struct md_view_mount *root_mount = resolve & RESOLVE_IN_ROOT ? boundary_mount
                    : md_view_root_mount(fs);
                if ((resolve & RESOLVE_NO_XDEV) && (view!=root_view || mount!=root_mount)) { r=-EXDEV; break; }
                r=walk_leave(&walk,0);
                if (r) break;
                point_close(&current);
                r=point_native(&current,resolve & RESOLVE_IN_ROOT ? duplicate(boundary_fd) : md_view_root_fd(fs));
                view=root_view; mount=root_mount;
                if (!r && !(resolve&RESOLVE_IN_ROOT) && !fs->native_root) r=enter(view,&current,&mount,resolve);
            }
            continue;
        }
        if (!r && (!last || trailing) && !S_ISDIR(next.identity.st_mode)) r = -ENOTDIR;
        if (r) { point_close(&next); break; }
        if (last) {
            if (observe(&walk,view,next_mount,&next,observer,&r)) { point_close(&next); break; }
            out->boundary = next_mount != mount;
            /* A mount root is materialized by its source FD, never its covered dentry. */
            if (out->boundary || !strcmp(name, "..")) {
                r=point_fd(&walk,&next);
                if (!r) { point_close(&current); r=point_native(&current,duplicate(next.fd)); strcpy(name,"."); }
            }
            if (!r) r=point_fd(&walk,&current);
            if (!r) r=point_fd(&walk,&next);
            if (!r) {
                out->parent=current.fd; current.fd=-1; out->fd=next.fd; next.fd=-1;
                out->view=view; out->mount=next_mount; strcpy(out->name,name);
                out->captured_metadata=next.identity; out->metadata_captured=next.metadata_captured;
            }
            point_close(&next); break;
        }
        point_close(&current); current=next; mount=next_mount;
    }
    r=walk_leave(&walk,r);
    point_close(&current);
    if (boundary_fd >= 0) close(boundary_fd);
    if (r) md_view_location_close(out);
    return r;
}
int md_view_walk(struct md_filesystem *fs,int base,const struct md_fs_anchor *origin,
        const char *path,int follow,int missing,uint64_t resolve,struct md_view_location *out) {
    return walk_path(fs,base,origin,path,follow,missing,resolve,out,NULL,NULL,NULL);
}
int md_view_walk_async(struct md_filesystem *fs,int base,const struct md_fs_anchor *origin,
        const char *path,int follow,int missing,uint64_t resolve,struct md_view_location *out,struct md_view_observer *observer,
        struct md_view_walk_pending **pending) {
    if (pending) *pending=NULL;
    return walk_path(fs,base,origin,path,follow,missing,resolve,out,observer,pending,NULL);
}
int md_view_walk_resume(struct md_filesystem *fs,struct md_view_walk_pending *p,struct md_view_location *out,struct md_view_observer *observer) {
    return walk_path(fs,-1,NULL,NULL,0,0,0,out,observer,&p,p);
}
