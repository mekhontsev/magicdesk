#include "network_ipset.h"
#include "network_internal.h"
#include "network_nft_attr.h"
#include "network_filter_update.h"
#include "network_forward.h"
#include "guest_identity.h"
#include <linux/netfilter/ipset/ip_set.h>
#include <linux/netfilter/ipset/ip_set_hash.h>
#include <stdlib.h>

enum { SET_IP=1, SET_NET=2 };
#define PUT(call) do { if (!error) error=(call); } while (0)

struct md_filter_set *md_ipset_find(struct md_network_filter *f,const char *name,unsigned index) {
    if (f) for (size_t i=0;i<f->set_count;++i) {
        struct md_filter_set *s=&f->sets[i];
        if (s->ipset_kind && (name ? !strcmp(s->name,name) : s->ipset_index==index)) return s;
    }
    return NULL;
}
static unsigned references(const struct md_network_filter *f,uint64_t handle) {
    unsigned count=0;
    for (size_t i=0;i<f->rule_count;++i) for (unsigned j=0;j<f->rules[i].count;++j)
        count+=f->rules[i].expressions[j].code==MD_FILTER_IPSET && f->rules[i].expressions[j].target==handle;
    return count;
}
static const char *typename(unsigned kind) { return kind==SET_IP ? "hash:ip" : "hash:net"; }
static unsigned kind(const char *name) { return !name ? 0 : !strcmp(name,"hash:ip") ? SET_IP : !strcmp(name,"hash:net") ? SET_NET : 0; }
static unsigned revision(unsigned type) { return type==SET_NET ? 2 : 0; }
static int byte(const struct attribute *a,unsigned *value) {
    if (!a->data || a->flags || a->size!=1) return -EINVAL;
    *value=*(const unsigned char *)a->data; return 0;
}
static int u8(struct md_network_message *o,unsigned type,unsigned value) {
    unsigned char v=(unsigned char)value; return md_network_message_attribute(o,type,&v,1);
}
static int u32(struct md_network_message *o,unsigned type,unsigned value) {
    uint32_t v=htonl(value); return md_network_message_attribute(o,type|NLA_F_NET_BYTEORDER,&v,sizeof(v));
}
static int text(struct md_network_message *o,unsigned type,const char *s) {
    return md_network_message_attribute(o,type,s,strlen(s)+1);
}
static int header(struct md_network_message *o,const struct nlmsghdr *h,uint32_t port,unsigned type,int multi) {
    struct nfgenmsg nf={.version=NFNETLINK_V0};
    int error=md_network_message_begin(o,h,port,(NFNL_SUBSYS_IPSET<<8)|type,multi,&nf,sizeof(nf));
    PUT(u8(o,IPSET_ATTR_PROTOCOL,IPSET_PROTOCOL)); return error;
}
static int set_header(struct md_network_message *o,const struct md_filter_set *s) {
    int error=text(o,IPSET_ATTR_SETNAME,s->name);
    PUT(text(o,IPSET_ATTR_TYPENAME,typename(s->ipset_kind)));
    PUT(u8(o,IPSET_ATTR_FAMILY,s->ipset_family));
    PUT(u8(o,IPSET_ATTR_REVISION,s->ipset_revision)); return error;
}
static void prefixes(struct md_filter_set *s) {
    memset(s->prefixes,0,sizeof(s->prefixes));
    if (s->ipset_kind==SET_NET) for (size_t i=0;i<s->count;++i) {
        unsigned prefix=s->elements[i].key[s->key_size-1];
        s->prefixes[prefix/64]|=UINT64_C(1)<<(prefix%64);
    }
}
int md_ipset_matches(const struct md_filter_set *s,struct md_ip_address ip) {
    if (!s || s->ipset_family!=ip.family) return 0;
    if (s->ipset_kind==SET_IP) return md_filter_set_lookup(s,ip.bytes)!=NULL;
    /* Only installed prefix lengths are searched, longest first. No element
     * walk on packet admission, and relay decisions retain generation caching. */
    for (int word=2;word>=0;--word) {
        uint64_t bits=s->prefixes[word];
        while (bits) {
            unsigned bit=63-__builtin_clzll(bits),prefix=word*64+bit;
            struct md_ip_address masked=md_ip_network(ip,prefix);
            unsigned char key[20]={0}; memcpy(key,masked.bytes,md_ip_size(ip)); key[s->key_size-1]=prefix;
            const struct md_filter_element *e=md_filter_set_lookup(s,key);
            if (e) return !e->data[0];
            bits&=~(UINT64_C(1)<<bit);
        }
    }
    return 0;
}
static int emit_set(struct md_network_filter *f,const struct md_filter_set *s,
        const struct nlmsghdr *h,uint32_t port,struct md_network_message *o,unsigned flags) {
    unsigned command=NFNL_MSG_TYPE(h->nlmsg_type);
    int error=header(o,h,port,command,1);
    if (flags&IPSET_FLAG_LIST_SETNAME) { PUT(text(o,IPSET_ATTR_SETNAME,s->name)); return error; }
    PUT(set_header(o,s));
    size_t data=0,list=0;
    PUT(md_network_message_nest(o,IPSET_ATTR_DATA,&data));
    PUT(u32(o,IPSET_ATTR_HASHSIZE,s->ipset_hashsize)); PUT(u32(o,IPSET_ATTR_MAXELEM,s->ipset_max));
    PUT(u32(o,IPSET_ATTR_REFERENCES,references(f,s->handle))); PUT(u32(o,IPSET_ATTR_ELEMENTS,s->count));
    PUT(u32(o,IPSET_ATTR_MEMSIZE,sizeof(*s)+s->capacity*sizeof(*s->elements)));
    if (s->ipset_timeout) PUT(u32(o,IPSET_ATTR_TIMEOUT,0));
    PUT(md_network_message_end_nest(o,data));
    if (flags&IPSET_FLAG_LIST_HEADER) return error;
    PUT(md_network_message_nest(o,IPSET_ATTR_ADT,&list));
    for (size_t i=0;!error && i<s->count;++i) {
        size_t element=0,address=0;
        PUT(md_network_message_nest(o,IPSET_ATTR_DATA,&element));
        PUT(md_network_message_nest(o,IPSET_ATTR_IP,&address));
        unsigned v6=s->ipset_family==AF_INET6;
        PUT(md_network_message_attribute(o,(v6 ? IPSET_ATTR_IPADDR_IPV6 : IPSET_ATTR_IPADDR_IPV4)|NLA_F_NET_BYTEORDER,
            s->elements[i].key,v6 ? 16 : 4));
        PUT(md_network_message_end_nest(o,address));
        if (s->ipset_kind==SET_NET) PUT(u8(o,IPSET_ATTR_CIDR,s->elements[i].key[s->key_size-1]));
        if (s->elements[i].data[0]) PUT(u32(o,IPSET_ATTR_CADT_FLAGS,IPSET_FLAG_NOMATCH));
        if (s->ipset_timeout) PUT(u32(o,IPSET_ATTR_TIMEOUT,0));
        PUT(md_network_message_end_nest(o,element));
    }
    PUT(md_network_message_end_nest(o,list)); return error;
}
static int create(struct md_network_filter *f,const struct attribute *a,const char *name,int exist) {
    const char *type=NULL; unsigned family=0,rev=0;
    int error=string(&a[IPSET_ATTR_TYPENAME],&type);
    PUT(byte(&a[IPSET_ATTR_FAMILY],&family)); PUT(byte(&a[IPSET_ATTR_REVISION],&rev));
    unsigned k=kind(type);
    if (error) return error;
    if (!k || rev>revision(k)) return -IPSET_ERR_FIND_TYPE;
    if (family!=AF_INET && family!=AF_INET6) return -IPSET_ERR_INVALID_FAMILY;
    struct attribute b[IPSET_ATTR_CREATE_MAX+1];
    error=nested(&a[IPSET_ATTR_DATA],BIT(IPSET_ATTR_HASHSIZE)|BIT(IPSET_ATTR_MAXELEM)|BIT(IPSET_ATTR_NETMASK)
        |BIT(IPSET_ATTR_TIMEOUT)|BIT(IPSET_ATTR_CADT_FLAGS),b,IPSET_ATTR_CREATE_MAX+1);
    uint64_t hash=0,max=0,timeout=0,flags=0; unsigned mask=family==AF_INET ? 32 : 128;
    PUT(number(&b[IPSET_ATTR_HASHSIZE],&hash,0)); PUT(number(&b[IPSET_ATTR_MAXELEM],&max,0));
    PUT(number(&b[IPSET_ATTR_TIMEOUT],&timeout,0)); PUT(number(&b[IPSET_ATTR_CADT_FLAGS],&flags,0));
    if (!error && b[IPSET_ATTR_NETMASK].data) error=byte(&b[IPSET_ATTR_NETMASK],&mask);
    if (error) return error;
    if (timeout || flags || mask!=(family==AF_INET ? 32U : 128U)) return -EOPNOTSUPP;
    struct md_filter_set *s=md_ipset_find(f,name,0);
    if (s) return exist && s->ipset_kind==k && s->ipset_family==family && s->ipset_revision==rev
        && s->ipset_timeout==!!b[IPSET_ATTR_TIMEOUT].data && s->ipset_max==(max ? max : 65536) ? 0 : -EEXIST;
    unsigned index=0; while (md_ipset_find(f,NULL,index)) ++index;
    if (index>=IPSET_INVALID_ID || f->next_handle==UINT64_MAX) return -IPSET_ERR_MAX_SETS;
    error=md_filter_reserve((void **)&f->sets,&f->set_capacity,f->set_count+1,sizeof(*s),256);
    if (error) return error;
    s=&f->sets[f->set_count++];
    *s=(struct md_filter_set){.handle=f->next_handle++,.key_size=(family==AF_INET ? 4U : 16U)+(k==SET_NET),
        .ipset_kind=k,.ipset_family=family,.ipset_index=index,.ipset_revision=rev,
        .ipset_max=max ? max : 65536,.ipset_hashsize=hash ? hash : 1024,.ipset_timeout=!!b[IPSET_ATTR_TIMEOUT].data};
    strcpy(s->name,name); return 0;
}
static int element(struct md_filter_set *s,const struct attribute *a,unsigned command,int exist) {
    struct attribute b[IPSET_ATTR_ADT_MAX+1],ip[IPSET_ATTR_IPADDR_MAX+1];
    int error=nested(a,BIT(IPSET_ATTR_IP)|BIT(IPSET_ATTR_CIDR)|BIT(IPSET_ATTR_TIMEOUT)
        |BIT(IPSET_ATTR_CADT_FLAGS)|BIT(IPSET_ATTR_CADT_LINENO),b,IPSET_ATTR_ADT_MAX+1);
    unsigned address=s->ipset_family==AF_INET ? IPSET_ATTR_IPADDR_IPV4 : IPSET_ATTR_IPADDR_IPV6;
    unsigned bytes=s->ipset_family==AF_INET ? 4 : 16,prefix=bytes*8;
    PUT(nested(&b[IPSET_ATTR_IP],BIT(address),ip,IPSET_ATTR_IPADDR_MAX+1));
    if (!error && (!ip[address].data || ip[address].size!=bytes || (ip[address].flags&~NLA_F_NET_BYTEORDER))) error=-EINVAL;
    if (!error && b[IPSET_ATTR_CIDR].data) error=byte(&b[IPSET_ATTR_CIDR],&prefix);
    uint64_t timeout=0,flags=0;
    PUT(number(&b[IPSET_ATTR_TIMEOUT],&timeout,0)); PUT(number(&b[IPSET_ATTR_CADT_FLAGS],&flags,0));
    if (error) return error;
    if (timeout || (b[IPSET_ATTR_TIMEOUT].data && !s->ipset_timeout)) return -IPSET_ERR_TIMEOUT;
    if (flags&~IPSET_FLAG_NOMATCH || (flags && (s->ipset_kind!=SET_NET || s->ipset_revision<2))) return -EOPNOTSUPP;
    if (!prefix || prefix>bytes*8 || (s->ipset_kind==SET_IP && prefix!=bytes*8)) return -IPSET_ERR_INVALID_CIDR;
    struct md_ip_address value=md_ip_load(s->ipset_family,ip[address].data);
    if (s->ipset_kind==SET_IP && md_ip_unspecified(value)) return -IPSET_ERR_HASH_ELEM;
    if (command==IPSET_CMD_TEST && s->ipset_kind==SET_NET && prefix==bytes*8)
        return md_ipset_matches(s,value) ? 0 : -IPSET_ERR_EXIST;
    value=md_ip_network(value,prefix);
    struct md_filter_element e={0}; memcpy(e.key,value.bytes,bytes);
    if (s->ipset_kind==SET_NET) e.key[bytes]=prefix;
    e.data[0]=!!(flags&IPSET_FLAG_NOMATCH);
    size_t at=md_filter_set_position(s,e.key,0);
    int found=at<s->count && !memcmp(s->elements[at].key,e.key,s->key_size);
    if (command==IPSET_CMD_TEST) return found && !s->elements[at].data[0] ? 0 : -IPSET_ERR_EXIST;
    if (command==IPSET_CMD_DEL) {
        if (!found) return exist ? 0 : -IPSET_ERR_EXIST;
        memmove(s->elements+at,s->elements+at+1,(s->count-at-1)*sizeof(e)); --s->count;
    } else if (found) {
        if (!exist) return -IPSET_ERR_EXIST;
        s->elements[at]=e;
    } else {
        error=md_filter_reserve((void **)&s->elements,&s->capacity,s->count+1,sizeof(e),s->ipset_max<1024 ? s->ipset_max : 1024);
        if (error) return error;
        memmove(s->elements+at+1,s->elements+at,(s->count-at)*sizeof(e)); s->elements[at]=e; ++s->count;
    }
    return 0;
}
static int dispatch(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *o) {
    unsigned command=NFNL_MSG_TYPE(h->nlmsg_type),protocol=0;
    uint64_t allowed=BIT(IPSET_ATTR_PROTOCOL)|BIT(IPSET_ATTR_LINENO);
    switch (command) {
    case IPSET_CMD_PROTOCOL: break;
    case IPSET_CMD_TYPE: allowed|=BIT(IPSET_ATTR_TYPENAME)|BIT(IPSET_ATTR_FAMILY)|BIT(IPSET_ATTR_REVISION); break;
    case IPSET_CMD_CREATE: allowed|=BIT(IPSET_ATTR_SETNAME)|BIT(IPSET_ATTR_TYPENAME)|BIT(IPSET_ATTR_REVISION)
        |BIT(IPSET_ATTR_FAMILY)|BIT(IPSET_ATTR_DATA); break;
    case IPSET_CMD_SWAP: case IPSET_CMD_RENAME: allowed|=BIT(IPSET_ATTR_SETNAME)|BIT(IPSET_ATTR_SETNAME2); break;
    case IPSET_CMD_ADD: case IPSET_CMD_DEL: case IPSET_CMD_TEST:
        allowed|=BIT(IPSET_ATTR_SETNAME)|BIT(IPSET_ATTR_DATA)|BIT(IPSET_ATTR_ADT)|BIT(IPSET_ATTR_FLAGS); break;
    case IPSET_CMD_LIST: case IPSET_CMD_SAVE: allowed|=BIT(IPSET_ATTR_SETNAME)|BIT(IPSET_ATTR_FLAGS); break;
    case IPSET_CMD_DESTROY: case IPSET_CMD_FLUSH: case IPSET_CMD_HEADER: case IPSET_CMD_GET_BYNAME:
        allowed|=BIT(IPSET_ATTR_SETNAME); break;
    case IPSET_CMD_GET_BYINDEX: allowed|=BIT(IPSET_ATTR_INDEX); break;
    default: return -EOPNOTSUPP;
    }
    struct attribute a[IPSET_ATTR_CMD_MAX+1];
    int error=attributes(h,allowed,a,IPSET_ATTR_CMD_MAX+1);
    PUT(byte(&a[IPSET_ATTR_PROTOCOL],&protocol));
    if (error) return error;
    if (protocol<IPSET_PROTOCOL_MIN || protocol>IPSET_PROTOCOL) return -IPSET_ERR_PROTOCOL;
    const char *name=NULL,*type=NULL;
    PUT(string(&a[IPSET_ATTR_SETNAME],&name)); PUT(string(&a[IPSET_ATTR_TYPENAME],&type));
    uint64_t flags=0; PUT(number(&a[IPSET_ATTR_FLAGS],&flags,0));
    if (error) return error;
    if ((name && strlen(name)>=IPSET_MAXNAMELEN) || (type && strlen(type)>=IPSET_MAXNAMELEN)) return -EINVAL;
    if (flags&~(IPSET_FLAG_EXIST|IPSET_FLAG_LIST_SETNAME|IPSET_FLAG_LIST_HEADER)) return -EOPNOTSUPP;
    if (command==IPSET_CMD_PROTOCOL) {
        error=header(o,h,port,command,0); PUT(u8(o,IPSET_ATTR_PROTOCOL_MIN,IPSET_PROTOCOL_MIN)); return error;
    }
    if (command==IPSET_CMD_TYPE) {
        unsigned k=kind(type),family=0; PUT(byte(&a[IPSET_ATTR_FAMILY],&family));
        if (error) return error;
        if (!k) return -IPSET_ERR_FIND_TYPE;
        if (family!=AF_INET && family!=AF_INET6) return -IPSET_ERR_INVALID_FAMILY;
        error=header(o,h,port,command,0); PUT(text(o,IPSET_ATTR_TYPENAME,type));
        PUT(u8(o,IPSET_ATTR_FAMILY,family)); PUT(u8(o,IPSET_ATTR_REVISION,revision(k)));
        PUT(u8(o,IPSET_ATTR_REVISION_MIN,0)); return error;
    }
    struct md_filter_set *s=name ? md_ipset_find(f,name,0) : NULL;
    if (command==IPSET_CMD_LIST || command==IPSET_CMD_SAVE) {
        if (name && !s) return -ENOENT;
        if (f) for (size_t i=0;!error && i<f->set_count;++i)
            if (f->sets[i].ipset_kind && (!name || !strcmp(name,f->sets[i].name))) error=emit_set(f,&f->sets[i],h,port,o,flags);
        return error;
    }
    if (command==IPSET_CMD_DESTROY || command==IPSET_CMD_FLUSH) {
        if (name && !s) return command==IPSET_CMD_DESTROY && !(h->nlmsg_flags&NLM_F_EXCL) ? 0 : -ENOENT;
        if (f) for (size_t i=0;i<f->set_count;) {
            s=&f->sets[i];
            if (!s->ipset_kind || (name && strcmp(s->name,name))) { ++i; continue; }
            if (command==IPSET_CMD_DESTROY) {
                if (references(f,s->handle)) return -IPSET_ERR_BUSY;
                md_filter_set_delete(f,i);
            } else { s->count=0; prefixes(s); ++i; }
        }
        return 0;
    }
    if (command==IPSET_CMD_GET_BYINDEX) {
        uint16_t index;
        if (!a[IPSET_ATTR_INDEX].data || a[IPSET_ATTR_INDEX].size!=2 || (a[IPSET_ATTR_INDEX].flags&~NLA_F_NET_BYTEORDER)) return -EINVAL;
        memcpy(&index,a[IPSET_ATTR_INDEX].data,2); s=md_ipset_find(f,NULL,ntohs(index));
    } else if (!name) return -EINVAL;
    int exist=!(h->nlmsg_flags&NLM_F_EXCL);
    if (command==IPSET_CMD_CREATE) return create(f,a,name,exist);
    if (!s) return -ENOENT;
    if (command==IPSET_CMD_HEADER || command==IPSET_CMD_GET_BYNAME || command==IPSET_CMD_GET_BYINDEX) {
        error=header(o,h,port,command,0);
        if (command==IPSET_CMD_HEADER) PUT(set_header(o,s));
        else {
            uint16_t index=htons(s->ipset_index);
            PUT(text(o,IPSET_ATTR_SETNAME,s->name)); PUT(u8(o,IPSET_ATTR_FAMILY,s->ipset_family));
            PUT(md_network_message_attribute(o,IPSET_ATTR_INDEX|NLA_F_NET_BYTEORDER,&index,2));
        }
        return error;
    }
    if (command==IPSET_CMD_RENAME || command==IPSET_CMD_SWAP) {
        if (!type) return -EINVAL;
        struct md_filter_set *other=md_ipset_find(f,type,0);
        if (command==IPSET_CMD_RENAME) {
            if (other) return -IPSET_ERR_EXIST_SETNAME2;
            if (references(f,s->handle)) return -IPSET_ERR_REFERENCED;
            strcpy(s->name,type); return 0;
        }
        if (!other) return -ENOENT;
        if (s->ipset_kind!=other->ipset_kind || s->ipset_family!=other->ipset_family
                || s->ipset_timeout!=other->ipset_timeout) return -IPSET_ERR_TYPE_MISMATCH;
        /* References retain identity; all set configuration and contents swap. */
        struct md_filter_set left=*s,right=*other;
        *s=right; *other=left;
        strcpy(s->name,left.name); s->handle=left.handle; s->ipset_index=left.ipset_index;
        strcpy(other->name,right.name); other->handle=right.handle; other->ipset_index=right.ipset_index;
        return 0;
    }
    if (command!=IPSET_CMD_ADD && command!=IPSET_CMD_DEL && command!=IPSET_CMD_TEST) return -EOPNOTSUPP;
    if (a[IPSET_ATTR_DATA].data && a[IPSET_ATTR_ADT].data) return -EINVAL;
    if (a[IPSET_ATTR_DATA].data) {
        error=element(s,&a[IPSET_ATTR_DATA],command,exist);
        if (!error && command!=IPSET_CMD_TEST) prefixes(s);
        return error;
    }
    if (command==IPSET_CMD_TEST || !a[IPSET_ATTR_ADT].data || a[IPSET_ATTR_ADT].flags!=NLA_F_NESTED) return -EINVAL;
    struct md_netlink_cursor cursor={a[IPSET_ATTR_ADT].data,a[IPSET_ATTR_ADT].size};
    struct md_netlink_attribute item; int result;
    while ((result=md_netlink_attribute_next(&cursor,&item))>0) {
        if (item.type!=IPSET_ATTR_DATA) return -EINVAL;
        struct attribute data={item.data,item.size,item.flags};
        error=element(s,&data,command,exist); if (error) return error;
    }
    if (!result) prefixes(s);
    return result;
}
long md_network_ipset(struct md_network_namespace *n,const struct md_identity *identity,const void *data,size_t size,
        uint32_t port,int capped,void *reply,size_t capacity,const struct md_network_publication *publication) {
    const struct nlmsghdr *h=data;
    if (size<sizeof(*h) || h->nlmsg_len<NLMSG_LENGTH(sizeof(struct nfgenmsg)) || h->nlmsg_len>size
            || NLMSG_ALIGN(h->nlmsg_len)!=size) return -EINVAL;
    unsigned command=NFNL_MSG_TYPE(h->nlmsg_type);
    int writing=command==IPSET_CMD_CREATE || command==IPSET_CMD_DESTROY || command==IPSET_CMD_FLUSH
        || command==IPSET_CMD_RENAME || command==IPSET_CMD_SWAP || command==IPSET_CMD_ADD || command==IPSET_CMD_DEL;
    int dump=command==IPSET_CMD_LIST || command==IPSET_CMD_SAVE;
    struct md_network_message out={.data=reply,.capacity=capacity};
    struct md_network_forward_update *update=NULL;
    pthread_mutex_lock(&n->fabric->lock);
    int error=md_identity_capable_in(identity,n->owner,CAP_NET_ADMIN) ? 0 : -EPERM;
    const struct nfgenmsg *nf=NLMSG_DATA(h);
    if (!error && (!(h->nlmsg_flags&NLM_F_REQUEST) || nf->version!=NFNETLINK_V0)) error=-EINVAL;
    struct md_network_filter *next=NULL;
    if (!error && writing) {
        next=md_network_filter_copy(n->filter);
        if (!next) error=-ENOMEM;
        else next->generation=md_network_filter_next_generation(n->filter);
    }
    if (!error) error=dispatch(writing ? next : n->filter,h,port,&out);
    if (!error && writing) error=md_network_filter_prepare(n,next,&update);
    if (error) out.size=0;
    int encoded=md_network_message_done(&out,h,port,error,dump,capped);
    long result=encoded ? encoded : publication && out.size ? publication->publish(publication->context,out.data,out.size) : (long)out.size;
    if (!error && result>=0 && writing) { next=md_network_filter_commit(n,next,update); update=NULL; }
    md_network_forward_abort(update); md_network_filter_free(next);
    pthread_mutex_unlock(&n->fabric->lock); return result;
}
long md_ipset_sockopt(const struct md_network_filter *f,void *data,size_t size) {
    if (size<sizeof(struct ip_set_req_version)) return -EINVAL;
    struct ip_set_req_version *version=data;
    if (version->op==IP_SET_OP_VERSION) {
        if (size!=sizeof(*version)) return -EINVAL;
        version->version=IPSET_PROTOCOL; return size;
    }
    if (version->version<IPSET_PROTOCOL_MIN || version->version>IPSET_PROTOCOL) return -EPROTO;
    unsigned family=version->op==IP_SET_OP_GET_FNAME;
    if (size!=(family ? sizeof(struct ip_set_req_get_set_family) : sizeof(struct ip_set_req_get_set))) return -EINVAL;
    union ip_set_name_index *value=family ? &((struct ip_set_req_get_set_family *)data)->set : &((struct ip_set_req_get_set *)data)->set;
    struct md_filter_set *s;
    if (version->op==IP_SET_OP_GET_BYINDEX) {
        s=md_ipset_find((struct md_network_filter *)f,NULL,value->index);
        memset(value,0,sizeof(*value)); if (s) strcpy(value->name,s->name);
    } else if (family || version->op==IP_SET_OP_GET_BYNAME) {
        if (!memchr(value->name,0,sizeof(value->name))) return -EINVAL;
        s=md_ipset_find((struct md_network_filter *)f,value->name,0);
        memset(value,0,sizeof(*value)); value->index=s ? s->ipset_index : IPSET_INVALID_ID;
        if (family) ((struct ip_set_req_get_set_family *)data)->family=s ? s->ipset_family : AF_UNSPEC;
    } else return -EOPNOTSUPP;
    return size;
}
