#define _GNU_SOURCE
#include "network_endpoint.h"
#include "network_internal.h"
#include "network_forward.h"
#include "network_conntrack.h"
#include "network_proc.h"
#include "control_epoll.h"
#include "fd_selector.h"
#include "test_network_fixture.h"
#include "guest_identity.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/netfilter/nfnetlink_conntrack.h>
#include <net/if.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int table_has_inode(const char *text,unsigned long long inode) {
    for (const char *row=text;row;row=strchr(row,'\n')) {
        if (*row=='\n') ++row;
        unsigned long long value;
        if (sscanf(row," %*u: %*s %*s %*x %*x:%*x %*x:%*x %*x %*u %*u %llu",&value)==1
            && value==inode) return 1;
    }
    return 0;
}
static void endpoint_option(struct md_fd_object *o,int fd,unsigned level,unsigned option,void *value,socklen_t size) {
    struct md_identity root=md_identity_new(0,0);
    int error=md_network_endpoint_sockopt(o,fd,&root,1,level,option,value,&size);
    md_identity_release(&root);
    if (error) fprintf(stderr,"endpoint option %u/%u: %d\n",level,option,error);
    assert(!error);
}
static void connection_observers(struct md_network_namespace *n,unsigned family,uint32_t policy) {
    struct md_fd_object *object=NULL; int fd=-1;
    assert(!md_network_endpoint_open(n,0,family,SOCK_STREAM,0,&object,&fd));
    struct md_inet_address address={.ip=md_ip_loopback(family)};
    int server=socket(family,SOCK_STREAM|SOCK_CLOEXEC,0);
    assert(server>=0 && !md_inet_bind(server,&address,0));
    assert(!md_inet_name(server,0,&address) && !listen(server,1));
    assert(!md_inet_connect(fd,&address,0));
    int peer=accept4(server,NULL,NULL,SOCK_CLOEXEC);
    assert(peer>=0);
    struct md_control_file provider=md_fd_object_readiness(object);
    struct md_control_epoll *queues[2]={md_control_epoll_new(),md_control_epoll_new()};
    struct epoll_event event={.events=EPOLLIN|EPOLLET|policy,.data.u64=42},observed;
    for (unsigned i=0;i<2;++i) {
        assert(queues[i]);
        assert(!md_control_epoll_ctl(queues[i],EPOLL_CTL_ADD,fd,object,provider,&event,fd));
    }
    for (unsigned round=0;round<32;++round) {
        assert(write(peer,"x",1)==1);
        for (unsigned i=0;i<2;++i) {
            assert(md_control_epoll_collect(queues[i],&observed,1)==1);
            assert(observed.events==EPOLLIN && observed.data.u64==42);
        }
        char byte;
        assert(read(fd,&byte,1)==1 && byte=='x');
        for (unsigned i=0;i<2;++i) {
            assert(md_control_epoll_collect(queues[i],&observed,1)==0);
            struct pollfd source={.fd=md_control_epoll_descriptor(queues[i]),.events=POLLIN};
            assert(poll(&source,1,0)==0);
        }
    }
    for (unsigned i=0;i<2;++i) md_control_epoll_release(queues[i]);
    close(peer); close(server); close(fd); md_fd_object_release(object);
}
static void refused_connect(struct md_network_namespace *n,const struct md_inet_address *target,uint32_t policy) {
    struct md_fd_object *object=NULL; int fd=-1;
    assert(!md_network_endpoint_open(n,0,target->ip.family,SOCK_STREAM|SOCK_NONBLOCK,0,&object,&fd));
    struct md_inet_address client={.ip=md_ip_loopback(target->ip.family)},gateway;
    assert(!md_inet_bind(fd,&client,0) && !md_inet_name(fd,0,&client));
    struct md_network_flow_path path={.original={.source=client.ip,.source_port=client.port,
        .destination=target->ip,.destination_port=target->port,.protocol=IPPROTO_TCP}};
    path.translated=path.original;
    struct md_control_file provider=md_fd_object_readiness(object);
    struct md_control_epoll *queue=md_control_epoll_new();
    struct epoll_event event={.events=EPOLLOUT|EPOLLET|policy,.data.u64=42},observed;
    assert(queue && provider.ops);
    /* Registration before connect must remain valid when mediation starts. */
    assert(!md_control_epoll_ctl(queue,EPOLL_CTL_ADD,fd,object,provider,&event,fd));
    pthread_mutex_lock(&n->fabric->lock);
    assert(!md_network_forward_route(n,object,SOCK_STREAM,&client,target,&path,&gateway));
    assert(md_inet_connect(fd,&gateway,0)==-1 && errno==EINPROGRESS);
    /* Forwarder cannot process either leg under this lock. Native writability
     * must not leak through the still-pending destination handshake. */
    assert(provider.ops->poll(provider.context)==0);
    assert(md_control_epoll_collect(queue,&observed,1)==0);
    pthread_mutex_unlock(&n->fabric->lock);
    struct timespec start,now;
    assert(!clock_gettime(CLOCK_MONOTONIC,&start));
    for (;;) {
        int count=md_control_epoll_collect(queue,&observed,1);
        assert(count>=0);
        if (count) break;
        assert(!clock_gettime(CLOCK_MONOTONIC,&now));
        long elapsed=(now.tv_sec-start.tv_sec)*1000+(now.tv_nsec-start.tv_nsec)/1000000;
        assert(elapsed<2000);
        struct pollfd ready={.fd=md_control_epoll_descriptor(queue),.events=POLLIN};
        /* EVENT_WAIT: refusal completion; expiry fails the test. */
        assert(poll(&ready,1,(int)(2000-elapsed))==1);
    }
    assert(observed.data.u64==42 && (observed.events&EPOLLERR));
    assert(md_network_endpoint_connect_result(object,NULL)==-ECONNREFUSED);
    for (unsigned i=0;i<2;++i) {
        int value=-1; socklen_t size=sizeof(value);
        assert(!md_network_endpoint_sockopt(object,fd,NULL,0,SOL_SOCKET,SO_ERROR,&value,&size));
        assert(value==(i ? 0 : ECONNREFUSED));
    }
    assert(!(provider.ops->poll(provider.context)&EPOLLERR));
    struct sockaddr_storage peer; socklen_t length=sizeof(peer);
    assert(md_network_endpoint_name(object,fd,1,&peer,&length)==-ENOTCONN);
    close(fd); md_fd_object_release(object); md_control_epoll_release(queue);
}
static void forwarded_connect(unsigned family) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    connection_observers(n,family,0);
    connection_observers(n,family,EPOLLEXCLUSIVE);
    struct md_inet_address bad={.ip=md_ip_any(family),.port=htons(9)};
    if (family==AF_INET) bad.ip.v4=htonl(INADDR_BROADCAST);
    else { bad.ip.bytes[0]=0xfe; bad.ip.bytes[1]=0x80; bad.ip.bytes[15]=1; }
    int fd=socket(family,SOCK_STREAM|SOCK_NONBLOCK|SOCK_CLOEXEC,0);
    assert(fd>=0 && md_inet_connect(fd,&bad,0)==-1);
    int expected=-errno;
    assert(expected!=-EINPROGRESS);
    close(fd);
    struct md_inet_address client={.ip=md_ip_loopback(family)},gateway;
    struct md_network_flow_path path={.original={.source=client.ip,.protocol=IPPROTO_TCP}};
    pthread_mutex_lock(&n->fabric->lock);
    for (unsigned i=0;i<4;++i)
        assert(md_network_forward_route(n,NULL,SOCK_STREAM,&client,&bad,&path,&gateway)==expected);
    pthread_mutex_unlock(&n->fabric->lock);

    struct md_inet_address target={.ip=md_ip_loopback(family)};
    int server=socket(family,SOCK_STREAM|SOCK_CLOEXEC,0);
    assert(server>=0 && !md_inet_bind(server,&target,0));
    assert(!md_inet_name(server,0,&target));
    /* A reserved but non-listening port rejects the SYN. Nonblocking connect
     * reports EINPROGRESS first; the queued SO_ERROR must not become success
     * merely because the relay itself can accept a local connection. */
    for (unsigned i=0;i<32;++i) refused_connect(n,&target,i&1 ? EPOLLEXCLUSIVE : 0);
    assert(!listen(server,1));
    fd=socket(family,SOCK_STREAM|SOCK_CLOEXEC,0);
    assert(fd>=0 && !md_inet_bind(fd,&client,0) && !md_inet_name(fd,0,&client));
    path.original=(struct md_filter_packet){.source=client.ip,.source_port=client.port,
        .destination=target.ip,.destination_port=target.port,.protocol=IPPROTO_TCP};
    path.translated=path.original;
    pthread_mutex_lock(&n->fabric->lock);
    assert(!md_network_forward_route(n,NULL,SOCK_STREAM,&client,&target,&path,&gateway));
    pthread_mutex_unlock(&n->fabric->lock);
    /* EVENT_WAIT: the real upstream is connected before the guest sees its
     * local relay; both directions must retain that exact connection. */
    struct pollfd ready={.fd=server,.events=POLLIN};
    assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
    int peer=accept4(server,NULL,NULL,SOCK_CLOEXEC);
    assert(peer>=0 && !md_inet_connect(fd,&gateway,0));
    for (unsigned i=0;i<2;++i) {
        int from=i ? fd : peer,to=i ? peer : fd;
        assert(write(from,"r",1)==1);
        ready=(struct pollfd){.fd=to,.events=POLLIN};
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        char byte=0;
        assert(read(to,&byte,1)==1 && byte=='r');
    }
    close(fd); close(peer); close(server);
    md_network_namespace_release(n);
    printf("PASS IPv%u relay: immediate connect errors and retained upstream socket\n",family==AF_INET ? 4 : 6);
}
static void tcp_reuse(unsigned family) {
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *n=md_network_namespace_copy(root,NULL);
    assert(n);
    struct md_identity identity=md_identity_new(0,0);
    unsigned index;
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"tcp-reuse",&index));
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    link->info.flags|=IFF_UP; test_network_v4(link,htonl(0x0a590001),24);
    md_network_interface_up(link);
    struct md_inet_address local={.ip=family==AF_INET ? md_ip_v4(htonl(0x0a590001))
        : md_network_interface_source(link,md_ip_loopback(AF_INET6)),.port=htons(18765)};
    struct sockaddr_storage wire; socklen_t length;
    assert(!md_inet_encode(&local,0,&wire,&length));
    struct md_fd_object *objects[2]; int fds[2],one=1;
    for (unsigned i=0;i<2;++i) {
        assert(!md_network_endpoint_open(n,0,family,SOCK_STREAM,0,&objects[i],&fds[i]));
        endpoint_option(objects[i],fds[i],SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
        assert(!md_network_endpoint_device_set(objects[i],&identity,"tcp-reuse"));
        assert(!md_network_endpoint_bind(objects[i],fds[i],&identity,(void *)&wire,length));
    }
    struct md_network_flow_path path={.destination=n->inode,
        .translated={.destination=local.ip,.destination_port=local.port,.input=index}};
    struct md_inet_address native; const void *selected=NULL;
    assert(md_network_endpoint_resolve(n->fabric,&path,SOCK_STREAM,&native,&selected)==-ECONNREFUSED);
    assert(!md_network_endpoint_listen(objects[0],fds[0],1));
    assert(!md_network_endpoint_resolve(n->fabric,&path,SOCK_STREAM,&native,&selected) && selected==objects[0]);
    assert(md_network_endpoint_listen(objects[1],fds[1],1)==-EADDRINUSE);
    close(fds[0]); md_fd_object_release(objects[0]);
    assert(!md_network_endpoint_listen(objects[1],fds[1],1));
    assert(!md_network_endpoint_resolve(n->fabric,&path,SOCK_STREAM,&native,&selected) && selected==objects[1]);
    close(fds[1]); md_fd_object_release(objects[1]);
    md_identity_release(&identity); md_network_namespace_release(n); md_network_namespace_release(root);
    printf("PASS IPv%u TCP reuse: bind-only sockets, listen conflicts and listener selection\n",family==AF_INET ? 4 : 6);
}
static void datagram_handoff(unsigned family) {
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *n=md_network_namespace_copy(root,NULL);
    struct md_identity identity=md_identity_new(0,0);
    unsigned index;
    assert(n && !md_network_interface_add(n,MD_LINK_BRIDGE,"udp-handoff",&index));
    md_network_interface_find(n,0,"lo")->info.flags|=IFF_UP;
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    link->info.flags|=IFF_UP; test_network_v4(link,htonl(0x0a590001),24); md_network_interface_up(link);
    struct md_inet_address local={.ip=family==AF_INET ? md_ip_v4(htonl(0x0a590001))
        : md_network_interface_source(link,md_ip_loopback(AF_INET6)),.port=htons(18766)};
    if (family==AF_INET6) {
        local.ip=md_ip_any(AF_INET6); local.ip.bytes[0]=0xfd; local.ip.bytes[15]=1;
        assert(!md_network_interface_address(link,(struct md_network_ip_assignment){
            .address=local.ip,.prefix=64,.flags=IFA_F_PERMANENT},0,0));
    }
    struct sockaddr_storage wire,native,peer; socklen_t length,native_size,peer_size;
    assert(!md_inet_encode(&local,0,&wire,&length));
    struct md_fd_object *objects[3]; int fds[3],one=1;
    for (unsigned i=0;i<3;++i) {
        assert(!md_network_endpoint_open(n,0,family,SOCK_DGRAM,0,&objects[i],&fds[i]));
        endpoint_option(objects[i],fds[i],SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
    }
    assert(!md_network_endpoint_bind(objects[0],fds[0],&identity,(void *)&wire,length));
    assert(!md_network_endpoint_autobind(objects[1],fds[1],&identity,(void *)&wire,length,0));
    assert(!md_network_endpoint_destination(objects[1],(void *)&wire,length,&native,&native_size));
    assert(!connect(fds[1],(void *)&native,native_size));
    assert(!md_network_endpoint_connected(objects[1],fds[1],(void *)&wire,length));
    for (unsigned round=0;round<3;++round) {
        unsigned receiver=round==1 ? 2 : 0;
        assert(write(fds[1],"u",1)==1);
        struct pollfd ready={.fd=fds[receiver],.events=POLLIN};
        int count=poll(&ready,1,2000);
        if (count!=1) fprintf(stderr,"UDP handoff family=%u round=%u receiver=%u readiness=%d\n",family,round,receiver,count);
        assert(count==1 && (ready.revents&POLLIN));
        char byte;
        peer_size=sizeof(peer);
        assert(recvfrom(fds[receiver],&byte,1,0,(void *)&peer,&peer_size)==1 && byte=='u');
        assert(!md_network_endpoint_source(objects[receiver],&peer,&peer_size));
        if (!round) {
            assert(!md_network_endpoint_bind(objects[2],fds[2],&identity,(void *)&wire,length));
            assert(!md_network_endpoint_destination(objects[2],(void *)&peer,peer_size,&native,&native_size));
            assert(!connect(fds[2],(void *)&native,native_size));
            assert(!md_network_endpoint_connected(objects[2],fds[2],(void *)&peer,peer_size));
        }
        unsigned sender=round<2 ? 2 : 0;
        assert(!md_network_endpoint_destination(objects[sender],(void *)&peer,peer_size,&native,&native_size));
        assert(sendto(fds[sender],"r",1,0,(void *)&native,native_size)==1);
        if (round==1) { close(fds[2]); md_fd_object_release(objects[2]); }
        ready=(struct pollfd){.fd=fds[1],.events=POLLIN};
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        assert(read(fds[1],&byte,1)==1 && byte=='r');
        if (round==1) {
            ready=(struct pollfd){.fd=fds[0],.events=POLLIN};
            assert(!poll(&ready,1,0));
        }
    }
    for (unsigned i=0;i<2;++i) { close(fds[i]); md_fd_object_release(objects[i]); }
    md_identity_release(&identity); md_network_namespace_release(n); md_network_namespace_release(root);
    printf("PASS IPv%u UDP listener handoff: connected peer, reverse tuple and listener fallback\n",family==AF_INET ? 4 : 6);
}
static int count_connection(void *context,const struct md_connection *entry) {
    (void)entry;
    ++*(size_t *)context; return 0;
}
static size_t connection_count(struct md_network_namespace *n) {
    size_t count=0;
    pthread_mutex_lock(&n->fabric->lock);
    assert(!md_network_forward_connections(n,count_connection,&count));
    pthread_mutex_unlock(&n->fabric->lock); return count;
}
static int reject_connection_reply(void *context,const void *data,size_t size) {
    (void)context; (void)data; (void)size; return -EAGAIN;
}
static void forget_connections(struct md_network_namespace *n,const struct md_identity *identity,unsigned family) {
    struct { struct nlmsghdr header; struct nfgenmsg family; } request={
        .header={.nlmsg_len=NLMSG_LENGTH(sizeof(struct nfgenmsg)),
            .nlmsg_type=(NFNL_SUBSYS_CTNETLINK<<8)|IPCTNL_MSG_CT_DELETE,
            .nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK,.nlmsg_seq=1},
        .family={.nfgen_family=family,.version=NFNETLINK_V0}};
    unsigned char reply[256];
    size_t count=connection_count(n); assert(count);
    struct md_network_publication failure={.publish=reject_connection_reply};
    assert(md_network_conntrack(n,identity,&request,sizeof(request),0,0,reply,sizeof(reply),&failure)==-EAGAIN);
    assert(connection_count(n)==count);
    long size=md_network_conntrack(n,identity,&request,sizeof(request),0,0,reply,sizeof(reply),NULL);
    assert(size>=(long)NLMSG_LENGTH(sizeof(struct nlmsgerr)));
    struct nlmsghdr *header=(void *)reply;
    assert(header->nlmsg_type==NLMSG_ERROR && !((struct nlmsgerr *)NLMSG_DATA(header))->error);
    assert(!connection_count(n));
}
static void datagram_namespace_retirement(unsigned family,int bound) {
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity identity=md_identity_new(0,0);
    struct md_fd_object *client;
    int fd;
    assert(root && !md_network_endpoint_open(root,0,family,SOCK_DGRAM,0,&client,&fd));
    for (unsigned round=0;round<2;++round) {
        struct md_network_namespace *child=md_network_namespace_copy(root,NULL);
        unsigned indices[2];
        assert(child);
        pthread_mutex_lock(&root->fabric->lock);
        assert(!md_network_interface_add(root,MD_LINK_VETH,"retire-out",&indices[0]));
        assert(!md_network_interface_add(child,MD_LINK_VETH,"retire-in",&indices[1]));
        struct md_network_interface *links[2]={md_network_interface_find(root,indices[0],NULL),
            md_network_interface_find(child,indices[1],NULL)};
        struct md_ip_address addresses[2];
        for (unsigned i=0;i<2;++i) {
            links[i]->peer=indices[1-i]; links[i]->info.flags|=IFF_UP;
            addresses[i]=family==AF_INET ? md_ip_v4(htonl(0x0a5a0001+i)) : md_ip_any(AF_INET6);
            if (family==AF_INET6) { addresses[i].bytes[0]=0xfd; addresses[i].bytes[15]=1+i; }
            assert(!md_network_interface_address(links[i],(struct md_network_ip_assignment){
                .address=addresses[i],.prefix=family==AF_INET ? 24 : 64,.flags=IFA_F_PERMANENT},0,0));
            md_network_interface_up(links[i]);
        }
        pthread_mutex_unlock(&root->fabric->lock);
        struct md_fd_object *server;
        int peer;
        assert(!md_network_endpoint_open(child,0,family,SOCK_DGRAM,0,&server,&peer));
        struct md_inet_address address={.ip=addresses[1],.port=htons(18768)};
        struct sockaddr_storage wire,native,source; socklen_t length,size,source_size=sizeof(source);
        assert(!md_inet_encode(&address,0,&wire,&length));
        assert(!md_network_endpoint_bind(server,peer,&identity,(void *)&wire,length));
        if (!round) {
            if (bound) endpoint_option(client,fd,SOL_SOCKET,SO_BINDTOIFINDEX,&indices[0],sizeof(indices[0]));
            assert(!md_network_endpoint_autobind(client,fd,&identity,(void *)&wire,length,0));
            assert(!md_network_endpoint_destination(client,(void *)&wire,length,&native,&size));
            assert(!connect(fd,(void *)&native,size));
            assert(!md_network_endpoint_connected(client,fd,(void *)&wire,length));
        }
        assert(write(fd,"q",1)==1);
        struct pollfd ready={.fd=peer,.events=POLLIN};
        if (round && bound) {
            /* EVENT_WAIT: replacement must not receive traffic bound to the deleted link. */
            assert(!poll(&ready,1,200));
            assert(md_network_endpoint_destination(client,(void *)&wire,length,&native,&size)==-ENODEV);
            close(peer); md_fd_object_release(server); md_network_namespace_release(child);
            break;
        }
        /* EVENT_WAIT: one native write must reach the new namespace; no reconnect or retry. */
        int count=poll(&ready,1,2000);
        if (count!=1) fprintf(stderr,"UDP namespace retirement family=%u round=%u readiness=%d\n",family,round,count);
        assert(count==1 && (ready.revents&POLLIN));
        char byte;
        assert(recvfrom(peer,&byte,1,0,(void *)&source,&source_size)==1 && byte=='q');
        assert(!md_network_endpoint_source(server,&source,&source_size));
        assert(!md_network_endpoint_destination(server,(void *)&source,source_size,&native,&size));
        assert(sendto(peer,"a",1,0,(void *)&native,size)==1);
        ready=(struct pollfd){.fd=fd,.events=POLLIN};
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        assert(read(fd,&byte,1)==1 && byte=='a');
        close(peer); md_fd_object_release(server);
        md_network_namespace_release(child);
        assert(!md_network_interface_find(root,indices[0],NULL));
        assert(!connection_count(root));
    }
    close(fd); md_fd_object_release(client);
    assert(!root->fabric->endpoints);
    md_identity_release(&identity); md_network_namespace_release(root);
    printf("PASS IPv%u UDP: peer namespace replacement preserves connected client and explicit binding=%d\n",
        family==AF_INET ? 4 : 6,bound);
}
static void datagram_idle(void) {
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity identity=md_identity_new(0,0);
    struct {
        struct md_network_namespace *space;
        struct md_fd_object *objects[2];
        int fds[2];
        unsigned family;
        int flush, connected, external, group;
    } pairs[14]={0};
    assert(root);
    for (unsigned i=0;i<14;++i) {
        unsigned variant=i%7;
        pairs[i].family=i<7 ? AF_INET : AF_INET6;
        pairs[i].flush=variant<6 && (variant&1);
        pairs[i].connected=variant>=2 && variant<6;
        pairs[i].external=variant==4 || variant==5;
        pairs[i].group=variant==6;
        struct md_network_namespace *n=pairs[i].space=pairs[i].external
            ? md_network_namespace_new(NULL,MD_NETWORK_ROUTED) : md_network_namespace_copy(root,NULL);
        assert(n);
        struct md_network_interface *loop=md_network_interface_find(n,0,"lo");
        loop->info.flags|=IFF_UP; md_network_interface_up(loop);
        struct md_inet_address local={.ip=md_ip_loopback(pairs[i].family),.port=pairs[i].external ? 0 : htons(18767)};
        if (pairs[i].group) local.ip=md_ip_any(pairs[i].family);
        struct sockaddr_storage wire,native; socklen_t length,native_size;
        assert(!md_inet_encode(&local,0,&wire,&length));
        for (unsigned j=0;j<2;++j) {
            if (j && pairs[i].external) {
                pairs[i].fds[j]=socket(pairs[i].family,SOCK_DGRAM|SOCK_CLOEXEC,0);
                assert(pairs[i].fds[j]>=0);
            } else assert(!md_network_endpoint_open(n,0,pairs[i].family,SOCK_DGRAM,0,&pairs[i].objects[j],&pairs[i].fds[j]));
        }
        assert(!md_network_endpoint_bind(pairs[i].objects[0],pairs[i].fds[0],&identity,(void *)&wire,length));
        if (pairs[i].external) {
            assert(!md_network_endpoint_name(pairs[i].objects[0],pairs[i].fds[0],0,&wire,&length));
            assert(!connect(pairs[i].fds[1],(void *)&wire,length));
            continue;
        }
        if (pairs[i].group) {
            unsigned index;
            assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"idle-group",&index));
            struct md_network_interface *link=md_network_interface_find(n,index,NULL);
            link->info.flags|=IFF_UP; test_network_v4(link,htonl(0x0a580001),24); md_network_interface_up(link);
            if (pairs[i].family==AF_INET) {
                local.ip=md_ip_v4(htonl(0xef580001));
                struct ip_mreqn request={.imr_multiaddr.s_addr=local.ip.v4,.imr_ifindex=(int)index};
                endpoint_option(pairs[i].objects[0],pairs[i].fds[0],IPPROTO_IP,IP_ADD_MEMBERSHIP,&request,sizeof(request));
                endpoint_option(pairs[i].objects[1],pairs[i].fds[1],IPPROTO_IP,IP_MULTICAST_IF,&request,sizeof(request));
            } else {
                local.ip=md_ip_any(AF_INET6); local.ip.bytes[0]=0xff; local.ip.bytes[1]=2; local.ip.bytes[15]=1;
                struct ipv6_mreq request={.ipv6mr_multiaddr=local.ip.v6,.ipv6mr_interface=index};
                endpoint_option(pairs[i].objects[0],pairs[i].fds[0],IPPROTO_IPV6,IPV6_JOIN_GROUP,&request,sizeof(request));
                endpoint_option(pairs[i].objects[1],pairs[i].fds[1],IPPROTO_IPV6,IPV6_MULTICAST_IF,&index,sizeof(index));
            }
            assert(!md_inet_encode(&local,0,&wire,&length));
        }
        assert(!md_network_endpoint_autobind(pairs[i].objects[1],pairs[i].fds[1],&identity,(void *)&wire,length,0));
        assert(!md_network_endpoint_destination(pairs[i].objects[1],(void *)&wire,length,&native,&native_size));
        assert(!connect(pairs[i].fds[1],(void *)&native,native_size));
        assert(!md_network_endpoint_connected(pairs[i].objects[1],pairs[i].fds[1],(void *)&wire,length));
    }
    for (unsigned round=0;round<2;++round) {
        for (unsigned i=0;i<14;++i) {
            if (round) assert(!connection_count(pairs[i].space));
            if (round && pairs[i].connected) {
                assert(write(pairs[i].fds[0],"b",1)==1);
                struct pollfd ready={.fd=pairs[i].fds[1],.events=POLLIN};
                assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
                char byte;
                assert(read(pairs[i].fds[1],&byte,1)==1 && byte=='b');
            }
            /* Only native connected IO after initial admission: the application
             * does not know that its route/conntrack mapping has expired. */
            assert(write(pairs[i].fds[1],"u",1)==1);
            struct pollfd ready={.fd=pairs[i].fds[0],.events=POLLIN};
            /* EVENT_WAIT: both directions must deliver, without retrying a lost packet. */
            int count=poll(&ready,1,2000);
            if (count!=1) fprintf(stderr,"UDP idle family=%u variant=%u round=%u readiness=%d\n",pairs[i].family,i%7,round,count);
            assert(count==1 && (ready.revents&POLLIN));
            struct sockaddr_storage peer,native; socklen_t peer_size=sizeof(peer),native_size;
            char byte;
            assert(recvfrom(pairs[i].fds[0],&byte,1,0,(void *)&peer,&peer_size)==1 && byte=='u');
            if (pairs[i].group) { assert(!connection_count(pairs[i].space)); continue; }
            if (!round || !pairs[i].connected) {
                assert(!md_network_endpoint_source(pairs[i].objects[0],&peer,&peer_size));
                assert(!md_network_endpoint_destination(pairs[i].objects[0],(void *)&peer,peer_size,&native,&native_size));
                if (pairs[i].connected) {
                    assert(!connect(pairs[i].fds[0],(void *)&native,native_size));
                    assert(!md_network_endpoint_connected(pairs[i].objects[0],pairs[i].fds[0],(void *)&peer,peer_size));
                }
            }
            if (pairs[i].connected) assert(write(pairs[i].fds[0],"r",1)==1);
            else assert(sendto(pairs[i].fds[0],"r",1,0,(void *)&native,native_size)==1);
            ready=(struct pollfd){.fd=pairs[i].fds[1],.events=POLLIN};
            assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
            assert(read(pairs[i].fds[1],&byte,1)==1 && byte=='r');
            assert(connection_count(pairs[i].space)==1);
            if (!round && pairs[i].flush) forget_connections(pairs[i].space,&identity,pairs[i].family);
        }
        /* Protocol delay: cross the actual 30-second UDP idle lifetime once
         * for both families and explicit deletion, without a test-only clock. */
        if (!round) assert(!poll(NULL,0,31000));
    }
    for (unsigned i=0;i<14;++i) {
        for (unsigned j=0;j<2;++j) { close(pairs[i].fds[j]); md_fd_object_release(pairs[i].objects[j]); }
        md_network_namespace_release(pairs[i].space);
    }
    md_identity_release(&identity); md_network_namespace_release(root);
    puts("PASS IPv4/IPv6 UDP: private, published and multicast carriers survive idle expiry and conntrack deletion");
}
static void published_datagram_reuse(unsigned family) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity identity=md_identity_new(0,0);
    struct md_fd_object *objects[3]; int fds[3],one=1;
    assert(n);
    for (unsigned i=0;i<3;++i) {
        assert(!md_network_endpoint_open(n,0,family,SOCK_DGRAM,0,&objects[i],&fds[i]));
        if (i<2) endpoint_option(objects[i],fds[i],SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
    }
    struct md_inet_address local={.ip=md_ip_any(family)};
    struct sockaddr_storage wire,peer; socklen_t length,peer_size;
    assert(!md_inet_encode(&local,0,&wire,&length));
    assert(!md_network_endpoint_bind(objects[0],fds[0],&identity,(void *)&wire,length));
    assert(!md_network_endpoint_name(objects[0],fds[0],0,&wire,&length));
    assert(!md_inet_decode((void *)&wire,length,&local));
    local.ip=md_ip_loopback(family);
    assert(!md_inet_encode(&local,0,&wire,&length));
    assert(md_network_endpoint_bind(objects[2],fds[2],&identity,(void *)&wire,length)==-EADDRINUSE);
    assert(!md_network_endpoint_bind(objects[1],fds[1],&identity,(void *)&wire,length));
    int client=socket(family,SOCK_DGRAM|SOCK_CLOEXEC,0);
    assert(client>=0);
    for (unsigned round=0;round<3;++round) {
        assert(sendto(client,"u",1,0,(void *)&wire,length)==1);
        struct pollfd ready={.fd=fds[1],.events=POLLIN};
        /* EVENT_WAIT: host publication reaches the surviving exact-address
         * endpoint; expiry fails rather than extending network timeouts. */
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        char byte; peer_size=sizeof(peer);
        assert(recvfrom(fds[1],&byte,1,0,(void *)&peer,&peer_size)==1 && byte=='u');
        assert(sendto(fds[1],"r",1,0,(void *)&peer,peer_size)==1);
        ready=(struct pollfd){.fd=client,.events=POLLIN};
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        assert(read(client,&byte,1)==1 && byte=='r');
        if (!round) {
            close(fds[0]); md_fd_object_release(objects[0]);
            if (family==AF_INET) {
                struct md_inet_address absent=local;
                absent.ip=md_ip_v4(htonl(0x7f000002));
                struct sockaddr_storage absent_wire; socklen_t absent_length;
                assert(!md_inet_encode(&absent,0,&absent_wire,&absent_length));
                assert(sendto(client,"x",1,0,(void *)&absent_wire,absent_length)==1);
                ready=(struct pollfd){.fd=fds[1],.events=POLLIN};
                /* EVENT_WAIT: an unmatched destination must not deliver to the
                 * remaining exact-address endpoint during the rejection bound. */
                assert(poll(&ready,1,100)==0);
            }
        }
    }
    close(client);
    /* Keep distinct native clients alive: port reuse must not reduce this
     * burst to fewer retained UDP tuples. TCP shares the same forwarder. */
    int clients[1152];
    for (unsigned i=0;i<sizeof(clients)/sizeof(*clients);++i) {
        clients[i]=socket(family,SOCK_DGRAM|SOCK_CLOEXEC,0); assert(clients[i]>=0);
        assert(sendto(clients[i],"b",1,0,(void *)&wire,length)==1);
        struct pollfd ready={.fd=fds[1],.events=POLLIN};
        /* EVENT_WAIT: this exact datagram and its reply, not a capacity retry. */
        int count=poll(&ready,1,2000);
        if(count!=1) fprintf(stderr,"UDP publication burst family=%u index=%u\n",family,i);
        assert(count==1 && (ready.revents&POLLIN));
        char byte; peer_size=sizeof(peer);
        assert(recvfrom(fds[1],&byte,1,0,(void *)&peer,&peer_size)==1 && byte=='b');
        assert(sendto(fds[1],"r",1,0,(void *)&peer,peer_size)==1);
        ready=(struct pollfd){.fd=clients[i],.events=POLLIN};
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        assert(read(clients[i],&byte,1)==1 && byte=='r');
    }
    struct md_fd_object *stream=NULL; int listener=-1;
    assert(!md_network_endpoint_open(n,0,family,SOCK_STREAM,0,&stream,&listener));
    struct md_inet_address stream_address={.ip=md_ip_loopback(family)};
    struct sockaddr_storage stream_wire; socklen_t stream_size;
    assert(!md_inet_encode(&stream_address,0,&stream_wire,&stream_size));
    assert(!md_network_endpoint_bind(stream,listener,&identity,(void *)&stream_wire,stream_size));
    assert(!md_network_endpoint_name(stream,listener,0,&stream_wire,&stream_size));
    assert(!md_network_endpoint_listen(stream,listener,1));
    int stream_client=socket(family,SOCK_STREAM|SOCK_CLOEXEC,0); assert(stream_client>=0);
    assert(!connect(stream_client,(void *)&stream_wire,stream_size));
    struct pollfd ready={.fd=listener,.events=POLLIN};
    /* EVENT_WAIT: TCP admission still works with all UDP tuples retained. */
    assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
    int accepted=accept4(listener,NULL,NULL,SOCK_CLOEXEC); assert(accepted>=0);
    assert(write(accepted,"t",1)==1);
    ready=(struct pollfd){.fd=stream_client,.events=POLLIN};
    assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
    char byte; assert(read(stream_client,&byte,1)==1 && byte=='t');
    close(accepted); close(stream_client); close(listener); md_fd_object_release(stream);
    assert(connection_count(n)>1024);
    forget_connections(n,&identity,family);
    assert(sendto(clients[0],"b",1,0,(void *)&wire,length)==1);
    ready=(struct pollfd){.fd=fds[1],.events=POLLIN};
    /* EVENT_WAIT: a flushed tuple reconnects through the retained UDP carrier. */
    assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
    peer_size=sizeof(peer);
    assert(recvfrom(fds[1],&byte,1,0,(void *)&peer,&peer_size)==1 && byte=='b');
    assert(sendto(fds[1],"r",1,0,(void *)&peer,peer_size)==1);
    ready=(struct pollfd){.fd=clients[0],.events=POLLIN};
    assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
    assert(read(clients[0],&byte,1)==1 && byte=='r');
    for (unsigned i=0;i<sizeof(clients)/sizeof(*clients);++i) close(clients[i]);
    for (unsigned i=1;i<3;++i) { close(fds[i]); md_fd_object_release(objects[i]); }
    client=socket(family,SOCK_DGRAM|SOCK_CLOEXEC,0);
    assert(client>=0 && !bind(client,(void *)&wire,length)); close(client);
    md_identity_release(&identity); md_network_namespace_release(n);
    printf("PASS IPv%u published UDP reuse: wildcard/exact selection, conflict checks, surviving owner, retained burst and atomic flush\n",family==AF_INET ? 4 : 6);
}
static void multicast(unsigned family) {
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *n=md_network_namespace_copy(root,NULL),*other=md_network_namespace_copy(root,NULL);
    assert(n && other);
    struct md_identity identity=md_identity_new(0,0);
    unsigned index,isolated;
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"multicast",&index));
    assert(!md_network_interface_add(other,MD_LINK_BRIDGE,"isolated",&isolated));
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    struct md_network_interface *away=md_network_interface_find(other,isolated,NULL);
    link->info.flags|=IFF_UP; away->info.flags|=IFF_UP;
    test_network_v4(link,htonl(0x0a580001),24); test_network_v4(away,htonl(0x0a580001),24);
    md_network_interface_up(link); md_network_interface_up(away);
    struct md_inet_address group={.ip=md_ip_v4(htonl(0xe00000fb)),.port=htons(5353)};
    if (family==AF_INET6) { group.ip=md_ip_any(AF_INET6); group.ip.bytes[0]=0xff; group.ip.bytes[1]=2; group.ip.bytes[15]=0xfb; }
    struct md_inet_address any={.ip=md_ip_any(family),.port=group.port};
    struct sockaddr_storage wire; socklen_t length;
    struct md_fd_object *objects[5]; int fds[5];
    int one=1,zero=0;
    for (unsigned i=0;i<5;++i) {
        assert(!md_network_endpoint_open(i==3 ? other : n,0,family,SOCK_DGRAM,0,&objects[i],&fds[i]));
        if (i==4) continue;
        endpoint_option(objects[i],fds[i],SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
        endpoint_option(objects[i],fds[i],family==AF_INET ? IPPROTO_IP : IPPROTO_IPV6,
            family==AF_INET ? IP_MULTICAST_ALL : IPV6_MULTICAST_ALL,&zero,sizeof(zero));
        assert(!md_inet_encode(&any,0,&wire,&length));
        assert(!md_network_endpoint_bind(objects[i],fds[i],&identity,(void *)&wire,length));
        if (i==2) continue;
        unsigned selected=i==3 ? isolated : index;
        if (family==AF_INET) {
            struct ip_mreqn request={.imr_multiaddr.s_addr=group.ip.v4,.imr_ifindex=(int)selected};
            endpoint_option(objects[i],fds[i],IPPROTO_IP,IP_ADD_MEMBERSHIP,&request,sizeof(request));
        } else {
            struct ipv6_mreq request={.ipv6mr_multiaddr=group.ip.v6,.ipv6mr_interface=selected};
            endpoint_option(objects[i],fds[i],IPPROTO_IPV6,IPV6_ADD_MEMBERSHIP,&request,sizeof(request));
        }
    }
    if (family==AF_INET) {
        struct ip_mreqn request={.imr_ifindex=(int)index};
        endpoint_option(objects[4],fds[4],IPPROTO_IP,IP_MULTICAST_IF,&request,sizeof(request));
    } else endpoint_option(objects[4],fds[4],IPPROTO_IPV6,IPV6_MULTICAST_IF,&index,sizeof(index));
    assert(!md_inet_encode(&group,0,&wire,&length));
    assert(!md_network_endpoint_autobind(objects[4],fds[4],&identity,(void *)&wire,length,0));
    struct sockaddr_storage target; socklen_t target_size;
    assert(!md_network_endpoint_destination(objects[4],(void *)&wire,length,&target,&target_size));
    assert(!connect(fds[4],(void *)&target,target_size));
    assert(!md_network_endpoint_connected(objects[4],fds[4],(void *)&wire,length));
    for (unsigned round=0;round<2;++round) {
        assert(write(fds[4],"m",1)==1);
        for (unsigned i=round;i<2;++i) {
            struct pollfd ready={.fd=fds[i],.events=POLLIN};
            assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
            char byte=0; struct sockaddr_storage source; socklen_t source_size=sizeof(source);
            assert(recvfrom(fds[i],&byte,1,0,(void *)&source,&source_size)==1 && byte=='m');
            struct md_network_packet_info info;
            assert(!md_network_endpoint_source_info(objects[i],&source,&source_size,&info));
            assert(info.interface==index && md_ip_equal(info.destination,group.ip) && info.hops_set && info.hops==1);
        }
        struct pollfd quiet[]={{.fd=fds[2],.events=POLLIN},{.fd=fds[3],.events=POLLIN},{.fd=fds[0],.events=POLLIN}};
        assert(!poll(quiet,round ? 3 : 2,30));
        if (!round) {
            if (family==AF_INET) {
                struct ip_mreqn request={.imr_multiaddr.s_addr=group.ip.v4,.imr_ifindex=(int)index};
                endpoint_option(objects[0],fds[0],IPPROTO_IP,IP_DROP_MEMBERSHIP,&request,sizeof(request));
            } else {
                struct ipv6_mreq request={.ipv6mr_multiaddr=group.ip.v6,.ipv6mr_interface=index};
                endpoint_option(objects[0],fds[0],IPPROTO_IPV6,IPV6_DROP_MEMBERSHIP,&request,sizeof(request));
            }
        }
    }
    endpoint_option(objects[4],fds[4],family==AF_INET ? IPPROTO_IP : IPPROTO_IPV6,
        family==AF_INET ? IP_MULTICAST_LOOP : IPV6_MULTICAST_LOOP,&zero,sizeof(zero));
    assert(write(fds[4],"n",1)==1);
    struct pollfd quiet={.fd=fds[1],.events=POLLIN};
    assert(!poll(&quiet,1,50));
    for (unsigned i=0;i<5;++i) { close(fds[i]); md_fd_object_release(objects[i]); }
    md_identity_release(&identity); md_network_namespace_release(other); md_network_namespace_release(n); md_network_namespace_release(root);
    printf("PASS IPv%u UDP multicast: reuse, fanout, leave, loop policy, source metadata and namespace isolation\n",family==AF_INET ? 4 : 6);
}

