#define _GNU_SOURCE
#include "inode_overlay.h"
#include "inode_internal.h"
#include "inode_view.h"
#include "inode_xattr.h"
#include "inode_leaf.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/xattr.h>
#include <unistd.h>

struct merge {
    struct md_inode_store *index, *source;
    int user_xattr, upper;
    unsigned entries;
    unsigned sources[MDI_SOURCES];
};
static int node_origin(struct merge *m, const struct mdi_node *source, const struct mdi_node *target) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(m->index,"INSERT INTO overlay_links(object,upper,lower) VALUES(?1,?2,?3) "
        "ON CONFLICT(object) DO UPDATE SET upper=excluded.upper,lower=max(lower,excluded.lower)",&q);
    if (!r) r=mdi_bind_id(q,1,target->id);
    if (!r && m->upper) r=mdi_bind_id(q,2,source->id);
    if (!r) r=mdi_sql_error(sqlite3_bind_int(q,3,!m->upper));
    if (!r) r=mdi_sql_error(mdi_step(m->index,q));
    sqlite3_finalize(q); q=NULL;
    if (!r && m->upper && target->kind==S_IFREG && target->device==source->device && target->inode==source->inode) {
        r=mdi_prepare(m->index,"UPDATE objects SET shared=?1 WHERE object=?2",&q);
        if (!r) r=mdi_sql_error(sqlite3_bind_int(q,1,source->shared));
        if (!r) r=mdi_bind_id(q,2,target->id);
        if (!r) r=mdi_sql_error(mdi_step(m->index,q));
        sqlite3_finalize(q);
    }
    if (!r && m->upper && source->kind==S_IFDIR) {
        struct mdi_node shared=*target;
        r=mdi_bind_body(m->index,source,&shared);
    }
    return r;
}
static int lower_name(struct merge *m, const struct mdi_node *parent, const char *name, int visible) {
    if (m->upper) return 0;
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(m->index,visible
        ? "INSERT OR IGNORE INTO overlay_lower_names(parent,name) VALUES(?1,?2)"
        : "DELETE FROM overlay_lower_names WHERE parent=?1 AND name=?2",&q);
    if (!r) r=mdi_bind_id(q,1,parent->id);
    if (!r) r=mdi_bind_name(q,2,name);
    if (!r) r=mdi_sql_error(mdi_step(m->index,q));
    sqlite3_finalize(q); return r;
}
/* Separate stores retain a source snapshot and an unpublished index transaction.
 * Views of one store borrow the writer's snapshot instead of acquiring its gate
 * a second time. Neither path holds a transaction across a guest callback. */
