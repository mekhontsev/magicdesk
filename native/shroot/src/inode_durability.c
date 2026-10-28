#define _GNU_SOURCE
#include "inode_internal.h"
#include "storage_database.h"
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

int mdi_sync(struct md_inode_store *s,int fd) {
    if (s->lifetime->temporary) return 0;
    if (s->directory_sync_batch && fd==s->objects) {
        s->directory_sync_batch=2;
        return 0;
    }
    struct md_cost *cost=!s->statistics ? NULL : fd==s->objects || fd==s->lifetime->root
        ? &s->statistics->sync_directory : &s->statistics->sync_object;
    int64_t begin=md_cost_begin(cost);
    int r=fsync(fd) ? -errno : 0;
    md_cost_end(cost,begin);
    return r;
}
int mdi_sync_batch_begin(struct md_inode_store *s) {
    if (!s->locked || !s->write_transaction || sqlite3_get_autocommit(s->db)
            || s->directory_sync_batch) return -EDEADLK;
    s->directory_sync_batch=1;
    return 0;
}
int mdi_sync_batch_end(struct md_inode_store *s,int result) {
    unsigned pending=s->directory_sync_batch;
    s->directory_sync_batch=0;
    if (!pending || !s->locked || sqlite3_get_autocommit(s->db)) return -EDEADLK;
    return result ? result : pending==2 ? mdi_sync(s,s->objects) : 0;
}
int mdi_checkpoint(struct md_inode_store *s) {
    if (!s->locked || !sqlite3_get_autocommit(s->db)) return -EDEADLK;
    if (s->lifetime->temporary) return 0;
    struct md_cost *cost=s->statistics ? &s->statistics->checkpoint : NULL;
    int64_t begin=md_cost_begin(cost);
    int frames=-1, copied=-1;
    int r=md_database_error(s->db,sqlite3_wal_checkpoint_v2(s->db,"main",SQLITE_CHECKPOINT_FULL,&frames,&copied));
    if (!r && (frames<0 || frames!=copied)) r=-EIO;
    md_cost_end(cost,begin);
    return r;
}
int md_inode_sync(struct md_inode_store *s,int fd,enum md_inode_sync_mode mode) {
    if (mode<MD_INODE_SYNC_FILE || mode>MD_INODE_SYNC_FILESYSTEM) return -EINVAL;
    if (fd<0 && (fd!=MD_INODE_ROOT || mode!=MD_INODE_SYNC_FILESYSTEM)) return -EBADF;
    if (fd>=0) {
        int flags=fcntl(fd,F_GETFL);
        if (flags<0) return -errno;
        if (flags&O_PATH) return -EBADF;
    }
    int r=mdi_begin(s,0);
    if (r) return r;
    struct mdi_node node;
    if (fd>=0) r=mdi_fd(s,fd,&node);
    if (!r && mode!=MD_INODE_SYNC_FILESYSTEM && node.kind!=S_IFREG && node.kind!=S_IFDIR) r=-EINVAL;
    if (!r) {
        int result=mode==MD_INODE_SYNC_FILESYSTEM ? syncfs(s->objects)
            : mode==MD_INODE_SYNC_DATA ? fdatasync(fd) : fsync(fd);
        if (result) r=-errno;
    }
    if (!r) s->sync_requested=1;
    return mdi_finish(s,r);
}
