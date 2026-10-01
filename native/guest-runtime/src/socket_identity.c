#define _GNU_SOURCE
#include "socket_identity.h"
#include "ipc_credentials.h"
#include "fs_rpc.h"
#include "raw.h"
#include <errno.h>
#include <sys/socket.h>

long md_socket_identity_call(const struct md_fs *fs, unsigned operation, int fd, int second,
        long result, const struct sockaddr_un *name, unsigned length) {
    if (!fs->endpoint[0]) return operation == MD_IPC_PEER ? -ENODATA : 0;
    char encoded[2 * sizeof(name->sun_path) + 1] = {0};
    if (name) {
        if (length < offsetof(struct sockaddr_un, sun_path) || length > sizeof(*name)) return -EINVAL;
        unsigned bytes = length - offsetof(struct sockaddr_un, sun_path);
        for (unsigned i = 0; i < bytes; ++i) {
            unsigned c = (unsigned char)name->sun_path[i];
            encoded[2*i] = "0123456789abcdef"[c >> 4]; encoded[2*i+1] = "0123456789abcdef"[c & 15];
        }
    }
    struct md_fs_request q = {.operation=MD_FS_IPC, .flags=operation, .directory={fd,second},
        .path={encoded,NULL}, .offset=result};
    struct md_fs_response out;
    long r = md_fs_call(fs->endpoint, 5000, &q, &out);
    if (!r) r = out.result.error;
    if (r == -ENOTSUP) return operation == MD_IPC_PEER ? -ENODATA : 0;
    if (r < 0) return r;
    return operation == MD_IPC_PEER ? out.result.fd : out.result.position;
}
long md_socket_identity_hidden(const struct md_fs *fs, int fd, const struct sockaddr_un *a, unsigned n) {
    if (n < offsetof(struct sockaddr_un, sun_path)+15 || a->sun_path[0]) return 0;
    for (unsigned i = 0; i < 14; i++) if (a->sun_path[i+1] != "md-guest-peer-"[i]) return 0;
    return md_socket_identity_call(fs, MD_IPC_NAME, fd, -1, 0, a, n);
}
long md_socket_identity_option(const struct md_fs *fs, const unsigned long *a) {
    if ((int)a[1] != SOL_SOCKET || ((int)a[2] != SO_PEERCRED && (int)a[2] != SO_PEERGROUPS))
        return md_raw(SYS_getsockopt, a[0], a[1], a[2], a[3], a[4], 0);
    long fd = md_socket_identity_call(fs, MD_IPC_PEER, (int)a[0], -1, 0, NULL, 0);
    if (fd == -ENODATA || fd == -EAFNOSUPPORT || fd == -EPROTOTYPE)
        return md_raw(SYS_getsockopt, a[0], a[1], a[2], a[3], a[4], 0);
    if (fd < 0) return fd;
    struct md_ipc_identity id;
    long r = RAW4(pread64, fd, &id, sizeof(id), 0);
    unsigned capacity = 0;
    if (r == sizeof(id)) r = md_read_memory(&capacity, (void *)a[4], sizeof(capacity));
    else r = r < 0 ? r : -EPROTO;
    if (!r && (int)capacity < 0) r = -EINVAL;
    if (!r && (int)a[2] == SO_PEERCRED) {
        struct ucred cred = {.pid=id.pid, .uid=id.uid, .gid=id.gid};
        unsigned n = capacity < sizeof(cred) ? capacity : sizeof(cred);
        r = md_write_memory((void *)a[3], &cred, n);
        if (!r) r = md_write_memory((void *)a[4], &n, sizeof(n));
    } else if (!r) {
        unsigned n = id.group_count * sizeof(uint32_t);
        if (id.group_count > MD_IDENTITY_GROUPS_MAX) r = -EPROTO;
        else if (capacity < n) {
            r = md_write_memory((void *)a[4], &n, sizeof(n));
            if (!r) r = -ERANGE;
        } else {
            char buffer[512];
            for (unsigned pos = 0; !r && pos < n;) {
                unsigned chunk = n - pos < sizeof(buffer) ? n - pos : sizeof(buffer);
                long got = RAW4(pread64, fd, buffer, chunk, sizeof(id)+pos);
                r = got == chunk ? md_write_memory((void *)(a[3]+pos), buffer, chunk) : -EIO;
                pos += chunk;
            }
            if (!r) r = md_write_memory((void *)a[4], &n, sizeof(n));
        }
    }
    RAW1(close, fd); return r;
}
