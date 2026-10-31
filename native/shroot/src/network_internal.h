#ifndef MD_NETWORK_INTERNAL_H
#define MD_NETWORK_INTERNAL_H
#include "network_namespace.h"
#include "network_ip.h"
#include "network_transport.h"
#include <pthread.h>

struct md_network_ipv4_policy { uint32_t route_localnet, rp_filter, arp_notify; };
struct md_network_ipv6_policy {
    uint32_t forwarding, disabled, accept_dad, accept_ra, autoconf, mtu, hop_limit, addr_gen_mode, use_tempaddr;
};
struct md_network_ip_assignment {
    struct md_ip_address address;
    unsigned prefix, scope, flags, route_metric;
    /* Monotonic deadlines in milliseconds; zero denotes infinite lifetime. */
    int64_t preferred_until, valid_until;
    /* Temporary addresses retain their template and original lifetime caps. */
    struct in6_addr temporary_parent;
    int64_t temporary_preferred, temporary_valid, regenerate_at;
};
struct md_network_route_entry {
    struct md_ip_address destination, gateway, source;
    unsigned prefix, interface, metric, protocol, scope, nexthop;
    int64_t valid_until;
    unsigned char preference;
    struct md_network_route_metrics metrics;
};
struct md_network_nexthop { uint32_t id, interface, flags, protocol; struct md_ip_address gateway; };
enum { MD_NETWORK_MAX_ADDRESSES=16, MD_NETWORK_MAX_ROUTES=256 };
struct md_network_interface {
    struct md_network_link info;
    enum md_network_link_kind kind;
    unsigned master, peer, hairpin, isolated;
    uint32_t bridge_forward_delay;
    unsigned address_count;
    struct md_network_ip_assignment addresses[MD_NETWORK_MAX_ADDRESSES];
    uint64_t policy_id;
    struct md_network_ipv4_policy policy;
    struct md_network_ipv6_policy ipv6;
};
static inline struct md_network_transport_policy md_network_route_transport(
        const struct md_network_interface *link,const struct md_network_route_entry *route) {
    if (!link || !route || route->destination.family!=AF_INET6) return (struct md_network_transport_policy){0};
    unsigned mtu=link->ipv6.mtu;
    if (link->info.mtu<mtu) mtu=link->info.mtu;
    if (route->metrics.mtu && route->metrics.mtu<mtu) mtu=route->metrics.mtu;
    return (struct md_network_transport_policy){.mtu=mtu,.hops=route->metrics.hops ? route->metrics.hops : link->ipv6.hop_limit};
}
struct md_network_fabric {
    atomic_uint references;
    pthread_mutex_t lock;
    unsigned next_index;
    struct md_network_namespace *spaces;
    struct md_network_endpoint *endpoints;
    int endpoints_enabled;
    uint64_t policy_generation;
    struct md_network_forward *forward;
    int64_t configuration_deadline;
};
struct md_network_namespace {
    struct md_network_multicast *multicast;
    atomic_uint references;
    uint64_t inode, next_policy;
    struct md_user_namespace *owner;
    enum md_network_mode mode;
    struct md_network_fabric *fabric;
    struct md_network_namespace *next;
    struct md_network_interface *links;
    unsigned count, capacity;
    unsigned route_count;
    struct md_network_route_entry routes[MD_NETWORK_MAX_ROUTES];
    unsigned nexthop_count;
    int64_t configuration_deadline;
    struct md_network_nexthop nexthops[MD_NETWORK_MAX_ROUTES];
    uint32_t unprivileged_port, ping_groups[2], local_ports[2];
    uint32_t next_port, next_ip_id, ipv4_forward, bindv6only;
    uint32_t tcp_keepalive[3];
    struct md_network_ipv4_policy all, defaults;
    struct md_network_ipv6_policy all6, defaults6;
    struct md_network_listener *listeners;
    struct md_network_ethernet *ethernet;
    struct md_network_raw6 *raw6;
    struct md_network_raw4 *raw4;
    struct md_network_filter *filter;
    struct md_network_tun *tunnels;
    struct md_network_stack *stack;
    atomic_bool packet_enabled;
};
/* Weak listeners are removed under the fabric lock before final FD release.
 * Only committed topology changes are broadcast. Delivery never blocks. */
struct md_network_listener {
    struct md_network_listener *next;
    int peer, protocol;
    uint32_t groups, port;
    int overflow, no_enobufs, broadcast_error;
};
static inline int md_network_forwarding(const struct md_network_namespace *n,unsigned family,unsigned input) {
    if (family!=AF_INET6) return n->ipv4_forward;
    for (unsigned i=0;i<n->count;++i) if (n->links[i].info.index==input)
        return n->links[i].ipv6.forwarding && !n->links[i].ipv6.disabled;
    return n->all6.forwarding;
}
unsigned md_network_broadcast(struct md_network_namespace *,unsigned,const void *,size_t);
/* Mutators below run under the fabric lock. No guest IO or blocking syscall
 * may span that lock. Namespaces are weakly listed and unlink before release. */
struct md_network_interface *md_network_interface_find(struct md_network_namespace *,unsigned,const char *);
/* Caller holds the fabric lock; all link observers share this projection. */
struct md_network_link md_network_interface_snapshot(struct md_network_namespace *,const struct md_network_interface *);
int md_network_interface_reserve(struct md_network_namespace *,unsigned);
int md_network_interface_add(struct md_network_namespace *,enum md_network_link_kind,const char *,unsigned *);
int md_network_interface_remove(struct md_network_namespace *,unsigned);
int md_network_interface_move(struct md_network_namespace *,unsigned,struct md_network_namespace *,const char *);
/* NULL ingress denotes locally generated bridge traffic. */
int md_network_bridge_forward(const struct md_network_interface *ingress,const struct md_network_interface *egress);
struct md_network_namespace *md_network_interface_owner(struct md_network_fabric *,unsigned);
struct md_network_interface *md_network_route_lookup(struct md_network_namespace *,struct md_ip_address,unsigned,struct md_network_route_entry *);
static inline struct md_network_interface *md_network_route_find(struct md_network_namespace *n,
        struct md_ip_address ip,struct md_network_route_entry *route) {
    return md_network_route_lookup(n,ip,0,route);
}
struct md_ip_address md_network_interface_source(const struct md_network_interface *,struct md_ip_address);
struct md_ip_address md_network_route_source(struct md_network_namespace *,const struct md_network_interface *,
    const struct md_network_route_entry *,struct md_ip_address);
int md_network_interface_has(const struct md_network_interface *,struct md_ip_address);
int md_network_interface_broadcast(const struct md_network_interface *,struct md_ip_address);
unsigned md_network_address_type(struct md_network_namespace *,struct md_ip_address);
int md_network_interface_address(struct md_network_interface *,struct md_network_ip_assignment,int,int);
void md_network_interface_up(struct md_network_interface *);
void md_network_interface_ipv6_flush(struct md_network_namespace *,struct md_network_interface *);
int md_network_address_duplicate(struct md_network_namespace *,const struct md_network_interface *,struct md_ip_address);
struct md_network_interface *md_network_neighbor(struct md_network_namespace *,const struct md_network_interface *,struct md_ip_address);
int md_network_ingress(struct md_network_namespace *,const struct md_network_interface *,struct md_ip_address,struct md_ip_address);
#endif