static void device_binding(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    unsigned first,second;
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"device-a",&first));
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"device-b",&second));
    struct md_network_interface *a=md_network_interface_find(n,first,NULL);
    struct md_network_interface *b=md_network_interface_find(n,second,NULL);
    a->info.flags|=IFF_UP; test_network_v4(a,htonl(0x0a010001),24);
    b->info.flags|=IFF_UP; test_network_v4(b,htonl(0x0a020001),24);
    struct md_fd_object *late; int late_fd;
    assert(!md_network_endpoint_open(n,0,AF_INET,SOCK_DGRAM,0,&late,&late_fd));
    struct sockaddr_in wildcard={.sin_family=AF_INET};
    assert(!md_network_endpoint_bind(late,late_fd,&root,(void *)&wildcard,sizeof(wildcard)));
    struct sockaddr_storage public_address; socklen_t size;
    assert(!md_network_endpoint_name(late,late_fd,0,&public_address,&size));
    assert(!md_network_endpoint_device_set(late,&root,"device-a"));
    int native_fd=socket(AF_INET,SOCK_DGRAM,0);
    assert(native_fd>=0 && !bind(native_fd,(void *)&public_address,size));
    assert(md_network_endpoint_device_set(late,&root,"")==-EADDRINUSE);
    char retained[IFNAMSIZ]; size=sizeof(retained);
    assert(!md_network_endpoint_device_get(late,retained,&size) && !strcmp(retained,"device-a"));
    close(native_fd);
    assert(!md_network_endpoint_device_set(late,&root,""));
    close(late_fd); md_fd_object_release(late);
    struct md_fd_object *one,*two; int f,s;
    assert(!md_network_endpoint_open(n,0,AF_INET,SOCK_DGRAM,0,&one,&f));
    assert(!md_network_endpoint_open(n,0,AF_INET,SOCK_DGRAM,0,&two,&s));
    char name[IFNAMSIZ]; socklen_t length=0;
    assert(!md_network_endpoint_device_get(one,name,&length) && !length);
    assert(md_network_endpoint_device_set(one,&root,"missing")==-ENODEV);
    assert(!md_network_endpoint_device_set(one,&user,"device-a"));
    assert(md_network_endpoint_device_set(one,&user,"")==-EPERM);
    assert(md_network_endpoint_device_get(one,name,&length)==-EINVAL);
    length=sizeof(name);
    assert(!md_network_endpoint_device_get(one,name,&length) && length==9 && !strcmp(name,"device-a"));
    assert(!md_network_endpoint_device_set(one,&root,""));
    assert(!md_network_endpoint_device_set(one,&root,"device-a"));
    int index=-1; size=sizeof(index);
    assert(md_network_endpoint_sockopt(two,s,&root,1,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size)==-EINVAL);
    index=INT_MAX;
    assert(!md_network_endpoint_sockopt(two,s,&user,1,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size));
    index=0;
    assert(md_network_endpoint_sockopt(two,s,&user,1,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size)==-EPERM);
    assert(!md_network_endpoint_sockopt(two,s,&root,0,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size) && index==INT_MAX);
    index=(int)second; size=sizeof(index)-1;
    assert(md_network_endpoint_sockopt(two,s,&root,1,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size)==-EINVAL);
    size=sizeof(index);
    assert(!md_network_endpoint_sockopt(two,s,&root,1,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size));
    index=0;
    assert(!md_network_endpoint_sockopt(two,s,&root,0,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size) && index==(int)second);
    assert(!getsockopt(s,SOL_SOCKET,SO_BINDTOIFINDEX,&index,&size) && !index);
    length=sizeof(name);
    assert(!md_network_endpoint_device_get(two,name,&length) && !strcmp(name,"device-b"));
    struct sockaddr_in any={.sin_family=AF_INET,.sin_port=htons(18670)};
    assert(!md_network_endpoint_bind(one,f,&root,(void *)&any,sizeof(any)));
    assert(!md_network_endpoint_bind(two,s,&root,(void *)&any,sizeof(any)));
    struct md_network_flow_path path={.destination=n->inode,
        .translated={.destination=md_ip_v4(htonl(0x0a010001)),.destination_port=any.sin_port,.input=first}};
    struct md_inet_address native; const void *selected=NULL;
    assert(!md_network_endpoint_resolve(n->fabric,&path,SOCK_DGRAM,&native,&selected));
    assert(selected==one && path.mediated);
    path.translated.input=second;
    assert(!md_network_endpoint_resolve(n->fabric,&path,SOCK_DGRAM,&native,&selected) && selected==two);
    path.translated.input=1;
    assert(md_network_endpoint_resolve(n->fabric,&path,SOCK_DGRAM,&native,&selected)==-ECONNREFUSED);
    struct sockaddr_storage destination;
    any.sin_addr.s_addr=htonl(0x0a020001);
    assert(md_network_endpoint_destination(one,(void *)&any,sizeof(any),&destination,&length)==-ENETUNREACH);
    assert(!md_network_interface_move(n,first,n,"renamed"));
    length=sizeof(name);
    assert(!md_network_endpoint_device_get(one,name,&length) && !strcmp(name,"renamed"));
    assert(!md_network_interface_remove(n,first));
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"renamed",&first));
    length=sizeof(name);
    assert(md_network_endpoint_device_get(one,name,&length)==-ENODEV);
    assert(md_network_endpoint_destination(one,(void *)&any,sizeof(any),&destination,&length)==-ENODEV);
    close(f); close(s); md_fd_object_release(one); md_fd_object_release(two);
    md_identity_release(&root); md_identity_release(&user); md_network_namespace_release(n);
    puts("PASS socket device binding: shared endpoint identity, permissions, routing, ingress, rename and removal");
}

