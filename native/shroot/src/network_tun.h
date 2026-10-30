#ifndef MD_NETWORK_TUN_H
#define MD_NETWORK_TUN_H
#include "control_file.h"
struct md_network_namespace;
struct md_network_interface;
struct md_network_fabric;
int md_tun_device_node(struct md_control_file *);
int md_tun_device_is_node(struct md_control_file);
/* Packet and lifetime operations run under the fabric lock. Queue handles pin
 * their opening namespace; persistent interfaces belong to that namespace. */
int md_network_tun_output(struct md_network_namespace *,unsigned,const void *,size_t);
int md_network_tun_carrier(struct md_network_namespace *,unsigned);
void md_network_tun_changed(struct md_network_namespace *);
void md_network_tun_retire(struct md_network_namespace *);
#endif
