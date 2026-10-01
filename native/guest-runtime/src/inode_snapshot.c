#define _GNU_SOURCE
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int image_source(struct md_inode_store *source, struct md_inode_store *target, int *id) {
    char proc[64], path[PATH_MAX];
    snprintf(proc, sizeof(proc), "/proc/self/fd/%d", source->objects);
    if (!realpath(proc, path)) return -errno;
    struct stat st;
    if (fstat(source->objects, &st)) return -errno;
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(target, "INSERT INTO sources(path,device,inode) VALUES(?1,?2,?3) "
        "ON CONFLICT(device,inode) DO UPDATE SET path=excluded.path RETURNING id", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_text(q, 1, path, -1, SQLITE_STATIC));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, (sqlite3_int64)st.st_ino));
    if (!r) {
        int rc = mdi_step(target, q);
        if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
        else *id = sqlite3_column_int(q, 0);
    }
    sqlite3_finalize(q); return r;
}
static int clone_node(struct md_inode_store *source, struct md_inode_store *target, struct mdi_node *node, int origin) {
    struct stat st;
    int r = mdi_backing_stat(source, node, &st);
    if (r) return r;
    if (node->kind == S_IFLNK) {
        char value[PATH_MAX];
        ssize_t n = readlinkat(source->objects, node->backing, value, sizeof(value));
        if (n < 0) return -errno;
        if ((size_t)n >= sizeof(value)) return -ENAMETOOLONG;
        value[n] = 0;
        if (symlinkat(value, target->objects, node->backing)) return -errno;
        struct timespec times[2] = {st.st_atim, st.st_mtim};
        if (utimensat(target->objects, node->backing, times, AT_SYMLINK_NOFOLLOW)) return -errno;
    } else if (node->kind == S_IFDIR) {
        if (strcmp(node->id, MDI_ROOT) && mkdirat(target->objects, node->backing, 0700)) return -errno;
        int fd = openat(target->objects, node->backing, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return -errno;
        struct timespec times[2] = {st.st_atim, st.st_mtim};
        if (fchmod(fd, st.st_mode & 01777) || futimens(fd, times) || fsync(fd)) r = -errno;
        close(fd);
        if (r) return r;
    } else if (node->kind != S_IFREG) return -ENOTSUP;
    if (node->kind != S_IFREG && fstatat(target->objects, node->backing, &st, AT_SYMLINK_NOFOLLOW)) return -errno;
    sqlite3_stmt *q = NULL;
    r = mdi_prepare(target, "UPDATE objects SET device=?1,inode=?2,shared=(kind=32768),source=?4 WHERE object=?3", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st.st_ino));
    if (!r) r = mdi_bind_id(q, 3, node->id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 4, node->kind == S_IFREG ? (node->source ? node->source : origin) : 0));
    if (!r) r = mdi_sql_error(mdi_step(target, q));
    sqlite3_finalize(q);
    return r;
}
int md_inode_snapshot(struct md_inode_store *source, struct md_inode_store *target) {
    if (!source || !target || source == target) return -EINVAL;
    if (!source->readonly || target->readonly) return -EINVAL;
    struct stat a, b;
    if (fstat(source->root, &a) || fstat(target->root, &b)) return -errno;
    if (a.st_dev == b.st_dev && a.st_ino == b.st_ino) return -EINVAL;
    /* The destination is unpublished. Shared bodies retain an explicit source
     * directory identity; no host hardlinks or implicit source copying. */
    int r = mdi_begin(target, 1);
    if (r) return r;
    struct mdi_node root;
    r = mdi_node(target, MDI_ROOT, &root);
    if (!r) { int empty = mdi_empty(target, &root); if (empty != 1) r = empty < 0 ? empty : -ENOTEMPTY; }
    r = mdi_finish(target, r);
    if (r) return r;
    r = mdi_begin(source, 0);
    if (r) return r;
    sqlite3_backup *backup = sqlite3_backup_init(target->db, "main", source->db, "main");
    if (!backup) r = mdi_sql_failure(sqlite3_errcode(target->db));
    if (!r) {
        int rc = sqlite3_backup_step(backup, -1);
        if (rc != SQLITE_DONE) r = mdi_sql_failure(rc);
        int done = sqlite3_backup_finish(backup);
        if (!r) r = mdi_sql_error(done);
    }
    if (!r) {
        r = mdi_begin(target, 1);
        if (!r) {
            r = mdi_sql(target, "DELETE FROM backings; DELETE FROM events; DELETE FROM sockets; "
                "UPDATE properties SET value=0 WHERE key='sealed'");
            int origin = 0;
            if (!r) r = image_source(source, target, &origin);
            sqlite3_stmt *q = NULL;
            if (!r) r = mdi_prepare(source, "SELECT object FROM objects", &q);
            while (!r) {
                int rc = mdi_step(source, q);
                if (rc == SQLITE_DONE) break;
                if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
                struct mdi_node node;
                r = mdi_node(source, (const char *)sqlite3_column_text(q, 0), &node);
                if (!r) r = clone_node(source, target, &node, origin);
            }
            sqlite3_finalize(q);
            if (!r) r = mdi_sql(target, "INSERT INTO backings SELECT backing,object,device,inode,source FROM objects");
            if (!r && fsync(target->objects)) r = -errno;
            r = mdi_commit(target, r);
        }
    }
    r = mdi_finish(source, r);
    if (!r) r = mdi_sources_open(target);
    return r;
}
