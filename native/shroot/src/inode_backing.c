#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_view.h"
#include "inode_overlay.h"
#include "inode_native.h"
#include "inode_leaf.h"
#include "inode_xattr.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/file.h>
#include <sys/xattr.h>
#include <unistd.h>

int mdi_backing_directory(struct md_inode_store *s, const struct mdi_node *node) {
    int fd = node->source ? s->lifetime->sources[node->source] : s->objects;
    return fd < 0 ? -ESTALE : fd;
}
int mdi_backing_path(struct md_inode_store *s,const struct mdi_node *node,char *out,size_t size) {
    int dir=node->descriptor ? node->descriptor-1 : mdi_backing_directory(s,node);
    if (dir<0) return dir;
    int n=node->descriptor ? snprintf(out,size,"/proc/self/fd/%d",dir)
        : snprintf(out,size,"/proc/self/fd/%d/%s",dir,node->backing);
    return n<0 ? -EIO : (size_t)n>=size ? -ERANGE : 0;
}
int mdi_backing_open(struct md_inode_store *s,const struct mdi_node *node,int flags) {
    int fd;
    if (node->descriptor) {
        return md_open_pinned(node->descriptor-1,flags);
    } else {
        int dir=mdi_backing_directory(s,node);
        if (dir<0) return dir;
        fd=openat(dir,node->backing,flags|O_NOFOLLOW|O_CLOEXEC);
    }
    return fd<0 ? -errno : fd;
}
static int descriptor_source(struct md_inode_store *s, int fd, char *out, size_t capacity) {
    struct stat opened, named;
    if (fstat(fd,&opened)) return -errno;
    char proc[64], path[PATH_MAX];
    snprintf(proc,sizeof(proc),"/proc/self/fd/%d",fd);
    ssize_t n=readlink(proc,path,sizeof(path)-1);
    if (n<0) return -errno;
    if ((size_t)n==sizeof(path)-1) return -ENAMETOOLONG;
    path[n]=0;
    const char *name=strrchr(path,'/');
    if (!name || strlen(++name)!=32 || strspn(name,"0123456789abcdef")!=32) return -EXDEV;
    for (unsigned i=0;i<MDI_SOURCES;++i) {
        int directory=i ? s->lifetime->sources[i] : s->objects;
        if (directory<0) continue;
        if (fstatat(directory,name,&named,AT_SYMLINK_NOFOLLOW)) {
            if (errno==ENOENT) continue;
            return -errno;
        }
        if (named.st_dev!=opened.st_dev || named.st_ino!=opened.st_ino) continue;
        int length=snprintf(out,capacity,"/proc/%d/fd/%d/%s",getpid(),directory,name);
        return length<0 ? -EIO : (size_t)length>=capacity ? -ERANGE : 0;
    }
    return -EXDEV;
}
int md_inode_descriptor_source(struct md_inode_store *s,int fd,char *out,size_t capacity,int promote) {
    int r=descriptor_source(s,fd,out,capacity);
    /* Ordinary callers can receive the exact FD without a durable publication.
     * Restricted callers may need a stable name to avoid a filtered recvmsg. */
    if (r!=-EXDEV || !promote) return r;
    r=mdi_begin(s,0);
    if (r) return r;
    struct mdi_node node;
    r=mdi_fd(s,fd,&node);
    if (r==-ENOENT) r=-EXDEV;
    if (!r && node.native && !node.attached) r=-EXDEV;
    if (!r && node.native) r=mdi_leaf_promote(s,&node);
    r=mdi_finish(s,r);
    if (r==-EROFS) r=-EXDEV;
    return r ? r : descriptor_source(s,fd,out,capacity);
}
int mdi_sources_open(struct md_inode_store *s) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT id,path,device,inode FROM sources WHERE scope=?1", &q);
    if (!r) r=mdi_bind_id(q,1,s->lifetime->scope);
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        int id = sqlite3_column_int(q, 0);
        const char *path = (const char *)sqlite3_column_text(q, 1);
        if (id <= 0 || id >= MDI_SOURCES || !path || path[0] != '/') { r = -EIO; break; }
        if (s->lifetime->sources[id] >= 0) continue;
        int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) { r = -errno; break; }
        int lease = openat(fd, "..", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (lease < 0) r = -errno;
        if (!r && flock(lease, LOCK_SH | LOCK_NB)) r = errno == EWOULDBLOCK ? -EBUSY : -errno;
        struct stat st;
        if (!r && fstat(fd, &st)) r = -errno;
        if (!r && (st.st_dev != (dev_t)sqlite3_column_int64(q, 2) || st.st_ino != (ino_t)sqlite3_column_int64(q, 3)))
            r = -ESTALE;
        struct stat named;
        if (!r && (fstatat(lease, "objects", &named, AT_SYMLINK_NOFOLLOW)
                || !st.st_nlink || named.st_dev != st.st_dev || named.st_ino != st.st_ino)) r = -ESTALE;
        if (r) { close(fd); if (lease >= 0) close(lease); }
        else { s->lifetime->sources[id] = fd; s->lifetime->source_leases[id] = lease; }
    }
    sqlite3_finalize(q); return r;
}

