#define _GNU_SOURCE
#include "network_raw4.h"
#include "network_internal.h"
#include "network_ethernet.h"
#include "network_flow.h"
#include "network_forward.h"
#include "network_stack.h"
#include <errno.h>
#include <linux/if_ether.h>
#include <net/if.h>
#include <netinet/ip.h>
#include <netinet/udp.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/time.h>

static uint32_t checksum_add(uint32_t sum,const void *data,size_t size) {
    const unsigned char *p=data;
    for (size_t i=0;i<size;i+=2) sum+=((unsigned)p[i]<<8)|(i+1<size ? p[i+1] : 0);
    return sum;
}
static uint16_t checksum(const void *data,size_t size) {
    uint32_t sum=checksum_add(0,data,size);
    while (sum>>16) sum=(sum&65535)+(sum>>16);
    return htons((uint16_t)~sum);
}
static struct md_filter_packet packet_tuple(const struct iphdr *h,const void *payload,size_t size) {
    struct md_filter_packet p={.source=md_ip_v4(h->saddr),.destination=md_ip_v4(h->daddr),
        .protocol=h->protocol,.state=8};
    p.transport_size=size<sizeof(p.transport) ? (unsigned)size : sizeof(p.transport);
    if (h->protocol==IPPROTO_ICMP && size<8) p.transport_size=0;
    memcpy(p.transport,payload,p.transport_size);
    if ((h->protocol==IPPROTO_UDP || h->protocol==IPPROTO_TCP) && size>=4) {
        memcpy(&p.source_port,payload,2); memcpy(&p.destination_port,(const char *)payload+2,2);
    }
    return p;
}
void md_network_raw4_attach(struct md_network_raw4 *s,struct md_network_namespace *n,int peer,int protocol) {
    *s=(struct md_network_raw4){.space=n,.peer=peer,.protocol=protocol,.header=protocol==IPPROTO_RAW,
        .ttl=64,.next=n->raw4};
    n->raw4=s;
}
void md_network_raw4_detach(struct md_network_raw4 *s) {
    struct md_network_raw4 **at=&s->space->raw4;
    while (*at && *at!=s) at=&(*at)->next;
    if (*at) *at=s->next;
}
int md_network_raw4_options(const struct md_network_raw4 *s) {
    return MD_NETWORK_PACKET_IPV4 | (s->info ? MD_NETWORK_PACKET_IPV4_INFO : 0)
        | (s->receive_ttl ? MD_NETWORK_PACKET_IPV4_TTL : 0) | (s->timestamp ? MD_NETWORK_PACKET_TIMESTAMP : 0);
}
static void receive(struct md_network_namespace *n,const struct md_network_interface *link,
        const void *frame,size_t length,int udp) {
    if (length<ETH_HLEN+sizeof(struct iphdr)) return;
    struct iphdr h; memcpy(&h,(const char *)frame+ETH_HLEN,sizeof(h));
    size_t header=h.ihl*4,size=ntohs(h.tot_len);
    if (h.version!=4 || header<sizeof(h) || size<header || size>length-ETH_HLEN
            || checksum((const char *)frame+ETH_HLEN,header)) return;
    /* Fragment reassembly is not implemented; never publish partial IP data. */
    if (ntohs(h.frag_off)&(IP_MF|IP_OFFMASK)) return;
    if (!md_network_interface_has(link,md_ip_v4(h.daddr)) && !md_network_interface_broadcast(link,md_ip_v4(h.daddr))) return;
    const unsigned char *payload=(const unsigned char *)frame+ETH_HLEN+header;
    struct md_filter_packet packet=packet_tuple(&h,payload,size-header);
    if (udp && h.protocol==IPPROTO_UDP) {
        if (size-header<sizeof(struct udphdr)) return;
        struct udphdr u; memcpy(&u,payload,sizeof(u));
        size_t bytes=ntohs(u.len);
        if (bytes<sizeof(u) || bytes>size-header) return;
        if (u.check) {
            uint32_t sum=checksum_add(IPPROTO_UDP+bytes,&h.saddr,8);
            sum=checksum_add(sum,payload,bytes);
            while (sum>>16) sum=(sum&65535)+(sum>>16);
            if (sum!=65535) return;
        }
        if (md_network_forward_datagram(n,link->info.index,&packet,h.ttl,payload+sizeof(u),bytes-sizeof(u))) return;
    } else if (udp && md_network_flow_packet_policy(n,link,&packet,1,size-header)) return;
    md_network_raw4_observe(n,link,(const char *)frame+ETH_HLEN,size);
}
void md_network_raw4_observe(struct md_network_namespace *n,const struct md_network_interface *link,
        const void *data,size_t size) {
    if(!n->raw4 || size<sizeof(struct iphdr)) return;
    struct iphdr h; memcpy(&h,data,sizeof(h));
    struct md_network_packet envelope={.ip={.interface=link->info.index,.hops=h.ttl}};
    memcpy(envelope.ip.source,&h.saddr,4); memcpy(envelope.ip.destination,&h.daddr,4);
    envelope.ip.local4=md_network_interface_has(link,md_ip_v4(h.daddr))
        ? (uint32_t)h.daddr : md_network_interface_source(link,md_ip_v4(h.saddr)).v4;
    struct timeval now; gettimeofday(&now,NULL);
    envelope.ip.seconds=now.tv_sec; envelope.ip.microseconds=now.tv_usec;
    struct iovec vectors[]={{&envelope,sizeof(envelope)},{(void *)data,size}};
    struct msghdr message={.msg_iov=vectors,.msg_iovlen=2};
    for (struct md_network_raw4 *s=n->raw4;s;s=s->next) {
        if (s->protocol==IPPROTO_RAW || s->protocol!=h.protocol
                || (s->device && (s->device!=link->info.index || s->device_policy!=link->policy_id))
                || (s->local && s->local!=(uint32_t)h.daddr) || (s->connected && s->remote!=(uint32_t)h.saddr)) continue;
        if (sendmsg(s->peer,&message,MSG_DONTWAIT|MSG_NOSIGNAL)<0 && errno!=ECONNREFUSED) s->overflow=1;
    }
}
void md_network_raw4_receive(struct md_network_namespace *n,const struct md_network_interface *link,
        const void *frame,size_t length) { receive(n,link,frame,length,1); }
