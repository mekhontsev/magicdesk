#define _GNU_SOURCE
#include "network_tc.h"
#include "network_route.h"
#include "network_internal.h"
#include "network_ethernet.h"
#include "network_packet.h"
#include "guest_identity.h"
#include <assert.h>
#include <arpa/inet.h>
#include <errno.h>
#include <linux/if_ether.h>
#include <linux/pkt_cls.h>
#include <linux/pkt_sched.h>
#include <linux/rtnetlink.h>
#include <linux/tc_act/tc_csum.h>
#include <net/if.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

union packet { struct nlmsghdr header; unsigned char bytes[2048]; };
static struct md_network_message request(union packet *p,unsigned type,unsigned flags,unsigned index,unsigned handle,unsigned parent,unsigned info) {
    memset(p,0,sizeof(*p));
    struct md_network_message out={.data=p->bytes,.capacity=sizeof(*p)};
    struct nlmsghdr h={.nlmsg_seq=17};
    struct tcmsg m={.tcm_ifindex=(int)index,.tcm_handle=handle,.tcm_parent=parent,.tcm_info=info};
    assert(!md_network_message_begin(&out,&h,31,type,0,&m,sizeof(m)));
    out.message->nlmsg_flags=NLM_F_REQUEST|NLM_F_ACK|flags;
    return out;
}
static void attr(struct md_network_message *m,unsigned type,const void *data,size_t size) {
    assert(!md_network_message_attribute(m,type,data,size));
}
static size_t nest(struct md_network_message *m,unsigned type) { size_t offset; assert(!md_network_message_nest(m,type,&offset)); return offset; }
static void end(struct md_network_message *m,size_t at) { assert(!md_network_message_end_nest(m,at)); }
static void qdisc(struct md_network_message *m,unsigned defcls) {
    attr(m,TCA_KIND,"htb",4); size_t opt=nest(m,TCA_OPTIONS);
    struct tc_htb_glob htb={.version=TC_HTB_PROTOVER,.rate2quantum=10,.defcls=defcls};
    attr(m,TCA_HTB_INIT,&htb,sizeof(htb)); end(m,opt);
}
static void filter(struct md_network_message *m,int offset,unsigned flags) {
    attr(m,TCA_KIND,"u32",4); size_t opt=nest(m,TCA_OPTIONS);
    unsigned char buffer[sizeof(struct tc_u32_sel)+sizeof(struct tc_u32_key)]={0};
    struct tc_u32_sel s={.flags=TC_U32_TERMINAL,.nkeys=1};
    struct tc_u32_key key={.mask=htonl(0xffff),.val=htonl(68),.off=offset};
    memcpy(buffer,&s,sizeof(s)); memcpy(buffer+sizeof(s),&key,sizeof(key));
    attr(m,TCA_U32_SEL,buffer,sizeof(buffer));
    size_t actions=nest(m,TCA_U32_ACT),action=nest(m,1);
    attr(m,TCA_ACT_KIND,"csum",5); size_t opts=nest(m,TCA_ACT_OPTIONS);
    struct tc_csum c={.action=TC_ACT_PIPE,.update_flags=flags};
    attr(m,TCA_CSUM_PARMS,&c,sizeof(c)); end(m,opts); end(m,action); end(m,actions); end(m,opt);
}
static long call(struct md_network_namespace *n,const struct md_identity *id,union packet *p,union packet *out,
        size_t capacity,const struct md_network_publication *publication) {
    return md_network_route(n,id,&p->header,p->header.nlmsg_len,31,MD_NETWORK_ROUTE_STRICT|MD_NETWORK_ROUTE_CAPPED_ACK,
        NULL,out->bytes,capacity,publication);
}
static int ack(struct md_network_namespace *n,const struct md_identity *id,union packet *p) {
    union packet out;
    long r=call(n,id,p,&out,sizeof(out),NULL);
    assert(r>=(long)NLMSG_LENGTH(sizeof(struct nlmsgerr)) && out.header.nlmsg_type==NLMSG_ERROR);
    assert(out.header.nlmsg_seq==17 && out.header.nlmsg_pid==31);
    return ((struct nlmsgerr *)NLMSG_DATA(&out.header))->error;
}
static unsigned dump(struct md_network_namespace *n,const struct md_identity *id,unsigned type,unsigned index) {
    union packet p,out; request(&p,type,NLM_F_DUMP,index,0,0,0);
    long r=call(n,id,&p,&out,sizeof(out),NULL); assert(r>0);
    unsigned size=(unsigned)r,count=0;
    for (struct nlmsghdr *h=&out.header;NLMSG_OK(h,size);h=NLMSG_NEXT(h,size)) {
        assert(h->nlmsg_type==type-2 || h->nlmsg_type==NLMSG_DONE);
        if (h->nlmsg_type==type-2) ++count;
    }
    assert(!size); return count;
}
static int refuse(void *context,const void *data,size_t size) { (void)context; (void)data; assert(size); return -EAGAIN; }
static void put16(unsigned char *p,unsigned v) { p[0]=v>>8; p[1]=v; }
static unsigned checksum(const unsigned char *data,size_t size,unsigned seed) {
    for (size_t i=0;i<size;++i) seed+=(unsigned)data[i]<<((i&1) ? 0 : 8);
    while (seed>>16) seed=(seed&65535)+(seed>>16);
    return (~seed)&65535;
}
static void packet(unsigned char *frame,unsigned ihl) {
    memset(frame,0,80); memset(frame,0xff,6); put16(frame+12,ETH_P_IP);
    unsigned char *ip=frame+14,*udp=ip+ihl;
    ip[0]=0x40|ihl/4; put16(ip+2,ihl+13); ip[8]=32; ip[9]=IPPROTO_UDP; put16(ip+10,123);
    ip[12]=192; ip[14]=2; ip[15]=1; ip[16]=192; ip[18]=2; ip[19]=2;
    put16(udp,67); put16(udp+2,68); put16(udp+4,13); put16(udp+6,123);
    memcpy(udp+8,"hello",5);
}
static void check_packet(const void *frame,unsigned ihl) {
    const unsigned char *ip=(const unsigned char *)frame+14;
    assert(!checksum(ip,ihl,0));
    unsigned seed=0;
    for (unsigned i=12;i<20;i+=2) seed+=(ip[i]<<8)|ip[i+1];
    assert(!checksum(ip+ihl,13,seed+IPPROTO_UDP+13));
}
static void packets(struct md_network_namespace *n,const struct md_identity *root,unsigned index) {
    union packet p; struct md_network_message m;
    const unsigned flags=TCA_CSUM_UPDATE_FLAG_IPV4HDR|TCA_CSUM_UPDATE_FLAG_UDP;
    for (unsigned ihl=20;ihl<=24;ihl+=4) {
        m=request(&p,RTM_NEWTFILTER,NLM_F_CREATE|NLM_F_REPLACE,index,0x80000800,0x10000,(2<<16)|htons(ETH_P_IP));
        filter(&m,(int)ihl,flags); assert(!ack(n,root,&p));
        struct md_network_interface *link=md_network_interface_find(n,index,NULL);
        unsigned char frame[80],before[80]; packet(frame,ihl); memcpy(before,frame,80);
        void *copy=NULL;
        assert(!md_network_tc_egress(link->tc,frame,14+ihl+13,&copy) && copy);
        check_packet(copy,ihl); assert(!memcmp(frame,before,80)); free(copy);
        put16(frame+14+ihl+2,69);
        assert(!md_network_tc_egress(link->tc,frame,14+ihl+13,&copy) && !copy);
        put16(frame+14+ihl+2,68); put16(frame+14+ihl+6,0);
        assert(!md_network_tc_egress(link->tc,frame,14+ihl+13,&copy) && copy);
        assert(!memcmp((char *)copy+14+ihl+6,"\0\0",2)); free(copy);
        packet(frame,ihl); put16(frame+20,1); /* Noninitial fragment: header only. */
        assert(!md_network_tc_egress(link->tc,frame,14+ihl+13,&copy) && copy);
        assert(!memcmp((char *)copy+14+ihl+6,frame+14+ihl+6,2)); free(copy);
        packet(frame,ihl);
        assert(md_network_tc_egress(link->tc,frame,14+ihl+12,&copy)==1 && !copy);
    }
    m=request(&p,RTM_NEWTFILTER,NLM_F_REPLACE,index,0x80000800,0x10000,(2<<16)|htons(ETH_P_IP));
    filter(&m,20,flags); assert(!ack(n,root,&p));
}
static void branch_delivery(struct md_network_namespace *n,const struct md_identity *root,unsigned bridge) {
    unsigned ports[2];
    struct md_network_ethernet receivers[2]; int sockets[2][2];
    for (unsigned i=0;i<2;++i) {
        assert(!md_network_interface_add(n,MD_LINK_TAP,i ? "other" : "repaired",&ports[i]));
        struct md_network_interface *link=md_network_interface_find(n,ports[i],NULL);
        link->master=bridge; link->info.flags|=IFF_UP;
        assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_NONBLOCK,0,sockets[i]));
        md_network_ethernet_attach(&receivers[i],n,sockets[i][1],SOCK_RAW,htons(ETH_P_ALL));
        receivers[i].index=ports[i]; receivers[i].policy=link->policy_id;
    }
    union packet p; struct md_network_message m=request(&p,RTM_NEWQDISC,NLM_F_CREATE,ports[0],0x10000,TC_H_ROOT,0);
    qdisc(&m,2); assert(!ack(n,root,&p));
    m=request(&p,RTM_NEWTFILTER,NLM_F_CREATE,ports[0],0x80000800,0x10000,(2<<16)|htons(ETH_P_IP));
    filter(&m,20,TCA_CSUM_UPDATE_FLAG_IPV4HDR|TCA_CSUM_UPDATE_FLAG_UDP); assert(!ack(n,root,&p));
    /* Remove the bridge filter: only one of its outgoing branches is repaired. */
    m=request(&p,RTM_DELQDISC,0,bridge,0x10000,TC_H_ROOT,0); assert(!ack(n,root,&p));
    unsigned char frame[80],received[2048]; packet(frame,20);
    struct md_network_interface *link=md_network_interface_find(n,bridge,NULL); link->info.flags|=IFF_UP;
    assert(!md_network_ethernet_transmit(n,link,NULL,frame,47));
    for (unsigned i=0;i<2;++i) {
        assert(recv(sockets[i][0],received,sizeof(received),0)==(ssize_t)(sizeof(struct md_network_packet)+47));
        if (!i) check_packet(received+sizeof(struct md_network_packet),20);
        else assert(!memcmp(received+sizeof(struct md_network_packet),frame,47));
        md_network_ethernet_detach(&receivers[i]); close(sockets[i][0]); close(sockets[i][1]);
        assert(!md_network_interface_remove(n,ports[i]));
    }
}
void test_network_tc(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    unsigned index; assert(n && !md_network_interface_add(n,MD_LINK_BRIDGE,"tc-test",&index));
    union packet p,out;
    struct md_network_message m=request(&p,RTM_NEWQDISC,NLM_F_CREATE|NLM_F_EXCL,index,0x10000,TC_H_ROOT,0);
    qdisc(&m,2);
    assert(ack(n,&user,&p)==-EPERM);
    struct md_network_publication publication={.publish=refuse};
    assert(call(n,&root,&p,&out,sizeof(out),&publication)==-EAGAIN);
    assert(!md_network_interface_find(n,index,NULL)->tc);
    assert(call(n,&root,&p,&out,1,NULL)==-EMSGSIZE);
    assert(!md_network_interface_find(n,index,NULL)->tc);
    assert(!ack(n,&root,&p)); assert(ack(n,&root,&p)==-EEXIST);
    assert(dump(n,&root,RTM_GETQDISC,index)==1 && !dump(n,&root,RTM_GETTCLASS,index));
    m=request(&p,RTM_NEWTCLASS,NLM_F_CREATE,index,0x10001,0x10000,0); qdisc(&m,2);
    assert(ack(n,&root,&p)==-EOPNOTSUPP);
    m=request(&p,RTM_NEWTFILTER,NLM_F_CREATE,index,0,0x10000,(2<<16)|htons(ETH_P_IP));
    filter(&m,20,TCA_CSUM_UPDATE_FLAG_IPV4HDR|TCA_CSUM_UPDATE_FLAG_UDP);
    int queue[2]; assert(!socketpair(AF_UNIX,SOCK_DGRAM|SOCK_CLOEXEC,0,queue));
    struct md_network_listener observer={.peer=queue[0],.protocol=NETLINK_ROUTE,.groups=RTMGRP_TC};
    n->listeners=&observer;
    assert(call(n,&root,&p,&out,sizeof(out),&publication)==-EAGAIN);
    unsigned char event[2048];
    ssize_t bytes=recv(queue[1],event,sizeof(event),MSG_DONTWAIT);
    assert(bytes>(ssize_t)(sizeof(struct md_network_packet)+sizeof(struct nlmsghdr)));
    struct md_network_packet envelope; memcpy(&envelope,event,sizeof(envelope));
    struct nlmsghdr header; memcpy(&header,event+sizeof(envelope),sizeof(header));
    assert(envelope.group==RTNLGRP_TC && header.nlmsg_type==RTM_NEWTFILTER && !header.nlmsg_seq);
    n->listeners=NULL; close(queue[0]); close(queue[1]);
    assert(dump(n,&root,RTM_GETTFILTER,index)==1);
    struct md_network_tc *saved=md_network_interface_find(n,index,NULL)->tc;
    m=request(&p,RTM_NEWTFILTER,NLM_F_REPLACE,index,0x80000800,0x10000,(2<<16)|htons(ETH_P_IP));
    filter(&m,20,TCA_CSUM_UPDATE_FLAG_TCP);
    assert(ack(n,&root,&p)==-EOPNOTSUPP);
    assert(md_network_interface_find(n,index,NULL)->tc==saved);
    packets(n,&root,index);
    m=request(&p,RTM_DELTFILTER,0,index,0,0x10000,(2<<16)|htons(ETH_P_IP));
    assert(call(n,&root,&p,&out,sizeof(out),&publication)==-EAGAIN);
    assert(dump(n,&root,RTM_GETTFILTER,index)==1);
    assert(!ack(n,&root,&p)); assert(!dump(n,&root,RTM_GETTFILTER,index));
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL); assert(child);
    assert(!dump(child,&root,RTM_GETQDISC,0)); md_network_namespace_release(child);
    branch_delivery(n,&root,index);
    /* Device moves discard qdisc authority; the topology journal retains it
     * independently until its own rollback/commit completes. */
    unsigned veth; assert(!md_network_interface_add(n,MD_LINK_VETH,"movable",&veth));
    m=request(&p,RTM_NEWQDISC,NLM_F_CREATE,veth,0x10000,TC_H_ROOT,0); qdisc(&m,2);
    assert(!ack(n,&root,&p));
    child=md_network_namespace_copy(n,NULL); assert(child);
    assert(!md_network_interface_move(n,veth,child,"moved"));
    assert(!md_network_interface_find(child,veth,NULL)->tc);
    md_network_namespace_release(child);
    /* A direct queue of length zero really drops, without pretending to shape. */
    md_network_interface_find(n,index,NULL)->info.tx_queue_length=0;
    m=request(&p,RTM_NEWQDISC,NLM_F_CREATE,index,0x10000,TC_H_ROOT,0); qdisc(&m,2);
    assert(!ack(n,&root,&p));
    unsigned char frame[80]; packet(frame,20); void *copy=NULL;
    assert(md_network_tc_egress(md_network_interface_find(n,index,NULL)->tc,frame,47,&copy)==1 && !copy);
    assert(!md_network_interface_remove(n,index)); md_network_namespace_release(n);
    puts("PASS tc: transactional qdisc/filter ownership, notifications, namespace cleanup, IPv4/UDP repair and branch-local copies");
}
