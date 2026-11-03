#ifndef MD_NETWORK_VXLAN_H
#define MD_NETWORK_VXLAN_H
#include "network_ip.h"
#include <stddef.h>
struct md_network_namespace;
struct md_network_interface;
struct md_network_message;
struct md_network_vxlan_update;
struct md_network_vxlan_config {
    uint32_t id,link;
    struct md_ip_address local,remote;
    uint16_t port;
    unsigned char ttl,tos;
};
int md_network_vxlan_parse(const void *,size_t,struct md_network_vxlan_config *);
int md_network_vxlan_reply(struct md_network_message *,const struct md_network_vxlan_config *);
/* Prepare socket/stack resources before rtnetlink publication. The interface
 * catalogue is tentative under the fabric lock; failure cannot expose it. */
int md_network_vxlan_prepare(struct md_network_namespace *,struct md_network_vxlan_update **);
void md_network_vxlan_finish(struct md_network_vxlan_update *,int);
void md_network_vxlan_changed(struct md_network_namespace *);
void md_network_vxlan_retire(struct md_network_namespace *);
int md_network_vxlan_output(struct md_network_namespace *,struct md_network_interface *,const void *,size_t);
#endif
