#ifndef MD_NETWORK_STACK_H
#define MD_NETWORK_STACK_H
#include <stddef.h>
#include "network_ip.h"
struct md_network_namespace;
struct md_network_interface;
struct md_network_fabric;
struct md_network_flow_path;
struct md_stack_channel;
struct arpreq;
struct md_network_rejection;
struct md_filter_packet;
struct md_network_nat_ports;
/* Packet stacks are lazy and namespace-owned. All calls hold the fabric lock;
 * no ordinary file/socket payload is sent through this interface. */
int md_network_stack_prepare(struct md_network_namespace *);
int md_network_stack_input(struct md_network_namespace *,struct md_network_interface *,const void *,size_t);
int md_network_stack_has_link(struct md_network_namespace *,unsigned);
int md_network_stack_send_ip(struct md_network_namespace *,unsigned,const void *,size_t);
int md_network_stack_arp(struct md_network_namespace *,unsigned,struct arpreq *);
void md_network_stack_neighbor_forget(struct md_network_namespace *,unsigned,struct md_ip_address);
void md_network_stack_changed(struct md_network_namespace *);
void md_network_stack_retire(struct md_network_namespace *);
/* Packet TCP may retain a tuple after the relay retires, until TIME_WAIT ends. */
void md_network_stack_nat_ports(struct md_network_namespace *,const struct md_filter_packet *,struct md_network_nat_ports *);
/* A native socketpair joins the existing relay to a packet transport. The
 * relay owns the returned FD and channel; neither retains a guest payload FD.
 * result is EINPROGRESS until the remote TCP handshake, not the local one. */
int md_network_stack_connect(struct md_network_fabric *,const struct md_network_flow_path *,int,
    struct md_stack_channel **);
int md_network_stack_result(struct md_stack_channel *);
/* Borrow the owning relay's generation-cached policy until channel close.
 * Packet rejection needs the original IP header before lwIP consumes it. */
void md_network_stack_set_policy(struct md_stack_channel *,void *,unsigned,
    int (*)(void *,unsigned,size_t,struct md_network_rejection *));
/* Graceful relay retirement must leave queued payload and FIN with the packet
 * owner before closing the native carrier. Errors still retire immediately. */
int md_network_stack_write_closed(struct md_stack_channel *);
void md_network_stack_close(struct md_stack_channel *);
#endif
