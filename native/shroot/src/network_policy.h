#ifndef MD_NETWORK_POLICY_H
#define MD_NETWORK_POLICY_H
#include "network_internal.h"
#include "network_message.h"
void md_network_policy_init(struct md_network_namespace *);
int md_network_rules(struct md_network_namespace *,const struct nlmsghdr *,uint32_t,int,struct md_network_message *);
int md_network_rule_reply(struct md_network_message *,const struct nlmsghdr *,uint32_t,int,const struct md_network_rule *);
struct md_network_interface *md_network_policy_route(struct md_network_namespace *,struct md_ip_address,
    struct md_ip_address,uint32_t,unsigned,struct md_network_route_entry *,int *);
#endif
