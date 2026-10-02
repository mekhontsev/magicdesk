#define _GNU_SOURCE
#include "event_wait.h"
#include "fs_service.h"
#include "fs_engine.h"
#include "image_catalogue.h"
#include "inode_watch.h"
#include "ipc_credentials.h"
#include "raw.h"
#include "posix_acl.h"
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
            || q->version != MD_FS_VERSION || q->descriptors > 3 || q->reserved || q->padding || q->actor <= 0
            || !q->length[0] || !q->length[1] || q->length[0] > PATH_MAX || q->length[1] > PATH_MAX
            || size != offsetof(struct md_fs_packet, data) + q->length[0] + q->length[1]
            || rights->count != (q->descriptors & 1) + ((q->descriptors >> 1) & 1)) return -EPROTO;
    const char *a = q->data, *b = a + q->length[0];
    if (a[q->length[0]-1] || b[q->length[1]-1]
            || memchr(a, 0, q->length[0]-1) || memchr(b, 0, q->length[1]-1)) return -EPROTO;
    if (q->operation < MD_FS_CREATE || q->operation > MD_FS_LAST) return -ENOTSUP;
    if (q->attributes.creation_mask & ~0777U) return -EINVAL;
    if (q->operation==MD_FS_MOUNT_TABLE)
        return q->descriptors || *a || *b || q->flags>1 || q->mode || q->capacity || q->offset || q->resolve ? -EINVAL : 0;
    if (q->operation==MD_FS_NATIVE_MOUNT)
        return q->descriptors || *a || *b || q->flags || q->mode || q->capacity || q->offset<=0 || q->resolve ? -EINVAL : 0;
    if (q->operation == MD_FS_XATTR_OPEN)
        return q->descriptors != 1 || q->length[0] > 256 || *b || q->flags
            || (q->mode != F_OK && q->mode != R_OK && q->mode != W_OK)
            || (q->mode == F_OK ? *a != 0 : *a == 0) || q->capacity || q->offset || q->resolve ? -EINVAL : 0;
    if (q->operation == MD_FS_LISTATTR)
        return q->descriptors!=1 || *a || *b || q->mode || q->flags || q->capacity || q->offset ? -EINVAL : 0;
    if (q->operation>=MD_FS_GETACL && q->operation<=MD_FS_REMOVEACL) {
        int set=q->operation==MD_FS_SETACL;
        return q->descriptors!=(set ? 3U : 1U) || *a || *b || q->offset
            || (q->mode!=MD_ACL_ACCESS && q->mode!=MD_ACL_DEFAULT)
            || (set ? (q->capacity && q->capacity<4) || q->capacity>MD_ACL_MAX || (q->flags&~3U)
                : q->capacity || q->flags) ? -EINVAL : 0;
    }
    if (q->resolve && q->operation != MD_FS_OPEN) return -EINVAL;
    if (q->operation == MD_FS_IPC)
        return (q->descriptors != 1 && !((q->flags == MD_IPC_PAIR || q->flags == MD_IPC_MESSAGE_READ) && q->descriptors == 3))
            || *b || q->mode || q->capacity || q->flags < MD_IPC_LISTEN_BEGIN || q->flags > MD_IPC_MESSAGE_READ ? -EINVAL : 0;
    if (q->operation == MD_FS_GETCAP || q->operation == MD_FS_SETCAP || q->operation == MD_FS_REMOVECAP)
        return q->descriptors != 1 || *a || *b || q->mode || q->offset
            || (q->operation != MD_FS_SETCAP && (q->capacity || q->flags))
            || q->capacity > MD_FILE_CAPABILITY_MAX ? -EINVAL : 0;
    if ((q->capacity && q->operation != MD_FS_GETDENTS && q->operation != MD_FS_WATCH_READ) || q->capacity > PATH_MAX
            || (q->offset && q->operation != MD_FS_SEEKDIR)) return -EINVAL;
    if (q->operation == MD_FS_REOPEN)
        return q->descriptors != 1 || *a || *b || q->mode > 1 ? -EINVAL : 0;
    if (q->operation == MD_FS_ACCESS)
        return (q->descriptors & 2) || (!*a && q->descriptors != 1) || *b ? -EINVAL : 0;
    if (q->operation >= MD_FS_CHMOD && q->operation <= MD_FS_UTIMENS)
        return q->descriptors != 1 || *a || *b ? -EINVAL : 0;
    if (q->operation >= MD_FS_WATCH_CREATE && q->operation <= MD_FS_WATCH_READ) {
        unsigned descriptors = q->operation == MD_FS_WATCH_CREATE ? 0 : q->operation == MD_FS_WATCH_ADD ? 3 : 1;
        if (q->descriptors != descriptors || *a || *b || q->mode) return -EINVAL;
        if (q->flags && q->operation != MD_FS_WATCH_CREATE && q->operation != MD_FS_WATCH_ADD
                && q->operation != MD_FS_WATCH_REMOVE) return -EINVAL;
        return 0;
    }
    if (q->operation == MD_FS_TEMPORARY)
        return q->descriptors || *a || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OBJECT_ID)
        return q->descriptors != 1 || *a || *b || q->flags || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OPEN_OBJECT)
        return q->descriptors || !*a || *b || q->mode ? -EINVAL : 0;
    if (q->operation == MD_FS_OPEN_IMAGE)
        return (q->descriptors & 2) || !*a || *b || q->mode || (q->flags & ~O_NOFOLLOW) ? -EINVAL : 0;
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
            || (q->mode && q->operation != MD_FS_CREATE && q->operation != MD_FS_MKDIR && q->operation != MD_FS_MKFIFO && q->operation != MD_FS_OPEN
                && !((q->operation==MD_FS_STAT || q->operation==MD_FS_FSTAT) && q->mode==MD_FS_STAT_MOUNT))
            || (q->flags && q->operation != MD_FS_OPEN && q->operation != MD_FS_LINK
                && q->operation != MD_FS_RENAME && q->operation != MD_FS_UNLINK && q->operation != MD_FS_STAT)
            || ((q->operation == MD_FS_FSTAT || q->operation == MD_FS_PATH) && *a)
            || (q->operation == MD_FS_FSTAT && !(q->descriptors & 1))) return -EINVAL;
    return 0;
}
static int dispatch(struct md_filesystem *fs, pid_t peer, const struct md_fs_packet *q,
        const struct md_fs_rights *input, struct md_fs_reply *out, struct md_fs_rights *output) {
    struct md_fs_request request = {.actor = q->actor, .peer = peer, .operation = q->operation, .flags = q->flags, .mode = q->mode,
        .capacity = q->capacity, .offset = q->offset, .resolve = q->resolve, .attributes = q->attributes,
        .directory = {MD_INODE_ROOT, MD_INODE_ROOT}, .path = {q->data, q->data + q->length[0]}};
    unsigned index = 0;
    for (unsigned i = 0; i < 2; ++i) if (q->descriptors & (1U << i)) request.directory[i] = input->fd[index++];
    struct md_fs_result result;
    struct md_fs_output buffer = {.data = out->data,
        .capacity = q->operation == MD_FS_OPEN_IMAGE ? sizeof(out->data) : PATH_MAX};
    md_fs_execute(fs, &request, &result, &buffer);
    out->info = result.info; out->size = result.size; out->position = result.position;
    out->host_path=result.host_path;
    out->open_completion=result.open_completion.kind;
    if (result.fd >= 0) output->fd[output->count++] = result.fd;
    if (result.open_completion.kind == MD_OPEN_PIPE) output->fd[output->count++] = result.open_completion.control;
    return result.error;
}
enum peer_phase { REQUEST, REPLY, ACKNOWLEDGE, CONFIRM, RELEASE };
struct peer {
    pid_t process;
    enum peer_phase phase;
    int64_t deadline;
    struct md_fs_reply reply;
    struct md_fs_rights output;
    int watch_reader;
};
static int service_request(struct md_filesystem *fs, int socket,
        struct peer *peer, struct md_fs_statistics *statistics) {
    struct md_inode_store *s = fs->store;
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
            if (packet.operation == MD_FS_WATCH_READ) {
                ssize_t size = md_inode_watch_reserve(s, input.fd[0], reply->data, packet.capacity);
                if (size < 0) reply->error = (int)size;
                else {
                    reply->size = (size_t)size;
                    peer->watch_reader = input.fd[0]; input.count = 0;
                }
            } else reply->error = dispatch(fs, peer->process, &packet, &input, reply, &peer->output);
            md_cost_end(cost, begin);
            OBSERVE(MD_FS_AFTER_DISPATCH, &packet);
        }
    } else reply->error = -EPROTO;
    md_fs_close_rights(&input);
    reply->descriptors = peer->output.count;
    peer->phase = REPLY;
    return 0;
}
static int service_peer(struct md_filesystem *fs,
        struct pollfd *fd, struct peer *peer, struct md_fs_statistics *statistics) {
    struct md_inode_store *s = fs->store;
    if (peer->phase == RELEASE) return 1;
    if (peer->phase == ACKNOWLEDGE) {
        int copied;
        struct md_fs_rights rights = {0};
        long size = md_fs_receive(fd->fd, &copied, sizeof(copied), &rights);
        unsigned count = rights.count; md_fs_close_rights(&rights);
        if (size == -EAGAIN || size == -EINTR) return 0;
        /* A missing/invalid acknowledgement leaves delivery unconfirmed. Never
         * put possibly delivered events back into another consumer's stream. */
        int rejected = size == sizeof(copied) && !count && copied < 0 && copied >= -4095;
        peer->reply.error = md_inode_watch_complete(s, peer->watch_reader, !rejected);
        close(peer->watch_reader); peer->watch_reader = -1;
        if (size != sizeof(copied) || count) return 1;
        peer->phase = CONFIRM;
    }
    if (peer->phase == CONFIRM) {
        struct md_fs_rights none = {0};
        long sent = md_fs_send(fd->fd, &peer->reply.error, sizeof(peer->reply.error), &none);
        if (sent == -EAGAIN || sent == -EINTR) { fd->events = POLLOUT; return 0; }
        if (sent != sizeof(peer->reply.error)) return 1;
        peer->phase = RELEASE; fd->events = POLLIN; return 0;
    }
    if (peer->phase == REQUEST && service_request(fs, fd->fd, peer, statistics)) return 1;
    if (peer->phase != REPLY) return 0;
    size_t size = offsetof(struct md_fs_reply, data) + peer->reply.size;
    long sent = md_fs_send(fd->fd, &peer->reply, size, &peer->output);
    if (sent == -EAGAIN || sent == -EINTR) { fd->events = POLLOUT; return 0; }
    if (sent != (long)size) return 1;
    md_fs_close_rights(&peer->output);
    /* Client close acknowledges receipt. Do not race a nonblocking SEQPACKET
     * receive with server shutdown; never redispatch a completed mutation. */
    peer->phase = peer->watch_reader >= 0 ? ACKNOWLEDGE : RELEASE;
    fd->events = POLLIN;
    OBSERVE(MD_FS_REPLY_SENT, NULL);
    return 0;
}
static void close_peer(struct md_inode_store *s, struct pollfd *fd, struct peer *peer) {
    if (peer->watch_reader >= 0) {
        md_inode_watch_complete(s, peer->watch_reader, peer->phase == ACKNOWLEDGE);
        close(peer->watch_reader); peer->watch_reader = -1;
    }
    md_fs_close_rights(&peer->output);
    close(fd->fd); fd->fd = -1;
    OBSERVE(MD_FS_CONNECTION_CLOSED, NULL);
}
int md_fs_serve(struct md_filesystem *fs, int listener, int stop_fd,
        unsigned timeout_ms, struct md_fs_statistics *statistics, const struct md_fs_work_source *work) {
    struct md_inode_store *s = fs->store;
    if (!s || listener < 0 || stop_fd < 0 || !timeout_ms || timeout_ms > 60000) return -EINVAL;
    enum { SLOTS = 32, BASE = 5 };
    struct pollfd fds[BASE+SLOTS] = {{.fd = listener, .events = POLLIN}, {.fd = stop_fd, .events = POLLIN},
        {.fd = work ? work->fd : -1, .events = POLLIN}, {.fd = -1, .events = POLLIN}};
    /* Fixed capacity, allocated once per service, never per request. */
    struct peer *peers = calloc(SLOTS, sizeof(*peers));
    if (!peers) return -ENOMEM;
    for (unsigned i = BASE; i < BASE+SLOTS; ++i) fds[i].fd = -1;
    int error = 0;
    for (;;) {
        long now = md_event_now(); if (now < 0) { error = (int)now; break; }
        int64_t nearest = INT64_MAX;
        int expired_watch = 0;
        for (unsigned i = 0; i < SLOTS; ++i) if (fds[BASE+i].fd >= 0) {
            if (peers[i].deadline <= now) {
                expired_watch |= peers[i].watch_reader >= 0;
                close_peer(s, &fds[BASE+i], &peers[i]);
            }
            else if (peers[i].deadline < nearest) nearest = peers[i].deadline;
        }
        if (expired_watch && work && work->notification) {
            error = work->notification(work->context, fs, 0);
            if (error) break;
        }
        struct timespec timeout = {0};
        if (nearest != INT64_MAX) {
            int64_t left = nearest-now;
            timeout.tv_sec = left / 1000000000LL; timeout.tv_nsec = left % 1000000000LL;
        }
        /* EVENT_WAIT: request/reply/client-close/stop readiness; the absolute
         * peer deadline drops only that connection, without replaying work. */
        fds[3].fd = work && work->notification_fd ? work->notification_fd(work->context) : -1;
        fds[4] = (struct pollfd){.fd = md_inode_watch_pollfd(s), .events = POLLIN};
        int r = ppoll(fds, BASE+SLOTS, nearest == INT64_MAX ? NULL : &timeout, NULL);
        if (r < 0) { if (errno == EINTR) continue; error = -errno; break; }
        if (fds[1].revents) break;
        int serviced_request = 0;
        if (fds[4].revents) {
            error = md_inode_watch_pump(s);
            if (error) break;
        }
        if (work && fds[2].revents) work->ready(work->context);
        if (work && fds[3].revents) {
            error = work->notification(work->context, fs, fds[3].revents);
            if (error) break;
        }
        if (fds[0].revents & (POLLNVAL | POLLERR | POLLHUP)) { error = -EIO; break; }
        for (unsigned i = BASE; i < BASE+SLOTS; ++i) if (fds[i].fd >= 0 && fds[i].revents) {
            serviced_request |= peers[i-BASE].phase == REQUEST || peers[i-BASE].phase == ACKNOWLEDGE;
            if (service_peer(fs, &fds[i], &peers[i-BASE], statistics)) close_peer(s, &fds[i], &peers[i-BASE]);
        }
        if (fds[0].revents & POLLIN) {
            /* Bound each accept batch so a connecting peer cannot starve shutdown or requests. */
            for (unsigned n = 0; n < SLOTS; ++n) {
                int fd = accept4(listener, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
                if (fd < 0) { if (errno == EAGAIN || errno == EINTR) break; error = -errno; break; }
                if (md_fs_peer(fd)) { close(fd); continue; }
                struct ucred identity; socklen_t identity_size = sizeof(identity);
                if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &identity, &identity_size)
                        || identity_size != sizeof(identity)) { close(fd); continue; }
                unsigned slot = 0; while (slot < SLOTS && fds[BASE+slot].fd >= 0) ++slot;
                if (slot == SLOTS) { close(fd); continue; }
                now = md_event_now();
                if (now < 0) { close(fd); error = (int)now; break; }
                fds[BASE+slot] = (struct pollfd){.fd = fd, .events = POLLIN};
                peers[slot].phase = REQUEST;
                peers[slot].process = identity.pid;
                peers[slot].watch_reader = -1;
                peers[slot].deadline = now + (int64_t)timeout_ms * 1000000;
            }
            if (error) break;
        }
        /* Event-driven retry on watch readiness or cancellation. Namespace
         * commits signal the native journal watch; unrelated stat/open traffic
         * must not rescan every pending reader. */
        if (work && work->notification && fds[3].fd >= 0
                && (fds[4].revents || fds[2].revents || serviced_request)) {
            error = work->notification(work->context, fs, 0);
            if (error) break;
        }
    }
    for (unsigned i = BASE; i < BASE+SLOTS; ++i)
        if (fds[i].fd >= 0) close_peer(s, &fds[i], &peers[i-BASE]);
    free(peers);
    return error;
}
