#define _GNU_SOURCE
#include "fs_engine.h"
#include "image_catalogue.h"
#include <errno.h>
#include <string.h>

static void info(const struct stat *s, struct md_fs_info *out) {
    *out = (struct md_fs_info){.device = s->st_dev, .inode = s->st_ino, .links = s->st_nlink,
        .rdev = s->st_rdev, .size = s->st_size, .blocks = s->st_blocks, .mode = s->st_mode,
        .uid = s->st_uid, .gid = s->st_gid, .block_size = (uint32_t)s->st_blksize,
        .access_seconds = s->st_atim.tv_sec, .modify_seconds = s->st_mtim.tv_sec,
        .change_seconds = s->st_ctim.tv_sec, .access_nanos = (uint32_t)s->st_atim.tv_nsec,
        .modify_nanos = (uint32_t)s->st_mtim.tv_nsec, .change_nanos = (uint32_t)s->st_ctim.tv_nsec};
}
void md_fs_execute(struct md_inode_store *s, struct md_image_catalogue *images,
        const struct md_fs_request *q, struct md_fs_result *out) {
    *out = (struct md_fs_result){.delivery = MD_FS_REPLIED, .fd = -1};
    const char *a = q->path[0] ? q->path[0] : "", *b = q->path[1] ? q->path[1] : "";
    int first = q->directory[0], second = q->directory[1];
    int r = -ENOTSUP; struct stat st;
    switch (q->operation) {
    case MD_FS_OBJECT_ID:
        r = md_catalogue_object_id(images, s, first, out->data);
        if (!r) out->size = 33;
        break;
    case MD_FS_OPEN_OBJECT: r = md_catalogue_open_object(images, s, a, (int)q->flags); goto opened;
    case MD_FS_SOCKET_BIND: r = md_inode_socket_bind(s, first, a, q->mode, second); break;
    case MD_FS_SOCKET_ADDRESS: case MD_FS_SOCKET_NAME:
        r = q->operation == MD_FS_SOCKET_ADDRESS
            ? md_inode_socket_address(s, first, a, out->data, sizeof(out->data))
            : md_inode_socket_name(s, a, out->data, sizeof(out->data));
        if (!r) out->size = (uint32_t)strlen(out->data) + 1;
        break;
    case MD_FS_CREATE: r = md_inode_create(s, first, a, q->mode); goto opened;
    case MD_FS_OPEN: r = md_catalogue_open(images, s, first, a, (int)q->flags, q->mode, q->resolve); goto opened;
    case MD_FS_MKDIR: r = md_inode_mkdir(s, first, a, q->mode); break;
    case MD_FS_SYMLINK: r = md_inode_symlink(s, b, first, a); break;
    case MD_FS_LINK: r = md_inode_link(s, first, a, second, b, (int)q->flags); break;
    case MD_FS_UNLINK: r = md_inode_unlink(s, first, a, (int)q->flags); break;
    case MD_FS_RENAME: r = md_inode_rename(s, first, a, second, b, q->flags); break;
    case MD_FS_STAT: case MD_FS_FSTAT:
        r = q->operation == MD_FS_STAT ? md_catalogue_stat(images, s, first, a, (int)q->flags, &st)
            : md_catalogue_fstat(images, s, first, &st);
        if (!r) info(&st, &out->info);
        break;
    case MD_FS_PATH:
        r = md_catalogue_path(images, s, first, out->data, sizeof(out->data));
        if (!r) out->size = (uint32_t)strlen(out->data) + 1;
        break;
    case MD_FS_REALPATH:
        r = md_inode_realpath(s, first, a, out->data, sizeof(out->data));
        if (!r) out->size = (uint32_t)strlen(out->data) + 1;
        break;
    case MD_FS_TEMPORARY: r = md_inode_temporary(s); goto opened;
    case MD_FS_READLINK:
        r = (int)md_inode_readlink(s, first, a, out->data, sizeof(out->data));
        if (r >= 0) { out->size = (uint32_t)r; r = 0; }
        break;
    case MD_FS_GETDENTS:
        r = (int)md_inode_getdents(s, first, out->data, q->capacity);
        if (r >= 0) { out->size = (uint32_t)r; r = 0; }
        break;
    case MD_FS_SEEKDIR: {
        int64_t position = md_inode_seekdir(s, first, q->offset, (int)q->flags);
        if (position < 0) r = (int)position;
        else { out->position = position; r = 0; }
        break;
    }
    }
    out->error = r;
    return;
opened:
    if (r < 0) out->error = r;
    else out->fd = r;
}
