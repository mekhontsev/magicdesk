#include "posix_acl.h"
#include <errno.h>

/* Linux's xattr representation is little-endian, like the supported ARM64 ABI. */
_Static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "ACL byte order");
_Static_assert(sizeof(struct md_acl_entry)==8, "ACL entry ABI");
static size_t count(size_t size) { return (size-4)/8; }
int md_acl_valid(const void *data, size_t size) {
    if (!data || size<4 || size>MD_ACL_MAX || (size-4)%8) return 0;
    const struct md_acl *acl=data;
    if (acl->version!=2) return 0;
    if (size==4) return 1;
    unsigned phase=0, named=0, mask=0;
    uint32_t previous=0;
    for (size_t i=0;i<count(size);i++) {
        const struct md_acl_entry *e=&acl->entries[i];
        if (e->permissions>7) return 0;
        if (e->tag!=MD_ACL_USER && e->tag!=MD_ACL_GROUP && e->id!=UINT32_MAX) return 0;
        switch(e->tag) {
        case MD_ACL_USER_OBJ: if (phase) return 0; phase=1; break;
        case MD_ACL_USER:
            if ((phase!=1 && phase!=2) || e->id==UINT32_MAX || (phase==2 && e->id<=previous)) return 0;
            phase=2; previous=e->id; named=1; break;
        case MD_ACL_GROUP_OBJ: if (phase!=1 && phase!=2) return 0; phase=3; break;
        case MD_ACL_GROUP:
            if ((phase!=3 && phase!=4) || e->id==UINT32_MAX || (phase==4 && e->id<=previous)) return 0;
            phase=4; previous=e->id; named=1; break;
        case MD_ACL_MASK: if (phase!=3 && phase!=4) return 0; phase=5; mask=1; break;
        case MD_ACL_OTHER:
            if (phase<3 || phase>5 || (named && !mask)) return 0;
            phase=6; break;
        default:return 0;
        }
    }
    return phase==6;
}
unsigned md_acl_mode(const struct md_acl *acl,size_t size,unsigned mode) {
    mode &= ~0777U;
    for (size_t i=0;i<count(size);i++) {
        const struct md_acl_entry *e=&acl->entries[i];
        if (e->tag==MD_ACL_USER_OBJ) mode|=e->permissions<<6;
        else if (e->tag==MD_ACL_GROUP_OBJ) mode|=e->permissions<<3;
        else if (e->tag==MD_ACL_MASK) mode=(mode&~070U)|(e->permissions<<3);
        else if (e->tag==MD_ACL_OTHER) mode|=e->permissions;
    }
    return mode;
}
void md_acl_chmod(struct md_acl *acl,size_t size,unsigned mode,int inherit) {
    int mask=0;
    for(size_t i=0;i<count(size);i++) mask|=acl->entries[i].tag==MD_ACL_MASK;
    for(size_t i=0;i<count(size);i++) {
        struct md_acl_entry *e=&acl->entries[i];
        unsigned bits;
        if(e->tag==MD_ACL_USER_OBJ) bits=(mode>>6)&7;
        else if(e->tag==MD_ACL_MASK || (e->tag==MD_ACL_GROUP_OBJ && !mask)) bits=(mode>>3)&7;
        else if(e->tag==MD_ACL_OTHER) bits=mode&7;
        else continue;
        e->permissions=inherit ? e->permissions&bits : bits;
    }
}
int md_acl_permission(const struct md_acl *acl,size_t size,const struct md_identity *ids,
        uint32_t owner,uint32_t group,unsigned requested) {
    unsigned mask=7, group_seen=0, group_allowed=0, other=0;
    for(size_t i=0;i<count(size);i++) if(acl->entries[i].tag==MD_ACL_MASK) mask=acl->entries[i].permissions;
    for(size_t i=0;i<count(size);i++) {
        const struct md_acl_entry *e=&acl->entries[i];
        if(e->tag==MD_ACL_USER_OBJ && ids->uid.fs==owner)
            return (e->permissions&requested)==requested ? 0 : -EACCES;
        if(e->tag==MD_ACL_USER && ids->uid.fs==e->id)
            return (e->permissions&mask&requested)==requested ? 0 : -EACCES;
        if((e->tag==MD_ACL_GROUP_OBJ && md_identity_in_group(ids,group))
                || (e->tag==MD_ACL_GROUP && md_identity_in_group(ids,e->id))) {
            group_seen=1;
            group_allowed|=(e->permissions&mask&requested)==requested;
        }
        if(e->tag==MD_ACL_OTHER) other=e->permissions;
    }
    return group_seen ? group_allowed ? 0 : -EACCES : (other&requested)==requested ? 0 : -EACCES;
}
