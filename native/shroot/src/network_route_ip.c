#include "network_route_ip.h"
#include "network_nexthop.h"
#include "network_forward.h"
#include "network_autoconf.h"
#include "network_policy.h"
#include "netlink_attributes.h"
#include <errno.h>
#include <linux/if_addr.h>
#include <linux/rtnetlink.h>
#include <string.h>
#include <time.h>

int64_t md_network_lifetime_now(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t);
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
uint32_t md_network_lifetime_remaining(int64_t deadline,int64_t now) {
    return !deadline ? UINT32_MAX : deadline<=now ? 0 : (uint32_t)((deadline-now+999)/1000);
}
static int64_t lifetime_deadline(uint32_t seconds,int64_t now) {
    return seconds==UINT32_MAX ? 0 : now+(int64_t)seconds*1000;
}

static int ip_attribute(struct md_network_message *o,unsigned type,struct md_ip_address a) {
    return md_network_message_attribute(o,type,a.bytes,md_ip_size(a));
}
static int read_ip(const struct md_netlink_attribute *a,unsigned family,struct md_ip_address *out) {
    unsigned size=family==AF_INET6 ? 16 : 4;
    const struct rtattr *wire=a->wire;
    if (RTA_PAYLOAD(wire)!=size) return -EINVAL;
    *out=md_ip_load(family,RTA_DATA(wire)); return 0;
}
static int read_number(const struct md_netlink_attribute *a,uint32_t *out) {
    const struct rtattr *wire=a->wire;
    if (RTA_PAYLOAD(wire)!=4) return -EINVAL;
    memcpy(out,RTA_DATA(wire),4); return 0;
}
static int family_supported(unsigned family) { return family==AF_INET || family==AF_INET6; }
static int metrics_read(const struct md_netlink_attribute *outer,unsigned family,struct md_network_route_metrics *out) {
    const struct rtattr *wire=outer->wire;
    struct md_netlink_cursor cursor={RTA_DATA(wire),RTA_PAYLOAD(wire)};
    struct md_netlink_attribute a; int error;
    while ((error=md_netlink_attribute_next(&cursor,&a))>0) {
        if (a.type!=RTAX_MTU && a.type!=RTAX_HOPLIMIT && a.type!=RTAX_QUICKACK) return -EOPNOTSUPP;
        if (out->present&(1U<<a.type)) return -EINVAL;
        uint32_t value;
        error=read_number(&a,&value); if (error) return error;
        if (a.type==RTAX_MTU) {
            if (family!=AF_INET6 && value) return -EOPNOTSUPP;
            if (value && (value<1280 || value>65535)) return -EINVAL;
            out->mtu=value;
        } else if (a.type==RTAX_HOPLIMIT) {
            if (family!=AF_INET6 && value) return -EOPNOTSUPP;
            if (value>255) return -EINVAL;
            out->hops=value;
        } else if (value) return -EOPNOTSUPP; /* Zero selects native adaptive ACKs. */
        out->present|=1U<<a.type;
    }
    return error;
}
static int metrics_reply(struct md_network_message *o,const struct md_network_route_metrics *m) {
    if (!m->present) return 0;
    struct { struct rtattr attribute; uint32_t value; } values[3];
    unsigned count=0;
    const unsigned types[]={RTAX_MTU,RTAX_HOPLIMIT,RTAX_QUICKACK};
    for (unsigned i=0;i<3;++i) if (m->present&(1U<<types[i])) {
        values[count].attribute=(struct rtattr){.rta_type=types[i],.rta_len=RTA_LENGTH(4)};
        values[count++].value=i==0 ? m->mtu : i==1 ? m->hops : 0;
    }
    return md_network_message_attribute(o,RTA_METRICS,values,count*sizeof(*values));
}
int md_network_address_reply(struct md_network_message *o,const struct nlmsghdr *h,uint32_t port,int dump,
        const struct md_network_interface *link,const struct md_network_ip_assignment *address) {
    struct ifaddrmsg m={.ifa_family=address->address.family,.ifa_prefixlen=address->prefix,.ifa_flags=address->flags,
        .ifa_scope=address->scope,.ifa_index=link->info.index};
    int error=md_network_message_begin(o,h,port,RTM_NEWADDR,dump,&m,sizeof(m));
    if (!error) error=ip_attribute(o,IFA_ADDRESS,md_network_address_destination(address));
    if (!error && (m.ifa_family==AF_INET || !md_ip_unspecified(address->peer)))
        error=ip_attribute(o,IFA_LOCAL,address->address);
    if (!error && m.ifa_family==AF_INET)
        error=md_network_message_attribute(o,IFA_LABEL,link->info.name,strlen(link->info.name)+1);
    if (!error) error=md_network_message_attribute(o,IFA_FLAGS,&address->flags,sizeof(address->flags));
    if (!error && address->route_metric)
        error=md_network_message_attribute(o,IFA_RT_PRIORITY,&address->route_metric,sizeof(address->route_metric));
    int64_t now=md_network_lifetime_now();
    struct ifa_cacheinfo cache={.ifa_prefered=md_network_lifetime_remaining(address->preferred_until,now),
        .ifa_valid=md_network_lifetime_remaining(address->valid_until,now)};
    if (!error) error=md_network_message_attribute(o,IFA_CACHEINFO,&cache,sizeof(cache));
    return error;
}
int md_network_addresses(struct md_network_namespace *n,const struct nlmsghdr *h,uint32_t port,int dump,
        struct md_network_message *o) {
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct ifaddrmsg))) return -EINVAL;
    const struct ifaddrmsg *m=NLMSG_DATA(h);
    if (m->ifa_family!=AF_UNSPEC && !family_supported(m->ifa_family)) return -EAFNOSUPPORT;
    if (h->nlmsg_type==RTM_GETADDR) {
        int found=0;
        for (unsigned i=0;i<n->count;++i) {
            const struct md_network_interface *link=&n->links[i];
            if (m->ifa_index && m->ifa_index!=link->info.index) continue;
            for (unsigned j=0;j<link->address_count;++j) {
                const struct md_network_ip_assignment *a=&link->addresses[j];
                if (m->ifa_family && m->ifa_family!=a->address.family) continue;
                int error=md_network_address_reply(o,h,port,dump,link,a);
                if (error) return error;
                found=1;
            }
        }
        return dump || found ? 0 : -EADDRNOTAVAIL;
    }
    if (!family_supported(m->ifa_family) || m->ifa_prefixlen>(m->ifa_family==AF_INET6 ? 128 : 32)) return -EINVAL;
    struct md_network_interface *link=md_network_interface_find(n,m->ifa_index,NULL);
    if (!link) return -ENODEV;
    if (m->ifa_family==AF_INET6 && (link->ipv6.disabled || link->info.mtu<1280)) return -EACCES;
    struct md_network_ip_assignment value={.address=md_ip_any(m->ifa_family),.prefix=m->ifa_prefixlen,
        .scope=m->ifa_scope,.flags=m->ifa_flags|IFA_F_PERMANENT};
    struct md_ip_address local=md_ip_any(m->ifa_family),peer=local;
    struct md_netlink_cursor cursor={IFA_RTA(m),h->nlmsg_len-NLMSG_LENGTH(sizeof(*m))};
    struct md_netlink_attribute a; uint64_t seen=0; int error;
    uint32_t preferred=UINT32_MAX,valid=UINT32_MAX;
    while ((error=md_netlink_attribute_next(&cursor,&a))>0) {
        if (!a.type || a.type>IFA_MAX) return -EOPNOTSUPP;
        if (seen&(UINT64_C(1)<<a.type)) return -EINVAL;
        seen|=UINT64_C(1)<<a.type;
        const struct rtattr *wire=a.wire;
        if (a.type==IFA_ADDRESS || a.type==IFA_LOCAL) {
            error=read_ip(&a,m->ifa_family,a.type==IFA_LOCAL ? &local : &peer);
        } else if (a.type==IFA_FLAGS) error=read_number(&a,&value.flags);
        else if (a.type==IFA_RT_PRIORITY) error=read_number(&a,&value.route_metric);
        else if (a.type==IFA_LABEL) {
            size_t size=RTA_PAYLOAD(wire),length=strlen(link->info.name);
            error=size!=length+1 || memcmp(RTA_DATA(wire),link->info.name,length+1) ? -EOPNOTSUPP : 0;
        } else if (a.type==IFA_BROADCAST && m->ifa_family==AF_INET) {
            struct md_ip_address ignored; error=read_ip(&a,AF_INET,&ignored);
        } else if (a.type==IFA_CACHEINFO) {
            struct ifa_cacheinfo cache;
            if (RTA_PAYLOAD(wire)!=sizeof(cache)) return -EINVAL;
            memcpy(&cache,RTA_DATA(wire),sizeof(cache));
            preferred=cache.ifa_prefered; valid=cache.ifa_valid;
            error=!valid || preferred>valid ? -EINVAL : 0;
        } else error=-EOPNOTSUPP;
        if (error) return error;
    }
    if (error) return error;
    value.address=md_ip_unspecified(local) ? peer : local;
    if (!md_ip_unspecified(local) && !md_ip_unspecified(peer) && !md_ip_equal(local,peer)) {
        if (md_ip_multicast(peer)) return -EINVAL;
        value.peer=peer;
    }
    unsigned allowed=IFA_F_PERMANENT|IFA_F_NODAD|IFA_F_NOPREFIXROUTE|IFA_F_MANAGETEMPADDR;
    if (value.flags&~allowed) return -EOPNOTSUPP;
    value.flags|=IFA_F_PERMANENT;
    if (h->nlmsg_type==RTM_NEWADDR && (preferred!=UINT32_MAX || valid!=UINT32_MAX)) {
        int64_t now=md_network_lifetime_now();
        value.preferred_until=lifetime_deadline(preferred,now);
        value.valid_until=lifetime_deadline(valid,now);
        if (valid!=UINT32_MAX) value.flags&=~IFA_F_PERMANENT;
        if (!preferred) value.flags|=IFA_F_DEPRECATED;
        error=md_network_forward_timer(n->fabric);
        if (error) return error;
    }
    if (value.scope!=RT_SCOPE_UNIVERSE && value.scope!=RT_SCOPE_LINK && value.scope!=RT_SCOPE_HOST) return -EINVAL;
    if (md_ip_multicast(value.address)) return -EINVAL;
    if (h->nlmsg_type==RTM_NEWADDR && value.address.family==AF_INET6 && !(value.flags&IFA_F_NODAD)
            && (link->ipv6.accept_dad || n->all6.accept_dad)
            && md_network_address_duplicate(n,link,value.address)) {
        if ((link->ipv6.accept_dad==2 || n->all6.accept_dad==2) && md_ip_linklocal(value.address)) {
            link->ipv6.disabled=1; md_network_interface_ipv6_flush(n,link); return 0;
        }
        value.flags|=IFA_F_DADFAILED;
    }
    error=md_network_interface_address(link,value,h->nlmsg_type==RTM_DELADDR,!!(h->nlmsg_flags&NLM_F_EXCL));
    return error ? error : md_network_address_refresh(n,link,md_network_lifetime_now());
}
struct md_network_route_entry md_network_connected_route(const struct md_network_interface *link,
        const struct md_network_ip_assignment *a) {
    return (struct md_network_route_entry){.destination=md_ip_network(md_network_address_destination(a),a->prefix),
        .gateway=md_ip_any(a->address.family),.prefix=a->prefix,.interface=link->info.index,
        .source=md_ip_unspecified(a->peer) ? md_ip_any(a->address.family) : a->address,
        .metric=a->route_metric ? a->route_metric : a->address.family==AF_INET6 ? 256 : 0,
        .protocol=RTPROT_KERNEL,.scope=RT_SCOPE_LINK};
}
int md_network_address_has_route(const struct md_network_ip_assignment *a) {
    return !(a->flags&(IFA_F_NOPREFIXROUTE|IFA_F_TENTATIVE|IFA_F_DADFAILED));
}
int md_network_connected_index(const struct md_network_interface *link,const struct md_network_ip_assignment *a) {
    for (unsigned i=0;link && i<link->address_count;++i) {
        const struct md_network_ip_assignment *b=&link->addresses[i];
        if (md_network_address_has_route(b) && a->prefix==b->prefix && a->route_metric==b->route_metric
                && md_ip_prefix(md_network_address_destination(a),md_network_address_destination(b),a->prefix)) return (int)i;
    }
    return -1;
}
int md_network_route_reply(struct md_network_message *o,const struct nlmsghdr *h,uint32_t port,int dump,
        const struct md_network_interface *link,const struct md_network_route_entry *r,const struct md_ip_address *lookup) {
    uint32_t table=r->table ? r->table : RT_TABLE_MAIN;
    struct rtmsg m={.rtm_family=r->destination.family,.rtm_dst_len=lookup ? md_ip_size(*lookup)*8 : r->prefix,
        .rtm_table=table<=255 ? table : RT_TABLE_UNSPEC,.rtm_protocol=r->protocol,.rtm_scope=r->scope,
        .rtm_type=table==RT_TABLE_LOCAL ? RTN_LOCAL : RTN_UNICAST};
    int error=md_network_message_begin(o,h,port,RTM_NEWROUTE,dump,&m,sizeof(m));
    if (!error) error=md_network_message_attribute(o,RTA_TABLE,&table,sizeof(table));
    if (!error && r->nexthop && !lookup) error=md_network_message_attribute(o,RTA_NH_ID,&r->nexthop,sizeof(r->nexthop));
    if (!error && (!r->nexthop || lookup)) error=md_network_message_attribute(o,RTA_OIF,&r->interface,sizeof(r->interface));
    if (!error && (!r->nexthop || lookup) && !md_ip_unspecified(r->gateway)) error=ip_attribute(o,RTA_GATEWAY,r->gateway);
    if (!error && r->metric) error=md_network_message_attribute(o,RTA_PRIORITY,&r->metric,sizeof(r->metric));
    if (!error) error=metrics_reply(o,&r->metrics);
    if (!error && r->destination.family==AF_INET6)
        error=md_network_message_attribute(o,RTA_PREF,&r->preference,sizeof(r->preference));
    if (!error && r->valid_until) {
        uint32_t expires=md_network_lifetime_remaining(r->valid_until,md_network_lifetime_now());
        error=md_network_message_attribute(o,RTA_EXPIRES,&expires,sizeof(expires));
    }
    if (!error && (r->prefix || lookup)) error=ip_attribute(o,RTA_DST,lookup ? *lookup : r->destination);
    struct md_ip_address source=md_ip_unspecified(r->source)
        ? md_network_interface_source(link,lookup ? *lookup : r->destination) : r->source;
    if (!error && (lookup || !md_ip_unspecified(r->source) || (r->protocol==RTPROT_KERNEL && r->destination.family==AF_INET))
            && !md_ip_unspecified(source)) error=ip_attribute(o,RTA_PREFSRC,source);
    return error;
}
int md_network_routes(struct md_network_namespace *n,const struct nlmsghdr *h,uint32_t port,int dump,int strict,
        struct md_network_message *o) {
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct rtmsg))) return -EINVAL;
    const struct rtmsg *m=NLMSG_DATA(h);
    if (m->rtm_family!=AF_UNSPEC && !family_supported(m->rtm_family)) return -EAFNOSUPPORT;
    int query=h->nlmsg_type==RTM_GETROUTE;
    if ((!query || !dump) && !family_supported(m->rtm_family)) return -EINVAL;
    struct md_network_route_entry value={.destination=md_ip_any(m->rtm_family),.gateway=md_ip_any(m->rtm_family),
        .prefix=m->rtm_dst_len,.protocol=m->rtm_protocol,.scope=m->rtm_scope,
        .metric=m->rtm_family==AF_INET6 ? 1024 : 0};
    uint32_t table=m->rtm_table,mark=0; uint64_t seen=0;
    struct md_ip_address source=md_ip_any(m->rtm_family);
    if (!query || !dump || strict) {
        if ((m->rtm_src_len && (!query || dump || m->rtm_src_len!=md_ip_size(source)*8))
                || m->rtm_tos || (m->rtm_flags&~(query ? RTM_F_LOOKUP_TABLE : 0))) return -EOPNOTSUPP;
        if (value.prefix>(m->rtm_family==AF_INET6 ? 128 : 32)) return -EINVAL;
        if (query && dump && (m->rtm_dst_len || m->rtm_scope || m->rtm_flags)) return -EOPNOTSUPP;
        if (!query && m->rtm_type!=RTN_UNICAST
                && !(h->nlmsg_type==RTM_DELROUTE && m->rtm_type==RTN_UNSPEC)) return -EOPNOTSUPP;
        struct md_netlink_cursor cursor={RTM_RTA(m),h->nlmsg_len-NLMSG_LENGTH(sizeof(*m))};
        struct md_netlink_attribute a; int error;
        while ((error=md_netlink_attribute_next(&cursor,&a))>0) {
            if (!a.type || a.type>RTA_MAX) return -EOPNOTSUPP;
            if (seen&(UINT64_C(1)<<a.type)) return -EINVAL;
            seen|=UINT64_C(1)<<a.type;
            if (query && dump && a.type!=RTA_TABLE && a.type!=RTA_OIF) return -EOPNOTSUPP;
            switch (a.type) {
            case RTA_DST: error=read_ip(&a,m->rtm_family,&value.destination); break;
            case RTA_SRC: error=query ? read_ip(&a,m->rtm_family,&source) : -EOPNOTSUPP; break;
            case RTA_MARK: error=query ? read_number(&a,&mark) : -EOPNOTSUPP; break;
            case RTA_GATEWAY: error=read_ip(&a,m->rtm_family,&value.gateway); break;
            case RTA_PREFSRC: error=query ? -EINVAL : read_ip(&a,m->rtm_family,&value.source); break;
            case RTA_OIF: error=read_number(&a,&value.interface); break;
            case RTA_PRIORITY: error=read_number(&a,&value.metric); break;
            case RTA_TABLE: error=read_number(&a,&table); break;
            case RTA_NH_ID: error=read_number(&a,&value.nexthop); break;
            case RTA_METRICS: error=metrics_read(&a,m->rtm_family,&value.metrics); break;
            case RTA_PREF: {
                const struct rtattr *wire=a.wire;
                if (m->rtm_family!=AF_INET6 || RTA_PAYLOAD(wire)!=1) return -EINVAL;
                value.preference=*(const unsigned char *)RTA_DATA(wire);
                error=value.preference==0 || value.preference==1 || value.preference==3 ? 0 : -EINVAL;
                break;
            }
            case RTA_EXPIRES: {
                uint32_t expires; error=read_number(&a,&expires);
                if (!error) value.valid_until=lifetime_deadline(expires,md_network_lifetime_now());
                break;
            }
            case RTA_UID: { uint32_t ignored; error=query ? read_number(&a,&ignored) : -EOPNOTSUPP; break; }
            default: error=-EOPNOTSUPP;
            }
            if (error) return error;
        }
        if (error) return error;
    } else table=0;
    value.table=table ? table : RT_TABLE_MAIN;
    if (!query && value.table==RT_TABLE_LOCAL) return -EOPNOTSUPP;
    if (query && dump) {
        if (strict && m->rtm_type && m->rtm_type!=RTN_UNICAST) return 0;
        for (unsigned i=0;(!table || table==RT_TABLE_MAIN) && i<n->count;++i) for (unsigned j=0;j<n->links[i].address_count;++j) {
            if (value.interface && value.interface!=n->links[i].info.index) continue;
            const struct md_network_ip_assignment *a=&n->links[i].addresses[j];
            if (!md_network_address_has_route(a) || md_network_connected_index(&n->links[i],a)!=(int)j
                    || (m->rtm_family && m->rtm_family!=a->address.family)) continue;
            struct md_network_route_entry route=md_network_connected_route(&n->links[i],a);
            if (strict && m->rtm_protocol && m->rtm_protocol!=route.protocol) continue;
            int error=md_network_route_reply(o,h,port,1,&n->links[i],&route,NULL);
            if (error) return error;
        }
        for (unsigned i=0;i<n->route_count;++i) {
            const struct md_network_route_entry *route=&n->routes[i];
            if (table && table!=(route->table ? route->table : RT_TABLE_MAIN)) continue;
            if (strict && m->rtm_protocol && m->rtm_protocol!=route->protocol) continue;
            if (value.interface && value.interface!=route->interface) continue;
            if (m->rtm_family && m->rtm_family!=route->destination.family) continue;
            struct md_network_interface *link=md_network_interface_find(n,route->interface,NULL);
            int error=md_network_route_reply(o,h,port,1,link,route,NULL);
            if (error) return error;
        }
        return 0;
    }
    if (query) {
        if (!(seen&(UINT64_C(1)<<RTA_DST))) return -EINVAL;
        struct md_network_route_entry route={0}; int error=-ENETUNREACH;
        struct md_network_interface *link=table ? md_network_route_table(n,value.destination,value.interface,table,&route)
            : md_network_policy_route(n,source,value.destination,mark,value.interface,&route,&error);
        if (!link) return error;
        return md_network_route_reply(o,h,port,0,link,&route,&value.destination);
    }
    if (!md_ip_equal(md_ip_network(value.destination,value.prefix),value.destination)) return -EINVAL;
    if (value.nexthop) {
        if (value.interface || !md_ip_unspecified(value.gateway)) return -EINVAL;
        const struct md_network_nexthop *next=md_network_nexthop_find(n,value.nexthop);
        if (!next) return -ENOENT;
        if (next->gateway.family!=value.destination.family) return -EINVAL;
        value.interface=next->interface; value.gateway=next->gateway;
    }
    if (!value.interface && !md_ip_unspecified(value.gateway)) {
        struct md_network_route_entry route;
        struct md_network_interface *link=md_network_route_find(n,value.gateway,&route);
        if (link && md_ip_unspecified(route.gateway)) value.interface=link->info.index;
    }
    if (h->nlmsg_type==RTM_DELROUTE) {
        for (unsigned i=0;i<n->route_count;++i) {
            const struct md_network_route_entry *r=&n->routes[i];
            if (value.table!=(r->table ? r->table : RT_TABLE_MAIN)) continue;
            if (!md_ip_equal(value.destination,r->destination) || value.prefix!=r->prefix
                    || (value.interface && value.interface!=r->interface)
                    || (!md_ip_unspecified(value.gateway) && !md_ip_equal(value.gateway,r->gateway))
                    || (!md_ip_unspecified(value.source) && !md_ip_equal(value.source,r->source))
                    || ((seen&(UINT64_C(1)<<RTA_PRIORITY)) && value.metric!=r->metric)) continue;
            memmove(n->routes+i,n->routes+i+1,(--n->route_count-i)*sizeof(*n->routes)); return 0;
        }
        return -ESRCH;
    }
    struct md_network_interface *link=md_network_interface_find(n,value.interface,NULL);
    if (!link) return -ENETUNREACH;
    if (!md_ip_unspecified(value.source)) {
        int local=0;
        for (unsigned i=0;i<n->count;++i) local|=md_network_interface_has(&n->links[i],value.source);
        if (!local) return -EINVAL;
    }
    if (!md_ip_unspecified(value.gateway) && md_ip_unspecified(md_network_interface_source(link,value.gateway))) return -ENETUNREACH;
    if (value.valid_until) {
        int error=md_network_forward_timer(n->fabric);
        if (error) return error;
    }
    for (unsigned i=0;i<n->route_count;++i) {
        const struct md_network_route_entry *r=&n->routes[i];
        if (value.table!=(r->table ? r->table : RT_TABLE_MAIN)) continue;
        if (!md_ip_equal(value.destination,r->destination) || value.prefix!=r->prefix || value.metric!=r->metric) continue;
        if (value.destination.family==AF_INET6 && !(h->nlmsg_flags&NLM_F_REPLACE)
                && (value.interface!=r->interface || value.nexthop!=r->nexthop || !md_ip_equal(value.gateway,r->gateway))) continue;
        if ((h->nlmsg_flags&NLM_F_EXCL) || !(h->nlmsg_flags&NLM_F_REPLACE)) return -EEXIST;
        n->routes[i]=value; return 0;
    }
    if (!(h->nlmsg_flags&NLM_F_CREATE)) return -ENOENT;
    if (n->route_count==MD_NETWORK_MAX_ROUTES) return -ENOSPC;
    n->routes[n->route_count++]=value; return 0;
}
