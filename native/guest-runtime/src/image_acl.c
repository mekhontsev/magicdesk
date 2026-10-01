#define _GNU_SOURCE
#include "image_acl.h"
#include "inode_internal.h"
#include <archive.h>
#include <archive_entry.h>
#include <errno.h>
#include <stdlib.h>

static int order(const void *a,const void *b) {
    const struct md_acl_entry *x=a,*y=b;
    if(x->tag!=y->tag) return x->tag<y->tag ? -1 : 1;
    return x->id==y->id ? 0 : x->id<y->id ? -1 : 1;
}
int md_image_acl_record(struct md_inode_store *s,struct archive_entry *entry,const char *path) {
    if(archive_entry_acl_count(entry,ARCHIVE_ENTRY_ACL_TYPE_NFS4)) return -ENOTSUP;
    for(unsigned kind=MD_ACL_ACCESS;kind<=MD_ACL_DEFAULT;kind++) {
        int wanted=kind==MD_ACL_ACCESS ? ARCHIVE_ENTRY_ACL_TYPE_ACCESS : ARCHIVE_ENTRY_ACL_TYPE_DEFAULT;
        int count=archive_entry_acl_reset(entry,wanted);
        if(!count) continue;
        if(count<3 || (unsigned)count>(MD_ACL_MAX-4)/8) return -EINVAL;
        size_t size=4+(size_t)count*8;
        struct md_acl *acl=malloc(size);
        if(!acl) return -ENOMEM;
        acl->version=2;
        int r=0;
        for(int i=0;!r && i<count;i++) {
            int type,perm,tag,id; const char *name;
            if(archive_entry_acl_next(entry,wanted,&type,&perm,&tag,&id,&name)!=ARCHIVE_OK || type!=wanted) { r=-EINVAL; break; }
            unsigned translated=tag==ARCHIVE_ENTRY_ACL_USER_OBJ ? MD_ACL_USER_OBJ
                : tag==ARCHIVE_ENTRY_ACL_USER ? MD_ACL_USER
                : tag==ARCHIVE_ENTRY_ACL_GROUP_OBJ ? MD_ACL_GROUP_OBJ
                : tag==ARCHIVE_ENTRY_ACL_GROUP ? MD_ACL_GROUP
                : tag==ARCHIVE_ENTRY_ACL_MASK ? MD_ACL_MASK
                : tag==ARCHIVE_ENTRY_ACL_OTHER ? MD_ACL_OTHER : 0;
            int named=translated==MD_ACL_USER || translated==MD_ACL_GROUP;
            if(!translated || perm<0 || perm>7 || (named && id<0)) r=-EINVAL;
            else acl->entries[i]=(struct md_acl_entry){translated,perm,named ? (uint32_t)id : UINT32_MAX};
        }
        if(!r) { qsort(acl->entries,count,8,order); if(!md_acl_valid(acl,size)) r=-EINVAL; }
        sqlite3_stmt *q=NULL;
        if(!r) r=mdi_prepare(s,"INSERT INTO temp.layer_acls VALUES(?1,?2,?3)",&q);
        if(!r) r=mdi_sql_error(sqlite3_bind_text(q,1,path,-1,SQLITE_STATIC));
        if(!r) r=mdi_sql_error(sqlite3_bind_int(q,2,kind));
        if(!r) r=mdi_sql_error(sqlite3_bind_blob(q,3,acl,size,SQLITE_STATIC));
        if(!r) r=mdi_sql_error(mdi_step(s,q));
        sqlite3_finalize(q); free(acl);
        if(r) return r;
    }
    return 0;
}
int md_image_acls_finish(struct md_inode_store *s) {
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,"SELECT path,type,value FROM temp.layer_acls",&q);
    while(!r) {
        int rc=mdi_step(s,q);
        if(rc==SQLITE_DONE) break;
        if(rc!=SQLITE_ROW) { r=mdi_sql_failure(rc); break; }
        struct mdi_location loc;
        r=mdi_walk(s,MD_INODE_ROOT,(const char *)sqlite3_column_text(q,0),MDI_ENTRY,0,&loc);
        if(!r) r=mdi_acl_store(s,&loc.node,sqlite3_column_int(q,1),sqlite3_column_blob(q,2),sqlite3_column_bytes(q,2));
    }
    sqlite3_finalize(q); return r;
}
