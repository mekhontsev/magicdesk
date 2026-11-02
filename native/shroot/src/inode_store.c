#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_overlay.h"
#include "inode_leaf.h"
#include "inode_native.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <sys/inotify.h>
#include <string.h>
#include <unistd.h>

int md_inode_temporary(struct md_inode_store *s) {
    int fd = openat(s->objects, ".", O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
    return fd < 0 ? -errno : fd;
}

int md_inode_object_id(struct md_inode_store *s, int fd, char out[33]) {
    if (!out) return -EFAULT;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, fd, &node);
    if (!r) memcpy(out, node.id, sizeof(node.id));
    return mdi_finish(s, r);
}
int md_inode_open_object(struct md_inode_store *s, const char *id, int flags) {
    if (!id) return -EFAULT;
    if (strnlen(id, 33) != 32) return -EINVAL;
    for (unsigned i = 0; i < 32; ++i)
        if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return -EINVAL;
    if (flags & O_PATH) flags &= O_PATH | O_CLOEXEC | O_DIRECTORY;
    if (flags & ~(O_PATH | O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NONBLOCK | O_LARGEFILE)) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node node;
    r = mdi_node(s, id, &node);
    if (!r && strcmp(node.scope,s->lifetime->scope)) r=-EXDEV;
    struct stat st;
    if (!r) r = mdi_backing_stat(s, &node, &st);
    if (!r && node.kind != S_IFREG) r = -EINVAL;
    if (!r) r = mdi_open_permission(s, &node, flags,NULL);
    if (!r) r=mdi_landlock_object(s,&node,s->image_open ? MD_LL_EXECUTE : md_landlock_open_access(flags,node.kind));
    int fd = -1;
    int directory = r ? -1 : mdi_backing_directory(s, &node);
    if (!r && directory < 0) r = directory;
    if (!r && (fd=mdi_backing_open(s,&node,flags))<0) r=fd;
    r = mdi_finish(s, r);
    if (r && fd >= 0) close(fd);
    return r ? r : fd;
}

