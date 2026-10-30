#define _GNU_SOURCE
#include "network_raw6.h"
#include "network_internal.h"
#include "network_ethernet.h"
#include "network_flow.h"
#include "network_forward.h"
#include "network_stack.h"
#include <errno.h>
#include <linux/if_ether.h>
#include <net/if.h>
#include <netinet/ip6.h>
#include <netinet/udp.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/time.h>

void md_network_raw6_attach(struct md_network_raw6 *s,struct md_network_namespace *n,int peer,int protocol) {
    *s=(struct md_network_raw6){.space=n,.peer=peer,.protocol=protocol,
        .checksum=protocol==IPPROTO_ICMPV6 ? 2 : -1,.unicast_hops=-1,.next=n->raw6};
    md_network_multicast_attach(&s->multicast,n);
    n->raw6=s;
}
void md_network_raw6_detach(struct md_network_raw6 *s) {
    struct md_network_raw6 **at=&s->space->raw6;
    while (*at && *at!=s) at=&(*at)->next;
    if (*at) *at=s->next;
    md_network_multicast_destroy(&s->multicast);
}
int md_network_raw6_options(const struct md_network_raw6 *s) {
    return MD_NETWORK_PACKET_IPV6 | (s->receive_info ? MD_NETWORK_PACKET_IPV6_INFO : 0)
        | (s->receive_hops ? MD_NETWORK_PACKET_IPV6_HOPS : 0) | (s->timestamp ? MD_NETWORK_PACKET_TIMESTAMP : 0);
}
static struct md_ip_address ip(const void *a) { return md_ip_load(AF_INET6,a); }
static uint32_t sum_bytes(const void *data,size_t size,uint32_t sum) {
    const unsigned char *p=data;
    while (size>=2) { sum+=(unsigned)p[0]*256+p[1]; p+=2; size-=2; }
    if (size) sum+=(unsigned)*p*256;
    return sum;
}
static uint16_t checksum(const struct ip6_hdr *h,const void *data,size_t size) {
    uint32_t sum=sum_bytes(&h->ip6_src,16,0);
    sum=sum_bytes(&h->ip6_dst,16,sum);
    sum=sum_bytes(data,size,sum+(uint32_t)size+h->ip6_nxt);
    while (sum>>16) sum=(sum&65535)+(sum>>16);
    return htons((uint16_t)~sum);
}
static struct md_filter_packet packet_tuple(const struct ip6_hdr *h,const void *data,size_t size) {
    struct md_filter_packet packet={.source=ip(&h->ip6_src),.destination=ip(&h->ip6_dst),
        .protocol=h->ip6_nxt,.state=8,.transport_size=size<4 ? (unsigned)size : 4};
    if (h->ip6_nxt==IPPROTO_ICMPV6 && size<8) packet.transport_size=0;
    memcpy(packet.transport,data,packet.transport_size);
    if ((h->ip6_nxt==IPPROTO_UDP || h->ip6_nxt==IPPROTO_TCP) && size>=4) {
        memcpy(&packet.source_port,data,2); memcpy(&packet.destination_port,(const char *)data+2,2);
    }
    return packet;
}
static int packet_policy(struct md_network_namespace *n,struct md_network_interface *link,
        const struct ip6_hdr *h,const void *data,size_t size,int input) {
    struct md_filter_packet packet=packet_tuple(h,data,size);
    return md_network_flow_packet_policy(n,link,&packet,input,size);
}
static void reply(struct md_network_namespace *n,struct md_network_interface *link,const struct ethhdr *ether,
        const struct ip6_hdr *input,const void *payload,size_t size,struct md_ip_address source) {
    size_t length=sizeof(struct ethhdr)+sizeof(struct ip6_hdr)+size;
    unsigned char *frame=malloc(length);
    if (!frame) return;
    struct ethhdr e={.h_proto=htons(ETH_P_IPV6)};
    memcpy(e.h_source,link->info.address,6); memcpy(e.h_dest,ether->h_source,6);
    struct ip6_hdr h={.ip6_flow=htonl(6U<<28),.ip6_plen=htons((uint16_t)size),
        .ip6_nxt=IPPROTO_ICMPV6,.ip6_hlim=255,.ip6_src=source.v6,.ip6_dst=input->ip6_src};
    if (md_ip_unspecified(ip(&h.ip6_dst))) {
        h.ip6_dst=(struct in6_addr){{{0xff,2,0,0,0,0,0,0,0,0,0,0,0,0,0,1}}};
        memcpy(e.h_dest,(unsigned char[]){0x33,0x33,0,0,0,1},6);
    }
    memcpy(frame,&e,sizeof(e)); memcpy(frame+sizeof(e),&h,sizeof(h));
    unsigned char *body=frame+sizeof(e)+sizeof(h);
    memcpy(body,payload,size); body[2]=body[3]=0;
    uint16_t check=checksum(&h,body,size); memcpy(body+2,&check,2);
    if (packet_policy(n,link,&h,body,size,0)) { free(frame); return; }
    /* Only echo replies and neighbor advertisements enter here. Neither can
     * generate another automatic reply, so bridge traversal cannot recurse. */
    if (link->kind!=MD_LINK_LOOPBACK && md_network_interface_has(link,ip(&h.ip6_dst)))
        md_network_raw6_receive(n,link,frame,length);
    else md_network_ethernet_transmit(n,link,NULL,frame,length);
    free(frame);
}
static void receive(struct md_network_namespace *n,struct md_network_interface *link,
        const void *frame,size_t length,int udp) {
    if (link->ipv6.disabled || length<sizeof(struct ethhdr)+sizeof(struct ip6_hdr)+8) return;
    const struct ethhdr *ether=frame;
    struct ip6_hdr h; memcpy(&h,(const char *)frame+sizeof(*ether),sizeof(h));
    size_t size=ntohs(h.ip6_plen);
    if ((h.ip6_vfc>>4)!=6 || (h.ip6_nxt!=IPPROTO_ICMPV6 && h.ip6_nxt!=IPPROTO_UDP) || size<8
            || size>length-sizeof(*ether)-sizeof(h)) return;
    const unsigned char *data=(const void *)((const char *)frame+sizeof(*ether)+sizeof(h));
    if (checksum(&h,data,size) || IN6_IS_ADDR_MULTICAST(&h.ip6_src)) return;
    struct md_ip_address source=ip(&h.ip6_src),destination=ip(&h.ip6_dst);
    int local=md_network_interface_has(link,destination);
    if (!local && !(md_ip_multicast(destination) && md_network_multicast_subscribed(n,link,destination))) return;
    if (h.ip6_nxt==IPPROTO_UDP) {
        struct udphdr header; memcpy(&header,data,sizeof(header));
        if (!header.check || ntohs(header.len)!=size) return;
        if (udp) {
            struct md_filter_packet packet=packet_tuple(&h,data,size);
            if (md_network_forward_datagram(n,link->info.index,&packet,h.ip6_hlim,data+sizeof(header),size-sizeof(header))) return;
        }
    } else if (packet_policy(n,link,&h,data,size,1)) return;
    md_network_raw6_observe(n,link,source,destination,h.ip6_nxt,h.ip6_hlim,data,size);
    if (h.ip6_nxt!=IPPROTO_ICMPV6) return;
    if (data[0]==ICMP6_ECHO_REQUEST && !data[1] && local && !md_ip_unspecified(source)) {
        unsigned char *body=malloc(size);
        if (!body) return;
        memcpy(body,data,size); body[0]=ICMP6_ECHO_REPLY;
        reply(n,link,ether,&h,body,size,destination); free(body);
    } else if (data[0]==ND_NEIGHBOR_SOLICIT && !data[1] && h.ip6_hlim==255 && size>=24) {
        struct md_ip_address target=md_ip_load(AF_INET6,data+8);
        if (md_ip_multicast(target) || !md_network_interface_has(link,target)) return;
        for (size_t at=24;at<size;) {
            if (size-at<2 || !data[at+1] || (size_t)data[at+1]*8>size-at) return;
            if (md_ip_unspecified(source) && data[at]==ND_OPT_SOURCE_LINKADDR) return;
            at+=(size_t)data[at+1]*8;
        }
        unsigned char body[32]={ND_NEIGHBOR_ADVERT};
        body[4]=(md_network_forwarding(n,AF_INET6,link->info.index) ? 0x80 : 0)
            | (md_ip_unspecified(source) ? 0 : 0x40) | 0x20;
        memcpy(body+8,target.bytes,16); body[24]=ND_OPT_TARGET_LINKADDR; body[25]=1;
        memcpy(body+26,link->info.address,6);
        reply(n,link,ether,&h,body,sizeof(body),target);
    }
}
void md_network_raw6_observe(struct md_network_namespace *n,struct md_network_interface *link,
        struct md_ip_address source,struct md_ip_address destination,unsigned protocol,unsigned hops,const void *data,size_t size) {
    if(!n->raw6) return;
    struct md_network_packet envelope={.ip={.interface=link->info.index,.hops=hops}};
    memcpy(envelope.ip.source,source.bytes,16); memcpy(envelope.ip.destination,destination.bytes,16);
    struct timeval now; gettimeofday(&now,NULL);
    envelope.ip.seconds=now.tv_sec; envelope.ip.microseconds=now.tv_usec;
    struct iovec vectors[]={{&envelope,sizeof(envelope)},{(void *)data,size}};
    struct msghdr message={.msg_iov=vectors,.msg_iovlen=2};
    for(struct md_network_raw6 *s=n->raw6;s;s=s->next) {
        if((unsigned)s->protocol!=protocol || (s->device && (s->device!=link->info.index || s->device_policy!=link->policy_id))
                || (s->local_scope && (s->local_scope!=link->info.index || s->local_policy!=link->policy_id))
                || (!IN6_IS_ADDR_UNSPECIFIED(&s->local) && !md_ip_equal(ip(&s->local),destination))
                || (s->connected && !md_ip_equal(ip(&s->remote),source))
                || (md_ip_multicast(destination) && !s->multicast.all && !md_network_multicast_joined(&s->multicast,link,destination))
                || (s->protocol==IPPROTO_ICMPV6 && (!size || ICMP6_FILTER_WILLBLOCK(*(const unsigned char *)data,&s->filter)))) continue;
        if(sendmsg(s->peer,&message,MSG_DONTWAIT|MSG_NOSIGNAL)<0 && errno!=ECONNREFUSED) s->overflow=1;
    }
}
void md_network_raw6_receive(struct md_network_namespace *n,struct md_network_interface *link,
        const void *frame,size_t length) { receive(n,link,frame,length,1); }
