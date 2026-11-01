#define _GNU_SOURCE
#include "network_ethernet.h"
#include "network_tun.h"
#include "network_stack.h"
#include "network_internal.h"
#include "network_raw6.h"
#include "network_raw4.h"
#include "network_link.h"
#include "network_tc.h"
#include <errno.h>
#include <limits.h>
#include <linux/if_ether.h>
#include <linux/if_arp.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>

struct md_ethernet_membership {
    struct md_ethernet_membership *next;
    struct packet_mreq request;
    uint64_t policy;
    unsigned count;
};
void md_network_ethernet_attach(struct md_network_ethernet *s,struct md_network_namespace *n,int peer,int type,int protocol) {
    *s=(struct md_network_ethernet){.space=n,.peer=peer,.type=type,.protocol=(uint16_t)protocol,.next=n->ethernet};
    n->ethernet=s;
}
void md_network_ethernet_detach(struct md_network_ethernet *s) {
    struct md_network_ethernet **at=&s->space->ethernet;
    while (*at && *at!=s) at=&(*at)->next;
    if (*at) *at=s->next;
    while (s->memberships) {
        struct md_ethernet_membership *m=s->memberships;
        s->memberships=m->next; free(m);
    }
}
static int membership(struct md_network_ethernet *s,int option,const void *data,size_t size) {
    if (size<sizeof(struct packet_mreq)) return -EINVAL;
    struct packet_mreq request; memcpy(&request,data,sizeof(request));
    if (request.mr_ifindex<=0 || request.mr_alen>ETH_ALEN) return -EINVAL;
    if (request.mr_type!=PACKET_MR_MULTICAST && request.mr_type!=PACKET_MR_UNICAST
            && request.mr_type!=PACKET_MR_PROMISC && request.mr_type!=PACKET_MR_ALLMULTI) return -EOPNOTSUPP;
    if ((request.mr_type==PACKET_MR_MULTICAST || request.mr_type==PACKET_MR_UNICAST)
            && request.mr_alen!=ETH_ALEN) return -EINVAL;
    struct md_network_interface *link=md_network_interface_find(s->space,(unsigned)request.mr_ifindex,NULL);
    if (!link) return option==PACKET_DROP_MEMBERSHIP ? 0 : -ENODEV;
    struct md_ethernet_membership **at=&s->memberships;
    while (*at && ((*at)->policy!=link->policy_id || (*at)->request.mr_type!=request.mr_type
            || (*at)->request.mr_alen!=request.mr_alen
            || memcmp((*at)->request.mr_address,request.mr_address,request.mr_alen))) at=&(*at)->next;
    if (option==PACKET_DROP_MEMBERSHIP) {
        if (*at && !--(*at)->count) { struct md_ethernet_membership *old=*at; *at=old->next; free(old); }
    } else if (*at) {
        if ((*at)->count==UINT_MAX) return -ENOBUFS;
        ++(*at)->count;
    } else {
        *at=calloc(1,sizeof(**at));
        if (!*at) return -ENOMEM;
        (*at)->request=request; (*at)->policy=link->policy_id; (*at)->count=1;
    }
    return 0;
}
static unsigned packet_type(const struct md_network_interface *link,const struct ethhdr *frame) {
    static const unsigned char broadcast[ETH_ALEN]={255,255,255,255,255,255};
    if (!memcmp(frame->h_dest,broadcast,ETH_ALEN)) return PACKET_BROADCAST;
    if (frame->h_dest[0]&1) return PACKET_MULTICAST;
    return memcmp(frame->h_dest,link->info.address,ETH_ALEN) ? PACKET_OTHERHOST : PACKET_HOST;
}
static void deliver(struct md_network_namespace *n,const struct md_network_interface *link,
        const struct md_network_ethernet *sender,const struct ethhdr *frame,size_t size,int outgoing) {
    struct md_network_packet header={.ethernet={.sll_family=AF_PACKET,.sll_ifindex=(int)link->info.index,
        .sll_protocol=frame->h_proto,.sll_hatype=link->info.type,.sll_halen=ETH_ALEN,
        .sll_pkttype=outgoing ? PACKET_OUTGOING : packet_type(link,frame)}};
    memcpy(header.ethernet.sll_addr,frame->h_source,ETH_ALEN);
    for (struct md_network_ethernet *s=n->ethernet;s;s=s->next) {
        if ((outgoing && s==sender) || !s->protocol || (s->index && (s->index!=link->info.index || s->policy!=link->policy_id))) continue;
        if (outgoing ? s->ignore_outgoing || s->protocol!=htons(ETH_P_ALL)
                : s->protocol!=htons(ETH_P_ALL) && s->protocol!=frame->h_proto) continue;
        size_t skip=s->type==SOCK_DGRAM ? sizeof(*frame) : 0;
        header.auxiliary=(struct tpacket_auxdata){.tp_status=TP_STATUS_USER,
            .tp_len=(uint32_t)(size-skip),.tp_snaplen=(uint32_t)(size-skip),.tp_net=skip ? 0 : ETH_HLEN};
        struct iovec vectors[]={{&header,sizeof(header)},{(char *)frame+skip,size-skip}};
        struct msghdr message={.msg_iov=vectors,.msg_iovlen=2};
        if (sendmsg(s->peer,&message,MSG_DONTWAIT|MSG_NOSIGNAL)<0 && errno!=ECONNREFUSED) s->overflow=1;
    }
}
struct frame_copy { struct frame_copy *next; void *frame; };
struct frame_delivery { const struct md_network_ethernet *sender; size_t size; struct frame_copy *copies; };
static int frame_visit(void *context,struct md_network_namespace *n,struct md_network_interface *link,int outgoing,const void **payload) {
    struct frame_delivery *d=context;
    if (outgoing && link->tc) {
        void *copy=NULL;
        int result=md_network_tc_egress(link->tc,*payload,d->size,&copy);
        if (result) return result;
        if (copy) {
            struct frame_copy *owned=malloc(sizeof(*owned));
            if (!owned) { free(copy); return -ENOMEM; }
            *owned=(struct frame_copy){d->copies,copy}; d->copies=owned; *payload=copy;
        }
    }
    const struct ethhdr *frame=*payload;
    deliver(n,link,d->sender,frame,d->size,outgoing);
    if (outgoing && link->kind==MD_LINK_TAP) md_network_tun_output(n,link->info.index,frame,d->size);
    if (!outgoing && md_network_stack_has_link(n,link->info.index)) {
        if(!link->master) md_network_stack_input(n,link,frame,d->size);
        return 0;
    }
    if (!outgoing && frame->h_proto==htons(ETH_P_IPV6)) md_network_raw6_receive(n,link,frame,d->size);
    if (!outgoing && frame->h_proto==htons(ETH_P_IP)) md_network_raw4_receive(n,link,frame,d->size);
    return 0;
}
static int frame_walk(struct md_network_namespace *space,struct md_network_interface *source,
        const struct md_network_ethernet *sender,const void *data,size_t size,int ingress) {
    if (size<ETH_HLEN) return -EINVAL;
    const struct ethhdr *frame=data;
    static const unsigned char reserved[5]={1,0x80,0xc2,0,0};
    int link_local=!memcmp(frame->h_dest,reserved,sizeof(reserved)) && frame->h_dest[5]<=15;
    struct frame_delivery delivery={.sender=sender,.size=size};
    int result=ingress ? md_network_link_ingress(space,source,link_local,frame_visit,&delivery,frame)
        : md_network_link_walk(space,source,link_local,frame_visit,&delivery,frame);
    while (delivery.copies) {
        struct frame_copy *copy=delivery.copies; delivery.copies=copy->next;
        free(copy->frame); free(copy);
    }
    return result;
}
int md_network_ethernet_transmit(struct md_network_namespace *space,struct md_network_interface *source,
        const struct md_network_ethernet *s,const void *data,size_t size) {
    return frame_walk(space,source,s,data,size,0);
}
int md_network_ethernet_receive(struct md_network_namespace *space,struct md_network_interface *source,
        const void *data,size_t size) {
    return frame_walk(space,source,NULL,data,size,1);
}
static long send_frame(struct md_network_ethernet *s,const void *data,size_t size,const struct md_network_send *destination) {
    if (destination && destination->control_size) return -EOPNOTSUPP;
    struct sockaddr_ll address={.sll_ifindex=(int)s->index,.sll_protocol=s->protocol};
    if (destination && destination->size) {
        if (destination->size<sizeof(address) || destination->size>sizeof(destination->value)) return -EINVAL;
        memcpy(&address,&destination->value,sizeof(address));
        if (address.sll_family!=AF_PACKET || address.sll_ifindex<0) return -EINVAL;
    }
    struct md_network_interface *link=md_network_interface_find(s->space,(unsigned)address.sll_ifindex,NULL);
    if (!link || ((!destination || !destination->size) && s->index && s->policy!=link->policy_id)) return -ENXIO;
    if (!(link->info.flags&IFF_UP)) return -ENETDOWN;
    if (size>(size_t)link->info.mtu+(s->type==SOCK_RAW ? ETH_HLEN : 0)) return -EMSGSIZE;
    if (s->type==SOCK_RAW && size<ETH_HLEN) return -EINVAL;
    void *allocation=NULL;
    const struct ethhdr *frame=data;
    size_t length=size;
    if (s->type==SOCK_DGRAM) {
        if (!destination || !destination->size || address.sll_halen!=ETH_ALEN) return -EINVAL;
        allocation=malloc(size+ETH_HLEN);
        if (!allocation) return -ENOMEM;
        struct ethhdr header={.h_proto=address.sll_protocol};
        memcpy(header.h_dest,address.sll_addr,ETH_ALEN);
        memcpy(header.h_source,link->info.address,ETH_ALEN);
        memcpy(allocation,&header,sizeof(header)); memcpy((char *)allocation+ETH_HLEN,data,size);
        frame=allocation; length+=ETH_HLEN;
    }
    int error=md_network_ethernet_transmit(s->space,link,s,frame,length);
    free(allocation); return error ? error : (long)size;
}
long md_network_ethernet_call(struct md_network_ethernet *s,long nr,const unsigned long *a,void *data,size_t capacity,size_t size,
        const struct md_network_send *destination) {
    if (nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_write) return send_frame(s,data,size,destination);
    if (nr==SYS_connect) return -EOPNOTSUPP;
    if (nr==SYS_getpeername) return -EOPNOTSUPP;
    if (nr==SYS_bind) {
        if (size<sizeof(struct sockaddr_ll)) return -EINVAL;
        const struct sockaddr_ll *address=data;
        if (address->sll_family!=AF_PACKET || address->sll_ifindex<0) return -EINVAL;
        struct md_network_interface *link=md_network_interface_find(s->space,(unsigned)address->sll_ifindex,NULL);
        if (address->sll_ifindex && !link) return -ENODEV;
        s->index=(unsigned)address->sll_ifindex; s->policy=link ? link->policy_id : 0;
        if (address->sll_protocol) s->protocol=address->sll_protocol;
        return 0;
    }
    if (nr==SYS_getsockname) {
        if (capacity<sizeof(struct sockaddr_ll)) return -EINVAL;
        struct sockaddr_ll address={.sll_family=AF_PACKET,.sll_protocol=s->protocol,.sll_ifindex=(int)s->index};
        struct md_network_interface *link=md_network_interface_find(s->space,s->index,NULL);
        if (link && link->policy_id==s->policy) {
            address.sll_hatype=link->info.type; address.sll_halen=ETH_ALEN;
            memcpy(address.sll_addr,link->info.address,ETH_ALEN);
        }
        memcpy(data,&address,sizeof(address)); return sizeof(address);
    }
    if (nr==SYS_setsockopt && a[1]==SOL_PACKET) {
        if (a[2]==PACKET_AUXDATA) {
            if (size!=sizeof(int)) return -EINVAL;
            int value; memcpy(&value,data,sizeof(value));
            s->auxiliary=!!value; return 0;
        }
        if (a[2]==PACKET_ADD_MEMBERSHIP || a[2]==PACKET_DROP_MEMBERSHIP) return membership(s,(int)a[2],data,size);
        if (a[2]==PACKET_IGNORE_OUTGOING) {
            if (size!=sizeof(int)) return -EINVAL;
            int value; memcpy(&value,data,sizeof(value));
            if (value!=0 && value!=1) return -EINVAL;
            s->ignore_outgoing=value; return 0;
        }
    }
    if (nr==SYS_getsockopt && a[1]==SOL_PACKET && a[2]==PACKET_IGNORE_OUTGOING) {
        if (capacity<sizeof(int)) return -EINVAL;
        memcpy(data,&s->ignore_outgoing,sizeof(int)); return sizeof(int);
    }
    if (nr==SYS_getsockopt && a[1]==SOL_PACKET && a[2]==PACKET_AUXDATA) {
        if (capacity<sizeof(int)) return -EINVAL;
        memcpy(data,&s->auxiliary,sizeof(int)); return sizeof(int);
    }
    return -EOPNOTSUPP;
}
