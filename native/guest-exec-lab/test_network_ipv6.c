#define _GNU_SOURCE
#include "network_endpoint.h"
#include "network_internal.h"
#include "network_launch.h"
#include "network_proc.h"
#include "network_autoconf.h"
#include "network_route.h"
#include "network_route_ip.h"
#include "network_packet.h"
#include "guest_identity.h"
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <linux/if_addr.h>
#include <net/if.h>
#include <netinet/udp.h>
#include <sys/syscall.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void ready(int fd) {
    struct pollfd p={.fd=fd,.events=POLLIN};
    /* EVENT_WAIT: socket data/accept readiness; timeout fails the exchange. */
    assert(poll(&p,1,2000)==1 && (p.revents&POLLIN));
}
static struct sockaddr_storage encode(struct md_inet_address a,unsigned family,socklen_t *size) {
    struct sockaddr_storage out;
    assert(!md_inet_encode(&a,family,&out,size)); return out;
}
static void send_packet(struct md_fd_object *e,int fd,const struct sockaddr_storage *to,socklen_t size) {
    struct sockaddr_storage native; socklen_t length;
    assert(!md_network_endpoint_destination(e,(const void *)to,size,&native,&length));
    assert(sendto(fd,"ping",4,0,(void *)&native,length)==4);
}
static struct sockaddr_storage receive_packet(struct md_fd_object *e,int fd,socklen_t *size) {
    struct sockaddr_storage from;
    char data[4]; *size=sizeof(from); ready(fd);
    assert(recvfrom(fd,data,sizeof(data),0,(void *)&from,size)==4 && !memcmp(data,"ping",4));
    assert(!md_network_endpoint_source(e,&from,size)); return from;
}
static void exchange(int family,int type) {
    struct md_network_profile profile={.policy=MD_NETWORK_BRIDGE};
    struct md_network_launch launch={0};
    assert(!md_network_launch_open(&profile,NULL,&launch));
    struct md_identity root=md_identity_new(0,0);
    struct md_fd_object *server,*client; int s,c;
    assert(!md_network_endpoint_open(launch.space,0,AF_INET6,type,0,&server,&s));
    assert(!md_network_endpoint_open(launch.space,0,family,type,0,&client,&c));
    socklen_t size;
    struct sockaddr_storage local=encode((struct md_inet_address){.ip=md_ip_any(AF_INET6)},AF_INET6,&size);
    assert(!md_network_endpoint_bind(server,s,&root,(void *)&local,size));
    assert(!md_network_endpoint_name(server,s,0,&local,&size));
    struct md_inet_address destination;
    assert(!md_inet_decode((void *)&local,size,&destination));
    destination.ip=md_ip_loopback(family);
    struct sockaddr_storage target=encode(destination,family,&size),native;
    assert(!md_network_endpoint_autobind(client,c,&root,(void *)&target,size,0));
    if (type==SOCK_STREAM) {
        assert(!md_network_endpoint_listen(server,s,4));
        socklen_t length;
        assert(!md_network_endpoint_destination(client,(void *)&target,size,&native,&length));
        assert(!connect(c,(void *)&native,length));
        assert(!md_network_endpoint_connected(client,c,(void *)&target,size));
        ready(s); int fd=accept(s,NULL,NULL); assert(fd>=0);
        struct md_fd_object *accepted;
        assert(!md_network_endpoint_accept(server,fd,&accepted));
        assert(!md_network_endpoint_name(accepted,fd,0,&local,&length));
        struct md_inet_address actual;
        assert(!md_inet_decode((void *)&local,length,&actual) && md_ip_equal(actual.ip,destination.ip));
        assert(send(c,"ping",4,0)==4); ready(fd);
        char data[4]; assert(recv(fd,data,4,0)==4 && !memcmp(data,"ping",4));
        assert(send(fd,"pong",4,0)==4); ready(c);
        assert(recv(c,data,4,0)==4 && !memcmp(data,"pong",4));
        close(fd); md_fd_object_release(accepted);
    } else {
        send_packet(client,c,&target,size);
        struct sockaddr_storage peer=receive_packet(server,s,&size);
        struct md_inet_address actual;
        assert(!md_inet_decode((void *)&peer,size,&actual) && actual.ip.family==(unsigned)family
            && md_ip_is_loopback(actual.ip));
        send_packet(server,s,&peer,size);
        peer=receive_packet(client,c,&size);
        assert(!md_inet_decode((void *)&peer,size,&actual) && md_inet_equal(&actual,&destination));
    }
    close(c); close(s); md_fd_object_release(client); md_fd_object_release(server);
    md_identity_release(&root); md_network_launch_close(&launch);
}
static void policy(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL);
    assert(n && child);
    uint64_t scope; uint32_t value[2]={1};
    assert(!md_network_parameter_scope(n,MD_NET_IPV6_DISABLE,"eth0",&scope));
    assert(!md_network_parameter_write(n,MD_NET_IPV6_DISABLE,scope,value));
    struct md_ip_address remote; assert(!md_network_profile_ip("2001:db8::1",&remote));
    assert(!md_network_route_find(n,remote,NULL));
    assert(md_network_route_find(n,md_ip_v4(htonl(0xc0000201)),NULL));
    assert(!md_network_parameter_read(child,MD_NET_IPV6_DISABLE,MD_NET_SCOPE_ALL,value) && !value[0]);
    assert(!md_network_parameter_write(n,MD_NET_IPV6_DISABLE,scope,value));
    const struct md_network_interface *link=md_network_interface_find(n,0,"eth0");
    unsigned local=0;
    for (unsigned i=0;i<link->address_count;++i) local+=md_ip_linklocal(link->addresses[i].address);
    assert(local==1);
    value[0]=1;
    assert(!md_network_parameter_write(n,MD_NET_IPV6_BINDONLY,0,value));
    struct md_fd_object *endpoint; int fd;
    assert(!md_network_endpoint_open(n,0,AF_INET6,SOCK_STREAM,0,&endpoint,&fd));
    int only=0; socklen_t size=sizeof(only);
    assert(!getsockopt(fd,IPPROTO_IPV6,IPV6_V6ONLY,&only,&size) && only==1);
    close(fd); md_fd_object_release(endpoint);
    assert(!md_network_parameter_read(child,MD_NET_IPV6_BINDONLY,0,value) && !value[0]);
    struct md_control_file file=md_network_proc_new(n,MD_NET_TABLE_IF_INET6);
    struct md_identity root=md_identity_new(0,0);
    struct md_control_actor actor={.identity=&root}; char text[4096]={0};
    assert(file.ops && file.ops->read(file.context,&actor,text,sizeof(text)-1,0)>0);
    assert(strstr(text,"00000000000000000000000000000001") && strstr(text,"fe80"));
    md_control_file_release(file); md_identity_release(&root);
    md_network_namespace_release(child); md_network_namespace_release(n);
}
static void literals(void) {
    const char *good[]={"::","::1","2001:db8::abcd","fd12:3456:789a::1","1:2:3:4:5:6:7:8","::ffff:192.0.2.1"};
    for (unsigned i=0;i<sizeof(good)/sizeof(*good);++i) {
        struct md_ip_address a; struct in6_addr native;
        assert(inet_pton(AF_INET6,good[i],&native)==1);
        assert(!md_network_profile_ip(good[i],&a) && a.family==AF_INET6 && !memcmp(a.bytes,&native,16));
    }
    const char *bad[]={":::1","1::2::3","1:2:3:4:5:6:7","1:2:3:4:5:6:7:8:9","::12345","fe80::1%eth0","1:",":"};
    for (unsigned i=0;i<sizeof(bad)/sizeof(*bad);++i) {
        struct md_ip_address a; assert(md_network_profile_ip(bad[i],&a)<0);
    }
    struct md_network_profile p={.policy=MD_NETWORK_BRIDGE};
    assert(!md_network_profile_add(&p,"--dns","2001:db8::53"));
    assert(md_network_profile_add(&p,"--dns","2001:db8:0:0::53")==-EEXIST);
    assert(!md_network_profile_add(&p,"-p","[::1]:8090:80"));
    assert(!md_network_profile_add(&p,"-p","127.0.0.1:8090:80"));
    assert(md_network_profile_add(&p,"-p","[::]:8090:81")==-EADDRINUSE);
}
static void temporary_addresses(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_interface *link=md_network_interface_find(n,0,"eth0");
    struct md_ip_address base,target;
    assert(!md_network_profile_ip("fd92:10::1",&base));
    assert(!md_network_profile_ip("fd92:10::2",&target));
    int64_t now=md_network_lifetime_now();
    struct md_network_ip_assignment value={.address=base,.prefix=64,.flags=IFA_F_MANAGETEMPADDR,
        .preferred_until=now+INT64_C(200000000),.valid_until=now+INT64_C(300000000)};
    pthread_mutex_lock(&n->fabric->lock);
    assert(!md_network_interface_address(link,value,0,0));
    assert(!md_network_route_privacy(n,link->policy_id,2));
    unsigned last=link->address_count-1;
    struct md_network_ip_assignment first=link->addresses[last];
    assert(first.flags&IFA_F_TEMPORARY);
    assert(!md_ip_equal(first.address,base) && md_ip_prefix(first.address,base,64));
    assert(first.valid_until>=now+INT64_C(172800000) && first.valid_until<now+INT64_C(172801000));
    assert(first.preferred_until<first.valid_until && first.regenerate_at==first.preferred_until-5000);
    assert(md_ip_equal(md_network_interface_source(link,target),first.address));
    assert(!md_network_route_privacy(n,link->policy_id,1));
    assert(md_ip_equal(md_network_interface_source(link,target),base));
    md_network_route_expire(n->fabric,first.regenerate_at);
    assert(link->address_count==last+2 && !md_ip_equal(link->addresses[last+1].address,first.address));
    md_network_route_expire(n->fabric,first.preferred_until);
    assert(link->addresses[last].flags&IFA_F_DEPRECATED);
    assert(!md_network_route_privacy(n,link->policy_id,0));
    unsigned count=link->address_count;
    assert(!md_network_address_refresh(n,link,first.valid_until));
    assert(link->address_count==count-1 && !md_network_interface_has(link,first.address));
    assert(!md_network_interface_address(link,value,1,0));
    assert(!md_network_address_refresh(n,link,first.valid_until));
    for (unsigned i=0;i<link->address_count;++i) assert(!(link->addresses[i].flags&IFA_F_TEMPORARY));
    pthread_mutex_unlock(&n->fabric->lock);
    md_network_namespace_release(n);
    puts("PASS IPv6 privacy: random addresses, source preference, rotation, deprecation, expiry and template removal");
}
static void raw_bindings(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_fd_object *socket=NULL; int fd=-1;
    assert(md_network_socket_open(n,&user,AF_INET6,SOCK_RAW,IPPROTO_ICMPV6,&socket,&fd)==-EPERM);
    assert(!md_network_socket_open(n,&root,AF_INET6,SOCK_RAW,IPPROTO_ICMPV6,&socket,&fd));
    unsigned index;
    assert(!md_network_interface_add(n,MD_LINK_VETH,"raw0",&index));
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    link->info.flags|=IFF_UP; md_network_interface_up(link);
    unsigned long args[6]={0,SOL_SOCKET,SO_BINDTODEVICE}; char name[IFNAMSIZ]="raw0";
    assert(!md_network_socket_call(socket,&root,SYS_setsockopt,args,name,sizeof(name),5,1,NULL,NULL,NULL));
    assert(md_network_socket_call(socket,&user,SYS_setsockopt,args,name,sizeof(name),0,1,NULL,NULL,NULL)==-EPERM);
    struct md_inet_address local={.ip=link->addresses[0].address,.scope=index};
    struct md_network_send to={0}; socklen_t length;
    struct sockaddr_storage wire=encode(local,AF_INET6,&length);
    memcpy(to.value,&wire,length); to.size=length;
    unsigned char echo[8]={128,0,0,0,0,1,0,1};
    assert(md_network_socket_call(socket,&root,SYS_sendto,args,echo,sizeof(echo),sizeof(echo),1,&to,NULL,NULL)==8);
    unsigned char data[sizeof(struct md_network_packet)+8]; unsigned replies=0;
    while (recv(fd,data,sizeof(data),MSG_DONTWAIT)>0) replies+=data[sizeof(struct md_network_packet)]==129;
    assert(replies==1);
    assert(!md_network_interface_move(n,index,child,NULL));
    assert(!md_network_interface_move(child,index,n,NULL));
    assert(md_network_socket_call(socket,&root,SYS_sendto,args,echo,sizeof(echo),sizeof(echo),1,&to,NULL,NULL)==-ENODEV);
    close(fd); md_fd_object_release(socket); md_identity_release(&root); md_identity_release(&user);
    md_network_namespace_release(child); md_network_namespace_release(n);
    puts("PASS ICMPv6: capability custody, local echo and retired device binding");
}
static int hops_received(int fd) {
    char byte; union { struct cmsghdr align; char bytes[CMSG_SPACE(sizeof(int))]; } control;
    struct iovec iov={&byte,1};
    struct msghdr message={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.bytes,.msg_controllen=sizeof(control)};
    ready(fd); assert(recvmsg(fd,&message,0)==1);
    struct cmsghdr *c=CMSG_FIRSTHDR(&message);
    assert(c && c->cmsg_level==IPPROTO_IPV6 && c->cmsg_type==IPV6_HOPLIMIT);
    int value; memcpy(&value,CMSG_DATA(c),sizeof(value)); return value;
}
static void raw_datagrams(int family) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0);
    struct md_fd_object *raw,*server,*client; int r,s,c;
    assert(!md_network_socket_open(n,&root,family,SOCK_RAW,IPPROTO_UDP,&raw,&r));
    assert(!md_network_endpoint_open(n,0,family,SOCK_DGRAM,0,&server,&s));
    assert(!md_network_endpoint_open(n,0,family,SOCK_DGRAM,0,&client,&c));
    socklen_t size;
    struct sockaddr_storage local=encode((struct md_inet_address){.ip=md_ip_loopback(family)},family,&size);
    assert(!md_network_endpoint_bind(server,s,&root,(void *)&local,size));
    assert(!md_network_endpoint_bind(client,c,&root,(void *)&local,size));
    struct sockaddr_storage source,target;
    assert(!md_network_endpoint_name(client,c,0,&source,&size));
    struct md_inet_address from,to;
    assert(!md_inet_decode((void *)&source,size,&from));
    assert(!md_network_endpoint_name(server,s,0,&target,&size));
    assert(!md_inet_decode((void *)&target,size,&to));
    unsigned long args[6]={0,IPPROTO_IPV6,IPV6_CHECKSUM}; int offset=6;
    if (family==AF_INET6)
        assert(!md_network_socket_call(raw,&root,SYS_setsockopt,args,&offset,sizeof(offset),sizeof(offset),1,NULL,NULL,NULL));
    struct { struct udphdr header; char data[4]; } packet={
        .header={.source=from.port,.dest=to.port,.len=htons(12)},.data={'p','i','n','g'}};
    struct md_network_send destination={.size=size}; memcpy(destination.value,&target,size);
    assert(md_network_socket_call(raw,&root,SYS_sendto,args,&packet,sizeof(packet),sizeof(packet),1,
        &destination,NULL,NULL)==sizeof(packet));
    struct sockaddr_storage peer=receive_packet(server,s,&size);
    struct md_inet_address actual;
    assert(!md_inet_decode((void *)&peer,size,&actual) && md_inet_equal(&actual,&from));
    unsigned char data[sizeof(struct md_network_packet)+32];
    size_t header=sizeof(struct md_network_packet)+(family==AF_INET ? 20 : 0);
    ready(r); assert(recv(r,data,sizeof(data),0)==(ssize_t)(header+12));
    assert(!memcmp(data+header+8,"ping",4));
    send_packet(server,s,&peer,size);
    peer=receive_packet(client,c,&size);
    assert(!md_inet_decode((void *)&peer,size,&actual) && md_inet_equal(&actual,&to));
    ready(r); assert(recv(r,data,sizeof(data),0)==(ssize_t)(header+12));
    struct udphdr reply; memcpy(&reply,data+header,sizeof(reply));
    assert(reply.source==to.port && reply.dest==from.port && !memcmp(data+header+8,"ping",4));
    close(r); close(s); close(c);
    md_fd_object_release(raw); md_fd_object_release(server); md_fd_object_release(client);
    md_identity_release(&root); md_network_namespace_release(n);
}
static void transport_policy(void) {
    struct md_network_profile profile={.policy=MD_NETWORK_BRIDGE};
    struct md_network_launch launch={0};
    assert(!md_network_launch_open(&profile,NULL,&launch));
    for (unsigned i=0;i<launch.space->route_count;++i) if (launch.space->routes[i].destination.family==AF_INET6)
        launch.space->routes[i].metrics=(struct md_network_route_metrics){.mtu=1440,.hops=37};
    struct md_identity root=md_identity_new(0,0);
    for (unsigned tcp=0;tcp<2;++tcp) {
        int type=tcp ? SOCK_STREAM : SOCK_DGRAM;
        struct md_fd_object *client; int fd;
        assert(!md_network_endpoint_open(launch.space,0,AF_INET6,type,0,&client,&fd));
        struct sockaddr_in6 logical={.sin6_family=AF_INET6,.sin6_port=htons(12345)};
        assert(inet_pton(AF_INET6,"2001:db8::1",&logical.sin6_addr)==1);
        assert(!md_network_endpoint_autobind(client,fd,&root,(void *)&logical,sizeof(logical),0));
        int server=socket(AF_INET6,type|SOCK_CLOEXEC,0); assert(server>=0);
        struct sockaddr_in6 native={.sin6_family=AF_INET6,.sin6_addr=IN6ADDR_LOOPBACK_INIT};
        assert(!bind(server,(void *)&native,sizeof(native)));
        socklen_t size=sizeof(native); assert(!getsockname(server,(void *)&native,&size));
        int one=1;
        if (tcp) assert(!listen(server,4));
        else assert(!setsockopt(server,IPPROTO_IPV6,IPV6_RECVHOPLIMIT,&one,sizeof(one)));
        assert(!connect(fd,(void *)&native,sizeof(native)));
        assert(!md_network_endpoint_connected(client,fd,(void *)&logical,sizeof(logical)));
        uint64_t scope; uint32_t requested[2]={1280},current[2];
        assert(!md_network_parameter_scope(launch.space,MD_NET_IPV6_MTU,"eth0",&scope));
        assert(!md_network_parameter_read(launch.space,MD_NET_IPV6_MTU,scope,current));
        assert(md_network_parameter_write(launch.space,MD_NET_IPV6_MTU,scope,requested)==-EBUSY);
        uint32_t unchanged[2];
        assert(!md_network_parameter_read(launch.space,MD_NET_IPV6_MTU,scope,unchanged) && unchanged[0]==current[0]);
        int value=0; size=sizeof(value);
        assert(!md_network_endpoint_sockopt(client,fd,&root,0,IPPROTO_IPV6,IPV6_MTU,&value,&size) && value==1440);
        if (tcp) {
            ready(server); int peer=accept(server,NULL,NULL); assert(peer>=0);
            size=sizeof(value); assert(!getsockopt(fd,IPPROTO_TCP,TCP_MAXSEG,&value,&size) && value<=1380);
            assert(send(fd,"x",1,0)==1); ready(peer); char byte; assert(recv(peer,&byte,1,0)==1);
            close(peer);
        } else {
            assert(!setsockopt(fd,IPPROTO_IPV6,IPV6_DONTFRAG,&one,sizeof(one)));
            char bytes[1450]={0};
            assert(send(fd,bytes,sizeof(bytes),0)==-1 && errno==EMSGSIZE);
            assert(send(fd,"x",1,0)==1 && hops_received(server)==37);
            value=41; size=sizeof(value);
            assert(!md_network_endpoint_sockopt(client,fd,&root,1,IPPROTO_IPV6,IPV6_UNICAST_HOPS,&value,&size));
            assert(!md_network_endpoint_autobind(client,fd,&root,(void *)&logical,sizeof(logical),0));
            assert(send(fd,"x",1,0)==1 && hops_received(server)==41);
            value=-1;
            assert(!md_network_endpoint_sockopt(client,fd,&root,1,IPPROTO_IPV6,IPV6_UNICAST_HOPS,&value,&size));
            assert(send(fd,"x",1,0)==1 && hops_received(server)==37);
            value=1280;
            assert(!md_network_endpoint_sockopt(client,fd,&root,1,IPPROTO_IPV6,IPV6_MTU,&value,&size));
            assert(send(fd,bytes,1300,0)==-1 && errno==EMSGSIZE);
            value=0;
            assert(!md_network_endpoint_sockopt(client,fd,&root,1,IPPROTO_IPV6,IPV6_MTU,&value,&size));
            assert(send(fd,bytes,1300,0)==1300); ready(server);
            assert(recv(server,bytes,sizeof(bytes),0)==1300);
        }
        close(fd); close(server); md_fd_object_release(client);
        assert(!md_network_parameter_write(launch.space,MD_NET_IPV6_MTU,scope,requested));
        assert(!md_network_parameter_write(launch.space,MD_NET_IPV6_MTU,scope,current));
    }
    md_identity_release(&root); md_network_launch_close(&launch);
    puts("PASS IPv6 route transport: native UDP MTU/hops, application override/reset and TCP MSS without payload mediation");
}
void test_network_ipv6(void) {
    transport_policy();
    literals(); policy(); temporary_addresses(); raw_bindings();
    raw_datagrams(AF_INET); raw_datagrams(AF_INET6);
    for (unsigned tcp=0;tcp<2;++tcp) for (unsigned v6=0;v6<2;++v6)
        exchange(v6 ? AF_INET6 : AF_INET,tcp ? SOCK_STREAM : SOCK_DGRAM);
    puts("PASS IPv6: literal grammar, scoped policy, proc, IPv6-only defaults and dual-stack TCP/UDP round trips");
}
