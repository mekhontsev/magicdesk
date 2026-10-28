#define _GNU_SOURCE
#include "network_forward.h"
#include "network_endpoint.h"
#include "network_route.h"
#include "network_raw4.h"
#include "network_raw6.h"
#include "network_link.h"
#include <limits.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/netfilter.h>
#include <linux/rtnetlink.h>
#include <linux/if_packet.h>
#include <netinet/in.h>
#include <net/if.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <time.h>
#include <unistd.h>

struct listener {
    struct listener *next;
    uint64_t id, space;
    const void *endpoint;
    int fd, reserved, type, active, external, published, listening, endpoint_listening, v6only;
    int upstream_fd, upstream_connecting;
    struct md_inet_address address, client, target;
    struct md_network_flow_path path;
    int64_t deadline;
    unsigned group_output, group_started;
    uint64_t group_policy;
    int group_hops;
};
struct stream_buffer { size_t begin, end; unsigned char data[65536]; };
struct datagram { struct datagram *next; size_t size; unsigned char data[]; };
struct datagram_queue { struct datagram *head, *tail; size_t bytes, count; };
struct flow {
    struct flow *next;
    uint64_t id, listener, space, checked[2];
    const void *endpoint, *receiver;
    int fd[2], borrowed, type, connecting, eof[2], denied[2], receiver_closed;
    struct md_inet_address client, remote, gateway;
    struct md_network_flow_path path;
    struct stream_buffer *buffer;
    struct datagram_queue datagrams[2];
    struct md_filter_accounting accounting[2];
    unsigned started;
    unsigned front_family;
    unsigned packet_input;
    uint64_t packet_policy, packet_destination;
    int group, group_hops;
    int64_t deadline;
};
struct md_network_forward {
    struct md_network_fabric *fabric;
    pthread_t thread;
    int epoll, wake, stopped;
    uint64_t next_id;
    size_t flows, listeners;
    uint64_t dropped_datagrams;
    struct listener *ports;
    struct flow *connections;
};
struct md_network_forward_update {
    struct md_network_forward *owner;
    uint64_t space;
    struct listener *ports[512];
    unsigned char created[512];
    size_t count;
};
struct md_network_port_reservation { struct md_network_forward *owner; struct listener *listener; int created; };
static int64_t now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (int64_t)ts.tv_sec*1000+ts.tv_nsec/1000000;
}
static int same(const struct md_inet_address *a,const struct md_inet_address *b) {
    return md_inet_equal(a,b);
}
static void wake(struct md_network_forward *f) {
    uint64_t one=1;
    if (f) { ssize_t result=write(f->wake,&one,sizeof(one)); (void)result; }
}
static struct listener *find_listener(struct md_network_forward *f,uint64_t id) {
    for (struct listener *l=f->ports;l;l=l->next) if (l->id==id) return l;
    return NULL;
}
static struct flow *find_flow(struct md_network_forward *f,uint64_t id) {
    for (struct flow *c=f->connections;c;c=c->next) if (c->id==id) return c;
    return NULL;
}
static int watch(struct md_network_forward *f,int command,int fd,uint64_t id,unsigned leg,uint32_t mask) {
    struct epoll_event e={.events=mask,.data.u64=id*4+leg};
    return epoll_ctl(f->epoll,command,fd,&e) ? -errno : 0;
}
static void listener_interests(struct md_network_forward *,uint64_t);
static void drop_flow(struct md_network_forward *f,struct flow *c) {
    struct flow **at=&f->connections;
    while (*at && *at!=c) at=&(*at)->next;
    if (*at) { *at=c->next; --f->flows; }
    if (!c->borrowed && c->fd[0]>=0) { epoll_ctl(f->epoll,EPOLL_CTL_DEL,c->fd[0],NULL); close(c->fd[0]); }
    if (c->fd[1]>=0) { epoll_ctl(f->epoll,EPOLL_CTL_DEL,c->fd[1],NULL); close(c->fd[1]); }
    for (unsigned i=0;i<2;++i) while (c->datagrams[i].head) {
        struct datagram *d=c->datagrams[i].head;
        c->datagrams[i].head=d->next; free(d);
    }
    if (c->borrowed) listener_interests(f,c->listener);
    free(c->accounting[0].cells); free(c->accounting[1].cells);
    free(c->buffer); free(c);
}
static void drop_listener(struct md_network_forward *f,struct listener *l) {
    struct flow *c=f->connections;
    while (c) { struct flow *next=c->next; if (c->listener==l->id) drop_flow(f,c); c=next; }
    struct listener **at=&f->ports;
    while (*at && *at!=l) at=&(*at)->next;
    if (*at) { *at=l->next; --f->listeners; }
    if (l->fd>=0) { epoll_ctl(f->epoll,EPOLL_CTL_DEL,l->fd,NULL); close(l->fd); }
    if (l->reserved>=0) close(l->reserved);
    if (l->upstream_fd>=0) close(l->upstream_fd);
    free(l);
}
static int socket_bound(int type,struct md_inet_address *address,int v6only) {
    int fd=socket(address->ip.family,type|SOCK_CLOEXEC|SOCK_NONBLOCK,0);
    if (fd<0) return -errno;
    if (type==SOCK_STREAM) { int one=1; setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one)); }
    if (address->ip.family==AF_INET6 && setsockopt(fd,IPPROTO_IPV6,IPV6_V6ONLY,&v6only,sizeof(v6only))) {
        int error=-errno; close(fd); return error;
    }
    if (md_inet_bind(fd,address,0)) { int error=-errno; close(fd); return error; }
    if (md_inet_name(fd,0,address)) { int error=-errno; close(fd); return error; }
    return fd;
}
static int connect_target(const void *endpoint,int type,struct md_network_transport_policy policy,
        const struct md_inet_address *target,struct md_inet_address *local,int *connecting) {
    *local=(struct md_inet_address){.ip=md_ip_any(target->ip.family)};
    *connecting=0;
    int fd=socket_bound(type,local,1);
    if (fd<0) return fd;
    int error=0;
    if (target->ip.family==AF_INET6) {
        struct md_network_transport transport;
        md_network_endpoint_transport(endpoint,&transport);
        error=md_network_transport_apply(&transport,fd,type,policy);
    }
    if (!error && md_inet_connect(fd,target,0)) {
        if (errno==EINPROGRESS) *connecting=1;
        else error=-errno;
    }
    if (error) { close(fd); return error; }
    return fd;
}
static int check(struct md_network_forward *f,struct flow *c,unsigned direction) {
    if (c->checked[direction]!=f->fabric->policy_generation) {
        unsigned mark=c->path.translated.ctmark;
        c->denied[direction]=md_network_flow_accounting(f->fabric,&c->path,direction,!c->started,&c->accounting[direction]);
        if (mark!=c->path.translated.ctmark) c->checked[1-direction]=0;
        c->checked[direction]=f->fabric->policy_generation;
    }
    return c->denied[direction];
}
static void account(struct flow *c,unsigned direction,size_t bytes) {
    md_filter_account(&c->accounting[direction],bytes);
    if (!direction && !c->started) { c->started=1; c->checked[0]=0; }
}
static void reset_stream(int fd) {
    struct linger reset={.l_onoff=1,.l_linger=0};
    setsockopt(fd,SOL_SOCKET,SO_LINGER,&reset,sizeof(reset));
}
static int interests(struct md_network_forward *f,struct flow *c) {
    if (c->type==SOCK_STREAM && (check(f,c,0)==-ECONNREFUSED || check(f,c,1)==-ECONNREFUSED)) {
        for (unsigned leg=0;leg<2;++leg) if (c->fd[leg]>=0) reset_stream(c->fd[leg]);
        return -ECONNREFUSED;
    }
    for (unsigned leg=0;leg<2;++leg) {
        if ((!leg && c->borrowed) || c->fd[leg]<0) continue;
        uint32_t events=0;
        if (c->connecting) { if (leg) events|=EPOLLOUT; }
        else if (c->type==SOCK_DGRAM) {
            if (c->datagrams[leg].count<64 && c->datagrams[leg].bytes<262144) events|=EPOLLIN;
            if (c->datagrams[1-leg].head) events|=EPOLLOUT;
        }
        else {
            if (!c->eof[leg] && !c->buffer[leg].end && !check(f,c,leg)) events|=EPOLLIN|EPOLLRDHUP;
            if (c->buffer[1-leg].end && !check(f,c,1-leg)) events|=EPOLLOUT;
        }
        int error=watch(f,EPOLL_CTL_MOD,c->fd[leg],c->id,leg,events);
        if (error) return error;
    }
    return 0;
}
static struct flow *new_flow(struct md_network_forward *f,struct listener *l,int front,
        const struct md_inet_address *client,struct md_network_flow_path *path,const struct md_inet_address *target,const void *receiver) {
    if (f->flows==1024 || f->next_id>UINT64_MAX/4) { errno=ENOSPC; return NULL; }
    struct flow *c=calloc(1,sizeof(*c));
    if (!c) return NULL;
    c->fd[0]=front; c->fd[1]=-1; c->type=l->type; c->borrowed=l->external && l->type==SOCK_DGRAM;
    c->listener=l->external ? l->id : 0; c->space=l->space; c->endpoint=l->external ? NULL : l->endpoint;
    c->client=*client; c->path=*path; c->gateway=l->address;
    c->front_family=l->address.ip.family;
    c->receiver=receiver;
    if (!receiver && c->type==SOCK_DGRAM && path->destination) {
        struct md_inet_address ignored;
        int error=md_network_endpoint_resolve(f->fabric,path,c->type,&ignored,&c->receiver);
        if (error) { free(c); errno=-error; return NULL; }
    }
    c->id=++f->next_id;
    if (c->type==SOCK_STREAM && !l->external && l->upstream_fd>=0) {
        c->fd[1]=l->upstream_fd; l->upstream_fd=-1;
        c->connecting=l->upstream_connecting;
        if (md_inet_name(c->fd[1],0,&c->remote)) goto failed;
    } else {
        c->fd[1]=connect_target(c->endpoint,c->type,path->transport,target,&c->remote,&c->connecting);
        if (c->fd[1]<0) { errno=-c->fd[1]; goto failed; }
    }
    if (c->type==SOCK_STREAM) {
        c->buffer=calloc(2,sizeof(*c->buffer)); if (!c->buffer) goto failed;
    }
    c->deadline=c->type==SOCK_DGRAM || c->connecting ? now_ms()+30000 : INT64_MAX;
    for (unsigned leg=0;leg<2;++leg) if (c->fd[leg]>=0 && (leg || !c->borrowed))
        if (watch(f,EPOLL_CTL_ADD,c->fd[leg],c->id,leg,0)) goto failed;
    c->next=f->connections; f->connections=c; ++f->flows;
    int error=interests(f,c);
    if (error) {
        if (!c->borrowed) epoll_ctl(f->epoll,EPOLL_CTL_DEL,front,NULL);
        c->fd[0]=-1; drop_flow(f,c); errno=-error; return NULL;
    }
    if (c->type==SOCK_STREAM && !c->connecting) md_network_endpoint_connect_complete(c->endpoint,0);
    return c;
failed:
    if (c->fd[1]>=0) { epoll_ctl(f->epoll,EPOLL_CTL_DEL,c->fd[1],NULL); close(c->fd[1]); }
    if (!c->borrowed && c->fd[0]>=0) epoll_ctl(f->epoll,EPOLL_CTL_DEL,c->fd[0],NULL);
    free(c->buffer); free(c); return NULL;
}
static int incoming_path(struct md_network_forward *f,struct listener *l,const struct md_inet_address *client,
        const struct md_inet_address *local,struct md_network_flow_path *path,struct md_inet_address *target) {
    if (!l->external) { *path=l->path; *target=l->target; return 0; }
    struct md_network_namespace *n=md_network_space_find(f->fabric,l->space);
    if (!n) return -ENETUNREACH;
    struct md_filter_packet p={.source=client->ip,.source_port=client->port,
        .destination=local->ip,.destination_port=local->port,.state=8,
        .protocol=l->type==SOCK_STREAM ? IPPROTO_TCP : IPPROTO_UDP};
    if (!md_ip_unspecified(l->address.ip) && !md_ip_is_loopback(l->address.ip))
        p.destination=l->address.ip;
    /* Android loopback clients originate on this host, just like guest
     * loopback clients. Feeding them into PREROUTING can bypass a local
     * listener through DNAT and leak a loopback source into a private link. */
    int host_local=md_ip_is_loopback(client->ip) && md_ip_is_loopback(local->ip);
    int error=md_network_flow_route(n,&p,!host_local,0,path);
    if (!error && !p.dnat && !l->endpoint) error=-ECONNREFUSED;
    if (!error) error=md_network_endpoint_resolve(f->fabric,path,l->type,target,NULL);
    return error;
}
static void tcp_accept(struct md_network_forward *f,struct listener *l) {
    for (unsigned budget=0;budget<16;++budget) {
        struct md_inet_address client;
        int fd=md_inet_accept(l->fd,&client,SOCK_NONBLOCK|SOCK_CLOEXEC);
        if (fd<0) return;
        if (!l->external && !same(&client,&l->client)) { close(fd); continue; }
        struct md_inet_address local;
        struct md_network_flow_path path;
        struct md_inet_address target;
        int error=md_inet_name(fd,0,&local);
        if (!error) error=incoming_path(f,l,&client,&local,&path,&target);
        if (error) {
            if (!l->external) md_network_endpoint_connect_complete(l->endpoint,error);
            if (error==-ECONNREFUSED) reset_stream(fd);
            close(fd); continue;
        }
        struct flow *c=new_flow(f,l,fd,&client,&path,&target,NULL);
        if (!c) {
            if (!l->external) md_network_endpoint_connect_complete(l->endpoint,-errno);
            close(fd);
        }
        if (!l->external) { drop_listener(f,l); return; }
    }
}
static int packet_reply(struct md_network_forward *f,struct flow *c,const void *data,size_t size) {
    struct md_network_namespace *n=md_network_space_find(f->fabric,c->space);
    struct md_network_interface *input=n ? md_network_interface_find(n,c->packet_input,NULL) : NULL;
    if (!input || input->policy_id!=c->packet_policy) return -ENETUNREACH;
    /* check()/account() already walked the retained path in reverse, including
     * live filters and conntrack's inverse NAT. A fresh route here would apply
     * NEW policy again and lose the original source address/port. */
    struct md_network_interface *link=md_network_local_interface(n,c->path.original.source,1);
    struct md_network_namespace *receiver=n;
    if (!link) {
        link=md_network_neighbor(n,input,c->path.original.source);
        receiver=link ? md_network_interface_owner(f->fabric,link->info.index) : NULL;
    }
    if (!receiver) return -ENETUNREACH;
    c->packet_destination=receiver->inode;
    struct md_inet_address source={.ip=c->path.original.destination,.port=c->path.original.destination_port};
    struct md_inet_address destination={.ip=c->path.original.source,.port=c->path.original.source_port};
    md_network_raw4_reply(receiver,link,&source,&destination,data,size);
    md_network_raw6_reply(receiver,link,&source,&destination,data,size);
    struct md_network_flow_path path={.destination=receiver->inode,
        .translated={.source=source.ip,.source_port=source.port,.destination=destination.ip,
            .destination_port=destination.port,.protocol=IPPROTO_UDP}};
    struct md_inet_address target;
    const void *endpoint=NULL;
    int error=md_network_endpoint_resolve(f->fabric,&path,SOCK_DGRAM,&target,&endpoint);
    if (error==-ECONNREFUSED) return 0;
    if (error) return error;
    if (!endpoint) return 0;
    ssize_t sent=md_inet_sendto(c->fd[0],data,size,MSG_DONTWAIT|MSG_NOSIGNAL,&target,0);
    return sent<0 ? -errno : (size_t)sent==size ? 0 : -EIO;
}
static int datagram_send(struct md_network_forward *f,struct flow *c,unsigned direction,const void *data,size_t size) {
    /* A retained tuple describes queued packets, not authority to deliver to a
     * new socket which happens to reuse the closed peer's native port. */
    if ((!direction && c->receiver_closed)
            || (direction && !c->borrowed && !c->endpoint && !c->packet_input)) return -ECONNREFUSED;
    if (c->group) {
        if (direction) return -EOPNOTSUPP;
        struct md_network_namespace *n=md_network_space_find(f->fabric,c->path.destination);
        struct md_network_interface *link=n ? md_network_interface_find(n,c->path.translated.input,NULL) : NULL;
        if (!link || link->policy_id!=c->packet_policy) return -ENETUNREACH;
        struct md_inet_address ignored;
        const void *receiver=NULL;
        int joined=md_network_multicast_subscribed(n,link,c->path.translated.destination);
        while ((receiver=md_network_endpoint_group_next(n,link,&c->path.translated,joined,receiver,&ignored)) && receiver!=c->receiver) {}
        if (!receiver) return -ECONNREFUSED;
    }
    if (direction && c->packet_input) return packet_reply(f,c,data,size);
    ssize_t sent=direction ? md_inet_sendto(c->fd[0],data,size,MSG_DONTWAIT|MSG_NOSIGNAL,&c->client,c->front_family)
        : send(c->fd[1],data,size,MSG_DONTWAIT|MSG_NOSIGNAL);
    return sent<0 ? -errno : (size_t)sent==size ? 0 : -EIO;
}
static void listener_interests(struct md_network_forward *f,uint64_t id) {
    struct listener *l=find_listener(f,id);
    if (!l) return;
    uint32_t events=EPOLLIN;
    for (struct flow *c=f->connections;c;c=c->next)
        if (c->borrowed && c->listener==id && c->datagrams[1].head) { events|=EPOLLOUT; break; }
    watch(f,EPOLL_CTL_MOD,l->fd,l->id,2,events);
}
static int datagram_submit(struct md_network_forward *f,struct flow *c,unsigned direction,const void *data,size_t size) {
    int denied=c->group ? 0 : check(f,c,direction);
    if (!c->group) account(c,direction,size);
    if (denied) return -EACCES;
    struct datagram_queue *q=&c->datagrams[direction];
    if (!q->head) {
        int error=datagram_send(f,c,direction,data,size);
        if (!error) { c->deadline=now_ms()+30000; return 0; }
        if (error!=-EAGAIN && error!=-EWOULDBLOCK && error!=-EINTR) { ++f->dropped_datagrams; return 0; }
    }
    struct datagram *d=q->count<64 && size<=262144-q->bytes ? malloc(sizeof(*d)+size) : NULL;
    if (!d) { ++f->dropped_datagrams; return 0; }
    d->next=NULL; d->size=size; memcpy(d->data,data,size);
    if (q->tail) q->tail->next=d; else q->head=d;
    q->tail=d; q->bytes+=size; ++q->count;
    c->deadline=now_ms()+30000;
    interests(f,c);
    if (c->borrowed && direction) listener_interests(f,c->listener);
    return 0;
}
static void datagram_flush(struct md_network_forward *f,struct flow *c,unsigned direction) {
    struct datagram_queue *q=&c->datagrams[direction];
    for (unsigned budget=0;q->head && budget<16;++budget) {
        struct datagram *d=q->head;
        int error=check(f,c,direction) ? -EACCES : datagram_send(f,c,direction,d->data,d->size);
        if (error==-EAGAIN || error==-EWOULDBLOCK || error==-EINTR) break;
        if (error && error!=-EACCES) ++f->dropped_datagrams;
        q->head=d->next; q->bytes-=d->size; --q->count; free(d);
        if (!q->head) q->tail=NULL;
    }
    interests(f,c);
    if (c->borrowed && direction) listener_interests(f,c->listener);
}
static void udp_receive(struct md_network_forward *f,struct flow *c,unsigned leg) {
    unsigned char data[65536];
    for (unsigned budget=0;budget<16;++budget) {
        struct md_inet_address source;
        ssize_t bytes=md_inet_recvfrom(c->fd[leg],data,sizeof(data),MSG_DONTWAIT,&source);
        if (bytes<0) return;
        if (c->group) continue;
        if (!leg && !same(&source,&c->client)) continue;
        datagram_submit(f,c,leg,data,(size_t)bytes);
        if (c->datagrams[leg].count>=64 || c->datagrams[leg].bytes>=262144) break;
    }
}
struct group_delivery {
    struct md_network_forward *owner;
    struct listener *sender;
    struct md_network_namespace *space;
    struct md_network_interface *output;
    struct md_network_flow_path path;
    const void *data;
    size_t size;
    int hops, loop, local_delivered;
};
static void group_deliver(struct md_network_forward *f,struct listener *sender,
        struct md_network_namespace *n,struct md_network_interface *link,
        struct md_network_flow_path *path,int hops,const void *data,size_t size) {
    struct md_inet_address target;
    const void *receiver=NULL;
    int joined=md_network_multicast_subscribed(n,link,path->translated.destination);
    while ((receiver=md_network_endpoint_group_next(n,link,&path->translated,joined,receiver,&target))) {
        struct flow *c=f->connections;
        while (c && !(c->group && c->endpoint==sender->endpoint && c->receiver==receiver
                && c->group_hops==hops && c->path.translated.input==link->info.index
                && md_ip_equal(c->path.original.source,path->original.source)
                && md_ip_equal(c->path.original.destination,path->original.destination)
                && c->path.original.source_port==path->original.source_port
                && c->path.original.destination_port==path->original.destination_port)) c=c->next;
        if (!c) {
            c=new_flow(f,sender,-1,&sender->client,path,&target,receiver);
            if (!c) { ++f->dropped_datagrams; continue; }
            c->group=1; c->group_hops=hops; c->packet_policy=link->policy_id;
            int v6=target.ip.family==AF_INET6,native_hops=hops ? hops : 1;
            if (setsockopt(c->fd[1],v6 ? IPPROTO_IPV6 : IPPROTO_IP,v6 ? IPV6_UNICAST_HOPS : IP_TTL,
                    &native_hops,sizeof(native_hops))) {
                drop_flow(f,c); ++f->dropped_datagrams; continue;
            }
        }
        datagram_submit(f,c,0,data,size);
    }
}
static void group_visit(void *context,struct md_network_namespace *n,struct md_network_interface *link,int outgoing) {
    struct group_delivery *d=context;
    if (outgoing || (n==d->space && (!d->loop || link!=d->output))
            || (!d->hops && (n!=d->space || link!=d->output))) return;
    if (n==d->space && link==d->output) {
        if (d->local_delivered) return;
        d->local_delivered=1;
    }
    struct md_network_flow_path path=d->path;
    /* Output was evaluated and accounted once, before the L2 fanout. Each
     * recipient owns only its input hooks, queue and native receive socket. */
    path.count=0;
    int error=md_network_flow_group_input(n,link,&path);
    struct md_filter_accounting accounting={0};
    int denied=md_network_flow_accounting(d->owner->fabric,&path,0,!d->sender->group_started,&accounting);
    md_filter_account(&accounting,d->size); free(accounting.cells);
    if (error || denied) return;
    group_deliver(d->owner,d->sender,n,link,&path,d->hops,d->data,d->size);
}
static void group_send(struct md_network_forward *f,struct listener *l,const void *data,size_t size) {
    struct md_network_namespace *n=md_network_space_find(f->fabric,l->space);
    struct md_network_interface *link=n ? md_network_interface_find(n,l->group_output,NULL) : NULL;
    const struct md_network_multicast *m=md_network_endpoint_multicast(l->endpoint,l->path.original.destination.family);
    if (!m || !link || link->policy_id!=l->group_policy || !(link->info.flags&IFF_UP)) return;
    struct group_delivery d={.owner=f,.sender=l,.space=n,.output=link,.data=data,.size=size,
        .hops=l->group_hops<0 ? m->hops : l->group_hops,.loop=m->loop};
    struct md_filter_packet p=l->path.original;
    p.state=l->group_started ? 2 : 8;
    int routed=md_network_flow_group_output(n,link,&p,&d.path);
    struct md_filter_accounting accounting={0};
    int error=md_network_flow_accounting(f->fabric,&d.path,0,!l->group_started,&accounting);
    md_filter_account(&accounting,size); free(accounting.cells);
    if (error || routed) return;
    if (link->kind!=MD_LINK_LOOPBACK) group_visit(&d,n,link,0);
    if (md_network_link_walk(n,link,0,group_visit,&d)) ++f->dropped_datagrams;
    l->group_started=1;
    l->deadline=now_ms()+30000;
}
static void udp_accept(struct md_network_forward *f,struct listener *l) {
    unsigned char data[65536];
    for (unsigned budget=0;budget<16;++budget) {
        struct md_inet_address client; struct sockaddr_storage wire;
        struct iovec vector={data,sizeof(data)};
        union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(sizeof(struct in_pktinfo))+CMSG_SPACE(sizeof(struct in6_pktinfo))]; } ancillary;
        struct msghdr message={.msg_name=&wire,.msg_namelen=sizeof(wire),.msg_iov=&vector,.msg_iovlen=1,
            .msg_control=ancillary.bytes,.msg_controllen=sizeof(ancillary)};
        ssize_t bytes=recvmsg(l->fd,&message,MSG_DONTWAIT);
        if (bytes<0) return;
        if (md_inet_decode((void *)&wire,message.msg_namelen,&client)) continue;
        if (l->group_output) {
            if (same(&client,&l->client)) group_send(f,l,data,(size_t)bytes);
            continue;
        }
        struct md_inet_address local=l->address;
        for (struct cmsghdr *h=CMSG_FIRSTHDR(&message);h;h=CMSG_NXTHDR(&message,h))
            if (h->cmsg_level==IPPROTO_IP && h->cmsg_type==IP_PKTINFO && h->cmsg_len==CMSG_LEN(sizeof(struct in_pktinfo))) {
                struct in_pktinfo info; memcpy(&info,CMSG_DATA(h),sizeof(info)); local.ip=md_ip_v4(info.ipi_addr.s_addr);
            } else if (h->cmsg_level==IPPROTO_IPV6 && h->cmsg_type==IPV6_PKTINFO && h->cmsg_len==CMSG_LEN(sizeof(struct in6_pktinfo))) {
                struct in6_pktinfo info; memcpy(&info,CMSG_DATA(h),sizeof(info));
                local.ip=md_ip_load(AF_INET6,&info.ipi6_addr);
                if (IN6_IS_ADDR_V4MAPPED(&info.ipi6_addr)) local.ip=md_ip_load(AF_INET,info.ipi6_addr.s6_addr+12);
            }
        struct flow *c=f->connections;
        while (c && !(c->listener==l->id && same(&c->client,&client) && md_ip_equal(c->path.original.destination,local.ip))) c=c->next;
        if (c && c->receiver_closed) { drop_flow(f,c); c=NULL; }
        if (!c) {
            struct md_network_flow_path path; struct md_inet_address target;
            if (incoming_path(f,l,&client,&local,&path,&target)) continue;
            c=new_flow(f,l,l->fd,&client,&path,&target,NULL);
            if (!c) continue;
        }
        datagram_submit(f,c,0,data,(size_t)bytes);
    }
}
static int stream_event(struct md_network_forward *f,struct flow *c,unsigned leg,uint32_t events) {
    if (c->connecting && leg && (events&(EPOLLOUT|EPOLLERR|EPOLLHUP))) {
        int error=0; socklen_t size=sizeof(error);
        if (getsockopt(c->fd[1],SOL_SOCKET,SO_ERROR,&error,&size)) return -errno;
        if (error) return -error;
        c->connecting=0; c->deadline=INT64_MAX;
        md_network_endpoint_connect_complete(c->endpoint,0);
    }
    if (events&EPOLLERR) return -ECONNRESET;
    if (c->connecting) return interests(f,c);
    if ((events&(EPOLLIN|EPOLLRDHUP|EPOLLHUP)) && !c->eof[leg] && !c->buffer[leg].end) {
        if (check(f,c,leg)) {
            if (events&(EPOLLRDHUP|EPOLLHUP)) return -ECONNRESET;
        } else {
            ssize_t bytes=recv(c->fd[leg],c->buffer[leg].data,sizeof(c->buffer[leg].data),MSG_DONTWAIT);
            if (bytes>0) { c->buffer[leg].end=(size_t)bytes; account(c,leg,(size_t)bytes); }
            else if (!bytes) { c->eof[leg]=1; shutdown(c->fd[1-leg],SHUT_WR); }
            else if (errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR) return -errno;
        }
    }
    for (unsigned direction=0;direction<2;++direction) {
        struct stream_buffer *b=&c->buffer[direction];
        if (b->end && !check(f,c,direction)) {
            ssize_t bytes=send(c->fd[1-direction],b->data+b->begin,b->end-b->begin,MSG_DONTWAIT|MSG_NOSIGNAL);
            if (bytes>0) { b->begin+=(size_t)bytes; if (b->begin==b->end) b->begin=b->end=0; }
            else if (bytes<0 && errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR) return -errno;
        }
    }
    return c->eof[0] && c->eof[1] && !c->buffer[0].end && !c->buffer[1].end ? -ECONNRESET : interests(f,c);
}
static void *run(void *context) {
    struct md_network_forward *f=context;
    int timeout=-1;
    for (;;) {
        struct epoll_event events[64];
        /* EVENT_WAIT: socket readiness, ruleset/lifetime change or a UDP/pending
         * connect expiry. Idle listener sets have no periodic wakeup. */
        int count=epoll_wait(f->epoll,events,64,timeout);
        int wait_error=count<0 ? errno : 0;
        pthread_mutex_lock(&f->fabric->lock);
        if (f->stopped) { pthread_mutex_unlock(&f->fabric->lock); return NULL; }
        if (count<0 && wait_error!=EINTR) { pthread_mutex_unlock(&f->fabric->lock); return NULL; }
        for (int i=0;i<count;++i) {
            uint64_t id=events[i].data.u64/4; unsigned leg=(unsigned)(events[i].data.u64%4);
            if (!id) {
                uint64_t value; while (read(f->wake,&value,sizeof(value))>0) {}
                struct flow *c=f->connections;
                while (c) {
                    struct flow *next=c->next;
                    int error=interests(f,c);
                    if (error) { md_network_endpoint_connect_complete(c->endpoint,error); drop_flow(f,c); }
                    c=next;
                }
            } else if (leg==2) {
                struct listener *l=find_listener(f,id);
                if (l && l->active) {
                    if (l->type==SOCK_STREAM) tcp_accept(f,l);
                    else {
                        if (events[i].events&EPOLLIN) udp_accept(f,l);
                        if (events[i].events&EPOLLOUT) for (struct flow *c=f->connections;c;c=c->next)
                            if (c->borrowed && c->listener==id) datagram_flush(f,c,1);
                    }
                }
            } else {
                struct flow *c=find_flow(f,id);
                if (c) {
                    if (c->type==SOCK_DGRAM) {
                        if (events[i].events&EPOLLIN) udp_receive(f,c,leg);
                        if (events[i].events&EPOLLOUT) datagram_flush(f,c,1-leg);
                        if (events[i].events&EPOLLERR) { int error=0; socklen_t size=sizeof(error); getsockopt(c->fd[leg],SOL_SOCKET,SO_ERROR,&error,&size); }
                    }
                    else {
                        int error=stream_event(f,c,leg,events[i].events);
                        if (error) { md_network_endpoint_connect_complete(c->endpoint,error); drop_flow(f,c); }
                    }
                }
            }
        }
        int64_t now=now_ms(),deadline=md_network_route_expire(f->fabric,now);
        struct flow *c=f->connections;
        while (c) {
            struct flow *next=c->next;
            if (c->deadline<=now) {
                md_network_endpoint_connect_complete(c->endpoint,-ETIMEDOUT); drop_flow(f,c);
            } else if (c->deadline<deadline) deadline=c->deadline;
            c=next;
        }
        struct listener *l=f->ports;
        while (l) {
            struct listener *next=l->next;
            if (l->deadline<=now) {
                if (!l->external) md_network_endpoint_connect_complete(l->endpoint,-ETIMEDOUT);
                drop_listener(f,l);
            } else if (l->deadline<deadline) deadline=l->deadline;
            l=next;
        }
        timeout=deadline==INT64_MAX ? -1 : deadline-now>INT_MAX ? INT_MAX : (int)(deadline-now);
        pthread_mutex_unlock(&f->fabric->lock);
    }
}
static int ensure(struct md_network_fabric *fabric) {
    if (fabric->forward) return 0;
    struct md_network_forward *f=calloc(1,sizeof(*f));
    if (!f) return -ENOMEM;
    f->fabric=fabric; f->epoll=epoll_create1(EPOLL_CLOEXEC); f->wake=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);
    int error=0;
    if (f->epoll<0 || f->wake<0) error=-errno;
    if (!error) error=watch(f,EPOLL_CTL_ADD,f->wake,0,0,EPOLLIN);
    if (!error) error=-pthread_create(&f->thread,NULL,run,f);
    if (error) { if (f->wake>=0) close(f->wake); if (f->epoll>=0) close(f->epoll); free(f); return error; }
    fabric->forward=f; return 0;
}
int md_network_forward_timer(struct md_network_fabric *fabric) { return ensure(fabric); }
static int publish_reserved(struct md_network_forward *,struct listener *,int);
static struct listener *new_listener(struct md_network_forward *f,uint64_t space,int type,struct md_inet_address address,int external,int listening,int v6only) {
    if (f->listeners==512 || f->next_id>UINT64_MAX/4) { errno=ENOSPC; return NULL; }
    struct listener *l=calloc(1,sizeof(*l)); if (!l) return NULL;
    l->reserved=l->upstream_fd=-1;
    l->space=space; l->type=type; l->external=external; l->id=++f->next_id;
    l->address=address;
    l->v6only=v6only;
    if (external && !md_ip_unspecified(address.ip) && !md_ip_is_loopback(address.ip))
        address.ip=md_ip_any(address.ip.family);
    l->fd=socket_bound(type,&address,v6only);
    l->address.port=address.port;
    if (l->fd<0) { errno=-l->fd; free(l); return NULL; }
    int error=0;
    if (type==SOCK_STREAM) {
        if (listening) error=external ? publish_reserved(f,l,64) : listen(l->fd,64) ? -errno : 0;
    }
    else {
        int one=1,v6=address.ip.family==AF_INET6;
        if (setsockopt(l->fd,v6 ? IPPROTO_IPV6 : IPPROTO_IP,v6 ? IPV6_RECVPKTINFO : IP_PKTINFO,&one,sizeof(one))) error=-errno;
    }
    l->listening=type==SOCK_DGRAM || listening;
    if (!error && l->listening && l->reserved<0) error=watch(f,EPOLL_CTL_ADD,l->fd,l->id,2,EPOLLIN);
    if (error) {
        close(l->fd); if (l->reserved>=0) close(l->reserved);
        free(l); errno=-error; return NULL;
    }
    l->deadline=external ? INT64_MAX : now_ms()+30000;
    l->next=f->ports; f->ports=l; ++f->listeners; return l;
}
int md_network_forward_bind(struct md_network_namespace *n,const void *endpoint,int type,
        const struct md_inet_address *address,int v6only,struct md_network_port_reservation **out) {
    *out=NULL;
    int error=ensure(n->fabric); if (error) return error;
    struct md_network_port_reservation *r=calloc(1,sizeof(*r));
    if (!r) return -ENOMEM;
    r->owner=n->fabric->forward;
    struct listener *l=r->owner->ports;
    while (l && !(l->external && l->space==n->inode && l->type==type && same(&l->address,address))) l=l->next;
    if (l && (l->endpoint==endpoint || (l->endpoint && type==SOCK_DGRAM))) { free(r); return 0; }
    if (l && l->endpoint) { free(r); return -EADDRINUSE; }
    if (!l) {
        l=new_listener(r->owner,n->inode,type,*address,1,0,v6only);
        if (!l) { error=-errno; free(r); return error; }
        r->created=1;
    }
    r->listener=l; l->endpoint=endpoint; *out=r; return 0;
}
void md_network_forward_bound(struct md_network_port_reservation *r,int success) {
    if (!r) return;
    if (success) r->listener->active=r->listener->listening;
    else {
        r->listener->endpoint=NULL;
        if (r->created) drop_listener(r->owner,r->listener);
    }
    wake(r->owner); free(r);
}
int md_network_forward_listen(struct md_network_namespace *n,const void *endpoint,int backlog) {
    struct md_network_forward *f=n->fabric->forward;
    if (!f) return 0;
    for (struct listener *l=f->ports;l;l=l->next) if (l->endpoint==endpoint && l->external && l->type==SOCK_STREAM) {
        int error=l->listening ? (listen(l->fd,backlog) ? -errno : 0) : publish_reserved(f,l,backlog);
        if (error) return error;
        l->endpoint_listening=l->listening=l->active=1; wake(f); return 0;
    }
    return 0;
}
/* A bound guest socket is not a listener. Retain its native reservation while
 * NAT owns a separate acceptor, so abort/removal can restore bind-only state
 * without a port-release race or an irreversible listen on the reservation. */
