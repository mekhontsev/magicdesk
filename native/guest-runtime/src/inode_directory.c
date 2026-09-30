#define _GNU_SOURCE
#include "inode_internal.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

_Static_assert(offsetof(struct md_inode_dirent, name) == 19, "Linux getdents64 ABI");
static int directory(struct md_inode_store *s, int fd, struct mdi_node *node) {
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) return -errno;
    if (flags & O_PATH) return -EBADF;
    int r = mdi_fd(s, fd, node);
    return r ? r : node->kind == S_IFDIR ? 0 : -ENOTDIR;
}
static size_t record(void *out, size_t space, const char *name, size_t length,
        uint64_t inode, int64_t next, uint8_t type) {
    size_t size = (offsetof(struct md_inode_dirent, name) + length + 1 + 7) & ~(size_t)7;
    if (space < size) return 0;
    struct md_inode_dirent header = {.inode = inode, .next = next, .size = (uint16_t)size, .type = type};
    memset(out, 0, size);
    memcpy(out, &header, offsetof(struct md_inode_dirent, name));
    memcpy((char *)out + offsetof(struct md_inode_dirent, name), name, length);
    return size;
}
int64_t md_inode_seekdir(struct md_inode_store *s, int fd, int64_t offset, int whence) {
    if (whence != SEEK_SET && whence != SEEK_CUR) return -EINVAL;
    int r = mdi_begin(s, 0); if (r) return r;
    struct mdi_node node; r = directory(s, fd, &node);
    r = mdi_finish(s, r);
    if (r) return r;
    off_t result = lseek(fd, offset, whence);
    return result < 0 ? -errno : result;
}
ssize_t md_inode_getdents(struct md_inode_store *s, int fd, void *out, size_t capacity) {
    if (!out) return -EFAULT;
    if (capacity > INT_MAX) return -EINVAL;
    int r = mdi_begin(s, 0); if (r) return r;
    struct mdi_node node; r = directory(s, fd, &node);
    struct stat st;
    if (!r) r = mdi_stat(s, &node, &st);
    if (!r && !st.st_nlink) r = -ENOENT;
    int64_t offset = 0;
    if (!r && (offset = lseek(fd, 0, SEEK_CUR)) < 0) r = -errno;
    size_t used = 0;
    while (!r && offset < 2) {
        struct mdi_node entry = node;
        if (offset == 1) r = mdi_node(s, node.parent, &entry);
        if (r) break;
        size_t n = record((char *)out + used, capacity-used, offset ? ".." : ".",
            offset ? 2 : 1, entry.inode, offset+1, DT_DIR);
        if (!n) { if (!used) r = -EINVAL; break; }
        used += n; ++offset;
    }
    sqlite3_stmt *q = NULL;
    if (!r && offset >= 2) r = mdi_prepare(s,
        "SELECT n.cookie,n.name,o.inode,o.kind FROM names n JOIN objects o ON o.object=n.object "
        "WHERE n.parent=?1 AND n.cookie>?2 ORDER BY n.cookie", &q);
    if (!r && q) r = mdi_bind_id(q, 1, node.id);
    if (!r && q) r = mdi_sql_error(sqlite3_bind_int64(q, 2, offset-2));
    while (!r && q) {
        int rc = mdi_step(s, q);
        if (rc == SQLITE_DONE) break;
        if (rc != SQLITE_ROW) { r = mdi_sql_failure(rc); break; }
        int length = sqlite3_column_bytes(q, 1);
        const char *name = sqlite3_column_blob(q, 1);
        if (!name || length < 1 || length > NAME_MAX || memchr(name, 0, (size_t)length)
                || memchr(name, '/', (size_t)length)) { r = -EIO; break; }
        int64_t next = sqlite3_column_int64(q, 0) + 2;
        if (next <= offset) { r = -EIO; break; }
        size_t n = record((char *)out + used, capacity-used, name, (size_t)length,
            (uint64_t)sqlite3_column_int64(q, 2), next, (uint8_t)(sqlite3_column_int(q, 3) >> 12));
        if (!n) { if (!used) r = -EINVAL; break; }
        used += n; offset = next;
    }
    sqlite3_finalize(q); r = mdi_finish(s, r);
    if (!r && used && lseek(fd, offset, SEEK_SET) != offset) r = -EIO;
    return r ? r : (ssize_t)used;
}
