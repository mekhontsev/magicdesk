#define _GNU_SOURCE
#include "fs_engine.h"
#include "image_catalogue.h"
#include "inode_watch.h"
#include "fs_mounts.h"
#include "credential_registry.h"
#include "ipc_credentials.h"
#include "inode_internal.h"
#include "linux_abi.h"
#include <errno.h>
#include <string.h>

void md_fs_stat_info(const struct stat *s, struct md_fs_info *out) {
    *out = (struct md_fs_info){.device = s->st_dev, .inode = s->st_ino, .links = s->st_nlink,
        .rdev = s->st_rdev, .size = s->st_size, .blocks = s->st_blocks, .mode = s->st_mode,
        .uid = s->st_uid, .gid = s->st_gid, .block_size = (uint32_t)s->st_blksize,
        .access_seconds = s->st_atim.tv_sec, .modify_seconds = s->st_mtim.tv_sec,
        .change_seconds = s->st_ctim.tv_sec, .access_nanos = (uint32_t)s->st_atim.tv_nsec,
        .modify_nanos = (uint32_t)s->st_mtim.tv_nsec, .change_nanos = (uint32_t)s->st_ctim.tv_nsec};
}
void md_fs_execute(struct md_filesystem *fs,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_identity identity = {0};
    if (fs->credentials) {
        int error = md_credentials_read(fs->credentials, q->actor, q->peer, &identity);
        if (error) { *out = (struct md_fs_result){.fd = -1, .error = error}; return; }
    }
    const struct md_identity *previous = fs->store->identity;
    if (!fs->credentials && previous) identity = md_identity_copy(previous);
    if (q->operation == MD_FS_IPC) {
        if (!fs->ipc && fs->ipc_store) {
            int error = md_ipc_credentials_open(fs->ipc_store, &fs->ipc);
            if (error) {
                *out = (struct md_fs_result){.fd=-1,.error=error};
                md_identity_release(&identity); return;
            }
        }
        md_ipc_credentials_execute(fs->ipc, &identity, q, out, output);
        md_identity_release(&identity);
        return;
    }
    if (fs->credentials || previous) {
        if (q->operation == MD_FS_ACCESS && !(q->flags & MD_AT_EACCESS)) {
            identity.uid.fs = identity.uid.real; identity.gid.fs = identity.gid.real;
            identity.caps.effective = identity.uid.real ? 0 : identity.caps.permitted;
        }
        fs->store->identity = &identity;
    }
    unsigned previous_mask=fs->store->creation_mask;
    fs->store->creation_mask=q->attributes.creation_mask;
    if (fs->mounts) md_fs_mounts_execute(fs, q, out, output);
    else md_fs_inode_execute(fs, q, out, output);
    fs->store->creation_mask=previous_mask;
    fs->store->identity = previous;
    md_identity_release(&identity);
}
void md_fs_inode_execute(struct md_filesystem *fs,
        const struct md_fs_request *q, struct md_fs_result *out, const struct md_fs_output *output) {
    struct md_inode_store *s = fs->store;
    struct md_image_catalogue *images = fs->images;
    *out = (struct md_fs_result){.fd = -1};
    void *data = output ? output->data : NULL;
    size_t capacity = output ? output->capacity : 0;
    const char *a = q->path[0] ? q->path[0] : "", *b = q->path[1] ? q->path[1] : "";
    int first = q->directory[0], second = q->directory[1];
    int r = -ENOTSUP; struct stat st;
    switch (q->operation) {
    case MD_FS_GETACL: case MD_FS_SETACL: case MD_FS_REMOVEACL:
        md_inode_acl(s,q,out); return;
    case MD_FS_LISTATTR:
        r=md_inode_attributes(s,first,data,capacity);
        if(r>=0) { out->size=(size_t)r; r=0; }
        break;
    case MD_FS_GETCAP: case MD_FS_SETCAP: case MD_FS_REMOVECAP:
        r = md_inode_capability(s, q, data, capacity);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_CHMOD: case MD_FS_CHOWN: case MD_FS_ACCESS: case MD_FS_UTIMENS:
        r = md_inode_metadata(s, q); break;
    case MD_FS_XATTR_OPEN: r = md_inode_xattr_open(s, first, a, (int)q->mode); goto opened;
    case MD_FS_REOPEN: r = md_catalogue_reopen(images, s, first, (int)q->flags, (int)q->mode); goto opened;
    case MD_FS_WATCH_CREATE: r = md_inode_watch_create(s, (int)q->flags); goto opened;
    case MD_FS_WATCH_ADD:
        r = md_inode_watch_add(s, first, second, q->flags);
        if (r >= 0) { out->position = r; r = 0; }
        break;
    case MD_FS_WATCH_REMOVE: r = md_inode_watch_remove(s, first, (int)q->flags); break;
    case MD_FS_WATCH_CONTAINS:
        out->position = md_inode_watch_contains(s, first); r = 0; break;
    case MD_FS_WATCH_BYTES:
        r = (int)md_inode_watch_bytes(s, first);
        if (r >= 0) { out->position = r; r = 0; }
        break;
    case MD_FS_WATCH_READ:
        r = (int)md_inode_watch_read(s, first, data, q->capacity < capacity ? q->capacity : capacity,
            output ? output->deliver : NULL, output ? output->context : NULL);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_OPEN_IMAGE: {
        if (!data || capacity < sizeof(struct md_image_identity)) { r = -ERANGE; break; }
        struct md_image_identity *identity = data;
        r = md_catalogue_open_image(images, s, first, a, (int)q->flags, identity);
        if (r >= 0) out->size = offsetof(struct md_image_identity, path) + strlen(identity->path) + 1;
        goto opened;
    }
    case MD_FS_OBJECT_ID:
        if (!data || capacity < 33) { r = -ERANGE; break; }
        r = md_catalogue_object_id(images, s, first, data);
        if (!r) out->size = 33;
        break;
    case MD_FS_OPEN_OBJECT: r = md_catalogue_open_object(images, s, a, (int)q->flags); goto opened;
    case MD_FS_SOCKET_BIND: r = md_inode_socket_bind(s, first, a, q->mode, second); break;
    case MD_FS_SOCKET_ADDRESS: case MD_FS_SOCKET_NAME:
        r = q->operation == MD_FS_SOCKET_ADDRESS
            ? md_inode_socket_address(s, first, a, data, capacity)
            : md_inode_socket_name(s, a, data, capacity);
        if (!r) out->size = strlen(data) + 1;
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
        if (!r) md_fs_stat_info(&st, &out->info);
        break;
    case MD_FS_PATH:
        r = md_catalogue_path(images, s, first, data, capacity);
        if (!r) out->size = strlen(data) + 1;
        break;
    case MD_FS_REALPATH:
        r = md_inode_realpath(s, first, a, data, capacity);
        if (!r) out->size = strlen(data) + 1;
        break;
    case MD_FS_TEMPORARY: r = md_inode_temporary(s); goto opened;
    case MD_FS_READLINK:
        r = (int)md_inode_readlink(s, first, a, data, capacity);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
        break;
    case MD_FS_GETDENTS:
        r = (int)md_inode_getdents_deliver(s, first, data, q->capacity < capacity ? q->capacity : capacity,
            output ? output->deliver : NULL, output ? output->context : NULL);
        if (r >= 0) { out->size = (size_t)r; r = 0; }
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
