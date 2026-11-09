#define _GNU_SOURCE
#include "fs_wire.h"
#include "event_wait.h"
#include "inode_store.h"
#include "ipc_credentials.h"
#include "raw.h"
#include "fd_client.h"
#include "fs_buffer.h"
#include "native_identity.h"
#include "bpf_request.h"
#include "filesystem_ioctl.h"
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/mman.h>

static int valid_entries(const char *data, size_t size) {
    int64_t previous = 0;
    while (size) {
        struct md_directory_entry entry;
        size_t length=md_directory_record_read(data,size,&entry);
        if (!length || length>NAME_MAX || entry.next<=previous) return 0;
        previous = entry.next; data += entry.size; size -= entry.size;
    }
    return 1;
}
int md_fs_result_valid(const struct md_fs_request *request, const struct md_fs_result *reply,
        const char *data, size_t capacity, unsigned descriptors) {
    uint32_t operation=request->operation;
    int peer=operation==MD_FS_IOCTL && md_filesystem_ioctl_opens(request->mode);
    int opens = operation == MD_FS_MOUNT_TABLE || operation == MD_FS_OPEN || operation == MD_FS_CREATE || operation == MD_FS_TEMPORARY
        || operation == MD_FS_OPEN_OBJECT || operation == MD_FS_OPEN_IMAGE || operation == MD_FS_WATCH_CREATE
        || operation == MD_FS_REOPEN || operation==MD_FS_FSOPEN || operation==MD_FS_FSMOUNT || operation==MD_FS_FSPICK
        || operation==MD_FS_FDINFO || operation==MD_FS_OPEN_TREE || operation==MD_FS_SHM_CHANNEL || peer
        || (operation==MD_FS_BPF && md_bpf_opens(request->mode))
        || (operation==MD_FS_LANDLOCK && request->mode==MD_LL_CREATE)
        || operation == MD_FS_TASK_PATH || (operation == MD_FS_IPC
            && (request->flags == MD_IPC_MESSAGE_CREATE || request->flags == MD_IPC_MESSAGE_SENDER));
    if (reply->error > 0 || reply->error < -4095 || reply->host_path>2
            || (reply->proc_namespace && !reply->host_path)
            || (reply->host_path==2 && operation!=MD_FS_LINK && operation!=MD_FS_RENAME
                && (operation!=MD_FS_MOUNT || (request->mode!=MD_FS_MOUNT_BIND
                && request->mode!=MD_FS_MOUNT_PIVOT && request->mode!=MD_FS_MOUNT_MOVE
                && request->mode!=MD_FS_MOUNT_MOVE_FD)))
            || reply->open_completion.kind > MD_OPEN_CALLER || reply->open_completion.kind < MD_OPEN_READY
            || (reply->open_completion.kind && (reply->error || reply->host_path || reply->size
                || (operation != MD_FS_OPEN && operation != MD_FS_REOPEN && operation != MD_FS_OPEN_OBJECT && !peer)
                || (request->flags & O_PATH)))
            || reply->size > capacity
            || descriptors != (unsigned)(opens && !reply->error && !reply->host_path)
                + (reply->open_completion.kind == MD_OPEN_PIPE)
            || (reply->error && reply->size)
            || (reply->size && !reply->host_path && operation != MD_FS_READLINK && operation != MD_FS_PATH && operation != MD_FS_GETDENTS
                && operation != MD_FS_SOCKET_ADDRESS && operation != MD_FS_SOCKET_NAME && operation != MD_FS_REALPATH
                && operation != MD_FS_OBJECT_ID && operation != MD_FS_OPEN_IMAGE && operation != MD_FS_WATCH_READ
                && operation != MD_FS_GETCAP && operation != MD_FS_STATFS
                && !md_fs_operation_value(operation))
            || (!reply->error && !reply->host_path && operation==MD_FS_STATFS
                && reply->size!=sizeof(struct md_fs_statfs))
            || (!reply->error && operation == MD_FS_GETCAP && !md_file_capability_valid(data,reply->size))
            || (operation != MD_FS_OPEN_IMAGE && !md_fs_operation_value(operation) && reply->size > PATH_MAX)
            || (!reply->error && operation == MD_FS_OBJECT_ID && (reply->size != 33 || data[32]))
            || (reply->position && ((operation != MD_FS_SEEKDIR && operation != MD_FS_WATCH_ADD && operation != MD_FS_WATCH_PATH
                && operation != MD_FS_WATCH_BYTES && operation != MD_FS_WATCH_CONTAINS && operation != MD_FS_IPC
                && operation != MD_FS_GETACL && operation != MD_FS_UMASK
                && operation != MD_FS_XATTR && operation != MD_FS_FXATTR && operation != MD_FS_XATTR_OBJECT) || reply->error)) || reply->position < 0
            || (operation==MD_FS_UMASK && reply->position>0777)
            || (operation == MD_FS_GETDENTS && !reply->host_path
                && (reply->size > request->capacity || !valid_entries(data, reply->size)))
            || (operation == MD_FS_WATCH_READ && reply->size > request->capacity)) return 0;
    if (operation==MD_FS_IPC_PEER) {
        if (reply->host_path) return 0;
        if (!reply->error) {
            if (reply->size<sizeof(struct md_ipc_identity)) return 0;
            struct md_ipc_identity id;
            memcpy(&id,data,sizeof(id));
            if (id.group_count>MD_IDENTITY_GROUPS_MAX || reply->size!=sizeof(id)
                    +(request->mode ? id.group_count*sizeof(uint32_t) : 0)) return 0;
        }
    } else if (operation==MD_FS_BPF && !reply->error) {
        if (reply->host_path || reply->position || reply->size>request->capacity) return 0;
        if (request->mode==BPF_OBJ_GET_INFO_BY_FD) {
            if (reply->size<sizeof(struct md_bpf_info)) return 0;
            struct md_bpf_info info; memcpy(&info,data,sizeof(info));
            if (info.kind==MD_BPF_INFO_MAP) return reply->size==sizeof(info);
            if (info.kind!=MD_BPF_INFO_PROGRAM || info.value.program.nr_map_ids>MD_BPF_MAPS_PER_PROGRAM
                    || info.value.program.xlated_prog_len>MD_BPF_INSTRUCTIONS_MAX*sizeof(struct bpf_insn)) return 0;
            size_t bytes=request->capacity-sizeof(info);
            if (bytes>info.value.program.xlated_prog_len) bytes=info.value.program.xlated_prog_len;
            return reply->size==sizeof(info)+bytes;
        }
        if (md_bpf_element(request->mode)) return reply->size==request->capacity;
        if (request->mode==BPF_PROG_QUERY) {
            if (reply->size<sizeof(struct md_bpf_query)) return 0;
            struct md_bpf_query query; memcpy(&query,data,sizeof(query));
            unsigned count=(request->capacity-sizeof(query))/sizeof(uint32_t);
            if (count>query.count) count=query.count;
            return reply->size==sizeof(query)+count*sizeof(uint32_t);
        }
        return reply->size==0;
    } else if (!reply->error && !reply->host_path && md_fs_operation_value(operation)) {
        int read=operation==MD_FS_GETACL || ((operation==MD_FS_XATTR || operation==MD_FS_FXATTR || operation==MD_FS_XATTR_OBJECT)
            && (request->mode==MD_FS_XATTR_GET || request->mode==MD_FS_XATTR_LIST));
        if (reply->position>MD_FS_VALUE_MAX || (read ? reply->size!=(request->capacity ? (uint64_t)reply->position : 0)
            || reply->size>request->capacity : reply->size || reply->position)) return 0;
    }
    int path=operation==MD_FS_PATH || operation==MD_FS_SOCKET_ADDRESS || operation==MD_FS_SOCKET_NAME
        || operation==MD_FS_REALPATH;
    if (reply->host_path && (reply->error || reply->position || !reply->size || reply->size>PATH_MAX || data[0]!='/')) return 0;
    if (!reply->error && (path || reply->host_path)
            && (!reply->size || data[reply->size-1] || md_length(data)!=reply->size-1)) return 0;
    if (!reply->error && !reply->host_path && operation == MD_FS_OPEN_IMAGE) {
        const struct md_image_identity *image = (const void *)data;
        int valid = reply->size > offsetof(struct md_image_identity, path) + 1
            && image->object[32] == 0 && image->path[0] == '/'
            && data[reply->size-1] == 0;
        for (unsigned i = 0; valid && i < 32; i++)
            valid = (image->object[i] >= '0' && image->object[i] <= '9')
                || (image->object[i] >= 'a' && image->object[i] <= 'f');
        for (size_t i = offsetof(struct md_image_identity, path); valid && i + 1 < reply->size; i++)
            valid = data[i] != 0;
        if (!valid) return 0;
    }
    return 1;
}
#ifdef MD_USE_LIBC
static long receive_reply(int socket, int64_t *delivery_deadline, unsigned timeout_ms,
                          const struct md_fs_request *request, struct md_fs_response *out,
                          struct md_fs_reply *reply, uint64_t exchange,
                          int (*deliver)(void *, const void *, size_t), void *context) {
    struct md_fs_rights rights = {0};
    long r; int deferred=0;
    int64_t deadline=*delivery_deadline;
    for (;;) {
        r = md_fs_receive(socket, reply, sizeof(*reply), &rights);
        if (r==sizeof(struct md_fs_deferred)) {
            struct md_fs_deferred admission;
            memcpy(&admission,reply,sizeof(admission));
            unsigned count=rights.count; md_fs_close_rights(&rights);
            if (deferred || count || admission.magic!=MD_FS_MAGIC || admission.version!=MD_FS_VERSION
                    || admission.error!=-EINPROGRESS) return -EPROTO;
            deferred=1; deadline=INT64_MAX;
            continue;
        }
        if (r != -EAGAIN && r != -EINTR) break;
        /* EVENT_WAIT: admitted operation completion or peer shutdown; the
         * handshake timeout must not become a filesystem operation timeout. */
        r = md_event_wait_fd(socket, POLLIN, deadline); if (r < 0) return r;
    }
    if (r < 0) return r;
    if (deferred) {
        long now=md_event_now();
        if (now<0) { md_fs_close_rights(&rights); return now; }
        *delivery_deadline=deadline=now+(int64_t)timeout_ms*1000000;
    }
    if (r<(long)offsetof(struct md_fs_reply,data)) { md_fs_close_rights(&rights); return -EPROTO; }
    if (r>=(long)offsetof(struct md_fs_reply,data) && reply->receipt) {
        if (reply->magic!=MD_FS_MAGIC || reply->version!=MD_FS_VERSION || reply->receipt!=1
                || !exchange || reply->error || rights.count!=1 || !reply->descriptors
                || reply->descriptors>MD_FS_MAX_FDS) {
            md_fs_close_rights(&rights); return -EPROTO;
        }
        size_t size=md_fd_receipt_size(reply->descriptors);
        long memory=RAW6(mmap,NULL,size,PROT_READ|PROT_WRITE,MAP_SHARED,rights.fd[0],0);
        md_fs_close_rights(&rights);
        if (memory<0 && memory>=-4095) return memory;
        long received;
        for (;;) {
            received=md_fs_receive_receipt(socket,(void *)memory,reply->descriptors,&rights,exchange);
            if (received!=-EAGAIN && received!=-EINTR) break;
            received=md_event_wait_fd(socket,POLLIN,deadline);
            if (received<0) break;
        }
        RAW2(munmap,memory,size);
        if (received<0) return received;
    } else if (exchange && rights.count) {
        md_fs_close_rights(&rights); return -EPROTO;
    }
    if (r < (long)offsetof(struct md_fs_reply, data)
            || reply->magic != MD_FS_MAGIC || reply->version != MD_FS_VERSION
            || reply->receipt>1
            || reply->size > sizeof(reply->data)
            || (size_t)r != offsetof(struct md_fs_reply, data)
                + (reply->size<MD_FS_REPLY_CHUNK ? reply->size : MD_FS_REPLY_CHUNK)
            || (reply->size>MD_FS_REPLY_CHUNK && (reply->receipt || reply->descriptors))
            || reply->descriptors != rights.count) {
        md_fs_close_rights(&rights); return -EPROTO;
    }
    size_t received=(size_t)r - offsetof(struct md_fs_reply,data);
    while (received<reply->size) {
        size_t left=reply->size-received, size=left<MD_FS_REPLY_CHUNK ? left : MD_FS_REPLY_CHUNK;
        struct md_fs_rights extra={0};
        /* EVENT_WAIT: the same accepted reply's next chunk; the original call
         * deadline bounds missing bytes without replaying the request. */
        for (;;) {
            r=md_fs_receive(socket,reply->data+received,size,&extra);
            if (r!=-EAGAIN && r!=-EINTR) break;
            r=md_event_wait_fd(socket,POLLIN,deadline);
            if (r<0) break;
        }
        unsigned unexpected=extra.count; md_fs_close_rights(&extra);
        if (r!=(long)size || unexpected) {
            md_fs_close_rights(&rights); return r<0 ? r : -EPROTO;
        }
        received+=size;
    }
    struct md_fs_result result={.error=reply->error,.fd=rights.count ? rights.fd[0] : -1,
        .info=reply->info,.size=reply->size,.position=reply->position,.host_path=reply->host_path,
        .proc_namespace=reply->proc_namespace,
        .open_completion={.kind=(enum md_open_kind)reply->open_completion,.control=rights.count>1 ? rights.fd[1] : -1}};
    if (!md_fs_result_valid(request,&result,reply->data,sizeof(reply->data),rights.count)) {
        md_fs_close_rights(&rights); return -EPROTO;
    }
    out->delivery = MD_FS_REPLIED; out->result=result;
    if (reply->size<=sizeof(out->data)) memcpy(out->data, reply->data, reply->size);
    if (!result.error && !result.host_path && md_fs_operation_value(request->operation)) {
        if (deliver) return deliver(context,reply->data,reply->size);
        if (reply->size>sizeof(out->data)) return -EOVERFLOW;
    }
    return 0;
}
#endif
long md_fs_call_deliver(const char *name, unsigned timeout_ms,
        const struct md_fs_request *request, struct md_fs_response *out,
        int (*deliver)(void *, const void *, size_t), void *context) {
    if (!out) return -EFAULT;
    memset(out, 0, sizeof(*out)); out->result.fd = -1;
    if (!request) return -EFAULT;
    if (!timeout_ms || timeout_ms > 60000) return -EINVAL;
#ifndef MD_USE_LIBC
    (void)name;
    if (deliver && request->operation!=MD_FS_WATCH_READ && request->operation!=MD_FS_GETDENTS
            && !md_fs_operation_value(request->operation)) return -EINVAL;
    /* Guest calls have one transport. Task-affine inputs are captured before
     * worker execution, without retrying an accepted filesystem operation. */
    return md_fs_buffer_call(request,out,deliver,context);
#else
    if (request->capture[0].path || request->capture[1].path
            || request->capture[0].flags || request->capture[1].flags) return -ENOTSUP;
    /* The request is no longer needed after send: reuse its storage for the reply. */
    union { struct md_fs_packet packet; struct md_fs_reply reply; } wire;
    memset(&wire.packet,0,offsetof(struct md_fs_packet,data));
    wire.packet.magic=MD_FS_MAGIC; wire.packet.version=MD_FS_VERSION;
    wire.packet.operation=request->operation; wire.packet.flags=request->flags; wire.packet.mode=request->mode;
    wire.packet.capacity=request->capacity; wire.packet.offset=request->offset; wire.packet.resolve=request->resolve;
    wire.packet.actor=request->actor ? request->actor : md_native_tid(); wire.packet.attributes=request->attributes;
    if (request->value_size>MD_FS_VALUE_MAX || (request->value_size && !request->value)) return -EINVAL;
    wire.packet.value_size=request->value_size;
    struct md_fs_rights rights = {0};
    size_t length = 0;
    for (unsigned i = 0; i < 2; ++i) {
        if (request->object[i]) {
            if (request->directory[i]!=-1 || !md_fs_object_id_valid(request->object[i])) return -EINVAL;
            memcpy(wire.packet.object[i],request->object[i],33);
        }
        const char *path = request->path[i] ? request->path[i] : "";
        size_t n = 0;
        while (path[n]) { if (n == PATH_MAX-1) return -ENAMETOOLONG; ++n; }
        memcpy(wire.packet.data + length, path, n+1); wire.packet.length[i] = n+1; length += n+1;
        if (request->directory[i]==AT_FDCWD) {
            wire.packet.descriptors|=1U<<i;
            wire.packet.cwd|=1U<<i;
        } else if (request->directory[i] < -1) return -EBADF;
        else if (request->directory[i] >= 0) {
            wire.packet.descriptors |= 1U << i;
            rights.fd[rights.count++] = request->directory[i];
        }
    }
    if (request->value_size) {
        memcpy(wire.packet.data+length,request->value,request->value_size); length+=request->value_size;
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
    uint64_t exchange=0;
    while (!r) {
        long begin=md_fd_control(MD_FD_INPUT,0,request->directory[0],request->directory[1]);
        if (begin<0) { r=begin; break; }
        if (!begin && wire.packet.cwd) { r=-ENODATA; break; }
        exchange=(uint64_t)begin; wire.packet.exchange=exchange;
        r = md_fs_send((int)socket, &wire.packet, length, &rights);
        if (r>=0) out->delivery=MD_FS_UNCONFIRMED;
        if (exchange) {
            long sent=md_fd_control(MD_FD_SENT,exchange,0,0);
            if (sent<0) { r=sent; break; }
        }
        if (r == -EAGAIN || r == -EINTR) {
            if (exchange) {
                long end=md_fd_control(MD_FD_END,exchange,0,0);
                if (end<0) { exchange=0; r=end; break; }
            }
            exchange=0;
            r = md_event_wait_fd((int)socket, POLLOUT, deadline); continue;
        }
        if (r >= 0) {
            r = (size_t)r == length ? receive_reply((int)socket, &deadline, timeout_ms,
                request, out, &wire.reply, exchange,deliver,context) : -EIO;
        }
        break;
    }
    if (!r && !out->result.error && !out->result.host_path
            && (request->operation == MD_FS_WATCH_READ || request->operation==MD_FS_GETDENTS)) {
        int copied = deliver ? deliver(context, out->data, out->result.size) : 0;
        struct md_fs_rights none = {0};
        long sent;
        for (;;) {
            sent = md_fs_send((int)socket, &copied, sizeof(copied), &none);
            if (sent != -EAGAIN && sent != -EINTR) break;
            sent = md_event_wait_fd((int)socket, POLLOUT, deadline);
            if (sent < 0) break;
        }
        if (sent != sizeof(copied)) {
            out->delivery = MD_FS_UNCONFIRMED;
            r = sent < 0 ? sent : -EIO;
        } else {
            int committed = -EPROTO;
            struct md_fs_rights rights = {0};
            for (;;) {
                sent = md_fs_receive((int)socket, &committed, sizeof(committed), &rights);
                if (sent != -EAGAIN && sent != -EINTR) break;
                sent = md_event_wait_fd((int)socket, POLLIN, deadline);
                if (sent < 0) break;
            }
            unsigned count = rights.count; md_fs_close_rights(&rights);
            if (sent != sizeof(committed) || count || committed > 0 || committed < -4095) {
                out->delivery = MD_FS_UNCONFIRMED;
                r = sent < 0 ? sent : -EPROTO;
            } else r = committed ? committed : copied;
        }
    }
    if (exchange) {
        long end=md_fd_control(MD_FD_END,exchange,0,0);
        if (!r && end<0) r=end;
    }
    if (r<0 && out->result.fd>=0) {
        RAW1(close,out->result.fd); out->result.fd=-1;
        if (out->result.open_completion.kind==MD_OPEN_PIPE)
            RAW1(close,out->result.open_completion.control);
        out->result.open_completion=(struct md_open_completion){0};
    }
    RAW1(close, socket);
    return r;
#endif
}
long md_fs_call(const char *name, unsigned timeout_ms,
        const struct md_fs_request *request, struct md_fs_response *out) {
    long r=md_fs_call_deliver(name, timeout_ms, request, out, NULL, NULL);
#ifdef MD_USE_LIBC
    if (!r && !out->result.error && !out->result.host_path && request->operation==MD_FS_TASK_PATH) {
        int fd=out->result.fd; out->result.fd=-1;
        out->result.error=(int)(request->mode==MD_FS_TASK_CHDIR ? RAW1(fchdir,fd)
            : RAW2(ftruncate,fd,request->offset));
        RAW1(close,fd);
    }
#endif
    return r;
}
