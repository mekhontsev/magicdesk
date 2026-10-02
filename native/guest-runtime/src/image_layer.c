#define _GNU_SOURCE
#include "image_layer.h"
#include "image_acl.h"
#include "inode_internal.h"
#include "image_io.h"
#include "file_capability.h"
#include "proc_paths.h"
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int path_name(const char *input, char out[PATH_MAX]) {
    if (!input || input[0] == '/') return -EINVAL;
    size_t n = 0;
    while (*input) {
        const char *end = strchr(input, '/');
        size_t size = end ? (size_t)(end - input) : strlen(input);
        if (size == 2 && !memcmp(input, "..", 2)) return -EINVAL;
        if (size && !(size == 1 && *input == '.')) {
            if (size > NAME_MAX || n + size + 1 >= PATH_MAX) return -ENAMETOOLONG;
            out[n++] = '/'; memcpy(out + n, input, size); n += size;
        }
        if (!end) break;
        input = end + 1;
    }
    if (!n) out[n++] = '/';
    out[n] = 0; return 0;
}
static int remove_tree(struct md_inode_store *s, struct mdi_location *loc, int children_only) {
    sqlite3_stmt *q = NULL;
    int r = 0;
    if (loc->node.kind == S_IFDIR) {
        r = mdi_prepare(s, "WITH RECURSIVE tree(object) AS (SELECT ?1 UNION ALL "
            "SELECT n.object FROM names n JOIN tree t ON n.parent=t.object "
            "JOIN objects o ON o.object=n.object WHERE o.kind=16384) "
            "DELETE FROM names WHERE parent IN tree", &q);
        if (!r) r = mdi_bind_id(q, 1, loc->node.id);
        if (!r) r = mdi_sql_error(mdi_step(s, q));
        sqlite3_finalize(q);
    }
    if (!r && !children_only) r = loc->special ? -EBUSY : mdi_delete_name(s, loc->parent.id, loc->name);
    return r;
}
static int parents(struct md_inode_store *s, char *path) {
    for (char *p = path + 1; *p; ++p) if (*p == '/') {
        *p = 0;
        struct mdi_location loc;
        int r = mdi_walk(s, MD_INODE_ROOT, path, MDI_FOLLOW, 1, &loc);
        if (!r && !loc.exists) {
            struct mdi_node node; int fd;
            r = mdi_allocate(s, S_IFDIR, 0700, 0, NULL, loc.parent.id, &node, &fd);
            if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, node.id);
        } else if (!r && loc.node.kind != S_IFDIR) r = -ENOTDIR;
        *p = '/';
        if (r) return r;
    }
    return 0;
}
static int whiteout(struct md_inode_store *s, char *path) {
    char *name = strrchr(path, '/') + 1;
    if (strncmp(name, ".wh.", 4)) return 0;
    int opaque = !strcmp(name, ".wh..wh..opq");
    if (opaque) {
        if (name == path + 1) name[0] = 0; else name[-1] = 0;
    } else {
        if (!name[4] || !strcmp(name + 4, ".") || !strcmp(name + 4, "..")) return -EINVAL;
        memmove(name, name + 4, strlen(name + 4) + 1);
    }
    struct mdi_location loc;
    int r = mdi_walk(s, MD_INODE_ROOT, path, MDI_ENTRY, 0, &loc);
    if (r == -ENOENT) return 1;
    if (!r && opaque && loc.node.kind != S_IFDIR) r = -ENOTDIR;
    if (!r) r = remove_tree(s, &loc, opaque);
    return r ? r : 1;
}
static int record_metadata(struct md_inode_store *s, struct archive_entry *entry, const struct mdi_node *node) {
    sqlite3_stmt *q = NULL;
    la_int64_t uid = archive_entry_uid(entry), gid = archive_entry_gid(entry);
    if (uid < 0 || gid < 0 || uid >= UINT32_MAX || gid >= UINT32_MAX) return -EINVAL;
    int r = mdi_prepare(s, "INSERT OR REPLACE INTO temp.image_metadata VALUES(?1,?2,?3,?4,?5,?6)", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 2, archive_entry_perm(entry) & 07777));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, archive_entry_mtime(entry)));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 4, archive_entry_mtime_nsec(entry)));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 5, uid));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 6, gid));
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    sqlite3_finalize(q); return r;
}
static int unique_path(struct md_inode_store *s, const char *path) {
    sqlite3_stmt *seen = NULL;
    int r = mdi_prepare(s, "INSERT INTO temp.layer_paths VALUES(?1)", &seen);
    if (!r) r = mdi_sql_error(sqlite3_bind_text(seen, 1, path, -1, SQLITE_STATIC));
    if (!r) r = mdi_sql_error(mdi_step(s, seen));
    sqlite3_finalize(seen);
    return r;
}
static int entry_file(struct md_inode_store *s, struct archive *ar, struct archive_entry *entry,
        char *path, uint64_t *bytes) {
    mode_t kind = archive_entry_filetype(entry);
    const char *link = archive_entry_hardlink(entry);
    const void *capability = NULL; size_t capability_size = 0;
    const char *name = NULL; const void *value = NULL; size_t length = 0;
    archive_entry_xattr_reset(entry);
    while (!archive_entry_xattr_next(entry,&name,&value,&length)) {
        if (strcmp(name, MD_FILE_CAPABILITY_NAME)) {
            fprintf(stderr,"Guest image: unsupported extended attribute %s on %s\n",name,path);
            return -ENOTSUP;
        }
        if (!md_file_capability_valid(value,length)) return -EINVAL;
        /* Tar producers may publish both SCHILY and LIBARCHIVE encodings. */
        if (capability && (capability_size != length || memcmp(capability,value,length))) return -EINVAL;
        capability=value; capability_size=length;
    }
    if (archive_entry_is_encrypted(entry)) return -ENOTSUP;
    if (kind == S_IFCHR || kind == S_IFBLK) {
        if (strncmp(path, "/dev/", 5) || !md_host_path(path)) return -ENOTSUP;
        if (link || archive_entry_symlink(entry) || archive_entry_size(entry)
                || archive_entry_uid(entry) < 0 || archive_entry_uid(entry) >= UINT32_MAX
                || archive_entry_gid(entry) < 0 || archive_entry_gid(entry) >= UINT32_MAX
                || archive_entry_rdevmajor(entry) < 0 || archive_entry_rdevmajor(entry) > UINT32_MAX
                || archive_entry_rdevminor(entry) < 0 || archive_entry_rdevminor(entry) > UINT32_MAX) return -EINVAL;
        if (capability || archive_entry_acl_count(entry, ARCHIVE_ENTRY_ACL_TYPE_ACCESS
                | ARCHIVE_ENTRY_ACL_TYPE_DEFAULT | ARCHIVE_ENTRY_ACL_TYPE_NFS4)) return -ENOTSUP;
        /* Host /dev replaces these entries. Remove an older placeholder without
         * following archive symlinks into unrelated guest content. */
        struct mdi_location loc;
        int r = mdi_walk_resolved(s, MD_INODE_ROOT, path, MDI_ENTRY, 0, RESOLVE_NO_SYMLINKS, &loc);
        if (r == -ENOENT) r = 0;
        else if (!r) r = remove_tree(s, &loc, 0);
        if (!r) fprintf(stderr, "Guest image: omitted %s device %s (%lld:%lld); provided by host /dev\n",
            kind == S_IFCHR ? "character" : "block", path,
            (long long)archive_entry_rdevmajor(entry), (long long)archive_entry_rdevminor(entry));
        return r;
    }
    if (!link && kind != S_IFREG && kind != S_IFDIR && kind != S_IFLNK && kind != S_IFIFO) return -ENOTSUP;
    if (kind == S_IFIFO && archive_entry_size(entry)) return -EINVAL;
    int r = md_image_acl_record(s,entry,path);
    if (!r) r = parents(s, path);
    struct mdi_location loc;
    if (!r) r = mdi_walk(s, MD_INODE_ROOT, path, MDI_ENTRY, 1, &loc);
    if (!r && loc.exists && (link || kind != S_IFDIR || loc.node.kind != S_IFDIR))
        r = remove_tree(s, &loc, 0);
    if (r) return r;
    if (link) {
        char target[PATH_MAX];
        r = path_name(link, target);
        sqlite3_stmt *q = NULL;
        if (!r) r = mdi_prepare(s, "INSERT INTO temp.layer_links VALUES(?1,?2,?3,?4)", &q);
        if (!r) r = mdi_bind_id(q, 1, loc.parent.id);
        if (!r) r = mdi_bind_name(q, 2, loc.name);
        if (!r) r = mdi_sql_error(sqlite3_bind_text(q, 3, target, -1, SQLITE_TRANSIENT));
        if (!r && capability) r = mdi_sql_error(sqlite3_bind_blob(q,4,capability,capability_size,SQLITE_TRANSIENT));
        if (!r) r = mdi_sql_error(mdi_step(s, q));
        sqlite3_finalize(q); return r;
    }
    struct mdi_node node = loc.node;
    int fd = -1;
    if (!(loc.exists && kind == S_IFDIR && loc.node.kind == S_IFDIR)) {
        const char *target = kind == S_IFLNK ? archive_entry_symlink(entry) : NULL;
        if (kind == S_IFLNK && (!target || !*target || strlen(target) >= PATH_MAX)) return -EINVAL;
        r = mdi_allocate(s, kind, kind == S_IFDIR ? 0700 : 0600, O_RDWR, target,
            kind == S_IFDIR ? loc.parent.id : NULL, &node, &fd);
        if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, node.id);
    }
    if (!r && kind == S_IFREG) {
        int64_t size = archive_entry_size(entry);
        if (size < 0 || (uint64_t)size > 8ULL * 1024 * 1024 * 1024 - *bytes) r = -EFBIG;
        unsigned char buffer[65536];
        uint64_t total = 0;
        while (!r) {
            la_ssize_t n = archive_read_data(ar, buffer, sizeof(buffer));
            if (n < 0) { r = -EBADMSG; break; }
            if (!n) break;
            if ((uint64_t)n > (uint64_t)size - total) { r = -EBADMSG; break; }
            r = md_image_write(fd, buffer, (size_t)n); total += (uint64_t)n;
        }
        if (!r && total != (uint64_t)size) r = -EBADMSG;
        if (!r) *bytes += total;
        if (!r && fsync(fd)) r = -errno;
    }
    if (fd >= 0) close(fd);
    if (!r) r = record_metadata(s, entry, &node);
    if (!r && capability) r = mdi_file_capability(s,&node,capability,capability_size);
    return r;
}
static int links(struct md_inode_store *s) {
    int r = 0;
    for (;;) {
        sqlite3_stmt *q = NULL;
        r = mdi_prepare(s, "SELECT rowid,parent,name,target,capability FROM temp.layer_links", &q);
        unsigned remaining = 0, changed = 0;
        while (!r) {
            int rc = mdi_step(s, q);
            if (rc == SQLITE_DONE) break;
            if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
            ++remaining;
            struct mdi_location target;
            int found = mdi_walk(s, MD_INODE_ROOT, (const char *)sqlite3_column_text(q, 3), MDI_NOFOLLOW, 0, &target);
            if (found == -ENOENT) continue;
            if (found) { r = found; break; }
            if (target.node.kind == S_IFDIR) { r = -EPERM; break; }
            int size = sqlite3_column_bytes(q, 2);
            char name[NAME_MAX+1];
            if (size < 1 || size > NAME_MAX) { r = -EIO; break; }
            memcpy(name, sqlite3_column_blob(q, 2), (size_t)size); name[size] = 0;
            r = mdi_add_name(s, (const char *)sqlite3_column_text(q, 1), name, target.node.id);
            if (!r && sqlite3_column_type(q,4)!=SQLITE_NULL)
                r=mdi_file_capability(s,&target.node,sqlite3_column_blob(q,4),sqlite3_column_bytes(q,4));
            sqlite3_stmt *del = NULL;
            if (!r) r = mdi_prepare(s, "DELETE FROM temp.layer_links WHERE rowid=?1", &del);
            if (!r) r = mdi_sql_error(sqlite3_bind_int64(del, 1, sqlite3_column_int64(q, 0)));
            if (!r) r = mdi_sql_error(mdi_step(s, del));
            sqlite3_finalize(del); ++changed;
        }
        sqlite3_finalize(q);
        if (r || !remaining) break;
        if (!changed) { r = -ENOENT; break; }
    }
    return r;
}
static int metadata(struct md_inode_store *s, int preserve) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT object,mode,seconds,nanos,uid,gid FROM temp.image_metadata", &q);
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        const char *object = (const char *)sqlite3_column_text(q, 0);
        struct mdi_node node;
        r = mdi_node(s, object, &node);
        struct timespec times[2] = {{0, UTIME_OMIT},
            {sqlite3_column_int64(q, 2), sqlite3_column_int64(q, 3)}};
        if (!r && node.kind == S_IFLNK && utimensat(s->objects, object, times, AT_SYMLINK_NOFOLLOW)) r = -errno;
        if (!r && node.kind == S_IFIFO) {
            if (utimensat(s->objects, object, times, AT_SYMLINK_NOFOLLOW)) r = -errno;
            if (!r && !preserve) r = mdi_metadata(s, &node, node.uid, node.gid, sqlite3_column_int(q, 1));
        }
        if (!r && node.kind != S_IFLNK && node.kind != S_IFIFO) {
            int fd = openat(s->objects, object, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
            if (fd < 0) r = -errno;
            else {
                if (fchmod(fd, (mode_t)sqlite3_column_int(q, 1) & 01777) || futimens(fd, times) || fsync(fd)) r = -errno;
                close(fd);
            }
        }
        if (!r && preserve) r = mdi_metadata(s, &node, (uint32_t)sqlite3_column_int64(q, 4),
            (uint32_t)sqlite3_column_int64(q, 5), (mode_t)sqlite3_column_int(q, 1));
        if (!r && preserve) r=mdi_acl_chmod(s,&node,(mode_t)sqlite3_column_int(q,1));
        if (!r && !preserve && node.acl_mask) r=-ENOTSUP;
    }
    sqlite3_finalize(q); return r;
}
int md_image_layer(struct md_inode_store *s, int fd, uint64_t *bytes, uint64_t *entries) {
    int r = mdi_begin(s, 1);
    if (r) return r;
    r = mdi_sql(s, "CREATE TEMP TABLE IF NOT EXISTS image_metadata(object TEXT PRIMARY KEY,mode INTEGER,seconds INTEGER,nanos INTEGER,uid INTEGER,gid INTEGER);"
        "CREATE TEMP TABLE layer_links(parent TEXT,name BLOB,target TEXT,capability BLOB,UNIQUE(parent,name));"
        "CREATE TEMP TABLE layer_acls(path TEXT,type INTEGER,value BLOB,PRIMARY KEY(path,type));"
        "CREATE TEMP TABLE layer_paths(path TEXT PRIMARY KEY);");
    /* OCI whiteouts only remove lower entries, regardless of tar ordering. */
    for (unsigned pass = 0; pass < 2 && !r; ++pass) {
        struct archive *ar = archive_read_new();
        if (!ar) { r = -ENOMEM; break; }
        archive_read_support_format_tar(ar);
        if (lseek(fd, 0, SEEK_SET) < 0) r = -errno;
        if (!r && archive_read_open_fd(ar, fd, 65536) != ARCHIVE_OK) r = -EBADMSG;
        struct archive_entry *entry;
        while (!r) {
            int rc = archive_read_next_header(ar, &entry);
            if (rc == ARCHIVE_EOF) break;
            if (rc != ARCHIVE_OK) { r = -EBADMSG; break; }
            if (!pass && ++*entries > 200000) { r = -EFBIG; break; }
            char path[PATH_MAX];
            r = path_name(archive_entry_pathname(entry), path);
            if (!r && !pass) r = unique_path(s, path);
            if (r) break;
            const char *name = strrchr(path, '/') + 1;
            int marker = !strncmp(name, ".wh.", 4);
            if (marker && (archive_entry_filetype(entry) != S_IFREG || archive_entry_size(entry) != 0
                    || archive_entry_hardlink(entry))) { r = -EINVAL; break; }
            if (!pass && marker) { int removed = whiteout(s, path); if (removed < 0) r = removed; }
            if (pass && !marker) r = entry_file(s, ar, entry, path, bytes);
            if (r) fprintf(stderr,"Guest image: entry %s: %s\n",path,strerror(-r));
        }
        if (archive_read_close(ar) != ARCHIVE_OK && !r) r = -EBADMSG;
        archive_read_free(ar);
    }
    if (!r) r = links(s);
    if (!r) r = md_image_acls_finish(s);
    if (!r) r = mdi_sql(s, "DROP TABLE temp.layer_links; DROP TABLE temp.layer_paths; DROP TABLE temp.layer_acls");
    if (!r && fsync(s->objects)) r = -errno;
    return mdi_commit(s, r);
}
int md_image_layers_finish(struct md_inode_store *s, int preserve) {
    int r = mdi_begin(s, 1);
    if (r) return r;
    /* The unpublished tree stays traversable while applying layers. Final
     * permissions belong to publication, not the importer's access rights. */
    r=mdi_sql(s,"CREATE TEMP TABLE IF NOT EXISTS image_metadata(object TEXT PRIMARY KEY,mode INTEGER,seconds INTEGER,nanos INTEGER,uid INTEGER,gid INTEGER)");
    if (!r && preserve) {
        struct mdi_node root;
        r = mdi_node(s, MDI_ROOT, &root);
        if (!r) r = mdi_metadata(s, &root, 0, 0, 0755);
    }
    if (!r) r = metadata(s, preserve);
    if (!r) r = mdi_sql(s, preserve ? "INSERT INTO properties VALUES('image-users',1)"
        : "INSERT INTO properties VALUES('image-users',0)");
    if (!r) r = mdi_sql(s, "DROP TABLE temp.image_metadata");
    return mdi_commit(s, r);
}
