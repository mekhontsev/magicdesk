#define _GNU_SOURCE
#include "network_tun.h"
#include "network_stack_internal.h"
#include "network_endpoint.h"
#include "network_route.h"
#include "network_ioctl.h"
#include "guest_identity.h"
#include "network_packet.h"
#include "network_virtio.h"
#include "test_network_fixture.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <linux/virtio_net.h>
#include <linux/netfilter.h>
#include <net/if.h>
#include <poll.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

struct cable {
    struct md_control_file tun[2];
    int stop, frame_limit;
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
                assert(size>0 && size<=c->frame_limit); a.ops->read_commit(a.context,size);
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
    assert(features==(IFF_TUN|IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE|IFF_VNET_HDR));
    struct ifreq req={.ifr_name="tun%d",.ifr_flags=IFF_TUN};
    assert(!control(f,&root,TUNSETIFF,0,&req));
    assert(control(f,&root,TUNSETOFFLOAD,TUN_F_UFO,NULL)==-EOPNOTSUPP);
    assert(!control(f,&root,TUNSETOFFLOAD,TUN_F_CSUM,NULL));
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
static void virtio_header(void) {
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    for(unsigned pi=0;pi<2;++pi) {
        struct md_control_file f=open_tun(n,&root);
        struct ifreq req={.ifr_flags=IFF_TAP|IFF_VNET_HDR|(pi ? 0 : IFF_NO_PI)};
        assert(!control(f,&root,TUNSETIFF,0,&req));
        unsigned index=md_network_interface_find(n,0,req.ifr_name)->info.index;
        int length=0;
        assert(!control(f,&user,TUNGETVNETHDRSZ,0,&length) && length==10);
        length=9; assert(control(f,&user,TUNSETVNETHDRSZ,0,&length)==-EINVAL);
        unsigned char packet[32]={0},copy[64]; packet[12]=8;
        pthread_mutex_lock(&n->fabric->lock);
        assert(!md_network_tun_output(n,index,packet,sizeof(packet)));
        pthread_mutex_unlock(&n->fabric->lock);
        /* Header changes apply even to packets already queued, not only new ones. */
        length=12; assert(!control(f,&user,TUNSETVNETHDRSZ,0,&length));
        size_t prefix=12+(pi ? sizeof(struct tun_pi) : 0);
        memset(copy,0xff,sizeof(copy));
        assert(f.ops->read(f.context,NULL,copy,prefix-1,0)==-EINVAL);
        assert(f.ops->read(f.context,NULL,copy,sizeof(copy),0)==(long)(prefix+sizeof(packet)));
        for(size_t i=pi ? 4 : 0;i<prefix;++i) assert(!copy[i]);
        assert(!memcmp(copy+prefix,packet,sizeof(packet)));
        f.ops->read_commit(f.context,prefix+sizeof(packet));
        assert(!md_network_route_set_flags(n,&root,req.ifr_name,IFF_UP));
        assert(f.ops->write(f.context,NULL,copy,prefix-1,0)==-EINVAL);
        unsigned char *header=copy+(pi ? 4 : 0);
        header[1]=VIRTIO_NET_HDR_GSO_UDP;
        assert(f.ops->write(f.context,NULL,copy,prefix+sizeof(packet),0)==-EOPNOTSUPP);
        header[1]=0; header[0]=VIRTIO_NET_HDR_F_NEEDS_CSUM; header[6]=31;
        assert(f.ops->write(f.context,NULL,copy,prefix+sizeof(packet),0)==-EINVAL);
        header[0]=0; header[6]=0;
        length=1; assert(!control(f,&user,TUNSETVNETBE,0,&length));
        header[2]=1;
        assert(f.ops->write(f.context,NULL,copy,prefix+sizeof(packet),0)==-EINVAL);
        assert(!control(f,&user,TUNSETVNETLE,0,&length));
        header[2]=0;
        assert(!control(f,&user,TUNGETVNETLE,0,&length) && length==1);
        length=4096; assert(!control(f,&user,TUNSETSNDBUF,0,&length));
        length=0; assert(!control(f,&user,TUNGETSNDBUF,0,&length) && length==4096);
        md_control_file_release(f);
    }
    md_network_namespace_release(n); md_identity_release(&root); md_identity_release(&user);
    puts("PASS virtio TAP: queued header negotiation, PI, short buffers, endian and malformed checksum bounds");
}
struct segment_check { size_t copied, payload; unsigned packets; int ipv6, ethernet, le; };
static unsigned get16(const unsigned char *p) { return (unsigned)p[0]*256+p[1]; }
static void put16(unsigned char *p,unsigned value) { p[0]=(unsigned char)(value>>8); p[1]=(unsigned char)value; }
static unsigned check_sum(unsigned sum,const unsigned char *p,size_t size) {
    for(size_t i=0;i<size;i+=2) sum+=(unsigned)p[i]*256+(i+1<size ? p[i+1] : 0);
    while(sum>>16) sum=(sum&0xffff)+(sum>>16);
    return sum;
}
static int check_segment(void *context,const void *data,size_t size) {
    struct segment_check *c=context; const unsigned char *p=data;
    size_t ip=c->ethernet ? 14 : 0,tcp=ip+(c->ipv6 ? 40 : 20),head=tcp+20;
    assert(size>=head && size<=head+1000);
    size_t bytes=size-head;
    assert(bytes==(c->payload-c->copied<1000 ? c->payload-c->copied : 1000));
    assert(((uint32_t)get16(p+tcp+4)<<16|get16(p+tcp+6))==0xfffff000U+(uint32_t)c->copied);
    unsigned flags=0x10|(!c->copied ? 0x80 : 0)|(c->copied+bytes==c->payload ? 0x09 : 0);
    assert(p[tcp+13]==flags);
    unsigned sum;
    if(c->ipv6) {
        assert(get16(p+ip+4)==size-ip-40);
        sum=check_sum((unsigned)(size-tcp)+6,p+ip+8,32);
    } else {
        assert(get16(p+ip+2)==size-ip && get16(p+ip+4)==(uint16_t)(65534+c->packets));
        assert(check_sum(0,p+ip,20)==65535);
        sum=check_sum((unsigned)(size-tcp)+6,p+ip+12,8);
    }
    assert(check_sum(sum,p+tcp,size-tcp)==65535);
    for(size_t i=0;i<bytes;++i) assert(p[head+i]==(unsigned char)(c->copied+i));
    c->copied+=bytes; ++c->packets;
    return 0;
}
struct partial_check { unsigned seed,calls,zero,offset; };
static int check_partial(void *context,const void *data,size_t size) {
    struct partial_check *c=context; const unsigned char *p=data;
    assert(size==7 && p[0]==0xab && check_sum(c->seed,p+1,size-1)==65535);
    if(c->zero) assert(p[1+c->offset]==255 && p[2+c->offset]==255);
    ++c->calls; return 0;
}
static void virtio_packets(void) {
    for(int v6=0;v6<2;++v6) for(int eth=0;eth<2;++eth) for(int le=0;le<2;++le) {
        unsigned char packet[8192]={0},original[sizeof(packet)],h[10]={0};
        size_t ip=eth ? 14 : 0,tcp=ip+(v6 ? 40 : 20),head=tcp+20,size=head+6503;
        if(eth) put16(packet+12,v6 ? 0x86dd : 0x0800);
        packet[ip]=v6 ? 0x60 : 0x45; packet[ip+(v6 ? 6 : 9)]=6;
        if(v6) { put16(packet+ip+4,(unsigned)(size-ip-40)); packet[ip+8]=packet[ip+24]=0xfd; }
        else { put16(packet+ip+2,(unsigned)(size-ip)); put16(packet+ip+4,65534); packet[ip+12]=packet[ip+16]=10; }
        put16(packet+tcp,1234); put16(packet+tcp+2,4321);
        put16(packet+tcp+4,0xffff); put16(packet+tcp+6,0xf000);
        packet[tcp+12]=0x50; packet[tcp+13]=0x99;
        for(size_t i=head;i<size;++i) packet[i]=(unsigned char)(i-head);
        memcpy(original,packet,size);
        h[0]=VIRTIO_NET_HDR_F_NEEDS_CSUM;
        h[1]=(v6 ? VIRTIO_NET_HDR_GSO_TCPV6 : VIRTIO_NET_HDR_GSO_TCPV4)|VIRTIO_NET_HDR_GSO_ECN;
        unsigned fields[]={head,1000,tcp,16};
        for(unsigned i=0;i<4;++i) { h[2+i*2]=(unsigned char)(le ? fields[i] : fields[i]>>8); h[3+i*2]=(unsigned char)(le ? fields[i]>>8 : fields[i]); }
        struct segment_check check={.payload=size-head,.ipv6=v6,.ethernet=eth,.le=le};
        assert(!md_network_virtio_receive(h,le,eth,packet,size,check_segment,&check));
        assert(check.packets==7 && check.copied==check.payload && !memcmp(original,packet,size));
        h[4]=h[5]=0;
        assert(md_network_virtio_receive(h,le,eth,packet,size,check_segment,&check)==-EINVAL);
    }
    for(unsigned offset=0;offset<2;++offset) {
        unsigned char packet[]={0xab,0,0,0,0,0,0},h[10]={VIRTIO_NET_HDR_F_NEEDS_CSUM};
        packet[1+offset]=0x12; packet[2+offset]=0x34; packet[5]=0x56; packet[6]=0x78;
        h[6]=1; h[8]=(unsigned char)offset;
        struct partial_check check={.seed=offset ? 0x3412 : 0x1234};
        assert(!md_network_virtio_receive(h,1,0,packet,sizeof(packet),check_partial,&check) && check.calls==1);
        memset(packet+1,0,sizeof(packet)-1); packet[5]=packet[6]=255;
        check=(struct partial_check){.zero=1,.offset=offset};
        assert(!md_network_virtio_receive(h,1,0,packet,sizeof(packet),check_partial,&check) && check.calls==1);
    }
    puts("PASS virtio packets: IPv4/IPv6 TSO, raw/TAP, both endians, checksums, sequence/ID wrap, FIN/PSH/CWR and borrowed input");
}
static void group_packets(void) {
    for(unsigned mode=0;mode<3;++mode) {
        int v6=mode==2;
        struct md_identity root=md_identity_new(0,0);
        struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
        struct md_control_file f=open_tun(n,&root);
        struct ifreq req={.ifr_flags=IFF_TUN|IFF_NO_PI};
        assert(!control(f,&root,TUNSETIFF,0,&req));
        struct md_network_interface *link=md_network_interface_find(n,0,req.ifr_name);
        assert(!md_network_route_set_flags(n,&root,req.ifr_name,link->info.flags|IFF_UP));
        unsigned index=link->info.index;
        struct md_fd_object *endpoint; int fd;
        unsigned family=v6 ? AF_INET6 : AF_INET;
        assert(!md_network_endpoint_open(n,0,family,SOCK_DGRAM,0,&endpoint,&fd));
        struct md_inet_address group={.ip=md_ip_v4(htonl(mode ? 0xe00000fb : 0xffffffff)),.port=htons(1067)};
        if(v6) { group.ip=md_ip_any(AF_INET6); group.ip.bytes[0]=0xff; group.ip.bytes[1]=2; group.ip.bytes[15]=0xfb; }
        if(mode) {
            struct ip_mreqn v4={.imr_multiaddr.s_addr=group.ip.v4,.imr_ifindex=(int)index};
            struct ipv6_mreq v6req={.ipv6mr_multiaddr=group.ip.v6,.ipv6mr_interface=index};
            socklen_t size=v6 ? sizeof(v6req) : sizeof(v4);
            assert(!md_network_endpoint_sockopt(endpoint,fd,&root,1,v6 ? IPPROTO_IPV6 : IPPROTO_IP,
                v6 ? IPV6_ADD_MEMBERSHIP : IP_ADD_MEMBERSHIP,v6 ? (void *)&v6req : (void *)&v4,&size));
        }
        struct sockaddr_storage address; socklen_t length;
        struct md_inet_address local={.ip=md_ip_any(family),.port=group.port};
        assert(!md_inet_encode(&local,0,&address,&length));
        assert(!md_network_endpoint_bind(endpoint,fd,&root,(void *)&address,length));
        unsigned char packet[64]={0};
        size_t udp=v6 ? 40 : 20,size=udp+12;
        packet[0]=v6 ? 0x60 : 0x45; packet[v6 ? 6 : 9]=IPPROTO_UDP; packet[v6 ? 7 : 8]=32;
        if(v6) {
            put16(packet+4,12); packet[8]=0xfe; packet[9]=0x80; packet[23]=2;
            memcpy(packet+24,group.ip.bytes,16);
        } else {
            put16(packet+2,(unsigned)size); /* Source 0.0.0.0 is valid for discovery. */
            memcpy(packet+16,group.ip.bytes,4);
            put16(packet+10,~check_sum(0,packet,20));
        }
        put16(packet+udp,1068); put16(packet+udp+2,1067); put16(packet+udp+4,12);
        memcpy(packet+udp+8,"DHCP",4);
        if(v6) put16(packet+udp+6,~check_sum(check_sum(12+IPPROTO_UDP,packet+8,32),packet+udp,12));
        assert(f.ops->write(f.context,NULL,packet,size,0)==(long)size);
        struct pollfd ready={.fd=fd,.events=POLLIN};
        /* EVENT_WAIT: group packet is delivered to the ordinary UDP endpoint. */
        assert(poll(&ready,1,2000)==1 && (ready.revents&POLLIN));
        char data[4]; length=sizeof(address);
        assert(recvfrom(fd,data,sizeof(data),0,(void *)&address,&length)==4 && !memcmp(data,"DHCP",4));
        struct md_network_packet_info info;
        assert(!md_network_endpoint_source_info(endpoint,&address,&length,&info));
        assert(info.interface==index && md_ip_equal(info.destination,group.ip) && info.hops==32);
        close(fd); md_fd_object_release(endpoint); md_control_file_release(f);
        md_network_namespace_release(n); md_identity_release(&root);
    }
    puts("PASS packet UDP group ingress: zero-address broadcast, IPv4/IPv6 multicast and endpoint metadata");
}
static void arp_control(void) {
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    struct md_control_file f=open_tun(n,&root);
    struct ifreq req={.ifr_flags=IFF_TAP|IFF_NO_PI};
    assert(!control(f,&root,TUNSETIFF,0,&req));
    assert(!md_network_route_set_flags(n,&root,req.ifr_name,IFF_UP));
    struct md_network_interface *link=md_network_interface_find(n,0,req.ifr_name);
    pthread_mutex_lock(&n->fabric->lock);
    test_network_v4(link,htonl(0xc6336401),24); md_network_forward_changed(n->fabric);
    pthread_mutex_unlock(&n->fabric->lock);
    struct md_fd_object *endpoint; int fd;
    assert(!md_network_endpoint_open(n,0,AF_INET,SOCK_DGRAM,0,&endpoint,&fd));
    struct arpreq arp={.arp_ha={.sa_family=ARPHRD_ETHER},.arp_flags=ATF_COM};
    struct sockaddr_in ip={.sin_family=AF_INET,.sin_addr.s_addr=htonl(0xc6336402)};
    memcpy(&arp.arp_pa,&ip,sizeof(ip)); memcpy(arp.arp_dev,req.ifr_name,IFNAMSIZ);
    const char mac[6]={2,0,0,0,0,7}; memcpy(arp.arp_ha.sa_data,mac,sizeof(mac));
    unsigned long args[6]={0,SIOCSARP};
    assert(md_network_socket_call(endpoint,&user,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL)==-EPERM);
    assert(!md_network_socket_call(endpoint,&root,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL));
    args[1]=SIOCGARP; memset(arp.arp_ha.sa_data,0,6);
    assert(!md_network_socket_call(endpoint,&user,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL));
    assert(arp.arp_flags==ATF_COM && !memcmp(arp.arp_ha.sa_data,mac,6));
    args[1]=SIOCSARP; arp.arp_flags|=ATF_PERM;
    assert(!md_network_socket_call(endpoint,&root,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL));
    args[1]=SIOCGARP;
    assert(!md_network_socket_call(endpoint,&user,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL));
    assert(arp.arp_flags==(ATF_COM|ATF_PERM));
    args[1]=SIOCSARP; arp.arp_flags=ATF_COM|ATF_PUBL;
    assert(md_network_socket_call(endpoint,&root,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL)==-EOPNOTSUPP);
    args[1]=SIOCDARP;
    assert(!md_network_socket_call(endpoint,&root,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL));
    args[1]=SIOCGARP;
    assert(md_network_socket_call(endpoint,&user,SYS_ioctl,args,&arp,sizeof(arp),sizeof(arp),1,NULL,NULL,NULL)==-ENXIO);
    close(fd); md_fd_object_release(endpoint); md_control_file_release(f);
    md_network_namespace_release(n); md_identity_release(&root); md_identity_release(&user);
    puts("PASS packet ARP ioctls: namespace-owned cache, dynamic/permanent entries, readback, permissions and deletion");
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
    req.ifr_flags=IFF_TAP|IFF_MULTI_QUEUE|IFF_VNET_HDR;
    assert(!control(second,&user,TUNSETIFF,0,&req));
    assert(req.ifr_flags==(IFF_TAP|IFF_NO_PI|IFF_MULTI_QUEUE));
    req.ifr_flags=IFF_DETACH_QUEUE;
    assert(!control(second,&user,TUNSETQUEUE,0,&req));
    assert(second.ops->read(second.context,NULL,data,sizeof(data),0)==-EBADFD);
    req.ifr_flags=IFF_ATTACH_QUEUE;
    assert(!control(second,&user,TUNSETQUEUE,0,&req));
    md_control_file_release(second); md_control_file_release(f);
    assert(md_network_interface_find(n,0,"tap0"));
    f=open_tun(n,&root); req.ifr_flags=IFF_TAP|IFF_MULTI_QUEUE|IFF_VNET_HDR;
    assert(!control(f,&root,TUNSETIFF,0,&req));
    assert(req.ifr_flags==(IFF_TAP|IFF_MULTI_QUEUE|IFF_VNET_HDR));
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
    cable->frame_limit=1280+(tap ? 14 : 0);
    cable->stop=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); assert(cable->stop>=0);
    for(unsigned k=0;k<2;++k) {
        spaces[k]=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE); assert(spaces[k]);
        cable->tun[k]=open_tun(spaces[k],root);
        struct ifreq req={.ifr_flags=(short)((tap ? IFF_TAP : IFF_TUN)|IFF_NO_PI)};
        assert(!control(cable->tun[k],root,TUNSETIFF,0,&req));
        pthread_mutex_lock(&spaces[k]->fabric->lock);
        struct md_network_interface *link=md_network_interface_find(spaces[k],0,req.ifr_name); assert(link);
        link->info.flags|=IFF_UP;
        addresses[k]=md_ip_v4(htonl(0x0a170001+k));
        if(ipv6) { addresses[k]=md_ip_any(AF_INET6); addresses[k].bytes[0]=0xfd; addresses[k].bytes[1]=0x23; addresses[k].bytes[15]=(unsigned char)(k+1); }
        assert(!md_network_interface_address(link,(struct md_network_ip_assignment){.address=addresses[k],.prefix=ipv6 ? 64 : 24,.flags=IFA_F_PERMANENT},0,0));
        spaces[k]->routes[spaces[k]->route_count++]=(struct md_network_route_entry){.destination=md_ip_network(addresses[k],ipv6 ? 64 : 24),
            .prefix=ipv6 ? 64 : 24,.gateway=md_ip_any(addresses[k].family),.interface=link->info.index};
        md_network_forward_changed(spaces[k]->fabric);
        /* Configuration after link-up must update both IP families. */
        link->info.mtu=1280;
        md_network_forward_changed(spaces[k]->fabric);
        pthread_mutex_unlock(&spaces[k]->fabric->lock);
    }
}
static void cable_close(struct cable *cable,struct md_network_namespace *spaces[2]) {
    uint64_t stop=1; assert(write(cable->stop,&stop,sizeof(stop))==sizeof(stop));
    assert(!pthread_join(cable->thread,NULL)); close(cable->stop);
    for(unsigned i=0;i<2;++i) { md_control_file_release(cable->tun[i]); md_network_namespace_release(spaces[i]); }
}
static int tcp_observed(int raw,int ipv6) {
    int fin=0;
    for(;;) {
        unsigned char packet[sizeof(struct md_network_packet)+8192];
        ssize_t size=recv(raw,packet,sizeof(packet),MSG_DONTWAIT);
        if(size<0) { assert(errno==EAGAIN || errno==EWOULDBLOCK); return fin; }
        size_t header=sizeof(struct md_network_packet);
        assert((size_t)size>header);
        if(!ipv6) header+=(packet[header]&15)*4;
        assert((size_t)size>=header+20);
        assert(!(packet[header+13]&4));
        fin|=packet[header+13]&1;
    }
}
static void transfer(int tap,int ipv6,int type,int native_server) {
    struct md_identity root=md_identity_new(0,0);
    struct md_network_namespace *spaces[2];
    struct cable cable={0};
    struct md_ip_address addresses[2];
    cable_setup(&cable,&root,spaces,addresses,tap,ipv6);
    struct md_network_namespace *server=spaces[1],*client=spaces[0];
    if(native_server==2) {
        struct md_ip_address target=addresses[1];
        target.bytes[ipv6 ? 1 : 2]^=0x40;
        pthread_mutex_lock(&server->fabric->lock);
        struct md_network_interface *lo=md_network_interface_find(server,0,"lo");
        lo->info.flags|=IFF_UP;
        assert(!md_network_interface_address(lo,(struct md_network_ip_assignment){
            .address=target,.prefix=ipv6 ? 128 : 32,.flags=IFA_F_PERMANENT},0,0));
        md_network_forward_changed(server->fabric);
        pthread_mutex_unlock(&server->fabric->lock);
        pthread_mutex_lock(&client->fabric->lock);
        struct md_network_route_entry route=client->routes[client->route_count-1];
        route.destination=target; route.prefix=ipv6 ? 128 : 32; route.gateway=addresses[1];
        client->routes[client->route_count++]=route;
        md_network_forward_changed(client->fabric);
        pthread_mutex_unlock(&client->fabric->lock);
        addresses[1]=target;
    }
    struct md_fd_object *observer=NULL; int raw=-1;
    if(type==SOCK_DGRAM || !ipv6)
        assert(!md_network_socket_open(client,&root,addresses[0].family,SOCK_RAW,
            type==SOCK_DGRAM ? IPPROTO_UDP : IPPROTO_TCP,&observer,&raw));
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
    int fin=0;
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
        if(type==SOCK_STREAM) { if(raw>=0) fin|=tcp_observed(raw,ipv6); }
        else {
            unsigned char packet[sizeof(struct md_network_packet)+20+8+sizeof(sent)];
            struct pollfd ready={.fd=raw,.events=POLLIN};
            /* EVENT_WAIT: reassembled transport is also delivered to raw observers. */
            assert(poll(&ready,1,5000)==1);
            size_t header=sizeof(struct md_network_packet)+(ipv6 ? 0 : 20)+8;
            assert(recv(raw,packet,sizeof(packet),0)==(ssize_t)(header+sizeof(sent)));
            assert(!memcmp(packet+header,sent,sizeof(sent)));
        }
    }
    if(type==SOCK_DGRAM && native_server) {
        struct md_fd_object *errors; int error_fd;
        assert(!md_network_socket_open(client,&root,addresses[0].family,SOCK_RAW,
            ipv6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP,&errors,&error_fd));
        struct md_network_filter_table table={.handle=1,.family=NFPROTO_INET};
        struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_ACCEPT};
        struct md_filter_rule rule={.chain=2,.count=2,.expressions={
            {.code=MD_FILTER_TRANSPORT,.transport={.protocol=IPPROTO_UDP,.source={0,65535},.destination={0,65535}}},
            {.code=MD_FILTER_REJECT,.key=MD_REJECT_ICMPX,.aux=3}}};
        struct md_network_filter filter={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        pthread_mutex_lock(&server->fabric->lock);
        server->filter=&filter; md_network_forward_changed(server->fabric);
        pthread_mutex_unlock(&server->fabric->lock);
        assert(send(fd,sent,sizeof(sent),MSG_NOSIGNAL)==sizeof(sent));
        for(unsigned packets=0;;++packets) {
            struct pollfd error_ready={.fd=error_fd,.events=POLLIN};
            /* EVENT_WAIT: changed policy refuses an established packet flow. */
            assert(packets<16 && poll(&error_ready,1,2000)==1);
            unsigned char error[2048]; size_t offset=sizeof(struct md_network_packet)+(ipv6 ? 0 : 20);
            assert(recv(error_fd,error,sizeof(error),0)>(ssize_t)(offset+8));
            if(ipv6 && error[offset]>=130 && error[offset]<=143) continue;
            assert(error[offset]==(ipv6 ? 1 : 3) && error[offset+1]==(ipv6 ? 1 : 13)); break;
        }
        pthread_mutex_lock(&server->fabric->lock);
        server->filter=NULL; md_network_forward_changed(server->fabric);
        pthread_mutex_unlock(&server->fabric->lock);
        close(error_fd); md_fd_object_release(errors);
    }
    if(type==SOCK_STREAM) {
        assert(!shutdown(fd,SHUT_WR));
        struct pollfd ready={.fd=fd,.events=POLLIN};
        /* EVENT_WAIT: both packet legs finish gracefully, without a relay reset. */
        assert(poll(&ready,1,5000)==1);
        assert(recv(fd,received,1,0)==0);
        while(raw>=0 && !fin) {
            struct pollfd observed={.fd=raw,.events=POLLIN};
            /* EVENT_WAIT: packet-side FIN, not only EOF on the local carrier. */
            assert(poll(&observed,1,5000)==1);
            fin|=tcp_observed(raw,ipv6);
        }
    }
    close(fd); md_fd_object_release(endpoint);
    if(native_server) { assert(!pthread_join(echo.thread,NULL)); close(echo.fd); md_fd_object_release(server_endpoint); }
    if(raw>=0) close(raw);
    md_fd_object_release(observer); cable_close(&cable,spaces);
    md_identity_release(&root);
    printf("PASS %s IPv%u %s %s server and teardown\n",tap ? "TAP" : "TUN",ipv6 ? 6 : 4,type==SOCK_STREAM ? "TCP" : "UDP",
        native_server==2 ? "off-subnet native" : native_server ? "native" : "packet");
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
        if(ipv6 && ((reply[header]>=130 && reply[header]<=137) || reply[header]==143)) continue; /* ND/MLD share raw ICMPv6. */
        assert(received==header+sizeof(echo));
        break;
    }
    assert(reply[header]==(ipv6 ? 129 : 0) && !memcmp(reply+header+4,echo+4,sizeof(echo)-4));
    close(fd); md_fd_object_release(socket); cable_close(&cable,spaces); md_identity_release(&root);
    printf("PASS %s IPv%u raw ICMP, neighbor resolution and reply observation\n",tap ? "TAP" : "TUN",ipv6 ? 6 : 4);
}
static void packet_reject(int tap,int ipv6) {
    struct md_identity root=md_identity_new(0,0);
    struct md_network_namespace *spaces[2]; struct md_ip_address addresses[2];
    struct cable cable={0}; cable_setup(&cable,&root,spaces,addresses,tap,ipv6);
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_INET};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_ACCEPT};
    struct md_filter_rule rule={.chain=2,.count=2,.expressions={{.code=MD_FILTER_TRANSPORT,
        .transport={.protocol=IPPROTO_UDP,.source={0,65535},.destination={0,65535}}},
        {.code=MD_FILTER_REJECT,.key=MD_REJECT_ICMPX,.aux=3}}};
    struct md_network_filter filter={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
    pthread_mutex_lock(&spaces[1]->fabric->lock);
    spaces[1]->filter=&filter; md_network_forward_changed(spaces[1]->fabric);
    pthread_mutex_unlock(&spaces[1]->fabric->lock);
    struct md_fd_object *observer; int raw;
    assert(!md_network_socket_open(spaces[0],&root,addresses[0].family,SOCK_RAW,
        ipv6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP,&observer,&raw));
    assert(!pthread_create(&cable.thread,NULL,cable_run,&cable));
    for(unsigned scenario=0;scenario<5;++scenario) {
        unsigned transport=scenario==4 ? 0 : scenario==3 ? 2 : scenario;
        int expected=scenario<3;
        unsigned protocol=transport==0 ? IPPROTO_UDP : transport==1 ? IPPROTO_TCP : ipv6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP;
        unsigned icmp_type=scenario==3 ? (ipv6 ? 1 : 3) : (ipv6 ? 128 : 8);
        pthread_mutex_lock(&spaces[1]->fabric->lock);
        rule.expressions[0]=transport<2 ? (struct md_filter_expression){.code=MD_FILTER_TRANSPORT,
            .transport={.protocol=protocol,.source={0,65535},.destination={0,65535}}}
            : (struct md_filter_expression){.code=MD_FILTER_ICMP_MATCH,.key=protocol,
                .value={icmp_type,0,0,0}};
        md_network_forward_changed(spaces[1]->fabric);
        pthread_mutex_unlock(&spaces[1]->fabric->lock);
        unsigned char packet[1240]={0}; unsigned header=ipv6 ? 40 : 24;
        unsigned length=sizeof(packet)-header;
        if(ipv6) { packet[0]=0x60; put16(packet+4,length); packet[6]=protocol; packet[7]=64; }
        else {
            packet[0]=0x46; packet[8]=64; packet[9]=protocol; put16(packet+2,sizeof(packet));
            memset(packet+20,1,4); /* IPv4 NOP options must survive in the quoted header. */
        }
        memcpy(packet+(ipv6 ? 8 : 12),addresses[0].bytes,ipv6 ? 16 : 4);
        memcpy(packet+(ipv6 ? 24 : 16),addresses[1].bytes,ipv6 ? 16 : 4);
        if(!ipv6) put16(packet+10,~check_sum(0,packet,header));
        unsigned char *body=packet+header;
        put16(body,32123); put16(body+2,12345);
        unsigned checksum;
        if(transport==0) { put16(body+4,length); checksum=6; }
        else if(transport==1) { body[12]=0x50; body[13]=2; checksum=16; }
        else { body[0]=icmp_type; body[1]=0; body[2]=body[3]=0; checksum=2; }
        unsigned sum=transport==2 && !ipv6 ? 0 : check_sum(length+protocol,packet+(ipv6 ? 8 : 12),ipv6 ? 32 : 8);
        put16(body+checksum,~check_sum(sum,body,length));
        if(scenario==4) body[length-1]^=1;
        struct ifreq request; assert(!control(cable.tun[0],&root,TUNGETIFF,0,&request));
        pthread_mutex_lock(&spaces[0]->fabric->lock);
        unsigned index=md_network_interface_find(spaces[0],0,request.ifr_name)->info.index;
        assert(!md_network_stack_send_ip(spaces[0],index,packet,sizeof(packet)));
        pthread_mutex_unlock(&spaces[0]->fabric->lock);
        unsigned char reply[2048]; size_t offset=sizeof(struct md_network_packet)+(ipv6 ? 0 : 20);
        ssize_t size;
        for(unsigned received=0;;++received) {
            struct pollfd ready={.fd=raw,.events=POLLIN};
            /* EVENT_WAIT: an actual ICMP refusal; timeout fails the test. */
            int polled=poll(&ready,1,expected ? 2000 : 100);
            if(!expected && !polled) break;
            if(polled!=1) fprintf(stderr,"reject timeout: tap=%d ipv6=%d protocol=%u\n",tap,ipv6,protocol);
            assert(received<16 && polled==1);
            size=recv(raw,reply,sizeof(reply),MSG_DONTWAIT); assert(size>(ssize_t)offset);
            if(ipv6 && reply[offset]>=130 && reply[offset]<=143) continue;
            assert(expected);
            break;
        }
        if(!expected) continue;
        assert(reply[offset]==(ipv6 ? 1 : 3) && reply[offset+1]==(ipv6 ? 1 : 13));
        unsigned quote=(ipv6 ? 1280 : 576)-(ipv6 ? 40 : 20)-8;
        assert(size==(ssize_t)(offset+8+quote));
        assert(!memcmp(reply+offset+8,packet,quote));
        sum=ipv6 ? check_sum(8+quote+IPPROTO_ICMPV6,addresses[1].bytes,16) : 0;
        if(ipv6) sum=check_sum(sum,addresses[0].bytes,16);
        assert(check_sum(sum,reply+offset,8+quote)==65535);
    }
    pthread_mutex_lock(&spaces[1]->fabric->lock);
    rule.expressions[0]=(struct md_filter_expression){.code=MD_FILTER_TRANSPORT,
        .transport={.protocol=IPPROTO_TCP,.source={0,65535},.destination={0,65535}}};
    md_network_forward_changed(spaces[1]->fabric);
    pthread_mutex_unlock(&spaces[1]->fabric->lock);
    struct md_fd_object *endpoint; int fd;
    assert(!md_network_endpoint_open(spaces[0],0,addresses[0].family,SOCK_STREAM|SOCK_NONBLOCK,0,&endpoint,&fd));
    struct md_inet_address target={.ip=addresses[1],.port=htons(32123)};
    struct sockaddr_storage logical,native; socklen_t size,length;
    assert(!md_inet_encode(&target,0,&logical,&size));
    assert(!md_network_endpoint_autobind(endpoint,fd,&root,(void *)&logical,size,0));
    assert(!md_network_endpoint_destination(endpoint,(void *)&logical,size,&native,&length));
    int connected=connect(fd,(void *)&native,length);
    assert(!connected || errno==EINPROGRESS);
    assert(!md_network_endpoint_connected(endpoint,fd,(void *)&logical,size));
    struct pollfd ready={.events=POLLIN};
    if(md_network_endpoint_connect_result(endpoint,&ready.fd)==-EINPROGRESS) {
        /* EVENT_WAIT: ICMP refusal completes the pending packet-side handshake. */
        assert(ready.fd>=0 && poll(&ready,1,2000)==1);
    }
    assert(md_network_endpoint_connect_result(endpoint,NULL)==-EACCES);
    int error=0; socklen_t error_size=sizeof(error);
    assert(!md_network_endpoint_sockopt(endpoint,fd,NULL,0,SOL_SOCKET,SO_ERROR,&error,&error_size) && error==EACCES);
    close(fd); md_fd_object_release(endpoint);
    pthread_mutex_lock(&spaces[1]->fabric->lock);
    spaces[1]->filter=NULL; md_network_forward_changed(spaces[1]->fabric);
    pthread_mutex_unlock(&spaces[1]->fabric->lock);
    close(raw); md_fd_object_release(observer); cable_close(&cable,spaces); md_identity_release(&root);
    printf("PASS %s IPv%u ICMP rejection: TCP/UDP/echo, checksum, bounded quote, IPv4 options and pending socket error\n",tap ? "TAP" : "TUN",ipv6 ? 6 : 4);
}
void test_network_tun(void) {
    lifecycle(); packet_contract(); virtio_header(); virtio_packets(); group_packets(); arp_control(); retiring_destination();
    for(int server=0;server<3;++server) for(int tap=0;tap<2;++tap) for(int v6=0;v6<2;++v6) for(int tcp=0;tcp<2;++tcp)
        transfer(tap,v6,tcp ? SOCK_STREAM : SOCK_DGRAM,server);
    for(int tap=0;tap<2;++tap) for(int v6=0;v6<2;++v6) raw_echo(tap,v6);
    for(int tap=0;tap<2;++tap) for(int v6=0;v6<2;++v6) packet_reject(tap,v6);
}
