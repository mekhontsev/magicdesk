#define _GNU_SOURCE
#include "socket_namespace.h"
#include "namespace_internal.h"
#include "raw.h"
#include "socket_identity.h"
#include "socket_calls.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>

long md_namespace_socket_bind(const struct md_fs *fs, int socket, const char *path) {
    long mode = md_namespace_creation_mode(fs,0777);
    if (mode < 0) return mode;
    struct md_fs_request q = {.operation = MD_FS_SOCKET_BIND, .directory = {AT_FDCWD, socket},
        .path = {path, path}, .mode = 0777, .attributes.creation_mask = 0777U ^ (unsigned)mode};
    return md_namespace_paths_request(fs, &q);
}
long md_namespace_socket_address(const struct md_fs *fs, const char *path, struct sockaddr_un *address, unsigned *length) {
    struct md_fs_request q = {.operation = MD_FS_SOCKET_ADDRESS,
        .directory = {AT_FDCWD, -1}, .path = {path, NULL}};
    struct md_fs_response out;
    long r = md_namespace_paths_resolve(fs, &q, &out);
    if (r < 0) return r;
    if (out.result.size > sizeof(address->sun_path) || out.result.size < 2) return -EPROTO;
    *address = (struct sockaddr_un){.sun_family = AF_UNIX};
    memcpy(address->sun_path + 1, out.data, out.result.size - 1);
    *length = (unsigned)offsetof(struct sockaddr_un, sun_path) + out.result.size;
    return 0;
}
static long restore_address(const struct md_fs *fs, struct sockaddr_un *address, unsigned *length) {
    size_t header = offsetof(struct sockaddr_un, sun_path);
    if (!fs->endpoint[0] || *length <= header + 1 || *length > sizeof(*address)
            || address->sun_family != AF_UNIX || address->sun_path[0]) return 0;
    size_t size = *length - header - 1;
    char endpoint[sizeof(address->sun_path)];
    memcpy(endpoint, address->sun_path + 1, size);
    endpoint[size] = 0;
    if (md_length(endpoint) != size || !md_prefix(endpoint, "md-guest-inode-")) return 0;
    struct md_fs_request q = {.operation = MD_FS_SOCKET_NAME, .directory = {-1, -1}, .path = {endpoint, NULL}};
    struct md_fs_response out;
    long r = md_fs_call(fs->endpoint, 5000, &q, &out);
    if (!r) r = out.result.error;
    if (r == -ENOENT) return 0; // An unrelated abstract name is not part of this store.
    if (r < 0) return r;
    if (!out.result.size || out.result.size > sizeof(address->sun_path) + 1) return -EPROTO;
    memcpy(address->sun_path, out.data, out.result.size);
    *length = (unsigned)header + out.result.size;
    return 0;
}
long md_namespace_socket_restore(const struct md_fs *fs, int socket, struct sockaddr_un *address, unsigned *length) {
    if (*length < sizeof(address->sun_family) || address->sun_family != AF_UNIX) return 0;
    long hidden=md_socket_identity_hidden(fs,socket,address,*length);
    if (hidden<0) return hidden;
    if (hidden) { *length=offsetof(struct sockaddr_un,sun_path); return 0; }
    return restore_address(fs,address,length);
}
long md_namespace_socket_output(const struct md_fs *fs, long nr, const unsigned long *a) {
    unsigned index = nr == SYS_recvfrom ? 4 : 1;
    unsigned capacity = 0;
    void *target = (void *)a[index];
    if (target) {
        long r = md_read_memory(&capacity, (void *)a[index + 1], sizeof(capacity));
        if (r < 0) return r;
    }
    if (!target) return md_socket_invoke(nr,a);
    // Linux may append a NUL to a pathname occupying all 108 sun_path bytes.
    union { struct sockaddr_un address; unsigned char bytes[sizeof(struct sockaddr_un) + 1]; } buffer = {0};
    struct sockaddr_un *address = &buffer.address;
    unsigned length = sizeof(buffer.bytes);
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    args[index] = (unsigned long)address;
    args[index + 1] = (unsigned long)&length;
    long result = md_socket_invoke(nr,args);
    if (result < 0) return result;
    long r = length > sizeof(buffer.bytes) ? -EPROTO : 0;
    if (!r) {
        r = md_namespace_socket_restore(fs, (int)((nr == SYS_accept || nr == SYS_accept4) ? result : a[0]), address, &length);
    }
    if (!r) r = md_write_memory(target, address, capacity < length ? capacity : length);
    if (!r) r = md_write_memory((void *)a[index + 1], &length, sizeof(length));
    if (r < 0 && (nr == SYS_accept || nr == SYS_accept4)) RAW1(close, result);
    return r < 0 ? r : result;
}