static int value(struct md_inode_store *s, const struct mdi_node *node,
        const char *name, char *out) {
    if (node->kind!=S_IFREG && node->kind!=S_IFDIR) return 0;
    char bytes[4]; ssize_t size=mdi_xattr_get(s,node,name,bytes,sizeof(bytes));
    int r=size<0 ? (size==-ENODATA || size==-ENOTSUP ? 0 : (int)size) : 1;
    if (r>0 && out) *out=size==1 ? bytes[0] : 0;
    return r;
}
static int properties(struct merge *m, const struct mdi_node *source, struct mdi_node *target) {
    struct stat st;
    int r=mdi_stat(m->source,source,&st);
    if (!r) r=mdi_metadata(m->index,target,st.st_uid,st.st_gid,st.st_mode&07777);
    if (!r && target->kind!=S_IFLNK) r=mdi_acl_store(m->index,target,MD_ACL_ACCESS,NULL,0);
    if (!r && target->kind==S_IFDIR) r=mdi_acl_store(m->index,target,MD_ACL_DEFAULT,NULL,0);
    if (!r) r=mdi_file_capability(m->index,target,NULL,0);
    sqlite3_stmt *q=NULL;
    if (!r) r=mdi_prepare(m->source,"SELECT type,value FROM inode_acls WHERE object=?1",&q);
    if (!r) r=mdi_bind_id(q,1,source->id);
    while (!r) {
        int next=mdi_step(m->source,q);
        if (next==SQLITE_DONE) break;
        if (next!=SQLITE_ROW) { r=mdi_sql_failure(next); break; }
        r=mdi_acl_store(m->index,target,(unsigned)sqlite3_column_int(q,0),
            sqlite3_column_blob(q,1),(size_t)sqlite3_column_bytes(q,1));
    }
    sqlite3_finalize(q); q=NULL;
    if (!r) r=mdi_prepare(m->source,"SELECT value FROM file_capabilities WHERE object=?1",&q);
    if (!r) r=mdi_bind_id(q,1,source->id);
    if (!r) {
        int next=mdi_step(m->source,q);
        if (next==SQLITE_ROW) r=mdi_file_capability(m->index,target,
            sqlite3_column_blob(q,0),(size_t)sqlite3_column_bytes(q,0));
        else if (next!=SQLITE_DONE) r=mdi_sql_failure(next);
    }
    sqlite3_finalize(q);
    if (!r && target->kind!=S_IFREG) {
        struct timespec times[2]={st.st_atim,st.st_mtim};
        if (utimensat(m->index->objects,target->backing,times,AT_SYMLINK_NOFOLLOW)) r=-errno;
        if (!r && target->kind==S_IFDIR) {
            int from=mdi_backing_directory(m->source,source);
            int a=from<0 ? from : openat(from,source->backing,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
            int b=openat(m->index->objects,target->backing,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
            if (a<0 || b<0) r=from<0 ? from : -errno;
            else r=mdi_replace_attributes(a,b);
            if (a>=0) close(a);
            if (b>=0) close(b);
        }
    }
    if (!r) r=mdi_xattr_virtual_copy(m->index,target,m->source,source);
    if (!r) r=mdi_channel_copy(m->index,target,m->source,source);
    return r ? r : node_origin(m,source,target);
}
static int alias(struct merge *m, const struct mdi_node *source, struct mdi_node *target, int insert) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(m->index,insert
        ? "INSERT INTO overlay_origins(device,inode,origin,object,scope) VALUES(?1,?2,?3,?4,?5)"
        : "SELECT object FROM overlay_origins WHERE device=?1 AND inode=?2 AND origin=?3 AND scope=?5",&q);
    if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,1,(sqlite3_int64)m->source->lifetime->device));
    if (!r) r=mdi_sql_error(sqlite3_bind_int64(q,2,(sqlite3_int64)m->source->lifetime->inode));
    if (!r) r=mdi_bind_id(q,3,source->id);
    if (!r && insert) r=mdi_bind_id(q,4,target->id);
    if (!r) r=mdi_bind_id(q,5,m->index->lifetime->scope);
    if (!r) {
        int next=mdi_step(m->index,q);
        if (insert) r=next==SQLITE_DONE ? 0 : mdi_sql_failure(next);
        else if (next==SQLITE_DONE) r=-ENOENT;
        else if (next!=SQLITE_ROW) r=mdi_sql_failure(next);
        else r=mdi_node(m->index,(const char *)sqlite3_column_text(q,0),target);
    }
    sqlite3_finalize(q); return r;
}
static int security_equal(struct merge *m, const struct mdi_node *source, const struct mdi_node *target) {
    static const char sql[]="SELECT CAST(type AS TEXT),value FROM inode_acls WHERE object=?1 "
        "UNION ALL SELECT '4',value FROM file_capabilities WHERE object=?1 "
        "UNION ALL SELECT name,value FROM inode_xattrs WHERE object=?1 ORDER BY 1";
    sqlite3_stmt *a=NULL,*b=NULL;
    int r=mdi_prepare(m->source,sql,&a);
    if (!r) r=mdi_prepare(m->index,sql,&b);
    if (!r) r=mdi_bind_id(a,1,source->id);
    if (!r) r=mdi_bind_id(b,1,target->id);
    int same=1;
    while (!r && same) {
        int left=mdi_step(m->source,a), right=mdi_step(m->index,b);
        if (left!=SQLITE_ROW && left!=SQLITE_DONE) r=mdi_sql_failure(left);
        if (!r && right!=SQLITE_ROW && right!=SQLITE_DONE) r=mdi_sql_failure(right);
        if (r) break;
        if (left!=right) same=0;
        else if (left==SQLITE_DONE) break;
        else {
            int size=sqlite3_column_bytes(a,1);
            same=!strcmp((const char *)sqlite3_column_text(a,0),(const char *)sqlite3_column_text(b,0))
                && sqlite3_column_type(a,1)==sqlite3_column_type(b,1)
                && size==sqlite3_column_bytes(b,1)
                && !memcmp(sqlite3_column_blob(a,1),sqlite3_column_blob(b,1),(size_t)size);
        }
    }
    sqlite3_finalize(a); sqlite3_finalize(b); return r ? r : same;
}
static int shared_alias(struct merge *m, const struct mdi_node *source,
        const struct mdi_node *parent, struct mdi_node *target) {
    int directory=mdi_backing_directory(m->source,source);
    if (directory<0) return directory;
    int fd=openat(directory,source->backing,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if (fd<0) return -errno;
    struct mdi_node existing;
    int r=mdi_fd(m->index,fd,&existing);
    struct stat original,current;
    if (!r) r=mdi_stat(m->source,source,&original);
    if (!r) r=mdi_stat(m->index,&existing,&current);
    int same=!r && existing.kind==S_IFREG && existing.device==source->device
        && existing.inode==source->inode && original.st_uid==current.st_uid
        && original.st_gid==current.st_gid && (original.st_mode&07777)==(current.st_mode&07777);
    sqlite3_stmt *q=NULL;
    if (same) {
        r=mdi_prepare(m->index,"SELECT 1 FROM overlay_origins WHERE scope=?1 AND object=?2 AND origin=?3 LIMIT 1",&q);
        if (!r) r=mdi_bind_id(q,1,m->index->lifetime->scope);
        if (!r) r=mdi_bind_id(q,2,existing.id);
        if (!r) r=mdi_bind_id(q,3,source->id);
        if (!r) {
            int next=mdi_step(m->index,q);
            if (next==SQLITE_DONE) same=0;
            else if (next!=SQLITE_ROW) r=mdi_sql_failure(next);
        }
        sqlite3_finalize(q);
    }
    if (!r && same) {
        same=security_equal(m,source,&existing);
        if (same<0) r=same;
    }
    if (!r && same) *target=existing;
    else if (!r) {
        /* One OFD must identify one logical inode inside a view. Distinct
         * metadata sharing an OCI body gets its own backing on this cold path. */
        struct stat native;
        if (fstat(fd,&native)) r=-errno;
        if (!r && (native.st_dev!=source->device || native.st_ino!=source->inode)) r=-ESTALE;
        int copy=-1;
        if (!r) r=mdi_allocate(m->index,S_IFREG,0600,O_RDWR,NULL,parent->id,target,&copy);
        if (!r) r=mdi_copy_body(m->index,fd,copy,&native);
        if (copy>=0) close(copy);
    }
    close(fd); return r;
}
static int copy_node(struct merge *m, const struct mdi_node *source,
        const struct mdi_node *parent, struct mdi_node *target) {
    int r=source->kind==S_IFDIR ? -ENOENT : alias(m,source,target,0);
    if (r==-ENOENT && (source->kind==S_IFIFO || source->kind==S_IFSOCK)) {
        char channel[33];
        r=mdi_channel(m->source,source,channel,NULL);
        sqlite3_stmt *q=NULL;
        if (!r) r=mdi_prepare(m->index,"SELECT object FROM inode_channels WHERE scope=?1 AND channel=?2",&q);
        if (!r) r=mdi_bind_id(q,1,m->index->lifetime->scope);
        if (!r) r=mdi_bind_id(q,2,channel);
        if (!r) {
            int next=mdi_step(m->index,q);
            r=next==SQLITE_ROW ? mdi_node(m->index,(const char *)sqlite3_column_text(q,0),target)
                : next==SQLITE_DONE ? -ENOENT : mdi_sql_failure(next);
        }
        sqlite3_finalize(q);
    }
    if (!r && m->upper) return properties(m,source,target);
    if (r!=-ENOENT) return r;
    int fd=-1;
    if (source->kind==S_IFREG) {
        unsigned *registered=&m->sources[source->source];
        int layer=(int)*registered-1;
        r=0;
        if (!*registered) {
            int from=mdi_backing_directory(m->source,source);
            if (from<0) return from;
            r=mdi_source_register(m->index,from,&layer);
            if (!r) r=mdi_sources_open(m->index);
            if (!r) *registered=(unsigned)layer+1;
        }
        if (!r) r=mdi_import_shared(m->index,layer,source->backing,target);
        if (r==-EEXIST) r=shared_alias(m,source,parent,target);
    } else if (source->kind==S_IFDIR || source->kind==S_IFLNK || source->kind==S_IFIFO || source->kind==S_IFSOCK
            || source->kind==S_IFCHR || source->kind==S_IFBLK) {
        char link[PATH_MAX];
        r=0;
        if (source->kind==S_IFLNK) {
            ssize_t n=readlinkat(m->source->objects,source->backing,link,sizeof(link));
            if (n<0) r=-errno;
            else if ((size_t)n==sizeof(link)) r=-ENAMETOOLONG;
            else link[n]=0;
        }
        if (!r) r=mdi_allocate(m->index,source->kind,0700,O_RDWR,
            source->kind==S_IFLNK ? link : NULL,parent->id,target,&fd);
    } else return -EOPNOTSUPP;
    if (!r) r=mdi_device_number(m->index,target,source->rdev);
    if (!r) r=properties(m,source,target);
    if (!r && source->kind!=S_IFDIR) r=alias(m,source,target,1);
    if (fd>=0) close(fd);
    return r;
}
static int clear(struct md_inode_store *s, const struct mdi_node *node) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,"WITH RECURSIVE dirs(id) AS (SELECT ?1 UNION "
        "SELECT n.object FROM names n JOIN dirs d ON n.parent=d.id "
        "JOIN objects o ON o.object=n.object WHERE o.kind=16384) "
        "DELETE FROM overlay_lower_names WHERE parent IN (SELECT id FROM dirs)",&q);
    if (!r) r=mdi_bind_id(q,1,node->id);
    if (!r) r=mdi_sql_error(mdi_step(s,q));
    sqlite3_finalize(q); q=NULL;
    if (!r) r=mdi_prepare(s,"WITH RECURSIVE dirs(id) AS (SELECT ?1 UNION "
        "SELECT n.object FROM names n JOIN dirs d ON n.parent=d.id "
        "JOIN objects o ON o.object=n.object WHERE o.kind=16384) "
        "DELETE FROM names WHERE parent IN (SELECT id FROM dirs)",&q);
    if (!r) r=mdi_bind_id(q,1,node->id);
    if (!r) r=mdi_sql_error(mdi_step(s,q));
    sqlite3_finalize(q); return r;
}
static int merge_directory(struct merge *m, const struct mdi_node *from,
        struct mdi_node *to, unsigned depth) {
    if (depth>256) return -ELOOP;
    char opaque=0;
    int r=value(m->source,from,m->user_xattr ? "user.overlay.opaque" : "trusted.overlay.opaque",&opaque);
    if (r<0) return r;
    r=opaque=='y' ? clear(m->index,to) : 0;
    sqlite3_stmt *q=NULL;
    if (!r) r=mdi_prepare(m->source,"SELECT name,object FROM names WHERE parent=?1 ORDER BY cookie",&q);
    if (!r) r=mdi_bind_id(q,1,from->id);
    while (!r) {
        int next=mdi_step(m->source,q);
        if (next==SQLITE_DONE) break;
        if (next!=SQLITE_ROW) { r=mdi_sql_failure(next); break; }
        if (++m->entries>1000000) { r=-E2BIG; break; }
        int length=sqlite3_column_bytes(q,0);
        char name[NAME_MAX+1];
        if (length<1 || length>NAME_MAX) { r=-EIO; break; }
        memcpy(name,sqlite3_column_blob(q,0),(size_t)length); name[length]=0;
        if (memchr(name,0,(size_t)length) || mdi_name_valid(name)) { r=-EIO; break; }
        struct mdi_node source, existing;
        r=mdi_node(m->source,(const char *)sqlite3_column_text(q,1),&source);
        int found=r ? r : mdi_lookup(m->index,to,name,&existing,NULL,NULL);
        if (found && found!=-ENOENT) { r=found; break; }
        int whiteout=source.kind==S_IFCHR && !source.rdev;
        struct stat st;
        if (!r && source.kind==S_IFREG) {
            r=mdi_stat(m->source,&source,&st);
            if (!r && !st.st_size) whiteout=value(m->source,&source,
                m->user_xattr ? "user.overlay.whiteout" : "trusted.overlay.whiteout",NULL);
        }
        if (r) break;
        if (whiteout<0) { r=whiteout; break; }
        r=lower_name(m,to,name,!whiteout);
        if (!r && !found && (whiteout || source.kind!=S_IFDIR || existing.kind!=S_IFDIR)) {
            if (existing.kind==S_IFDIR) r=clear(m->index,&existing);
            if (!r) r=mdi_delete_name(m->index,to->id,name);
            found=-ENOENT;
        }
        if (r || whiteout) continue;
        if (found==-ENOENT) {
            r=copy_node(m,&source,to,&existing);
            if (!r) r=mdi_add_name(m->index,to->id,name,existing.id);
        }
        if (!r && source.kind==S_IFDIR) r=merge_directory(m,&source,&existing,depth+1);
    }
    sqlite3_finalize(q);
    if (!r) r=properties(m,from,to);
    return r;
}
/* Borrow another scope on the transaction owner's connection and cache. Only
 * the owner may commit/unlock; both projections use the same row invalidation. */
