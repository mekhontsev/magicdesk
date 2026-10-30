#define _GNU_SOURCE
#include "network_tun.h"
#include "network_stack_internal.h"
#include "network_endpoint.h"
#include "network_route.h"
#include "network_ioctl.h"
#include "guest_identity.h"
#include "network_packet.h"
#include "test_network_fixture.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

struct cable {
    struct md_control_file tun[2];
    int stop;
    pthread_t thread;
};
static void *cable_run(void *context) {
    struct cable *c=context;
    struct pollfd fds[3]={{.events=POLLIN},{.events=POLLIN},{.fd=c->stop,.events=POLLIN}};
    for(unsigned i=0;i<2;++i) assert(!c->tun[i].ops->poll_descriptor(c->tun[i].context,&fds[i].fd));
    unsigned char packet[65536];
    for(;;) {
        /* EVENT_WAIT: packet readiness or explicit test shutdown. */
        assert(poll(fds,3,5000)>0);
        if(fds[2].revents) return NULL;
        for(unsigned i=0;i<2;++i) {
            struct md_control_file a=c->tun[i],b=c->tun[1-i];
            a.ops->poll(a.context);
            for(unsigned budget=0;budget<64;++budget) {
                long size=a.ops->read(a.context,NULL,packet,sizeof(packet),0);
                if(size==-EAGAIN) break;
                assert(size>0); a.ops->read_commit(a.context,size);
                assert(b.ops->write(b.context,NULL,packet,size,0)==size);
            }
        }
    }
}
static long control(struct md_control_file f,struct md_identity *id,unsigned command,unsigned long value,void *data) {
    struct md_control_ioctl spec;
    assert(!f.ops->ioctl_spec(f.context,command,&spec));
    struct md_control_actor actor={.identity=id};
    return f.ops->ioctl(f.context,&actor,command,value,data,spec.size);
}
static struct md_control_file open_tun(struct md_network_namespace *n,struct md_identity *id) {
    struct md_control_file node={0}; assert(!md_tun_device_node(&node));
    struct md_control_opener opener={.identity=id,.network=n};
    struct md_control_file f={.ops=node.ops,.context=node.ops->open(node.context,&opener,O_RDWR|O_NONBLOCK)};
    assert(f.context); md_control_file_release(node); return f;
}
static void packet_contract(void) {
    struct md_identity root=md_identity_new(0,0);
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    struct md_control_file f=open_tun(n,&root);
    unsigned features=0;
    assert(!control(f,&root,TUNGETFEATURES,0,&features));
    assert(features==(IFF_TUN|IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE));
    struct ifreq req={.ifr_name="tun%d",.ifr_flags=IFF_TUN};
    assert(!control(f,&root,TUNSETIFF,0,&req));
    assert(control(f,&root,TUNSETOFFLOAD,TUN_F_CSUM,NULL)==-EOPNOTSUPP);
    assert(!control(f,&root,TUNSETOFFLOAD,0,NULL));
    unsigned index=md_network_interface_find(n,0,req.ifr_name)->info.index;
    unsigned char packet[32]={0x45},copy[sizeof(struct tun_pi)+sizeof(packet)];
    pthread_mutex_lock(&n->fabric->lock);
    assert(!md_network_tun_output(n,index,packet,sizeof(packet)));
    pthread_mutex_unlock(&n->fabric->lock);
    assert(f.ops->poll(f.context)&POLLIN);
    assert(f.ops->read(f.context,NULL,copy,2,0)==-EINVAL);
    assert(f.ops->read(f.context,NULL,copy,sizeof(copy),0)==sizeof(copy));
    struct tun_pi pi; memcpy(&pi,copy,sizeof(pi));
    assert(!pi.flags && pi.proto==htons(0x0800));
    assert(!memcmp(copy+sizeof(pi),packet,sizeof(packet)));
    f.ops->read_commit(f.context,0);
    assert(f.ops->read(f.context,NULL,copy,8,0)==8);
    memcpy(&pi,copy,sizeof(pi)); assert(pi.flags==TUN_PKT_STRIP);
    f.ops->read_commit(f.context,8);
    assert(!(f.ops->poll(f.context)&POLLIN));
    assert(f.ops->read(f.context,NULL,copy,sizeof(copy),0)==-EAGAIN);
    assert(f.ops->write(f.context,NULL,copy,sizeof(copy),0)==-EIO);
    int carrier=0; assert(!control(f,&root,TUNSETCARRIER,0,&carrier));
    pthread_mutex_lock(&n->fabric->lock);
    assert(md_network_tun_output(n,index,packet,sizeof(packet))==-ENETDOWN);
    pthread_mutex_unlock(&n->fabric->lock);
    struct md_control_file alias=md_control_file_retain(f);
    md_control_file_release(f); md_network_namespace_release(n);
    assert(!control(alias,&root,TUNGETIFF,0,&req));
    assert(!strcmp(req.ifr_name,"tun0"));
    md_control_file_release(alias); md_identity_release(&root);
    puts("PASS TUN packet ABI: PI, truncation, failed copy retry, readiness, carrier and retained namespace");
}
static void lifecycle(void) {
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    struct md_control_file f=open_tun(n,&root);
    unsigned char data[32];
    assert(f.ops->read(f.context,NULL,data,sizeof(data),0)==-EBADFD);
    struct ifreq req={.ifr_name="tap%d",.ifr_flags=IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE};
    assert(control(f,&user,TUNSETIFF,0,&req)==-EPERM);
    assert(!control(f,&root,TUNSETIFF,0,&req));
    assert(!strcmp(req.ifr_name,"tap0"));
    assert(!md_network_route_set_flags(n,&root,"lo",IFF_UP|IFF_RUNNING));
    assert(!md_network_route_set_flags(n,&root,"tap0",IFF_UP|IFF_RUNNING));
    assert(!(md_network_interface_find(n,0,"tap0")->info.flags&IFF_MULTICAST));
    struct ifreq configuration={.ifr_name="tap0"};
    struct sockaddr_in ip={.sin_family=AF_INET,.sin_addr.s_addr=htonl(0x0a000264)};
    memcpy(&configuration.ifr_addr,&ip,sizeof(ip));
    assert(!md_network_route_address_ioctl(n,&root,SIOCSIFADDR,&configuration));
    ip.sin_addr.s_addr=htonl(0xffffff00);
    memcpy(&configuration.ifr_addr,&ip,sizeof(ip));
    assert(!md_network_route_address_ioctl(n,&root,SIOCSIFNETMASK,&configuration));
    assert(!md_network_route_address_ioctl(n,&root,SIOCGIFNETMASK,&configuration));
    memcpy(&ip,&configuration.ifr_addr,sizeof(ip)); assert(ip.sin_addr.s_addr==htonl(0xffffff00));
    struct md_network_route_ioctl route={.device="tap0",.route={.rt_flags=RTF_UP|RTF_GATEWAY}};
    ip.sin_addr.s_addr=0;
    memcpy(&route.route.rt_dst,&ip,sizeof(ip)); memcpy(&route.route.rt_genmask,&ip,sizeof(ip));
    ip.sin_addr.s_addr=htonl(0x0a000202); memcpy(&route.route.rt_gateway,&ip,sizeof(ip));
    assert(!md_network_route_legacy(n,&root,SIOCADDRT,&route));
    assert(md_network_route_find(n,md_ip_v4(htonl(0x01010101)),NULL)==md_network_interface_find(n,0,"tap0"));
    assert(!md_network_route_legacy(n,&root,SIOCDELRT,&route));
    assert(control(f,&root,TUNSETIFF,0,&req)==-EEXIST);
    assert(!control(f,&root,TUNSETOWNER,1000,NULL));
    assert(!control(f,&root,TUNSETPERSIST,1,NULL));
    struct md_control_file second=open_tun(n,&user);
    assert(!control(second,&user,TUNSETIFF,0,&req));
    req.ifr_flags=IFF_DETACH_QUEUE;
    assert(!control(second,&user,TUNSETQUEUE,0,&req));
    assert(second.ops->read(second.context,NULL,data,sizeof(data),0)==-EBADFD);
    req.ifr_flags=IFF_ATTACH_QUEUE;
    assert(!control(second,&user,TUNSETQUEUE,0,&req));
    md_control_file_release(second); md_control_file_release(f);
    assert(md_network_interface_find(n,0,"tap0"));
    f=open_tun(n,&root); req.ifr_flags=IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE;
    assert(!control(f,&root,TUNSETIFF,0,&req));
    assert(!control(f,&root,TUNSETPERSIST,0,NULL));
    md_control_file_release(f);
    assert(!md_network_interface_find(n,0,"tap0"));
    md_network_namespace_release(n); md_identity_release(&root); md_identity_release(&user);
}
static void retiring_destination(void) {
    struct md_identity root=md_identity_new(0,0);
    struct md_network_namespace *parent=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *child=md_network_namespace_copy(parent,NULL);
    struct md_control_file tun=open_tun(child,&root);
    struct ifreq request={.ifr_name="tun0",.ifr_flags=IFF_TUN|IFF_NO_PI};
    assert(!control(tun,&root,TUNSETIFF,0,&request));
    struct md_fd_object *endpoint; int fd;
    assert(!md_network_endpoint_open(parent,0,AF_INET,SOCK_STREAM,0,&endpoint,&fd));
    pthread_mutex_lock(&parent->fabric->lock);
    struct md_network_interface *link=md_network_interface_find(child,0,"tun0"); assert(link);
    link->info.flags|=IFF_UP;
    struct md_ip_address local=md_ip_v4(htonl(0x0a170001)),remote=md_ip_v4(htonl(0x0a170002));
    assert(!md_network_interface_address(link,(struct md_network_ip_assignment){.address=local,.prefix=24,.flags=IFA_F_PERMANENT},0,0));
    md_network_forward_changed(parent->fabric);
    struct md_network_flow_path path={.packet_space=child->inode,.packet_output=link->info.index,
        .original={.source=local,.destination=remote,.source_port=htons(32000),.destination_port=htons(1234),.protocol=IPPROTO_TCP}};
    path.translated=path.original;
    struct md_inet_address client={.ip=local,.port=path.original.source_port};
    struct md_inet_address target={.ip=remote,.port=path.original.destination_port},destination;
    assert(!md_network_forward_route(parent,endpoint,SOCK_STREAM,&client,&target,&path,&destination));
    assert(md_network_endpoint_connect_result(endpoint,NULL)==-EINPROGRESS);
    pthread_mutex_unlock(&parent->fabric->lock);
    /* The caller has not connected its carrier yet. Retiring the destination
     * must cancel the passive relay and its cross-namespace channel together. */
    md_control_file_release(tun); md_network_namespace_release(child);
    assert(md_network_endpoint_connect_result(endpoint,NULL)==-ENETUNREACH);
    close(fd); md_fd_object_release(endpoint);
    md_network_namespace_release(parent); md_identity_release(&root);
    puts("PASS packet destination retirement: pending cross-namespace connect, error delivery and channel lifetime");
}
static err_t echo_tcp(void *context,struct tcp_pcb *pcb,struct pbuf *p,err_t error) {
    (void)context; assert(!error);
    if(!p) { tcp_close(pcb); return ERR_OK; }
    for(struct pbuf *q=p;q;q=q->next) assert(tcp_write(pcb,q->payload,q->len,TCP_WRITE_FLAG_COPY)==ERR_OK);
    tcp_recved(pcb,p->tot_len); pbuf_free(p); tcp_output(pcb); return ERR_OK;
}
static err_t accept_tcp(void *context,struct tcp_pcb *pcb,err_t error) {
    (void)context; assert(!error); tcp_recv(pcb,echo_tcp); return ERR_OK;
}
static void echo_udp(void *context,struct udp_pcb *pcb,struct pbuf *p,const ip_addr_t *address,u16_t port) {
    (void)context;
    assert(udp_sendto(pcb,p,address,port)==ERR_OK); pbuf_free(p);
}
struct native_echo { int fd, type; pthread_t thread; };
static void *native_echo_run(void *context) {
    struct native_echo *s=context;
    int fd=s->fd;
    if(s->type==SOCK_STREAM) {
        struct pollfd ready={.fd=fd,.events=POLLIN};
        /* EVENT_WAIT: remote SYN is bridged to the native listener. */
        assert(poll(&ready,1,5000)==1);
        fd=accept(fd,NULL,NULL); assert(fd>=0);
    }
    unsigned char data[8192];
    size_t total=0;
    while(total<8*sizeof(data)) {
        struct pollfd ready={.fd=fd,.events=POLLIN};
        /* EVENT_WAIT: packet-stack payload reaches an ordinary socket. */
        assert(poll(&ready,1,5000)==1);
        struct sockaddr_storage peer; socklen_t size=sizeof(peer);
        ssize_t bytes=recvfrom(fd,data,sizeof(data),0,(void *)&peer,&size); assert(bytes>0);
        ssize_t sent=s->type==SOCK_STREAM ? send(fd,data,(size_t)bytes,MSG_NOSIGNAL)
            : sendto(fd,data,(size_t)bytes,MSG_NOSIGNAL,(void *)&peer,size);
        assert(sent==bytes); total+=(size_t)bytes;
    }
    if(fd!=s->fd) close(fd);
    return NULL;
}
static void cable_setup(struct cable *cable,struct md_identity *root,struct md_network_namespace *spaces[2],
        struct md_ip_address addresses[2],int tap,int ipv6) {
    cable->stop=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(cable->stop>=0);
    for(unsigned k=0;k<2;++k) {
        spaces[k]=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE); assert(spaces[k]);
        cable->tun[k]=open_tun(spaces[k],root);
        struct ifreq req={.ifr_flags=(short)((tap ? IFF_TAP : IFF_TUN)|IFF_NO_PI)};
        assert(!control(cable->tun[k],root,TUNSETIFF,0,&req));
        pthread_mutex_lock(&spaces[k]->fabric->lock);
        struct md_network_interface *link=md_network_interface_find(spaces[k],0,req.ifr_name); assert(link);
        link->info.flags|=IFF_UP; link->info.mtu=1280;
        addresses[k]=md_ip_v4(htonl(0x0a170001+k));
        if(ipv6) { addresses[k]=md_ip_any(AF_INET6); addresses[k].bytes[0]=0xfd; addresses[k].bytes[1]=0x23; addresses[k].bytes[15]=(unsigned char)(k+1); }
        assert(!md_network_interface_address(link,(struct md_network_ip_assignment){.address=addresses[k],.prefix=ipv6 ? 64 : 24,.flags=IFA_F_PERMANENT},0,0));
        spaces[k]->routes[spaces[k]->route_count++]=(struct md_network_route_entry){.destination=md_ip_network(addresses[k],ipv6 ? 64 : 24),
            .prefix=ipv6 ? 64 : 24,.gateway=md_ip_any(addresses[k].family),.interface=link->info.index};
        md_network_forward_changed(spaces[k]->fabric);
        pthread_mutex_unlock(&spaces[k]->fabric->lock);
    }
}
static void cable_close(struct cable *cable,struct md_network_namespace *spaces[2]) {
    uint64_t stop=1; assert(write(cable->stop,&stop,sizeof(stop))==sizeof(stop));
    assert(!pthread_join(cable->thread,NULL)); close(cable->stop);
    for(unsigned i=0;i<2;++i) { md_control_file_release(cable->tun[i]); md_network_namespace_release(spaces[i]); }
}
static void transfer(int tap,int ipv6,int type,int native_server) {
    struct md_identity root=md_identity_new(0,0);
    struct md_network_namespace *spaces[2];
    struct cable cable={0};
    struct md_ip_address addresses[2];
    cable_setup(&cable,&root,spaces,addresses,tap,ipv6);
    struct md_network_namespace *server=spaces[1],*client=spaces[0];
    struct md_fd_object *observer=NULL; int raw=-1;
    if(type==SOCK_DGRAM) assert(!md_network_socket_open(client,&root,addresses[0].family,SOCK_RAW,IPPROTO_UDP,&observer,&raw));
    struct md_fd_object *server_endpoint=NULL;
    struct native_echo echo={.fd=-1,.type=type};
    if(native_server) {
        assert(!md_network_endpoint_open(server,0,addresses[1].family,type,0,&server_endpoint,&echo.fd));
        struct md_inet_address target={.ip=addresses[1],.port=htons(40000)};
        struct sockaddr_storage bind_address; socklen_t size;
        assert(!md_inet_encode(&target,0,&bind_address,&size));
        assert(!md_network_endpoint_bind(server_endpoint,echo.fd,&root,(void *)&bind_address,size));
        if(type==SOCK_STREAM) assert(!md_network_endpoint_listen(server_endpoint,echo.fd,16));
        assert(!pthread_create(&echo.thread,NULL,native_echo_run,&echo));
    } else {
    pthread_mutex_lock(&server->fabric->lock);
    struct md_lwip_context *previous=md_lwip_enter(server->stack->context);
    ip_addr_t any=md_stack_ip(md_ip_any(addresses[1].family));
    if(type==SOCK_STREAM) {
        struct tcp_pcb *pcb=tcp_new_ip_type(IP_GET_TYPE(&any)); assert(pcb);
        assert(!tcp_bind(pcb,&any,40000)); pcb=tcp_listen(pcb); assert(pcb); tcp_accept(pcb,accept_tcp);
    } else {
        struct udp_pcb *pcb=udp_new_ip_type(IP_GET_TYPE(&any)); assert(pcb);
        assert(!udp_bind(pcb,&any,40000)); udp_recv(pcb,echo_udp,NULL);
    }
    md_stack_arm(server->stack); md_lwip_leave(previous);
    pthread_mutex_unlock(&server->fabric->lock);
    }
    assert(!pthread_create(&cable.thread,NULL,cable_run,&cable));
    struct md_fd_object *endpoint; int fd;
    assert(!md_network_endpoint_open(client,0,addresses[0].family,type,0,&endpoint,&fd));
    struct md_inet_address target={.ip=addresses[1],.port=htons(40000)};
    struct sockaddr_storage logical,native; socklen_t size,length;
    assert(!md_inet_encode(&target,0,&logical,&size));
    assert(!md_network_endpoint_autobind(endpoint,fd,&root,(void *)&logical,size,0));
    assert(!md_network_endpoint_destination(endpoint,(void *)&logical,size,&native,&length));
    assert(!connect(fd,(void *)&native,length));
    assert(!md_network_endpoint_connected(endpoint,fd,(void *)&logical,size));
    unsigned char sent[8192],received[8192]; memset(sent,0x6b,sizeof(sent));
    for(unsigned round=0;round<8;++round) {
        assert(send(fd,sent,sizeof(sent),MSG_NOSIGNAL)==sizeof(sent));
        size_t count=0;
        while(count<sizeof(received)) {
            struct pollfd ready={.fd=fd,.events=POLLIN};
            /* EVENT_WAIT: real packet response, not a local carrier handshake. */
            assert(poll(&ready,1,5000)==1);
            ssize_t bytes=recv(fd,received+count,sizeof(received)-count,0);
            assert(bytes>0); count+=(size_t)bytes;
            if(type==SOCK_DGRAM) assert(count==sizeof(received));
        }
        assert(!memcmp(sent,received,sizeof(sent)));
        if(raw>=0) {
            unsigned char packet[sizeof(struct md_network_packet)+20+8+sizeof(sent)];
            struct pollfd ready={.fd=raw,.events=POLLIN};
            /* EVENT_WAIT: reassembled transport is also delivered to raw observers. */
            assert(poll(&ready,1,5000)==1);
            size_t header=sizeof(struct md_network_packet)+(ipv6 ? 0 : 20)+8;
            assert(recv(raw,packet,sizeof(packet),0)==(ssize_t)(header+sizeof(sent)));
            assert(!memcmp(packet+header,sent,sizeof(sent)));
        }
    }
    close(fd); md_fd_object_release(endpoint);
    if(native_server) { assert(!pthread_join(echo.thread,NULL)); close(echo.fd); md_fd_object_release(server_endpoint); }
    if(raw>=0) close(raw);
    md_fd_object_release(observer); cable_close(&cable,spaces);
    md_identity_release(&root);
    printf("PASS %s IPv%u %s %s server and teardown\n",tap ? "TAP" : "TUN",ipv6 ? 6 : 4,type==SOCK_STREAM ? "TCP" : "UDP",native_server ? "native" : "packet");
}
static void raw_echo(int tap,int ipv6) {
    struct md_identity root=md_identity_new(0,0);
    struct md_network_namespace *spaces[2]; struct md_ip_address addresses[2];
    struct cable cable={0}; cable_setup(&cable,&root,spaces,addresses,tap,ipv6);
    assert(!pthread_create(&cable.thread,NULL,cable_run,&cable));
    struct md_fd_object *socket; int fd;
    assert(!md_network_socket_open(spaces[0],&root,addresses[0].family,SOCK_RAW,
        ipv6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP,&socket,&fd));
    unsigned char echo[32]={0}; echo[0]=ipv6 ? 128 : 8; echo[4]=0x12; echo[5]=0x34; echo[7]=1;
    if(!ipv6) { uint16_t sum=htons((uint16_t)~(0x0800+0x1234+1)); memcpy(echo+2,&sum,2); }
    struct md_network_send destination={0}; struct sockaddr_storage wire; socklen_t size;
    assert(!md_inet_encode(&(struct md_inet_address){.ip=addresses[1]},0,&wire,&size));
    destination.size=size; memcpy(destination.value,&wire,size);
    unsigned long args[6]={0};
    assert(md_network_socket_call(socket,&root,SYS_sendto,args,echo,sizeof(echo),sizeof(echo),1,&destination,NULL,NULL)==sizeof(echo));
    struct pollfd ready={.fd=fd,.events=POLLIN};
    /* EVENT_WAIT: raw request traverses TAP/TUN and the remote IP stack replies. */
    unsigned char reply[sizeof(struct md_network_packet)+20+sizeof(echo)];
    unsigned header=sizeof(struct md_network_packet)+(ipv6 ? 0 : 20);
    for(unsigned packets=0;;++packets) {
        assert(packets<8 && poll(&ready,1,5000)==1);
        ssize_t received=recv(fd,reply,sizeof(reply),0); assert(received>(ssize_t)header);
        if(ipv6 && reply[header]==136) continue; /* Neighbor advertisements share the raw ICMPv6 socket. */
        assert(received==header+sizeof(echo));
        break;
    }
    assert(reply[header]==(ipv6 ? 129 : 0) && !memcmp(reply+header+4,echo+4,sizeof(echo)-4));
    close(fd); md_fd_object_release(socket); cable_close(&cable,spaces); md_identity_release(&root);
    printf("PASS %s IPv%u raw ICMP, neighbor resolution and reply observation\n",tap ? "TAP" : "TUN",ipv6 ? 6 : 4);
}
void test_network_tun(void) {
    lifecycle(); packet_contract(); retiring_destination();
    for(int server=0;server<2;++server) for(int tap=0;tap<2;++tap) for(int v6=0;v6<2;++v6) for(int tcp=0;tcp<2;++tcp)
        transfer(tap,v6,tcp ? SOCK_STREAM : SOCK_DGRAM,server);
    for(int tap=0;tap<2;++tap) for(int v6=0;v6<2;++v6) raw_echo(tap,v6);
}
