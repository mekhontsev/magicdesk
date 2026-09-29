#define _GNU_SOURCE
#include "socket_namespace.h"
#include "namespace_internal.h"
#include "raw.h"
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>

long md_namespace_socket_bind(const struct md_fs *fs, int socket, const char *path) {
    long mode = md_namespace_creation_mode(0777);
    if (mode < 0) return mode;
    struct md_fs_request q = {.operation = MD_FS_SOCKET_BIND, .directory = {AT_FDCWD, socket},
        .path = {path, NULL}, .mode = (uint32_t)mode};
    struct md_fs_result out;
    return md_namespace_request(fs, &q, &out);
}
long md_namespace_socket_address(const struct md_fs *fs, const char *path, struct sockaddr_un *address, unsigned *length) {
    struct md_fs_request q = {.operation = MD_FS_SOCKET_ADDRESS,
        .directory = {AT_FDCWD, -1}, .path = {path, NULL}};
    struct md_fs_result out;
    long r = md_namespace_request(fs, &q, &out);
    if (r < 0) return r;
    if (out.size > sizeof(address->sun_path) || out.size < 2) return -EPROTO;
    *address = (struct sockaddr_un){.sun_family = AF_UNIX};
    memcpy(address->sun_path + 1, out.data, out.size - 1);
    *length = (unsigned)offsetof(struct sockaddr_un, sun_path) + out.size;
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
    struct md_fs_result out;
    long r = md_fs_call(fs->endpoint, 5000, &q, &out);
    if (!r) r = out.error;
    if (r == -ENOENT) return 0; // An unrelated abstract name is not part of this store.
    if (r < 0) return r;
    if (!out.size || out.size > sizeof(address->sun_path) + 1) return -EPROTO;
    memcpy(address->sun_path, out.data, out.size);
    *length = (unsigned)header + out.size;
    return 0;
}
long md_namespace_socket_output(const struct md_fs *fs, long nr, const unsigned long *a) {
    struct msghdr message;
    unsigned index = nr == SYS_recvfrom ? 4 : 1;
    unsigned capacity = 0;
    void *target = (void *)a[index];
    if (nr == SYS_recvmsg) {
        long r = md_read_memory(&message, (void *)a[1], sizeof(message));
        if (r < 0) return r;
        target = message.msg_name;
        capacity = message.msg_namelen;
    } else if (target) {
        long r = md_read_memory(&capacity, (void *)a[index + 1], sizeof(capacity));
        if (r < 0) return r;
    }
    if (!target) return md_raw(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
    // Linux may append a NUL to a pathname occupying all 108 sun_path bytes.
    union { struct sockaddr_un address; unsigned char bytes[sizeof(struct sockaddr_un) + 1]; } buffer = {0};
    struct sockaddr_un *address = &buffer.address;
    unsigned length = sizeof(buffer.bytes);
    unsigned long args[6];
    memcpy(args, a, sizeof(args));
    if (nr == SYS_recvmsg) {
        message.msg_name = address;
        message.msg_namelen = length;
        args[1] = (unsigned long)&message;
    } else {
        args[index] = (unsigned long)address;
        args[index + 1] = (unsigned long)&length;
    }
    long result = md_raw(nr, args[0], args[1], args[2], args[3], args[4], args[5]);
    if (result < 0) return result;
    if (nr == SYS_recvmsg) length = message.msg_namelen;
    long r = length > sizeof(buffer.bytes) ? -EPROTO : restore_address(fs, address, &length);
    if (!r) r = md_write_memory(target, address, capacity < length ? capacity : length);
    if (nr == SYS_recvmsg) {
        message.msg_name = target;
        message.msg_namelen = length;
        if (!r) r = md_write_memory((void *)a[1], &message, sizeof(message));
    } else if (!r) r = md_write_memory((void *)a[index + 1], &length, sizeof(length));
    if (r < 0 && (nr == SYS_accept || nr == SYS_accept4)) RAW1(close, result);
    return r < 0 ? r : result;
}
