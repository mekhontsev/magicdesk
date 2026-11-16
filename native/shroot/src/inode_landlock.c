#include "inode_internal.h"
#include <errno.h>
#include <string.h>

struct selection { struct md_inode_store *store; const struct mdi_node *node, *parent; };
int mdi_landlock_matches(struct md_inode_store *s,const struct mdi_node *node,
        const struct mdi_node *parent,const char *root,const struct md_landlock_key *key) {
    if (!md_inode_lifetime_same(key->store,s->lifetime)) return 0;
    if (node && !strcmp(key->object,node->id)) return 1;
    /* File hardlinks have multiple parents. Only the resolved dentry's parent
     * participates; a grant through another alias must not authorize this one. */
    const struct mdi_node *dir=node && node->kind==S_IFDIR ? node : parent;
    if (!dir) return 0;
    if (root && *root) {
        int inside=mdi_ancestor(s,root,key->object);
        if (inside<=0) return inside;
    }
    return mdi_ancestor(s,key->object,dir->id);
}
static int matches(void *data,const struct md_landlock_key *key) {
    struct selection *s=data;
    return mdi_landlock_matches(s->store,s->node,s->parent,
        s->store->landlock_scope ? s->store->landlock_scope->root : NULL,key);
}
int mdi_landlock(struct md_inode_store *s,const struct mdi_node *node,
        const struct mdi_node *parent,uint64_t access) {
    if (!s->landlock || !access) return 0;
    struct selection selected={s,node,parent};
    return md_landlock_check(s->landlock,access,matches,&selected,
        s->landlock_scope ? s->landlock_scope->inherited : NULL);
}
int mdi_landlock_object(struct md_inode_store *s,const struct mdi_node *node,uint64_t access) {
    if (!s->landlock || !access) return 0;
    struct mdi_node parent;
    if (node->kind==S_IFDIR) return mdi_landlock(s,node,NULL,access);
    if (node->native) {
        int r=node->attached ? mdi_node(s,node->parent,&parent) : 0;
        return r ? r : mdi_landlock(s,node,node->attached ? &parent : NULL,access);
    }
    /* Without retained dentry provenance, a unique attached name is enough.
     * Ambiguous aliases fail closed instead of selecting a more permissive one. */
    sqlite3_stmt *q=NULL;
    int r=mdi_prepare(s,"SELECT parent FROM names WHERE object=?1 LIMIT 2",&q);
    if (!r) r=mdi_bind_id(q,1,node->id);
    int found=0;
    char id[33];
    if (!r) {
        int rc=mdi_step(s,q);
        if (rc==SQLITE_ROW) {
            const char *value=(const char *)sqlite3_column_text(q,0);
            if (!value || strlen(value)!=32) r=-EIO;
            else { memcpy(id,value,33); found=1; }
            if (!r) { rc=mdi_step(s,q); if (rc==SQLITE_ROW) found=0; else if (rc!=SQLITE_DONE) r=mdi_sql_failure(rc); }
        } else if (rc!=SQLITE_DONE) r=mdi_sql_failure(rc);
    }
    sqlite3_finalize(q);
    if (!r && found) r=mdi_node(s,id,&parent);
    return r ? r : mdi_landlock(s,node,found ? &parent : NULL,access);
}
int md_inode_landlock_fd(struct md_inode_store *s,int fd,int flags) {
    if (!s->landlock) return 0;
    int r=mdi_begin(s,0);
    if (r) return r;
    struct mdi_node node;
    r=mdi_fd(s,fd,&node);
    if (!r) r=mdi_landlock_object(s,&node,s->image_open ? MD_LL_EXECUTE
        : md_landlock_open_access(flags,node.kind));
    return mdi_finish(s,r);
}
