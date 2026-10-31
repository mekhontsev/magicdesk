#define _GNU_SOURCE
#include "network_endpoint.h"
#include "network_internal.h"
#include "network_forward.h"
#include "network_connection.h"
#include "network_policy.h"
#include "fd_selector.h"
#include "guest_identity.h"
#include <errno.h>
#include <net/if.h>
#include <netinet/tcp.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

struct direct_path { struct direct_path *next; struct md_network_flow_path path; };
struct md_network_endpoint {
    struct md_fd_object object;
    struct md_network_namespace *space;
    struct md_network_endpoint *next;
    int type, protocol, bound, connected, accepted, v6only;
    int reuse_address, reuse_port, priority;
    unsigned family;
    int published, publication_requested, listening, backlog;
    unsigned device;
    uint32_t mark;
    struct md_ip_address source, binding;
    struct md_inet_address local, native, peer;
    struct direct_path *direct;
    unsigned direct_count;
    uint64_t inode;
    uint32_t uid;
    struct md_network_transport transport;
    struct md_network_multicast multicast[2];
    struct md_network_connection *connection;
};
static void endpoint_changed(struct md_network_endpoint *e) {
    if (e->type==SOCK_DGRAM) ++e->space->fabric->endpoint_generation;
}
static void destroy(struct md_fd_object *object) {
    struct md_network_endpoint *e=(void *)object;
    struct md_network_fabric *f=e->space->fabric;
    pthread_mutex_lock(&f->lock);
    struct md_network_endpoint **at=&f->endpoints;
    while (*at && *at!=e) at=&(*at)->next;
    if (*at) *at=e->next;
    endpoint_changed(e);
    md_network_forward_endpoint_closed(e->space,e);
    while (e->direct) { struct direct_path *p=e->direct; e->direct=p->next; free(p); }
    for (unsigned i=0;i<2;++i) md_network_multicast_destroy(&e->multicast[i]);
    pthread_mutex_unlock(&f->lock);
    md_network_connection_free(e->connection);
    md_network_namespace_release(e->space); free(e);
}
static int direct_path(struct md_network_endpoint *e,const struct md_network_flow_path *path) {
    for (struct direct_path *p=e->direct;p;p=p->next)
        if (md_ip_equal(p->path.original.destination,path->original.destination)
                && p->path.original.destination_port==path->original.destination_port) return 0;
    if (e->direct_count==1024) return -ENOSPC;
    struct direct_path *p=malloc(sizeof(*p));
    if (!p) return -ENOMEM;
    p->path=*path; p->next=e->direct; e->direct=p; ++e->direct_count; return 0;
}
void md_network_endpoint_nat_ports(struct md_network_namespace *n,const struct md_filter_packet *packet,
        struct md_network_nat_ports *ports) {
    for (struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next)
        for (struct direct_path *p=e->direct;p;p=p->next)
            md_network_flow_nat_ports(&p->path,n->inode,packet,ports);
}
int md_network_endpoint_policy_update(struct md_network_namespace *n,struct md_network_filter *filter) {
    /* Direct kernel streams cannot be moved into a relay after connect. Reject
     * a revoking transaction atomically rather than retain a stale allowance.
     * No per-read/write broker call is added to the ordinary data path. */
    for (struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next)
        for (struct direct_path *p=e->direct;p;p=p->next) {
            int error=md_network_flow_admits(n,filter,&p->path);
            if (error) return error==-EACCES ? -EBUSY : error;
        }
    return 0;
}
static unsigned selectors(const struct md_fd_object *object) {
    const struct md_network_endpoint *e=(const void *)object;
    return e->connection ? (1U<<MD_FD_SELECTOR_EPOLL_TARGET)|(1U<<MD_FD_SELECTOR_POLL) : 0;
}
static struct md_control_file readiness(struct md_fd_object *object) {
    struct md_network_endpoint *e=(void *)object;
    return md_network_connection_readiness(e->connection);
}
static const struct md_fd_object_type endpoint_type={.destroy=destroy,
    .selector_mask=selectors,.readiness=readiness};