static void borrow_scope(struct md_inode_store *out,struct md_inode_store *owner,struct md_inode_store *scope) {
    *out=*scope; out->db=owner->db; out->locked=1;
    out->read_depth=owner->read_depth; out->write_transaction=owner->write_transaction;
    out->recording=owner->recording; out->operation_changes=owner->operation_changes;
    out->event_sequence=owner->event_sequence;
    out->read_cache=owner->read_cache; out->cache_checked=owner->cache_checked;
    /* Prefix generations belong to this borrowed catalogue, not the source
     * connection. Physical validation also starts with the new gate owner. */
    out->path_cache=NULL;
    mdi_directory_checks_reset(out);
    memset(out->queries,0,sizeof(out->queries));
}
static void release_scope(struct md_inode_store *borrowed,struct md_inode_store *owner) {
    for (unsigned i=0;i<MDI_QUERY_COUNT;++i) sqlite3_finalize(borrowed->queries[i]);
    mdi_path_cache_destroy(borrowed);
    owner->operation_changes=borrowed->operation_changes;
    if (owner->event_sequence<borrowed->event_sequence) owner->event_sequence=borrowed->event_sequence;
}
static int layer(struct md_inode_store *index, struct md_inode_store *source,
        int directory, int user_xattr, int upper) {
    if (!index || !source || index->readonly || index->overlay || md_inode_lifetime_same(index->lifetime,source->lifetime))
        return -EINVAL;
    int shared=index->lifetime->device==source->lifetime->device
        && index->lifetime->inode==source->lifetime->inode;
    int r=shared ? 0 : mdi_begin(source,0);
    if (r) return r;
    struct md_inode_store borrowed;
    struct mdi_node from, to;
    int begun=0;
    if (shared) { r=mdi_begin(index,1); begun=!r; }
    if (shared && !r) { borrow_scope(&borrowed,index,source); source=&borrowed; }
    if (!r) r=mdi_fd(source,directory,&from);
    if (!r && from.kind!=S_IFDIR) r=-ENOTDIR;
    if (!r && !source->readonly) r=mdi_leaf_materialize_tree(source,&from);
    if (!r && !shared) { r=mdi_begin(index,1); begun=!r; }
    if (!r) r=mdi_node(index,mdi_root(index),&to);
    /* All new names remain unpublished until this writer commits. Flush their
     * shared backing directory once before that commit; object contents keep
     * their individual durability, and promotion runs before this batch. */
    int batching=0;
    if (!r) { r=mdi_sync_batch_begin(index); batching=!r; }
    struct merge m={.index=index,.source=source,.user_xattr=!!user_xattr,.upper=upper};
    if (!r) r=merge_directory(&m,&from,&to,0);
    if (shared && begun) release_scope(&borrowed,index);
    if (batching) r=mdi_sync_batch_end(index,r);
    if (begun) r=mdi_commit(index,r);
    if (!shared) return mdi_finish(source,r);
    return r;
}
int md_inode_overlay_layer(struct md_inode_store *index, struct md_inode_store *source,
        int directory, int user_xattr) {
    return layer(index,source,directory,user_xattr,0);
}
/* Linux exposes an empty, mode-000 work/work directory. Recreate only that
 * empty internal directory on remount; never discard foreign workdir contents. */