static void socket_priority(unsigned family) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_fd_object *parent; int fd;
    assert(!md_network_endpoint_open(n,0,family,SOCK_STREAM,0,&parent,&fd));
    int value=-1; socklen_t size=sizeof(value);
    assert(!md_network_endpoint_sockopt(parent,fd,&user,0,SOL_SOCKET,SO_PRIORITY,&value,&size) && !value);
    value=6;
    assert(!md_network_endpoint_sockopt(parent,fd,&user,1,SOL_SOCKET,SO_PRIORITY,&value,&size));
    value=7;
    assert(md_network_endpoint_sockopt(parent,fd,&user,1,SOL_SOCKET,SO_PRIORITY,&value,&size)==-EPERM);
    assert(!md_network_endpoint_sockopt(parent,fd,&user,0,SOL_SOCKET,SO_PRIORITY,&value,&size) && value==6);
    value=-1;
    assert(md_network_endpoint_sockopt(parent,fd,&user,1,SOL_SOCKET,SO_PRIORITY,&value,&size)==-EPERM);
    assert(!md_network_endpoint_sockopt(parent,fd,&root,1,SOL_SOCKET,SO_PRIORITY,&value,&size));
    value=7; size=sizeof(value)-1;
    assert(md_network_endpoint_sockopt(parent,fd,&root,1,SOL_SOCKET,SO_PRIORITY,&value,&size)==-EINVAL);
    size=sizeof(value);
    assert(!md_network_endpoint_sockopt(parent,fd,&root,1,SOL_SOCKET,SO_PRIORITY,&value,&size));
    value=0; size=1;
    assert(!md_network_endpoint_sockopt(parent,fd,&user,0,SOL_SOCKET,SO_PRIORITY,&value,&size) && size==1 && value==7);
    size=sizeof(value);
    assert(!getsockopt(fd,SOL_SOCKET,SO_PRIORITY,&value,&size) && !value);

    struct md_inet_address address={.ip=md_ip_loopback(family)};
    struct sockaddr_storage logical; socklen_t length;
    assert(!md_inet_encode(&address,family,&logical,&length));
    assert(!md_network_endpoint_bind(parent,fd,&root,(void *)&logical,length));
    assert(!md_network_endpoint_listen(parent,fd,1));
    assert(!md_inet_name(fd,0,&address));
    int client=socket(family,SOCK_STREAM|SOCK_CLOEXEC,0);
    assert(client>=0 && !md_inet_connect(client,&address,0));
    int accepted=accept4(fd,NULL,NULL,SOCK_CLOEXEC);
    struct md_fd_object *child;
    assert(accepted>=0 && !md_network_endpoint_accept(parent,accepted,&child));
    assert(!md_network_endpoint_sockopt(child,accepted,&user,0,SOL_SOCKET,SO_PRIORITY,&value,&size) && value==7);
    close(accepted); close(client); close(fd);
    md_fd_object_release(child); md_fd_object_release(parent);
    md_identity_release(&root); md_identity_release(&user); md_network_namespace_release(n);
    puts("PASS socket priority: namespace permissions, readback, accept inheritance and unchanged host priority");
}