void md_network_raw4_reply(struct md_network_namespace *n,const struct md_network_interface *link,
        const struct md_inet_address *source,const struct md_inet_address *destination,const void *data,size_t size) {
    if (source->ip.family!=AF_INET || destination->ip.family!=AF_INET || size>65507 || !n->raw4) return;
    size_t length=ETH_HLEN+sizeof(struct iphdr)+sizeof(struct udphdr)+size;
    unsigned char *frame=calloc(1,length);
    if (!frame) return;
    struct iphdr ip={.version=4,.ihl=5,.ttl=64,.protocol=IPPROTO_UDP,
        .tot_len=htons((uint16_t)(length-ETH_HLEN)),.saddr=source->ip.v4,.daddr=destination->ip.v4};
    ip.check=checksum(&ip,sizeof(ip));
    struct udphdr udp={.source=source->port,.dest=destination->port,.len=htons((uint16_t)(size+sizeof(udp)))};
    memcpy(frame+ETH_HLEN,&ip,sizeof(ip)); memcpy(frame+ETH_HLEN+sizeof(ip),&udp,sizeof(udp));
    memcpy(frame+ETH_HLEN+sizeof(ip)+sizeof(udp),data,size);
    receive(n,link,frame,length,0); free(frame);
}
static long send_packet(struct md_network_raw4 *s,const void *data,size_t size,const struct md_network_send *to) {
    if (to && to->control_size) return -EOPNOTSUPP;
    struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=s->remote};
    if (to && to->size) {
        if (to->size<sizeof(address) || to->size>sizeof(to->value)) return -EINVAL;
        memcpy(&address,to->value,sizeof(address));
        if (address.sin_family!=AF_INET) return -EAFNOSUPPORT;
    } else if (!s->connected) return -EDESTADDRREQ;
    struct md_ip_address destination=md_ip_v4(address.sin_addr.s_addr);
    if (md_ip_unspecified(destination)) destination=md_ip_loopback(AF_INET);
    struct md_network_route_entry route={0};
    struct md_network_interface *link=md_network_route_lookup(s->space,destination,s->device,&route);
    if (!link && s->device) link=md_network_interface_find(s->space,s->device,NULL);
    if (!link) return s->device ? -ENODEV : -ENETUNREACH;
    if (s->device && s->device_policy!=link->policy_id) return -ENODEV;
    if (!(link->info.flags&IFF_UP)) return -ENETDOWN;
    int is_broadcast=md_network_interface_broadcast(link,destination);
    if (is_broadcast && !s->broadcast) return -EACCES;
    if (md_ip_multicast(destination)) return -EOPNOTSUPP;
    size_t header=s->header ? 0 : sizeof(struct iphdr);
    if (size>65535-header || size+header>link->info.mtu) return -EMSGSIZE;
    struct iphdr h={.version=4,.ihl=5,.ttl=(uint8_t)s->ttl,.tos=(uint8_t)s->tos,
        .protocol=(uint8_t)s->protocol,.saddr=s->local,.daddr=destination.v4};
    if (s->header) {
        if (size<sizeof(h)) return -EINVAL;
        memcpy(&h,data,sizeof(h));
        if (h.version!=4 || h.ihl<5 || h.ihl*4U>size) return -EINVAL;
        if (ntohs(h.frag_off)&(IP_MF|IP_OFFMASK)) return -EOPNOTSUPP;
    }
    if (!h.saddr) h.saddr=md_network_route_source(s->space,link,&route,destination).v4;
    if (!h.id) h.id=htons((uint16_t)++s->space->next_ip_id);
    h.tot_len=htons((uint16_t)(size+header)); h.check=0;
    size_t length=ETH_HLEN+header+size;
    unsigned char *frame=malloc(length);
    if (!frame) return -ENOMEM;
    struct ethhdr e={.h_proto=htons(ETH_P_IP)};
    memcpy(e.h_source,link->info.address,ETH_ALEN);
    int error=0;
    int stack=md_network_stack_has_link(s->space,link->info.index) && !md_network_interface_has(link,destination);
    if (is_broadcast) memset(e.h_dest,255,ETH_ALEN);
    else if(!stack) {
        struct md_network_interface *peer=md_network_interface_has(link,destination) ? link
            : md_network_neighbor(s->space,link,destination);
        if (!peer) error=-ENETUNREACH;
        else memcpy(e.h_dest,peer->info.address,ETH_ALEN);
    }
    if (!error) {
        memcpy(frame,&e,sizeof(e)); memcpy(frame+ETH_HLEN+header,data,size);
        memcpy(frame+ETH_HLEN,&h,sizeof(h));
        uint16_t check=checksum(frame+ETH_HLEN,h.ihl*4);
        memcpy(frame+ETH_HLEN+offsetof(struct iphdr,check),&check,sizeof(check));
        size_t ip_header=h.ihl*4U;
        struct md_filter_packet packet=packet_tuple(&h,frame+ETH_HLEN+ip_header,length-ETH_HLEN-ip_header);
        error=stack && h.protocol==IPPROTO_ICMP ? 0
            : md_network_flow_packet_policy(s->space,link,&packet,0,length-ETH_HLEN-ip_header);
        if (!error && link->kind!=MD_LINK_LOOPBACK && md_network_interface_has(link,destination))
            md_network_raw4_receive(s->space,link,frame,length);
        else if (!error) error=stack ? md_network_stack_send_ip(s->space,link->info.index,frame+ETH_HLEN,length-ETH_HLEN)
            : md_network_ethernet_transmit(s->space,link,NULL,frame,length);
    }
    free(frame); return error ? error : (long)size;
}
long md_network_raw4_call(struct md_network_raw4 *s,long nr,const unsigned long *a,void *data,size_t capacity,size_t size,
        const struct md_network_send *destination) {
    if (nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_write) return send_packet(s,data,size,destination);
    if (nr==SYS_bind || nr==SYS_connect) {
        struct sockaddr_in address={0};
        if (size<sizeof(sa_family_t)) return -EINVAL;
        memcpy(&address,data,size<sizeof(address) ? size : sizeof(address));
        if (nr==SYS_connect && address.sin_family==AF_UNSPEC) { s->connected=0; return 0; }
        if (size<sizeof(address)) return -EINVAL;
        if (address.sin_family!=AF_INET) return -EAFNOSUPPORT;
        if (nr==SYS_connect) { s->remote=address.sin_addr.s_addr; s->connected=1; return 0; }
        if (s->bound) return -EINVAL;
        if (address.sin_addr.s_addr && !md_network_local_interface(s->space,md_ip_v4(address.sin_addr.s_addr),0))
            return -EADDRNOTAVAIL;
        s->bound=1; s->local=address.sin_addr.s_addr; return 0;
    }
    if (nr==SYS_getsockname || nr==SYS_getpeername) {
        if (nr==SYS_getpeername && !s->connected) return -ENOTCONN;
        if (capacity<sizeof(struct sockaddr_in)) return -EINVAL;
        struct sockaddr_in address={.sin_family=AF_INET,.sin_port=htons((uint16_t)s->protocol),
            .sin_addr.s_addr=nr==SYS_getpeername ? s->remote : s->local};
        memcpy(data,&address,sizeof(address)); return sizeof(address);
    }
    if (nr!=SYS_setsockopt && nr!=SYS_getsockopt) return -EOPNOTSUPP;
    if (a[1]==SOL_SOCKET && a[2]==SO_BINDTODEVICE) {
        if (nr==SYS_setsockopt) {
            if (size>IFNAMSIZ || (size && !memchr(data,0,size))) return -EINVAL;
            const char *name=size ? data : "";
            struct md_network_interface *link=*name ? md_network_interface_find(s->space,0,name) : NULL;
            if (*name && !link) return -ENODEV;
            s->device=link ? link->info.index : 0; s->device_policy=link ? link->policy_id : 0; return 0;
        }
        if (!s->device) return 0;
        struct md_network_interface *link=md_network_interface_find(s->space,s->device,NULL);
        if (!link || link->policy_id!=s->device_policy) return -ENODEV;
        size_t length=strlen(link->info.name)+1;
        if (capacity<IFNAMSIZ) return -EINVAL;
        memcpy(data,link->info.name,length); return (long)length;
    }
    int *option=NULL,boolean=1;
    if (a[1]==SOL_SOCKET) {
        if (a[2]==SO_BROADCAST) option=&s->broadcast;
        else if (a[2]==SO_TIMESTAMP) option=&s->timestamp;
    } else if (a[1]==IPPROTO_IP) {
        if (a[2]==IP_HDRINCL) option=&s->header;
        else if (a[2]==IP_PKTINFO) option=&s->info;
        else if (a[2]==IP_RECVTTL) option=&s->receive_ttl;
        else if (a[2]==IP_TTL) { option=&s->ttl; boolean=0; }
        else if (a[2]==IP_TOS) { option=&s->tos; boolean=0; }
    }
    if (!option) return -ENOPROTOOPT;
    if (nr==SYS_getsockopt) {
        if (capacity<sizeof(int)) return -EINVAL;
        memcpy(data,option,sizeof(int)); return sizeof(int);
    }
    int value=0;
    if (!size) return -EINVAL;
    if (a[1]==SOL_SOCKET && size<sizeof(int)) return -EINVAL;
    if (size<sizeof(int)) value=*(unsigned char *)data;
    else memcpy(&value,data,sizeof(value));
    if (a[1]==IPPROTO_IP && a[2]==IP_TTL && value==-1) value=64;
    if (!boolean && (value<0 || value>255 || (option==&s->ttl && !value))) return -EINVAL;
    *option=boolean ? !!value : value; return 0;
}
