#define _GNU_SOURCE
#include "network_raw6.h"
#include "network_raw4.h"
#include "network_internal.h"
#include "network_ioctl.h"
#include "network_route.h"
#include "network_generic.h"
#include "network_nft.h"
#include "network_endpoint.h"
#include "network_forward.h"
#include "network_packet.h"
#include "network_stack.h"
#include "socket_filter.h"
#include "network_ethernet.h"
#include "guest_identity.h"
#include "fd_selector.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/sockios.h>
#include <net/if.h>
#include <linux/if_arp.h>
#include <netinet/in.h>
#include <pthread.h>

static int scoped_ipv6(enum md_network_parameter kind) {
    return kind==MD_NET_IPV6_FORWARD || kind==MD_NET_IPV6_DISABLE
        || (kind>=MD_NET_IPV6_DAD && kind<=MD_NET_IPV6_PRIVACY);
}

int md_network_parameter_scope(struct md_network_namespace *n,enum md_network_parameter kind,
        const char *name,uint64_t *out) {
    if (!md_network_namespace_managed(n)) return -EXDEV;
    *out=0;
    if ((kind<MD_NET_ROUTE_LOCALNET || kind>MD_NET_ARP_NOTIFY)
            && !scoped_ipv6(kind)) return name && *name ? -EINVAL : 0;
    if (!name || !*name) return -EINVAL;
    if (!strcmp(name,"all")) { *out=MD_NET_SCOPE_ALL; return 0; }
    if (!strcmp(name,"default")) { *out=MD_NET_SCOPE_DEFAULT; return 0; }
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *link=md_network_interface_find(n,0,name);
    if (link) *out=link->policy_id;
    pthread_mutex_unlock(&n->fabric->lock);
    return *out ? 0 : -ENOENT;
}
static uint32_t *scoped_value(struct md_network_namespace *n,enum md_network_parameter kind,uint64_t scope) {
    if (scoped_ipv6(kind)) {
        struct md_network_ipv6_policy *policy=NULL;
        if (scope==MD_NET_SCOPE_ALL) policy=&n->all6;
        else if (scope==MD_NET_SCOPE_DEFAULT) policy=&n->defaults6;
        else for (unsigned i=0;i<n->count;++i)
            if (n->links[i].policy_id==scope) { policy=&n->links[i].ipv6; break; }
        if (!policy) return NULL;
        switch (kind) {
        case MD_NET_IPV6_FORWARD: return &policy->forwarding;
        case MD_NET_IPV6_DISABLE: return &policy->disabled;
        case MD_NET_IPV6_DAD: return &policy->accept_dad;
        case MD_NET_IPV6_RA: return &policy->accept_ra;
        case MD_NET_IPV6_AUTOCONF: return &policy->autoconf;
        case MD_NET_IPV6_MTU: return &policy->mtu;
        case MD_NET_IPV6_HOPS: return &policy->hop_limit;
        case MD_NET_IPV6_ADDRGEN: return &policy->addr_gen_mode;
        case MD_NET_IPV6_PRIVACY: return &policy->use_tempaddr;
        default: return NULL;
        }
    }
    struct md_network_ipv4_policy *policy=NULL;
    if (scope==MD_NET_SCOPE_ALL) policy=&n->all;
    else if (scope==MD_NET_SCOPE_DEFAULT) policy=&n->defaults;
    else for (unsigned i=0;i<n->count;++i)
        if (n->links[i].policy_id==scope) { policy=&n->links[i].policy; break; }
    return !policy ? NULL : kind==MD_NET_ROUTE_LOCALNET ? &policy->route_localnet
        : kind==MD_NET_RP_FILTER ? &policy->rp_filter
        : kind==MD_NET_SRC_VALID_MARK ? &policy->src_valid_mark : &policy->arp_notify;
}
int md_network_parameter_read(struct md_network_namespace *n,enum md_network_parameter kind,uint64_t scope,uint32_t out[2]) {
    if (!md_network_namespace_managed(n)) return -EXDEV;
    pthread_mutex_lock(&n->fabric->lock);
    int error=0;
    switch (kind) {
    case MD_NET_UNPRIVILEGED_PORT: out[0]=n->unprivileged_port; out[1]=0; break;
    case MD_NET_PING_GROUPS: memcpy(out,n->ping_groups,sizeof(n->ping_groups)); break;
    case MD_NET_LOCAL_PORTS: memcpy(out,n->local_ports,sizeof(n->local_ports)); break;
    case MD_NET_IPV4_FORWARD: out[0]=n->ipv4_forward; out[1]=0; break;
    case MD_NET_BRIDGE_FILTER: case MD_NET_BRIDGE_FILTER6: out[0]=1; out[1]=0; break;
    case MD_NET_IPV6_BINDONLY: out[0]=n->bindv6only; out[1]=0; break;
    case MD_NET_TCP_KEEPIDLE: case MD_NET_TCP_KEEPINTVL: case MD_NET_TCP_KEEPCNT:
        out[0]=n->tcp_keepalive[kind-MD_NET_TCP_KEEPIDLE]; out[1]=0; break;
    case MD_NET_IPV6_FORWARD: case MD_NET_IPV6_DISABLE: case MD_NET_IPV6_DAD: case MD_NET_IPV6_RA:
    case MD_NET_IPV6_AUTOCONF:
    case MD_NET_IPV6_MTU: case MD_NET_IPV6_HOPS: case MD_NET_IPV6_ADDRGEN: case MD_NET_IPV6_PRIVACY:
    case MD_NET_ROUTE_LOCALNET: case MD_NET_RP_FILTER: case MD_NET_SRC_VALID_MARK: case MD_NET_ARP_NOTIFY: {
        uint32_t *value=scoped_value(n,kind,scope);
        if (!value) error=-ENODEV;
        else { out[0]=*value; out[1]=0; }
        break;
    }
    default: error=-EINVAL;
    }
    pthread_mutex_unlock(&n->fabric->lock); return error;
}
int md_network_parameter_write(struct md_network_namespace *n,enum md_network_parameter kind,uint64_t scope,const uint32_t in[2]) {
    if (!md_network_namespace_managed(n)) return -EXDEV;
    pthread_mutex_lock(&n->fabric->lock);
    int error=0;
    switch (kind) {
    case MD_NET_UNPRIVILEGED_PORT:
        if (in[0]>65535 || in[0]>n->local_ports[0]) error=-EINVAL;
        else n->unprivileged_port=in[0];
        break;
    case MD_NET_PING_GROUPS:
        if (in[0]==UINT32_MAX || in[1]==UINT32_MAX) error=-EINVAL;
        else if (in[0]>in[1]) { n->ping_groups[0]=1; n->ping_groups[1]=0; }
        else memcpy(n->ping_groups,in,sizeof(n->ping_groups));
        break;
    case MD_NET_LOCAL_PORTS:
        if (!in[0] || in[0]>in[1] || in[1]>65535 || in[0]<n->unprivileged_port) error=-EINVAL;
        else memcpy(n->local_ports,in,sizeof(n->local_ports));
        break;
    case MD_NET_IPV4_FORWARD:
        if (in[0]>1) error=-EINVAL;
        else n->ipv4_forward=in[0];
        break;
    case MD_NET_BRIDGE_FILTER: case MD_NET_BRIDGE_FILTER6:
        /* The logical bridge always traverses IP hooks. Disabling them is
         * not implemented; do not acknowledge an ineffective policy change. */
        if (in[0]>1) error=-EINVAL;
        else if (!in[0]) error=-EOPNOTSUPP;
        break;
    case MD_NET_IPV6_BINDONLY:
        if (in[0]>1) error=-EINVAL;
        else n->bindv6only=in[0];
        break;
    case MD_NET_IPV6_MTU: case MD_NET_IPV6_HOPS: case MD_NET_IPV6_ADDRGEN: case MD_NET_IPV6_PRIVACY: {
        uint32_t *value=scoped_value(n,kind,scope);
        struct md_network_interface *link=NULL;
        for (unsigned i=0;i<n->count;++i) if (n->links[i].policy_id==scope) link=&n->links[i];
        if (!value) error=-ENODEV;
        else if (kind==MD_NET_IPV6_MTU && (in[0]<1280 || in[0]>(link ? link->info.mtu : 65536U))) error=-EINVAL;
        else if (kind==MD_NET_IPV6_HOPS && (in[0]<1 || in[0]>255)) error=-EINVAL;
        else if (kind==MD_NET_IPV6_ADDRGEN && in[0]>3) error=-EINVAL;
        else if (kind==MD_NET_IPV6_ADDRGEN && in[0]>1) error=-EOPNOTSUPP;
        else if (kind==MD_NET_IPV6_PRIVACY && in[0]>INT32_MAX) error=-EINVAL;
        else if (kind==MD_NET_IPV6_PRIVACY && scope!=MD_NET_SCOPE_DEFAULT)
            error=md_network_route_privacy(n,scope,in[0]);
        else {
            uint32_t previous=*value;
            *value=in[0];
            if (kind==MD_NET_IPV6_MTU || kind==MD_NET_IPV6_HOPS)
                error=md_network_endpoint_transport_update(n);
            if (error) *value=previous;
        }
        break;
    }
    case MD_NET_IPV6_FORWARD: case MD_NET_IPV6_DISABLE: case MD_NET_IPV6_DAD: case MD_NET_IPV6_RA:
    case MD_NET_IPV6_AUTOCONF: {
        uint32_t *value=scoped_value(n,kind,scope);
        if (!value) error=-ENODEV;
        else if (in[0]>(kind==MD_NET_IPV6_DAD || kind==MD_NET_IPV6_RA ? 2U : 1U)) error=-EINVAL;
        /* The socket fabric has configured routes, not an ICMPv6 router-
         * advertisement engine. Expose its disabled state without pretending
         * that accepting advertisements or SLAAC has been enabled. */
        else if ((kind==MD_NET_IPV6_RA || kind==MD_NET_IPV6_AUTOCONF) && in[0]) error=-EOPNOTSUPP;
        else if (kind==MD_NET_IPV6_DISABLE && scope!=MD_NET_SCOPE_DEFAULT)
            error=md_network_route_disable_ipv6(n,scope,in[0]);
        else {
            *value=in[0];
            if (scope==MD_NET_SCOPE_ALL && kind==MD_NET_IPV6_FORWARD) {
                n->defaults6.forwarding=in[0];
                for (unsigned i=0;i<n->count;++i) n->links[i].ipv6.forwarding=in[0];
            }
        }
        break;
    }
    case MD_NET_TCP_KEEPIDLE: case MD_NET_TCP_KEEPINTVL: case MD_NET_TCP_KEEPCNT: {
        uint32_t *value=&n->tcp_keepalive[kind-MD_NET_TCP_KEEPIDLE];
        /* Kernel socket overrides have a narrower domain than these sysctls.
         * Reject unsupported defaults before publication, not at socket(). */
        if (in[0]>(kind==MD_NET_TCP_KEEPCNT ? UINT8_MAX : INT32_MAX)) error=-EINVAL;
        else if (!in[0] || in[0]>(kind==MD_NET_TCP_KEEPCNT ? 127U : 32767U)) error=-EOPNOTSUPP;
        /* Updating active sockets needs native OFD custody. Retaining their
         * data FDs would postpone close/EOF; never do that for metadata. */
        else if (*value!=in[0] && !md_network_endpoint_tcp_idle(n)) error=-EBUSY;
        else *value=in[0];
        break;
    }
    case MD_NET_ROUTE_LOCALNET: case MD_NET_RP_FILTER: case MD_NET_SRC_VALID_MARK: case MD_NET_ARP_NOTIFY: {
        uint32_t *value=scoped_value(n,kind,scope);
        if (!value) error=-ENODEV;
        else if (in[0]>(kind==MD_NET_RP_FILTER ? 2U : 1U)) error=-EINVAL;
        else *value=in[0];
        break;
    }
    default: error=-EINVAL;
    }
    if (!error && ((kind>=MD_NET_IPV4_FORWARD && kind<=MD_NET_BRIDGE_FILTER)
            || kind==MD_NET_IPV6_FORWARD || kind==MD_NET_IPV6_DISABLE))
        md_network_forward_changed(n->fabric);
    pthread_mutex_unlock(&n->fabric->lock); return error;
}
struct network_socket {
    struct md_fd_object object;
    struct md_network_namespace *space;
    int peer, receiver, family, type, protocol, bound;
    struct md_socket_filter filter;
    struct md_network_ethernet ethernet;
    struct md_network_raw6 raw6;
    struct md_network_raw4 raw4;
    int capped_ack;
    int packet_info, pass_credentials, extended_ack, strict_check;
    struct md_network_listener listener;
};
static void socket_destroy(struct md_fd_object *object) {
    struct network_socket *socket=(void *)object;
    if (socket->family==AF_NETLINK) {
        pthread_mutex_lock(&socket->space->fabric->lock);
        struct md_network_listener **at=&socket->space->listeners;
        while (*at && *at!=&socket->listener) at=&(*at)->next;
        if (*at) *at=(*at)->next;
        pthread_mutex_unlock(&socket->space->fabric->lock);
    }
    if (socket->family==AF_PACKET) {
        pthread_mutex_lock(&socket->space->fabric->lock);
        md_network_ethernet_detach(&socket->ethernet);
        pthread_mutex_unlock(&socket->space->fabric->lock);
    }
    if (socket->raw6.space) {
        pthread_mutex_lock(&socket->space->fabric->lock);
        md_network_raw6_detach(&socket->raw6);
        pthread_mutex_unlock(&socket->space->fabric->lock);
    }
    if (socket->raw4.space) {
        pthread_mutex_lock(&socket->space->fabric->lock);
        md_network_raw4_detach(&socket->raw4);
        pthread_mutex_unlock(&socket->space->fabric->lock);
    }
    if (socket->peer>=0) close(socket->peer);
    if (socket->receiver>=0) close(socket->receiver);
    md_socket_filter_clear(&socket->filter);
    md_network_namespace_release(socket->space); free(socket);
}
static const struct md_fd_object_type socket_type={.destroy=socket_destroy};
static const struct md_fd_object_type control_type={.destroy=socket_destroy,
    .selectors=1U<<MD_FD_SELECTOR_PROC};
