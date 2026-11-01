#ifndef MD_NETWORK_TC_H
#define MD_NETWORK_TC_H
#include "network_message.h"
struct md_network_tc;
struct md_network_namespace;
struct md_network_interface;
/* Interface-owned snapshots, retained by the topology journal under its lock. */
struct md_network_tc *md_network_tc_retain(struct md_network_tc *);
void md_network_tc_release(struct md_network_tc *);
int md_network_tc_request(unsigned);
int md_network_tc_control(struct md_network_namespace *,const struct nlmsghdr *,uint32_t,int,struct md_network_message *);
unsigned md_network_tc_notify(struct md_network_namespace *,unsigned,const struct md_network_tc *,const struct md_network_tc *);
/* Ethernet egress only. Zero passes, one drops, negative is an allocation error.
 * A non-NULL copy belongs to the caller; the borrowed input is never changed. */
int md_network_tc_egress(struct md_network_tc *,const void *,size_t,void **copy);
#endif