int md_network_endpoint_tcp_idle(const struct md_network_namespace *n) {
    for (const struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next)
        if (e->space==n && e->protocol==IPPROTO_TCP) return 0;
    return 1;
}
int md_network_endpoint_transport_update(struct md_network_namespace *n) {
    /* An established native stream cannot acquire a new route ceiling without
     * retaining extra socket FDs. Reject that mutation before publication;
     * unrelated routes and subsequent addressed datagrams remain independent. */
    for (struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next) {
        for (struct direct_path *p=e->direct;p;p=p->next)
            if (md_network_flow_routing_admits(n,&p->path)) return -EBUSY;
        if (e->space!=n || !e->connected || e->peer.ip.family!=AF_INET6) continue;
        struct md_network_route_entry route={0};
        struct md_network_interface *link=md_network_policy_route(n,e->source,e->peer.ip,e->mark,
            e->device ? e->device : e->peer.scope,&route,NULL);
        if (!link) continue;
        struct md_network_transport_policy policy=md_network_route_transport(link,&route);
        if (policy.mtu!=e->transport.route.mtu || policy.hops!=e->transport.route.hops) return -EBUSY;
    }
    return 0;
}
int md_network_endpoint_is(const struct md_fd_object *o) { return o && o->type==&endpoint_type; }
struct md_network_namespace *md_network_endpoint_namespace(const struct md_fd_object *o) {
    return md_network_endpoint_is(o) ? ((const struct md_network_endpoint *)o)->space : NULL;
}
int md_network_endpoint_enabled(const struct md_network_namespace *n) {
    return n && (n->fabric->endpoints_enabled || atomic_load_explicit(&n->packet_enabled,memory_order_acquire));
}
unsigned md_network_endpoint_family(const struct md_fd_object *o) {
    return md_network_endpoint_is(o) ? ((const struct md_network_endpoint *)o)->family : AF_UNSPEC;
}
static struct md_network_endpoint *create(struct md_network_namespace *n,unsigned family,int type,int protocol) {
    struct md_network_endpoint *e=calloc(1,sizeof(*e));
    if (!e) return NULL;
    md_fd_object_init(&e->object,&endpoint_type);
    e->space=md_network_namespace_retain(n); e->type=type; e->protocol=protocol;
    e->transport=(struct md_network_transport)MD_NETWORK_TRANSPORT_INITIALIZER;
    e->family=family; e->binding=e->source=e->local.ip=e->native.ip=e->peer.ip=md_ip_any(family);
    pthread_mutex_lock(&n->fabric->lock);
    for (unsigned i=0;i<2;++i) md_network_multicast_attach(&e->multicast[i],n);
    e->next=n->fabric->endpoints; n->fabric->endpoints=e;
    pthread_mutex_unlock(&n->fabric->lock); return e;
}
int md_network_endpoint_open(struct md_network_namespace *n,uint32_t uid,int family,int type,int protocol,
        struct md_fd_object **out,int *fd) {
    if (!md_network_endpoint_enabled(n)) return -EOPNOTSUPP;
    if (family!=AF_INET && family!=AF_INET6) return -EAFNOSUPPORT;
    int base=type&15;
    if (base!=SOCK_STREAM && base!=SOCK_DGRAM) return -ESOCKTNOSUPPORT;
    int expected=base==SOCK_STREAM ? IPPROTO_TCP : IPPROTO_UDP;
    if (protocol && protocol!=expected) return -EPROTONOSUPPORT;
    struct md_network_endpoint *e=create(n,family,base,expected);
    if (!e) return -ENOMEM;
    int native=socket(family,type|SOCK_CLOEXEC,protocol);
    if (native<0) { int error=-errno; md_fd_object_release(&e->object); return error; }
    struct stat st;
    if (fstat(native,&st)) { int error=-errno; close(native); md_fd_object_release(&e->object); return error; }
    if (base==SOCK_STREAM && !(e->connection=md_network_connection_new(native))) {
        int error=-errno; close(native); md_fd_object_release(&e->object); return error;
    }
    pthread_mutex_lock(&n->fabric->lock);
    e->inode=st.st_ino; e->uid=uid;
    int error=0;
    if (family==AF_INET6) {
        e->v6only=(int)n->bindv6only;
        if (setsockopt(native,IPPROTO_IPV6,IPV6_V6ONLY,&e->v6only,sizeof(e->v6only))) error=-errno;
    }
    if (expected==IPPROTO_TCP) {
        const int options[]={TCP_KEEPIDLE,TCP_KEEPINTVL,TCP_KEEPCNT};
        for (unsigned i=0;i<3 && !error;++i) {
            int value=(int)n->tcp_keepalive[i];
            if (setsockopt(native,IPPROTO_TCP,options[i],&value,sizeof(value))) error=-errno;
        }
    }
    pthread_mutex_unlock(&n->fabric->lock);
    if (error) { close(native); md_fd_object_release(&e->object); return error; }
    *out=&e->object; *fd=native; return 0;
}
static int address(struct md_network_endpoint *e,const struct sockaddr *a,socklen_t length,struct md_inet_address *out) {
    int error=md_inet_decode(a,length,out);
    if (!error && out->ip.family!=e->family && (e->family!=AF_INET6 || e->v6only)) error=-EAFNOSUPPORT;
    return error;
}
static struct md_network_interface *local_interface(struct md_network_namespace *n,struct md_ip_address ip,int up) {
    /* Logical peers resolve the live catalogue directly. arp_notify needs no
     * gratuitous packet: there is no neighbor cache to invalidate on link or
     * address changes, and no announcement is sent onto Android's network. */
    return md_network_local_interface(n,ip,up);
}
static int matches(const struct md_network_endpoint *e,struct md_ip_address ip) {
    if (e->local.ip.family==ip.family) return md_ip_unspecified(e->local.ip) || md_ip_unspecified(ip) || md_ip_equal(e->local.ip,ip);
    return e->family==AF_INET6 && !e->v6only && md_ip_unspecified(e->local.ip);
}
static int conflicts(struct md_network_endpoint *e,const struct md_inet_address *a,int listening) {
    for (struct md_network_endpoint *other=e->space->fabric->endpoints;other;other=other->next)
        if (other!=e && other->space==e->space && other->type==e->type && other->bound && !other->accepted
                && (!listening || other->listening)
                && (!other->device || !e->device || other->device==e->device)
                && (!a->scope || !other->local.scope || a->scope==other->local.scope)
                && other->local.port==a->port && (matches(other,a->ip)
                    || (e->family==AF_INET6 && !e->v6only && md_ip_unspecified(a->ip)))
                && !(e->reuse_port && other->reuse_port && e->uid==other->uid)
                && !(e->reuse_address && other->reuse_address && !other->listening)) return 1;
    return 0;
}
static int direct_endpoint_active(struct md_network_endpoint *e) {
    if (e->direct || e->connected) return 1;
    for (struct md_network_endpoint *other=e->space->fabric->endpoints;other;other=other->next)
        if (other->type==e->type) for (struct direct_path *p=other->direct;p;p=p->next)
            if (p->path.destination==e->space->inode && p->path.translated.destination_port==e->local.port
                    && matches(e,p->path.translated.destination)) return 1;
    return 0;
}
static int device_set(struct md_fd_object *o,const struct md_identity *identity,const char *name,int selected) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    if (name && strnlen(name,IFNAMSIZ)==IFNAMSIZ) return -EINVAL;
    struct md_network_endpoint *e=(void *)o;
    struct md_network_namespace *n=e->space;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *link=name ? (name[0] ? md_network_interface_find(n,0,name) : NULL)
        : selected>0 ? md_network_interface_find(n,(unsigned)selected,NULL) : NULL;
    unsigned index=name ? (link ? link->info.index : 0) : (unsigned)selected;
    int error=name && name[0] && !link ? -ENODEV : 0;
    if (!error && e->device && !md_identity_capable_in(identity,n->owner,CAP_NET_RAW)) error=-EPERM;
    if (!error && !name && selected<0) error=-EINVAL;
    /* Existing native streams cannot be retargeted in place. An idle bound
     * socket can change its publication without replacing the guest's OFD. */
    if (!error && e->device!=index && (direct_endpoint_active(e)
            || md_network_forward_endpoint_active(n,e))) error=-EBUSY;
    unsigned previous=e->device;
    if (!error && previous!=index) {
        e->device=index;
        if (e->bound && conflicts(e,&e->local,e->listening)) error=-EADDRINUSE;
        struct md_network_interface *local=local_interface(n,e->local.ip,0);
        int publish=e->bound && e->publication_requested && n->mode==MD_NETWORK_ROUTED
            && (!index || (link && (link->kind==MD_LINK_LOOPBACK || link->kind==MD_LINK_UPLINK)))
            && (md_ip_unspecified(e->local.ip) || md_ip_is_loopback(e->local.ip)
                || (local && local->kind==MD_LINK_UPLINK));
        struct md_network_port_reservation *reservation=NULL;
        if (!error && publish && !e->published) {
            error=md_network_forward_bind(n,e,e->type,&e->local,e->v6only,&reservation);
            if (!error && e->listening) error=md_network_forward_listen(n,e,e->backlog);
        }
        md_network_forward_bound(reservation,!error);
        if (error) e->device=previous;
        else {
            if (e->published && !publish) md_network_forward_endpoint_closed(n,e);
            e->published=publish;
            endpoint_changed(e);
        }
    }
    pthread_mutex_unlock(&n->fabric->lock); return error;
}
int md_network_endpoint_device_set(struct md_fd_object *o,const struct md_identity *identity,const char *name) {
    return device_set(o,identity,name,0);
}
int md_network_endpoint_device_get(struct md_fd_object *o,char *name,socklen_t *length) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    pthread_mutex_lock(&e->space->fabric->lock);
    int error=0;
    if ((int)*length<0) error=-EINVAL;
    else if (!e->device) *length=0;
    else if (*length<IFNAMSIZ) error=-EINVAL;
    else {
        struct md_network_interface *link=md_network_interface_find(e->space,e->device,NULL);
        if (!link) error=-ENODEV;
        else { *length=strlen(link->info.name)+1; memcpy(name,link->info.name,*length); }
    }
    pthread_mutex_unlock(&e->space->fabric->lock); return error;
}
static int device_admits(const void *endpoint,unsigned interface) {
    const struct md_network_endpoint *e=endpoint;
    unsigned index=e ? (e->device ? e->device : e->local.scope) : 0;
    if (!index) return 0;
    struct md_network_interface *link=md_network_interface_find(e->space,index,NULL);
    return link && (link->info.flags&IFF_UP) && index==interface ? 0 : -ENETUNREACH;
}
const struct md_network_multicast *md_network_endpoint_multicast(const void *object,unsigned family) {
    return md_network_endpoint_is(object) ? &((const struct md_network_endpoint *)object)->multicast[family==AF_INET6] : NULL;
}
const void *md_network_endpoint_group_next(struct md_network_namespace *n,struct md_network_interface *link,
        const struct md_filter_packet *p,int joined,const void *after,struct md_inet_address *native) {
    for (struct md_network_endpoint *e=after ? ((const struct md_network_endpoint *)after)->next : n->fabric->endpoints;e;e=e->next) {
        if (e->space!=n || e->type!=SOCK_DGRAM || !e->bound || e->local.port!=p->destination_port
                || !matches(e,p->destination) || device_admits(e,link->info.index)
                || (e->connected && (!md_ip_equal(e->peer.ip,p->source) || e->peer.port!=p->source_port))) continue;
        const struct md_network_multicast *m=&e->multicast[p->destination.family==AF_INET6];
        if (md_ip_multicast(p->destination) && !md_network_multicast_joined(m,link,p->destination) && !(m->all && joined)) continue;
        *native=e->native; native->ip=md_ip_loopback(p->destination.family); return e;
    }
    return NULL;
}
static int bind_endpoint(struct md_fd_object *o,int fd,const struct md_identity *identity,
        const struct sockaddr *a,socklen_t length,int external) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    struct md_inet_address logical;
    if (e->family==AF_INET6) {
        socklen_t size=sizeof(e->v6only); int value;
        if (getsockopt(fd,IPPROTO_IPV6,IPV6_V6ONLY,&value,&size)) return -errno;
        pthread_mutex_lock(&e->space->fabric->lock); e->v6only=value; pthread_mutex_unlock(&e->space->fabric->lock);
    }
    int error=address(e,a,length,&logical); if (error) return error;
    struct md_network_namespace *n=e->space;
    pthread_mutex_lock(&n->fabric->lock);
    if (md_ip_linklocal(logical.ip) && !logical.scope) logical.scope=e->device;
    if (e->bound) error=-EINVAL;
    else if (!md_ip_unspecified(logical.ip) && !md_ip_multicast(logical.ip) && !local_interface(n,logical.ip,0)) error=-EADDRNOTAVAIL;
    else if (md_ip_linklocal(logical.ip) && !logical.scope && !e->device) error=-EINVAL;
    else if (logical.port && ntohs(logical.port)<n->unprivileged_port
            && !md_identity_capable_in(identity,n->owner,CAP_NET_BIND_SERVICE)) error=-EACCES;
    else if (logical.port && conflicts(e,&logical,0)) error=-EADDRINUSE;
    struct md_inet_address native=logical;
    struct md_network_port_reservation *reservation=NULL;
    unsigned selected=e->device ? e->device : logical.scope;
    struct md_network_interface *device=selected ? md_network_interface_find(n,selected,NULL) : NULL;
    if (!error && selected && !device) error=-ENODEV;
    if (!error && e->device && logical.scope && e->device!=logical.scope) error=-EINVAL;
    if (!error && md_ip_linklocal(logical.ip) && device && !md_network_interface_has(device,logical.ip)) error=-EADDRNOTAVAIL;
    struct md_network_interface *bound_link=logical.scope ? device : local_interface(n,logical.ip,0);
    int internal=!md_ip_unspecified(logical.ip) && !md_ip_is_loopback(logical.ip)
        && bound_link && bound_link->kind!=MD_LINK_UPLINK;
    if (device && device->kind!=MD_LINK_UPLINK && device->kind!=MD_LINK_LOOPBACK) internal=1;
    int published=external && n->mode==MD_NETWORK_ROUTED && !internal && !md_ip_multicast(logical.ip);
    if (!error) {
        native.ip=md_ip_is_loopback(logical.ip) ? md_ip_loopback(e->family) : md_ip_any(e->family);
        native.scope=0;
        /* bind/getsockname are nonblocking. Publication and conflict checking
         * share one fabric turn; no guest memory or stream IO is under it. */
        unsigned attempts=logical.port ? 1 : n->local_ports[1]-n->local_ports[0]+1;
        int automatic=!logical.port;
        error=-EADDRINUSE;
        for (unsigned i=0;i<attempts;++i) {
            if (automatic) {
                if (n->next_port<n->local_ports[0] || n->next_port>n->local_ports[1]) n->next_port=n->local_ports[0];
                logical.port=htons((uint16_t)n->next_port++);
                if (conflicts(e,&logical,0)) continue;
            }
            if (published) {
                error=md_network_forward_bind(n,e,e->type,&logical,e->v6only,&reservation);
                if (error) { if (automatic && error==-EADDRINUSE) continue; break; }
            }
            native.port=0;
            if (!md_inet_bind(fd,&native,e->family)) { error=0; break; }
            error=-errno;
            md_network_forward_bound(reservation,0); reservation=NULL;
            if (!automatic || error!=-EADDRINUSE) break;
        }
        if (!error) {
            if (md_inet_name(fd,0,&native)) error=-errno;
            else {
                e->local=logical; e->binding=logical.ip; e->native=native; e->bound=1;
                e->published=published; e->publication_requested=external;
                struct md_network_endpoint **at=&n->fabric->endpoints;
                while (*at && *at!=e) at=&(*at)->next;
                if (*at) { *at=e->next; e->next=n->fabric->endpoints; n->fabric->endpoints=e; }
                endpoint_changed(e);
            }
        }
    }
    md_network_forward_bound(reservation,!error);
    pthread_mutex_unlock(&n->fabric->lock); return error;
}
int md_network_endpoint_bind(struct md_fd_object *o,int fd,const struct md_identity *identity,
        const struct sockaddr *a,socklen_t length) {
    return bind_endpoint(o,fd,identity,a,length,1);
}
int md_network_endpoint_listen(struct md_fd_object *o,int fd,int backlog) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    pthread_mutex_lock(&e->space->fabric->lock);
    struct md_network_port_reservation *reservation=NULL;
    int conflict=conflicts(e,&e->local,1);
    struct md_network_interface *link=local_interface(e->space,e->local.ip,0);
    int external=e->space->mode==MD_NETWORK_ROUTED && (md_ip_unspecified(e->local.ip)
        || md_ip_is_loopback(e->local.ip) || (link && link->kind==MD_LINK_UPLINK));
    if (e->device) {
        struct md_network_interface *device=md_network_interface_find(e->space,e->device,NULL);
        external=external && device && (device->kind==MD_LINK_UPLINK || device->kind==MD_LINK_LOOPBACK);
    }
    int error=conflict ? -EADDRINUSE : external ? md_network_forward_bind(e->space,e,e->type,&e->local,e->v6only,&reservation) : 0;
    if (!error) error=listen(fd,backlog) ? -errno : md_network_forward_listen(e->space,e,backlog);
    if (!error) { e->listening=e->publication_requested=1; e->backlog=backlog; e->published=external; }
    md_network_forward_bound(reservation,!error);
    pthread_mutex_unlock(&e->space->fabric->lock); return error;
}
const void *md_network_endpoint_publication(struct md_network_namespace *n,const void *except,int type,
        const struct md_inet_address *address) {
    for (const struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next)
        if (e!=except && e->space==n && e->type==type && e->bound && e->published && md_inet_equal(&e->local,address)) return e;
    return NULL;
}
static uint64_t reuse_score(const struct md_network_endpoint *e,const struct md_filter_packet *p) {
    uint64_t value=e->inode ^ ((uint64_t)p->source_port<<32) ^ p->destination_port;
    for (unsigned i=0;i<md_ip_size(p->source)/4;++i)
        value=(value ^ p->source.words[i] ^ p->destination.words[i])*UINT64_C(0x9e3779b97f4a7c15);
    value^=value>>30; value*=UINT64_C(0xbf58476d1ce4e5b9); value^=value>>27;
    value*=UINT64_C(0x94d049bb133111eb); return value^(value>>31);
}
static int receives(const struct md_network_endpoint *e,const struct md_network_flow_path *path,int type) {
    return e->space->inode==path->destination && e->type==type && e->bound && !e->accepted
        && (type!=SOCK_STREAM || e->listening)
        && e->local.port==path->translated.destination_port && matches(e,path->translated.destination)
        && (!e->connected || type!=SOCK_DGRAM || (md_ip_equal(e->peer.ip,path->translated.source)
            && e->peer.port==path->translated.source_port));
}
int md_network_endpoint_reply(struct md_network_fabric *f,const struct md_network_flow_path *path,
        const struct md_inet_address *native) {
    if (!md_ip_is_loopback(native->ip)) return 0;
    for (struct md_network_endpoint *e=f->endpoints;e;e=e->next)
        if (e->native.port==native->port && receives(e,path,SOCK_DGRAM)
                && !device_admits(e,path->translated.input)) return 1;
    return 0;
}
int md_network_endpoint_resolve(struct md_network_fabric *f,struct md_network_flow_path *path,int type,
        struct md_inet_address *native,const void **endpoint) {
    struct md_inet_address logical={.ip=path->translated.destination,
        .port=path->translated.destination_port};
    *native=logical;
    if (endpoint) *endpoint=NULL;
    if (!path->destination) return 0;
    struct md_network_namespace *n=md_network_space_find(f,path->destination);
    if (!n) return -ENETUNREACH;
    int excluded=0;
    struct md_network_endpoint *best=NULL;
    unsigned specificity=0;
    uint64_t score=0;
    for (struct md_network_endpoint *e=f->endpoints;e;e=e->next)
        if (receives(e,path,type)) {
            if (device_admits(e,path->translated.input)) { excluded=1; continue; }
            unsigned candidate=(e->connected ? 8 : 0)+(!md_ip_unspecified(e->local.ip) ? 4 : 0)
                +(e->device ? 2 : 0)+(e->family==logical.ip.family ? 1 : 0);
            uint64_t hash=e->reuse_port ? reuse_score(e,&path->translated) : 0;
            if (!best || candidate>specificity || (candidate==specificity && e->reuse_port && best->reuse_port && hash>score)) {
                best=e; specificity=candidate; score=hash;
            }
        }
    if (best) {
        if (best->device) path->mediated=1;
        if (endpoint) *endpoint=best;
        *native=best->native; native->ip=md_ip_loopback(logical.ip.family); return 0;
    }
    return !excluded && n->mode==MD_NETWORK_ROUTED && md_ip_is_loopback(logical.ip) ? 0 : -ECONNREFUSED;
}
int md_network_endpoint_destination(struct md_fd_object *o,const struct sockaddr *a,socklen_t length,
        struct sockaddr_storage *out,socklen_t *out_length) {
    return md_network_endpoint_destination_info(o,a,length,NULL,out,out_length);
}
int md_network_endpoint_destination_info(struct md_fd_object *o,const struct sockaddr *a,socklen_t length,
        struct md_network_packet_info *info,struct sockaddr_storage *out,socklen_t *out_length) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    struct md_inet_address logical;
    int error=address(e,a,length,&logical); if (error) return error;
    struct md_network_namespace *n=e->space;
    pthread_mutex_lock(&n->fabric->lock);
    struct md_inet_address native=logical;
    struct md_inet_address local=e->local;
    local.ip=e->binding;
    int multicast=md_ip_multicast(logical.ip);
    if (multicast && md_ip_multicast(local.ip)) local.ip=md_ip_any(logical.ip.family);
    unsigned selected=e->device ? e->device : md_ip_linklocal(logical.ip) ? logical.scope : 0;
    if (multicast && !selected) {
        if (logical.scope) selected=logical.scope;
        else error=md_network_multicast_output(n,&e->multicast[logical.ip.family==AF_INET6],&selected);
    }
    if (e->device && logical.scope && e->device!=logical.scope) error=-EINVAL;
    struct md_network_interface *device=selected ? md_network_interface_find(n,selected,NULL) : NULL;
    if (selected && !device) error=-ENODEV;
    if (md_ip_linklocal(logical.ip) && !selected) error=-EINVAL;
    if (!error && device && !(device->info.flags&IFF_UP)) error=-ENETUNREACH;
    if (info) {
        struct md_network_interface *link=info->interface ? md_network_interface_find(n,info->interface,NULL) : NULL;
        if (info->interface && !link) error=-ENODEV;
        else if (selected && info->interface && selected!=info->interface) error=-EINVAL;
        else if (!md_ip_unspecified(info->source) && !local_interface(n,info->source,0)) error=-EADDRNOTAVAIL;
        else if (link && !md_ip_unspecified(info->source) && local_interface(n,info->source,0)!=link) error=-EINVAL;
        if (!error && !md_ip_unspecified(info->source)) local.ip=info->source;
    }
    if (!error && info && info->interface) selected=info->interface;
    if (!error && selected) md_network_policy_route(n,local.ip,logical.ip,e->mark,selected,NULL,&error);
    if (!error && multicast && e->type==SOCK_DGRAM) {
        struct md_filter_packet packet={.source=local.ip,.source_port=local.port,
            .destination=logical.ip,.destination_port=logical.port,.protocol=e->protocol,.state=8,.mark=e->mark};
        error=md_network_forward_group(n,e,&e->native,&packet,selected,info && info->hops_set ? info->hops : -1,&native);
    } else if (!error && !md_network_forward_destination(n,e,e->type,&local,&logical,&native)) {
        struct md_filter_packet packet={.source=local.ip,.source_port=local.port,
            .destination=logical.ip,.destination_port=logical.port,.protocol=e->protocol,.state=8,.mark=e->mark};
        struct md_network_flow_path path;
        error=md_network_flow_route(n,&packet,0,selected,&path);
        if (!error) error=md_network_endpoint_resolve(n->fabric,&path,e->type,&native,NULL);
        /* A private datagram's queued source must outlive the sending socket.
         * The existing bounded flow owner retains both logical directions. */
        if (!error && e->type==SOCK_DGRAM && path.destination) path.mediated=1;
        if (!error && e->device) path.mediated=1;
        if (!error && path.mediated) {
            struct md_inet_address target=native;
            error=md_network_forward_route(n,e,e->type,&e->native,&target,&path,&native);
        } else if (!error) error=direct_path(e,&path);
    }
    if (!error) {
        error=md_inet_encode(&native,e->family,out,out_length);
        if (info) *info=(struct md_network_packet_info){.source=e->native.ip,.destination=md_ip_any(e->family)};
    }
    pthread_mutex_unlock(&n->fabric->lock); return error;
}
static struct md_ip_address source_address(struct md_network_namespace *n,struct md_ip_address target) {
    struct md_network_route_entry route;
    struct md_network_interface *l=md_network_route_find(n,target,&route);
    return md_network_route_source(n,l,&route,target);
}
int md_network_endpoint_autobind(struct md_fd_object *o,int fd,const struct md_identity *identity,
        const struct sockaddr *a,socklen_t length,unsigned output) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    struct md_inet_address target;
    int error=address(e,a,length,&target); if (error) return error;
    pthread_mutex_lock(&e->space->fabric->lock);
    if (!output && md_ip_multicast(target.ip) && !e->device && !target.scope)
        error=md_network_multicast_output(e->space,&e->multicast[target.ip.family==AF_INET6],&output);
    if (error) { pthread_mutex_unlock(&e->space->fabric->lock); return error; }
    if (output && ((e->device && output!=e->device) || (target.scope && output!=target.scope))) {
        pthread_mutex_unlock(&e->space->fabric->lock); return -EINVAL;
    }
    int bound=e->bound;
    struct md_network_route_entry entry={0};
    struct md_network_interface *route=md_network_policy_route(e->space,e->binding,target.ip,e->mark,
        output ? output : e->device ? e->device : target.scope,&entry,NULL);
    e->source=md_network_route_source(e->space,route,&entry,target.ip);
    if (e->family==AF_INET6 && route) error=md_network_transport_apply(&e->transport,fd,e->type,
        md_network_route_transport(route,&entry));
    pthread_mutex_unlock(&e->space->fabric->lock);
    if (error) return error;
    if (!bound) {
        struct md_inet_address any={.ip=md_ip_any(e->family)};
        struct sockaddr_storage native; socklen_t size;
        error=md_inet_encode(&any,e->family,&native,&size);
        if (!error) error=bind_endpoint(o,fd,identity,(void *)&native,size,0);
        /* Another operation on the same OFD may have completed autobind. */
        if (error==-EINVAL) {
            pthread_mutex_lock(&e->space->fabric->lock); bound=e->bound; pthread_mutex_unlock(&e->space->fabric->lock);
            if (bound) error=0;
        }
    }
    return error;
}
int md_network_endpoint_sockopt(struct md_fd_object *o,int fd,const struct md_identity *identity,int writing,unsigned level,unsigned option,
        void *buffer,socklen_t *size) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    if ((int)*size<0) return -EINVAL;
    if (level==SOL_SOCKET && option==SO_BINDTOIFINDEX && writing) {
        int value;
        if (*size<sizeof(value)) return -EINVAL;
        memcpy(&value,buffer,sizeof(value));
        return device_set(o,identity,NULL,value);
    }
    pthread_mutex_lock(&e->space->fabric->lock);
    int error=0;
    if (md_network_multicast_option(level,option)) {
        error=e->type!=SOCK_DGRAM ? -EOPNOTSUPP : md_network_multicast_option_io(e->space,
            &e->multicast[level==IPPROTO_IPV6],e->family,e->device,writing,level,option,buffer,size);
    } else if (level==SOL_SOCKET && option==SO_MARK) {
        if (writing) {
            if (*size<sizeof(e->mark)) error=-EINVAL;
            else if (!md_identity_capable_in(identity,e->space->owner,CAP_NET_ADMIN)) error=-EPERM;
            else {
                uint32_t mark; memcpy(&mark,buffer,sizeof(mark));
                if (mark!=e->mark && (e->connected || e->direct || md_network_forward_endpoint_active(e->space,e))) error=-EBUSY;
                else e->mark=mark;
            }
        } else { if (*size>sizeof(e->mark)) *size=sizeof(e->mark); memcpy(buffer,&e->mark,*size); }
    } else if (level==SOL_SOCKET && (option==SO_BINDTOIFINDEX || option==SO_PRIORITY)) {
        int value=option==SO_PRIORITY ? e->priority : (int)e->device;
        if (writing) {
            if (*size<sizeof(value)) error=-EINVAL;
            else {
                memcpy(&value,buffer,sizeof(value));
                if ((value<0 || value>6) && !md_identity_capable_in(identity,e->space->owner,CAP_NET_ADMIN)) error=-EPERM;
                else e->priority=value;
            }
        } else {
            if (*size>sizeof(value)) *size=sizeof(value);
            memcpy(buffer,&value,*size);
        }
    } else if (level==SOL_SOCKET && option==SO_ERROR && !writing) {
        int value=0; socklen_t length=sizeof(value);
        if (getsockopt(fd,SOL_SOCKET,SO_ERROR,&value,&length)) error=-errno;
        if (!error) {
            md_network_connection_error(e->connection,&value);
            if (*size>sizeof(value)) *size=sizeof(value);
            memcpy(buffer,&value,*size);
        }
    } else if (level==SOL_SOCKET && (option==SO_REUSEADDR || option==SO_REUSEPORT)) {
        int *setting=option==SO_REUSEADDR ? &e->reuse_address : &e->reuse_port;
        if (writing) {
            if (*size<sizeof(int)) error=-EINVAL;
            else {
                int value; memcpy(&value,buffer,sizeof(value));
                error=setsockopt(fd,SOL_SOCKET,option,&value,sizeof(value)) ? -errno : 0;
                if (!error) { *setting=!!value; endpoint_changed(e); }
            }
        } else { if (*size>sizeof(int)) *size=sizeof(int); memcpy(buffer,setting,*size); }
    } else if (writing) {
        int value;
        if (*size<sizeof(value)) error=-EINVAL;
        else {
            memcpy(&value,buffer,sizeof(value));
            if (e->family==AF_INET6) {
                int current=level==IPPROTO_TCP ? e->transport.user_mss
                    : option==IPV6_MTU ? e->transport.user_mtu : e->transport.user_hops;
                /* A relayed connection has two native legs. Until changing
                 * both is supported, do not acknowledge only the local leg. */
                error=current!=value && md_network_forward_endpoint_active(e->space,e) ? -EBUSY
                    : md_network_transport_set(&e->transport,fd,e->type,level,option,value);
            } else error=setsockopt(fd,level,option,&value,sizeof(value)) ? -errno : 0;
        }
    } else {
        int value; socklen_t length=sizeof(value);
        if (getsockopt(fd,level,option,&value,&length)) error=-errno;
        if (!error) {
            if (level==IPPROTO_IPV6 && option==IPV6_MTU && e->transport.route.mtu
                    && e->transport.route.mtu<(unsigned)value) value=(int)e->transport.route.mtu;
            if (*size>sizeof(value)) *size=sizeof(value);
            memcpy(buffer,&value,*size);
        }
    }
    pthread_mutex_unlock(&e->space->fabric->lock);
    return error;
}
void md_network_endpoint_transport(const void *object,struct md_network_transport *out) {
    *out=(struct md_network_transport)MD_NETWORK_TRANSPORT_INITIALIZER;
    if (!md_network_endpoint_is(object)) return;
    const struct md_network_endpoint *e=object;
    out->user_mtu=e->transport.user_mtu;
    out->user_hops=e->transport.user_hops;
    out->user_mss=e->transport.user_mss;
}
int md_network_endpoint_connect_begin(const void *o) {
    return md_network_endpoint_is(o) ? md_network_connection_begin(((const struct md_network_endpoint *)o)->connection) : 0;
}
void md_network_endpoint_connect_complete(const void *o,int result) {
    if (md_network_endpoint_is(o)) md_network_connection_complete(((const struct md_network_endpoint *)o)->connection,result);
}
int md_network_endpoint_connect_result(struct md_fd_object *o,int *wait) {
    return md_network_connection_result(md_network_endpoint_is(o) ? ((struct md_network_endpoint *)o)->connection : NULL,wait);
}
void md_network_endpoint_connect_reported(struct md_fd_object *o,int result) {
    if (result>=0 || result==-EINPROGRESS || result==-EALREADY || !md_network_endpoint_is(o)) return;
    int error;
    md_network_connection_error(((struct md_network_endpoint *)o)->connection,&error);
}
int md_network_endpoint_connected(struct md_fd_object *o,int fd,const struct sockaddr *a,socklen_t length) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    struct md_inet_address peer={.ip=md_ip_any(e->family)},native;
    int disconnect=length>=sizeof(sa_family_t) && a->sa_family==AF_UNSPEC;
    int error=disconnect ? 0 : address(e,a,length,&peer); if (error) return error;
    if (md_inet_name(fd,0,&native)) return -errno;
    pthread_mutex_lock(&e->space->fabric->lock);
    e->peer=peer; e->connected=!disconnect; e->native=native;
    endpoint_changed(e);
    if (disconnect) {
        md_network_connection_reset(e->connection);
        md_network_forward_endpoint_disconnected(e->space,e,!native.port);
        while (e->direct) { struct direct_path *p=e->direct; e->direct=p->next; free(p); }
        e->direct_count=0;
        e->source=md_ip_any(e->family);
        e->local.ip=e->binding;
        if (!native.port) { e->bound=0; e->local.port=0; e->published=e->publication_requested=0; }
    } else {
        if (!e->bound) { e->local=native; e->local.ip=e->binding; e->bound=1; }
        /* Like Linux, an assigned UDP source survives reconnect until an
         * AF_UNSPEC disconnect resets it to the original binding. */
        if (md_ip_unspecified(e->local.ip)) e->local.ip=source_address(e->space,peer.ip);
    }
    pthread_mutex_unlock(&e->space->fabric->lock); return 0;
}
int md_network_endpoint_name(struct md_fd_object *o,int fd,int peer,struct sockaddr_storage *out,socklen_t *size) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    struct md_inet_address native;
    if (md_inet_name(fd,peer,&native)) return -errno;
    pthread_mutex_lock(&e->space->fabric->lock);
    struct md_inet_address result=peer ? e->peer : e->local;
    if (peer && md_network_connection_result(e->connection,NULL)) {
        pthread_mutex_unlock(&e->space->fabric->lock); return -ENOTCONN;
    }
    if (peer && !e->connected) result=native;
    else if (!peer && !e->bound) result=native;
    int error=md_inet_encode(&result,e->family,out,size);
    pthread_mutex_unlock(&e->space->fabric->lock); return error;
}
int md_network_endpoint_source(struct md_fd_object *o,struct sockaddr_storage *address,socklen_t *length) {
    return md_network_endpoint_source_info(o,address,length,NULL);
}
int md_network_endpoint_source_info(struct md_fd_object *o,struct sockaddr_storage *address,socklen_t *length,
        struct md_network_packet_info *info) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *e=(void *)o;
    struct md_inet_address decoded,*a=&decoded;
    int error=md_inet_decode((void *)address,*length,a);
    if (error) return error;
    /* Logical inter-namespace traffic is transported over native loopback.
     * An internet peer sharing a local ephemeral port is still that peer. */
    pthread_mutex_lock(&e->space->fabric->lock);
    struct md_ip_address local=e->local.ip;
    unsigned input=0;
    struct md_network_packet_info provenance={0};
    int forwarded=md_ip_is_loopback(a->ip) && md_network_forward_source(e->space,e->type,a,&provenance);
    if (forwarded) { local=provenance.destination; input=provenance.interface; }
    if (md_ip_is_loopback(a->ip) && !forwarded)
      for (struct md_network_endpoint *other=e->space->fabric->endpoints;other;other=other->next)
        if (other->bound && !other->accepted && other->type==e->type && other->native.port==a->port
                && (other->family==a->ip.family || (other->family==AF_INET6 && !other->v6only))
                && (md_ip_unspecified(other->native.ip) || md_ip_equal(other->native.ip,a->ip))) {
            *a=other->local;
            if (md_ip_unspecified(a->ip)) a->ip=!md_ip_unspecified(other->source) ? other->source
                : source_address(other->space,e->local.ip);
            if (md_ip_unspecified(local)) for (struct direct_path *p=other->direct;p;p=p->next)
                if (p->path.destination==e->space->inode
                        && p->path.translated.destination_port==e->local.port) {
                    local=p->path.translated.destination; break;
                }
            break;
        }
    if (md_ip_unspecified(local)) local=source_address(e->space,a->ip);
    struct md_network_interface *link=input ? md_network_interface_find(e->space,input,NULL)
        : e->local.scope ? md_network_interface_find(e->space,e->local.scope,NULL)
        : local_interface(e->space,local,0);
    if (md_ip_linklocal(a->ip)) a->scope=link ? link->info.index : 0;
    if (info) {
        *info=(struct md_network_packet_info){.interface=link ? link->info.index : 0,
            .source=md_ip_multicast(local) ? md_network_interface_source(link,a->ip) : local,.destination=local,
            .hops=provenance.hops,.hops_set=provenance.hops_set};
    }
    error=md_inet_encode(a,e->family,address,length);
    pthread_mutex_unlock(&e->space->fabric->lock); return error;
}
int md_network_endpoint_accept(struct md_fd_object *o,int fd,struct md_fd_object **out) {
    if (!md_network_endpoint_is(o)) return -EBADF;
    struct md_network_endpoint *parent=(void *)o;
    struct sockaddr_storage peer; socklen_t length=sizeof(peer);
    if (getpeername(fd,(void *)&peer,&length)) return -errno;
    struct stat st;
    if (fstat(fd,&st)) return -errno;
    struct md_network_packet_info info;
    int error=md_network_endpoint_source_info(o,&peer,&length,&info); if (error) return error;
    struct md_network_endpoint *e=create(parent->space,parent->family,parent->type,parent->protocol);
    if (!e) return -ENOMEM;
    pthread_mutex_lock(&parent->space->fabric->lock);
    e->local=parent->local; e->native=parent->native; e->bound=e->connected=e->accepted=1;
    if (md_ip_unspecified(e->local.ip)) e->local.ip=info.destination;
    if (md_ip_linklocal(e->local.ip)) e->local.scope=info.interface;
    e->device=parent->device; e->v6only=parent->v6only; e->binding=e->local.ip;
    e->transport=parent->transport; e->priority=parent->priority; e->mark=parent->mark;
    e->inode=st.st_ino; e->uid=parent->uid;
    error=md_inet_decode((void *)&peer,length,&e->peer);
    if (!error && e->family==AF_INET6) {
        struct md_network_route_entry route={0};
        struct md_network_interface *link=md_network_policy_route(e->space,e->local.ip,e->peer.ip,e->mark,e->device,&route,NULL);
        if (link) error=md_network_transport_apply(&e->transport,fd,e->type,md_network_route_transport(link,&route));
    }
    for (struct md_network_endpoint *client=parent->space->fabric->endpoints;client && !error;client=client->next)
        if (client!=e && client->type==SOCK_STREAM)
            for (struct direct_path *p=client->direct;p && !error;p=p->next)
                if (p->path.destination==parent->space->inode
                        && md_ip_equal(p->path.original.source,e->peer.ip) && p->path.original.source_port==e->peer.port
                        && p->path.translated.destination_port==e->local.port) error=direct_path(e,&p->path);
    pthread_mutex_unlock(&parent->space->fabric->lock);
    if (error) { md_fd_object_release(&e->object); return error; }
    *out=&e->object; return 0;
}
static int inode_order(const void *left,const void *right) {
    uint64_t a=((const struct md_network_endpoint_info *)left)->inode;
    uint64_t b=((const struct md_network_endpoint_info *)right)->inode;
    return (a>b)-(a<b);
}
int md_network_endpoint_snapshot(struct md_network_namespace *n,int protocol,
        struct md_network_endpoint_info **out,size_t *count) {
    *out=NULL; *count=0;
    if (!md_network_namespace_managed(n)) return -EXDEV;
    pthread_mutex_lock(&n->fabric->lock);
    size_t size=0;
    for (struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next)
        if (e->space==n && e->protocol==protocol && e->inode && e->bound) ++size;
    struct md_network_endpoint_info *items=size ? calloc(size,sizeof(*items)) : NULL;
    if (size && !items) { pthread_mutex_unlock(&n->fabric->lock); return -ENOMEM; }
    for (struct md_network_endpoint *e=n->fabric->endpoints;e;e=e->next)
        if (e->space==n && e->protocol==protocol && e->inode && e->bound)
            items[(*count)++]=(struct md_network_endpoint_info){
                .inode=e->inode,.uid=e->uid,.local=e->local,.peer=e->peer};
    pthread_mutex_unlock(&n->fabric->lock);
    if (size) qsort(items,size,sizeof(*items),inode_order);
    *out=items; return 0;
}
