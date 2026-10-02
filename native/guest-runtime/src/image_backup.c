#define _GNU_SOURCE
#include "image_backup.h"
#include "image_publish.h"
#include "image_layer.h"
#include "image_io.h"
#include "image_json.h"
#include "inode_internal.h"
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <unistd.h>

static const char default_config[] = "{\"architecture\":\"arm64\",\"os\":\"linux\",\"config\":{\"Cmd\":[\"/bin/sh\"],\"WorkingDir\":\"/\"}}";
static int config_read(struct md_inode_store *s, char **out) {
    int fd = openat(s->root, "image-config.json", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        if (errno != ENOENT) return -errno;
        *out = strdup(default_config); return *out ? 0 : -ENOMEM;
    }
    int r = md_image_read(fd, 8*1024*1024, out); close(fd);
    if (!r) r = md_json_validate(s->db, *out, strlen(*out));
    return r;
}
static int header(struct archive *ar, const char *name, const void *data, size_t size) {
    struct archive_entry *e = archive_entry_new();
    if (!e) return -ENOMEM;
    archive_entry_set_pathname(e, name); archive_entry_set_filetype(e, AE_IFREG);
    archive_entry_set_perm(e, 0600); archive_entry_set_size(e, size);
    int r = archive_write_header(ar, e) == ARCHIVE_OK ? 0 : -EIO;
    if (!r && archive_write_data(ar, data, size) != (la_ssize_t)size) r = -EIO;
    archive_entry_free(e); return r;
}
static int security(struct md_inode_store *s, const struct mdi_node *node, struct archive_entry *entry) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT type,value FROM inode_acls WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    while (!r) {
        int rc = mdi_step(s, q); if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        const struct md_acl *acl = sqlite3_column_blob(q, 1);
        size_t size = sqlite3_column_bytes(q, 1);
        if (!md_acl_valid(acl, size)) { r = -EIO; break; }
        int type = sqlite3_column_int(q, 0) == MD_ACL_ACCESS ? ARCHIVE_ENTRY_ACL_TYPE_ACCESS : ARCHIVE_ENTRY_ACL_TYPE_DEFAULT;
        for (unsigned i = 0; !r && i < (size-4)/8; ++i) {
            const struct md_acl_entry *a = &acl->entries[i];
            int tag = a->tag == MD_ACL_USER_OBJ ? ARCHIVE_ENTRY_ACL_USER_OBJ : a->tag == MD_ACL_USER ? ARCHIVE_ENTRY_ACL_USER
                : a->tag == MD_ACL_GROUP_OBJ ? ARCHIVE_ENTRY_ACL_GROUP_OBJ : a->tag == MD_ACL_GROUP ? ARCHIVE_ENTRY_ACL_GROUP
                : a->tag == MD_ACL_MASK ? ARCHIVE_ENTRY_ACL_MASK : ARCHIVE_ENTRY_ACL_OTHER;
            if (archive_entry_acl_add_entry(entry, type, a->permissions, tag, a->id, NULL) != ARCHIVE_OK) r = -EIO;
        }
    }
    sqlite3_finalize(q); q = NULL;
    if (!r) r = mdi_prepare(s, "SELECT value FROM file_capabilities WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    if (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_ROW) archive_entry_xattr_add_entry(entry, "security.capability", sqlite3_column_blob(q, 0), sqlite3_column_bytes(q, 0));
        else if (rc != SQLITE_DONE) r = mdi_sql_failure(rc);
    }
    sqlite3_finalize(q); return r;
}
static int attributes(struct md_inode_store *s, const struct mdi_node *node, struct archive_entry *entry) {
    char path[128];
    int directory = mdi_backing_directory(s, node);
    if (directory < 0) return directory;
    snprintf(path, sizeof(path), "/proc/self/fd/%d/%s", directory, node->backing);
    ssize_t size = llistxattr(path, NULL, 0);
    if (size < 0) return -errno;
    if (size > 65536) return -E2BIG;
    char *names = malloc(size ? (size_t)size : 1);
    if (!names) return -ENOMEM;
    int r = llistxattr(path, names, size) == size ? 0 : -ESTALE;
    for (size_t at = 0; !r && at < (size_t)size;) {
        const char *name = names + at;
        size_t length = strnlen(name, (size_t)size-at);
        if (length == (size_t)size-at) { r = -EIO; break; }
        at += length+1;
        if (!strcmp(name, "security.selinux")) continue;
        if (strncmp(name, "user.", 5)) { r = -ENOTSUP; break; }
        ssize_t count = lgetxattr(path, name, NULL, 0);
        if (count < 0 || count > 65536) { r = count < 0 ? -errno : -E2BIG; break; }
        void *value = malloc(count ? (size_t)count : 1);
        if (!value) { r = -ENOMEM; break; }
        if (lgetxattr(path, name, value, count) != count) r = -ESTALE;
        if (!r) archive_entry_xattr_add_entry(entry, name, value, count);
        free(value);
    }
    free(names); return r;
}
static int write_tree(struct md_inode_store *s, struct archive *ar) {
    int r = mdi_sql(s, "CREATE TEMP TABLE exported(object TEXT PRIMARY KEY,path BLOB NOT NULL)");
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "WITH RECURSIVE tree(object,path,depth) AS (SELECT '" MDI_ROOT "','',0 UNION ALL "
        "SELECT n.object,t.path||'/'||CAST(n.name AS TEXT),t.depth+1 FROM tree t JOIN names n ON n.parent=t.object) "
        "SELECT object,path FROM tree ORDER BY depth,path", &q);
    uint64_t total = 0, entries = 0;
    while (!r) {
        int rc = mdi_step(s, q); if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        const char *path = (const char *)sqlite3_column_text(q, 1);
        if (!path || strlen(path) >= PATH_MAX-8 || ++entries > 200000) { r = -EFBIG; break; }
        struct mdi_node node; struct stat st;
        r = mdi_node(s, (const char *)sqlite3_column_text(q, 0), &node);
        if (!r) r = mdi_stat(s, &node, &st);
        if (r) break;
        if (node.kind == S_IFSOCK) { fprintf(stderr, "Guest backup: omitted socket %s\n", path); continue; }
        struct archive_entry *e = archive_entry_new();
        if (!e) { r = -ENOMEM; break; }
        char name[PATH_MAX]; snprintf(name, sizeof(name), "rootfs/%s", *path ? path+1 : ".");
        archive_entry_set_pathname(e, name); archive_entry_set_mode(e, st.st_mode);
        archive_entry_set_uid(e, st.st_uid); archive_entry_set_gid(e, st.st_gid);
        archive_entry_set_mtime(e, st.st_mtim.tv_sec, st.st_mtim.tv_nsec);
        archive_entry_set_atime(e, st.st_atim.tv_sec, st.st_atim.tv_nsec);
        archive_entry_set_size(e, 0);
        sqlite3_stmt *seen = NULL;
        r = mdi_prepare(s, "SELECT path FROM exported WHERE object=?1", &seen);
        if (!r) r = mdi_bind_id(seen, 1, node.id);
        int linked = 0;
        if (!r) {
            int result = mdi_step(s, seen);
            if (result == SQLITE_ROW) { archive_entry_set_hardlink(e, (const char *)sqlite3_column_text(seen, 0)); linked = 1; }
            else if (result != SQLITE_DONE) r = mdi_sql_failure(result);
        }
        sqlite3_finalize(seen); seen = NULL;
        if (!r && !linked && node.kind != S_IFDIR) {
            r = mdi_prepare(s, "INSERT INTO exported VALUES(?1,?2)", &seen);
            if (!r) r = mdi_bind_id(seen, 1, node.id);
            if (!r) r = mdi_sql_error(sqlite3_bind_text(seen, 2, name, -1, SQLITE_STATIC));
            if (!r) r = mdi_sql_error(mdi_step(s, seen));
            sqlite3_finalize(seen);
        }
        int fd = -1;
        if (!r && !linked && node.kind == S_IFREG) {
            if (st.st_size < 0 || (uint64_t)st.st_size > 8ULL*1024*1024*1024-total) r = -EFBIG;
            else { total += st.st_size; archive_entry_set_size(e, st.st_size); }
            int directory = mdi_backing_directory(s, &node);
            if (!r && (fd = openat(directory, node.backing, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)) < 0) r = -errno;
        }
        if (!r && !linked && node.kind == S_IFLNK) {
            char target[PATH_MAX]; ssize_t n = readlinkat(s->objects, node.backing, target, sizeof(target)-1);
            if (n < 0) r = -errno; else { target[n] = 0; archive_entry_set_symlink(e, target); }
        }
        if (!r && !linked) r = security(s, &node, e);
        if (!r && !linked) r = attributes(s, &node, e);
        /* Libarchive represents ACL GROUP_OBJ in its mode field, whereas Linux
         * stat uses the ACL mask. Preserve the independent guest mode explicitly. */
        unsigned char mode[2] = {st.st_mode & 255, (st.st_mode & 07777) >> 8};
        if (!linked) archive_entry_xattr_add_entry(e, "magicdesk.backup.mode", mode, sizeof(mode));
        if (!r && archive_write_header(ar, e) != ARCHIVE_OK) r = -EIO;
        char buffer[65536];
        for (off_t offset = 0; !r && fd >= 0 && offset < st.st_size;) {
            size_t n = (uint64_t)(st.st_size-offset) > sizeof(buffer) ? sizeof(buffer) : (size_t)(st.st_size-offset);
            ssize_t got = pread(fd, buffer, n, offset);
            if (got < 0 && errno == EINTR) continue;
            if (got <= 0) { r = got < 0 ? -errno : -EIO; break; }
            if (archive_write_data(ar, buffer, got) != got) r = -EIO;
            offset += got;
        }
        if (fd >= 0) close(fd);
        archive_entry_free(e);
    }
    sqlite3_finalize(q); return r;
}
int md_image_backup(const char *store, const char *destination) {
    struct md_inode_store *s = NULL;
    int r = md_inode_store_open_exclusive(store, &s);
    if (r) return r;
    char *config = NULL, *metadata = NULL;
    r = config_read(s, &config);
    sqlite3_stmt *q = NULL;
    int guest_users = 0;
    if (!r) r = mdi_prepare(s, "SELECT value FROM properties WHERE key='image-users'", &q);
    if (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_ROW) guest_users = sqlite3_column_int(q, 0) != 0;
        else if (rc != SQLITE_DONE) r = mdi_sql_failure(rc);
    }
    sqlite3_finalize(q);
    if (!r && asprintf(&metadata, "{\"format\":1,\"guestUsers\":%d,\"imageConfig\":%s}", guest_users, config) < 0) r = -ENOMEM;
    char temporary[PATH_MAX]; char id[33];
    if (!r) r = mdi_random_id(id);
    if (!r && (!destination || destination[0] != '/')) r = -EINVAL;
    if (!r && snprintf(temporary, sizeof(temporary), "%s.part-%s", destination, id) >= (int)sizeof(temporary)) r = -ENAMETOOLONG;
    char directory[PATH_MAX];
    int parent = -1;
    if (!r) {
        if (strlen(destination) >= sizeof(directory)) r = -ENAMETOOLONG;
        else {
            strcpy(directory, destination);
            char *end = strrchr(directory, '/');
            if (end == directory) end[1] = 0; else *end = 0;
            if ((parent = open(directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC)) < 0) r = -errno;
        }
    }
    int fd = -1;
    if (!r && (fd = open(temporary, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600)) < 0) r = -errno;
    struct archive *ar = archive_write_new();
    if (!ar && !r) r = -ENOMEM;
    if (!r && (archive_write_set_format_pax(ar) != ARCHIVE_OK || archive_write_add_filter_zstd(ar) != ARCHIVE_OK
            || archive_write_open_fd(ar, fd) != ARCHIVE_OK)) r = -EIO;
    if (!r) r = header(ar, "magicdesk-backup.json", metadata, strlen(metadata));
    if (!r) r = write_tree(s, ar);
    if (ar) { if (archive_write_close(ar) != ARCHIVE_OK && !r) r = -EIO; archive_write_free(ar); }
    if (!r && fsync(fd)) r = -errno;
    if (!r && syscall(SYS_renameat2, AT_FDCWD, temporary, AT_FDCWD, destination, RENAME_NOREPLACE)) r = -errno;
    if (!r && fsync(parent)) r = -errno;
    if (parent >= 0) close(parent);
    if (fd >= 0) { close(fd); unlink(temporary); }
    free(config); free(metadata); md_inode_store_close(s); return r;
}
static int backup_config(int fd, sqlite3 *db, char **out, int *guest_users) {
    struct archive *ar = archive_read_new();
    if (!ar) return -ENOMEM;
    archive_read_support_format_tar(ar); archive_read_support_filter_zstd(ar);
    struct archive_entry *entry = NULL;
    int r = archive_read_open_fd(ar, fd, 65536) == ARCHIVE_OK ? 0 : -EBADMSG;
    if (!r && (archive_read_next_header(ar, &entry) != ARCHIVE_OK
            || strcmp(archive_entry_pathname(entry), "magicdesk-backup.json")
            || archive_entry_filetype(entry) != S_IFREG || archive_entry_hardlink(entry)
            || archive_entry_size(entry) <= 0 || archive_entry_size(entry) > 8*1024*1024)) r = -EBADMSG;
    char *data = NULL;
    if (!r) {
        size_t size = archive_entry_size(entry); data = malloc(size+1);
        if (!data) r = -ENOMEM;
        else {
            for (size_t at = 0; !r && at < size;) {
                la_ssize_t n = archive_read_data(ar, data+at, size-at);
                if (n <= 0) r = -EBADMSG; else at += (size_t)n;
            }
            if (!r) { data[size] = 0; r = md_json_validate(db, data, size); }
        }
    }
    sqlite3_stmt *q = NULL;
    if (!r) r = md_json_query(db, data, "SELECT json_extract(?1,'$.imageConfig'),json_extract(?1,'$.guestUsers') "
        "WHERE json_extract(?1,'$.format')=1 AND json_type(?1,'$.imageConfig')='object' "
        "AND json_type(?1,'$.guestUsers')='integer' AND json_extract(?1,'$.guestUsers') IN (0,1)", &q);
    if (!r) {
        if (sqlite3_step(q) != SQLITE_ROW) r = -EPROTONOSUPPORT;
        else {
            *guest_users = sqlite3_column_int(q, 1);
            if (!(*out = strdup((const char *)sqlite3_column_text(q, 0)))) r = -ENOMEM;
        }
    }
    sqlite3_finalize(q); free(data); archive_read_free(ar); return r;
}
int md_image_archive_import(const char *archive, const char *destination, int backup) {
    int fd = open(archive, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -errno;
    struct md_image_publish p = {.parent=-1, .stage=-1};
    int r = md_image_publish_begin(destination, &p);
    int snapshot = -1;
    if (!r) r = md_image_snapshot(fd, p.stage, 8ULL*1024*1024*1024, &snapshot);
    struct md_inode_store *s = NULL;
    if (!r) r = md_inode_store_open(p.path, 1, &s);
    char *config = NULL;
    int guest_users = 1;
    if (!r) r = backup ? backup_config(snapshot, s->db, &config, &guest_users) : ((config = strdup(default_config)) ? 0 : -ENOMEM);
    uint64_t bytes = 0, entries = 0;
    if (!r) r = backup ? md_image_backup_tree(s, snapshot, &bytes, &entries) : md_image_rootfs(s, snapshot, &bytes, &entries);
    if (!r) r = md_image_layers_finish(s, 1);
    if (!r && !guest_users) r = mdi_sql(s, "UPDATE properties SET value=0 WHERE key='image-users'");
    int out = -1;
    if (!r && (out = openat(s->root, "image-config.json", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600)) < 0) r = -errno;
    if (!r) r = md_image_write(out, config, strlen(config));
    if (!r && fsync(out)) r = -errno;
    if (out >= 0) close(out);
    if (!r && !backup) r = md_inode_store_seal(s);
    if (!r && fsync(s->root)) r = -errno;
    md_inode_store_close(s);
    if (!r) r = md_image_publish_commit(&p);
    if (snapshot >= 0) close(snapshot);
    md_image_publish_close(&p); free(config); close(fd); return r;
}