static void tcp_defaults(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    const int options[]={TCP_KEEPIDLE,TCP_KEEPINTVL,TCP_KEEPCNT};
    const uint32_t defaults[]={7200,75,9},selected[]={1800,30,4};
    uint32_t values[2]={0};
    for (unsigned i=0;i<3;++i) {
        enum md_network_parameter kind=MD_NET_TCP_KEEPIDLE+i;
        assert(!md_network_parameter_read(n,kind,0,values) && values[0]==defaults[i]);
        values[0]=0;
        assert(md_network_parameter_write(n,kind,0,values)==-EOPNOTSUPP);
        values[0]=i==2 ? 256 : UINT32_MAX;
        assert(md_network_parameter_write(n,kind,0,values)==-EINVAL);
        values[0]=i==2 ? 128 : 32768;
        assert(md_network_parameter_write(n,kind,0,values)==-EOPNOTSUPP);
        values[0]=selected[i];
        assert(!md_network_parameter_write(n,kind,0,values));
    }
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL);
    assert(child);
    assert(!md_network_parameter_read(child,MD_NET_TCP_KEEPIDLE,0,values) && values[0]==7200);
    struct md_fd_object *tcp,*udp; int fd,datagram;
    assert(!md_network_endpoint_open(n,0,AF_INET,SOCK_DGRAM,0,&udp,&datagram));
    assert(!md_fd_object_selectors(udp));
    values[0]=1801;
    assert(!md_network_parameter_write(n,MD_NET_TCP_KEEPIDLE,0,values));
    values[0]=1800;
    assert(!md_network_parameter_write(n,MD_NET_TCP_KEEPIDLE,0,values));
    assert(!md_network_endpoint_open(n,0,AF_INET,SOCK_STREAM,0,&tcp,&fd));
    assert(md_fd_object_selectors(tcp)==((1U<<MD_FD_SELECTOR_EPOLL_TARGET)|(1U<<MD_FD_SELECTOR_POLL)));
    for (unsigned i=0;i<3;++i) {
        int value=-1; socklen_t length=sizeof(value);
        assert(!getsockopt(fd,IPPROTO_TCP,options[i],&value,&length) && value==(int)selected[i]);
        values[0]=selected[i];
        assert(!md_network_parameter_write(n,MD_NET_TCP_KEEPIDLE+i,0,values));
        ++values[0];
        assert(md_network_parameter_write(n,MD_NET_TCP_KEEPIDLE+i,0,values)==-EBUSY);
        assert(!md_network_parameter_read(n,MD_NET_TCP_KEEPIDLE+i,0,values) && values[0]==selected[i]);
    }
    values[0]=3600;
    assert(!md_network_parameter_write(child,MD_NET_TCP_KEEPIDLE,0,values));
    close(fd); md_fd_object_release(tcp);
    assert(!md_network_parameter_write(n,MD_NET_TCP_KEEPIDLE,0,values));
    close(datagram); md_fd_object_release(udp);
    md_network_namespace_release(child); md_network_namespace_release(n);
    puts("PASS TCP namespace defaults: native options, independent namespaces, atomic admission and close");
}

