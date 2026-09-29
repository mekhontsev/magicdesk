#define _GNU_SOURCE
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

int mdi_walk(struct md_inode_store *s, int dirfd, const char *path,
        enum mdi_follow follow, int missing, struct mdi_location *out) {
    if (!path) return -EFAULT;
    if (!*path) return -ENOENT;
    size_t length = strnlen(path, PATH_MAX);
    if (length == PATH_MAX) return -ENAMETOOLONG;
    char todo[PATH_MAX]; memcpy(todo, path, length+1);
    struct mdi_node current;
    int r = path[0] == '/' || dirfd == MD_INODE_ROOT
        ? mdi_node(s, MDI_ROOT, &current) : mdi_fd(s, dirfd, &current);
    if (r) return r;
    if (current.kind != S_IFDIR) return -ENOTDIR;
    unsigned links = 0;
    memset(out, 0, sizeof(*out));
    for (;;) {
        char *start = todo; while (*start == '/') ++start;
        if (!*start) {
            out->node = out->parent = current;
            out->exists = out->special = 1;
            out->trailing = 1;
            return 0;
        }
        if ((r = mdi_access(s, &current, X_OK))) return r;
        char *end = start; while (*end && *end != '/') ++end;
        size_t size = (size_t)(end-start);
        if (size > NAME_MAX) return -ENAMETOOLONG;
        char name[NAME_MAX+1]; memcpy(name, start, size); name[size] = 0;
        int slash = *end == '/';
        memmove(todo, end, strlen(end)+1);
        char *rest = todo; while (*rest == '/') ++rest;
        int last = !*rest;
        struct mdi_node next;
        int special = !strcmp(name, ".") || !strcmp(name, "..");
        if (!strcmp(name, ".")) { next = current; r = 0; }
        else if (!strcmp(name, "..")) r = mdi_node(s, current.parent, &next);
        else r = mdi_lookup(s, current.id, name, &next);
        if (r == -ENOENT && last && missing && !special) {
            out->parent = current; strcpy(out->name, name);
            out->trailing = slash;
            return 0;
        }
        if (r) return r;
        if (next.kind == S_IFLNK && (!last || follow == MDI_FOLLOW
                || (follow == MDI_NOFOLLOW && slash))) {
            if (++links > 40) return -ELOOP;
            char target[PATH_MAX];
            ssize_t n = readlinkat(s->objects, next.id, target, sizeof(target));
            if (n < 0) return -errno;
            size_t rest_length = strlen(todo);
            if (!n) return -ENOENT;
            if ((size_t)n + rest_length >= sizeof(target)) return -ENAMETOOLONG;
            memcpy(target+n, todo, rest_length+1);
            strcpy(todo, target);
            if (target[0] == '/' && (r = mdi_node(s, MDI_ROOT, &current))) return r;
            continue;
        }
        if ((!last || (slash && follow != MDI_ENTRY)) && next.kind != S_IFDIR) return -ENOTDIR;
        if (last) {
            out->parent = current; out->node = next; strcpy(out->name, name);
            out->exists = 1; out->special = special; out->trailing = slash;
            return 0;
        }
        current = next;
    }
}

int mdi_ancestor(struct md_inode_store *s, const char *ancestor, const char *child) {
    sqlite3_stmt *q = NULL;
    /* UNION (not UNION ALL) terminates even for a corrupt parent cycle. */
    int r = mdi_prepare(s, "WITH RECURSIVE chain(object,parent) AS ("
        "SELECT object,parent FROM objects WHERE object=?1 UNION "
        "SELECT o.object,o.parent FROM objects o JOIN chain c ON o.object=c.parent) "
        "SELECT EXISTS(SELECT 1 FROM chain WHERE object=?2)", &q);
    if (!r) r = mdi_bind_id(q, 1, child);
    if (!r) r = mdi_bind_id(q, 2, ancestor);
    if (!r) {
        int rc = sqlite3_step(q);
        r = rc == SQLITE_ROW ? sqlite3_column_int(q, 0) : mdi_sql_failure(rc);
    }
    sqlite3_finalize(q);
    return r;
}

int md_inode_path(struct md_inode_store *s, int dirfd, char *out, size_t size) {
    if (!out) return -EFAULT;
    if (!size) return -ERANGE;
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_node node;
    r = dirfd == MD_INODE_ROOT ? mdi_node(s, MDI_ROOT, &node) : mdi_fd(s, dirfd, &node);
    if (!r && node.kind != S_IFDIR) r = -ENOTDIR;
    char path[PATH_MAX] = "";
    while (!r && strcmp(node.id, MDI_ROOT)) {
        sqlite3_stmt *q = NULL;
        r = mdi_prepare(s, "SELECT name FROM names WHERE parent=?1 AND object=?2", &q);
        if (!r) r = mdi_bind_id(q, 1, node.parent);
        if (!r) r = mdi_bind_id(q, 2, node.id);
        if (!r) {
            int rc = sqlite3_step(q);
            if (rc != SQLITE_ROW) r = rc == SQLITE_DONE ? -ENOENT : mdi_sql_error(rc);
            else {
                int n = sqlite3_column_bytes(q, 0);
                size_t old = strlen(path);
                if (n < 1 || n > NAME_MAX) r = -EIO;
                else if (old + (size_t)n + 1 >= sizeof(path)) r = -ENAMETOOLONG;
                else {
                    memmove(path+n+1, path, old+1); path[0] = '/';
                    memcpy(path+1, sqlite3_column_blob(q, 0), (size_t)n);
                    if (memchr(path+1, 0, (size_t)n)) r = -EIO;
                }
            }
        }
        sqlite3_finalize(q);
        if (!r) r = mdi_node(s, node.parent, &node);
    }
    if (!r) {
        if (!*path) strcpy(path, "/");
        if (strlen(path) >= size) r = -ERANGE;
        else strcpy(out, path);
    }
    return mdi_finish(s, r);
}
