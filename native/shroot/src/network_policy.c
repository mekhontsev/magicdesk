#include "network_policy.h"
#include "netlink_attributes.h"
#include <errno.h>
#include <linux/fib_rules.h>
#include <linux/rtnetlink.h>
#include <string.h>

void md_network_policy_init(struct md_network_namespace *n) {
    const unsigned tables[]={RT_TABLE_LOCAL,RT_TABLE_MAIN,RT_TABLE_DEFAULT};
    const unsigned priorities[]={0,32766,32767};
    const unsigned families[]={AF_INET,AF_INET6};
    for (unsigned i=0;i<3;++i) for (unsigned f=0;f<2;++f) if (!f || i<2)
        n->rules[n->rule_count++]=(struct md_network_rule){.source=md_ip_any(families[f]),
            .destination=md_ip_any(families[f]),.priority=priorities[i],.table=tables[i],
            .action=FR_ACT_TO_TBL,.protocol=RTPROT_KERNEL};
}
int md_network_rule_reply(struct md_network_message *out,const struct nlmsghdr *h,uint32_t port,int dump,
        const struct md_network_rule *r) {
    struct fib_rule_hdr m={.family=r->destination.family,.src_len=r->source_prefix,.dst_len=r->destination_prefix,
        .table=r->table<=255 ? r->table : RT_TABLE_UNSPEC,.action=r->action,.flags=r->flags};
    int error=md_network_message_begin(out,h,port,RTM_NEWRULE,dump,&m,sizeof(m));
    unsigned char protocol=(unsigned char)r->protocol;
    if (!error) error=md_network_message_attribute(out,FRA_PROTOCOL,&protocol,1);
    if (!error && r->priority) error=md_network_message_attribute(out,FRA_PRIORITY,&r->priority,4);
    if (!error) error=md_network_message_attribute(out,FRA_TABLE,&r->table,4);
    if (!error && r->mask) error=md_network_message_attribute(out,FRA_FWMARK,&r->mark,4);
    if (!error && r->mask) error=md_network_message_attribute(out,FRA_FWMASK,&r->mask,4);
    if (!error && r->source_prefix) error=md_network_message_attribute(out,FRA_SRC,r->source.bytes,md_ip_size(r->source));
    if (!error && r->destination_prefix) error=md_network_message_attribute(out,FRA_DST,r->destination.bytes,md_ip_size(r->destination));
    return error;
}
int md_network_rules(struct md_network_namespace *n,const struct nlmsghdr *h,uint32_t port,int dump,struct md_network_message *out) {
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct fib_rule_hdr))) return -EINVAL;
    const struct fib_rule_hdr *m=NLMSG_DATA(h);
    if (m->family!=AF_INET && m->family!=AF_INET6 && !(dump && m->family==AF_UNSPEC)) return -EAFNOSUPPORT;
    if (h->nlmsg_type==RTM_GETRULE) {
        if (!dump) return -EOPNOTSUPP;
        /* Strict admission belongs to the route gateway. Legacy dumps select
         * only family and may carry rtgenmsg, rtmsg or ifinfomsg padding. */
        for (unsigned i=0;i<n->rule_count;++i) if (!m->family || m->family==n->rules[i].destination.family) {
            int error=md_network_rule_reply(out,h,port,1,&n->rules[i]); if (error) return error;
        }
        return 0;
    }
    /* Legacy clients use rtmsg here; its protocol/scope occupy reserved bytes. */
    if (m->tos || (m->flags&~FIB_RULE_INVERT)) return -EOPNOTSUPP;
    unsigned bits=m->family==AF_INET ? 32 : 128;
    if (m->src_len>bits || m->dst_len>bits) return -EINVAL;
    struct md_network_rule value={.source=md_ip_any(m->family),.destination=md_ip_any(m->family),
        .table=m->table,.source_prefix=m->src_len,.destination_prefix=m->dst_len,.flags=m->flags,
        .action=m->action,.protocol=RTPROT_BOOT};
    struct md_netlink_cursor cursor={(const char *)m+NLMSG_ALIGN(sizeof(*m)),h->nlmsg_len-NLMSG_LENGTH(sizeof(*m))};
    struct md_netlink_attribute a; uint64_t seen=0; int error;
    while ((error=md_netlink_attribute_next(&cursor,&a))>0) {
        if (!a.type || a.type>=64) return -EOPNOTSUPP;
        if (seen&(UINT64_C(1)<<a.type)) return -EINVAL;
        seen|=UINT64_C(1)<<a.type;
        uint32_t number=0;
        const struct rtattr *wire=a.wire;
        size_t size=RTA_PAYLOAD(wire);
        if (a.type==FRA_SRC || a.type==FRA_DST) {
            struct md_ip_address *address=a.type==FRA_SRC ? &value.source : &value.destination;
            if (size!=md_ip_size(*address)) return -EINVAL;
            memcpy(address->bytes,RTA_DATA(wire),size); continue;
        }
        if (a.type==FRA_PROTOCOL) {
            if (size!=1) return -EINVAL;
            value.protocol=*(const unsigned char *)RTA_DATA(wire); continue;
        }
        if (size!=4) return -EINVAL;
        memcpy(&number,RTA_DATA(wire),4);
        switch (a.type) {
        case FRA_TABLE: value.table=number; break;
        case FRA_PRIORITY: value.priority=number; break;
        case FRA_FWMARK: value.mark=number; break;
        case FRA_FWMASK: value.mask=number; break;
        case FRA_FLOW: if (number) return -EOPNOTSUPP; break;
        case FRA_SUPPRESS_PREFIXLEN: if (number!=UINT32_MAX) return -EOPNOTSUPP; break;
        default: return -EOPNOTSUPP;
        }
    }
    if (error) return error;
    if ((seen&(UINT64_C(1)<<FRA_FWMARK)) && !(seen&(UINT64_C(1)<<FRA_FWMASK))) value.mask=UINT32_MAX;
    if (!md_ip_equal(md_ip_network(value.source,value.source_prefix),value.source)
            || !md_ip_equal(md_ip_network(value.destination,value.destination_prefix),value.destination)) return -EINVAL;
    if (value.action!=FR_ACT_TO_TBL && value.action!=FR_ACT_UNREACHABLE && value.action!=FR_ACT_PROHIBIT
            && value.action!=FR_ACT_BLACKHOLE && !(h->nlmsg_type==RTM_DELRULE && !value.action)) return -EOPNOTSUPP;
    if (h->nlmsg_type==RTM_NEWRULE && value.action==FR_ACT_TO_TBL && !value.table) return -EINVAL;
    if (!(seen&(UINT64_C(1)<<FRA_PRIORITY)) && h->nlmsg_type==RTM_NEWRULE) {
        value.priority=32765;
        for (unsigned i=0;i<n->rule_count;++i)
            if (n->rules[i].destination.family==m->family && n->rules[i].priority && n->rules[i].priority<=value.priority)
                value.priority=n->rules[i].priority-1;
    }
    for (unsigned i=0;i<n->rule_count;++i) {
        const struct md_network_rule *r=&n->rules[i];
        if (r->destination.family!=m->family) continue;
        if (h->nlmsg_type==RTM_DELRULE) {
            if (((seen&(UINT64_C(1)<<FRA_PRIORITY)) && value.priority!=r->priority)
                    || (value.table && value.table!=r->table) || (value.action && value.action!=r->action)
                    || (value.flags!=r->flags)
                    || ((seen&(UINT64_C(1)<<FRA_PROTOCOL)) && value.protocol!=r->protocol)
                    || ((seen&(UINT64_C(1)<<FRA_FWMARK)) && (value.mark!=r->mark || value.mask!=r->mask))
                    || value.source_prefix!=r->source_prefix || value.destination_prefix!=r->destination_prefix
                    || !md_ip_equal(value.source,r->source) || !md_ip_equal(value.destination,r->destination)) continue;
            memmove(n->rules+i,n->rules+i+1,(--n->rule_count-i)*sizeof(*r)); return 0;
        }
        if (!memcmp(r,&value,sizeof(value)) && (h->nlmsg_flags&NLM_F_EXCL)) return -EEXIST;
    }
    if (h->nlmsg_type==RTM_DELRULE) return -ENOENT;
    if (h->nlmsg_flags&NLM_F_REPLACE) return -EOPNOTSUPP;
    if (n->rule_count==MD_NETWORK_MAX_RULES) return -ENOSPC;
    unsigned at=0;
    while (at<n->rule_count && n->rules[at].priority<=value.priority) ++at;
    memmove(n->rules+at+1,n->rules+at,(n->rule_count-at)*sizeof(value));
    n->rules[at]=value; ++n->rule_count; return 0;
}
struct md_network_interface *md_network_policy_route(struct md_network_namespace *n,struct md_ip_address source,
        struct md_ip_address destination,uint32_t mark,unsigned output,struct md_network_route_entry *result,int *error) {
    if (error) *error=-ENETUNREACH;
    for (unsigned i=0;i<n->rule_count;++i) {
        const struct md_network_rule *r=&n->rules[i];
        if (r->destination.family!=destination.family) continue;
        int matches=(!r->source_prefix || md_ip_prefix(source,r->source,r->source_prefix))
            && md_ip_prefix(destination,r->destination,r->destination_prefix) && !((mark^r->mark)&r->mask);
        if (matches==!!(r->flags&FIB_RULE_INVERT)) continue;
        if (r->action!=FR_ACT_TO_TBL) {
            if (error) *error=r->action==FR_ACT_PROHIBIT ? -EACCES : r->action==FR_ACT_BLACKHOLE ? -EINVAL : -ENETUNREACH;
            return NULL;
        }
        struct md_network_interface *link=md_network_route_table(n,destination,output,r->table,result);
        if (link) { if (error) *error=0; return link; }
    }
    return NULL;
}