static void unspecified_destination(unsigned family,int type) {
    struct md_network_namespace *spaces[2]={md_network_namespace_new(NULL,MD_NETWORK_ROUTED)};
    spaces[1]=md_network_namespace_copy(spaces[0],NULL);
    struct md_identity identity=md_identity_new(0,0);
    struct md_fd_object *servers[2]; int fds[2];
    struct md_inet_address target={.ip=md_ip_any(family)};
    struct sockaddr_storage wire,native; socklen_t length,size;
    for (unsigned i=0;i<2;++i) {
        assert(spaces[i]);
        struct md_network_interface *loop=md_network_interface_find(spaces[i],0,"lo");
        loop->info.flags|=IFF_UP; md_network_interface_up(loop);
        assert(!md_network_endpoint_open(spaces[i],0,family,type,0,&servers[i],&fds[i]));
        assert(!md_inet_encode(&target,family,&wire,&length));
        assert(!md_network_endpoint_bind(servers[i],fds[i],&identity,(void *)&wire,length));
        if (type==SOCK_STREAM) assert(!md_network_endpoint_listen(servers[i],fds[i],1));
        assert(!md_network_endpoint_name(servers[i],fds[i],0,&wire,&length));
        assert(!md_inet_decode((void *)&wire,length,&target));
    }
    for (unsigned i=0;i<2;++i) {
        struct md_fd_object *client; int fd;
        assert(!md_network_endpoint_open(spaces[i],0,family,type,0,&client,&fd));
        assert(!md_inet_encode(&target,family,&wire,&length));
        assert(!md_network_endpoint_autobind(client,fd,&identity,(void *)&wire,length,0));
        int error=md_network_endpoint_destination(client,(void *)&wire,length,&native,&size);
        if (error) fprintf(stderr,"unspecified destination family=%u type=%d namespace=%u: %d\n",family,type,i,error);
        assert(!error);
        assert(!connect(fd,(void *)&native,size));
        assert(!md_network_endpoint_connected(client,fd,(void *)&wire,length));
        assert(!md_network_endpoint_name(client,fd,1,&native,&size));
        struct md_inet_address actual;
        assert(!md_inet_decode((void *)&native,size,&actual) && md_ip_is_loopback(actual.ip));
        assert(write(fd,"x",1)==1);
        struct pollfd ready[2]={{.fd=fds[0],.events=POLLIN},{.fd=fds[1],.events=POLLIN}};
        /* EVENT_WAIT: only this namespace's listener must receive the request. */
        assert(poll(ready,2,2000)==1 && ready[i].revents==POLLIN && !ready[1-i].revents);
        int peer=type==SOCK_STREAM ? accept4(fds[i],NULL,NULL,SOCK_CLOEXEC) : fds[i];
        char byte;
        assert(peer>=0 && read(peer,&byte,1)==1 && byte=='x');
        if (type==SOCK_STREAM) close(peer);
        close(fd); md_fd_object_release(client);
    }
    for (unsigned i=0;i<2;++i) { close(fds[i]); md_fd_object_release(servers[i]); }
    md_identity_release(&identity);
    md_network_namespace_release(spaces[1]); md_network_namespace_release(spaces[0]);
    printf("PASS unspecified IPv%u destination: type=%d, namespace isolation and peer identity\n",family==AF_INET ? 4 : 6,type);
}

