#define _GNU_SOURCE
#include "inode_internal.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define SOCKET_PREFIX "md-guest-inode-"
static int endpoint(const struct mdi_node *node, char *out, size_t size) {
    if (size < sizeof(SOCKET_PREFIX) + 32) return -ENAMETOOLONG;
    strcpy(out, SOCKET_PREFIX);
    strcat(out, node->id);
    return 0;
}
int md_inode_socket_bind(struct md_inode_store *s, int dirfd, const char *path, mode_t mode, int socket) {
    if (mode & ~0777) return -EINVAL;
    if (!path || !*path || strlen(path) > sizeof(((struct sockaddr_un *)0)->sun_path))
        return -ENAMETOOLONG;
    int r = mdi_begin(s, 1);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_ENTRY, 1, &loc);
    if (!r && loc.exists) r = -EADDRINUSE;
    if (!r && loc.trailing) r = -ENOENT;
    if (!r) r = mdi_parent_writable(s, &loc.parent);
    struct mdi_node node;
    int fd = -1;
    if (!r) r = mdi_allocate(s, S_IFSOCK, mode, O_RDWR, NULL, NULL, &node, &fd);
    if (fd >= 0) close(fd);
    sqlite3_stmt *query = NULL;
    if (!r) r = mdi_prepare(s, "INSERT INTO sockets(object,address) VALUES(?1,?2)", &query);
    if (!r) r = mdi_bind_id(query, 1, node.id);
    if (!r) r = mdi_sql_error(sqlite3_bind_text(query, 2, path, -1, SQLITE_TRANSIENT));
    if (!r) r = mdi_sql_error(mdi_step(s, query));
    sqlite3_finalize(query);
    if (!r) r = mdi_add_name(s, loc.parent.id, loc.name, node.id);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (!r) r = endpoint(&node, address.sun_path + 1, sizeof(address.sun_path) - 1);
    if (!r && bind(socket, (struct sockaddr *)&address,
            (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(address.sun_path + 1)))) r = -errno;
    // Binding and committing cannot be atomically undone after a lost reply.
    // A failed/unconfirmed bind is never replayed on the same socket.
    return mdi_commit(s, r);
}
int md_inode_socket_address(struct md_inode_store *s, int dirfd, const char *path, char *out, size_t size) {
    int r = mdi_begin(s, 0);
    if (r) return r;
    struct mdi_location loc;
    r = mdi_walk(s, dirfd, path, MDI_FOLLOW, 0, &loc);
    if (!r && loc.node.kind != S_IFSOCK) r = -ECONNREFUSED;
    struct stat st;
    if (!r) r = mdi_stat(s, &loc.node, &st);
    if (!r && faccessat(s->objects, loc.node.id, W_OK, AT_EACCESS)) r = -errno;
    if (!r) r = endpoint(&loc.node, out, size);
    return mdi_finish(s, r);
}
int md_inode_socket_name(struct md_inode_store *s, const char *address, char *out, size_t size) {
    size_t prefix = sizeof(SOCKET_PREFIX) - 1;
    if (strncmp(address, SOCKET_PREFIX, prefix) || strlen(address + prefix) != 32
            || strspn(address + prefix, "0123456789abcdef") != 32) return -ENOENT;
    int r = mdi_begin(s, 0);
    if (r) return r;
    sqlite3_stmt *query = NULL;
    r = mdi_prepare(s, "SELECT address FROM sockets WHERE object=?1", &query);
    if (!r) r = mdi_bind_id(query, 1, address + prefix);
    if (!r) {
        int rc = mdi_step(s, query);
        if (rc != SQLITE_ROW) r = rc == SQLITE_DONE ? -ENOENT : mdi_sql_failure(rc);
        else {
            const char *name = (const char *)sqlite3_column_text(query, 0);
            int bytes = sqlite3_column_bytes(query, 0);
            if (!name || bytes <= 0 || (size_t)bytes >= size || strlen(name) != (size_t)bytes) r = -EIO;
            else memcpy(out, name, (size_t)bytes + 1);
        }
    }
    sqlite3_finalize(query);
    return mdi_finish(s, r);
}
