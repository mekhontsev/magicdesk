#include "network_stack_internal.h"
#include "network_raw_observe.h"
#include "lwip/inet_chksum.h"
#include "lwip/ip.h"
#include "lwip/prot/icmp6.h"
#include "lwip/priv/tcp_priv.h"
#include "lwip/sys.h"
#include <errno.h>
#include <stdlib.h>

static struct md_ip_address address(const ip_addr_t *a) {
    return IP_IS_V6(a) ? md_ip_load(AF_INET6,ip_2_ip6(a)->addr) : md_ip_v4(ip4_addr_get_u32(ip_2_ip4(a)));
}
void md_stack_packet_reject(struct md_stack_interface *i,struct pbuf *original,unsigned protocol,
        struct md_network_rejection rejection) {
    struct md_network_namespace *n=i->owner->space;
    struct md_network_interface *link=md_network_interface_find(n,i->index,NULL);
    struct md_ip_address source=address(ip_current_src_addr()),destination=address(ip_current_dest_addr());
    unsigned header=ip_current_header_tot_len();
    if(!rejection.type || !link || md_ip_unspecified(source) || md_ip_multicast(source)
            || md_ip_multicast(destination) || md_network_interface_broadcast(link,source)
            || md_network_interface_broadcast(link,destination)
            || (original->flags&(PBUF_FLAG_LLBCAST|PBUF_FLAG_LLMCAST))) return;
    unsigned type=pbuf_get_at(original,(u16_t)header);
    if((protocol==IPPROTO_ICMPV6 && type<128)
            || (protocol==IPPROTO_ICMP && (type==3 || type==4 || type==5 || type==11 || type==12))) return;
    /* Only refusals consult the clock. Bound error amplification per namespace;
     * admitted packet and ordinary socket IO have no additional timer work. */
    uint32_t now=sys_now();
    if((uint32_t)(now-i->owner->rejection_epoch)>=1000) {
        i->owner->rejection_epoch=now; i->owner->rejection_count=0;
    }
    if(i->owner->rejection_count>=32) return;
    ++i->owner->rejection_count;
    if(rejection.type==MD_REJECT_TCP) {
        unsigned char tcp[20];
        if(protocol!=IPPROTO_TCP || pbuf_copy_partial(original,tcp,20,(u16_t)header)!=20 || (tcp[13]&4)) return;
        uint32_t seq,ack; uint16_t sport,dport;
        memcpy(&seq,tcp+4,4); memcpy(&ack,tcp+8,4); memcpy(&sport,tcp,2); memcpy(&dport,tcp+2,2);
        unsigned advance=original->tot_len-header-(tcp[12]>>4)*4+!!(tcp[13]&2)+!!(tcp[13]&1);
        tcp_rst_netif(&i->netif,(tcp[13]&16) ? ntohl(ack) : 0,ntohl(seq)+advance,
            ip_current_dest_addr(),ip_current_src_addr(),ntohs(dport),ntohs(sport));
        return;
    }
    int v6=ip_current_is_v6();
    if(rejection.type!=(v6 ? MD_REJECT_ICMP6 : MD_REJECT_ICMP4)) return;
    unsigned overhead=(v6 ? 40 : 20)+8;
    unsigned maximum=v6 ? 1280 : 576;
    if(i->netif.mtu<maximum) maximum=i->netif.mtu;
    if(maximum<overhead+header+8) return;
    unsigned quote=original->tot_len<maximum-overhead ? original->tot_len : maximum-overhead;
    struct pbuf *reply=pbuf_alloc(PBUF_IP,(u16_t)(8+quote),PBUF_RAM);
    if(!reply) return;
    unsigned char *icmp=reply->payload;
    memset(icmp,0,8); icmp[0]=v6 ? 1 : 3; icmp[1]=rejection.code;
    pbuf_copy_partial(original,icmp+8,(u16_t)quote,0);
    if(v6) {
        const ip_addr_t *src=ip6_select_source_address(&i->netif,ip6_current_src_addr());
        if(src) {
            uint16_t sum=ip6_chksum_pseudo(reply,IPPROTO_ICMPV6,reply->tot_len,ip_2_ip6(src),ip6_current_src_addr());
            memcpy(icmp+2,&sum,2);
            ip6_output_if(reply,ip_2_ip6(src),ip6_current_src_addr(),64,0,IPPROTO_ICMPV6,&i->netif);
        }
    } else {
        const ip4_addr_t *src=md_network_interface_has(link,destination)
            ? ip4_current_dest_addr() : netif_ip4_addr(&i->netif);
        uint16_t sum=inet_chksum_pbuf(reply); memcpy(icmp+2,&sum,2);
        ip4_output_if(reply,src,ip4_current_src_addr(),64,0,IPPROTO_ICMP,&i->netif);
    }
    pbuf_free(reply);
}
static int checked_transport(struct pbuf *p,unsigned header,unsigned protocol) {
    unsigned size=p->tot_len-header;
    unsigned minimum=protocol==IPPROTO_TCP ? 20 : 8;
    if(size<minimum) return 0;
    unsigned char fields[20]; pbuf_copy_partial(p,fields,(u16_t)minimum,(u16_t)header);
    if(protocol==IPPROTO_TCP && ((unsigned)(fields[12]>>4)*4<20 || (unsigned)(fields[12]>>4)*4>size)) return 0;
    if(protocol==IPPROTO_UDP) {
        unsigned length=(unsigned)fields[4]*256+fields[5];
        if(length<8 || length>size) return 0;
        size=length;
        if(!fields[6] && !fields[7]) return !ip_current_is_v6();
    }
    if(pbuf_remove_header(p,header)) return 0;
    u16_t sum=protocol==IPPROTO_ICMP ? inet_chksum_pbuf(p)
        : ip_chksum_pseudo_partial(p,(u8_t)protocol,(u16_t)size,(u16_t)size,
            ip_current_src_addr(),ip_current_dest_addr());
    pbuf_add_header_force(p,header);
    return !sum;
}
static void observe(struct md_network_namespace *n,struct md_network_interface *link,
        struct pbuf *p,unsigned header,unsigned protocol) {
    if(ip_current_is_v6() ? !n->raw6 : !n->raw4) return;
    void *data=malloc(p->tot_len); if(!data) return;
    pbuf_copy_partial(p,data,p->tot_len,0);
    if(ip_current_is_v6()) {
        md_network_raw6_observe(n,link,address(ip_current_src_addr()),address(ip_current_dest_addr()),
            protocol,IP6H_HOPLIM(ip6_current_header()),(char *)data+header,p->tot_len-header);
    } else md_network_raw4_observe(n,link,data,p->tot_len);
    free(data);
}
static void group_datagram(struct md_network_namespace *n,struct md_network_interface *link,
        struct pbuf *p,unsigned header) {
    unsigned char udp[8]; pbuf_copy_partial(p,udp,sizeof(udp),(u16_t)header);
    size_t size=(unsigned)udp[4]*256+udp[5]-sizeof(udp);
    void *data=malloc(size ? size : 1); if(!data) return;
    pbuf_copy_partial(p,data,(u16_t)size,(u16_t)(header+sizeof(udp)));
    struct md_filter_packet tuple={.source=address(ip_current_src_addr()),.destination=address(ip_current_dest_addr()),
        .protocol=IPPROTO_UDP,.state=8};
    memcpy(&tuple.source_port,udp,2); memcpy(&tuple.destination_port,udp+2,2);
    unsigned hops=ip_current_is_v6() ? IP6H_HOPLIM(ip6_current_header()) : IPH_TTL(ip4_current_header());
    md_network_forward_datagram(n,link->info.index,&tuple,hops,data,size);
    free(data);
}
static void refused_connection(struct md_stack_interface *i,struct pbuf *p,unsigned header,unsigned protocol) {
    if(protocol!=IPPROTO_ICMP && protocol!=IPPROTO_ICMPV6) return;
    int v6=protocol==IPPROTO_ICMPV6;
    unsigned char icmp[8],ip[40],tcp[8];
    if(pbuf_copy_partial(p,icmp,8,(u16_t)header)!=8 || icmp[0]!=(v6 ? 1 : 3)) return;
    struct md_network_rejection rejection={.type=v6 ? MD_REJECT_ICMP6 : MD_REJECT_ICMP4,.code=icmp[1]};
    if(!md_network_reject_valid(rejection.type,rejection.code)) return;
    unsigned offset=header+8,minimum=v6 ? 40 : 20;
    if(pbuf_copy_partial(p,ip,(u16_t)minimum,(u16_t)offset)!=minimum || ip[0]>>4!=(v6 ? 6 : 4)) return;
    struct md_filter_packet tuple={.source=md_ip_load(v6 ? AF_INET6 : AF_INET,ip+(v6 ? 8 : 12)),
        .destination=md_ip_load(v6 ? AF_INET6 : AF_INET,ip+(v6 ? 24 : 16))};
    if(!md_ip_equal(tuple.source,address(ip_current_dest_addr()))) return;
    unsigned next=ip[v6 ? 6 : 9],length=v6 ? 40 : (ip[0]&15)*4;
    if(length<minimum || (!v6 && ((ip[6]&0x1f) || ip[7]))) return;
    offset+=length;
    /* Quoted IPv6 extension chains are bounded by the received packet. */
    while(v6 && (next==IPPROTO_HOPOPTS || next==IPPROTO_ROUTING || next==IPPROTO_DSTOPTS || next==IPPROTO_FRAGMENT)) {
        unsigned char extension[8];
        if(pbuf_copy_partial(p,extension,8,(u16_t)offset)!=8) return;
        if(next==IPPROTO_FRAGMENT && (extension[2] || (extension[3]&0xf8))) return;
        length=next==IPPROTO_FRAGMENT ? 8 : ((unsigned)extension[1]+1)*8;
        if(length>p->tot_len-offset) return;
        offset+=length; next=extension[0];
    }
    if(next!=IPPROTO_TCP || offset>p->tot_len || pbuf_copy_partial(p,tcp,8,(u16_t)offset)!=8) return;
    memcpy(&tuple.source_port,tcp,2); memcpy(&tuple.destination_port,tcp+2,2);
    uint32_t sequence; memcpy(&sequence,tcp+4,4);
    md_stack_channel_refused(i,&tuple,ntohl(sequence),md_network_reject_error(rejection));
}
int md_stack_packet_input(struct pbuf *p,struct netif *netif,unsigned protocol) {
    struct md_stack_interface *i=netif->state;
    struct md_network_namespace *n=i->owner->space;
    struct md_network_interface *link=md_network_interface_find(n,i->index,NULL);
    unsigned header=ip_current_header_tot_len();
    if(!link || link->policy_id!=i->policy || header>p->tot_len) goto drop;
    int transport=protocol==IPPROTO_TCP || protocol==IPPROTO_UDP;
    if((transport || protocol==IPPROTO_ICMP || protocol==IPPROTO_ICMPV6)
            && !checked_transport(p,header,protocol)) goto drop;
    if(protocol==IPPROTO_UDP && (md_ip_multicast(address(ip_current_dest_addr()))
            || md_network_interface_broadcast(link,address(ip_current_dest_addr())))) {
        group_datagram(n,link,p,header);
        observe(n,link,p,header,protocol);
        goto drop;
    }
    if(!transport) {
        struct md_filter_packet tuple={.source=address(ip_current_src_addr()),.destination=address(ip_current_dest_addr()),
            .protocol=protocol,.state=8};
        /* The transport relay can forward TCP/UDP; other protocols must not
         * mistake an off-host address for a local endpoint. */
        if(!md_ip_multicast(tuple.destination) && !md_network_local_interface(n,tuple.destination,1)) goto drop;
        tuple.transport_size=p->tot_len-header;
        if(tuple.transport_size>sizeof(tuple.transport)) tuple.transport_size=sizeof(tuple.transport);
        pbuf_copy_partial(p,tuple.transport,(u16_t)tuple.transport_size,(u16_t)header);
        if(md_network_flow_packet_policy(n,link,&tuple,1,p->tot_len-header)) {
            md_stack_packet_reject(i,p,protocol,tuple.rejection); goto drop;
        }
    }
    if(transport && md_stack_channel_input(i->owner,i,p,protocol)) return 1;
    observe(n,link,p,header,protocol);
    refused_connection(i,p,header,protocol);
    /* Guest routing daemons see these through raw sockets. Do not let lwIP
     * create a second, unreported route/MTU configuration from the same RA. */
    if(protocol==IPPROTO_ICMPV6) {
        int type=pbuf_get_at(p,(u16_t)header);
        if(type==ICMP6_TYPE_RA || type==ICMP6_TYPE_RD) goto drop;
    }
    return 0;
drop:
    pbuf_free(p); return 1;
}
int md_stack_packet_output(struct md_stack_interface *i,struct pbuf *p) {
    struct md_network_namespace *n=i->owner->space;
    if(!n->filter || !n->filter->chain_count) return 0;
    unsigned offset=i->ethernet ? 14 : 0;
    unsigned char head[40];
    if(i->ethernet) {
        unsigned char type[2];
        if(pbuf_copy_partial(p,type,2,12)!=2 || (type[0]!=8 && type[0]!=0x86)
                || (type[0]==8 ? type[1]!=0 : type[1]!=0xdd)) return 0;
    }
    unsigned copied=pbuf_copy_partial(p,head,sizeof(head),(u16_t)offset);
    unsigned version=copied ? head[0]>>4 : 0;
    unsigned header=version==4 ? (head[0]&15)*4 : 40;
    if((version!=4 && version!=6) || copied<(version==4 ? 20U : 40U) || header<20 || offset+header>p->tot_len) return -EINVAL;
    unsigned protocol=head[version==4 ? 9 : 6];
    if(version==6) {
        /* Walk the actual chain, including the Router Alert header in MLD.
         * Every step consumes at least eight bytes of this bounded packet. */
        while(protocol==IPPROTO_HOPOPTS || protocol==IPPROTO_ROUTING || protocol==IPPROTO_DSTOPTS
                || protocol==IPPROTO_FRAGMENT) {
            unsigned char extension[8];
            if(pbuf_copy_partial(p,extension,8,(u16_t)(offset+header))!=8) return -EINVAL;
            if(protocol==IPPROTO_FRAGMENT && (extension[2] || (extension[3]&0xf8))) return 0;
            unsigned length=protocol==IPPROTO_FRAGMENT ? 8 : ((unsigned)extension[1]+1)*8;
            if(length>p->tot_len-offset-header) return -EINVAL;
            header+=length; protocol=extension[0];
        }
    }
    if(protocol!=IPPROTO_ICMP && protocol!=IPPROTO_ICMPV6) return 0;
    struct md_filter_packet tuple={.protocol=protocol,.state=8,
        .source=md_ip_load(version==4 ? AF_INET : AF_INET6,head+(version==4 ? 12 : 8)),
        .destination=md_ip_load(version==4 ? AF_INET : AF_INET6,head+(version==4 ? 16 : 24))};
    tuple.transport_size=p->tot_len-offset-header;
    if(tuple.transport_size>sizeof(tuple.transport)) tuple.transport_size=sizeof(tuple.transport);
    pbuf_copy_partial(p,tuple.transport,(u16_t)tuple.transport_size,(u16_t)(offset+header));
    struct md_network_interface *link=md_network_interface_find(n,i->index,NULL);
    return link ? md_network_flow_packet_policy(n,link,&tuple,0,p->tot_len-offset-header) : -ENODEV;
}
