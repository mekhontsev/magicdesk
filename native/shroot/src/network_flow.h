#ifndef MD_NETWORK_FLOW_H
#define MD_NETWORK_FLOW_H
#include "network_filter.h"
#include "network_internal.h"

struct md_network_flow_step {
    uint64_t space;
    unsigned hook, bridge_ingress, bridge_egress;
    struct md_filter_packet packet;
    struct md_filter_rewrite after;
};
struct md_network_flow_path {
    struct md_filter_packet original, translated;
    struct md_network_flow_step steps[16];
    unsigned count, mediated;
    struct md_network_rejection rejection[2];
    uint64_t destination, generation;
    uint64_t packet_space;
    unsigned packet_output;
    /* Native publication owns this host-local address only in its ingress
     * namespace. DNAT and child namespaces must not inherit that ownership. */
    uint64_t native_space;
    struct md_ip_address native_address;
    struct md_network_transport_policy transport;
};
/* A connection-setup snapshot, never retained or consulted by payload IO. */
struct md_network_nat_ports { uint64_t used[1024]; };
void md_network_flow_nat_ports(const struct md_network_flow_path *,uint64_t,
    const struct md_filter_packet *,struct md_network_nat_ports *);
/* Routing, policy and retained conntrack coordinates share the fabric lock. */
struct md_network_interface *md_network_local_interface(struct md_network_namespace *,struct md_ip_address,int);
struct md_network_namespace *md_network_space_find(struct md_network_fabric *,uint64_t);
int md_network_flow_route(struct md_network_namespace *,struct md_filter_packet *,int,unsigned,struct md_network_flow_path *);
int md_network_flow_group_output(struct md_network_namespace *,struct md_network_interface *,struct md_filter_packet *,struct md_network_flow_path *);
int md_network_flow_group_input(struct md_network_namespace *,struct md_network_interface *,struct md_network_flow_path *);
int md_network_flow_packet_policy(struct md_network_namespace *,const struct md_network_interface *,
    struct md_filter_packet *,int input,size_t bytes);
int md_network_flow_check(struct md_network_fabric *,struct md_network_flow_path *,int);
int md_network_flow_accounting(struct md_network_fabric *,struct md_network_flow_path *,int,int,struct md_filter_accounting *);
int md_network_flow_admits(struct md_network_namespace *,struct md_network_filter *,const struct md_network_flow_path *);
#endif
