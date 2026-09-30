#define _GNU_SOURCE
#include "event_wait.h"
#include "fs_service.h"
#include "image_catalogue.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#ifdef MD_FS_TESTING
static void (*observer)(enum md_fs_checkpoint, const struct md_fs_packet *, void *);
static void *observation;
void md_fs_observe(void (*fn)(enum md_fs_checkpoint, const struct md_fs_packet *, void *), void *context) {
    observer = fn; observation = context;
}
#define OBSERVE(stage, packet) do { if (observer) observer(stage, packet, observation); } while (0)
#else
#define OBSERVE(stage, packet) ((void)0)
#endif

int md_fs_listen(const char *name) {
    struct sockaddr_un address;
    long length = md_fs_address(name, &address); if (length < 0) return (int)length;
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -errno;
    if (bind(fd, (struct sockaddr *)&address, (socklen_t)length) || listen(fd, 32)) {
        int r = -errno; close(fd); return r;
    }
    return fd;
}
static int valid(const struct md_fs_packet *q, size_t size, const struct md_fs_rights *rights) {
    if (size < offsetof(struct md_fs_packet, data) || q->magic != MD_FS_MAGIC
            || q->version != MD_FS_VERSION || q->descriptors > 3 || q->reserved
            || !q->length[0] || !q->length[1] || q->length[0] > PATH_MAX || q->length[1] > PATH_MAX
            || size != offsetof(struct md_fs_packet, data) + q->length[0] + q->length[1]
            || rights->count != (q->descriptors & 1) + ((q->descriptors >> 1) & 1)) return -EPROTO;
    const char *a = q->data, *b = a + q->length[0];
    if (a[q->length[0]-1] || b[q->length[1]-1]
            || memchr(a, 0, q->length[0]-1) || memchr(b, 0, q->length[1]-1)) return -EPROTO;
    if (q->operation < MD_FS_CREATE || q->operation > MD_FS_OPEN_OBJECT) return -ENOTSUP;
    if (q->resolve && q->operation != MD_FS_OPEN) return -EINVAL;
    if ((q->capacity && q->operation != MD_FS_GETDENTS) || q->capacity > PATH_MAX
            || (q->offset && q->operation != MD_FS_SEEKDIR)) return -EINVAL;
    if (q->operation == MD_FS_TEMPORARY)
        return q->descriptors || *a || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OBJECT_ID)
        return q->descriptors != 1 || *a || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OPEN_OBJECT)
        return q->descriptors || !*a || *b || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_GETDENTS || q->operation == MD_FS_SEEKDIR) {
        if (q->descriptors != 1 || *a || *b || q->mode) return -EINVAL;
        return q->operation == MD_FS_GETDENTS ? (q->flags ? -EINVAL : 0)
            : (q->flags == SEEK_SET || q->flags == SEEK_CUR ? 0 : -EINVAL);
    }
    if (q->operation == MD_FS_SOCKET_BIND)
        return !(q->descriptors & 2) || !*a || *b || q->flags || (q->mode & ~0777) ? -EINVAL : 0;
    if (q->operation == MD_FS_SOCKET_ADDRESS || q->operation == MD_FS_SOCKET_NAME)
        return (q->descriptors & 2) || !*a || *b || q->flags || q->mode
            || (q->operation == MD_FS_SOCKET_NAME && q->descriptors) ? -EINVAL : 0;
    int binary = q->operation == MD_FS_LINK || q->operation == MD_FS_RENAME;
    if ((!binary && (q->descriptors & 2))
            || (!binary && q->operation != MD_FS_SYMLINK && *b)
            || (q->mode && q->operation != MD_FS_CREATE && q->operation != MD_FS_MKDIR && q->operation != MD_FS_OPEN)
            || (q->flags && q->operation != MD_FS_OPEN && q->operation != MD_FS_LINK
                && q->operation != MD_FS_RENAME && q->operation != MD_FS_UNLINK && q->operation != MD_FS_STAT)
            || ((q->operation == MD_FS_FSTAT || q->operation == MD_FS_PATH) && *a)
            || (q->operation == MD_FS_FSTAT && !(q->descriptors & 1))) return -EINVAL;
    return 0;
}
static void info(const struct stat *s, struct md_fs_info *out) {
    *out = (struct md_fs_info){.device = s->st_dev, .inode = s->st_ino, .links = s->st_nlink,
        .rdev = s->st_rdev, .size = s->st_size, .blocks = s->st_blocks, .mode = s->st_mode,
        .uid = s->st_uid, .gid = s->st_gid, .block_size = (uint32_t)s->st_blksize,
        .access_seconds = s->st_atim.tv_sec, .modify_seconds = s->st_mtim.tv_sec,
        .change_seconds = s->st_ctim.tv_sec, .access_nanos = (uint32_t)s->st_atim.tv_nsec,
        .modify_nanos = (uint32_t)s->st_mtim.tv_nsec, .change_nanos = (uint32_t)s->st_ctim.tv_nsec};
}
static int dispatch(struct md_inode_store *s, struct md_image_catalogue *images, const struct md_fs_packet *q,
        const struct md_fs_rights *input, struct md_fs_reply *out, struct md_fs_rights *output) {
    int fd[2] = {MD_INODE_ROOT, MD_INODE_ROOT}; unsigned index = 0;
    for (unsigned i = 0; i < 2; ++i) if (q->descriptors & (1U << i)) fd[i] = input->fd[index++];
    const char *a = q->data, *b = a + q->length[0];
    int r; struct stat st;
    switch (q->operation) {
    case MD_FS_OBJECT_ID:
        r = md_catalogue_object_id(images, s, fd[0], out->data);
        if (!r) out->size = 33;
        return r;
    case MD_FS_OPEN_OBJECT: r = md_catalogue_open_object(images, s, a, (int)q->flags); break;
    case MD_FS_SOCKET_BIND: return md_inode_socket_bind(s, fd[0], a, q->mode, fd[1]);
    case MD_FS_SOCKET_ADDRESS: case MD_FS_SOCKET_NAME:
        r = q->operation == MD_FS_SOCKET_ADDRESS
            ? md_inode_socket_address(s, fd[0], a, out->data, sizeof(out->data))
            : md_inode_socket_name(s, a, out->data, sizeof(out->data));
        if (!r) out->size = (uint32_t)strlen(out->data) + 1;
        return r;
    case MD_FS_CREATE: r = md_inode_create(s, fd[0], a, q->mode); break;
    case MD_FS_OPEN: r = md_catalogue_open(images, s, fd[0], a, (int)q->flags, q->mode, q->resolve); break;
    case MD_FS_MKDIR: return md_inode_mkdir(s, fd[0], a, q->mode);
    case MD_FS_SYMLINK: return md_inode_symlink(s, b, fd[0], a);
    case MD_FS_LINK: return md_inode_link(s, fd[0], a, fd[1], b, (int)q->flags);
    case MD_FS_UNLINK: return md_inode_unlink(s, fd[0], a, (int)q->flags);
    case MD_FS_RENAME: return md_inode_rename(s, fd[0], a, fd[1], b, q->flags);
    case MD_FS_STAT: case MD_FS_FSTAT:
        r = q->operation == MD_FS_STAT ? md_catalogue_stat(images, s, fd[0], a, (int)q->flags, &st)
            : md_catalogue_fstat(images, s, fd[0], &st);
        if (!r) info(&st, &out->info);
        return r;
    case MD_FS_PATH:
        r = md_catalogue_path(images, s, fd[0], out->data, sizeof(out->data));
        if (!r) out->size = (uint32_t)strlen(out->data)+1;
        return r;
    case MD_FS_REALPATH:
        r = md_inode_realpath(s, fd[0], a, out->data, sizeof(out->data));
        if (!r) out->size = (uint32_t)strlen(out->data)+1;
        return r;
    case MD_FS_TEMPORARY:
        r = md_inode_temporary(s);
        if (r < 0) return r;
        output->fd[output->count++] = r;
        return 0;
    case MD_FS_READLINK:
        r = (int)md_inode_readlink(s, fd[0], a, out->data, sizeof(out->data));
        if (r >= 0) { out->size = (uint32_t)r; return 0; }
        return r;
    case MD_FS_GETDENTS:
        r = (int)md_inode_getdents(s, fd[0], out->data, q->capacity);
        if (r >= 0) { out->size = (uint32_t)r; return 0; }
        return r;
    case MD_FS_SEEKDIR: {
        int64_t position = md_inode_seekdir(s, fd[0], q->offset, (int)q->flags);
        if (position < 0) return (int)position;
        out->position = position; return 0;
    }
    default: return -ENOTSUP;
    }
    if (r < 0) return r;
    output->fd[output->count++] = r;
    return 0;
}
enum peer_phase { REQUEST, REPLY, RELEASE };
struct peer {
    enum peer_phase phase;
    int64_t deadline;
    struct md_fs_reply reply;
    struct md_fs_rights output;
};
static int service_request(struct md_inode_store *s, struct md_image_catalogue *images, int socket,
        struct peer *peer, struct md_fs_statistics *statistics) {
    struct md_fs_packet packet;
    struct md_fs_rights input = {0};
    long n = md_fs_receive(socket, &packet, sizeof(packet), &input);
    if (n == -EAGAIN || n == -EINTR) return 0;
    if (!n) { md_fs_close_rights(&input); return 1; }
    struct md_fs_reply *reply = &peer->reply;
    *reply = (struct md_fs_reply){.magic = MD_FS_MAGIC, .version = MD_FS_VERSION};
    if (n > 0) {
        reply->error = valid(&packet, (size_t)n, &input);
        if (!reply->error) {
            OBSERVE(MD_FS_BEFORE_DISPATCH, &packet);
            struct md_cost *cost = statistics ? &statistics->operation[packet.operation] : NULL;
            int64_t begin = md_cost_begin(cost);
            reply->error = dispatch(s, images, &packet, &input, reply, &peer->output);
            md_cost_end(cost, begin);
            OBSERVE(MD_FS_AFTER_DISPATCH, &packet);
        }
    } else reply->error = -EPROTO;
    md_fs_close_rights(&input);
    reply->descriptors = peer->output.count;
    peer->phase = REPLY;
    return 0;
}
static int service_peer(struct md_inode_store *s, struct md_image_catalogue *images,
        struct pollfd *fd, struct peer *peer, struct md_fs_statistics *statistics) {
    if (peer->phase == RELEASE) return 1;
    if (peer->phase == REQUEST && service_request(s, images, fd->fd, peer, statistics)) return 1;
    if (peer->phase != REPLY) return 0;
    size_t size = offsetof(struct md_fs_reply, data) + peer->reply.size;
    long sent = md_fs_send(fd->fd, &peer->reply, size, &peer->output);
    if (sent == -EAGAIN || sent == -EINTR) { fd->events = POLLOUT; return 0; }
    if (sent != (long)size) return 1;
    md_fs_close_rights(&peer->output);
    /* Client close acknowledges receipt. Do not race a nonblocking SEQPACKET
     * receive with server shutdown; never redispatch a completed mutation. */
    peer->phase = RELEASE;
    fd->events = POLLIN;
    OBSERVE(MD_FS_REPLY_SENT, NULL);
    return 0;
}
static void close_peer(struct pollfd *fd, struct peer *peer) {
    md_fs_close_rights(&peer->output);
    close(fd->fd); fd->fd = -1;
    OBSERVE(MD_FS_CONNECTION_CLOSED, NULL);
}
int md_fs_serve(struct md_inode_store *s, struct md_image_catalogue *images, int listener, int stop_fd,
        unsigned timeout_ms, struct md_fs_statistics *statistics) {
    if (!s || listener < 0 || stop_fd < 0 || !timeout_ms || timeout_ms > 60000) return -EINVAL;
    enum { SLOTS = 32, BASE = 2 };
    struct pollfd fds[BASE+SLOTS] = {{.fd = listener, .events = POLLIN}, {.fd = stop_fd, .events = POLLIN}};
    /* Fixed capacity, allocated once per service, never per request. */
    struct peer *peers = calloc(SLOTS, sizeof(*peers));
    if (!peers) return -ENOMEM;
    for (unsigned i = BASE; i < BASE+SLOTS; ++i) fds[i].fd = -1;
    int error = 0;
    for (;;) {
        long now = md_event_now(); if (now < 0) { error = (int)now; break; }
        int64_t nearest = INT64_MAX;
        for (unsigned i = 0; i < SLOTS; ++i) if (fds[BASE+i].fd >= 0) {
            if (peers[i].deadline <= now) close_peer(&fds[BASE+i], &peers[i]);
            else if (peers[i].deadline < nearest) nearest = peers[i].deadline;
        }
        struct timespec timeout = {0};
        if (nearest != INT64_MAX) {
            int64_t left = nearest-now;
            timeout.tv_sec = left / 1000000000LL; timeout.tv_nsec = left % 1000000000LL;
        }
        /* EVENT_WAIT: request/reply/client-close/stop readiness; the absolute
         * peer deadline drops only that connection, without replaying work. */
        int r = ppoll(fds, BASE+SLOTS, nearest == INT64_MAX ? NULL : &timeout, NULL);
        if (r < 0) { if (errno == EINTR) continue; error = -errno; break; }
        if (fds[1].revents) break;
        if (fds[0].revents & (POLLNVAL | POLLERR | POLLHUP)) { error = -EIO; break; }
        for (unsigned i = BASE; i < BASE+SLOTS; ++i) if (fds[i].fd >= 0 && fds[i].revents) {
            if (service_peer(s, images, &fds[i], &peers[i-BASE], statistics)) close_peer(&fds[i], &peers[i-BASE]);
        }
        if (fds[0].revents & POLLIN) {
            /* Bound each accept batch so a connecting peer cannot starve shutdown or requests. */
            for (unsigned n = 0; n < SLOTS; ++n) {
                int fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if (fd < 0) { if (errno == EAGAIN || errno == EINTR) break; error = -errno; break; }
                if (md_fs_peer(fd)) { close(fd); continue; }
                unsigned slot = 0; while (slot < SLOTS && fds[BASE+slot].fd >= 0) ++slot;
                if (slot == SLOTS) { close(fd); continue; }
                now = md_event_now();
                if (now < 0) { close(fd); error = (int)now; break; }
                fds[BASE+slot] = (struct pollfd){.fd = fd, .events = POLLIN};
                peers[slot].phase = REQUEST;
                peers[slot].deadline = now + (int64_t)timeout_ms * 1000000;
            }
            if (error) break;
        }
    }
    for (unsigned i = BASE; i < BASE+SLOTS; ++i)
        if (fds[i].fd >= 0) close_peer(&fds[i], &peers[i-BASE]);
    free(peers);
    return error;
}
