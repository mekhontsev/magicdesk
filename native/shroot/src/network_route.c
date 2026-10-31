#define _GNU_SOURCE
#include "network_nexthop.h"
#include "network_autoconf.h"
#include "network_route.h"
#include "network_ioctl.h"
#include "network_route_ip.h"
#include "network_message.h"
#include "netlink_attributes.h"
#include "network_internal.h"
#include "network_forward.h"
#include "network_endpoint.h"
#include "guest_identity.h"
#include <errno.h>
#include <linux/if_link.h>
#include <linux/fib_rules.h>
#include <linux/neighbour.h>
#include <linux/nexthop.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <linux/veth.h>
#include <net/if.h>
#include <netinet/in.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <limits.h>

struct attrs { const struct rtattr *v[IFLA_MAX+1]; };
static int attributes(const void *data,size_t size,struct attrs *out,unsigned max) {
    memset(out,0,sizeof(*out));
    struct md_netlink_cursor cursor={data,size};
    struct md_netlink_attribute a; int result;
    while ((result=md_netlink_attribute_next(&cursor,&a))>0) {
        unsigned type=a.type;
        if (type>max || type>=sizeof(out->v)/sizeof(*out->v) || !type) return -EOPNOTSUPP;
        if (out->v[type]) return -EINVAL;
        out->v[type]=a.wire;
    }
    return result;
}
static int number(const struct rtattr *a,uint32_t *out) {
    if (RTA_PAYLOAD(a)!=sizeof(*out)) return -EINVAL;
    memcpy(out,RTA_DATA(a),sizeof(*out)); return 0;
}
static int string(const struct rtattr *a,char *out,size_t maximum) {
    size_t size=RTA_PAYLOAD(a);
    if (!size || size>maximum) return -EINVAL;
    size_t length=strnlen(RTA_DATA(a),size);
    if (!length || length>=maximum) return -EINVAL;
    memcpy(out,RTA_DATA(a),length); out[length]=0; return 0;
}
static int kind_is(const struct rtattr *a,const char *name) {
    size_t size=RTA_PAYLOAD(a),length=strlen(name);
    return (size==length || (size==length+1 && !((const char *)RTA_DATA(a))[length]))
        && !memcmp(RTA_DATA(a),name,length);
}
static int link_reply(struct md_network_message *out,const struct nlmsghdr *h,uint32_t port,int dump,
        struct md_network_namespace *n,const struct md_network_interface *link,int family) {
    struct md_network_link observed=md_network_interface_snapshot(n,link);
    struct ifinfomsg m={.ifi_family=family,.ifi_type=link->info.type,
        .ifi_index=link->info.index,.ifi_flags=observed.flags};
    int error=md_network_message_begin(out,h,port,RTM_NEWLINK,dump,&m,sizeof(m));
    if (!error) error=md_network_message_attribute(out,IFLA_IFNAME,link->info.name,strlen(link->info.name)+1);
    if (!error) error=md_network_message_attribute(out,IFLA_MTU,&link->info.mtu,sizeof(link->info.mtu));
    if (!error) error=md_network_message_attribute(out,IFLA_TXQLEN,&link->info.tx_queue_length,sizeof(link->info.tx_queue_length));
    if (!error) error=md_network_message_attribute(out,IFLA_ADDRESS,link->info.address,6);
    unsigned char state=observed.state;
    if (!error) error=md_network_message_attribute(out,IFLA_OPERSTATE,&state,1);
    if (!error && link->master) error=md_network_message_attribute(out,IFLA_MASTER,&link->master,sizeof(link->master));
    if (!error && link->peer) error=md_network_message_attribute(out,IFLA_LINK,&link->peer,sizeof(link->peer));
    if (!error && (family==AF_UNSPEC || family==AF_INET6)) {
        size_t af,inet6;
        unsigned char mode=(unsigned char)link->ipv6.addr_gen_mode;
        error=md_network_message_nest(out,IFLA_AF_SPEC,&af);
        if (!error) error=md_network_message_nest(out,AF_INET6,&inet6);
        if (!error) error=md_network_message_attribute(out,IFLA_INET6_ADDR_GEN_MODE,&mode,1);
        if (!error) error=md_network_message_end_nest(out,inet6);
        if (!error) error=md_network_message_end_nest(out,af);
    }
    if (!error && link->master && family==AF_BRIDGE) {
        size_t nest=0;
        unsigned char hairpin=(unsigned char)link->hairpin;
        unsigned char isolated=(unsigned char)link->isolated;
        error=md_network_message_nest(out,IFLA_PROTINFO,&nest);
        if (!error) error=md_network_message_attribute(out,IFLA_BRPORT_MODE,&hairpin,sizeof(hairpin));
        if (!error) error=md_network_message_attribute(out,IFLA_BRPORT_ISOLATED,&isolated,sizeof(isolated));
        if (!error) error=md_network_message_end_nest(out,nest);
    }
    if (!error && (link->kind==MD_LINK_VETH || link->kind==MD_LINK_BRIDGE || link->kind==MD_LINK_TUN || link->kind==MD_LINK_TAP)) {
        const char *kind=link->kind==MD_LINK_VETH ? "veth" : link->kind==MD_LINK_BRIDGE ? "bridge" : "tun";
        size_t info=out->size;
        /* rtnl_link_fill uses nla_nest_start_noflag for this legacy nest. */
        error=md_network_message_attribute(out,IFLA_LINKINFO,NULL,0);
        if (!error) error=md_network_message_attribute(out,IFLA_INFO_KIND,kind,strlen(kind)+1);
        if (!error && link->kind==MD_LINK_BRIDGE) {
            size_t data=out->size;
            uint32_t stp=0;
            error=md_network_message_attribute(out,IFLA_INFO_DATA,NULL,0);
            if (!error) error=md_network_message_attribute(out,IFLA_BR_STP_STATE,&stp,sizeof(stp));
            if (!error) error=md_network_message_attribute(out,IFLA_BR_FORWARD_DELAY,&link->bridge_forward_delay,sizeof(uint32_t));
            if (!error) error=md_network_message_end_nest(out,data);
        }
        if (!error) error=md_network_message_end_nest(out,info);
    }
    return error;
}
struct link_change {
    const char *name,*peer_name;
    char name_storage[IFNAMSIZ],peer_storage[IFNAMSIZ];
    enum md_network_link_kind kind;
    unsigned index,master,mtu,change,flags,tx_queue_length,peer_tx_queue_length,peer_mtu,family;
    int has_master,has_mtu,has_address,has_tx_queue_length,has_peer_tx_queue_length;
    struct md_network_target_reference namespace,peer_namespace;
    int has_peer_mtu,has_peer_address,has_hairpin,has_isolated,has_addrgen;
    unsigned addrgen;
    unsigned hairpin,isolated;
    uint32_t forward_delay;
    int has_forward_delay;
    unsigned char address[6],peer_address[6];
};
static int namespace_reference(const struct rtattr *attribute,unsigned type,struct md_network_target_reference *out) {
    if (out->kind) return -EINVAL;
    uint32_t value=0;
    int error=number(attribute,&value);
    if (error) return error;
    enum md_network_target_kind kind=type==IFLA_NET_NS_FD ? MD_NETWORK_TARGET_FD : MD_NETWORK_TARGET_PID;
    if ((int)value<0 || (kind==MD_NETWORK_TARGET_PID && !value))
        return kind==MD_NETWORK_TARGET_FD ? -EBADF : -ESRCH;
    *out=(struct md_network_target_reference){kind,(int)value}; return 0;
}
static int parse_link(const struct nlmsghdr *h,struct link_change *change,struct attrs *a) {
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct ifinfomsg))) return -EINVAL;
    const struct ifinfomsg *m=NLMSG_DATA(h);
    int ipv6_query=h->nlmsg_type==RTM_GETLINK && m->ifi_family==AF_INET6;
    int ipv6_update=m->ifi_family==AF_INET6 && m->ifi_index>0
        && (h->nlmsg_type==RTM_SETLINK || (h->nlmsg_type==RTM_NEWLINK && !(h->nlmsg_flags&NLM_F_CREATE)));
    int ipv4_query=h->nlmsg_type==RTM_GETLINK && m->ifi_family==AF_INET;
    int local_query=h->nlmsg_type==RTM_GETLINK && m->ifi_family==AF_UNIX;
    if (m->ifi_index<0 || (m->ifi_family!=AF_UNSPEC && m->ifi_family!=AF_PACKET
            && m->ifi_family!=AF_BRIDGE && !ipv6_query && !ipv6_update && !ipv4_query && !local_query)) return -EINVAL;
    *change=(struct link_change){.index=(unsigned)m->ifi_index,.change=m->ifi_change,.flags=m->ifi_flags,
        .family=m->ifi_family};
    int error=attributes(IFLA_RTA(m),h->nlmsg_len-NLMSG_LENGTH(sizeof(*m)),a,IFLA_MAX);
    for (unsigned i=1;!error && i<=IFLA_MAX;++i) {
        const struct rtattr *v=a->v[i]; if (!v) continue;
        switch (i) {
        case IFLA_IFNAME:
            error=string(v,change->name_storage,IFNAMSIZ); change->name=change->name_storage; break;
        case IFLA_MTU: error=number(v,&change->mtu); change->has_mtu=1; break;
        case IFLA_TXQLEN: error=number(v,&change->tx_queue_length); change->has_tx_queue_length=1; break;
        case IFLA_MASTER: error=number(v,&change->master); change->has_master=1; break;
        case IFLA_ADDRESS:
            if (RTA_PAYLOAD(v)!=6) error=-EINVAL;
            else { memcpy(change->address,RTA_DATA(v),6); change->has_address=1; } break;
        case IFLA_NET_NS_FD: case IFLA_NET_NS_PID:
            error=namespace_reference(v,i,&change->namespace); break;
        case IFLA_EXT_MASK: {
            uint32_t mask; error=number(v,&mask);
            /* Requests for optional link details do not require a virtual
             * device to implement those hardware capabilities. */
            break;
        }
        case IFLA_LINKINFO: break;
        case IFLA_AF_SPEC: {
            struct attrs families,inet6;
            error=attributes(RTA_DATA(v),RTA_PAYLOAD(v),&families,IFLA_MAX);
            for (unsigned j=1;!error && j<=IFLA_MAX;++j) if (families.v[j]) {
                if (j!=AF_INET6) { error=-EOPNOTSUPP; break; }
                error=attributes(RTA_DATA(families.v[j]),RTA_PAYLOAD(families.v[j]),&inet6,IFLA_INET6_MAX);
                for (unsigned k=1;!error && k<=IFLA_INET6_MAX;++k) if (inet6.v[k]) {
                    if (k!=IFLA_INET6_ADDR_GEN_MODE) error=-EOPNOTSUPP;
                    else if (RTA_PAYLOAD(inet6.v[k])!=1) error=-EINVAL;
                    else {
                        change->has_addrgen=1;
                        change->addrgen=*(const unsigned char *)RTA_DATA(inet6.v[k]);
                        if (change->addrgen>1) error=-EOPNOTSUPP;
                    }
                }
            }
            break;
        }
        case IFLA_PROTINFO: {
            if (m->ifi_family!=AF_BRIDGE) { error=-EOPNOTSUPP; break; }
            struct attrs port;
            error=attributes(RTA_DATA(v),RTA_PAYLOAD(v),&port,IFLA_BRPORT_MAX);
            for (unsigned j=1;!error && j<=IFLA_BRPORT_MAX;++j) if (port.v[j]) {
                if (j!=IFLA_BRPORT_MODE && j!=IFLA_BRPORT_ISOLATED) error=-EOPNOTSUPP;
                else if (RTA_PAYLOAD(port.v[j])!=1) error=-EINVAL;
                else {
                    unsigned value=*(const unsigned char *)RTA_DATA(port.v[j]);
                    if (value>1) error=-EINVAL;
                    else if (j==IFLA_BRPORT_MODE) { change->hairpin=value; change->has_hairpin=1; }
                    else { change->isolated=value; change->has_isolated=1; }
                }
            }
            break;
        }
        default: error=-EOPNOTSUPP;
        }
    }
    if (error || !a->v[IFLA_LINKINFO]) return error;
    struct attrs info;
    error=attributes(RTA_DATA(a->v[IFLA_LINKINFO]),RTA_PAYLOAD(a->v[IFLA_LINKINFO]),&info,IFLA_INFO_MAX);
    if (error || !info.v[IFLA_INFO_KIND]) return error ? error : -EINVAL;
    for (unsigned i=1;i<=IFLA_INFO_MAX;++i)
        if (info.v[i] && i!=IFLA_INFO_KIND && i!=IFLA_INFO_DATA) return -EOPNOTSUPP;
    if (kind_is(info.v[IFLA_INFO_KIND],"bridge")) {
        change->kind=MD_LINK_BRIDGE;
        if (!info.v[IFLA_INFO_DATA]) return 0;
        struct attrs data;
        error=attributes(RTA_DATA(info.v[IFLA_INFO_DATA]),RTA_PAYLOAD(info.v[IFLA_INFO_DATA]),&data,IFLA_BR_MAX);
        for (unsigned i=1;!error && i<=IFLA_BR_MAX;++i) if (data.v[i]) {
            uint32_t value;
            error=number(data.v[i],&value);
            if (error) break;
            if (i==IFLA_BR_FORWARD_DELAY) { change->forward_delay=value; change->has_forward_delay=1; }
            else if (i!=IFLA_BR_STP_STATE || value) error=-EOPNOTSUPP;
        }
        return error;
    }
    if (!kind_is(info.v[IFLA_INFO_KIND],"veth")) return -EOPNOTSUPP;
    change->kind=MD_LINK_VETH;
    if (!info.v[IFLA_INFO_DATA] || !RTA_PAYLOAD(info.v[IFLA_INFO_DATA])) return 0;
    struct attrs data;
    error=attributes(RTA_DATA(info.v[IFLA_INFO_DATA]),RTA_PAYLOAD(info.v[IFLA_INFO_DATA]),&data,VETH_INFO_MAX);
    if (error || !data.v[VETH_INFO_PEER]) return error ? error : -EINVAL;
    const struct rtattr *peer=data.v[VETH_INFO_PEER];
    if (RTA_PAYLOAD(peer)<sizeof(struct ifinfomsg)) return -EINVAL;
    const struct ifinfomsg *p=RTA_DATA(peer);
    if (p->ifi_index || p->ifi_flags || p->ifi_change) return -EOPNOTSUPP;
    struct attrs peer_attrs;
    error=attributes(p+1,RTA_PAYLOAD(peer)-sizeof(*p),&peer_attrs,IFLA_MAX);
    if (error) return error;
    for (unsigned i=1;i<=IFLA_MAX;++i) if (peer_attrs.v[i]) {
        if (i==IFLA_TXQLEN) {
            error=number(peer_attrs.v[i],&change->peer_tx_queue_length);
            if (error) return error;
            change->has_peer_tx_queue_length=1;
        } else if (i==IFLA_MTU) {
            error=number(peer_attrs.v[i],&change->peer_mtu);
            if (error) return error;
            change->has_peer_mtu=1;
        } else if (i==IFLA_ADDRESS) {
            if (RTA_PAYLOAD(peer_attrs.v[i])!=6) return -EINVAL;
            memcpy(change->peer_address,RTA_DATA(peer_attrs.v[i]),6);
            change->has_peer_address=1;
        } else if (i==IFLA_NET_NS_FD || i==IFLA_NET_NS_PID) {
            error=namespace_reference(peer_attrs.v[i],i,&change->peer_namespace);
            if (error) return error;
        } else if (i==IFLA_LINKINFO) {
            struct attrs peer_info;
            error=attributes(RTA_DATA(peer_attrs.v[i]),RTA_PAYLOAD(peer_attrs.v[i]),&peer_info,IFLA_INFO_MAX);
            if (error) return error;
            if (!peer_info.v[IFLA_INFO_KIND] || !kind_is(peer_info.v[IFLA_INFO_KIND],"veth")) return -EINVAL;
            for (unsigned j=1;j<=IFLA_INFO_MAX;++j)
                if (peer_info.v[j] && j!=IFLA_INFO_KIND) return -EOPNOTSUPP;
        } else if (i!=IFLA_IFNAME) return -EOPNOTSUPP;
    }
    if (!peer_attrs.v[IFLA_IFNAME]) return 0;
    change->peer_name=change->peer_storage;
    return string(peer_attrs.v[IFLA_IFNAME],change->peer_storage,IFNAMSIZ);
}
static int automatic_name(struct md_network_namespace *n,const char *kind,const char *avoid,char out[IFNAMSIZ]) {
    for (unsigned i=0;i<UINT16_MAX;++i) {
        snprintf(out,IFNAMSIZ,"%s%u",kind,i);
        if ((!avoid || strcmp(out,avoid)) && !md_network_interface_find(n,0,out)) return 0;
    }
    return -ENFILE;
}
static int change_link(struct md_network_namespace *n,const struct nlmsghdr *h,const struct link_change *c,
        struct md_network_namespace *target,struct md_network_namespace *peer_target) {
    struct md_network_interface *link=md_network_interface_find(n,c->index,c->name);
    if (h->nlmsg_type==RTM_DELLINK) return link ? md_network_interface_remove(n,link->info.index) : -ENODEV;
    const unsigned mutable_flags=IFF_UP|IFF_MULTICAST|IFF_PROMISC|IFF_ALLMULTI;
    if ((c->change&~mutable_flags) && (!link || ((link->info.flags^c->flags)&c->change&~mutable_flags))) return -EOPNOTSUPP;
    if (c->has_mtu && (c->mtu<68 || c->mtu>65535)) return -EOPNOTSUPP;
    if (c->has_peer_mtu && (c->peer_mtu<68 || c->peer_mtu>65535)) return -EINVAL;
    if ((c->has_address && ((c->address[0]&1) || !memcmp(c->address,"\0\0\0\0\0\0",6)))
            || (c->has_peer_address && ((c->peer_address[0]&1) || !memcmp(c->peer_address,"\0\0\0\0\0\0",6))))
        return -EADDRNOTAVAIL;
    if (c->has_address && link && link->kind==MD_LINK_LOOPBACK) return -EOPNOTSUPP;
    if (c->has_master && c->master) {
        struct md_network_interface *master=md_network_interface_find(n,c->master,NULL);
        if (!master || master->kind!=MD_LINK_BRIDGE) return -EINVAL;
    }
    if ((c->has_hairpin || c->has_isolated) && (!link || link->kind!=MD_LINK_VETH
            || !(c->has_master ? c->master : link->master))) return -EINVAL;
    int creating=h->nlmsg_type==RTM_NEWLINK && (h->nlmsg_flags&NLM_F_CREATE);
    if (link && creating && (h->nlmsg_flags&NLM_F_EXCL)) return -EEXIST;
    if (!link) {
        if (!creating) return -ENODEV;
        if (!c->kind || c->index || target) return -EOPNOTSUPP;
        struct md_network_namespace *peer_space=peer_target ? peer_target : n;
        char generated[IFNAMSIZ];
        const char *name=c->name;
        int error=0;
        if (!name) {
            error=automatic_name(n,c->kind==MD_LINK_VETH ? "veth" : "br",peer_space==n ? c->peer_name : NULL,generated);
            if (error) return error;
            name=generated;
        }
        const char *peer_name=c->peer_name;
        char generated_peer[IFNAMSIZ];
        if (c->kind==MD_LINK_VETH && !peer_name) {
            error=automatic_name(peer_space,"veth",peer_space==n ? name : NULL,generated_peer);
            if (error) return error;
            peer_name=generated_peer;
        }
        if (peer_name && peer_space==n && !strcmp(peer_name,name)) return -EINVAL;
        if (peer_name && md_network_interface_find(peer_space,0,peer_name)) return -EEXIST;
        error=md_network_interface_reserve(n,c->kind==MD_LINK_VETH && peer_space==n ? 2 : 1);
        if (!error && peer_space!=n) error=md_network_interface_reserve(peer_space,1);
        unsigned index=0,peer=0;
        if (!error) error=md_network_interface_add(n,c->kind,name,&index);
        if (error) return error;
        if (c->kind==MD_LINK_VETH) {
            error=md_network_interface_add(peer_space,MD_LINK_VETH,peer_name,&peer);
            if (error) { md_network_interface_remove(n,index); return error; }
            struct md_network_interface *peer_link=md_network_interface_find(peer_space,peer,NULL);
            peer_link->peer=index;
            if (c->has_peer_tx_queue_length)
                peer_link->info.tx_queue_length=c->peer_tx_queue_length;
            if (c->has_peer_mtu) peer_link->info.mtu=c->peer_mtu;
            if (c->has_peer_address) memcpy(peer_link->info.address,c->peer_address,6);
        }
        link=md_network_interface_find(n,index,NULL); link->peer=peer;
    } else if (peer_target || (c->kind && c->kind!=link->kind)) return -EOPNOTSUPP;
    if ((target && target!=n) || (c->name && strcmp(c->name,link->info.name))) {
        unsigned index=link->info.index;
        int error=md_network_interface_move(n,index,target ? target : n,c->name);
        if (error) return error;
        link=md_network_interface_find(target ? target : n,index,NULL);
    }
    if (target && c->has_master) return -EOPNOTSUPP;
    int was_up=!!(link->info.flags&IFF_UP), had_ipv6_mtu=link->info.mtu>=1280;
    link->info.flags=(link->info.flags&~c->change)|(c->flags&c->change);
    if (c->has_master && link->master!=c->master) {
        link->master=c->master; link->hairpin=link->isolated=0;
    }
    if (c->has_mtu) {
        if (link->ipv6.mtu==link->info.mtu || link->ipv6.mtu>c->mtu) link->ipv6.mtu=c->mtu;
        link->info.mtu=c->mtu;
    }
    if (c->has_addrgen) link->ipv6.addr_gen_mode=c->addrgen;
    if (c->has_tx_queue_length) link->info.tx_queue_length=c->tx_queue_length;
    if (c->has_address) memcpy(link->info.address,c->address,6);
    if (c->has_hairpin) link->hairpin=c->hairpin;
    if (c->has_isolated) link->isolated=c->isolated;
    if (c->has_forward_delay) link->bridge_forward_delay=c->forward_delay;
    struct md_network_namespace *owner=target ? target : n;
    if ((was_up && !(link->info.flags&IFF_UP)) || (had_ipv6_mtu && link->info.mtu<1280))
        md_network_interface_ipv6_flush(owner,link);
    if (!was_up || (!had_ipv6_mtu && link->info.mtu>=1280)) md_network_interface_up(link);
    return 0;
}
struct topology_checkpoint {
    struct md_network_namespace *space;
    struct md_network_interface *links;
    unsigned count,route_count,nexthop_count;
    struct md_network_route_entry *routes;
    struct md_network_nexthop *nexthops;
    unsigned char states[256];
};
static int checkpoint(struct topology_checkpoint *saved,struct md_network_namespace *n) {
    if (!n) return 0;
    struct md_network_interface *links=n->count ? malloc(n->count*sizeof(*links)) : NULL;
    if (n->count && !links) return -ENOMEM;
    struct md_network_route_entry *routes=n->route_count ? malloc(n->route_count*sizeof(*routes)) : NULL;
    if (n->route_count && !routes) { free(links); return -ENOMEM; }
    struct md_network_nexthop *nexthops=n->nexthop_count ? malloc(n->nexthop_count*sizeof(*nexthops)) : NULL;
    if (n->nexthop_count && !nexthops) { free(links); free(routes); return -ENOMEM; }
    if (n->count) memcpy(links,n->links,n->count*sizeof(*links));
    if (n->route_count) memcpy(routes,n->routes,n->route_count*sizeof(*routes));
    if (n->nexthop_count) memcpy(nexthops,n->nexthops,n->nexthop_count*sizeof(*nexthops));
    *saved=(struct topology_checkpoint){.space=n,.links=links,.count=n->count,
        .routes=routes,.route_count=n->route_count,.nexthops=nexthops,.nexthop_count=n->nexthop_count};
    if (n->listeners) for (unsigned i=0;i<n->count;++i)
        saved->states[i]=md_network_interface_snapshot(n,&n->links[i]).state;
    return 0;
}
static void checkpoint_finish(struct topology_checkpoint *saved,int commit) {
    struct md_network_namespace *n=saved->space;
    if (!n) return;
    if (!commit) {
        free(n->links); n->links=saved->links; saved->links=NULL;
        n->count=n->capacity=saved->count;
        n->route_count=saved->route_count;
        if (n->route_count) memcpy(n->routes,saved->routes,n->route_count*sizeof(*n->routes));
        n->nexthop_count=saved->nexthop_count;
        if (n->nexthop_count) memcpy(n->nexthops,saved->nexthops,n->nexthop_count*sizeof(*n->nexthops));
    }
    free(saved->links);
    free(saved->routes);
    free(saved->nexthops);
}
static const struct md_network_interface *snapshot_link(const struct md_network_interface *links,
        unsigned count,unsigned index) {
    for (unsigned i=0;i<count;++i) if (links[i].info.index==index) return &links[i];
    return NULL;
}
static unsigned notify(struct md_network_namespace *n,const struct md_network_interface *link,unsigned type,
        const struct md_network_ip_assignment *address,const struct md_network_route_entry *route) {
    union { struct nlmsghdr alignment; unsigned char bytes[1024]; } packet;
    struct md_network_message out={.data=packet.bytes,.capacity=sizeof(packet)};
    const struct nlmsghdr request={0};
    unsigned group=address ? (address->address.family==AF_INET6 ? RTNLGRP_IPV6_IFADDR : RTNLGRP_IPV4_IFADDR)
        : route ? (route->destination.family==AF_INET6 ? RTNLGRP_IPV6_ROUTE : RTNLGRP_IPV4_ROUTE) : RTNLGRP_LINK;
    int r=address ? md_network_address_reply(&out,&request,0,0,link,address)
        : route ? md_network_route_reply(&out,&request,0,0,link,route,NULL)
        : link_reply(&out,&request,0,0,n,link,AF_UNSPEC);
    if (!r) {
        ((struct nlmsghdr *)packet.bytes)->nlmsg_type=type;
        return md_network_broadcast(n,group,packet.bytes,out.size);
    }
    return 0;
}
void md_network_route_link_created(struct md_network_namespace *n,unsigned index) {
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    if (link) notify(n,link,RTM_NEWLINK,NULL,NULL);
    md_network_forward_changed(n->fabric);
}
void md_network_route_link_removed(struct md_network_namespace *n,unsigned index) {
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    if (!link) return;
    for (unsigned i=0;i<link->address_count;++i) notify(n,link,RTM_DELADDR,&link->addresses[i],NULL);
    for (unsigned i=0;i<n->route_count;++i)
        if (n->routes[i].interface==index) notify(n,link,RTM_DELROUTE,NULL,&n->routes[i]);
    notify(n,link,RTM_DELLINK,NULL,NULL);
    md_network_interface_remove(n,index);
    md_network_forward_changed(n->fabric);
}
static int assignment_present(const struct md_network_interface *link,const struct md_network_ip_assignment *a,int identity) {
    for (unsigned i=0;link && i<link->address_count;++i) {
        const struct md_network_ip_assignment *b=&link->addresses[i];
        if (md_ip_equal(a->address,b->address) && (identity || (a->prefix==b->prefix && a->flags==b->flags
                && a->scope==b->scope && a->route_metric==b->route_metric
                && a->preferred_until==b->preferred_until && a->valid_until==b->valid_until))) return 1;
    }
    return 0;
}
static unsigned notify_addresses(struct md_network_namespace *n,const struct md_network_interface *from,
        const struct md_network_interface *to,int removed) {
    unsigned published=0;
    for (unsigned i=0;i<from->address_count;++i) {
        const struct md_network_ip_assignment *a=&from->addresses[i];
        if (!assignment_present(to,a,removed))
            published+=notify(n,from,removed ? RTM_DELADDR : RTM_NEWADDR,a,NULL);
        if (md_network_address_has_route(a) && md_network_connected_index(from,a)==(int)i
                && md_network_connected_index(to,a)<0) {
            struct md_network_route_entry route=md_network_connected_route(from,a);
            published+=notify(n,from,removed ? RTM_DELROUTE : RTM_NEWROUTE,NULL,&route);
        }
    }
    return published;
}
static int route_present(const struct md_network_route_entry *routes,unsigned count,const struct md_network_route_entry *a) {
    for (unsigned i=0;i<count;++i) {
        const struct md_network_route_entry *b=&routes[i];
        if (md_ip_equal(a->destination,b->destination) && md_ip_equal(a->gateway,b->gateway)
                && md_ip_equal(a->source,b->source) && !memcmp(&a->metrics,&b->metrics,sizeof(a->metrics))
                && a->prefix==b->prefix && a->interface==b->interface && a->metric==b->metric
                && a->scope==b->scope && a->protocol==b->protocol && a->nexthop==b->nexthop
                && a->valid_until==b->valid_until && a->preference==b->preference) return 1;
    }
    return 0;
}
static int64_t earlier(int64_t a,int64_t b) { return !b || (a && a<b) ? a : b; }
static void refresh_deadline(struct md_network_namespace *n) {
    int64_t next=0;
    for (unsigned i=0;i<n->count;++i) for (unsigned j=0;j<n->links[i].address_count;++j) {
        const struct md_network_ip_assignment *a=&n->links[i].addresses[j];
        next=earlier(next,a->valid_until);
        if (!(a->flags&IFA_F_DEPRECATED)) next=earlier(next,a->preferred_until);
        if (n->links[i].ipv6.use_tempaddr) next=earlier(next,a->regenerate_at);
    }
    for (unsigned i=0;i<n->route_count;++i) next=earlier(next,n->routes[i].valid_until);
    n->configuration_deadline=next;
}
static int64_t fabric_deadline(struct md_network_fabric *fabric) {
    int64_t next=0;
    for (const struct md_network_namespace *n=fabric->spaces;n;n=n->next)
        next=earlier(next,n->configuration_deadline);
    return fabric->configuration_deadline=next;
}
int64_t md_network_route_expire(struct md_network_fabric *fabric,int64_t now) {
    if (!fabric->configuration_deadline) return INT64_MAX;
    if (fabric->configuration_deadline>now) return fabric->configuration_deadline;
    for (struct md_network_namespace *n=fabric->spaces;n;n=n->next) {
        if (!n->configuration_deadline || n->configuration_deadline>now) continue;
        for (unsigned i=0;i<n->count;++i) {
            struct md_network_interface *link=&n->links[i];
            struct md_network_interface before=*link;
            md_network_address_refresh(n,link,now);
            notify_addresses(n,&before,link,1);
            notify_addresses(n,link,&before,0);
        }
        for (unsigned i=0;i<n->route_count;) {
            struct md_network_route_entry *r=&n->routes[i];
            if (!r->valid_until || r->valid_until>now) { ++i; continue; }
            notify(n,md_network_interface_find(n,r->interface,NULL),RTM_DELROUTE,NULL,r);
            memmove(r,r+1,(--n->route_count-i)*sizeof(*r));
        }
        refresh_deadline(n);
    }
    md_network_forward_changed(fabric);
    int64_t next=fabric_deadline(fabric);
    return next ? next : INT64_MAX;
}
static unsigned notify_changes(const struct topology_checkpoint *saved) {
    struct md_network_namespace *n=saved->space;
    if (!n || !n->listeners) return 0;
    unsigned published=0;
    for (unsigned pass=0;pass<2;++pass) {
        const struct md_network_nexthop *from=pass ? n->nexthops : saved->nexthops;
        unsigned count=pass ? n->nexthop_count : saved->nexthop_count;
        const struct md_network_nexthop *to=pass ? saved->nexthops : n->nexthops;
        unsigned other=pass ? saved->nexthop_count : n->nexthop_count;
        for (unsigned i=0;i<count;++i) {
            unsigned j=0; while (j<other && to[j].id!=from[i].id) ++j;
            if (j<other && (!pass || !memcmp(&to[j],&from[i],sizeof(*from)))) continue;
            unsigned char packet[256]; const struct nlmsghdr request={0};
            struct md_network_message out={.data=packet,.capacity=sizeof(packet)};
            if (!md_network_nexthop_reply(&out,&request,0,0,&from[i])) {
                ((struct nlmsghdr *)packet)->nlmsg_type=pass ? RTM_NEWNEXTHOP : RTM_DELNEXTHOP;
                published+=md_network_broadcast(n,RTNLGRP_NEXTHOP,packet,out.size);
            }
        }
    }
    for (unsigned i=0;i<saved->count;++i) {
        const struct md_network_interface *old=&saved->links[i];
        const struct md_network_interface *now=snapshot_link(n->links,n->count,old->info.index);
        published+=notify_addresses(n,old,now,1);
        if (!now) published+=notify(n,old,RTM_DELLINK,NULL,NULL);
    }
    for (unsigned i=0;i<n->count;++i) {
        const struct md_network_interface *now=&n->links[i];
        const struct md_network_interface *old=snapshot_link(saved->links,saved->count,now->info.index);
        int state_changed=old && saved->states[old-saved->links]!=md_network_interface_snapshot(n,now).state;
        if (!old || memcmp(&old->info,&now->info,sizeof(now->info)) || old->kind!=now->kind
                || old->peer!=now->peer || old->master!=now->master || state_changed
                || old->hairpin!=now->hairpin || old->isolated!=now->isolated
                || old->ipv6.addr_gen_mode!=now->ipv6.addr_gen_mode)
            published+=notify(n,now,RTM_NEWLINK,NULL,NULL);
        published+=notify_addresses(n,now,old,0);
    }
    for (unsigned i=0;i<saved->route_count;++i) if (!route_present(n->routes,n->route_count,&saved->routes[i]))
        published+=notify(n,snapshot_link(saved->links,saved->count,saved->routes[i].interface),RTM_DELROUTE,NULL,&saved->routes[i]);
    for (unsigned i=0;i<n->route_count;++i) if (!route_present(saved->routes,saved->route_count,&n->routes[i]))
        published+=notify(n,snapshot_link(n->links,n->count,n->routes[i].interface),RTM_NEWROUTE,NULL,&n->routes[i]);
    return published;
}
int md_network_route_query(unsigned type) {
    switch (type) {
    case RTM_GETLINK: case RTM_GETADDR: case RTM_GETROUTE: case RTM_GETRULE:
    case RTM_GETNEIGH: case RTM_GETQDISC: case RTM_GETTCLASS: case RTM_GETTFILTER:
    case RTM_GETNEXTHOP: return 1;
    default: return 0;
    }
}
int md_network_route_privacy(struct md_network_namespace *n,uint64_t scope,unsigned value) {
    struct topology_checkpoint saved={0};
    int error=checkpoint(&saved,n);
    if (error) return error;
    int64_t now=md_network_lifetime_now();
    for (unsigned i=0;!error && i<n->count;++i) {
        struct md_network_interface *link=&n->links[i];
        if (scope!=MD_NET_SCOPE_ALL && scope!=link->policy_id) continue;
        link->ipv6.use_tempaddr=value;
        error=md_network_address_refresh(n,link,now);
    }
    if (!error) {
        if (scope==MD_NET_SCOPE_ALL) n->all6.use_tempaddr=value;
        notify_changes(&saved); refresh_deadline(n); fabric_deadline(n->fabric);
        md_network_forward_changed(n->fabric);
    }
    checkpoint_finish(&saved,!error);
    return error;
}
int md_network_route_disable_ipv6(struct md_network_namespace *n,uint64_t scope,unsigned disabled) {
    struct topology_checkpoint saved={0};
    int error=checkpoint(&saved,n);
    if (error) return error;
    for (unsigned i=0;i<n->count;++i) {
        struct md_network_interface *link=&n->links[i];
        if ((scope!=MD_NET_SCOPE_ALL && link->policy_id!=scope) || link->ipv6.disabled==disabled) continue;
        link->ipv6.disabled=disabled;
        if (!disabled) { md_network_interface_up(link); continue; }
        md_network_interface_ipv6_flush(n,link);
    }
    if (scope==MD_NET_SCOPE_ALL) n->all6.disabled=disabled;
    notify_changes(&saved); checkpoint_finish(&saved,1);
    refresh_deadline(n); fabric_deadline(n->fabric); return 0;
}
static int policy_dump(struct md_network_message *out,const struct nlmsghdr *h,uint32_t port,int dump) {
    if (!dump) return -EOPNOTSUPP;
    if (h->nlmsg_len<NLMSG_LENGTH(sizeof(struct rtgenmsg))) return -EINVAL;
    unsigned family=((const struct rtgenmsg *)NLMSG_DATA(h))->rtgen_family;
    if (family!=AF_UNSPEC && family!=AF_INET && family!=AF_INET6 && family!=AF_BRIDGE) return -EAFNOSUPPORT;
    /* The fabric routes local addresses before its main table. It has no
     * neighbor cache, programmable policy rules or
     * traffic-control objects. Mutations of these catalogues remain rejected. */
    if (h->nlmsg_type!=RTM_GETRULE) return 0;
    if (family==AF_BRIDGE) return -EAFNOSUPPORT;
    const unsigned char tables[]={RT_TABLE_LOCAL,RT_TABLE_MAIN,RT_TABLE_DEFAULT};
    const uint32_t priority[]={0,32766,32767};
    int error=0;
    const unsigned families[]={AF_INET,AF_INET6};
    for (unsigned f=0;!error && f<2;++f) for (unsigned i=0;!error && i<(f ? 2U : 3U);++i) {
        if (family && family!=families[f]) continue;
        struct fib_rule_hdr rule={.family=families[f],.table=tables[i],.action=FR_ACT_TO_TBL};
        error=md_network_message_begin(out,h,port,RTM_NEWRULE,1,&rule,sizeof(rule));
        const unsigned char protocol=RTPROT_KERNEL;
        if (!error) error=md_network_message_attribute(out,FRA_PROTOCOL,&protocol,sizeof(protocol));
        if (!error && priority[i]) error=md_network_message_attribute(out,FRA_PRIORITY,&priority[i],sizeof(priority[i]));
    }
    return error;
}
static int strict_query(const struct nlmsghdr *h,int dump) {
    size_t body;
    switch (h->nlmsg_type) {
    case RTM_GETLINK: body=sizeof(struct ifinfomsg); break;
    case RTM_GETADDR: body=sizeof(struct ifaddrmsg); break;
    case RTM_GETROUTE: body=sizeof(struct rtmsg); break;
    case RTM_GETRULE: body=sizeof(struct fib_rule_hdr); break;
    case RTM_GETNEIGH: body=sizeof(struct ndmsg); break;
    case RTM_GETNEXTHOP: body=sizeof(struct nhmsg); break;
    default: body=sizeof(struct tcmsg); break;
    }
    if (h->nlmsg_len<NLMSG_LENGTH(body)) return -EINVAL;
    if (h->nlmsg_type==RTM_GETLINK) {
        const struct ifinfomsg *m=NLMSG_DATA(h);
        if (m->__ifi_pad || m->ifi_type || m->ifi_flags || m->ifi_change || (dump && m->ifi_index)) return -EINVAL;
        /* Non-dump lookup and extension masks use the normal typed parser.
         * Filters not represented by our link catalogue must not broaden dumps. */
        if (dump) {
            struct attrs attrs;
            int r=attributes(IFLA_RTA(m),h->nlmsg_len-NLMSG_LENGTH(body),&attrs,IFLA_MAX);
            if (r) return r;
            for (unsigned i=1;i<=IFLA_MAX;++i)
                if (attrs.v[i] && i!=IFLA_EXT_MASK && i!=IFLA_MASTER) return -EOPNOTSUPP;
        }
    } else if (dump && h->nlmsg_type==RTM_GETADDR) {
        const struct ifaddrmsg *m=NLMSG_DATA(h);
        if (m->ifa_prefixlen || m->ifa_flags || m->ifa_scope) return -EINVAL;
        if (h->nlmsg_len!=NLMSG_LENGTH(body)) return -EOPNOTSUPP;
    } else if (dump && (h->nlmsg_type==RTM_GETROUTE || h->nlmsg_type==RTM_GETNEXTHOP)) {
        /* routes() validates the header and typed table selector together. */
        return 0;
    } else if (dump) {
        /* These catalogues currently support family selection only. Preserve
         * an explicit unsupported result for richer selection predicates. */
        const unsigned char *bytes=NLMSG_DATA(h);
        for (size_t i=1;i<body;++i) if (bytes[i]) return -EOPNOTSUPP;
        if (h->nlmsg_len!=NLMSG_LENGTH(body)) return -EOPNOTSUPP;
    }
    return 0;
}
long md_network_route(struct md_network_namespace *n,const struct md_identity *identity,
        const struct nlmsghdr *h,size_t size,uint32_t port,unsigned options,const struct md_network_target *resolver,
        void *data,size_t capacity,const struct md_network_publication *publication) {
    if (size<sizeof(*h) || h->nlmsg_len!=size || !(h->nlmsg_flags&NLM_F_REQUEST)) return -EINVAL;
    int query=md_network_route_query(h->nlmsg_type);
    /* rtnetlink accepts either ROOT or MATCH, not only their DUMP union. */
    int dump=query && (h->nlmsg_flags&NLM_F_DUMP);
    int strict=!!(options&MD_NETWORK_ROUTE_STRICT);
    int error=query && strict ? strict_query(h,dump) : 0;
    const struct nlmsghdr *original=h;
    struct { struct nlmsghdr header; struct ifinfomsg body; } expanded={0};
    if (!strict && dump && (h->nlmsg_type==RTM_GETLINK || h->nlmsg_type==RTM_GETADDR || h->nlmsg_type==RTM_GETROUTE)) {
        size_t payload=h->nlmsg_type==RTM_GETLINK ? sizeof(struct ifinfomsg)
            : h->nlmsg_type==RTM_GETADDR ? sizeof(struct ifaddrmsg) : sizeof(struct rtmsg);
        /* Legacy rtnetlink dumps carry only rtgenmsg's family (glibc pads it
         * to four bytes). Mutation and lookup requests retain strict parsing. */
        if (size>=NLMSG_LENGTH(sizeof(struct rtgenmsg)) && size<NLMSG_LENGTH(payload)) {
            expanded.header=*h;
            expanded.header.nlmsg_len=NLMSG_LENGTH(payload);
            expanded.body.ifi_family=((const struct rtgenmsg *)NLMSG_DATA(h))->rtgen_family;
            h=&expanded.header;
        }
    }
    if (!query && !md_identity_capable_in(identity,n->owner,CAP_NET_ADMIN)) error=-EPERM;
    struct md_network_message out={.data=data,.capacity=capacity};
    struct link_change change={0}; struct attrs attrs={0};
    struct md_network_namespace *target=NULL,*peer_target=NULL;
    int link=h->nlmsg_type==RTM_GETLINK || h->nlmsg_type==RTM_NEWLINK || h->nlmsg_type==RTM_SETLINK || h->nlmsg_type==RTM_DELLINK;
    if (!error && link) error=parse_link(h,&change,&attrs);
    if (!error && link && change.namespace.kind) {
        error=resolver && resolver->resolve ? resolver->resolve(resolver->context,change.namespace,&target) : -EOPNOTSUPP;
        if (!error && (!md_identity_capable_in(identity,target->owner,CAP_NET_ADMIN))) error=-EPERM;
        if (!error && target->fabric!=n->fabric) error=-EXDEV;
        if (!error && (query || change.has_master)) error=-EOPNOTSUPP;
    }
    if (!error && link && change.peer_namespace.kind) {
        error=resolver && resolver->resolve ? resolver->resolve(resolver->context,change.peer_namespace,&peer_target) : -EOPNOTSUPP;
        if (!error && !md_identity_capable_in(identity,peer_target->owner,CAP_NET_ADMIN)) error=-EPERM;
        if (!error && peer_target->fabric!=n->fabric) error=-EXDEV;
        if (!error && (query || target)) error=-EOPNOTSUPP;
    }
    pthread_mutex_lock(&n->fabric->lock);
    /* A link mutation touches its owner and at most one peer/move target.
     * Queries and socket data IO allocate no topology journal. The lock keeps
     * tentative edits invisible until their first notification or response
     * enters the native queue. */
    struct topology_checkpoint saved[2]={0};
    unsigned next_index=n->fabric->next_index;
    if (!error && !query) {
        struct md_network_namespace *other=peer_target ? peer_target : target;
        if (!other && link) {
            struct md_network_interface *found=md_network_interface_find(n,change.index,change.name);
            if (found) other=md_network_interface_owner(n->fabric,found->peer);
        }
        error=checkpoint(&saved[0],n);
        if (!error && other && other!=n) error=checkpoint(&saved[1],other);
    }
    if (!error) {
        if (h->nlmsg_type==RTM_GETLINK) {
            if (dump) {
                for (unsigned i=0;!error && i<n->count;++i)
                    if ((!change.master || n->links[i].master==change.master)
                            && (change.family!=AF_BRIDGE || n->links[i].master))
                        error=link_reply(&out,h,port,1,n,&n->links[i],change.family);
            } else {
                struct md_network_interface *found=md_network_interface_find(n,change.index,change.name);
                error=found && (change.family!=AF_BRIDGE || found->master)
                    ? link_reply(&out,h,port,0,n,found,change.family) : -ENODEV;
            }
        } else if (link) error=change_link(n,h,&change,target,peer_target);
        else if (h->nlmsg_type==RTM_GETADDR || h->nlmsg_type==RTM_NEWADDR || h->nlmsg_type==RTM_DELADDR)
            error=md_network_addresses(n,h,port,dump,&out);
        else if (h->nlmsg_type==RTM_GETROUTE || h->nlmsg_type==RTM_NEWROUTE || h->nlmsg_type==RTM_DELROUTE)
            error=md_network_routes(n,h,port,dump,strict,&out);
        else if (h->nlmsg_type==RTM_GETNEXTHOP || h->nlmsg_type==RTM_NEWNEXTHOP || h->nlmsg_type==RTM_DELNEXTHOP)
            error=md_network_nexthops(n,h,port,dump,&out);
        else if (query) error=policy_dump(&out,h,port,dump);
        else error=-EOPNOTSUPP;
    }
    if (!error && !query && link && (change.has_hairpin || change.has_isolated))
        error=md_network_endpoint_policy_update(n,n->filter);
    if (!error && !query && (h->nlmsg_type==RTM_NEWROUTE || h->nlmsg_type==RTM_DELROUTE
            || h->nlmsg_type==RTM_NEWNEXTHOP || h->nlmsg_type==RTM_DELNEXTHOP || (link && change.has_mtu)))
        error=md_network_endpoint_transport_update(n);
    if (error) out.size=0;
    int encoded=md_network_message_done(&out,original,port,error,dump,options&MD_NETWORK_ROUTE_CAPPED_ACK);
    unsigned published=0;
    /* rtnetlink announces changes before ACK, while the originating request
     * is still pending in network managers. Once any observer can see a change
     * (or queue overflow), a lost ACK cannot roll the topology back. */
    if (!error && !encoded && !query)
        for (unsigned i=0;i<2;++i) published+=notify_changes(&saved[i]);
    if (!encoded && out.size && publication)
        encoded=publication->publish(publication->context,out.data,out.size);
    int commit=!error && (!encoded || published);
    if (commit && !query) {
        for (unsigned i=0;i<2;++i) {
            if (saved[i].space) refresh_deadline(saved[i].space);
        }
        fabric_deadline(n->fabric);
        md_network_forward_changed(n->fabric);
    }
    for (unsigned i=0;i<2;++i) checkpoint_finish(&saved[i],commit);
    if (!query && !commit) n->fabric->next_index=next_index;
    pthread_mutex_unlock(&n->fabric->lock);
    md_network_namespace_release(target);
    md_network_namespace_release(peer_target);
    return encoded ? encoded : (long)out.size;
}
static int legacy_request(struct md_network_namespace *n,const struct md_identity *id,struct nlmsghdr *header) {
    union { struct nlmsghdr align; unsigned char bytes[128]; } reply={0};
    long size=md_network_route(n,id,header,header->nlmsg_len,0,MD_NETWORK_ROUTE_CAPPED_ACK,
        NULL,reply.bytes,sizeof(reply),NULL);
    if(size<0) return (int)size;
    if(size<(long)NLMSG_LENGTH(sizeof(struct nlmsgerr)) || reply.align.nlmsg_type!=NLMSG_ERROR) return -EPROTO;
    return ((struct nlmsgerr *)NLMSG_DATA(&reply.align))->error;
}
static int legacy_link_change(struct md_network_namespace *n,const struct md_identity *id,
        unsigned index,const char *name,unsigned flags,unsigned change,
        unsigned attribute,const void *value,size_t length) {
    union { struct nlmsghdr align; unsigned char bytes[128]; } request={0};
    struct nlmsghdr *header=&request.align;
    *header=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(sizeof(struct ifinfomsg)),
        .nlmsg_type=RTM_SETLINK,.nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK};
    struct ifinfomsg *link=NLMSG_DATA(header);
    *link=(struct ifinfomsg){.ifi_index=(int)index,.ifi_flags=flags,.ifi_change=change};
    struct md_network_message message={.data=request.bytes,.size=header->nlmsg_len,
        .capacity=sizeof(request),.message=header};
    int error=name ? md_network_message_attribute(&message,IFLA_IFNAME,name,strlen(name)+1) : 0;
    if (!error && attribute) error=md_network_message_attribute(&message,attribute,value,length);
    if (error) return error;
    return legacy_request(n,id,header);
}
static int mask_prefix(uint32_t mask,unsigned *prefix) {
    uint32_t host=ntohl(mask),inverse=~host;
    if(inverse&(inverse+1)) return -EINVAL;
    *prefix=(unsigned)__builtin_popcount(host); return 0;
}
int md_network_route_address_ioctl(struct md_network_namespace *n,const struct md_identity *id,unsigned command,struct ifreq *r) {
    int writing=command==SIOCSIFADDR || command==SIOCSIFNETMASK;
    if(writing && !md_identity_capable_in(id,n->owner,CAP_NET_ADMIN)) return -EPERM;
    struct sockaddr_in address; memcpy(&address,&r->ifr_addr,sizeof(address));
    if(writing && address.sin_family!=AF_INET) return -EAFNOSUPPORT;
    unsigned prefix=0;
    int error=command==SIOCSIFNETMASK ? mask_prefix(address.sin_addr.s_addr,&prefix) : 0;
    if(error) return error;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *link=md_network_interface_find(n,0,r->ifr_name);
    if(!link) { pthread_mutex_unlock(&n->fabric->lock); return -ENODEV; }
    unsigned at=0; while(at<link->address_count && link->addresses[at].address.family!=AF_INET) ++at;
    if(!writing) {
        if(at==link->address_count) error=-EADDRNOTAVAIL;
        else {
            struct md_network_ip_assignment *a=&link->addresses[at];
            address=(struct sockaddr_in){.sin_family=AF_INET,.sin_addr.s_addr=command==SIOCGIFADDR
                ? a->address.v4 : htonl(a->prefix ? UINT32_MAX<<(32-a->prefix) : 0)};
            memcpy(&r->ifr_addr,&address,sizeof(address));
        }
    } else {
        struct md_network_interface before=*link;
        if(command==SIOCSIFNETMASK) {
            if(at==link->address_count) error=-EADDRNOTAVAIL;
            else link->addresses[at].prefix=prefix;
        } else {
            uint32_t host=ntohl(address.sin_addr.s_addr);
            if((host>>28)==14 || host==UINT32_MAX) error=-EINVAL;
            else {
                if(at<link->address_count) {
                    memmove(&link->addresses[at],&link->addresses[at+1],(link->address_count-at-1)*sizeof(link->addresses[0]));
                    --link->address_count;
                }
                if(host) error=md_network_interface_address(link,(struct md_network_ip_assignment){
                    .address=md_ip_v4(address.sin_addr.s_addr),.prefix=host<0x80000000 ? 8 : host<0xc0000000 ? 16 : 24,
                    .scope=(host>>24)==127 ? RT_SCOPE_HOST : RT_SCOPE_UNIVERSE,.flags=IFA_F_PERMANENT},0,0);
            }
        }
        if(!error) error=md_network_endpoint_transport_update(n);
        if(error) *link=before;
        else {
            notify_addresses(n,&before,link,1); notify_addresses(n,link,&before,0);
            md_network_forward_changed(n->fabric);
        }
    }
    pthread_mutex_unlock(&n->fabric->lock); return error;
}
int md_network_route_legacy(struct md_network_namespace *n,const struct md_identity *id,unsigned command,
        const struct md_network_route_ioctl *r) {
    const struct rtentry *route=&r->route;
    if(route->rt_flags&~(RTF_UP|RTF_GATEWAY|RTF_HOST)) return -EOPNOTSUPP;
    if(route->rt_dst.sa_family!=AF_INET || (route->rt_flags&RTF_GATEWAY && route->rt_gateway.sa_family!=AF_INET)
            || (route->rt_genmask.sa_family && route->rt_genmask.sa_family!=AF_INET)) return -EAFNOSUPPORT;
    if(!memchr(r->device,0,sizeof(r->device))) return -ENODEV;
    if(route->rt_metric<0) return -EINVAL;
    struct sockaddr_in destination,gateway,mask;
    memcpy(&destination,&route->rt_dst,sizeof(destination)); memcpy(&gateway,&route->rt_gateway,sizeof(gateway));
    memcpy(&mask,&route->rt_genmask,sizeof(mask));
    unsigned prefix=32;
    int error=route->rt_flags&RTF_HOST ? 0 : mask_prefix(mask.sin_addr.s_addr,&prefix);
    if(error) return error;
    unsigned index=0;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *link=r->device[0] ? md_network_interface_find(n,0,r->device)
        : md_network_route_find(n,md_ip_v4(route->rt_flags&RTF_GATEWAY ? gateway.sin_addr.s_addr : destination.sin_addr.s_addr),NULL);
    if(link) index=link->info.index;
    pthread_mutex_unlock(&n->fabric->lock);
    if(!index) return r->device[0] ? -ENODEV : -ENETUNREACH;
    union { struct nlmsghdr align; unsigned char bytes[192]; } request={0};
    struct nlmsghdr *header=&request.align;
    *header=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg)),
        .nlmsg_type=command==SIOCADDRT ? RTM_NEWROUTE : RTM_DELROUTE,
        .nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK|(command==SIOCADDRT ? NLM_F_CREATE|NLM_F_EXCL : 0)};
    struct rtmsg *m=NLMSG_DATA(header);
    *m=(struct rtmsg){.rtm_family=AF_INET,.rtm_dst_len=prefix,.rtm_table=RT_TABLE_MAIN,
        .rtm_type=RTN_UNICAST,.rtm_protocol=RTPROT_BOOT,.rtm_scope=route->rt_flags&RTF_GATEWAY ? RT_SCOPE_UNIVERSE : RT_SCOPE_LINK};
    struct md_network_message message={.data=request.bytes,.size=header->nlmsg_len,.capacity=sizeof(request),.message=header};
    error=md_network_message_attribute(&message,RTA_OIF,&index,sizeof(index));
    if(!error) error=md_network_message_attribute(&message,RTA_DST,&destination.sin_addr.s_addr,4);
    if(!error && (route->rt_flags&RTF_GATEWAY)) error=md_network_message_attribute(&message,RTA_GATEWAY,&gateway.sin_addr.s_addr,4);
    uint32_t metric=route->rt_metric ? (unsigned)route->rt_metric-1 : 0;
    if(!error && metric) error=md_network_message_attribute(&message,RTA_PRIORITY,&metric,sizeof(metric));
    return error ? error : legacy_request(n,id,header);
}
int md_network_route_set_address(struct md_network_namespace *n,const struct md_identity *id,
        const char *name,const unsigned char address[6]) {
    return legacy_link_change(n,id,0,name,0,0,IFLA_ADDRESS,address,6);
}
int md_network_route_set_flags(struct md_network_namespace *n,const struct md_identity *id,
        const char *name,unsigned flags) {
    unsigned change=IFF_UP|IFF_DEBUG|IFF_NOTRAILERS|IFF_NOARP|IFF_PROMISC|IFF_ALLMULTI
        |IFF_MULTICAST|IFF_PORTSEL|IFF_AUTOMEDIA|IFF_DYNAMIC;
    return legacy_link_change(n,id,0,name,flags,change,0,NULL,0);
}
int md_network_route_set_mtu(struct md_network_namespace *n,const struct md_identity *id,
        const char *name,unsigned mtu) {
    return legacy_link_change(n,id,0,name,0,0,IFLA_MTU,&mtu,sizeof(mtu));
}
int md_network_route_attach_bridge(struct md_network_namespace *n,const struct md_identity *id,
        const char *name,unsigned index) {
    if (!index) return -ENODEV;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *bridge=md_network_interface_find(n,0,name);
    uint32_t master=bridge && bridge->kind==MD_LINK_BRIDGE ? bridge->info.index : 0;
    pthread_mutex_unlock(&n->fabric->lock);
    if (!master) return -ENODEV;
    return legacy_link_change(n,id,index,NULL,0,0,IFLA_MASTER,&master,sizeof(master));
}
int md_network_route_bridge(struct md_network_namespace *n,const struct md_identity *id,
        struct md_network_bridge_ioctl *r) {
    if (!memchr(r->name,0,IFNAMSIZ)) return -ENODEV;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *bridge=md_network_interface_find(n,0,r->name);
    int exists=bridge && bridge->kind==MD_LINK_BRIDGE;
    if (exists && r->args[0]==BRCTL_GET_BRIDGE_INFO) {
        memset(&r->info,0,sizeof(r->info));
        r->info.bridge_forward_delay=bridge->bridge_forward_delay;
        r->info.forward_delay=bridge->bridge_forward_delay;
    }
    pthread_mutex_unlock(&n->fabric->lock);
    if (!exists) return -ENODEV;
    if (r->args[0]==BRCTL_GET_BRIDGE_INFO) return 0;
    if (!md_identity_capable_in(id,n->owner,CAP_NET_ADMIN)) return -EPERM;
    if (r->args[0]!=BRCTL_SET_BRIDGE_FORWARD_DELAY && r->args[0]!=BRCTL_SET_BRIDGE_STP_STATE)
        return -EOPNOTSUPP;
    if (r->args[1]>UINT32_MAX) return -ERANGE;
    union { struct nlmsghdr alignment; unsigned char bytes[256]; } request={0};
    struct nlmsghdr *header=(void *)request.bytes;
    *header=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(sizeof(struct ifinfomsg)),
        .nlmsg_type=RTM_NEWLINK,.nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK};
    struct md_network_message message={.data=request.bytes,.size=header->nlmsg_len,.capacity=sizeof(request),.message=header};
    size_t info,data;
    uint32_t value=(uint32_t)r->args[1];
    int error=md_network_message_attribute(&message,IFLA_IFNAME,r->name,strlen(r->name)+1);
    if (!error) error=md_network_message_nest(&message,IFLA_LINKINFO,&info);
    if (!error) error=md_network_message_attribute(&message,IFLA_INFO_KIND,"bridge",7);
    if (!error) error=md_network_message_nest(&message,IFLA_INFO_DATA,&data);
    if (!error) error=md_network_message_attribute(&message,r->args[0]==BRCTL_SET_BRIDGE_FORWARD_DELAY
        ? IFLA_BR_FORWARD_DELAY : IFLA_BR_STP_STATE,&value,sizeof(value));
    if (!error) error=md_network_message_end_nest(&message,data);
    if (!error) error=md_network_message_end_nest(&message,info);
    return error ? error : legacy_request(n,id,header);
}
