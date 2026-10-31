#define _GNU_SOURCE
#include "network_internal.h"
#include "network_filter.h"
#include "network_forward.h"
#include "network_tun.h"
#include "network_stack.h"
#include "network_route_ip.h"
#include "network_policy.h"
#include "namespace_identity.h"
#include <errno.h>
#include <limits.h>
#include <linux/if_arp.h>
#include <linux/if_addr.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>

struct md_network_interface *md_network_interface_find(struct md_network_namespace *n,unsigned index,const char *name) {
    for (unsigned i=0;i<n->count;++i)
        if ((index && n->links[i].info.index==index) || (!index && name && !strcmp(n->links[i].info.name,name)))
            return &n->links[i];
    return NULL;
}
struct md_network_namespace *md_network_interface_owner(struct md_network_fabric *f,unsigned index) {
    if (index==1) return NULL;
    for (struct md_network_namespace *n=f->spaces;n;n=n->next)
        if (md_network_interface_find(n,index,NULL)) return n;
    return NULL;
}
struct md_ip_address md_network_interface_source(const struct md_network_interface *link,struct md_ip_address target) {
    if (target.family==AF_INET6 && md_ip_multicast(target) && (target.bytes[1]&15)<=2) {
        target=md_ip_any(AF_INET6); target.bytes[0]=0xfe; target.bytes[1]=0x80;
    }
    struct md_ip_address best=md_ip_any(target.family);
    unsigned score=0;
    if (!link) return best;
    for (unsigned i=0;i<link->address_count;++i) {
        const struct md_network_ip_assignment *a=&link->addresses[i];
        if (a->address.family!=target.family || (a->flags&(IFA_F_TENTATIVE|IFA_F_DADFAILED))) continue;
        if (md_ip_linklocal(a->address)!=md_ip_linklocal(target) && !md_ip_multicast(target)) continue;
        unsigned candidate=1+(!(a->flags&IFA_F_DEPRECATED) ? 8 : 0)
            +(md_ip_prefix(md_network_address_destination(a),target,a->prefix) ? 1 : 0)+(md_ip_equal(a->address,target) ? 16 : 0)
            +(a->address.family==AF_INET6 && (!!(a->flags&IFA_F_TEMPORARY)==(link->ipv6.use_tempaddr>1)) ? 2 : 0);
        if (candidate>score) { best=a->address; score=candidate; }
    }
    return best;
}
int md_network_interface_has(const struct md_network_interface *link,struct md_ip_address address) {
    if (md_ip_unspecified(address)) return 0;
    for (unsigned i=0;i<link->address_count;++i)
        if (!(link->addresses[i].flags&(IFA_F_TENTATIVE|IFA_F_DADFAILED)) && (md_ip_equal(link->addresses[i].address,address)
                || (link->kind==MD_LINK_LOOPBACK && address.family==AF_INET
                    && link->addresses[i].address.family==AF_INET && md_ip_is_loopback(address)))) return 1;
    return 0;
}
int md_network_interface_broadcast(const struct md_network_interface *link,struct md_ip_address address) {
    if (address.family!=AF_INET) return 0;
    if (address.v4==INADDR_BROADCAST) return 1;
    if (!(link->info.flags&IFF_BROADCAST)) return 0;
    for (unsigned i=0;i<link->address_count;++i) {
        const struct md_network_ip_assignment *a=&link->addresses[i];
        if (a->address.family!=AF_INET || a->prefix>=31) continue;
        uint32_t mask=a->prefix ? UINT32_MAX<<(32-a->prefix) : 0;
        if (ntohl(address.v4)==(ntohl(a->address.v4)|~mask)) return 1;
    }
    return 0;
}
unsigned md_network_address_type(struct md_network_namespace *n,struct md_ip_address address) {
    if (md_ip_multicast(address)) return RTN_MULTICAST;
    if (address.family==AF_INET && address.v4==INADDR_BROADCAST) return RTN_BROADCAST;
    for (unsigned i=0;i<n->count;++i) {
        if (md_network_interface_has(&n->links[i],address)) return RTN_LOCAL;
        if (md_network_interface_broadcast(&n->links[i],address)) return RTN_BROADCAST;
    }
    return RTN_UNICAST;
}
struct md_ip_address md_network_route_source(struct md_network_namespace *n,const struct md_network_interface *link,
        const struct md_network_route_entry *route,struct md_ip_address target) {
    if (route && !md_ip_unspecified(route->source))
        for (unsigned i=0;i<n->count;++i)
            if (md_network_interface_has(&n->links[i],route->source)) return route->source;
    return md_network_interface_source(link,target);
}
int md_network_interface_address(struct md_network_interface *link,struct md_network_ip_assignment address,int remove,int exclusive) {
    if (md_ip_unspecified(address.address) || address.prefix>md_ip_size(address.address)*8) return -EINVAL;
    for (unsigned i=0;i<link->address_count;++i) if (md_ip_equal(link->addresses[i].address,address.address)) {
        if (remove) {
            if (link->addresses[i].prefix!=address.prefix || (!md_ip_unspecified(address.peer)
                    && !md_ip_equal(link->addresses[i].peer,address.peer))) return -EADDRNOTAVAIL;
            memmove(link->addresses+i,link->addresses+i+1,(--link->address_count-i)*sizeof(*link->addresses));
        } else if (exclusive) return -EEXIST;
        else link->addresses[i]=address;
        return 0;
    }
    if (remove) return -EADDRNOTAVAIL;
    if (link->address_count==MD_NETWORK_MAX_ADDRESSES) return -ENOSPC;
    link->addresses[link->address_count++]=address; return 0;
}
void md_network_interface_up(struct md_network_interface *link) {
    if (!(link->info.flags&IFF_UP)) return;
    if (link->kind==MD_LINK_LOOPBACK) {
        struct md_ip_address a=md_ip_loopback(AF_INET);
        if (!md_network_interface_has(link,a)) md_network_interface_address(link,
            (struct md_network_ip_assignment){.address=a,.prefix=8,.scope=RT_SCOPE_HOST,.flags=IFA_F_PERMANENT},0,0);
        if (link->ipv6.disabled) return;
        a=md_ip_loopback(AF_INET6);
        if (!md_network_interface_has(link,a)) md_network_interface_address(link,
            (struct md_network_ip_assignment){.address=a,.prefix=128,.scope=RT_SCOPE_HOST,.flags=IFA_F_PERMANENT},0,0);
    } else if (!link->ipv6.disabled && !link->ipv6.addr_gen_mode && link->info.mtu>=1280) {
        for (unsigned i=0;i<link->address_count;++i) if (md_ip_linklocal(link->addresses[i].address)) return;
        struct md_ip_address a=md_ip_any(AF_INET6);
        a.bytes[0]=0xfe; a.bytes[1]=0x80;
        memcpy(a.bytes+8,link->info.address,3); a.bytes[8]^=2;
        a.bytes[11]=0xff; a.bytes[12]=0xfe; memcpy(a.bytes+13,link->info.address+3,3);
        md_network_interface_address(link,(struct md_network_ip_assignment){.address=a,.prefix=64,.scope=RT_SCOPE_LINK,.flags=IFA_F_PERMANENT},0,0);
    }
}
void md_network_interface_ipv6_flush(struct md_network_namespace *n,struct md_network_interface *link) {
    for (unsigned a=0;a<link->address_count;) {
        if (link->addresses[a].address.family!=AF_INET6) { ++a; continue; }
        memmove(link->addresses+a,link->addresses+a+1,(--link->address_count-a)*sizeof(*link->addresses));
    }
    for (unsigned r=0;r<n->route_count;) {
        if (n->routes[r].interface!=link->info.index || n->routes[r].destination.family!=AF_INET6) { ++r; continue; }
        memmove(n->routes+r,n->routes+r+1,(--n->route_count-r)*sizeof(*n->routes));
    }
    for (unsigned i=0;i<n->nexthop_count;) {
        if (n->nexthops[i].interface!=link->info.index || n->nexthops[i].gateway.family!=AF_INET6) { ++i; continue; }
        memmove(n->nexthops+i,n->nexthops+i+1,(--n->nexthop_count-i)*sizeof(*n->nexthops));
    }
}
static unsigned link_domain(struct md_network_namespace *n,const struct md_network_interface *link) {
    if (link->kind==MD_LINK_LOOPBACK) return 0;
    if (link->master) return link->master;
    struct md_network_namespace *owner=md_network_interface_owner(n->fabric,link->peer);
    struct md_network_interface *peer=owner ? md_network_interface_find(owner,link->peer,NULL) : NULL;
    if (peer && peer->master) return peer->master;
    return peer && peer->info.index<link->info.index ? peer->info.index : link->info.index;
}
struct md_network_interface *md_network_neighbor(struct md_network_namespace *n,const struct md_network_interface *link,struct md_ip_address ip) {
    unsigned domain=link_domain(n,link);
    if (!domain) return NULL;
    for (struct md_network_namespace *s=n->fabric->spaces;s;s=s->next)
        for (unsigned i=0;i<s->count;++i) {
            struct md_network_interface *other=&s->links[i];
            if (other!=link && (other->info.flags&IFF_UP) && md_network_interface_has(other,ip)
                    && link_domain(s,other)==domain) return other;
        }
    return NULL;
}
int md_network_address_duplicate(struct md_network_namespace *n,const struct md_network_interface *link,struct md_ip_address ip) {
    return md_network_neighbor(n,link,ip)!=NULL;
}
struct md_network_interface *md_network_route_table(struct md_network_namespace *n,struct md_ip_address ip,
        unsigned output,unsigned table,struct md_network_route_entry *result) {
    struct md_network_interface *best=NULL;
    struct md_network_route_entry selected={.destination=md_ip_any(ip.family),.gateway=md_ip_any(ip.family)};
    unsigned prefix=0,metric=UINT32_MAX;
    for (unsigned i=0;(table==RT_TABLE_LOCAL || table==RT_TABLE_MAIN) && i<n->count;++i) {
        struct md_network_interface *l=&n->links[i];
        if (!(l->info.flags&IFF_UP) || (output && output!=l->info.index)
                || (md_ip_is_loopback(ip) && l->kind!=MD_LINK_LOOPBACK)) continue;
        for (unsigned j=0;j<l->address_count;++j) {
            const struct md_network_ip_assignment *a=&l->addresses[j];
            if (a->address.family!=ip.family || (a->flags&(IFA_F_TENTATIVE|IFA_F_DADFAILED))) continue;
            int local=md_ip_equal(a->address,ip)
                || (l->kind==MD_LINK_LOOPBACK && ip.family==AF_INET && md_ip_is_loopback(ip));
            if (table==RT_TABLE_LOCAL && !local) continue;
            if (table==RT_TABLE_MAIN) local=0;
            unsigned bits=local ? md_ip_size(ip)*8 : a->prefix;
            if ((local || !(a->flags&IFA_F_NOPREFIXROUTE))
                    && (local || md_ip_prefix(md_network_address_destination(a),ip,bits)) && (!best || bits>prefix)) {
                best=l; prefix=bits; metric=a->route_metric ? a->route_metric : ip.family==AF_INET6 ? 256 : 0;
                selected=md_network_connected_route(l,a);
                if (local) { selected.destination=ip; selected.prefix=bits; selected.table=RT_TABLE_LOCAL; }
            }
        }
    }
    for (unsigned i=0;i<n->route_count;++i) {
        const struct md_network_route_entry *r=&n->routes[i];
        if ((r->table ? r->table : RT_TABLE_MAIN)!=table) continue;
        unsigned preference=r->preference==1 ? 2 : r->preference==3 ? 0 : 1;
        unsigned previous=selected.preference==1 ? 2 : selected.preference==3 ? 0 : 1;
        if (!md_ip_prefix(ip,r->destination,r->prefix) || (best && (r->prefix<prefix
                || (r->prefix==prefix && (r->metric>metric || (r->metric==metric && preference<=previous)))))) continue;
        struct md_network_interface *l=md_network_interface_find(n,r->interface,NULL);
        if (!l || !(l->info.flags&IFF_UP) || (output && output!=l->info.index)
                || (md_ip_is_loopback(ip) && l->kind!=MD_LINK_LOOPBACK)
                || (ip.family==AF_INET6 && l->ipv6.disabled)
                || md_ip_unspecified(md_network_route_source(n,l,r,ip))) continue;
        best=l; selected=*r; prefix=r->prefix; metric=r->metric;
    }
    if (table==RT_TABLE_MAIN && !best && md_ip_multicast(ip) && (output || ip.family==AF_INET6))
        for (unsigned i=0;i<n->count;++i) {
            struct md_network_interface *l=&n->links[i];
            if (!(l->info.flags&IFF_UP) || (output ? output!=l->info.index : l->kind==MD_LINK_LOOPBACK)
                    || (ip.family==AF_INET6 && l->ipv6.disabled) || md_ip_unspecified(md_network_interface_source(l,ip))) continue;
            best=l;
            selected=(struct md_network_route_entry){.destination=md_ip_network(ip,ip.family==AF_INET6 ? 8 : 4),
                .prefix=ip.family==AF_INET6 ? 8 : 4,.gateway=md_ip_any(ip.family),.interface=l->info.index,
                .scope=RT_SCOPE_LINK,.protocol=RTPROT_KERNEL,.metric=256};
            break;
        }
    if (result) {
        *result=selected;
        if (!md_ip_unspecified(selected.source)) result->source=md_network_route_source(n,best,&selected,ip);
    }
    return best;
}
struct md_network_interface *md_network_route_lookup(struct md_network_namespace *n,struct md_ip_address ip,
        unsigned output,struct md_network_route_entry *result) {
    if (result) *result=(struct md_network_route_entry){0};
    return md_network_policy_route(n,md_ip_any(ip.family),ip,0,output,result,NULL);
}
int md_network_interface_reserve(struct md_network_namespace *n,unsigned extra) {
    if (extra>256-n->count) return -ENOSPC;
    if (n->count+extra<=n->capacity) return 0;
    unsigned capacity=n->capacity ? n->capacity*2 : 4;
    if (capacity<n->count+extra) capacity=n->count+extra;
    if (capacity>256) capacity=256;
    void *links=realloc(n->links,capacity*sizeof(*n->links));
    if (!links) return -ENOMEM;
    n->links=links; n->capacity=capacity; return 0;
}
static int valid_name(const char *name) {
    size_t length=strnlen(name,IFNAMSIZ);
    if (!length || length==IFNAMSIZ || !strcmp(name,".") || !strcmp(name,"..")) return 0;
    for (size_t i=0;i<length;++i)
        if (name[i]=='/' || name[i]==':' || name[i]==' ' || name[i]=='\t' || name[i]=='\n') return 0;
    return 1;
}
int md_network_bridge_forward(const struct md_network_interface *in,const struct md_network_interface *out) {
    if (!out || !out->master) return 0;
    if (!in) return 1;
    return in->master==out->master && (in!=out || out->hairpin) && !(in->isolated && out->isolated);
}
int md_network_interface_add(struct md_network_namespace *n,enum md_network_link_kind kind,const char *name,unsigned *index) {
    if (!valid_name(name)) return -EINVAL;
    if (md_network_interface_find(n,0,name)) return -EEXIST;
    if (n->fabric->next_index==INT_MAX || n->next_policy==MD_NET_SCOPE_DEFAULT-1) return -ENOSPC;
    int error=md_network_interface_reserve(n,1);
    if (error) return error;
    unsigned id=kind==MD_LINK_LOOPBACK ? 1 : n->fabric->next_index++;
    struct md_network_interface *link=&n->links[n->count++];
    *link=(struct md_network_interface){.kind=kind,.bridge_forward_delay=kind==MD_LINK_BRIDGE ? 1500 : 0,
        .policy=n->defaults,.ipv6=n->defaults6,.policy_id=++n->next_policy,.info={.index=id,
        .flags=kind==MD_LINK_LOOPBACK ? IFF_LOOPBACK : IFF_BROADCAST|IFF_MULTICAST,
        .mtu=kind==MD_LINK_LOOPBACK ? 65536 : 1500,
        .type=kind==MD_LINK_LOOPBACK ? ARPHRD_LOOPBACK : ARPHRD_ETHER,.state=IF_OPER_DOWN}};
    memcpy(link->info.name,name,strlen(name)+1);
    link->ipv6.mtu=link->info.mtu;
    if (kind!=MD_LINK_LOOPBACK) {
        link->info.address[0]=2;
        link->info.address[3]=(unsigned char)(id>>16);
        link->info.address[4]=(unsigned char)(id>>8);
        link->info.address[5]=(unsigned char)id;
    }
    *index=id; return 0;
}
static void remove_one(struct md_network_namespace *n,unsigned index) {
    for (unsigned i=0;i<n->count;++i) {
        if (n->links[i].master==index) n->links[i].master=n->links[i].hairpin=n->links[i].isolated=0;
        if (n->links[i].info.index==index) {
            memmove(n->links+i,n->links+i+1,(n->count-i-1)*sizeof(*n->links));
            --n->count; --i;
        }
    }
    for (unsigned i=0;i<n->route_count;) {
        if (n->routes[i].interface!=index) { ++i; continue; }
        memmove(n->routes+i,n->routes+i+1,(--n->route_count-i)*sizeof(*n->routes));
    }
    for (unsigned i=0;i<n->nexthop_count;) {
        if (n->nexthops[i].interface!=index) { ++i; continue; }
        memmove(n->nexthops+i,n->nexthops+i+1,(--n->nexthop_count-i)*sizeof(*n->nexthops));
    }
}
int md_network_interface_remove(struct md_network_namespace *n,unsigned index) {
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    if (!link) return -ENODEV;
    if (link->kind==MD_LINK_LOOPBACK || link->kind==MD_LINK_UPLINK) return -EOPNOTSUPP;
    unsigned peer=link->peer;
    struct md_network_namespace *other=md_network_interface_owner(n->fabric,peer);
    remove_one(n,index);
    if (other) remove_one(other,peer);
    return 0;
}
int md_network_interface_move(struct md_network_namespace *from,unsigned index,struct md_network_namespace *to,const char *name) {
    if (from->fabric!=to->fabric) return -EXDEV;
    struct md_network_interface *link=md_network_interface_find(from,index,NULL);
    if (!link) return -ENODEV;
    if (from!=to && link->kind!=MD_LINK_VETH) return -EOPNOTSUPP;
    if (!name) name=link->info.name;
    if (!valid_name(name)) return -EINVAL;
    struct md_network_interface *collision=md_network_interface_find(to,0,name);
    if (collision && collision!=link) return -EEXIST;
    char renamed[IFNAMSIZ]={0}; memcpy(renamed,name,strlen(name));
    if (from==to) { memcpy(link->info.name,renamed,IFNAMSIZ); return 0; }
    if (to->next_policy==MD_NET_SCOPE_DEFAULT-1) return -ENOSPC;
    int error=md_network_interface_reserve(to,1);
    if (error) return error;
    struct md_network_interface moved=*link;
    moved.policy=to->defaults;
    moved.ipv6=to->defaults6;
    moved.policy_id=++to->next_policy;
    moved.master=moved.hairpin=moved.isolated=0; memcpy(moved.info.name,renamed,IFNAMSIZ);
    remove_one(from,index); to->links[to->count++]=moved;
    return 0;
}
static struct md_network_namespace *create(struct md_user_namespace *owner,enum md_network_mode mode,struct md_network_fabric *fabric) {
    struct md_network_namespace *n=calloc(1,sizeof(*n));
    if (!n) return NULL;
    int error=md_namespace_identity_new(&n->inode);
    if (error) { free(n); errno=-error; return NULL; }
    if (!fabric) {
        fabric=calloc(1,sizeof(*fabric));
        if (!fabric) { free(n); return NULL; }
        error=pthread_mutex_init(&fabric->lock,NULL);
        if (error) { free(n); free(fabric); errno=error; return NULL; }
        atomic_init(&fabric->references,1); fabric->next_index=2;
        fabric->policy_generation=1;
        fabric->endpoints_enabled=mode==MD_NETWORK_ROUTED;
    } else atomic_fetch_add_explicit(&fabric->references,1,memory_order_relaxed);
    n->fabric=fabric; n->mode=mode; n->owner=md_user_namespace_retain(owner);
    md_network_policy_init(n);
    atomic_init(&n->references,1);
    atomic_init(&n->packet_enabled,0);
    n->unprivileged_port=1024; n->ping_groups[0]=1;
    n->local_ports[0]=32768; n->local_ports[1]=60999;
    n->tcp_keepalive[0]=7200; n->tcp_keepalive[1]=75; n->tcp_keepalive[2]=9;
    n->ipv4_forward=n->all6.forwarding=n->defaults6.forwarding=mode==MD_NETWORK_ROUTED;
    n->all6.accept_dad=n->defaults6.accept_dad=1;
    n->all6.hop_limit=n->defaults6.hop_limit=64;
    n->all6.mtu=n->defaults6.mtu=1500;
    pthread_mutex_lock(&fabric->lock);
    unsigned loopback=0;
    error=md_network_interface_add(n,MD_LINK_LOOPBACK,"lo",&loopback);
    if (!error && mode==MD_NETWORK_ROUTED) {
        n->links[0].info.flags|=IFF_UP;
        md_network_interface_up(&n->links[0]);
        unsigned uplink=0;
        error=md_network_interface_add(n,MD_LINK_UPLINK,"eth0",&uplink);
        if (!error) {
            struct md_network_interface *link=md_network_interface_find(n,uplink,NULL);
            link->info.flags|=IFF_UP;
            md_network_interface_up(link);
            struct md_ip_address address=md_ip_v4(htonl(0x0a000264));
            md_network_interface_address(link,(struct md_network_ip_assignment){.address=address,.prefix=24,.scope=RT_SCOPE_UNIVERSE,.flags=IFA_F_PERMANENT},0,0);
            n->routes[n->route_count++]=(struct md_network_route_entry){.destination=md_ip_any(AF_INET),
                .gateway=md_ip_v4(htonl(0x0a000202)),.interface=uplink,.protocol=RTPROT_BOOT};
            address=md_ip_any(AF_INET6); address.bytes[0]=0xfd; address.bytes[1]=0x6d;
            address.bytes[2]=0x64; address.bytes[3]=0x73; address.bytes[15]=0x64;
            md_network_interface_address(link,(struct md_network_ip_assignment){.address=address,.prefix=64,.scope=RT_SCOPE_UNIVERSE,.flags=IFA_F_PERMANENT},0,0);
            address.bytes[15]=2;
            n->routes[n->route_count++]=(struct md_network_route_entry){.destination=md_ip_any(AF_INET6),
                .gateway=address,.interface=uplink,.metric=1024,.protocol=RTPROT_BOOT};
        }
    }
    n->next=fabric->spaces; fabric->spaces=n;
    pthread_mutex_unlock(&fabric->lock);
    if (error) { md_network_namespace_release(n); errno=-error; return NULL; }
    return n;
}
struct md_network_namespace *md_network_namespace_new(struct md_user_namespace *owner,enum md_network_mode mode) {
    if (mode<MD_NETWORK_NATIVE || mode>MD_NETWORK_ROUTED) { errno=EINVAL; return NULL; }
    return create(owner,mode,NULL);
}
struct md_network_namespace *md_network_namespace_copy(struct md_network_namespace *from,struct md_user_namespace *owner) {
    if (!from) { errno=EINVAL; return NULL; }
    return create(owner,MD_NETWORK_PRIVATE,from->fabric);
}
struct md_network_namespace *md_network_namespace_retain(struct md_network_namespace *n) {
    if (n) atomic_fetch_add_explicit(&n->references,1,memory_order_relaxed);
    return n;
}
void md_network_namespace_release(struct md_network_namespace *n) {
    if (!n || atomic_fetch_sub_explicit(&n->references,1,memory_order_acq_rel)!=1) return;
    struct md_network_fabric *f=n->fabric;
    pthread_mutex_lock(&f->lock);
    md_network_forward_retire(n);
    md_network_stack_retire(n);
    md_network_tun_retire(n);
    for (unsigned i=0;i<n->count;++i) {
        struct md_network_namespace *peer=md_network_interface_owner(f,n->links[i].peer);
        if (peer && peer!=n) remove_one(peer,n->links[i].peer);
    }
    struct md_network_namespace **at=&f->spaces;
    while (*at && *at!=n) at=&(*at)->next;
    if (*at) *at=n->next;
    md_network_forward_changed(f);
    pthread_mutex_unlock(&f->lock);
    if (atomic_fetch_sub_explicit(&f->references,1,memory_order_acq_rel)==1) {
        md_network_forward_shutdown(f);
        pthread_mutex_destroy(&f->lock); free(f);
    }
    md_network_filter_free(n->filter);
    md_user_namespace_release(n->owner); free(n->links); free(n);
}
uint64_t md_network_namespace_inode(const struct md_network_namespace *n) { return n->inode; }
const struct md_user_namespace *md_network_namespace_owner(const struct md_network_namespace *n) { return n->owner; }
int md_network_namespace_managed(const struct md_network_namespace *n) { return n && n->mode!=MD_NETWORK_NATIVE; }
static int veth_carrier(struct md_network_namespace *n,const struct md_network_interface *link) {
    struct md_network_namespace *owner=md_network_interface_owner(n->fabric,link->peer);
    struct md_network_interface *peer=owner ? md_network_interface_find(owner,link->peer,NULL) : NULL;
    return (link->info.flags&IFF_UP) && peer && (peer->info.flags&IFF_UP);
}
struct md_network_link md_network_interface_snapshot(struct md_network_namespace *n,const struct md_network_interface *link) {
    struct md_network_link out=link->info;
    out.kind=link->kind;
    int carrier=!!(out.flags&IFF_UP);
    if (link->kind==MD_LINK_TUN || link->kind==MD_LINK_TAP) carrier=carrier && md_network_tun_carrier(n,out.index);
    else if (link->kind==MD_LINK_VETH) carrier=veth_carrier(n,link);
    else if (link->kind==MD_LINK_BRIDGE) {
        carrier=0;
        if (out.flags&IFF_UP) for (unsigned i=0;i<n->count;++i)
            if (n->links[i].master==out.index && (n->links[i].kind==MD_LINK_TAP
                    ? md_network_tun_carrier(n,n->links[i].info.index) && (n->links[i].info.flags&IFF_UP)
                    : veth_carrier(n,&n->links[i]))) { carrier=1; break; }
    }
    out.state=!(out.flags&IFF_UP) ? IF_OPER_DOWN
        : carrier ? (link->kind==MD_LINK_LOOPBACK ? IF_OPER_UNKNOWN : IF_OPER_UP)
        : link->kind==MD_LINK_VETH ? IF_OPER_LOWERLAYERDOWN : IF_OPER_DOWN;
    out.flags&=~(IFF_RUNNING|IFF_LOWER_UP);
    if (carrier) out.flags|=IFF_RUNNING|IFF_LOWER_UP;
    out.peer=link->peer;
    out.lifetime=link->policy_id;
    return out;
}
int md_network_link_info(const struct md_network_namespace *n,unsigned index,struct md_network_link *out) {
    if (!md_network_namespace_managed(n)) return -EOPNOTSUPP;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *link=md_network_interface_find((struct md_network_namespace *)n,index,NULL);
    if (link) *out=md_network_interface_snapshot((struct md_network_namespace *)n,link);
    pthread_mutex_unlock(&n->fabric->lock);
    return link ? 0 : -ENODEV;
}
int md_network_link_list(struct md_network_namespace *n,struct md_network_link *out,unsigned capacity) {
    return md_network_link_snapshot(n,NULL,out,capacity);
}
int md_network_link_snapshot(struct md_network_namespace *n,uint64_t *generation,
        struct md_network_link *out,unsigned capacity) {
    if (!md_network_namespace_managed(n)) return -EXDEV;
    pthread_mutex_lock(&n->fabric->lock);
    int result=generation && *generation==n->fabric->policy_generation ? 0
        : n->count>capacity ? -ENOSPC : (int)n->count;
    if (result>0) {
        for (unsigned i=0;i<n->count;++i) out[i]=md_network_interface_snapshot(n,&n->links[i]);
        if (generation) *generation=n->fabric->policy_generation;
    }
    pthread_mutex_unlock(&n->fabric->lock); return result;
}
int md_network_ingress(struct md_network_namespace *n,const struct md_network_interface *in,
        struct md_ip_address source,struct md_ip_address destination,uint32_t mark) {
    if (!in) return -ENETUNREACH;
    if (source.family==AF_INET6) return in->kind!=MD_LINK_LOOPBACK
        && (md_ip_is_loopback(source) || md_ip_is_loopback(destination)) ? -ENETUNREACH : 0;
    if (in->kind!=MD_LINK_LOOPBACK && !n->all.route_localnet && !in->policy.route_localnet
            && (md_ip_is_loopback(source) || md_ip_is_loopback(destination))) return -ENETUNREACH;
    unsigned filter=n->all.rp_filter>in->policy.rp_filter ? n->all.rp_filter : in->policy.rp_filter;
    if (filter) {
        struct md_network_interface *reverse=md_network_policy_route(n,destination,source,
            n->all.src_valid_mark || in->policy.src_valid_mark ? mark : 0,0,NULL,NULL);
        if (!reverse || (filter==1 && reverse->info.index!=in->info.index)) return -ENETUNREACH;
    }
    return 0;
}
