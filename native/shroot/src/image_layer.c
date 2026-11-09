#define _GNU_SOURCE
#include "image_layer.h"
#include "image_acl.h"
#include "image_pool.h"
#include "inode_internal.h"
#include "inode_xattr.h"
#include "inode_native.h"
#include "image_io.h"
#include "file_capability.h"
#include "proc_paths.h"
#include <archive.h>
#include <archive_entry.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/openat2.h>
#include <stdlib.h>
#include <string.h>
#include <sys/xattr.h>
#include <sys/sysmacros.h>
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
static int record_metadata(struct md_inode_store *s, struct archive_entry *entry, const struct mdi_node *node, mode_t mode) {
    sqlite3_stmt *q = NULL;
    la_int64_t uid = archive_entry_uid(entry), gid = archive_entry_gid(entry);
    if (uid < 0 || gid < 0 || uid >= UINT32_MAX || gid >= UINT32_MAX) return -EINVAL;
    int r = mdi_prepare(s, "INSERT OR REPLACE INTO temp.image_metadata VALUES(?1,?2,?3,?4,?5,?6,?7,?8)", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 2, mode & 07777));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, archive_entry_mtime(entry)));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 4, archive_entry_mtime_nsec(entry)));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 5, uid));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 6, gid));
    if (!r && archive_entry_atime_is_set(entry)) {
        r = mdi_sql_error(sqlite3_bind_int64(q, 7, archive_entry_atime(entry)));
        if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 8, archive_entry_atime_nsec(entry)));
    }
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
        char *path, uint64_t *bytes, int source, uint64_t index, int backup) {
    mode_t kind = archive_entry_filetype(entry);
    const char *link = archive_entry_hardlink(entry);
    const void *capability = NULL; size_t capability_size = 0;
    const char *name = NULL; const void *value = NULL; size_t length = 0;
    mode_t mode = archive_entry_perm(entry);
    int mode_seen = 0;
    archive_entry_xattr_reset(entry);
    while (!archive_entry_xattr_next(entry,&name,&value,&length)) {
        if (mdi_native_private_attribute(name)) return -EPERM;
        if (backup && !strcmp(name, "magicdesk.backup.mode")) {
            if (length != 2) return -EINVAL;
            const unsigned char *b = value;
            unsigned parsed = b[0] | (unsigned)b[1]<<8;
            if (parsed > 07777 || (mode_seen && parsed != mode)) return -EINVAL;
            mode = parsed; mode_seen = 1;
            continue;
        }
        if ((backup && !strncmp(name, "user.", 5)) || mdi_xattr_virtual(name)) {
            if (length > 65536 || strlen(name) > 255 || link) return -EINVAL;
            continue;
        }
        if (strcmp(name, MD_FILE_CAPABILITY_NAME)) {
            fprintf(stderr,"Guest image: unsupported extended attribute %s on %s\n",name,path);
            return -ENOTSUP;
        }
        if (!md_file_capability_valid(value,length)) return -EINVAL;
        /* Tar producers may publish both SCHILY and LIBARCHIVE encodings. */
        if (capability && (capability_size != length || memcmp(capability,value,length))) return -EINVAL;
        capability=value; capability_size=length;
    }
    if (backup && !link && !mode_seen) return -EINVAL;
    if (archive_entry_is_encrypted(entry)) return -ENOTSUP;
    int device=kind==S_IFCHR || kind==S_IFBLK;
    if (device && !link) {
        if (archive_entry_symlink(entry) || archive_entry_size(entry)
                || archive_entry_uid(entry) < 0 || archive_entry_uid(entry) >= UINT32_MAX
                || archive_entry_gid(entry) < 0 || archive_entry_gid(entry) >= UINT32_MAX
                || archive_entry_rdevmajor(entry) < 0 || archive_entry_rdevmajor(entry) > UINT32_MAX
                || archive_entry_rdevminor(entry) < 0 || archive_entry_rdevminor(entry) > UINT32_MAX) return -EINVAL;
    }
    if (!link && !device && kind != S_IFREG && kind != S_IFDIR && kind != S_IFLNK && kind != S_IFIFO) return -ENOTSUP;
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
        if (!r) r = mdi_prepare(s, "INSERT INTO temp.layer_links(parent,name,target,capability) VALUES(?1,?2,?3,?4)", &q);
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
        int shared = source && kind == S_IFREG;
        if (shared) {
            char backing[33]; md_image_pool_name(index, backing);
            struct stat st;
            if (fstatat(s->lifetime->sources[source], backing, &st, AT_SYMLINK_NOFOLLOW)) r = -errno;
            else if (!S_ISREG(st.st_mode) || st.st_size != archive_entry_size(entry)
                    || (st.st_mode & 07777) != (0600 | ((archive_entry_perm(entry) & 0111) ? 0100 : 0))
                    || st.st_mtim.tv_sec != archive_entry_mtime(entry)
                    || st.st_mtim.tv_nsec != archive_entry_mtime_nsec(entry)) r = -ESTALE;
            if (!r) r = mdi_import_shared(s, source, backing, &node);
            /* Reapplying a layer can replace one name while a hardlink retains
             * its previous inode. Equal provenance must not merge those objects. */
            if (r == -EEXIST) { shared = 0; r = 0; }
        }
        if (!r && !shared) r = mdi_allocate(s, kind, kind == S_IFDIR ? 0700 : 0600, O_RDWR, target,
            kind == S_IFDIR ? loc.parent.id : NULL, &node, &fd);
        if (!r && device) r=mdi_device_number(s,&node,
            makedev(archive_entry_rdevmajor(entry),archive_entry_rdevminor(entry)));
        if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, node.id);
    }
    if (!r && kind == S_IFREG) {
        int64_t size = archive_entry_size(entry);
        if (size < 0 || (uint64_t)size > MD_IMAGE_MAX_BYTES - *bytes) r = -EFBIG;
        unsigned char buffer[65536];
        uint64_t total = 0;
        while (!r && !node.shared) {
            la_ssize_t n = archive_read_data(ar, buffer, sizeof(buffer));
            if (n < 0) { r = -EBADMSG; break; }
            if (!n) break;
            if ((uint64_t)n > (uint64_t)size - total) { r = -EBADMSG; break; }
            r = md_image_write(fd, buffer, (size_t)n); total += (uint64_t)n;
        }
        if (!r && node.shared) total = (uint64_t)size;
        if (!r && total != (uint64_t)size) r = -EBADMSG;
        if (!r) *bytes += total;
        if (!r && fd >= 0 && fsync(fd)) r = -errno;
    }
    if (fd >= 0) close(fd);
    if (!r) r = record_metadata(s, entry, &node, mode);
    if (!r && capability) r = mdi_file_capability(s,&node,capability,capability_size);
    if (!r) {
        char native[128];
        snprintf(native, sizeof(native), "/proc/self/fd/%d/%s", s->objects, node.backing);
        archive_entry_xattr_reset(entry);
        while (!r && !archive_entry_xattr_next(entry, &name, &value, &length)) {
            if (mdi_xattr_virtual(name)) {
                r=mdi_xattr_virtual_set(s,&node,name,length ? value : "",length);
                continue;
            }
            if (!backup || strncmp(name, "user.", 5)) continue;
            if (lsetxattr(native, name, value, length, XATTR_CREATE)) {
                if (errno != EEXIST) r = -errno;
                else {
                    void *existing = malloc(length ? length : 1);
                    if (!existing) r = -ENOMEM;
                    else if (lgetxattr(native, name, existing, length) != (ssize_t)length
                            || memcmp(existing, value, length)) r = -EINVAL;
                    free(existing);
                }
            }
        }
    }
    return r;
}
static int links(struct md_inode_store *s) {
    int r=0;
    /* Keep the old pass/entry ordering (including capability writes), but wake
     * only links waiting for a newly published name. No recursion or rescans. */
    while (!r) {
        sqlite3_stmt *q=NULL;
        r=mdi_prepare(s,"SELECT rowid,parent,name,target,capability,ready FROM temp.layer_links "
            "WHERE ready IS NOT NULL ORDER BY ready,rowid LIMIT 1",&q);
        int rc=r ? SQLITE_ERROR : mdi_step(s,q);
        if (!r && rc!=SQLITE_ROW && rc!=SQLITE_DONE) r=mdi_sql_failure(rc);
        if (r || rc==SQLITE_DONE) { sqlite3_finalize(q); break; }
        sqlite3_int64 id=sqlite3_column_int64(q,0),round=sqlite3_column_int64(q,5);
        char parent[33],name[NAME_MAX+1];
        int size=sqlite3_column_bytes(q,2);
        if (sqlite3_column_bytes(q,1)!=32 || size<1 || size>NAME_MAX) r=-EIO;
        else {
            memcpy(parent,sqlite3_column_text(q,1),32); parent[32]=0;
            memcpy(name,sqlite3_column_blob(q,2),(size_t)size); name[size]=0;
        }
        struct mdi_location target={0};
        int found=r ? r : mdi_walk(s,MD_INODE_ROOT,(const char *)sqlite3_column_text(q,3),MDI_NOFOLLOW,0,&target);
        if (!r && !found && target.node.kind==S_IFDIR) r=-EPERM;
        if (!r && !found) {
            r=mdi_add_name(s,parent,name,target.node.id);
            if (!r && sqlite3_column_type(q,4)!=SQLITE_NULL)
                r=mdi_file_capability(s,&target.node,sqlite3_column_blob(q,4),sqlite3_column_bytes(q,4));
        }
        sqlite3_finalize(q); q=NULL;
        if (!r && !found) {
            r=mdi_prepare(s,"UPDATE temp.layer_links SET ready=?1+(rowid<=?2),waiting_parent=NULL,waiting_name=NULL "
                "WHERE waiting_parent=?3 AND waiting_name=?4",&q);
            if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,1,round));
            if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,2,id));
            if (!r) r=mdi_bind_id(q,3,parent);
            if (!r) r=mdi_bind_name(q,4,name);
            if (!r) r=mdi_sql_error(mdi_step(s,q));
            sqlite3_finalize(q); q=NULL;
        }
        if (!r && !found) {
            r=mdi_prepare(s,"DELETE FROM temp.layer_links WHERE rowid=?1",&q);
            if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,1,id));
            if (!r) r=mdi_sql_error(mdi_step(s,q));
        } else if (!r && found==-ENOENT && *target.parent.id) {
            r=mdi_prepare(s,"UPDATE temp.layer_links SET ready=NULL,waiting_parent=?2,waiting_name=?3 WHERE rowid=?1",&q);
            if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,1,id));
            if (!r) r=mdi_bind_id(q,2,target.parent.id);
            if (!r) r=mdi_bind_name(q,3,target.name);
            if (!r) r=mdi_sql_error(mdi_step(s,q));
        } else if (!r) r=found;
        sqlite3_finalize(q);
    }
    if (!r) {
        sqlite3_stmt *q=NULL;
        r=mdi_prepare(s,"SELECT 1 FROM temp.layer_links LIMIT 1",&q);
        int rc=r ? SQLITE_ERROR : mdi_step(s,q);
        if (!r) r=rc==SQLITE_DONE ? 0 : rc==SQLITE_ROW ? -ENOENT : mdi_sql_failure(rc);
        sqlite3_finalize(q);
    }
    return r;
}
static int metadata(struct md_inode_store *s, int preserve) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT object,mode,seconds,nanos,uid,gid,atime,atime_nanos FROM temp.image_metadata", &q);
    while (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        const char *object = (const char *)sqlite3_column_text(q, 0);
        struct mdi_node node;
        r = mdi_node(s, object, &node);
        struct timespec times[2] = {{0, UTIME_OMIT},
            {sqlite3_column_int64(q, 2), sqlite3_column_int64(q, 3)}};
        if (sqlite3_column_type(q, 6) != SQLITE_NULL)
            times[0] = (struct timespec){sqlite3_column_int64(q, 6), sqlite3_column_int64(q, 7)};
        if (!r && node.kind == S_IFLNK && utimensat(s->objects, object, times, AT_SYMLINK_NOFOLLOW)) r = -errno;
        int carrier=!r && (node.kind==S_IFIFO || node.kind==S_IFCHR || node.kind==S_IFBLK);
        if (!r && carrier) {
            if (utimensat(s->objects, object, times, AT_SYMLINK_NOFOLLOW)) r = -errno;
            if (!r && !preserve) r = mdi_metadata(s, &node, node.uid, node.gid, sqlite3_column_int(q, 1));
        }
        if (!r && node.kind != S_IFLNK && !carrier && !node.shared) {
            int fd = openat(s->objects, object, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
            if (fd < 0) r = -errno;
            else {
                if (fchmod(fd, (mode_t)sqlite3_column_int(q, 1) & 01777) || futimens(fd, times) || fsync(fd)) r = -errno;
                close(fd);
            }
        }
        if (!r && preserve) r = mdi_metadata(s, &node, (uint32_t)sqlite3_column_int64(q, 4),
            (uint32_t)sqlite3_column_int64(q, 5), (mode_t)sqlite3_column_int(q, 1));
        if (!r && !preserve && node.shared) r = mdi_metadata(s, &node, geteuid(), getegid(),
            (mode_t)sqlite3_column_int(q, 1) & 01777);
        if (!r && preserve) r=mdi_acl_chmod(s,&node,(mode_t)sqlite3_column_int(q,1));
        if (!r && !preserve && node.acl_mask) r=-ENOTSUP;
    }
    sqlite3_finalize(q); return r;
}
static int read_tree(struct md_inode_store *s, int fd, uint64_t *bytes, uint64_t *entries, int overlay, int backup, int objects) {
    int r = mdi_begin(s, 1);
    if (r) return r;
    int source = 0;
    if (objects >= 0) r = mdi_source_register(s, objects, &source);
    if (!r && source) r = mdi_sources_open(s);
    if (!r) r = mdi_sql(s, "CREATE TEMP TABLE IF NOT EXISTS image_metadata(object TEXT PRIMARY KEY,mode INTEGER,seconds INTEGER,nanos INTEGER,uid INTEGER,gid INTEGER,atime INTEGER,atime_nanos INTEGER);"
        "CREATE TEMP TABLE layer_links(parent TEXT,name BLOB,target TEXT,capability BLOB,ready INTEGER DEFAULT 0,waiting_parent TEXT,waiting_name BLOB,UNIQUE(parent,name));"
        "CREATE INDEX temp.layer_links_ready ON layer_links(ready) WHERE ready IS NOT NULL;"
        "CREATE INDEX temp.layer_links_waiting ON layer_links(waiting_parent,waiting_name) WHERE waiting_parent IS NOT NULL;"
        "CREATE TEMP TABLE layer_acls(path TEXT,type INTEGER,value BLOB,PRIMARY KEY(path,type));"
        "CREATE TEMP TABLE layer_paths(path TEXT PRIMARY KEY);");
    /* OCI whiteouts only remove lower entries, regardless of tar ordering. */
    for (unsigned pass = 0; pass < 2 && !r; ++pass) {
        struct archive *ar = archive_read_new();
        if (!ar) { r = -ENOMEM; break; }
        archive_read_support_format_tar(ar);
        archive_read_support_filter_gzip(ar);
        archive_read_support_filter_zstd(ar);
        if (lseek(fd, 0, SEEK_SET) < 0) r = -errno;
        if (!r && archive_read_open_fd(ar, fd, 65536) != ARCHIVE_OK) r = -EBADMSG;
        struct archive_entry *entry;
        uint64_t index = 0;
        while (!r) {
            int rc = archive_read_next_header(ar, &entry);
            if (rc == ARCHIVE_EOF) break;
            if (rc != ARCHIVE_OK) { r = -EBADMSG; break; }
            ++index;
            if (!pass && ++*entries > MD_IMAGE_MAX_ENTRIES) { r = -EFBIG; break; }
            char path[PATH_MAX];
            const char *name_in = archive_entry_pathname(entry);
            if (backup) {
                if (name_in && !strcmp(name_in, "magicdesk-backup.json")) {
                    if (index != 1) { r = -EINVAL; break; }
                    continue;
                }
                if (!name_in || strncmp(name_in, "rootfs/", 7)) { r = -EINVAL; break; }
                name_in += 7;
                const char *link = archive_entry_hardlink(entry);
                if (link) {
                    if (strncmp(link, "rootfs/", 7)) { r = -EINVAL; break; }
                    char *target = strdup(link + 7);
                    if (!target) { r = -ENOMEM; break; }
                    archive_entry_set_hardlink(entry, target);
                    free(target);
                }
            }
            r = path_name(name_in, path);
            if (!r && !pass) r = unique_path(s, path);
            if (r) break;
            const char *name = strrchr(path, '/') + 1;
            int marker = overlay && !strncmp(name, ".wh.", 4);
            if (marker && (archive_entry_filetype(entry) != S_IFREG || archive_entry_size(entry) != 0
                    || archive_entry_hardlink(entry))) { r = -EINVAL; break; }
            if (!pass && marker) { int removed = whiteout(s, path); if (removed < 0) r = removed; }
            if (pass && !marker) r = entry_file(s, ar, entry, path, bytes, source, index, backup);
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
int md_image_layer(struct md_inode_store *s, int fd, uint64_t *bytes, uint64_t *entries) {
    return read_tree(s, fd, bytes, entries, 1, 0, -1);
}
int md_image_layer_shared(struct md_inode_store *s, int fd, int objects, uint64_t *bytes, uint64_t *entries) {
    return read_tree(s, fd, bytes, entries, 1, 0, objects);
}
int md_image_rootfs(struct md_inode_store *s, int fd, uint64_t *bytes, uint64_t *entries) {
    return read_tree(s, fd, bytes, entries, 0, 0, -1);
}
int md_image_backup_tree(struct md_inode_store *s, int fd, uint64_t *bytes, uint64_t *entries) {
    return read_tree(s, fd, bytes, entries, 0, 1, -1);
}
int md_image_layers_finish(struct md_inode_store *s, int preserve) {
    int r = mdi_begin(s, 1);
    if (r) return r;
    /* The unpublished tree stays traversable while applying layers. Final
     * permissions belong to publication, not the importer's access rights. */
    r=mdi_sql(s,"CREATE TEMP TABLE IF NOT EXISTS image_metadata(object TEXT PRIMARY KEY,mode INTEGER,seconds INTEGER,nanos INTEGER,uid INTEGER,gid INTEGER,atime INTEGER,atime_nanos INTEGER)");
    if (!r && preserve) {
        struct mdi_node root;
        r = mdi_node(s, MDI_ROOT, &root);
        if (!r) r = mdi_metadata(s, &root, 0, 0, 0755);
    }
    if (!r) r = metadata(s, preserve);
    if (!r) r = mdi_sql(s, preserve ? "INSERT INTO properties VALUES('image-users',1)"
        : "INSERT INTO properties VALUES('image-users',0)");
    if (!r) r = mdi_sql(s, "DROP TABLE temp.image_metadata");
    /* No guest owns this unpublished namespace. Drop unreachable metadata and
     * lower dependencies now; runtime open-unlinked objects are never compacted. */
    if (!r) r = mdi_sql(s, "CREATE TEMP TABLE image_live(object TEXT PRIMARY KEY);"
        "INSERT INTO image_live WITH RECURSIVE tree(object) AS (SELECT '" MDI_ROOT "' UNION "
        "SELECT n.object FROM names n JOIN tree t ON n.parent=t.object) SELECT object FROM tree;"
        "DELETE FROM file_paths;"
        "DELETE FROM names WHERE parent NOT IN image_live;"
        "DELETE FROM backings WHERE object NOT IN image_live;"
        "DELETE FROM inode_acls WHERE object NOT IN image_live;"
        "DELETE FROM file_capabilities WHERE object NOT IN image_live;"
        "DELETE FROM objects WHERE object NOT IN image_live;"
        "INSERT INTO file_paths SELECT o.object,n.parent,n.name,o.name_count>1 "
            "FROM objects o JOIN names n ON n.object=o.object WHERE o.kind!=16384 GROUP BY o.object;"
        "DELETE FROM sources WHERE NOT EXISTS (SELECT 1 FROM objects "
            "WHERE objects.source=sources.id AND objects.scope=sources.scope) "
            "AND NOT EXISTS(SELECT 1 FROM inode_channels c WHERE c.source=sources.id AND c.scope=sources.scope);"
        "DROP TABLE image_live");
    s->sync_requested=1;
    r=mdi_commit(s,r);
    if (!r) {
        r=mdi_begin(s,0);
        if (!r) r=mdi_finish(s,mdi_discard_untracked_backings(s));
    }
    return r;
}
