#define _GNU_SOURCE
#include "network_internal.h"
#include "network_endpoint.h"
#include "network_flow.h"
#include "network_profile.h"
#include "network_route.h"
#include "test_network_fixture.h"
#include "guest_identity.h"
#include <assert.h>
#include <errno.h>
#include <linux/if_ether.h>
#include <linux/if_link.h>
#include <linux/rtnetlink.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

static void bind_link(struct md_fd_object *socket,const struct md_identity *id,unsigned index) {
    struct sockaddr_ll address={.sll_family=AF_PACKET,.sll_ifindex=(int)index};
    unsigned long args[6]={0};
    assert(!md_network_socket_call(socket,id,SYS_bind,args,&address,sizeof(address),sizeof(address),1,NULL,NULL,NULL));
}
static int isolated(struct md_network_namespace *n,const struct md_identity *id,unsigned index,unsigned char value) {
    union { struct nlmsghdr alignment; unsigned char bytes[256]; } request={0},reply={0};
    struct nlmsghdr *h=&request.alignment;
    *h=(struct nlmsghdr){.nlmsg_type=RTM_SETLINK,.nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK,
        .nlmsg_len=NLMSG_LENGTH(sizeof(struct ifinfomsg))};
    *(struct ifinfomsg *)NLMSG_DATA(h)=(struct ifinfomsg){.ifi_family=AF_BRIDGE,.ifi_index=index};
    struct rtattr *nest=(void *)(request.bytes+h->nlmsg_len);
    *nest=(struct rtattr){.rta_type=IFLA_PROTINFO|NLA_F_NESTED,.rta_len=RTA_LENGTH(RTA_SPACE(1))};
    struct rtattr *a=RTA_DATA(nest);
    *a=(struct rtattr){.rta_type=IFLA_BRPORT_ISOLATED,.rta_len=RTA_LENGTH(1)};
    *(unsigned char *)RTA_DATA(a)=value;
    h->nlmsg_len+=RTA_ALIGN(nest->rta_len);
    assert(md_network_route(n,id,h,h->nlmsg_len,1,0,NULL,reply.bytes,sizeof(reply),NULL)>0);
    assert(reply.alignment.nlmsg_type==NLMSG_ERROR);
    return ((struct nlmsgerr *)NLMSG_DATA(&reply.alignment))->error;
}
static void isolated_bridge(void) {
    struct md_network_namespace *host=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *a=md_network_namespace_copy(host,NULL),*b=md_network_namespace_copy(host,NULL);
    assert(host && a && b);
    struct md_identity root=md_identity_new(0,0);
    unsigned bridge,ports[2],peers[2];
    assert(!md_network_interface_add(host,MD_LINK_BRIDGE,"br",&bridge));
    assert(!md_network_interface_add(host,MD_LINK_VETH,"left",&ports[0]));
    assert(!md_network_interface_add(host,MD_LINK_VETH,"right",&ports[1]));
    struct md_network_interface *master=md_network_interface_find(host,bridge,NULL);
    master->info.flags|=IFF_UP; test_network_v4(master,htonl(0x0a070001),24);
    struct md_network_namespace *children[]={a,b};
    for (unsigned i=0;i<2;++i) {
        assert(!md_network_interface_add(children[i],MD_LINK_VETH,"eth0",&peers[i]));
        struct md_network_interface *port=md_network_interface_find(host,ports[i],NULL);
        struct md_network_interface *peer=md_network_interface_find(children[i],peers[i],NULL);
        port->info.flags|=IFF_UP; port->peer=peers[i]; port->master=bridge;
        peer->info.flags|=IFF_UP; peer->peer=ports[i]; test_network_v4(peer,htonl(0x0a070002+i),24);
    }
    struct md_fd_object *client=NULL,*server=NULL;
    int clientfd,serverfd;
    assert(!md_network_endpoint_open(a,0,AF_INET,SOCK_STREAM,0,&client,&clientfd));
    assert(!md_network_endpoint_open(b,0,AF_INET,SOCK_STREAM,0,&server,&serverfd));
    struct sockaddr_in address={.sin_family=AF_INET,.sin_addr.s_addr=htonl(0x0a070003),.sin_port=htons(8070)};
    assert(!md_network_endpoint_bind(server,serverfd,&root,(void *)&address,sizeof(address)));
    assert(!md_network_endpoint_listen(server,serverfd,1));
    struct sockaddr_storage destination; socklen_t length;
    assert(!md_network_endpoint_destination(client,(void *)&address,sizeof(address),&destination,&length));
    struct md_filter_packet packet={.source=md_ip_v4(htonl(0x0a070002)),.destination=md_ip_v4(address.sin_addr.s_addr),.protocol=IPPROTO_TCP};
    struct md_network_flow_path path;
    assert(!md_network_flow_route(a,&packet,0,0,&path) && !path.mediated && path.destination==b->inode);
    assert(!isolated(host,&root,ports[0],1));
    assert(isolated(host,&root,ports[1],1)==-EBUSY);
    assert(!md_network_interface_find(host,ports[1],NULL)->isolated);
    close(clientfd); md_fd_object_release(client);
    assert(!isolated(host,&root,ports[1],1));
    assert(md_network_flow_check(host->fabric,&path,0)==-EACCES);
    assert(md_network_flow_check(host->fabric,&path,1)==-EACCES);
    assert(md_network_flow_route(a,&packet,0,0,&path)==-EACCES);
    packet=(struct md_filter_packet){.source=md_ip_v4(htonl(0x0a070002)),.destination=md_ip_v4(htonl(0x0a070001)),.protocol=IPPROTO_TCP};
    assert(!md_network_flow_route(a,&packet,0,0,&path) && path.destination==host->inode);
    struct md_fd_object *sender=NULL,*receiver=NULL;
    int sendfd,receivefd;
    assert(!md_network_socket_open(a,&root,AF_PACKET,SOCK_RAW,htons(ETH_P_ALL),&sender,&sendfd));
    assert(!md_network_socket_open(b,&root,AF_PACKET,SOCK_RAW|SOCK_NONBLOCK,htons(ETH_P_ALL),&receiver,&receivefd));
    bind_link(sender,&root,peers[0]); bind_link(receiver,&root,peers[1]);
    unsigned char frame[64]={255,255,255,255,255,255,2,0,0,0,0,1,0x88,0xb5};
    unsigned long args[6]={0}; char received[sizeof(struct md_network_packet)+sizeof(frame)];
    assert(md_network_socket_call(sender,&root,SYS_write,args,frame,sizeof(frame),sizeof(frame),1,NULL,NULL,NULL)==sizeof(frame));
    assert(recv(receivefd,received,sizeof(received),MSG_DONTWAIT)<0 && errno==EAGAIN);
    assert(!isolated(host,&root,ports[1],0));
    assert(md_network_socket_call(sender,&root,SYS_write,args,frame,sizeof(frame),sizeof(frame),1,NULL,NULL,NULL)==sizeof(frame));
    assert(recv(receivefd,received,sizeof(received),MSG_DONTWAIT)==sizeof(struct md_network_packet)+sizeof(frame));
    assert(!memcmp(received+sizeof(struct md_network_packet),frame,sizeof(frame)));
    packet=(struct md_filter_packet){.source=md_ip_v4(htonl(0x0a070002)),.destination=md_ip_v4(address.sin_addr.s_addr),.protocol=IPPROTO_TCP};
    assert(!md_network_flow_route(a,&packet,0,0,&path) && path.mediated && path.destination==b->inode);
    assert(!md_network_flow_check(host->fabric,&path,1));
    assert(!isolated(host,&root,ports[1],1));
    assert(md_network_flow_check(host->fabric,&path,0)==-EACCES);
    close(sendfd); close(receivefd); close(serverfd);
    md_fd_object_release(sender); md_fd_object_release(receiver); md_fd_object_release(server);
    md_network_namespace_release(a); md_network_namespace_release(b); md_network_namespace_release(host);
    md_identity_release(&root);
    puts("PASS isolated bridge: Ethernet and IP policy, reverse flows, local delivery and atomic direct-stream rejection");
}
static void bound_veth_loopback(void) {
    for (unsigned v6=0;v6<3;++v6) for (unsigned udp=0;udp<2;++udp) {
        struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
        unsigned a,b;
        assert(n && !md_network_interface_add(n,MD_LINK_VETH,"out",&a));
        assert(!md_network_interface_add(n,MD_LINK_VETH,"in",&b));
        struct md_network_interface *left=md_network_interface_find(n,a,NULL);
        struct md_network_interface *right=md_network_interface_find(n,b,NULL);
        left->peer=b; right->peer=a;
        left->info.flags|=IFF_UP; right->info.flags|=IFF_UP;
        struct md_ip_address source,destination;
        assert(!md_network_profile_ip(v6==2 ? "fe80::2" : v6 ? "fd92:10::2" : "10.231.247.2",&source));
        assert(!md_network_profile_ip(v6==2 ? "fe80::1" : v6 ? "fd92:10::1" : "10.231.247.1",&destination));
        unsigned scope=v6==2 ? RT_SCOPE_LINK : RT_SCOPE_UNIVERSE;
        assert(!md_network_interface_address(left,(struct md_network_ip_assignment){
            .address=source,.prefix=v6 ? 64 : 24,.scope=scope},0,0));
        assert(!md_network_interface_address(right,(struct md_network_ip_assignment){
            .address=destination,.prefix=v6 ? 64 : 24,.scope=scope},0,0));
        struct md_filter_packet p={.source=source,.destination=destination,
            .source_port=htons(32000),.destination_port=htons(8080),
            .protocol=udp ? IPPROTO_UDP : IPPROTO_TCP,.state=8};
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&p,0,a,&path));
        assert(path.destination==n->inode && path.count==4 && !path.mediated);
        assert(path.steps[0].packet.output==a && path.steps[3].packet.input==b);
        assert(!md_network_flow_check(n->fabric,&path,0));
        assert(!md_network_flow_check(n->fabric,&path,1));
        if (v6==2) {
            assert(md_network_flow_route(n,&p,1,a,&path)==-ENETUNREACH);
            assert(!md_network_flow_route(n,&p,1,b,&path) && path.destination==n->inode);
        }
        md_network_namespace_release(n);
    }
    puts("PASS bound veth: IPv4/IPv6 TCP/UDP return to the source namespace without reusing the socket output binding");
}
void test_network_ethernet(void) {
    bound_veth_loopback();
    isolated_bridge();
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_fd_object *sender=NULL,*receiver=NULL;
    int sendfd=-1,receivefd=-1;
    assert(md_network_socket_open(n,&user,AF_PACKET,SOCK_RAW,htons(ETH_P_ALL),&sender,&sendfd)==-EPERM);
    assert(!sender && sendfd==-1);
    assert(!md_network_socket_open(n,&root,AF_PACKET,SOCK_RAW,htons(ETH_P_ALL),&sender,&sendfd));
    assert(!md_network_socket_open(n,&root,AF_PACKET,SOCK_RAW|SOCK_NONBLOCK,htons(ETH_P_ALL),&receiver,&receivefd));
    unsigned bridge,a,b;
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"br",&bridge));
    assert(!md_network_interface_add(n,MD_LINK_VETH,"a",&a));
    assert(!md_network_interface_add(n,MD_LINK_VETH,"b",&b));
    struct md_network_interface *left=md_network_interface_find(n,a,NULL),*right=md_network_interface_find(n,b,NULL);
    left->peer=b; right->peer=a;
    left->master=right->master=bridge;
    left->info.flags|=IFF_UP; right->info.flags|=IFF_UP;
    md_network_interface_find(n,bridge,NULL)->info.flags|=IFF_UP;
    bind_link(sender,&root,bridge); bind_link(receiver,&root,b);
    unsigned char frame[64]={255,255,255,255,255,255,2,0,0,0,0,1,0x88,0xb5};
    unsigned long args[6]={0};
    assert(md_network_socket_call(sender,&root,SYS_write,args,frame,sizeof(frame),sizeof(frame),1,NULL,NULL,NULL)==sizeof(frame));
    /* Two ports of the same bridge form a cycle. One ingress and one egress
     * visit per interface bound both execution and the observer queue. */
    unsigned packets=0;
    struct { struct md_network_packet header; unsigned char data[64]; } packet;
    for (;;) {
        ssize_t size=recv(receivefd,&packet,sizeof(packet),MSG_DONTWAIT);
        if (size<0) { assert(errno==EAGAIN); break; }
        assert(size==sizeof(packet) && !memcmp(packet.data,frame,sizeof(frame)));
        assert(packet.header.ethernet.sll_ifindex==(int)b);
        assert(++packets<=2);
    }
    assert(packets==2);
    /* Namespace movement retires the old binding even if the link moves back. */
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL);
    assert(!md_network_interface_move(n,b,child,NULL));
    assert(!md_network_interface_move(child,b,n,NULL));
    assert(md_network_socket_call(sender,&root,SYS_write,args,frame,sizeof(frame),sizeof(frame),1,NULL,NULL,NULL)==sizeof(frame));
    assert(recv(receivefd,&packet,sizeof(packet),MSG_DONTWAIT)<0 && errno==EAGAIN);
    bind_link(receiver,&root,b);
    assert(md_network_socket_call(sender,&root,SYS_write,args,frame,sizeof(frame),sizeof(frame),1,NULL,NULL,NULL)==sizeof(frame));
    assert(recv(receivefd,&packet,sizeof(packet),MSG_DONTWAIT)==sizeof(packet));
    close(sendfd); close(receivefd);
    md_fd_object_release(sender); md_fd_object_release(receiver);
    assert(!n->ethernet);
    md_network_namespace_release(child); md_network_namespace_release(n);
    md_identity_release(&root); md_identity_release(&user);
    puts("PASS virtual Ethernet: capabilities, bounded bridge cycles, namespace moves and final release");
}
