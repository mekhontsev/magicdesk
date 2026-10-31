#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_overlay.h"
#include "inode_native.h"
#include "fs_operation.h"
#include "linux_abi.h"
#include "file_permissions.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <unistd.h>

int md_inode_runtime_prepare(struct md_inode_store *s) {
    const char *paths[] = {"/dev", "/dev/shm", "/sys", "/proc"};
    const mode_t modes[] = {0755, 01777, 0755, 0755};
    int r = mdi_begin(s,1);
    if (r) return r;
    for (unsigned i=0; !r && i<sizeof(paths)/sizeof(*paths); i++) {
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

int mdi_permission_stat(struct md_inode_store *s,const struct mdi_node *node,struct stat *st) {
    if (!s->locked || node->native || node->descriptor
            || node->kind!=S_IFDIR || node->mode<0 || strlen(node->backing)!=32)
        return mdi_stat(s,node,st);
    int objects=mdi_backing_directory(s,node);
    if (objects<0) return objects;
    uint64_t hash=node->inode^(node->device*UINT64_C(0x9e3779b185ebca87))^(unsigned)objects;
    hash^=hash>>32;
    unsigned first=(hash%64)*4,slot=first;
    for (;slot<first+4;++slot) {
        const struct mdi_directory_check *check=&s->directory_checks[slot];
        if (check->epoch==s->directory_check_epoch && check->objects==objects
                && check->device==node->device && check->inode==node->inode
                && !strcmp(check->backing,node->backing)) break;
    }
    if (slot==first+4) {
        int error=mdi_backing_stat(s,node,st);
        if (error) return error;
        if (s->statistics) ++s->statistics->directory_check_misses;
        slot=first+(s->directory_check_count++%4);
        for (unsigned i=first;i<first+4;++i)
            if (s->directory_checks[i].epoch!=s->directory_check_epoch) { slot=i; break; }
        struct mdi_directory_check *check=&s->directory_checks[slot];
        *check=(struct mdi_directory_check){.epoch=s->directory_check_epoch,
            .objects=objects,.device=node->device,.inode=node->inode};
        memcpy(check->backing,node->backing,sizeof(check->backing));
    } else if (s->statistics) ++s->statistics->directory_check_hits;
    /* Only permission callers use this projection. User-visible stat, native
     * attributes and ordinary file IO always take their own current snapshot. */
    *st=(struct stat){.st_mode=node->kind|node->mode,.st_uid=node->uid,.st_gid=node->gid,
        .st_nlink=node->links,.st_dev=node->logical_device,.st_ino=node->logical_inode};
    return 0;
}
static int permission_observed(struct md_inode_store *s,const struct mdi_node *node,int mode,
        const struct md_identity *identity,const struct stat *observed) {
    if (mode & ~(R_OK | W_OK | X_OK)) return -EINVAL;
    if (!identity) {
        int dir = mdi_backing_directory(s, node);
        return dir < 0 ? dir : syscall(SYS_faccessat2, dir, node->backing,
            mode, MD_AT_EACCESS) ? -errno : 0;
    }
    struct stat st;
    int error=0;
    if (observed) st=*observed;
    else error=mdi_permission_stat(s,node,&st);
    if (error || !mode) return error;
    if (md_file_dac_override(&st,mode,identity)) return 0;
    if (node->acl_mask&MD_ACL_ACCESS) return mdi_acl_permission(s,node,identity,mode);
    return md_file_mode_allows(&st,mode,identity) ? 0 : -EACCES;
}
int mdi_permission_as(struct md_inode_store *s,const struct mdi_node *node,int mode,const struct md_identity *identity) {
    return permission_observed(s,node,mode,identity,NULL);
}
int mdi_permission(struct md_inode_store *s, const struct mdi_node *node, int mode, int real) {
    struct md_identity ids;
    const struct md_identity *actor=s->identity;
    if (real && actor) {
        ids=md_identity_access(actor,0);
        actor=&ids;
    }
    int r;
    if (real && !actor) {
        int dir=mdi_backing_directory(s,node);
        r=dir<0 ? dir : syscall(SYS_faccessat2,dir,node->backing,mode,0) ? -errno : 0;
    } else r=mdi_permission_as(s,node,mode,actor);
    return r ? r : mdi_overlay_permission(s,node,mode);
}
int mdi_open_permission(struct md_inode_store *s,const struct mdi_node *node,int flags,const struct stat *observed) {
    if (flags & O_PATH) return 0;
    /* The loader reads the ELF on behalf of exec, not as guest file IO.
     * Execute-only helpers must retain X_OK without gaining R_OK. */
    if (s->image_open && node->kind!=S_IFREG) return -EACCES;
    int access=s->image_open ? X_OK : (flags&O_ACCMODE)==O_WRONLY ? W_OK
        : (flags&O_ACCMODE)==O_RDWR ? R_OK|W_OK : R_OK;
    int r=permission_observed(s,node,access,s->identity,observed);
    if (!r) r=mdi_overlay_permission(s,node,access);
    if (s->image_open) return r;
    if (!r && (flags & O_NOATIME) && s->identity) {
        struct stat st;
        if (observed) st=*observed;
        else r=mdi_stat(s,node,&st);
        if (!r) r = md_file_noatime(&st,s->identity);
    }
    return r;
}
int mdi_sticky_as(struct md_inode_store *s, const struct mdi_location *loc, const struct md_identity *identity) {
    if (!identity) return 0;
    struct stat parent, child;
    int r = mdi_permission_stat(s, &loc->parent, &parent);
    if (!r && (parent.st_mode & S_ISVTX) && parent.st_uid != identity->uid.fs) {
        r = mdi_stat(s, &loc->node, &child);
        if (!r && child.st_uid != identity->uid.fs
                && !md_identity_file_capable(identity,child.st_uid,child.st_gid,CAP_FOWNER)) r = -EPERM;
    }
    return r;
}
int mdi_sticky(struct md_inode_store *s, const struct mdi_location *loc) {
    return mdi_sticky_as(s,loc,s->identity);
}
int mdi_xattr_permission_as(struct md_inode_store *s, const struct mdi_node *node,
        const char *name, int access, const struct md_identity *ids) {
    if (!name || (access != F_OK && access != R_OK && access != W_OK)
            || (access == F_OK ? *name != 0 : *name == 0)) return -EINVAL;
    int write = access == W_OK;
    int r=0;
    /* Match the VFS namespace rules; LSM checks still run on the backing. */
    if (!r && !strncmp(name,"trusted.",8)) {
        if (ids && !md_identity_capable_in(ids,NULL,CAP_SYS_ADMIN)) r = write ? -EPERM : -ENODATA;
    } else if (!r && access != F_OK && strncmp(name,"security.",9) && strncmp(name,"system.",7)) {
        if (!strncmp(name,"user.",5)) {
            struct stat st;
            r = mdi_stat(s,node,&st);
            if (!r && !S_ISREG(st.st_mode) && !S_ISDIR(st.st_mode)) r = write ? -EPERM : -ENODATA;
            if (!r && write && S_ISDIR(st.st_mode) && (st.st_mode&S_ISVTX) && ids
                    && ids->uid.fs != st.st_uid && !md_identity_file_capable(ids,st.st_uid,st.st_gid,CAP_FOWNER)) r = -EPERM;
        }
        if (!r) r = mdi_permission_as(s,node,access,ids);
    }
    return r;
}
int mdi_xattr_permission(struct md_inode_store *s, const struct mdi_node *node, const char *name, int access) {
    int r=mdi_xattr_permission_as(s,node,name,access,s->identity);
    return r ? r : mdi_overlay_xattr(s,node,name,access);
}
int mdi_setattr_permission(struct md_inode_store *s, const struct mdi_node *node,
        const struct md_fs_request *q, const struct md_identity *ids) {
    if (!ids) return 0;
    struct stat st;
    int r=mdi_stat(s,node,&st);
    if (r) return r;
    int owner=ids->uid.fs==st.st_uid;
    int owner_cap=md_identity_file_capable(ids,st.st_uid,st.st_gid,CAP_FOWNER);
    switch (q->operation) {
    case MD_FS_CHMOD:
    case MD_FS_SETACL:
    case MD_FS_REMOVEACL:
        return owner || owner_cap ? 0 : -EPERM;
    case MD_FS_CHOWN: {
        uint32_t uid=q->attributes.uid==UINT32_MAX ? st.st_uid : q->attributes.uid;
        uint32_t gid=q->attributes.gid==UINT32_MAX ? st.st_gid : q->attributes.gid;
        return md_identity_file_capable(ids,st.st_uid,st.st_gid,CAP_CHOWN)
            || (owner && uid==st.st_uid && (gid==st.st_gid || md_identity_in_group(ids,gid))) ? 0 : -EPERM;
    }
    case MD_FS_SETCAP:
    case MD_FS_REMOVECAP:
        return md_identity_capable_in(ids,NULL,CAP_SETFCAP) ? 0 : -EPERM;
    case MD_FS_UTIMENS: {
        int now=1,omit=1;
        for (unsigned i=0;i<2;++i) {
            long ns=q->attributes.nanos[i];
            if (ns!=UTIME_NOW && ns!=UTIME_OMIT && (ns<0 || ns>=1000000000)) return -EINVAL;
            now&=ns==UTIME_NOW; omit&=ns==UTIME_OMIT;
        }
        if (omit || owner || owner_cap) return 0;
        return now ? mdi_permission_as(s,node,W_OK,ids) : -EPERM;
    }
    default: return -EINVAL;
    }
}
int mdi_metadata(struct md_inode_store *s, const struct mdi_node *node, uint32_t uid, uint32_t gid, mode_t mode) {
    if (uid == UINT32_MAX || gid == UINT32_MAX || (mode & ~07777)) return -EINVAL;
    if (node->native) {
        int fd=mdi_backing_open(s,node,O_PATH);
        if (fd<0) return fd;
        int r=mdi_native_metadata(fd,uid,gid,mode);
        close(fd); return r;
    }
    sqlite3_stmt *q = NULL;
    int r = mdi_query_acquire(s, MDI_METADATA, &q);
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 1, uid));
    if (!r) r = mdi_sql_error(sqlite3_bind_int64(q, 2, gid));
    if (!r) r = mdi_sql_error(sqlite3_bind_int(q, 3, (int)mode));
    if (!r) r = mdi_bind_id(q, 4, node->id);
    if (!r) r = mdi_sql_error(mdi_step(s, q));
    r = mdi_query_release(q,r);
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
    int r = mdi_begin(s, 0);
    if (r) return r;
    if (q->operation!=MD_FS_ACCESS && mdi_readonly(s)) return mdi_finish(s,-EROFS);
    struct mdi_node node;
    struct stat st = {0};
    if (q->path[0] && *q->path[0]) {
        struct mdi_location loc;
        r = mdi_walk(s, q->directory[0], q->path[0], q->flags & AT_SYMLINK_NOFOLLOW ? MDI_NOFOLLOW : MDI_FOLLOW, 0, &loc);
        if (!r) node = loc.node;
    } else r = mdi_fd(s, q->directory[0], &node);
    if (!r) r = mdi_stat(s, &node, &st);
    if (!r && q->operation == MD_FS_ACCESS)
        return mdi_finish(s, mdi_permission(s, &node, (int)q->mode, !(q->flags & MD_AT_EACCESS)));
    const struct md_identity *ids = s->identity;
    if (!r) r=mdi_setattr_permission(s,&node,q,ids);
    if (!r && q->operation==MD_FS_UTIMENS && q->attributes.nanos[0]==UTIME_OMIT
            && q->attributes.nanos[1]==UTIME_OMIT) return mdi_finish(s,0);
    if (!r && !node.native) r=mdi_write(s);
    if (!r) r=mdi_overlay_setattr(s,&node,q);
    uint32_t uid = st.st_uid, gid = st.st_gid;
    mode_t mode = st.st_mode & 07777;
    struct timespec times[2] = {{q->attributes.seconds[0], q->attributes.nanos[0]},
        {q->attributes.seconds[1], q->attributes.nanos[1]}};
    if (!r && (ids || node.native)) {
        switch (q->operation) {
        case MD_FS_CHMOD:
            if (node.kind == S_IFLNK) r = -EOPNOTSUPP;
            else {
                mode = q->mode & 07777;
                if (ids && !md_identity_file_capable(ids,st.st_uid,st.st_gid,CAP_FSETID) && !md_identity_in_group(ids, gid)) mode &= ~S_ISGID;
            }
            break;
        case MD_FS_CHOWN:
            if (q->attributes.uid != UINT32_MAX) uid = q->attributes.uid;
            if (q->attributes.gid != UINT32_MAX) gid = q->attributes.gid;
            if (node.kind != S_IFDIR) { mode &= ~S_ISUID; if (mode & S_IXGRP) mode &= ~S_ISGID; }
            break;
        case MD_FS_UTIMENS: break;
        default: r = -EINVAL;
        }
    }
    if (!r) r = mdi_copy_up(s, &node);
    int dir = r ? -1 : mdi_backing_directory(s, &node);
    if (!r && dir < 0) r = dir;
    if (!r && node.native && q->operation==MD_FS_CHOWN) {
        int fd=mdi_backing_open(s,&node,O_PATH);
        r=fd<0 ? fd : mdi_native_chown(fd,uid,gid,mode);
        if (fd>=0) close(fd);
    } else if (!r && (ids || node.native) && q->operation != MD_FS_UTIMENS) r = mdi_metadata(s, &node, uid, gid, mode);
    else if (!r && q->operation == MD_FS_CHMOD) {
        if (node.kind == S_IFLNK) r = -EOPNOTSUPP;
        else if (q->mode & 06000) r = -ENOTSUP;
        else if (fchmodat(dir, node.backing, q->mode & 07777, 0)) r = -errno;
    } else if (!r && q->operation == MD_FS_CHOWN) {
        if (fchownat(dir, node.backing, q->attributes.uid, q->attributes.gid, AT_SYMLINK_NOFOLLOW)) r = -errno;
    } else if (!r && q->operation == MD_FS_UTIMENS) {
        if (node.descriptor) {
            char path[64]; r=mdi_backing_path(s,&node,path,sizeof(path));
            if (!r && utimensat(AT_FDCWD,path,times,0)) r=-errno;
        } else if (utimensat(dir, node.backing, times, AT_SYMLINK_NOFOLLOW)) r = -errno;
    }
    if (!r && q->operation == MD_FS_CHOWN && !node.native) r = mdi_file_capability(s, &node, NULL, 0);
    if (!r && q->operation == MD_FS_CHMOD) r = mdi_acl_chmod(s,&node,mode);
    if (!r) r = mdi_event(s, NULL, &node, NULL, IN_ATTRIB, NULL);
    return mdi_commit(s, r);
}
