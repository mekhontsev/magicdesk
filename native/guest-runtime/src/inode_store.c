#define _GNU_SOURCE
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
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
    struct stat st;
    if (!r) r = mdi_stat(s, &node, &st);
    if (!r && node.kind != S_IFREG) r = -EINVAL;
    int fd = -1;
    if (!r && (fd = openat(s->objects, id, flags | O_NOFOLLOW | O_CLOEXEC)) < 0) r = -errno;
    r = mdi_finish(s, r);
    if (r && fd >= 0) close(fd);
    return r ? r : fd;
}

static int create_node(struct md_inode_store *s, int dirfd, const char *path,
        mode_t kind, mode_t mode, const char *target) {
    if (mode & ~01777) return -ENOTSUP;
    int r = mdi_begin(s, 1);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_ENTRY, 1, &loc);
    if (!r && loc.trailing && kind == S_IFREG) r = -EISDIR;
    if (!r && loc.exists) r = -EEXIST;
    if (!r && loc.trailing && kind != S_IFDIR) r = -ENOENT;
    if (!r) r = mdi_parent_writable(s, &loc.parent);
    struct mdi_node node;
    int fd = -1;
    if (!r) r = mdi_allocate(s, kind, mode, O_RDWR, target,
        kind == S_IFDIR ? loc.parent.id : NULL, &node, &fd);
    if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, node.id);
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
    return create_node(s, dirfd, path, S_IFDIR, mode, NULL);
}
int md_inode_symlink(struct md_inode_store *s, const char *target, int dirfd, const char *path) {
    if (!target) return -EFAULT;
    if (!*target) return -ENOENT;
    if (strnlen(target, PATH_MAX) == PATH_MAX) return -ENAMETOOLONG;
    return create_node(s, dirfd, path, S_IFLNK, 0777, target);
}
ssize_t md_inode_readlink(struct md_inode_store *s, int dirfd, const char *path, char *out, size_t size) {
    if (!out) return -EFAULT;
    if (!size) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_NOFOLLOW, 0, &loc);
    if (!r && loc.node.kind != S_IFLNK) r = -EINVAL;
    ssize_t n = -1;
    if (!r && (n = readlinkat(s->objects, loc.node.id, out, size)) < 0) r = -errno;
    r = mdi_finish(s, r);
    return r ? r : n;
}
int md_inode_open(struct md_inode_store *s, int dirfd, const char *path, int flags, mode_t mode) {
    return md_inode_open_resolved(s, dirfd, path, flags, mode, 0);
}
int md_inode_open_resolved(struct md_inode_store *s, int dirfd, const char *path, int flags, mode_t mode,
        uint64_t resolve) {
    if (flags & ~(O_ACCMODE | O_CLOEXEC | O_APPEND | O_TRUNC | O_NOFOLLOW | O_DIRECTORY | O_PATH
            | O_CREAT | O_EXCL | O_NONBLOCK | O_NOCTTY | O_LARGEFILE | O_SYNC | O_DSYNC)) return -ENOTSUP;
    if ((flags & O_ACCMODE) == O_ACCMODE) return -EINVAL;
    if (flags & O_PATH) flags &= O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    if ((flags & O_TRUNC) && !(flags & (O_WRONLY | O_RDWR))) return -EINVAL;
    int create = flags & O_CREAT;
    if (create && (flags & O_DIRECTORY)) return -EINVAL;
    if (create && (mode & ~01777)) return -ENOTSUP;
    int r = mdi_begin(s, !!create);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk_resolved(s, dirfd, path, create && (flags & O_EXCL) ? MDI_ENTRY
        : flags & O_NOFOLLOW ? MDI_NOFOLLOW : MDI_FOLLOW, !!create, resolve, &loc);
    int fd = -1;
    if (!r && create && loc.trailing) r = -EISDIR;
    if (!r && create && (flags & O_EXCL) && loc.exists) r = -EEXIST;
    if (!r && !loc.exists) {
        if (loc.trailing) r = -EISDIR;
        if (!r) r = mdi_parent_writable(s, &loc.parent);
        if (!r) r = mdi_allocate(s, S_IFREG, mode, flags, NULL, NULL, &loc.node, &fd);
        if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, loc.node.id);
        r = mdi_commit(s, r);
        if (r && fd >= 0) close(fd);
        return r ? r : fd;
    }
    if (!r && (flags & O_DIRECTORY) && loc.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && loc.node.kind == S_IFLNK && !(flags & O_PATH)) r = -ELOOP;
    if (!r && loc.node.kind == S_IFSOCK && !(flags & O_PATH)) r = -ENXIO;
    if (!r && loc.node.kind == S_IFDIR && (flags & (O_WRONLY | O_RDWR | O_TRUNC | O_CREAT))) r = -EISDIR;
    struct stat st;
    if (!r) r = mdi_stat(s, &loc.node, &st);
    if (!r && (fd = openat(s->objects, loc.node.id, (flags & ~(O_CREAT | O_EXCL)) | O_CLOEXEC | O_NOFOLLOW)) < 0) r = -errno;
    r = mdi_finish(s, r);
    if (r) { if (fd >= 0) close(fd); return r; }
    return fd;
}
int md_inode_link(struct md_inode_store *s, int sourcefd, const char *source,
        int targetfd, const char *target, int flags) {
    if (flags & ~AT_SYMLINK_FOLLOW) return -EINVAL;
    int r = mdi_begin(s, 1);
    if (r) return r;
    struct mdi_location a, b;
    r = mdi_walk(s, sourcefd, source, flags & AT_SYMLINK_FOLLOW ? MDI_FOLLOW : MDI_NOFOLLOW, 0, &a);
    if (!r) r = mdi_walk(s, targetfd, target, MDI_ENTRY, 1, &b);
    if (!r && a.node.kind == S_IFDIR) r = -EPERM;
    if (!r && b.exists) r = -EEXIST;
    if (!r && b.trailing) r = -ENOENT;
    if (!r) r = mdi_parent_writable(s, &b.parent);
    if (!r) r = mdi_add_name(s, b.parent.id, b.name, a.node.id);
    return mdi_commit(s, r);
}
int md_inode_unlink(struct md_inode_store *s, int dirfd, const char *path, int flags) {
    if (flags & ~AT_REMOVEDIR) return -EINVAL;
    int r = mdi_begin(s, 1);
    if (r) return r;
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
    if (!r) r = mdi_delete_name(s, loc.parent.id, loc.name);
    return mdi_commit(s, r);
}
int md_inode_rename(struct md_inode_store *s, int sourcefd, const char *source,
        int targetfd, const char *target, unsigned flags) {
    if (flags != 0 && flags != RENAME_NOREPLACE && flags != RENAME_EXCHANGE) return -EINVAL;
    int r = mdi_begin(s, 1);
    if (r) return r;
    struct mdi_location a, b;
    r = mdi_walk(s, sourcefd, source, MDI_ENTRY, 0, &a);
    if (!r) r = mdi_walk(s, targetfd, target, MDI_ENTRY, 1, &b);
    if (!r && (a.special || b.special)) r = -EBUSY;
    if (!r && a.trailing && a.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && b.trailing && a.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && b.trailing && b.exists && b.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r && flags == RENAME_NOREPLACE && b.exists) r = -EEXIST;
    if (!r && flags == RENAME_EXCHANGE && !b.exists) r = -ENOENT;
    /* Kernel rename between two names of the same inode preserves both names. */
    if (!r && b.exists && !strcmp(a.node.id, b.node.id)) return mdi_finish(s, 0);
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
    if (!r) r = mdi_delete_name(s, a.parent.id, a.name);
    if (!r && b.exists) r = mdi_delete_name(s, b.parent.id, b.name);
    if (!r) r = mdi_add_name(s, b.parent.id, b.name, a.node.id);
    if (!r) r = mdi_reparent(s, &a.node, b.parent.id);
    if (!r && flags == RENAME_EXCHANGE) r = mdi_add_name(s, a.parent.id, a.name, b.node.id);
    if (!r && flags == RENAME_EXCHANGE) r = mdi_reparent(s, &b.node, a.parent.id);
    return mdi_commit(s, r);
}
int md_inode_stat(struct md_inode_store *s, int dirfd, const char *path, int flags, struct stat *st) {
    if (!st) return -EFAULT;
    if (flags & ~AT_SYMLINK_NOFOLLOW) return -EINVAL;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, flags & AT_SYMLINK_NOFOLLOW ? MDI_NOFOLLOW : MDI_FOLLOW, 0, &loc);
    if (!r) r = mdi_stat(s, &loc.node, st);
    return mdi_finish(s, r);
}
int md_inode_fstat(struct md_inode_store *s, int fd, struct stat *st) {
    if (!st) return -EFAULT;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, fd, &node);
    if (!r) r = mdi_stat(s, &node, st);
    return mdi_finish(s, r);
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
        int rc = sqlite3_step(q);
        if (rc != SQLITE_ROW) { r = mdi_sql_error(rc); break; }
        int size = sqlite3_column_bytes(q, 0);
        if (size <= 0 || size > NAME_MAX) { r = -EIO; break; }
        char name[NAME_MAX+1];
        memcpy(name, sqlite3_column_blob(q, 0), (size_t)size); name[size] = 0;
        if (memchr(name, 0, (size_t)size) || mdi_name_valid(name)) { r = -EIO; break; }
        r = visit(name, context);
    }
    sqlite3_finalize(q);
    return mdi_finish(s, r);
}
