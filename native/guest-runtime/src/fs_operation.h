#ifndef MD_FS_OPERATION_H
#define MD_FS_OPERATION_H
#include <stddef.h>
#include <stdint.h>
#include "file_capability.h"
#include "file_open.h"

enum md_fs_operation {
    MD_FS_CREATE = 1, MD_FS_OPEN, MD_FS_MKDIR, MD_FS_SYMLINK, MD_FS_READLINK,
    MD_FS_LINK, MD_FS_UNLINK, MD_FS_RENAME, MD_FS_STAT, MD_FS_FSTAT, MD_FS_PATH,
    MD_FS_GETDENTS, MD_FS_SEEKDIR, MD_FS_SOCKET_BIND, MD_FS_SOCKET_ADDRESS, MD_FS_SOCKET_NAME,
    MD_FS_REALPATH, MD_FS_TEMPORARY, MD_FS_OBJECT_ID, MD_FS_OPEN_OBJECT, MD_FS_OPEN_IMAGE,
    MD_FS_WATCH_CREATE, MD_FS_WATCH_ADD, MD_FS_WATCH_REMOVE, MD_FS_WATCH_BYTES,
    MD_FS_WATCH_CONTAINS, MD_FS_WATCH_READ, MD_FS_REOPEN,
    MD_FS_CHMOD, MD_FS_CHOWN, MD_FS_ACCESS, MD_FS_UTIMENS, MD_FS_IPC,
    MD_FS_GETCAP, MD_FS_SETCAP, MD_FS_REMOVECAP,
    MD_FS_GETACL, MD_FS_SETACL, MD_FS_REMOVEACL, MD_FS_LISTATTR,
    MD_FS_XATTR_OPEN, MD_FS_MOUNT_TABLE, MD_FS_NATIVE_MOUNT, MD_FS_MKFIFO, MD_FS_LAST = MD_FS_MKFIFO
};
enum { MD_FS_STAT_MOUNT = 1 };
struct md_fs_attributes {
    uint32_t uid, gid;
    int64_t seconds[2], nanos[2];
    unsigned char capability[MD_FILE_CAPABILITY_MAX];
    uint32_t creation_mask;
};
/* Paths and descriptors are borrowed, already captured by the caller. Root is
 * explicit (-1), never the namespace owner's cwd. No guest pointers enter here. */
struct md_fs_request {
    int actor, peer;
    uint32_t operation, flags, mode;
    int directory[2];
    const char *path[2];
    uint32_t capacity;
    uint64_t resolve;
    int64_t offset;
    struct md_fs_attributes attributes;
};
struct md_fs_info {
    uint64_t device, inode, links, rdev, mount_id;
    int64_t size, blocks;
    uint32_t mode, uid, gid, block_size;
    int64_t access_seconds, modify_seconds, change_seconds;
    uint32_t access_nanos, modify_nanos, change_nanos, reserved;
};
struct md_fs_result {
    int error, fd;
    unsigned host_path;
    struct md_open_completion open_completion;
    size_t size;
    int64_t position;
    struct md_fs_info info;
};
/* Caller-owned output, independent of wire framing. Directory delivery runs
 * synchronously after the read transaction and before advancing the cursor.
 * A failed publication is not permission to replay an operation. */
struct md_fs_output {
    void *data;
    size_t capacity;
    int (*deliver)(void *, const void *, size_t);
    void *context;
};
#endif
