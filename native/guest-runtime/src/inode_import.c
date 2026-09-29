#define _GNU_SOURCE
#include "inode_internal.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/xattr.h>
#include <unistd.h>

struct import_frame {
    DIR *source;
    struct stat initial;
    struct mdi_node target;
    int source_fd, destination;
    size_t path_bytes;
    struct import_frame *parent;
};
struct importer {
    struct md_inode_store *store;
    const struct md_inode_import_limits *limits;
    struct md_inode_import_result result;
    unsigned char buffer[65536];
};
static int same(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_mode == b->st_mode
        && a->st_uid == b->st_uid && a->st_gid == b->st_gid && a->st_size == b->st_size
        && a->st_nlink == b->st_nlink && a->st_mtim.tv_sec == b->st_mtim.tv_sec
        && a->st_mtim.tv_nsec == b->st_mtim.tv_nsec && a->st_ctim.tv_sec == b->st_ctim.tv_sec
        && a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}
static int attributes(int fd, const char *symlink_path) {
    char names[256];
    ssize_t n = symlink_path ? llistxattr(symlink_path, names, sizeof(names)) : flistxattr(fd, names, sizeof(names));
    if (n < 0) return errno == ERANGE ? -ENOTSUP : -errno;
    for (size_t offset = 0; offset < (size_t)n;) {
        size_t length = strnlen(names+offset, (size_t)n-offset);
        if (length == (size_t)n-offset) return -EIO;
        /* New objects retain their kernel-assigned SELinux label, never a copied policy label. */
        if (strcmp(names+offset, "security.selinux")) return -ENOTSUP;
        offset += length+1;
    }
    return 0;
}
static int metadata(int fd, const struct stat *source) {
    struct timespec times[2] = {source->st_atim, source->st_mtim};
    if (fchmod(fd, source->st_mode & 0777) || futimens(fd, times) || fsync(fd)) return -errno;
    return 0;
}
static int overlap(int descendant, const struct stat *ancestor) {
    int fd = openat(descendant, ".", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (fd < 0) return -errno;
    int r = 0;
    for (;;) {
        struct stat current, parent;
        if (fstat(fd, &current)) { r = -errno; break; }
        if (current.st_dev == ancestor->st_dev && current.st_ino == ancestor->st_ino) { r = -EINVAL; break; }
        int next = openat(fd, "..", O_PATH | O_DIRECTORY | O_CLOEXEC);
        if (next < 0) { r = -errno; break; }
        if (fstat(next, &parent)) { r = -errno; close(next); break; }
        close(fd); fd = next;
        if (current.st_dev == parent.st_dev && current.st_ino == parent.st_ino) break;
    }
    close(fd); return r;
}
static int origin(struct md_inode_store *s, const struct stat *st, struct mdi_node *node, int insert) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, insert
        ? "INSERT INTO temp.imported VALUES(?1,?2,?3,?4)"
        : "SELECT object,snapshot FROM temp.imported WHERE device=?1 AND inode=?2", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, (sqlite3_int64)st->st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st->st_ino));
    if (!r && insert) r = mdi_bind_id(q, 3, node->id);
    if (!r && insert) r = mdi_sql_error(sqlite3_bind_blob(q, 4, st, sizeof(*st), SQLITE_TRANSIENT));
    if (!r) {
        int rc = sqlite3_step(q);
        if (insert) r = rc == SQLITE_DONE ? 0 : mdi_sql_failure(rc);
        else if (rc == SQLITE_DONE) r = -ENOENT;
        else if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
        else {
            const char *id = (const char *)sqlite3_column_text(q, 0);
            struct stat original;
            const void *snapshot = sqlite3_column_blob(q, 1);
            if (!snapshot || sqlite3_column_bytes(q, 1) != sizeof(original)) r = -EIO;
            else {
                memcpy(&original, snapshot, sizeof(original));
                r = !same(st, &original) ? -ESTALE
                    : id && sqlite3_column_bytes(q, 0) == 32 ? mdi_node(s, id, node) : -EIO;
            }
        }
    }
    sqlite3_finalize(q); return r;
}
static int push(struct import_frame **stack, int source, int destination,
        const struct stat *st, const struct mdi_node *node, size_t path_bytes) {
    struct import_frame *frame = calloc(1, sizeof(*frame));
    if (!frame) return -ENOMEM;
    frame->source = fdopendir(source);
    if (!frame->source) { int r = -errno; free(frame); return r; }
    frame->initial = *st; frame->target = *node; frame->source_fd = source; frame->destination = destination;
    frame->path_bytes = path_bytes; frame->parent = *stack; *stack = frame;
    return 0;
}
static void pop(struct import_frame **stack) {
    struct import_frame *frame = *stack; *stack = frame->parent;
    closedir(frame->source); if (frame->destination >= 0) close(frame->destination);
    free(frame);
}
static int copy_file(struct importer *i, int source, int destination, const struct stat *st) {
    if (st->st_size < 0 || (uint64_t)st->st_size > i->limits->bytes - i->result.bytes) return -EFBIG;
    off_t offset = 0;
    while (offset < st->st_size) {
        size_t size = (uint64_t)(st->st_size-offset) > sizeof(i->buffer)
            ? sizeof(i->buffer) : (size_t)(st->st_size-offset);
        ssize_t n = pread(source, i->buffer, size, offset);
        if (n < 0) { if (errno == EINTR) continue; return -errno; }
        if (!n) return -ESTALE;
        size_t written = 0;
        while (written < (size_t)n) {
            ssize_t w = pwrite(destination, i->buffer+written, (size_t)n-written, offset+(off_t)written);
            if (w < 0) { if (errno == EINTR) continue; return -errno; }
            if (!w) return -EIO;
            written += (size_t)w;
        }
        offset += n;
    }
    i->result.bytes += (uint64_t)st->st_size;
    return 0;
}
static int leaf(struct importer *i, struct import_frame *frame, const char *name, const struct stat *st) {
    int parent = frame->source_fd, source = -1, destination = -1;
    int link = S_ISLNK(st->st_mode), r = 0;
    char target[PATH_MAX], proc[64+NAME_MAX];
    if (link) {
        if (snprintf(proc, sizeof(proc), "/proc/self/fd/%d/%s", parent, name) >= (int)sizeof(proc)) return -ENAMETOOLONG;
        r = attributes(-1, proc);
        ssize_t n = r ? -1 : readlinkat(parent, name, target, sizeof(target));
        if (!r && n < 0) r = -errno;
        if (!r && (size_t)n >= sizeof(target)) r = -ENAMETOOLONG;
        if (!r) target[n] = 0;
    } else {
        source = openat(parent, name, O_RDONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
        if (source < 0) return -errno;
        struct stat actual;
        if (fstat(source, &actual)) r = -errno;
        else if (!same(st, &actual)) r = -ESTALE;
        if (!r) r = attributes(source, NULL);
    }
    struct mdi_node node = {0};
    int found = r ? r : origin(i->store, st, &node, 0);
    if (!r && found && found != -ENOENT) r = found;
    if (!r && !found) {
        if (node.kind != (st->st_mode & S_IFMT)) r = -ESTALE;
        else ++i->result.aliases;
    } else if (!r) {
        r = mdi_allocate(i->store, st->st_mode & S_IFMT, 0600, O_RDWR, link ? target : NULL, NULL, &node, &destination);
        if (!r && !link) r = copy_file(i, source, destination, st);
        if (!r && !link) r = metadata(destination, st);
        if (!r && link) {
            struct timespec times[2] = {st->st_atim, st->st_mtim};
            if (utimensat(i->store->objects, node.id, times, AT_SYMLINK_NOFOLLOW)
                    || fsync(i->store->objects)) r = -errno;
        }
        if (!r) r = origin(i->store, st, &node, 1);
    }
    struct stat after;
    if (!r && fstatat(parent, name, &after, AT_SYMLINK_NOFOLLOW)) r = -errno;
    if (!r && !same(st, &after)) r = -ESTALE;
    if (!r) r = mdi_add_name(i->store, frame->target.id, name, node.id);
    if (source >= 0) close(source);
    if (destination >= 0) close(destination);
    return r;
}
int md_inode_import_tree(struct md_inode_store *s, int source_fd,
        const struct md_inode_import_limits *limits, struct md_inode_import_result *out) {
    if (!s || !limits || !out) return -EFAULT;
    memset(out, 0, sizeof(*out));
    if (!limits->bytes || !limits->entries) return -EINVAL;
    struct stat root, storage;
    if (fstat(source_fd, &root) || fstat(s->objects, &storage)) return -errno;
    if (!S_ISDIR(root.st_mode)) return -ENOTDIR;
    int r = overlap(s->objects, &root);
    if (!r) r = overlap(source_fd, &storage);
    if (r) return r;
    struct importer *i = calloc(1, sizeof(*i)); if (!i) return -ENOMEM;
    i->store = s; i->limits = limits;
    r = mdi_sql(s, "BEGIN IMMEDIATE"); if (r) { free(i); return r; }
    struct mdi_node node; r = mdi_node(s, MDI_ROOT, &node);
    if (!r) { int empty = mdi_empty(s, &node); if (empty != 1) r = empty < 0 ? empty : -ENOTEMPTY; }
    if (!r) r = mdi_sql(s, "CREATE TEMP TABLE imported(device INTEGER,inode INTEGER,object TEXT,snapshot BLOB,"
        "PRIMARY KEY(device,inode)) WITHOUT ROWID");
    struct import_frame *stack = NULL;
    int fd = -1;
    if (!r && (fd = openat(source_fd, ".", O_RDONLY | O_DIRECTORY | O_CLOEXEC)) < 0) r = -errno;
    if (!r) r = attributes(fd, NULL);
    if (!r) r = push(&stack, fd, -1, &root, &node, 1);
    if (r && fd >= 0) close(fd);
    while (!r && stack) {
        errno = 0;
        struct dirent *entry = readdir(stack->source);
        if (!entry) {
            if (errno) { r = -errno; break; }
            struct stat actual;
            if (fstat(stack->source_fd, &actual)) r = -errno;
            if (!r && !same(&stack->initial, &actual)) r = -ESTALE;
            if (!r && stack->destination >= 0) r = metadata(stack->destination, &stack->initial);
            pop(&stack); continue;
        }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        if (++i->result.entries > limits->entries) { r = -EFBIG; break; }
        size_t path_bytes = stack->path_bytes + strlen(entry->d_name)+1;
        if (path_bytes >= PATH_MAX) { r = -ENAMETOOLONG; break; }
        struct stat st;
        if (fstatat(stack->source_fd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW)) { r = -errno; break; }
        if (st.st_dev != root.st_dev) { r = -EXDEV; break; }
        if ((st.st_mode & 07000) || (!S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode) && !S_ISLNK(st.st_mode))) {
            r = -ENOTSUP; break;
        }
        if (!S_ISDIR(st.st_mode)) { r = leaf(i, stack, entry->d_name, &st); continue; }
        int child = openat(stack->source_fd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (child < 0) { r = -errno; break; }
        struct stat actual;
        if (fstat(child, &actual)) r = -errno;
        else if (!same(&st, &actual)) r = -ESTALE;
        if (!r) r = attributes(child, NULL);
        int destination = -1;
        if (!r) r = mdi_allocate(s, S_IFDIR, 0700, 0, NULL, stack->target.id, &node, &destination);
        if (!r && fchmodat(s->objects, node.id, 0700, 0)) r = -errno;
        if (!r && (destination = openat(s->objects, node.id, O_RDONLY | O_DIRECTORY | O_CLOEXEC)) < 0) r = -errno;
        if (!r) r = mdi_add_name(s, stack->target.id, entry->d_name, node.id);
        if (!r) r = push(&stack, child, destination, &st, &node, path_bytes);
        if (r) { close(child); if (destination >= 0) close(destination); }
    }
    while (stack) pop(&stack);
    if (!r) r = mdi_sql(s, "DROP TABLE temp.imported");
    r = mdi_commit(s, r);
    if (!r) *out = i->result;
    free(i); return r;
}
