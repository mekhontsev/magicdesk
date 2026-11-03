#define _GNU_SOURCE
#include "network_vxlan.h"
#include "network_internal.h"
#include "network_endpoint.h"
#include "network_forward.h"
#include "network_stack.h"
#include "network_ethernet.h"
#include "network_neighbor.h"
#include "network_message.h"
#include "netlink_attributes.h"
#include <linux/if_link.h>
#include <net/if.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <unistd.h>

struct md_network_vxlan_port {
    struct md_network_vxlan_port *next;
    struct md_network_namespace *space;
    struct md_network_vxlan_config config;
    struct md_fd_object *endpoint;
    struct md_network_event_source *event;
    int fd;
};
struct md_network_vxlan_update {
    struct md_network_namespace *space;
    struct md_network_vxlan_port *added;
    int had_stack, packet_enabled;
};
int md_network_vxlan_parse(const void *data,size_t size,struct md_network_vxlan_config *config) {
    *config=(struct md_network_vxlan_config){.port=htons(8472),.local=md_ip_any(AF_INET),.remote=md_ip_any(AF_INET)};
    struct md_netlink_cursor cursor={data,size}; struct md_netlink_attribute a;
    uint64_t seen=0; int result,has_id=0,learning=1,local_family=0,remote_family=0;
    while((result=md_netlink_attribute_next(&cursor,&a))>0) {
        if(!a.type || a.type>=64) return -EOPNOTSUPP;
        if(seen&(UINT64_C(1)<<a.type)) return -EINVAL;
        seen|=UINT64_C(1)<<a.type;
        uint32_t value=0;
        if(a.size<=4) memcpy(&value,a.data,a.size);
        switch(a.type) {
        case IFLA_VXLAN_ID: case IFLA_VXLAN_LINK:
            if(a.size!=4) return -EINVAL;
            if(a.type==IFLA_VXLAN_ID) { config->id=value; has_id=1; } else config->link=value;
            break;
        case IFLA_VXLAN_LOCAL: case IFLA_VXLAN_GROUP: case IFLA_VXLAN_LOCAL6: case IFLA_VXLAN_GROUP6: {
            int local=a.type==IFLA_VXLAN_LOCAL || a.type==IFLA_VXLAN_LOCAL6;
            unsigned family=a.type==IFLA_VXLAN_LOCAL6 || a.type==IFLA_VXLAN_GROUP6 ? AF_INET6 : AF_INET;
            if(local ? local_family : remote_family) return -EINVAL;
            if(local) local_family=(int)family; else remote_family=(int)family;
            if(a.size!=md_ip_size(md_ip_any(family))) return -EINVAL;
            struct md_ip_address ip=md_ip_load(family,a.data);
            if(local) config->local=ip; else config->remote=ip;
            break;
        }
        case IFLA_VXLAN_PORT:
            if(a.size!=2 || !value) return -EINVAL;
            config->port=(uint16_t)value; break;
        case IFLA_VXLAN_TTL: case IFLA_VXLAN_TOS: case IFLA_VXLAN_LEARNING:
            if(a.size!=1) return -EINVAL;
            if(a.type==IFLA_VXLAN_TTL) config->ttl=(unsigned char)value;
            else if(a.type==IFLA_VXLAN_TOS) config->tos=(unsigned char)value;
            else { if(value>1) return -EINVAL; learning=(int)value; }
            break;
        case IFLA_VXLAN_AGEING: case IFLA_VXLAN_LIMIT:
            if(a.size!=4) return -EINVAL;
            if(value) return -EOPNOTSUPP;
            break;
        case IFLA_VXLAN_PROXY: case IFLA_VXLAN_RSC: case IFLA_VXLAN_L2MISS: case IFLA_VXLAN_L3MISS:
        case IFLA_VXLAN_UDP_ZERO_CSUM6_TX: case IFLA_VXLAN_UDP_ZERO_CSUM6_RX:
            if(a.size!=1) return -EINVAL;
            if(value) return -EOPNOTSUPP;
            break;
        case IFLA_VXLAN_UDP_CSUM:
            if(a.size!=1 || value>1) return -EINVAL;
            /* Native UDP always supplies a valid checksum. */
            break;
        default: return -EOPNOTSUPP;
        }
    }
    if(result) return result;
    if(!has_id || config->id>0xffffff) return -EINVAL;
    if(learning || config->tos || config->ttl) return -EOPNOTSUPP;
    if(local_family && remote_family && local_family!=remote_family) return -EINVAL;
    if(md_ip_multicast(config->local) || md_ip_multicast(config->remote)) return -EOPNOTSUPP;
    if(!md_ip_unspecified(config->remote)) {
        if(md_ip_unspecified(config->local)) config->local=md_ip_any(config->remote.family);
        if(config->local.family!=config->remote.family) return -EINVAL;
    } else config->remote=md_ip_any(config->local.family);
    return 0;
}
int md_network_vxlan_reply(struct md_network_message *out,const struct md_network_vxlan_config *c) {
    size_t nest; unsigned char zero=0,one=1;
    int error=md_network_message_nest(out,IFLA_INFO_DATA,&nest);
    if(!error) error=md_network_message_attribute(out,IFLA_VXLAN_ID,&c->id,4);
    if(!error && c->link) error=md_network_message_attribute(out,IFLA_VXLAN_LINK,&c->link,4);
    if(!error) error=md_network_message_attribute(out,c->local.family==AF_INET6 ? IFLA_VXLAN_LOCAL6 : IFLA_VXLAN_LOCAL,c->local.bytes,md_ip_size(c->local));
    if(!error && !md_ip_unspecified(c->remote)) error=md_network_message_attribute(out,c->remote.family==AF_INET6 ? IFLA_VXLAN_GROUP6 : IFLA_VXLAN_GROUP,c->remote.bytes,md_ip_size(c->remote));
    if(!error) error=md_network_message_attribute(out,IFLA_VXLAN_PORT,&c->port,2);
    if(!error) error=md_network_message_attribute(out,IFLA_VXLAN_LEARNING,&zero,1);
    if(!error) error=md_network_message_attribute(out,IFLA_VXLAN_UDP_CSUM,&one,1);
    if(!error) error=md_network_message_attribute(out,IFLA_VXLAN_TTL,&c->ttl,1);
    if(!error) error=md_network_message_attribute(out,IFLA_VXLAN_TOS,&c->tos,1);
    return error ? error : md_network_message_end_nest(out,nest);
}
static int matches(const struct md_network_vxlan_port *p,const struct md_network_vxlan_config *c) {
    return p->config.port==c->port && p->config.link==c->link && md_ip_equal(p->config.local,c->local)
        && p->config.ttl==c->ttl && p->config.tos==c->tos;
}
static void ready(void *context,uint32_t events) {
    (void)events; struct md_network_vxlan_port *p=context;
    unsigned char data[65536];
    for(unsigned budget=0;budget<32;++budget) {
        struct md_inet_address source;
        ssize_t size=md_inet_recvfrom(p->fd,data,sizeof(data),MSG_DONTWAIT,&source);
        if(size<0) break;
        if(size<22 || data[0]!=8 || data[1] || data[2] || data[3] || data[7]) continue;
        unsigned id=(unsigned)data[4]<<16|(unsigned)data[5]<<8|data[6];
        for(unsigned i=0;i<p->space->count;++i) {
            struct md_network_interface *l=&p->space->links[i];
            if(l->kind!=MD_LINK_VXLAN || l->vxlan.id!=id || !matches(p,&l->vxlan)
                    || !(l->info.flags&IFF_UP) || (size_t)size>l->info.mtu+22U) continue;
            md_network_ethernet_receive(p->space,l,data+8,(size_t)size-8); break;
        }
    }
}
static void close_port(struct md_network_vxlan_port *p) {
    md_network_event_remove(p->event);
    md_network_endpoint_service_close(p->endpoint);
    if(p->fd>=0) close(p->fd);
    free(p);
}
int md_network_vxlan_prepare(struct md_network_namespace *n,struct md_network_vxlan_update **out) {
    *out=NULL;
    int present=n->vxlan!=NULL;
    for(unsigned i=0;!present && i<n->count;++i) present=n->links[i].kind==MD_LINK_VXLAN;
    if(!present) return 0;
    struct md_network_vxlan_update *u=calloc(1,sizeof(*u)); if(!u) return -ENOMEM;
    u->space=n; u->had_stack=n->stack!=NULL;
    u->packet_enabled=atomic_load_explicit(&n->packet_enabled,memory_order_acquire);
    int error=0;
    for(unsigned i=0;!error && i<n->count;++i) {
        struct md_network_interface *l=&n->links[i];
        if(l->kind!=MD_LINK_VXLAN) continue;
        struct md_network_vxlan_config *c=&l->vxlan;
        struct md_network_interface *underlay=c->link ? md_network_interface_find(n,c->link,NULL) : NULL;
        if(c->link && (!underlay || underlay->kind==MD_LINK_VXLAN)) { error=-ENODEV; break; }
        for(unsigned j=0;j<i;++j) {
            struct md_network_interface *other=&n->links[j];
            if(other->kind==MD_LINK_VXLAN && c->id==other->vxlan.id && c->port==other->vxlan.port
                    && c->link==other->vxlan.link && md_ip_equal(c->local,other->vxlan.local)) error=-EEXIST;
        }
        if(error) break;
        struct md_network_vxlan_port *p=n->vxlan;
        while(p && !matches(p,c)) p=p->next;
        if(!p) { p=u->added; while(p && !matches(p,c)) p=p->next; }
        if(p) continue;
        p=calloc(1,sizeof(*p)); if(!p) { error=-ENOMEM; break; }
        *p=(struct md_network_vxlan_port){.space=n,.config=*c,.fd=-1};
        struct md_inet_address local={.ip=c->local,.port=c->port,.scope=md_ip_linklocal(c->local) ? c->link : 0};
        error=md_network_endpoint_service_open(n,local,c->link,&p->endpoint,&p->fd);
        if(!error) error=md_network_event_add(n->fabric,p->fd,EPOLLIN,ready,p,&p->event);
        if(error) { close_port(p); break; }
        p->next=u->added; u->added=p;
    }
    if(!error) error=md_network_stack_prepare(n);
    if(error) { md_network_vxlan_finish(u,0); return error; }
    *out=u; return 0;
}
void md_network_vxlan_finish(struct md_network_vxlan_update *u,int commit) {
    if(!u) return;
    while(u->added) {
        struct md_network_vxlan_port *p=u->added; u->added=p->next;
        if(commit) { p->next=u->space->vxlan; u->space->vxlan=p; }
        else close_port(p);
    }
    if(commit) md_network_vxlan_changed(u->space);
    if(!commit && !u->had_stack) {
        md_network_stack_retire(u->space);
        atomic_store_explicit(&u->space->packet_enabled,u->packet_enabled,memory_order_release);
    }
    free(u);
}
void md_network_vxlan_changed(struct md_network_namespace *n) {
    for(struct md_network_vxlan_port **at=&n->vxlan;*at;) {
        struct md_network_vxlan_port *p=*at;
        unsigned i=0;
        for(;i<n->count;++i)
            if(n->links[i].kind==MD_LINK_VXLAN && matches(p,&n->links[i].vxlan)) break;
        if(i==n->count) { *at=p->next; close_port(p); } else at=&p->next;
    }
}
void md_network_vxlan_retire(struct md_network_namespace *n) {
    while(n->vxlan) { struct md_network_vxlan_port *p=n->vxlan; n->vxlan=p->next; close_port(p); }
}
int md_network_vxlan_output(struct md_network_namespace *n,struct md_network_interface *l,const void *data,size_t size) {
    if(size<14 || size>l->info.mtu+14U || size>65507-8) return -EMSGSIZE;
    struct md_network_vxlan_port *p=n->vxlan;
    while(p && !matches(p,&l->vxlan)) p=p->next;
    if(!p) return -ENETDOWN;
    struct md_ip_address remote;
    static const unsigned char flood[6]={0};
    if(!md_network_neighbor_remote(n,l,data,&remote) && !md_network_neighbor_remote(n,l,flood,&remote)) remote=l->vxlan.remote;
    if(md_ip_unspecified(remote)) return 0;
    if(remote.family!=l->vxlan.local.family) return -EAFNOSUPPORT;
    struct md_inet_address target={.ip=remote,.port=l->vxlan.port,.scope=md_ip_linklocal(remote) ? l->vxlan.link : 0};
    struct sockaddr_storage destination; socklen_t length;
    int error=md_network_endpoint_service_destination(p->endpoint,target,&destination,&length);
    if(error) return error;
    unsigned id=l->vxlan.id;
    unsigned char header[8]={8,0,0,0,(unsigned char)(id>>16),(unsigned char)(id>>8),(unsigned char)id,0};
    struct iovec vectors[]={{header,sizeof(header)},{(void *)data,size}};
    struct msghdr message={.msg_name=&destination,.msg_namelen=length,.msg_iov=vectors,.msg_iovlen=2};
    ssize_t sent=sendmsg(p->fd,&message,MSG_DONTWAIT|MSG_NOSIGNAL);
    return sent<0 ? -errno : 0;
}
