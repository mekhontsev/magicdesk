#ifndef MD_FS_WIRE_H
#define MD_FS_WIRE_H
#include "fs_rpc.h"
#include <stddef.h>
#include <sys/un.h>

#define MD_FS_MAGIC 0x4d444653U
#define MD_FS_VERSION 11U
#define MD_FS_MAX_FDS 2
struct md_fs_packet {
    /* descriptors is a bitmask of the two base-FD slots, in slot order. */
    uint32_t magic, version, operation, flags, mode, descriptors, length[2];
    uint32_t capacity, reserved;
    int32_t actor;
    uint32_t padding;
    int64_t offset;
    uint64_t resolve;
    struct md_fs_attributes attributes;
    char data[2 * PATH_MAX];
};
struct md_fs_reply {
    uint32_t magic, version;
    int32_t error;
    uint32_t descriptors, size, reserved; /* descriptor count, not request slot mask */
    int64_t position;
    struct md_fs_info info;
    char data[sizeof(struct md_image_identity)];
};
struct md_fs_rights { unsigned count; int fd[MD_FS_MAX_FDS]; };
long md_fs_send(int socket, const void *, size_t, const struct md_fs_rights *);
long md_fs_receive(int socket, void *, size_t, struct md_fs_rights *);
void md_fs_close_rights(struct md_fs_rights *);
long md_fs_address(const char *, struct sockaddr_un *);
long md_fs_peer(int socket);
#endif
