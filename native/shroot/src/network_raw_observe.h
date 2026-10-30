#ifndef MD_NETWORK_RAW_OBSERVE_H
#define MD_NETWORK_RAW_OBSERVE_H
#include "network_ip.h"
#include <stddef.h>
struct md_network_namespace;
struct md_network_interface;
/* Validated, reassembled data; no policy evaluation or automatic replies. */
void md_network_raw4_observe(struct md_network_namespace *,const struct md_network_interface *,const void *,size_t);
void md_network_raw6_observe(struct md_network_namespace *,struct md_network_interface *,
    struct md_ip_address,struct md_ip_address,unsigned,unsigned,const void *,size_t);
#endif
