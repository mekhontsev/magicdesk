#define _GNU_SOURCE
#include "inode_internal.h"
#include "fs_operation.h"
#include <errno.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/xattr.h>

int mdi_file_capability(struct md_inode_store *s, const struct mdi_node *node,
        const void *value, size_t size) {
    if (value && (!md_file_capability_valid(value, size) || node->kind != S_IFREG)) return -EINVAL;
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, value ? "INSERT OR REPLACE INTO file_capabilities VALUES(?1,?2)"
        : "DELETE FROM file_capabilities WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, node->id);
    if (!r && value) r = mdi_sql_error(sqlite3_bind_blob(q, 2, value, (int)size, SQLITE_STATIC));
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    sqlite3_finalize(q); return r;
}
int md_inode_capability(struct md_inode_store *s, const struct md_fs_request *request,
        void *data, size_t capacity) {
    int write = request->operation != MD_FS_GETCAP;
    if (request->flags & ~(XATTR_CREATE | XATTR_REPLACE)) return -EINVAL;
    if (request->operation != MD_FS_SETCAP && request->flags) return -EINVAL;
    int r = mdi_begin(s, write);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, request->directory[0], &node);
    if (!r && write && s->identity && !md_identity_capable(s->identity, CAP_SETFCAP)) r = -EPERM;
    sqlite3_stmt *q = NULL;
    if (!r) r = mdi_prepare(s, "SELECT value FROM file_capabilities WHERE object=?1", &q);
    if (!r) r = mdi_bind_id(q, 1, node.id);
    int present = 0, size = 0;
    if (!r) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_ROW) {
            present = 1; size = sqlite3_column_bytes(q, 0);
            if (!md_file_capability_valid(sqlite3_column_blob(q,0), size)) r = -EIO;
            if (!r && !write) {
                if (!data || capacity < (size_t)size) r = -ERANGE;
                else memcpy(data, sqlite3_column_blob(q,0), size);
            }
        } else if (rc != SQLITE_DONE) r = mdi_sql_failure(rc);
    }
    sqlite3_finalize(q);
    if (!r && !write && !present) r = -ENODATA;
    if (!r && request->operation == MD_FS_SETCAP) {
        if (present && (request->flags & XATTR_CREATE)) r = -EEXIST;
        else if (!present && (request->flags & XATTR_REPLACE)) r = -ENODATA;
        else r = mdi_file_capability(s, &node, request->attributes.capability, request->capacity);
    }
    if (!r && request->operation == MD_FS_REMOVECAP)
        r = present ? mdi_file_capability(s, &node, NULL, 0) : -ENODATA;
    if (!r && write) r = mdi_event(s, NULL, &node, NULL, IN_ATTRIB, NULL);
    r = mdi_commit(s, r);
    return r ? r : write ? 0 : size;
}