static int create_node(struct md_inode_store *s, int dirfd, const char *path,
        mode_t kind, mode_t mode, const char *target, dev_t device) {
    if (mode & ~(s->identity ? 07777 : 01777)) return -ENOTSUP;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_ENTRY, 1, &loc);
    if (!r && loc.trailing && kind == S_IFREG) r = -EISDIR;
    if (!r && loc.exists) r = -EEXIST;
    if (!r && loc.trailing && kind != S_IFDIR) r = -ENOENT;
    if (!r && mdi_readonly(s)) r=-EROFS;
    if (!r) r = mdi_parent_writable(s, &loc.parent);
    if (!r && (kind==S_IFCHR || kind==S_IFBLK) && s->identity
            && !md_identity_capable(s->identity,CAP_MKNOD)) r=-EPERM;
    if (!r) r=mdi_landlock(s,&loc.parent,NULL,md_landlock_make_access(kind));
    if (!r && kind==S_IFREG) {
        int fd=mdi_leaf_create(s,&loc.parent,loc.name,O_RDWR,mode);
        if (fd!=-EOPNOTSUPP) {
            r=mdi_finish(s,fd<0 ? fd : 0);
            if (r && fd>=0) close(fd);
            return r ? r : fd;
        }
    }
    if (!r) r=mdi_write(s);
    struct mdi_node node;
    int fd = -1;
    if (!r) r = mdi_allocate(s, kind, mode, O_RDWR, target,
        loc.parent.id, &node, &fd);
    if (!r && (kind==S_IFCHR || kind==S_IFBLK)) r=mdi_device_number(s,&node,device);
    if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, node.id);
    if (!r) r = mdi_event(s, loc.parent.id, &node, loc.name, IN_CREATE, NULL);
    r = mdi_commit(s, r);
    /* Retain unpublished objects on failure: commit IO errors can have an
     * unknown outcome. Reclamation requires a separate lifetime contract. */
    if (r) { if (fd >= 0) close(fd); return r; }
    return fd >= 0 ? fd : 0;
}
int md_inode_create(struct md_inode_store *s, int dirfd, const char *path, mode_t mode) {
    return md_inode_open(s, dirfd, path, O_CREAT | O_EXCL | O_RDWR, mode);
}
int md_inode_mkdir(struct md_inode_store *s, int dirfd, const char *path, mode_t mode) {
    return create_node(s, dirfd, path, S_IFDIR, mode, NULL, 0);
}
int md_inode_mkfifo(struct md_inode_store *s, int dirfd, const char *path, mode_t mode) {
    int r = create_node(s, dirfd, path, S_IFIFO, mode, NULL, 0);
    if (r >= 0) { close(r); return 0; }
    return r;
}
int mdi_device_number(struct md_inode_store *s,struct mdi_node *node,dev_t device) {
    if (node->kind!=S_IFCHR && node->kind!=S_IFBLK) return device ? -EINVAL : 0;
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,"UPDATE objects SET rdev=?1 WHERE object=?2",&q);
    if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,1,(sqlite3_int64)device));
    if (!r) r=mdi_bind_id(q,2,node->id);
    if (!r) r=mdi_sql_error(mdi_step(s,q));
    sqlite3_finalize(q);
    if (!r) node->rdev=device;
    return r;
}
int md_inode_mknod(struct md_inode_store *s,int dirfd,const char *path,mode_t mode,dev_t device) {
    mode_t kind=mode&S_IFMT;
    if (kind!=S_IFREG && kind!=S_IFIFO && kind!=S_IFCHR && kind!=S_IFBLK) return -EINVAL;
    int r=create_node(s,dirfd,path,kind,mode&07777,NULL,device);
    if (r>=0) { close(r); return 0; }
    return r;
}
int md_inode_symlink(struct md_inode_store *s, const char *target, int dirfd, const char *path) {
    if (!target) return -EFAULT;
    if (!*target) return -ENOENT;
    if (strnlen(target, PATH_MAX) == PATH_MAX) return -ENAMETOOLONG;
    return create_node(s, dirfd, path, S_IFLNK, 0777, target, 0);
}
ssize_t mdi_readlink(struct md_inode_store *s,const struct mdi_node *node,char *out,size_t size,int empty) {
    if (!out) return -EFAULT;
    if (!size) return -EINVAL;
    if (node->kind!=S_IFLNK) return empty ? -ENOENT : -EINVAL;
    int directory=mdi_backing_directory(s,node);
    if (directory<0) return directory;
    ssize_t n=readlinkat(directory,node->backing,out,size);
    return n<0 ? -errno : n;
}
ssize_t md_inode_readlink(struct md_inode_store *s, int dirfd, const char *path, char *out, size_t size) {
    if (!out) return -EFAULT;
    if (!size) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    int empty = path && !*path;
    r = empty ? (dirfd == MD_INODE_ROOT ? -ENOENT : mdi_fd(s, dirfd, &loc.node))
        : mdi_walk(s, dirfd, path, MDI_NOFOLLOW, 0, &loc);
    ssize_t n = -1;
    if (!r && (n=mdi_readlink(s,&loc.node,out,size,empty))<0) r=(int)n;
    r = mdi_finish(s, r);
    return r ? r : n;
}
static int open_resolved(struct md_inode_store *, int, const char *, int, mode_t, uint64_t,
        struct md_image_identity *, struct md_open_completion *, int,struct md_inode_birth **);