int mdi_source_register(struct md_inode_store *s, int objects, int *id) {
    char proc[64], path[PATH_MAX];
    snprintf(proc, sizeof(proc), "/proc/self/fd/%d", objects);
    if (!realpath(proc, path)) return -errno;
    struct stat st;
    if (fstat(objects, &st)) return -errno;
    struct stat local;
    if (fstat(s->objects,&local)) return -errno;
    if (st.st_dev==local.st_dev && st.st_ino==local.st_ino) { *id=0; return 0; }
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "INSERT INTO sources(id,path,device,inode,scope) VALUES("
        "coalesce((SELECT id FROM sources WHERE scope=?4 AND device=?2 AND inode=?3),"
        "(SELECT coalesce(max(id),0)+1 FROM sources WHERE scope=?4)),?1,?2,?3,?4) "
        "ON CONFLICT(scope,device,inode) DO UPDATE SET path=excluded.path RETURNING id", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_text(q, 1, path, -1, SQLITE_STATIC));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, (sqlite3_int64)st.st_ino));
    if (!r) r=mdi_bind_id(q,4,s->lifetime->scope);
    if (!r) {
        int rc = mdi_step(s, q);
        if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
        else *id = sqlite3_column_int(q, 0);
    }
    sqlite3_finalize(q); return r;
}

int mdi_import_shared(struct md_inode_store *s, int source, const char *backing, struct mdi_node *node) {
    if (source < 0 || source >= MDI_SOURCES || (source && s->lifetime->sources[source] < 0)
            || strlen(backing) != 32 || strspn(backing, "0123456789abcdef") != 32) return -EINVAL;
    struct stat st;
    if (fstatat(source ? s->lifetime->sources[source] : s->objects, backing, &st, AT_SYMLINK_NOFOLLOW)) return -errno;
    if (!S_ISREG(st.st_mode)) return -EINVAL;
    char id[33];
    int r = mdi_random_id(id);
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "INSERT INTO objects(object,kind,device,inode,backing,shared,source,logical_inode,logical_device,scope) "
        "VALUES(?1,32768,?2,?3,?4,1,?5,?3,?2,?6)", &q);
    if (!r) r = mdi_bind_id(q, 1, id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, (sqlite3_int64)st.st_ino));
    if (!r) r = mdi_bind_id(q, 4, backing);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 5, source));
    if (!r) r = mdi_bind_id(q,6,s->lifetime->scope);
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    sqlite3_finalize(q);
    if (!r) r = mdi_node(s, id, node);
    return r;
}