static int prepare_workdir(struct md_inode_store *s, const struct mdi_node *work) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,"SELECT 1 FROM names WHERE parent=?1 AND name!=?2 LIMIT 1",&q);
    if (!r) r=mdi_bind_id(q,1,work->id);
    if (!r) r=mdi_bind_name(q,2,"work");
    if (!r) {
        int next=mdi_step(s,q);
        if (next==SQLITE_ROW) r=-ENOTEMPTY;
        else if (next!=SQLITE_DONE) r=mdi_sql_failure(next);
    }
    sqlite3_finalize(q);
    struct mdi_node node;
    if (!r) {
        int found=mdi_lookup(s,work,"work",&node,NULL,NULL);
        if (!found) {
            int empty=node.kind==S_IFDIR ? mdi_empty(s,&node) : 0;
            if (empty!=1) r=empty<0 ? empty : -ENOTEMPTY;
            if (!r) r=mdi_delete_name(s,work->id,"work");
            if (!r) r=mdi_event(s,work->id,&node,"work",IN_DELETE,NULL);
        } else if (found!=-ENOENT) r=found;
    }
    int fd=-1;
    if (!r) r=mdi_allocate(s,S_IFDIR,0700,O_RDONLY,NULL,work->id,&node,&fd);
    if (fd>=0) close(fd);
    if (!r) r=mdi_acl_store(s,&node,MD_ACL_ACCESS,NULL,0);
    if (!r) r=mdi_acl_store(s,&node,MD_ACL_DEFAULT,NULL,0);
    if (!r) r=mdi_metadata(s,&node,node.uid,node.gid,0);
    if (!r) r=mdi_add_name(s,work->id,"work",node.id);
    if (!r) r=mdi_event(s,work->id,&node,"work",IN_CREATE,NULL);
    return r;
}
int md_inode_overlay_prepare_upper(struct md_inode_store *index, struct md_inode_store *source,
        int upper_fd, int work_fd, int user_xattr) {
    if (!index || !source || md_inode_lifetime_same(index->lifetime,source->lifetime)
            || index->lifetime->device!=source->lifetime->device
            || index->lifetime->inode!=source->lifetime->inode
            || !strcmp(index->lifetime->scope,MDI_ROOT)) return -EXDEV;
    if (source->readonly || index->readonly) return -EROFS;
    int r=mdi_begin(index,1);
    if (r) return r;
    struct md_inode_store borrowed;
    borrow_scope(&borrowed,index,source);
    struct mdi_node upper,work;
    /* Versions belong to connections: refresh the borrowed superblock policy
     * under this transaction's gate before acquiring upper write authority. */
    borrowed.super_checked=0;
    r=mdi_super_refresh(&borrowed,index->locked_version);
    if (!r && mdi_readonly(&borrowed)) r=-EROFS;
    if (!r) r=mdi_fd(&borrowed,upper_fd,&upper);
    if (!r) r=mdi_fd(&borrowed,work_fd,&work);
    if (!r && (upper.kind!=S_IFDIR || work.kind!=S_IFDIR)) r=-ENOTDIR;
    if (!r) r=mdi_parent_writable(&borrowed,&upper);
    if (!r) r=mdi_parent_writable(&borrowed,&work);
    if (!r) {
        int overlap=mdi_ancestor(&borrowed,upper.id,work.id);
        if (!overlap) overlap=mdi_ancestor(&borrowed,work.id,upper.id);
        if (overlap) r=overlap<0 ? overlap : -EINVAL;
    }
    if (!r) r=mdi_leaf_materialize_directory(&borrowed,&work);
    if (!r) r=mdi_views_collect(index);
    sqlite3_stmt *q=NULL;
    if (!r) r=mdi_prepare(index,"SELECT 1 FROM inode_views WHERE upper IN (?1,?2) OR work IN (?1,?2)",&q);
    if (!r) r=mdi_bind_id(q,1,upper.id);
    if (!r) r=mdi_bind_id(q,2,work.id);
    if (!r) {
        int next=mdi_step(index,q);
        if (next==SQLITE_ROW) r=-EBUSY;
        else if (next!=SQLITE_DONE) r=mdi_sql_failure(next);
    }
    sqlite3_finalize(q); q=NULL;
    if (!r) r=prepare_workdir(&borrowed,&work);
    if (!r) r=mdi_prepare(index,"UPDATE inode_views SET upper=?1,work=?2,user_xattr=?3 "
        "WHERE scope=?4 AND upper IS NULL",&q);
    if (!r) r=mdi_bind_id(q,1,upper.id);
    if (!r) r=mdi_bind_id(q,2,work.id);
    if (!r) r=mdi_sql_error(sqlite3_bind_int(q,3,!!user_xattr));
    if (!r) r=mdi_bind_id(q,4,index->lifetime->scope);
    if (!r) r=mdi_sql_error(mdi_step(index,q));
    if (!r && sqlite3_changes(index->db)!=1) r=-EALREADY;
    sqlite3_finalize(q);
    release_scope(&borrowed,index);
    r=mdi_commit(index,r);
    index->super_checked=0;
    return r ? r : layer(index,source,upper_fd,user_xattr,1);
}
