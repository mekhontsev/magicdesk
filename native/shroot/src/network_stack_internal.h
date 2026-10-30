#ifndef MD_NETWORK_STACK_INTERNAL_H
#define MD_NETWORK_STACK_INTERNAL_H
#include "network_stack.h"
#include "network_forward.h"
#include "lwip_context.h"
#include "lwip/netif.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"

struct md_stack_interface {
    struct md_stack_interface *next;
    struct md_network_stack *owner;
    struct netif netif;
    unsigned index;
    uint64_t policy;
    ip4_addr_t gateway4;
    ip6_addr_t gateway6;
    int ethernet;
};
struct md_stack_packet;
struct md_stack_listener;
struct md_network_stack {
    struct md_network_namespace *space;
    struct md_lwip_context *context;
    struct md_stack_interface *interfaces;
    struct md_stack_channel *channels;
    struct md_stack_listener *listeners;
    struct md_stack_packet *head, **tail;
    size_t packets, bytes;
    int event, timer;
    struct md_network_event_source *event_source, *timer_source;
};
struct md_stack_interface *md_stack_interface(struct md_network_stack *,unsigned);
void md_stack_arm(struct md_network_stack *);
int md_stack_error(err_t);
ip_addr_t md_stack_ip(struct md_ip_address);
void md_stack_channels_retire(struct md_network_stack *);
void md_stack_channels_changed(struct md_network_stack *);
int md_stack_channel_input(struct md_network_stack *,struct md_stack_interface *,struct pbuf *,unsigned);
int md_stack_packet_input(struct pbuf *,struct netif *,unsigned);
int md_stack_packet_output(struct md_stack_interface *,struct pbuf *);
#endif
