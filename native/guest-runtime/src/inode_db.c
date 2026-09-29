#define _GNU_SOURCE
#include "inode_internal.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <unistd.h>

int mdi_sql_error(int rc) {
    if (rc == SQLITE_CONSTRAINT_PRIMARYKEY || rc == SQLITE_CONSTRAINT_UNIQUE) return -EEXIST;
    switch (rc & 255) {
    case SQLITE_OK: case SQLITE_ROW: case SQLITE_DONE: return 0;
    case SQLITE_BUSY: case SQLITE_LOCKED: return -EAGAIN;
    case SQLITE_NOMEM: return -ENOMEM;
    case SQLITE_FULL: return -ENOSPC;
    case SQLITE_READONLY: return -EROFS;
    default: return -EIO;
    }
}
int mdi_sql(struct md_inode_store *s, const char *text) {
    return mdi_sql_error(sqlite3_exec(s->db, text, NULL, NULL, NULL));
}
int mdi_sql_failure(int rc) {
    int error = mdi_sql_error(rc);
    return error ? error : -EIO;
}
int mdi_prepare(struct md_inode_store *s, const char *text, sqlite3_stmt **out) {
    return mdi_sql_error(sqlite3_prepare_v2(s->db, text, -1, out, NULL));
}
int mdi_name_valid(const char *name) {
    if (!name) return -EFAULT;
    size_t n = strnlen(name, NAME_MAX+1);
    if (!n) return -ENOENT;
    if (n > NAME_MAX) return -ENAMETOOLONG;
    if (!strcmp(name, ".") || !strcmp(name, "..") || strchr(name, '/')) return -EINVAL;
    return 0;
}
int mdi_bind_name(sqlite3_stmt *q, int slot, const char *name) {
    return mdi_sql_error(sqlite3_bind_blob(q, slot, name, (int)strlen(name), SQLITE_TRANSIENT));
}
int mdi_bind_id(sqlite3_stmt *q, int slot, const char *id) {
    return mdi_sql_error(sqlite3_bind_text(q, slot, id, -1, SQLITE_TRANSIENT));
}
static int store_lock(struct md_inode_store *s) {
    if (s->locked) return -EDEADLK;
    if (flock(s->objects, LOCK_EX | LOCK_NB)) {
        if (errno != EWOULDBLOCK) return -errno;
#ifdef MD_INODE_TESTING
        if (s->observe) s->observe(MD_STORE_CONTENDED, s->context);
#endif
        /* EVENT_WAIT: another store operation releases its kernel lock (also on
         * owner death). Signal interruption fails before BEGIN, never replays IO.
         * Client RPC deadlines remain cancellation bounds on observation only. */
        if (flock(s->objects, LOCK_EX)) return -errno;
    }
    s->locked = 1;
    return 0;
}
static int store_unlock(struct md_inode_store *s, int result) {
    if (s->locked) {
        if (flock(s->objects, LOCK_UN)) return result ? result : -errno;
        s->locked = 0;
    }
    return result;
}
int mdi_begin(struct md_inode_store *s, int write) {
    int r = store_lock(s);
    if (r) return r;
    r = mdi_sql(s, write ? "BEGIN IMMEDIATE" : "BEGIN");
    return r ? store_unlock(s, r) : 0;
}
int mdi_finish(struct md_inode_store *s, int result) {
    if (!result) result = mdi_sql(s, "COMMIT");
    if (result) mdi_sql(s, "ROLLBACK");
    return store_unlock(s, result);
}
#ifndef MD_INODE_TESTING
enum { MD_OBJECT_SYNCED, MD_NAMESPACE_STAGED, MD_NAMESPACE_COMMITTED };
#endif
static int checkpoint(struct md_inode_store *s, int point) {
#ifdef MD_INODE_TESTING
    if (s->observe) {
        /* Force a real rollback journal before the deterministic kill point. */
        if (point == MD_NAMESPACE_STAGED) {
            int r = mdi_sql_error(sqlite3_db_cacheflush(s->db));
            if (r) return r;
        }
        s->observe(point, s->context);
    }
#else
    (void)s; (void)point;
#endif
    return 0;
}
int mdi_commit(struct md_inode_store *s, int r) {
    if (!r) r = checkpoint(s, MD_NAMESPACE_STAGED);
    r = mdi_finish(s, r);
    if (!r) r = checkpoint(s, MD_NAMESPACE_COMMITTED);
    return r;
}
static int read_id(sqlite3_stmt *q, int column, char out[33]) {
    const char *value = (const char *)sqlite3_column_text(q, column);
    if (!value || sqlite3_column_bytes(q, column) != 32
            || strspn(value, "0123456789abcdef") != 32) return -EIO;
    memcpy(out, value, 33);
    return 0;
}
static int read_node(sqlite3_stmt *q, struct mdi_node *node) {
    int rc = sqlite3_step(q);
    if (rc != SQLITE_ROW) return rc == SQLITE_DONE ? -ENOENT : mdi_sql_failure(rc);
    struct mdi_node value = {0};
    int r = read_id(q, 0, value.id);
    value.kind = (mode_t)sqlite3_column_int(q, 1);
    value.device = (dev_t)sqlite3_column_int64(q, 2);
    value.inode = (ino_t)sqlite3_column_int64(q, 3);
    if (!r && value.kind == S_IFDIR) r = read_id(q, 4, value.parent);
    if (!r && value.kind != S_IFDIR && value.kind != S_IFREG && value.kind != S_IFLNK && value.kind != S_IFSOCK) r = -EIO;
    if (!r) *node = value;
    return r;
}
int mdi_node(struct md_inode_store *s, const char *id, struct mdi_node *node) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT object,kind,device,inode,parent FROM objects WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, id);
    if (!r) r = read_node(q, node);
    sqlite3_finalize(q); return r;
}
int mdi_fd(struct md_inode_store *s, int fd, struct mdi_node *node) {
    struct stat st;
    if (fstat(fd, &st)) return -errno;
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT object,kind,device,inode,parent FROM objects WHERE device=?1 AND inode=?2", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, (sqlite3_int64)st.st_ino));
    if (!r) r = read_node(q, node);
    sqlite3_finalize(q); return r == -ENOENT ? -EXDEV : r;
}
int mdi_lookup(struct md_inode_store *s, const char *parent, const char *name, struct mdi_node *node) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT o.object,o.kind,o.device,o.inode,o.parent FROM objects o "
        "JOIN names n ON n.object=o.object WHERE n.parent=?1 AND n.name=?2", &q);
    if (!r) r = mdi_bind_id(q, 1, parent);
    if (!r) r = mdi_bind_name(q, 2, name);
    if (!r) r = read_node(q, node);
    sqlite3_finalize(q); return r;
}
int mdi_add_name(struct md_inode_store *s, const char *parent, const char *name, const char *object) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "INSERT INTO names(parent,name,object) VALUES(?1,?2,?3)", &q);
    if (!r) r = mdi_bind_id(q, 1, parent);
    if (!r) r = mdi_bind_name(q, 2, name);
    if (!r) r = mdi_bind_id(q, 3, object);
    if (!r) r = mdi_sql_error(sqlite3_step(q));
    sqlite3_finalize(q); return r;
}
int mdi_delete_name(struct md_inode_store *s, const char *parent, const char *name) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "DELETE FROM names WHERE parent=?1 AND name=?2", &q);
    if (!r) r = mdi_bind_id(q, 1, parent);
    if (!r) r = mdi_bind_name(q, 2, name);
    if (!r) r = mdi_sql_error(sqlite3_step(q));
    if (!r && !sqlite3_changes(s->db)) r = -ENOENT;
    sqlite3_finalize(q); return r;
}
int mdi_reparent(struct md_inode_store *s, const struct mdi_node *node, const char *parent) {
    if (node->kind != S_IFDIR) return 0;
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "UPDATE objects SET parent=?1 WHERE object=?2", &q);
    if (!r) r = mdi_bind_id(q, 1, parent);
    if (!r) r = mdi_bind_id(q, 2, node->id);
    if (!r) r = mdi_sql_error(sqlite3_step(q));
    sqlite3_finalize(q); return r;
}
int mdi_empty(struct md_inode_store *s, const struct mdi_node *node) {
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT NOT EXISTS(SELECT 1 FROM names WHERE parent=?1)", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    if (!r) {
        int rc = sqlite3_step(q);
        r = rc == SQLITE_ROW ? sqlite3_column_int(q, 0) : mdi_sql_failure(rc);
    }
    sqlite3_finalize(q); return r;
}
int mdi_stat(struct md_inode_store *s, const struct mdi_node *node, struct stat *st) {
    if (fstatat(s->objects, node->id, st, AT_SYMLINK_NOFOLLOW)) return -errno;
    mode_t backing = node->kind == S_IFSOCK ? S_IFREG : node->kind;
    if (st->st_dev != node->device || st->st_ino != node->inode || (st->st_mode & S_IFMT) != backing) return -EIO;
    st->st_mode = (st->st_mode & ~S_IFMT) | node->kind;
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "SELECT count(*), (SELECT count(*) FROM names n JOIN objects o ON n.object=o.object "
        "WHERE n.parent=?1 AND o.kind=?2) FROM names WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 2, S_IFDIR));
    if (!r) {
        int rc = sqlite3_step(q);
        if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
        else {
            st->st_nlink = (nlink_t)sqlite3_column_int64(q, 0);
            if (node->kind == S_IFDIR && (st->st_nlink || !strcmp(node->id, MDI_ROOT)))
                st->st_nlink = 2 + (nlink_t)sqlite3_column_int64(q, 1);
        }
    }
    sqlite3_finalize(q); return r;
}
int mdi_access(struct md_inode_store *s, const struct mdi_node *node, int mode) {
    if (node->kind != S_IFDIR) return -ENOTDIR;
    return faccessat(s->objects, node->id, mode, AT_EACCESS) ? -errno : 0;
}
int mdi_parent_writable(struct md_inode_store *s, const struct mdi_node *node) {
    int r = mdi_access(s, node, W_OK | X_OK);
    struct stat st;
    if (!r) r = mdi_stat(s, node, &st);
    if (!r && !st.st_nlink) r = -ENOENT;
    return r;
}
static int make_object(struct md_inode_store *s, const char *id, mode_t kind, mode_t mode, int flags,
        const char *target, const char *parent, struct mdi_node *node, int *fd) {
    *fd = -1;
    if (kind == S_IFREG || kind == S_IFSOCK) {
        *fd = openat(s->objects, id, O_CREAT | O_EXCL | flags | O_CLOEXEC | O_NOFOLLOW, mode);
        if (*fd < 0) return -errno;
    } else if (kind == S_IFDIR) {
        if (mkdirat(s->objects, id, mode)) return -errno;
    } else if (symlinkat(target, s->objects, id)) return -errno;
    struct stat st;
    int r = fstatat(s->objects, id, &st, AT_SYMLINK_NOFOLLOW) ? -errno : 0;
    if (!r && *fd >= 0 && fsync(*fd)) r = -errno;
    if (!r && fsync(s->objects)) r = -errno;
    if (!r) r = checkpoint(s, MD_OBJECT_SYNCED);
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "INSERT INTO objects VALUES(?1,?2,?3,?4,?5)", &q);
    if (!r) r = mdi_bind_id(q, 1, id);
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 2, (int)kind));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 3, (sqlite3_int64)st.st_dev));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 4, (sqlite3_int64)st.st_ino));
    if (!r) r = parent ? mdi_bind_id(q, 5, parent) : mdi_sql_error(sqlite3_bind_null(q, 5));
    if (!r) r = mdi_sql_error(sqlite3_step(q));
    sqlite3_finalize(q);
    if (!r) r = mdi_node(s, id, node);
    return r;
}
int mdi_allocate(struct md_inode_store *s, mode_t kind, mode_t mode, int flags, const char *target,
        const char *parent, struct mdi_node *node, int *fd) {
    unsigned char random[16];
    long n = syscall(SYS_getrandom, random, sizeof(random), 0);
    if (n != sizeof(random)) return n < 0 ? -errno : -EIO;
    char id[33];
    for (size_t i = 0; i < sizeof(random); ++i) {
        id[2*i] = "0123456789abcdef"[random[i] >> 4];
        id[2*i+1] = "0123456789abcdef"[random[i] & 15];
    }
    id[32] = 0;
    if (!strcmp(id, MDI_ROOT)) return -EEXIST;
    return make_object(s, id, kind, mode, flags, target, parent, node, fd);
}
int md_inode_store_open(const char *directory, int create, struct md_inode_store **out) {
    if (!out) return -EFAULT;
    *out = NULL;
    if (!directory || directory[0] != '/') return -EINVAL;
    char file[PATH_MAX];
    if (snprintf(file, sizeof(file), "%s/namespace.db", directory) >= (int)sizeof(file)) return -ENAMETOOLONG;
    if (create && mkdir(directory, 0700)) return -errno;
    int root = open(directory, O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (root < 0) return -errno;
    int r = create && mkdirat(root, "objects", 0700) ? -errno : 0;
    struct md_inode_store *s = calloc(1, sizeof(*s));
    if (!s) { close(root); return -ENOMEM; }
    s->objects = -1;
    if (!r && (s->objects = openat(root, "objects", O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) < 0) r = -errno;
    if (!r) r = store_lock(s);
    if (!r) r = mdi_sql_error(sqlite3_open_v2(file, &s->db,
        SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX | SQLITE_OPEN_NOFOLLOW
            | (create ? SQLITE_OPEN_CREATE : 0), NULL));
    if (!r) r = mdi_sql_error(sqlite3_extended_result_codes(s->db, 1));
    if (!r) r = mdi_sql(s, "PRAGMA foreign_keys=ON; PRAGMA synchronous=EXTRA; PRAGMA trusted_schema=OFF");
    if (!r && create) {
        r = mdi_sql(s, "PRAGMA journal_mode=DELETE; BEGIN IMMEDIATE;"
            "CREATE TABLE objects(object TEXT PRIMARY KEY CHECK(length(object)=32),kind INTEGER NOT NULL,"
                "device INTEGER NOT NULL,inode INTEGER NOT NULL,parent TEXT REFERENCES objects,"
                "UNIQUE(device,inode),CHECK(kind IN (32768,16384,40960,49152)),"
                "CHECK((kind=16384)=(parent IS NOT NULL))) STRICT;"
            "CREATE TABLE names(cookie INTEGER PRIMARY KEY AUTOINCREMENT,"
                "parent TEXT NOT NULL REFERENCES objects,name BLOB NOT NULL,"
                "object TEXT NOT NULL REFERENCES objects,UNIQUE(parent,name),"
                "CHECK(cookie>0 AND cookie<9223372036854775805)) STRICT;"
            "CREATE INDEX names_object ON names(object);"
            "CREATE INDEX names_cursor ON names(parent,cookie);"
            "CREATE TABLE sockets(object TEXT PRIMARY KEY REFERENCES objects, address TEXT NOT NULL) STRICT;"
            "PRAGMA user_version=4;");
        struct mdi_node node; int fd;
        if (!r) r = make_object(s, MDI_ROOT, S_IFDIR, 0700, 0, NULL, MDI_ROOT, &node, &fd);
        if (!r) r = mdi_sql(s, "COMMIT");
        if (r) mdi_sql(s, "ROLLBACK");
    }
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "PRAGMA user_version", &q);
    if (!r) {
        int rc = sqlite3_step(q);
        if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
        else if (sqlite3_column_int(q, 0) != 4) r = -EPROTONOSUPPORT;
    }
    sqlite3_finalize(q); q = NULL;
    if (!r) r = mdi_prepare(s, "PRAGMA journal_mode", &q);
    if (!r) {
        int rc = sqlite3_step(q);
        if (rc != SQLITE_ROW) r = mdi_sql_failure(rc);
        else if (strcmp((const char *)sqlite3_column_text(q, 0), "delete")) r = -ENOTSUP;
    }
    sqlite3_finalize(q);
    if (!r && create && fsync(root)) r = -errno;
    r = store_unlock(s, r);
    close(root);
    if (r) md_inode_store_close(s); else *out = s;
    return r;
}
void md_inode_store_close(struct md_inode_store *s) {
    if (!s) return;
    if (s->db) sqlite3_close(s->db);
    if (s->objects >= 0) close(s->objects);
    free(s);
}
#ifdef MD_INODE_TESTING
void md_inode_observe(struct md_inode_store *s, void (*observer)(enum md_inode_checkpoint, void *), void *context) {
    s->observe = observer; s->context = context;
}
int md_inode_audit(struct md_inode_store *s, struct md_inode_audit *audit) {
    memset(audit, 0, sizeof(*audit));
    int r = mdi_begin(s, 0);
    if (r) return r;
    sqlite3_stmt *q = NULL;
    r = mdi_prepare(s, "PRAGMA integrity_check", &q);
    if (!r && (sqlite3_step(q) != SQLITE_ROW || strcmp((const char *)sqlite3_column_text(q, 0), "ok"))) r = -EIO;
    sqlite3_finalize(q); q = NULL;
    if (!r) r = mdi_prepare(s, "PRAGMA foreign_key_check", &q);
    if (!r && sqlite3_step(q) != SQLITE_DONE) r = -EIO;
    sqlite3_finalize(q); q = NULL;
    if (!r) r = mdi_prepare(s, "SELECT object,kind,device,inode,parent FROM objects", &q);
    while (!r) {
        struct mdi_node node;
        r = read_node(q, &node);
        if (r == -ENOENT) { r = 0; break; }
        struct stat st;
        if (!r) r = mdi_stat(s, &node, &st);
        if (!r && node.kind == S_IFDIR && st.st_nlink) {
            int rooted = mdi_ancestor(s, MDI_ROOT, node.id);
            if (rooted != 1) r = rooted < 0 ? rooted : -EIO;
        }
        if (!r) { ++audit->objects; audit->detached += !st.st_nlink; }
    }
    sqlite3_finalize(q); q = NULL;
    if (!r) r = mdi_prepare(s, "SELECT n.parent,n.name,n.object,o.parent,o.kind "
        "FROM names n JOIN objects o ON n.object=o.object", &q);
    while (!r) {
        int rc = sqlite3_step(q);
        if (rc != SQLITE_ROW) { r = mdi_sql_error(rc); break; }
        char parent[33], object[33];
        r = read_id(q, 0, parent);
        if (!r) r = read_id(q, 2, object);
        int size = sqlite3_column_bytes(q, 1);
        if (size < 1 || size > NAME_MAX) r = -EIO;
        if (!r) {
            char name[NAME_MAX+1]; memcpy(name, sqlite3_column_blob(q, 1), (size_t)size); name[size] = 0;
            if (memchr(name, 0, (size_t)size) || mdi_name_valid(name)) r = -EIO;
        }
        struct mdi_node node;
        if (!r) r = mdi_node(s, parent, &node);
        if (!r && node.kind != S_IFDIR) r = -EIO;
        if (!r && sqlite3_column_int(q, 4) == S_IFDIR) {
            char owner[33]; r = read_id(q, 3, owner);
            if (!r && (strcmp(owner, parent) || !strcmp(object, MDI_ROOT))) r = -EIO;
        }
        if (!r) ++audit->names;
    }
    sqlite3_finalize(q); q = NULL;
    if (!r) r = mdi_prepare(s, "SELECT n.object FROM names n JOIN objects o ON n.object=o.object "
        "WHERE o.kind=16384 GROUP BY n.object HAVING count(*)>1", &q);
    if (!r && sqlite3_step(q) != SQLITE_DONE) r = -EIO;
    sqlite3_finalize(q);
    int fd = r ? -1 : openat(s->objects, ".", O_DIRECTORY | O_CLOEXEC);
    DIR *dir = fd < 0 ? NULL : fdopendir(fd);
    if (!dir && !r) { r = -errno; if (fd >= 0) close(fd); }
    while (!r) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) { if (errno) r = -errno; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        struct mdi_node node;
        r = mdi_node(s, entry->d_name, &node);
        if (r == -ENOENT) { ++audit->untracked; r = 0; }
    }
    if (dir) closedir(dir);
    return mdi_finish(s, r);
}
#endif
