#define _GNU_SOURCE
#include "network_nft.h"
#include "network_filter.h"
#include "network_nft_expression.h"
#include "network_nft_objects.h"
#include "network_flow.h"
#include "network_internal.h"
#include "network_profile.h"
#include "guest_identity.h"
#include <assert.h>
#include <errno.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nf_tables.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nf_tables_compat.h>
#include <linux/netfilter/nf_nat.h>
#include <linux/netfilter/xt_tcpudp.h>
#include <linux/netfilter/xt_comment.h>
#include <linux/netfilter/xt_conntrack.h>
#include <linux/netfilter/xt_mark.h>
#include <linux/netfilter/xt_connmark.h>
#include <linux/netfilter/xt_addrtype.h>
#include <linux/netfilter/xt_pkttype.h>
#include <linux/netfilter/xt_rpfilter.h>
#include <linux/netfilter_ipv4/ipt_REJECT.h>
#include <linux/netfilter_ipv6/ip6t_REJECT.h>
#include <linux/if_packet.h>
#include <linux/rtnetlink.h>
#include <linux/fib_rules.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

struct packet { _Alignas(struct nlmsghdr) unsigned char data[8192]; };
static struct nlmsghdr *add(struct md_network_message *o,unsigned type,unsigned flags,unsigned family) {
    struct nlmsghdr request={.nlmsg_seq=(unsigned)o->size+1};
    struct nfgenmsg nf={.nfgen_family=family,.version=NFNETLINK_V0};
    if (type==NFNL_MSG_BATCH_BEGIN) nf.res_id=htons(NFNL_SUBSYS_NFTABLES);
    assert(!md_network_message_begin(o,&request,0,type,0,&nf,sizeof(nf)));
    o->message->nlmsg_flags=NLM_F_REQUEST|flags;
    return o->message;
}
static void begin(struct md_network_message *o,uint32_t generation) {
    add(o,NFNL_MSG_BATCH_BEGIN,0,AF_UNSPEC);
    if (generation) {
        generation=htonl(generation);
        assert(!md_network_message_attribute(o,NFNL_BATCH_GENID,&generation,sizeof(generation)));
    }
}
static void end(struct md_network_message *o) { add(o,NFNL_MSG_BATCH_END,0,AF_UNSPEC); }
static struct nlmsghdr *table(struct md_network_message *o,unsigned type,unsigned flags,unsigned family,const char *name) {
    struct nlmsghdr *h=add(o,(NFNL_SUBSYS_NFTABLES<<8)|type,NLM_F_ACK|flags,family);
    if (name) assert(!md_network_message_attribute(o,NFTA_TABLE_NAME,name,strlen(name)+1));
    return h;
}
static long run(struct md_network_namespace *n,const struct md_identity *identity,const struct md_network_message *o,
        struct packet *reply,const struct md_network_publication *publication) {
    memset(reply,0,sizeof(*reply));
    return md_network_nft(n,identity,o->data,o->size,43,0,reply->data,sizeof(reply->data),publication);
}
static int result(struct md_network_namespace *n,const struct md_identity *identity,const struct md_network_message *o) {
    struct packet reply;
    long length=run(n,identity,o,&reply,NULL);
    assert(length>0);
    for (struct nlmsghdr *h=(void *)reply.data;NLMSG_OK(h,length);h=NLMSG_NEXT(h,length)) {
        assert(h->nlmsg_type==NLMSG_ERROR && h->nlmsg_pid==43);
        int error=((struct nlmsgerr *)NLMSG_DATA(h))->error;
        if (error) return error;
    }
    assert(!length);
    return 0;
}
static int fail_publish(void *context,const void *data,size_t size) {
    (void)context; (void)data; assert(size); return -EAGAIN;
}
static void attribute_u32(struct md_network_message *o,unsigned type,uint32_t value) {
    value=htonl(value);
    assert(!md_network_message_attribute(o,type,&value,sizeof(value)));
}
static void data_maps(void) {
    for (unsigned ipv6=0;ipv6<2;++ipv6) {
        unsigned family=ipv6 ? NFPROTO_IPV6 : NFPROTO_IPV4;
        unsigned bytes=ipv6 ? 16 : 4, data_size=bytes+4;
        struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
        struct md_identity root=md_identity_new(0,0);
        struct packet wire,reply;
        struct md_network_message o={.data=wire.data,.capacity=sizeof(wire.data)};
        begin(&o,1); table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,family,"maps");
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWSET,NLM_F_CREATE|NLM_F_ACK,family);
        assert(!md_network_message_attribute(&o,NFTA_SET_TABLE,"maps",5));
        assert(!md_network_message_attribute(&o,NFTA_SET_NAME,"destinations",13));
        attribute_u32(&o,NFTA_SET_FLAGS,NFT_SET_MAP);
        attribute_u32(&o,NFTA_SET_KEY_TYPE,(12<<6)|13);
        attribute_u32(&o,NFTA_SET_KEY_LEN,8);
        attribute_u32(&o,NFTA_SET_DATA_TYPE,((ipv6 ? 8 : 7)<<6)|13);
        attribute_u32(&o,NFTA_SET_DATA_LEN,data_size);
        end(&o); assert(!result(n,&root,&o));
        struct md_filter_set *s=md_filter_set_find(n->filter,1,"destinations",0,0);
        assert(s && s->key_size==8 && s->data_size==data_size);
        unsigned char key[8]={IPPROTO_TCP},value[20]={0};
        uint16_t port=htons(42345),target_port=htons(8080);
        struct md_ip_address destination=ipv6 ? md_ip_loopback(AF_INET6) : md_ip_v4(htonl(0x0a000202));
        memcpy(key+4,&port,2); memcpy(value,destination.bytes,bytes); memcpy(value+bytes,&target_port,2);
        o.size=0; begin(&o,2);
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWSETELEM,NLM_F_CREATE|NLM_F_ACK,family);
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_TABLE,"maps",5));
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_SET,"destinations",13));
        size_t list,item,data;
        assert(!md_network_message_nest(&o,NFTA_SET_ELEM_LIST_ELEMENTS,&list));
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&item));
        assert(!md_network_message_nest(&o,NFTA_SET_ELEM_KEY,&data));
        assert(!md_network_message_attribute(&o,NFTA_DATA_VALUE,key,sizeof(key)));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_nest(&o,NFTA_SET_ELEM_DATA,&data));
        assert(!md_network_message_attribute(&o,NFTA_DATA_VALUE,value,data_size));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,item));
        assert(!md_network_message_end_nest(&o,list));
        end(&o); assert(!result(n,&root,&o));
        s=md_filter_set_find(n->filter,1,"destinations",0,0);
        assert(s->count==1 && !memcmp(s->elements[0].data,value,data_size));
        struct md_network_filter *copy=md_network_filter_copy(n->filter);
        assert(copy && copy->sets[0].elements!=s->elements && !memcmp(copy->sets[0].elements[0].data,value,data_size));
        md_network_filter_free(copy);

        /* A data tuple may contain the bit pattern of a chain handle. Only
         * verdict maps retain chains; data must not create a phantom edge. */
        if (ipv6) {
            o.size=0;
            add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWCHAIN,NLM_F_CREATE,family);
            assert(!md_network_message_attribute(&o,NFTA_CHAIN_TABLE,"maps",5));
            assert(!md_network_message_attribute(&o,NFTA_CHAIN_NAME,"unused",7));
            struct md_network_message ignored={0};
            assert(!md_nft_objects(n->filter,o.message,43,&ignored));
            struct md_filter_chain *unused=md_filter_chain_find(n->filter,1,"unused",0,0);
            assert(unused);
            memcpy(s->elements[0].data+offsetof(struct md_filter_verdict,target),&unused->handle,sizeof(unused->handle));
            o.message->nlmsg_type=(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_DELCHAIN;
            o.message->nlmsg_flags=NLM_F_REQUEST;
            assert(!md_nft_objects(n->filter,o.message,43,&ignored) && !n->filter->chain_count);
            free(n->filter->chains); n->filter->chains=NULL; n->filter->chain_capacity=0;
            memcpy(s->elements[0].data,value,data_size);
        }

        /* Reimport the emitted wire representation, not the in-memory copy. */
        struct md_network_message out={.data=reply.data,.capacity=sizeof(reply.data)};
        o.size=0;
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_GETSETELEM,NLM_F_DUMP,family);
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_TABLE,"maps",5));
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_SET,"destinations",13));
        assert(!md_nft_objects(n->filter,o.message,43,&out));
        struct nlmsghdr *dump=(void *)out.data;
        assert(NFNL_MSG_TYPE(dump->nlmsg_type)==NFT_MSG_NEWSETELEM);
        dump->nlmsg_flags=NLM_F_CREATE;
        s->count=0; o.size=0;
        assert(!md_nft_objects(n->filter,dump,43,&o));
        assert(s->count==1 && !memcmp(s->elements[0].key,key,sizeof(key))
            && !memcmp(s->elements[0].data,value,data_size));

        /* systemd echoes the map value when deleting. Linux selects by key,
         * even when that value has already changed since the client's dump. */
        memset(s->elements[0].data,0,data_size);
        dump->nlmsg_type=(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_DELSETELEM;
        dump->nlmsg_flags=NLM_F_REQUEST;
        assert(!md_nft_objects(n->filter,dump,43,&o) && !s->count);
        assert(md_nft_objects(n->filter,dump,43,&o)==-ENOENT);
        dump->nlmsg_type=(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWSETELEM;
        dump->nlmsg_flags=NLM_F_CREATE;
        assert(!md_nft_objects(n->filter,dump,43,&o) && s->count==1);

        /* Decode an overlapping register lookup, including an IPv6 tuple wider
         * than one legacy 16-byte register. The source must be initialized first. */
        o.size=0; add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,family);
        size_t start=o.size;
        const char *names[]={"meta","payload","lookup","nat"};
        for (unsigned i=0;i<4;++i) {
            assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&item));
            assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,names[i],strlen(names[i])+1));
            assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
            if (i==0) {
                attribute_u32(&o,NFTA_META_KEY,NFT_META_L4PROTO);
                attribute_u32(&o,NFTA_META_DREG,NFT_REG32_01);
            } else if (i==1) {
                attribute_u32(&o,NFTA_PAYLOAD_BASE,NFT_PAYLOAD_TRANSPORT_HEADER);
                attribute_u32(&o,NFTA_PAYLOAD_OFFSET,2); attribute_u32(&o,NFTA_PAYLOAD_LEN,2);
                attribute_u32(&o,NFTA_PAYLOAD_DREG,NFT_REG32_02);
            } else if (i==2) {
                assert(!md_network_message_attribute(&o,NFTA_LOOKUP_SET,"destinations",13));
                attribute_u32(&o,NFTA_LOOKUP_SREG,NFT_REG32_01);
                attribute_u32(&o,NFTA_LOOKUP_DREG,NFT_REG32_01);
            } else {
                attribute_u32(&o,NFTA_NAT_TYPE,NFT_NAT_DNAT); attribute_u32(&o,NFTA_NAT_FAMILY,family);
                attribute_u32(&o,NFTA_NAT_REG_ADDR_MIN,NFT_REG32_01);
                attribute_u32(&o,NFTA_NAT_REG_PROTO_MIN,NFT_REG32_01+bytes/4);
            }
            assert(!md_network_message_end_nest(&o,data));
            assert(!md_network_message_end_nest(&o,item));
        }
        struct md_filter_rule rule={.chain=3};
        assert(!md_nft_expressions(n->filter,1,&rule,o.data+start,o.size-start));
        assert(rule.count==4 && rule.expressions[2].code==MD_FILTER_DATA_MAP);
        struct md_filter_chain chain={.handle=3,.table=1,.hook=NF_INET_PRE_ROUTING,.nat=1,.policy=NF_ACCEPT};
        n->filter->chains=&chain; n->filter->chain_count=1;
        n->filter->rules=&rule; n->filter->rule_count=1;
        assert(!md_filter_validate(n->filter));
        struct md_filter_packet packet={.destination=md_ip_loopback(destination.family),.destination_port=port,
            .source=md_ip_loopback(destination.family),.protocol=IPPROTO_TCP,.state=8};
        assert(!md_filter_evaluate(n->filter,NF_INET_PRE_ROUTING,&packet));
        assert(packet.dnat && md_ip_equal(packet.destination,destination) && packet.destination_port==target_port);
        packet.dnat=0; packet.destination_port=htons(42346);
        assert(!md_filter_evaluate(n->filter,NF_INET_PRE_ROUTING,&packet) && !packet.dnat);
        struct md_filter_port ports[8]; size_t count=0;
        assert(!md_filter_ports(n->filter,md_ip_any(destination.family),ports,8,&count));
        assert(count==1 && ports[0].port==port && ports[0].protocol==IPPROTO_TCP);
        size_t saved=s->count; s->count=0;
        assert(!md_filter_ports(n->filter,md_ip_any(destination.family),ports,8,&count) && !count);
        s->count=saved;
        o.size=0; begin(&o,3);
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_DELSET,NLM_F_ACK,family);
        assert(!md_network_message_attribute(&o,NFTA_SET_TABLE,"maps",5));
        assert(!md_network_message_attribute(&o,NFTA_SET_NAME,"destinations",13));
        end(&o); assert(result(n,&root,&o)==-EBUSY);
        n->filter->chains=NULL; n->filter->chain_count=0;
        n->filter->rules=NULL; n->filter->rule_count=0; free(rule.wire);
        md_identity_release(&root); md_network_namespace_release(n);
    }
    puts("PASS data maps: IPv4/IPv6 tuples, overlapping registers, misses, finite listener publication, snapshot and busy deletion");
}
static void optional_expression_data(void) {
    const char *names[]={"masq","counter","payload","meta","immediate"};
    for (unsigned i=0;i<sizeof(names)/sizeof(*names);++i) for (unsigned present=0;present<2;++present) {
        struct packet wire;
        struct md_network_message o={.data=wire.data,.capacity=sizeof(wire.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=o.size,item,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&item));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,names[i],strlen(names[i])+1));
        if (present) {
            assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
            assert(!md_network_message_end_nest(&o,data));
        }
        assert(!md_network_message_end_nest(&o,item));
        struct md_filter_rule rule={0};
        assert(md_nft_expressions(NULL,0,&rule,o.data+start,o.size-start)==(i<2 ? 0 : -EINVAL));
        if (i<2) assert(rule.count==1 && rule.expressions[0].code==(i ? MD_FILTER_COUNTER : MD_FILTER_MASQUERADE));
        free(rule.wire);
    }
    puts("PASS nft optional expression data: omitted/empty stateless payloads and required parameter rejection");
}
static void batch_validation(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0);
    struct packet wire,reply;
    struct md_network_message o={.data=wire.data,.capacity=sizeof(wire.data)};
    begin(&o,1);
    uint32_t sequences[3];
    sequences[0]=table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE|NLM_F_ECHO,NFPROTO_IPV4,"invalid")->nlmsg_seq;
    sequences[1]=add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWCHAIN,NLM_F_CREATE|NLM_F_ACK,NFPROTO_IPV4)->nlmsg_seq;
    assert(!md_network_message_attribute(&o,NFTA_CHAIN_TABLE,"invalid",8));
    assert(!md_network_message_attribute(&o,NFTA_CHAIN_NAME,"out",4));
    assert(!md_network_message_attribute(&o,NFTA_CHAIN_TYPE,"nat",4));
    size_t hook,list,item,data;
    assert(!md_network_message_nest(&o,NFTA_CHAIN_HOOK,&hook));
    attribute_u32(&o,NFTA_HOOK_HOOKNUM,NF_INET_LOCAL_OUT);
    attribute_u32(&o,NFTA_HOOK_PRIORITY,0);
    assert(!md_network_message_end_nest(&o,hook));
    sequences[2]=add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,NLM_F_CREATE|NLM_F_ACK,NFPROTO_IPV4)->nlmsg_seq;
    assert(!md_network_message_attribute(&o,NFTA_RULE_TABLE,"invalid",8));
    assert(!md_network_message_attribute(&o,NFTA_RULE_CHAIN,"out",4));
    assert(!md_network_message_nest(&o,NFTA_RULE_EXPRESSIONS,&list));
    assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&item));
    assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"masq",5));
    assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
    assert(!md_network_message_end_nest(&o,data));
    assert(!md_network_message_end_nest(&o,item));
    assert(!md_network_message_end_nest(&o,list));
    end(&o); o.message->nlmsg_flags|=NLM_F_ACK;
    long length=run(n,&root,&o,&reply,NULL);
    struct nlmsghdr *h=(void *)reply.data;
    for (unsigned i=0;i<3;++i) {
        assert(NLMSG_OK(h,length) && h->nlmsg_type==NLMSG_ERROR && h->nlmsg_seq==sequences[i]);
        assert(!((struct nlmsgerr *)NLMSG_DATA(h))->error);
        h=NLMSG_NEXT(h,length);
    }
    assert(NLMSG_OK(h,length) && h->nlmsg_type==NLMSG_ERROR && h->nlmsg_seq==sequences[0]);
    assert(((struct nlmsgerr *)NLMSG_DATA(h))->error==-EOPNOTSUPP);
    h=NLMSG_NEXT(h,length); assert(!length && !n->filter);
    md_identity_release(&root); md_network_namespace_release(n);
    puts("PASS nft commit validation: retained operation ACKs, no rejected mutation echoes or premature end ACK");
}
static void reverse_policy(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_DROP};
    struct md_network_filter filter={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    n->filter=&filter;
    struct md_filter_packet packet={.destination=md_ip_v4(htonl(0xc0000201)),.destination_port=htons(53),
        .source_port=htons(32800),.protocol=IPPROTO_UDP,.state=8};
    struct md_network_flow_path path;
    assert(!md_network_flow_route(n,&packet,0,0,&path) && path.mediated);
    assert(!md_network_flow_check(n->fabric,&path,0));
    assert(md_network_flow_check(n->fabric,&path,1)==-EACCES);
    chain.policy=NF_ACCEPT;
    assert(!md_network_flow_route(n,&packet,0,0,&path) && !path.mediated);
    assert(!md_network_flow_check(n->fabric,&path,1));
    n->filter=NULL;
    md_network_namespace_release(n);
    puts("PASS nft reply policy: outbound native bypass requires both directions to allow IO");
}
static void nat_hook_order(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    uint32_t private=htonl(0x0a5c0002),uplink=htonl(0x0a000264);
    struct md_filter_chain chains[4]={
        {.table=1,.handle=2,.hook=NF_INET_POST_ROUTING,.priority=100,.nat=1,.policy=NF_ACCEPT},
        {.table=1,.handle=3,.hook=NF_INET_PRE_ROUTING,.priority=-300,.policy=NF_ACCEPT},
        {.table=1,.handle=4,.hook=NF_INET_PRE_ROUTING,.priority=0,.policy=NF_DROP},
        {.table=1,.handle=5,.hook=NF_INET_POST_ROUTING,.priority=200,.policy=NF_DROP}};
    struct md_filter_rule rules[4]={
        {.chain=2,.count=1,.expressions={{.code=MD_FILTER_MASQUERADE}}},
        {.chain=3,.count=3}, {.chain=4,.count=3}, {.chain=5,.count=3}};
    for (unsigned i=1;i<4;++i) {
        rules[i].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,
            .key=NFT_PAYLOAD_NETWORK_HEADER,.aux=i==3 ? 12 : 16,.size=4};
        rules[i].expressions[1]=(struct md_filter_expression){.code=MD_FILTER_COMPARE,.operation=NFT_CMP_EQ,.size=4};
        memcpy(rules[i].expressions[1].value,i==3 ? &uplink : &private,4);
        rules[i].expressions[2]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=i==1 ? NF_DROP : NF_ACCEPT};
    }
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_network_filter f={.tables=&table,.count=1,.chains=chains,.chain_count=4,.rules=rules,.rule_count=4};
    n->filter=&f;
    for (unsigned transport=0;transport<2;++transport) {
        struct md_filter_packet p={.source=md_ip_v4(private),.destination=md_ip_v4(htonl(0xc0000201)),.source_port=htons(32000),
            .destination_port=htons(80),.protocol=transport ? IPPROTO_UDP : IPPROTO_TCP,.state=8};
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&p,0,0,&path) && p.snat && p.source.v4==uplink);
        assert(!md_network_flow_check(n->fabric,&path,0));
        assert(!md_network_flow_check(n->fabric,&path,1));
        /* Raw rules must see the real incoming tuple and remain enforceable. */
        memcpy(rules[1].expressions[1].value,&uplink,4);
        assert(md_network_flow_check(n->fabric,&path,1)==-EACCES);
        memcpy(rules[1].expressions[1].value,&private,4);
        /* Even removal of the allocating NAT chain retains this connection's mapping. */
        chains[0].hook=-1;
        ++n->fabric->policy_generation;
        assert(!md_network_flow_check(n->fabric,&path,0));
        assert(!md_network_flow_check(n->fabric,&path,1));
        struct md_filter_accounting accounting={0};
        assert(!md_network_flow_accounting(n->fabric,&path,0,1,&accounting));
        free(accounting.cells);
        chains[0].hook=NF_INET_POST_ROUTING;
        /* Adding NAT after admission cannot retarget the first payload either. */
        chains[0].hook=chains[3].hook=-1;
        p=(struct md_filter_packet){.source=md_ip_v4(private),.destination=md_ip_v4(htonl(0xc0000201)),
            .source_port=htons(32000),.destination_port=htons(80),
            .protocol=transport ? IPPROTO_UDP : IPPROTO_TCP,.state=8};
        assert(!md_network_flow_route(n,&p,0,0,&path) && !p.snat);
        chains[0].hook=chains[3].hook=NF_INET_POST_ROUTING;
        memcpy(rules[3].expressions[1].value,&private,4);
        ++n->fabric->policy_generation;
        accounting=(struct md_filter_accounting){0};
        assert(!md_network_flow_accounting(n->fabric,&path,0,1,&accounting));
        free(accounting.cells);
        memcpy(rules[3].expressions[1].value,&uplink,4);
    }
    n->filter=NULL; md_network_namespace_release(n);
    puts("PASS retained NAT hook order: raw wire tuple, reverse translation, post-NAT filter, TCP/UDP and policy changes before first payload");
}
static void native_fib_publication(void) {
    for (unsigned v6=0;v6<2;++v6) {
        struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
        assert(n);
        uint32_t enabled[2]={1};
        assert(!md_network_parameter_write(n,v6 ? MD_NET_IPV6_FORWARD : MD_NET_IPV4_FORWARD,
            MD_NET_SCOPE_ALL,enabled));
        struct md_filter_chain chains[]={
            {.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.priority=-200,.policy=NF_DROP},
            {.table=1,.handle=3,.hook=NF_INET_PRE_ROUTING,.priority=-100,.nat=1,.policy=NF_ACCEPT},
            {.table=1,.handle=4,.hook=NF_INET_PRE_ROUTING,.priority=0,.policy=NF_DROP}};
        struct md_filter_rule rules[5]={{.chain=2,.count=2},{.chain=2,.count=3},
            {.chain=3,.count=1},{.chain=4,.count=2},{.chain=4,.count=3}};
        for (unsigned i=0;i<4;i+=3) {
            rules[i].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_STATE_MATCH,.key=2};
            rules[i].expressions[1]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        }
        for (unsigned i=1;i<5;i+=3) {
            rules[i].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_FIB,
                .key=NFT_FIB_RESULT_ADDRTYPE,.operation=NFTA_FIB_F_DADDR,.size=4};
            rules[i].expressions[1]=(struct md_filter_expression){.code=MD_FILTER_COMPARE,
                .operation=NFT_CMP_EQ,.size=4,.value={i==4 ? RTN_UNICAST : RTN_LOCAL}};
            rules[i].expressions[2]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        }
        struct md_ip_address source,host,target;
        assert(!md_network_profile_ip(v6 ? "2001:db8::2" : "192.0.2.2",&source));
        assert(!md_network_profile_ip(v6 ? "2001:db8::1" : "192.0.2.1",&host));
        assert(!md_network_profile_ip(v6 ? "2001:db8:2::1" : "198.51.100.1",&target));
        rules[2].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_NAT,
            .operation=NFT_NAT_DNAT,.constant=1,.key=1,.aux=1,.nat_port=htons(8080)};
        memcpy(rules[2].expressions[0].value,target.bytes,md_ip_size(target));
        struct md_network_filter_table table={.handle=1,.family=NFPROTO_INET};
        struct md_network_filter f={.tables=&table,.count=1,.chains=chains,.chain_count=3,.rules=rules,.rule_count=5};
        assert(!md_filter_validate(&f));
        n->filter=&f;
        struct md_filter_packet original={.source=source,.destination=host,.source_port=htons(32000),
            .destination_port=htons(18080),.protocol=IPPROTO_TCP,.state=8},p=original;
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&p,1,0,&path) && p.dnat && md_ip_equal(p.destination,target));
        assert(!md_network_flow_check(n->fabric,&path,0));
        assert(!md_network_flow_check(n->fabric,&path,1));
        p=original;
        assert(md_network_flow_route(n,&p,1,2,&path)==-EACCES);
        /* Port-only DNAT retains the native address, not a route to the uplink. */
        rules[2].expressions[0].key=0;
        rules[4].expressions[1].value[0]=RTN_LOCAL;
        p=original;
        assert(!md_network_flow_route(n,&p,1,0,&path) && p.dnat && path.destination==n->inode
            && md_ip_equal(p.destination,host) && p.destination_port==htons(8080));
        assert(!md_network_flow_check(n->fabric,&path,0));
        assert(!md_network_flow_check(n->fabric,&path,1));
        n->filter=NULL; md_network_namespace_release(n);
    }
    puts("PASS native FIB publication: IPv4/IPv6 local ownership, DNAT stages, port-only redirect, raw isolation and retained replies");
}
static void local_output_publication(void) {
    for (unsigned v6=0;v6<2;++v6) for (unsigned udp=0;udp<2;++udp) {
        struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
        assert(n);
        struct md_ip_address host,target;
        assert(!md_network_profile_ip(v6 ? "2001:db8::1" : "192.0.2.1",&host));
        assert(!md_network_profile_ip(v6 ? "2001:db8:2::1" : "198.51.100.1",&target));
        struct md_network_interface *owner=md_network_route_find(n,target,NULL);
        assert(owner && owner->info.index!=1);
        unsigned bound=owner->info.index;
        assert(!md_network_interface_address(owner,(struct md_network_ip_assignment){
            .address=host,.prefix=v6 ? 64 : 24,.scope=RT_SCOPE_UNIVERSE},0,0));
        struct md_filter_packet original={.source=md_ip_any(host.family),.destination=host,
            .source_port=htons(32000),.destination_port=htons(18080),
            .protocol=udp ? IPPROTO_UDP : IPPROTO_TCP,.state=8},p=original;
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&p,0,0,&path) && path.destination==n->inode);
        assert(md_ip_equal(p.source,host) && !path.mediated);
        assert(path.steps[0].packet.output==1 && !strcmp(path.steps[0].packet.output_name,"lo"));
        assert(path.steps[path.count-1].packet.input==bound);
        struct md_filter_chain chain={.handle=1,.hook=NF_INET_LOCAL_OUT,.nat=1,.policy=NF_ACCEPT};
        struct md_filter_rule rule={.chain=1,.count=3,.expressions={
            {.code=MD_FILTER_META,.key=NFT_META_OIF,.size=4},
            {.code=MD_FILTER_COMPARE,.operation=NFT_CMP_EQ,.size=4,.value={1}},
            {.code=MD_FILTER_NAT,.operation=NFT_NAT_DNAT,.constant=1,.key=1,.aux=1,.nat_port=htons(8080)}}};
        memcpy(rule.expressions[2].value,target.bytes,md_ip_size(target));
        struct md_network_filter_table table={.family=NFPROTO_INET};
        struct md_network_filter filter={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        assert(!md_filter_validate(&filter));
        n->filter=&filter;
        for (unsigned scoped=0;scoped<2;++scoped) {
            p=original;
            assert(!md_network_flow_route(n,&p,0,scoped ? bound : 0,&path));
            assert(p.dnat && md_ip_equal(p.destination,target) && md_ip_equal(p.source,host));
            assert(p.destination_port==htons(8080) && path.steps[0].packet.output==1);
            assert(path.steps[path.count-1].packet.output==bound);
            assert(!md_network_flow_check(n->fabric,&path,0));
            assert(!md_network_flow_check(n->fabric,&path,1));
        }
        p=original; p.destination=target;
        assert(!md_network_flow_route(n,&p,0,0,&path) && !p.dnat && !path.mediated);
        assert(path.steps[0].packet.output==bound);
        n->filter=NULL; md_network_namespace_release(n);
    }
    puts("PASS local-output interface: IPv4/IPv6 TCP/UDP, source selection, scoped output, DNAT reroute and retained replies");
}
static void native_nat_update(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    const int hooks[]={NF_INET_PRE_ROUTING,NF_INET_LOCAL_OUT,NF_INET_POST_ROUTING};
    for (unsigned transport=0;transport<2;++transport) for (unsigned h=0;h<3;++h) {
        struct md_filter_packet packet={.destination=md_ip_v4(htonl(0xc0000201)),.destination_port=htons(80),
            .source_port=htons(32800),.protocol=transport ? IPPROTO_UDP : IPPROTO_TCP,.state=8};
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&packet,0,0,&path) && !path.mediated);
        struct md_filter_chain chains[]={
            {.handle=1,.hook=hooks[h],.nat=1,.policy=NF_ACCEPT,.counters=1},
            {.handle=2,.hook=-1,.policy=NF_ACCEPT},
            {.handle=3,.hook=NF_INET_LOCAL_OUT,.policy=NF_ACCEPT}};
        struct md_filter_rule rules[]={
            {.chain=1,.count=1,.expressions={{.code=MD_FILTER_VERDICT,.operation=NFT_JUMP,.target=2}}},
            {.chain=2,.count=1,.expressions={{.code=MD_FILTER_COUNTER}}},
            {.chain=3,.count=1,.expressions={{.code=MD_FILTER_VERDICT,.operation=NFT_JUMP,.target=2}}}};
        struct md_network_filter_table table={.family=NFPROTO_IPV4};
        struct md_network_filter filter={.tables=&table,.count=1,.chains=chains,.chain_count=2,.rules=rules,.rule_count=2};
        /* Newly installed NAT counters do not require moving already native
         * streams into a relay; their admitted tuple must not be rewritten. */
        assert(!md_network_flow_admits(n,&filter,&path));
        chains[0].counters=0;
        assert(!md_network_flow_admits(n,&filter,&path));
        chains[0].policy=NF_DROP;
        assert(!md_network_flow_admits(n,&filter,&path));
        struct md_filter_packet first=packet;
        assert(md_filter_evaluate(&filter,hooks[h],&first)==-EACCES);
        chains[0].policy=NF_ACCEPT;
        chains[0].nat=0;
        assert(!md_network_flow_admits(n,&filter,&path));
        chains[0].nat=1; filter.chain_count=3; filter.rule_count=3;
        /* Filter counters also leave existing native streams unchanged. */
        assert(!md_network_flow_admits(n,&filter,&path));
        rules[2].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_DROP};
        assert(md_network_flow_admits(n,&filter,&path)==-EBUSY);
        assert(!chains[0].counter.packets && !rules[1].expressions[0].counter.packets);
    }
    md_network_namespace_release(n);
    puts("PASS native flow updates: retained NAT in both directions, counters, shared jump chains and filter revocation");
}
static void native_disjoint_update(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    for (unsigned transport=0;transport<2;++transport) {
        struct md_filter_packet packet={.destination=md_ip_v4(htonl(0xc0000201)),.destination_port=htons(443),
            .source_port=htons(32800),.protocol=transport ? IPPROTO_UDP : IPPROTO_TCP,.state=8};
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&packet,0,0,&path) && !path.mediated);
        struct md_filter_chain chain={.handle=1,.hook=NF_INET_LOCAL_OUT,.policy=NF_ACCEPT};
        struct md_filter_rule rule={.chain=1,.count=2,.expressions={
            {.code=MD_FILTER_TRANSPORT,.transport={.protocol=packet.protocol,
                .source={0,65535},.destination={53,53}}},
            {.code=MD_FILTER_COUNTER}}};
        struct md_network_filter_table table={.family=NFPROTO_IPV4};
        struct md_network_filter filter={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        for (unsigned reverse=0;reverse<2;++reverse) {
            chain.hook=reverse ? NF_INET_LOCAL_IN : NF_INET_LOCAL_OUT;
            const enum md_filter_opcode effects[]={MD_FILTER_COUNTER,MD_FILTER_META_SET,MD_FILTER_CT_SET,MD_FILTER_VERDICT};
            for (unsigned i=0;i<sizeof(effects)/sizeof(*effects);++i) {
                rule.expressions[1]=(struct md_filter_expression){.code=effects[i],.operation=NF_DROP};
                rule.expressions[0].transport.destination[0]=rule.expressions[0].transport.destination[1]=53;
                assert(!md_network_flow_admits(n,&filter,&path));
                rule.expressions[0].transport.destination[0]=rule.expressions[0].transport.destination[1]=reverse ? 32800 : 443;
                assert(md_network_flow_admits(n,&filter,&path)
                    ==(effects[i]==MD_FILTER_VERDICT ? -EBUSY : 0));
                if (effects[i]==MD_FILTER_COUNTER) {
                    struct md_filter_packet fresh=packet;
                    struct md_network_flow_path successor;
                    n->filter=&filter;
                    assert(!md_network_flow_route(n,&fresh,0,0,&successor) && successor.mediated);
                    n->filter=NULL;
                    assert(!rule.expressions[1].counter.packets && !rule.expressions[1].counter.bytes);
                }
            }
        }
        chain.hook=NF_INET_LOCAL_OUT;
        rule.expressions[0]=(struct md_filter_expression){.code=MD_FILTER_STATE_MATCH,.key=8};
        assert(md_network_flow_admits(n,&filter,&path)==-EBUSY);
        rule.expressions[0].key=2;
        assert(md_network_flow_admits(n,&filter,&path)==-EBUSY);
        rule.expressions[0].key=4;
        assert(!md_network_flow_admits(n,&filter,&path));
        assert(!chain.counter.packets && !chain.counter.bytes);
    }
    md_network_namespace_release(n);
    puts("PASS native flow updates: disjoint rules, exact TCP/UDP tuples, both directions, observational counters and enforced mutations");
}
static void program(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chains[2]={
        {.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.nat=1,.policy=NF_ACCEPT},
        {.table=1,.handle=3,.hook=-1,.policy=NF_ACCEPT}};
    struct md_filter_rule rules[2]={{.chain=2,.count=1},{.chain=3,.count=7}};
    struct md_network_filter f={.tables=&table,.count=1,.chains=chains,.chain_count=2,.rules=rules,.rule_count=2};
    rules[0].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NFT_JUMP,.target=3};
    struct md_filter_expression *e=rules[1].expressions;
    e[0]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,.key=NFT_PAYLOAD_NETWORK_HEADER,.aux=12,.size=4};
    e[1]=(struct md_filter_expression){.code=MD_FILTER_COMPARE,.operation=NFT_CMP_EQ,.size=4};
    uint32_t source=htonl(0x7f00002a),destination=htonl(0x7f000001);
    memcpy(e[1].value,&source,4);
    e[2]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,.key=NFT_PAYLOAD_TRANSPORT_HEADER,.aux=2,.size=2};
    e[3]=(struct md_filter_expression){.code=MD_FILTER_COMPARE,.operation=NFT_CMP_EQ,.size=2};
    uint16_t port=htons(39281); memcpy(e[3].value,&port,2);
    e[4]=(struct md_filter_expression){.code=MD_FILTER_IMMEDIATE,.dst=4,.size=4};
    memcpy(e[4].value,&destination,4);
    e[5]=(struct md_filter_expression){.code=MD_FILTER_IMMEDIATE,.dst=8,.size=2};
    uint16_t translated=htons(39282); memcpy(e[5].value,&translated,2);
    e[6]=(struct md_filter_expression){.code=MD_FILTER_NAT,.operation=NFT_NAT_DNAT,.key=1,.aux=1,.src=8,.dst=4};
    assert(!md_filter_validate(&f));
    struct md_filter_port ports[4]; size_t count=0;
    assert(!md_filter_ports(&f,md_ip_v4(htonl(0x0a000264)),ports,4,&count) && count==2);
    assert(!ports[0].address.v4 && ports[0].port==port && !ports[1].address.v4 && ports[1].port==port);
    chains[0].hook=NF_INET_LOCAL_OUT;
    assert(!md_filter_validate(&f));
    assert(!md_filter_ports(&f,md_ip_v4(htonl(0x0a000264)),ports,4,&count) && count==2);
    assert(!ports[0].address.v4 && ports[0].port==port && !ports[1].address.v4 && ports[1].port==port);
    chains[0].hook=NF_INET_PRE_ROUTING;
    struct md_filter_packet packet={.source=md_ip_v4(source),.destination=md_ip_v4(htonl(0x0a000264)),.destination_port=port,
        .protocol=IPPROTO_TCP,.state=8};
    assert(!md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet));
    assert(packet.dnat && packet.destination.v4==destination && packet.destination_port==translated);
    packet.source=md_ip_v4(htonl(0x7f000001)); packet.dnat=0; packet.destination_port=port;
    assert(!md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet) && !packet.dnat);
    chains[0].nat=0; assert(md_filter_validate(&f)==-EOPNOTSUPP);
    chains[0].nat=1; chains[0].hook=NF_INET_POST_ROUTING;
    assert(md_filter_validate(&f)==-EOPNOTSUPP);
    chains[0].hook=NF_INET_PRE_ROUTING;
    e[3].code=MD_FILTER_IMMEDIATE;
    assert(md_filter_ports(&f,md_ip_any(AF_INET),ports,4,&count)==-EOPNOTSUPP);
    rules[1].expressions[0]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NFT_JUMP,.target=3};
    assert(md_filter_validate(&f)==-ELOOP);
    rules[1].count=0;
    chains[0].nat=0;
    rules[0].expressions[0].operation=NFT_GOTO;
    assert(!md_filter_validate(&f));
    chains[0].policy=NF_DROP;
    assert(md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet)==-EACCES);
    chains[0].policy=NF_ACCEPT;
    assert(!md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet));
    rules[0].expressions[0].operation=NFT_RETURN;
    assert(!md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet));
    chains[0].policy=NF_DROP;
    assert(md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet)==-EACCES);
    puts("PASS nft program: transitive hook validation, cycle rejection, source-dependent finite publication and real tuple evaluation");
}
static void fib_hook_validation(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_INET};
    struct md_filter_chain chains[]={
        {.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.policy=NF_ACCEPT},
        {.table=1,.handle=3,.hook=-1},
        {.table=1,.handle=4,.hook=NF_INET_LOCAL_OUT,.policy=NF_ACCEPT}};
    struct md_filter_rule rules[]={
        {.chain=2,.count=1,.expressions={{.code=MD_FILTER_VERDICT,.operation=NFT_JUMP,.target=3}}},
        {.chain=3,.count=1,.expressions={{.code=MD_FILTER_FIB,.key=NFT_FIB_RESULT_OIF,.size=4}}},
        {.chain=4,.count=1,.expressions={{.code=MD_FILTER_VERDICT,.operation=NFT_GOTO,.target=3}}}};
    struct md_network_filter f={.tables=&table,.count=1,.chains=chains,.chain_count=3,
        .rules=rules,.rule_count=2};
    struct md_filter_expression *e=&rules[1].expressions[0];
    const unsigned results[]={NFT_FIB_RESULT_OIF,NFT_FIB_RESULT_OIFNAME,NFT_FIB_RESULT_ADDRTYPE};
    const unsigned devices[]={0,NFTA_FIB_F_IIF,NFTA_FIB_F_OIF};
    for (unsigned i=0;i<3;++i) for (unsigned j=0;j<3;++j) {
        if (i<2 && j==2) continue;
        e->key=results[i]; e->operation=NFTA_FIB_F_SADDR|devices[j];
        for (unsigned nat=0;nat<2;++nat) for (unsigned hook=0;hook<NF_INET_NUMHOOKS;++hook) {
            chains[0].nat=nat; chains[0].hook=hook;
            int valid=i<2 || j==1 ? hook==NF_INET_PRE_ROUTING || hook==NF_INET_LOCAL_IN || hook==NF_INET_FORWARD
                : j==2 ? hook==NF_INET_LOCAL_OUT || hook==NF_INET_POST_ROUTING || hook==NF_INET_FORWARD : 1;
            assert(md_filter_validate(&f)==(valid ? 0 : -EOPNOTSUPP));
        }
    }
    chains[0].hook=NF_INET_PRE_ROUTING; chains[0].nat=0;
    e->key=NFT_FIB_RESULT_OIF; e->operation=NFTA_FIB_F_SADDR|NFTA_FIB_F_IIF;
    assert(!md_filter_validate(&f));
    f.rule_count=3;
    assert(md_filter_validate(&f)==-EOPNOTSUPP);
    rules[2].count=0;
    assert(!md_filter_validate(&f));
    puts("PASS nft fib hook validation: all hooks, filter/NAT, inherited jump/goto and shared-chain rejection");
}
static void verdict_maps(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chains[2]={
        {.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_ACCEPT},
        {.table=1,.handle=3,.hook=-1}};
    struct md_filter_element elements[2]={
        {.key="eth0",.verdict={NF_DROP,0}},
        {.key="lo",.verdict={NFT_JUMP,3}}};
    struct md_filter_set set={.table=1,.handle=4,.flags=NFT_SET_MAP,.key_size=16,.data_type=NFT_DATA_VERDICT,
        .elements=elements,.count=2};
    struct md_filter_rule rules[2]={
        {.chain=2,.count=2,.expressions={{.code=MD_FILTER_META,.key=NFT_META_IIFNAME,.size=16},
            {.code=MD_FILTER_VERDICT_MAP,.target=4,.size=16}}},
        {.chain=3,.count=2,.expressions={{.code=MD_FILTER_COUNTER},
            {.code=MD_FILTER_VERDICT,.operation=NFT_RETURN}}}};
    struct md_network_filter f={.tables=&table,.count=1,.chains=chains,.chain_count=2,
        .rules=rules,.rule_count=2,.sets=&set,.set_count=1};
    assert(!md_filter_validate(&f));
    struct md_filter_accounting accounting={0};
    struct md_filter_packet p={.destination=md_ip_any(AF_INET),.input_name="lo",.state=8,.accounting=&accounting};
    assert(!md_filter_evaluate(&f,NF_INET_LOCAL_IN,&p) && accounting.count==1);
    assert(!rules[1].expressions[0].counter.packets);
    md_filter_account(&accounting,123);
    assert(rules[1].expressions[0].counter.packets==1 && rules[1].expressions[0].counter.bytes==123);
    strcpy(p.input_name,"eth0"); accounting.count=0;
    assert(md_filter_evaluate(&f,NF_INET_LOCAL_IN,&p)==-EACCES && !accounting.count);
    strcpy(p.input_name,"missing"); assert(!md_filter_evaluate(&f,NF_INET_LOCAL_IN,&p));
    rules[1].expressions[1]=(struct md_filter_expression){.code=MD_FILTER_VERDICT_MAP,.target=4,.size=16};
    assert(md_filter_validate(&f)==-ELOOP);
    rules[1].expressions[1]=(struct md_filter_expression){.code=MD_FILTER_MASQUERADE};
    assert(md_filter_validate(&f)==-EOPNOTSUPP);
    free(accounting.cells);
    puts("PASS verdict maps: return, denial, miss, cycle and transitive NAT rejection; explicit counter accounting");
}
static void interval_sets(void) {
    struct md_network_filter *f=md_network_filter_copy(NULL); assert(f);
    assert(!md_network_filter_add(f,NFPROTO_IPV6,"intervals",NULL,0));
    struct packet request,reply;
    struct md_network_message o={.data=request.data,.capacity=sizeof(request.data)};
    struct md_network_message out={.data=reply.data,.capacity=sizeof(reply.data)};
    add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWSET,NLM_F_CREATE,NFPROTO_IPV6);
    assert(!md_network_message_attribute(&o,NFTA_SET_TABLE,"intervals",10));
    assert(!md_network_message_attribute(&o,NFTA_SET_NAME,"prefixes",9));
    uint32_t flags=htonl(NFT_SET_INTERVAL),type=htonl(8),length=htonl(16);
    assert(!md_network_message_attribute(&o,NFTA_SET_FLAGS,&flags,4));
    assert(!md_network_message_attribute(&o,NFTA_SET_KEY_TYPE,&type,4));
    assert(!md_network_message_attribute(&o,NFTA_SET_KEY_LEN,&length,4));
    assert(!md_nft_objects(f,o.message,43,&out) && f->set_count==1);
    struct md_filter_set *s=&f->sets[0];
    /* Out-of-order insertion, two adjacent ranges, an initial end sentinel,
     * and a final range extending through the largest IPv6 address. */
    const unsigned values[]={0x40,0x20,0,0x30,0x20,0x10};
    const unsigned ends[]={0,0,1,1,1,0};
    for (unsigned i=0;i<6;++i) {
        o.size=out.size=0;
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWSETELEM,NLM_F_CREATE|NLM_F_EXCL,NFPROTO_IPV6);
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_TABLE,"intervals",10));
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_SET,"prefixes",9));
        size_t list,item,key;
        assert(!md_network_message_nest(&o,NFTA_SET_ELEM_LIST_ELEMENTS,&list));
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&item));
        assert(!md_network_message_nest(&o,NFTA_SET_ELEM_KEY,&key));
        unsigned char address[16]={(unsigned char)values[i]};
        assert(!md_network_message_attribute(&o,NFTA_DATA_VALUE,address,16));
        assert(!md_network_message_end_nest(&o,key));
        flags=htonl(ends[i] ? NFT_SET_ELEM_INTERVAL_END : 0);
        assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_FLAGS,&flags,4));
        assert(!md_network_message_end_nest(&o,item));
        assert(!md_network_message_end_nest(&o,list));
        assert(!md_nft_objects(f,o.message,43,&out));
        assert(md_nft_objects(f,o.message,43,&out)==-EEXIST);
    }
    assert(!md_filter_validate(f) && s->count==6);
    for (unsigned value=0;value<256;++value) {
        unsigned char key[16]={(unsigned char)value};
        int expected=(value>=0x10 && value<0x30) || value>=0x40;
        assert(!!md_filter_set_lookup(s,key)==expected);
        memset(key+1,255,15);
        assert(!!md_filter_set_lookup(s,key)==expected);
    }
    s->elements[4].end=0;
    assert(md_filter_validate(f)==-EEXIST); s->elements[4].end=1;
    o.size=out.size=0;
    add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_GETSETELEM,NLM_F_DUMP,NFPROTO_IPV6);
    assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_TABLE,"intervals",10));
    assert(!md_network_message_attribute(&o,NFTA_SET_ELEM_LIST_SET,"prefixes",9));
    assert(!md_nft_objects(f,o.message,43,&out));
    struct nlmsghdr *dump=(void *)out.data;
    dump->nlmsg_type=(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWSETELEM; dump->nlmsg_flags=NLM_F_CREATE;
    s->count=0;
    o.size=0; assert(!md_nft_objects(f,dump,43,&o));
    assert(s->count==6 && !md_filter_validate(f));
    unsigned char boundary[16]={0x20};
    assert(md_filter_set_lookup(s,boundary)==s->elements+3);
    md_network_filter_free(f);
    puts("PASS nft interval sets: sorted insertion, boundaries, adjacent ranges, sentinel, IPv6 maximum, overlap rejection and dump roundtrip");
}
static void policy_counters(void) {
    struct md_filter_chain chains[2]={
        {.table=1,.handle=2,.hook=NF_INET_LOCAL_OUT,.policy=NF_ACCEPT,.counters=1},
        {.table=1,.handle=3,.hook=-1}};
    struct md_filter_rule rule={.chain=2,.count=1,.expressions={
        {.code=MD_FILTER_VERDICT,.operation=NFT_GOTO,.target=3}}};
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_network_filter f={.tables=&table,.count=1,.chains=chains,.chain_count=2,.rules=&rule,.rule_count=1};
    struct md_filter_accounting a={0};
    struct md_filter_packet p={.destination=md_ip_any(AF_INET),.state=8,.accounting=&a};
    assert(!md_filter_evaluate(&f,NF_INET_LOCAL_OUT,&p) && a.count==1);
    md_filter_account(&a,123);
    assert(chains[0].counter.packets==1 && chains[0].counter.bytes==123);
    a.count=0; rule.expressions[0].operation=NF_ACCEPT;
    assert(!md_filter_evaluate(&f,NF_INET_LOCAL_OUT,&p) && !a.count);
    rule.expressions[0].operation=NFT_RETURN;
    chains[0].policy=NF_DROP;
    assert(md_filter_evaluate(&f,NF_INET_LOCAL_OUT,&p)==-EACCES && a.count==1);
    md_filter_account(&a,456);
    assert(chains[0].counter.packets==2 && chains[0].counter.bytes==579);
    a.count=0; rule.expressions[0].operation=NF_DROP;
    assert(md_filter_evaluate(&f,NF_INET_LOCAL_OUT,&p)==-EACCES && !a.count);
    chains[0].nat=1; p.state=2; a.count=0;
    assert(!md_filter_evaluate(&f,NF_INET_LOCAL_OUT,&p) && !a.count);
    free(a.cells);
    puts("PASS base-chain counters: policy fallthrough, goto/return, explicit verdict exclusion and NAT established bypass");
}
static int parse_transport(struct md_network_filter *f,struct md_filter_rule *r,int tcp,unsigned inversion,unsigned flags) {
    struct packet p;
    struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
    add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
    size_t start=o.size;
    size_t entry,data;
    assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
    assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"match",6));
    assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
    assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,tcp ? "tcp" : "udp",4));
    uint32_t revision=0;
    assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
    unsigned char info[16]={0};
    if (tcp) {
        struct xt_tcp value={.spts={1000,2000},.dpts={4000,4001},.invflags=inversion,.flg_mask=flags};
        memcpy(info,&value,sizeof(value));
    } else {
        struct xt_udp value={.spts={1000,2000},.dpts={4000,4001},.invflags=inversion};
        memcpy(info,&value,sizeof(value));
    }
    assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,info,sizeof(info)));
    assert(!md_network_message_end_nest(&o,data));
    assert(!md_network_message_end_nest(&o,entry));
    return md_nft_expressions(f,1,r,o.data+start,o.size-start);
}
static void compat_transport(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.policy=NF_ACCEPT,.nat=1};
    struct md_filter_rule rule={.chain=2};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
    for (unsigned tcp=0;tcp<2;++tcp) for (unsigned invert=0;invert<4;++invert) {
        assert(md_nft_compat_supported(NFPROTO_IPV4,tcp ? "tcp" : "udp",0,0));
        assert(!parse_transport(&f,&rule,tcp,invert,0) && rule.count==1);
        struct packet dump;
        struct md_network_message out={.data=dump.data,.capacity=sizeof(dump.data)};
        add(&out,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=out.size;
        assert(!md_nft_emit_expressions(&f,&rule,&out));
        assert(out.size==start+NLA_HDRLEN+rule.wire_size && !memcmp(dump.data+start+NLA_HDRLEN,rule.wire,rule.wire_size));
        free(rule.wire); rule.wire=NULL;
        rule.expressions[1]=(struct md_filter_expression){.code=MD_FILTER_NAT,.operation=NFT_NAT_DNAT,
            .constant=1,.family=AF_INET,.aux=1,.nat_port=htons(5000)};
        rule.count=2;
        for (unsigned src=0;src<2;++src) for (unsigned dst=0;dst<2;++dst) {
            struct md_filter_packet p={.destination=md_ip_any(AF_INET),.protocol=tcp ? IPPROTO_TCP : IPPROTO_UDP,.state=8,
                .source_port=htons(src ? 1500 : 999),.destination_port=htons(dst ? 4000 : 4002)};
            unsigned match=(src!=!!(invert&1)) && (dst!=!!(invert&2));
            assert(!md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&p) && p.dnat==match);
            if (match) assert(p.destination_port==htons(5000));
            p.protocol=tcp ? IPPROTO_UDP : IPPROTO_TCP; p.dnat=0;
            assert(!md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&p) && !p.dnat);
        }
        struct md_filter_port ports[4]; size_t count=0;
        int error=md_filter_ports(&f,md_ip_any(AF_INET),ports,4,&count);
        if (invert&2) assert(error==-EOPNOTSUPP);
        else {
            assert(!error && count==2);
            assert(ports[0].protocol==(tcp ? IPPROTO_TCP : IPPROTO_UDP));
            assert(ports[0].port==htons(4000) && ports[1].port==htons(4001));
        }
        memset(&rule,0,sizeof(rule)); rule.chain=2;
    }
    assert(parse_transport(&f,&rule,1,0,2)==-EOPNOTSUPP);
    assert(parse_transport(&f,&rule,0,4,0)==-EOPNOTSUPP);
    assert(md_nft_compat_supported(NFPROTO_IPV6,"tcp",0,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"udp",1,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"udp",0,1));
    puts("PASS xt TCP/UDP: raw ABI, dump roundtrip, source/destination ranges and inversion, NAT publication and explicit feature rejection");
}
static void compat_nat(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.policy=NF_ACCEPT,.nat=1};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    for (unsigned snat=0;snat<2;++snat) for (unsigned rev=0;rev<3;++rev)
        for (unsigned variant=0;variant<8;++variant) {
            const char *name=snat ? "SNAT" : "DNAT";
            assert(md_nft_compat_supported(NFPROTO_IPV4,name,rev,1));
            chain.hook=snat ? NF_INET_POST_ROUTING : NF_INET_PRE_ROUTING;
            struct nf_nat_range2 r={.flags=NF_NAT_RANGE_MAP_IPS|NF_NAT_RANGE_PROTO_SPECIFIED,
                .min_addr.ip=htonl(0x0a580002),.max_addr.ip=htonl(0x0a580002),
                .min_proto.all=htons(8080),.max_proto.all=htons(8080)};
            if (variant==1) r.flags=NF_NAT_RANGE_MAP_IPS;
            if (variant==2) r.flags=NF_NAT_RANGE_PROTO_SPECIFIED;
            if (variant==3) r.max_addr.ip=htonl(0x0a580003);
            if (variant==4) r.max_proto.all=htons(8081);
            if (variant==5) r.flags|=NF_NAT_RANGE_PROTO_RANDOM;
            if (variant==6) { r.flags|=NF_NAT_RANGE_PROTO_OFFSET; r.base_proto.all=htons(1000); }
            struct nf_nat_ipv4_multi_range_compat r0={.rangesize=1,
                .range={{.flags=r.flags,.min_ip=r.min_addr.ip,.max_ip=r.max_addr.ip,
                    .min=r.min_proto,.max=r.max_proto}}};
            struct nf_nat_range r1={.flags=r.flags,.min_addr=r.min_addr,.max_addr=r.max_addr,
                .min_proto=r.min_proto,.max_proto=r.max_proto};
            unsigned char wire[(sizeof(r)+7)&~(size_t)7]={0};
            size_t size=rev==0 ? sizeof(r0) : rev==1 ? sizeof(r1) : sizeof(r);
            memcpy(wire,rev==0 ? (void *)&r0 : rev==1 ? (void *)&r1 : (void *)&r,size);
            struct packet p;
            struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
            add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
            size_t start=o.size,entry,data;
            assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
            assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"target",7));
            assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
            assert(!md_network_message_attribute(&o,NFTA_TARGET_NAME,name,strlen(name)+1));
            uint32_t revision=htonl(rev);
            assert(!md_network_message_attribute(&o,NFTA_TARGET_REV,&revision,4));
            assert(!md_network_message_attribute(&o,NFTA_TARGET_INFO,wire,((size+7)&~(size_t)7)-(variant==7)));
            assert(!md_network_message_end_nest(&o,data));
            assert(!md_network_message_end_nest(&o,entry));
            struct md_filter_rule rule={.chain=2};
            int error=md_nft_expressions(&f,1,&rule,o.data+start,o.size-start);
            if (variant>=3 && !(variant==4 && snat)) { assert(error==(variant==7 ? -EINVAL : -EOPNOTSUPP)); continue; }
            assert(!error && rule.count==1 && rule.expressions[0].code==MD_FILTER_NAT);
            f.rules=&rule; f.rule_count=1;
            for (unsigned udp=0;udp<2;++udp) {
                struct md_filter_packet flow={.protocol=udp ? IPPROTO_UDP : IPPROTO_TCP,.state=8,
                    .source=md_ip_v4(htonl(0xc0000201)),.destination=md_ip_v4(htonl(0xc0000202)),
                    .source_port=htons(40000),.destination_port=htons(443)};
                assert(!md_filter_evaluate(&f,chain.hook,&flow));
                assert(snat ? flow.snat : flow.dnat);
                assert((snat ? flow.source.v4 : flow.destination.v4)==(variant==2 ? htonl(snat ? 0xc0000201 : 0xc0000202) : r.min_addr.ip));
                assert((snat ? flow.source_port : flow.destination_port)==(variant==1 ? htons(snat ? 40000 : 443) : r.min_proto.all));
            }
            o.size=NLMSG_HDRLEN; o.message=(void *)p.data;
            assert(!md_nft_emit_expressions(&f,&rule,&o));
            struct nlattr *list=(void *)(p.data+NLMSG_HDRLEN);
            struct md_filter_rule copy={.chain=2};
            assert(!md_nft_expressions(&f,1,&copy,list+1,list->nla_len-sizeof(*list)));
            assert(copy.wire_size==rule.wire_size && !memcmp(copy.wire,rule.wire,rule.wire_size));
            assert(copy.count==rule.count && !memcmp(copy.expressions,rule.expressions,rule.count*sizeof(*rule.expressions)));
            free(copy.wire); free(rule.wire); f.rules=NULL; f.rule_count=0;
        }
    puts("PASS xt NAT revisions 0/1/2: TCP/UDP translations, wire roundtrip, unsupported flags/ranges and malformed size");
}
static void compat_masquerade(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_POST_ROUTING,.policy=NF_ACCEPT,.nat=1};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    assert(md_nft_compat_supported(NFPROTO_IPV4,"MASQUERADE",0,1));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"MASQUERADE",1,1));
    assert(md_nft_compat_supported(NFPROTO_IPV6,"MASQUERADE",0,1));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"MASQUERADE",0,0));
    for (unsigned variant=0;variant<7;++variant) {
        struct packet p;
        struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=o.size,entry,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"target",7));
        assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
        assert(!md_network_message_attribute(&o,NFTA_TARGET_NAME,"MASQUERADE",11));
        uint32_t revision=0;
        assert(!md_network_message_attribute(&o,NFTA_TARGET_REV,&revision,4));
        struct nf_nat_ipv4_multi_range_compat info={.rangesize=variant==1 ? 2 : 1};
        if (variant==2) info.range[0].flags=NF_NAT_RANGE_PROTO_SPECIFIED;
        if (variant==3) info.range[0].min_ip=htonl(1);
        if (variant>=4) {
            info.range[0].flags=NF_NAT_RANGE_PROTO_SPECIFIED;
            info.range[0].min.all=htons(1024); info.range[0].max.all=htons(65535);
            if (variant==5) info.range[0].flags|=NF_NAT_RANGE_PROTO_RANDOM;
            if (variant==6) info.range[0].max.all=htons(1000);
        }
        unsigned char wire[(sizeof(info)+7)&~(size_t)7];
        memset(wire,0,sizeof(wire)); memcpy(wire,&info,sizeof(info));
        assert(!md_network_message_attribute(&o,NFTA_TARGET_INFO,wire,sizeof(wire)));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,entry));
        struct md_filter_rule rule={.chain=2};
        int error=md_nft_expressions(&f,1,&rule,o.data+start,o.size-start);
        if (variant==1 || variant==3 || variant>=5) { assert(error==(variant==6 ? -EINVAL : -EOPNOTSUPP)); continue; }
        assert(!error && rule.count==1 && rule.expressions[0].code==MD_FILTER_MASQUERADE);
        f.rules=&rule; f.rule_count=1;
        struct md_filter_packet flow={.protocol=IPPROTO_TCP,.state=8,
            .source=md_ip_v4(htonl(0x0a580002)),.destination=md_ip_v4(htonl(0x08080808)),
            .masquerade_address=md_ip_v4(htonl(0x0a580001))};
        assert(!md_filter_evaluate(&f,NF_INET_POST_ROUTING,&flow));
        assert(flow.masquerade && flow.snat && md_ip_equal(flow.source,flow.masquerade_address));
        assert(flow.source_port==htons(variant==2 ? 0 : variant==4 ? 1024 : 1));
        free(rule.wire); f.rules=NULL; f.rule_count=0;
    }
    puts("PASS xt MASQUERADE: port ranges and explicit unsupported flag/address rejection");
}
static void nft_source_port_range(void) {
    for (unsigned ipv6=0;ipv6<2;++ipv6) for (unsigned snat=0;snat<2;++snat) {
        struct md_network_filter_table table={.handle=1,.family=NFPROTO_INET};
        struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_POST_ROUTING,.policy=NF_ACCEPT,.nat=1};
        struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
        struct md_filter_rule typed={.chain=2,.count=3,.expressions={
            {.code=MD_FILTER_IMMEDIATE,.dst=0,.size=2},
            {.code=MD_FILTER_IMMEDIATE,.dst=4,.size=2},
            {.code=snat ? MD_FILTER_NAT : MD_FILTER_MASQUERADE,.operation=NFT_NAT_SNAT,
                .family=snat ? (ipv6 ? AF_INET6 : AF_INET) : 0,.aux=1,.src=0,.port_range=1,.port_max=4}}};
        uint16_t low=htons(40000),high=htons(40001);
        memcpy(typed.expressions[0].value,&low,2); memcpy(typed.expressions[1].value,&high,2);
        struct packet packet;
        struct md_network_message out={.data=packet.data,.capacity=sizeof(packet.data),.size=NLMSG_HDRLEN,.message=(void *)packet.data};
        assert(!md_nft_emit_expressions(&f,&typed,&out));
        const struct nlattr *list=(void *)(packet.data+NLMSG_HDRLEN);
        struct md_filter_rule parsed={.chain=2};
        assert(!md_nft_expressions(&f,1,&parsed,list+1,list->nla_len-sizeof(*list)));
        assert(parsed.count==3 && !memcmp(parsed.expressions,typed.expressions,3*sizeof(*typed.expressions)));
        f.rules=&parsed; f.rule_count=1; assert(!md_filter_validate(&f));
        for (unsigned tcp=0;tcp<2;++tcp) for (unsigned port=39999;port<=40002;++port) {
            struct md_filter_packet p={.source=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET),
                .destination=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET),.source_port=htons(port),
                .protocol=tcp ? IPPROTO_TCP : IPPROTO_UDP,.state=8};
            p.masquerade_address=p.source;
            assert(!md_filter_evaluate(&f,NF_INET_POST_ROUTING,&p));
            assert(p.source_port==htons(port==40001 ? 40001 : 40000));
        }
        low=htons(40002); memcpy(parsed.expressions[0].value,&low,2);
        struct md_filter_packet p={.source=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET),
            .destination=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET),.protocol=IPPROTO_TCP,.state=8};
        assert(md_filter_evaluate(&f,NF_INET_POST_ROUTING,&p)==-EINVAL);
        free(parsed.wire);
        /* Register reads must not accept uninitialized maximum bounds. */
        typed.expressions[1]=typed.expressions[2]; typed.count=2;
        out.size=NLMSG_HDRLEN;
        assert(!md_nft_emit_expressions(&f,&typed,&out));
        list=(void *)(packet.data+NLMSG_HDRLEN); parsed=(struct md_filter_rule){.chain=2};
        assert(md_nft_expressions(&f,1,&parsed,list+1,list->nla_len-sizeof(*list))==-EINVAL);
    }
    puts("PASS nft source NAT: initialized min/max registers, IPv4/IPv6 TCP/UDP, source-port preservation, invalid ranges and dump roundtrip");
}
static void compat_comment(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_network_filter f={.tables=&table,.count=1};
    struct packet p;
    struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
    add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
    size_t start=o.size,entry,data;
    assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
    assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"match",6));
    assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
    assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,"comment",8));
    uint32_t revision=0;
    assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
    struct xt_comment_info comment={.comment="container forwarding"};
    assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,&comment,sizeof(comment)));
    assert(!md_network_message_end_nest(&o,data));
    assert(!md_network_message_end_nest(&o,entry));
    struct md_filter_rule rule={0};
    assert(md_nft_compat_supported(NFPROTO_IPV4,"comment",0,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"comment",1,0));
    assert(!md_nft_expressions(&f,1,&rule,o.data+start,o.size-start));
    assert(rule.count==1 && rule.expressions[0].code==MD_FILTER_VERDICT
        && (int)rule.expressions[0].operation==NFT_CONTINUE);
    struct packet dump;
    struct md_network_message out={.data=dump.data,.capacity=sizeof(dump.data)};
    add(&out,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
    start=out.size;
    assert(!md_nft_emit_expressions(&f,&rule,&out));
    assert(out.size==start+NLA_HDRLEN+rule.wire_size
        && !memcmp(dump.data+start+NLA_HDRLEN,rule.wire,rule.wire_size));
    free(rule.wire);
    puts("PASS xt comment: unconditional predicate and exact dump preservation");
}
static void compat_conntrack(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_FORWARD,.policy=NF_DROP};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    assert(md_nft_compat_supported(NFPROTO_IPV4,"conntrack",3,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"conntrack",2,0));
    for (unsigned variant=0;variant<4;++variant) {
        struct packet p;
        struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=o.size,entry,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"match",6));
        assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
        assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,"conntrack",10));
        uint32_t revision=htonl(3);
        assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
        struct xt_conntrack_mtinfo3 info={.match_flags=XT_CONNTRACK_STATE,.state_mask=6,
            .invert_flags=variant==1 ? XT_CONNTRACK_STATE : 0};
        if (variant==2) info.match_flags|=XT_CONNTRACK_PROTO;
        if (variant==3) info.state_mask|=XT_CONNTRACK_STATE_SNAT;
        unsigned char wire[(sizeof(info)+7)&~(size_t)7];
        memset(wire,0,sizeof(wire)); memcpy(wire,&info,sizeof(info));
        assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,wire,sizeof(wire)));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,entry));
        struct md_filter_rule rule={.chain=2};
        int error=md_nft_expressions(&f,1,&rule,o.data+start,o.size-start);
        if (variant>=2) { assert(error==-EOPNOTSUPP); continue; }
        assert(!error && rule.count==1);
        rule.expressions[rule.count++]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        f.rules=&rule; f.rule_count=1;
        for (unsigned state=1;state<=8;state<<=1) {
            struct md_filter_packet flow={.destination=md_ip_any(AF_INET),.state=state};
            int accepted=!!(state&6)!=!!variant;
            assert(md_filter_evaluate(&f,NF_INET_FORWARD,&flow)==(accepted ? 0 : -EACCES));
        }
        free(rule.wire); f.rules=NULL; f.rule_count=0;
    }
    puts("PASS xt conntrack: original state masks, inversion and unsupported selectors rejected");
}
static void compat_mark(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_FORWARD,.policy=NF_DROP};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    assert(md_nft_compat_supported(NFPROTO_IPV4,"MARK",2,1));
    assert(md_nft_compat_supported(NFPROTO_IPV4,"mark",1,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"MARK",0,1));
    for (unsigned variant=0;variant<3;++variant) {
        struct packet p;
        struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=o.size,entry,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,variant ? "match" : "target",variant ? 6 : 7));
        assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
        uint32_t revision=htonl(variant ? 1 : 2);
        if (variant) {
            struct xt_mark_mtinfo1 info={.mark=0x2000,.mask=0x2000,.invert=variant==2};
            unsigned char wire[(sizeof(info)+7)&~(size_t)7];
            memset(wire,0,sizeof(wire)); memcpy(wire,&info,sizeof(info));
            assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,"mark",5));
            assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
            assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,wire,sizeof(wire)));
        } else {
            struct xt_mark_tginfo2 info={.mark=0x2001,.mask=0x2000};
            assert(!md_network_message_attribute(&o,NFTA_TARGET_NAME,"MARK",5));
            assert(!md_network_message_attribute(&o,NFTA_TARGET_REV,&revision,4));
            assert(!md_network_message_attribute(&o,NFTA_TARGET_INFO,&info,sizeof(info)));
        }
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,entry));
        struct md_filter_rule rule={.chain=2};
        assert(!md_nft_expressions(&f,1,&rule,o.data+start,o.size-start));
        rule.expressions[rule.count++]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        f.rules=&rule; f.rule_count=1;
        for (unsigned bit=0;bit<2;++bit) {
            struct md_filter_packet flow={.destination=md_ip_any(AF_INET),.mark=0x4001|(bit ? 0x2000 : 0)};
            int accepted=!variant || (bit!=(variant==2));
            assert(md_filter_evaluate(&f,NF_INET_FORWARD,&flow)==(accepted ? 0 : -EACCES));
            if (!variant) assert(flow.mark==0x6000);
        }
        free(rule.wire); f.rules=NULL; f.rule_count=0;
    }
    puts("PASS xt mark: masked update, preserved bits, XOR and inverted predicate");
}
static void compat_connmark(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.policy=NF_DROP};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    assert(md_nft_compat_supported(NFPROTO_IPV4,"CONNMARK",1,1));
    assert(md_nft_compat_supported(NFPROTO_IPV6,"connmark",1,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"CONNMARK",2,1));
    for (unsigned variant=0;variant<5;++variant) {
        struct packet p; struct md_network_message out={.data=p.data,.capacity=sizeof(p.data)};
        add(&out,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=out.size,entry,data;
        int match=variant>=3;
        assert(!md_network_message_nest(&out,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&out,NFTA_EXPR_NAME,match ? "match" : "target",match ? 6 : 7));
        assert(!md_network_message_nest(&out,NFTA_EXPR_DATA,&data));
        uint32_t revision=htonl(1);
        assert(!md_network_message_attribute(&out,match ? NFTA_MATCH_NAME : NFTA_TARGET_NAME,match ? "connmark" : "CONNMARK",9));
        assert(!md_network_message_attribute(&out,match ? NFTA_MATCH_REV : NFTA_TARGET_REV,&revision,4));
        struct xt_connmark_tginfo1 target={.ctmark=0x2001,.ctmask=0x6000,.nfmask=0x2000,.mode=variant};
        struct xt_connmark_mtinfo1 predicate={.mark=0x2000,.mask=0x2000,.invert=variant==4};
        unsigned char wire[16]={0};
        memcpy(wire,match ? (void *)&predicate : (void *)&target,match ? sizeof(predicate) : sizeof(target));
        assert(!md_network_message_attribute(&out,match ? NFTA_MATCH_INFO : NFTA_TARGET_INFO,wire,sizeof(wire)));
        assert(!md_network_message_end_nest(&out,data)); assert(!md_network_message_end_nest(&out,entry));
        struct md_filter_rule rule={.chain=2};
        assert(!md_nft_expressions(&f,1,&rule,out.data+start,out.size-start));
        rule.expressions[rule.count++]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        f.rules=&rule; f.rule_count=1;
        for (unsigned bit=0;bit<2;++bit) {
            uint32_t mark=0x4001|(bit ? 0x2000 : 0),ct=0x8003|(bit ? 0x2000 : 0);
            struct md_filter_packet packet={.destination=md_ip_any(AF_INET),.mark=mark,.ctmark=ct};
            assert(md_filter_evaluate(&f,NF_INET_PRE_ROUTING,&packet)==(!match || bit!=(variant==4) ? 0 : -EACCES));
            assert(packet.mark==(variant==2 ? (mark&~target.nfmask)^(ct&target.ctmask) : mark));
            assert(packet.ctmark==(variant==0 ? (ct&~target.ctmask)^target.ctmark
                : variant==1 ? (ct&~target.ctmask)^(mark&target.nfmask) : ct));
        }
        free(rule.wire);
    }
    puts("PASS xt connmark: masked set/save/restore, independent marks and inverted matching");
}
static void compat_addrtype(void) {
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_FORWARD,.policy=NF_DROP};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1};
    assert(md_nft_compat_supported(NFPROTO_IPV4,"addrtype",1,0));
    assert(!md_nft_compat_supported(NFPROTO_IPV4,"addrtype",0,0));
    for (unsigned variant=0;variant<4;++variant) {
        struct packet p;
        struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=o.size,entry,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"match",6));
        assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
        assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,"addrtype",9));
        uint32_t revision=htonl(1);
        assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
        struct xt_addrtype_info_v1 info={.source=XT_ADDRTYPE_UNICAST,.dest=XT_ADDRTYPE_LOCAL,
            .flags=variant==1 ? XT_ADDRTYPE_INVERT_DEST : 0};
        if (variant==2) info.flags|=XT_ADDRTYPE_LIMIT_IFACE_IN;
        if (variant==3) info.dest|=XT_ADDRTYPE_BROADCAST;
        assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,&info,sizeof(info)));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,entry));
        struct md_filter_rule rule={.chain=2};
        int error=md_nft_expressions(&f,1,&rule,o.data+start,o.size-start);
        if (variant>=2) { assert(error==-EOPNOTSUPP); continue; }
        assert(!error);
        rule.expressions[rule.count++]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        f.rules=&rule; f.rule_count=1;
        for (unsigned source=RTN_UNICAST;source<=RTN_LOCAL;++source)
            for (unsigned dest=RTN_UNICAST;dest<=RTN_LOCAL;++dest) {
                struct md_filter_packet flow={.destination=md_ip_any(AF_INET),.source_fib_type=source,.fib_type=dest};
                int accepted=source==RTN_UNICAST && ((dest==RTN_LOCAL)!=!!variant);
                assert(md_filter_evaluate(&f,NF_INET_FORWARD,&flow)==(accepted ? 0 : -EACCES));
            }
        free(rule.wire); f.rules=NULL; f.rule_count=0;
    }
    puts("PASS xt addrtype: shared route classification and unsupported scopes rejected");
}
static void compat_revisions(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_identity root=md_identity_new(0,0);
    assert(n);
    const char *names[]={"DNAT","SNAT","MASQUERADE","MARK","tcp","udp","comment","mark","addrtype","conntrack"};
    unsigned revisions[]={0,0,0,2,0,0,0,1,1,3};
    for (unsigned i=0;i<sizeof(names)/sizeof(*names);++i) for (unsigned variant=0;variant<4;++variant) {
        struct packet p,reply;
        struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
        unsigned family=variant==3 ? NFPROTO_IPV6 : NFPROTO_IPV4;
        const char *name=variant==2 ? "unknown" : names[i];
        uint32_t revision=htonl(variant==1 ? 32 : revisions[i]),target=htonl(i<4);
        add(&o,(NFNL_SUBSYS_NFT_COMPAT<<8)|NFNL_MSG_COMPAT_GET,NLM_F_ACK,family);
        assert(!md_network_message_attribute(&o,NFTA_COMPAT_NAME,name,strlen(name)+1));
        assert(!md_network_message_attribute(&o,NFTA_COMPAT_REV,&revision,4));
        assert(!md_network_message_attribute(&o,NFTA_COMPAT_TYPE,&target,4));
        if (variant==1 || variant==2 || (variant==3 && i<2))
            assert(result(n,&root,&o)==(variant==2 ? -ENOENT : -EPROTONOSUPPORT));
        else {
            long size=run(n,&root,&o,&reply,NULL);
            struct nlmsghdr *h=(void *)reply.data;
            assert(NLMSG_OK(h,size) && h->nlmsg_type==((NFNL_SUBSYS_NFT_COMPAT<<8)|NFNL_MSG_COMPAT_GET));
            h=NLMSG_NEXT(h,size);
            assert(NLMSG_OK(h,size) && h->nlmsg_type==NLMSG_ERROR && !((struct nlmsgerr *)NLMSG_DATA(h))->error);
        }
    }
    md_identity_release(&root); md_network_namespace_release(n);
    puts("PASS xt revision negotiation: absent extension differs from unsupported revision");
}
static void packet_route_matches(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    unsigned other;
    assert(!md_network_interface_add(n,MD_LINK_BRIDGE,"reverse-test",&other));
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV4};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.policy=NF_DROP};
    struct md_filter_rule rule={.chain=2};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
    n->filter=&f;
    for (unsigned kind=0;kind<2;++kind) for (unsigned flags=0;flags<(kind ? 16U : 2U);++flags) {
        const char *name=kind ? "rpfilter" : "pkttype";
        assert(md_nft_compat_supported(NFPROTO_IPV4,name,0,0));
        struct packet packet;
        struct md_network_message o={.data=packet.data,.capacity=sizeof(packet.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,NFPROTO_IPV4);
        size_t start=o.size,entry,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"match",6));
        assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
        assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,name,strlen(name)+1));
        uint32_t revision=0;
        assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
        unsigned char info[8]={0};
        if (kind) info[0]=flags;
        else { struct xt_pkttype_info type={.pkttype=PACKET_HOST,.invert=(int)flags}; memcpy(info,&type,sizeof(type)); }
        assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,info,sizeof(info)));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,entry));
        rule=(struct md_filter_rule){.chain=2};
        assert(!md_nft_expressions(&f,1,&rule,o.data+start,o.size-start));
        rule.expressions[rule.count++]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        for (unsigned wrong=0;wrong<2;++wrong) {
            struct md_filter_packet p={.source=md_ip_v4(htonl(0xc0000201)),.destination=md_ip_loopback(AF_INET),
                .protocol=IPPROTO_UDP,.state=8,.packet_type=wrong ? PACKET_MULTICAST : PACKET_HOST};
            struct md_network_flow_path path;
            int allowed=kind ? ((!wrong || (flags&XT_RPFILTER_LOOSE))!=!!(flags&XT_RPFILTER_INVERT))
                : ((!wrong)!=!!flags);
            assert(md_network_flow_route(n,&p,1,wrong ? other : 2,&path)==(allowed ? 0 : -EACCES));
        }
        free(rule.wire);
    }
    rule=(struct md_filter_rule){.chain=2,.count=3,.expressions={
        {.code=MD_FILTER_FIB,.key=NFT_FIB_RESULT_OIF,.operation=NFTA_FIB_F_SADDR|NFTA_FIB_F_IIF|NFTA_FIB_F_PRESENT,.size=4},
        {.code=MD_FILTER_COMPARE,.operation=NFT_CMP_EQ,.size=4,.value={1}},
        {.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT}}};
    for (unsigned wrong=0;wrong<2;++wrong) {
        struct md_filter_packet p={.source=md_ip_v4(htonl(0xc0000201)),.destination=md_ip_loopback(AF_INET),.protocol=IPPROTO_UDP,.state=8};
        struct md_network_flow_path path;
        assert(md_network_flow_route(n,&p,1,wrong ? other : 2,&path)==(wrong ? -EACCES : 0));
    }
    n->filter=NULL; md_network_namespace_release(n);
    puts("PASS packet type and reverse-path predicates: wire parsing, inversion, loose/strict routing and nft fib presence");
}
static void scoped_reverse_path(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(n);
    unsigned first,second;
    assert(!md_network_interface_add(n,MD_LINK_VETH,"rp-source",&first));
    assert(!md_network_interface_add(n,MD_LINK_VETH,"rp-input",&second));
    struct md_network_interface *source=md_network_interface_find(n,first,NULL);
    struct md_network_interface *input=md_network_interface_find(n,second,NULL);
    source->info.flags|=IFF_UP; input->info.flags|=IFF_UP;
    md_network_interface_up(source); md_network_interface_up(input);
    struct md_ip_address multicast=md_ip_any(AF_INET6);
    multicast.bytes[0]=0xff; multicast.bytes[1]=2; multicast.bytes[15]=2;
    struct md_ip_address peer=md_network_interface_source(source,multicast);
    struct md_ip_address local=md_network_interface_source(input,multicast);
    assert(md_ip_linklocal(peer) && md_ip_linklocal(local) && !md_ip_equal(peer,local));
    struct md_network_filter_table table={.handle=1,.family=NFPROTO_IPV6};
    struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_PRE_ROUTING,.policy=NF_DROP};
    struct md_filter_rule rule={.chain=2,.count=2,.expressions={
        {.code=MD_FILTER_REVERSE_PATH}, {.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT}}};
    struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
    n->filter=&f;
    for (unsigned flags=0;flags<16;++flags) for (unsigned own=0;own<2;++own) {
        rule.expressions[0].key=flags;
        struct md_filter_packet packet={.source=own ? local : peer,.destination=multicast,.protocol=IPPROTO_ICMPV6};
        int accepted=(!own || !!(flags&XT_RPFILTER_ACCEPT_LOCAL))!=!!(flags&XT_RPFILTER_INVERT);
        assert(md_network_flow_packet_policy(n,input,&packet,1,16)==(accepted ? 0 : -EACCES));
    }
    n->filter=NULL; md_network_namespace_release(n);
    puts("PASS IPv6 reverse path: link-local scope, strict/loose, local-address admission and inversion");
}
static void compat_icmp(void) {
    for (unsigned ipv6=0;ipv6<2;++ipv6) for (unsigned invert=0;invert<2;++invert)
        for (unsigned wildcard=0;wildcard<2;++wildcard) {
        unsigned family=ipv6 ? NFPROTO_IPV6 : NFPROTO_IPV4,protocol=ipv6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP;
        const char *name=ipv6 ? "icmp6" : "icmp";
        assert(md_nft_compat_supported(family,name,0,0));
        assert(!md_nft_compat_supported(ipv6 ? NFPROTO_IPV4 : NFPROTO_IPV6,name,0,0));
        struct md_network_filter_table table={.handle=1,.family=family};
        struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_DROP};
        struct md_filter_rule rule={.chain=2};
        struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        struct packet packet;
        struct md_network_message o={.data=packet.data,.capacity=sizeof(packet.data)};
        add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,family);
        size_t start=o.size,entry,data;
        assert(!md_network_message_nest(&o,NFTA_LIST_ELEM,&entry));
        assert(!md_network_message_attribute(&o,NFTA_EXPR_NAME,"match",6));
        assert(!md_network_message_nest(&o,NFTA_EXPR_DATA,&data));
        assert(!md_network_message_attribute(&o,NFTA_MATCH_NAME,name,strlen(name)+1));
        uint32_t revision=0;
        assert(!md_network_message_attribute(&o,NFTA_MATCH_REV,&revision,4));
        unsigned char info[8]={wildcard ? 255 : (ipv6 ? 128 : 8),1,3,invert};
        assert(!md_network_message_attribute(&o,NFTA_MATCH_INFO,info,sizeof(info)));
        assert(!md_network_message_end_nest(&o,data));
        assert(!md_network_message_end_nest(&o,entry));
        assert(!md_nft_expressions(&f,1,&rule,o.data+start,o.size-start));
        rule.expressions[rule.count++]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_ACCEPT};
        assert(!md_filter_validate(&f));
        for (unsigned type=0;type<256;++type) for (unsigned code=0;code<5;++code) {
            struct md_filter_packet p={.source=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET),
                .destination=md_ip_loopback(ipv6 ? AF_INET6 : AF_INET),.protocol=protocol,
                .transport={type,code},.transport_size=4};
            int match=(!ipv6 && wildcard) || (type==info[0] && code>=1 && code<=3);
            assert(md_filter_evaluate(&f,NF_INET_LOCAL_IN,&p)==((match!=!!invert) ? 0 : -EACCES));
            p.protocol=IPPROTO_UDP;
            assert(md_filter_evaluate(&f,NF_INET_LOCAL_IN,&p)==-EACCES);
            p.protocol=protocol; p.transport_size=1;
            assert(md_filter_evaluate(&f,NF_INET_LOCAL_IN,&p)==-EACCES);
        }
        free(rule.wire);
    }
    puts("PASS xt ICMP: family/revision admission, type/code ranges, IPv4 wildcard, inversion and short payloads");
}
static void reject_tcp(void) {
    for (unsigned ipv6=0;ipv6<2;++ipv6) for (unsigned compat=0;compat<2;++compat) {
        struct md_network_filter_table table={.handle=1,.family=ipv6 ? NFPROTO_IPV6 : NFPROTO_IPV4};
        struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_ACCEPT};
        struct md_filter_rule rule={.chain=2,.count=1,.expressions={{.code=MD_FILTER_TRANSPORT,
            .transport={.protocol=IPPROTO_TCP,.source={0,65535},.destination={0,65535}}}}};
        struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        struct packet p;
        struct md_network_message out={.data=p.data,.capacity=sizeof(p.data)};
        add(&out,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,table.family);
        size_t start=out.size;
        size_t entry,data;
        assert(!md_network_message_nest(&out,NFTA_LIST_ELEM,&entry));
        const char *name=compat ? "target" : "reject";
        assert(!md_network_message_attribute(&out,NFTA_EXPR_NAME,name,strlen(name)+1));
        assert(!md_network_message_nest(&out,NFTA_EXPR_DATA,&data));
        uint32_t mode=htonl(NFT_REJECT_TCP_RST);
        size_t type_offset=out.size+sizeof(struct nlattr);
        if (compat) {
            uint32_t zero=0,info[2]={ipv6 ? IP6T_TCP_RESET : IPT_TCP_RESET,0};
            assert(!md_network_message_attribute(&out,NFTA_TARGET_NAME,"REJECT",7));
            assert(!md_network_message_attribute(&out,NFTA_TARGET_REV,&zero,4));
            assert(!md_network_message_attribute(&out,NFTA_TARGET_INFO,info,sizeof(info)));
        } else {
            unsigned char unused=0;
            assert(!md_network_message_attribute(&out,NFTA_REJECT_TYPE,&mode,4));
            assert(!md_network_message_attribute(&out,NFTA_REJECT_ICMP_CODE,&unused,1));
        }
        assert(!md_network_message_end_nest(&out,data));
        assert(!md_network_message_end_nest(&out,entry));
        assert(!md_nft_expressions(&f,1,&rule,out.data+start,out.size-start));
        assert(rule.count==2 && rule.expressions[1].code==MD_FILTER_REJECT && !md_filter_validate(&f));
        struct md_filter_packet packet={.destination=md_ip_any(ipv6 ? AF_INET6 : AF_INET),.protocol=IPPROTO_TCP};
        assert(md_filter_evaluate(&f,NF_INET_LOCAL_IN,&packet)==-ECONNREFUSED);
        packet.protocol=IPPROTO_UDP;
        assert(!md_filter_evaluate(&f,NF_INET_LOCAL_IN,&packet));
        chain.hook=NF_INET_PRE_ROUTING;
        assert(md_filter_validate(&f)==-EOPNOTSUPP);
        chain.hook=NF_INET_LOCAL_IN;
        rule.expressions[0].transport.protocol=IPPROTO_UDP;
        assert(md_filter_validate(&f)==-EOPNOTSUPP);
        rule.count=3;
        rule.expressions[2]=rule.expressions[1];
        rule.expressions[1]=(struct md_filter_expression){.code=MD_FILTER_COMPARE,
            .operation=NFT_CMP_EQ,.size=1,.value={IPPROTO_TCP}};
        rule.expressions[0]=(struct md_filter_expression){.code=MD_FILTER_META,.key=NFT_META_L4PROTO,.size=1};
        assert(!md_filter_validate(&f));
        rule.expressions[0]=(struct md_filter_expression){.code=MD_FILTER_PAYLOAD,
            .key=NFT_PAYLOAD_NETWORK_HEADER,.aux=ipv6 ? 6 : 9,.size=1};
        assert(!md_filter_validate(&f));
        table.family=NFPROTO_INET;
        assert(md_filter_validate(&f)==-EOPNOTSUPP);
        table.family=ipv6 ? NFPROTO_IPV6 : NFPROTO_IPV4;
        free(rule.wire);
        struct md_filter_rule unsupported={0};
        if (!compat) {
            /* ICMPX is an inet-table operation, not an IPv4/IPv6 wire type. */
            uint32_t unsupported_mode=htonl(NFT_REJECT_ICMPX_UNREACH);
            memcpy(out.data+type_offset,&unsupported_mode,4);
            assert(md_nft_expressions(&f,1,&unsupported,out.data+start,out.size-start)==-EOPNOTSUPP);
        }
    }
    puts("PASS nft/xt TCP REJECT: IPv4/IPv6, immediate error, UDP exclusion and hook/family validation");
}
static void reject_icmp(void) {
    for(unsigned family=0;family<3;++family) for(unsigned compat=0;compat<(family==2 ? 1U : 2U);++compat) {
        unsigned families[]={NFPROTO_IPV4,NFPROTO_IPV6,NFPROTO_INET};
        struct md_network_filter_table table={.handle=1,.family=families[family]};
        struct md_filter_chain chain={.table=1,.handle=2,.hook=NF_INET_LOCAL_IN,.policy=NF_ACCEPT};
        struct md_filter_rule rule={.chain=2};
        struct md_network_filter f={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        for(unsigned code=0;code<16;++code) {
            struct packet p;
            struct md_network_message out={.data=p.data,.capacity=sizeof(p.data)};
            add(&out,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWRULE,0,table.family);
            size_t start=out.size,entry,data;
            assert(!md_network_message_nest(&out,NFTA_LIST_ELEM,&entry));
            const char *name=compat ? "target" : "reject";
            assert(!md_network_message_attribute(&out,NFTA_EXPR_NAME,name,strlen(name)+1));
            assert(!md_network_message_nest(&out,NFTA_EXPR_DATA,&data));
            unsigned kind=family==2 ? MD_REJECT_ICMPX : family ? MD_REJECT_ICMP6 : MD_REJECT_ICMP4;
            int valid=md_network_reject_valid(kind,code);
            if(compat) {
                static const int v4[]={IPT_ICMP_NET_UNREACHABLE,IPT_ICMP_HOST_UNREACHABLE,
                    IPT_ICMP_PROT_UNREACHABLE,IPT_ICMP_PORT_UNREACHABLE,-1,-1,-1,-1,-1,
                    IPT_ICMP_NET_PROHIBITED,IPT_ICMP_HOST_PROHIBITED,-1,-1,IPT_ICMP_ADMIN_PROHIBITED};
                static const int v6[]={IP6T_ICMP6_NO_ROUTE,IP6T_ICMP6_ADM_PROHIBITED,
                    IP6T_ICMP6_NOT_NEIGHBOUR,IP6T_ICMP6_ADDR_UNREACH,IP6T_ICMP6_PORT_UNREACH,
                    IP6T_ICMP6_POLICY_FAIL,IP6T_ICMP6_REJECT_ROUTE};
                uint32_t info[2]={valid ? (unsigned)(family ? v6[code] : v4[code]) : 999,0},zero=0;
                assert(!md_network_message_attribute(&out,NFTA_TARGET_NAME,"REJECT",7));
                assert(!md_network_message_attribute(&out,NFTA_TARGET_REV,&zero,4));
                assert(!md_network_message_attribute(&out,NFTA_TARGET_INFO,info,sizeof(info)));
            } else {
                uint32_t type=htonl(family==2 ? NFT_REJECT_ICMPX_UNREACH : NFT_REJECT_ICMP_UNREACH);
                unsigned char wire_code=code;
                assert(!md_network_message_attribute(&out,NFTA_REJECT_TYPE,&type,4));
                assert(!md_network_message_attribute(&out,NFTA_REJECT_ICMP_CODE,&wire_code,1));
            }
            assert(!md_network_message_end_nest(&out,data));
            assert(!md_network_message_end_nest(&out,entry));
            int error=md_nft_expressions(&f,1,&rule,out.data+start,out.size-start);
            assert(error==(valid ? 0 : -EOPNOTSUPP));
            if(valid) {
                assert(rule.count==1 && !md_filter_validate(&f));
                for(unsigned v6=0;v6<2;++v6) for(unsigned tcp=0;tcp<2;++tcp) {
                    struct md_filter_packet packet={.destination=md_ip_any(v6 ? AF_INET6 : AF_INET),
                        .protocol=tcp ? IPPROTO_TCP : IPPROTO_UDP};
                    error=md_filter_evaluate(&f,NF_INET_LOCAL_IN,&packet);
                    if(family!=2 && family!=v6) assert(!error && !packet.rejection.type);
                    else {
                        assert(error<0 && packet.rejection.type==(v6 ? MD_REJECT_ICMP6 : MD_REJECT_ICMP4));
                        assert(error==md_network_reject_error(packet.rejection));
                    }
                    /* A later accepted evaluation must not retain stale refusal metadata. */
                    assert(!md_filter_evaluate(NULL,NF_INET_LOCAL_IN,&packet) && !packet.rejection.type);
                }
                chain.hook=NF_INET_PRE_ROUTING; assert(md_filter_validate(&f)==-EOPNOTSUPP);
                chain.hook=NF_INET_LOCAL_IN;
                free(rule.wire); rule=(struct md_filter_rule){.chain=2};
            }
        }
    }
    puts("PASS nft/xt ICMP REJECT: code/family admission, ICMPX normalization, TCP/UDP and hook validation");
}
static struct md_filter_route_result reroute_probe(void *context,const struct md_filter_packet *p,unsigned flags) {
    unsigned *calls=context; ++*calls;
    assert(flags==(NFTA_FIB_F_DADDR|NFTA_FIB_F_MARK) && p->mark==0x1200);
    return (struct md_filter_route_result){.interface=7,.type=RTN_UNICAST,.name="marked0"};
}
static void native_mark_updates(void) {
    for (unsigned family=AF_INET;family<=AF_INET6;family+=AF_INET6-AF_INET) {
        struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
        struct md_filter_packet packet={.destination=md_ip_any(family),.destination_port=htons(443),
            .source_port=htons(32800),.protocol=IPPROTO_TCP,.state=8};
        assert(!md_network_profile_ip(family==AF_INET ? "192.0.2.1" : "2001:db8::1",&packet.destination));
        struct md_network_flow_path path;
        assert(!md_network_flow_route(n,&packet,0,0,&path) && !path.mediated);
        struct md_filter_chain chain={.handle=1,.hook=NF_INET_LOCAL_OUT,.policy=NF_ACCEPT};
        struct md_filter_rule rule={.chain=1,.count=1,.expressions={{.code=MD_FILTER_META_SET,.constant=1}}};
        unsigned mark=0x1200,mask=UINT32_MAX;
        memcpy(rule.expressions[0].value,&mark,4); memcpy(rule.expressions[0].mask,&mask,4);
        struct md_network_filter_table table={.family=family==AF_INET ? NFPROTO_IPV4 : NFPROTO_IPV6};
        struct md_network_filter filter={.tables=&table,.count=1,.chains=&chain,.chain_count=1,.rules=&rule,.rule_count=1};
        assert(!md_network_flow_admits(n,&filter,&path));
        memmove(n->rules+2,n->rules+1,(n->rule_count-1)*sizeof(*n->rules)); ++n->rule_count;
        n->rules[1]=(struct md_network_rule){.source=md_ip_any(family),.destination=md_ip_any(family),
            .priority=10,.mark=mark,.mask=mask,.action=FR_ACT_PROHIBIT};
        assert(md_network_flow_admits(n,&filter,&path)==-EBUSY);
        memmove(n->rules+1,n->rules+2,(--n->rule_count-1)*sizeof(*n->rules));
        rule.count=3;
        rule.expressions[0].code=MD_FILTER_CT_SET;
        rule.expressions[1]=(struct md_filter_expression){.code=MD_FILTER_CONNMARK_MATCH,.key=mark,.aux=mask};
        rule.expressions[2]=(struct md_filter_expression){.code=MD_FILTER_VERDICT,.operation=NF_DROP};
        assert(md_network_flow_admits(n,&filter,&path)==-EBUSY);
        rule.expressions[1].key=mark+1;
        assert(!md_network_flow_admits(n,&filter,&path));
        assert(!path.original.mark && !path.translated.ctmark);
        md_network_namespace_release(n);
    }
    puts("PASS native mark updates: same route admitted, marked denial and conntrack filter rejected, admission is side-effect free");
}
static void route_chains(void) {
    for (unsigned family=NFPROTO_IPV4;family<=NFPROTO_IPV6;family+=NFPROTO_IPV6-NFPROTO_IPV4) {
        struct md_network_filter filter={0};
        assert(!md_network_filter_add(&filter,family,"mangle",NULL,0));
        for (unsigned hook=0;hook<NF_INET_NUMHOOKS;++hook) {
            struct packet wire,reply;
            struct md_network_message o={.data=wire.data,.capacity=sizeof(wire.data)};
            struct md_network_message result={.data=reply.data,.capacity=sizeof(reply.data)};
            add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWCHAIN,NLM_F_CREATE|NLM_F_ECHO,family);
            assert(!md_network_message_attribute(&o,NFTA_CHAIN_TABLE,"mangle",7));
            assert(!md_network_message_attribute(&o,NFTA_CHAIN_NAME,"OUTPUT",7));
            assert(!md_network_message_attribute(&o,NFTA_CHAIN_TYPE,"route",6));
            size_t start;
            assert(!md_network_message_nest(&o,NFTA_CHAIN_HOOK,&start));
            attribute_u32(&o,NFTA_HOOK_HOOKNUM,hook); attribute_u32(&o,NFTA_HOOK_PRIORITY,-150);
            assert(!md_network_message_end_nest(&o,start));
            int error=md_nft_objects(&filter,o.message,43,&result);
            assert(error==(hook==NF_INET_LOCAL_OUT ? 0 : -EOPNOTSUPP));
            if (!error) assert(filter.chain_count==1 && filter.chains[0].reroute && !filter.chains[0].nat && result.size);
        }
        struct md_filter_rule rule={.chain=filter.chains[0].handle,.count=1,
            .expressions={{.code=MD_FILTER_META_SET,.key=NFT_META_MARK,.constant=1}}};
        unsigned mark=0x1200,mask=UINT32_MAX;
        memcpy(rule.expressions[0].value,&mark,sizeof(mark)); memcpy(rule.expressions[0].mask,&mask,sizeof(mask));
        filter.rules=&rule; filter.rule_count=1;
        unsigned calls=0;
        struct md_filter_packet p={.source=md_ip_any(family==NFPROTO_IPV4 ? AF_INET : AF_INET6),
            .destination=md_ip_any(family==NFPROTO_IPV4 ? AF_INET : AF_INET6),.route=reroute_probe,.route_context=&calls};
        assert(!md_filter_evaluate(&filter,NF_INET_LOCAL_OUT,&p) && calls==1 && p.output==7 && !strcmp(p.output_name,"marked0"));
        assert(!md_filter_evaluate(&filter,NF_INET_LOCAL_OUT,&p) && calls==1);
        free(filter.chains); free(filter.tables);
    }
    puts("PASS route chains: OUTPUT-only admission, IPv4/IPv6 and rerouting only changed marks");
}
void test_network_nft(void) {
    native_mark_updates();
    route_chains();
    optional_expression_data();
    batch_validation();
    data_maps();
    reject_tcp();
    reject_icmp();
    compat_icmp();
    packet_route_matches();
    scoped_reverse_path();
    compat_revisions();
    compat_transport();
    compat_nat();
    compat_masquerade();
    nft_source_port_range();
    compat_comment();
    compat_conntrack();
    compat_mark();
    compat_connmark();
    compat_addrtype();
    program();
    fib_hook_validation();
    verdict_maps();
    interval_sets();
    policy_counters();
    reverse_policy();
    nat_hook_order();
    native_fib_publication();
    local_output_publication();
    native_nat_update();
    native_disjoint_update();
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    struct md_network_namespace *child=md_network_namespace_copy(n,NULL);
    struct md_identity root=md_identity_new(0,0),user=md_identity_new(1000,1000);
    struct packet p,reply;
    struct md_network_message o={.data=p.data,.capacity=sizeof(p.data)};
    assert(n && child && !n->filter && md_network_filter_generation(n->filter)==1);

    add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_GETGEN,0,AF_UNSPEC);
    assert(result(n,&user,&o)==-EPERM);
    long length=run(n,&root,&o,&reply,NULL);
    struct nlmsghdr *h=(void *)reply.data;
    assert(length>0 && h->nlmsg_type==((NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWGEN));
    assert(ntohs(((struct nfgenmsg *)NLMSG_DATA(h))->res_id)==1);
    struct nlattr *a=(void *)((struct nfgenmsg *)NLMSG_DATA(h)+1);
    uint32_t generation; memcpy(&generation,a+1,sizeof(generation));
    assert(a->nla_type==NFTA_GEN_ID && ntohl(generation)==1);

    o.size=0; table(&o,NFT_MSG_GETTABLE,NLM_F_DUMP,AF_UNSPEC,NULL);
    length=run(n,&root,&o,&reply,NULL); h=(void *)reply.data;
    assert(length>0 && h->nlmsg_type==NLMSG_DONE);
    o.size=0; table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,NFPROTO_INET,"test");
    assert(result(n,&root,&o)==-EINVAL && !n->filter);
    o.size=0; begin(&o,1);
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE|NLM_F_EXCL,NFPROTO_INET,"test");
    end(&o);
    assert(result(n,&user,&o)==-EPERM && !n->filter);
    struct md_network_publication refusal={.publish=fail_publish};
    assert(run(n,&root,&o,&reply,&refusal)==-EAGAIN && !n->filter);
    assert(md_network_nft(n,&root,o.data,o.size,43,0,reply.data,0,NULL)==-EMSGSIZE && !n->filter);
    assert(!result(n,&root,&o));
    assert(n->filter && n->filter->count==1 && n->filter->generation==2);
    assert(n->filter->tables[0].handle==1 && !child->filter);
    assert(result(n,&root,&o)==-ERESTART && n->filter->generation==2);

    o.size=0; begin(&o,2);
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,NFPROTO_IPV4,"second");
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE|NLM_F_EXCL,NFPROTO_INET,"test");
    end(&o);
    assert(result(n,&root,&o)==-EEXIST && n->filter->count==1 && n->filter->next_handle==2);
    /* Batch callers wait for every operation's sequence, even on rollback. */
    o.size=0; begin(&o,2);
    ((struct nlmsghdr *)o.data)->nlmsg_flags|=NLM_F_ACK;
    uint32_t sequences[4]={1};
    sequences[1]=table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE|NLM_F_ECHO,NFPROTO_IPV4,"rolled-back")->nlmsg_seq;
    sequences[2]=table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE|NLM_F_EXCL,NFPROTO_INET,"test")->nlmsg_seq;
    sequences[3]=table(&o,NFT_MSG_GETTABLE,0,NFPROTO_INET,"test")->nlmsg_seq;
    end(&o);
    length=run(n,&root,&o,&reply,NULL); h=(void *)reply.data;
    int errors[]={0,0,-EEXIST,-EINVAL};
    for (unsigned i=0;i<4;++i) {
        assert(NLMSG_OK(h,length) && h->nlmsg_type==NLMSG_ERROR && h->nlmsg_seq==sequences[i]);
        assert(((struct nlmsgerr *)NLMSG_DATA(h))->error==errors[i]);
        h=NLMSG_NEXT(h,length);
    }
    assert(!length && n->filter->count==1 && n->filter->generation==2 && n->filter->next_handle==2);
    o.size=0; begin(&o,2);
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,NFPROTO_IPV4,"second");
    assert(result(n,&root,&o)==-EINVAL && n->filter->count==1);
    end(&o);
    assert(!result(n,&root,&o) && n->filter->count==2 && n->filter->generation==3);

    o.size=0; table(&o,NFT_MSG_GETTABLE,NLM_F_DUMP,AF_UNSPEC,NULL);
    length=run(n,&root,&o,&reply,NULL); h=(void *)reply.data;
    assert(length>0 && h->nlmsg_type==((NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWTABLE));
    assert(h->nlmsg_flags&NLM_F_MULTI);
    h=NLMSG_NEXT(h,length);
    assert(NLMSG_OK(h,length) && h->nlmsg_type==((NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWTABLE));
    h=NLMSG_NEXT(h,length);
    assert(NLMSG_OK(h,length) && h->nlmsg_type==NLMSG_DONE);
    length=run(child,&root,&o,&reply,NULL); h=(void *)reply.data;
    assert(length>0 && h->nlmsg_type==NLMSG_DONE);

    o.size=0; begin(&o,3);
    table(&o,NFT_MSG_DELTABLE,0,NFPROTO_INET,"test");
    add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWFLOWTABLE,NLM_F_CREATE|NLM_F_ACK,NFPROTO_INET);
    end(&o);
    assert(result(n,&root,&o)==-EOPNOTSUPP && n->filter->count==2 && n->filter->generation==3);
    o.size=0; begin(&o,3); table(&o,NFT_MSG_DELTABLE,0,AF_UNSPEC,NULL); end(&o);
    assert(!result(n,&root,&o) && !n->filter->count && n->filter->generation==4);

    o.size=0; begin(&o,4);
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,NFPROTO_INET,"test");
    assert(!md_network_message_attribute(&o,NFTA_TABLE_NAME,"duplicate",10));
    end(&o);
    assert(result(n,&root,&o)==-EINVAL && !n->filter->count);
    o.size=0; begin(&o,4);
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,NFPROTO_INET,"test");
    uint32_t flags=htonl(NFT_TABLE_F_DORMANT);
    assert(!md_network_message_attribute(&o,NFTA_TABLE_FLAGS,&flags,sizeof(flags)));
    end(&o);
    assert(result(n,&root,&o)==-EOPNOTSUPP && !n->filter->count);

    /* Large batches use the same transaction, not a 4 KiB per-message limit. */
    o.size=0; begin(&o,4);
    for (int i=0;i<150;++i) {
        char name[32]; snprintf(name,sizeof(name),"table-%d",i);
        table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE,NFPROTO_INET,name);
    }
    end(&o); assert(o.size>4096);
    assert(!result(n,&root,&o) && n->filter->count==150);

    /* Mutation echoes and subsequent queries expose the same wrapped generation. */
    n->filter->generation=UINT32_MAX;
    o.size=0; begin(&o,UINT32_MAX);
    table(&o,NFT_MSG_NEWTABLE,NLM_F_CREATE|NLM_F_EXCL|NLM_F_ECHO,NFPROTO_INET,"wrapped");
    end(&o);
    length=run(n,&root,&o,&reply,NULL); h=(void *)reply.data;
    assert(length>0 && h->nlmsg_type==((NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWTABLE));
    assert(ntohs(((struct nfgenmsg *)NLMSG_DATA(h))->res_id)==1 && n->filter->generation==1);
    assert(result(n,&root,&o)==-ERESTART && n->filter->generation==1);
    o.size=0; add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_GETGEN,0,AF_UNSPEC);
    length=run(n,&root,&o,&reply,NULL); h=(void *)reply.data;
    assert(length>0 && ntohs(((struct nfgenmsg *)NLMSG_DATA(h))->res_id)==1);
    a=(void *)((struct nfgenmsg *)NLMSG_DATA(h)+1);
    memcpy(&generation,a+1,sizeof(generation));
    assert(a->nla_type==NFTA_GEN_ID && ntohl(generation)==1);

    /* Kernel queue framing and capabilities are identical to the other netlink
     * owners, including poll readiness and retained creation namespace. */
    struct md_fd_object *socket=NULL; int fd=-1;
    assert(!md_network_socket_open(child,&root,AF_NETLINK,SOCK_RAW,NETLINK_NETFILTER,&socket,&fd));
    o.size=0; add(&o,(NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_GETGEN,0,AF_UNSPEC);
    unsigned long args[6]={0};
    assert(md_network_socket_call(socket,&root,SYS_sendto,args,o.data,o.capacity,o.size,92,0,NULL,NULL)==(long)o.size);
    struct { struct md_network_packet envelope; struct packet payload; } received;
    /* EVENT_WAIT: the queued response must arrive within the fixture alarm. */
    assert(read(fd,&received,sizeof(received))>0 && !received.envelope.group);
    h=(void *)((char *)&received+sizeof(struct md_network_packet));
    assert(h->nlmsg_type==((NFNL_SUBSYS_NFTABLES<<8)|NFT_MSG_NEWGEN) && h->nlmsg_pid==92);
    close(fd); md_fd_object_release(socket);
    md_identity_release(&root); md_identity_release(&user);
    md_network_namespace_release(child); md_network_namespace_release(n);
    puts("PASS nftables: isolated catalogue, generation, atomic batches, rollback, permissions, explicit unsupported operations and netlink carrier");
}