static int birth_permission(struct md_inode_store *s,const struct mdi_location *loc,int flags) {
    int r=loc->trailing ? -EISDIR : mdi_readonly(s) ? -EROFS : 0;
    if (!r) r=mdi_parent_writable(s,&loc->parent);
    if (!r) r=mdi_landlock(s,&loc->parent,NULL,MD_LL_MAKE_REG|md_landlock_open_access(flags,S_IFREG));
    return r;
}
int md_inode_open_image(struct md_inode_store *s, int dirfd, const char *path, int flags,
        struct md_image_identity *image) {
    if (!image) return -EFAULT;
    if (flags & ~O_NOFOLLOW) return -EINVAL;
    struct md_open_completion completion;
    return open_resolved(s, dirfd, path, flags | O_RDONLY | O_CLOEXEC, 0, 0, image, &completion, 0,NULL);
}
int md_inode_open(struct md_inode_store *s, int dirfd, const char *path, int flags, mode_t mode) {
    return md_inode_open_resolved(s, dirfd, path, flags, mode, 0);
}
int md_inode_open_resolved(struct md_inode_store *s, int dirfd, const char *path, int flags, mode_t mode,
        uint64_t resolve) {
    struct md_open_completion completion;
    int fd = md_inode_prepare_open(s, dirfd, path, flags, mode, resolve, &completion);
    return md_complete_open(fd, flags | O_CLOEXEC, completion);
}
int md_inode_prepare_open(struct md_inode_store *s, int dirfd, const char *path, int flags, mode_t mode,
        uint64_t resolve, struct md_open_completion *completion) {
    return md_inode_plan_open(s,dirfd,path,flags,mode,resolve,completion,0,NULL);
}
int md_inode_plan_open(struct md_inode_store *s,int dirfd,const char *path,int flags,mode_t mode,
        uint64_t resolve,struct md_open_completion *completion,int native_plan,struct md_inode_birth **birth) {
    return open_resolved(s, dirfd, path, flags, mode, resolve, NULL, completion, native_plan,birth);
}
static int open_resolved(struct md_inode_store *s, int dirfd, const char *path, int flags, mode_t mode,
        uint64_t resolve, struct md_image_identity *image, struct md_open_completion *completion, int native_plan,
        struct md_inode_birth **birth) {
    *completion = (struct md_open_completion){0};
    if (birth && (!native_plan || *birth)) return -EINVAL;
    if (native_plan && !md_open_native_plan(flags,birth!=NULL)) return -EXDEV;
    if (flags & ~(O_ACCMODE | O_CLOEXEC | O_APPEND | O_TRUNC | O_NOFOLLOW | O_DIRECTORY | O_PATH
            | O_CREAT | O_EXCL | O_NONBLOCK | O_NOCTTY | O_NOATIME | O_LARGEFILE | O_SYNC | O_DSYNC | O_DIRECT)) return -ENOTSUP;
    if ((flags & O_ACCMODE) == O_ACCMODE) return -EINVAL;
    if (flags & O_PATH) flags &= O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    int create = flags & O_CREAT;
    if (create && (flags & O_DIRECTORY)) return -EINVAL;
    if (create && (mode & ~(s->identity ? 07777 : 01777))) return -ENOTSUP;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    enum mdi_follow follow=create && (flags&O_EXCL) ? MDI_ENTRY : flags&O_NOFOLLOW ? MDI_NOFOLLOW : MDI_FOLLOW;
    struct mdi_path_pin pin={.fd=-1};
    r=native_plan ? mdi_walk_pinned(s,dirfd,path,follow,!!create,resolve,&loc,&pin)
        : mdi_walk_resolved(s,dirfd,path,follow,!!create,resolve,&loc);
    int fd=pin.fd;
    /* The selected provider owns data-open semantics. Do not reject this
     * inode endpoint combination before discovering a mount crossing. */
    if (!r && (flags&O_TRUNC) && !(flags&O_ACCMODE)) r=-EINVAL;
    if (!r && create && loc.trailing) r = -EISDIR;
    if (!r && create && (flags & O_EXCL) && loc.exists) r = -EEXIST;
    if (!r && !loc.exists) {
        if (native_plan && !birth) return mdi_finish(s,-EXDEV);
        r=birth_permission(s,&loc,flags);
        if (native_plan) {
            if (!r) {
                fd=mdi_leaf_prepare_birth(s,&loc.parent,loc.name,flags,mode,birth);
                r=fd<0 ? (fd==-EOPNOTSUPP ? -EXDEV : fd) : 0;
            }
            struct stat st;
            if (!r && fstat(fd,&st)) r=-errno;
            r=mdi_finish(s,r);
            if (r) {
                if (fd>=0) close(fd);
                int discarded=md_inode_birth_discard(birth);
                return discarded ? discarded : r;
            }
            *completion=(struct md_open_completion){.kind=MD_OPEN_PLAN,
                .backing_device=st.st_dev,.backing_inode=st.st_ino,.mode=S_IFREG,.native_leaf=1};
            return fd;
        }
        if (!r) {
            fd=mdi_leaf_create(s,&loc.parent,loc.name,flags,mode);
            if (fd!=-EOPNOTSUPP) {
                r=mdi_finish(s,fd<0 ? fd : 0);
                if (r && fd>=0) close(fd);
                if (!r) completion->native_leaf=1;
                return r ? r : fd;
            }
            fd=-1;
        }
        if (!r) r=mdi_write(s);
        if (!r) r = mdi_allocate(s, S_IFREG, mode, flags, NULL, loc.parent.id, &loc.node, &fd);
        if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, loc.node.id);
        if (!r) r = mdi_dentry_capture(s,&loc,&completion->dentry);
        if (!r) r = mdi_event(s, loc.parent.id, &loc.node, loc.name, IN_CREATE, NULL);
        if (!r && (flags&O_DSYNC)) s->sync_requested=1;
        r = mdi_commit(s, r);
        if (r && fd >= 0) close(fd);
        return r ? r : fd;
    }
    if (!r && (flags & O_DIRECTORY) && loc.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && loc.node.kind == S_IFLNK && !(flags & O_PATH)) r = -ELOOP;
    if (!r && loc.node.kind == S_IFSOCK && !(flags & O_PATH)) r = -ENXIO;
    if (!r && loc.node.kind == S_IFDIR && (flags & (O_WRONLY | O_RDWR | O_TRUNC | O_CREAT))) r = -EISDIR;
    if (!r && loc.node.kind==S_IFREG && mdi_readonly(s) && (flags&(O_WRONLY|O_RDWR|O_TRUNC))) r=-EROFS;
    if (!r && image && loc.node.kind != S_IFREG) r = -EACCES;
    int device=!r && (loc.node.kind==S_IFCHR || loc.node.kind==S_IFBLK) && !(flags&O_PATH);
    if (device && s->nodev) r=-EACCES;
    struct stat observed=pin.physical;
    if (!r && pin.fd>=0) mdi_stat_project(&loc.node,&observed);
    /* The pinned lookup already checked backing identity. Reuse only this
     * operation's observation, never permission metadata from an earlier open. */
    if (!r) r = image ? mdi_permission(s, &loc.node, X_OK, 0)
        : mdi_open_permission(s,&loc.node,flags,pin.fd>=0 ? &observed : NULL);
    if (!r) r=mdi_landlock(s,&loc.node,&loc.parent,image ? MD_LL_EXECUTE : md_landlock_open_access(flags,loc.node.kind));
    /* Decline special endpoints before copy-up or any data-open effect. */
    if (!r && native_plan && !md_open_native_kind(flags,loc.node.kind)) r=-EXDEV;
    int copy_up=!r && !device && (flags&(O_WRONLY|O_RDWR|O_TRUNC))
        && (loc.node.shared || (s->overlay && !loc.node.native));
    if (!r && s->read_depth>1 && (copy_up || image || (loc.node.kind==S_IFIFO && !(flags&O_PATH)))) {
        /* FIFO channel custody, image promotion and COW may write metadata.
         * Leave the enclosing read scope before any such effect or retry. */
        if (fd>=0) close(fd);
        completion->kind=MD_OPEN_NEEDS_WRITE;
        return mdi_finish(s,-EAGAIN);
    }
    if (copy_up) {
        r=mdi_write(s);
        if (!r) r=mdi_copy_up(s,&loc.node);
    }
    int plan=!r && native_plan;
    struct stat st=pin.physical;
    if (!r && !plan) r = mdi_backing_stat(s, &loc.node, &st);
    int directory = r ? -1 : mdi_backing_directory(s, &loc.node);
    if (!r && directory < 0) r = directory;
    int deferred = !r && loc.node.kind == S_IFIFO && !(flags & O_PATH);
    int native_flags = deferred || device ? O_PATH : flags & ~(O_CREAT | O_EXCL);
    if (plan) native_flags=O_PATH;
    if (!r && image) r=mdi_leaf_promote(s,&loc.node);
    if (!r && fd<0 && (fd=mdi_backing_open(s,&loc.node,native_flags))<0) r=fd;
    if (!r && plan && pin.fd<0) r=mdi_backing_fstat(&loc.node,fd,&st);
    if (!r && image) {
        struct stat opened;
        if (fstat(fd, &opened)) r = -errno;
        else if (opened.st_dev != st.st_dev || opened.st_ino != st.st_ino) r = -EIO;
        if (!r) r = mdi_location_path(s, &loc, image->path, sizeof(image->path));
        if (!r) memcpy(image->object, loc.node.id, sizeof(image->object));
    }
    uint64_t dentry=0;
    if (!r) r=mdi_dentry_capture(s,&loc,&dentry);
    if (!r && (flags&O_DSYNC) && (!plan || s->write_transaction)) s->sync_requested=1;
    r = mdi_finish(s, r);
    if (r) { if (fd >= 0) close(fd); return r; }
    if (deferred) {
        int result=mdi_fifo_prepare(s,fd,completion);
        if (result>=0) completion->dentry=dentry;
        return result;
    }
    if (plan) *completion=(struct md_open_completion){.kind=MD_OPEN_PLAN,
        .backing_device=st.st_dev,.backing_inode=st.st_ino,.mode=st.st_mode&S_IFMT};
    completion->native_leaf=loc.node.native;
    if (device) *completion=(struct md_open_completion){.kind=MD_OPEN_DEVICE,
        .device=loc.node.rdev,.mode=loc.node.kind};
    completion->dentry=dentry;
    return fd;
}
int md_inode_link(struct md_inode_store *s, int sourcefd, const char *source,
        int targetfd, const char *target, int flags) {
    if (flags & ~AT_SYMLINK_FOLLOW) return -EINVAL;
    int r = mdi_begin(s, 1);
    if (r) return r;
    struct mdi_location a, b;
    r = mdi_walk(s, sourcefd, source, flags & AT_SYMLINK_FOLLOW ? MDI_FOLLOW : MDI_NOFOLLOW, 0, &a);
    if (!r) {
        r = mdi_walk(s, targetfd, target, MDI_ENTRY, 1, &b);
        if (r==-EREMOTE && s->boundary) s->boundary->operand=1;
    }
    if (!r && a.node.kind == S_IFDIR) r = -EPERM;
    if (!r && b.exists) r = -EEXIST;
    if (!r && b.trailing) r = -ENOENT;
    if (!r) r = mdi_parent_writable(s, &b.parent);
    if (!r && s->landlock && strcmp(a.parent.id,b.parent.id)) r=-EXDEV;
    if (!r) r=mdi_landlock(s,&b.parent,NULL,md_landlock_make_access(a.node.kind));
    if (!r) r=mdi_leaf_promote(s,&a.node);
    if (!r) r = mdi_add_name(s, b.parent.id, b.name, a.node.id);
    if (!r) r = mdi_event(s, b.parent.id, &a.node, b.name, IN_CREATE, NULL);
    if (!r) r = mdi_event(s, NULL, &a.node, NULL, IN_ATTRIB, NULL);
    return mdi_commit(s, r);
}
int md_inode_unlink(struct md_inode_store *s, int dirfd, const char *path, int flags) {
    if (flags & ~AT_REMOVEDIR) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    if (mdi_readonly(s)) return mdi_finish(s,-EROFS);
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_ENTRY, 0, &loc);
    if (!r && loc.special) {
        if (!(flags & AT_REMOVEDIR)) r = -EISDIR;
        else if (!strcmp(loc.name, ".")) r = -EINVAL;
        else r = *loc.name ? -ENOTEMPTY : -EBUSY;
    }
    if (!r && loc.trailing && loc.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && (flags & AT_REMOVEDIR) && loc.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && !(flags & AT_REMOVEDIR) && loc.node.kind == S_IFDIR) r = -EISDIR;
    if (!r && (flags & AT_REMOVEDIR)) {
        int empty = mdi_empty(s, &loc.node);
        if (empty != 1) r = empty < 0 ? empty : -ENOTEMPTY;
    }
    if (!r) r = mdi_parent_writable(s, &loc.parent);
    if (!r) r = mdi_sticky(s, &loc);
    if (!r) r=mdi_landlock(s,&loc.parent,NULL,loc.node.kind==S_IFDIR ? MD_LL_REMOVE_DIR : MD_LL_REMOVE_FILE);
    if (!r) r=mdi_super_preserve_writer(s,&loc.node);
    if (!r && loc.node.native) {
        int directory=mdi_directory_pin(s,&loc.parent);
        r=unlinkat(directory<0 ? s->objects : directory,
            directory<0 ? loc.node.backing : loc.name,0) ? -errno : 0;
        return mdi_finish(s,r);
    }
    if (!r) r=mdi_write(s);
    if (!r) r = mdi_delete_name(s, loc.parent.id, loc.name);
    if (!r) r = mdi_removed_event(s, &loc, 1);
    return mdi_commit(s, r);
}
int md_inode_rename(struct md_inode_store *s, int sourcefd, const char *source,
        int targetfd, const char *target, unsigned flags) {
    if (flags != 0 && flags != RENAME_NOREPLACE && flags != RENAME_EXCHANGE) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    if (mdi_readonly(s)) return mdi_finish(s,-EROFS);
    struct mdi_location a, b;
    r = mdi_walk(s, sourcefd, source, MDI_ENTRY, 0, &a);
    if (!r) {
        r = mdi_walk(s, targetfd, target, MDI_ENTRY, 1, &b);
        if (r==-EREMOTE && s->boundary) s->boundary->operand=1;
    }
    if (!r && (a.special || b.special)) r = -EBUSY;
    if (!r && a.trailing && a.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && b.trailing && a.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && b.trailing && b.exists && b.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && flags == RENAME_NOREPLACE && b.exists) r = -EEXIST;
    if (!r && flags == RENAME_EXCHANGE && !b.exists) r = -ENOENT;
    if (!r && s->landlock && strcmp(a.parent.id,b.parent.id)) r=-EXDEV;
    if (!r) r=mdi_landlock(s,&a.parent,NULL,(a.node.kind==S_IFDIR ? MD_LL_REMOVE_DIR : MD_LL_REMOVE_FILE)
        | md_landlock_make_access(a.node.kind)
        | (b.exists ? (b.node.kind==S_IFDIR ? MD_LL_REMOVE_DIR : MD_LL_REMOVE_FILE) : 0)
        | (flags==RENAME_EXCHANGE ? md_landlock_make_access(b.node.kind) : 0));
    /* Kernel rename between two names of the same inode preserves both names. */
    if (!r && b.exists && !strcmp(a.node.id, b.node.id)) return mdi_finish(s, 0);
    if (!r && s->overlay) r=mdi_overlay_rename_directory(s,&a.node);
    if (!r && s->overlay && flags==RENAME_EXCHANGE) r=mdi_overlay_rename_directory(s,&b.node);
    if (!r && b.exists && flags != RENAME_EXCHANGE) {
        if (a.node.kind == S_IFDIR && b.node.kind != S_IFDIR) r = -ENOTDIR;
        if (a.node.kind != S_IFDIR && b.node.kind == S_IFDIR) r = -EISDIR;
        if (!r && b.node.kind == S_IFDIR) {
            int empty = mdi_empty(s, &b.node);
            if (empty != 1) r = empty < 0 ? empty : -ENOTEMPTY;
        }
    }
    if (!r && a.node.kind == S_IFDIR) {
        int cycle = mdi_ancestor(s, a.node.id, b.parent.id);
        if (cycle) r = cycle < 0 ? cycle : -EINVAL;
    }
    if (!r && flags == RENAME_EXCHANGE && b.node.kind == S_IFDIR) {
        int cycle = mdi_ancestor(s, b.node.id, a.parent.id);
        if (cycle) r = cycle < 0 ? cycle : -EINVAL;
    }
    if (!r) r = mdi_parent_writable(s, &a.parent);
    if (!r) r = mdi_parent_writable(s, &b.parent);
    if (!r) r = mdi_sticky(s, &a);
    if (!r && b.exists) r = mdi_sticky(s, &b);
    if (!r && b.exists && flags!=RENAME_EXCHANGE) r=mdi_super_preserve_writer(s,&b.node);
    int native_name=!r && a.node.native ? mdi_overlay_native_name(s,&b.parent,b.name) : -EOPNOTSUPP;
    if (!r && native_name && native_name!=-EOPNOTSUPP) r=native_name;
    if (!r && a.node.native && !native_name && b.parent.mode>=0 && !b.parent.source
            && b.parent.attached && (!b.exists || b.node.native)) {
        char target[NAME_MAX+34];
        int n=snprintf(target,sizeof(target),"%s/%s",b.parent.backing,b.name);
        if (n<0 || (size_t)n>=sizeof(target)) r=-ENAMETOOLONG;
        else {
            int from=mdi_directory_pin(s,&a.parent),to=mdi_directory_pin(s,&b.parent);
            if (renameat2(from<0 ? s->objects : from,from<0 ? a.node.backing : a.name,
                    to<0 ? s->objects : to,to<0 ? target : b.name,flags)) r=-errno;
        }
        return mdi_finish(s,r);
    }
    if (!r) r=mdi_leaf_promote(s,&a.node);
    if (!r && b.exists) r=mdi_leaf_promote(s,&b.node);
    if (!r) r=mdi_write(s);
    uint64_t source_entry=0,target_entry=0;
    if (!r) r=mdi_dentry_capture(s,&a,&source_entry);
    if (!r && flags==RENAME_EXCHANGE) r=mdi_dentry_capture(s,&b,&target_entry);
    if (!r) r = mdi_delete_name(s, a.parent.id, a.name);
    if (!r && b.exists) r = mdi_delete_name(s, b.parent.id, b.name);
    if (!r) r = mdi_add_name(s, b.parent.id, b.name, a.node.id);
    if (!r) r = mdi_reparent(s, &a.node, b.parent.id);
    if (!r && flags == RENAME_EXCHANGE) r = mdi_add_name(s, a.parent.id, a.name, b.node.id);
    if (!r && flags == RENAME_EXCHANGE) r = mdi_reparent(s, &b.node, a.parent.id);
    if (!r) r=mdi_dentry_move(s,source_entry,b.parent.id,b.name);
    if (!r && flags==RENAME_EXCHANGE) r=mdi_dentry_move(s,target_entry,a.parent.id,a.name);
    if (!r && b.exists && flags != RENAME_EXCHANGE) r = mdi_removed_event(s, &b, 0);
    unsigned cookie = 0;
    if (!r) r = mdi_event(s, a.parent.id, &a.node, a.name, IN_MOVED_FROM, &cookie);
    if (!r) r = mdi_event(s, b.parent.id, &a.node, b.name, IN_MOVED_TO, &cookie);
    if (!r) r = mdi_event(s, NULL, &a.node, NULL, IN_MOVE_SELF, NULL);
    cookie = 0;
    if (!r && flags == RENAME_EXCHANGE) r = mdi_event(s, b.parent.id, &b.node, b.name, IN_MOVED_FROM, &cookie);
    if (!r && flags == RENAME_EXCHANGE) r = mdi_event(s, a.parent.id, &b.node, a.name, IN_MOVED_TO, &cookie);
    if (!r && flags == RENAME_EXCHANGE) r = mdi_event(s, NULL, &b.node, NULL, IN_MOVE_SELF, NULL);
    return mdi_commit(s, r);
}
int md_inode_stat(struct md_inode_store *s, int dirfd, const char *path, int flags, struct stat *st) {
    if (!st) return -EFAULT;
    if (flags & ~AT_SYMLINK_NOFOLLOW) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk_stat(s, dirfd, path, flags & AT_SYMLINK_NOFOLLOW ? MDI_NOFOLLOW : MDI_FOLLOW, &loc, st);
    if (!r && s->stat_location) {
        struct mdi_stat_location *p=s->stat_location;
        p->device=loc.node.device; p->inode=loc.node.inode;
        p->root=!strcmp(loc.node.id,s->lifetime->scope);
        if (p->path && mdi_location_path(s,&loc,p->path,PATH_MAX)) p->path[0]=0;
    }
    return mdi_finish(s, r);
}
int md_inode_fstat_origin(struct md_inode_store *s, int fd, struct stat *st, int native_leaf) {
    if (!st) return -EFAULT;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fstat_origin(s, fd, &node, st, native_leaf && !s->stat_location);
    if (!r && s->stat_location) {
        struct mdi_stat_location *p=s->stat_location;
        p->device=node.device; p->inode=node.inode;
        p->root=!strcmp(node.id,s->lifetime->scope);
        if (p->path && mdi_node_path(s,&node,p->path,PATH_MAX)) p->path[0]=0;
    }
    return mdi_finish(s, r);
}
int md_inode_fstat(struct md_inode_store *s, int fd, struct stat *st) {
    return md_inode_fstat_origin(s,fd,st,0);
}
int md_inode_list(struct md_inode_store *s, int dirfd, const char *path,
        int (*visit)(const char *, void *), void *context) {
    if (!visit) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_FOLLOW, 0, &loc);
    if (!r) r = mdi_access(s, &loc.node, R_OK);
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "SELECT name FROM names WHERE parent=?1 ORDER BY name", &q);
    if (!r) r = mdi_bind_id(q, 1, loc.node.id);
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc != SQLITE_ROW) { r = mdi_sql_error(rc); break; }
        int size = sqlite3_column_bytes(q, 0);
        if (size <= 0 || size > NAME_MAX) { r = -EIO; break; }
        char name[NAME_MAX+1];
        memcpy(name, sqlite3_column_blob(q, 0), (size_t)size); name[size] = 0;
        if (memchr(name, 0, (size_t)size) || mdi_name_valid(name)) { r = -EIO; break; }
        r = visit(name, context);
    }
    sqlite3_finalize(q);
    if (!r) r=mdi_leaf_list(s,&loc.node,visit,context);
    return mdi_finish(s, r);
}