void test_network_endpoint(void) {
    unspecified_destination(AF_INET,SOCK_STREAM); unspecified_destination(AF_INET6,SOCK_STREAM);
    unspecified_destination(AF_INET,SOCK_DGRAM); unspecified_destination(AF_INET6,SOCK_DGRAM);
    forwarded_connect(AF_INET);
    forwarded_connect(AF_INET6);
    multicast(AF_INET);
    multicast(AF_INET6);
    tcp_defaults();
    tcp_reuse(AF_INET); tcp_reuse(AF_INET6);
    datagram_handoff(AF_INET); datagram_handoff(AF_INET6);
    for (unsigned bound=0;bound<2;++bound) {
        datagram_namespace_retirement(AF_INET,bound); datagram_namespace_retirement(AF_INET6,bound);
    }
    datagram_idle();
    published_datagram_reuse(AF_INET); published_datagram_reuse(AF_INET6);
    device_binding();
    socket_priority(AF_INET);
    socket_priority(AF_INET6);
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *a=md_network_namespace_copy(root,NULL), *b=md_network_namespace_copy(root,NULL);
    struct md_network_namespace *offline=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    assert(root && a && b && offline);
    struct md_identity identity=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_fd_object *first=NULL,*second=NULL,*conflict=NULL;
    int f=-1,s=-1,c=-1;
    assert(md_network_endpoint_open(offline,0,AF_INET,SOCK_STREAM,0,&first,&f)==-EOPNOTSUPP);
    assert(!md_network_endpoint_open(a,0,AF_INET6,SOCK_STREAM,0,&first,&f));
    close(f); md_fd_object_release(first);
    assert(!md_network_endpoint_open(a,1000,AF_INET,SOCK_STREAM,0,&first,&f));
    assert(!md_network_endpoint_open(b,0,AF_INET,SOCK_STREAM,0,&second,&s));
    assert(!md_network_endpoint_open(a,0,AF_INET,SOCK_STREAM,0,&conflict,&c));
    struct sockaddr_in local={.sin_family=AF_INET,.sin_port=htons(80)};
    assert(md_network_endpoint_bind(first,f,&user,(void *)&local,sizeof(local))==-EACCES);
    assert(!md_network_endpoint_bind(first,f,&identity,(void *)&local,sizeof(local)));
    assert(!md_network_endpoint_bind(second,s,&identity,(void *)&local,sizeof(local)));
    assert(md_network_endpoint_bind(conflict,c,&identity,(void *)&local,sizeof(local))==-EADDRINUSE);
    struct sockaddr_in real_a,real_b; socklen_t length=sizeof(real_a);
    assert(!getsockname(f,(void *)&real_a,&length)); length=sizeof(real_b);
    assert(!getsockname(s,(void *)&real_b,&length) && real_a.sin_port!=real_b.sin_port);
    assert(!md_network_endpoint_listen(first,f,1));
    assert(!md_network_endpoint_listen(second,s,1));
    struct md_network_endpoint_info *entries=NULL;
    size_t count=0;
    assert(!md_network_endpoint_snapshot(a,IPPROTO_TCP,&entries,&count));
    struct stat st,other;
    assert(!fstat(f,&st) && !fstat(s,&other));
    assert(count==1 && entries[0].inode==st.st_ino && entries[0].uid==1000
        && entries[0].local.port==htons(80) && entries[0].state==TCP_LISTEN);
    free(entries);
    struct md_control_file table=md_network_proc_new(a,MD_NET_TABLE_TCP);
    assert(table.ops);
    struct md_control_actor actor={.identity=&identity};
    char text[4096]={0};
    long bytes=table.ops->read(table.context,&actor,text,sizeof(text)-1,0);
    assert(bytes>0 && strstr(text,"00000000:0050"));
    assert(table_has_inode(text,st.st_ino));
    assert(!table_has_inode(text,other.st_ino));
    assert(strstr(text," 0A "));
    assert(table.ops->read(table.context,&actor,text,sizeof(text),bytes)==0);
    assert(table.ops->access(table.context,&identity,W_OK)==-EACCES);
    void *reopened=table.ops->open(table.context,&(struct md_control_opener){.identity=&identity},O_RDONLY);
    assert(reopened && reopened!=table.context);
    table.ops->release(reopened); md_control_file_release(table);
    table=md_network_proc_new(a,MD_NET_TABLE_TCP6);
    assert(table.ops);
    bytes=table.ops->read(table.context,&actor,text,sizeof(text)-1,0);
    assert(bytes>0); text[bytes]=0;
    assert(strchr(text,'\n')==text+bytes-1);
    md_control_file_release(table);
    table=md_network_proc_new(offline,MD_NET_TABLE_TCP6);
    assert(table.ops);
    bytes=table.ops->read(table.context,&actor,text,sizeof(text)-1,0);
    assert(bytes>0); text[bytes]=0;
    assert(strchr(text,'\n')==text+bytes-1);
    md_control_file_release(table);
    struct sockaddr_storage logical;
    assert(!md_network_endpoint_name(first,f,0,&logical,&length));
    assert(((struct sockaddr_in *)&logical)->sin_port==htons(80));
    struct sockaddr_in internet=real_b;
    internet.sin_addr.s_addr=htonl(0xcb007109);
    memcpy(&logical,&internet,sizeof(internet)); length=sizeof(internet);
    assert(!md_network_endpoint_source(first,&logical,&length));
    assert(!memcmp(&logical,&internet,sizeof(internet)));
    struct sockaddr_in remote={.sin_family=AF_INET,.sin_port=htons(443),.sin_addr.s_addr=htonl(0x01010101)};
    assert(md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length)==-ENETUNREACH);
    unsigned bridge,inside,outside;
    assert(!md_network_interface_add(root,MD_LINK_BRIDGE,"forward-bridge",&bridge));
    assert(!md_network_interface_add(root,MD_LINK_VETH,"forward-out",&outside));
    assert(!md_network_interface_add(a,MD_LINK_VETH,"forward-in",&inside));
    struct md_network_interface *in=md_network_interface_find(a,inside,NULL);
    struct md_network_interface *out=md_network_interface_find(root,outside,NULL);
    in->info.flags|=IFF_UP; out->info.flags|=IFF_UP;
    test_network_v4(in,htonl(0x0a580002),16);
    in->peer=outside; out->peer=inside; out->master=bridge;
    md_network_interface_find(root,bridge,NULL)->info.flags|=IFF_UP;
    a->routes[a->route_count++]=(struct md_network_route_entry){.destination=md_ip_any(AF_INET),
        .gateway=md_ip_any(AF_INET),.interface=inside};
    assert(!md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length));
    uint32_t filter[2]={1};
    uint64_t scope;
    assert(!md_network_parameter_scope(root,MD_NET_RP_FILTER,"forward-bridge",&scope));
    assert(!md_network_parameter_write(root,MD_NET_RP_FILTER,scope,filter));
    assert(md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length)==-ENETUNREACH);
    filter[0]=2;
    assert(!md_network_parameter_write(root,MD_NET_RP_FILTER,scope,filter));
    assert(!md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length));
    struct md_network_interface *gateway=md_network_interface_find(root,bridge,NULL);
    test_network_v4(gateway,htonl(0x0a580001),16);
    filter[0]=1;
    assert(!md_network_parameter_write(root,MD_NET_RP_FILTER,scope,filter));
    assert(!md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length));
    assert(md_network_ingress(root,gateway,md_ip_v4(htonl(INADDR_LOOPBACK)),md_ip_v4(remote.sin_addr.s_addr),0)==-ENETUNREACH);
    filter[0]=2;
    assert(!md_network_parameter_write(root,MD_NET_RP_FILTER,scope,filter));
    uint32_t localnet[2]={1};
    assert(!md_network_parameter_write(root,MD_NET_ROUTE_LOCALNET,scope,localnet));
    assert(!md_network_ingress(root,gateway,md_ip_v4(htonl(INADDR_LOOPBACK)),md_ip_v4(remote.sin_addr.s_addr),0));
    uint32_t forward[2]={0};
    assert(!md_network_parameter_write(root,MD_NET_IPV4_FORWARD,0,forward));
    assert(md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length)==-ENETUNREACH);
    struct md_fd_object *host=NULL; int hostfd=-1;
    assert(!md_network_endpoint_open(root,0,AF_INET,SOCK_STREAM,0,&host,&hostfd));
    assert(!md_network_endpoint_destination(host,(void *)&remote,sizeof(remote),&logical,&length));
    close(hostfd); md_fd_object_release(host);
    forward[0]=1;
    assert(!md_network_parameter_write(root,MD_NET_IPV4_FORWARD,0,forward));
    assert(!md_network_endpoint_destination(first,(void *)&remote,sizeof(remote),&logical,&length));
    /* Retaining provenance does not retain the application's OFD. */
    close(f); close(s); close(c);
    assert(fcntl(f,F_GETFD)==-1 && errno==EBADF);
    md_fd_object_release(first); md_fd_object_release(second); md_fd_object_release(conflict);
    assert(!root->fabric->endpoints);
    uint32_t ports[2]={49100,49101};
    assert(!md_network_parameter_write(a,MD_NET_LOCAL_PORTS,0,ports));
    assert(!md_network_endpoint_open(a,0,AF_INET,SOCK_STREAM,0,&first,&f));
    assert(!md_network_endpoint_open(a,0,AF_INET,SOCK_STREAM,0,&second,&s));
    assert(!md_network_endpoint_open(a,0,AF_INET,SOCK_STREAM,0,&conflict,&c));
    local.sin_port=0;
    assert(!md_network_endpoint_bind(first,f,&identity,(void *)&local,sizeof(local)));
    assert(!md_network_endpoint_bind(second,s,&identity,(void *)&local,sizeof(local)));
    assert(md_network_endpoint_bind(conflict,c,&identity,(void *)&local,sizeof(local))==-EADDRINUSE);
    assert(!md_network_endpoint_name(first,f,0,&logical,&length));
    assert(ntohs(((struct sockaddr_in *)&logical)->sin_port)==49100);
    assert(!md_network_endpoint_name(second,s,0,&logical,&length));
    assert(ntohs(((struct sockaddr_in *)&logical)->sin_port)==49101);
    close(f); md_fd_object_release(first);
    assert(!md_network_endpoint_bind(conflict,c,&identity,(void *)&local,sizeof(local)));
    close(s); close(c); md_fd_object_release(second); md_fd_object_release(conflict);
    md_network_namespace_release(a); md_network_namespace_release(b);
    md_network_namespace_release(root); md_network_namespace_release(offline);
    md_identity_release(&identity); md_identity_release(&user);
    puts("PASS network endpoints: namespace port reuse, conflicts, credentials, offline routing and OFD custody");
}
