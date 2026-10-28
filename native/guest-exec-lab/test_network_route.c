#define _GNU_SOURCE
#include "network_route.h"
#include "network_internal.h"
#include "network_route_ip.h"
#include "network_nexthop.h"
#include "network_profile.h"
#include "network_packet.h"
#include "network_endpoint.h"
#include "test_network_fixture.h"
#include "guest_identity.h"
#include "sysfs_view.h"
#include <assert.h>
#include <errno.h>
#include <linux/rtnetlink.h>
#include <linux/fib_rules.h>
#include <linux/veth.h>
#include <linux/nexthop.h>
#include <net/if.h>
#include <netinet/in.h>
#include <linux/sockios.h>
#include <linux/if_arp.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>

struct packet { union { struct nlmsghdr align; unsigned char bytes[2048]; }; };
static struct nlmsghdr *request(struct packet *p,unsigned type,unsigned flags) {
    memset(p,0,sizeof(*p));
    struct nlmsghdr *h=(void *)p->bytes;
    *h=(struct nlmsghdr){.nlmsg_len=NLMSG_LENGTH(sizeof(struct ifinfomsg)),.nlmsg_type=type,
        .nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK|flags,.nlmsg_seq=71};
    return h;
}
static struct rtattr *attr(struct nlmsghdr *h,unsigned type,const void *data,size_t size) {
    assert(h->nlmsg_len+RTA_SPACE(size)<sizeof(struct packet));
    struct rtattr *a=(void *)((char *)h+h->nlmsg_len);
    a->rta_type=type; a->rta_len=RTA_LENGTH(size);
    if (size) memcpy(RTA_DATA(a),data,size);
    h->nlmsg_len+=RTA_SPACE(size); return a;
}
static int ack(struct md_network_namespace *n,const struct md_identity *id,struct nlmsghdr *h,
        const struct md_network_target *resolver) {
    struct packet reply;
    long length=md_network_route(n,id,h,h->nlmsg_len,91,0,resolver,reply.bytes,sizeof(reply),NULL);
    assert(length>=(long)NLMSG_LENGTH(sizeof(struct nlmsgerr)));
    struct nlmsghdr *out=(void *)reply.bytes;
    assert(out->nlmsg_type==NLMSG_ERROR && out->nlmsg_seq==71 && out->nlmsg_pid==91);
    int error=((struct nlmsgerr *)NLMSG_DATA(out))->error;
    if (error) {
        assert(length==(long)(NLMSG_LENGTH(sizeof(struct nlmsgerr))+h->nlmsg_len-sizeof(*h)));
        assert(!memcmp((char *)NLMSG_DATA(out)+sizeof(struct nlmsgerr),NLMSG_DATA(h),h->nlmsg_len-sizeof(*h)));
    } else assert(out->nlmsg_flags&NLM_F_CAPPED);
    return error;
}
static int resolve(void *context,struct md_network_target_reference ref,struct md_network_namespace **out) {
    if (ref.kind==MD_NETWORK_TARGET_FD) { if (ref.value!=7) return -EBADF; }
    else if (ref.kind==MD_NETWORK_TARGET_PID) { if (ref.value!=42) return -ESRCH; }
    else return -EINVAL;
    *out=md_network_namespace_retain(context); return 0;
}
static int reject_response(void *context,const void *data,size_t size) {
    (void)context; (void)data; assert(size); return -EAGAIN;
}
static int reject_after_notification(void *context,const void *data,size_t size) {
    (void)data; assert(size);
    unsigned char packet[2048];
    int fd=*(int *)context;
    ssize_t bytes=recv(fd,packet,sizeof(packet),MSG_DONTWAIT);
    assert(bytes>(ssize_t)(sizeof(struct md_network_packet)+sizeof(struct nlmsghdr)));
    struct md_network_packet envelope; memcpy(&envelope,packet,sizeof(envelope));
    assert(envelope.group==RTNLGRP_IPV6_IFADDR);
    struct nlmsghdr header; memcpy(&header,packet+sizeof(envelope),sizeof(header));
    assert(header.nlmsg_type==RTM_NEWADDR && !header.nlmsg_seq);
    return -EAGAIN;
}
static void route_metrics(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0);
    struct md_network_interface *link=md_network_interface_find(n,0,"eth0"); assert(link);
    struct packet packet;
    struct nlmsghdr *h=request(&packet,RTM_NEWROUTE,NLM_F_CREATE|NLM_F_EXCL);
    h->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg));
    struct rtmsg *m=NLMSG_DATA(h);
    *m=(struct rtmsg){.rtm_family=AF_INET6,.rtm_dst_len=64,.rtm_table=RT_TABLE_MAIN,
        .rtm_protocol=RTPROT_BOOT,.rtm_type=RTN_UNICAST};
    struct md_ip_address destination;
    assert(!md_network_profile_ip("2001:db8:42::",&destination));
    attr(h,RTA_DST,destination.bytes,16); attr(h,RTA_OIF,&link->info.index,4);
    struct rtattr *nested=attr(h,RTA_METRICS|NLA_F_NESTED,NULL,0);
    unsigned mtu=1440,hops=37,quick=0;
    struct rtattr *mtu_attribute=attr(h,RTAX_MTU,&mtu,4);
    attr(h,RTAX_HOPLIMIT,&hops,4); struct rtattr *q=attr(h,RTAX_QUICKACK,&quick,4);
    nested->rta_len=(char *)h+h->nlmsg_len-(char *)nested;
    assert(!ack(n,&root,h,NULL));
    struct md_network_route_entry route;
    assert(md_network_route_find(n,destination,&route));
    assert(route.metrics.mtu==mtu && route.metrics.hops==hops);
    assert(route.metrics.present==((1U<<RTAX_MTU)|(1U<<RTAX_HOPLIMIT)|(1U<<RTAX_QUICKACK)));
    struct packet reply;
    struct md_network_message out={.data=reply.bytes,.capacity=sizeof(reply)};
    assert(!md_network_route_reply(&out,h,91,1,link,&route,NULL));
    struct nlmsghdr *rh=(void *)reply.bytes;
    unsigned remaining=rh->nlmsg_len-NLMSG_LENGTH(sizeof(struct rtmsg));
    struct rtattr *found=NULL;
    for (struct rtattr *a=RTM_RTA((struct rtmsg *)NLMSG_DATA(rh));RTA_OK(a,remaining);a=RTA_NEXT(a,remaining))
        if ((a->rta_type&NLA_TYPE_MASK)==RTA_METRICS) found=a;
    assert(found && RTA_PAYLOAD(found)==RTA_PAYLOAD(nested));
    assert(!memcmp(RTA_DATA(found),RTA_DATA(nested),RTA_PAYLOAD(found)));
    h->nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK|NLM_F_REPLACE;
    struct md_fd_object *socket; int fd;
    assert(!md_network_endpoint_open(n,0,AF_INET6,SOCK_DGRAM,0,&socket,&fd));
    struct sockaddr_in6 address={.sin6_family=AF_INET6,.sin6_port=htons(12345)};
    memcpy(&address.sin6_addr,destination.bytes,16);
    assert(!md_network_endpoint_autobind(socket,fd,&root,(void *)&address,sizeof(address),0));
    assert(!md_network_endpoint_connected(socket,fd,(void *)&address,sizeof(address)));
    assert(!ack(n,&root,h,NULL));
    mtu=1280; memcpy(RTA_DATA(mtu_attribute),&mtu,4);
    assert(ack(n,&root,h,NULL)==-EBUSY);
    assert(md_network_route_find(n,destination,&route) && route.metrics.mtu==1440);
    close(fd); md_fd_object_release(socket);
    assert(!ack(n,&root,h,NULL));
    assert(md_network_route_find(n,destination,&route) && route.metrics.mtu==1280);
    quick=1; memcpy(RTA_DATA(q),&quick,4);
    assert(ack(n,&root,h,NULL)==-EOPNOTSUPP);
    assert(md_network_route_find(n,destination,&route) && route.metrics.hops==37);
    quick=0; memcpy(RTA_DATA(q),&quick,4);
    attr(h,RTAX_MTU,&mtu,4); nested->rta_len=(char *)h+h->nlmsg_len-(char *)nested;
    assert(ack(n,&root,h,NULL)==-EINVAL);
    md_identity_release(&root); md_network_namespace_release(n);
    puts("PASS IPv6 route metrics: parsing, dump, active connection rollback and unsupported/duplicate rejection");
}
void test_network_route(void) {
    route_metrics();
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct packet p;
    struct nlmsghdr *h=request(&p,RTM_SETLINK,NLM_F_CREATE|NLM_F_EXCL);
    struct ifinfomsg *loopback=NLMSG_DATA(h);
    loopback->ifi_index=1; loopback->ifi_flags=loopback->ifi_change=IFF_UP;
    assert(!ack(n,&root,h,NULL) && n->count==2);
    loopback->ifi_index=999;
    assert(ack(n,&root,h,NULL)==-ENODEV && n->count==2);
    h=request(&p,RTM_NEWLINK,NLM_F_CREATE|NLM_F_EXCL);
    attr(h,IFLA_IFNAME,"br-test",8);
    struct rtattr *info=attr(h,IFLA_LINKINFO|NLA_F_NESTED,NULL,0);
    attr(h,IFLA_INFO_KIND,"bridge",6);
    info->rta_len=(char *)h+h->nlmsg_len-(char *)info;
    assert(ack(n,&user,h,NULL)==-EPERM && n->count==2);
    struct packet rejected;
    struct md_network_publication refusal={.publish=reject_response};
    unsigned original_index=n->fabric->next_index;
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,NULL,rejected.bytes,sizeof(rejected),&refusal)==-EAGAIN);
    assert(n->count==2 && n->fabric->next_index==original_index);
    assert(!md_network_interface_find(n,0,"br-test"));
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,NULL,rejected.bytes,0,NULL)==-EMSGSIZE);
    assert(n->count==2 && n->fabric->next_index==original_index);
    assert(!ack(n,&root,h,NULL) && n->count==3);
    assert(ack(n,&root,h,NULL)==-EEXIST && n->count==3);
    unsigned bridge=md_network_interface_find(n,0,"br-test")->info.index;
    struct md_sysfs *sysfs=md_sysfs_new(AT_FDCWD,n,&root); assert(sysfs);
    struct stat bridge_node;
    assert(!fstatat(md_sysfs_root(sysfs),"firmware",&bridge_node,0) && S_ISDIR(bridge_node.st_mode));
    assert(fstatat(md_sysfs_root(sysfs),"firmware/ibft",&bridge_node,0)==-1 && errno==ENOENT);
    assert(!md_sysfs_host_visible("/firmware"));
    assert(!fstatat(md_sysfs_root(sysfs),"class/net/br-test/bridge",&bridge_node,0));
    assert(S_ISDIR(bridge_node.st_mode));
    assert(fstatat(md_sysfs_root(sysfs),"class/net/lo/bridge",&bridge_node,0)==-1 && errno==ENOENT);
    h=request(&p,RTM_NEWLINK,NLM_F_CREATE|NLM_F_EXCL);
    attr(h,IFLA_IFNAME,"veth-host",10);
    info=attr(h,IFLA_LINKINFO|NLA_F_NESTED,NULL,0);
    attr(h,IFLA_INFO_KIND,"veth",5);
    struct rtattr *data=attr(h,IFLA_INFO_DATA|NLA_F_NESTED,NULL,0);
    struct ifinfomsg zero={0};
    struct rtattr *peer=attr(h,VETH_INFO_PEER|NLA_F_NESTED,&zero,sizeof(zero));
    attr(h,IFLA_IFNAME,"veth-guest",11);
    unsigned queue_length=77;
    attr(h,IFLA_TXQLEN,&queue_length,sizeof(queue_length));
    peer->rta_len=(char *)h+h->nlmsg_len-(char *)peer;
    data->rta_len=(char *)h+h->nlmsg_len-(char *)data;
    info->rta_len=(char *)h+h->nlmsg_len-(char *)info;
    assert(!ack(n,&root,h,NULL) && n->count==5);
    unsigned endpoint=md_network_interface_find(n,0,"veth-guest")->info.index;
    assert(md_network_interface_find(n,endpoint,NULL)->info.tx_queue_length==77);
    struct md_fd_object *socket=md_network_socket_new(n);
    assert(socket && md_network_interface_ioctl(SIOCGIFHWADDR) && md_network_interface_ioctl(SIOCSIFHWADDR));
    struct ifreq mac={0}; strcpy(mac.ifr_name,"veth-guest");
    mac.ifr_hwaddr.sa_family=ARPHRD_ETHER;
    const unsigned char address[6]={2,3,4,5,6,7};
    memcpy(mac.ifr_hwaddr.sa_data,address,6);
    unsigned long io[6]={0,SIOCSIFHWADDR};
    assert(md_network_socket_call(socket,&user,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EPERM);
    assert(!md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    assert(!memcmp(md_network_interface_find(n,endpoint,NULL)->info.address,address,6));
    mac.ifr_hwaddr.sa_data[0]=1;
    assert(md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EADDRNOTAVAIL);
    io[1]=SIOCGIFHWADDR;
    assert(!md_network_socket_call(socket,&user,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    assert(mac.ifr_hwaddr.sa_family==ARPHRD_ETHER && !memcmp(mac.ifr_hwaddr.sa_data,address,6));
    strcpy(mac.ifr_name,"br-test"); mac.ifr_ifindex=(int)endpoint; io[1]=SIOCBRADDIF;
    assert(md_network_socket_call(socket,&user,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EPERM);
    assert(!md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    assert(md_network_interface_find(n,endpoint,NULL)->master==bridge);
    assert(md_network_interface_ioctl(SIOCSIFFLAGS) && md_network_interface_ioctl(SIOCSIFMTU));
    strcpy(mac.ifr_name,"veth-guest"); io[1]=SIOCGIFFLAGS;
    assert(!md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    mac.ifr_flags|=IFF_UP; io[1]=SIOCSIFFLAGS;
    assert(md_network_socket_call(socket,&user,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EPERM);
    assert(!md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    assert(md_network_interface_find(n,endpoint,NULL)->info.flags&IFF_UP);
    mac.ifr_flags|=IFF_PROMISC;
    assert(md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EOPNOTSUPP);
    assert(!(md_network_interface_find(n,endpoint,NULL)->info.flags&IFF_PROMISC));
    mac.ifr_flags&=~(IFF_PROMISC|IFF_UP);
    assert(!md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    assert(!(md_network_interface_find(n,endpoint,NULL)->info.flags&IFF_UP));
    io[1]=SIOCSIFMTU; mac.ifr_mtu=1400;
    assert(md_network_socket_call(socket,&user,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EPERM);
    assert(!md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL));
    assert(md_network_interface_find(n,endpoint,NULL)->info.mtu==1400);
    mac.ifr_mtu=-1;
    assert(md_network_socket_call(socket,&root,SYS_ioctl,io,&mac,sizeof(mac),sizeof(mac),1,NULL,NULL,NULL)==-EOPNOTSUPP);
    assert(md_network_interface_find(n,endpoint,NULL)->info.mtu==1400);
    h=request(&p,RTM_SETLINK,0);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index=endpoint;
    unsigned no_master=0; attr(h,IFLA_MASTER,&no_master,sizeof(no_master));
    assert(!ack(n,&root,h,NULL));
    md_fd_object_release(socket);
    struct md_control_file sysctl=md_network_sysctl_new(n,MD_NET_RP_FILTER,"veth-guest",NULL);
    assert(sysctl.ops);
    struct md_control_actor actor={.identity=&root};
    char value[8]; struct stat original_policy,current_policy;
    assert(!sysctl.ops->stat(sysctl.context,&original_policy));
    assert(sysctl.ops->write(sysctl.context,&actor,"2",1,0)==1);
    h=request(&p,RTM_NEWLINK,0);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index=endpoint;
    attr(h,IFLA_IFNAME,"eth0",4); unsigned fd=7;
    struct rtattr *selection=attr(h,IFLA_NET_NS_FD,&fd,sizeof(fd));
    struct md_network_target target={.context=child,.resolve=resolve};
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,&target,rejected.bytes,sizeof(rejected),&refusal)==-EAGAIN);
    assert(md_network_interface_find(n,endpoint,NULL) && !md_network_interface_find(child,endpoint,NULL));
    assert(sysctl.ops->read(sysctl.context,&actor,value,sizeof(value),0)==2 && !memcmp(value,"2\n",2));
    assert(!ack(n,&root,h,&target));
    assert(!md_network_interface_find(n,endpoint,NULL));
    assert(md_network_interface_find(child,endpoint,NULL));
    assert(sysctl.ops->read(sysctl.context,&actor,value,sizeof(value),0)==-ENODEV);
    assert(!md_network_interface_move(child,endpoint,n,"veth-guest"));
    assert(sysctl.ops->read(sysctl.context,&actor,value,sizeof(value),0)==-ENODEV);
    assert(sysctl.ops->write(sysctl.context,&actor,"1",1,0)==-ENODEV);
    struct md_control_file reopened=md_network_sysctl_new(n,MD_NET_RP_FILTER,"veth-guest",NULL);
    assert(reopened.ops && !reopened.ops->stat(reopened.context,&current_policy));
    assert(current_policy.st_ino!=original_policy.st_ino);
    assert(reopened.ops->read(reopened.context,&actor,value,sizeof(value),0)==2 && !memcmp(value,"0\n",2));
    md_control_file_release(reopened); md_control_file_release(sysctl);
    assert(!ack(n,&root,h,&target));
    assert(!md_network_interface_move(child,endpoint,n,"veth-guest"));
    selection->rta_type=IFLA_NET_NS_PID;
    unsigned pid=999; memcpy(RTA_DATA(selection),&pid,sizeof(pid));
    assert(ack(n,&root,h,&target)==-ESRCH && md_network_interface_find(n,endpoint,NULL));
    pid=42; memcpy(RTA_DATA(selection),&pid,sizeof(pid));
    unsigned prior=h->nlmsg_len;
    attr(h,IFLA_NET_NS_FD,&fd,sizeof(fd));
    assert(ack(n,&root,h,&target)==-EINVAL && md_network_interface_find(n,endpoint,NULL));
    h->nlmsg_len=prior;
    assert(!ack(n,&root,h,&target));
    h=request(&p,RTM_NEWLINK,0);
    struct ifinfomsg *link=NLMSG_DATA(h);
    link->ifi_index=endpoint; link->ifi_change=IFF_UP; link->ifi_flags=IFF_UP;
    unsigned invalid=999;
    attr(h,IFLA_MASTER,&invalid,sizeof(invalid));
    assert(ack(child,&root,h,NULL)==-EINVAL);
    assert(!(md_network_interface_find(child,endpoint,NULL)->info.flags&IFF_UP));
    h=request(&p,RTM_GETLINK,NLM_F_DUMP);
    struct packet reply;
    long length=md_network_route(n,&user,h,h->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0);
    unsigned links=0,done=0,bridges=0;
    for (struct nlmsghdr *item=(void *)reply.bytes;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
        assert(item->nlmsg_seq==71);
        if (item->nlmsg_type==RTM_NEWLINK) {
            ++links;
            const struct ifinfomsg *m=NLMSG_DATA(item);
            int left=IFLA_PAYLOAD(item);
            for (const struct rtattr *a=IFLA_RTA(m);RTA_OK(a,left);a=RTA_NEXT(a,left)) {
                assert(a->rta_type!=(IFLA_LINKINFO|NLA_F_NESTED));
                if (a->rta_type==IFLA_LINKINFO && (unsigned)m->ifi_index==bridge) {
                    const struct rtattr *kind=RTA_DATA(a);
                    assert(kind->rta_type==IFLA_INFO_KIND && !strcmp(RTA_DATA(kind),"bridge"));
                    ++bridges;
                }
            }
        }
        else { assert(item->nlmsg_type==NLMSG_DONE); ++done; }
    }
    assert(!length && links==4 && done==1 && bridges==1);
    h=request(&p,RTM_GETRULE,NLM_F_DUMP);
    h->nlmsg_len=NLMSG_LENGTH(sizeof(struct fib_rule_hdr));
    length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
    unsigned rules=0;
    for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
        if (item->nlmsg_type==NLMSG_DONE) continue;
        assert(item->nlmsg_type==RTM_NEWRULE);
        const struct fib_rule_hdr *rule=NLMSG_DATA(item);
        int left=item->nlmsg_len-NLMSG_LENGTH(sizeof(*rule)),protocols=0;
        for (const struct rtattr *a=(const void *)(rule+1);RTA_OK(a,left);a=RTA_NEXT(a,left))
            if (a->rta_type==FRA_PROTOCOL) {
                assert(RTA_PAYLOAD(a)==1 && *(const unsigned char *)RTA_DATA(a)==RTPROT_KERNEL);
                ++protocols;
            }
        assert(!left && protocols==1); ++rules;
    }
    assert(!length && rules==5);
    h=request(&p,RTM_GETNEXTHOP,NLM_F_DUMP);
    h->nlmsg_len=NLMSG_LENGTH(8); /* struct nhmsg */
    length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length==(long)NLMSG_LENGTH(sizeof(int)) && reply.align.nlmsg_type==NLMSG_DONE);
    assert(*(int *)NLMSG_DATA(&reply.align)==0);
    h=request(&p,RTM_NEWNEXTHOP,0);
    h->nlmsg_len=NLMSG_LENGTH(8);
    assert(ack(n,&root,h,NULL)==-EINVAL);
    for (unsigned bad=0;bad<4;++bad) {
        h=request(&p,RTM_GETLINK,NLM_F_DUMP);
        struct ifinfomsg *body=NLMSG_DATA(h);
        if (bad==0) h->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtgenmsg));
        if (bad==1) body->ifi_index=1;
        if (bad==2) body->__ifi_pad=1;
        if (bad==3) attr(h,IFLA_IFNAME,"lo",3);
        length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
        assert(length>=(long)NLMSG_LENGTH(sizeof(struct nlmsgerr)));
        assert(reply.align.nlmsg_type==NLMSG_ERROR);
        assert(((struct nlmsgerr *)NLMSG_DATA(&reply.align))->error==(bad==3 ? -EOPNOTSUPP : -EINVAL));
    }
    h=request(&p,RTM_GETLINK,NLM_F_DUMP);
    length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0); links=done=0;
    for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
        assert(item->nlmsg_type==RTM_NEWLINK || item->nlmsg_type==NLMSG_DONE);
        if (item->nlmsg_type==RTM_NEWLINK) ++links; else ++done;
    }
    assert(!length && links==4 && done==1);
    /* IPv4 queries and legacy generic link queries share the same catalogue. */
    const unsigned link_families[]={AF_INET,AF_UNIX};
    for (unsigned family=0;family<2;++family) for (unsigned strict=0;strict<2;++strict) {
        h=request(&p,RTM_GETLINK,NLM_F_DUMP);
        ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_family=link_families[family];
        if (!strict) h->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtgenmsg));
        length=md_network_route(n,&user,h,h->nlmsg_len,91,strict ? MD_NETWORK_ROUTE_STRICT : 0,
            NULL,reply.bytes,sizeof(reply),NULL);
        assert(length>0); links=done=0;
        for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
            assert(item->nlmsg_type==RTM_NEWLINK || item->nlmsg_type==NLMSG_DONE);
            if (item->nlmsg_type==RTM_NEWLINK) ++links; else ++done;
        }
        assert(!length && links==4 && done==1);
    }
    h=request(&p,RTM_GETLINK,0);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_family=AF_INET;
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index=1;
    length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0 && reply.align.nlmsg_type==RTM_NEWLINK);
    assert(((struct ifinfomsg *)NLMSG_DATA(&reply.align))->ifi_index==1);
    uint32_t extension_mask=0x109;
    attr(h,IFLA_EXT_MASK,&extension_mask,sizeof(extension_mask));
    length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0 && reply.align.nlmsg_type==RTM_NEWLINK);
    for (unsigned family=0;family<2;++family) {
        h=request(&p,RTM_GETADDR,NLM_F_DUMP);
        h->nlmsg_len=NLMSG_LENGTH(sizeof(struct ifaddrmsg));
        struct ifaddrmsg *body=NLMSG_DATA(h);
        *body=(struct ifaddrmsg){.ifa_family=family ? AF_INET6 : AF_INET,.ifa_index=1};
        length=md_network_route(n,&user,h,h->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,NULL,reply.bytes,sizeof(reply),NULL);
        unsigned addresses=0; done=0;
        for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
            if (item->nlmsg_type==RTM_NEWADDR) {
                const struct ifaddrmsg *a=NLMSG_DATA(item);
                assert(a->ifa_index==1 && a->ifa_family==body->ifa_family); ++addresses;
            } else { assert(item->nlmsg_type==NLMSG_DONE && !*(int *)NLMSG_DATA(item)); ++done; }
        }
        assert(!length && addresses==1 && done==1);
    }
    for (unsigned strict=0;strict<2;++strict) {
        h=request(&p,RTM_GETLINK,NLM_F_DUMP);
        ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_family=AF_INET6;
        if (!strict) h->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtgenmsg));
        length=md_network_route(n,&user,h,h->nlmsg_len,91,strict ? MD_NETWORK_ROUTE_STRICT : 0,
            NULL,reply.bytes,sizeof(reply),NULL);
        assert(length>0); links=done=0;
        for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
            if (item->nlmsg_type==RTM_NEWLINK) {
                assert(((struct ifinfomsg *)NLMSG_DATA(item))->ifi_family==AF_INET6);
                ++links;
            } else { assert(item->nlmsg_type==NLMSG_DONE && !*(int *)NLMSG_DATA(item)); ++done; }
        }
        assert(!length && links==4 && done==1);
    }
    h=request(&p,RTM_NEWLINK,NLM_F_CREATE|NLM_F_EXCL);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_family=AF_INET6;
    assert(ack(n,&root,h,NULL)==-EINVAL);
    h=request(&p,RTM_NEWLINK,0);
    *(struct ifinfomsg *)NLMSG_DATA(h)=(struct ifinfomsg){.ifi_family=AF_INET6,.ifi_index=1};
    struct rtattr *af=attr(h,IFLA_AF_SPEC,NULL,0),*inet6=attr(h,AF_INET6,NULL,0);
    unsigned char addrgen_mode=1;
    attr(h,IFLA_INET6_ADDR_GEN_MODE,&addrgen_mode,1);
    inet6->rta_len=(char *)h+h->nlmsg_len-(char *)inet6;
    af->rta_len=(char *)h+h->nlmsg_len-(char *)af;
    assert(!ack(n,&root,h,NULL));
    assert(md_network_interface_find(n,1,NULL)->ipv6.addr_gen_mode==1);
    const unsigned queries[]={RTM_GETLINK,RTM_GETADDR,RTM_GETROUTE};
    const unsigned dump_flags[]={NLM_F_ROOT,NLM_F_MATCH,NLM_F_DUMP};
    for (unsigned q=0;q<3;++q) for (unsigned padding=1;padding<=4;padding*=4)
            for (unsigned flag=0;flag<3;++flag) {
        h=request(&p,queries[q],dump_flags[flag]);
        h->nlmsg_len=NLMSG_LENGTH(padding);
        length=md_network_route(n,&user,h,h->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),NULL);
        assert(length>0);
        done=0;
        unsigned addresses=0;
        for (struct nlmsghdr *item=(void *)reply.bytes;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
            assert(item->nlmsg_type!=NLMSG_ERROR && item->nlmsg_seq==71);
            if (item->nlmsg_type==NLMSG_DONE) ++done;
            if (item->nlmsg_type==RTM_NEWADDR) {
                const struct ifaddrmsg *address=NLMSG_DATA(item);
                int left=IFA_PAYLOAD(item),found=0;
                for (const struct rtattr *a=IFA_RTA(address);RTA_OK(a,left);a=RTA_NEXT(a,left))
                    if (a->rta_type==IFA_FLAGS) {
                        uint32_t value;
                        assert(RTA_PAYLOAD(a)==sizeof(value));
                        memcpy(&value,RTA_DATA(a),sizeof(value));
                        assert(value==IFA_F_PERMANENT && value==address->ifa_flags);
                        ++found;
                    }
                assert(!left && found==1);
                ++addresses;
            }
        }
        assert(!length && done==1);
        if (queries[q]==RTM_GETADDR) assert(addresses==5);
    }
    h=request(&p,RTM_NEWLINK,NLM_F_CREATE|NLM_F_EXCL);
    info=attr(h,IFLA_LINKINFO,NULL,0);
    attr(h,IFLA_INFO_KIND,"veth",5);
    data=attr(h,IFLA_INFO_DATA,NULL,0);
    peer=attr(h,VETH_INFO_PEER,&zero,sizeof(zero));
    struct rtattr *peer_info=attr(h,IFLA_LINKINFO,NULL,0);
    attr(h,IFLA_INFO_KIND,"veth",5);
    peer_info->rta_len=(char *)h+h->nlmsg_len-(char *)peer_info;
    attr(h,IFLA_IFNAME,"eth1",5);
    selection=attr(h,IFLA_NET_NS_FD,&fd,sizeof(fd));
    uint32_t peer_mtu=1400;
    const unsigned char peer_mac[6]={2,1,2,3,4,5};
    attr(h,IFLA_MTU,&peer_mtu,sizeof(peer_mtu));
    attr(h,IFLA_ADDRESS,peer_mac,sizeof(peer_mac));
    peer->rta_len=(char *)h+h->nlmsg_len-(char *)peer;
    data->rta_len=(char *)h+h->nlmsg_len-(char *)data;
    info->rta_len=(char *)h+h->nlmsg_len-(char *)info;
    attr(h,IFLA_MASTER,&bridge,sizeof(bridge));
    unsigned before=n->count,child_before=child->count;
    original_index=n->fabric->next_index;
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,&target,rejected.bytes,sizeof(rejected),&refusal)==-EAGAIN);
    assert(n->count==before && child->count==child_before && n->fabric->next_index==original_index);
    assert(!md_network_interface_find(child,0,"eth1"));
    selection->rta_type=IFLA_NET_NS_PID;
    pid=999; memcpy(RTA_DATA(selection),&pid,sizeof(pid));
    assert(ack(n,&root,h,&target)==-ESRCH && n->count==before && child->count==child_before);
    pid=42; memcpy(RTA_DATA(selection),&pid,sizeof(pid));
    assert(!ack(n,&root,h,&target));
    struct md_network_interface *generated=md_network_interface_find(n,0,"veth0");
    struct md_network_interface *direct=md_network_interface_find(child,0,"eth1");
    assert(generated && direct && generated->peer==direct->info.index && direct->peer==generated->info.index);
    assert(generated->master==bridge);
    assert(direct->info.mtu==peer_mtu && !memcmp(direct->info.address,peer_mac,sizeof(peer_mac)));
    unsigned generated_index=generated->info.index;
    assert(ack(n,&root,h,&target)==-EEXIST);
    assert(n->count==before+1 && child->count==child_before+1);
    h=request(&p,RTM_SETLINK,0);
    *(struct ifinfomsg *)NLMSG_DATA(h)=(struct ifinfomsg){.ifi_family=AF_BRIDGE,.ifi_index=generated_index};
    struct rtattr *prot=attr(h,IFLA_PROTINFO|NLA_F_NESTED,NULL,0);
    unsigned char enabled=1;
    struct rtattr *mode=attr(h,IFLA_BRPORT_MODE,&enabled,1);
    attr(h,IFLA_BRPORT_ISOLATED,&enabled,1);
    prot->rta_len=(char *)h+h->nlmsg_len-(char *)prot;
    assert(ack(n,&user,h,NULL)==-EPERM);
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,NULL,rejected.bytes,sizeof(rejected),&refusal)==-EAGAIN);
    assert(!md_network_interface_find(n,generated_index,NULL)->hairpin);
    assert(!md_network_interface_find(n,generated_index,NULL)->isolated);
    assert(!ack(n,&root,h,NULL));
    assert(md_network_interface_find(n,generated_index,NULL)->hairpin);
    assert(md_network_interface_find(n,generated_index,NULL)->isolated);
    h=request(&p,RTM_GETLINK,NLM_F_DUMP);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_family=AF_BRIDGE;
    length=md_network_route(n,&user,h,h->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0); links=done=0;
    for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
        if (item->nlmsg_type==NLMSG_DONE) { ++done; continue; }
        assert(item->nlmsg_type==RTM_NEWLINK);
        const struct ifinfomsg *m=NLMSG_DATA(item);
        assert(m->ifi_family==AF_BRIDGE && (unsigned)m->ifi_index==generated_index);
        int left=IFLA_PAYLOAD(item), found=0;
        for (const struct rtattr *a=IFLA_RTA(m);RTA_OK(a,left);a=RTA_NEXT(a,left))
            if ((a->rta_type&NLA_TYPE_MASK)==IFLA_PROTINFO) {
                const struct rtattr *mode=RTA_DATA(a);
                assert(mode->rta_type==IFLA_BRPORT_MODE && RTA_PAYLOAD(mode)==1);
                assert(*(const unsigned char *)RTA_DATA(mode)==1); ++found;
                int nested=RTA_PAYLOAD(a);
                mode=RTA_NEXT(mode,nested);
                assert(RTA_OK(mode,nested) && mode->rta_type==IFLA_BRPORT_ISOLATED && RTA_PAYLOAD(mode)==1);
                assert(*(const unsigned char *)RTA_DATA(mode)==1);
            }
        assert(found==1); ++links;
    }
    assert(!length && links==1 && done==1);
    h=request(&p,RTM_GETLINK,0);
    *(struct ifinfomsg *)NLMSG_DATA(h)=(struct ifinfomsg){.ifi_family=AF_BRIDGE,.ifi_index=1};
    assert(ack(n,&user,h,NULL)==-ENODEV);
    h=request(&p,RTM_SETLINK,0);
    *(struct ifinfomsg *)NLMSG_DATA(h)=(struct ifinfomsg){.ifi_family=AF_BRIDGE,.ifi_index=generated_index};
    prot=attr(h,IFLA_PROTINFO|NLA_F_NESTED,NULL,0);
    mode=attr(h,IFLA_BRPORT_MODE,&enabled,1);
    prot->rta_len=(char *)h+h->nlmsg_len-(char *)prot;
    *(unsigned char *)RTA_DATA(mode)=2;
    assert(ack(n,&root,h,NULL)==-EINVAL);
    assert(md_network_interface_find(n,generated_index,NULL)->hairpin);
    mode->rta_type=IFLA_BRPORT_ISOLATED;
    assert(ack(n,&root,h,NULL)==-EINVAL);
    assert(md_network_interface_find(n,generated_index,NULL)->isolated);
    direct=md_network_interface_find(child,0,"eth1"); assert(direct);
    test_network_v4(direct,htonl(0x0a590002),24); direct->info.flags|=IFF_UP;
    h=request(&p,RTM_NEWROUTE,NLM_F_CREATE|NLM_F_EXCL);
    h->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg));
    *(struct rtmsg *)NLMSG_DATA(h)=(struct rtmsg){.rtm_family=AF_INET,.rtm_table=RT_TABLE_MAIN,
        .rtm_type=RTN_UNICAST,.rtm_protocol=RTPROT_BOOT,.rtm_scope=RT_SCOPE_UNIVERSE};
    uint32_t gateway=htonl(0x0a590001),metric=100;
    attr(h,RTA_GATEWAY,&gateway,sizeof(gateway));
    struct rtattr *priority=attr(h,RTA_PRIORITY,&metric,sizeof(metric));
    assert(md_network_route(child,&root,h,h->nlmsg_len,91,0,NULL,rejected.bytes,sizeof(rejected),&refusal)==-EAGAIN);
    assert(!child->route_count);
    assert(!ack(child,&root,h,NULL) && test_network_default(child,AF_INET)->metric==100);
    struct packet dump_packet;
    struct nlmsghdr *dump_header=request(&dump_packet,RTM_GETROUTE,NLM_F_DUMP);
    dump_header->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg));
    ((struct rtmsg *)NLMSG_DATA(dump_header))->rtm_family=AF_INET;
    length=md_network_route(child,&root,dump_header,dump_header->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0);
    unsigned metrics=0;
    for (struct nlmsghdr *item=(void *)reply.bytes;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
        if (item->nlmsg_type!=RTM_NEWROUTE) continue;
        const struct rtmsg *body=NLMSG_DATA(item);
        int left=RTM_PAYLOAD(item);
        for (const struct rtattr *a=RTM_RTA(body);RTA_OK(a,left);a=RTA_NEXT(a,left))
            if (a->rta_type==RTA_PRIORITY) {
                assert(RTA_PAYLOAD(a)==4 && !memcmp(RTA_DATA(a),&metric,4)); ++metrics;
            }
    }
    assert(metrics==1);
    dump_header=request(&dump_packet,RTM_GETROUTE,NLM_F_DUMP);
    dump_header->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg));
    *(struct rtmsg *)NLMSG_DATA(dump_header)=(struct rtmsg){.rtm_family=AF_INET,
        .rtm_table=RT_TABLE_MAIN,.rtm_protocol=RTPROT_BOOT,.rtm_type=RTN_UNICAST};
    length=md_network_route(child,&root,dump_header,dump_header->nlmsg_len,91,0,
        NULL,reply.bytes,sizeof(reply),NULL);
    assert(length>0);
    unsigned legacy_routes=0;
    for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
        assert(item->nlmsg_type==RTM_NEWROUTE || item->nlmsg_type==NLMSG_DONE);
        legacy_routes+=item->nlmsg_type==RTM_NEWROUTE;
    }
    assert(!length && legacy_routes>=2);
    const unsigned protocols[]={RTPROT_UNSPEC,RTPROT_BOOT,RTPROT_KERNEL,RTPROT_RA,RTPROT_UNSPEC};
    const unsigned types[]={RTN_UNICAST,RTN_UNSPEC,RTN_UNICAST,RTN_UNICAST,RTN_LOCAL};
    const unsigned expected[]={2,1,1,0,0};
    for (unsigned family=0;family<2;++family) for (unsigned filter=0;filter<5;++filter) {
        struct rtmsg *selected=NLMSG_DATA(dump_header);
        selected->rtm_family=family ? AF_UNSPEC : AF_INET;
        selected->rtm_type=types[filter]; selected->rtm_protocol=protocols[filter];
        length=md_network_route(child,&root,dump_header,dump_header->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,
            NULL,reply.bytes,sizeof(reply),NULL);
        assert(length>0);
        unsigned routes=0;
        for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
            assert(item->nlmsg_type==RTM_NEWROUTE || item->nlmsg_type==NLMSG_DONE);
            if (item->nlmsg_type==RTM_NEWROUTE) {
                const struct rtmsg *route=NLMSG_DATA(item);
                assert(!selected->rtm_type || selected->rtm_type==route->rtm_type);
                assert(!selected->rtm_protocol || selected->rtm_protocol==route->rtm_protocol);
                ++routes;
            }
        }
        assert(!length && routes==expected[filter]);
    }
    for (unsigned selection=0;selection<6;++selection) {
        dump_header=request(&dump_packet,RTM_GETROUTE,NLM_F_DUMP);
        dump_header->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg));
        ((struct rtmsg *)NLMSG_DATA(dump_header))->rtm_family=AF_INET;
        uint32_t table=selection==1 ? 1234 : RT_TABLE_MAIN;
        struct rtattr *chosen=attr(dump_header,RTA_TABLE,&table,sizeof(table));
        if (selection==2) chosen->rta_len=RTA_LENGTH(1);
        if (selection==3) chosen->rta_type=RTA_OIF;
        if (selection==4) ((struct rtmsg *)NLMSG_DATA(dump_header))->rtm_tos=1;
        if (selection==5) ((struct rtmsg *)NLMSG_DATA(dump_header))->rtm_table=RT_TABLE_DEFAULT;
        length=md_network_route(child,&root,dump_header,dump_header->nlmsg_len,91,MD_NETWORK_ROUTE_STRICT,
            NULL,reply.bytes,sizeof(reply),NULL);
        assert(length>0);
        if (selection==2 || selection==4) {
            assert(reply.align.nlmsg_type==NLMSG_ERROR);
            assert(((struct nlmsgerr *)NLMSG_DATA(&reply.align))->error==(selection==2 ? -EINVAL : -EOPNOTSUPP));
        } else {
            unsigned routes=0;
            for (struct nlmsghdr *item=&reply.align;NLMSG_OK(item,length);item=NLMSG_NEXT(item,length)) {
                assert(item->nlmsg_type==RTM_NEWROUTE || item->nlmsg_type==NLMSG_DONE);
                routes+=item->nlmsg_type==RTM_NEWROUTE;
            }
            assert(selection==1 || selection==3 ? routes==0 : routes==2);
        }
    }
    uint32_t wrong_metric=99; memcpy(RTA_DATA(priority),&wrong_metric,4);
    assert(!ack(child,&root,h,NULL) && child->route_count==2);
    struct md_network_route_entry selected_route;
    assert(md_network_route_find(child,md_ip_v4(htonl(0x01010101)),&selected_route) && selected_route.metric==99);
    h->nlmsg_type=RTM_DELROUTE;
    assert(!ack(child,&root,h,NULL) && child->route_count==1);
    assert(ack(child,&root,h,NULL)==-ESRCH && test_network_default(child,AF_INET)->metric==100);
    memcpy(RTA_DATA(priority),&metric,4);
    assert(!ack(child,&root,h,NULL) && !child->route_count);
    h=request(&p,RTM_DELLINK,0);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index=generated_index;
    assert(!ack(n,&root,h,NULL));
    assert(n->count==before && child->count==child_before);
    h=request(&p,RTM_DELLINK,0);
    ((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index=bridge;
    assert(!ack(n,&root,h,NULL));
    assert(!md_sysfs_refresh(sysfs));
    assert(fstatat(md_sysfs_root(sysfs),"class/net/br-test",&bridge_node,0)==-1 && errno==ENOENT);
    md_sysfs_release(sysfs);
    md_network_namespace_release(child); md_network_namespace_release(n);
    md_identity_release(&root); md_identity_release(&user);
    puts("PASS route netlink: permissions, bridge/veth, namespace handles, atomic rejection and multipart dumps");
}

void test_network_route_lifetimes(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0);
    struct packet p,reply;
    unsigned index=md_network_interface_find(n,0,"eth0")->info.index;
    struct md_ip_address address,gateway,destination;
    assert(!md_network_profile_ip("fd92:10::1234",&address));
    assert(!md_network_profile_ip("fe80::1234",&gateway));
    assert(!md_network_profile_ip("fd93::",&destination));
    struct nlmsghdr *h=request(&p,RTM_NEWADDR,NLM_F_CREATE|NLM_F_EXCL);
    h->nlmsg_len=NLMSG_LENGTH(sizeof(struct ifaddrmsg));
    *(struct ifaddrmsg *)NLMSG_DATA(h)=(struct ifaddrmsg){.ifa_family=AF_INET6,.ifa_prefixlen=64,.ifa_index=index};
    attr(h,IFA_LOCAL,address.bytes,16);
    const unsigned char zero[16]={0}; attr(h,IFA_ADDRESS,zero,16);
    struct ifa_cacheinfo cache={.ifa_prefered=30,.ifa_valid=60};
    struct rtattr *lifetimes=attr(h,IFA_CACHEINFO,&cache,sizeof(cache));
    uint32_t flags=IFA_F_MANAGETEMPADDR|IFA_F_NOPREFIXROUTE,metric=512;
    attr(h,IFA_FLAGS,&flags,4); attr(h,IFA_RT_PRIORITY,&metric,4);
    cache.ifa_prefered=61; memcpy(RTA_DATA(lifetimes),&cache,sizeof(cache));
    assert(ack(n,&root,h,NULL)==-EINVAL);
    cache.ifa_prefered=30; memcpy(RTA_DATA(lifetimes),&cache,sizeof(cache));
    struct md_network_publication refusal={.publish=reject_response};
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),&refusal)==-EAGAIN);
    assert(!md_network_interface_has(md_network_interface_find(n,index,NULL),address));
    assert(!n->configuration_deadline);
    assert(!ack(n,&root,h,NULL));

    uint32_t id=123;
    h=request(&p,RTM_NEWNEXTHOP,NLM_F_CREATE|NLM_F_EXCL);
    h->nlmsg_len=NLMSG_LENGTH(sizeof(struct nhmsg));
    *(struct nhmsg *)NLMSG_DATA(h)=(struct nhmsg){.nh_family=AF_INET6,.nh_protocol=RTPROT_RA};
    attr(h,NHA_ID,&id,4); attr(h,NHA_OIF,&index,4); attr(h,NHA_GATEWAY,gateway.bytes,16);
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),&refusal)==-EAGAIN);
    assert(!n->nexthop_count);
    assert(!ack(n,&root,h,NULL) && n->nexthop_count==1);
    assert(ack(n,&root,h,NULL)==-EEXIST);
    h=request(&p,RTM_NEWROUTE,NLM_F_CREATE|NLM_F_EXCL);
    h->nlmsg_len=NLMSG_LENGTH(sizeof(struct rtmsg));
    *(struct rtmsg *)NLMSG_DATA(h)=(struct rtmsg){.rtm_family=AF_INET6,.rtm_dst_len=64,
        .rtm_table=RT_TABLE_MAIN,.rtm_type=RTN_UNICAST,.rtm_protocol=RTPROT_RA};
    uint32_t seconds=45;
    attr(h,RTA_DST,destination.bytes,16); attr(h,RTA_NH_ID,&id,4); attr(h,RTA_EXPIRES,&seconds,4);
    assert(!ack(n,&root,h,NULL));
    pthread_mutex_lock(&n->fabric->lock);
    struct md_network_interface *link=md_network_interface_find(n,index,NULL);
    struct md_network_ip_assignment *a=NULL;
    for (unsigned i=0;i<link->address_count;++i) if (md_ip_equal(link->addresses[i].address,address)) a=&link->addresses[i];
    assert(a && a->route_metric==512 && !(a->flags&IFA_F_PERMANENT));
    int64_t preferred=a->preferred_until,valid=a->valid_until;
    assert(n->configuration_deadline==preferred && md_network_lifetime_remaining(valid,preferred)==30);
    md_network_route_expire(n->fabric,preferred);
    assert(a->flags&IFA_F_DEPRECATED);
    struct md_network_route_entry route;
    assert(md_network_route_find(n,destination,&route) && route.nexthop==id);
    md_network_route_expire(n->fabric,route.valid_until);
    assert(md_network_route_find(n,destination,&route) && !route.nexthop);
    md_network_route_expire(n->fabric,valid);
    assert(!md_network_interface_has(link,address) && !n->configuration_deadline);
    pthread_mutex_unlock(&n->fabric->lock);
    h=request(&p,RTM_DELNEXTHOP,0); h->nlmsg_len=NLMSG_LENGTH(sizeof(struct nhmsg));
    attr(h,NHA_ID,&id,4); assert(!ack(n,&root,h,NULL) && !n->nexthop_count);
    assert(ack(n,&root,h,NULL)==-ENOENT);
    int queue[2]; assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,queue));
    struct md_network_listener observer={.peer=queue[0],.protocol=NETLINK_ROUTE,.groups=RTMGRP_IPV6_IFADDR};
    n->listeners=&observer;
    h=request(&p,RTM_NEWADDR,NLM_F_CREATE|NLM_F_EXCL); h->nlmsg_len=NLMSG_LENGTH(sizeof(struct ifaddrmsg));
    *(struct ifaddrmsg *)NLMSG_DATA(h)=(struct ifaddrmsg){.ifa_family=AF_INET6,.ifa_prefixlen=64,.ifa_index=index};
    attr(h,IFA_ADDRESS,address.bytes,16);
    struct md_network_publication lost_ack={.context=&queue[1],.publish=reject_after_notification};
    assert(md_network_route(n,&root,h,h->nlmsg_len,91,0,NULL,reply.bytes,sizeof(reply),&lost_ack)==-EAGAIN);
    assert(md_network_interface_has(md_network_interface_find(n,index,NULL),address));
    observer.groups=RTMGRP_IPV6_ROUTE|RTMGRP_IPV6_IFADDR;
    h->nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK|NLM_F_REPLACE;
    ((struct ifaddrmsg *)NLMSG_DATA(h))->ifa_prefixlen=80;
    assert(!ack(n,&root,h,NULL));
    unsigned added=0,removed=0,addresses=0;
    unsigned char event[2048]; ssize_t bytes;
    while ((bytes=recv(queue[1],event,sizeof(event),MSG_DONTWAIT))>0) {
        assert(bytes>(ssize_t)(sizeof(struct md_network_packet)+sizeof(struct nlmsghdr)));
        struct nlmsghdr header; memcpy(&header,event+sizeof(struct md_network_packet),sizeof(header));
        added+=header.nlmsg_type==RTM_NEWROUTE; removed+=header.nlmsg_type==RTM_DELROUTE;
        addresses+=header.nlmsg_type==RTM_NEWADDR;
        assert(header.nlmsg_type!=RTM_DELADDR);
    }
    assert(errno==EAGAIN && added==1 && removed==1 && addresses==1);
    n->listeners=NULL; close(queue[0]); close(queue[1]);
    md_identity_release(&root); md_network_namespace_release(n);
    puts("PASS route configuration: nexthops, rollback, lifetimes, expiry, notification-before-ACK and lost ACK custody");
}
