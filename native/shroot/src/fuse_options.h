#ifndef MD_FUSE_OPTIONS_H
#define MD_FUSE_OPTIONS_H
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <limits.h>
#include <sys/stat.h>
enum { MD_FUSE_IO_SIZE=16384 };
struct md_fuse_options {
    int descriptor;
    uint32_t rootmode,uid,gid,max_read;
    int allow_other,default_permissions;
};
/* Shared with the freestanding syscall adapter: no allocation or libc calls. */
static inline int md_fuse_option_key(const char *text,size_t length,const char *key) {
    for (size_t i=0;i<length;++i) if (!key[i] || text[i]!=key[i]) return 0;
    return !key[length];
}
static inline int md_fuse_options_parse(const char *text,struct md_fuse_options *out) {
    *out=(struct md_fuse_options){.descriptor=-1,.max_read=MD_FUSE_IO_SIZE};
    unsigned seen=0;
    if (!text || !*text) return -EINVAL;
    for (const char *at=text;*at;) {
        const char *end=at,*equal=NULL;
        while (*end && *end!=',') { if (*end=='=' && !equal) equal=end; ++end; }
        size_t length=(size_t)(end-at);
        if (!equal) {
            if (md_fuse_option_key(at,length,"allow_other")) out->allow_other=1;
            else if (md_fuse_option_key(at,length,"default_permissions")) out->default_permissions=1;
            else return -EINVAL;
        } else {
            size_t key=(size_t)(equal-at); unsigned field;
            if (md_fuse_option_key(at,key,"fd")) field=0;
            else if (md_fuse_option_key(at,key,"rootmode")) field=1;
            else if (md_fuse_option_key(at,key,"user_id")) field=2;
            else if (md_fuse_option_key(at,key,"group_id")) field=3;
            else if (md_fuse_option_key(at,key,"max_read")) field=4;
            else return -EINVAL;
            unsigned base=field==1 ? 8 : 10; uint32_t value=0;
            if (equal+1==end) return -EINVAL;
            for (const char *p=equal+1;p<end;++p) {
                unsigned digit=(unsigned char)*p-'0';
                if (digit>=base || value>(UINT32_MAX-digit)/base) return -EINVAL;
                value=value*base+digit;
            }
            switch (field) {
            case 0: if (value>INT_MAX) return -EINVAL; out->descriptor=(int)value; break;
            case 1: if ((value&S_IFMT)!=S_IFDIR || value&~(S_IFMT|07777U)) return -EINVAL; out->rootmode=value; break;
            case 2: out->uid=value; break;
            case 3: out->gid=value; break;
            case 4: out->max_read=value && value<MD_FUSE_IO_SIZE ? value : MD_FUSE_IO_SIZE; break;
            }
            seen|=1U<<field;
        }
        at=*end ? end+1 : end;
        if (*end && !*at) return -EINVAL;
    }
    return (seen&15)==15 ? 0 : -EINVAL;
}
struct md_fuse_mount {
    const char *type,*source;
    struct md_fuse_options options;
};
enum { MD_FUSE_MOUNT_MAX=3*PATH_MAX };
/* Captured mount payload: type, source, options, each NUL-terminated. Source
 * is a filesystem label, not a path operand or an authority for the device FD. */
static inline int md_fuse_mount_parse(const void *data,size_t size,struct md_fuse_mount *out) {
    if (!data || !size || size>MD_FUSE_MOUNT_MAX) return -EINVAL;
    const char *at=data,*fields[3];
    size_t lengths[3];
    for (unsigned i=0;i<3;++i) {
        fields[i]=at;
        size_t n=0;
        while (n<size && n<PATH_MAX && at[n]) ++n;
        if (n==size || n==PATH_MAX) return -EINVAL;
        lengths[i]=n; at+=n+1; size-=n+1;
    }
    if (size || !(md_fuse_option_key(fields[0],lengths[0],"fuse")
            || (lengths[0]>5 && fields[0][0]=='f' && fields[0][1]=='u'
                && fields[0][2]=='s' && fields[0][3]=='e' && fields[0][4]=='.'))) return -EINVAL;
    out->type=fields[0]; out->source=fields[1];
    return md_fuse_options_parse(fields[2],&out->options);
}
#endif