struct md_fd_object *md_network_socket_new(struct md_network_namespace *n) {
    if (!md_network_namespace_managed(n)) { errno=EINVAL; return NULL; }
    struct network_socket *s=calloc(1,sizeof(*s)); if (!s) return NULL;
    md_fd_object_init(&s->object,&socket_type); s->space=md_network_namespace_retain(n); s->peer=s->receiver=-1;
    return &s->object;
}
int md_network_socket_offline(const struct md_fd_object *object) {
    if (!object) return 0;
    if (object->type==&control_type) return 1;
    if (object->type!=&socket_type) return 0;
    const struct network_socket *socket=(const void *)object;
    return !md_network_endpoint_enabled(socket->space);
}
int md_network_socket_control(const struct md_fd_object *object) {
    return object && object->type==&control_type;
}
struct md_network_namespace *md_network_socket_namespace(const struct md_fd_object *object) {
    if (object && (object->type==&control_type || object->type==&socket_type))
        return ((const struct network_socket *)object)->space;
    return md_network_endpoint_namespace(object);
}
int md_network_interface_ioctl(unsigned long command) {
    return md_network_arp_ioctl(command) || command==SIOCDEVPRIVATE || command==SIOCGIFCONF || command==SIOCGIFNAME || command==SIOCGIFINDEX || command==SIOCGIFFLAGS || command==SIOCGIFMTU
        || command==SIOCGIFHWADDR || command==SIOCSIFHWADDR || command==SIOCBRADDIF || command==SIOCGIFTXQLEN
        || command==SIOCSIFFLAGS || command==SIOCSIFMTU || md_network_address_ioctl(command) || md_network_family_ioctl(command);
}
int md_network_netlink_destination(const struct md_network_send *destination,uint32_t *groups) {
    *groups=0;
    if (destination && destination->control_size) return -EOPNOTSUPP;
    if (!destination || !destination->size) return 0;
    if (destination->size<sizeof(struct sockaddr_nl) || destination->size>sizeof(destination->value)) return -EINVAL;
    const struct sockaddr_nl *address=(const void *)&destination->value;
    if (address->nl_family!=AF_NETLINK || address->nl_pid) return -EOPNOTSUPP;
    *groups=address->nl_groups; return 0;
}
int md_network_socket_open(struct md_network_namespace *n, const struct md_identity *identity, int family, int type, int protocol,
        struct md_fd_object **out, int *fd) {
    if (!md_network_namespace_managed(n)) return -EINVAL;
    if (type&~(SOCK_CLOEXEC|SOCK_NONBLOCK|15)) return -EINVAL;
    if (family==AF_UNIX && md_network_endpoint_enabled(n)) {
        int native=socket(family,type|SOCK_CLOEXEC,protocol);
        if (native<0) return -errno;
        struct md_fd_object *object=md_network_socket_new(n);
        if (!object) { int error=-errno; close(native); return error; }
        *out=object; *fd=native; return 0;
    }
    int raw6=family==AF_INET6 && (type&15)==SOCK_RAW;
    int raw4=family==AF_INET && (type&15)==SOCK_RAW;
    if ((family==AF_INET || family==AF_INET6) && !raw6 && !raw4 && md_network_endpoint_enabled(n))
        return md_network_endpoint_open(n,identity->uid.fs,family,type,protocol,out,fd);
    int base=type&15;
    if (raw6 || raw4) {
        if (!md_identity_capable_in(identity,n->owner,CAP_NET_RAW)) return -EPERM;
        if (raw4 && (protocol<=0 || protocol>255)) return -EPROTONOSUPPORT;
        if (raw6 && protocol!=IPPROTO_ICMPV6 && protocol!=IPPROTO_UDP) return -EPROTONOSUPPORT;
    } else if (family==AF_NETLINK) {
        if (protocol!=NETLINK_ROUTE && protocol!=NETLINK_GENERIC && protocol!=NETLINK_NETFILTER) return -EPROTONOSUPPORT;
        if (base!=SOCK_RAW && base!=SOCK_DGRAM) return -ESOCKTNOSUPPORT;
    } else if (family==AF_PACKET) {
        if (!md_identity_capable_in(identity,n->owner,CAP_NET_RAW)) return -EPERM;
        if (base!=SOCK_RAW && base!=SOCK_DGRAM) return -ESOCKTNOSUPPORT;
        if (protocol<0 || protocol>UINT16_MAX) return -EINVAL;
    } else if (family==AF_UNIX || family==AF_INET || family==AF_INET6) {
        if (base!=SOCK_DGRAM || protocol) return -EPROTONOSUPPORT;
    } else return -EAFNOSUPPORT;
    struct network_socket *s=calloc(1,sizeof(*s)); if (!s) return -ENOMEM;
    int pair[2];
    if (socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,pair)) { int e=-errno; free(s); return e; }
    if (fcntl(pair[1],F_SETFL,O_NONBLOCK) || ((type&SOCK_NONBLOCK) && fcntl(pair[0],F_SETFL,O_NONBLOCK))) {
        int e=-errno; close(pair[0]); close(pair[1]); free(s); return e;
    }
    md_fd_object_init(&s->object,&control_type); s->space=md_network_namespace_retain(n);
    s->peer=pair[1]; s->family=family; s->type=base; s->protocol=protocol;
    s->receiver=fcntl(pair[0],F_DUPFD_CLOEXEC,3);
    if (s->receiver<0) {
        int e=-errno; close(pair[0]); close(pair[1]); md_network_namespace_release(s->space); free(s); return e;
    }
    if (family==AF_NETLINK) {
        s->listener.peer=s->peer; s->listener.protocol=protocol;
        pthread_mutex_lock(&n->fabric->lock);
        s->listener.next=n->listeners; n->listeners=&s->listener;
        pthread_mutex_unlock(&n->fabric->lock);
    }
    if (family==AF_PACKET) {
        pthread_mutex_lock(&n->fabric->lock);
        md_network_ethernet_attach(&s->ethernet,n,s->peer,base,protocol);
        pthread_mutex_unlock(&n->fabric->lock);
    }
    if (raw6) {
        pthread_mutex_lock(&n->fabric->lock);
        md_network_raw6_attach(&s->raw6,n,s->peer,protocol);
        pthread_mutex_unlock(&n->fabric->lock);
    }
    if (raw4) {
        pthread_mutex_lock(&n->fabric->lock);
        md_network_raw4_attach(&s->raw4,n,s->peer,protocol);
        pthread_mutex_unlock(&n->fabric->lock);
    }
    *out=&s->object; *fd=pair[0]; return 0;
}
static int packet_from(int peer,const struct md_network_packet *header,const void *data,size_t size) {
    struct iovec vector[]={{(void *)header,sizeof(*header)},{(void *)data,size}};
    struct msghdr message={.msg_iov=vector,.msg_iovlen=2};
    return sendmsg(peer,&message,MSG_DONTWAIT|MSG_NOSIGNAL)<0 ? -errno : 0;
}
static int packet(int peer,unsigned group,const void *data,size_t size) {
    const struct md_network_packet header={.group=group};
    return packet_from(peer,&header,data,size);
}
static int publish_response(void *context,const void *data,size_t size) {
    struct network_socket *s=context;
    return packet(s->peer,0,data,size);
}
unsigned md_network_broadcast(struct md_network_namespace *n,unsigned group,const void *data,size_t size) {
    unsigned published=0;
    for (struct md_network_listener *s=n->listeners;s;s=s->next)
        if (s->protocol==NETLINK_ROUTE && (s->groups&(1U<<(group-1)))) {
            int r=packet(s->peer,group,data,size);
            if (r && r!=-ECONNREFUSED && !s->no_enobufs) s->overflow=1;
            if (r!=-ECONNREFUSED) ++published;
        }
    return published;
}
static uint32_t supported_groups(const struct network_socket *s) {
    if (s->protocol==NETLINK_NETFILTER) return 0;
    if (s->protocol==NETLINK_GENERIC) return 1U<<(MD_NETWORK_GENERIC_NOTIFY-1);
    /* IPv6 observation groups remain valid when the fabric has no IPv6
     * devices, prefixes or neighbor-discovery state. No mutation is implied. */
    return RTMGRP_LINK|RTMGRP_NOTIFY|RTMGRP_IPV4_IFADDR|RTMGRP_IPV6_IFADDR|RTMGRP_IPV4_ROUTE|RTMGRP_IPV6_ROUTE
        | RTMGRP_NEIGH|RTMGRP_TC|RTMGRP_IPV4_RULE|RTMGRP_IPV6_IFINFO|(1U<<(RTNLGRP_IPV6_RULE-1))
        | (1U<<(RTNLGRP_IPV6_PREFIX-1)) | (1U<<(RTNLGRP_ND_USEROPT-1))
        | (1U<<(RTNLGRP_IPV6_NETCONF-1)) | (1U<<(RTNLGRP_NEXTHOP-1));
}
/* Port identity is protocol/namespace scoped and lives with the socket OFD.
 * Dup, fork and SCM_RIGHTS retain it; final release removes its listener. */
