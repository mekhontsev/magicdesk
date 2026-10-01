#define _GNU_SOURCE
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/xattr.h>
#include <unistd.h>

int mdi_backing_directory(struct md_inode_store *s, const struct mdi_node *node) {
    int fd = node->source ? s->sources[node->source] : s->objects;
    return fd < 0 ? -ESTALE : fd;
}
int mdi_sources_open(struct md_inode_store *s) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT id,path,device,inode FROM sources", &q);
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        int id = sqlite3_column_int(q, 0);
        const char *path = (const char *)sqlite3_column_text(q, 1);
        if (id <= 0 || id >= MDI_SOURCES || !path || path[0] != '/') { r = -EIO; break; }
        if (s->sources[id] >= 0) continue;
        int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) { r = -errno; break; }
        struct stat st;
        if (fstat(fd, &st)) r = -errno;
        if (!r && (st.st_dev != (dev_t)sqlite3_column_int64(q, 2) || st.st_ino != (ino_t)sqlite3_column_int64(q, 3)))
            r = -ESTALE;
        if (r) close(fd); else s->sources[id] = fd;
    }
    sqlite3_finalize(q); return r;
}

static int attributes(int source, int destination) {
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
        if (!strcmp(name, "security.selinux")) continue;
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
int mdi_copy_up(struct md_inode_store *s, struct mdi_node *node) {
    if (s->readonly) return -EROFS;
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
    if (!r && (destination = openat(s->root, temporary, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0)
        r = -errno;
    if (!r) r = copy_bytes(s, source, destination, before.st_size);
    if (!r) r = attributes(source, destination);
    struct timespec times[2] = {before.st_atim, before.st_mtim};
    if (!r && (fchmod(destination, before.st_mode & 01777) || futimens(destination, times)
            || fsync(destination) || fstat(destination, &after))) r = -errno;
    if (destination >= 0) { if (close(destination) && !r) r = -errno; destination = -1; }
    if (!r && renameat(s->root, temporary, s->objects, backing)) r = -errno;
    if (!r && (fsync(s->objects) || fsync(s->root))) r = -errno;
    if (r && *temporary) unlinkat(s->root, temporary, 0);
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
int md_inode_store_seal(struct md_inode_store *s) {
    if (s->readonly) return 0;
    int r = mdi_begin(s, 1);
    if (r) return r;
    r = mdi_sql(s, "UPDATE properties SET value=1 WHERE key='sealed'");
    r = mdi_commit(s, r);
    if (!r) s->readonly = 1;
    return r;
}
int md_inode_reopen(struct md_inode_store *s, int original, int flags, int mutable) {
    if (flags & ~(O_PATH | O_CLOEXEC | O_NONBLOCK | O_DIRECTORY | O_LARGEFILE | O_ACCMODE
            | O_APPEND | O_TRUNC | O_NOFOLLOW | O_SYNC | O_DSYNC | O_DIRECT)) return -EINVAL;
    if ((flags & O_ACCMODE) == O_ACCMODE) return -EINVAL;
    if ((flags & O_TRUNC) && !(flags & O_ACCMODE)) return -EINVAL;
    if (flags & O_PATH) flags &= O_PATH | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW;
    int write = mutable || (flags & (O_WRONLY | O_RDWR | O_TRUNC));
    int r = mdi_begin(s, write);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, original, &node);
    if (!r && !(flags & O_PATH)) r = mdi_permission(s, &node,
        (flags & O_ACCMODE) == O_WRONLY ? W_OK : (flags & O_ACCMODE) == O_RDWR ? R_OK | W_OK : R_OK, 0);
    if (!r && mutable && (flags & O_PATH) && s->identity && !md_identity_capable(s->identity, CAP_FOWNER)) {
        struct stat st;
        r = mdi_stat(s, &node, &st);
        if (!r && st.st_uid != s->identity->uid.fs) r = -EPERM;
    }
    if (!r && write) r = mdi_copy_up(s, &node);
    int fd = -1;
    int directory = r ? -1 : mdi_backing_directory(s, &node);
    if (!r && directory < 0) r = directory;
    if (!r && (fd = openat(directory, node.backing, flags | O_NOFOLLOW | O_CLOEXEC)) < 0) r = -errno;
    r = mdi_commit(s, r);
    if (r && fd >= 0) close(fd);
    return r ? r : fd;
}
