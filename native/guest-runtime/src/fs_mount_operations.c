#define _GNU_SOURCE
#include "fs_mount_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static int writable(unsigned flags) {
    return !(flags & O_PATH) && (flags & (O_ACCMODE | O_TRUNC | O_CREAT));
}
static int reopen(struct md_filesystem *fs, struct md_view_object *p, int flags, int mutable) {
    if (flags & ~(O_PATH | O_CLOEXEC | O_NONBLOCK | O_DIRECTORY | O_LARGEFILE | O_ACCMODE
            | O_APPEND | O_TRUNC | O_NOFOLLOW | O_SYNC | O_DSYNC)) return -EINVAL;
    if ((flags & O_ACCMODE) == O_ACCMODE || ((flags & O_TRUNC) && !(flags & O_ACCMODE))) return -EINVAL;
    if (flags & O_PATH) flags &= O_PATH | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW;
    if (fs->mounts->mounts[p->mount].readonly && (mutable || writable(flags))) return -EROFS;
    struct stat st;
    if (fstat(p->fd, &st)) return -errno;
    if (S_ISLNK(st.st_mode)) {
        if (!(flags & O_PATH) || !(flags & O_NOFOLLOW)) return -ELOOP;
        int fd = fcntl(p->fd, F_DUPFD_CLOEXEC, 3); return fd < 0 ? -errno : fd;
    }
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", p->fd);
    int fd = open(path, (flags & ~O_NOFOLLOW) | O_CLOEXEC);
    return fd < 0 ? -errno : fd;
}
static void result_stat(struct md_fs_result *out, int fd) {
    struct stat st;
    if (fstat(fd, &st)) out->error = -errno;
    else md_fs_stat_info(&st, &out->info);
}
static int record(struct md_filesystem *fs, int fd, int mount, struct md_fs_result *out) {
    if (fd < 0) return -errno;
    struct md_view_object *object;
    int r = md_view_record(fs, fd, mount, &object);
    if (r) close(fd); else out->fd = fd;
    return r;
}
static int descriptor(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_view_object *p = NULL;
    int r = md_view_identity(fs, q->directory[0], &p);
    if (r == -EXDEV) return 0;
    if (r) { out->error = r; return 1; }
    size_t capacity = output ? output->capacity : 0;
    void *data = output ? output->data : NULL;
    switch (q->operation) {
    case MD_FS_FSTAT: result_stat(out, q->directory[0]); break;
    case MD_FS_REOPEN:
        r = reopen(fs, p, (int)q->flags, (int)q->mode);
        if (r >= 0) { out->fd = r; r = 0; }
        break;
    case MD_FS_OBJECT_ID:
        if (!data || capacity < sizeof(p->id)) r = -ERANGE;
        else { memcpy(data, p->id, sizeof(p->id)); out->size = sizeof(p->id); }
        break;
    case MD_FS_PATH:
        if (!data || !capacity) r = -ERANGE;
        else {
            struct stat st;
            if (fstat(q->directory[0], &st)) r = -errno;
            else if (!S_ISDIR(st.st_mode)) r = -ENOTDIR;
            else r = md_view_path(fs, q->directory[0], p->mount, data, capacity);
            if (!r) out->size = strlen(data)+1;
        }
        break;
    case MD_FS_SEEKDIR: {
        off_t pos = lseek(q->directory[0], q->offset, (int)q->flags);
        if (pos < 0) r = -errno; else out->position = pos;
        break;
    }
    case MD_FS_GETDENTS: {
        if (!data || !capacity) { r = -EINVAL; break; }
        int fd = q->directory[0];
        off_t before = lseek(fd, 0, SEEK_CUR);
        if (before < 0) { r = -errno; break; }
        size_t count = q->capacity < capacity ? q->capacity : capacity;
        ssize_t n = syscall(SYS_getdents64, fd, data, count);
        if (n < 0) { r = -errno; break; }
        /* Native and inode directories share the same publish-before-commit
         * contract. Restore on rejected output; never redispatch the read. */
        if (output->deliver) r = output->deliver(output->context, data, (size_t)n);
        if (r) { if (lseek(fd, before, SEEK_SET) < 0) r = -errno; }
        else out->size = (size_t)n;
        break;
    }
    default: return 0;
    }
    if (r) out->error = r;
    return 1;
}
static int has_path(unsigned operation) {
    switch (operation) {
    case MD_FS_CREATE: case MD_FS_OPEN: case MD_FS_MKDIR: case MD_FS_SYMLINK:
    case MD_FS_READLINK: case MD_FS_LINK: case MD_FS_UNLINK: case MD_FS_RENAME:
    case MD_FS_STAT: case MD_FS_REALPATH: case MD_FS_OPEN_IMAGE:
    case MD_FS_SOCKET_BIND: case MD_FS_SOCKET_ADDRESS: return 1;
    default: return 0;
    }
}
static int follow(const struct md_fs_request *q) {
    switch (q->operation) {
    case MD_FS_OPEN: case MD_FS_OPEN_IMAGE:
        return (q->flags & (O_CREAT | O_EXCL)) == (O_CREAT | O_EXCL) ? -1 : !(q->flags & O_NOFOLLOW);
    case MD_FS_STAT: return !(q->flags & AT_SYMLINK_NOFOLLOW);
    case MD_FS_LINK: return !!(q->flags & AT_SYMLINK_FOLLOW);
    case MD_FS_CREATE: case MD_FS_MKDIR: case MD_FS_SYMLINK:
    case MD_FS_UNLINK: case MD_FS_RENAME: case MD_FS_SOCKET_BIND: return -1;
    case MD_FS_READLINK: return 0;
    default: return 1;
    }
}
static int missing(const struct md_fs_request *q) {
    if (q->operation == MD_FS_MKDIR) return 2;
    return q->operation == MD_FS_CREATE || q->operation == MD_FS_SYMLINK
        || q->operation == MD_FS_SOCKET_BIND || (q->operation == MD_FS_OPEN && (q->flags & O_CREAT));
}
static int mutates(const struct md_fs_request *q) {
    switch (q->operation) {
    case MD_FS_CREATE: case MD_FS_MKDIR: case MD_FS_SYMLINK: case MD_FS_LINK:
    case MD_FS_UNLINK: case MD_FS_RENAME: case MD_FS_SOCKET_BIND: return 1;
    case MD_FS_OPEN: return writable(q->flags);
    default: return 0;
    }
}
static int mounted_root(struct md_filesystem *fs, const struct md_view_location *p) {
    if (p->fd < 0 || p->mount < 0) return 0;
    struct stat st;
    return !fstat(p->fd, &st) && md_view_same(&st, &fs->mounts->mounts[p->mount].native);
}
static void native(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_view_location *a, struct md_view_location *b,
        struct md_fs_result *out, const struct md_fs_output *output) {
    int r = 0;
    void *data = output ? output->data : NULL;
    size_t capacity = output ? output->capacity : 0;
    switch (q->operation) {
    case MD_FS_CREATE: case MD_FS_OPEN: case MD_FS_OPEN_IMAGE: {
        int flags = q->operation == MD_FS_CREATE ? O_RDWR | O_CREAT | O_EXCL
            : q->operation == MD_FS_OPEN_IMAGE ? O_RDONLY : (int)q->flags;
        if (flags & ~(O_ACCMODE | O_CLOEXEC | O_APPEND | O_TRUNC | O_NOFOLLOW | O_DIRECTORY | O_PATH
                | O_CREAT | O_EXCL | O_NONBLOCK | O_NOCTTY | O_LARGEFILE | O_SYNC | O_DSYNC)) { r = -ENOTSUP; break; }
        if ((flags & O_ACCMODE) == O_ACCMODE) { r = -EINVAL; break; }
        if (q->operation == MD_FS_OPEN_IMAGE && (!data || capacity < sizeof(struct md_image_identity))) { r = -ERANGE; break; }
        if (flags & O_PATH) flags &= O_PATH | O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC;
        if (((flags & O_TRUNC) && !(flags & O_ACCMODE))
                || ((flags & O_CREAT) && (flags & O_DIRECTORY))) { r = -EINVAL; break; }
        if ((flags & O_CREAT) && (q->mode & ~01777)) { r = -ENOTSUP; break; }
        int fd = openat(a->parent, a->name, flags | O_NOFOLLOW | O_CLOEXEC, q->mode);
        r = record(fs, fd, a->mount, out);
        if (!r && q->operation == MD_FS_OPEN_IMAGE) {
            struct stat st;
            if (fstat(fd, &st)) r = -errno;
            else if (!S_ISREG(st.st_mode)) r = -EACCES;
            struct md_view_object *object;
            if (!r) r = md_view_identity(fs, fd, &object);
            struct md_image_identity *identity = data;
            if (!r) r = md_view_path(fs, fd, a->mount, identity->path, sizeof(identity->path));
            if (!r) { memcpy(identity->object, object->id, sizeof(identity->object)); out->size = offsetof(struct md_image_identity, path)+strlen(identity->path)+1; }
            if (r) { close(out->fd); out->fd = -1; }
        }
        break;
    }
    case MD_FS_STAT:
        if (q->flags & ~AT_SYMLINK_NOFOLLOW) r = -EINVAL;
        else result_stat(out, a->fd);
        break;
    case MD_FS_REALPATH:
        if (!data || !capacity) r = -ERANGE;
        else { r = md_view_path(fs, a->fd, a->mount, data, capacity); if (!r) out->size = strlen(data)+1; }
        break;
    case MD_FS_READLINK: {
        ssize_t n = data && capacity ? readlinkat(a->fd, "", data, capacity) : -1;
        if (n < 0) r = data && capacity ? -errno : -EINVAL; else out->size = (size_t)n;
        break;
    }
    case MD_FS_MKDIR: if (mkdirat(a->parent, a->name, q->mode)) r = -errno; break;
    case MD_FS_SYMLINK: if (symlinkat(q->path[1], a->parent, a->name)) r = -errno; break;
    case MD_FS_UNLINK: if (unlinkat(a->parent, a->name, (int)q->flags)) r = -errno; break;
    case MD_FS_RENAME: if (syscall(SYS_renameat2, a->parent, a->name, b->parent, b->name, q->flags)) r = -errno; break;
    case MD_FS_LINK:
        if (q->flags & ~AT_SYMLINK_FOLLOW) r = -EINVAL;
        else if (linkat(a->parent, a->name, b->parent, b->name, 0)) r = -errno;
        break;
    default: r = -EOPNOTSUPP; break;
    }
    if (r) out->error = r;
}
void md_fs_mounts_execute(struct md_filesystem *fs, const struct md_fs_request *q,
        struct md_fs_result *out, const struct md_fs_output *output) {
    *out = (struct md_fs_result){.fd=-1};
    if (q->operation == MD_FS_OPEN_OBJECT) {
        for (unsigned i = 0; i < MD_VIEW_BUCKETS; ++i) for (struct md_view_object *p = fs->mounts->table[i]; p; p = p->next) {
            if (!q->path[0] || strcmp(q->path[0], p->id)) continue;
            if (writable(q->flags)) { out->error = -EACCES; return; }
            int fd = reopen(fs, p, (int)q->flags, 0);
            if (fd < 0) out->error = fd; else out->fd = fd;
            return;
        }
    }
    if (!has_path(q->operation)) {
        switch (q->operation) {
        case MD_FS_FSTAT: case MD_FS_PATH: case MD_FS_REOPEN: case MD_FS_GETDENTS:
        case MD_FS_SEEKDIR: case MD_FS_OBJECT_ID:
            if (descriptor(fs, q, out, output)) return;
        }
        md_fs_inode_execute(fs, q, out, output); return;
    }
    struct md_view_location a = {.parent=-1,.fd=-1}, b = {.parent=-1,.fd=-1};
    int r = md_view_walk(fs, q->directory[0], q->path[0], follow(q), missing(q), q->resolve, &a);
    int pair = q->operation == MD_FS_LINK || q->operation == MD_FS_RENAME;
    if (!r && pair) r = md_view_walk(fs, q->directory[1], q->path[1], -1, 1, 0, &b);
    if (!r && pair && a.mount != b.mount) r = -EXDEV;
    if (!r && mutates(q) && a.mount >= 0 && fs->mounts->mounts[a.mount].readonly) r = -EROFS;
    if (!r && (q->operation == MD_FS_UNLINK || q->operation == MD_FS_RENAME)
            && (mounted_root(fs, &a) || (pair && mounted_root(fs, &b)))) r = -EBUSY;
    if (!r && a.mount >= 0) native(fs, q, &a, &b, out, output);
    else if (!r) {
        struct md_fs_request local = *q;
        local.directory[0] = a.parent; local.path[0] = a.name; local.resolve = 0;
        if (pair) { local.directory[1] = b.parent; local.path[1] = b.name; }
        md_fs_inode_execute(fs, &local, out, output);
    } else out->error = r;
    md_view_location_close(&a); md_view_location_close(&b);
}
