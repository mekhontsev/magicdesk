#ifndef MD_FS_WIRE_H
#define MD_FS_WIRE_H
#include "fs_rpc.h"
#include "fd_receipt.h"
#include <stddef.h>
#include <sys/un.h>

#define MD_FS_MAGIC 0x4d444653U
#define MD_FS_VERSION 22U
#define MD_FS_MAX_FDS 2
enum { MD_FS_REPLY_CHUNK=65536 };
/* One admission record only for a parked operation. It is not a result and
 * never authorizes replay; daemon completion or socket closure ends the wait. */
struct md_fs_deferred { uint32_t magic,version; int32_t error; };
struct md_fs_packet {
    /* Base-slot masks: cwd slots use the authenticated exchange's captured
     * directory. Other descriptor slots travel as SCM_RIGHTS, in slot order. */
    uint32_t magic, version, operation, flags, mode, descriptors, length[2];
    uint32_t capacity, cwd;
    int32_t actor;
    uint32_t value_size;
    int64_t offset;
    uint64_t resolve;
    uint64_t exchange;
    struct md_fs_attributes attributes;
    char object[2][33];
    char data[2 * PATH_MAX + MD_FS_VALUE_MAX];
};
struct md_fs_reply {
    /* size is the complete output. The first packet includes at most one chunk;
     * descriptor-free continuation packets carry the remaining bounded bytes. */
    uint32_t magic, version;
    int32_t error;
    uint32_t descriptors, size, host_path, open_completion, receipt; /* descriptor count, not request slot mask */
    int64_t position;
    uint64_t proc_namespace;
    struct md_fs_info info;
    char data[MD_FS_OUTPUT_MAX];
};
struct md_fs_rights { unsigned count; int fd[MD_FS_MAX_FDS]; };
long md_fs_send(int socket, const void *, size_t, const struct md_fs_rights *);
long md_fs_receive(int socket, void *, size_t, struct md_fs_rights *);
/* Bounded private seqpacket vectors. Receive closes every installed right on
 * truncation/malformed control and publishes count only on success. */
long md_fs_send_fds(int socket, const void *, size_t, const int *, unsigned count);
long md_fs_receive_fds(int socket, void *, size_t, int *, unsigned capacity, unsigned *count);
/* Same bounded parser and ownership as receive; leaves the packet queued.
 * Only for a selected private transport, never arbitrary application FDs. */
long md_fs_peek_fds(int socket, void *, size_t, int *, unsigned capacity, unsigned *count);
/* Admission-time SCM_RIGHTS capability for the selected executor. Returns
 * one when transferable, zero on host denial, or a negative setup error. */
int md_fs_rights_transferable(const int *, unsigned count);
long md_fs_receive_receipt(int socket, struct md_fd_receipt *, unsigned capacity,
        struct md_fs_rights *, uint64_t exchange);
void md_fs_close_rights(struct md_fs_rights *);
long md_fs_address(const char *, struct sockaddr_un *);
long md_fs_peer(int socket);
/* Private runtime transport only: connected Unix seqpacket, nonblocking,
 * CLOEXEC, same real executor identity. Returns the kernel peer PID or error. */
long md_fs_private_peer(int socket);
#endif