static int copy_attributes(int source, int destination,int overlay) {
    ssize_t size = flistxattr(source, NULL, 0);
    if (size < 0) return -errno;
    if (!size) return 0;
    if (size > 65536) return -E2BIG;
    char *names = malloc((size_t)size);
    if (!names) return -ENOMEM;
    int r = flistxattr(source, names, (size_t)size) != size ? -ESTALE : 0;
    for (size_t at = 0; !r && at < (size_t)size;) {
        const char *name = names + at;
        size_t length = strnlen(name, (size_t)size-at);
        if (length == (size_t)size-at) { r = -EIO; break; }
        at += length+1;
        if (!strcmp(name, "security.selinux") || mdi_native_private_attribute(name)
                || (overlay && mdi_overlay_private_attribute(name))) continue;
        ssize_t count = fgetxattr(source, name, NULL, 0);
        if (count < 0 || count > 65536) { r = count < 0 ? -errno : -E2BIG; break; }
        void *value = malloc(count ? (size_t)count : 1);
        if (!value) { r = -ENOMEM; break; }
        if (fgetxattr(source, name, value, (size_t)count) != count) r = -ESTALE;
        if (!r && fsetxattr(destination, name, value, (size_t)count, 0)) r = -errno;
        free(value);
    }
    free(names); return r;
}
int mdi_copy_attributes(int source,int destination) { return copy_attributes(source,destination,0); }
static int replace_attributes(int source, int destination,int overlay) {
    struct stat a,b;
    if (fstat(source,&a) || fstat(destination,&b)) return -errno;
    if (a.st_dev==b.st_dev && a.st_ino==b.st_ino) return 0;
    ssize_t size=flistxattr(destination,NULL,0);
    if (size<0) return -errno;
    if (size>65536) return -E2BIG;
    char *names=size ? malloc((size_t)size) : NULL;
    if (size && !names) return -ENOMEM;
    int r=size && flistxattr(destination,names,(size_t)size)!=size ? -ESTALE : 0;
    for (size_t at=0; !r && at<(size_t)size;) {
        const char *name=names+at;
        size_t length=strnlen(name,(size_t)size-at);
        if (length==(size_t)size-at) { r=-EIO; break; }
        at+=length+1;
        if (strcmp(name,"security.selinux") && !mdi_native_private_attribute(name)
                && !(overlay && mdi_overlay_private_attribute(name))
                && fremovexattr(destination,name)) r=-errno;
    }
    free(names);
    return r ? r : copy_attributes(source,destination,overlay);
}
int mdi_replace_attributes(int source,int destination) { return replace_attributes(source,destination,0); }
int mdi_overlay_replace_attributes(int source,int destination) { return replace_attributes(source,destination,1); }
static int copy_bytes(struct md_inode_store *s, int source, int destination, off_t size) {
    if (!s->reflink_unavailable) {
        if (!ioctl(destination, FICLONE, source)) return 0;
        if (errno != EOPNOTSUPP && errno != ENOTTY && errno != EXDEV && errno != EINVAL
                && errno != EACCES && errno != EPERM) return -errno;
        if (errno != EXDEV) s->reflink_unavailable = 1;
    }
    unsigned char buffer[65536];
    for (off_t at = 0; at < size;) {
        size_t take = (uint64_t)(size-at) > sizeof(buffer) ? sizeof(buffer) : (size_t)(size-at);
        ssize_t n = pread(source, buffer, take, at);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return n < 0 ? -errno : -ESTALE;
        for (ssize_t written = 0; written < n;) {
            ssize_t next = pwrite(destination, buffer+written, (size_t)(n-written), at+written);
            if (next < 0 && errno == EINTR) continue;
            if (next <= 0) return next < 0 ? -errno : -EIO;
            written += next;
        }
        at += n;
    }
    return 0;
}
int mdi_copy_body(struct md_inode_store *s, int source, int destination, const struct stat *before) {
    int r=copy_bytes(s,source,destination,before->st_size);
    if (!r) r=mdi_copy_attributes(source,destination);
    struct timespec times[2]={before->st_atim,before->st_mtim};
    if (!r && (fchmod(destination,before->st_mode&01777) || futimens(destination,times))) r=-errno;
    if (!r) r=mdi_sync(s,destination);
    return r;
}
int mdi_copy_up(struct md_inode_store *s, struct mdi_node *node) {
    if (mdi_readonly(s)) return -EROFS;
    int mark=s->overlay ? mdi_overlay_touch(s,node) : 0;
    if (mark) return mark;
    if (!node->shared) return 0;
    if (node->kind != S_IFREG) return -EIO;
    int directory = mdi_backing_directory(s, node);
    if (directory < 0) return directory;
    int source = openat(directory, node->backing, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (source < 0) return -errno;
    struct stat before = {0}, after = {0};
    int r = fstat(source, &before) ? -errno : 0;
    if (!r && (before.st_dev != node->device || before.st_ino != node->inode || !S_ISREG(before.st_mode))) r = -ESTALE;
    char backing[33], temporary[48] = {0};
    if (!r) r = mdi_random_id(backing);
    int destination = -1;
    if (!r) snprintf(temporary, sizeof(temporary), ".copy-%s", backing);
    /* Prepare outside the watched backing directory, including the final close.
     * Publication exposes only future application IO to namespace observers. */
    if (!r && (destination = openat(s->lifetime->root, temporary, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0)
        r = -errno;
    if (!r) r = mdi_copy_body(s,source,destination,&before);
    if (!r && fstat(destination, &after)) r = -errno;
    if (destination >= 0) { if (close(destination) && !r) r = -errno; destination = -1; }
    if (!r && renameat(s->lifetime->root, temporary, s->objects, backing)) r = -errno;
    if (!r) r=mdi_sync(s,s->objects);
    if (!r) r=mdi_sync(s,s->lifetime->root);
    if (r && *temporary) unlinkat(s->lifetime->root, temporary, 0);
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "UPDATE objects SET backing=?1,device=?2,inode=?3,shared=0,source=0 WHERE object=?4", &q);
    if (!r) r = mdi_bind_id(q, 1, backing);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)after.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, (sqlite3_int64)after.st_ino));
    if (!r) r = mdi_bind_id(q, 4, node->id);
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    sqlite3_finalize(q);
    if (!r) r = mdi_node(s, node->id, node);
    close(source);
    /* Both backing names survive a failed/uncertain commit. A preexisting read
     * descriptor keeps its lower snapshot, as with overlayfs copy-up. */
    return r;
}
int mdi_bind_body(struct md_inode_store *s,const struct mdi_node *from,struct mdi_node *to) {
    if (from->source || from->shared) return -EIO;
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,"UPDATE objects SET backing=?1,device=?2,inode=?3,source=0,shared=0 WHERE object=?4",&q);
    if (!r) r=mdi_bind_id(q,1,from->backing);
    if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,2,(sqlite3_int64)from->device));
    if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,3,(sqlite3_int64)from->inode));
    if (!r) r=mdi_bind_id(q,4,to->id);
    if (!r) r=mdi_sql_error(mdi_step(s,q));
    sqlite3_finalize(q);
    return r ? r : mdi_node(s,to->id,to);
}
int md_inode_store_seal(struct md_inode_store *s) {
    if (strcmp(s->lifetime->scope,MDI_ROOT)) return -EINVAL;
    if (s->readonly) return 0;
    int r = mdi_begin(s, 1);
    if (r) return r;
    r=mdi_leaf_materialize(s);
    if (!r) r=mdi_views_collect(s);
    if (!r) r=mdi_views_busy(s);
    if (!r && !s->lifetime->temporary && syncfs(s->objects)) r=-errno;
    if (!r) r = mdi_sql(s, "UPDATE properties SET value=1 WHERE key='sealed'");
    s->sync_requested=1;
    r = mdi_commit(s, r);
    if (!r) s->readonly = 1;
    return r;
}
int md_inode_reopen(struct md_inode_store *s, int original, int flags, int mutable) {
    struct md_open_completion completion;
    int fd = md_inode_plan_reopen(s, original, flags, mutable, &completion, 0);
    return md_complete_open(fd, flags | O_CLOEXEC, completion);
}
int md_inode_plan_reopen(struct md_inode_store *s, int original, int flags, int mutable,
        struct md_open_completion *completion, int native_plan) {
    *completion = (struct md_open_completion){0};
    if (native_plan && (mutable || !md_open_native_plan(flags,0))) return -EXDEV;
    if (flags & ~(O_PATH | O_CLOEXEC | O_NONBLOCK | O_DIRECTORY | O_LARGEFILE | O_ACCMODE
            | O_APPEND | O_TRUNC | O_NOFOLLOW | O_SYNC | O_DSYNC | O_DIRECT | O_NOCTTY | O_NOATIME)) return -EINVAL;
    if ((flags & O_ACCMODE) == O_ACCMODE) return -EINVAL;
    if ((flags & O_TRUNC) && !(flags & O_ACCMODE)) return -EINVAL;
    if (flags & O_PATH) flags &= O_PATH | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW;
    int write = mutable || (flags & (O_WRONLY | O_RDWR | O_TRUNC));
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, original, &node);
    if (!r && (flags & O_DIRECTORY) && node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && node.kind==S_IFDIR && (flags&(O_WRONLY|O_RDWR|O_TRUNC))) r=-EISDIR;
    if (!r && write && (mutable || node.kind==S_IFREG) && mdi_readonly(s)) r=-EROFS;
    int device=!r && (node.kind==S_IFCHR || node.kind==S_IFBLK) && !(flags&O_PATH);
    if (device && s->nodev) r=-EACCES;
    if (!r) r = mdi_open_permission(s, &node, flags,NULL);
    if (!r) r=mdi_landlock_object(s,&node,s->image_open ? MD_LL_EXECUTE : md_landlock_open_access(flags,node.kind));
    if (!r && mutable && (flags & O_PATH) && s->identity) {
        struct stat st;
        r = mdi_stat(s, &node, &st);
        if (!r && st.st_uid != s->identity->uid.fs
                && !md_identity_file_capable(s->identity,st.st_uid,st.st_gid,CAP_FOWNER)) r = -EPERM;
    }
    if (!r && native_plan && !md_open_native_kind(flags,node.kind)) r=-EXDEV;
    int copy_up=!r && write && (mutable || node.kind==S_IFREG)
        && (node.shared || (s->overlay && !node.native));
    int deferred=!r && node.kind==S_IFIFO && !(flags&O_PATH);
    if (!r && s->read_depth>1 && (copy_up || deferred)) {
        completion->kind=MD_OPEN_NEEDS_WRITE;
        return mdi_finish(s,-EAGAIN);
    }
    if (copy_up) {
        r=mdi_write(s);
        if (!r) r=mdi_copy_up(s,&node);
    }
    int fd = -1;
    int directory = r ? -1 : mdi_backing_directory(s, &node);
    if (!r && directory < 0) r = directory;
    if (!r && (fd=mdi_backing_open(s,&node,deferred || device || native_plan ? O_PATH : flags))<0) r=fd;
    struct stat st;
    if (!r && native_plan) r=mdi_backing_fstat(&node,fd,&st);
    if (!r && native_plan) r=md_open_plan_access(fd,flags);
    if (!r && (flags&O_DSYNC) && (!native_plan || s->write_transaction)) s->sync_requested=1;
    r = mdi_finish(s, r);
    if (r && fd >= 0) close(fd);
    if (!r && deferred) return mdi_fifo_prepare(s, fd, completion);
    if (!r && native_plan) *completion=(struct md_open_completion){.kind=MD_OPEN_PLAN,
        .backing_device=st.st_dev,.backing_inode=st.st_ino,.mode=st.st_mode&S_IFMT};
    if (!r) completion->native_leaf=node.native;
    if (!r && device) *completion=(struct md_open_completion){.kind=MD_OPEN_DEVICE,.device=node.rdev,.mode=node.kind};
    return r ? r : fd;
}