static int publish_reserved(struct md_network_forward *f,struct listener *l,int backlog) {
    struct md_inet_address address;
    if (md_inet_name(l->fd,0,&address)) return -errno;
    int fd=socket_bound(SOCK_STREAM,&address,l->v6only);
    if (fd<0) return fd;
    int error=listen(fd,backlog) ? -errno : watch(f,EPOLL_CTL_ADD,fd,l->id,2,EPOLLIN);
    if (error) { close(fd); return error; }
    l->reserved=l->fd; l->fd=fd; l->listening=1;
    return 0;
}
static void restore_reserved(struct md_network_forward *f,struct listener *l) {
    epoll_ctl(f->epoll,EPOLL_CTL_DEL,l->fd,NULL); close(l->fd);
    l->fd=l->reserved; l->reserved=-1; l->listening=l->active=0;
}
int md_network_forward_prepare(struct md_network_namespace *n,struct md_network_filter *filter,struct md_network_forward_update **out) {
    *out=NULL;
    struct md_filter_port ports[512]; size_t count=0;
    int error=0;
    if (n->mode==MD_NETWORK_ROUTED) for (unsigned i=0;i<2 && !error;++i) {
        struct md_ip_address target=md_ip_any(i ? AF_INET6 : AF_INET);
        struct md_network_interface *uplink=md_network_route_find(n,target,NULL);
        size_t added=0;
        error=md_filter_ports(filter,md_network_interface_source(uplink,target),ports+count,512-count,&added);
        count+=added;
    }
    if (error) return error;
    if (!count && !n->fabric->forward) return 0;
    error=ensure(n->fabric); if (error) return error;
    struct md_network_forward_update *u=calloc(1,sizeof(*u)); if (!u) return -ENOMEM;
    u->owner=n->fabric->forward; u->space=n->inode;
    for (size_t i=0;!error && i<count;++i) {
        struct md_inet_address address={.ip=ports[i].address,.port=ports[i].port};
        int type=ports[i].protocol==IPPROTO_TCP ? SOCK_STREAM : SOCK_DGRAM;
        struct listener *l=u->owner->ports;
        while (l && !(l->external && l->space==n->inode && l->type==type && same(&l->address,&address))) l=l->next;
        if (!l) {
            l=new_listener(u->owner,n->inode,type,address,1,1,1);
            if (!l) { error=-errno; break; }
            u->created[u->count]=1;
        } else if (!l->listening) {
            error=publish_reserved(u->owner,l,64);
            if (error) break;
            u->created[u->count]=2;
        }
        u->ports[u->count++]=l;
    }
    if (error) { md_network_forward_abort(u); return error; }
    *out=u; return 0;
}
void md_network_forward_abort(struct md_network_forward_update *u) {
    if (!u) return;
    for (size_t i=0;i<u->count;++i) {
        if (u->created[i]==1) drop_listener(u->owner,u->ports[i]);
        else if (u->created[i]==2) restore_reserved(u->owner,u->ports[i]);
    }
    free(u);
}
void md_network_forward_commit(struct md_network_forward_update *u) {
    if (!u) return;
    struct listener *l=u->owner->ports;
    while (l) {
        struct listener *next=l->next;
        if (l->space==u->space && l->external) {
            size_t i=0; while (i<u->count && u->ports[i]!=l) ++i;
            l->published=i!=u->count;
            if (!l->published && !l->endpoint) drop_listener(u->owner,l);
            else {
                if (!l->published && !l->endpoint_listening && l->reserved>=0) restore_reserved(u->owner,l);
                l->active=l->listening;
            }
        }
        l=next;
    }
    wake(u->owner); free(u);
}
void md_network_forward_changed(struct md_network_fabric *fabric) {
    if (!++fabric->policy_generation) ++fabric->policy_generation;
    wake(fabric->forward);
}
int md_network_forward_route(struct md_network_namespace *n,const void *endpoint,int type,const struct md_inet_address *client,
        const struct md_inet_address *target,struct md_network_flow_path *path,struct md_inet_address *destination) {
    int error=ensure(n->fabric); if (error) return error;
    struct md_network_forward *f=n->fabric->forward;
    struct md_inet_address address={.ip=md_ip_loopback(path->original.source.family)};
    struct listener *l=new_listener(f,n->inode,type,address,0,1,1);
    if (!l) return -errno;
    if (type==SOCK_STREAM) {
        error=md_network_endpoint_connect_begin(endpoint);
        if (error) { drop_listener(f,l); return error; }
        /* Retain the real connect and publish its eventual result through the
         * endpoint. The local handshake alone is not connection completion. */
        struct md_inet_address local;
        l->upstream_fd=connect_target(endpoint,type,path->transport,target,&local,&l->upstream_connecting);
        if (l->upstream_fd<0) {
            error=l->upstream_fd; md_network_endpoint_connect_complete(endpoint,error);
            drop_listener(f,l); return error;
        }
    }
    l->endpoint=endpoint; l->client=*client; l->client.ip=address.ip;
    l->target=*target; l->path=*path; l->active=1;
    *destination=l->address;
    if (type==SOCK_DGRAM) {
        epoll_ctl(f->epoll,EPOLL_CTL_DEL,l->fd,NULL);
        int fd=l->fd;
        struct flow *c=new_flow(f,l,fd,&l->client,path,target,NULL);
        if (!c) { drop_listener(f,l); return -errno; }
        l->fd=-1; drop_listener(f,l);
    }
    wake(f); return 0;
}
int md_network_forward_datagram(struct md_network_namespace *n,unsigned input,
        const struct md_filter_packet *packet,unsigned hops,const void *data,size_t size) {
    struct md_network_interface *link=md_network_interface_find(n,input,NULL);
    if (!link) return -ENODEV;
    int error=ensure(n->fabric); if (error) return error;
    struct md_network_forward *f=n->fabric->forward;
    if (md_ip_multicast(packet->destination) || md_network_interface_broadcast(link,packet->destination)) {
        struct md_network_flow_path path={.original=*packet,.translated=*packet,.mediated=1,
            .generation=n->fabric->policy_generation};
        path.original.packet_type=path.translated.packet_type=md_ip_multicast(packet->destination)
            ? PACKET_MULTICAST : PACKET_BROADCAST;
        path.original.fib_type=path.translated.fib_type=md_ip_multicast(packet->destination)
            ? RTN_MULTICAST : RTN_BROADCAST;
        error=md_network_flow_group_input(n,link,&path);
        struct md_filter_accounting accounting={0};
        int denied=md_network_flow_accounting(n->fabric,&path,0,1,&accounting);
        md_filter_account(&accounting,size); free(accounting.cells);
        if (error || denied) return error ? error : denied;
        struct listener sender={.space=n->inode,.type=SOCK_DGRAM,
            .address={.ip=md_ip_loopback(packet->source.family)}};
        group_deliver(f,&sender,n,link,&path,(int)hops,data,size);
        wake(f); return 0;
    }
    struct flow *c=f->connections;
    while (c) {
        const struct md_filter_packet *original=&c->path.original;
        if (c->space==n->inode && c->packet_input==input && c->packet_policy==link->policy_id
                && !c->receiver_closed
                && md_ip_equal(original->source,packet->source) && md_ip_equal(original->destination,packet->destination)
                && original->source_port==packet->source_port && original->destination_port==packet->destination_port) break;
        c=c->next;
    }
    if (!c) {
        /* A retained UDP tuple keeps its NAT binding. Only a new flow routes
         * through NEW rules; generation changes recheck live filter policy. */
        struct md_network_flow_path path;
        struct md_filter_packet p=*packet;
        error=md_network_flow_route(n,&p,1,input,&path);
        struct md_inet_address target;
        const void *endpoint=NULL;
        if (!error) error=md_network_endpoint_resolve(n->fabric,&path,SOCK_DGRAM,&target,&endpoint);
        if (error || !endpoint) {
            /* Raw observers still receive admitted UDP without a UDP socket.
             * Count the actual packet (including a denied one), not each tap. */
            if (error && error!=-ECONNREFUSED && error!=-EACCES) return error;
            struct md_filter_accounting accounting={0};
            int verdict=md_network_flow_accounting(n->fabric,&path,0,1,&accounting);
            md_filter_account(&accounting,size); free(accounting.cells);
            return verdict ? verdict : error==-ECONNREFUSED ? 0 : error;
        }
        struct listener l={.space=n->inode,.type=SOCK_DGRAM,.address={.ip=md_ip_loopback(packet->source.family)}};
        int fd=socket_bound(SOCK_DGRAM,&l.address,1);
        if (fd<0) return fd;
        c=new_flow(f,&l,fd,&l.address,&path,&target,endpoint);
        if (!c) { error=-errno; close(fd); return error; }
        c->packet_input=input; c->packet_policy=link->policy_id;
    }
    error=datagram_submit(f,c,0,data,size);
    int watched=interests(f,c);
    if (watched) { drop_flow(f,c); if (!error) error=watched; }
    wake(f); return error;
}
int md_network_forward_group(struct md_network_namespace *n,const void *endpoint,const struct md_inet_address *client,
        struct md_filter_packet *packet,unsigned output,int hops,struct md_inet_address *destination) {
    struct md_network_route_entry route;
    struct md_network_interface *link=md_network_route_lookup(n,packet->destination,output,&route);
    if (!link || !(link->info.flags&IFF_UP)) return -ENETUNREACH;
    if (md_ip_unspecified(packet->source)) packet->source=md_network_route_source(n,link,&route,packet->destination);
    if (md_ip_unspecified(packet->source)) return -EADDRNOTAVAIL;
    int error=ensure(n->fabric); if (error) return error;
    struct md_network_forward *f=n->fabric->forward;
    for (struct listener *l=f->ports;l;l=l->next)
        if (l->group_output==link->info.index && l->group_policy==link->policy_id && l->endpoint==endpoint
                && l->group_hops==hops && md_ip_equal(l->path.original.source,packet->source)
                && md_ip_equal(l->path.original.destination,packet->destination)
                && l->path.original.destination_port==packet->destination_port) {
            *destination=l->address; return 0;
        }
    struct md_inet_address address={.ip=md_ip_loopback(packet->source.family)};
    struct listener *l=new_listener(f,n->inode,SOCK_DGRAM,address,0,1,1);
    if (!l) return -errno;
    l->endpoint=endpoint; l->client=*client; l->client.ip=address.ip;
    l->group_output=link->info.index; l->group_policy=link->policy_id; l->group_hops=hops;
    /* A connected socket keeps this carrier across membership/rule changes.
     * Evaluate and account actual packets in the relay, before fanout. */
    l->path.original=*packet; l->active=1;
    *destination=l->address; wake(f); return 0;
}
int md_network_forward_source(struct md_network_namespace *n,int type,struct md_inet_address *address,struct md_network_packet_info *info) {
    struct md_network_forward *f=n->fabric->forward;
    if (!f || !md_ip_is_loopback(address->ip)) return 0;
    for (struct flow *c=f->connections;c;c=c->next) {
        if (c->type!=type) continue;
        if (c->remote.ip.family==address->ip.family && c->remote.port==address->port && c->path.destination==n->inode) {
            if (info) *info=(struct md_network_packet_info){.destination=c->path.translated.destination,
                .interface=c->path.translated.input,.hops=c->group_hops,.hops_set=c->group};
            address->ip=c->path.translated.source; address->port=c->path.translated.source_port; return 1;
        }
        if (c->gateway.ip.family==address->ip.family && c->gateway.port==address->port
                && (c->packet_input ? c->packet_destination==n->inode : c->space==n->inode) && !c->borrowed) {
            if (info) *info=(struct md_network_packet_info){.destination=c->path.original.source};
            address->ip=c->path.original.destination; address->port=c->path.original.destination_port; return 1;
        }
    }
    return 0;
}
int md_network_forward_destination(struct md_network_namespace *n,const void *endpoint,int type,const struct md_inet_address *local,
        const struct md_inet_address *destination,struct md_inet_address *native) {
    struct md_network_forward *f=n->fabric->forward;
    if (!f) return 0;
    for (struct flow *c=f->connections;c;c=c->next) {
        if (c->type!=type || c->group) continue;
        /* Addressed UDP keeps the established mapping too. The relay checks
         * the current generation; sendto must not re-run NEW/NAT each time. */
        if (type==SOCK_DGRAM && c->endpoint==endpoint
                && !c->receiver_closed
                && (md_ip_unspecified(local->ip) || md_ip_equal(local->ip,c->path.original.source))
                && md_ip_equal(c->path.original.destination,destination->ip)
                && c->path.original.destination_port==destination->port) {
            *native=c->gateway; return 1;
        }
        if (c->path.destination==n->inode
                && (type!=SOCK_DGRAM || c->receiver==endpoint)
                && local->port==c->path.translated.destination_port
                && (md_ip_unspecified(local->ip) || md_ip_equal(local->ip,c->path.translated.destination))
                && md_ip_equal(destination->ip,c->path.translated.source) && destination->port==c->path.translated.source_port) {
            *native=c->remote; native->ip=md_ip_loopback(native->ip.family); return 1;
        }
    }
    return 0;
}
int md_network_forward_endpoint_active(struct md_network_namespace *n,const void *endpoint) {
    struct md_network_forward *f=n->fabric->forward;
    if (!f) return 0;
    for (struct listener *l=f->ports;l;l=l->next)
        if (!l->external && l->endpoint==endpoint) return 1;
    for (struct flow *c=f->connections;c;c=c->next)
        if (c->endpoint==endpoint || c->receiver==endpoint) return 1;
    return 0;
}
void md_network_forward_endpoint_disconnected(struct md_network_namespace *n,const void *endpoint,int unbound) {
    struct md_network_forward *f=n->fabric->forward; if (!f) return;
    struct listener *l=f->ports;
    while (l) {
        struct listener *next=l->next;
        if (l->endpoint==endpoint && (unbound || !l->external)) {
            l->endpoint=l->external && l->type==SOCK_DGRAM ? md_network_endpoint_publication(n,endpoint,l->type,&l->address) : NULL;
            if (l->endpoint) { l=next; continue; }
            l->endpoint_listening=0;
            if (!l->external || !l->published) drop_listener(f,l);
        }
        l=next;
    }
    struct flow *c=f->connections;
    while (c) {
        struct flow *next=c->next;
        int sender=c->endpoint==endpoint, receiver=c->receiver==endpoint;
        if (sender) c->endpoint=NULL;
        if (receiver) { c->receiver=NULL; c->receiver_closed=1; }
        if ((sender || receiver) && (c->type!=SOCK_DGRAM || (!c->endpoint && !c->receiver && !c->borrowed)))
            drop_flow(f,c);
        /* A surviving private peer may still receive already queued data.
         * Keep its tuple until that peer closes or the existing UDP idle bound;
         * published listeners above are released immediately. */
        c=next;
    }
    wake(f);
}
void md_network_forward_endpoint_closed(struct md_network_namespace *n,const void *endpoint) {
    md_network_forward_endpoint_disconnected(n,endpoint,1);
}
void md_network_forward_retire(struct md_network_namespace *n) {
    struct md_network_forward *f=n->fabric->forward; if (!f) return;
    struct listener *l=f->ports;
    while (l) { struct listener *next=l->next; if (l->space==n->inode) drop_listener(f,l); l=next; }
    struct flow *c=f->connections;
    while (c) {
        struct flow *next=c->next; int found=c->space==n->inode;
        for (unsigned i=0;i<c->path.count;++i) found|=c->path.steps[i].space==n->inode;
        if (found) drop_flow(f,c);
        c=next;
    }
    wake(f);
}
void md_network_forward_shutdown(struct md_network_fabric *fabric) {
    struct md_network_forward *f=fabric->forward; if (!f) return;
    pthread_mutex_lock(&fabric->lock); f->stopped=1; wake(f); pthread_mutex_unlock(&fabric->lock);
    /* EVENT_WAIT: the exclusively owned relay acknowledges stop before its
     * fabric lock and queue descriptors are destroyed. No guest IO is awaited. */
    pthread_join(f->thread,NULL);
    while (f->ports) drop_listener(f,f->ports);
    while (f->connections) drop_flow(f,f->connections);
    close(f->wake); close(f->epoll); free(f); fabric->forward=NULL;
}