void md_network_raw6_reply(struct md_network_namespace *n,struct md_network_interface *link,
        const struct md_inet_address *source,const struct md_inet_address *destination,const void *data,size_t size) {
    if (source->ip.family!=AF_INET6 || destination->ip.family!=AF_INET6 || size>65527 || !n->raw6) return;
    size_t length=ETH_HLEN+sizeof(struct ip6_hdr)+sizeof(struct udphdr)+size;
    unsigned char *frame=calloc(1,length);
    if (!frame) return;
    struct ip6_hdr ip={.ip6_flow=htonl(6U<<28),.ip6_nxt=IPPROTO_UDP,.ip6_hlim=64,
        .ip6_plen=htons((uint16_t)(size+sizeof(struct udphdr))),.ip6_src=source->ip.v6,.ip6_dst=destination->ip.v6};
    struct udphdr udp={.source=source->port,.dest=destination->port,.len=ip.ip6_plen};
    memcpy(frame+ETH_HLEN,&ip,sizeof(ip)); memcpy(frame+ETH_HLEN+sizeof(ip),&udp,sizeof(udp));
    unsigned char *body=frame+ETH_HLEN+sizeof(ip);
    memcpy(body+sizeof(udp),data,size);
    udp.check=checksum(&ip,body,size+sizeof(udp));
    if (!udp.check) udp.check=UINT16_MAX;
    memcpy(body+offsetof(struct udphdr,check),&udp.check,2);
    receive(n,link,frame,length,0); free(frame);
}
struct send_options {
    struct in6_pktinfo info;
    int hops, traffic_class;
};
static int send_options(const struct md_network_send *to,struct send_options *out) {
    *out=(struct send_options){.hops=-1,.traffic_class=-1};
    if (!to) return 0;
    if (to->control_size>sizeof(to->control)) return -EMSGSIZE;
    for (size_t at=0;to->control_size-at>=sizeof(struct cmsghdr);) {
        const struct cmsghdr *c=(const void *)(to->control+at);
        if (c->cmsg_len<CMSG_LEN(0) || c->cmsg_len>to->control_size-at) return -EINVAL;
        if (c->cmsg_level!=IPPROTO_IPV6) return -EOPNOTSUPP;
        if (c->cmsg_type==IPV6_PKTINFO) {
            if (c->cmsg_len!=CMSG_LEN(sizeof(out->info))) return -EINVAL;
            memcpy(&out->info,CMSG_DATA(c),sizeof(out->info));
        } else if (c->cmsg_type==IPV6_HOPLIMIT || c->cmsg_type==IPV6_TCLASS) {
            int value;
            if (c->cmsg_len!=CMSG_LEN(sizeof(value))) return -EINVAL;
            memcpy(&value,CMSG_DATA(c),sizeof(value));
            if (value< -1 || value>255) return -EINVAL;
            if (c->cmsg_type==IPV6_HOPLIMIT) out->hops=value;
            else out->traffic_class=value;
        } else return -EOPNOTSUPP;
        size_t step=CMSG_ALIGN(c->cmsg_len);
        if (step>to->control_size-at) break;
        at+=step;
    }
    return 0;
}
static long send_packet(struct md_network_raw6 *s,const void *data,size_t size,const struct md_network_send *to) {
    if (s->protocol==IPPROTO_ICMPV6 && size<sizeof(struct icmp6_hdr)) return -EINVAL;
    if (s->checksum>=0 && ((size_t)s->checksum>size || size-(size_t)s->checksum<2)) return -EINVAL;
    if (size>65535) return -EMSGSIZE;
    struct send_options options;
    int error=send_options(to,&options);
    if (error) return error;
    struct sockaddr_in6 address={.sin6_family=AF_INET6,.sin6_addr=s->remote,.sin6_scope_id=s->remote_scope};
    if (to && to->size) {
        if (to->size<sizeof(address) || to->size>sizeof(to->value)) return -EINVAL;
        memcpy(&address,to->value,sizeof(address));
        if (address.sin6_family!=AF_INET6) return -EAFNOSUPPORT;
    } else if (!s->connected) return -EDESTADDRREQ;
    struct md_ip_address destination=ip(&address.sin6_addr);
    if (md_ip_unspecified(destination)) destination=md_ip_loopback(AF_INET6);
    unsigned index=address.sin6_scope_id ? address.sin6_scope_id : s->device ? s->device
        : md_ip_multicast(destination) ? s->multicast.output : s->local_scope;
    if (options.info.ipi6_ifindex) {
        if (address.sin6_scope_id && address.sin6_scope_id!=(unsigned)options.info.ipi6_ifindex) return -EINVAL;
        index=options.info.ipi6_ifindex;
    }
    if (index && s->device && index!=s->device) return -EINVAL;
    struct md_network_route_entry route={0};
    struct md_network_interface *link=md_network_route_lookup(s->space,destination,index,&route);
    if (!link && index) link=md_network_interface_find(s->space,index,NULL);
    if (!link) return index ? -ENODEV : -ENETUNREACH;
    if ((s->device && s->device_policy!=link->policy_id)
            || (s->local_scope && (s->local_scope!=link->info.index || s->local_policy!=link->policy_id))
            || (md_ip_multicast(destination) && !options.info.ipi6_ifindex && !address.sin6_scope_id && !s->device && s->multicast.output
                && s->multicast.output_policy!=link->policy_id)) return -ENODEV;
    if (!(link->info.flags&IFF_UP)) return -ENETDOWN;
    if (link->ipv6.disabled) return -EADDRNOTAVAIL;
    if (size+sizeof(struct ip6_hdr)>link->info.mtu || size+sizeof(struct ip6_hdr)>link->ipv6.mtu) return -EMSGSIZE;
    struct md_ip_address source=ip(IN6_IS_ADDR_UNSPECIFIED(&options.info.ipi6_addr) ? &s->local : &options.info.ipi6_addr);
    if (md_ip_unspecified(source)) source=md_ip_multicast(destination) ? md_network_interface_source(link,destination)
        : md_network_route_source(s->space,link,&route,destination);
    if (md_ip_unspecified(source)) return -EADDRNOTAVAIL;
    if (md_ip_linklocal(source) ? !md_network_interface_has(link,source)
            : !md_network_local_interface(s->space,source,0)) return -EADDRNOTAVAIL;
    size_t length=sizeof(struct ethhdr)+sizeof(struct ip6_hdr)+size;
    unsigned char *frame=malloc(length);
    if (!frame) return -ENOMEM;
    struct ethhdr ether={.h_proto=htons(ETH_P_IPV6)};
    memcpy(ether.h_source,link->info.address,6);
    int stack=md_network_stack_has_link(s->space,link->info.index) && !md_network_interface_has(link,destination);
    int found=stack || md_ip_multicast(destination);
    if (md_ip_multicast(destination)) { ether.h_dest[0]=ether.h_dest[1]=0x33; memcpy(ether.h_dest+2,destination.bytes+12,4); }
    else if(!stack) {
        struct md_network_interface *neighbor=md_network_interface_has(link,destination) ? link
            : md_network_neighbor(s->space,link,destination);
        if (neighbor) { memcpy(ether.h_dest,neighbor->info.address,6); found=1; }
    }
    error=found ? 0 : -ENETUNREACH;
    if (!error) {
        unsigned traffic_class=options.traffic_class<0 ? (unsigned)s->traffic_class : (unsigned)options.traffic_class;
        struct ip6_hdr h={.ip6_flow=htonl((6U<<28)|(traffic_class<<20)),.ip6_plen=htons((uint16_t)size),
            .ip6_nxt=(uint8_t)s->protocol,.ip6_hlim=options.hops>=0 ? (unsigned)options.hops
                : md_ip_multicast(destination) ? (unsigned)s->multicast.hops
                : s->unicast_hops<0 ? link->ipv6.hop_limit : (unsigned)s->unicast_hops,
            .ip6_src=source.v6,.ip6_dst=destination.v6};
        memcpy(frame,&ether,sizeof(ether)); memcpy(frame+sizeof(ether),&h,sizeof(h));
        unsigned char *body=frame+sizeof(ether)+sizeof(h);
        memcpy(body,data,size);
        if (s->checksum>=0) {
            body[s->checksum]=body[s->checksum+1]=0;
            uint16_t check=checksum(&h,body,size);
            if (!check && s->protocol==IPPROTO_UDP) check=UINT16_MAX;
            memcpy(body+s->checksum,&check,2);
        }
        error=stack && h.ip6_nxt==IPPROTO_ICMPV6 ? 0 : packet_policy(s->space,link,&h,body,size,0);
        if (!error && link->kind!=MD_LINK_LOOPBACK && (md_network_interface_has(link,destination)
                || (md_ip_multicast(destination) && s->multicast.loop)))
            md_network_raw6_receive(s->space,link,frame,length);
        if (!error && (!md_network_interface_has(link,destination) || link->kind==MD_LINK_LOOPBACK))
            error=stack ? md_network_stack_send_ip(s->space,link->info.index,frame+ETH_HLEN,length-ETH_HLEN)
                : md_network_ethernet_transmit(s->space,link,NULL,frame,length);
    }
    free(frame); return error ? error : (long)size;
}
long md_network_raw6_call(struct md_network_raw6 *s,long nr,const unsigned long *a,void *data,size_t capacity,size_t size,
        const struct md_network_send *destination) {
    if (nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_write) return send_packet(s,data,size,destination);
    if (nr==SYS_bind || nr==SYS_connect) {
        if (size<sizeof(sa_family_t)) return -EINVAL;
        struct sockaddr_in6 address={0};
        memcpy(&address,data,size<sizeof(address) ? size : sizeof(address));
        if (nr==SYS_connect && address.sin6_family==AF_UNSPEC) { s->connected=0; return 0; }
        if (size<sizeof(address)) return -EINVAL;
        if (address.sin6_family!=AF_INET6) return -EAFNOSUPPORT;
        if (nr==SYS_connect) {
            s->remote=address.sin6_addr; s->remote_scope=address.sin6_scope_id; s->connected=1; return 0;
        }
        if (s->bound) return -EINVAL;
        struct md_network_interface *scoped=address.sin6_scope_id
            ? md_network_interface_find(s->space,address.sin6_scope_id,NULL) : NULL;
        if (address.sin6_scope_id && !scoped) return -ENODEV;
        if (IN6_IS_ADDR_LINKLOCAL(&address.sin6_addr) && !scoped && !s->device) return -EINVAL;
        if (!IN6_IS_ADDR_UNSPECIFIED(&address.sin6_addr)) {
            int found=0;
            for (unsigned i=0;i<s->space->count;++i) if ((!address.sin6_scope_id
                    || address.sin6_scope_id==s->space->links[i].info.index)
                    && md_network_interface_has(&s->space->links[i],ip(&address.sin6_addr))) found=1;
            if (!found) return -EADDRNOTAVAIL;
        }
        s->local=address.sin6_addr; s->local_scope=address.sin6_scope_id;
        s->local_policy=scoped ? scoped->policy_id : 0; s->bound=1; return 0;
    }
    if (nr==SYS_getsockname || nr==SYS_getpeername) {
        if (capacity<sizeof(struct sockaddr_in6)) return -EINVAL;
        if (nr==SYS_getpeername && !s->connected) return -ENOTCONN;
        struct sockaddr_in6 address={.sin6_family=AF_INET6,.sin6_port=htons((uint16_t)s->protocol),
            .sin6_addr=nr==SYS_getsockname ? s->local : s->remote,
            .sin6_scope_id=nr==SYS_getsockname ? s->local_scope : s->remote_scope};
        memcpy(data,&address,sizeof(address)); return sizeof(address);
    }
    int set=nr==SYS_setsockopt;
    if (!set && nr!=SYS_getsockopt) return -EOPNOTSUPP;
    if (a[1]==IPPROTO_ICMPV6 && a[2]==ICMP6_FILTER) {
        if (s->protocol!=IPPROTO_ICMPV6) return -ENOPROTOOPT;
        if (set) { if (size<sizeof(s->filter)) return -EINVAL; memcpy(&s->filter,data,sizeof(s->filter)); return 0; }
        if (capacity<sizeof(s->filter)) return -EINVAL;
        memcpy(data,&s->filter,sizeof(s->filter)); return sizeof(s->filter);
    }
    if (a[1]==IPPROTO_IPV6 && a[2]==IPV6_CHECKSUM) {
        if (!set) { if (capacity<sizeof(int)) return -EINVAL; memcpy(data,&s->checksum,sizeof(int)); return sizeof(int); }
        int value;
        if (s->protocol==IPPROTO_ICMPV6 || size<sizeof(value)) return -EINVAL;
        memcpy(&value,data,sizeof(value));
        if (value>=0 && (value&1)) return -EINVAL;
        s->checksum=value<0 ? -1 : value; return 0;
    }
    if (a[1]==SOL_SOCKET && a[2]==SO_BINDTODEVICE) {
        if (!set) {
            struct md_network_interface *link=md_network_interface_find(s->space,s->device,NULL);
            size_t length=link && link->policy_id==s->device_policy ? strlen(link->info.name)+1 : 0;
            if (capacity<length) return -EINVAL;
            if (length) memcpy(data,link->info.name,length);
            return length;
        }
        if (!size || !*(char *)data) { s->device=0; s->device_policy=0; return 0; }
        if (size>IFNAMSIZ || !memchr(data,0,size)) return -EINVAL;
        struct md_network_interface *link=md_network_interface_find(s->space,0,data);
        if (!link) return -ENODEV;
        s->device=link->info.index; s->device_policy=link->policy_id; return 0;
    }
    if (a[1]==IPPROTO_IPV6 && md_network_multicast_option(a[1],a[2])) {
        socklen_t length=(socklen_t)(set ? size : capacity);
        int error=md_network_multicast_option_io(s->space,&s->multicast,AF_INET6,s->device,set,a[1],a[2],data,&length);
        return error ? error : set ? 0 : (long)length;
    }
    int *field=NULL,low=0,high=1;
    if (a[1]==IPPROTO_IPV6) {
        switch (a[2]) {
        case IPV6_UNICAST_HOPS: field=&s->unicast_hops; low=-1; high=255; break;
        case IPV6_RECVHOPLIMIT: field=&s->receive_hops; break;
        case IPV6_RECVPKTINFO: field=&s->receive_info; break;
        case IPV6_TCLASS: field=&s->traffic_class; low=-1; high=255; break;
        default: return -ENOPROTOOPT;
        }
    } else if (a[1]==SOL_SOCKET && a[2]==SO_TIMESTAMP) field=&s->timestamp;
    else if (a[1]==SOL_SOCKET && a[2]==SO_BROADCAST) field=&s->broadcast;
#ifdef SO_BINDTOIFINDEX
    else if (a[1]==SOL_SOCKET && a[2]==SO_BINDTOIFINDEX) {
        if (!set) { if (capacity<sizeof(int)) return -EINVAL; memcpy(data,&s->device,4); return 4; }
        int index; if (size<sizeof(index)) return -EINVAL; memcpy(&index,data,sizeof(index));
        struct md_network_interface *link=index ? md_network_interface_find(s->space,(unsigned)index,NULL) : NULL;
        if (index && !link) return -ENODEV;
        s->device=(unsigned)index; s->device_policy=link ? link->policy_id : 0; return 0;
    }
#endif
    if (!field) return -ENOPROTOOPT;
    if (!set) { if (capacity<sizeof(int)) return -EINVAL; memcpy(data,field,sizeof(int)); return sizeof(int); }
    int value; if (size<sizeof(value)) return -EINVAL; memcpy(&value,data,sizeof(value));
    if (value<low || value>high) return -EINVAL;
    if (value==-1 && a[2]==IPV6_TCLASS) value=0;
    *field=value; return 0;
}