static int bind_port(struct network_socket *s,uint32_t requested,pid_t pid) {
    if (s->bound) return requested && requested!=s->listener.port ? -EINVAL : 0;
    uint32_t port=requested ? requested : (uint32_t)pid;
    if (!port) port=UINT32_MAX;
    for (;;) {
        struct md_network_listener *p=s->space->listeners;
        while (p && (p==&s->listener || p->protocol!=s->protocol || p->port!=port)) p=p->next;
        if (!p) break;
        if (requested) return -EADDRINUSE;
        port=port==(uint32_t)pid ? UINT32_MAX : port-1;
        if (!port) return -EADDRINUSE;
    }
    s->listener.port=port; s->bound=1; return 0;
}
long md_network_socket_call(struct md_fd_object *object, const struct md_identity *identity,
        long nr, const unsigned long *a, void *data, size_t capacity, size_t size, pid_t pid,const struct md_network_send *destination,
        const struct md_pid_identity *process,
        const struct md_network_target *resolver) {
    struct md_network_namespace *space=md_network_socket_namespace(object);
    if (!space || (!md_network_socket_control(object) && nr!=SYS_ioctl)) return -EBADF;
    if (nr==SYS_writev) nr=SYS_write;
    struct network_socket *s=(void *)object;
    if ((nr==SYS_setsockopt || nr==SYS_getsockopt) && a[1]==SOL_SOCKET
            && (a[2]==SO_ATTACH_FILTER || a[2]==SO_DETACH_FILTER || a[2]==SO_LOCK_FILTER)) {
        pthread_mutex_lock(&space->fabric->lock);
        long result;
        if (nr==SYS_setsockopt && a[2]==SO_ATTACH_FILTER)
            result=md_socket_filter_attach(&s->filter,s->receiver,data,size,sizeof(struct md_network_packet));
        else if (nr==SYS_getsockopt && a[2]==SO_GET_FILTER) {
            size_t bytes=s->filter.count*sizeof(*s->filter.code);
            result=bytes>capacity ? -ENOSPC : (long)bytes;
            if (result>0) memcpy(data,s->filter.code,bytes);
        } else if (nr==SYS_setsockopt && a[2]==SO_DETACH_FILTER)
            result=md_socket_filter_detach(&s->filter,s->receiver);
        else if (nr==SYS_setsockopt)
            result=setsockopt(s->receiver,SOL_SOCKET,SO_LOCK_FILTER,data,(socklen_t)size) ? -errno : 0;
        else {
            socklen_t length=(socklen_t)capacity;
            result=getsockopt(s->receiver,SOL_SOCKET,(int)a[2],data,&length) ? -errno : (long)length;
        }
        pthread_mutex_unlock(&space->fabric->lock); return result;
    }
    if (nr==SYS_read || nr==SYS_readv || nr==SYS_recvfrom || nr==SYS_recvmsg) {
        pthread_mutex_lock(&s->space->fabric->lock);
        int result=(s->listener.overflow || s->ethernet.overflow || s->raw6.overflow || s->raw4.overflow) ? -ENOBUFS
            : s->raw4.space ? md_network_raw4_options(&s->raw4)
            : s->raw6.space ? md_network_raw6_options(&s->raw6)
            : s->family==AF_PACKET ? MD_NETWORK_PACKET_ETHERNET | (s->ethernet.auxiliary ? MD_NETWORK_PACKET_AUXDATA : 0)
            : (s->packet_info ? MD_NETWORK_PACKET_INFO : 0)
                | (s->pass_credentials ? MD_NETWORK_PACKET_CREDENTIALS : 0);
        s->listener.overflow=s->ethernet.overflow=s->raw6.overflow=s->raw4.overflow=0;
        pthread_mutex_unlock(&s->space->fabric->lock);
        return result;
    }
    if (nr==SYS_ioctl) {
        if (md_network_family_ioctl(a[1])) {
            unsigned family=md_network_endpoint_is(object) ? md_network_endpoint_family(object) : s->family;
            size_t bytes=family==AF_INET6 ? (md_network_route_ioctl(a[1]) ? sizeof(struct in6_rtmsg) : sizeof(struct in6_ifreq))
                : md_network_route_ioctl(a[1]) ? sizeof(struct md_network_route_ioctl) : sizeof(struct ifreq);
            /* A zero-sized request queries the exact socket ABI before guest
             * copying. Revalidate it after descriptor capture on the real call. */
            if (!size) return (long)bytes;
            if (size!=bytes) return -EINVAL;
            if (family==AF_INET6) return md_network_route_legacy6(space,identity,a[1],data);
            if (a[1]==SIOCDIFADDR) return -ENOTTY;
        }
        if(md_network_arp_ioctl(a[1])) {
            if(size!=sizeof(struct arpreq)) return -EINVAL;
            if(a[1]!=SIOCGARP && !md_identity_capable_in(identity,space->owner,CAP_NET_ADMIN)) return -EPERM;
            pthread_mutex_lock(&space->fabric->lock);
            int result=md_network_stack_arp(space,a[1],data);
            pthread_mutex_unlock(&space->fabric->lock); return result;
        }
        if (a[1]==SIOCDEVPRIVATE) return size==sizeof(struct md_network_bridge_ioctl)
            ? md_network_route_bridge(space,identity,data) : -EINVAL;
        if (a[1]==SIOCGIFCONF) {
            if (size!=sizeof(struct md_network_ifconf) || capacity<size) return -EINVAL;
            struct md_network_ifconf *list=data;
            if (list->capacity < -1 || (list->capacity>=0 && (size_t)list->capacity>capacity-size)) return -EINVAL;
            int length=0;
            pthread_mutex_lock(&space->fabric->lock);
            for (unsigned i=0;i<space->count;++i) {
                const struct md_network_interface *link=&space->links[i];
                for (unsigned j=0;j<link->address_count;++j) {
                    const struct md_ip_address *address=&link->addresses[j].address;
                    if (address->family!=AF_INET) continue;
                    if (list->capacity>=0) {
                        if (list->capacity-length<(int)sizeof(struct ifreq)) continue;
                        struct ifreq *entry=(void *)((char *)list->entries+length);
                        memset(entry,0,sizeof(*entry));
                        memcpy(entry->ifr_name,link->info.name,IFNAMSIZ);
                        struct sockaddr_in *value=(void *)&entry->ifr_addr;
                        value->sin_family=AF_INET; value->sin_addr.s_addr=address->v4;
                    }
                    length+=sizeof(struct ifreq);
                }
            }
            pthread_mutex_unlock(&space->fabric->lock);
            list->length=length; return 0;
        }
        if(md_network_route_ioctl(a[1])) return size==sizeof(struct md_network_route_ioctl)
            ? md_network_route_legacy(space,identity,a[1],data) : -EINVAL;
        if (size!=sizeof(struct ifreq)) return -EINVAL;
        struct ifreq *r=data;
        if (a[1]!=SIOCGIFNAME && !memchr(r->ifr_name,0,IFNAMSIZ)) return -ENODEV;
        if (a[1]==SIOCBRADDIF) return md_network_route_attach_bridge(space,identity,r->ifr_name,(unsigned)r->ifr_ifindex);
        if (a[1]==SIOCSIFFLAGS) return md_network_route_set_flags(space,identity,r->ifr_name,(unsigned short)r->ifr_flags);
        if (a[1]==SIOCSIFMTU) return md_network_route_set_mtu(space,identity,r->ifr_name,(unsigned)r->ifr_mtu);
        if(md_network_address_ioctl(a[1]))
            return md_network_route_address_ioctl(space,identity,a[1],r);
        if (a[1]==SIOCSIFHWADDR) {
            if (r->ifr_hwaddr.sa_family!=ARPHRD_ETHER) return -EINVAL;
            return md_network_route_set_address(space,identity,r->ifr_name,(const unsigned char *)r->ifr_hwaddr.sa_data);
        }
        struct md_network_link info;
        pthread_mutex_lock(&space->fabric->lock);
        struct md_network_interface *link=md_network_interface_find(space,
            a[1]==SIOCGIFNAME ? (unsigned)r->ifr_ifindex : 0,
            a[1]==SIOCGIFNAME ? NULL : r->ifr_name);
        if (link) info=md_network_interface_snapshot(space,link);
        pthread_mutex_unlock(&space->fabric->lock);
        if (!link) return -ENODEV;
        if (a[1]==SIOCGIFNAME) {
            memcpy(r->ifr_name,info.name,IFNAMSIZ); return 0;
        }
        if (a[1]==SIOCGIFINDEX) r->ifr_ifindex=info.index;
        else if (a[1]==SIOCGIFFLAGS) r->ifr_flags=(short)info.flags;
        else if (a[1]==SIOCGIFMTU) r->ifr_mtu=info.mtu;
        else if (a[1]==SIOCGIFTXQLEN) r->ifr_qlen=info.tx_queue_length;
        else if (a[1]==SIOCGIFHWADDR) {
            memset(&r->ifr_hwaddr,0,sizeof(r->ifr_hwaddr));
            r->ifr_hwaddr.sa_family=info.type;
            memcpy(r->ifr_hwaddr.sa_data,info.address,6);
        }
        else return -EOPNOTSUPP;
        return 0;
    }
    int socket_query=nr==SYS_getsockopt && a[1]==SOL_SOCKET && (a[2]==SO_DOMAIN || a[2]==SO_TYPE
        || a[2]==SO_PROTOCOL || a[2]==SO_ERROR || a[2]==SO_PASSCRED);
    if (s->raw6.space && !socket_query) {
        pthread_mutex_lock(&space->fabric->lock);
        int binding=nr==SYS_setsockopt && a[1]==SOL_SOCKET && (a[2]==SO_BINDTODEVICE
#ifdef SO_BINDTOIFINDEX
            || a[2]==SO_BINDTOIFINDEX
#endif
            );
        long result=binding && s->raw6.device && !md_identity_capable_in(identity,space->owner,CAP_NET_RAW)
            ? -EPERM : md_network_raw6_call(&s->raw6,nr,a,data,capacity,size,destination);
        pthread_mutex_unlock(&space->fabric->lock); return result;
    }
    if (s->raw4.space && !socket_query) {
        pthread_mutex_lock(&space->fabric->lock);
        int binding=nr==SYS_setsockopt && a[1]==SOL_SOCKET && a[2]==SO_BINDTODEVICE;
        long result=binding && s->raw4.device && !md_identity_capable_in(identity,space->owner,CAP_NET_RAW)
            ? -EPERM : md_network_raw4_call(&s->raw4,nr,a,data,capacity,size,destination);
        pthread_mutex_unlock(&space->fabric->lock); return result;
    }
    if (s->family==AF_PACKET && !(nr==SYS_getsockopt && a[1]==SOL_SOCKET)) {
        pthread_mutex_lock(&space->fabric->lock);
        long result=md_network_ethernet_call(&s->ethernet,nr,a,data,capacity,size,destination);
        pthread_mutex_unlock(&space->fabric->lock); return result;
    }
    if (nr==SYS_getsockopt) {
        if (capacity<sizeof(int)) return -EINVAL;
        int value;
        if (a[1]==SOL_NETLINK && s->family==AF_NETLINK) {
            pthread_mutex_lock(&s->space->fabric->lock);
            switch (a[2]) {
            case NETLINK_PKTINFO: value=s->packet_info; break;
            case NETLINK_CAP_ACK: value=s->capped_ack; break;
            case NETLINK_EXT_ACK: value=s->extended_ack; break;
            case NETLINK_GET_STRICT_CHK: value=s->strict_check; break;
            case NETLINK_BROADCAST_ERROR: value=s->listener.broadcast_error; break;
            case NETLINK_NO_ENOBUFS: value=s->listener.no_enobufs; break;
            case NETLINK_LIST_MEMBERSHIPS: value=(int)s->listener.groups; break;
            default: pthread_mutex_unlock(&s->space->fabric->lock); return -ENOPROTOOPT;
            }
            pthread_mutex_unlock(&s->space->fabric->lock);
            memcpy(data,&value,sizeof(value)); return sizeof(value);
        }
        if (a[1]!=SOL_SOCKET) return -EOPNOTSUPP;
        switch (a[2]) {
        case SO_DOMAIN: value=s->family; break;
        case SO_TYPE: value=s->type; break;
        case SO_PROTOCOL: value=s->protocol; break;
        case SO_PASSCRED:
            pthread_mutex_lock(&s->space->fabric->lock);
            value=s->pass_credentials;
            pthread_mutex_unlock(&s->space->fabric->lock); break;
        case SO_ERROR:
            pthread_mutex_lock(&s->space->fabric->lock);
            value=s->listener.overflow || s->ethernet.overflow || s->raw6.overflow || s->raw4.overflow ? ENOBUFS : 0;
            s->listener.overflow=s->ethernet.overflow=s->raw6.overflow=s->raw4.overflow=0;
            pthread_mutex_unlock(&s->space->fabric->lock); break;
        default: return -EOPNOTSUPP;
        }
        memcpy(data,&value,sizeof(value)); return sizeof(value);
    }
    if (s->family!=AF_NETLINK) return -EOPNOTSUPP;
    if (nr==SYS_setsockopt && a[1]==SOL_SOCKET && a[2]==SO_PASSCRED) {
        if (size<sizeof(int)) return -EINVAL;
        int value; memcpy(&value,data,sizeof(value));
        pthread_mutex_lock(&s->space->fabric->lock);
        s->pass_credentials=!!value;
        pthread_mutex_unlock(&s->space->fabric->lock); return 0;
    }
    if (nr==SYS_setsockopt && a[1]==SOL_NETLINK) {
        if (size<sizeof(int)) return -EINVAL;
        int value; memcpy(&value,data,sizeof(value));
        int result=0;
        pthread_mutex_lock(&s->space->fabric->lock);
        if (a[2]==NETLINK_ADD_MEMBERSHIP || a[2]==NETLINK_DROP_MEMBERSHIP) {
            if (value<=0 || value>32) result=-EINVAL;
            else if (!(supported_groups(s)&(1U<<(value-1)))) result=-EOPNOTSUPP;
            else if (a[2]==NETLINK_ADD_MEMBERSHIP) s->listener.groups|=1U<<(value-1);
            else s->listener.groups&=~(1U<<(value-1));
        } else if (a[2]==NETLINK_PKTINFO) s->packet_info=!!value;
        else if (a[2]==NETLINK_EXT_ACK) s->extended_ack=!!value;
        else if (a[2]==NETLINK_CAP_ACK) s->capped_ack=!!value;
        else if (a[2]==NETLINK_GET_STRICT_CHK) s->strict_check=!!value;
        /* Kernel topology producers and netlink_sendmsg both ignore the
         * multicast return code. This subscriber option cannot roll back a
         * committed link change; receiver overrun is controlled separately. */
        else if (a[2]==NETLINK_BROADCAST_ERROR) s->listener.broadcast_error=!!value;
        else if (a[2]==NETLINK_NO_ENOBUFS) {
            s->listener.no_enobufs=!!value;
        }
        else result=-ENOPROTOOPT;
        pthread_mutex_unlock(&s->space->fabric->lock);
        return result;
    }
    if (nr==SYS_connect) {
        if (size<sizeof(sa_family_t) || size>sizeof(struct sockaddr_storage)) return -EINVAL;
        const struct sockaddr_nl *address=data;
        if (address->nl_family==AF_UNSPEC) return 0;
        if (address->nl_family!=AF_NETLINK) return -EAFNOSUPPORT;
        if (size<sizeof(*address)) return -EINVAL;
        /* Only the namespace's kernel catalogue is an addressable peer.
         * Unconnected sends already use this default destination. */
        if (address->nl_pid || address->nl_groups) return -EOPNOTSUPP;
        pthread_mutex_lock(&s->space->fabric->lock);
        int error=bind_port(s,0,pid);
        pthread_mutex_unlock(&s->space->fabric->lock); return error;
    }
    if (nr==SYS_bind) {
        if (size<sizeof(struct sockaddr_nl) || size>sizeof(struct sockaddr_storage)) return -EINVAL;
        const struct sockaddr_nl *address=data;
        if (address->nl_family!=AF_NETLINK) return -EAFNOSUPPORT;
        if (address->nl_groups&~supported_groups(s)) return -EOPNOTSUPP;
        pthread_mutex_lock(&s->space->fabric->lock);
        int error=bind_port(s,address->nl_pid,pid);
        if (!error) s->listener.groups=address->nl_groups;
        pthread_mutex_unlock(&s->space->fabric->lock); return error;
    }
    if (nr==SYS_getsockname || nr==SYS_getpeername) {
        if (capacity<sizeof(struct sockaddr_nl)) return -EINVAL;
        pthread_mutex_lock(&s->space->fabric->lock);
        struct sockaddr_nl address={.nl_family=AF_NETLINK,
            .nl_pid=nr==SYS_getsockname ? s->listener.port : 0,.nl_groups=nr==SYS_getsockname ? s->listener.groups : 0};
        pthread_mutex_unlock(&s->space->fabric->lock);
        memcpy(data,&address,sizeof(address)); return sizeof(address);
    }
    if (nr==SYS_sendto || nr==SYS_sendmsg || nr==SYS_write) {
        uint32_t groups=0;
        int address_error=md_network_netlink_destination(destination,&groups);
        if (address_error) return address_error;
        if (size<sizeof(struct nlmsghdr)) return -EINVAL;
        pthread_mutex_lock(&s->space->fabric->lock);
        int error=bind_port(s,0,pid);
        uint32_t port=s->listener.port;
        pthread_mutex_unlock(&s->space->fabric->lock);
        if (error) return error;
        if (groups) {
            if (!md_identity_capable_in(identity,s->space->owner,CAP_NET_ADMIN)) return -EPERM;
            unsigned group=(unsigned)__builtin_ctz(groups)+1;
            if (!(supported_groups(s)&(1U<<(group-1)))) return -EOPNOTSUPP;
            struct md_network_packet header={.group=group,.port=port,
                .pid=process ? md_pid_identity_native(process) : pid,
                .uid=identity->uid.real,.gid=identity->gid.real};
            header.pid_count=md_pid_identity_export(process,header.pids);
            /* A multicast destination also addresses the namespace's kernel.
             * User messages keep their source identity and exclude the sender;
             * they must never masquerade as committed topology notifications. */
            pthread_mutex_lock(&s->space->fabric->lock);
            for (struct md_network_listener *p=s->space->listeners;p;p=p->next)
                if (p!=&s->listener && p->protocol==s->protocol && (p->groups&(1U<<(group-1)))) {
                    int r=packet_from(p->peer,&header,data,size);
                    if (r && r!=-ECONNREFUSED && !p->no_enobufs) p->overflow=1;
                }
            pthread_mutex_unlock(&s->space->fabric->lock);
        }
        if (s->protocol==NETLINK_NETFILTER) {
            void *reply=malloc(MD_NETWORK_PACKET_MAX);
            if (!reply) return -ENOMEM;
            struct md_network_publication publication={.context=s,.publish=publish_response};
            long result=md_network_nft(s->space,identity,data,size,port,
                s->capped_ack,reply,MD_NETWORK_PACKET_MAX,&publication);
            free(reply); return result<0 ? result : (long)size;
        }
        const struct nlmsghdr *h=data;
        /* Kernel-backed response queue supplies poll/epoll readiness; no
         * request or interface mutation is submitted to Android netlink. */
        long remaining=(long)size;
        if (!NLMSG_OK(h,remaining)) return -EINVAL;
        for (;NLMSG_OK(h,remaining);h=NLMSG_NEXT(h,remaining)) {
            union { struct nlmsghdr align; unsigned char bytes[4096+sizeof(struct nlmsgerr)]; } small;
            int query=s->protocol==NETLINK_ROUTE && md_network_route_query(h->nlmsg_type);
            size_t limit=query ? MD_NETWORK_PACKET_MAX : sizeof(small);
            unsigned char *reply=query ? malloc(limit) : small.bytes; if (!reply) return -ENOMEM;
            struct md_network_publication publication={.context=s,.publish=publish_response};
            long result;
            if (s->protocol==NETLINK_GENERIC) {
                result=md_network_generic(h,h->nlmsg_len,port,s->capped_ack,reply,limit);
                if (result>0) result=publish_response(s,reply,(size_t)result);
            } else result=md_network_route(s->space,identity,h,h->nlmsg_len,port,
                (s->capped_ack ? MD_NETWORK_ROUTE_CAPPED_ACK : 0)
                    | (s->strict_check ? MD_NETWORK_ROUTE_STRICT : 0),resolver,reply,limit,&publication);
            if (query) free(reply);
            if (result<0) return result;
        }
        return (long)size;
    }
    return -EOPNOTSUPP;
}
