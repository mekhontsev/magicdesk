#define _GNU_SOURCE
#include "network_stack_internal.h"
#include "network_tun.h"
#include "network_ethernet.h"
#include "lwip/etharp.h"
#include "lwip/ethip6.h"
#include "lwip/ip.h"
#include "lwip/timeouts.h"
#include "netif/ethernet.h"
#include <errno.h>
#include <linux/if_addr.h>
#include <net/if.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/timerfd.h>
#include <unistd.h>

struct md_stack_packet {
    struct md_stack_packet *next;
    unsigned index;
    uint64_t policy;
    size_t size;
    unsigned char data[];
};
int md_stack_error(err_t e) {
    switch(e) {
    case ERR_OK:return 0;
    case ERR_MEM:case ERR_BUF:return -ENOBUFS;
    case ERR_TIMEOUT:return -ETIMEDOUT;
    case ERR_RTE:return -ENETUNREACH;
    case ERR_USE:return -EADDRINUSE;
    case ERR_RST:return -ECONNRESET;
    case ERR_CLSD:return -EPIPE;
    case ERR_ABRT:return -ECONNABORTED;
    case ERR_CONN:return -ENOTCONN;
    case ERR_WOULDBLOCK:case ERR_INPROGRESS:return -EAGAIN;
    default:return -EINVAL;
    }
}
ip_addr_t md_stack_ip(struct md_ip_address a) {
    ip_addr_t out={0}; IP_SET_TYPE_VAL(out,a.family==AF_INET6 ? IPADDR_TYPE_V6 : IPADDR_TYPE_V4);
    if(a.family==AF_INET6) memcpy(ip_2_ip6(&out)->addr,a.bytes,16);
    else ip4_addr_set_u32(ip_2_ip4(&out),a.v4);
    return out;
}
struct md_stack_interface *md_stack_interface(struct md_network_stack *s,unsigned index) {
    for(struct md_stack_interface *i=s->interfaces;i;i=i->next) if(i->index==index) return i;
    return NULL;
}
int md_network_stack_has_link(struct md_network_namespace *n,unsigned index) {
    return n->stack && md_stack_interface(n->stack,index);
}
int md_network_stack_send_ip(struct md_network_namespace *n,unsigned index,const void *data,size_t size) {
    struct md_stack_interface *i=n->stack ? md_stack_interface(n->stack,index) : NULL;
    if(!i) return -ENETDOWN;
    if(size>65535 || size<20) return -EMSGSIZE;
    const unsigned char *bytes=data;
    unsigned version=bytes[0]>>4;
    if((version!=4 && version!=6) || (version==6 && size<40)) return -EINVAL;
    struct md_lwip_context *previous=md_lwip_enter(n->stack->context);
    struct pbuf *p=pbuf_alloc(PBUF_LINK,(u16_t)size,PBUF_RAM);
    err_t error=ERR_MEM;
    if(p) {
        pbuf_take(p,data,(u16_t)size);
        if(version==4) {
            ip4_addr_t destination; memcpy(&destination.addr,bytes+16,4);
            error=i->netif.output(&i->netif,p,&destination);
        } else {
            ip6_addr_t destination={0}; memcpy(destination.addr,bytes+24,16);
            ip6_addr_assign_zone(&destination,IP6_UNICAST,&i->netif);
            error=i->netif.output_ip6(&i->netif,p,&destination);
        }
        pbuf_free(p);
    }
    md_stack_arm(n->stack); md_lwip_leave(previous); return md_stack_error(error);
}
void md_stack_arm(struct md_network_stack *s) {
    u32_t delay=sys_timeouts_sleeptime();
    struct itimerspec timer={0};
    if(delay!=SYS_TIMEOUTS_SLEEPTIME_INFINITE) {
        timer.it_value.tv_sec=delay/1000;
        timer.it_value.tv_nsec=(delay%1000)*1000000L;
        if(!delay) timer.it_value.tv_nsec=1;
    }
    timerfd_settime(s->timer,0,&timer,NULL);
}
static err_t output(struct netif *netif,struct pbuf *p) {
    struct md_stack_interface *i=netif->state;
    struct md_network_namespace *n=i->owner->space;
    struct md_network_interface *link=md_network_interface_find(n,i->index,NULL);
    if(!link || link->policy_id!=i->policy) return ERR_IF;
    if(md_stack_packet_output(i,p)) return ERR_RTE;
    void *data=malloc(p->tot_len); if(!data) return ERR_MEM;
    pbuf_copy_partial(p,data,p->tot_len,0);
    int error=i->ethernet ? md_network_ethernet_transmit(n,link,NULL,data,p->tot_len)
        : md_network_tun_output(n,i->index,data,p->tot_len);
    free(data); return error ? ERR_IF : ERR_OK;
}
static err_t output4(struct netif *n,struct pbuf *p,const ip4_addr_t *a) { (void)a; return output(n,p); }
static err_t output6(struct netif *n,struct pbuf *p,const ip6_addr_t *a) { (void)a; return output(n,p); }
static err_t init_interface(struct netif *n) {
    struct md_stack_interface *i=n->state;
    n->name[0]='m'; n->name[1]='d'; n->mtu=1500; n->hwaddr_len=6;
    n->flags=NETIF_FLAG_BROADCAST|NETIF_FLAG_IGMP|NETIF_FLAG_MLD6;
    if(i->ethernet) n->flags|=NETIF_FLAG_ETHARP|NETIF_FLAG_ETHERNET;
    n->output=i->ethernet ? etharp_output : output4;
    n->output_ip6=i->ethernet ? ethip6_output : output6;
    n->linkoutput=output; return ERR_OK;
}
static const ip4_addr_t *gateway4(struct netif *netif,const ip4_addr_t *a) {
    struct md_stack_interface *i=netif->state;
    struct md_network_route_entry r={0};
    struct md_network_interface *link=md_network_route_lookup(i->owner->space,md_ip_v4(a->addr),i->index,&r);
    if(!link || md_ip_unspecified(r.gateway)) return NULL;
    i->gateway4.addr=r.gateway.v4; return &i->gateway4;
}
static const ip6_addr_t *gateway6(struct netif *netif,const ip6_addr_t *a) {
    struct md_stack_interface *i=netif->state;
    struct md_network_route_entry r={0};
    struct md_network_interface *link=md_network_route_lookup(i->owner->space,md_ip_load(AF_INET6,a->addr),i->index,&r);
    if(!link || md_ip_unspecified(r.gateway)) return NULL;
    memcpy(i->gateway6.addr,r.gateway.bytes,16);
    ip6_addr_assign_zone(&i->gateway6,IP6_UNICAST,netif); return &i->gateway6;
}
static void synchronize(struct md_stack_interface *i,struct md_network_interface *link) {
    struct netif *n=&i->netif;
    n->mtu=(u16_t)(link->info.mtu>65535 ? 65535 : link->info.mtu);
    memcpy(n->hwaddr,link->info.address,6);
    ip4_addr_t address={0},mask={0},gateway={0};
    unsigned v6=0;
    for(unsigned at=0;at<link->address_count;++at) {
        const struct md_network_ip_assignment *a=&link->addresses[at];
        if(a->address.family==AF_INET && !address.addr) {
            address.addr=a->address.v4;
            mask.addr=htonl(a->prefix ? UINT32_MAX<<(32-a->prefix) : 0);
        } else if(a->address.family==AF_INET6 && v6<LWIP_IPV6_NUM_ADDRESSES && !link->ipv6.disabled) {
            ip6_addr_t ip={0}; memcpy(ip.addr,a->address.bytes,16);
            ip6_addr_assign_zone(&ip,IP6_UNICAST,n);
            netif_ip6_addr_set(n,v6,&ip);
            netif_ip6_addr_set_state(n,v6,(a->flags&IFA_F_TENTATIVE) ? IP6_ADDR_TENTATIVE
                : (a->flags&IFA_F_DEPRECATED) ? IP6_ADDR_DEPRECATED : IP6_ADDR_PREFERRED);
            ++v6;
        }
    }
    while(v6<LWIP_IPV6_NUM_ADDRESSES) netif_ip6_addr_set_state(n,v6++,IP6_ADDR_INVALID);
    netif_set_addr(n,&address,&mask,&gateway);
    if(link->info.flags&IFF_UP) netif_set_up(n); else netif_set_down(n);
    if(md_network_interface_snapshot(i->owner->space,link).flags&IFF_RUNNING) netif_set_link_up(n);
    else netif_set_link_down(n);
}
void md_network_stack_changed(struct md_network_namespace *n) {
    struct md_network_stack *s=n->stack; if(!s) return;
    struct md_lwip_context *previous=md_lwip_enter(s->context);
    struct md_stack_interface **at=&s->interfaces;
    while(*at) {
        struct md_stack_interface *i=*at;
        struct md_network_interface *link=md_network_interface_find(n,i->index,NULL);
        if(!link || link->policy_id!=i->policy) {
            *at=i->next; netif_remove(&i->netif); free(i);
        } else { synchronize(i,link); at=&i->next; }
    }
    for(unsigned k=0;k<n->count;++k) {
        struct md_network_interface *link=&n->links[k];
        if((link->kind!=MD_LINK_TUN && link->kind!=MD_LINK_TAP && link->kind!=MD_LINK_BRIDGE
                && link->kind!=MD_LINK_VETH) || md_stack_interface(s,link->info.index)) continue;
        struct md_stack_interface *i=calloc(1,sizeof(*i)); if(!i) continue;
        i->owner=s; i->index=link->info.index; i->policy=link->policy_id; i->ethernet=link->kind!=MD_LINK_TUN;
        if(!netif_add(&i->netif,NULL,NULL,NULL,i,init_interface,i->ethernet ? ethernet_input : ip_input)) { free(i); continue; }
        i->next=s->interfaces; s->interfaces=i; synchronize(i,link);
    }
    if(s->interfaces) netif_set_default(&s->interfaces->netif);
    md_stack_channels_changed(s);
    md_stack_arm(s); md_lwip_leave(previous);
}
static void ready(void *context,uint32_t events) {
    (void)events; struct md_network_stack *s=context;
    uint64_t value;
    while(read(s->event,&value,sizeof(value))>0) {}
    while(read(s->timer,&value,sizeof(value))>0) {}
    struct md_lwip_context *previous=md_lwip_enter(s->context);
    for(unsigned budget=0;s->head && budget<64;++budget) {
        struct md_stack_packet *packet=s->head;
        s->head=packet->next; s->bytes-=packet->size; --s->packets;
        if(!s->head) s->tail=&s->head;
        struct md_stack_interface *i=md_stack_interface(s,packet->index);
        if(i && i->policy==packet->policy) {
            struct pbuf *p=pbuf_alloc(PBUF_RAW,(u16_t)packet->size,PBUF_RAM);
            if(p) {
                pbuf_take(p,packet->data,(u16_t)packet->size);
                if(i->netif.input(p,&i->netif)!=ERR_OK) pbuf_free(p);
            }
        }
        free(packet);
    }
    if(s->head) { value=1; ssize_t ignored=write(s->event,&value,sizeof(value)); (void)ignored; }
    sys_check_timeouts(); md_stack_arm(s); md_lwip_leave(previous);
}
int md_network_stack_prepare(struct md_network_namespace *n) {
    if(n->stack) return 0;
    struct md_network_stack *s=calloc(1,sizeof(*s)); if(!s) return -ENOMEM;
    s->space=n; s->tail=&s->head; s->timer=s->event=-1;
    s->context=md_lwip_context_new();
    int error=s->context ? 0 : -ENOMEM;
    if(!error) {
        s->context->gateway4=gateway4; s->context->gateway6=gateway6;
        s->context->input=md_stack_packet_input;
        s->event=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC);
        s->timer=timerfd_create(CLOCK_MONOTONIC,TFD_NONBLOCK|TFD_CLOEXEC);
        if(s->event<0 || s->timer<0) error=-errno;
    }
    if(!error) error=md_network_event_add(n->fabric,s->event,EPOLLIN,ready,s,&s->event_source);
    if(!error) error=md_network_event_add(n->fabric,s->timer,EPOLLIN,ready,s,&s->timer_source);
    n->stack=s;
    if(error) { md_network_stack_retire(n); return error; }
    atomic_store_explicit(&n->packet_enabled,1,memory_order_release);
    md_network_stack_changed(n); return 0;
}
int md_network_stack_input(struct md_network_namespace *n,struct md_network_interface *link,const void *data,size_t size) {
    struct md_network_stack *s=n->stack;
    if(!s || !md_stack_interface(s,link->info.index)) return -ENETDOWN;
    if(size>65535) return -EMSGSIZE;
    if(s->packets>=1024 || size>4*1024*1024-s->bytes) return -ENOBUFS;
    struct md_stack_packet *p=malloc(sizeof(*p)+size); if(!p) return -ENOMEM;
    *p=(struct md_stack_packet){.size=size,.index=link->info.index,.policy=link->policy_id};
    memcpy(p->data,data,size);
    int empty=!s->head;
    *s->tail=p; s->tail=&p->next; ++s->packets; s->bytes+=size;
    if(empty) { uint64_t one=1; ssize_t ignored=write(s->event,&one,sizeof(one)); (void)ignored; }
    return 0;
}
void md_network_stack_retire(struct md_network_namespace *n) {
    struct md_network_stack *s=n->stack; if(!s) return;
    md_network_event_remove(s->event_source); md_network_event_remove(s->timer_source);
    md_stack_channels_retire(s);
    while(s->head) { struct md_stack_packet *p=s->head; s->head=p->next; free(p); }
    while(s->interfaces) { struct md_stack_interface *i=s->interfaces; s->interfaces=i->next; free(i); }
    md_lwip_context_free(s->context);
    if(s->event>=0) close(s->event);
    if(s->timer>=0) close(s->timer);
    free(s); n->stack=NULL;
}
