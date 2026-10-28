#include "network_nft.h"
#include "network_filter.h"
#include "network_filter_update.h"
#include "network_internal.h"
#include "network_nft_attr.h"
#include "network_nft_objects.h"
#include "network_nft_expression.h"
#include "network_forward.h"
#include "network_endpoint.h"
#include "guest_identity.h"
#include <errno.h>
#include <endian.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <linux/netfilter/nf_tables_compat.h>
#include <linux/netfilter/nfnetlink.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>

static int family_supported(unsigned family) {
    return family==NFPROTO_INET || family==NFPROTO_IPV4 || family==NFPROTO_IPV6;
}
static int emit_table(struct md_network_message *out,const struct nlmsghdr *request,uint32_t port,
        const struct md_network_filter_table *table,unsigned type,int dump,uint32_t generation) {
    struct nfgenmsg header={.nfgen_family=table->family,.version=NFNETLINK_V0,.res_id=htons(generation&0xffff)};
    int error=md_network_message_begin(out,request,port,(NFNL_SUBSYS_NFTABLES<<8)|type,dump,&header,sizeof(header));
    uint32_t zero=0; uint64_t handle=htobe64(table->handle);
    if (!error) error=md_network_message_attribute(out,NFTA_TABLE_NAME,table->name,strlen(table->name)+1);
    if (!error) error=md_network_message_attribute(out,NFTA_TABLE_FLAGS,&zero,sizeof(zero));
    if (!error) error=md_network_message_attribute(out,NFTA_TABLE_USE,&zero,sizeof(zero));
    if (!error) error=md_network_message_attribute(out,NFTA_TABLE_HANDLE,&handle,sizeof(handle));
    if (!error && table->userdata_size) error=md_network_message_attribute(out,NFTA_TABLE_USERDATA,table->userdata,table->userdata_size);
    return error;
}
static int tables(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *out) {
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type);
    const struct nfgenmsg *header=NLMSG_DATA(h);
    unsigned family=header->nfgen_family;
    int query=operation==NFT_MSG_GETTABLE,dump=query && (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP;
    uint64_t allowed=BIT(NFTA_TABLE_NAME)|BIT(NFTA_TABLE_HANDLE)|BIT(NFTA_TABLE_PAD);
    if (operation==NFT_MSG_NEWTABLE) allowed|=BIT(NFTA_TABLE_FLAGS)|BIT(NFTA_TABLE_USERDATA);
    struct attribute attrs[NFTA_TABLE_MAX+1];
    int error=attributes(h,allowed,attrs,NFTA_TABLE_MAX+1);
    const char *name=NULL; uint64_t handle=0,flags=0;
    if (!error) error=string(&attrs[NFTA_TABLE_NAME],&name);
    if (!error) error=number(&attrs[NFTA_TABLE_HANDLE],&handle,1);
    if (!error && attrs[NFTA_TABLE_PAD].size) error=-EINVAL;
    if (!error && name && handle) error=-EINVAL;
    if (error) return error;
    if (family && !family_supported(family)) return -EAFNOSUPPORT;
    if (query) {
        if (!dump && (!family || (!name && !handle))) return -EINVAL;
        int found=0;
        if (f) for (size_t i=0;!error && i<f->count;++i) {
            const struct md_network_filter_table *t=&f->tables[i];
            if ((family && t->family!=family) || (name && strcmp(name,t->name)) || (handle && handle!=t->handle)) continue;
            ++found; error=emit_table(out,h,port,t,NFT_MSG_NEWTABLE,dump,md_network_filter_generation(f));
        }
        return error ? error : !dump && !found ? -ENOENT : 0;
    }
    if (operation==NFT_MSG_NEWTABLE) {
        if (!family || !name || handle) return -EINVAL;
        error=number(&attrs[NFTA_TABLE_FLAGS],&flags,0);
        if (error) return error;
        if (flags || (h->nlmsg_flags&NLM_F_REPLACE)) return -EOPNOTSUPP;
        if (attrs[NFTA_TABLE_USERDATA].flags || attrs[NFTA_TABLE_USERDATA].size>256) return -EINVAL;
        struct md_network_filter_table *table=md_network_filter_find(f,family,name,0);
        if (table) {
            if (h->nlmsg_flags&NLM_F_EXCL) return -EEXIST;
        } else {
            error=md_network_filter_add(f,family,name,attrs[NFTA_TABLE_USERDATA].data,attrs[NFTA_TABLE_USERDATA].size);
            if (error) return error;
            table=md_network_filter_find(f,family,name,0);
        }
        return (h->nlmsg_flags&NLM_F_ECHO) ? emit_table(out,h,port,table,NFT_MSG_NEWTABLE,0,f->generation) : 0;
    }
    if ((name || handle) && (!family || !md_network_filter_find(f,family,name,handle))) return -ENOENT;
    if (h->nlmsg_flags&NLM_F_ECHO) {
        for (size_t i=0;!error && i<f->count;++i) {
            struct md_network_filter_table *t=&f->tables[i];
            if ((!family || t->family==family) && (!name || !strcmp(t->name,name)) && (!handle || t->handle==handle))
                error=emit_table(out,h,port,t,NFT_MSG_DELTABLE,0,f->generation);
        }
    }
    if (!error) md_network_filter_delete(f,family,name,handle);
    return error;
}
static int query(unsigned type) {
    switch (type) {
    case NFT_MSG_GETGEN: case NFT_MSG_GETTABLE: case NFT_MSG_GETCHAIN: case NFT_MSG_GETRULE:
    case NFT_MSG_GETSET: case NFT_MSG_GETSETELEM: case NFT_MSG_GETOBJ: case NFT_MSG_GETFLOWTABLE: return 1;
    default: return 0;
    }
}
static int empty_dump(struct md_network_filter *f,const struct nlmsghdr *h) {
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type),table_key;
    switch (operation) {
    case NFT_MSG_GETOBJ: table_key=NFTA_OBJ_TABLE; break;
    case NFT_MSG_GETFLOWTABLE: table_key=NFTA_FLOWTABLE_TABLE; break;
    default: return -EOPNOTSUPP;
    }
    /* These catalogues are empty because unsupported creation is rejected,
     * not because installed but unenforced rules are hidden from the caller. */
    struct attribute attrs[32];
    int error=attributes(h,BIT(table_key),attrs,32);
    const char *table=NULL;
    if (!error) error=string(&attrs[table_key],&table);
    if (error) return error;
    unsigned family=((const struct nfgenmsg *)NLMSG_DATA(h))->nfgen_family;
    if (family && !family_supported(family)) return -EAFNOSUPPORT;
    if (table && (!family || !md_network_filter_find(f,family,table,0))) return -ENOENT;
    return (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP ? 0 : -EOPNOTSUPP;
}
static int compat_query(const struct nlmsghdr *h,uint32_t port,struct md_network_message *out) {
    if (NFNL_MSG_TYPE(h->nlmsg_type)!=NFNL_MSG_COMPAT_GET) return -EOPNOTSUPP;
    struct attribute a[NFTA_COMPAT_MAX+1];
    int error=attributes(h,BIT(NFTA_COMPAT_NAME)|BIT(NFTA_COMPAT_REV)|BIT(NFTA_COMPAT_TYPE),a,NFTA_COMPAT_MAX+1);
    const char *name=NULL; uint32_t revision=0,type=0;
    if (!error) error=string(&a[NFTA_COMPAT_NAME],&name);
    if (!error) error=required_number(&a[NFTA_COMPAT_REV],&revision);
    if (!error) error=required_number(&a[NFTA_COMPAT_TYPE],&type);
    if (!error && (!name || strlen(name)>=NFT_COMPAT_NAME_MAX || type>1)) error=-EINVAL;
    const struct nfgenmsg *nf=NLMSG_DATA(h);
    if (!error) error=md_nft_compat_revision(nf->nfgen_family,name,revision,type);
    if (error) return error;
    struct nfgenmsg reply={.nfgen_family=nf->nfgen_family,.version=NFNETLINK_V0};
    error=md_network_message_begin(out,h,port,h->nlmsg_type,1,&reply,sizeof(reply));
    if (!error) error=md_network_message_attribute(out,NFTA_COMPAT_NAME,name,strlen(name)+1);
    revision=htonl(revision); type=htonl(type);
    if (!error) error=md_network_message_attribute(out,NFTA_COMPAT_REV,&revision,sizeof(revision));
    if (!error) error=md_network_message_attribute(out,NFTA_COMPAT_TYPE,&type,sizeof(type));
    return error;
}
static int dispatch(struct md_network_filter *f,const struct nlmsghdr *h,uint32_t port,struct md_network_message *out) {
    if (NFNL_SUBSYS_ID(h->nlmsg_type)!=NFNL_SUBSYS_NFTABLES) return -EOPNOTSUPP;
    switch (NFNL_MSG_TYPE(h->nlmsg_type)) {
    case NFT_MSG_GETTABLE: case NFT_MSG_NEWTABLE: case NFT_MSG_DELTABLE: return tables(f,h,port,out);
    case NFT_MSG_GETGEN: {
        if (h->nlmsg_len!=NLMSG_LENGTH(sizeof(struct nfgenmsg))) return -EINVAL;
        struct nfgenmsg gen={.version=NFNETLINK_V0,
            .res_id=htons(md_network_filter_generation(f)&0xffff)};
        uint32_t generation=htonl(md_network_filter_generation(f));
        int error=md_network_message_begin(out,h,port,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWGEN,0,&gen,sizeof(gen));
        if (!error) error=md_network_message_attribute(out,NFTA_GEN_ID,&generation,sizeof(generation));
        return error;
    }
    case NFT_MSG_GETOBJ: case NFT_MSG_GETFLOWTABLE:
        return empty_dump(f,h);
    default: return md_nft_objects(f,h,port,out);
    }
}
static void discard_echoes(struct md_network_message *out) {
    long remaining=(long)out->size;
    size_t kept=0;
    const struct nlmsghdr *h=(const void *)out->data;
    while (NLMSG_OK(h,remaining)) {
        size_t size=NLMSG_ALIGN(h->nlmsg_len);
        if (h->nlmsg_type==NLMSG_ERROR) {
            memmove((char *)out->data+kept,h,size); kept+=size;
        }
        h=(const void *)((const char *)h+size); remaining-=(long)size;
    }
    out->size=kept;
}
long md_network_nft(struct md_network_namespace *n,const struct md_identity *identity,const void *data,size_t size,
        uint32_t port,int capped,void *reply,size_t capacity,const struct md_network_publication *publication) {
    if (size<sizeof(struct nlmsghdr) || size>INT32_MAX) return -EINVAL;
    struct md_network_message out={.data=reply,.capacity=capacity};
    const struct nlmsghdr *h=data,*failed=h,*first_operation=h,*batch_end=NULL;
    long remaining=(long)size;
    int error=0,batch=h->nlmsg_type==NFNL_MSG_BATCH_BEGIN,ended=0,rejected=0;
    struct md_network_filter *next=NULL;
    struct md_network_forward_update *forward=NULL;
    pthread_mutex_lock(&n->fabric->lock);
    for (;NLMSG_OK(h,remaining);h=NLMSG_NEXT(h,remaining)) {
        failed=h;
        if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct nfgenmsg)) || !(h->nlmsg_flags&NLM_F_REQUEST)) { error=-EINVAL; break; }
        const struct nfgenmsg *header=NLMSG_DATA(h);
        if (header->version!=NFNETLINK_V0) { error=-EPROTONOSUPPORT; break; }
        if (!md_identity_capable_in(identity,n->owner,CAP_NET_ADMIN)) { error=-EPERM; break; }
        if (h->nlmsg_type==NFNL_MSG_BATCH_BEGIN) {
            if (h!=data || !batch || header->nfgen_family!=AF_UNSPEC
                    || ntohs(header->res_id)!=NFNL_SUBSYS_NFTABLES) { error=-EINVAL; break; }
            struct attribute attrs[NFNL_BATCH_MAX+1]; uint64_t generation=0;
            error=attributes(h,BIT(NFNL_BATCH_GENID),attrs,NFNL_BATCH_MAX+1);
            if (!error) error=number(&attrs[NFNL_BATCH_GENID],&generation,0);
            if (!error && generation && generation!=md_network_filter_generation(n->filter)) error=-ERESTART;
            if (error) break;
            next=md_network_filter_copy(n->filter);
            if (!next) { error=-ENOMEM; break; }
            next->generation=md_network_filter_next_generation(n->filter);
            error=md_network_message_done(&out,h,port,0,0,capped);
            if (error) break;
            continue;
        }
        if (h->nlmsg_type==NFNL_MSG_BATCH_END) {
            if (!batch || ended || h->nlmsg_len!=NLMSG_LENGTH(sizeof(struct nfgenmsg))
                    || remaining!=(long)NLMSG_ALIGN(h->nlmsg_len) || header->nfgen_family!=AF_UNSPEC
                    || (header->res_id && ntohs(header->res_id)!=NFNL_SUBSYS_NFTABLES)) { error=-EINVAL; break; }
            ended=1;
            batch_end=h;
            continue;
        }
        if (first_operation==data) first_operation=h;
        size_t before=out.size;
        if (!batch && NFNL_SUBSYS_ID(h->nlmsg_type)==NFNL_SUBSYS_NFT_COMPAT) error=compat_query(h,port,&out);
        else if (NFNL_SUBSYS_ID(h->nlmsg_type)!=NFNL_SUBSYS_NFTABLES) error=-EOPNOTSUPP;
        else if (batch==query(NFNL_MSG_TYPE(h->nlmsg_type))) error=-EINVAL;
        else error=dispatch(batch ? next : n->filter,h,port,&out);
        if (error && !batch) break;
        int operation_error=error;
        if (operation_error) { rejected=1; out.size=before; }
        int dump=!batch && (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP;
        error=md_network_message_done(&out,h,port,operation_error,dump,capped);
        if (error) break;
    }
    if (!error && (remaining || (batch && !ended))) { error=-EINVAL; failed=data; }
    if (!error && batch && !rejected) {
        /* Anonymous sets have the lifetime of their installed lookup rules. */
        for (size_t i=0;i<next->set_count;) {
            int used=0;
            for (size_t j=0;j<next->rule_count && !used;++j)
                for (unsigned k=0;k<next->rules[j].count;++k) {
                    const struct md_filter_expression *e=&next->rules[j].expressions[k];
                    if (md_filter_set_expression(e) && e->target==next->sets[i].handle) used=1;
                }
            if (!used && (next->sets[i].flags&NFT_SET_ANONYMOUS)) md_filter_set_delete(next,i); else ++i;
        }
        error=md_network_filter_prepare(n,next,&forward);
        if (error) {
            rejected=1;
            discard_echoes(&out);
            error=md_network_message_done(&out,first_operation,port,error,0,capped);
            failed=first_operation;
        } else error=md_network_message_done(&out,batch_end,port,0,0,capped);
    }
    /* A batch failure rolls back mutations, not the individual acknowledgments.
     * Callers may wait on every sequence before inspecting the batch result. */
    if (rejected) discard_echoes(&out);
    if (error) {
        out.size=0;
        /* A malformed outer header cannot be echoed safely. */
        if (failed->nlmsg_len<sizeof(*failed) || failed->nlmsg_len>size-(size_t)((const char *)failed-(const char *)data)) {
            md_network_filter_free(next); pthread_mutex_unlock(&n->fabric->lock); return error;
        }
        int encoded=md_network_message_done(&out,failed,port,error,0,capped);
        if (encoded) { md_network_filter_free(next); pthread_mutex_unlock(&n->fabric->lock); return encoded; }
    }
    long result=publication && out.size ? publication->publish(publication->context,out.data,out.size) : (long)out.size;
    if (!error && !rejected && result>=0 && batch) {
        next=md_network_filter_commit(n,next,forward); forward=NULL;
    }
    md_network_forward_abort(forward);
    md_network_filter_free(next);
    pthread_mutex_unlock(&n->fabric->lock);
    return result;
}
