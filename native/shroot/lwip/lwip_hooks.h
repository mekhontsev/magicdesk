#ifndef MD_LWIP_HOOKS_H
#define MD_LWIP_HOOKS_H
#include "lwip_context.h"
#include "lwip/ip_addr.h"
u32_t md_lwip_sequence(const ip_addr_t *,u16_t,const ip_addr_t *,u16_t);
static inline const struct ip4_addr *md_lwip_gateway4(struct netif *n,const struct ip4_addr *a) {
    return md_lwip_current->gateway4 ? md_lwip_current->gateway4(n,a) : NULL;
}
static inline const struct ip6_addr *md_lwip_gateway6(struct netif *n,const struct ip6_addr *a) {
    return md_lwip_current->gateway6 ? md_lwip_current->gateway6(n,a) : NULL;
}
#endif
