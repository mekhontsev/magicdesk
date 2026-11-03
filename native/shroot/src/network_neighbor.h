#ifndef MD_NETWORK_NEIGHBOR_H
#define MD_NETWORK_NEIGHBOR_H
#include "network_ip.h"
#include <linux/netlink.h>
struct md_network_namespace;
struct md_network_interface;
struct md_network_message;
struct md_network_neighbors;
/* Immutable, sorted control-plane snapshot. Mutations replace one snapshot;
 * the rtnetlink journal retains the old one until publication commits. */
struct md_network_neighbors *md_network_neighbors_retain(struct md_network_neighbors *);
void md_network_neighbors_release(struct md_network_neighbors *);
int md_network_neighbors_prune(struct md_network_namespace *);
int md_network_neighbors_control(struct md_network_namespace *,const struct nlmsghdr *,uint32_t,int,struct md_network_message *);
unsigned md_network_neighbors_notify(struct md_network_namespace *,const struct md_network_neighbors *);
void md_network_neighbors_commit(struct md_network_namespace *,const struct md_network_neighbors *);
int md_network_neighbor_mac(struct md_network_namespace *,const struct md_network_interface *,struct md_ip_address,unsigned char [6]);
int md_network_neighbor_remote(struct md_network_namespace *,const struct md_network_interface *,const unsigned char [6],struct md_ip_address *);
/* Returns one for a static entry, zero for the dynamic ARP cache fallback. */
int md_network_neighbor_arp(struct md_network_namespace *,const struct md_network_interface *,unsigned,struct md_ip_address,unsigned char [6],int);
#endif
