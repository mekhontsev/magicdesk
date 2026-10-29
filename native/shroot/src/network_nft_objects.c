#include "network_nft_objects.h"
#include "network_nft_expression.h"
#include "network_nft_attr.h"
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <stdio.h>
#include <stdlib.h>

static int u32(struct md_network_message *o,unsigned type,uint32_t value) {
    value=htonl(value); return md_network_message_attribute(o,type,&value,4);
}
static int u64(struct md_network_message *o,unsigned type,uint64_t value) {
    value=htobe64(value); return md_network_message_attribute(o,type,&value,8);
}
static int text(struct md_network_message *o,unsigned type,const char *value) {
    return md_network_message_attribute(o,type,value,strlen(value)+1);
}
static int blob(const struct attribute *a,unsigned char *data,size_t *size) {
    if (a->flags || a->size>256) return -EINVAL;
    *size=a->size;
    if (a->size) memcpy(data,a->data,a->size);
    return 0;
}
static struct md_network_filter_table *owner(struct md_network_filter *f,uint64_t handle) {
    if (f) for (size_t i=0;i<f->count;++i) if (f->tables[i].handle==handle) return &f->tables[i];
    return NULL;
}
static int table_selector(struct md_network_filter *f,const struct nlmsghdr *h,const struct attribute *a,
        int dump,struct md_network_filter_table **table) {
    const char *name=NULL;
    int error=string(a,&name); if (error) return error;
    unsigned family=((const struct nfgenmsg *)NLMSG_DATA(h))->nfgen_family;
    *table=NULL;
    if (name) {
        *table=md_network_filter_find(f,family,name,0);
        return *table ? 0 : -ENOENT;
    }
    return dump ? 0 : -EINVAL;
}
static int selected(const struct nlmsghdr *h,const struct md_network_filter_table *t,
        const struct md_network_filter_table *requested) {
    unsigned family=((const struct nfgenmsg *)NLMSG_DATA(h))->nfgen_family;
    return t && (!family || t->family==family) && (!requested || requested==t);
}
static int header(struct md_network_message *o,const struct nlmsghdr *h,uint32_t port,unsigned type,
        const struct md_network_filter_table *t,uint32_t generation,int dump) {
    struct nfgenmsg nf={.nfgen_family=t->family,.version=NFNETLINK_V0,.res_id=htons(generation&0xffff)};
    return md_network_message_begin(o,h,port,(NFNL_SUBSYS_NFTABLES<<8)|type,dump,&nf,sizeof(nf));
}
static int emit_chain(struct md_network_filter *f,const struct md_filter_chain *c,const struct nlmsghdr *h,
        uint32_t port,struct md_network_message *o,unsigned type,int dump) {
    struct md_network_filter_table *t=owner(f,c->table);
    if (!t) return -ENOENT;
    int error=header(o,h,port,type,t,f->generation,dump);
#define PUT(call) do { if (!error) error=(call); } while (0)
    PUT(text(o,NFTA_CHAIN_TABLE,t->name)); PUT(text(o,NFTA_CHAIN_NAME,c->name));
    PUT(u64(o,NFTA_CHAIN_HANDLE,c->handle));
    unsigned use=0;
    for (size_t i=0;i<f->rule_count;++i) if (f->rules[i].chain==c->handle) ++use;
    PUT(u32(o,NFTA_CHAIN_USE,use));
    if (c->hook>=0) {
        PUT(text(o,NFTA_CHAIN_TYPE,c->nat ? "nat" : "filter"));
        PUT(u32(o,NFTA_CHAIN_POLICY,c->policy)); PUT(u32(o,NFTA_CHAIN_FLAGS,NFT_CHAIN_BASE));
        size_t nesting=0;
        PUT(md_network_message_nest(o,NFTA_CHAIN_HOOK,&nesting));
        PUT(u32(o,NFTA_HOOK_HOOKNUM,(unsigned)c->hook)); PUT(u32(o,NFTA_HOOK_PRIORITY,(uint32_t)c->priority));
        PUT(md_network_message_end_nest(o,nesting));
    }
    if (c->userdata_size) PUT(md_network_message_attribute(o,NFTA_CHAIN_USERDATA,c->userdata,c->userdata_size));
    if (c->counters) {
        size_t nesting=0;
        PUT(md_network_message_nest(o,NFTA_CHAIN_COUNTERS,&nesting));
        PUT(u64(o,NFTA_COUNTER_PACKETS,c->counter.packets));
        PUT(u64(o,NFTA_COUNTER_BYTES,c->counter.bytes));
        PUT(md_network_message_end_nest(o,nesting));
    }
    return error;
}
static int chains(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *o) {
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type);
    int query=operation==NFT_MSG_GETCHAIN,dump=query && (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP;
    uint64_t allowed=BIT(NFTA_CHAIN_TABLE)|BIT(NFTA_CHAIN_NAME)|BIT(NFTA_CHAIN_HANDLE);
    if (operation==NFT_MSG_DELCHAIN) allowed|=BIT(NFTA_CHAIN_USE);
    if (operation==NFT_MSG_NEWCHAIN) allowed|=BIT(NFTA_CHAIN_HOOK)|BIT(NFTA_CHAIN_TYPE)|BIT(NFTA_CHAIN_POLICY)
        |BIT(NFTA_CHAIN_FLAGS)|BIT(NFTA_CHAIN_ID)|BIT(NFTA_CHAIN_USERDATA)|BIT(NFTA_CHAIN_COUNTERS);
    struct attribute a[NFTA_CHAIN_MAX+1];
    int error=attributes(h,allowed,a,NFTA_CHAIN_MAX+1);
    struct md_network_filter_table *t=NULL;
    const char *name=NULL; uint64_t handle=0;
    if (!error) error=table_selector(f,h,&a[NFTA_CHAIN_TABLE],dump,&t);
    if (!error) error=string(&a[NFTA_CHAIN_NAME],&name);
    if (!error) error=number(&a[NFTA_CHAIN_HANDLE],&handle,1);
    if (error) return error;
    if (query) {
        unsigned found=0;
        if (f) for (size_t i=0;!error && i<f->chain_count;++i) {
            struct md_filter_chain *c=&f->chains[i];
            if (!selected(h,owner(f,c->table),t) || (name && strcmp(name,c->name)) || (handle && c->handle!=handle)) continue;
            ++found; error=emit_chain(f,c,h,port,o,NFT_MSG_NEWCHAIN,dump);
        }
        return error ? error : !dump && !found ? -ENOENT : 0;
    }
    struct md_filter_chain *existing=md_filter_chain_find(f,t->handle,name,handle,0);
    if (operation==NFT_MSG_DELCHAIN) {
        if (!existing) return -ENOENT;
        uint64_t use=0;
        error=number(&a[NFTA_CHAIN_USE],&use,0);
        if (error) return error;
        /* The caller's dumped use count may be stale; inspect the live graph. */
        for (size_t i=0;i<f->rule_count;++i) {
            const struct md_filter_rule *r=&f->rules[i];
            if (r->chain==existing->handle) {
                if (h->nlmsg_flags&NLM_F_NONREC) return -EBUSY;
                continue;
            }
            for (unsigned j=0;j<r->count;++j)
                if (r->expressions[j].code==MD_FILTER_VERDICT && r->expressions[j].target==existing->handle)
                    return -EBUSY;
        }
        for (size_t i=0;i<f->set_count;++i)
          if ((f->sets[i].flags&NFT_SET_MAP) && f->sets[i].data_type==NFT_DATA_VERDICT)
            for (size_t j=0;j<f->sets[i].count;++j)
                if (f->sets[i].elements[j].verdict.target==existing->handle) return -EBUSY;
        md_filter_chain_delete(f,(size_t)(existing-f->chains)); return 0;
    }
    if (!name || (h->nlmsg_flags&NLM_F_REPLACE)) return -EOPNOTSUPP;
    if (existing && (h->nlmsg_flags&NLM_F_EXCL)) return -EEXIST;
    struct md_filter_chain c=existing ? *existing : (struct md_filter_chain){.table=t->handle,.hook=-1,.policy=NF_ACCEPT};
    memcpy(c.name,name,strlen(name)+1);
    uint64_t flags=0,id=0,policy=c.policy;
    error=number(&a[NFTA_CHAIN_FLAGS],&flags,0);
    if (!error) error=number(&a[NFTA_CHAIN_ID],&id,0);
    if (!error && a[NFTA_CHAIN_POLICY].data) error=number(&a[NFTA_CHAIN_POLICY],&policy,0);
    if (error) return error;
    if (flags&~NFT_CHAIN_BASE || (policy!=NF_ACCEPT && policy!=NF_DROP)) return -EOPNOTSUPP;
    c.id=(uint32_t)id; c.policy=(unsigned)policy;
    if (a[NFTA_CHAIN_HOOK].data) {
        struct attribute b[NFTA_HOOK_MAX+1];
        error=nested(&a[NFTA_CHAIN_HOOK],BIT(NFTA_HOOK_HOOKNUM)|BIT(NFTA_HOOK_PRIORITY),b,NFTA_HOOK_MAX+1);
        uint32_t hook=0,priority=0;
        if (!error) error=required_number(&b[NFTA_HOOK_HOOKNUM],&hook);
        if (!error) error=required_number(&b[NFTA_HOOK_PRIORITY],&priority);
        const char *type=NULL;
        if (!error) error=string(&a[NFTA_CHAIN_TYPE],&type);
        if (error) return error;
        if (!type || (strcmp(type,"filter") && strcmp(type,"nat")) || hook>=NF_INET_NUMHOOKS
                || (t->family!=NFPROTO_INET && t->family!=NFPROTO_IPV4 && t->family!=NFPROTO_IPV6)) return -EOPNOTSUPP;
        c.nat=!strcmp(type,"nat"); c.hook=(int)hook; c.priority=(int32_t)priority;
        if (c.nat && hook==NF_INET_FORWARD) return -EOPNOTSUPP;
        if (existing && (c.hook!=existing->hook || c.nat!=existing->nat || c.priority!=existing->priority)) return -EOPNOTSUPP;
    } else if (a[NFTA_CHAIN_TYPE].data || (!existing && (flags || a[NFTA_CHAIN_POLICY].data))) return -EINVAL;
    if (a[NFTA_CHAIN_COUNTERS].data) {
        if (c.hook<0) return -EOPNOTSUPP;
        struct attribute b[NFTA_COUNTER_MAX+1];
        error=nested(&a[NFTA_CHAIN_COUNTERS],BIT(NFTA_COUNTER_PACKETS)|BIT(NFTA_COUNTER_BYTES),b,NFTA_COUNTER_MAX+1);
        if (!error && (!b[NFTA_COUNTER_PACKETS].data || !b[NFTA_COUNTER_BYTES].data)) error=-EINVAL;
        if (!error) error=number(&b[NFTA_COUNTER_PACKETS],&c.counter.packets,1);
        if (!error) error=number(&b[NFTA_COUNTER_BYTES],&c.counter.bytes,1);
        if (error) return error;
        c.counters=1;
    }
    if (a[NFTA_CHAIN_USERDATA].data) error=blob(&a[NFTA_CHAIN_USERDATA],c.userdata,&c.userdata_size);
    if (error) return error;
    if (existing) *existing=c;
    else {
        error=md_filter_reserve((void **)&f->chains,&f->chain_capacity,f->chain_count+1,sizeof(c),256);
        if (error) return error;
        if (f->next_handle==UINT64_MAX) return -ENOSPC;
        c.handle=f->next_handle++; f->chains[f->chain_count++]=c;
    }
    return h->nlmsg_flags&NLM_F_ECHO ? emit_chain(f,&c,h,port,o,NFT_MSG_NEWCHAIN,0) : 0;
}
static int emit_rule(struct md_network_filter *f,const struct md_filter_rule *r,const struct nlmsghdr *h,
        uint32_t port,struct md_network_message *o,int dump) {
    struct md_filter_chain *c=md_filter_chain_find(f,0,NULL,r->chain,0);
    if (!c) return -ENOENT;
    struct md_network_filter_table *t=owner(f,c->table);
    int error=header(o,h,port,NFT_MSG_NEWRULE,t,f->generation,dump);
    PUT(text(o,NFTA_RULE_TABLE,t->name)); PUT(text(o,NFTA_RULE_CHAIN,c->name));
    PUT(u64(o,NFTA_RULE_HANDLE,r->handle));
    PUT(md_nft_emit_expressions(f,r,o));
    if (r->userdata_size) PUT(md_network_message_attribute(o,NFTA_RULE_USERDATA,r->userdata,r->userdata_size));
    return error;
}
static int rules(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *o) {
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type);
    int query=operation==NFT_MSG_GETRULE,dump=query && (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP;
    uint64_t allowed=BIT(NFTA_RULE_TABLE)|BIT(NFTA_RULE_CHAIN)|BIT(NFTA_RULE_HANDLE)|BIT(NFTA_RULE_CHAIN_ID);
    /* iptables-nft resends the dumped rule when deleting by handle. These
     * attributes do not select a rule; the handle remains authoritative. */
    if (operation==NFT_MSG_DELRULE) allowed|=BIT(NFTA_RULE_EXPRESSIONS)|BIT(NFTA_RULE_USERDATA);
    if (operation==NFT_MSG_NEWRULE) allowed|=BIT(NFTA_RULE_EXPRESSIONS)|BIT(NFTA_RULE_USERDATA)
        |BIT(NFTA_RULE_POSITION)|BIT(NFTA_RULE_ID)|BIT(NFTA_RULE_POSITION_ID)|BIT(NFTA_RULE_COMPAT);
    struct attribute a[NFTA_RULE_MAX+1];
    int error=attributes(h,allowed,a,NFTA_RULE_MAX+1);
    struct md_network_filter_table *t=NULL;
    const char *name=NULL; uint64_t handle=0,id=0;
    if (!error) error=table_selector(f,h,&a[NFTA_RULE_TABLE],dump,&t);
    if (!error) error=string(&a[NFTA_RULE_CHAIN],&name);
    if (!error) error=number(&a[NFTA_RULE_HANDLE],&handle,1);
    if (!error) error=number(&a[NFTA_RULE_CHAIN_ID],&id,0);
    if (!error && a[NFTA_RULE_EXPRESSIONS].data && (a[NFTA_RULE_EXPRESSIONS].flags&~NLA_F_NESTED)) error=-EINVAL;
    if (error) return error;
    struct md_filter_chain *c=(name || id) && t ? md_filter_chain_find(f,t->handle,name,0,(uint32_t)id) : NULL;
    if ((name || id) && !c) return -ENOENT;
    if (query || operation==NFT_MSG_DELRULE) {
        unsigned found=0;
        if (f) for (size_t i=0;!error && i<f->rule_count;) {
            struct md_filter_rule *r=&f->rules[i];
            struct md_filter_chain *chain=md_filter_chain_find(f,0,NULL,r->chain,0);
            if (!chain || !selected(h,owner(f,chain->table),t) || (c && chain!=c) || (handle && r->handle!=handle)) { ++i; continue; }
            ++found;
            if (query) { error=emit_rule(f,r,h,port,o,dump); ++i; }
            else md_filter_rule_delete(f,i);
        }
        return error ? error : !found && handle ? -ENOENT : 0;
    }
    if (!c || !a[NFTA_RULE_EXPRESSIONS].data || (a[NFTA_RULE_EXPRESSIONS].flags&~NLA_F_NESTED)) return -EINVAL;
    if (a[NFTA_RULE_COMPAT].data) {
        /* This is xt extension validation context, not a packet predicate.
         * Expressions implement matches; supported targets validate their ABI. */
        struct attribute b[NFTA_RULE_COMPAT_MAX+1];
        uint32_t protocol=0,flags=0;
        error=nested(&a[NFTA_RULE_COMPAT],BIT(NFTA_RULE_COMPAT_PROTO)|BIT(NFTA_RULE_COMPAT_FLAGS),b,NFTA_RULE_COMPAT_MAX+1);
        if (!error) error=required_number(&b[NFTA_RULE_COMPAT_PROTO],&protocol);
        if (!error) error=required_number(&b[NFTA_RULE_COMPAT_FLAGS],&flags);
        if (!error && (protocol>UINT16_MAX || (flags&~NFT_RULE_COMPAT_F_MASK))) error=-EINVAL;
        if (error) return error;
    }
    uint64_t position=0,position_id=0,rule_id=0;
    error=number(&a[NFTA_RULE_POSITION],&position,1);
    if (!error) error=number(&a[NFTA_RULE_POSITION_ID],&position_id,0);
    if (!error) error=number(&a[NFTA_RULE_ID],&rule_id,0);
    if (error) return error;
    size_t index=f->rule_count,replace=f->rule_count;
    int located=!(position || position_id);
    for (size_t i=0;i<f->rule_count;++i) if (f->rules[i].chain==c->handle) {
        if (handle && f->rules[i].handle==handle) replace=i;
        if ((position && f->rules[i].handle==position) || (position_id && f->rules[i].id==position_id)) {
            index=i+((h->nlmsg_flags&NLM_F_APPEND)!=0); located=1;
        } else if (!(position || position_id || (h->nlmsg_flags&NLM_F_APPEND)) && index==f->rule_count) index=i;
    }
    if (!located) return -ENOENT;
    if (h->nlmsg_flags&NLM_F_REPLACE) { if (replace==f->rule_count) return -ENOENT; index=replace; }
    else if (handle) return -EOPNOTSUPP;
    struct md_filter_rule r={.chain=c->handle,.id=(uint32_t)rule_id};
    error=blob(&a[NFTA_RULE_USERDATA],r.userdata,&r.userdata_size);
    if (!error) error=md_nft_expressions(f,t->handle,&r,a[NFTA_RULE_EXPRESSIONS].data,a[NFTA_RULE_EXPRESSIONS].size);
    if (error) { free(r.wire); return error; }
    if (h->nlmsg_flags&NLM_F_REPLACE) {
        r.handle=f->rules[index].handle; free(f->rules[index].wire); f->rules[index]=r;
    } else {
        error=md_filter_reserve((void **)&f->rules,&f->rule_capacity,f->rule_count+1,sizeof(r),1024);
        if (!error && f->next_handle==UINT64_MAX) error=-ENOSPC;
        if (error) { free(r.wire); return error; }
        r.handle=f->next_handle++;
        memmove(f->rules+index+1,f->rules+index,(f->rule_count-index)*sizeof(r));
        f->rules[index]=r; ++f->rule_count;
    }
    return h->nlmsg_flags&NLM_F_ECHO ? emit_rule(f,&r,h,port,o,0) : 0;
}
static int emit_set(struct md_network_filter *f,const struct md_filter_set *s,const struct nlmsghdr *h,
        uint32_t port,struct md_network_message *o,int dump) {
    struct md_network_filter_table *t=owner(f,s->table);
    int error=header(o,h,port,NFT_MSG_NEWSET,t,f->generation,dump);
    PUT(text(o,NFTA_SET_TABLE,t->name)); PUT(text(o,NFTA_SET_NAME,s->name));
    PUT(u64(o,NFTA_SET_HANDLE,s->handle)); PUT(u32(o,NFTA_SET_FLAGS,s->flags));
    PUT(u32(o,NFTA_SET_KEY_TYPE,s->key_type)); PUT(u32(o,NFTA_SET_KEY_LEN,s->key_size));
    if (s->flags&NFT_SET_MAP) PUT(u32(o,NFTA_SET_DATA_TYPE,s->data_type));
    if (s->data_size) PUT(u32(o,NFTA_SET_DATA_LEN,s->data_size));
    if (s->userdata_size) PUT(md_network_message_attribute(o,NFTA_SET_USERDATA,s->userdata,s->userdata_size));
    return error;
}
static int sets(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *o) {
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type);
    int query=operation==NFT_MSG_GETSET,dump=query && (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP;
    uint64_t allowed=BIT(NFTA_SET_TABLE)|BIT(NFTA_SET_NAME)|BIT(NFTA_SET_HANDLE)|BIT(NFTA_SET_ID);
    if (operation==NFT_MSG_NEWSET) allowed|=BIT(NFTA_SET_FLAGS)|BIT(NFTA_SET_KEY_TYPE)|BIT(NFTA_SET_KEY_LEN)
        |BIT(NFTA_SET_POLICY)|BIT(NFTA_SET_DESC)|BIT(NFTA_SET_USERDATA)|BIT(NFTA_SET_DATA_TYPE)|BIT(NFTA_SET_DATA_LEN);
    struct attribute a[NFTA_SET_MAX+1];
    int error=attributes(h,allowed,a,NFTA_SET_MAX+1);
    struct md_network_filter_table *t=NULL;
    const char *name=NULL; uint64_t handle=0,id=0;
    if (!error) error=table_selector(f,h,&a[NFTA_SET_TABLE],dump,&t);
    if (!error) error=string(&a[NFTA_SET_NAME],&name);
    if (!error) error=number(&a[NFTA_SET_HANDLE],&handle,1);
    if (!error) error=number(&a[NFTA_SET_ID],&id,0);
    if (error) return error;
    if (query) {
        unsigned found=0;
        if (f) for (size_t i=0;!error && i<f->set_count;++i) {
            struct md_filter_set *s=&f->sets[i];
            if (!selected(h,owner(f,s->table),t) || (name && strcmp(name,s->name)) || (handle && handle!=s->handle)) continue;
            ++found; error=emit_set(f,s,h,port,o,dump);
        }
        return error ? error : !dump && !found ? -ENOENT : 0;
    }
    struct md_filter_set *s=md_filter_set_find(f,t->handle,name,handle,(uint32_t)id);
    if (operation==NFT_MSG_DELSET) {
        if (!s) return -ENOENT;
        for (size_t i=0;i<f->rule_count;++i) for (unsigned j=0;j<f->rules[i].count;++j) {
            const struct md_filter_expression *e=&f->rules[i].expressions[j];
            if (md_filter_set_expression(e) && e->target==s->handle) return -EBUSY;
        }
        md_filter_set_delete(f,(size_t)(s-f->sets)); return 0;
    }
    if (!name || handle || (h->nlmsg_flags&NLM_F_REPLACE)) return -EINVAL;
    if (s) return h->nlmsg_flags&NLM_F_EXCL ? -EEXIST : 0;
    struct md_filter_set value={.table=t->handle,.id=(uint32_t)id};
    error=required_number(&a[NFTA_SET_FLAGS],&value.flags);
    if (!error) error=required_number(&a[NFTA_SET_KEY_TYPE],&value.key_type);
    if (!error) error=required_number(&a[NFTA_SET_KEY_LEN],&value.key_size);
    if (!error) error=blob(&a[NFTA_SET_USERDATA],value.userdata,&value.userdata_size);
    if (error) return error;
    if (value.flags&~(NFT_SET_ANONYMOUS|NFT_SET_CONSTANT|NFT_SET_MAP|NFT_SET_INTERVAL)
            || !value.key_size || value.key_size>16) return -EOPNOTSUPP;
    if (value.flags&NFT_SET_MAP) {
        uint64_t length=0;
        error=required_number(&a[NFTA_SET_DATA_TYPE],&value.data_type);
        if (!error) error=number(&a[NFTA_SET_DATA_LEN],&length,0);
        if (error) return error;
        if (value.data_type==NFT_DATA_VERDICT) {
            if (length) return -EINVAL;
        } else {
            if (value.data_type>=NFT_DATA_RESERVED_MASK || !length || length>sizeof(((struct md_filter_element *)0)->data))
                return -EOPNOTSUPP;
            value.data_size=(uint32_t)length;
        }
    } else if (a[NFTA_SET_DATA_TYPE].data || a[NFTA_SET_DATA_LEN].data) return -EINVAL;
    if (a[NFTA_SET_DESC].data) {
        struct attribute b[NFTA_SET_DESC_MAX+1]; uint64_t hint=0;
        error=nested(&a[NFTA_SET_DESC],BIT(NFTA_SET_DESC_SIZE),b,NFTA_SET_DESC_MAX+1);
        if (!error) error=number(&b[NFTA_SET_DESC_SIZE],&hint,0);
        if (error) return error;
        if (hint>1024) return -ENOSPC;
    }
    uint64_t policy=0;
    error=number(&a[NFTA_SET_POLICY],&policy,0); if (error) return error;
    if (policy>1) return -EOPNOTSUPP;
    if (value.flags&NFT_SET_ANONYMOUS) snprintf(value.name,sizeof(value.name),"__set%llu",(unsigned long long)f->next_handle);
    else memcpy(value.name,name,strlen(name)+1);
    error=md_filter_reserve((void **)&f->sets,&f->set_capacity,f->set_count+1,sizeof(value),256);
    if (error) return error;
    if (f->next_handle==UINT64_MAX) return -ENOSPC;
    value.handle=f->next_handle++; f->sets[f->set_count++]=value;
    return 0;
}
static int emit_elements(struct md_network_filter *f,struct md_filter_set *s,const struct nlmsghdr *h,
        uint32_t port,struct md_network_message *o,int dump) {
    struct md_network_filter_table *t=owner(f,s->table);
    int error=header(o,h,port,NFT_MSG_NEWSETELEM,t,f->generation,dump);
    PUT(text(o,NFTA_SET_ELEM_LIST_TABLE,t->name)); PUT(text(o,NFTA_SET_ELEM_LIST_SET,s->name));
    size_t list=0;
    PUT(md_network_message_nest(o,NFTA_SET_ELEM_LIST_ELEMENTS,&list));
    for (size_t i=0;!error && i<s->count;++i) {
        size_t item=0,key=0;
        PUT(md_network_message_nest(o,NFTA_LIST_ELEM,&item));
        PUT(md_network_message_nest(o,NFTA_SET_ELEM_KEY,&key));
        PUT(md_network_message_attribute(o,NFTA_DATA_VALUE,s->elements[i].key,s->key_size));
        PUT(md_network_message_end_nest(o,key));
        if (s->elements[i].end) PUT(u32(o,NFTA_SET_ELEM_FLAGS,NFT_SET_ELEM_INTERVAL_END));
        if ((s->flags&NFT_SET_MAP) && !s->elements[i].end) {
            size_t data=0;
            PUT(md_network_message_nest(o,NFTA_SET_ELEM_DATA,&data));
            if (s->data_size) PUT(md_network_message_attribute(o,NFTA_DATA_VALUE,s->elements[i].data,s->data_size));
            else PUT(md_nft_emit_verdict(f,s->table,&s->elements[i].verdict,o));
            PUT(md_network_message_end_nest(o,data));
        }
        PUT(md_network_message_end_nest(o,item));
    }
    PUT(md_network_message_end_nest(o,list)); return error;
}
struct md_filter_set *md_nft_set(struct md_network_filter *f,uint64_t table,const char *name,uint32_t id) {
    /* Named sets survive transactions; IDs only resolve sets created in this
     * batch (including anonymous sets whose generated name is not yet known). */
    struct md_filter_set *set=name ? md_filter_set_find(f,table,name,0,0) : NULL;
    return set ? set : id ? md_filter_set_find(f,table,NULL,0,id) : NULL;
}
static int elements(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *o) {
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type);
    struct attribute a[NFTA_SET_ELEM_LIST_MAX+1];
    int error=attributes(h,BIT(NFTA_SET_ELEM_LIST_TABLE)|BIT(NFTA_SET_ELEM_LIST_SET)
        |BIT(NFTA_SET_ELEM_LIST_SET_ID)|BIT(NFTA_SET_ELEM_LIST_ELEMENTS),a,NFTA_SET_ELEM_LIST_MAX+1);
    struct md_network_filter_table *t=NULL; const char *name=NULL; uint64_t id=0;
    if (!error) error=table_selector(f,h,&a[NFTA_SET_ELEM_LIST_TABLE],0,&t);
    if (!error) error=string(&a[NFTA_SET_ELEM_LIST_SET],&name);
    if (!error) error=number(&a[NFTA_SET_ELEM_LIST_SET_ID],&id,0);
    if (error) return error;
    struct md_filter_set *s=md_nft_set(f,t->handle,name,(uint32_t)id);
    if (!s) return -ENOENT;
    if (operation==NFT_MSG_GETSETELEM) {
        if (a[NFTA_SET_ELEM_LIST_ELEMENTS].data) return -EOPNOTSUPP;
        return emit_elements(f,s,h,port,o,(h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP);
    }
    const struct attribute *list=&a[NFTA_SET_ELEM_LIST_ELEMENTS];
    if (list->flags&~NLA_F_NESTED) return -EINVAL;
    if (!list->data && operation==NFT_MSG_DELSETELEM) { s->count=0; return 0; }
    if (!list->data) return -EINVAL;
    struct md_netlink_cursor cursor={list->data,list->size};
    struct md_netlink_attribute item; int result;
    while ((result=md_netlink_attribute_next(&cursor,&item))>0) {
        /* Set elements are a nested sequence, not an attribute map. libnftnl
         * uses ordinal types here; the kernel ignores that ordinal too. */
        if (item.flags&~NLA_F_NESTED) return -EINVAL;
        struct attribute b[NFTA_SET_ELEM_MAX+1],key[NFTA_DATA_MAX+1]; uint64_t flags=0;
        error=fields(item.data,item.size,BIT(NFTA_SET_ELEM_KEY)|BIT(NFTA_SET_ELEM_FLAGS)|BIT(NFTA_SET_ELEM_DATA),b,NFTA_SET_ELEM_MAX+1);
        if (!error) error=number(&b[NFTA_SET_ELEM_FLAGS],&flags,0);
        if (!error) error=nested(&b[NFTA_SET_ELEM_KEY],BIT(NFTA_DATA_VALUE),key,NFTA_DATA_MAX+1);
        if (error) return error;
        if (flags&~NFT_SET_ELEM_INTERVAL_END) return -EOPNOTSUPP;
        if (flags && !(s->flags&NFT_SET_INTERVAL)) return -EINVAL;
        if (!key[NFTA_DATA_VALUE].data || key[NFTA_DATA_VALUE].flags || key[NFTA_DATA_VALUE].size!=s->key_size) return -EINVAL;
        struct md_filter_element element={.end=!!flags};
        memcpy(element.key,key[NFTA_DATA_VALUE].data,s->key_size);
        /* Deletion selects only the key. Clients may echo the old value,
         * which neither participates in matching nor acquires verdict edges. */
        if (operation!=NFT_MSG_DELSETELEM && (s->flags&NFT_SET_MAP) && !element.end) {
            if (s->data_size) {
                struct attribute value[NFTA_DATA_MAX+1];
                error=nested(&b[NFTA_SET_ELEM_DATA],BIT(NFTA_DATA_VALUE),value,NFTA_DATA_MAX+1);
                if (!error && (!value[NFTA_DATA_VALUE].data || value[NFTA_DATA_VALUE].flags
                        || value[NFTA_DATA_VALUE].size!=s->data_size)) error=-EINVAL;
                if (!error) memcpy(element.data,value[NFTA_DATA_VALUE].data,s->data_size);
            } else error=md_nft_verdict(f,s->table,&b[NFTA_SET_ELEM_DATA],&element.verdict);
            if (error) return error;
        } else if (operation!=NFT_MSG_DELSETELEM && b[NFTA_SET_ELEM_DATA].data) return -EINVAL;
        size_t index=md_filter_set_position(s,element.key,element.end);
        int found=index<s->count && s->elements[index].end==element.end
            && !memcmp(element.key,s->elements[index].key,s->key_size);
        if (operation==NFT_MSG_DELSETELEM) {
            if (!found) return -ENOENT;
            memmove(s->elements+index,s->elements+index+1,(s->count-index-1)*sizeof(*s->elements)); --s->count;
        } else if (found) {
            if (h->nlmsg_flags&NLM_F_EXCL) return -EEXIST;
            s->elements[index]=element;
        } else {
            error=md_filter_reserve((void **)&s->elements,&s->capacity,s->count+1,sizeof(*s->elements),1024);
            if (error) return error;
            memmove(s->elements+index+1,s->elements+index,(s->count-index)*sizeof(*s->elements));
            s->elements[index]=element; ++s->count;
        }
    }
    return result;
}
int md_nft_objects(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *o) {
    switch (NFNL_MSG_TYPE(h->nlmsg_type)) {
    case NFT_MSG_NEWCHAIN: case NFT_MSG_DELCHAIN: case NFT_MSG_GETCHAIN: return chains(f,h,port,o);
    case NFT_MSG_NEWRULE: case NFT_MSG_DELRULE: case NFT_MSG_GETRULE: return rules(f,h,port,o);
    case NFT_MSG_NEWSET: case NFT_MSG_DELSET: case NFT_MSG_GETSET: return sets(f,h,port,o);
    case NFT_MSG_NEWSETELEM: case NFT_MSG_DELSETELEM: case NFT_MSG_GETSETELEM: return elements(f,h,port,o);
    default: return -EOPNOTSUPP;
    }
}
