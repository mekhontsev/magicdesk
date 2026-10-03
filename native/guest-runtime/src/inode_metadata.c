#define _GNU_SOURCE
#include "inode_internal.h"
#include "fs_operation.h"
#include "linux_abi.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <unistd.h>

int md_inode_runtime_prepare(struct md_inode_store *s) {
    const char *paths[] = {"/dev", "/dev/shm"};
    const mode_t modes[] = {0755, 01777};
    int r = mdi_begin(s,1);
    if (r) return r;
    for (unsigned i=0; !r && i<2; i++) {
        struct mdi_location loc;
        r = mdi_walk(s,MD_INODE_ROOT,paths[i],MDI_ENTRY,1,&loc);
        if (!r && loc.exists) { if (loc.node.kind!=S_IFDIR) r=-ENOTDIR; continue; }
        int fd=-1;
        if (!r) r=mdi_allocate(s,S_IFDIR,modes[i],O_RDONLY,NULL,loc.parent.id,&loc.node,&fd);
        if (!r) r=mdi_metadata(s,&loc.node,0,0,modes[i]);
        if (!r) r=mdi_add_name(s,loc.parent.id,loc.name,loc.node.id);
        if (fd>=0) close(fd);
    }
    return mdi_commit(s,r);
}

int mdi_permission(struct md_inode_store *s, const struct mdi_node *node, int mode, int real) {
    if (mode & ~(R_OK | W_OK | X_OK)) return -EINVAL;
    if (!s->identity) {
        int dir = mdi_backing_directory(s, node);
        return dir < 0 ? dir : syscall(SYS_faccessat2, dir, node->backing,
            mode, real ? 0 : MD_AT_EACCESS) ? -errno : 0;
    }
    struct stat st;
    int error = mdi_stat(s, node, &st);
    if (error || !mode) return error;
    struct md_identity ids = *s->identity;
    if (real) {
        ids.uid.fs = ids.uid.real; ids.gid.fs = ids.gid.real;
        ids.caps.effective = ids.uid.real ? 0 : ids.caps.permitted;
    }
    if (md_identity_capable(&ids, CAP_DAC_OVERRIDE))
        return (mode & X_OK) && !S_ISDIR(st.st_mode) && !(st.st_mode & 0111) ? -EACCES : 0;
    if (node->acl_mask&MD_ACL_ACCESS) return mdi_acl_permission(s,node,&ids,mode);
    unsigned shift = ids.uid.fs == st.st_uid ? 6 : md_identity_in_group(&ids, st.st_gid) ? 3 : 0;
    return (((unsigned)st.st_mode >> shift) & (unsigned)mode) == (unsigned)mode ? 0 : -EACCES;
}
int mdi_open_permission(struct md_inode_store *s, const struct mdi_node *node, int flags) {
    if (flags & O_PATH) return 0;
    int r = mdi_permission(s, node,
        (flags & O_ACCMODE) == O_WRONLY ? W_OK : (flags & O_ACCMODE) == O_RDWR ? R_OK | W_OK : R_OK, 0);
    if (!r && (flags & O_NOATIME) && s->identity && !md_identity_capable(s->identity, CAP_FOWNER)) {
        struct stat st;
        r = mdi_stat(s, node, &st);
        if (!r && st.st_uid != s->identity->uid.fs) r = -EPERM;
    }
    return r;
}
int mdi_sticky(struct md_inode_store *s, const struct mdi_location *loc) {
    if (!s->identity || md_identity_capable(s->identity, CAP_FOWNER)) return 0;
    struct stat parent, child;
    int r = mdi_stat(s, &loc->parent, &parent);
    if (!r && (parent.st_mode & S_ISVTX) && parent.st_uid != s->identity->uid.fs) {
        r = mdi_stat(s, &loc->node, &child);
        if (!r && child.st_uid != s->identity->uid.fs) r = -EPERM;
    }
    return r;
}
int md_inode_xattr_open(struct md_inode_store *s, int original, const char *name, int access) {
    if (!name || (access != F_OK && access != R_OK && access != W_OK)
            || (access == F_OK ? *name != 0 : *name == 0)) return -EINVAL;
    int write = access == W_OK;
    int r = mdi_begin(s, write);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, original, &node);
    const struct md_identity *ids = s->identity;
    /* Match the VFS namespace rules; LSM checks still run on the backing. */
    if (!r && !strncmp(name,"trusted.",8)) {
        if (ids && !md_identity_capable(ids,CAP_SYS_ADMIN)) r = write ? -EPERM : -ENODATA;
    } else if (!r && access != F_OK && strncmp(name,"security.",9) && strncmp(name,"system.",7)) {
        if (!strncmp(name,"user.",5)) {
            struct stat st;
            r = mdi_stat(s,&node,&st);
            if (!r && !S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode)) r = write ? -EPERM : -ENODATA;
            if (!r && write && S_ISDIR(st.st_mode) && (st.st_mode&S_ISVTX) && ids
                    && ids->uid.fs != st.st_uid && !md_identity_capable(ids,CAP_FOWNER)) r = -EPERM;
        }
        if (!r) r = mdi_permission(s,&node,access,0);
    }
    if (!r && write) r = mdi_copy_up(s,&node);
    int fd = -1, directory = r ? -1 : mdi_backing_directory(s,&node);
    if (!r && directory < 0) r = directory;
    if (!r && (fd = openat(directory,node.backing,O_PATH|O_NOFOLLOW|O_CLOEXEC)) < 0) r = -errno;
    r = mdi_commit(s,r);
    if (r && fd >= 0) close(fd);
    return r ? r : fd;
}
int mdi_metadata(struct md_inode_store *s, const struct mdi_node *node, uint32_t uid, uint32_t gid, mode_t mode) {
    if (uid == UINT32_MAX || gid == UINT32_MAX || (mode & ~07777)) return -EINVAL;
    sqlite3_stmt *q = NULL;
    int r = mdi_prepare(s, "UPDATE objects SET uid=?1,gid=?2,mode=?3 WHERE object=?4", &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, uid));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, gid));
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 3, (int)mode));
    if (!r) r = mdi_bind_id(q, 4, node->id);
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    sqlite3_finalize(q);
    /* Keep the caller-owned backing usable without granting kernel set-ID.
     * This is data storage, not the guest's permission authority. */
    int dir = r ? -1 : mdi_backing_directory(s, node);
    if (!r && dir < 0) r = dir;
    if (!r && !node->shared && node->kind != S_IFLNK && fchmodat(dir, node->backing,
            node->kind == S_IFDIR ? 0700 : 0600 | ((mode & 0111) ? 0100 : 0), 0)) r = -errno;
    return r;
}
int md_inode_import_metadata(struct md_inode_store *s, int fd, uint32_t uid, uint32_t gid, mode_t mode) {
    int r = mdi_begin(s, 1);
    if (r) return r;
    struct mdi_node node;
    r = mdi_fd(s, fd, &node);
    if (!r) r = mdi_copy_up(s, &node);
    if (!r) r = mdi_metadata(s, &node, uid, gid, mode);
    return mdi_commit(s, r);
}
int md_inode_metadata(struct md_inode_store *s, const struct md_fs_request *q) {
    if (q->flags & ~(AT_SYMLINK_NOFOLLOW | MD_AT_EACCESS)) return -EINVAL;
    if (q->flags & MD_AT_EACCESS && q->operation != MD_FS_ACCESS) return -EINVAL;
    int r = mdi_begin(s, q->operation != MD_FS_ACCESS);
    if (r) return r;
    struct mdi_node node;
    struct stat st = {0};
    if (q->operation == MD_FS_ACCESS && q->path[0] && *q->path[0]) {
        struct mdi_location loc;
        r = mdi_walk(s, q->directory[0], q->path[0], q->flags & AT_SYMLINK_NOFOLLOW ? MDI_NOFOLLOW : MDI_FOLLOW, 0, &loc);
        if (!r) node = loc.node;
    } else r = mdi_fd(s, q->directory[0], &node);
    if (!r) r = mdi_stat(s, &node, &st);
    if (!r && q->operation == MD_FS_ACCESS)
        return mdi_finish(s, mdi_permission(s, &node, (int)q->mode, !(q->flags & MD_AT_EACCESS)));
    const struct md_identity *ids = s->identity;
    int owner_cap = ids && md_identity_capable(ids, CAP_FOWNER), owner = ids && ids->uid.fs == st.st_uid;
    uint32_t uid = st.st_uid, gid = st.st_gid;
    mode_t mode = st.st_mode & 07777;
    struct timespec times[2] = {{q->attributes.seconds[0], q->attributes.nanos[0]},
        {q->attributes.seconds[1], q->attributes.nanos[1]}};
    if (!r && ids) {
        switch (q->operation) {
        case MD_FS_CHMOD:
            if (!owner_cap && !owner) r = -EPERM;
            else if (node.kind == S_IFLNK) r = -EOPNOTSUPP;
            else {
                mode = q->mode & 07777;
                if (!md_identity_capable(ids, CAP_FSETID) && !md_identity_in_group(ids, gid)) mode &= ~S_ISGID;
            }
            break;
        case MD_FS_CHOWN:
            if (q->attributes.uid != UINT32_MAX) uid = q->attributes.uid;
            if (q->attributes.gid != UINT32_MAX) gid = q->attributes.gid;
            if (!md_identity_capable(ids, CAP_CHOWN) && (!owner || uid != st.st_uid
                    || (gid != st.st_gid && !md_identity_in_group(ids, gid)))) r = -EPERM;
            if (node.kind != S_IFDIR) { mode &= ~S_ISUID; if (mode & S_IXGRP) mode &= ~S_ISGID; }
            break;
        case MD_FS_UTIMENS: {
            int now = 1, omit = 1;
            for (unsigned i = 0; i < 2; i++) {
                long ns = times[i].tv_nsec;
                if (ns != UTIME_NOW && ns != UTIME_OMIT && (ns < 0 || ns >= 1000000000)) r = -EINVAL;
                now &= ns == UTIME_NOW; omit &= ns == UTIME_OMIT;
            }
            if (omit) return mdi_finish(s, 0);
            if (!r && !owner_cap && !owner) r = now ? mdi_permission(s, &node, W_OK, 0) : -EPERM;
            break;
        }
        default: r = -EINVAL;
        }
    }
    if (!r) r = mdi_copy_up(s, &node);
    int dir = r ? -1 : mdi_backing_directory(s, &node);
    if (!r && dir < 0) r = dir;
    if (!r && ids && q->operation != MD_FS_UTIMENS) r = mdi_metadata(s, &node, uid, gid, mode);
    else if (!r && q->operation == MD_FS_CHMOD) {
        if (node.kind == S_IFLNK) r = -EOPNOTSUPP;
        else if (q->mode & 06000) r = -ENOTSUP;
        else if (fchmodat(dir, node.backing, q->mode & 07777, 0)) r = -errno;
    } else if (!r && q->operation == MD_FS_CHOWN) {
        if (fchownat(dir, node.backing, q->attributes.uid, q->attributes.gid, AT_SYMLINK_NOFOLLOW)) r = -errno;
    } else if (!r && q->operation == MD_FS_UTIMENS) {
        if (utimensat(dir, node.backing, times, AT_SYMLINK_NOFOLLOW)) r = -errno;
    }
    if (!r && q->operation == MD_FS_CHOWN) r = mdi_file_capability(s, &node, NULL, 0);
    if (!r && q->operation == MD_FS_CHMOD) r = mdi_acl_chmod(s,&node,mode);
    if (!r) r = mdi_event(s, NULL, &node, NULL, IN_ATTRIB, NULL);
    return mdi_commit(s, r);
}
