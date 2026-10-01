#define _GNU_SOURCE
#include "inode_internal.h"
#include "fs_operation.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/xattr.h>
#include <unistd.h>

static int select_acl(struct md_inode_store *s,const struct mdi_node *node,unsigned type,sqlite3_stmt **q) {
    int r=mdi_query_acquire(s,MDI_ACL,q);
    if(!r) r=mdi_bind_id(*q,1,node->id);
    if(!r) r=mdi_sql_error(sqlite3_bind_int(*q,2,type));
    if(!r) {
        int rc=mdi_step(s,*q);
        r=rc==SQLITE_ROW ? 0 : rc==SQLITE_DONE ? -ENODATA : mdi_sql_failure(rc);
    }
    if(!r && !md_acl_valid(sqlite3_column_blob(*q,0),sqlite3_column_bytes(*q,0))) r=-EIO;
    return r;
}
static int copy_acl(struct md_inode_store *s,const struct mdi_node *node,unsigned type,struct md_acl **out,size_t *size) {
    sqlite3_stmt *q=NULL;
    int r=select_acl(s,node,type,&q);
    *out=NULL;
    if(!r) {
        *size=sqlite3_column_bytes(q,0); *out=malloc(*size);
        if(!*out) r=-ENOMEM; else memcpy(*out,sqlite3_column_blob(q,0),*size);
    }
    return mdi_query_release(q,r);
}
int mdi_acl_store(struct md_inode_store *s,const struct mdi_node *node,unsigned type,const void *value,size_t size) {
    if(type!=MD_ACL_ACCESS && type!=MD_ACL_DEFAULT) return -EINVAL;
    if(node->kind==S_IFLNK) return -EOPNOTSUPP;
    if(value && !md_acl_valid(value,size)) return -EINVAL;
    if(size==4 || (type==MD_ACL_ACCESS && size==28)) value=NULL;
    if(type==MD_ACL_DEFAULT && node->kind!=S_IFDIR) return value ? -EACCES : 0;
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,value ? "INSERT INTO inode_acls VALUES(?1,?2,?3) ON CONFLICT(object,type) DO UPDATE SET value=excluded.value"
        : "DELETE FROM inode_acls WHERE object=?1 AND type=?2",&q);
    if(!r) r=mdi_bind_id(q,1,node->id);
    if(!r) r=mdi_sql_error(sqlite3_bind_int(q,2,type));
    if(!r && value) r=mdi_sql_error(sqlite3_bind_blob(q,3,value,size,SQLITE_STATIC));
    if(!r) r=mdi_sql_error(mdi_step(s,q));
    sqlite3_finalize(q);
    return r;
}
int mdi_acl_permission(struct md_inode_store *s,const struct mdi_node *node,const struct md_identity *ids,unsigned mode) {
    sqlite3_stmt *q=NULL;
    int r=select_acl(s,node,MD_ACL_ACCESS,&q);
    if(!r) r=md_acl_permission(sqlite3_column_blob(q,0),sqlite3_column_bytes(q,0),ids,node->uid,node->gid,mode);
    return mdi_query_release(q,r== -ENODATA ? -EIO : r);
}
int mdi_acl_chmod(struct md_inode_store *s,const struct mdi_node *node,unsigned mode) {
    if(!(node->acl_mask&MD_ACL_ACCESS)) return 0;
    struct md_acl *acl=NULL; size_t size=0;
    int r=copy_acl(s,node,MD_ACL_ACCESS,&acl,&size);
    if(!r) { md_acl_chmod(acl,size,mode,0); r=mdi_acl_store(s,node,MD_ACL_ACCESS,acl,size); }
    free(acl); return r;
}
int mdi_acl_inherit(struct md_inode_store *s,const struct mdi_node *parent,const struct mdi_node *node,unsigned mode) {
    struct md_acl *acl=NULL; size_t size=0;
    int r=copy_acl(s,parent,MD_ACL_DEFAULT,&acl,&size);
    if(!r && node->kind==S_IFDIR) r=mdi_acl_store(s,node,MD_ACL_DEFAULT,acl,size);
    if(!r) {
        md_acl_chmod(acl,size,mode,1);
        r=mdi_acl_store(s,node,MD_ACL_ACCESS,acl,size);
        if(!r) r=mdi_metadata(s,node,node->uid,node->gid,md_acl_mode(acl,size,mode));
    }
    free(acl); return r;
}
void md_inode_acl(struct md_inode_store *s,const struct md_fs_request *request,struct md_fs_result *out) {
    int write=request->operation!=MD_FS_GETACL;
    int r=mdi_begin(s,write);
    if(r) { out->error=r; return; }
    struct mdi_node node;
    r=mdi_fd(s,request->directory[0],&node);
    const struct md_identity *ids=s->identity;
    if(!r && write && ids && node.uid!=ids->uid.fs && !md_identity_capable(ids,CAP_FOWNER)) r=-EPERM;
    if(!r && write && !ids) r=-ENOTSUP;
    int present=!r && (node.acl_mask&request->mode);
    struct md_acl *acl=NULL; size_t size=request->capacity;
    if(!r && request->operation==MD_FS_GETACL) {
        if(!present) r=-ENODATA;
        if(!r) r=copy_acl(s,&node,request->mode,&acl,&size);
        int fd=r ? -1 : md_inode_temporary(s);
        if(!r && fd<0) r=fd;
        if(!r && pwrite(fd,acl,size,0)!=(ssize_t)size) r=-EIO;
        if(r && fd>=0) close(fd);
        else if(!r) { out->fd=fd; out->position=size; }
    } else if(!r) {
        /* Linux do_set_acl bypasses ordinary xattr CREATE/REPLACE semantics;
         * zero-sized values and repeated removal both clear the ACL. */
        if(!r && request->operation==MD_FS_SETACL && size) {
            if(size<4 || size>MD_ACL_MAX) r=-EINVAL;
            else if(!(acl=malloc(size))) r=-ENOMEM;
            else if(pread(request->directory[1],acl,size,0)!=(ssize_t)size) r=-EINVAL;
            else if(acl->version!=2) r=-EOPNOTSUPP;
            else if(!md_acl_valid(acl,size)) r=-EINVAL;
        }
        if(!r) r=mdi_copy_up(s,&node);
        if(!r) r=mdi_acl_store(s,&node,request->mode,acl,size);
        if(!r && acl && size>4 && request->mode==MD_ACL_ACCESS) {
            unsigned mode=md_acl_mode(acl,size,node.mode);
            if(ids && !md_identity_capable(ids,CAP_FSETID) && !md_identity_in_group(ids,node.gid)) mode&=~S_ISGID;
            r=mdi_metadata(s,&node,node.uid,node.gid,mode);
        }
        if(!r) r=mdi_event(s,NULL,&node,NULL,IN_ATTRIB,NULL);
    }
    free(acl);
    r=mdi_commit(s,r);
    if(r && out->fd>=0) { close(out->fd); out->fd=-1; out->position=0; }
    out->error=r;
}
int md_inode_attributes(struct md_inode_store *s,int fd,void *data,size_t capacity) {
    int r=mdi_begin(s,0);
    if(r) return r;
    struct mdi_node node;
    r=mdi_fd(s,fd,&node);
    sqlite3_stmt *q=NULL;
    if(!r) r=mdi_prepare(s,"SELECT 1 FROM file_capabilities WHERE object=?1",&q);
    if(!r) r=mdi_bind_id(q,1,node.id);
    int cap=0;
    if(!r) { int rc=mdi_step(s,q); cap=rc==SQLITE_ROW; if(rc!=SQLITE_ROW && rc!=SQLITE_DONE) r=mdi_sql_failure(rc); }
    sqlite3_finalize(q);
    const char *names[]={MD_FILE_CAPABILITY_NAME,MD_ACL_ACCESS_NAME,MD_ACL_DEFAULT_NAME};
    unsigned bits=r ? 0 : (unsigned)cap|(node.acl_mask<<1); size_t length=0;
    for(unsigned i=0;!r && i<3;i++) if(bits&(1U<<i)) {
        size_t n=strlen(names[i])+1;
        if(!data || n>capacity-length) r=-ERANGE;
        else { memcpy((char *)data+length,names[i],n); length+=n; }
    }
    r=mdi_finish(s,r); return r ? r : (int)length;
}
