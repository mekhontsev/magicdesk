#include "network_nexthop.h"
#include "netlink_attributes.h"
#include <errno.h>
#include <linux/nexthop.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <string.h>

const struct md_network_nexthop *md_network_nexthop_find(const struct md_network_namespace *n,uint32_t id) {
    for (unsigned i=0;i<n->nexthop_count;++i) if (n->nexthops[i].id==id) return &n->nexthops[i];
    return NULL;
}
int md_network_nexthop_reply(struct md_network_message *o,const struct nlmsghdr *h,uint32_t port,int dump,
        const struct md_network_nexthop *value) {
    struct nhmsg m={.nh_family=value->gateway.family,.nh_protocol=value->protocol,.nh_flags=value->flags};
    int error=md_network_message_begin(o,h,port,RTM_NEWNEXTHOP,dump,&m,sizeof(m));
    if (!error) error=md_network_message_attribute(o,NHA_ID,&value->id,sizeof(value->id));
    if (!error) error=md_network_message_attribute(o,NHA_OIF,&value->interface,sizeof(value->interface));
    if (!error && !md_ip_unspecified(value->gateway))
        error=md_network_message_attribute(o,NHA_GATEWAY,value->gateway.bytes,md_ip_size(value->gateway));
    return error;
}
int md_network_nexthops(struct md_network_namespace *n,const struct nlmsghdr *h,uint32_t port,int dump,
        struct md_network_message *o) {
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct nhmsg))) return -EINVAL;
    const struct nhmsg *m=NLMSG_DATA(h);
    if (m->nh_family!=AF_UNSPEC && m->nh_family!=AF_INET && m->nh_family!=AF_INET6) return -EAFNOSUPPORT;
    if (m->resvd || m->nh_scope || (m->nh_flags&~RTNH_F_ONLINK)) return -EOPNOTSUPP;
    struct md_network_nexthop value={.gateway=md_ip_any(m->nh_family),.flags=m->nh_flags,.protocol=m->nh_protocol};
    struct md_netlink_cursor cursor={(const char *)m+NLMSG_ALIGN(sizeof(*m)),h->nlmsg_len-NLMSG_LENGTH(sizeof(*m))};
    struct md_netlink_attribute a; uint64_t seen=0; int error;
    while ((error=md_netlink_attribute_next(&cursor,&a))>0) {
        if (a.type!=NHA_ID && a.type!=NHA_OIF && a.type!=NHA_GATEWAY) return -EOPNOTSUPP;
        if (seen&(UINT64_C(1)<<a.type)) return -EINVAL;
        seen|=UINT64_C(1)<<a.type;
        unsigned length=a.type==NHA_GATEWAY ? md_ip_size(value.gateway) : sizeof(uint32_t);
        const struct rtattr *wire=a.wire;
        if (RTA_PAYLOAD(wire)!=length) return -EINVAL;
        if (a.type==NHA_GATEWAY) memcpy(value.gateway.bytes,RTA_DATA(wire),length);
        else memcpy(a.type==NHA_ID ? &value.id : &value.interface,RTA_DATA(wire),length);
    }
    if (error) return error;
    if (h->nlmsg_type==RTM_GETNEXTHOP) {
        for (unsigned i=0;i<n->nexthop_count;++i) {
            const struct md_network_nexthop *entry=&n->nexthops[i];
            if ((value.id && value.id!=entry->id) || (value.interface && value.interface!=entry->interface)
                    || (m->nh_family && m->nh_family!=entry->gateway.family)) continue;
            error=md_network_nexthop_reply(o,h,port,dump,entry);
            if (error || !dump) return error;
        }
        return dump ? 0 : -ENOENT;
    }
    if (!value.id) return -EINVAL;
    unsigned at=0;
    while (at<n->nexthop_count && n->nexthops[at].id!=value.id) ++at;
    if (h->nlmsg_type==RTM_DELNEXTHOP) {
        if (at==n->nexthop_count) return -ENOENT;
        memmove(n->nexthops+at,n->nexthops+at+1,(--n->nexthop_count-at)*sizeof(value));
        for (unsigned i=0;i<n->route_count;) {
            if (n->routes[i].nexthop!=value.id) { ++i; continue; }
            memmove(n->routes+i,n->routes+i+1,(--n->route_count-i)*sizeof(*n->routes));
        }
        return 0;
    }
    if (m->nh_family!=AF_INET && m->nh_family!=AF_INET6) return -EINVAL;
    struct md_network_interface *link=md_network_interface_find(n,value.interface,NULL);
    if (!link) return -ENODEV;
    if (!(link->info.flags&IFF_UP)) return -ENETDOWN;
    if (m->nh_family==AF_INET6 && link->ipv6.disabled) return -EACCES;
    if (md_ip_multicast(value.gateway)) return -EINVAL;
    if (!md_ip_unspecified(value.gateway) && !(value.flags&RTNH_F_ONLINK)
            && !(value.gateway.family==AF_INET6 && md_ip_linklocal(value.gateway))) {
        struct md_network_route_entry route;
        if (!md_network_route_lookup(n,value.gateway,value.interface,&route) || !md_ip_unspecified(route.gateway)) return -EINVAL;
    }
    if (at<n->nexthop_count && ((h->nlmsg_flags&NLM_F_EXCL) || !(h->nlmsg_flags&NLM_F_REPLACE))) return -EEXIST;
    if (at==n->nexthop_count) {
        if (!(h->nlmsg_flags&NLM_F_CREATE)) return -ENOENT;
        if (n->nexthop_count==MD_NETWORK_MAX_ROUTES) return -ENOSPC;
        ++n->nexthop_count;
    }
    for (unsigned i=0;i<n->route_count;++i) if (n->routes[i].nexthop==value.id) {
        if (n->routes[i].destination.family!=value.gateway.family) return -EINVAL;
        n->routes[i].interface=value.interface; n->routes[i].gateway=value.gateway;
        n->routes[i].flags=value.flags;
    }
    n->nexthops[at]=value; return 0;
}
