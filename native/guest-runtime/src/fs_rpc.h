#ifndef MD_FS_RPC_H
#define MD_FS_RPC_H
#include <stdint.h>
#include <linux/limits.h>

enum md_fs_operation {
    MD_FS_CREATE = 1, MD_FS_OPEN, MD_FS_MKDIR, MD_FS_SYMLINK, MD_FS_READLINK,
    MD_FS_LINK, MD_FS_UNLINK, MD_FS_RENAME, MD_FS_STAT, MD_FS_FSTAT, MD_FS_PATH,
    MD_FS_GETDENTS, MD_FS_SEEKDIR, MD_FS_SOCKET_BIND, MD_FS_SOCKET_ADDRESS, MD_FS_SOCKET_NAME
};
/* Explicit root (-1), never an implicit broker cwd. Other values are borrowed FDs. */
struct md_fs_request {
    uint32_t operation, flags, mode;
    int directory[2];
    const char *path[2];
    uint32_t capacity;
    int64_t offset;
};
struct md_fs_info {
    uint64_t device, inode, links, rdev;
    int64_t size, blocks;
    uint32_t mode, uid, gid, block_size;
    int64_t access_seconds, modify_seconds, change_seconds;
    uint32_t access_nanos, modify_nanos, change_nanos, reserved;
};
enum md_fs_delivery { MD_FS_NOT_SENT, MD_FS_UNCONFIRMED, MD_FS_REPLIED };
struct md_fs_result {
    enum md_fs_delivery delivery;
    int error, fd;
    uint32_t size;
    int64_t position;
    struct md_fs_info info;
    char data[PATH_MAX];
};
/* One connection per call: no shared socket, lock, heap, TLS errno or replay.
 * Return value is transport/protocol status; result.error is the remote errno.
 * UNCONFIRMED means a mutation may have committed. A timeout is not cancellation
 * of the remote operation. Success transfers ownership of result.fd to caller.
 * Inputs are adapter-owned memory, not unchecked guest pointers. */
long md_fs_call(const char *abstract_name, unsigned timeout_ms,
        const struct md_fs_request *, struct md_fs_result *);
#endif
