#ifndef MD_LWIP_OPTIONS_H
#define MD_LWIP_OPTIONS_H
#define NO_SYS 1
#define SYS_LIGHTWEIGHT_PROT 0
#define LWIP_SOCKET 0
#define LWIP_NETCONN 0
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS 1
#define LWIP_STATS 0
#include <stddef.h>
void *md_lwip_malloc(size_t);
void *md_lwip_calloc(size_t,size_t);
void md_lwip_free(void *);
#define MEM_CUSTOM_ALLOCATOR 1
#define MEM_CUSTOM_MALLOC md_lwip_malloc
#define MEM_CUSTOM_CALLOC md_lwip_calloc
#define MEM_CUSTOM_FREE md_lwip_free
#define MEMP_MEM_MALLOC 1
#define MEM_ALIGNMENT 8
#define LWIP_IPV4 1
#define LWIP_IPV6 1
#define LWIP_TCP 1
#define LWIP_UDP 1
#define LWIP_RAW 1
#define LWIP_ICMP 1
#define LWIP_ICMP6 1
#define LWIP_IGMP 1
#define LWIP_IPV6_MLD 1
#define LWIP_ARP 1
#define LWIP_ETHERNET 1
#define ARP_QUEUEING 1
#define ARP_TABLE_SIZE 64
#define ARP_QUEUE_LEN 32
#define ETHARP_SUPPORT_STATIC_ENTRIES 1
#define ETHARP_TABLE_MATCH_NETIF 1
#define LWIP_ND6_NUM_NEIGHBORS 64
#define LWIP_ND6_NUM_DESTINATIONS 64
#define LWIP_ND6_NUM_PREFIXES 16
#define LWIP_ND6_NUM_ROUTERS 16
#define LWIP_IPV6_NUM_ADDRESSES 16
/* Shroot owns configuration; guest DHCP and routing daemons remain clients. */
#define LWIP_DHCP 0
#define LWIP_DNS 0
#define LWIP_AUTOIP 0
#define LWIP_IPV6_DHCP6 0
#define LWIP_IPV6_AUTOCONFIG 0
#define LWIP_IPV6_SEND_ROUTER_SOLICIT 0
#define LWIP_IPV6_DUP_DETECT_ATTEMPTS 0
#define LWIP_NETIF_LOOPBACK 0
#define LWIP_HAVE_LOOPIF 0
#define IP_FORWARD 0
#define LWIP_IPV6_FORWARD 0
#define IP_REASSEMBLY 1
#define IP_FRAG 1
#define LWIP_IPV6_REASS 1
#define LWIP_IPV6_FRAG 1
#define IPV6_FRAG_COPYHEADER 1
#define IP_REASS_MAX_PBUFS 256
#define IP6_REASS_MAX_PBUFS 256
#define TCP_MSS 1460
#define TCP_WND 262144
#define TCP_SND_BUF 262144
#define LWIP_WND_SCALE 1
#define TCP_RCV_SCALE 3
#define TCP_SNDLOWAT (4 * TCP_MSS)
#define LWIP_TCP_SACK_OUT 1
#define LWIP_TCP_TIMESTAMPS 1
#define LWIP_TCP_KEEPALIVE 1
#define TCP_LISTEN_BACKLOG 1
#define SO_REUSE 1
#define LWIP_NETIF_LINK_CALLBACK 1
#define LWIP_NETIF_STATUS_CALLBACK 1
#define LWIP_TIMERS 1
#define LWIP_NOASSERT_ON_ERROR 1
#define LWIP_HOOK_FILENAME "lwip_hooks.h"
#define LWIP_HOOK_ETHARP_GET_GW(n,a) md_lwip_gateway4(n,a)
#define LWIP_HOOK_ND6_GET_GW(n,a) md_lwip_gateway6(n,a)
#endif
