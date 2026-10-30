#define _GNU_SOURCE
#include "network_stack_internal.h"
#include "network_endpoint.h"
#include "lwip/ip.h"
#include <errno.h>
#include <net/if.h>
#include <stdlib.h>
#include <sys/epoll.h>
#include <unistd.h>

struct received { struct received *next; struct pbuf *packet; size_t offset; };
struct md_stack_listener {
    struct md_stack_listener *next;
    struct tcp_pcb *pcb;
    unsigned index;
    uint64_t policy;
};
struct md_stack_channel {
    struct md_stack_channel *next;
    struct md_network_stack *owner;
    struct md_network_event_source *source;
    struct tcp_pcb *tcp;
    struct udp_pcb *udp;
    struct received *head, **tail;
    unsigned index;
    uint64_t policy;
    size_t queued, packets;
    int fd, result, write_closed, read_closed, blocked;
};
static void interests(struct md_stack_channel *c) {
    if(!c->source) return;
    uint32_t events=0;
    if(!c->result) {
        if(!c->write_closed && !c->blocked && (c->udp || (c->tcp && tcp_sndbuf(c->tcp)))) events|=EPOLLIN;
        if(c->head) events|=EPOLLOUT;
    }
    md_network_event_update(c->source,events);
}
static void fail(struct md_stack_channel *c,int error) {
    c->result=error;
    md_network_event_remove(c->source); c->source=NULL;
    shutdown(c->fd,SHUT_RDWR);
    md_network_forward_wake(c->owner->space->fabric);
}
static void tcp_error(void *context,err_t error) {
    struct md_stack_channel *c=context; c->tcp=NULL;
    fail(c,error==ERR_RST && c->result==-EINPROGRESS ? -ECONNREFUSED : md_stack_error(error));
}
static void flush(struct md_stack_channel *c) {
    unsigned char data[65536];
    for(unsigned budget=0;c->head && !c->result && budget<16;++budget) {
        struct received *q=c->head;
        size_t size=q->packet->tot_len-q->offset;
        pbuf_copy_partial(q->packet,data,(u16_t)size,(u16_t)q->offset);
        ssize_t sent=send(c->fd,data,size,MSG_DONTWAIT|MSG_NOSIGNAL);
        if(sent<0) {
            if(errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR) fail(c,-errno);
            break;
        }
        if(c->tcp && sent) tcp_recved(c->tcp,(u16_t)sent);
        q->offset+=(size_t)sent;
        if(q->offset<q->packet->tot_len) break;
        c->queued-=q->packet->tot_len; --c->packets;
        c->head=q->next; if(!c->head) c->tail=&c->head;
        pbuf_free(q->packet); free(q);
    }
    if(!c->head && c->read_closed) shutdown(c->fd,SHUT_WR);
    interests(c);
}
static int received(struct md_stack_channel *c,struct pbuf *p) {
    if(c->packets>=1024 || p->tot_len>262144-c->queued) return -ENOBUFS;
    struct received *q=malloc(sizeof(*q)); if(!q) return -ENOMEM;
    *q=(struct received){.packet=p}; *c->tail=q; c->tail=&q->next;
    c->queued+=p->tot_len; ++c->packets; flush(c); return 0;
}
static err_t tcp_received(void *context,struct tcp_pcb *pcb,struct pbuf *p,err_t error) {
    (void)pcb; (void)error; struct md_stack_channel *c=context;
    if(!p) { c->read_closed=1; flush(c); return ERR_OK; }
    return received(c,p) ? ERR_MEM : ERR_OK;
}
static err_t sent_callback(void *context,struct tcp_pcb *pcb,u16_t bytes) {
    (void)pcb; (void)bytes; struct md_stack_channel *c=context;
    c->blocked=0; interests(c); return ERR_OK;
}
static err_t retry_callback(void *context,struct tcp_pcb *pcb) { return sent_callback(context,pcb,0); }
static err_t tcp_connected(void *context,struct tcp_pcb *pcb,err_t error) {
    (void)pcb; struct md_stack_channel *c=context;
    c->result=md_stack_error(error); interests(c);
    md_network_forward_wake(c->owner->space->fabric); return ERR_OK;
}
static void udp_received(void *context,struct udp_pcb *pcb,struct pbuf *p,const ip_addr_t *a,u16_t port) {
    (void)pcb; (void)a; (void)port;
    if(received(context,p)) pbuf_free(p);
}
static void ready(void *context,uint32_t events) {
    struct md_stack_channel *c=context;
    struct md_lwip_context *previous=md_lwip_enter(c->owner->context);
    if(events&EPOLLOUT) flush(c);
    unsigned char data[65536];
    for(unsigned budget=0;!c->result && !c->write_closed && budget<16;++budget) {
        size_t capacity=c->udp ? 65535 : c->tcp ? tcp_sndbuf(c->tcp) : 0;
        if(!capacity) break;
        if(capacity>sizeof(data)) capacity=sizeof(data);
        if(capacity>65535) capacity=65535;
        ssize_t bytes=recv(c->fd,data,capacity,MSG_DONTWAIT|MSG_PEEK);
        if(bytes<0) {
            if(errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR) fail(c,-errno);
            break;
        }
        err_t error;
        if(c->udp) {
            struct pbuf *p=pbuf_alloc(PBUF_TRANSPORT,(u16_t)bytes,PBUF_RAM);
            error=ERR_MEM;
            if(p) { pbuf_take(p,data,(u16_t)bytes); error=udp_send(c->udp,p); pbuf_free(p); }
            /* UDP has no retransmission queue. Drop on resource pressure,
             * rather than repeatedly waking on the same native datagram. */
            if(error==ERR_MEM || error==ERR_BUF) error=ERR_OK;
        } else if(!bytes) {
            error=tcp_shutdown(c->tcp,0,1);
            if(!error) c->write_closed=1;
            if(error==ERR_MEM) c->blocked=1;
            else if(error) fail(c,md_stack_error(error));
            break;
        } else error=tcp_write(c->tcp,data,(u16_t)bytes,TCP_WRITE_FLAG_COPY);
        if(error==ERR_MEM || error==ERR_BUF) { if(c->tcp) c->blocked=1; break; }
        if(error) { fail(c,md_stack_error(error)); break; }
        ssize_t consumed=recv(c->fd,data,(size_t)bytes,MSG_DONTWAIT);
        if(consumed!=bytes) { fail(c,-EIO); break; }
    }
    if(c->tcp && !c->result) tcp_output(c->tcp);
    interests(c); md_stack_arm(c->owner); md_lwip_leave(previous);
}
int md_network_stack_result(struct md_stack_channel *c) { return c ? c->result : 0; }
static struct md_stack_channel *new_channel(struct md_stack_interface *i,int type,int *peer) {
    struct md_stack_channel *c=calloc(1,sizeof(*c)); if(!c) { *peer=-ENOMEM; return NULL; }
    c->owner=i->owner; c->index=i->index; c->policy=i->policy; c->fd=-1; c->tail=&c->head;
    int fds[2];
    if(socketpair(AF_UNIX,type|SOCK_CLOEXEC|SOCK_NONBLOCK,0,fds)) { *peer=-errno; free(c); return NULL; }
    c->fd=fds[0];
    int error=md_network_event_add(i->owner->space->fabric,c->fd,0,ready,c,&c->source);
    if(error) { close(fds[0]); close(fds[1]); free(c); *peer=error; return NULL; }
    c->next=i->owner->channels; i->owner->channels=c;
    *peer=fds[1]; return c;
}
static void attach_tcp(struct md_stack_channel *c,struct tcp_pcb *pcb) {
    c->tcp=pcb;
    tcp_arg(pcb,c); tcp_err(pcb,tcp_error); tcp_recv(pcb,tcp_received); tcp_sent(pcb,sent_callback);
    tcp_poll(pcb,retry_callback,2);
}
static int attach_udp(struct md_stack_channel *c,const ip_addr_t *local,u16_t port,
        const ip_addr_t *remote,u16_t remote_port) {
    c->udp=udp_new_ip_type(IP_GET_TYPE(local)); if(!c->udp) return -ENOMEM;
    struct md_stack_interface *i=md_stack_interface(c->owner,c->index);
    udp_bind_netif(c->udp,&i->netif); udp_recv(c->udp,udp_received,c);
    ip_set_option(c->udp,SOF_REUSEADDR);
    int error=md_stack_error(udp_bind(c->udp,local,port));
    if(!error) error=md_stack_error(udp_connect(c->udp,remote,remote_port));
    return error;
}
int md_network_stack_connect(struct md_network_fabric *fabric,const struct md_network_flow_path *path,
        int type,struct md_stack_channel **out) {
    *out=NULL;
    struct md_network_namespace *n=md_network_space_find(fabric,path->packet_space);
    struct md_stack_interface *i=n && n->stack ? md_stack_interface(n->stack,path->packet_output) : NULL;
    if(!i || !(i->netif.flags&NETIF_FLAG_UP)) return -ENETUNREACH;
    int peer;
    struct md_stack_channel *c=new_channel(i,type,&peer); if(!c) return peer;
    int error=0;
    struct md_lwip_context *previous=md_lwip_enter(n->stack->context);
    ip_addr_t local=md_stack_ip(path->translated.source),remote=md_stack_ip(path->translated.destination);
    if(IP_IS_V6(&local)) ip6_addr_assign_zone(ip_2_ip6(&local),IP6_UNICAST,&i->netif);
    if(IP_IS_V6(&remote)) ip6_addr_assign_zone(ip_2_ip6(&remote),IP6_UNICAST,&i->netif);
    if(!error && type==SOCK_STREAM) {
        c->tcp=tcp_new_ip_type(IP_GET_TYPE(&local));
        if(!c->tcp) error=-ENOMEM;
        else {
            attach_tcp(c,c->tcp);
            tcp_bind_netif(c->tcp,&i->netif);
            error=md_stack_error(tcp_bind(c->tcp,&local,ntohs(path->translated.source_port)));
            c->result=-EINPROGRESS;
            if(!error) error=md_stack_error(tcp_connect(c->tcp,&remote,ntohs(path->translated.destination_port),tcp_connected));
        }
    } else if(!error) {
        error=attach_udp(c,&local,ntohs(path->translated.source_port),&remote,ntohs(path->translated.destination_port));
    }
    if(!error) { interests(c); md_stack_arm(n->stack); }
    md_lwip_leave(previous);
    if(error) { close(peer); md_network_stack_close(c); return error; }
    *out=c; return peer;
}
static struct md_ip_address address(const ip_addr_t *a) {
    return IP_IS_V6(a) ? md_ip_load(AF_INET6,ip_2_ip6(a)->addr) : md_ip_v4(ip4_addr_get_u32(ip_2_ip4(a)));
}
static err_t accept_packet(void *context,struct tcp_pcb *pcb,err_t status) {
    struct md_network_stack *s=context;
    struct md_stack_interface *i=ip_current_input_netif()->state;
    if(status || !i || i->owner!=s) { tcp_abort(pcb); return ERR_ABRT; }
    int peer;
    struct md_stack_channel *c=new_channel(i,SOCK_STREAM,&peer);
    if(!c) { tcp_abort(pcb); return ERR_ABRT; }
    attach_tcp(c,pcb);
    struct md_filter_packet p={.source=address(&pcb->remote_ip),.destination=address(&pcb->local_ip),
        .source_port=htons(pcb->remote_port),.destination_port=htons(pcb->local_port),.protocol=IPPROTO_TCP,.state=8};
    int error=md_network_forward_packet(s->space,i->index,SOCK_STREAM,&p,peer,c);
    if(error) { close(peer); md_network_stack_close(c); return ERR_ABRT; }
    interests(c); return ERR_OK;
}
int md_stack_channel_input(struct md_network_stack *s,struct md_stack_interface *i,struct pbuf *buffer,unsigned protocol) {
    const ip_addr_t *source=ip_current_src_addr();
    unsigned header=ip_current_header_tot_len();
    unsigned minimum=protocol==IPPROTO_TCP ? 20 : 8;
    if(buffer->tot_len<header+minimum) return 0;
    unsigned char transport[20]; pbuf_copy_partial(buffer,transport,(u16_t)minimum,(u16_t)header);
    struct md_filter_packet p={.source=address(source),.destination=address(ip_current_dest_addr()),
        .protocol=protocol,.state=8};
    memcpy(&p.source_port,transport,2); memcpy(&p.destination_port,transport+2,2);
    if(protocol==IPPROTO_TCP && (transport[13]&0x12)!=0x02) return 0;
    ip_addr_t local=*ip_current_dest_addr(),remote=*source;
    u16_t port=ntohs(p.destination_port),remote_port=ntohs(p.source_port);
    if(protocol==IPPROTO_UDP) {
        for(struct md_stack_channel *c=s->channels;c;c=c->next)
            if(c->udp && c->index==i->index && c->udp->local_port==port && c->udp->remote_port==remote_port
                    && ip_addr_cmp(&c->udp->local_ip,&local) && ip_addr_cmp(&c->udp->remote_ip,&remote)) return 0;
    }
    struct md_network_flow_path path;
    struct md_filter_packet translated=p;
    int type=protocol==IPPROTO_TCP ? SOCK_STREAM : SOCK_DGRAM;
    int error=md_network_flow_route(s->space,&translated,1,i->index,&path);
    struct md_inet_address target;
    if(!error) error=md_network_endpoint_resolve(s->space->fabric,&path,type,&target,NULL);
    if(error) {
        if(error==-EACCES) { pbuf_free(buffer); return 1; }
        return 0;
    }
    if(type==SOCK_DGRAM) {
        int peer;
        struct md_stack_channel *c=new_channel(i,type,&peer); if(!c) return 0;
        error=attach_udp(c,&local,port,&remote,remote_port);
        if(!error) error=md_network_forward_packet(s->space,i->index,type,&p,peer,c);
        if(error) { close(peer); md_network_stack_close(c); }
        else interests(c);
    } else {
        unsigned count=0;
        struct md_stack_listener **oldest=&s->listeners;
        for(struct md_stack_listener **at=&s->listeners;*at;at=&(*at)->next) {
            struct md_stack_listener *l=*at;
            if(l->index==i->index && l->pcb->local_port==port && ip_addr_cmp(&l->pcb->local_ip,&local)) {
                *at=l->next; l->next=s->listeners; s->listeners=l;
                return 0;
            }
            oldest=at;
            ++count;
        }
        /* Only the passive admission cache is bounded. Established channels
         * retain their own PCBs; closing a listener never closes them. */
        if(count>=512) {
            struct md_stack_listener *l=*oldest;
            *oldest=NULL; tcp_close(l->pcb); free(l);
        }
        struct md_stack_listener *l=calloc(1,sizeof(*l)); if(!l) return 0;
        struct tcp_pcb *pcb=tcp_new_ip_type(IP_GET_TYPE(&local));
        if(!pcb) { free(l); return 0; }
        ip_set_option(pcb,SOF_REUSEADDR); tcp_bind_netif(pcb,&i->netif);
        err_t result=tcp_bind(pcb,&local,port);
        struct tcp_pcb *listener=result ? NULL : tcp_listen_with_backlog_and_err(pcb,32,&result);
        if(!listener) { tcp_abort(pcb); free(l); return 0; }
        tcp_arg(listener,s); tcp_accept(listener,accept_packet);
        *l=(struct md_stack_listener){.next=s->listeners,.pcb=listener,.index=i->index,.policy=i->policy};
        s->listeners=l;
    }
    return 0;
}
void md_network_stack_close(struct md_stack_channel *c) {
    if(!c) return;
    struct md_network_stack *s=c->owner;
    struct md_lwip_context *previous=md_lwip_enter(s->context);
    struct md_stack_channel **at=&s->channels;
    while(*at && *at!=c) at=&(*at)->next;
    if(*at) *at=c->next;
    md_network_event_remove(c->source);
    if(c->tcp) {
        tcp_arg(c->tcp,NULL); tcp_err(c->tcp,NULL); tcp_recv(c->tcp,NULL); tcp_sent(c->tcp,NULL); tcp_poll(c->tcp,NULL,0);
        if(!c->read_closed || !c->write_closed || tcp_close(c->tcp)!=ERR_OK) tcp_abort(c->tcp);
    }
    if(c->udp) udp_remove(c->udp);
    while(c->head) { struct received *q=c->head; c->head=q->next; pbuf_free(q->packet); free(q); }
    if(c->fd>=0) close(c->fd);
    md_stack_arm(s); md_lwip_leave(previous); free(c);
}
void md_stack_channels_retire(struct md_network_stack *s) {
    while(s->channels) md_network_stack_close(s->channels);
    while(s->listeners) { struct md_stack_listener *l=s->listeners; s->listeners=l->next; free(l); }
}
void md_stack_channels_changed(struct md_network_stack *s) {
    for(struct md_stack_channel *c=s->channels;c;c=c->next) {
        struct md_stack_interface *i=md_stack_interface(s,c->index);
        if(!i || i->policy!=c->policy) fail(c,-ENETUNREACH);
    }
    struct md_stack_listener **at=&s->listeners;
    while(*at) {
        struct md_stack_listener *l=*at;
        struct md_stack_interface *i=md_stack_interface(s,l->index);
        if(!i || i->policy!=l->policy) { *at=l->next; tcp_close(l->pcb); free(l); }
        else at=&l->next;
    }
}
