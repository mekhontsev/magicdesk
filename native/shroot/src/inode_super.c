#define _GNU_SOURCE
#include "inode_internal.h"
#include "inode_leaf.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static int super_policy(struct md_inode_store *s,const struct md_inode_lifetime *scope,int *value) {
    sqlite3_stmt *q=NULL;
    int r=mdi_query_acquire(s,scope->filesystem_kind==MD_FILESYSTEM_OVERLAY
        ? MDI_OVERLAY_READONLY : MDI_SUPER_READONLY,&q);
    if (!r) r=mdi_bind_id(q,1,scope->scope);
    if (!r) {
        int rc=mdi_step(s,q);
        if (rc!=SQLITE_ROW) r=mdi_sql_failure(rc);
        else *value=sqlite3_column_int(q,0)!=0;
    }
    return mdi_query_release(q,r);
}
int mdi_super_refresh(struct md_inode_store *s,sqlite3_int64 version) {
    if (s->super_checked && s->super_version==version) return 0;
    int r=super_policy(s,s->lifetime,&s->super_readonly);
    if (!r) { s->super_version=version; s->super_checked=1; }
    return r;
}
int md_inode_store_readonly(struct md_inode_store *s) {
    int r=mdi_begin(s,0);
    if (r) return r;
    int value=mdi_readonly(s);
    r=mdi_finish(s,0); return r ? r : value;
}
int md_inode_store_scope_readonly(struct md_inode_store *s,const struct md_inode_lifetime *scope) {
    if (!s || !scope) return -EINVAL;
    if (!md_inode_lifetime_catalogue_same(s->lifetime,scope)) return -EXDEV;
    if (md_inode_lifetime_same(s->lifetime,scope)) return md_inode_store_readonly(s);
    int r=mdi_begin(s,0);
    if (r) return r;
    int value=0;
    r=super_policy(s,scope,&value);
    r=mdi_finish(s,r);
    return r ? r : s->readonly || value;
}

static int writer_check(int directory,const char *name,pid_t tid) {
    struct stat st;
    if (fstatat(directory,name,&st,AT_SYMLINK_NOFOLLOW)) return -errno;
    if (!S_ISREG(st.st_mode)) return 0;
    int fd=openat(directory,name,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);
    if (fd<0) return -errno;
    struct f_owner_ex owner={.type=F_OWNER_TID,.pid=tid};
    int r=fcntl(fd,F_SETOWN_EX,&owner) ? -errno : 0;
    if (!r && fcntl(fd,F_SETLEASE,F_RDLCK)) r=errno==EAGAIN ? -EBUSY : -errno;
    /* Closing this private open description releases the probe lease. Guest
     * opens remain excluded by the store gate, not by persistent file leases. */
    if (close(fd) && !r) r=-errno;
    return r;
}
static int directory_writers(int parent,const char *name,pid_t tid,unsigned depth) {
    int fd=openat(parent,name,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) return -errno;
    DIR *directory=fdopendir(fd);
    if (!directory) { int r=-errno; close(fd); return r; }
    int r=0;
    while (!r) {
        errno=0;
        struct dirent *entry=readdir(directory);
        if (!entry) { r=-errno; break; }
        if (!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
        struct stat st;
        if (fstatat(fd,entry->d_name,&st,AT_SYMLINK_NOFOLLOW)) r=-errno;
        else if (S_ISDIR(st.st_mode)) {
            /* Native leaves live one level below object directories, not in
             * the flat promoted-object catalogue. The same gate owns both. */
            r=depth ? -EIO : directory_writers(fd,entry->d_name,tid,depth+1);
        } else r=writer_check(fd,entry->d_name,tid);
    }
    if (closedir(directory) && !r) r=-errno;
    return r;
}
static int writers_check(struct md_inode_store *s,const char *backing) {
    sigset_t signals,previous,pending;
    sigemptyset(&signals); sigaddset(&signals,SIGIO);
    int error=pthread_sigmask(SIG_BLOCK,&signals,&previous);
    if (error) return -error;
    int r=sigpending(&pending) ? -errno : 0;
    /* Do not consume another owner's pending signal. Leases target this worker
     * TID only; a native caller outside the guest gate cannot kill the service. */
    if (!r && sigismember(&pending,SIGIO)) r=-EBUSY;
    int drain=!r;
    pid_t tid=(pid_t)syscall(SYS_gettid);
    if (!r) r=backing ? writer_check(s->objects,backing,tid) : directory_writers(s->objects,".",tid,0);
    if (drain) {
        /* Nonblocking consumption after all leases are closed. A break attempt
         * means the backing escaped the gate; do not publish read-only then. */
        struct timespec zero={0};
        if (sigtimedwait(&signals,NULL,&zero)>=0 && !r) r=-EBUSY;
    }
    error=pthread_sigmask(SIG_SETMASK,&previous,NULL);
    return r ? r : -error;
}
int mdi_super_preserve_writer(struct md_inode_store *s,struct mdi_node *node) {
    if (!node->native || !s->lifetime->temporary || !md_filesystem_temporary(s->lifetime->filesystem_kind)) return 0;
    /* Temporary superblocks exclude existing writers. Keep a writer's final
     * backing reachable by that rare scan, including after rename-overwrite.
     * Persistent files and unreferenced tmpfs leaves keep native deletion. */
    int r=writers_check(s,node->backing);
    return r==-EBUSY ? mdi_leaf_promote(s,node) : r;
}
int md_inode_store_reconfigure(struct md_inode_store *s,int readonly) {
    if (s->readonly) return readonly ? 0 : -EROFS;
    int r=mdi_begin(s,0);
    if (r) return r;
    if (readonly && !s->super_readonly && s->lifetime->temporary
            && md_filesystem_temporary(s->lifetime->filesystem_kind)) r=writers_check(s,NULL);
    /* Policy is metadata, not a guest write: changing it back to writable must
     * be possible under the same gate even while ordinary mutations are denied.
     * Retained and unlinked bodies remain until exclusive store maintenance. */
    /* Persistent stores restrict future operations, not previously acquired
     * writable OFDs or mappings. No writer tracking enters native unlink/IO. */
    sqlite3_stmt *q=NULL;
    if (!r) r=mdi_prepare(s,"INSERT INTO properties VALUES('runtime_readonly/'||?1,?2) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",&q);
    if (!r) r=mdi_bind_id(q,1,s->lifetime->scope);
    if (!r) r=mdi_sql_error(sqlite3_bind_int(q,2,!!readonly));
    if (!r) r=mdi_sql_error(mdi_step(s,q));
    sqlite3_finalize(q);
    r=mdi_finish(s,r);
    s->super_checked=0;
    return r;
}
