#include "network_stack_internal.h"
#include "network_raw_observe.h"
#include "lwip/inet_chksum.h"
#include "lwip/ip.h"
#include "lwip/prot/icmp6.h"
#include <errno.h>
#include <stdlib.h>

static struct md_ip_address address(const ip_addr_t *a) {
    return IP_IS_V6(a) ? md_ip_load(AF_INET6,ip_2_ip6(a)->addr) : md_ip_v4(ip4_addr_get_u32(ip_2_ip4(a)));
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
int md_stack_packet_input(struct pbuf *p,struct netif *netif,unsigned protocol) {
    struct md_stack_interface *i=netif->state;
    struct md_network_namespace *n=i->owner->space;
    struct md_network_interface *link=md_network_interface_find(n,i->index,NULL);
    unsigned header=ip_current_header_tot_len();
    if(!link || link->policy_id!=i->policy || header>p->tot_len) goto drop;
    int transport=protocol==IPPROTO_TCP || protocol==IPPROTO_UDP;
    if((transport || protocol==IPPROTO_ICMP || protocol==IPPROTO_ICMPV6)
            && !checked_transport(p,header,protocol)) goto drop;
    if(!transport) {
        struct md_filter_packet tuple={.source=address(ip_current_src_addr()),.destination=address(ip_current_dest_addr()),
            .protocol=protocol,.state=8};
        tuple.transport_size=p->tot_len-header;
        if(tuple.transport_size>sizeof(tuple.transport)) tuple.transport_size=sizeof(tuple.transport);
        pbuf_copy_partial(p,tuple.transport,(u16_t)tuple.transport_size,(u16_t)header);
        if(md_network_flow_packet_policy(n,link,&tuple,1,p->tot_len-header)) goto drop;
    }
    if(transport && md_stack_channel_input(i->owner,i,p,protocol)) return 1;
    observe(n,link,p,header,protocol);
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
