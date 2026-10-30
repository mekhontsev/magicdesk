#ifndef MD_LWIP_CONTEXT_H
#define MD_LWIP_CONTEXT_H
#include <stddef.h>
#include <stdint.h>

#define MD_LWIP_MODULES(X) \
    X(netif,NETIF) X(ip,IP) X(tcp,TCP) X(tcp_in,TCP_IN) X(udp,UDP) X(raw,RAW) X(pbuf,PBUF) \
    X(timers,TIMERS) X(ip4,IP4) X(ip4_frag,IP4_FRAG) X(ip6_frag,IP6_FRAG) \
    X(igmp,IGMP) X(arp,ARP) X(nd6,ND6) X(ip4_addr,IP4_ADDR) X(ip6_addr,IP6_ADDR)
#define MD_LWIP_MODULE_ENUM(name,id) MD_LWIP_##id,
enum { MD_LWIP_MODULES(MD_LWIP_MODULE_ENUM) MD_LWIP_MODULE_COUNT };
#undef MD_LWIP_MODULE_ENUM

struct md_lwip_allocation;
struct netif;
struct pbuf;
struct ip4_addr;
struct ip6_addr;
struct md_lwip_context {
    void *modules[MD_LWIP_MODULE_COUNT];
    struct md_lwip_allocation *allocations;
    size_t bytes;
    const struct ip4_addr *(*gateway4)(struct netif *,const struct ip4_addr *);
    const struct ip6_addr *(*gateway6)(struct netif *,const struct ip6_addr *);
    int (*input)(struct pbuf *,struct netif *,unsigned);
};
enum { MD_LWIP_MEMORY_LIMIT=64*1024*1024 };
/* Dispatch only, never protocol state. Every entry restores the previous
 * context; the caller serializes each stack and queues recursive packet IO. */
extern _Alignas(64) _Thread_local struct md_lwip_context *md_lwip_current;
struct md_lwip_context *md_lwip_context_new(void);
void md_lwip_context_free(struct md_lwip_context *);
static inline struct md_lwip_context *md_lwip_enter(struct md_lwip_context *context) {
    struct md_lwip_context *previous=md_lwip_current;
    md_lwip_current=context;
    return previous;
}
static inline void md_lwip_leave(struct md_lwip_context *previous) { md_lwip_current=previous; }
#endif
