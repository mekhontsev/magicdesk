#define _GNU_SOURCE
#include "binfmt.h"
#include "fs_mount_internal.h"
#include "inode_internal.h"
#include "linux_abi.h"
#include "mount_topology.h"
#include "fs_storage.h"
#include "mount_source.h"
#include "procfs_view.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <sys/syscall.h>
#include <sys/vfs.h>
#include <linux/magic.h>
#include <unistd.h>

struct md_view_mount *md_view_root_mount(struct md_filesystem *fs) {
    return fs->native_root ? fs->native_root->mount : !fs->mounts ? NULL
        : fs->store->root_id ? &fs->mounts->base : fs->mounts->root;
}
int md_view_root_fd(struct md_filesystem *fs) {
    struct md_view_mount *mount=md_view_root_mount(fs);
    int source=fs->native_root ? fs->native_root->fd
        : !md_view_base(fs->mounts,mount) ? mount->source : -1;
    if (source<0) return md_inode_open(fs->store,MD_INODE_ROOT,"/",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
    int fd=fcntl(source,F_DUPFD_CLOEXEC,3); return fd<0 ? -errno : fd;
}
int md_view_fdpath(int fd, char *out, size_t capacity) {
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(path, out, capacity);
    if (n < 0) return -errno;
    if ((size_t)n >= capacity) return -ENAMETOOLONG;
    out[n] = 0; return 0;
}
static int beneath(const char *path, const char *parent) {
    size_t n = strlen(parent);
    if (n == 1 && parent[0] == '/') return path[0] == '/';
    return !strncmp(path, parent, n) && (!path[n] || path[n] == '/');
}
static unsigned bucket(const struct stat *st) {
    return (unsigned)(st->st_ino ^ (st->st_dev * 2654435761U)) % MD_VIEW_BUCKETS;
}
static unsigned id_bucket(const char *id) {
    unsigned hash = 2166136261U;
    for (unsigned i=0; id[i]; ++i) hash = (hash ^ (unsigned char)id[i]) * 16777619U;
    return hash % MD_VIEW_BUCKETS;
}
struct md_view_object *md_view_object(struct md_filesystem *fs, const char *id) {
    if (!md_mount_namespace_attached(fs->mounts) || !id) return NULL;
    for (struct md_view_object *p=fs->mounts->tree->by_id[id_bucket(id)]; p; p=p->id_next)
        if (!strcmp(p->id,id)) return p;
    return NULL;
}
static struct md_view_object *find(struct md_fs_mounts *m, const struct stat *st, struct md_view_mount *mount) {
    for (struct md_view_object *p = m->table[bucket(st)]; p; p = p->next)
        if (p->mount==mount && md_view_same(&p->identity, st)) return p;
    return NULL;
}
int md_view_record(struct md_filesystem *fs, int fd, struct md_view_mount *mount, struct md_view_object **out) {
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    struct md_fs_mounts *m = fs->mounts->tree;
    struct md_view_object *p = find(m, &st, mount);
    if (p) { *out = p; return 0; }
    if (m->objects == MD_VIEW_OBJECT_LIMIT) return -ENFILE;
    p = calloc(1, sizeof(*p));
    if (!p) return -ENOMEM;
    char proc[64]; snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
    p->fd = open(proc, O_PATH | O_CLOEXEC);
    if (p->fd < 0) { int r = -errno; free(p); return r; }
    if (md_view_inode(mount)) {
        struct md_inode_store *store;
        int r=md_fs_storage_get(fs,mount,&store);
        if (!r) r=md_inode_object_id(store,fd,p->inode);
        if (r) { close(p->fd); free(p); return r; }
    }
    unsigned char bytes[16];
    if (getrandom(bytes, sizeof(bytes), 0) != sizeof(bytes)) {
        int r = errno ? -errno : -EIO; close(p->fd); free(p); return r;
    }
    for (unsigned i = 0; i < sizeof(bytes); ++i) {
        p->id[2*i] = "0123456789abcdef"[bytes[i] >> 4];
        p->id[2*i+1] = "0123456789abcdef"[bytes[i] & 15];
    }
    p->identity = st; p->mount = mount;
    unsigned b = bucket(&st); p->next = m->table[b]; m->table[b] = p; ++m->objects;
    b = id_bucket(p->id); p->id_next = m->by_id[b]; m->by_id[b] = p;
    *out = p; return 0;
}
int md_view_identity(struct md_filesystem *fs, int fd, struct md_view_object **out) {
    *out = NULL;
    if (fd < 0 || !md_mount_namespace_attached(fs->mounts)) return -EXDEV;
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    struct md_fs_mounts *m = fs->mounts->tree;
    struct md_view_object *p = NULL;
    for (struct md_view_object *entry=m->table[bucket(&st)]; entry; entry=entry->next) {
        if (!md_view_same(&entry->identity,&st)) continue;
        /* An unannotated FD cannot choose between identical native objects
         * reached through different mounts, even if their policies agree. */
        if (p) return -ENOTSUP;
        p=entry;
    }
    if (p) { *out = p; return 0; }
    /* Admit inherited/native descriptors only when their current dentry is in
     * an explicitly attached tree. Registered identities survive unlink/rename. */
    char path[PATH_MAX], root[PATH_MAX];
    int r = md_view_fdpath(fd, path, sizeof(path));
    if (r) return r;
    struct md_view_mount *mount=NULL;
    for (unsigned i = 0; i < m->count; ++i) {
        if (m->mounts[i].backing!=MD_MOUNT_NATIVE || m->mounts[i].detached) continue;
        r = md_view_fdpath(m->mounts[i].source, root, sizeof(root));
        if (r) return r;
        if (!beneath(path, root)) continue;
        if (mount) return -ENOTSUP;
        mount=&m->mounts[i];
    }
    return mount ? md_view_record(fs,fd,mount,out) : -EXDEV;
}
int md_view_backing_path(struct md_filesystem *fs, int fd, const struct md_view_mount *mount,
        char *out, size_t size) {
    if (mount && mount->backing==MD_MOUNT_PROC) return md_procfs_guest_path(mount,fd,out,size);
    if (mount && mount->provider) return md_provider_tree_path(mount->provider,fd,out,size);
    if (mount && mount->devpts) {
        char name[32]; int r=md_devpts_name(mount->devpts,fd,name,sizeof(name));
        if (r) return r;
        int n=snprintf(out,size,"/%s",!strcmp(name,".") ? "" : name);
        return n<0 || (size_t)n>=size ? -ERANGE : 0;
    }
    if (!md_view_inode(mount)) return md_view_fdpath(fd,out,size);
    struct md_fs_storage_scope scope;
    int r=md_fs_storage_enter(fs,mount,&scope);
    if (r) return r;
    scope.store->root_id=NULL;
    r=md_inode_path(scope.store,fd,out,size);
    md_fs_storage_leave(&scope); return r;
}
int md_view_backing_contains(struct md_filesystem *fs,const struct md_view_mount *mount,int root,int object) {
    if (md_view_inode(mount)) {
        struct md_fs_storage_scope scope;
        int r=md_fs_storage_enter(fs,mount,&scope);
        if (r) return r;
        r=md_inode_contains(scope.store,root,object);
        md_fs_storage_leave(&scope); return r;
    }
    struct stat a,b;
    if (fstat(root,&a) || fstat(object,&b)) return -errno;
    if (md_view_same(&a,&b)) return 1;
    if (!S_ISDIR(a.st_mode)) return 0;
    char parent[PATH_MAX],child[PATH_MAX];
    int r=md_view_backing_path(fs,root,mount,parent,sizeof(parent));
    if (!r) r=md_view_backing_path(fs,object,mount,child,sizeof(child));
    return r ? r : beneath(child,parent);
}
static int backing_dentry_path(struct md_filesystem *fs,int fd,struct md_view_mount *m,
        uint64_t dentry,int native_birth,char *out,size_t size) {
    if ((!dentry && !native_birth) || !md_view_inode(m)) return md_view_backing_path(fs,fd,m,out,size);
    struct md_fs_storage_scope scope;
    int r=md_fs_storage_enter(fs,m,&scope);
    if (r) return r;
    scope.store->root_id=NULL;
    r=md_inode_dentry_path(scope.store,fd,dentry,native_birth,out,size);
    md_fs_storage_leave(&scope); return r;
}
static int location_path(struct md_filesystem *fs, int fd, struct md_view_mount *m,
        const struct md_view_mount *boundary,uint64_t dentry,int native_birth,char *out, size_t size) {
    if (!m) m=&fs->mounts->base;
    /* Prepend each backing-relative suffix once. Mount depth must not consume
     * worker stack or repeatedly copy the already-composed descendant path. */
    char root[PATH_MAX], path[PATH_MAX], suffix[PATH_MAX];
    size_t begin=sizeof(suffix)-1;
    suffix[begin]=0;
    for (;;) {
        if (m==&fs->mounts->base && m->source<0) {
            int r=backing_dentry_path(fs,fd,m,dentry,native_birth,path,sizeof(path));
            if (r) return r;
            break;
        }
        /* A retained root still names its subtree after detach. Children
         * detached below that boundary have no reachable pathname. */
        if (m->detached && m!=boundary) return -ENOENT;
        struct stat st;
        if (fstat(fd,&st)) return -errno;
        if (m==boundary) {
            if (!st.st_nlink) return -ENOENT;
            int r=backing_dentry_path(fs,fd,m,dentry,native_birth,path,sizeof(path));
            if (r) return r;
            break;
        }
        /* Mount roots use their covered dentry; file binds need no backing
         * name reconstruction and empty overmounts add no path component. */
        if (!md_view_same(&st,&m->native)) {
            if (!st.st_nlink) return -ENOENT;
            int r=md_view_backing_path(fs,m->source,m,root,sizeof(root));
            if (!r) r=backing_dentry_path(fs,fd,m,dentry,native_birth,path,sizeof(path));
            if (r) return r;
            if (!beneath(path,root)) return -EXDEV;
            const char *part=!strcmp(path,root) ? "" : path+(strlen(root)==1 ? 0 : strlen(root));
            size_t length=strlen(part);
            if (length>begin) return -ERANGE;
            begin-=length; memcpy(suffix+begin,part,length);
        }
        if (!m->parent) { strcpy(path,"/"); break; }
        fd=m->target; m=m->parent; dentry=0; native_birth=0;
    }
    size_t prefix=!strcmp(path,"/") && suffix[begin]=='/' ? 0 : strlen(path);
    size_t length=sizeof(suffix)-1-begin;
    if (prefix+length>=size) return -ERANGE;
    memcpy(out,path,prefix); memcpy(out+prefix,suffix+begin,length+1);
    return 0;
}
int md_view_path(struct md_filesystem *fs, int fd, struct md_view_mount *m, char *out, size_t size) {
    return md_view_dentry_path(fs,fd,m,0,0,out,size);
}
int md_view_dentry_path(struct md_filesystem *fs,int fd,struct md_view_mount *m,
        uint64_t dentry,int native_birth,char *out,size_t size) {
    if (!fs->native_root && !fs->store->root_id) return location_path(fs,fd,m,NULL,dentry,native_birth,out,size);
    if (fs->native_root) {
        struct md_view_mount *ancestor=m;
        while (ancestor && ancestor!=fs->native_root->mount) ancestor=ancestor->parent;
        if (!ancestor) return -ENOENT;
    }
    int root_fd=fs->native_root ? fs->native_root->fd
        : md_inode_open(fs->store,MD_INODE_ROOT,"/",O_PATH|O_DIRECTORY|O_CLOEXEC,0);
    if (root_fd<0) return root_fd;
    const char *selected=fs->store->root_id;
    /* Source and covered dentries belong to their backing filesystem. Apply
     * the process root only after composing the complete mount-tree path. */
    fs->store->root_id=NULL;
    char root[PATH_MAX], path[PATH_MAX];
    struct md_view_mount *boundary=fs->native_root ? fs->native_root->mount : NULL;
    int r = location_path(fs,root_fd,boundary,boundary,0,0,root,sizeof(root));
    if (!r) r = location_path(fs,fd,m,boundary,dentry,native_birth,path,sizeof(path));
    fs->store->root_id=selected;
    if (!fs->native_root) close(root_fd);
    if (!r && !beneath(path,root)) r = -ENOENT;
    if (!r) {
        const char *relative = !strcmp(path,root) ? "/" : path+(strlen(root)==1 ? 0 : strlen(root));
        if (strlen(relative)>=size) r=-ERANGE;
        else strcpy(out,relative);
    }
    return r;
}
int md_view_location_path(struct md_filesystem *fs,const struct md_view_location *location,char *out,size_t size) {
    if (location->view!=fs->mounts) return -ENOENT;
    /* A named lookup selects a dentry, not an arbitrary alias of its inode. */
    if (location->parent<0 || !strcmp(location->name,"."))
        return md_view_path(fs,location->fd,location->mount,out,size);
    int error=md_view_path(fs,location->parent,location->mount,out,size);
    if (!error) {
        size_t length=strlen(out);
        int n=snprintf(out+length,size-length,"%s%s",length==1 ? "" : "/",location->name);
        if (n<0 || (size_t)n>=size-length) error=-ERANGE;
    }
    return error;
}
int md_fs_root_capture(struct md_filesystem *fs, int fd, const struct md_fs_anchor *origin,
        struct md_fs_location *out) {
    *out = (struct md_fs_location){0};
    if (origin) {
        if (origin->child) return -ENOTDIR;
        int error=md_view_anchor_check(fs,fd,origin);
        if (error) return error;
        if (origin->view && origin->view!=fs->mounts) return -ESTALE;
    }
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    if (!S_ISDIR(st.st_mode)) return -ENOTDIR;
    struct md_view_object *p = NULL;
    /* Once materialized, the base keeps explicit location identities even if
     * it becomes root again. Empty initial roots must not depend on cache hits. */
    int r = origin ? (origin->view && (!md_view_base(fs->mounts,origin->mount) || fs->mounts->base.source>=0)
        ? md_view_record(fs,fd,origin->mount,&p) : -EXDEV)
        : fs->mounts ? md_view_identity(fs,fd,&p) : -EXDEV;
    if (!r && md_view_base(fs->mounts,p->mount) && fs->mounts->base.source<0) r=-EXDEV;
    if (!r) {
        if (md_view_inode(p->mount)) {
            struct md_fs_request access={.operation=MD_FS_ACCESS,.directory={fd,-1},.mode=X_OK,.flags=MD_AT_EACCESS};
            struct md_fs_storage_scope scope;
            r=md_fs_storage_enter(fs,p->mount,&scope);
            if (!r) { r=md_inode_metadata(scope.store,&access); md_fs_storage_leave(&scope); }
            if (r) return r;
        } else if (syscall(SYS_faccessat2,fd,"",X_OK,AT_EMPTY_PATH|MD_AT_EACCESS)) return -errno;
        out->mount=p->mount->id; memcpy(out->object,p->id,sizeof(out->object));
        return 0;
    }
    if (r!=-EXDEV) return r;
    struct md_fs_request access={.operation=MD_FS_ACCESS,.directory={fd,-1},.mode=X_OK,.flags=MD_AT_EACCESS};
    r=md_inode_metadata(fs->store,&access);
    if (!r) r=md_inode_object_id(fs->store,fd,out->object);
    if (!r && !strcmp(out->object,MDI_ROOT)) out->object[0]=0;
    if (r==-EXDEV && fs->mounts) {
        /* A caller-captured native directory is already an admitted authority.
         * Retain it as a detached root, not a second filesystem path policy. */
        if (syscall(SYS_faccessat2,fd,"",X_OK,AT_EMPTY_PATH|MD_AT_EACCESS)) return -errno;
        struct md_mount_source source={.fd=fd,.backing=MD_MOUNT_NATIVE};
        r=md_mount_native_policy(fd,&source.policy);
        struct statfs type;
        if (!r && fstatfs(fd,&type)) r=-errno;
        /* Captured proc roots use the same caller-aware component walker as
         * mounted proc, not a native suffix that can bypass the new root. */
        if (!r && type.f_type==PROC_SUPER_MAGIC) source.backing=MD_MOUNT_PROC;
        struct md_view_mount *mount=NULL;
        if (!r) r=md_view_attach_source(fs,&source,NULL,&mount);
        if (!r) r=md_view_record(fs,fd,mount,&p);
        if (!r) { out->mount=mount->id; memcpy(out->object,p->id,sizeof(out->object)); }
    }
    return r;
}
int md_fs_root_select(struct md_filesystem *fs, const struct md_fs_location *root) {
    fs->native_root=NULL;
    if (root->mount) {
        struct md_view_object *p=md_view_object(fs,root->object);
        if (!p || p->mount->id!=root->mount) return -ESTALE;
        fs->native_root=p;
    }
    fs->store->root_id=root->mount || !root->object[0] ? NULL : root->object;
    if (fs->native_root && md_view_inode(fs->native_root->mount)) {
        struct md_inode_store *store;
        int r=md_fs_storage_get(fs,fs->native_root->mount,&store);
        if (r) return r;
        if (store==fs->store) fs->store->root_id=fs->native_root->inode;
    }
    return 0;
}
int md_fs_root_is_mount_root(struct md_filesystem *fs, const struct md_fs_location *root) {
    if (!fs->mounts) return -EINVAL;
    if (!root->mount) return fs->mounts->root==&fs->mounts->base
        && (!root->object[0] || !strcmp(root->object,MDI_ROOT));
    struct md_view_object *p=md_view_object(fs,root->object);
    if (!p || p->mount->id!=root->mount) return -ESTALE;
    return p->mount==fs->mounts->root && md_view_same(&p->identity,&p->mount->native);
}
int md_fs_mounts_join_context(struct md_filesystem *fs, struct md_guest_domain *context) {
    if (!fs->mounts || !context) return -EINVAL;
    struct md_filesystem selected=*fs;
    selected.native_root=NULL;
    const char *previous=fs->store->root_id;
    fs->store->root_id=NULL;
    struct md_view_location root={.fd=-1,.parent=-1};
    int r=md_view_walk(&selected,AT_FDCWD,NULL,"/",1,0,0,&root);
    struct md_fs_anchor *origin=NULL;
    struct md_fs_location location;
    if (!r) {
        struct stat st;
        if (fstat(root.fd,&st)) r=-errno;
        int retained=r ? -1 : fcntl(root.fd,F_DUPFD_CLOEXEC,3);
        if (!r && retained<0) r=-errno;
        if (!r) {
            origin=md_view_anchor_new(md_inode_store_lifetime(fs->store),fs->mounts,root.mount,
                retained,st.st_dev,st.st_ino);
            if (!origin) { r=-errno; close(retained); }
        }
    }
    if (!r) r=md_fs_root_capture(&selected,root.fd,origin,&location);
    if (!r) r=md_domain_set_root(context,&location);
    if (!r) r=md_domain_set_cwd(context,origin);
    if (!r) md_domain_bind_mounts(context,fs->mounts);
    md_fs_anchor_release(origin); md_view_location_close(&root);
    fs->store->root_id=previous; return r;
}
void md_fs_mounts_close(struct md_filesystem *fs) {
    md_binfmt_registry_destroy(fs->binfmt); fs->binfmt=NULL;
    md_mount_namespace_release(fs->mounts);
    fs->mounts = NULL; fs->native_root = NULL;
    md_fs_storage_prune(fs->storage);
}
int md_fs_mount_contents(struct md_filesystem *fs,const char *path,const void *bytes,size_t size) {
    if (!path || path[0]!='/' || !bytes || !size) return -EINVAL;
    struct md_view_location target;
    int error=md_view_walk(fs,MD_INODE_ROOT,NULL,path,1,0,0,&target);
    if (error) return error;
    /* O_TMPFILE is reopenable under Android's shell policy, unlike a memfd.
     * No name or inode record survives this launch, including an abrupt exit. */
    int fd=md_inode_temporary(fs->store);
    if (fd<0) error=fd;
    size_t written=0;
    while (!error && written<size) {
        ssize_t count=pwrite(fd,(const char *)bytes+written,size-written,(off_t)written);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) error=count<0 ? -errno : -EIO;
        else written+=(size_t)count;
    }
    if (!error && fchmod(fd,0444)) error=-errno;
    if (!error) {
        struct md_mount_source source={.fd=fd,.policy=MD_MOUNT_READONLY|MD_MOUNT_NOEXEC|MD_MOUNT_NOSUID|MD_MOUNT_NODEV};
        error=md_view_attach_source(fs,&source,&target,NULL);
    }
    if (fd>=0) close(fd);
    md_view_location_close(&target);
    return error;
}
int md_fs_mounts_open(struct md_filesystem *fs, const struct md_fs_attachment *entries, unsigned count) {
    if (fs->mounts || count > MD_FS_MOUNTS_MAX) return -EINVAL;
    fs->mounts = md_mount_namespace_new();
    if (!fs->mounts) return -errno;
    fs->binfmt=md_binfmt_registry_new();
    if (!fs->binfmt) { md_fs_mounts_close(fs); return -ENOMEM; }
    if (!count) return 0;
    struct md_fs_mounts *tree = md_view_tree_new();
    if (!tree) { md_fs_mounts_close(fs); return -ENOMEM; }
    fs->mounts->tree = tree;
    /* A startup layout is built by one owner. Resolve each retained path once
     * for overlap checks, rather than rewalking all previous entries per bind. */
    struct attachment_path { char *source, *target; };
    struct attachment_path *paths=calloc(count,sizeof(*paths));
    if (!paths) { md_fs_mounts_close(fs); return -ENOMEM; }
    int r = 0;
    for (unsigned i = 0; i < count && !r; ++i) {
        const struct md_fs_attachment *entry = &entries[i];
        struct md_view_mount *m = &tree->mounts[tree->count];
        m->generation=1;
        m->source = m->target = -1;
        r = md_mount_policy_set(&m->policy,entry->readonly ? MD_MOUNT_READONLY : 0);
        if (!r) r = md_mount_id_allocate(&m->id);
        if (r) break;
        if (!entry->source || !entry->target || entry->source[0] != '/' || entry->target[0] != '/') { r = -EINVAL; break; }
        m->source = open(entry->source, O_PATH | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (m->source < 0) { r = -errno; break; }
        struct md_view_location target_location;
        r = md_view_walk(fs, MD_INODE_ROOT, NULL, entry->target, 1, 0, 0, &target_location);
        if (r) break;
        m->target = target_location.fd; target_location.fd = -1;
        m->parent = target_location.mount;
        md_view_location_close(&target_location);
        if (fstat(m->source, &m->native) || fstat(m->target, &m->guest)) { r = -errno; break; }
        char source[PATH_MAX], target[PATH_MAX];
        r = md_view_fdpath(m->source, source, sizeof(source));
        if (!r && !S_ISDIR(m->guest.st_mode)) r = -ENOTDIR;
        if (!r) r = md_view_path(fs, m->target, m->parent, target, sizeof(target));
        if (!r && (!strcmp(target, "/") || beneath(target, "/dev") || beneath(target, "/proc") || beneath(target, "/sys"))) r = -EINVAL;
        for (unsigned j = 0; j < i && !r; ++j) {
            if (beneath(source,paths[j].source) || beneath(paths[j].source,source)) r=-EXDEV;
            /* Covering an existing subtree requires explicit mount-stack and
             * open-description ownership; never silently hide earlier entries. */
            if (!r && beneath(paths[j].target,target)) r=-EBUSY;
        }
        if (!r) {
            paths[i].source=strdup(source); paths[i].target=strdup(target);
            if (!paths[i].source || !paths[i].target) r=-ENOMEM;
        }
        if (!r) ++tree->count;
    }
    if (r) {
        struct md_view_mount *pending = &tree->mounts[tree->count];
        if (pending->source >= 0) close(pending->source);
        if (pending->target >= 0) close(pending->target);
    }
    for (unsigned i=0;i<count;++i) { free(paths[i].source); free(paths[i].target); }
    free(paths);
    if (r) md_fs_mounts_close(fs);
    return r;
}

int md_fs_mounts_copy_cwd(struct md_mount_namespace *source, struct md_mount_namespace *copy,
        const struct md_fs_anchor *cwd, struct md_fs_anchor **out) {
    if (!source || !copy || !out || *out) return -EINVAL;
    if (!cwd || cwd->view!=source) {
        *out=md_fs_anchor_retain((struct md_fs_anchor *)cwd); return 0;
    }
    struct md_view_mount *mount=&copy->base;
    if (!md_view_base(source,cwd->mount)) {
        unsigned i=0;
        while (source->tree && i<source->tree->count && cwd->mount!=&source->tree->mounts[i]) ++i;
        if (!source->tree || i==source->tree->count || !copy->tree || i>=copy->tree->count) return -ESTALE;
        mount=&copy->tree->mounts[i];
    }
    int fd=fcntl(cwd->fd,F_DUPFD_CLOEXEC,3);
    if (fd<0) return -errno;
    struct md_fs_anchor *p=md_view_anchor_new(cwd->lifetime,copy,mount,fd,cwd->device,cwd->inode);
    if (!p) { int r=-errno; close(fd); return r; }
    *out=p; return 0;
}
int md_fs_mounts_copy(struct md_filesystem *fs, const struct md_fs_location *root,
        struct md_mount_namespace **out, struct md_fs_location *copied_root) {
    if (!fs->mounts || !root || !out || !copied_root) return -EINVAL;
    unsigned order[(fs->mounts->tree ? fs->mounts->tree->count : 0)+1];
    int valid=md_view_mount_reconcile(fs);
    if (!valid) valid=md_view_topology_order(fs->mounts,order);
    if (valid) return valid;
    for (unsigned i=0;i<=(fs->mounts->tree ? fs->mounts->tree->count : 0);++i)
        if (md_mount_links_foreign(&md_view_mount_node(fs->mounts,i)->links)) return -EXDEV;
    struct md_view_object *origin=NULL;
    if (root->mount) {
        origin=md_view_object(fs,root->object);
        if (!origin || origin->mount->id!=root->mount) return -ESTALE;
    }
    struct md_mount_namespace *copy=md_mount_namespace_new();
    if (!copy) return -errno;
    md_mount_namespace_set_owner(copy,md_mount_namespace_owner(fs->mounts));
    atomic_store_explicit(&copy->managed_paths,md_mount_namespace_managed_paths(fs->mounts),memory_order_relaxed);
    copy->base.policy.local=md_mount_policy_flags(&fs->mounts->base.policy);
    copy->base.locked=fs->mounts->base.locked;
    copy->base.root_providers=fs->mounts->base.root_providers;
    copy->base.links=md_mount_links_copy(&fs->mounts->base.links);
    struct md_fs_mounts *source=fs->mounts->tree;
    int r=0;
    if (source && source->count) {
        copy->tree=md_view_tree_new();
        if (!copy->tree) r=-ENOMEM;
    }
    for (unsigned i=0; !r && source && i<source->count; ++i) {
        const struct md_view_mount *a=&source->mounts[i];
        struct md_view_mount *b=&copy->tree->mounts[i];
        int skip=0;
        for (const struct md_view_mount *p=a;p;p=p->parent)
            if (md_view_file_namespace(p->file)) { skip=1; break; }
        /* Mount-namespace files are not inherited by a namespace copy: doing
         * so would reverse the acyclic ownership ordering enforced at bind. */
        if (!a->id || skip) {
            *b=(struct md_view_mount){.source=-1,.target=-1,.detached=1,.generation=a->generation};
            ++copy->tree->count;
            continue;
        }
        /* Namespace copies get independent policy, even from a shared view. */
        *b=(struct md_view_mount){.source=-1,.target=-1,
            .backing=a->backing,.detached=a->detached,.locked=a->locked,.generation=a->generation,
            .root_providers=a->root_providers,
            .links=md_mount_links_copy(&a->links),
            .storage=md_inode_lifetime_retain(a->storage),
            .pid_namespace=md_pid_namespace_retain(a->pid_namespace),
            .devpts=md_devpts_retain(a->devpts),
            .cgroup=md_cgroup_retain(a->cgroup),
            .sysfs=md_sysfs_retain(a->sysfs),
            .provider=md_provider_tree_retain(a->provider),
            .ipc_namespace=md_ipc_namespace_retain(a->ipc_namespace),
            .file=md_control_file_retain(a->file),
            .policy={.local=md_mount_policy_flags(&a->policy)},.native=a->native,.guest=a->guest,
            .parent=!a->parent ? NULL : !md_view_base(fs->mounts,a->parent)
                ? &copy->tree->mounts[a->parent-source->mounts] : &copy->base};
        /* Count partially built entries so rollback closes every acquired FD. */
        ++copy->tree->count;
        if (b->detached) atomic_store_explicit(&copy->detached_mounts,1,memory_order_relaxed);
        r=md_mount_id_allocate(&b->id);
        if (!r && (b->source=fcntl(a->source,F_DUPFD_CLOEXEC,3))<0) r=-errno;
        if (!r && (b->target=fcntl(a->target,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    }
    if (!r && fs->mounts->base.source>=0) {
        const struct md_view_mount *a=&fs->mounts->base;
        copy->base.native=a->native; copy->base.guest=a->guest; copy->base.detached=a->detached;
        copy->base.parent=a->parent ? &copy->tree->mounts[a->parent-source->mounts] : NULL;
        copy->base.source=fcntl(a->source,F_DUPFD_CLOEXEC,3);
        if (copy->base.source<0) r=-errno;
        if (!r && (copy->base.target=fcntl(a->target,F_DUPFD_CLOEXEC,3))<0) r=-errno;
    }
    if (!r && fs->mounts->root!=&fs->mounts->base)
        copy->root=&copy->tree->mounts[fs->mounts->root-source->mounts];
    struct md_fs_location selected=*root;
    if (!r && origin) {
        struct md_filesystem candidate={.store=fs->store,.storage=fs->storage,.mounts=copy};
        struct md_view_mount *mount=origin->mount==&fs->mounts->base ? &copy->base
            : &copy->tree->mounts[origin->mount-source->mounts];
        struct md_view_object *object;
        r=md_view_record(&candidate,origin->fd,mount,&object);
        if (!r) {
            selected.mount=mount->id;
            memcpy(selected.object,object->id,sizeof(selected.object));
        }
    }
    if (r) md_mount_namespace_release(copy);
    else {
        md_mount_family_join(copy,fs->mounts);
        md_mount_family_retire(copy);
        *out=copy; *copied_root=selected;
    }
    return r;
}
