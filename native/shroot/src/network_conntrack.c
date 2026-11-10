#include "network_conntrack.h"
#include "network_internal.h"
#include "network_forward.h"
#include "network_nft_attr.h"
#include "guest_identity.h"
#include <linux/netfilter/nfnetlink_conntrack.h>
#include <stdlib.h>

struct query {
    const struct nlmsghdr *request;
    struct md_network_message *out;
    uint32_t port, id;
    unsigned family, remove, dump, has_id, has_original, has_reply;
    struct md_connection_tuple original, reply;
    uint64_t *removed;
    size_t count, capacity;
};
static int bytes(const struct attribute *a,void *out,size_t size) {
    if (!a->data || a->size!=size || (a->flags&~NLA_F_NET_BYTEORDER)) return -EINVAL;
    memcpy(out,a->data,size); return 0;
}
static int parse_tuple(const struct attribute *a,unsigned family,struct md_connection_tuple *out) {
    struct attribute tuple[CTA_TUPLE_MAX+1],ip[CTA_IP_MAX+1],proto[CTA_PROTO_MAX+1];
    unsigned source=family==AF_INET ? CTA_IP_V4_SRC : CTA_IP_V6_SRC;
    unsigned destination=family==AF_INET ? CTA_IP_V4_DST : CTA_IP_V6_DST;
    size_t size=family==AF_INET ? 4 : 16;
    int error=nested(a,BIT(CTA_TUPLE_IP)|BIT(CTA_TUPLE_PROTO),tuple,CTA_TUPLE_MAX+1);
    if (!error) error=nested(&tuple[CTA_TUPLE_IP],BIT(source)|BIT(destination),ip,CTA_IP_MAX+1);
    if (!error) error=nested(&tuple[CTA_TUPLE_PROTO],BIT(CTA_PROTO_NUM)|BIT(CTA_PROTO_SRC_PORT)|BIT(CTA_PROTO_DST_PORT),proto,CTA_PROTO_MAX+1);
    uint8_t protocol=0;
    out->source=out->destination=md_ip_any(family);
    if (!error) error=bytes(&ip[source],out->source.bytes,size);
    if (!error) error=bytes(&ip[destination],out->destination.bytes,size);
    if (!error) error=bytes(&proto[CTA_PROTO_NUM],&protocol,1);
    if (!error && protocol!=IPPROTO_TCP && protocol!=IPPROTO_UDP) error=-EOPNOTSUPP;
    if (!error) error=bytes(&proto[CTA_PROTO_SRC_PORT],&out->source_port,2);
    if (!error) error=bytes(&proto[CTA_PROTO_DST_PORT],&out->destination_port,2);
    out->protocol=protocol;
    return error;
}
static int emit_tuple(struct md_network_message *out,unsigned kind,const struct md_connection_tuple *t) {
    unsigned source=t->source.family==AF_INET ? CTA_IP_V4_SRC : CTA_IP_V6_SRC;
    unsigned destination=t->source.family==AF_INET ? CTA_IP_V4_DST : CTA_IP_V6_DST;
    size_t size=t->source.family==AF_INET ? 4 : 16;
    size_t tuple,ip,proto;
    uint8_t protocol=t->protocol;
    int error=md_network_message_nest(out,kind,&tuple);
    if (!error) error=md_network_message_nest(out,CTA_TUPLE_IP,&ip);
    if (!error) error=md_network_message_attribute(out,source,t->source.bytes,size);
    if (!error) error=md_network_message_attribute(out,destination,t->destination.bytes,size);
    if (!error) error=md_network_message_end_nest(out,ip);
    if (!error) error=md_network_message_nest(out,CTA_TUPLE_PROTO,&proto);
    if (!error) error=md_network_message_attribute(out,CTA_PROTO_NUM,&protocol,1);
    if (!error) error=md_network_message_attribute(out,CTA_PROTO_SRC_PORT,&t->source_port,2);
    if (!error) error=md_network_message_attribute(out,CTA_PROTO_DST_PORT,&t->destination_port,2);
    if (!error) error=md_network_message_end_nest(out,proto);
    if (!error) error=md_network_message_end_nest(out,tuple);
    return error;
}
static int emit_number(struct md_network_message *out,unsigned key,uint32_t number) {
    number=htonl(number); return md_network_message_attribute(out,key,&number,4);
}
static int same_tuple(const struct md_connection_tuple *a,const struct md_connection_tuple *b) {
    return a->protocol==b->protocol && a->source_port==b->source_port && a->destination_port==b->destination_port
        && md_ip_equal(a->source,b->source) && md_ip_equal(a->destination,b->destination);
}
static int visit(void *context,const struct md_connection *entry) {
    struct query *q=context;
    if ((q->family && entry->original.source.family!=q->family)
            || (q->has_id && (uint32_t)entry->id!=q->id)
            || (q->has_original && !same_tuple(&q->original,&entry->original))
            || (q->has_reply && !same_tuple(&q->reply,&entry->reply))) return 0;
    if (q->remove) {
        /* The relay owns transport lifetime separately from TCP tracking. */
        int error=md_filter_reserve((void **)&q->removed,&q->capacity,q->count+1,sizeof(*q->removed),
            MD_NETWORK_FORWARD_TCP_LIMIT+MD_NETWORK_FORWARD_UDP_LIMIT);
        if (error) return error;
        q->removed[q->count++]=entry->id; return 0;
    }
    ++q->count;
    struct nfgenmsg header={.nfgen_family=entry->original.source.family,.version=NFNETLINK_V0};
    int error=md_network_message_begin(q->out,q->request,q->port,
        (NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_NEW,q->dump,&header,sizeof(header));
    if (!error) error=emit_tuple(q->out,CTA_TUPLE_ORIG,&entry->original);
    if (!error) error=emit_tuple(q->out,CTA_TUPLE_REPLY,&entry->reply);
    if (!error) error=emit_number(q->out,CTA_STATUS,entry->status);
    if (!error) error=emit_number(q->out,CTA_MARK,entry->mark);
    if (!error) error=emit_number(q->out,CTA_TIMEOUT,entry->timeout);
    if (!error) error=emit_number(q->out,CTA_ID,(uint32_t)entry->id);
    return error;
}
long md_network_conntrack(struct md_network_namespace *n,const struct md_identity *identity,
        const void *data,size_t size,uint32_t port,int capped,void *reply,size_t capacity,
        const struct md_network_publication *publication) {
    const struct nlmsghdr *h=data;
    if (size<sizeof(*h) || h->nlmsg_len<sizeof(*h) || h->nlmsg_len>size) return -EINVAL;
    struct md_network_message out={.data=reply,.capacity=capacity};
    struct query q={.request=h,.out=&out,.port=port};
    int error=0;
    unsigned operation=NFNL_MSG_TYPE(h->nlmsg_type);
    if (NLMSG_ALIGN(h->nlmsg_len)!=size || h->nlmsg_len<NLMSG_LENGTH(sizeof(struct nfgenmsg))
            || !(h->nlmsg_flags&NLM_F_REQUEST)) error=-EINVAL;
    if (!error && !md_identity_capable_in(identity,n->owner,CAP_NET_ADMIN)) error=-EPERM;
    if (!error && operation!=IPCTNL_MSG_CT_GET && operation!=IPCTNL_MSG_CT_DELETE) error=-EOPNOTSUPP;
    if (!error) {
        const struct nfgenmsg *header=NLMSG_DATA(h);
        q.family=header->nfgen_family;
        q.remove=operation==IPCTNL_MSG_CT_DELETE;
        q.dump=!q.remove && (h->nlmsg_flags&NLM_F_DUMP)==NLM_F_DUMP;
        if (header->version!=NFNETLINK_V0) error=-EPROTONOSUPPORT;
        else if (header->res_id) error=-EOPNOTSUPP;
        else if (q.family!=AF_UNSPEC && q.family!=AF_INET && q.family!=AF_INET6) error=-EAFNOSUPPORT;
    }
    struct attribute a[CTA_MAX+1];
    if (!error) error=attributes(h,BIT(CTA_TUPLE_ORIG)|BIT(CTA_TUPLE_REPLY)|BIT(CTA_STATUS)
        |BIT(CTA_TIMEOUT)|BIT(CTA_MARK)|BIT(CTA_ID),a,CTA_MAX+1);
    if (!error) {
        q.has_original=!!a[CTA_TUPLE_ORIG].data; q.has_reply=!!a[CTA_TUPLE_REPLY].data; q.has_id=!!a[CTA_ID].data;
        if ((q.has_original || q.has_reply) && !q.family) error=-EINVAL;
        if (!error && q.has_original) error=parse_tuple(&a[CTA_TUPLE_ORIG],q.family,&q.original);
        if (!error && q.has_reply) error=parse_tuple(&a[CTA_TUPLE_REPLY],q.family,&q.reply);
        if (!error && q.has_id) error=required_number(&a[CTA_ID],&q.id);
        for (unsigned key=0;!error && key<CTA_MAX+1;++key)
            if (key==CTA_MARK || key==CTA_TIMEOUT || key==CTA_STATUS) {
                uint64_t value;
                if (a[key].data && (!q.remove || (!q.has_original && !q.has_reply))) error=-EOPNOTSUPP;
                else error=number(&a[key],&value,0);
            }
        if (!error && !q.remove && !q.dump && !q.has_original && !q.has_reply) error=-EINVAL;
        if (!error && q.has_id && !q.has_original && !q.has_reply) error=-EINVAL;
        if (!error && q.dump && (q.has_original || q.has_reply)) error=-EOPNOTSUPP;
    }
    pthread_mutex_lock(&n->fabric->lock);
    if (!error) error=md_network_forward_connections(n,visit,&q);
    if (!error && !q.dump && !q.count && (q.has_original || q.has_reply)) error=-ENOENT;
    if (error) out.size=0;
    int encoded=md_network_message_done(&out,h,port,error,q.dump&&!error,capped);
    long result=encoded ? encoded : publication && out.size
        ? publication->publish(publication->context,out.data,out.size) : (long)out.size;
    if (!error && result>=0 && q.remove) md_network_forward_forget(n,q.removed,q.count);
    pthread_mutex_unlock(&n->fabric->lock);
    free(q.removed);
    return result;
}
