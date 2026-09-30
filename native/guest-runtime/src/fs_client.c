#define _GNU_SOURCE
#include "fs_wire.h"
#include "event_wait.h"
#include "inode_store.h"
#include "raw.h"
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>

static int valid_entries(const char *data, size_t size) {
    int64_t previous = 0;
    while (size) {
        struct md_inode_dirent entry;
        size_t header = offsetof(struct md_inode_dirent, name);
        if (size < header) return 0;
        memcpy(&entry, data, header);
        if (entry.size <= header || entry.size > size || entry.size % 8
                || entry.next <= previous) return 0;
        size_t n = 0;
        while (n < entry.size-header && data[header+n]) {
            if (data[header+n] == '/' || ++n > NAME_MAX) return 0;
        }
        if (!n || n == entry.size-header) return 0;
        previous = entry.next; data += entry.size; size -= entry.size;
    }
    return 1;
}
static long receive_reply(int socket, int64_t deadline, const struct md_fs_request *request, struct md_fs_result *out,
                          struct md_fs_reply *reply) {
    struct md_fs_rights rights = {0};
    long r;
    for (;;) {
        r = md_fs_receive(socket, reply, sizeof(*reply), &rights);
        if (r != -EAGAIN && r != -EINTR) break;
        r = md_event_wait_fd(socket, POLLIN, deadline); if (r < 0) return r;
    }
    if (r < 0) return r;
    uint32_t operation = request->operation;
    int opens = operation == MD_FS_OPEN || operation == MD_FS_CREATE || operation == MD_FS_TEMPORARY
        || operation == MD_FS_OPEN_OBJECT;
    if (r < (long)offsetof(struct md_fs_reply, data)
            || reply->magic != MD_FS_MAGIC || reply->version != MD_FS_VERSION
            || reply->error > 0 || reply->error < -4095 || reply->reserved
            || reply->size > sizeof(reply->data) || (size_t)r != offsetof(struct md_fs_reply, data) + reply->size
            || reply->descriptors != rights.count || rights.count != (unsigned)(opens && !reply->error)
            || (reply->error && reply->size)
            || (reply->size && operation != MD_FS_READLINK && operation != MD_FS_PATH && operation != MD_FS_GETDENTS
                && operation != MD_FS_SOCKET_ADDRESS && operation != MD_FS_SOCKET_NAME && operation != MD_FS_REALPATH
                && operation != MD_FS_OBJECT_ID)
            || (!reply->error && operation == MD_FS_OBJECT_ID && (reply->size != 33 || reply->data[32]))
            || (reply->position && (operation != MD_FS_SEEKDIR || reply->error)) || reply->position < 0
            || (operation == MD_FS_GETDENTS && (reply->size > request->capacity || !valid_entries(reply->data, reply->size)))
            || (!reply->error && (operation == MD_FS_PATH || operation == MD_FS_SOCKET_ADDRESS || operation == MD_FS_SOCKET_NAME || operation == MD_FS_REALPATH)
                && (!reply->size || reply->data[reply->size-1]))) {
        md_fs_close_rights(&rights); return -EPROTO;
    }
    out->delivery = MD_FS_REPLIED; out->error = reply->error;
    out->fd = rights.count ? rights.fd[0] : -1;
    out->info = reply->info; out->size = reply->size;
    out->position = reply->position;
    memcpy(out->data, reply->data, reply->size);
    return 0;
}
long md_fs_call(const char *name, unsigned timeout_ms,
        const struct md_fs_request *request, struct md_fs_result *out) {
    if (!out) return -EFAULT;
    memset(out, 0, sizeof(*out)); out->fd = -1;
    if (!request) return -EFAULT;
    if (!timeout_ms || timeout_ms > 60000) return -EINVAL;
    /* The request is no longer needed after send: reuse its storage for the reply. */
    union { struct md_fs_packet packet; struct md_fs_reply reply; } wire = {.packet = {.magic = MD_FS_MAGIC, .version = MD_FS_VERSION,
        .operation = request->operation, .flags = request->flags, .mode = request->mode,
        .capacity = request->capacity, .offset = request->offset, .resolve = request->resolve}};
    struct md_fs_rights rights = {0};
    size_t length = 0;
    for (unsigned i = 0; i < 2; ++i) {
        const char *path = request->path[i] ? request->path[i] : "";
        size_t n = 0;
        while (path[n]) { if (n == PATH_MAX-1) return -ENAMETOOLONG; ++n; }
        memcpy(wire.packet.data + length, path, n+1); wire.packet.length[i] = n+1; length += n+1;
        if (request->directory[i] < -1) return -EBADF;
        if (request->directory[i] >= 0) {
            wire.packet.descriptors |= 1U << i;
            rights.fd[rights.count++] = request->directory[i];
        }
    }
    struct sockaddr_un address;
    long size = md_fs_address(name, &address); if (size < 0) return size;
    long now = md_event_now(); if (now < 0) return now;
    int64_t deadline = now + (int64_t)timeout_ms * 1000000;
    long socket = RAW3(socket, AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (socket < 0) return socket;
    long r = RAW3(connect, socket, &address, size);
    if (r == -EINPROGRESS) {
        r = md_event_wait_fd((int)socket, POLLOUT, deadline);
        if (!r) {
            int error = 0; unsigned bytes = sizeof(error);
            r = RAW5(getsockopt, socket, SOL_SOCKET, SO_ERROR, &error, &bytes);
            if (!r && error) r = -error;
        }
    }
    if (!r) r = md_fs_peer((int)socket);
    length += offsetof(struct md_fs_packet, data);
    while (!r) {
        r = md_fs_send((int)socket, &wire.packet, length, &rights);
        if (r == -EAGAIN || r == -EINTR) { r = md_event_wait_fd((int)socket, POLLOUT, deadline); continue; }
        if (r >= 0) {
            out->delivery = MD_FS_UNCONFIRMED;
            r = (size_t)r == length ? receive_reply((int)socket, deadline, request, out, &wire.reply) : -EIO;
        }
        break;
    }
    RAW1(close, socket);
    return r;
}
