#ifndef MD_NETWORK_RAW6_H
#define MD_NETWORK_RAW6_H
#include "network_packet.h"
#include "network_multicast.h"
#include "network_raw_observe.h"
#include <netinet/icmp6.h>
struct md_network_namespace;
struct md_network_interface;
/* Raw IPv6 sockets share Ethernet delivery and the datagram envelope. Host
 * raw-socket privileges and interception of ordinary file IO are unnecessary. */
struct md_network_raw6 {
    struct md_network_raw6 *next;
    struct md_network_namespace *space;
    struct md_network_multicast multicast;
    struct icmp6_filter filter;
    struct in6_addr local, remote;
    int peer, bound, connected, overflow, protocol, checksum, broadcast;
    int unicast_hops, receive_hops, receive_info, timestamp, traffic_class;
    unsigned device, local_scope, remote_scope;
    uint64_t device_policy, local_policy;
};
void md_network_raw6_attach(struct md_network_raw6 *,struct md_network_namespace *,int,int protocol);
void md_network_raw6_detach(struct md_network_raw6 *);
long md_network_raw6_call(struct md_network_raw6 *,long,const unsigned long *,void *,size_t,size_t,
    const struct md_network_send *);
void md_network_raw6_receive(struct md_network_namespace *,struct md_network_interface *,const void *,size_t);
void md_network_raw6_reply(struct md_network_namespace *,struct md_network_interface *,
    const struct md_inet_address *,const struct md_inet_address *,const void *,size_t);
int md_network_raw6_options(const struct md_network_raw6 *);
#endif
