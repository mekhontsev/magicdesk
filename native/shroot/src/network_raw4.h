#ifndef MD_NETWORK_RAW4_H
#define MD_NETWORK_RAW4_H
#include "network_packet.h"
#include "network_ip.h"
#include "network_raw_observe.h"
struct md_network_namespace;
struct md_network_interface;
/* The control socket owns the native receive queue; the fabric owns routing. */
struct md_network_raw4 {
    struct md_network_raw4 *next;
    struct md_network_namespace *space;
    int peer, protocol, connected, bound, overflow;
    int header, broadcast, info, receive_ttl, timestamp, ttl, tos;
    uint32_t local, remote;
    unsigned device;
    uint64_t device_policy;
};
void md_network_raw4_attach(struct md_network_raw4 *,struct md_network_namespace *,int,int);
void md_network_raw4_detach(struct md_network_raw4 *);
int md_network_raw4_options(const struct md_network_raw4 *);
void md_network_raw4_receive(struct md_network_namespace *,const struct md_network_interface *,const void *,size_t);
void md_network_raw4_reply(struct md_network_namespace *,const struct md_network_interface *,
    const struct md_inet_address *,const struct md_inet_address *,const void *,size_t);
long md_network_raw4_call(struct md_network_raw4 *,long,const unsigned long *,void *,size_t,size_t,
    const struct md_network_send *);
#endif
